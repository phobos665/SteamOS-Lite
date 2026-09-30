package com.steamoslite.runtime

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import java.io.File
import kotlin.math.abs

/**
 * The battery the Steam client can see. The client reads /sys/class/power_supply/BAT<n>/ laid out
 * as a laptop's or a Deck's, which Android's supply is not (and apps often may not read it at all).
 * This writes BAT0 and BAT1 from Android's battery API into [dir], which the session binds over
 * /sys/class/power_supply, every few seconds while the session runs.
 */
class BatterySysfs(private val context: Context, val dir: File) {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        running = true
        write()
        thread = Thread({
            while (running) {
                try { Thread.sleep(PERIOD_MS) } catch (e: InterruptedException) { break }
                if (running) write()
            }
        }, "battery-sysfs").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    fun write() {
        try {
            val bm = context.getSystemService(BatteryManager::class.java)
            val sticky: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN) ?: BatteryManager.BATTERY_STATUS_UNKNOWN
            val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.takeIf { it > 0 } ?: 100
            val pct = if (level >= 0) level * 100 / scale else (bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 50)
            val voltageUv = ((sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0).toLong() * 1000L).takeIf { it > 0 } ?: 3_800_000L
            val tempDeci = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            val tech = sticky?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.takeIf { it.isNotBlank() } ?: "Li-ion"
            val chargeNowUah = bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.takeIf { it > 0 && it != Long.MIN_VALUE } ?: 0L
            val currentNowUa = bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)?.takeIf { it != Long.MIN_VALUE } ?: 0L
            val currentAvgUa = bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)?.takeIf { it != Long.MIN_VALUE && it != 0L } ?: currentNowUa
            // Vendors disagree on the current's sign; the status says which way it flows.
            val discharging = status == BatteryManager.BATTERY_STATUS_DISCHARGING || status == BatteryManager.BATTERY_STATUS_NOT_CHARGING
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING
            val chargeFullUah = if (chargeNowUah > 0 && pct > 0) chargeNowUah * 100 / pct else 0L
            val energyNowUwh = chargeNowUah * voltageUv / 1_000_000L
            val energyFullUwh = chargeFullUah * voltageUv / 1_000_000L
            val powerNowUw = abs(currentNowUa) * voltageUv / 1_000_000L
            val timeToEmptyS = if (discharging && chargeNowUah > 0 && abs(currentAvgUa) > 0) chargeNowUah * 3600L / abs(currentAvgUa) else 0L
            val timeToFullS = if (charging && chargeFullUah > chargeNowUah && abs(currentAvgUa) > 0) (chargeFullUah - chargeNowUah) * 3600L / abs(currentAvgUa) else 0L
            val statusText = when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
                BatteryManager.BATTERY_STATUS_FULL -> "Full"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
                else -> "Unknown"
            }
            val capacityLevel = when {
                status == BatteryManager.BATTERY_STATUS_FULL || pct >= 100 -> "Full"
                pct <= 5 -> "Critical"
                pct <= 15 -> "Low"
                else -> "Normal"
            }
            val attrs = linkedMapOf(
                "type" to "Battery", "present" to "1", "status" to statusText, "capacity" to "$pct",
                "capacity_level" to capacityLevel, "technology" to tech,
                "voltage_now" to "$voltageUv", "voltage_min_design" to "$voltageUv",
                "current_now" to "$currentNowUa", "current_avg" to "$currentAvgUa",
                "charge_now" to "$chargeNowUah", "charge_full" to "$chargeFullUah", "charge_full_design" to "$chargeFullUah",
                "energy_now" to "$energyNowUwh", "energy_full" to "$energyFullUwh", "energy_full_design" to "$energyFullUwh",
                "power_now" to "$powerNowUw",
                "time_to_empty_now" to "$timeToEmptyS", "time_to_empty_avg" to "$timeToEmptyS",
                "time_to_full_now" to "$timeToFullS", "time_to_full_avg" to "$timeToFullS",
                "temp" to "$tempDeci", "model_name" to Build.MODEL.replace(' ', '_'),
                "manufacturer" to Build.MANUFACTURER.replace(' ', '_'), "scope" to "System",
            )
            for (name in listOf("BAT0", "BAT1")) {
                val bat = File(dir, name).apply { mkdirs() }
                for ((k, v) in attrs) put(File(bat, k), v + "\n")
                put(File(bat, "uevent"), "POWER_SUPPLY_NAME=$name\n" + attrs.entries.joinToString("") { (k, v) -> "POWER_SUPPLY_${k.uppercase()}=$v\n" })
            }
        } catch (t: Throwable) {
            Log.w(TAG, "could not write the battery", t)
        }
    }

    /** Atomic per file: a reader in the guest never sees a half-written value. */
    private fun put(target: File, text: String) {
        val tmp = File(target.parentFile, "." + target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) { target.writeText(text); tmp.delete() }
    }

    companion object {
        private const val TAG = "BatterySysfs"
        private const val PERIOD_MS = 5_000L
    }
}
