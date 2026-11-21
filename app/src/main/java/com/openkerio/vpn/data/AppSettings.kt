package com.openkerio.vpn.data

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import java.util.Locale

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class AppLanguage { SYSTEM, RU, EN }

enum class CipherPreference { AUTO, AES, LEGACY }

class AppSettings(context: Context) {
    // applicationContext is null during Application.attachBaseContext — fall back to base.
    private val prefs = (context.applicationContext ?: context)
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var themeMode: ThemeMode
        get() = ThemeMode.entries.getOrElse(prefs.getInt(KEY_THEME, ThemeMode.DARK.ordinal)) { ThemeMode.DARK }
        set(value) {
            prefs.edit().putInt(KEY_THEME, value.ordinal).apply()
        }

    var language: AppLanguage
        get() = AppLanguage.entries.getOrElse(prefs.getInt(KEY_LANG, AppLanguage.RU.ordinal)) { AppLanguage.RU }
        set(value) {
            prefs.edit().putInt(KEY_LANG, value.ordinal).apply()
        }

    var cipherPreference: CipherPreference
        get() = CipherPreference.entries.getOrElse(prefs.getInt(KEY_CIPHER, 0)) { CipherPreference.AUTO }
        set(value) {
            prefs.edit().putInt(KEY_CIPHER, value.ordinal).apply()
        }

    fun applyTheme() {
        val mode = when (themeMode) {
            ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    /** Restores settings defaults; does not touch connection profiles/servers. */
    fun resetToDefaults() {
        prefs.edit()
            .putInt(KEY_THEME, ThemeMode.DARK.ordinal)
            .putInt(KEY_LANG, AppLanguage.RU.ordinal)
            .putInt(KEY_CIPHER, CipherPreference.AUTO.ordinal)
            .commit()
    }

    fun clearAll() {
        prefs.edit().clear().commit()
    }

    fun resolveLocale(): Locale? {
        return when (language) {
            AppLanguage.SYSTEM -> null
            AppLanguage.RU -> Locale.forLanguageTag("ru")
            AppLanguage.EN -> Locale.ENGLISH
        }
    }

    companion object {
        private const val PREFS = "openkerio_settings"
        private const val KEY_THEME = "theme"
        private const val KEY_LANG = "lang"
        private const val KEY_CIPHER = "cipher"

        /** Bumped when language changes so underlying activities can recreate. */
        @Volatile
        var localeEpoch: Int = 0
            private set

        fun notifyLocaleChanged() {
            localeEpoch += 1
        }

        fun wrapContext(base: Context): Context {
            return try {
                val locale = AppSettings(base).resolveLocale() ?: return base
                val config = Configuration(base.resources.configuration)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    config.setLocales(LocaleList(locale))
                } else {
                    @Suppress("DEPRECATION")
                    config.locale = locale
                }
                config.setLocale(locale)
                base.createConfigurationContext(config)
            } catch (_: Throwable) {
                base
            }
        }
    }
}
