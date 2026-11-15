// SPDX-License-Identifier: Apache-2.0
package dev.jakethesnake.carrierpigeon.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import dev.jakethesnake.carrierpigeon.data.DataSimMonitor
import dev.jakethesnake.carrierpigeon.data.DataSimRefresher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CarrierPigeonWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CarrierPigeonWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        // Also our periodic safety net (updatePeriodMillis): make sure the callback is registered and state is fresh.
        DataSimMonitor.registerNetworkCallback(context)
        // GlanceAppWidgetReceiver already holds goAsync() for this broadcast, so don't call it again.
        val app = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            DataSimRefresher.refresh(app, "widget-update")
        }
    }
}
