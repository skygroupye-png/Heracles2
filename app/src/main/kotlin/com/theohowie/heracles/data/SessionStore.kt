package com.theohowie.heracles.data

import com.theohowie.heracles.bridge.DeviceFs
import com.theohowie.heracles.bridge.WorkspaceRegistry
import com.theohowie.heracles.model.AssistantMsg
import com.theohowie.heracles.model.ChatMessage
import com.theohowie.heracles.model.PlanStep
import com.theohowie.heracles.model.SessionMeta
import com.theohowie.heracles.model.SessionUsage
import com.theohowie.heracles.model.UserMsg
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

class SessionStore(private val fs: DeviceFs) {

    @Serializable
    private data class JsonlRecord(
        val type: String, // "msg" | "usage" | "plan" | "checkpoint"
        val message: ChatMessage? = null,
        val usage: SessionUsage? = null,
        val plan: List<PlanStep>? = null,
        val stopReason: String? = null,
    )

    data class LoadedSession(
        val meta: SessionMeta,
        val messages: List<ChatMessage>,
        val usage: SessionUsage,
        val plan: List<PlanStep>,
        val hasEarlier: Boolean,
        val totalLines: Int,
        val loadedLineCount: Int,
    )

    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun dotDir(workspacePath: String): String =
        workspacePath.trimEnd('/') + "/" + WorkspaceRegistry.DOT_DIR

    private fun indexPath(workspacePath: String): String =
        dotDir(workspacePath) + "/sessions.json"

    private fun sessionFilePath(workspacePath: String, sessionId: String): String =
        dotDir(workspacePath) + "/sessions/$sessionId.jsonl"

    suspend fun ensureDirs(workspacePath: String) {
        fs.mkdir(dotDir(workspacePath))
        fs.mkdir(dotDir(workspacePath) + "/sessions")
    }

    suspend fun listSessions(
        workspacePath: String,
        includeArchived: Boolean = false,
    ): List<SessionMeta> = mutex.withLock {
        val raw = runCatching { fs.readWholeText(indexPath(workspacePath)) }.getOrNull()
            ?: return@withLock emptyList()
        val parsed = runCatching {
            json.decodeFromString(ListSerializer(SessionMeta.serializer()), raw.trim())
        }.getOrDefault(emptyList())
        parsed
            .filter { includeArchived || !it.archived }
            .sortedByDescending { it.updatedAtMs }
    }

    suspend fun createSession(
        workspacePath: String,
        title: String = "جلسة جديدة",
    ): SessionMeta {
        ensureDirs(workspacePath)
        val now = System.currentTimeMillis()
        val meta = SessionMeta(
            id = "s_" + UUID.randomUUID().toString().replace("-", "").take(12),
            title = title,
            workspacePath = workspacePath,
            createdAtMs = now,
            updatedAtMs = now,
            messageCount = 0,
            archived = false,
        )
        upsertMeta(workspacePath, meta)
        fs.writeText(sessionFilePath(workspacePath, meta.id), "")
        return meta
    }

    suspend fun loadTail(
        workspacePath: String,
        meta: SessionMeta,
        tailLines: Int = 200,
    ): LoadedSession {
        val path = sessionFilePath(workspacePath, meta.id)
        val tail = fs.readTailLines(path, maxLines = tailLines)
        val rawText = tail["text"] as? String ?: ""
        val truncated = tail["truncated"] == true
        val records = parseRecords(rawText)
        return assembleLoaded(meta, records, hasEarlier = truncated, loadedLineCount = rawText.lines().size)
    }

    suspend fun loadFull(
        workspacePath: String,
        meta: SessionMeta,
    ): LoadedSession {
        val path = sessionFilePath(workspacePath, meta.id)
        val rawText = runCatching { fs.readWholeText(path) }.getOrDefault("")
        val records = parseRecords(rawText)
        val lines = if (rawText.isBlank()) 0 else rawText.lines().size
        return assembleLoaded(meta, records, hasEarlier = false, loadedLineCount = lines)
    }

    suspend fun appendMessage(
        workspacePath: String,
        sessionId: String,
        message: ChatMessage,
    ) {
        ensureDirs(workspacePath)
        val rec = json.encodeToString(JsonlRecord.serializer(), JsonlRecord(type = "msg", message = message))
        appendLineSafe(sessionFilePath(workspacePath, sessionId), rec)
        val metas = listSessions(workspacePath, includeArchived = true)
        val existing = metas.firstOrNull { it.id == sessionId }
        if (existing != null) {
            val autoTitle = if (existing.title == "جلسة جديدة" || existing.title == "محادثة جديدة" || existing.title == "新对话" || existing.title == "New Session") {
                when (message) {
                    is UserMsg -> message.text.trim().lineSequence().firstOrNull()?.take(36).orEmpty().ifBlank { existing.title }
                    is AssistantMsg -> existing.title
                }
            } else {
                existing.title
            }
            upsertMeta(
                workspacePath,
                existing.copy(
                    title = autoTitle,
                    updatedAtMs = System.currentTimeMillis(),
                    messageCount = existing.messageCount + 1,
                ),
            )
        }
    }

    suspend fun saveUsage(
        workspacePath: String,
        sessionId: String,
        usage: SessionUsage,
    ) {
        ensureDirs(workspacePath)
        val rec = json.encodeToString(JsonlRecord.serializer(), JsonlRecord(type = "usage", usage = usage))
        appendLineSafe(sessionFilePath(workspacePath, sessionId), rec)
    }

    suspend fun savePlan(
        workspacePath: String,
        sessionId: String,
        plan: List<PlanStep>,
    ) {
        ensureDirs(workspacePath)
        val rec = json.encodeToString(JsonlRecord.serializer(), JsonlRecord(type = "plan", plan = plan))
        appendLineSafe(sessionFilePath(workspacePath, sessionId), rec)
    }

    suspend fun saveStopReason(
        workspacePath: String,
        sessionId: String,
        reason: String?,
    ) {
        val metas = listSessions(workspacePath, includeArchived = true)
        val existing = metas.firstOrNull { it.id == sessionId } ?: return
        upsertMeta(
            workspacePath,
            existing.copy(
                lastRunStopReason = reason,
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun renameSession(workspacePath: String, sessionId: String, newTitle: String) {
        val metas = listSessions(workspacePath, includeArchived = true)
        val target = metas.firstOrNull { it.id == sessionId } ?: return
        upsertMeta(workspacePath, target.copy(title = newTitle.trim().ifEmpty { target.title }))
    }

    suspend fun setArchived(workspacePath: String, sessionId: String, archived: Boolean) {
        val metas = listSessions(workspacePath, includeArchived = true)
        val target = metas.firstOrNull { it.id == sessionId } ?: return
        upsertMeta(workspacePath, target.copy(archived = archived, updatedAtMs = System.currentTimeMillis()))
    }

    suspend fun deleteSession(workspacePath: String, sessionId: String) {
        mutex.withLock {
            val raw = runCatching { fs.readWholeText(indexPath(workspacePath)) }.getOrNull() ?: "[]"
            val parsed = runCatching {
                json.decodeFromString(ListSerializer(SessionMeta.serializer()), raw.trim())
            }.getOrDefault(emptyList())
            val remaining = parsed.filter { it.id != sessionId }
            ensureDirs(workspacePath)
            fs.writeText(
                indexPath(workspacePath),
                json.encodeToString(ListSerializer(SessionMeta.serializer()), remaining),
            )
        }
        fs.delete(sessionFilePath(workspacePath, sessionId), recursive = false)
    }

    private suspend fun upsertMeta(workspacePath: String, meta: SessionMeta) {
        mutex.withLock {
            val raw = runCatching { fs.readWholeText(indexPath(workspacePath)) }.getOrNull() ?: "[]"
            val parsed = runCatching {
                json.decodeFromString(ListSerializer(SessionMeta.serializer()), raw.trim())
            }.getOrDefault(emptyList())
            val updated = parsed.filter { it.id != meta.id } + meta
            fs.mkdir(dotDir(workspacePath))
            fs.writeText(
                indexPath(workspacePath),
                json.encodeToString(ListSerializer(SessionMeta.serializer()), updated),
            )
        }
    }

    private suspend fun appendLineSafe(path: String, jsonLine: String) {
        val line = "$jsonLine\n"
        val res = fs.appendText(path, line)
        if (res["ok"] == true) return
        val existing = runCatching { fs.readWholeText(path) }.getOrDefault("")
        fs.writeText(path, existing + line)
    }

    private fun parseRecords(rawText: String): List<JsonlRecord> {
        val out = ArrayList<JsonlRecord>()
        for (line in rawText.lineSequence()) {
            val s = line.trim()
            if (s.isEmpty()) continue
            runCatching { json.decodeFromString(JsonlRecord.serializer(), s) }
                .getOrNull()
                ?.let(out::add)
        }
        return out
    }

    private fun assembleLoaded(
        meta: SessionMeta,
        records: List<JsonlRecord>,
        hasEarlier: Boolean,
        loadedLineCount: Int,
    ): LoadedSession {
        val msgsById = LinkedHashMap<String, ChatMessage>()
        var usage = SessionUsage()
        var plan = emptyList<PlanStep>()
        for (r in records) {
            when (r.type) {
                "msg" -> r.message?.let { m ->
                    msgsById[m.id] = if (m is AssistantMsg) m.copy(isStreaming = false) else m
                }
                "usage" -> r.usage?.let { usage = it }
                "plan" -> r.plan?.let { plan = it }
            }
        }
        return LoadedSession(
            meta = meta,
            messages = msgsById.values.toList(),
            usage = usage,
            plan = plan,
            hasEarlier = hasEarlier,
            totalLines = loadedLineCount,
            loadedLineCount = loadedLineCount,
        )
    }
}
