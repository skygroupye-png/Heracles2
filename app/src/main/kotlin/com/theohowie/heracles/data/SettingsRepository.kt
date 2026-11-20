package com.theohowie.heracles.data

import android.content.Context
import com.theohowie.heracles.bridge.AutomationScheduler
import com.theohowie.heracles.bridge.KeystoreVault
import com.theohowie.heracles.model.AppSettings
import com.theohowie.heracles.model.AutomationRunRecord
import com.theohowie.heracles.model.AutomationTask
import com.theohowie.heracles.model.ModelProviderConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

class SettingsRepository(
    private val context: Context,
    private val vault: KeystoreVault,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _settings = MutableStateFlow(loadInitialSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _tasks = MutableStateFlow(loadAutomationTasks())
    val tasks: StateFlow<List<AutomationTask>> = _tasks.asStateFlow()

    private val _runs = MutableStateFlow(loadAutomationRuns())
    val runs: StateFlow<List<AutomationRunRecord>> = _runs.asStateFlow()

    private fun loadInitialSettings(): AppSettings {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return AppSettings()
        val loaded = runCatching { json.decodeFromString(AppSettings.serializer(), raw) }
            .getOrElse { AppSettings() }
        val defaults = AppSettings.defaultProviders()
        val merged = defaults.map { def ->
            loaded.providers.firstOrNull { it.id == def.id } ?: def
        } + loaded.providers.filter { p -> defaults.none { it.id == p.id } }
        return loaded.copy(providers = merged)
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        val encoded = json.encodeToString(AppSettings.serializer(), next)
        prefs.edit().putString(KEY_SETTINGS, encoded).apply()
    }

    fun getProviderApiKey(providerId: String): String =
        vault.read(providerKeyAlias(providerId))

    fun setProviderApiKey(providerId: String, key: String) {
        vault.write(providerKeyAlias(providerId), key)
    }

    fun hasProviderApiKey(providerId: String): Boolean =
        vault.contains(providerKeyAlias(providerId))

    fun saveProvider(config: ModelProviderConfig, apiKey: String? = null) {
        updateSettings { cur ->
            val exists = cur.providers.any { it.id == config.id }
            val updatedList = if (exists) {
                cur.providers.map { if (it.id == config.id) config else it }
            } else {
                cur.providers + config
            }
            cur.copy(providers = updatedList)
        }
        if (apiKey != null) {
            setProviderApiKey(config.id, apiKey)
        }
    }

    fun deleteProvider(providerId: String) {
        val target = _settings.value.providers.firstOrNull { it.id == providerId } ?: return
        if (target.isBuiltIn) return
        vault.delete(providerKeyAlias(providerId))
        updateSettings { cur ->
            val filtered = cur.providers.filter { it.id != providerId }
            val nextActive = if (cur.activeProviderId == providerId) {
                filtered.firstOrNull()?.id ?: ""
            } else {
                cur.activeProviderId
            }
            cur.copy(providers = filtered, activeProviderId = nextActive)
        }
    }

    fun setLastWorkspacePath(path: String) {
        prefs.edit().putString(KEY_LAST_WORKSPACE, path).apply()
    }

    fun getLastWorkspacePath(): String =
        prefs.getString(KEY_LAST_WORKSPACE, "") ?: ""

    fun setLastSessionId(workspacePath: String, sessionId: String) {
        prefs.edit().putString("$KEY_LAST_SESSION:$workspacePath", sessionId).apply()
    }

    fun getLastSessionId(workspacePath: String): String =
        prefs.getString("$KEY_LAST_SESSION:$workspacePath", "") ?: ""

    fun saveAutomationTasks(list: List<AutomationTask>) {
        _tasks.value = list
        val encoded = json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(AutomationTask.serializer()),
            list,
        )
        AutomationScheduler.saveTasks(context, encoded)
    }

    fun recordAutomationRun(record: AutomationRunRecord) {
        val updated = (listOf(record) + _runs.value).take(50)
        _runs.value = updated
        val encoded = json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(AutomationRunRecord.serializer()),
            updated,
        )
        prefs.edit().putString(KEY_AUTOMATION_RUNS, encoded).apply()
    }

    private fun loadAutomationTasks(): List<AutomationTask> {
        val raw = AutomationScheduler.loadTasks(context)
        return runCatching {
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(AutomationTask.serializer()),
                raw,
            )
        }.getOrDefault(emptyList())
    }

    private fun loadAutomationRuns(): List<AutomationRunRecord> {
        val raw = prefs.getString(KEY_AUTOMATION_RUNS, "[]") ?: "[]"
        return runCatching {
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(AutomationRunRecord.serializer()),
                raw,
            )
        }.getOrDefault(emptyList())
    }

    private fun providerKeyAlias(providerId: String) = "heracles.provider.$providerId.key"

    companion object {
        private const val PREFS_NAME = "heracles_settings"
        private const val KEY_SETTINGS = "app_settings_v1"
        private const val KEY_LAST_WORKSPACE = "last_workspace"
        private const val KEY_LAST_SESSION = "last_session"
        private const val KEY_AUTOMATION_RUNS = "automation_runs"
    }
}
