package com.che.ls05

import android.app.AlertDialog
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.che.ls05.protocol.NotificationState
import com.che.ls05.protocol.UteFrame
import com.che.ls05.protocol.WatchType

/**
 * Saat > Bildirimler: iletim, aramalar, kurallar, özel kaynaklar, uygulama listesi ve test bildirimi.
 * Band 8 uygulama simgesi ve navigasyon metni de burada.
 */
class NotifySection(private val act: MainActivity) {

    private val ui: Ui = act.ui

    /** Saat > Bildirimler bölümünün görünümü. */
    val view: LinearLayout = ui.vertical()
    private val body: LinearLayout get() = view

    private companion object {
        const val RECENT_COUNT = 3
    }

    /** Uygulama adı ve ikonu bir kez okunur. system: güncellenmemiş sistem uygulaması ya da bilgisi okunamayan paket. */
    private class AppInfo(val label: String, val icon: Drawable?, val system: Boolean)

    private class AppRow(val pkg: String, val info: AppInfo, val last: Long)

    private val accessBanner: Banner = ui.banner(
        "Bildirim erişimi kapalı. Bildirimler saate iletilemez.",
        "Aç",
    ) { act.openSettings(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }

    private val forward: SwitchRow = ui.switchRow(
        "Bildirimleri saate ilet", null, Prefs.forwardingEnabled(act), iconRes = R.drawable.ic_bell,
    ) { Prefs.setForwardingEnabled(act, it) }
    private val incomingCalls: SwitchRow = ui.switchRow(
        "Gelen aramalar",
        "Arayan saatte görünür, saat titrer; cevaplayınca ya da bitince susar.",
        Prefs.incomingCalls(act),
        iconRes = R.drawable.ic_phone,
    ) { Prefs.setIncomingCalls(act, it) }
    private val missedCalls: SwitchRow = ui.switchRow(
        "Cevapsız aramalar", null, Prefs.missedCalls(act), iconRes = R.drawable.ic_phone_missed,
    ) { Prefs.setMissedCalls(act, it) }
    private val vibrate: SwitchRow = ui.switchRow(
        "Bildirimden sonra titret", null, Prefs.vibrate(act), iconRes = R.drawable.ic_vibrate,
    ) { Prefs.setVibrate(act, it) }
    private val foldTurkish: SwitchRow = ui.switchRow(
        "Türkçe harfleri sadeleştir",
        "Saatte boşluklu görünen ı yerine i yazılır (İ yerine I).",
        Prefs.foldTurkish(act),
    ) { Prefs.setFoldTurkish(act, it) }
    private val rejectCalls: SwitchRow = ui.switchRow(
        "Saatten aramayı reddet",
        "Saatteki kırmızı düğme aramayı kapatır.",
        Prefs.rejectFromWatch(act),
        iconRes = R.drawable.ic_phone_reject,
    ) { on ->
        Prefs.setRejectFromWatch(act, on)
        if (on && android.os.Build.VERSION.SDK_INT >= 28 &&
            act.checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            act.requestPermissions(arrayOf(android.Manifest.permission.ANSWER_PHONE_CALLS), 3)
        }
    }

    private val navigationRow: SwitchRow = ui.switchRow(
        "Navigasyon yönergeleri",
        "Google Maps yönergesi değişince saate gider (OsmAnd, Komoot denenmedi).",
        Prefs.navigation(act),
    ) { Prefs.setNavigation(act, it) }
    private val batteryLowRow: SwitchRow = ui.switchRow(
        "Telefon pili azaldı", "Eşik: %${Prefs.batteryLowThreshold(act)}. Değiştirmek için uzun bas.", Prefs.batteryLow(act),
    ) { Prefs.setBatteryLow(act, it) }
    // Band 8: uygulama simgesi (bant isteyince) ve navigasyonda tam metin.
    private val band8Icons: SwitchRow = ui.switchRow(
        "Uygulama simgesini gönder",
        "Band bir uygulamanın simgesini isteyince o uygulamanın simgesi gönderilir. Kapalıyken band kendi simgesini gösterir.",
        Prefs.band8Icons(act),
    ) { on -> Prefs.setBand8Icons(act, on) }
    private val band8NavText: SwitchRow = ui.switchRow(
        "Navigasyonda tam metin bildirimi",
        "Navigasyon kadranı açıkken de yönerge bildirimi gider. Kapalıyken her manevrada yalnızca kısa manevra adı gider.",
        Prefs.band8NavText(act),
    ) { on -> Prefs.setBand8NavText(act, on) }
    private val batteryFullRow: SwitchRow = ui.switchRow(
        "Telefon pili doldu", "Şarjdayken %100 olunca bir kez.", Prefs.batteryFull(act),
    ) { Prefs.setBatteryFull(act, it) }

    // Küçük ikincil düğme: sayfanın asıl işi iletim ayarları, test yardımcı.
    private val ls05NotificationOptions = ui.vertical()
    private val testButton: Button = ui.secondary("Test bildirimi gönder") {
        if (WatchRegistry.active(act)?.type == WatchType.BAND8) sendTest("Band 8 denemesi", "Öğle ışığı, çığ ve güller")
        else sendTest("LS05TEST123", "LS05TEST123")
    }.also {
        it.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    }
    private val testState: TextView = ui.text("", 14f, R.color.text)
    private val confirmRow: LinearLayout = ui.horizontal()

    private val search = ui.field("Uygulama ara")
    private val appBox: LinearLayout = ui.card(padDp = 16, padVDp = 6)
    private val infos = HashMap<String, AppInfo>()
    private var restOpen = false

    private var notifState = NotificationState.IDLE

    init {
        ui.add(body, accessBanner.view)

        val main = ui.card(padDp = 16, padVDp = 6)
        ui.add(main, forward.view)
        for (row in listOf(incomingCalls, missedCalls, vibrate, foldTurkish, rejectCalls)) {
            ui.add(ls05NotificationOptions, ui.divider(), height = ui.dp(1))
            ui.add(ls05NotificationOptions, row.view)
        }
        ui.add(main, ls05NotificationOptions)
        ui.add(main, band8Icons.view)
        ui.add(body, main, topDp = 10)

        // Kurallar ve özel kaynaklar: iki saat için ortak.
        val extra = ui.card(padDp = 16, padVDp = 6)
        ui.add(extra, ui.row("Bildirim kuralları", "Uygulama, kişi, kelime, sessiz saatler, tekrar") { NotifyRulesScreen(act).show() })
        for (row in listOf(navigationRow, band8NavText, batteryLowRow, batteryFullRow)) {
            ui.add(extra, ui.divider(), height = ui.dp(1))
            ui.add(extra, row.view)
        }
        batteryLowRow.view.setOnLongClickListener { editBatteryThreshold(); true }
        ui.add(body, extra, topDp = 10)

        // "Uygulamalar" + arama simgesi. Arama kutusu simgeye dokununca açılır.
        val head = ui.horizontal()
        ui.add(head, ui.section("Uygulamalar"), width = 0, weight = 1f)
        head.addView(
            ui.iconButton(R.drawable.ic_search, "Uygulama ara") { toggleSearch() },
            LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)),
        )
        ui.add(body, head, topDp = 10)
        search.visibility = View.GONE
        ui.add(body, search, topDp = 4)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                refreshApps()
            }
        })
        ui.add(body, appBox, topDp = 8)

        // Test: en altta. Sonuç kullanıcıya sorulur.
        ui.add(body, testButton, topDp = 16, width = Ui.WRAP)
        ui.add(
            body,
            ui.caption("Saate kısa bir deneme bildirimi gider. Sonra saatte görüp görmediğini işaretle."),
            topDp = 8,
        )
        testState.visibility = View.GONE
        ui.add(body, testState, topDp = 12)
        ui.add(confirmRow, ui.secondary("Saatte gördüm") { setNotifState(NotificationState.DISPLAYED_CONFIRMED) },
            width = 0, weight = 1f)
        ui.add(confirmRow, ui.secondary("Görmedim") { setNotifState(NotificationState.NOT_DISPLAYED) },
            width = 0, weight = 1f, leftDp = 8)
        confirmRow.visibility = View.GONE
        ui.add(body, confirmRow, topDp = 8)
    }

    fun refresh() {
        accessBanner.view.visibility = if (act.hasNotificationAccess()) View.GONE else View.VISIBLE
        forward.toggle.set(Prefs.forwardingEnabled(act))
        vibrate.toggle.set(Prefs.vibrate(act))
        incomingCalls.toggle.set(Prefs.incomingCalls(act))
        missedCalls.toggle.set(Prefs.missedCalls(act))
        rejectCalls.toggle.set(Prefs.rejectFromWatch(act))
        val caps = WatchManager.caps(act)
        ls05NotificationOptions.visibility = if (WatchRegistry.active(act)?.type == WatchType.BAND8) View.GONE else View.VISIBLE
        rejectCalls.view.visibility = if (caps.callReject) View.VISIBLE else View.GONE
        foldTurkish.toggle.set(Prefs.foldTurkish(act))
        val band = WatchRegistry.active(act)?.type == WatchType.BAND8
        band8Icons.toggle.set(Prefs.band8Icons(act))
        band8NavText.toggle.set(Prefs.band8NavText(act))
        band8Icons.view.visibility = if (band) View.VISIBLE else View.GONE
        band8NavText.view.visibility = band8Icons.view.visibility
        // Ayırıcı çizgi satırla birlikte gizlensin.
        (band8NavText.view.parent as? LinearLayout)?.let { p ->
            val i = p.indexOfChild(band8NavText.view)
            if (i > 0) p.getChildAt(i - 1).visibility = band8Icons.view.visibility
        }
        onReady(WatchHub.ready)
        refreshApps()
    }

    fun onReady(ready: Boolean) {
        ui.enable(testButton, ready)
    }

    private fun toggleSearch() {
        val imm = act.getSystemService(InputMethodManager::class.java)
        if (search.visibility == View.VISIBLE) {
            imm?.hideSoftInputFromWindow(search.windowToken, 0)
            search.setText("")
            search.visibility = View.GONE
        } else {
            search.visibility = View.VISIBLE
            search.requestFocus()
            imm?.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun info(pkg: String): AppInfo = infos.getOrPut(pkg) {
        val pm = act.packageManager
        try {
            val ai = pm.getApplicationInfo(pkg, 0)
            val icon: Drawable? = try {
                pm.getApplicationIcon(ai)
            } catch (e: Exception) {
                null
            }
            val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            AppInfo(pm.getApplicationLabel(ai).toString(), icon, system)
        } catch (e: Exception) {
            AppInfo(pkg, null, true)
        }
    }

    private fun addAppRows(box: LinearLayout, rows: List<AppRow>) {
        for (row in rows) {
            val sub = if (row.last > 0L) Fmt.ago(row.last) else null
            val pkg = row.pkg
            val r = ui.switchRow(
                row.info.label, sub, Prefs.appEnabled(act, pkg), appIcon = true, lead = row.info.icon,
            ) { Prefs.setAppEnabled(act, pkg, it) }
            ui.add(box, r.view)
        }
    }

    /**
     * Yalnızca uygulama kartını yeniden kurar; arama kutusunun odağı ve kaydırma konumu korunur.
     * Üstte iletimi açık ve son bildirim gönderen uygulamalar; gerisi "Tüm uygulamalar" altında katlanır.
     * Arama yapılırken tüm uygulamalar düz liste olarak aranır.
     */
    private fun refreshApps() {
        appBox.removeAllViews()
        val q = search.text.toString().trim().lowercase()
        val apps = Prefs.seenApps(act).map { pkg -> AppRow(pkg, info(pkg), Prefs.lastNotified(act, pkg)) }
        if (apps.isEmpty()) {
            ui.add(
                appBox,
                ui.caption("Henüz bildirim görülmedi. Bildirim erişimini açtıktan sonra gelen bildirimlerle dolar."),
                topDp = 10,
            )
            ui.add(appBox, ui.space(), height = ui.dp(10))
            return
        }
        val shown = apps
            .filter { q.isEmpty() || it.info.label.lowercase().contains(q) || it.pkg.lowercase().contains(q) }
            .sortedWith(compareByDescending<AppRow> { it.last }.thenBy { it.info.label.lowercase() })
        if (shown.isEmpty()) {
            ui.add(appBox, ui.caption("Aramayla eşleşen uygulama yok."), topDp = 10)
            ui.add(appBox, ui.space(), height = ui.dp(10))
            return
        }
        if (q.isNotEmpty()) {
            // Arama: tüm uygulamalar düz liste.
            addAppRows(appBox, shown)
            return
        }
        // Varsayılan: iletimi açık uygulamalar ve son bildirim gönderen 3 uygulama. Gerisi katlanır.
        val recent = shown.filter { it.last > 0L }.take(RECENT_COUNT).map { it.pkg }.toSet()
        val top = shown.filter { Prefs.appEnabled(act, it.pkg) || it.pkg in recent }
        val rest = shown.filter { it !in top }
        addAppRows(appBox, top)
        if (rest.isNotEmpty()) {
            if (top.isNotEmpty()) ui.add(appBox, ui.divider(), height = ui.dp(1))
            val group = ui.collapsible(
                "Tüm uygulamalar (${rest.size})", null, null, open = restOpen, inCard = false,
            )
            group.onToggle = { open -> restOpen = open }
            addAppRows(group.content, rest.filter { !it.info.system })
            val system = rest.filter { it.info.system }
            if (system.isNotEmpty()) {
                ui.add(group.content, ui.caption("Sistem uygulamaları"), topDp = 12)
                addAppRows(group.content, system)
            }
            ui.add(appBox, group.view)
        }
    }

    // ---------- Test bildirimi ----------

    /** Gönderildiyse true. Sonuç bu sekmede işaretlenir. */
    private fun editBatteryThreshold() {
        val box = ui.dialogBox()
        val f = ui.field("Yüzde (5-50)")
        f.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        f.setText(Prefs.batteryLowThreshold(act).toString())
        ui.add(box, f, topDp = 8)
        val d = AlertDialog.Builder(act).setTitle("Düşük pil eşiği").setView(box)
            .setPositiveButton("Kaydet") { _, _ ->
                f.text.toString().toIntOrNull()?.let { Prefs.setBatteryLowThreshold(act, it) }
                batteryLowRow.subtitle.text = "Eşik: %${Prefs.batteryLowThreshold(act)}. Değiştirmek için uzun bas."
            }
            .setNegativeButton("Vazgeç", null).create()
        act.track(d)
        d.show()
    }

    fun sendTest(title: String, message: String): Boolean {
        val s = WatchHub.service
        if (s == null || !s.isWatchReady()) {
            WatchHub.log("Saat hazır değil; önce bağlan.")
            act.toast("Saat bağlı değil.")
            return false
        }
        if (!s.sendTestNotification(title, message)) return false
        if (WatchRegistry.active(act)?.type == WatchType.BAND8) {
            setNotifState(NotificationState.REQUESTED)
            return true
        }
        setNotifState(NotificationState.SENDING)
        act.handler.postDelayed({
            if (notifState == NotificationState.SENDING) setNotifState(NotificationState.TRANSFER_TIMEOUT)
        }, 6_000L)
        return true
    }

    private fun setNotifState(st: NotificationState) {
        notifState = st
        val (logText, shownText) = when (st) {
            NotificationState.IDLE -> return
            NotificationState.SENDING ->
                "Test bildirimi gönderiliyor…" to "Gönderiliyor…"
            NotificationState.REQUESTED ->
                "Test bildirimi sıraya alındı. Saatte gördün mü?" to "Gönderim istendi. Saatte gördün mü?"
            NotificationState.TRANSFER_DONE ->
                "Aktarım tamam (saat onayladı). Ekranda gördün mü?" to "Aktarım tamam. Saatte gördün mü?"
            NotificationState.TRANSFER_TIMEOUT ->
                "Aktarım onayı 6 sn içinde gelmedi. Ekranda gördün mü?" to
                    "Aktarım onayı 6 sn içinde gelmedi. Saatte gördün mü?"
            NotificationState.DISPLAYED_CONFIRMED ->
                "SONUÇ: Test bildirimi saatte göründü (kullanıcı onayı)." to "Test bildirimi saatte göründü."
            NotificationState.NOT_DISPLAYED ->
                "SONUÇ: Test bildirimi saatte görünmedi (kullanıcı onayı)." to "Test bildirimi saatte görünmedi."
        }
        WatchHub.log(logText)
        testState.text = shownText
        testState.visibility = View.VISIBLE
        confirmRow.visibility =
            if (st in setOf(NotificationState.REQUESTED, NotificationState.TRANSFER_DONE, NotificationState.TRANSFER_TIMEOUT)) {
                View.VISIBLE
            } else View.GONE
    }

    fun onFrame(frame: UteFrame) {
        if (frame is UteFrame.NotificationTransferDone && notifState == NotificationState.SENDING) {
            setNotifState(NotificationState.TRANSFER_DONE)
        }
    }
}
