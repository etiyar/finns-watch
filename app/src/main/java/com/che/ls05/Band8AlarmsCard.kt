package com.che.ls05

import android.app.AlertDialog
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.che.ls05.protocol.band8.B8Alarm
import com.che.ls05.protocol.band8.B8AlarmSpec
import com.che.ls05.protocol.band8.B8Alarms

/**
 * Saat > Alarmlar, Band 8: bandın kendi alarm listesi (17/0) ve ekle (17/1), düzenle (17/2), sil (17/4).
 * Kimlikler liste sırasıdır; her değişiklikten sonra liste kendiliğinden yeniden okunur. Akıllı alarm yok.
 * Görünüm burada, protokol Band8Driver ve protocol/band8/B8Settings.kt içinde.
 */
class Band8AlarmsCard(private val act: MainActivity) {

    private object Style {
        const val GAP_DP = 8
        const val TIME_SP = 22f
        val DAY_NAMES = listOf("Pzt", "Sal", "Çar", "Per", "Cum", "Cmt", "Paz")
    }

    private val ui = act.ui
    val view: LinearLayout = ui.vertical()
    private val list: LinearLayout = ui.card(padDp = 16, padVDp = 6)
    private val status: TextView = ui.caption("")
    private val addButton: Button = ui.tonal("Alarm ekle") { edit(null) }

    init {
        ui.add(view, list, topDp = Style.GAP_DP)
        status.setPadding(ui.dp(4), 0, ui.dp(4), 0)
        ui.add(view, status, topDp = Style.GAP_DP)
        val buttons = ui.horizontal()
        ui.add(buttons, addButton, width = Ui.WRAP)
        ui.add(buttons, ui.secondary("Yenile") { refresh(read = true) }, width = Ui.WRAP, leftDp = Style.GAP_DP)
        ui.add(view, buttons, topDp = Style.GAP_DP)
    }

    private fun service(): WatchService? = act.readyService()

    fun refresh(read: Boolean = false) {
        if (read) service()?.bandAlarmRefresh()?.let { act.toast(it) }
        list.removeAllViews()
        val l = WatchHub.service?.bandAlarms()
        if (l == null) {
            status.text = if (WatchHub.ready) "Alarm listesi okunuyor…" else "Band bağlanınca alarmlar okunur."
            addButton.isEnabled = false
            list.visibility = View.GONE
            return
        }
        list.visibility = View.VISIBLE
        addButton.isEnabled = l.max == null || l.alarms.size < l.max!!
        status.text = "Bantta ${l.alarms.size} alarm" + (l.max?.let { ", en çok $it" } ?: "") + "."
        if (l.alarms.isEmpty()) ui.add(list, ui.caption("Bantta alarm yok."), topDp = Style.GAP_DP)
        l.alarms.forEachIndexed { i, a ->
            if (i > 0) ui.add(list, ui.divider(), height = ui.dp(1))
            ui.add(list, row(a))
        }
    }

    private fun row(a: B8Alarm): View {
        val r = ui.row(Fmt.hm(a.hour, a.minute), "${daysLabel(B8Alarms.days(a))} · ${if (a.enabled) "Açık" else "Kapalı"}" +
            if (a.smart == 1) " · akıllı" else "") { edit(a) }
        (r.getChildAt(0) as? LinearLayout)?.getChildAt(0)?.let { (it as? TextView)?.textSize = Style.TIME_SP }
        return r
    }

    private fun daysLabel(days: Int): String = when (days) {
        0 -> "Bir kez"
        0x7F -> "Her gün"
        0x1F -> "Hafta içi"
        0x60 -> "Hafta sonu"
        else -> Style.DAY_NAMES.filterIndexed { i, _ -> days and (1 shl i) != 0 }.joinToString(", ")
    }

    private fun edit(existing: B8Alarm?) {
        var hour = existing?.hour ?: 7
        var minute = existing?.minute ?: 0
        val box = ui.dialogBox()
        val timeBtn: Button = ui.secondary(Fmt.hm(hour, minute)) {}
        timeBtn.setOnClickListener { act.pickTime(hour, minute) { h, m -> hour = h; minute = m; timeBtn.text = Fmt.hm(h, m) } }
        ui.add(box, ui.row("Saat", trailing = timeBtn), topDp = Style.GAP_DP)
        ui.add(box, ui.caption("Günler (hiçbiri: bir kez)"), topDp = Style.GAP_DP)
        val days = existing?.let { B8Alarms.days(it) } ?: 0
        val checks = Style.DAY_NAMES.mapIndexed { i, n -> ui.check(n, days and (1 shl i) != 0) }
        checks.forEach { ui.add(box, it) }
        val enabled = ui.check("Açık", existing?.enabled ?: true)
        ui.add(box, enabled, topDp = Style.GAP_DP)
        val b = AlertDialog.Builder(act)
            .setTitle(if (existing == null) "Band 8 alarmı ekle" else "Band 8 alarmı")
            .setView(ui.scroll(box))
            .setPositiveButton("Kaydet") { _, _ ->
                var mask = 0
                checks.forEachIndexed { i, c -> if (c.isChecked) mask = mask or (1 shl i) }
                val err = service()?.bandAlarmSave(existing?.id, B8AlarmSpec(hour, minute, mask, enabled.isChecked))
                act.toast(err ?: "Alarm banda gönderildi.")
                refresh()
            }
            .setNegativeButton("Vazgeç", null)
        if (existing != null) b.setNeutralButton("Sil") { _, _ ->
            val err = service()?.bandAlarmDelete(existing.id)
            act.toast(err ?: "Alarm siliniyor.")
            refresh()
        }
        val d = b.create()
        act.track(d)
        d.show()
    }
}
