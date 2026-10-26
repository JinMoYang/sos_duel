package com.example.activeperception.acquire

import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Measures How-to-Sense's detector tolerance curve on the sos_cf_v1 counterfactual record,
 * without labels, with the app's own feature code ([HowToSense]). Not a unit test: run its
 * main() on the JVM.
 *
 *     main(datasetDir, outCurveFile[, bootstrap = 300])
 *
 * Recipe (agreed 2026-10-26):
 * - Exposure groups of one moment, captured apart: g240 (the 1/240 burst), g120 (the 1/120
 *   burst), g60 (the 1/60 burst; its 1/30 and 1/15 sums too) and real (the real_check shot).
 *   The five ISO images of a group are one RAW at different digital ratios.
 * - A car = a cluster of vehicle boxes (conf >= 0.5) across groups. A sample is one car in one
 *   of the 25 counterfactual images; it counts only if the car is held by >= 2 OTHER groups
 *   (leave-one-group-out: the image's own group never vouches for the car).
 * - Detected = the image has a vehicle box with conf >= 0.25 matching the car's box of that
 *   group (or of the nearest group in time when the group has none), by IoU >= 0.3 or by
 *   centre distance <= 0.6 x long side with similar size (the groups are up to ~0.2 s apart).
 * - Features, as [HowToSense] defines them: brightness = mean of min(L, 1) over the box, clip =
 *   share of box pixels with L >= 0.98 (L = linear channel mean of the image's 640 x 480
 *   picture), blur = speed x shutter / box long side; speed = the median of [HowToSense.speed]
 *   over the grid cells under the box, between the g240 and g120 first frames at the base ISO.
 * - p and var per blur x brightness bin from samples with clip < 0.05; bin lower edges at the
 *   axis sextiles; var by bootstrap over moments (samples of one moment are not independent);
 *   an empty bin takes its nearest non-empty bin's p with var 0.25. clip_factor per clip bin
 *   = observed detections / those expected from p (indirect standardisation).
 */
object ToleranceCurveSosCf {
    private val imageIoRead = Class.forName("javax.imageio.ImageIO").getMethod("read", File::class.java)
    private val VEH = setOf(1, 2, 3, 5, 7)
    private const val TOP = 80                         // letterbox rows above the picture
    private val CLIP_LO = doubleArrayOf(0.0, 0.05, 0.2, 0.5, 0.8)
    /** Pyramid levels for the record's speed pairs (1/240 -> 1/120 first frames, ~67 ms apart,
     *  different exposures). Checked against the gyro 2026-10-26, cars in turns (~470-530 px/s
     *  of rotation): 3 levels read ~520-530 px/s, 5 levels ~600-790 (too high). The app's own
     *  pairs (equal exposures, ~130 ms) need 5 ([HowToSense.FLOW_LEVELS]); each was checked
     *  against the same reference, the gyro, rather than forced to share a depth. */
    private const val RECORD_FLOW_LEVELS = 3

    private class Img(val id: String, val cell: Int, val iso: Int, val shutterUs: Int,
                      val group: String, val ts: Long, val file: File)
    private class Box(val b: DoubleArray, val conf: Double)
    private class Sample(val unit: Int, val blur: Double, val bright: Double, val clip: Double, val hit: Boolean)

    private fun picture(f: File): DoubleArray {
        val img = imageIoRead.invoke(null, f) ?: error("cannot read $f")
        val ip = Int::class.javaPrimitiveType
        val px = img.javaClass.getMethod("getRGB", ip, ip, ip, ip, IntArray::class.java, ip, ip)
            .invoke(img, 0, TOP, HowToSense.W, HowToSense.H, null, 0, HowToSense.W) as IntArray
        return HowToSense.picture(px)
    }

    private fun csv(f: File): List<Map<String, String>> {
        val lines = f.readLines().filter { it.isNotBlank() }
        val head = lines[0].split(',')
        return lines.drop(1).map { l -> head.zip(l.split(',')).toMap() }
    }

    private fun side(b: DoubleArray) = max(b[2] - b[0], b[3] - b[1])

    /** Same car across groups: overlap, or a nearby centre with a similar size. */
    private fun same(a: DoubleArray, b: DoubleArray): Boolean {
        if (V3Cause.iou(a, b) >= 0.3) return true
        val sa = side(a); val sb = side(b)
        if (sa <= 0 || sb <= 0 || sa / sb > 1.5 || sb / sa > 1.5) return false
        val d = hypot((a[0] + a[2] - b[0] - b[2]) / 2, (a[1] + a[3] - b[1] - b[3]) / 2)
        return d <= 0.6 * max(sa, sb)
    }

    private fun nms(boxes: List<Box>): List<Box> {
        val keep = ArrayList<Box>()
        for (x in boxes.sortedByDescending { it.conf }) if (keep.all { V3Cause.iou(it.b, x.b) < 0.5 }) keep += x
        return keep
    }

    private fun boxStats(pic: DoubleArray, b: DoubleArray): DoubleArray {
        val x0 = max(0, b[0].toInt()); val y0 = max(0, b[1].toInt())
        val x1 = min(HowToSense.W, kotlin.math.ceil(b[2]).toInt()); val y1 = min(HowToSense.H, kotlin.math.ceil(b[3]).toInt())
        var s = 0.0; var c = 0.0; var n = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            val v = pic[y * HowToSense.W + x]; s += min(v, 1.0); if (v >= HowToSense.CLIP) c += 1.0; n++
        }
        return if (n == 0) doubleArrayOf(Double.NaN, Double.NaN) else doubleArrayOf(s / n, c / n)
    }

    private fun cellsUnder(b: DoubleArray): List<Int> {
        val out = ArrayList<Int>()
        for (gy in max(0, (b[1] / HowToSense.CS).toInt()) until min(HowToSense.GY, (b[3] / HowToSense.CS).toInt() + 1))
            for (gx in max(0, (b[0] / HowToSense.CS).toInt()) until min(HowToSense.GX, (b[2] / HowToSense.CS).toInt() + 1))
                out += gy * HowToSense.GX + gx
        return out
    }

    private fun sequence(root: File, seq: String, unitBase: Int, out: MutableList<Sample>): IntArray {
        val dir = File(root, "sequences/$seq")
        val byMoment = HashMap<Int, MutableList<Img>>()
        val baseIso = HashMap<Int, Int>()
        for (r in csv(File(dir, "images.csv"))) {
            val use = r["use"]
            if (use != "counterfactual" && use != "real_check") continue
            val m = r["moment"]!!.toInt()
            val group = if (use == "real_check") "real" else when (r["source_shutter_us"]!!.toInt()) {
                4167 -> "g240"; 8333 -> "g120"; else -> "g60" }
            byMoment.getOrPut(m) { ArrayList() } += Img(r["image_id"]!!, r["cell"]!!.toInt(), r["iso"]!!.toInt(),
                r["shutter_us"]!!.toInt(), group, r["sensor_ts_ns"]!!.toLong(), File(dir, r["file"]!!))
            if (use == "counterfactual") baseIso[m] = r["base_iso"]!!.toInt()
        }
        val dets = HashMap<String, List<Box>>()
        val idRe = Regex("\"image_id\":\\s*\"([^\"]+)\"")
        val boxRe = Regex("\\[([-0-9.eE]+),\\s*([-0-9.eE]+),\\s*([-0-9.eE]+),\\s*([-0-9.eE]+),\\s*([-0-9.eE]+),\\s*(\\d+)]")
        File(dir, "detections.jsonl").forEachLine { line ->
            val id = idRe.find(line)?.groupValues?.get(1) ?: return@forEachLine
            dets[id] = boxRe.findAll(line).mapNotNull { g ->
                val v = g.groupValues
                if (v[6].toInt() !in VEH) null
                else Box(doubleArrayOf(v[1].toDouble(), v[2].toDouble() - TOP, v[3].toDouble(), v[4].toDouble() - TOP), v[5].toDouble())
            }.toList()
        }
        val yawOf = csv(File(dir, "moments.csv")).associate { it["moment"]!! to abs(it["yaw_rate_rad_s"]!!.toDouble()) }
        val order = listOf("g240", "g120", "g60", "real")
        var moments = 0; var cars = 0; var used = 0
        for ((m, imgs) in byMoment.toSortedMap()) {
            // Strong boxes per group, then clusters across groups.
            val strong = order.associateWith { g -> nms(imgs.filter { it.group == g }.flatMap { dets[it.id].orEmpty() }.filter { it.conf >= 0.5 }) }
            val clusters = ArrayList<HashMap<String, DoubleArray>>()
            for (g in order) for (x in strong[g]!!) {
                val hit = clusters.firstOrNull { c -> !c.containsKey(g) && c.values.any { same(it, x.b) } }
                if (hit != null) hit[g] = x.b else clusters += hashMapOf(g to x.b)
            }
            val certain = clusters.filter { it.size >= 2 }
            if (certain.isEmpty()) continue
            moments++; cars += certain.size
            // Speed between the base-ISO first frames of the 1/240 and 1/120 bursts.
            val bi = baseIso[m] ?: continue
            val a = imgs.firstOrNull { it.group == "g240" && it.iso == bi && it.shutterUs == 4167 }
            val b = imgs.firstOrNull { it.group == "g120" && it.iso == bi && it.shutterUs == 8333 }
            if (a == null || b == null || b.ts <= a.ts) continue
            val need = BooleanArray(HowToSense.N)
            for (c in certain) for (k in cellsUnder(c["g240"] ?: c["g120"] ?: c.values.first())) need[k] = true
            val (speed, _) = HowToSense.speed(picture(a.file), 2.0, picture(b.file), (b.ts - a.ts) / 1e9, need,
                levels = RECORD_FLOW_LEVELS)
            val carSpeed = certain.map { c -> HowToSense.median(cellsUnder(c["g240"] ?: c["g120"] ?: c.values.first()).map { speed[it] }) }
            speeds += carSpeed
            val yawM = yawOf[m.toString()] ?: Double.NaN
            for (v in carSpeed) speedYaw += doubleArrayOf(yawM, v)
            if (speedsOnly) continue
            val pics = HashMap<String, DoubleArray>()
            for (img in imgs) {
                if (img.group == "real") continue
                for ((ci, c) in certain.withIndex()) {
                    if (c.keys.count { it != img.group } < 2) continue          // leave the image's own group out
                    val ref = c[img.group] ?: run {
                        val i = order.indexOf(img.group)
                        c.entries.minByOrNull { abs(order.indexOf(it.key) - i) }!!.value
                    }
                    val hit = dets[img.id].orEmpty().any { it.conf >= 0.25 && same(it.b, ref) }
                    val pic = pics.getOrPut(img.id) { picture(img.file) }
                    val st = boxStats(pic, ref)
                    if (st[0].isNaN()) continue
                    val blur = carSpeed[ci] * (img.shutterUs / 1e6) / max(side(ref), 1.0)
                    out += Sample(unitBase + m, blur, st[0], st[1], hit)
                    used++
                }
            }
        }
        println("$seq: ${byMoment.size} moments, $moments with certain cars, $cars cars, $used samples")
        return intArrayOf(moments, cars, used)
    }

    private fun sextiles(v: List<Double>): DoubleArray {
        val s = v.sorted()
        val e = (0 until 6).map { s[(it * s.size / 6).coerceAtMost(s.size - 1)] }.toMutableList()
        e[0] = 0.0                                     // values below the first sextile join bin 0
        return e.distinct().toDoubleArray()
    }

    /** Every certain car's speed (picture px/s), and whether to stop there (main(dir, "--speeds")). */
    private val speeds = ArrayList<Double>()
    private val speedYaw = ArrayList<DoubleArray>()
    private var speedsOnly = false

    /**
     * Is the speed flow right at the app's spacing? Per moment, the speed over the whole picture
     * from the 1/240 to the 1/120 first frame (~67 ms apart) and from the 1/240 to the 1/60 first
     * frame (~134 ms, the app's base spacing), at [levelsList] pyramid depths. A flow that keeps
     * up gives the same speed at both spacings (the moments are short).
     */
    private fun rangeCheck(root: File, levelsList: List<Int>) {
        val ratios = levelsList.associateWith { ArrayList<Double>() }
        val med67 = levelsList.associateWith { ArrayList<Double>() }
        for (seq in listOf("bright", "dim", "dark")) {
            val dir = File(root, "sequences/$seq")
            val rows = csv(File(dir, "images.csv")).filter { it["use"] == "counterfactual" && it["made_by"] == "physical" }
            for ((_, rs) in rows.groupBy { it["moment"] }) {
                fun pick(us: Int) = rs.firstOrNull { it["shutter_us"]!!.toInt() == us }
                val a = pick(4167) ?: continue; val b = pick(8333) ?: continue; val c = pick(16667) ?: continue
                val ta = a["sensor_ts_ns"]!!.toLong(); val tb = b["sensor_ts_ns"]!!.toLong(); val tc = c["sensor_ts_ns"]!!.toLong()
                if (tb <= ta || tc <= tb) continue
                val pa = picture(File(dir, a["file"]!!)); val pb = picture(File(dir, b["file"]!!)); val pc = picture(File(dir, c["file"]!!))
                val all = BooleanArray(HowToSense.N) { true }
                for (lv in levelsList) {
                    val s1 = HowToSense.median(HowToSense.speed(pa, 2.0, pb, (tb - ta) / 1e9, all, levels = lv).first.toList())
                    val s2 = HowToSense.median(HowToSense.speed(pa, 4.0, pc, (tc - ta) / 1e9, all, levels = lv).first.toList())
                    med67[lv]!! += s1
                    if (s1 > 150) ratios[lv]!! += s2 / s1          // moving moments only
                }
            }
            println("$seq done")
        }
        for (lv in levelsList) {
            val r = ratios[lv]!!.sorted(); val m = med67[lv]!!.sorted()
            fun q(v: List<Double>, f: Double) = v[(f * (v.size - 1)).toInt()]
            println("levels $lv: picture speed at 67 ms p50 %.0f p90 %.0f px/s | 134 ms / 67 ms on %d moving moments: p10 %.2f p50 %.2f p90 %.2f".format(
                q(m, 0.5), q(m, 0.9), r.size, q(r, 0.1), q(r, 0.5), q(r, 0.9)))
        }
    }

    /**
     * The speed flow at the app's own spacing, against the gyro: consecutive Duel base pictures
     * of the main sessions (duel_main_v1; both at ISO 1600, 1/240 s, ~130 ms apart), the flow's
     * median speed over the upper half of the picture (far scene, where turning dominates)
     * binned by the yaw rate. A flow that keeps up grows with the yaw rate; one that loses the
     * content flattens. [fPx] = expected focal length in picture px, for reference only.
     */
    private fun gyroCheck(root: File, levelsList: List<Int>, useCars: Boolean, fPx: Double = 425.0) {
        val edges = doubleArrayOf(0.0, 0.3, 0.6, 0.9, 1.2, 1.6)
        val bins = levelsList.associateWith { Array(edges.size) { ArrayList<Double>() } }
        val yaws = Array(edges.size) { ArrayList<Double>() }
        val upper = BooleanArray(HowToSense.N) { it / HowToSense.GX < HowToSense.GY / 2 }
        val runs = root.resolve("sessions").listFiles()!!.filter { it.isDirectory && !it.name.startsWith("dim_repeat") }
            .flatMap { s -> s.listFiles()!!.filter { it.name.startsWith("run_limo_duel_") } }
        for (run in runs) {
            val fr = csv(File(run, "frames.csv")).associateBy { it["idx"]!!.toInt() }
            val df = csv(File(run, "duel_frames.csv")).associateBy { it["idx"]!!.toInt() }
            // Grid cells under each frame's output cars (oriented-image px -> picture px).
            val carCells = HashMap<Int, BooleanArray>()
            val xyRe = Regex(""""xyxy":\[([-0-9.eE]+),([-0-9.eE]+),([-0-9.eE]+),([-0-9.eE]+)\]""")
            val frameRe = Regex(""""frame":(\d+)""")
            File(run, "dets.jsonl").forEachLine { line ->
                val k = frameRe.find(line)?.groupValues?.get(1)?.toInt() ?: return@forEachLine
                val detsPart = line.substringAfter("\"dets\":").substringBefore("\"dropped\"")
                val need = BooleanArray(HowToSense.N)
                for (g in xyRe.findAll(detsPart)) {
                    val v = g.groupValues.drop(1).map { it.toDouble() * 640.0 / 1020.0 }
                    for (c in cellsUnder(doubleArrayOf(v[0], v[1], v[2], v[3]))) need[c] = true
                }
                if (need.any { it }) carCells[k] = need
            }
            var prev: Pair<Int, DoubleArray>? = null
            for (k in fr.keys.sorted()) {
                val d = df[k] ?: continue
                val f = File(run, "img/frame_%04d_base.jpg".format(k))
                if (d["base_iso"] != "1600" || d["base_shutter_us"] != "4167" || !f.exists()) { prev = null; continue }
                val pic = picture(f)
                val p = prev
                prev = k to pic
                if (p == null || p.first != k - 1) continue
                val ta = fr[k - 1]!!["ts"]!!.toLong(); val tb = fr[k]!!["ts"]!!.toLong()
                if (tb <= ta) continue
                val yaw = (abs(fr[k - 1]!!["yaw_rate"]!!.toDouble()) + abs(fr[k]!!["yaw_rate"]!!.toDouble())) / 2
                val bi = HowToSense.Curve.binOf(edges, yaw)
                yaws[bi] += yaw
                val cells = if (useCars) carCells[k] ?: run { yaws[bi].removeAt(yaws[bi].size - 1); null } else upper
                if (cells == null) continue
                for (lv in levelsList) {
                    val (v, _) = HowToSense.speed(p.second, 1.0, pic, (tb - ta) / 1e9, cells, levels = lv)
                    bins[lv]!![bi] += HowToSense.median((0 until HowToSense.N).filter { cells[it] }.map { v[it] })
                }
            }
            println("${run.parentFile.name}/${run.name} done")
        }
        println("yaw rate bin | pairs | median yaw x f(${fPx.toInt()} px) | " + levelsList.joinToString(" | ") { "flow, $it levels" })
        for (i in edges.indices) {
            if (yaws[i].isEmpty()) continue
            val y = HowToSense.median(yaws[i])
            println("  >= %.1f rad/s | %5d | %6.0f px/s | ".format(edges[i], yaws[i].size, y * fPx) +
                levelsList.joinToString(" | ") { "%6.0f px/s".format(HowToSense.median(bins[it]!![i])) })
        }
    }

    /** [gyroCheck] on the record's own pairs (the curve's speeds): 1/240 -> 1/120 first frames at
     *  the base ISO, ~67 ms apart, binned by the moment's yaw rate (moments.csv). */
    private fun gyroCheckRecord(root: File, fPx: Double = 425.0) {
        val edges = doubleArrayOf(0.0, 0.3, 0.6, 0.9, 1.2, 1.6)
        val bins = Array(edges.size) { ArrayList<Double>() }; val yaws = Array(edges.size) { ArrayList<Double>() }
        val upper = BooleanArray(HowToSense.N) { it / HowToSense.GX < HowToSense.GY / 2 }
        for (seq in listOf("bright", "dim", "dark")) {
            val dir = File(root, "sequences/$seq")
            val yawOf = csv(File(dir, "moments.csv")).associate { it["moment"]!! to abs(it["yaw_rate_rad_s"]!!.toDouble()) }
            val rows = csv(File(dir, "images.csv")).filter { it["use"] == "counterfactual" && it["made_by"] == "physical" }
            for ((m, rs) in rows.groupBy { it["moment"]!! }) {
                val a = rs.firstOrNull { it["shutter_us"] == "4167" } ?: continue
                val b = rs.firstOrNull { it["shutter_us"] == "8333" } ?: continue
                val dt = (b["sensor_ts_ns"]!!.toLong() - a["sensor_ts_ns"]!!.toLong()) / 1e9
                val yaw = yawOf[m] ?: continue
                if (dt <= 0) continue
                val (v, _) = HowToSense.speed(picture(File(dir, a["file"]!!)), 2.0, picture(File(dir, b["file"]!!)), dt, upper)
                val i = HowToSense.Curve.binOf(edges, yaw)
                yaws[i] += yaw; bins[i] += HowToSense.median((0 until HowToSense.N).filter { upper[it] }.map { v[it] })
            }
        }
        println("record pairs (~67 ms): yaw bin | pairs | yaw x f | flow (default levels)")
        for (i in edges.indices) if (yaws[i].isNotEmpty())
            println("  >= %.1f | %4d | %5.0f | %5.0f".format(edges[i], yaws[i].size, HowToSense.median(yaws[i]) * fPx, HowToSense.median(bins[i])))
    }

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.getOrNull(1) == "--gyrorecord") { gyroCheckRecord(File(args[0])); return }
        if (args.getOrNull(1) == "--rangecheck") { rangeCheck(File(args[0]), listOf(3, 5)); return }
        if (args.getOrNull(1) == "--gyrocheck") { gyroCheck(File(args[0]), listOf(3, 5), args.getOrNull(2) == "cars"); return }
        if (args.getOrNull(1) == "--speeds") {
            speedsOnly = true
            listOf("bright", "dim", "dark").forEachIndexed { i, s -> sequence(File(args[0]), s, i * 10_000, ArrayList()) }
            val s = speeds.sorted()
            fun q(f: Double) = s[(f * (s.size - 1)).toInt()]
            println("car speeds (px/s, ${s.size} cars): p10 %.0f  p50 %.0f  p90 %.0f  p99 %.0f  max %.0f".format(q(0.1), q(0.5), q(0.9), q(0.99), s.last()))
            for (t in listOf(100.0, 250.0, 400.0)) println("  share above $t px/s: %.1f%%".format(100.0 * s.count { it > t } / s.size))
            val edges = doubleArrayOf(0.0, 0.3, 0.6, 0.9, 1.2, 1.6)
            println("car-cell speed by yaw rate: bin | cars | yaw x 425 px | car flow")
            for (i in edges.indices) {
                val inBin = speedYaw.filter { !it[0].isNaN() && HowToSense.Curve.binOf(edges, it[0]) == i }
                if (inBin.isEmpty()) continue
                println("  >= %.1f | %4d | %5.0f | %5.0f".format(edges[i], inBin.size, HowToSense.median(inBin.map { it[0] }) * 425, HowToSense.median(inBin.map { it[1] })))
            }
            return
        }
        val root = File(args[0]); val outFile = File(args[1])
        val nBoot = args.getOrNull(2)?.toInt() ?: 300
        val samples = ArrayList<Sample>()
        val counts = listOf("bright", "dim", "dark").mapIndexed { i, s -> s to sequence(root, s, i * 10_000, samples) }
        val low = samples.filter { it.clip < CLIP_LO[1] }
        val blurLo = sextiles(low.map { it.blur }); val brightLo = sextiles(low.map { it.bright })
        val nb = blurLo.size; val nd = brightLo.size
        fun bin(s: Sample) = HowToSense.Curve.binOf(blurLo, s.blur) * nd + HowToSense.Curve.binOf(brightLo, s.bright)
        fun pOf(ss: List<Sample>): Pair<DoubleArray, IntArray> {
            val hit = DoubleArray(nb * nd); val n = IntArray(nb * nd)
            for (s in ss) { val i = bin(s); n[i]++; if (s.hit) hit[i] += 1.0 }
            return DoubleArray(nb * nd) { if (n[it] > 0) hit[it] / n[it] else Double.NaN } to n
        }
        val (p0, n0) = pOf(low)
        // Bootstrap over moments.
        val units = low.groupBy { it.unit }.values.toList()
        val rng = java.util.Random(20261026L)
        val sum = DoubleArray(nb * nd); val sq = DoubleArray(nb * nd); val cnt = IntArray(nb * nd)
        repeat(nBoot) {
            val ss = ArrayList<Sample>(low.size)
            repeat(units.size) { ss += units[rng.nextInt(units.size)] }
            val (pb, _) = pOf(ss)
            for (i in pb.indices) if (!pb[i].isNaN()) { sum[i] += pb[i]; sq[i] += pb[i] * pb[i]; cnt[i]++ }
        }
        val varr = DoubleArray(nb * nd) { if (cnt[it] > 1) max(0.0, (sq[it] - sum[it] * sum[it] / cnt[it]) / (cnt[it] - 1)) else 0.25 }
        // Empty bins: nearest non-empty bin's p, variance 0.25.
        val p = p0.copyOf()
        for (i in p.indices) if (p[i].isNaN()) {
            val (bi, di) = i / nd to i % nd
            val j = p0.indices.filter { !p0[it].isNaN() }.minByOrNull { abs(it / nd - bi) * 10 + abs(it % nd - di) }
            p[i] = if (j != null) p0[j] else 0.5; varr[i] = 0.25
        }
        // Clip factor by indirect standardisation.
        val cf = DoubleArray(CLIP_LO.size) { s ->
            val ss = samples.filter { HowToSense.Curve.binOf(CLIP_LO, it.clip) == s }
            val exp = ss.sumOf { p[bin(it)] }
            if (exp > 0) ss.count { it.hit } / exp else 1.0
        }
        val nClip = IntArray(CLIP_LO.size) { s -> samples.count { HowToSense.Curve.binOf(CLIP_LO, it.clip) == s } }
        fun row(v: DoubleArray) = v.joinToString(" ") { "%.6g".format(it) }
        val text = buildString {
            appendLine("# How-to-Sense tolerance curve measured on sos_cf_v1 (Galaxy S26 Ultra, Limo track, 2026-10-16 record)")
            appendLine("# by ToleranceCurveSosCf (2026-10-26): leave-one-group-out certain cars, detection = conf >= 0.25,")
            appendLine("# HowToSense features (box brightness, clip share, blur = speed x shutter / long side); p, var from clip < 0.05;")
            appendLine("# var by bootstrap over moments ($nBoot); empty bins take the nearest bin's p with var 0.25.")
            appendLine("# speeds: HowToSense.speed with $RECORD_FLOW_LEVELS pyramid levels on the 1/240 -> 1/120 first frames (gyro-checked).")
            appendLine("# samples ${samples.size} (clip < 0.05: ${low.size}); " + counts.joinToString("; ") { (s, c) -> "$s ${c[0]} moments ${c[1]} cars ${c[2]} samples" })
            appendLine("# n per bin (blur-major): ${n0.joinToString(" ")}")
            appendLine("# n per clip bin: ${nClip.joinToString(" ")}")
            appendLine("blur_lo ${row(blurLo)}")
            appendLine("bright_lo ${row(brightLo)}")
            appendLine("clip_lo ${row(CLIP_LO)}")
            appendLine("p ${row(p)}")
            appendLine("var ${row(varr)}")
            appendLine("clip_factor ${row(cf)}")
        }
        outFile.parentFile?.mkdirs()
        outFile.writeText(text)
        HowToSense.Curve.parse(text)                                   // the app must be able to read it
        println(text)
        println("p grid (rows = blur bins from ${blurLo.joinToString { "%.3g".format(it) }}; cols = brightness bins from ${brightLo.joinToString { "%.3g".format(it) }}):")
        for (b in 0 until nb) println("  " + (0 until nd).joinToString("  ") { d -> "%.2f(%4d)".format(p[b * nd + d], n0[b * nd + d]) })
    }
}
