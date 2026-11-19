package com.openkerio.vpn

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.openkerio.vpn.data.AppLanguage
import com.openkerio.vpn.data.AppSettings
import com.openkerio.vpn.data.CipherPreference
import com.openkerio.vpn.data.ThemeMode
import com.openkerio.vpn.update.UpdateCheckResult
import com.openkerio.vpn.update.UpdateChecker
import com.openkerio.vpn.vpn.KerioVpnService
import kotlin.concurrent.thread

class SettingsActivity : AppCompatActivity() {
    private lateinit var settings: AppSettings
    private lateinit var themeSpinner: Spinner
    private lateinit var languageSpinner: Spinner
    private lateinit var cipherSpinner: Spinner
    private lateinit var autoUpdateCheck: CheckBox
    private lateinit var updateStatusText: TextView
    private lateinit var aboutText: TextView
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
        autoUpdateCheck = findViewById(R.id.autoUpdateCheck)
        updateStatusText = findViewById(R.id.updateStatusText)
        aboutText = findViewById(R.id.aboutText)

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

        themeSpinner.setSelection(settings.themeMode.ordinal.coerceIn(0, ThemeMode.entries.lastIndex), false)
        languageSpinner.setSelection(settings.language.ordinal.coerceIn(0, AppLanguage.entries.lastIndex), false)
        cipherSpinner.setSelection(
            settings.cipherPreference.ordinal.coerceIn(0, CipherPreference.entries.lastIndex),
            false,
        )
        autoUpdateCheck.isChecked = settings.autoCheckUpdates
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
        autoUpdateCheck.setOnCheckedChangeListener { _, _ ->
            if (!suppressPersist) {
                settings.autoCheckUpdates = autoUpdateCheck.isChecked
            }
        }

        findViewById<MaterialButton>(R.id.checkUpdateButton).setOnClickListener {
            checkUpdates(manual = true)
        }
        findViewById<MaterialButton>(R.id.openLogButton).setOnClickListener {
            SessionLogActivity.start(this)
        }
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
        settings.autoCheckUpdates = autoUpdateCheck.isChecked

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

    private fun checkUpdates(manual: Boolean) {
        updateStatusText.text = getString(R.string.update_checking)
        thread(name = "update-check") {
            val result = UpdateChecker.check(settings, ignoreDismissed = manual)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (result) {
                    is UpdateCheckResult.Available ->
                        showUpdateDialog(result.info.tag, result.info.htmlUrl, result.info.body)
                    UpdateCheckResult.UpToDate -> {
                        updateStatusText.text = getString(R.string.update_up_to_date)
                        if (manual) {
                            Toast.makeText(this, R.string.update_up_to_date, Toast.LENGTH_SHORT).show()
                        }
                    }
                    UpdateCheckResult.Dismissed -> {
                        updateStatusText.text = getString(R.string.update_dismissed)
                    }
                    UpdateCheckResult.RepoNotConfigured -> {
                        updateStatusText.text = getString(R.string.update_repo_missing)
                        if (manual) {
                            Toast.makeText(this, R.string.update_repo_missing, Toast.LENGTH_LONG).show()
                        }
                    }
                    is UpdateCheckResult.Error -> {
                        updateStatusText.text = getString(R.string.update_error, result.message)
                    }
                }
            }
        }
    }

    private fun showUpdateDialog(tag: String, url: String, body: String) {
        if (isFinishing || isDestroyed || tag.isBlank() || url.isBlank()) return
        updateStatusText.text = getString(R.string.update_available, tag)
        AlertDialog.Builder(this)
            .setTitle(R.string.update_available_title)
            .setMessage(
                getString(R.string.update_available_message, tag) +
                    if (body.isNotBlank()) "\n\n" + body.take(400) else "",
            )
            .setPositiveButton(R.string.update_open) { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            }
            .setNegativeButton(R.string.update_later) { _, _ ->
                settings.dismissedUpdateTag = tag
                updateStatusText.text = getString(R.string.update_dismissed)
            }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, SettingsActivity::class.java))
        }
    }
}
