package com.che.ls05.protocol.band8

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class B8NavFaceTest {
    private fun text(w: Int, h: Int) = B8FaceBin.Image(w, h, IntArray(w * h) { if (it % 3 == 0) B8FaceDesign.WHITE else 0 })

    @Test
    fun navFaceIsValidAndSmall() {
        val b = B8NavFace.build(B8NavFace.sampleArrow(), text(90, 40), text(180, 50))
        assertEquals(emptyList<String>(), B8FaceBin.check(b))
        assertEquals(B8NavFace.ID, (B8FaceFile.parse(b) as B8FaceFile.Result.Ok).id)
        assertTrue(B8NavFace.ID.startsWith(B8FaceBin.OWN_ID_PREFIX))
        assertTrue(b.size < 120_000, "boy ${b.size}")
    }

    @Test
    fun navFaceWithoutTexts() {
        val b = B8NavFace.build(B8NavFace.sampleArrow(), null, null)
        assertEquals(emptyList<String>(), B8FaceBin.check(b))
    }

    @Test
    fun largeArrowIsFitted() {
        val big = B8FaceBin.Image(192, 192, IntArray(192 * 192) { B8FaceDesign.WHITE })
        val f = B8NavFace.fit(big, 150, 150)
        assertEquals(150, f.width); assertEquals(150, f.height)
        assertEquals(B8FaceDesign.WHITE, f.argb[0])
        val wide = B8NavFace.fit(B8FaceBin.Image(192, 20, IntArray(192 * 20)), 96, 56)
        assertEquals(96, wide.width); assertEquals(10, wide.height)
        assertEquals(0, wide.argb[0])
        val b = B8NavFace.build(big, null, null)
        assertEquals(emptyList<String>(), B8FaceBin.check(b))
    }

    @Test
    fun previewDecodes() {
        val b = B8NavFace.build(B8NavFace.sampleArrow(), text(90, 40), null)
        val p = assertNotNull(B8FaceBin.preview(b))
        assertEquals(B8FaceDesign.PREVIEW_W, p.width); assertEquals(B8FaceDesign.PREVIEW_H, p.height)
        // Kenar siyah, ortada okun beyazı var.
        assertEquals(B8FaceDesign.BLACK, p.argb[0])
        assertTrue(p.argb.any { it == B8FaceDesign.WHITE })
        assertNull(B8FaceBin.preview(ByteArray(10)))
        // Ham BGRA tek görüntü de açılır.
        val raw = ByteArray(12 + 4)
        raw[0] = 0; raw[1] = 0; B8FaceBin.le16(raw, 4, 1); B8FaceBin.le16(raw, 6, 1); B8FaceBin.le32(raw, 8, 4)
        raw[12] = 1; raw[13] = 2; raw[14] = 3; raw[15] = 0xFF.toByte()
        assertEquals(0xFF030201.toInt(), B8FaceBin.decodeImage(raw, 0)!!.argb[0])
    }

    @Test
    fun previewOfBuiltFaceMatchesRender() {
        val b = B8FaceDesign.simpleDigital(B8FaceDesign.Spec("167299001", "x"))
        val p = B8FaceBin.preview(b)!!
        assertEquals(122, p.width)
    }

    @Test
    fun gateUploadsOnlyOnManeuverChange() {
        val g = B8NavGate()
        assertTrue(g.decide("sola", "300 m", 0))
        assertFalse(g.decide("sola", "300 m", 1_000))
        assertFalse(g.decide("sola", "200 m", 60_000))
        assertTrue(g.decide("saga", "200 m", 61_000))
        g.reset()
        assertTrue(g.decide("saga", "200 m", 62_000))
    }

    @Test
    fun gateDistanceUpdatesWhenEnabled() {
        val g = B8NavGate(distanceUpdates = true, minIntervalMs = 30_000)
        assertTrue(g.decide("sola", "300 m", 0))
        assertFalse(g.decide("sola", "250 m", 10_000))
        assertTrue(g.decide("sola", "200 m", 30_000))
    }

    private fun face(id: String, active: Boolean = false, del: Boolean = true) = B8Watchface.Face(id, "", active, del)

    @Test
    fun targetAlternatesAndNeverPicksActive() {
        val base = listOf(face("266110010", del = false), face("167299001", active = true))
        assertEquals(B8NavFace.ID, B8NavFace.target(base))
        val navA = listOf(face("266110010", del = false), face(B8NavFace.ID, active = true))
        assertEquals(B8NavFace.ID_B, B8NavFace.target(navA))
        val both = listOf(face(B8NavFace.ID, active = true), face(B8NavFace.ID_B))
        assertEquals(B8NavFace.ID_B, B8NavFace.target(both))
        assertNull(B8NavFace.target(both, blocked = setOf(B8NavFace.ID_B)))
        val bothB = listOf(face(B8NavFace.ID), face(B8NavFace.ID_B, active = true))
        assertEquals(B8NavFace.ID, B8NavFace.target(bothB))
        // Bantta yoksa ve etkin değilse önce bantta olmayan seçilir.
        val oneOld = listOf(face("167299001", active = true), face(B8NavFace.ID))
        assertEquals(B8NavFace.ID_B, B8NavFace.target(oneOld))
    }

    @Test
    fun restoreChoice() {
        val list = listOf(face("266110010", del = false), face("167299001"), face(B8NavFace.ID, active = true))
        assertEquals("167299001", B8NavFace.restoreTo("167299001", list))
        assertEquals("266110010", B8NavFace.restoreTo(null, list))
        assertEquals("266110010", B8NavFace.restoreTo(B8NavFace.ID_B, list))
        assertEquals("266110010", B8NavFace.restoreTo("999999999", list))
        assertNull(B8NavFace.restoreTo(null, listOf(face(B8NavFace.ID))))
    }

    @Test
    fun guardTripsOnTwoDisconnectsAfterFaceOps() {
        val g = B8NavGuard()
        assertFalse(g.disconnected(1_000))          // kadran işlemi yok
        g.faceOp(10_000)
        assertFalse(g.disconnected(40_000))         // 30 sn sonra: sayılmaz
        g.faceOp(50_000)
        assertFalse(g.disconnected(56_000))         // 1. uyarı
        assertEquals(1, g.strikeCount)
        g.faceOp(90_000)
        assertTrue(g.disconnected(100_000))         // 2. uyarı: tetik
        g.reset()
        assertEquals(0, g.strikeCount)
    }

    @Test
    fun guardForgetsOldStrikes() {
        val g = B8NavGuard(memoryMs = 60_000)
        g.faceOp(0); assertFalse(g.disconnected(5_000))
        g.faceOp(200_000); assertFalse(g.disconnected(205_000))
    }
}
