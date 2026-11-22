// SPDX-License-Identifier: Apache-2.0
package dev.jakethesnake.carrierpigeon.ui

import android.content.res.Resources
import android.text.Annotation
import android.text.Spanned
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import dev.jakethesnake.carrierpigeon.ui.Glossary.Segment

/** Text made of segments, some of them tappable terms. Drawn by [TermText]. */
typealias RichText = List<Segment>

/** The text without links, e.g. for the widget, which has no popups. */
fun RichText.plain(): String = joinToString("") { it.text }

/** A value filled into a `%1$s` placeholder of a resource string by [richString]. */
sealed interface RichArg {
    /** Text from the phone or the person (SIM name, Wi-Fi name): shown as it is, never linked. */
    data class Plain(val text: String) : RichArg

    /** A technical name that is never translated ("5G NSA", "Wi-Fi 6"): known terms become links. */
    data class Technical(val text: String) : RichArg

    /** Rich text built already, e.g. from another resource. */
    data class Rich(val text: RichText) : RichArg
}

/**
 * Loads string resource [id] as rich text: words inside `<annotation term="…">` become tappable
 * terms, and `%1$s`-style placeholders are filled with [args]. With [emphasizeArgs], filled-in
 * values are marked for emphasis (drawn medium weight), as in "Security: **WPA3**".
 */
fun Resources.richString(@StringRes id: Int, vararg args: RichArg, emphasizeArgs: Boolean = false): RichText =
    fillPlaceholders(annotatedSegments(getText(id)), args.toList(), emphasizeArgs)

/** [richString] for Compose; reads the configuration so it updates when the language changes. */
@Composable
fun richStringResource(@StringRes id: Int, vararg args: RichArg, emphasizeArgs: Boolean = false): RichText {
    val configuration = LocalConfiguration.current
    val resources = LocalResources.current
    return remember(configuration, id, *args, emphasizeArgs) { resources.richString(id, *args, emphasizeArgs = emphasizeArgs) }
}

/** Splits [text] at its `<annotation term="…">` spans. Unknown keys stay plain text. */
internal fun annotatedSegments(text: CharSequence): RichText {
    if (text !is Spanned) return listOf(Segment(text.toString()))
    val annotations = text.getSpans(0, text.length, Annotation::class.java)
        .filter { it.key == "term" }
        .sortedBy { text.getSpanStart(it) }
    val out = mutableListOf<Segment>()
    var pos = 0
    for (annotation in annotations) {
        val start = text.getSpanStart(annotation)
        val end = text.getSpanEnd(annotation)
        if (start < pos || end <= start) continue
        if (start > pos) out += Segment(text.substring(pos, start))
        out += Segment(text.substring(start, end), TermId.byKey(annotation.value))
        pos = end
    }
    if (pos < text.length) out += Segment(text.substring(pos))
    return out
}

private val PLACEHOLDER = Regex("""%(\d+)\$[sd]|%%""")

/** Replaces `%1$s`, `%2$d` … in the plain segments with [args]; `%%` becomes "%". Pure, so unit tested. */
internal fun fillPlaceholders(segments: RichText, args: List<RichArg>, emphasizeArgs: Boolean = false): RichText {
    if (args.isEmpty()) return segments
    val out = mutableListOf<Segment>()
    for (segment in segments) {
        if (segment.term != null) {
            out += segment
            continue
        }
        var pos = 0
        for (match in PLACEHOLDER.findAll(segment.text)) {
            if (match.range.first > pos) out += Segment(segment.text.substring(pos, match.range.first))
            if (match.value == "%%") {
                out += Segment("%")
            } else {
                val filled = when (val arg = args.getOrNull(match.groupValues[1].toInt() - 1)) {
                    null -> listOf(Segment(match.value))
                    is RichArg.Plain -> listOf(Segment(arg.text))
                    is RichArg.Technical -> Glossary.segments(arg.text)
                    is RichArg.Rich -> arg.text
                }
                out += if (emphasizeArgs) filled.map { it.copy(emphasis = true) } else filled
            }
            pos = match.range.last + 1
        }
        if (pos < segment.text.length) out += Segment(segment.text.substring(pos))
    }
    return out
}
