package com.che.ls05.protocol.band8

import com.che.ls05.protocol.band8.B8FaceBin.Element
import com.che.ls05.protocol.band8.B8FaceBin.Image

/**
 * Navigasyon kadranı (Notify yöntemi, BAND8_PROTOKOL 17.8 ve 18.4): manevra oku, mesafe ve sokak
 * siyah zeminli küçük bir kadran olarak çizilir ve sabit kimlikle banda yüklenir. Metinler uygulamada
 * (Türkçe karakterlerle) görüntüye çevrilip buraya verilir. Android'e bağlı değil.
 */
object B8NavFace {
    /** Sabit kimlik; her manevrada üzerine yazılır. Bizim önekimiz [B8FaceBin.OWN_ID_PREFIX]. */
    const val ID = "167299090"
    const val NAME = "Navigasyon"

    const val ARROW_MAX = 150
    const val ARROW_Y = 64
    const val DISTANCE_Y = 236
    const val STREET_Y = 300
    const val TEXT_MAX_W = 184
    private const val CLOCK_W = 14
    private const val CLOCK_H = 24
    private const val CLOCK_Y = 18

    /**
     * Kadranı üretir. [distance] ve [street] boş olabilir. Normal ekranda üstte küçük saat (0811, 1011),
     * ok, mesafe, sokak; AOD'de ok ve mesafe.
     */
    fun build(arrow: Image, distance: Image?, street: Image?): ByteArray {
        val a = fit(arrow, ARROW_MAX, ARROW_MAX)
        val digits = B8FaceDesign.digits(CLOCK_W, CLOCK_H, 3, B8FaceDesign.GRAY)
        val clockX = (B8FaceBin.WIDTH - (4 * CLOCK_W + 2 * 2 + 12)) / 2
        val normal = ArrayList<Element>()
        normal.add(Element.Picture(0, 0, B8FaceDesign.solid(B8FaceBin.WIDTH, B8FaceBin.HEIGHT, B8FaceDesign.BLACK)))
        normal.add(Element.Number(clockX, CLOCK_Y, B8FaceBin.Src.HOUR, digits, 2, align = 1, showZero = true, spacing = 2))
        normal.add(Element.Picture(clockX + 2 * CLOCK_W + 2 + 4, CLOCK_Y + 6, B8FaceDesign.dot(3, B8FaceDesign.GRAY)))
        normal.add(Element.Picture(clockX + 2 * CLOCK_W + 2 + 4, CLOCK_Y + 15, B8FaceDesign.dot(3, B8FaceDesign.GRAY)))
        normal.add(Element.Number(clockX + 2 * CLOCK_W + 2 + 12, CLOCK_Y, B8FaceBin.Src.MINUTE, digits, 2, align = 1, showZero = true, spacing = 2))
        normal.add(centered(a, ARROW_Y + (ARROW_MAX - a.height) / 2))
        distance?.let { normal.add(centered(fit(it, TEXT_MAX_W, 56), DISTANCE_Y)) }
        street?.let { normal.add(centered(fit(it, TEXT_MAX_W, 160), STREET_Y)) }
        val aod = ArrayList<Element>()
        aod.add(centered(a, ARROW_Y + (ARROW_MAX - a.height) / 2))
        distance?.let { aod.add(centered(fit(it, TEXT_MAX_W, 56), DISTANCE_Y)) }
        val preview = B8FaceDesign.scale(
            B8FaceDesign.render(normal, mapOf(B8FaceBin.Src.HOUR to 10, B8FaceBin.Src.MINUTE to 8)),
            B8FaceDesign.PREVIEW_W, B8FaceDesign.PREVIEW_H,
        )
        return B8FaceBin.build(ID, NAME, preview, normal, aod)
    }

    private fun centered(img: Image, y: Int) = Element.Picture((B8FaceBin.WIDTH - img.width) / 2, y, img)

    /** En-boy oranını koruyarak sığdırır (yalnızca küçültür). Saydamlık korunur. */
    fun fit(img: Image, maxW: Int, maxH: Int): Image {
        if (img.width <= maxW && img.height <= maxH) return img
        val k = minOf(maxW.toDouble() / img.width, maxH.toDouble() / img.height)
        val w = maxOf(1, (img.width * k).toInt()); val h = maxOf(1, (img.height * k).toInt())
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val x0 = x * img.width / w; val x1 = maxOf(x0 + 1, (x + 1) * img.width / w)
            val y0 = y * img.height / h; val y1 = maxOf(y0 + 1, (y + 1) * img.height / h)
            var a = 0L; var r = 0L; var g = 0L; var b = 0L; var n = 0
            for (yy in y0 until y1) for (xx in x0 until x1) {
                val c = img.argb[yy * img.width + xx]
                val ca = (c ushr 24).toLong()
                a += ca; r += ((c ushr 16) and 0xFF) * ca; g += ((c ushr 8) and 0xFF) * ca; b += (c and 0xFF) * ca; n++
            }
            out[y * w + x] = if (a == 0L) 0 else
                ((a / n).toInt() shl 24) or ((r / a).toInt() shl 16) or ((g / a).toInt() shl 8) or (b / a).toInt()
        }
        return Image(w, h, out)
    }

    /** Deneme düğmesi için yazı tipsiz sağa dönüş oku (beyaz, zemin saydam). */
    fun sampleArrow(size: Int = 140): Image {
        val px = IntArray(size * size)
        fun on(x: Int, y: Int) { if (x in 0 until size && y in 0 until size) px[y * size + x] = B8FaceDesign.WHITE }
        val t = size / 7
        val stemX0 = size * 3 / 10; val turnY = size * 2 / 5
        for (y in turnY until size) for (x in stemX0 until stemX0 + t) on(x, y)            // dikey gövde
        val headX = size * 13 / 20
        for (y in turnY until turnY + t) for (x in stemX0 until headX) on(x, y)            // yatay kol
        val cy = turnY + t / 2; val half = size / 4
        for (x in headX until size) {                                                      // ok ucu (üçgen)
            val k = half * (size - x) / (size - headX)
            for (y in cy - k..cy + k) on(x, y)
        }
        return Image(size, size, px)
    }
}

/**
 * Navigasyon kadranının ne zaman yeniden yükleneceği. Manevra değişince evet; yalnızca mesafe değişince
 * [distanceUpdates] kapalıyken hayır, açıkken en sık [minIntervalMs]'de bir (yükleme süresi ölçülünce karar).
 */
class B8NavGate(private val distanceUpdates: Boolean = false, private val minIntervalMs: Long = 30_000) {
    private var maneuver: String? = null
    private var distance: String? = null
    private var at = 0L

    fun decide(maneuverKey: String, dist: String, now: Long): Boolean {
        val go = when {
            maneuver == null || maneuver != maneuverKey -> true
            dist == distance -> false
            !distanceUpdates -> false
            else -> now - at >= minIntervalMs
        }
        if (go) { maneuver = maneuverKey; distance = dist; at = now }
        return go
    }

    fun reset() { maneuver = null; distance = null; at = 0 }
}
