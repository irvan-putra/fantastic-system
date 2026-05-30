package com.yourname.helloworld

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight file-backed log for copy/paste debugging.
 *
 * Stored in app-private storage. Not synced unless the user exports it.
 */
object AppLog {
    private const val FILE_NAME = "irvan_trae_log.txt"
    private const val MAX_BYTES = 200_000 // ~200 KB
    private val tsFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    @Synchronized
    fun append(context: Context, tag: String, message: String) {
        val line = "[${tsFormat.format(Date())}] $tag: $message\n"
        try {
            val f = file(context)
            f.appendText(line)
            trimIfNeeded(f)
        } catch (e: Exception) {
            Log.e(tag, "Failed to write AppLog", e)
        }
    }

    @Synchronized
    fun appendException(context: Context, tag: String, message: String, t: Throwable) {
        append(context, tag, message)
        append(context, tag, Log.getStackTraceString(t))
    }

    @Synchronized
    fun readAll(context: Context): String {
        return try {
            val f = file(context)
            if (!f.exists()) "" else f.readText()
        } catch (_: Exception) {
            ""
        }
    }

    @Synchronized
    fun clear(context: Context) {
        try {
            val f = file(context)
            if (f.exists()) f.delete()
        } catch (_: Exception) {
            // ignore
        }
    }

    private fun trimIfNeeded(f: File) {
        if (!f.exists()) return
        val len = f.length()
        if (len <= MAX_BYTES) return
        // Keep the last MAX_BYTES by truncating the beginning.
        val bytes = f.readBytes()
        val start = (bytes.size - MAX_BYTES).coerceAtLeast(0)
        f.writeBytes(bytes.copyOfRange(start, bytes.size))
    }
}

