package com.che.ls05.protocol.band8

import java.io.ByteArrayOutputStream

/**
 * Uyku dosyası (tür 0, alt tür 8, sürüm 2) için sentetik dosya üretici. Yapı 20.11.2026 cihaz arşiviyle
 * eşleştirildi; buradaki bütün değerler uydurmadır.
 */
internal object B8SleepFixture {
    val MARKER = byteArrayOf(0xfb.toByte(), 0xfa.toByte(), 0xfc.toByte(), 0xff.toByte())

    fun le32(v: Long) = byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte(), ((v ushr 24) and 0xFF).toByte())
    fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())
    fun be16(v: Int) = byteArrayOf(((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

    fun key(start: Long) = le32(start) + ByteArray(4)

    fun summaryRecord(start: Long, total: Int, awake: Int, light: Int, rem: Int, deep: Int, tag: Int = 0x0010, ver: Int = 0x10): ByteArray =
        byteArrayOf(0x11) + key(start) + be16(tag) + byteArrayOf(0, 0x0d, ver.toByte()) +
            be16(total) + be16(awake) + be16(light) + be16(rem) + be16(deep) + byteArrayOf(0, 0)

    fun stageRecord(start: Long, pairs: List<Pair<Int, Int>>, tag: Int = 0x0011): ByteArray {
        val b = ByteArrayOutputStream()
        b.write(byteArrayOf(0x11) + key(start) + be16(tag) + byteArrayOf(0, (pairs.size * 2).toByte()))
        for ((s, m) in pairs) { b.write(s); b.write(m) }
        return b.toByteArray()
    }

    /** Bilinmeyen türde, anahtarı oturuma uymayan kayıt (yok sayılmalı). */
    fun otherRecord(seed: Int) = byteArrayOf(0x11) + le32(seed.toLong()) + ByteArray(4) + be16(0x0101) + byteArrayOf(0, 7, 7, 7)

    fun file(start: Long, end: Long, tzQuarters: Int, hr: IntArray, records: List<ByteArray>, prefix: ByteArray = byteArrayOf(1, 2, 3)): B8ActivityFile {
        val body = ByteArrayOutputStream()
        body.write(byteArrayOf(0xf0.toByte(), 0x00))
        body.write(le32(start)); body.write(le32(end)); body.write(le16(0)); body.write(le16(hr.size)); body.write(le32(start))
        for (v in hr) body.write(v)
        body.write(prefix)
        for (r in records) { body.write(MARKER); body.write(r) }
        val id = B8FileId(end, tzQuarters, 2, 0, B8FileId.SUB_SLEEP, 0)
        return B8ActivityFile(id, B8Activity.buildFile(id, body.toByteArray()))
    }
}
