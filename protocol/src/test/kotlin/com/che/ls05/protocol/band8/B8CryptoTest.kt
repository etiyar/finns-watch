package com.che.ls05.protocol.band8

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class B8CryptoTest {
    @Test
    fun hkdfRfc5869Case1() {
        val okm = B8Hash.hkdf(ByteArray(22) { 0x0b }, hex("000102030405060708090a0b0c"), hex("f0f1f2f3f4f5f6f7f8f9"), 42)
        assertContentEquals(hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"), okm)
    }

    @Test
    fun ccmNistExample1FourByteTag() {
        val c = AesCcm(hex("404142434445464748494a4b4c4d4e4f"), 4)
        val out = c.encrypt(hex("10111213141516"), hex("20212223"), hex("0001020304050607"))
        assertContentEquals(hex("7162015b4dac255d"), out)
        assertContentEquals(hex("20212223"), c.decrypt(hex("10111213141516"), out, hex("0001020304050607")))
    }

    @Test
    fun ccmNistExample2() {
        val c = AesCcm(hex("404142434445464748494a4b4c4d4e4f"), 6)
        val out = c.encrypt(hex("1011121314151617"), hex("202122232425262728292a2b2c2d2e2f"), hex("000102030405060708090a0b0c0d0e0f"))
        assertContentEquals(hex("d2a1f0e051ea5f62081a7792073d593d1fc64fbfaccd"), out)
    }

    @Test
    fun ccmRfc3610Packet1() {
        val c = AesCcm(hex("c0c1c2c3c4c5c6c7c8c9cacbcccdcecf"), 8)
        val nonce = hex("00000003020100a0a1a2a3a4a5")
        val plain = ByteArray(23) { (it + 8).toByte() }
        val out = c.encrypt(nonce, plain, hex("0001020304050607"))
        assertContentEquals(hex("588c979a61c663d2f066d0c2c0f989806d5f6b61dac38417e8d12cfdf926e0"), out)
    }

    @Test
    fun ccmRejectsTamperedTag() {
        val c = AesCcm(hex("404142434445464748494a4b4c4d4e4f"), 4)
        val out = c.encrypt(hex("10111213141516"), hex("20212223"), hex("0001020304050607"))
        out[out.size - 1] = (out[out.size - 1].toInt() xor 1).toByte()
        assertNull(c.decrypt(hex("10111213141516"), out, hex("0001020304050607")))
        assertNull(c.decrypt(hex("10111213141516"), hex("0102"), ByteArray(0)))
    }

    @Test
    fun ccmEmptyAndMultiBlockRoundTrip() {
        val c = AesCcm(ByteArray(16) { it.toByte() }, 4)
        val n = ByteArray(12) { 7 }
        for (len in listOf(0, 1, 15, 16, 17, 100, 4092)) {
            val p = ByteArray(len) { (it * 31).toByte() }
            assertContentEquals(p, c.decrypt(n, c.encrypt(n, p)), "boy $len")
        }
    }

    @Test
    fun sessionGoldenVectors() {
        val v = B8TestVectors
        val s = B8Session(v.KEY, v.PHONE_NONCE, v.BAND_NONCE)
        assertContentEquals(v.HKDF64, B8Hash.hkdf(v.KEY, v.PHONE_NONCE + v.BAND_NONCE, B8Session.INFO, 64))
        assertContentEquals(v.BAND_HMAC, s.expectedBandHmac())
        assertContentEquals(v.PHONE_HMAC, s.phoneHmac())
        assertTrue(s.bandHmacMatches(v.BAND_HMAC))
        assertFalse(s.bandHmacMatches(v.PHONE_HMAC))
        assertContentEquals(v.TO_BAND_CTR1, s.encryptToBand(hex("08021001"), 1))
        assertContentEquals(v.TO_BAND_CTR0_LONG, s.encryptToBand(ByteArray(40) { it.toByte() }, 0))
        assertContentEquals(v.BAT_PLAIN, s.decryptFromBand(v.FROM_BAND))
        assertContentEquals(v.FROM_BAND, s.encryptAsBand(v.BAT_PLAIN))
    }

    @Test
    fun wrongCounterOrKeyFails() {
        val v = B8TestVectors
        val s = B8Session(v.KEY, v.PHONE_NONCE, v.BAND_NONCE)
        assertNull(s.decryptAsBand(v.TO_BAND_CTR1, 2))
        assertContentEquals(hex("08021001"), s.decryptAsBand(v.TO_BAND_CTR1, 1))
        val other = B8Session(ByteArray(16), v.PHONE_NONCE, v.BAND_NONCE)
        assertNull(other.decryptFromBand(v.FROM_BAND))
        assertFalse(other.bandHmacMatches(v.BAND_HMAC))
    }

    @Test
    fun parseAuthKey() {
        assertContentEquals(B8TestVectors.KEY, B8Session.parseAuthKey("0x984309BA053C0C4FD160802CB7E86B01"))
        assertContentEquals(B8TestVectors.KEY, B8Session.parseAuthKey(" 984309ba053c0c4fd160802cb7e86b01 "))
        assertNull(B8Session.parseAuthKey("984309ba053c0c4fd160802cb7e86b0"))
        assertNull(B8Session.parseAuthKey("g84309ba053c0c4fd160802cb7e86b01"))
        assertEquals(null, B8Session.parseAuthKey(""))
    }
}
