package com.theohowie.heracles.data

import com.theohowie.heracles.bridge.DeviceFs
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class DocumentReader(private val fs: DeviceFs) {

    data class DocumentResult(
        val ok: Boolean,
        val text: String = "",
        val format: String = "",
        val truncated: Boolean = false,
        val error: String? = null,
    )

    suspend fun read(path: String, maxChars: Int = 80_000): DocumentResult {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "txt", "md", "markdown", "json", "yaml", "yml", "xml", "csv", "log",
            "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "dart", "c", "cpp", "h",
            "rs", "go", "sh", "toml", "ini", "properties", "html", "htm", "css", "sql",
            -> {
                val res = fs.readText(path)
                if (res["ok"] != true) {
                    DocumentResult(false, error = res["error"]?.toString() ?: "فشلت قراءة الملف")
                } else {
                    val raw = res["text"] as? String ?: ""
                    val truncated = raw.length > maxChars || res["truncated"] == true
                    DocumentResult(true, text = raw.take(maxChars), format = ext, truncated = truncated)
                }
            }

            "docx", "xlsx", "pptx" -> readOfficeZip(path, ext, maxChars)

            "pdf" -> DocumentResult(
                ok = false,
                format = "pdf",
                error = "محلل النص العادي المدمج لا يفك ضغط تدفقات PDF المضغوطة مباشرة؛ يرجى استخدام pdftotext أو python pypdf في البيئة المعزولة، أو توفير مستند .txt/.md/.docx.",
            )

            else -> {
                val res = fs.readText(path)
                if (res["ok"] != true) {
                    DocumentResult(false, error = res["error"]?.toString() ?: "تعذرت قراءة ملف .$ext كنص")
                } else {
                    val raw = res["text"] as? String ?: ""
                    DocumentResult(true, text = raw.take(maxChars), format = ext, truncated = raw.length > maxChars)
                }
            }
        }
    }

    private suspend fun readOfficeZip(path: String, ext: String, maxChars: Int): DocumentResult {
        val bin = fs.readBytes(path)
        if (bin["ok"] != true) {
            return DocumentResult(false, error = bin["error"]?.toString() ?: "فشلت قراءة ملف Office")
        }
        val bytes = bin["bytes"] as? ByteArray
            ?: return DocumentResult(false, error = "ملف فارغ")
        return runCatching {
            val parts = mutableListOf<Pair<String, String>>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val name = entry.name
                    val interesting = when (ext) {
                        "docx" -> name == "word/document.xml" || name.startsWith("word/header") || name.startsWith("word/footer")
                        "xlsx" -> name == "xl/sharedStrings.xml" || name.startsWith("xl/worksheets/sheet")
                        "pptx" -> name.startsWith("ppt/slides/slide") && name.endsWith(".xml")
                        else -> false
                    }
                    if (interesting) {
                        val xml = zis.readBytes().toString(Charsets.UTF_8)
                        val plain = stripXml(xml)
                        if (plain.isNotBlank()) parts += name to plain
                    }
                    zis.closeEntry()
                }
            }
            val joined = parts.sortedBy { it.first }
                .joinToString("\n\n") { "[${it.first}]\n${it.second}" }
            if (joined.isBlank()) {
                DocumentResult(false, format = ext, error = "لا توجد عقد نصية قابلة للاستخراج في المستند")
            } else {
                DocumentResult(
                    ok = true,
                    text = joined.take(maxChars),
                    format = ext,
                    truncated = joined.length > maxChars,
                )
            }
        }.getOrElse {
            DocumentResult(false, format = ext, error = "فشل تحليل بنية ZIP لملف .$ext: ${it.message}")
        }
    }

    private fun stripXml(xml: String): String {
        return xml
            .replace(Regex("</w:p>|</a:p>|</row>|</si>|</t>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .lines()
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }
}
