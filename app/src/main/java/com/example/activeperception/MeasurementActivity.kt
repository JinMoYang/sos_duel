package com.example.activeperception

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.activeperception.acquire.Detection
import com.example.activeperception.acquire.Grid
import com.example.activeperception.acquire.HowToSense
import com.example.activeperception.acquire.RAYNEO_X3_PRO_3x3
import com.example.activeperception.acquire.REAL_3x3_INDOOR
import com.example.activeperception.acquire.TfliteYoloDetector
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * UI shell: pick a mode and its parameters, then run it on a worker thread. All measurement
 * behaviour lives in [MeasurementController] and the output layout in [MeasurementLogger].
 *
 * The camera and detector are opened once per Activity session, but a fresh logger and
 * controller are built per run so each lands in its own directory.
 */
class MeasurementActivity : AppCompatActivity() {

    companion object {
        private const val RAYNEO_FRAME_PERIOD_NS = 33_329_000L
        private const val START_DISPATCH_ALLOWANCE_NS = 10_000_000L
        /** Run modes have no frame cap: field passes follow a lap protocol the operator
         *  ends with Stop. Storage stays modest (~85 KB per frame, img/ JPEGs only). */
        private const val NO_FRAME_CAP = Int.MAX_VALUE
        /** Manual focus for every method (diopters): the 2026-10-13 Limo sessions' median
         *  autofocus position, about 45-50 cm, where the toy cars sit. */
        private const val DEFAULT_FOCUS_DIOPTERS = 2.0f
        /** Neural-AE collection budget. The v2 collector pays 25 physical captures per
         *  step for the surface; hists are ~15 ms each, so EVERY captured cell becomes a
         *  sample (a full exposure bracket per scene instant — nothing thrown away).
         *  300 samples = 12 steps (~40 s); three lighting passes stay well under the
         *  2400-record pool cap. Move the camera during collection: within-step samples
         *  share one instant, so scene diversity comes from the steps. */
        private const val NAE_CELLS_PER_STEP = 25
        private const val NAE_TARGET_SAMPLES = 300
        private const val NAE_MAX_FRAMES = 300
        /** Below this the pool is too small to hold out a meaningful validation split. */
        private const val NAE_MIN_TRAIN = 300
        /** Dark enough that the panel costs almost nothing, bright enough to still read
         *  the block number at a glance when the car is stopped. Not 0f: some panels
         *  treat that as off, and the run depends on the Activity staying resumed. */
        private const val DRIVE_BRIGHTNESS = 0.05f
    }

    // Device profile: each grid's base exposure nearly fills its device's RAW frame period
    // (RayNeo 32ms/33.3ms, S25 16ms/16.7ms), so burst-summing stays gap-free on both.
    private val grid: Grid = if (Build.MANUFACTURER.equals("RayNeo", ignoreCase = true))
        RAYNEO_X3_PRO_3x3 else REAL_3x3_INDOOR
    private lateinit var raw: RawSensorCapturer
    private lateinit var detector: TfliteYoloDetector
    private lateinit var sensors: SensorDataManager
    private lateinit var health: DeviceHealthMonitor
    private lateinit var status: TextView
    private lateinit var preview: ImageView
    private lateinit var overlay: OverlayView
    private lateinit var cellText: TextView
    private lateinit var methodGroup: RadioGroup
    private lateinit var cellGridWrap: LinearLayout
    private lateinit var cellGrid: GridLayout
    private lateinit var periodGroup: RadioGroup
    private lateinit var fallbackGroup: RadioGroup
    private lateinit var boostGroup: RadioGroup
    private lateinit var proposedSettings: LinearLayout
    private lateinit var confThreshSpinner: Spinner
    private lateinit var sessionSpinner: Spinner
    private lateinit var drivePanel: LinearLayout
    private lateinit var driveStart: Button
    private lateinit var driveStatus: TextView
    private val prefs by lazy { getSharedPreferences("sos_ui", MODE_PRIVATE) }
    private lateinit var driveRowsView: LinearLayout
    private lateinit var blockSecView: EditText
    private lateinit var repeatsView: EditText
    private var driveMode = false
    private var driveRole = "A"
    private var driveShownAtMs = 0L
    /** Car mounts hold the phone inverted, which turns the scene upside down for both the
     *  driver and the detector. The flip rotates the UI (reverse portrait) and adds 180
     *  degrees to the formation path, so both see an upright image. */
    private var driveFlip = false
    @Volatile private var mountOffsetDeg = 0
    /** A "once" row (NAE collect+train) advances on COMPLETION, not on a block boundary,
     *  and that does not break the pairing: the rotation is indexed by the wall clock, so
     *  a phone joins whatever block is live when its prep finishes.
     *  0 idle, 1 collecting, 2 training. */
    @Volatile private var prepStage = 0
    private var prepDone = false
    private lateinit var offloadCheck: CheckBox
    private lateinit var offloadUrl: EditText
    private lateinit var offloadRegime: Spinner
    private lateinit var controlsScroll: ScrollView
    private lateinit var controlsInner: LinearLayout
    private lateinit var btnStart: Button
    private lateinit var btnRotationStart: Button
    private lateinit var btnStop: Button
    private val cellButtons = ArrayList<Button>(16)
    private var selectedCell: Int = 0

    // The X3 Pro temple pad reports absolute touchscreen coordinates that are unrelated to
    // the UI. Treat it as a focus controller, while phones retain normal touch behaviour.
    private val rayNeoTouchpad = Build.MANUFACTURER.equals("RayNeo", ignoreCase = true)
    private var pointerDownX = 0f
    private var pointerDownY = 0f
    private var pointerSwipeHandled = false

    private var mc: MeasurementController? = null
    // TFLite GPU delegates must be created, invoked, and closed on the same thread. Reuse one
    // serial worker across Start presses instead of creating a new Thread for every run.
    private val inferenceExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "SoS-GPU-Worker")
    }
    private val displayIoExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "SoS-DisplayIo").apply { priority = Thread.MIN_PRIORITY }
    }
    @Volatile private var runActive = false
    /** Lap-driven collection (LapSession.kt). Null whenever none is in progress. */
    private var lapSession: LapSession? = null
    /** Digital boost to put back when a lap session ends; negative when none is saved. */
    private var boostBeforeLapSession = -1.0
    /** How many collection blocks the current lap session has opened, minus one. */
    private var lapCollectBlockOrdinal = -1
    private var opened = false
    @Volatile private var resourcesMustClose = false
    @Volatile private var displayConfThreshold = 0.25f

    private class RotationRunSession(val id: Long) {
        @Volatile var cancelled = false
        @Volatile var measurementStarted = false
        @Volatile var logger: MeasurementLogger? = null
        @Volatile var trigger: RotationStartController? = null
        val cleanupClaimed = AtomicBoolean(false)
        val firstPoseRecorded = AtomicBoolean(false)
    }
    @Volatile private var rotationSession: RotationRunSession? = null

    /** Non-null when the Offload checkbox is set, or when the Activity was launched with an
     *  `--es server_url http://...` extra. */
    private var offloader: OffloadClient? = null

    /** `--ez offload_all true`: send every frame regardless of the router, so network
     *  round-trip distributions can be collected from any scene — the router stays silent
     *  on detection-free lab scenes. router.csv still records the real decisions, so
     *  offline analysis can separate force-sent frames from would-have-sent ones. */
    private val offloadAll by lazy { intent.getBooleanExtra("offload_all", false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A screen-off event pauses the Activity on RayNeo. Keep the display awake during an
        // experiment; closing TFLite while Interpreter.run() is active causes a native crash.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_measurement)
        status = findViewById(R.id.statusText)
        preview = findViewById(R.id.previewImage)
        overlay = findViewById(R.id.overlayView)
        cellText = findViewById(R.id.cellText)
        methodGroup = findViewById(R.id.methodGroup)
        cellGridWrap = findViewById(R.id.cellGridWrap)
        cellGrid = findViewById(R.id.cellGrid)
        periodGroup = findViewById(R.id.periodGroup)
        fallbackGroup = findViewById(R.id.fallbackGroup)
        boostGroup = findViewById(R.id.boostGroup)
        proposedSettings = findViewById(R.id.proposedSettings)
        confThreshSpinner = findViewById(R.id.confThreshSpinner)
        val confChoices = (1..10).map { "%.2f".format(it * 0.05) }   // 0.05 .. 0.50
        confThreshSpinner.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_item, confChoices
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        confThreshSpinner.setSelection(confChoices.indexOf("0.05").coerceAtLeast(0))
        sessionSpinner = findViewById(R.id.sessionSpinner)
        val sessionLabels = listOf("Indoor", "Out-Day", "Dim", "Out-Night")
        sessionSpinner.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_item, sessionLabels
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        // "session" extra preselects: indoor|outdoor_day|dim|outdoor_night (plus the older
        // walk/vehicle_* aliases) for adb-driven starts.
        val sessTags = listOf("indoor", "outdoor_day", "dim", "outdoor_night")
        val aliases = mapOf("walk" to "dim",
            "vehicle_night" to "outdoor_night", "vehicle_day" to "outdoor_day")
        intent.getStringExtra("session")?.let { raw2 ->
            val i = sessTags.indexOf(aliases[raw2] ?: raw2)
            if (i >= 0) sessionSpinner.setSelection(i)
        }
        offloadCheck = findViewById(R.id.offloadCheck)
        offloadUrl = findViewById(R.id.offloadUrl)
        offloadRegime = findViewById(R.id.offloadRegime)
        controlsScroll = findViewById(R.id.controls)
        controlsInner = findViewById(R.id.controlsInner)
        btnStart = findViewById(R.id.btnStart)
        btnRotationStart = findViewById(R.id.btnRotationStart)
        btnStop = findViewById(R.id.btnStop)
        // Server-side delay-injection profiles; "clear" means no added delay. New labels must
        // be added to the server's regime table too.
        offloadRegime.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_item,
            listOf("clear", "wifi", "5g", "lte", "congested")
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        sensors = SensorDataManager(this)
        health = DeviceHealthMonitor(this)

        // Edge-to-edge on Android 15+. All four sides, because the nav bar moves to the right
        // edge in landscape on some devices. Base padding is captured once so repeated
        // callbacks don't compound it.
        val basePad = intArrayOf(
            controlsInner.paddingLeft, controlsInner.paddingTop,
            controlsInner.paddingRight, controlsInner.paddingBottom)
        ViewCompat.setOnApplyWindowInsetsListener(controlsInner) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                basePad[0] + bars.left,
                basePad[1] + bars.top,
                basePad[2] + bars.right,
                basePad[3] + bars.bottom)
            insets
        }

        applyOrientation(resources.configuration.orientation)
        // Must precede buildCellGrid so the cell labels render with the right effective ISO.
        grid.digitalBoost = boostFromCheckedId(boostGroup.checkedRadioButtonId)
        buildCellGrid()
        // The two method rows act as ONE mutually-exclusive choice: checking in either
        // group clears the other (guarded against the recursive clear callback). The
        // settings panels key on the radio id, whichever row it lives in.
        val methodGroup2: RadioGroup = findViewById(R.id.methodGroup2)
        var syncingMethodRows = false
        fun applyMethodVisibility(id: Int) {
            cellGridWrap.visibility = if (id == R.id.methodFixed) View.VISIBLE else View.GONE
            // Prop-C shares the period row; its fallback row is grayed because v2 holds
            // on all-zero frames instead of running a fallback metric.
            val proposedFamily = id == R.id.methodProposed || id == R.id.methodContinuous
            proposedSettings.visibility = if (proposedFamily) View.VISIBLE else View.GONE
            val fallbackOn = id == R.id.methodProposed
            for (i in 0 until fallbackGroup.childCount)
                fallbackGroup.getChildAt(i).isEnabled = fallbackOn
            fallbackGroup.alpha = if (fallbackOn) 1f else 0.4f
        }
        methodGroup.setOnCheckedChangeListener { _, id ->
            if (id != -1 && !syncingMethodRows) {
                syncingMethodRows = true; methodGroup2.clearCheck(); syncingMethodRows = false
            }
            if (id != -1) applyMethodVisibility(id)
        }
        methodGroup2.setOnCheckedChangeListener { _, id ->
            if (id != -1 && !syncingMethodRows) {
                syncingMethodRows = true; methodGroup.clearCheck(); syncingMethodRows = false
            }
            if (id != -1) applyMethodVisibility(id)
        }
        // Initial state mirrors the default-checked methodProposed radio.
        cellGridWrap.visibility = View.GONE
        proposedSettings.visibility = View.VISIBLE

        // v2 (Prop-C) has no boost concept, and the v1 arms must keep the 2x default their
        // collected runs used — so the row is frozen rather than removed: a stray tap can
        // no longer fork comparability mid-experiment.
        for (i in 0 until boostGroup.childCount) boostGroup.getChildAt(i).isEnabled = false
        boostGroup.alpha = 0.4f
        // Grid is mutated in place; the rebuild refreshes the effective-ISO labels.
        boostGroup.setOnCheckedChangeListener { _, id ->
            grid.digitalBoost = boostFromCheckedId(id)
            buildCellGrid()
            updateProfileSummary()
        }

        btnStart.setOnClickListener { startSelectedMethod() }
        btnRotationStart.setOnClickListener { startRotationSelectedMethod() }
        findViewById<Button>(R.id.btnVerify).setOnClickListener {
            start("verify") { mc!!.runVerify(false, ::post, ::showFrame) }
        }
        findViewById<Button>(R.id.btnVerifyProbe).setOnClickListener {
            start("verifyprobe") { mc!!.runVerifyProbe(false, ::post, ::showFrame) }
        }
        findViewById<Button>(R.id.btnBench).setOnClickListener {
            start("ab_bench") { mc!!.runAbBench(::post) }
        }
        findViewById<Button>(R.id.btnIsoDiag).setOnClickListener {
            // `--ez v2_diag true` repurposes the button as the v2 lattice reachability audit.
            if (intent.getBooleanExtra("v2_diag", false))
                start("v2_diag") { mc!!.runV2LatticeDiag(::post) }
            else start("iso_diag") { mc!!.runIsoDiag(onStatus = ::post) }
        }
        takeLaunchOverrides()
        // Headless hook for adb-driven devices whose display sleeps when not worn (the
        // glass): `--ez v2_diag true --ez autorun true` presses IsoDiag after layout.
        if (intent.getBooleanExtra("autorun", false)) {
            findViewById<Button>(R.id.btnIsoDiag).postDelayed(
                { findViewById<Button>(R.id.btnIsoDiag).performClick() }, 1500)
        }
        // Stand-alone camera timing probe (IntervalDiag.kt): `--ez interval_diag true`.
        // It opens camera 0 by itself, so it is only for a launch that starts no run.
        if (intent.getBooleanExtra("interval_diag", false)) {
            Thread({ IntervalDiag.run(applicationContext, ::post) }, "interval-diag").start()
        }
        // Gap-free read probe (GaplessDiag.kt): `--ez gapless_diag true`; same rule.
        if (intent.getBooleanExtra("gapless_diag", false)) {
            Thread({ GaplessDiag.run(applicationContext, ::post) }, "gapless-diag").start()
        }
        // Lap-driven collection: the one control this experiment adds. From adb,
        // `--ez lap_session true` presses it, with optional `--es lap_plan
        // "collect:10,ae_phone:3"`, `--ei lap_repeats 3`, `--ei lap_leadin_s 90` and
        // `--ei lap_fallback_s 15`. A single collection run without laps is
        // `--es run collect`, optionally capped with `--ei collect_frames N`.
        findViewById<Button>(R.id.btnLapSession).setOnClickListener { toggleLapSession() }
        if (intent.getBooleanExtra("lap_session", false)) {
            btnStart.postDelayed({ if (lapSession == null) toggleLapSession() }, 1500)
        }
        // General adb runner — the glass touchpad intercepts injected taps (focus-first
        // model), so radio selection is impossible from the host; this starts any method
        // directly: `--es run <method>` with optional `--es period 5|10|20`. Methods:
        // fixed | ae_phone | ae_cust | aeq_phone | aeq_cust | proposed | prop_c |
        // physsweep | shin_nm | nae | collect_nae. Stop a run with KEYCODE_BACK.
        intent.getStringExtra("period")?.let { p ->
            val pid = when (p) {
                "10" -> R.id.period10; "20" -> R.id.period20; else -> R.id.period5
            }
            findViewById<android.widget.RadioButton>(pid).isChecked = true
        }
        intent.getStringExtra("run")?.let { cmd ->
            btnStart.postDelayed({ launchRunnerCommand(cmd) }, 1500)
        }
        // Two-phone driving playlist: `--es playlist "prop_c,ae_cust,..."` with optional
        // `--es block_s 120`. The block INDEX derives from the wall clock
        // (floor(epoch/block_s) mod len), so two phones stay aligned with no
        // communication, and a rebooted phone rejoins at the right position. Give the
        // second phone the role-swapped list so Prop-C is always running on one side.
        // KEYCODE_BACK (or Stop) cancels the playlist; block-boundary stops do not.
        intent.getStringExtra("playlist")?.let { pl ->
            val tags = pl.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (tags.isNotEmpty()) {
                // An adb playlist replaces the authored rows, taking the rest of each
                // row's settings from whatever the screen currently holds.
                val base = captureRow()
                rows = tags.map { base.copy(method = it) }.toMutableList()
                saveRows()
                playlistBlockS = (intent.getStringExtra("block_s")?.toIntOrNull() ?: 120)
                    .coerceAtLeast(20)
                playlistActive = true
                btnStart.postDelayed({ startPlaylistBlock() }, 1500)
            }
        }
        findViewById<Button>(R.id.btnExp21).setOnClickListener {
            start("exp2_1") { mc!!.runExp21DirectTensor(::post) }
        }
        findViewById<Button>(R.id.btnExp22).setOnClickListener {
            start("exp2_2") { mc!!.runExp22DecodeOptimization(::post) }
        }
        findViewById<Button>(R.id.btnExp23).setOnClickListener {
            start("exp2_3") { mc!!.runExp23Coco5Comparison(::post) }
        }
        findViewById<Button>(R.id.btnExp3).setOnClickListener {
            start("exp3") { mc!!.runExp3IntegratedVsExp1A(::post) }
        }
        findViewById<Button>(R.id.btnExp4).setOnClickListener {
            start("exp4") { mc!!.runExp4PipelinedPeriods(::post) }
        }
        findViewById<Button>(R.id.btnExp51).setOnClickListener {
            start("exp5_1") { mc!!.runExp51CaptureGuardComparison(::post) }
        }
        findViewById<Button>(R.id.btnNaeCollect).setOnClickListener { collectNaeData() }
        findViewById<Button>(R.id.btnNaeTrain).setOnClickListener { trainNaePool() }
        btnStop.setOnClickListener { stopMeasurement() }

        // The focus-first tap model exists for the glass touchpad only. On a phone it makes
        // every control need TWO taps (focusableInTouchMode: first tap focuses, second
        // clicks), so the phone profile keeps plain touch and hides the glass-only chrome.
        if (rayNeoTouchpad) configureStaticTouchpadControls()
        // Glass simple mode: the temple touchpad makes the dense phone UI painful, so on
        // RayNeo everything except the essentials is hidden — session, the two method
        // rows, the proposed settings (period/fallback), Start/Stop and the NAE row.
        // Hidden buttons stay functional for intent-driven autorun (performClick works
        // on GONE views), and offload still arrives via the server_url extra.
        if (rayNeoTouchpad) {
            val keep = setOf<View>(
                findViewById(R.id.touchpadHelp),
                findViewById(R.id.sessionRow),
                methodGroup, findViewById(R.id.methodGroup2),
                proposedSettings,
                btnStart.parent as View,
                findViewById<Button>(R.id.btnNaeCollect).parent as View
            )
            for (i in 0 until controlsInner.childCount) {
                val c = controlsInner.getChildAt(i)
                if (c !in keep) c.visibility = View.GONE
            }
        }
        else configurePhoneProfileUi()
        wireDriveMode()
        // Start is the safest useful default: one tap launches the default Proposed mode.
        if (rayNeoTouchpad) btnStart.post { btnStart.requestFocus() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1)
        } else if (intent.getStringExtra("autorun") == "ab_visual") {
            // Headless-friendly entry point for a controlled paired A/B capture. Posting keeps
            // initialization ordered after the Activity has finished wiring its views.
            btnStart.post {
                start("ab_visual") { mc!!.runAbVisual(::post) }
            }
        } else if (intent.getStringExtra("autorun") == "exp2_1") {
            btnStart.post {
                start("exp2_1") { mc!!.runExp21DirectTensor(::post) }
            }
        } else if (intent.getStringExtra("autorun") == "exp2_2") {
            btnStart.post {
                start("exp2_2") { mc!!.runExp22DecodeOptimization(::post) }
            }
        } else if (intent.getStringExtra("autorun") == "exp2_3") {
            btnStart.post {
                start("exp2_3") { mc!!.runExp23Coco5Comparison(::post) }
            }
        } else if (intent.getStringExtra("autorun") == "exp3") {
            btnStart.post {
                start("exp3") { mc!!.runExp3IntegratedVsExp1A(::post) }
            }
        } else if (intent.getStringExtra("autorun") == "exp4") {
            btnStart.post {
                start("exp4") { mc!!.runExp4PipelinedPeriods(::post) }
            }
        } else if (intent.getStringExtra("autorun") == "exp5_1") {
            btnStart.post {
                start("exp5_1") { mc!!.runExp51CaptureGuardComparison(::post) }
            }
        } else if (intent.getStringExtra("autorun") == "exp5_1_a") {
            btnStart.post {
                start("exp5_1_a") { mc!!.runExp51CaptureGuardComparison(::post, strategyFilter = "A_") }
            }
        } else if (intent.getStringExtra("autorun") == "exp5_1_b") {
            btnStart.post {
                start("exp5_1_b") { mc!!.runExp51CaptureGuardComparison(::post, strategyFilter = "B_") }
            }
        } else if (intent.getStringExtra("autorun") == "exp5_1_c") {
            btnStart.post {
                start("exp5_1_c") { mc!!.runExp51CaptureGuardComparison(::post, strategyFilter = "C_") }
            }
        } else if (intent.getStringExtra("autorun") == "exp5_1_1_k") {
            btnStart.post {
                start("exp5_1_1_k") { mc!!.runExp511NoGuardDecode(::post, "B_kotlin") }
            }
        } else if (intent.getStringExtra("autorun") == "exp5_1_1_n") {
            btnStart.post {
                start("exp5_1_1_n") { mc!!.runExp511NoGuardDecode(::post, "D_native_neon") }
            }
        } else if (intent.getStringExtra("autorun") == "exp5_1_1_p") {
            btnStart.post {
                start("exp5_1_1_p") { mc!!.runExp511NoGuardDecode(::post, "E_native_preview") }
            }
        } else if (intent.getStringExtra("autorun") == "exp5_2_o") {
            btnStart.post {
                start("exp5_2_o") { mc!!.runExp52CaptureMode(::post, "A_on_demand") }
            }
        } else if (intent.getStringExtra("autorun") == "exp5_2_c") {
            btnStart.post {
                start("exp5_2_c") { mc!!.runExp52CaptureMode(::post, "B_continuous_ring") }
            }
        } else if (intent.getStringExtra("autorun") == "exp5_3") {
            btnStart.post {
                start("exp5_3") { mc!!.runExp53RawFormats(::post) }
            }
        } else if (intent.getStringExtra("autorun") == "exp5_3_s") {
            btnStart.post {
                start("exp5_3_s") {
                    mc!!.runExp53RawFormats(::post, android.graphics.ImageFormat.RAW_SENSOR)
                }
            }
        } else if (intent.getStringExtra("autorun") == "exp5_3_10") {
            btnStart.post {
                start("exp5_3_10") {
                    mc!!.runExp53RawFormats(::post, android.graphics.ImageFormat.RAW10)
                }
            }
        } else if (intent.getStringExtra("autorun") == "exp5_4") {
            btnStart.post {
                start("exp5_4") { mc!!.runExp54CpuContention(::post) }
            }
        } else if (intent.getStringExtra("autorun") == "exp5_4_best") {
            btnStart.post {
                start("exp5_4_best") {
                    mc!!.runExp54CpuContention(::post, confirmationOnly = true)
                }
            }
        } else if (intent.getStringExtra("autorun") == "exp5_5") {
            btnStart.post {
                start("exp5_5") { mc!!.runExp55FinalAdaptiveP5(::post) }
            }
        } else if (intent.getStringExtra("autorun") == "exp5_6") {
            btnStart.post {
                start("exp5_6") {
                    mc!!.runExp55FinalAdaptiveP5(::post, persistentFastCapture = true)
                }
            }
        } else if (intent.getStringExtra("autorun") == "exp5_6_d2") {
            btnStart.post {
                start("exp5_6_d2") {
                    mc!!.runExp55FinalAdaptiveP5(::post, persistentFastCapture = true,
                        decodeThreads = 2, formationThreads = 4)
                }
            }
        } else if (intent.getStringExtra("autorun") == "exp5_6_d3") {
            btnStart.post {
                start("exp5_6_d3") {
                    mc!!.runExp55FinalAdaptiveP5(::post, persistentFastCapture = true,
                        decodeThreads = 3, formationThreads = 4)
                }
            }
        } else if (intent.getStringExtra("autorun") == "exp5_7") {
            btnStart.post {
                start("exp5_7") {
                    mc!!.runExp55FinalAdaptiveP5(::post, persistentFastCapture = true,
                        decodeThreads = 4, formationThreads = 4,
                        deepSinglePrefetch = true)
                }
            }
        } else if (intent.getStringExtra("autorun") == "exp6") {
            btnStart.post {
                start("exp6") {
                    mc!!.runExp55FinalAdaptiveP5(::post, persistentFastCapture = true,
                        decodeThreads = 4, formationThreads = 4,
                        deepSinglePrefetch = true, integratedOptimizations = true)
                }
            }
        }

    }

    /** Reflows the root between portrait (preview above controls) and landscape (controls
     *  left, preview right) by swapping LayoutParams rather than reinflating — reinflating
     *  would tear down the camera and detector. View objects keep their state across the
     *  detach and reattach. */
    private fun applyOrientation(orientation: Int) {
        val root = findViewById<LinearLayout>(R.id.rootLayout)
        // Grab the children before removeAllViews; afterwards findViewById returns null.
        val previewWrap = findViewById<android.widget.FrameLayout>(R.id.previewWrap)
        val controls = findViewById<android.widget.ScrollView>(R.id.controls)
        root.removeAllViews()
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            root.orientation = LinearLayout.HORIZONTAL
            root.addView(controls, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 2f))
            root.addView(previewWrap, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 3f))
        } else {
            root.orientation = LinearLayout.VERTICAL
            root.addView(previewWrap, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            root.addView(controls, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation(newConfig.orientation)
        // System bars move on rotation, so re-fire the inset listener to follow them.
        ViewCompat.requestApplyInsets(findViewById(R.id.controlsInner))
    }

    private fun boostFromCheckedId(id: Int): Double = when (id) {
        R.id.boost1x -> 1.0
        R.id.boost4x -> 4.0
        else -> 2.0
    }

    private fun effectiveIso(gainIdx: Int): Int =
        (grid.gains[gainIdx] * grid.digitalBoost).toInt()

    /** Fixed-mode cell selector: rows are gain, columns are shutter. Labels show EFFECTIVE
     *  ISO so the number matches the brightness the formed cell will actually have. Call
     *  again after digitalBoost changes to refresh them. */
    private fun buildCellGrid() {
        cellGrid.removeAllViews()
        cellButtons.clear()
        // Overrides the XML's 3×3 hint so wider grids get enough rows and columns.
        cellGrid.rowCount = grid.nGain
        cellGrid.columnCount = grid.nShutter
        for (gi in 0 until grid.nGain) {
            for (sj in 0 until grid.nShutter) {
                val cell = grid.cell(gi, sj)
                val effIso = effectiveIso(gi); val expUs = grid.exposuresUs[sj]
                // Two lines, no unit words — anything longer stops fitting on a phone.
                val expMs = expUs / 1000.0
                val expLabel = if (expMs >= 1.0) "%.0fms".format(expMs) else "%.1fms".format(expMs)
                val b = Button(this).apply {
                    text = "$effIso\n$expLabel"
                    textSize = 10f
                    minWidth = 0; minimumWidth = 0
                    setPadding(0, 0, 0, 0)
                    setOnClickListener { setSelectedCell(cell) }
                }
                if (rayNeoTouchpad) configureTouchpadControl(b)
                val lp = GridLayout.LayoutParams().apply {
                    width = 0; height = GridLayout.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(sj, 1f); rowSpec = GridLayout.spec(gi, 1f)
                    setMargins(2, 2, 2, 2)
                }
                cellGrid.addView(b, lp)
                cellButtons.add(b)
            }
        }
        setSelectedCell(0)
    }

    private fun setSelectedCell(cell: Int) {
        selectedCell = cell
        val idle = ContextCompat.getColor(this, R.color.cell_unselected)
        val active = ContextCompat.getColor(this, R.color.cell_selected)
        for (b in cellButtons) b.setBackgroundColor(idle)
        cellButtons.getOrNull(cell)?.setBackgroundColor(active)
    }

    /** nae-bin-v1 weights: a freshly trained file in the app files dir wins over the
     *  bundled asset, so Train NAE takes effect without a rebuild. */
    private fun loadNaeWeights(): ByteArray? =
        runCatching {
            java.io.File(getExternalFilesDir(null), "nae_hist_scalar_3x3.bin").readBytes()
        }.recoverCatching {
            assets.open("nae_hist_scalar_3x3.bin").use { it.readBytes() }
        }.getOrNull()

    private fun sessionTag(): String = when (sessionSpinner.selectedItemPosition) {
        1 -> "outdoor_day"; 2 -> "dim"; 3 -> "outdoor_night"; else -> "indoor"
    }

    /** The rig the camera rides. It names the run directory and is recorded in the
     *  manifest, so a mixed pile of runs can be split by platform without guessing. */
    private val platformTags = listOf("limo", "car", "rayneo")
    private var platformIdx = 0
    private fun platformTag() = platformTags[platformIdx.coerceIn(0, 2)]

    /** Stable per-install id. Two units of the same model are otherwise indistinguishable in
     *  the output — car_v1's roles had to be recovered from a fuel-gauge quantisation fluke. */
    @Suppress("HardwareIds")
    private fun deviceId(): String = runCatching {
        android.provider.Settings.Secure.getString(contentResolver,
            android.provider.Settings.Secure.ANDROID_ID) ?: ""
    }.getOrDefault("")

    /** Snapshot of the two-phone pairing state, taken on the UI thread: the worker that
     *  builds the controller must not read driveRole/rows while the operator can edit them. */
    private class PairingSnap(val role: String, val deviceId: String, val blockS: Int,
                              val blockIdx: Long, val repeats: Int, val rows: String)

    private fun pairingSnap() = PairingSnap(
        role = if (platformTag() == "car") driveRole else "",
        deviceId = deviceId(),
        blockS = if (playlistActive) playlistBlockS else -1,
        blockIdx = if (playlistActive) currentBlockIndex() else -1L,
        repeats = if (playlistActive) playlistRepeats else -1,
        rows = if (playlistActive) rows.joinToString(";") { it.encode() } else "")

    private fun MeasurementController.applyPairing(p: PairingSnap) {
        driveRole = p.role; deviceId = p.deviceId; blockSeconds = p.blockS
        blockIndex = p.blockIdx; playlistRepeats = p.repeats; playlistRows = p.rows
    }

    private fun naeDatasetFile() = java.io.File(getExternalFilesDir(null), "nae_dataset.bin")

    /** Neural-AE data collection only: one press appends up to [NAE_TARGET_SAMPLES] to the
     *  pool. Collection is a measurement pass like any other (Stop ends it early and keeps
     *  whatever reached the pool), so it goes through [start].
     *
     *  Training is a separate button: a multi-condition protocol (one collection pass per
     *  lighting) trains once on the pooled set at the end, instead of a throwaway
     *  retraining after every pass.
     *
     *  Budget: [NAE_TARGET_SAMPLES] samples at [NAE_CELLS_PER_STEP] per step is ~100
     *  full-grid probe steps, and a probe step measured ~0.5 s on the S25 — about a
     *  minute of pointing the phone at traffic. The frame cap bounds a scene that never
     *  detects anything. */
    private fun collectNaeData() {
        val already = NaeDataset.count(naeDatasetFile())
        start(if (rayNeoTouchpad) "nae_collect" else "nae_collect_v2") {
            post("NAE collect: pool has $already samples — collecting…")
            // Phones collect on the v2 lattice (physical 15-capture surface); the glass
            // keeps the v1 single-burst grid collector.
            val added = if (rayNeoTouchpad)
                mc!!.runNaeCollect(naeDatasetFile(), NAE_TARGET_SAMPLES, NAE_MAX_FRAMES,
                    NAE_CELLS_PER_STEP, ::post, ::onFrameWithOffload)
            else mc!!.runNaeCollectV2(naeDatasetFile(), NAE_TARGET_SAMPLES, NAE_MAX_FRAMES,
                NAE_CELLS_PER_STEP, ::post, ::onFrameWithOffload)
            val total = NaeDataset.count(naeDatasetFile())
            post(if (total >= NAE_MIN_TRAIN)
                "NAE collect: +$added → $total samples — collect the next condition, or Train"
            else "NAE collect: +$added → $total samples, need $NAE_MIN_TRAIN — " +
                "press again on a scene with vehicles")
        }
    }

    private fun naeArchiveFiles(): List<java.io.File> =
        (getExternalFilesDir(null)?.listFiles { f ->
            f.name.startsWith("nae_dataset_trained_") && f.name.endsWith(".bin")
        } ?: emptyArray()).sortedBy { it.name }

    /** Train NAE asks WHAT to train on: only the samples collected since the last
     *  training (per-condition models, e.g. normal-only) or everything ever collected
     *  (the pooled model) — the two arms of the in-sample / OOD protocol, selectable at
     *  the button with no file surgery. Every successful training archives the current
     *  pool and saves a timestamped weights copy, so earlier models stay recoverable. */
    private fun trainNaePool() {
        if (runActive) { post("busy — stop first"); return }
        val newN = NaeDataset.count(naeDatasetFile())
        val archN = naeArchiveFiles().sumOf { NaeDataset.count(it) }
        if (newN + archN < NAE_MIN_TRAIN) {
            post("NAE train: $newN new + $archN archived samples, " +
                "need $NAE_MIN_TRAIN — Collect first")
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Train NAE")
            .setMessage("New since last training: $newN\nCumulative (incl. archives): ${newN + archN}")
            .setPositiveButton("New only ($newN)") { _, _ -> doTrainNae(useAll = false) }
            .setNegativeButton("All samples (${newN + archN})") { _, _ -> doTrainNae(useAll = true) }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun doTrainNae(useAll: Boolean, onDone: (() -> Unit)? = null) {
        if (runActive) { post("busy — stop first"); onDone?.invoke(); return }
        runActive = true
        inferenceExecutor.execute {
            try {
                val samples = ArrayList<com.example.activeperception.acquire.NaeTrainer.Sample>()
                if (useAll) for (a in naeArchiveFiles()) samples += NaeDataset.load(a)
                samples += NaeDataset.load(naeDatasetFile())
                if (samples.size < NAE_MIN_TRAIN) {
                    post("NAE train: ${samples.size} samples in this scope, need $NAE_MIN_TRAIN")
                    return@execute
                }
                val scope = if (useAll) "all" else "new-only"
                post("NAE train ($scope): ${samples.size} samples…")
                val bin = com.example.activeperception.acquire.NaeTrainer().train(samples) { ep, tr, va ->
                    if (ep % 5 == 0) post("NAE train: epoch $ep loss ${"%.4f".format(tr)} val ${"%.4f".format(va)}")
                }
                val ts = System.currentTimeMillis()
                java.io.File(getExternalFilesDir(null), "nae_hist_scalar_3x3.bin").writeBytes(bin)
                java.io.File(getExternalFilesDir(null), "nae_w_${scope}_$ts.bin").writeBytes(bin)
                if (NaeDataset.count(naeDatasetFile()) > 0) {
                    naeDatasetFile().renameTo(java.io.File(getExternalFilesDir(null),
                        "nae_dataset_trained_$ts.bin"))
                }
                post("NAE ready ($scope, ${samples.size} samples) — weights active + " +
                    "copy nae_w_${scope}_$ts.bin; pool archived")
            } catch (e: Exception) {
                post("NAE train failed: ${e.message}")
            } finally {
                runActive = false
                // Fires on every exit path, so a too-small pool or a failure still hands
                // the playlist back instead of stranding it in the prep stage.
                onDone?.invoke()
            }
        }
    }

    private fun startSelectedMethod() {
        // GT-reference checkbox removed from UI; hardcoded false. Re-add a flag plumb
        // if a GT pass mode is needed (or a long-press gesture on Start to toggle).
        val gtRef = false
        // Second method row (baselines) — mutually exclusive with methodGroup, so at most
        // one of the two groups has a checked id.
        when (findViewById<RadioGroup>(R.id.methodGroup2).checkedRadioButtonId) {
            R.id.methodContinuous -> {
                val period = when (periodGroup.checkedRadioButtonId) {
                    R.id.period10 -> 10
                    R.id.period20 -> 20
                    else -> 5
                }
                start("proposed_v2_p$period") {
                    mc!!.runContinuousProposed(period, NO_FRAME_CAP, ::post, ::onFrameWithOffload)
                }
                return
            }
            R.id.methodPhysSweep -> {
                // Phones search the v2 lattice — the same 5x5 space Prop-C lives on —
                // so the comparison isolates the method, not the search space. The glass
                // keeps its device grid.
                val v2 = !rayNeoTouchpad
                val n = if (v2) 25 else grid.nGain * grid.nShutter
                start(if (v2) "physsweep_v2_full_h$n" else "physsweep_full_h$n") {
                    mc!!.runPhysSweep(true, n, NO_FRAME_CAP, ::post, v2 = v2,
                        onFrame = ::onFrameWithOffload)
                }
                return
            }
            R.id.methodShinNM -> {
                val v2 = !rayNeoTouchpad
                start(if (v2) "shin_nm_v2" else "shin_nm") {
                    mc!!.runShinNM("restart_int", NO_FRAME_CAP, ::post, v2 = v2,
                        onFrame = ::onFrameWithOffload)
                }
                return
            }
            R.id.methodNeuralAe -> {
                val w = loadNaeWeights()
                if (w == null) post("NAE weights missing — Train NAE below, or push nae_hist_scalar_3x3.bin to the app files dir")
                else {
                    val v2 = !rayNeoTouchpad
                    start(if (v2) "nae_v2" else "nae") {
                        mc!!.runNeuralAe(w, NO_FRAME_CAP, ::post, v2 = v2,
                            onFrame = ::onFrameWithOffload)
                    }
                }
                return
            }
            R.id.methodProposed -> {
                val period = when (periodGroup.checkedRadioButtonId) {
                    R.id.period10 -> 10
                    R.id.period20 -> 20
                    else -> 5
                }
                val fallback = when (fallbackGroup.checkedRadioButtonId) {
                    R.id.fbLaplacian -> FallbackMetric.LAPLACIAN_VAR
                    R.id.fbTenengrad -> FallbackMetric.TENENGRAD_NORM
                    R.id.fbCreteRoffet -> FallbackMetric.CRETE_ROFFET
                    R.id.fbSafeCell -> FallbackMetric.SAFE_CELL
                    else -> FallbackMetric.ENTROPY
                }
                start("proposed_p${period}_${fallback.tag()}") {
                    mc!!.runFinalProposed(period, NO_FRAME_CAP, fallback,
                        onStatus = ::post, onFrame = ::onFrameWithOffload)
                }
                return
            }
        }
        // AE strategies are flattened into distinct method radios — the arm is fully
        // named by the id, no nested strategy group to consult.
        when (methodGroup.checkedRadioButtonId) {
            R.id.methodFixed -> {
                val (gi, sj) = grid.indices(selectedCell)
                start("fixed_g${grid.gains[gi]}_e${grid.exposuresUs[sj]}") {
                    mc!!.runFixed(gi, sj, NO_FRAME_CAP, gtRef, ::post, ::onFrameWithOffload)
                }
            }
            R.id.methodAePhone -> start("ae_phone") {
                // presetRunCap: the temporary ae_collect step stops itself after its frames.
                mc!!.runAe(presetRunCap ?: NO_FRAME_CAP, gtRef, AeStrategy.PHONE, ::post,
                    ::onFrameWithOffload)
            }
            R.id.methodAeCustom -> start("ae_custom") {
                mc!!.runAe(NO_FRAME_CAP, gtRef, AeStrategy.CUSTOM_BRIGHTNESS, ::post, ::onFrameWithOffload)
            }
            R.id.methodAeQuantPhone -> start("ae_paired_phone") {
                mc!!.runAeQuant(NO_FRAME_CAP, gtRef, AeStrategy.PHONE, ::post, ::onFrameWithOffload)
            }
            R.id.methodAeQuantCustom -> start("ae_paired_custom") {
                mc!!.runAeQuant(NO_FRAME_CAP, gtRef, AeStrategy.CUSTOM_BRIGHTNESS, ::post, ::onFrameWithOffload)
            }
            else -> post("pick a method")
        }
    }

    /** The ordinary Start path above is intentionally unchanged. This separate path snapshots
     *  the selected method, preloads camera/GPU, learns the oscillation, then releases exactly
     *  one run at the learned midpoint while moving in +gyro-Y. */
    private fun startRotationSelectedMethod() {
        val gtRef = false
        // Rotation start supports the primary methods; the method id may sit in either
        // radio row (Proposed lives in row 2 next to the baselines, which are excluded).
        val selectedId = methodGroup.checkedRadioButtonId.takeIf { it != -1 }
            ?: findViewById<RadioGroup>(R.id.methodGroup2).checkedRadioButtonId
        when (selectedId) {
            R.id.methodFixed -> {
                val (gi, sj) = grid.indices(selectedCell)
                val expUs = grid.exposuresUs[sj]
                startRotation("fixed_g${grid.gains[gi]}_e$expUs", expUs, grid.gains[gi], 1,
                    expUs * 1_000L / 2L) {
                    mc!!.runFixed(gi, sj, NO_FRAME_CAP, gtRef, ::post, ::onFrameWithOffload)
                }
            }
            R.id.methodAePhone, R.id.methodAeCustom -> {
                val ae = if (selectedId == R.id.methodAePhone) AeStrategy.PHONE
                         else AeStrategy.CUSTOM_BRIGHTNESS
                startRotation("ae_${ae.tag()}", grid.fastestExposureUs, grid.baseGain, 1,
                    grid.fastestExposureUs * 1_000L / 2L) {
                    mc!!.runAe(NO_FRAME_CAP, gtRef, ae, ::post, ::onFrameWithOffload)
                }
            }
            R.id.methodAeQuantPhone, R.id.methodAeQuantCustom -> {
                val ae = if (selectedId == R.id.methodAeQuantPhone) AeStrategy.PHONE
                         else AeStrategy.CUSTOM_BRIGHTNESS
                startRotation("ae_paired_${ae.tag()}", grid.fastestExposureUs, grid.baseGain, 1,
                    grid.fastestExposureUs * 1_000L / 2L) {
                    mc!!.runAeQuant(NO_FRAME_CAP, gtRef, ae, ::post, ::onFrameWithOffload)
                }
            }
            R.id.methodProposed -> {
                val period = when (periodGroup.checkedRadioButtonId) {
                    R.id.period10 -> 10
                    R.id.period20 -> 20
                    else -> 5
                }
                val fallback = when (fallbackGroup.checkedRadioButtonId) {
                    R.id.fbLaplacian -> FallbackMetric.LAPLACIAN_VAR
                    R.id.fbTenengrad -> FallbackMetric.TENENGRAD_NORM
                    R.id.fbCreteRoffet -> FallbackMetric.CRETE_ROFFET
                    R.id.fbSafeCell -> FallbackMetric.SAFE_CELL
                    else -> FallbackMetric.ENTROPY
                }
                val halfWindowNs = ((grid.maxBurst - 1) * RAYNEO_FRAME_PERIOD_NS +
                    grid.fastestExposureUs * 1_000L) / 2L
                startRotation("proposed_p${period}_${fallback.tag()}",
                    grid.fastestExposureUs, grid.baseGain, grid.maxBurst, halfWindowNs) {
                    mc!!.runFinalProposed(period, NO_FRAME_CAP, fallback,
                        onStatus = ::post, onFrame = ::onFrameWithOffload)
                }
            }
            else -> post("pick a method")
        }
    }

    private fun startRotation(
        modeTag: String,
        preflightExposureUs: Int,
        preflightIso: Int,
        preflightBurst: Int,
        firstWindowHalfNs: Long,
        block: () -> Unit
    ) {
        if (runActive) { post("busy — stop first"); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) { post("no camera permission"); return }

        // Snapshot every UI option before asynchronous preload/learning begins.
        val offUrl = (if (offloadCheck.isChecked) offloadUrl.text.toString().trim().ifBlank { null }
                      else null) ?: intent.getStringExtra("server_url")
        val offRegime = (offloadRegime.selectedItem?.toString() ?: "clear").ifBlank { "clear" }
        val selectConf = confThreshSpinner.selectedItem?.toString()?.toFloatOrNull() ?: 0.25f
        displayConfThreshold = selectConf
        val sess = sessionTag()
        val plat = platformTag()
        val pairSnap = pairingSnap()
        applyMountOffset()
        val session = RotationRunSession(System.currentTimeMillis())
        rotationSession = session
        runActive = true

        inferenceExecutor.execute {
            try {
                if (resourcesMustClose) {
                    closeResources(); resourcesMustClose = false
                }
                if (session.cancelled) return@execute
                if (!opened) {
                    post("ROTATION PRELOAD · opening camera…")
                    raw = RawSensorCapturer(this); raw.open()
                    raw.mountOffsetDeg = mountOffsetDeg
                    post("ROTATION PRELOAD · loading YOLO GPU batches…")
                    detector = createProfiledDetector()
                    opened = true
                }
                if (session.cancelled) return@execute

                post("ROTATION PRELOAD · warming GPU B=1/3/9…")
                detector.warmUpAllBatches()
                if (session.cancelled) return@execute

                // First pass settles the requested physical state. The second pass measures
                // request-to-first-sensor latency without the 12-frame setting guard.
                post("ROTATION PRELOAD · settling camera and AWB…")
                raw.capture(preflightExposureUs, preflightIso, preflightBurst)
                val submittedNs = SystemClock.elapsedRealtimeNanos()
                raw.capture(preflightExposureUs, preflightIso, preflightBurst)
                val firstSensorNs = raw.lastMeta.firstOrNull()?.timestamp ?: submittedNs
                val requestLatencyNs = (firstSensorNs - submittedNs).coerceAtLeast(0L)
                if (session.cancelled) return@execute

                val runName = "run_${plat}_rotation_${modeTag}_${session.id}"
                val logger = MeasurementLogger(this, runName)
                session.logger = logger
                val mcLocal = MeasurementController(raw, detector, grid, sensors, logger, health, selectConf)
                mcLocal.setV2Session(sess)
                mcLocal.platformTag = plat
                mcLocal.applyPairing(pairSnap)
                mc = mcLocal
                offloader = offUrl?.let {
                    OffloadClient(it, logger.dir, offRegime) { mcLocal.lastFrameIdx }
                        .apply { warmConnection() }
                }

                val triggerController = RotationStartController(this, logger.dir, ::post)
                session.trigger = triggerController
                triggerController.startLearning { profile ->
                    if (session.cancelled || rotationSession !== session) return@startLearning
                    // Include worker dispatch/startPass allowance. The camera part is measured
                    // on this exact session and the exposure-window term is method-specific.
                    val leadNs = requestLatencyNs + firstWindowHalfNs + START_DISPATCH_ALLOWANCE_NS
                    post("ROTATION · ${"%.1f".format(profile.rangeDeg)}° learned · arming start")
                    triggerController.arm(leadNs) { trigger ->
                        if (session.cancelled || rotationSession !== session) return@arm
                        session.measurementStarted = true
                        File(logger.dir, "rotation_start.json").writeText(JSONObject().apply {
                            put("mode", modeTag)
                            put("range_deg", profile.rangeDeg)
                            put("half_period_ms", profile.halfPeriodMs)
                            put("direction", "gyro_y_positive")
                            put("preflight_exposure_us", preflightExposureUs)
                            put("preflight_iso", preflightIso)
                            put("preflight_burst", preflightBurst)
                            put("request_latency_ns", requestLatencyNs)
                            put("first_window_half_ns", firstWindowHalfNs)
                            put("dispatch_allowance_ns", START_DISPATCH_ALLOWANCE_NS)
                            put("trigger_sensor_timestamp_ns", trigger.sensorTimestampNs)
                            put("trigger_gyro_y_rad_s", trigger.gyroY)
                            put("trigger_center_distance_deg", trigger.centerDistanceDeg)
                            put("predicted_time_to_center_ns", trigger.predictedTimeToCenterNs)
                            put("lead_ns", trigger.leadNs)
                        }.toString(2))
                        post("ROTATION · trigger · measurement starting")
                        inferenceExecutor.execute {
                            if (session.cancelled) {
                                runCatching { triggerController.close() }
                                session.trigger = null
                                logger.close(); finishRotationSession(session)
                                return@execute
                            }
                            try {
                                block()
                                val above = mcLocal.detectionTotalAboveThresh
                                val floor = mcLocal.detectionTotalAtFloor
                                val frames = mcLocal.totalFramesLogged
                                post("rotation done — detections: $above above thresh ($floor at floor), $frames frames")
                            } catch (error: Throwable) {
                                post("rotation error: ${error.message}")
                            } finally {
                                runCatching { session.trigger?.close() }
                                session.trigger = null
                                logger.close()
                                finishRotationSession(session)
                            }
                        }
                    }
                }
            } catch (error: Throwable) {
                post("rotation preload error: ${error.message}")
                cleanupPendingRotationSession(session)
            } finally {
                if (session.cancelled && !session.measurementStarted) {
                    cleanupPendingRotationSession(session)
                }
            }
        }
    }

    private fun finishRotationSession(session: RotationRunSession) {
        if (rotationSession === session) rotationSession = null
        runActive = false
    }

    private fun cleanupPendingRotationSession(session: RotationRunSession) {
        if (!session.cleanupClaimed.compareAndSet(false, true)) return
        runCatching { session.trigger?.close() }
        session.trigger = null
        runCatching { session.logger?.close() }
        finishRotationSession(session)
    }

    private fun cancelRotationSession(): Boolean {
        val session = rotationSession ?: return false
        session.cancelled = true
        runCatching { session.trigger?.close() }
        session.trigger = null
        mc?.stop()
        post("ROTATION · cancelled")
        if (!session.measurementStarted) {
            runCatching { inferenceExecutor.execute { cleanupPendingRotationSession(session) } }
        }
        return true
    }

    // ---------- playlist screen (the default) ----------

    /** One block of the experiment: a snapshot of the settings screen. Applying a row
     *  restores those controls, so rows need no plumbing of their own — every run-start
     *  path keeps reading the same views it always did. */
    private data class PlRow(val method: String, val period: Int, val fallbackIdx: Int) {
        fun encode() = "$method|$period|$fallbackIdx"
        companion object {
            /** Also reads the older 4-field form, whose third field was the session
             *  (now a screen-level setting, since a drive does not change scene). */
            fun decode(s: String): PlRow? {
                val p = s.split('|')
                return when {
                    p.size >= 4 -> PlRow(p[0], p[1].toIntOrNull() ?: 10, p[3].toIntOrNull() ?: 4)
                    p.size == 3 -> PlRow(p[0], p[1].toIntOrNull() ?: 10, p[2].toIntOrNull() ?: 4)
                    else -> null
                }
            }
        }
    }

    /** Period only steers the Proposed family, so it is the only row that shows one. */
    private fun rowLabel(r: PlRow): String =
        if (r.method == "prop_c" || r.method == "proposed") "${r.method} · p${r.period}"
        else r.method

    /** Rows come in three kinds, and every one of them is authored the same way — pick a
     *  method in the editor and save. The kind follows from the method:
     *   - reference: the Proposed family, the constant partner of every rotating row;
     *   - once:      preparation (NAE collect+train) that runs at START and advances on
     *                completion instead of on a block boundary;
     *   - rotating:  everything else, one per block, paired against the reference. */
    private fun isOnce(m: String) = m == "nae_prep"

    private val methodIds = listOf(
        "fixed" to R.id.methodFixed,
        "ae_phone" to R.id.methodAePhone,
        "ae_cust" to R.id.methodAeCustom,
        "aeq_phone" to R.id.methodAeQuantPhone,
        "aeq_cust" to R.id.methodAeQuantCustom,
        "proposed" to R.id.methodProposed,
        "prop_c" to R.id.methodContinuous,
        "physsweep" to R.id.methodPhysSweep,
        "shin_nm" to R.id.methodShinNM,
        "nae" to R.id.methodNeuralAe,
        "nae_prep" to R.id.methodNaePrep)
    /** Indices, not resource ids: R.id values are not stable across builds. */
    private val fallbackIds = listOf(R.id.fbEntropy, R.id.fbLaplacian, R.id.fbTenengrad,
        R.id.fbCreteRoffet, R.id.fbSafeCell)
    private val sessionShort = listOf("indoor", "day", "dim", "night")

    private var rows: MutableList<PlRow> = mutableListOf()
    /** Editor state: -1 appends a new row, >= 0 edits that row, -2 is tools-only
     *  (diagnostics and Run now, nothing saved), -3 means the editor is closed. */
    private var editingIndex = -3

    /** Default rotation: Prop-C alternating with each baseline. Role B shifts the block
     *  index by one, so two phones running the SAME list always have Prop-C on one side
     *  and a baseline on the other, and every baseline is paired on both phones. */
    /** NAE prep heads the default plan: a NeuralAE block is only meaningful with weights
     *  trained in the scene about to be driven, and having to remember to add it is how
     *  that gets skipped. Delete the row when the weights are already good. */
    private fun defaultRows(): MutableList<PlRow> =
        listOf("nae_prep", "ae_phone", "ae_cust", "prop_c", "physsweep", "shin_nm", "nae")
            .map { PlRow(it, 10, 4) }
            .toMutableList()

    private fun isProp(m: String) = m == "prop_c" || m == "proposed"
    private fun baselineRows() = rows.filter { !isProp(it.method) && !isOnce(it.method) }
    private fun onceRows() = rows.filter { isOnce(it.method) }
    private fun propRow() = rows.firstOrNull { isProp(it.method) } ?: PlRow("prop_c", 10, 4)

    /** LEFT takes Prop-C on odd blocks, RIGHT on even ones, so exactly one phone is on
     *  Prop-C at every moment and the other walks the baselines. Naming them by mount
     *  position instead of A/B is what makes a mistake obvious: the phone on the left of
     *  the rig is LEFT. Each baseline is seen by BOTH phones in consecutive blocks, so
     *  device bias cancels without authoring a second list. */
    private fun isPropBlock(k: Long) = if (driveRole == "R") k % 2L == 0L else k % 2L == 1L

    /** (row to run, its index in the authored list) for wall-clock block [k]. */
    private fun blockRow(k: Long): Pair<PlRow, Int> {
        val bl = baselineRows()
        if (bl.isEmpty()) return propRow() to rows.indexOfFirst { isProp(it.method) }
        if (isPropBlock(k)) return propRow() to rows.indexOfFirst { isProp(it.method) }
        val b = bl[((k / 2) % bl.size).toInt()]
        return b to rows.indexOf(b)
    }

    private fun loadRows() {
        val raw2 = prefs.getString("rows", null)
        rows = if (raw2.isNullOrBlank()) defaultRows()
        else raw2.split(';').mapNotNull { PlRow.decode(it) }.toMutableList()
        if (rows.isEmpty()) rows = defaultRows()
        normalizeRows()
    }

    private fun saveRows() {
        prefs.edit().putString("rows", rows.joinToString(";") { it.encode() }).apply()
    }

    /** Playlist is the home screen; the settings screen is only ever a row editor or a
     *  tools panel, so it starts hidden. */
    private fun wireDriveMode() {
        drivePanel = findViewById(R.id.drivePanel)
        driveStart = findViewById(R.id.driveStart)
        driveStatus = findViewById(R.id.driveStatus)
        driveRowsView = findViewById(R.id.driveRows)
        // The settings screen carries the window insets; the playlist replaces it, so it
        // takes over keeping its status line clear of the navigation bar.
        val dpBase = intArrayOf(drivePanel.paddingLeft, drivePanel.paddingTop,
            drivePanel.paddingRight, drivePanel.paddingBottom)
        ViewCompat.setOnApplyWindowInsetsListener(drivePanel) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(dpBase[0] + bars.left, dpBase[1] + bars.top,
                dpBase[2] + bars.right, dpBase[3] + bars.bottom)
            insets
        }
        // Role is the phone's physical place in the rig, so it is set once by looking at
        // the mount rather than negotiated (there is no link between the phones).
        driveRole = when (prefs.getString("role", "L")) { "R", "B" -> "R"; else -> "L" }
        driveFlip = prefs.getBoolean("flip", false)
        playlistBlockS = prefs.getInt("block_s", 120)
        sessionSpinner.setSelection(prefs.getInt("session_idx", 0))
        blockSecView = findViewById(R.id.driveBlockSec)
        blockSecView.setText(playlistBlockS.toString())
        // Buttons do not take focus in touch mode, so the focus listener alone would let an
        // edited value sit uncommitted; Done commits it, and so does START.
        blockSecView.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitBlockSec() }
        blockSecView.setOnEditorActionListener { _, _, _ ->
            commitBlockSec()
            blockSecView.clearFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                ?.hideSoftInputFromWindow(blockSecView.windowToken, 0)
            true
        }
        playlistRepeats = prefs.getInt("repeats", 0)
        repeatsView = findViewById(R.id.driveRepeats)
        repeatsView.setText(playlistRepeats.toString())
        repeatsView.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitRepeats() }
        repeatsView.setOnEditorActionListener { _, _, _ ->
            commitRepeats()
            repeatsView.clearFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                ?.hideSoftInputFromWindow(repeatsView.windowToken, 0)
            true
        }
        loadRows()
        // Offload survives launches too: in the car there is no chance to retype a URL.
        prefs.getString("offload_url", null)?.let { offloadUrl.setText(it) }
        offloadCheck.isChecked = prefs.getBoolean("offload_on", false)
        offloadCheck.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean("offload_on", on)
                .putString("offload_url", offloadUrl.text.toString()).apply()
        }
        findViewById<Button>(R.id.driveRoleA).setOnClickListener {
            setDriveRole(if (driveRole == "L") "R" else "L")
        }
        listOf(R.id.driveSessIndoor to 0, R.id.driveSessDay to 1,
            R.id.driveSessDim to 2, R.id.driveSessNight to 3).forEach { (id, idx) ->
            findViewById<Button>(id).setOnClickListener {
                sessionSpinner.setSelection(idx)
                prefs.edit().putInt("session_idx", idx).apply()
                updateDriveUi()
            }
        }
        // The glass is its own rig, so it starts there; `--es platform car` overrides for
        // adb-driven starts.
        platformIdx = prefs.getInt("platform", if (rayNeoTouchpad) 2 else 0)
        intent.getStringExtra("platform")?.let { t ->
            val i = platformTags.indexOf(t)
            if (i >= 0) { platformIdx = i; prefs.edit().putInt("platform", i).apply() }
        }
        findViewById<Button>(R.id.drivePlatform).setOnClickListener {
            platformIdx = (platformIdx + 1) % platformTags.size
            prefs.edit().putInt("platform", platformIdx).apply()
            updateDriveUi()
        }
        findViewById<TextView>(R.id.driveReference).setOnClickListener {
            val i = rows.indexOfFirst { r -> isProp(r.method) }
            if (i >= 0) openEditor(i) else openEditor(-1)
        }
        findViewById<Button>(R.id.driveFlip).setOnClickListener {
            driveFlip = !driveFlip
            prefs.edit().putBoolean("flip", driveFlip).apply()
            applyDriveOrientation()
            applyMountOffset()
            updateDriveUi()
        }
        findViewById<Button>(R.id.driveToLab).setOnClickListener { openEditor(-2) }
        // auto_collect: three steps and what is running, over the preview, kept clear of the
        // status and navigation bars like the drive panel.
        val expPanel = findViewById<View>(R.id.expPanel)
        val expBase = intArrayOf(expPanel.paddingLeft, expPanel.paddingTop,
            expPanel.paddingRight, expPanel.paddingBottom)
        ViewCompat.setOnApplyWindowInsetsListener(expPanel) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(expBase[0] + bars.left, expBase[1] + bars.top,
                expBase[2] + bars.right, expBase[3] + bars.bottom)
            insets
        }
        findViewById<Button>(R.id.driveToExp).setOnClickListener { expPanel.visibility = View.VISIBLE }
        findViewById<Button>(R.id.expClose).setOnClickListener { expPanel.visibility = View.GONE }
        for ((t, _) in EXP_TABS) findViewById<Button>(t).setOnClickListener { showExpTab(t) }
        showExpTab(R.id.expTabMain)
        for ((id, preset) in EXP_PRESETS) {
            val b = findViewById<Button>(id)
            b.setOnClickListener {
                // While a step runs, only its own button (stop) does anything.
                if ((runActive || lapSession != null || countdownTick != null) &&
                    activeStepButton !== b) {
                    post("busy: stop the running step first"); return@setOnClickListener
                }
                toggleLapSession(preset, b)
            }
        }
        findViewById<Button>(R.id.driveAddRow).setOnClickListener { openEditor(-1) }
        findViewById<Button>(R.id.editorSave).setOnClickListener { saveEditorRow() }
        findViewById<Button>(R.id.editorRunNow).setOnClickListener {
            closeEditor(); btnStart.performClick()
        }
        findViewById<Button>(R.id.editorCancel).setOnClickListener { closeEditor() }
        driveStart.setOnClickListener {
            // Opening this screen can reveal the button under a finger still travelling
            // from the control that opened it, so the first moments are deaf.
            if (SystemClock.elapsedRealtime() - driveShownAtMs < 700) return@setOnClickListener
            if (playlistActive || runActive) { vibrate(400); stopMeasurement() }
            else startDrivePlaylist()
        }
        // Long-press keeps working for anyone who learned it as the stop gesture.
        driveStart.setOnLongClickListener {
            if (playlistActive || runActive) { vibrate(400); stopMeasurement() }
            true
        }
        // The glass keeps its touchpad-driven simple UI; the playlist is a phone screen.
        if (!rayNeoTouchpad) setDriveMode(true) else updateDriveUi()
    }

    private fun setDriveRole(role: String) {
        driveRole = role
        prefs.edit().putString("role", role).apply()
        updateDriveUi()
    }

    /** [index]: -1 append, >= 0 edit that row, -2 tools only. */
    private fun openEditor(index: Int) {
        editingIndex = index
        if (index >= 0) applyRow(rows[index])
        findViewById<View>(R.id.editorBar).visibility = View.VISIBLE
        findViewById<Button>(R.id.editorSave).visibility =
            if (index == -2) View.GONE else View.VISIBLE
        // RUN NOW in the bar is exactly what the legacy Start button did, so the old
        // Start/Stop row would be a second copy of the same control.
        (btnStart.parent as View).visibility = View.GONE
        setDriveMode(false)
        controlsScroll.scrollTo(0, 0)
    }

    private fun closeEditor() {
        editingIndex = -3
        findViewById<View>(R.id.editorBar).visibility = View.GONE
        (btnStart.parent as View).visibility = View.VISIBLE
        setDriveMode(true)
    }

    private fun saveEditorRow() {
        val row = captureRow()
        if (editingIndex >= 0) rows[editingIndex] = row else rows.add(row)
        normalizeRows()
        saveRows()
        closeEditor()
    }

    /** Once rows are prerequisites, not cycle members, so they are kept at the front —
     *  otherwise their position would claim an order the run does not follow. With this,
     *  stored order == shown order == run order. */
    private fun normalizeRows() {
        val once = rows.filter { isOnce(it.method) }
        val rest = rows.filter { !isOnce(it.method) }
        rows = (once + rest).toMutableList()
    }

    /** Current settings-screen state as a row. */
    private fun captureRow(): PlRow {
        val method = methodIds.firstOrNull {
            findViewById<android.widget.RadioButton>(it.second).isChecked
        }?.first ?: "prop_c"
        val period = when (periodGroup.checkedRadioButtonId) {
            R.id.period10 -> 10; R.id.period20 -> 20; else -> 5
        }
        val fb = fallbackIds.indexOf(fallbackGroup.checkedRadioButtonId).coerceAtLeast(0)
        return PlRow(method, period, fb)
    }

    /** Free-form block length, clamped to something a run can actually fill. */
    private fun commitBlockSec() {
        val v = blockSecView.text.toString().toIntOrNull() ?: playlistBlockS
        playlistBlockS = v.coerceIn(20, 3600)
        if (blockSecView.text.toString() != playlistBlockS.toString())
            blockSecView.setText(playlistBlockS.toString())
        prefs.edit().putInt("block_s", playlistBlockS).apply()
        updateDriveUi()
    }

    /** How many full passes over the list to run before stopping by itself. 0 keeps the
     *  old behaviour — rotate until the operator taps STOP — which is what a drive of
     *  unknown length wants; a fixed count is what a repeatable protocol wants. */
    private fun commitRepeats() {
        val v = repeatsView.text.toString().toIntOrNull() ?: playlistRepeats
        playlistRepeats = v.coerceIn(0, 999)
        if (repeatsView.text.toString() != playlistRepeats.toString())
            repeatsView.setText(playlistRepeats.toString())
        prefs.edit().putInt("repeats", playlistRepeats).apply()
        updateDriveUi()
    }

    /** Blocks in one full pass: Prop-C takes every other block, so a pass is twice the
     *  number of rotating rows. Never 0, so the repeat check cannot divide by nothing. */
    private fun cycleBlocks(): Long = (2L * baselineRows().size).coerceAtLeast(1L)

    /** The display stays ON (the Activity must not pause) but goes dark for the drive: at
     *  full brightness a 30 min run spends real energy and heat on a screen nobody looks
     *  at, and both now land in the log as batt_charge_uah / thermal_status / cpu_mhz.
     *  It must be a FIXED value for the whole playlist, never adaptive — a brightness that
     *  drifts mid-run puts a varying load underneath the per-method energy comparison,
     *  which is the one thing that comparison cannot tolerate.
     *  BRIGHTNESS_OVERRIDE_NONE hands control back to the system. */
    private fun setDriveBrightness(dim: Boolean) {
        window.attributes = window.attributes.apply {
            screenBrightness = if (dim) DRIVE_BRIGHTNESS
            else android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    /** Restore a row into the settings screen — this is also how a block is launched. */
    private fun applyRow(row: PlRow) {
        methodIds.firstOrNull { it.first == row.method }?.let {
            findViewById<android.widget.RadioButton>(it.second).isChecked = true
        }
        findViewById<android.widget.RadioButton>(when (row.period) {
            5 -> R.id.period5; 20 -> R.id.period20; else -> R.id.period10
        }).isChecked = true
        findViewById<android.widget.RadioButton>(
            fallbackIds[row.fallbackIdx.coerceIn(0, fallbackIds.size - 1)]).isChecked = true
    }

    private fun setDriveMode(on: Boolean) {
        driveMode = on
        if (on) driveShownAtMs = SystemClock.elapsedRealtime()
        drivePanel.visibility = if (on) View.VISIBLE else View.GONE
        controlsScroll.visibility = if (on) View.GONE else View.VISIBLE
        applyDriveOrientation()
        applyMountOffset()
        updateDriveUi()
    }

    /** The flip describes how the phone is MOUNTED, so it holds for every screen — tying
     *  it to the playlist screen made the editor flip back and forth. */
    private fun applyDriveOrientation() {
        requestedOrientation = if (driveFlip)
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    /** Read before a run starts; per-run pipelines size their buffers after this. */
    private fun applyMountOffset() {
        mountOffsetDeg = mountOnSide() ?: if (driveFlip) 180 else 0
        if (::raw.isInitialized) raw.mountOffsetDeg = mountOffsetDeg
    }

    /** A phone mounted on its side needs a quarter turn, which the 0/180 flip cannot give;
     *  without it every picture reaches the detector lying sideways, and it finds no cars.
     *  Read from gravity: left edge up (x reads -g) turns the picture 90 degrees clockwise,
     *  right edge up 270. Null when the phone stands upright or upside down (the flip
     *  setting holds) or gravity has not locked yet. */
    private fun mountOnSide(): Int? {
        val g = sensors.gravityXyz() ?: return null
        if (abs(g[0]) < 4.9f || abs(g[0]) < abs(g[1])) return null
        return if (g[0] < 0f) 90 else 270
    }

    /** Block index counts from START, so a drive walks the list from the top exactly as
     *  written. The two phones are aligned by starting them together — the operator's job,
     *  and a predictable one — while the BOUNDARIES stay on wall-clock multiples so the
     *  pair never drifts apart over a long drive. */
    private var blockCounter = 0L
    private var liveBlockIdx = 0L

    private fun currentBlockIndex(): Long = liveBlockIdx

    /** Rebuilds the row list: the live block is marked, tap edits, long-press deletes. */
    private fun renderRows() {
        if (!::driveRowsView.isInitialized) return
        driveRowsView.removeAllViews()
        val k = currentBlockIndex()
        val liveIdx = blockRow(k).second
        val left = (playlistBlockS * 1000L -
            System.currentTimeMillis() % (playlistBlockS * 1000L)) / 1000
        // The list is the authored order - reordering would be a lie otherwise. Only the
        // reference is lifted out, onto its own line above.
        rows.indices.forEach { i ->
            val r = rows[i]
            if (isProp(r.method)) return@forEach
            val live = i == liveIdx
            val tv = TextView(this).apply {
                // Only the live row carries state - what it faces on the other phone and
                // the seconds left - so the rest stays a plain list.
                text = (if (live || (isOnce(r.method) && prepStage > 0)) "> " else "   ") +
                    rowLabel(r) + when {
                        isOnce(r.method) && prepStage == 1 -> "   (collecting…)"
                        isOnce(r.method) && prepStage == 2 -> "   (training…)"
                        isOnce(r.method) && prepDone -> "   (done)"
                        isOnce(r.method) -> "   (once, at START)"
                        // Only while running, and only what this phone is doing: the block
                        // number (two phones in step show the same one) and the seconds
                        // left before the next scheme. Idle, a countdown would be counting
                        // toward nothing.
                        live && playlistActive -> "   blk $k   (" + left + "s)"
                        else -> ""
                    }
                textSize = 16f
                setTextColor(getColor(
                    if (live || (isOnce(r.method) && prepStage > 0)) R.color.text_primary
                    else R.color.text_readout))
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(4, 10, 4, 10)
                setOnClickListener { openEditor(i) }
                setOnLongClickListener { showRowMenu(i); vibrate(40); true }
            }
            driveRowsView.addView(tv)
        }
    }

    /** Long-press menu: the plan is edited in place instead of only appended to. Moves
     *  step over the hidden reference row so the list behaves as it looks. */
    private fun showRowMenu(i: Int) {
        // Moves stay within a kind: a once row cannot sink into the cycle and a cycle row
        // cannot climb above the prerequisites, which is what keeps position truthful.
        val kindOnce = isOnce(rows[i].method)
        val visible = rows.indices.filter {
            !isProp(rows[it].method) && isOnce(rows[it].method) == kindOnce
        }
        val p = visible.indexOf(i)
        val items = mutableListOf<String>()
        val acts = mutableListOf<() -> Unit>()
        if (p > 0) {
            items += "Move up"
            acts += { java.util.Collections.swap(rows, i, visible[p - 1]) }
        }
        if (p in 0 until visible.size - 1) {
            items += "Move down"
            acts += { java.util.Collections.swap(rows, i, visible[p + 1]) }
        }
        items += "Insert copy above"
        acts += { rows.add(i, rows[i].copy()) }
        items += "Delete"
        acts += { rows.removeAt(i) }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(rowLabel(rows[i]))
            .setItems(items.toTypedArray()) { _, which ->
                acts[which](); saveRows(); updateDriveUi()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** The structure in one quiet line: what runs opposite what, how long a full cycle
     *  takes (the repetitions a drive yields are its duration divided by this), and
     *  whether prep runs first. Keeping it out of the list keeps the list a list. */
    private fun renderCaption() {
        val ref = findViewById<TextView>(R.id.driveReference)
        val refRow = rows.firstOrNull { isProp(it.method) }
        ref.text = if (refRow == null) "reference: none - add a Prop-C row"
            else "reference  " + rowLabel(refRow) + "   (opposite every row)"
        val nb = baselineRows().size
        val cap = findViewById<TextView>(R.id.driveCaption)
        if (nb == 0) { cap.text = "no rotating rows - add one"; return }
        val cycleMin = 2 * nb * playlistBlockS / 60.0
        cap.text = "cycle " + (2 * nb) + " blocks = " + "%.0f".format(cycleMin) +
            " min  |  each row 2x" + playlistBlockS + "s  |  " +
            (if (playlistRepeats > 0)
                "stops after " + playlistRepeats + "x = " +
                    "%.0f".format(cycleMin * playlistRepeats) + " min"
             else "runs until STOP")
    }

    private fun startDrivePlaylist() {
        if (rows.isEmpty()) { post("playlist is empty — add a row"); return }
        commitBlockSec()
        commitRepeats()
        setDriveBrightness(true)
        playlistActive = true
        blockCounter = 0; liveBlockIdx = 0
        vibrate(120); playlistHandler.postDelayed({ vibrate(120) }, 220)
        if (onceRows().isNotEmpty() && !prepDone) {
            // No boundary stop is scheduled for a once row: the collector ends itself at
            // its sample target, then training runs, then the rotation is joined.
            prepStage = 1
            post("NAE prep: collecting…")
            updateDriveUi()
            findViewById<Button>(R.id.btnNaeCollect).performClick()
            return
        }
        startPlaylistBlock()
        updateDriveUi()
    }

    private fun updateDriveUi() {
        if (!::drivePanel.isInitialized) return
        val running = playlistActive || runActive
        driveStart.text = if (running) "STOP" else "START"
        // Role only means something with two phones side by side, which is the car rig;
        // on the limo and the glass there is nothing to pair with, so it is not offered.
        val paired = platformTag() == "car"
        findViewById<Button>(R.id.driveRoleA).visibility =
            if (paired) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.driveRoleA).text =
            if (driveRole == "L") "LEFT · prop odd" else "RIGHT · prop even"
        findViewById<Button>(R.id.driveFlip).alpha = if (driveFlip) 1f else 0.35f
        findViewById<Button>(R.id.drivePlatform).text = platformTag().uppercase()
        val si = sessionSpinner.selectedItemPosition
        listOf(R.id.driveSessIndoor, R.id.driveSessDay, R.id.driveSessDim, R.id.driveSessNight)
            .forEachIndexed { i, id -> findViewById<Button>(id).alpha = if (i == si) 1f else 0.35f }
        renderCaption()
        renderRows()
    }

    @Suppress("DEPRECATION")
    private fun vibrate(ms: Long) {
        val effect = android.os.VibrationEffect.createOneShot(
            ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(android.os.VibratorManager::class.java)
            vm?.defaultVibrator?.vibrate(effect)
        } else {
            (getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator)?.vibrate(effect)
        }
    }

    // ---------- driving playlist ----------

    private var playlistBlockS: Int = 120
    /** Full passes over the list before the playlist stops itself. 0 = run forever. */
    private var playlistRepeats: Int = 0
    @Volatile private var playlistActive = false
    private val playlistHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** One-shot overrides from the launch intent, for the ONE adb-started lap session or run.
     *  They used to be read from the intent at every button press, so a test launch's
     *  settings (a short lap_plan, a 5 s fallback lap) silently stayed in force for later
     *  manual sessions until the app was killed. Now they are taken once in onCreate,
     *  removed from the intent, and dropped when that session or run ends. Extras:
     *  lap_plan, lap_repeats, lap_leadin_s, lap_fallback_s, lap_start_delay_s,
     *  collect_frames, save_images (false = latency check without JPEGs), focus_dpt
     *  (manual focus in diopters; "af" leaves autofocus on). */
    private var launchLapPlan: String? = null
    private var launchLapRepeats: Int? = null
    private var launchLapLeadInS: Int? = null
    private var launchLapFallbackS: Int? = null
    private var launchLapStartDelayS: Int? = null
    private var launchCollectFrames: Int? = null
    private var launchSaveImages: Boolean? = null
    private var launchFocusDiopters: Float? = null

    private fun takeLaunchOverrides() {
        val i = intent
        fun int(key: String): Int? = if (i.hasExtra(key)) i.getIntExtra(key, 0) else null
        launchLapPlan = i.getStringExtra("lap_plan")
        launchLapRepeats = int("lap_repeats")
        launchLapLeadInS = int("lap_leadin_s")
        launchLapFallbackS = int("lap_fallback_s")
        launchLapStartDelayS = int("lap_start_delay_s")
        launchCollectFrames = int("collect_frames")
        launchSaveImages = if (i.hasExtra("save_images")) i.getBooleanExtra("save_images", true) else null
        launchFocusDiopters = i.getStringExtra("focus_dpt")?.let {
            if (it.equals("af", ignoreCase = true)) Float.NaN else it.toFloatOrNull()
        }
        for (key in listOf("lap_plan", "lap_repeats", "lap_leadin_s", "lap_fallback_s",
                "lap_start_delay_s", "collect_frames", "save_images", "focus_dpt")) {
            i.removeExtra(key)
        }
    }

    private fun clearLaunchOverrides() {
        launchLapPlan = null; launchLapRepeats = null; launchLapLeadInS = null
        launchLapFallbackS = null; launchLapStartDelayS = null; launchCollectFrames = null
        launchSaveImages = null; launchFocusDiopters = null
        presetRunCap = null; presetSaveImages = null; presetRunActive = false
        ExperimentSession.lighting = ""; ExperimentSession.session = ""
    }

    /** One choice of the experiment selector above the Lap session button: either a lap
     *  plan (method:laps,... repeated [repeats] times) or a single static [run] with an
     *  optional frame [cap]. Counts are defaults; adb extras still override them. */
    private class LapPreset(
        val label: String, val plan: String? = null, val repeats: Int = 1,
        val run: String? = null, val cap: Int? = null, val saveImages: Boolean = true,
        /** Seconds counted down on the big line before a [run] starts. */
        val delayS: Int = 0,
        /** Lighting the operator declared by the button pressed; goes into every manifest. */
        val lighting: String? = null
    )

    /** The Duel experiment (docs/SPEC_proposed_v4.md): eight methods, three runs each of
     *  [MAIN_LAPS] laps, one run of every method per round, the order shifted by one each round
     *  (no method always follows the same one, slow drift spreads over all). Each run
     *  starts fresh and keeps its state for its laps. 24 runs. */
    private val MAIN_METHODS = listOf("duel2_trace", "duel2_every", "ae_phone", "prop_c",
        "physsweep", "shin_nm", "nae", "fixed_1600_240")
    /** Laps per run: 3 until the 2026-10-26 bright session; 2 since the Limo was slowed to
     *  ~20 s a lap (that session took 24 min). */
    private val MAIN_LAPS = 2
    private val MAIN_PLAN = (0 until 3).flatMap { r ->
        MAIN_METHODS.indices.map { MAIN_METHODS[(it + r) % MAIN_METHODS.size] }
    }.joinToString(",") { "$it:$MAIN_LAPS" }

    /** One round of the Limo step, one lap each, the method changing every lap so slow drift
     *  (light, battery, speed) falls on every method alike: five collection laps (one per
     *  base ISO, never two in a row, also across rounds) and one of each compared method:
     *  v3cause (the proposed) and the baselines Sense-well (prop_c), AE-Phone, PhysSweep,
     *  ShinNM. 10 laps. */
    private val LIMO_ROUND = listOf("collect55", "prop_c", "collect55", "v3cause_t010",
        "collect55", "ae_phone", "collect55", "physsweep", "collect55", "shin_nm")
        .joinToString(",") { "$it:1" }

    /** auto_collect's three steps: question 1 (synthesis reproduces the real photo)
     *  with the phone still, questions 1 and 2 on the Limo, and the one-time read noise.
     *  Still: 30 frames, old and new image pipeline alternating (~1 min). Limo: four rounds,
     *  40 laps (~9 min): collection 20 (four per base ISO, for aligning synthesized and
     *  real photos by track position), each compared method 4.
     *  v3cause runs at theta 0.10 only; v3cause_t020, v3cause_inf and v3cause_pilot still
     *  start by name. */
    private val EXP_PRESETS = linkedMapOf(
        // Main tab: the Duel experiment, one press per lighting (5 s countdown, a lead-in
        // lap, then 24 runs; one beep at the start, three at the end).
        R.id.expMainBright to LapPreset("main, bright", MAIN_PLAN, 1, lighting = "bright"),
        R.id.expMainDim to LapPreset("main, dim", MAIN_PLAN, 1, lighting = "dim"),
        R.id.expMainDark to LapPreset("main, dark", MAIN_PLAN, 1, lighting = "dark"),
        // Check tab: Duel's two modes, one 3-lap run each (spec 7, track part).
        R.id.expTrackTest to LapPreset("track test", "duel2_trace:3,duel2_every:3", 1),
        R.id.expReadNoise to LapPreset("read noise", run = "readnoise"),
        // Old tab: the v3 experiments' steps (2026-10-15/16).
        R.id.expStatic to LapPreset("question 1, still", run = "collect55_ab", cap = 30),
        R.id.expLimo to LapPreset("questions 1+2, Limo", LIMO_ROUND, 4),
        // The counterfactual replay record: one run of collect_cf, 225 moments (about 0.85 s
        // each, some 15 per lap, so about 15 laps), started 5 s after the press with no
        // lead-in lap: the record needs no lap boundaries, and the track position is in
        // every moment's yaw.
        R.id.expCollect to LapPreset("counterfactual record, Limo", run = "collect_cf", cap = 225, delayS = 5),
        // Record tab: the same counterfactual record (225 moments, ~15 laps, ~4 min) with the
        // pressed lighting in every manifest, as Main does.
        R.id.expRecordBright to LapPreset("record, bright", run = "collect_cf", cap = 225, delayS = 5, lighting = "bright"),
        R.id.expRecordDim to LapPreset("record, dim", run = "collect_cf", cap = 225, delayS = 5, lighting = "dim"),
        R.id.expRecordDark to LapPreset("record, dark", run = "collect_cf", cap = 225, delayS = 5, lighting = "dark"),
        // Phone AE alone for ~40-50 s (300 frames, some 3-4 laps), made 2026-10-16 to measure
        // what AE chooses in a lighting the counterfactual record has but no AE run had.
        R.id.expAeCollect to LapPreset("phone AE alone, Limo", run = "ae_phone", cap = 300, delayS = 5)
    )

    /** auto_collect's tabs and the button row each shows. */
    private val EXP_TABS = listOf(R.id.expTabMain to R.id.expRowMain,
        R.id.expTabCheck to R.id.expRowCheck, R.id.expTabRecord to R.id.expRowRecord,
        R.id.expTabOld to R.id.expRowOld)

    private fun showExpTab(tab: Int) {
        for ((t, row) in EXP_TABS) {
            val on = t == tab
            findViewById<View>(row).visibility = if (on) View.VISIBLE else View.GONE
            findViewById<Button>(t).apply {
                // Explicit colours: the theme's button text is dark purple, unreadable on the
                // panel's black once dimmed.
                setTextColor(if (on) android.graphics.Color.WHITE else 0xFF9E9E9E.toInt())
                paintFlags = if (on) paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                    else paintFlags and android.graphics.Paint.UNDERLINE_TEXT_FLAG.inv()
            }
        }
    }

    /** Frame cap and image saving for the current auto_collect run or session. */
    private var presetRunCap: Int? = null
    private var presetSaveImages: Boolean? = null
    private var presetRunActive = false
    /** The auto_collect button whose step is running, and its idle label. */
    private var activeStepButton: Button? = null
    private var activeStepLabel: CharSequence = ""
    /** How a static step ended when not normally: "stopped" or "error". */
    @Volatile private var stepEnding: String? = null

    /** The large line on auto_collect: what runs now, or how the last step ended. */
    private fun showNow(text: String) = runOnUiThread {
        findViewById<TextView>(R.id.expNow)?.text = text
    }

    /** What auto_collect shows in large letters while [method] runs. */
    private fun methodTitle(method: String): String {
        v3Command(method)?.let { (theta, pilot) ->
            return if (pilot) "pilot: v3cause"
            else "proposed: v3cause " + if (theta.isInfinite()) "θ∞" else "θ%.2f".format(theta)
        }
        return when (method) {
            "duel2_trace" -> "proposed: Duel2 (trace)"
            "duel2_every" -> "proposed: Duel2 (every)"
            "fixed_1600_240" -> "reference: fixed 1600 · 1/240"
            "nae" -> "baseline: Neural-AE"
            "prop_c" -> "baseline: Sense-well"
            "ae_phone" -> "baseline: AE-Phone"
            "physsweep" -> "baseline: PhysSweep"
            "shin_nm" -> "baseline: ShinNM"
            "collect55" -> "collect: 5×5"
            "collect55_ab" -> "collect: 5×5 A/B"
            "collect_cf" -> "collect: counterfactual"
            "readnoise" -> "read noise"
            else -> method
        }
    }

    private fun restoreStepButton(done: Boolean) {
        activeStepButton?.let { b -> b.text = activeStepLabel.toString() + if (done) "  ✓" else "" }
        activeStepButton = null
    }

    /** Programmatic equivalent of picking a method radio and pressing Start. */
    /** The 5x3 collection run (MeasurementController.runCollect53). It has no method radio,
     *  so it is started directly rather than through the Start button. */
    private fun startCollect(kind: String = "collect") {
        val cap = launchCollectFrames ?: presetRunCap ?: NO_FRAME_CAP
        // Inside a lap session the base ISO is held for a whole lap and stepped by the
        // session; on its own the run steps it every frame.
        val base = if (lapSession != null) lapCollectBlockOrdinal else -1
        val tag = when (kind) {
            "collect55" -> "collect_5x5"; "collect55_ab" -> "collect_5x5_ab"
            "collect_cf" -> "collect_cf"; else -> "collect_5x3"
        }
        start(tag) {
            val controller = mc!!
            controller.collectBaseOverride = base
            when (kind) {
                // claim 1 on prop_c's lattice; _ab alternates the old and new image pipeline
                "collect55", "collect55_ab", "collect_cf" ->
                    controller.runCollect55(cap, kind == "collect55_ab", ::post, ::showFrame,
                        counterfactual = kind == "collect_cf")
                else -> controller.runCollect53(cap, ::post, ::showFrame)
            }
        }
    }

    private fun isCollect(method: String) =
        method == "collect" || method == "collect55" || method == "collect55_ab" ||
            method == "collect_cf"

    /** Starts a lap session, or stops the one in progress. The plan and its timeouts come
     *  from the launch intent when given, otherwise from LapSession's defaults. */
    private fun toggleLapSession(preset: LapPreset? = null, stepButton: Button? = null) {
        val lapButton = stepButton ?: findViewById<Button>(R.id.btnLapSession)
        if (lapSession != null) { stopMeasurement(); return }
        countdownTick?.let {
            // Pressed again during the countdown: nothing has started, so just cancel.
            countdownHandler.removeCallbacks(it); countdownTick = null
            presetRunActive = false; presetRunCap = null
            ExperimentSession.lighting = ""; ExperimentSession.session = ""
            restoreStepButton(false); showNow("stopped")
            return
        }
        if (presetRunActive && runActive) { stepEnding = "stopped"; stopMeasurement(); return }
        if (runActive || playlistActive) { post("busy — stop first"); return }
        presetSaveImages = preset?.saveImages
        if (stepButton != null) {
            activeStepButton = stepButton
            activeStepLabel = stepButton.text.toString().removeSuffix("  ✓")
            stepEnding = null
            findViewById<TextView>(R.id.expStatus).text = ""
        }
        // A static step is one run, not a lap session.
        if (preset?.run != null) {
            presetRunCap = preset.cap
            presetRunActive = true
            // A labelled step (the Record tab) writes its lighting into the run's manifest too.
            ExperimentSession.lighting = preset.lighting.orEmpty()
            ExperimentSession.session = if (preset.lighting != null)
                "${preset.run}_${preset.lighting}_${System.currentTimeMillis()}" else ""
            stepButton?.text = "■ stop"
            startAfterCountdown(preset.delayS, preset.run)
            return
        }
        val plan = LapSession.parsePlan(launchLapPlan ?: preset?.plan ?: LapSession.DEFAULT_PLAN)
        if (plan.isEmpty()) { post("lap plan is empty"); return }
        // Duel2 cannot start without its tolerance curve; refuse the whole session up front
        // rather than let its blocks silently not start while the laps are counted.
        if (plan.any { it.method.startsWith("duel2") } && runCatching {
                HowToSense.Curve.parse(assets.open("tolerance_curve.txt").bufferedReader().use { it.readText() })
            }.isFailure) {
            restoreStepButton(false); showNow("no tolerance curve")
            post("Duel2 needs assets/tolerance_curve.txt — session not started")
            return
        }
        // Runs in a lap session end by laps, never by a frame cap left over from a step.
        presetRunCap = null
        ExperimentSession.lighting = preset?.lighting.orEmpty()
        ExperimentSession.session = if (preset?.lighting != null)
            "duel2_main_${preset.lighting}_${System.currentTimeMillis()}" else ""
        // The x2 digital boost exists for the S25's ISO-400 analog ceiling and is folded
        // into every grid-formed image, phone AE included. The collection run sets its own
        // ratios, so leaving the boost on would make the AE laps one stop brighter than
        // what the phone chose and than everything they are compared with. It is put back
        // when the session ends; the manifest of each run records the value in force.
        boostBeforeLapSession = grid.digitalBoost
        grid.digitalBoost = 1.0
        val session = LapSession(this, sensors, object : LapSession.Host {
            override fun startBlock(method: String) {
                if (stepButton != null) showNow(methodTitle(method))
                ExperimentSession.runLap = 0
                if (isCollect(method)) {
                    // Each collection block opens one base ISO further along than the
                    // last, so the lap cut short by the block switch is not always the
                    // same ISO's.
                    lapCollectBlockOrdinal++
                    startCollect(method)
                } else launchRunnerCommand(method)
            }
            override fun stopBlock() = stopMeasurement(userInitiated = false)
            override fun status(text: String) {
                if (stepButton == null) { lapButton.text = "STOP · $text"; return }
                stepButton.text = "■ stop"
                findViewById<TextView>(R.id.expStatus).text = text
            }
            override fun buzz(ms: Long) = vibrate(ms)
            override fun beep(times: Int) = playLapBeeps(times)
            override fun lapStarted(method: String, lapsInBlock: Int) {
                ExperimentSession.runLap = lapsInBlock
                // One base ISO per lap: the collection run reads this before each frame.
                if (isCollect(method))
                    mc?.collectBaseOverride = lapsInBlock + lapCollectBlockOrdinal
            }
            override fun sessionEnded(completed: Boolean) {
                lapSession = null
                ExperimentSession.runLap = -1
                ExperimentSession.lighting = ""; ExperimentSession.session = ""
                clearLaunchOverrides()
                // An aborted session can end while its last run is still winding down;
                // the boost goes back only once no run can still be forming images.
                if (!runActive) restoreBoostAfterLapSession()
                if (stepButton != null) {
                    restoreStepButton(completed)
                    showNow(if (completed) "done" else "stopped")
                } else lapButton.text = if (completed) "Lap session · done" else "Lap session"
            }
        }, plan, (launchLapRepeats ?: preset?.repeats ?: LapSession.DEFAULT_REPEATS).coerceAtLeast(1),
            leadInTimeoutS = (launchLapLeadInS ?: 90).coerceAtLeast(1),
            fallbackLapS = (launchLapFallbackS ?: 15).coerceAtLeast(3),
            startDelayS = (launchLapStartDelayS ?: 5).coerceAtLeast(0))
        lapSession = session
        lapCollectBlockOrdinal = -1
        if (stepButton != null) showNow("lead-in")
        vibrate(120)
        session.start()
    }

    private fun restoreBoostAfterLapSession() {
        if (boostBeforeLapSession > 0.0) grid.digitalBoost = boostBeforeLapSession
        boostBeforeLapSession = -1.0
    }

    /** Long beeps on the ALARM stream, so they are heard from across the room whatever the
     *  media volume is and with the phone on silent: one when a lap session really
     *  starts, three when its whole plan is done. */
    private val countdownHandler = android.os.Handler(android.os.Looper.getMainLooper())
    /** Non-null while an auto_collect step counts down to its start. */
    private var countdownTick: Runnable? = null

    /** Counts [seconds] down on the big line, then starts [run]. Heard from across the room
     *  like a lap session: one beep when it really starts, three when it ends. */
    private fun startAfterCountdown(seconds: Int, run: String) {
        var left = seconds
        val tick = object : Runnable {
            override fun run() {
                if (left > 0) {
                    showNow("starting in $left")
                    left--
                    countdownHandler.postDelayed(this, 1000L)
                    return
                }
                countdownTick = null
                showNow(methodTitle(run))
                vibrate(120)
                playLapBeeps(1)
                launchRunnerCommand(run)
            }
        }
        countdownTick = tick
        tick.run()
    }

    private fun playLapBeeps(times: Int) {
        val tone = runCatching {
            android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100)
        }.getOrNull() ?: return
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        for (i in 0 until times) handler.postDelayed({
            runCatching { tone.startTone(android.media.ToneGenerator.TONE_DTMF_D, 450) }
        }, i * 750L)
        handler.postDelayed({ runCatching { tone.release() } }, times * 750L + 1_000L)
    }

    /** proposed_v3cause arms by name: v3cause_t010 / v3cause_t020 / v3cause_inf, and the
     *  pilot v3cause_pilot (theta 0.10) or v3cause_pilot_t020. Null if [cmd] is not one. */
    private fun v3Command(cmd: String): Pair<Double, Boolean>? {
        if (!cmd.startsWith("v3cause")) return null
        val pilot = cmd.startsWith("v3cause_pilot")
        val t = Regex("_t(\\d{3})$").find(cmd)?.groupValues?.get(1)?.toIntOrNull()
        val theta = when {
            cmd.endsWith("_inf") -> Double.POSITIVE_INFINITY
            t != null -> t / 100.0
            pilot -> 0.10
            else -> return null
        }
        return theta to pilot
    }

    private fun launchRunnerCommand(cmd: String) {
        if (isCollect(cmd)) { startCollect(cmd); return }
        if (cmd == "readnoise") {
            start("readnoise") { mc!!.runReadNoise(::post) }
            return
        }
        v3Command(cmd)?.let { (theta, pilot) ->
            val cap = launchCollectFrames ?: presetRunCap ?: NO_FRAME_CAP
            start(cmd) { mc!!.runV3Cause(theta, pilot, cap, ::post, ::showFrame) }
            return
        }
        if (cmd == "duel2_trace" || cmd == "duel2_every") {
            val cap = launchCollectFrames ?: presetRunCap ?: NO_FRAME_CAP
            val curve = runCatching {
                HowToSense.Curve.parse(assets.open("tolerance_curve.txt").bufferedReader().use { it.readText() })
            }.getOrElse { post("Duel needs assets/tolerance_curve.txt: ${it.message}"); return }
            start(cmd) { mc!!.runDuel(cmd == "duel2_every", curve, cap, ::post, ::showFrame) }
            return
        }
        // The Duel experiments' fixed reference cell: ISO 1600 x 1/240 s on the v2 lattice.
        if (cmd == "fixed_1600_240") {
            val cap = launchCollectFrames ?: presetRunCap ?: NO_FRAME_CAP
            start(cmd) { mc!!.runFixedLattice(1600, 4167, cap, ::post, ::showFrame) }
            return
        }
        // Sense-well is compared at P = 5 (spec); the period radio is screen state that an
        // accidental tap can change (a stray P = 10 run exists), so a named launch sets it.
        if (cmd == "prop_c") findViewById<android.widget.RadioButton>(R.id.period5).isChecked = true
        val rid = when (cmd) {
            "fixed" -> R.id.methodFixed
            "ae_phone" -> R.id.methodAePhone
            "ae_cust" -> R.id.methodAeCustom
            "aeq_phone" -> R.id.methodAeQuantPhone
            "aeq_cust" -> R.id.methodAeQuantCustom
            "proposed" -> R.id.methodProposed
            "prop_c" -> R.id.methodContinuous
            "physsweep" -> R.id.methodPhysSweep
            "shin_nm" -> R.id.methodShinNM
            "nae" -> R.id.methodNeuralAe
            else -> 0
        }
        // An unknown name used to press Start with whatever radio happened to be checked.
        if (rid == 0 && cmd != "collect_nae") { post("unknown method: $cmd"); return }
        if (rid != 0) findViewById<android.widget.RadioButton>(rid).isChecked = true
        if (cmd == "collect_nae") findViewById<Button>(R.id.btnNaeCollect).performClick()
        else btnStart.performClick()
    }

    /** Starts the scheme the wall clock says this block belongs to, and schedules the
     *  boundary stop. Near-boundary launches wait for the next block instead of running
     *  a stub. Pairing needs no bookkeeping: block index is floor(run start epoch /
     *  block_s) offline. */
    private fun startPlaylistBlock() {
        if (!playlistActive) return
        if (runActive) { playlistHandler.postDelayed({ startPlaylistBlock() }, 1000); return }
        val blockMs = playlistBlockS * 1000L
        val now = System.currentTimeMillis()
        val remain = blockMs - now % blockMs
        if (remain < 15_000L) {
            post("playlist: ${remain / 1000}s to the boundary — waiting")
            playlistHandler.postDelayed({ startPlaylistBlock() }, remain + 500)
            return
        }
        if (rows.isEmpty()) {
            playlistActive = false; setDriveBrightness(false)
            post("playlist is empty"); return
        }
        // Stop on a PASS boundary, never mid-rotation: an unequal tail would give some
        // baselines one more block than others, which is exactly what breaks the paired
        // comparison the whole rotation exists to produce.
        if (playlistRepeats > 0 && blockCounter >= playlistRepeats * cycleBlocks()) {
            playlistActive = false
            // One long buzz, distinct from the short per-block one, so the driver knows it
            // ended without looking at the screen.
            vibrate(700)
            setDriveBrightness(false)
            post("playlist finished: " + playlistRepeats + " x " + cycleBlocks() + " blocks")
            updateDriveUi()
            return
        }
        val k = blockCounter
        liveBlockIdx = k
        blockCounter = k + 1
        val row = blockRow(k).first
        post("[$driveRole] block $k: ${row.method} (${remain / 1000}s)")
        // One buzz per scheme change: the driver never needs to look at the screen.
        vibrate(150)
        // A row IS the settings screen's state, so launching a block is restoring it and
        // pressing Start — every run-start path keeps reading the views it always did.
        applyRow(row)
        if (row.method == "collect_nae") findViewById<Button>(R.id.btnNaeCollect).performClick()
        else btnStart.performClick()
        updateDriveUi()
        playlistHandler.postDelayed({
            if (playlistActive && runActive) stopMeasurement(userInitiated = false)
        }, remain)
    }

    private fun stopMeasurement(userInitiated: Boolean = true) {
        // A stop the operator asked for ends the lap plan too, not just the current block.
        if (userInitiated) lapSession?.abort()
        if (userInitiated && playlistActive) {
            playlistActive = false
            setDriveBrightness(false)
            prepStage = 0
            playlistHandler.removeCallbacksAndMessages(null)
            post("playlist cancelled")
        }
        updateDriveUi()
        if (cancelRotationSession()) {
            runOnUiThread { overlay.clear(); cellText.text = "—" }
            return
        }
        mc?.stop()
        post("stopping…")
        runOnUiThread { overlay.clear(); cellText.text = "—" }
    }

    private fun toggleRunFromTouchpad() {
        if (runActive) stopMeasurement() else startSelectedMethod()
    }

    /** DPAD-style input is emitted by some RayNeo firmware and is also useful for testing. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Volume keys start/stop in drive mode: findable by touch in a car mount, so the
        // experiment can be driven without locating a button on screen.
        // On the experiment screen they do nothing: a volume press there used to start the
        // car playlist hidden underneath.
        if (findViewById<View>(R.id.expPanel)?.isShown == true && event.keyCode in intArrayOf(
                KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP)) return true
        if (driveMode && event.keyCode in intArrayOf(
                KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (playlistActive || runActive) { vibrate(400); stopMeasurement() }
                else startDrivePlaylist()
            }
            return true
        }
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_PAGE_DOWN -> 1
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_PAGE_UP -> -1
            else -> 0
        }
        if (direction != 0) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) moveTouchpadFocus(direction)
            return true
        }
        if (event.keyCode in intArrayOf(
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE)) {
            if (event.action == KeyEvent.ACTION_UP) activateFocusedControl()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Trackpad/mouse-wheel firmware variants map their scroll axis to the same focus model. */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL &&
            event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            val vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val horizontal = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
            val axis = if (vertical != 0f) vertical else -horizontal
            if (axis != 0f) {
                moveTouchpadFocus(if (axis < 0f) 1 else -1)
                return true
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    /**
     * RayNeo reports the temple pad as a direct absolute touchscreen. Coordinates therefore
     * cannot be used for hit-testing: swipes move focus and taps operate the focused control.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!rayNeoTouchpad) return super.dispatchTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerDownX = event.x
                pointerDownY = event.y
                pointerSwipeHandled = false
            }
            MotionEvent.ACTION_MOVE -> if (!pointerSwipeHandled) {
                val dx = event.x - pointerDownX
                val dy = event.y - pointerDownY
                if (max(abs(dx), abs(dy)) >= dp(42)) {
                    pointerSwipeHandled = true
                    val forward = if (abs(dy) >= abs(dx)) dy < 0f else dx < 0f
                    moveTouchpadFocus(if (forward) 1 else -1)
                }
            }
            MotionEvent.ACTION_UP -> if (!pointerSwipeHandled) {
                if (event.eventTime - event.downTime >= ViewConfiguration.getLongPressTimeout()) {
                    toggleRunFromTouchpad()
                } else {
                    activateFocusedControl()
                }
            }
        }
        return true
    }

    private fun configureStaticTouchpadControls() {
        touchpadControls().forEach(::configureTouchpadControl)
    }

    /** Phone profile: plain touch clicks, glass-only controls hidden, and an idle status
     *  line naming the active device profile so a run's configuration is visible at a
     *  glance before Start. */
    private fun configurePhoneProfileUi() {
        findViewById<View>(R.id.touchpadHelp).visibility = View.GONE
        btnRotationStart.visibility = View.GONE
        updateProfileSummary()
    }

    private fun updateProfileSummary() {
        if (rayNeoTouchpad) return
        val exps = grid.exposuresUs.joinToString("/") { (it / 1000).toString() }
        status.text = "${Build.MODEL} · veh-V3 {1,2,3,5,7} · ${exps}ms · " +
            "ISO ${grid.gains.first()}-${grid.gains.last()} ×${grid.digitalBoost.toInt()}"
    }

    /** Reads the current hierarchy so rebuilt Fixed cells and hidden method settings stay sane. */
    private fun touchpadControls(): List<View> {
        val result = ArrayList<View>()
        fun visit(view: View) {
            when (view) {
                is EditText -> Unit // Avoid opening an on-glass keyboard for the server URL.
                is Button, is CheckBox, is Spinner -> result += view
                is ViewGroup -> for (i in 0 until view.childCount) visit(view.getChildAt(i))
            }
        }
        visit(controlsInner)
        return result
    }

    private fun availableTouchpadControls(): List<View> =
        touchpadControls().filter { it.isShown && it.isEnabled }

    private fun currentTouchpadControl(): View? =
        currentFocus?.takeIf { it in touchpadControls() && it.isShown && it.isEnabled }

    private fun moveTouchpadFocus(delta: Int) {
        val available = availableTouchpadControls()
        if (available.isEmpty()) return
        val current = available.indexOf(currentTouchpadControl())
        val next = if (current < 0) 0 else (current + delta).floorMod(available.size)
        available[next].requestFocus()
        scrollControlIntoView(available[next])
    }

    private fun activateFocusedControl() {
        val focused = currentTouchpadControl() ?: btnStart.also { it.requestFocus() }
        if (focused is Spinner) {
            if (focused.count > 0) {
                focused.setSelection((focused.selectedItemPosition + 1).floorMod(focused.count))
                post("${controlLabel(focused)} · ${focused.selectedItem}")
            }
        } else {
            focused.performClick()
        }
    }

    private fun configureTouchpadControl(view: View) {
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.setOnFocusChangeListener { focusedView, hasFocus ->
            focusedView.animate().cancel()
            focusedView.scaleX = if (hasFocus) 1.06f else 1f
            focusedView.scaleY = if (hasFocus) 1.06f else 1f
            focusedView.alpha = if (hasFocus) 1f else 0.9f
            if (hasFocus) {
                scrollControlIntoView(focusedView)
                if (!runActive) post("Focus · ${controlLabel(focusedView)} · tap")
            }
        }
    }

    private fun scrollControlIntoView(view: View) {
        controlsScroll.post {
            val rect = Rect()
            view.getDrawingRect(rect)
            controlsInner.offsetDescendantRectToMyCoords(view, rect)
            controlsScroll.smoothScrollTo(0, max(0, rect.top - dp(56)))
        }
    }

    private fun controlLabel(view: View): String = when (view) {
        is Spinner -> when (view.id) {
            R.id.confThreshSpinner -> "Confidence"
            R.id.offloadRegime -> "Network"
            else -> "Option"
        }
        is TextView -> view.text.toString().replace('\n', ' ').trim().ifBlank { "Control" }
        else -> "Control"
    }

    private fun Int.floorMod(size: Int): Int = ((this % size) + size) % size
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun post(s: String) = runOnUiThread {
        status.text = s
        if (driveMode) driveStatus.text = s
        // In a lap session auto_collect's small line shows the session's progress instead.
        if (lapSession == null)
            findViewById<TextView>(R.id.expStatus)?.let { if (it.isShown) it.text = s }
    }

    /** Preview plus non-blocking advisory-cloud escalation from cross-exposure consistency. */
    private fun onFrameWithOffload(bmp: Bitmap, dets: List<Detection>, iso: Int, expUs: Int) {
        recordRotationFirstFramePose()
        showFrame(bmp, dets, iso, expUs)
        offloader?.takeIf { offloadAll || mc?.lastRoutingDecision?.shouldOffload == true }?.let { o ->
            val frameIdx = mc?.lastFrameIdx ?: 0
            displayIoExecutor.execute {
                val jpeg = ByteArrayOutputStream()
                    .also { bmp.compress(Bitmap.CompressFormat.JPEG, OffloadClient.JPEG_QUALITY, it) }
                    .toByteArray()
                o.offload(frameIdx, jpeg)
            }
        }
    }

    /** Validate the first real exposure-window midpoint, not the intentionally early trigger. */
    private fun recordRotationFirstFramePose() {
        val session = rotationSession ?: return
        if (!session.measurementStarted || !session.firstPoseRecorded.compareAndSet(false, true)) return
        val triggerController = session.trigger ?: return
        val runDir = session.logger?.dir ?: return
        val metas = raw.lastMeta
        if (metas.isEmpty()) return
        val first = metas.first()
        val last = metas.last()
        val midpointNs = (first.timestamp + last.timestamp + last.appliedExpUs * 1_000L) / 2L
        val pose = triggerController.poseAt(midpointNs)
        File(runDir, "rotation_first_frame.json").writeText(JSONObject().apply {
            put("first_sensor_timestamp_ns", first.timestamp)
            put("last_sensor_timestamp_ns", last.timestamp)
            put("exposure_window_midpoint_ns", midpointNs)
            put("pose_sample_timestamp_ns", pose?.sampleTimestampNs)
            put("pose_sample_offset_ns", pose?.sampleTimestampNs?.minus(midpointNs))
            put("center_error_deg", pose?.centerErrorDeg)
            put("gyro_y_rad_s", pose?.gyroY)
            put("direction", if ((pose?.gyroY ?: 0.0) > 0.0) "positive" else "negative")
            put("within_1deg", pose != null && pose.centerErrorDeg <= 1.0 && pose.gyroY > 0.0)
        }.toString(2))
        runCatching { triggerController.close() }
        session.trigger = null
    }

    /** Detection.xyxy is already in `bmp` pixel space, so the bitmap's own dims are what the
     *  overlay needs to scale against. */
    private fun showFrame(bmp: Bitmap, dets: List<Detection>, iso: Int, expUs: Int) {
        // Keep the 0.01 confidence tail in JSONL for offline analysis, but only draw boxes
        // that can actually contribute to the SoS selection score. This prevents All-COCO
        // low-confidence noise from looking like a valid on-glass detection.
        runOnUiThread {
            val drawn = dets.asSequence().filter { it.confidence >= displayConfThreshold }.map {
                val xy = it.xyxy
                OverlayView.DrawInfo(
                    Rect(xy[0].toInt(), xy[1].toInt(), xy[2].toInt(), xy[3].toInt()),
                    "${CocoLabels.name(it.classId)} %.2f".format(it.confidence))
            }.toList()
            val cellLabel = "ISO %d\nexp %.1f ms".format(iso, expUs / 1000.0)
            preview.setImageBitmap(bmp)
            overlay.setResults(drawn, bmp.width, bmp.height)
            cellText.text = cellLabel
        }
    }

    /** Device-profiled detector. Both devices now run the vehicle domain — the 80-class
     *  model with the V3 selection filter (sense/proxy.py classes: bicycle, car,
     *  motorcycle, bus, truck) — on the same GPU probe mode (AUTO backend, no silent CPU
     *  fallback). The COCO5 tabletop head the glass finalize shipped is retired.
     *
     *  RayNeo loads B1/B3 only: building the B=9 OpenCL delegate on a clean shader cache
     *  hangs on the glass (the finalize build hit this), so K=9 probes run as exact-slot
     *  chunks inside the detector instead. Phones keep B9 — their first compile is slow
     *  (~1 min) but completes. `--ez no_b9 true` emulates the glass slot set on a phone
     *  so the chunked path can be exercised without RayNeo hardware. */
    private fun createProfiledDetector(): TfliteYoloDetector {
        val excludeB9 = rayNeoTouchpad || intent.getBooleanExtra("no_b9", false)
        return TfliteYoloDetector(
            this,
            batchedAssets = listOf(
                "yolov8n_640_fp16.tflite" to 1,
                "yolov8n_640_b3_fp16.tflite" to 3,
                "yolov8n_640_b9_fp16.tflite" to 9
            ).filter { it.second < 9 || !excludeB9 },
            numClasses = 80,
            allowed = setOf(1, 2, 3, 5, 7),
            accelerator = TfliteYoloDetector.Accelerator.GPU,
            allowFallback = false,
            gpuBackend = TfliteYoloDetector.GpuBackend.AUTO,
            onLoadStatus = ::post
        )
    }

    /** Runs [block] on the persistent GPU worker with a fresh logger and controller, so each
     *  press gets its own run directory while the camera and three GPU interpreters are reused. */
    private fun start(modeTag: String, block: () -> Unit) {
        if (runActive) { post("busy — stop first"); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) { post("no camera permission"); return }
        // All View reads happen here, on the UI thread, before the worker starts.
        val offUrl = (if (offloadCheck.isChecked) offloadUrl.text.toString().trim().ifBlank { null }
                      else null) ?: intent.getStringExtra("server_url")
        val offRegime = (offloadRegime.selectedItem?.toString() ?: "clear").ifBlank { "clear" }
        val selectConf = (confThreshSpinner.selectedItem?.toString()?.toFloatOrNull()) ?: 0.25f
        displayConfThreshold = selectConf
        val sess = sessionTag()
        val plat = platformTag()
        val pairSnap = pairingSnap()
        val saveImagesNow = launchSaveImages ?: presetSaveImages ?: true
        val focusNow = launchFocusDiopters ?: DEFAULT_FOCUS_DIOPTERS
        applyMountOffset()
        runActive = true
        inferenceExecutor.execute {
            try {
                // A background transition invalidates camera ownership. This executes on the
                // same serial worker and therefore cannot race a GPU invocation.
                if (resourcesMustClose) {
                    closeResources()
                    resourcesMustClose = false
                }
                if (!opened) {
                    post("opening camera…")
                    raw = RawSensorCapturer(this); raw.open()
                    raw.mountOffsetDeg = mountOffsetDeg
                    // Keep the paper's FP16 640px YOLOv8n and select the exact fixed batch for
                    // K=1/3/9. AUTO was validated on X3 Pro as OpenCL GPU delegate V2.
                    post("loading YOLO GPU batches…")
                    detector = createProfiledDetector()
                    post("GPU ready ${detector.backendSummary} — capturing first frame…")
                    opened = true
                }
                // Platform leads the name so a mixed directory groups by rig, while the
                // epoch stays last for the parsers that split on it.
                val runName = "run_${plat}_${modeTag}_${System.currentTimeMillis()}"
                val logger = MeasurementLogger(this, runName)
                val mcLocal = MeasurementController(raw, detector, grid, sensors, logger, health, selectConf)
                mcLocal.setV2Session(sess)
                mcLocal.platformTag = plat
                mcLocal.applyPairing(pairSnap)
                mcLocal.saveImages = saveImagesNow
                mcLocal.appFilesDir = getExternalFilesDir(null)
                raw.fixedFocusDiopters = focusNow
                mc = mcLocal
                // The currentFrame supplier reads lastFrameIdx, so staleness is measured
                // against the same frame index that frames.csv records.
                offloader = offUrl?.let {
                    post("offload -> $it ($offRegime)")
                    OffloadClient(it, logger.dir, offRegime) { mcLocal.lastFrameIdx }
                        .apply { warmConnection() }
                }
                try {
                    block()
                } finally {
                    logger.close()
                }
                // All 80 COCO classes are retained; the UI threshold only controls scoring/drawing.
                val above = mcLocal.detectionTotalAboveThresh
                val floor = mcLocal.detectionTotalAtFloor
                val frames = mcLocal.totalFramesLogged
                post("done — detections: $above above thresh (${floor} at floor), $frames frames")
                android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed({ status.text = "" }, 5000)
            } catch (e: Throwable) {
                if (stepEnding == null) stepEnding = "error"
                post("error: ${e.message}")
            } finally {
                runActive = false
                // Playlist continuation: the next block starts once this run's worker has
                // fully unwound (camera and GPU stay open, so the gap is a second or two).
                runOnUiThread {
                    lapSession?.onRunEnded()
                    if (lapSession == null) {
                        if (presetRunActive) {
                            restoreStepButton(stepEnding == null)
                            showNow(stepEnding ?: "done")
                            if (stepEnding != "stopped") { vibrate(800); playLapBeeps(3) }
                        }
                        restoreBoostAfterLapSession()
                        // A single adb-started run consumes its overrides too.
                        if (!playlistActive) clearLaunchOverrides()
                    }
                    updateDriveUi()
                    when {
                        !playlistActive -> {}
                        // Prep chains collect -> train -> rotation instead of waiting for
                        // a boundary, so the drive starts with usable NAE weights.
                        prepStage == 1 -> {
                            prepStage = 2
                            updateDriveUi()
                            doTrainNae(useAll = false) {
                                prepStage = 0; prepDone = true
                                runOnUiThread {
                                    updateDriveUi()
                                    if (playlistActive) startPlaylistBlock()
                                }
                            }
                        }
                        prepStage == 2 -> {}   // training owns the transition
                        else -> playlistHandler.postDelayed({ startPlaylistBlock() }, 1500)
                    }
                }
            }
        }
    }

    override fun onResume() { super.onResume(); sensors.registerListeners() }
    override fun onPause() {
        super.onPause()
        lapSession?.abort()
        cancelRotationSession()
        sensors.unregisterListeners()
        // Backgrounding revokes camera ownership, but Interpreter.close() must not race an
        // in-flight native run. Request stop and defer teardown until the worker has returned.
        mc?.stop()
        resourcesMustClose = true
        queueResourceClose()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (runActive) stopMeasurement() else super.onBackPressed()
    }

    override fun onDestroy() {
        cancelRotationSession()
        mc?.stop()
        resourcesMustClose = true
        queueResourceClose()
        inferenceExecutor.shutdown()
        displayIoExecutor.shutdown()
        health.close()
        sensors.release()
        super.onDestroy()
    }

    private fun queueResourceClose() {
        // FIFO ordering guarantees this runs after an in-flight detectBatch returns.
        runCatching {
            inferenceExecutor.execute {
                if (!resourcesMustClose) return@execute
                closeResources()
                resourcesMustClose = false
            }
        }
    }

    private fun closeResources() {
        if (!opened) return
        runCatching { raw.close() }
        runCatching { detector.close() }
        opened = false
    }
}
