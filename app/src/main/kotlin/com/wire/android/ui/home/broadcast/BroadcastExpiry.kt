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

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wire.android.BuildConfig
import com.wire.android.R
import com.wire.kalium.logic.CoreLogic
import com.wire.kalium.logic.data.logout.LogoutReason
import com.wire.kalium.logic.feature.session.GetAllSessionsResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Subscription-style expiry. Every build carries a fixed expiry date baked in at build time, so every copy of the same
 * build stops working at the same moment. When it passes, all accounts are logged out and the app only shows a lock
 * screen. A newer build (with a later expiry date) works again, and the accounts can then be logged in as usual.
 */
object BroadcastExpiry {
    const val WARNING_DAYS = 5L
    const val COUNTDOWN_SECONDS = 60L
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val MAX_WAIT_MS = 15L * 60 * 1000
    private const val MIN_WAIT_MS = 1_000L
    private const val TAG = "BroadcastExpiry"

    private val expiresAt: Long get() = BuildConfig.BROADCAST_EXPIRY_MS

    fun millisLeft(now: Long = System.currentTimeMillis()): Long = (expiresAt - now).coerceAtLeast(0)

    fun isExpired(now: Long = System.currentTimeMillis()): Boolean = millisLeft(now) == 0L

    /** Whole days left, rounded up. 0 when expired. */
    fun daysLeft(now: Long = System.currentTimeMillis()): Long = (millisLeft(now) + DAY_MS - 1) / DAY_MS

    fun shouldWarn(now: Long = System.currentTimeMillis()): Boolean = !isExpired(now) && daysLeft(now) <= WARNING_DAYS

    /** Soft-logs out every valid account: the device stays registered and local data is kept, but access is locked. */
    suspend fun logOutAllAccounts(coreLogic: CoreLogic) {
        val result = coreLogic.getGlobalScope().session.allSessions()
        if (result is GetAllSessionsResult.Success) {
            result.sessions.forEach {
                coreLogic.getSessionScope(it.userId).logout(reason = LogoutReason.SELF_SOFT_LOGOUT, waitUntilCompletes = true)
            }
        }
    }

    /**
     * Runs for the lifetime of the process, so an expiry that passes while the app is in the background is enforced too.
     * Wakes up right at the expiry time.
     */
    suspend fun enforceWhileRunning(coreLogic: CoreLogic) {
        while (true) {
            if (isExpired()) {
                try {
                    logOutAllAccounts(coreLogic)
                } catch (e: CancellationException) {
                    throw e
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    Log.e(TAG, "Could not log out expired accounts", e)
                }
            }
            delay(millisLeft().coerceIn(MIN_WAIT_MS, MAX_WAIT_MS))
        }
    }
}

/** Shows a full-screen countdown during the last minute. Calls [onExpired] when it reaches zero. */
@Composable
fun BroadcastExpiryCountdown(onExpired: () -> Unit) {
    var secondsLeft by remember { mutableLongStateOf(Long.MAX_VALUE) }
    LaunchedEffect(Unit) {
        while (true) {
            val left = (BroadcastExpiry.millisLeft() + 999) / 1000
            secondsLeft = left
            if (left == 0L) {
                onExpired()
                return@LaunchedEffect
            }
            val untilCountdown = (left - BroadcastExpiry.COUNTDOWN_SECONDS - 1) * 1_000L
            delay(if (untilCountdown > 1_000L) untilCountdown.coerceAtMost(60_000L) else 1_000L)
        }
    }
    if (secondsLeft <= BroadcastExpiry.COUNTDOWN_SECONDS) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(COUNTDOWN_BACKDROP)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                Text(
                    text = stringResource(R.string.broadcast_countdown_title),
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(R.string.broadcast_countdown_seconds, secondsLeft),
                    color = Color.White,
                    style = MaterialTheme.typography.displayLarge,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }
    }
}

private const val COUNTDOWN_BACKDROP = 0xE6000000
private const val LABEL_REFRESH_MS = 15_000L
private const val MS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L
private const val HOURS_PER_DAY = 24L

/** Small text for the side menu showing how long the app still works, e.g. "Expires in 29 d 4 h 12 min". */
@Composable
fun BroadcastExpiryLabel(modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(LABEL_REFRESH_MS)
        }
    }
    val totalMinutes = BroadcastExpiry.millisLeft(now) / MS_PER_MINUTE
    val text = if (totalMinutes <= 0L) {
        stringResource(R.string.broadcast_expiry_label_soon)
    } else {
        stringResource(
            R.string.broadcast_expiry_label,
            totalMinutes / (MINUTES_PER_HOUR * HOURS_PER_DAY),
            (totalMinutes / MINUTES_PER_HOUR) % HOURS_PER_DAY,
            totalMinutes % MINUTES_PER_HOUR
        )
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

@Composable
fun BroadcastExpiredScreen() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.broadcast_expired_title),
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(R.string.broadcast_expired_text),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }
    }
}
