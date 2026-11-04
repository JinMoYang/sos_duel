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
        /** Run modes have no frame cap: field passes follow a lap protocol the operator
         *  ends with Stop. Storage stays modest (~85 KB per frame, img/ JPEGs only). */
        private const val NO_FRAME_CAP = Int.MAX_VALUE
        /** Manual focus for every method (diopters): the 2026-10-13 Limo sessions' median
         *  autofocus position, about 45-50 cm, where the toy cars sit. */
        private const val DEFAULT_FOCUS_DIOPTERS = 2.0f
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
    /** One eye's half of the glass's screen (walk_eye): the curve's state, the title, two lines,
     *  and the runs to choose while idle. */
    private class Eye(root: View) {
        val state: TextView = root.findViewById(R.id.eyeState)
        val title: TextView = root.findViewById(R.id.eyeTitle)
        val detail: TextView = root.findViewById(R.id.eyeDetail)
        val sub: TextView = root.findViewById(R.id.eyeSub)
        val choiceBox: View = root.findViewById(R.id.eyeChoices)
        val choices: List<TextView> = listOf(R.id.eyeChoice0, R.id.eyeChoice1, R.id.eyeChoice2,
            R.id.eyeChoice3, R.id.eyeChoice4).map { root.findViewById(it) }
    }
    /** The glass's screen (the manual panel and the preview are hidden), both eyes alike. */
    private lateinit var eyes: List<Eye>
    /** The run a tap starts on the glass's idle screen, an index into [GLASS_RUNS]; a swipe moves it. */
    private var glassChoice = 2
    /** Draw each output and its boxes; off on the glass (`--ez preview true` turns it on). */
    private var previewOn = true
    private lateinit var methodGroup: RadioGroup
    private lateinit var periodGroup: RadioGroup
    private lateinit var boostGroup: RadioGroup
    private lateinit var proposedSettings: LinearLayout
    private lateinit var confThreshSpinner: Spinner
    private lateinit var sessionSpinner: Spinner
    private val prefs by lazy { getSharedPreferences("sos_ui", MODE_PRIVATE) }
    @Volatile private var mountOffsetDeg = 0
    private lateinit var controlsScroll: ScrollView
    private lateinit var controlsInner: LinearLayout
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

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
    /** Runs started so far (counted when a run's controller is set), and the count when the walk's
     *  current block asked for its run: the block's run is the one after that. */
    @Volatile private var runSerial = 0
    private var blockRunSerial = 0
    /** The session driving a plan of runs (LapSession: Limo laps; WalkSession: corridor legs on
     *  the glass). Null whenever none is in progress. */
    private var lapSession: PlanSession? = null
    /** Digital boost to put back when a lap session ends; negative when none is saved. */
    private var boostBeforeLapSession = -1.0
    /** How many collection blocks the current lap session has opened, minus one. */
    private var lapCollectBlockOrdinal = -1
    private var opened = false
    @Volatile private var resourcesMustClose = false
    @Volatile private var displayConfThreshold = 0.25f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A screen-off event pauses the Activity on RayNeo. Keep the display awake during an
        // experiment; closing TFLite while Interpreter.run() is active causes a native crash.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // The glass's lock screen comes up whenever its display sleeps and would cover the app
        // (pausing it, which stops the camera and a walk session): show over it, and wake it.
        if (Build.MANUFACTURER.equals("RayNeo", ignoreCase = true) && Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        setContentView(R.layout.activity_measurement)
        status = findViewById(R.id.statusText)
        preview = findViewById(R.id.previewImage)
        overlay = findViewById(R.id.overlayView)
        cellText = findViewById(R.id.cellText)
        eyes = listOf(Eye(findViewById(R.id.eyeLeft)), Eye(findViewById(R.id.eyeRight)))
        ExperimentSession.experimenter = prefs.getInt("experimenter", 1)
        previewOn = intent.getBooleanExtra("preview", !rayNeoTouchpad)
        methodGroup = findViewById(R.id.methodGroup)
        periodGroup = findViewById(R.id.periodGroup)
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
        controlsScroll = findViewById(R.id.controls)
        controlsInner = findViewById(R.id.controlsInner)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
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
        grid.digitalBoost = boostFromCheckedId(boostGroup.checkedRadioButtonId)
        // The two method rows act as ONE mutually-exclusive choice: checking in either
        // group clears the other (guarded against the recursive clear callback). The
        // settings panels key on the radio id, whichever row it lives in.
        val methodGroup2: RadioGroup = findViewById(R.id.methodGroup2)
        var syncingMethodRows = false
        fun applyMethodVisibility(id: Int) {
            // The period row is Sense-well's (P = 5 in the comparison).
            proposedSettings.visibility = if (id == R.id.methodContinuous) View.VISIBLE else View.GONE
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
        // Initial state mirrors the default-checked Sense-well radio.
        proposedSettings.visibility = View.VISIBLE

        // v2 (Prop-C) has no boost concept, and the v1 arms must keep the 2x default their
        // collected runs used — so the row is frozen rather than removed: a stray tap can
        // no longer fork comparability mid-experiment.
        for (i in 0 until boostGroup.childCount) boostGroup.getChildAt(i).isEnabled = false
        boostGroup.alpha = 0.4f
        // Grid is mutated in place; the rebuild refreshes the effective-ISO labels.
        boostGroup.setOnCheckedChangeListener { _, id ->
            grid.digitalBoost = boostFromCheckedId(id)
            updateProfileSummary()
        }

        btnStart.setOnClickListener { startSelectedMethod() }
        takeLaunchOverrides()
        // Lap-driven collection: the one control this experiment adds. From adb,
        // `--ez lap_session true` presses it, with optional `--es lap_plan
        // "collect:10,ae_phone:3"`, `--ei lap_repeats 3`, `--ei lap_leadin_s 90` and
        // `--ei lap_fallback_s 15`. A single collection run without laps is
        // `--es run collect`, optionally capped with `--ei collect_frames N`.
        findViewById<Button>(R.id.btnLapSession).setOnClickListener { toggleLapSession() }
        findViewById<Button>(R.id.btnRotationSession).setOnClickListener { toggleRotationSession() }
        if (intent.getBooleanExtra("lap_session", false)) {
            btnStart.postDelayed({ if (lapSession == null) toggleLapSession() }, 1500)
        }
        if (intent.getBooleanExtra("rotation_session", false)) {
            btnStart.postDelayed({ if (lapSession == null) toggleRotationSession() }, 1500)
        }
        // General adb runner — the glass touchpad intercepts injected taps (focus-first
        // model), so radio selection is impossible from the host; this starts any method
        // directly: `--es run <method>` with optional `--es period 5|10|20`. Methods: the
        // proposed addl_3_sep_every (and the addl_* checks), the baselines ae_phone |
        // prop_c | physsweep | shin_nm | nae | fixed_1600_240, each also as par_<method>
        // (parallel capture), par_physsweep_fast, collect_cf | collect_cf2, readnoise,
        // budget_probe. Stop a run with KEYCODE_BACK.
        intent.getStringExtra("period")?.let { p ->
            val pid = when (p) {
                "10" -> R.id.period10; "20" -> R.id.period20; else -> R.id.period5
            }
            findViewById<android.widget.RadioButton>(pid).isChecked = true
        }
        intent.getStringExtra("run")?.let { cmd ->
            btnStart.postDelayed({ launchRunnerCommand(cmd) }, 1500)
        }
        btnStop.setOnClickListener { stopMeasurement() }

        // The focus-first tap model exists for the glass touchpad only. On a phone it makes
        // every control need TWO taps (focusableInTouchMode: first tap focuses, second
        // clicks), so the phone profile keeps plain touch and hides the glass-only chrome.
        if (rayNeoTouchpad) configureStaticTouchpadControls()
        // The glass shows only its own screen: no manual panel (its hidden buttons still work
        // for runs started from adb, since performClick works on GONE views), and no preview
        // unless asked for. Swipes on the temple choose a run, a tap starts it.
        if (rayNeoTouchpad) {
            controlsScroll.visibility = View.GONE
            findViewById<View>(R.id.walkPanel).visibility = View.VISIBLE
            showGlassIdle()
            if (!previewOn) {
                preview.visibility = View.GONE
                overlay.visibility = View.GONE
                cellText.visibility = View.GONE
            }
        }
        else configurePhoneProfileUi()
        wireExperimentPanel()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1)
        }
    }
    /** The experiment screen (Main / Check / Record tabs, the Limo speed) is the phone's home
     *  screen, over the preview; "manual" switches to the manual controls (method rows,
     *  Start / Stop), whose "experiments" button switches back. The glass keeps its
     *  touchpad-driven simple UI. */
    private fun wireExperimentPanel() {
        // The rig the camera rides names every run directory: limo on phones, rayneo on the
        // glass; `--es platform limo|car|rayneo` overrides, and the choice persists.
        platformIdx = prefs.getInt("platform", if (rayNeoTouchpad) 2 else 0)
        intent.getStringExtra("platform")?.let { t ->
            val i = platformTags.indexOf(t)
            if (i >= 0) { platformIdx = i; prefs.edit().putInt("platform", i).apply() }
        }
        val expPanel = findViewById<View>(R.id.expPanel)
        val expBase = intArrayOf(expPanel.paddingLeft, expPanel.paddingTop,
            expPanel.paddingRight, expPanel.paddingBottom)
        ViewCompat.setOnApplyWindowInsetsListener(expPanel) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(expBase[0] + bars.left, expBase[1] + bars.top,
                expBase[2] + bars.right, expBase[3] + bars.bottom)
            insets
        }
        fun showExperiments(on: Boolean) {
            expPanel.visibility = if (on) View.VISIBLE else View.GONE
            controlsScroll.visibility = if (on) View.GONE else View.VISIBLE
        }
        findViewById<Button>(R.id.expClose).setOnClickListener { showExperiments(false) }
        findViewById<Button>(R.id.btnOpenExp).setOnClickListener { showExperiments(true) }
        for ((t, _) in EXP_TABS) findViewById<Button>(t).setOnClickListener { showExpTab(t) }
        showExpTab(R.id.expTabMain)
        // Limo speed picked: survives launches (it changes between days, not runs); from adb,
        // `--es limo_speed slow|avg|fast` picks it the same way.
        val limoButtons = listOf(R.id.limoSlow to "slow", R.id.limoAvg to "avg", R.id.limoFast to "fast")
        fun pickLimo(v: String) {
            ExperimentSession.limoSpeed = v
            prefs.edit().putString("limo_speed", v).apply()
            for ((id, t) in limoButtons) findViewById<Button>(id).alpha = if (t == v) 1f else 0.35f
        }
        pickLimo((intent.getStringExtra("limo_speed") ?: prefs.getString("limo_speed", ""))
            .takeIf { s -> limoButtons.any { it.second == s } }.orEmpty())
        for ((id, t) in limoButtons) findViewById<Button>(id).setOnClickListener {
            pickLimo(if (ExperimentSession.limoSpeed == t) "" else t)
        }
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
        // The glass shows neither: its screen is the walk session's progress line.
        if (rayNeoTouchpad) { expPanel.visibility = View.GONE; controlsScroll.visibility = View.GONE }
        else showExperiments(true)
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

    /** nae-bin-v1 weights: a file in the app files dir (trained on the PC by
     *  NaeTrainSosCf and pushed there) wins over the bundled asset. */
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
                    mc!!.runContinuousProposed(period, NO_FRAME_CAP, ::post, ::showFrame)
                }
                return
            }
            R.id.methodPhysSweep -> {
                // Every device searches the v2 lattice — the same 5x5 space Prop-C and the
                // proposed method live on — so the comparison isolates the method, not the
                // search space (the glass too since 2026-10-30).
                val v2 = true
                val n = if (v2) 25 else grid.nGain * grid.nShutter
                start(if (v2) "physsweep_v2_full_h$n" else "physsweep_full_h$n") {
                    mc!!.runPhysSweep(true, n, NO_FRAME_CAP, ::post, v2 = v2,
                        onFrame = ::showFrame)
                }
                return
            }
            R.id.methodShinNM -> {
                val v2 = true
                start(if (v2) "shin_nm_v2" else "shin_nm") {
                    mc!!.runShinNM("restart_int", NO_FRAME_CAP, ::post, v2 = v2,
                        onFrame = ::showFrame)
                }
                return
            }
            R.id.methodNeuralAe -> {
                val w = loadNaeWeights()
                if (w == null) post("NAE weights missing — push nae_hist_scalar_3x3.bin to the app files dir")
                else {
                    val v2 = true
                    start(if (v2) "nae_v2" else "nae") {
                        mc!!.runNeuralAe(w, NO_FRAME_CAP, ::post, v2 = v2,
                            onFrame = ::showFrame)
                    }
                }
                return
            }
        }
        // AE strategies are flattened into distinct method radios — the arm is fully
        // named by the id, no nested strategy group to consult.
        when (methodGroup.checkedRadioButtonId) {
            R.id.methodAePhone -> start("ae_phone") {
                // presetRunCap: a capped step stops itself after its frames.
                mc!!.runAe(presetRunCap ?: NO_FRAME_CAP, gtRef, AeStrategy.PHONE, ::post, ::showFrame)
            }
            else -> post("pick a method")
        }
    }

    /** Read before a run starts; per-run pipelines size their buffers after this. */
    private fun applyMountOffset() {
        mountOffsetDeg = mountOnSide() ?: 0
        if (::raw.isInitialized) raw.mountOffsetDeg = mountOffsetDeg
    }

    /** A phone mounted on its side needs a quarter turn, which the 0/180 flip cannot give;
     *  without it every picture reaches the detector lying sideways, and it finds no cars.
     *  Read from gravity: left edge up (x reads -g) turns the picture 90 degrees clockwise,
     *  right edge up 270. Null when the phone stands upright or upside down or gravity
     *  has not locked yet. */
    private fun mountOnSide(): Int? {
        val g = sensors.gravityXyz() ?: return null
        if (abs(g[0]) < 4.9f || abs(g[0]) < abs(g[1])) return null
        return if (g[0] < 0f) 90 else 270
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
        val lighting: String? = null,
        /** Prefix of the experiment_session label every run of a lighting preset carries. */
        val sessionTag: String = "duel2_main"
    )

    /** Laps per run: 3 until the 2026-10-26 bright session; 2 since the Limo was slowed to
     *  ~20 s a lap (that session took 24 min). */
    private val MAIN_LAPS = 2

    /** A lap plan of three rounds, one run of [MAIN_LAPS] laps per method each round, the order
     *  shifted by one each round (no method always follows the same one, slow drift - light,
     *  battery, Limo speed - spreads over all). Each run starts fresh. */
    private fun rounds(methods: List<String>) = (0 until 3).flatMap { r ->
        methods.indices.map { methods[(it + r) % methods.size] }
    }.joinToString(",") { "$it:$MAIN_LAPS" }

    /** The final comparison (2026-10-29), parallel structure for every method (capture and analysis
     *  on separate threads, 10 fps): the proposed method (addl_3_sep_every: the key read at the
     *  curve's setting plus up to 3 more at that setting every frame, each detected on its own, fused)
     *  and the baselines moved unchanged onto the parallel capture (par_*), PhysSweep as fast
     *  PhysSweep. 21 runs, ~12 min per lighting. From 2026-11-03 the proposed method is sense (the
     *  app spec of that day, MeasurementController.runSense); addl_3_sep_every stays runnable by name.
     *  Sense-well (par_prop_c) left out on 2026-11-03: in that day's walks it stopped by itself
     *  ~8 s into its block; it stays runnable by name. */
    private val PAR_MAIN_METHODS = listOf("sense", "par_ae_phone", "par_physsweep_fast",
        "par_shin_nm", "par_nae", "par_fixed_1600_240")
    private val PAR_MAIN_PLAN = rounds(PAR_MAIN_METHODS)

    /*
     * Earlier checks, startable by lap_plan (same rounds, 2 laps a run):
     * (Before 2026-10-29 the proposed method was Duel / Duel2, serial; those runs and checks are
     * on the topdown-base branch.)
     * - 2026-10-29, additional exposures after a trace (addl_0, addl_{1,3,5}_{merge,sep}), parallel,
     *   dark (4.4 lux, fast): key only .81; +1 merged .88 / separate .82; +3 1.00 / .98; +5 1.04 /
     *   .97 (se .07). The gain saturates at +3, the largest within 100 ms (+5 took ~120 ms).
     * - 2026-10-29, the 3 additional exposures after a trace or every frame (VERIFY_E_PLAN), fast:
     *   dark (4.5 lux) every merged 1.17 / every separate 1.14 / trace separate 1.04 / trace merged
     *   1.02 (se .04); dim (10.4 lux) every separate 2.20 / trace separate 2.13 / trace merged 2.03 /
     *   every merged 1.89 (se .11; 27% of its car boxes > 5% clipped: the key's setting is chosen for
     *   one read). Every frame, separate: first or tied first in both, the proposed method.
     */

    /** When to spend the 3 additional exposures (2026-10-29), 12 runs, ~6 min per lighting: after a
     *  trace only, or every frame; merged or each detected on its own. */
    private val VERIFY_E_PLAN = rounds(listOf("addl_3_merge", "addl_3_sep", "addl_3_merge_every", "addl_3_sep_every"))

    /** The proposed method's tolerance curve (gyro + translation speeds), in assets. */
    private val CURVE_FILE = "tolerance_curve_gyro.txt"
    /** The glass's own curve in the app's files dir, preferred over [CURVE_FILE] when present. */
    private val RAYNEO_CURVE_FILE = "tolerance_curve_rayneo.txt"

    private val EXP_PRESETS = linkedMapOf(
        // Main tab: the Duel experiment, one press per lighting (5 s countdown, a lead-in
        // lap, then 24 runs; one beep at the start, three at the end).
        R.id.expMainBright to LapPreset("main (parallel), bright", PAR_MAIN_PLAN, 1, lighting = "bright",
            sessionTag = "par_main"),
        R.id.expMainDim to LapPreset("main (parallel), dim", PAR_MAIN_PLAN, 1, lighting = "dim",
            sessionTag = "par_main"),
        R.id.expMainDark to LapPreset("main (parallel), dark", PAR_MAIN_PLAN, 1, lighting = "dark",
            sessionTag = "par_main"),
        // Check tab: when to spend the additional exposures, dim and dark (see VERIFY_E_PLAN).
        R.id.expVerifyDim to LapPreset("verify trace / every frame, dim", VERIFY_E_PLAN, 1, lighting = "dim",
            sessionTag = "addl_every_verify"),
        R.id.expVerifyDark to LapPreset("verify trace / every frame, dark", VERIFY_E_PLAN, 1, lighting = "dark",
            sessionTag = "addl_every_verify"),
        R.id.expReadNoise to LapPreset("read noise", run = "readnoise"),
        // Record tab: the counterfactual record with tracking-updated sums and digital ISO
        // 3200/6400 (collect_cf2, 2026-10-28) with the pressed lighting in every manifest, as
        // Main does: 150 moments (~1.7 s each, ~4.5 min, ~13 laps of the slowed Limo; the first
        // bright record has 225). The plain collect_cf stays on the Old tab.
        R.id.expRecordBright to LapPreset("record, bright", run = "collect_cf2", cap = 150, delayS = 5, lighting = "bright"),
        R.id.expRecordDim to LapPreset("record, dim", run = "collect_cf2", cap = 150, delayS = 5, lighting = "dim"),
        R.id.expRecordDark to LapPreset("record, dark", run = "collect_cf2", cap = 150, delayS = 5, lighting = "dark"),
    )

    /** auto_collect's tabs and the button row each shows. */
    private val EXP_TABS = listOf(R.id.expTabMain to R.id.expRowMain,
        R.id.expTabCheck to R.id.expRowCheck, R.id.expTabRecord to R.id.expRowRecord)

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
        if (method.startsWith("par_")) return "parallel: " + methodTitle(method.removePrefix("par_"))
        if (method == "physsweep_fast") return "baseline: PhysSweep (fast)"
        return when (method) {
            "addl_0" -> "check: key exposure only"
            "addl_1_merge" -> "check: key + 1 more, merged"
            "addl_1_sep" -> "check: key + 1 more, separate"
            "addl_3_merge" -> "check: key + 3 more, merged"
            "addl_3_sep" -> "check: key + 3 more, separate"
            "addl_5_merge" -> "check: key + 5 more, merged"
            "addl_5_sep" -> "check: key + 5 more, separate"
            "addl_3_merge_every" -> "check: key + 3 more every frame, merged"
            "addl_3_sep_every" -> "proposed (10-31): key + up to 3 more every frame, separate"
            "sense" -> "proposed: Key Exposure + new samples (spec 2026-11-03)"
            "fixed_1600_240" -> "reference: fixed 1600 · 1/240"
            "newest_ae_phone" -> "baseline: AE-Phone (newest read)"
            "newest_fixed_1600_240" -> "reference: fixed 1600 · 1/240 (newest read)"
            "newest_fixed_1600_30" -> "reference: fixed 1600 · 1/30 (newest read)"
            "nae" -> "baseline: Neural-AE"
            "prop_c" -> "baseline: Sense-well"
            "ae_phone" -> "baseline: AE-Phone"
            "physsweep" -> "baseline: PhysSweep"
            "shin_nm" -> "baseline: ShinNM"
            "collect_cf" -> "collect: counterfactual"
            "collect_cf2" -> "collect: counterfactual + tracking sums"
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
    private fun startCollect(kind: String) {
        val cap = launchCollectFrames ?: presetRunCap ?: NO_FRAME_CAP
        // Inside a lap session the base ISO is held for a whole lap and stepped by the
        // session; on its own the run steps it every frame.
        val base = if (lapSession != null) lapCollectBlockOrdinal else -1
        start(kind) {
            val controller = mc!!
            controller.collectBaseOverride = base
            // The counterfactual record (the tolerance curve's data); cf2 adds tracking sums.
            controller.runCollect55(cap, false, ::post, ::showFrame,
                counterfactual = true, aligned = kind == "collect_cf2")
        }
    }

    private fun isCollect(method: String) = method == "collect_cf" || method == "collect_cf2"

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
        if (runActive) { post("busy — stop first"); return }
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
        // The proposed method cannot start without its tolerance curve; refuse the whole session
        // up front rather than let its blocks silently not start while the laps are counted.
        if (plan.any { it.method.startsWith("addl_") } && runCatching {
                HowToSense.Curve.parse(curveText())
            }.isFailure) {
            restoreStepButton(false); showNow("no tolerance curve")
            post("the proposed method needs assets/$CURVE_FILE — session not started")
            return
        }
        // Runs in a lap session end by laps, never by a frame cap left over from a step.
        presetRunCap = null
        ExperimentSession.lighting = preset?.lighting.orEmpty()
        ExperimentSession.session = if (preset?.lighting != null)
            "${preset.sessionTag}_${preset.lighting}_${System.currentTimeMillis()}" else ""
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

    /** A stop asked for while a run was still opening ([stopMeasurement]); [start] stops the
     *  run's controller as soon as it exists. */
    @Volatile private var stopPending = false

    /** Set by a "par_" command for the run it starts ([start] takes it). */
    private var parallelNext = false

    private fun launchRunnerCommand(command: String) {
        // "par_<method>" (2026-10-29): the same method with capture and analysis on separate
        // threads at 10 fps (MeasurementController.parallelCapture); the run is named par_...
        parallelNext = command.startsWith("par_")
        val cmd = command.removePrefix("par_")
        if (cmd == "walk") { toggleWalkSession(); return }
        if (cmd == "rotation") { toggleRotationSession(); return }
        if (isCollect(cmd)) { startCollect(cmd); return }
        if (cmd == "readnoise") {
            start("readnoise") { mc!!.runReadNoise(::post) }
            return
        }
        // Fast PhysSweep: PhysSweep with its sweep in the parallel structure's spare slots
        // (MeasurementController.runPhysSweepFast); only as par_physsweep_fast.
        if (cmd == "physsweep_fast") {
            if (!parallelNext) { post("physsweep_fast runs only as par_physsweep_fast"); return }
            val cap = launchCollectFrames ?: presetRunCap ?: NO_FRAME_CAP
            start("physsweep_fast_v2") { mc!!.runPhysSweepFast(cap, ::post, ::showFrame) }
            return
        }
        // Desk test, phone still: what one output can spend with capture and analysis on
        // separate threads (MeasurementController.runBudgetProbe).
        // `--es budget_mode stream|lazy|paced` (how reads reach the analysis) and
        // `--es budget_patterns "1|1,1|2"` (lane patterns, | between outputs' patterns).
        // 2026-11-03: `--ei budget_outputs` per pattern, `--ei budget_period_ms` (output clock,
        // 0 = back to back), `--ez budget_nested true` (lanes share reads: "1,2,4" = key, key + 1,
        // key + 3), `--es budget_align_frac 0.25` (share of tiles aligned); the next-setting
        // choice runs with the proposed method's curve.
        if (cmd == "budget_probe") {
            val mode = intent.getStringExtra("budget_mode") ?: "stream"
            val patterns = intent.getStringExtra("budget_patterns")
                ?.split('|')?.map { pat -> pat.split(',').map { it.trim().toInt() } }
            val ahead = intent.getIntExtra("budget_ahead", 1)
            val outputs = intent.getIntExtra("budget_outputs", 12)
            val periodMs = intent.getIntExtra("budget_period_ms", 0).toLong()
            val nested = intent.getBooleanExtra("budget_nested", false)
            val alignFrac = intent.getStringExtra("budget_align_frac")?.toDoubleOrNull() ?: 1.0
            val curve = runCatching {
                HowToSense.Curve.parse(curveText())
            }.getOrNull()
            // Beeps, for a run the operator unplugs: 1 when the first pattern starts, 2 at each
            // next pattern, 3 when done.
            var patternsStarted = 0
            val status: (String) -> Unit = { s ->
                post(s)
                when {
                    s.startsWith("budget: done") -> playLapBeeps(3)
                    s.startsWith("budget: $mode ") -> playLapBeeps(if (patternsStarted++ == 0) 1 else 2)
                }
            }
            start("${cmd}_$mode") {
                mc!!.runBudgetProbe(status, mode, patterns, ahead, outputs, periodMs, nested, alignFrac, curve)
            }
            return
        }
        // Desk test, device still: read interval per shutter and how many reads a new setting
        // takes (MeasurementController.runReadProbe); `--ei probe_iso` (default 800).
        if (cmd == "read_probe") {
            val iso = intent.getIntExtra("probe_iso", 800)
            start(cmd) { mc!!.runReadProbe(::post, iso) }
            return
        }
        // The additional-exposure check: addl_0, addl_<extra>_merge | _sep (extra 1-5), the key
        // read plus that many more at its setting after a trace; a trailing _every, on every
        // frame (MeasurementController.runAddl).
        // The glass's dedicated capture session (MeasurementController.runGlassCollect): 15 s, then
        // one beep: stand facing cars; two beeps: walk; three beeps: done. `--ei collect_stand_s`,
        // `--ei collect_walk_s`, `--ei collect_countdown_s`.
        if (cmd == "collect_glass") {
            val countdown = intent.getIntExtra("collect_countdown_s", 15)
            val stand = intent.getIntExtra("collect_stand_s", 60)
            val walk = intent.getIntExtra("collect_walk_s", 180)
            val status: (String) -> Unit = { s ->
                post(s)
                when {
                    s.startsWith("collect: countdown ") -> runOnUiThread {
                        showWalk("시작까지 ${s.removePrefix("collect: countdown ")}초", "전용 촬영", "삑: 차를 보고 서 있기") }
                    s == "collect: stand" -> { playLapBeeps(1); runOnUiThread {
                        showWalk("서 있기", "${stand}초", "삑삑: 걷기 시작") } }
                    s == "collect: walk" -> { playLapBeeps(2); runOnUiThread {
                        showWalk("걷기", "${walk}초", "삑삑삑: 끝") } }
                    s == "collect: done" -> { playLapBeeps(3); runOnUiThread { showWalk("끝", "전용 촬영 완료", "") } }
                }
            }
            start(cmd) { mc!!.runGlassCollect(status, countdown, stand, walk) }
            return
        }
        // The glass's tolerance-curve record (MeasurementController.runCurveRecord): 15 s, then one
        // beep: walk, one direction through several parts of the garage; three beeps: done.
        // `--ei curve_moments` (default 200), `--ei curve_countdown_s` (default 15).
        if (cmd == "curve_record") {
            val countdown = intent.getIntExtra("curve_countdown_s", 15)
            val moments = intent.getIntExtra("curve_moments", 200)
            val status: (String) -> Unit = { s ->
                post(s)
                when {
                    s.startsWith("curve: countdown ") -> runOnUiThread {
                        showWalk("시작까지 ${s.removePrefix("curve: countdown ")}초", "보정 기록", "삑: 걷기 시작") }
                    s == "curve: start" -> { playLapBeeps(1); runOnUiThread {
                        showWalk("걷기", "보정 기록 0/$moments", "한 방향으로 여러 구역을 지나가기") } }
                    s.startsWith("curve: moment ") -> runOnUiThread {
                        showWalk("걷기", "보정 기록 ${s.removePrefix("curve: moment ").substringBefore(' ')}",
                            "삑삑삑: 끝 · 정지: 두 번 누르기") }
                    s == "curve: done" -> { playLapBeeps(3); runOnUiThread { showWalk("끝", "보정 기록 완료", "") } }
                }
            }
            start(cmd) { mc!!.runCurveRecord(status, countdown, moments) }
            return
        }
        // The proposed method of the 2026-11-03 app spec (MeasurementController.runSense):
        // `--ei sense_images` = B, the images one output may detect (default 2: the glass's
        // 2026-11-03 costs at 2 fps, docs/RAYNEO_COSTS_2026-11-03.md).
        if (cmd == "sense") {
            val curve = runCatching {
                HowToSense.Curve.parse(curveText())
            }.getOrElse { post("sense needs assets/$CURVE_FILE: ${it.message}"); return }
            val cap = launchCollectFrames ?: presetRunCap ?: NO_FRAME_CAP
            val images = intent.getIntExtra("sense_images", 2).coerceIn(1, 3)
            // Desk tests: --ez sense_every true (once, then forgotten): the sums on every output.
            val every = intent?.getBooleanExtra("sense_every", false) == true
            intent?.removeExtra("sense_every")
            start(cmd) { mc!!.runSense(curve, images, cap, ::post, ::showFrame, everyOutput = every) }
            return
        }
        Regex("""addl_([0-5])(?:_(merge|sep))?(_every)?""").matchEntire(cmd)?.let { m ->
            val extra = m.groupValues[1].toInt(); val use = m.groupValues[2]
            if ((extra == 0) != use.isEmpty()) { post("addl: $cmd takes _merge or _sep exactly when extra > 0"); return }
            val curve = runCatching {
                HowToSense.Curve.parse(curveText())
            }.getOrElse { post("addl needs assets/$CURVE_FILE: ${it.message}"); return }
            val cap = launchCollectFrames ?: presetRunCap ?: NO_FRAME_CAP
            // _every, or for desk tests --ez addl_every true (once, then forgotten): every frame additional.
            val every = m.groupValues[3].isNotEmpty() || intent?.getBooleanExtra("addl_every", false) == true
            intent?.removeExtra("addl_every")
            // Desk tests: --ei addl_start_cell c forces the start cell once (then forgotten).
            val forced = intent?.getIntExtra("addl_start_cell", -1)?.takeIf { it in 0 until 25 }
            intent?.removeExtra("addl_start_cell")
            start(cmd) { mc!!.runAddl(extra, use == "merge", curve, cap, ::post, ::showFrame,
                everyFrameAdditional = every, startCellOverride = forced) }
            return
        }
        // AE-Phone and fixed cells from the repeating stream's newest read, as sense takes its key
        // (MeasurementController.runNewest; 2026-11-03 handover §3, the same picture age):
        // newest_ae_phone, newest_fixed_<iso>_<shutter denominator>, e.g. newest_fixed_1600_30 (the
        // 2026-09-01 walk's custom AE, which sat at 1600 x 1/30 for 98-99% of its frames).
        Regex("""newest_(ae_phone|fixed_(\d+)_(\d+))""").matchEntire(cmd)?.let { m ->
            val cap = launchCollectFrames ?: presetRunCap ?: NO_FRAME_CAP
            val iso = m.groupValues[2].toIntOrNull()
            val expUs = m.groupValues[3].toIntOrNull()?.let { Math.round(1e6 / it).toInt() } ?: 0
            if (iso != null && expUs !in com.example.activeperception.acquire.Duel.SHUTTERS_US) { post("newest: 1/${m.groupValues[3]} is not a lattice shutter"); return }
            start(cmd) { mc!!.runNewest(iso, expUs, cap, ::post, ::showFrame) }
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
            "ae_phone" -> R.id.methodAePhone
            "prop_c" -> R.id.methodContinuous
            "physsweep" -> R.id.methodPhysSweep
            "shin_nm" -> R.id.methodShinNM
            "nae" -> R.id.methodNeuralAe
            else -> 0
        }
        // An unknown name used to press Start with whatever radio happened to be checked.
        if (rid == 0) { post("unknown method: $cmd"); return }
        findViewById<android.widget.RadioButton>(rid).isChecked = true
        btnStart.performClick()
    }

    private fun stopMeasurement(userInitiated: Boolean = true) {
        // A stop the operator asked for ends the lap plan too, not just the current block.
        if (userInitiated) lapSession?.abort()
        // A run still opening (mc is the previous run's until the new controller exists) stops
        // as soon as it has one.
        if (runActive) stopPending = true
        mc?.stop()
        post("stopping…")
        runOnUiThread { overlay.clear(); cellText.text = "—" }
    }

    /** Until when a second tap on the glass stops the walk (0 = not asked). */
    private var walkStopArmedUntil = 0L

    /** The glass's screen, in both eyes: the curve's state on top, [title] large, [detail] and
     *  [sub] under it (an empty one is hidden); the runs to choose only while [idle]. */
    private fun showWalk(title: String, detail: String, sub: String, idle: Boolean = false) {
        val (stateText, stateColor) = curveState()
        for (e in eyes) {
            e.state.text = stateText; e.state.setTextColor(stateColor)
            e.title.text = title
            e.detail.text = detail
            e.detail.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
            e.sub.text = sub
            e.sub.visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE
        }
        showGlassChoices(idle)
    }

    /** One run the glass's idle screen offers. */
    private class GlassRun(val label: String, val detail: String)
    /** The glass's runs, preserving the original record/walk choices and adding an independent
     *  oscillating-turntable experiment; the final choice advances the experimenter. */
    private val GLASS_RUNS = listOf(
        GlassRun("보정 기록", "곡선용 · 한 번만 · 약 6분"),
        GlassRun("시험 걷기", "8개 방법 편도 1번씩 · 약 3분"),
        GlassRun("본 걷기", "8개 방법 × 2번 · 약 11분"),
        GlassRun("회전 실험", "8개 방법 × 2번 · 동일 phase 왕복"),
        GlassRun("실험자", "눌러서 바꾸기 (1~9) · 기록마다 남음"))
    private var glassChoiceSet = false

    /** The idle screen: the chosen run large, the list under it. Before the glass has its own
     *  curve the curve record is chosen first. */
    private fun showGlassIdle() {
        if (!glassChoiceSet) { glassChoiceSet = true; glassChoice = if (rayneoCurve().isFile) 2 else 0 }
        val r = GLASS_RUNS[glassChoice]
        if (glassChoice == 4) showWalk("실험자 ${ExperimentSession.experimenter}", r.detail, "밀어서 고르기", idle = true)
        else showWalk(r.label, r.detail, "밀어서 고르기 · 눌러서 시작", idle = true)
    }

    private fun showGlassChoices(visible: Boolean) {
        val curveOk = rayneoCurve().isFile
        for (e in eyes) {
            e.choiceBox.visibility = if (visible) View.VISIBLE else View.GONE
            e.choices.forEachIndexed { i, t ->
                t.visibility = View.VISIBLE
                t.text = GLASS_RUNS[i].label + (if (i == 0 && curveOk) "  ✓" else "") +
                    (if (i == 4) ": ${ExperimentSession.experimenter}" else "")
                val on = i == glassChoice
                t.setTextColor(if (on) android.graphics.Color.WHITE else 0xFF9E9E9E.toInt())
                t.setBackgroundColor(if (on) 0xFF1E63B5.toInt() else android.graphics.Color.TRANSPARENT)
            }
        }
    }

    /** The walk's block-end choices on the glass ([WalkSession.confirmChoice]); empty hides them. */
    private fun showChoiceList(labels: List<String>, selected: Int) {
        for (e in eyes) {
            e.choiceBox.visibility = if (labels.isEmpty()) View.GONE else View.VISIBLE
            e.choices.forEachIndexed { i, t ->
                t.visibility = if (i < labels.size) View.VISIBLE else View.GONE
                if (i >= labels.size) return@forEachIndexed
                t.text = labels[i]
                val on = i == selected
                t.setTextColor(if (on) android.graphics.Color.WHITE else 0xFF9E9E9E.toInt())
                t.setBackgroundColor(if (on) 0xFF1E63B5.toInt() else android.graphics.Color.TRANSPARENT)
            }
        }
    }

    /** The folder of the last run started (its logger's). */
    @Volatile private var lastRunDir: java.io.File? = null

    /** Moves the last run's folder into discarded/ beside it (the wearer chose to collect its
     *  block again): out of the runs the analysis reads, never deleted. Returns its name. */
    private fun discardLastRun(): String {
        val d = lastRunDir ?: return "none"
        val to = java.io.File(java.io.File(d.parentFile, "discarded").apply { mkdirs() }, d.name)
        return if (d.renameTo(to)) "discarded/${d.name}" else "${d.name} (move failed)"
    }

    /** A tap on the idle glass: starts the chosen run, always with its defaults. Overrides an adb
     *  launch left in the intent must not carry over (2026-11-03: a 10-moment desk test's
     *  curve_moments made the wearer's curve record 10 moments long). */
    private fun startGlassChoice() {
        for (k in listOf("curve_moments", "curve_countdown_s", "walk_plan", "walk_rounds", "walk_legs",
                "walk_kind", "walk_start_delay_s", "rotation_plan", "rotation_rounds",
                "rotation_start_delay_s", "rotation_calibration_cycles", "collect_frames")) intent.removeExtra(k)
        launchCollectFrames = null
        when (glassChoice) {
            0 -> launchRunnerCommand("curve_record")
            1 -> { walkKind = "check"; toggleWalkSession() }
            2 -> { walkKind = "main"; toggleWalkSession() }
            3 -> toggleRotationSession()
            else -> {
                ExperimentSession.experimenter = ExperimentSession.experimenter % 9 + 1
                prefs.edit().putInt("experimenter", ExperimentSession.experimenter).apply()
                showGlassIdle()
            }
        }
    }

    /** The glass's own tolerance curve: built on the computer from its curve record and pushed to
     *  the app's files dir. sense and the walk use it when present, else the S26 curve in assets
     *  ([CURVE_FILE]). */
    private fun rayneoCurve(): java.io.File = java.io.File(getExternalFilesDir(null), RAYNEO_CURVE_FILE)
    private fun curveText(): String = rayneoCurve().takeIf { it.isFile }?.readText()
        ?: assets.open(CURVE_FILE).bufferedReader().use { it.readText() }

    /** A curve record that ended (summary.json) with at least 100 moments, on the glass. */
    private fun curveRecordDone(): Boolean = listOf(java.io.File(getExternalFilesDir(null), "sos"),
        java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS), "sos"))
        .flatMap { it.listFiles()?.toList().orEmpty() }
        .any { d -> d.name.contains("_curve_record_") && java.io.File(d, "summary.json").isFile &&
            runCatching { java.io.File(d, "moments.csv").useLines { it.count() } - 1 }.getOrDefault(0) >= 100 }

    /** The line on top of the glass's screen: green once its own curve is in place (initialized),
     *  yellow when the record exists but its curve is not built and pushed yet, grey before. */
    private fun curveState(): Pair<String, Int> = when {
        rayneoCurve().isFile -> "initialized · RayNeo 곡선 · 실험자 ${ExperimentSession.experimenter}" to 0xFF4CAF50.toInt()
        curveRecordDone() -> "보정 기록 있음 · 곡선 설치 전 · 실험자 ${ExperimentSession.experimenter}" to 0xFFFFC107.toInt()
        else -> "보정 전 · S26 곡선 사용 중 · 실험자 ${ExperimentSession.experimenter}" to 0xFF9E9E9E.toInt()
    }

    /** The walk comparison's methods (2026-11-03 handover §4): sense, AE-Phone and the fixed cells
     *  from the stream's newest read (the same picture age as sense), the fixed 1600 x 1/30 (the
     *  2026-09-01 walk's custom AE sat there) and 1600 x 1/60 (the garage's strongest fixed cell in the
     *  2026-11-03 curve record), Sense-well, PhysSweep, ShinNM, Neural-AE. The fixed 1/240 left on
     *  2026-11-03: the weakest cell in the curve record and that night's walk (2.85 cars an output);
     *  it stays runnable by name. sense sits mid-list, so the first and last blocks of a pass are not
     *  always its. */
    private val WALK_METHODS = listOf("newest_ae_phone", "par_physsweep_fast", "newest_fixed_1600_30", "sense",
        "par_shin_nm", "par_prop_c", "newest_fixed_1600_60", "par_nae")

    /** `--es walk_kind`: "main" (default), the experiment: [WALK_METHODS] in 2 passes, forward, back
     *  (every method 2 blocks, 2 legs each way; the drift over the session - light, battery, the walker - falls
     *  on every method alike), each block one round trip; ~11 min with 8 methods. "check": a test walk before it,
     *  every method once for one leg (~3 min), to see each runs while walking. */
    private fun walkCheck() = (walkKind ?: intent.getStringExtra("walk_kind")) == "check"
    /** "extra": [EXTRA_METHODS] alone, in its own session after the experiment walk. */
    private fun walkExtra() = (walkKind ?: intent.getStringExtra("walk_kind")) == "extra"
    /** The extra walk's method (2026-11-03): the fixed cell the curve record found strongest in the
     *  garage, ISO 1600 x 1/60 (3.21 cars an image; 1/120 3.16, 1/30 3.07). */
    private val EXTRA_METHODS = listOf("newest_fixed_1600_60")
    /** The walk kind chosen on the glass's screen; null: `--es walk_kind`. */
    private var walkKind: String? = null

    /** The walk's methods in order: [WALK_METHODS] (or `--es walk_plan a,b,...`, a rerun of some)
     *  in `--ei walk_rounds` passes (main 2, check 1), every other pass in reverse. */
    private fun walkPlan(): List<String> {
        val passes = intent.getIntExtra("walk_rounds", if (walkCheck()) 1 else 2).coerceAtLeast(1)
        val methods = intent.getStringExtra("walk_plan")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() } ?: if (walkExtra()) EXTRA_METHODS else WALK_METHODS
        return (0 until passes).flatMap { r -> if (r % 2 == 0) methods else methods.reversed() }
    }

    /** Legs (one-way walks) per method block: main 2 (one round trip, both directions), check 1.
     *  `--ei walk_legs` overrides. */
    private fun walkLegs() = intent.getIntExtra("walk_legs", if (walkCheck()) 1 else 2).coerceAtLeast(1)

    /** Short names on the glass's walk screen. */
    private fun walkName(method: String) = when (method) {
        "sense" -> "proposed"
        "addl_3_sep_every" -> "proposed (10-31)"
        "par_ae_phone" -> "AE-Phone"
        "par_prop_c" -> "Sense-well"
        "par_physsweep_fast" -> "PhysSweep"
        "par_shin_nm" -> "ShinNM"
        "par_nae" -> "Neural-AE"
        "par_fixed_1600_240" -> "fixed"
        "newest_ae_phone" -> "AE-Phone"
        "newest_fixed_1600_240" -> "fixed 1/240"
        "newest_fixed_1600_30" -> "fixed 1/30"
        "newest_fixed_1600_60" -> "fixed 1/60"
        else -> method
    }

    /** The glass's corridor session ([WalkSession]): [walkPlan]'s methods, each for [walkLegs]
     *  legs. Every run of it carries experiment_session walk_<ms>; `--es lighting` labels it.
     *  Started by the start button (a tap on the temple) or `--es run walk`; stopped by two taps
     *  (see [activateFocusedControl]). */
    private fun toggleWalkSession() {
        if (lapSession != null) { stopMeasurement(); return }
        if (runActive) { post("busy — stop first"); return }
        if (runCatching {
                HowToSense.Curve.parse(curveText())
            }.isFailure) {
            post("the proposed method needs assets/$CURVE_FILE — walk not started"); return
        }
        val plan = walkPlan()
        presetRunCap = null
        ExperimentSession.lighting = intent.getStringExtra("lighting").orEmpty()
        // A check walk's runs carry walk_check_<ms>, so they never mix into the experiment's.
        ExperimentSession.session = "${if (walkCheck()) "walk_check" else if (walkExtra()) "walk_extra" else "walk"}_${System.currentTimeMillis()}"
        val session = WalkSession(this, sensors, object : LapSession.Host {
            override fun startBlock(method: String) {
                ExperimentSession.runLap = 0
                blockRunSerial = runSerial
                launchRunnerCommand(method)
            }
            override fun runReady() = runSerial > blockRunSerial && (mc?.totalFramesLogged ?: 0) > 0
            override fun stopBlock() = stopMeasurement(userInitiated = false)
            override fun status(text: String) = post(text)
            override fun buzz(ms: Long) = vibrate(ms)
            override fun beep(times: Int) = playLapBeeps(times)
            override fun lapStarted(method: String, lapsInBlock: Int) { ExperimentSession.runLap = lapsInBlock }
            override fun sessionEnded(completed: Boolean) {
                lapSession = null
                ExperimentSession.runLap = -1
                ExperimentSession.lighting = ""; ExperimentSession.session = ""
                // The session's last screen (done or stopped) stays; the runs to choose return.
                showGlassChoices(true)
            }
        }, plan, legsPerBlock = walkLegs(),
            // 15 s (2026-11-03): time to unplug the cable and put the glasses on; the block-start
            // beeps mark the end. `--ei walk_start_delay_s` overrides.
            startDelayS = intent.getIntExtra("walk_start_delay_s", 15).coerceAtLeast(0),
            displayName = ::walkName,
            screen = { title, detail, sub -> showWalk(title, detail, sub) },
            choices = ::showChoiceList, discardLastRun = ::discardLastRun)
        lapSession = session
        session.start()
    }

    /** The turntable plan uses exactly the current comparison methods. As in the main walk, pass
     *  two is reversed so slow light/temperature/battery drift does not always favour one end. */
    private fun rotationPlan(): List<RotationSession.Block> {
        val methods = intent.getStringExtra("rotation_plan")?.split(',')?.map { it.trim() }
            ?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() } ?: WALK_METHODS
        val passes = intent.getIntExtra("rotation_rounds", 2).coerceAtLeast(1)
        return (0 until passes).flatMap { pass0 ->
            val ordered = if (pass0 % 2 == 0) methods else methods.reversed()
            ordered.mapIndexed { position0, method ->
                RotationSession.Block(method, pass0 + 1, position0 + 1, methods.size)
            }
        }
    }

    /** Starts the oscillating-turntable experiment without changing any existing walk/lap path. */
    private fun toggleRotationSession() {
        if (lapSession != null) { stopMeasurement(); return }
        if (runActive) { post("busy — stop first"); return }
        if (runCatching { HowToSense.Curve.parse(curveText()) }.isFailure) {
            post("the proposed method needs assets/$CURVE_FILE — rotation not started"); return
        }
        val plan = rotationPlan()
        presetRunCap = null
        ExperimentSession.lighting = intent.getStringExtra("lighting").orEmpty()
        ExperimentSession.session = "rotation_${System.currentTimeMillis()}"
        val session = RotationSession(this, sensors, object : LapSession.Host {
            override fun startBlock(method: String) {
                blockRunSerial = runSerial
                launchRunnerCommand(method)
            }
            override fun runReady() = runSerial > blockRunSerial && (mc?.totalFramesLogged ?: 0) > 0
            override fun stopBlock() = stopMeasurement(userInitiated = false)
            override fun status(text: String) = post(text)
            override fun buzz(ms: Long) = vibrate(ms)
            override fun beep(times: Int) = playLapBeeps(times)
            override fun lapStarted(method: String, lapsInBlock: Int) = Unit
            override fun sessionEnded(completed: Boolean) {
                lapSession = null
                ExperimentSession.lighting = ""
                ExperimentSession.session = ""
                showGlassChoices(true)
            }
        }, plan,
            startDelayS = intent.getIntExtra("rotation_start_delay_s", 5).coerceAtLeast(0),
            calibrationCycles = intent.getIntExtra("rotation_calibration_cycles", 3).coerceIn(2, 6),
            displayName = ::walkName,
            screen = { title, detail, sub -> showWalk(title, detail, sub) },
            currentRunName = { lastRunDir?.name.orEmpty() })
        lapSession = session
        session.start()
    }

    /** DPAD-style input is emitted by some RayNeo firmware and is also useful for testing. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // On the experiment screen the volume keys do nothing.
        if (findViewById<View>(R.id.expPanel)?.isShown == true && event.keyCode in intArrayOf(
                KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP)) return true
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
     * A long press counts as a tap: the glass's own system menu takes long presses (and
     * covers the app), so nothing here may depend on one.
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
            MotionEvent.ACTION_UP -> if (!pointerSwipeHandled) activateFocusedControl()
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
        // The glass while idle: a swipe moves the run a tap will start; at a walk block's end,
        // the choice between the next block and this one again.
        if (rayNeoTouchpad) {
            (lapSession as? WalkSession)?.takeIf { it.choosing }?.let { it.moveChoice(delta); return }
            if (lapSession == null && !runActive) {
                glassChoiceSet = true
                glassChoice = (glassChoice + delta).floorMod(GLASS_RUNS.size)
                showGlassIdle()
            }
            return
        }
        val available = availableTouchpadControls()
        if (available.isEmpty()) return
        val current = available.indexOf(currentTouchpadControl())
        val next = if (current < 0) 0 else (current + delta).floorMod(available.size)
        available[next].requestFocus()
        scrollControlIntoView(available[next])
    }

    private fun activateFocusedControl() {
        // The glass: idle, a tap starts the chosen run. During a walk or a run a tap only asks,
        // and a second tap within 3 s stops it, so touching the temple while walking cannot
        // end it.
        if (rayNeoTouchpad) {
            (lapSession as? WalkSession)?.takeIf { it.choosing }?.let { it.confirmChoice(); return }
            val now = SystemClock.elapsedRealtime()
            when {
                lapSession == null && !runActive -> startGlassChoice()
                now < walkStopArmedUntil -> {
                    walkStopArmedUntil = 0L
                    if (lapSession != null) toggleWalkSession() else stopMeasurement()
                }
                else -> {
                    walkStopArmedUntil = now + 3_000L
                    val shown = eyes[0].sub.text
                    val ask = "한 번 더 누르면 정지"
                    for (e in eyes) { e.sub.text = ask; e.sub.visibility = View.VISIBLE }
                    eyes[0].sub.postDelayed({
                        if (eyes[0].sub.text == ask) for (e in eyes) e.sub.text = shown
                    }, 3_000L)
                }
            }
            return
        }
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
                // The glass's panel is hidden: no hint about a control it cannot see.
                if (!runActive && !rayNeoTouchpad) post("Focus · ${controlLabel(focusedView)} · tap")
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
            else -> "Option"
        }
        is TextView -> view.text.toString().replace('\n', ' ').trim().ifBlank { "Control" }
        else -> "Control"
    }

    private fun Int.floorMod(size: Int): Int = ((this % size) + size) % size
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun post(s: String) = runOnUiThread {
        status.text = s
        // In a lap session auto_collect's small line shows the session's progress instead.
        if (lapSession == null)
            findViewById<TextView>(R.id.expStatus)?.let { if (it.isShown) it.text = s }
    }

    /** Detection.xyxy is already in `bmp` pixel space, so the bitmap's own dims are what the
     *  overlay needs to scale against. */
    private fun showFrame(bmp: Bitmap, dets: List<Detection>, iso: Int, expUs: Int) {
        if (!previewOn) return
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
        val par = parallelNext
        parallelNext = false
        if (runActive) { post("busy — stop first"); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) { post("no camera permission"); return }
        // All View reads happen here, on the UI thread, before the worker starts.
        val selectConf = (confThreshSpinner.selectedItem?.toString()?.toFloatOrNull()) ?: 0.25f
        displayConfThreshold = selectConf
        val sess = sessionTag()
        val plat = platformTag()
        val device = deviceId()
        val saveImagesNow = launchSaveImages ?: presetSaveImages ?: true
        val focusNow = launchFocusDiopters ?: DEFAULT_FOCUS_DIOPTERS
        applyMountOffset()
        stopPending = false
        runActive = true
        inferenceExecutor.execute {
            try {
                // A background transition invalidates camera ownership. This executes on the
                // same serial worker and therefore cannot race a GPU invocation.
                if (resourcesMustClose) {
                    closeResources()
                    resourcesMustClose = false
                }
                // The camera service can drop the camera mid-session (2026-11-03: its provider
                // died on SIGPIPE during a walk and the run stopped); the next run opens it again
                // instead of failing on the dead one. The detector stays loaded.
                if (opened && !raw.cameraAlive) {
                    post("camera lost — reopening…")
                    runCatching { raw.close() }
                    raw = RawSensorCapturer(this).apply { preferRaw10 = intent.getBooleanExtra("raw10", false) }
                    raw.open()
                    raw.mountOffsetDeg = mountOffsetDeg
                }
                if (!opened) {
                    post("opening camera…")
                    // `--ez raw10 true`: RAW10 instead of RAW_SENSOR (the glass budget).
                    raw = RawSensorCapturer(this).apply { preferRaw10 = intent.getBooleanExtra("raw10", false) }
                    raw.open()
                    raw.mountOffsetDeg = mountOffsetDeg
                    // Keep the paper's FP16 640px YOLOv8n and select the exact fixed batch for
                    // K=1/3/9. AUTO was validated on X3 Pro as OpenCL GPU delegate V2.
                    post("loading YOLO GPU batches…")
                    detector = createProfiledDetector()
                    post("GPU ready ${detector.backendSummary} — capturing first frame…")
                    opened = true
                }
                // `--ei decode_block 4|6`: the decode's block size (RawSensorCapturer.decodeMode);
                // the glass decodes 6x6 blocks by default (formation reads 2.25x fewer sites).
                when (intent.getIntExtra("decode_block", if (rayNeoTouchpad) 6 else 0)) {
                    4 -> { raw.defaultDecodeMode = 0; raw.decodeMode = 0 }
                    6 -> { raw.defaultDecodeMode = 2; raw.decodeMode = 2 }
                }
                // Platform leads the name so a mixed directory groups by rig, while the
                // epoch stays last for the parsers that split on it.
                val runName = "run_${plat}_${if (par) "par_" else ""}${modeTag}_${System.currentTimeMillis()}"
                val logger = MeasurementLogger(this, runName)
                lastRunDir = logger.dir
                val mcLocal = MeasurementController(raw, detector, grid, sensors, logger, health, selectConf)
                mcLocal.setV2Session(sess)
                mcLocal.platformTag = plat
                mcLocal.deviceId = device
                mcLocal.saveImages = saveImagesNow
                mcLocal.parallelCapture = par
                // The parallel structure's output clock (`--ei par_period_ms`), the outputs whose
                // reads are requested ahead (`--ei par_ahead`) and the images one output may detect
                // (`--ei budget_images`). Phone: 100 ms, continuous stream, no cap. The glass
                // (2026-10-30 budget): 2 fps, two outputs ahead, 3 images.
                mcLocal.outputPeriodMs = intent.getIntExtra("par_period_ms", if (rayNeoTouchpad) 500 else 100).toLong()
                mcLocal.captureAhead = intent.getIntExtra("par_ahead", if (rayNeoTouchpad) 2 else 0)
                mcLocal.budgetImages = intent.getIntExtra("budget_images", if (rayNeoTouchpad) 3 else 0)
                // `--ez par_lazy true`: the lazy capture (MeasurementController.captureLazy).
                mcLocal.captureLazy = intent.getBooleanExtra("par_lazy", false)
                mcLocal.appFilesDir = getExternalFilesDir(null)
                raw.fixedFocusDiopters = focusNow
                mc = mcLocal
                // After mc: a walk block that sees the new serial reads the new run's outputs.
                runSerial++
                if (stopPending) mcLocal.stop()
                try {
                    block()
                } catch (e: Throwable) {
                    // A run that fails leaves why in its own folder (2026-11-03: a PhysSweep stop
                    // showed only on the screen).
                    android.util.Log.e("MeasurementActivity", "run $runName failed", e)
                    runCatching {
                        java.io.File(logger.dir, "error.json").writeText(org.json.JSONObject()
                            .put("error", e.toString()).put("stack", android.util.Log.getStackTraceString(e))
                            .put("ts_ms", System.currentTimeMillis()).toString(2))
                    }
                    throw e
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
                stopPending = false
                runOnUiThread {
                    findViewById<TextView>(R.id.limoMeasured)?.text = ExperimentSession.lastLapS
                        .let { if (it.isNaN()) "lap —" else "lap %.1f s".format(it) }
                    lapSession?.onRunEnded()
                    if (lapSession == null) {
                        if (presetRunActive) {
                            restoreStepButton(stepEnding == null)
                            showNow(stepEnding ?: "done")
                            if (stepEnding != "stopped") { vibrate(800); playLapBeeps(3) }
                        }
                        restoreBoostAfterLapSession()
                        // A single adb-started run consumes its overrides too.
                        clearLaunchOverrides()
                        // The glass: after a run of its own (the curve record), its last screen
                        // stays a few seconds, then the runs to choose return.
                        if (rayNeoTouchpad) eyes[0].title.postDelayed({
                            if (lapSession == null && !runActive) showGlassIdle()
                        }, 4_000L)
                    }
                }
            }
        }
    }

    override fun onResume() { super.onResume(); sensors.registerListeners() }
    override fun onPause() {
        super.onPause()
        // A walk session is told why, so the wearer hears an alarm instead of a silent stop.
        when (val session = lapSession) {
            is WalkSession -> session.interrupted("the app was paused (display off or covered)")
            is RotationSession -> session.interrupted("the app was paused (display off or covered)")
            else -> session?.abort()
        }
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
