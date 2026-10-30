package com.example.activeperception.acquire

/**
 * The exposure lattice the proposed method and its records use: 5 ISOs x 5 shutters, cell
 * c = ISO index * 5 + shutter index ([HowToSense.cell]), plus two thresholds shared by the
 * per-image filtering and fusion in [V3Cause].
 *
 * Boxes are [x0, y0, x1, y1] in detector-input pixels.
 */
object Duel {
    /** Nominal ISO ladder; 1600 = analog ceiling x digital residual. */
    val ISOS = intArrayOf(100, 200, 400, 800, 1600)
    /** 1/240, 1/120, 1/60, 1/30, 1/15 s. */
    val SHUTTERS_US = intArrayOf(4167, 8333, 16667, 33333, 66667)
    /** IoU at which a car counts as caught in an image. */
    const val SEEN_IOU = 0.3
    /** RAW sites read per box for a clipped fraction (fusion weights, records): enough for
     *  a fraction (standard error under 1%); reading up to 40,000 per box per image made the
     *  fusion ~15 ms a frame on the phone. */
    const val SAT_SITES = 4_096
}
