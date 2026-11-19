package com.openkerio.vpn.data

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import java.util.Locale

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class AppLanguage { SYSTEM, RU, EN }

enum class CipherPreference { AUTO, AES, LEGACY }

class AppSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var themeMode: ThemeMode
        get() = ThemeMode.entries.getOrElse(prefs.getInt(KEY_THEME, 0)) { ThemeMode.SYSTEM }
        set(value) {
            prefs.edit().putInt(KEY_THEME, value.ordinal).apply()
        }

    var language: AppLanguage
        get() = AppLanguage.entries.getOrElse(prefs.getInt(KEY_LANG, 0)) { AppLanguage.SYSTEM }
        set(value) {
            prefs.edit().putInt(KEY_LANG, value.ordinal).apply()
        }

    var cipherPreference: CipherPreference
        get() = CipherPreference.entries.getOrElse(prefs.getInt(KEY_CIPHER, 0)) { CipherPreference.AUTO }
        set(value) {
            prefs.edit().putInt(KEY_CIPHER, value.ordinal).apply()
        }

    /** owner/repo for GitHub releases, e.g. "myorg/openkerio-android" */
    var updateRepo: String
        get() = prefs.getString(KEY_UPDATE_REPO, DEFAULT_UPDATE_REPO)?.trim().orEmpty()
        set(value) {
            prefs.edit().putString(KEY_UPDATE_REPO, value.trim()).apply()
        }

    var dismissedUpdateTag: String
        get() = prefs.getString(KEY_DISMISSED_TAG, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_DISMISSED_TAG, value).apply()
        }

    var lastUpdateCheckAt: Long
        get() = prefs.getLong(KEY_LAST_CHECK, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_CHECK, value).apply()
        }

    var autoCheckUpdates: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CHECK, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_CHECK, value).apply()
        }

    fun applyTheme() {
        val mode = when (themeMode) {
            ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    fun resolveLocale(base: Context): Locale? {
        return when (language) {
            AppLanguage.SYSTEM -> null
            AppLanguage.RU -> Locale("ru")
            AppLanguage.EN -> Locale.ENGLISH
        }
    }

    companion object {
        private const val PREFS = "openkerio_settings"
        private const val KEY_THEME = "theme"
        private const val KEY_LANG = "lang"
        private const val KEY_CIPHER = "cipher"
        private const val KEY_UPDATE_REPO = "update_repo"
        private const val KEY_DISMISSED_TAG = "dismissed_update_tag"
        private const val KEY_LAST_CHECK = "last_update_check"
        private const val KEY_AUTO_CHECK = "auto_check_updates"

        /** Default GitHub Releases source for in-app updates. */
        const val DEFAULT_UPDATE_REPO = "artyomka101/kerioopen"

        fun wrapContext(base: Context): Context {
            val settings = AppSettings(base)
            val locale = settings.resolveLocale(base) ?: return base
            val config = Configuration(base.resources.configuration)
            config.setLocale(locale)
            return base.createConfigurationContext(config)
        }
    }
}
