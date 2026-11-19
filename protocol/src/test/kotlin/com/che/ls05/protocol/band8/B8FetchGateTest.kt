package com.che.ls05.protocol.band8

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Sağlık dosyası çekme kapısı: bildirimler önce gider, çekme kanal boşken ilerler. Veriler sentetik. */
class B8FetchGateTest {
    private val key = B8TestVectors.KEY
    private val T0 = 1_735_689_600L

    private fun sends(outs: List<B8Fetch.Out>) = outs.filterIsInstance<B8Fetch.Out.Send>()

    private fun setup(): Pair<B8Harness, List<B8FileId>> {
        val band = FakeBand(key)
        val detail = B8FileId(T0, 12, 3, 0, 0, 0)
        val summary = B8FileId(T0 + 75_000, 12, 5, 0, 0, 1)
        band.files[detail] = B8Activity.buildFile(detail, ByteArray(5 + 12 * 400))
        band.files[summary] = B8Activity.buildFile(summary, ByteArray(4 + 53))
        val h = B8Harness(key, band)
        h.authenticate()
        return h to listOf(detail, summary)
    }

    @Test
    fun holdsCommandWhileChannelBusy() {
        val g = B8FetchGate()
        assertEquals(0, sends(g.start(0)).size)
        assertEquals(0, sends(g.poll(0, channelIdle = false)).size)
        val out = sends(g.poll(0, channelIdle = true))
        assertTrue(out.single().plain.contentEquals(B8Messages.filesToday()))
    }

    @Test
    fun timeoutCountsFromActualSend() {
        val g = B8FetchGate()
        g.start(0)
        // Bildirimler 60 sn boyunca kanalı meşgul tutar: liste zaman aşımı işlemez.
        for (t in 0L..60_000L step 1_000) assertEquals(0, g.poll(t, channelIdle = false).size)
        assertTrue(sends(g.poll(60_000, channelIdle = true)).single().plain.contentEquals(B8Messages.filesToday()))
        assertEquals(0, g.poll(69_999, channelIdle = true).size)
        val past = sends(g.poll(70_000, channelIdle = true))
        assertTrue(past.single().plain.contentEquals(B8Messages.filesPast()))
    }

    @Test
    fun fetchesEveryFileOneIdPerRequestWithoutDelete() {
        val (h, ids) = setup()
        val g = B8FetchGate()
        h.gate = g
        h.process(g.start(h.now))
        h.pumpGate()
        val reqs = h.band.received.filter { it.matches(8, 3) }
        assertEquals(2, reqs.size)
        assertTrue(reqs.all { it.body(10)!!.bytes(2)!!.size == 7 })
        assertEquals(ids, h.fetchOut.filterIsInstance<B8Fetch.Out.File>().map { it.file.id })
        assertTrue(h.fetchOut.last() is B8Fetch.Out.Done)
        assertTrue(h.band.received.none { it.matches(8, 5) })
        assertTrue(!g.isRunning)
    }

    @Test
    fun pendingNotificationGoesBeforeNextFetchCommand() {
        val (h, _) = setup()
        val g = B8FetchGate()
        h.gate = g
        h.process(g.start(h.now))
        // Bildirim kuyrukta: kapı yoklansa bile 8/1 kanala girmez.
        val n = B8Notification("com.example.test", "Test", "T", "Gövde", "20250101T093000", 7, "k")
        val w = h.ch.send(B8Messages.notification(n))
        assertEquals(0, sends(g.poll(h.now, channelIdle = h.ch.pending == 0)).size)
        h.write(w)
        h.pumpGate()
        val order = h.band.received.map { it.type to it.subtype }
        assertTrue(order.indexOf(7 to 0) < order.indexOf(8 to 1), "bildirim önce: $order")
    }
}
