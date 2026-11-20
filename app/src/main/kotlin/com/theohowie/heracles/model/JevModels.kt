package com.theohowie.heracles.model

import kotlinx.serialization.Serializable

const val JEV_DEFAULT_BASE_URL = "https://tokenrhythm.studio/v1/decision"
const val JEV_DEFAULT_MODEL = "NeoHorse-Jev-4B"

@Serializable
enum class JevSource(val shortLabel: String) {
    CLOUD("سحابي"),
    LOCAL_08("محلي 0.8B"),
    LOCAL_2B("محلي 2B"),
}

@Serializable
enum class JevReasonSource {
    NONE,
    MODEL_THOUGHT,
    LLM,
}

@Serializable
data class JevConfig(
    val source: JevSource = JevSource.CLOUD,
    val cloudBaseUrl: String = JEV_DEFAULT_BASE_URL,
    val cloudModel: String = JEV_DEFAULT_MODEL,
)

@Serializable
data class JevDecision(
    val id: String,
    val question: String,
    val yesProb: Double,
    val engineLabel: String,
    val elapsedMs: Int,
    val createdAtMs: Long,
    val reason: String? = null,
    val reasonSource: JevReasonSource = JevReasonSource.NONE,
) {
    val recommended: Boolean get() = yesProb >= 0.5
    val confidence: Double get() = if (recommended) yesProb else 1.0 - yesProb
}

data class JevDiagnostics(
    val resolvedEndpoint: String,
    val httpStatus: Int? = null,
    val serverCode: String? = null,
    val serverMessage: String? = null,
    val traceId: String? = null,
    val rawBody: String = "",
    val elapsedMs: Int = 0,
    val modelCorrected: Boolean = false,
    val hint: String? = null,
    val attempts: Int = 1,
    val retriedAfter422: Boolean = false,
    val modelsCatalogReadable: Boolean = false,
    val transportError: String? = null,
) {
    val ok: Boolean get() = httpStatus != null && httpStatus in 200..299
}

data class JevDeviceProfile(
    val model: String,
    val totalRamMb: Int,
    val freeStorageMb: Int,
    val appFilesDir: String,
) {
    val totalRamGb: Double get() = totalRamMb / 1024.0
    val freeStorageGb: Double get() = freeStorageMb / 1024.0
}

enum class JevTier { LOCAL_2B, LOCAL_08, CLOUD_ONLY }

data class JevRecommendation(
    val tier: JevTier,
    val headline: String,
    val detail: String,
)

fun recommendJev(p: JevDeviceProfile): JevRecommendation {
    val ramGb = p.totalRamGb
    val freeGb = p.freeStorageGb
    if (p.totalRamMb >= 6144 && p.freeStorageMb >= 3277) {
        return JevRecommendation(
            tier = JevTier.LOCAL_2B,
            headline = "الخيار الأمثل: محلي 2B",
            detail = "ذاكرة الوصول العشوائي %.0f غيغابايت والتخزين المتاح %.0f غيغابايت يفيان بمتطلبات أوزان 2B Q8 (~2.0 غيغابايت، يحتاج ~3 غيغابايت للتشغيل).".format(ramGb, freeGb),
        )
    }
    if (p.totalRamMb >= 3584 && p.freeStorageMb >= 1229) {
        return JevRecommendation(
            tier = JevTier.LOCAL_08,
            headline = "الخيار الأمثل: محلي 0.8B",
            detail = "ذاكرة %.1f غيغابايت كافية لنموذج 0.8B Q4 (الأوزان ~0.5 غيغابايت)، التخزين المتاح %.1f غيغابايت.".format(ramGb, freeGb),
        )
    }
    return JevRecommendation(
        tier = JevTier.CLOUD_ONLY,
        headline = "يُوصى باستخدام JEV السحابي على هذا الجهاز",
        detail = "الذاكرة (%.1f غيغابايت) أو التخزين المتاح (%.1f غيغابايت) أقل من الحد الأدنى لـ 0.8B (~3.5 غيغابايت RAM / 1.2 غيغابايت تخزين).".format(ramGb, freeGb),
    )
}

data class JevModelSpec(
    val source: JevSource,
    val displayName: String,
    val quantLabel: String,
    val sizeLabel: String,
    val approxBytes: Long,
    val ggufRepo: String,
    val ggufFile: String,
    val configRepo: String,
    val dirName: String,
)

val JEV_MODEL_SPECS = listOf(
    JevModelSpec(
        source = JevSource.LOCAL_08,
        displayName = "StartLux-Decision 0.8B",
        quantLabel = "تكميم Q4_K_M",
        sizeLabel = "حوالي 0.5 GB",
        approxBytes = 530_000_000L,
        ggufRepo = "startlux-models/StartLux-Decision-v1.1-0.8B-Instant-GGUF",
        ggufFile = "StartLux-Decision-v1.1-0.8B-Instant-Q4_K_M.gguf",
        configRepo = "startlux-models/StartLux-Decision-v1.1-0.8B-Instant",
        dirName = "0.8b",
    ),
    JevModelSpec(
        source = JevSource.LOCAL_2B,
        displayName = "StartLux-Decision 2B",
        quantLabel = "تكميم Q8_0",
        sizeLabel = "2.0 GB",
        approxBytes = 2_010_000_000L,
        ggufRepo = "startlux-models/StartLux-Decision-v1.1-2B-Instant-GGUF",
        ggufFile = "StartLux-Decision-v1.1-2B-Instant-Q8_0.gguf",
        configRepo = "startlux-models/StartLux-Decision-v1.1-2B-Instant",
        dirName = "2b",
    ),
)
