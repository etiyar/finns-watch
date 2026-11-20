package com.che.ls05.protocol

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Özel hatırlatıcı ve takvim zamanlaması. Başlıklar uydurmadır. */
class CustomRemindersTest {
    private val zone = ZoneId.of("Europe/Istanbul")
    private fun ms(t: LocalDateTime) = t.atZone(zone).toInstant().toEpochMilli()
    private fun at(d: Int, h: Int, m: Int) = LocalDateTime.of(2025, 1, d, h, m) // 2025-01-06 Pazartesi
    private fun r(repeat: ReminderRepeat, days: Int = 0, every: Int = 1, start: Int = 6, h: Int = 9, m: Int = 0) =
        CustomReminder(1, "İlaç", LocalDate.of(2025, 1, start), h, m, repeat, days, every, true)

    @Test
    fun onceFiresOnlyOnItsDate() {
        val x = r(ReminderRepeat.ONCE)
        assertEquals(ms(at(6, 9, 0)), CustomReminders.next(x, ms(at(1, 0, 0)), zone))
        assertNull(CustomReminders.next(x, ms(at(6, 9, 0)), zone), "geçtikten sonra yok")
    }

    @Test
    fun dailyStartsFromStartDate() {
        val x = r(ReminderRepeat.DAILY, start = 8)
        assertEquals(ms(at(8, 9, 0)), CustomReminders.next(x, ms(at(6, 10, 0)), zone))
        assertEquals(ms(at(9, 9, 0)), CustomReminders.next(x, ms(at(8, 9, 0)), zone))
    }

    @Test
    fun weekdaysSkipWeekend() {
        val x = r(ReminderRepeat.WEEKDAYS)
        assertEquals(ms(at(13, 9, 0)), CustomReminders.next(x, ms(at(10, 9, 0)), zone), "cuma sonrası pazartesi")
    }

    @Test
    fun selectedDays() {
        val x = r(ReminderRepeat.DAYS, days = (1 shl 2) or (1 shl 6)) // çarşamba ve pazar
        assertEquals(ms(at(8, 9, 0)), CustomReminders.next(x, ms(at(6, 0, 0)), zone))
        assertEquals(ms(at(12, 9, 0)), CustomReminders.next(x, ms(at(8, 9, 0)), zone))
        assertNull(CustomReminders.next(r(ReminderRepeat.DAYS, days = 0), 0, zone), "gün yoksa yok")
    }

    @Test
    fun everyNDaysCountsFromStart() {
        val x = r(ReminderRepeat.EVERY_N, every = 3)
        assertEquals(ms(at(9, 9, 0)), CustomReminders.next(x, ms(at(6, 9, 0)), zone))
        assertEquals(ms(at(12, 9, 0)), CustomReminders.next(x, ms(at(10, 0, 0)), zone))
    }

    @Test
    fun disabledNeverFires() {
        assertNull(CustomReminders.next(r(ReminderRepeat.DAILY).copy(enabled = false), 0, zone))
    }

    @Test
    fun stillValidOnlyForSameMoment() {
        val x = r(ReminderRepeat.DAILY)
        assertTrue(CustomReminders.stillValid(x, ms(at(7, 9, 0)), zone))
        assertTrue(!CustomReminders.stillValid(x.copy(hour = 10), ms(at(7, 9, 0)), zone))
    }

    @Test
    fun listRoundTripsWithEscapingAndSkipsBadLines() {
        val list = listOf(
            r(ReminderRepeat.DAYS, days = 5).copy(id = 2, title = "Ç\tok\nsatır"),
            r(ReminderRepeat.EVERY_N, every = 4).copy(id = 3, enabled = false),
        )
        assertEquals(list, CustomReminders.decode(CustomReminders.encode(list)))
        assertEquals(list, CustomReminders.decode(CustomReminders.encode(list) + "C\tbozuk\n"))
        assertEquals(emptyList<CustomReminder>(), CustomReminders.decode(null))
    }

    @Test
    fun titleIsCleanedAndLimited() {
        assertEquals("a b", CustomReminders.cleanTitle("  a\n b "))
        assertEquals(CustomReminders.MAX_TITLE, CustomReminders.cleanTitle("x".repeat(200)).length)
    }

    // ---------- Takvim ----------

    private fun inst(id: Long, begin: LocalDateTime, mins: List<Int> = emptyList(), declined: Boolean = false, allDay: Boolean = false) =
        CalendarPlan.Instance(id, ms(begin), allDay, declined, mins)

    @Test
    fun calendarUsesEventReminderOrDefaultTen() {
        val now = ms(at(6, 8, 0))
        val p = CalendarPlan.plan(listOf(inst(1, at(6, 10, 0), listOf(30)), inst(2, at(6, 11, 0))), now, 24 * 3_600_000L)
        assertEquals(listOf(ms(at(6, 9, 30)), ms(at(6, 10, 50))), p.map { it.at })
        assertEquals(listOf(1L, 2L), p.map { it.eventId })
    }

    @Test
    fun calendarSkipsDeclinedPastAndBeyondHorizon() {
        val now = ms(at(6, 8, 0))
        val p = CalendarPlan.plan(
            listOf(
                inst(1, at(6, 10, 0), declined = true),
                inst(2, at(6, 8, 5)),                  // hatırlatma anı geçti
                inst(3, at(8, 10, 0)),                 // ufuk dışında
                inst(4, at(6, 12, 0), listOf(15, 15, 60)),
            ),
            now, 24 * 3_600_000L,
        )
        assertEquals(listOf(ms(at(6, 11, 0)), ms(at(6, 11, 45))), p.map { it.at }, "aynı dakika bir kez")
        assertTrue(p.all { it.key.startsWith("4:") })
    }

    @Test
    fun allDayEventRemindsAtNineDayBeforeWhenNoMinutes() {
        val now = ms(at(6, 0, 0))
        // Tüm gün etkinliği: başlangıç UTC gece yarısı; varsayılan 10 dk yerine bir önceki gün değil, aynı gün 09:00.
        val begin = LocalDate.of(2025, 1, 7).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        val p = CalendarPlan.plan(listOf(CalendarPlan.Instance(9, begin, true, false, emptyList())), now, 48 * 3_600_000L, zone)
        assertEquals(listOf(ms(at(7, 9, 0))), p.map { it.at })
    }
}
