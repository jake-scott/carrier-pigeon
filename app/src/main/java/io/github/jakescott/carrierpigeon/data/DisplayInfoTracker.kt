// SPDX-License-Identifier: Apache-2.0
package io.github.jakescott.carrierpigeon.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps the latest [TelephonyDisplayInfo] for each active SIM. That is what the status bar uses to
 * show 5G while the phone is registered on LTE (5G NSA), and it can't be read on demand: Android
 * only delivers it to a listener, so we hold one listener per SIM while our process is alive.
 *
 * Android 12+: TelephonyCallback.DisplayInfoListener, no permission needed.
 * Android 11: PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED, needs READ_PHONE_STATE.
 */
object DisplayInfoTracker {
    private const val TAG = "DisplayInfoTracker"
    private const val FIRST_DELIVERY_TIMEOUT_MS = 500L
    private const val FIRST_DELIVERY_POLL_MS = 25L

    private val infos = ConcurrentHashMap<Int, TelephonyDisplayInfo>()
    /** Sub id to its registered TelephonyCallback (Android 12+) or PhoneStateListener (Android 11). */
    private val listeners = HashMap<Int, Any>()
    private var failureLogged = false

    private val _version = MutableStateFlow(0)
    /** Bumped on every delivery, so screens showing all SIMs can redraw. */
    val version: StateFlow<Int> = _version

    /** Latest display info for [subId], or null if none has been delivered yet. */
    fun displayInfo(subId: Int): TelephonyDisplayInfo? = infos[subId]

    /**
     * Listens on exactly [subIds], adding and removing listeners as SIMs come and go. Safe to call
     * repeatedly; registrations that failed earlier are retried. Call on the main thread.
     *
     * @param onChanged called (on the main thread) when a SIM's display info changes.
     */
    @Synchronized
    fun sync(app: Context, subIds: Set<Int>, onChanged: (subId: Int) -> Unit) {
        val valid = subIds.filter { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }.toSet()
        val tm = app.getSystemService(TelephonyManager::class.java)
        (listeners.keys - valid).toList().forEach { subId ->
            unregister(tm.createForSubscriptionId(subId), listeners.remove(subId)!!)
            infos.remove(subId)
        }
        (valid - listeners.keys).forEach { subId ->
            val subTm = tm.createForSubscriptionId(subId)
            val deliver = { info: TelephonyDisplayInfo ->
                val old = infos.put(subId, info)
                _version.value++
                if (old != info) onChanged(subId)
            }
            try {
                listeners[subId] = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    registerCallback(app, subTm, deliver)
                } else {
                    registerLegacy(app, subTm, deliver)
                }
            } catch (e: RuntimeException) {
                Log.w(TAG, "display info listener for sub $subId failed", e)
                // On Android 11 this is expected until READ_PHONE_STATE is granted; log once per process.
                if (!failureLogged) {
                    failureLogged = true
                    SwitchLog(app).note("display info listener failed: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    /**
     * Android delivers the current value right after registering. Wait briefly for it, so a refresh
     * straight after registering doesn't show plain "LTE" and then flip to "5G NSA" a moment later.
     */
    suspend fun awaitFirst(subId: Int) {
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return
        val registered = synchronized(this) { listeners.containsKey(subId) }
        if (!registered) return
        var waited = 0L
        while (infos[subId] == null && waited < FIRST_DELIVERY_TIMEOUT_MS) {
            delay(FIRST_DELIVERY_POLL_MS)
            waited += FIRST_DELIVERY_POLL_MS
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerCallback(app: Context, tm: TelephonyManager, deliver: (TelephonyDisplayInfo) -> Unit): Any {
        val callback = object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
            override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) = deliver(telephonyDisplayInfo)
        }
        tm.registerTelephonyCallback(app.mainExecutor, callback)
        return callback
    }

    // LISTEN_DISPLAY_INFO_CHANGED needs READ_PHONE_STATE on Android 11; a SecurityException is caught by the caller.
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION") // PhoneStateListener is the only way on Android 11.
    private fun registerLegacy(app: Context, tm: TelephonyManager, deliver: (TelephonyDisplayInfo) -> Unit): Any {
        val listener = object : android.telephony.PhoneStateListener(app.mainExecutor) {
            override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) = deliver(telephonyDisplayInfo)
        }
        tm.listen(listener, android.telephony.PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED)
        return listener
    }

    @Suppress("DEPRECATION") // PhoneStateListener, for Android 11.
    private fun unregister(tm: TelephonyManager, listener: Any) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && listener is TelephonyCallback) {
                tm.unregisterTelephonyCallback(listener)
            } else if (listener is android.telephony.PhoneStateListener) {
                tm.listen(listener, android.telephony.PhoneStateListener.LISTEN_NONE)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "unregister failed", e)
        }
    }
}
