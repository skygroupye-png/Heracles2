package com.theohowie.heracles.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theohowie.heracles.bridge.WorkspaceRegistry
import com.theohowie.heracles.i18n.LocalStrings
import com.theohowie.heracles.model.SessionMeta
import com.theohowie.heracles.ui.theme.LocalHeraclesColors

@Composable
fun WorkspaceDrawerContent(
    workspaces: List<WorkspaceRegistry.Entry>,
    activeWorkspace: WorkspaceRegistry.Entry?,
    sessions: List<SessionMeta>,
    activeSession: SessionMeta?,
    onSelectWorkspace: (WorkspaceRegistry.Entry) -> Unit,
    onPickFolder: () -> Unit,
    onNewSession: () -> Unit,
    onSelectSession: (SessionMeta) -> Unit,
    onRenameSession: (SessionMeta, String) -> Unit,
    onArchiveSession: (SessionMeta) -> Unit,
    onDeleteSession: (SessionMeta) -> Unit,
    onOpenArchived: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenMcp: () -> Unit,
    onOpenAutomation: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHeraclesColors.current
    val s = LocalStrings.current
    var renamingTarget by remember { mutableStateOf<SessionMeta?>(null) }
    var renameText by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(colors.surface)
            .statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(s.appName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.textPrimary)
            }
            Surface(
                onClick = onNewSession,
                shape = RoundedCornerShape(10.dp),
                color = colors.accent,
                modifier = Modifier.minimumInteractiveComponentSize().testTag("new_session_button"),
            ) {
                Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Add, contentDescription = s.newSession, tint = colors.onAccent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(s.newSession, style = MaterialTheme.typography.labelMedium, color = colors.onAccent)
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(s.workspaces, style = MaterialTheme.typography.labelMedium, color = colors.textMuted)
            TextButton(onClick = onPickFolder, modifier = Modifier.testTag("pick_workspace_button")) {
                Icon(Icons.Filled.CreateNewFolder, contentDescription = s.addWorkspace, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(s.addWorkspace)
            }
        }

        workspaces.forEach { ws ->
            val selected = ws.path == activeWorkspace?.path
            val displayName = if (ws.name == WorkspaceRegistry.APP_WORKSPACE_NAME) s.defaultWorkspace else ws.name
            Surface(
                onClick = { onSelectWorkspace(ws) },
                shape = RoundedCornerShape(12.dp),
                color = if (selected) colors.surfaceAlt else colors.surface,
                border = if (selected) BorderStroke(1.dp, colors.accent.copy(alpha = 0.55f)) else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .minimumInteractiveComponentSize()
                    .testTag("workspace_item_${ws.name}"),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Folder,
                        contentDescription = null,
                        tint = if (selected) colors.accent else colors.textMuted,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(displayName, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (ws.isSaf) "SAF · ${ws.path}" else ws.path, style = MaterialTheme.typography.bodySmall, color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = colors.cardBorder)
        Spacer(Modifier.height(10.dp))
        Text(s.sessionHistory, style = MaterialTheme.typography.labelMedium, color = colors.textMuted)
        Spacer(Modifier.height(6.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(sessions, key = { it.id }) { sess ->
                val isCurrent = sess.id == activeSession?.id
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isCurrent) colors.surfaceAlt else colors.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .clickable { onSelectSession(sess) }
                        .testTag("session_item_${sess.id}"),
                ) {
                    Row(
                        modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                sess.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isCurrent) colors.accent else colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(s.recordsCount(sess.messageCount), style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                        }
                        IconButton(
                            onClick = {
                                renamingTarget = sess
                                renameText = sess.title
                            },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(Icons.Filled.Edit, contentDescription = s.rename, tint = colors.textMuted, modifier = Modifier.size(16.dp))
                        }
                        IconButton(
                            onClick = { onArchiveSession(sess) },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(Icons.Filled.Archive, contentDescription = s.archive, tint = colors.textMuted, modifier = Modifier.size(16.dp))
                        }
                        IconButton(
                            onClick = { onDeleteSession(sess) },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(Icons.Filled.DeleteOutline, contentDescription = s.delete, tint = colors.textMuted, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = colors.cardBorder)
        Spacer(Modifier.height(6.dp))
        DrawerActionRow(Icons.Filled.Archive, s.archivedSessions, "drawer_archived_btn", onOpenArchived)
        DrawerActionRow(Icons.Filled.Extension, s.skills, "drawer_skills_btn", onOpenSkills)
        DrawerActionRow(Icons.Filled.Hub, s.mcpServers, "drawer_mcp_btn", onOpenMcp)
        DrawerActionRow(Icons.Filled.Schedule, s.automation, "drawer_automation_btn", onOpenAutomation)
        DrawerActionRow(Icons.Filled.Settings, s.settings, "drawer_settings_btn", onOpenSettings)
    }

    if (renamingTarget != null) {
        AlertDialog(
            onDismissRequest = { renamingTarget = null },
            title = { Text(s.renameSessionTitle) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text(s.sessionTitleLabel) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    renamingTarget?.let { onRenameSession(it, renameText) }
                    renamingTarget = null
                }) { Text(s.save) }
            },
            dismissButton = {
                TextButton(onClick = { renamingTarget = null }) { Text(s.cancel) }
            },
        )
    }
}

@Composable
private fun DrawerActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tag: String,
    onClick: () -> Unit,
) {
    val colors = LocalHeraclesColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = colors.textMuted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
    }
}
