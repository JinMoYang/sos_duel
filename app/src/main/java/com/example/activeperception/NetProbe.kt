package com.example.activeperception

import android.os.SystemClock
import android.util.Log
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The network record while driving (app spec 2026-11-03 §2.2): every [periodMs] one POST to the
 * offload server's /detect, a small crop and the whole frame in turn, one at a time over one
 * keep-alive connection (the OkHttp setup of [OffloadClient]), so a replay can take each send's
 * arrival time from the network of that moment. The two images are fixed bytes (assets
 * net_probe_q16.jpg 4,694 B and net_probe_full.jpg 40,246 B: the 2026-11-02 probe's 160 x 160
 * centre crop and 640 x 640 frame of a 10-29 Limo output, JPEG 85), so their size never follows
 * the scene. It runs beside any method and never touches the camera or the detector.
 *
 * Writes net_probe.csv (one row per send; X-Frame-Id net-<name>-<i> for the server log) and
 * net_probe_meta.json (server, period, sizes, the /health answer at the start) into the run dir.
 */
class NetProbe(
    private val serverUrl: String,
    logDir: File,
    private val small: ByteArray,
    private val full: ByteArray,
    private val periodMs: Long = 1000L,
    private val onStatus: (String) -> Unit = {}
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(4, 5, TimeUnit.MINUTES))
        .build()
    private val octet = "application/octet-stream".toMediaType()
    private val csvFile = File(logDir, "net_probe.csv")
    private val metaFile = File(logDir, "net_probe_meta.json")
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread({ loop() }, "net-probe").apply { priority = Thread.MIN_PRIORITY; start() }
    }

    /** Lets the send in flight finish (up to 3 s), then interrupts. */
    fun stop() {
        running = false
        thread?.let { t ->
            t.join(3000)
            if (t.isAlive) { t.interrupt(); t.join(1000) }
        }
    }

    private fun loop() {
        val health = health()
        val ok = runCatching { JSONObject(health.optString("body")).optBoolean("ok") }.getOrDefault(false)
        onStatus(if (ok) "net probe: server ok" else "net probe: server NOT ok (${health.optString("error", health.optString("body"))})")
        runCatching {
            metaFile.writeText(JSONObject().put("server_url", serverUrl).put("period_ms", periodMs)
                .put("order", "small, full, small, ... (i even = small)")
                .put("small", JSONObject().put("asset", "net_probe_q16.jpg").put("bytes", small.size).put("px", "160x160"))
                .put("full", JSONObject().put("asset", "net_probe_full.jpg").put("bytes", full.size).put("px", "640x640"))
                .put("roundtrip", "send to the reply's headers (as OffloadClient), System.nanoTime")
                .put("health", health).put("health_ok", ok).toString(2))
            csvFile.writeText("name,i,bytes,t_send_ms,t_reply_ms,roundtrip_ms,server_ms,infer_ms,http,ok\n")
        }
        var i = 0
        var next = SystemClock.elapsedRealtime()
        while (running) {
            val wait = next - SystemClock.elapsedRealtime()
            if (wait > 0) try { Thread.sleep(wait) } catch (_: InterruptedException) { break }
            if (!running) break
            // A send longer than the period starts the next one at once, never a burst to catch up.
            next = maxOf(next + periodMs, SystemClock.elapsedRealtime())
            if (i % 2 == 0) send("small", small, i) else send("full", full, i)
            i++
        }
    }

    private fun health(): JSONObject = try {
        client.newCall(Request.Builder().url("$serverUrl/health").build()).execute().use { r ->
            JSONObject().put("http", r.code).put("body", r.body?.string()?.take(300) ?: "")
        }
    } catch (e: IOException) {
        JSONObject().put("error", e.toString())
    }

    private fun send(name: String, body: ByteArray, i: Int) {
        val req = Request.Builder()
            .url("$serverUrl/detect")
            .header("X-Frame-Id", "net-$name-$i")
            .header("X-Regime", "clear")
            .post(body.toRequestBody(octet))
            .build()
        val sentMs = System.currentTimeMillis()
        val t0 = System.nanoTime()
        try {
            client.newCall(req).execute().use { resp ->
                val rtMs = (System.nanoTime() - t0) / 1e6
                val j = runCatching { JSONObject(resp.body?.string() ?: "{}") }.getOrNull()
                row(name, i, body.size, sentMs, sentMs + Math.round(rtMs), rtMs,
                    j?.optDouble("server_ms", Double.NaN) ?: Double.NaN, j?.optDouble("infer_ms", Double.NaN) ?: Double.NaN,
                    resp.code, if (resp.isSuccessful && j != null) "ok" else "bad_response")
            }
        } catch (e: IOException) {
            Log.w("NetProbe", "send $name-$i failed: ${e.message}")
            row(name, i, body.size, sentMs, System.currentTimeMillis(), Double.NaN, Double.NaN, Double.NaN, 0, "fail")
        }
    }

    private fun row(name: String, i: Int, bytes: Int, sent: Long, reply: Long, rt: Double, srv: Double,
                    inf: Double, http: Int, ok: String) {
        fun f(x: Double) = if (x.isNaN()) "" else "%.2f".format(x)
        runCatching { csvFile.appendText("$name,$i,$bytes,$sent,$reply,${f(rt)},${f(srv)},${f(inf)},$http,$ok\n") }
    }
}
