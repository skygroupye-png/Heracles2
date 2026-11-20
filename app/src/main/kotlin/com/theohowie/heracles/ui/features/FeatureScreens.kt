package com.theohowie.heracles.ui.features

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theohowie.heracles.AppContainer
import com.theohowie.heracles.bridge.McpConfigBridge
import com.theohowie.heracles.data.SkillsRepository
import com.theohowie.heracles.i18n.LocalStrings
import com.theohowie.heracles.model.AutomationTask
import com.theohowie.heracles.model.JEV_MODEL_SPECS
import com.theohowie.heracles.model.JevSource
import com.theohowie.heracles.model.recommendJev
import com.theohowie.heracles.ui.settings.SettingsGroupCard
import com.theohowie.heracles.ui.settings.SettingsScaffold
import com.theohowie.heracles.ui.theme.JetBrainsMonoFamily
import com.theohowie.heracles.ui.theme.LocalHeraclesColors
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun SkillsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val container = remember { AppContainer.get(app) }
    val wsPath = remember {
        container.settingsRepo.getLastWorkspacePath().ifBlank {
            container.workspaceRegistry.ensureAppWorkspace()?.path.orEmpty()
        }
    }
    var loadResult by remember { mutableStateOf(container.skillsRepo.loadAll(wsPath)) }
    var installSource by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    val colors = LocalHeraclesColors.current
    val s = LocalStrings.current

    fun reload() { loadResult = container.skillsRepo.loadAll(wsPath) }

    SettingsScaffold(title = s.skills, onBack = onBack) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    SettingsGroupCard(title = s.installSkillTitle) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = installSource,
                                onValueChange = { installSource = it },
                                label = { Text(s.installSkillHint) },
                                singleLine = true,
                                modifier = Modifier.weight(1f).testTag("skill_install_input"),
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    val r = container.skillsRepo.install(wsPath, installSource)
                                    notice = if (r.ok) "${s.successLabel} (${r.installed.size})" else r.errors.joinToString("; ")
                                    if (r.ok) installSource = ""
                                    reload()
                                },
                                modifier = Modifier.testTag("skill_install_btn"),
                            ) { Text(s.install) }
                        }
                        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.accent) }
                    }
                }

                items(loadResult.skills, key = { it.id }) { skill: SkillsRepository.SkillEntry ->
                    SettingsGroupCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(skill.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${if (skill.isBuiltIn) s.builtinLabel else s.workspaceLabel} · ${skill.description}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textMuted,
                                )
                            }
                            if (!skill.isBuiltIn) {
                                IconButton(onClick = {
                                    container.skillsRepo.remove(wsPath, skill)
                                    reload()
                                }) {
                                    Icon(Icons.Filled.DeleteOutline, contentDescription = s.delete)
                                }
                            }
                            Switch(
                                checked = skill.enabled,
                                onCheckedChange = {
                                    container.skillsRepo.setEnabled(skill.name, it)
                                    reload()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun McpScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val container = remember { AppContainer.get(app) }
    var servers by remember { mutableStateOf(container.mcpBridge.list()) }
    var showAdd by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var transport by remember { mutableStateOf("http") }
    var endpoint by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val colors = LocalHeraclesColors.current
    val s = LocalStrings.current

    fun reload() { servers = container.mcpBridge.list() }

    SettingsScaffold(
        title = s.mcpTitle,
        onBack = onBack,
        actions = {
            IconButton(onClick = { showAdd = true }, modifier = Modifier.testTag("add_mcp_btn")) {
                Icon(Icons.Filled.Add, contentDescription = s.addMcp)
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (servers.isEmpty()) {
                    item {
                        SettingsGroupCard {
                            Text(s.mcpEmpty, color = colors.textMuted)
                        }
                    }
                }
                items(servers, key = { it.name }) { srv ->
                    SettingsGroupCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(srv.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${srv.transport} · ${if (srv.transport == "http") srv.url else srv.command}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textMuted,
                                )
                            }
                            IconButton(onClick = {
                                container.mcpBridge.delete(srv.name)
                                reload()
                            }) {
                                Icon(Icons.Filled.DeleteOutline, contentDescription = s.delete)
                            }
                            Switch(
                                checked = srv.enabled,
                                onCheckedChange = { enabled ->
                                    container.mcpBridge.save(srv.copy(enabled = enabled))
                                    reload()
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text(s.addMcp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(s.mcpNameLabel) }, singleLine = true)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("http", "stdio").forEach { t ->
                            FilterChip(selected = transport == t, onClick = { transport = t }, label = { Text(t) })
                        }
                    }
                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = { endpoint = it },
                        label = { Text(if (transport == "http") "URL (https://...)" else "Command") },
                        singleLine = true,
                    )
                    errorMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.danger) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cfg = McpConfigBridge.ServerConfig(
                        name = name.trim(),
                        transport = transport,
                        url = if (transport == "http") endpoint.trim() else "",
                        command = if (transport == "stdio") endpoint.trim() else "",
                    )
                    val res = container.mcpBridge.save(cfg)
                    if (res.ok) {
                        name = ""; endpoint = ""; errorMsg = null; showAdd = false; reload()
                    } else {
                        errorMsg = res.error
                    }
                }) { Text(s.save) }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text(s.cancel) } },
        )
    }
}

@Composable
fun AutomationScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val container = remember { AppContainer.get(app) }
    val tasks by container.settingsRepo.tasks.collectAsStateWithLifecycle()
    val runs by container.settingsRepo.runs.collectAsStateWithLifecycle()
    var showCreate by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var intervalMin by remember { mutableStateOf("60") }
    val colors = LocalHeraclesColors.current
    val s = LocalStrings.current

    SettingsScaffold(
        title = s.automationTitle,
        onBack = onBack,
        actions = {
            IconButton(onClick = { showCreate = true }, modifier = Modifier.testTag("add_automation_btn")) {
                Icon(Icons.Filled.Add, contentDescription = s.newTask)
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(tasks, key = { it.id }) { t ->
                    SettingsGroupCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(t.name, style = MaterialTheme.typography.titleMedium)
                                Text(s.everyMinutes(t.intervalMinutes, t.lastStatus.ifBlank { s.neverRun }), style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                                if (t.lastRunSummary.isNotBlank()) {
                                    Text(t.lastRunSummary, style = MaterialTheme.typography.bodySmall, color = colors.textPrimary)
                                }
                            }
                            IconButton(onClick = { container.automationRunner.triggerTask(t) }) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = s.runNow)
                            }
                            IconButton(onClick = {
                                container.settingsRepo.saveAutomationTasks(tasks.filter { it.id != t.id })
                            }) {
                                Icon(Icons.Filled.DeleteOutline, contentDescription = s.delete)
                            }
                            Switch(
                                checked = t.enabled,
                                onCheckedChange = { en ->
                                    container.settingsRepo.saveAutomationTasks(
                                        tasks.map { if (it.id == t.id) it.copy(enabled = en) else it },
                                    )
                                },
                            )
                        }
                    }
                }

                if (runs.isNotEmpty()) {
                    item { Text(s.recentRuns, style = MaterialTheme.typography.labelMedium, color = colors.textMuted) }
                    items(runs.take(15), key = { it.id }) { r ->
                        SettingsGroupCard {
                            Text("${r.taskName} · ${if (r.ok) s.successLabel else s.failLabel}", style = MaterialTheme.typography.bodyMedium, color = if (r.ok) colors.success else colors.danger)
                            Text(r.summary, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text(s.newTaskTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text(s.taskNameLabel) }, singleLine = true)
                    OutlinedTextField(value = prompt, onValueChange = { prompt = it }, label = { Text(s.taskPromptLabel) }, minLines = 2)
                    OutlinedTextField(value = intervalMin, onValueChange = { intervalMin = it.filter(Char::isDigit) }, label = { Text(s.intervalMinLabel) }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val ws = container.settingsRepo.getLastWorkspacePath().ifBlank {
                        container.workspaceRegistry.ensureAppWorkspace()?.path.orEmpty()
                    }
                    val task = AutomationTask(
                        id = "task_" + UUID.randomUUID().toString().take(8),
                        name = title.trim().ifBlank { s.newTask },
                        prompt = prompt.trim(),
                        workspacePath = ws,
                        intervalMinutes = intervalMin.toIntOrNull()?.coerceAtLeast(15) ?: 60,
                    )
                    container.settingsRepo.saveAutomationTasks(tasks + task)
                    title = ""; prompt = ""; showCreate = false
                }) { Text(s.save) }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text(s.cancel) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JevSettingsScreen(
    onBack: () -> Unit,
    onOpenLocal: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as Application
    val container = remember { AppContainer.get(app) }
    val cfg by container.jevRepo.config.collectAsStateWithLifecycle()
    val diag by container.jevRepo.lastDiagnostics.collectAsStateWithLifecycle()
    var baseUrl by remember(cfg.cloudBaseUrl) { mutableStateOf(cfg.cloudBaseUrl) }
    var apiKey by remember { mutableStateOf(container.jevRepo.getApiKey()) }
    var testQuestion by remember { mutableStateOf("هل اليوم مناسب لإصدار نسخة جديدة؟") }
    var testResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val colors = LocalHeraclesColors.current
    val s = LocalStrings.current

    SettingsScaffold(title = s.jevSettingsTitle, onBack = onBack) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    SettingsGroupCard(title = s.decisionSourceLabel) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            JevSource.entries.forEach { src ->
                                FilterChip(
                                    selected = cfg.source == src,
                                    onClick = { container.jevRepo.updateConfig { it.copy(source = src) } },
                                    label = { Text(src.shortLabel) },
                                )
                            }
                        }
                        OutlinedButton(onClick = onOpenLocal, modifier = Modifier.testTag("open_jev_local_btn")) {
                            Text(s.openJevLocalBtn)
                        }
                    }
                }

                item {
                    SettingsGroupCard(title = s.cloudEndpointTitle) {
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            label = { Text(s.baseUrlLabel) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = { Text(s.bearerKeyLabel) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                container.jevRepo.updateConfig { it.copy(cloudBaseUrl = baseUrl.trim()) }
                                container.jevRepo.setApiKey(apiKey)
                                testResult = s.successLabel
                            }, modifier = Modifier.weight(1f)) { Text(s.save) }

                            OutlinedButton(onClick = {
                                container.jevRepo.updateConfig { it.copy(cloudBaseUrl = baseUrl.trim()) }
                                container.jevRepo.setApiKey(apiKey)
                                scope.launch {
                                    val r = container.jevRepo.evaluateDecision(testQuestion)
                                    testResult = r.fold(
                                        { "${s.successLabel}: yes=${(it.yesProb * 100).toInt()}% (${it.elapsedMs}ms)" },
                                        { "${s.failLabel}: ${it.message}" },
                                    )
                                }
                            }, modifier = Modifier.weight(1f)) { Text(s.testConnection) }
                        }
                        testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.accent) }
                    }
                }

                diag?.let { d ->
                    item {
                        SettingsGroupCard(title = s.lastDiagnosticsTitle) {
                            Text("Endpoint: ${d.resolvedEndpoint}", style = MaterialTheme.typography.bodySmall, fontFamily = JetBrainsMonoFamily)
                            Text("HTTP: ${d.httpStatus ?: "--"} · ${d.elapsedMs}ms · retry422=${d.retriedAfter422} · /models=${d.modelsCatalogReadable}", style = MaterialTheme.typography.bodySmall)
                            if (d.traceId != null) Text("Trace: ${d.traceId}", style = MaterialTheme.typography.bodySmall, fontFamily = JetBrainsMonoFamily)
                            if (d.rawBody.isNotBlank()) {
                                Text(d.rawBody.take(600), style = MaterialTheme.typography.bodySmall, fontFamily = JetBrainsMonoFamily, color = colors.textMuted)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun JevLocalSettingsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val container = remember { AppContainer.get(app) }
    val profile = remember { container.jevRepo.deviceProfile() }
    val rec = remember(profile) { recommendJev(profile) }
    val progress by container.jevRepo.downloadProgress.collectAsStateWithLifecycle()
    var statuses by remember { mutableStateOf(container.jevRepo.localModelStatuses()) }
    val scope = rememberCoroutineScope()
    val colors = LocalHeraclesColors.current
    val s = LocalStrings.current

    SettingsScaffold(title = s.jevLocalTitle, onBack = onBack) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    SettingsGroupCard(title = s.deviceHardwareTitle) {
                        Text("${profile.model} · RAM ${"%.1f".format(profile.totalRamGb)} GB · Storage ${"%.1f".format(profile.freeStorageGb)} GB", style = MaterialTheme.typography.bodyMedium)
                        Text(rec.headline, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.accent)
                        Text(rec.detail, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                    }
                }
                if (progress.active) {
                    item {
                        SettingsGroupCard {
                            Text(progress.status, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(progress = { progress.percent / 100f }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                items(statuses, key = { it.spec.dirName }) { st ->
                    SettingsGroupCard {
                        Text("${st.spec.displayName} (${st.spec.quantLabel})", style = MaterialTheme.typography.titleMedium)
                        Text("${st.spec.sizeLabel} · ${st.runtimeNote}", style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!st.downloaded) {
                                Button(onClick = {
                                    scope.launch {
                                        container.jevRepo.downloadLocalModel(st.spec)
                                        statuses = container.jevRepo.localModelStatuses()
                                    }
                                }) { Text(s.downloadGgufBtn) }
                            } else {
                                OutlinedButton(onClick = {
                                    container.jevRepo.deleteLocalModel(st.spec)
                                    statuses = container.jevRepo.localModelStatuses()
                                }) { Text(s.deleteWeightsBtn) }
                            }
                        }
                    }
                }
            }
        }
    }
}
