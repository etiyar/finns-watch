package com.che.ls05.protocol.band8

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PbTest {
    @Test
    fun varintAndTypeSubtype() {
        assertContentEquals(hex("08021001"), PbWriter().uint(1, 2).uint(2, 1).toByteArray())
        // 300 = ac 02
        assertContentEquals(hex("08ac02"), PbWriter().uint(1, 300).toByteArray())
    }

    @Test
    fun bigFieldNumbersLikeAuth() {
        // Auth alan 30, 31, 32, 33 iki baytlık etiket kullanır (kayıttaki f2 01, fa 01, 82 02, 8a 02).
        assertContentEquals(hex("f20100"), PbWriter().bytes(30, ByteArray(0)).toByteArray())
        assertContentEquals(hex("fa0100"), PbWriter().bytes(31, ByteArray(0)).toByteArray())
        assertContentEquals(hex("820200"), PbWriter().bytes(32, ByteArray(0)).toByteArray())
        assertContentEquals(hex("8a0200"), PbWriter().bytes(33, ByteArray(0)).toByteArray())
    }

    @Test
    fun zigzag() {
        // +3 saat = +12 çeyrek -> 24 (kayıttaki saat dilimi alanının kodlama biçimi)
        assertContentEquals(hex("0818"), PbWriter().sint(1, 12).toByteArray())
        assertContentEquals(hex("0807"), PbWriter().sint(1, -4).toByteArray())
        val m = PbMessage.parse(PbWriter().sint(1, -20).sint(2, 47).toByteArray())
        assertEquals(-20, m.sint(1))
        assertEquals(47, m.sint(2))
    }

    @Test
    fun floatAndString() {
        val b = PbWriter().float(2, 34f).string(5, "ĞŞ").toByteArray()
        assertContentEquals(hex("1500000842") + hex("2a04c49ec59e"), b)
        val m = PbMessage.parse(b)
        assertEquals(34f, m.float(2))
        assertEquals("ĞŞ", m.string(5))
    }

    @Test
    fun nestedAndRepeated() {
        val b = PbWriter().message(4, PbWriter().message(1, PbWriter().uint(1, 7)).message(1, PbWriter().uint(1, 9))).toByteArray()
        val outer = PbMessage.parse(b).message(4)!!
        assertEquals(listOf(7, 9), outer.messages(1).map { it.int(1) })
    }

    @Test
    fun negativeUint32IsMasked() {
        val m = PbMessage.parse(PbWriter().uint(1, -1).toByteArray())
        assertEquals(0xFFFFFFFFL, m.uint(1))
    }

    @Test
    fun malformed() {
        assertFailsWith<PbException> { PbMessage.parse(hex("0a05aabb")) }
        assertFailsWith<PbException> { PbMessage.parse(hex("08")) }
        assertFailsWith<PbException> { PbMessage.parse(hex("0f")) }
        assertNull(PbMessage.parseOrNull(hex("00")))
    }

    @Test
    fun missingFieldsAreNull() {
        val m = PbMessage.parse(hex("0801"))
        assertNull(m.int(2))
        assertNull(m.message(4))
        assertNull(m.string(1))
    }
}
