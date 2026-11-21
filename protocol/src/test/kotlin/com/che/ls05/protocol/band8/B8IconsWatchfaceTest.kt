package com.che.ls05.protocol.band8

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class B8IconsWatchfaceTest {
    private val key = B8TestVectors.KEY

    // ---------- Simge ----------

    @Test
    fun iconQueryAndSpecRoundTrip() {
        assertEquals("com.example.app", B8Icons.parseQuery(B8Command.parse(B8Icons.query("com.example.app"))!!))
        val req = B8Command.parse(B8Icons.request("com.example.app"))!!
        assertTrue(req.matches(7, 15))
        assertEquals("com.example.app", req.body(9)!!.message(14)!!.string(1))
        // Kayıttaki yanıt yapısı: alan 15 > 1 durum 0, 2 biçim 3, 3 kenar 28.
        val spec = B8Icons.parseSpec(B8Command.parse(B8Icons.spec(B8Icons.Spec(0, 3, 28)))!!)
        assertEquals(B8Icons.Spec(0, 3, 28), spec)
        assertTrue(B8Icons.supported(spec!!))
        assertFalse(B8Icons.supported(B8Icons.Spec(1, 3, 28)))
        assertFalse(B8Icons.supported(B8Icons.Spec(0, 7, 28)))
        assertFalse(B8Icons.supported(B8Icons.Spec(0, 3, 1000)))
        assertNull(B8Icons.parseSpec(B8Command.parse(B8Icons.request("x"))!!))
    }

    @Test
    fun argb8888LittleEndianIsBgra() {
        val px = intArrayOf(0x80112233.toInt(), 0xFF000000.toInt(), 0x00FFFFFF, 0x12345678)
        val out = B8Icons.encode(px, 2, B8Icons.FORMAT_ARGB8888_LE)!!
        assertContentEquals(hex("33221180 000000ff ffffff00 78563412"), out)
        assertEquals(28 * 28 * 4, B8Icons.encode(IntArray(28 * 28), 28, 3)!!.size)
    }

    @Test
    fun rgb565Variants() {
        val px = intArrayOf(0xFFFF0000.toInt())
        assertContentEquals(hex("00f8"), B8Icons.encode(px, 1, B8Icons.FORMAT_RGB565_LE))
        assertContentEquals(hex("f800"), B8Icons.encode(px, 1, B8Icons.FORMAT_RGB565_BE))
        // Saydam piksel siyah olur.
        assertContentEquals(hex("0000"), B8Icons.encode(intArrayOf(0x00FFFFFF), 1, B8Icons.FORMAT_RGB565_LE))
        assertNull(B8Icons.encode(px, 1, 8))
        assertNull(B8Icons.encode(px, 2, 3))
    }

    @Test
    fun packagePrefixResolve() {
        val apps = listOf("com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger")
        assertEquals("com.whatsapp", B8Icons.resolvePackage("com.whatsapp", apps))
        assertEquals("org.telegram.messenger", B8Icons.resolvePackage("org.telegram.mess", apps))
        assertNull(B8Icons.resolvePackage("com.whats", apps))
        assertNull(B8Icons.resolvePackage("com.none", apps))
    }

    @Test
    fun iconFlowEndToEnd() {
        val band = FakeBand(key).apply { iconSpec = B8Icons.Spec(0, 3, 44) }
        val h = B8Harness(key, band)
        h.authenticate()
        h.send(B8Icons.request("com.example.app"))
        val spec = B8Icons.parseSpec(h.inbox.last())!!
        val bytes = B8Icons.encode(IntArray(spec.size * spec.size) { 0xFF336699.toInt() }, spec.size, spec.format)!!
        h.upload(B8UploadJob(B8Upload.TYPE_ICON, bytes))
        assertEquals(1, band.uploads.size)
        assertEquals(50, band.uploads[0].type)
        assertEquals(44 * 44 * 4, band.uploads[0].data.size)
        assertEquals(listOf(7 to 15, 22 to 0), band.received.filter { it.type != 1 }.map { it.type to it.subtype })
    }

    // ---------- Kadran ----------

    /** Kayıttaki 4/0 yanıtının yapısı: 8 kadran, sonuncusu etkin ve silinebilir. Adlar ve kimlikler sentetik. */
    private fun listReply(): B8Command {
        val l = PbWriter()
        for (i in 0 until 8) {
            l.message(1, PbWriter().string(1, "99911${i}010").string(2, "Kadran $i")
                .bool(3, i == 7).bool(4, i > 0).uint(5, 0).uint(6, 0).uint(11, 0))
        }
        return B8Command.parse(B8Messages.command(4, 0).message(6, PbWriter().message(1, l)).toByteArray())!!
    }

    @Test
    fun listParse() {
        val faces = B8Watchface.parseList(listReply())!!
        assertEquals(8, faces.size)
        assertEquals("999110010", faces[0].id)
        assertFalse(faces[0].canDelete)
        assertTrue(faces[7].active)
        assertFalse(B8Watchface.canDelete(faces[0]))
        assertFalse(B8Watchface.canDelete(faces[7]))
        assertTrue(B8Watchface.canDelete(faces[3]))
        assertTrue(B8Watchface.sameFamily("999123456", faces))
        assertFalse(B8Watchface.sameFamily("123456789", faces))
        assertContentEquals(hex("08041000"), B8Watchface.list())
    }

    @Test
    fun setDeleteInstallCommands() {
        val s = B8Command.parse(B8Watchface.set("999110013"))!!
        assertTrue(s.matches(4, 1))
        assertEquals("999110013", s.body(6)!!.string(2))
        assertTrue(B8Command.parse(B8Watchface.delete("1"))!!.matches(4, 2))
        val i = B8Command.parse(B8Watchface.installStart("999110013", 123456))!!
        assertTrue(i.matches(4, 4))
        assertEquals("999110013", i.body(6)!!.message(6)!!.string(1))
        assertEquals(123456, i.body(6)!!.message(6)!!.int(2))
        fun status(v: Int?) = B8Watchface.installStatus(B8Command.parse(B8Messages.command(4, 4).message(6,
            PbWriter().also { if (v != null) it.uint(5, v) }).toByteArray())!!)
        assertEquals(0, status(0))
        assertEquals(2, status(2))
        assertEquals(0, status(null))
    }

    private fun faceFile(id: String, name: String?, size: Int = 512): ByteArray {
        val b = ByteArray(size)
        b[0] = 0x5A; b[1] = 0xA5.toByte()
        id.toByteArray().copyInto(b, 0x28)
        if (name == null) for (k in 0 until 4) b[0x68 + k] = 0xFF.toByte() else name.toByteArray().copyInto(b, 0x68)
        return b
    }

    @Test
    fun faceFileHeader() {
        val ok = B8FaceFile.parse(faceFile("999110099", "Deneme")) as B8FaceFile.Result.Ok
        assertEquals("999110099", ok.id)
        assertEquals("Deneme", ok.name)
        assertEquals(512, ok.size)
        val loc = B8FaceFile.parse(faceFile("999110099", null)) as B8FaceFile.Result.Ok
        assertEquals("", loc.name)
        assertTrue(B8FaceFile.parse(faceFile("99a", "x")) is B8FaceFile.Result.Invalid)
        assertTrue(B8FaceFile.parse(faceFile("", "x")) is B8FaceFile.Result.Invalid)
        assertTrue(B8FaceFile.parse(ByteArray(64)) is B8FaceFile.Result.Invalid)
        val bad = faceFile("1", "x").also { it[1] = 0 }
        assertTrue(B8FaceFile.parse(bad) is B8FaceFile.Result.Invalid)
        // Sıfırla bitmeyen kimlik alanı reddedilir.
        val noTerm = faceFile("1", "x").also { for (k in 0x28 until 0x68) it[k] = '1'.code.toByte() }
        assertTrue(B8FaceFile.parse(noTerm) is B8FaceFile.Result.Invalid)
    }

    @Test
    fun faceUploadEndToEnd() {
        val h = B8Harness(key, FakeBand(key))
        h.authenticate()
        val file = faceFile("999110099", "Deneme", 9000)
        h.send(B8Watchface.installStart("999110099", file.size))
        h.upload(B8UploadJob(B8Upload.TYPE_WATCHFACE, file))
        assertEquals(16, h.band.uploads.single().type)
        assertContentEquals(file, h.band.uploads.single().data)
    }
}
