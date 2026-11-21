package com.che.ls05.protocol.band8

import com.che.ls05.protocol.band8.B8FaceBin.Element
import com.che.ls05.protocol.band8.B8FaceBin.Image
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Kadran üretici ve denetleyici. Beklenen yapı kullanıcının gerçek kadran dosyalarından (belge 3-5). */
class B8FaceBinTest {
    private val u16 = B8FaceBin::u16
    private val u32 = B8FaceBin::u32

    private fun face(): ByteArray = B8FaceDesign.simpleDigital(B8FaceDesign.Spec("167299001", "Finn Dijital"))

    @Test
    fun rleRoundTrip() {
        val img = B8FaceDesign.digit(8, 20, 30, 4, B8FaceDesign.WHITE)
        val pix = B8FaceBin.pixels(img, 4)
        val c = B8FaceBin.compressed(pix, 4)
        assertContentEquals(hex("e021a55a"), c.copyOf(4))
        assertEquals((pix.size.toLong() shl 4) or 4, u32(c, 4))
        val (raw, bpp) = B8FaceBin.unpack(c, 0, c.size)!!
        assertEquals(4, bpp)
        assertContentEquals(pix, raw)
        // 127'den uzun tekrar bölünür.
        val flat = B8FaceBin.rle(ByteArray(2 * 300), 2)
        assertEquals(listOf(127, 127, 46), (0 until flat.size / 3).map { flat[it * 3].toInt() and 0xFF })
    }

    @Test
    fun rleLiteralRecordDecodes() {
        // Cihaz örneklerinde düz kayıt da var: üst bit 1, ardından n piksel.
        val payload = hex("e021a55a") + B8Upload.blob(0, ByteArray(0)).copyOf(0) +
            byteArrayOf(0x42, 0, 0, 0) + byteArrayOf(0x82.toByte(), 1, 2, 3, 4, 0x02, 9, 9)
        val info = (8L shl 4) or 2
        for (i in 0 until 4) payload[4 + i] = (info ushr (8 * i)).toByte()
        val (raw, _) = B8FaceBin.unpack(payload, 0, payload.size)!!
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 9, 9, 9, 9), raw)
    }

    @Test
    fun rleRejectsBadData() {
        val c = B8FaceBin.compressed(ByteArray(40), 2)
        assertNull(B8FaceBin.unpack(c, 0, c.size - 1))
        val bad = c.copyOf(); bad[0] = 0
        assertNull(B8FaceBin.unpack(bad, 0, bad.size))
        val zero = c.copyOf(); zero[8] = 0
        assertNull(B8FaceBin.unpack(zero, 0, zero.size))
    }

    @Test
    fun imageBlocksMatchSampleConventions() {
        // Saydamsız düz görüntü: işaret 3 (RGB565), bayrak 4 (sıkıştırılmış).
        val bg = B8FaceBin.imageBlock(B8FaceDesign.solid(192, 490, B8FaceDesign.BLACK))
        assertEquals(3, bg[0].toInt()); assertEquals(4, bg[1].toInt())
        assertEquals(192, u16(bg, 4)); assertEquals(490, u16(bg, 6))
        assertEquals((bg.size - 12).toLong(), u32(bg, 8))
        assertEquals((192L * 490 * 2 shl 4) or 2, u32(bg, 16))
        // Saydam görüntü: işaret 0 (BGRA).
        assertEquals(0, B8FaceBin.imageBlock(B8FaceDesign.digit(1, 10, 20, 3, -1))[0].toInt())
        // Liste: işaret 0, sayı, bayrak 4; toplam = yalnızca yükler.
        val l = B8FaceBin.listBlock(B8FaceDesign.digits(10, 20, 3, -1))
        assertEquals(0, l[0].toInt()); assertEquals(10, l[1].toInt()); assertEquals(4, l[2].toInt())
        val sum = (0 until 10).sumOf { u32(l, 12 + 4 * it) }
        assertEquals(sum, u32(l, 8))
        assertEquals(12 + 40 + sum.toInt(), l.size)
    }

    @Test
    fun headerAndLayout() {
        val b = face()
        assertContentEquals(B8FaceBin.MAGIC, b.copyOf(4))
        assertEquals(0x800L, u32(b, 0x10))
        assertEquals(2, b[0x1C].toInt()); assertEquals(4, b[0x1E].toInt())
        assertEquals("167299001", String(b, 0x28, 9))
        assertEquals("Finn Dijital", String(b, 0x68, 12))
        assertEquals(0, b[0x68 + 12].toInt())
        assertEquals(0, b.size % 4)
        // Kadran başlıkları: ilk sözcük 0x02000000, önizleme yalnızca normal ekranda.
        val prev = u32(b, 0x20)
        assertEquals(0x02000000L, u32(b, 0xA8)); assertEquals(prev, u32(b, 0xA8 + 4))
        assertEquals(0x02000000L, u32(b, 0x100)); assertEquals(0L, u32(b, 0x100 + 4))
        // Tanımlar başlıklardan hemen sonra; önizleme dosyanın sonunda, 122 x 310 RGB565.
        assertEquals(0x158L, u32(b, 0xA8 + 0x0C))
        assertEquals(0L, prev % 4)
        val p = prev.toInt()
        assertEquals(3, b[p].toInt()); assertEquals(122, u16(b, p + 4)); assertEquals(310, u16(b, p + 6))
        assertTrue(b.size - (p + 12 + u32(b, p + 8).toInt()) in 0..3)
        // Normal ekranda 6 öğe, 2 düz görüntü, 4 liste, 4 sayı; AOD'de 3 öğe, 1 düz görüntü, 2 liste, 2 sayı.
        assertEquals(listOf(6L, 2L, 4L, 4L), listOf(0x08, 0x18, 0x20, 0x40).map { u32(b, 0xA8 + it) })
        assertEquals(listOf(3L, 1L, 2L, 2L), listOf(0x08, 0x18, 0x20, 0x40).map { u32(b, 0x100 + it) })
        assertEquals(emptyList<String>(), B8FaceBin.check(b))
        assertTrue(b.size < 200_000, "boy ${b.size}")
    }

    @Test
    fun widgetBytesMatchSample() {
        val b = face()
        // Normal ekranın ilk sayı tanımı (saat).
        val wDefs = u32(b, 0xA8 + 0x44).toInt()
        val off = u32(b, wDefs + 8).toInt()
        assertEquals(20L, u32(b, wDefs + 12))
        // Cihaz örneğindeki saat: 08 11 02 15 .. liste tanımı, tür 3, aralık.
        assertContentEquals(hex("08110215 00000000 000000 03 00 0a 0000 00000000"), b.copyOfRange(off, off + 20))
        // Öğe 0: arka plan (görüntü 0, tür 2) konum 0,0; öğe 1: sayı 0 (tür 7).
        val el = u32(b, u32(b, 0xA8 + 0x0C).toInt() + 8).toInt()
        assertContentEquals(hex("00000002 0000 0000 0000000000000000"), b.copyOfRange(el, el + 16))
        assertContentEquals(hex("00000007"), b.copyOfRange(el + 16, el + 20))
    }

    @Test
    fun blocksAreAlignedAndInside() {
        val b = face()
        for (f in 0 until 2) {
            val h = 0xA8 + f * 0x58
            for (sec in listOf(0x18, 0x20)) {
                val n = u32(b, h + sec).toInt(); val o = u32(b, h + sec + 4).toInt()
                for (i in 0 until n) {
                    val at = u32(b, o + 16 * i + 8); val len = u32(b, o + 16 * i + 12)
                    assertEquals(0L, at % 4)
                    assertTrue(at + len <= b.size)
                }
            }
        }
    }

    @Test
    fun checkerCatchesDamage() {
        val b = face()
        assertTrue(B8FaceBin.check(b.copyOf(100)).isNotEmpty())
        assertTrue(B8FaceBin.check(b.copyOf().also { it[2] = 0 }).isNotEmpty())
        // Bir tanımı dosya dışına it.
        val far = b.copyOf(); B8FaceBin.le32(far, u32(b, 0xA8 + 0x1C).toInt() + 8, 0x7FFFFFF0)
        assertTrue(B8FaceBin.check(far).isNotEmpty())
        // Arka planın RLE verisini boz.
        val rle = b.copyOf(); val bg = u32(b, u32(b, 0xA8 + 0x1C).toInt() + 8).toInt(); rle[bg + 20] = 0
        assertTrue(B8FaceBin.check(rle).isNotEmpty())
        // Kimlik rakam değil.
        assertTrue(B8FaceBin.check(b.copyOf().also { it[0x28] = 'x'.code.toByte() }).isNotEmpty())
        // Öğe olmayan tanıma bağlı.
        val ref = b.copyOf(); val el = u32(b, u32(b, 0xA8 + 0x0C).toInt() + 8).toInt(); ref[el] = 9
        assertTrue(B8FaceBin.check(ref).isNotEmpty())
    }

    @Test
    fun faceFileHeaderParserAccepts() {
        val ok = B8FaceFile.parse(face()) as B8FaceFile.Result.Ok
        assertEquals("167299001", ok.id)
        assertEquals("Finn Dijital", ok.name)
    }

    @Test
    fun buildRejectsBadInput() {
        val bg = Element.Picture(0, 0, B8FaceDesign.solid(10, 10, -1))
        assertTrue(runCatching { B8FaceBin.build("12", "x", B8FaceDesign.solid(10, 10, -1), listOf(bg), listOf(bg)) }.isFailure)
        assertTrue(runCatching { B8FaceBin.build("167299001", "x", B8FaceDesign.solid(10, 10, -1), listOf(bg), emptyList()) }.isFailure)
        assertTrue(runCatching { Image(200, 10, IntArray(2000)) }.isFailure)
        val n = Element.Number(0, 0, 0x0841, B8FaceDesign.digits(5, 9, 1, -1).take(9), 3)
        assertTrue(runCatching { B8FaceBin.build("167299001", "x", B8FaceDesign.solid(10, 10, -1), listOf(n), listOf(bg)) }.isFailure)
    }

    @Test
    fun batteryWithSuffix() {
        val d = B8FaceDesign.digits(10, 16, 2, -1)
        val bat = Element.Number(80, 20, B8FaceBin.Src.BATTERY, d, 3, align = 1, showZero = false, suffix = B8FaceDesign.dot(4, -1))
        val bg = Element.Picture(0, 0, B8FaceDesign.solid(192, 490, B8FaceDesign.BLACK))
        val b = B8FaceBin.build("167299002", "Pil", B8FaceDesign.solid(122, 310, B8FaceDesign.BLACK), listOf(bg, bat), listOf(bat))
        assertEquals(emptyList<String>(), B8FaceBin.check(b))
        val w = u32(b, u32(b, 0xA8 + 0x44).toInt() + 8).toInt()
        assertContentEquals(hex("084103 11"), b.copyOfRange(w, w + 4))
        assertContentEquals(hex("010000 02"), b.copyOfRange(w + 16, w + 20))
    }
}

class B8FacePrefixTest {
    private fun f(id: String, del: Boolean) = B8Watchface.Face(id, "", false, del)

    @Test
    fun prefixWarningUsesInstalledFaces() {
        val band = listOf(f("266110010", false), f("266150010", false), f("167210065", true))
        kotlin.test.assertEquals(setOf("167"), B8Watchface.installedPrefixes(band))
        kotlin.test.assertFalse(B8Watchface.prefixDiffers("167299001", band))
        kotlin.test.assertTrue(B8Watchface.prefixDiffers("266299001", band))
        val builtIn = listOf(f("266110010", false))
        kotlin.test.assertFalse(B8Watchface.prefixDiffers("266299001", builtIn))
        kotlin.test.assertTrue(B8Watchface.prefixDiffers("167299001", builtIn))
        kotlin.test.assertFalse(B8Watchface.prefixDiffers("167299001", emptyList()))
    }
}
