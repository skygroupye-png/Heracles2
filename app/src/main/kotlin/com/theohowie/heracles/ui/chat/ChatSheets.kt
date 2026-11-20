package com.theohowie.heracles.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.theohowie.heracles.data.GitHubAuthService
import com.theohowie.heracles.data.GitService
import com.theohowie.heracles.i18n.LocalStrings
import com.theohowie.heracles.model.AppSettings
import com.theohowie.heracles.model.ReasoningLevel
import com.theohowie.heracles.model.SessionUsage
import com.theohowie.heracles.ui.theme.JetBrainsMonoFamily
import com.theohowie.heracles.ui.theme.LocalHeraclesColors

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ModelAndUsageSheet(
    settings: AppSettings,
    usage: SessionUsage,
    onSelectModel: (providerId: String, model: String) -> Unit,
    onSelectReasoning: (ReasoningLevel) -> Unit,
    onOpenModelsSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalHeraclesColors.current
    val s = LocalStrings.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(s.modelUsageSheetTitle, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                TextButton(onClick = { onDismiss(); onOpenModelsSettings() }, modifier = Modifier.testTag("sheet_manage_models_btn")) {
                    Text(s.manageProviders)
                }
            }

            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.surfaceAlt,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    val ctxText = if (usage.contextWindow > 0) {
                        "${usage.promptTokens} / ${usage.contextWindow} tokens (" +
                            (if (usage.contextFromServer) s.serverDeclared else s.localLimit) + ")"
                    } else {
                        s.cacheHitLabel(usage.promptTokens, usage.completionTokens, usage.cacheHitPercent)
                    }
                    Text(s.sessionUsageRounds(usage.rounds), style = MaterialTheme.typography.labelMedium, color = colors.textMuted)
                    Spacer(Modifier.height(4.dp))
                    Text(ctxText, style = MaterialTheme.typography.bodySmall, color = colors.textPrimary)
                    if (usage.contextWindow > 0) {
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { usage.contextFraction },
                            modifier = Modifier.fillMaxWidth(),
                            color = colors.accent,
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(s.reasoningDepth, style = MaterialTheme.typography.labelMedium, color = colors.textMuted)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReasoningLevel.entries.forEach { lvl ->
                    FilterChip(
                        selected = settings.reasoningLevel == lvl,
                        onClick = { onSelectReasoning(lvl) },
                        label = { Text(lvl.name) },
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(s.switchModel, style = MaterialTheme.typography.labelMedium, color = colors.textMuted)
            Spacer(Modifier.height(6.dp))
            LazyColumn(modifier = Modifier.height(260.dp)) {
                items(settings.providers, key = { it.id }) { p ->
                    val models = p.availableModels.ifEmpty { listOf(p.model) }
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(p.name, style = MaterialTheme.typography.labelMedium, color = colors.accent)
                        models.forEach { m ->
                            val active = p.id == settings.activeProviderId && m == p.model
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSelectModel(p.id, m)
                                        onDismiss()
                                    }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(m, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
                                if (active) Icon(Icons.Filled.Check, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
                            }
                        }
                        HorizontalDivider(color = colors.cardBorder)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitAndGitHubSheet(
    status: GitService.RepoStatus,
    branches: List<String>,
    commits: List<GitService.CommitSummary>,
    diff: String,
    gitBusy: Boolean,
    githubUser: GitHubAuthService.GitHubUser?,
    deviceCode: GitHubAuthService.DeviceCodeInfo?,
    repos: List<GitHubAuthService.GitHubRepo>,
    onRefresh: () -> Unit,
    onInitRepo: () -> Unit,
    onCommit: (String) -> Unit,
    onPush: () -> Unit,
    onPull: () -> Unit,
    onCheckout: (String) -> Unit,
    onStartGitHubLogin: () -> Unit,
    onOpenVerificationUrl: (String) -> Unit,
    onLoadRepos: () -> Unit,
    onCloneRepo: (GitHubAuthService.GitHubRepo) -> Unit,
    onSignOutGitHub: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalHeraclesColors.current
    val s = LocalStrings.current
    var commitMsg by remember { mutableStateOf("") }
    var newBranchName by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surface) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(s.gitRepoAndGithub, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = s.refresh)
                    }
                }
            }

            if (gitBusy) {
                item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            }

            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = colors.surfaceAlt,
                    border = BorderStroke(1.dp, colors.cardBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        if (!status.isRepo) {
                            Text(s.gitNotInitialized, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = onInitRepo, modifier = Modifier.testTag("git_init_btn")) {
                                Text(s.gitInitButton)
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.AccountTree, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(s.branchLabel(status.branch, status.ahead, status.behind), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = onPull, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(s.pull)
                                }
                                OutlinedButton(onClick = onPush, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Filled.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(s.push)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            if (branches.isNotEmpty()) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    branches.take(4).forEach { b ->
                                        FilterChip(selected = b == status.branch, onClick = { onCheckout(b) }, label = { Text(b) })
                                    }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = newBranchName,
                                    onValueChange = { newBranchName = it },
                                    label = { Text(s.switchOrNewBranch) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = {
                                    if (newBranchName.isNotBlank()) {
                                        onCheckout(newBranchName.trim())
                                        newBranchName = ""
                                    }
                                }) { Text(s.switchAction) }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(s.changedFilesCount(status.changes.size), style = MaterialTheme.typography.labelMedium, color = colors.textMuted)
                            status.changes.take(10).forEach { c ->
                                Text("${c.status} ${c.path}", style = MaterialTheme.typography.bodySmall, fontFamily = JetBrainsMonoFamily)
                            }
                            if (status.changes.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = commitMsg,
                                        onValueChange = { commitMsg = it },
                                        label = { Text(s.commitMessageHint) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Button(onClick = {
                                        onCommit(commitMsg)
                                        commitMsg = ""
                                    }) { Text(s.commitAction) }
                                }
                            }
                        }
                    }
                }
            }

            if (diff.isNotBlank()) {
                item {
                    Surface(shape = RoundedCornerShape(10.dp), color = colors.surfaceAlt, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            diff.take(1500),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = JetBrainsMonoFamily,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
            }

            if (commits.isNotEmpty()) {
                item {
                    Text(s.recentCommits, style = MaterialTheme.typography.labelMedium, color = colors.textMuted)
                    commits.take(5).forEach { cm ->
                        Text("${cm.shortHash} · ${cm.message}", style = MaterialTheme.typography.bodySmall, color = colors.textPrimary)
                    }
                }
            }

            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = colors.surfaceAlt,
                    border = BorderStroke(1.dp, colors.cardBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                if (githubUser != null) "GitHub: @${githubUser.login}" else s.githubAuthTitle,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (githubUser != null) {
                                TextButton(onClick = onSignOutGitHub) {
                                    Icon(Icons.Filled.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(s.signOut)
                                }
                            } else {
                                Button(onClick = onStartGitHubLogin, modifier = Modifier.testTag("github_login_btn")) {
                                    Icon(Icons.Filled.Login, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(s.connectGithub)
                                }
                            }
                        }

                        if (deviceCode != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(s.authCodeLabel(deviceCode.userCode), style = MaterialTheme.typography.titleMedium, fontFamily = JetBrainsMonoFamily, color = colors.accent)
                            Spacer(Modifier.height(4.dp))
                            OutlinedButton(onClick = { onOpenVerificationUrl(deviceCode.verificationUri) }) {
                                Icon(Icons.Filled.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(s.openBrowserCode)
                            }
                        }

                        if (githubUser != null) {
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = onLoadRepos) { Text(s.refreshGithubRepos) }
                            repos.take(12).forEach { r ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(r.fullName, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                        Text(if (r.isPrivate) "Private · ${r.defaultBranch}" else "Public · ${r.defaultBranch}", style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                                    }
                                    TextButton(onClick = { onCloneRepo(r) }) { Text(s.cloneAction) }
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}
