package com.che.ls05.protocol

import com.che.ls05.protocol.band8.B8ActivityFile
import com.che.ls05.protocol.band8.B8ActivityParsers
import com.che.ls05.protocol.band8.B8Parsed
import com.che.ls05.protocol.band8.B8SleepRun
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/*
 * Ortak sağlık modeli (docs/COKLU_SAAT_TASARIM.md 5.2). Zamanlar yerel duvar saatidir (LocalWall).
 */

enum class SourceKind { LS05, BAND8 }

/** Sağlık kaynağı: saat kaydı. [name] CSV'nin kaynak sütununa yazılır. */
data class HealthSource(val id: String, val name: String, val kind: SourceKind)

/** REM ayrı aşamadır; asla uyanık sayılmaz. */
enum class SleepStage(val code: Char) {
    DEEP('D'), LIGHT('L'), REM('R'), AWAKE('A');

    companion object {
        fun of(code: Char): SleepStage? = entries.firstOrNull { it.code == code }
        fun ofBand8(stage: Int): SleepStage = when (stage) {
            B8SleepRun.LIGHT -> LIGHT
            B8SleepRun.DEEP -> DEEP
            B8SleepRun.REM -> REM
            else -> AWAKE
        }
    }
}

data class SleepPart(val start: LocalDateTime, val minutes: Int, val stage: SleepStage)

/** Band 8 uyku oturumu. Süreler bandın özetinden (dakika). [wakeDay]: uyanılan gün (bitişin yerel tarihi). */
data class Band8SleepSession(
    val start: LocalDateTime,
    val end: LocalDateTime,
    val parts: List<SleepPart>,
    val wakeUps: Int,
    val avgHeart: Int?,
    val awakeMin: Int,
    val lightMin: Int,
    val remMin: Int,
    val deepMin: Int,
) {
    val wakeDay: LocalDate get() = end.toLocalDate()
}

/**
 * Bir Band 8 saatinin sağlık verisi. Adım: yerel (gün, saat) toplamı, dakikalık kayıtlardan. Günlük toplam
 * özet dosyasından (o güne ait en son özet). Nabız: dakika başına (ayrıntı dosyası önce, yoksa uyku dosyası).
 */
class Band8Health(
    val stepHours: Map<Pair<LocalDate, Int>, Int>,
    val stepDays: Map<LocalDate, Int>,
    val heart: Map<LocalDateTime, Int>,
    val sleep: List<Band8SleepSession>,
) {
    val isEmpty: Boolean get() = stepHours.isEmpty() && stepDays.isEmpty() && heart.isEmpty() && sleep.isEmpty()

    companion object {
        val EMPTY = Band8Health(emptyMap(), emptyMap(), emptyMap(), emptyList())

        /** Arşivdeki dosyalardan. Aynı arşiv her zaman aynı sonucu verir (sıra önemsiz). */
        fun fromFiles(files: List<B8ActivityFile>, fallback: ZoneId): Band8Health {
            val sorted = files.sortedWith(compareBy({ it.id.utcSeconds }, { it.id.encode().joinToString("") { b -> "%02x".format(b) } }))
            val stepMinutes = java.util.TreeMap<LocalDateTime, Int>()
            val heart = java.util.TreeMap<LocalDateTime, Int>()
            val summaries = HashMap<LocalDate, Pair<Long, Int>>()
            val sleeps = java.util.TreeMap<Long, Pair<Long, com.che.ls05.protocol.band8.B8Sleep>>()
            for (f in sorted) {
                when (val p = B8ActivityParsers.parse(f)) {
                    is B8Parsed.DailyDetail -> for (r in p.records) {
                        val t = LocalWall.of(r.utcSeconds, r.tzQuarters, fallback).truncatedTo(ChronoUnit.MINUTES)
                        val s = r.steps
                        if (s != null && s > 0) stepMinutes[t] = s else stepMinutes.remove(t)
                        val hr = r.heartRate
                        if (hr != null && hr in HealthLog.VALID_BPM) heart[t] = hr
                    }
                    is B8Parsed.DailySummary -> {
                        val day = LocalWall.of(p.id.utcSeconds, p.id.tzQuarters, fallback).toLocalDate()
                        val prev = summaries[day]
                        if (prev == null || p.id.utcSeconds >= prev.first) {
                            summaries[day] = p.id.utcSeconds to p.summary.steps.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
                        }
                    }
                    is B8Parsed.Sleep -> {
                        val prev = sleeps[p.sleep.startUtc]
                        if (prev == null || p.id.utcSeconds >= prev.first) sleeps[p.sleep.startUtc] = p.id.utcSeconds to p.sleep
                    }
                    else -> Unit
                }
            }
            val sessions = ArrayList<Band8SleepSession>()
            for ((_, pair) in sleeps) {
                val s = pair.second
                val start = LocalWall.of(s.startUtc, s.tzQuarters, fallback)
                val end = LocalWall.of(s.endUtc, s.tzQuarters, fallback)
                var t = start
                val parts = s.runs.filter { it.minutes > 0 }.map { r ->
                    SleepPart(t, r.minutes, SleepStage.ofBand8(r.stage)).also { t = t.plusMinutes(r.minutes.toLong()) }
                }
                sessions += Band8SleepSession(start, end, parts, s.wakeUps, s.averageHeart, s.awakeMin, s.lightMin, s.remMin, s.deepMin)
                val hrStart = LocalWall.of(s.hrStartUtc, s.tzQuarters, fallback).truncatedTo(ChronoUnit.MINUTES)
                s.heartPerMinute.forEachIndexed { i, v ->
                    if (v in HealthLog.VALID_BPM) heart.putIfAbsent(hrStart.plusMinutes(i.toLong()), v)
                }
            }
            val hours = java.util.TreeMap<Pair<LocalDate, Int>, Int>(compareBy({ it.first }, { it.second }))
            for ((t, s) in stepMinutes) {
                val k = t.toLocalDate() to t.hour
                hours[k] = (hours[k] ?: 0) + s
            }
            return Band8Health(hours, summaries.mapValues { it.value.second }, heart, sessions)
        }

        /** Gün dosyalarından okunan parçaları birleştirir. */
        fun combine(parts: List<Band8Health>): Band8Health {
            val hours = LinkedHashMap<Pair<LocalDate, Int>, Int>()
            val days = LinkedHashMap<LocalDate, Int>()
            val heart = java.util.TreeMap<LocalDateTime, Int>()
            val sleep = ArrayList<Band8SleepSession>()
            for (p in parts) {
                hours.putAll(p.stepHours)
                days.putAll(p.stepDays)
                heart.putAll(p.heart)
                sleep += p.sleep
            }
            return Band8Health(hours, days, heart, sleep.distinct().sortedBy { it.start })
        }
    }
}

/**
 * Band 8 verisinin gün dosyası: `health-<kimlik>/band8-days/yyyy-MM-dd.txt`. Arşivden her ayrıştırmada aynı
 * metin üretilir; dosya yalnızca değiştiyse yeniden yazılır, böylece yeniden ayrıştırma çift kayıt üretmez.
 * Satırlar (sekme ayraçlı): S saat adım | T günlük toplam | H ssdd nabız |
 * U başlangıç bitiş uyanma ortNabız uyanık hafif rem derin | P oturumBaşı parçaBaşı dakika evre.
 * Uyku oturumu uyanılan günün dosyasındadır.
 */
object Band8DayFile {
    const val HEADER = "# finnswatch band8 gün v1"

    fun days(h: Band8Health): Set<LocalDate> {
        val out = java.util.TreeSet<LocalDate>()
        h.stepHours.keys.forEach { out += it.first }
        out += h.stepDays.keys
        h.heart.keys.forEach { out += it.toLocalDate() }
        h.sleep.forEach { out += it.wakeDay }
        return out
    }

    private fun hhmm(t: LocalDateTime) = "%02d%02d".format(t.hour, t.minute)

    fun write(day: LocalDate, h: Band8Health): String {
        val sb = StringBuilder(HEADER).append('\n')
        for (hour in 0..23) h.stepHours[day to hour]?.let { sb.append("S\t$hour\t$it\n") }
        h.stepDays[day]?.let { sb.append("T\t$it\n") }
        for ((t, v) in h.heart.entries.filter { it.key.toLocalDate() == day }.sortedBy { it.key }) sb.append("H\t${hhmm(t)}\t$v\n")
        for (s in h.sleep.filter { it.wakeDay == day }.sortedBy { it.start }) {
            sb.append("U\t${s.start}\t${s.end}\t${s.wakeUps}\t${s.avgHeart ?: "-"}\t${s.awakeMin}\t${s.lightMin}\t${s.remMin}\t${s.deepMin}\n")
            for (p in s.parts) sb.append("P\t${s.start}\t${p.start}\t${p.minutes}\t${p.stage.code}\n")
        }
        return sb.toString()
    }

    /** Bozuk satırlar atlanır. */
    fun read(day: LocalDate, text: String): Band8Health {
        val hours = LinkedHashMap<Pair<LocalDate, Int>, Int>()
        val days = LinkedHashMap<LocalDate, Int>()
        val heart = java.util.TreeMap<LocalDateTime, Int>()
        class S(val u: List<String>, val parts: ArrayList<SleepPart> = ArrayList())
        val sessions = LinkedHashMap<String, S>()
        for (line in text.split('\n')) {
            val p = line.split('\t')
            try {
                when (p[0]) {
                    "S" -> if (p.size == 3) {
                        val hour = p[1].toInt(); val v = p[2].toInt()
                        if (hour in 0..23 && v >= 0) hours[day to hour] = v
                    }
                    "T" -> if (p.size == 2) p[1].toInt().takeIf { it >= 0 }?.let { days[day] = it }
                    "H" -> if (p.size == 3 && p[1].length == 4) {
                        val hh = p[1].substring(0, 2).toInt(); val mm = p[1].substring(2).toInt(); val v = p[2].toInt()
                        if (hh in 0..23 && mm in 0..59 && v in HealthLog.VALID_BPM) heart[day.atTime(hh, mm)] = v
                    }
                    "U" -> if (p.size == 9) sessions[p[1]] = S(p)
                    "P" -> if (p.size == 5) {
                        val st = SleepStage.of(p[4].single()) ?: continue
                        sessions[p[1]]?.parts?.add(SleepPart(LocalDateTime.parse(p[2]), p[3].toInt(), st))
                    }
                }
            } catch (_: Exception) {
            }
        }
        val sleep = sessions.values.mapNotNull { s ->
            try {
                val u = s.u
                Band8SleepSession(
                    LocalDateTime.parse(u[1]), LocalDateTime.parse(u[2]), s.parts.toList(), u[3].toInt(),
                    u[4].takeIf { it != "-" }?.toInt(), u[5].toInt(), u[6].toInt(), u[7].toInt(), u[8].toInt(),
                )
            } catch (_: Exception) {
                null
            }
        }
        return Band8Health(hours, days, heart, sleep)
    }
}
