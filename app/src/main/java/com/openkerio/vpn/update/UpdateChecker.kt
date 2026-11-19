package com.openkerio.vpn.update

import com.openkerio.vpn.BuildConfig
import com.openkerio.vpn.data.AppSettings
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val tag: String,
    val versionName: String,
    val versionCode: Int,
    val htmlUrl: String,
    val body: String,
)

sealed class UpdateCheckResult {
    data class Available(val info: UpdateInfo) : UpdateCheckResult()
    data object UpToDate : UpdateCheckResult()
    data object Dismissed : UpdateCheckResult()
    data object RepoNotConfigured : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

object UpdateChecker {
    fun check(settings: AppSettings, ignoreDismissed: Boolean = false): UpdateCheckResult {
        val repo = settings.updateRepo.ifBlank { AppSettings.DEFAULT_UPDATE_REPO }.trim()
        if (repo.isEmpty() || !repo.contains('/')) {
            return UpdateCheckResult.RepoNotConfigured
        }
        if (repo.count { it == '/' } != 1 || repo.any { it.isWhitespace() }) {
            return UpdateCheckResult.Error("Bad repo format (owner/name)")
        }

        return try {
            val url = URL("https://api.github.com/repos/$repo/releases/latest")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 12_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "OpenKerio-Android/${BuildConfig.VERSION_NAME}")
            }
            try {
                val code = conn.responseCode
                if (code != 200) {
                    return UpdateCheckResult.Error("HTTP $code")
                }
                val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                val json = JSONObject(body)
                val tag = json.optString("tag_name").trim()
                if (tag.isEmpty()) return UpdateCheckResult.Error("Empty tag")
                val htmlUrl = json.optString("html_url").ifBlank {
                    "https://github.com/$repo/releases/tag/$tag"
                }
                // Only allow github.com release pages for this repo.
                if (!htmlUrl.startsWith("https://github.com/$repo/")) {
                    return UpdateCheckResult.Error("Unexpected release URL")
                }
                val info = UpdateInfo(
                    tag = tag,
                    versionName = tag.removePrefix("v"),
                    versionCode = parseVersionCode(tag),
                    htmlUrl = htmlUrl,
                    body = json.optString("body").orEmpty(),
                )
                settings.lastUpdateCheckAt = System.currentTimeMillis()
                if (info.versionCode <= BuildConfig.VERSION_CODE &&
                    !isNewerName(info.versionName, BuildConfig.VERSION_NAME)
                ) {
                    return UpdateCheckResult.UpToDate
                }
                if (!ignoreDismissed && settings.dismissedUpdateTag == info.tag) {
                    return UpdateCheckResult.Dismissed
                }
                UpdateCheckResult.Available(info)
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            UpdateCheckResult.Error(t.message ?: t.javaClass.simpleName)
        }
    }

    fun shouldAutoCheck(settings: AppSettings): Boolean {
        if (!settings.autoCheckUpdates) return false
        val repo = settings.updateRepo.ifBlank { AppSettings.DEFAULT_UPDATE_REPO }
        if (repo.isBlank()) return false
        val elapsed = System.currentTimeMillis() - settings.lastUpdateCheckAt
        return elapsed > TimeUnit.HOURS.toMillis(12)
    }

    private fun parseVersionCode(tag: String): Int {
        // Prefer trailing integer: v1.2.3+5 or build 5
        Regex("""(?:\+|build[= ]?)(\d+)$""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)
            ?.toIntOrNull()?.let { return it }
        val parts = tag.trim().removePrefix("v").split('.', '-', '+')
        // Encode 1.2.3 -> 10203 style if no explicit code.
        val nums = parts.mapNotNull { it.filter { ch -> ch.isDigit() }.toIntOrNull() }
        if (nums.isEmpty()) return 0
        return when (nums.size) {
            1 -> nums[0]
            2 -> nums[0] * 100 + nums[1]
            else -> nums[0] * 10000 + nums[1] * 100 + nums[2]
        }
    }

    private fun isNewerName(remote: String, local: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").split('.', '-', '+')
            .map { it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
        val a = parts(remote)
        val b = parts(local)
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
