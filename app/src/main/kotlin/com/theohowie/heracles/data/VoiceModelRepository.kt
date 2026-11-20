package com.theohowie.heracles.data

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.theohowie.heracles.bridge.VoiceRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

class VoiceModelRepository(private val context: Context) {

    enum class ModelId(
        val id: String,
        val displayName: String,
        val subtitle: String,
        val dirName: String,
    ) {
        BUNDLED_ZIPFORMER_ZH(
            id = "zipformer-zh-14m",
            displayName = "Zipformer المدمج للبث الصوتي (14M)",
            subtitle = "مدمج في التطبيق · جاهز بدون تنزيل · استجابة سريعة",
            dirName = "streaming-zipformer-zh-14m",
        ),
        SENSE_VOICE_SMALL(
            id = "sensevoice-small",
            displayName = "SenseVoice Small (متعدد اللغات)",
            subtitle = "ModelScope ONNX مضغوط · ~230 MB",
            dirName = "sensevoice-small",
        ),
        ZIPFORMER_BILINGUAL(
            id = "zipformer-bilingual",
            displayName = "Zipformer ثنائي اللغة (صيني/إنجليزي)",
            subtitle = "HuggingFace ONNX · ~190 MB",
            dirName = "zipformer-bilingual",
        ),
    }

    data class VoiceModelStatus(
        val model: ModelId,
        val installed: Boolean,
        val bytesOnDisk: Long,
        val isActive: Boolean,
    )

    data class VoiceDownloadState(
        val active: Boolean = false,
        val modelId: String = "",
        val percent: Int = 0,
        val message: String = "",
    )

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    val recorder = VoiceRecorder(context)

    private val _downloadState = MutableStateFlow(VoiceDownloadState())
    val downloadState: StateFlow<VoiceDownloadState> = _downloadState.asStateFlow()

    private val _statuses = MutableStateFlow<List<VoiceModelStatus>>(emptyList())
    val statuses: StateFlow<List<VoiceModelStatus>> = _statuses.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null

    init {
        if (!isUninstalledByUser() && isVoiceEnabled()) {
            ensureBundledExtractedSync()
        }
        refreshStatuses()
    }

    fun isVoiceEnabled(): Boolean = prefs.getBoolean(KEY_VOICE_ENABLED, true)
    fun isUninstalledByUser(): Boolean = prefs.getBoolean(KEY_VOICE_UNINSTALLED, false)
    fun activeModelId(): String = prefs.getString(KEY_ACTIVE_MODEL, ModelId.BUNDLED_ZIPFORMER_ZH.id)
        ?: ModelId.BUNDLED_ZIPFORMER_ZH.id

    fun setActiveModel(id: String) {
        prefs.edit().putString(KEY_ACTIVE_MODEL, id).apply()
        refreshStatuses()
    }

    fun setVoiceEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VOICE_ENABLED, enabled).apply()
        if (enabled) prefs.edit().putBoolean(KEY_VOICE_UNINSTALLED, false).apply()
    }

    fun refreshStatuses() {
        val active = activeModelId()
        _statuses.value = ModelId.entries.map { m ->
            val dir = modelDir(m)
            val bytes = if (dir.isDirectory) dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
            val ok = when (m) {
                ModelId.BUNDLED_ZIPFORMER_ZH -> verifyBundledFiles(dir)
                ModelId.SENSE_VOICE_SMALL -> File(dir, "model_quant.onnx").isFile && File(dir, "tokens.json").isFile
                ModelId.ZIPFORMER_BILINGUAL -> File(dir, "encoder.int8.onnx").isFile && File(dir, "tokens.txt").isFile
            }
            VoiceModelStatus(m, ok, bytes, active == m.id)
        }
    }

    fun extractBundled(): Result<Unit> {
        prefs.edit()
            .putBoolean(KEY_VOICE_UNINSTALLED, false)
            .putBoolean(KEY_VOICE_ENABLED, true)
            .apply()
        val ok = ensureBundledExtractedSync()
        refreshStatuses()
        return if (ok) Result.success(Unit) else Result.failure(IllegalStateException("الأصول الصوتية المدمجة غير مكتملة أو فشل حفظها"))
    }

    fun uninstallBundled() {
        prefs.edit()
            .putBoolean(KEY_VOICE_UNINSTALLED, true)
            .putBoolean(KEY_VOICE_ENABLED, false)
            .apply()
        runCatching { modelDir(ModelId.BUNDLED_ZIPFORMER_ZH).deleteRecursively() }
        refreshStatuses()
    }

    fun uninstallModel(model: ModelId) {
        if (model == ModelId.BUNDLED_ZIPFORMER_ZH) {
            uninstallBundled()
            return
        }
        runCatching { modelDir(model).deleteRecursively() }
        if (activeModelId() == model.id) setActiveModel(ModelId.BUNDLED_ZIPFORMER_ZH.id)
        refreshStatuses()
    }

    suspend fun downloadOptionalModel(model: ModelId): Result<Unit> = withContext(Dispatchers.IO) {
        if (model == ModelId.BUNDLED_ZIPFORMER_ZH) return@withContext extractBundled()
        val files: List<Pair<String, String>> = when (model) {
            ModelId.SENSE_VOICE_SMALL -> listOf(
                "model_quant.onnx" to "https://www.modelscope.cn/models/iic/SenseVoiceSmall-onnx/resolve/master/model_quant.onnx",
                "tokens.json" to "https://www.modelscope.cn/models/iic/SenseVoiceSmall-onnx/resolve/master/tokens.json",
                "am.mvn" to "https://www.modelscope.cn/models/iic/SenseVoiceSmall-onnx/resolve/master/am.mvn",
                "config.yaml" to "https://www.modelscope.cn/models/iic/SenseVoiceSmall-onnx/resolve/master/config.yaml",
            )
            ModelId.ZIPFORMER_BILINGUAL -> listOf(
                "encoder.int8.onnx" to "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main/encoder-epoch-99-avg-1.int8.onnx",
                "decoder.int8.onnx" to "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main/decoder-epoch-99-avg-1.int8.onnx",
                "joiner.int8.onnx" to "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main/joiner-epoch-99-avg-1.int8.onnx",
                "tokens.txt" to "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main/tokens.txt",
            )
            ModelId.BUNDLED_ZIPFORMER_ZH -> emptyList()
        }
        val dir = modelDir(model).apply { mkdirs() }
        runCatching {
            files.forEachIndexed { idx, (name, url) ->
                _downloadState.value = VoiceDownloadState(
                    active = true,
                    modelId = model.id,
                    percent = (idx * 100) / files.size,
                    message = "جارٍ تنزيل $name…",
                )
                val req = Request.Builder().url(url).get().build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("فشل تنزيل $name: HTTP ${resp.code}")
                    val total = resp.body?.contentLength() ?: -1L
                    val inp = resp.body?.byteStream() ?: error("تدفق ملف فارغ")
                    val dst = File(dir, name)
                    FileOutputStream(dst).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = inp.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = ((idx + done.toDouble() / total) * 100 / files.size).toInt().coerceIn(0, 99)
                                _downloadState.value = VoiceDownloadState(true, model.id, pct, "جارٍ تنزيل $name ($pct%)")
                            }
                        }
                    }
                }
            }
            _downloadState.value = VoiceDownloadState(false, model.id, 100, "اكتمل التثبيت")
            setActiveModel(model.id)
            refreshStatuses()
            Unit
        }.also {
            if (it.isFailure) {
                _downloadState.value = VoiceDownloadState()
                refreshStatuses()
            }
        }
    }

    fun startSpeechRecognition(
        locale: Locale,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        Handler(Looper.getMainLooper()).post {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                onError("لا تتوفر خدمة التعرف الصوتي على هذا النظام، يُرجى استخدام التسجيل الصوتي.")
                return@post
            }
            speechRecognizer?.destroy()
            val sr = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer = sr
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onError(error: Int) {
                    onError("انتهى التعرف الصوتي (code=$error)")
                }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    onFinal(text)
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    if (text.isNotBlank()) onPartial(text)
                }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            sr.startListening(intent)
        }
    }

    fun stopSpeechRecognition() {
        Handler(Looper.getMainLooper()).post {
            runCatching { speechRecognizer?.stopListening() }
        }
    }

    fun releaseSpeechRecognizer() {
        Handler(Looper.getMainLooper()).post {
            runCatching { speechRecognizer?.destroy() }
            speechRecognizer = null
        }
    }

    private fun modelDir(model: ModelId): File =
        File(File(context.filesDir, "voice_models"), model.dirName)

    private fun ensureBundledExtractedSync(): Boolean {
        val dir = modelDir(ModelId.BUNDLED_ZIPFORMER_ZH)
        if (verifyBundledFiles(dir)) return true
        dir.mkdirs()
        return runCatching {
            val assetList = context.assets.list("voice/streaming-zipformer-zh-14m").orEmpty()
            if (assetList.isEmpty()) return false
            for (name in assetList) {
                val dst = File(dir, name)
                context.assets.open("voice/streaming-zipformer-zh-14m/$name").use { inp ->
                    FileOutputStream(dst).use { out -> inp.copyTo(out) }
                }
            }
            verifyBundledFiles(dir)
        }.getOrDefault(false)
    }

    private fun verifyBundledFiles(dir: File): Boolean {
        val dec = File(dir, "decoder-epoch-99-avg-1.int8.onnx")
        val join = File(dir, "joiner-epoch-99-avg-1.int8.onnx")
        val tok = File(dir, "tokens.txt")
        return dec.isFile && dec.length() == 1_888_682L &&
            join.isFile && join.length() == 1_795_562L &&
            tok.isFile && tok.length() == 48_697L
    }

    companion object {
        private const val PREFS_NAME = "heracles_voice_models"
        private const val KEY_VOICE_ENABLED = "heracles.voice.enabled"
        private const val KEY_VOICE_UNINSTALLED = "heracles.voice.uninstalled"
        private const val KEY_ACTIVE_MODEL = "heracles.voice.active_model"
    }
}
