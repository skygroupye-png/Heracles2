package com.theohowie.heracles.bridge

import android.content.Context
import com.heracles.sandbox.api.ChannelInfo
import com.heracles.sandbox.api.ExecutionRequest
import com.heracles.sandbox.api.ExecutionResult
import com.heracles.sandbox.api.MountBackend
import com.heracles.sandbox.api.MountEntry
import com.heracles.sandbox.api.PathView
import com.heracles.sandbox.api.SandboxException
import com.heracles.sandbox.channel.ChannelManager
import com.heracles.sandbox.file.AndroidSafDocumentIo
import com.heracles.sandbox.file.HostPath
import com.heracles.sandbox.file.MountRegistry
import com.heracles.sandbox.file.MountResolver
import com.heracles.sandbox.file.PathSemantics
import com.heracles.sandbox.file.SandboxFileService
import com.heracles.sandbox.runtime.ExecutionSink
import com.heracles.sandbox.runtime.NetworkPolicy
import com.heracles.sandbox.runtime.NoOpEventSink
import com.heracles.sandbox.runtime.PathResolver
import com.heracles.sandbox.runtime.SandboxDependencies
import com.heracles.sandbox.runtime.SandboxEnvManager
import com.heracles.sandbox.runtime.SandboxServiceCore
import com.heracles.sandbox.runtime.ToolchainActivation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest

/**
 * Direct Kotlin interface to the embedded PRoot + rootfs sandbox runtime AARs.
 */
class SandboxManager(private val context: Context) {

    data class SandboxStatus(
        val installed: Boolean,
        val prootReady: Boolean,
        val rootfsSummary: String,
        val channels: List<ChannelInfo>,
        val installedEnvs: List<String>,
        val mounts: List<MountEntry>,
        val buildSuiteReady: Boolean = false,
        val buildSuiteVersion: String = "",
        val buildSuiteOccupiedBytes: Long = 0L,
        val buildSuiteMissing: List<String> = emptyList(),
        val networkWhitelistOnly: Boolean = true,
        val networkRiskNote: String = "",
    ) {
        val ready: Boolean get() = installed && prootReady
    }

    data class ProgressState(
        val active: Boolean = false,
        val percent: Int = -1,
        val message: String = "",
    )

    private val baseDir: File = File(context.filesDir, BASE_DIR_NAME).apply { mkdirs() }
    private val channelManager = ChannelManager(context)
    private val envPreferences by lazy {
        context.getSharedPreferences("sandbox_installed_envs", Context.MODE_PRIVATE)
    }

    private val _progress = MutableStateFlow(ProgressState())
    val progress: StateFlow<ProgressState> = _progress.asStateFlow()

    val mounts: MountRegistry = MountRegistry {
        core.rootfs.rootfsHostPath()
    }

    private val mountResolver = MountResolver(mounts, AndroidSafDocumentIo(context))

    private val files = SandboxFileService(
        pathSemantics = PathSemantics {
            core.rootfs.rootfsHostPath()?.let { HostPath.parse(it) }
        },
        zeroWidth = com.heracles.sandbox.file.ZeroWidthFileChannel(),
        mountResolver = mountResolver,
    )

    private val core: SandboxServiceCore = SandboxServiceCore(
        dependencies = SandboxDependencies(
            eventSink = NoOpEventSink,
            pathResolver = object : PathResolver {
                override fun sandboxToHost(sandboxPath: String): String =
                    core.rootfs.sandboxToHost(sandboxPath)

                override fun hostToSandbox(hostPath: String): String? =
                    core.rootfs.hostToSandbox(hostPath)

                override fun rootfsHostRoot(): String? = core.rootfs.rootfsHostPath()
            },
            channelQuery = channelManager,
            fileAccess = { path, view -> files.read(path, view) },
        ),
        baseDir = baseDir,
        assets = context.assets,
        prootBinary = File(context.applicationInfo.nativeLibraryDir, "libproot.so"),
        loaderBinary = File(context.applicationInfo.nativeLibraryDir, "libproot-loader.so"),
        mountBindSource = mounts,
        commandRewriter = mountResolver,
    )

    private val toolchain: ToolchainActivation by lazy {
        ToolchainActivation(
            rootfsHostRoot = File(
                core.rootfs.rootfsHostPath() ?: File(baseDir, "rootfs").absolutePath,
            ),
            runInSandbox = { command, timeoutMs ->
                val out = StringBuilder()
                val err = StringBuilder()
                val res = runBlocking {
                    core.executeAndWait(
                        ExecutionRequest(
                            command = command,
                            workingDirectory = "/",
                            timeoutMillis = timeoutMs,
                        ),
                        object : ExecutionSink {
                            override fun onOutput(stream: Int, chunk: String, sequence: Long) {
                                if (stream == ExecutionSink.STREAM_STDERR) err.append(chunk)
                                else out.append(chunk)
                            }
                            override fun onProgress(percent: Int, message: String) = Unit
                            override fun onExit(result: ExecutionResult) = Unit
                            override fun onError(code: Int, message: String) = Unit
                            override fun isCancelled(): Boolean = false
                        },
                    )
                }
                ToolchainActivation.CommandOutcome(
                    exitCode = res.exitCode,
                    stdout = out.toString().ifBlank { res.stdout },
                    stderr = err.toString().ifBlank { res.stderr },
                )
            },
        )
    }

    init {
        if (runCatching { core.rootfs.status().installed }.getOrDefault(false)) {
            registerDefaultMounts()
            runCatching { core.ensureTooltools() }
        }
    }

    suspend fun status(): SandboxStatus = withContext(Dispatchers.IO) {
        val st = runCatching { core.rootfs.status() }.getOrNull()
        val installed = st?.installed ?: false
        val prootReady = File(context.applicationInfo.nativeLibraryDir, "libproot.so").isFile
        val suite = if (installed) runCatching {
            val s = toolchain.status()
            if (s.ready) runCatching { toolchain.repairPath() }
            s
        }.getOrNull() else null
        val net = NetworkPolicy()
        SandboxStatus(
            installed = installed,
            prootReady = prootReady,
            rootfsSummary = rootfsSummary(),
            channels = runCatching { core.queryChannels() }.getOrDefault(emptyList()),
            installedEnvs = if (installed) installedEnvs() else emptyList(),
            mounts = runCatching { mounts.list() }.getOrDefault(emptyList()),
            buildSuiteReady = suite?.ready ?: false,
            buildSuiteVersion = suite?.version.orEmpty(),
            buildSuiteOccupiedBytes = suite?.occupiedBytes ?: 0L,
            buildSuiteMissing = suite?.missing ?: emptyList(),
            networkWhitelistOnly = net.whitelistOnly,
            networkRiskNote = net.riskNote,
        )
    }

    suspend fun activate(ociRef: String? = null): Boolean = withContext(Dispatchers.IO) {
        if (core.rootfs.status().installed) {
            registerDefaultMounts()
            return@withContext true
        }
        _progress.value = ProgressState(true, -1, "جارٍ تفعيل نظام الملفات الجذري للبيئة المعزولة…")
        try {
            val bundled = runCatching { core.rootfs.useBundledRootfs() }.getOrNull()
            if (bundled?.isInstalled == true) {
                registerDefaultMounts()
                runCatching { core.ensureTooltools() }
                return@withContext true
            }
            if (!ociRef.isNullOrBlank()) {
                runCatching {
                    core.rootfs.installOci(ociRef)
                    val ok = core.rootfs.status().installed
                    if (ok) {
                        registerDefaultMounts()
                        runCatching { core.ensureTooltools() }
                    }
                    return@withContext ok
                }
            }
            false
        } finally {
            _progress.value = ProgressState()
        }
    }

    suspend fun resetRootfs(snapshot: String = ""): Boolean = withContext(Dispatchers.IO) {
        _progress.value = ProgressState(true, -1, "جارٍ إعادة تعيين البيئة المعزولة…")
        try {
            val st = core.resetRootfs(snapshot)
            registerDefaultMounts()
            st.installed
        } catch (_: Throwable) {
            false
        } finally {
            _progress.value = ProgressState()
        }
    }

    suspend fun createSnapshot(name: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val st = core.snapshotRootfs(name)
            st.snapshotName ?: name
        }
    }

    suspend fun execute(
        command: String,
        workdir: String = "/workspace",
        timeoutMs: Long = 120_000L,
        usePty: Boolean = false,
        onChunk: ((stream: Int, chunk: String) -> Unit)? = null,
    ): ExecutionResult = withContext(Dispatchers.IO) {
        val req = ExecutionRequest(
            command = command,
            workingDirectory = workdir,
            timeoutMillis = timeoutMs,
            usePty = usePty,
        )
        core.executeAndWait(
            req,
            object : ExecutionSink {
                override fun onOutput(stream: Int, chunk: String, sequence: Long) {
                    onChunk?.invoke(stream, chunk)
                }
                override fun onProgress(percent: Int, message: String) {
                    _progress.value = ProgressState(true, percent, message)
                }
                override fun onExit(result: ExecutionResult) = Unit
                override fun onError(code: Int, message: String) = Unit
                override fun isCancelled(): Boolean = false
            },
        )
    }

    suspend fun registerMount(
        name: String,
        hostPath: String,
        readOnly: Boolean = false,
        description: String = "",
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            mounts.register(
                MountEntry(
                    name = name,
                    backend = MountBackend.HOST_PRIVATE_DIR,
                    hostPath = hostPath,
                    readOnly = readOnly,
                    description = description,
                ),
            )
            Unit
        }
    }

    suspend fun activateBuildSuite(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        _progress.value = ProgressState(true, 0, "جارٍ التحضير لتفعيل حزمة أدوات الترجمة…")
        try {
            val a = toolchain.activate { done, total, stage ->
                val pct = if (total > 0) ((done * 100) / total).toInt() else -1
                val msg = if (total > 0) {
                    "$stage %.1f / %.1f MB".format(done / 1048576.0, total / 1048576.0)
                } else {
                    "$stage %.1f MB".format(done / 1048576.0)
                }
                _progress.value = ProgressState(true, pct, msg)
            }
            a.success to a.message
        } catch (e: Throwable) {
            false to (e.message ?: e.javaClass.simpleName)
        } finally {
            _progress.value = ProgressState()
        }
    }

    suspend fun resetBuildSuite(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        runCatching {
            val res = toolchain.reset()
            res.success to res.message
        }.getOrElse { false to (it.message ?: it.javaClass.simpleName) }
    }

    suspend fun installEnv(name: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        _progress.value = ProgressState(true, -1, "جارٍ تثبيت البيئة $name…")
        try {
            val res = SandboxEnvManager.installEnv(
                name,
                exec = { cmd, timeoutMs ->
                    val r = core.executeAndWait(
                        ExecutionRequest(command = cmd, workingDirectory = "/", timeoutMillis = timeoutMs),
                        object : ExecutionSink {
                            override fun onOutput(stream: Int, chunk: String, sequence: Long) = Unit
                            override fun onProgress(percent: Int, message: String) = Unit
                            override fun onExit(result: ExecutionResult) = Unit
                            override fun onError(code: Int, message: String) = Unit
                            override fun isCancelled(): Boolean = false
                        },
                    )
                    listOf(r.stdout, r.stderr).filter { it.isNotBlank() }.joinToString("\n")
                },
                onProgress = { msg -> _progress.value = ProgressState(true, -1, msg) },
            )
            val ok = res["ok"] == true
            val installedName = res["name"] as? String ?: name
            if (ok) updateInstalledEnv(installedName, installed = true)
            val msg = (res["message"] as? String) ?: (res["error"] as? String) ?: if (ok) "تم التثبيت بنجاح" else "فشل التثبيت"
            ok to msg
        } catch (e: Throwable) {
            false to "خطأ أثناء التثبيت: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            _progress.value = ProgressState()
        }
    }

    suspend fun uninstallEnv(name: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val recipe = SandboxEnvManager.recipe(name) ?: return@withContext false to "بيئة غير معروفة: $name"
        val isApk = recipe.installCommands.any { it.startsWith("apk add") }
        if (!isApk) return@withContext false to "هذه البيئة غير مثبّتة عبر apk (${recipe.source})"
        val pkg = recipe.installCommands.first { it.startsWith("apk add") }.removePrefix("apk add").trim()
        val r = execute("apk del $pkg", workdir = "/", timeoutMs = 120_000L)
        if (r.exitCode == 0) {
            updateInstalledEnv(name, installed = false)
            true to "تمت إزالة $pkg"
        } else {
            false to "فشل apk del (exit=${r.exitCode}): ${r.stderr.takeLast(200)}"
        }
    }

    fun availableEnvRecipes(): List<String> = listOf("python", "nodejs", "git", "curl", "gcc", "openjdk")

    private fun registerDefaultMounts() {
        runCatching {
            mounts.register(
                MountEntry(
                    name = "workspace",
                    backend = MountBackend.ROOTFS_RELATIVE,
                    hostPath = "opt/ws",
                    description = "مساحة العمل الافتراضية للبيئة المعزولة",
                ),
            )
            File(baseDir, "rootfs/opt/ws").mkdirs()
        }
    }

    private fun envStateKey(): String {
        val root = core.rootfs.rootfsHostPath() ?: return "default"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(root.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "rootfs:$digest"
    }

    private fun installedEnvs(): List<String> = runCatching {
        val raw = envPreferences.getString(envStateKey(), "[]") ?: "[]"
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
    }.getOrDefault(emptyList())

    private fun updateInstalledEnv(name: String, installed: Boolean) {
        runCatching {
            val envs = installedEnvs().toMutableSet()
            if (installed) envs += name else envs -= name
            envPreferences.edit().putString(envStateKey(), JSONArray(envs.sorted()).toString()).commit()
        }
    }

    private fun rootfsSummary(): String = runCatching {
        val s = core.rootfs.status()
        if (!s.installed) "غير مثبّت" else "${s.label} (${s.sizeBytes / 1024 / 1024} MB, snapshots=${s.snapshotCount})"
    }.getOrDefault("غير مهيّأ")

    companion object {
        private const val BASE_DIR_NAME = "sandbox"
    }
}
