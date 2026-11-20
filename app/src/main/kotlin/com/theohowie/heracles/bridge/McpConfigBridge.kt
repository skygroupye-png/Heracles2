package com.theohowie.heracles.bridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class McpConfigBridge(private val context: Context) {

    data class ServerConfig(
        val name: String,
        val transport: String, // "http" or "stdio"
        val command: String = "",
        val url: String = "",
        val args: List<String> = emptyList(),
        val env: Map<String, String> = emptyMap(),
        val enabled: Boolean = true,
    )

    data class SaveResult(
        val ok: Boolean,
        val error: String? = null,
    )

    private companion object {
        const val PREFS = "heracles_mcp"
        const val KEY_SERVERS = "servers"
        val SECRET_HINTS = listOf("KEY", "TOKEN", "SECRET", "PASSWORD")
    }

    fun list(): List<ServerConfig> {
        val arr = readArray()
        val out = mutableListOf<ServerConfig>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name")
            if (name.isBlank()) continue
            val argsList = mutableListOf<String>()
            val argsArr = o.optJSONArray("args") ?: JSONArray()
            for (j in 0 until argsArr.length()) {
                argsList += argsArr.optString(j)
            }
            val envMap = mutableMapOf<String, String>()
            val envObj = o.optJSONObject("env")
            if (envObj != null) {
                val keys = envObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    envMap[k] = envObj.optString(k)
                }
            }
            out += ServerConfig(
                name = name,
                transport = o.optString("transport", "http"),
                command = o.optString("command"),
                url = o.optString("url"),
                args = argsList,
                env = envMap,
                enabled = o.optBoolean("enabled", true),
            )
        }
        return out
    }

    fun save(server: ServerConfig): SaveResult {
        val name = server.name.trim()
        if (name.isEmpty()) return SaveResult(false, "لا يمكن أن يكون الاسم فارغاً")
        val transport = server.transport.trim()
        if (transport != "stdio" && transport != "http") {
            return SaveResult(false, "يجب أن يكون نوع النقل stdio أو http فقط")
        }
        if (transport == "stdio" && server.command.isBlank()) {
            return SaveResult(false, "وضع stdio يتطلب إدخال الأمر (command)")
        }
        if (transport == "http" &&
            !server.url.startsWith("http://") &&
            !server.url.startsWith("https://")
        ) {
            return SaveResult(false, "وضع http يتطلب أن يبدأ الرابط بـ http:// أو https://")
        }

        for (k in server.env.keys) {
            if (SECRET_HINTS.any { k.uppercase().contains(it) }) {
                return SaveResult(false, "لا تضع المفاتيح السرية في متغيرات بيئة MCP ($k)، استخدم التخزين الآمن")
            }
        }

        val envObj = JSONObject()
        server.env.forEach { (k, v) -> envObj.put(k, v) }
        val argsArr = JSONArray().apply { server.args.forEach { put(it) } }

        val obj = JSONObject()
            .put("name", name)
            .put("transport", transport)
            .put("command", server.command.trim())
            .put("url", server.url.trim())
            .put("args", argsArr)
            .put("env", envObj)
            .put("enabled", server.enabled)

        val arr = readArray()
        var replaced = false
        for (i in 0 until arr.length()) {
            if (arr.optJSONObject(i)?.optString("name") == name) {
                arr.put(i, obj)
                replaced = true
                break
            }
        }
        if (!replaced) arr.put(obj)
        if (!writeArray(arr)) return SaveResult(false, "فشل الحفظ في التخزين")
        return SaveResult(true)
    }

    fun delete(name: String): Boolean {
        if (name.isBlank()) return false
        val arr = readArray()
        val kept = JSONArray()
        var deleted = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("name") == name) deleted = true else kept.put(o)
        }
        return if (deleted) writeArray(kept) else false
    }

    private fun readArray(): JSONArray = runCatching {
        JSONArray(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SERVERS, "[]") ?: "[]",
        )
    }.getOrElse { JSONArray() }

    private fun writeArray(arr: JSONArray): Boolean = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SERVERS, arr.toString()).commit()
    }.getOrDefault(false)
}
