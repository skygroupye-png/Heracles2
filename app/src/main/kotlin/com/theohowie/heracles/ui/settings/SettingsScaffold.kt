package com.theohowie.heracles.ui.settings

import android.app.Application
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PrecisionManufacturing
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theohowie.heracles.AppContainer
import com.theohowie.heracles.BuildConfig
import com.theohowie.heracles.i18n.LocalStrings
import com.theohowie.heracles.model.AppAppearance
import com.theohowie.heracles.model.AppLanguage
import com.theohowie.heracles.model.AppSettings
import com.theohowie.heracles.model.AppThemeStyle
import com.theohowie.heracles.model.BusySendBehavior
import com.theohowie.heracles.model.ContinuePolicyMode
import com.theohowie.heracles.model.PermissionMode
import com.theohowie.heracles.model.SessionMeta
import com.theohowie.heracles.model.StepDetail
import com.theohowie.heracles.ui.theme.HeraclesBackground
import com.theohowie.heracles.ui.theme.LocalHeraclesColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val colors = LocalHeraclesColors.current
    val s = LocalStrings.current
    HeraclesBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text(title, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary) },
                    navigationIcon = {
                        IconButton(onClick = onBack, modifier = Modifier.minimumInteractiveComponentSize().testTag("nav_back_btn")) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = s.back, tint = colors.textPrimary)
                        }
                    },
                    actions = { actions() },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface.copy(alpha = 0.92f)),
                )
            },
            content = content,
        )
    }
}

@Composable
fun SettingsGroupCard(
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalHeraclesColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = colors.textMuted,
                modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
            )
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.cardBorder),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

@Composable
fun SettingsNavRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    tag: String,
    onClick: () -> Unit,
) {
    val colors = LocalHeraclesColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = title, tint = colors.accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = colors.textPrimary)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.textMuted)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsOverviewScreen(
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenDevice: () -> Unit,
    onOpenVoice: () -> Unit,
    onOpenSandbox: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenRobot: () -> Unit,
    onOpenJev: () -> Unit,
    onOpenArchived: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as Application
    val repo = remember { AppContainer.get(app).settingsRepo }
    val settings by repo.settings.collectAsStateWithLifecycle()
    val s = LocalStrings.current

    SettingsScaffold(title = s.settings, onBack = onBack) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    SettingsGroupCard(title = s.sectionAiModels) {
                        SettingsNavRow(
                            icon = Icons.Filled.Dns,
                            title = s.modelsAndProviders,
                            subtitle = settings.activeProvider?.let { "${it.name} · ${it.model}" } ?: s.notConfigured,
                            tag = "settings_models_row",
                            onClick = onOpenModels,
                        )
                        HorizontalDivider()
                        SettingsNavRow(
                            icon = Icons.Filled.Psychology,
                            title = s.jevDecisionModel,
                            subtitle = s.jevSubtitle,
                            tag = "settings_jev_row",
                            onClick = onOpenJev,
                        )
                        HorizontalDivider()
                        SettingsNavRow(
                            icon = Icons.Filled.Memory,
                            title = s.memorySystem,
                            subtitle = s.memorySubtitle,
                            tag = "settings_memory_row",
                            onClick = onOpenMemory,
                        )
                    }
                }

                item {
                    SettingsGroupCard(title = s.sectionAgentBehavior) {
                        Text(s.toolApprovalPolicy, style = MaterialTheme.typography.labelMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PermissionMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = settings.permissionMode == mode,
                                    onClick = { repo.updateSettings { it.copy(permissionMode = mode) } },
                                    label = {
                                        Text(
                                            when (mode) {
                                                PermissionMode.MANUAL -> s.permManual
                                                PermissionMode.WORKSPACE_AUTO -> s.permWorkspaceAuto
                                                PermissionMode.FULL_ACCESS -> s.permFullAccess
                                            },
                                        )
                                    },
                                )
                            }
                        }

                        Text(s.stepGranularity, style = MaterialTheme.typography.labelMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StepDetail.entries.forEach { d ->
                                FilterChip(
                                    selected = settings.stepDetail == d,
                                    onClick = { repo.updateSettings { it.copy(stepDetail = d) } },
                                    label = { Text(d.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                )
                            }
                        }

                        Text(s.sendWhileBusy, style = MaterialTheme.typography.labelMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BusySendBehavior.entries.forEach { b ->
                                FilterChip(
                                    selected = settings.busySendBehavior == b,
                                    onClick = { repo.updateSettings { it.copy(busySendBehavior = b) } },
                                    label = { Text(if (b == BusySendBehavior.QUEUE) s.busyQueue else s.busyInterrupt) },
                                )
                            }
                        }

                        Text(s.maxTurnsAndContinue, style = MaterialTheme.typography.labelMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppSettings.maxTurnOptions.forEach { t ->
                                FilterChip(
                                    selected = settings.maxTurns == t,
                                    onClick = { repo.updateSettings { it.copy(maxTurns = t) } },
                                    label = { Text(if (t == 0) "Auto (40)" else s.turnsUnit(t)) },
                                )
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ContinuePolicyMode.entries.forEach { cp ->
                                FilterChip(
                                    selected = settings.continuePolicy == cp,
                                    onClick = { repo.updateSettings { it.copy(continuePolicy = cp) } },
                                    label = {
                                        Text(
                                            when (cp) {
                                                ContinuePolicyMode.OFF -> s.continueOff
                                                ContinuePolicyMode.ASK -> s.continueAsk
                                                ContinuePolicyMode.AUTO -> s.continueAuto
                                            },
                                        )
                                    },
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(s.notifyOnCompleteLabel, style = MaterialTheme.typography.bodyMedium)
                            Switch(
                                checked = settings.notifyOnComplete,
                                onCheckedChange = { checked -> repo.updateSettings { it.copy(notifyOnComplete = checked) } },
                            )
                        }
                    }
                }

                item {
                    SettingsGroupCard(title = s.sectionSystemAppearance) {
                        SettingsNavRow(Icons.Filled.ColorLens, s.themeAndLanguage, "${settings.themeStyle.name} · ${settings.appearance.name} · ${settings.language.name}", "settings_appearance_row", onOpenAppearance)
                        HorizontalDivider()
                        SettingsNavRow(Icons.Filled.Terminal, s.linuxSandboxToolchain, s.sandboxSubtitle, "settings_sandbox_row", onOpenSandbox)
                        HorizontalDivider()
                        SettingsNavRow(Icons.Filled.PhoneAndroid, s.devicePermsKeepalive, s.devicePermsSubtitle, "settings_device_row", onOpenDevice)
                        HorizontalDivider()
                        SettingsNavRow(Icons.Filled.Mic, s.voiceModelsTitle, s.voiceModelsSubtitle, "settings_voice_row", onOpenVoice)
                        HorizontalDivider()
                        SettingsNavRow(Icons.Filled.Search, s.webSearchEndpoint, s.webSearchSubtitle, "settings_search_row", onOpenSearch)
                        HorizontalDivider()
                        SettingsNavRow(Icons.Filled.PrecisionManufacturing, s.robotGateway, s.robotSubtitle, "settings_robot_row", onOpenRobot)
                    }
                }

                item {
                    SettingsGroupCard(title = s.sectionDataAbout) {
                        SettingsNavRow(Icons.Filled.Archive, s.archivedSessions, s.archivedSubtitle, "settings_archived_row", onOpenArchived)
                        HorizontalDivider()
                        SettingsNavRow(Icons.Filled.Info, s.aboutHeracles, "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", "settings_about_row", onOpenAbout)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val repo = remember { AppContainer.get(app).settingsRepo }
    val settings by repo.settings.collectAsStateWithLifecycle()
    val s = LocalStrings.current

    SettingsScaffold(title = s.themeAndLanguage, onBack = onBack) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    SettingsGroupCard(title = s.visualStyle) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppThemeStyle.entries.forEach { st ->
                                FilterChip(
                                    selected = settings.themeStyle == st,
                                    onClick = { repo.updateSettings { it.copy(themeStyle = st) } },
                                    label = { Text(st.name) },
                                )
                            }
                        }
                    }
                }
                item {
                    SettingsGroupCard(title = s.lightDarkMode) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppAppearance.entries.forEach { ap ->
                                FilterChip(
                                    selected = settings.appearance == ap,
                                    onClick = { repo.updateSettings { it.copy(appearance = ap) } },
                                    label = { Text(ap.name) },
                                )
                            }
                        }
                    }
                }
                item {
                    SettingsGroupCard(title = s.interfaceLanguage) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppLanguage.entries.forEach { lang ->
                                FilterChip(
                                    selected = settings.language == lang,
                                    onClick = { repo.updateSettings { it.copy(language = lang) } },
                                    label = {
                                        Text(
                                            when (lang) {
                                                AppLanguage.AR -> "العربية"
                                                AppLanguage.EN -> "English"
                                                AppLanguage.ZH -> "简体中文"
                                            },
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ArchivedSessionsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val container = remember { AppContainer.get(app) }
    val scope = rememberCoroutineScope()
    val s = LocalStrings.current
    var archived by remember { mutableStateOf<List<Pair<String, SessionMeta>>>(emptyList()) }

    fun reload() {
        scope.launch {
            val all = mutableListOf<Pair<String, SessionMeta>>()
            for (ws in container.workspaceRegistry.list()) {
                val list = container.sessionStore.listSessions(ws.path, includeArchived = true)
                list.filter { it.archived }.forEach { all += ws.name to it }
            }
            archived = all
        }
    }

    LaunchedEffect(Unit) { reload() }

    SettingsScaffold(title = s.archivedSessions, onBack = onBack) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            if (archived.isEmpty()) {
                Text(s.noArchivedSessions, modifier = Modifier.padding(32.dp), color = LocalHeraclesColors.current.textMuted)
            } else {
                LazyColumn(
                    modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(archived, key = { it.second.id }) { (wsName, sess) ->
                        SettingsGroupCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(sess.title, style = MaterialTheme.typography.titleMedium)
                                    Text("$wsName · ${s.recordsCount(sess.messageCount)}", style = MaterialTheme.typography.bodySmall, color = LocalHeraclesColors.current.textMuted)
                                }
                                IconButton(onClick = {
                                    scope.launch {
                                        container.sessionStore.setArchived(sess.workspacePath, sess.id, false)
                                        reload()
                                    }
                                }) {
                                    Icon(Icons.Filled.RestoreFromTrash, contentDescription = s.restore)
                                }
                                IconButton(onClick = {
                                    scope.launch {
                                        container.sessionStore.deleteSession(sess.workspacePath, sess.id)
                                        reload()
                                    }
                                }) {
                                    Icon(Icons.Filled.DeleteOutline, contentDescription = s.deleteForever)
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
fun AboutScreen(
    onBack: () -> Unit,
    onOpenLicenses: () -> Unit,
) {
    val s = LocalStrings.current
    SettingsScaffold(title = s.aboutTitle, onBack = onBack) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    SettingsGroupCard {
                        Text("Heracles for Android", style = MaterialTheme.typography.headlineSmall)
                        Text(s.versionLabel(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE), style = MaterialTheme.typography.bodyMedium, color = LocalHeraclesColors.current.textMuted)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            s.aboutDescription,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                item {
                    SettingsGroupCard {
                        SettingsNavRow(Icons.Filled.Info, s.ossLicensesRow, s.ossLicensesSubtitle, "about_licenses_row", onOpenLicenses)
                    }
                }
            }
        }
    }
}

@Composable
fun OssLicensesScreen(onBack: () -> Unit) {
    val s = LocalStrings.current
    val libs = remember {
        listOf(
            "AndroidX / Jetpack Compose / Navigation" to "Apache License 2.0",
            "Kotlin & Kotlinx Coroutines / Serialization" to "Apache License 2.0",
            "Square OkHttp & OkHttp-SSE" to "Apache License 2.0",
            "Eclipse JGit" to "Eclipse Distribution License 1.0",
            "Coil Image Loading" to "Apache License 2.0",
            "Rikka Shizuku API & Provider" to "Apache License 2.0",
            "Stellar API" to "Open Source API Wrapper",
            "Bundled Zipformer Speech Model Assets" to "Apache License 2.0",
        )
    }
    SettingsScaffold(title = s.ossLicensesRow, onBack = onBack) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(libs) { (name, license) ->
                    SettingsGroupCard {
                        Text(name, style = MaterialTheme.typography.titleMedium)
                        Text(license, style = MaterialTheme.typography.bodySmall, color = LocalHeraclesColors.current.textMuted)
                    }
                }
            }
        }
    }
}
