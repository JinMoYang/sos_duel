package com.example.activeperception

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.os.Build
import android.os.SystemClock
import com.example.activeperception.acquire.CandidateSource
import com.example.activeperception.acquire.Detection
import com.example.activeperception.acquire.Detector
import com.example.activeperception.acquire.Grid
import com.example.activeperception.acquire.CrossExposureRouter
import com.example.activeperception.acquire.RoutingDecision
import com.example.activeperception.acquire.RawFrame
import com.example.activeperception.acquire.BurstAlign
import com.example.activeperception.acquire.NaeFeatures
import com.example.activeperception.acquire.NaeSnap
import com.example.activeperception.acquire.NaeTrainer
import com.example.activeperception.acquire.NelderMead
import com.example.activeperception.acquire.NeuralAeNet
import com.example.activeperception.acquire.RegimeClassifier
import com.example.activeperception.acquire.ShinMetric
import com.example.activeperception.acquire.Signal
import com.example.activeperception.acquire.V3Cause as V
import com.example.activeperception.acquire.Duel
import com.example.activeperception.acquire.HowToSense
import com.example.activeperception.acquire.SparseFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Budget probe ([MeasurementController.runBudgetProbe]): reads per queued burst, outputs per
 *  pattern, newest reads kept for the analysis thread, and the lane patterns tried. */
private const val BUDGET_BURST = 6
private const val BUDGET_OUTPUTS = 12
private const val BUDGET_RING = 8
private val BUDGET_PATTERNS = listOf(listOf(1), listOf(2), listOf(4), listOf(6), listOf(1, 1), listOf(2, 1),
    listOf(2, 2), listOf(3, 3), listOf(1, 1, 1), listOf(2, 2, 2), listOf(1, 1, 1, 1), List(6) { 1 })
private typealias BudgetRead = Pair<RawFrame, RawSensorCapturer.RawMeta>

/** Additional-exposure check ([MeasurementController.runAddl]): reads per queued burst, bursts
 *  queued (a new key setting reaches the sensor within about ADDL_BURST x ADDL_QUEUED reads),
 *  newest reads kept, and the output period. */
private const val ADDL_BURST = 3
private const val ADDL_QUEUED = 3
private const val ADDL_RING = 10
private const val ADDL_PERIOD_MS = 100L

/** Fast PhysSweep ([MeasurementController.runPhysSweepFast]): sweep cells visited a window, next to
 *  the output's read (the detector fits 4 images in a 100 ms window). */
private const val PHYS_FAST_CANDIDATES = 3

/**
 * Who chooses (iso, exposure) in the AE / AE_quant modes.
 * PHONE is the HAL's CONTROL_AE_MODE_ON — vendor-tuned and closed. CUSTOM_BRIGHTNESS is
 * [CustomAeBrightness], which is deterministic and reproducible. Quantization in AE_quant
 * is identical either way; only the upstream decision differs.
 */
enum class AeStrategy { PHONE, CUSTOM_BRIGHTNESS }

fun AeStrategy.tag(): String = when (this) {
    AeStrategy.PHONE -> "phone"
    AeStrategy.CUSTOM_BRIGHTNESS -> "custom"
}

/**
 * Drives every measurement mode (Fixed | AE | AE_quant | Proposed | Verify | VerifyProbe |
 * Bench | IsoDiag) over the RAW capturer, the detector, and the acquire core. Runs on a
 * worker thread — capture and inference both block.
 *
 * Conventions shared by all modes:
 *  - the detector floors at conf 0.01, keeping a tail for the offload signal
 *  - selection re-thresholds at [selectConf]
 *  - the same on-device YOLOv8n runs everywhere
 *
 * Per-run file layout is in [MeasurementLogger].
 */
class MeasurementController(
    private val raw: RawSensorCapturer,
    private val detector: Detector<Bitmap>,
    private val grid: Grid,
    private val sensors: SensorDataManager,
    private val logger: MeasurementLogger,
    private val health: DeviceHealthMonitor,
    /** Operating confidence cutoff, above the detector's 0.01 decode floor. Applied to
     *  sum_conf in frames.csv and to Proposed's selection. Set from the UI, recorded in
     *  manifest as detector.select_conf_threshold. */
    private val selectConf: Float = 0.25f,
    private val colorPipeline: ColorPipeline = ColorPipeline.ORIGINAL_GAIN_SRGB
) {
    @Volatile var running = false; private set
    fun stop() { running = false }

    /** The canonical per-pass frame index: the `f` that names img/frame_XXXX.jpg and fills
     *  frames.csv `idx`. Published by [logFrame] just before each onFrame callback so
     *  external listeners can tag their work with a joinable id. */
    @Volatile var lastFrameIdx: Int = -1; private set
    @Volatile var lastRoutingDecision: RoutingDecision = RoutingDecision.KEEP_LOCAL; private set

    /** Per-pass totals for the post-run banner. Vehicle classes only — the detector already
     *  filters. The at-floor count includes the 0.01 tail, which is mostly noise. */
    @Volatile var detectionTotalAboveThresh: Int = 0; private set
    @Volatile var detectionTotalAtFloor: Int = 0; private set
    @Volatile var totalFramesLogged: Int = 0; private set

    private val regime = RegimeClassifier()
    private var passStartMs: Long = 0L
    /** The run's measured Limo speed, armed with the IMU log; into summary.json at the end. */
    private val speedMeter = LimoSpeedMeter()
    /** Recent gyro samples, fed with the IMU log, for Duel2's "gyro" speed source. */
    private val gyroWindow = com.example.activeperception.acquire.GyroWindow()

    private fun now() = System.nanoTime()
    private fun ms(t0: Long) = (now() - t0) / 1e6
    private fun log2Of(x: Double) = kotlin.math.ln(x) / kotlin.math.ln(2.0)

    /** Elapsed between two captured marks, for spans that do not end at "now" — the
     *  capture round trip is bracketed mid-step, so [ms] cannot express it. */
    private fun msBetween(t0: Long, t1: Long) = (t1 - t0) / 1e6

    // ---------- manifest + per-frame helpers shared by all modes ----------

    private fun headers() = listOf(
        "idx", "ts", "frame_number", "time_since_start", "lap", "heading_angle",
        "regime", "yaw_rate", "lux", "accel",
        "method", "chosen_cell", "gain", "exposure_us",
        "iso_req", "iso_applied", "exp_req", "exp_applied",
        "frame_duration_ns", "apply_delay_frames", "pending_commands",
        "black_r", "black_g1", "black_g2", "black_b", "row_stride", "pixel_stride",
        "wb_r", "wb_g", "wb_b",
        "ccm_00", "ccm_01", "ccm_02", "ccm_10", "ccm_11", "ccm_12",
        "ccm_20", "ccm_21", "ccm_22",
        "battery_temp_c", "thermal_status", "pss_kb",
        "K", "is_burst", "formation_ms", "infer_ms", "total_ms",
        "n_det", "sum_conf", "img_path",
        // Proposed only: which path picked the cell — conf | safe_cell | entropy |
        // laplacian | tenengrad | crete_roffet. Blank elsewhere.
        "tie_break",
        // AE_quant only: AE's continuous choice before quantization. The gain/exposure_us
        // columns above carry the quantized cell that was actually captured. Blank elsewhere.
        "ae_iso", "ae_exp_us",
        // Resource accounting. Appended rather than slotted next to their relatives so
        // every existing column keeps its index and older analysis scripts still parse.
        // capture_ms: the capture round trip (request issued -> frames in hand). It used
        // to be recoverable only as total-formation-infer, a residual that silently
        // absorbed bitmap alloc, JPEG enqueue, logging and GC — reading actuation cost out
        // of that residual is what produced a wrong lag estimate, so it is measured now.
        // cpu_pct: percent of ONE core (200 = two cores busy), whole process, 2 s window.
        // graphics_kb: GPU/texture/EGL memory, the one that actually moves with K.
        "capture_ms", "cpu_pct", "java_heap_kb", "native_heap_kb", "graphics_kb", "cpu_mhz",
        // Energy. Run energy = (batt_charge_uah at start - at end) x mean batt_voltage_mv,
        // which is far steadier than integrating batt_current_ua. batt_plugged MUST be 0
        // for any of it to mean anything: on USB the phone charges while the run drains
        // it, so a tethered run has to be rejected, not averaged in.
        "batt_charge_uah", "batt_current_ua", "batt_voltage_mv", "batt_pct", "batt_plugged"
    ) + StepClock.HEADERS + V3_FRAME_HEADERS +
        // Lap of the run this frame belongs to, 0 = the run's first lap, counted by the lap
        // session that switches runs (blank outside a lap session: -1).
        listOf("run_lap")


    /** proposed_v3cause's per-frame summary (device spec 6.4); blank for every other method.
     *  capture_ms / formation_ms / infer_ms are the shared columns above. */
    private val V3_FRAME_HEADERS = listOf("base_again", "weak_max", "k", "plan", "applied",
        "delta_d", "delta_l", "n_images", "n_captures", "t_capture_start", "t_capture2_start",
        "t_capture_end", "t_output", "flow_ms", "fusion_ms", "plan_ms")

    /** Per-step timeline shared by every method that logs through [logFrame]. */
    private val stepClock = StepClock()

    /** False for the short latency check that measures how much image saving costs: no
     *  JPEG is written, everything else is logged as usual. Recorded in the manifest. */
    @Volatile var saveImages: Boolean = true
    /** Parallel capture (2026-10-29, a "par_" run): capture and analysis on separate threads at
     *  10 fps ([RawSensorCapturer.startParallel]). Every capture call of the baselines then takes
     *  the newest fresh read of a continuous stream; their decision rules are unchanged. Set by
     *  the activity before the run starts. */
    @Volatile var parallelCapture: Boolean = false
    /** A method with its own capture thread (runAddl, runBudgetProbe): parallel whatever
     *  [parallelCapture] says; only the manifest reads it. */
    private var ownCaptureThread = false

    private fun saveImage(name: String, bmp: Bitmap): String =
        if (saveImages) logger.saveJpegAsync(name, bmp) else ""

    /** [methodParams] carries the mode's own configuration so a run variant is identifiable
     *  from the manifest alone, without parsing the run directory name. Nested so the
     *  top-level schema stays stable as modes come and go. */
    private fun writeManifest(method: String, isGtReference: Boolean,
                              captureWidth: Int, captureHeight: Int,
                              methodParams: JSONObject? = null) {
        val m = JSONObject()
        m.put("method", method)
        m.put("is_gt_reference", isGtReference)
        m.put("pass_start_ts", System.currentTimeMillis())
        m.put("device_model", Build.MODEL)
        m.put("os_sdk", Build.VERSION.SDK_INT)
        if (methodParams != null) m.put("method_params", methodParams)
        if (ExperimentSession.lighting.isNotEmpty()) m.put("lighting", ExperimentSession.lighting)
        if (ExperimentSession.session.isNotEmpty()) m.put("experiment_session", ExperimentSession.session)
        if (ExperimentSession.limoSpeed.isNotEmpty()) m.put("limo_speed_setting", ExperimentSession.limoSpeed)
        m.put("capture_structure", if (parallelCapture || ownCaptureThread) "parallel: capture thread streams, one output every 100 ms " +
            "from the newest fresh reads" else "serial: capture, then analysis, then the next capture")
        m.put("grid", JSONObject().apply {
            put("gains", JSONArray(grid.gains.toTypedArray()))
            put("exposures_us", JSONArray(grid.exposuresUs.toTypedArray()))
            put("n_gain", grid.nGain); put("n_shutter", grid.nShutter)
            // Uniform digital multiplier folded into gainRatio; see Grid.
            put("digital_boost", grid.digitalBoost)
        })
        m.put("capture_resolution", JSONObject().apply {
            put("width", captureWidth); put("height", captureHeight)
            put("downsampled_in_decode", true)
            // The RAW size above is NOT the space the logged boxes live in — decode()
            // bins each 4x4 block to a 2x2 quad and demosaic2x2 halves again. Every xyxy in dets.jsonl and
            // candidate_dets.jsonl is in the detector_input space recorded here.
            put("detector_input_width", raw.bitmapWidth)
            put("detector_input_height", raw.bitmapHeight)
        })
        m.put("image_pipeline", JSONObject()
            .put("decode", "each 4x4 sensor block -> one 2x2 Bayer quad; every site is the " +
                "mean of the block's 4 same-colour pixels (all pixels used); black removed, " +
                "signed, not clipped")
            .put("resample", "area average of whole quads in linear values, then gain, " +
                "then one clip to [0, white], then sRGB LUT")
            .put("white_balance", "none")
            .put("fixed_focus_diopters", raw.fixedFocusDiopters.toDouble())
            .put("lens_min_focus_diopters", raw.minFocusDiopters.toDouble())
            .put("save_images", saveImages))
        m.put("cfa_pattern", raw.cfaPattern)
        m.put("white_level", raw.maxDn)
        m.put("sync_max_latency", raw.syncMaxLatency)
        m.put("timestamp_source", raw.timestampSource)
        m.put("platform", platformTag)
        m.put("pairing", JSONObject()
            .put("drive_role", driveRole)
            .put("device_id", deviceId)
            .put("block_s", blockSeconds)
            .put("block_idx", blockIndex)
            .put("repeats", playlistRepeats)
            .put("rows", playlistRows))
        m.put("sensor_orientation", raw.sensorOrientation)
        m.put("sensor_iso_range", JSONObject()
            .put("min", raw.sensorIsoMin).put("max", raw.sensorIsoMax))
        m.put("sensor_max_analog_iso", raw.maxAnalogIso)
        m.put("image_formation", JSONObject().apply {
            put("pipeline", colorPipeline.tag)
            put("orientation_applied_clockwise_degrees", raw.sensorOrientation)
            put("white_balance", if (colorPipeline == ColorPipeline.RAYNEO_AWB_CCM)
                "per-frame CaptureResult.COLOR_CORRECTION_GAINS" else "not applied")
            put("color_transform", if (colorPipeline == ColorPipeline.RAYNEO_AWB_CCM)
                "per-frame CaptureResult.COLOR_CORRECTION_TRANSFORM" else "not applied")
            put("order", if (colorPipeline == ColorPipeline.RAYNEO_AWB_CCM)
                "black subtract -> burst sum -> demosaic -> digital gain -> AWB -> CCM -> sRGB OETF -> rotate"
                else "black subtract -> burst sum -> demosaic -> digital gain -> sRGB OETF -> rotate")
        })
        m.put("rayneo_profile", JSONObject().apply {
            put("camera_id", "0")
            put("measured_frame_period_ns", 33_329_000L)
            put("measured_setting_delay_frames", "9-10")
            put("setting_guard_frames", 0)
            put("setting_acceptance", "first timestamp-paired RAW with matching exposure/ISO metadata")
            put("metadata_verified_actuation", true)
            put("virtual_window", if (colorPipeline == ColorPipeline.ORIGINAL_GAIN_SRGB)
                "first_n_original_sos" else "centered_rayneo_legacy")
        })
        // Reads the loaded detector's actual configuration (device-profiled: COCO5 head on
        // RayNeo, 80-class + vehicle filter on the S25). Boxes in dets.jsonl are post-NMS,
        // post-class-filter, post-conf_floor.
        val tfl = detector as? com.example.activeperception.acquire.TfliteYoloDetector
        m.put("detector", JSONObject().apply {
            put("model", tfl?.modelName ?: "unknown")
            put("img_size", 640)
            put("conf_floor", 0.01)
            put("iou_thresh", 0.45)
            put("max_det_per_frame", 100)
            if (tfl?.usesClassIdMap == true) {
                put("class_filter", "coco5_head")
                put("allowed_classes", JSONArray(listOf(41, 40, 46, 5, 60)))
                put("class_names", JSONArray(listOf("cup", "wine glass", "banana", "bus", "dining table")))
            } else {
                put("class_filter", "allowed_set")
                put("allowed_classes", JSONArray(tfl?.configuredAllowed?.sorted() ?: emptyList<Int>()))
            }
            put("num_classes", tfl?.configuredNumClasses ?: -1)
            put("select_conf_threshold", selectConf)
            // sense/proxy.py V3 canon: vehicle-class, IoU-deduped sumconf.
            put("selection_signal", "v3_veh_dedup")
            put("signal_iou_match", Signal.IOU_MATCH)
            put("signal_classes", JSONArray(Signal.VEH_CLASSES.toTypedArray()))
            put("backend_by_batch",
                (detector as? com.example.activeperception.acquire.TfliteYoloDetector)
                    ?.backendSummary ?: "unknown")
        })
        logger.manifest(m)
    }

    /** V3 selection signal (sense/proxy.py canon): vehicle classes, dedup, then sum.
     *  Replaces the raw over-threshold sum used before 2026-08-27; recorded in the
     *  manifest as selection_signal so runs are attributable to their signal version. */
    private fun sumConf(dets: List<Detection>, sel: Float = selectConf) =
        Signal.sumConfV3(dets, sel)

    // ---------- shared optimized pipeline ----------

    /**
     * The capture/formation/inference path validated for the production controller, made
     * available to every other mode.
     *
     * Until 2026-08-28 only [runExp55FinalAdaptiveP5] used it; the baselines and the
     * Fixed/AE modes still ran the legacy route (one-shot `capture` with its per-call
     * listener arm and drain, `bitmapFromRaw`, Bitmap-preprocess `detectBatch`). On the
     * LIMO passes that cost 200-500 ms of capture round-trip per step, so per-step latency
     * across policies compared PIPELINE GENERATIONS rather than methods — the baselines
     * looked 3-6x slower for reasons unrelated to their exposure policy. Anything whose
     * cost is reported next to the controller's must run through here.
     *
     * Physical-visit policies (Fixed, AE, PhysSweep, ShinNM, NeuralAe) capture AT their
     * cell and form the single identity cell, exactly as [bitmapFromRaw] did; virtual-probe
     * policies (ShinSelect, NaeCollect) capture one base burst and form the whole grid.
     */
    private inner class FastPipeline(
        val tfl: com.example.activeperception.acquire.TfliteYoloDetector
    ) {
        val source = ParallelRawCandidateSource(grid, raw,
            colorPipeline = ColorPipeline.ORIGINAL_GAIN_SRGB, burstWindow = BurstWindow.FIRST_N)
        /** The cell whose formation is a plain demosaic of a capture already taken at the
         *  wanted setting (gain ratio 1 x digitalBoost, burst 1) — [bitmapFromRaw]'s cell. */
        val identityCell = intArrayOf(grid.cell(0, 0))

        fun open(onStatus: (String) -> Unit) {
            raw.configureDecodeThreads(4)
            raw.startFastCapture()
            if (parallelCapture) raw.startParallel()
            onStatus("warming FP16 GPU B=1/3/9…")
            tfl.warmUpAllBatches()
        }

        fun close() {
            runCatching { raw.stopFastCapture() }
            runCatching { source.shutdown() }
        }

        fun capture(exposureUs: Int, iso: Int, nBurst: Int = 1): List<RawFrame> =
            raw.captureFast(exposureUs, iso, nBurst)

        /** Fused RAW->tensor formation plus one batched inference over [cells]. */
        fun formAndDetect(frames: List<RawFrame>, cells: IntArray):
            Pair<Exp21TensorResult, List<List<Detection>>> {
            val tensor = source.formFusedNativeTensor(frames, cells)
            return tensor to tfl.detectTensorBatchOptimized(tensor.batch)
        }

        /** GPU run + decode of the last [formAndDetect]; preprocess is 0 on this path
         *  because formation wrote the tensor directly. */
        val inferMs: Double get() = tfl.lastRunMs + tfl.lastDecodeMs

        fun bitmap(tensor: Exp21TensorResult, lane: Int): Bitmap =
            source.selectedTensorBitmap(tensor, lane)

        /** Boxes remapped into the 640 tensor space the preview bitmap lives in. */
        fun previewDets(dets: List<Detection>, tensor: Exp21TensorResult, lane: Int) =
            source.detectionsForTensorPreview(dets, tensor, lane)
    }

    /** Runs [body] with the optimized pipeline open, tearing it down on every exit path. */
    private fun <T> withFastPipeline(onStatus: (String) -> Unit, body: (FastPipeline) -> T): T {
        val tfl = detector as? com.example.activeperception.acquire.TfliteYoloDetector
            ?: error("optimized pipeline requires TfliteYoloDetector")
        val p = FastPipeline(tfl)
        p.open(onStatus)
        try { return body(p) } finally { p.close() }
    }

    private fun detJson(d: Detection): JSONObject {
        val xy = JSONArray()
        for (v in d.xyxy) xy.put(v.toDouble())
        return JSONObject().apply {
            put("xyxy", xy); put("conf", d.confidence.toDouble()); put("cls", d.classId)
        }
    }

    /** Full detection list including the sub-threshold tail. `method` keeps rows separable
     *  when a mode writes more than one per frame. */
    private fun writeDets(frame: Int, method: String, cell: Int, dets: List<Detection>) {
        val arr = JSONArray()
        for (d in dets) arr.put(detJson(d))
        logger.jsonl("dets", JSONObject().apply {
            put("frame", frame); put("method", method); put("cell", cell); put("dets", arr)
        })
    }

    /** Phone AE's setting when a run starts: the start point of the methods defined to start
     *  from the camera's current exposure (Duel, ShinNM, Neural-AE). Runs AE frames on the
     *  legacy one-shot path — so it must come before the fast pipeline opens — until two
     *  frames in a row agree within 5% in ISO x exposure (at least 6, at most 20 frames; AE
     *  can arrive far from the scene, e.g. from a previous dark run). */
    private class AeStart(val iso: Int, val expUs: Long, val frames: Int, val settled: Boolean) {
        fun json(): JSONObject = JSONObject().put("iso", iso).put("exposure_us", expUs)
            .put("frames", frames).put("settled", settled)
    }

    private fun meterAeStart(onStatus: (String) -> Unit): AeStart? {
        onStatus("metering the start setting with phone AE…")
        var iso = -1; var expUs = -1L; var prev = -1.0; var n = 0
        while (n < 20) {
            n++
            val m = runCatching { raw.captureAe(1) }.getOrNull()
                ?.let { raw.lastMeta.firstOrNull() } ?: continue
            iso = m.appliedIso; expUs = m.appliedExpUs
            val product = iso.toDouble() * expUs
            val settled = prev > 0 && kotlin.math.abs(product / prev - 1.0) <= 0.05
            prev = product
            if (n >= 6 && settled) return AeStart(iso, expUs, n, true)
        }
        return if (iso > 0 && expUs > 0) AeStart(iso, expUs, n, false) else null
    }

    /** Cell of [g] nearest to a continuous (iso, exposure), each axis by log distance;
     *  values beyond the lattice land on its edge. */
    private fun nearestCell(g: Grid, iso: Int, expUs: Long): Int {
        val i = iso.coerceAtLeast(1).toDouble(); val e = expUs.coerceAtLeast(1L).toDouble()
        val gi = g.gains.indices.minByOrNull { kotlin.math.abs(kotlin.math.ln(i / g.gains[it])) } ?: 0
        val sj = g.exposuresUs.indices.minByOrNull {
            kotlin.math.abs(kotlin.math.ln(e / g.exposuresUs[it])) } ?: 0
        return g.cell(gi, sj)
    }

    /** One imu.csv row per sensor sample, tagged by `source`, carrying only that sensor's
     *  channels and its own ns timestamp. Merging channels into one row would attach a stale
     *  timestamp to whichever sensor did not fire, which offline orientation fusion can't undo. */
    private fun wireImuLog() {
        logger.csv("imu", listOf("ts", "source", "ax", "ay", "az", "gx", "gy", "gz", "lux"))
        sensors.onAccelSample = { ts, ax, ay, az ->
            logger.row("imu", listOf(ts, "accel", ax, ay, az, "", "", "", ""))
        }
        speedMeter.start(sensors.totalYawDeg, System.currentTimeMillis())
        ExperimentSession.lastLapS = Double.NaN
        gyroWindow.clear()
        sensors.onGyroSample = { ts, gx, gy, gz ->
            logger.row("imu", listOf(ts, "gyro", "", "", "", gx, gy, gz, ""))
            speedMeter.add(sensors.currentYawRate)
            gyroWindow.add(ts, gx.toDouble(), gy.toDouble(), gz.toDouble())
        }
        sensors.onLuxSample = { ts, lux ->
            logger.row("imu", listOf(ts, "light", "", "", "", "", "", "", lux))
        }
    }

    private fun unwireImuLog() {
        sensors.onAccelSample = null
        sensors.onGyroSample = null
        sensors.onLuxSample = null
    }

    /**
     * Shared per-frame row writer.
     *
     * Two different ISO conventions land in the same row on purpose: `gain` is EFFECTIVE ISO
     * (physical × digitalBoost, matching the saved bitmap), while `iso_req` / `iso_applied`
     * stay physical so the camera metadata is faithful. digital_boost is in the manifest, so
     * physical = effective / boost.
     *
     * Note that `iso_req` / `exp_req` describe the capture that produced the pixels, which in
     * Proposed is the base capture — not the virtual cell named by `gain` / `exposure_us`.
     */
    private fun logFrame(
        idx: Int, method: String, chosenCell: Int, gainVal: Int, expUs: Int,
        k: Int, isBurst: Boolean, formationMs: Double, inferMs: Double, totalMs: Double,
        dets: List<Detection>, imgPath: String, tieBreak: String = "",
        aeIso: Int = -1, aeExpUs: Int = -1,
        /** v2 passes nominal rung ISO with no digital-boost concept behind it. */
        applyBoost: Boolean = true,
        /** Capture round trip in ms. Defaulted so call sites that do not time the capture
         *  separately keep compiling; they log it blank rather than a wrong number. */
        captureMs: Double = -1.0,
        /** proposed_v3cause's columns, in [V3_FRAME_HEADERS] order; blank otherwise. */
        v3Extra: List<Any?>? = null,
        /** False when the method writes its own, richer dets.jsonl line. */
        writeDetsLine: Boolean = true,
        /** The capture whose metadata the row describes, when it is not raw.lastMeta[0]. */
        metaOverride: RawSensorCapturer.RawMeta? = null
    ) {
        // Published before the mode's onFrame callback fires, so listeners see the matching id.
        lastFrameIdx = idx
        detectionTotalAtFloor += dets.size
        detectionTotalAboveThresh += dets.count { it.confidence >= selectConf }
        totalFramesLogged += 1
        val m = metaOverride ?: raw.lastMeta.firstOrNull()
        val aeIsoCol = if (aeIso < 0) "" else aeIso.toString()
        val aeExpCol = if (aeExpUs < 0) "" else aeExpUs.toString()
        val healthNow = health.sample()
        logger.row("frames", listOf(
            idx, m?.timestamp ?: -1L, m?.frameNumber ?: -1L,
            "%.3f".format((System.currentTimeMillis() - passStartMs) / 1000.0),
            sensors.getCurrentLap(), "%.3f".format(sensors.currentHeadingAngle),
            regime.update(sensors.currentYawRate),
            sensors.currentYawRate, sensors.currentLux, sensors.currentAccel,
            method, chosenCell, if (applyBoost) effIso(gainVal) else gainVal, expUs,
            m?.requestedIso ?: -1, m?.appliedIso ?: -1,
            m?.requestedExpUs ?: -1L, m?.appliedExpUs ?: -1L,
            m?.frameDurationNs ?: -1L, m?.applyDelayFrames ?: raw.lastApplyDelayFrames ?: -1L,
            raw.pendingCommandCount,
            m?.blackLevels?.getOrNull(0) ?: -1, m?.blackLevels?.getOrNull(1) ?: -1,
            m?.blackLevels?.getOrNull(2) ?: -1, m?.blackLevels?.getOrNull(3) ?: -1,
            m?.rowStrideBytes ?: -1, m?.pixelStrideBytes ?: -1,
            m?.whiteBalance?.getOrNull(0) ?: -1f,
            m?.whiteBalance?.getOrNull(1) ?: -1f,
            m?.whiteBalance?.getOrNull(2) ?: -1f,
            *(m?.cameraToSrgb ?: DoubleArray(9) { -1.0 }).toTypedArray(),
            healthNow.batteryTemperatureC, healthNow.thermalStatus, healthNow.totalPssKb,
            k, if (isBurst) 1 else 0,
            "%.1f".format(formationMs), "%.1f".format(inferMs), "%.1f".format(totalMs),
            dets.size, "%.3f".format(sumConf(dets)), imgPath, tieBreak,
            aeIsoCol, aeExpCol,
            if (captureMs < 0) "" else "%.1f".format(captureMs),
            if (healthNow.cpuPercent < 0) "" else "%.1f".format(healthNow.cpuPercent),
            healthNow.javaHeapKb, healthNow.nativeHeapKb,
            healthNow.graphicsKb, healthNow.cpuMaxMhz,
            if (healthNow.chargeUAh == Long.MIN_VALUE) "" else healthNow.chargeUAh,
            if (healthNow.currentUA == Int.MIN_VALUE) "" else healthNow.currentUA,
            healthNow.voltageMv, healthNow.batteryPct, healthNow.pluggedState,
            *stepClock.columns(StepClock.nowNs()).toTypedArray(),
            *(v3Extra ?: List(V3_FRAME_HEADERS.size) { "" }).map { it ?: "" }.toTypedArray(),
            ExperimentSession.runLap
        ))
        stepClock.reset()
        if (writeDetsLine) writeDets(idx, method, chosenCell, dets)
    }

    // ---------- Fixed ----------

    /** One cell of the v2 lattice every frame, realized as every other lattice method
     *  realizes it ([captureV2Cell]: analog up to the ceiling, the rest digital). The Duel
     *  experiments' reference "fixed_1600_240": the cell sos_cf_v1 found best in hindsight
     *  in all three lightings — a reference, not a method. */
    fun runFixedLattice(isoNominal: Int, expUs: Int, maxFrames: Int,
                        onStatus: (String) -> Unit,
                        onFrame: (Bitmap, List<Detection>, Int /*iso*/, Int /*expUs*/) -> Unit = { _, _, _, _ -> }) {
        val name = "fixed_${isoNominal}_${Math.round(1e6 / expUs)}"
        val phys = minOf(isoNominal, v2AnalogCeil())
        startPass(name, isGtReference = false, methodParams = JSONObject()
            .put("reference", "hindsight-best fixed cell of sos_cf_v1 (not a method)")
            .put("iso", isoNominal).put("analog_iso", phys)
            .put("digital", isoNominal.toDouble() / phys).put("exposure_us", expUs)
            .put("space", "v2_lattice_5x5"))
        withFastPipeline(onStatus) { pipe ->
            var f = 0
            while (running && f < maxFrames) {
                val t0 = now()
                stepClock.begin()
                val r = pipe.captureV2Cell(isoNominal, expUs) ?: break
                val tf = now()
                val (tensor, dets) = r
                stepClock.decided()
                val totalMs = ms(t0)
                val bmp = pipe.bitmap(tensor, 0)
                val path = saveImage("${frameName(f)}_${isoExpTag(isoNominal, expUs)}", bmp)
                logFrame(f, name, -1, isoNominal, expUs, 1, false, ms(tf),
                    pipe.inferMs, totalMs, dets, path, applyBoost = false,
                    captureMs = msBetween(t0, tf))
                onFrame(bmp, pipe.previewDets(dets, tensor, 0), isoNominal, expUs)
                onStatus("Fixed ISO$isoNominal 1/${Math.round(1e6 / expUs)} f=$f ndet=${dets.size} " +
                    "${"%.0f".format(totalMs)}ms")
                f++
            }
        }
        endPass()
    }

    /**
     * Budget probe (2026-10-29, desk test, phone still): what one output can spend when capture
     * and analysis run on separate threads. A capture thread keeps the sensor reading back to back
     * (two bursts of [BUDGET_BURST] reads always queued, ISO 1600 x 1/240); the analysis thread
     * forms outputs from the newest unused reads, back to back, for each pattern of
     * [BUDGET_PATTERNS]: lane sizes, e.g. [2, 1] = a two-read tracking sum and one read, each its
     * own image, detected in one call. Merging as Duel2's track mode, but LK on every tile (an
     * upper bound: Duel2 tracks only the map's and the traces' tiles). Fusion and the setting
     * choice are not run (Duel2 logs their cost). Then the camera stops and the same work runs
     * on held reads, for the costs without capture.
     *
     * Logs budget_raws.csv (every read: sensor time, gap to the previous read, time from exposure
     * end to in hand), budget_outputs.csv (every output: wait, align, form, GPU run, decode,
     * total, latency from the last read's exposure end) and budget_summary.json (medians and
     * 90th percentiles per phase; each pattern's first 2 outputs left out as warm-up).
     */
    fun runBudgetProbe(onStatus: (String) -> Unit) {
        val tfl = detector as? com.example.activeperception.acquire.TfliteYoloDetector
            ?: error("the budget probe requires TfliteYoloDetector")
        val iso = 1600; val us = 4167
        val phys = minOf(iso, v2AnalogCeil())
        val ratio = iso.toDouble() / phys
        ownCaptureThread = true
        startPass("budget_probe", isGtReference = false, withFrames = false, methodParams = JSONObject()
            .put("rule", "capture thread: reads back to back (two bursts of $BUDGET_BURST queued); analysis " +
                "thread: outputs from the newest unused reads, back to back, per lane pattern")
            .put("iso", iso).put("analog_iso", phys).put("exposure_us", us)
            .put("patterns", JSONArray(BUDGET_PATTERNS.map { JSONArray(it) }))
            .put("outputs_per_pattern", BUDGET_OUTPUTS).put("warmup_outputs", 2)
            .put("align", "BurstAlign on every tile (upper bound), 4 threads")
            .put("not_run", "fusion, How-to-Sense choice")
            .put("timestamp_source", raw.timestampSource)
            .put("detector_slots", tfl.backendSummary))
        logger.csv("budget_raws", listOf("phase", "seq", "sensor_ts", "exp_us", "frame_duration_ns",
            "since_prev_ms", "in_hand_ms"))
        logger.csv("budget_outputs", listOf("phase", "idx", "lanes", "n_raw", "n_img", "wait_ms",
            "align_ms", "form_ms", "run_ms", "dec_ms", "total_ms", "since_prev_output_ms",
            "latency_ms", "span_ms", "max_gap_ms", "skipped_reads", "n_dets"))
        val src = ParallelRawCandidateSource(grid, raw,
            colorPipeline = ColorPipeline.ORIGINAL_GAIN_SRGB, burstWindow = BurstWindow.FIRST_N)
        raw.configureDecodeThreads(4)
        raw.startFastCapture()
        onStatus("budget: warming FP16 GPU batches…")
        tfl.warmUpAllBatches()
        val alignPool = Executors.newFixedThreadPool(4)
        val ring = ArrayDeque<BudgetRead>()
        val lock = java.lang.Object()
        val phase = java.util.concurrent.atomic.AtomicReference("capture_only")
        val capturing = java.util.concurrent.atomic.AtomicBoolean(true)
        val capError = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        // Per phase: raw gaps and in-hand delays; output rows for the summary.
        val rawStats = HashMap<String, MutableList<DoubleArray>>()
        val outStats = HashMap<String, MutableList<DoubleArray>>()
        val capThread = Thread({
            val queued = ArrayDeque<RawSensorCapturer.BracketHandle>()
            fun submit() { queued.addLast(raw.beginBracketSequence(List(BUDGET_BURST) { us to phys },
                android.hardware.camera2.CameraDevice.TEMPLATE_STILL_CAPTURE)) }
            var seq = 0; var prevTs = 0L; var failures = 0
            try {
                submit(); submit()
                while (capturing.get() && running) {
                    val h = queued.removeFirst()
                    submit()
                    try {
                        for (i in 0 until h.count) {
                            val (fr, m) = raw.takeBracketFrame(h, i)
                            val inHand = SystemClock.elapsedRealtimeNanos()
                            val since = if (prevTs == 0L) Double.NaN else (m.timestamp - prevTs) / 1e6
                            val inHandMs = (inHand - m.timestamp - m.appliedExpUs * 1000L) / 1e6
                            val ph = phase.get()
                            logger.row("budget_raws", listOf(ph, seq, m.timestamp, m.appliedExpUs,
                                m.frameDurationNs, "%.3f".format(since), "%.2f".format(inHandMs)))
                            synchronized(lock) {
                                rawStats.getOrPut(ph) { ArrayList() } += doubleArrayOf(since, inHandMs)
                                ring.addLast(fr to m)
                                while (ring.size > BUDGET_RING) ring.removeFirst()
                                lock.notifyAll()
                            }
                            prevTs = m.timestamp; seq++
                        }
                        failures = 0
                    } catch (t: Throwable) {
                        android.util.Log.e("BudgetProbe", "burst failed", t)
                        logger.jsonl("budget_errors", JSONObject().put("phase", phase.get()).put("seq", seq)
                            .put("error", android.util.Log.getStackTraceString(t)))
                        raw.abortBracketSequence(h)
                        if (++failures >= 5) throw t
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("BudgetProbe", "capture stopped", t)
                capError.set(t)
            } finally {
                queued.forEach { raw.abortBracketSequence(it) }
                synchronized(lock) { capturing.set(false); lock.notifyAll() }
            }
        }, "budget-capture")

        class Parts(val alignMs: Double, val formMs: Double, val runMs: Double, val decMs: Double, val nDets: Int)
        /** One output's work on [reads] in time order: lanes of [lanes] reads, each lane's later
         *  reads moved back onto its first and summed, every lane detected in one call. */
        fun work(reads: List<BudgetRead>, lanes: List<Int>): Parts {
            val ta = now()
            var at = 0
            val laneFrames = lanes.map { k ->
                val rs = reads.subList(at, at + k).map { it.first }; at += k
                if (rs.size == 1) rs else {
                    val pyr = rs.map { fr -> BurstAlign.pyramid(fr.bayer, fr.width, fr.height, alignPool) }
                    val acc = BurstAlign.accumulate((1 until rs.size).map { j -> BurstAlign.step(pyr[j - 1], pyr[j], alignPool) })
                    listOf(rs[0]) + acc.withIndex().map { (j, m) ->
                        val fr = rs[j + 1]
                        RawFrame(BurstAlign.moveBack(fr.bayer, fr.width, fr.height, m, alignPool), fr.width, fr.height,
                            fr.cfaPattern, fr.maxDn, fr.sensorOrientation, fr.whiteBalance, fr.cameraToSrgb)
                    }
                }
            }
            val alignMs = ms(ta)
            val tf = now()
            val tensor = src.formLanesTensor(laneFrames, DoubleArray(lanes.size) { ratio })
            val formMs = ms(tf)
            val dets = tfl.detectTensorBatchOptimized(tensor.batch)
            return Parts(alignMs, formMs, tfl.lastRunMs, tfl.lastDecodeMs, dets.sumOf { d -> d.count { it.confidence >= 0.25f } })
        }
        fun logOut(ph: String, idx: Int, lanes: List<Int>, waitMs: Double, p: Parts, totalMs: Double,
                   sincePrev: Double, latency: Double, span: Double, maxGap: Double, skipped: Int) {
            logger.row("budget_outputs", listOf(ph, idx, lanes.joinToString("+"), lanes.sum(), lanes.size,
                "%.2f".format(waitMs), "%.2f".format(p.alignMs), "%.2f".format(p.formMs), "%.2f".format(p.runMs),
                "%.2f".format(p.decMs), "%.2f".format(totalMs), "%.2f".format(sincePrev), "%.2f".format(latency),
                "%.2f".format(span), "%.2f".format(maxGap), skipped, p.nDets))
            if (idx >= 2) outStats.getOrPut(ph) { ArrayList() } += doubleArrayOf(waitMs, p.alignMs, p.formMs,
                p.runMs, p.decMs, totalMs, sincePrev, latency, span, maxGap, skipped.toDouble())
        }

        var held: List<BudgetRead> = emptyList()
        try {
            capThread.start()
            onStatus("budget: capture only")
            Thread.sleep(3000)
            var lastUsed = 0L
            for (lanes in BUDGET_PATTERNS) {
                if (!running || capError.get() != null) break
                val r = lanes.sum()
                val ph = "p_" + lanes.joinToString("+")
                phase.set(ph)
                onStatus("budget: $ph")
                var prevOut = 0L
                for (idx in 0 until BUDGET_OUTPUTS) {
                    val t0 = now()
                    // The newest r reads newer than the last output's: same moment, freshest.
                    var skipped = 0
                    val reads: List<BudgetRead> = synchronized(lock) {
                        while (capturing.get() && ring.count { it.second.timestamp > lastUsed } < r) lock.wait(50)
                        val fresh = ring.filter { it.second.timestamp > lastUsed }
                        if (fresh.size < r) emptyList() else { skipped = fresh.size - r; fresh.takeLast(r) }
                    }
                    if (reads.isEmpty()) break
                    val waitMs = ms(t0)
                    lastUsed = reads.last().second.timestamp
                    val p = work(reads, lanes)
                    val outNs = SystemClock.elapsedRealtimeNanos()
                    val last = reads.last().second
                    val gaps = reads.zipWithNext { a, b -> (b.second.timestamp - a.second.timestamp) / 1e6 }
                    logOut(ph, idx, lanes, waitMs, p, ms(t0),
                        if (prevOut == 0L) Double.NaN else (outNs - prevOut) / 1e6,
                        (outNs - last.timestamp - last.appliedExpUs * 1000L) / 1e6,
                        (last.timestamp + last.appliedExpUs * 1000L - reads[0].second.timestamp) / 1e6,
                        gaps.maxOrNull() ?: 0.0, skipped)
                    prevOut = outNs
                }
            }
            held = synchronized(lock) { ring.toList().takeLast(6) }
            capturing.set(false)
            capThread.join(3000)
            raw.stopFastCapture()
            // The same work with the camera idle, on the last reads held in memory.
            if (held.size == 6) for (lanes in listOf(listOf(1), listOf(2), listOf(1, 1, 1), listOf(2, 2, 2), List(6) { 1 })) {
                if (!running) break
                val ph = "held_" + lanes.joinToString("+")
                onStatus("budget: $ph")
                for (idx in 0 until 8) {
                    val t0 = now()
                    val p = work(held.take(lanes.sum()), lanes)
                    logOut(ph, idx, lanes, 0.0, p, ms(t0), Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0)
                }
            }
        } finally {
            capturing.set(false)
            runCatching { capThread.join(3000) }
            runCatching { raw.stopFastCapture() }
            runCatching { src.shutdown() }
            alignPool.shutdown()
        }
        // Summary: medians and 90th percentiles per phase.
        fun q(v: List<Double>, p: Double): Any {
            val s = v.filter { !it.isNaN() }.sorted()
            return if (s.isEmpty()) JSONObject.NULL else Math.round(s[((s.size - 1) * p).toInt()] * 100) / 100.0
        }
        val summary = JSONObject().put("capture_error", capError.get()?.toString() ?: JSONObject.NULL)
        val names = listOf("wait_ms", "align_ms", "form_ms", "run_ms", "dec_ms", "total_ms",
            "since_prev_output_ms", "latency_ms", "span_ms", "max_gap_ms", "skipped_reads")
        for ((ph, rows) in outStats) {
            val o = JSONObject().put("n", rows.size)
            names.forEachIndexed { i, nm -> o.put(nm, JSONObject().put("median", q(rows.map { it[i] }, 0.5)).put("p90", q(rows.map { it[i] }, 0.9))) }
            rawStats[ph]?.let { rs ->
                val gaps = rs.map { it[0] }.filter { !it.isNaN() }
                o.put("raws", JSONObject().put("n", rs.size)
                    .put("gap_ms_median", q(gaps, 0.5))
                    .put("gaps_over_25ms", gaps.count { it > 25.0 })
                    .put("in_hand_ms_median", q(rs.map { it[1] }, 0.5)).put("in_hand_ms_p90", q(rs.map { it[1] }, 0.9)))
            }
            summary.put(ph, o)
        }
        rawStats["capture_only"]?.let { rs ->
            val gaps = rs.map { it[0] }.filter { !it.isNaN() }
            summary.put("capture_only", JSONObject().put("raws", JSONObject().put("n", rs.size)
                .put("gap_ms_median", q(gaps, 0.5)).put("gaps_over_25ms", gaps.count { it > 25.0 })
                .put("in_hand_ms_median", q(rs.map { it[1] }, 0.5)).put("in_hand_ms_p90", q(rs.map { it[1] }, 0.9))))
        }
        runCatching { java.io.File(logger.dir, "budget_summary.json").writeText(summary.toString(2)) }
        onStatus("budget: done" + (capError.get()?.let { " (capture error: ${it.message})" } ?: ""))
        endPass()
    }

    /**
     * Additional-exposure check (2026-10-29): how detection grows with the reads one output
     * spends on its moment, capture and analysis on separate threads at a fixed 10 fps. A capture
     * thread keeps the sensor reading back to back at the key exposure's setting; every
     * [ADDL_PERIOD_MS] the analysis thread forms one output from the newest reads:
     * - single exposure frame: the newest read alone (the key exposure);
     * - additional exposure frame (the frame after one whose image 0 had a weak trace): the newest
     *   [extra] + 1 consecutive reads at the key setting, the first the key exposure, the rest its
     *   additional exposures. [merge]: all moved onto the key ([BurstAlign], LK on the map's and
     *   the previous traces' tiles) and summed into one image, "key setting x k reads aligned" (the
     *   light of k reads, the blur of one; not a longer shutter). Otherwise each read is its own
     *   image, its boxes moved to the key's time by LK on the detector-input images, read to read,
     *   and the detections fused (every kept cluster).
     * Additional exposures share the key's setting, so they are already captured when a frame is
     * planned: nothing has to be requested. Fewer than [extra] when the key's reads would not fit
     * the window's sensor time ([sensorHoldMs]: a 1/30 key takes one, a 1/15 key none). The key's setting: How-to-Sense on the key read alone,
     * gyro curve, one read's blur (Duel2 sg's rule), applied at the capture thread's next burst
     * and chosen again only once reads at the requested setting arrive. The trace is image 0's
     * weak detections: the key alone, or under [merge] the key merged with its additional
     * exposures (the key alone is not detected there).
     *
     * Records as Duel2 (frames.csv, dets.jsonl with out flags, candidate_dets roles base /
     * neighbor, decisions.jsonl), so verify_eval.py reads it, plus addl_frames.csv (every frame:
     * kind, reads, timings, how late the output clock ran).
     */
    fun runAddl(extra: Int, merge: Boolean, curve: HowToSense.Curve, maxFrames: Int,
                onStatus: (String) -> Unit,
                onFrame: (Bitmap, List<Detection>, Int, Int) -> Unit = { _, _, _, _ -> },
                /** Every frame an additional exposure frame, trace or not (run name suffix _every; also
                 *  desk tests, which have no traces). */
                everyFrameAdditional: Boolean = false,
                /** Desk tests only: start at this lattice cell instead of the phone AE's. */
                startCellOverride: Int? = null) {
        val tfl = detector as? com.example.activeperception.acquire.TfliteYoloDetector
            ?: error("the additional-exposure check requires TfliteYoloDetector")
        require(extra in 0..5)
        val name = "addl_$extra" + (if (extra == 0) "" else if (merge) "_merge" else "_sep") +
            if (everyFrameAdditional && extra > 0) "_every" else ""
        val lattice = Grid(gains = Duel.ISOS, exposuresUs = Duel.SHUTTERS_US, digitalBoost = 1.0)
        val ceil = v2AnalogCeil()
        val ae = if (startCellOverride != null) null else meterAeStart(onStatus)
        val (g0, s0) = lattice.indices(startCellOverride
            ?: if (ae != null) nearestCell(lattice, ae.iso, ae.expUs) else lattice.cell(2, 2))
        // The requested key setting; the capture thread reads it at every burst.
        val cell = java.util.concurrent.atomic.AtomicInteger(HowToSense.cell(g0, s0))
        ownCaptureThread = true
        startPass(name, isGtReference = false, methodParams = JSONObject()
            .put("mode", name).put("extra_reads", extra).put("merge", merge)
            .put("every_frame_additional", everyFrameAdditional)
            .put("structure", "capture thread: reads back to back at the key setting (bursts of $ADDL_BURST, " +
                "$ADDL_QUEUED queued); analysis thread: one output every $ADDL_PERIOD_MS ms from the newest reads")
            .put("single_frame", "the newest read alone (key exposure)")
            .put("additional_frame", "after a frame whose image 0 had a weak trace: the newest ${extra + 1} " +
                "consecutive reads at the key setting, the first the key exposure")
            .put("use", if (extra == 0) "none" else if (merge) "every read moved onto the key (BurstAlign, map and " +
                "previous trace tiles) and summed: key setting x k reads aligned" else "each read its own image, " +
                "boxes moved to the key's time by LK read to read, fused (every kept cluster)")
            .put("trace", if (merge) "weak detections in image 0 (in an additional frame the key merged with its " +
                "additional exposures)" else "weak detections in the key image")
            .put("extra_cap", "additional exposures: at most extra_reads, and only as many as fit 100 ms of " +
                "sensor time with the key (1/240-1/60: 3+, 1/30: 1, 1/15: 0)")
            .put("key_rule", "How-to-Sense on the key read alone, gyro curve, one read's blur (Duel2 sg); " +
                "applied at the capture thread's next burst")
            .put("curve", curve.note)
            .put("start", JSONObject().put("ae", ae?.json() ?: JSONObject.NULL)
                .put("iso", Duel.ISOS[g0]).put("shutter_us", Duel.SHUTTERS_US[s0]))
            .put("isos", JSONArray(Duel.ISOS.toList())).put("shutters_us", JSONArray(Duel.SHUTTERS_US.toList()))
            .put("analog_ceiling", ceil).put("out_conf", V.OUT_THR).put("weak_conf", V.WEAK_LO)
            .put("detector", "yolov8n tflite, conf >= 0.05 kept, vehicles for decisions").put("input_size", 640))
        logger.csv("addl_frames", listOf("idx", "kind", "n_reads", "n_images", "iso", "shutter_us",
            "key_ts", "last_end_ts", "t_output", "wait_ms", "align_ms", "form_ms", "infer_ms", "shift_ms",
            "fusion_ms", "choose_ms", "total_ms", "clock_late_ms", "latency_ms", "max_gap_ms", "skipped_reads",
            "n_out", "n_weak", "moved_to_iso", "moved_to_shutter_us"))
        val src = ParallelRawCandidateSource(grid, raw,
            colorPipeline = ColorPipeline.ORIGINAL_GAIN_SRGB, burstWindow = BurstWindow.FIRST_N)
        raw.configureDecodeThreads(4)
        raw.startFastCapture()
        raw.armNativeDecodeValidation()
        onStatus("warming FP16 GPU batches…")
        tfl.warmUpAllBatches()
        val alignPool = Executors.newFixedThreadPool(4)
        val chooseLead = Executors.newSingleThreadExecutor()

        class Read(val frame: RawFrame, val meta: RawSensorCapturer.RawMeta, val cell: Int)
        val ring = ArrayDeque<Read>()
        val lock = java.lang.Object()
        val capturing = java.util.concurrent.atomic.AtomicBoolean(true)
        val capError = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val capThread = Thread({
            val queued = ArrayDeque<Pair<RawSensorCapturer.BracketHandle, Int>>()
            fun submit() {
                val c = cell.get()
                val us = Duel.SHUTTERS_US[c % 5]; val phys = minOf(Duel.ISOS[c / 5], ceil)
                queued.addLast(raw.beginBracketSequence(List(ADDL_BURST) { us to phys },
                    android.hardware.camera2.CameraDevice.TEMPLATE_STILL_CAPTURE) to c)
            }
            var failures = 0
            try {
                repeat(ADDL_QUEUED) { submit() }
                while (capturing.get() && running) {
                    val (h, c) = queued.removeFirst()
                    submit()
                    try {
                        for (i in 0 until h.count) {
                            val (fr, m) = raw.takeBracketFrame(h, i)
                            synchronized(lock) {
                                ring.addLast(Read(fr, m, c))
                                while (ring.size > ADDL_RING) ring.removeFirst()
                                lock.notifyAll()
                            }
                        }
                        failures = 0
                    } catch (t: Throwable) {
                        android.util.Log.e("Addl", "burst failed", t)
                        raw.abortBracketSequence(h)
                        if (++failures >= 5) throw t
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("Addl", "capture stopped", t)
                capError.set(t)
            } finally {
                queued.forEach { raw.abortBracketSequence(it.first) }
                synchronized(lock) { capturing.set(false); lock.notifyAll() }
            }
        }, "addl-capture")

        val map = HowToSense.ObjectMap()
        var prevTrace = false
        var prevTraceCells = BooleanArray(HowToSense.N)
        var lastUsed = 0L
        fun jsonBox(b: DoubleArray) = JSONArray(b.toList())
        fun median(v: List<Double>): Double {
            val s = v.sorted(); val m = s.size
            return if (m % 2 == 1) s[m / 2] else 0.5 * (s[m / 2 - 1] + s[m / 2])
        }
        try {
            capThread.start()
            var f = 0
            var tick = now()
            while (running && f < maxFrames && capError.get() == null) {
                // ---- the output clock: a frame ADDL_PERIOD_MS after the previous one started, at once
                // when that one overran (lateMs: by how much).
                val lead = tick - now()
                if (lead > 0) Thread.sleep(lead / 1_000_000, (lead % 1_000_000).toInt())
                val t0 = now()
                val lateMs = (t0 - tick) / 1e6
                tick = t0 + ADDL_PERIOD_MS * 1_000_000L
                stepClock.begin()
                // ---- 1. reads: the newest 1 or 1 + additional at the newest read's setting, never used
                // before. Additional exposures: [extra], or fewer when the key's reads would not fit
                // the window's sensor time (a 1/30 key: 1, a 1/15 key: none).
                val keyUs = Duel.SHUTTERS_US[synchronized(lock) { ring.lastOrNull()?.cell ?: cell.get() } % 5]
                val fit = maxOf(0, (ADDL_PERIOD_MS / sensorHoldMs(keyUs)).toInt() - 1)
                val additional = extra > 0 && fit > 0 && (prevTrace || everyFrameAdditional)
                val kind = if (additional) "additional" else "single"
                val need = if (additional) 1 + minOf(extra, fit) else 1
                var skipped = 0
                val reads: List<Read> = synchronized(lock) {
                    fun fresh(): List<Read> {
                        val newest = ring.lastOrNull() ?: return emptyList()
                        return ring.filter { it.meta.timestamp > lastUsed && it.cell == newest.cell }
                    }
                    while (capturing.get() && fresh().size < need) lock.wait(20)
                    val fr = fresh()
                    if (fr.size < need) emptyList() else { skipped = fr.size - need; fr.takeLast(need) }
                }
                if (reads.isEmpty()) break
                val waitMs = ms(t0)
                lastUsed = reads.last().meta.timestamp
                val key = reads[0]
                val metas = reads.map { it.meta }
                stepClock.captured(metas)
                val c = key.cell
                val gIdx = c / 5; val sIdx = c % 5
                val iso = Duel.ISOS[gIdx]; val phys = minOf(iso, ceil); val ratio = iso.toDouble() / phys
                val gaps = metas.zipWithNext { a, b -> (b.timestamp - a.timestamp) / 1e6 }

                // ---- 2. images. merge: one lane of every read, moved onto the key; else a lane a read.
                val tA = now()
                val orient = (key.frame.sensorOrientation % 360 + 360) % 360
                fun sensorTiles(cells: BooleanArray): BooleanArray? = when (orient) {
                    0 -> cells.copyOf()
                    180 -> BooleanArray(HowToSense.N) { t -> cells[HowToSense.N - 1 - t] }
                    else -> null
                }
                val mapTiles = HowToSense.flowCells(map.weights())
                val lanes: List<List<RawFrame>> = if (reads.size == 1 || !merge) reads.map { listOf(it.frame) } else {
                    val rs = reads.map { it.frame }
                    val tiles = sensorTiles(BooleanArray(HowToSense.N) { mapTiles[it] || prevTraceCells[it] })
                    val pyr = rs.map { fr -> BurstAlign.pyramid(fr.bayer, fr.width, fr.height, alignPool) }
                    val acc = BurstAlign.accumulate((1 until rs.size).map { j -> BurstAlign.step(pyr[j - 1], pyr[j], alignPool, tiles) })
                    listOf(listOf(rs[0]) + acc.withIndex().map { (j, mv) ->
                        val fr = rs[j + 1]
                        RawFrame(BurstAlign.moveBack(fr.bayer, fr.width, fr.height, mv, alignPool), fr.width, fr.height,
                            fr.cfaPattern, fr.maxDn, fr.sensorOrientation, fr.whiteBalance, fr.cameraToSrgb)
                    })
                }
                val alignMs = ms(tA)
                val n = lanes.size
                val tF = now()
                // The choice reads the key alone: a merged lane 0 gets the key's own picture first
                // (formLanesTensor reuses one buffer per lane count).
                val keyRgb: FloatArray? = if (lanes[0].size > 1) {
                    val kt = src.formLanesTensor(listOf(listOf(key.frame)), doubleArrayOf(ratio))
                    tensorRgbRows(kt, 0, kt.batch.transforms[0].padY.toInt())
                } else null
                val tensor = src.formLanesTensor(lanes, DoubleArray(n) { ratio })
                val formMs = ms(tF)
                stepClock.formed()
                val mp0 = tensor.batch.transforms[0]
                val top = mp0.padY.toInt()
                val rgb = keyRgb ?: tensorRgbRows(tensor, 0, top)
                val pre = chooseLead.submit(java.util.concurrent.Callable {
                    val pic = DoubleArray(HowToSense.W * HowToSense.H)
                    HowToSense.pictureRows(rgb, pic, 0 until HowToSense.H)
                    HowToSense.stats(pic, c)
                })

                // ---- 3. detect, every image in one call.
                val dets = tfl.detectTensorBatchOptimized(tensor.batch)
                stepClock.detected()
                val inferMs = tfl.lastRunMs + tfl.lastDecodeMs
                val per = (0 until n).map { a ->
                    V.perImage(dets[a].filter { it.confidence >= 0.05f }.map { d ->
                        V.D(d.confidence.toDouble(), DoubleArray(4) { d.xyxy[it].toDouble() }, d.classId)
                    })
                }
                val samplers = (0 until n).map { a -> com.example.activeperception.acquire.RawBoxSampler(lanes[a][0],
                    metas[0].blackLevels, metas[0].white.toDouble(), lanes[a].drop(1)) }

                // ---- 4. separate images: boxes to the key's time, then fusion.
                var shiftMs = 0.0; var fusionMs = 0.0
                val shiftLog = JSONArray()
                val outK = ArrayList<List<V.D>>()
                var clusters: List<V.Cluster> = emptyList()
                if (n > 1 && per.any { it.out.isNotEmpty() }) {
                    val tS = now()
                    val grays = (0 until n).map { SparseFlow.pyramid(tensorGrayHalf(tensor, it)) }
                    /** Median per-axis displacement (detector-input px) of [box]'s content from
                     *  image [from] to image [to] (as Duel2's shiftOf). */
                    fun shiftOf(box: DoubleArray, from: List<SparseFlow.Img>, to: List<SparseFlow.Img>): DoubleArray {
                        val gx0 = (box[0] * mp0.scale + mp0.padX) / 2; val gy0 = (box[1] * mp0.scale + mp0.padY) / 2
                        val gx1 = (box[2] * mp0.scale + mp0.padX) / 2; val gy1 = (box[3] * mp0.scale + mp0.padY) / 2
                        val pts = SparseFlow.corners(from[0], gx0, gy0, gx1, gy1, 4)
                        if (pts.isEmpty()) return doubleArrayOf(0.0, 0.0)
                        val tr = SparseFlow.track(to, from, pts).filterNotNull()
                        if (tr.isEmpty()) return doubleArrayOf(0.0, 0.0)
                        return doubleArrayOf(median(tr.map { it[0] }) * 2.0 / mp0.scale, median(tr.map { it[1] }) * 2.0 / mp0.scale)
                    }
                    fun moved(b: DoubleArray, s: DoubleArray) = doubleArrayOf(b[0] + s[0], b[1] + s[1], b[2] + s[0], b[3] + s[1])
                    // Image a's box at the key's time, tracked read to read (a -> a-1 -> ... -> 0):
                    // one read apart a car moves ~1/10 of its box, five reads apart about half.
                    fun shiftToKey(a: Int, b: DoubleArray): DoubleArray {
                        var x = b
                        for (k in a downTo 1) x = moved(x, shiftOf(x, grays[k], grays[k - 1]))
                        return doubleArrayOf(x[0] - b[0], x[1] - b[1])
                    }
                    val lowK = ArrayList<List<V.D>>()
                    outK += per[0].out; lowK += per[0].low
                    for (a in 1 until n) {
                        // Cars and the weak boxes near a car are tracked; other weak boxes never
                        // meet a cluster and take the image's median car shift.
                        val sh = java.util.IdentityHashMap<V.D, DoubleArray>()
                        for (d in per[a].out) sh[d] = shiftToKey(a, d.box)
                        val med = if (sh.isEmpty()) doubleArrayOf(0.0, 0.0)
                            else doubleArrayOf(median(sh.values.map { it[0] }), median(sh.values.map { it[1] }))
                        for (d in per[a].low) if (!sh.containsKey(d)) {
                            val same = per[a].out.firstOrNull { it.box.contentEquals(d.box) }
                            sh[d] = when {
                                same != null -> sh[same]!!
                                per[a].out.any { V.iou(it.box, d.box) >= 0.1 } -> shiftToKey(a, d.box)
                                else -> med
                            }
                        }
                        fun toKey(d: V.D) = V.D(d.conf, moved(d.box, sh[d]!!), d.cls)
                        outK += per[a].out.map(::toKey); lowK += per[a].low.map(::toKey)
                        shiftLog.put(JSONObject().put("image", a).put("n_out", per[a].out.size)
                            .put("median_dx", med[0]).put("median_dy", med[1]))
                    }
                    shiftMs = ms(tS)
                    val tFu = now()
                    // Saturation is read at the key-time box (in the dark it is ~0 either way).
                    clusters = V.fuse((0 until n).map { a ->
                        V.FuseImage(outK[a], lowK[a]) { box -> samplers[a].satFrac(box, ratio, Duel.SAT_SITES) } })
                    fusionMs = ms(tFu)
                }
                val kept = clusters.filter { it.status == "kept" }
                val outDets = if (n == 1) per[0].out.map { d -> Detection(FloatArray(4) { d.box[it].toFloat() }, d.conf.toFloat(), d.cls) }
                    else kept.map { k -> Detection(FloatArray(4) { k.box[it].toFloat() }, k.evidence.toFloat(), k.cls) }
                val tOutputNs = StepClock.nowNs()

                // ---- 5. the key setting for the next frames: map (key time), gyro over the key, curve.
                val tC = now()
                fun toPic(b: DoubleArray) = doubleArrayOf(b[0] * mp0.scale + mp0.padX, b[1] * mp0.scale + mp0.padY - top,
                    b[2] * mp0.scale + mp0.padX, b[3] * mp0.scale + mp0.padY - top)
                for (d in if (n == 1) per[0].out else outK.flatten()) map.add(toPic(d.box))
                val st = pre.get()
                val gyro = gyroWindow.mean(key.meta.timestamp, key.meta.timestamp + key.meta.appliedExpUs * 1_000L)
                val speed = HowToSense.gyroSpeeds(gyro.first, map.sizes())
                val choiceNote = JSONObject().put("gyro", JSONObject().put("w", JSONArray(gyro.first.toList()))
                    .put("samples", gyro.second))
                var moved = false
                if (c != cell.get()) choiceNote.put("rule", "the requested setting has not arrived: keep")
                else {
                    val ch = HowToSense.choose(curve, st, speed, map.weights(), map.sizes(), c, HowToSense.DENS)
                    moved = ch.next != c
                    if (moved) cell.set(ch.next)
                    choiceNote.put("rule", "argmax E, move if the gain exceeds the threshold")
                        .put("e", JSONArray(ch.e.toList()))
                        .put("best", JSONObject().put("iso", Duel.ISOS[ch.best / 5]).put("shutter_us", Duel.SHUTTERS_US[ch.best % 5]))
                        .put("e_gain", ch.e[ch.best] - ch.e[c]).put("threshold", ch.threshold).put("moved", moved)
                        .put("speed_median", median(speed.filter { !it.isNaN() }))
                }
                val chooseMs = ms(tC)
                // ---- 6. the trace: image 0's weak detections; the next frame is additional if any.
                prevTrace = per[0].weak.isNotEmpty()
                prevTraceCells = BooleanArray(HowToSense.N).also { cells ->
                    for (t in per[0].weak) {
                        val b = toPic(t.box)
                        for (gy in maxOf(0, (b[1] / HowToSense.CS).toInt())..minOf(HowToSense.GY - 1, (b[3] / HowToSense.CS).toInt()))
                            for (gx in maxOf(0, (b[0] / HowToSense.CS).toInt())..minOf(HowToSense.GX - 1, (b[2] / HowToSense.CS).toInt()))
                                cells[gy * HowToSense.GX + gx] = true
                    }
                }
                stepClock.decided()
                val totalMs = ms(t0)

                // ---- records.
                val roles = List(n) { if (it == 0) "base" else "neighbor" }
                for (a in 0 until n) {
                    val arr = JSONArray()
                    for (d in dets[a]) {
                        if (d.confidence < 0.05f) continue
                        val box = DoubleArray(4) { d.xyxy[it].toDouble() }
                        arr.put(JSONObject().put("xyxy", jsonBox(box)).put("conf", d.confidence.toDouble())
                            .put("class", d.classId).put("sat", samplers[a].satFrac(box, ratio, Duel.SAT_SITES)))
                    }
                    val m = metas[if (merge) 0 else a]
                    logger.jsonl("candidate_dets", JSONObject().put("frame", f).put("role", roles[a])
                        .put("made_by", if (lanes[a].size == 1) "frame" else "track_sum${lanes[a].size}")
                        .put("n_frames", lanes[a].size).put("iso", iso).put("analog_gain", m.appliedIso)
                        .put("analog_gain_req", m.requestedIso).put("digital_gain", ratio)
                        .put("exposure_us", m.appliedExpUs * lanes[a].size).put("frame_exposure_us", m.appliedExpUs)
                        .put("frame_exposure_us_req", m.requestedExpUs).put("sensor_ts", m.timestamp).put("dets", arr))
                }
                val outArr = JSONArray(); val dropArr = JSONArray()
                if (n == 1) for (d in per[0].out) outArr.put(JSONObject().put("xyxy", jsonBox(d.box))
                    .put("evidence", d.conf).put("class", d.cls).put("from", "base"))
                for (k in clusters) {
                    val o = JSONObject().put("xyxy", jsonBox(k.box)).put("evidence", k.evidence)
                        .put("peak", k.peak).put("class", k.cls).put("n_pic", k.nPic)
                        .put("r", JSONArray(k.r.toList())).put("w", JSONArray(k.w.toList()))
                        .put("out", k.status == "kept")
                    if (k.status == "kept") outArr.put(o) else dropArr.put(o.put("reason", k.status))
                }
                logger.jsonl("dets", JSONObject().put("frame", f).put("method", name)
                    .put("roles", JSONArray(roles)).put("dets", outArr).put("dropped", dropArr).put("shifts", shiftLog))
                logger.jsonl("decisions", JSONObject().put("frame", f).put("mode", name).put("kind", kind)
                    .put("n_reads", reads.size).put("n_images", n)
                    .put("reads_ts", JSONArray(metas.map { it.timestamp }))
                    .put("base", JSONObject().put("g_req", iso).put("g_analog_req", phys).put("g_applied", key.meta.appliedIso)
                        .put("digital", ratio).put("s_req", Duel.SHUTTERS_US[sIdx]).put("s_applied", key.meta.appliedExpUs))
                    .put("base_choice", choiceNote)
                    .put("next_key", JSONObject().put("iso", Duel.ISOS[cell.get() / 5]).put("shutter_us", Duel.SHUTTERS_US[cell.get() % 5]))
                    .put("trace", prevTrace).put("n_weak", per[0].weak.size).put("n_cars", per[0].out.size))
                val last = metas.last()
                val lastEnd = last.timestamp + last.appliedExpUs * 1_000L
                logger.row("addl_frames", listOf(f, kind, reads.size, n, iso, Duel.SHUTTERS_US[sIdx],
                    key.meta.timestamp, lastEnd, tOutputNs, "%.1f".format(waitMs), "%.1f".format(alignMs),
                    "%.1f".format(formMs), "%.1f".format(inferMs), "%.1f".format(shiftMs), "%.1f".format(fusionMs),
                    "%.1f".format(chooseMs), "%.1f".format(totalMs), "%.1f".format(lateMs),
                    "%.1f".format((tOutputNs - lastEnd) / 1e6), "%.1f".format(gaps.maxOrNull() ?: 0.0), skipped,
                    outDets.size, per[0].weak.size,
                    if (moved) Duel.ISOS[cell.get() / 5] else "", if (moved) Duel.SHUTTERS_US[cell.get() % 5] else ""))
                val bmp = src.selectedTensorBitmap(tensor, 0)
                val path = saveImage("${frameName(f)}_base", bmp)
                logFrame(f, name, lattice.cell(gIdx, sIdx), iso, Duel.SHUTTERS_US[sIdx], n, reads.size > 1,
                    formMs, inferMs, totalMs, outDets, path, tieBreak = kind, applyBoost = false,
                    captureMs = waitMs, writeDetsLine = false, metaOverride = key.meta)
                runCatching { onFrame(bmp, src.detectionsForTensorPreview(outDets, tensor, 0), iso, Duel.SHUTTERS_US[sIdx]) }
                onStatus("$name f=$f $kind ${reads.size} reads ISO$iso 1/${HowToSense.DENS[sIdx]} out=${outDets.size} " +
                    "${"%.0f".format(totalMs)}ms")
                f++
            }
        } finally {
            capturing.set(false)
            runCatching { capThread.join(3000) }
            runCatching { chooseLead.shutdownNow(); alignPool.shutdownNow() }
            runCatching { raw.stopFastCapture() }
            runCatching { src.shutdown() }
        }
        capError.get()?.let { onStatus("$name: capture stopped (${it.message})") }
        endPass()
    }

    // ---------- PhysSweep (Lens/ATI-style physical-sweep baseline, sense/baselines.py) ----------

    /** Lens-style physical sweep (Baek et al., ICLR 2025; `sense.baselines.PhysSweep`):
     *  candidate cells are VISITED physically, one capture per frame, and each captured
     *  frame is that frame's logged output — the sweep pays the real capture latency the
     *  virtual probe avoids (manuscript §3.2, T_phys >= N/f). After a sweep the
     *  best-observed cell (V3 sumconf, own captures only) holds for [hold] frames, then
     *  the sweep repeats. [full] sweeps every cell; otherwise the ISO column at the
     *  shutter row of the previous sweep's winner (row 0 cold start — the sim re-meters
     *  an AE shutter per frame, which would cost an extra capture here; deviation noted
     *  in the manifest). Scores are recorded the frame they are captured, which carries
     *  the same information as the sim's read-previous-frame convention. */
    fun runPhysSweep(full: Boolean, hold: Int, maxFrames: Int,
                     onStatus: (String) -> Unit,
                     v2: Boolean = false,
                     onFrame: (Bitmap, List<Detection>, Int /*iso*/, Int /*expUs*/) -> Unit = { _, _, _, _ -> }) {
        val g = if (v2) v2SpaceCur else grid
        fun eff(iso: Int) = if (v2) iso else effIso(iso)
        val tag = (if (v2) "physsweep_v2" else "physsweep") +
            (if (full) "_full_h$hold" else "_col_h$hold")
        startPass(tag, isGtReference = false, methodParams = JSONObject()
            .put("baseline", "phys_sweep_lens_iclr25")
            .put("full", full).put("hold", hold)
            .put("space", if (v2) "v2_lattice_5x5" else "device_grid")
            .put("session", if (v2) v2SessionTag else "device_grid")
            .put("gain_rungs", JSONArray((if (v2) v2GainRungs else grid.gains).toList()))
            .put("col_shutter_rule", "previous_winner_row (sim uses per-frame AE metering)"))
        logger.csv("candidates", listOf("frame", "cell", "gain", "exposure_us",
            "sum_conf", "chosen", "tie_break"))
        withFastPipeline(onStatus) { pipe ->
            var sweepSh = 0
            var i = 0; var h = 0; var best = -1
            val obs = ArrayList<Pair<Double, Int>>()
            var f = 0
            while (running && f < maxFrames) {
                val cells: List<Int> = if (full) (0 until g.nGain * g.nShutter).toList()
                    else (0 until g.nGain).map { g.cell(it, sweepSh) }
                val sweeping = i < cells.size
                val cell: Int
                if (sweeping) {
                    cell = cells[i]; i++
                } else {
                    if (h == 0 && best < 0) {
                        best = obs.maxByOrNull { it.first }?.second ?: cells[0]
                        sweepSh = g.indices(best).second
                    }
                    cell = best; h++
                }
                val (gi, sj) = g.indices(cell)
                val gainVal = g.gains[gi]; val expUs = g.exposuresUs[sj]
                val t0 = now()
                stepClock.begin()
                val tf: Long
                val tensor: Exp21TensorResult
                val dets: List<Detection>
                if (v2) {
                    val r = pipe.captureV2Cell(gainVal, expUs) ?: break
                    tf = now(); tensor = r.first; dets = r.second
                } else {
                    val frames = pipe.capture(expUs, gainVal, 1)
                    if (frames.isEmpty()) break
                    tf = now()
                    val (t, all) = pipe.formAndDetect(frames, pipe.identityCell)
                    tensor = t; dets = all[0]
                }
                val formMs = ms(tf)
                stepClock.decided()
                val totalMs = ms(t0)
                val score = sumConf(dets)
                if (sweeping) obs.add(score to cell)
                logger.row("candidates", listOf(f, cell, eff(gainVal), expUs,
                    "%.5f".format(score), if (!sweeping) 1 else 0,
                    if (sweeping) "sweep" else "hold"))
                val bmp = pipe.bitmap(tensor, 0)
                val path = saveImage(
                    "${frameName(f)}_${isoExpTag(eff(gainVal), expUs)}", bmp)
                logFrame(f, "physsweep", cell, gainVal, expUs, 1, false, formMs,
                    pipe.inferMs, totalMs, dets, path,
                    tieBreak = if (sweeping) "sweep" else "hold", applyBoost = !v2,
                    captureMs = msBetween(t0, tf))
                onFrame(bmp, pipe.previewDets(dets, tensor, 0), eff(gainVal), expUs)
                onStatus("PhysSweep f=$f ${if (sweeping) "sweep" else "hold"} cell=$cell " +
                    "score=${"%.2f".format(score)} ${"%.0f".format(totalMs)}ms")
                if (!sweeping && h >= hold) { i = 0; h = 0; best = -1; obs.clear() }
                f++
            }
        }
        endPass()
    }

    /** Sensor time one read holds in the parallel stream (ms), measured on the S26 (2026-10-29):
     *  up to 1/60 one 16.7 ms slot; a 1/30 read ~46 ms before the next read starts; a 1/15 read
     *  ~146 ms, with or without an explicit frame duration. Budgeted a little above (50, 170): at
     *  46 / 146 fast PhysSweep's windows after a long read still ran ~6% over 100 ms on average. */
    private fun sensorHoldMs(exposureUs: Int) = when (exposureUs) {
        in 0..16_667 -> 16.7
        in 16_668..33_334 -> 50.0
        else -> 170.0
    }

    /**
     * Fast PhysSweep (2026-10-29): [runPhysSweep] (Baek et al., ICLR 2025) in the parallel
     * structure. The sweep no longer takes output frames: every output is the best-scored cell's
     * read, and the window's spare sensor time visits the next sweep cells (at most
     * [PHYS_FAST_CANDIDATES] a window, within its 100 ms; a 1/30 read holds the sensor ~46 ms,
     * a 1/15 read ~146 ms, so a 1/15 cell goes alone and the windows after it visit fewer cells
     * until the time is paid back). Every visited image is detected only to score its cell (V3 sum of confidences of the
     * cell's own image, as PhysSweep); candidates never enter the output (no fusion). The sweep
     * runs on over the 25 v2 cells in PhysSweep's order with no hold (sweeping no longer costs an
     * output); best = the cell with the highest latest score. Cold start: cell 0, as PhysSweep's
     * first frame. Runs only with parallel capture.
     */
    fun runPhysSweepFast(maxFrames: Int, onStatus: (String) -> Unit,
                         onFrame: (Bitmap, List<Detection>, Int, Int) -> Unit = { _, _, _, _ -> }) {
        check(parallelCapture) { "fast PhysSweep runs only with parallel capture" }
        val g = v2SpaceCur
        val nCells = g.nGain * g.nShutter
        val ceil = v2AnalogCeil()
        fun state(c: Int): SensorState {
            val (gi, sj) = g.indices(c)
            return SensorState(g.exposuresUs[sj].toLong(), minOf(g.gains[gi], ceil))
        }
        fun ratio(c: Int): Double { val gain = g.gains[g.indices(c).first]; return gain.toDouble() / minOf(gain, ceil) }
        fun holdMs(c: Int) = sensorHoldMs(g.exposuresUs[g.indices(c).second])
        startPass("physsweep_fast_v2", isGtReference = false, methodParams = JSONObject()
            .put("baseline", "phys_sweep_lens_iclr25 in the parallel structure (fast)")
            .put("rule", "output = the best-scored cell's read; spare slots of each window visit the next " +
                "sweep cells, detected only to score them; continuous sweep, no hold; best = highest latest score")
            .put("candidates_per_output", PHYS_FAST_CANDIDATES).put("slots_per_output", 6)
            .put("score", "V3 sum of confidences of the cell's own image (as PhysSweep)")
            .put("cold_start", "cell 0 (PhysSweep's first frame)")
            .put("space", "v2_lattice_5x5").put("session", v2SessionTag)
            .put("gain_rungs", JSONArray(g.gains.toList())).put("shutters_us", JSONArray(g.exposuresUs.toList())))
        logger.csv("candidates", listOf("frame", "cell", "gain", "exposure_us", "sum_conf", "role", "sensor_ts"))
        withFastPipeline(onStatus) { pipe ->
            val score = DoubleArray(nCells) { Double.NaN }
            var best = 0
            var cursor = 0
            // Sensor time a window took beyond its 100 ms (a 1/15 read), owed by the next windows.
            var debtMs = 0.0
            /** The next sweep cells after [cursor] (skipping [b]) whose reads fit the window's
             *  100 ms with [b]'s read and the debt; a cell longer than a whole window goes alone
             *  in a window with no debt. Returns them and how far the cursor moves. */
            fun window(b: Int): Pair<List<Int>, Int> {
                val out = ArrayList<Int>()
                val avail = ADDL_PERIOD_MS - holdMs(b) - debtMs
                var used = 0.0; var k = 0; var moved = 0
                while (out.size < PHYS_FAST_CANDIDATES && k < nCells) {
                    val c = (cursor + k) % nCells
                    k++
                    if (c == b) { moved = k; continue }
                    val fits = used + holdMs(c) <= avail || (out.isEmpty() && debtMs == 0.0)
                    if (!fits) break
                    out += c; used += holdMs(c); moved = k
                }
                debtMs = maxOf(0.0, debtMs + holdMs(b) + used - ADDL_PERIOD_MS)
                return out to moved
            }
            // Windows are planned two ahead and each queued once (candidates first, the output's
            // cell last, so the output's read is the window's freshest): the reads already in
            // flight are always the next window's, and no slot goes to a stale setting. A new
            // best therefore takes over two windows later.
            val planned = ArrayDeque<Pair<Int, List<Int>>>()
            fun planNext() {
                val (cands, moved) = window(best)
                cursor = (cursor + moved) % nCells
                planned.addLast(best to cands)
                raw.parallelEnqueue((cands + best).map(::state))
            }
            planNext(); planNext()
            var f = 0
            while (running && f < maxFrames) {
                val t0 = now()
                stepClock.begin()
                val (cellOut, cands) = planned.removeFirst()
                val cellsNow = listOf(cellOut) + cands
                val reads = raw.parallelTakeEach(cellsNow.map(::state))
                planNext()
                stepClock.captured(reads.map { it.second })
                val tf = now()
                val tensor = pipe.source.formLanesTensor(reads.map { listOf(it.first) },
                    DoubleArray(reads.size) { ratio(cellsNow[it]) })
                stepClock.formed()
                val dets = pipe.tfl.detectTensorBatchOptimized(tensor.batch)
                stepClock.detected()
                val formMs = ms(tf) - pipe.inferMs
                cellsNow.forEachIndexed { a, c ->
                    score[c] = sumConf(dets[a])
                    val (gi, sj) = g.indices(c)
                    logger.row("candidates", listOf(f, c, g.gains[gi], g.exposuresUs[sj], "%.5f".format(score[c]),
                        if (a == 0) "output" else "candidate", reads[a].second.timestamp))
                }
                val newBest = (0 until nCells).filter { !score[it].isNaN() }.maxByOrNull { score[it] } ?: best
                stepClock.decided()
                val totalMs = ms(t0)
                val (gi, sj) = g.indices(cellOut)
                val gainVal = g.gains[gi]; val expUs = g.exposuresUs[sj]
                val bmp = pipe.bitmap(tensor, 0)
                val path = saveImage("${frameName(f)}_${isoExpTag(gainVal, expUs)}", bmp)
                logFrame(f, "physsweep_fast", cellOut, gainVal, expUs, 1, false, formMs,
                    pipe.inferMs, totalMs, dets[0], path, tieBreak = "candidates=${cands.size}",
                    applyBoost = false, captureMs = msBetween(t0, tf), metaOverride = reads[0].second)
                onFrame(bmp, pipe.previewDets(dets[0], tensor, 0), gainVal, expUs)
                onStatus("PhysSweep fast f=$f cell=$cellOut ISO$gainVal 1/${Math.round(1e6 / expUs)} " +
                    "+${cands.size} candidates ${"%.0f".format(totalMs)}ms")
                best = newBest
                f++
            }
        }
        endPass()
    }

    // ---------- Shin IROS'19 baselines (sense/baselines.py ShinSelect / ShinNM) ----------

    /** Shin et al. IROS'19 Algorithm 1 (`sense.baselines.ShinNM` via [NelderMead]): NM over
     *  stop space, ONE physical capture per function evaluation — the captured frame is the
     *  frame's logged output, so search cost lands on the recording exactly as deployed.
     *  Modes: hold (never re-search) / restart_int (re-search when the hold cell's mean
     *  intensity drifts > 0.15*255 from the hold start) / always. Start point (lattice v2,
     *  since 2026-10-20): phone AE's setting at run start, as continuous stops — the paper
     *  starts at the camera's current exposure and gain. Until then it started at the
     *  (max gain, shortest shutter) corner, a leftover of the old 3x3 grid's "Proposed
     *  cold-start cell" that happened to be the best fixed cell. The device grid keeps it. */
    fun runShinNM(mode: String, maxFrames: Int, onStatus: (String) -> Unit,
                  v2: Boolean = false,
                  onFrame: (Bitmap, List<Detection>, Int /*iso*/, Int /*expUs*/) -> Unit = { _, _, _, _ -> }) {
        require(mode == "hold" || mode == "restart_int" || mode == "always")
        val g = if (v2) v2SpaceCur else grid
        fun eff(iso: Int) = if (v2) iso else effIso(iso)
        // Stop-space search assumes power-of-2 spacing on both axes.
        val uMax = maxOf(g.nGain - 1, g.nShutter - 1).toDouble()
        // Stops are lattice indices: u = (log2(ISO / lowest ISO), log2(shutter / shortest)).
        val ae = if (v2) meterAeStart(onStatus) else null
        val u0 = if (ae != null) doubleArrayOf(
            log2Of(ae.iso / g.gains[0].toDouble()).coerceIn(0.0, uMax),
            log2Of(ae.expUs / g.exposuresUs[0].toDouble()).coerceIn(0.0, uMax))
        else if (v2) doubleArrayOf(2.0, 2.0)
        else doubleArrayOf((g.nGain - 1).toDouble(), 0.0)
        startPass((if (v2) "shin_nm_v2_" else "shin_nm_") + mode,
            isGtReference = false, methodParams = JSONObject()
            .put("baseline", "shin_iros19_nelder_mead").put("mode", mode).put("u_max", uMax)
            .put("space", if (v2) "v2_lattice_5x5" else "device_grid")
            .put("session", if (v2) v2SessionTag else "device_grid")
            .put("gain_rungs", JSONArray((if (v2) v2GainRungs else grid.gains).toList()))
            .put("start_rule", when {
                ae != null -> "phone AE at run start, continuous stops (Shin IROS'19: current exposure/gain)"
                v2 -> "AE gave nothing: centre of the lattice"
                else -> "proposed_cold_start_cell (device grid)" })
            .put("ae_start", ae?.json() ?: JSONObject.NULL)
            .put("u0", JSONArray(u0.toList())))
        logger.csv("candidates", listOf("frame", "cell", "gain", "exposure_us",
            "metric", "chosen", "tie_break"))
        withFastPipeline(onStatus) { pipe ->
            var nm: NelderMead? = null
            var holdU: DoubleArray? = null
            var holdI = -1.0
            var nRestart = 0
            var f = 0
            while (running && f < maxFrames) {
                val t0 = now()
                stepClock.begin()
                val machine = nm
                val proposal: DoubleArray
                val phase: String
                if (machine == null) { proposal = (holdU ?: u0).copyOf(); phase = "init" }
                else {
                    val p = machine.propose()
                    if (p == null) { proposal = machine.best(); phase = "hold" }
                    else { proposal = p; phase = "search" }
                }
                val cell = NelderMead.snapToGrid(proposal, g.nGain, g.nShutter)
                val (gi, sj) = g.indices(cell)
                val gainVal = g.gains[gi]; val expUs = g.exposuresUs[sj]
                val tf: Long
                val tensor: Exp21TensorResult
                val dets: List<Detection>
                if (v2) {
                    val r = pipe.captureV2Cell(gainVal, expUs) ?: break
                    tf = now(); tensor = r.first; dets = r.second
                } else {
                    val frames = pipe.capture(expUs, gainVal, 1)
                    if (frames.isEmpty()) break
                    tf = now()
                    val (t, all) = pipe.formAndDetect(frames, pipe.identityCell)
                    tensor = t; dets = all[0]
                }
                val formMs = ms(tf)
                val bmp = pipe.bitmap(tensor, 0)
                val px = IntArray(bmp.width * bmp.height)
                bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                val lum = ShinMetric.lumFromArgb(px, bmp.width, bmp.height)
                val fI = ShinMetric.score(lum, bmp.width, bmp.height)
                val meanI = ShinMetric.meanIntensity(lum)
                when (phase) {
                    // The constructor's first simplex vertex IS the (clipped) start point, so
                    // this frame's capture doubles as its evaluation — one capture per eval,
                    // matching the sim's cache-free reading of Alg. 1.
                    "init" -> nm = NelderMead(proposal, meanI, uMax).also { it.observe(-fI) }
                    "search" -> machine!!.observe(-fI)
                    "hold" -> {
                        holdU = proposal
                        if (holdI < 0) holdI = meanI
                        if (mode == "always" ||
                            (mode == "restart_int" && kotlin.math.abs(meanI - holdI) > 0.15 * 255)) {
                            nm = null; holdI = -1.0; nRestart++
                        }
                    }
                }
                stepClock.decided()
                val totalMs = ms(t0)
                logger.row("candidates", listOf(f, cell, eff(gainVal), expUs,
                    "%.6f".format(fI), 1, phase))
                val path = saveImage(
                    "${frameName(f)}_${isoExpTag(eff(gainVal), expUs)}", bmp)
                logFrame(f, if (v2) "shin_nm_v2_$mode" else "shin_nm_$mode", cell, gainVal,
                    expUs, 1, false, formMs,
                    pipe.inferMs, totalMs, dets, path, tieBreak = phase, applyBoost = !v2,
                    captureMs = msBetween(t0, tf))
                onFrame(bmp, pipe.previewDets(dets, tensor, 0), eff(gainVal), expUs)
                onStatus("ShinNM[$mode] f=$f $phase cell=$cell f(I)=${"%.2f".format(fI)} " +
                    "evals=${nm?.nFeval ?: 0} restarts=$nRestart")
                f++
            }
        }
        endPass()
    }

    // ---------- Neural-AE (Onzon CVPR'21-style, sense/baselines.py NeuralAEController) ----------

    /** Neural-AE closed loop, hist_scalar/ema variant: capture the current cell (that frame
     *  is the output), build the 59x256 multi-scale histogram, predict a bounded exposure
     *  change (Eq. 4), EMA in log-exposure (Eq. 5), snap by the shutter-priority split
     *  (Eq. 6). [weights] is a nae-bin-v1 blob (since 2026-10-20 trained on the sos_cf_v1
     *  record, same-moment labels; see NaeTrainSosCfTest). On lattice v2 the run starts at
     *  phone AE's exposure (ISO x shutter) and splits with T_MAX = 15 ms, both as in the
     *  sim; before 2026-10-20 it started at the (max gain, shortest shutter) corner and
     *  split at the grid's longest shutter. */
    fun runNeuralAe(weights: ByteArray, maxFrames: Int, onStatus: (String) -> Unit,
                    v2: Boolean = false,
                    onFrame: (Bitmap, List<Detection>, Int /*iso*/, Int /*expUs*/) -> Unit = { _, _, _, _ -> }) {
        val net = NeuralAeNet(weights)
        val g = if (v2) v2SpaceCur else grid
        fun eff(iso: Int) = if (v2) iso else effIso(iso)
        val tMaxUs = if (v2) 15_000 else -1
        val ae = if (v2) meterAeStart(onStatus) else null
        val weightsSha = java.security.MessageDigest.getInstance("SHA-256").digest(weights)
            .joinToString("") { "%02x".format(it) }.take(16)
        startPass(if (v2) "nae_v2_hist_scalar_ema" else "nae_hist_scalar_ema",
            isGtReference = false, methodParams = JSONObject()
            .put("baseline", "neural_ae_cvpr21_style").put("variant", "hist_scalar")
            .put("mode", "ema").put("weights_bytes", weights.size).put("weights_sha256_16", weightsSha)
            .put("space", if (v2) "v2_lattice_5x5" else "device_grid")
            .put("session", if (v2) v2SessionTag else "device_grid")
            .put("gain_rungs", JSONArray((if (v2) v2GainRungs else grid.gains).toList()))
            .put("t_max_us", if (tMaxUs > 0) tMaxUs else g.exposuresUs.maxOrNull())
            .put("start_rule", when {
                ae != null -> "phone AE at run start: log e = ln(ISO/100 x shutter s), split and snapped"
                v2 -> "AE gave nothing: ISO 400, 1/60 s"
                else -> "proposed_cold_start_cell (device grid)" })
            .put("ae_start", ae?.json() ?: JSONObject.NULL))
        withFastPipeline(onStatus) { pipe ->
            var logE: Double
            var cell: Int
            if (ae != null) {
                logE = Math.log(ae.iso / 100.0 * ae.expUs * 1e-6)
                cell = NaeSnap.splitAndSnap(Math.exp(logE), g.gains, g.exposuresUs, tMaxUs)
            } else {
                cell = if (v2) g.cell(2, 2) else g.cell(g.nGain - 1, 0)
                logE = Math.log(NaeSnap.cellE(cell, g.gains, g.exposuresUs))
            }
            var f = 0
            while (running && f < maxFrames) {
                val t0 = now()
                val (gi, sj) = g.indices(cell)
                val gainVal = g.gains[gi]; val expUs = g.exposuresUs[sj]
                val tf: Long
                val tensor: Exp21TensorResult
                val dets: List<Detection>
                if (v2) {
                    val r = pipe.captureV2Cell(gainVal, expUs) ?: break
                    tf = now(); tensor = r.first; dets = r.second
                } else {
                    val frames = pipe.capture(expUs, gainVal, 1)
                    if (frames.isEmpty()) break
                    tf = now()
                    val (t, all) = pipe.formAndDetect(frames, pipe.identityCell)
                    tensor = t; dets = all[0]
                }
                val formMs = ms(tf)
                val bmp = pipe.bitmap(tensor, 0)
                val px = IntArray(bmp.width * bmp.height)
                bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                val lum = ShinMetric.lumFromArgb(px, bmp.width, bmp.height)
                val hist = NaeFeatures.multiScaleHist(lum, bmp.width, bmp.height)
                val logU = net.predictLogU(hist)
                val totalMs = ms(t0)
                val path = logger.saveJpegAsync(
                    "${frameName(f)}_${isoExpTag(eff(gainVal), expUs)}", bmp)
                logFrame(f, if (v2) "nae_v2_hist_scalar_ema" else "nae_hist_scalar_ema",
                    cell, gainVal, expUs, 1, false,
                    formMs, pipe.inferMs, totalMs, dets, path, applyBoost = !v2,
                    captureMs = msBetween(t0, tf))
                onFrame(bmp, pipe.previewDets(dets, tensor, 0), eff(gainVal), expUs)
                onStatus("NAE f=$f cell=$cell logU=${"%.3f".format(logU)} ${"%.0f".format(totalMs)}ms")
                val target = Math.log(NaeSnap.cellE(cell, g.gains, g.exposuresUs)) + logU
                logE = NaeSnap.emaLogE(logE, target)
                cell = NaeSnap.splitAndSnap(Math.exp(logE), g.gains, g.exposuresUs, tMaxUs)
                f++
            }
        }
        endPass()
    }

    // ---------- AE ----------

    /** Every frame captured under [aeStrategy], logging whichever (iso, exp) it chose. */
    fun runAe(maxFrames: Int, isGtReference: Boolean,
              aeStrategy: AeStrategy,
              onStatus: (String) -> Unit,
              onFrame: (Bitmap, List<Detection>, Int /*iso*/, Int /*expUs*/) -> Unit = { _, _, _, _ -> }) {
        val customAe = if (aeStrategy == AeStrategy.CUSTOM_BRIGHTNESS) customAeForDevice() else null
        startPass("ae_${aeStrategy.tag()}", isGtReference, methodParams = JSONObject()
            .put("ae_strategy", aeStrategy.tag())
            .put("capture_path", "persistent fast routing, as the manual methods")
            .apply { if (customAe != null) put("custom_ae", customAe.toJson()) })
        // Custom AE seed; replaced every frame by the brightness feedback below.
        var nextIso = grid.baseGain
        var nextExpUs = grid.fastestExposureUs
        if (aeStrategy == AeStrategy.CUSTOM_BRIGHTNESS) {
            // Deterministic AE is manual-keyed capture, so it rides the optimized pipeline
            // and its per-step cost is comparable with the controller's.
            withFastPipeline(onStatus) { pipe ->
                var f = 0
                while (running && f < maxFrames) {
                    val t0 = now()
                    val frames = pipe.capture(nextExpUs, nextIso, 1)
                    if (frames.isEmpty()) break
                    val tf = now()
                    val (tensor, detsAll) = pipe.formAndDetect(frames, pipe.identityCell)
                    val formMs = ms(tf)
                    val dets = detsAll[0]
                    val totalMs = ms(t0)
                    val gainVal = nextIso; val expUs = nextExpUs
                    val bmp = pipe.bitmap(tensor, 0)
                    val path = logger.saveJpegAsync(
                        "${frameName(f)}_${isoExpTag(effIso(gainVal), expUs)}", bmp)
                    logFrame(f, "ae", -1, gainVal, expUs, 1, false, formMs,
                        pipe.inferMs, totalMs, dets, path,
                        captureMs = msBetween(t0, tf))
                    onFrame(bmp, pipe.previewDets(dets, tensor, 0), effIso(gainVal), expUs)
                    onStatus("AE[custom] f=$f iso=${effIso(gainVal)} exp=${expUs}us " +
                        "ndet=${dets.size} ${"%.0f".format(totalMs)}ms")
                    val ratio = meanRawRatio(frames[0])
                    val (newIso, newExp) = customAe!!.next(gainVal, expUs, ratio)
                    nextIso = newIso; nextExpUs = newExp
                    f++
                }
            }
            endPass()
            return
        }
        // PHONE AE sends the HAL's own AE-on request, on the same persistent capture routing
        // as prop_c and the v2 baselines (captureFastAe; until 2026-10-16 the one-shot
        // captureAe, which re-armed the reader every frame). The frame is decoded natively,
        // formed by the same fused tensor fill and detected by the same batched detector, so
        // the only difference left in the comparison is how the exposure was chosen,
        // including the HAL's 33 ms AE frame duration.
        val tfl = detector as? com.example.activeperception.acquire.TfliteYoloDetector
        val src = ParallelRawCandidateSource(grid, raw,
            colorPipeline = ColorPipeline.ORIGINAL_GAIN_SRGB, burstWindow = BurstWindow.FIRST_N)
        raw.configureDecodeThreads(4)
        raw.startFastCapture()
        if (parallelCapture) raw.startParallel()
        tfl?.warmUpAllBatches()
        var f = 0
        try { while (running && f < maxFrames) {
            val t0 = now()
            stepClock.begin()
            val frames = raw.captureFastAe(1)
            if (frames.isEmpty()) break
            stepClock.captured(raw.lastMeta)
            val tf = now()
            val bmp: Bitmap
            val dets: List<Detection>
            val previewDets: List<Detection>
            val formMs: Double
            val infMs: Double
            if (tfl != null) {
                val tensor = src.formExplicitGainsTensor(frames[0], doubleArrayOf(1.0))
                stepClock.formed()
                dets = tfl.detectTensorBatchOptimized(tensor.batch)[0]
                stepClock.detected()
                infMs = tfl.lastRunMs + tfl.lastDecodeMs
                formMs = ms(tf) - infMs
                bmp = src.selectedTensorBitmap(tensor, 0)
                previewDets = src.detectionsForTensorPreview(dets, tensor, 0)
            } else {
                bmp = bitmapFromRaw(frames[0])
                formMs = ms(tf)
                stepClock.formed()
                val ti = now(); dets = detector.detectBatch(listOf(bmp))[0]; infMs = ms(ti)
                stepClock.detected()
                previewDets = dets
            }
            stepClock.decided()
            val totalMs = ms(t0)
            val applied = raw.lastMeta.firstOrNull()
            val gainVal = applied?.appliedIso ?: -1
            val expUs = (applied?.appliedExpUs ?: -1L).toInt()
            val path = saveImage("${frameName(f)}_${isoExpTag(gainVal, expUs)}", bmp)
            logFrame(f, "ae", -1, gainVal, expUs, 1, false, formMs, infMs, totalMs, dets, path,
                applyBoost = false, captureMs = msBetween(t0, tf))
            runCatching { onFrame(bmp, previewDets, gainVal, expUs) }
            onStatus("AE[phone] f=$f iso=$gainVal exp=${expUs}us ndet=${dets.size} ${"%.0f".format(totalMs)}ms")
            f++
        } } finally {
            runCatching { raw.stopFastCapture() }
        }
        endPass()
    }

    // ---------- proposed_v3cause (device spec "v3 cause 판") ----------

    /** App files dir (getExternalFilesDir), where the read-noise table lives across runs. */
    @Volatile var appFilesDir: java.io.File? = null

    /** The table [runReadNoise] writes: read noise in DN per analog gain. */
    private val READ_NOISE_FILE = "read_noise.json"

    /** Luma of a formed lane at half resolution (320x320 for the 640 input), divided by its
     *  mean so a base-gain change between frames does not look like motion. */
    /** Lane [lane]'s rows [top, top + HowToSense.H) as float r, g, b; HowToSense.pictureRows
     *  quantises them as the saved images are (ParallelRawCandidateSource.packArgbKotlin). */
    private fun tensorRgbRows(t: Exp21TensorResult, lane: Int, top: Int, imgsz: Int = 640): FloatArray {
        val w = HowToSense.W; val h = HowToSense.H
        require(w == imgsz && top >= 0 && top + h <= imgsz)
        val buf = t.batch.input.duplicate().order(java.nio.ByteOrder.nativeOrder())
        buf.position((lane * imgsz * imgsz + top * imgsz) * 3 * 4)
        val f = FloatArray(w * h * 3)
        buf.asFloatBuffer().get(f)
        return f
    }

    private fun tensorGrayHalf(t: Exp21TensorResult, lane: Int, imgsz: Int = 640):
        com.example.activeperception.acquire.SparseFlow.Img {
        val n = imgsz * imgsz * 3
        val buf = t.batch.input.duplicate().order(java.nio.ByteOrder.nativeOrder())
        buf.position(lane * n * 4)
        val f = FloatArray(n)
        buf.asFloatBuffer().get(f)
        val h = imgsz / 2
        val out = FloatArray(h * h)
        var sum = 0.0
        for (y in 0 until h) for (x in 0 until h) {
            var g = 0f
            for (dy in 0..1) for (dx in 0..1) {
                val p = ((2 * y + dy) * imgsz + 2 * x + dx) * 3
                g += 0.299f * f[p] + 0.587f * f[p + 1] + 0.114f * f[p + 2]
            }
            out[y * h + x] = g * 0.25f
            sum += out[y * h + x]
        }
        val mean = (sum / out.size).toFloat().coerceAtLeast(1e-6f)
        for (i in out.indices) out[i] /= mean
        return com.example.activeperception.acquire.SparseFlow.Img(h, h, out)
    }

    /** Writes decode_check.json once the first frame after [RawSensorCapturer.armNativeDecodeValidation]
     *  has been compared with the Kotlin reference decoder (mismatching sites; 0 = identical). */
    private fun writeDecodeCheck() = runCatching {
        java.io.File(logger.dir, "decode_check.json").writeText(JSONObject()
            .put("native_vs_kotlin_mismatch_sites", raw.lastNativeDecodeMismatchCount)
            .put("decode_mode", raw.decodeMode).toString(2))
    }

    /**
     * Read noise per analog gain, measured once per device with the LENS COVERED: for each
     * gain, [framesPerGain] dark frames; per raw pixel (unbinned, sensor centre crop) the
     * standard deviation over frames, averaged as RMS over pixels with hot pixels excluded.
     * Also the same on decoded sites (mean of 4 pixels) for reference. Writes
     * read_noise.json to the run and, only when every gain was measured (a stopped run must
     * not replace a full table), to the app files dir, where v3cause reads it.
     */
    fun runReadNoise(onStatus: (String) -> Unit, framesPerGain: Int = 20, exposureUs: Int = 16_667) {
        startPass("readnoise", isGtReference = false, withFrames = false, methodParams = JSONObject()
            .put("note", "lens covered").put("frames_per_gain", framesPerGain)
            .put("exposure_us", exposureUs).put("crop_px", 512).put("site_crop", 256))
        logger.csv("readnoise", listOf("gain_req", "gain_applied", "n_frames", "n_site_frames",
            "exposure_us", "rn_pixel_dn", "rn_site_dn", "offset_dn", "fpn_dn", "hot_frac"))
        raw.configureDecodeThreads(4)
        raw.startFastCapture()
        val ceil = v2AnalogCeil()
        val gains = listOf(raw.sensorIsoMin, 100, 200, 400, 800, ceil)
            .filter { it in raw.sensorIsoMin..ceil }.distinct().sorted()
        class Row(val gain: Int, val applied: Int, val n: Int, val ns: Int, val rn: Double,
                  val rnSite: Double, val offset: Double, val fpn: Double, val hot: Double)
        val rows = ArrayList<Row>()
        /** RMS over pixels of the temporal std, hot pixels (> 25x the median variance) out. */
        fun temporal(stack: List<IntArray>): DoubleArray {
            if (stack.size < 2) return doubleArrayOf(Double.NaN, Double.NaN, Double.NaN, Double.NaN)
            val m = stack[0].size
            val varr = DoubleArray(m); val mean = DoubleArray(m)
            for (p in 0 until m) {
                var s = 0.0; var s2 = 0.0
                for (fr in stack) { val v = fr[p].toDouble(); s += v; s2 += v * v }
                val mu = s / stack.size
                mean[p] = mu
                varr[p] = (s2 - stack.size * mu * mu) / (stack.size - 1)
            }
            val med = varr.sorted()[m / 2].coerceAtLeast(1e-9)
            val good = (0 until m).filter { varr[it] <= 25 * med }
            val rnv = kotlin.math.sqrt(good.sumOf { varr[it] } / good.size)
            val off = good.sumOf { mean[it] } / good.size
            val fpn = kotlin.math.sqrt(good.sumOf { (mean[it] - off) * (mean[it] - off) } / good.size)
            return doubleArrayOf(rnv, off, fpn, 1.0 - good.size.toDouble() / m)
        }
        try {
            for (g in gains) {
                if (!running) break
                val crops = java.util.Collections.synchronizedList(ArrayList<IntArray>())
                val applied = java.util.Collections.synchronizedList(ArrayList<Int>())
                raw.rawTap = RawSensorCapturer.RawTap(512, 512) { crop, meta ->
                    if (kotlin.math.abs(meta.appliedIso - g) <= maxOf(8, g / 100)) {
                        crops.add(crop); applied.add(meta.appliedIso)
                    }
                }
                val sites = ArrayList<IntArray>()
                var tries = 0
                while (running && crops.size < framesPerGain && tries < framesPerGain * 3) {
                    tries++
                    val fr = runCatching { raw.captureFast(exposureUs, g, 1) }.getOrNull() ?: continue
                    if (fr.isEmpty()) continue
                    val frame = fr[0]
                    val n = 256
                    val x0 = ((frame.width - n) / 2) and 1.inv(); val y0 = ((frame.height - n) / 2) and 1.inv()
                    sites += IntArray(n * n) { i -> frame.bayer[(y0 + i / n) * frame.width + x0 + i % n] }
                    onStatus("read noise ISO $g: ${crops.size}/$framesPerGain (cover the lens)")
                }
                raw.rawTap = null
                val px = temporal(crops.toList())
                val st = temporal(sites)
                val ap = applied.toList().sorted().let { if (it.isEmpty()) -1 else it[it.size / 2] }
                val row = Row(g, ap, crops.size, sites.size, px[0], st[0], px[1], px[2], px[3])
                rows += row
                logger.row("readnoise", listOf(g, ap, row.n, row.ns, exposureUs,
                    "%.4f".format(row.rn), "%.4f".format(row.rnSite), "%.4f".format(row.offset),
                    "%.4f".format(row.fpn), "%.5f".format(row.hot)))
            }
        } finally {
            raw.rawTap = null
            runCatching { raw.stopFastCapture() }
        }
        val j = JSONObject().put("device_model", Build.MODEL).put("measured_at_ms", System.currentTimeMillis())
            .put("exposure_us", exposureUs).put("frames_per_gain", framesPerGain)
            .put("gains_req", JSONArray(rows.map { it.gain })).put("gains_applied", JSONArray(rows.map { it.applied }))
            .put("rn_dn", JSONArray(rows.map { if (it.rn.isNaN()) JSONObject.NULL else it.rn }))
            .put("rn_site_dn", JSONArray(rows.map { if (it.rnSite.isNaN()) JSONObject.NULL else it.rnSite }))
            .put("offset_dn", JSONArray(rows.map { if (it.offset.isNaN()) JSONObject.NULL else it.offset }))
            .put("fpn_dn", JSONArray(rows.map { if (it.fpn.isNaN()) JSONObject.NULL else it.fpn }))
            .put("hot_frac", JSONArray(rows.map { if (it.hot.isNaN()) JSONObject.NULL else it.hot }))
            .put("n_frames", JSONArray(rows.map { it.n }))
            .put("complete", rows.size == gains.size && rows.all { it.n >= 2 })
        runCatching { java.io.File(logger.dir, READ_NOISE_FILE).writeText(j.toString(2)) }
        if (j.getBoolean("complete")) {
            runCatching { appFilesDir?.let { java.io.File(it, READ_NOISE_FILE).writeText(j.toString(2)) } }
        }
        onStatus("read noise: " + rows.joinToString(" ") { "ISO${it.gain}=${"%.2f".format(it.rn)}DN" })
        endPass()
    }

    // ---------- Continuous v2 (S26U): selection-following analog base ----------

    /** Nominal rungs of the 5x5 (gain x shutter) space. Every rung is captured at
     *  min(rung, analog ceiling); only the 1600 rung carries a digital residual (x1.33 on
     *  a 1200 capture). Consecutive shutter rungs are exact x2, so any window row is a
     *  same-ISO FIRST_N burst sum (1/2/4 frames) of the window-min exposure. */
    /** Session presets shift the 5-rung gain ladder's ANCHOR, not its width: the dark end
     *  is reachable only through ISO (shutter caps at 1/30), while the bright end is the
     *  shutter's job — so a night ladder pushes up without losing anything below. Rungs
     *  above the device's analog ceiling are digital-extension rungs (realized as
     *  ceiling-analog x residual); residuals are recorded per run. */
    private val v2Sessions = linkedMapOf(
        "indoor" to intArrayOf(100, 200, 400, 800, 1600),
        "outdoor_day" to intArrayOf(100, 200, 400, 800, 1600),
        "dim" to intArrayOf(200, 400, 800, 1600, 3200),
        "outdoor_night" to intArrayOf(400, 800, 1600, 3200, 6400)
    )
    /** Older tags from earlier notes keep working (walk = parking-lot dim, vehicle_* =
     *  outdoor_*): the session name doubles as the run's condition label in the manifest,
     *  so the canonical tags are scene-based. */
    private val v2SessionAliases = mapOf(
        "walk" to "dim", "vehicle_night" to "outdoor_night", "vehicle_day" to "outdoor_day")
    @Volatile private var v2SessionTag = "indoor"
    /** The rig this run rode (limo / car / rayneo), recorded in every manifest so a mixed
     *  pile of runs can be split by platform without parsing directory names. */
    @Volatile var platformTag: String = ""
    /** Two-phone pairing state. A drive is only interpretable if you can tell WHICH phone
     *  produced a run and WHICH block it belongs to; car_v1 recorded none of it, and the
     *  LEFT/RIGHT roles had to be recovered afterwards from an accident of fuel-gauge
     *  quantisation. Defaults mean "not a playlist run" rather than "unknown". */
    @Volatile var driveRole: String = ""
    @Volatile var blockSeconds: Int = -1
    @Volatile var blockIndex: Long = -1L
    @Volatile var playlistRepeats: Int = -1
    @Volatile var playlistRows: String = ""
    /** Stable per-install id, so two units of the same model are distinguishable. */
    @Volatile var deviceId: String = ""
    /** The v2 lattice as a Grid, so the space-generic baselines (PhysSweep, ShinNM,
     *  NeuralAE snap) can search the SAME space Prop-C lives on. Boost 1: v2 has no
     *  digital-boost concept — nominal ISO is the label. */
    @Volatile private var v2SpaceCur = Grid(
        gains = v2Sessions["indoor"]!!,
        exposuresUs = intArrayOf(4167, 8333, 16667, 33333, 66667),
        digitalBoost = 1.0
    )

    /** Select the session ladder before a run starts (per-run pipelines and manifests
     *  read it once at startPass). Unknown tags keep the current session. */
    fun setV2Session(rawTag: String) {
        val tag = v2SessionAliases[rawTag] ?: rawTag
        val gains = v2Sessions[tag] ?: return
        v2SessionTag = tag
        v2SpaceCur = Grid(gains = gains,
            exposuresUs = intArrayOf(4167, 8333, 16667, 33333, 66667), digitalBoost = 1.0)
    }

    private val v2GainRungs: IntArray get() = v2SpaceCur.gains
    private val v2ShutterRungsUs = intArrayOf(4167, 8333, 16667, 33333, 66667)
    private fun v2Cell(gi: Int, sj: Int) = gi * v2ShutterRungsUs.size + sj

    /** Per-rung digital residual (nominal / analog-realized) for the manifest. */
    private fun v2ResidualsJson(): JSONArray {
        val ceil = v2AnalogCeil()
        val arr = JSONArray()
        for (r in v2GainRungs) arr.put(
            "%.4f".format(r.toDouble() / minOf(r, ceil)).toDouble())
        return arr
    }
    private fun v2AnalogCeil() = if (raw.maxAnalogIso > 0) raw.maxAnalogIso else raw.sensorIsoMax

    /** Physical visit of one v2 cell, realized exactly as Prop-C realizes it: capture at
     *  the analog-clamped ISO, form one lane with the nominal/physical residual (only the
     *  1600 rung has one). Returns tensor + detections, or null when capture fails. */
    private fun FastPipeline.captureV2Cell(
        gainNominal: Int, expUs: Int
    ): Pair<Exp21TensorResult, List<Detection>>? {
        val phys = minOf(gainNominal, v2AnalogCeil())
        val frames = capture(expUs, phys, 1)
        if (frames.isEmpty()) return null
        stepClock.captured(raw.lastMeta)
        val tensor = source.formExplicitGainsTensor(frames[0],
            doubleArrayOf(gainNominal.toDouble() / phys))
        stepClock.formed()
        val dets = tfl.detectTensorBatchOptimized(tensor.batch)[0]
        stepClock.detected()
        return tensor to dets
    }

    /**
     * Continuity design: the SELECTED candidate becomes the next frame's physical base -
     * the same rule the shutter axis always followed, extended to gain. The original
     * failure was a base PINNED at the bottom rung, making every upward candidate a
     * multi-stop digital multiply of the weakest original; with the base riding the
     * selection, div2/x1/x2 are formed digitally from a mid-window original and the
     * digital span never exceeds one stop.
     *
     * SINGLE capture command per step: off-probe one frame at (base ISO, current
     * shutter); probe one burst at (base ISO, window-min shutter) whose same-ISO sums
     * realize the neighbor shutter rows (duty gaps below the frame period are the
     * accepted cost). Two consecutive same-direction wins (hysteresis) move the base one
     * rung; clipping self-corrects because a blown candidate scores low and selection
     * walks back down.
     */
    fun runContinuousProposed(
        period: Int, maxFrames: Int,
        onStatus: (String) -> Unit,
        onFrame: (Bitmap, List<Detection>, Int, Int) -> Unit
    ) {
        require(period >= 2)
        val tfl = detector as? com.example.activeperception.acquire.TfliteYoloDetector
            ?: error("continuous v2 requires TfliteYoloDetector")
        val analogCeil = if (raw.maxAnalogIso > 0) raw.maxAnalogIso else raw.sensorIsoMax
        startPass("proposed_v2_continuous", isGtReference = false, methodParams = JSONObject()
            .put("title", "Continuous v2 | selection-following base, single-capture window")
            .put("session", v2SessionTag)
            .put("gain_rungs", JSONArray(v2GainRungs.toList()))
            .put("rung_residuals", v2ResidualsJson())
            .put("shutter_rungs_us", JSONArray(v2ShutterRungsUs.toList()))
            .put("analog_ceiling", analogCeil)
            .put("period", period).put("hysteresis", 2)
            .put("candidates", "div2 / x1 / x2 formed digitally from the step's single " +
                "capture; the moving base keeps the digital span within one stop")
            .put("probe", "one burst at the window-min shutter; neighbor shutter rows by " +
                "same-ISO burst sum (1/2/4 frames)")
            .put("frames_iso_caveat",
                "frames.csv iso_req/iso_applied are the step's capture; cont.csv carries " +
                "the rung bookkeeping"))
        logger.csv("candidates", listOf("frame", "cell", "gain", "exposure_us",
            "sum_conf", "chosen", "tie_break"))
        logger.csv("cont", listOf("frame", "rung_idx", "nominal_iso", "phys_iso", "residual",
            "shutter_idx", "probe", "gain_pend", "shutter_pend"))
        // Was missing: row() is a documented no-op on a stream csv() never opened, so every
        // router decision this method made was silently discarded — in the one method the
        // routing story is about. Header order must match the five values written below.
        logger.csv("router", listOf("frame", "score", "model_limited_clusters",
            "recovered_locally_clusters", "offload"))
        val router = CrossExposureRouter(selectConf)
        val src = ParallelRawCandidateSource(grid, raw,
            colorPipeline = ColorPipeline.ORIGINAL_GAIN_SRGB, burstWindow = BurstWindow.FIRST_N)
        raw.configureDecodeThreads(4)
        raw.startFastCapture()
        if (parallelCapture) raw.startParallel()
        onStatus("warming FP16 GPU batches...")
        tfl.warmUpAllBatches()
        var gi = 2; var sj = 2               // cold start: ISO 400 / rung 2 (1/60 s on the 1/240-1/15 rungs)
        var gainPend = 0; var shutterPend = 0
        var f = 0
        try {
            while (running && f < maxFrames) {
                val t0 = now()
                stepClock.begin()
                val probe = f % period == 0
                val rung = v2GainRungs[gi]
                val phys = minOf(rung, analogCeil)
                val res = rung.toDouble() / phys
                // The shutter this step outputs at; the hysteresis below may move sj for the
                // NEXT step, so logging must not read sj after that.
                val sjUsed = sj
                val rows = if (probe)
                    (maxOf(0, sj - 1)..minOf(v2ShutterRungsUs.size - 1, sj + 1)).toList()
                else listOf(sj)
                val gis = (maxOf(0, gi - 1)..minOf(v2GainRungs.size - 1, gi + 1)).toList()
                val baseExpUs = v2ShutterRungsUs[rows.first()]
                val nBurst = Math.round(
                    v2ShutterRungsUs[rows.last()].toDouble() / baseExpUs).toInt()
                // The step's ONE capture command.
                val frames = raw.captureFast(baseExpUs, phys, nBurst)
                if (frames.isEmpty()) break
                stepClock.captured(raw.lastMeta)
                val tForm = now()
                val laneCount = rows.size * gis.size
                val laneBurst = IntArray(laneCount); val laneRatio = DoubleArray(laneCount)
                val laneGi = IntArray(laneCount); val laneSj = IntArray(laneCount)
                var l = 0
                for (r in rows) for (g in gis) {
                    laneBurst[l] = Math.round(
                        v2ShutterRungsUs[r].toDouble() / baseExpUs).toInt()
                    laneRatio[l] = v2GainRungs[g].toDouble() / phys
                    laneGi[l] = g; laneSj[l] = r; l++
                }
                val tensor = src.formExplicitBurstTensor(frames, laneBurst, laneRatio)
                stepClock.formed()
                val dets = tfl.detectTensorBatchOptimized(tensor.batch)
                stepClock.detected()
                val inferMs = tfl.lastRunMs + tfl.lastDecodeMs
                val formMs = ms(tForm) - inferMs
                val scores = DoubleArray(laneCount) { sumConf(dets[it]) }
                // Gain decision on the current-shutter column only; probe rows feed the
                // shutter decision through their row sums.
                val colLanes = (0 until laneCount).filter { laneSj[it] == sj }
                val allZero = colLanes.all { scores[it] <= 0.0 }
                val centerLane = colLanes.first { laneGi[it] == gi }
                val bestLane = if (allZero) centerLane
                    else colLanes.maxByOrNull { scores[it] }!!
                val chosenGi = laneGi[bestLane]
                val chosenNominal = v2GainRungs[chosenGi]
                val chosenDets = dets[bestLane]
                val bmp = src.selectedTensorBitmap(tensor, bestLane)
                val previewDets = src.detectionsForTensorPreview(chosenDets, tensor, bestLane)
                for (i in 0 until laneCount) {
                    logger.row("candidates", listOf(f, v2Cell(laneGi[i], laneSj[i]),
                        v2GainRungs[laneGi[i]], v2ShutterRungsUs[laneSj[i]],
                        "%.5f".format(scores[i]), if (i == bestLane) 1 else 0,
                        if (laneSj[i] != sj) "probe" else if (allZero) "hold" else "conf"))
                    writeDets(f, "cand", v2Cell(laneGi[i], laneSj[i]), dets[i])
                }
                logger.row("cont", listOf(f, gi, rung, phys, "%.4f".format(res), sj,
                    if (probe) 1 else 0, gainPend, shutterPend))
                lastRoutingDecision = router.decide(colLanes.map { dets[it] })
                logger.row("router", listOf(f, "%.5f".format(lastRoutingDecision.score),
                    lastRoutingDecision.modelLimitedClusters,
                    lastRoutingDecision.recoveredLocallyClusters,
                    if (lastRoutingDecision.shouldOffload) 1 else 0))
                // Continuity with 2-step hysteresis on both axes.
                val rel = chosenGi - gi
                gainPend = when {
                    allZero || rel == 0 -> 0
                    rel > 0 -> maxOf(1, gainPend + 1)
                    else -> minOf(-1, gainPend - 1)
                }
                if (gainPend >= 2 && gi < v2GainRungs.size - 1) { gi++; gainPend = 0 }
                else if (gainPend <= -2 && gi > 0) { gi--; gainPend = 0 }
                if (probe && rows.size > 1) {
                    val rowSum = rows.associateWith { r ->
                        (0 until laneCount).filter { laneSj[it] == r }.sumOf { scores[it] }
                    }
                    val bestRow = rowSum.maxByOrNull { it.value }!!.key
                    // Strictly-better only: on ties (and all-zero frames) the shutter
                    // holds instead of drifting toward whichever row iterates first.
                    val srel = if ((rowSum[bestRow] ?: 0.0) > (rowSum[sj] ?: 0.0) + 1e-9)
                        Integer.signum(bestRow - sj) else 0
                    shutterPend = when {
                        srel == 0 -> 0
                        srel > 0 -> maxOf(1, shutterPend + 1)
                        else -> minOf(-1, shutterPend - 1)
                    }
                    if (shutterPend >= 2 && sj < v2ShutterRungsUs.size - 1) {
                        sj++; shutterPend = 0
                    } else if (shutterPend <= -2 && sj > 0) { sj--; shutterPend = 0 }
                }
                stepClock.decided()
                val totalMs = ms(t0)
                val path = saveImage(
                    "${frameName(f)}_cell${v2Cell(chosenGi, sjUsed)}", bmp)
                logFrame(f, "proposed_v2", v2Cell(chosenGi, sjUsed), chosenNominal,
                    v2ShutterRungsUs[sjUsed], laneCount, nBurst > 1, formMs, inferMs, totalMs,
                    chosenDets, path, tieBreak = if (allZero) "hold" else "conf",
                    applyBoost = false, captureMs = msBetween(t0, tForm))
                runCatching { onFrame(bmp, previewDets, chosenNominal, v2ShutterRungsUs[sjUsed]) }
                onStatus("V2 f=$f iso=$rung($phys) exp=${v2ShutterRungsUs[sjUsed] / 1000.0}ms " +
                    "conf=${"%.2f".format(scores[bestLane])}" +
                    (if (probe) " | probe" else "") + " | ${"%.0f".format(totalMs)}ms")
                f++
            }
        } finally {
            runCatching { raw.stopFastCapture() }
            runCatching { src.shutdown() }
            endPass()
        }
    }

    // ---------- Collect 5x3: the scheme's one burst plus the real photo of every cell ----------

    /** Which base ISO [runCollect53] shoots its burst at, as a running count taken modulo
     *  the number of levels. Negative (the default) steps the base every frame. A lap
     *  session sets it once per lap, so each lap is one base ISO from start to finish.
     *  Read before every frame, so it may change while the run is going. */
    @Volatile var collectBaseOverride: Int = -1

    // ---------- Collect 5x5: claim 1 on prop_c's own lattice ----------

    /** One request unit of a 5x5 collection frame: a burst of [n] frames at shutter [sj]
     *  and the base ISO, or one real shot of cell (gi, sj). */
    private class Unit55(val kind: String, val gi: Int, val sj: Int, val n: Int)

    /**
     * Claim 1 ("software synthesis reproduces the physical counterfactual") on the lattice
     * prop_c actually uses: ISO ladder x shutters 1/240..1/15. One frame holds
     *
     *  - the four bursts prop_c's probes take at the base ISO: 4 x 1/240, 4 x 1/120,
     *    4 x 1/60, 2 x 1/30. Each makes the rows prop_c makes from it by same-ISO sums of
     *    1, 2, 4 frames (the 1/240 and 1/120 sums have gaps between frames; flagged);
     *    every row at ratios to the neighbouring ISO rungs, and single frames at every ISO
     *    so large digital ratios are covered too;
     *  - the real photo of all 25 cells;
     *  - one repeat of the base ISO at 1/15 s. With the bursts' first frames (themselves
     *    real photos of the base row) this gives a real-versus-real pair at every shutter:
     *    the yardstick a synthesized-versus-real difference is judged against.
     *
     * Units are shuffled every frame, so no comparison always has the same time gap or the
     * same order. Every synthesized and real image is saved. [pipelineAB] alternates the
     * current image pipeline and the pre-2026-10-15 one frame by frame (for a static scene).
     *
     * [counterfactual] turns a frame into one moment of a replay record instead: four
     * back-to-back frames each at 1/240, 1/120 and 1/60 (about 200 ms), at the highest ISO
     * that does not clip, then one real cell. Every image is labelled with its use:
     * - "counterfactual": one image per cell built only from syntheses the 2026-10-16 claim-1
     *   analysis found faithful (1/240 and 1/120 from the first frame of their bursts; 1/60,
     *   1/30, 1/15 from 1, 2, 4 of the gap-free 1/60 frames; ISO by digital gain from the
     *   base, so darkening below it). What a baseline, or any method's physical output, sees.
     * - "proposed_input_only": sums of 2 and 4 of the 1/240 or 1/120 frames, the imperfect
     *   rows Sense-well's probe computes. For replaying the proposed's own decisions only;
     *   never a stand-in for a real capture (not faithful in the dark or in motion).
     * - "real_check": the one real cell, cycling through all 25, against which the
     *   counterfactual images keep being measured.
     *
     * [aligned] (collect_cf2, 2026-10-28) adds to a counterfactual moment, from the same
     * frames: every burst's 2- and 4-frame sums again after a tracking update
     * ([com.example.activeperception.acquire.BurstAlign]: each later frame moved back onto
     * the first, tile by tile, by the LK motion between consecutive frames), use "aligned";
     * digital ISO 3200 and 6400 (gain indices 5, 6) for every single frame and aligned sum,
     * use "gain_ext" for the single frames; and the tile motions (collect_motion.jsonl).
     */
    fun runCollect55(
        maxFrames: Int, pipelineAB: Boolean,
        onStatus: (String) -> Unit,
        onFrame: (Bitmap, List<Detection>, Int /*iso*/, Int /*expUs*/) -> Unit = { _, _, _, _ -> },
        counterfactual: Boolean = false,
        aligned: Boolean = false
    ) {
        val tfl = detector as? com.example.activeperception.acquire.TfliteYoloDetector
            ?: error("collect requires TfliteYoloDetector")
        val gains = v2GainRungs.copyOf()
        val ceil = v2AnalogCeil()
        val shutters = v2ShutterRungsUs.copyOf()
        val nG = gains.size
        // collect_cf2's digital rungs above the lattice: gain indices nG, nG + 1.
        val gainsExt = if (aligned) gains + intArrayOf(3200, 6400) else gains
        val nGx = gainsExt.size
        val nS = shutters.size
        val frameFloorUs = 16_750
        fun captured(gi: Int) = minOf(gains[gi], ceil)
        // Probe bursts by window-min shutter: (shutter index, frames).
        val bursts = if (counterfactual) listOf(0 to 4, 1 to 4, 2 to 4)
            else listOf(0 to 4, 1 to 4, 2 to 4, 3 to 2)
        // Counterfactual base: the highest rung whose 1/60 frame would clip on fewer than
        // this fraction of the sites, predicted from the previous moment's 1/60 frame.
        val cfClipLimit = 0.002
        // A counterfactual moment's 13 shots go in one request: a second request would wait
        // for the first one's last frame and open a gap in the middle of the moment.
        val CHUNK = if (counterfactual) 13 else 10
        startPass(if (aligned) "collect_cf2" else if (counterfactual) "collect_cf" else if (pipelineAB) "collect_5x5_ab"
            else "collect_5x5", isGtReference = false,
            withFrames = false, methodParams = JSONObject()
                .put("purpose", "claim 1: every image prop_c synthesizes, beside the real " +
                    "capture of the same setting and a real-vs-real yardstick")
                .put("session", v2SessionTag)
                .put("gain_levels", JSONArray(gains.toList()))
                .put("gain_captured_at", JSONArray(gains.map { minOf(it, ceil) }))
                .put("shutters_us", JSONArray(shutters.toList()))
                .put("bursts", JSONArray(bursts.map { JSONArray(listOf(shutters[it.first], it.second)) }))
                .put("synth_rule", "rows by FIRST_N same-ISO sums of 1, 2, 4 burst frames; " +
                    "single-frame rows at every ISO, summed rows at the base ISO and its neighbours")
                .put("gapped_rule", "a sum of frames shorter than the ${frameFloorUs} us frame " +
                    "interval has gaps between its frames")
                .put("yardstick", "burst first frames and one base-ISO 1/15 s repeat vs the real shots")
                .put("order", "units shuffled per frame (seeded by frame index)")
                .put("pipeline_ab", pipelineAB)
                .put("base_iso_rotation", if (collectBaseOverride >= 0) "one base ISO per lap"
                    else "frame index mod $nG")
                .put("cell_id", "gain_idx * $nS + shutter_idx")
                .put("class_filter", "none: every COCO class, 0.01 decode floor")
                .put("white_reference", "white minus black, for synthesized and real alike")
                .apply {
                    if (counterfactual) {
                        put("purpose", "counterfactual record for offline replay: the whole " +
                            "5x5 lattice synthesized from one moment (captures ~100 ms apart)")
                        put("synth_rule", "four back-to-back frames each at 1/240, 1/120, 1/60; " +
                            "every burst gives sums of 1, 2, 4 frames at every ISO by digital gain " +
                            "from the base")
                        put("uses", JSONObject()
                            .put("counterfactual", "one faithful image per cell: 1/240 and 1/120 " +
                                "from the first frame of their bursts, 1/60, 1/30, 1/15 from 1, 2, 4 " +
                                "of the 1/60 frames; for baselines and every method's output")
                            .put("proposed_input_only", "sums of 2 and 4 of the 1/240 or 1/120 " +
                                "frames (Sense-well's probe rows); for replaying the proposed's " +
                                "decisions only, never as a real capture")
                            .put("real_check", "the one real cell per moment"))
                        put("yardstick", "one real cell per moment right after the bursts, " +
                            "cell = frame x 7 mod 25 (all 25 every 25 moments)")
                        put("order", "bursts back to back, then the real cell; not shuffled")
                        put("base_iso_rotation", "none")
                        put("base_rule", "highest rung whose first 1/60 frame would clip on " +
                            "fewer than $cfClipLimit of the sites, predicted from the previous " +
                            "moment's first 1/60 frame; the first moment starts at the top rung")
                        put("gapped_note", "1/60 sums are flagged gapped by the rule above, but " +
                            "the gap is 83 us of 16750 (0.5%)")
                    }
                    if (aligned) {
                        put("gain_levels_ext", JSONArray(gainsExt.toList()))
                        put("aligned_rule", "every burst's 2- and 4-frame sums again after a tracking " +
                            "update: frames k = 1..n-1 moved back onto frame 0 tile by tile " +
                            "(${BurstAlign.TX} x ${BurstAlign.TY} tiles, whole 2x2 quads, CFA kept) by the " +
                            "accumulated LK motion between consecutive frames (quad luma at half quad " +
                            "resolution, ${BurstAlign.PTS} corners a tile, ${BurstAlign.LEVELS} levels, " +
                            "untracked or outlying tiles from their 3x3 neighbours), then summed as FIRST_N")
                        put("uses_ext", JSONObject()
                            .put("aligned", "tracking-updated sums, at every ISO incl. 3200 and 6400")
                            .put("gain_ext", "single frames at digital ISO 3200 and 6400"))
                        put("motion_log", "collect_motion.jsonl: per burst, the consecutive-frame tile " +
                            "steps (dx, dy, points; half-quad px = 4 sites = 2 px of the 1020-wide image)")
                    }
                })
        logger.csv("collect_frames", listOf("frame", "base_gi", "base_iso", "base_iso_captured",
            "pipeline", "order", "t_request_ns", "t_first_exposure_ns", "t_last_exposure_end_ns",
            "t_done_ns", "capture_span_ms", "wait_ms", "formation_ms", "infer_ms", "log_ms",
            "total_ms", "n_shots", "n_images", "yaw_rate", "total_yaw_deg", "lux", "accel",
            "battery_temp_c", "thermal_status", "cpu_pct", "cpu_mhz", "pss_kb", "java_heap_kb",
            "batt_charge_uah", "batt_plugged"))
        logger.csv("collect_shots", listOf("frame", "shot", "unit", "role", "gi", "sj", "burst_k",
            "nominal_iso", "iso_req", "iso_applied", "exp_req_us", "exp_applied_us",
            "sensor_ts_ns", "frame_duration_ns", "taken_ns", "focus_distance", "af_state",
            "lens_state", "raw_mean", "raw_clip_frac"))
        if (counterfactual) logger.csv("collect_base", listOf("frame", "base_gi", "next_base_gi",
            "clip_g0", "clip_g1", "clip_g2", "clip_g3", "clip_g4"))
        logger.csv("collect_cells", listOf("frame", "pipeline", "kind", "cell", "gi", "sj",
            "nominal_iso", "exposure_us", "base_gi", "burst_sj", "source_iso_captured",
            "n_frames", "gapped", "digital_ratio", "shot", "sensor_ts_ns", "mean_luma",
            "clip_frac", "n_det", "n_det_sel", "sum_conf_vehicle", "sum_conf_all", "img_path",
            "use"))

        val detsWriter = java.util.concurrent.Executors.newSingleThreadExecutor()
        val alignPool = java.util.concurrent.Executors.newFixedThreadPool(4)
        // Runs a burst's tracking update (which fans out on alignPool) beside the detector.
        val alignLead = java.util.concurrent.Executors.newSingleThreadExecutor()
        val laneBytes = 640 * 640 * 3 * 4
        val jpegPool = java.util.concurrent.Executors.newFixedThreadPool(4)
        val copyPermits = java.util.concurrent.Semaphore(12)
        val copies = java.util.concurrent.ConcurrentLinkedQueue<java.nio.ByteBuffer>()
        fun saveLaneJpeg(tensor: Exp21TensorResult, lane: Int, name: String): String {
            if (!saveImages) return ""
            // Every image matters here, so wait for a writer rather than skip.
            if (!copyPermits.tryAcquire(5, java.util.concurrent.TimeUnit.SECONDS)) return ""
            val copy = copies.poll() ?: java.nio.ByteBuffer.allocateDirect(laneBytes)
                .order(java.nio.ByteOrder.nativeOrder())
            val view = tensor.batch.input.duplicate()
            view.position(lane * laneBytes); view.limit((lane + 1) * laneBytes)
            copy.clear(); copy.put(view)
            jpegPool.execute {
                try {
                    val pixels = IntArray(640 * 640)
                    for (p in pixels.indices) {
                        val o = p * 12
                        val r = (copy.getFloat(o) * 255f).toInt().coerceIn(0, 255)
                        val g = (copy.getFloat(o + 4) * 255f).toInt().coerceIn(0, 255)
                        val bl = (copy.getFloat(o + 8) * 255f).toInt().coerceIn(0, 255)
                        pixels[p] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
                    }
                    val bmp = Bitmap.createBitmap(pixels, 640, 640, Bitmap.Config.ARGB_8888)
                    logger.saveJpeg(name, bmp)
                    bmp.recycle()
                } catch (_: Throwable) {
                } finally {
                    copies.offer(copy); copyPermits.release()
                }
            }
            return "img/$name.jpg"
        }
        fun usableRange(fr: RawFrame, black: Int) = RawFrame(fr.bayer, fr.width, fr.height,
            fr.cfaPattern, (fr.maxDn - black).coerceAtLeast(1.0), fr.sensorOrientation,
            fr.whiteBalance, fr.cameraToSrgb)

        val src = ParallelRawCandidateSource(grid, raw,
            colorPipeline = ColorPipeline.ORIGINAL_GAIN_SRGB, burstWindow = BurstWindow.FIRST_N)
        raw.configureDecodeThreads(4)
        raw.startFastCapture()
        raw.armNativeDecodeValidation()
        onStatus("collect 5x5: warming GPU batches…")
        tfl.warmUpAllBatches()
        /** Forms and detects lanes [laneN] x [ratio] of [frs] in chunks of at most [maxLanes]:
         *  the source caches one tensor buffer per lane count (17 lanes = 83 MB), so few small
         *  counts keep the 512 MB heap clear. Each lane goes to [each] (tensor, lane in that
         *  tensor, lane in the list, its detections) while its chunk's tensor is current.
         *  Returns the formation and inference ms. */
        fun formAndDetect(frs: List<RawFrame>, laneN: List<Int>, ratio: List<Double>, maxLanes: Int,
                          each: (Exp21TensorResult, Int, Int, List<Detection>) -> Unit): DoubleArray {
            var fMs = 0.0; var iMs = 0.0
            var start = 0
            while (start < laneN.size) {
                val end = minOf(laneN.size, start + maxLanes)
                val tf = now()
                val t = src.formExplicitBurstTensor(frs, laneN.subList(start, end).toIntArray(),
                    ratio.subList(start, end).toDoubleArray())
                fMs += ms(tf)
                val d = tfl.detectTensorBatchAllClasses(t.batch)
                iMs += tfl.lastRunMs + tfl.lastDecodeMs
                for (l in start until end) each(t, l - start, l, d[l - start])
                start = end
            }
            return doubleArrayOf(fMs, iMs)
        }
        // collect_cf2 forms up to 31 lanes a burst: in chunks of 7 (one 34 MB buffer).
        val maxLanes = if (aligned) 7 else Int.MAX_VALUE
        /** Fraction of a decoded frame's sampled sites (black removed) that would clip if its
         *  gain were multiplied by [scale]. */
        fun clipFractionAt(fr: RawFrame, scale: Double): Double {
            val px = fr.bayer
            val clipAt = (fr.maxDn - 4.0) / scale
            val step = (px.size / 100_000).coerceAtLeast(1) or 1
            var clipped = 0; var count = 0; var i = 0
            while (i < px.size) { if (px[i] >= clipAt) clipped++; count++; i += step }
            return if (count == 0) 0.0 else clipped.toDouble() / count
        }
        var cfBase = nG - 1
        var f = 0
        var failures = 0
        try {
            while (running && f < maxFrames) {
                val tFrame = now()
                val held = collectBaseOverride
                val b = when {
                    counterfactual -> cfBase
                    held >= 0 -> held % nG
                    else -> f % nG
                }
                val legacy = pipelineAB && f % 2 == 1
                val pipeline = if (legacy) "legacy" else "current"
                raw.decodeMode = if (legacy) 1 else 0
                src.resampleMode = if (legacy) 1 else 0

                val units = ArrayList<Unit55>()
                for ((sj, n) in bursts) units += Unit55("burst", b, sj, n)
                if (counterfactual) {
                    val cell = (f * 7) % (nG * nS)
                    units += Unit55("real", cell / nS, cell % nS, 1)
                } else {
                    for (gi in 0 until nG) for (sj in 0 until nS) units += Unit55("real", gi, sj, 1)
                    units += Unit55("repeat", b, nS - 1, 1)
                    units.shuffle(java.util.Random(f.toLong() * 7919L + 17L))
                }
                // Expand to shots, in requests of at most CHUNK shots (bursts never split). A
                // request is issued when the previous one's last frame has been taken, so at
                // most one request's decoded frames (12.5 MB each) wait in memory: 40 shots
                // in flight at once exhausted the 512 MB heap.
                class Shot(val unit: Int, val k: Int, val chunk: Int, val idx: Int)
                val shots = ArrayList<Shot>()
                val chunkSizes = ArrayList<Int>()
                run {
                    var chunk = 0; var inChunk = 0
                    for ((ui, u) in units.withIndex()) {
                        if (inChunk + u.n > CHUNK && inChunk > 0) { chunkSizes += inChunk; chunk++; inChunk = 0 }
                        repeat(u.n) { k -> shots += Shot(ui, k, chunk, inChunk); inChunk++ }
                    }
                    chunkSizes += inChunk
                }
                fun setting(s: Shot): Pair<Int, Int> { val u = units[s.unit]; return shutters[u.sj] to captured(u.gi) }
                val handles = arrayOfNulls<RawSensorCapturer.BracketHandle>(chunkSizes.size)
                fun issue(c: Int) {
                    if (c < chunkSizes.size && handles[c] == null)
                        handles[c] = raw.beginBracketSequence(shots.filter { it.chunk == c }.map(::setting))
                }
                val tRequestNs = SystemClock.elapsedRealtimeNanos()
                issue(0)

                var waitMs = 0.0; var formMs = 0.0; var inferMs = 0.0; var logMs = 0.0
                var firstExposureNs = Long.MAX_VALUE; var lastExposureEndNs = 0L
                var nImages = 0
                val burst = ArrayList<RawFrame>(4)
                var burstShot = -1; var burstTs = -1L
                var preview: Bitmap? = null
                var previewDets: List<Detection> = emptyList()
                var ok = true
                fun logCell(kind: String, gi: Int, sj: Int, burstSj: Int, sourceIso: Int, nFrames: Int,
                            gapped: Boolean, ratio: Double, shot: Int, ts: Long, stats: DoubleArray,
                            dets: List<Detection>, imgPath: String, use: String = "") {
                    val cell = gi * nS + sj
                    var sumAll = 0.0; var nSel = 0
                    for (d in dets) if (d.confidence >= selectConf) { sumAll += d.confidence; nSel++ }
                    logger.row("collect_cells", listOf(f, pipeline, kind, cell, gi, sj, gainsExt[gi],
                        shutters[sj], b, burstSj, sourceIso, nFrames, if (gapped) 1 else 0,
                        "%.4f".format(ratio), shot, ts, "%.4f".format(stats[0]), "%.5f".format(stats[1]),
                        dets.size, nSel, "%.3f".format(sumConf(dets)), "%.3f".format(sumAll), imgPath,
                        use))
                    val fr = f
                    detsWriter.execute {
                        val arr = JSONArray()
                        for (d in dets) arr.put(detJson(d))
                        logger.jsonl("collect_dets", JSONObject().put("frame", fr).put("pipeline", pipeline)
                            .put("kind", kind).put("cell", cell).put("base_gi", b).put("burst_sj", burstSj)
                            .put("n_frames", nFrames).put("use", use).put("dets", arr))
                    }
                }
                try {
                    for (i in shots.indices) {
                        val s = shots[i]
                        val u = units[s.unit]
                        val tw = now()
                        val handle = handles[s.chunk]!!
                        val hi = s.idx
                        val (taken, meta) = raw.takeBracketFrame(handle, hi)
                        // This request is fully in hand: start the next one now.
                        if (hi == chunkSizes[s.chunk] - 1) issue(s.chunk + 1)
                        waitMs += ms(tw)
                        val takenNs = SystemClock.elapsedRealtimeNanos()
                        if (meta.timestamp in 1 until firstExposureNs) firstExposureNs = meta.timestamp
                        lastExposureEndNs = maxOf(lastExposureEndNs, meta.timestamp + meta.appliedExpUs * 1_000L)
                        val tl0 = now()
                        val rawStats = rawMeanAndClip(taken, meta.black)
                        val frame = usableRange(taken, meta.black)
                        logger.row("collect_shots", listOf(f, i, s.unit, u.kind, u.gi, u.sj,
                            if (u.kind == "burst") s.k else -1, gains[u.gi], meta.requestedIso,
                            meta.appliedIso, meta.requestedExpUs, meta.appliedExpUs, meta.timestamp,
                            meta.frameDurationNs, takenNs, handle.focusDistance[hi],
                            handle.afState[hi], handle.lensState[hi],
                            "%.4f".format(rawStats[0]), "%.5f".format(rawStats[1])))
                        logMs += ms(tl0)
                        if (u.kind == "burst") {
                            if (s.k == 0) { burstShot = i; burstTs = meta.timestamp }
                            if (counterfactual && s.k == 0 && u.sj == 2) {
                                val clip = DoubleArray(nG) { g ->
                                    clipFractionAt(frame, captured(g).toDouble() / captured(b))
                                }
                                val next = (nG - 1 downTo 0).firstOrNull { clip[it] < cfClipLimit } ?: 0
                                logger.row("collect_base", listOf(f, b, next) +
                                    clip.map { "%.5f".format(it) })
                                cfBase = next
                            }
                            burst += frame
                            if (s.k < u.n - 1) continue
                            // Rows prop_c makes from this burst: n = 1, 2, 4 frames.
                            val laneBurst = ArrayList<Int>(); val laneRatio = ArrayList<Double>()
                            val laneGi = ArrayList<Int>(); val laneSj = ArrayList<Int>()
                            var n = 1; var row = u.sj
                            while (n <= u.n && row < nS) {
                                val gis = if (n == 1 && aligned) (0 until nGx).toList()
                                    else if (n == 1 || counterfactual) (0 until nG).toList()
                                    else (maxOf(0, b - 1)..minOf(nG - 1, b + 1)).toList()
                                for (g in gis) {
                                    laneBurst += n; laneRatio += gainsExt[g].toDouble() / captured(b)
                                    laneGi += g; laneSj += row
                                }
                                n *= 2; row++
                            }
                            // collect_cf2: the tracking update (CPU) runs while the plain sums are
                            // formed and detected (GPU), and is joined after them.
                            val frozen = ArrayList(burst)
                            val alignJob = if (aligned && u.n > 1) alignLead.submit(java.util.concurrent.Callable {
                                val ta = now()
                                val bw = frozen[0].width; val bh = frozen[0].height
                                val pyr = frozen.map { fr -> alignPool.submit(java.util.concurrent.Callable {
                                    BurstAlign.pyramid(fr.bayer, fr.width, fr.height) }) }.map { it.get() }
                                val steps = (1 until frozen.size).map { k -> BurstAlign.step(pyr[k - 1], pyr[k], alignPool) }
                                val acc = BurstAlign.accumulate(steps)
                                val movedJobs = acc.withIndex().map { (k, m) -> alignPool.submit(java.util.concurrent.Callable {
                                    BurstAlign.moveBack(frozen[k + 1].bayer, bw, bh, m) }) }
                                val moved = ArrayList<RawFrame>(frozen.size)
                                moved += frozen[0]
                                movedJobs.forEachIndexed { k, job ->
                                    val fr = frozen[k + 1]
                                    moved += RawFrame(job.get(), fr.width, fr.height, fr.cfaPattern, fr.maxDn,
                                        fr.sensorOrientation, fr.whiteBalance, fr.cameraToSrgb)
                                }
                                Triple(moved, steps, ms(ta))
                            }) else null
                            val tl = now()
                            val fi = formAndDetect(burst, laneBurst, laneRatio, maxLanes) { tensor, tLane, lane, laneDets ->
                                val gi = laneGi[lane]; val sj = laneSj[lane]
                                val stats = lumaAndClipFromTensorLane(tensor, tLane)
                                val gapped = laneBurst[lane] > 1 && shutters[u.sj] < frameFloorUs
                                // Sums of short frames are the proposed's imperfect probe rows.
                                val use = when {
                                    !counterfactual -> ""
                                    gi >= nG -> "gain_ext"
                                    laneBurst[lane] > 1 && u.sj < 2 -> "proposed_input_only"
                                    else -> "counterfactual"
                                }
                                val tag = when (use) {
                                    "counterfactual" -> "cf_"; "proposed_input_only" -> "pi_"; "gain_ext" -> "gx_"; else -> ""
                                }
                                val path = saveLaneJpeg(tensor, tLane, "${frameName(f)}_${tag}s${laneBurst[lane]}x" +
                                    "${shutters[u.sj]}_b${gains[b]}_g${gainsExt[gi]}")
                                logCell("synth", gi, sj, u.sj, captured(b), laneBurst[lane], gapped,
                                    laneRatio[lane], burstShot, burstTs, stats, laneDets, path, use)
                                if (laneBurst[lane] == 1 && gi == b && u.sj == 2) {
                                    preview = src.selectedTensorBitmap(tensor, tLane)
                                    previewDets = src.detectionsForTensorPreview(laneDets, tensor, tLane)
                                }
                            }
                            formMs += fi[0]; inferMs += fi[1]
                            nImages += laneBurst.size
                            logMs += ms(tl) - fi[0] - fi[1]
                            if (alignJob != null) {
                                // Tracking update: frames 1..n-1 moved back onto frame 0.
                                val tw0 = now()
                                val (moved, steps, alignMs) = alignJob.get()
                                val alignWaitMs = ms(tw0)
                                val aBurst = ArrayList<Int>(); val aRatio = ArrayList<Double>()
                                val aGi = ArrayList<Int>(); val aSj = ArrayList<Int>()
                                var an = 2; var arow = u.sj + 1
                                while (an <= u.n && arow < nS) {
                                    for (g in 0 until nGx) {
                                        aBurst += an; aRatio += gainsExt[g].toDouble() / captured(b)
                                        aGi += g; aSj += arow
                                    }
                                    an *= 2; arow++
                                }
                                val tal = now()
                                val afi = formAndDetect(moved, aBurst, aRatio, maxLanes) { aTensor, tLane, lane, laneDets ->
                                    val gi = aGi[lane]; val sj = aSj[lane]
                                    val stats = lumaAndClipFromTensorLane(aTensor, tLane)
                                    val gapped = shutters[u.sj] < frameFloorUs
                                    val path = saveLaneJpeg(aTensor, tLane, "${frameName(f)}_al_s${aBurst[lane]}x" +
                                        "${shutters[u.sj]}_b${gains[b]}_g${gainsExt[gi]}")
                                    logCell("aligned", gi, sj, u.sj, captured(b), aBurst[lane], gapped,
                                        aRatio[lane], burstShot, burstTs, stats, laneDets, path, "aligned")
                                }
                                formMs += afi[0]; inferMs += afi[1]
                                nImages += aBurst.size
                                val fr = f; val bsj = u.sj
                                val stepJson = JSONArray(steps.map { st -> JSONArray(st.map { t ->
                                    JSONArray(listOf(Math.round(t[0] * 1000) / 1000.0, Math.round(t[1] * 1000) / 1000.0, t[2].toInt())) }) })
                                detsWriter.execute {
                                    logger.jsonl("collect_motion", JSONObject().put("frame", fr).put("burst_sj", bsj)
                                        .put("tiles", JSONArray(listOf(BurstAlign.TX, BurstAlign.TY)))
                                        .put("align_ms", alignMs).put("align_wait_ms", alignWaitMs).put("steps", stepJson))
                                }
                                logMs += ms(tal) - afi[0] - afi[1]
                            }
                            burst.clear()
                        } else {
                            val ratio = gains[u.gi].toDouble() / captured(u.gi)
                            val tf = now()
                            val tensor = src.formExplicitGainsTensor(frame, doubleArrayOf(ratio))
                            formMs += ms(tf)
                            val dets = tfl.detectTensorBatchAllClasses(tensor.batch)[0]
                            inferMs += tfl.lastRunMs + tfl.lastDecodeMs
                            val tl = now()
                            val stats = lumaAndClipFromTensorLane(tensor, 0)
                            val path = saveLaneJpeg(tensor, 0, "${frameName(f)}_${u.kind}_g${gains[u.gi]}_e${shutters[u.sj]}")
                            logCell(u.kind, u.gi, u.sj, -1, captured(u.gi), 1, false, ratio, i,
                                meta.timestamp, stats, dets, path, if (counterfactual) "real_check" else "")
                            detectionTotalAtFloor += dets.size
                            detectionTotalAboveThresh += dets.count { it.confidence >= selectConf }
                            nImages++
                            logMs += ms(tl)
                        }
                    }
                } catch (t: Throwable) {
                    handles.forEach { h -> h?.let { raw.abortBracketSequence(it) } }
                    ok = false; failures++
                    android.util.Log.e("SosCollect", "frame $f failed", t)
                    onStatus("collect 5x5: frame $f failed (${t.message})")
                }
                if (!ok) {
                    // The failed frame keeps its own index (its partial rows stay in the
                    // shot and cell logs) and is marked here, so a retry never shares it.
                    logger.row("collect_frames", listOf(f, b, gains[b], captured(b), pipeline,
                        "failed", tRequestNs))
                    burst.clear()
                    f++
                    if (failures >= 3) break
                    continue
                }
                failures = 0
                if (f == 0) writeDecodeCheck()
                val tDoneNs = SystemClock.elapsedRealtimeNanos()
                val totalMs = ms(tFrame)
                val h = health.sample()
                logger.row("collect_frames", listOf(f, b, gains[b], captured(b), pipeline,
                    units.joinToString(" ") { "${it.kind[0]}${it.gi}${it.sj}" },
                    tRequestNs, firstExposureNs, lastExposureEndNs, tDoneNs,
                    "%.1f".format((lastExposureEndNs - firstExposureNs) / 1e6),
                    "%.1f".format(waitMs), "%.1f".format(formMs), "%.1f".format(inferMs),
                    "%.1f".format(logMs), "%.1f".format(totalMs), shots.size, nImages,
                    sensors.currentYawRate, "%.2f".format(sensors.totalYawDeg),
                    sensors.currentLux, sensors.currentAccel, h.batteryTemperatureC, h.thermalStatus,
                    if (h.cpuPercent < 0) "" else "%.1f".format(h.cpuPercent), h.cpuMaxMhz,
                    h.totalPssKb, h.javaHeapKb,
                    if (h.chargeUAh == Long.MIN_VALUE) "" else h.chargeUAh, h.pluggedState))
                lastFrameIdx = f
                totalFramesLogged += 1
                preview?.let { bmp -> runCatching { onFrame(bmp, previewDets, gains[b], shutters[2]) } }
                onStatus((if (aligned) "COLLECT CF2" else if (counterfactual) "COLLECT CF" else "COLLECT55") +
                    " f=$f base ISO ${gains[b]} $pipeline | ${shots.size} shots, " +
                    "$nImages images | ${"%.0f".format(totalMs)}ms")
                f++
            }
        } finally {
            raw.decodeMode = 0
            src.resampleMode = 0
            runCatching { raw.stopFastCapture() }
            runCatching { src.shutdown() }
            runCatching {
                jpegPool.shutdown(); detsWriter.shutdown(); alignPool.shutdownNow(); alignLead.shutdownNow()
                jpegPool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)
                detsWriter.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)
            }
            endPass()
        }
    }

    /** Mean level and clipped fraction of a decoded Bayer frame, both relative to the
     *  usable range above black. Sampled, so it costs a fraction of a millisecond. */
    private fun rawMeanAndClip(frame: RawFrame, black: Int): DoubleArray {
        val px = frame.bayer
        val top = (frame.maxDn - black).coerceAtLeast(1.0)
        val clipAt = top - 4.0
        val step = (px.size / 100_000).coerceAtLeast(1) or 1   // odd, so all CFA sites are hit
        var sum = 0L
        var clipped = 0
        var count = 0
        var i = 0
        while (i < px.size) {
            val v = px[i]
            sum += v
            if (v >= clipAt) clipped++
            count++
            i += step
        }
        return if (count == 0) doubleArrayOf(0.0, 0.0)
        else doubleArrayOf(sum.toDouble() / count / top, clipped.toDouble() / count)
    }

    /** Mean luma and clipped fraction of one tensor lane, over the image area only: the
     *  letterbox padding is a flat gray that would otherwise pull every mean toward it. */
    private fun lumaAndClipFromTensorLane(tensor: Exp21TensorResult, lane: Int): DoubleArray {
        val input = tensor.batch.input
        val t = tensor.batch.transforms[lane]
        val size = 640
        val padX = Math.ceil(t.padX).toInt().coerceIn(0, size / 2 - 1)
        val padY = Math.ceil(t.padY).toInt().coerceIn(0, size / 2 - 1)
        val base = lane * size * size * 3 * 4
        var sum = 0.0
        var clipped = 0
        var count = 0
        var y = padY
        while (y < size - padY) {
            var x = padX + (y % 3)
            while (x < size - padX) {
                val o = base + (y * size + x) * 12
                val r = input.getFloat(o)
                val g = input.getFloat(o + 4)
                val bl = input.getFloat(o + 8)
                sum += r * 0.299 + g * 0.587 + bl * 0.114
                if (r >= 0.995f || g >= 0.995f || bl >= 0.995f) clipped++
                count++
                x += 3
            }
            y += 3
        }
        return if (count == 0) doubleArrayOf(0.0, 0.0)
        else doubleArrayOf(sum / count, clipped.toDouble() / count)
    }

    // ---------- shared helpers ----------

    /** Writes the manifest, opens the shared logs, and arms [running]. Modes with their own
     *  row schema pass `withFrames = false`. */
    private fun startPass(method: String, isGtReference: Boolean,
                          withFrames: Boolean = true,
                          methodParams: JSONObject? = null) {
        passStartMs = System.currentTimeMillis()
        val w = if (raw.captureWidth > 0) raw.captureWidth else -1
        val h = if (raw.captureHeight > 0) raw.captureHeight else -1
        writeManifest(method, isGtReference, w, h, methodParams)
        if (withFrames) logger.csv("frames", headers())
        wireImuLog()
        detectionTotalAboveThresh = 0
        detectionTotalAtFloor = 0
        totalFramesLogged = 0
        running = true
    }

    private fun endPass() {
        unwireImuLog()
        logger.flush()
        // Written so the run's totals can be cited from the logs alone.
        runCatching {
            val summary = JSONObject()
                .put("total_frames_logged", totalFramesLogged)
                .put("total_dets_above_threshold", detectionTotalAboveThresh)
                .put("total_dets_at_floor", detectionTotalAtFloor)
                .put("select_conf_threshold", selectConf)
                .put("detector_conf_floor", 0.01)
                .put("ts_ms", System.currentTimeMillis())
            val sp = speedMeter.result(sensors.totalYawDeg, System.currentTimeMillis())
            ExperimentSession.lastLapS = sp.lapS
            fun num(v: Double): Any = if (v.isNaN()) JSONObject.NULL else Math.round(v * 1000) / 1000.0
            summary.put("limo_measured", JSONObject()
                .put("seconds", num(sp.seconds)).put("laps", num(sp.laps)).put("lap_s", num(sp.lapS))
                .put("turn_yaw_rate_rad_s", num(sp.turnRateRadS))
                .put("turn_threshold_rad_s", LimoSpeedMeter.TURN_RAD_S)
                .put("gravity_locked", sensors.gravityLocked))
            java.io.File(logger.dir, "summary.json").writeText(summary.toString(2))
        }
    }

    private fun frameName(idx: Int) = "frame_%04d".format(idx)

    /** Mean RAW value as a fraction of white, for Custom AE's brightness feedback. */
    private fun meanRawRatio(frame: RawFrame): Double {
        val pixels = frame.bayer
        val step = (pixels.size / 100_000).coerceAtLeast(1)
        var sum = 0L; var count = 0
        var i = 0
        while (i < pixels.size) { sum += pixels[i].toLong(); count++; i += step }
        return if (count == 0 || frame.maxDn <= 0) 0.0
        else (sum.toDouble() / count) / frame.maxDn
    }

    /** Closed-loop AE driving mean brightness toward [targetRatio]. ISO absorbs the correction
     *  first and exposure only grows once ISO clamps, which keeps motion blur down. Unlike
     *  phone AE it is fully determined by the brightness sequence, so runs are reproducible. */
    /** Custom AE clamped to the device's real sensitivity range: a request past the range
     *  is never metadata-approved, so the capture times out ("obtained 0/N ... ISO1600
     *  failures=0"). Also capped at the analog ceiling when the HAL reports one — the S26U
     *  fine sweep measured RAW response linear to exactly ISO 1200 and DEAD above it, so
     *  requesting more only mislabels the exposure metadata without changing the pixels. */
    private fun customAeForDevice(): CustomAeBrightness {
        val rawCeiling = if (raw.maxAnalogIso > 0) raw.maxAnalogIso else raw.sensorIsoMax
        return CustomAeBrightness(
            isoMin = maxOf(100, raw.sensorIsoMin),
            isoMax = minOf(1600, rawCeiling))
    }

    private class CustomAeBrightness(
        val targetRatio: Double = 0.40,    // bright but short of clipping
        val isoMin: Int = 100,
        val isoMax: Int = 1600,
        // Exposure clamps aligned to the v2 lattice envelope (1/480..1/30), so every
        // method shares one operating range; 33 ms doubles as the motion-blur cap.
        val expMinUs: Int = 2083,
        val expMaxUs: Int = 33_333,
        val maxStep: Double = 4.0          // per-call scale limit, damps dim -> bright swings
    ) {
        fun next(currentIso: Int, currentExpUs: Int, lastRatio: Double): Pair<Int, Int> {
            if (lastRatio < 0.001) return Pair(currentIso, currentExpUs)  // no signal — hold
            val scale = (targetRatio / lastRatio).coerceIn(1.0 / maxStep, maxStep)
            val newIso = (currentIso * scale).toInt().coerceIn(isoMin, isoMax)
            val resScale = scale * currentIso / newIso       // what ISO could not absorb
            val newExp = (currentExpUs * resScale).toInt().coerceIn(expMinUs, expMaxUs)
            return Pair(newIso, newExp)
        }

        fun toJson(): JSONObject = JSONObject()
            .put("target_ratio", targetRatio)
            .put("iso_min", isoMin).put("iso_max", isoMax)
            .put("exp_min_us", expMinUs).put("exp_max_us", expMaxUs)
            .put("max_step", maxStep)
    }

    /** ISO + exposure tag for filenames. Milliseconds when the exposure is whole, microseconds
     *  otherwise, so it is always lossless. No dots — they confuse extension parsing. */
    private fun isoExpTag(iso: Int, expUs: Int): String =
        if (expUs % 1000 == 0) "iso${iso}_exp${expUs / 1000}ms"
        else "iso${iso}_exp${expUs}us"

    /** Physical ISO × digitalBoost. Every bitmap goes through a formation path that applies
     *  the boost, so this is the number that matches what a JPEG actually looks like, and it
     *  is what JPEG filenames and the cell-grid UI use. RAW files keep physical ISO. */
    private fun effIso(iso: Int): Int = (iso * grid.digitalBoost).toInt()

    /** One bitmap from one capture that was already taken at the requested cell, so no digital
     *  re-gain beyond digitalBoost. Goes through the parallel path for the LUT and worker pool. */
    private fun bitmapFromRaw(frame: RawFrame): Bitmap =
        parallelFormation.formAllCells(listOf(frame), intArrayOf(grid.cell(0, 0)))[0]

    /** Shared formation path for the modes that capture separately from forming. Same
     *  implementation Proposed uses, so Bench measures the deployed cost. */
    private val parallelFormation by lazy {
        ParallelRawCandidateSource(grid, raw, colorPipeline = colorPipeline)
    }
}

/**
 * Lap-session state that the activity sets and every run reads: the activity builds a new
 * MeasurementController for each run, so it cannot live on the controller (it did until
 * 2026-10-20, and a run's first lap was logged as -1 and the lighting was lost).
 */
object ExperimentSession {
    /** 0 when a block's run starts, the block's completed laps at every new lap, -1
     *  outside a lap session. */
    @Volatile var runLap: Int = -1
    /** The lighting the operator pressed (bright / dim / dark), and the session id; written
     *  into every run's manifest while set. */
    @Volatile var lighting: String = ""
    @Volatile var session: String = ""
    /** The Limo speed the operator picked (slow / avg / fast, "" for none); kept across runs
     *  until changed and written into every run's manifest while set. */
    @Volatile var limoSpeed: String = ""
    /** The last run's lap time from the gyro, s; NaN when it turned under half a lap. */
    @Volatile var lastLapS: Double = Double.NaN
}
