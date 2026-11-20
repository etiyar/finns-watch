package com.che.ls05.protocol.band8

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.che.ls05.protocol.band8.B8SleepFixture as F

/** Uyku dosyası ayrıştırıcısı. Değerler uydurmadır. */
class B8SleepParserTest {
    private val start = 1_735_682_400L          // sentetik
    private val end = start + 420 * 60L         // 7 saat
    private val stages = listOf(0x10 to 30, 0x20 to 90, 0x00 to 5, 0x10 to 100, 0x30 to 60, 0x00 to 10, 0x10 to 85, 0x30 to 40)
    // hafif 215, derin 90, REM 100, uyanık 15, toplam uyku 405

    private fun parse(f: B8ActivityFile) = B8ActivityParsers.parse(f)

    private fun standard(hr: IntArray = IntArray(300) { if (it % 3 == 0) 0 else 60 + it % 7 }) = F.file(
        start, end, 12, hr,
        listOf(
            F.summaryRecord(start, 0, 0, 0, 0, 0),                     // geçici, boş
            F.otherRecord(11),
            F.summaryRecord(start, 405, 15, 215, 100, 90),
            F.stageRecord(start, stages),
            F.otherRecord(12),
        ),
    )

    @Test
    fun sleepSessionTimesAndTotals() {
        val s = (parse(standard()) as B8Parsed.Sleep).sleep
        assertEquals(start, s.startUtc)
        assertEquals(end, s.endUtc)
        assertEquals(12, s.tzQuarters)
        assertEquals(405, s.totalMin)
        assertEquals(15, s.awakeMin)
        assertEquals(215, s.lightMin)
        assertEquals(100, s.remMin)
        assertEquals(90, s.deepMin)
    }

    @Test
    fun stagesInOrderWithCodes() {
        val s = (parse(standard()) as B8Parsed.Sleep).sleep
        assertEquals(stages.map { (c, m) -> B8SleepRun(c ushr 4, m) }, s.runs)
        assertEquals(420, s.runs.sumOf { it.minutes })
        assertEquals(2, s.wakeUps)
    }

    @Test
    fun perMinuteHeartRateAndAverageSkipZero() {
        val hr = intArrayOf(0, 60, 62, 0, 64)
        val s = (parse(standard(hr)) as B8Parsed.Sleep).sleep
        assertEquals(start, s.hrStartUtc)
        assertEquals(hr.toList(), s.heartPerMinute.toList())
        assertEquals(62, s.averageHeart)
    }

    @Test
    fun noHeartValuesMeansNoAverage() {
        val s = (parse(standard(IntArray(4))) as B8Parsed.Sleep).sleep
        assertNull(s.averageHeart)
    }

    @Test
    fun lastNonEmptyMatchingRecordWins() {
        val f = F.file(start, end, 12, IntArray(0), listOf(
            F.summaryRecord(start, 400, 20, 200, 100, 100, tag = 0x0110, ver = 0x11),
            F.stageRecord(start - 3600, listOf(0x10 to 10)),           // önceki oturum anahtarı
            F.summaryRecord(start - 3600, 1, 0, 1, 0, 0),              // önceki oturum
            F.stageRecord(start, listOf(0x10 to 200, 0x20 to 220), tag = 0x0111),
            F.summaryRecord(start, 0, 0, 0, 0, 0),                     // boş, atlanır
        ))
        val s = (parse(f) as B8Parsed.Sleep).sleep
        assertEquals(400, s.totalMin)
        assertEquals(listOf(B8SleepRun(1, 200), B8SleepRun(2, 220)), s.runs)
        assertEquals(0, s.wakeUps)
    }

    @Test
    fun missingSummaryIsComputedFromStages() {
        val f = F.file(start, end, 12, IntArray(0), listOf(F.stageRecord(start, stages)))
        val s = (parse(f) as B8Parsed.Sleep).sleep
        assertEquals(405, s.totalMin)
        assertEquals(100, s.remMin)
        assertEquals(15, s.awakeMin)
    }

    @Test
    fun noSessionRecordsIsUnsupported() {
        val f = F.file(start, end, 12, IntArray(3), listOf(F.otherRecord(1)))
        assertTrue(parse(f) is B8Parsed.Unsupported)
    }

    @Test
    fun truncatedFileIsUnsupported() {
        val id = B8FileId(end, 12, 2, 0, 8, 0)
        val f = B8ActivityFile(id, B8Activity.buildFile(id, byteArrayOf(0xf0.toByte(), 0, 1, 2)))
        assertTrue(parse(f) is B8Parsed.Unsupported)
    }

    @Test
    fun unknownSleepVersionIsUnsupported() {
        val ok = standard()
        val id = ok.id.copy(version = 9)
        val f = B8ActivityFile(id, B8Activity.buildFile(id, ok.body()))
        assertTrue(parse(f) is B8Parsed.Unsupported)
    }
}
