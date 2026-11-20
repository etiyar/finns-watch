package com.che.ls05.protocol

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Birleşik sağlık görünümü (docs/COKLU_SAAT_TASARIM.md 5.4). Değerler uydurmadır. */
class HealthMergeTest {
    private fun e(hex: String) = HealthEntry(0, 180, Hex.parse(hex))
    private fun step(d: Int, hour: Int, total: Int) =
        "0A 07 EA 0B %02X %02X %02X %02X 00 00 00 00 00 00 00 00 %02X %02X".format(d, hour, total shr 8, total and 0xFF, total shr 8, total and 0xFF)
    private fun live(d: Int, h: Int, m: Int, bpm: Int) = "18 03 07 EA 0B %02X %02X %02X %02X".format(d, h, m, bpm)

    private val ls = HealthSource("ls", "Saat LS05", SourceKind.LS05)
    private val b8 = HealthSource("b8", "Band 8", SourceKind.BAND8)
    private fun day(d: Int) = UteDate(2026, 11, d)
    private fun ld(d: Int) = LocalDate.of(2026, 11, d)
    private fun at(d: Int, h: Int, m: Int) = LocalDateTime.of(2026, 11, d, h, m)

    /** LS05 ham kayıtları: adım, canlı ve blok nabız, gece yarısını aşan uyku, nabız özeti. */
    private val ls05Entries = listOf(
        e(step(12, 6, 50)), e(step(12, 7, 300)), e(step(12, 7, 320)), e(step(13, 9, 120)),
        e(live(12, 7, 20, 72)), e(live(12, 7, 20, 74)), e(live(13, 9, 5, 80)),
        e("18 07 EA 0B 0D 08 FF FF FF FF FF 46 FF FF FF FF FF 4B"),
        e("1D 01 07 EA 0B 0D 04"), e("1E 17 1E 02 01 00 3C 00 1E 01 01 00 5A 02 00 02 01 00 64 03 28 03 01 00 0A"),
    )

    private fun merged(view: HealthView, b: Band8Health? = null, active: (LocalDateTime) -> String? = { null }) =
        MergedHealth(listOf(ls to view), if (b == null) emptyList() else listOf(b8 to b), active, activeId = "ls")

    @Test
    fun ls05OnlyIsIdenticalToHealthView() {
        val v = HealthView(ls05Entries)
        val m = merged(v)
        for (d in 10..15) {
            assertEquals(v.stepsForDay(day(d)), m.stepsForDay(day(d)), "adım $d")
            assertContentEquals(v.hourlySteps(day(d)).toList(), m.hourlySteps(day(d)).toList(), "saatlik $d")
            assertEquals(v.sleepSegmentsFor(day(d)), m.sleepSegmentsFor(day(d)), "uyku şeridi $d")
            assertEquals(v.sleepFor(day(d)), m.sleepFor(day(d)), "uyku $d")
        }
        assertEquals(v.heartPoints(), m.heartPoints())
        assertEquals(v.lastHeart(), m.lastHeart())
        assertEquals(v.lastHeart(notAfter = at(12, 8, 0)), m.lastHeart(notAfter = at(12, 8, 0)))
        assertEquals(v.heartSummary, m.heartSummary)
        assertNull(m.sleepFor(day(13))?.wakeUps, "LS05 uykusunda Band 8 alanları boş")
    }

    private fun band(
        hours: Map<Pair<LocalDate, Int>, Int> = emptyMap(),
        days: Map<LocalDate, Int> = emptyMap(),
        heart: Map<LocalDateTime, Int> = emptyMap(),
        sleep: List<Band8SleepSession> = emptyList(),
    ) = Band8Health(hours, days, heart, sleep)

    @Test
    fun stepsHourlyMaxNeverSummed() {
        val m = merged(HealthView(ls05Entries), band(hours = mapOf((ld(12) to 7) to 250, (ld(12) to 9) to 40)))
        val h = m.hourlySteps(day(12))
        assertEquals(50, h[6])
        assertEquals(320, h[7], "büyük olan, toplam değil")
        assertEquals(40, h[9])
        assertEquals(410, m.stepsForDay(day(12)))
        assertEquals("Saat LS05", m.stepSource(day(12), 7))
        assertEquals("Band 8", m.stepSource(day(12), 9))
    }

    @Test
    fun dayTotalIsMaxOfHourlySumAndBandSummary() {
        val m = merged(HealthView(emptyList()), band(hours = mapOf((ld(14) to 10) to 100), days = mapOf(ld(14) to 130, ld(15) to 20)))
        assertEquals(130, m.stepsForDay(day(14)))
        assertEquals(20, m.stepsForDay(day(15)), "yalnızca günlük toplam")
        assertEquals(0, m.hourlySteps(day(15)).sum(), "saatlik dağılım uydurulmaz")
        assertNull(m.stepsForDay(day(16)))
    }

    @Test
    fun heartSameMinuteUsesActiveWatchAndKeepsOthers() {
        val b = band(heart = mapOf(at(12, 7, 20) to 90, at(12, 7, 21) to 91))
        val activeBand = merged(HealthView(ls05Entries), b) { "b8" }
        val pts = activeBand.heartPoints()
        assertEquals(listOf(90), pts.filter { it.at == at(12, 7, 20) }.map { it.bpm })
        assertTrue(pts.any { it.at == at(12, 7, 21) && it.bpm == 91 })
        assertEquals("Band 8", activeBand.heartSource(at(12, 7, 20)))
        val unknown = merged(HealthView(ls05Entries), b) { null }
        assertEquals(listOf(72, 74), unknown.heartPoints().filter { it.at == at(12, 7, 20) }.map { it.bpm }, "etkin saat bilinmiyorsa LS05")
    }

    private fun bandNight(startH: Int, startD: Int, parts: List<Pair<SleepStage, Int>>, wake: Int = 1, avg: Int? = 58): Band8SleepSession {
        val start = at(startD, startH, 0)
        var t = start
        val ps = parts.map { (s, m) -> SleepPart(t, m, s).also { t = t.plusMinutes(m.toLong()) } }
        fun sum(s: SleepStage) = parts.filter { it.first == s }.sumOf { it.second }
        return Band8SleepSession(start, t, ps, wake, avg, sum(SleepStage.AWAKE), sum(SleepStage.LIGHT), sum(SleepStage.REM), sum(SleepStage.DEEP))
    }

    @Test
    fun moreDetailedBandNightWinsOverlappingLs05Night() {
        val night = bandNight(23, 12, listOf(SleepStage.LIGHT to 60, SleepStage.DEEP to 80, SleepStage.AWAKE to 10, SleepStage.REM to 50))
        val m = merged(HealthView(ls05Entries), band(sleep = listOf(night)))
        val s = m.sleepFor(day(13))!!
        assertEquals(50, s.remMin)
        assertEquals(80, s.deepMin)
        assertEquals(60, s.lightMin)
        assertEquals(10, s.awakeMin)
        assertEquals(1, s.wakeUps)
        assertEquals(58, s.avgHeart)
        assertEquals(190, s.sleepMin)
        assertEquals(at(12, 23, 0), s.start)
        val segs = m.sleepSegmentsFor(day(13))
        assertEquals(listOf(SleepSegment.LIGHT, SleepSegment.DEEP, SleepSegment.AWAKE, SleepSegment.REM), segs.map { it.type })
        assertEquals("Band 8", m.sleepSource(day(13)))
    }

    @Test
    fun equalDetailPrefersWholeThenLongerThenLs05() {
        // LS05 gecesi: hafif + derin, bölümler bitişik (bütün), 23:30-03:40.
        val v = HealthView(ls05Entries)
        val shorter = bandNight(0, 13, listOf(SleepStage.LIGHT to 60, SleepStage.DEEP to 60))
        assertEquals("Saat LS05", merged(v, band(sleep = listOf(shorter))).sleepSource(day(13)), "eşit ayrıntı, LS05 daha uzun")
        val longer = bandNight(23, 12, listOf(SleepStage.LIGHT to 200, SleepStage.DEEP to 100))
        assertEquals("Band 8", merged(v, band(sleep = listOf(longer))).sleepSource(day(13)), "eşit ayrıntı, Band 8 daha uzun")
    }

    @Test
    fun nonOverlappingSessionsAreBothKept() {
        // LS05 gecesi 23:30-03:40; Band 8 öğlen uykusu 13:00-13:40 aynı uyanış gününde.
        val nap = bandNight(13, 13, listOf(SleepStage.LIGHT to 40), wake = 0, avg = null)
        val m = merged(HealthView(ls05Entries), band(sleep = listOf(nap)))
        val s = m.sleepFor(day(13))!!
        assertEquals(250 + 40, s.sleepMin)
        assertEquals("Saat LS05+Band 8", m.sleepSource(day(13)))
    }

    @Test
    fun bandOnlyDayHasNoLs05Artifacts() {
        val night = bandNight(22, 14, listOf(SleepStage.LIGHT to 100, SleepStage.REM to 30))
        val m = merged(HealthView(emptyList()), band(sleep = listOf(night), heart = mapOf(at(15, 6, 0) to 60)))
        assertEquals(130, m.sleepFor(day(15))!!.sleepMin)
        assertEquals(HeartPoint(at(15, 6, 0), 60), m.lastHeart())
        assertNull(m.heartSummary)
        assertTrue(m.hasBand8Steps(day(15)).not())
    }
}
