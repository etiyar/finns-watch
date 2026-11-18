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

/** Saatlerim: Band 8 önce taranır ve doğrulanır, ardından anahtarı istenir. */
class WatchesCard(private val act: MainActivity) {

    private val ui: Ui = act.ui
    val view: LinearLayout = ui.vertical()
    private val list: LinearLayout = ui.card(padDp = 16, padVDp = 6)
    private val hint: TextView = ui.caption(BAND8_HINT)

    companion object {
        const val BAND8_HINT = "Band 8 bağlıyken Mi Fitness ve Notify kapalı olmalı."
    }

    init {
        ui.add(view, ui.section("Saatlerim"), topDp = 8)
        ui.add(view, list, topDp = 8)
        hint.visibility = View.GONE
        hint.setPadding(ui.dp(4), 0, ui.dp(4), 0)
        ui.add(view, hint, topDp = 8)
    }

    fun refresh() {
        list.removeAllViews()
        val l = WatchRegistry.list(act)
        if (WatchRegistry.needsRecovery(act)) {
            ui.add(list, ui.row("Saat kayıtlarını kurtar", "Eski yerel kayıtlardan geri yükle") { confirmRecovery() })
            ui.add(list, ui.divider(), height = ui.dp(1))
        }
        for ((i, w) in l.watches.withIndex()) {
            if (i > 0) ui.add(list, ui.divider(), height = ui.dp(1))
            val active = w.id == l.activeId
            val sub = when {
                needsSetup(w) -> "Kurulum gerekli"
                active -> "${w.type.label} · ${WatchManager.state.label}"
                else -> w.type.label
            }
            val trailing = if (active) ui.pill("Etkin", Tone.OK) else null
            ui.add(list, ui.row(w.name, sub, trailing) { actions(w) })
        }
        // Model kısayolu yalnızca kullanıcı kuruluma başladığında kayıt oluşturur.
        for (type in WatchType.entries) {
            if (l.watches.any { it.type == type }) continue
            if (list.childCount > 0) ui.add(list, ui.divider(), height = ui.dp(1))
            ui.add(list, ui.row(type.label, "Kurulum gerekli") { addAndSetUp(type, type.label) })
        }
        ui.add(list, ui.divider(), height = ui.dp(1))
        ui.add(list, ui.row("Saat ekle", null) { add() })
        hint.visibility = if (l.watches.any { it.type == WatchType.BAND8 }) View.VISIBLE else View.GONE
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

    private fun switchTo(w: WatchRecord) {
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
