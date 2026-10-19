package com.example.activeperception.acquire

import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Trains the Neural-AE baseline (hist_scalar) on the sos_cf_v1 counterfactual record with
 * the app's own feature code and trainer, so the weights load on the phone unchanged.
 * Not a unit test: run its main() on the JVM (it needs several GB of heap).
 *
 *     main(datasetDir, outDir[, cellsPerMoment = 25[, epochs = 60]])
 *
 * Samples: every counterfactual image (use = counterfactual; 25 cells per moment) of the
 * three lightings. Label (agreed 2026-10-20): the SAME moment's best cell — the record's
 * moments are ~1.2 s apart while the online frames are ~0.1 s apart, so "the next moment's
 * best" would ask the net for a scene 1.2 s ahead. Best = highest Signal.sumConfV3 (the
 * app's NAE supervision signal); ties go to the cell nearest the image's own exposure
 * (NaeTraining.bestNextCell's rule); moments with no vehicle anywhere give no samples.
 * Label = ln(e(best) / e(cell)), e = ISO/100 x shutter s, clamped to +-ln 10.
 * Validation: the last 20% of laps of each lighting (neighbouring moments are
 * near-duplicates, so a random split would flatter the validation loss).
 * Writes nae_hist_scalar_3x3.bin (the name the app loads) and report.json.
 */
object NaeTrainSosCf {
    private val ISOS = intArrayOf(100, 200, 400, 800, 1600)
    private val EXPS = intArrayOf(4167, 8333, 16667, 33333, 66667)
    private const val LN10 = 2.302585092994046

    private class Moment(val seq: String, val id: Int, val lap: Int,
                         val score: DoubleArray, val file: Array<File?>)

    private fun e(cell: Int) = NaeSnap.cellE(cell, ISOS, EXPS)

    private fun bestCellFor(m: Moment, cur: Int): Int {
        val top = m.score.maxOrNull()!!
        var pick = -1; var bestD = Double.MAX_VALUE
        for (c in 0 until 25) {
            if (m.score[c] < top - 1e-9) continue
            val d = abs(ln(e(c) / e(cur)))
            if (d < bestD) { bestD = d; pick = c }
        }
        return pick
    }

    /** javax.imageio is not on the Android unit-test compile classpath (android.jar stands in
     *  for the JDK there) but is in the JDK that runs this, so it is reached by reflection. */
    private val imageIoRead = Class.forName("javax.imageio.ImageIO").getMethod("read", File::class.java)

    private fun hist(f: File): FloatArray {
        val img = imageIoRead.invoke(null, f) ?: error("cannot read $f")
        val k = img.javaClass
        val w = k.getMethod("getWidth").invoke(img) as Int
        val h = k.getMethod("getHeight").invoke(img) as Int
        val px = k.getMethod("getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, IntArray::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(img, 0, 0, w, h, null, 0, w) as IntArray
        return NaeFeatures.multiScaleHist(ShinMetric.lumFromArgb(px, w, h), w, h)
    }

    /** Minimal CSV for the record's files (no quoted commas in them). */
    private fun csv(f: File): List<Map<String, String>> {
        val lines = f.readLines().filter { it.isNotBlank() }
        val head = lines[0].split(',')
        return lines.drop(1).map { l -> head.zip(l.split(',')).toMap() }
    }

    private fun loadSeq(root: File, seq: String): List<Moment> {
        val dir = File(root, "sequences/$seq")
        val laps = csv(File(dir, "moments.csv")).associate { it["moment"]!!.toInt() to it["lap"]!!.toInt() }
        val byId = HashMap<String, Pair<Int, Int>>()          // image_id -> (moment, cell)
        val files = HashMap<Int, Array<File?>>()
        for (r in csv(File(dir, "images.csv"))) {
            if (r["use"] != "counterfactual") continue
            val m = r["moment"]!!.toInt(); val c = r["cell"]!!.toInt()
            byId[r["image_id"]!!] = m to c
            files.getOrPut(m) { arrayOfNulls(25) }[c] = File(dir, r["file"]!!)
        }
        val scores = HashMap<Int, DoubleArray>()
        val idRe = Regex("\"image_id\":\\s*\"([^\"]+)\"")
        val boxRe = Regex("\\[([-0-9.eE]+),\\s*([-0-9.eE]+),\\s*([-0-9.eE]+),\\s*([-0-9.eE]+),\\s*([-0-9.eE]+),\\s*(\\d+)]")
        File(dir, "detections.jsonl").forEachLine { line ->
            val id = idRe.find(line)?.groupValues?.get(1) ?: return@forEachLine
            val (m, c) = byId[id] ?: return@forEachLine
            val dets = boxRe.findAll(line).map { g ->
                val v = g.groupValues
                Detection(floatArrayOf(v[1].toFloat(), v[2].toFloat(), v[3].toFloat(), v[4].toFloat()),
                    v[5].toFloat(), v[6].toInt())
            }.toList()
            scores.getOrPut(m) { DoubleArray(25) }[c] = Signal.sumConfV3(dets)
        }
        return files.keys.sorted().mapNotNull { m ->
            val f = files[m]!!
            if (f.any { it == null }) null
            else Moment(seq, m, laps[m] ?: 0, scores[m] ?: DoubleArray(25), f)
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args[0]); val out = File(args[1]).apply { mkdirs() }
        val cellsPerMoment = args.getOrNull(2)?.toInt() ?: 25
        val epochs = args.getOrNull(3)?.toInt() ?: 60
        val rng = java.util.Random(20261020L)
        val train = ArrayList<NaeTrainer.Sample>(); val held = ArrayList<NaeTrainer.Sample>()
        val heldMoments = ArrayList<Moment>()
        val report = StringBuilder("{\n  \"dataset\": \"${root.absolutePath.replace("\\", "/")}\",\n")
        report.append("  \"label\": \"same-moment best cell by Signal.sumConfV3, ties to the nearest exposure\",\n")
        report.append("  \"cells_per_moment\": $cellsPerMoment,\n  \"sequences\": {\n")
        val seqs = listOf("bright", "dim", "dark")
        for ((si, seq) in seqs.withIndex()) {
            val ms = loadSeq(root, seq)
            val laps = ms.map { it.lap }.distinct().sorted()
            val nHeld = maxOf(1, Math.round(laps.size * 0.2).toInt())
            val heldLaps = laps.takeLast(nHeld).toSet()
            var nTr = 0; var nVa = 0; var skipped = 0
            val t0 = System.nanoTime()
            for (m in ms) {
                if (m.score.maxOrNull()!! <= 0.0) { skipped++; continue }
                val isHeld = m.lap in heldLaps
                if (isHeld) heldMoments += m
                val cells = (0 until 25).toMutableList()
                // Held-out moments keep every cell so the offline check sees every start.
                if (!isHeld && cellsPerMoment < 25) { cells.shuffle(rng); cells.subList(cellsPerMoment, 25).clear() }
                for (c in cells) {
                    val b = bestCellFor(m, c)
                    val y = ln(e(b) / e(c)).coerceIn(-LN10, LN10)
                    val s = NaeTrainer.Sample(hist(m.file[c]!!), y)
                    if (isHeld) { held += s; nVa++ } else { train += s; nTr++ }
                }
            }
            println("$seq: ${ms.size} moments, ${skipped} without vehicles, laps ${laps.size} " +
                "(held out ${heldLaps.sorted()}), samples train $nTr val $nVa, " +
                "%.0f s".format((System.nanoTime() - t0) / 1e9))
            report.append("    \"$seq\": {\"moments\": ${ms.size}, \"no_vehicle_moments\": $skipped, " +
                "\"laps\": ${laps.size}, \"held_out_laps\": ${heldLaps.sorted()}, " +
                "\"train_samples\": $nTr, \"val_samples\": $nVa}${if (si < seqs.size - 1) "," else ""}\n")
        }
        report.append("  },\n")
        val zeroMse = held.sumOf { it.targetLogU * it.targetLogU } / held.size
        println("train ${train.size}, val ${held.size}; val MSE of 'no change' = %.4f".format(zeroMse))
        val losses = StringBuilder()
        val t1 = System.nanoTime()
        val bin = NaeTrainer(42L).train(train, epochs = epochs, heldOut = held) { ep, tr, va ->
            println("epoch $ep train %.4f val %.4f (%.0f s)".format(tr, va, (System.nanoTime() - t1) / 1e9))
            losses.append("${if (losses.isEmpty()) "" else ", "}[$ep, %.5f, %.5f]".format(tr, va))
        }
        File(out, "nae_hist_scalar_3x3.bin").writeBytes(bin)

        // Offline check on the held-out moments: from every start cell, one predicted move
        // (no EMA), split at T_MAX = 15 ms as on the phone; sumconf of where it lands.
        val net = NeuralAeNet(bin)
        var sumLand = 0.0; var sumStart = 0.0; var sumBest = 0.0; var sumFixed = 0.0; var n = 0
        var sqErr = 0.0
        for (m in heldMoments) for (c in 0 until 25) {
            val pred = net.predictLogU(hist(m.file[c]!!))
            val land = NaeSnap.splitAndSnap(e(c) * exp(pred), ISOS, EXPS, 15_000)
            sumLand += m.score[land]; sumStart += m.score[c]; sumBest += m.score.maxOrNull()!!
            sumFixed += m.score[20]
            val y = ln(e(bestCellFor(m, c)) / e(c)).coerceIn(-LN10, LN10)
            sqErr += (pred - y) * (pred - y); n++
        }
        val check = "{\"starts\": $n, \"val_mse\": %.5f, \"val_mse_no_change\": %.5f, ".format(sqErr / n, zeroMse) +
            "\"sumconf_start\": %.4f, \"sumconf_after_one_move\": %.4f, \"sumconf_best\": %.4f, \"sumconf_fixed_1600_240\": %.4f}"
                .format(sumStart / n, sumLand / n, sumBest / n, sumFixed / n)
        println("held-out check: $check")
        report.append("  \"epochs\": [$losses],\n  \"held_out_check\": $check,\n")
        report.append("  \"weights_bytes\": ${bin.size}\n}\n")
        File(out, "report.json").writeText(report.toString())
        println("wrote ${File(out, "nae_hist_scalar_3x3.bin")} and report.json")
    }
}
