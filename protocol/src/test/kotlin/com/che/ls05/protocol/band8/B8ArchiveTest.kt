package com.che.ls05.protocol.band8

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Arşiv özeti ve dışa aktarma. Dosyalar sentetik; içerikte sağlık değeri yok. */
class B8ArchiveTest {
    private val T0 = 1_735_689_600L
    private val detail = B8FileId(T0, 12, 3, 0, 0, 0)
    private val summary = B8FileId(T0 + 75_000, 12, 5, 0, 0, 1)
    private val sleep = B8FileId(T0 + 3_600, 12, 2, 0, 8, 0)

    private fun file(id: B8FileId, n: Int) = B8ActivityFile(id, B8Activity.buildFile(id, ByteArray(n)))

    @Test
    fun statsCountKindsBytesAndCrc() {
        val s = B8FetchStats()
        s.file(file(detail, 100))
        s.file(file(detail, 50))
        s.file(file(sleep, 10))
        s.failed("CRC tutmadı")
        s.failed("zaman aşımı")
        val line = s.summary()
        assertEquals(
            "3 dosya, ${(108 + 4) + (58 + 4) + (18 + 4)} bayt, CRC tamam=3, CRC hata=1, diğer hata=1; türler: t0/s0/d0=2, t0/s8/d0=1",
            line,
        )
    }

    @Test
    fun emptyStats() {
        assertEquals("0 dosya, 0 bayt, CRC tamam=0, CRC hata=0, diğer hata=0; türler: yok", B8FetchStats().summary())
    }

    @Test
    fun entryNameIsHexFileId() {
        assertEquals("band8/" + detail.encode().toHex() + ".bin", B8ArchiveZip.entryName(detail))
    }

    @Test
    fun indexListsEveryFileWithFormatAndIdentity() {
        val entries = listOf(B8ArchiveZip.Entry(file(detail, 100), 1_735_700_000_000L), B8ArchiveZip.Entry(file(summary, 57), 1_735_700_100_000L))
        val text = B8ArchiveZip.index(entries, "Finn's Watch 0.15.0-alpha1 · build 1 · run 1-1 · abc1234 · dal", exportedAtMs = 1_735_800_000_000L)
        assertTrue(text.contains("Finn's Watch 0.15.0-alpha1 · build 1 · run 1-1 · abc1234 · dal"))
        assertTrue(text.contains("çözülmüş"), "biçim açıklaması")
        assertTrue(text.contains("anahtar gerekmez"))
        val rows = text.lines().filter { it.startsWith("band8/") }
        assertEquals(2, rows.size)
        val cols = rows[0].split('\t')
        assertEquals(B8ArchiveZip.entryName(detail), cols[0])
        assertEquals(detail.encode().toHex(), cols[1])
        assertEquals("0", cols[2]) // tür
        assertEquals("0", cols[3]) // alt tür
        assertEquals("0", cols[4]) // ayrıntı
        assertEquals("3", cols[5]) // sürüm
        assertEquals("112", cols[6]) // bayt
        assertEquals("tamam", cols[8])
        assertEquals("2025-01-01T02:53:20Z", cols[9])
    }

    @Test
    fun zipHoldsIndexAndExactFileBytes() {
        val a = file(detail, 100)
        val b = file(summary, 57)
        val out = ByteArrayOutputStream()
        B8ArchiveZip.write(out, listOf(B8ArchiveZip.Entry(a, 1L), B8ArchiveZip.Entry(b, 2L)), "kimlik", 3L)
        val got = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                got[e.name] = z.readBytes()
            }
        }
        assertEquals(setOf("index.txt", B8ArchiveZip.entryName(detail), B8ArchiveZip.entryName(summary)), got.keys)
        assertContentEquals(a.bytes, got[B8ArchiveZip.entryName(detail)])
        assertTrue(B8Activity.crcOk(got[B8ArchiveZip.entryName(summary)]!!))
        assertTrue(String(got["index.txt"]!!, Charsets.UTF_8).contains("kimlik"))
    }

    @Test
    fun emptyArchiveStillWritesIndex() {
        val out = ByteArrayOutputStream()
        B8ArchiveZip.write(out, emptyList(), "kimlik", 0L)
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z -> while (true) { names += (z.nextEntry ?: break).name } }
        assertEquals(listOf("index.txt"), names)
        assertFalse(B8ArchiveZip.index(emptyList(), "k", 0L).lines().any { it.startsWith("band8/") })
    }
}
