/*
 * Wire
 * Copyright (C) 2024 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see http://www.gnu.org/licenses/.
 */

package com.wire.android.ui.home.broadcast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wire.android.R
import com.wire.android.ui.home.broadcastViewModel
import kotlin.math.roundToInt

private const val SECONDS_PER_MINUTE = 60L

@Composable
fun BroadcastScreen(viewModel: BroadcastViewModel = broadcastViewModel()) {
    val state = viewModel.state
    var showConfirm by remember { mutableStateOf(false) }

    // A long broadcast must not be interrupted by the screen turning off.
    val view = LocalView.current
    DisposableEffect(state.isSending) {
        view.keepScreenOn = state.isSending
        onDispose { view.keepScreenOn = false }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.weight(1f)) {
            item {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = state.message,
                        onValueChange = viewModel::onMessageChange,
                        enabled = !state.isSending,
                        label = { Text(stringResource(R.string.broadcast_message_label)) },
                        minLines = 3,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Column {
                        Text(
                            text = stringResource(R.string.broadcast_delay_label, state.delaySeconds),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Slider(
                            value = state.delaySeconds.toFloat(),
                            onValueChange = { viewModel.onDelayChange(it.roundToInt()) },
                            valueRange = BroadcastUiState.MIN_DELAY_SECONDS.toFloat()..BroadcastUiState.MAX_DELAY_SECONDS.toFloat(),
                            enabled = !state.isSending,
                        )
                    }
                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = viewModel::onSearchChange,
                        label = { Text(stringResource(R.string.broadcast_search_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(onClick = viewModel::selectAllVisible, enabled = !state.isSending) {
                            Text(stringResource(R.string.broadcast_select_all))
                        }
                        OutlinedButton(onClick = viewModel::clearSelection, enabled = !state.isSending) {
                            Text(stringResource(R.string.broadcast_select_none))
                        }
                    }
                    Text(
                        text = stringResource(
                            R.string.broadcast_selected_count,
                            state.selectedIds.size,
                            state.contacts.size
                        ),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                HorizontalDivider()
            }
            items(items = state.visibleContacts, key = { it.conversationId.toString() }) { contact ->
                val checked = contact.conversationId in state.selectedIds
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !state.isSending) { viewModel.toggle(contact.conversationId) }
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { viewModel.toggle(contact.conversationId) },
                        enabled = !state.isSending
                    )
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text(text = contact.name, style = MaterialTheme.typography.bodyLarge)
                        contact.handle?.let {
                            Text(
                                text = "@$it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        BroadcastBottomBar(
            state = state,
            onSendClick = { showConfirm = true },
            onCancelClick = viewModel::cancel,
            onDismissResult = viewModel::dismissResult,
        )
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.broadcast_confirm_title)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.broadcast_confirm_text,
                        state.selectedIds.size,
                        state.selectedIds.size
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    viewModel.send()
                }) { Text(stringResource(R.string.broadcast_send)) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text(stringResource(R.string.broadcast_cancel)) }
            }
        )
    }
}

@Composable
private fun BroadcastBottomBar(
    state: BroadcastUiState,
    onSendClick: () -> Unit,
    onCancelClick: () -> Unit,
    onDismissResult: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when {
                state.isSending -> {
                    Text(text = statusText(state), style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(
                        progress = { if (state.total == 0) 0f else state.processed.toFloat() / state.total },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(onClick = onCancelClick, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.broadcast_stop))
                    }
                }

                state.finished -> {
                    Text(
                        text = stringResource(R.string.broadcast_done, state.sent, state.failed),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Button(onClick = onDismissResult, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.broadcast_ok))
                    }
                }

                else -> {
                    if (state.total > 0) {
                        // A broadcast was stopped half-way.
                        Text(
                            text = stringResource(R.string.broadcast_stopped, state.sent, state.failed, state.total),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Button(
                        onClick = onSendClick,
                        enabled = state.canSend,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.broadcast_send_to, state.selectedIds.size))
                    }
                }
            }
        }
    }
}

/** For example "178/242 - 40 minutes left". */
@Composable
private fun statusText(state: BroadcastUiState): String {
    val minutes = ((state.remainingSeconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE).toInt()
    return if (state.remainingSeconds >= SECONDS_PER_MINUTE) {
        pluralStringResource(R.plurals.broadcast_status_minutes, minutes, state.processed, state.total, minutes)
    } else {
        val seconds = state.remainingSeconds.toInt()
        pluralStringResource(R.plurals.broadcast_status_seconds, seconds, state.processed, state.total, seconds)
    }
}
