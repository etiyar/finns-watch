package com.che.ls05.protocol.band8

import com.che.ls05.protocol.band8.B8FaceBin.Element
import com.che.ls05.protocol.band8.B8FaceBin.Image

/**
 * Kendi kadranlarımız için basit çizimler: yazı tipi gerektirmeyen yedi parçalı rakamlar,
 * düz renk arka plan ve önizleme. Görüntüler ARGB, Android'e bağlı değil.
 */
object B8FaceDesign {
    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLACK = 0xFF000000.toInt()
    const val GRAY = 0xFF9A9A9A.toInt()

    fun solid(w: Int, h: Int, color: Int) = Image(w, h, IntArray(w * h) { color })

    // Parçalar: a üst, b sağ üst, c sağ alt, d alt, e sol alt, f sol üst, g orta.
    private val SEGMENTS = arrayOf("abcdef", "bc", "abged", "abgcd", "fgbc", "afgcd", "afgedc", "abc", "abcdefg", "abcdfg")

    /** [w] x [h] boyunda, [t] kalınlığında tek rakam; zemin saydam. */
    fun digit(n: Int, w: Int, h: Int, t: Int, color: Int): Image {
        require(n in 0..9 && t * 3 < h && t * 2 < w)
        val px = IntArray(w * h)
        fun rect(x0: Int, y0: Int, x1: Int, y1: Int) {
            for (y in maxOf(0, y0) until minOf(h, y1)) for (x in maxOf(0, x0) until minOf(w, x1)) px[y * w + x] = color
        }
        val mid = (h - t) / 2
        val gap = maxOf(1, t / 4)
        for (s in SEGMENTS[n]) when (s) {
            'a' -> rect(t + gap, 0, w - t - gap, t)
            'd' -> rect(t + gap, h - t, w - t - gap, h)
            'g' -> rect(t + gap, mid, w - t - gap, mid + t)
            'f' -> rect(0, t + gap, t, mid - gap)
            'b' -> rect(w - t, t + gap, w, mid - gap)
            'e' -> rect(0, mid + t + gap, t, h - t - gap)
            'c' -> rect(w - t, mid + t + gap, w, h - t - gap)
        }
        return Image(w, h, px)
    }

    fun digits(w: Int, h: Int, t: Int, color: Int): List<Image> = (0..9).map { digit(it, w, h, t, color) }

    /** Kare nokta (tarih ayıracı). */
    fun dot(size: Int, color: Int) = solid(size, size, color)

    /**
     * Deneme kadranı: siyah zemin, büyük beyaz saat (üstte saat, altta dakika), küçük tarih (ay.gün).
     * AOD: gri saat ve dakika, zemin yok.
     */
    class Spec(val id: String, val name: String)

    private const val BIG_W = 74
    private const val BIG_H = 124
    private const val BIG_T = 14
    private const val BIG_GAP = 10
    private const val SMALL_W = 20
    private const val SMALL_H = 34
    private const val SMALL_T = 5

    fun simpleDigital(spec: Spec): ByteArray {
        val big = digits(BIG_W, BIG_H, BIG_T, WHITE)
        val small = digits(SMALL_W, SMALL_H, SMALL_T, WHITE)
        val bigX = (B8FaceBin.WIDTH - (2 * BIG_W + BIG_GAP)) / 2
        val dateY = 420
        val dotSize = 4
        val dateW = 2 * SMALL_W + 2 + 4 + dotSize + 4 + 2 * SMALL_W + 2
        val dateX = (B8FaceBin.WIDTH - dateW) / 2
        val monthW = 2 * SMALL_W + 2
        val normal = listOf(
            Element.Picture(0, 0, solid(B8FaceBin.WIDTH, B8FaceBin.HEIGHT, BLACK)),
            Element.Number(bigX, 90, B8FaceBin.Src.HOUR, big, 2, align = 1, showZero = true, spacing = BIG_GAP),
            Element.Number(bigX, 250, B8FaceBin.Src.MINUTE, big, 2, align = 1, showZero = true, spacing = BIG_GAP),
            Element.Number(dateX, dateY, B8FaceBin.Src.MONTH, small, 2, align = 1, showZero = true, spacing = 2),
            Element.Picture(dateX + monthW + 4, dateY + SMALL_H - dotSize, dot(dotSize, WHITE)),
            Element.Number(dateX + monthW + 4 + dotSize + 4, dateY, B8FaceBin.Src.DAY, small, 2, align = 1, showZero = true, spacing = 2),
        )
        val dim = digits(BIG_W, BIG_H, BIG_T, GRAY)
        // Cihaz örneklerinin AOD ekranında hep en az bir düz görüntü var; saat ile dakika arasına küçük bir nokta.
        val aod = listOf(
            Element.Number(bigX, 90, B8FaceBin.Src.HOUR, dim, 2, align = 1, showZero = true, spacing = BIG_GAP),
            Element.Picture((B8FaceBin.WIDTH - 6) / 2, 231, dot(6, GRAY)),
            Element.Number(bigX, 250, B8FaceBin.Src.MINUTE, dim, 2, align = 1, showZero = true, spacing = BIG_GAP),
        )
        val preview = scale(render(normal, mapOf(B8FaceBin.Src.HOUR to 10, B8FaceBin.Src.MINUTE to 8, B8FaceBin.Src.MONTH to 11, B8FaceBin.Src.DAY to 21)), PREVIEW_W, PREVIEW_H)
        return B8FaceBin.build(spec.id, spec.name, preview, normal, aod)
    }

    /** Cihaz örneklerinde önizleme 122 x 310. */
    const val PREVIEW_W = 122
    const val PREVIEW_H = 310

    /** Ekranı verilen değerlerle çizer (yalnızca sol hizalı sayılar; önizleme için). */
    fun render(es: List<Element>, values: Map<Int, Int>): Image {
        val w = B8FaceBin.WIDTH; val h = B8FaceBin.HEIGHT
        val px = IntArray(w * h) { BLACK }
        fun paste(img: Image, x0: Int, y0: Int) {
            for (y in 0 until img.height) for (x in 0 until img.width) {
                val tx = x0 + x; val ty = y0 + y
                if (tx !in 0 until w || ty !in 0 until h) continue
                val c = img.argb[y * img.width + x]
                val a = c ushr 24
                if (a == 0xFF) px[ty * w + tx] = c else if (a > 0) px[ty * w + tx] = blend(px[ty * w + tx], c, a)
            }
        }
        for (e in es) when (e) {
            is Element.Picture -> paste(e.image, e.x, e.y)
            is Element.Number -> {
                val v = values[e.source] ?: 0
                val s = v.toString().padStart(e.count, '0').takeLast(e.count)
                var x = e.x
                for (ch in s) { val d = e.digits[ch - '0']; paste(d, x, e.y); x += d.width + e.spacing }
            }
            is Element.Pictures -> paste(e.images[(values[e.source] ?: 0).coerceIn(0, e.images.size - 1)], e.x, e.y)
        }
        return Image(w, h, px)
    }

    private fun blend(dst: Int, src: Int, a: Int): Int {
        fun ch(s: Int) = (((src ushr s) and 0xFF) * a + ((dst ushr s) and 0xFF) * (255 - a)) / 255
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Alan ortalamasıyla küçültme. */
    fun scale(img: Image, w: Int, h: Int): Image {
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val x0 = x * img.width / w; val x1 = maxOf(x0 + 1, (x + 1) * img.width / w)
            val y0 = y * img.height / h; val y1 = maxOf(y0 + 1, (y + 1) * img.height / h)
            var r = 0L; var g = 0L; var b = 0L; var n = 0
            for (yy in y0 until y1) for (xx in x0 until x1) {
                val c = img.argb[yy * img.width + xx]
                r += (c ushr 16) and 0xFF; g += (c ushr 8) and 0xFF; b += c and 0xFF; n++
            }
            out[y * w + x] = (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
        }
        return Image(w, h, out)
    }
}
