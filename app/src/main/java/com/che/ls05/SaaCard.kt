package com.che.ls05

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Ayarlar > Sleep as Android kartı (açık/kapalı, nabız gönder, kurulum adımları, durum). */
class SaaCard(private val act: MainActivity, private val ui: Ui) {
    private val enabled: SwitchRow = ui.switchRow(
        "Sleep as Android ile çalış",
        "Takip, akıllı alarm ve ipucu titreşimi Band 8'e gelir.",
        Prefs.saaEnabled(act),
        iconRes = R.drawable.ic_moon,
    ) { on ->
        Prefs.setSaaEnabled(act, on)
        SleepAsAndroidBridge.settingsChanged(act)
        refresh()
    }
    private val heart: SwitchRow = ui.switchRow(
        "Nabız gönder",
        "Takip sürerken bandın canlı nabzı gider. Bandın pili daha hızlı biter.",
        Prefs.saaHeartRate(act),
        iconRes = R.drawable.ic_heart,
    ) { on ->
        Prefs.setSaaHeartRate(act, on)
        SleepAsAndroidBridge.settingsChanged(act)
        refresh()
    }
    private val move: SwitchRow = ui.switchRow(
        "Hareket gönder (deneysel)",
        "Bantta gizli bir antrenman açılır, ivme verisi gider. Bantta antrenman ekranı görünür; durdurma. Pil daha hızlı biter.",
        Prefs.saaMovement(act),
        iconRes = R.drawable.ic_vibrate,
    ) { on ->
        Prefs.setSaaMovement(act, on)
        SleepAsAndroidBridge.settingsChanged(act)
        refresh()
    }
    private val status: TextView = ui.caption("")

    val section: TextView = ui.section("Sleep as Android")
    val card: LinearLayout = ui.card(padDp = 16, padVDp = 6)

    init {
        ui.add(card, enabled.view)
        ui.add(card, ui.divider(), height = ui.dp(1))
        ui.add(card, heart.view)
        ui.add(card, ui.divider(), height = ui.dp(1))
        ui.add(card, move.view)
        ui.add(card, ui.divider(), height = ui.dp(1))
        ui.add(card, ui.caption(setupText()), topDp = 8)
        ui.add(card, status, topDp = 8)
        refresh()
    }

    private fun setupText(): String = listOf(
        "Kurulum:",
        "1. Sleep as Android > Ayarlar > Wearables bölümünde \"DIY\" seçeneğini seç.",
        "2. Paket adı alanına ${act.packageName} yaz.",
        "3. Nabız için aynı bölümde nabız ölçümünü aç.",
        "4. \"Sensörleri test et\" ile dene. Band hazırsa bağlantı onaylanır.",
        "Sleep as Android uyku grafiği için hareket verisi ister. Hareket kapalıyken yalnızca nabız gider ve sensör testi boş kalabilir. Alarmda bantta \"Alarm\" ekranı çıkar: reddet alarmı kapatır, sessize al erteler.",
        "LS05 için bu özellik yok; Sleep as Android saatsiz çalışır.",
    ).joinToString("\n")

    fun refresh() {
        enabled.toggle.set(Prefs.saaEnabled(act))
        heart.toggle.set(Prefs.saaHeartRate(act))
        ui.enable(heart.view, Prefs.saaEnabled(act))
        heart.toggle.sw.isEnabled = Prefs.saaEnabled(act)
        move.toggle.set(Prefs.saaMovement(act))
        ui.enable(move.view, Prefs.saaEnabled(act))
        move.toggle.sw.isEnabled = Prefs.saaEnabled(act)
        status.text = "Durum: " + SleepAsAndroidBridge.statusText(act)
    }

    fun setVisible(on: Boolean) {
        val v = if (on) View.VISIBLE else View.GONE
        section.visibility = v
        card.visibility = v
    }
}
