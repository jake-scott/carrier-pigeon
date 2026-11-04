// SPDX-License-Identifier: Apache-2.0
package io.github.jakescott.carrierpigeon.data

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
            append(" name=\"").append(new.label).append('"')
            new.networkType?.let { append(" type=").append(it) }
            append(" access=\"").append(new.accessLine).append('"')
            if (new.status != DataStatus.OK) append(" status=").append(new.status.name)
            if (switched) append(" from=").append(old!!.activeSubId)
        }
        write(line)
    }

    /** A diagnostic line (registration results, process starts) so the spike log explains gaps. */
    fun note(text: String) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        write("$time   NOTE $text")
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
