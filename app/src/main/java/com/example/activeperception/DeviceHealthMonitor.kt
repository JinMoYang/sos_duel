package com.example.activeperception

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class DeviceHealth(
    val batteryTemperatureC: Double,
    val thermalStatus: Int,
    val totalPssKb: Int,
    /** Whole-process CPU over the last sampling interval, in percent of ONE core: 200
     *  means two cores fully busy. -1 on the first sample (no interval to divide by). */
    val cpuPercent: Double,
    val javaHeapKb: Int,
    val nativeHeapKb: Int,
    /** GPU/texture/EGL memory. The TFLite delegate and the candidate tensors live here,
     *  so this moves with K while total PSS barely does. */
    val graphicsKb: Int,
    /** Highest scaling_cur_freq across readable cores, MHz; -1 where the node is blocked.
     *  Logged next to thermalStatus so throttling shows up as a frequency drop rather
     *  than only as a status code. */
    val cpuMaxMhz: Int,
    /** Cumulative battery charge, uAh. Energy for a run is (charge at start - charge at
     *  end) x mean voltage; this is far steadier than integrating currentNow. Long.MIN_VALUE
     *  where the fuel gauge does not expose it. */
    val chargeUAh: Long,
    /** Instantaneous current, uA. Sign convention is vendor-specific; on Samsung it is
     *  negative while discharging. Noisy — use it to attribute, not to total. */
    val currentUA: Int,
    val voltageMv: Int,
    val batteryPct: Int,
    /** BatteryManager.EXTRA_PLUGGED. NON-ZERO MEANS THE ENERGY COLUMNS ARE MEANINGLESS:
     *  a phone on USB is charging, so charge counts up while the run drains it. Logged so
     *  the analysis can reject such runs instead of silently averaging them in. */
    val pluggedState: Int
)

/** Low-rate cached health sample so per-frame logging does not add per-frame binder work. */
class DeviceHealthMonitor(private val context: Context) {
    @Volatile private var lastAtMs = 0L
    @Volatile private var cached = DeviceHealth(Double.NaN, -1, -1, -1.0, -1, -1, -1, -1,
        Long.MIN_VALUE, Int.MIN_VALUE, -1, -1, -1)
    private val sampler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "SoS-HealthSampler")
    }

    private val batteryManager by lazy {
        context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    }

    private val clkTck = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }
        .getOrDefault(100L).coerceAtLeast(1L)
    private var lastCpuTicks = -1L
    private var lastCpuAtMs = 0L

    /** Resolved once: a device exposes only a subset of these, and a node that is
     *  unreadable at startup stays unreadable for the life of the process. */
    private val freqNodes: List<File> by lazy {
        runCatching {
            File("/sys/devices/system/cpu")
                .listFiles { f -> f.name.matches(Regex("cpu\\d+")) }
                ?.map { File(it, "cpufreq/scaling_cur_freq") }
                ?.filter { it.canRead() }
                ?: emptyList()
        }.getOrDefault(emptyList())
    }

    init {
        sampler.scheduleAtFixedRate({ runCatching { refresh() } },
            0L, 2L, TimeUnit.SECONDS)
    }

    /** Critical-path reads never perform binder/PSS/procfs work. */
    fun sample(): DeviceHealth = cached

    /** utime+stime in clock ticks. Field 2 (comm) can itself contain spaces and
     *  parentheses, so the split has to start after the LAST ')'. */
    private fun procCpuTicks(): Long = runCatching {
        val s = File("/proc/self/stat").readText()
        val rest = s.substring(s.lastIndexOf(')') + 2).split(' ')
        // Dropping fields 1-2 puts field 3 (state) at index 0, so utime=14 -> 11, stime=15 -> 12.
        rest[11].toLong() + rest[12].toLong()
    }.getOrDefault(-1L)

    @Synchronized
    private fun refresh() {
        val now = SystemClock.elapsedRealtime()
        val battery = context.registerReceiver(null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tenthsC = battery?.getIntExtra("temperature", Int.MIN_VALUE) ?: Int.MIN_VALUE
        val thermal = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .currentThermalStatus
        val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        fun stat(key: String) = runCatching {
            memory.getMemoryStat(key)?.toIntOrNull() ?: -1
        }.getOrDefault(-1)

        val ticks = procCpuTicks()
        var cpu = -1.0
        if (ticks >= 0 && lastCpuTicks >= 0 && now > lastCpuAtMs) {
            cpu = (ticks - lastCpuTicks).toDouble() / clkTck /
                ((now - lastCpuAtMs) / 1000.0) * 100.0
        }
        if (ticks >= 0) { lastCpuTicks = ticks; lastCpuAtMs = now }

        val mhz = freqNodes.mapNotNull { n ->
            runCatching { n.readText().trim().toLong() / 1000L }.getOrNull()
        }.maxOrNull()?.toInt() ?: -1

        fun longProp(id: Int) = runCatching {
            batteryManager.getLongProperty(id)
        }.getOrDefault(Long.MIN_VALUE)
        fun intProp(id: Int) = runCatching {
            batteryManager.getIntProperty(id)
        }.getOrDefault(Int.MIN_VALUE)

        cached = DeviceHealth(
            if (tenthsC == Int.MIN_VALUE) Double.NaN else tenthsC / 10.0,
            thermal,
            memory.totalPss,
            cpu,
            stat("summary.java-heap"),
            stat("summary.native-heap"),
            stat("summary.graphics"),
            mhz,
            longProp(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
            intProp(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1,
            intProp(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        )
        lastAtMs = now
    }

    fun close() { sampler.shutdownNow() }
}
