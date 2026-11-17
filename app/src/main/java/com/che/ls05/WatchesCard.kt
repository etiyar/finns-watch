package com.che.ls05

import android.app.AlertDialog
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.che.ls05.protocol.WatchList
import com.che.ls05.protocol.WatchRecord
import com.che.ls05.protocol.WatchType

/**
 * Saat sekmesinin en üstündeki "Saatlerim" listesi: saat ekle, etkin yap, yeniden adlandır, sil.
 * Bu sürümde yalnızca LS05 eklenebilir; kayıt Band 8 türünü de taşır.
 */
class WatchesCard(private val act: MainActivity) {

    private val ui: Ui = act.ui
    val view: LinearLayout = ui.vertical()
    private val list: LinearLayout = ui.card(padDp = 16, padVDp = 6)
    private val hint: TextView = ui.caption(BAND8_HINT)

    companion object {
        const val BAND8_HINT = "Band 8 bağlıyken Mi Fitness ve Notify kapalı olmalı."

        /** "Saat ekle"de sunulan türler. Band 8, sürücüsü gelince eklenir. */
        val ADDABLE = listOf(WatchType.LS05)
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
        if (l.watches.isEmpty()) {
            ui.add(list, ui.row("Henüz saat yok", "Saat ekle ile başla. İlk LS05, Ayarlar > Saat bağlantısı'ndan da bağlanabilir."))
        }
        for ((i, w) in l.watches.withIndex()) {
            if (i > 0) ui.add(list, ui.divider(), height = ui.dp(1))
            val active = w.id == l.activeId
            val sub = if (active) "${w.type.label} · ${WatchManager.state.label}" else w.type.label
            val trailing = if (active) ui.pill("Etkin", Tone.OK) else null
            ui.add(list, ui.row(w.name, sub, trailing) { actions(w) })
        }
        ui.add(list, ui.divider(), height = ui.dp(1))
        ui.add(list, ui.row("Saat ekle", null) { add() })
        hint.visibility = if (l.watches.any { it.type == WatchType.BAND8 }) View.VISIBLE else View.GONE
    }

    private fun actions(w: WatchRecord) {
        val l = WatchRegistry.list(act)
        val active = w.id == l.activeId
        val labels = ArrayList<String>()
        val doers = ArrayList<() -> Unit>()
        if (!active) {
            labels += "Bu saati tak (etkin yap)"
            doers += { switchTo(w) }
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

    /** Tür seçimi (bu sürümde yalnızca LS05), ad, sonra etkin yapma ve kurulum. */
    private fun add() {
        val types = ADDABLE
        val d = AlertDialog.Builder(act)
            .setTitle("Saat ekle")
            .setItems(types.map { it.label }.toTypedArray()) { _, which ->
                val type = types[which]
                nameDialog("Saat adı", type.label) { name ->
                    val rec = WatchRegistry.add(act, type, name)
                    val err = WatchManager.switchTo(act, rec.id)
                    if (err != null) {
                        act.toast(err)
                    } else {
                        act.toast("Şimdi Ayarlar > Saat bağlantısı'ndan Tara ile saati seç.")
                    }
                    refresh()
                }
            }
            .setNegativeButton("Vazgeç", null)
            .create()
        act.track(d)
        d.show()
    }
}
