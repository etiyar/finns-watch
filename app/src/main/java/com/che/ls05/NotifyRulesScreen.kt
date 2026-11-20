package com.che.ls05

import android.app.AlertDialog
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import com.che.ls05.protocol.NotifyRule
import com.che.ls05.protocol.NotifyRuleSet
import com.che.ls05.protocol.QuietHours
import com.che.ls05.protocol.QuietWindow
import com.che.ls05.protocol.RuleAction

/**
 * Bildirimler > Bildirim kuralları. Kurallar telefona aittir, iki saate de uygulanır (NotifyForwarder).
 * Görünüm kurulumu bu dosyadaki küçük yardımcılarda; kural mantığı protocol/NotifyRules.kt içinde.
 * Bildirim metni ekranda ya da logda gösterilmez; yalnızca kullanıcının yazdığı kural alanları görünür.
 */
class NotifyRulesScreen(private val act: MainActivity) {

    private object Style {
        const val GAP_DP = 8
        const val SECTION_GAP_DP = 20
        const val MAX_REPEAT_EVERY_MIN = 240
        const val MAX_REPEAT_COUNT = 20
        val DAY_NAMES = listOf("Pzt", "Sal", "Çar", "Per", "Cum", "Cmt", "Paz")
        val VIBRATION_LABELS = listOf("Varsayılan", "Titreşimsiz", "1", "2", "3", "4", "5")
    }

    private val ui = act.ui
    private var set: NotifyRuleSet = Prefs.notifyRules(act)
    private val box: LinearLayout = ui.dialogBox()

    fun show() {
        render()
        val d = AlertDialog.Builder(act)
            .setTitle("Bildirim kuralları")
            .setView(ui.scroll(box))
            .setPositiveButton("Kapat", null)
            .create()
        act.track(d)
        d.show()
    }

    private fun save(newSet: NotifyRuleSet) {
        set = newSet
        Prefs.setNotifyRules(act, newSet)
        WatchHub.log("Bildirim kuralları kaydedildi: ${newSet.rules.size} kural, sessiz saat ${if (newSet.quiet.enabled) "açık" else "kapalı"}.")
        render()
    }

    // ---------- Liste ----------

    private fun render() {
        box.removeAllViews()
        ui.add(box, ui.caption("Kurallar iki saate de uygulanır. En çok alanı dolu olan kural geçerlidir. Kural yoksa bildirim iletilir."), topDp = Style.GAP_DP)
        ui.add(box, ui.section("Kurallar"), topDp = Style.GAP_DP)
        val list = ui.card(padDp = 12, padVDp = 4)
        if (set.rules.isEmpty()) ui.add(list, ui.caption("Henüz kural yok."), topDp = Style.GAP_DP)
        set.rules.forEachIndexed { i, r ->
            if (i > 0) ui.add(list, ui.divider(), height = ui.dp(1))
            ui.add(list, ui.row(title(r), subtitle(r)) { editRule(r) })
        }
        ui.add(box, list, topDp = Style.GAP_DP)
        ui.add(box, ui.tonal("Kural ekle") { editRule(null) }, topDp = Style.GAP_DP, width = Ui.WRAP)

        ui.add(box, ui.section("Sessiz saatler"), topDp = Style.SECTION_GAP_DP)
        val q = set.quiet
        val quiet = ui.card(padDp = 12, padVDp = 4)
        ui.add(quiet, ui.switchRow("Sessiz saatler", "Bu aralıklarda istisnalar dışındaki bildirimler saate gitmez.", q.enabled) {
            save(set.copy(quiet = set.quiet.copy(enabled = it)))
        }.view)
        q.windows.forEachIndexed { i, w ->
            ui.add(quiet, ui.divider(), height = ui.dp(1))
            ui.add(quiet, ui.row(windowText(w), "Düzenlemek ya da silmek için dokun") { editWindow(i, w) })
        }
        ui.add(quiet, ui.divider(), height = ui.dp(1))
        ui.add(quiet, ui.row("İstisnalar", exceptionsText(q)) { editExceptions() })
        ui.add(box, quiet, topDp = Style.GAP_DP)
        ui.add(box, ui.tonal("Aralık ekle") { editWindow(null, QuietWindow(QuietWindow.ALL_DAYS, 22 * 60, 7 * 60)) }, topDp = Style.GAP_DP, width = Ui.WRAP)
    }

    private fun appName(pkg: String): String = if (pkg.isEmpty()) "Bütün uygulamalar" else try {
        act.packageManager.getApplicationLabel(act.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }

    private fun title(r: NotifyRule): String =
        (if (r.action == RuleAction.BLOCK) "Engelle: " else "İzin ver: ") + appName(r.pkg) + if (r.enabled) "" else " (kapalı)"

    private fun subtitle(r: NotifyRule): String = listOfNotNull(
        r.contact.takeIf { it.isNotEmpty() }?.let { "kişi \"$it\"" },
        r.keyword.takeIf { it.isNotEmpty() }?.let { "kelime \"$it\"" },
        if (r.repeats) "${r.repeatEveryMin} dk'da bir, en çok ${r.repeatMax} kez tekrar" else null,
        when (r.vibration) { -1 -> null; 0 -> "titreşimsiz"; else -> "${r.vibration} titreşim (LS05)" },
    ).joinToString(" · ").ifEmpty { "Bütün bildirimler" }

    private fun hm(min: Int) = Fmt.hm(min / 60, min % 60)

    private fun windowText(w: QuietWindow): String {
        val days = if (w.days == QuietWindow.ALL_DAYS) "Her gün" else Style.DAY_NAMES.filterIndexed { i, _ -> w.days and (1 shl i) != 0 }.joinToString(", ")
        return "$days · ${hm(w.startMin)} → ${hm(w.endMin)}"
    }

    private fun exceptionsText(q: QuietHours): String {
        val parts = q.exceptApps.map { appName(it) } + q.exceptWords.map { "\"$it\"" }
        return if (parts.isEmpty()) "Yok" else parts.joinToString(", ")
    }

    // ---------- Kural düzenleme ----------

    private fun numberField(hint: String, value: Int): EditText {
        val f = ui.field(hint)
        f.inputType = InputType.TYPE_CLASS_NUMBER
        if (value > 0) f.setText(value.toString())
        return f
    }

    private fun editRule(existing: NotifyRule?) {
        val r = existing ?: NotifyRule(set.nextId())
        var pkg = r.pkg
        var vibration = r.vibration
        val dlg = ui.dialogBox()
        val appButton: Button = ui.secondary(appName(pkg)) {}
        appButton.setOnClickListener { pickApp { chosen -> pkg = chosen; appButton.text = appName(chosen) } }
        ui.add(dlg, ui.row("Uygulama", trailing = appButton), topDp = Style.GAP_DP)
        val contact = ui.field("Kişi ya da grup (başlıkta geçen, boş: hepsi)").also { it.setText(r.contact) }
        val keyword = ui.field("Kelime (başlık ya da metinde, boş: hepsi)").also { it.setText(r.keyword) }
        ui.add(dlg, contact, topDp = Style.GAP_DP)
        ui.add(dlg, keyword, topDp = Style.GAP_DP)
        var actionIndex = if (r.action == RuleAction.BLOCK) 1 else 0
        val action = ui.segmented(listOf("İzin ver", "Engelle"), actionIndex) { actionIndex = it }
        ui.add(dlg, action.view, topDp = Style.GAP_DP)
        ui.add(dlg, ui.caption("Okunana kadar tekrar (telefonda durdukça). Boş: tekrar yok."), topDp = Style.GAP_DP)
        val every = numberField("Kaç dakikada bir", r.repeatEveryMin)
        val max = numberField("En çok kaç kez", r.repeatMax)
        ui.add(dlg, every, topDp = 4)
        ui.add(dlg, max, topDp = 4)
        val vibButton: Button = ui.secondary(vibLabel(vibration)) {}
        vibButton.setOnClickListener {
            val d = AlertDialog.Builder(act).setTitle("Titreşim (LS05)")
                .setItems(Style.VIBRATION_LABELS.toTypedArray()) { _, which -> vibration = which - 1; vibButton.text = vibLabel(vibration) }
                .create()
            act.track(d); d.show()
        }
        ui.add(dlg, ui.row("Titreşim", "Band 8'de şimdilik yalnızca Gelişmiş denemesinde", vibButton), topDp = Style.GAP_DP)
        val enabled: CheckBox = ui.check("Kural açık", r.enabled)
        ui.add(dlg, enabled, topDp = Style.GAP_DP)

        val b = AlertDialog.Builder(act)
            .setTitle(if (existing == null) "Yeni kural" else "Kuralı düzenle")
            .setView(ui.scroll(dlg))
            .setPositiveButton("Kaydet") { _, _ ->
                val e = every.text.toString().toIntOrNull()?.coerceIn(0, Style.MAX_REPEAT_EVERY_MIN) ?: 0
                val m = max.text.toString().toIntOrNull()?.coerceIn(0, Style.MAX_REPEAT_COUNT) ?: 0
                val rule = NotifyRule(
                    r.id, pkg, contact.text.toString().trim(), keyword.text.toString().trim(),
                    if (actionIndex == 1) RuleAction.BLOCK else RuleAction.ALLOW,
                    if (m > 0) e else 0, if (e > 0) m else 0, vibration, enabled.isChecked,
                )
                save(set.copy(rules = if (existing == null) set.rules + rule else set.rules.map { if (it.id == rule.id) rule else it }))
            }
            .setNegativeButton("Vazgeç", null)
        if (existing != null) b.setNeutralButton("Sil") { _, _ -> save(set.copy(rules = set.rules.filter { it.id != existing.id })) }
        val d = b.create()
        act.track(d)
        d.show()
    }

    private fun vibLabel(v: Int) = Style.VIBRATION_LABELS[(v + 1).coerceIn(0, Style.VIBRATION_LABELS.size - 1)]

    /** Bildirim gelmiş uygulamalar arasından seçim; en üstte "Bütün uygulamalar". */
    private fun pickApp(done: (String) -> Unit) {
        val pkgs = listOf("") + Prefs.seenApps(act).sortedBy { appName(it).lowercase() }
        val d = AlertDialog.Builder(act).setTitle("Uygulama")
            .setItems(pkgs.map { appName(it) }.toTypedArray()) { _, which -> done(pkgs[which]) }
            .create()
        act.track(d)
        d.show()
    }

    // ---------- Sessiz saat aralığı ----------

    private fun editWindow(index: Int?, w: QuietWindow) {
        var start = w.startMin
        var end = w.endMin
        val dlg = ui.dialogBox()
        val checks = Style.DAY_NAMES.mapIndexed { i, name -> ui.check(name, w.days and (1 shl i) != 0) }
        val daysRow = LinearLayout(act).also { it.orientation = LinearLayout.VERTICAL }
        checks.forEach { ui.add(daysRow, it) }
        ui.add(dlg, ui.caption("Günler (gece yarısını geçen aralık başladığı güne aittir)"), topDp = Style.GAP_DP)
        ui.add(dlg, daysRow, topDp = 4)
        val startBtn: Button = ui.secondary(hm(start)) {}
        startBtn.setOnClickListener { act.pickTime(start / 60, start % 60) { h, m -> start = h * 60 + m; startBtn.text = hm(start) } }
        val endBtn: Button = ui.secondary(hm(end)) {}
        endBtn.setOnClickListener { act.pickTime(end / 60, end % 60) { h, m -> end = h * 60 + m; endBtn.text = hm(end) } }
        ui.add(dlg, ui.row("Başlangıç", trailing = startBtn), topDp = Style.GAP_DP)
        ui.add(dlg, ui.row("Bitiş", trailing = endBtn))
        val b = AlertDialog.Builder(act)
            .setTitle("Sessiz saat aralığı")
            .setView(ui.scroll(dlg))
            .setPositiveButton("Kaydet") { _, _ ->
                var days = 0
                checks.forEachIndexed { i, c -> if (c.isChecked) days = days or (1 shl i) }
                if (days == 0) { act.toast("En az bir gün seç."); return@setPositiveButton }
                val nw = QuietWindow(days, start, end)
                val list = set.quiet.windows.toMutableList()
                if (index == null) list += nw else list[index] = nw
                save(set.copy(quiet = set.quiet.copy(windows = list)))
            }
            .setNegativeButton("Vazgeç", null)
        if (index != null) b.setNeutralButton("Sil") { _, _ ->
            save(set.copy(quiet = set.quiet.copy(windows = set.quiet.windows.filterIndexed { i, _ -> i != index })))
        }
        val d = b.create()
        act.track(d)
        d.show()
    }

    /** İstisnalar: uygulamalar listeden, kelimeler virgülle. */
    private fun editExceptions() {
        val q = set.quiet
        var apps = q.exceptApps
        val dlg = ui.dialogBox()
        val appsText = ui.caption("")
        fun refreshApps() { appsText.text = if (apps.isEmpty()) "Uygulama istisnası yok" else apps.joinToString(", ") { appName(it) } }
        refreshApps()
        ui.add(dlg, appsText, topDp = Style.GAP_DP)
        ui.add(dlg, ui.secondary("Uygulama ekle") {
            pickApp { p -> if (p.isNotEmpty() && p !in apps) { apps = apps + p; refreshApps() } }
        }, topDp = 4, width = Ui.WRAP)
        ui.add(dlg, ui.secondary("Uygulamaları temizle") { apps = emptyList(); refreshApps() }, topDp = 4, width = Ui.WRAP)
        val words = ui.field("Kişi ya da kelimeler, virgülle").also { it.setText(q.exceptWords.joinToString(", ")) }
        ui.add(dlg, words, topDp = Style.GAP_DP)
        val d = AlertDialog.Builder(act)
            .setTitle("Sessiz saat istisnaları")
            .setView(ui.scroll(dlg))
            .setPositiveButton("Kaydet") { _, _ ->
                val w = words.text.toString().split(',').map { it.trim() }.filter { it.isNotEmpty() }
                save(set.copy(quiet = set.quiet.copy(exceptApps = apps, exceptWords = w)))
            }
            .setNegativeButton("Vazgeç", null)
            .create()
        act.track(d)
        d.show()
    }
}
