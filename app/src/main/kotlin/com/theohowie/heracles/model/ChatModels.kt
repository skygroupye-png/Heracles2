package com.theohowie.heracles.model

import kotlinx.serialization.Serializable

@Serializable
enum class AttachmentKind {
    IMAGE,
    FILE,
}

@Serializable
data class AttachmentItem(
    val id: String,
    val name: String,
    val path: String,
    val kind: AttachmentKind,
    val mimeType: String = "",
    val sizeBytes: Long = 0L,
)

@Serializable
enum class ToolCallStatus {
    RUNNING,
    SUCCESS,
    FAILED,
    DENIED,
}

@Serializable
data class DiffStat(
    val added: Int = 0,
    val removed: Int = 0,
) {
    val hasChanges: Boolean get() = added > 0 || removed > 0
}

@Serializable
data class ToolCallItem(
    val id: String,
    val name: String,
    val summary: String,
    val argsJson: String,
    val status: ToolCallStatus = ToolCallStatus.RUNNING,
    val output: String = "",
    val rawOutput: String = "",
    val durationMs: Long = 0L,
    val diffStat: DiffStat? = null,
    val surface: String = "app", // "app" | "sandbox" | "device" | "git" | "web"
)

@Serializable
data class StepItem(
    val id: String,
    val index: Int,
    val thinking: String = "",
    val toolCalls: List<ToolCallItem> = emptyList(),
    val timestampMs: Long = System.currentTimeMillis(),
)

@Serializable
sealed interface ChatMessage {
    val id: String
    val timestampMs: Long
}

@Serializable
data class UserMsg(
    override val id: String,
    val text: String,
    val attachments: List<AttachmentItem> = emptyList(),
    override val timestampMs: Long = System.currentTimeMillis(),
) : ChatMessage

@Serializable
data class AssistantMsg(
    override val id: String,
    val text: String,
    val thinking: String = "",
    val steps: List<StepItem> = emptyList(),
    val isStreaming: Boolean = false,
    val isError: Boolean = false,
    override val timestampMs: Long = System.currentTimeMillis(),
) : ChatMessage

@Serializable
data class PlanStep(
    val id: String,
    val title: String,
    val status: String = "pending", // pending | in_progress | completed
)

@Serializable
data class AskOption(
    val label: String,
    val description: String = "",
)

@Serializable
data class AskQuestion(
    val id: String,
    val prompt: String,
    val options: List<AskOption> = emptyList(),
    val allowFreeText: Boolean = true,
)

@Serializable
data class ApprovalRequest(
    val id: String,
    val toolName: String,
    val summary: String,
    val details: String,
    val path: String = "",
    val isHighRisk: Boolean = false,
)

@Serializable
data class SessionUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cachedPromptTokens: Int = 0,
    val totalTokens: Int = 0,
    val contextWindow: Int = 0,
    val contextFromServer: Boolean = false,
    val modelName: String = "",
    val rounds: Int = 0,
) {
    val contextFraction: Float
        get() = if (contextWindow <= 0) 0f else (promptTokens.toFloat() / contextWindow.toFloat()).coerceIn(0f, 1f)

    val cacheHitPercent: Int
        get() = if (promptTokens <= 0) 0 else ((cachedPromptTokens * 100.0) / promptTokens).toInt().coerceIn(0, 100)
}

@Serializable
data class SessionMeta(
    val id: String,
    val title: String,
    val workspacePath: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val messageCount: Int = 0,
    val archived: Boolean = false,
    val lastRunStopReason: String? = null,
)
