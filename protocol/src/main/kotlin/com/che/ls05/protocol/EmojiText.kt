package com.che.ls05.protocol

import com.che.ls05.protocol.band8.B8Text

/**
 * Emoji yerine kısa metin. İki saat de emoji çizemiyor (LS05 yazı tipi; Band 8'de 20.11.2026 testinde boş kutucuk).
 * Küçük ve okunur bir eşleme: tanınan emoji metne döner, tanınmayan emoji, ZWJ dizileri, ten rengi ve değişken
 * seçiciler çıkar. Emoji olmayan metin değişmez.
 */
object EmojiText {
    private val MAP: Map<Int, String> = buildMap {
        for (cp in listOf(0x2764, 0x2665, 0x1F60D, 0x1F970, 0x1F497, 0x1F496, 0x1F495, 0x1F49C, 0x1F499, 0x1F49A, 0x1F49B, 0x1F5A4, 0x1F90D)) put(cp, "<3")
        put(0x1F44D, "(+1)")
        put(0x1F44E, "(-1)")
        for (cp in listOf(0x1F602, 0x1F923, 0x1F606)) put(cp, ":D")
        for (cp in listOf(0x1F600, 0x1F603, 0x1F604, 0x1F601, 0x1F60A, 0x1F642)) put(cp, ":)")
        put(0x1F609, ";)")
        for (cp in listOf(0x1F62E, 0x1F62F, 0x1F632, 0x1F631)) put(cp, ":O")
        for (cp in listOf(0x1F622, 0x1F62D, 0x1F61E, 0x2639, 0x1F641, 0x1F614)) put(cp, ":(")
        put(0x1F64F, "(dua)")
        put(0x1F618, ":*")
        put(0x1F44F, "(alkış)")
        put(0x1F525, "(ateş)")
        put(0x1F389, "(kutlama)")
        put(0x1F44B, "(selam)")
        for (cp in listOf(0x1F621, 0x1F620)) put(cp, ">:(")
        put(0x1F914, "(?)")
        for (cp in listOf(0x2705, 0x2714)) put(cp, "(tamam)")
        put(0x274C, "(x)")
    }

    private fun isModifier(cp: Int) = cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF

    private fun isEmoji(cp: Int) =
        cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF || cp in 0x2300..0x23FF ||
            cp == 0x200D || isModifier(cp) || cp in 0xE0020..0xE007F || cp == 0x3030 || cp == 0x303D

    fun apply(input: String): String {
        if (input.codePoints().noneMatch { isEmoji(it) }) return input
        val sb = StringBuilder(input.length)
        for (g in B8Text.graphemes(input)) {
            val cps = g.codePoints().toArray()
            if (cps.none { isEmoji(it) }) { sb.append(g); continue }
            val core = cps.filter { !isModifier(it) }
            val mapped = if (core.size == 1) MAP[core[0]] else null
            if (mapped != null) sb.append(mapped)
        }
        return sb.toString().replace(Regex(" {2,}"), " ").trim()
    }
}
