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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wire.kalium.logic.data.conversation.ConversationDetails
import com.wire.kalium.logic.data.id.ConversationId
import com.wire.kalium.logic.data.user.ConnectionState
import com.wire.kalium.logic.feature.conversation.ObserveConversationListDetailsUseCase
import com.wire.kalium.logic.feature.message.MessageOperationResult
import com.wire.kalium.logic.feature.message.SendTextMessageUseCase
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** One selectable 1:1 conversation in the broadcast list. */
data class BroadcastContact(
    val conversationId: ConversationId,
    val name: String,
    val handle: String?,
)

data class BroadcastUiState(
    val contacts: List<BroadcastContact> = emptyList(),
    val selectedIds: Set<ConversationId> = emptySet(),
    val searchQuery: String = "",
    val message: String = "",
    val delaySeconds: Int = DEFAULT_DELAY_SECONDS,
    val isSending: Boolean = false,
    val sent: Int = 0,
    val failed: Int = 0,
    val total: Int = 0,
    val remainingSeconds: Long = 0,
    val finished: Boolean = false,
) {
    val visibleContacts: List<BroadcastContact>
        get() = if (searchQuery.isBlank()) {
            contacts
        } else {
            contacts.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                        (it.handle?.contains(searchQuery, ignoreCase = true) == true)
            }
        }

    /** Messages processed so far, successful or not. */
    val processed: Int get() = sent + failed

    val canSend: Boolean get() = !isSending && message.isNotBlank() && selectedIds.isNotEmpty()

    companion object {
        const val DEFAULT_DELAY_SECONDS = 8
        const val MIN_DELAY_SECONDS = 1
        const val MAX_DELAY_SECONDS = 120
    }
}

/**
 * Lets the user pick any number of 1:1 conversations and send the same text message to each of them,
 * one at a time with a configurable pause in between, while reporting progress and remaining time.
 */
class BroadcastViewModel @Inject constructor(
    private val observeConversationListDetails: ObserveConversationListDetailsUseCase,
    private val sendTextMessage: SendTextMessageUseCase,
) : ViewModel() {

    var state by mutableStateOf(BroadcastUiState())
        private set

    private var sendJob: Job? = null

    init {
        viewModelScope.launch {
            observeConversationListDetails(fromArchive = false).collectLatest { details ->
                val contacts = details
                    .filterIsInstance<ConversationDetails.OneOne>()
                    .filter { it.otherUser.connectionStatus == ConnectionState.ACCEPTED && !it.otherUser.deleted }
                    .map {
                        BroadcastContact(
                            conversationId = it.conversation.id,
                            name = it.otherUser.name ?: it.otherUser.handle.orEmpty(),
                            handle = it.otherUser.handle,
                        )
                    }
                    .sortedBy { it.name.lowercase() }
                val validIds = contacts.map { it.conversationId }.toSet()
                // Never touch the selection of a running broadcast; the list it works on is already fixed.
                state = if (state.isSending) {
                    state.copy(contacts = contacts)
                } else {
                    state.copy(contacts = contacts, selectedIds = state.selectedIds.intersect(validIds))
                }
            }
        }
    }

    fun onMessageChange(text: String) {
        if (!state.isSending) state = state.copy(message = text)
    }

    fun onSearchChange(query: String) {
        state = state.copy(searchQuery = query)
    }

    fun onDelayChange(seconds: Int) {
        if (!state.isSending) {
            state = state.copy(
                delaySeconds = seconds.coerceIn(BroadcastUiState.MIN_DELAY_SECONDS, BroadcastUiState.MAX_DELAY_SECONDS)
            )
        }
    }

    fun toggle(id: ConversationId) {
        if (state.isSending) return
        val selected = state.selectedIds
        state = state.copy(selectedIds = if (id in selected) selected - id else selected + id)
    }

    /** Selects everything that matches the current search, so a search can be used to narrow the group down. */
    fun selectAllVisible() {
        if (state.isSending) return
        state = state.copy(selectedIds = state.selectedIds + state.visibleContacts.map { it.conversationId })
    }

    fun clearSelection() {
        if (!state.isSending) state = state.copy(selectedIds = emptySet())
    }

    fun send() {
        val current = state
        if (!current.canSend) return

        // Keep the on-screen order so the person can follow along.
        val targets = current.contacts.filter { it.conversationId in current.selectedIds }
        val text = current.message.trim()
        val delayMillis = current.delaySeconds * MILLIS_PER_SECOND

        state = current.copy(
            isSending = true,
            finished = false,
            sent = 0,
            failed = 0,
            total = targets.size,
            remainingSeconds = targets.size.toLong() * current.delaySeconds,
        )

        sendJob = viewModelScope.launch {
            try {
                targets.forEachIndexed { index, contact ->
                    val result = sendTextMessage(contact.conversationId, text)
                    val left = (targets.size - index - 1).toLong()
                    state = if (result is MessageOperationResult.Success) {
                        state.copy(sent = state.sent + 1, remainingSeconds = left * current.delaySeconds)
                    } else {
                        state.copy(failed = state.failed + 1, remainingSeconds = left * current.delaySeconds)
                    }
                    if (left > 0) delay(delayMillis)
                }
                state = state.copy(isSending = false, finished = true, remainingSeconds = 0)
            } catch (e: CancellationException) {
                state = state.copy(isSending = false, finished = false)
                throw e
            }
        }
    }

    fun cancel() {
        sendJob?.cancel()
    }

    /** Dismisses the "done" summary and returns to normal editing. */
    fun dismissResult() {
        if (!state.isSending) state = state.copy(finished = false, sent = 0, failed = 0, total = 0)
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
    }
}
