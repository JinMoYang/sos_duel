package com.example.activeperception

import android.graphics.Bitmap
import com.example.activeperception.acquire.CandidateSource
import com.example.activeperception.acquire.Formation
import com.example.activeperception.acquire.Grid
import com.example.activeperception.acquire.RawCapturer
import com.example.activeperception.acquire.RawFrame
import com.example.activeperception.acquire.DirectTensorBatch
import com.example.activeperception.acquire.TensorLetterbox
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.floor

enum class ColorPipeline(val tag: String) {
    RAYNEO_AWB_CCM("A_awb_ccm"),
    ORIGINAL_GAIN_SRGB("B_original_gain_srgb")
}

enum class BurstWindow { CENTERED, FIRST_N }

data class FormationProfile(
    val pipeline: String,
    val k: Int,
    val distinctRows: Int,
    val rowWallMs: Double,
    val sumCpuMs: Double,
    val demosaicCpuMs: Double,
    val packWallMs: Double,
    val packCpuMs: Double,
    val bitmapMs: Double,
    val totalMs: Double
)

data class Exp21PathProfile(
    val path: String,
    val k: Int,
    val transformWallMs: Double,
    val transformCpuMs: Double,
    val bitmapMs: Double,
    val totalMs: Double
)

data class Exp21TensorResult(val batch: DirectTensorBatch, val profile: Exp21PathProfile)

/**
 * Drop-in for [com.example.activeperception.acquire.RawCandidateSource], bit-equivalent but
 * much faster. Two changes: an sRGB lookup table replacing the per-pixel `pow(x, 1/2.4)`,
 * and a worker pool splitting the independent per-row and per-cell work.
 *
 * Four workers keep camera delivery independent from formation. The pure math in
 * `acquire/Formation.kt` is left untouched.
 */
class ParallelRawCandidateSource(
    private val grid: Grid,
    private val capturer: RawCapturer,
    nThreads: Int = 4,
    private val colorPipeline: ColorPipeline = ColorPipeline.RAYNEO_AWB_CCM,
    private val burstWindow: BurstWindow = BurstWindow.FIRST_N
) : CandidateSource<Bitmap> {

    /** 0 (default): area average in linear values, one clip after the gain. 1: the
     *  pre-2026-10-15 bilinear blend, kept only for the before/after comparison. */
    @Volatile var resampleMode: Int = 0

    private val pool = Executors.newFixedThreadPool(nThreads)
    private val exp21TensorBuffers = ConcurrentHashMap<Int, ByteBuffer>()
    @Volatile private var srgbLut: IntArray = IntArray(0)
    @Volatile private var lutMaxDn: Double = -1.0
    @Volatile var lastProfile: FormationProfile? = null
        private set

    private data class RowResult(
        val shutterIdx: Int,
        val rgb: Array<IntArray>,
        val sumMs: Double,
        val demosaicMs: Double
    )

    private data class PackResult(val argb: Formation.OrientedArgb, val cpuMs: Double)

    /** sRGB LUT for the given white level, rebuilt only when it changes. Values fed to it are
     *  already clipped to [0, maxDn] by the caller, so the table needs no headroom. */
    private fun lutFor(maxDn: Double): IntArray {
        if (lutMaxDn != maxDn) {
            val n = maxDn.toInt() + 1
            srgbLut = IntArray(n) { Formation.srgbU8(it.toDouble(), maxDn) }
            lutMaxDn = maxDn
        }
        return srgbLut
    }

    override fun render(cells: IntArray): List<Bitmap> {
        val rows = cells.map { grid.indices(it).second }.toSortedSet()
        val frames: List<RawFrame> = if (rows.size == 1) {
            capturer.capture(grid.exposuresUs[rows.first()], grid.baseGain, 1)
        } else {
            capturer.capture(grid.fastestExposureUs, grid.baseGain, grid.maxBurst)
        }
        require(frames.isNotEmpty()) { "RawCapturer returned no frames" }
        return formAllCells(frames, cells)
    }

    /** Form bitmaps from already-captured frames, so callers that want to time formation
     *  alone (Bench, Verify) can keep capture out of the measurement. Always returns exactly
     *  `cells.size` bitmaps, in order — callers index detections against `cells`. */
    fun formAllCells(frames: List<RawFrame>, cells: IntArray): List<Bitmap> {
        val totalStart = System.nanoTime()
        val avail = frames.size
        val w = frames[0].width; val h = frames[0].height
        val maxDn = frames[0].maxDn; val cfa = frames[0].cfaPattern
        // One calibration for the whole bracket prevents AWB drift from changing candidate
        // ranking. The temporal center corresponds best to all centered shutter windows.
        val calibration = frames[avail / 2]
        val lut = lutFor(maxDn)
        val lutMax = lut.size - 1

        // Per shutter row: burst-sum + demosaic. This is the expensive stage and it scales
        // with the number of DISTINCT rows in `cells`, not with cells.size.
        val rows = cells.map { grid.indices(it).second }.toSortedSet()
        val rowStart = System.nanoTime()
        val rowFutures = rows.map { sj ->
            pool.submit<RowResult> {
                val n = minOf(grid.burstN(sj), avail)
                val sumStart = System.nanoTime()
                val summed = IntArray(frames[0].bayer.size)
                // The deployed original-SoS path sums the first N planes. Keep the centered
                // RayNeo window only in the legacy A arm so the historical A/B is reproducible.
                val start = if (burstWindow == BurstWindow.FIRST_N) 0 else (avail - n) / 2
                for (k in start until start + n) {
                    val bk = frames[k].bayer
                    for (p in summed.indices) summed[p] += bk[p]
                }
                val demosaicStart = System.nanoTime()
                val rgb = Formation.demosaic2x2(summed, w, h, cfa)
                val end = System.nanoTime()
                RowResult(sj, rgb,
                    (demosaicStart - sumStart) / 1e6,
                    (end - demosaicStart) / 1e6)
            }
        }
        val rgbByRow = HashMap<Int, Array<IntArray>>()
        var sumCpuMs = 0.0
        var demosaicCpuMs = 0.0
        for (f in rowFutures) {
            val result = f.get()
            rgbByRow[result.shutterIdx] = result.rgb
            sumCpuMs += result.sumMs
            demosaicCpuMs += result.demosaicMs
        }
        val rowEnd = System.nanoTime()

        // Per cell: digital re-gain + sRGB encode + ARGB pack.
        val ow = w / 2; val oh = h / 2
        val orientation = calibration.sensorOrientation
        val packStart = System.nanoTime()
        val cellFutures = cells.map { c ->
            pool.submit<PackResult> {
                val start = System.nanoTime()
                val (gi, sj) = grid.indices(c)
                val rgb = rgbByRow[sj]!!
                val argb = when (colorPipeline) {
                    ColorPipeline.RAYNEO_AWB_CCM -> packOrientedArgbWithLut(
                        rgb[0], rgb[1], rgb[2], ow, oh, orientation,
                        grid.gainRatio(gi), calibration.whiteBalance,
                        calibration.cameraToSrgb, lut, lutMax)
                    ColorPipeline.ORIGINAL_GAIN_SRGB -> packOriginalOrientedArgbWithLut(
                        rgb[0], rgb[1], rgb[2], ow, oh, orientation,
                        grid.gainRatio(gi), lut, lutMax)
                }
                PackResult(argb, (System.nanoTime() - start) / 1e6)
            }
        }
        val packed = cellFutures.map { it.get() }
        val packEnd = System.nanoTime()
        val bitmapStart = System.nanoTime()
        val bitmaps = packed.map {
            val oriented = it.argb
            Bitmap.createBitmap(oriented.pixels, oriented.width, oriented.height, Bitmap.Config.ARGB_8888)
        }
        val end = System.nanoTime()
        lastProfile = FormationProfile(
            pipeline = colorPipeline.tag,
            k = cells.size,
            distinctRows = rows.size,
            rowWallMs = (rowEnd - rowStart) / 1e6,
            sumCpuMs = sumCpuMs,
            demosaicCpuMs = demosaicCpuMs,
            packWallMs = (packEnd - packStart) / 1e6,
            packCpuMs = packed.sumOf { it.cpuMs },
            bitmapMs = (end - bitmapStart) / 1e6,
            totalMs = (end - totalStart) / 1e6
        )
        return bitmaps
    }

    /**
     * Continuous-v2 formation: explicit per-lane burst counts (same-ISO FIRST_N sums over
     * [frames]) and digital gain ratios relative to the frames AS CAPTURED, no grid
     * coupling. The caller owns the rung bookkeeping; lanes sharing a burst count are
     * filled in one native call, mirroring [formFusedNativeTensor]'s per-row grouping.
     */
    fun formExplicitBurstTensor(
        frames: List<RawFrame>, laneBurstN: IntArray, gainRatios: DoubleArray,
        imgsz: Int = 640
    ): Exp21TensorResult {
        require(frames.isNotEmpty())
        require(laneBurstN.size == gainRatios.size && gainRatios.isNotEmpty())
        val started = System.nanoTime()
        val count = gainRatios.size
        val floatsPerFrame = imgsz * imgsz * 3
        val input = exp21TensorBuffers.computeIfAbsent(count) {
            ByteBuffer.allocateDirect(count * floatsPerFrame * 4).order(ByteOrder.nativeOrder())
        }
        val f0 = frames[0]
        val lut = lutFor(f0.maxDn)
        NativeTensorPreprocessor.clearTensor(input, 0, count * floatsPerFrame, 114f / 255f)
        val frameArrays = frames.map { it.bayer }.toTypedArray()
        val byBurst = (0 until count).groupBy { laneBurstN[it] }
        val degrees = (f0.sensorOrientation % 360 + 360) % 360
        val rgbW = f0.width / 2; val rgbH = f0.height / 2
        val orientedH = if (degrees == 90 || degrees == 270) rgbW else rgbH
        val orientedW = if (degrees == 90 || degrees == 270) rgbH else rgbW
        val scale = minOf(imgsz.toFloat() / orientedW, imgsz.toFloat() / orientedH)
        val nh = Math.round(orientedH * scale)
        val futures = ArrayList<java.util.concurrent.Future<Double>>()
        for ((burstN, laneIdx) in byBurst) {
            val gains = DoubleArray(laneIdx.size) { gainRatios[laneIdx[it]] }
            val bases = IntArray(laneIdx.size) { laneIdx[it] * floatsPerFrame }
            // Four stripes in all: lanes of different burst counts (Duel's single frame and
            // sum) used to run one stripe each, making a two-lane formation ~3.5x slower.
            val stripes = maxOf(1, 4 / byBurst.size)
            repeat(stripes) { stripe ->
                val y0 = stripe * nh / stripes; val y1 = (stripe + 1) * nh / stripes
                futures += pool.submit<Double> {
                    val cpuStart = System.nanoTime()
                    check(NativeTensorPreprocessor.fillFusedBayerSrgbRowStripe(
                        frameArrays, minOf(burstN, frames.size), f0.width, f0.height,
                        f0.cfaPattern, f0.sensorOrientation, gains, bases, lut, input,
                        imgsz, y0, y1, resampleMode))
                    (System.nanoTime() - cpuStart) / 1e6
                }
            }
        }
        val cpuMs = futures.sumOf { it.get() }
        input.rewind()
        val nw = Math.round(orientedW * scale)
        val mapping = TensorLetterbox(scale.toDouble(),
            ((imgsz - nw) / 2f).toDouble(), ((imgsz - nh) / 2f).toDouble())
        val end = System.nanoTime()
        return Exp21TensorResult(DirectTensorBatch(input, List(count) { mapping }),
            Exp21PathProfile("V2_explicit_burst_native", count,
                (end - started) / 1e6, cpuMs, 0.0, (end - started) / 1e6))
    }

    /**
     * Lanes from their own frames (Duel2's single-read and tracking-sum bases, 2026-10-28):
     * lane i is the sum of all of [laneFrames]\[i] (one frame, or frames already moved onto
     * the first by a tracking update) at digital ratio [gainRatios]\[i], by the same fused
     * native fill as [formExplicitBurstTensor].
     */
    fun formLanesTensor(
        laneFrames: List<List<RawFrame>>, gainRatios: DoubleArray, imgsz: Int = 640
    ): Exp21TensorResult {
        require(laneFrames.isNotEmpty() && laneFrames.size == gainRatios.size && laneFrames.all { it.isNotEmpty() })
        val started = System.nanoTime()
        val count = laneFrames.size
        val floatsPerFrame = imgsz * imgsz * 3
        val input = exp21TensorBuffers.computeIfAbsent(count) {
            ByteBuffer.allocateDirect(count * floatsPerFrame * 4).order(ByteOrder.nativeOrder())
        }
        val f0 = laneFrames[0][0]
        val lut = lutFor(f0.maxDn)
        NativeTensorPreprocessor.clearTensor(input, 0, count * floatsPerFrame, 114f / 255f)
        val degrees = (f0.sensorOrientation % 360 + 360) % 360
        val rgbW = f0.width / 2; val rgbH = f0.height / 2
        val orientedH = if (degrees == 90 || degrees == 270) rgbW else rgbH
        val orientedW = if (degrees == 90 || degrees == 270) rgbH else rgbW
        val scale = minOf(imgsz.toFloat() / orientedW, imgsz.toFloat() / orientedH)
        val nh = Math.round(orientedH * scale)
        val futures = ArrayList<java.util.concurrent.Future<Double>>()
        val stripes = maxOf(1, 4 / count)
        for (lane in 0 until count) {
            val frameArrays = laneFrames[lane].map { it.bayer }.toTypedArray()
            val gains = doubleArrayOf(gainRatios[lane])
            val bases = intArrayOf(lane * floatsPerFrame)
            repeat(stripes) { stripe ->
                val y0 = stripe * nh / stripes; val y1 = (stripe + 1) * nh / stripes
                futures += pool.submit<Double> {
                    val cpuStart = System.nanoTime()
                    check(NativeTensorPreprocessor.fillFusedBayerSrgbRowStripe(
                        frameArrays, frameArrays.size, f0.width, f0.height,
                        f0.cfaPattern, f0.sensorOrientation, gains, bases, lut, input,
                        imgsz, y0, y1, resampleMode))
                    (System.nanoTime() - cpuStart) / 1e6
                }
            }
        }
        val cpuMs = futures.sumOf { it.get() }
        input.rewind()
        val nw = Math.round(orientedW * scale)
        val mapping = TensorLetterbox(scale.toDouble(),
            ((imgsz - nw) / 2f).toDouble(), ((imgsz - nh) / 2f).toDouble())
        val end = System.nanoTime()
        return Exp21TensorResult(DirectTensorBatch(input, List(count) { mapping }),
            Exp21PathProfile("V2_lanes_native", count,
                (end - started) / 1e6, cpuMs, 0.0, (end - started) / 1e6))
    }

    /**
     * Continuous-v2 formation: one frame, explicit per-lane digital gain ratios, no grid
     * coupling. The ratios are relative to the frame AS CAPTURED (its physical ISO), so
     * the caller owns the base-rung bookkeeping. Same fused native fill as
     * [formFusedNativeTensor] with burst count pinned to 1.
     */
    fun formExplicitGainsTensor(
        frame: RawFrame, gainRatios: DoubleArray, imgsz: Int = 640
    ): Exp21TensorResult {
        require(gainRatios.isNotEmpty())
        val started = System.nanoTime()
        val count = gainRatios.size
        val floatsPerFrame = imgsz * imgsz * 3
        val input = exp21TensorBuffers.computeIfAbsent(count) {
            ByteBuffer.allocateDirect(count * floatsPerFrame * 4).order(ByteOrder.nativeOrder())
        }
        val lut = lutFor(frame.maxDn)
        NativeTensorPreprocessor.clearTensor(input, 0, count * floatsPerFrame, 114f / 255f)
        val frameArrays = arrayOf(frame.bayer)
        val bases = IntArray(count) { it * floatsPerFrame }
        val degrees = (frame.sensorOrientation % 360 + 360) % 360
        val rgbW = frame.width / 2; val rgbH = frame.height / 2
        val orientedH = if (degrees == 90 || degrees == 270) rgbW else rgbH
        val orientedW = if (degrees == 90 || degrees == 270) rgbH else rgbW
        val scale = minOf(imgsz.toFloat() / orientedW, imgsz.toFloat() / orientedH)
        val nh = Math.round(orientedH * scale)
        val futures = ArrayList<java.util.concurrent.Future<Double>>()
        repeat(4) { stripe ->
            val y0 = stripe * nh / 4; val y1 = (stripe + 1) * nh / 4
            futures += pool.submit<Double> {
                val cpuStart = System.nanoTime()
                check(NativeTensorPreprocessor.fillFusedBayerSrgbRowStripe(
                    frameArrays, 1, frame.width, frame.height, frame.cfaPattern,
                    frame.sensorOrientation, gainRatios, bases, lut, input,
                    imgsz, y0, y1, resampleMode))
                (System.nanoTime() - cpuStart) / 1e6
            }
        }
        val cpuMs = futures.sumOf { it.get() }
        input.rewind()
        val nw = Math.round(orientedW * scale)
        val mapping = TensorLetterbox(scale.toDouble(),
            ((imgsz - nw) / 2f).toDouble(), ((imgsz - nh) / 2f).toDouble())
        val end = System.nanoTime()
        return Exp21TensorResult(DirectTensorBatch(input, List(count) { mapping }),
            Exp21PathProfile("V2_explicit_gains_native", count,
                (end - started) / 1e6, cpuMs, 0.0, (end - started) / 1e6))
    }

    /**
     * EXP6 integrated path: RAW burst sum, 2x2 demosaic, gain, sRGB and letterbox are fused
     * in native code. It avoids per-row summed Bayer and three full RGB IntArrays while
     * retaining the exact FIRST_N and integer-green-average semantics of the reference.
     */
    fun formFusedNativeTensor(
        frames: List<RawFrame>, cells: IntArray, imgsz: Int = 640
    ): Exp21TensorResult {
        require(frames.isNotEmpty())
        val started = System.nanoTime()
        val count = cells.size
        val floatsPerFrame = imgsz * imgsz * 3
        val input = exp21TensorBuffers.computeIfAbsent(count) {
            ByteBuffer.allocateDirect(count * floatsPerFrame * 4).order(ByteOrder.nativeOrder())
        }
        val lut = lutFor(frames[0].maxDn)
        NativeTensorPreprocessor.clearTensor(
            input, 0, count * floatsPerFrame, 114f / 255f)
        val frameArrays = frames.map { it.bayer }.toTypedArray()
        val indexedByRow = cells.indices.groupBy { grid.indices(cells[it]).second }
        val futures = ArrayList<java.util.concurrent.Future<Double>>()
        for ((sj, lanes) in indexedByRow) {
            val gains = DoubleArray(lanes.size) { i ->
                grid.gainRatio(grid.indices(cells[lanes[i]]).first)
            }
            val bases = IntArray(lanes.size) { i -> lanes[i] * floatsPerFrame }
            val degrees = (frames[0].sensorOrientation % 360 + 360) % 360
            val rgbW = frames[0].width / 2; val rgbH = frames[0].height / 2
            val orientedH = if (degrees == 90 || degrees == 270) rgbW else rgbH
            val orientedW = if (degrees == 90 || degrees == 270) rgbH else rgbW
            val scale = minOf(imgsz.toFloat() / orientedW, imgsz.toFloat() / orientedH)
            val nh = Math.round(orientedH * scale)
            val stripes = if (indexedByRow.size == 1) 4 else 1
            repeat(stripes) { stripe ->
                val y0 = stripe * nh / stripes; val y1 = (stripe + 1) * nh / stripes
                futures += pool.submit<Double> {
                    val cpuStart = System.nanoTime()
                    check(NativeTensorPreprocessor.fillFusedBayerSrgbRowStripe(
                        frameArrays, minOf(grid.burstN(sj), frames.size),
                        frames[0].width, frames[0].height, frames[0].cfaPattern,
                        frames[0].sensorOrientation, gains, bases, lut, input,
                        imgsz, y0, y1, resampleMode))
                    (System.nanoTime() - cpuStart) / 1e6
                }
            }
        }
        val cpuMs = futures.sumOf { it.get() }
        input.rewind()
        val degrees = (frames[0].sensorOrientation % 360 + 360) % 360
        val rgbW = frames[0].width / 2; val rgbH = frames[0].height / 2
        val orientedW = if (degrees == 90 || degrees == 270) rgbH else rgbW
        val orientedH = if (degrees == 90 || degrees == 270) rgbW else rgbH
        val scale = minOf(imgsz.toFloat() / orientedW, imgsz.toFloat() / orientedH)
        val nw = Math.round(orientedW * scale); val nh = Math.round(orientedH * scale)
        val mapping = TensorLetterbox(scale.toDouble(),
            ((imgsz - nw) / 2f).toDouble(), ((imgsz - nh) / 2f).toDouble())
        val end = System.nanoTime()
        return Exp21TensorResult(DirectTensorBatch(input, List(count) { mapping }),
            Exp21PathProfile("EXP6_fused_bayer_native", count,
                (end - started) / 1e6, cpuMs, 0.0, (end - started) / 1e6))
    }

    /** Materializes only the selected 640 input after detection. All non-selected lanes stay
     * tensor-only, so display does not restore the old K-way ARGB/Bitmap bottleneck. */
    fun selectedTensorBitmap(result: Exp21TensorResult, lane: Int, imgsz: Int = 640): Bitmap {
        require(lane in result.batch.transforms.indices)
        val pixels = IntArray(imgsz * imgsz)
        packArgb(result.batch.input, lane * imgsz * imgsz * 3, pixels)
        return Bitmap.createBitmap(pixels, imgsz, imgsz, Bitmap.Config.ARGB_8888)
    }

    /** This runs on the measurement thread after every detection, before the next capture
     *  is requested. The Kotlin loop took ~45 ms per frame on the S26 Ultra (about 30% of
     *  each method's frame period); the native packing takes a millisecond or two. The
     *  first call in a process compares the two pixel by pixel and keeps Kotlin on any
     *  difference, so saved photos stay exactly what they were. */
    private fun packArgb(input: ByteBuffer, baseFloat: Int, pixels: IntArray) {
        when (nativeArgbOk) {
            true -> if (runCatching {
                    NativeTensorPreprocessor.tensorLaneToArgb(input, baseFloat, pixels)
                }.getOrDefault(false)) return
            null -> {
                if (runCatching {
                        NativeTensorPreprocessor.tensorLaneToArgb(input, baseFloat, pixels)
                    }.getOrDefault(false)) {
                    val reference = IntArray(pixels.size)
                    packArgbKotlin(input, baseFloat, reference)
                    val mismatches = pixels.indices.count { pixels[it] != reference[it] }
                    nativeArgbOk = mismatches == 0
                    android.util.Log.i("ParallelRawSource",
                        "tensorLaneToArgb native vs Kotlin: $mismatches mismatching pixels")
                    if (mismatches != 0) reference.copyInto(pixels)
                    return
                }
                nativeArgbOk = false
            }
            false -> {}
        }
        packArgbKotlin(input, baseFloat, pixels)
    }

    private fun packArgbKotlin(input: ByteBuffer, baseFloat: Int, pixels: IntArray) {
        for (p in pixels.indices) {
            val i = baseFloat + p * 3
            val r = (input.getFloat(i * 4) * 255f).toInt().coerceIn(0, 255)
            val g = (input.getFloat((i + 1) * 4) * 255f).toInt().coerceIn(0, 255)
            val b = (input.getFloat((i + 2) * 4) * 255f).toInt().coerceIn(0, 255)
            pixels[p] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private companion object {
        /** Null until the first conversion has compared native with Kotlin. Process-wide,
         *  since every run builds its own source. */
        @Volatile var nativeArgbOk: Boolean? = null
    }

    /** Detector boxes are in the pre-letterbox image space; the final preview is the exact
     * model tensor, so map boxes into that 640-square coordinate system. */
    fun detectionsForTensorPreview(
        detections: List<com.example.activeperception.acquire.Detection>,
        result: Exp21TensorResult, lane: Int
    ): List<com.example.activeperception.acquire.Detection> {
        val t = result.batch.transforms[lane]
        return detections.map { d ->
            val p = d.xyxy
            com.example.activeperception.acquire.Detection(floatArrayOf(
                (p[0] * t.scale + t.padX).toFloat(),
                (p[1] * t.scale + t.padY).toFloat(),
                (p[2] * t.scale + t.padX).toFloat(),
                (p[3] * t.scale + t.padY).toFloat()
            ), d.confidence, d.classId)
        }
    }

    /** Original SoS minimal ISP: digital gain, clipping, sRGB OETF, orientation and packing.
     *  RayNeo-only timestamp pairing and per-CFA black correction happen before this method. */
    private fun packOriginalOrientedArgbWithLut(
        r: IntArray, g: IntArray, b: IntArray, width: Int, height: Int,
        sensorOrientation: Int, gainRatio: Double, lut: IntArray, lutMax: Int
    ): Formation.OrientedArgb {
        val out = IntArray(r.size)
        val degrees = (sensorOrientation % 360 + 360) % 360
        require(degrees == 0 || degrees == 90 || degrees == 180 || degrees == 270)
        for (p in r.indices) {
            val ri = (r[p] * gainRatio).toInt().coerceIn(0, lutMax)
            val gi = (g[p] * gainRatio).toInt().coerceIn(0, lutMax)
            val bi = (b[p] * gainRatio).toInt().coerceIn(0, lutMax)
            val x = p % width
            val y = p / width
            val dst = when (degrees) {
                0 -> p
                90 -> x * height + (height - 1 - y)
                180 -> out.lastIndex - p
                else -> (width - 1 - x) * height + y
            }
            out[dst] = (0xFF shl 24) or (lut[ri] shl 16) or (lut[gi] shl 8) or lut[bi]
        }
        return if (degrees == 90 || degrees == 270) {
            Formation.OrientedArgb(out, height, width)
        } else Formation.OrientedArgb(out, width, height)
    }

    /** Equivalent to `Formation.packCandidateArgb`, minus the per-pixel `Math.pow`. */
    private fun packOrientedArgbWithLut(
        r: IntArray, g: IntArray, b: IntArray, width: Int, height: Int,
        sensorOrientation: Int, gainRatio: Double,
        whiteBalance: FloatArray, cameraToSrgb: DoubleArray,
        lut: IntArray, lutMax: Int
    ): Formation.OrientedArgb {
        val out = IntArray(r.size)
        val degrees = (sensorOrientation % 360 + 360) % 360
        require(degrees == 0 || degrees == 90 || degrees == 180 || degrees == 270)
        for (p in r.indices) {
            val wr = r[p] * gainRatio * whiteBalance[0]
            val wg = g[p] * gainRatio * whiteBalance[1]
            val wb = b[p] * gainRatio * whiteBalance[2]
            val ri = (cameraToSrgb[0] * wr + cameraToSrgb[1] * wg + cameraToSrgb[2] * wb)
                .toInt().coerceIn(0, lutMax)
            val gi = (cameraToSrgb[3] * wr + cameraToSrgb[4] * wg + cameraToSrgb[5] * wb)
                .toInt().coerceIn(0, lutMax)
            val bi = (cameraToSrgb[6] * wr + cameraToSrgb[7] * wg + cameraToSrgb[8] * wb)
                .toInt().coerceIn(0, lutMax)
            val x = p % width
            val y = p / width
            val dst = when (degrees) {
                0 -> p
                90 -> x * height + (height - 1 - y)
                180 -> out.lastIndex - p
                else -> (width - 1 - x) * height + y // 270
            }
            out[dst] = (0xFF shl 24) or (lut[ri] shl 16) or (lut[gi] shl 8) or lut[bi]
        }
        return if (degrees == 90 || degrees == 270) {
            Formation.OrientedArgb(out, height, width)
        } else Formation.OrientedArgb(out, width, height)
    }

    fun shutdown() {
        pool.shutdown()
        pool.awaitTermination(2, TimeUnit.SECONDS)
        exp21TensorBuffers.clear()
    }
}
