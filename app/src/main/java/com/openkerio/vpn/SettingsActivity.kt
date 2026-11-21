package com.openkerio.vpn

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.openkerio.vpn.data.AppLanguage
import com.openkerio.vpn.data.AppSettings
import com.openkerio.vpn.data.CipherPreference
import com.openkerio.vpn.data.ConnectionPrefs
import com.openkerio.vpn.data.SessionLog
import com.openkerio.vpn.data.ThemeMode
import com.openkerio.vpn.vpn.KerioVpnService

class SettingsActivity : AppCompatActivity() {
    private lateinit var settings: AppSettings
    private lateinit var themeSpinner: Spinner
    private lateinit var languageSpinner: Spinner
    private lateinit var cipherSpinner: Spinner
    private lateinit var aboutText: TextView
    private lateinit var resetPanel: LinearLayout
    private var suppressPersist = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppSettings.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        setContentView(R.layout.activity_settings)

        themeSpinner = findViewById(R.id.themeSpinner)
        languageSpinner = findViewById(R.id.languageSpinner)
        cipherSpinner = findViewById(R.id.cipherSpinner)
        aboutText = findViewById(R.id.aboutText)
        resetPanel = findViewById(R.id.resetPanel)

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        suppressPersist = true
        themeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf(
                getString(R.string.theme_system),
                getString(R.string.theme_light),
                getString(R.string.theme_dark),
            ),
        )
        languageSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf(
                getString(R.string.lang_system),
                getString(R.string.lang_ru),
                getString(R.string.lang_en),
            ),
        )
        cipherSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf(
                getString(R.string.cipher_auto),
                getString(R.string.cipher_aes),
                getString(R.string.cipher_legacy),
            ),
        )

        bindSettingsToUi()
        suppressPersist = false

        aboutText.text = getString(R.string.settings_version_format, BuildConfig.VERSION_NAME)

        val applyListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                applySettingsLive()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        themeSpinner.onItemSelectedListener = applyListener
        languageSpinner.onItemSelectedListener = applyListener
        cipherSpinner.onItemSelectedListener = applyListener

        findViewById<MaterialButton>(R.id.openLogButton).setOnClickListener {
            SessionLogActivity.start(this)
        }
        findViewById<MaterialButton>(R.id.resetSectionButton).setOnClickListener {
            resetPanel.visibility =
                if (resetPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<MaterialButton>(R.id.fullResetButton).setOnClickListener {
            confirmFullReset()
        }
        findViewById<MaterialButton>(R.id.settingsResetButton).setOnClickListener {
            confirmSettingsReset()
        }
    }

    private fun bindSettingsToUi() {
        themeSpinner.setSelection(settings.themeMode.ordinal.coerceIn(0, ThemeMode.entries.lastIndex), false)
        languageSpinner.setSelection(settings.language.ordinal.coerceIn(0, AppLanguage.entries.lastIndex), false)
        cipherSpinner.setSelection(
            settings.cipherPreference.ordinal.coerceIn(0, CipherPreference.entries.lastIndex),
            false,
        )
    }

    override fun onPause() {
        applySettingsLive(showToasts = false)
        super.onPause()
    }

    private fun applySettingsLive(showToasts: Boolean = true) {
        if (suppressPersist || !::settings.isInitialized || !::themeSpinner.isInitialized) return
        val oldLang = settings.language
        val oldTheme = settings.themeMode
        val oldCipher = settings.cipherPreference

        settings.themeMode = ThemeMode.entries.getOrElse(themeSpinner.selectedItemPosition) { ThemeMode.SYSTEM }
        settings.language = AppLanguage.entries.getOrElse(languageSpinner.selectedItemPosition) {
            AppLanguage.SYSTEM
        }
        settings.cipherPreference = CipherPreference.entries.getOrElse(
            cipherSpinner.selectedItemPosition,
        ) { CipherPreference.AUTO }

        if (settings.themeMode != oldTheme) {
            settings.applyTheme()
            // AppCompat recreates activities for night mode — no process restart needed.
        }

        if (settings.language != oldLang) {
            AppSettings.notifyLocaleChanged()
            if (showToasts) {
                Toast.makeText(this, R.string.settings_restart_language, Toast.LENGTH_SHORT).show()
            }
            recreate()
            return
        }

        if (settings.cipherPreference != oldCipher) {
            val vpnActive = KerioVpnService.lastStatus == KerioVpnService.STATUS_CONNECTED ||
                KerioVpnService.lastStatus == KerioVpnService.STATUS_CONNECTING ||
                KerioVpnService.lastStatus == KerioVpnService.STATUS_RECONNECTING
            if (showToasts) {
                Toast.makeText(
                    this,
                    if (vpnActive) R.string.settings_cipher_reconnect else R.string.settings_cipher_saved,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun confirmFullReset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_full_reset)
            .setMessage(R.string.settings_full_reset_confirm)
            .setPositiveButton(R.string.settings_full_reset) { _, _ ->
                performFullReset()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun performFullReset() {
        runCatching { KerioVpnService.stop(this) }
        SessionLog.clear()
        val am = getSystemService(ActivityManager::class.java)
        val cleared = am?.clearApplicationUserData() == true
        if (!cleared) {
            // Fallback if the system rejects clearApplicationUserData.
            AppSettings(this).clearAll()
            ConnectionPrefs(this).clearAll()
            cacheDir.deleteRecursively()
            filesDir.deleteRecursively()
            finishAffinity()
            Runtime.getRuntime().exit(0)
        }
    }

    private fun confirmSettingsReset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_reset_settings)
            .setMessage(R.string.settings_reset_settings_confirm)
            .setPositiveButton(R.string.settings_reset_settings) { _, _ ->
                performSettingsReset()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun performSettingsReset() {
        val oldLang = settings.language
        settings.resetToDefaults()
        settings.applyTheme()
        suppressPersist = true
        bindSettingsToUi()
        suppressPersist = false
        Toast.makeText(this, R.string.settings_reset_settings_done, Toast.LENGTH_SHORT).show()
        if (oldLang != settings.language) {
            AppSettings.notifyLocaleChanged()
            recreate()
        }
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, SettingsActivity::class.java))
        }
    }
}
