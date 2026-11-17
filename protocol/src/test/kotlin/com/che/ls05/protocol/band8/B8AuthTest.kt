package com.che.ls05.protocol.band8

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class B8AuthTest {
    private val v = B8TestVectors
    private val info = B8PhoneInfo(34, "Test Phone", "GB")

    private fun bandNonceReply(nonce: ByteArray, hmac: ByteArray) = B8Command.parse(
        B8Messages.command(1, 26).message(3, PbWriter().message(31, PbWriter().bytes(1, nonce).bytes(2, hmac))).toByteArray())!!

    private fun result(ok: Int) = B8Command.parse(
        B8Messages.command(1, 27).message(3, PbWriter().message(33, PbWriter().uint(1, ok).uint(2, 1234))).toByteArray())!!

    @Test
    fun goldenHandshake() {
        val a = B8Auth(v.KEY, info) { v.PHONE_NONCE.copyOf() }
        val first = B8Command.parse(a.start())!!
        assertContentEquals(v.PHONE_NONCE, first.body(3)!!.message(30)!!.bytes(1))
        val step = a.onCommand(bandNonceReply(v.BAND_NONCE, v.BAND_HMAC)) as B8Auth.Step.Send
        val proof = B8Command.parse(step.plain)!!.body(3)!!.message(32)!!
        assertContentEquals(v.PHONE_HMAC, proof.bytes(1))
        // Cihaz bilgisi telefon anahtarı ve sayaç 0 ile şifreli.
        val s = B8Session(v.KEY, v.PHONE_NONCE, v.BAND_NONCE)
        assertContentEquals(B8Messages.phoneInfo(info), s.decryptAsBand(proof.bytes(2)!!, 0))
        val ok = a.onCommand(result(1)) as B8Auth.Step.Success
        assertContentEquals(v.BAND_HMAC, ok.session.expectedBandHmac())
    }

    @Test
    fun wrongKey() {
        val a = B8Auth(ByteArray(16) { 1 }, info) { v.PHONE_NONCE.copyOf() }
        a.start()
        val f = a.onCommand(bandNonceReply(v.BAND_NONCE, v.BAND_HMAC)) as B8Auth.Step.Failed
        assertEquals(B8Auth.Reason.WRONG_KEY, f.reason)
    }

    @Test
    fun bandRefusesAndNotConfirmed() {
        val a = B8Auth(v.KEY, info) { v.PHONE_NONCE.copyOf() }
        a.start()
        val refused = B8Command.parse(B8Messages.command(1, 26).message(3, PbWriter().uint(3, 4)).toByteArray())!!
        val f = a.onCommand(refused) as B8Auth.Step.Failed
        assertEquals(B8Auth.Reason.BAND_REFUSED, f.reason)
        assertEquals(4, f.status)

        val b = B8Auth(v.KEY, info) { v.PHONE_NONCE.copyOf() }
        b.start()
        b.onCommand(bandNonceReply(v.BAND_NONCE, v.BAND_HMAC))
        assertEquals(B8Auth.Reason.NOT_CONFIRMED, (b.onCommand(result(2)) as B8Auth.Step.Failed).reason)
    }

    @Test
    fun malformedAndIgnored() {
        val a = B8Auth(v.KEY, info) { v.PHONE_NONCE.copyOf() }
        a.start()
        assertNull(a.onCommand(B8Command.parse(hex("08021001"))!!), "auth olmayan mesaj")
        assertNull(a.onCommand(result(1)), "sırası gelmemiş adım")
        val f = a.onCommand(bandNonceReply(ByteArray(5), ByteArray(32))) as B8Auth.Step.Failed
        assertEquals(B8Auth.Reason.MALFORMED, f.reason)
    }

    @Test
    fun eachStartUsesFreshNonce() {
        var n = 0
        val a = B8Auth(v.KEY, info) { size -> ByteArray(size) { (n + it).toByte() }.also { n++ } }
        val x = B8Command.parse(a.start())!!.body(3)!!.message(30)!!.bytes(1)!!
        val y = B8Command.parse(a.start())!!.body(3)!!.message(30)!!.bytes(1)!!
        assertTrue(!x.contentEquals(y))
    }
}
