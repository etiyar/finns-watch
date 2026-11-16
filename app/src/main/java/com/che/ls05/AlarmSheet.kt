package com.che.ls05

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import com.che.ls05.protocol.AlarmReminder
import com.che.ls05.protocol.AlarmSetting
import com.che.ls05.protocol.UteAlarm

/**
 * Alarm düzenleme paneli (v0.11): alttan açılan, üst köşeleri yuvarlak pencere.
 * Saat ve dakika çarkı, gün yuvarlakları, titreşim süresi, not, açık/kapalı; Kaydet, Sil, Vazgeç.
 * Kayıt her zamanki ayar yolundan gider (SettingsStore): istenen değer, gönderim, onay.
 */
class AlarmSheet(private val act: MainActivity, private val slot: Int, private val isNew: Boolean) {

    private val ui = act.ui

    private companion object {
        /** Gün yuvarlakları, pazartesiden pazara (UteAlarm.UI_ORDER sırası). */
        val SHORT = arrayOf("P", "S", "Ç", "P", "C", "C", "P")
        val FULL = arrayOf("Pazartesi", "Salı", "Çarşamba", "Perşembe", "Cuma", "Cumartesi", "Pazar")
        val VIBES = intArrayOf(UteAlarm.VIBES_SHORT, UteAlarm.VIBES_NORMAL, UteAlarm.VIBES_LONG)
    }

    private val key = AlarmSetting.key(slot)
    private val stored: AlarmSetting? = AlarmSetting.decode(SettingsStore.entry(act, key).desired)
    private val start: AlarmSetting = if (isNew) AlarmSetting.NEW else stored ?: AlarmSetting.NEW

    private var mask = start.mask
    private var vibes = start.vibes
    private val chips = ArrayList<TextView>()

    fun show() {
        val d = Dialog(act, R.style.AppDialog)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val sheet = ui.vertical()
        val bg = GradientDrawable()
        bg.setColor(ui.color(R.color.card))
        val r = ui.dp(24).toFloat()
        bg.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        sheet.background = bg
        sheet.setPadding(ui.dp(20), ui.dp(10), ui.dp(20), ui.dp(12))

        // Tutamak
        val handle = View(act)
        handle.background = ui.round(ui.color(R.color.border), 2)
        val hlp = LinearLayout.LayoutParams(ui.dp(36), ui.dp(4))
        hlp.gravity = Gravity.CENTER_HORIZONTAL
        sheet.addView(handle, hlp)

        // Başlık ve açık/kapalı anahtarı
        val head = ui.horizontal()
        head.minimumHeight = ui.dp(48)
        ui.add(head, ui.text(if (isNew) "Yeni alarm" else "Alarm $slot", 20f, R.color.text, bold = true), width = 0, weight = 1f)
        val onToggle = ui.toggle(start.enabled) {}
        onToggle.sw.contentDescription = "Alarm açık"
        ui.add(head, onToggle.sw, width = Ui.WRAP, leftDp = 12)
        ui.add(sheet, head, topDp = 6)

        val form = ui.vertical()

        // Saat ve dakika çarkı
        val time = ui.horizontal()
        time.setGravity(Gravity.CENTER)
        val hour = picker(d, 23, start.hour, "Saat")
        val minute = picker(d, 59, start.minute, "Dakika")
        time.addView(hour, LinearLayout.LayoutParams(ui.dp(88), Ui.WRAP))
        val colon = ui.text(":", 34f, R.color.text, bold = true)
        colon.setPadding(ui.dp(8), 0, ui.dp(8), ui.dp(4))
        time.addView(colon, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP))
        time.addView(minute, LinearLayout.LayoutParams(ui.dp(88), Ui.WRAP))
        ui.add(form, time, topDp = 2)

        // Günler
        ui.add(form, ui.cardTitle("Günler"), topDp = 14)
        val days = LinearLayout(act)
        days.orientation = LinearLayout.HORIZONTAL
        for (i in UteAlarm.UI_ORDER.indices) {
            val bit = UteAlarm.UI_ORDER[i]
            val cell = FrameLayout(act)
            cell.minimumHeight = ui.dp(48)
            val chip = ui.text(SHORT[i], 15f, R.color.text, bold = true)
            chip.gravity = Gravity.CENTER
            chip.contentDescription = FULL[i]
            chip.isClickable = true
            chip.isFocusable = true
            chip.setOnClickListener {
                mask = mask xor bit
                paintChips()
            }
            chips += chip
            cell.addView(chip, FrameLayout.LayoutParams(ui.dp(40), ui.dp(40), Gravity.CENTER))
            ui.add(days, cell, width = 0, height = ui.dp(48), weight = 1f)
        }
        ui.add(form, days, topDp = 4)
        val shortcuts = ui.horizontal()
        ui.add(shortcuts, shortcut("Hafta içi") { mask = UteAlarm.WEEKDAYS; paintChips() }, width = Ui.WRAP)
        ui.add(shortcuts, shortcut("Her gün") { mask = UteAlarm.EVERY_DAY; paintChips() }, width = Ui.WRAP, leftDp = 8)
        ui.add(form, shortcuts, topDp = 4)
        paintChips()

        // Titreşim süresi
        val vibHead = ui.horizontal()
        ui.icon(R.drawable.ic_vibrate, R.color.text2)?.let {
            val lp = LinearLayout.LayoutParams(ui.dp(18), ui.dp(18))
            lp.rightMargin = ui.dp(6)
            vibHead.addView(it, lp)
        }
        ui.add(vibHead, ui.cardTitle("Titreşim"), width = 0, weight = 1f)
        val vibLen = ui.caption("")
        ui.add(vibHead, vibLen, width = Ui.WRAP)
        ui.add(form, vibHead, topDp = 16)
        fun showLen() {
            vibLen.text = "yaklaşık ${UteAlarm.vibesLabel(vibes)}"
        }
        val sel = when {
            vibes <= UteAlarm.VIBES_SHORT -> 0
            vibes <= UteAlarm.VIBES_NORMAL -> 1
            else -> 2
        }
        val seg = ui.segmented(listOf("Kısa", "Normal", "Uzun"), sel) { i ->
            vibes = VIBES[i]
            showLen()
        }
        showLen()
        ui.add(form, seg.view, topDp = 8)

        // Hatırlatma notu (kurallar AlarmReminder'da, değişmedi)
        ui.add(form, ui.cardTitle("Hatırlatma notu"), topDp = 16)
        val noteField = ui.field("İsteğe bağlı, ör. Sabah ilacı")
        noteField.filters = arrayOf(android.text.InputFilter.LengthFilter(AlarmReminder.MAX_NOTE))
        noteField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        noteField.setText(if (isNew) "" else Prefs.alarmNote(act, slot))
        ui.add(form, noteField, topDp = 8)
        val noteHelp = if (Reminders.canExact(act)) {
            "Alarm çalınca not saate bildirim olarak gider. Yalnızca telefonda saklanır. Saat o an bağlı değilse 5 dakika beklenir."
        } else {
            "Tam zamanlı alarm izni yok: not gecikebilir veya gönderilemeyebilir. Yalnızca telefonda saklanır."
        }
        ui.add(form, ui.caption(noteHelp), topDp = 6)

        // Form kaydırılır, düğmeler hep görünür. (WRAP + ağırlık: uzun formda kaydırma alanı küçülür.)
        val scroll = ui.scroll(form)
        ui.add(sheet, scroll, height = Ui.WRAP, weight = 1f, topDp = 4)

        val buttons = ui.horizontal()
        if (!isNew) {
            ui.add(buttons, ui.danger("Sil") {
                delete()
                d.dismiss()
            }, width = Ui.WRAP)
        }
        ui.add(buttons, ui.space(), width = 0, height = ui.dp(1), weight = 1f)
        ui.add(buttons, ui.textButton("Vazgeç") { d.dismiss() }, width = Ui.WRAP)
        ui.add(buttons, ui.tonal("Kaydet") {
            hour.clearFocus()
            minute.clearFocus()
            if (save(onToggle.sw.isChecked, hour.value, minute.value, noteField.text.toString())) d.dismiss()
        }, width = Ui.WRAP, leftDp = 8)
        ui.add(sheet, buttons, topDp = 12)

        // Gezinme çubuğunun arkasına düşerse alt boşluk eklenir.
        val basePad = sheet.paddingBottom
        sheet.setOnApplyWindowInsetsListener { v, insets ->
            val bottom = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, basePad + bottom)
            insets
        }

        d.setContentView(sheet)
        d.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setGravity(Gravity.BOTTOM)
            w.setWindowAnimations(android.R.style.Animation_InputMethod)
            @Suppress("DEPRECATION")
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        act.track(d)
        d.show()
    }

    /** Büyük, kaydırılan sayı çarkı (00…max). Klavye açılmaz. */
    private fun picker(d: Dialog, max: Int, value: Int, description: String): NumberPicker {
        val p = NumberPicker(d.context)
        p.minValue = 0
        p.maxValue = max
        // Biçimlendirici ilk çizimde uygulanmıyor; iki haneli metin doğrudan verilir.
        p.displayedValues = Array(max + 1) { String.format(java.util.Locale.US, "%02d", it) }
        p.value = value
        p.wrapSelectorWheel = true
        p.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        p.contentDescription = description
        if (Build.VERSION.SDK_INT >= 29) {
            p.textColor = ui.color(R.color.text)
            p.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 30f, act.resources.displayMetrics)
            p.selectionDividerHeight = ui.dp(1)
        }
        return p
    }

    private fun shortcut(label: String, onClick: () -> Unit): TextView {
        val t = ui.text(label, 13f, R.color.text2, bold = true)
        t.gravity = Gravity.CENTER
        t.minHeight = ui.dp(36)
        t.setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(6))
        t.background = ui.ripple(ui.round(ui.color(R.color.raised), 18), null)
        t.isClickable = true
        t.isFocusable = true
        t.setOnClickListener { onClick() }
        return t
    }

    /** Seçili gün nane, seçili olmayan yükseltilmiş yüzey. Durum TalkBack'e de söylenir. */
    private fun paintChips() {
        chips.forEachIndexed { i, chip ->
            val on = (mask and UteAlarm.UI_ORDER[i]) != 0
            chip.isSelected = on
            chip.setTextColor(ui.color(if (on) R.color.on_accent else R.color.text))
            chip.background = ui.ripple(ui.round(ui.color(if (on) R.color.accent else R.color.raised), 20), null)
            if (Build.VERSION.SDK_INT >= 30) {
                chip.stateDescription = if (on) "Seçili" else "Seçili değil"
            } else {
                chip.contentDescription = "${FULL[i]}, ${if (on) "seçili" else "seçili değil"}"
            }
        }
    }

    /** Kaydeder; geçersizse uyarır ve false döner. */
    private fun save(on: Boolean, hour: Int, minute: Int, note: String): Boolean {
        if (on && mask == 0) {
            act.toast("Açık alarm için en az bir gün seç.")
            return false
        }
        // Kapalı alarmda gün seçilmediyse önceki günler korunur (boş maske geçersiz).
        val finalMask = if (mask == 0) start.mask else mask
        // Not önce kaydedilir ki set() içindeki yeniden zamanlama yeni notu görsün.
        Prefs.setAlarmNote(act, slot, AlarmReminder.cleanNote(note))
        Prefs.setAlarmVisible(act, slot, true)
        val value = AlarmSetting(on, hour, minute, finalMask, vibes).encode()
        if (value == SettingsStore.entry(act, key).desired) {
            // Yalnızca not değişti: saate komut gitmez.
            Reminders.reschedule(act)
            WatchHub.settingsChanged()
        } else {
            SettingsStore.set(act, key, value)
        }
        return true
    }

    /** Sil: alarm kapatılır (normal ayar yolu) ve yuva gizlenir. Not da silinir. */
    private fun delete() {
        Prefs.setAlarmNote(act, slot, "")
        Prefs.setAlarmVisible(act, slot, false)
        val cur = stored
        val value = cur?.copy(enabled = false)?.encode()
        if (value == null || value == SettingsStore.entry(act, key).desired) {
            Reminders.reschedule(act)
            WatchHub.settingsChanged()
        } else {
            SettingsStore.set(act, key, value)
        }
        act.toast("Alarm silindi.")
    }
}
