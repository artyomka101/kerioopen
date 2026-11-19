package com.openkerio.vpn.protocol

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Kerio Control legacy UDP cipher (kind 0): Blowfish-ECB counter keystream.
 * Used by Kerio ≤ 9.2.7; AES-GCM (kind 1) starts at 9.2.8+.
 * See opencontrol-protocol LEGACY.md.
 */
class LegacyBlowfishCipher(
    key: ByteArray,
    private val salt: ByteArray,
    private val wireVersion: Int,
    private val slot: Int = 0,
) {
    init {
        require(key.size == 16)
        require(salt.size == 8)
        require(slot in 0..1)
        require(wireVersion in 1..4)
    }

    private val blowfish = Cipher.getInstance("Blowfish/ECB/NoPadding").also {
        it.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "Blowfish"))
    }
    private val random = SecureRandom()
    private var counter: Long = random.nextLong()
    private var txSeq = 0

    fun seal(ipPacket: ByteArray): ByteArray {
        val trailer = if (wireVersion == 1) 0 else 6
        val innerLen = 2 + ipPacket.size + trailer // EtherType + packet [+ seq + chk]
        val totalLen = 2 + innerLen // + length field
        val padded = (totalLen + 7) / 8 * 8
        val blocks = padded / 8
        val nextCounter = Math.addExact(counter, blocks.toLong())

        val plain = ByteArray(padded)
        plain[0] = ((innerLen shr 8) and 0xff).toByte()
        plain[1] = (innerLen and 0xff).toByte()
        plain[2] = ((ProtocolCodec.ETHER_IPV4 shr 8) and 0xff).toByte()
        plain[3] = (ProtocolCodec.ETHER_IPV4 and 0xff).toByte()
        System.arraycopy(ipPacket, 0, plain, 4, ipPacket.size)
        if (trailer != 0) {
            val seq = txSeq + 1
            val seqOff = 4 + ipPacket.size
            plain[seqOff] = ((seq ushr 24) and 0xff).toByte()
            plain[seqOff + 1] = ((seq ushr 16) and 0xff).toByte()
            plain[seqOff + 2] = ((seq ushr 8) and 0xff).toByte()
            plain[seqOff + 3] = (seq and 0xff).toByte()
            val chk = onesComplementChecksum(plain, 2, seqOff + 4 - 2)
            plain[seqOff + 4] = ((chk shr 8) and 0xff).toByte()
            plain[seqOff + 5] = (chk and 0xff).toByte()
            txSeq = seq
        }
        for (i in totalLen until padded) plain[i] = 0xff.toByte()

        cryptInPlace(plain, counter)
        val out = ByteArray(13 + padded)
        out[0] = ProtocolCodec.MAGIC
        out[1] = 0
        out[2] = 0
        out[3] = slot.toByte()
        out[4] = 8
        val counterBytes = longToLe(counter)
        for (i in 0 until 8) {
            out[5 + i] = (counterBytes[i].toInt() xor salt[i].toInt()).toByte()
        }
        System.arraycopy(plain, 0, out, 13, padded)
        counter = nextCounter
        return out
    }

    fun open(packet: ByteArray): ByteArray? {
        if (packet.size < 21 || (packet.size - 13) % 8 != 0) return null
        if (packet[0] != ProtocolCodec.MAGIC || packet[2].toInt() != 0) return null
        if ((packet[3].toInt() and 0xff) != slot || packet[4].toInt() != 8) return null

        val counterBytes = ByteArray(8)
        for (i in 0 until 8) {
            counterBytes[i] = (packet[5 + i].toInt() xor salt[i].toInt()).toByte()
        }
        val first = leToLong(counterBytes)
        val plain = packet.copyOfRange(13, packet.size)
        cryptInPlace(plain, first)

        val declared = ((plain[0].toInt() and 0xff) shl 8) or (plain[1].toInt() and 0xff)
        val trailer = if (wireVersion == 1) 0 else 6
        if (declared < 2 + trailer || declared + 2 > plain.size) return null
        val padStart = declared + 2
        if (plain.size - padStart > 7) return null
        for (i in padStart until plain.size) {
            if (plain[i] != 0xff.toByte()) return null
        }
        val end = padStart
        if (trailer != 0) {
            val chkOff = end - 2
            val expected = onesComplementChecksum(plain, 2, chkOff - 2)
            val got = ((plain[chkOff].toInt() and 0xff) shl 8) or (plain[chkOff + 1].toInt() and 0xff)
            if (expected != got) return null
        }
        // ether(2) + packet … [seq+chk]
        return plain.copyOfRange(4, end - trailer)
    }

    private fun cryptInPlace(buffer: ByteArray, first: Long) {
        require(buffer.size % 8 == 0)
        Math.addExact(first, (buffer.size / 8).toLong())
        for (i in buffer.indices step 8) {
            val block = longToLe(first + (i / 8).toLong())
            val keystream = synchronized(blowfish) { blowfish.doFinal(block) }
            for (j in 0 until 8) {
                buffer[i + j] = (buffer[i + j].toInt() xor keystream[j].toInt()).toByte()
            }
        }
    }

    companion object {
        private fun longToLe(value: Long): ByteArray {
            val out = ByteArray(8)
            var v = value
            for (i in 0 until 8) {
                out[i] = (v and 0xff).toByte()
                v = v ushr 8
            }
            return out
        }

        private fun leToLong(bytes: ByteArray): Long {
            var v = 0L
            for (i in 7 downTo 0) {
                v = (v shl 8) or (bytes[i].toInt() and 0xff).toLong()
            }
            return v
        }

        /** One's-complement checksum over [offset, offset+length). */
        fun onesComplementChecksum(data: ByteArray, offset: Int, length: Int): Int {
            var sum = 0
            var i = 0
            while (i < length) {
                val b0 = data[offset + i].toInt() and 0xff
                val b1 = if (i + 1 < length) data[offset + i + 1].toInt() and 0xff else 0
                sum += (b0 shl 8) or b1
                i += 2
            }
            while (sum ushr 16 != 0) {
                sum = (sum and 0xffff) + (sum ushr 16)
            }
            return sum.inv() and 0xffff
        }
    }
}
