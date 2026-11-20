package com.che.ls05.protocol

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Bildirim kuralları motoru. Metinler ve kişiler uydurmadır. */
class NotifyRulesTest {
    private val wa = "com.whatsapp"
    private val wab = "com.whatsapp.w4b"
    // 2025-01-06 Pazartesi
    private fun at(day: Int, h: Int, m: Int) = LocalDateTime.of(2025, 1, day, h, m)
    private val monNoon = at(6, 12, 0)

    private fun set(vararg rules: NotifyRule, quiet: QuietHours = QuietHours.OFF) = NotifyRuleSet(rules.toList(), quiet)
    private fun facts(pkg: String = wa, title: String = "Ayşe", text: String = "Merhaba") = NoteFacts(pkg, title, text)

    @Test
    fun noRulesForwards() {
        assertTrue(NotifyRules.decide(set(), facts(), monNoon) is NotifyDecision.Forward)
    }

    @Test
    fun blockWholeApp() {
        val d = NotifyRules.decide(set(NotifyRule(1, pkg = wab, action = RuleAction.BLOCK)), facts(pkg = wab), monNoon)
        assertTrue(d is NotifyDecision.Block)
        assertTrue(NotifyRules.decide(set(NotifyRule(1, pkg = wab, action = RuleAction.BLOCK)), facts(pkg = wa), monNoon) is NotifyDecision.Forward, "kişisel WhatsApp ayrı")
    }

    @Test
    fun turkishAwareCaseInsensitiveContactAndKeyword() {
        val r = NotifyRule(1, contact = "AYSE", action = RuleAction.BLOCK)
        assertTrue(NotifyRules.decide(set(r), facts(title = "ayşe yılmaz"), monNoon) is NotifyDecision.Block)
        val k = NotifyRule(2, keyword = "ışık", action = RuleAction.BLOCK)
        assertTrue(NotifyRules.decide(set(k), facts(text = "IŞIK açık mı"), monNoon) is NotifyDecision.Block)
        assertTrue(NotifyRules.decide(set(k), facts(title = "İŞIK grubu", text = "x"), monNoon) is NotifyDecision.Block, "başlıkta da aranır")
    }

    @Test
    fun moreSpecificRuleWins() {
        val blockApp = NotifyRule(1, pkg = wa, action = RuleAction.BLOCK)
        val allowFamily = NotifyRule(2, pkg = wa, contact = "Annem", action = RuleAction.ALLOW, vibration = 3)
        val d = NotifyRules.decide(set(blockApp, allowFamily), facts(title = "Annem"), monNoon)
        assertEquals(2, (d as NotifyDecision.Forward).rule?.id)
        assertTrue(NotifyRules.decide(set(blockApp, allowFamily), facts(title = "Başkası"), monNoon) is NotifyDecision.Block)
    }

    @Test
    fun disabledRuleIgnored() {
        val r = NotifyRule(1, pkg = wa, action = RuleAction.BLOCK, enabled = false)
        assertTrue(NotifyRules.decide(set(r), facts(), monNoon) is NotifyDecision.Forward)
    }

    private val night = QuietWindow(QuietWindow.ALL_DAYS, 22 * 60, 7 * 60)
    private val weekdayLunch = QuietWindow(0b0011111, 12 * 60, 13 * 60) // Pzt-Cum

    @Test
    fun quietWindowsCrossMidnightAndDays() {
        val q = QuietHours(true, listOf(night, weekdayLunch), emptyList(), emptyList())
        assertTrue(NotifyRules.quietAt(q, at(6, 23, 0)))
        assertTrue(NotifyRules.quietAt(q, at(7, 6, 59)), "gece yarısını geçer")
        assertTrue(!NotifyRules.quietAt(q, at(7, 7, 0)))
        assertTrue(NotifyRules.quietAt(q, at(6, 12, 30)), "pazartesi öğle")
        assertTrue(!NotifyRules.quietAt(q, at(11, 12, 30)), "cumartesi öğle değil")
        assertTrue(!NotifyRules.quietAt(q.copy(enabled = false), at(6, 23, 0)))
    }

    @Test
    fun midnightWindowUsesStartDay() {
        // Yalnızca cuma gecesi (cuma 23:00 - cumartesi 02:00).
        val fri = QuietHours(true, listOf(QuietWindow(1 shl 4, 23 * 60, 2 * 60)), emptyList(), emptyList())
        assertTrue(NotifyRules.quietAt(fri, at(11, 1, 0)), "cumartesi 01:00 cuma gecesine ait")
        assertTrue(!NotifyRules.quietAt(fri, at(12, 1, 0)), "pazar 01:00 değil")
    }

    @Test
    fun quietHoursBlockUnlessException() {
        val q = QuietHours(true, listOf(night), exceptApps = listOf("com.android.phone"), exceptWords = listOf("acil"))
        val late = at(6, 23, 30)
        val d = NotifyRules.decide(set(quiet = q), facts(), late)
        assertEquals("sessiz saat", (d as NotifyDecision.Block).reason)
        assertTrue(NotifyRules.decide(set(quiet = q), facts(pkg = "com.android.phone"), late) is NotifyDecision.Forward)
        assertTrue(NotifyRules.decide(set(quiet = q), facts(text = "ACİL dönüş yap"), late) is NotifyDecision.Forward)
    }

    @Test
    fun ruleSetRoundTripsWithEscaping() {
        val s = NotifyRuleSet(
            listOf(
                NotifyRule(3, pkg = wab, contact = "Ali\tVeli", keyword = "a\\b\nc", action = RuleAction.BLOCK),
                NotifyRule(4, pkg = wa, contact = "Annem", repeatEveryMin = 5, repeatMax = 3, vibration = 2, enabled = false),
            ),
            QuietHours(true, listOf(night, weekdayLunch), listOf("com.android.phone"), listOf("acil", "bebek")),
        )
        assertEquals(s, NotifyRuleSet.decode(s.encode()))
        assertEquals(NotifyRuleSet.EMPTY, NotifyRuleSet.decode(null))
        assertEquals(NotifyRuleSet.EMPTY, NotifyRuleSet.decode("çöp"))
    }

    @Test
    fun corruptLineSkippedOthersKept() {
        val s = NotifyRuleSet(listOf(NotifyRule(1, pkg = wa)), QuietHours.OFF)
        val text = s.encode() + "R\tbozuk\n"
        assertEquals(s, NotifyRuleSet.decode(text))
    }

    @Test
    fun nextIdIsUnique() {
        assertEquals(5, set(NotifyRule(1), NotifyRule(4)).nextId())
        assertEquals(1, set().nextId())
    }
}
