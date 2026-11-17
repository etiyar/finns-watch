package com.che.ls05.protocol.band8

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Kontrol paketleri kayıttaki baytlarla birebir (kişisel veri taşımazlar). */
class B8FrameTest {
    @Test
    fun controlPacketsFromCapture() {
        assertContentEquals(hex("00000300"), B8Frame.ACK)
        assertContentEquals(hex("00000101"), B8Frame.READY)
        assertContentEquals(hex("00000100"), B8Frame.ALL_RECEIVED)
        // Kayıtta: band 17 parçalık şifreli gönderim başlattı.
        assertContentEquals(hex("000000011100"), B8Frame.chunkStart(17, true))
    }

    @Test
    fun decodeKinds() {
        assertTrue(B8Frame.decode(hex("00000300")) is B8Frame.Event.Ack)
        assertEquals(2, (B8Frame.decode(hex("00000302")) as B8Frame.Event.Nack).code)
        val cs = B8Frame.decode(hex("000000011100")) as B8Frame.Event.ChunkStart
        assertEquals(17, cs.count); assertTrue(cs.encrypted)
        assertTrue(B8Frame.decode(hex("00000101")) is B8Frame.Event.Ready)
        assertTrue(B8Frame.decode(hex("00000100")) is B8Frame.Event.AllReceived)
        assertTrue(B8Frame.decode(hex("00000102")) is B8Frame.Event.Rejected)
        assertEquals(listOf(2, 5), (B8Frame.decode(hex("0000010502000500")) as B8Frame.Event.Resend).indexes)
        val s = B8Frame.decode(hex("00000202aabb")) as B8Frame.Event.Single
        assertFalse(s.encrypted); assertContentEquals(hex("aabb"), s.payload)
        val e = B8Frame.decode(hex("00000201cc")) as B8Frame.Event.Single
        assertTrue(e.encrypted)
        val c = B8Frame.decode(hex("0300ddee")) as B8Frame.Event.Chunk
        assertEquals(3, c.index); assertContentEquals(hex("ddee"), c.data)
        assertTrue(B8Frame.decode(hex("00")) is B8Frame.Event.Unknown)
    }

    @Test
    fun splitSingleBoundary() {
        // MTU 247: yazma en çok 244 bayt, tek pakette 240 bayt blok sığar.
        val single = B8Frame.split(ByteArray(240), true, 247)
        assertFalse(single.isChunked)
        assertEquals(244, single.first.size)
        assertContentEquals(hex("00000201"), single.first.copyOf(4))
        val one = B8Frame.split(ByteArray(241), true, 247)
        assertTrue(one.isChunked)
        assertEquals(1, one.chunks.size)
        assertContentEquals(hex("000000010100"), one.first)
        val two = B8Frame.split(ByteArray(243), true, 247)
        assertEquals(listOf(244, 3), two.chunks.map { it.size })
        assertContentEquals(hex("000000010200"), two.first)
    }

    @Test
    fun splitLikeCaptureNotification() {
        // Kayıttaki 814 baytlık bildirim: 2 bayt sayaç + 818 bayt şifreli = 820 -> 242+242+242+94.
        val sp = B8Frame.split(ByteArray(820), true, 247)
        assertEquals(listOf(244, 244, 244, 96), sp.chunks.map { it.size })
        assertContentEquals(hex("0400"), sp.chunks[3].copyOf(2))
    }

    @Test
    fun reassembleOutOfOrderAndMissing() {
        val r = B8Reassembler()
        r.start(3, true)
        assertNull(r.add(2, hex("02")))
        assertNull(r.add(9, hex("09")))
        assertEquals(listOf(1, 3), r.missing())
        assertNull(r.add(1, hex("01")))
        val done = r.add(3, hex("03"))!!
        assertContentEquals(hex("010203"), done.first)
        assertTrue(done.second)
        assertFalse(r.inProgress)
        assertNull(r.add(1, hex("01")))
    }
}
