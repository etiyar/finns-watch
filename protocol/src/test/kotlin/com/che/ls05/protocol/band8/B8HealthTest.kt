package com.che.ls05.protocol.band8

import com.che.ls05.protocol.Band8DayFile
import com.che.ls05.protocol.Band8Health
import com.che.ls05.protocol.SleepStage
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.che.ls05.protocol.band8.B8SleepFixture as F

/** Band 8 dosyalarından ortak sağlık modeli ve gün dosyası. Bütün değerler uydurmadır. */
class B8HealthTest {
    private val zone = ZoneId.of("UTC") // dosyanın saat dilimi baytı kullanılır; yedek bölge etkisiz
    private val tz = 12 // UTC+3
    // Yerel 2025-01-02 00:00 (UTC+3) = 2025-01-01T21:00Z
    private val midnight = 1_735_765_200L

    private fun detail(dayStartUtc: Long, minutes: Int, steps: (Int) -> Int, hr: (Int) -> Int = { 0 }): B8ActivityFile {
        val id = B8FileId(dayStartUtc, tz, 3, 0, 0, 0)
        val body = java.io.ByteArrayOutputStream()
        body.write(hex("9accc88cc8"))
        repeat(minutes) { m ->
            val r = ByteArray(12)
            val s = steps(m)
            r[0] = (s and 0xFF).toByte(); r[1] = ((s ushr 8) and 0x3F).toByte()
            r[6] = hr(m).toByte()
            body.write(r)
        }
        return B8ActivityFile(id, B8Activity.buildFile(id, body.toByteArray()))
    }

    private fun summary(atUtc: Long, steps: Long): B8ActivityFile {
        val id = B8FileId(atUtc, tz, 5, 0, 0, 1)
        val body = ByteArray(4 + 57)
        for (i in 0 until 4) body[i] = 0xff.toByte()
        for (i in 0 until 4) body[4 + i] = ((steps ushr (8 * i)) and 0xFF).toByte()
        return B8ActivityFile(id, B8Activity.buildFile(id, body))
    }

    private fun local(h: Int, m: Int, day: Int = 2) = LocalDateTime.of(2025, 1, day, h, m)

    @Test
    fun minuteStepsBecomeLocalHourTotals() {
        // 08:00-08:59 her dakika 2 adım, 09:00-09:09 her dakika 5 adım.
        val f = detail(midnight, 550, { m -> if (m in 480..539) 2 else if (m >= 540) 5 else 0 })
        val h = Band8Health.fromFiles(listOf(f), zone)
        val d = LocalDate.of(2025, 1, 2)
        assertEquals(120, h.stepHours[d to 8])
        assertEquals(50, h.stepHours[d to 9])
        assertNull(h.stepHours[d to 7], "adımsız saat yazılmaz")
    }

    @Test
    fun heartPerMinuteFromDetailUsesFileTimezone() {
        val f = detail(midnight, 61, { 0 }, { m -> if (m == 60) 71 else 0 })
        val h = Band8Health.fromFiles(listOf(f), zone)
        assertEquals(mapOf(local(1, 0) to 71), h.heart)
    }

    @Test
    fun latestSummaryPerLocalDay() {
        val h = Band8Health.fromFiles(listOf(summary(midnight + 3600, 100), summary(midnight + 7200, 250), summary(midnight + 86_400 + 60, 7)), zone)
        assertEquals(250, h.stepDays[LocalDate.of(2025, 1, 2)])
        assertEquals(7, h.stepDays[LocalDate.of(2025, 1, 3)])
    }

    private val sleepStart = midnight - 2 * 3600L // yerel 22:00
    private fun sleepFile(endOffsetMin: Int = 420, hr: IntArray = intArrayOf(0, 58, 60)) = F.file(
        sleepStart, sleepStart + endOffsetMin * 60L, tz, hr,
        listOf(F.summaryRecord(sleepStart, 400, 20, 200, 100, 100), F.stageRecord(sleepStart, listOf(0x10 to 200, 0x00 to 20, 0x20 to 100, 0x30 to 100))),
    )

    @Test
    fun sleepSessionInLocalTimeWithParts() {
        val h = Band8Health.fromFiles(listOf(sleepFile()), zone)
        val s = h.sleep.single()
        assertEquals(LocalDateTime.of(2025, 1, 1, 22, 0), s.start)
        assertEquals(local(5, 0), s.end)
        assertEquals(listOf(SleepStage.LIGHT, SleepStage.AWAKE, SleepStage.DEEP, SleepStage.REM), s.parts.map { it.stage })
        assertEquals(LocalDateTime.of(2025, 1, 1, 22, 0), s.parts[0].start)
        assertEquals(LocalDateTime.of(2025, 1, 2, 1, 20), s.parts[1].start)
        assertEquals(1, s.wakeUps)
        assertEquals(59, s.avgHeart)
        assertEquals(100, s.remMin)
        assertEquals(LocalDate.of(2025, 1, 2), s.wakeDay)
    }

    @Test
    fun sleepHeartFillsMinutesWithoutDetailValue() {
        val h = Band8Health.fromFiles(listOf(sleepFile()), zone)
        assertEquals(58, h.heart[LocalDateTime.of(2025, 1, 1, 22, 1)])
        assertEquals(60, h.heart[LocalDateTime.of(2025, 1, 1, 22, 2)])
        assertNull(h.heart[LocalDateTime.of(2025, 1, 1, 22, 0)], "0 ölçüm yok")
    }

    @Test
    fun sameSessionInTwoFilesKeepsLatestFile() {
        val older = sleepFile(endOffsetMin = 400)
        val newer = sleepFile(endOffsetMin = 420)
        val h = Band8Health.fromFiles(listOf(newer, older), zone)
        assertEquals(1, h.sleep.size)
        assertEquals(local(5, 0), h.sleep.single().end)
    }

    @Test
    fun dayFileRoundTripAndIsDeterministic() {
        val files = listOf(
            detail(midnight, 600, { m -> if (m % 7 == 0) 3 else 0 }, { m -> if (m % 30 == 0) 65 else 0 }),
            summary(midnight + 36_000, 999), sleepFile(),
        )
        val h = Band8Health.fromFiles(files, zone)
        val days = Band8DayFile.days(h)
        assertTrue(LocalDate.of(2025, 1, 2) in days)
        val back = Band8Health.combine(days.map { d -> Band8DayFile.read(d, Band8DayFile.write(d, h)) })
        assertEquals(h.stepHours, back.stepHours)
        assertEquals(h.stepDays, back.stepDays)
        assertEquals(h.heart, back.heart)
        assertEquals(h.sleep, back.sleep)
        val again = Band8Health.fromFiles(files.reversed(), zone)
        for (d in days) assertEquals(Band8DayFile.write(d, h), Band8DayFile.write(d, again), "aynı arşiv aynı metni üretir")
    }

    @Test
    fun corruptLinesAreSkipped() {
        val d = LocalDate.of(2025, 1, 2)
        val back = Band8DayFile.read(d, "${Band8DayFile.HEADER}\nS\t8\tx\nS\t9\t40\nH\t2561\t70\nZ\t1\n")
        assertEquals(mapOf((d to 9) to 40), back.stepHours)
        assertTrue(back.heart.isEmpty())
    }
}
