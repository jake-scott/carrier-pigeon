// SPDX-License-Identifier: Apache-2.0
package dev.jakethesnake.carrierpigeon.data

import android.content.Context
import android.telephony.SubscriptionManager
import androidx.glance.appwidget.updateAll
import dev.jakethesnake.carrierpigeon.widget.CarrierPigeonWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The single path every trigger goes through: resolve, log, store, redraw widgets. */
object DataSimRefresher {
    /**
     * Turning an eSIM on or off sends dozens of system events over a few seconds, and the first ones
     * can arrive before the platform has settled (e.g. the new SIM's name not yet readable). System
     * events therefore wait this long and are coalesced into one refresh.
     */
    private const val SETTLE_MS = 1_000L

    private val mutex = Mutex()
    private val lock = Any()
    private val pendingReasons = linkedSetOf<String>()
    private var pendingNetworkSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    private var windowOpen = false

    /**
     * @param settle true for system events (coalesced after [SETTLE_MS]); false when the user asked,
     *   so a tap or opening the app refreshes immediately.
     */
    suspend fun refresh(
        context: Context,
        reason: String,
        networkSubId: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID,
        settle: Boolean = true,
    ) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        if (!settle) {
            refreshNow(app, reason, networkSubId)
            return@withContext
        }
        val leader = synchronized(lock) {
            pendingReasons += reason
            if (networkSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) pendingNetworkSubId = networkSubId
            if (windowOpen) false else true.also { windowOpen = true }
        }
        // Another call is already waiting out the window and will include this reason.
        if (!leader) return@withContext
        delay(SETTLE_MS)
        val (reasons, subId) = synchronized(lock) {
            windowOpen = false
            val r = pendingReasons.joinToString("+")
            val s = pendingNetworkSubId
            pendingReasons.clear()
            pendingNetworkSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
            r to s
        }
        refreshNow(app, reasons, subId)
    }

    private suspend fun refreshNow(app: Context, reason: String, networkSubId: Int) {
        // Retry any in-process listener that failed earlier, e.g. before the permission was granted.
        withContext(Dispatchers.Main) { DataSimMonitor.registerInProcessListeners(app) }
        // A listener registered just now delivers the 5G NSA override a moment later; wait for it.
        if (DeviceFeatures.hasTelephony(app)) DisplayInfoTracker.awaitFirst(SubscriptionManager.getActiveDataSubscriptionId())
        val changed = mutex.withLock {
            val store = StateStore(app)
            val old = store.current()
            val new = DataSimResolver(app).resolve(networkSubId).keepKnownDetails(old)
            val changed = old == null || old.withoutVolatile() != new.withoutVolatile()
            if (changed || !reason.isOnlyNoisy()) SwitchLog(app).append(reason, old, new)
            if (changed) store.save(new)
            changed
        }
        if (changed) CarrierPigeonWidget().updateAll(app)
    }

    /** Mid-switch, SIM details can briefly be unreadable; keep what we knew about the same SIM. */
    private fun DataSimState.keepKnownDetails(old: DataSimState?): DataSimState =
        if (old != null && old.activeSubId == activeSubId && displayName == null && old.displayName != null) {
            copy(
                displayName = old.displayName,
                carrierName = old.carrierName,
                slotIndex = old.slotIndex,
                isEmbedded = old.isEmbedded,
                iconTint = old.iconTint,
            )
        } else {
            this
        }

    private fun String.isOnlyNoisy() = split("+").all {
        it == DataSimMonitor.DEFAULT_NETWORK_REASON || it == DataSimMonitor.SERVICE_STATE_REASON
    }

    private fun DataSimState.withoutVolatile() = copy(networkSubId = -1, updatedAtMillis = 0)
}
