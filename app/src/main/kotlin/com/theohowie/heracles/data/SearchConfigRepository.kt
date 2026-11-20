package com.theohowie.heracles.data

import android.content.Context
import com.theohowie.heracles.model.SearchConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class SearchConfigRepository(context: Context) {

    data class SearchResult(
        val title: String,
        val url: String,
        val snippet: String,
    )

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<SearchConfig> = _config.asStateFlow()

    private fun loadConfig(): SearchConfig {
        val cleaned = cleanLegacyKeysIfPresent()
        val url = prefs.getString(KEY_ENDPOINT_URL, "").orEmpty().trim()
        return SearchConfig(endpointUrl = url, legacyKeyCleaned = cleaned)
    }

    private fun cleanLegacyKeysIfPresent(): Boolean {
        var removed = false
        for (legacy in listOf("search_api_key", "tavily_key", "serper_key", "api_key")) {
            if (prefs.contains(legacy)) {
                prefs.edit().remove(legacy).apply()
                removed = true
            }
        }
        return removed
    }

    fun saveEndpoint(url: String): Result<Unit> {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            prefs.edit().remove(KEY_ENDPOINT_URL).apply()
            _config.value = SearchConfig(endpointUrl = "", legacyKeyCleaned = _config.value.legacyKeyCleaned)
            return Result.success(Unit)
        }
        if (!trimmed.startsWith("https://") && !trimmed.startsWith("http://")) {
            return Result.failure(IllegalArgumentException("يجب أن يبدأ رابط نقطة البحث بـ https:// أو http://"))
        }
        val lower = trimmed.lowercase()
        if (lower.contains("api_key=") || lower.contains("apikey=") || lower.contains("token=")) {
            return Result.failure(IllegalArgumentException("لأسباب أمنية، لا يُسمح بتضمين مفاتيح API داخل رابط البحث"))
        }
        prefs.edit().putString(KEY_ENDPOINT_URL, trimmed).apply()
        _config.value = SearchConfig(endpointUrl = trimmed, legacyKeyCleaned = _config.value.legacyKeyCleaned)
        return Result.success(Unit)
    }

    fun search(query: String, limit: Int = 6): Result<List<SearchResult>> {
        val endpoint = _config.value.endpointUrl
        if (endpoint.isBlank()) {
            return Result.failure(IllegalStateException("لم يتم إعداد نقطة بحث الويب بعد (انتقل إلى الإعدادات ← نقطة بحث الويب لإعداد SearXNG)"))
        }
        return runCatching {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val reqUrl = when {
                "{query}" in endpoint -> endpoint.replace("{query}", encoded)
                "?" in endpoint -> "$endpoint&q=$encoded&format=json"
                else -> "${endpoint.trimEnd('/')}/search?q=$encoded&format=json"
            }
            val req = Request.Builder().url(reqUrl).get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    error("فشل طلب البحث: HTTP ${resp.code}")
                }
                val body = resp.body?.string().orEmpty()
                parseSearchResults(body, limit)
            }
        }
    }

    private fun parseSearchResults(body: String, limit: Int): List<SearchResult> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return emptyList()
        val obj = runCatching { JSONObject(trimmed) }.getOrNull()
        val arr = when {
            obj?.has("results") == true -> obj.optJSONArray("results")
            obj?.has("items") == true -> obj.optJSONArray("items")
            obj?.has("data") == true -> obj.optJSONArray("data")
            trimmed.startsWith("[") -> runCatching { JSONArray(trimmed) }.getOrNull()
            else -> null
        } ?: return emptyList()

        val out = mutableListOf<SearchResult>()
        for (i in 0 until arr.length()) {
            if (out.size >= limit) break
            val item = arr.optJSONObject(i) ?: continue
            val title = item.optString("title").ifBlank { item.optString("name") }
            val url = item.optString("url").ifBlank { item.optString("link") }
            val snippet = item.optString("content")
                .ifBlank { item.optString("snippet") }
                .ifBlank { item.optString("description") }
            if (url.isNotBlank() || title.isNotBlank()) {
                out += SearchResult(title = title.ifBlank { url }, url = url, snippet = snippet.take(400))
            }
        }
        return out
    }

    companion object {
        private const val PREFS_NAME = "heracles_search_config"
        private const val KEY_ENDPOINT_URL = "endpoint_url"
    }
}
