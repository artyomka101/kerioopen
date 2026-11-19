package com.openkerio.vpn.protocol

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.openkerio.vpn.data.CipherPreference
import com.openkerio.vpn.data.SessionLog
import com.openkerio.vpn.vpn.NativeEngine
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Pure-Kotlin OpenControl client (wire format from opencontrol-protocol).
 * AES-GCM for Kerio 9.2.8+; Blowfish legacy fallback for ≤ 9.2.7.
 */
class KotlinVpnEngine {
    private val stop = AtomicBoolean(false)
    private val running = AtomicBoolean(false)

    fun isRunning(): Boolean = running.get()

    fun stop() {
        stop.set(true)
    }

    fun start(
        host: String,
        port: Int,
        username: String,
        password: String,
        fingerprintSha256: String,
        cipherPreference: CipherPreference = CipherPreference.AUTO,
        callbacks: NativeEngine.TunCallbacks,
    ) {
        // Wait briefly if a previous session is still tearing down after stop().
        val waitUntil = System.currentTimeMillis() + 3_000L
        while (!running.compareAndSet(false, true)) {
            if (System.currentTimeMillis() >= waitUntil) {
                throw IllegalStateException("already running")
            }
            stop.set(true)
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("cancelled")
            }
        }
        stop.set(false)
        try {
            when (cipherPreference) {
                CipherPreference.AES -> {
                    SessionLog.i("cipher=AES forced")
                    runSession(host, port, username, password, fingerprintSha256, callbacks, CipherMode.AES)
                }
                CipherPreference.LEGACY -> {
                    SessionLog.i("cipher=Blowfish forced")
                    runSession(host, port, username, password, fingerprintSha256, callbacks, CipherMode.LEGACY)
                }
                CipherPreference.AUTO -> {
                    try {
                        SessionLog.i("cipher=AUTO trying AES")
                        runSession(host, port, username, password, fingerprintSha256, callbacks, CipherMode.AES)
                    } catch (e: CipherRejected) {
                        if (stop.get() || isCancellation(e)) throw e
                        Log.w(TAG, "AES rejected by peer, retrying legacy Blowfish", e)
                        SessionLog.w("AES rejected, fallback Blowfish: ${e.message}")
                        callbacks.onStatus("connecting", "Legacy (9.2.x)…")
                        runSession(host, port, username, password, fingerprintSha256, callbacks, CipherMode.LEGACY)
                    }
                }
            }
        } finally {
            running.set(false)
        }
    }

    private enum class CipherMode { AES, LEGACY }

    private class CipherRejected(message: String, cause: Throwable? = null) :
        IllegalStateException(message, cause)

    private fun runSession(
        host: String,
        port: Int,
        username: String,
        password: String,
        fingerprintSha256: String,
        callbacks: NativeEngine.TunCallbacks,
        cipherMode: CipherMode,
    ) {
        fun stage(detail: String) = callbacks.onStatus("connecting", detail)

        stage(host)
        val pin = fingerprintSha256.trim().lowercase()
        val trust = PinningTrustManager(pin)
        val ssl = openProtectedSslSocket(host, port, trust, callbacks)
        ssl.soTimeout = 15_000
        ssl.startHandshake()

        val input = ssl.inputStream
        val output = ssl.outputStream
        val decoder = ControlDecoder()
        val pending = ArrayDeque<ControlFrame>()
        fun readFrame(timeoutMs: Int = 15_000): ControlFrame {
            if (pending.isNotEmpty()) return pending.removeFirst()
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                if (stop.get()) throw IllegalStateException("cancelled")
                ssl.soTimeout = 500
                try {
                    val n = input.read(buf)
                    if (n < 0) throw IllegalStateException("TLS closed by peer")
                    if (n > 0) {
                        val frames = decoder.push(buf, 0, n)
                        if (frames.isNotEmpty()) {
                            for (i in 1 until frames.size) pending.addLast(frames[i])
                            return frames[0]
                        }
                    }
                } catch (_: java.net.SocketTimeoutException) {
                }
            }
            throw IllegalStateException("control read timeout")
        }

        val lastControlRx = AtomicLong(System.currentTimeMillis())
        fun tryReadFrames(): List<ControlFrame> {
            val out = ArrayList<ControlFrame>()
            while (pending.isNotEmpty()) out.add(pending.removeFirst())
            val buf = ByteArray(4096)
            ssl.soTimeout = 20
            try {
                val n = input.read(buf)
                if (n > 0) {
                    out.addAll(decoder.push(buf, 0, n))
                    lastControlRx.set(System.currentTimeMillis())
                } else if (n < 0) {
                    throw IllegalStateException("TLS closed by peer")
                }
            } catch (_: java.net.SocketTimeoutException) {
            }
            return out
        }

        fun send(bytes: ByteArray) {
            output.write(bytes)
            output.flush()
        }

        stage("Авторизация")
        send(ProtocolCodec.encodeVersion(4))
        val ver = MessageParse.parseVersion(readFrame())
        if (ver !in 1..4) throw IllegalStateException("unsupported version $ver")

        send(ProtocolCodec.encodeUsername(username))
        if (!MessageParse.isOk(readFrame())) throw IllegalStateException("username rejected")
        send(ProtocolCodec.encodePassword(password))
        val passReply = readFrame()
        when {
            MessageParse.isOk(passReply) -> Unit
            MessageParse.isError(passReply) -> throw IllegalStateException("authentication failed")
            else -> throw IllegalStateException("unexpected password reply ${passReply.opcode}")
        }

        val rng = SecureRandom()
        val aesKey = ByteArray(16).also { rng.nextBytes(it) }
        val legacyKey = ByteArray(16).also { rng.nextBytes(it) }
        val legacySalt = ByteArray(8).also { rng.nextBytes(it) }

        stage(if (cipherMode == CipherMode.AES) "AES" else "Blowfish")
        try {
            if (cipherMode == CipherMode.AES) {
                send(ProtocolCodec.encodeInitialConfig(wireVersion = ver, cep = 30, aesKey = aesKey))
            } else {
                send(
                    ProtocolCodec.encodeLegacyInitialConfig(
                        wireVersion = ver,
                        cep = 30,
                        key = legacyKey,
                        salt = legacySalt,
                    ),
                )
            }
        } catch (t: Throwable) {
            if (stop.get() || isCancellation(t)) throw t
            if (cipherMode == CipherMode.AES) throw CipherRejected("TLS write after AES config", t)
            throw t
        }

        val ipFrame = try {
            readFrame()
        } catch (t: Throwable) {
            if (stop.get() || isCancellation(t)) throw t
            if (cipherMode == CipherMode.AES) throw CipherRejected("TLS closed after AES config", t)
            throw t
        }
        if (MessageParse.isError(ipFrame)) {
            if (cipherMode == CipherMode.AES) throw CipherRejected("AES InitialConfig rejected")
            throw IllegalStateException("IP config rejected")
        }
        val ip = MessageParse.parseIpConfig(ipFrame, ver)
        lastControlRx.set(System.currentTimeMillis())

        stage("UDP")
        val udp = DatagramSocket()
        udp.soTimeout = 5
        runCatching {
            udp.receiveBufferSize = 512 * 1024
            udp.sendBufferSize = 512 * 1024
        }
        if (!callbacks.protectUdpSocket(udp)) {
            Log.w(TAG, "protect(UDP) soft-fail")
        }
        val serverAddr = InetAddress.getByName(host)
        udp.connect(InetSocketAddress(serverAddr, port))

        val connectPkt = ProtocolCodec.encodeUdpConnect(ip.secret)
        val bindDeadline = System.currentTimeMillis() + 12_000
        var bound = false
        var lastConnect = 0L
        while (!bound) {
            if (stop.get()) throw IllegalStateException("cancelled")
            val now = System.currentTimeMillis()
            if (now > bindDeadline) {
                throw IllegalStateException("UDP timeout")
            }
            if (now - lastConnect > 400) {
                udp.send(DatagramPacket(connectPkt, connectPkt.size))
                lastConnect = now
            }
            for (frame in tryReadFrames()) {
                if (MessageParse.isSecretReceived(frame)) bound = true
                if (MessageParse.isError(frame)) {
                    throw IllegalStateException("UDP rejected")
                }
            }
            if (!bound) Thread.sleep(15)
        }

        val dns = if (ip.dns.isNotEmpty()) ip.dns else listOf("1.1.1.1", "8.8.8.8")
        val routes = arrayOf(
            "${ip.address}/${ip.prefix}",
            "0.0.0.0/1",
            "128.0.0.0/1",
        )
        val tunFd = callbacks.configureTun(
            ip.address,
            ip.prefix,
            dns.toTypedArray(),
            routes,
            1400,
        )
        if (tunFd < 0) throw IllegalStateException("VpnService.establish failed")

        val cipherLabel = if (cipherMode == CipherMode.AES) "AES-GCM" else "Blowfish"
        callbacks.onSessionInfo(ver, cipherLabel)
        callbacks.onStatus("connected", ip.address)
        SessionLog.i("connected ip=${ip.address} wire=v$ver cipher=$cipherLabel cep=${ip.cep}")

        val tunPfd = ParcelFileDescriptor.fromFd(tunFd)
        runCatching {
            val flags = Os.fcntlInt(tunPfd.fileDescriptor, OsConstants.F_GETFL, 0)
            Os.fcntlInt(tunPfd.fileDescriptor, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
        }
        val tunIn = FileInputStream(tunPfd.fileDescriptor)
        val tunOut = FileOutputStream(tunPfd.fileDescriptor)

        var primarySlot = 0
        var aesPrimary: AesGcmSession? = null
        var aesPending: AesGcmSession? = null
        var legacyPrimary: LegacyBlowfishCipher? = null
        var legacyPending: LegacyBlowfishCipher? = null
        var pendingSlot: Int? = null

        if (cipherMode == CipherMode.AES) {
            aesPrimary = AesGcmSession(aesKey, slot = 0)
        } else {
            legacyPrimary = LegacyBlowfishCipher(legacyKey, legacySalt, ver, slot = 0)
        }

        var txSeq = 1
        var lastControlKa = System.currentTimeMillis()
        var lastUdpKa = System.currentTimeMillis()
        var lastStats = System.currentTimeMillis()
        var lastRekey = System.currentTimeMillis()
        var rekeyProposedAt = 0L
        val cepMs = ip.cep.coerceAtLeast(2) * 1000L
        val rekeyIntervalMs = (cepMs / 2).coerceAtLeast(5_000L)
        val rxWindow = AtomicLong(0)
        val txWindow = AtomicLong(0)
        val tunBuf = ByteArray(2048)
        val udpBuf = ByteArray(8192)

        fun openUdp(data: ByteArray): ByteArray? {
            if (cipherMode == CipherMode.AES) {
                val slot = data.getOrNull(3)?.toInt()?.and(0xff) ?: return null
                val cipher = when (slot) {
                    primarySlot -> aesPrimary
                    pendingSlot -> aesPending
                    else -> null
                } ?: return null
                return cipher.open(slot, data)
            }
            val slot = data.getOrNull(3)?.toInt()?.and(0xff) ?: return null
            val cipher = when (slot) {
                primarySlot -> legacyPrimary
                pendingSlot -> legacyPending
                else -> null
            } ?: return null
            return cipher.open(data)
        }

        fun sealUdp(packet: ByteArray): ByteArray {
            return if (cipherMode == CipherMode.AES) {
                val cipher = aesPrimary ?: error("no AES cipher")
                cipher.seal(primarySlot, txSeq++, packet)
            } else {
                val cipher = legacyPrimary ?: error("no legacy cipher")
                cipher.seal(packet)
            }
        }

        fun startRekey() {
            if (pendingSlot != null) return
            val nextSlot = 1 - primarySlot
            if (cipherMode == CipherMode.AES) {
                val key = ByteArray(16).also { rng.nextBytes(it) }
                aesPending = AesGcmSession(key, slot = nextSlot)
                pendingSlot = nextSlot
                send(ProtocolCodec.encodeAesKeyChange(nextSlot, key))
            } else {
                val key = ByteArray(16).also { rng.nextBytes(it) }
                val salt = ByteArray(8).also { rng.nextBytes(it) }
                legacyPending = LegacyBlowfishCipher(key, salt, ver, slot = nextSlot)
                pendingSlot = nextSlot
                send(ProtocolCodec.encodeLegacyKeyChange(nextSlot, key, salt))
            }
            val t = System.currentTimeMillis()
            lastRekey = t
            rekeyProposedAt = t
            Log.i(TAG, "rekey proposed slot=$nextSlot cep=${ip.cep}s")
        }

        fun promoteRekey() {
            val slot = pendingSlot ?: return
            if (cipherMode == CipherMode.AES) {
                aesPrimary = aesPending
                aesPending = null
            } else {
                legacyPrimary = legacyPending
                legacyPending = null
            }
            primarySlot = slot
            pendingSlot = null
            rekeyProposedAt = 0L
            txSeq = 1
            Log.i(TAG, "rekey promoted slot=$slot")
        }

        try {
            while (!stop.get()) {
                val now = System.currentTimeMillis()
                if (now - lastControlRx.get() > 120_000) {
                    throw IllegalStateException("TLS closed by peer (control timeout)")
                }
                if (now - lastControlKa > 60_000) {
                    send(ProtocolCodec.encodeKeepaliveRequest())
                    lastControlKa = now
                }
                if (now - lastUdpKa > 5_000) {
                    val ka = ProtocolCodec.encodeUdpKeepalive()
                    udp.send(DatagramPacket(ka, ka.size))
                    lastUdpKa = now
                }
                if (pendingSlot != null && rekeyProposedAt > 0L && now - rekeyProposedAt > 20_000) {
                    throw IllegalStateException("rekey acknowledgment timeout")
                }
                if (now - lastRekey >= rekeyIntervalMs) {
                    startRekey()
                }
                if (now - lastStats >= 1_000) {
                    val elapsed = (now - lastStats).coerceAtLeast(1)
                    val rx = rxWindow.getAndSet(0)
                    val tx = txWindow.getAndSet(0)
                    callbacks.onTraffic(rx * 1000 / elapsed, tx * 1000 / elapsed)
                    lastStats = now
                }

                for (frame in tryReadFrames()) {
                    when {
                        MessageParse.isError(frame) -> Log.w(TAG, "control ERR")
                        frame.opcode == 11 -> {
                            // keepalive response — refreshes lastControlRx via tryReadFrames
                        }
                        frame.opcode == 9 -> promoteRekey()
                        frame.opcode == 19 -> {
                            val (flags, list) = MessageParse.parseRoutes(frame)
                            val cidrs = ArrayList<String>()
                            if (flags and 9 == 9) {
                                cidrs.add("0.0.0.0/1")
                                cidrs.add("128.0.0.0/1")
                            }
                            list.forEach { cidrs.add("${it.network}/${it.prefix}") }
                            if (cidrs.isNotEmpty()) callbacks.onRoutesUpdated(cidrs.toTypedArray())
                        }
                        // Server-initiated KEY_CHANGE is not part of the client role model;
                        // acknowledge defensively if a peer still emits opcode 8.
                        frame.opcode == 8 -> send(ProtocolCodec.encodeKeyChanged())
                    }
                }

                var udpIdle = true
                while (true) {
                    try {
                        val packet = DatagramPacket(udpBuf, udpBuf.size)
                        udp.receive(packet)
                        udpIdle = false
                        val len = packet.length
                        val data = if (len == packet.data.size) packet.data else packet.data.copyOf(len)
                        val type = data.getOrNull(2)?.toInt()?.and(0xff) ?: -1
                        if (data.size >= 5 && data[0] == ProtocolCodec.MAGIC && type == 0) {
                            val ipPacket = openUdp(data)
                            if (ipPacket != null) {
                                tunOut.write(ipPacket)
                                rxWindow.addAndGet(ipPacket.size.toLong())
                            }
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        break
                    }
                }
                if (!udpIdle) tunOut.flush()

                var tunIdle = true
                repeat(64) {
                    try {
                        val n = tunIn.read(tunBuf)
                        if (n > 0) {
                            tunIdle = false
                            val version = (tunBuf[0].toInt() shr 4) and 0x0f
                            if (version != 4) return@repeat
                            val sealed = sealUdp(tunBuf.copyOf(n))
                            udp.send(DatagramPacket(sealed, sealed.size))
                            txWindow.addAndGet(n.toLong())
                        } else if (n < 0) {
                            return@runSession
                        } else {
                            return@repeat
                        }
                    } catch (_: java.io.IOException) {
                        return@repeat
                    }
                }

                if (tunIdle && udpIdle) {
                    try {
                        Thread.sleep(1)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        } finally {
            runCatching { ssl.close() }
            runCatching { udp.close() }
            runCatching { tunIn.close() }
            runCatching { tunOut.close() }
            runCatching { tunPfd.close() }
        }
    }

    private fun sslFactory(trust: X509TrustManager): SSLSocketFactory {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(trust), SecureRandom())
        return ctx.socketFactory
    }

    private fun openProtectedSslSocket(
        host: String,
        port: Int,
        trust: X509TrustManager,
        callbacks: NativeEngine.TunCallbacks,
    ): SSLSocket {
        val plain = Socket()
        plain.tcpNoDelay = true
        if (!callbacks.protectTcpSocket(plain)) {
            Log.w(TAG, "protect(TCP) soft-fail")
        }
        plain.connect(InetSocketAddress(host, port), 20_000)
        val ssl = sslFactory(trust).createSocket(plain, host, port, true) as SSLSocket
        // Prefer 1.2 for old Kerio; keep 1.3 for newer appliances.
        val supported = ssl.supportedProtocols.toSet()
        val wanted = listOf("TLSv1.2", "TLSv1.3").filter { it in supported }
        if (wanted.isNotEmpty()) {
            ssl.enabledProtocols = wanted.toTypedArray()
        }
        return ssl
    }

    fun probeCertificate(host: String, port: Int): String {
        val capture = CapturingTrustManager()
        val factory = sslFactory(capture)
        val socket = factory.createSocket() as SSLSocket
        try {
            socket.soTimeout = 20_000
            val supported = socket.supportedProtocols.toSet()
            val wanted = listOf("TLSv1.2", "TLSv1.3").filter { it in supported }
            if (wanted.isNotEmpty()) {
                socket.enabledProtocols = wanted.toTypedArray()
            }
            socket.connect(InetSocketAddress(host, port), 20_000)
            socket.startHandshake()
            return capture.fingerprint
                ?: throw IllegalStateException("certificate fingerprint missing")
        } finally {
            runCatching { socket.close() }
        }
    }

    companion object {
        private const val TAG = "KotlinVpnEngine"
        val instance = KotlinVpnEngine()

        private fun isCancellation(t: Throwable?): Boolean {
            var cur: Throwable? = t
            while (cur != null) {
                if (cur.message.equals("cancelled", ignoreCase = true)) return true
                cur = cur.cause
            }
            return false
        }
    }
}

private class PinningTrustManager(private val expectedSha256Hex: String) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val cert = chain?.firstOrNull() ?: throw IllegalStateException("empty chain")
        val actual = sha256Hex(cert.encoded)
        if (!actual.equals(expectedSha256Hex, ignoreCase = true)) {
            throw IllegalStateException("certificate pin mismatch: got $actual")
        }
    }
}

private class CapturingTrustManager : X509TrustManager {
    @Volatile
    var fingerprint: String? = null
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val cert = chain?.firstOrNull() ?: throw IllegalStateException("empty chain")
        fingerprint = sha256Hex(cert.encoded)
    }
}

private fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}
