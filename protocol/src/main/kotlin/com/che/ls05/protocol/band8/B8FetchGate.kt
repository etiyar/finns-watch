package com.che.ls05.protocol.band8

/**
 * Sağlık dosyası çekmeyi komut kanalına bağlar. Bildirimler öncelikli kalsın diye çekme komutu
 * yalnızca kanal boşken çıkar. Komut beklerken zaman aşımı işlemez; süre gönderimle başlar.
 * 8/5 (silme) hiç üretilmez: [B8Fetch] silme izni olmadan kurulur.
 *
 * Çağıran her olaydan sonra ve düzenli aralıklarla (örnek saniyede bir) [poll] çağırır.
 * [B8Fetch.Out.Send] yalnızca [poll] dönüşünde gelir.
 */
class B8FetchGate(partTimeoutMs: Long = 5_000, listTimeoutMs: Long = 10_000) {
    private val fetch = B8Fetch(allowDelete = false, partTimeoutMs = partTimeoutMs, listTimeoutMs = listTimeoutMs)
    private val outbox = ArrayDeque<ByteArray>()

    val isRunning: Boolean get() = fetch.isRunning || outbox.isNotEmpty()

    fun start(nowMs: Long): List<B8Fetch.Out> {
        outbox.clear()
        return absorb(fetch.start(nowMs))
    }

    fun onCommand(c: B8Command, nowMs: Long): List<B8Fetch.Out> = absorb(fetch.onCommand(c, nowMs))

    /** İstenmeden gelen dosyalar da (elle ölçüm) burada teslim edilir. */
    fun onActivityPart(part: ByteArray, nowMs: Long): List<B8Fetch.Out> = absorb(fetch.onActivityPart(part, nowMs))

    /** Zaman aşımını denetler ve kanal boşsa bekleyen tek komutu verir. */
    fun poll(nowMs: Long, channelIdle: Boolean): List<B8Fetch.Out> {
        val out = if (outbox.isEmpty()) absorb(fetch.onTick(nowMs)).toMutableList() else mutableListOf()
        if (channelIdle && outbox.isNotEmpty()) {
            out += B8Fetch.Out.Send(outbox.removeFirst())
            fetch.restartTimer(nowMs)
        }
        return out
    }

    private fun absorb(outs: List<B8Fetch.Out>): List<B8Fetch.Out> {
        val rest = ArrayList<B8Fetch.Out>()
        for (o in outs) if (o is B8Fetch.Out.Send) outbox.addLast(o.plain) else rest += o
        return rest
    }
}
