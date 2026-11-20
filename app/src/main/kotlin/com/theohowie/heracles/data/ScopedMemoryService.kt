package com.theohowie.heracles.data

import com.theohowie.heracles.bridge.DeviceFs
import com.theohowie.heracles.bridge.MemoryStore
import com.theohowie.heracles.bridge.WorkspaceRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class ScopedMemoryService(
    private val globalStore: MemoryStore,
    private val fs: DeviceFs,
) {
    @Serializable
    data class ScopedEntry(
        val id: String,
        val scope: String, // "workspace" | "session"
        val kind: String,
        val text: String,
        val createdAt: Long,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun workspaceMemoryPath(workspacePath: String): String =
        workspacePath.trimEnd('/') + "/" + WorkspaceRegistry.DOT_DIR + "/memory.jsonl"

    private fun sessionMemoryPath(workspacePath: String, sessionId: String): String =
        workspacePath.trimEnd('/') + "/" + WorkspaceRegistry.DOT_DIR + "/sessions/$sessionId.mem.jsonl"

    suspend fun listScoped(scope: String, workspacePath: String, sessionId: String?): List<ScopedEntry> {
        val path = when (scope) {
            "workspace" -> workspaceMemoryPath(workspacePath)
            "session" -> if (!sessionId.isNullOrBlank()) sessionMemoryPath(workspacePath, sessionId) else return emptyList()
            else -> return emptyList()
        }
        val raw = runCatching { fs.readWholeText(path) }.getOrNull() ?: return emptyList()
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { runCatching { json.decodeFromString(ScopedEntry.serializer(), it) }.getOrNull() }
            .toList()
            .sortedByDescending { it.createdAt }
    }

    suspend fun saveScoped(
        scope: String,
        workspacePath: String,
        sessionId: String?,
        kind: String,
        text: String,
    ): Result<ScopedEntry> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("لا يمكن أن يكون محتوى الذاكرة فارغاً"))
        if (trimmed.length > MemoryStore.ENTRY_MAX_CHARS) {
            return Result.failure(IllegalArgumentException("يتجاوز الإدخال الواحد حد ${MemoryStore.ENTRY_MAX_CHARS} حرف"))
        }
        if (MemoryStore.SECRET_PATTERN.containsMatchIn(trimmed)) {
            return Result.failure(IllegalArgumentException("يبدو أن المحتوى يتضمن مفتاحاً سرياً أو رمزاً؛ تم رفض الحفظ في الذاكرة النصية"))
        }
        val normalizedScope = scope.lowercase().trim()
        if (normalizedScope == "global") {
            return Result.failure(
                IllegalArgumentException("تمنع قواعد الأمان الكتابة المباشرة في الذاكرة طويلة المدى global؛ استخدم propose_global_memory لتقديم مقترح ينتظر موافقة المستخدم."),
            )
        }
        val path = when (normalizedScope) {
            "workspace" -> workspaceMemoryPath(workspacePath)
            "session" -> {
                if (sessionId.isNullOrBlank()) return Result.failure(IllegalArgumentException("معرف الجلسة sessionId مفقود"))
                sessionMemoryPath(workspacePath, sessionId)
            }
            else -> return Result.failure(IllegalArgumentException("نطاق غير معروف: $scope (يدعم workspace / session)"))
        }
        val entry = ScopedEntry(
            id = "sm_${System.currentTimeMillis()}_${(1000..9999).random()}",
            scope = normalizedScope,
            kind = if (kind.lowercase() in MemoryStore.KINDS) kind.lowercase() else "other",
            text = trimmed,
            createdAt = System.currentTimeMillis(),
        )
        val line = json.encodeToString(ScopedEntry.serializer(), entry) + "\n"
        val res = fs.appendText(path, line)
        if (res["ok"] != true) {
            val prev = runCatching { fs.readWholeText(path) }.getOrDefault("")
            val w = fs.writeText(path, prev + line)
            if (w["ok"] != true) {
                return Result.failure(IllegalStateException(w["error"]?.toString() ?: "فشلت كتابة الذاكرة"))
            }
        }
        return Result.success(entry)
    }

    suspend fun deleteScoped(
        scope: String,
        workspacePath: String,
        sessionId: String?,
        entryId: String,
    ): Boolean {
        val path = when (scope) {
            "workspace" -> workspaceMemoryPath(workspacePath)
            "session" -> if (!sessionId.isNullOrBlank()) sessionMemoryPath(workspacePath, sessionId) else return false
            else -> return false
        }
        val current = listScoped(scope, workspacePath, sessionId)
        val filtered = current.filter { it.id != entryId }
        if (filtered.size == current.size) return false
        val body = filtered.sortedBy { it.createdAt }
            .joinToString("\n") { json.encodeToString(ScopedEntry.serializer(), it) }
        val res = fs.writeText(path, if (body.isEmpty()) "" else "$body\n")
        return res["ok"] == true
    }

    suspend fun buildPromptBlock(workspacePath: String, sessionId: String?): String {
        val global = globalStore.list(limit = 30)
        val ws = listScoped("workspace", workspacePath, sessionId).take(25)
        val sess = listScoped("session", workspacePath, sessionId).take(20)
        if (global.isEmpty() && ws.isEmpty() && sess.isEmpty()) return ""
        return buildString {
            appendLine("## Memory Context")
            if (global.isNotEmpty()) {
                appendLine("### Global (user-approved)")
                global.forEach { appendLine("- [${it.kind}] ${it.text}") }
            }
            if (ws.isNotEmpty()) {
                appendLine("### Workspace")
                ws.forEach { appendLine("- [${it.kind}] ${it.text}") }
            }
            if (sess.isNotEmpty()) {
                appendLine("### Session")
                sess.forEach { appendLine("- [${it.kind}] ${it.text}") }
            }
        }
    }
}
