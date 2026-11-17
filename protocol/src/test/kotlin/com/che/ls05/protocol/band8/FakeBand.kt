package com.che.ls05.protocol.band8

/**
 * Testler için sahte Band 8. Kayıttaki band davranışını taklit eder:
 * onaylar, parçalı gönderim, sayaç denetimi, auth, pil, cihaz bilgisi, dosya listesi ve bölümler.
 * Bütün değerler sentetik.
 */
internal class FakeBand(
    private val authKey: ByteArray,
    private val bandNonce: ByteArray = ByteArray(16) { (0xA0 + it).toByte() },
    private val mtu: Int = 247,
) {
    class Notify(val char: B8Char, val bytes: ByteArray)

    var session: B8Session? = null
    private var phoneNonce: ByteArray? = null
    private var pendingSession: B8Session? = null
    private var lastCounter = 0L
    private val reasm = B8Reassembler()
    private val pendingChunks = HashMap<B8Char, List<ByteArray>>()

    /** Sahte bandın dosyaları: kimlik -> tam dosya baytları. */
    val files = LinkedHashMap<B8FileId, ByteArray>()
    /** 8/3'te bölümlerin yalnızca ilk N'ini gönder (kayıttaki eksik bölüm durumunu taklit). */
    var maxPartsPerFile = Int.MAX_VALUE

    val received = ArrayList<B8Command>()
    val counters = ArrayList<Long>()
    val acksFromPhone = HashMap<B8Char, Int>()
    var decryptFailures = 0
    var authOk = false

    fun onWrite(char: B8Char, v: ByteArray): List<Notify> {
        val out = ArrayList<Notify>()
        val ev = B8Frame.decode(v)
        if (char != B8Char.CMD_OUT) {
            when (ev) {
                is B8Frame.Event.Ack, is B8Frame.Event.AllReceived -> {
                    acksFromPhone.merge(char, 1, Int::plus)
                    if (char == B8Char.ACTIVITY) nextActivity(out)
                }
                is B8Frame.Event.Ready -> pendingChunks.remove(char)?.forEach { out.add(Notify(char, it)) }
                else -> {}
            }
            return out
        }
        when (ev) {
            is B8Frame.Event.Single -> {
                out.add(Notify(B8Char.CMD_OUT, B8Frame.ACK))
                handleBlob(ev.payload, ev.encrypted, out)
            }
            is B8Frame.Event.ChunkStart -> {
                reasm.start(ev.count, ev.encrypted)
                out.add(Notify(B8Char.CMD_OUT, B8Frame.READY))
            }
            is B8Frame.Event.Chunk -> {
                val done = reasm.add(ev.index, ev.data)
                if (done != null) {
                    out.add(Notify(B8Char.CMD_OUT, B8Frame.ALL_RECEIVED))
                    handleBlob(done.first, done.second, out)
                }
            }
            else -> {}
        }
        return out
    }

    private fun handleBlob(blob: ByteArray, encrypted: Boolean, out: MutableList<Notify>) {
        val plain: ByteArray
        if (encrypted) {
            val ctr = ((blob[0].toInt() and 0xFF) or ((blob[1].toInt() and 0xFF) shl 8)).toLong()
            val p = session?.decryptAsBand(blob.copyOfRange(2, blob.size), ctr)
            if (p == null) { decryptFailures++; return }
            if (ctr <= lastCounter) error("sayaç artmadı: $ctr <= $lastCounter")
            lastCounter = ctr
            counters.add(ctr)
            plain = p
        } else plain = blob
        val c = B8Command.parse(plain) ?: error("bozuk komut")
        received.add(c)
        handle(c, out)
    }

    private fun send(char: B8Char, plain: ByteArray, out: MutableList<Notify>) {
        val s = session!!
        val sp = B8Frame.split(s.encryptAsBand(plain), true, mtu)
        out.add(Notify(char, sp.first))
        if (sp.isChunked) pendingChunks[char] = sp.chunks
    }

    private fun sendPlain(plain: ByteArray, out: MutableList<Notify>) {
        out.add(Notify(B8Char.CMD_IN, B8Frame.plainSingle(plain)))
    }

    private fun handle(c: B8Command, out: MutableList<Notify>) {
        when {
            c.matches(1, 26) -> {
                val pn = c.body(3)!!.message(30)!!.bytes(1)!!
                phoneNonce = pn
                val s = B8Session(authKey, pn, bandNonce)
                pendingSession = s
                sendPlain(B8Messages.command(1, 26).message(3, PbWriter().message(31,
                    PbWriter().bytes(1, bandNonce).bytes(2, s.expectedBandHmac()))).toByteArray(), out)
            }
            c.matches(1, 27) -> {
                val s = pendingSession!!
                val proof = c.body(3)!!.message(32)!!
                val ok = proof.bytes(1)!!.contentEquals(s.phoneHmac()) &&
                    s.decryptAsBand(proof.bytes(2)!!, 0)?.let { PbMessage.parseOrNull(it)?.has(1) } == true
                authOk = ok
                if (ok) { session = s; lastCounter = 0 }
                sendPlain(B8Messages.command(1, 27).message(3, PbWriter().message(33,
                    PbWriter().uint(1, if (ok) 1 else 2).uint(2, 1234))).toByteArray(), out)
            }
            c.matches(2, 1) -> send(B8Char.CMD_IN, B8TestVectors.BAT_PLAIN, out)
            c.matches(2, 2) -> send(B8Char.CMD_IN, B8Messages.command(2, 2).message(4, PbWriter().message(3,
                PbWriter().string(1, "SERIAL-TEST").string(2, "9.9.9").string(3, "").string(4, "TESTMODEL"))).toByteArray(), out)
            c.matches(8, 1) -> send(B8Char.CMD_IN, B8Messages.command(8, 1).message(10,
                PbWriter().bytes(2, files.keys.fold(ByteArray(0)) { a, id -> a + id.encode() })).toByteArray(), out)
            c.matches(8, 2) -> send(B8Char.CMD_IN, B8Messages.command(8, 2).message(10, PbWriter().bytes(2, ByteArray(0))).toByteArray(), out)
            c.matches(8, 3) -> {
                val ids = c.body(10)!!.bytes(2)!!
                for (k in 0 until ids.size / 7) {
                    val id = B8FileId.decode(ids, k * 7)
                    val f = files[id] ?: continue
                    B8Activity.splitParts(f).take(maxPartsPerFile).forEach { part -> sendActivityPart(part, out) }
                }
            }
            c.matches(8, 5) -> files.remove(B8FileId.decode(c.body(10)!!.bytes(3)!!))
            // Diğerleri (saat, bildirim, bul): yalnızca kaydedilir.
        }
    }

    /** Etkinlik bölümleri sırayla gider: bir sonraki, telefon öncekini onaylayınca (kayıttaki gibi). */
    private val activityBacklog = ArrayDeque<B8Frame.Split>()
    private var activityBusy = false

    private fun sendActivityPart(part: ByteArray, out: MutableList<Notify>) {
        activityBacklog.addLast(B8Frame.split(session!!.encryptAsBand(part), true, mtu))
        if (!activityBusy) nextActivity(out)
    }

    private fun nextActivity(out: MutableList<Notify>) {
        val sp = activityBacklog.removeFirstOrNull()
        activityBusy = sp != null
        if (sp == null) return
        out.add(Notify(B8Char.ACTIVITY, sp.first))
        if (sp.isChunked) pendingChunks[B8Char.ACTIVITY] = sp.chunks
    }

    /** Band kendiliğinden dosya gönderir (elle ölçüm gibi). */
    fun pushUnsolicited(file: ByteArray): List<Notify> {
        val out = ArrayList<Notify>()
        B8Activity.splitParts(file).forEach { sendActivityPart(it, out) }
        return out
    }

    fun encryptForPhone(plain: ByteArray): ByteArray = session!!.encryptAsBand(plain)
}
