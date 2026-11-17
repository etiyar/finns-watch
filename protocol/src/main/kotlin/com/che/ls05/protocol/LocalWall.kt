package com.che.ls05.protocol

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Ortak sağlık modelinde tek zaman kuralı: her kayıt, kaydedildiği saat dilimiyle yerel duvar saatine çevrilir.
 * Kaydın saat dilimi yoksa telefonun bölgesi (o anki kuralıyla) kullanılır. LS05 kayıtları zaten yereldir.
 * Band 8: zaman UTC Unix saniyesi, saat dilimi ayrı bir bayt (15 dakika birimi, işaretli).
 */
object LocalWall {

    /** Gerçek saat dilimleri UTC-12 ile UTC+14 arasında: 15 dakika biriminde -48..56. */
    val QUARTER_RANGE = -48..56

    /** İşaretli bayt değerini 15 dakika birimine çevirir (0xF4 → -12, yani UTC-3). */
    fun quarterFromByte(b: Int): Int = b.toByte().toInt()

    /** Kaydın UTC farkı (saniye). Geçersiz ya da yoksa telefonun o andaki farkı. */
    fun offsetOf(utcSec: Long, tzQuarter: Int?, fallback: ZoneId): ZoneOffset =
        if (tzQuarter != null && tzQuarter in QUARTER_RANGE) {
            ZoneOffset.ofTotalSeconds(tzQuarter * 900)
        } else {
            fallback.rules.getOffset(Instant.ofEpochSecond(utcSec))
        }

    fun of(utcSec: Long, tzQuarter: Int?, fallback: ZoneId): LocalDateTime =
        LocalDateTime.ofEpochSecond(utcSec, 0, offsetOf(utcSec, tzQuarter, fallback))
}
