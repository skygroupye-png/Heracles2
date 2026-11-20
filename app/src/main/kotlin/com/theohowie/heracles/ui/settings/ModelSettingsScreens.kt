package com.theohowie.heracles.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theohowie.heracles.AppContainer
import com.theohowie.heracles.i18n.LocalStrings
import com.theohowie.heracles.model.ModelCapability
import com.theohowie.heracles.model.ModelProviderConfig
import com.theohowie.heracles.model.ProviderProtocol
import com.theohowie.heracles.ui.theme.LocalHeraclesColors
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun ModelsListScreen(
    onBack: () -> Unit,
    onEditProvider: (String?) -> Unit,
) {
    val app = LocalContext.current.applicationContext as Application
    val container = remember { AppContainer.get(app) }
    val settings by container.settingsRepo.settings.collectAsStateWithLifecycle()
    val colors = LocalHeraclesColors.current
    val s = LocalStrings.current

    SettingsScaffold(
        title = s.modelsAndProviders,
        onBack = onBack,
        actions = {
            IconButton(onClick = { onEditProvider(null) }, modifier = Modifier.testTag("add_provider_btn")) {
                Icon(Icons.Filled.Add, contentDescription = s.addProvider, tint = colors.textPrimary)
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(settings.providers, key = { it.id }) { p ->
                    val isActive = p.id == settings.activeProviderId
                    val hasKey = container.settingsRepo.hasProviderApiKey(p.id)
                    SettingsGroupCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(p.name, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                                    if (isActive) {
                                        Spacer(Modifier.width(6.dp))
                                        Icon(Icons.Filled.CheckCircle, contentDescription = s.activeTag, tint = colors.accent)
                                    }
                                }
                                Text(
                                    "${p.protocol.name} · ${p.model} · ${if (hasKey) s.keyConfigured else s.keyMissing}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (hasKey) colors.textMuted else colors.warning,
                                )
                            }
                            if (!isActive) {
                                TextButton(onClick = {
                                    container.settingsRepo.updateSettings { it.copy(activeProviderId = p.id) }
                                }) { Text(s.setDefault) }
                            }
                            IconButton(onClick = { onEditProvider(p.id) }, modifier = Modifier.testTag("edit_provider_${p.id}")) {
                                Icon(Icons.Filled.Edit, contentDescription = s.edit)
                            }
                            if (!p.isBuiltIn) {
                                IconButton(onClick = { container.settingsRepo.deleteProvider(p.id) }) {
                                    Icon(Icons.Filled.DeleteOutline, contentDescription = s.delete)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelEditorScreen(
    providerId: String?,
    onBack: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as Application
    val container = remember { AppContainer.get(app) }
    val settings by container.settingsRepo.settings.collectAsStateWithLifecycle()
    val existing = remember(providerId, settings) {
        settings.providers.firstOrNull { it.id == providerId }
    }
    val scope = rememberCoroutineScope()
    val colors = LocalHeraclesColors.current
    val s = LocalStrings.current

    var name by remember { mutableStateOf(existing?.name ?: "") }
    var protocol by remember { mutableStateOf(existing?.protocol ?: ProviderProtocol.OPENAI_CHAT) }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl ?: "https://api.openai.com/v1") }
    var model by remember { mutableStateOf(existing?.model ?: "") }
    var apiKey by remember {
        mutableStateOf(if (existing != null) container.settingsRepo.getProviderApiKey(existing.id) else "")
    }
    var supportsVision by remember { mutableStateOf(existing?.supportsVision ?: false) }
    var availableModels by remember { mutableStateOf(existing?.availableModels ?: emptyList()) }
    var capabilities by remember { mutableStateOf(existing?.modelCapabilities ?: emptyMap<String, ModelCapability>()) }
    var fetching by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf<String?>(null) }

    SettingsScaffold(title = if (existing == null) s.newProviderTitle else s.editProviderTitle(existing.name), onBack = onBack) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    SettingsGroupCard {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text(s.providerNameLabel) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("provider_name_input"),
                        )
                        Text(s.protocolLabel, style = MaterialTheme.typography.labelMedium, color = colors.textMuted)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ProviderProtocol.entries.forEach { p ->
                                FilterChip(
                                    selected = protocol == p,
                                    onClick = { protocol = p },
                                    label = { Text(p.name) },
                                )
                            }
                        }
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            label = { Text(s.baseUrlLabel) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("provider_url_input"),
                        )
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = { Text(s.apiKeyKeystoreLabel) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth().testTag("provider_key_input"),
                        )
                        OutlinedTextField(
                            value = model,
                            onValueChange = { model = it },
                            label = { Text(s.currentModelIdLabel) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("provider_model_input"),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(s.visionSupportLabel, style = MaterialTheme.typography.bodyMedium)
                            Switch(checked = supportsVision, onCheckedChange = { supportsVision = it })
                        }

                        if (fetching) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        statusText?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.accent)
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    fetching = true
                                    statusText = null
                                    val temp = ModelProviderConfig(
                                        id = existing?.id ?: "tmp",
                                        name = name,
                                        protocol = protocol,
                                        baseUrl = baseUrl,
                                        model = model,
                                    )
                                    scope.launch {
                                        val res = container.llmClient.fetchModels(temp, apiKey.trim())
                                        fetching = false
                                        res.fold(
                                            onSuccess = { fetched ->
                                                availableModels = fetched.models
                                                capabilities = capabilities + fetched.capabilities
                                                if (model.isBlank() && fetched.models.isNotEmpty()) {
                                                    model = fetched.models.first()
                                                }
                                                statusText = s.fetchedModelsCount(fetched.models.size)
                                            },
                                            onFailure = { e -> statusText = e.message ?: s.failLabel },
                                        )
                                    }
                                },
                                modifier = Modifier.weight(1f).testTag("fetch_models_btn"),
                            ) { Text(s.fetchModelsBtn) }

                            Button(
                                onClick = {
                                    val id = existing?.id ?: ("custom_" + UUID.randomUUID().toString().take(8))
                                    val finalModels = (availableModels + model).filter { it.isNotBlank() }.distinct()
                                    val cfg = ModelProviderConfig(
                                        id = id,
                                        name = name.trim().ifBlank { "Custom Provider" },
                                        protocol = protocol,
                                        baseUrl = baseUrl.trim(),
                                        model = model.trim().ifBlank { finalModels.firstOrNull().orEmpty() },
                                        availableModels = finalModels,
                                        modelCapabilities = capabilities,
                                        supportsVision = supportsVision,
                                        isBuiltIn = existing?.isBuiltIn ?: false,
                                    )
                                    container.settingsRepo.saveProvider(cfg, apiKey.trim())
                                    if (existing == null) {
                                        container.settingsRepo.updateSettings { it.copy(activeProviderId = id) }
                                    }
                                    onBack()
                                },
                                modifier = Modifier.weight(1f).testTag("save_provider_btn"),
                            ) { Text(s.saveConfigBtn) }
                        }
                    }
                }

                if (availableModels.isNotEmpty()) {
                    item {
                        SettingsGroupCard(title = s.availableModelsPick(availableModels.size)) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                availableModels.take(30).forEach { m ->
                                    FilterChip(
                                        selected = model == m,
                                        onClick = { model = m },
                                        label = { Text(m) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
