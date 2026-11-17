package com.che.ls05.protocol.band8

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class B8FetchTest {
    private val key = B8TestVectors.KEY
    private val T0 = 1_735_689_600L

    private fun detailFile(id: B8FileId, minutes: Int): ByteArray {
        val body = java.io.ByteArrayOutputStream()
        body.write(hex("9accc88cc8"))
        repeat(minutes) { m -> val r = ByteArray(12); r[0] = (m % 50).toByte(); r[6] = 70; r[11] = 0xFF.toByte(); body.write(r) }
        return B8Activity.buildFile(id, body.toByteArray())
    }

    private fun setup(maxParts: Int = Int.MAX_VALUE): Pair<B8Harness, List<B8FileId>> {
        val band = FakeBand(key)
        val detail = B8FileId(T0, 12, 3, 0, 0, 0)
        val summary = B8FileId(T0 + 75_000, 12, 5, 0, 0, 1)
        band.files[detail] = detailFile(detail, 1260) // 4 bölüm
        band.files[summary] = B8Activity.buildFile(summary, ByteArray(4 + 53))
        band.maxPartsPerFile = maxParts
        val h = B8Harness(key, band)
        h.authenticate()
        return h to listOf(detail, summary)
    }

    private fun requests(h: B8Harness) = h.band.received.filter { it.matches(8, 3) }

    @Test
    fun oneIdPerRequestAllPartsArrive() {
        val (h, ids) = setup()
        h.startFetch(B8Fetch())
        val reqs = requests(h)
        assertEquals(2, reqs.size)
        assertTrue(reqs.all { it.body(10)!!.bytes(2)!!.size == 7 }, "her 8/3'te tek kimlik")
        val files = h.fetchOut.filterIsInstance<B8Fetch.Out.File>()
        assertEquals(ids, files.map { it.file.id })
        assertTrue(files.all { it.requested })
        assertTrue(h.fetchOut.last() is B8Fetch.Out.Done)
        val parsed = B8ActivityParsers.parse(files[0].file) as B8Parsed.DailyDetail
        assertEquals(1260, parsed.records.size)
        assertEquals(T0 + 1259 * 60, parsed.records.last().utcSeconds)
    }

    @Test
    fun neverDeletesByDefault() {
        val (h, ids) = setup()
        val f = B8Fetch()
        h.startFetch(f)
        for (id in ids) h.process(f.confirmStored(id))
        h.run()
        assertTrue(h.band.received.none { it.matches(8, 5) })
        assertEquals(2, h.band.files.size)
    }

    @Test
    fun deletesOnlyWithFlagAndConfirmation() {
        val (h, ids) = setup()
        val f = B8Fetch(allowDelete = true)
        h.startFetch(f)
        assertTrue(h.band.received.none { it.matches(8, 5) }, "onaysız silme yok")
        h.process(f.confirmStored(ids[0])); h.run()
        assertEquals(1, h.band.received.count { it.matches(8, 5) })
        assertEquals(listOf(ids[1]), h.band.files.keys.toList())
    }

    @Test
    fun missingPartsTimeOutAndContinue() {
        // Kayıttaki durum: ayrıntı dosyasının yalnızca 1. bölümü geliyor.
        val (h, ids) = setup(maxParts = 1)
        val f = B8Fetch()
        h.startFetch(f)
        assertEquals(1, requests(h).size)
        assertTrue(f.isRunning)
        h.tick(4_000)
        assertEquals(1, requests(h).size, "5 sn dolmadan atlanmaz")
        h.tick(1_500)
        val failed = h.fetchOut.filterIsInstance<B8Fetch.Out.Failed>()
        assertEquals(ids[0], failed.single().id)
        assertEquals(listOf(ids[1]), h.fetchOut.filterIsInstance<B8Fetch.Out.File>().map { it.file.id })
        assertTrue(h.fetchOut.last() is B8Fetch.Out.Done)
    }

    @Test
    fun unsolicitedFileIsDelivered() {
        val (h, _) = setup()
        val manualId = B8FileId(T0 + 500, 12, 1, 0, 6, 0)
        val f = B8Fetch()
        h.fetch = f
        h.deliver(h.band.pushUnsolicited(B8Activity.buildFile(manualId, hex("f0"))))
        val got = h.fetchOut.filterIsInstance<B8Fetch.Out.File>().single()
        assertEquals(manualId, got.file.id)
        assertTrue(!got.requested)
        assertTrue(h.band.received.none { it.matches(8, 5) })
    }

    @Test
    fun listTimeoutsDoNotHang() {
        val f = B8Fetch()
        val first = f.start(0)
        assertTrue((first.single() as B8Fetch.Out.Send).plain.contentEquals(B8Messages.filesToday()))
        assertEquals(0, f.onTick(9_999).size)
        val past = f.onTick(10_000)
        assertTrue((past.single() as B8Fetch.Out.Send).plain.contentEquals(B8Messages.filesPast()))
        assertTrue(f.onTick(20_000).single() is B8Fetch.Out.Done)
        assertTrue(!f.isRunning)
    }

    @Test
    fun duplicateIdsFetchedOnce() {
        val f = B8Fetch()
        f.start(0)
        val id = B8FileId(T0, 0, 3, 0, 0, 0)
        val list = B8Command.parse(B8Messages.command(8, 1).message(10, PbWriter().bytes(2, id.encode() + id.encode())).toByteArray())!!
        f.onCommand(list, 0)
        val past = B8Command.parse(B8Messages.command(8, 2).message(10, PbWriter().bytes(2, id.encode())).toByteArray())!!
        val out = f.onCommand(past, 0)
        assertEquals(1, out.size)
        assertEquals(id, f.currentFile)
        assertTrue(f.onTick(6_000).any { it is B8Fetch.Out.Done })
    }
}
