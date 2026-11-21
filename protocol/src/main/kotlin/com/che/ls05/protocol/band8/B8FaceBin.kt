package com.che.ls05.protocol.band8

/**
 * Band 8 kadran ikili dosyası (.bin): kodlama, üretim ve denetim. Biçim: docs/BAND8_KADRAN_BICIMI.md.
 *
 * Bütün tamsayılar küçük endian. Yerleşim (tek kadran ya da kadran + AOD):
 * ana başlık (0xA8), kadran başlıkları (0x58), tanımlar (16 bayt), özellikler, önizleme, görüntüler.
 * Bu dosya yalnızca bizim kullandığımız alt kümeyi üretir: düz görüntü, görüntü listesi, sayı.
 * Yerleşim kullanıcının 5 gerçek kadran dosyasıyla karşılaştırıldı (yalnızca yapı).
 */
object B8FaceBin {
    const val WIDTH = 192
    const val HEIGHT = 490
    const val MAIN_HEADER = 0xA8
    const val FACE_HEADER = 0x58
    const val DEF_SIZE = 16
    const val IMAGE_HEADER = 12
    val MAGIC = byteArrayOf(0x5A, 0xA5.toByte(), 0x34, 0x12)
    private val RLE_MAGIC = byteArrayOf(0xE0.toByte(), 0x21, 0xA5.toByte(), 0x5A)

    const val DEF_ELEMENT = 0
    const val DEF_IMAGE = 2
    const val DEF_LIST = 3
    const val DEF_WIDGET = 7

    /** Görüntü işareti: 0 = BGRA 8888 (saydam), 3 = RGB565 LE (saydamsız). */
    const val SIGN_BGRA = 0
    const val SIGN_RGB565 = 3

    /** Bizim kadran kimliklerimiz bu önekle başlar (deneme aralığı; bkz. belge). */
    const val OWN_ID_PREFIX = "1672990"

    /** Veri kaynakları (belge bölüm 6). */
    object Src {
        const val HOUR = 0x0811
        const val MINUTE = 0x1011
        const val HOUR_TENS = 0x0A11
        const val HOUR_ONES = 0x0911
        const val MINUTE_TENS = 0x1211
        const val MINUTE_ONES = 0x1111
        const val MONTH = 0x1012
        const val DAY = 0x1812
        const val WEEKDAY = 0x2012
        const val BATTERY = 0x0841
    }

    /** ARGB görüntü, satır satır. */
    class Image(val width: Int, val height: Int, val argb: IntArray) {
        init { require(width in 1..WIDTH && height in 1..HEIGHT && argb.size == width * height) { "görüntü boyu geçersiz" } }
        val opaque: Boolean get() = argb.all { (it ushr 24) == 0xFF }
    }

    sealed class Element(val x: Int, val y: Int) {
        class Picture(x: Int, y: Int, val image: Image) : Element(x, y)
        /** Tek basamaklı kaynak için görüntü listesi (0-9 ya da haftanın günleri). */
        class Pictures(x: Int, y: Int, val source: Int, val images: List<Image>) : Element(x, y)
        /** Sayı: [digits] 0-9, [count] basamak, [align] 0 sağ, 1 sol, 2 orta. [suffix] sondaki görüntü. */
        class Number(
            x: Int, y: Int, val source: Int, val digits: List<Image>, val count: Int,
            val align: Int = 1, val showZero: Boolean = true, val spacing: Int = 0, val suffix: Image? = null,
        ) : Element(x, y)
    }

    // ---------- Görüntü kodlama ----------

    fun pixels(img: Image, bpp: Int): ByteArray {
        val out = ByteArray(img.argb.size * bpp)
        var p = 0
        for (c in img.argb) {
            val a = c ushr 24; val r = (c ushr 16) and 0xFF; val g = (c ushr 8) and 0xFF; val b = c and 0xFF
            if (bpp == 4) {
                out[p++] = b.toByte(); out[p++] = g.toByte(); out[p++] = r.toByte(); out[p++] = a.toByte()
            } else {
                out[p++] = (((g and 0x1C) shl 3) or (b ushr 3)).toByte()
                out[p++] = ((r and 0xF8) or (g ushr 5)).toByte()
            }
        }
        return out
    }

    /** RLE 1.0: her kayıt 1 bayt sayı (1-127) + bir piksel. Yalnızca tekrar kaydı yazılır. */
    fun rle(pix: ByteArray, bpp: Int): ByteArray {
        require(pix.size % bpp == 0)
        val out = java.io.ByteArrayOutputStream(pix.size / 8 + 16)
        var i = 0
        while (i < pix.size) {
            var n = 1
            while (n < 0x7F && i + (n + 1) * bpp <= pix.size && same(pix, i, i + n * bpp, bpp)) n++
            out.write(n)
            out.write(pix, i, bpp)
            i += n * bpp
        }
        return out.toByteArray()
    }

    private fun same(b: ByteArray, x: Int, y: Int, n: Int): Boolean {
        for (k in 0 until n) if (b[x + k] != b[y + k]) return false
        return true
    }

    /** Sıkıştırılmış yük: imza, (ham boy << 4 | bpp), RLE. */
    fun compressed(pix: ByteArray, bpp: Int): ByteArray {
        val enc = rle(pix, bpp)
        val out = ByteArray(8 + enc.size)
        RLE_MAGIC.copyInto(out)
        le32(out, 4, (pix.size.toLong() shl 4) or bpp.toLong())
        enc.copyInto(out, 8)
        return out
    }

    /** RLE 1.0 çözümü. Ham boy tutmazsa ya da veri taşarsa null. Tekrar (üst bit 0) ve düz (üst bit 1) kayıt. */
    fun unpack(payload: ByteArray, from: Int, len: Int): Pair<ByteArray, Int>? {
        if (len < 8 || from + len > payload.size) return null
        for (k in 0 until 4) if (payload[from + k] != RLE_MAGIC[k]) return null
        val info = u32(payload, from + 4)
        val bpp = (info and 0xF).toInt()
        val raw = (info ushr 4).toInt()
        if (bpp !in 1..4 || raw <= 0 || raw % bpp != 0 || raw > WIDTH * HEIGHT * 4) return null
        val out = ByteArray(raw)
        var ip = from + 8
        val end = from + len
        var op = 0
        while (ip < end && op < raw) {
            val c = payload[ip++].toInt() and 0xFF
            val n = c and 0x7F
            if (n == 0) return null
            if (c and 0x80 == 0) {
                if (ip + bpp > end || op + n * bpp > raw) return null
                for (j in 0 until n) { System.arraycopy(payload, ip, out, op, bpp); op += bpp }
                ip += bpp
            } else {
                if (ip + n * bpp > end || op + n * bpp > raw) return null
                System.arraycopy(payload, ip, out, op, n * bpp); ip += n * bpp; op += n * bpp
            }
        }
        if (op != raw || ip != end) return null
        return out to bpp
    }

    /** Tek görüntü bloğu. Saydamsa BGRA, değilse RGB565. Her zaman sıkıştırılmış (bayrak 4). */
    fun imageBlock(img: Image): ByteArray {
        val alpha = !img.opaque
        val bpp = if (alpha) 4 else 2
        val payload = compressed(pixels(img, bpp), bpp)
        val h = ByteArray(IMAGE_HEADER)
        h[0] = (if (alpha) SIGN_BGRA else SIGN_RGB565).toByte()
        h[1] = 4
        le16(h, 4, img.width); le16(h, 6, img.height)
        le32(h, 8, payload.size.toLong())
        return h + payload
    }

    /**
     * Görüntü listesi bloğu: hepsi BGRA, sıkıştırılmış; başlıktan sonra görüntü başına 4 bayt boy.
     * Başlıktaki toplam yalnızca yüklerin toplamıdır (cihaz örneklerinin çoğu böyle, belge 4.3).
     */
    fun listBlock(imgs: List<Image>): ByteArray {
        require(imgs.isNotEmpty() && imgs.size <= 255)
        val payloads = imgs.map { compressed(pixels(it, 4), 4) }
        val h = ByteArray(IMAGE_HEADER + 4 * imgs.size)
        h[0] = SIGN_BGRA.toByte(); h[1] = imgs.size.toByte(); h[2] = 4
        le16(h, 4, imgs[0].width); le16(h, 6, imgs[0].height)
        le32(h, 8, payloads.sumOf { it.size }.toLong())
        payloads.forEachIndexed { i, p -> le32(h, IMAGE_HEADER + 4 * i, p.size.toLong()) }
        val out = java.io.ByteArrayOutputStream()
        out.write(h); payloads.forEach { out.write(it) }
        return out.toByteArray()
    }

    // ---------- Üretim ----------

    /**
     * Kadranı üretir: normal ekran ve her zaman açık ekran (AOD). Cihazdaki örnek kadranların hepsi iki
     * ekranlı, bu yüzden AOD zorunlu. Yerleşim cihaz örnekleriyle aynı (belge 3): ana başlık, iki kadran
     * başlığı, önce 1. sonra 2. ekranın tanımları; her ekran için öğe özellikleri, widget özellikleri, düz
     * görüntüler, listeler; en sonda önizleme. Bloklar 4 bayta hizalanır, aynı içerik paylaşılmaz.
     */
    fun build(id: String, name: String, preview: Image, normal: List<Element>, aod: List<Element>): ByteArray {
        require(id.length == 9 && id.all { it in '0'..'9' }) { "kimlik 9 rakam olmalı" }
        require(normal.isNotEmpty() && aod.isNotEmpty()) { "iki ekran gerekli" }
        val plans = listOf(plan(normal), plan(aod))
        val header = ByteArray(MAIN_HEADER)
        MAGIC.copyInto(header)
        le32(header, 0x10, 0x800)
        header[0x1C] = 2
        header[0x1E] = 4
        id.toByteArray(Charsets.US_ASCII).copyInto(header, 0x28)
        val nb = name.toByteArray(Charsets.UTF_8)
        nb.copyInto(header, 0x68, 0, minOf(nb.size, 0x3C))

        // Konumlar
        var at = MAIN_HEADER + 2 * FACE_HEADER
        val defAt = plans.map { p -> at.also { at += p.defCount * DEF_SIZE } }
        val layouts = plans.map { p ->
            val elAt = at; at += p.elements.size * 16
            val wAt = at; at += p.widgets.sumOf { widgetSize(it) }
            val sAt = p.singles.map { blk -> align(at).also { at = it + blk.size } }
            val lAt = p.lists.map { blk -> align(at).also { at = it + blk.size } }
            Layout(elAt, wAt, sAt, lAt)
        }
        val previewBlock = imageBlock(preview)
        val previewAt = align(at)
        at = previewAt + previewBlock.size
        val total = align(at)
        le32(header, 0x20, previewAt.toLong())

        val out = ByteArray(total)
        header.copyInto(out)
        plans.forEachIndexed { f, p ->
            faceHeader(p, defAt[f], if (f == 0) previewAt else 0).copyInto(out, MAIN_HEADER + f * FACE_HEADER)
            emit(p, layouts[f], defAt[f], out)
        }
        previewBlock.copyInto(out, previewAt)
        return out
    }

    private class Layout(val elements: Int, val widgets: Int, val singles: List<Int>, val lists: List<Int>)

    private fun align(v: Int) = (v + 3) and 3.inv()

    /** Görüntü listesi widget'ı 16 bayt (eşlemesiz), sayı 20 bayt (cihaz örnekleri). */
    private fun widgetSize(e: Element): Int = if (e is Element.Pictures) 16 else 20

    /** Bir kadran ekranının planı: her başvuruya ayrı tanım ve ayrı blok. */
    private class Plan(val elements: List<Element>) {
        val singles = ArrayList<ByteArray>()
        val lists = ArrayList<ByteArray>()
        val singleOf = HashMap<Pair<Int, Int>, Int>() // (öğe, 0 görüntü / 1 sonek) -> tanım sırası
        val listOf = HashMap<Int, Int>()
        val widgets = elements.filter { it !is Element.Picture }
        init {
            elements.forEachIndexed { i, e ->
                when (e) {
                    is Element.Picture -> { singleOf[i to 0] = singles.size; singles.add(imageBlock(e.image)) }
                    is Element.Number -> e.suffix?.let { singleOf[i to 1] = singles.size; singles.add(imageBlock(it)) }
                    else -> {}
                }
            }
            elements.forEachIndexed { i, e ->
                val imgs = when (e) { is Element.Pictures -> e.images; is Element.Number -> e.digits; else -> null }
                if (imgs != null) { listOf[i] = lists.size; lists.add(listBlock(imgs)) }
            }
        }
        val defCount get() = elements.size + singles.size + lists.size + widgets.size
    }

    private fun plan(es: List<Element>): Plan {
        es.forEach {
            require(it.x in -WIDTH..WIDTH && it.y in -HEIGHT..HEIGHT) { "konum ekran dışında" }
            if (it is Element.Number) require(it.digits.size == 10 && it.count in 1..9 && it.align in 0..2) { "sayı öğesi geçersiz" }
        }
        return Plan(es)
    }

    private fun faceHeader(p: Plan, start: Int, preview: Int): ByteArray {
        val h = ByteArray(FACE_HEADER)
        h[3] = 2 // cihaz örneklerinde 0x02000000 (anlamı bilinmiyor)
        le32(h, 4, preview.toLong())
        var off = start
        fun sec(at: Int, count: Int) { le32(h, at, count.toLong()); le32(h, at + 4, off.toLong()); off += count * DEF_SIZE }
        sec(0x08, p.elements.size)
        sec(0x10, 0)
        sec(0x18, p.singles.size)
        sec(0x20, p.lists.size)
        sec(0x28, 0); sec(0x30, 0); sec(0x38, 0)
        sec(0x40, p.widgets.size)
        sec(0x48, 0); sec(0x50, 0)
        return h
    }

    private fun emit(p: Plan, l: Layout, defStart: Int, out: ByteArray) {
        var d = defStart
        fun def(idx: Int, type: Int, off: Int, len: Int) {
            le16(out, d, idx); out[d + 3] = type.toByte(); le32(out, d + 8, off.toLong()); le32(out, d + 12, len.toLong()); d += DEF_SIZE
        }
        p.elements.indices.forEach { def(it, DEF_ELEMENT, l.elements + 16 * it, 16) }
        p.singles.forEachIndexed { i, b -> def(i, DEF_IMAGE, l.singles[i], b.size) }
        p.lists.forEachIndexed { i, b -> def(i, DEF_LIST, l.lists[i], b.size) }
        var wo = l.widgets
        p.widgets.forEachIndexed { i, w -> def(i, DEF_WIDGET, wo, widgetSize(w)); wo += widgetSize(w) }

        p.elements.forEachIndexed { i, e ->
            val q = l.elements + 16 * i
            if (e is Element.Picture) { le24(out, q, p.singleOf[i to 0]!!); out[q + 3] = DEF_IMAGE.toByte() }
            else { le24(out, q, p.widgets.indexOf(e)); out[q + 3] = DEF_WIDGET.toByte() }
            le16(out, q + 4, e.x); le16(out, q + 6, e.y)
        }
        var q = l.widgets
        p.elements.forEachIndexed { i, e ->
            if (e is Element.Picture) return@forEachIndexed
            val src = if (e is Element.Pictures) e.source else (e as Element.Number).source
            out[q] = (src ushr 8).toByte(); out[q + 1] = (src and 0xFF).toByte()
            when (e) {
                is Element.Number -> {
                    out[q + 2] = e.count.toByte()
                    out[q + 3] = (0x10 or ((if (e.showZero) 1 else 0) shl 2) or e.align).toByte()
                    out[q + 13] = e.spacing.toByte()
                    if (e.suffix != null) { le24(out, q + 16, p.singleOf[i to 1]!!); out[q + 19] = DEF_IMAGE.toByte() }
                }
                is Element.Pictures -> out[q + 3] = 0x20
                else -> {}
            }
            le24(out, q + 8, p.listOf[i]!!); out[q + 11] = DEF_LIST.toByte()
            q += widgetSize(e)
        }
        p.singles.forEachIndexed { i, b -> b.copyInto(out, l.singles[i]) }
        p.lists.forEachIndexed { i, b -> b.copyInto(out, l.lists[i]) }
    }

    // ---------- Önizleme ----------

    /**
     * Dosyadaki önizlemeyi (ana başlık 0x20) ARGB görüntüye açar. İşaret 3 (RGB565) ve 0 (BGRA),
     * sıkıştırılmış ya da ham. Okunamazsa null. Galeri ve onay penceresi için.
     */
    fun preview(b: ByteArray): Image? {
        if (b.size < MAIN_HEADER) return null
        for (k in 0 until 4) if (b[k] != MAGIC[k]) return null
        val at = u32(b, 0x20)
        if (at <= 0L || at + IMAGE_HEADER > b.size) return null
        return decodeImage(b, at.toInt())
    }

    /** Tek görüntü bloğunu açar (liste değil). */
    fun decodeImage(b: ByteArray, at: Int): Image? {
        if (at < 0 || at + IMAGE_HEADER > b.size) return null
        val sign = b[at].toInt() and 0xFF
        val w = u16(b, at + 4); val h = u16(b, at + 6); val len = u32(b, at + 8).toInt()
        if (w !in 1..WIDTH || h !in 1..HEIGHT || len <= 0 || at + IMAGE_HEADER + len > b.size) return null
        val bpp = when (sign) { SIGN_BGRA -> 4; SIGN_RGB565 -> 2; else -> return null }
        val pix: ByteArray = if (b[at + 1].toInt() == 4) {
            val r = unpack(b, at + IMAGE_HEADER, len) ?: return null
            if (r.second != bpp) return null
            r.first
        } else b.copyOfRange(at + IMAGE_HEADER, at + IMAGE_HEADER + len)
        if (pix.size != w * h * bpp) return null
        val argb = IntArray(w * h)
        for (i in argb.indices) {
            argb[i] = if (bpp == 4) {
                val o = i * 4
                ((pix[o + 3].toInt() and 0xFF) shl 24) or ((pix[o + 2].toInt() and 0xFF) shl 16) or
                    ((pix[o + 1].toInt() and 0xFF) shl 8) or (pix[o].toInt() and 0xFF)
            } else {
                val v = (pix[i * 2].toInt() and 0xFF) or ((pix[i * 2 + 1].toInt() and 0xFF) shl 8)
                val r = (v ushr 11) and 0x1F; val g = (v ushr 5) and 0x3F; val bl = v and 0x1F
                (0xFF shl 24) or ((r shl 3 or (r ushr 2)) shl 16) or ((g shl 2 or (g ushr 4)) shl 8) or (bl shl 3 or (bl ushr 2))
            }
        }
        return Image(w, h, argb)
    }

    // ---------- Denetim ----------

    /**
     * Yüklemeden önce yapı denetimi. Boş liste = sorun yok. Dosyadaki bütün konum ve boyların dosya içinde
     * kaldığını, görüntülerin çözüldüğünü ve öğelerin var olan tanımlara bağlandığını denetler.
     * Dosyada sağlama toplamı yok; aktarım MD5 ve CRC32 ile korunur (belge 7).
     */
    fun check(b: ByteArray): List<String> {
        val p = ArrayList<String>()
        if (b.size < MAIN_HEADER + FACE_HEADER) return listOf("dosya çok kısa")
        if (b.size > B8FaceFile.MAX_SIZE) return listOf("dosya çok büyük")
        for (k in 0 until 4) if (b[k] != MAGIC[k]) return listOf("imza 5A A5 34 12 değil")
        val faces = b[0x1C].toInt() and 0xFF
        if (faces !in 1..2) return listOf("kadran sayısı $faces")
        if (MAIN_HEADER + faces * FACE_HEADER > b.size) return listOf("kadran başlığı dosya dışında")
        val id = cstr(b, 0x28, 0x68)
        if (id == null || id.length != 9 || !id.all { it in '0'..'9' }) p.add("kimlik 9 rakam değil")
        val prev = u32(b, 0x20)
        if (prev == 0L || prev >= b.size) p.add("önizleme yok") else imageOk(b, prev.toInt(), false, p, "önizleme")
        for (f in 0 until faces) checkFace(b, MAIN_HEADER + f * FACE_HEADER, f, p)
        return p
    }

    private fun checkFace(b: ByteArray, h: Int, f: Int, p: MutableList<String>) {
        val defs = HashMap<Pair<Int, Int>, Pair<Int, Int>>() // (tür, sıra) -> (konum, boy)
        val expect = mapOf(0x08 to DEF_ELEMENT, 0x18 to DEF_IMAGE, 0x20 to DEF_LIST, 0x28 to 4, 0x30 to 5, 0x40 to DEF_WIDGET, 0x48 to 8, 0x50 to 9)
        for ((at, type) in expect) {
            val count = u32(b, h + at); val off = u32(b, h + at + 4)
            if (count > 4096 || off + count * DEF_SIZE > b.size) { p.add("ekran $f: bölüm 0x${at.toString(16)} dosya dışında"); return }
            for (i in 0 until count.toInt()) {
                val d = off.toInt() + i * DEF_SIZE
                val t = b[d + 3].toInt() and 0xFF
                val o = u32(b, d + 8); val len = u32(b, d + 12)
                if (t != type) p.add("ekran $f: tanım türü $t, beklenen $type")
                if (len == 0L || o + len > b.size) { p.add("ekran $f: tanım ${type}/$i dosya dışında"); continue }
                defs[t to u16(b, d)] = o.toInt() to len.toInt()
            }
        }
        for ((k, v) in defs) when (k.first) {
            DEF_IMAGE -> imageOk(b, v.first, false, p, "ekran $f görüntü ${k.second}")
            DEF_LIST -> imageOk(b, v.first, true, p, "ekran $f liste ${k.second}")
        }
        val elCount = u32(b, h + 0x08).toInt()
        for (i in 0 until elCount) {
            val def = defs[DEF_ELEMENT to i]
            if (def == null) { p.add("ekran $f: öğe $i tanımı yok"); continue }
            val (o, len) = def
            if (len < 8) { p.add("ekran $f: öğe $i kısa"); continue }
            val ref = u24(b, o); val t = b[o + 3].toInt() and 0xFF
            if (defs[t to ref] == null) p.add("ekran $f: öğe $i olmayan tanıma bağlı ($t/$ref)")
            val x = s16(b, o + 4); val y = s16(b, o + 6)
            if (x !in -WIDTH..WIDTH || y !in -HEIGHT..HEIGHT) p.add("ekran $f: öğe $i ekran dışında")
            if (t == DEF_WIDGET) {
                val (wo, wl) = defs[DEF_WIDGET to ref] ?: continue
                if (wl >= 12) {
                    val kind = (b[wo + 3].toInt() and 0xF0)
                    if (kind == 0x10 || kind == 0x20) {
                        val li = u24(b, wo + 8)
                        if ((b[wo + 11].toInt() and 0xFF) != DEF_LIST || defs[DEF_LIST to li] == null) p.add("ekran $f: öğe $i listesi yok")
                    }
                }
            }
        }
    }

    private fun imageOk(b: ByteArray, at: Int, list: Boolean, p: MutableList<String>, what: String): Boolean {
        if (at < 0 || at + IMAGE_HEADER > b.size) { p.add("$what: dosya dışında"); return false }
        val sign = b[at].toInt() and 0xFF
        val w = u16(b, at + 4); val h = u16(b, at + 6)
        val len = u32(b, at + 8)
        if (w !in 1..WIDTH || h !in 1..HEIGHT) { p.add("$what: boy ${w}x$h"); return false }
        if (sign != SIGN_BGRA && sign != SIGN_RGB565) { p.add("$what: işaret $sign desteklenmiyor"); return false }
        val compressedFlag = if (list) b[at + 2].toInt() else b[at + 1].toInt()
        val count = if (list) b[at + 1].toInt() and 0xFF else 1
        if (count == 0) { p.add("$what: boş liste"); return false }
        val tableAt = at + IMAGE_HEADER
        val dataAt = tableAt + (if (list && compressedFlag == 4) 4 * count else 0)
        if (dataAt > b.size) { p.add("$what: veri dosya dışında"); return false }
        val bpp = if (sign == SIGN_BGRA) 4 else 2
        if (compressedFlag != 4) {
            val need = w.toLong() * h * bpp * count
            if (len != need || dataAt + need > b.size) { p.add("$what: sıkıştırmasız boy tutmuyor"); return false }
            return true
        }
        var o = dataAt
        var sum = 0L
        for (i in 0 until count) {
            val l = if (list) u32(b, tableAt + 4 * i).toInt() else len.toInt()
            val r = unpack(b, o, l)
            if (r == null || r.second != bpp || r.first.size != w * h * bpp) { p.add("$what: görüntü $i çözülemedi"); return false }
            o += l; sum += l
        }
        // Liste toplamı örneklerde iki biçimde: yalnızca yükler ya da yükler + boy tablosu.
        val okSum = if (list) len == sum || len == sum + 4L * count else len == sum
        if (!okSum) { p.add("$what: boy toplamı tutmuyor"); return false }
        return true
    }

    // ---------- yardımcılar ----------

    private fun cstr(b: ByteArray, from: Int, limit: Int): String? {
        var e = from
        while (e < limit && b[e].toInt() != 0) e++
        return if (e >= limit) null else String(b, from, e - from, Charsets.US_ASCII)
    }

    fun le16(b: ByteArray, at: Int, v: Int) { b[at] = v.toByte(); b[at + 1] = (v ushr 8).toByte() }
    fun le24(b: ByteArray, at: Int, v: Int) { le16(b, at, v); b[at + 2] = (v ushr 16).toByte() }
    fun le32(b: ByteArray, at: Int, v: Long) { for (i in 0 until 4) b[at + i] = (v ushr (8 * i)).toByte() }
    fun u16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    fun s16(b: ByteArray, at: Int): Int = u16(b, at).toShort().toInt()
    fun u24(b: ByteArray, at: Int): Int = u16(b, at) or ((b[at + 2].toInt() and 0xFF) shl 16)
    fun u32(b: ByteArray, at: Int): Long = u16(b, at).toLong() or (u16(b, at + 2).toLong() shl 16)
}
