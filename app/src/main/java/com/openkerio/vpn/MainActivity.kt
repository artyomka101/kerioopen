package com.openkerio.vpn

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.openkerio.vpn.data.AppSettings
import com.openkerio.vpn.data.ConnectionConfig
import com.openkerio.vpn.data.ConnectionPrefs
import com.openkerio.vpn.data.SavedProfile
import com.openkerio.vpn.data.SessionLog
import com.openkerio.vpn.ui.SpeedGraphView
import com.openkerio.vpn.vpn.KerioVpnService
import com.openkerio.vpn.vpn.NativeEngine
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: ConnectionPrefs
    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var errorText: TextView
    private lateinit var rxSpeedText: TextView
    private lateinit var txSpeedText: TextView
    private lateinit var uptimeText: TextView
    private lateinit var speedPanel: View
    private lateinit var speedGraph: SpeedGraphView
    private lateinit var profilesLabel: TextView
    private val uiHandler = Handler(Looper.getMainLooper())
    private var connectedAtMs = 0L
    private var lastWireVersion = 0
    private var lastCipher = ""
    private val uptimeTicker = object : Runnable {
        override fun run() {
            if (connectedAtMs > 0L) {
                uptimeText.text = getString(R.string.uptime_format, formatUptime(connectedAtMs))
                uiHandler.postDelayed(this, 1_000L)
            }
        }
    }
    private lateinit var profilesList: RecyclerView
    private lateinit var nameInput: TextInputEditText
    private lateinit var serverInput: TextInputEditText
    private lateinit var usernameInput: TextInputEditText
    private lateinit var passwordInput: TextInputEditText
    private lateinit var savePasswordCheck: CheckBox
    private lateinit var persistentCheck: CheckBox
    private lateinit var connectButton: MaterialButton
    private lateinit var disconnectButton: MaterialButton
    private lateinit var clearButton: MaterialButton

    private var busy = false
    private var localeEpochSeen = AppSettings.localeEpoch
    private lateinit var profilesAdapter: ProfilesAdapter

    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpn()
        } else {
            errorText.text = getString(R.string.status_error)
            renderStatus(KerioVpnService.STATUS_DISCONNECTED, "")
        }
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != KerioVpnService.ACTION_STATUS) return
            val status = intent.getStringExtra(KerioVpnService.EXTRA_STATUS)
                ?: KerioVpnService.STATUS_DISCONNECTED
            val detail = intent.getStringExtra(KerioVpnService.EXTRA_DETAIL).orEmpty()
            val rx = intent.getLongExtra(KerioVpnService.EXTRA_RX_BPS, 0L)
            val tx = intent.getLongExtra(KerioVpnService.EXTRA_TX_BPS, 0L)
            val connectedAt = intent.getLongExtra(KerioVpnService.EXTRA_CONNECTED_AT, 0L)
            lastWireVersion = intent.getIntExtra(KerioVpnService.EXTRA_WIRE_VERSION, lastWireVersion)
            lastCipher = intent.getStringExtra(KerioVpnService.EXTRA_CIPHER) ?: lastCipher
            renderStatus(status, detail, rx, tx, connectedAt)
            if (status == KerioVpnService.STATUS_CONNECTED) {
                speedGraph.push(rx, tx)
                refreshProfiles()
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppSettings.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = ConnectionPrefs(this)
        bindViews()
        setupProfilesList()
        loadForm()
        refreshProfiles()
        maybeRequestNotificationPermission()

        findViewById<ImageButton>(R.id.settingsButton).setOnClickListener {
            SettingsActivity.start(this)
        }
        clearButton.setOnClickListener { clearForm() }
        connectButton.setOnClickListener { requestVpnAndConnect() }
        disconnectButton.setOnClickListener {
            KerioVpnService.stop(this)
            SessionLog.i("disconnect requested by user")
            renderStatus(KerioVpnService.STATUS_DISCONNECTED, "")
        }
        val filter = IntentFilter(KerioVpnService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onResume() {
        super.onResume()
        if (localeEpochSeen != AppSettings.localeEpoch) {
            localeEpochSeen = AppSettings.localeEpoch
            recreate()
            return
        }
        lastWireVersion = KerioVpnService.lastWireVersion
        lastCipher = KerioVpnService.lastCipher
        renderStatus(
            KerioVpnService.lastStatus,
            KerioVpnService.lastDetail,
            KerioVpnService.lastRxBps,
            KerioVpnService.lastTxBps,
            KerioVpnService.connectedAtMs,
        )
        refreshProfiles()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(uptimeTicker)
        runCatching { unregisterReceiver(statusReceiver) }
        super.onDestroy()
    }

    private fun bindViews() {
        statusText = findViewById(R.id.statusText)
        detailText = findViewById(R.id.detailText)
        errorText = findViewById(R.id.errorText)
        rxSpeedText = findViewById(R.id.rxSpeedText)
        txSpeedText = findViewById(R.id.txSpeedText)
        uptimeText = findViewById(R.id.uptimeText)
        speedPanel = findViewById(R.id.speedPanel)
        speedGraph = findViewById(R.id.speedGraph)
        profilesLabel = findViewById(R.id.profilesLabel)
        profilesList = findViewById(R.id.profilesList)
        nameInput = findViewById(R.id.nameInput)
        serverInput = findViewById(R.id.serverInput)
        usernameInput = findViewById(R.id.usernameInput)
        passwordInput = findViewById(R.id.passwordInput)
        savePasswordCheck = findViewById(R.id.savePasswordCheck)
        persistentCheck = findViewById(R.id.persistentCheck)
        connectButton = findViewById(R.id.connectButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        clearButton = findViewById(R.id.clearButton)
    }

    private fun setupProfilesList() {
        profilesAdapter = ProfilesAdapter(
            onClick = { applyProfile(it) },
            onLongClick = { confirmDeleteProfile(it) },
        )
        profilesList.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        profilesList.adapter = profilesAdapter
    }

    private fun refreshProfiles() {
        val list = prefs.listProfiles()
        profilesAdapter.submit(list)
        val visible = if (list.isEmpty()) View.GONE else View.VISIBLE
        profilesLabel.visibility = visible
        profilesList.visibility = visible
    }

    private fun applyProfile(profile: SavedProfile) {
        val cfg = profile.toConfig()
        nameInput.setText(cfg.displayName)
        serverInput.setText(cfg.server)
        usernameInput.setText(cfg.username)
        passwordInput.setText(cfg.password)
        savePasswordCheck.isChecked = cfg.savePassword
        persistentCheck.isChecked = cfg.persistent
        prefs.save(cfg)
        errorText.text = ""
    }

    private fun confirmDeleteProfile(profile: SavedProfile) {
        AlertDialog.Builder(this)
            .setMessage(R.string.delete_profile)
            .setPositiveButton(R.string.delete) { _, _ ->
                prefs.deleteProfile(profile.id)
                refreshProfiles()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun loadForm() {
        val cfg = prefs.load()
        nameInput.setText(cfg.displayName)
        serverInput.setText(cfg.server)
        usernameInput.setText(cfg.username)
        passwordInput.setText(cfg.password)
        savePasswordCheck.isChecked = cfg.savePassword
        persistentCheck.isChecked = cfg.persistent
        renderStatus(KerioVpnService.lastStatus, KerioVpnService.lastDetail)
    }

    private fun formConfig(): ConnectionConfig {
        val server = serverInput.text?.toString()?.trim().orEmpty()
        return ConnectionConfig(
            displayName = nameInput.text?.toString()?.trim().orEmpty(),
            server = server,
            username = usernameInput.text?.toString()?.trim().orEmpty(),
            password = passwordInput.text?.toString().orEmpty(),
            savePassword = savePasswordCheck.isChecked,
            persistent = persistentCheck.isChecked,
            trustedFingerprintSha256 = fingerprintForServer(server),
        )
    }

    /** Fingerprint is valid only for the same server it was trusted for. */
    private fun fingerprintForServer(server: String): String {
        val normalized = server.trim()
        if (normalized.isBlank()) return ""
        val saved = prefs.load()
        if (
            normalized.equals(saved.server, ignoreCase = true) &&
            saved.trustedFingerprintSha256.isNotBlank()
        ) {
            return saved.trustedFingerprintSha256
        }
        return prefs.listProfiles()
            .firstOrNull {
                it.server.equals(normalized, ignoreCase = true) &&
                    it.trustedFingerprintSha256.isNotBlank()
            }
            ?.trustedFingerprintSha256
            .orEmpty()
    }

    private fun persistForm(fingerprintOverride: String? = null) {
        val cfg = formConfig().let { base ->
            if (fingerprintOverride != null) {
                base.copy(trustedFingerprintSha256 = fingerprintOverride)
            } else {
                base
            }
        }
        prefs.save(cfg)
    }

    private fun clearForm() {
        if (busy) return
        nameInput.setText("")
        serverInput.setText("")
        usernameInput.setText("")
        passwordInput.setText("")
        savePasswordCheck.isChecked = true
        persistentCheck.isChecked = false
        errorText.text = ""
        prefs.save(ConnectionConfig())
        SessionLog.i("form cleared")
    }

    /** Probe server certificate, trust it, then connect without an extra confirmation step. */
    private fun probeCertificateAndConnect() {
        if (busy) return
        val (host, port) = KerioVpnService.parseServer(serverInput.text?.toString().orEmpty())
        if (host.isBlank()) {
            errorText.text = getString(R.string.error_invalid_credentials)
            return
        }
        busy = true
        errorText.text = ""
        detailText.text = getString(R.string.checking_cert)
        renderStatus(KerioVpnService.STATUS_CONNECTING, getString(R.string.checking_cert))
        thread(name = "probe-cert") {
            try {
                val fp = NativeEngine.probeCertificate(host, port).lowercase()
                runOnUiThread {
                    busy = false
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    prefs.saveTrustedFingerprint(fp)
                    persistForm(fingerprintOverride = fp)
                    SessionLog.i("certificate auto-trusted for $host")
                    launchVpnPermissionOrStart()
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    busy = false
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val raw = t.message.orEmpty()
                    val msg = when {
                        KerioVpnService.isServerUnreachableMessage(raw) ->
                            getString(R.string.error_server_unreachable)
                        raw.isNotBlank() -> raw
                        else -> getString(R.string.cert_check_failed)
                    }
                    SessionLog.e("cert probe failed: ${t.message}")
                    renderStatus(KerioVpnService.STATUS_ERROR, msg)
                }
            }
        }
    }

    private fun requestVpnAndConnect() {
        if (busy) return
        val cfg = formConfig()
        if (cfg.server.isBlank() || cfg.username.isBlank() || cfg.password.isBlank()) {
            errorText.text = getString(R.string.error_invalid_credentials)
            return
        }
        errorText.text = ""
        if (cfg.trustedFingerprintSha256.isBlank()) {
            probeCertificateAndConnect()
            return
        }
        persistForm()
        launchVpnPermissionOrStart()
    }

    private fun launchVpnPermissionOrStart() {
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            vpnPermission.launch(prepare)
        } else {
            startVpn()
        }
    }

    private fun startVpn() {
        val cfg = formConfig()
        if (cfg.server.isBlank() || cfg.username.isBlank() || cfg.password.isBlank()) {
            errorText.text = getString(R.string.error_invalid_credentials)
            return
        }
        if (cfg.trustedFingerprintSha256.isBlank()) {
            errorText.text = getString(R.string.cert_check_failed)
            return
        }
        persistForm()
        speedGraph.clear()
        SessionLog.i("connect requested name=${cfg.displayName.ifBlank { "-" }} server=${cfg.server}")
        KerioVpnService.start(this, cfg)
        renderStatus(KerioVpnService.STATUS_CONNECTING, "")
    }

    private fun renderStatus(
        status: String,
        detail: String,
        rxBps: Long = 0L,
        txBps: Long = 0L,
        connectedAt: Long = 0L,
    ) {
        statusText.text = when (status) {
            KerioVpnService.STATUS_CONNECTED -> getString(R.string.status_connected)
            KerioVpnService.STATUS_CONNECTING -> getString(R.string.status_connecting)
            KerioVpnService.STATUS_RECONNECTING -> getString(R.string.status_reconnecting)
            KerioVpnService.STATUS_ERROR -> getString(R.string.status_error)
            else -> getString(R.string.status_disconnected)
        }
        detailText.text = when {
            status == KerioVpnService.STATUS_ERROR -> ""
            else -> detail
        }
        when (status) {
            KerioVpnService.STATUS_ERROR -> errorText.text = detail
            KerioVpnService.STATUS_CONNECTING,
            KerioVpnService.STATUS_CONNECTED,
            KerioVpnService.STATUS_RECONNECTING,
            -> errorText.text = ""
        }

        val connected = status == KerioVpnService.STATUS_CONNECTED
        speedPanel.visibility = if (connected) View.VISIBLE else View.GONE
        if (connected) {
            rxSpeedText.text = "↓ ${formatRate(rxBps)}"
            txSpeedText.text = "↑ ${formatRate(txBps)}"
            // Always trust service uptime so reconnects reset the timer.
            connectedAtMs = connectedAt
            if (connectedAtMs > 0L) {
                uptimeText.visibility = View.VISIBLE
                uptimeText.text = getString(R.string.uptime_format, formatUptime(connectedAtMs))
                uiHandler.removeCallbacks(uptimeTicker)
                uiHandler.postDelayed(uptimeTicker, 1_000L)
            } else {
                uptimeText.visibility = View.GONE
                uiHandler.removeCallbacks(uptimeTicker)
            }
        } else {
            connectedAtMs = 0L
            uptimeText.visibility = View.GONE
            uiHandler.removeCallbacks(uptimeTicker)
            if (status == KerioVpnService.STATUS_DISCONNECTED || status == KerioVpnService.STATUS_ERROR) {
                speedGraph.clear()
                lastWireVersion = 0
                lastCipher = ""
            }
        }

        val connecting = status == KerioVpnService.STATUS_CONNECTING ||
            status == KerioVpnService.STATUS_RECONNECTING
        connectButton.isEnabled = !connected && !connecting && !busy
        disconnectButton.isEnabled = connected || connecting
        clearButton.isEnabled = !connected && !connecting && !busy
        nameInput.isEnabled = !connected && !connecting
        serverInput.isEnabled = !connected && !connecting
        usernameInput.isEnabled = !connected && !connecting
        passwordInput.isEnabled = !connected && !connecting
        profilesList.isEnabled = !connected && !connecting
    }

    private fun formatUptime(connectedAt: Long): String {
        val sec = ((System.currentTimeMillis() - connectedAt) / 1000L).coerceAtLeast(0)
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) {
            "%d:%02d:%02d".format(h, m, s)
        } else {
            "%02d:%02d".format(m, s)
        }
    }

    private fun formatRate(bps: Long): String {
        val bits = bps * 8.0
        return when {
            bits >= 1_000_000 -> getString(R.string.rate_mbps, bits / 1_000_000.0)
            bits >= 1_000 -> getString(R.string.rate_kbps, bits / 1_000.0)
            else -> getString(R.string.rate_bps, bits)
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private class ProfilesAdapter(
        private val onClick: (SavedProfile) -> Unit,
        private val onLongClick: (SavedProfile) -> Unit,
    ) : RecyclerView.Adapter<ProfilesAdapter.Holder>() {
        private val items = ArrayList<SavedProfile>()

        fun submit(list: List<SavedProfile>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_profile, parent, false)
            return Holder(view as TextView)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.label.text = item.label()
            holder.label.setOnClickListener { onClick(item) }
            holder.label.setOnLongClickListener {
                onLongClick(item)
                true
            }
        }

        override fun getItemCount(): Int = items.size

        class Holder(val label: TextView) : RecyclerView.ViewHolder(label)
    }
}
