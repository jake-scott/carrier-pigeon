// SPDX-License-Identifier: Apache-2.0
package dev.jakethesnake.carrierpigeon.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.CarrierConfigManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Manifest receiver for SIM-level events, so we hear about them even when our process is dead.
 * CARRIER_CONFIG_CHANGED is exempt from Android's implicit-broadcast limits and fires when an eSIM
 * is enabled or disabled; the default-data broadcasts are listed too in case the device delivers them.
 */
class SimEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED -> "carrier-config-changed"
            in DataSimMonitor.DEFAULT_DATA_ACTIONS -> "default-data-broadcast"
            DataSimMonitor.AIRPLANE_ACTION -> "airplane-mode"
            else -> return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                DataSimRefresher.refresh(context, reason)
            } finally {
                pending.finish()
            }
        }
    }
}
