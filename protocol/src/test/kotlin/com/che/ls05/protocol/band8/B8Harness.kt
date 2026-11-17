package com.che.ls05.protocol.band8

/**
 * Kanal, auth ve dosya çekmeyi sahte banda bağlayan küçük test sürücüsü.
 * Uygulamadaki Band8Driver'ın yapacağı işin Android'siz hâli.
 */
internal class B8Harness(key: ByteArray, val band: FakeBand, random: (Int) -> ByteArray = { n -> ByteArray(n) { (it * 7 + 1).toByte() } }) {
    val ch = B8Channel()
    val auth = B8Auth(key, B8PhoneInfo(34, "Test Phone", "GB"), random)
    var authResult: B8Auth.Step? = null
    val inbox = ArrayList<B8Command>()
    val failures = ArrayList<B8Incoming>()
    var fetch: B8Fetch? = null
    val fetchOut = ArrayList<B8Fetch.Out>()
    var now = 0L
    /** true dönerse bu yazma banda ulaşmaz (kayıp taklidi). */
    var dropToBand: (B8Write) -> Boolean = { false }
    /** Banda giden bütün yazmalar, sırayla. */
    val written = ArrayList<B8Write>()
    private val wire = ArrayDeque<FakeBand.Notify>()

    fun write(ws: List<B8Write>) {
        for (w in ws) {
            written.add(w)
            if (dropToBand(w)) continue
            wire.addAll(band.onWrite(w.char, w.bytes))
        }
    }

    fun deliver(ns: List<FakeBand.Notify>) { wire.addAll(ns); run() }

    fun run() {
        var guard = 0
        while (wire.isNotEmpty()) {
            check(guard++ < 100_000) { "döngü bitmedi" }
            val n = wire.removeFirst()
            val r = ch.onNotify(n.char, n.bytes)
            write(r.writes)
            for (inc in r.incoming) handle(inc)
        }
    }

    private fun handle(inc: B8Incoming) {
        when (inc) {
            is B8Incoming.Command -> {
                val c = B8Command.parse(inc.plain) ?: return
                val step = auth.onCommand(c)
                if (step != null) {
                    when (step) {
                        is B8Auth.Step.Send -> write(ch.sendPlain(step.plain))
                        is B8Auth.Step.Success -> { authResult = step; write(ch.setSession(step.session)) }
                        is B8Auth.Step.Failed -> authResult = step
                    }
                    return
                }
                inbox.add(c)
                fetch?.let { process(it.onCommand(c, now)) }
            }
            is B8Incoming.ActivityPart -> fetch?.let { process(it.onActivityPart(inc.plain, now)) }
            else -> failures.add(inc)
        }
    }

    fun process(outs: List<B8Fetch.Out>) {
        for (o in outs) if (o is B8Fetch.Out.Send) write(ch.send(o.plain)) else fetchOut.add(o)
    }

    fun authenticate() { write(ch.beginAuth(auth.start())); run() }
    fun send(plain: ByteArray) { write(ch.send(plain)); run() }
    fun startFetch(f: B8Fetch) { fetch = f; process(f.start(now)); run() }
    fun tick(ms: Long) { now += ms; fetch?.let { process(it.onTick(now)) }; run() }
}
