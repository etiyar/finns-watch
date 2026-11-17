package com.che.ls05.protocol.band8

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sentetik dosyalar. Yapı, çözülmüş kayıttaki dosyalarla aynı; değerler uydurma. */
class B8ActivityTest {
    private val T0 = 1_735_689_600L // sentetik başlangıç zamanı

    /** Kayıttaki ayrıntı dosyası başlığının yapısı: bütün gruplar var, 12 baytlık kayıt. */
    private val HEADER_V3 = hex("9accc88cc8")

    private fun detailRecord(steps: Int, hr: Int, spo2: Int, stress: Int): ByteArray {
        val r = ByteArray(12)
        r[0] = (steps and 0xFF).toByte(); r[1] = ((steps ushr 8) and 0x3F).toByte()
        r[6] = hr.toByte(); r[10] = spo2.toByte(); r[11] = stress.toByte()
        return r
    }

    @Test
    fun fileIdFlagsLikeCapture() {
        val detail = B8FileId.decode(hex("00000000") + hex("0c0300"))
        assertTrue(detail.isDailyDetail); assertEquals(3, detail.version); assertEquals(12, detail.tzQuarters)
        val summary = B8FileId.decode(hex("00000000") + hex("0c0501"))
        assertTrue(summary.isDailySummary); assertEquals(5, summary.version)
        val manual = B8FileId.decode(hex("00000000") + hex("0c0118"))
        assertTrue(manual.isManual); assertEquals(6, manual.subtype); assertEquals(0, manual.detailType)
        val sleep = B8FileId(T0, 0, 2, 0, 8, 0)
        assertTrue(sleep.isSleep)
        assertEquals(0x20, sleep.encode()[6].toInt())
        val neg = B8FileId(T0, -20, 1, 1, 3, 1)
        assertEquals(neg, B8FileId.decode(neg.encode()))
        assertEquals(0xFFFFFFFFL, B8FileId.decode(hex("ffffffff000000")).utcSeconds)
    }

    @Test
    fun crc() {
        val f = B8Activity.buildFile(B8FileId(T0, 0, 1, 0, 6, 0), hex("0102030405"))
        assertTrue(B8Activity.crcOk(f))
        f[9] = 9
        assertFalse(B8Activity.crcOk(f))
        assertEquals(0xCBF43926L, B8Activity.crc32("123456789".toByteArray()))
    }

    @Test
    fun partAssemblerMultiPart() {
        val id = B8FileId(T0, 12, 3, 0, 0, 0)
        val body = HEADER_V3 + ByteArray(12 * 1300)
        val file = B8Activity.buildFile(id, body)
        val parts = B8Activity.splitParts(file)
        assertEquals(4, parts.size)
        assertContentEquals(hex("04000100"), parts[0].copyOf(4))
        assertEquals(4096, parts[0].size)
        val a = B8PartAssembler()
        for (i in 0 until 3) assertTrue(a.add(parts[i]) is B8PartAssembler.Result.NeedMore)
        val r = a.add(parts[3]) as B8PartAssembler.Result.Complete
        assertEquals(id, r.file.id)
        assertContentEquals(file, r.file.bytes)
    }

    @Test
    fun partAssemblerErrors() {
        val file = B8Activity.buildFile(B8FileId(T0, 0, 3, 0, 0, 0), ByteArray(9000))
        val parts = B8Activity.splitParts(file)
        val a = B8PartAssembler()
        a.add(parts[0])
        assertTrue(a.add(parts[2]) is B8PartAssembler.Result.Failed, "atlanan bölüm")
        assertFalse(a.inProgress)
        // Sıra 1 tamponu sıfırlar: yarım dosyadan sonra yeni dosya düzgün gelir.
        a.add(parts[0])
        val single = B8Activity.splitParts(B8Activity.buildFile(B8FileId(T0, 0, 1, 0, 6, 0), hex("f0")))
        assertTrue(a.add(single[0]) is B8PartAssembler.Result.Complete)
        val bad = B8Activity.buildFile(B8FileId(T0, 0, 1, 0, 6, 0), hex("f0"))
        bad[bad.size - 1] = (bad[bad.size - 1].toInt() xor 0xFF).toByte()
        assertTrue(a.add(B8Activity.splitParts(bad)[0]) is B8PartAssembler.Result.Failed)
        assertTrue(a.add(hex("0000")) is B8PartAssembler.Result.Failed)
    }

    @Test
    fun dailyDetailV3() {
        val id = B8FileId(T0, 12, 3, 0, 0, 0)
        val body = HEADER_V3 + detailRecord(0, 0, 0, 255) + detailRecord(87, 72, 97, 30) + detailRecord(16383, 255, 0, 255) + hex("0102")
        val p = B8ActivityParsers.parse(B8ActivityFile(id, B8Activity.buildFile(id, body))) as B8Parsed.DailyDetail
        assertEquals(3, p.records.size, "artık 2 bayt kayıt sayılmaz")
        val r0 = p.records[0]
        assertEquals(T0, r0.utcSeconds); assertEquals(12, r0.tzQuarters)
        assertEquals(0, r0.steps); assertNull(r0.heartRate); assertNull(r0.spo2); assertNull(r0.stress)
        val r1 = p.records[1]
        assertEquals(T0 + 60, r1.utcSeconds)
        assertEquals(87, r1.steps); assertEquals(72, r1.heartRate); assertEquals(97, r1.spo2); assertEquals(30, r1.stress)
        assertEquals(16383, p.records[2].steps)
        assertNull(p.records[2].heartRate)
        assertEquals(T0 + 120, p.records[2].utcSeconds)
    }

    @Test
    fun dailyDetailExtraFlagAndSparseHeader() {
        val id = B8FileId(T0, 0, 3, 0, 0, 0)
        // Yalnızca grup 0 (adım + ek alan bayrağı geçerli) ve ek grup (grup 9) var.
        val header = hex("b0000000" + "08")
        val rec = byteArrayOf(5, 0x40, 0x77) // adım 5, ek bayrak 1, ek bayt
        val body = header + rec + byteArrayOf(9, 0)
        val p = B8ActivityParsers.parse(B8ActivityFile(id, B8Activity.buildFile(id, body))) as B8Parsed.DailyDetail
        assertEquals(listOf(5, 9), p.records.map { it.steps })
        val v4 = B8FileId(T0, 0, 4, 0, 0, 0)
        val p4 = B8ActivityParsers.parse(B8ActivityFile(v4, B8Activity.buildFile(v4, hex("900000000088") + hex("0300aaaabbbb")))) as B8Parsed.DailyDetail
        assertEquals(listOf(3), p4.records.map { it.steps })
    }

    @Test
    fun dailyDetailEmptyHeaderDoesNotLoop() {
        val id = B8FileId(T0, 0, 3, 0, 0, 0)
        val p = B8ActivityParsers.parse(B8ActivityFile(id, B8Activity.buildFile(id, ByteArray(5) + ByteArray(10)))) as B8Parsed.DailyDetail
        assertEquals(0, p.records.size)
        val v9 = B8FileId(T0, 0, 9, 0, 0, 0)
        assertTrue(B8ActivityParsers.parse(B8ActivityFile(v9, B8Activity.buildFile(v9, ByteArray(20)))) is B8Parsed.Unsupported)
    }

    @Test
    fun dailySummaryV5() {
        val id = B8FileId(T0, 12, 5, 0, 0, 1)
        val w = java.io.ByteArrayOutputStream()
        fun u8(v: Int) = w.write(v)
        fun u16(v: Int) { u8(v and 0xFF); u8(v ushr 8 and 0xFF) }
        fun u32(v: Long) { u16((v and 0xFFFF).toInt()); u16((v ushr 16).toInt()) }
        w.write(hex("ffffffff"))
        u32(12345); u8(0); u8(0); u8(0)
        u8(61); u8(150); u32(T0 + 100); u8(48); u32(T0 + 200); u8(70)
        u8(30); u8(80); u8(5)
        u16(0x0102); u8(0x03)
        u16(321); u8(0); u8(0); u8(0)
        u8(99); u32(T0 + 300); u8(91); u32(T0 + 400); u8(96)
        u16(0); u16(0); u8(0); u8(0); u8(0); u8(0); u16(0)
        u16(0) // Band 8'de görülen fazladan 2 bayt
        val body = w.toByteArray()
        assertEquals(4 + 53, body.size)
        val p = B8ActivityParsers.parse(B8ActivityFile(id, B8Activity.buildFile(id, body))) as B8Parsed.DailySummary
        val s = p.summary
        assertEquals(12345L, s.steps); assertEquals(61, s.hrResting); assertEquals(150, s.hrMax); assertEquals(T0 + 100, s.hrMaxUtc)
        assertEquals(48, s.hrMin); assertEquals(70, s.hrAvg); assertEquals(30, s.stressAvg)
        assertEquals(0x030102, s.standing); assertEquals(321, s.calories)
        assertEquals(99, s.spo2Max); assertEquals(T0 + 400, s.spo2MinUtc); assertEquals(96, s.spo2Avg)
        assertEquals(12, s.tzQuarters)
        val short = B8Activity.buildFile(id, ByteArray(20))
        assertTrue(B8ActivityParsers.parse(B8ActivityFile(id, short)) is B8Parsed.Unsupported)
    }

    @Test
    fun manualV1LikeCaptureAndV2() {
        val v1 = B8FileId(T0, 12, 1, 0, 6, 0)
        fun rec(dt: Long, type: Int, v: Int): ByteArray {
            val t = T0 + dt
            return byteArrayOf((t and 0xFF).toByte(), (t ushr 8 and 0xFF).toByte(), (t ushr 16 and 0xFF).toByte(), (t ushr 24 and 0xFF).toByte(), type.toByte(), v.toByte())
        }
        val body1 = hex("f0") + rec(-2, 1, 80) + rec(-1, 1, 0) + rec(0, 1, 82)
        val p1 = B8ActivityParsers.parse(B8ActivityFile(v1, B8Activity.buildFile(v1, body1))) as B8Parsed.Manual
        assertEquals(listOf(80, 82), p1.samples.map { it.value })
        assertEquals(listOf(T0 - 2, T0), p1.samples.map { it.utcSeconds })
        assertEquals(12, p1.samples[0].tzQuarters)
        val v2 = B8FileId(T0, 0, 2, 0, 6, 0)
        val body2 = rec(0, 2, 97) + rec(1, 4, 1) + byteArrayOf(0, 0, 0) + rec(2, 99, 1)
        val p2 = B8ActivityParsers.parse(B8ActivityFile(v2, B8Activity.buildFile(v2, body2))) as B8Parsed.Manual
        assertEquals(listOf(2, 4), p2.samples.map { it.type })
        assertEquals(1, p2.samples[1].value)
    }

    @Test
    fun sleepIsMarkedUnsupported() {
        val id = B8FileId(T0, 0, 2, 0, 8, 0)
        val r = B8ActivityParsers.parse(B8ActivityFile(id, B8Activity.buildFile(id, ByteArray(30))))
        assertTrue(r is B8Parsed.Unsupported)
    }
}
