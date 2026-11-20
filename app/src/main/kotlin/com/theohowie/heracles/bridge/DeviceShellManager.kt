package com.theohowie.heracles.bridge

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import roro.stellar.Stellar

object DeviceShellManager {

    const val REQUEST_CODE = 2001

    enum class Route(val label: String) {
        STELLAR("Stellar"),
        SHIZUKU("Shizuku"),
        NONE("غير متصل"),
    }

    data class StellarStatus(
        val available: Boolean,
        val granted: Boolean,
        val uid: Int = -1,
    ) {
        val isRoot: Boolean get() = uid == 0
    }

    data class Diagnostics(
        val route: Route,
        val stellarInstalled: Boolean,
        val stellarRunning: Boolean,
        val stellarGranted: Boolean,
        val stellarUid: Int,
        val stellarRoot: Boolean,
        val shizukuInstalled: Boolean,
        val shizukuPing: Boolean,
        val shizukuGranted: Boolean,
        val shizukuPreV11: Boolean,
    )

    private const val PERMISSION = "stellar"
    private const val STELLAR_MANAGER_PACKAGE = "roro.stellar.manager"
    private const val SHIZUKU_MANAGER_PACKAGE = "moe.shizuku.privileged.api"

    @Volatile
    var appContext: Context? = null

    fun stellarStatus(): StellarStatus = try {
        val ping = Stellar.pingBinder()
        StellarStatus(
            available = ping,
            granted = ping && Stellar.checkSelfPermission(PERMISSION),
            uid = if (ping) runCatching { Stellar.uid }.getOrDefault(-1) else -1,
        )
    } catch (_: Throwable) {
        StellarStatus(false, false)
    }

    fun shizukuStatus(): Triple<Boolean, Boolean, Boolean> = try {
        val ping = Shizuku.pingBinder()
        val pre = runCatching { Shizuku.isPreV11() }.getOrDefault(false)
        val granted = ping && !pre &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        Triple(ping, granted, pre)
    } catch (_: Throwable) {
        Triple(false, false, false)
    }

    fun currentRoute(): Route {
        val s = stellarStatus()
        if (s.available && s.granted) return Route.STELLAR
        val (szPing, szGranted, _) = shizukuStatus()
        if (szPing && szGranted) return Route.SHIZUKU
        return Route.NONE
    }

    fun requestPermissionAndRun(
        command: String,
        onResult: (success: Boolean, output: String) -> Unit,
    ) {
        val s = stellarStatus()
        if (s.available) {
            if (s.granted) runViaStellar(command, onResult) else stellarRequestThenRun(command, onResult)
            return
        }
        val (szPing, szGranted, _) = shizukuStatus()
        if (szPing) {
            if (szGranted) ShizukuManager.runCommand(command, onResult)
            else ShizukuManager.requestPermissionAndRun(command, onResult)
            return
        }
        onResult(false, "لم يتم رصد خدمة Stellar أو Shizuku — يُرجى تشغيل الخدمة في أحد التطبيقين أولاً")
    }

    fun forceRequestPermission(onResult: (granted: Boolean, message: String) -> Unit) {
        val s = stellarStatus()
        if (s.available) {
            stellarRequestThenRun("id") { ok, out -> onResult(ok, out) }
            return
        }
        val (szPing, _, _) = shizukuStatus()
        if (szPing) {
            val listener = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    if (requestCode != ShizukuManager.REQUEST_CODE) return
                    Shizuku.removeRequestPermissionResultListener(this)
                    val ok = grantResult == PackageManager.PERMISSION_GRANTED
                    onResult(ok, if (ok) "تم منح إذن Shizuku" else "تم رفض إذن Shizuku")
                }
            }
            Shizuku.addRequestPermissionResultListener(listener)
            try {
                ShizukuManager.requestPermission()
            } catch (e: Throwable) {
                Shizuku.removeRequestPermissionResultListener(listener)
                onResult(false, "تعذر طلب إذن Shizuku: ${e.message}")
            }
            return
        }
        onResult(false, "لم يتم العثور على خدمة Stellar أو Shizuku نشطة")
    }

    private fun stellarRequestThenRun(
        command: String,
        onResult: (success: Boolean, output: String) -> Unit,
    ) {
        val listener = object : Stellar.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(
                requestCode: Int,
                allowed: Boolean,
                onetime: Boolean,
            ) {
                if (requestCode != REQUEST_CODE) return
                Stellar.removeRequestPermissionResultListener(this)
                if (allowed) runViaStellar(command, onResult)
                else onResult(false, "تم رفض إذن Stellar")
            }
        }
        Stellar.addRequestPermissionResultListener(listener)
        try {
            Stellar.requestPermission(PERMISSION, REQUEST_CODE)
        } catch (e: Throwable) {
            Stellar.removeRequestPermissionResultListener(listener)
            onResult(false, "تعذر طلب إذن Stellar: ${e.message}")
        }
    }

    private fun runViaStellar(
        command: String,
        onResult: (success: Boolean, output: String) -> Unit,
    ) {
        try {
            val process = Stellar.newProcess(arrayOf("sh", "-c", command), null, null)
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val err = process.errorStream.bufferedReader().use { it.readText() }
            val code = process.waitFor()
            runCatching { process.destroy() }
            val text = (output + err).trim()
            onResult(code == 0, text.ifEmpty { "exit=$code" })
        } catch (e: Throwable) {
            onResult(false, "فشل تنفيذ Stellar: ${e.message}")
        }
    }

    fun diagnostics(): Diagnostics {
        val s = stellarStatus()
        val (szPing, szGranted, szPre) = shizukuStatus()
        return Diagnostics(
            route = currentRoute(),
            stellarInstalled = isInstalled(STELLAR_MANAGER_PACKAGE),
            stellarRunning = s.available,
            stellarGranted = s.granted,
            stellarUid = s.uid,
            stellarRoot = s.isRoot,
            shizukuInstalled = isInstalled(SHIZUKU_MANAGER_PACKAGE),
            shizukuPing = szPing,
            shizukuGranted = szGranted,
            shizukuPreV11 = szPre,
        )
    }

    private fun isInstalled(pkg: String): Boolean = try {
        appContext?.packageManager?.getPackageInfo(pkg, 0) != null
    } catch (_: Throwable) {
        false
    }
}
