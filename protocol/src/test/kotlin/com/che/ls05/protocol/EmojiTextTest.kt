package com.che.ls05.protocol

import com.che.ls05.protocol.band8.B8Command
import com.che.ls05.protocol.band8.B8Messages
import com.che.ls05.protocol.band8.B8Notification
import kotlin.test.Test
import kotlin.test.assertEquals

/** Emoji yerine kısa metin (iki saat için ortak). Bant ve LS05 emoji çizemiyor (20.11.2026: kutucuk). */
class EmojiTextTest {
    private val heart = "❤️"

    @Test
    fun probeLineBecomesReadable() {
        assertEquals("<3 (+1) :D :O :( (dua)", EmojiText.apply("$heart 👍 😂 😮 😢 🙏"))
    }

    @Test
    fun reactionIsReadable() {
        assertEquals("Reacted (+1) to \"3 photos\"", EmojiText.apply("Reacted 👍 to \"3 photos\""))
        assertEquals("Reacted <3 to x", EmojiText.apply("Reacted $heart to x"))
    }

    @Test
    fun skinToneAndVariationSelectorStillMap() {
        assertEquals("(+1)", EmojiText.apply("👍🏽"))
        assertEquals("<3", EmojiText.apply("❤"))
    }

    @Test
    fun unmappedEmojiAndZwjSequencesAreRemoved() {
        val family = "👨‍👩‍👧"
        assertEquals("a b", EmojiText.apply("a $family 🦄 b"))
        assertEquals("bayrak", EmojiText.apply("🇹🇷 bayrak"))
        assertEquals("", EmojiText.apply("🦄"))
    }

    @Test
    fun plainTextAndTurkishUntouched() {
        assertEquals("Merhaba şğıöüç ŞĞİÖÜÇ © 1 → 2", EmojiText.apply("Merhaba şğıöüç ŞĞİÖÜÇ © 1 → 2"))
    }

    @Test
    fun ls05SanitizerUnchangedWithoutMappedEmoji() {
        val samples = listOf("Merhaba 🦄 şğıöüç\nŞĞİÖÜÇ", "a  b", "ş01ğ02ı03", "x\t\ty", "🦄🦄")
        for (s in samples) assertEquals(TextSanitizer.clean(s), TextSanitizer.clean(EmojiText.apply(s)), s)
    }

    @Test
    fun band8NotificationUsesMappedText() {
        val n = B8Notification("p", "App", "Ali $heart", "Reacted 👍 to x", "20250101T093000", 1, "k")
        val inner = B8Command.parse(B8Messages.notification(n))!!.body(9)!!.message(3)!!.message(1)!!
        assertEquals("Ali <3", inner.string(3))
        assertEquals("Reacted (+1) to x", inner.string(5))
    }
}
