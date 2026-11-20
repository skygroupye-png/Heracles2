package com.theohowie.heracles.bridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class WorkspaceRegistry(private val context: Context) {

    data class Entry(
        val path: String,
        val name: String,
        val treeUri: String,
    ) {
        val isSaf: Boolean get() = treeUri.isNotBlank()
        val id: String get() = path
    }

    companion object {
        const val PREFS = "heracles_workspaces"
        private const val KEY_LIST = "list"
        const val DOT_DIR = ".heracles"
        private const val KEY_SESSIONS = "$DOT_DIR/sessions.json"
        const val APP_WORKSPACE_DIR = "app-workspace"
        const val APP_WORKSPACE_NAME = "مساحة العمل الافتراضية"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(): List<Entry> {
        val raw = prefs.getString(KEY_LIST, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<Entry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val path = o.optString("path")
            if (path.isBlank()) continue
            val dirName = File(path).name
            out += Entry(
                path = path,
                name = o.optString("name").ifBlank { dirName },
                treeUri = o.optString("treeUri"),
            )
        }
        return out
    }

    fun matchSaf(path: String): Pair<Entry, String>? {
        var best: Pair<Entry, String>? = null
        for (e in list()) {
            if (!e.isSaf) continue
            val root = e.path.trimEnd('/')
            if (path != root && !path.startsWith("$root/")) continue
            val rel = if (path == root) "" else path.substring(root.length + 1)
            if (best == null || root.length > best.first.path.length) best = e to rel
        }
        return best
    }

    fun add(path: String, name: String, treeUri: String = ""): Entry? {
        val dir = File(path)
        if (treeUri.isBlank() && !dir.isDirectory) return null
        val entry = Entry(
            path = path,
            name = name.ifBlank { dir.name.ifBlank { path } },
            treeUri = treeUri,
        )
        val all = list().filter { it.path != path } + entry
        save(all)
        return entry
    }

    fun remove(path: String) {
        save(list().filter { it.path != path })
    }

    fun ensureAppWorkspace(): Entry? {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, APP_WORKSPACE_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val path = dir.absolutePath
        list().firstOrNull { it.path == path }?.let { return it }
        return add(path = path, name = APP_WORKSPACE_NAME)
    }

    suspend fun sessionCount(fs: DeviceFs, entry: Entry): Int = runCatching {
        val text = fs.readWholeText(entry.path.trimEnd('/') + "/" + KEY_SESSIONS)
        JSONArray(text.trim()).length()
    }.getOrDefault(0)

    private fun save(all: List<Entry>) {
        val arr = JSONArray()
        all.forEach { e ->
            arr.put(
                JSONObject().apply {
                    put("path", e.path)
                    put("name", e.name)
                    put("treeUri", e.treeUri)
                },
            )
        }
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
    }
}
