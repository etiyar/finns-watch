package com.che.ls05.protocol.band8

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Yalnızca yerelde: gerçek kayıt ve gerçek anahtarla doğrulama.
 * `B8_AUTH_KEY` ve `B8_CAPTURE_FRAMES` ortam değişkenleri yoksa hiçbir şey yapmaz (CI'da atlanır).
 * Çerçeve dosyası repo dışında durur. Satır biçimi: `TX|RX <tanıtıcı onaltılık> <değer onaltılık>`.
 * Hiçbir değer yazdırılmaz; yalnızca sayılar.
 */
class B8CaptureTest {
    @Test
    fun realCaptureDecryptsFully() {
        val keyText = System.getenv("B8_AUTH_KEY") ?: return
        val path = System.getenv("B8_CAPTURE_FRAMES") ?: return
        val key = B8Session.parseAuthKey(keyText) ?: error("B8_AUTH_KEY geçersiz")
        val charOf = mapOf(0x104 to B8Char.CMD_IN, 0x107 to B8Char.CMD_OUT, 0x10a to B8Char.ACTIVITY, 0x110 to B8Char.UPLOAD)

        var session: B8Session? = null
        val old = ArrayList<B8Session>()
        var phoneNonce: ByteArray? = null
        val reasm = HashMap<Pair<String, B8Char>, B8Reassembler>()
        val assembler = B8PartAssembler()
        var auths = 0; var phoneHmacOk = 0; var infoOk = 0
        var tx = 0; var rx = 0; var parts = 0; var uploads = 0; var oldSession = 0; var failed = 0
        var files = 0; var parsed = 0

        fun blob(dir: String, ch: B8Char, data: ByteArray, encrypted: Boolean) {
            if (!encrypted) {
                val c = B8Command.parse(data) ?: run { failed++; return }
                val auth = c.body(B8Type.F_AUTH)
                when {
                    dir == "TX" && c.matches(1, 26) -> phoneNonce = auth!!.message(30)!!.bytes(1)
                    dir == "RX" && c.matches(1, 26) -> {
                        val wn = auth!!.message(31)!!
                        val s = B8Session(key, phoneNonce!!, wn.bytes(1)!!)
                        assertTrue(s.bandHmacMatches(wn.bytes(2)!!), "band HMAC")
                        session?.let { old.add(it) }
                        session = s; auths++
                    }
                    dir == "TX" && c.matches(1, 27) -> {
                        val p = auth!!.message(32)!!
                        if (p.bytes(1)!!.contentEquals(session!!.phoneHmac())) phoneHmacOk++
                        if (session!!.decryptAsBand(p.bytes(2)!!, 0)?.let { PbMessage.parseOrNull(it) } != null) infoOk++
                    }
                }
                return
            }
            val s = session ?: run { failed++; return }
            if (dir == "TX") {
                if (ch == B8Char.UPLOAD) {
                    if (s.decryptAsBand(data, 0) != null) uploads++ else failed++
                    return
                }
                val ctr = ((data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)).toLong()
                val ct = data.copyOfRange(2, data.size)
                val p = s.decryptAsBand(ct, ctr)
                if (p != null && B8Command.parse(p) != null) { tx++; return }
                if (old.any { it.decryptAsBand(ct, ctr) != null }) { oldSession++; return }
                failed++
            } else {
                val p = s.decryptFromBand(data) ?: run { failed++; return }
                if (ch == B8Char.ACTIVITY) {
                    parts++
                    val r = assembler.add(p)
                    if (r is B8PartAssembler.Result.Complete) {
                        files++
                        if (B8ActivityParsers.parse(r.file) !is B8Parsed.Unsupported) parsed++
                    }
                } else if (B8Command.parse(p) != null) rx++ else failed++
            }
        }

        File(path).forEachLine { line ->
            val (dir, h, v) = line.trim().split(" ")
            val ch = charOf[h.toInt(16)] ?: return@forEachLine
            val value = hex(v)
            when (val ev = B8Frame.decode(value)) {
                is B8Frame.Event.Single -> blob(dir, ch, ev.payload, ev.encrypted)
                is B8Frame.Event.ChunkStart -> reasm.getOrPut(dir to ch) { B8Reassembler() }.start(ev.count, ev.encrypted)
                is B8Frame.Event.Chunk -> reasm[dir to ch]?.add(ev.index, ev.data)?.let { blob(dir, ch, it.first, it.second) }
                else -> {}
            }
        }
        println("Band 8 kayıt: auth=$auths telefonHmac=$phoneHmacOk cihazBilgisi=$infoOk gönderilen=$tx gelen=$rx " +
            "bölüm=$parts yükleme=$uploads eskiOturum=$oldSession tamDosya=$files ayrıştırılan=$parsed hata=$failed")
        assertEquals(3, auths)
        assertEquals(3, phoneHmacOk)
        assertEquals(3, infoOk)
        assertEquals(0, failed)
        assertEquals(1, oldSession)
        assertTrue(tx > 40 && rx > 20 && parts > 10 && uploads >= 10)
        assertTrue(files >= 8 && parsed == files)
    }
}
