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
    private val lock = Any()
    private val timeFmt = ThreadLocal.withInitial {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }
    private val fileFmt = ThreadLocal.withInitial {
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }

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
        val stamp = synchronized(lock) { fileFmt.get()!!.format(Date()) }
        val file = File(dir, "openkerio_${stamp}.log")
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
        val stamp = synchronized(lock) { timeFmt.get()!!.format(Date()) }
        val line = "$stamp $level $safe"
        lines.add(line)
        while (lines.size > MAX) {
            runCatching { lines.removeAt(0) }
        }
    }

    private fun redact(message: String): String {
        var out = message
        out = out.replace(Regex("(?i)password[=:].*"), "password=***")
        out = out.replace(Regex("(?i)secret[=:].*"), "secret=***")
        return out
    }
}
