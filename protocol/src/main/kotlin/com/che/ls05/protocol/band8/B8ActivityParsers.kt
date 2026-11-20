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

/** Uyku evresi parçası. [stage]: 0 uyanık, 1 hafif, 2 derin, 3 REM (dosyadaki kod / 16). */
data class B8SleepRun(val stage: Int, val minutes: Int) {
    companion object {
        const val AWAKE = 0
        const val LIGHT = 1
        const val DEEP = 2
        const val REM = 3
    }
}

/**
 * Bir uyku oturumu (alt tür 8, sürüm 2). Zamanlar UTC saniyesi; [tzQuarters] dosya kimliğinden.
 * Süreler dakika. [heartPerMinute] [hrStartUtc]'den başlayarak dakikada bir; 0 = ölçüm yok.
 */
class B8Sleep(
    val startUtc: Long,
    val endUtc: Long,
    val tzQuarters: Int,
    val totalMin: Int,
    val awakeMin: Int,
    val lightMin: Int,
    val remMin: Int,
    val deepMin: Int,
    val runs: List<B8SleepRun>,
    val hrStartUtc: Long,
    val heartPerMinute: IntArray,
) {
    /** Bantta "uyanma" sayısı: uyanık parçaların sayısı. */
    val wakeUps: Int get() = runs.count { it.stage == B8SleepRun.AWAKE }

    /** Geçerli dakikalık nabızların yuvarlanmış ortalaması; ölçüm yoksa null. */
    val averageHeart: Int?
        get() {
            val v = heartPerMinute.filter { it in com.che.ls05.protocol.HealthLog.VALID_BPM }
            return if (v.isEmpty()) null else Math.round(v.average()).toInt()
        }
}

/** Elle ölçüm. [type]: 1 nabız, 2 SpO2, 3 stres, 4 sıcaklık. */
data class B8ManualSample(val utcSeconds: Long, val tzQuarters: Int, val type: Int, val value: Int)

sealed class B8Parsed {
    abstract val id: B8FileId
    class DailyDetail(override val id: B8FileId, val records: List<B8MinuteRecord>) : B8Parsed()
    class DailySummary(override val id: B8FileId, val summary: B8DailySummary) : B8Parsed()
    class Manual(override val id: B8FileId, val samples: List<B8ManualSample>) : B8Parsed()
    class Sleep(override val id: B8FileId, val sleep: B8Sleep) : B8Parsed()
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
                id.isSleep && id.detailType == B8FileId.DETAIL -> parseSleep(file)
                // Uyku evreleri dosyası (alt tür 3) cihaz arşivinde görülmedi.
                id.isSleep || id.subtype == B8FileId.SUB_SLEEP_STAGES -> B8Parsed.Unsupported(id, "uyku dosyası ${id.kind()}")
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

    private val SLEEP_MARKER = byteArrayOf(0xfb.toByte(), 0xfa.toByte(), 0xfc.toByte(), 0xff.toByte())

    /**
     * Uyku dosyası, sürüm 2 (belge 7.6; yapı 20.11.2026 cihaz arşiviyle doğrulandı).
     * Gövde: 2 bayt başlık, başlangıç u32, bitiş u32, u16 (bilinmiyor), sayı u16, nabız başlangıcı u32,
     * sonra [sayı] bayt dakikalık nabız. Kalanı `fb fa fc ff` ile ayrılmış kayıtlar:
     * `11` + 8 bayt anahtar + 2 bayt etiket + 1 bayt (0) + yük. Oturum kayıtlarının anahtarı başlangıç u32 + 4 sıfır.
     * Etiket xx 10: özet (0d, sürüm, toplam, uyanık, hafif, REM, derin; u16 büyük endian, dakika).
     * Etiket xx 11: evreler (uzunluk, sonra (evre, dakika) çiftleri; evre 00/10/20/30).
     * Aynı oturum için birden çok kayıt olabilir: anahtarı tutan, boş olmayan son kayıt geçerlidir.
     */
    fun parseSleep(file: B8ActivityFile): B8Parsed {
        val id = file.id
        if (id.version != 2) return B8Parsed.Unsupported(id, "uyku sürümü ${id.version}")
        val b = file.bytes
        val end = b.size - 4
        val base = 8
        if (end - base < 18) return B8Parsed.Unsupported(id, "uyku dosyası kısa")
        fun u16le(p: Int) = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)
        fun u16be(p: Int) = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
        fun u32le(p: Int) = (u16le(p).toLong()) or (u16le(p + 2).toLong() shl 16)
        val start = u32le(base + 2)
        val stop = u32le(base + 6)
        val count = u16le(base + 12)
        val hrStart = u32le(base + 14)
        val hrFrom = base + 18
        if (stop <= start || hrFrom + count > end) return B8Parsed.Unsupported(id, "uyku başlığı geçersiz")
        val hr = IntArray(count) { b[hrFrom + it].toInt() and 0xFF }
        val key = ByteArray(8).also { for (i in 0 until 4) it[i] = b[base + 2 + i] }

        var summary: IntArray? = null
        var runs: List<B8SleepRun>? = null
        var p = hrFrom + count
        // Kayıt sınırları: işaretin her geçtiği yer.
        val cuts = ArrayList<Int>()
        var i = p
        while (i + 4 <= end) {
            if (b[i] == SLEEP_MARKER[0] && b[i + 1] == SLEEP_MARKER[1] && b[i + 2] == SLEEP_MARKER[2] && b[i + 3] == SLEEP_MARKER[3]) {
                cuts += i
                i += 4
            } else i++
        }
        for ((k, cut) in cuts.withIndex()) {
            val from = cut + 4
            val to = if (k + 1 < cuts.size) cuts[k + 1] else end
            if (to - from < 13 || b[from] != 0x11.toByte()) continue
            var match = true
            for (j in 0 until 8) if (b[from + 1 + j] != key[j]) { match = false; break }
            if (!match) continue
            val tag = b[from + 10].toInt() and 0xFF
            p = from + 12 // etiketten sonraki 0 bayt atlanır
            when (tag) {
                0x10 -> if (to - p >= 12) {
                    val v = IntArray(5) { u16be(p + 2 + it * 2) }
                    if (v[0] > 0) summary = v
                }
                0x11 -> {
                    val len = b[p].toInt() and 0xFF
                    if (len > 0 && p + 1 + len <= to) {
                        runs = (0 until len / 2).map { n ->
                            B8SleepRun((b[p + 1 + 2 * n].toInt() and 0xFF) ushr 4, b[p + 2 + 2 * n].toInt() and 0xFF)
                        }
                    }
                }
            }
        }
        val r = runs
        if (summary == null && r == null) return B8Parsed.Unsupported(id, "uyku oturumu yok")
        val list = r ?: emptyList()
        fun sum(stage: Int) = list.filter { it.stage == stage }.sumOf { it.minutes }
        val sm = summary ?: intArrayOf(
            sum(B8SleepRun.LIGHT) + sum(B8SleepRun.DEEP) + sum(B8SleepRun.REM),
            sum(B8SleepRun.AWAKE), sum(B8SleepRun.LIGHT), sum(B8SleepRun.REM), sum(B8SleepRun.DEEP),
        )
        return B8Parsed.Sleep(id, B8Sleep(start, stop, id.tzQuarters, sm[0], sm[1], sm[2], sm[3], sm[4], list, hrStart, hr))
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
