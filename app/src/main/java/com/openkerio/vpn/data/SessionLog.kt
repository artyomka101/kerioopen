package com.openkerio.vpn.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory ring buffer of session events. Never stores passwords or secrets.
 */
object SessionLog {
    private const val MAX = 500
    private val lines = CopyOnWriteArrayList<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val fileFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun clear() {
        lines.clear()
    }

    fun i(message: String) = append("I", message)

    fun w(message: String) = append("W", message)

    fun e(message: String) = append("E", message)

    fun snapshot(): List<String> = lines.toList()

    fun asText(): String = lines.joinToString("\n")

    fun exportShareIntent(context: Context): Intent {
        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
        val file = File(dir, "openkerio_${fileFmt.format(Date())}.log")
        file.writeText(asText().ifBlank { "(empty log)" })
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "OpenKerio session log")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun append(level: String, message: String) {
        val safe = redact(message)
        val line = "${timeFmt.format(Date())} $level $safe"
        lines.add(line)
        while (lines.size > MAX) {
            lines.removeAt(0)
        }
    }

    private fun redact(message: String): String {
        var out = message
        // Never leak password-looking fragments if somehow passed in.
        out = out.replace(Regex("(?i)password[=:].*"), "password=***")
        out = out.replace(Regex("(?i)secret[=:].*"), "secret=***")
        return out
    }
}
