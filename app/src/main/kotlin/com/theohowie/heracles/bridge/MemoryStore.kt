package com.theohowie.heracles.bridge

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock

class MemoryStore(private val context: Context) {

    companion object {
        const val DIR = "heracles"
        const val SUBDIR = "memory"
        const val GLOBAL_FILE = "global.jsonl"
        const val PROPOSALS_FILE = "proposals.jsonl"
        const val INDEX_FILE = "index.json"
        const val MIGRATE_FILE = "migrate.json"
        const val LEGACY_DIR = "legacy"
        const val LEGACY_FILE = "memory.md"
        const val VERSION = 1
        const val SCOPE = "global"
        const val ENTRY_MAX_CHARS = 8000
        const val GLOBAL_SOFT_BYTES = 8 * 1024 * 1024
        const val ENTRIES_SOFT_MAX = 5000
        const val SEARCH_DEFAULT_LIMIT = 20
        const val LIMIT_UNLIMITED = 0
        const val COMPACT_GARBAGE_RATIO = 0.5
        const val COMPACT_SIZE_RATIO = 0.8
        val KINDS: Set<String> = setOf("preference", "background", "habit", "other")
        val PROPOSAL_STATUSES: Set<String> = setOf("pending", "promoted", "rejected")

        val SECRET_PATTERN = Regex(
            "(sk-[A-Za-z0-9_-]{10,}|AKIA[0-9A-Z]{16}|ghp_[A-Za-z0-9]{20,}" +
                "|gho_[A-Za-z0-9]{20,}|eyJ[A-Za-z0-9_-]{15,}|[A-Fa-f0-9]{32,})",
        )

        private const val TAG = "HeraclesMemory"
    }

    data class MemoryItem(
        val id: String,
        val kind: String,
        val text: String,
        val source: String,
        val createdAt: Long,
        val expiresAt: Long?,
    ) {
        fun isExpired(nowMs: Long): Boolean = expiresAt != null && expiresAt <= nowMs
    }

    data class ProposalItem(
        val id: String,
        val status: String,
        val kind: String,
        val text: String,
        val source: String,
        val createdAt: Long,
        val resolvedAt: Long?,
        val promotedId: String?,
    )

    private val lock = ReentrantLock()
    private var lastMicros: Long = 0L

    fun path(): String? = globalFileOrNull()?.absolutePath

    suspend fun list(
        includeExpired: Boolean = false,
        limit: Int = LIMIT_UNLIMITED,
    ): List<MemoryItem> = withContext(Dispatchers.IO) {
        lock.lock()
        try {
            val dir = memoryDirOrNull() ?: return@withContext emptyList()
            val fold = fold(readTextOrEmpty(globalFile(dir)))
            val nowMs = System.currentTimeMillis()
            val all = fold.entries.values
                .filter { includeExpired || !it.isExpired(nowMs) }
                .sortedWith(NEWEST_FIRST)
            if (limit > 0) all.take(limit) else all
        } finally {
            lock.unlock()
        }
    }

    suspend fun search(query: String, limit: Int = SEARCH_DEFAULT_LIMIT): Map<String, Any?> = io {
        val q = query.trim()
        if (q.isEmpty()) throw StoreException("E_BAD_ARG", "يفتقر memory.search إلى كلمة البحث query")
        val tokens = tokenize(q)
        if (tokens.isEmpty()) throw StoreException("E_BAD_ARG", "لا تحتوي عبارة البحث على كلمات مفتاحية صالحة")
        val dir = requireDirLocked()
        val fold = fold(readTextOrEmpty(globalFile(dir)))
        val nowMs = System.currentTimeMillis()
        val scored = ArrayList<Pair<MemoryItem, Int>>()
        for (e in fold.entries.values) {
            if (e.isExpired(nowMs)) continue
            val hay = (e.text + " " + e.kind).lowercase(Locale.US)
            var score = 0
            for (t in tokens) if (hay.contains(t)) score++
            if (score > 0) scored.add(e to score)
        }
        scored.sortWith(
            compareByDescending<Pair<MemoryItem, Int>> { it.second }
                .thenByDescending { it.first.createdAt }
                .thenByDescending { it.first.id },
        )
        val cap = if (limit > 0) limit else SEARCH_DEFAULT_LIMIT
        val page = scored.take(cap)
        mapOf(
            "ok" to true,
            "entries" to page.map { entryMap(it.first) },
            "total" to scored.size,
        )
    }

    suspend fun userEdit(
        action: String,
        id: String? = null,
        text: String? = null,
        kind: String? = null,
    ): Map<String, Any?> = io {
        val dir = requireDirLocked()
        when (action.trim()) {
            "add" -> {
                val body = validateText(text)
                val k = validateKind(kind)
                val entry = MemoryItem(
                    id = nextEntryId(),
                    kind = k,
                    text = body,
                    source = "user",
                    createdAt = System.currentTimeMillis(),
                    expiresAt = null,
                )
                writeEntryLocked(dir, entry, replaceId = null, origin = "userEdit.add")
            }

            "update" -> {
                val targetId = requiredId(id, "update")
                val body = validateText(text)
                val old = fold(readTextOrEmpty(globalFile(dir))).entries[targetId]
                    ?: throw StoreException("E_NOT_FOUND", "العنصر غير موجود: $targetId")
                val k = if (kind.isNullOrBlank()) old.kind else validateKind(kind)
                writeEntryLocked(dir, old.copy(text = body, kind = k), replaceId = targetId, origin = "userEdit.update")
            }

            "delete" -> {
                val targetId = requiredId(id, "delete")
                deleteLocked(dir, listOf(targetId), all = false) + mapOf("id" to targetId)
            }

            "clear" -> deleteLocked(dir, emptyList(), all = true)

            else -> throw StoreException("E_BAD_ARG", "إجراء غير معروف: $action")
        }
    }

    suspend fun propose(note: String, kind: String? = null): Map<String, Any?> = io {
        val dir = requireDirLocked()
        val body = validateText(note)
        val k = validateKind(kind)
        val nowMs = System.currentTimeMillis()
        val id = nextProposalId()
        val obj = JSONObject()
            .put("v", VERSION)
            .put("id", id)
            .put("status", "pending")
            .put("kind", k)
            .put("text", body)
            .put("source", "agent")
            .put("createdAt", nowMs)
        val f = proposalsFile(dir)
        appendRaw(f, obj.toString() + "\n")
        mapOf("ok" to true, "id" to id, "pending" to countPending(f))
    }

    suspend fun listProposals(status: String? = "pending"): List<ProposalItem> =
        withContext(Dispatchers.IO) {
            lock.lock()
            try {
                val dir = memoryDirOrNull() ?: return@withContext emptyList()
                val want = (status ?: "pending").trim().lowercase(Locale.US)
                readProposalsLocked(proposalsFile(dir)).values
                    .filter {
                        want.isEmpty() || want == "all" ||
                            it.strOrEmpty("status").ifEmpty { "pending" } == want
                    }
                    .sortedByDescending { it.longOrNull("createdAt") ?: 0L }
                    .map { o ->
                        ProposalItem(
                            id = o.strOrEmpty("id"),
                            status = o.strOrEmpty("status").ifEmpty { "pending" },
                            kind = o.strOrEmpty("kind").ifEmpty { "other" },
                            text = o.strOrEmpty("text"),
                            source = o.strOrEmpty("source").ifEmpty { "agent" },
                            createdAt = o.longOrNull("createdAt") ?: 0L,
                            resolvedAt = o.longOrNull("resolvedAt"),
                            promotedId = o.strOrEmpty("promotedId").ifEmpty { null },
                        )
                    }
            } finally {
                lock.unlock()
            }
        }

    suspend fun resolveProposal(id: String, action: String): Map<String, Any?> = io {
        val dir = requireDirLocked()
        val targetId = id.trim()
        if (targetId.isEmpty()) throw StoreException("E_BAD_ARG", "المعامل id مفقود")
        val act = action.trim().lowercase(Locale.US)
        if (act != "promote" && act != "reject") {
            throw StoreException("E_BAD_ARG", "إجراء غير معروف: $action")
        }
        val f = proposalsFile(dir)
        val map = readProposalsLocked(f)
        val obj = map[targetId] ?: throw StoreException("E_NOT_FOUND", "المقترح غير موجود: $targetId")
        val status = obj.strOrEmpty("status").ifEmpty { "pending" }
        if (status != "pending") {
            throw StoreException("E_BAD_ARG", "تمت معالجة المقترح مسبقاً (status=$status)")
        }
        val nowMs = System.currentTimeMillis()
        if (act == "promote") {
            val text = obj.strOrEmpty("text")
            if (text.isBlank()) throw StoreException("E_BAD_ARG", "نص المقترح فارغ: $targetId")
            if (SECRET_PATTERN.containsMatchIn(text)) {
                throw StoreException("E_SECRET", SECRET_REJECT_MESSAGE)
            }
            if (text.length > ENTRY_MAX_CHARS) {
                throw StoreException("E_ENTRY_TOO_LONG", "يتجاوز نص المقترح حد الإدخال الواحد $ENTRY_MAX_CHARS.")
            }
            val entry = MemoryItem(
                id = nextEntryId(),
                kind = obj.strOrEmpty("kind").ifEmpty { "other" },
                text = text,
                source = "promoted",
                createdAt = nowMs,
                expiresAt = null,
            )
            val written = writeEntryLocked(dir, entry, replaceId = null, origin = "resolveProposal.promote")
            obj.put("status", "promoted").put("resolvedAt", nowMs).put("promotedId", entry.id)
            map[targetId] = obj
            rewriteProposalsLocked(f, map)
            return@io written + mapOf("id" to targetId, "entryId" to entry.id, "promoted" to true)
        }
        obj.put("status", "rejected").put("resolvedAt", nowMs)
        map[targetId] = obj
        rewriteProposalsLocked(f, map)
        mapOf("ok" to true, "id" to targetId, "promoted" to false)
    }

    suspend fun stats(): Map<String, Any?> = io {
        val dir = memoryDirOrNull()
        val gf = dir?.let { globalFile(it) }
        val raw = if (gf == null) "" else readTextOrEmpty(gf)
        val fold = fold(raw)
        val nowMs = System.currentTimeMillis()
        val alive = fold.entries.values.filter { !it.isExpired(nowMs) }
        mapOf(
            "ok" to true,
            "scope" to SCOPE,
            "entries" to alive.size,
            "expired" to (fold.entries.size - alive.size),
            "bytes" to survivingBytes(alive),
            "fileBytes" to (if (gf != null && gf.exists()) gf.length() else 0L),
            "pendingProposals" to (if (dir == null) 0 else countPending(proposalsFile(dir))),
            "path" to gf?.absolutePath,
        )
    }

    suspend fun migrate(): Map<String, Any?> = io {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = requireDirLocked()
        val gf = globalFile(dir)
        val markFile = File(dir, MIGRATE_FILE)
        val legacyFile = File(File(base, DIR), LEGACY_FILE)

        val mark = readJsonOrNull(markFile)
        if (mark != null && mark.optInt("v", 0) == VERSION && gf.exists()) {
            return@io mapOf("ok" to true, "migrated" to false, "alreadyMigrated" to true)
        }
        if (!legacyFile.exists() || !legacyFile.isFile) {
            return@io mapOf("ok" to true, "migrated" to false, "reason" to "no-legacy-file")
        }
        if (gf.exists() && gf.length() > 0L) {
            val alive = fold(readTextOrEmpty(gf)).entries.size
            writeMigrateMark(dir, alive, null, "global-jsonl-exists")
            return@io mapOf("ok" to true, "migrated" to false, "alreadyMigrated" to true, "entries" to alive)
        }

        val stamp = System.currentTimeMillis()
        val backupName = "$LEGACY_FILE.$stamp.bak"
        val legacyDir = File(dir, LEGACY_DIR)
        val backupFile = File(legacyDir, backupName)
        copyFileSynced(legacyFile, backupFile)

        val tmp = File(dir, "$GLOBAL_FILE.tmp")
        try {
            val entries = parseLegacyEntries(legacyFile)
            val sb = StringBuilder()
            for (e in entries) sb.append(entryJsonString(e)).append('\n')
            FileOutputStream(tmp).use { out ->
                out.write(sb.toString().toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(gf)) {
                if (gf.exists()) gf.delete()
                if (!tmp.renameTo(gf)) throw StoreException("E_MIGRATE_FAILED", "فشل استبدال global.jsonl")
            }
            writeIndexBestEffort(dir)
            writeMigrateMark(dir, entries.size, "$LEGACY_DIR/$backupName", null)
            runCatching {
                val dest = File(legacyDir, LEGACY_FILE)
                legacyFile.renameTo(if (dest.exists()) File(legacyDir, "$LEGACY_FILE.$stamp.moved") else dest)
            }
            mapOf("ok" to true, "migrated" to true, "entries" to entries.size)
        } catch (e: Throwable) {
            runCatching { if (tmp.exists()) tmp.delete() }
            if (e is StoreException) throw e
            throw StoreException("E_MIGRATE_FAILED", "فشل الترحيل: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun io(block: () -> Map<String, Any?>): Map<String, Any?> =
        withContext(Dispatchers.IO) {
            lock.lock()
            try {
                block()
            } catch (e: StoreException) {
                Log.w(TAG, "store error: ${e.code}")
                mapOf("ok" to false, "code" to e.code, "error" to e.message)
            } catch (e: Throwable) {
                Log.w(TAG, "store io error: ${e.javaClass.simpleName}")
                mapOf("ok" to false, "code" to "E_STORE_IO", "error" to "فشل الإدخال/الإخراج في مخزن الذاكرة: ${e.javaClass.simpleName}")
            } finally {
                lock.unlock()
            }
        }

    private fun memoryDirOrNull(): File? {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(File(base, DIR), SUBDIR)
    }

    private fun globalFileOrNull(): File? = memoryDirOrNull()?.let { globalFile(it) }
    private fun globalFile(dir: File): File = File(dir, GLOBAL_FILE)
    private fun proposalsFile(dir: File): File = File(dir, PROPOSALS_FILE)

    private fun requireDirLocked(): File {
        val dir = memoryDirOrNull()
            ?: throw StoreException("E_STORE_IO", "تعذر تحديد المجلد الخاص بالتطبيق")
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw StoreException("E_STORE_IO", "تعذر إنشاء مجلد الذاكرة")
        }
        return dir
    }

    private fun entryJsonString(e: MemoryItem): String = JSONObject()
        .put("v", VERSION)
        .put("id", e.id)
        .put("scope", SCOPE)
        .put("kind", e.kind)
        .put("text", e.text)
        .put("source", e.source)
        .put("createdAt", e.createdAt)
        .apply {
            if (e.expiresAt == null) put("expiresAt", JSONObject.NULL) else put("expiresAt", e.expiresAt)
        }
        .toString()

    private fun entryMap(e: MemoryItem): Map<String, Any?> = linkedMapOf(
        "id" to e.id,
        "scope" to SCOPE,
        "kind" to e.kind,
        "text" to e.text,
        "source" to e.source,
        "createdAt" to e.createdAt,
        "expiresAt" to e.expiresAt,
    )

    private fun lineBytes(e: MemoryItem): Long = entryJsonString(e).toByteArray(Charsets.UTF_8).size.toLong()
    private fun aliveOf(fold: Fold, nowMs: Long): List<MemoryItem> = fold.entries.values.filter { !it.isExpired(nowMs) }
    private fun survivingBytes(entries: Collection<MemoryItem>): Long = entries.sumOf { lineBytes(it) }

    private val NEWEST_FIRST: Comparator<MemoryItem> =
        compareByDescending<MemoryItem> { it.createdAt }.thenByDescending { it.id }

    private class Fold(
        val entries: LinkedHashMap<String, MemoryItem>,
        val totalLines: Int,
        val invalidLines: Int,
        val tombstoneLines: Int,
    )

    private fun fold(raw: String): Fold {
        val entries = LinkedHashMap<String, MemoryItem>()
        var total = 0
        var invalid = 0
        var tombs = 0
        for (line in raw.lineSequence()) {
            val s = line.trim()
            if (s.isEmpty()) continue
            total++
            val obj = runCatching { JSONObject(s) }.getOrNull() ?: run { invalid++; continue }
            val tomb = obj.strOrEmpty("tomb")
            if (tomb.isNotEmpty()) {
                tombs++
                when (tomb) {
                    "clear" -> entries.clear()
                    "del" -> {
                        val arr = obj.optJSONArray("ids")
                        if (arr == null) invalid++ else {
                            for (i in 0 until arr.length()) {
                                val id = arr.optString(i, "")
                                if (id.isNotEmpty()) entries.remove(id)
                            }
                        }
                    }
                    else -> invalid++
                }
                continue
            }
            val id = obj.strOrEmpty("id")
            if (id.isEmpty()) { invalid++; continue }
            val scope = obj.strOrEmpty("scope").ifEmpty { SCOPE }
            if (scope != SCOPE) { invalid++; continue }
            val text = obj.strOrEmpty("text")
            if (text.isEmpty()) { invalid++; continue }
            entries[id] = MemoryItem(
                id = id,
                kind = obj.strOrEmpty("kind").ifEmpty { "other" },
                text = text,
                source = obj.strOrEmpty("source").ifEmpty { "legacy" },
                createdAt = obj.longOrNull("createdAt") ?: 0L,
                expiresAt = obj.longOrNull("expiresAt")?.takeIf { it > 0L },
            )
        }
        return Fold(entries, total, invalid, tombs)
    }

    private fun writeEntryLocked(
        dir: File,
        entry: MemoryItem,
        replaceId: String?,
        origin: String,
    ): Map<String, Any?> {
        val nowMs = System.currentTimeMillis()
        val gf = globalFile(dir)
        val before = fold(readTextOrEmpty(gf))
        if (replaceId != null && !before.entries.containsKey(replaceId)) {
            throw StoreException("E_NOT_FOUND", "العنصر غير موجود: $replaceId")
        }
        val line = entryJsonString(entry)
        val newLineBytes = line.toByteArray(Charsets.UTF_8).size.toLong()
        val candidates = before.entries.values.filter { it.id != replaceId }
        val plan = planEviction(candidates, newLineBytes, nowMs)

        val buf = StringBuilder()
        if (replaceId != null) buf.append(tombDelLine(listOf(replaceId), nowMs)).append('\n')
        if (plan.isNotEmpty()) buf.append(tombDelLine(plan, nowMs)).append('\n')
        buf.append(line).append('\n')
        appendRaw(gf, buf.toString())

        val compactError = maybeCompactLocked(gf)
        writeIndexBestEffort(dir)
        val after = fold(readTextOrEmpty(gf))
        Log.i(TAG, "$origin: id=${entry.id} alive=${after.entries.size} evicted=${plan.size}")
        val out = linkedMapOf<String, Any?>(
            "ok" to true,
            "id" to entry.id,
            "bytes" to survivingBytes(aliveOf(after, System.currentTimeMillis())),
            "entries" to after.entries.size,
            "evicted" to plan,
        )
        if (compactError != null) out["compactError"] = compactError
        return out
    }

    private fun planEviction(entries: List<MemoryItem>, newLineBytes: Long, nowMs: Long): List<String> {
        var count = entries.size
        var bytes = survivingBytes(entries)
        val evicted = LinkedHashSet<String>()
        fun fits(): Boolean = count + 1 <= ENTRIES_SOFT_MAX && bytes + newLineBytes <= GLOBAL_SOFT_BYTES
        if (fits()) return emptyList()
        fun evict(e: MemoryItem) {
            if (evicted.add(e.id)) {
                count--
                bytes -= lineBytes(e)
            }
        }
        for (e in entries.filter { it.isExpired(nowMs) }.sortedBy { it.createdAt }) { if (fits()) break; evict(e) }
        for (e in entries.filter { it.source == "agent" }.sortedBy { it.createdAt }) { if (fits()) break; evict(e) }
        for (e in entries.filter { it.source != "agent" && it.source != "user" }.sortedBy { it.createdAt }) { if (fits()) break; evict(e) }
        for (e in entries.filter { it.source == "user" }.sortedBy { it.createdAt }) { if (fits()) break; evict(e) }
        if (!fits()) {
            throw StoreException("E_ENTRY_TOO_LONG", "تجاوزت السعة الحد المرن للذاكرة العامة، ولا تكفي المساحة حتى بعد التنظيف")
        }
        return evicted.toList()
    }

    private fun deleteLocked(dir: File, ids: List<String>, all: Boolean): Map<String, Any?> {
        val nowMs = System.currentTimeMillis()
        val gf = globalFile(dir)
        val before = fold(readTextOrEmpty(gf))
        val targets: List<String> = if (all) before.entries.keys.toList() else ids.filter { before.entries.containsKey(it) }
        if (!all && targets.isEmpty()) {
            throw StoreException("E_NOT_FOUND", "العنصر المراد حذفه غير موجود")
        }
        if (all && before.entries.isEmpty()) {
            appendRaw(gf, tombClearLine(nowMs) + "\n")
            return linkedMapOf("ok" to true, "removed" to 0, "bytes" to 0L, "entries" to 0)
        }
        val line = if (all) tombClearLine(nowMs) else tombDelLine(targets, nowMs)
        appendRaw(gf, line + "\n")
        val compactError = maybeCompactLocked(gf)
        writeIndexBestEffort(dir)
        val after = fold(readTextOrEmpty(gf))
        val out = linkedMapOf<String, Any?>(
            "ok" to true,
            "removed" to targets.size,
            "bytes" to survivingBytes(aliveOf(after, System.currentTimeMillis())),
            "entries" to after.entries.size,
        )
        if (compactError != null) out["compactError"] = compactError
        return out
    }

    private fun tombDelLine(ids: List<String>, ts: Long): String =
        JSONObject().put("v", VERSION).put("tomb", "del").put("ids", JSONArray(ids)).put("ts", ts).toString()

    private fun tombClearLine(ts: Long): String =
        JSONObject().put("v", VERSION).put("tomb", "clear").put("ts", ts).toString()

    private fun maybeCompactLocked(gf: File): String? {
        if (!gf.isFile) return null
        val fold = fold(readTextOrEmpty(gf))
        if (fold.totalLines == 0) return null
        val dead = fold.invalidLines + fold.tombstoneLines +
            (fold.totalLines - fold.entries.size - fold.invalidLines - fold.tombstoneLines).coerceAtLeast(0)
        val ratio = dead.toDouble() / fold.totalLines.toDouble()
        val tooBig = gf.length() >= (GLOBAL_SOFT_BYTES * COMPACT_SIZE_RATIO).toLong()
        if (ratio < COMPACT_GARBAGE_RATIO && !tooBig) return null
        return runCatching {
            val sb = StringBuilder()
            for (e in fold.entries.values) sb.append(entryJsonString(e)).append('\n')
            writeAtomic(gf, sb.toString())
            null
        }.getOrElse { it.javaClass.simpleName }
    }

    private fun readProposalsLocked(f: File): LinkedHashMap<String, JSONObject> {
        val out = LinkedHashMap<String, JSONObject>()
        for (line in readTextOrEmpty(f).lineSequence()) {
            val s = line.trim()
            if (s.isEmpty()) continue
            val o = runCatching { JSONObject(s) }.getOrNull() ?: continue
            val id = o.strOrEmpty("id")
            if (id.isNotEmpty()) out[id] = o
        }
        return out
    }

    private fun rewriteProposalsLocked(f: File, map: LinkedHashMap<String, JSONObject>) {
        val sb = StringBuilder()
        for (o in map.values) sb.append(o.toString()).append('\n')
        writeAtomic(f, sb.toString())
    }

    private fun countPending(f: File): Int =
        readProposalsLocked(f).values.count { it.strOrEmpty("status").ifEmpty { "pending" } == "pending" }

    private fun parseLegacyEntries(legacyFile: File): List<MemoryItem> {
        val raw = String(legacyFile.readBytes(), Charsets.UTF_8)
        val out = ArrayList<MemoryItem>()
        val seen = HashSet<String>()
        for (line in raw.lineSequence()) {
            val s = line.trim()
            if (s.isEmpty()) continue
            var entry: MemoryItem? = null
            val brace = s.indexOf('{')
            if (brace >= 0) {
                val obj = runCatching { JSONObject(s.substring(brace)) }.getOrNull()
                val text = obj?.strOrEmpty("text").orEmpty()
                if (obj != null && text.isNotEmpty()) {
                    entry = MemoryItem(
                        id = obj.strOrEmpty("id").ifEmpty { nextEntryId() },
                        kind = obj.strOrEmpty("kind").ifEmpty { "other" },
                        text = text,
                        source = obj.strOrEmpty("source").ifEmpty { "legacy" },
                        createdAt = obj.longOrNull("createdAt") ?: 0L,
                        expiresAt = obj.longOrNull("expiresAt")?.takeIf { it > 0L },
                    )
                }
            }
            if (entry == null) {
                entry = MemoryItem(nextEntryId(), "other", s, "legacy", 0L, null)
            }
            val id = if (seen.add(entry.id)) entry.id else nextEntryId()
            out.add(entry.copy(id = id))
        }
        return out
    }

    private fun writeMigrateMark(dir: File, entries: Int, backup: String?, reason: String?) {
        val obj = JSONObject().put("v", VERSION).put("scope", SCOPE).put("entries", entries)
            .put("migratedAt", System.currentTimeMillis())
        if (backup != null) obj.put("backup", backup)
        if (reason != null) obj.put("reason", reason)
        runCatching { writeAtomic(File(dir, MIGRATE_FILE), obj.toString()) }
    }

    private fun writeIndexBestEffort(dir: File) {
        runCatching {
            val f = fold(readTextOrEmpty(globalFile(dir)))
            val nowMs = System.currentTimeMillis()
            val alive = f.entries.values.filter { !it.isExpired(nowMs) }
            val obj = JSONObject().put("v", VERSION).put("scope", SCOPE).put("entries", alive.size)
                .put("bytes", survivingBytes(alive)).put("updatedAt", nowMs)
            writeAtomic(File(dir, INDEX_FILE), obj.toString())
        }
    }

    private val SECRET_REJECT_MESSAGE: String
        get() = "يبدو أن المحتوى يتضمن مفتاحاً سرياً أو رمزاً، تم رفض الحفظ في الذاكرة طويلة المدى (الذاكرة نصية واضحة وقد تتسرب)."

    private fun validateText(text: String?): String {
        val body = (text ?: "").trim()
        if (body.isEmpty()) throw StoreException("E_BAD_ARG", "المحتوى المراد تذكّره فارغ")
        if (body.length > ENTRY_MAX_CHARS) {
            throw StoreException("E_ENTRY_TOO_LONG", "تتجاوز الذاكرة الواحدة الحد الأقصى البالغ $ENTRY_MAX_CHARS حرفاً")
        }
        if (SECRET_PATTERN.containsMatchIn(body)) {
            throw StoreException("E_SECRET", SECRET_REJECT_MESSAGE)
        }
        return body
    }

    private fun validateKind(kind: String?): String {
        if (kind.isNullOrBlank()) return "other"
        val k = kind.trim().lowercase(Locale.US)
        if (k !in KINDS) throw StoreException("E_BAD_ARG", "نوع غير معروف: $kind")
        return k
    }

    private fun requiredId(id: String?, action: String): String {
        val s = (id ?: "").trim()
        if (s.isEmpty()) throw StoreException("E_BAD_ARG", "المعامل id مفقود في userEdit $action")
        return s
    }

    private fun readTextOrEmpty(f: File): String = if (!f.isFile) "" else String(f.readBytes(), Charsets.UTF_8)
    private fun readJsonOrNull(f: File): JSONObject? =
        if (!f.isFile) null else runCatching { JSONObject(readTextOrEmpty(f).trim()) }.getOrNull()

    private fun appendRaw(f: File, text: String) {
        f.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
        FileOutputStream(f, true).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
    }

    private fun writeAtomic(target: File, text: String) {
        target.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) throw StoreException("E_STORE_IO", "فشل استبدال الملف: ${target.name}")
        }
    }

    private fun copyFileSynced(src: File, dst: File) {
        dst.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
        FileInputStream(src).use { input ->
            FileOutputStream(dst).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }
                out.flush()
                out.fd.sync()
            }
        }
    }

    private fun nextMicros(): Long {
        var v = System.currentTimeMillis() * 1000L
        if (v <= lastMicros) v = lastMicros + 1L
        lastMicros = v
        return v
    }

    private fun nextEntryId(): String = "m" + nextMicros()
    private fun nextProposalId(): String = "p" + nextMicros()

    private fun tokenize(query: String): List<String> {
        val parts = query.lowercase(Locale.US)
            .split(Regex("[\\s\\p{Punct}،؛؟，。、；：！？（）【】「」《》·…—]+"))
            .filter { it.isNotEmpty() }
        if (parts.size > 1) return parts
        val one = parts.firstOrNull() ?: return emptyList()
        if (one.length <= 2) return listOf(one)
        val grams = LinkedHashSet<String>()
        grams.add(one)
        for (i in 0 until one.length - 1) grams.add(one.substring(i, i + 2))
        return grams.toList()
    }

    private fun JSONObject.strOrEmpty(name: String): String {
        if (!has(name) || isNull(name)) return ""
        val v = opt(name) ?: return ""
        return if (v is String) v else v.toString()
    }

    private fun JSONObject.longOrNull(name: String): Long? {
        if (!has(name) || isNull(name)) return null
        return when (val v = opt(name)) {
            is Number -> v.toLong()
            is String -> v.trim().toLongOrNull()
            else -> null
        }
    }

    private class StoreException(val code: String, override val message: String) : Exception(message)
}
