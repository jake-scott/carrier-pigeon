// SPDX-License-Identifier: Apache-2.0
package io.github.jakescott.carrierpigeon.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import io.github.jakescott.carrierpigeon.data.DataSimRefresher
import io.github.jakescott.carrierpigeon.data.DataSimState
import io.github.jakescott.carrierpigeon.data.DataStatus
import io.github.jakescott.carrierpigeon.data.StateStore

/** Home-screen widget. Only reads stored state; never calls telephony APIs itself. */
class CarrierPigeonWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = StateStore(context)
        val initial = store.current()
        provideContent {
            val state by remember { store.state }.collectAsState(initial)
            GlanceTheme {
                WidgetContent(state)
            }
        }
    }
}

@Composable
private fun WidgetContent(state: DataSimState?) {
    val compact = LocalSize.current.width < 110.dp
    // Grey dot when the data SIM can't carry data (airplane mode, data off, no service).
    val dotColor = if (state == null || state.status != DataStatus.OK) {
        Color(0xFF9E9E9E)
    } else {
        state.iconTint.takeIf { it != 0 }?.let { Color(it) } ?: Color(0xFF3D5AFE)
    }

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(16.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clickable(actionRunCallback<RefreshAction>()),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = GlanceModifier.size(12.dp).cornerRadius(6.dp).background(dotColor),
            ) {}
            Spacer(GlanceModifier.width(8.dp))
            Column {
                // Top line: the carrier carrying data, marked as the default data SIM or a backup.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = state?.label ?: "Tap to load",
                        maxLines = 1,
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = if (compact) 14.sp else 16.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                    marker(state, compact)?.let { m ->
                        Text(
                            text = m,
                            maxLines = 1,
                            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                        )
                    }
                }
                // Bottom line: how the device is reaching the internet right now. On mobile data, the
                // network badge ("5G NSA") is followed by what is underneath it ("LTE + NR").
                if (state != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = state.accessLine,
                            maxLines = 1,
                            style = TextStyle(
                                color = GlanceTheme.colors.onSurfaceVariant,
                                fontSize = 12.sp,
                                fontWeight = if (state.accessIsCellular) FontWeight.Medium else FontWeight.Normal,
                            ),
                        )
                        val detail = state.networkDetail
                        if (!compact && state.accessIsCellular && detail != null) {
                            Text(
                                text = "  $detail",
                                maxLines = 1,
                                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 10.sp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** "default" when data is on the SIM chosen in Settings, "backup" when the phone has moved it to another SIM. */
private fun marker(s: DataSimState?, compact: Boolean): String? {
    if (s == null || !s.hasActiveSub || s.defaultSubId < 0) return null
    return when {
        s.isTemporarySwitch -> if (compact) " ⇄" else "  backup"
        else -> if (compact) " ★" else "  default"
    }
}

/** Tapping the widget re-reads the data SIM, so the user can always force it to be current. */
class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        DataSimRefresher.refresh(context, "widget-tap", settle = false)
    }
}
