package com.theohowie.heracles.bridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import java.io.File

/**
 * Resolves SAF Tree URIs returned by ActivityResultContracts.OpenDocumentTree into real paths,
 * checks Android 11+ scoped storage restrictions, and probes POSIX writability.
 */
object SafPathHelper {

    private const val PREFS = "heracles_folder_picker"
    private const val KEY_LAST_URI = "last_tree_uri"

    private val RESTRICTED_EXACT = setOf(
        "/storage/emulated/0",
        "/storage/emulated/0/Download",
        "/storage/emulated/0/Android",
        "/storage/emulated/0/Android/data",
        "/storage/emulated/0/Android/obb",
    )

    sealed interface PickOutcome {
        data class Ready(
            val entry: WorkspaceRegistry.Entry,
            val posixWritable: Boolean,
        ) : PickOutcome

        data class Restricted(
            val path: String,
            val reason: String,
        ) : PickOutcome

        data class NoLocalPath(val reason: String) : PickOutcome
    }

    fun processTreeUri(
        context: Context,
        registry: WorkspaceRegistry,
        uri: Uri,
    ): PickOutcome {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }

        val path = toRealPath(uri) ?: return PickOutcome.NoLocalPath(
            "هذا الموقع لا يحتوي على مسار ملف محلي (مثل التخزين السحابي)، لا يمكن لـ Heracles القراءة والكتابة إلا في مجلدات حقيقية. " +
                "يرجى اختيار مجلد فرعي داخل «وحدة التخزين الداخلية» أو استخدام «مساحة العمل الافتراضية».",
        )

        val restricted = checkRestricted(path)
        if (restricted != null) {
            return PickOutcome.Restricted(path, restricted)
        }

        val dir = File(path)
        val writable = probeWritable(dir)
        saveLastUri(context, uri.toString())
        val entry = registry.add(
            path = path,
            name = dir.name.ifBlank { path },
            treeUri = uri.toString(),
        ) ?: WorkspaceRegistry.Entry(path, dir.name.ifBlank { path }, uri.toString())
        return PickOutcome.Ready(entry = entry, posixWritable = writable)
    }

    fun loadLastUri(context: Context): Uri? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_URI, "")
            .orEmpty()
        return if (raw.isBlank()) null else runCatching { Uri.parse(raw) }.getOrNull()
    }

    private fun saveLastUri(context: Context, uri: String) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_LAST_URI, uri).apply()
        }
    }

    fun checkRestricted(path: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val p = path.trimEnd('/')
        if (p.isEmpty()) return null
        if (RESTRICTED_EXACT.contains(p)) {
            return "بدءاً من Android 11، لا يسمح النظام بمنح التطبيق صلاحية الوصول الكامل إلى مجلد «${p.substringAfterLast('/')}»."
        }
        if (p.startsWith("/storage/emulated/0/Android/")) {
            return "بدءاً من Android 11، لا يسمح النظام بمنح صلاحية الوصول إلى أي موقع داخل مجلد Android."
        }
        if (Regex("^/storage/[A-Za-z0-9-]+$").matches(p)) {
            return "بدءاً من Android 11، لا يسمح النظام بمنح التطبيق صلاحية الوصول إلى وحدة التخزين بأكملها."
        }
        return null
    }

    private fun probeWritable(dir: File): Boolean {
        if (!dir.isDirectory || !dir.canWrite()) return false
        val probe = File(dir, ".heracles_probe_${System.currentTimeMillis()}")
        return try {
            if (probe.mkdir()) {
                probe.delete()
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    fun toRealPath(uri: Uri): String? {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val parts = docId.split(":", limit = 2)
        val type = parts.getOrNull(0) ?: return null
        val rel = parts.getOrNull(1).orEmpty()
        val base = if (type == "primary") "/storage/emulated/0" else "/storage/$type"
        val full = if (rel.isBlank()) base else "$base/$rel"
        return runCatching { File(full).canonicalPath }.getOrNull()
    }
}
