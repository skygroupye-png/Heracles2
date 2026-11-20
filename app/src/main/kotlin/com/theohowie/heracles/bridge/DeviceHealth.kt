package com.theohowie.heracles.bridge

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DeviceHealth(private val context: Context) {

    data class Snapshot(
        val ok: Boolean,
        val battery: Int?,
        val tempC: Double?,
        val charging: Boolean?,
        val error: String? = null,
    )

    suspend fun read(): Snapshot = withContext(Dispatchers.IO) {
        try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                ?: return@withContext Snapshot(
                    ok = false,
                    battery = null,
                    tempC = null,
                    charging = null,
                    error = "النظام لا يوفر خدمة البطارية (BatteryManager غير متاح)",
                )

            val rawPercent = runCatching {
                bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            }.getOrDefault(Int.MIN_VALUE)
            val battery: Int? = if (rawPercent in 0..100) rawPercent else null

            val sticky: Intent? = runCatching {
                context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            }.getOrNull()

            // EXTRA_TEMPERATURE is reported in tenths of a degree Celsius (e.g., 350 -> 35.0 C)
            val tempTenths = sticky?.getIntExtra(
                BatteryManager.EXTRA_TEMPERATURE,
                Int.MIN_VALUE,
            ) ?: Int.MIN_VALUE
            val tempC: Double? = if (tempTenths == Int.MIN_VALUE || tempTenths <= 0) {
                null
            } else {
                tempTenths / 10.0
            }

            val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging: Boolean? = when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING,
                BatteryManager.BATTERY_STATUS_FULL,
                -> true
                BatteryManager.BATTERY_STATUS_DISCHARGING,
                BatteryManager.BATTERY_STATUS_NOT_CHARGING,
                -> false
                else -> null
            }

            if (battery == null && tempC == null && charging == null) {
                return@withContext Snapshot(
                    ok = false,
                    battery = null,
                    tempC = null,
                    charging = null,
                    error = "تعذر قراءة حالة البطارية (لم يُرجع النظام بيانات الشحن/الحرارة/الطاقة)",
                )
            }

            Snapshot(ok = true, battery = battery, tempC = tempC, charging = charging)
        } catch (e: Throwable) {
            Snapshot(
                ok = false,
                battery = null,
                tempC = null,
                charging = null,
                error = "فشل قراءة بيانات حالة الجهاز: ${e.javaClass.simpleName}: ${e.message}",
            )
        }
    }
}
