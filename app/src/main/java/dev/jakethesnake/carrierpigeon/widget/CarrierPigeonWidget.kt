// SPDX-License-Identifier: Apache-2.0
package dev.jakethesnake.carrierpigeon.widget

import android.content.Context
import android.content.Intent
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
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
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
import dev.jakethesnake.carrierpigeon.R
import dev.jakethesnake.carrierpigeon.data.AppSettings
import dev.jakethesnake.carrierpigeon.data.DataSimState
import dev.jakethesnake.carrierpigeon.data.DataStatus
import dev.jakethesnake.carrierpigeon.data.StateStore
import dev.jakethesnake.carrierpigeon.ui.Labels
import dev.jakethesnake.carrierpigeon.ui.MainActivity
import dev.jakethesnake.carrierpigeon.ui.plain

/** Home-screen widget. Only reads stored state; never calls telephony APIs itself. */
class CarrierPigeonWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = StateStore(context)
        val settings = AppSettings(context)
        val initial = store.current()
        val initialShowDetail = settings.showNetworkDetail()
        provideContent {
            val state by remember { store.state }.collectAsState(initial)
            val showDetail by remember { settings.showNetworkDetail }.collectAsState(initialShowDetail)
            GlanceTheme {
                WidgetContent(state, showDetail)
            }
        }
    }
}

@Composable
private fun WidgetContent(state: DataSimState?, showDetail: Boolean) {
    val compact = LocalSize.current.width < 110.dp
    val context = LocalContext.current
    val res = context.resources
    // A tap always opens the app, which refreshes the widget as it opens. Without phone access it
    // also asks for it straight away; a widget can't show the permission prompt itself.
    val needsAccess = state != null && !state.phoneAccess
    val onTap = actionStartActivity(
        Intent(context, MainActivity::class.java)
            .apply { if (needsAccess) putExtra(MainActivity.EXTRA_REQUEST_PHONE_ACCESS, true) }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
    )
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
            .clickable(onTap),
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
                        text = state?.let { Labels.widgetHeadline(res, it) } ?: context.getString(R.string.widget_tap_to_load),
                        maxLines = 1,
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = if (compact) 14.sp else 16.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                    marker(context, state, compact)?.let { m ->
                        Text(
                            text = m,
                            maxLines = 1,
                            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                        )
                    }
                }
                // Bottom line: how the device is reaching the internet right now. On mobile data, the
                // network badge ("5G NSA") can be followed by what is underneath it ("LTE + NR").
                if (needsAccess) {
                    Text(
                        text = context.getString(if (compact) R.string.widget_tap_to_allow_short else R.string.widget_tap_to_allow),
                        maxLines = 1,
                        style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 12.sp),
                    )
                } else if (state != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = Labels.accessLine(res, state).plain(),
                            maxLines = 1,
                            style = TextStyle(
                                color = GlanceTheme.colors.onSurfaceVariant,
                                fontSize = 12.sp,
                                fontWeight = if (state.accessIsCellular) FontWeight.Medium else FontWeight.Normal,
                            ),
                        )
                        val detail = Labels.networkDetail(res, state.networkKind)?.plain()
                        if (showDetail && !compact && state.accessIsCellular && detail != null) {
                            Text(
                                text = "  $detail", // not-translated: spacing before a translated value
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
private fun marker(context: Context, s: DataSimState?, compact: Boolean): String? {
    if (s == null || !s.hasActiveSub || s.defaultSubId < 0 || s.cellularUnavailable) return null
    // Narrow widgets use a symbol, the same in every language.
    return when {
        s.isTemporarySwitch -> if (compact) " ⇄" else "  " + context.getString(R.string.widget_marker_backup)
        else -> if (compact) " ★" else "  " + context.getString(R.string.widget_marker_default)
    }
}
