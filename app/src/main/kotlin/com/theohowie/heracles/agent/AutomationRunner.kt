package com.theohowie.heracles.agent

import android.content.Context
import com.theohowie.heracles.bridge.AutomationScheduler
import com.theohowie.heracles.data.LlmClient
import com.theohowie.heracles.data.SettingsRepository
import com.theohowie.heracles.model.AutomationRunRecord
import com.theohowie.heracles.model.AutomationTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AutomationRunner(
    private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val llmClient: LlmClient,
    private val tools: ToolRegistry,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runningTaskIds = ConcurrentHashMap.newKeySet<String>()

    fun drainPending() {
        val pendingIds = AutomationScheduler.consumePending(context)
        if (pendingIds.isEmpty()) return
        val all = settingsRepo.tasks.value
        pendingIds.distinct().forEach { id ->
            val task = all.firstOrNull { it.id == id && it.enabled } ?: return@forEach
            triggerTask(task)
        }
    }

    fun triggerTask(task: AutomationTask) {
        if (!runningTaskIds.add(task.id)) return
        scope.launch {
            val started = System.currentTimeMillis()
            var ok = false
            var summary = ""
            try {
                val settings = settingsRepo.settings.value
                val provider = settings.activeProvider
                    ?: error("لم يتم إعداد مزوّد نموذج")
                val apiKey = settingsRepo.getProviderApiKey(provider.id)
                if (apiKey.isBlank()) error("مفتاح API للنموذج مفقود")

                val sb = StringBuilder()
                val ctx = ToolRegistry.ToolContext(
                    workspacePath = task.workspacePath,
                    sessionId = "auto_${task.id}",
                    readFiles = mutableSetOf(),
                    onPlanUpdated = {},
                    onAskUser = { "(مهمة أتمتة في الخلفية، يتعذر التأكيد التفاعلي)" },
                )
                val history = mutableListOf(
                    LlmClient.ChatTurnInput(role = "user", text = task.prompt),
                )

                var round = 0
                while (round < 8) {
                    round++
                    var calls: List<LlmClient.ToolCallOutput> = emptyList()
                    var err: String? = null
                    val roundText = StringBuilder()
                    llmClient.streamCompletion(
                        provider = provider,
                        apiKey = apiKey,
                        systemPrompt = "You are executing scheduled automation task '${task.name}' in workspace ${task.workspacePath}.",
                        history = history,
                        tools = tools.schemas().filter { tools.isReadOnly(it.name) },
                        reasoningLevel = settings.reasoningLevel,
                    ).collect { d ->
                        when (d) {
                            is LlmClient.StreamDelta.Text -> {
                                roundText.append(d.delta)
                                sb.append(d.delta)
                            }
                            is LlmClient.StreamDelta.ToolCalls -> calls = d.calls
                            is LlmClient.StreamDelta.Error -> err = d.message
                            else -> Unit
                        }
                    }
                    if (err != null) error(err!!)
                    if (calls.isEmpty()) break
                    history += LlmClient.ChatTurnInput(role = "assistant", text = roundText.toString(), toolCalls = calls)
                    for (tc in calls) {
                        val out = if (tools.isReadOnly(tc.name)) {
                            tools.execute(tc.name, tc.argumentsJson, ctx).output
                        } else {
                            "تسمح المهام المجدولة في الخلفية بأدوات القراءة فقط؛ تم تخطي ${tc.name}"
                        }
                        history += LlmClient.ChatTurnInput(role = "tool", text = out.take(8000), toolCallId = tc.id, toolName = tc.name)
                    }
                }
                ok = true
                summary = sb.toString().trim().take(240).ifBlank { "اكتمل التنفيذ" }
            } catch (e: Throwable) {
                ok = false
                summary = e.message ?: e.javaClass.simpleName
            } finally {
                val finished = System.currentTimeMillis()
                settingsRepo.recordAutomationRun(
                    AutomationRunRecord(
                        id = "run_" + UUID.randomUUID().toString().take(8),
                        taskId = task.id,
                        taskName = task.name,
                        startedAtMs = started,
                        finishedAtMs = finished,
                        ok = ok,
                        summary = summary,
                    ),
                )
                val updated = settingsRepo.tasks.value.map {
                    if (it.id == task.id) {
                        it.copy(
                            lastRunAtMs = finished,
                            lastStatus = if (ok) "نجاح" else "فشل",
                            lastRunSummary = summary,
                        )
                    } else it
                }
                settingsRepo.saveAutomationTasks(updated)
                runningTaskIds.remove(task.id)
            }
        }
    }
}
