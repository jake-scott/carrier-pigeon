// SPDX-License-Identifier: Apache-2.0
package io.github.jakescott.carrierpigeon.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Woken by the system when a cellular internet network becomes available. */
class NetworkChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val network = IntentCompat.getParcelableExtra(intent, ConnectivityManager.EXTRA_NETWORK, Network::class.java)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val networkSubId = DataSimResolver.subIdForNetwork(context, network)
                DataSimRefresher.refresh(context, "network-callback", networkSubId)
            } finally {
                pending.finish()
            }
        }
    }
}
