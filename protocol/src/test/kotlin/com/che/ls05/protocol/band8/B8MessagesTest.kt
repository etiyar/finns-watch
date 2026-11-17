package com.che.ls05.protocol.band8

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mesaj yapıları, çözülmüş kayıttaki yapılarla aynı; bütün değerler sentetik. */
class B8MessagesTest {
    @Test
    fun authNonceShapeMatchesCapture() {
        val b = B8Messages.authNonce(ByteArray(16) { 0x11 })
        // Kayıttaki 1/26 başı: 08 01 10 1a 1a 15 f2 01 12 0a 10 + 16 bayt nonce, toplam 27 bayt.
        assertContentEquals(hex("0801101a1a15f201120a10"), b.copyOf(11))
        assertEquals(27, b.size)
    }

    @Test
    fun authProofShape() {
        val b = B8Messages.authProof(ByteArray(32), ByteArray(30))
        assertContentEquals(hex("0801101b1a"), b.copyOf(5))
        val auth = B8Command.parse(b)!!.body(3)!!.message(32)!!
        assertEquals(32, auth.bytes(1)!!.size)
        assertEquals(30, auth.bytes(2)!!.size)
    }

    @Test
    fun phoneInfoFields() {
        val m = PbMessage.parse(B8Messages.phoneInfo(B8PhoneInfo(34, "Test Phone", "gb")))
        assertEquals(0, m.int(1))
        assertTrue(m.has(1), "alan 1 açıkça yazılmalı")
        assertEquals(34f, m.float(2))
        assertEquals("Test Phone", m.string(3))
        assertEquals(224, m.int(4))
        assertEquals("GB", m.string(5))
    }

    @Test
    fun simpleRequests() {
        assertContentEquals(hex("08021001"), B8Messages.batteryRequest())
        assertContentEquals(hex("08021002"), B8Messages.deviceInfoRequest())
        assertContentEquals(hex("080210122202 2800".replace(" ", "")), B8Messages.findBand(true))
        assertContentEquals(hex("08021012220228 01".replace(" ", "")), B8Messages.findBand(false))
        assertContentEquals(hex("08081002"), B8Messages.filesPast())
        // Kayıttaki 8/1 isteği: Health > 5 > 1 = 1.
        assertContentEquals(hex("080810015204 2a020801".replace(" ", "")), B8Messages.filesToday())
    }

    @Test
    fun parseBatteryAndDeviceInfo() {
        val bat = B8Messages.parseBattery(B8Command.parse(B8TestVectors.BAT_PLAIN)!!)!!
        assertEquals(55, bat.levelPercent)
        assertEquals(2, bat.state)
        val di = B8Messages.command(2, 2).message(4, PbWriter().message(3,
            PbWriter().string(1, "SERIAL-TEST").string(2, "9.9.9").string(3, "").string(4, "TESTMODEL"))).toByteArray()
        val info = B8Messages.parseDeviceInfo(B8Command.parse(di)!!)!!
        assertEquals("9.9.9", info.firmware)
        assertEquals("TESTMODEL", info.model)
        assertNull(B8Messages.parseBattery(B8Command.parse(hex("08021001"))!!))
    }

    @Test
    fun clockLikeCapture() {
        val b = B8Messages.clock(B8Clock(2025, 1, 2, 9, 30, 15, 250, 12, 0, "Test/Zone", false))
        val clock = B8Command.parse(b)!!.body(4)!!.message(4)!!
        val date = clock.message(1)!!
        assertEquals(listOf(2025, 1, 2), listOf(date.int(1), date.int(2), date.int(3)))
        val time = clock.message(2)!!
        assertEquals(listOf(9, 30, 15, 250), listOf(time.int(1), time.int(2), time.int(3), time.int(4)))
        val tz = clock.message(3)!!
        assertEquals(24, tz.int(1), "zigzag: +12 çeyrek -> 24")
        assertEquals(12, tz.sint(1))
        assertFalse(tz.has(2), "yaz saati 0 iken yazılmaz (kayıttaki gibi)")
        assertEquals("Test/Zone", tz.string(3))
        assertFalse(clock.has(4), "24 saat biçiminde alan 4 yazılmaz (kayıttaki gibi)")
        val neg = B8Command.parse(B8Messages.clock(B8Clock(2025, 1, 2, 9, 30, 0, 0, -14, 4, "X", true)))!!.body(4)!!.message(4)!!
        assertEquals(-14, neg.message(3)!!.sint(1))
        assertEquals(4, neg.message(3)!!.sint(2))
        assertEquals(true, neg.bool(4))
    }

    @Test
    fun notificationFieldsLikeCapture() {
        val n = B8Notification("com.example.test", "Test App", "Başlık", "Gövde ğüşiöç", "20250101T093000", 42, "0|com.example.test|42|null|1")
        val inner = B8Command.parse(B8Messages.notification(n))!!.body(9)!!.message(3)!!.message(1)!!
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 12), inner.fields.map { it.number })
        assertEquals("", inner.string(4))
        assertEquals("Gövde ğüşiöç", inner.string(5))
        assertEquals(15, inner.string(6)!!.length)
        assertEquals(42, inner.int(7))
        assertFalse(inner.has(8), "arama değilse alan 8 yazılmaz")
    }

    @Test
    fun notificationTruncation() {
        val long = "ş".repeat(400) // 800 bayt
        val n = B8Notification("p", "a", "Ü".repeat(50), long, "20250101T093000", 1, "k")
        val inner = B8Command.parse(B8Messages.notification(n))!!.body(9)!!.message(3)!!.message(1)!!
        val title = inner.string(3)!!
        val body = inner.string(5)!!
        assertTrue(title.toByteArray().size <= B8Messages.TITLE_MAX_BYTES)
        assertTrue(body.toByteArray().size <= B8Messages.BODY_MAX_BYTES)
        assertTrue(body.endsWith("…"))
        assertEquals("abc", B8Messages.truncateUtf8("abc", 3))
        // Emoji (4 bayt) bölünmez.
        val e = B8Messages.truncateUtf8("ab😀cd", 7)
        assertEquals("ab…", e)
    }

    @Test
    fun callMessages() {
        val inner = B8Command.parse(B8Messages.incomingCall(null, "000", "20250101T093000"))!!.body(9)!!.message(3)!!.message(1)!!
        assertEquals("phone", inner.string(1))
        assertEquals("?", inner.string(3))
        assertEquals(0, inner.int(7))
        assertEquals(true, inner.bool(8))
        val end = B8Command.parse(B8Messages.callEnded())!!
        assertTrue(end.matches(7, 1))
        val refs = B8Messages.parseDismiss(end)
        assertEquals(listOf(B8NotificationRef(0, "phone", null)), refs)
    }

    @Test
    fun dismissBothDirections() {
        val refs = listOf(B8NotificationRef(1, "com.example.a", "k1"), B8NotificationRef(2, "com.example.b", "k2"))
        val c = B8Command.parse(B8Messages.dismiss(refs))!!
        assertEquals(refs, B8Messages.parseDismiss(c))
    }

    @Test
    fun openOnPhoneAndFindPhone() {
        val open = B8Messages.command(7, 8).message(9, PbWriter().message(2,
            PbWriter().uint(1, 5).string(2, "com.example.a").string(4, "k"))).toByteArray()
        assertEquals(B8NotificationRef(5, "com.example.a", "k"), B8Messages.parseOpenOnPhone(B8Command.parse(open)!!))
        val find = B8Messages.command(2, 17).message(4, PbWriter().uint(5, 0)).toByteArray()
        assertEquals(true, B8Messages.parseFindStart(B8Command.parse(find)!!))
    }

    @Test
    fun fileListAndRequest() {
        val a = B8FileId(1_700_000_000, 0, 3, 0, 0, 0)
        val b = B8FileId(1_700_003_600, -4, 5, 0, 0, 1)
        val reply = B8Messages.command(8, 1).message(10, PbWriter().bytes(2, a.encode() + b.encode())).toByteArray()
        assertEquals(listOf(a, b), B8Messages.parseFileList(B8Command.parse(reply)!!))
        val empty = B8Messages.command(8, 2).message(10, PbWriter().bytes(2, ByteArray(0))).toByteArray()
        assertEquals(emptyList<B8FileId>(), B8Messages.parseFileList(B8Command.parse(empty)!!))
        val req = B8Command.parse(B8Messages.fileRequest(a))!!
        assertContentEquals(a.encode(), req.body(10)!!.bytes(2))
        val del = B8Command.parse(B8Messages.fileDelete(a))!!
        assertTrue(del.matches(8, 5))
        assertContentEquals(a.encode(), del.body(10)!!.bytes(3))
    }

    @Test
    fun commandParseRejectsGarbage() {
        assertNull(B8Command.parse(hex("ffff")))
        assertNull(B8Command.parse(hex("0801")))
        assertNotNull(B8Command.parse(hex("08011001")))
    }
}
