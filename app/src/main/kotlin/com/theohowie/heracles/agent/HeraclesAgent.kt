package com.theohowie.heracles.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Base64
import com.theohowie.heracles.bridge.AgentChatService
import com.theohowie.heracles.data.LlmClient
import com.theohowie.heracles.data.ScopedMemoryService
import com.theohowie.heracles.data.SessionStore
import com.theohowie.heracles.data.SettingsRepository
import com.theohowie.heracles.data.SkillsRepository
import com.theohowie.heracles.model.ApprovalRequest
import com.theohowie.heracles.model.AskQuestion
import com.theohowie.heracles.model.AssistantMsg
import com.theohowie.heracles.model.AttachmentItem
import com.theohowie.heracles.model.AttachmentKind
import com.theohowie.heracles.model.BusySendBehavior
import com.theohowie.heracles.model.ChatMessage
import com.theohowie.heracles.model.ContinuePolicyMode
import com.theohowie.heracles.model.PermissionMode
import com.theohowie.heracles.model.PlanStep
import com.theohowie.heracles.model.SessionUsage
import com.theohowie.heracles.model.StepItem
import com.theohowie.heracles.model.ToolCallItem
import com.theohowie.heracles.model.ToolCallStatus
import com.theohowie.heracles.model.UserMsg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class HeraclesAgent(
    private val context: Context,
    private val llmClient: LlmClient,
    private val tools: ToolRegistry,
    private val settingsRepo: SettingsRepository,
    private val sessionStore: SessionStore,
    private val scopedMemory: ScopedMemoryService,
    private val skillsRepo: SkillsRepository,
) {
    enum class ApprovalDecision { ALLOW_ONCE, ALLOW_ALWAYS, DENY }

    data class QueuedPrompt(
        val text: String,
        val attachments: List<AttachmentItem>,
    )

    data class AgentRuntimeState(
        val busy: Boolean = false,
        val activeAssistant: AssistantMsg? = null,
        val planSteps: List<PlanStep> = emptyList(),
        val pendingApproval: ApprovalRequest? = null,
        val pendingAsk: AskQuestion? = null,
        val queuedCount: Int = 0,
        val stopReason: String? = null,
        val statusNotice: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null
    private var approvalDeferred: CompletableDeferred<ApprovalDecision>? = null
    private var askDeferred: CompletableDeferred<String>? = null
    private val queued = ArrayDeque<QueuedPrompt>()
    private val readFileCache = mutableSetOf<String>()

    private val _state = MutableStateFlow(AgentRuntimeState())
    val state: StateFlow<AgentRuntimeState> = _state.asStateFlow()

    fun submitOrSteer(
        workspacePath: String,
        sessionId: String,
        text: String,
        attachments: List<AttachmentItem>,
        currentMessages: List<ChatMessage>,
        currentUsage: SessionUsage,
        onMessagePersisted: (ChatMessage) -> Unit,
        onUsageUpdated: (SessionUsage) -> Unit,
    ) {
        if (_state.value.busy) {
            val behavior = settingsRepo.settings.value.busySendBehavior
            if (behavior == BusySendBehavior.QUEUE) {
                queued.addLast(QueuedPrompt(text, attachments))
                _state.value = _state.value.copy(queuedCount = queued.size)
                return
            } else {
                stopRun("interrupted")
            }
        }
        startRun(
            workspacePath = workspacePath,
            sessionId = sessionId,
            text = text,
            attachments = attachments,
            baseMessages = currentMessages,
            baseUsage = currentUsage,
            onMessagePersisted = onMessagePersisted,
            onUsageUpdated = onUsageUpdated,
        )
    }

    fun resolveApproval(decision: ApprovalDecision) {
        approvalDeferred?.complete(decision)
        _state.value = _state.value.copy(pendingApproval = null)
    }

    fun resolveAsk(answer: String) {
        askDeferred?.complete(answer)
        _state.value = _state.value.copy(pendingAsk = null)
    }

    fun updatePlanSteps(steps: List<PlanStep>) {
        _state.value = _state.value.copy(planSteps = steps)
    }

    fun clearStopReason() {
        _state.value = _state.value.copy(stopReason = null)
    }

    fun stopRun(reason: String = "stopped") {
        approvalDeferred?.complete(ApprovalDecision.DENY)
        askDeferred?.complete("(أوقف المستخدم التشغيل)")
        activeJob?.cancel()
        activeJob = null
        AgentChatService.stop(context)
        _state.value = _state.value.copy(
            busy = false,
            pendingApproval = null,
            pendingAsk = null,
            stopReason = reason,
        )
    }

    private fun startRun(
        workspacePath: String,
        sessionId: String,
        text: String,
        attachments: List<AttachmentItem>,
        baseMessages: List<ChatMessage>,
        baseUsage: SessionUsage,
        onMessagePersisted: (ChatMessage) -> Unit,
        onUsageUpdated: (SessionUsage) -> Unit,
    ) {
        activeJob?.cancel()
        activeJob = scope.launch {
            val userMsg = UserMsg(
                id = "u_${UUID.randomUUID().toString().take(8)}",
                text = text,
                attachments = attachments,
            )
            sessionStore.appendMessage(workspacePath, sessionId, userMsg)
            onMessagePersisted(userMsg)

            val workingHistory = (baseMessages + userMsg).toMutableList()
            var usage = baseUsage
            val assistantId = "a_${UUID.randomUUID().toString().take(8)}"
            val steps = mutableListOf<StepItem>()
            val accumulatedText = StringBuilder()
            val accumulatedThinking = StringBuilder()

            AgentChatService.start(context)
            _state.value = _state.value.copy(
                busy = true,
                stopReason = null,
                statusNotice = null,
                activeAssistant = AssistantMsg(id = assistantId, text = "", isStreaming = true),
            )

            try {
                val settings = settingsRepo.settings.value
                val provider = settings.activeProvider
                    ?: error("لم يتم إعداد مزوّد نموذج متاح")
                val apiKey = settingsRepo.getProviderApiKey(provider.id)
                val maxTurns = settings.effectiveMaxTurns
                val systemPrompt = buildSystemPrompt(workspacePath, sessionId)
                val toolCtx = ToolRegistry.ToolContext(
                    workspacePath = workspacePath,
                    sessionId = sessionId,
                    readFiles = readFileCache,
                    onPlanUpdated = { list ->
                        _state.value = _state.value.copy(planSteps = list)
                        sessionStore.savePlan(workspacePath, sessionId, list)
                    },
                    onAskUser = { question ->
                        val def = CompletableDeferred<String>()
                        askDeferred = def
                        _state.value = _state.value.copy(pendingAsk = question)
                        val ans = def.await()
                        _state.value = _state.value.copy(pendingAsk = null)
                        ans
                    },
                )

                val turnInputs = buildTurnInputs(workingHistory, provider.supportsVision)
                var turn = 0
                while (turn < maxTurns) {
                    turn++
                    AgentChatService.update(context, "الجولة $turn: جارٍ التفكير والتنفيذ…")
                    val stepThinking = StringBuilder()
                    val stepText = StringBuilder()
                    var pendingCalls: List<LlmClient.ToolCallOutput> = emptyList()
                    var streamError: String? = null

                    llmClient.streamCompletion(
                        provider = provider,
                        apiKey = apiKey,
                        systemPrompt = systemPrompt,
                        history = turnInputs,
                        tools = tools.schemas(),
                        reasoningLevel = settings.reasoningLevel,
                    ).collect { delta ->
                        when (delta) {
                            is LlmClient.StreamDelta.Thinking -> {
                                stepThinking.append(delta.delta)
                                accumulatedThinking.append(delta.delta)
                                emitStreaming(assistantId, accumulatedText.toString(), accumulatedThinking.toString(), steps)
                            }
                            is LlmClient.StreamDelta.Text -> {
                                stepText.append(delta.delta)
                                accumulatedText.append(delta.delta)
                                emitStreaming(assistantId, accumulatedText.toString(), accumulatedThinking.toString(), steps)
                            }
                            is LlmClient.StreamDelta.ToolCalls -> pendingCalls = delta.calls
                            is LlmClient.StreamDelta.Usage -> {
                                val cap = provider.activeCapability?.contextWindow ?: 0
                                val effectiveLimit = if (settings.tokenLimitK > 0) settings.tokenLimitK * 1000 else cap
                                usage = usage.copy(
                                    promptTokens = delta.usage.promptTokens.takeIf { it > 0 } ?: usage.promptTokens,
                                    completionTokens = usage.completionTokens + delta.usage.completionTokens,
                                    cachedPromptTokens = delta.usage.cachedPromptTokens,
                                    totalTokens = delta.usage.totalTokens.takeIf { it > 0 } ?: (usage.promptTokens + usage.completionTokens),
                                    contextWindow = effectiveLimit,
                                    contextFromServer = cap > 0 && settings.tokenLimitK <= 0,
                                    modelName = provider.model,
                                    rounds = usage.rounds + 1,
                                )
                                onUsageUpdated(usage)
                            }
                            is LlmClient.StreamDelta.Error -> streamError = delta.message
                            LlmClient.StreamDelta.Done -> Unit
                        }
                    }

                    if (streamError != null) {
                        val finalErr = AssistantMsg(
                            id = assistantId,
                            text = (accumulatedText.toString() + "\n\n[خطأ] $streamError").trim(),
                            thinking = accumulatedThinking.toString(),
                            steps = steps.toList(),
                            isStreaming = false,
                            isError = true,
                        )
                        sessionStore.appendMessage(workspacePath, sessionId, finalErr)
                        sessionStore.saveUsage(workspacePath, sessionId, usage)
                        onMessagePersisted(finalErr)
                        _state.value = _state.value.copy(busy = false, activeAssistant = null, stopReason = "error")
                        return@launch
                    }

                    if (pendingCalls.isEmpty()) break

                    turnInputs += LlmClient.ChatTurnInput(
                        role = "assistant",
                        text = stepText.toString(),
                        toolCalls = pendingCalls,
                    )

                    val callItems = pendingCalls.map { tc ->
                        ToolCallItem(
                            id = tc.id,
                            name = tc.name,
                            summary = tools.summarizeArgs(tc.name, tc.argumentsJson),
                            argsJson = tc.argumentsJson,
                            status = ToolCallStatus.RUNNING,
                            surface = tools.surfaceFor(tc.name, tc.argumentsJson),
                        )
                    }.toMutableList()

                    val step = StepItem(
                        id = "st_${UUID.randomUUID().toString().take(8)}",
                        index = steps.size + 1,
                        thinking = stepThinking.toString(),
                        toolCalls = callItems.toList(),
                    )
                    steps += step
                    emitStreaming(assistantId, accumulatedText.toString(), accumulatedThinking.toString(), steps)

                    for (i in pendingCalls.indices) {
                        val tc = pendingCalls[i]
                        val allowed = checkApproval(tc)
                        val t0 = System.currentTimeMillis()
                        val outcome = if (!allowed) {
                            ToolRegistry.ToolOutcome(false, "رفض المستخدم تنفيذ الأداة ${tc.name}", surface = tools.surfaceFor(tc.name, tc.argumentsJson))
                        } else {
                            tools.execute(tc.name, tc.argumentsJson, toolCtx)
                        }
                        val dt = System.currentTimeMillis() - t0
                        val updatedItem = callItems[i].copy(
                            status = when {
                                !allowed -> ToolCallStatus.DENIED
                                outcome.ok -> ToolCallStatus.SUCCESS
                                else -> ToolCallStatus.FAILED
                            },
                            output = outcome.output,
                            rawOutput = outcome.output,
                            durationMs = dt,
                            diffStat = outcome.diffStat,
                        )
                        callItems[i] = updatedItem
                        steps[steps.lastIndex] = step.copy(toolCalls = callItems.toList())
                        emitStreaming(assistantId, accumulatedText.toString(), accumulatedThinking.toString(), steps)

                        turnInputs += LlmClient.ChatTurnInput(
                            role = "tool",
                            text = outcome.output.take(16_000),
                            toolCallId = tc.id,
                            toolName = tc.name,
                        )
                    }

                    if (turn >= maxTurns && settings.continuePolicy == ContinuePolicyMode.ASK) {
                        _state.value = _state.value.copy(stopReason = "max_turns")
                        break
                    }
                }

                val finalMsg = AssistantMsg(
                    id = assistantId,
                    text = accumulatedText.toString().ifBlank { "تم تنفيذ المهمة بنجاح." },
                    thinking = accumulatedThinking.toString(),
                    steps = steps.toList(),
                    isStreaming = false,
                )
                sessionStore.appendMessage(workspacePath, sessionId, finalMsg)
                sessionStore.saveUsage(workspacePath, sessionId, usage)
                onMessagePersisted(finalMsg)
                if (settingsRepo.settings.value.notifyOnComplete) {
                    notifyComplete(finalMsg.text.take(80))
                }
                _state.value = _state.value.copy(busy = false, activeAssistant = null)
            } catch (_: CancellationException) {
                if (accumulatedText.isNotBlank() || steps.isNotEmpty()) {
                    val partial = AssistantMsg(
                        id = assistantId,
                        text = accumulatedText.toString(),
                        thinking = accumulatedThinking.toString(),
                        steps = steps.toList(),
                        isStreaming = false,
                    )
                    sessionStore.appendMessage(workspacePath, sessionId, partial)
                    onMessagePersisted(partial)
                }
                _state.value = _state.value.copy(busy = false, activeAssistant = null)
            } catch (e: Throwable) {
                val errMsg = AssistantMsg(
                    id = assistantId,
                    text = "توقف التشغيل: ${e.message ?: e.javaClass.simpleName}",
                    thinking = accumulatedThinking.toString(),
                    steps = steps.toList(),
                    isStreaming = false,
                    isError = true,
                )
                sessionStore.appendMessage(workspacePath, sessionId, errMsg)
                onMessagePersisted(errMsg)
                _state.value = _state.value.copy(busy = false, activeAssistant = null, stopReason = "error")
            } finally {
                AgentChatService.stop(context)
                if (queued.isNotEmpty() && _state.value.stopReason == null) {
                    val next = queued.removeFirst()
                    _state.value = _state.value.copy(queuedCount = queued.size)
                    startRun(
                        workspacePath = workspacePath,
                        sessionId = sessionId,
                        text = next.text,
                        attachments = next.attachments,
                        baseMessages = workingHistory,
                        baseUsage = usage,
                        onMessagePersisted = onMessagePersisted,
                        onUsageUpdated = onUsageUpdated,
                    )
                }
            }
        }
    }

    private suspend fun checkApproval(tc: LlmClient.ToolCallOutput): Boolean {
        if (tools.isReadOnly(tc.name)) return true
        val settings = settingsRepo.settings.value
        if (tc.name in settings.alwaysAllowedTools) return true
        val highRisk = tools.isHighRisk(tc.name, tc.argumentsJson)
        val autoOk = when (settings.permissionMode) {
            PermissionMode.FULL_ACCESS -> true
            PermissionMode.WORKSPACE_AUTO -> !highRisk
            PermissionMode.MANUAL -> false
        }
        if (autoOk) return true

        val def = CompletableDeferred<ApprovalDecision>()
        approvalDeferred = def
        _state.value = _state.value.copy(
            pendingApproval = ApprovalRequest(
                id = tc.id,
                toolName = tc.name,
                summary = tools.summarizeArgs(tc.name, tc.argumentsJson),
                details = tc.argumentsJson.take(1200),
                isHighRisk = highRisk,
            ),
        )
        val decision = def.await()
        _state.value = _state.value.copy(pendingApproval = null)
        if (decision == ApprovalDecision.ALLOW_ALWAYS) {
            settingsRepo.updateSettings { it.copy(alwaysAllowedTools = it.alwaysAllowedTools + tc.name) }
        }
        return decision != ApprovalDecision.DENY
    }

    private fun emitStreaming(
        assistantId: String,
        text: String,
        thinking: String,
        steps: List<StepItem>,
    ) {
        _state.value = _state.value.copy(
            activeAssistant = AssistantMsg(
                id = assistantId,
                text = text,
                thinking = thinking,
                steps = steps.toList(),
                isStreaming = true,
            ),
        )
    }

    private suspend fun buildSystemPrompt(workspacePath: String, sessionId: String): String {
        val memBlock = scopedMemory.buildPromptBlock(workspacePath, sessionId)
        val skillBlock = skillsRepo.buildPromptSection(workspacePath)
        return buildString {
            appendLine("You are Heracles, a pragmatic developer and device agent running natively on Android.")
            appendLine("Current workspace path: $workspacePath")
            appendLine("Rules:")
            appendLine("- Always call read_file before editing or overwriting an existing file.")
            appendLine("- Never fabricate command output or file contents; inspect real state via tools.")
            appendLine("- Respond concisely in the user's language (default Arabic when prompted in Arabic).")
            if (skillBlock.isNotBlank()) appendLine("\n$skillBlock")
            if (memBlock.isNotBlank()) appendLine("\n$memBlock")
        }
    }

    private fun buildTurnInputs(history: List<ChatMessage>, supportsVision: Boolean): MutableList<LlmClient.ChatTurnInput> {
        val list = mutableListOf<LlmClient.ChatTurnInput>()
        for (m in history.takeLast(28)) {
            when (m) {
                is UserMsg -> {
                    val images = if (supportsVision) {
                        m.attachments.filter { it.kind == AttachmentKind.IMAGE }.mapNotNull { att ->
                            runCatching {
                                val bytes = File(att.path).readBytes()
                                val mime = att.mimeType.ifBlank { "image/jpeg" }
                                mime to Base64.encodeToString(bytes, Base64.NO_WRAP)
                            }.getOrNull()
                        }
                    } else {
                        emptyList()
                    }
                    val attachNote = if (m.attachments.isEmpty()) "" else {
                        "\n[Attachments: " + m.attachments.joinToString { "${it.name} (${it.path})" } + "]"
                    }
                    list += LlmClient.ChatTurnInput(
                        role = "user",
                        text = m.text + attachNote,
                        imageBase64List = images,
                    )
                }
                is AssistantMsg -> {
                    if (m.text.isNotBlank()) {
                        list += LlmClient.ChatTurnInput(role = "assistant", text = m.text)
                    }
                }
            }
        }
        return list
    }

    private fun notifyComplete(preview: String) {
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val chId = "heracles_task_done"
            if (nm.getNotificationChannel(chId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(chId, "إشعارات اكتمال المهام", NotificationManager.IMPORTANCE_DEFAULT),
                )
            }
            val n = android.app.Notification.Builder(context, chId)
                .setContentTitle("أكمل Heracles المهمة")
                .setContentText(preview.ifBlank { "اضغط لعرض النتيجة" })
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setAutoCancel(true)
                .build()
            nm.notify(2099, n)
        }
    }
}
