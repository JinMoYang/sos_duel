package com.example.activeperception

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Stand-alone camera timing probe, started with `--ez interval_diag true`.
 *
 * It answers one question the capability tables cannot: how far apart does this phone
 * actually place consecutive full-resolution RAW frames, and does asking for a shorter
 * SENSOR_FRAME_DURATION change that. The main lens declares a 33 ms minimum for RAW yet
 * delivers 16.67 ms when the key is left unset, so the declaration is not evidence either
 * way — only the per-frame SENSOR_TIMESTAMP spacing is.
 *
 * It also issues bursts whose exposure / ISO differ frame to frame, to check that the
 * declared per-frame control really applies each request to its own frame.
 *
 * Deliberately independent of [RawSensorCapturer]: it opens camera 0 itself, discards the
 * pixels, and reads only capture-result metadata. It must not overlap a measurement run.
 * Output goes to logcat under [TAG] and to interval_diag.csv in the app's external files.
 */
object IntervalDiag {
    const val TAG = "SoSIntervalDiag"
    private val busy = AtomicBoolean(false)

    private class Shot(val expNs: Long, val iso: Int, val frameDurNs: Long? = null)

    private class Row(
        val frameNumber: Long, val ts: Long, val expNs: Long, val iso: Int,
        val frameDurNs: Long, val physId: String, val focal: Float
    )

    @SuppressLint("MissingPermission")
    fun run(context: Context, onStatus: (String) -> Unit) {
        if (!busy.compareAndSet(false, true)) return
        val text = StringBuilder()
        fun out(line: String) { Log.i(TAG, line); text.append(line).append('\n') }

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val camThread = HandlerThread("interval-diag-cam").apply { start() }
        val imgThread = HandlerThread("interval-diag-img").apply { start() }
        val camHandler = Handler(camThread.looper)
        val imgHandler = Handler(imgThread.looper)
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var reader: ImageReader? = null
        try {
            val chars = manager.getCameraCharacteristics("0")
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: error("no stream configuration map")
            val size = map.getOutputSizes(ImageFormat.RAW_SENSOR)
                ?.maxByOrNull { it.width * it.height } ?: error("no RAW_SENSOR size")
            out("INFO,raw_size=${size.width}x${size.height}" +
                ",declared_min_frame_ns=${map.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, size)}" +
                ",exposure_range_ns=${chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)}" +
                ",sync_max_latency=${chars.get(CameraCharacteristics.SYNC_MAX_LATENCY)}")

            val rd = ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 6)
            reader = rd
            // Pixels are not needed; every image is released at once so the queue never fills.
            rd.setOnImageAvailableListener({ r ->
                runCatching { r.acquireNextImage()?.close() }
            }, imgHandler)

            val opened = CountDownLatch(1)
            manager.openCamera("0", object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) { device = camera; opened.countDown() }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); opened.countDown() }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); opened.countDown() }
            }, camHandler)
            check(opened.await(5, TimeUnit.SECONDS) && device != null) { "camera 0 open failed" }
            val dev = device!!

            val configured = CountDownLatch(1)
            @Suppress("DEPRECATION")
            dev.createCaptureSession(listOf(rd.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(value: CameraCaptureSession) { session = value; configured.countDown() }
                override fun onConfigureFailed(value: CameraCaptureSession) { configured.countDown() }
            }, camHandler)
            check(configured.await(5, TimeUnit.SECONDS) && session != null) { "RAW session failed" }
            val sess = session!!

            fun runCase(name: String, template: Int, shots: List<Shot>,
                        fpsRange: Range<Int>? = null, report: Boolean = true) {
                val rows = arrayOfNulls<Row>(shots.size)
                val failed = IntArray(1)
                val latch = CountDownLatch(shots.size)
                val requests = shots.mapIndexed { i, s ->
                    dev.createCaptureRequest(template).apply {
                        set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                        set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
                        set(CaptureRequest.SENSOR_EXPOSURE_TIME, s.expNs)
                        set(CaptureRequest.SENSOR_SENSITIVITY, s.iso)
                        s.frameDurNs?.let { set(CaptureRequest.SENSOR_FRAME_DURATION, it) }
                        fpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                        setTag(i)
                        addTarget(rd.surface)
                    }.build()
                }
                sess.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession, request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        val i = request.tag as Int
                        rows[i] = Row(
                            result.frameNumber,
                            result.get(CaptureResult.SENSOR_TIMESTAMP) ?: -1L,
                            result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: -1L,
                            result.get(CaptureResult.SENSOR_SENSITIVITY) ?: -1,
                            result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: -1L,
                            result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) ?: "",
                            result.get(CaptureResult.LENS_FOCAL_LENGTH) ?: -1f)
                        latch.countDown()
                    }
                    override fun onCaptureFailed(
                        session: CameraCaptureSession, request: CaptureRequest,
                        failure: CaptureFailure
                    ) {
                        synchronized(failed) { failed[0]++ }
                        latch.countDown()
                    }
                }, camHandler)
                val done = latch.await(maxOf(6_000L, shots.size * 600L), TimeUnit.MILLISECONDS)
                if (!report) return
                val tname = if (template == CameraDevice.TEMPLATE_PREVIEW) "preview" else "still"
                val got = rows.count { it != null }
                out("CASE,$name,template=$tname,requested=${shots.size},got=$got" +
                    ",failed=${failed[0]},timed_out=${!done}")
                val gaps = ArrayList<Double>()
                var prev = -1L
                rows.forEachIndexed { i, r ->
                    if (r == null) return@forEachIndexed
                    val dt = if (prev > 0L) (r.ts - prev) / 1e6 else Double.NaN
                    if (!dt.isNaN()) gaps.add(dt)
                    prev = r.ts
                    val s = shots[i]
                    out("ROW,$name,$i,${r.frameNumber},${r.ts}," +
                        (if (dt.isNaN()) "" else "%.3f".format(dt)) +
                        ",${s.expNs / 1000},${r.expNs / 1000},${s.iso},${r.iso}," +
                        (s.frameDurNs?.let { "%.3f".format(it / 1e6) } ?: "unset") +
                        ",${"%.3f".format(r.frameDurNs / 1e6)},${r.physId},${r.focal}")
                }
                if (gaps.isNotEmpty()) {
                    val sorted = gaps.sorted()
                    val first = rows.first { it != null }!!
                    val last = rows.last { it != null }!!
                    out("SUM,$name,dt_min_ms=${"%.3f".format(sorted.first())}" +
                        ",dt_median_ms=${"%.3f".format(sorted[sorted.size / 2])}" +
                        ",dt_max_ms=${"%.3f".format(sorted.last())}" +
                        ",span_ms=${"%.3f".format((last.ts - first.ts + last.expNs) / 1e6)}")
                }
            }

            val still = CameraDevice.TEMPLATE_STILL_CAPTURE
            val preview = CameraDevice.TEMPLATE_PREVIEW
            val e480 = 2_083_333L; val e240 = 4_166_667L; val e120 = 8_333_333L
            val e60 = 16_666_667L; val e30 = 33_333_333L
            fun same(expNs: Long, n: Int, fd: Long? = null) = List(n) { Shot(expNs, 400, fd) }

            onStatus("interval diag: warming up")
            runCase("warmup", still, same(e480, 8), report = false)

            onStatus("interval diag: same-setting bursts")
            // What the app does today: no frame duration requested.
            runCase("e480_unset_still", still, same(e480, 12))
            runCase("e480_unset_preview", preview, same(e480, 12))
            // Ask for progressively shorter intervals at a 1/480 s exposure.
            runCase("e480_fd16.67", still, same(e480, 12, 16_666_667L))
            runCase("e480_fd8.33", still, same(e480, 12, 8_333_333L))
            runCase("e480_fd4.17", still, same(e480, 12, 4_166_667L))
            runCase("e480_fd2.50", still, same(e480, 12, 2_500_000L))
            runCase("e480_fd8.33_fps60", still, same(e480, 12, 8_333_333L), Range(60, 60))
            // A 1/240 s exposure asked to repeat every 1/240 s.
            runCase("e240_fd4.17", still, same(e240, 12, 4_166_667L))
            // The shutters the methods actually sum: Prop-C's and Proposed's base frames.
            runCase("e240_unset", still, same(e240, 12))
            runCase("e120_unset", still, same(e120, 12))
            runCase("e60_unset", still, same(e60, 12))
            runCase("e16000_unset", still, same(16_000_000L, 12))
            runCase("e30_unset", still, same(e30, 8))

            onStatus("interval diag: per-frame brackets")
            val shutters = listOf(e480, e240, e120, e60, e30)
            runCase("bracket_shutter", still,
                List(15) { Shot(shutters[it % 5], 400) })
            val isos = listOf(200, 400, 800)
            runCase("bracket_iso", still, List(12) { Shot(e120, isos[it % 3]) })
            // The full 5 shutters x 3 ISOs frame, twice over.
            runCase("bracket_5x3", still,
                List(30) { Shot(shutters[(it % 15) / 3], isos[it % 3]) })

            out("DONE")
            onStatus("interval diag done")
        } catch (t: Throwable) {
            out("FAILED,${t.javaClass.simpleName},${t.message}")
            onStatus("interval diag failed: ${t.message}")
        } finally {
            runCatching { session?.close() }
            runCatching { device?.close() }
            runCatching { reader?.close() }
            camThread.quitSafely(); imgThread.quitSafely()
            runCatching {
                File(context.getExternalFilesDir(null), "interval_diag.csv").writeText(text.toString())
            }
            busy.set(false)
        }
    }
}
