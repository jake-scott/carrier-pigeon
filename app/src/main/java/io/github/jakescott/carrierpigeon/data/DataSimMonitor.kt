// SPDX-License-Identifier: Apache-2.0
package io.github.jakescott.carrierpigeon.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Registers the two ways we hear about data SIM changes. */
object DataSimMonitor {
    private const val TAG = "DataSimMonitor"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var inProcessCallback: Any? = null

    /**
     * Battery-free wake-up: the system sends [NetworkChangeReceiver] a broadcast whenever a cellular
     * internet network becomes available, even if our process is dead. A data SIM switch brings up the
     * other SIM's network, so this fires on every switch (to be confirmed by the spike).
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
        }
    }

    /** Instant updates while our process happens to be alive (API 31+). Needs no permission. */
    @Synchronized
    fun registerInProcessListener(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || inProcessCallback != null) return
        val app = context.applicationContext
        val tm = app.getSystemService(TelephonyManager::class.java)
        val callback = object : TelephonyCallback(), TelephonyCallback.ActiveDataSubscriptionIdListener {
            override fun onActiveDataSubscriptionIdChanged(subId: Int) {
                scope.launch { DataSimRefresher.refresh(app, "telephony-callback") }
            }
        }
        try {
            tm.registerTelephonyCallback(app.mainExecutor, callback)
            inProcessCallback = callback
        } catch (e: SecurityException) {
            Log.w(TAG, "registerTelephonyCallback denied", e)
        }
    }
}
