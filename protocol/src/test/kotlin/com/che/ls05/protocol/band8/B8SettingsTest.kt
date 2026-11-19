package com.che.ls05.protocol.band8

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Elle deneme komutları (Gelişmiş > Band 8 denemeleri). Yapılar belge 6.6, 7.10, 8.1, 8.3, 8.5'e göre.
 * Bütün değerler sentetik.
 */
class B8SettingsTest {
    private fun cmd(b: ByteArray) = B8Command.parse(b)!!

    // ---------- Arama ----------

    @Test
    fun missedCallIsPlainNotificationFromPhone() {
        val c = cmd(B8Calls.missedCall("Test arama", "20250101T093000"))
        assertTrue(c.matches(7, 0))
        val n = c.body(9)!!.message(3)!!.message(1)!!
        assertEquals("phone", n.string(1))
        assertEquals("Test arama", n.string(3))
        assertEquals("Cevapsız arama", n.string(5))
        assertNull(n.bool(8), "arama mı alanı yazılmaz")
        assertEquals(B8Calls.MISSED_CALL_ID, n.int(7))
    }

    // ---------- Alarm ----------

    @Test
    fun alarmListRequestIsBare() {
        assertContentEquals(hex("08111000"), B8Alarms.list())
    }

    @Test
    fun alarmListParsesIdsAndMax() {
        val details = PbWriter().message(2, PbWriter().uint(1, 6).uint(2, 30)).uint(3, 1).bool(5, true)
        val alarms = PbWriter()
            .message(1, PbWriter().uint(1, 1).message(2, details))
            .message(1, PbWriter().uint(1, 3).message(2, details))
            .uint(2, 10)
        val c = cmd(B8Messages.command(17, 0).message(19, PbWriter().message(1, alarms)).toByteArray())
        val l = B8Alarms.parseList(c)!!
        assertEquals(listOf(1, 3), l.ids)
        assertEquals(10, l.max)
    }

    @Test
    fun emptyAlarmList() {
        val c = cmd(B8Messages.command(17, 0).message(19, PbWriter().message(1, PbWriter().uint(2, 10))).toByteArray())
        val l = B8Alarms.parseList(c)!!
        assertEquals(emptyList<Int>(), l.ids)
        assertEquals(10, l.max)
    }

    @Test
    fun createRingingOneShotAlarm() {
        val c = cmd(B8Alarms.create(7, 45))
        assertTrue(c.matches(17, 1))
        val d = c.body(19)!!.message(2)!!
        assertEquals(7, d.message(2)!!.int(1))
        assertEquals(45, d.message(2)!!.int(2))
        assertEquals(0, d.int(3), "bir kez")
        assertEquals(true, d.bool(5), "açık")
        assertEquals(2, d.int(7), "normal alarm")
        assertNull(d.int(4), "gün bayrağı yok")
    }

    @Test
    fun editKeepsIdAndDetails() {
        val c = cmd(B8Alarms.edit(4, 8, 5))
        assertTrue(c.matches(17, 2))
        val a = c.body(19)!!.message(3)!!
        assertEquals(4, a.int(1))
        assertEquals(8, a.message(2)!!.message(2)!!.int(1))
        assertEquals(5, a.message(2)!!.message(2)!!.int(2))
        assertEquals(true, a.message(2)!!.bool(5))
    }

    @Test
    fun deleteListsIds() {
        val c = cmd(B8Alarms.delete(listOf(4)))
        assertTrue(c.matches(17, 4))
        assertEquals(listOf(4L), c.body(19)!!.message(5)!!.all(1).map { it.value })
    }

    @Test
    fun newAlarmIdIsTheOneMissingBefore() {
        assertEquals(5, B8Alarms.newId(listOf(1, 3), listOf(1, 3, 5)))
        assertNull(B8Alarms.newId(listOf(1, 3), listOf(1, 3)))
        assertNull(B8Alarms.newId(listOf(1), listOf(1, 2, 3)), "belirsizse tahmin yok")
    }

    @Test
    fun testTimeWrapsAtMidnight() {
        assertEquals(10 to 32, B8Alarms.plusMinutes(10, 30, 2))
        assertEquals(0 to 1, B8Alarms.plusMinutes(23, 59, 2))
    }

    // ---------- Rahatsız etme ----------

    @Test
    fun dndNowOnAndOff() {
        val on = cmd(B8Dnd.now(true))
        assertTrue(on.matches(2, 23))
        assertEquals(0, on.body(4)!!.message(11)!!.int(1))
        assertEquals(2, cmd(B8Dnd.now(false)).body(4)!!.message(11)!!.int(1))
    }

    @Test
    fun sleepScheduleNightRange() {
        val c = cmd(B8Dnd.sleepSchedule(true, 22, 0, 7, 0))
        assertTrue(c.matches(17, 9))
        val m = c.body(19)!!.message(9)!!
        assertEquals(true, m.bool(1))
        val r = m.message(2)!!
        assertEquals(22, r.message(1)!!.int(1))
        assertEquals(0, r.message(1)!!.int(2))
        assertEquals(7, r.message(2)!!.int(1))
        assertEquals(0, r.int(3))
        assertEquals(false, cmd(B8Dnd.sleepSchedule(false, 22, 0, 7, 0)).body(19)!!.message(9)!!.bool(1))
    }

    // ---------- Hareketsizlik ----------

    private fun standingReply(): B8Command {
        val hm = { h: Int, m: Int -> PbWriter().uint(1, h).uint(2, m) }
        val inner = PbWriter().bool(1, false).message(2, hm(9, 0)).message(3, hm(21, 0)).bool(4, true)
            .message(5, hm(12, 0)).message(6, hm(13, 0))
        return cmd(B8Messages.command(8, 12).message(10, PbWriter().message(17, inner)).toByteArray())
    }

    @Test
    fun standingReadIsBare() {
        assertContentEquals(hex("0808100c"), B8Standing.read())
    }

    @Test
    fun standingParseFindsSingleSettingMessage() {
        val raw = B8Standing.parse(standingReply())!!
        assertEquals(17, raw.field)
        assertEquals("1v 2m 3m 4v 5m 6m", raw.shape())
    }

    @Test
    fun standingWriteFlipsOnlyEnabled() {
        val raw = B8Standing.parse(standingReply())!!
        val c = cmd(B8Standing.write(raw, true)!!)
        assertTrue(c.matches(8, 13))
        val m = c.body(10)!!.message(17)!!
        assertEquals(true, m.bool(1))
        assertEquals(9, m.message(2)!!.int(1))
        assertEquals(21, m.message(3)!!.int(1))
        assertEquals(true, m.bool(4))
        assertEquals(13, m.message(6)!!.int(1))
        assertEquals(1, m.all(1).size)
        assertEquals(false, cmd(B8Standing.write(raw, false)!!).body(10)!!.message(17)!!.bool(1))
    }

    @Test
    fun standingParseRejectsAmbiguousReply() {
        val two = cmd(B8Messages.command(8, 12).message(10, PbWriter().message(17, PbWriter().bool(1, true)).message(18, PbWriter().bool(1, true))).toByteArray())
        assertNull(B8Standing.parse(two))
        assertNull(B8Standing.parse(cmd(B8Messages.command(8, 12).toByteArray())))
    }

    // ---------- Hava durumu ----------

    private val meta = B8WeatherMeta("2025-01-01T09:00:00+03:00", "Kent", "finnswatch")

    @Test
    fun currentWeatherShape() {
        val c = cmd(B8Weather.current(meta, xiaomiCode = 1, temperatureC = -3))
        assertTrue(c.matches(10, 0))
        val w = c.body(12)!!.message(1)!!
        val m = w.message(1)!!
        assertEquals("2025-01-01T09:00:00+03:00", m.string(1))
        assertEquals("Kent", m.string(2))
        assertEquals("finnswatch", m.string(4))
        assertEquals(1, w.int(2))
        assertEquals("℃", w.message(3)!!.string(1))
        assertEquals(-3, w.message(3)!!.sint(2))
    }

    @Test
    fun forecastHasOneEntryPerDay() {
        val days = listOf(B8ForecastDay(0, 0, -2, 5), B8ForecastDay(7, 8, 3, 9))
        val c = cmd(B8Weather.forecast(meta, days))
        assertTrue(c.matches(10, 1))
        val f = c.body(12)!!.message(2)!!
        assertEquals("finnswatch", f.message(1)!!.string(4))
        val entries = f.message(2)!!.messages(1)
        assertEquals(2, entries.size)
        assertEquals(7, entries[1].message(2)!!.sint(1))
        assertEquals(8, entries[1].message(2)!!.sint(2))
        assertEquals(-2, entries[0].message(3)!!.sint(1))
        assertEquals(5, entries[0].message(3)!!.sint(2))
        assertEquals("℃", entries[0].string(4))
    }

    @Test
    fun addLocation() {
        val c = cmd(B8Weather.addLocation("finnswatch", "Kent"))
        assertTrue(c.matches(10, 7))
        assertEquals("finnswatch", c.body(12)!!.message(5)!!.string(1))
        assertEquals("Kent", c.body(12)!!.message(5)!!.string(2))
    }

    @Test
    fun uteCodesMapToXiaomiCodes() {
        assertEquals(0, B8Weather.fromUte(com.che.ls05.protocol.UteWeather.SUNNY))
        assertEquals(1, B8Weather.fromUte(com.che.ls05.protocol.UteWeather.PARTLY_CLOUDY))
        assertEquals(2, B8Weather.fromUte(com.che.ls05.protocol.UteWeather.CLOUDY))
        assertEquals(4, B8Weather.fromUte(com.che.ls05.protocol.UteWeather.THUNDER))
        assertEquals(14, B8Weather.fromUte(com.che.ls05.protocol.UteWeather.SNOW))
        assertEquals(2, B8Weather.fromUte(-1), "bilinmeyen kod kapalı sayılır")
    }

    // ---------- Müzik ----------

    @Test
    fun musicInfoPlayingTestTrack() {
        val c = cmd(B8Music.info("Test şarkı", "Finn's Watch", playing = true, durationSec = 180, volumePercent = 50))
        assertTrue(c.matches(18, 1))
        val i = c.body(20)!!.message(1)!!
        assertEquals(1, i.int(1))
        assertEquals(50, i.int(2))
        assertEquals("Test şarkı", i.string(4))
        assertEquals("Finn's Watch", i.string(5))
        assertEquals(180, i.int(7))
        assertEquals(2, cmd(B8Music.info("T", "A", playing = false, durationSec = 1, volumePercent = 0)).body(20)!!.message(1)!!.int(1))
    }

    @Test
    fun shapeNeverShowsValues() {
        val raw = B8RawSetting(17, PbWriter().uint(1, 123456).string(2, "gizli").toByteArray())
        assertEquals("1v 2m", raw.shape())
        assertFalse(raw.shape().contains("123456"))
        assertNotNull(raw)
    }
}
