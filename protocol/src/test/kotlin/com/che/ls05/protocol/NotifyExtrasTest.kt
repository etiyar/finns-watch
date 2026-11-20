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
    fun navigationForwardsManeuverChangeAndThrottlesDistanceTo30s() {
        val n = NavigationFilter()
        val first = n.decide("maps", "200 m", "Atatürk Cd. yönünde sola dönün", 0)!!
        assertEquals("Atatürk Cd. yönünde sola dönün", first.title)
        assertEquals("200 m", first.body)
        assertNull(n.decide("maps", "150 m", "Atatürk Cd. yönünde sola dönün", 10_000), "yalnızca mesafe, 30 sn dolmadı")
        assertEquals("150 m", n.decide("maps", "150 m", "Atatürk Cd. yönünde sola dönün", 31_000)?.body)
        val later = NavigationFilter()
        later.decide("maps", "200 m", "Sola dönün", 0)
        assertEquals("100 m", later.decide("maps", "100 m", "Sola dönün", 30_000)?.body, "30 sn sonra mesafe güncellemesi")
        assertNull(later.decide("maps", "100 m", "Sola dönün", 90_000), "aynı mesafe ve yönerge tekrar gitmez")
        assertEquals("Sağa dönün", later.decide("maps", "50 m", "Sağa dönün", 31_000)?.title, "yönerge değişince hemen")
    }

    @Test
    fun navigationFindsDistanceInEitherFieldAndKeepsStreet() {
        val n = NavigationFilter()
        val o = n.decide("osmand", "Sola dönün", "0,4 km · İstiklal Cd.", 0)!!
        assertEquals("Sola dönün", o.title)
        assertEquals("0,4 km · İstiklal Cd.", o.body)
        val noDist = NavigationFilter().decide("maps", "Varış 12:30", "Hedefe yaklaşıyorsunuz", 0)!!
        assertEquals("Varış 12:30", noDist.title)
    }

    @Test
    fun navigationAppsAreSeparateAndResetForgets() {
        val n = NavigationFilter()
        n.decide("maps", "200 m", "Sola dönün", 0)
        assertTrue(n.decide("osmand", "200 m", "Sola dönün", 1) != null, "uygulamalar ayrı")
        n.reset("maps")
        assertTrue(n.decide("maps", "200 m", "Sola dönün", 2) != null)
    }

    // ---------- Müzik tuşu ----------

    @Test
    fun bandVolumeLevelScalesToStreamSteps() {
        assertEquals(15, MusicVolume.toStreamStep(100, 15))
        assertEquals(0, MusicVolume.toStreamStep(0, 15))
        assertEquals(8, MusicVolume.toStreamStep(50, 15))
        assertEquals(15, MusicVolume.toStreamStep(250, 15), "aralık dışı kırpılır")
        assertEquals(53, MusicVolume.toPercent(8, 15))
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
