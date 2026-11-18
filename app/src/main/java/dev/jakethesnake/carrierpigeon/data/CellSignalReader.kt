// SPDX-License-Identifier: Apache-2.0
package dev.jakethesnake.carrierpigeon.data

import android.content.Context
import android.os.Build
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.concurrent.ConcurrentHashMap

/**
 * Live signal strength for each SIM, for the app's SIM tiles. Unlike [DisplayInfoTracker] this
 * only listens while the screen collects it: signal changes every few seconds and the widget
 * doesn't show it, so it never triggers a refresh.
 *
 * Android 12+: TelephonyCallback.SignalStrengthsListener. Android 11: PhoneStateListener with
 * LISTEN_SIGNAL_STRENGTHS. Neither needs a permission.
 */
class CellSignalReader(context: Context) {
    private val app = context.applicationContext

    /**
     * Each of [subIds] to its signal, or to null while unknown. Starts with what Android has now
     * and updates as it changes.
     */
    fun updates(subIds: Set<Int>): Flow<Map<Int, Signal?>> = callbackFlow {
        val tm = if (DeviceFeatures.hasTelephony(app)) app.getSystemService(TelephonyManager::class.java) else null
        val valid = subIds.filter { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
        // ConcurrentHashMap can't hold null values, so unknown is stored as an absent key.
        val readings = ConcurrentHashMap<Int, Signal>()
        fun push() {
            trySend(valid.associateWith { readings[it] })
        }
        fun store(subId: Int, signal: Signal?) {
            if (signal == null) readings.remove(subId) else readings[subId] = signal
        }

        val registrations = mutableListOf<Pair<TelephonyManager, Any>>()
        if (tm != null) {
            valid.forEach { subId ->
                val subTm = tm.createForSubscriptionId(subId)
                store(subId, read(subTm))
                val deliver: (SignalStrength) -> Unit = { ss ->
                    store(subId, Signal.of(ss))
                    push()
                }
                try {
                    registrations += subTm to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        registerCallback(subTm, deliver)
                    } else {
                        registerLegacy(subTm, deliver)
                    }
                } catch (e: RuntimeException) {
                    Log.w(TAG, "signal listener for sub $subId failed", e)
                }
            }
        }
        push()
        awaitClose { registrations.forEach { (subTm, listener) -> unregister(subTm, listener) } }
    }.distinctUntilChanged()

    private fun read(tm: TelephonyManager): Signal? = try {
        Signal.of(tm.signalStrength)
    } catch (e: RuntimeException) {
        Log.w(TAG, "signal strength read failed", e)
        null
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerCallback(tm: TelephonyManager, deliver: (SignalStrength) -> Unit): Any {
        val callback = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength) = deliver(signalStrength)
        }
        tm.registerTelephonyCallback(app.mainExecutor, callback)
        return callback
    }

    @Suppress("DEPRECATION") // PhoneStateListener is the only way on Android 11.
    private fun registerLegacy(tm: TelephonyManager, deliver: (SignalStrength) -> Unit): Any {
        val listener = object : android.telephony.PhoneStateListener(app.mainExecutor) {
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength) = deliver(signalStrength)
        }
        tm.listen(listener, android.telephony.PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
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

    private companion object {
        const val TAG = "CellSignalReader"
    }
}
