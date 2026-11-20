package com.che.ls05

import android.app.AlertDialog
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Saat > Hareketsizlik uyarısı (deneysel), Band 8: açık/kapalı ve saat aralığı. Bandın ayarı önce okunur (8/12),
 * yazarken aynı mesaj yalnızca açık mı ve başlangıç/bitiş değiştirilerek geri gider (8/13). Gün seçimi protokolde
 * bilinmiyor; band kendi gün ayarını korur. Kullanıcı cihazda doğrulayana kadar "deneysel".
 */
class Band8IdleCard(private val act: MainActivity) {

    private object Style {
        const val GAP_DP = 8
    }

    private val ui = act.ui
    val view: LinearLayout = ui.vertical()
    private val card: LinearLayout = ui.card(padDp = 16, padVDp = 10)
    private val state: TextView = ui.text("", 16f, R.color.text, bold = true)
    private val note: TextView = ui.caption("Deneysel: bandda doğrulanana kadar sonucu bandın ayarlar ekranından kontrol et.")
    private val editButton: Button = ui.tonal("Düzenle") { edit() }
    private var requested = false

    init {
        ui.add(card, ui.cardTitle("Hareketsizlik uyarısı (deneysel)"))
        ui.add(card, state, topDp = Style.GAP_DP)
        ui.add(card, note, topDp = 4)
        val row = ui.horizontal()
        ui.add(row, editButton, width = Ui.WRAP)
        ui.add(row, ui.secondary("Bandtan oku") { read() }, width = Ui.WRAP, leftDp = Style.GAP_DP)
        ui.add(card, row, topDp = Style.GAP_DP)
        ui.add(view, card, topDp = 10)
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
        ui.add(box, ui.caption("Gün seçimi ve öğle arası bandın kendi ayarında kalır."), topDp = Style.GAP_DP)
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
