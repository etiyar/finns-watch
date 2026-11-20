package com.che.ls05.protocol.band8

import com.che.ls05.protocol.TextSanitizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Band 8 metni: emoji kalır, kontrol karakterleri gider. LS05 temizleyicisi değişmez. Metinler uydurmadır. */
class B8TextTest {
    private val heart = "❤️"              // ❤️ (değişken seçici)
    private val family = "👨‍👩‍👧" // ZWJ dizisi
    private val thumbDark = "👍🏿" // 👍🏿 ten rengi
    private val flagTr = "🇹🇷"    // 🇹🇷 bölge harfleri

    @Test
    fun keepsEmojiSequencesAndVariationSelectors() {
        val s = "Tepki $heart 👍 😂 😮 😢 🙏 $family $thumbDark $flagTr"
        assertEquals(s, B8Text.clean(s))
    }

    @Test
    fun reactionKeepsEmoji() {
        assertEquals("Reacted $heart to \"3 photos\"", B8Text.clean("Reacted $heart to \"3 photos\""))
    }

    @Test
    fun removesControlsAndCollapsesWhitespace() {
        assertEquals("a b c d", B8Text.clean("  a\u0000\n\r b\t\u0007 c‎   d \u0085 "))
    }

    @Test
    fun dropsLoneSurrogates() {
        assertEquals("ab", B8Text.clean("a\uD83Db"))
    }

    @Test
    fun ls05SanitizerUnchangedAfterBand8Clean() {
        val samples = listOf(
            "Merhaba 😀 şğıöüç\nŞĞİÖÜÇ $heart", "a  $family  b", "😀‍", "  x\t\ty  ",
            "Reacted $heart to \"3 photos\"", "ş01ğ02ı03", "a‎b﻿c", "$flagTr$thumbDark z", "\u0000\u0007",
        )
        for (s in samples) assertEquals(TextSanitizer.clean(s), TextSanitizer.clean(B8Text.clean(s)), s)
    }

    @Test
    fun truncateNeverSplitsGraphemes() {
        // "ab" + aile (18 bayt) + "cd": 12 baytta aile sığmaz, yarım kalmaz.
        val s = "ab${family}cd"
        val t = B8Text.truncate(s, 12)
        assertEquals("ab…", t)
        assertTrue(t.toByteArray(Charsets.UTF_8).size <= 12)
        assertEquals("ab${heart}…", B8Text.truncate("ab${heart}${heart}cd", 2 + 6 + 3 + 2))
        assertEquals("x…", B8Text.truncate("x$thumbDark", 7), "ten rengi ayrılmaz")
        assertEquals("x…", B8Text.truncate("x$flagTr", 8), "bayrak çifti ayrılmaz")
    }

    @Test
    fun truncateKeepsShortText() {
        assertEquals("ab$heart", B8Text.truncate("ab$heart", 100))
    }

    @Test
    fun notificationKeepsEmojiInTitleAndBody() {
        val n = B8Notification("p", "App", "Ali $heart", "Reacted 👍 to x", "20250101T093000", 1, "k")
        val inner = B8Command.parse(B8Messages.notification(n))!!.body(9)!!.message(3)!!.message(1)!!
        assertEquals("Ali $heart", inner.string(3))
        assertEquals("Reacted 👍 to x", inner.string(5))
    }

    @Test
    fun emojiProbeText() {
        assertEquals("$heart 👍 😂 😮 😢 🙏", B8Text.EMOJI_TEST)
    }
}
