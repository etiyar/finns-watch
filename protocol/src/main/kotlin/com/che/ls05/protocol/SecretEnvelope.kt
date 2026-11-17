package com.che.ls05.protocol

import java.util.Base64

/**
 * Şifreli gizli veri zarfı ve anahtar girişi denetimi. Şifreleme Android Keystore'da yapılır (SecretStore);
 * burada yalnızca biçim var. Gizli değer hiçbir zaman loga, istisna mesajına ya da toString'e girmez.
 */
object SecretEnvelope {

    private const val PREFIX = "1"

    /** Biçim: 1:<iv base64>:<şifreli metin base64>. */
    fun encode(iv: ByteArray, cipherText: ByteArray): String {
        val e = Base64.getEncoder()
        return PREFIX + ":" + e.encodeToString(iv) + ":" + e.encodeToString(cipherText)
    }

    /** Biçim bozuksa null. Dönen çift: iv, şifreli metin. */
    fun decode(text: String?): Pair<ByteArray, ByteArray>? {
        val p = text?.split(':') ?: return null
        if (p.size != 3 || p[0] != PREFIX) return null
        return try {
            val d = Base64.getDecoder()
            val iv = d.decode(p[1])
            val ct = d.decode(p[2])
            if (iv.isEmpty() || ct.isEmpty()) null else iv to ct
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * Band 8 kimlik anahtarı girişi: 16 bayt, 32 onaltılık karakter. Boşluk, iki nokta ve baştaki "0x" yok sayılır.
     * Geçersizse null.
     */
    fun parseKeyHex(text: String): ByteArray? {
        var t = text.trim()
        if (t.startsWith("0x") || t.startsWith("0X")) t = t.substring(2)
        t = t.filter { !it.isWhitespace() && it != ':' && it != '-' }
        if (t.length != 32 || !t.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return ByteArray(16) { i -> t.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
