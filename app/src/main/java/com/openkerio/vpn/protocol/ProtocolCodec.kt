package com.openkerio.vpn.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** OpenControl / Kerio Control VPN wire framing (PROTOCOL.md). */
object ProtocolCodec {
    const val MAGIC: Byte = 0x4B
    const val ETHER_IPV4 = 0x0800

    fun encodeFrame(opcode: Int, payload: ByteArray): ByteArray {
        val total = 4 + payload.size
        require(total in 5..63999)
        val out = ByteArray(total)
        out[0] = MAGIC
        out[1] = (total and 0xff).toByte()
        out[2] = ((total shr 8) and 0xff).toByte()
        out[3] = opcode.toByte()
        System.arraycopy(payload, 0, out, 4, payload.size)
        return out
    }

    fun encodeCredential(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(!bytes.contains(0))
        return bytes + 0
    }

    fun encodeVersion(version: Int): ByteArray = encodeFrame(22, byteArrayOf(version.toByte()))

    fun encodeUsername(username: String): ByteArray =
        encodeFrame(1, encodeCredential(username))

    fun encodePassword(password: String): ByteArray =
        encodeFrame(2, encodeCredential(password))

    fun encodeKeepaliveRequest(): ByteArray = encodeFrame(10, byteArrayOf(0))

    fun encodeKeyChanged(): ByteArray = encodeFrame(9, byteArrayOf(0))

    fun encodeAesKeyChange(slot: Int, aesKey: ByteArray): ByteArray {
        require(aesKey.size == 16)
        require(slot in 0..1)
        val payload = ByteArray(21)
        payload[0] = 1
        payload[1] = slot.toByte()
        payload[2] = 16
        payload[3] = 12
        payload[4] = 16
        System.arraycopy(aesKey, 0, payload, 5, 16)
        return encodeFrame(8, payload)
    }

    fun encodeLegacyKeyChange(slot: Int, key: ByteArray, salt: ByteArray): ByteArray {
        require(key.size == 16 && salt.size == 8)
        require(slot in 0..1)
        val payload = ByteArray(28)
        payload[0] = 0
        payload[1] = slot.toByte()
        payload[2] = 16
        payload[3] = 8
        System.arraycopy(key, 0, payload, 4, 16)
        System.arraycopy(salt, 0, payload, 20, 8)
        return encodeFrame(8, payload)
    }

    fun encodeInitialConfig(
        wireVersion: Int,
        cep: Int,
        aesKey: ByteArray,
        description: String = "OpenKerio Android",
    ): ByteArray {
        require(aesKey.size == 16)
        require(wireVersion in 1..4)
        val payload = if (wireVersion >= 3) {
            val bytes = ByteArray(160)
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            buf.putShort(cep.toShort())
            buf.put(1) // kind AES
            buf.put(0) // slot
            putClientMetadata(buf, description)
            buf.put(16)
            buf.put(12)
            buf.put(16)
            buf.put(aesKey)
            bytes
        } else {
            val bytes = ByteArray(23)
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            buf.putShort(cep.toShort())
            buf.put(1)
            buf.put(0)
            buf.put(16)
            buf.put(12)
            buf.put(16)
            buf.put(aesKey)
            bytes
        }
        return encodeFrame(5, payload)
    }

    /** Legacy Blowfish InitialConfig (kind 0). Kerio ≤ 9.2.7. */
    fun encodeLegacyInitialConfig(
        wireVersion: Int,
        cep: Int,
        key: ByteArray,
        salt: ByteArray,
        description: String = "OpenKerio Android",
    ): ByteArray {
        require(key.size == 16 && salt.size == 8)
        require(wireVersion in 1..4)
        val payload = if (wireVersion >= 3) {
            val bytes = ByteArray(167)
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            buf.putShort(cep.toShort())
            buf.put(0) // kind legacy
            buf.put(0) // slot
            putClientMetadata(buf, description)
            buf.put(16)
            buf.put(8)
            buf.put(key)
            buf.put(salt)
            bytes
        } else {
            val bytes = ByteArray(30)
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            buf.putShort(cep.toShort())
            buf.put(0)
            buf.put(0)
            buf.put(16)
            buf.put(8)
            buf.put(key)
            buf.put(salt)
            bytes
        }
        return encodeFrame(5, payload)
    }

    private fun putClientMetadata(buf: ByteBuffer, description: String) {
        val desc = description.toByteArray(Charsets.UTF_8)
        require(desc.size <= 127 && !desc.contains(0))
        buf.put(byteArrayOf(0, 9, 0))
        buf.putShort(1)
        buf.putInt(3)
        val descField = ByteArray(128)
        System.arraycopy(desc, 0, descField, 0, desc.size)
        buf.put(descField)
    }

    fun encodeUdpConnect(secret: ByteArray): ByteArray {
        require(secret.size == 128)
        val out = ByteArray(133)
        out[0] = MAGIC
        out[1] = 0
        out[2] = 1
        out[3] = 0
        out[4] = 0
        System.arraycopy(secret, 0, out, 5, 128)
        return out
    }

    fun encodeUdpKeepalive(): ByteArray = byteArrayOf(MAGIC, 0, 2, 0, 0)
}

/** Reuses Cipher instances across packets (init still required per nonce). */
class AesGcmSession(key: ByteArray, val slot: Int = 0) {
    private val keySpec = SecretKeySpec(key, "AES")
    private val enc = Cipher.getInstance("AES/GCM/NoPadding")
    private val dec = Cipher.getInstance("AES/GCM/NoPadding")
    private val random = SecureRandom()

    fun seal(sequence: Int, ipPacket: ByteArray, rnd: SecureRandom = random): ByteArray {
        return seal(slot, sequence, ipPacket, rnd)
    }

    fun seal(slot: Int, sequence: Int, ipPacket: ByteArray, rnd: SecureRandom = random): ByteArray {
        val nonce = ByteArray(12)
        rnd.nextBytes(nonce)
        val plaintext = ByteArray(2 + ipPacket.size + 4)
        plaintext[0] = ((ProtocolCodec.ETHER_IPV4 shr 8) and 0xff).toByte()
        plaintext[1] = (ProtocolCodec.ETHER_IPV4 and 0xff).toByte()
        System.arraycopy(ipPacket, 0, plaintext, 2, ipPacket.size)
        val seqOff = 2 + ipPacket.size
        plaintext[seqOff] = ((sequence shr 24) and 0xff).toByte()
        plaintext[seqOff + 1] = ((sequence shr 16) and 0xff).toByte()
        plaintext[seqOff + 2] = ((sequence shr 8) and 0xff).toByte()
        plaintext[seqOff + 3] = (sequence and 0xff).toByte()

        enc.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, nonce))
        val encrypted = enc.doFinal(plaintext)
        val ct = encrypted.copyOfRange(0, encrypted.size - 16)
        val tag = encrypted.copyOfRange(encrypted.size - 16, encrypted.size)

        val out = ByteArray(32 + ct.size)
        out[0] = ProtocolCodec.MAGIC
        out[1] = 0
        out[2] = 0
        out[3] = slot.toByte()
        System.arraycopy(nonce, 0, out, 4, 12)
        System.arraycopy(tag, 0, out, 16, 16)
        System.arraycopy(ct, 0, out, 32, ct.size)
        return out
    }

    fun open(slot: Int, packet: ByteArray): ByteArray? {
        if (packet.size < 34 || packet[0] != ProtocolCodec.MAGIC || (packet[2].toInt() and 0xff) != 0) {
            return null
        }
        if ((packet[3].toInt() and 0xff) != slot) return null
        val nonce = packet.copyOfRange(4, 16)
        val tag = packet.copyOfRange(16, 32)
        val ct = packet.copyOfRange(32, packet.size)
        return try {
            dec.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, nonce))
            val plain = dec.doFinal(ct + tag)
            if (plain.size < 6) return null
            plain.copyOfRange(2, plain.size - 4)
        } catch (_: Exception) {
            null
        }
    }
}

class ControlDecoder {
    private var buf = ByteArray(0)

    fun push(data: ByteArray, offset: Int = 0, length: Int = data.size): List<ControlFrame> {
        if (length <= 0) return emptyList()
        val merged = ByteArray(buf.size + length)
        System.arraycopy(buf, 0, merged, 0, buf.size)
        System.arraycopy(data, offset, merged, buf.size, length)
        buf = merged

        val frames = ArrayList<ControlFrame>()
        var pos = 0
        while (true) {
            if (buf.size - pos < 4) break
            if (buf[pos] != ProtocolCodec.MAGIC) {
                buf = ByteArray(0)
                throw IllegalStateException("bad control magic")
            }
            val total = (buf[pos + 1].toInt() and 0xff) or ((buf[pos + 2].toInt() and 0xff) shl 8)
            if (total !in 5..63999) {
                buf = ByteArray(0)
                throw IllegalStateException("bad frame length $total")
            }
            if (buf.size - pos < total) break
            val opcode = buf[pos + 3].toInt() and 0xff
            val payload = buf.copyOfRange(pos + 4, pos + total)
            frames.add(ControlFrame(opcode, payload))
            pos += total
        }
        buf = if (pos == 0) buf else if (pos >= buf.size) ByteArray(0) else buf.copyOfRange(pos, buf.size)
        return frames
    }
}

data class ControlFrame(val opcode: Int, val payload: ByteArray)

data class IpConfig(
    val address: String,
    val prefix: Int,
    val dns: List<String>,
    val secret: ByteArray,
    val cep: Int,
)

data class RouteEntry(val network: String, val prefix: Int)

object MessageParse {
    fun ipv4(bytes: ByteArray, off: Int): String =
        "${bytes[off].toInt() and 0xff}.${bytes[off + 1].toInt() and 0xff}." +
            "${bytes[off + 2].toInt() and 0xff}.${bytes[off + 3].toInt() and 0xff}"

    fun prefixFromMask(bytes: ByteArray, off: Int): Int {
        var mask = 0
        for (i in 0 until 4) {
            mask = (mask shl 8) or (bytes[off + i].toInt() and 0xff)
        }
        return Integer.bitCount(mask)
    }

    fun parseVersion(frame: ControlFrame): Int {
        require(frame.opcode == 22 && frame.payload.size == 1)
        return frame.payload[0].toInt() and 0xff
    }

    fun isOk(frame: ControlFrame): Boolean = frame.opcode == 3
    fun isError(frame: ControlFrame): Boolean = frame.opcode == 4
    fun isSecretReceived(frame: ControlFrame): Boolean = frame.opcode == 7

    fun parseIpConfig(frame: ControlFrame, wireVersion: Int): IpConfig {
        require(frame.opcode == 6)
        val p = frame.payload
        val (len, secretOff) = when (wireVersion) {
            1, 2 -> 146 to 18
            3 -> 410 to 282
            else -> 411 to 283
        }
        require(p.size == len) { "IP_CONFIG length ${p.size} != $len" }
        val address = ipv4(p, 0)
        val prefix = prefixFromMask(p, 4)
        val cep = ((p[8].toInt() and 0xff) shl 8) or (p[9].toInt() and 0xff)
        val dns = listOf(ipv4(p, 10), ipv4(p, 14)).filter { it != "0.0.0.0" }.map {
            if (it == "127.0.0.1") {
                // gateway sentinel → network+1
                val parts = address.split('.').map { n -> n.toInt() }
                val ip = (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
                val maskBits = prefix
                val mask = if (maskBits == 0) 0 else -1 shl (32 - maskBits)
                val gw = (ip and mask) or 1
                "${(gw ushr 24) and 0xff}.${(gw ushr 16) and 0xff}.${(gw ushr 8) and 0xff}.${gw and 0xff}"
            } else it
        }
        val secret = p.copyOfRange(secretOff, secretOff + 128)
        return IpConfig(address, prefix, dns, secret, cep)
    }

    fun parseRoutes(frame: ControlFrame): Pair<Int, List<RouteEntry>> {
        require(frame.opcode == 19)
        val p = frame.payload
        require(p.size >= 2)
        val flags = p[0].toInt() and 0xff
        val count = p[1].toInt() and 0xff
        require(p.size == 2 + 8 * count)
        val routes = ArrayList<RouteEntry>(count)
        var off = 2
        repeat(count) {
            routes.add(RouteEntry(ipv4(p, off), prefixFromMask(p, off + 4)))
            off += 8
        }
        if (flags and 9 == 9) {
            routes.add(RouteEntry("0.0.0.0", 1))
            routes.add(RouteEntry("128.0.0.0", 1))
        }
        return flags to routes
    }
}
