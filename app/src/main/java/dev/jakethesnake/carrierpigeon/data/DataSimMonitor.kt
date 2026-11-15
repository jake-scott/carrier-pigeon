// SPDX-License-Identifier: Apache-2.0
package dev.jakethesnake.carrierpigeon.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.telephony.ServiceState
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
    val AIRPLANE_ACTION: String = Intent.ACTION_AIRPLANE_MODE_CHANGED

    val DEFAULT_DATA_ACTIONS = listOf(
        "android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED",
        SubscriptionManager.ACTION_DEFAULT_SUBSCRIPTION_CHANGED,
    )

    /** Fires often (e.g. Wi-Fi signal changes), so unchanged results from it aren't logged. */
    const val DEFAULT_NETWORK_REASON = "default-network"
    const val SERVICE_STATE_REASON = "service-state"
    /** 5G NSA / LTE+ overrides; can change often as the phone moves between cells. */
    const val DISPLAY_INFO_REASON = "display-info"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var telephonyCallback: Any? = null
    private var telephonyFailureLogged = false
    private var defaultNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var subscriptionsListener: SubscriptionManager.OnSubscriptionsChangedListener? = null
    private var runtimeReceiver: BroadcastReceiver? = null

    /**
     * Battery-free wake-up: the system sends [NetworkChangeReceiver] a broadcast whenever a cellular
     * internet network becomes available, even if our process is dead.
     * Registering the same PendingIntent again replaces the earlier request, so this is safe to repeat.
     */
    fun registerNetworkCallback(context: Context) {
        val app = context.applicationContext
        releaseLegacyRequest(app)
        // Cellular internet networks: a data SIM switch brings up the other SIM's network.
        register(
            app,
            NetworkChangeReceiver.ACTION_CELLULAR,
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
        )
        // Any internet network, VPNs included: Wi-Fi or a VPN coming up changes how data is reached.
        register(
            app,
            NetworkChangeReceiver.ACTION_ANY,
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build(),
        )
    }

    /** Earlier test builds registered one PendingIntent with no action; drop it. */
    private fun releaseLegacyRequest(app: Context) {
        val flags = PendingIntent.FLAG_NO_CREATE or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val legacy = PendingIntent.getBroadcast(app, 0, Intent(app, NetworkChangeReceiver::class.java), flags) ?: return
        try {
            app.getSystemService(ConnectivityManager::class.java).releaseNetworkRequest(legacy)
        } catch (e: RuntimeException) {
            // Not registered any more; nothing to do.
        }
        legacy.cancel()
    }

    private fun register(app: Context, action: String, request: NetworkRequest) {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        // Must be mutable: the system adds the Network to the intent's extras.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        // The action keeps the two PendingIntents distinct, so each registration replaces only its own.
        val pendingIntent = PendingIntent.getBroadcast(
            app, 0, Intent(app, NetworkChangeReceiver::class.java).setAction(action), flags,
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
        registerDefaultNetworkCallback(app)
        registerRuntimeReceiver(app)
        syncDisplayInfoListeners(app)
    }

    /** One display-info listener per active SIM, plus the active data SIM if we can't list SIMs. */
    private fun syncDisplayInfoListeners(app: Context) {
        val subIds = DataSimResolver(app).activeSubscriptions().map { it.subscriptionId }.toMutableSet()
        subIds += SubscriptionManager.getActiveDataSubscriptionId()
        DisplayInfoTracker.sync(app, subIds) {
            scope.launch { DataSimRefresher.refresh(app, DISPLAY_INFO_REASON) }
        }
    }

    private fun registerTelephonyCallback(app: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || telephonyCallback != null) return
        val tm = app.getSystemService(TelephonyManager::class.java)
        val callback = object : TelephonyCallback(),
            TelephonyCallback.ActiveDataSubscriptionIdListener,
            TelephonyCallback.ServiceStateListener,
            TelephonyCallback.UserMobileDataStateListener {
            override fun onActiveDataSubscriptionIdChanged(subId: Int) {
                scope.launch { DataSimRefresher.refresh(app, "telephony-callback") }
            }

            override fun onServiceStateChanged(serviceState: ServiceState) {
                scope.launch { DataSimRefresher.refresh(app, SERVICE_STATE_REASON) }
            }

            override fun onUserMobileDataStateChanged(enabled: Boolean) {
                scope.launch { DataSimRefresher.refresh(app, "mobile-data-toggled") }
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

    /** While alive, follow the default network fully: Wi-Fi lost, VPN up or down, cellular to Wi-Fi. */
    private fun registerDefaultNetworkCallback(app: Context) {
        if (defaultNetworkCallback != null) return
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = changed()
            override fun onLost(network: Network) = changed()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = changed()
            private fun changed() {
                scope.launch { DataSimRefresher.refresh(app, DEFAULT_NETWORK_REASON) }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
            defaultNetworkCallback = callback
        } catch (e: RuntimeException) {
            Log.w(TAG, "registerDefaultNetworkCallback failed", e)
            SwitchLog(app).note("default network callback registration failed: ${e.javaClass.simpleName}: ${e.message}")
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
                val reason = if (intent.action == AIRPLANE_ACTION) "airplane-mode" else "default-data-broadcast"
                scope.launch { DataSimRefresher.refresh(app, reason) }
            }
        }
        val filter = IntentFilter().apply {
            DEFAULT_DATA_ACTIONS.forEach { addAction(it) }
            addAction(AIRPLANE_ACTION)
        }
        // System broadcasts need RECEIVER_EXPORTED (flag added in API 33, required when targeting 34+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            app.registerReceiver(receiver, filter)
        }
        runtimeReceiver = receiver
    }
}
