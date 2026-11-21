package com.openkerio.vpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.openkerio.vpn.MainActivity
import com.openkerio.vpn.R
import com.openkerio.vpn.data.AppSettings
import com.openkerio.vpn.data.ConnectionConfig
import com.openkerio.vpn.data.ConnectionPrefs
import com.openkerio.vpn.data.SessionLog
import java.net.InetAddress
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException
import kotlin.concurrent.thread

class KerioVpnService : VpnService() {
    private val running = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private val networkReconnect = AtomicBoolean(false)
    private val reconnectAttempt = AtomicInteger(0)
    private var tun: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private var sessionConfig: ConnectionConfig? = null
    private var profileSaved = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastUnderlyingNetwork: Network? = null
    private var pendingLostNetwork: Network? = null
    private val knownNetworks = LinkedHashSet<Network>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val networkSwitchRunnable = Runnable {
        if (stopping.get() || !running.get()) return@Runnable
        pendingLostNetwork = null
        Log.i(TAG, "underlying network changed — reconnecting")
        SessionLog.w("network change → reconnect")
        networkReconnect.set(true)
        resetUptime()
        publishStatus(STATUS_RECONNECTING, getString(R.string.status_network_switch))
        updateStatusNotification(STATUS_RECONNECTING, getString(R.string.status_network_switch))
        runCatching { NativeEngine.stop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must enter foreground immediately after startForegroundService — before any early return.
        ensureForegroundStarted(getString(R.string.vpn_notification_connecting))
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                disconnectInternal(userRequested = true)
                return START_NOT_STICKY
            }
            ACTION_CONNECT, null -> {
                sessionConfig = takePendingConfig()
                    ?: sessionConfig
                    ?: ConnectionPrefs(this).load()
                profileSaved = false
                reconnectAttempt.set(0)
                connectInternal()
            }
        }
        return START_STICKY
    }

    private fun ensureForegroundStarted(content: String) {
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(content),
                type,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed", t)
        }
    }

    override fun onDestroy() {
        disconnectInternal(userRequested = true)
        super.onDestroy()
    }

    private fun connectInternal() {
        if (!running.compareAndSet(false, true)) {
            ensureForegroundStarted(
                if (lastStatus == STATUS_CONNECTED) {
                    getString(R.string.vpn_notification_connected)
                } else {
                    getString(R.string.vpn_notification_connecting)
                },
            )
            return
        }
        stopping.set(false)
        networkReconnect.set(false)
        ensureForegroundStarted(getString(R.string.vpn_notification_connecting))
        registerNetworkWatch()

        val config = sessionConfig ?: ConnectionPrefs(this).load()
        sessionConfig = config
        val (host, port) = parseServer(config.server)
        if (host.isBlank() || config.username.isBlank() || config.password.isBlank()) {
            publishStatus(STATUS_ERROR, "Заполните поля")
            disconnectInternal(userRequested = true)
            return
        }
        if (config.trustedFingerprintSha256.isBlank()) {
            publishStatus(STATUS_ERROR, "Подтвердите сертификат")
            disconnectInternal(userRequested = true)
            return
        }

        closeTun()

        worker = thread(name = "kerio-vpn-engine", isDaemon = true) {
            var failure: Throwable? = null
            try {
                val cipherPref = AppSettings(this@KerioVpnService).cipherPreference
                SessionLog.i("connecting host=$host:$port cipherPref=$cipherPref")
                publishStatus(STATUS_CONNECTING, host)
                updateStatusNotification(STATUS_CONNECTING, host)
                NativeEngine.start(
                    host = host,
                    port = port,
                    username = config.username,
                    password = config.password,
                    fingerprintSha256 = config.trustedFingerprintSha256,
                    cipherPreference = cipherPref,
                    callbacks = object : NativeEngine.TunCallbacks {
                        override fun configureTun(
                            address: String,
                            prefixLength: Int,
                            dnsServers: Array<String>,
                            routes: Array<String>,
                            mtu: Int,
                        ): Int = openTun(address, prefixLength, dnsServers, routes, mtu)

                        override fun onStatus(status: String, detail: String) {
                            when (status) {
                                STATUS_CONNECTED -> {
                                    reconnectAttempt.set(0)
                                    if (connectedAtMs == 0L) {
                                        connectedAtMs = System.currentTimeMillis()
                                    }
                                }
                                STATUS_RECONNECTING -> resetUptime()
                                STATUS_CONNECTING -> {
                                    // Keep uptime only across brief CONNECTING after first connect;
                                    // network/engine reconnect paths zero it explicitly.
                                }
                                else -> resetUptime()
                            }
                            // Show Kerio server address (not tunnel IP) when connected.
                            val statusDetail = if (status == STATUS_CONNECTED) {
                                config.server.trim().ifBlank { detail }
                            } else {
                                detail
                            }
                            publishStatus(status, statusDetail)
                            updateStatusNotification(status, statusDetail)
                            if (status == STATUS_CONNECTED) {
                                if (!profileSaved) {
                                    profileSaved = true
                                    runCatching {
                                        ConnectionPrefs(this@KerioVpnService).upsertProfile(config)
                                    }
                                }
                            }
                        }

                        override fun onSessionInfo(wireVersion: Int, cipher: String) {
                            lastWireVersion = wireVersion
                            lastCipher = cipher
                            SessionLog.i("session wire=v$wireVersion cipher=$cipher")
                        }

                        override fun onRoutesUpdated(routes: Array<String>) {
                            Log.i(TAG, "Routes updated: ${routes.joinToString()}")
                            SessionLog.i("routes update count=${routes.size}")
                        }

                        override fun onTraffic(rxBps: Long, txBps: Long) {
                            publishTraffic(rxBps, txBps)
                            if (lastStatus == STATUS_CONNECTED || lastStatus == STATUS_CONNECTING ||
                                lastStatus == STATUS_RECONNECTING
                            ) {
                                updateStatusNotification(lastStatus, lastDetail)
                            }
                        }

                        override fun protectSocket(fd: Int): Boolean = protectFdOrNetwork(fd)

                        override fun protectTcpSocket(socket: java.net.Socket): Boolean =
                            protectTcp(socket)

                        override fun protectUdpSocket(socket: java.net.DatagramSocket): Boolean =
                            protectUdp(socket)
                    },
                )
            } catch (t: Throwable) {
                failure = t
                SessionLog.e("engine failed: ${t.message}")
                Log.e(TAG, "VPN engine failed", t)
            } finally {
                val cancelled = isCancellation(failure)
                val fatal = isFatalConfigError(failure)
                val fromNetworkSwitch = networkReconnect.getAndSet(false)
                val transient = fromNetworkSwitch || isTransientNetworkError(failure)
                val persistent = sessionConfig?.persistent == true
                val shouldReconnect = !stopping.get() &&
                    !cancelled &&
                    !fatal &&
                    (transient || persistent)
                if (shouldReconnect) {
                    val attempt = reconnectAttempt.incrementAndGet()
                    val delayMs = reconnectDelayMs(attempt, transient || fromNetworkSwitch)
                    val detail = when {
                        fromNetworkSwitch -> getString(R.string.status_network_switch)
                        transient -> friendlyNetworkError(failure)
                        else -> failure?.message.orEmpty()
                    }
                    resetUptime()
                    publishStatus(STATUS_RECONNECTING, detail)
                    updateStatusNotification(STATUS_RECONNECTING, detail)
                    Log.i(TAG, "reconnect attempt=$attempt delay=${delayMs}ms")
                    try {
                        Thread.sleep(delayMs)
                    } catch (_: InterruptedException) {
                    }
                    if (!stopping.get()) {
                        running.set(false)
                        closeTun()
                        connectInternal()
                        return@thread
                    }
                }
                if (!stopping.get() && failure != null && !cancelled) {
                    val err = failure
                    val msg = when {
                        isAuthError(err) -> getString(R.string.error_invalid_credentials)
                        fatal -> err.message ?: getString(R.string.status_error)
                        else -> friendlyNetworkError(err)
                    }
                    publishStatus(STATUS_ERROR, msg)
                    updateNotification(msg)
                }
                unregisterNetworkWatch()
                running.set(false)
                closeTun()
                if (!stopping.get() && (failure == null || cancelled)) {
                    publishStatus(STATUS_DISCONNECTED, "")
                }
                if (!shouldReconnect || stopping.get()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun disconnectInternal(userRequested: Boolean) {
        stopping.set(true)
        networkReconnect.set(false)
        reconnectAttempt.set(0)
        mainHandler.removeCallbacks(networkSwitchRunnable)
        unregisterNetworkWatch()
        try {
            NativeEngine.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "stop() failed", t)
        }
        worker?.interrupt()
        worker = null
        closeTun()
        running.set(false)
        if (userRequested) {
            sessionConfig = null
            clearPendingConfig()
            connectedAtMs = 0L
            publishStatus(STATUS_DISCONNECTED, "")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun registerNetworkWatch() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        unregisterNetworkWatch()
        lastUnderlyingNetwork = null
        knownNetworks.clear()
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                synchronized(knownNetworks) { knownNetworks.add(network) }
                onUnderlyingNetworkEvent(network, lost = false)
            }

            override fun onLost(network: Network) {
                synchronized(knownNetworks) { knownNetworks.remove(network) }
                onUnderlyingNetworkEvent(network, lost = true)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (!isUnderlyingNetwork(caps)) return
                synchronized(knownNetworks) { knownNetworks.add(network) }
                onUnderlyingNetworkEvent(network, lost = false)
            }
        }
        try {
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (t: Throwable) {
            Log.w(TAG, "registerNetworkCallback failed", t)
        }
    }

    private fun unregisterNetworkWatch() {
        val cb = networkCallback ?: return
        networkCallback = null
        lastUnderlyingNetwork = null
        pendingLostNetwork = null
        synchronized(knownNetworks) { knownNetworks.clear() }
        mainHandler.removeCallbacks(networkSwitchRunnable)
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { cm.unregisterNetworkCallback(cb) }
    }

    private fun onUnderlyingNetworkEvent(network: Network, lost: Boolean) {
        if (stopping.get()) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        if (lost) {
            if (network == lastUnderlyingNetwork) {
                // Brief Wi-Fi hiccup: wait before tearing the session.
                pendingLostNetwork = network
                lastUnderlyingNetwork = null
                SessionLog.i("underlying network lost (debounced)")
                scheduleNetworkReconnect(NETWORK_HICCUP_MS)
            }
            return
        }
        val caps = cm.getNetworkCapabilities(network) ?: return
        if (!isUnderlyingNetwork(caps)) return
        // Same network came back quickly — cancel reconnect.
        if (pendingLostNetwork == network) {
            pendingLostNetwork = null
            lastUnderlyingNetwork = network
            mainHandler.removeCallbacks(networkSwitchRunnable)
            SessionLog.i("underlying network recovered — keep session")
            return
        }
        val previous = lastUnderlyingNetwork
        if (previous == null) {
            lastUnderlyingNetwork = network
            if (pendingLostNetwork != null) {
                pendingLostNetwork = null
                scheduleNetworkReconnect(1_200L)
            }
            return
        }
        if (previous != network) {
            lastUnderlyingNetwork = network
            SessionLog.i("underlying network switched")
            scheduleNetworkReconnect(1_200L)
        }
    }

    private fun scheduleNetworkReconnect(delayMs: Long) {
        if (stopping.get() || !running.get()) return
        mainHandler.removeCallbacks(networkSwitchRunnable)
        mainHandler.postDelayed(networkSwitchRunnable, delayMs)
    }

    private fun isUnderlyingNetwork(caps: NetworkCapabilities): Boolean {
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)
    }

    private fun isTransientNetworkError(t: Throwable?): Boolean {
        if (t == null) return false
        if (isCancellation(t) || isFatalConfigError(t)) return false
        if (t is SocketException || t is UnknownHostException || t is SSLException) {
            return true
        }
        val text = throwableText(t)
        return text.contains("software caused connection abort") ||
            text.contains("connection reset") ||
            text.contains("broken pipe") ||
            text.contains("network is unreachable") ||
            text.contains("enetunreach") ||
            text.contains("econnaborted") ||
            text.contains("enotconn") ||
            text.contains("connection timed out") ||
            text.contains("tls closed by peer") ||
            text.contains("control timeout") ||
            text.contains("ssl handshake") ||
            text.contains("connection abort") ||
            text.contains("udp timeout") ||
            text.contains("rekey acknowledgment timeout")
    }

    private fun isAuthError(t: Throwable?): Boolean {
        val text = throwableText(t)
        return text.contains("authentication failed") ||
            text.contains("username rejected")
    }

    private fun isFatalConfigError(t: Throwable?): Boolean {
        val text = throwableText(t)
        return isAuthError(t) ||
            text.contains("certificate pin") ||
            text.contains("unsupported version") ||
            text.contains("ip config rejected") ||
            text.contains("udp rejected") ||
            text.contains("заполните") ||
            text.contains("подтвердите")
    }

    private fun isCancellation(t: Throwable?): Boolean {
        var cur = t
        while (cur != null) {
            if (cur.message.equals("cancelled", ignoreCase = true)) return true
            cur = cur.cause
        }
        return false
    }

    private fun throwableText(t: Throwable?): String {
        if (t == null) return ""
        return buildString {
            var cur: Throwable? = t
            while (cur != null) {
                append(cur.message.orEmpty())
                append(' ')
                cur = cur.cause
            }
        }.lowercase()
    }

    private fun reconnectDelayMs(attempt: Int, fastPath: Boolean): Long {
        val base = if (fastPath) 1_200L else 2_500L
        val exp = base * (1L shl (attempt - 1).coerceIn(0, 4))
        return exp.coerceAtMost(30_000L)
    }

    private fun friendlyNetworkError(t: Throwable?): String {
        val raw = t?.message ?: return getString(R.string.status_network_switch)
        val lower = raw.lowercase()
        return when {
            isServerUnreachableMessage(lower) ->
                getString(R.string.error_server_unreachable)
            "software caused connection abort" in lower || "connection abort" in lower ->
                getString(R.string.status_network_switch)
            "tls closed by peer" in lower || "control timeout" in lower ->
                getString(R.string.status_reconnecting)
            else -> raw
        }
    }

    @Synchronized
    private fun openTun(
        address: String,
        prefixLength: Int,
        dnsServers: Array<String>,
        routes: Array<String>,
        mtu: Int,
    ): Int {
        closeTun()
        // IPv4-only: do not add IPv6 family/routes — Android blocks IPv6 by default,
        // avoiding Happy Eyeballs blackholes that make sites feel slow.
        val builder = Builder()
            .setSession("OpenKerio VPN")
            .setMtu(if (mtu > 0) mtu else 1400)
            .addAddress(address, prefixLength)
        // allowFamily is API 29+; on Android 8–9 IPv4-only comes from addAddress/routes.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.allowFamily(android.system.OsConstants.AF_INET)
        }

        val dnsList = dnsServers.filter { it.isNotBlank() && it != "0.0.0.0" }
            .ifEmpty { listOf("1.1.1.1", "8.8.8.8") }
        dnsList.forEach { dns ->
            try {
                builder.addDnsServer(dns)
            } catch (t: Throwable) {
                Log.w(TAG, "Bad DNS $dns", t)
            }
        }

        val applied = routes.filter { it.isNotBlank() }
        if (applied.isEmpty()) {
            builder.addRoute("0.0.0.0", 0)
        } else {
            applied.forEach { cidr ->
                try {
                    val (network, prefix) = parseCidr(cidr)
                    builder.addRoute(network, prefix)
                } catch (t: Throwable) {
                    Log.w(TAG, "Bad route $cidr", t)
                }
            }
        }

        try {
            builder.addDisallowedApplication(packageName)
        } catch (t: Throwable) {
            Log.w(TAG, "addDisallowedApplication failed", t)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val pfd = builder.establish() ?: return -1
        tun = pfd
        return pfd.fd
    }

    @Synchronized
    private fun closeTun() {
        try {
            tun?.close()
        } catch (_: Throwable) {
        }
        tun = null
    }

    private fun protectTcp(socket: java.net.Socket): Boolean {
        if (runCatching { protect(socket) }.getOrDefault(false)) return true
        bindSocketToUnderlyingNetwork { it.bindSocket(socket) }
        return true
    }

    private fun protectUdp(socket: java.net.DatagramSocket): Boolean {
        if (runCatching { protect(socket) }.getOrDefault(false)) return true
        bindSocketToUnderlyingNetwork { it.bindSocket(socket) }
        return true
    }

    private fun protectFdOrNetwork(fd: Int): Boolean {
        if (fd < 0) return true
        runCatching { protect(fd) }
        return true
    }

    private fun bindSocketToUnderlyingNetwork(bind: (Network) -> Unit): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        val candidates = ArrayList<Network>()
        lastUnderlyingNetwork?.let { candidates.add(it) }
        synchronized(knownNetworks) {
            knownNetworks.forEach { if (it !in candidates) candidates.add(it) }
        }
        cm.activeNetwork?.let { if (it !in candidates) candidates.add(it) }
        for (network in candidates) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!isUnderlyingNetwork(caps)) continue
            try {
                bind(network)
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "bindSocket failed for $network", t)
            }
        }
        return false
    }

    private fun buildNotification(content: String): Notification {
        ensureChannel()
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnect = PendingIntent.getService(
            this,
            1,
            Intent(this, KerioVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = sessionConfig?.displayName?.trim().orEmpty()
            .ifBlank { getString(R.string.vpn_notification_title) }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.vpn_disconnect), disconnect)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(content: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, buildNotification(content))
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.vpn_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        nm.createNotificationChannel(channel)
    }

    private fun resetUptime() {
        connectedAtMs = 0L
    }

    private fun updateStatusNotification(status: String, detail: String) {
        val text = when (status) {
            STATUS_CONNECTED -> {
                val uptime = formatUptime(connectedAtMs)
                val rx = formatRate(lastRxBps)
                val tx = formatRate(lastTxBps)
                val statusLine = getString(R.string.vpn_notification_status, uptime, rx, tx)
                val server = sessionConfig?.server?.trim().orEmpty()
                if (server.isNotBlank()) "$server · $statusLine" else statusLine
            }
            STATUS_CONNECTING -> getString(R.string.vpn_notification_connecting) +
                if (detail.isNotBlank()) " · $detail" else ""
            STATUS_RECONNECTING -> getString(R.string.status_reconnecting) +
                if (detail.isNotBlank()) " · $detail" else ""
            STATUS_ERROR -> detail.ifBlank { getString(R.string.status_error) }
            else -> getString(R.string.status_disconnected)
        }
        updateNotification(text)
    }

    private fun formatUptime(connectedAt: Long): String {
        if (connectedAt <= 0L) return "—"
        val sec = ((System.currentTimeMillis() - connectedAt) / 1000L).coerceAtLeast(0)
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    private fun formatRate(bps: Long): String {
        val bits = bps * 8.0
        return when {
            bits >= 1_000_000 -> String.format("%.1fM", bits / 1_000_000.0)
            bits >= 1_000 -> String.format("%.0fK", bits / 1_000.0)
            else -> String.format("%.0f", bits)
        }
    }

    private fun publishStatus(status: String, detail: String) {
        lastStatus = status
        lastDetail = detail
        if (status == STATUS_RECONNECTING) {
            resetUptime()
        }
        if (status != STATUS_CONNECTED) {
            lastRxBps = 0
            lastTxBps = 0
            if (status == STATUS_DISCONNECTED || status == STATUS_ERROR) {
                lastWireVersion = 0
                lastCipher = ""
                resetUptime()
            }
        }
        sendBroadcast(
            Intent(ACTION_STATUS).setPackage(packageName)
                .putExtra(EXTRA_STATUS, status)
                .putExtra(EXTRA_DETAIL, detail)
                .putExtra(EXTRA_RX_BPS, lastRxBps)
                .putExtra(EXTRA_TX_BPS, lastTxBps)
                .putExtra(EXTRA_CONNECTED_AT, connectedAtMs)
                .putExtra(EXTRA_WIRE_VERSION, lastWireVersion)
                .putExtra(EXTRA_CIPHER, lastCipher),
        )
    }

    private fun publishTraffic(rxBps: Long, txBps: Long) {
        lastRxBps = rxBps
        lastTxBps = txBps
        sendBroadcast(
            Intent(ACTION_STATUS).setPackage(packageName)
                .putExtra(EXTRA_STATUS, lastStatus)
                .putExtra(EXTRA_DETAIL, lastDetail)
                .putExtra(EXTRA_RX_BPS, rxBps)
                .putExtra(EXTRA_TX_BPS, txBps)
                .putExtra(EXTRA_CONNECTED_AT, connectedAtMs)
                .putExtra(EXTRA_WIRE_VERSION, lastWireVersion)
                .putExtra(EXTRA_CIPHER, lastCipher),
        )
    }

    companion object {
        private const val TAG = "KerioVpnService"
        private const val CHANNEL_ID = "openkerio_vpn"
        private const val NOTIFICATION_ID = 42
        private const val NETWORK_HICCUP_MS = 3_500L

        fun isServerUnreachableMessage(text: String): Boolean {
            val lower = text.lowercase()
            return "failed to connect" in lower ||
                "connection timed out" in lower ||
                "connect timed out" in lower ||
                "etimedout" in lower ||
                "econnrefused" in lower ||
                "connection refused" in lower ||
                "no route to host" in lower ||
                "network is unreachable" in lower ||
                "enetunreach" in lower
        }

        const val ACTION_CONNECT = "com.openkerio.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.openkerio.vpn.DISCONNECT"
        const val ACTION_STATUS = "com.openkerio.vpn.STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_DETAIL = "detail"
        const val EXTRA_RX_BPS = "rx_bps"
        const val EXTRA_TX_BPS = "tx_bps"
        const val EXTRA_CONNECTED_AT = "connected_at"
        const val EXTRA_WIRE_VERSION = "wire_version"
        const val EXTRA_CIPHER = "cipher"

        const val STATUS_CONNECTING = "connecting"
        const val STATUS_CONNECTED = "connected"
        const val STATUS_DISCONNECTED = "disconnected"
        const val STATUS_RECONNECTING = "reconnecting"
        const val STATUS_ERROR = "error"

        @Volatile
        var lastStatus: String = STATUS_DISCONNECTED
            private set

        @Volatile
        var lastDetail: String = ""
            private set

        @Volatile
        var lastRxBps: Long = 0
            private set

        @Volatile
        var lastTxBps: Long = 0
            private set

        @Volatile
        var connectedAtMs: Long = 0
            private set

        @Volatile
        var lastWireVersion: Int = 0
            private set

        @Volatile
        var lastCipher: String = ""
            private set

        @Volatile
        private var pendingConfig: ConnectionConfig? = null

        @Synchronized
        private fun takePendingConfig(): ConnectionConfig? {
            val cfg = pendingConfig
            pendingConfig = null
            return cfg
        }

        @Synchronized
        private fun clearPendingConfig() {
            pendingConfig = null
        }

        fun parseServer(raw: String): Pair<String, Int> {
            val value = raw.trim()
            if (value.isEmpty()) return "" to 4090
            val idx = value.lastIndexOf(':')
            if (idx > 0 && !value.endsWith("]")) {
                val host = value.substring(0, idx).trim()
                val port = value.substring(idx + 1).toIntOrNull() ?: 4090
                return host to port
            }
            return value to 4090
        }

        fun parseCidr(cidr: String): Pair<String, Int> {
            val parts = cidr.split("/", limit = 2)
            val network = parts[0].trim()
            InetAddress.getByName(network)
            val prefix = if (parts.size > 1) parts[1].toInt() else 32
            return network to prefix
        }

        fun start(context: Context, config: ConnectionConfig) {
            synchronized(this) {
                pendingConfig = config
            }
            val intent = Intent(context, KerioVpnService::class.java).setAction(ACTION_CONNECT)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, KerioVpnService::class.java).setAction(ACTION_DISCONNECT)
            context.startService(intent)
        }
    }
}
