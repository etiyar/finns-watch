package com.che.ls05

import android.app.AlertDialog
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.che.ls05.protocol.UteCommand
import com.che.ls05.protocol.WatchList
import com.che.ls05.protocol.WatchRecord
import com.che.ls05.protocol.WatchType

/**
 * Ayarlar > Saatlerim: kayıtlı saatler, etkin saat (bağlı, pil), "Etkin yap", kimlik satırı ve "Saat ekle".
 * Band 8 önce taranır ve doğrulanır, ardından anahtarı istenir.
 */
class WatchesCard(private val act: MainActivity) {

    private val ui: Ui = act.ui
    val view: LinearLayout = ui.vertical()
    private val list: LinearLayout = ui.vertical()
    private val hint: TextView = ui.caption(BAND8_HINT)

    companion object {
        const val BAND8_HINT = "Band 8 için Mi Fitness ve Notify kaldırılmalı ya da Bluetooth izinleri kapatılmalı. Bantla aynı anda konuşurlarsa bildirimler ACK alır ama bantta görünmez."
    }

    init {
        ui.add(view, ui.section("Saatlerim"))
        list.background = ui.cardShape()
        ui.add(view, list, topDp = 8)
        hint.visibility = View.GONE
        hint.setPadding(ui.dp(4), 0, ui.dp(4), 0)
        ui.add(view, hint, topDp = 8)
    }

    /** Saatin küçük simgesi: Band 8 dikdörtgen, LS05 yuvarlak (siyah ekran, ince kasa). */
    private fun watchIcon(type: WatchType): View {
        val v = View(act)
        val d = ui.round(ui.color(R.color.face_bg), if (type == WatchType.BAND8) 11 else 20)
        d.setStroke(ui.dp(2), ui.color(R.color.face_case))
        v.background = d
        v.layoutParams = if (type == WatchType.BAND8) LinearLayout.LayoutParams(ui.dp(22), ui.dp(52))
        else LinearLayout.LayoutParams(ui.dp(40), ui.dp(40))
        v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        return v
    }

    private fun addRow(content: View, first: Boolean) {
        if (!first) ui.add(list, ui.divider(), height = ui.dp(1))
        ui.add(list, content)
    }

    private var lastKey: String? = null

    fun refresh() {
        val l = WatchRegistry.list(act)
        // Liste yalnızca görünen bilgi değişince yeniden kurulur; açık kimlik satırı durum güncellemesiyle kapanmasın.
        val key = listOf(l.watches.joinToString { "${it.id}:${it.name}:${needsSetup(it)}" }, l.activeId, WatchHub.ready,
            WatchHub.battery, WatchManager.state, WatchRegistry.needsRecovery(act), WatchHub.bandForeignRecent()).toString()
        if (key == lastKey) return
        lastKey = key
        list.removeAllViews()
        var first = true
        if (WatchRegistry.needsRecovery(act)) {
            addRow(pad(ui.row("Saat kayıtlarını kurtar", "Eski yerel kayıtlardan geri yükle") { confirmRecovery() }), first)
            first = false
        }
        for (w in l.watches) {
            val active = w.id == l.activeId
            val box = ui.vertical()
            val r = ui.horizontal()
            r.minimumHeight = ui.dp(72)
            r.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14))
            val iconBox = android.widget.FrameLayout(act)
            iconBox.addView(watchIcon(w.type).also {
                (it.layoutParams as LinearLayout.LayoutParams).let { lp -> it.layoutParams = android.widget.FrameLayout.LayoutParams(lp.width, lp.height, android.view.Gravity.CENTER) }
            })
            ui.add(r, iconBox, width = ui.dp(40), height = Ui.WRAP)
            val texts = ui.vertical()
            ui.add(texts, ui.text(w.name, 15f, R.color.text, bold = true))
            val (sub, subColor) = when {
                needsSetup(w) -> "Kurulum gerekli" to R.color.warning
                active && WatchHub.ready -> (listOfNotNull("Etkin", "Bağlı", WatchHub.battery?.let { "%$it" }).joinToString(" · ")) to R.color.accent_text
                active -> "Etkin · ${WatchManager.state.label}" to R.color.text2
                else -> "Kayıtlı · ${w.type.label}" to R.color.text2
            }
            ui.add(texts, ui.text(sub, 12f, subColor, bold = active), topDp = 3)
            ui.add(r, texts, width = 0, weight = 1f, leftDp = 12)
            if (!active && !needsSetup(w)) {
                ui.add(r, ui.outlineAccent("Etkin yap") { switchTo(w) }, width = Ui.WRAP, leftDp = 8)
            } else {
                ui.icon(R.drawable.ic_chevron, R.color.text2)?.let { ui.add(r, it, width = ui.dp(18), height = ui.dp(18), leftDp = 8) }
            }
            r.background = ui.pressable(20)
            r.isClickable = true
            r.setOnClickListener { actions(w) }
            ui.add(box, r)
            // Kimlik satırı: anahtar ya da kullanıcı kimliği, maskeli (CredentialRow).
            if (!needsSetup(w) || w.type == WatchType.LS05) {
                val cred = CredentialRow.build(act, ui, w)
                cred.setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(14))
                ui.add(box, cred)
            }
            addRow(box, first)
            first = false
        }
        // Model kısayolu yalnızca kullanıcı kuruluma başladığında kayıt oluşturur.
        for (type in WatchType.entries) {
            if (l.watches.any { it.type == type }) continue
            addRow(pad(ui.row(type.label, "Kurulum gerekli") { addAndSetUp(type, type.label) }), first)
            first = false
        }
        val add = ui.horizontal()
        add.gravity = android.view.Gravity.CENTER
        add.minimumHeight = ui.dp(52)
        ui.icon(R.drawable.ic_plus, R.color.accent_text)?.let { add.addView(it, LinearLayout.LayoutParams(ui.dp(18), ui.dp(18))) }
        ui.add(add, ui.text("Saat ekle", 14f, R.color.accent_text, bold = true), width = Ui.WRAP, leftDp = 6)
        add.background = ui.pressable(20)
        add.isClickable = true
        add.setOnClickListener { add() }
        addRow(add, first)
        val foreign = l.active?.type == WatchType.BAND8 && WatchHub.bandForeignRecent()
        hint.text = if (foreign) WatchService.FOREIGN_CLIENT_WARNING else BAND8_HINT
        hint.setTextColor(act.getColor(if (foreign) R.color.danger else R.color.text2))
        hint.visibility = if (l.watches.any { it.type == WatchType.BAND8 }) View.VISIBLE else View.GONE
    }

    private fun pad(v: LinearLayout): LinearLayout {
        v.setPadding(ui.dp(16), v.paddingTop, ui.dp(16), v.paddingBottom)
        return v
    }

    private fun needsSetup(w: WatchRecord): Boolean {
        if (Prefs.address(act, w.ns) == null) return true
        return when (w.type) {
            WatchType.LS05 -> UteCommand.parseUserId(Prefs.userIdText(act, w.ns)) == null
            WatchType.BAND8 -> !SecretStore.has(act, w.id)
        }
    }

    private fun actions(w: WatchRecord) {
        val l = WatchRegistry.list(act)
        val active = w.id == l.activeId
        val labels = ArrayList<String>()
        val doers = ArrayList<() -> Unit>()
        if (needsSetup(w)) {
            labels += "Kurulumu aç"
            doers += { setUp(w) }
        } else if (!active) {
            labels += "Bu saati tak (etkin yap)"
            doers += { switchTo(w) }
        }
        if (active && w.type == WatchType.BAND8 && SecretStore.has(act, w.id)) {
            labels += "Kimlik anahtarını değiştir"
            doers += { act.showBandKeyEditor() }
        }
        labels += "Yeniden adlandır"
        doers += { rename(w) }
        if (!active) {
            labels += "Sil"
            doers += { confirmRemove(w) }
        }
        val d = AlertDialog.Builder(act)
            .setTitle(w.name)
            .setItems(labels.toTypedArray()) { _, which -> doers[which]() }
            .setNegativeButton("Vazgeç", null)
            .create()
        act.track(d)
        d.show()
    }

    fun switchTo(w: WatchRecord) {
        val err = WatchManager.switchTo(act, w.id)
        if (err != null) act.toast(err) else act.toast("${w.name} etkin; bağlanılıyor.")
        refresh()
    }

    private fun nameDialog(title: String, initial: String, onOk: (String) -> Unit) {
        val box = ui.dialogBox()
        val input = ui.field("Saat adı")
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.filters = arrayOf(android.text.InputFilter.LengthFilter(WatchList.MAX_NAME))
        input.setText(initial)
        ui.add(box, input, topDp = 16)
        val d = AlertDialog.Builder(act)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Kaydet") { _, _ -> onOk(input.text.toString()) }
            .setNegativeButton("Vazgeç", null)
            .create()
        act.track(d)
        d.show()
    }

    private fun rename(w: WatchRecord) {
        nameDialog("Yeniden adlandır", w.name) { name ->
            WatchRegistry.rename(act, w.id, name)
            refresh()
        }
    }

    private fun confirmRemove(w: WatchRecord) {
        val d = AlertDialog.Builder(act)
            .setTitle("${w.name} silinsin mi?")
            .setMessage("Saatin bu telefondaki ayarları silinir. Sağlık geçmişi kalır. Saatteki eşleşmeye dokunulmaz.")
            .setPositiveButton("Sil") { _, _ ->
                if (!WatchRegistry.remove(act, w.id)) act.toast("Etkin saat silinemez.")
                refresh()
            }
            .setNegativeButton("Vazgeç", null)
            .create()
        act.track(d)
        d.show()
    }

    /** İkinci bir saat için tür ve ad seçimi. */
    private fun add() {
        val types = WatchType.entries
        val d = AlertDialog.Builder(act)
            .setTitle("Saat ekle")
            .setItems(types.map { it.label }.toTypedArray()) { _, which ->
                val type = types[which]
                nameDialog("Saat adı", type.label) { name ->
                    addAndSetUp(type, name)
                }
            }
            .setNegativeButton("Vazgeç", null)
            .create()
        act.track(d)
        d.show()
    }
    private fun addAndSetUp(type: WatchType, name: String) {
        val rec = try {
            WatchRegistry.add(act, type, name)
        } catch (e: IllegalStateException) {
            act.toast(e.message ?: "Saat kaydedilemedi.")
            return
        }
        setUp(rec)
    }

    private fun confirmRecovery() {
        val d = AlertDialog.Builder(act)
            .setTitle("Saat kayıtlarını kurtar")
            .setMessage("Saat listesi okunamıyor. Telefonda adresi ve şifreli anahtarı kalan Band 8 kayıtları geri alınacak. Bozuk listenin iki kopyası da saklanacak. Eksik saatler yeniden kurulmak zorunda kalabilir.")
            .setPositiveButton("Kurtar") { _, _ ->
                val count = WatchRegistry.recoverLocalDevices(act)
                act.toast(if (count > 0) "$count Band 8 kaydı geri alındı. Saati seçip bağlantıyı dene." else "Kurtarılabilir Band 8 kaydı bulunamadı; veriler değiştirilmedi.")
                refresh()
            }
            .setNegativeButton("Vazgeç", null)
            .create()
        act.track(d)
        d.show()
    }

    private fun setUp(rec: WatchRecord) {
        val err = WatchManager.switchTo(act, rec.id)
        if (err != null) act.toast(err)
        else act.settings.openWatchConnection()
        refresh()
    }
}

/** Ana > etkin saat kartı: dokununca kayıtlı saatler arasından seçim. */
object WatchSwitcher {
    fun show(act: MainActivity) {
        val l = WatchRegistry.list(act)
        if (l.watches.isEmpty()) {
            act.selectTab(3)
            return
        }
        val labels = l.watches.map { if (it.id == l.activeId) "${it.name} (etkin)" else it.name } + "Saatlerimi yönet"
        val d = android.app.AlertDialog.Builder(act)
            .setTitle("Saat seç")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == l.watches.size) {
                    act.selectTab(3)
                    return@setItems
                }
                val w = l.watches[which]
                if (w.id == l.activeId) return@setItems
                val err = WatchManager.switchTo(act, w.id)
                act.toast(err ?: "${w.name} etkin; bağlanılıyor.")
            }
            .setNegativeButton("Vazgeç", null)
            .create()
        act.track(d)
        d.show()
    }
}
