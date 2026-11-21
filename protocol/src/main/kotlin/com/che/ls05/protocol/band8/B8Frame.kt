package com.che.ls05.protocol.band8

/**
 * Band 8 çerçeveleri (belge 2.1-2.3).
 *
 * İlk iki bayt küçük endian sıra numarasıdır. 0 ise kontrol paketi, üçüncü bayt türü verir.
 * 1 ve üstü ise parçalı bir mesajın parçasıdır.
 */
object B8Frame {
    val ACK = byteArrayOf(0, 0, 3, 0)
    val READY = byteArrayOf(0, 0, 1, 1)
    val ALL_RECEIVED = byteArrayOf(0, 0, 1, 0)

    /** ATT başlığı (3 bayt) düşülünce bir yazmaya sığan en büyük boy. */
    fun maxWrite(mtu: Int): Int = mtu - 3

    /** Şifresiz tek paket (yalnızca auth). */
    fun plainSingle(payload: ByteArray): ByteArray = byteArrayOf(0, 0, 2, 2) + payload

    /** Telefondan banda şifreli tek paket: sayaç (2 bayt LE) + şifreli veri. */
    fun encryptedSingle(blob: ByteArray): ByteArray = byteArrayOf(0, 0, 2, 1) + blob

    fun chunkStart(count: Int, encrypted: Boolean): ByteArray =
        byteArrayOf(0, 0, 0, if (encrypted) 1 else 0, (count and 0xFF).toByte(), (count ushr 8).toByte())

    fun chunk(index: Int, data: ByteArray): ByteArray =
        byteArrayOf((index and 0xFF).toByte(), (index ushr 8).toByte()) + data

    /**
     * Bir bloğu yazmalara böler. Tek pakete sığıyorsa tek eleman döner (tek paket çerçevesiyle).
     * Sığmıyorsa: ilk eleman parça başlatma, sonrakiler parçalar. Parçalar yalnızca band
     * "hazırım" dedikten sonra gönderilir.
     *
     * @param blob şifreli gönderimde: sayaç önü (varsa) + şifreli veri. Şifresizde: düz veri.
     * @param forceChunked veri yüklemede (0x0055) bölüm her zaman parçalı gider (kayıttaki gibi).
     */
    fun split(blob: ByteArray, encrypted: Boolean, mtu: Int, forceChunked: Boolean = false): Split {
        val max = maxWrite(mtu)
        if (!forceChunked && 4 + blob.size <= max) {
            val head = byteArrayOf(0, 0, 2, if (encrypted) 1 else 2)
            return Split(head + blob, emptyList())
        }
        val per = max - 2
        val chunks = ArrayList<ByteArray>()
        var p = 0
        var idx = 1
        while (p < blob.size) {
            val n = minOf(per, blob.size - p)
            chunks.add(chunk(idx, blob.copyOfRange(p, p + n)))
            p += n
            idx++
        }
        return Split(chunkStart(chunks.size, encrypted), chunks)
    }

    /** [first] hemen yazılır. [chunks] boş değilse "hazırım" beklendikten sonra yazılır. */
    class Split(val first: ByteArray, val chunks: List<ByteArray>) {
        val isChunked: Boolean get() = chunks.isNotEmpty()
    }

    /** Gelen tek bir bildirimin anlamı. */
    sealed class Event {
        object Ack : Event()
        class Nack(val code: Int) : Event()
        class ChunkStart(val count: Int, val encrypted: Boolean) : Event()
        object Ready : Event()
        object AllReceived : Event()
        object Rejected : Event()
        class Resend(val indexes: List<Int>) : Event()
        /** Tek paket. Şifreliyse [payload] şifreli veridir (banddan gelende sayaç yok). */
        class Single(val encrypted: Boolean, val payload: ByteArray) : Event()
        class Chunk(val index: Int, val data: ByteArray) : Event()
        class Unknown(val raw: ByteArray) : Event()
    }

    fun decode(v: ByteArray): Event {
        if (v.size < 2) return Event.Unknown(v)
        val seq = (v[0].toInt() and 0xFF) or ((v[1].toInt() and 0xFF) shl 8)
        if (seq != 0) return Event.Chunk(seq, v.copyOfRange(2, v.size))
        if (v.size < 4) return Event.Unknown(v)
        val kind = v[2].toInt() and 0xFF
        val sub = v[3].toInt() and 0xFF
        return when (kind) {
            0 -> if (v.size >= 6) Event.ChunkStart((v[4].toInt() and 0xFF) or ((v[5].toInt() and 0xFF) shl 8), sub == 1)
                 else Event.Unknown(v)
            1 -> when (sub) {
                0 -> Event.AllReceived
                1 -> Event.Ready
                2 -> Event.Rejected
                5 -> {
                    val idx = ArrayList<Int>()
                    var i = 4
                    while (i + 1 < v.size) { idx.add((v[i].toInt() and 0xFF) or ((v[i + 1].toInt() and 0xFF) shl 8)); i += 2 }
                    Event.Resend(idx)
                }
                else -> Event.Unknown(v)
            }
            2 -> when (sub) {
                1 -> Event.Single(true, v.copyOfRange(4, v.size))
                2 -> Event.Single(false, v.copyOfRange(4, v.size))
                else -> Event.Unknown(v)
            }
            3 -> if (sub == 0) Event.Ack else Event.Nack(sub)
            else -> Event.Unknown(v)
        }
    }
}

/**
 * Bir karakteristikte gelen parçalı mesajı birleştirir.
 * Parça başlatma ile sıfırlanır. Bütün parçalar gelince birleşik blok döner.
 */
class B8Reassembler {
    private var expected = 0
    private var encrypted = false
    private val parts = HashMap<Int, ByteArray>()

    val inProgress: Boolean get() = expected > 0

    fun start(count: Int, encrypted: Boolean) {
        expected = count
        this.encrypted = encrypted
        parts.clear()
    }

    /** Son parça gelince (blok, şifreli mi) döner. Beklenmeyen parça yok sayılır. */
    fun add(index: Int, data: ByteArray): Pair<ByteArray, Boolean>? {
        if (expected == 0 || index < 1 || index > expected) return null
        parts[index] = data
        if (parts.size < expected) return null
        var total = 0
        for (i in 1..expected) total += parts[i]!!.size
        val out = ByteArray(total)
        var p = 0
        for (i in 1..expected) { val d = parts[i]!!; System.arraycopy(d, 0, out, p, d.size); p += d.size }
        val enc = encrypted
        reset()
        return out to enc
    }

    fun missing(): List<Int> = if (expected == 0) emptyList() else (1..expected).filter { it !in parts }

    fun reset() { expected = 0; parts.clear() }
}
