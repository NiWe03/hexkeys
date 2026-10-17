package io.github.tieo.hexkeys

import android.content.Context
import android.content.pm.ApplicationInfo
import org.json.JSONObject
import java.io.File

/**
 * Every touch on the keyboard written down in debug builds, one JSON object
 * per line in `files/gestures.jsonl`: where the finger went and when, what
 * the core made of it, and what was typed in the end. With it, what was
 * meant can be held against what happened. The file is cut to its newer
 * half once it passes [LIMIT]. Release builds write nothing.
 */
object GestureLog {
    private const val LIMIT = 4L shl 20

    fun enabled(context: Context) = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    fun write(context: Context, record: JSONObject) {
        val file = File(context.filesDir, "gestures.jsonl")
        if (file.length() > LIMIT) {
            val lines = file.readLines()
            file.writeText(lines.drop(lines.size / 2).joinToString("\n", postfix = "\n"))
        }
        file.appendText(record.toString() + "\n")
    }
}
