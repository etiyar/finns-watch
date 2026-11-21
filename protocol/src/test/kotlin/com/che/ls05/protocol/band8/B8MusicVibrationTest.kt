package com.che.ls05.protocol.band8

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Müzik istekleri ve titreşim deseni (yapılar tahmin, belge 16.3 ve 17.7). Değerler uydurmadır. */
class B8MusicVibrationTest {
    private fun cmd(b: ByteArray) = B8Command.parse(b)!!

    @Test
    fun vibrationPatternIsRepeatedOnOffSteps() {
        val c = cmd(B8Vibration.pattern(listOf(true to 400, false to 200, true to 800)))
        assertTrue(c.matches(2, 59))
        val steps = c.body(4)!!.message(41)!!.messages(1)
        assertEquals(listOf(1, 0, 1), steps.map { it.int(1) })
        assertEquals(listOf(400, 200, 800), steps.map { it.int(2) })
    }

    @Test
    fun ruleCountEndsWithLongPulse() {
        assertEquals(listOf(true to 800), B8Vibration.count(1))
        assertEquals(listOf(true to 400, false to 200, true to 400, false to 200, true to 800), B8Vibration.count(3))
        assertEquals(B8Vibration.count(5), B8Vibration.count(9), "en çok 5")
        assertTrue(B8Vibration.count(5).sumOf { it.second } <= 3_500)
    }

    private fun musicFrom(sub: Int, body: PbWriter?) = cmd(
        (if (body == null) B8Messages.command(18, sub) else B8Messages.command(18, sub).message(20, body)).toByteArray(),
    )

    @Test
    fun bareRequestIsStateRequestWithoutKey() {
        val c = musicFrom(0, null)
        assertTrue(B8Music.isStateRequest(c))
        assertNull(B8Music.mediaKey(c))
        assertEquals("yok", B8Music.describe(c))
    }

    @Test
    fun mediaKeyAndVolumeAreRead() {
        val c = musicFrom(0, PbWriter().message(2, PbWriter().uint(1, 5).uint(2, 40)))
        assertEquals(B8Music.MediaKey(5, 40), B8Music.mediaKey(c))
        assertTrue(!B8Music.isStateRequest(c))
        assertEquals("2m{1v 2v}", B8Music.describe(c))
    }

    @Test
    fun flatKeyAndVolumeAreReadToo() {
        // 20.11.2026 (build 191): band ses ekranında 18/2 gönderdi, Music alanları "1v 2v".
        val c = musicFrom(2, PbWriter().uint(1, 5).uint(2, 60))
        assertEquals(B8Music.MediaKey(5, 60), B8Music.mediaKey(c))
        assertEquals("1v 2v", B8Music.describe(c))
    }

    @Test
    fun stateInfoIsNotAKey() {
        assertNull(B8Music.mediaKey(cmd(B8Music.info("a", "b", B8Music.STATE_PLAYING, 1, 0, 10))))
    }

    @Test
    fun describeNeverShowsStrings() {
        val c = musicFrom(1, PbWriter().message(1, PbWriter().uint(1, 1).string(4, "Gizli şarkı")))
        val d = B8Music.describe(c)
        assertEquals("1m{1v 4m}", d)
        assertTrue(!d.contains("Gizli"))
    }

    @Test
    fun infoWithoutPlayerIsNoneState() {
        val i = cmd(B8Music.info(null, null, B8Music.STATE_NONE, 0, 0, 30)).body(20)!!.message(1)!!
        assertEquals(0, i.int(1))
        assertNull(i.string(4))
        assertEquals(30, i.int(2))
    }

    @Test
    fun infoMapsEmojiInTitles() {
        val i = cmd(B8Music.info("Şarkı 🔥", "Grup", B8Music.STATE_PLAYING, 180, 15, 50)).body(20)!!.message(1)!!
        assertEquals("Şarkı (ateş)", i.string(4))
        assertEquals(1, i.int(1))
        assertEquals(15, i.int(6))
        assertEquals(180, i.int(7))
    }
}
