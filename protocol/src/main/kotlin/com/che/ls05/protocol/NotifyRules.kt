package com.che.ls05.protocol

import java.time.LocalDateTime

/*
 * Bildirim kuralları (v0.15.0-beta3). İki saat için tek motor; NotifyForwarder saat sürücüsünden önce uygular.
 * Kurallar telefona aittir, saatler arasında ortaktır. Bildirim metni hiçbir yere yazılmaz; yalnızca karar verilir.
 */

enum class RuleAction { ALLOW, BLOCK }

/**
 * Bir kural. Boş alan "hepsi" demektir. [pkg] uygulama paketi (kişisel ve İş WhatsApp ayrı paketlerdir).
 * [contact] başlıkta, [keyword] başlık ya da metinde aranır (büyük/küçük harf ve Türkçe harf duyarsız).
 * [repeatEveryMin] > 0 ise bildirim telefonda durdukça en çok [repeatMax] kez yeniden gönderilir.
 * [vibration]: -1 varsayılan, 0 titreşimsiz, 1-5 titreşim sayısı (LS05 sayı; Band 8'de B8Vibration.count deseni).
 */
data class NotifyRule(
    val id: Int,
    val pkg: String = "",
    val contact: String = "",
    val keyword: String = "",
    val action: RuleAction = RuleAction.ALLOW,
    val repeatEveryMin: Int = 0,
    val repeatMax: Int = 0,
    val vibration: Int = -1,
    val enabled: Boolean = true,
) {
    val specificity: Int get() = (if (pkg.isNotEmpty()) 1 else 0) + (if (contact.isNotEmpty()) 1 else 0) + (if (keyword.isNotEmpty()) 1 else 0)
    val repeats: Boolean get() = repeatEveryMin > 0 && repeatMax > 0
}

/** Sessiz saat aralığı. [days]: bit 0 pazartesi ... bit 6 pazar, başlangıç gününe göre. Dakika 0-1439. Başlangıç = bitiş: bütün gün. */
data class QuietWindow(val days: Int, val startMin: Int, val endMin: Int) {
    companion object {
        const val ALL_DAYS = 0x7F
    }
}

/** Sessiz saatler ve istisnaları. [exceptWords] başlıkta ya da metinde geçerse bildirim yine gider. */
data class QuietHours(
    val enabled: Boolean,
    val windows: List<QuietWindow>,
    val exceptApps: List<String>,
    val exceptWords: List<String>,
) {
    companion object {
        val OFF = QuietHours(false, emptyList(), emptyList(), emptyList())
    }
}

data class NotifyRuleSet(val rules: List<NotifyRule>, val quiet: QuietHours) {
    fun nextId(): Int = (rules.maxOfOrNull { it.id } ?: 0) + 1

    fun encode(): String {
        val sb = StringBuilder(HEADER).append('\n')
        for (r in rules) {
            sb.append(listOf("R", r.id.toString(), if (r.enabled) "1" else "0", esc(r.pkg), esc(r.contact), esc(r.keyword),
                r.action.name, r.repeatEveryMin.toString(), r.repeatMax.toString(), r.vibration.toString()).joinToString("\t")).append('\n')
        }
        sb.append("Q\t").append(if (quiet.enabled) "1" else "0").append('\n')
        for (w in quiet.windows) sb.append("W\t${w.days}\t${w.startMin}\t${w.endMin}\n")
        for (a in quiet.exceptApps) sb.append("XA\t").append(esc(a)).append('\n')
        for (w in quiet.exceptWords) sb.append("XW\t").append(esc(w)).append('\n')
        return sb.toString()
    }

    companion object {
        const val HEADER = "# finnswatch kurallar v1"
        val EMPTY = NotifyRuleSet(emptyList(), QuietHours.OFF)

        private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

        private fun unesc(s: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        't' -> sb.append('\t'); 'n' -> sb.append('\n'); else -> sb.append(s[i + 1])
                    }
                    i += 2
                } else {
                    sb.append(c); i++
                }
            }
            return sb.toString()
        }

        /** Bozuk satırlar atlanır; başlık yoksa boş küme. */
        fun decode(text: String?): NotifyRuleSet {
            if (text == null || !text.startsWith(HEADER)) return EMPTY
            val rules = ArrayList<NotifyRule>()
            var enabled = false
            val windows = ArrayList<QuietWindow>()
            val apps = ArrayList<String>()
            val words = ArrayList<String>()
            for (line in text.split('\n')) {
                val p = line.split('\t')
                try {
                    when (p[0]) {
                        "R" -> if (p.size == 10) rules += NotifyRule(
                            p[1].toInt(), unesc(p[3]), unesc(p[4]), unesc(p[5]), RuleAction.valueOf(p[6]),
                            p[7].toInt().coerceIn(0, 240), p[8].toInt().coerceIn(0, 20), p[9].toInt().coerceIn(-1, 5), p[2] == "1",
                        )
                        "Q" -> if (p.size == 2) enabled = p[1] == "1"
                        "W" -> if (p.size == 4) {
                            val w = QuietWindow(p[1].toInt() and 0x7F, p[2].toInt(), p[3].toInt())
                            if (w.startMin in 0..1439 && w.endMin in 0..1439) windows += w
                        }
                        "XA" -> if (p.size == 2) apps += unesc(p[1])
                        "XW" -> if (p.size == 2) words += unesc(p[1])
                    }
                } catch (_: Exception) {
                }
            }
            return NotifyRuleSet(rules, QuietHours(enabled, windows, apps, words))
        }
    }
}

/** Kurallara sokulacak bildirim bilgisi. Yalnızca bellekte kullanılır. */
data class NoteFacts(val pkg: String, val title: String, val text: String)

sealed class NotifyDecision {
    /** İletilir. [rule]: uyan en özel kural (titreşim ve tekrar için), yoksa null. */
    data class Forward(val rule: NotifyRule?) : NotifyDecision()

    /** İletilmez. [reason] içerik barındırmaz ("kural", "sessiz saat"). */
    data class Block(val reason: String, val ruleId: Int? = null) : NotifyDecision()
}

object NotifyRules {
    private val FOLD = mapOf('ı' to 'i', 'ş' to 's', 'ğ' to 'g', 'ç' to 'c', 'ö' to 'o', 'ü' to 'u', 'â' to 'a', 'î' to 'i', 'û' to 'u')

    /** Türkçe duyarlı küçük harf, ardından harf sadeleştirme: "IŞIK" ve "ışık" ikisi de "isik" olur. */
    fun norm(s: String): String {
        val lower = s.lowercase(java.util.Locale.forLanguageTag("tr")).replace("i\u0307", "i")
        val sb = StringBuilder(lower.length)
        for (c in lower) sb.append(FOLD[c] ?: c)
        return sb.toString().trim()
    }

    private fun contains(hay: String, needle: String) = needle.isEmpty() || norm(hay).contains(norm(needle))

    fun matches(r: NotifyRule, f: NoteFacts): Boolean =
        r.enabled &&
            (r.pkg.isEmpty() || r.pkg == f.pkg) &&
            contains(f.title, r.contact) &&
            (r.keyword.isEmpty() || contains(f.title, r.keyword) || contains(f.text, r.keyword))

    /** Uyan en özel kural; eşitlikte listede önce gelen. */
    fun pick(set: NotifyRuleSet, f: NoteFacts): NotifyRule? =
        set.rules.filter { matches(it, f) }.withIndex()
            .sortedWith(compareByDescending<IndexedValue<NotifyRule>> { it.value.specificity }.thenBy { it.index })
            .firstOrNull()?.value

    fun quietAt(q: QuietHours, at: LocalDateTime): Boolean {
        if (!q.enabled) return false
        val minute = at.hour * 60 + at.minute
        val today = at.dayOfWeek.value - 1          // 0 pazartesi
        val yesterday = (today + 6) % 7
        return q.windows.any { w ->
            fun on(d: Int) = w.days and (1 shl d) != 0
            when {
                w.startMin == w.endMin -> on(today)
                w.startMin < w.endMin -> on(today) && minute >= w.startMin && minute < w.endMin
                else -> (on(today) && minute >= w.startMin) || (on(yesterday) && minute < w.endMin)
            }
        }
    }

    private fun exempt(q: QuietHours, f: NoteFacts): Boolean =
        f.pkg in q.exceptApps || q.exceptWords.any { it.isNotBlank() && (contains(f.title, it) || contains(f.text, it)) }

    fun decide(set: NotifyRuleSet, f: NoteFacts, at: LocalDateTime): NotifyDecision {
        val r = pick(set, f)
        if (r != null && r.action == RuleAction.BLOCK) return NotifyDecision.Block("kural", r.id)
        if (quietAt(set.quiet, at) && !exempt(set.quiet, f)) return NotifyDecision.Block("sessiz saat")
        return NotifyDecision.Forward(r)
    }
}
