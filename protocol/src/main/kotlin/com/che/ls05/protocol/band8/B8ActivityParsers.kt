package com.che.ls05.protocol.band8

/*
 * Etkinlik dosyası ayrıştırıcıları (belge 7.5, 7.7, 7.9).
 * Çıktı ham kayıtlardır: zaman UTC Unix saniyesi, saat dilimi ayrı (15 dakikalık birim).
 * Yerel saate çevirme burada yapılmaz.
 */

/** Dakikalık kayıt. Değer yoksa ya da geçersizse null. Adım, o dakikanın adımıdır. */
data class B8MinuteRecord(
    val utcSeconds: Long,
    val tzQuarters: Int,
    val steps: Int?,
    val heartRate: Int?,
    val spo2: Int?,
    val stress: Int?,
)

data class B8DailySummary(
    val utcSeconds: Long,
    val tzQuarters: Int,
    val steps: Long,
    val hrResting: Int,
    val hrMax: Int,
    val hrMaxUtc: Long,
    val hrMin: Int,
    val hrMinUtc: Long,
    val hrAvg: Int,
    val stressAvg: Int,
    val stressMax: Int,
    val stressMin: Int,
    val standing: Int,
    val calories: Int,
    val spo2Max: Int,
    val spo2MaxUtc: Long,
    val spo2Min: Int,
    val spo2MinUtc: Long,
    val spo2Avg: Int,
)

/** Elle ölçüm. [type]: 1 nabız, 2 SpO2, 3 stres, 4 sıcaklık. */
data class B8ManualSample(val utcSeconds: Long, val tzQuarters: Int, val type: Int, val value: Int)

sealed class B8Parsed {
    abstract val id: B8FileId
    class DailyDetail(override val id: B8FileId, val records: List<B8MinuteRecord>) : B8Parsed()
    class DailySummary(override val id: B8FileId, val summary: B8DailySummary) : B8Parsed()
    class Manual(override val id: B8FileId, val samples: List<B8ManualSample>) : B8Parsed()
    /** Tanınmayan ya da henüz desteklenmeyen dosya. [reason] kişisel veri içermez. */
    class Unsupported(override val id: B8FileId, val reason: String) : B8Parsed()
}

object B8ActivityParsers {

    fun parse(file: B8ActivityFile): B8Parsed {
        val id = file.id
        return try {
            when {
                id.isDailyDetail -> parseDailyDetail(file)
                id.isDailySummary -> parseDailySummary(file)
                id.isManual -> parseManual(file)
                // TODO(7. adım): uyku dosyası (alt tür 8) ve uyku evreleri (alt tür 3).
                // Belge 7.6'daki yapı kayıtla doğrulanmadı. Gerçek bir sabah eşitlemesi gelmeden yazılmaz.
                id.isSleep || id.subtype == B8FileId.SUB_SLEEP_STAGES -> B8Parsed.Unsupported(id, "uyku henüz yok")
                else -> B8Parsed.Unsupported(id, "bilinmeyen dosya ${id.kind()}")
            }
        } catch (e: IndexOutOfBoundsException) {
            B8Parsed.Unsupported(id, "dosya beklenenden kısa")
        }
    }

    /** Başlık yarım baytlarına göre grup okuyucu. */
    private class Groups(private val header: ByteArray, private val b: ByteArray, var pos: Int) {
        private var group = -1
        private var nibble = 0
        private var bits = 0
        private var value = 0

        fun reset() { group = -1 }

        /** Grup başlıkta varsa baytları okur ve true döner. */
        fun next(nBits: Int): Boolean {
            group++
            if (group >= header.size * 2) throw IndexOutOfBoundsException("başlık kısa")
            val hb = header[group / 2].toInt() and 0xFF
            nibble = if (group % 2 == 0) hb ushr 4 else hb and 0x0F
            if (nibble and 8 == 0) return false
            bits = nBits
            value = when (nBits) {
                8 -> b[pos].toInt() and 0xFF
                16 -> (b[pos].toInt() and 0xFF) or ((b[pos + 1].toInt() and 0xFF) shl 8)
                else -> error("desteklenmeyen grup boyu")
            }
            pos += nBits / 8
            return true
        }

        /** idx 0, 1, 2 için geçerlilik biti: 0x4, 0x2, 0x1. */
        fun valid(idx: Int): Boolean = nibble and (1 shl (2 - idx)) != 0

        fun get(idx: Int, n: Int): Int {
            val shift = bits - idx - n
            return (value ushr shift) and ((1 shl n) - 1)
        }
    }

    fun parseDailyDetail(file: B8ActivityFile): B8Parsed {
        val id = file.id
        val headerSize = when (id.version) { 1, 2 -> 4; 3 -> 5; 4 -> 6; else -> return B8Parsed.Unsupported(id, "ayrıntı sürümü ${id.version}") }
        val b = file.bytes
        val end = b.size - 4
        val header = b.copyOfRange(8, 8 + headerSize)
        val g = Groups(header, b, 8 + headerSize)
        val out = ArrayList<B8MinuteRecord>()
        var t = id.utcSeconds
        while (g.pos < end) {
            val start = g.pos
            try {
                g.reset()
                var extra = 0
                var steps: Int? = null
                var hr: Int? = null
                var spo2: Int? = null
                var stress: Int? = null
                if (g.next(16)) {
                    if (g.valid(1)) extra = g.get(1, 1)
                    if (g.valid(2)) steps = g.get(2, 14)
                }
                g.next(8)   // etkinlik türü ve kalori (kullanılmıyor)
                g.next(8)
                g.next(16)  // mesafe olabilir
                if (g.next(8) && g.valid(0)) hr = g.get(0, 8).takeIf { it in 1..254 }
                g.next(8)   // enerji
                g.next(16)
                if (id.version >= 3) {
                    if (g.next(8) && g.valid(0)) spo2 = g.get(0, 8).takeIf { it in 1..100 }
                    if (g.next(8) && g.valid(0)) stress = g.get(0, 8).takeIf { it != 255 }
                }
                if (extra == 1) g.next(8)
                if (id.version >= 4) { g.next(16); g.next(16) }
                if (g.pos > end || g.pos == start) { g.pos = start; break }
                out.add(B8MinuteRecord(t, id.tzQuarters, steps, hr, spo2, stress))
            } catch (e: IndexOutOfBoundsException) {
                break
            }
            t += 60
        }
        return B8Parsed.DailyDetail(id, out)
    }

    fun parseDailySummary(file: B8ActivityFile): B8Parsed {
        val id = file.id
        if (id.version != 5) return B8Parsed.Unsupported(id, "özet sürümü ${id.version}")
        val b = file.bytes
        var p = 8 + 4
        if (b.size - 4 - p < 51) return B8Parsed.Unsupported(id, "özet kısa")
        fun u8(): Int = b[p++].toInt() and 0xFF
        fun u16(): Int { val v = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8); p += 2; return v }
        fun u24(): Int { val v = u16(); return v or (u8() shl 16) }
        fun u32(): Long { val lo = u16().toLong(); return lo or (u16().toLong() shl 16) }
        val steps = u32()
        p += 3
        val rest = u8(); val hrMax = u8(); val hrMaxTs = u32(); val hrMin = u8(); val hrMinTs = u32(); val hrAvg = u8()
        val sAvg = u8(); val sMax = u8(); val sMin = u8()
        val standing = u24()
        val cal = u16()
        p += 3
        val oMax = u8(); val oMaxTs = u32(); val oMin = u8(); val oMinTs = u32(); val oAvg = u8()
        return B8Parsed.DailySummary(id, B8DailySummary(
            id.utcSeconds, id.tzQuarters, steps, rest, hrMax, hrMaxTs, hrMin, hrMinTs, hrAvg,
            sAvg, sMax, sMin, standing, cal, oMax, oMaxTs, oMin, oMinTs, oAvg,
        ))
    }

    fun parseManual(file: B8ActivityFile): B8Parsed {
        val id = file.id
        val b = file.bytes
        val end = b.size - 4
        var p = when (id.version) { 1 -> 9; 2 -> 8; else -> return B8Parsed.Unsupported(id, "elle ölçüm sürümü ${id.version}") }
        val out = ArrayList<B8ManualSample>()
        while (p + 5 <= end) {
            val ts = (b[p].toLong() and 0xFF) or ((b[p + 1].toLong() and 0xFF) shl 8) or
                ((b[p + 2].toLong() and 0xFF) shl 16) or ((b[p + 3].toLong() and 0xFF) shl 24)
            val type = b[p + 4].toInt() and 0xFF
            p += 5
            val value: Int = when (type) {
                1, 2, 3 -> { if (p + 1 > end) break; b[p++].toInt() and 0xFF }
                4 -> {
                    if (p + 4 > end) break
                    val v = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
                        ((b[p + 2].toInt() and 0xFF) shl 16) or ((b[p + 3].toInt() and 0xFF) shl 24)
                    p += 4; v
                }
                else -> return B8Parsed.Manual(id, out) // bilinmeyen tür: buraya kadarını ver
            }
            if (value != 0) out.add(B8ManualSample(ts, id.tzQuarters, type, value))
        }
        return B8Parsed.Manual(id, out)
    }
}
