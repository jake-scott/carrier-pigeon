// SPDX-License-Identifier: Apache-2.0
package dev.jakethesnake.carrierpigeon.data.history

import android.content.Context
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import dev.jakethesnake.carrierpigeon.data.Access
import dev.jakethesnake.carrierpigeon.data.AppSettings
import dev.jakethesnake.carrierpigeon.data.CellSignalReader
import dev.jakethesnake.carrierpigeon.data.DataSimResolver
import dev.jakethesnake.carrierpigeon.data.DataSimState
import dev.jakethesnake.carrierpigeon.data.DeviceFeatures
import dev.jakethesnake.carrierpigeon.data.Signal
import dev.jakethesnake.carrierpigeon.data.WifiReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Feeds history. Connection, SIM and VPN changes arrive through [onState], which DataSimRefresher
 * calls after every refresh, so they cost no extra wake-ups. Signal comes from listeners that run
 * while the process is alive ([syncSignalListeners]) and from the 15-minute [HistorySampleWorker].
 */
object HistoryRecorder {
    private const val TAG = "HistoryRecorder"

    /**
     * When this process started watching. A period confirmed since then has been followed by the
     * live listeners the whole time (see [HistoryMath.knownSpans]).
     */
    @Volatile
    var liveSince: Long = Long.MAX_VALUE
        private set

    @Volatile
    private var enabled = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var cellJob: Job? = null
    private var cellSubIds: Set<Int> = emptySet()
    private var wantedSubIds: Set<Int> = emptySet()
    private var wifiJob: Job? = null

    /** Called once from Application.onCreate. Follows the "Record history" setting from then on. */
    fun init(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            if (liveSince != Long.MAX_VALUE) return
            liveSince = System.currentTimeMillis()
        }
        scope.launch {
            AppSettings(app).historyEnabled.distinctUntilChanged().collect { on -> setEnabled(app, on) }
        }
    }

    private fun setEnabled(app: Context, on: Boolean) {
        val wasOn = enabled
        enabled = on
        HistoryScheduler.sync(app, on)
        synchronized(this) {
            if (on) {
                startWifi(app)
                restartCell(app)
            } else {
                cellJob?.cancel()
                cellJob = null
                wifiJob?.cancel()
                wifiJob = null
            }
        }
        // Switched off: end the open periods now, so they aren't drawn as continuing.
        if (wasOn && !on) HistoryStore.get(app).closeAll(System.currentTimeMillis(), liveSince)
    }

    /** Records the state DataSimRefresher just resolved, changed or not, so its period is confirmed. */
    suspend fun onState(context: Context, state: DataSimState) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        if (!AppSettings(app).historyEnabled()) return@withContext
        try {
            val now = System.currentTimeMillis()
            val resolver = DataSimResolver(app)
            val sims = resolver.activeSubscriptions().map { info ->
                SimRecord(
                    subId = info.subscriptionId,
                    label = info.displayName?.toString()?.takeIf { it.isNotBlank() }
                        ?: info.carrierName?.toString()?.takeIf { it.isNotBlank() },
                    slotIndex = info.simSlotIndex,
                    isEmbedded = info.isEmbedded,
                    iconTint = info.iconTint,
                )
            }.ifEmpty {
                // Without phone access Android won't list the SIMs; keep what the state knows.
                if (state.hasActiveSub) {
                    listOf(
                        SimRecord(
                            state.activeSubId,
                            state.displayName?.takeIf { it.isNotBlank() } ?: state.carrierName,
                            state.slotIndex,
                            state.isEmbedded,
                            state.iconTint,
                        ),
                    )
                } else {
                    emptyList()
                }
            }
            val kinds = sims.associate { sim ->
                sim.subId to if (sim.subId == state.activeSubId) state.networkKind else resolver.networkKind(sim.subId)
            }
            val store = HistoryStore.get(app)
            store.recordSims(sims, now)
            store.recordState(
                AccessValue(
                    access = state.access,
                    subId = if (state.access == Access.CELLULAR) state.activeSubId else SubscriptionManager.INVALID_SUBSCRIPTION_ID,
                    defaultSubId = state.defaultSubId,
                    status = state.status,
                ),
                state.vpn,
                kinds,
                now,
                liveSince,
            )
        } catch (e: RuntimeException) {
            // History must never break the widget's refresh.
            Log.w(TAG, "recording state failed", e)
        }
    }

    /**
     * Follows the signal of each SIM in [subIds] while the process is alive. Called whenever the
     * set of active SIMs may have changed; does nothing when it hasn't.
     */
    fun syncSignalListeners(context: Context, subIds: Set<Int>) {
        val app = context.applicationContext
        synchronized(this) {
            wantedSubIds = subIds.filter { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }.toSet()
            if (enabled) restartCell(app)
        }
    }

    /** Must hold the lock. */
    private fun restartCell(app: Context) {
        if (cellJob?.isActive == true && cellSubIds == wantedSubIds) return
        cellJob?.cancel()
        cellSubIds = wantedSubIds
        if (cellSubIds.isEmpty() || !DeviceFeatures.hasTelephony(app)) return
        val ids = cellSubIds
        cellJob = scope.launch {
            CellSignalReader(app).updates(ids).collect { readings ->
                readings.forEach { (subId, signal) -> record(app, SignalSource.Sim(subId), signal) }
            }
        }
    }

    /** Must hold the lock. */
    private fun startWifi(app: Context) {
        if (wifiJob?.isActive == true || !DeviceFeatures.hasWifi(app)) return
        wifiJob = scope.launch {
            WifiReader(app).updates().collect { wifi ->
                if (wifi.connected) record(app, SignalSource.Wifi, wifi.signal)
            }
        }
    }

    /** One-off readings for the periodic sampler: each active SIM and the Wi-Fi connection. */
    fun sampleSignalNow(context: Context) {
        val app = context.applicationContext
        if (DeviceFeatures.hasTelephony(app)) {
            val tm = app.getSystemService(TelephonyManager::class.java)
            val ids = DataSimResolver(app).activeSubscriptions().map { it.subscriptionId }.toMutableSet()
            ids += SubscriptionManager.getActiveDataSubscriptionId()
            ids.filter { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }.forEach { subId ->
                val signal = try {
                    Signal.of(tm.createForSubscriptionId(subId).signalStrength)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "signal read for sub $subId failed", e)
                    null
                }
                record(app, SignalSource.Sim(subId), signal)
            }
        }
        if (DeviceFeatures.hasWifi(app)) {
            val wifi = WifiReader(app).current()
            if (wifi.connected) record(app, SignalSource.Wifi, wifi.signal)
        }
    }

    private fun record(app: Context, source: SignalSource, signal: Signal?) {
        if (signal == null) return
        try {
            HistoryStore.get(app).recordSignal(source, signal.bars, signal.dbm, System.currentTimeMillis())
        } catch (e: RuntimeException) {
            Log.w(TAG, "recording signal failed", e)
        }
    }
}
