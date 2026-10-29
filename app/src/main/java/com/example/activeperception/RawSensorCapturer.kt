package com.example.activeperception

import android.content.Context
import android.graphics.ImageFormat
import android.os.Build
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.util.Size
import com.example.activeperception.acquire.RawCapturer
import com.example.activeperception.acquire.RawFrame
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder
import kotlin.math.max

/**
 * RayNeo X3 Pro camera-0 RAW capturer.
 *
 * Camera callbacks only hand Images to a bounded worker pool. Workers pair each Image with
 * its CaptureResult by SENSOR_TIMESTAMP, copy/decode Bayer data, and close the Image. A RAW
 * becomes visible to the SoS pipeline only when the result's exposure/ISO actually matches
 * the requested state. This is intentionally independent of SYNC_MAX_LATENCY because the
 * device declares 0 while physical measurements show 9-10 frames of delay.
 */
class RawSensorCapturer(private val context: Context) : RawCapturer {

    companion object {
        private const val TAG = "RayNeoRawCapturer"
        private const val CAMERA_ID = "0"
        private const val BASE_FRAME_DURATION_NS = 33_329_000L
        private const val SETTING_GUARD_FRAMES = 12
        private const val MAX_IMAGES = 32
        private const val CONTINUOUS_RING_CAPACITY = 6
        /** Newest reads the parallel stream keeps (12.5 MB each). */
        private const val PARALLEL_RING = 10
        // Only used if the HAL omits per-frame AWB metadata. Measured from RayNeo camera 0;
        // normal operation uses CaptureResult.COLOR_CORRECTION_GAINS instead.
        private val RAYNEO_WB_FALLBACK = floatArrayOf(1.8896484f, 1f, 1.7802734f)
        private val IDENTITY_CCM = doubleArrayOf(
            1.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, 1.0
        )
    }

    data class RawMeta(
        val requestedIso: Int,
        val appliedIso: Int,
        val requestedExpUs: Long,
        val appliedExpUs: Long,
        val frameNumber: Long,
        val timestamp: Long,
        val black: Int,
        val blackLevels: IntArray,
        val white: Int,
        val frameDurationNs: Long,
        val applyDelayFrames: Long?,
        val rowStrideBytes: Int,
        val pixelStrideBytes: Int,
        val whiteBalance: FloatArray,
        val cameraToSrgb: DoubleArray,
        /** CaptureResult.SENSOR_NOISE_PROFILE flattened as S0, O0, S1, O1, … one pair per
         *  CFA channel (noise variance = S·x + O, x = signal normalized to [0, 1]); null when
         *  the HAL does not report it. */
        val noiseProfile: DoubleArray? = null
    )

    data class CaptureProfile(
        val strategy: String,
        val requestedFrames: Int,
        val firstResultMs: Double,
        val firstImageMs: Double,
        val firstMatchedMs: Double,
        val decodeWallMs: Double,
        val decodeCpuSumMs: Double,
        val cleanupMs: Double,
        val totalMs: Double
    )

    data class ContinuousProfile(
        val requestedFrames: Int,
        val firstResultMs: Double,
        val firstMatchedMs: Double,
        val readyMs: Double,
        val decodeCpuSumMs: Double,
        val staleFramesDropped: Int,
        val decodedFrames: Int
    )

    data class RawStreamConfig(
        val format: Int,
        val formatName: String,
        val width: Int,
        val height: Int,
        val minFrameDurationNs: Long,
        val stallDurationNs: Long,
        val estimatedBufferBytes: Long
    )

    class FastSequenceHandle internal constructor(
        val commandId: Long,
        val requestedExpUs: Int,
        val requestedIso: Int,
        val count: Int,
        val startedNs: Long,
        val startedSensorNs: Long
    )

    data class CaptureSnapshot(
        val metas: List<RawMeta>,
        val profile: CaptureProfile?
    )

    private data class Decoded(val frame: RawFrame, val meta: RawMeta)

    /** One finite exact-N request routed through the persistent low-latency listener. */
    private class FastTarget(
        /** Null for phone-AE captures: the HAL picks the setting and every frame is kept. */
        val expected: SensorState?,
        val commandId: Long,
        val wanted: Int,
        val startedNs: Long
    ) {
        val lock = java.lang.Object()
        val decoded = ArrayList<Decoded>()
        val ready = LinkedBlockingQueue<Decoded>()
        var firstResultNs = 0L
        var firstImageNs = 0L
        var firstMatchedNs = 0L
        var firstDecodeStartNs = 0L
        var lastDecodeEndNs = 0L
        var decodeCpuNs = 0L
        var failures = 0
        /** Frames whose metadata missed the request (settling) — skipped, not fatal. */
        var settlingSkips = 0
        /** Extra requests submitted to replace settling frames, bounded by the guard. */
        var resubmits = 0
        var error: Throwable? = null
    }

    private class ContinuousTarget(
        val expected: SensorState,
        val commandId: Long,
        val wanted: Int,
        val startedNs: Long
    ) {
        val lock = java.lang.Object()
        val ring = ArrayList<Decoded>(CONTINUOUS_RING_CAPACITY)
        var firstResultNs = 0L
        var firstMatchedNs = 0L
        var readyNs = 0L
        var decodeCpuNs = 0L
        var staleFrames = 0
        var decodedFrames = 0
        var error: Throwable? = null
    }

    @Volatile var lastMeta: List<RawMeta> = emptyList(); private set
    @Volatile var lastSettlingMeta: List<RawMeta> = emptyList(); private set
    @Volatile var lastApplyDelayFrames: Long? = null; private set
    @Volatile var lastCaptureProfile: CaptureProfile? = null; private set
    @Volatile var lastContinuousProfile: ContinuousProfile? = null; private set
    @Volatile var lastNativeDecodeMismatchCount: Int = -1; private set
    val pendingCommandCount: Int get() = scheduler.pendingSnapshot().size

    var cfaPattern: String = "RGGB"; private set
    var maxDn: Double = 1023.0; private set
    var syncMaxLatency: Int = CameraMetadata.SYNC_MAX_LATENCY_UNKNOWN; private set
    var captureWidth: Int = 0; private set
    var captureHeight: Int = 0; private set
    private val sensorBitmapWidth: Int get() = ((captureWidth / 2) and 1.inv()) / 2
    private val sensorBitmapHeight: Int get() = ((captureHeight / 2) and 1.inv()) / 2
    val bitmapWidth: Int get() = if (sensorOrientation.mod(180) == 0) sensorBitmapWidth else sensorBitmapHeight
    val bitmapHeight: Int get() = if (sensorOrientation.mod(180) == 0) sensorBitmapHeight else sensorBitmapWidth
    var timestampSource: Int = 0; private set
    /** Extra clockwise rotation for non-upright mounts (0/90/180/270), added to the
     *  sensor's own orientation so the whole pipeline — detector input, preview and JPEG
     *  logging — sees an upright image. Set before a run starts: formation buffers are
     *  sized from [bitmapWidth]/[bitmapHeight] when a pipeline warms up. */
    @Volatile var mountOffsetDeg: Int = 0
    private var sensorOrientationBase: Int = 0
    val sensorOrientation: Int get() = (sensorOrientationBase + mountOffsetDeg).mod(360)
    /** SENSOR_INFO_SENSITIVITY_RANGE, so AE-style modes can clamp requests to what the
     *  HAL can actually apply (a request past the range never metadata-approves). */
    var sensorIsoMin: Int = 100; private set
    var sensorIsoMax: Int = 1600; private set
    /** SENSOR_MAX_ANALOG_SENSITIVITY — above this the sensor applies digital gain. 0 when
     *  the HAL does not report it. */
    var maxAnalogIso: Int = 0; private set
    var streamFormat: Int = ImageFormat.RAW_SENSOR; private set
    val streamFormatName: String get() = formatName(streamFormat)
    /** Manual focus in diopters, applied to EVERY request this capturer builds (AF off), so
     *  no method carries the autofocus's own hunting into a comparison. NaN or negative
     *  leaves focus to the template's AF. Clamped to the lens minimum focus distance; a
     *  fixed-focus lens (minimum 0) is left alone. */
    @Volatile var fixedFocusDiopters: Float = 2.0f
    var minFocusDiopters: Float = 0f; private set
    /** SENSOR_INFO_EXPOSURE_TIME_RANGE in microseconds (0 when not reported). */
    var exposureMinUs: Long = 0; private set
    var exposureMaxUs: Long = 0; private set

    /** Diagnostic tap for read-noise measurement: while set, every decoded frame also hands
     *  over an UNBINNED crop of raw pixels from the sensor centre, black removed, signed,
     *  together with its metadata. Called on decode threads. */
    class RawTap(val cropW: Int, val cropH: Int, val sink: (IntArray, RawMeta) -> Unit)
    @Volatile var rawTap: RawTap? = null

    /** 0 (default): every decoded site is the mean of the 4 same-colour pixels of its 4x4
     *  block, signed. 1: the pre-2026-10-15 decode (one pixel of the 4, clipped at black),
     *  kept only so a static collection can alternate old and new pipelines frame by frame.
     *  Read when each frame is decoded. */
    @Volatile var decodeMode: Int = 0

    fun applyFixedFocus(builder: CaptureRequest.Builder) {
        val d = fixedFocusDiopters
        if (d.isNaN() || d < 0f || minFocusDiopters <= 0f) return
        builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
        builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, minOf(d, minFocusDiopters))
    }
    /** Min frame duration of the ACTIVE RAW stream, from the stream configuration map;
     *  falls back to the RayNeo-measured 33.329ms when the HAL reports none. */
    @Volatile var streamMinFrameDurationNs: Long = BASE_FRAME_DURATION_NS; private set
    /** RayNeo pins SENSOR_FRAME_DURATION (validated 33.3ms cadence; frame-number to
     *  wall-time conversion depends on it). The S25 HAL advertises a conservative 33.3ms
     *  min for full-res RAW but delivers 16.7ms bursts when the key is left unset
     *  (measured in sos_control) — pinning to the advertised value would halve the
     *  stream rate, so non-RayNeo devices leave the key out. */
    private val pinFrameDuration = Build.MANUFACTURER.equals("RayNeo", ignoreCase = true)

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private lateinit var chars: CameraCharacteristics
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var width = 0
    private var height = 0
    private var staticBlack = intArrayOf(0, 0, 0, 0)
    @Volatile private var sessionClosedLatch: CountDownLatch? = null

    // Camera metadata and ImageReader delivery must not wait behind formation workers.
    // Android's DISPLAY priority is a scheduling hint only; it does not busy-spin or reserve
    // a core, but lets these very short callbacks pre-empt ordinary CPU work promptly.
    private val cameraThread = HandlerThread(
        "RayNeo-CameraResult", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val imageThread = HandlerThread(
        "RayNeo-RawImage", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val imageHandler = Handler(imageThread.looper)
    @Volatile private var decodePool = Executors.newFixedThreadPool(4)
    @Volatile var decodeThreadCount: Int = 4; private set
    // isoTolerance: the S25 HAL quantizes applied sensitivity (req 100 -> applied 99,
    // 400 -> 398), so exact ISO matching never approves a frame there. 8 covers the
    // observed <=0.5% rounding up to ISO 1600 while staying far below the 2x grid spacing.
    private val scheduler = SensorStateScheduler(isoTolerance = 8)
    private val nativeValidationRemaining = AtomicInteger(0)
    private val continuousResults = ConcurrentHashMap<Long, TotalCaptureResult>()
    private val continuousResultLock = java.lang.Object()
    @Volatile private var activeContinuousTarget: ContinuousTarget? = null
    @Volatile private var continuousStarted = false
    private val fastResults = ConcurrentHashMap<Long, TotalCaptureResult>()
    private val fastResultLock = java.lang.Object()
    private val fastTargets = ConcurrentHashMap<Long, FastTarget>()
    private val captureSnapshot = ThreadLocal<CaptureSnapshot>()
    @Volatile private var fastCaptureStarted = false
    @Volatile private var currentState: SensorState? = null
    @Volatile private var lastWhiteBalance = RAYNEO_WB_FALLBACK.copyOf()
    @Volatile private var lastCameraToSrgb = IDENTITY_CCM.copyOf()

    @Suppress("MissingPermission")
    fun open() {
        require(manager.cameraIdList.contains(CAMERA_ID)) { "RayNeo camera 0 is unavailable" }
        chars = manager.getCameraCharacteristics(CAMERA_ID)
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        require(caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)) {
            "RayNeo camera 0 does not expose RAW"
        }
        require(caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) {
            "RayNeo camera 0 does not expose MANUAL_SENSOR"
        }

        maxDn = (chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023).toDouble()
        syncMaxLatency = chars.get(CameraCharacteristics.SYNC_MAX_LATENCY)
            ?: CameraMetadata.SYNC_MAX_LATENCY_UNKNOWN
        timestampSource = chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ?: 0
        sensorOrientationBase = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let {
            sensorIsoMin = it.lower; sensorIsoMax = it.upper
        }
        maxAnalogIso = chars.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY) ?: 0
        minFocusDiopters = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let {
            exposureMinUs = it.lower / 1_000L; exposureMaxUs = it.upper / 1_000L
        }
        Log.i(TAG, "sensitivity range=[$sensorIsoMin,$sensorIsoMax] maxAnalog=$maxAnalogIso " +
            "minFocus=${minFocusDiopters}dpt fixedFocus=${fixedFocusDiopters}dpt")
        cfaPattern = when (chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)) {
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> "RGGB"
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> "GRBG"
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> "GBRG"
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> "BGGR"
            else -> error("Unsupported CFA pattern")
        }
        chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.copyTo(staticBlack, 0)

        val size = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.RAW_SENSOR)
            ?.maxByOrNull { it.width * it.height }
            ?: error("RayNeo camera 0 has no RAW_SENSOR size")
        width = size.width
        height = size.height
        captureWidth = width
        captureHeight = height
        reader = ImageReader.newInstance(width, height, ImageFormat.RAW_SENSOR, MAX_IMAGES)
        streamFormat = ImageFormat.RAW_SENSOR
        streamMinFrameDurationNs = (chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, size) ?: 0L)
            .takeIf { it > 0L } ?: BASE_FRAME_DURATION_NS

        val opened = CountDownLatch(1)
        manager.openCamera(CAMERA_ID, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) { device = camera; opened.countDown() }
            override fun onDisconnected(camera: CameraDevice) {
                camera.close(); device = null; opened.countDown()
            }
            override fun onError(camera: CameraDevice, error: Int) {
                camera.close(); device = null; opened.countDown()
            }
        }, cameraHandler)
        check(opened.await(4, TimeUnit.SECONDS) && device != null) { "camera 0 open failed" }

        val configured = CountDownLatch(1)
        @Suppress("DEPRECATION")
        device!!.createCaptureSession(listOf(reader!!.surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(value: CameraCaptureSession) {
                    session = value; configured.countDown()
                }
                override fun onConfigureFailed(value: CameraCaptureSession) {
                    configured.countDown()
                }
                override fun onClosed(value: CameraCaptureSession) {
                    sessionClosedLatch?.countDown()
                }
            }, cameraHandler)
        check(configured.await(4, TimeUnit.SECONDS) && session != null) {
            "camera 0 RAW session failed"
        }
        Log.i(TAG, "camera0=${width}x$height cfa=$cfaPattern white=$maxDn " +
            "syncDeclared=$syncMaxLatency timestamp=$timestampSource orientation=$sensorOrientation")
    }

    fun availableRawStreamConfigs(): List<RawStreamConfig> {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return emptyList()
        return intArrayOf(ImageFormat.RAW_SENSOR, ImageFormat.RAW10, ImageFormat.RAW12)
            .flatMap { format ->
                (map.getOutputSizes(format) ?: emptyArray<Size>()).map { size ->
                    val bits = when (format) {
                        ImageFormat.RAW10 -> 10L
                        ImageFormat.RAW12 -> 12L
                        else -> 16L
                    }
                    RawStreamConfig(format, formatName(format), size.width, size.height,
                        map.getOutputMinFrameDuration(format, size),
                        map.getOutputStallDuration(format, size),
                        size.width.toLong() * size.height * bits / 8L)
                }
            }.sortedWith(compareBy<RawStreamConfig> { it.format }
                .thenByDescending { it.width.toLong() * it.height })
    }

    /** EXP5.3 only: reconfigure the existing camera device to another advertised RAW format. */
    @Synchronized
    fun reconfigureRawStream(format: Int, requestedWidth: Int, requestedHeight: Int) {
        require(format == ImageFormat.RAW_SENSOR || format == ImageFormat.RAW10 ||
            format == ImageFormat.RAW12)
        val supported = availableRawStreamConfigs().any {
            it.format == format && it.width == requestedWidth && it.height == requestedHeight
        }
        require(supported) { "Unsupported ${formatName(format)} ${requestedWidth}x$requestedHeight" }
        stopContinuousCapture()
        stopFastCapture()
        val closed = CountDownLatch(1)
        sessionClosedLatch = closed
        runCatching { session?.close() }
        closed.await(2, TimeUnit.SECONDS)
        sessionClosedLatch = null
        reader?.setOnImageAvailableListener(null, null)
        reader?.let { drainReader(it) }
        reader?.close()
        session = null

        width = requestedWidth; height = requestedHeight
        captureWidth = width; captureHeight = height; streamFormat = format
        streamMinFrameDurationNs = (chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputMinFrameDuration(format, android.util.Size(width, height)) ?: 0L)
            .takeIf { it > 0L } ?: BASE_FRAME_DURATION_NS
        reader = ImageReader.newInstance(width, height, format, MAX_IMAGES)
        val configured = CountDownLatch(1)
        @Suppress("DEPRECATION")
        device!!.createCaptureSession(listOf(reader!!.surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(value: CameraCaptureSession) {
                    session = value; configured.countDown()
                }
                override fun onConfigureFailed(value: CameraCaptureSession) {
                    configured.countDown()
                }
                override fun onClosed(value: CameraCaptureSession) {
                    sessionClosedLatch?.countDown()
                }
            }, cameraHandler)
        check(configured.await(4, TimeUnit.SECONDS) && session != null) {
            "RAW session reconfigure failed for ${formatName(format)}"
        }
        currentState = null
        drainReader(reader!!)
        Log.i(TAG, "EXP5.3 stream=${formatName(format)} ${width}x$height")
    }

    private fun formatName(format: Int): String = when (format) {
        ImageFormat.RAW_SENSOR -> "RAW_SENSOR"
        ImageFormat.RAW10 -> "RAW10"
        ImageFormat.RAW12 -> "RAW12"
        else -> "FORMAT_$format"
    }

    override fun capture(exposureUs: Int, iso: Int, nBurst: Int): List<RawFrame> {
        return captureInternal(exposureUs, iso, nBurst, metadataFirst = false)
    }

    /** Experiment path: submit at most the measured 12-frame actuation window but stop as
     * soon as metadata exposes [nBurst] frames at the requested state. This keeps the safety
     * ceiling while removing the fixed wait from the accepted image path. */
    fun captureMetadataFirst(exposureUs: Int, iso: Int, nBurst: Int): List<RawFrame> {
        return captureInternal(exposureUs, iso, nBurst, metadataFirst = true)
    }

    /** EXP5.1 B: submit exactly the frames needed, with no fixed settling guard. A mismatch
     * is reported to the caller instead of silently accepting stale sensor state. */
    fun captureNoGuard(exposureUs: Int, iso: Int, nBurst: Int): List<RawFrame> {
        val expected = SensorState(exposureUs.toLong(), iso)
        val command = scheduler.enqueue(expected)
        val request = manualRequest(expected, command.id)
        return runCapture(request, max(1, nBurst), 0, expected, command.id)
    }

    /** EXP5.1.1 optimized B: exact-N request plus native NEON RAW decode. */
    fun captureNoGuardNative(exposureUs: Int, iso: Int, nBurst: Int): List<RawFrame> {
        val expected = SensorState(exposureUs.toLong(), iso)
        val command = scheduler.enqueue(expected)
        val request = manualRequest(expected, command.id)
        return runCapture(request, max(1, nBurst), 0, expected, command.id,
            nativeDecode = true)
    }

    /** EXP5.1.1 E: same exact-N/native path with the low-latency preview request template. */
    fun captureNoGuardNativePreview(exposureUs: Int, iso: Int, nBurst: Int): List<RawFrame> {
        val expected = SensorState(exposureUs.toLong(), iso)
        val command = scheduler.enqueue(expected)
        val request = manualRequest(expected, command.id, CameraDevice.TEMPLATE_PREVIEW)
        return runCapture(request, max(1, nBurst), 0, expected, command.id,
            nativeDecode = true)
    }

    /** Best safe EXP5.1.1 combination: preview template for latency-sensitive single RAW,
     * still template for four-frame bursts where preview increased tail latency. */
    fun captureExp511Best(exposureUs: Int, iso: Int, nBurst: Int): List<RawFrame> =
        if (nBurst <= 1) captureNoGuardNativePreview(exposureUs, iso, 1)
        else captureNoGuardNative(exposureUs, iso, nBurst)

    /**
     * EXP5.6: install ImageReader routing once, keep Camera result and RAW image delivery on
     * separate high-priority HandlerThreads, and decode every image directly from its native
     * plane buffer as soon as its timestamp-matched result is available. Unlike EXP5.2 this
     * does not run a repeating stream: each call still submits exactly N finite requests.
     */
    @Synchronized
    fun startFastCapture() {
        if (fastCaptureStarted) return
        check(!continuousStarted) { "continuous capture is active" }
        val imageReader = reader ?: error("RAW reader is closed")
        fastResults.clear(); fastTargets.clear(); drainReader(imageReader)
        imageReader.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireNextImage() }.getOrNull()
                ?: return@setOnImageAvailableListener
            val imageArrivalNs = System.nanoTime()
            // Ownership of Image moves to exactly one decode worker. No 12 MP plane copy is
            // made: native code reads plane.buffer directly and Image closes after decode.
            decodePool.execute {
                try {
                    val result = awaitResult(image.timestamp, fastResults,
                        fastResultLock, 2_000) ?: return@execute
                    val commandId = result.request.tag as? Long ?: return@execute
                    val target = fastTargets[commandId] ?: return@execute
                    val actual = result.sensorState()
                    val expected = target.expected
                    if (expected != null && !scheduler.matches(expected, actual)) {
                        // Settling frame: a HAL may deliver a tagged request's frame with
                        // pre-settling metadata (seen on the S25 from a cold stream). Skip
                        // it — the result callback resubmits the slot — rather than failing
                        // the whole capture.
                        synchronized(target.lock) { target.settlingSkips++ }
                        return@execute
                    }
                    val decodeStart = System.nanoTime()
                    val decoded = decode(image, result, target.expected, commandId,
                        nativeDecode = true)
                    val decodeEnd = System.nanoTime()
                    synchronized(target.lock) {
                        if (target.firstImageNs == 0L) target.firstImageNs = imageArrivalNs
                        if (target.firstMatchedNs == 0L) target.firstMatchedNs = decodeStart
                        if (target.firstDecodeStartNs == 0L ||
                            decodeStart < target.firstDecodeStartNs) {
                            target.firstDecodeStartNs = decodeStart
                        }
                        if (decodeEnd > target.lastDecodeEndNs) target.lastDecodeEndNs = decodeEnd
                        target.decodeCpuNs += decodeEnd - decodeStart
                        target.decoded.add(decoded)
                        target.ready.offer(decoded)
                        if (target.decoded.size >= target.wanted) target.lock.notifyAll()
                    }
                } catch (t: Throwable) {
                    // Route an error only when its request can still be identified. The caller
                    // otherwise reaches the bounded timeout and reports the missing frame.
                    fastTargets.values.forEach { target ->
                        synchronized(target.lock) {
                            if (target.error == null) target.error = t
                            target.lock.notifyAll()
                        }
                    }
                } finally {
                    image.close()
                }
            }
        }, imageHandler)
        fastCaptureStarted = true
        Log.i(TAG, "EXP5.6 persistent finite listener armed")
    }

    /** Exact-N on-demand capture using [startFastCapture]'s persistent routing. */
    fun captureFast(exposureUs: Int, iso: Int, nBurst: Int): List<RawFrame> {
        check(fastCaptureStarted) { "startFastCapture() must be called first" }
        val wanted = max(1, nBurst)
        parallel?.let { p ->
            val key = ParallelKey.Manual(SensorState(exposureUs.toLong(), iso))
            return p.take(listOf(key), wanted).map { it.frame }
        }
        val expected = SensorState(exposureUs.toLong(), iso)
        val command = scheduler.enqueue(expected)
        val template = if (wanted == 1) CameraDevice.TEMPLATE_PREVIEW
            else CameraDevice.TEMPLATE_STILL_CAPTURE
        return runFastTarget(FastTarget(expected, command.id, wanted, System.nanoTime()),
            manualRequest(expected, command.id, template), "requested=${exposureUs}us ISO$iso")
    }

    /** Phone-AE capture on the same persistent routing as [captureFast]. The request is
     *  [captureAe]'s (HAL AE and AWB on, fixed focus); only the delivery differs ([captureAe]
     *  re-arms the reader listener for every frame), so the AE baseline and the manual
     *  methods share one capture path. Measured 2026-10-16 the delivery itself cost no whole
     *  frame: AE's longer period comes from the HAL running AE at a 33 ms frame duration even
     *  at 1/60 s exposures. The HAL picks the setting, so nothing is expected and every frame
     *  is kept. Ids are negative, so they never meet the scheduler's. */
    fun captureFastAe(nBurst: Int): List<RawFrame> {
        check(fastCaptureStarted) { "startFastCapture() must be called first" }
        val wanted = max(1, nBurst)
        parallel?.let { p -> return p.take(listOf(ParallelKey.Ae), wanted).map { it.frame } }
        val id = aeCommandIds.decrementAndGet()
        return runFastTarget(FastTarget(null, id, wanted, System.nanoTime()), aeRequest(id), "phone AE")
    }

    /** [captureFastAe]'s request: the HAL's AE and AWB on, fixed focus, tagged [id]. */
    private fun aeRequest(id: Long): CaptureRequest =
        device!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            applyFixedFocus(this)
            addTarget(reader!!.surface)
            setTag(id)
        }.build()

    private val aeCommandIds = AtomicLong(0L)

    /** The capture callback of a [FastTarget] on the persistent routing: records the result for
     *  the decode workers, tracks the applied state, and replaces a settling frame's slot
     *  (bounded by [SETTING_GUARD_FRAMES]). */
    private fun fastCallback(target: FastTarget): CameraCaptureSession.CaptureCallback =
        object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                val nowNs = System.nanoTime()
                synchronized(target.lock) {
                    if (target.firstResultNs == 0L) target.firstResultNs = nowNs
                }
                val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: -1L
                val expected = target.expected
                val actual = result.sensorState()
                if (expected != null) {
                    scheduler.markRequestFrame(target.commandId, result.frameNumber)
                    scheduler.observe(result.frameNumber, ts, actual)?.let {
                        lastApplyDelayFrames = it.applyDelayFrames; currentState = actual
                    }
                }
                fastResults[ts] = result
                synchronized(fastResultLock) { fastResultLock.notifyAll() }
                // Replace a settling frame's slot so exact-N still completes; bounded so a
                // persistently wrong HAL state cannot loop forever.
                if (expected != null && !scheduler.matches(expected, actual)) {
                    val resubmit = synchronized(target.lock) {
                        if (target.resubmits < SETTING_GUARD_FRAMES) { target.resubmits++; true }
                        else false
                    }
                    if (resubmit) runCatching { session.capture(request, this, cameraHandler) }
                }
            }

            override fun onCaptureFailed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                failure: CaptureFailure
            ) {
                synchronized(target.lock) {
                    target.failures++
                    target.error = IllegalStateException(
                        "Fast capture failed frame=${failure.frameNumber} reason=${failure.reason}")
                    target.lock.notifyAll()
                }
            }
        }

    /** Submits [request] [FastTarget.wanted] times on the persistent routing and waits for
     *  that many decoded frames. [what] names the capture in errors. */
    private fun runFastTarget(target: FastTarget, request: CaptureRequest, what: String): List<RawFrame> {
        val wanted = target.wanted
        check(fastTargets.putIfAbsent(target.commandId, target) == null)
        val callback = fastCallback(target)
        val sess = session ?: error("camera session is closed")
        sess.captureBurst(List(wanted) { request }, callback, cameraHandler)
        val timeoutNs = TimeUnit.MILLISECONDS.toNanos(max(6_000L, wanted * 400L))
        val deadline = System.nanoTime() + timeoutNs
        synchronized(target.lock) {
            while (target.decoded.size < wanted && target.error == null) {
                val left = deadline - System.nanoTime()
                if (left <= 0L) break
                target.lock.wait(minOf(100L,
                    TimeUnit.NANOSECONDS.toMillis(left).coerceAtLeast(1L)))
            }
        }
        fastTargets.remove(target.commandId)
        target.error?.let { throw it }
        val accepted = synchronized(target.lock) { target.decoded.sortedBy { it.meta.frameNumber } }
        check(accepted.size == wanted) {
            "Fast capture obtained ${accepted.size}/$wanted RAW frames; " +
                "$what failures=${target.failures}"
        }
        val endNs = System.nanoTime()
        fun sinceStart(value: Long): Double = if (value == 0L) Double.NaN
            else (value - target.startedNs) / 1e6
        lastCaptureProfile = CaptureProfile(
            strategy = "persistent_finite_direct_native",
            requestedFrames = wanted,
            firstResultMs = sinceStart(target.firstResultNs),
            firstImageMs = sinceStart(target.firstImageNs),
            firstMatchedMs = sinceStart(target.firstMatchedNs),
            decodeWallMs = if (target.firstDecodeStartNs == 0L || target.lastDecodeEndNs == 0L)
                Double.NaN else (target.lastDecodeEndNs - target.firstDecodeStartNs) / 1e6,
            decodeCpuSumMs = target.decodeCpuNs / 1e6,
            cleanupMs = 0.0,
            totalMs = (endNs - target.startedNs) / 1e6)
        lastSettlingMeta = accepted.map { it.meta }
        lastMeta = lastSettlingMeta
        accepted.lastOrNull()?.let {
            currentState = SensorState(it.meta.appliedExpUs, it.meta.appliedIso)
        }
        return accepted.map { it.frame }
    }

    /**
     * Submit a finite run of same-exposure RAW requests without waiting for all of them.
     * [takeFastSequenceFrame] releases each decoded frame to formation immediately, allowing
     * later sensor frames to arrive while the current frame is on CPU/GPU.
     */
    fun beginFastSequence(exposureUs: Int, iso: Int, count: Int): FastSequenceHandle {
        check(fastCaptureStarted) { "startFastCapture() must be called first" }
        require(count in 1..MAX_IMAGES)
        val expected = SensorState(exposureUs.toLong(), iso)
        val command = scheduler.enqueue(expected)
        val target = FastTarget(expected, command.id, count, System.nanoTime())
        val startedSensorNs = SystemClock.elapsedRealtimeNanos()
        check(fastTargets.putIfAbsent(command.id, target) == null)
        val request = manualRequest(expected, command.id, CameraDevice.TEMPLATE_PREVIEW)
        val callback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                val nowNs = System.nanoTime()
                synchronized(target.lock) {
                    if (target.firstResultNs == 0L) target.firstResultNs = nowNs
                }
                val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: -1L
                scheduler.markRequestFrame(command.id, result.frameNumber)
                val actual = result.sensorState()
                scheduler.observe(result.frameNumber, ts, actual)?.let {
                    lastApplyDelayFrames = it.applyDelayFrames; currentState = actual
                }
                fastResults[ts] = result
                synchronized(fastResultLock) { fastResultLock.notifyAll() }
                // Same settling-slot replacement as captureFast.
                if (!scheduler.matches(target.expected!!, actual)) {
                    val resubmit = synchronized(target.lock) {
                        if (target.resubmits < SETTING_GUARD_FRAMES) { target.resubmits++; true }
                        else false
                    }
                    if (resubmit) runCatching { session.capture(request, this, cameraHandler) }
                }
            }

            override fun onCaptureFailed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                failure: CaptureFailure
            ) {
                synchronized(target.lock) {
                    target.failures++
                    target.error = IllegalStateException(
                        "Fast sequence failed frame=${failure.frameNumber} reason=${failure.reason}")
                    target.lock.notifyAll()
                }
            }
        }
        (session ?: error("camera session is closed"))
            .captureBurst(List(count) { request }, callback, cameraHandler)
        return FastSequenceHandle(command.id, exposureUs, iso, count, target.startedNs,
            startedSensorNs)
    }

    fun takeFastSequenceFrame(
        handle: FastSequenceHandle,
        timeoutMs: Long = 6_000L
    ): RawFrame {
        val target = fastTargets[handle.commandId]
            ?: error("Fast sequence ${handle.commandId} is no longer active")
        val decoded = target.ready.poll(timeoutMs, TimeUnit.MILLISECONDS)
            ?: run {
                target.error?.let { throw it }
                error("Timed out waiting for fast sequence ${handle.commandId}")
            }
        val nowNs = System.nanoTime()
        synchronized(target.lock) {
            lastCaptureProfile = CaptureProfile(
                strategy = "persistent_finite_streaming",
                requestedFrames = handle.count,
                firstResultMs = if (target.firstResultNs == 0L) Double.NaN
                    else (target.firstResultNs - target.startedNs) / 1e6,
                firstImageMs = if (target.firstImageNs == 0L) Double.NaN
                    else (target.firstImageNs - target.startedNs) / 1e6,
                firstMatchedMs = if (target.firstMatchedNs == 0L) Double.NaN
                    else (target.firstMatchedNs - target.startedNs) / 1e6,
                decodeWallMs = if (target.firstDecodeStartNs == 0L || target.lastDecodeEndNs == 0L)
                    Double.NaN else (target.lastDecodeEndNs - target.firstDecodeStartNs) / 1e6,
                decodeCpuSumMs = target.decodeCpuNs / 1e6,
                cleanupMs = 0.0,
                totalMs = (nowNs - target.startedNs) / 1e6)
        }
        lastMeta = listOf(decoded.meta); lastSettlingMeta = lastMeta
        currentState = SensorState(decoded.meta.appliedExpUs, decoded.meta.appliedIso)
        if (target.ready.isEmpty() && synchronized(target.lock) {
                target.decoded.size >= target.wanted
            }) {
            fastTargets.remove(handle.commandId)
        }
        return decoded.frame
    }

    /** One burst whose shots each carry their own exposure and ISO; see
     *  [beginBracketSequence]. */
    class BracketHandle internal constructor(
        internal val commandIds: LongArray,
        val exposureUs: IntArray,
        val iso: IntArray,
        /** elapsedRealtimeNanos at submission — the clock SENSOR_TIMESTAMP uses. */
        val submittedSensorNs: Long
    ) {
        val count: Int get() = commandIds.size
        /** Lens state read from each shot's capture result. NaN / -1 until that result
         *  lands, which is always before the shot's frame can be taken. */
        val focusDistance = FloatArray(commandIds.size) { Float.NaN }
        val afState = IntArray(commandIds.size) { -1 }
        val lensState = IntArray(commandIds.size) { -1 }
    }

    /**
     * Submit one burst in which every shot has its OWN (exposureUs, iso), without waiting.
     *
     * The S26 Ultra applies each request to its own frame (measured: 209 of 209 shots,
     * exposure exact), so a mixed burst costs the same per frame as a uniform one. Each
     * shot becomes a single-frame target on the persistent listener, which already routes
     * by request tag, so nothing about the existing same-setting paths changes.
     *
     * Take the frames in order with [takeBracketFrame]; a decoded frame is 12.5 MB, so
     * the caller should release each one before asking for many more.
     *
     * [template] is shared by every shot; a caller mixing one-shot and multi-shot frames can
     * follow [captureFast]'s rule (preview for one shot, still for a burst).
     */
    fun beginBracketSequence(
        shots: List<Pair<Int, Int>>,
        template: Int = CameraDevice.TEMPLATE_STILL_CAPTURE
    ): BracketHandle {
        check(fastCaptureStarted) { "startFastCapture() must be called first" }
        require(shots.size in 1..MAX_IMAGES)
        val sess = session ?: error("camera session is closed")
        val submittedSensorNs = SystemClock.elapsedRealtimeNanos()
        val startedNs = System.nanoTime()
        val ids = LongArray(shots.size)
        val indexOf = HashMap<Long, Int>(shots.size * 2)
        val requests = ArrayList<CaptureRequest>(shots.size)
        shots.forEachIndexed { i, (expUs, iso) ->
            val expected = SensorState(expUs.toLong(), iso)
            val command = scheduler.enqueue(expected)
            check(fastTargets.putIfAbsent(command.id,
                FastTarget(expected, command.id, 1, startedNs)) == null)
            ids[i] = command.id
            indexOf[command.id] = i
            // One template for every shot, so nothing but exposure and ISO differs.
            requests += manualRequest(expected, command.id, template)
        }
        val handle = BracketHandle(ids, IntArray(shots.size) { shots[it].first },
            IntArray(shots.size) { shots[it].second }, submittedSensorNs)
        val callback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                val id = request.tag as? Long ?: return
                val target = fastTargets[id] ?: return
                val i = indexOf[id] ?: return
                val nowNs = System.nanoTime()
                synchronized(target.lock) {
                    if (target.firstResultNs == 0L) target.firstResultNs = nowNs
                }
                val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: -1L
                scheduler.markRequestFrame(id, result.frameNumber)
                val actual = result.sensorState()
                scheduler.observe(result.frameNumber, ts, actual)?.let {
                    lastApplyDelayFrames = it.applyDelayFrames; currentState = actual
                }
                handle.focusDistance[i] = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: Float.NaN
                handle.afState[i] = result.get(CaptureResult.CONTROL_AF_STATE) ?: -1
                handle.lensState[i] = result.get(CaptureResult.LENS_STATE) ?: -1
                fastResults[ts] = result
                synchronized(fastResultLock) { fastResultLock.notifyAll() }
                // Same settling-slot replacement as captureFast, per shot.
                if (!scheduler.matches(target.expected!!, actual)) {
                    val resubmit = synchronized(target.lock) {
                        if (target.resubmits < SETTING_GUARD_FRAMES) { target.resubmits++; true }
                        else false
                    }
                    if (resubmit) runCatching { session.capture(request, this, cameraHandler) }
                }
            }

            override fun onCaptureFailed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                failure: CaptureFailure
            ) {
                val id = request.tag as? Long ?: return
                val target = fastTargets[id] ?: return
                synchronized(target.lock) {
                    target.failures++
                    target.error = IllegalStateException(
                        "Bracket shot failed frame=${failure.frameNumber} reason=${failure.reason}")
                    target.lock.notifyAll()
                }
            }
        }
        sess.captureBurst(requests, callback, cameraHandler)
        return handle
    }

    /** Blocks until shot [index] of [handle] is decoded, then hands it over. Shots may be
     *  taken in any order, but each only once. */
    fun takeBracketFrame(
        handle: BracketHandle,
        index: Int,
        timeoutMs: Long = 6_000L
    ): Pair<RawFrame, RawMeta> {
        val id = handle.commandIds[index]
        val target = fastTargets[id] ?: error("Bracket shot $index is no longer active")
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        // Polls in short steps so a failed shot is reported at once instead of after the
        // whole timeout.
        fun awaitDecoded(): Decoded {
            while (true) {
                target.ready.poll(100, TimeUnit.MILLISECONDS)?.let { return it }
                val failure = target.error
                if (failure != null || System.nanoTime() >= deadline) {
                    fastTargets.remove(id)
                    throw failure ?: IllegalStateException(
                        "Timed out waiting for bracket shot $index " +
                            "(${handle.exposureUs[index]}us ISO${handle.iso[index]})")
                }
            }
        }
        val decoded = awaitDecoded()
        fastTargets.remove(id)
        lastMeta = listOf(decoded.meta); lastSettlingMeta = lastMeta
        currentState = SensorState(decoded.meta.appliedExpUs, decoded.meta.appliedIso)
        return decoded.frame to decoded.meta
    }

    /** Drops every shot of [handle] that has not been taken; late images are discarded. */
    fun abortBracketSequence(handle: BracketHandle) {
        for (id in handle.commandIds) fastTargets.remove(id)
    }

    // ---------- parallel mode (2026-10-29) ----------

    /** What a parallel-mode read was requested as: a manual setting, or the phone's AE. */
    sealed class ParallelKey {
        data class Manual(val state: SensorState) : ParallelKey()
        object Ae : ParallelKey()
    }

    /** One read of the parallel stream, with the key it was requested under. */
    class ParallelRead(val frame: RawFrame, val meta: RawMeta, val key: ParallelKey)

    /**
     * Capture and analysis on separate threads: a stream thread keeps [depth] single-shot
     * requests in flight, cycling through [pattern], and keeps the newest decoded reads; a
     * caller's [take] waits until [periodNs] after its previous take started (the output clock),
     * then for fresh reads (never handed out before) of the keys it asks for. The pattern is set
     * before that wait, so a new setting reaches the sensor while the caller waits: about
     * [depth] reads plus the request latency (~38 ms on the S26). Every request uses the
     * preview template, as captureFast's one-shot requests do.
     */
    private inner class ParallelStream(val depth: Int, val periodNs: Long) {
        @Volatile var pattern: List<ParallelKey> = emptyList()
        /** Keys to request once each, in order, before the stream returns to cycling [pattern]
         *  (a caller that plans its next windows ahead). */
        val queue = java.util.concurrent.ConcurrentLinkedQueue<ParallelKey>()
        private val ring = ArrayDeque<ParallelRead>()
        private val lock = java.lang.Object()
        @Volatile var active = true
        @Volatile var error: Throwable? = null
        private var lastReturnedTs = 0L
        /** Newest timestamp handed out per key (several-key takes: a key's read need only be
         *  newer than that key's last one). */
        private val lastReturnedByKey = HashMap<ParallelKey, Long>()
        private var tick = 0L
        val thread = Thread({ loop() }, "RawParallelStream")

        private fun submit(key: ParallelKey): FastTarget {
            val target: FastTarget
            val request: CaptureRequest
            when (key) {
                is ParallelKey.Manual -> {
                    val command = scheduler.enqueue(key.state)
                    target = FastTarget(key.state, command.id, 1, System.nanoTime())
                    request = manualRequest(key.state, command.id, CameraDevice.TEMPLATE_PREVIEW)
                }
                ParallelKey.Ae -> {
                    val id = aeCommandIds.decrementAndGet()
                    target = FastTarget(null, id, 1, System.nanoTime())
                    request = aeRequest(id)
                }
            }
            check(fastTargets.putIfAbsent(target.commandId, target) == null)
            (session ?: error("camera session is closed")).captureBurst(listOf(request), fastCallback(target), cameraHandler)
            return target
        }

        private fun await(target: FastTarget): Decoded {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            try {
                while (true) {
                    target.ready.poll(100, TimeUnit.MILLISECONDS)?.let { return it }
                    target.error?.let { throw it }
                    if (System.nanoTime() >= deadline) error("parallel read timed out")
                }
            } finally {
                fastTargets.remove(target.commandId)
            }
        }

        private fun loop() {
            val inflight = ArrayDeque<Pair<FastTarget, ParallelKey>>()
            var next = 0
            var failures = 0
            try {
                while (active) {
                    while (inflight.size < depth) {
                        val pat = pattern
                        val key = queue.poll() ?: if (pat.isEmpty()) null else pat[next++ % pat.size]
                        if (key == null) break
                        inflight.addLast(submit(key) to key)
                    }
                    if (inflight.isEmpty()) { Thread.sleep(5); continue }
                    val (t, key) = inflight.removeFirst()
                    val d = try {
                        await(t)
                    } catch (e: Throwable) {
                        Log.w(TAG, "parallel read failed", e)
                        if (++failures >= 5) throw e
                        continue
                    }
                    failures = 0
                    synchronized(lock) {
                        ring.addLast(ParallelRead(d.frame, d.meta, key))
                        while (ring.size > PARALLEL_RING) ring.removeFirst()
                        lock.notifyAll()
                    }
                }
            } catch (e: Throwable) {
                if (active) { error = e; Log.e(TAG, "parallel stream stopped", e) }
            } finally {
                inflight.forEach { fastTargets.remove(it.first.commandId) }
                synchronized(lock) { active = false; lock.notifyAll() }
            }
        }

        /** Paced take: [keys] becomes the pattern (unless [setPattern] is false: the caller set
         *  it); after the output clock, the newest [n] fresh reads of keys[0] when there is one
         *  key, else the newest fresh read of each key: keys[0] (the output's) newer than every
         *  read handed out before, every other key newer than its own last one. */
        fun take(keys: List<ParallelKey>, n: Int = 1, setPattern: Boolean = true): List<ParallelRead> {
            if (setPattern) pattern = keys
            val lead = tick - System.nanoTime()
            if (lead > 0) Thread.sleep(lead / 1_000_000, (lead % 1_000_000).toInt())
            val t0 = System.nanoTime()
            tick = t0 + periodNs
            val deadline = t0 + TimeUnit.SECONDS.toNanos(4)
            fun pick(): List<ParallelRead>? {
                val fresh = ring.filter { it.meta.timestamp > lastReturnedTs }
                if (keys.size == 1) return fresh.filter { it.key == keys[0] }.takeIf { it.size >= n }?.takeLast(n)
                return keys.mapIndexed { i, k ->
                    val after = if (i == 0) lastReturnedTs else lastReturnedByKey[k] ?: 0L
                    ring.lastOrNull { it.key == k && it.meta.timestamp > after } ?: return null
                }
            }
            var got: List<ParallelRead>? = null
            synchronized(lock) {
                while (got == null) {
                    got = pick()
                    if (got != null) break
                    error?.let { throw it }
                    check(active) { "parallel stream is not running" }
                    val left = deadline - System.nanoTime()
                    check(left > 0) { "parallel: no fresh read of $keys" }
                    lock.wait(minOf(20L, TimeUnit.NANOSECONDS.toMillis(left).coerceAtLeast(1L)))
                }
            }
            val reads = got!!
            lastReturnedTs = maxOf(lastReturnedTs, reads.maxOf { it.meta.timestamp })
            for (r in reads) lastReturnedByKey[r.key] = r.meta.timestamp
            val metas = reads.map { it.meta }
            lastMeta = metas; lastSettlingMeta = metas
            currentState = SensorState(metas.last().appliedExpUs, metas.last().appliedIso)
            lastCaptureProfile = CaptureProfile("parallel", reads.size, Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, 0.0, 0.0, (System.nanoTime() - t0) / 1e6)
            return reads
        }
    }

    @Volatile private var parallel: ParallelStream? = null
    val parallelActive: Boolean get() = parallel != null

    /** Parallel mode (see [ParallelStream]): from now on [captureFast] and [captureFastAe] hand
     *  out the newest fresh reads of a continuous stream, one call every [periodMs]. */
    @Synchronized
    fun startParallel(depth: Int = 3, periodMs: Long = 100L) {
        check(fastCaptureStarted) { "startFastCapture() must be called first" }
        if (parallel != null) return
        parallel = ParallelStream(depth, periodMs * 1_000_000L).also { it.thread.start() }
        Log.i(TAG, "parallel mode: depth $depth, period $periodMs ms")
    }

    @Synchronized
    fun stopParallel() {
        val p = parallel ?: return
        parallel = null
        p.active = false
        runCatching { p.thread.join(3000) }
    }

    /** Parallel mode, several settings: from now on the stream cycles [states]. */
    fun parallelSetPattern(states: List<SensorState>) {
        (parallel ?: error("parallel mode is off")).pattern = states.map { ParallelKey.Manual(it) }
    }

    /** Parallel mode, a window planned ahead: [states] are requested once each, in order, after
     *  the windows queued before; then the stream cycles them until the next window is queued. */
    fun parallelEnqueue(states: List<SensorState>) {
        val p = parallel ?: error("parallel mode is off")
        val keys = states.map { ParallelKey.Manual(it) }
        p.queue.addAll(keys)
        p.pattern = keys
    }

    /** Parallel mode, several settings: after the output clock, the newest fresh read of each of
     *  [states], in that order (states[0] the output's; see [ParallelStream.take]). The pattern is
     *  the caller's ([parallelSetPattern]). */
    fun parallelTakeEach(states: List<SensorState>): List<Pair<RawFrame, RawMeta>> =
        (parallel ?: error("parallel mode is off")).take(states.map { ParallelKey.Manual(it) }, setPattern = false)
            .map { it.frame to it.meta }

    @Synchronized
    fun stopFastCapture() {
        stopParallel()
        if (!fastCaptureStarted) return
        reader?.setOnImageAvailableListener(null, null)
        reader?.let { drainReader(it) }
        fastTargets.clear(); fastResults.clear()
        fastCaptureStarted = false
    }

    /** EXP5.2: keep one listener and one repeating RAW stream alive for a bounded experiment.
     * Images whose request tag or applied sensor state is stale are closed before decode.
     * Only metadata-confirmed frames enter the six-frame decoded ring. */
    @Synchronized
    fun startContinuousCapture() {
        if (continuousStarted) return
        val imageReader = reader ?: error("RAW reader is closed")
        val sess = session ?: error("camera session is closed")
        activeContinuousTarget = null
        continuousResults.clear()
        drainReader(imageReader)
        imageReader.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireNextImage() }.getOrNull()
                ?: return@setOnImageAvailableListener
            decodePool.execute {
                try {
                    val result = awaitResult(image.timestamp, continuousResults,
                        continuousResultLock, 2_000) ?: return@execute
                    val target = activeContinuousTarget
                    val tag = result.request.tag as? Long
                    val actual = result.sensorState()
                    if (target == null || tag != target.commandId ||
                        !scheduler.matches(target.expected, actual)) {
                        target?.let { synchronized(it.lock) { it.staleFrames++ } }
                        return@execute
                    }
                    val decodeStart = System.nanoTime()
                    val decoded = decode(image, result, target.expected, target.commandId,
                        nativeDecode = true)
                    val decodeEnd = System.nanoTime()
                    synchronized(target.lock) {
                        if (activeContinuousTarget !== target) return@synchronized
                        if (target.firstMatchedNs == 0L) target.firstMatchedNs = decodeStart
                        target.decodeCpuNs += decodeEnd - decodeStart
                        target.decodedFrames++
                        target.ring.add(decoded)
                        target.ring.sortBy { it.meta.frameNumber }
                        while (target.ring.size > CONTINUOUS_RING_CAPACITY) {
                            target.ring.removeAt(0)
                        }
                        if (target.ring.size >= target.wanted && target.readyNs == 0L) {
                            target.readyNs = decodeEnd
                        }
                        target.lock.notifyAll()
                    }
                } catch (t: Throwable) {
                    activeContinuousTarget?.let {
                        synchronized(it.lock) { it.error = t; it.lock.notifyAll() }
                    }
                } finally {
                    image.close()
                }
            }
        }, cameraHandler)
        // The first concrete target is installed by captureContinuous().
        continuousStarted = true
        Log.i(TAG, "EXP5.2 continuous listener armed session=$sess")
    }

    @Synchronized
    fun captureContinuous(exposureUs: Int, iso: Int, nBurst: Int): List<RawFrame> {
        check(continuousStarted) { "startContinuousCapture() must be called first" }
        val wanted = max(1, nBurst)
        require(wanted <= CONTINUOUS_RING_CAPACITY)
        val expected = SensorState(exposureUs.toLong(), iso)
        val command = scheduler.enqueue(expected)
        val target = ContinuousTarget(expected, command.id, wanted, System.nanoTime())
        activeContinuousTarget = target
        val template = if (wanted == 1) CameraDevice.TEMPLATE_PREVIEW
            else CameraDevice.TEMPLATE_STILL_CAPTURE
        val request = manualRequest(expected, command.id, template)
        val callback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: -1L
                val tag = result.request.tag as? Long
                if (tag == target.commandId) {
                    synchronized(target.lock) {
                        if (target.firstResultNs == 0L) target.firstResultNs = System.nanoTime()
                    }
                    scheduler.markRequestFrame(command.id, result.frameNumber)
                    val actual = result.sensorState()
                    scheduler.observe(result.frameNumber, ts, actual)?.let {
                        lastApplyDelayFrames = it.applyDelayFrames
                        currentState = actual
                    }
                }
                continuousResults[ts] = result
                synchronized(continuousResultLock) { continuousResultLock.notifyAll() }
            }

            override fun onCaptureFailed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                failure: CaptureFailure
            ) {
                synchronized(target.lock) {
                    target.error = IllegalStateException("continuous capture failed: ${failure.reason}")
                    target.lock.notifyAll()
                }
            }
        }
        (session ?: error("camera session is closed"))
            .setRepeatingRequest(request, callback, cameraHandler)
        val timeoutNs = TimeUnit.MILLISECONDS.toNanos(max(6_000L, wanted * 1_500L))
        synchronized(target.lock) {
            while (target.ring.size < wanted && target.error == null) {
                val left = timeoutNs - (System.nanoTime() - target.startedNs)
                if (left <= 0L) break
                target.lock.wait(minOf(100L,
                    TimeUnit.NANOSECONDS.toMillis(left).coerceAtLeast(1L)))
            }
        }
        target.error?.let { throw it }
        check(target.ring.size >= wanted) {
            "Continuous capture only obtained ${target.ring.size}/$wanted frames for " +
                "${exposureUs}us ISO$iso"
        }
        val accepted = synchronized(target.lock) { target.ring.takeLast(wanted) }
        fun elapsed(ns: Long): Double = if (ns == 0L) Double.NaN
            else (ns - target.startedNs) / 1e6
        lastContinuousProfile = ContinuousProfile(
            requestedFrames = wanted,
            firstResultMs = elapsed(target.firstResultNs),
            firstMatchedMs = elapsed(target.firstMatchedNs),
            readyMs = elapsed(target.readyNs),
            decodeCpuSumMs = target.decodeCpuNs / 1e6,
            staleFramesDropped = target.staleFrames,
            decodedFrames = target.decodedFrames)
        lastMeta = accepted.map { it.meta }
        captureSnapshot.set(CaptureSnapshot(lastMeta.map { it.copy(
            blackLevels = it.blackLevels.copyOf(),
            whiteBalance = it.whiteBalance.copyOf(),
            cameraToSrgb = it.cameraToSrgb.copyOf()) }, lastCaptureProfile))
        lastSettlingMeta = lastMeta
        accepted.lastOrNull()?.let {
            currentState = SensorState(it.meta.appliedExpUs, it.meta.appliedIso)
        }
        return accepted.map { it.frame }
    }

    /** Snapshot belonging to the calling capture worker, immune to concurrent streaming frames. */
    fun consumeCaptureSnapshot(): CaptureSnapshot? {
        val value = captureSnapshot.get()
        captureSnapshot.remove()
        return value
    }

    @Synchronized
    fun stopContinuousCapture() {
        if (!continuousStarted) return
        activeContinuousTarget = null
        runCatching { session?.stopRepeating() }
        reader?.setOnImageAvailableListener(null, null)
        reader?.let { drainReader(it) }
        continuousResults.clear()
        continuousStarted = false
    }

    fun armNativeDecodeValidation() {
        lastNativeDecodeMismatchCount = -1
        nativeValidationRemaining.set(1)
    }

    /** EXP5.4: change RAW decode parallelism only between completed captures. */
    @Synchronized
    fun configureDecodeThreads(threads: Int) {
        require(threads in 1..4)
        if (threads == decodeThreadCount) return
        val old = decodePool
        old.shutdown()
        check(old.awaitTermination(3, TimeUnit.SECONDS)) {
            "RAW decode workers did not become idle"
        }
        decodePool = Executors.newFixedThreadPool(threads)
        decodeThreadCount = threads
        Log.i(TAG, "EXP5.4 decodeThreads=$threads")
    }

    /** EXP5.1 C: do not queue 12 surplus requests. Submit one frame until metadata matches,
     * then request only the remaining burst frames. This is intentionally camera-only and
     * conservative; the production path remains unchanged until the experiment validates it. */
    @Synchronized
    fun captureAdaptive(exposureUs: Int, iso: Int, nBurst: Int): List<RawFrame> {
        val expected = SensorState(exposureUs.toLong(), iso)
        val command = scheduler.enqueue(expected)
        val request = manualRequest(expected, command.id)
        val wanted = max(1, nBurst)
        val acceptedFrames = ArrayList<RawFrame>(wanted)
        val acceptedMeta = ArrayList<RawMeta>(wanted)
        var submissions = 0
        val maxSubmissions = SETTING_GUARD_FRAMES + wanted
        while (acceptedFrames.size < wanted && submissions < maxSubmissions) {
            val remaining = wanted - acceptedFrames.size
            // Before the first matching result, advance one request at a time. Afterwards,
            // collect only the exact number of additional burst frames needed.
            val count = if (acceptedFrames.isEmpty()) 1 else remaining
            val frames = runCapture(request, count, 0, null, command.id)
            val metas = lastMeta
            submissions += count
            for (i in frames.indices) {
                val meta = metas.getOrNull(i) ?: continue
                if (scheduler.matches(expected,
                        SensorState(meta.appliedExpUs, meta.appliedIso))) {
                    acceptedFrames.add(frames[i])
                    acceptedMeta.add(meta.copy(
                        requestedExpUs = exposureUs.toLong(), requestedIso = iso))
                    if (acceptedFrames.size == wanted) break
                }
            }
        }
        lastSettlingMeta = acceptedMeta.toList()
        lastMeta = acceptedMeta.toList()
        check(acceptedFrames.size == wanted) {
            "Adaptive capture only obtained ${acceptedFrames.size}/$wanted matching frames; " +
                "requested=${exposureUs}us ISO$iso, submissions=$submissions"
        }
        return acceptedFrames
    }

    private fun captureInternal(
        exposureUs: Int, iso: Int, nBurst: Int, metadataFirst: Boolean
    ): List<RawFrame> {
        val expected = SensorState(exposureUs.toLong(), iso)
        val command = scheduler.enqueue(expected)
        val alreadyApplied = currentState?.let { scheduler.matches(expected, it) } == true
        val guard = if (alreadyApplied) 0 else SETTING_GUARD_FRAMES
        val request = manualRequest(expected, command.id)
        return runCapture(request, max(1, nBurst), guard, expected, command.id, metadataFirst)
    }

    fun captureAe(nBurst: Int): List<RawFrame> {
        val request = device!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            applyFixedFocus(this)
            addTarget(reader!!.surface)
        }.build()
        // Same native decoder as every manual path, so the phone-AE baseline's RAW is read
        // exactly like the methods it is compared with.
        return runCapture(request, max(1, nBurst), 0, null, null, nativeDecode = true)
    }

    private fun manualRequest(
        expected: SensorState, commandId: Long,
        template: Int = CameraDevice.TEMPLATE_STILL_CAPTURE
    ): CaptureRequest =
        device!!.createCaptureRequest(template).apply {
            // Keep AE/manual exposure under SoS control while allowing the HAL AWB estimator
            // to publish per-frame gains and its camera-RGB -> linear-sRGB transform.
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_FAST)
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, expected.exposureUs * 1_000L)
            set(CaptureRequest.SENSOR_SENSITIVITY, expected.iso)
            if (pinFrameDuration) {
                set(CaptureRequest.SENSOR_FRAME_DURATION,
                    max(streamMinFrameDurationNs, expected.exposureUs * 1_000L + 1_000_000L))
            }
            applyFixedFocus(this)
            setTag(commandId)
            addTarget(reader!!.surface)
        }.build()

    private fun runCapture(
        request: CaptureRequest,
        wanted: Int,
        guard: Int,
        expected: SensorState?,
        commandId: Long?,
        metadataFirst: Boolean = false,
        nativeDecode: Boolean = false
    ): List<RawFrame> {
        val captureStartNs = System.nanoTime()
        val sess = session ?: error("camera session is closed")
        val imageReader = reader ?: error("RAW reader is closed")
        val count = guard + wanted
        require(count <= MAX_IMAGES) { "capture count $count exceeds ImageReader capacity" }

        val resultByTimestamp = ConcurrentHashMap<Long, TotalCaptureResult>()
        val resultLock = java.lang.Object()
        val futures = LinkedBlockingQueue<Future<Decoded?>>()
        val done = CountDownLatch(count)
        val decodeSlots = AtomicInteger(0)
        val firstResultNs = AtomicLong(0L)
        val firstImageNs = AtomicLong(0L)
        val firstMatchedNs = AtomicLong(0L)
        val firstDecodeStartNs = AtomicLong(0L)
        val lastDecodeEndNs = AtomicLong(0L)
        val decodeCpuNs = LongAdder()
        val allResultMeta = java.util.Collections.synchronizedList(ArrayList<RawMeta>())

        imageReader.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireNextImage() }.getOrNull() ?: return@setOnImageAvailableListener
            firstImageNs.compareAndSet(0L, System.nanoTime())
            futures.offer(decodePool.submit<Decoded?> {
                try {
                    val result = awaitResult(image.timestamp, resultByTimestamp, resultLock, 2_000)
                        ?: return@submit null
                    if (metadataFirst && expected != null) {
                        val actual = result.sensorState()
                        if (!scheduler.matches(expected, actual)) return@submit null
                        // Settling frames and surplus matching frames do not enter the costly
                        // 12 MP RAW decode pool. Reserve exactly the frames the caller needs.
                        if (decodeSlots.getAndIncrement() >= wanted) return@submit null
                    }
                    firstMatchedNs.compareAndSet(0L, System.nanoTime())
                    val decodeStart = System.nanoTime()
                    firstDecodeStartNs.compareAndSet(0L, decodeStart)
                    decode(image, result, expected, commandId, nativeDecode).also {
                        val end = System.nanoTime()
                        decodeCpuNs.add(end - decodeStart)
                        while (true) {
                            val old = lastDecodeEndNs.get()
                            if (end <= old || lastDecodeEndNs.compareAndSet(old, end)) break
                        }
                    }
                } finally {
                    image.close()
                }
            })
        }, cameraHandler)

        val callback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                firstResultNs.compareAndSet(0L, System.nanoTime())
                val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: -1L
                if (commandId != null) {
                    scheduler.markRequestFrame(commandId, result.frameNumber)
                    val actual = result.sensorState()
                    scheduler.observe(result.frameNumber, ts, actual)?.let {
                        lastApplyDelayFrames = it.applyDelayFrames
                        currentState = actual
                    }
                }
                resultByTimestamp[ts] = result
                synchronized(resultLock) { resultLock.notifyAll() }
                done.countDown()
            }

            override fun onCaptureFailed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                failure: CaptureFailure
            ) { done.countDown() }
        }

        sess.captureBurst(List(count) { request }, callback, cameraHandler)
        val timeoutMs = max(6_000L, count * 400L)
        val decoded = ArrayList<Decoded>()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var ready = false
        while (System.nanoTime() < deadline) {
            val future = futures.poll(100, TimeUnit.MILLISECONDS) ?: continue
            runCatching { future.get(2, TimeUnit.SECONDS) }.getOrNull()?.let { decoded.add(it) }
            val eligibleCount = if (expected == null) decoded.size else decoded.count {
                scheduler.matches(expected, SensorState(it.meta.appliedExpUs, it.meta.appliedIso))
            }
            ready = eligibleCount >= wanted
            if (metadataFirst && ready) break
            if (!metadataFirst && decoded.size >= count) break
        }
        val cleanupStartNs = System.nanoTime()
        if (metadataFirst && ready && done.count > 0) {
            // Remaining requests are settling frames that are no longer needed. Abort only
            // this finite burst; the next explicit capture starts from the observed state.
            runCatching { sess.abortCaptures() }
            done.await(400, TimeUnit.MILLISECONDS)
        } else {
            done.await(400, TimeUnit.MILLISECONDS)
        }
        imageReader.setOnImageAvailableListener(null, null)
        drainReader(imageReader)
        while (true) {
            val surplus = futures.poll() ?: break
            surplus.cancel(true)
        }
        val cleanupEndNs = System.nanoTime()

        decoded.sortBy { it.meta.frameNumber }
        allResultMeta.addAll(decoded.map { it.meta })
        lastSettlingMeta = allResultMeta.toList()
        val eligible = if (expected == null) decoded else decoded.filter {
            scheduler.matches(expected, SensorState(it.meta.appliedExpUs, it.meta.appliedIso))
        }
        val accepted = eligible.takeLast(wanted)
        fun sinceStart(value: Long): Double =
            if (value == 0L) Double.NaN else (value - captureStartNs) / 1e6
        val decodeStartNs = firstDecodeStartNs.get()
        val decodeEndNs = lastDecodeEndNs.get()
        lastCaptureProfile = CaptureProfile(
            strategy = if (nativeDecode) "native_neon" else
                if (metadataFirst) "metadata_first_abort" else "kotlin",
            requestedFrames = wanted,
            firstResultMs = sinceStart(firstResultNs.get()),
            firstImageMs = sinceStart(firstImageNs.get()),
            firstMatchedMs = sinceStart(firstMatchedNs.get()),
            decodeWallMs = if (decodeStartNs == 0L || decodeEndNs == 0L) Double.NaN
                else (decodeEndNs - decodeStartNs) / 1e6,
            decodeCpuSumMs = decodeCpuNs.sum() / 1e6,
            cleanupMs = (cleanupEndNs - cleanupStartNs) / 1e6,
            totalMs = (cleanupEndNs - captureStartNs) / 1e6)
        check(accepted.size == wanted) {
            "Only ${accepted.size}/$wanted metadata-confirmed RAW frames; " +
                "requested=${expected?.exposureUs}us ISO${expected?.iso}, guard=$guard"
        }
        lastMeta = accepted.map { it.meta }
        accepted.lastOrNull()?.let {
            currentState = SensorState(it.meta.appliedExpUs, it.meta.appliedIso)
        }
        return accepted.map { it.frame }
    }

    private fun awaitResult(
        timestamp: Long,
        results: ConcurrentHashMap<Long, TotalCaptureResult>,
        lock: java.lang.Object,
        timeoutMs: Long
    ): TotalCaptureResult? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        synchronized(lock) {
            while (results[timestamp] == null) {
                val left = deadline - System.nanoTime()
                if (left <= 0) return null
                lock.wait(minOf(50L, TimeUnit.NANOSECONDS.toMillis(left).coerceAtLeast(1L)))
            }
        }
        return results.remove(timestamp)
    }

    private fun TotalCaptureResult.sensorState(): SensorState = SensorState(
        exposureUs = (get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: -1L) / 1_000L,
        iso = get(CaptureResult.SENSOR_SENSITIVITY) ?: -1
    )

    private fun decode(
        image: Image,
        result: TotalCaptureResult,
        expected: SensorState?,
        commandId: Long?,
        nativeDecode: Boolean = false
    ): Decoded {
        val plane = image.planes[0]
        val bytes = plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val dynamic = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
        val black = if (dynamic != null && dynamic.size >= 4) {
            IntArray(4) { dynamic[it].toInt() }
        } else staticBlack.copyOf()

        val outWidth = (width / 2) and 1.inv()
        val outHeight = (height / 2) and 1.inv()
        val out = IntArray(outWidth * outHeight)
        val mode = decodeMode
        val nativeOk = nativeDecode && if (streamFormat == ImageFormat.RAW10) {
            NativeTensorPreprocessor.decodeSubsampledRaw10(
                bytes, bytes.position(), width, height, rowStride, black, out, mode)
        } else {
            NativeTensorPreprocessor.decodeSubsampledBayer(
                bytes, bytes.position(), width, height, rowStride, pixelStride, black, out, mode)
        }
        if (nativeOk && nativeValidationRemaining.compareAndSet(1, 0)) {
            val reference = IntArray(out.size)
            if (streamFormat == ImageFormat.RAW10) {
                decodeRaw10Reference(bytes, rowStride, black, outWidth, outHeight, reference, mode)
            } else {
                decodeReference(bytes, rowStride, pixelStride, black, outWidth, outHeight, reference, mode)
            }
            var mismatches = 0
            for (i in out.indices) if (out[i] != reference[i]) mismatches++
            lastNativeDecodeMismatchCount = mismatches
        }
        if (!nativeOk) {
            if (streamFormat == ImageFormat.RAW10) {
                decodeRaw10Reference(bytes, rowStride, black, outWidth, outHeight, out, mode)
            } else {
                decodeReference(bytes, rowStride, pixelStride, black, outWidth, outHeight, out, mode)
            }
        }

        val actual = result.sensorState()
        val calibration = colorCalibration(result)
        val applied = commandId?.let { id ->
            scheduler.historySnapshot().lastOrNull { it.id == id }?.applyDelayFrames
        }
        val meta = RawMeta(
            requestedIso = expected?.iso ?: -1,
            appliedIso = actual.iso,
            requestedExpUs = expected?.exposureUs ?: -1,
            appliedExpUs = actual.exposureUs,
            frameNumber = result.frameNumber,
            timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: image.timestamp,
            black = black.average().toInt(),
            blackLevels = black,
            white = maxDn.toInt(),
            frameDurationNs = result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: -1L,
            applyDelayFrames = applied,
            rowStrideBytes = rowStride,
            pixelStrideBytes = pixelStride,
            whiteBalance = calibration.first.copyOf(),
            cameraToSrgb = calibration.second.copyOf(),
            noiseProfile = result.get(CaptureResult.SENSOR_NOISE_PROFILE)?.let { p ->
                DoubleArray(p.size * 2) { i -> if (i % 2 == 0) p[i / 2].first else p[i / 2].second }
            }
        )
        rawTap?.let { tap ->
            runCatching {
                tap.sink(rawCrop(bytes, rowStride, pixelStride, black, tap.cropW, tap.cropH), meta)
            }
        }
        return Decoded(RawFrame(out, outWidth, outHeight, cfaPattern, maxDn,
            sensorOrientation, calibration.first.copyOf(), calibration.second.copyOf()), meta)
    }

    /** Centre crop of raw pixels, NOT binned, black removed per CFA channel, signed. The
     *  crop origin is even, so the CFA phase of the crop equals the sensor's. */
    private fun rawCrop(
        bytes: java.nio.ByteBuffer, rowStride: Int, pixelStride: Int, black: IntArray,
        cropW: Int, cropH: Int
    ): IntArray {
        val cw = minOf(cropW, width) and 1.inv(); val ch = minOf(cropH, height) and 1.inv()
        val x0 = ((width - cw) / 2) and 1.inv(); val y0 = ((height - ch) / 2) and 1.inv()
        val out = IntArray(cw * ch)
        val raw10 = streamFormat == ImageFormat.RAW10
        for (y in 0 until ch) {
            val sy = y0 + y
            for (x in 0 until cw) {
                val sx = x0 + x
                val v = if (raw10) {
                    val off = sy * rowStride + (sx / 4) * 5
                    val k = sx % 4
                    ((bytes.get(off + k).toInt() and 0xff) shl 2) or
                        (((bytes.get(off + 4).toInt() and 0xff) ushr (2 * k)) and 3)
                } else bytes.getShort(sy * rowStride + sx * pixelStride).toInt() and 0xFFFF
                out[y * cw + x] = v - black[(sy and 1) * 2 + (sx and 1)]
            }
        }
        return out
    }

    /** Every 4x4 sensor block -> one 2x2 Bayer quad; each site is the mean of the block's
     *  four same-colour pixels (rows iy, iy+2; columns ix, ix+2), black removed, NOT clipped.
     *  Must match the native decoder bit for bit (floor rounding of the mean). */
    private fun decodeReference(
        bytes: java.nio.ByteBuffer, rowStride: Int, pixelStride: Int,
        black: IntArray, outWidth: Int, outHeight: Int, out: IntArray, mode: Int = 0
    ) {
        fun px(y: Int, x: Int) = bytes.getShort(y * rowStride + x * pixelStride).toInt() and 0xFFFF
        for (oy in 0 until outHeight) {
            val iy = (oy and 1.inv()) * 2 + (oy and 1)
            val rowOut = oy * outWidth
            for (ox in 0 until outWidth) {
                val ix = (ox and 1.inv()) * 2 + (ox and 1)
                val channelBlack = black[(iy and 1) * 2 + (ix and 1)]
                out[rowOut + ox] = if (mode == 1) (px(iy, ix) - channelBlack).coerceAtLeast(0) else {
                    val sum = px(iy, ix) + px(iy, ix + 2) + px(iy + 2, ix) + px(iy + 2, ix + 2)
                    Math.floorDiv(sum - 4 * channelBlack + 2, 4)
                }
            }
        }
    }

    private fun decodeRaw10Reference(
        bytes: java.nio.ByteBuffer, rowStride: Int, black: IntArray,
        outWidth: Int, outHeight: Int, out: IntArray, mode: Int = 0
    ) {
        fun unpack(offset: Int, k: Int): Int {
            val tails = bytes.get(offset + 4).toInt() and 0xff
            return ((bytes.get(offset + k).toInt() and 0xff) shl 2) or ((tails ushr (2 * k)) and 3)
        }
        for (oy in 0 until outHeight) {
            val iy = (oy and 1.inv()) * 2 + (oy and 1)
            val rowOut = oy * outWidth
            val blackRow = (iy and 1) * 2
            for (group in 0 until outWidth / 2) {
                val a = iy * rowStride + group * 5
                if (mode == 1) {
                    out[rowOut + group * 2] = (unpack(a, 0) - black[blackRow]).coerceAtLeast(0)
                    out[rowOut + group * 2 + 1] = (unpack(a, 1) - black[blackRow + 1]).coerceAtLeast(0)
                    continue
                }
                val b = (iy + 2) * rowStride + group * 5
                val even = unpack(a, 0) + unpack(a, 2) + unpack(b, 0) + unpack(b, 2)
                val odd = unpack(a, 1) + unpack(a, 3) + unpack(b, 1) + unpack(b, 3)
                out[rowOut + group * 2] = Math.floorDiv(even - 4 * black[blackRow] + 2, 4)
                out[rowOut + group * 2 + 1] = Math.floorDiv(odd - 4 * black[blackRow + 1] + 2, 4)
            }
        }
    }

    /** Camera2 AWB operates independently of manual AE. Apply its gains in linear camera RGB,
     *  followed by the HAL's row-major camera-RGB -> linear-sRGB transform. */
    private fun colorCalibration(result: TotalCaptureResult): Pair<FloatArray, DoubleArray> {
        val vector = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
        val measuredWb = vector?.let {
            floatArrayOf(it.red, (it.greenEven + it.greenOdd) * 0.5f, it.blue)
        }?.takeIf { values -> values.all { it.isFinite() && it > 0f } }
        val neutralWb = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)?.let { neutral ->
            if (neutral.size >= 3) floatArrayOf(
                1f / neutral[0].toFloat(), 1f / neutral[1].toFloat(), 1f / neutral[2].toFloat()
            ) else null
        }?.takeIf { values -> values.all { it.isFinite() && it > 0f } }
        val wb = measuredWb ?: neutralWb ?: lastWhiteBalance
        if (measuredWb != null || neutralWb != null) lastWhiteBalance = wb.copyOf()

        val transform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
        val measuredCcm = transform?.let { value ->
            DoubleArray(9) { i -> value.getElement(i % 3, i / 3).toDouble() }
        }?.takeIf { values -> values.all { it.isFinite() } && values.any { kotlin.math.abs(it) > 1e-9 } }
        val ccm = measuredCcm ?: lastCameraToSrgb
        if (measuredCcm != null) lastCameraToSrgb = ccm.copyOf()
        return wb to ccm
    }

    private fun drainReader(imageReader: ImageReader) {
        while (true) {
            val image = runCatching { imageReader.acquireNextImage() }.getOrNull() ?: break
            image.close()
        }
    }

    fun close() {
        runCatching { stopContinuousCapture() }
        runCatching { stopFastCapture() }
        runCatching { reader?.setOnImageAvailableListener(null, null) }
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { reader?.close() }
        decodePool.shutdown()
        runCatching { decodePool.awaitTermination(2, TimeUnit.SECONDS) }
        cameraThread.quitSafely()
        imageThread.quitSafely()
        session = null; device = null; reader = null
    }
}
