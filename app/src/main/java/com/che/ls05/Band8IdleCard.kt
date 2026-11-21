package com.che.ls05

import android.app.AlertDialog
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Saat > Saat ayarları > Hareketsizlik uyarısı (deneysel), Band 8: açık/kapalı ve saat aralığı. Bandın ayarı önce okunur (8/12),
 * yazarken aynı mesaj yalnızca açık mı ve başlangıç/bitiş değiştirilerek geri gider (8/13). Gün seçimi protokolde
 * bilinmiyor; band kendi gün ayarını korur. Kullanıcı cihazda doğrulayana kadar "deneysel".
 */
class Band8IdleCard(private val act: MainActivity) {

    private object Style {
        const val GAP_DP = 8
    }

    private val ui = act.ui
    val view: LinearLayout = ui.vertical()
    private val state: TextView = ui.caption("")
    private val editButton: Button = ui.textButton("Düzenle") { edit() }
    private var requested = false

    init {
        ui.add(view, ui.divider(), height = ui.dp(1))
        val row = ui.horizontal()
        row.minimumHeight = ui.dp(56)
        val texts = ui.vertical()
        ui.add(texts, ui.text("Hareketsizlik uyarısı (deneysel)", 15f))
        ui.add(texts, state, topDp = 2)
        ui.add(row, texts, width = 0, weight = 1f)
        editButton.setTextColor(ui.color(R.color.accent_text))
        ui.add(row, editButton, width = Ui.WRAP, leftDp = 8)
        row.background = ui.pressable(12)
        row.isClickable = true
        row.setOnClickListener { if (WatchHub.service?.bandIdleWindow() == null) read() else edit() }
        ui.add(view, row)
    }

    private fun read() {
        val s = act.readyService() ?: return
        s.bandIdleRead()?.let { act.toast(it) }
    }

    /** Görünür olunca ayar henüz okunmadıysa bir kez okunur (yalnızca okuma). */
    fun refresh() {
        val w = WatchHub.service?.bandIdleWindow()
        if (w == null) {
            state.text = if (WatchHub.ready) "Bandın ayarı okunuyor…" else "Band bağlanınca okunur."
            editButton.isEnabled = false
            if (!requested && WatchHub.ready) {
                requested = true
                WatchHub.service?.bandIdleRead()
            }
            return
        }
        editButton.isEnabled = true
        state.text = "${if (w.enabled) "Açık" else "Kapalı"} · ${Fmt.hm(w.startH, w.startM)} → ${Fmt.hm(w.endH, w.endM)}"
    }

    private fun edit() {
        val w = WatchHub.service?.bandIdleWindow() ?: return
        var sh = w.startH; var sm = w.startM; var eh = w.endH; var em = w.endM
        val box = ui.dialogBox()
        val enabled = ui.check("Açık", w.enabled)
        ui.add(box, enabled, topDp = Style.GAP_DP)
        val start: Button = ui.secondary(Fmt.hm(sh, sm)) {}
        start.setOnClickListener { act.pickTime(sh, sm) { h, m -> sh = h; sm = m; start.text = Fmt.hm(h, m) } }
        val end: Button = ui.secondary(Fmt.hm(eh, em)) {}
        end.setOnClickListener { act.pickTime(eh, em) { h, m -> eh = h; em = m; end.text = Fmt.hm(h, m) } }
        ui.add(box, ui.row("Başlangıç", trailing = start), topDp = Style.GAP_DP)
        ui.add(box, ui.row("Bitiş", trailing = end))
        ui.add(box, ui.caption("Gün seçimi ve öğle arası bandın kendi ayarında kalır. Deneysel: sonucu bandın ayarlar ekranından kontrol et."), topDp = Style.GAP_DP)
        val d = AlertDialog.Builder(act)
            .setTitle("Hareketsizlik uyarısı")
            .setView(box)
            .setPositiveButton("Kaydet") { _, _ ->
                val err = act.readyService()?.bandIdleSave(enabled.isChecked, sh, sm, eh, em)
                act.toast(err ?: "Ayar banda gönderildi.")
            }
            .setNegativeButton("Vazgeç", null)
            .create()
        act.track(d)
        d.show()
    }
}
