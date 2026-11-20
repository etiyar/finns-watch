package com.che.ls05.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tekrar, navigasyon ve pil uyarısı kuralları. */
class NotifyExtrasTest {
    private val min = 60_000L

    // ---------- Okunana kadar tekrar ----------

    @Test
    fun repeatsEveryIntervalUpToMax() {
        val t = RepeatTracker(maxPerHour = 100)
        t.schedule("k", everyMin = 5, max = 2, now = 0)
        assertEquals(emptyList<String>(), t.due(5 * min - 1))
        assertEquals(listOf("k"), t.due(5 * min))
        assertEquals(emptyList<String>(), t.due(9 * min))
        assertEquals(listOf("k"), t.due(10 * min))
        assertEquals(emptyList<String>(), t.due(60 * min), "en çok 2 kez")
        assertTrue(t.isEmpty)
    }

    @Test
    fun removedNotificationStopsRepeats() {
        val t = RepeatTracker()
        t.schedule("k", 1, 5, 0)
        t.remove("k")
        assertEquals(emptyList<String>(), t.due(10 * min))
    }

    @Test
    fun globalCapPreventsSpam() {
        val t = RepeatTracker(maxPerHour = 3)
        for (i in 1..5) t.schedule("k$i", 1, 10, 0)
        assertEquals(3, t.due(min).size)
        assertEquals(0, t.due(2 * min).size, "saatlik üst sınır")
        assertEquals(3, t.due(62 * min).size, "bir saat sonra yeniden")
    }

    @Test
    fun nextWakeAndStateSurvivesRestart() {
        val t = RepeatTracker()
        t.schedule("a", 5, 3, 0)
        t.schedule("b\tx", 2, 1, 0)
        assertEquals(2 * min, t.nextWake())
        val back = RepeatTracker.decode(t.encode(), maxPerHour = 20)
        assertEquals(listOf("b\tx"), back.due(2 * min))
        assertEquals(listOf("a"), back.due(5 * min))
        assertTrue(RepeatTracker.decode("bozuk", 20).isEmpty)
    }

    // ---------- Navigasyon ----------

    @Test
    fun navigationForwardsChangedInstructionAndThrottlesDistance() {
        val n = NavigationFilter(minIntervalMs = 10_000)
        assertTrue(n.shouldForward("maps", "Sola dönün", "300 m", 0))
        assertTrue(!n.shouldForward("maps", "Sola dönün", "300 m", 20_000), "aynı yönerge tekrar gitmez")
        assertTrue(!n.shouldForward("maps", "Sola dönün", "200 m", 5_000), "yalnızca mesafe değişti, 10 sn dolmadı")
        assertTrue(n.shouldForward("maps", "Sola dönün", "150 m", 10_000))
        assertTrue(n.shouldForward("maps", "Sağa dönün", "50 m", 11_000), "yönerge değişti, hemen")
        assertTrue(n.shouldForward("osmand", "Sola dönün", "300 m", 11_000), "uygulamalar ayrı")
    }

    @Test
    fun navigationResetForgetsLast() {
        val n = NavigationFilter()
        n.shouldForward("maps", "A", "1", 0)
        n.reset("maps")
        assertTrue(n.shouldForward("maps", "A", "1", 1))
    }

    // ---------- Telefon pili ----------

    @Test
    fun lowBatteryOncePerTransition() {
        val b = BatteryAlerts()
        assertNull(b.update(50, charging = false, lowOn = true, threshold = 15, fullOn = true))
        assertEquals(BatteryAlerts.Alert.LOW, b.update(15, false, true, 15, true))
        assertNull(b.update(14, false, true, 15, true), "bir kez")
        assertNull(b.update(16, true, true, 15, true), "şarja takıldı: sıfırlanır")
        assertEquals(BatteryAlerts.Alert.LOW, b.update(15, false, true, 15, true))
        assertNull(b.update(10, false, false, 15, true), "kapalıysa yok")
    }

    @Test
    fun fullBatteryOnceWhileCharging() {
        val b = BatteryAlerts()
        assertNull(b.update(99, true, true, 15, true))
        assertEquals(BatteryAlerts.Alert.FULL, b.update(100, true, true, 15, true))
        assertNull(b.update(100, true, true, 15, true))
        assertNull(b.update(100, false, true, 15, true), "çıkarıldı")
        assertEquals(BatteryAlerts.Alert.FULL, b.update(100, true, true, 15, true))
    }

    // ---------- VoIP arama ----------

    @Test
    fun voipCallWithoutTypeIsIncomingUntilChronometer() {
        assertEquals(CallKind.INCOMING, CallClassifier.classify("com.whatsapp", "call", true, 0, false))
        assertEquals(CallKind.ONGOING, CallClassifier.classify("com.whatsapp", "call", true, 0, false, chronometer = true))
        assertEquals(CallKind.ONGOING, CallClassifier.classify("com.google.android.dialer", "call", true, 0, false), "telefon uygulaması eskisi gibi")
    }
}
