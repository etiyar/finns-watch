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
        val real = cmd(B8Calls.missedCall("Arayan", "20250101T093000", id = 42)).body(9)!!.message(3)!!.message(1)!!
        assertEquals(42, real.int(7))
        assertEquals("missed:42", real.string(12))
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

    private fun alarmEntry(id: Int, h: Int, m: Int, repeat: Int, enabled: Boolean, smart: Int, days: Int? = null): PbWriter {
        val d = PbWriter().message(2, PbWriter().uint(1, h).uint(2, m)).uint(3, repeat)
        if (days != null) d.uint(4, days)
        d.bool(5, enabled).uint(7, smart)
        return PbWriter().uint(1, id).message(2, d)
    }

    private fun listReply(vararg entries: PbWriter): B8Command {
        val alarms = PbWriter()
        for (e in entries) alarms.message(1, e)
        alarms.uint(2, 10)
        return cmd(B8Messages.command(17, 0).message(19, PbWriter().message(1, alarms)).toByteArray())
    }

    @Test
    fun alarmListParsesDetails() {
        val l = B8Alarms.parseList(listReply(alarmEntry(2, 6, 45, 5, true, 2, days = 0x1F), alarmEntry(5, 7, 1, 0, false, 2)))!!
        assertEquals(listOf(B8Alarm(2, 6, 45, 5, true, 2, 0x1F), B8Alarm(5, 7, 1, 0, false, 2)), l.alarms)
        assertEquals(listOf(2, 5), l.ids)
    }

    /** Band sıfır değerli alanları yazmayabilir (bir kez = 0, dakika 0). */
    private fun sparseEntry(id: Int, h: Int, m: Int, enabled: Boolean): PbWriter {
        val t = PbWriter()
        if (h != 0) t.uint(1, h)
        if (m != 0) t.uint(2, m)
        val d = PbWriter().message(2, t)
        if (enabled) d.bool(5, true)
        return PbWriter().uint(1, id).message(2, d)
    }

    @Test
    fun missingZeroFieldsReadAsDefaults() {
        val l = B8Alarms.parseList(listReply(sparseEntry(3, 17, 0, false), sparseEntry(4, 0, 5, true)))!!
        assertEquals(B8Alarm(3, 17, 0, 0, false, 0), l.alarms[0])
        assertEquals(B8Alarm(4, 0, 5, 0, true, 0), l.alarms[1])
        assertEquals(listOf(3), B8Alarms.probeCandidates(l).filter { !it.enabled }.map { it.id }, "yazılmayan tekrar (0) ve akıllı alanıyla da aday")
    }

    @Test
    fun smartAlarmIsNeverProbe() {
        val l = B8Alarms.parseList(listReply(alarmEntry(1, 6, 0, 0, true, 1)))!!
        assertTrue(B8Alarms.probeCandidates(l).isEmpty())
    }

    @Test
    fun probeSignatureIsOnceNormalWithoutDays() {
        val l = B8Alarms.parseList(listReply(
            alarmEntry(1, 6, 45, 5, true, 2, days = 0x1F), // haftalık, kullanıcının
            alarmEntry(2, 8, 0, 1, true, 2),               // her gün
            alarmEntry(3, 9, 0, 0, true, 1),               // bir kez ama akıllı
            alarmEntry(4, 6, 33, 0, false, 2),             // deneme imzası, çalmış
            alarmEntry(6, 6, 34, 0, true, 2),              // deneme imzası
        ))!!
        assertEquals(listOf(4, 6), B8Alarms.probeCandidates(l).map { it.id })
    }

    @Test
    fun userAlarmRepeatFromDays() {
        val once = cmd(B8Alarms.create(B8AlarmSpec(6, 30, 0, true))).body(19)!!.message(2)!!
        assertEquals(0, once.int(3))
        assertNull(once.int(4))
        val daily = cmd(B8Alarms.create(B8AlarmSpec(6, 30, 0x7F, true))).body(19)!!.message(2)!!
        assertEquals(1, daily.int(3))
        assertNull(daily.int(4))
        val weekdays = cmd(B8Alarms.edit(3, B8AlarmSpec(7, 0, 0x1F, false))).body(19)!!.message(3)!!
        assertEquals(3, weekdays.int(1))
        val d = weekdays.message(2)!!
        assertEquals(5, d.int(3))
        assertEquals(0x1F, d.int(4))
        assertEquals(false, d.bool(5))
        assertEquals(2, d.int(7), "normal alarm")
    }

    @Test
    fun alarmDaysFromListEntry() {
        assertEquals(0, B8Alarms.days(B8Alarm(1, 6, 0, 0, true, 2)))
        assertEquals(0x7F, B8Alarms.days(B8Alarm(1, 6, 0, 1, true, 2)))
        assertEquals(0x1F, B8Alarms.days(B8Alarm(1, 6, 0, 5, true, 2, 0x1F)))
    }

    @Test
    fun deleteListsIds() {
        val c = cmd(B8Alarms.delete(listOf(4)))
        assertTrue(c.matches(17, 4))
        assertEquals(listOf(4L), c.body(19)!!.message(5)!!.all(1).map { it.value })
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
    fun standingWindowChangesOnlyEnabledStartEnd() {
        val raw = B8Standing.parse(standingReply())!!
        val c = cmd(B8Standing.writeWindow(raw, true, 8, 30, 20, 15)!!)
        assertTrue(c.matches(8, 13))
        val m = c.body(10)!!.message(17)!!
        assertEquals(true, m.bool(1))
        assertEquals(8, m.message(2)!!.int(1))
        assertEquals(30, m.message(2)!!.int(2))
        assertEquals(20, m.message(3)!!.int(1))
        assertEquals(15, m.message(3)!!.int(2))
        assertEquals(true, m.bool(4), "öğle arası korunur")
        assertEquals(12, m.message(5)!!.int(1))
        assertEquals(1, m.all(2).size)
    }

    @Test
    fun standingWindowReadsBack() {
        val w = B8Standing.window(B8Standing.parse(standingReply())!!)!!
        assertEquals(B8Standing.Window(false, 9, 0, 21, 0), w)
    }

    @Test
    fun standingParseRejectsAmbiguousReply() {
        val two = cmd(B8Messages.command(8, 12).message(10, PbWriter().message(17, PbWriter().bool(1, true)).message(18, PbWriter().bool(1, true))).toByteArray())
        assertNull(B8Standing.parse(two))
        assertNull(B8Standing.parse(cmd(B8Messages.command(8, 12).toByteArray())))
    }

    // ---------- Otomatik nabız ölçümü ----------

    private fun hrReply(disabled: Boolean = true, interval: Int? = 10): B8Command {
        val inner = PbWriter().bool(1, disabled)
        interval?.let { inner.uint(2, it) }
        inner.bool(3, true).uint(4, 120).message(8, PbWriter().bool(1, false).uint(2, 50))
        return cmd(B8Messages.command(8, 10).message(10, PbWriter().message(8, inner)).toByteArray())
    }

    @Test
    fun heartRateConfigReadIsBare() {
        assertContentEquals(hex("0808100a"), B8HeartRateConfig.read())
    }

    @Test
    fun heartRateConfigParsesField8AndDescribesWithoutThresholds() {
        val raw = B8HeartRateConfig.parse(hrReply())!!
        assertEquals(8, raw.field)
        assertEquals(B8HeartRateConfig.State(true, 10), B8HeartRateConfig.state(raw))
        val d = B8HeartRateConfig.describe(raw)
        assertEquals("Health alanı 8, alanlar 1v 2v 3v 4v 8m, kapalı mı=true, aralık=10", d)
        assertFalse("120" in d, "uyarı eşiği loga yazılmaz")
    }

    @Test
    fun heartRateConfigWriteChangesOnlyDisabledAndInterval() {
        val raw = B8HeartRateConfig.parse(hrReply())!!
        val on = cmd(B8HeartRateConfig.write(raw, B8HeartRateConfig.Mode.CONTINUOUS)!!)
        assertTrue(on.matches(8, 11))
        val m = on.body(10)!!.message(8)!!
        assertEquals(false, m.bool(1))
        assertEquals(1, m.int(2))
        assertEquals(true, m.bool(3), "yüksek nabız uyarısı korunur")
        assertEquals(120, m.int(4))
        assertEquals(50, m.message(8)!!.int(2))
        assertEquals(1, m.all(1).size)
        assertEquals(1, m.all(2).size)
        assertEquals(10, cmd(B8HeartRateConfig.write(raw, B8HeartRateConfig.Mode.EVERY_10)!!).body(10)!!.message(8)!!.int(2))
    }

    @Test
    fun heartRateConfigOffKeepsInterval() {
        val raw = B8HeartRateConfig.parse(hrReply(disabled = false, interval = 30))!!
        val m = cmd(B8HeartRateConfig.write(raw, B8HeartRateConfig.Mode.OFF)!!).body(10)!!.message(8)!!
        assertEquals(true, m.bool(1))
        assertEquals(30, m.int(2))
        val noInterval = B8HeartRateConfig.parse(hrReply(interval = null))!!
        assertNull(cmd(B8HeartRateConfig.write(noInterval, B8HeartRateConfig.Mode.OFF)!!).body(10)!!.message(8)!!.int(2))
    }

    @Test
    fun heartRateConfigParseFallsBackToSingleMessageAndRejectsOthers() {
        val other = cmd(B8Messages.command(8, 10).message(10, PbWriter().message(9, PbWriter().bool(1, false))).toByteArray())
        assertEquals(9, B8HeartRateConfig.parse(other)!!.field)
        val two = cmd(B8Messages.command(8, 10).message(10, PbWriter().message(9, PbWriter().bool(1, true)).message(11, PbWriter().bool(1, true))).toByteArray())
        assertNull(B8HeartRateConfig.parse(two))
        assertNull(B8HeartRateConfig.parse(cmd(B8Messages.command(8, 12).toByteArray())))
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
