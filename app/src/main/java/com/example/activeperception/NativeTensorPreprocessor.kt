package com.example.activeperception

import java.nio.ByteBuffer

/** EXP2.1 native arm. Production paths never call this object. */
object NativeTensorPreprocessor {
    init { System.loadLibrary("sos_tensor") }

    external fun clearTensor(
        destination: ByteBuffer, floatOffset: Int, floatCount: Int, value: Float
    )

    /** Writes one horizontal content stripe into an already padded HWC float tensor. */
    external fun fillOriginalSrgbStripe(
        r: IntArray, g: IntArray, b: IntArray,
        width: Int, height: Int, orientation: Int, gain: Double,
        srgbLut: IntArray, destination: ByteBuffer, baseFloat: Int,
        imageSize: Int, outputYStart: Int, outputYEnd: Int
    ): Boolean

    /** Fuses first-N Bayer sum, 2x2 demosaic, digital gain, sRGB and letterbox resize. */
    external fun fillFusedBayerSrgbRowStripe(
        frames: Array<IntArray>, nSum: Int,
        bayerWidth: Int, bayerHeight: Int, cfaPattern: String,
        orientation: Int, gains: DoubleArray, baseFloats: IntArray,
        srgbLut: IntArray, destination: ByteBuffer,
        imageSize: Int, outputYStart: Int, outputYEnd: Int,
        /** 0 = area average in linear values (current); 1 = pre-2026-10-15 bilinear blend
         *  of clipped, sRGB-encoded quads (kept only for the before/after comparison). */
        resampleMode: Int
    ): Boolean

    /** 4x4 block -> 2x2 quad RAW decode + per-channel black subtraction. */
    external fun decodeSubsampledBayer(
        source: ByteBuffer, sourceByteOffset: Int,
        sourceWidth: Int, sourceHeight: Int,
        rowStrideBytes: Int, pixelStrideBytes: Int,
        blackLevels: IntArray, destination: IntArray,
        /** 0 = mean of the 4 same-colour pixels, signed (current); 1 = pre-2026-10-15:
         *  one pixel of the 4, clipped at black. */
        mode: Int
    ): Boolean

    /** Packs one HWC float lane of a direct tensor buffer, starting at [floatOffset], into
     *  opaque ARGB pixels (v * 255, truncated, clamped), one per element of [destination].
     *  False when the buffer is not direct or too short. */
    external fun tensorLaneToArgb(source: ByteBuffer, floatOffset: Int, destination: IntArray): Boolean

    /** RAW10 packed variant of [decodeSubsampledBayer], same modes. */
    external fun decodeSubsampledRaw10(
        source: ByteBuffer, sourceByteOffset: Int,
        sourceWidth: Int, sourceHeight: Int,
        rowStrideBytes: Int, blackLevels: IntArray,
        destination: IntArray,
        mode: Int
    ): Boolean
}
