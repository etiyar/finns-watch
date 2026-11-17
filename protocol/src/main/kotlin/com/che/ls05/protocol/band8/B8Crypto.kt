package com.che.ls05.protocol.band8

import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HMAC-SHA256 ve HKDF-SHA256 (RFC 5869). */
object B8Hash {
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // Boş anahtar SecretKeySpec'te geçersiz. RFC 5869'a göre boş tuz, 32 sıfır bayttır.
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * 32)
        val prk = hmacSha256(salt, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var i = 1
        while (pos < length) {
            t = hmacSha256(prk, t + info + byteArrayOf(i.toByte()))
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            i++
        }
        return out
    }
}

/**
 * AES-CCM (RFC 3610, NIST SP 800-38C). JDK ve Android'de hazır CCM olmadığı için
 * AES/ECB üstünde CBC-MAC ve CTR ile yazıldı.
 *
 * @param tagLength 4, 6, 8, 10, 12, 14 ya da 16. Band 8: 4.
 */
class AesCcm(key: ByteArray, private val tagLength: Int = 4) {
    private val aes: Cipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
    }

    init {
        require(key.size == 16 || key.size == 24 || key.size == 32) { "AES anahtarı boyu geçersiz" }
        require(tagLength in 4..16 && tagLength % 2 == 0) { "etiket boyu geçersiz" }
    }

    private fun block(b: ByteArray): ByteArray = aes.doFinal(b)

    private fun checkNonce(nonce: ByteArray) =
        require(nonce.size in 7..13) { "nonce 7-13 bayt olmalı" }

    private fun ctrBlock(nonce: ByteArray, counter: Int): ByteArray {
        val q = 15 - nonce.size
        val a = ByteArray(16)
        a[0] = (q - 1).toByte()
        System.arraycopy(nonce, 0, a, 1, nonce.size)
        var c = counter
        for (k in 15 downTo 16 - q) { a[k] = (c and 0xFF).toByte(); c = c ushr 8 }
        return a
    }

    private fun mac(nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray {
        val q = 15 - nonce.size
        require(q >= 4 || plain.size.toLong() < (1L shl (8 * q))) { "mesaj bu nonce için çok uzun" }
        val b0 = ByteArray(16)
        b0[0] = ((if (aad.isNotEmpty()) 0x40 else 0) or (((tagLength - 2) / 2) shl 3) or (q - 1)).toByte()
        System.arraycopy(nonce, 0, b0, 1, nonce.size)
        var len = plain.size
        for (k in 15 downTo 16 - q) { b0[k] = (len and 0xFF).toByte(); len = len ushr 8 }
        var x = block(b0)
        fun absorb(data: ByteArray) {
            var p = 0
            while (p < data.size) {
                val blk = ByteArray(16)
                val n = minOf(16, data.size - p)
                System.arraycopy(data, p, blk, 0, n)
                for (k in 0 until 16) blk[k] = (blk[k].toInt() xor x[k].toInt()).toByte()
                x = block(blk)
                p += 16
            }
        }
        if (aad.isNotEmpty()) {
            require(aad.size < 0xFF00) { "ek veri çok uzun" }
            absorb(byteArrayOf((aad.size ushr 8).toByte(), aad.size.toByte()) + aad)
        }
        absorb(plain)
        return x.copyOf(tagLength)
    }

    private fun ctr(nonce: ByteArray, data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        var p = 0
        var i = 1
        while (p < data.size) {
            val s = block(ctrBlock(nonce, i))
            val n = minOf(16, data.size - p)
            for (k in 0 until n) out[p + k] = (data[p + k].toInt() xor s[k].toInt()).toByte()
            p += 16
            i++
        }
        return out
    }

    /** Şifreli metin + etiket döner. */
    fun encrypt(nonce: ByteArray, plain: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        checkNonce(nonce)
        val t = mac(nonce, aad, plain)
        val s0 = block(ctrBlock(nonce, 0))
        val u = ByteArray(tagLength) { (t[it].toInt() xor s0[it].toInt()).toByte() }
        return ctr(nonce, plain) + u
    }

    /** Etiket tutmazsa null döner. */
    fun decrypt(nonce: ByteArray, sealed: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray? {
        checkNonce(nonce)
        if (sealed.size < tagLength) return null
        val c = sealed.copyOfRange(0, sealed.size - tagLength)
        val u = sealed.copyOfRange(sealed.size - tagLength, sealed.size)
        val plain = ctr(nonce, c)
        val t = mac(nonce, aad, plain)
        val s0 = block(ctrBlock(nonce, 0))
        var diff = 0
        for (k in 0 until tagLength) diff = diff or ((t[k].toInt() xor s0[k].toInt()) xor u[k].toInt())
        return if (diff == 0) plain else null
    }
}

/**
 * Bir auth oturumunun anahtarları (belge 3.2, 4.1, 4.2).
 *
 * Telefon nonce'u ve band nonce'u ile auth key'den HKDF ile 64 bayt türetilir:
 * band anahtarı, telefon anahtarı, band nonce önü, telefon nonce önü.
 */
class B8Session(authKey: ByteArray, val phoneNonce: ByteArray, val bandNonce: ByteArray) {
    private val bandKey: ByteArray
    private val phoneKey: ByteArray
    private val bandPrefix: ByteArray
    private val phonePrefix: ByteArray
    private val bandCcm: AesCcm
    private val phoneCcm: AesCcm

    init {
        require(authKey.size == 16) { "auth key 16 bayt olmalı" }
        require(phoneNonce.size == 16 && bandNonce.size == 16) { "nonce 16 bayt olmalı" }
        val d = B8Hash.hkdf(authKey, phoneNonce + bandNonce, INFO, 64)
        bandKey = d.copyOfRange(0, 16)
        phoneKey = d.copyOfRange(16, 32)
        bandPrefix = d.copyOfRange(32, 36)
        phonePrefix = d.copyOfRange(36, 40)
        bandCcm = AesCcm(bandKey, 4)
        phoneCcm = AesCcm(phoneKey, 4)
    }

    /** Bandın 1/26 yanıtındaki HMAC bununla aynı olmalı. */
    fun expectedBandHmac(): ByteArray = B8Hash.hmacSha256(bandKey, bandNonce + phoneNonce)

    /** Telefonun 1/27'de göndereceği HMAC. */
    fun phoneHmac(): ByteArray = B8Hash.hmacSha256(phoneKey, phoneNonce + bandNonce)

    fun bandHmacMatches(received: ByteArray): Boolean =
        java.security.MessageDigest.isEqual(expectedBandHmac(), received)

    private fun phoneNonceFor(counter: Long): ByteArray =
        phonePrefix + ByteArray(4) + le32(counter)

    private fun bandNonceFor(counter: Long): ByteArray =
        bandPrefix + ByteArray(4) + le32(counter)

    /** Telefondan banda: şifreli metin + 4 bayt etiket. */
    fun encryptToBand(plain: ByteArray, counter: Long): ByteArray = phoneCcm.encrypt(phoneNonceFor(counter), plain)

    /** Banddan gelen mesaj (sayaç her zaman 0). Etiket tutmazsa null. */
    fun decryptFromBand(sealed: ByteArray): ByteArray? = bandCcm.decrypt(bandNonceFor(0), sealed)

    /** Test ve sahte band için: bandın yapacağı şifreleme. */
    fun encryptAsBand(plain: ByteArray): ByteArray = bandCcm.encrypt(bandNonceFor(0), plain)

    /** Test ve sahte band için: telefondan geleni çözme. */
    fun decryptAsBand(sealed: ByteArray, counter: Long): ByteArray? = phoneCcm.decrypt(phoneNonceFor(counter), sealed)

    companion object {
        val INFO = "miwear-auth".toByteArray(Charsets.US_ASCII)

        private fun le32(v: Long): ByteArray =
            ByteArray(4) { ((v ushr (8 * it)) and 0xFF).toByte() }

        /** "0x" önekli ya da öneksiz 32 onaltılık karakter. Geçersizse null. */
        fun parseAuthKey(text: String): ByteArray? {
            var t = text.trim().lowercase()
            if (t.startsWith("0x")) t = t.substring(2)
            if (t.length != 32 || t.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
            return ByteArray(16) { t.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        }
    }
}
