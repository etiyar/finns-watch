package com.che.ls05.protocol.band8

/** Testlerde onaltılık metin. Boşluklar yok sayılır. */
internal fun hex(s: String): ByteArray {
    val t = s.replace(" ", "")
    return ByteArray(t.length / 2) { t.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

/**
 * Sabit TEST değerleri. Gerçek auth key değil: SHA-256("band8-test-key") ilk 16 bayt.
 * Altın vektörler repo dışındaki bağımsız bir Python betiğiyle (cryptography kütüphanesi) üretildi.
 */
internal object B8TestVectors {
    val KEY = hex("984309ba053c0c4fd160802cb7e86b01")
    val PHONE_NONCE = hex("74d73058e75b8d83db2b5fd743bb97c7")
    val BAND_NONCE = hex("2ce60e6a8b1422d5c4da506a51f114e2")
    val HKDF64 = hex("0c6ffa0dcba8889be5ce456ae7bfe76d56d619eb64b0dcc4c88534c16a19dc3cfe598d67a33c8df60ea183a4e9b034f9626285be2050aff453880628d97c448c")
    val BAND_HMAC = hex("ee7e2f8e06ae560e777983afc0c95bb1e43e688aca01fe48bcf4dbb4ae3ea451")
    val PHONE_HMAC = hex("450612ef7fcaf640d47388b335fae49dfe2cdc2a7518b246a26e9deea04c011d")
    /** Pil isteği (08 02 10 01), sayaç 1. */
    val TO_BAND_CTR1 = hex("d35da1d20661a920")
    /** 0..39 baytları, sayaç 0. */
    val TO_BAND_CTR0_LONG = hex("b8fda617513b777d5247bc00432d542f7cba6fb5c13be2b3985f2a12a39b91a617e0fabb23fa7ab02eacf732")
    /** Sentetik pil yanıtı: seviye 55, durum 2. */
    val BAT_PLAIN = hex("08021001220812060a0408371002")
    val FROM_BAND = hex("cf55091c7b2089d7872249e6dc63e3d02005")
}
