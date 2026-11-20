package com.theohowie.heracles.bridge

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile

class DeviceFs(
    private val context: Context,
    private val registry: WorkspaceRegistry,
) {
    companion object {
        const val MAX_TEXT_BYTES: Long = 1_000_000
        const val MAX_BINARY_BYTES: Long = 64L * 1024 * 1024
        const val SAF_APPEND_TIMEOUT_MS: Long = 1_500L
        const val DEFAULT_MAX_ENTRIES: Int = 500
        const val HARD_MAX_ENTRIES: Int = 5_000
        const val MAX_DEPTH: Int = 32
        const val HARD_MAX_TAIL_LINES: Int = 20_000
        const val DEFAULT_TAIL_LINES: Int = 400
    }

    data class FsEntry(
        val path: String,
        val name: String,
        val isDir: Boolean,
        val size: Long,
    )

    private fun fail(reason: String): Map<String, Any?> = mapOf("ok" to false, "error" to reason)

    private fun ok(vararg pairs: Pair<String, Any?>): Map<String, Any?> =
        mutableMapOf<String, Any?>("ok" to true).apply { putAll(pairs) }

    private suspend fun guard(
        io: CoroutineDispatcher = Dispatchers.IO,
        block: suspend () -> Map<String, Any?>,
    ): Map<String, Any?> = withContext(io) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            fail(e.message ?: "معامل غير صالح")
        } catch (e: SecurityException) {
            fail("لا توجد صلاحية للوصول إلى هذا الموقع (${e.message ?: "تم رفض الصلاحية"})")
        } catch (e: java.io.FileNotFoundException) {
            fail("تعذر العثور على الملف أو المجلد (${e.message ?: "غير موجود"})")
        } catch (e: Throwable) {
            fail("فشلت عملية الملف: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    suspend fun stat(path: String): Map<String, Any?> = guard {
        val p = normalize(path) ?: throw IllegalArgumentException("المسار فارغ")
        val saf = registry.matchSaf(p)
        if (saf != null) {
            val doc = resolveSaf(saf.first, saf.second)
                ?: return@guard ok("exists" to false, "isDir" to false, "size" to 0L, "mtimeMs" to 0L)
            return@guard ok(
                "exists" to true,
                "isDir" to doc.isDirectory,
                "size" to if (doc.isDirectory) 0L else doc.length(),
                "mtimeMs" to doc.lastModified(),
            )
        }
        val f = File(p)
        if (!f.exists()) {
            return@guard ok("exists" to false, "isDir" to false, "size" to 0L, "mtimeMs" to 0L)
        }
        ok(
            "exists" to true,
            "isDir" to f.isDirectory,
            "size" to if (f.isDirectory) 0L else f.length(),
            "mtimeMs" to f.lastModified(),
        )
    }

    suspend fun list(
        path: String,
        recursive: Boolean = false,
        maxEntries: Int = DEFAULT_MAX_ENTRIES,
    ): Map<String, Any?> = guard {
        val p = normalize(path) ?: throw IllegalArgumentException("المسار فارغ")
        val limit = maxEntries.coerceIn(1, HARD_MAX_ENTRIES)
        val entries = mutableListOf<Map<String, Any?>>()
        var truncated = false

        val saf = registry.matchSaf(p)
        if (saf != null) {
            val root = resolveSaf(saf.first, saf.second) ?: return@guard fail("المجلد غير موجود: $p")
            if (!root.isDirectory) return@guard fail("ليس مجلداً: $p")

            fun walk(dir: DocumentFile, base: String, depth: Int) {
                if (entries.size >= limit) {
                    truncated = true
                    return
                }
                val children = runCatching { dir.listFiles() }.getOrNull() ?: return
                val sorted = children.sortedWith(
                    compareBy({ !it.isDirectory }, { it.name?.lowercase() ?: "" }),
                )
                for (c in sorted) {
                    if (entries.size >= limit) {
                        truncated = true
                        return
                    }
                    val name = c.name ?: continue
                    val childPath = if (base.isEmpty()) "$p/$name" else "$base/$name"
                    entries += mapOf(
                        "path" to childPath,
                        "name" to name,
                        "isDir" to c.isDirectory,
                        "size" to if (c.isDirectory) 0L else c.length(),
                    )
                    if (recursive && c.isDirectory && depth < MAX_DEPTH) {
                        walk(c, childPath, depth + 1)
                    }
                }
            }
            walk(root, p.trimEnd('/'), 0)
            return@guard ok("entries" to entries, "truncated" to truncated)
        }

        val dir = File(p)
        if (!dir.isDirectory) return@guard fail("المجلد غير موجود أو ليس مجلداً: $p")

        fun walk(d: File, depth: Int) {
            if (entries.size >= limit) {
                truncated = true
                return
            }
            val children = d.listFiles() ?: return
            val sorted = children.sortedWith(
                compareBy({ !it.isDirectory }, { it.name.lowercase() }),
            )
            for (c in sorted) {
                if (entries.size >= limit) {
                    truncated = true
                    return
                }
                entries += mapOf(
                    "path" to c.absolutePath,
                    "name" to c.name,
                    "isDir" to c.isDirectory,
                    "size" to if (c.isDirectory) 0L else c.length(),
                )
                if (recursive && c.isDirectory && depth < MAX_DEPTH) walk(c, depth + 1)
            }
        }
        walk(dir, 0)
        ok("entries" to entries, "truncated" to truncated)
    }

    suspend fun readText(path: String, offsetLine: Int = 1, maxLines: Int = 0): Map<String, Any?> = guard {
        val p = normalize(path) ?: throw IllegalArgumentException("المسار فارغ")
        if (offsetLine < 1) throw IllegalArgumentException("يجب أن يبدأ offsetLine من 1")
        val text = readTextInternal(p)
        if (text.length.toLong() > MAX_TEXT_BYTES) {
            val cut = text.substring(0, MAX_TEXT_BYTES.toInt())
            return@guard slice(cut, offsetLine, maxLines, forcedTruncated = true)
        }
        slice(text, offsetLine, maxLines, forcedTruncated = false)
    }

    private fun slice(
        text: String,
        offsetLine: Int,
        maxLines: Int,
        forcedTruncated: Boolean,
    ): Map<String, Any?> {
        val lines = text.split('\n')
        val from = (offsetLine - 1).coerceIn(0, lines.size)
        val to = if (maxLines <= 0) lines.size else (from + maxLines).coerceAtMost(lines.size)
        val body = lines.subList(from, to).joinToString("\n")
        val truncated = forcedTruncated || to < lines.size
        return ok("text" to body, "truncated" to truncated, "totalLines" to lines.size)
    }

    private fun readTextInternal(path: String): String {
        val saf = registry.matchSaf(path)
        if (saf != null) {
            val doc = resolveSaf(saf.first, saf.second)
                ?: throw IllegalArgumentException("الملف غير موجود: $path")
            if (doc.isDirectory) throw IllegalArgumentException("ليس ملفاً: $path")
            val ins = context.contentResolver.openInputStream(doc.uri)
                ?: throw IllegalStateException("تعذر الفتح: $path")
            return ins.use { it.readBytes().toString(Charsets.UTF_8) }
        }
        val f = File(path)
        if (!f.exists()) throw IllegalArgumentException("الملف غير موجود: $path")
        if (f.isDirectory) throw IllegalArgumentException("ليس ملفاً: $path")
        return f.readText(Charsets.UTF_8)
    }

    suspend fun readWholeText(path: String): String = withContext(Dispatchers.IO) {
        readTextInternal(normalize(path) ?: throw IllegalArgumentException("المسار فارغ"))
    }

    suspend fun readTailLines(
        path: String,
        maxLines: Int,
        defaultLines: Int = DEFAULT_TAIL_LINES,
    ): Map<String, Any?> = guard {
        val p = normalize(path) ?: throw IllegalArgumentException("المسار فارغ")
        val want = (if (maxLines <= 0) defaultLines else maxLines).coerceIn(1, HARD_MAX_TAIL_LINES)

        val saf = registry.matchSaf(p)
        if (saf != null) {
            val doc = resolveSaf(saf.first, saf.second)
                ?: throw IllegalArgumentException("الملف غير موجود: $p")
            if (doc.isDirectory) throw IllegalArgumentException("ليس ملفاً: $p")
            val ins = context.contentResolver.openInputStream(doc.uri)
                ?: throw IllegalStateException("تعذر الفتح: $p")
            val all = ins.use { streamTailLines(it, want) }
            return@guard ok(
                "text" to all.joinToString("\n"),
                "truncated" to (all.size >= want),
                "route" to "saf",
            )
        }

        val f = File(p)
        if (!f.exists()) throw IllegalArgumentException("الملف غير موجود: $p")
        if (f.isDirectory) throw IllegalArgumentException("ليس ملفاً: $p")
        if (f.length() == 0L) {
            return@guard ok("text" to "", "truncated" to false, "route" to "posix")
        }

        val tail = readTailFromRandomAccess(f, want)
        ok(
            "text" to tail.joinToString("\n"),
            "truncated" to (tail.size >= want),
            "route" to "posix",
        )
    }

    private fun readTailFromRandomAccess(file: File, want: Int): List<String> {
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            if (len == 0L) return emptyList()

            var pos = len
            raf.seek(len - 1)
            if (raf.read() == '\n'.code) pos = len - 1

            val chunks = ArrayDeque<String>()
            val buf = ByteArray(8 * 1024)
            var newlines = 0
            var readTo = pos

            while (readTo > 0) {
                val chunkLen = minOf(buf.size.toLong(), readTo).toInt()
                val start = readTo - chunkLen
                raf.seek(start)
                raf.readFully(buf, 0, chunkLen)

                var i = chunkLen - 1
                var cut = chunkLen
                while (i >= 0) {
                    if (buf[i] == '\n'.code.toByte()) {
                        newlines++
                        if (newlines > want) {
                            val piece = String(buf, i + 1, cut - (i + 1), Charsets.UTF_8)
                            if (piece.isNotEmpty()) chunks.addFirst(piece)
                            return chunks.toList()
                        }
                        cut = i
                    }
                    i--
                }
                val piece = String(buf, 0, cut, Charsets.UTF_8)
                if (piece.isNotEmpty()) chunks.addFirst(piece)
                readTo = start
            }
            return chunks.toList()
        }
    }

    private fun streamTailLines(input: InputStream, want: Int): List<String> {
        val ring = ArrayDeque<String>()
        input.bufferedReader(Charsets.UTF_8).use { r ->
            while (true) {
                val line = r.readLine() ?: break
                ring.addLast(line)
                if (ring.size > want) ring.removeFirst()
            }
        }
        return ring.toList()
    }

    suspend fun readBytes(path: String): Map<String, Any?> = guard {
        val p = normalize(path) ?: throw IllegalArgumentException("المسار فارغ")
        val saf = registry.matchSaf(p)
        if (saf != null) {
            val doc = resolveSaf(saf.first, saf.second)
                ?: throw IllegalArgumentException("الملف غير موجود: $p")
            if (doc.isDirectory) throw IllegalArgumentException("ليس ملفاً: $p")
            val ins = context.contentResolver.openInputStream(doc.uri)
                ?: throw IllegalStateException("تعذر الفتح: $p")
            val bytes = ins.use { readLimited(it, MAX_BINARY_BYTES) }
            return@guard ok("bytes" to bytes)
        }
        val f = File(p)
        if (!f.exists()) throw IllegalArgumentException("الملف غير موجود: $p")
        if (f.isDirectory) throw IllegalArgumentException("ليس ملفاً: $p")
        if (f.length() > MAX_BINARY_BYTES) {
            throw IllegalArgumentException(
                "حجم الملف كبير جداً (${f.length()}B > ${MAX_BINARY_BYTES}B): $p — القناة الثنائية لا تقتطع",
            )
        }
        ok("bytes" to f.readBytes())
    }

    private fun readLimited(input: InputStream, max: Long): ByteArray {
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            total += n
            if (total > max) {
                throw IllegalArgumentException("المحتوى كبير جداً (يتجاوز ${max}B) — القناة الثنائية لا تقتطع")
            }
            buf.write(chunk, 0, n)
        }
        return buf.toByteArray()
    }

    suspend fun writeText(path: String, text: String): Map<String, Any?> =
        writeBytes(path, text.toByteArray(Charsets.UTF_8))

    suspend fun appendText(path: String, text: String): Map<String, Any?> = guard {
        val p = normalize(path) ?: throw IllegalArgumentException("المسار فارغ")
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size.toLong() > MAX_BINARY_BYTES) {
            throw IllegalArgumentException("حجم الإلحاق كبير جداً (${bytes.size}B): $p")
        }

        val saf = registry.matchSaf(p)
        if (saf != null) {
            val (entry, rel) = saf
            val fileName = rel.substringAfterLast('/')
            if (fileName.isEmpty()) throw IllegalArgumentException("المسار يشير إلى جذر مساحة العمل، يتعذر الإلحاق: $p")
            val parentRel = rel.substringBeforeLast('/', "")
            val parent = resolveSaf(entry, parentRel)
                ?: throw IllegalArgumentException("المجلد الأب غير موجود: $p")
            val existing = findChild(parent, fileName)
                ?: throw IllegalArgumentException("الملف غير موجود: $p (الإلحاق لا ينشئ ملفاً جديداً)")
            val wrote = withTimeoutOrNull(SAF_APPEND_TIMEOUT_MS) {
                val out = context.contentResolver.openOutputStream(existing.uri, "wa")
                    ?: throw IllegalStateException("هذا الموقع لا يدعم الكتابة بالإلحاق: $p")
                out.use {
                    it.write(bytes)
                    it.flush()
                }
                true
            }
            if (wrote != true) {
                throw IllegalStateException("هذا الموقع لا يدعم الكتابة بالإلحاق (انتهت مهلة المزوّد): $p")
            }
            return@guard ok("mode" to "append")
        }

        val f = File(p)
        if (f.isDirectory) throw IllegalArgumentException("ليس ملفاً: $p")
        if (!f.exists()) {
            val parent = f.parentFile
            if (parent == null || !parent.isDirectory) {
                throw IllegalArgumentException("المجلد الأب غير موجود: $p (الإلحاق لا ينشئ مجلدات)")
            }
            if (!f.createNewFile()) {
                throw IllegalStateException("تعذر إنشاء الملف: $p")
            }
        }
        FileOutputStream(f, true).use { out ->
            out.write(bytes)
            out.flush()
        }
        ok("mode" to "append")
    }

    suspend fun writeBytes(path: String, bytes: ByteArray): Map<String, Any?> = guard {
        val p = normalize(path) ?: throw IllegalArgumentException("المسار فارغ")
        if (bytes.size.toLong() > MAX_BINARY_BYTES) {
            throw IllegalArgumentException("حجم الكتابة كبير جداً (${bytes.size}B > ${MAX_BINARY_BYTES}B): $p")
        }
        val saf = registry.matchSaf(p)
        if (saf != null) {
            val (entry, rel) = saf
            val fileName = rel.substringAfterLast('/')
            if (fileName.isEmpty()) throw IllegalArgumentException("المسار يشير إلى جذر مساحة العمل، تتعذر الكتابة: $p")
            val parentRel = rel.substringBeforeLast('/', "")
            val parent = resolveOrCreateSaf(entry, parentRel)
                ?: throw IllegalStateException("تعذر إنشاء المجلد: $parentRel")
            writeSafFile(parent, fileName, bytes)
            return@guard ok()
        }
        val f = File(p)
        f.parentFile?.let { parent ->
            if (!parent.isDirectory && !parent.mkdirs()) {
                throw IllegalStateException("تعذر إنشاء المجلد الأب: ${parent.absolutePath}")
            }
        }
        f.writeBytes(bytes)
        ok()
    }

    private fun writeSafFile(parent: DocumentFile, fileName: String, bytes: ByteArray) {
        findChild(parent, fileName)?.let { existing ->
            if (!existing.delete()) {
                runCatching {
                    context.contentResolver.openOutputStream(existing.uri, "wt")?.use { out ->
                        out.write(bytes)
                        out.flush()
                    } ?: throw IllegalStateException("تعذر فتح تدفق الإخراج")
                }.getOrElse {
                    throw IllegalStateException(
                        "تعذر استبدال الملف الموجود أو إعادة كتابته في مكانه: $fileName (${it.javaClass.simpleName}: ${it.message})",
                    )
                }
                return
            }
        }

        val created = parent.createFile(mimeOf(fileName), fileName)
            ?: throw IllegalStateException("تعذر إنشاء الملف: $fileName (قد لا يكون المجلد الأب قابلاً للكتابة)")

        if (created.name != fileName) {
            val wrong = created.name
            created.delete()
            val retry = parent.createFile("application/octet-stream", fileName)
            if (retry == null || retry.name != fileName) {
                retry?.delete()
                throw IllegalStateException(
                    "قام نظام الملفات بتعديل اسم الملف: المطلوب $fileName، الفعلي $wrong. لا يدعم هذا الموقع التسمية كما هي.",
                )
            }
            writeToDoc(retry, bytes, fileName)
            return
        }
        writeToDoc(created, bytes, fileName)
    }

    private fun writeToDoc(doc: DocumentFile, bytes: ByteArray, name: String) {
        context.contentResolver.openOutputStream(doc.uri, "wt")?.use { out ->
            out.write(bytes)
            out.flush()
        } ?: throw IllegalStateException("تعذرت الكتابة: $name")
    }

    suspend fun mkdir(path: String): Map<String, Any?> = guard {
        val p = normalize(path) ?: throw IllegalArgumentException("المسار فارغ")
        val saf = registry.matchSaf(p)
        if (saf != null) {
            resolveOrCreateSaf(saf.first, saf.second)
                ?: throw IllegalStateException("تعذر إنشاء المجلد: $p (قد لا يشمل تفويض SAF صلاحية الكتابة)")
            return@guard ok()
        }
        val f = File(p)
        if (f.isDirectory) return@guard ok()
        if (!f.mkdirs() && !f.isDirectory) {
            throw IllegalStateException("تعذر إنشاء المجلد: $p")
        }
        ok()
    }

    suspend fun delete(path: String, recursive: Boolean): Map<String, Any?> = guard {
        val p = normalize(path) ?: throw IllegalArgumentException("المسار فارغ")
        val saf = registry.matchSaf(p)
        if (saf != null) {
            val doc = resolveSaf(saf.first, saf.second) ?: return@guard ok("existed" to false)
            if (doc.isDirectory) {
                if (!recursive) {
                    return@guard fail(
                        "تم رفض حذف المجلد «$p»: يتطلب recursive=true. الحذف في مساحة عمل SAF يكون متداخلاً ويجب تأكيده صراحةً.",
                    )
                }
                if (countSaf(doc, HARD_MAX_ENTRIES) > HARD_MAX_ENTRIES) {
                    return@guard fail("يحتوي المجلد على عناصر كثيرة جداً (أكثر من $HARD_MAX_ENTRIES)، تم رفض الحذف.")
                }
                val okDeleted = runCatching { doc.delete() }.getOrDefault(false)
                if (!okDeleted) return@guard fail("تعذر حذف المجلد: $p")
                return@guard ok()
            }
            val okFile = runCatching { doc.delete() }.getOrDefault(false)
            if (!okFile) return@guard fail("تعذر حذف الملف: $p")
            return@guard ok()
        }

        val f = File(p)
        if (!f.exists()) return@guard ok("existed" to false)
        if (f.isDirectory && !recursive) {
            return@guard fail("«$p» هو مجلد، يتطلب حذفه recursive=true")
        }
        val deleted = if (f.isDirectory) f.deleteRecursively() else f.delete()
        if (!deleted && f.exists()) throw IllegalStateException("تعذر الحذف: $p")
        ok()
    }

    private fun countSaf(dir: DocumentFile, cap: Int): Int {
        var n = 0
        fun walk(d: DocumentFile, depth: Int) {
            if (n > cap) return
            val children = runCatching { d.listFiles() }.getOrNull() ?: return
            for (c in children) {
                n++
                if (n > cap) return
                if (c.isDirectory && depth < MAX_DEPTH) walk(c, depth + 1)
            }
        }
        walk(dir, 0)
        return n
    }

    suspend fun rename(from: String, to: String): Map<String, Any?> = guard {
        val src = normalize(from) ?: throw IllegalArgumentException("from فارغ")
        val dst = normalize(to) ?: throw IllegalArgumentException("to فارغ")

        val srcSaf = registry.matchSaf(src)
        val dstSaf = registry.matchSaf(dst)

        if (srcSaf == null && dstSaf == null) {
            val a = File(src)
            if (!a.exists()) throw IllegalArgumentException("المصدر غير موجود: $src")
            val b = File(dst)
            b.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IllegalStateException("تعذر إنشاء المجلد الأب") }
            if (b.exists()) throw IllegalStateException("الوجهة موجودة مسبقاً: $dst")
            if (!a.renameTo(b)) throw IllegalStateException("فشلت إعادة التسمية: $src -> $dst")
            return@guard ok()
        }

        if (srcSaf != null) {
            val doc = resolveSaf(srcSaf.first, srcSaf.second)
                ?: throw IllegalArgumentException("المصدر غير موجود: $src")
            if (doc.isDirectory) {
                return@guard fail(
                    "لا تدعم مساحة عمل SAF إعادة تسمية المجلدات: «$src». يمكنك نسخ الملفات إلى مجلد جديد ثم حذف المجلد القديم.",
                )
            }
        }
        val bytes = (readBytes(src)["bytes"] as? ByteArray)
            ?: throw IllegalStateException("فشلت قراءة الملف المصدر: $src")
        val w = writeBytes(dst, bytes)
        if (w["ok"] != true) throw IllegalStateException("فشلت الكتابة إلى الوجهة: ${w["error"]}")
        val d = delete(src, recursive = false)
        if (d["ok"] != true) throw IllegalStateException("تم النسخ ولكن تعذر حذف الملف المصدر: $src")
        ok("note" to "تم النسخ إلى الموقع الجديد وحذف المصدر (عملية غير ذرية)")
    }

    suspend fun copy(from: String, to: String): Map<String, Any?> = guard {
        val src = normalize(from) ?: throw IllegalArgumentException("from فارغ")
        val dst = normalize(to) ?: throw IllegalArgumentException("to فارغ")
        if (src == dst) throw IllegalArgumentException("المصدر والوجهة هما المسار نفسه")

        val srcSaf = registry.matchSaf(src)
        val srcDoc = if (srcSaf != null) {
            resolveSaf(srcSaf.first, srcSaf.second)
                ?: throw IllegalArgumentException("المصدر غير موجود: $src")
        } else {
            if (!File(src).exists()) throw IllegalArgumentException("المصدر غير موجود: $src")
            null
        }

        val isDir = srcDoc?.isDirectory ?: File(src).isDirectory
        if (!isDir) {
            val bytes = (readBytes(src)["bytes"] as? ByteArray)
                ?: throw IllegalStateException("فشلت قراءة الملف المصدر: $src")
            val w = writeBytes(dst, bytes)
            if (w["ok"] != true) throw IllegalStateException("فشلت الكتابة إلى الوجهة: ${w["error"]}")
            return@guard ok()
        }

        val mk = mkdir(dst)
        if (mk["ok"] != true) throw IllegalStateException("تعذر إنشاء مجلد الوجهة: ${mk["error"]}")
        val listed = list(src, recursive = true, maxEntries = HARD_MAX_ENTRIES)
        if (listed["ok"] != true) throw IllegalStateException("فشل سرد مجلد المصدر: ${listed["error"]}")
        if (listed["truncated"] == true) {
            throw IllegalStateException("تتجاوز عناصر مجلد المصدر $HARD_MAX_ENTRIES عنصراً، تم رفض النسخ.")
        }
        @Suppress("UNCHECKED_CAST")
        val entries = listed["entries"] as? List<Map<String, Any?>> ?: emptyList()
        for (e in entries) {
            val p = e["path"] as? String ?: continue
            val rel = p.removePrefix(src.trimEnd('/')).trimStart('/')
            if (rel.isEmpty()) continue
            val target = dst.trimEnd('/') + "/" + rel
            if (e["isDir"] == true) {
                val r = mkdir(target)
                if (r["ok"] != true) throw IllegalStateException("تعذر إنشاء المجلد: $target (${r["error"]})")
            } else {
                val bytes = (readBytes(p)["bytes"] as? ByteArray)
                    ?: throw IllegalStateException("فشلت القراءة: $p")
                val r = writeBytes(target, bytes)
                if (r["ok"] != true) throw IllegalStateException("فشلت الكتابة: $target (${r["error"]})")
            }
        }
        ok()
    }

    private fun resolveSaf(entry: WorkspaceRegistry.Entry, relativePath: String): DocumentFile? {
        val root = runCatching {
            DocumentFile.fromTreeUri(context, Uri.parse(entry.treeUri))
        }.getOrNull() ?: return null
        var cur: DocumentFile = root
        val rel = relativePath.trim('/')
        if (rel.isEmpty()) return cur
        for (seg in rel.split('/')) {
            if (seg.isEmpty() || seg == "." || seg == "..") continue
            cur = findChild(cur, seg) ?: return null
        }
        return cur
    }

    private fun resolveOrCreateSaf(
        entry: WorkspaceRegistry.Entry,
        relativePath: String,
    ): DocumentFile? {
        val root = runCatching {
            DocumentFile.fromTreeUri(context, Uri.parse(entry.treeUri))
        }.getOrNull() ?: return null
        var cur: DocumentFile = root
        val rel = relativePath.trim('/')
        if (rel.isEmpty()) return cur
        for (seg in rel.split('/')) {
            if (seg.isEmpty() || seg == "." || seg == "..") continue
            val next = findChild(cur, seg)
            if (next != null && next.isDirectory) {
                cur = next
                continue
            }
            val created = cur.createDirectory(seg) ?: return null
            if (created.name != seg) {
                runCatching { created.delete() }
                cur = findChild(cur, seg) ?: return null
            } else {
                cur = created
            }
        }
        return cur
    }

    private fun findChild(dir: DocumentFile, name: String): DocumentFile? {
        dir.findFile(name)?.let { return it }
        return runCatching {
            dir.listFiles().firstOrNull { it.name?.equals(name, ignoreCase = true) == true }
        }.getOrNull()
    }

    private fun mimeOf(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "json" -> "application/json"
            "xml" -> "text/xml"
            "html", "htm" -> "text/html"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    private fun normalize(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty() || !s.startsWith("/")) return null
        val cleaned = s.replace('\\', '/').replace(Regex("/{2,}"), "/")
        return if (cleaned.length > 1) cleaned.trimEnd('/') else cleaned
    }
}
