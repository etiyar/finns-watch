package com.che.ls05.protocol.band8

/**
 * Band 8 metin kuralları. [clean] emojiyi korur (ortak ön temizlik); banda giden metin [forBand] ile emojiyi kısa
 * metne çevirir, çünkü bandın yazı tipi emoji çizemiyor (20.11.2026: boş kutucuk).
 * Kontrol karakterleri ve görünmez yön işaretleri çıkar, satır sonları boşluk olur, boşluklar tekleşir.
 * LS05'in yazı tipi için olan [com.che.ls05.protocol.TextSanitizer] değişmez; LS05 metni bunun üstüne onu uygular.
 */
object B8Text {
    private const val ZWJ = 0x200D
    private const val ELLIPSIS = "…"

    /** Banda giden metin: temizlenir, emoji kısa metne döner ya da çıkar (bant emoji çizemiyor, 20.11.2026). */
    fun forBand(input: String): String = com.che.ls05.protocol.EmojiText.apply(clean(input))

    fun clean(input: String): String {
        val sb = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val cp = input.codePointAt(i)
            i += Character.charCount(cp)
            val type = Character.getType(cp)
            when {
                cp == '\n'.code || cp == '\r'.code || cp == '\t'.code -> sb.append(' ')
                type == Character.CONTROL.toInt() -> Unit
                type == Character.SURROGATE.toInt() -> Unit
                // ZWJ ve etiket karakterleri (bayrak dizileri) emojinin parçası; diğer biçim karakterleri gider.
                type == Character.FORMAT.toInt() && cp != ZWJ && cp !in 0xE0020..0xE007F -> Unit
                else -> sb.appendCodePoint(cp)
            }
        }
        return sb.toString().replace(Regex(" {2,}"), " ").trim()
    }

    private fun isRegional(cp: Int) = cp in 0x1F1E6..0x1F1FF

    /** Önceki karakterle aynı görünür birime (grafem) bağlanıyor mu. Emoji için yeterli, sade bir kural. */
    private fun extends(prev: Int, cp: Int, regionalRun: Int): Boolean {
        if (cp == ZWJ || prev == ZWJ) return true
        if (cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F) return true
        val t = Character.getType(cp)
        if (t == Character.NON_SPACING_MARK.toInt() || t == Character.ENCLOSING_MARK.toInt() ||
            t == Character.COMBINING_SPACING_MARK.toInt()
        ) return true
        return isRegional(cp) && isRegional(prev) && regionalRun % 2 == 1
    }

    /** Metni görünür birimlere böler. */
    fun graphemes(s: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var prev = -1
        var regionalRun = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (prev != -1 && !extends(prev, cp, regionalRun)) {
                out += cur.toString()
                cur.setLength(0)
            }
            regionalRun = if (isRegional(cp)) regionalRun + 1 else 0
            cur.appendCodePoint(cp)
            prev = cp
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    /** UTF-8 bayt sınırına göre, görünür birim bölmeden kısaltır. Kısalırsa sona "…" ekler. */
    fun truncate(s: String, maxBytes: Int): String {
        if (s.toByteArray(Charsets.UTF_8).size <= maxBytes) return s
        val budget = maxBytes - ELLIPSIS.toByteArray(Charsets.UTF_8).size
        val sb = StringBuilder()
        var used = 0
        for (g in graphemes(s)) {
            val n = g.toByteArray(Charsets.UTF_8).size
            if (used + n > budget) break
            sb.append(g)
            used += n
        }
        return sb.append(ELLIPSIS).toString()
    }
}
