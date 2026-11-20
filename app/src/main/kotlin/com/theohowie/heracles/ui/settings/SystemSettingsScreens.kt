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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theohowie.heracles.AppContainer
import com.theohowie.heracles.bridge.AgentChatService
import com.theohowie.heracles.bridge.DevicePermissionChecker
import com.theohowie.heracles.bridge.DeviceShellManager
import com.theohowie.heracles.bridge.MemoryStore
import com.theohowie.heracles.bridge.SandboxManager
import com.theohowie.heracles.data.ScopedMemoryService
import com.theohowie.heracles.data.VoiceModelRepository
import com.theohowie.heracles.i18n.LocalStrings
import com.theohowie.heracles.ui.theme.JetBrainsMonoFamily
import com.theohowie.heracles.ui.theme.LocalHeraclesColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemoryScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val container = remember { AppContainer.get(app) }
    val scope = rememberCoroutineScope()
    val colors = LocalHeraclesColors.current
    val s = LocalStrings.current
    val wsPath = remember {
        container.settingsRepo.getLastWorkspacePath().ifBlank {
            container.workspaceRegistry.ensureAppWorkspace()?.path.orEmpty()
        }
    }

    var activeScope by remember { mutableStateOf("global") }
    var globalEntries by remember { mutableStateOf<List<MemoryStore.MemoryItem>>(emptyList()) }
    var proposals by remember { mutableStateOf<List<MemoryStore.ProposalItem>>(emptyList()) }
    var scopedEntries by remember { mutableStateOf<List<ScopedMemoryService.ScopedEntry>>(emptyList()) }
    var newNote by remember { mutableStateOf("") }
    var newKind by remember { mutableStateOf("preference") }
    var notice by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch {
            globalEntries = container.memoryStore.list()
            proposals = container.memoryStore.listProposals("pending")
            scopedEntries = if (activeScope == "workspace") {
                container.scopedMemory.listScoped("workspace", wsPath, null)
            } else {
                emptyList()
            }
        }
    }

    LaunchedEffect(activeScope) { reload() }

    SettingsScaffold(title = s.memorySystem, onBack = onBack) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = activeScope == "global", onClick = { activeScope = "global" }, label = { Text(s.globalMemoryTab(globalEntries.size)) })
                        FilterChip(selected = activeScope == "workspace", onClick = { activeScope = "workspace" }, label = { Text(s.workspaceMemoryTab) })
                        FilterChip(selected = activeScope == "proposals", onClick = { activeScope = "proposals" }, label = { Text(s.pendingProposalsTab(proposals.size)) })
                    }
                }

                if (activeScope != "proposals") {
                    item {
                        SettingsGroupCard(title = s.addMemoryTitle) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                MemoryStore.KINDS.forEach { k ->
                                    FilterChip(selected = newKind == k, onClick = { newKind = k }, label = { Text(k) })
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = newNote,
                                    onValueChange = { newNote = it },
                                    label = { Text(s.memoryContentHint) },
                                    modifier = Modifier.weight(1f).testTag("memory_add_input"),
                                )
                                Spacer(Modifier.width(8.dp))
                                Button(onClick = {
                                    scope.launch {
                                        if (activeScope == "global") {
                                            val r = container.memoryStore.userEdit("add", text = newNote, kind = newKind)
                                            notice = if (r["ok"] == true) s.successLabel else r["error"]?.toString()
                                            if (r["ok"] == true) newNote = ""
                                        } else {
                                            val r = container.scopedMemory.saveScoped("workspace", wsPath, null, newKind, newNote)
                                            notice = r.fold({ newNote = ""; s.successLabel }, { it.message })
                                        }
                                        reload()
                                    }
                                }) { Text(s.writeAction) }
                            }
                            if (activeScope == "global") {
                                TextButton(onClick = {
                                    scope.launch {
                                        val r = container.memoryStore.migrate()
                                        notice = "entries=${r["entries"] ?: 0}, migrated=${r["migrated"]}"
                                        reload()
                                    }
                                }) { Text(s.migrateLegacyMemory) }
                            }
                            notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.accent) }
                        }
                    }
                }

                when (activeScope) {
                    "global" -> items(globalEntries, key = { it.id }) { item ->
                        SettingsGroupCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("[${item.kind}] · ${item.source}", style = MaterialTheme.typography.labelMedium, color = colors.accent)
                                    Text(item.text, style = MaterialTheme.typography.bodyMedium)
                                }
                                IconButton(onClick = {
                                    scope.launch {
                                        container.memoryStore.userEdit("delete", id = item.id)
                                        reload()
                                    }
                                }) { Icon(Icons.Filled.DeleteOutline, contentDescription = s.delete) }
                            }
                        }
                    }

                    "workspace" -> items(scopedEntries, key = { it.id }) { item ->
                        SettingsGroupCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("[${item.kind}] · ${s.workspaceLabel}", style = MaterialTheme.typography.labelMedium, color = colors.accent)
                                    Text(item.text, style = MaterialTheme.typography.bodyMedium)
                                }
                                IconButton(onClick = {
                                    scope.launch {
                                        container.scopedMemory.deleteScoped("workspace", wsPath, null, item.id)
                                        reload()
                                    }
                                }) { Icon(Icons.Filled.DeleteOutline, contentDescription = s.delete) }
                            }
                        }
                    }

                    else -> items(proposals, key = { it.id }) { prop ->
                        SettingsGroupCard {
                            Text("[${prop.kind}] ${s.agentProposalBadge}", style = MaterialTheme.typography.labelMedium, color = colors.warning)
                            Text(prop.text, style = MaterialTheme.typography.bodyMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    scope.launch {
                                        container.memoryStore.resolveProposal(prop.id, "promote")
                                        reload()
                                    }
                                }) {
                                    Icon(Icons.Filled.Check, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text(s.promoteToGlobal)
                                }
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        container.memoryStore.resolveProposal(prop.id, "reject")
                                        reload()
                                    }
                                }) {
                                    Icon(Icons.Filled.Close, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text(s.rejectAction)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DevicePermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = LocalHeraclesColors.current
    val s = LocalStrings.current
    var diag by remember { mutableStateOf(DeviceShellManager.diagnostics()) }
    var keepalive by remember { mutableStateOf(AgentChatService.keepaliveStatus(context)) }
    var hasSecure by remember { mutableStateOf(DevicePermissionChecker.hasWriteSecureSettings(context)) }
    var actionMsg by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        diag = DeviceShellManager.diagnostics()
        keepalive = AgentChatService.keepaliveStatus(context)
        hasSecure = DevicePermissionChecker.hasWriteSecureSettings(context)
    }

    SettingsScaffold(
        title = s.devicePermsKeepalive,
        onBack = onBack,
        actions = {
            IconButton(onClick = { refresh() }) {
                Icon(Icons.Filled.Refresh, contentDescription = s.refresh)
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    SettingsGroupCard(title = s.privilegedShellTitle) {
                        Text(s.currentRouteLabel(diag.route.label), style = MaterialTheme.typography.titleMedium, color = colors.accent)
                        Text("Stellar: installed=${diag.stellarInstalled} · running=${diag.stellarRunning} · granted=${diag.stellarGranted} · uid=${diag.stellarUid}", style = MaterialTheme.typography.bodySmall)
                        Text("Shizuku: installed=${diag.shizukuInstalled} · running=${diag.shizukuPing} · granted=${diag.shizukuGranted}", style = MaterialTheme.typography.bodySmall)
                        Button(
                            onClick = {
                                DeviceShellManager.forceRequestPermission { granted, msg ->
                                    actionMsg = msg
                                    refresh()
                                }
                            },
                            modifier = Modifier.testTag("request_shell_perm_btn"),
                        ) { Text(s.requestShellPermBtn) }
                        actionMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.accent) }
                    }
                }

                item {
                    SettingsGroupCard(title = s.writeSecureDiagTitle) {
                        Text(
                            "WRITE_SECURE_SETTINGS: ${if (hasSecure) s.grantedLabel else s.notGrantedLabel}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (hasSecure) colors.success else colors.warning,
                        )
                        if (!hasSecure) {
                            Text(DevicePermissionChecker.guidance(context), style = MaterialTheme.typography.bodySmall, fontFamily = JetBrainsMonoFamily, color = colors.textMuted)
                        }
                    }
                }

                item {
                    SettingsGroupCard(title = s.keepaliveOemTitle(keepalive.autostartBrand)) {
                        Text(s.batteryWhitelistLabel(keepalive.batteryIgnored), style = MaterialTheme.typography.bodyMedium)
                        keepalive.batteryNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.warning) }
                        if (!keepalive.batteryIgnored) {
                            OutlinedButton(onClick = {
                                AgentChatService.requestIgnoreBatteryOptimizations(context)
                                refresh()
                            }) { Text(s.requestBatteryWhitelist) }
                        }
                        Text(s.autostartPathLabel(keepalive.autostartPath), style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                        AgentChatService.oemGuide("backgroundPopup")?.let {
                            Text(s.backgroundPopupLabel(it.second), style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                        }
                        AgentChatService.oemGuide("lockScreenShow")?.let {
                            Text(s.lockScreenLabel(it.second), style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun VoiceSettingsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val container = remember { AppContainer.get(app) }
    val statuses by container.voiceRepo.statuses.collectAsStateWithLifecycle()
    val dl by container.voiceRepo.downloadState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(container.voiceRepo.isVoiceEnabled()) }
    val colors = LocalHeraclesColors.current
    val s = LocalStrings.current

    SettingsScaffold(title = s.voiceAssetsTitle, onBack = onBack) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    SettingsGroupCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(s.enableVoiceLabel, style = MaterialTheme.typography.titleMedium)
                                Text(s.enableVoiceSubtitle, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                            }
                            Switch(checked = enabled, onCheckedChange = {
                                enabled = it
                                container.voiceRepo.setVoiceEnabled(it)
                            })
                        }
                    }
                }
                if (dl.active) {
                    item {
                        SettingsGroupCard {
                            Text(dl.message, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(4.dp))
                            LinearProgressIndicator(progress = { dl.percent / 100f }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                items(statuses, key = { it.model.id }) { st ->
                    SettingsGroupCard {
                        Text(st.model.displayName, style = MaterialTheme.typography.titleMedium)
                        Text("${st.model.subtitle} · ${s.diskMbLabel(st.bytesOnDisk / 1024 / 1024)}", style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!st.installed) {
                                Button(onClick = {
                                    scope.launch { container.voiceRepo.downloadOptionalModel(st.model) }
                                }) { Text(if (st.model == VoiceModelRepository.ModelId.BUNDLED_ZIPFORMER_ZH) s.restoreBundled else s.downloadModel) }
                            } else {
                                if (!st.isActive) {
                                    Button(onClick = { container.voiceRepo.setActiveModel(st.model.id) }) { Text(s.setDefault) }
                                } else {
                                    Text(s.inUseLabel, style = MaterialTheme.typography.labelMedium, color = colors.accent, modifier = Modifier.align(Alignment.CenterVertically))
                                }
                                OutlinedButton(onClick = { container.voiceRepo.uninstallModel(st.model) }) { Text(s.uninstall) }
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
fun SandboxScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val container = remember { AppContainer.get(app) }
    val progress by container.sandboxManager.progress.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val colors = LocalHeraclesColors.current
    val s = LocalStrings.current
    var status by remember { mutableStateOf<SandboxManager.SandboxStatus?>(null) }
    var actionNote by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch { status = container.sandboxManager.status() }
    }

    LaunchedEffect(Unit) { reload() }

    SettingsScaffold(
        title = s.linuxSandboxToolchain,
        onBack = onBack,
        actions = {
            IconButton(onClick = { reload() }) { Icon(Icons.Filled.Refresh, contentDescription = s.refresh) }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (progress.active) {
                    item {
                        SettingsGroupCard {
                            Text(progress.message, style = MaterialTheme.typography.bodySmall)
                            if (progress.percent >= 0) {
                                LinearProgressIndicator(progress = { progress.percent / 100f }, modifier = Modifier.fillMaxWidth())
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }

                status?.let { st ->
                    item {
                        SettingsGroupCard(title = s.prootRuntimeTitle) {
                            Text(s.rootfsLabel(st.rootfsSummary), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(s.prootBinaryLabel(st.prootReady, st.ready), style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                            if (st.networkRiskNote.isNotBlank()) {
                                Text(st.networkRiskNote, style = MaterialTheme.typography.bodySmall, color = colors.warning)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (!st.installed) {
                                    Button(onClick = {
                                        scope.launch {
                                            val ok = container.sandboxManager.activate()
                                            actionNote = if (ok) s.successLabel else s.failLabel
                                            reload()
                                        }
                                    }, modifier = Modifier.testTag("activate_sandbox_btn")) { Text(s.activateSandbox) }
                                } else {
                                    OutlinedButton(onClick = {
                                        scope.launch {
                                            container.sandboxManager.createSnapshot("snap_${System.currentTimeMillis() / 1000}")
                                            reload()
                                        }
                                    }) { Text(s.createSnapshot) }
                                    OutlinedButton(onClick = {
                                        scope.launch {
                                            container.sandboxManager.resetRootfs()
                                            reload()
                                        }
                                    }) { Text(s.resetSandbox) }
                                }
                            }
                        }
                    }

                    item {
                        SettingsGroupCard(title = s.buildSuiteTitle) {
                            Text(s.buildSuiteStatus(st.buildSuiteReady, st.buildSuiteVersion), style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = {
                                scope.launch {
                                    val (_, msg) = container.sandboxManager.activateBuildSuite()
                                    actionNote = msg
                                    reload()
                                }
                            }) { Text(s.activateRepairBuildSuite) }
                            Spacer(Modifier.height(6.dp))
                            Text(s.apkRecipesHint, style = MaterialTheme.typography.labelMedium, color = colors.textMuted)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                container.sandboxManager.availableEnvRecipes().forEach { env ->
                                    val installed = env in st.installedEnvs
                                    FilterChip(
                                        selected = installed,
                                        onClick = {
                                            scope.launch {
                                                val (_, msg) = if (installed) container.sandboxManager.uninstallEnv(env) else container.sandboxManager.installEnv(env)
                                                actionNote = msg
                                                reload()
                                            }
                                        },
                                        label = { Text(if (installed) "$env ✓" else env) },
                                    )
                                }
                            }
                            actionNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.accent) }
                        }
                    }

                    item {
                        SettingsGroupCard(title = s.mountsTitle(st.mounts.size)) {
                            st.mounts.forEach { m ->
                                Text("${m.name} → ${m.hostPath} (${if (m.readOnly) "RO" else "RW"})", style = MaterialTheme.typography.bodySmall, fontFamily = JetBrainsMonoFamily)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SearchSettingsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val container = remember { AppContainer.get(app) }
    val cfg by container.searchRepo.config.collectAsStateWithLifecycle()
    var url by remember(cfg.endpointUrl) { mutableStateOf(cfg.endpointUrl) }
    var notice by remember { mutableStateOf<String?>(null) }
    val colors = LocalHeraclesColors.current
    val s = LocalStrings.current

    SettingsScaffold(title = s.webSearchEndpoint, onBack = onBack) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    SettingsGroupCard(title = s.searxngTitle) {
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            label = { Text(s.searxngInputHint) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("search_endpoint_input"),
                        )
                        Text(s.searxngHelp, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                        Button(onClick = {
                            val r = container.searchRepo.saveEndpoint(url)
                            notice = r.fold({ s.successLabel }, { it.message ?: s.failLabel })
                        }) { Text(s.saveConfigBtn) }
                        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.accent) }
                    }
                }
            }
        }
    }
}

@Composable
fun RobotAccessScreen(onBack: () -> Unit) {
    val colors = LocalHeraclesColors.current
    val s = LocalStrings.current
    var gatewayUrl by remember { mutableStateOf("") }
    var savedMsg by remember { mutableStateOf<String?>(null) }

    SettingsScaffold(title = s.robotGateway, onBack = onBack) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    SettingsGroupCard(title = s.connectionStatusTitle) {
                        Text(s.robotUnconnected, style = MaterialTheme.typography.titleMedium, color = colors.warning)
                        Text(s.robotDescription, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                        OutlinedTextField(
                            value = gatewayUrl,
                            onValueChange = { gatewayUrl = it },
                            label = { Text(s.robotGatewayLabel) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(onClick = {
                            savedMsg = if (gatewayUrl.startsWith("ws://") || gatewayUrl.startsWith("wss://")) {
                                s.successLabel
                            } else {
                                "ws:// / wss:// URL"
                            }
                        }) { Text(s.saveGatewayBtn) }
                        savedMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.accent) }
                    }
                }
            }
        }
    }
}
