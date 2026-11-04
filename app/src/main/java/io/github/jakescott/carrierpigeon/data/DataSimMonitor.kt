// SPDX-License-Identifier: Apache-2.0
package io.github.jakescott.carrierpigeon.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Registers the ways we hear about data SIM changes, and records in the switch log whether each worked. */
object DataSimMonitor {
    private const val TAG = "DataSimMonitor"

    /** Broadcasts sent when the default data SIM changes. Not all are public API, so we match by string. */
    val DEFAULT_DATA_ACTIONS = listOf(
        "android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED",
        SubscriptionManager.ACTION_DEFAULT_SUBSCRIPTION_CHANGED,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var telephonyCallback: Any? = null
    private var telephonyFailureLogged = false
    private var subscriptionsListener: SubscriptionManager.OnSubscriptionsChangedListener? = null
    private var runtimeReceiver: BroadcastReceiver? = null

    /**
     * Battery-free wake-up: the system sends [NetworkChangeReceiver] a broadcast whenever a cellular
     * internet network becomes available, even if our process is dead.
     * Registering the same PendingIntent again replaces the earlier request, so this is safe to repeat.
     */
    fun registerNetworkCallback(context: Context) {
        val app = context.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        // Must be mutable: the system adds the Network to the intent's extras.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pendingIntent = PendingIntent.getBroadcast(
            app, 0, Intent(app, NetworkChangeReceiver::class.java), flags,
        )
        try {
            cm.registerNetworkCallback(request, pendingIntent)
        } catch (e: RuntimeException) {
            Log.e(TAG, "registerNetworkCallback failed", e)
            SwitchLog(app).note("network callback registration failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Listeners that only work while our process is alive. Safe to call repeatedly: anything that
     * failed earlier (for example before READ_PHONE_STATE was granted) is retried.
     */
    @Synchronized
    fun registerInProcessListeners(context: Context) {
        val app = context.applicationContext
        registerTelephonyCallback(app)
        registerSubscriptionsListener(app)
        registerRuntimeReceiver(app)
    }

    private fun registerTelephonyCallback(app: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || telephonyCallback != null) return
        val tm = app.getSystemService(TelephonyManager::class.java)
        val callback = object : TelephonyCallback(), TelephonyCallback.ActiveDataSubscriptionIdListener {
            override fun onActiveDataSubscriptionIdChanged(subId: Int) {
                scope.launch { DataSimRefresher.refresh(app, "telephony-callback") }
            }
        }
        try {
            tm.registerTelephonyCallback(app.mainExecutor, callback)
            telephonyCallback = callback
            SwitchLog(app).note("telephony callback registered")
        } catch (e: RuntimeException) {
            Log.w(TAG, "registerTelephonyCallback failed", e)
            // Expected until READ_PHONE_STATE is granted; log it once per process, not on every retry.
            if (!telephonyFailureLogged) {
                telephonyFailureLogged = true
                SwitchLog(app).note("telephony callback registration failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun registerSubscriptionsListener(app: Context) {
        if (subscriptionsListener != null) return
        val sm = app.getSystemService(SubscriptionManager::class.java)
        val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
            override fun onSubscriptionsChanged() {
                scope.launch { DataSimRefresher.refresh(app, "subscriptions-changed") }
            }
        }
        try {
            sm.addOnSubscriptionsChangedListener(app.mainExecutor, listener)
            subscriptionsListener = listener
        } catch (e: RuntimeException) {
            Log.w(TAG, "addOnSubscriptionsChangedListener failed", e)
            SwitchLog(app).note("subscriptions listener registration failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun registerRuntimeReceiver(app: Context) {
        if (runtimeReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                scope.launch { DataSimRefresher.refresh(app, "default-data-broadcast") }
            }
        }
        val filter = IntentFilter().apply { DEFAULT_DATA_ACTIONS.forEach { addAction(it) } }
        // System broadcasts need RECEIVER_EXPORTED (flag added in API 33, required when targeting 34+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            app.registerReceiver(receiver, filter)
        }
        runtimeReceiver = receiver
    }
}
