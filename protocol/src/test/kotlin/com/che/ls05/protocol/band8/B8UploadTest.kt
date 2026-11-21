package com.che.ls05.protocol.band8

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Veri yükleme kanalı. Beklenen boylar 17 Kasım kaydındaki üç simge yüklemesinin yapısından
 * (yalnızca boylar ve sayılar; içerik sentetik).
 */
class B8UploadTest {
    private val key = B8TestVectors.KEY

    private fun authed(band: FakeBand = FakeBand(key)): B8Harness {
        val h = B8Harness(key, band)
        h.authenticate()
        assertTrue(h.band.authOk)
        return h
    }

    private fun icon(side: Int) = ByteArray(side * side * 4) { (it * 31 + side).toByte() }

    @Test
    fun requestMatchesCaptureLayout() {
        // Kayıtta 22/0 isteğinin düz boyu: 3136 ve 7744 baytta 32, 25600 baytta 33.
        assertEquals(32, B8Upload.request(50, icon(28)).size)
        assertEquals(32, B8Upload.request(50, icon(44)).size)
        assertEquals(33, B8Upload.request(50, icon(80)).size)
        val c = B8Command.parse(B8Upload.request(50, icon(28)))!!
        assertTrue(c.matches(22, 0))
        val r = c.body(24)!!.message(1)!!
        assertEquals(50, r.int(1))
        assertContentEquals(B8Upload.md5(icon(28)), r.bytes(2))
        assertEquals(3136, r.int(3))
    }

    @Test
    fun blobLayoutMd5AndCrc() {
        val data = icon(28)
        val b = B8Upload.blob(50, data)
        assertEquals(3136 + 26, b.size)
        assertEquals(0, b[0].toInt())
        assertEquals(50, b[1].toInt())
        assertContentEquals(B8Upload.md5(data), b.copyOfRange(2, 18))
        assertContentEquals(hex("400c0000"), b.copyOfRange(18, 22))
        val crc = B8Upload.crc32(b, b.size - 4)
        assertEquals(crc, (0 until 4).fold(0L) { a, i -> a or ((b[b.size - 4 + i].toLong() and 0xFF) shl (8 * i)) })
        val p = B8Upload.parseBlob(b)!!
        assertEquals(50, p.type)
        assertContentEquals(data, p.data)
        // Bozuk CRC reddedilir.
        b[30] = (b[30] + 1).toByte()
        assertNull(B8Upload.parseBlob(b))
    }

    @Test
    fun partsMatchCapture() {
        fun sizes(side: Int) = B8Upload.parts(B8Upload.blob(50, icon(side))).map { it.size }
        assertEquals(listOf(3166), sizes(28))
        assertEquals(listOf(4096, 3682), sizes(44))
        assertEquals(List(6) { 4096 } + 1078, sizes(80))
        val last = B8Upload.parts(B8Upload.blob(50, icon(80))).last()
        assertContentEquals(hex("07000700"), last.copyOf(4))
    }

    @Test
    fun iconUploadsEndToEndWithCaptureChunkCounts() {
        val h = authed()
        for (side in listOf(28, 44, 80)) {
            val next = h.upload(B8UploadJob(50, icon(side)))
            assertTrue(next is B8UploadJob.Next.Send)
            assertTrue(h.uploadEvents.last() === B8Incoming.UploadDone)
        }
        // Kayıttaki parça sayıları: 14 | 17, 16 | 6 x 17, 5.
        assertEquals(listOf(14, 17, 16) + List(6) { 17 } + 5, h.band.uploadChunkCounts)
        assertEquals(3, h.band.uploads.size)
        assertContentEquals(icon(80), h.band.uploads[2].data)
        assertEquals(0, h.band.decryptFailures)
        assertTrue(!h.ch.uploading)
        // Bölümler sayaç 0 ile gider: komut sayacı yalnızca 22/0 istekleri kadar artar.
        assertEquals(listOf(1L, 2L, 3L), h.band.counters)
        // Her parça yazması en çok 244 bayt.
        assertTrue(h.written.filter { it.char == B8Char.UPLOAD }.all { it.bytes.size <= 244 })
    }

    @Test
    fun progressCountsParts() {
        val h = authed()
        h.upload(B8UploadJob(16, ByteArray(20_000) { it.toByte() }))
        val prog = h.uploadEvents.filterIsInstance<B8Incoming.UploadProgress>()
        assertEquals((1..5).toList(), prog.map { it.done })
        assertTrue(prog.all { it.total == 5 })
    }

    @Test
    fun resendRequestedChunk() {
        val band = FakeBand(key).apply { dropUploadChunk = 3 }
        val h = authed(band)
        h.upload(B8UploadJob(50, icon(44)))
        assertEquals(1, h.band.uploads.size)
        assertContentEquals(icon(44), h.band.uploads[0].data)
    }

    @Test
    fun nonZeroStatusStopsBeforeData() {
        val band = FakeBand(key).apply { uploadStatus = 1 }
        val h = authed(band)
        val next = h.upload(B8UploadJob(50, icon(28)))
        assertTrue(next is B8UploadJob.Next.Fail)
        assertTrue(h.written.none { it.char == B8Char.UPLOAD })
    }

    @Test
    fun md5MismatchFails() {
        val job = B8UploadJob(50, icon(28))
        assertTrue(job.onAck(B8Upload.Ack(ByteArray(16), 0, 0, null)) is B8UploadJob.Next.Fail)
        assertTrue(job.onAck(B8Upload.Ack(null, 0, 0, null)) is B8UploadJob.Next.Send)
    }

    @Test
    fun bandPartSizeIsUsed() {
        val job = B8UploadJob(16, ByteArray(5000))
        val n = job.onAck(B8Upload.Ack(job.md5, 0, 0, 2048)) as B8UploadJob.Next.Send
        assertEquals(3, n.parts.size)
        assertEquals(2048, n.parts[0].size)
    }

    @Test
    fun resumeSkipsWholePartsOnly() {
        val job = B8UploadJob(16, ByteArray(20_000))
        val n = job.onAck(B8Upload.Ack(job.md5, 0, 2 * 4092, null)) as B8UploadJob.Next.Send
        assertEquals(2, n.skipped)
        assertEquals(5, n.total)
        assertEquals(3, n.parts.size)
        assertContentEquals(hex("05000300"), n.parts[0].copyOf(4))
        assertTrue(job.onAck(B8Upload.Ack(job.md5, 0, 100, null)) is B8UploadJob.Next.Fail)
        assertTrue(job.onAck(B8Upload.Ack(job.md5, 0, 1_000_000, null)) is B8UploadJob.Next.Fail)
    }

    @Test
    fun resumeEndToEnd() {
        val band = FakeBand(key).apply { uploadResumeAt = 4092 }
        val h = authed(band)
        h.upload(B8UploadJob(16, ByteArray(10_000) { it.toByte() }))
        assertEquals(2, h.band.uploadChunkCounts.size)
        assertTrue(h.uploadEvents.last() === B8Incoming.UploadDone)
        assertEquals(listOf(2, 3), h.uploadEvents.filterIsInstance<B8Incoming.UploadProgress>().map { it.done })
    }

    @Test
    fun rejectedUploadReportsFailure() {
        val h = authed()
        val r = h.ch.startUpload(B8Upload.parts(B8Upload.blob(50, icon(28))))
        assertEquals(1, r.writes.size)
        assertTrue(h.ch.uploading)
        val res = h.ch.onNotify(B8Char.UPLOAD, hex("00000102"))
        assertTrue(res.incoming.single() is B8Incoming.UploadFailed)
        assertTrue(!h.ch.uploading)
    }

    @Test
    fun noSessionNoUpload() {
        val ch = B8Channel()
        val r = ch.startUpload(listOf(ByteArray(10)))
        assertTrue(r.writes.isEmpty())
        assertTrue(r.incoming.single() is B8Incoming.UploadFailed)
    }

    @Test
    fun secondUploadRefusedWhileBusy() {
        val h = authed()
        h.ch.startUpload(B8Upload.parts(B8Upload.blob(50, icon(28))))
        val r = h.ch.startUpload(B8Upload.parts(B8Upload.blob(50, icon(28))))
        assertTrue(r.incoming.single() is B8Incoming.UploadFailed)
        h.ch.abortUpload()
        assertTrue(!h.ch.uploading)
    }

    @Test
    fun disconnectDropsUpload() {
        val h = authed()
        h.ch.startUpload(B8Upload.parts(B8Upload.blob(50, icon(28))))
        h.ch.onDisconnected()
        assertTrue(!h.ch.uploading)
        // Bağlantı yokken gelen "hazırım" yazma üretmez.
        assertTrue(h.ch.onNotify(B8Char.UPLOAD, B8Frame.READY).writes.isEmpty())
    }

    @Test
    fun strayUploadControlIgnored() {
        val h = authed()
        val r = h.ch.onNotify(B8Char.UPLOAD, B8Frame.ALL_RECEIVED)
        assertTrue(r.writes.isEmpty() && r.incoming.isEmpty())
    }

    @Test
    fun ackParse() {
        val md5 = ByteArray(16) { it.toByte() }
        val a = B8Upload.parseAck(B8Command.parse(B8Upload.ack(md5))!!)!!
        assertContentEquals(md5, a.md5)
        assertEquals(0, a.status)
        assertEquals(0, a.resumeAt)
        assertNull(a.partSize)
        assertNull(B8Upload.parseAck(B8Command.parse(B8Messages.batteryRequest())!!))
    }
}
