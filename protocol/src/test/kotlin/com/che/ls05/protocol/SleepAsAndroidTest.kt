package com.che.ls05.protocol

import com.che.ls05.protocol.band8.B8Command
import com.che.ls05.protocol.band8.B8Messages
import com.che.ls05.protocol.band8.B8Realtime
import com.che.ls05.protocol.band8.B8SaaVibration
import com.che.ls05.protocol.band8.PbWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sleep as Android denetleyicisi ve canlı nabız komutları. Nabız değerleri uydurmadır. */
class SleepAsAndroidTest {
    private fun kinds(l: List<SaaOut>) = l.map { it::class.simpleName }

    private fun readyTracking(t: Long = 0L): SaaController {
        val c = SaaController()
        c.onBandReady(true, t)
        c.onStartTracking(t)
        return c
    }

    @Test
    fun realtimeCommandsAndParse() {
        val start = B8Command.parse(B8Realtime.start())!!
        assertTrue(start.matches(8, 45))
        assertTrue(B8Command.parse(B8Realtime.stop())!!.matches(8, 46))
        val ev = B8Command.parse(
            B8Messages.command(8, 47).message(10, PbWriter().message(39, PbWriter().uint(1, 1234).uint(4, 61))).toByteArray(),
        )!!
        assertEquals(B8Realtime.Stats(1234, 61), B8Realtime.parse(ev))
        assertNull(B8Realtime.parse(start))
        assertNull(B8Realtime.validHeartRate(0))
        assertNull(B8Realtime.validHeartRate(10))
        assertEquals(11, B8Realtime.validHeartRate(11))
        assertNull(B8Realtime.validHeartRate(255))
    }

    @Test
    fun hintPatternIsShortAndCapped() {
        assertEquals(1, B8SaaVibration.hint(1).count { it.first })
        assertEquals(5, B8SaaVibration.hint(99).count { it.first })
        assertTrue(B8SaaVibration.ALARM.sumOf { it.second } < SaaController.ALARM_REPEAT_MS)
    }

    @Test
    fun checkConnectedOnlyWhenBandReady() {
        val c = SaaController()
        assertTrue(c.onCheckConnected().isEmpty())
        c.onBandReady(true, 0)
        assertEquals(listOf("ConfirmConnected"), kinds(c.onCheckConnected()))
    }

    @Test
    fun startTrackingStartsStreamAndStopStops() {
        val c = SaaController()
        c.onBandReady(true, 0)
        assertEquals(listOf("ConfirmConnected", "HrStart"), kinds(c.onStartTracking(0)))
        assertTrue(c.streaming)
        assertEquals(listOf("HrStop"), kinds(c.onStopTracking(1_000)))
        assertFalse(c.streaming)
    }

    @Test
    fun noStreamWhenHeartRateSettingOff() {
        val c = SaaController(hrEnabled = false)
        c.onBandReady(true, 0)
        assertEquals(listOf("ConfirmConnected"), kinds(c.onStartTracking(0)))
        assertFalse(c.streaming)
        assertEquals(listOf("HrStart"), kinds(c.onSettings(true, 10)))
    }

    @Test
    fun batchesFlushAfterPeriodInArrivalOrder() {
        val c = readyTracking()
        assertEquals(120_000L, c.periodMs)
        assertTrue(c.onHeartRate(60, 10_000).second.isEmpty())
        c.onHeartRate(null, 20_000)
        c.onHeartRate(62, 30_000)
        val out = c.onHeartRate(64, 120_000).second
        val p = out.single() as SaaOut.SendHr
        assertEquals(listOf(60f, 62f, 64f), p.bpm.toList())
        assertEquals(4, c.events)
        assertEquals(3, c.valid)
        assertEquals(1, c.packets)
    }

    @Test
    fun batchSizeChangesPeriodWithinLimits() {
        val c = SaaController()
        c.onBatchSize(1)
        assertEquals(10_000L, c.periodMs)
        c.onBatchSize(1_000)
        assertEquals(300_000L, c.periodMs)
        c.onBatchSize(0)
        assertEquals(10_000L, c.periodMs)
    }

    @Test
    fun pauseStopsStreamAndResumes() {
        val c = readyTracking()
        c.onHeartRate(60, 5_000)
        val out = c.onPause(100_000, 10_000)
        assertEquals(listOf("SendHr", "HrStop"), kinds(out))
        assertTrue(c.onTick(50_000).none { it is SaaOut.HrStart })
        assertEquals(listOf("HrStart"), kinds(c.onTick(100_001)))
        assertEquals(listOf("HrStop"), kinds(c.onSuspended(true, 110_000)))
        assertEquals(listOf("HrStart"), kinds(c.onSuspended(false, 120_000)))
        c.onPause(500_000, 130_000)
        assertEquals(listOf("HrStart"), kinds(c.onPause(0, 140_000)))
    }

    @Test
    fun disconnectFlushesWithoutBandCommandAndReconnectRestarts() {
        val c = readyTracking()
        c.onHeartRate(70, 1_000)
        assertEquals(listOf("SendHr"), kinds(c.onBandReady(false, 2_000)))
        assertFalse(c.streaming)
        assertEquals(false, c.onHeartRate(70, 3_000).first)
        assertEquals(listOf("HrStart"), kinds(c.onBandReady(true, 4_000)))
    }

    @Test
    fun watchdogRestartsSilentStreamOncePerMinute() {
        val c = readyTracking()
        assertTrue(c.onTick(30_000).isEmpty())
        assertEquals(listOf("HrStart"), kinds(c.onTick(60_000)))
        assertTrue(c.onTick(70_000).none { it is SaaOut.HrStart })
        assertEquals(listOf("HrStart"), kinds(c.onTick(120_000)).filter { it == "HrStart" })
        assertEquals(2, c.restarts)
    }

    @Test
    fun unsolicitedEventsAreNotOurs() {
        val c = SaaController()
        c.onBandReady(true, 0)
        assertFalse(c.onHeartRate(60, 1_000).first)
        assertEquals(0, c.events)
    }

    @Test
    fun alarmShowsBuzzesAndStops() {
        val c = SaaController()
        c.onBandReady(true, 0)
        assertTrue(c.onStartAlarm(-1, 0).isEmpty())
        assertTrue(c.onStartAlarm(2_000, 0).isEmpty())
        assertEquals(2_000L, c.nextTickMs(0))
        assertEquals(listOf("AlarmShow"), kinds(c.onTick(2_000)))
        assertTrue(c.alarmActive)
        assertTrue(c.onTick(4_000).isEmpty())
        assertEquals(listOf("AlarmBuzz"), kinds(c.onTick(7_000)))
        assertEquals(listOf("AlarmHide"), kinds(c.onStopAlarm()))
        assertNull(c.nextTickMs(8_000))
    }

    @Test
    fun alarmStopsByItselfAfterLimit() {
        val c = SaaController()
        c.onBandReady(true, 0)
        c.onStartAlarm(0, 0)
        assertEquals(listOf("AlarmHide"), kinds(c.onTick(SaaController.ALARM_MAX_MS)))
        assertFalse(c.alarmActive)
    }

    @Test
    fun bandActionDismissesOrSnoozesOnlyDuringAlarm() {
        val c = SaaController()
        c.onBandReady(true, 0)
        assertNull(c.onBandAlarmAction(true))
        c.onStartAlarm(0, 0)
        assertEquals(listOf("AlarmHide", "Dismiss"), kinds(c.onBandAlarmAction(true)!!))
        c.onStartAlarm(0, 1_000)
        assertEquals(listOf("AlarmHide", "Snooze"), kinds(c.onBandAlarmAction(false)!!))
    }

    @Test
    fun hintNeedsBandAndIsCapped() {
        val c = SaaController()
        assertTrue(c.onHint(2).isEmpty())
        c.onBandReady(true, 0)
        assertEquals(5, (c.onHint(9).single() as SaaOut.Hint).repeat)
    }

    @Test
    fun actionNamesMatchDocumentedApi() {
        assertEquals("com.urbandroid.sleep.watch.START_TRACKING", SaaApi.START_TRACKING)
        assertEquals("com.urbandroid.sleep.watch.HR_DATA_UPDATE", SaaApi.HR_DATA_UPDATE)
        assertEquals("START_ALARM", SaaApi.shortName(SaaApi.START_ALARM))
        assertEquals(11, SaaApi.INCOMING.size)
    }
}
