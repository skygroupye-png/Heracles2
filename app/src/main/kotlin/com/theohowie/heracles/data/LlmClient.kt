package com.theohowie.heracles.data

import com.theohowie.heracles.model.ModelCapability
import com.theohowie.heracles.model.ModelProviderConfig
import com.theohowie.heracles.model.ProviderProtocol
import com.theohowie.heracles.model.ReasoningLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class LlmClient {

    data class ToolSchema(
        val name: String,
        val description: String,
        val parametersJson: JSONObject,
    )

    data class ChatTurnInput(
        val role: String, // "user" | "assistant" | "tool"
        val text: String = "",
        val toolCallId: String? = null,
        val toolName: String? = null,
        val toolCalls: List<ToolCallOutput> = emptyList(),
        val imageBase64List: List<Pair<String, String>> = emptyList(), // mime to base64
    )

    data class ToolCallOutput(
        val id: String,
        val name: String,
        val argumentsJson: String,
    )

    data class UsageReport(
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
        val cachedPromptTokens: Int = 0,
        val totalTokens: Int = 0,
    )

    sealed interface StreamDelta {
        data class Text(val delta: String) : StreamDelta
        data class Thinking(val delta: String) : StreamDelta
        data class ToolCalls(val calls: List<ToolCallOutput>) : StreamDelta
        data class Usage(val usage: UsageReport) : StreamDelta
        data class Error(val message: String) : StreamDelta
        data object Done : StreamDelta
    }

    data class ModelsFetchResult(
        val models: List<String>,
        val capabilities: Map<String, ModelCapability>,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun joinApiPath(baseUrl: String, leaf: String): String {
        val cleanBase = baseUrl.trim().trimEnd('/')
        val cleanLeaf = leaf.trimStart('/')
        return if (cleanBase.endsWith("/v1") && cleanLeaf.startsWith("v1/")) {
            cleanBase + "/" + cleanLeaf.removePrefix("v1/")
        } else if (cleanBase.endsWith("/v1")) {
            "$cleanBase/$cleanLeaf"
        } else if (cleanLeaf.startsWith("v1/")) {
            "$cleanBase/$cleanLeaf"
        } else {
            "$cleanBase/v1/$cleanLeaf"
        }
    }

    suspend fun fetchModels(
        provider: ModelProviderConfig,
        apiKey: String,
    ): Result<ModelsFetchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val url = joinApiPath(provider.baseUrl, "models")
            val reqBuilder = Request.Builder().url(url).get()
            if (provider.protocol == ProviderProtocol.ANTHROPIC) {
                if (apiKey.isNotBlank()) reqBuilder.header("x-api-key", apiKey)
                reqBuilder.header("anthropic-version", "2023-06-01")
            } else {
                if (apiKey.isNotBlank()) reqBuilder.header("Authorization", "Bearer $apiKey")
            }
            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    error("HTTP ${resp.code}: ${resp.body?.string()?.take(240).orEmpty()}")
                }
                val body = resp.body?.string().orEmpty()
                val root = JSONObject(body)
                val data = root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
                val ids = mutableListOf<String>()
                val caps = mutableMapOf<String, ModelCapability>()
                for (i in 0 until data.length()) {
                    val obj = data.optJSONObject(i) ?: continue
                    val id = obj.optString("id").ifBlank { obj.optString("name") }
                    if (id.isBlank()) continue
                    ids += id
                    val ctx = obj.optInt("context_length", 0)
                        .takeIf { it > 0 }
                        ?: obj.optInt("context_window", 0)
                            .takeIf { it > 0 }
                        ?: obj.optInt("max_context_tokens", 0)
                    val maxOut = obj.optInt("max_output_tokens", 0).takeIf { it > 0 }
                    if (ctx > 0 || maxOut != null) {
                        caps[id] = ModelCapability(
                            contextWindow = ctx,
                            maxOutputTokens = maxOut,
                        )
                    }
                }
                ModelsFetchResult(ids.distinct().sorted(), caps)
            }
        }
    }

    fun streamCompletion(
        provider: ModelProviderConfig,
        apiKey: String,
        systemPrompt: String,
        history: List<ChatTurnInput>,
        tools: List<ToolSchema>,
        reasoningLevel: ReasoningLevel,
    ): Flow<StreamDelta> = channelFlow {
        if (apiKey.isBlank()) {
            send(StreamDelta.Error("لم يتم إعداد مفتاح API للنموذج"))
            return@channelFlow
        }

        val (url, headers, payload) = when (provider.protocol) {
            ProviderProtocol.OPENAI_CHAT -> buildOpenAiChatRequest(
                provider, apiKey, systemPrompt, history, tools, reasoningLevel,
            )
            ProviderProtocol.ANTHROPIC -> buildAnthropicRequest(
                provider, apiKey, systemPrompt, history, tools, reasoningLevel,
            )
            ProviderProtocol.OPENAI_RESPONSES -> buildResponsesRequest(
                provider, apiKey, systemPrompt, history, tools, reasoningLevel,
            )
        }

        var attempt = 0
        val maxAttempts = 3
        while (true) {
            attempt++
            val result = withContext(Dispatchers.IO) {
                executeSseOnce(
                    url = url,
                    headers = headers,
                    payload = payload,
                    protocol = provider.protocol,
                    emitDelta = { delta -> trySend(delta) },
                )
            }
            if (result.retryable && attempt < maxAttempts) {
                delay(700L * attempt)
                continue
            }
            if (result.errorMessage != null) {
                send(StreamDelta.Error(result.errorMessage))
            } else {
                send(StreamDelta.Done)
            }
            break
        }
    }

    private data class SseRunOutcome(
        val retryable: Boolean = false,
        val errorMessage: String? = null,
    )

    private fun executeSseOnce(
        url: String,
        headers: Map<String, String>,
        payload: JSONObject,
        protocol: ProviderProtocol,
        emitDelta: (StreamDelta) -> Unit,
    ): SseRunOutcome {
        val reqBuilder = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }

        val latch = CountDownLatch(1)
        val errorRef = AtomicReference<SseRunOutcome?>(null)

        val openAiTools = mutableMapOf<Int, Triple<String, String, StringBuilder>>()
        val anthropicTools = mutableMapOf<Int, Triple<String, String, StringBuilder>>()
        val responsesTools = mutableMapOf<String, Triple<String, String, StringBuilder>>()

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") return
                val obj = runCatching { JSONObject(data) }.getOrNull() ?: return
                when (protocol) {
                    ProviderProtocol.OPENAI_CHAT -> handleOpenAiChatEvent(obj, openAiTools, emitDelta)
                    ProviderProtocol.ANTHROPIC -> handleAnthropicEvent(type, obj, anthropicTools, emitDelta)
                    ProviderProtocol.OPENAI_RESPONSES -> handleResponsesEvent(type, obj, responsesTools, emitDelta)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                flushToolBuffers(protocol, openAiTools, anthropicTools, responsesTools, emitDelta)
                latch.countDown()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val code = response?.code ?: 0
                val retryable = code == 408 || code == 429 || code in 500..599 || t is IOException
                val bodyPreview = runCatching { response?.body?.string()?.take(300) }.getOrNull().orEmpty()
                val msg = when {
                    code > 0 -> "فشل طلب API (HTTP $code) $bodyPreview".trim()
                    else -> "خطأ في الاتصال بالشبكة: ${t?.message ?: t?.javaClass?.simpleName ?: "خطأ غير معروف"}"
                }
                errorRef.set(SseRunOutcome(retryable = retryable, errorMessage = msg))
                latch.countDown()
            }
        }

        val factory = EventSources.createFactory(client)
        val source = factory.newEventSource(reqBuilder.build(), listener)
        latch.await(200, TimeUnit.SECONDS)
        source.cancel()
        return errorRef.get() ?: SseRunOutcome()
    }

    private fun handleOpenAiChatEvent(
        obj: JSONObject,
        toolBuf: MutableMap<Int, Triple<String, String, StringBuilder>>,
        emitDelta: (StreamDelta) -> Unit,
    ) {
        obj.optJSONObject("usage")?.let { u ->
            val prompt = u.optInt("prompt_tokens", 0)
            val cached = u.optJSONObject("prompt_tokens_details")?.optInt("cached_tokens", 0) ?: 0
            emitDelta(
                StreamDelta.Usage(
                    UsageReport(
                        promptTokens = prompt,
                        completionTokens = u.optInt("completion_tokens", 0),
                        cachedPromptTokens = cached,
                        totalTokens = u.optInt("total_tokens", 0),
                    ),
                ),
            )
        }
        val choices = obj.optJSONArray("choices") ?: return
        if (choices.length() == 0) return
        val delta = choices.optJSONObject(0)?.optJSONObject("delta") ?: return
        val reasoning = delta.optString("reasoning_content").ifEmpty { delta.optString("reasoning") }
        if (reasoning.isNotEmpty()) emitDelta(StreamDelta.Thinking(reasoning))
        val content = delta.optString("content")
        if (content.isNotEmpty()) emitDelta(StreamDelta.Text(content))

        val calls = delta.optJSONArray("tool_calls") ?: return
        for (i in 0 until calls.length()) {
            val c = calls.optJSONObject(i) ?: continue
            val idx = c.optInt("index", i)
            val existing = toolBuf[idx] ?: Triple("", "", StringBuilder())
            val id = c.optString("id").ifBlank { existing.first }
            val fn = c.optJSONObject("function")
            val name = fn?.optString("name")?.ifBlank { existing.second } ?: existing.second
            val argsDelta = fn?.optString("arguments").orEmpty()
            existing.third.append(argsDelta)
            toolBuf[idx] = Triple(id, name, existing.third)
        }
    }

    private fun handleAnthropicEvent(
        type: String?,
        obj: JSONObject,
        toolBuf: MutableMap<Int, Triple<String, String, StringBuilder>>,
        emitDelta: (StreamDelta) -> Unit,
    ) {
        val evtType = type ?: obj.optString("type")
        when (evtType) {
            "message_start" -> {
                val u = obj.optJSONObject("message")?.optJSONObject("usage") ?: return
                val input = u.optInt("input_tokens", 0)
                val cacheRead = u.optInt("cache_read_input_tokens", 0)
                emitDelta(StreamDelta.Usage(UsageReport(promptTokens = input + cacheRead, cachedPromptTokens = cacheRead)))
            }
            "content_block_start" -> {
                val idx = obj.optInt("index", 0)
                val block = obj.optJSONObject("content_block") ?: return
                if (block.optString("type") == "tool_use") {
                    toolBuf[idx] = Triple(block.optString("id"), block.optString("name"), StringBuilder())
                }
            }
            "content_block_delta" -> {
                val idx = obj.optInt("index", 0)
                val delta = obj.optJSONObject("delta") ?: return
                when (delta.optString("type")) {
                    "text_delta" -> delta.optString("text").takeIf { it.isNotEmpty() }?.let {
                        emitDelta(StreamDelta.Text(it))
                    }
                    "thinking_delta" -> delta.optString("thinking").takeIf { it.isNotEmpty() }?.let {
                        emitDelta(StreamDelta.Thinking(it))
                    }
                    "input_json_delta" -> {
                        val entry = toolBuf[idx]
                        entry?.third?.append(delta.optString("partial_json"))
                    }
                }
            }
            "message_delta" -> {
                val u = obj.optJSONObject("usage") ?: return
                val outTokens = u.optInt("output_tokens", 0)
                if (outTokens > 0) emitDelta(StreamDelta.Usage(UsageReport(completionTokens = outTokens)))
            }
        }
    }

    private fun handleResponsesEvent(
        type: String?,
        obj: JSONObject,
        toolBuf: MutableMap<String, Triple<String, String, StringBuilder>>,
        emitDelta: (StreamDelta) -> Unit,
    ) {
        val evtType = type ?: obj.optString("type")
        when (evtType) {
            "response.output_text.delta" -> obj.optString("delta").takeIf { it.isNotEmpty() }?.let {
                emitDelta(StreamDelta.Text(it))
            }
            "response.reasoning_summary_text.delta" -> obj.optString("delta").takeIf { it.isNotEmpty() }?.let {
                emitDelta(StreamDelta.Thinking(it))
            }
            "response.output_item.added" -> {
                val item = obj.optJSONObject("item") ?: return
                if (item.optString("type") == "function_call") {
                    val callId = item.optString("call_id").ifBlank { item.optString("id") }
                    toolBuf[item.optString("id")] = Triple(callId, item.optString("name"), StringBuilder())
                }
            }
            "response.function_call_arguments.delta" -> {
                val itemId = obj.optString("item_id")
                toolBuf[itemId]?.third?.append(obj.optString("delta"))
            }
            "response.completed" -> {
                val u = obj.optJSONObject("response")?.optJSONObject("usage") ?: return
                val input = u.optInt("input_tokens", 0)
                val cached = u.optJSONObject("input_tokens_details")?.optInt("cached_tokens", 0) ?: 0
                emitDelta(
                    StreamDelta.Usage(
                        UsageReport(
                            promptTokens = input,
                            completionTokens = u.optInt("output_tokens", 0),
                            cachedPromptTokens = cached,
                            totalTokens = u.optInt("total_tokens", 0),
                        ),
                    ),
                )
            }
        }
    }

    private fun flushToolBuffers(
        protocol: ProviderProtocol,
        openAi: Map<Int, Triple<String, String, StringBuilder>>,
        anthropic: Map<Int, Triple<String, String, StringBuilder>>,
        responses: Map<String, Triple<String, String, StringBuilder>>,
        emitDelta: (StreamDelta) -> Unit,
    ) {
        val collected = when (protocol) {
            ProviderProtocol.OPENAI_CHAT -> openAi.toSortedMap().values
            ProviderProtocol.ANTHROPIC -> anthropic.toSortedMap().values
            ProviderProtocol.OPENAI_RESPONSES -> responses.values
        }.filter { it.second.isNotBlank() }
            .map { (id, name, args) ->
                ToolCallOutput(
                    id = id.ifBlank { "tc_${System.nanoTime()}" },
                    name = name,
                    argumentsJson = args.toString().ifBlank { "{}" },
                )
            }
        if (collected.isNotEmpty()) emitDelta(StreamDelta.ToolCalls(collected))
    }

    private fun buildOpenAiChatRequest(
        provider: ModelProviderConfig,
        apiKey: String,
        systemPrompt: String,
        history: List<ChatTurnInput>,
        tools: List<ToolSchema>,
        reasoningLevel: ReasoningLevel,
    ): Triple<String, Map<String, String>, JSONObject> {
        val url = joinApiPath(provider.baseUrl, "chat/completions")
        val headers = mapOf("Authorization" to "Bearer $apiKey")
        val msgs = JSONArray()
        if (systemPrompt.isNotBlank()) {
            msgs.put(JSONObject().put("role", "system").put("content", systemPrompt))
        }
        for (h in history) {
            when (h.role) {
                "user" -> {
                    if (h.imageBase64List.isEmpty()) {
                        msgs.put(JSONObject().put("role", "user").put("content", h.text))
                    } else {
                        val parts = JSONArray().apply {
                            if (h.text.isNotBlank()) put(JSONObject().put("type", "text").put("text", h.text))
                            for ((mime, b64) in h.imageBase64List) {
                                put(
                                    JSONObject().put("type", "image_url").put(
                                        "image_url",
                                        JSONObject().put("url", "data:$mime;base64,$b64"),
                                    ),
                                )
                            }
                        }
                        msgs.put(JSONObject().put("role", "user").put("content", parts))
                    }
                }
                "assistant" -> {
                    val m = JSONObject().put("role", "assistant")
                    m.put("content", h.text.ifEmpty { JSONObject.NULL })
                    if (h.toolCalls.isNotEmpty()) {
                        val tcs = JSONArray()
                        for (tc in h.toolCalls) {
                            tcs.put(
                                JSONObject()
                                    .put("id", tc.id)
                                    .put("type", "function")
                                    .put("function", JSONObject().put("name", tc.name).put("arguments", tc.argumentsJson)),
                            )
                        }
                        m.put("tool_calls", tcs)
                    }
                    msgs.put(m)
                }
                "tool" -> {
                    msgs.put(
                        JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", h.toolCallId ?: "")
                            .put("content", h.text),
                    )
                }
            }
        }
        val body = JSONObject()
            .put("model", provider.model)
            .put("messages", msgs)
            .put("stream", true)
            .put("stream_options", JSONObject().put("include_usage", true))

        reasoningLevel.reasoningEffort?.let { eff ->
            if (provider.activeCapability?.reasoningLevelsEffective == true) {
                body.put("reasoning_effort", eff)
            }
        }
        if (tools.isNotEmpty() && provider.activeCapability?.supportsTools != false) {
            val arr = JSONArray()
            tools.forEach { t ->
                arr.put(
                    JSONObject().put("type", "function").put(
                        "function",
                        JSONObject()
                            .put("name", t.name)
                            .put("description", t.description)
                            .put("parameters", t.parametersJson),
                    ),
                )
            }
            body.put("tools", arr)
        }
        return Triple(url, headers, body)
    }

    private fun buildAnthropicRequest(
        provider: ModelProviderConfig,
        apiKey: String,
        systemPrompt: String,
        history: List<ChatTurnInput>,
        tools: List<ToolSchema>,
        reasoningLevel: ReasoningLevel,
    ): Triple<String, Map<String, String>, JSONObject> {
        val url = joinApiPath(provider.baseUrl, "messages")
        val headers = mapOf(
            "x-api-key" to apiKey,
            "anthropic-version" to "2023-06-01",
        )
        val useCache = provider.baseUrl.contains("api.anthropic.com")
        val msgs = JSONArray()
        for (h in history) {
            when (h.role) {
                "user" -> {
                    val blocks = JSONArray()
                    for ((mime, b64) in h.imageBase64List) {
                        blocks.put(
                            JSONObject().put("type", "image").put(
                                "source",
                                JSONObject().put("type", "base64").put("media_type", mime).put("data", b64),
                            ),
                        )
                    }
                    blocks.put(JSONObject().put("type", "text").put("text", h.text.ifBlank { " " }))
                    msgs.put(JSONObject().put("role", "user").put("content", blocks))
                }
                "assistant" -> {
                    val blocks = JSONArray()
                    if (h.text.isNotBlank()) blocks.put(JSONObject().put("type", "text").put("text", h.text))
                    for (tc in h.toolCalls) {
                        val inputObj = runCatching { JSONObject(tc.argumentsJson) }.getOrElse { JSONObject() }
                        blocks.put(
                            JSONObject()
                                .put("type", "tool_use")
                                .put("id", tc.id)
                                .put("name", tc.name)
                                .put("input", inputObj),
                        )
                    }
                    if (blocks.length() > 0) msgs.put(JSONObject().put("role", "assistant").put("content", blocks))
                }
                "tool" -> {
                    val blocks = JSONArray().put(
                        JSONObject()
                            .put("type", "tool_result")
                            .put("tool_use_id", h.toolCallId ?: "")
                            .put("content", h.text),
                    )
                    msgs.put(JSONObject().put("role", "user").put("content", blocks))
                }
            }
        }
        val body = JSONObject()
            .put("model", provider.model)
            .put("max_tokens", provider.activeCapability?.maxOutputTokens ?: 8192)
            .put("stream", true)
            .put("messages", msgs)

        if (systemPrompt.isNotBlank()) {
            val sysBlock = JSONObject().put("type", "text").put("text", systemPrompt)
            if (useCache) sysBlock.put("cache_control", JSONObject().put("type", "ephemeral"))
            body.put("system", JSONArray().put(sysBlock))
        }
        if (reasoningLevel.wantsThinking && provider.activeCapability?.supportsReasoning == true) {
            val budget = when (reasoningLevel) {
                ReasoningLevel.LOW -> 2048
                ReasoningLevel.HIGH -> 8192
                ReasoningLevel.MAX -> 16384
                else -> 4096
            }
            body.put("thinking", JSONObject().put("type", "enabled").put("budget_tokens", budget))
        }
        if (tools.isNotEmpty()) {
            val arr = JSONArray()
            tools.forEachIndexed { idx, t ->
                val item = JSONObject()
                    .put("name", t.name)
                    .put("description", t.description)
                    .put("input_schema", t.parametersJson)
                if (useCache && idx == tools.lastIndex) {
                    item.put("cache_control", JSONObject().put("type", "ephemeral"))
                }
                arr.put(item)
            }
            body.put("tools", arr)
        }
        return Triple(url, headers, body)
    }

    private fun buildResponsesRequest(
        provider: ModelProviderConfig,
        apiKey: String,
        systemPrompt: String,
        history: List<ChatTurnInput>,
        tools: List<ToolSchema>,
        reasoningLevel: ReasoningLevel,
    ): Triple<String, Map<String, String>, JSONObject> {
        val url = joinApiPath(provider.baseUrl, "responses")
        val headers = mapOf("Authorization" to "Bearer $apiKey")
        val input = JSONArray()
        for (h in history) {
            when (h.role) {
                "user" -> input.put(
                    JSONObject()
                        .put("type", "message")
                        .put("role", "user")
                        .put("content", h.text),
                )
                "assistant" -> {
                    if (h.text.isNotBlank()) {
                        input.put(
                            JSONObject()
                                .put("type", "message")
                                .put("role", "assistant")
                                .put("content", h.text),
                        )
                    }
                    for (tc in h.toolCalls) {
                        input.put(
                            JSONObject()
                                .put("type", "function_call")
                                .put("call_id", tc.id)
                                .put("name", tc.name)
                                .put("arguments", tc.argumentsJson),
                        )
                    }
                }
                "tool" -> {
                    input.put(
                        JSONObject()
                            .put("type", "function_call_output")
                            .put("call_id", h.toolCallId ?: "")
                            .put("output", h.text),
                    )
                }
            }
        }
        val body = JSONObject()
            .put("model", provider.model)
            .put("input", input)
            .put("stream", true)
        if (systemPrompt.isNotBlank()) body.put("instructions", systemPrompt)
        reasoningLevel.reasoningEffort?.let { eff ->
            body.put("reasoning", JSONObject().put("effort", eff).put("summary", "auto"))
        }
        if (tools.isNotEmpty()) {
            val arr = JSONArray()
            tools.forEach { t ->
                arr.put(
                    JSONObject()
                        .put("type", "function")
                        .put("name", t.name)
                        .put("description", t.description)
                        .put("parameters", t.parametersJson),
                )
            }
            body.put("tools", arr)
        }
        return Triple(url, headers, body)
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
