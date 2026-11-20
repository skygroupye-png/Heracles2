package com.theohowie.heracles.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DeviceShell {
    companion object {
        const val MAX_CAPTURED_BYTES: Int = 1 * 1024 * 1024
        const val EXIT_NO_SHELL: Int = -2
        const val DEFAULT_TIMEOUT_MS: Long = 120_000
        const val MAX_TIMEOUT_MS: Long = 15 * 60_000
    }

    data class ShellResult(
        val ok: Boolean,
        val code: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean,
        val durationMs: Long,
        val truncated: Boolean = false,
        val error: String? = null,
    )

    suspend fun run(cmd: String, cwd: String?, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ShellResult =
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            val budget = when {
                timeoutMs <= 0 -> DEFAULT_TIMEOUT_MS
                timeoutMs > MAX_TIMEOUT_MS -> MAX_TIMEOUT_MS
                else -> timeoutMs
            }

            val route = runCatching { DeviceShellManager.currentRoute() }
                .getOrDefault(DeviceShellManager.Route.NONE)
            if (route == DeviceShellManager.Route.NONE) {
                return@withContext ShellResult(
                    ok = false,
                    code = EXIT_NO_SHELL,
                    stdout = "",
                    stderr = "لم يتم اكتشاف خدمة Stellar أو Shizuku — يرجى تشغيل الخدمة في أحد المديرين أولاً.",
                    timedOut = false,
                    durationMs = System.currentTimeMillis() - started,
                    error = "لا تتوفر قناة Shell للجهاز",
                )
            }

            val full = buildString {
                append("umask 0000; ")
                if (!cwd.isNullOrBlank()) append("cd ${sq(cwd)} && ")
                append(cmd)
            }

            val proc = try {
                launch(route, full)
            } catch (e: Throwable) {
                return@withContext ShellResult(
                    ok = false,
                    code = EXIT_NO_SHELL,
                    stdout = "",
                    stderr = "تعذر تشغيل العملية البعيدة (${route.label}): ${e.javaClass.simpleName}: ${e.message}",
                    timedOut = false,
                    durationMs = System.currentTimeMillis() - started,
                    error = e.message,
                )
            }

            val outBuf = StringBuilder()
            val errBuf = StringBuilder()
            val outCut = AtomicBoolean(false)
            val errCut = AtomicBoolean(false)
            val t1 = Reader(proc.stdout, outBuf, outCut, "heracles-sh-out").also { it.start() }
            val t2 = Reader(proc.stderr, errBuf, errCut, "heracles-sh-err").also { it.start() }

            val done = CountDownLatch(1)
            var exit = -1
            Thread({
                exit = runCatching { proc.waitFor() }.getOrDefault(-1)
                done.countDown()
            }, "heracles-sh-wait").apply { isDaemon = true; start() }

            val finished = done.await(budget.coerceAtLeast(1), TimeUnit.MILLISECONDS)
            if (!finished) {
                runCatching { proc.destroy() }
                done.await(3, TimeUnit.SECONDS)
            }
            t1.join(2_000)
            t2.join(2_000)

            val truncated = outCut.get() || errCut.get()
            if (finished && exit == 0 && !cwd.isNullOrBlank()) {
                val fix: RemoteProc? = runCatching {
                    launch(route, "chmod -R a+rwX ${sq(cwd)} 2>/dev/null; true")
                }.getOrNull()
                if (fix != null) {
                    runCatching {
                        val chDone = CountDownLatch(1)
                        Thread({
                            runCatching { fix.waitFor() }
                            chDone.countDown()
                        }, "heracles-chmod").apply { isDaemon = true; start() }
                        if (!chDone.await(5, TimeUnit.SECONDS)) runCatching { fix.destroy() }
                        runCatching { fix.stdout.close() }
                        runCatching { fix.stderr.close() }
                    }
                }
            }

            val code = if (finished) exit else -1
            ShellResult(
                ok = code == 0 && finished,
                code = code,
                stdout = cap(outBuf.toString()),
                stderr = cap(errBuf.toString()),
                timedOut = !finished,
                durationMs = System.currentTimeMillis() - started,
                truncated = truncated,
                error = if (!finished) "انتهت مهلة الأمر (${budget} ms)، تم إنهاء العملية الفرعية" else null,
            )
        }

    private interface RemoteProc {
        val stdout: InputStream
        val stderr: InputStream
        fun waitFor(): Int
        fun destroy()
    }

    private fun launch(route: DeviceShellManager.Route, command: String): RemoteProc =
        when (route) {
            DeviceShellManager.Route.STELLAR -> StellarProc(command)
            DeviceShellManager.Route.SHIZUKU -> ShizukuProc(command)
            DeviceShellManager.Route.NONE -> error("لا يوجد مسار اتصال متاح")
        }

    private class StellarProc(command: String) : RemoteProc {
        private val p = roro.stellar.Stellar.newProcess(arrayOf("sh", "-c", command), null, null)
        override val stdout: InputStream get() = p.inputStream
        override val stderr: InputStream get() = p.errorStream
        override fun waitFor(): Int = p.waitFor()
        override fun destroy() { runCatching { p.destroy() } }
    }

    private class ShizukuProc(command: String) : RemoteProc {
        private val p = run {
            val binder = rikka.shizuku.Shizuku.getBinder()
                ?: throw IllegalStateException("خدمة Shizuku غير متاحة")
            val wrapped = rikka.shizuku.ShizukuBinderWrapper(binder)
            val svc = moe.shizuku.server.IShizukuService.Stub.asInterface(wrapped)
            svc.newProcess(arrayOf("sh", "-c", command), null, null)
        }
        override val stdout: InputStream
            get() = android.os.ParcelFileDescriptor.AutoCloseInputStream(p.inputStream)
        override val stderr: InputStream
            get() = android.os.ParcelFileDescriptor.AutoCloseInputStream(p.errorStream)
        override fun waitFor(): Int = p.waitFor()
        override fun destroy() { runCatching { p.destroy() } }
    }

    private class Reader(
        private val input: InputStream,
        private val sink: StringBuilder,
        private val cut: AtomicBoolean,
        name: String,
    ) : Thread(name) {
        init { isDaemon = true }

        override fun run() {
            try {
                val r = input.bufferedReader()
                val buf = CharArray(8 * 1024)
                while (true) {
                    val n = r.read(buf)
                    if (n < 0) break
                    if (sink.length >= MAX_CAPTURED_BYTES) {
                        cut.set(true)
                        continue
                    }
                    val room = MAX_CAPTURED_BYTES - sink.length
                    val take = if (n <= room) n else room
                    sink.appendRange(buf, 0, take)
                    if (take < n) cut.set(true)
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun cap(text: String): String =
        if (text.length > MAX_CAPTURED_BYTES) {
            text.substring(0, MAX_CAPTURED_BYTES) + "\n…(المخرجات كبيرة جداً، تم اقتطاعها)"
        } else {
            text
        }

    private fun sq(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
