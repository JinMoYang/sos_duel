package com.example.activeperception

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Runs every sensing method at the same phase of a continuously oscillating turntable.
 *
 * The turntable is never commanded by the app. The first method is opened immediately so camera
 * and GPU warm-up overlap the calibration. Gyro reversals then establish the two end points and
 * the period. A method is measured only after it has produced its first output and the next
 * reference-side reversal arrives. Its valid window ends at the following reference-side
 * reversal: exactly one left-right-left (or right-left-right) cycle.
 *
 * Camera runs are deliberately allowed to contain warm-up frames. [ExperimentSession]'s rotation
 * fields mark the exact valid window in frames.csv; this session's continuously sampled CSV keeps
 * the phase anchors needed to recompute/alignment-check it offline.
 */
class RotationSession(
    context: Context,
    private val sensors: SensorDataManager,
    private val host: LapSession.Host,
    private val plan: List<Block>,
    private val startDelayS: Int = 5,
    private val calibrationCycles: Int = 3,
    private val displayName: (String) -> String = { it },
    private val screen: (title: String, detail: String, sub: String) -> Unit,
    private val currentRunName: () -> String = { "" }
) : PlanSession {

    data class Block(val method: String, val pass: Int, val position: Int, val methodsInPass: Int)

    companion object {
        private const val TICK_MS = 50L
        private const val READY_TIMEOUT_MS = 30_000L
        private const val CALIBRATION_TIMEOUT_MS = 120_000L
        private const val SWITCH_TIMEOUT_MS = 20_000L
        private const val MIN_TIMEOUT_MS = 10_000L
        private const val TIMEOUT_PERIODS = 2.5
        private const val MAX_CYCLE_RETRIES = 1
    }

    private enum class State {
        IDLE, COUNTDOWN, CALIBRATING, READYING, WAIT_REFERENCE, RECORDING, SWITCHING
    }

    private val handler = Handler(Looper.getMainLooper())
    private val tracker = OscillationPhaseTracker()
    private var state = State.IDLE
    private var blockIdx = 0
    private var countdownEndMs = 0L
    private var countdownShownS = -1
    private var stateStartMs = 0L
    private var switchStartMs = 0L
    private var calibrationStartMs = 0L
    private var referenceDirection = 0
    private val referenceTimes = ArrayList<Long>()
    private val halfSweeps = ArrayList<Double>()
    private var typicalPeriodMs = 0.0
    private var typicalSweepDeg = 0.0
    private var cycleStartYaw = 0.0
    private var oppositeEndYaw = 0.0
    private var recordHalf = 0
    private var cycleRetries = 0
    private var lastUiMs = 0L
    private var pendingCompletion = false

    private val log = RotationLog(context)

    override val active: Boolean get() = state != State.IDLE

    private val tick = object : Runnable {
        override fun run() {
            if (state == State.IDLE) return
            runCatching { step() }.onFailure { fail("rotation controller: ${it.message}") }
            if (state != State.IDLE) handler.postDelayed(this, TICK_MS)
        }
    }

    override fun start() {
        if (state != State.IDLE || plan.isEmpty()) return
        resetRotationFields(active = true)
        log.start()
        event("session_start", "plan=" + plan.joinToString("+") { it.method } +
            " calibration_cycles=$calibrationCycles gravity_locked=${sensors.gravityLocked}")
        if (startDelayS > 0) {
            state = State.COUNTDOWN
            countdownEndMs = SystemClock.elapsedRealtime() + startDelayS * 1_000L
            stateStartMs = SystemClock.elapsedRealtime()
        } else begin()
        handler.post(tick)
    }

    private fun begin() {
        sensors.resetTotalYaw()
        tracker.reset()
        referenceDirection = 0
        referenceTimes.clear()
        halfSweeps.clear()
        typicalPeriodMs = 0.0
        typicalSweepDeg = 0.0
        calibrationStartMs = SystemClock.elapsedRealtime()
        stateStartMs = calibrationStartMs
        state = State.CALIBRATING
        event("calibration_start", "first method is warming while phase is learned")
        beginBlock(calibrating = true)
    }

    private fun beginBlock(calibrating: Boolean = false) {
        val b = plan[blockIdx]
        ExperimentSession.rotationBlock = blockIdx + 1
        ExperimentSession.rotationPass = b.pass
        ExperimentSession.rotationValid = false
        ExperimentSession.rotationPhase = Double.NaN
        ExperimentSession.rotationDirection = 0
        ExperimentSession.runLap = -2
        cycleRetries = 0
        stateStartMs = SystemClock.elapsedRealtime()
        if (!calibrating) state = State.READYING
        event("block_start", "run=${currentRunName()}")
        show(if (calibrating) "기준 phase 학습" else "scheme 준비 중",
            "${displayName(b.method)} · ${b.position}/${b.methodsInPass}",
            if (calibrating) "카메라·GPU 준비와 동시에 3회 왕복 관찰" else "첫 정상 결과를 기다리는 중")
        host.startBlock(b.method)
    }

    override fun abort() {
        if (state == State.IDLE) return
        event("abort")
        finish(completed = false, detail = "사용자 중지")
    }

    fun interrupted(why: String) {
        if (state == State.IDLE) return
        event("interrupted", why)
        host.buzz(800)
        finish(completed = false, detail = why)
    }

    override fun onRunEnded() {
        if (state != State.SWITCHING) {
            event("block_ended_early", "state=$state")
            finish(completed = false, detail = "scheme이 예상보다 일찍 종료됨")
            return
        }
        event("block_ended", "run=${currentRunName()}")
        if (pendingCompletion) {
            finish(completed = true, detail = "모든 scheme의 동일 phase 기록 완료")
            return
        }
        blockIdx++
        if (blockIdx >= plan.size) {
            finish(completed = true, detail = "모든 scheme의 동일 phase 기록 완료")
            return
        }
        beginBlock()
    }

    private fun step() {
        val now = SystemClock.elapsedRealtime()
        if (state == State.COUNTDOWN) {
            val left = countdownEndMs - now
            if (left <= 0L) begin()
            else {
                val sec = ((left + 999L) / 1_000L).toInt()
                if (sec != countdownShownS) {
                    countdownShownS = sec
                    show("회전 실험 시작까지 ${sec}초", "턴테이블은 계속 회전", "센서·모델은 자동 준비")
                }
            }
            sample("sample")
            return
        }

        val reversal = if (sensors.gravityLocked) tracker.update(
            now, sensors.totalYawDeg, sensors.currentYawRate) else null
        if (reversal != null) onReversal(reversal)
        updatePhase()
        sample("sample")

        when (state) {
            State.CALIBRATING -> {
                if (now - calibrationStartMs > CALIBRATION_TIMEOUT_MS)
                    fail("${CALIBRATION_TIMEOUT_MS / 1_000}s 안에 기준 phase를 학습하지 못함")
                else if (now - lastUiMs >= 250L) {
                    val cycles = (referenceTimes.size - 1).coerceAtLeast(0)
                    show("기준 phase 학습", "왕복 $cycles/$calibrationCycles",
                        if (sensors.gravityLocked) "scheme 1 준비 병행 중" else "글래스 gyro 기준축 대기 중")
                }
            }
            State.READYING -> when {
                host.runReady() -> {
                    state = State.WAIT_REFERENCE
                    stateStartMs = now
                    event("block_ready", "run=${currentRunName()}")
                    show("phase 대기", displayName(plan[blockIdx].method), "다음 기준 phase부터 기록")
                    host.beep(2)
                }
                now - stateStartMs > READY_TIMEOUT_MS ->
                    fail("scheme 첫 정상 결과가 ${READY_TIMEOUT_MS / 1_000}s 안에 생성되지 않음")
            }
            State.WAIT_REFERENCE -> if (typicalPeriodMs > 0 &&
                now - stateStartMs > cycleTimeoutMs()) {
                fail("기준 phase가 제한 시간 안에 다시 오지 않음")
            }
            State.RECORDING -> if (typicalPeriodMs > 0 &&
                now - stateStartMs > cycleTimeoutMs()) {
                ExperimentSession.rotationValid = false
                ExperimentSession.rotationPhase = Double.NaN
                event("cycle_invalid", "timeout retry=$cycleRetries")
                if (cycleRetries++ < MAX_CYCLE_RETRIES) {
                    state = State.WAIT_REFERENCE
                    stateStartMs = now
                    show("왕복 재시도", displayName(plan[blockIdx].method), "다음 기준 phase를 기다리는 중")
                } else fail("완전한 왕복을 검출하지 못함")
            }
            State.SWITCHING -> if (now - switchStartMs > SWITCH_TIMEOUT_MS)
                fail("scheme 종료가 ${SWITCH_TIMEOUT_MS / 1_000}s 안에 끝나지 않음")
            State.IDLE, State.COUNTDOWN -> Unit
        }
        lastUiMs = now
    }

    private fun onReversal(r: OscillationPhaseTracker.Reversal) {
        halfSweeps += r.sweepDeg
        event("reversal", "new_direction=${r.newDirection} sweep_deg=%.2f".format(r.sweepDeg))

        if (state == State.CALIBRATING) {
            if (referenceDirection == 0) referenceDirection = r.newDirection
            if (r.newDirection == referenceDirection) {
                referenceTimes += r.timeMs
                if (referenceTimes.size >= calibrationCycles + 1) finishCalibration()
            }
            return
        }

        if (state == State.WAIT_REFERENCE && r.newDirection == referenceDirection) {
            cycleStartYaw = r.yawDeg
            oppositeEndYaw = r.yawDeg
            recordHalf = 0
            state = State.RECORDING
            stateStartMs = r.timeMs
            ExperimentSession.rotationValid = true
            ExperimentSession.rotationPhase = 0.0
            ExperimentSession.rotationDirection = referenceDirection
            ExperimentSession.runLap = plan[blockIdx].pass - 1
            event("valid_cycle_start", "run=${currentRunName()}")
            val b = plan[blockIdx]
            show("측정 중", "${displayName(b.method)} · pass ${b.pass}", "phase 0% · 한 왕복")
            return
        }

        if (state != State.RECORDING) return
        if (r.newDirection == -referenceDirection && recordHalf == 0) {
            recordHalf = 1
            oppositeEndYaw = r.yawDeg
            ExperimentSession.rotationPhase = 0.5
            ExperimentSession.rotationDirection = r.newDirection
            event("valid_half_cycle", "phase=0.5")
        } else if (r.newDirection == referenceDirection && recordHalf == 1) {
            ExperimentSession.rotationPhase = 1.0
            event("valid_cycle_end", "duration_ms=${r.timeMs - stateStartMs}")
            ExperimentSession.rotationValid = false
            ExperimentSession.rotationDirection = 0
            ExperimentSession.runLap = -2
            state = State.SWITCHING
            switchStartMs = r.timeMs
            pendingCompletion = blockIdx == plan.lastIndex
            val next = plan.getOrNull(blockIdx + 1)?.let { displayName(it.method) } ?: "완료"
            show("scheme 전환", "다음: $next", "유효 왕복 저장 완료")
            host.stopBlock()
        }
    }

    private fun finishCalibration() {
        typicalPeriodMs = referenceTimes.zipWithNext { a, b -> (b - a).toDouble() }.median()
        typicalSweepDeg = halfSweeps.median()
        state = State.READYING
        stateStartMs = SystemClock.elapsedRealtime()
        event("calibration_done", "period_ms=%.1f sweep_deg=%.2f reference_direction=%d"
            .format(typicalPeriodMs, typicalSweepDeg, referenceDirection))
        show("기준 phase 완료", "주기 %.1f초 · 폭 %.1f°".format(typicalPeriodMs / 1_000.0, typicalSweepDeg),
            "scheme 1의 첫 정상 결과 확인 중")
        host.beep(1)
    }

    private fun updatePhase() {
        if (state != State.RECORDING || typicalSweepDeg <= 0.0) return
        val yaw = sensors.totalYawDeg
        val phase = if (recordHalf == 0) {
            0.5 * abs(yaw - cycleStartYaw) / typicalSweepDeg
        } else {
            0.5 + 0.5 * abs(yaw - oppositeEndYaw) / typicalSweepDeg
        }.coerceIn(0.0, 0.999)
        ExperimentSession.rotationPhase = phase
        ExperimentSession.rotationDirection = tracker.direction
        val now = SystemClock.elapsedRealtime()
        if (now - lastUiMs >= 250L) {
            val b = plan[blockIdx]
            show("측정 중", "${displayName(b.method)} · pass ${b.pass}",
                "phase ${(phase * 100).toInt()}% · 한 왕복")
        }
    }

    private fun cycleTimeoutMs(): Long =
        maxOf(MIN_TIMEOUT_MS.toDouble(), typicalPeriodMs * TIMEOUT_PERIODS).toLong()

    private fun fail(why: String) {
        if (state == State.IDLE) return
        event("failure", why)
        host.buzz(1_000)
        if (state == State.SWITCHING) finish(false, why)
        else {
            state = State.SWITCHING
            pendingCompletion = false
            switchStartMs = SystemClock.elapsedRealtime()
            show("회전 실험 오류", why, "현재 scheme 종료 중")
            host.stopBlock()
        }
    }

    private fun finish(completed: Boolean, detail: String) {
        if (state == State.IDLE) return
        event(if (completed) "session_done" else "session_stopped", detail)
        state = State.IDLE
        handler.removeCallbacks(tick)
        resetRotationFields(active = false)
        log.close()
        show(if (completed) "회전 실험 완료" else "회전 실험 중지", detail,
            if (completed) "모든 scheme 기록 저장됨" else "다시 시작하면 calibration부터 수행")
        if (completed) { host.beep(3); host.buzz(800) }
        host.sessionEnded(completed)
    }

    private fun resetRotationFields(active: Boolean) {
        ExperimentSession.rotationActive = active
        ExperimentSession.rotationValid = false
        ExperimentSession.rotationBlock = if (active) 0 else -1
        ExperimentSession.rotationPass = if (active) 0 else -1
        ExperimentSession.rotationPhase = Double.NaN
        ExperimentSession.rotationDirection = 0
        if (!active) ExperimentSession.runLap = -1
    }

    private fun show(title: String, detail: String, sub: String) = screen(title, detail, sub)

    private fun event(name: String, note: String = "") = log.row(name, note)
    private fun sample(name: String) = log.row(name, "")

    private fun List<Double>.median(): Double {
        if (isEmpty()) return 0.0
        val s = sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
    }

    /** Small continuous logger independent of per-method run loggers. */
    private inner class RotationLog(context: Context) {
        private val file: File = run {
            val public = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
            @Suppress("DEPRECATION")
            val base = if (public) File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOCUMENTS), "sos") else File(context.getExternalFilesDir(null), "sos")
            base.mkdirs()
            File(base, "rotationsession_${System.currentTimeMillis()}.csv")
        }
        private val queue = ArrayBlockingQueue<String>(8_192)
        @Volatile private var accepting = false
        private var writerThread: Thread? = null

        fun start() {
            accepting = true
            writerThread = Thread({
                file.bufferedWriter().use { w ->
                    w.write("wall_ms,elapsed_ns,gyro_ts_ns,event,state,block_idx,pass,method,run,valid,phase,direction,total_yaw_deg,yaw_rate_rad_s,note")
                    w.newLine()
                    while (accepting || queue.isNotEmpty()) {
                        queue.poll(200, TimeUnit.MILLISECONDS)?.let { w.write(it); w.newLine() }
                    }
                }
            }, "SoS-RotationLog").apply { priority = Thread.MIN_PRIORITY; start() }
        }

        fun row(event: String, note: String) {
            if (!accepting) return
            val b = plan.getOrNull(blockIdx)
            val phase = ExperimentSession.rotationPhase
            queue.offer(listOf(
                System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos(), sensors.lastGyroTimestampNs,
                event, state.name, blockIdx, b?.pass ?: "", b?.method ?: "", currentRunName(),
                if (ExperimentSession.rotationValid) 1 else 0,
                if (phase.isNaN()) "" else "%.5f".format(phase), tracker.direction,
                "%.5f".format(sensors.totalYawDeg), "%.6f".format(sensors.currentYawRate), note
            ).joinToString(",") { csv(it.toString()) })
        }

        fun close() {
            accepting = false
            writerThread?.join(2_000L)
        }

        private fun csv(s: String): String = if (s.any { it == ',' || it == '"' || it == '\n' })
            "\"${s.replace("\"", "\"\"")}\"" else s
    }
}

/** Gyro sign-change detector with hysteresis and a minimum physical sweep. */
internal class OscillationPhaseTracker(
    private val moveThresholdRadS: Double = 0.10,
    private val reversalConfirmMs: Long = 150L,
    private val minHalfSweepDeg: Double = 15.0
) {
    data class Reversal(val timeMs: Long, val yawDeg: Double, val newDirection: Int, val sweepDeg: Double)

    var direction: Int = 0
        private set
    private var halfStartYaw = 0.0
    private var pendingDirection = 0
    private var pendingSinceMs = 0L
    private var filteredRate = 0.0

    fun reset() {
        direction = 0
        halfStartYaw = 0.0
        pendingDirection = 0
        pendingSinceMs = 0L
        filteredRate = 0.0
    }

    fun update(timeMs: Long, yawDeg: Double, yawRateRadS: Double): Reversal? {
        // Prefer the new sample so a real turn is recognized near the physical endpoint; the
        // separate confirmation time below, rather than a sluggish EMA, rejects one-sample noise.
        filteredRate = if (direction == 0) yawRateRadS else 0.35 * filteredRate + 0.65 * yawRateRadS
        val observed = when {
            filteredRate >= moveThresholdRadS -> 1
            filteredRate <= -moveThresholdRadS -> -1
            else -> 0
        }
        if (observed == 0) return null
        if (direction == 0) {
            direction = observed
            halfStartYaw = yawDeg
            return null
        }
        if (observed == direction) {
            pendingDirection = 0
            return null
        }
        if (pendingDirection != observed) {
            pendingDirection = observed
            pendingSinceMs = timeMs
            return null
        }
        val sweep = abs(yawDeg - halfStartYaw)
        if (timeMs - pendingSinceMs < reversalConfirmMs || sweep < minHalfSweepDeg) return null
        direction = observed
        pendingDirection = 0
        halfStartYaw = yawDeg
        return Reversal(timeMs, yawDeg, observed, sweep)
    }
}
