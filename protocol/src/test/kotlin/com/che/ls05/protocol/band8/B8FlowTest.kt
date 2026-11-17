package com.che.ls05.protocol.band8

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Kayıttaki akışın yapısını sahte bantla oynatır:
 * auth, saat, pil, cihaz bilgisi, dosya listesi, dosya bölümleri, bildirim, kapatma, yeniden auth.
 * Bütün değerler sentetik.
 */
class B8FlowTest {
    @Test
    fun captureLikeSession() {
        val key = B8TestVectors.KEY
        val band = FakeBand(key)
        val T0 = 1_735_689_600L
        val detail = B8FileId(T0, 12, 3, 0, 0, 0)
        val summary = B8FileId(T0 + 75_000, 12, 5, 0, 0, 1)
        band.files[detail] = B8Activity.buildFile(detail, hex("9accc88cc8") + ByteArray(12 * 600))
        band.files[summary] = B8Activity.buildFile(summary, ByteArray(57))
        val h = B8Harness(key, band)

        h.authenticate()
        assertTrue(h.authResult is B8Auth.Step.Success)
        // Kayıttaki sıra: saat, pil, cihaz bilgisi.
        h.send(B8Messages.clock(B8Clock(2025, 1, 1, 9, 30, 0, 0, 12, 0, "Test/Zone", false)))
        h.send(B8Messages.batteryRequest())
        h.send(B8Messages.deviceInfoRequest())
        assertEquals(55, B8Messages.parseBattery(h.inbox[0])!!.levelPercent)
        assertEquals("9.9.9", B8Messages.parseDeviceInfo(h.inbox[1])!!.firmware)

        h.startFetch(B8Fetch())
        val files = h.fetchOut.filterIsInstance<B8Fetch.Out.File>()
        assertEquals(listOf(detail, summary), files.map { it.file.id })
        val parsed = files.map { B8ActivityParsers.parse(it.file) }
        assertTrue(parsed[0] is B8Parsed.DailyDetail)
        assertTrue(parsed[1] is B8Parsed.DailySummary)

        h.send(B8Messages.notification(B8Notification("com.example.test", "Test", "Başlık", "Metin", "20250101T093000", 7, "k7")))
        h.send(B8Messages.dismiss(listOf(B8NotificationRef(7, "com.example.test", "k7"))))
        h.send(B8Messages.findBand(true))

        // Bandda bildirim kapatıldı (7/1 banddan).
        val fromBand = B8Messages.dismiss(listOf(B8NotificationRef(7, "com.example.test", "k7")))
        h.deliver(listOf(FakeBand.Notify(B8Char.CMD_IN, B8Frame.encryptedSingle(band.encryptForPhone(fromBand)))))
        assertEquals(listOf(B8NotificationRef(7, "com.example.test", "k7")), B8Messages.parseDismiss(h.inbox.last()))

        // Yeniden auth (kayıtta 258 s): sayaç yine 1'den.
        val before = band.counters.size
        h.authenticate()
        h.send(B8Messages.batteryRequest())
        assertEquals(1L, band.counters[before])
        assertEquals(0, band.decryptFailures)
        assertTrue(h.failures.isEmpty())
        // Sayaç oturum içinde hep arttı (sahte bant azalırsa hata atardı).
        assertTrue(band.counters.size >= 10)
    }
}
