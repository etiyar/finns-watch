package com.che.ls05.protocol.band8

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class B8ChannelTest {
    private val key = B8TestVectors.KEY

    private fun authed(): B8Harness {
        val h = B8Harness(key, FakeBand(key))
        h.authenticate()
        assertTrue(h.authResult is B8Auth.Step.Success)
        assertTrue(h.band.authOk)
        return h
    }

    @Test
    fun oneMessageInFlightAndCounterFromOne() {
        val h = authed()
        val ch = h.ch
        // Onaylar teslim edilmeden üç mesaj: yalnızca ilki yazılır.
        val w1 = ch.send(B8Messages.batteryRequest())
        val w2 = ch.send(B8Messages.deviceInfoRequest())
        val w3 = ch.send(B8Messages.findBand(true))
        assertEquals(1, w1.size)
        assertEquals(0, w2.size + w3.size)
        assertEquals(3, ch.pending)
        // Çerçevedeki sayaç 1.
        assertContentEquals(hex("000002010100"), w1[0].bytes.copyOf(6))
        h.write(w1); h.run()
        assertEquals(listOf(1L, 2L, 3L), h.band.counters)
        assertEquals(0, ch.pending)
        assertEquals(listOf(2 to 1, 2 to 2), h.inbox.map { it.type to it.subtype })
    }

    @Test
    fun replyAckedOnSameCharacteristic() {
        val h = authed()
        h.send(B8Messages.batteryRequest())
        assertTrue(h.written.any { it.char == B8Char.CMD_IN && it.bytes.contentEquals(B8Frame.ACK) })
        assertEquals(55, B8Messages.parseBattery(h.inbox.last())!!.levelPercent)
    }

    @Test
    fun longMessageGoesChunked() {
        val h = authed()
        val n = B8Notification("com.example.test", "Test", "T", "x".repeat(500), "20250101T093000", 1, "k")
        h.send(B8Messages.notification(n))
        val got = h.band.received.last()
        assertTrue(got.matches(7, 0))
        assertEquals("x".repeat(500), got.body(9)!!.message(3)!!.message(1)!!.string(5))
        assertTrue(h.written.any { it.char == B8Char.CMD_OUT && B8Frame.decode(it.bytes) is B8Frame.Event.ChunkStart })
        assertEquals(0, h.ch.pending)
    }

    @Test
    fun queuedNotificationsPreserveSourceAndTurkishText() {
        val h = authed()
        val first = B8Notification("com.example.chat", "Sohbet", "Çağrı", "Öğle ışığı", "20261118T142301", 11, "chat:11")
        val second = B8Notification("com.example.mail", "Posta", "İleti", "Görüşürüz".repeat(31), "20261118T142302", 11, "mail:11")
        val firstWrite = h.ch.send(B8Messages.notification(first))
        assertTrue(h.ch.send(B8Messages.notification(second)).isEmpty())
        h.write(firstWrite)
        h.run()

        val received = h.band.received.filter { it.matches(7, 0) }
        assertEquals(2, received.size)
        received.zip(listOf(first, second)).forEach { (command, expected) ->
            val fields = command.body(9)!!.message(3)!!.message(1)!!
            assertEquals(expected.packageName, fields.string(1))
            assertEquals(expected.title, fields.string(3))
            assertEquals(expected.body, fields.string(5))
            assertEquals(expected.timestamp, fields.string(6))
            assertEquals(expected.id, fields.int(7))
            assertEquals(expected.key, fields.string(12))
        }
        assertEquals(0, h.ch.pending)
    }

    @Test
    fun reauthResetsCounterAndReencryptsQueued() {
        val h = authed()
        h.send(B8Messages.batteryRequest())
        h.send(B8Messages.deviceInfoRequest())
        assertEquals(2L, h.ch.counter)
        // Bir mesaj yolda kaybolsun (onay gelmesin), arkasında iki mesaj beklesin.
        h.dropToBand = { it.char == B8Char.CMD_OUT }
        h.send(B8Messages.findBand(true))
        h.write(h.ch.send(B8Messages.findBand(false)))
        h.write(h.ch.send(B8Messages.batteryRequest()))
        assertEquals(3, h.ch.pending)
        h.dropToBand = { false }
        // Yeniden auth: yoldaki ve bekleyenler yeni oturumla, sayaç 1'den gider.
        val before = h.band.counters.size
        h.authenticate()
        assertTrue(h.authResult is B8Auth.Step.Success)
        assertEquals(listOf(1L, 2L, 3L), h.band.counters.drop(before))
        assertEquals(0, h.band.decryptFailures, "eski anahtarla hiçbir şey gitmedi")
        val subs = h.band.received.takeLast(3).map { it.type to it.subtype }
        assertEquals(listOf(2 to 18, 2 to 18, 2 to 1), subs)
    }

    @Test
    fun encryptedWaitsUntilAuth() {
        val h = B8Harness(key, FakeBand(key))
        assertEquals(0, h.ch.send(B8Messages.batteryRequest()).size)
        h.authenticate()
        assertEquals(listOf(1L), h.band.counters)
    }

    @Test
    fun badTagIsReportedNotDelivered() {
        val h = authed()
        val forged = B8Frame.encryptedSingle(ByteArray(12) { 5 })
        val r = h.ch.onNotify(B8Char.CMD_IN, forged)
        assertTrue(r.incoming.single() is B8Incoming.DecryptFailed)
        assertContentEquals(B8Frame.ACK, r.writes.single().bytes)
    }

    @Test
    fun timeoutAndRejectMoveOn() {
        val h = authed()
        val ch = h.ch
        ch.send(B8Messages.batteryRequest())
        ch.send(B8Messages.deviceInfoRequest())
        assertEquals(1, ch.onTimeout().size)
        assertEquals(1, ch.pending)
        val r = ch.onNotify(B8Char.CMD_OUT, hex("00000301"))
        assertTrue(r.incoming.single() is B8Incoming.SendRejected)
        assertEquals(0, ch.pending)
    }

    @Test
    fun disconnectKeepsQueue() {
        val h = authed()
        h.ch.send(B8Messages.batteryRequest())
        h.ch.onDisconnected()
        assertEquals(1, h.ch.pending)
        assertEquals(null, h.ch.session)
    }

    @Test
    fun uuids() {
        assertEquals("00000052-0000-1000-8000-00805f9b34fb", B8Char.CMD_OUT.uuid)
        assertEquals(B8Char.ACTIVITY, B8Char.of(0x53))
    }
}
