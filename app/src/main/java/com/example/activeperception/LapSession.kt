package com.example.activeperception

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.BufferedWriter
import java.io.File
import kotlin.math.abs

/**
 * One press, many laps: runs a plan of blocks while a vehicle keeps circling a closed
 * track, moving to the next block when the vehicle has completed that block's laps.
 *
 * A lap is 360 degrees of rotation about gravity, read from
 * [SensorDataManager.totalYawDeg]. The first lap is a lead-in that finds where the
 * vehicle turns fastest; the lap line is placed there. On a straight the heading barely
 * changes, so a line placed on one can slide the length of that straight under a degree
 * or two of gyro drift, while in mid-curve the same drift moves it by a few hundredths
 * of a second.
 *
 * This has not been tried on the real track. So it never relies on lap detection alone:
 * if the lead-in does not finish, or a lap stops arriving, it falls back to counting
 * time instead of laps and says so in its log. The raw IMU is in every block's own run
 * directory, so laps can always be recomputed afterwards.
 *
 * Needs a track with a net 360 degrees per lap in one direction; a figure-eight sums to
 * zero and only the time fallback will advance it.
 *
 * Everything here runs on the main thread.
 */
class LapSession(
    context: Context,
    private val sensors: SensorDataManager,
    private val host: Host,
    private val plan: List<Block>,
    private val repeats: Int,
    /** Seconds to wait for the lead-in lap before giving up on lap detection. */
    private val leadInTimeoutS: Int = 90,
    /** Lap length assumed once lap detection has been given up on. */
    private val fallbackLapS: Int = 15,
    /** Seconds between the press and the real start, so the operator can let go of the
     *  phone and step back before any rotation is counted. 0 starts at once. */
    private val startDelayS: Int = 5
) {
    data class Block(val method: String, val laps: Int)

    interface Host {
        /** Start a run of [method]. Called on the main thread. */
        fun startBlock(method: String)
        /** Ask the current run to stop; [onRunEnded] must follow when it has. */
        fun stopBlock()
        fun status(text: String)
        fun buzz(ms: Long)
        /** An audible cue: one beep when counting really starts, three when the plan is done. */
        fun beep(times: Int)
        /** A new lap of the running block has begun; [lapsInBlock] laps of it are complete.
         *  Lets a block change a setting lap by lap. Not called for a block's first lap,
         *  which [startBlock] itself opens. */
        fun lapStarted(method: String, lapsInBlock: Int)
        fun sessionEnded(completed: Boolean)
    }

    companion object {
        const val DEFAULT_PLAN = "collect:10,ae_phone:3"
        /** Five repeats give the collection 50 laps: ten for each of its five base ISOs,
         *  which is what it takes to tell base ISOs apart at about 16%. */
        const val DEFAULT_REPEATS = 5
        private const val TICK_MS = 50L

        /** "collect:10,ae_phone:3" -> blocks. Entries that do not parse are dropped. */
        fun parsePlan(text: String): List<Block> = text.split(',').mapNotNull { part ->
            val bits = part.trim().split(':')
            val method = bits.getOrNull(0)?.trim().orEmpty()
            val laps = bits.getOrNull(1)?.trim()?.toIntOrNull() ?: 1
            if (method.isEmpty() || laps < 1) null else Block(method, laps)
        }
    }

    private enum class State { IDLE, COUNTDOWN, LEAD_IN, TO_LINE, RUNNING, SWITCHING }

    private val handler = Handler(Looper.getMainLooper())
    private val totalBlocks = plan.size * repeats
    private var state = State.IDLE
    private var timeMode = false
    private var blockIdx = 0
    private var lapsInBlock = 0
    private var lapNo = 0

    private var startMs = 0L
    private var moveStartMs = 0L
    private var rateEma = 0.0
    private var maxRate = 0.0
    private var phaseAtMax = 0.0
    private var nextLineDeg = 0.0
    private var nextLineMs = 0L
    private var lastLineMs = 0L
    private var typicalLapS = 0.0
    private var switchStartMs = 0L
    private var countdownEndMs = 0L
    private var countdownShownS = -1

    private val logFile: File = run {
        val public = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            Environment.isExternalStorageManager()
        @Suppress("DEPRECATION")
        val base = if (public) File(Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOCUMENTS), "sos")
        else File(context.getExternalFilesDir(null), "sos")
        base.mkdirs()
        File(base, "lapsession_${System.currentTimeMillis()}.csv")
    }
    private var log: BufferedWriter? = null

    val active: Boolean get() = state != State.IDLE

    private val tick = object : Runnable {
        override fun run() {
            if (state == State.IDLE) return
            runCatching { step() }
            if (state != State.IDLE) handler.postDelayed(this, TICK_MS)
        }
    }

    fun start() {
        if (state != State.IDLE || plan.isEmpty()) return
        log = runCatching { logFile.bufferedWriter() }.getOrNull()
        log?.apply {
            write("wall_ms,elapsed_ns,event,block_idx,method,lap_no,laps_in_block,lap_s," +
                "total_yaw_deg,yaw_rate_rad_s,mode,note")
            newLine()
        }
        timeMode = false
        blockIdx = 0; lapsInBlock = 0; lapNo = 0; typicalLapS = 0.0
        if (startDelayS > 0) {
            state = State.COUNTDOWN
            countdownEndMs = SystemClock.elapsedRealtime() + startDelayS * 1000L
            countdownShownS = -1
            event("countdown", note = "${startDelayS}s")
        } else beginLeadIn()
        handler.post(tick)
    }

    /** The real start: rotation is counted from here, not from the button press. */
    private fun beginLeadIn() {
        sensors.resetTotalYaw()
        startMs = SystemClock.elapsedRealtime()
        moveStartMs = 0L
        rateEma = 0.0; maxRate = 0.0; phaseAtMax = 0.0
        state = State.LEAD_IN
        event("start", note = "plan=" + plan.joinToString("+") { "${it.method}x${it.laps}" } +
            " repeats=$repeats gravity_locked=${sensors.gravityLocked}" +
            " limo_speed=${ExperimentSession.limoSpeed.ifEmpty { "-" }.replace(',', ';')}")
        host.buzz(120)
        host.beep(1)
        host.status(if (sensors.gravityLocked) "lead-in lap…"
                    else "lead-in… (hold still once so gravity locks)")
    }

    /** Stops driving the plan. Does not stop the current run; the caller does that. */
    fun abort() {
        if (state == State.IDLE) return
        event("abort")
        finish(completed = false)
    }

    /** The host calls this when a run it started for us has fully ended. */
    fun onRunEnded() {
        when (state) {
            State.SWITCHING -> {}
            State.RUNNING -> event("block_ended_early", note = "the run stopped by itself")
            else -> return
        }
        event("block_ended")
        blockIdx++
        if (blockIdx >= totalBlocks) {
            event("done")
            host.buzz(800)
            host.beep(3)
            finish(completed = true)
            return
        }
        beginBlock()
    }

    private fun step() {
        val nowMs = SystemClock.elapsedRealtime()
        val turned = abs(sensors.totalYawDeg)
        rateEma = 0.8 * rateEma + 0.2 * abs(sensors.currentYawRate)
        when (state) {
            State.COUNTDOWN -> {
                val leftMs = countdownEndMs - nowMs
                if (leftMs <= 0L) beginLeadIn()
                else {
                    val s = ((leftMs + 999L) / 1000L).toInt()
                    if (s != countdownShownS) {
                        countdownShownS = s
                        host.status("starting in $s…")
                    }
                }
            }
            State.LEAD_IN -> {
                if (moveStartMs == 0L && turned > 5.0) moveStartMs = nowMs
                if (turned < 360.0) {
                    if (rateEma > maxRate) { maxRate = rateEma; phaseAtMax = turned }
                } else {
                    typicalLapS = (nowMs - (if (moveStartMs > 0L) moveStartMs else startMs)) / 1000.0
                    nextLineDeg = 360.0 + phaseAtMax
                    state = State.TO_LINE
                    event("leadin_done", lapS = typicalLapS,
                        note = "line at %.0f deg into the lap, peak %.2f rad/s"
                            .format(phaseAtMax, maxRate))
                    host.status("lap is about %.0f s, heading for the line".format(typicalLapS))
                }
                if (state == State.LEAD_IN && nowMs - startMs > leadInTimeoutS * 1000L)
                    toTimeMode(nowMs, "no full turn within ${leadInTimeoutS}s", startFirst = true)
            }
            State.TO_LINE -> {
                if (turned >= nextLineDeg) {
                    nextLineDeg += 360.0
                    lastLineMs = nowMs
                    beginBlock()
                } else if (nowMs - startMs > (leadInTimeoutS + 60) * 1000L) {
                    toTimeMode(nowMs, "line not reached after the lead-in", startFirst = true)
                }
            }
            State.RUNNING, State.SWITCHING -> {
                val crossed = if (timeMode) nowMs >= nextLineMs else turned >= nextLineDeg
                if (crossed) {
                    val lapS = (nowMs - lastLineMs) / 1000.0
                    lastLineMs = nowMs
                    lapNo++
                    if (timeMode) nextLineMs += lapSecondsForTimeMode() * 1000L
                    else {
                        nextLineDeg += 360.0
                        typicalLapS = if (typicalLapS <= 0.0) lapS else 0.7 * typicalLapS + 0.3 * lapS
                    }
                    if (state == State.RUNNING) {
                        lapsInBlock++
                        event("lap", lapS = lapS)
                        val block = plan[blockIdx % plan.size]
                        host.status(label() + " lap $lapsInBlock/${block.laps}")
                        if (lapsInBlock >= block.laps) {
                            state = State.SWITCHING
                            switchStartMs = nowMs
                            event("block_stop_request")
                            host.stopBlock()
                        } else host.lapStarted(block.method, lapsInBlock)
                    } else {
                        event("lap_during_switch", lapS = lapS)
                    }
                } else if (!timeMode) {
                    // A lap that never arrives would otherwise stall the whole plan.
                    val limitS = maxOf(40.0, 2.5 * typicalLapS)
                    if ((nowMs - lastLineMs) / 1000.0 > limitS)
                        toTimeMode(nowMs, "no lap for %.0f s".format(limitS), startFirst = false)
                }
                // A run that never reports its end must not hold up the rest of the plan.
                if (state == State.SWITCHING && nowMs - switchStartMs > 20_000L) {
                    event("switch_timeout")
                    onRunEnded()
                }
            }
            State.IDLE -> {}
        }
    }

    private fun lapSecondsForTimeMode(): Long =
        (if (typicalLapS > 3.0) typicalLapS else fallbackLapS.toDouble()).toLong().coerceAtLeast(3L)

    private fun toTimeMode(nowMs: Long, why: String, startFirst: Boolean) {
        timeMode = true
        lastLineMs = nowMs
        nextLineMs = nowMs + lapSecondsForTimeMode() * 1000L
        event("fallback_time", note = why)
        host.status("lap detection off ($why): counting ${lapSecondsForTimeMode()} s laps")
        if (startFirst) beginBlock()
    }

    private fun beginBlock() {
        val block = plan[blockIdx % plan.size]
        lapsInBlock = 0
        state = State.RUNNING
        event("block_start")
        host.buzz(120)
        host.status(label() + " lap 0/${block.laps}")
        host.startBlock(block.method)
    }

    private fun label(): String =
        "blk ${blockIdx + 1}/$totalBlocks ${plan[blockIdx % plan.size].method}" +
            (if (timeMode) " [time]" else "")

    private fun finish(completed: Boolean) {
        state = State.IDLE
        handler.removeCallbacks(tick)
        runCatching { log?.flush(); log?.close() }
        log = null
        host.sessionEnded(completed)
    }

    private fun event(name: String, lapS: Double = Double.NaN, note: String = "") {
        val w = log ?: return
        val method = if (blockIdx < totalBlocks) plan[blockIdx % plan.size].method else ""
        runCatching {
            w.write(listOf(System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos(),
                name, blockIdx, method, lapNo, lapsInBlock,
                if (lapS.isNaN()) "" else "%.2f".format(lapS),
                "%.1f".format(sensors.totalYawDeg), "%.3f".format(sensors.currentYawRate),
                if (timeMode) "time" else "yaw",
                note.replace(',', ';')).joinToString(","))
            w.newLine()
            w.flush()
        }
    }
}
