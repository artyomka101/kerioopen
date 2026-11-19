package com.openkerio.vpn.vpn

import com.openkerio.vpn.data.CipherPreference
import com.openkerio.vpn.protocol.KotlinVpnEngine

/**
 * VPN engine entry points (Kotlin OpenControl implementation).
 */
object NativeEngine {
    fun probeCertificate(host: String, port: Int): String =
        KotlinVpnEngine.instance.probeCertificate(host, port)

    fun start(
        host: String,
        port: Int,
        username: String,
        password: String,
        fingerprintSha256: String,
        cipherPreference: CipherPreference = CipherPreference.AUTO,
        callbacks: TunCallbacks,
    ) {
        KotlinVpnEngine.instance.start(
            host,
            port,
            username,
            password,
            fingerprintSha256,
            cipherPreference,
            callbacks,
        )
    }

    fun stop() {
        KotlinVpnEngine.instance.stop()
    }

    fun isRunning(): Boolean = KotlinVpnEngine.instance.isRunning()

    interface TunCallbacks {
        fun configureTun(
            address: String,
            prefixLength: Int,
            dnsServers: Array<String>,
            routes: Array<String>,
            mtu: Int,
        ): Int

        fun onStatus(status: String, detail: String)

        fun onSessionInfo(wireVersion: Int, cipher: String) {}

        fun onRoutesUpdated(routes: Array<String>)

        fun onTraffic(rxBps: Long, txBps: Long) {}

        fun protectSocket(fd: Int): Boolean

        fun protectTcpSocket(socket: java.net.Socket): Boolean = protectSocket(-1)

        fun protectUdpSocket(socket: java.net.DatagramSocket): Boolean = protectSocket(-1)
    }
}
