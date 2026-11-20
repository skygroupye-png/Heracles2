package com.theohowie.heracles.bridge

import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper

object ShizukuManager {

    const val REQUEST_CODE = 2001

    fun isAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean = runCatching {
        isAvailable() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun diagnostics(): Map<String, Any?> {
        val ping = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val preV11 = runCatching { Shizuku.isPreV11() }.getOrDefault(false)
        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        val sui = runCatching { rikka.sui.Sui.isSui() }.getOrDefault(false)
        return mapOf(
            "shizukuPing" to ping,
            "shizukuPreV11" to preV11,
            "shizukuGranted" to granted,
            "sui" to sui,
        )
    }

    fun requestPermission() {
        if (!isAvailable()) return
        if (Shizuku.isPreV11()) {
            throw IllegalStateException("إصدار Shizuku قديم جداً (قبل v11)، يرجى تحديث تطبيق Shizuku")
        }
        Shizuku.requestPermission(REQUEST_CODE)
    }

    private fun service(): IShizukuService {
        val binder: IBinder = Shizuku.getBinder()
            ?: throw IllegalStateException("خدمة Shizuku غير متاحة (غير مشغّلة أو منقطعة)")
        val wrapped = ShizukuBinderWrapper(binder)
        return IShizukuService.Stub.asInterface(wrapped)
    }

    fun requestPermissionAndRun(
        command: String,
        onResult: (success: Boolean, output: String) -> Unit,
    ) {
        if (!isAvailable()) {
            onResult(false, "خدمة Shizuku غير مثبتة أو غير مشغّلة — يرجى تثبيتها وتشغيلها أولاً")
            return
        }
        if (hasPermission()) {
            runCommand(command, onResult)
            return
        }
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != REQUEST_CODE) return
                Shizuku.removeRequestPermissionResultListener(this)
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    runCommand(command, onResult)
                } else {
                    onResult(false, "تم رفض صلاحية Shizuku")
                }
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        try {
            requestPermission()
        } catch (e: Exception) {
            Shizuku.removeRequestPermissionResultListener(listener)
            onResult(false, "تعذر طلب صلاحية Shizuku: ${e.message}")
        }
    }

    fun runCommand(command: String, onResult: (success: Boolean, output: String) -> Unit) {
        try {
            val process = service().newProcess(arrayOf("sh", "-c", command), null, null)
            val out = ParcelFileDescriptor.AutoCloseInputStream(process.inputStream)
            val errFd = ParcelFileDescriptor.AutoCloseInputStream(process.errorStream)
            val output = out.bufferedReader().use { it.readText() }
            val err = errFd.bufferedReader().use { it.readText() }
            val code = process.waitFor()
            val text = (output + err).trim()
            onResult(code == 0, text.ifEmpty { "exit=$code" })
        } catch (e: Exception) {
            onResult(false, "فشل التنفيذ: ${e.message}")
        }
    }
}
