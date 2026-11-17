package com.che.ls05

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.che.ls05.protocol.SecretEnvelope
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Saat kimlik anahtarı (Band 8 auth key) için şifreli depo. Anahtar Android Keystore'daki AES-256-GCM anahtarıyla
 * şifrelenir; "watches" ayar dosyasında yalnızca şifreli zarf durur (SecretEnvelope). Düz anahtar hiçbir yere
 * yazılmaz: log, durum metni, Toast ve istisna mesajı dahil. Hata olursa yalnızca istisna türü loglanır.
 */
object SecretStore {

    private const val ALIAS = "watch-auth-v1"
    private const val FILE = "watches"
    private const val TAG_BITS = 128

    private fun keyName(watchId: String) = "w.$watchId.auth"

    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    /** Onaltılık girişi denetler ve şifreleyip saklar. Giriş geçersizse ya da şifreleme olmazsa false. */
    fun putHex(c: Context, watchId: String, hex: String): Boolean {
        val raw = SecretEnvelope.parseKeyHex(hex) ?: return false
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val ct = cipher.doFinal(raw)
            prefs(c).edit().putString(keyName(watchId), SecretEnvelope.encode(cipher.iv, ct)).commit()
        } catch (e: Exception) {
            WatchHub.log("Anahtar şifrelenemedi (${e.javaClass.simpleName}).")
            false
        } finally {
            raw.fill(0)
        }
    }

    fun has(c: Context, watchId: String): Boolean = SecretEnvelope.decode(prefs(c).getString(keyName(watchId), null)) != null

    /** Çözülmüş anahtar; yoksa ya da çözülemezse null. Çağıran kullandıktan sonra diziyi sıfırlamalı. */
    fun get(c: Context, watchId: String): ByteArray? {
        val (iv, ct) = SecretEnvelope.decode(prefs(c).getString(keyName(watchId), null)) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(ct)
        } catch (e: Exception) {
            WatchHub.log("Anahtar çözülemedi (${e.javaClass.simpleName}); yeniden girilmesi gerekebilir.")
            null
        }
    }

    fun remove(c: Context, watchId: String) {
        prefs(c).edit().remove(keyName(watchId)).apply()
    }
}
