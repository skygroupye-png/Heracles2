package com.theohowie.heracles.bridge

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class SkillsBridge(private val context: Context) {

    data class RawSkillFile(val path: String, val raw: String)

    data class ListResult(
        val entries: List<RawSkillFile>,
        val enabled: Map<String, Boolean>,
        val errors: List<String>,
    )

    data class InstallResult(
        val ok: Boolean,
        val installed: List<RawSkillFile>,
        val errors: List<String>,
    )

    private companion object {
        const val MAX_FILE_BYTES = 512 * 1024
        const val MAX_DEPTH = 4
        const val PREFS = "heracles_skills"
        const val KEY_ENABLED = "enabled"
        const val SKILLS_DIR = ".heracles/skills"
        const val DOWNLOAD_TIMEOUT_MS = 15_000
    }

    fun list(workspace: String): ListResult {
        val root = skillsRoot(workspace)
            ?: return ListResult(emptyList(), enabledMap(), listOf("لا يوجد مسار مساحة عمل متاح: $workspace"))
        val entries = mutableListOf<RawSkillFile>()
        val errors = mutableListOf<String>()
        if (root.isDirectory) {
            collect(root, 1, entries, errors)
        }
        return ListResult(entries, enabledMap(), errors)
    }

    private fun collect(
        dir: File,
        depth: Int,
        out: MutableList<RawSkillFile>,
        errors: MutableList<String>,
    ) {
        if (depth > MAX_DEPTH) {
            errors += "${dir.absolutePath}: تجاوز عمق المجلد $MAX_DEPTH مستويات، تم التخطي"
            return
        }
        val children = dir.listFiles() ?: return
        for (f in children.sortedBy { it.name }) {
            if (f.isDirectory) {
                if (!f.name.startsWith(".")) collect(f, depth + 1, out, errors)
                continue
            }
            if (f.name != "SKILL.md") continue
            if (f.length() > MAX_FILE_BYTES) {
                errors += "${f.absolutePath}: يتجاوز حجم الملف ${MAX_FILE_BYTES / 1024} KB، تم التخطي"
                continue
            }
            val raw = runCatching { f.readText() }.getOrElse {
                errors += "${f.absolutePath}: فشلت القراءة (${it.javaClass.simpleName})"
                null
            } ?: continue
            out += RawSkillFile(f.absolutePath, raw)
        }
    }

    fun setEnabled(name: String, enabled: Boolean): Map<String, Boolean> {
        if (name.isBlank()) return enabledMap()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val all = runCatching { JSONObject(prefs.getString(KEY_ENABLED, "{}") ?: "{}") }
            .getOrElse { JSONObject() }
        all.put(name, enabled)
        prefs.edit().putString(KEY_ENABLED, all.toString()).apply()
        return enabledMap()
    }

    fun enabledMap(): Map<String, Boolean> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ENABLED, "{}") ?: "{}"
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val out = mutableMapOf<String, Boolean>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = obj.optBoolean(k, true)
        }
        return out
    }

    fun install(workspace: String, source: String): InstallResult {
        val root = skillsRoot(workspace)
            ?: return InstallResult(false, emptyList(), listOf("مسار مساحة العمل غير قابل للكتابة: $workspace"))
        val src = source.trim()
        if (src.isEmpty()) return InstallResult(false, emptyList(), listOf("مصدر التثبيت لا يمكن أن يكون فارغاً"))

        val installed = mutableListOf<RawSkillFile>()
        val errors = mutableListOf<String>()

        when {
            src.startsWith("https://") -> {
                val r = download(src)
                if (r.error != null) {
                    errors += "$src: ${r.error}"
                } else {
                    val path = writeSkill(root, slugFor(urlFileName(src), r.text!!), r.text)
                    if (path == null) errors += "$src: فشل الكتابة في مجلد المهارات"
                    else installed += RawSkillFile(path, r.text)
                }
            }

            src.startsWith("/") -> {
                val f = File(src)
                when {
                    !f.exists() -> errors += "المصدر غير موجود: $src"
                    f.isFile -> {
                        if (f.length() > MAX_FILE_BYTES) {
                            errors += "يتجاوز الملف ${MAX_FILE_BYTES / 1024} KB: $src"
                        } else {
                            val text = runCatching { f.readText() }.getOrNull()
                            if (text == null) {
                                errors += "فشلت القراءة: $src"
                            } else {
                                val path = writeSkill(root, slugFor(f.parentFile?.name ?: "skill", text), text)
                                if (path == null) errors += "فشل الكتابة في مجلد المهارات"
                                else installed += RawSkillFile(path, text)
                            }
                        }
                    }
                    else -> {
                        val found = mutableListOf<RawSkillFile>()
                        collect(f, 1, found, errors)
                        if (found.isEmpty() && errors.isEmpty()) {
                            errors += "$src: لم يتم العثور على SKILL.md داخل المجلد"
                        }
                        for (item in found) {
                            val slug = slugFor(File(item.path).parentFile?.name ?: "skill", item.raw)
                            val path = writeSkill(root, slug, item.raw)
                            if (path == null) errors += "${item.path}: فشلت الكتابة"
                            else installed += RawSkillFile(path, item.raw)
                        }
                    }
                }
            }

            else -> errors += "يجب أن يكون المصدر رابط https:// أو مساراً مطلقاً ($src)"
        }

        return InstallResult(installed.isNotEmpty(), installed, errors)
    }

    fun remove(workspace: String, name: String, path: String?): Boolean {
        val root = skillsRoot(workspace) ?: return false
        val target: File = if (!path.isNullOrBlank()) {
            val f = File(path)
            if (!f.absolutePath.startsWith(root.absolutePath + File.separator)) return false
            f.parentFile ?: return false
        } else {
            if (name.isBlank()) return false
            findByDeclaredName(root, name) ?: return false
        }
        return target.exists() && target.deleteRecursively()
    }

    private fun findByDeclaredName(dir: File, name: String): File? {
        val children = dir.listFiles() ?: return null
        for (f in children) {
            if (f.isDirectory) {
                findByDeclaredName(f, name)?.let { return it }
                continue
            }
            if (f.name != "SKILL.md") continue
            val declared = runCatching {
                f.readLines().take(40)
                    .firstOrNull { it.trim().lowercase().startsWith("name:") }
                    ?.substringAfter(':')?.trim()
            }.getOrNull()
            if (declared == name) return f.parentFile
        }
        return null
    }

    private fun skillsRoot(workspace: String): File? {
        if (workspace.isBlank()) return null
        val ws = File(workspace)
        if (!ws.isDirectory) return null
        val root = File(ws, SKILLS_DIR)
        if (!root.exists() && !root.mkdirs()) return null
        return root
    }

    private fun writeSkill(root: File, slug: String, text: String): String? = runCatching {
        val dir = File(root, slug)
        if (!dir.exists() && !dir.mkdirs()) return@runCatching null
        val out = File(dir, "SKILL.md")
        FileOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        out.absolutePath
    }.getOrNull()

    private fun slugFor(raw: String, content: String): String {
        val s = raw.lowercase()
            .map { if (it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_') it else '-' }
            .joinToString("")
            .trim('-')
            .take(48)
        return if (s.isNotEmpty()) s else "skill-" + content.hashCode().toUInt().toString(16)
    }

    private fun urlFileName(url: String): String =
        url.substringBefore('?').substringAfterLast('/').ifBlank { "skill" }

    private data class Download(val text: String?, val error: String?)

    private fun download(url: String): Download {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = DOWNLOAD_TIMEOUT_MS
                readTimeout = DOWNLOAD_TIMEOUT_MS
                instanceFollowRedirects = false
                requestMethod = "GET"
            }
            val code = conn.responseCode
            if (code != 200) return Download(null, "HTTP $code")
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.size > MAX_FILE_BYTES) return Download(null, "يتجاوز الملف ${MAX_FILE_BYTES / 1024} KB")
            Download(String(bytes, Charsets.UTF_8), null)
        } catch (e: Exception) {
            Download(null, "فشل التنزيل (${e.javaClass.simpleName})")
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
