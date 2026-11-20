package com.che.ls05.protocol.band8

/** 0xFE95 servisindeki karakteristikler (belge 1.2). Tanıtıcı değil, 16 bitlik UUID. */
enum class B8Char(val uuid16: Int) {
    /** Band komut gönderir, telefon onay yazar. */
    CMD_IN(0x0051),
    /** Telefon komut yazar, band onay gönderir. */
    CMD_OUT(0x0052),
    /** Etkinlik dosyaları. */
    ACTIVITY(0x0053),
    /** Veri yükleme. Bu aşamada kullanılmıyor. */
    UPLOAD(0x0055);

    val uuid: String get() = "0000%04x-0000-1000-8000-00805f9b34fb".format(uuid16)

    companion object {
        fun of(uuid16: Int): B8Char? = values().firstOrNull { it.uuid16 == uuid16 }
    }
}

/** "Şu karakteristiğe şunu yaz" eylemi. Uygulama tarafı bunu yanıtsız yazma ile yapar. */
class B8Write(val char: B8Char, val bytes: ByteArray)

sealed class B8Incoming {
    /** Çözülmüş Command (CMD_IN). Auth yanıtları şifresiz gelir, [encrypted] false olur. */
    class Command(val plain: ByteArray, val encrypted: Boolean) : B8Incoming()
    /** Çözülmüş etkinlik dosyası bölümü (ACTIVITY), 4 baytlık bölüm başlığıyla. */
    class ActivityPart(val plain: ByteArray) : B8Incoming()
    /** Etiketi tutmayan mesaj. Atılır. */
    class DecryptFailed(val char: B8Char) : B8Incoming()
    /** Band gönderimimizi reddetti. */
    class SendRejected(val code: Int) : B8Incoming()
}

/**
 * Karakteristik başına kuyruk, onay ve sayaç mantığı (belge 2.2, 2.3, 3.4).
 * Android'e bağlı değil. Girdi: gelen bildirimler. Çıktı: yazılacak çerçeveler ve çözülmüş mesajlar.
 *
 * - CMD_OUT'ta aynı anda tek mesaj bekler. Onay gelmeden sıradakini göndermez.
 * - Şifreli mesajlar düz hâlde kuyrukta durur, gönderilirken şifrelenir. Böylece yeniden auth
 *   olunca bekleyen mesajlar yeni oturumla ve 1'den başlayan sayaçla gider.
 * - Banddan gelen her tek pakete aynı karakteristiğe onay yazılır.
 */
class B8Channel(var mtu: Int = 247) {

    private class Out(val plain: ByteArray, val encrypted: Boolean)

    class Result(val writes: List<B8Write>, val incoming: List<B8Incoming>) {
        companion object { val EMPTY = Result(emptyList(), emptyList()) }
    }

    private enum class Wait { NONE, ACK, READY, ALL_RECEIVED }

    var session: B8Session? = null
        private set

    /** Son kullanılan sayaç. Oturum başında 0, ilk şifreli mesaj 1. */
    var counter: Long = 0
        private set

    private val queue = ArrayDeque<Out>()
    private var inFlight: Out? = null
    private var inFlightChunks: List<ByteArray> = emptyList()
    private var wait = Wait.NONE
    private val reassemblers = HashMap<B8Char, B8Reassembler>()

    val pending: Int get() = queue.size + (if (inFlight != null) 1 else 0)
    val busy: Boolean get() = inFlight != null

    /**
     * CMD_OUT'tan gelen kontrol çerçevesini bu kanal bekliyor mu. Beklenmeyen onay, "hazırım" ya da
     * "hepsi alındı" başka bir istemcinin aynı karakteristiğe yazdığını gösterir (#16).
     */
    fun expects(ev: B8Frame.Event): Boolean = when (ev) {
        is B8Frame.Event.Ack -> wait == Wait.ACK
        is B8Frame.Event.Ready -> wait == Wait.READY
        is B8Frame.Event.AllReceived, is B8Frame.Event.Resend -> wait == Wait.ALL_RECEIVED
        is B8Frame.Event.Nack, is B8Frame.Event.Rejected -> inFlight != null
        else -> true
    }

    /** Şifreli komut kuyruğa girer. Oturum yoksa auth bitene kadar bekler. */
    fun send(plain: ByteArray): List<B8Write> {
        queue.addLast(Out(plain, true))
        return pump()
    }

    /**
     * Yeni auth başlatır: oturum düşer, gönderilmekte olan şifreli mesaj kuyruğun başına döner,
     * auth mesajı şifresiz ve öncelikli gider.
     */
    fun beginAuth(plainAuth: ByteArray): List<B8Write> {
        session = null
        counter = 0
        requeueInFlight()
        queue.addFirst(Out(plainAuth, false))
        return pump()
    }

    /** Auth'un ikinci adımı gibi şifresiz devam mesajı. */
    fun sendPlain(plainAuth: ByteArray): List<B8Write> {
        queue.addFirst(Out(plainAuth, false))
        return pump()
    }

    /** Auth başarılı: sayaç 0'a döner, bekleyen mesajlar bu oturumla gider. */
    fun setSession(s: B8Session): List<B8Write> {
        session = s
        counter = 0
        return pump()
    }

    /** Bağlantı koptu: oturum ve yarım parçalar düşer, kuyruk korunur. */
    fun onDisconnected() {
        session = null
        counter = 0
        requeueInFlight()
        reassemblers.values.forEach { it.reset() }
    }

    /** Onay gelmedi: gönderilmekte olan mesaj atılır, sıradaki gönderilir. */
    fun onTimeout(): List<B8Write> {
        inFlight = null
        inFlightChunks = emptyList()
        wait = Wait.NONE
        return pump()
    }

    /** Kuyruğu ve gönderilmekte olanı siler. */
    fun clear() {
        queue.clear()
        inFlight = null
        inFlightChunks = emptyList()
        wait = Wait.NONE
    }

    private fun requeueInFlight() {
        val f = inFlight
        if (f != null && f.encrypted) queue.addFirst(f)
        inFlight = null
        inFlightChunks = emptyList()
        wait = Wait.NONE
    }

    private fun pump(): List<B8Write> {
        if (inFlight != null) return emptyList()
        val next = queue.firstOrNull() ?: return emptyList()
        val blob: ByteArray
        if (next.encrypted) {
            val s = session ?: return emptyList()
            if (counter >= 0xFFFF) return emptyList() // yeniden auth gerekir
            counter++
            blob = byteArrayOf((counter and 0xFF).toByte(), ((counter ushr 8) and 0xFF).toByte()) +
                s.encryptToBand(next.plain, counter)
        } else {
            blob = next.plain
        }
        queue.removeFirst()
        inFlight = next
        val split = B8Frame.split(blob, next.encrypted, mtu)
        inFlightChunks = split.chunks
        wait = if (split.isChunked) Wait.READY else Wait.ACK
        return listOf(B8Write(B8Char.CMD_OUT, split.first))
    }

    private fun finishInFlight(): List<B8Write> {
        inFlight = null
        inFlightChunks = emptyList()
        wait = Wait.NONE
        return pump()
    }

    fun onNotify(char: B8Char, value: ByteArray): Result {
        val ev = B8Frame.decode(value)
        if (char == B8Char.CMD_OUT) return onOwnChannel(ev)
        val writes = ArrayList<B8Write>()
        val incoming = ArrayList<B8Incoming>()
        val re = reassemblers.getOrPut(char) { B8Reassembler() }
        when (ev) {
            is B8Frame.Event.Single -> {
                writes.add(B8Write(char, B8Frame.ACK))
                deliver(char, ev.payload, ev.encrypted, incoming)
            }
            is B8Frame.Event.ChunkStart -> {
                re.start(ev.count, ev.encrypted)
                writes.add(B8Write(char, B8Frame.READY))
            }
            is B8Frame.Event.Chunk -> {
                val done = re.add(ev.index, ev.data)
                if (done != null) {
                    writes.add(B8Write(char, B8Frame.ALL_RECEIVED))
                    deliver(char, done.first, done.second, incoming)
                }
            }
            else -> {}
        }
        return Result(writes, incoming)
    }

    private fun deliver(char: B8Char, payload: ByteArray, encrypted: Boolean, out: MutableList<B8Incoming>) {
        val plain = if (encrypted) {
            session?.decryptFromBand(payload) ?: run { out.add(B8Incoming.DecryptFailed(char)); return }
        } else payload
        when (char) {
            B8Char.ACTIVITY -> out.add(B8Incoming.ActivityPart(plain))
            else -> out.add(B8Incoming.Command(plain, encrypted))
        }
    }

    private fun onOwnChannel(ev: B8Frame.Event): Result {
        return when (ev) {
            is B8Frame.Event.Ack ->
                if (wait == Wait.ACK) Result(finishInFlight(), emptyList()) else Result.EMPTY
            is B8Frame.Event.Ready ->
                if (wait == Wait.READY) {
                    wait = Wait.ALL_RECEIVED
                    Result(inFlightChunks.map { B8Write(B8Char.CMD_OUT, it) }, emptyList())
                } else Result.EMPTY
            is B8Frame.Event.AllReceived ->
                if (wait == Wait.ALL_RECEIVED) Result(finishInFlight(), emptyList()) else Result.EMPTY
            is B8Frame.Event.Resend ->
                if (wait == Wait.ALL_RECEIVED) {
                    Result(ev.indexes.mapNotNull { inFlightChunks.getOrNull(it - 1) }.map { B8Write(B8Char.CMD_OUT, it) }, emptyList())
                } else Result.EMPTY
            is B8Frame.Event.Nack -> rejected(ev.code)
            is B8Frame.Event.Rejected -> rejected(-1)
            else -> Result.EMPTY
        }
    }

    private fun rejected(code: Int): Result {
        if (inFlight == null) return Result.EMPTY
        return Result(finishInFlight(), listOf(B8Incoming.SendRejected(code)))
    }
}
