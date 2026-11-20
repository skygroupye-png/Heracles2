package com.theohowie.heracles.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import com.theohowie.heracles.bridge.KeystoreVault
import com.theohowie.heracles.model.JEV_DEFAULT_BASE_URL
import com.theohowie.heracles.model.JEV_DEFAULT_MODEL
import com.theohowie.heracles.model.JEV_MODEL_SPECS
import com.theohowie.heracles.model.JevConfig
import com.theohowie.heracles.model.JevDecision
import com.theohowie.heracles.model.JevDeviceProfile
import com.theohowie.heracles.model.JevDiagnostics
import com.theohowie.heracles.model.JevModelSpec
import com.theohowie.heracles.model.JevReasonSource
import com.theohowie.heracles.model.JevSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit

class JevRepository(
    private val context: Context,
    private val vault: KeystoreVault,
) {
    data class LocalModelStatus(
        val spec: JevModelSpec,
        val downloaded: Boolean,
        val bytesOnDisk: Long,
        val runtimeNote: String,
    )

    data class DownloadProgress(
        val active: Boolean = false,
        val modelDir: String = "",
        val percent: Int = 0,
        val status: String = "",
    )

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<JevConfig> = _config.asStateFlow()

    private val _history = MutableStateFlow(loadHistory())
    val history: StateFlow<List<JevDecision>> = _history.asStateFlow()

    private val _lastDiagnostics = MutableStateFlow<JevDiagnostics?>(null)
    val lastDiagnostics: StateFlow<JevDiagnostics?> = _lastDiagnostics.asStateFlow()

    private val _downloadProgress = MutableStateFlow(DownloadProgress())
    val downloadProgress: StateFlow<DownloadProgress> = _downloadProgress.asStateFlow()

    private fun loadConfig(): JevConfig {
        val raw = prefs.getString(KEY_CONFIG, null) ?: return JevConfig()
        return runCatching { json.decodeFromString(JevConfig.serializer(), raw) }
            .getOrDefault(JevConfig())
    }

    fun updateConfig(transform: (JevConfig) -> JevConfig) {
        val next = transform(_config.value)
        _config.value = next
        prefs.edit().putString(KEY_CONFIG, json.encodeToString(JevConfig.serializer(), next)).apply()
    }

    fun getApiKey(): String = vault.read(KEY_JEV_API_KEY)
    fun setApiKey(key: String) = vault.write(KEY_JEV_API_KEY, key)
    fun hasApiKey(): Boolean = vault.contains(KEY_JEV_API_KEY)

    fun resolveEndpoint(rawBaseUrl: String): String {
        val clean = rawBaseUrl.trim().ifEmpty { JEV_DEFAULT_BASE_URL }
        val noQuery = clean.substringBefore('?').substringBefore('#').trimEnd('/')
        val path = runCatching { URI(noQuery).path ?: "" }.getOrDefault("")
        return when {
            path.endsWith("/decision") || path.endsWith("/decisions") || path.endsWith("/systemone") -> noQuery
            path.endsWith("/v1") || path.contains("/v1/") -> "$noQuery/decision"
            else -> "$noQuery/v1/decision"
        }
    }

    fun deviceProfile(): JevDeviceProfile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val totalMb = (mi.totalMem / (1024L * 1024L)).toInt()
        val stat = StatFs(context.filesDir.absolutePath)
        val freeMb = (stat.availableBytes / (1024L * 1024L)).toInt()
        return JevDeviceProfile(
            model = "${Build.MANUFACTURER} ${Build.MODEL}",
            totalRamMb = totalMb,
            freeStorageMb = freeMb,
            appFilesDir = context.filesDir.absolutePath,
        )
    }

    fun localModelStatuses(): List<LocalModelStatus> {
        val root = File(context.filesDir, "jev_models")
        return JEV_MODEL_SPECS.map { spec ->
            val dir = File(root, spec.dirName)
            val gguf = File(dir, spec.ggufFile)
            val ready = gguf.isFile && gguf.length() > 10_000_000L
            LocalModelStatus(
                spec = spec,
                downloaded = ready,
                bytesOnDisk = if (gguf.isFile) gguf.length() else 0L,
                runtimeNote = if (ready) "أوزان GGUF جاهزة (يتطلب الاستدلال المحلي بناء llama.cpp JNI؛ يُنصح باستخدام الوضع السحابي الآن)" else "لم يتم تنزيل الأوزان",
            )
        }
    }

    suspend fun downloadLocalModel(spec: JevModelSpec): Result<Unit> = withContext(Dispatchers.IO) {
        val dir = File(File(context.filesDir, "jev_models"), spec.dirName).apply { mkdirs() }
        val target = File(dir, spec.ggufFile)
        val url = "https://huggingface.co/${spec.ggufRepo}/resolve/main/${spec.ggufFile}"
        _downloadProgress.value = DownloadProgress(true, spec.dirName, 0, "جارٍ الاتصال بـ HuggingFace…")
        runCatching {
            val req = Request.Builder().url(url).get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("فشل التنزيل: HTTP ${resp.code}")
                val total = resp.body?.contentLength()?.takeIf { it > 0 } ?: spec.approxBytes
                val input = resp.body?.byteStream() ?: error("استجابة فارغة")
                val tmp = File(dir, "${spec.ggufFile}.part")
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = ((done * 100) / total).toInt().coerceIn(0, 99)
                        _downloadProgress.value = DownloadProgress(
                            active = true,
                            modelDir = spec.dirName,
                            percent = pct,
                            status = "جارٍ التنزيل %.1f / %.1f MB".format(done / 1048576.0, total / 1048576.0),
                        )
                    }
                }
                if (target.exists()) target.delete()
                tmp.renameTo(target)
            }
            _downloadProgress.value = DownloadProgress(false, spec.dirName, 100, "اكتمل التنزيل")
            Unit
        }.also {
            if (it.isFailure) _downloadProgress.value = DownloadProgress()
        }
    }

    fun deleteLocalModel(spec: JevModelSpec): Boolean {
        val dir = File(File(context.filesDir, "jev_models"), spec.dirName)
        return !dir.exists() || dir.deleteRecursively()
    }

    suspend fun evaluateDecision(question: String, contextText: String = ""): Result<JevDecision> =
        withContext(Dispatchers.IO) {
            val cfg = _config.value
            if (cfg.source != JevSource.CLOUD) {
                return@withContext Result.failure(
                    IllegalStateException("الاستدلال المحلي عبر llama.cpp JNI غير مدمج في هذه النسخة؛ يُرجى التبديل إلى JEV السحابي لإجراء التقييم الاحتمالي الفوري."),
                )
            }
            val apiKey = getApiKey()
            if (apiKey.isBlank()) {
                return@withContext Result.failure(IllegalStateException("لم يتم إعداد مفتاح API السحابي لـ JEV بعد"))
            }
            val endpoint = resolveEndpoint(cfg.cloudBaseUrl)
            val modelCorrected = cfg.cloudModel != JEV_DEFAULT_MODEL
            val modelName = JEV_DEFAULT_MODEL
            val started = System.currentTimeMillis()

            val firstResp = postDecision(endpoint, apiKey, modelName, question, contextText, includeInstructions = true)
            var retried422 = false
            val finalResp = if (firstResp.httpStatus == 422) {
                retried422 = true
                postDecision(endpoint, apiKey, modelName, question, contextText, includeInstructions = false)
            } else {
                firstResp
            }
            val elapsed = (System.currentTimeMillis() - started).toInt()
            val modelsReadable = probeModelsCatalog(endpoint, apiKey)

            val diag = JevDiagnostics(
                resolvedEndpoint = endpoint,
                httpStatus = finalResp.httpStatus,
                serverCode = finalResp.serverCode,
                serverMessage = finalResp.serverMessage,
                traceId = finalResp.traceId,
                rawBody = redactSecrets(finalResp.rawBody).take(2048),
                elapsedMs = elapsed,
                modelCorrected = modelCorrected,
                attempts = if (retried422) 2 else 1,
                retriedAfter422 = retried422,
                modelsCatalogReadable = modelsReadable,
                transportError = finalResp.transportError,
                hint = if (finalResp.httpStatus == 401 || finalResp.httpStatus == 403) "يرجى التحقق من صحة مفتاح JEV API" else null,
            )
            _lastDiagnostics.value = diag

            val code = finalResp.httpStatus ?: 0
            if (finalResp.transportError != null || code !in 200..299) {
                return@withContext Result.failure(
                    IllegalStateException(finalResp.serverMessage ?: finalResp.transportError ?: "HTTP ${finalResp.httpStatus}"),
                )
            }

            val parsedProb = parseYesProbability(finalResp.rawBody)
                ?: return@withContext Result.failure(IllegalStateException("استجابة JEV تفتقر إلى حقل احتمالية recommend"))
            val thought = parseModelThought(finalResp.rawBody)

            val decision = JevDecision(
                id = "jev_" + UUID.randomUUID().toString().take(8),
                question = question.trim(),
                yesProb = parsedProb.coerceIn(0.0, 1.0),
                engineLabel = "سحابي · $modelName",
                elapsedMs = elapsed,
                createdAtMs = System.currentTimeMillis(),
                reason = thought,
                reasonSource = if (thought != null) JevReasonSource.MODEL_THOUGHT else JevReasonSource.NONE,
            )
            appendHistory(decision)
            Result.success(decision)
        }

    private data class HttpOutcome(
        val httpStatus: Int? = null,
        val rawBody: String = "",
        val serverCode: String? = null,
        val serverMessage: String? = null,
        val traceId: String? = null,
        val transportError: String? = null,
    )

    private fun postDecision(
        endpoint: String,
        apiKey: String,
        model: String,
        question: String,
        contextText: String,
        includeInstructions: Boolean,
    ): HttpOutcome {
        val payload = JSONObject().apply {
            put("model", model)
            put("prompt", if (contextText.isBlank()) question else "$contextText\n\n$question")
            if (includeInstructions) {
                put("instructions", "Evaluate the decision concisely and return binary probability for recommend.")
            }
            put(
                "questions",
                JSONArray().put(
                    JSONObject().put("key", "recommend").put("question", question),
                ),
            )
        }
        return runCatching {
            val req = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val obj = runCatching { JSONObject(body) }.getOrNull()
                HttpOutcome(
                    httpStatus = resp.code,
                    rawBody = body,
                    serverCode = obj?.optString("code")?.ifBlank { null },
                    serverMessage = obj?.optString("message")?.ifBlank { obj.optString("detail").ifBlank { null } },
                    traceId = resp.header("x-trace-id") ?: obj?.optString("trace_id")?.ifBlank { null },
                )
            }
        }.getOrElse {
            HttpOutcome(transportError = it.message ?: it.javaClass.simpleName)
        }
    }

    private fun probeModelsCatalog(endpoint: String, apiKey: String): Boolean = runCatching {
        val modelsUrl = endpoint.substringBefore("/decision").trimEnd('/') + "/models"
        val req = Request.Builder()
            .url(modelsUrl)
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        http.newCall(req).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    private fun parseYesProbability(rawBody: String): Double? {
        val obj = runCatching { JSONObject(rawBody) }.getOrNull() ?: return null
        val answers = obj.optJSONObject("answers") ?: obj.optJSONObject("decisions")
        val rec = answers?.optJSONObject("recommend") ?: obj.optJSONObject("recommend")
        return when {
            rec != null && rec.has("yes_prob") -> rec.optDouble("yes_prob")
            rec != null && rec.has("probability") -> rec.optDouble("probability")
            rec != null && rec.has("p_yes") -> rec.optDouble("p_yes")
            obj.has("yes_prob") -> obj.optDouble("yes_prob")
            else -> null
        }?.takeIf { !it.isNaN() }
    }

    private fun parseModelThought(rawBody: String): String? {
        val obj = runCatching { JSONObject(rawBody) }.getOrNull() ?: return null
        val answers = obj.optJSONObject("answers") ?: obj.optJSONObject("decisions")
        val thought = answers?.optJSONObject("recommend")?.optString("thought")
        return thought?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun redactSecrets(input: String): String = input
        .replace(Regex("Bearer\\s+[A-Za-z0-9._-]+"), "Bearer ***")
        .replace(Regex("sk[_-][A-Za-z0-9._-]+"), "sk_***")

    private fun appendHistory(item: JevDecision) {
        val next = (listOf(item) + _history.value).take(40)
        _history.value = next
        prefs.edit().putString(
            KEY_HISTORY,
            json.encodeToString(ListSerializer(JevDecision.serializer()), next),
        ).apply()
    }

    fun clearHistory() {
        _history.value = emptyList()
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    private fun loadHistory(): List<JevDecision> {
        val raw = prefs.getString(KEY_HISTORY, "[]") ?: "[]"
        return runCatching {
            json.decodeFromString(ListSerializer(JevDecision.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val PREFS_NAME = "heracles_jev"
        private const val KEY_CONFIG = "jev_config"
        private const val KEY_HISTORY = "jev_history"
        private const val KEY_JEV_API_KEY = "heracles.jev.api_key"
    }
}
