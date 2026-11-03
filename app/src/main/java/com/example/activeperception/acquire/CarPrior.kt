package com.example.activeperception.acquire

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The car prior of the 2026-11-03 proposed method ([com.example.activeperception.MeasurementController.runSense]):
 * per How-to-Sense grid cell, the cars an output holds there. Every output box is one car, its 1
 * split over the cells it covers by overlap area / box area, so a big and a small car weigh the
 * same. After F outputs, cell k's prior = (count_k + 2 x mean) / (F + 2) with mean = (total
 * count + 1) / (F x [HowToSense.N] + 2): every cell starts at 1/2 and moves to its own rate as
 * outputs add up. Only the phone's own output boxes are counted (the app calls no server), so a
 * record replays the same choices. [HowToSense.ObjectMap] (the area-weighted map of runAddl and
 * Duel2) is unchanged.
 */
class CarPrior {
    private val count = DoubleArray(HowToSense.N)
    private val sides = Array(HowToSense.N) { ArrayList<Double>() }
    private val all = ArrayList<Double>()

    /** Outputs added so far. */
    var frames = 0
        private set

    /** One output's boxes, [x0, y0, x1, y1] in picture px. An output without boxes still counts. */
    fun addOutput(boxes: List<DoubleArray>) {
        val cs = HowToSense.CS.toDouble()
        for (b in boxes) {
            val area = (b[2] - b[0]) * (b[3] - b[1])
            if (area <= 0.0) continue
            val side = max(b[2] - b[0], b[3] - b[1])
            all.add(side)
            for (y in max(floor(b[1] / cs).toInt(), 0) until min(floor(b[3] / cs).toInt() + 1, HowToSense.GY)) {
                for (x in max(floor(b[0] / cs).toInt(), 0) until min(floor(b[2] / cs).toInt() + 1, HowToSense.GX)) {
                    val k = y * HowToSense.GX + x
                    count[k] += max(0.0, min(b[2], (x + 1) * cs) - max(b[0], x * cs)) *
                        max(0.0, min(b[3], (y + 1) * cs) - max(b[1], y * cs)) / area
                    sides[k].add(side)
                }
            }
        }
        frames++
    }

    /** Expected cars per output in each grid cell (the weights [HowToSense.choose] takes). */
    fun prior(): DoubleArray {
        val mean = (count.sum() + 1.0) / (frames.toDouble() * HowToSense.N + 2.0)
        return DoubleArray(HowToSense.N) { (count[it] + 2.0 * mean) / (frames + 2.0) }
    }

    /** Typical car size per grid cell: median long side of the boxes touching it, else of all
     *  boxes, else [HowToSense.FIRST_SIZE] (as [HowToSense.ObjectMap.sizes]). */
    fun sizes(): DoubleArray {
        val med = if (all.isEmpty()) HowToSense.FIRST_SIZE else HowToSense.median(all)
        return DoubleArray(HowToSense.N) { if (sides[it].isEmpty()) med else HowToSense.median(sides[it]) }
    }
}
