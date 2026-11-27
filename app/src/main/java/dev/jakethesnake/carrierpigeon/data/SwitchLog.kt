// SPDX-License-Identifier: Apache-2.0
package dev.jakethesnake.carrierpigeon.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Spike log: one line per event, so we can see on a real phone whether every automatic switch
 * is reported, by which path, and how quickly. Kept to the newest [MAX_LINES] lines.
 */
class SwitchLog(context: Context) {
    private val file = File(context.applicationContext.filesDir, "switch-log.txt")

    @Synchronized
    fun append(reason: String, old: DataSimState?, new: DataSimState) {
        val switched = old != null && old.activeSubId != new.activeSubId
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(new.updatedAtMillis))
        val line = buildString {
            append(time)
            append(if (switched) " SWITCH " else "        ")
            append("reason=").append(reason)
            append(" active=").append(new.activeSubId)
            append(" default=").append(new.defaultSubId)
            append(" network=").append(new.networkSubId)
            append(" name=\"").append(new.simLabel.forLog()).append('"')
            new.networkKind?.let { append(" type=\"").append(it.badge).append('"') }
            append(" access=").append(new.access.name.lowercase())
            if (new.vpn) append("+vpn")
            if (new.status != DataStatus.OK) append(" status=").append(new.status.name)
            if (switched) append(" from=").append(old!!.activeSubId)
        }
        write(line)
    }

    // The log is a diagnostic shared in bug reports, so it stays in English whatever the app's language.
    private fun SimLabel.forLog(): String = when (this) {
        is SimLabel.Named -> name.escapeLogValue()
        is SimLabel.Slot -> "SIM $number"
        is SimLabel.SubId -> "SIM #$id"
        SimLabel.NoDataSim -> "No data SIM"
    }

    /** A diagnostic line (registration results, process starts) so the spike log explains gaps. */
    fun note(text: String) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        write("$time   NOTE ${text.escapeLogValue()}")
    }

    @Synchronized
    private fun write(line: String) {
        val lines = (readLines() + line).takeLast(MAX_LINES)
        file.writeText(lines.joinToString("\n", postfix = "\n"))
    }

    @Synchronized
    fun readLines(): List<String> = if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()

    @Synchronized
    fun clear() {
        file.delete()
    }

    private companion object {
        const val MAX_LINES = 500
    }
}

/** Keeps platform-provided labels and exception messages inside one unambiguous log line. */
internal fun String.escapeLogValue(): String = buildString(length) {
    for (character in this@escapeLogValue) {
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> if (character.isISOControl()) {
                append("\\u")
                append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
}
