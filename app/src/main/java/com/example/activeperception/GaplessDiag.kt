package com.example.activeperception

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.StreamConfigurationMap
import android.hardware.camera2.params.TonemapCurve
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Stand-alone probe for gap-free reads (2026-10-27), started with `--ez gapless_diag true`.
 *
 * Full-resolution RAW comes every 16.7 ms, so a 1/240 s read leaves a 12.5 ms gap and a sum of
 * reads shows the car twice. This asks whether any output the phone offers reads back to back:
 * interval = exposure (1/240 s every 4.17 ms, 1/120 s every 8.33 ms).
 *
 * Stage 1 writes what every camera declares (all sizes' minimum frame and stall durations for
 * RAW_SENSOR, RAW10, RAW12, YUV_420_888 and PRIVATE, high-speed sizes and fps ranges, exposure
 * range, max frame duration, capabilities). Declarations are known to differ from practice, so
 * they only pick candidates.
 *
 * Stage 2 captures short bursts on candidates: the smallest and largest RAW size, YUV near
 * 1920x1080 and 1280x720 on every back camera, and constrained high-speed sessions at 120 and
 * 240 fps. Per read it logs SENSOR_TIMESTAMP, the result's exposure, frame duration, ISO and
 * rolling-shutter skew; gap = timestamp spacing - previous read's exposure. Bursts are run with
 * SENSOR_FRAME_DURATION = exposure and with it unset, and with the exposure alternating
 * 1/240 <-> 1/120 frame to frame. YUV is also run "linear" (tonemap contrast curve or gamma 1,
 * noise reduction and edge off) and its centre luma is logged, so the 1/120 : 1/240 luma ratio
 * shows whether the output really is linear (2.0). High speed also logs whether manual exposure
 * holds, whether a mixed-exposure batch is accepted, and whether a YUV ImageReader may be its
 * output.
 *
 * Independent of [RawSensorCapturer] and of every measurement run (it must not overlap one).
 * Output goes to logcat under [TAG] and to gapless_diag.csv in the app's external files.
 */
object GaplessDiag {
    const val TAG = "SoSGaplessDiag"
    private val busy = AtomicBoolean(false)
    private const val E240 = 4_166_667L
    private const val E120 = 8_333_333L
    private const val ISO = 400
    private const val N = 8

    private val FORMATS = linkedMapOf(
        ImageFormat.RAW_SENSOR to "RAW_SENSOR", ImageFormat.RAW10 to "RAW10", ImageFormat.RAW12 to "RAW12",
        ImageFormat.YUV_420_888 to "YUV_420_888", ImageFormat.PRIVATE to "PRIVATE")
    private val CAPS = mapOf(
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR to "MANUAL_SENSOR",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING to "MANUAL_POST_PROCESSING",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW to "RAW",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS to "READ_SENSOR_SETTINGS",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE to "BURST_CAPTURE",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO to "CONSTRAINED_HIGH_SPEED_VIDEO",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA to "LOGICAL_MULTI_CAMERA",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR to "ULTRA_HIGH_RESOLUTION_SENSOR")

    private class Shot(val expNs: Long, val frameDurNs: Long?)
    private class Row(val ts: Long, val expNs: Long, val frameDurNs: Long, val iso: Int, val skewNs: Long,
                      val tonemap: Int, val nr: Int, val edge: Int, val ae: Int)

    @SuppressLint("MissingPermission")
    fun run(context: Context, onStatus: (String) -> Unit) {
        if (!busy.compareAndSet(false, true)) return
        val text = StringBuilder()
        val out: (String) -> Unit = { line -> Log.i(TAG, line); synchronized(text) { text.append(line).append('\n') } }
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val camThread = HandlerThread("gapless-cam").apply { start() }
        val imgThread = HandlerThread("gapless-img").apply { start() }
        val camH = Handler(camThread.looper)
        val imgH = Handler(imgThread.looper)
        try {
            onStatus("gapless diag: declared tables")
            val ids = manager.cameraIdList.toList()
            out("IDS,${ids.joinToString(";")}")
            val described = LinkedHashSet<String>()
            for (id in ids) {
                val ch = manager.getCameraCharacteristics(id)
                if (described.add(id)) describe(id, ch, out, null)
                for (p in ch.physicalCameraIds) if (described.add(p)) {
                    runCatching { describe(p, manager.getCameraCharacteristics(p), out, id) }
                        .onFailure { out("FAILED,describe,$p,${it.javaClass.simpleName},${it.message}") }
                }
            }
            for (id in ids) {
                val ch = manager.getCameraCharacteristics(id)
                if (ch.get(CameraCharacteristics.LENS_FACING) != CameraMetadata.LENS_FACING_BACK) continue
                val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
                val cands = ArrayList<Pair<Int, Size>>()
                for (fmt in listOf(ImageFormat.RAW_SENSOR, ImageFormat.RAW10, ImageFormat.RAW12)) {
                    val sizes = runCatching { map.getOutputSizes(fmt) }.getOrNull()?.sortedBy { it.width * it.height } ?: continue
                    if (sizes.isEmpty()) continue
                    cands += fmt to sizes.first()
                    if (sizes.last() != sizes.first()) cands += fmt to sizes.last()
                }
                map.getOutputSizes(ImageFormat.YUV_420_888)?.let { sizes ->
                    for (want in listOf(Size(1920, 1080), Size(1280, 720))) {
                        val pick = sizes.filter { it.width >= 640 && it.height >= 480 }
                            .minByOrNull { kotlin.math.abs(it.width * it.height - want.width * want.height) } ?: continue
                        if (cands.none { it.first == ImageFormat.YUV_420_888 && it.second == pick }) cands += ImageFormat.YUV_420_888 to pick
                    }
                }
                for ((fmt, size) in cands) {
                    onStatus("gapless diag: cam $id ${FORMATS[fmt]} ${size.width}x${size.height}")
                    runCatching { probeRegular(manager, id, ch, fmt, size, camH, imgH, out) }
                        .onFailure { out("FAILED,regular,cam$id,${FORMATS[fmt]},${size.width}x${size.height},${it.javaClass.simpleName},${it.message}") }
                    Thread.sleep(400)
                }
                val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty()
                if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO in caps) {
                    for (size in map.highSpeedVideoSizes.sortedByDescending { it.width * it.height }.take(2)) {
                        val ranges = map.getHighSpeedVideoFpsRangesFor(size).toList()
                        for (fps in listOf(120, 240)) {
                            if (ranges.none { it.lower == fps && it.upper == fps }) continue
                            onStatus("gapless diag: cam $id high speed ${size.width}x${size.height} $fps fps")
                            runCatching { probeHighSpeed(manager, id, size, fps, camH, imgH, out) }
                                .onFailure { out("FAILED,highspeed,cam$id,${size.width}x${size.height},$fps,${it.javaClass.simpleName},${it.message}") }
                            Thread.sleep(400)
                        }
                    }
                }
            }
            out("DONE")
            onStatus("gapless diag done")
        } catch (t: Throwable) {
            out("FAILED,${t.javaClass.simpleName},${t.message}")
            onStatus("gapless diag failed: ${t.message}")
        } finally {
            camThread.quitSafely(); imgThread.quitSafely()
            runCatching { File(context.getExternalFilesDir(null), "gapless_diag.csv").writeText(synchronized(text) { text.toString() }) }
            busy.set(false)
        }
    }

    private fun describe(id: String, ch: CameraCharacteristics, out: (String) -> Unit, physicalOf: String?) {
        val facing = when (ch.get(CameraCharacteristics.LENS_FACING)) {
            CameraMetadata.LENS_FACING_BACK -> "back"; CameraMetadata.LENS_FACING_FRONT -> "front"; else -> "external" }
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.map { CAPS[it] ?: "cap$it" }.orEmpty()
        out("CAMERA,$id,physical_of=${physicalOf ?: ""},facing=$facing" +
            ",focal_mm=${ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString(";")}" +
            ",hw_level=${ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)}" +
            ",caps=${caps.joinToString(";")}" +
            ",exposure_range_ns=${ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)}" +
            ",max_frame_duration_ns=${ch.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)}" +
            ",iso_range=${ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)}" +
            ",pixel_array=${ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)}" +
            ",physical_ids=${ch.physicalCameraIds.joinToString(";")}" +
            ",tonemap_modes=${ch.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)?.joinToString(";")}" +
            ",nr_modes=${ch.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.joinToString(";")}" +
            ",edge_modes=${ch.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.joinToString(";")}")
        ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.let { streams(id, "default", it, out) }
        ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)?.let { streams(id, "max_resolution", it, out) }
    }

    private fun streams(id: String, mode: String, map: StreamConfigurationMap, out: (String) -> Unit) {
        for ((fmt, name) in FORMATS) {
            val sizes = runCatching { map.getOutputSizes(fmt) }.getOrNull() ?: continue
            for (s in sizes.sortedByDescending { it.width * it.height }) {
                out("STREAM,$id,$mode,$name,${s.width}x${s.height}" +
                    ",min_frame_ns=${runCatching { map.getOutputMinFrameDuration(fmt, s) }.getOrDefault(-1L)}" +
                    ",stall_ns=${runCatching { map.getOutputStallDuration(fmt, s) }.getOrDefault(-1L)}")
            }
        }
        if (mode == "default") for (s in map.highSpeedVideoSizes) {
            out("HIGH_SPEED,$id,${s.width}x${s.height},fps=${map.getHighSpeedVideoFpsRangesFor(s).joinToString(";")}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun open(manager: CameraManager, id: String, h: Handler): CameraDevice {
        var dev: CameraDevice? = null
        var err = 0
        val latch = CountDownLatch(1)
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) { dev = camera; latch.countDown() }
            override fun onDisconnected(camera: CameraDevice) { camera.close(); latch.countDown() }
            override fun onError(camera: CameraDevice, error: Int) { err = error; camera.close(); latch.countDown() }
        }, h)
        check(latch.await(5, TimeUnit.SECONDS) && dev != null) { "camera $id open failed (error $err)" }
        return dev!!
    }

    private fun session(dev: CameraDevice, surfaces: List<Surface>, h: Handler, type: Int): CameraCaptureSession {
        var s: CameraCaptureSession? = null
        val latch = CountDownLatch(1)
        dev.createCaptureSession(SessionConfiguration(type, surfaces.map { OutputConfiguration(it) }, Executor { h.post(it) },
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(value: CameraCaptureSession) { s = value; latch.countDown() }
                override fun onConfigureFailed(value: CameraCaptureSession) { latch.countDown() }
            }))
        check(latch.await(5, TimeUnit.SECONDS) && s != null) { "session configure failed" }
        return s!!
    }

    /** Mean of the luma plane's central quarter, every 8th pixel. */
    private fun centreLuma(img: Image): Double {
        val p = img.planes[0]; val buf = p.buffer; val rs = p.rowStride; val ps = p.pixelStride
        var sum = 0L; var n = 0
        var y = img.height / 4
        while (y < 3 * img.height / 4) {
            var x = img.width / 4
            while (x < 3 * img.width / 4) { sum += buf.get(y * rs + x * ps).toInt() and 0xFF; n++; x += 8 }
            y += 8
        }
        return if (n > 0) sum.toDouble() / n else Double.NaN
    }

    private fun rowOf(r: TotalCaptureResult) = Row(
        r.get(CaptureResult.SENSOR_TIMESTAMP) ?: -1L, r.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: -1L,
        r.get(CaptureResult.SENSOR_FRAME_DURATION) ?: -1L, r.get(CaptureResult.SENSOR_SENSITIVITY) ?: -1,
        r.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW) ?: -1L, r.get(CaptureResult.TONEMAP_MODE) ?: -1,
        r.get(CaptureResult.NOISE_REDUCTION_MODE) ?: -1, r.get(CaptureResult.EDGE_MODE) ?: -1,
        r.get(CaptureResult.CONTROL_AE_MODE) ?: -1)

    /** Per read: ROW, then SUM with the interval, gap (= interval - previous read's exposure),
     *  skew, how many reads got the exposure asked for, and for YUV the 1/120 : 1/240 luma ratio. */
    private fun report(label: String, case: String, req: List<Long?>, reqFd: List<Long?>, rows: List<Row>,
                       luma: Map<Long, Double>, out: (String) -> Unit) {
        val gaps = ArrayList<Double>(); val dts = ArrayList<Double>()
        rows.forEachIndexed { i, r ->
            val prev = rows.getOrNull(i - 1)
            val dt = if (prev != null) (r.ts - prev.ts) / 1e6 else Double.NaN
            val gap = if (prev != null) dt - prev.expNs / 1e6 else Double.NaN
            if (!dt.isNaN()) { dts += dt; gaps += gap }
            out("ROW,$label,$case,$i,${r.ts},${fmt(dt)},${fmt(gap)},${req.getOrNull(i)?.let { it / 1000 } ?: "auto"},${r.expNs / 1000}," +
                "${reqFd.getOrNull(i)?.let { fmt(it / 1e6) } ?: "unset"},${fmt(r.frameDurNs / 1e6)},${r.iso},${fmt(r.skewNs / 1e6)}," +
                "ae=${r.ae},tonemap=${r.tonemap},nr=${r.nr},edge=${r.edge},luma=${luma[r.ts]?.let { fmt(it) } ?: ""}")
        }
        val applied = rows.indices.count { i -> req.getOrNull(i)?.let { kotlin.math.abs(rows[i].expNs - it) <= it / 50 } ?: false }
        val l240 = rows.filter { kotlin.math.abs(it.expNs - E240) <= E240 / 20 }.mapNotNull { luma[it.ts] }
        val l120 = rows.filter { kotlin.math.abs(it.expNs - E120) <= E120 / 20 }.mapNotNull { luma[it.ts] }
        val ratio = if (l240.isNotEmpty() && l120.isNotEmpty()) l120.average() / l240.average() else Double.NaN
        fun med(v: List<Double>) = if (v.isEmpty()) Double.NaN else v.sorted()[v.size / 2]
        out("SUM,$label,$case,reads=${rows.size},interval_med_ms=${fmt(med(dts))},interval_min_ms=${fmt(dts.minOrNull() ?: Double.NaN)}" +
            ",interval_max_ms=${fmt(dts.maxOrNull() ?: Double.NaN)},gap_med_ms=${fmt(med(gaps))},gap_max_ms=${fmt(gaps.maxOrNull() ?: Double.NaN)}" +
            ",skew_ms=${fmt(med(rows.map { it.skewNs / 1e6 }))},exposure_as_asked=$applied/${req.count { it != null }}" +
            ",luma_120_over_240=${fmt(ratio)},ae=${rows.firstOrNull()?.ae},tonemap=${rows.firstOrNull()?.tonemap}" +
            ",nr=${rows.firstOrNull()?.nr},edge=${rows.firstOrNull()?.edge}")
    }

    private fun fmt(v: Double) = if (v.isNaN()) "" else "%.3f".format(v)

    private fun probeRegular(manager: CameraManager, id: String, ch: CameraCharacteristics, fmt: Int, size: Size,
                             camH: Handler, imgH: Handler, out: (String) -> Unit) {
        val label = "cam$id ${FORMATS[fmt]} ${size.width}x${size.height}"
        val yuv = fmt == ImageFormat.YUV_420_888
        val luma = ConcurrentHashMap<Long, Double>()
        val reader = ImageReader.newInstance(size.width, size.height, fmt, N + 2)
        reader.setOnImageAvailableListener({ r ->
            runCatching { r.acquireNextImage()?.use { img -> if (yuv) luma[img.timestamp] = centreLuma(img) } }
        }, imgH)
        val tonemaps = ch.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)?.toList().orEmpty()
        val linCurve = floatArrayOf(0f, 0f, 1f, 1f)
        val dev = open(manager, id, camH)
        try {
            val sess = session(dev, listOf(reader.surface), camH, SessionConfiguration.SESSION_REGULAR)
            try {
                fun burst(case: String, shots: List<Shot>, linear: Boolean = false, report: Boolean = true) {
                    val rows = arrayOfNulls<Row>(shots.size)
                    val latch = CountDownLatch(shots.size)
                    val requests = shots.mapIndexed { i, s ->
                        dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                            set(CaptureRequest.SENSOR_EXPOSURE_TIME, s.expNs)
                            set(CaptureRequest.SENSOR_SENSITIVITY, ISO)
                            s.frameDurNs?.let { set(CaptureRequest.SENSOR_FRAME_DURATION, it) }
                            if (linear) {
                                set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
                                set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
                                if (CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE in tonemaps) {
                                    set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
                                    set(CaptureRequest.TONEMAP_CURVE, TonemapCurve(linCurve, linCurve, linCurve))
                                } else if (CameraMetadata.TONEMAP_MODE_GAMMA_VALUE in tonemaps) {
                                    set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_GAMMA_VALUE)
                                    set(CaptureRequest.TONEMAP_GAMMA, 1f)
                                }
                            }
                            setTag(i)
                            addTarget(reader.surface)
                        }.build()
                    }
                    sess.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                            rows[request.tag as Int] = rowOf(result); latch.countDown()
                        }
                        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                            latch.countDown()
                        }
                    }, camH)
                    val done = latch.await(6, TimeUnit.SECONDS)
                    Thread.sleep(150)                       // let the last images reach the reader
                    if (!report) return
                    if (!done) out("TIMEOUT,$label,$case")
                    report(label, case, shots.map { it.expNs }, shots.map { it.frameDurNs }, rows.filterNotNull(), luma, out)
                }
                burst("warmup", List(N) { Shot(E240, null) }, report = false)
                for (e in listOf(E240, E120)) {
                    val den = if (e == E240) 240 else 120
                    burst("e$den fd=exp", List(N) { Shot(e, e) })
                    burst("e$den fd=unset", List(N) { Shot(e, null) })
                }
                val alt = List(N) { if (it % 2 == 0) E240 else E120 }
                burst("alt fd=unset", alt.map { Shot(it, null) })
                burst("alt fd=exp", alt.map { Shot(it, it) })
                if (yuv) {
                    burst("alt fd=unset linear", alt.map { Shot(it, null) }, linear = true)
                    burst("alt fd=exp linear", alt.map { Shot(it, it) }, linear = true)
                }
            } finally { runCatching { sess.close() } }
        } finally {
            runCatching { dev.close() }
            runCatching { reader.close() }
        }
    }

    private fun probeHighSpeed(manager: CameraManager, id: String, size: Size, fps: Int, camH: Handler, imgH: Handler,
                               out: (String) -> Unit) {
        val label = "cam$id HIGH_SPEED ${size.width}x${size.height} ${fps}fps"
        val dev = open(manager, id, camH)
        try {
            // May a YUV ImageReader feed a high-speed session at all?
            val yuvReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 4)
            val yuvOk = runCatching { session(dev, listOf(yuvReader.surface), camH, SessionConfiguration.SESSION_HIGH_SPEED).close(); true }
            out("HS_YUV_READER,$label,configured=${yuvOk.getOrDefault(false)}" +
                (yuvOk.exceptionOrNull()?.let { ",${it.javaClass.simpleName}: ${it.message}" } ?: ""))
            yuvReader.close()
            Thread.sleep(300)
            // A PRIVATE reader with GPU usage counts as a preview surface.
            val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.PRIVATE, 16, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
            reader.setOnImageAvailableListener({ r -> runCatching { r.acquireNextImage()?.close() } }, imgH)
            try {
                val sess = session(dev, listOf(reader.surface), camH, SessionConfiguration.SESSION_HIGH_SPEED)
                        as CameraConstrainedHighSpeedCaptureSession
                try {
                    val period = 1_000_000_000L / fps
                    fun request(expNs: Long?) = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
                        if (expNs != null) {
                            set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                            set(CaptureRequest.SENSOR_EXPOSURE_TIME, expNs)
                            set(CaptureRequest.SENSOR_SENSITIVITY, ISO)
                            set(CaptureRequest.SENSOR_FRAME_DURATION, period)
                        }
                        addTarget(reader.surface)
                    }.build()
                    fun repeat(case: String, list: List<CaptureRequest>, asked: Long?) {
                        val rows = java.util.Collections.synchronizedList(ArrayList<Row>())
                        sess.setRepeatingBurst(list, object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                                rows += rowOf(result)
                            }
                        }, camH)
                        Thread.sleep(700)
                        sess.stopRepeating()
                        Thread.sleep(200)
                        val got = synchronized(rows) { rows.sortedBy { it.ts }.takeLast(24) }
                        report(label, case, got.map { asked }, got.map { if (asked != null) period else null }, got, emptyMap(), out)
                    }
                    repeat("auto", sess.createHighSpeedRequestList(request(null)), null)
                    repeat("manual e=1/$fps", sess.createHighSpeedRequestList(request(period)), period)
                    repeat("manual e=1/${2 * fps}", sess.createHighSpeedRequestList(request(period / 2)), period / 2)
                    // Frame-to-frame exposure change: a batch mixing two exposures.
                    val a = sess.createHighSpeedRequestList(request(period))
                    val b = sess.createHighSpeedRequestList(request(period / 2))
                    val mixed = a.indices.map { if (it % 2 == 0) a[it] else b[it] }
                    runCatching { repeat("manual alternating", mixed, null) }
                        .onFailure { out("HS_ALTERNATING,$label,rejected,${it.javaClass.simpleName}: ${it.message}") }
                } finally { runCatching { sess.close() } }
            } finally { runCatching { reader.close() } }
        } finally { runCatching { dev.close() } }
    }
}
