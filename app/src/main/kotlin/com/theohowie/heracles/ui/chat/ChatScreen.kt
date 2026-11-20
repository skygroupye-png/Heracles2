package com.theohowie.heracles.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theohowie.heracles.agent.HeraclesAgent
import com.theohowie.heracles.i18n.LocalStrings
import com.theohowie.heracles.model.ApprovalRequest
import com.theohowie.heracles.model.AskQuestion
import com.theohowie.heracles.ui.theme.HeraclesBackground
import com.theohowie.heracles.ui.theme.JetBrainsMonoFamily
import com.theohowie.heracles.ui.theme.LocalHeraclesColors
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onPickWorkspaceFolder: () -> Unit,
    onPickPhoto: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickFile: () -> Unit,
    onRequestVoiceAndToggle: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenArchived: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenMcp: () -> Unit,
    onOpenAutomation: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenJevSettings: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val agentState by vm.agentState.collectAsStateWithLifecycle()
    val jevConfig by vm.jevConfig.collectAsStateWithLifecycle()
    val jevHistory by vm.jevHistory.collectAsStateWithLifecycle()
    val githubUser by vm.githubUser.collectAsStateWithLifecycle()

    val colors = LocalHeraclesColors.current
    val s = LocalStrings.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var showModelSheet by remember { mutableStateOf(false) }
    var showGitSheet by remember { mutableStateOf(false) }

    LaunchedEffect(ui.toastMessage) {
        val msg = ui.toastMessage ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        vm.clearToast()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isExpanded = maxWidth >= 840.dp

        val drawerBody: @Composable (Modifier) -> Unit = { mod ->
            WorkspaceDrawerContent(
                workspaces = ui.workspaces,
                activeWorkspace = ui.activeWorkspace,
                sessions = ui.sessions,
                activeSession = ui.activeSession,
                onSelectWorkspace = {
                    vm.selectWorkspace(it)
                    scope.launch { drawerState.close() }
                },
                onPickFolder = onPickWorkspaceFolder,
                onNewSession = {
                    vm.createNewSession()
                    scope.launch { drawerState.close() }
                },
                onSelectSession = {
                    vm.openSession(it)
                    scope.launch { drawerState.close() }
                },
                onRenameSession = vm::renameSession,
                onArchiveSession = vm::archiveSession,
                onDeleteSession = vm::deleteSession,
                onOpenArchived = {
                    scope.launch { drawerState.close() }
                    onOpenArchived()
                },
                onOpenSkills = {
                    scope.launch { drawerState.close() }
                    onOpenSkills()
                },
                onOpenMcp = {
                    scope.launch { drawerState.close() }
                    onOpenMcp()
                },
                onOpenAutomation = {
                    scope.launch { drawerState.close() }
                    onOpenAutomation()
                },
                onOpenSettings = {
                    scope.launch { drawerState.close() }
                    onOpenSettings()
                },
                modifier = mod,
            )
        }

        val mainContent: @Composable () -> Unit = {
            HeraclesBackground {
                Scaffold(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    snackbarHost = { SnackbarHost(snackbar) },
                    topBar = {
                        ChatTopBar(
                            showMenuIcon = !isExpanded,
                            workspaceName = ui.activeWorkspace?.name ?: s.defaultWorkspace,
                            sessionTitle = ui.activeSession?.title ?: s.appName,
                            modelLabel = settings.activeProvider?.model ?: s.configureModel,
                            gitBranch = if (ui.gitStatus.isRepo) ui.gitStatus.branch else null,
                            mode = ui.mode,
                            onOpenMenu = { scope.launch { drawerState.open() } },
                            onSelectMode = vm::setMode,
                            onOpenModelSheet = { showModelSheet = true },
                            onOpenGitSheet = {
                                vm.refreshGitStatus()
                                showGitSheet = true
                            },
                            onOpenSettings = onOpenSettings,
                        )
                    },
                    bottomBar = {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            agentState.pendingApproval?.let { req ->
                                ApprovalCard(
                                    req = req,
                                    onDecision = vm::resolveApproval,
                                )
                            }
                            agentState.pendingAsk?.let { q ->
                                AskUserCard(question = q, onAnswer = vm::resolveAsk)
                            }
                            ChatInputBar(
                                inputText = ui.inputText,
                                attachments = ui.attachments,
                                busy = agentState.busy,
                                busyBehavior = settings.busySendBehavior,
                                isRecordingVoice = ui.isRecordingVoice,
                                voiceTranscript = ui.voiceTranscript,
                                mode = ui.mode,
                                onInputChange = vm::updateInputText,
                                onSend = vm::sendCurrentInput,
                                onStop = vm::stopAgent,
                                onToggleVoice = onRequestVoiceAndToggle,
                                onPickPhoto = onPickPhoto,
                                onTakePhoto = onTakePhoto,
                                onPickFile = onPickFile,
                                onRemoveAttachment = vm::removeAttachment,
                            )
                        }
                    },
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    ) {
                        if (ui.processKilledPrompt) {
                            RecoveryBanner(
                                text = s.processKilledBanner,
                                primaryLabel = s.resumeRun,
                                secondaryLabel = s.discardRun,
                                onPrimary = vm::resumeAfterProcessKilled,
                                onSecondary = vm::dismissProcessKilledBanner,
                            )
                        }
                        if (agentState.stopReason == "max_turns") {
                            RecoveryBanner(
                                text = s.maxTurnsBanner(settings.effectiveMaxTurns),
                                primaryLabel = s.resumeRun,
                                secondaryLabel = s.discardRun,
                                onPrimary = {
                                    vm.clearStopReason()
                                    vm.updateInputText(s.continuePromptMsg)
                                    vm.sendCurrentInput()
                                },
                                onSecondary = vm::clearStopReason,
                            )
                        }

                        DynamicIsland(
                            busy = agentState.busy,
                            queuedCount = agentState.queuedCount,
                            planSteps = agentState.planSteps,
                        )

                        if (ui.mode == ChatViewModel.TopMode.CHAT) {
                            MessageList(
                                messages = ui.messages,
                                activeAssistant = agentState.activeAssistant,
                                hasEarlier = ui.hasEarlier,
                                stepDetail = settings.stepDetail,
                                hasApiKey = vm.hasActiveProviderApiKey(),
                                onLoadEarlier = vm::loadEarlierMessages,
                                onOpenModelsSettings = onOpenModels,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            DecisionModeView(
                                config = jevConfig,
                                latest = ui.jevLatest,
                                history = jevHistory,
                                busy = ui.jevBusy,
                                error = ui.jevError,
                                onSelectSource = vm::selectJevSource,
                                onOpenJevSettings = onOpenJevSettings,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }

        if (isExpanded) {
            Row(modifier = Modifier.fillMaxSize()) {
                drawerBody(Modifier.width(300.dp).fillMaxHeight())
                VerticalDivider(color = colors.cardBorder)
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    mainContent()
                }
            }
        } else {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet(drawerContainerColor = colors.surface) {
                        drawerBody(Modifier.width(300.dp))
                    }
                },
            ) {
                mainContent()
            }
        }
    }

    if (showModelSheet) {
        ModelAndUsageSheet(
            settings = settings,
            usage = ui.usage,
            onSelectModel = vm::selectProviderAndModel,
            onSelectReasoning = vm::updateReasoningLevel,
            onOpenModelsSettings = onOpenModels,
            onDismiss = { showModelSheet = false },
        )
    }

    if (showGitSheet) {
        GitAndGitHubSheet(
            status = ui.gitStatus,
            branches = ui.gitBranches,
            commits = ui.gitCommits,
            diff = ui.gitDiff,
            gitBusy = ui.gitBusy,
            githubUser = githubUser,
            deviceCode = ui.githubDeviceCode,
            repos = ui.githubRepos,
            onRefresh = vm::refreshGitStatus,
            onInitRepo = vm::gitInit,
            onCommit = vm::gitCommitAll,
            onPush = vm::gitPush,
            onPull = vm::gitPull,
            onCheckout = { vm.gitCheckout(it) },
            onStartGitHubLogin = vm::startGitHubDeviceFlow,
            onOpenVerificationUrl = onOpenUrl,
            onLoadRepos = vm::loadGitHubRepos,
            onCloneRepo = vm::cloneGitHubRepo,
            onSignOutGitHub = vm::signOutGitHub,
            onDismiss = { showGitSheet = false },
        )
    }
}

@Composable
private fun RecoveryBanner(
    text: String,
    primaryLabel: String,
    secondaryLabel: String,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
) {
    val colors = LocalHeraclesColors.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.warning.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, colors.warning.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = colors.textPrimary, modifier = Modifier.weight(1f))
            TextButton(onClick = onPrimary) { Text(primaryLabel) }
            TextButton(onClick = onSecondary) { Text(secondaryLabel) }
        }
    }
}

@Composable
private fun ApprovalCard(
    req: ApprovalRequest,
    onDecision: (HeraclesAgent.ApprovalDecision) -> Unit,
) {
    val colors = LocalHeraclesColors.current
    val s = LocalStrings.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.surface,
        border = BorderStroke(1.5.dp, if (req.isHighRisk) colors.danger else colors.warning),
        tonalElevation = 6.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("approval_card"),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                if (req.isHighRisk) s.highRiskApprovalTitle(req.toolName) else s.toolApprovalTitle(req.toolName),
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (req.isHighRisk) colors.danger else colors.warning,
            )
            Spacer(Modifier.height(4.dp))
            Text(req.summary, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = colors.textPrimary)
            Spacer(Modifier.height(4.dp))
            Text(
                req.details.take(280),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                fontFamily = JetBrainsMonoFamily,
                color = colors.textMuted,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onDecision(HeraclesAgent.ApprovalDecision.ALLOW_ONCE) },
                    modifier = Modifier.testTag("approval_allow_once"),
                ) { Text(s.approve) }
                OutlinedButton(
                    onClick = { onDecision(HeraclesAgent.ApprovalDecision.ALLOW_ALWAYS) },
                    modifier = Modifier.testTag("approval_allow_always"),
                ) { Text(s.allowAlways) }
                TextButton(
                    onClick = { onDecision(HeraclesAgent.ApprovalDecision.DENY) },
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.danger),
                    modifier = Modifier.testTag("approval_deny"),
                ) { Text(s.deny) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AskUserCard(
    question: AskQuestion,
    onAnswer: (String) -> Unit,
) {
    val colors = LocalHeraclesColors.current
    val s = LocalStrings.current
    var freeText by remember(question.id) { mutableStateOf("") }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.surface,
        border = BorderStroke(1.5.dp, colors.accent),
        tonalElevation = 6.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("ask_user_card"),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(question.prompt, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
            if (question.options.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    question.options.forEach { opt ->
                        OutlinedButton(onClick = { onAnswer(opt.label) }) { Text(opt.label) }
                    }
                }
            }
            if (question.allowFreeText) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = freeText,
                        onValueChange = { freeText = it },
                        placeholder = { Text(s.replyHint) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { if (freeText.isNotBlank()) onAnswer(freeText.trim()) }) { Text(s.submit) }
                }
            }
        }
    }
}
