package com.theohowie.heracles.model

import kotlinx.serialization.Serializable

@Serializable
enum class AppThemeStyle {
    BW,
    MIST,
    AURORA,
    VITALITY,
}

@Serializable
enum class AppAppearance {
    LIGHT,
    DARK,
    SYSTEM,
}

@Serializable
enum class AppLanguage {
    AR,
    EN,
    ZH,
}

@Serializable
enum class PermissionMode {
    MANUAL,
    WORKSPACE_AUTO,
    FULL_ACCESS;

    val requiresElevation: Boolean get() = this == FULL_ACCESS
}

@Serializable
enum class StepDetail {
    MINIMAL,
    STANDARD,
    DETAILED,
    FULLY_EXPANDED;

    val showArgs: Boolean get() = this != MINIMAL
    val fullResult: Boolean get() = this == DETAILED || this == FULLY_EXPANDED
    val showRawOutput: Boolean get() = this == FULLY_EXPANDED
    val statusOnly: Boolean get() = !showArgs

    companion object {
        const val SUMMARY_CHARS = 80
    }
}

@Serializable
enum class BusySendBehavior {
    QUEUE,
    INTERRUPT,
}

@Serializable
enum class ReasoningLevel {
    AUTO,
    OFF,
    LOW,
    HIGH,
    MAX;

    val wantsThinking: Boolean get() = this == LOW || this == HIGH || this == MAX
    val reasoningEffort: String?
        get() = when (this) {
            LOW -> "low"
            HIGH -> "high"
            MAX -> "max"
            else -> null
        }
}

@Serializable
enum class ProviderProtocol {
    OPENAI_CHAT,
    ANTHROPIC,
    OPENAI_RESPONSES,
}

@Serializable
enum class ContinuePolicyMode {
    OFF,
    ASK,
    AUTO,
}

@Serializable
data class ModelCapability(
    val contextWindow: Int = 0,
    val maxOutputTokens: Int? = null,
    val supportsTools: Boolean? = null,
    val supportsReasoning: Boolean? = null,
    val reasoningLevelsEffective: Boolean = false,
)

@Serializable
data class ModelProviderConfig(
    val id: String,
    val name: String,
    val protocol: ProviderProtocol = ProviderProtocol.OPENAI_CHAT,
    val baseUrl: String,
    val model: String,
    val availableModels: List<String> = emptyList(),
    val modelCapabilities: Map<String, ModelCapability> = emptyMap(),
    val supportsVision: Boolean = false,
    val isBuiltIn: Boolean = false,
) {
    val activeCapability: ModelCapability? get() = modelCapabilities[model]
}

@Serializable
data class AppSettings(
    val themeStyle: AppThemeStyle = AppThemeStyle.BW,
    val appearance: AppAppearance = AppAppearance.SYSTEM,
    val language: AppLanguage = AppLanguage.AR,
    val permissionMode: PermissionMode = PermissionMode.MANUAL,
    val stepDetail: StepDetail = StepDetail.STANDARD,
    val busySendBehavior: BusySendBehavior = BusySendBehavior.QUEUE,
    val reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val maxTurns: Int = 0, // 0 = Auto (40)
    val continuePolicy: ContinuePolicyMode = ContinuePolicyMode.ASK,
    val tokenLimitK: Int = 0, // 0 = Follow server / unlimited
    val providers: List<ModelProviderConfig> = defaultProviders(),
    val activeProviderId: String = "deepseek",
    val alwaysAllowedTools: Set<String> = emptySet(),
    val notifyOnComplete: Boolean = true,
) {
    val activeProvider: ModelProviderConfig?
        get() = providers.firstOrNull { it.id == activeProviderId } ?: providers.firstOrNull()

    val effectiveMaxTurns: Int get() = if (maxTurns <= 0) 40 else maxTurns

    companion object {
        val maxTurnOptions = listOf(0, 8, 16, 40, 80)

        fun defaultProviders(): List<ModelProviderConfig> = listOf(
            ModelProviderConfig(
                id = "deepseek",
                name = "DeepSeek",
                protocol = ProviderProtocol.OPENAI_CHAT,
                baseUrl = "https://api.deepseek.com/v1",
                model = "deepseek-chat",
                availableModels = listOf("deepseek-chat", "deepseek-reasoner"),
                modelCapabilities = mapOf(
                    "deepseek-chat" to ModelCapability(
                        contextWindow = 64_000,
                        supportsTools = true,
                        supportsReasoning = false,
                    ),
                    "deepseek-reasoner" to ModelCapability(
                        contextWindow = 64_000,
                        supportsTools = true,
                        supportsReasoning = true,
                        reasoningLevelsEffective = false,
                    ),
                ),
                supportsVision = false,
                isBuiltIn = true,
            ),
            ModelProviderConfig(
                id = "dashscope",
                name = "Alibaba Cloud DashScope",
                protocol = ProviderProtocol.OPENAI_CHAT,
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                model = "qwen3-max",
                availableModels = listOf("qwen3-max", "qwen3-coder-plus", "qwen-vl-max"),
                modelCapabilities = mapOf(
                    "qwen3-max" to ModelCapability(contextWindow = 128_000, supportsTools = true, supportsReasoning = true),
                    "qwen3-coder-plus" to ModelCapability(contextWindow = 128_000, supportsTools = true),
                    "qwen-vl-max" to ModelCapability(contextWindow = 128_000, supportsTools = true),
                ),
                supportsVision = true,
                isBuiltIn = true,
            ),
            ModelProviderConfig(
                id = "anthropic",
                name = "Anthropic Claude",
                protocol = ProviderProtocol.ANTHROPIC,
                baseUrl = "https://api.anthropic.com",
                model = "claude-sonnet-4-20250514",
                availableModels = listOf("claude-sonnet-4-20250514", "claude-3-7-sonnet-20250219"),
                modelCapabilities = mapOf(
                    "claude-sonnet-4-20250514" to ModelCapability(
                        contextWindow = 200_000,
                        supportsTools = true,
                        supportsReasoning = true,
                        reasoningLevelsEffective = true,
                    ),
                ),
                supportsVision = true,
                isBuiltIn = true,
            ),
        )
    }
}

@Serializable
data class AutomationTask(
    val id: String,
    val name: String,
    val prompt: String,
    val workspacePath: String,
    val schedule: String = "interval", // "interval"
    val intervalMinutes: Int = 60,
    val enabled: Boolean = true,
    val lastRunAtMs: Long = 0L,
    val lastStatus: String = "",
    val lastRunSummary: String = "",
)

@Serializable
data class AutomationRunRecord(
    val id: String,
    val taskId: String,
    val taskName: String,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val ok: Boolean,
    val summary: String,
)

@Serializable
data class SearchConfig(
    val endpointUrl: String = "",
    val legacyKeyCleaned: Boolean = false,
)
