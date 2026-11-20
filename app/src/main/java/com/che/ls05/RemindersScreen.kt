package com.che.ls05

import android.Manifest
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.text.InputType
import android.widget.Button
import android.widget.LinearLayout
import com.che.ls05.protocol.CustomReminder
import com.che.ls05.protocol.CustomReminders
import com.che.ls05.protocol.ReminderRepeat
import java.time.LocalDate

/**
 * Saat > Hatırlatıcılar ve takvim. Özel hatırlatıcılar ve isteğe bağlı telefon takvimi. Zamanı gelince telefon,
 * etkin saate bildirim gönderir (Reminders, CalendarReminders). Başlıklar loga yazılmaz.
 */
class RemindersScreen(private val act: MainActivity) {

    private object Style {
        const val GAP_DP = 8
        const val SECTION_GAP_DP = 20
        const val PERMISSION_REQUEST = 7
        const val CALENDAR_HINT = "Takvim uygulamasının bildirimleri zaten açıksa bu ayara gerek yok. İkisi de açıksa aynı etkinlik 3 dk içinde bir kez gider."
        val DAY_NAMES = listOf("Pzt", "Sal", "Çar", "Per", "Cum", "Cmt", "Paz")
        val REPEAT_LABELS = linkedMapOf(
            ReminderRepeat.ONCE to "Bir kez",
            ReminderRepeat.DAILY to "Her gün",
            ReminderRepeat.WEEKDAYS to "Hafta içi",
            ReminderRepeat.DAYS to "Seçili günler",
            ReminderRepeat.EVERY_N to "Her N günde",
        )
    }

    private val ui = act.ui
    private val box: LinearLayout = ui.dialogBox()

    fun show() {
        render()
        val d = AlertDialog.Builder(act)
            .setTitle("Hatırlatıcılar ve takvim")
            .setView(ui.scroll(box))
            .setPositiveButton("Kapat", null)
            .create()
        act.track(d)
        d.show()
    }

    private fun render() {
        box.removeAllViews()
        ui.add(box, ui.caption("Zamanı gelince telefon etkin saate bildirim gönderir. Saat bağlı değilse bağlanınca 5 dakikaya kadar gönderilir."), topDp = Style.GAP_DP)
        if (!Reminders.canExact(act)) ui.add(box, ui.caption("Tam zamanlı alarm izni yok; hatırlatmalar birkaç dakika gecikebilir."), topDp = 4)

        ui.add(box, ui.section("Hatırlatıcılar"), topDp = Style.GAP_DP)
        val list = ui.card(padDp = 12, padVDp = 4)
        val items = Reminders.custom(act)
        if (items.isEmpty()) ui.add(list, ui.caption("Henüz hatırlatıcı yok."), topDp = Style.GAP_DP)
        items.forEachIndexed { i, r ->
            if (i > 0) ui.add(list, ui.divider(), height = ui.dp(1))
            ui.add(list, ui.row(r.title.ifEmpty { "Hatırlatıcı" }, summary(r)) { edit(r) })
        }
        ui.add(box, list, topDp = Style.GAP_DP)
        ui.add(box, ui.tonal("Hatırlatıcı ekle") { edit(null) }, topDp = Style.GAP_DP, width = Ui.WRAP)

        ui.add(box, ui.section("Telefon takvimi"), topDp = Style.SECTION_GAP_DP)
        val cal = ui.card(padDp = 12, padVDp = 4)
        ui.add(cal, ui.switchRow("Takvim hatırlatmaları", "Etkinliğin kendi hatırlatma süresi, yoksa 10 dk önce. Reddedilenler atlanır.", Prefs.calendarEnabled(act)) { on ->
            if (on && !CalendarReminders.hasPermission(act)) {
                act.requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), Style.PERMISSION_REQUEST)
                act.toast("Takvim iznini verip yeniden aç.")
                render()
                return@switchRow
            }
            Prefs.setCalendarEnabled(act, on)
            CalendarReminders.refresh(act)
            render()
        }.view)
        if (Prefs.calendarEnabled(act) && CalendarReminders.hasPermission(act)) {
            val chosen = Prefs.calendarIds(act)
            for (c in CalendarReminders.calendars(act)) {
                ui.add(cal, ui.divider(), height = ui.dp(1))
                val check = ui.check(c.name.ifEmpty { "Takvim" } + if (c.account.isNotEmpty() && c.account != c.name) " · ${c.account}" else "", c.id in chosen)
                check.setOnCheckedChangeListener { _, on ->
                    val ids = Prefs.calendarIds(act).toMutableSet()
                    if (on) ids += c.id else ids -= c.id
                    Prefs.setCalendarIds(act, ids)
                    CalendarReminders.refresh(act)
                }
                ui.add(cal, check)
            }
        }
        ui.add(box, cal, topDp = Style.GAP_DP)
        ui.add(box, ui.caption(Style.CALENDAR_HINT), topDp = Style.GAP_DP)
    }

    private fun repeatText(r: CustomReminder): String = when (r.repeat) {
        ReminderRepeat.DAYS -> Style.DAY_NAMES.filterIndexed { i, _ -> r.days and (1 shl i) != 0 }.joinToString(", ")
        ReminderRepeat.EVERY_N -> "${r.everyDays} günde bir"
        else -> Style.REPEAT_LABELS[r.repeat] ?: ""
    }

    private fun summary(r: CustomReminder): String =
        "${Fmt.hm(r.hour, r.minute)} · ${repeatText(r)} · ${if (r.repeat == ReminderRepeat.ONCE) r.startDate else "${r.startDate} itibarıyla"}" +
            if (r.enabled) "" else " · kapalı"

    private fun edit(existing: CustomReminder?) {
        val all = Reminders.custom(act)
        if (existing == null && all.size >= Reminders.MAX_CUSTOM) { act.toast("En çok ${Reminders.MAX_CUSTOM} hatırlatıcı."); return }
        val now = java.time.LocalDateTime.now().plusMinutes(5)
        var date = existing?.startDate ?: now.toLocalDate()
        var hour = existing?.hour ?: now.hour
        var minute = existing?.minute ?: now.minute
        var repeat = existing?.repeat ?: ReminderRepeat.ONCE

        val dlg = ui.dialogBox()
        val title = ui.field("Başlık").also { it.setText(existing?.title ?: "") }
        ui.add(dlg, title, topDp = Style.GAP_DP)
        val dateBtn: Button = ui.secondary(date.toString()) {}
        dateBtn.setOnClickListener {
            val d = DatePickerDialog(act, { _, y, m, day -> date = LocalDate.of(y, m + 1, day); dateBtn.text = date.toString() }, date.year, date.monthValue - 1, date.dayOfMonth)
            act.track(d)
            d.show()
        }
        ui.add(dlg, ui.row("Tarih (ilk gün)", trailing = dateBtn), topDp = Style.GAP_DP)
        val timeBtn: Button = ui.secondary(Fmt.hm(hour, minute)) {}
        timeBtn.setOnClickListener { act.pickTime(hour, minute) { h, m -> hour = h; minute = m; timeBtn.text = Fmt.hm(h, m) } }
        ui.add(dlg, ui.row("Saat", trailing = timeBtn))
        val repeatBtn: Button = ui.secondary(Style.REPEAT_LABELS[repeat] ?: "") {}
        val dayChecks = Style.DAY_NAMES.mapIndexed { i, n -> ui.check(n, (existing?.days ?: 0) and (1 shl i) != 0) }
        val daysBox = ui.vertical().also { v -> dayChecks.forEach { ui.add(v, it) } }
        val every = ui.field("Kaç günde bir").also {
            it.inputType = InputType.TYPE_CLASS_NUMBER
            it.setText((existing?.everyDays ?: 2).toString())
        }
        fun showRepeatFields() {
            daysBox.visibility = if (repeat == ReminderRepeat.DAYS) android.view.View.VISIBLE else android.view.View.GONE
            every.visibility = if (repeat == ReminderRepeat.EVERY_N) android.view.View.VISIBLE else android.view.View.GONE
        }
        repeatBtn.setOnClickListener {
            val keys = Style.REPEAT_LABELS.keys.toList()
            val d = AlertDialog.Builder(act).setTitle("Tekrar")
                .setItems(Style.REPEAT_LABELS.values.toTypedArray()) { _, which ->
                    repeat = keys[which]; repeatBtn.text = Style.REPEAT_LABELS[repeat]; showRepeatFields()
                }.create()
            act.track(d); d.show()
        }
        ui.add(dlg, ui.row("Tekrar", trailing = repeatBtn))
        ui.add(dlg, daysBox, topDp = 4)
        ui.add(dlg, every, topDp = 4)
        showRepeatFields()
        val enabled = ui.check("Açık", existing?.enabled ?: true)
        ui.add(dlg, enabled, topDp = Style.GAP_DP)

        val b = AlertDialog.Builder(act)
            .setTitle(if (existing == null) "Yeni hatırlatıcı" else "Hatırlatıcı")
            .setView(ui.scroll(dlg))
            .setPositiveButton("Kaydet") { _, _ ->
                var days = 0
                dayChecks.forEachIndexed { i, c -> if (c.isChecked) days = days or (1 shl i) }
                if (repeat == ReminderRepeat.DAYS && days == 0) { act.toast("En az bir gün seç."); return@setPositiveButton }
                val id = existing?.id ?: ((all.maxOfOrNull { it.id } ?: 0) + 1).coerceAtMost(Reminders.MAX_CUSTOM)
                val r = CustomReminder(
                    id, CustomReminders.cleanTitle(title.text.toString()).ifEmpty { "Hatırlatıcı" }, date, hour, minute, repeat, days,
                    every.text.toString().toIntOrNull()?.coerceIn(1, 365) ?: 2, enabled.isChecked,
                )
                Reminders.saveCustom(act, if (existing == null) all + r else all.map { if (it.id == r.id) r else it })
                render()
            }
            .setNegativeButton("Vazgeç", null)
        if (existing != null) b.setNeutralButton("Sil") { _, _ ->
            Reminders.saveCustom(act, all.filter { it.id != existing.id })
            render()
        }
        val d = b.create()
        act.track(d)
        d.show()
    }
}
