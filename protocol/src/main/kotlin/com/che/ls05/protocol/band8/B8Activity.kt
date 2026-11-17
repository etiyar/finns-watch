package com.che.ls05.protocol.band8

import java.util.zip.CRC32

/**
 * Etkinlik dosyası kimliği, 7 bayt (belge 7.3).
 *
 * @param utcSeconds başlangıç zamanı, UTC Unix saniyesi.
 * @param tzQuarters saat dilimi farkı, 15 dakikalık birim, işaretli.
 */
data class B8FileId(
    val utcSeconds: Long,
    val tzQuarters: Int,
    val version: Int,
    val type: Int,
    val subtype: Int,
    val detailType: Int,
) {
    fun encode(): ByteArray {
        val flags = ((type and 1) shl 7) or ((subtype and 0x1F) shl 2) or (detailType and 3)
        return byteArrayOf(
            (utcSeconds and 0xFF).toByte(), ((utcSeconds ushr 8) and 0xFF).toByte(),
            ((utcSeconds ushr 16) and 0xFF).toByte(), ((utcSeconds ushr 24) and 0xFF).toByte(),
            tzQuarters.toByte(), version.toByte(), flags.toByte(),
        )
    }

    val isDailyDetail: Boolean get() = type == TYPE_ACTIVITY && subtype == SUB_DAILY && detailType == DETAIL
    val isDailySummary: Boolean get() = type == TYPE_ACTIVITY && subtype == SUB_DAILY && detailType == SUMMARY
    val isManual: Boolean get() = type == TYPE_ACTIVITY && subtype == SUB_MANUAL
    val isSleep: Boolean get() = type == TYPE_ACTIVITY && subtype == SUB_SLEEP

    /** Loglar için: kimliği gizler, yalnızca türü söyler. */
    fun kind(): String = "t$type/s$subtype/d$detailType/v$version"

    companion object {
        const val TYPE_ACTIVITY = 0
        const val TYPE_SPORTS = 1
        const val SUB_DAILY = 0
        const val SUB_SLEEP_STAGES = 3
        const val SUB_MANUAL = 6
        const val SUB_SLEEP = 8
        const val DETAIL = 0
        const val SUMMARY = 1

        fun decode(b: ByteArray, off: Int = 0): B8FileId {
            require(b.size - off >= 7) { "dosya kimliği 7 bayt olmalı" }
            val ts = (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
                ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24)
            val flags = b[off + 6].toInt() and 0xFF
            return B8FileId(
                utcSeconds = ts,
                tzQuarters = b[off + 4].toInt(),
                version = b[off + 5].toInt() and 0xFF,
                type = flags ushr 7,
                subtype = (flags ushr 2) and 0x1F,
                detailType = flags and 3,
            )
        }
    }
}

/** CRC'si tutmuş, tam bir etkinlik dosyası. [bytes]: kimlik + dolgu + gövde + CRC. */
class B8ActivityFile(val id: B8FileId, val bytes: ByteArray) {
    /** Dolgudan sonraki gövde, CRC hariç. */
    fun body(): ByteArray = bytes.copyOfRange(8, bytes.size - 4)
}

object B8Activity {
    fun crc32(b: ByteArray, from: Int = 0, to: Int = b.size): Long {
        val c = CRC32()
        c.update(b, from, to - from)
        return c.value
    }

    /** Son 4 bayt, öncesinin CRC32'si (LE) mi. */
    fun crcOk(b: ByteArray): Boolean {
        if (b.size < 12) return false
        val n = b.size
        val stored = (b[n - 4].toLong() and 0xFF) or ((b[n - 3].toLong() and 0xFF) shl 8) or
            ((b[n - 2].toLong() and 0xFF) shl 16) or ((b[n - 1].toLong() and 0xFF) shl 24)
        return stored == crc32(b, 0, n - 4)
    }

    /** Test ve sahte band için: kimlik + 0 + gövde + CRC. */
    fun buildFile(id: B8FileId, body: ByteArray): ByteArray {
        val d = id.encode() + byteArrayOf(0) + body
        val c = crc32(d)
        return d + ByteArray(4) { ((c ushr (8 * it)) and 0xFF).toByte() }
    }

    /** Test ve sahte band için: dosyayı 4 baytlık başlıklı bölümlere böler (en çok 4092 bayt veri). */
    fun splitParts(file: ByteArray, partData: Int = PART_DATA): List<ByteArray> {
        val total = (file.size + partData - 1) / partData
        return (0 until total).map { i ->
            val from = i * partData
            val to = minOf(file.size, from + partData)
            byteArrayOf((total and 0xFF).toByte(), (total ushr 8).toByte(),
                ((i + 1) and 0xFF).toByte(), ((i + 1) ushr 8).toByte()) + file.copyOfRange(from, to)
        }
    }

    const val PART_DATA = 4092
}

/**
 * 0x0053'ten gelen çözülmüş bölümleri birleştirir (belge 7.4).
 * Sıra 1 olan bölüm tamponu sıfırlar. Son bölümde CRC denetlenir.
 */
class B8PartAssembler {
    private var buf = java.io.ByteArrayOutputStream()
    private var nextIndex = 1
    private var total = 0

    val inProgress: Boolean get() = nextIndex > 1

    sealed class Result {
        object NeedMore : Result()
        class Complete(val file: B8ActivityFile) : Result()
        /** Bozuk dosya. [reason] kişisel veri içermez. */
        class Failed(val reason: String) : Result()
    }

    fun add(part: ByteArray): Result {
        if (part.size < 4) return fail("bölüm çok kısa")
        val tot = (part[0].toInt() and 0xFF) or ((part[1].toInt() and 0xFF) shl 8)
        val idx = (part[2].toInt() and 0xFF) or ((part[3].toInt() and 0xFF) shl 8)
        if (tot == 0 || idx == 0 || idx > tot) return fail("bölüm başlığı geçersiz")
        if (idx == 1) reset()
        if (idx != nextIndex || (total != 0 && tot != total)) return fail("bölüm sırası bozuk")
        total = tot
        buf.write(part, 4, part.size - 4)
        nextIndex++
        if (idx < tot) return Result.NeedMore
        val data = buf.toByteArray()
        reset()
        if (data.size < 12) return Result.Failed("dosya çok kısa")
        if (!B8Activity.crcOk(data)) return Result.Failed("CRC tutmadı")
        return Result.Complete(B8ActivityFile(B8FileId.decode(data), data))
    }

    fun reset() {
        buf = java.io.ByteArrayOutputStream()
        nextIndex = 1
        total = 0
    }

    private fun fail(reason: String): Result { reset(); return Result.Failed(reason) }
}
