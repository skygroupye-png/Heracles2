package com.theohowie.heracles.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.theohowie.heracles.R
import com.theohowie.heracles.i18n.LocalStrings
import com.theohowie.heracles.model.AssistantMsg
import com.theohowie.heracles.model.AttachmentKind
import com.theohowie.heracles.model.ChatMessage
import com.theohowie.heracles.model.PlanStep
import com.theohowie.heracles.model.StepDetail
import com.theohowie.heracles.model.ToolCallItem
import com.theohowie.heracles.model.ToolCallStatus
import com.theohowie.heracles.model.UserMsg
import com.theohowie.heracles.ui.theme.JetBrainsMonoFamily
import com.theohowie.heracles.ui.theme.LocalHeraclesColors
import java.io.File

@Composable
fun ChatTopBar(
    showMenuIcon: Boolean,
    workspaceName: String,
    sessionTitle: String,
    modelLabel: String,
    gitBranch: String?,
    mode: ChatViewModel.TopMode,
    onOpenMenu: () -> Unit,
    onSelectMode: (ChatViewModel.TopMode) -> Unit,
    onOpenModelSheet: () -> Unit,
    onOpenGitSheet: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = LocalHeraclesColors.current
    val s = LocalStrings.current

    Surface(
        color = colors.surface.copy(alpha = 0.92f),
        border = BorderStroke(0.5.dp, colors.cardBorder),
    ) {
        Column(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showMenuIcon) {
                    IconButton(
                        onClick = onOpenMenu,
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("open_drawer_btn"),
                    ) {
                        Icon(Icons.Filled.Menu, contentDescription = s.workspaces, tint = colors.textPrimary)
                    }
                }
                Column(modifier = Modifier.weight(1f).padding(horizontal = 6.dp)) {
                    Text(
                        sessionTitle.ifBlank { s.appName },
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        workspaceName,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = colors.surfaceAlt,
                    border = BorderStroke(1.dp, colors.cardBorder),
                ) {
                    Row(modifier = Modifier.padding(2.dp)) {
                        ModePill(
                            text = s.chatTab,
                            selected = mode == ChatViewModel.TopMode.CHAT,
                            tag = "mode_chat_tab",
                            onClick = { onSelectMode(ChatViewModel.TopMode.CHAT) },
                        )
                        ModePill(
                            text = s.decisionTab,
                            selected = mode == ChatViewModel.TopMode.DECISION,
                            tag = "mode_jev_tab",
                            onClick = { onSelectMode(ChatViewModel.TopMode.DECISION) },
                        )
                    }
                }

                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.minimumInteractiveComponentSize().testTag("topbar_settings_btn"),
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = s.settings, tint = colors.textPrimary)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    onClick = onOpenModelSheet,
                    shape = RoundedCornerShape(8.dp),
                    color = colors.surfaceAlt,
                    border = BorderStroke(1.dp, colors.cardBorder),
                    modifier = Modifier.testTag("topbar_model_chip"),
                ) {
                    Text(
                        modelLabel.ifBlank { s.configureModel },
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }

                Surface(
                    onClick = onOpenGitSheet,
                    shape = RoundedCornerShape(8.dp),
                    color = colors.surfaceAlt,
                    border = BorderStroke(1.dp, colors.cardBorder),
                    modifier = Modifier.testTag("topbar_git_chip"),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.AccountTree, contentDescription = s.gitPanel, tint = colors.accent, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            gitBranch ?: "Git",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.textPrimary,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModePill(text: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    val colors = LocalHeraclesColors.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) colors.accent else colors.surfaceAlt,
        modifier = Modifier.testTag(tag),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) colors.onAccent else colors.textMuted,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
fun DynamicIsland(
    busy: Boolean,
    queuedCount: Int,
    planSteps: List<PlanStep>,
) {
    if (!busy && planSteps.isEmpty() && queuedCount == 0) return
    val colors = LocalHeraclesColors.current
    val s = LocalStrings.current
    var expanded by remember { mutableStateOf(false) }
    val doneCount = planSteps.count { it.status == "completed" }

    Surface(
        onClick = { if (planSteps.isNotEmpty()) expanded = !expanded },
        shape = RoundedCornerShape(16.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.45f)),
        tonalElevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .testTag("dynamic_island"),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = colors.accent,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    buildString {
                        append(if (busy) s.runningTitle else s.planTitle)
                        if (planSteps.isNotEmpty()) append(" · $doneCount/${planSteps.size}")
                        if (queuedCount > 0) append(" · ${s.queueTag(queuedCount)}")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (planSteps.isNotEmpty()) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = colors.textMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            AnimatedVisibility(visible = expanded && planSteps.isNotEmpty()) {
                Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    planSteps.forEach { step ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val tint = when (step.status) {
                                "completed" -> colors.success
                                "in_progress" -> colors.accent
                                else -> colors.textMuted
                            }
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(step.title, style = MaterialTheme.typography.bodySmall, color = colors.textPrimary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MessageList(
    messages: List<ChatMessage>,
    activeAssistant: AssistantMsg?,
    hasEarlier: Boolean,
    stepDetail: StepDetail,
    hasApiKey: Boolean,
    onLoadEarlier: () -> Unit,
    onOpenModelsSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHeraclesColors.current
    val s = LocalStrings.current
    val listState = rememberLazyListState()
    val allItems = remember(messages, activeAssistant) {
        if (activeAssistant == null) messages else messages.filter { it.id != activeAssistant.id } + activeAssistant
    }

    LaunchedEffect(allItems.size, activeAssistant?.text?.length) {
        if (allItems.isNotEmpty()) {
            listState.scrollToItem(allItems.lastIndex)
        }
    }

    if (allItems.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.widthIn(max = 420.dp).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painter = painterResource(R.drawable.img_heracles_hero),
                    contentDescription = s.appName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(116.dp)
                        .clip(RoundedCornerShape(24.dp)),
                )
                Spacer(Modifier.height(16.dp))
                Text(s.appName, style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary)
                Spacer(Modifier.height(4.dp))
                Text(s.tagline, style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)
                if (!hasApiKey) {
                    Spacer(Modifier.height(16.dp))
                    Surface(
                        onClick = onOpenModelsSettings,
                        shape = RoundedCornerShape(12.dp),
                        color = colors.warning.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, colors.warning.copy(alpha = 0.5f)),
                        modifier = Modifier.testTag("empty_configure_model_btn"),
                    ) {
                        Text(
                            "${s.noProviderWarning} · ${s.configureModel}",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.warning,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (hasEarlier) {
            item {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = onLoadEarlier, modifier = Modifier.testTag("load_earlier_btn")) {
                        Text(s.loadEarlier)
                    }
                }
            }
        }

        items(allItems, key = { it.id }) { msg ->
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth()) {
                    when (msg) {
                        is UserMsg -> UserBubble(msg)
                        is AssistantMsg -> AssistantTurnView(msg, stepDetail)
                    }
                }
            }
        }
    }
}

@Composable
private fun UserBubble(msg: UserMsg) {
    val colors = LocalHeraclesColors.current
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
            color = colors.userBubble,
            modifier = Modifier.widthIn(max = 520.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (msg.attachments.isNotEmpty()) {
                    Row(
                        modifier = Modifier.padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        msg.attachments.forEach { att ->
                            if (att.kind == AttachmentKind.IMAGE && File(att.path).isFile) {
                                AsyncImage(
                                    model = File(att.path),
                                    contentDescription = att.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                                )
                            } else {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = colors.surface.copy(alpha = 0.25f),
                                ) {
                                    Text(
                                        att.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.userBubbleText,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                if (msg.text.isNotBlank()) {
                    Text(msg.text, style = MaterialTheme.typography.bodyLarge, color = colors.userBubbleText)
                }
            }
        }
    }
}

@Composable
private fun AssistantTurnView(msg: AssistantMsg, stepDetail: StepDetail) {
    val colors = LocalHeraclesColors.current
    val s = LocalStrings.current
    var showThinking by remember(msg.id) { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (msg.thinking.isNotBlank()) {
            Surface(
                onClick = { showThinking = !showThinking },
                shape = RoundedCornerShape(10.dp),
                color = colors.surfaceAlt,
                border = BorderStroke(1.dp, colors.cardBorder),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.thinking, style = MaterialTheme.typography.labelMedium, color = colors.textMuted, modifier = Modifier.weight(1f))
                        Icon(if (showThinking) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(16.dp))
                    }
                    AnimatedVisibility(visible = showThinking) {
                        Text(
                            msg.thinking,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textMuted,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }

        msg.steps.forEach { step ->
            step.toolCalls.forEach { tc ->
                ToolCallCard(tc, stepDetail)
            }
        }

        if (msg.text.isNotBlank()) {
            Text(
                msg.text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (msg.isError) colors.danger else colors.textPrimary,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        } else if (msg.isStreaming && msg.steps.isEmpty()) {
            Text(s.thinkingNow, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
        }
    }
}

@Composable
fun ToolCallCard(tc: ToolCallItem, stepDetail: StepDetail) {
    val colors = LocalHeraclesColors.current
    var expanded by remember(tc.id) { mutableStateOf(stepDetail == StepDetail.FULLY_EXPANDED) }
    val statusColor = when (tc.status) {
        ToolCallStatus.RUNNING -> colors.accent
        ToolCallStatus.SUCCESS -> colors.success
        ToolCallStatus.FAILED -> colors.danger
        ToolCallStatus.DENIED -> colors.warning
    }

    Surface(
        onClick = { if (!stepDetail.statusOnly) expanded = !expanded },
        shape = RoundedCornerShape(12.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.cardBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (tc.status) {
                    ToolCallStatus.RUNNING -> Icon(Icons.Filled.HourglassTop, contentDescription = null, tint = statusColor, modifier = Modifier.size(15.dp))
                    ToolCallStatus.SUCCESS -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = statusColor, modifier = Modifier.size(15.dp))
                    ToolCallStatus.FAILED, ToolCallStatus.DENIED -> Icon(Icons.Filled.Close, contentDescription = null, tint = statusColor, modifier = Modifier.size(15.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    tc.summary.take(StepDetail.SUMMARY_CHARS),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                tc.diffStat?.takeIf { it.hasChanges }?.let { d ->
                    Text(
                        "+${d.added} -${d.removed}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = JetBrainsMonoFamily,
                        color = colors.accent,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
                Surface(shape = CircleShape, color = colors.surfaceAlt) {
                    Text(tc.surface, style = MaterialTheme.typography.bodySmall, color = colors.textMuted, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }

            AnimatedVisibility(visible = expanded && stepDetail.showArgs) {
                Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        tc.argsJson.take(600),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = JetBrainsMonoFamily,
                        color = colors.textMuted,
                    )
                    val resultText = if (stepDetail.fullResult) tc.output else tc.output.take(360)
                    if (resultText.isNotBlank()) {
                        Surface(shape = RoundedCornerShape(8.dp), color = colors.surfaceAlt, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                resultText,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = JetBrainsMonoFamily,
                                color = colors.textPrimary,
                                modifier = Modifier.padding(8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
