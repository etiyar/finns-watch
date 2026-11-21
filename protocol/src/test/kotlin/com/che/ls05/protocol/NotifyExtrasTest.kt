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
        assertEquals("Sola dön", first.title)
        assertEquals("200 m · Atatürk Cd. yönünde sola dönün", first.body)
        assertNull(n.decide("maps", "150 m", "Atatürk Cd. yönünde sola dönün", 10_000), "yalnızca mesafe, 30 sn dolmadı")
        assertEquals("150 m · Atatürk Cd. yönünde sola dönün", n.decide("maps", "150 m", "Atatürk Cd. yönünde sola dönün", 31_000)?.body)
        val later = NavigationFilter()
        later.decide("maps", "200 m", "Sola dönün", 0)
        assertEquals("100 m · Sola dönün", later.decide("maps", "100 m", "Sola dönün", 30_000)?.body, "30 sn sonra mesafe güncellemesi")
        assertNull(later.decide("maps", "100 m", "Sola dönün", 90_000), "aynı mesafe ve yönerge tekrar gitmez")
        assertEquals("Sağa dön", later.decide("maps", "50 m", "Sağa dönün", 31_000)?.title, "yönerge değişince hemen")
    }

    @Test
    fun navigationFindsDistanceInEitherFieldAndKeepsStreet() {
        val n = NavigationFilter()
        val o = n.decide("osmand", "Sola dönün", "0,4 km · İstiklal Cd.", 0)!!
        assertEquals("Sola dön", o.title)
        assertEquals("0,4 km · Sola dönün · İstiklal Cd.", o.body)
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

    @Test
    fun bandVolumeEventPlausiblePercentIsApplied() {
        // Son bildirdiğimiz %40; bant %47 diyor: yakın, yüzde sayılır.
        assertEquals(71, MusicVolume.decide(raw = 47, lastReportedPercent = 40, currentStep = 60, streamMax = 150))
    }

    @Test
    fun bandVolumeEventFarValueStepsOneNotch() {
        // 20.11.2026 build 197: yukarıda 150/150, aşağıda 0/150 sıçraması. Uzak değer yön sayılır, bir çentik (max/15).
        assertEquals(70, MusicVolume.decide(raw = 255, lastReportedPercent = 40, currentStep = 60, streamMax = 150))
        assertEquals(50, MusicVolume.decide(raw = 0, lastReportedPercent = 40, currentStep = 60, streamMax = 150))
        assertEquals(150, MusicVolume.decide(raw = 999, lastReportedPercent = 100, currentStep = 145, streamMax = 150), "üstte kırpılır")
        assertEquals(0, MusicVolume.decide(raw = 0, lastReportedPercent = 3, currentStep = 4, streamMax = 150), "altta kırpılır")
        assertEquals(60, MusicVolume.decide(raw = 40, lastReportedPercent = 40, currentStep = 60, streamMax = 150), "aynı düzey: değişmez")
    }

    @Test
    fun bandVolumeEventWithoutReportUsesDirectionFromCurrent() {
        assertEquals(1, MusicVolume.decide(raw = 101, lastReportedPercent = null, currentStep = 0, streamMax = 15))
    }

    // ---------- Navigasyon manevrası ----------

    @Test
    fun maneuverIsClassifiedToShortTitle() {
        assertEquals(NavManeuver.LEFT, NavManeuver.classify("Çınar Sk. yönünde sola dönün"))
        assertEquals(NavManeuver.RIGHT, NavManeuver.classify("Turn right onto Main St"))
        assertEquals(NavManeuver.SLIGHT_LEFT, NavManeuver.classify("Hafif sola dönün"))
        assertEquals(NavManeuver.U_TURN, NavManeuver.classify("U dönüşü yapın"))
        assertEquals(NavManeuver.ROUNDABOUT, NavManeuver.classify("Göbekten 2. çıkışa girin"))
        assertEquals(NavManeuver.STRAIGHT, NavManeuver.classify("Continue straight"))
        assertEquals(NavManeuver.ARRIVE, NavManeuver.classify("Hedefinize ulaştınız"))
        assertEquals(NavManeuver.UNKNOWN, NavManeuver.classify("Atatürk Cd."))
        assertEquals("Sola dön", NavManeuver.LEFT.label)
    }

    @Test
    fun navigationOutputHasShortTitleAndFullBody() {
        val o = NavigationFilter().decide("maps", "200 m", "Çınar Sk. yönünde sola dönün", 0)!!
        assertEquals("Sola dön", o.title)
        assertEquals("200 m · Çınar Sk. yönünde sola dönün", o.body)
        val u = NavigationFilter().decide("maps", "Atatürk Cd.", "", 0)!!
        assertEquals("Atatürk Cd.", u.title)
        assertEquals(NavManeuver.STRAIGHT, NavManeuver.classify("Head towards Çınar Sk."))
    }

    @Test
    fun longUnknownManeuverIsCutFromTheRightAndKeptInBody() {
        val o = NavigationFilter().decide("maps", "350 m", "Mustafa Kemal Paşa Bulvarı çıkışını kullanın", 0)!!
        assertEquals("Mustafa Kemal Paşa…", o.title, "başlık sözcük sınırında sağdan kısalır")
        assertEquals("350 m · Mustafa Kemal Paşa Bulvarı çıkışını kullanın", o.body)
    }

    // ---------- Takvim çift bildirim ----------

    @Test
    fun calendarTitleSeenWithinThreeMinutesIsDuplicate() {
        val d = TitleDedupe(windowMs = 180_000)
        assertTrue(!d.seenRecently("Toplantı", 0))
        d.mark("Toplantı", 0)
        assertTrue(d.seenRecently("TOPLANTI ", 179_000), "büyük/küçük harf ve boşluk duyarsız")
        assertTrue(!d.seenRecently("Toplantı", 181_000))
        assertTrue(!d.seenRecently("Başka", 1_000))
        assertTrue(!d.seenRecently("", 1_000), "boş başlık eşleşmez")
        assertTrue("com.google.android.calendar" in TitleDedupe.CALENDAR_PACKAGES)
        assertTrue("com.whatsapp" !in TitleDedupe.CALENDAR_PACKAGES)
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

    @Test
    fun navigationPartsSplitDistanceManeuverStreet() {
        val p = NavigationFilter().parts("300 m", "Sola dönün · Örnek Caddesi")
        kotlin.test.assertEquals("300 m", p.distance)
        kotlin.test.assertEquals("Sola dönün · Örnek Caddesi", p.maneuver)
        val q = NavigationFilter().parts("Sağa dönün", "0,4 km · Deneme Sokak")
        kotlin.test.assertEquals("0,4 km", q.distance)
        kotlin.test.assertEquals("Sağa dönün", q.maneuver)
        kotlin.test.assertEquals("Deneme Sokak", q.rest)
        kotlin.test.assertEquals(NavManeuver.classify("Sağa dönün"), q.kind)
    }
}
