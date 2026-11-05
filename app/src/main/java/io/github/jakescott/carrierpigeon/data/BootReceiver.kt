// SPDX-License-Identifier: Apache-2.0
package io.github.jakescott.carrierpigeon.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Network callbacks don't survive a reboot or app update, so register again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // CarrierPigeonApp.onCreate has already registered the callback; refresh so the widget is current.
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                DataSimMonitor.registerNetworkCallback(context)
                DataSimRefresher.refresh(context, if (intent.action == Intent.ACTION_BOOT_COMPLETED) "boot" else "app-updated")
            } finally {
                pending.finish()
            }
        }
    }
}
