// SPDX-License-Identifier: Apache-2.0
package dev.jakethesnake.carrierpigeon.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The widget words its text when it draws, from language-free stored state, so a change of the
 * system language only needs a redraw. (A per-app language change is caught by MainActivity.)
 */
class LocaleChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_LOCALE_CHANGED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                CarrierPigeonWidget().updateAll(context)
            } finally {
                pending.finish()
            }
        }
    }
}
