package com.example.activeperception.acquire

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Duel (proposed v4, docs/SPEC_proposed_v4.md) decision rules: Appendix A ported line for
 * line, except the base, which [HowToSense] now computes (top-down proposed); the votes only
 * pick the neighbour's direction. Per-image filtering and fusion are the same rules as
 * v3cause's, so they are reused from [V3Cause] (perImage, fuse, iou). Pure Kotlin with no
 * Android types, so the JVM unit tests and an off-device replay run exactly the code the
 * phone runs.
 *
 * Boxes are [x0, y0, x1, y1] in detector-input pixels.
 */
object Duel {
    /** Nominal ISO ladder; 1600 = analog ceiling x digital residual. */
    val ISOS = intArrayOf(100, 200, 400, 800, 1600)
    /** 1/240, 1/120, 1/60, 1/30, 1/15 s. "One rung" = a neighbour in this list. */
    val SHUTTERS_US = intArrayOf(4167, 8333, 16667, 33333, 66667)
    /** IoU at which a car counts as caught in an image (spec 4.3). */
    const val SEEN_IOU = 0.3
    /** RAW sites read per box for a clipped fraction (fusion weights, records): enough for
     *  a fraction (standard error under 1%); reading up to 40,000 per box per image made the
     *  fusion ~15 ms a frame on the phone. */
    const val SAT_SITES = 4_096

    enum class Dir(val step: Int, val key: String) { SHORT(-1, "short"), LONG(+1, "long") }

    /**
     * Spec 4.3: for every fused output box (base time), was it caught in the base image and
     * in the neighbour image ([moveToNb] maps a base-time box to the neighbour's time)?
     * Neighbour only -> win, base only -> loss. Returns (w_t, l_t).
     */
    fun discord(
        fused: List<DoubleArray>, baseOut: List<V3Cause.D>, nbOut: List<V3Cause.D>,
        moveToNb: (DoubleArray) -> DoubleArray
    ): IntArray {
        var w = 0; var l = 0
        for (b in fused) {
            val hb = baseOut.any { V3Cause.iou(b, it.box) >= SEEN_IOU }
            val nb = moveToNb(b)
            val hn = nbOut.any { V3Cause.iou(nb, it.box) >= SEEN_IOU }
            if (hn && !hb) w++
            if (hb && !hn) l++
        }
        return intArrayOf(w, l)
    }

    /** P(p > 1/2) for p ~ Beta(w+1, l+1) = P(Binomial(w+l+1, 1/2) <= w); summed in log
     *  space so long stalemates (large w + l) stay finite. */
    fun pBetter(w: Int, l: Int): Double {
        val n = w + l + 1
        val ln2n = n * ln(2.0)
        var logC = 0.0
        var s = 0.0
        for (k in 0..w) {
            if (k > 0) logC += ln((n - k + 1).toDouble()) - ln(k.toDouble())
            s += exp(logC - ln2n)
        }
        return minOf(1.0, s)
    }

    /** Gamma(k, 1) for k >= 1 (Marsaglia & Tsang). */
    private fun gamma(k: Double, rng: java.util.Random): Double {
        require(k >= 1.0)
        val d = k - 1.0 / 3.0
        val c = 1.0 / sqrt(9.0 * d)
        while (true) {
            var x: Double
            var v: Double
            do { x = rng.nextGaussian(); v = 1.0 + c * x } while (v <= 0.0)
            v = v * v * v
            val u = rng.nextDouble()
            if (u < 1.0 - 0.0331 * x * x * x * x) return d * v
            if (ln(u) < 0.5 * x * x + d * (1.0 - v + ln(v))) return d * v
        }
    }

    /** Beta(a, b) variate, a, b >= 1. */
    fun betaVariate(a: Double, b: Double, rng: java.util.Random): Double {
        val x = gamma(a, rng)
        return x / (x + gamma(b, rng))
    }

    class Plan(val nb: Dir?, val reason: String, val draws: Map<Dir, Double>?)

    /**
     * The state of spec 4.1 and step 1 of 4.2, plus the votes of step 6. The caller assigns
     * the base ([baseG], [baseS]) from [HowToSense]. [every] = L1 (two images every frame);
     * otherwise L0 (two images after a trace on the previous base).
     */
    class Policy(val every: Boolean, var baseG: Int, var baseS: Int, seed: Long) {
        val w = IntArray(2)
        val l = IntArray(2)
        var prevTrace = false
        private val rng = java.util.Random(seed)

        /** Directions whose neighbour is still on the lattice. */
        fun dirs(): List<Dir> = Dir.values().filter { baseS + it.step in SHUTTERS_US.indices }

        /** Step 1: this frame's neighbour and why, from the previous frame's information only. */
        fun plan(): Plan {
            val ds = dirs()
            val reason = when {
                every -> "always"
                prevTrace -> "trace"
                else -> return Plan(null, "none", null)
            }
            val draws = LinkedHashMap<Dir, Double>()
            for (d in ds) draws[d] = betaVariate(w[d.ordinal] + 1.0, l[d.ordinal] + 1.0, rng)
            val pick = draws.entries.fold(null as Map.Entry<Dir, Double>?) { best, e ->
                if (best == null || e.value > best.value) e else best
            }!!.key
            return Plan(pick, reason, draws)
        }

        /** Step 6 after a two-image frame: the votes for [nb]'s direction. They are kept when
         *  the base moves. */
        fun afterCompare(nb: Dir?, wT: Int, lT: Int) {
            if (nb == null) return
            w[nb.ordinal] += wT
            l[nb.ordinal] += lT
        }

        fun pBetterNow(d: Dir): Double = pBetter(w[d.ordinal], l[d.ordinal])
    }
}
