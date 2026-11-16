// SPDX-License-Identifier: Apache-2.0
package dev.jakethesnake.carrierpigeon.data

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
                val reason = if (intent.action == ACTION_ANY) "any-network-callback" else "network-callback"
                DataSimRefresher.refresh(context, reason, networkSubId)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_CELLULAR = "dev.jakethesnake.carrierpigeon.NETWORK_CELLULAR"
        const val ACTION_ANY = "dev.jakethesnake.carrierpigeon.NETWORK_ANY"
    }
}
