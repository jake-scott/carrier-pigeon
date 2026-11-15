// SPDX-License-Identifier: Apache-2.0
package dev.jakethesnake.carrierpigeon.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.jakethesnake.carrierpigeon.R

/** Everything one SIM tile shows. Built from the platform in MainActivity; plain data so it can be previewed. */
data class SimTileModel(
    val subId: Int,
    /** 0-based slot, or -1 if unknown. */
    val slotIndex: Int,
    val name: String,
    /** Carrier as the SIM reports it; null or equal to [name] hides the second line. */
    val carrier: String?,
    val isEmbedded: Boolean,
    /** e.g. "5G NSA", a technical name; null when the SIM has no network right now. */
    val networkBadge: String?,
    /** e.g. "LTE + NR", in the current language. */
    val networkDetail: RichText?,
    /** This SIM is carrying mobile data right now. */
    val isData: Boolean,
    /** This is the data SIM chosen in Settings. */
    val isDefaultData: Boolean,
    /** For the data SIM: why data can't flow, e.g. "Airplane mode"; null when it can. */
    val problem: String? = null,
    /** For the data SIM: how the phone reaches the internet, e.g. "Wi-Fi + VPN". */
    val internetVia: RichText? = null,
    /** Airplane mode is on, so this SIM has no cellular connection. */
    val airplaneMode: Boolean = false,
    /** Solid green when carrying the internet, dotted when it would take over if Wi-Fi went away. */
    val role: TileRole = TileRole.NONE,
)

/** How a tile relates to the internet connection, shown by its border. */
enum class TileRole {
    /** Not involved: plain grey outline. */
    NONE,

    /** Carrying the internet right now: solid green outline. */
    ACTIVE,

    /** Would carry the internet if the current connection (e.g. Wi-Fi) dropped: dotted green outline. */
    FALLBACK,
}

/** Green used to mark the SIM carrying mobile data, readable in light and dark themes. */
@Composable
fun dataGreen(): Color = if (isSystemInDarkTheme()) Color(0xFF6CCB77) else Color(0xFF1E8E3E)

private val TileCorner = 20.dp
private val TileShape = RoundedCornerShape(TileCorner)

/** Card border for [role]: solid green for ACTIVE, nothing for FALLBACK (drawn by [roleOutline]), grey otherwise. */
@Composable
fun roleBorder(role: TileRole): BorderStroke = when (role) {
    TileRole.ACTIVE -> BorderStroke(3.dp, dataGreen())
    TileRole.FALLBACK -> BorderStroke(1.dp, Color.Transparent)
    TileRole.NONE -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
}

/** Light green fill behind the tile carrying the internet. */
@Composable
fun roleContainer(role: TileRole): Color =
    if (role == TileRole.ACTIVE) dataGreen().copy(alpha = 0.06f) else MaterialTheme.colorScheme.surface

/** Draws the dotted green outline of a FALLBACK tile; no-op for other roles. */
@Composable
fun Modifier.roleOutline(role: TileRole): Modifier {
    if (role != TileRole.FALLBACK) return this
    val green = dataGreen()
    return dottedBorder(green, width = 3.dp, gap = 8.dp, corner = TileCorner)
}

private fun Modifier.dottedBorder(color: Color, width: Dp, gap: Dp, corner: Dp): Modifier = drawWithContent {
    drawContent()
    val w = width.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(w / 2, w / 2),
        size = Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius(corner.toPx() - w / 2),
        style = Stroke(
            width = w,
            cap = StrokeCap.Round,
            // A near-zero dash with round caps draws dots.
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.01f, gap.toPx() + w)),
        ),
    )
}

/**
 * One active SIM. The outline follows [SimTileModel.role]: solid green with a "Mobile data" tag
 * while this SIM carries the internet, dotted green with "Standby" while it is the fallback behind
 * Wi-Fi, plain when it can't carry data (airplane mode, no service, data off);
 * network terms are tappable and call [onTerm] to explain themselves.
 */
@Composable
fun SimTile(sim: SimTileModel, showDetail: Boolean, onTerm: (TermId) -> Unit, modifier: Modifier = Modifier) {
    val green = dataGreen()
    OutlinedCard(
        modifier = modifier.fillMaxWidth().roleOutline(sim.role),
        shape = TileShape,
        border = roleBorder(sim.role),
        colors = CardDefaults.outlinedCardColors(containerColor = roleContainer(sim.role)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (sim.slotIndex >= 0) stringResource(R.string.sim_slot, sim.slotIndex + 1) else stringResource(R.string.sim_generic),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (sim.isEmbedded) TermText("eSIM", onTerm, MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                if (sim.airplaneMode) AirplaneModeIcon()
                when (sim.role) {
                    TileRole.ACTIVE -> Tag(stringResource(R.string.sim_tag_mobile_data), green, Color.White)
                    TileRole.FALLBACK -> Tag(stringResource(R.string.sim_tag_standby), green.copy(alpha = 0.15f), green)
                    TileRole.NONE -> Unit
                }
            }
            Text(sim.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            sim.carrier?.takeIf { it.isNotBlank() && it != sim.name }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (sim.networkBadge != null) {
                    TermText(sim.networkBadge, onTerm, MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                    sim.networkDetail?.takeIf { showDetail }?.let {
                        TermText(
                            it,
                            onTerm,
                            MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                } else {
                    Text(stringResource(R.string.sim_no_network), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            sim.problem?.let {
                Text(stringResource(R.string.sim_problem, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            if (sim.isData) {
                sim.internetVia?.let {
                    TermText(
                        richStringResource(R.string.sim_internet_via, RichArg.Rich(it), emphasizeArgs = true),
                        onTerm,
                        MaterialTheme.typography.bodyMedium,
                    )
                }
                // When data can't flow (airplane mode, no service) default/backup would mislead.
                if (sim.problem == null) {
                    Text(
                        stringResource(if (sim.isDefaultData) R.string.sim_default_data else R.string.sim_backup_data),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (sim.isDefaultData) {
                Text(
                    stringResource(R.string.sim_default_data_elsewhere),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showDetail) {
                Text(
                    stringResource(R.string.sim_subscription, sim.subId),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun Tag(text: String, background: Color, foreground: Color) {
    Surface(shape = RoundedCornerShape(50), color = background, contentColor = foreground) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * Shows [text] with each term as an underlined link that calls [onTerm]. Plain text keeps [style]'s
 * colour; links use the theme's primary colour. Filled-in values marked for emphasis are medium weight.
 */
@Composable
fun TermText(text: RichText, onTerm: (TermId) -> Unit, style: TextStyle, modifier: Modifier = Modifier) {
    val linkColor = MaterialTheme.colorScheme.primary
    val linkStyle = TextLinkStyles(
        style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
        pressedStyle = SpanStyle(color = linkColor, background = linkColor.copy(alpha = 0.12f), textDecoration = TextDecoration.Underline),
    )
    val emphasis = SpanStyle(fontWeight = FontWeight.Medium)
    val annotated = buildAnnotatedString {
        text.forEach { segment ->
            val term = segment.term
            val appendText: () -> Unit = {
                if (segment.emphasis) withStyle(emphasis) { append(segment.text) } else append(segment.text)
            }
            if (term == null) {
                appendText()
            } else {
                withLink(LinkAnnotation.Clickable(tag = term.key, styles = linkStyle) { onTerm(term) }) { appendText() }
            }
        }
    }
    Text(annotated, style = style, modifier = modifier)
}

/** [TermText] for a technical name that is never translated, e.g. "5G NSA" or "eSIM". */
@Composable
fun TermText(technical: String, onTerm: (TermId) -> Unit, style: TextStyle, modifier: Modifier = Modifier) =
    TermText(Glossary.segments(technical), onTerm, style, modifier)

/** Small popup explaining one term. */
@Composable
fun TermDialog(term: TermId, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_got_it)) } },
        title = { Text(stringResource(term.title)) },
        text = { Text(stringResource(term.body), style = MaterialTheme.typography.bodyMedium) },
        shape = RoundedCornerShape(24.dp),
    )
}
