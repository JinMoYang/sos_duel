package com.example.activeperception

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.BufferedWriter
import java.io.File
import kotlin.math.abs

/**
 * One long press, a corridor walked back and forth (the glass, 2026-10-30): runs a plan of
 * methods while the wearer walks a corridor, moving to the next method after [legsPerBlock]
 * legs (two round trips = 4 legs).
 *
 * A leg ends at a U-turn: the heading about gravity ([SensorDataManager.totalYawDeg]) moves by
 * at least [TURN_DEG] within [TURN_WINDOW_MS] and is still at least [TURN_HOLD_DEG] away from
 * where it started [TURN_CONFIRM_MS] later, so a glance back, which returns, is not counted
 * (it is logged as turn_rejected). After a turn none is counted for [TURN_GAP_MS].
 *
 * There is no time fallback. The wearer hears every event instead and stops the session when
 * one is missing or wrong: a turn counted, one short beep; a method started, two beeps; the
 * plan done, three long beeps; a run that stopped by itself, a switch that did not finish in
 * [SWITCH_TIMEOUT_MS], or the session stopped by the app being paused ([interrupted]), an alarm.
 *
 * The wearer also sees where they are ([Screen]): the method in large type, the lap and the
 * leg under it (lap 1/2 · leg 2/4), and the place in the plan.
 *
 * Logs walksession_<wall ms>.csv beside the runs (every event with the sensor clock and the
 * heading); each run's frames.csv carries the leg in run_lap. Everything here runs on the main
 * thread.
 */
class WalkSession(
    context: Context,
    private val sensors: SensorDataManager,
    private val host: LapSession.Host,
    private val plan: List<String>,
    private val legsPerBlock: Int = 4,
    /** Seconds between the long press and the first method, to settle the glass on the head. */
    private val startDelayS: Int = 5,
    /** The name the screen shows for a method. */
    private val displayName: (String) -> String = { it },
    private val screen: Screen = Screen { title, detail, sub ->
        host.status(listOf(title, detail, sub).filter { it.isNotEmpty() }.joinToString(" · "))
    }
) : PlanSession {
    /** The wearer's screen: [title] in large type, [detail] and [sub] under it. */
    fun interface Screen { fun show(title: String, detail: String, sub: String) }

    companion object {
        const val TURN_DEG = 150.0
        const val TURN_HOLD_DEG = 120.0
        const val TURN_WINDOW_MS = 4_000L
        const val TURN_CONFIRM_MS = 800L
        const val TURN_GAP_MS = 3_000L
        const val SWITCH_TIMEOUT_MS = 20_000L
        private const val TICK_MS = 50L
    }

    private enum class State { IDLE, COUNTDOWN, RUNNING, SWITCHING }
    private enum class Sound { TURN, BLOCK, DONE, ALARM }

    private val handler = Handler(Looper.getMainLooper())
    private var state = State.IDLE
    private var blockIdx = 0
    private var legsInBlock = 0
    private var turnNo = 0
    /** (elapsed ms, heading deg) over the last [TURN_WINDOW_MS]. */
    private val history = ArrayDeque<Pair<Long, Double>>()
    private var candidateMs = 0L
    private var candidateFromDeg = 0.0
    private var lastTurnMs = 0L
    private var switchStartMs = 0L
    private var countdownEndMs = 0L
    private var countdownShownS = -1
    private val tone: ToneGenerator? = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 100) }.getOrNull()

    private val logFile: File = run {
        val public = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
        @Suppress("DEPRECATION")
        val base = if (public) File(Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOCUMENTS), "sos")
        else File(context.getExternalFilesDir(null), "sos")
        base.mkdirs()
        File(base, "walksession_${System.currentTimeMillis()}.csv")
    }
    private var log: BufferedWriter? = null

    override val active: Boolean get() = state != State.IDLE

    private val tick = object : Runnable {
        override fun run() {
            if (state == State.IDLE) return
            runCatching { step() }
            if (state != State.IDLE) handler.postDelayed(this, TICK_MS)
        }
    }

    override fun start() {
        if (state != State.IDLE || plan.isEmpty()) return
        log = runCatching { logFile.bufferedWriter() }.getOrNull()
        log?.apply {
            write("wall_ms,elapsed_ns,event,block_idx,method,legs_in_block,turn_no,total_yaw_deg," +
                "yaw_rate_rad_s,note")
            newLine()
        }
        blockIdx = 0; legsInBlock = 0; turnNo = 0
        if (startDelayS > 0) {
            state = State.COUNTDOWN
            countdownEndMs = SystemClock.elapsedRealtime() + startDelayS * 1000L
            countdownShownS = -1
            event("countdown", note = "${startDelayS}s")
        } else begin()
        handler.post(tick)
    }

    private fun begin() {
        sensors.resetTotalYaw()
        event("start", note = "plan=" + plan.joinToString("+") + " legs=$legsPerBlock" +
            " gravity_locked=${sensors.gravityLocked}")
        beginBlock()
    }

    private fun beginBlock() {
        legsInBlock = 0
        history.clear(); candidateMs = 0L
        lastTurnMs = SystemClock.elapsedRealtime()
        state = State.RUNNING
        event("block_start")
        play(Sound.BLOCK)
        host.buzz(120)
        showProgress()
        host.startBlock(plan[blockIdx])
    }

    /** Stops driving the plan. Does not stop the current run; the caller does that. */
    override fun abort() {
        if (state == State.IDLE) return
        event("abort")
        finish(completed = false, "두 번 눌러 멈춤")
    }

    /** Stopped by something other than the wearer ([why] is logged): the alarm sounds. */
    fun interrupted(why: String) {
        if (state == State.IDLE) return
        event("interrupted", note = why)
        play(Sound.ALARM)
        finish(completed = false, "앱이 가려져서 멈춤")
    }

    /** The host calls this when a run it started for us has fully ended. */
    override fun onRunEnded() {
        when (state) {
            State.SWITCHING -> {}
            State.RUNNING -> {
                event("block_ended_early", note = "the run stopped by itself")
                play(Sound.ALARM)
            }
            else -> return
        }
        event("block_ended")
        blockIdx++
        if (blockIdx >= plan.size) {
            event("done")
            play(Sound.DONE)
            host.buzz(800)
            finish(completed = true, "방법 ${plan.size}개 모두 완료")
            return
        }
        beginBlock()
    }

    private fun step() {
        val nowMs = SystemClock.elapsedRealtime()
        when (state) {
            State.COUNTDOWN -> {
                val leftMs = countdownEndMs - nowMs
                if (leftMs <= 0L) begin()
                else {
                    val s = ((leftMs + 999L) / 1000L).toInt()
                    if (s != countdownShownS) {
                        countdownShownS = s
                        screen.show("시작까지 ${s}초", "첫 방법: ${displayName(plan[0])}", "방법 1/${plan.size}")
                    }
                }
            }
            State.RUNNING -> if (turned(nowMs)) {
                turnNo++; legsInBlock++
                event("turn")
                play(Sound.TURN)
                if (legsInBlock >= legsPerBlock) {
                    state = State.SWITCHING
                    switchStartMs = nowMs
                    event("block_stop_request")
                    screen.show("바꾸는 중",
                        if (blockIdx + 1 < plan.size) "다음: ${displayName(plan[blockIdx + 1])}" else "마무리 중", "")
                    host.stopBlock()
                } else {
                    showProgress()
                    host.lapStarted(plan[blockIdx], legsInBlock)
                }
            }
            State.SWITCHING -> if (nowMs - switchStartMs > SWITCH_TIMEOUT_MS) {
                // A run that never reports its end must not hold up the rest of the plan.
                event("switch_timeout")
                play(Sound.ALARM)
                onRunEnded()
            }
            State.IDLE -> {}
        }
    }

    /** True once per U-turn (see the class comment). */
    private fun turned(nowMs: Long): Boolean {
        val yaw = sensors.totalYawDeg
        history.addLast(nowMs to yaw)
        while (history.isNotEmpty() && nowMs - history.first().first > TURN_WINDOW_MS) history.removeFirst()
        if (nowMs - lastTurnMs < TURN_GAP_MS) { candidateMs = 0L; return false }
        if (candidateMs == 0L) {
            val from = history.maxByOrNull { abs(yaw - it.second) } ?: return false
            if (abs(yaw - from.second) >= TURN_DEG) { candidateMs = nowMs; candidateFromDeg = from.second }
            return false
        }
        if (nowMs - candidateMs < TURN_CONFIRM_MS) return false
        candidateMs = 0L
        if (abs(yaw - candidateFromDeg) < TURN_HOLD_DEG) {
            event("turn_rejected", note = "heading came back within $TURN_CONFIRM_MS ms")
            return false
        }
        lastTurnMs = nowMs
        history.clear()
        return true
    }

    /** The running method; its lap (a corridor round trip, two legs) and leg; the place in the plan. */
    private fun showProgress() {
        val laps = (legsPerBlock + 1) / 2
        screen.show(displayName(plan[blockIdx]),
            "바퀴 ${legsInBlock / 2 + 1}/$laps  ·  편도 ${legsInBlock + 1}/$legsPerBlock",
            "방법 ${blockIdx + 1}/${plan.size}  ·  정지: 두 번 누르기")
    }

    private fun play(sound: Sound) {
        val t = tone ?: return
        fun at(delayMs: Long, type: Int, ms: Int) =
            handler.postDelayed({ runCatching { t.startTone(type, ms) } }, delayMs)
        when (sound) {
            Sound.TURN -> at(0, ToneGenerator.TONE_PROP_BEEP, 150)
            Sound.BLOCK -> { at(0, ToneGenerator.TONE_DTMF_D, 200); at(350, ToneGenerator.TONE_DTMF_D, 200) }
            Sound.DONE -> for (i in 0 until 3) at(i * 900L, ToneGenerator.TONE_DTMF_D, 600)
            Sound.ALARM -> at(0, ToneGenerator.TONE_SUP_ERROR, 1500)
        }
    }

    private fun finish(completed: Boolean, detail: String) {
        state = State.IDLE
        screen.show(if (completed) "끝" else "멈춤", detail, "시작 버튼: 처음부터 다시")
        handler.removeCallbacks(tick)
        runCatching { log?.flush(); log?.close() }
        log = null
        handler.postDelayed({ runCatching { tone?.release() } }, 4_000L)
        host.sessionEnded(completed)
    }

    private fun event(name: String, note: String = "") {
        val w = log ?: return
        val method = if (blockIdx < plan.size) plan[blockIdx] else ""
        runCatching {
            w.write(listOf(System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos(), name, blockIdx,
                method, legsInBlock, turnNo, "%.1f".format(sensors.totalYawDeg),
                "%.3f".format(sensors.currentYawRate), note.replace(',', ';')).joinToString(","))
            w.newLine()
            w.flush()
        }
    }
}
