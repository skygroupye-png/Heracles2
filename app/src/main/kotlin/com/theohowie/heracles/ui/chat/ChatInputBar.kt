package com.theohowie.heracles.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.theohowie.heracles.i18n.LocalStrings
import com.theohowie.heracles.model.AttachmentItem
import com.theohowie.heracles.model.BusySendBehavior
import com.theohowie.heracles.ui.theme.LocalHeraclesColors

@Composable
fun ChatInputBar(
    inputText: String,
    attachments: List<AttachmentItem>,
    busy: Boolean,
    busyBehavior: BusySendBehavior,
    isRecordingVoice: Boolean,
    voiceTranscript: String,
    mode: ChatViewModel.TopMode,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onToggleVoice: () -> Unit,
    onPickPhoto: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickFile: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
) {
    val colors = LocalHeraclesColors.current
    val s = LocalStrings.current
    var showAttachMenu by remember { mutableStateOf(false) }

    Surface(
        color = colors.surface.copy(alpha = 0.96f),
        border = BorderStroke(0.5.dp, colors.cardBorder),
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding(),
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (isRecordingVoice) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = colors.accent.copy(alpha = 0.14f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                voiceTranscript.ifBlank { s.voiceRecordingHint },
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textPrimary,
                            )
                        }
                    }
                }

                if (attachments.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        attachments.forEach { att ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = colors.surfaceAlt,
                                border = BorderStroke(1.dp, colors.cardBorder),
                            ) {
                                Row(
                                    modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(att.name, style = MaterialTheme.typography.bodySmall, color = colors.textPrimary)
                                    IconButton(onClick = { onRemoveAttachment(att.id) }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Filled.Close, contentDescription = s.delete, modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (mode == ChatViewModel.TopMode.CHAT) {
                        Box {
                            IconButton(
                                onClick = { showAttachMenu = true },
                                modifier = Modifier.minimumInteractiveComponentSize().testTag("attach_menu_btn"),
                            ) {
                                Icon(Icons.Filled.AttachFile, contentDescription = s.attachAdd, tint = colors.textMuted)
                            }
                            DropdownMenu(expanded = showAttachMenu, onDismissRequest = { showAttachMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(s.attachPhotoPicker) },
                                    leadingIcon = { Icon(Icons.Filled.Image, contentDescription = null) },
                                    onClick = { showAttachMenu = false; onPickPhoto() },
                                )
                                DropdownMenuItem(
                                    text = { Text(s.attachCamera) },
                                    leadingIcon = { Icon(Icons.Filled.CameraAlt, contentDescription = null) },
                                    onClick = { showAttachMenu = false; onTakePhoto() },
                                )
                                DropdownMenuItem(
                                    text = { Text(s.attachDocument) },
                                    leadingIcon = { Icon(Icons.Filled.AttachFile, contentDescription = null) },
                                    onClick = { showAttachMenu = false; onPickFile() },
                                )
                            }
                        }

                        IconButton(
                            onClick = onToggleVoice,
                            modifier = Modifier.minimumInteractiveComponentSize().testTag("voice_capture_btn"),
                        ) {
                            Icon(
                                if (isRecordingVoice) Icons.Filled.Stop else Icons.Filled.Mic,
                                contentDescription = s.voiceInput,
                                tint = if (isRecordingVoice) colors.danger else colors.textMuted,
                            )
                        }
                    }

                    OutlinedTextField(
                        value = inputText,
                        onValueChange = onInputChange,
                        placeholder = {
                            Text(
                                if (mode == ChatViewModel.TopMode.DECISION) s.decisionPlaceholder else s.askAnythingPlaceholder,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                        maxLines = 5,
                        shape = RoundedCornerShape(18.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.accent,
                            unfocusedBorderColor = colors.cardBorder,
                            focusedContainerColor = colors.surfaceAlt,
                            unfocusedContainerColor = colors.surfaceAlt,
                        ),
                        modifier = Modifier.weight(1f).testTag("chat_input_field"),
                    )

                    val canSend = inputText.isNotBlank() || attachments.isNotEmpty()
                    if (busy && mode == ChatViewModel.TopMode.CHAT && !canSend) {
                        Surface(
                            onClick = onStop,
                            shape = CircleShape,
                            color = colors.danger,
                            modifier = Modifier.size(48.dp).testTag("stop_run_btn"),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Stop, contentDescription = s.stop, tint = colors.onAccent)
                            }
                        }
                    } else {
                        Surface(
                            onClick = {
                                if (canSend) onSend()
                            },
                            shape = CircleShape,
                            color = if (canSend) colors.accent else colors.surfaceAlt,
                            modifier = Modifier.size(48.dp).testTag("send_message_btn"),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = if (busy && busyBehavior == BusySendBehavior.QUEUE) s.queueNotice else s.send,
                                    tint = if (canSend) colors.onAccent else colors.textMuted,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
