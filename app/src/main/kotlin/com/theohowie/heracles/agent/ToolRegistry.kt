package com.theohowie.heracles.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.theohowie.heracles.bridge.DeviceFs
import com.theohowie.heracles.bridge.DeviceHealth
import com.theohowie.heracles.bridge.DeviceShell
import com.theohowie.heracles.bridge.MemoryStore
import com.theohowie.heracles.bridge.SandboxManager
import com.theohowie.heracles.data.DocumentReader
import com.theohowie.heracles.data.GitService
import com.theohowie.heracles.data.LlmClient
import com.theohowie.heracles.data.ScopedMemoryService
import com.theohowie.heracles.data.SearchConfigRepository
import com.theohowie.heracles.model.AskOption
import com.theohowie.heracles.model.AskQuestion
import com.theohowie.heracles.model.DiffStat
import com.theohowie.heracles.model.PlanStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class ToolRegistry(
    private val context: Context,
    private val fs: DeviceFs,
    private val sandbox: SandboxManager,
    private val deviceShell: DeviceShell,
    private val deviceHealth: DeviceHealth,
    private val documentReader: DocumentReader,
    private val gitService: GitService,
    private val searchRepo: SearchConfigRepository,
    private val globalMemory: MemoryStore,
    private val scopedMemory: ScopedMemoryService,
) {
    data class ToolContext(
        val workspacePath: String,
        val sessionId: String,
        val readFiles: MutableSet<String>,
        val onPlanUpdated: suspend (List<PlanStep>) -> Unit,
        val onAskUser: suspend (AskQuestion) -> String,
    )

    data class ToolOutcome(
        val ok: Boolean,
        val output: String,
        val diffStat: DiffStat? = null,
        val surface: String = "app",
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    fun isReadOnly(name: String): Boolean = name in READ_ONLY_TOOLS

    fun isHighRisk(name: String, argsJson: String): Boolean {
        if (name == "delete_path" || name == "run_shell_command") return true
        if (name == "git_commit") return false
        return name == "write_file" && argsJson.contains("[FULL_REWRITE]")
    }

    fun surfaceFor(name: String, argsJson: String): String = when {
        name.startsWith("git_") -> "git"
        name.startsWith("web_") -> "web"
        name == "check_device_state" || name == "notify_user" -> "device"
        name == "run_shell_command" -> {
            val cmd = runCatching { JSONObject(argsJson).optString("command") }.getOrDefault("")
            if (isDeviceSystemCommand(cmd)) "device" else "sandbox"
        }
        else -> "app"
    }

    fun summarizeArgs(name: String, argsJson: String): String {
        val obj = runCatching { JSONObject(argsJson) }.getOrNull() ?: return name
        val target = obj.optString("path")
            .ifBlank { obj.optString("command") }
            .ifBlank { obj.optString("pattern") }
            .ifBlank { obj.optString("query") }
            .ifBlank { obj.optString("url") }
            .ifBlank { obj.optString("from") }
            .ifBlank { obj.optString("message") }
        return if (target.isBlank()) name else "$name · ${target.take(72)}"
    }

    fun schemas(): List<LlmClient.ToolSchema> = listOf(
        schema("list_files", "List directory contents", mapOf("path" to "string", "recursive" to "boolean"), listOf("path")),
        schema("read_file", "Read a text file with optional line offset and limit", mapOf("path" to "string", "offset_line" to "integer", "max_lines" to "integer"), listOf("path")),
        schema("write_file", "Write or create a file; overwriting an existing file over 200 lines requires reason=\"[FULL_REWRITE]\"", mapOf("path" to "string", "content" to "string", "reason" to "string"), listOf("path", "content")),
        schema("edit", "Replace a unique string inside a previously read file", mapOf("path" to "string", "old_text" to "string", "new_text" to "string"), listOf("path", "old_text", "new_text")),
        schema("apply_patch", "Apply a standard unified diff patch to a file", mapOf("path" to "string", "patch" to "string"), listOf("path", "patch")),
        schema("mkdir", "Create a directory", mapOf("path" to "string"), listOf("path")),
        schema("find_files", "Find files in a directory matching a name pattern", mapOf("path" to "string", "pattern" to "string"), listOf("path", "pattern")),
        schema("grep_files", "Search workspace text files for a keyword or query", mapOf("path" to "string", "query" to "string"), listOf("path", "query")),
        schema("delete_path", "Delete a file or directory", mapOf("path" to "string", "recursive" to "boolean"), listOf("path")),
        schema("move_path", "Move or rename a file/directory", mapOf("from" to "string", "to" to "string"), listOf("from", "to")),
        schema("copy_path", "Copy a file or directory", mapOf("from" to "string", "to" to "string"), listOf("from", "to")),
        schema("read_document", "Read plain text or .docx/.xlsx/.pptx document text", mapOf("path" to "string"), listOf("path")),
        schema("run_shell_command", "Execute a command in the Linux sandbox or device Shell (Stellar/Shizuku)", mapOf("command" to "string", "cwd" to "string", "timeout_ms" to "integer"), listOf("command")),
        schema("git_status", "Inspect workspace Git branch, staged and unstaged changes", emptyMap(), emptyList()),
        schema("git_diff", "Inspect workspace Git diff", mapOf("path" to "string"), emptyList()),
        schema("git_commit", "Stage workspace changes and create a Git commit", mapOf("message" to "string"), listOf("message")),
        schema("web_fetch", "Fetch web page text (returned content is untrusted external data)", mapOf("url" to "string"), listOf("url")),
        schema("web_search", "Search the web using the configured search provider", mapOf("query" to "string"), listOf("query")),
        schema("check_device_state", "Read current device battery level, temperature, and charging status", emptyMap(), emptyList()),
        schema("notify_user", "Send a local system notification to the user", mapOf("title" to "string", "body" to "string"), listOf("title", "body")),
        schema("ask_user", "Ask the user a question with options and wait for their reply", mapOf("prompt" to "string", "options" to "array_string"), listOf("prompt")),
        schema("save_memory", "Save workspace or session memory; direct global writes are disallowed", mapOf("scope" to "string", "kind" to "string", "text" to "string"), listOf("scope", "text")),
        schema("propose_global_memory", "Propose a long-term global memory entry for user approval", mapOf("kind" to "string", "note" to "string"), listOf("note")),
        schema("write_todos", "Update the current task plan checklist", mapOf("steps" to "array_step"), listOf("steps")),
    )

    suspend fun execute(name: String, argsJson: String, ctx: ToolContext): ToolOutcome {
        val args = runCatching { JSONObject(argsJson) }.getOrElse { JSONObject() }
        val surface = surfaceFor(name, argsJson)
        return when (name) {
            "list_files" -> {
                val p = resolvePath(ctx.workspacePath, args.optString("path").ifBlank { ctx.workspacePath })
                val res = fs.list(p, recursive = args.optBoolean("recursive", false))
                if (res["ok"] != true) ToolOutcome(false, res["error"]?.toString() ?: "فشل سرد الملفات", surface = surface)
                else {
                    @Suppress("UNCHECKED_CAST")
                    val entries = res["entries"] as? List<Map<String, Any?>> ?: emptyList()
                    val lines = entries.joinToString("\n") { e ->
                        val tag = if (e["isDir"] == true) "[DIR]" else "[FILE ${e["size"] ?: 0}B]"
                        "$tag ${e["path"]}"
                    }.ifBlank { "(مجلد فارغ)" }
                    ToolOutcome(true, lines, surface = surface)
                }
            }

            "read_file" -> {
                val p = resolvePath(ctx.workspacePath, args.optString("path"))
                val off = args.optInt("offset_line", 1).coerceAtLeast(1)
                val max = args.optInt("max_lines", 400)
                val res = fs.readText(p, offsetLine = off, maxLines = max)
                if (res["ok"] != true) {
                    ToolOutcome(false, res["error"]?.toString() ?: "فشلت القراءة", surface = surface)
                } else {
                    ctx.readFiles += p
                    val body = res["text"] as? String ?: ""
                    val total = res["totalLines"] ?: 0
                    val trunc = if (res["truncated"] == true) "\n...(تم الاقتطاع، إجمالي الأسطر $total)" else ""
                    ToolOutcome(true, body + trunc, surface = surface)
                }
            }

            "write_file" -> {
                val p = resolvePath(ctx.workspacePath, args.optString("path"))
                val content = args.optString("content")
                val reason = args.optString("reason")
                val st = fs.stat(p)
                val exists = st["exists"] == true
                var diff: DiffStat? = null
                if (exists) {
                    if (p !in ctx.readFiles) {
                        return ToolOutcome(false, "حظر أمني: يجب استدعاء read_file لقراءة $p قبل استبداله.", surface = surface)
                    }
                    val oldText = runCatching { fs.readWholeText(p) }.getOrDefault("")
                    val lines = oldText.lines().size
                    if (lines > 200 && !reason.contains("[FULL_REWRITE]")) {
                        return ToolOutcome(
                            false,
                            "تم رفض إعادة الكتابة الكاملة: يحتوي الملف على $lines سطراً. استخدم edit / apply_patch للتعديل الجزئي، أو حدد reason=\"[FULL_REWRITE]\".",
                            surface = surface,
                        )
                    }
                    diff = GitService.computeDiffStats(oldText, content)
                } else {
                    diff = DiffStat(added = content.lines().size, removed = 0)
                }
                val res = fs.writeText(p, content)
                if (res["ok"] != true) ToolOutcome(false, res["error"]?.toString() ?: "فشلت الكتابة", surface = surface)
                else {
                    ctx.readFiles += p
                    ToolOutcome(true, "تمت كتابة $p (${content.length} chars)", diffStat = diff, surface = surface)
                }
            }

            "edit" -> {
                val p = resolvePath(ctx.workspacePath, args.optString("path"))
                if (p !in ctx.readFiles) {
                    return ToolOutcome(false, "يرجى استدعاء read_file لقراءة $p قبل التعديل.", surface = surface)
                }
                val oldText = args.optString("old_text")
                val newText = args.optString("new_text")
                if (oldText.isEmpty()) return ToolOutcome(false, "لا يمكن أن يكون old_text فارغاً", surface = surface)
                val full = runCatching { fs.readWholeText(p) }.getOrElse {
                    return ToolOutcome(false, "فشلت قراءة الملف: ${it.message}", surface = surface)
                }
                val first = full.indexOf(oldText)
                if (first < 0) return ToolOutcome(false, "لم يتم العثور على old_text مطابق في $p", surface = surface)
                if (full.indexOf(oldText, first + oldText.length) >= 0) {
                    return ToolOutcome(false, "ظهر old_text عدة مرات في $p؛ يرجى توفير سياق إضافي لضمان التطابق الفريد.", surface = surface)
                }
                val updated = full.replaceFirst(oldText, newText)
                val w = fs.writeText(p, updated)
                if (w["ok"] != true) ToolOutcome(false, w["error"]?.toString() ?: "فشلت الكتابة", surface = surface)
                else ToolOutcome(true, "تم التحديث الجزئي للملف $p", diffStat = GitService.computeDiffStats(full, updated), surface = surface)
            }

            "apply_patch" -> {
                val p = resolvePath(ctx.workspacePath, args.optString("path"))
                if (p !in ctx.readFiles) {
                    return ToolOutcome(false, "يرجى استدعاء read_file لقراءة $p قبل تطبيق الرقعة.", surface = surface)
                }
                val patch = args.optString("patch")
                val original = runCatching { fs.readWholeText(p) }.getOrElse {
                    return ToolOutcome(false, "فشلت القراءة: ${it.message}", surface = surface)
                }
                val patched = applyUnifiedHunk(original, patch)
                    ?: return ToolOutcome(false, "فشل تطبيق unified diff؛ يرجى التحقق من تطابق أسطر السياق مع $p.", surface = surface)
                val w = fs.writeText(p, patched)
                if (w["ok"] != true) ToolOutcome(false, w["error"]?.toString() ?: "فشلت الكتابة", surface = surface)
                else ToolOutcome(true, "تم تطبيق الرقعة على $p", diffStat = GitService.computeDiffStats(original, patched), surface = surface)
            }

            "mkdir" -> {
                val p = resolvePath(ctx.workspacePath, args.optString("path"))
                val r = fs.mkdir(p)
                ToolOutcome(r["ok"] == true, if (r["ok"] == true) "المجلد جاهز: $p" else (r["error"]?.toString() ?: "فشل الإنشاء"), surface = surface)
            }

            "find_files" -> {
                val p = resolvePath(ctx.workspacePath, args.optString("path").ifBlank { ctx.workspacePath })
                val pattern = args.optString("pattern").lowercase()
                val r = fs.list(p, recursive = true, maxEntries = 2000)
                if (r["ok"] != true) ToolOutcome(false, r["error"]?.toString() ?: "فشل البحث", surface = surface)
                else {
                    @Suppress("UNCHECKED_CAST")
                    val entries = r["entries"] as? List<Map<String, Any?>> ?: emptyList()
                    val matched = entries.mapNotNull { it["path"] as? String }
                        .filter { pattern.isEmpty() || it.lowercase().contains(pattern) }
                        .take(100)
                    ToolOutcome(true, matched.joinToString("\n").ifBlank { "لم يتم العثور على ملفات مطابقة" }, surface = surface)
                }
            }

            "grep_files" -> {
                val p = resolvePath(ctx.workspacePath, args.optString("path").ifBlank { ctx.workspacePath })
                val query = args.optString("query")
                if (query.isBlank()) return ToolOutcome(false, "لا يمكن أن يكون query فارغاً", surface = surface)
                val r = fs.list(p, recursive = true, maxEntries = 1000)
                if (r["ok"] != true) return ToolOutcome(false, r["error"]?.toString() ?: "فشل الفحص", surface = surface)
                @Suppress("UNCHECKED_CAST")
                val entries = r["entries"] as? List<Map<String, Any?>> ?: emptyList()
                val hits = mutableListOf<String>()
                for (e in entries) {
                    if (e["isDir"] == true) continue
                    val fp = e["path"] as? String ?: continue
                    val size = (e["size"] as? Number)?.toLong() ?: 0L
                    if (size > 512_000L) continue
                    val txt = runCatching { fs.readWholeText(fp) }.getOrNull() ?: continue
                    txt.lineSequence().forEachIndexed { idx, line ->
                        if (hits.size < 80 && line.contains(query, ignoreCase = true)) {
                            hits += "$fp:${idx + 1}: ${line.trim().take(160)}"
                        }
                    }
                    if (hits.size >= 80) break
                }
                ToolOutcome(true, hits.joinToString("\n").ifBlank { "لا توجد نتائج مطابقة" }, surface = surface)
            }

            "delete_path" -> {
                val p = resolvePath(ctx.workspacePath, args.optString("path"))
                val r = fs.delete(p, recursive = args.optBoolean("recursive", false))
                ToolOutcome(r["ok"] == true, if (r["ok"] == true) "تم الحذف: $p" else (r["error"]?.toString() ?: "فشل الحذف"), surface = surface)
            }

            "move_path" -> {
                val from = resolvePath(ctx.workspacePath, args.optString("from"))
                val to = resolvePath(ctx.workspacePath, args.optString("to"))
                val r = fs.rename(from, to)
                ToolOutcome(r["ok"] == true, if (r["ok"] == true) "تم النقل: $from -> $to" else (r["error"]?.toString() ?: "فشل النقل"), surface = surface)
            }

            "copy_path" -> {
                val from = resolvePath(ctx.workspacePath, args.optString("from"))
                val to = resolvePath(ctx.workspacePath, args.optString("to"))
                val r = fs.copy(from, to)
                ToolOutcome(r["ok"] == true, if (r["ok"] == true) "تم النسخ: $from -> $to" else (r["error"]?.toString() ?: "فشل النسخ"), surface = surface)
            }

            "read_document" -> {
                val p = resolvePath(ctx.workspacePath, args.optString("path"))
                val doc = documentReader.read(p)
                ToolOutcome(doc.ok, if (doc.ok) doc.text else (doc.error ?: "فشلت قراءة المستند"), surface = surface)
            }

            "run_shell_command" -> runShellCommand(args, ctx.workspacePath)

            "git_status" -> {
                val st = gitService.status(ctx.workspacePath)
                if (!st.isRepo) ToolOutcome(false, "لم يتم تهيئة مستودع Git في مساحة العمل الحالية", surface = "git")
                else {
                    val out = buildString {
                        appendLine("Branch: ${st.branch} (ahead ${st.ahead}, behind ${st.behind})")
                        st.changes.forEach { appendLine("${it.status} ${if (it.staged) "[S]" else "[U]"} ${it.path}") }
                        if (st.changes.isEmpty()) appendLine("Working tree clean")
                    }
                    ToolOutcome(true, out.trim(), surface = "git")
                }
            }

            "git_diff" -> {
                val path = args.optString("path").takeIf { it.isNotBlank() }
                val diffText = gitService.diff(ctx.workspacePath, path)
                ToolOutcome(true, diffText.ifBlank { "(لا توجد فروقات diff)" }, surface = "git")
            }

            "git_commit" -> {
                val msg = args.optString("message").trim()
                if (msg.isEmpty()) return ToolOutcome(false, "لا يمكن أن تكون رسالة الإيداع message فارغة", surface = "git")
                val stage = gitService.stageAll(ctx.workspacePath)
                if (stage.isFailure) return ToolOutcome(false, stage.exceptionOrNull()?.message ?: "فشل git add", surface = "git")
                val c = gitService.commit(ctx.workspacePath, msg)
                ToolOutcome(c.isSuccess, c.fold({ "تم الإيداع: $it ($msg)" }, { it.message ?: "فشل الإيداع" }), surface = "git")
            }

            "web_fetch" -> fetchWebPage(args.optString("url"))

            "web_search" -> {
                val q = args.optString("query").trim()
                if (q.isEmpty()) return ToolOutcome(false, "لا يمكن أن يكون query فارغاً", surface = "web")
                val r = searchRepo.search(q)
                r.fold(
                    onSuccess = { list ->
                        val text = list.mapIndexed { idx, item ->
                            "${idx + 1}. ${item.title}\n   ${item.url}\n   ${item.snippet}"
                        }.joinToString("\n\n").ifBlank { "لا توجد نتائج بحث" }
                        ToolOutcome(true, text, surface = "web")
                    },
                    onFailure = { ToolOutcome(false, it.message ?: "فشل البحث", surface = "web") },
                )
            }

            "check_device_state" -> {
                val snap = deviceHealth.read()
                if (!snap.ok) ToolOutcome(false, snap.error ?: "فشلت قراءة حالة الجهاز", surface = "device")
                else {
                    val info = "البطارية: ${snap.battery ?: "--"}% · الحرارة: ${snap.tempC?.let { "%.1f°C".format(it) } ?: "--"} · قيد الشحن: ${snap.charging ?: "--"}"
                    ToolOutcome(true, info, surface = "device")
                }
            }

            "notify_user" -> {
                sendLocalNotification(args.optString("title").ifBlank { "Heracles" }, args.optString("body"))
                ToolOutcome(true, "تم إرسال إشعار محلي", surface = "device")
            }

            "ask_user" -> {
                val prompt = args.optString("prompt").trim()
                val optsArr = args.optJSONArray("options") ?: JSONArray()
                val options = (0 until optsArr.length()).mapNotNull { i ->
                    optsArr.optString(i).takeIf { it.isNotBlank() }?.let { AskOption(it) }
                }
                val answer = ctx.onAskUser(
                    AskQuestion(
                        id = "ask_" + UUID.randomUUID().toString().take(8),
                        prompt = prompt,
                        options = options,
                    ),
                )
                ToolOutcome(true, "إجابة المستخدم: $answer", surface = "app")
            }

            "save_memory" -> {
                val scope = args.optString("scope")
                val kind = args.optString("kind").ifBlank { "other" }
                val text = args.optString("text")
                val res = scopedMemory.saveScoped(scope, ctx.workspacePath, ctx.sessionId, kind, text)
                res.fold(
                    onSuccess = { ToolOutcome(true, "تم الحفظ في ذاكرة $scope (id=${it.id})", surface = "app") },
                    onFailure = { ToolOutcome(false, it.message ?: "فشلت الكتابة", surface = "app") },
                )
            }

            "propose_global_memory" -> {
                val kind = args.optString("kind").ifBlank { "other" }
                val note = args.optString("note")
                val res = globalMemory.propose(note = note, kind = kind)
                if (res["ok"] == true) {
                    ToolOutcome(true, "تم إرسال مقترح الذاكرة طويلة المدى (id=${res["id"]}) بانتظار موافقة المستخدم في «الإعدادات ← إدارة الذاكرة».", surface = "app")
                } else {
                    ToolOutcome(false, res["error"]?.toString() ?: "فشل إرسال المقترح", surface = "app")
                }
            }

            "write_todos" -> {
                val arr = args.optJSONArray("steps") ?: JSONArray()
                val list = (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val title = o.optString("title").trim()
                    if (title.isEmpty()) return@mapNotNull null
                    PlanStep(
                        id = o.optString("id").ifBlank { "step_${i + 1}" },
                        title = title,
                        status = o.optString("status").ifBlank { "pending" },
                    )
                }
                ctx.onPlanUpdated(list)
                ToolOutcome(true, "تم تحديث قائمة المهام (${list.size} خطوات)", surface = "app")
            }

            else -> ToolOutcome(false, "أداة غير معروفة: $name", surface = "app")
        }
    }

    private suspend fun runShellCommand(args: JSONObject, workspacePath: String): ToolOutcome {
        val cmd = args.optString("command").trim()
        if (cmd.isEmpty()) return ToolOutcome(false, "لا يمكن أن يكون command فارغاً", surface = "sandbox")
        val forbidden = listOf("kill ", "killall ", "force-stop", "am force-stop", "pm disable")
        if (forbidden.any { cmd.contains(it, ignoreCase = true) }) {
            return ToolOutcome(false, "حظر أمني: يُمنع إنهاء عمليات النظام أو التطبيق المضيف عبر أداة Shell.", surface = "device")
        }
        val cwd = args.optString("cwd").takeIf { it.isNotBlank() } ?: workspacePath
        val timeout = args.optLong("timeout_ms", 120_000L).coerceIn(1_000L, 600_000L)

        if (isDeviceSystemCommand(cmd)) {
            val res = deviceShell.run(cmd, cwd = cwd, timeoutMs = timeout)
            val combined = buildString {
                if (res.stdout.isNotBlank()) appendLine(res.stdout.trimEnd())
                if (res.stderr.isNotBlank()) appendLine("[stderr]\n${res.stderr.trimEnd()}")
                if (res.error != null) appendLine("[error] ${res.error}")
            }.trim()
            return ToolOutcome(res.ok, combined.ifBlank { "(exit=${res.code})" }, surface = "device")
        }

        val st = sandbox.status()
        if (!st.ready) {
            return ToolOutcome(
                false,
                "بيئة Linux المعزولة غير مفعّلة بعد (rootfs/PRoot غير جاهز). يرجى الانتقال إلى «الإعدادات ← البيئة المعزولة» لتفعيل نظام الملفات الجذري قبل تشغيل هذا الأمر.",
                surface = "sandbox",
            )
        }
        val execRes = sandbox.execute(command = cmd, workdir = "/workspace", timeoutMs = timeout)
        val combined = buildString {
            if (execRes.stdout.isNotBlank()) appendLine(execRes.stdout.trimEnd())
            if (execRes.stderr.isNotBlank()) appendLine("[stderr]\n${execRes.stderr.trimEnd()}")
        }.trim()
        return ToolOutcome(
            execRes.exitCode == 0,
            combined.ifBlank { "(exit=${execRes.exitCode})" },
            surface = "sandbox",
        )
    }

    private suspend fun fetchWebPage(url: String): ToolOutcome = withContext(Dispatchers.IO) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("https://") && !trimmed.startsWith("http://")) {
            return@withContext ToolOutcome(false, "يجب أن يبدأ الرابط بـ http:// أو https://", surface = "web")
        }
        runCatching {
            val req = Request.Builder().url(trimmed).header("User-Agent", "Heracles-Android/0.5").get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val raw = resp.body?.string().orEmpty()
                val text = raw
                    .replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
                    .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
                    .replace(Regex("<[^>]+>"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(24_000)
                ToolOutcome(
                    true,
                    "[UNTRUSTED_WEB_CONTENT]\n$text\n[/UNTRUSTED_WEB_CONTENT]",
                    surface = "web",
                )
            }
        }.getOrElse {
            ToolOutcome(false, "فشل جلب صفحة الويب: ${it.message}", surface = "web")
        }
    }

    private fun sendLocalNotification(title: String, body: String) {
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val chId = "heracles_agent_alerts"
            if (nm.getNotificationChannel(chId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(chId, "تنبيهات الوكيل", NotificationManager.IMPORTANCE_DEFAULT),
                )
            }
            val n = android.app.Notification.Builder(context, chId)
                .setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .build()
            nm.notify((System.currentTimeMillis() and 0x7FFF).toInt() + 3000, n)
        }
    }

    private fun resolvePath(workspacePath: String, raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return workspacePath
        if (s.startsWith("/")) return s
        return workspacePath.trimEnd('/') + "/" + s.removePrefix("./")
    }

    private fun isDeviceSystemCommand(cmd: String): Boolean {
        val first = cmd.trim().substringBefore(' ').substringAfterLast('/')
        return first in setOf("pm", "am", "dumpsys", "settings", "screencap", "input", "cmd", "getprop", "logcat")
    }

    private fun applyUnifiedHunk(original: String, patch: String): String? {
        val origLines = original.split('\n').toMutableList()
        val patchLines = patch.lines()
        val hunks = mutableListOf<Pair<Int, List<String>>>()
        var idx = 0
        while (idx < patchLines.size) {
            val l = patchLines[idx]
            if (l.startsWith("@@")) {
                val m = Regex("@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@").find(l) ?: return null
                val startLine = (m.groupValues[1].toIntOrNull() ?: 1).coerceAtLeast(1)
                idx++
                val body = mutableListOf<String>()
                while (idx < patchLines.size && !patchLines[idx].startsWith("@@")) {
                    val pl = patchLines[idx]
                    if (pl.startsWith(" ") || pl.startsWith("+") || pl.startsWith("-")) body += pl
                    idx++
                }
                hunks += startLine to body
            } else {
                idx++
            }
        }
        if (hunks.isEmpty()) return null
        var lineOffset = 0
        for ((start1, body) in hunks) {
            var pos = (start1 - 1 + lineOffset).coerceIn(0, origLines.size)
            for (b in body) {
                when {
                    b.startsWith(" ") -> pos++
                    b.startsWith("-") -> {
                        val expected = b.substring(1)
                        if (pos >= origLines.size || origLines[pos] != expected) return null
                        origLines.removeAt(pos)
                        lineOffset--
                    }
                    b.startsWith("+") -> {
                        origLines.add(pos, b.substring(1))
                        pos++
                        lineOffset++
                    }
                }
            }
        }
        return origLines.joinToString("\n")
    }

    private fun schema(
        name: String,
        desc: String,
        props: Map<String, String>,
        required: List<String>,
    ): LlmClient.ToolSchema {
        val pObj = JSONObject()
        props.forEach { (k, t) ->
            val propObj = when (t) {
                "array_string" -> JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))
                "array_step" -> JSONObject().put("type", "array").put(
                    "items",
                    JSONObject().put("type", "object").put(
                        "properties",
                        JSONObject()
                            .put("id", JSONObject().put("type", "string"))
                            .put("title", JSONObject().put("type", "string"))
                            .put("status", JSONObject().put("type", "string")),
                    ),
                )
                else -> JSONObject().put("type", t)
            }
            pObj.put(k, propObj)
        }
        val params = JSONObject()
            .put("type", "object")
            .put("properties", pObj)
            .put("required", JSONArray(required))
        return LlmClient.ToolSchema(name, desc, params)
    }

    companion object {
        private val READ_ONLY_TOOLS = setOf(
            "list_files", "read_file", "find_files", "grep_files",
            "read_document", "git_status", "git_diff", "web_fetch",
            "web_search", "check_device_state", "write_todos",
        )
    }
}
