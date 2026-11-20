package com.che.ls05.protocol

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Birleşik sağlık görünümü (docs/COKLU_SAAT_TASARIM.md 5.4). Ham veriler kaynaklarında kalır; seçim yalnızca burada.
 *
 * - Adım: her (gün, saat) için kaynakların en büyüğü; asla toplanmaz. Gün toplamı = max(saatlik toplam,
 *   bandın günlük özeti).
 * - Nabız: dakika başına tek kaynak. Birden çok kaynak varsa o dakikada etkin saat; bilinmiyorsa önce LS05.
 * - Uyku: oturum oturum. Çakışan oturumlardan daha ayrıntılı (aşama sayısı), sonra bütün (5 dk'dan uzun boşluk
 *   yok), sonra daha uzun olan; hâlâ eşitse LS05. Çakışmayanların hepsi kalır.
 *
 * Yalnızca LS05 kaynağı varken her sorgu [HealthView] ile birebir aynı sonucu verir.
 */
class MergedHealth(
    private val ls05: List<Pair<HealthSource, HealthView>>,
    private val band8: List<Pair<HealthSource, Band8Health>>,
    /** O dakikada etkin saatin kimliği (geçiş günlüğü); bilinmiyorsa null. */
    private val activeAt: (LocalDateTime) -> String?,
    private val activeId: String?,
) : HealthQuery {

    private class Pick(val value: Int, val source: HealthSource)

    private val hours = HashMap<Pair<UteDate, Int>, Pick>()
    private val dayTotals = HashMap<UteDate, Pick>()
    private val heart: List<Pair<HeartPoint, HealthSource>>
    private val heartSourceByMinute = HashMap<LocalDateTime, HealthSource>()

    init {
        for ((src, v) in ls05) for ((k, s) in v.stepHours()) offerHour(k, s, src)
        for ((src, b) in band8) {
            for ((k, s) in b.stepHours) offerHour(ute(k.first) to k.second, s, src)
            for ((d, s) in b.stepDays) {
                val u = ute(d)
                val cur = dayTotals[u]
                if (cur == null || s > cur.value) dayTotals[u] = Pick(s, src)
            }
        }

        // Nabız: kaynak başına sıralı noktalar, dakikaya göre kaynak seçimi.
        val bySource = ArrayList<Pair<HealthSource, List<HeartPoint>>>()
        for ((src, v) in ls05) bySource += src to v.heartPoints()
        for ((src, b) in band8) bySource += src to b.heart.entries.sortedBy { it.key }.map { HeartPoint(it.key, it.value) }
        val present = HashMap<LocalDateTime, MutableList<HealthSource>>()
        for ((src, pts) in bySource) for (p in pts) {
            val l = present.getOrPut(minute(p.at)) { ArrayList() }
            if (src !in l) l += src
        }
        for ((m, srcs) in present) heartSourceByMinute[m] = if (srcs.size == 1) srcs[0] else chooseHeart(m, srcs)
        val all = ArrayList<Pair<HeartPoint, HealthSource>>()
        for ((src, pts) in bySource) for (p in pts) if (heartSourceByMinute[minute(p.at)] == src) all += p to src
        heart = all.sortedBy { it.first.at }
    }

    private fun minute(t: LocalDateTime) = t.truncatedTo(ChronoUnit.MINUTES)

    private fun chooseHeart(m: LocalDateTime, srcs: List<HealthSource>): HealthSource {
        val active = activeAt(m)
        srcs.firstOrNull { it.id == active }?.let { return it }
        return srcs.firstOrNull { it.kind == SourceKind.LS05 } ?: srcs[0]
    }

    private fun offerHour(k: Pair<UteDate, Int>, steps: Int, src: HealthSource) {
        val cur = hours[k]
        if (cur == null || steps > cur.value) hours[k] = Pick(steps, src)
    }

    private fun ute(d: LocalDate) = UteDate(d.year, d.monthValue, d.dayOfMonth)

    // ---------- Adım ----------

    override fun stepsForDay(day: UteDate): Int? {
        val hs = hours.filterKeys { it.first == day }
        val total = dayTotals[day]
        if (hs.isEmpty() && total == null) return null
        return maxOf(hs.values.sumOf { it.value }, total?.value ?: 0)
    }

    override fun hourlySteps(day: UteDate): IntArray {
        val out = IntArray(24)
        for ((k, v) in hours) if (k.first == day && k.second in 0..23) out[k.second] = v.value
        return out
    }

    override fun stepSource(day: UteDate, hour: Int): String? = hours[day to hour]?.source?.name

    /** Band 8'den o güne saatlik ya da günlük adım var mı (saatlik grafiğin gösterilmesi için). */
    fun hasBand8Steps(day: UteDate): Boolean =
        hours.any { it.key.first == day && it.value.source.kind == SourceKind.BAND8 } ||
            dayTotals[day]?.source?.kind == SourceKind.BAND8

    // ---------- Nabız ----------

    override val heartSummary: UteFrame.HeartSummary? =
        (ls05.firstOrNull { it.first.id == activeId } ?: ls05.firstOrNull { it.second.heartSummary != null })?.second?.heartSummary

    override fun heartPoints(): List<HeartPoint> = heart.map { it.first }

    override fun lastHeart(notAfter: LocalDateTime?): HeartPoint? =
        heart.asSequence().map { it.first }.filter { notAfter == null || !it.at.isAfter(notAfter) }.maxByOrNull { it.at }

    override fun heartSource(at: LocalDateTime): String? = heartSourceByMinute[minute(at)]?.name

    // ---------- Uyku ----------

    private class Session(
        val source: HealthSource,
        val start: LocalDateTime,
        val end: LocalDateTime,
        val segments: List<SleepSegment>,
        val stages: Int,
        val whole: Boolean,
        /** Yalnızca LS05 oturumu: o günün LS05 görünümü (tek seçilirse aynen döner). */
        val ls05View: HealthView?,
        val band: Band8SleepSession?,
    ) {
        val minutes: Long get() = ChronoUnit.MINUTES.between(start, end)
        fun overlaps(o: Session) = start.isBefore(o.end) && o.start.isBefore(end)
    }

    private val chosenByDay = HashMap<UteDate, List<Session>>()

    private fun stageCount(types: Collection<Int>) =
        types.toSet().count { it == SleepSegment.DEEP || it == SleepSegment.LIGHT || it == SleepSegment.REM }

    private fun ls05Session(src: HealthSource, v: HealthView, day: UteDate): Session? {
        val night = v.sleepFor(day) ?: return null
        val segs = v.sleepSegmentsFor(day)
        val wake = LocalDateTime.of(day.year, day.month, day.day, 0, 0)
        val spans = segs.map { s ->
            val st = (if (s.hour >= 18) wake.minusDays(1) else wake).withHour(s.hour).withMinute(s.minute)
            st to st.plusMinutes(s.minutes.toLong())
        }.sortedBy { it.first }
        val whole = spans.zipWithNext().all { (a, b) -> ChronoUnit.MINUTES.between(a.second, b.first) <= 5 }
        return Session(src, night.start, night.end, segs, stageCount(segs.map { it.type }), whole, v, null)
    }

    private fun bandSession(src: HealthSource, s: Band8SleepSession): Session {
        val segs = s.parts.map {
            val type = when (it.stage) {
                SleepStage.DEEP -> SleepSegment.DEEP
                SleepStage.LIGHT -> SleepSegment.LIGHT
                SleepStage.REM -> SleepSegment.REM
                SleepStage.AWAKE -> SleepSegment.AWAKE
            }
            SleepSegment(it.start.hour, it.start.minute, type, it.minutes)
        }
        val whole = s.parts.zipWithNext().all { (a, b) -> ChronoUnit.MINUTES.between(a.start.plusMinutes(a.minutes.toLong()), b.start) <= 5 }
        return Session(src, s.start, s.end, segs, stageCount(segs.map { it.type }), whole, null, s)
    }

    private val preference = compareByDescending<Session> { it.stages }
        .thenByDescending { it.whole }
        .thenByDescending { it.minutes }
        .thenBy { if (it.source.kind == SourceKind.LS05) 0 else 1 }

    private fun chosen(day: UteDate): List<Session> = chosenByDay.getOrPut(day) {
        val all = ArrayList<Session>()
        for ((src, v) in ls05) ls05Session(src, v, day)?.let { all += it }
        for ((src, b) in band8) for (s in b.sleep) if (ute(s.wakeDay) == day && s.parts.isNotEmpty()) all += bandSession(src, s)
        val picked = ArrayList<Session>()
        for (s in all.sortedWith(preference)) if (picked.none { it.overlaps(s) }) picked += s
        picked.sortedBy { it.start }
    }

    private fun order(s: SleepSegment) = (if (s.hour >= 18) s.hour - 24 else s.hour) * 60 + s.minute

    override fun sleepSegmentsFor(day: UteDate): List<SleepSegment> {
        val c = chosen(day)
        if (c.size == 1 && c[0].ls05View != null) return c[0].ls05View!!.sleepSegmentsFor(day)
        return c.flatMap { it.segments }.sortedBy { order(it) }
    }

    override fun sleepFor(day: UteDate): SleepNight? {
        val c = chosen(day)
        if (c.isEmpty()) return null
        if (c.size == 1 && c[0].ls05View != null) return c[0].ls05View!!.sleepFor(day)
        val segs = c.flatMap { it.segments }
        fun sum(t: Int) = segs.filter { it.type == t }.sumOf { it.minutes }
        val bands = c.mapNotNull { it.band }
        val withHr = bands.filter { it.avgHeart != null }
        return SleepNight(
            date = day,
            start = c.minOf { it.start },
            end = c.maxOf { it.end },
            deepMin = sum(SleepSegment.DEEP),
            lightMin = sum(SleepSegment.LIGHT),
            awakeMin = segs.filter { it.type != SleepSegment.DEEP && it.type != SleepSegment.LIGHT && it.type != SleepSegment.REM }.sumOf { it.minutes },
            remMin = sum(SleepSegment.REM),
            wakeUps = if (bands.isEmpty()) null else bands.sumOf { it.wakeUps },
            avgHeart = if (withHr.isEmpty()) null else Math.round(
                withHr.sumOf { it.avgHeart!!.toDouble() * ChronoUnit.MINUTES.between(it.start, it.end) } /
                    withHr.sumOf { ChronoUnit.MINUTES.between(it.start, it.end).toDouble() }.coerceAtLeast(1.0)
            ).toInt(),
        )
    }

    override fun sleepSource(day: UteDate): String? =
        chosen(day).map { it.source.name }.distinct().joinToString("+").ifEmpty { null }
}
