package com.theohowie.heracles.ui.chat

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.theohowie.heracles.i18n.LocalStrings
import com.theohowie.heracles.model.JevConfig
import com.theohowie.heracles.model.JevDecision
import com.theohowie.heracles.model.JevSource
import com.theohowie.heracles.ui.theme.JetBrainsMonoFamily
import com.theohowie.heracles.ui.theme.LocalHeraclesColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DecisionModeView(
    config: JevConfig,
    latest: JevDecision?,
    history: List<JevDecision>,
    busy: Boolean,
    error: String?,
    onSelectSource: (JevSource) -> Unit,
    onOpenJevSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHeraclesColors.current
    val s = LocalStrings.current

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 680.dp).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = colors.surface,
                    border = BorderStroke(1.dp, colors.cardBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(s.jevModeTitle, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                            TextButton(onClick = onOpenJevSettings, modifier = Modifier.testTag("jev_mode_settings_btn")) {
                                Text(s.engineAndDiag)
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            JevSource.entries.forEach { src ->
                                FilterChip(
                                    selected = config.source == src,
                                    onClick = { onSelectSource(src) },
                                    label = { Text(src.shortLabel) },
                                )
                            }
                        }
                    }
                }
            }

            if (busy) {
                item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            }

            if (error != null) {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = colors.danger.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, colors.danger.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(error, style = MaterialTheme.typography.bodyMedium, color = colors.danger, modifier = Modifier.padding(12.dp))
                    }
                }
            }

            if (latest != null) {
                item { JevDecisionCard(latest, featured = true) }
            }

            if (history.isNotEmpty()) {
                item {
                    Text(s.decisionHistory, style = MaterialTheme.typography.labelMedium, color = colors.textMuted)
                }
                items(history.take(20), key = { it.id }) { item ->
                    JevDecisionCard(item, featured = false)
                }
            }
        }
    }
}

@Composable
private fun JevDecisionCard(d: JevDecision, featured: Boolean) {
    val colors = LocalHeraclesColors.current
    val s = LocalStrings.current
    val pct = (d.yesProb * 100).toInt()
    val verdictColor = if (d.recommended) colors.success else colors.danger

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, if (featured) verdictColor.copy(alpha = 0.55f) else colors.cardBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (d.recommended) s.recommendYes(pct) else s.recommendNo(100 - pct),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = verdictColor,
                )
                Text(
                    "${d.engineLabel} · ${d.elapsedMs}ms",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = JetBrainsMonoFamily,
                    color = colors.textMuted,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(d.question, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { d.yesProb.toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = verdictColor,
            )
            d.reason?.let { reason ->
                Spacer(Modifier.height(8.dp))
                Text(reason, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
            }
        }
    }
}
