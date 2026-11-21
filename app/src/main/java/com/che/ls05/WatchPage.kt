package com.che.ls05

import android.app.AlertDialog
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.che.ls05.protocol.AlarmSetting
import com.che.ls05.protocol.DndSetting
import com.che.ls05.protocol.ProfileSetting
import com.che.ls05.protocol.SyncEntry
import com.che.ls05.protocol.SyncPhase
import com.che.ls05.protocol.UteAlarm
import com.che.ls05.protocol.UteProfile
import com.che.ls05.protocol.WatchType

// =====================================================================
// 3. Saat: Kadranlar / Alarmlar / Bildirimler / Hatırlatıcılar ve altta Saat ayarları
// =====================================================================

class WatchPage(act: MainActivity) : Page(act, "Saat") {

    companion object {
        const val FACES = 0
        const val ALARMS = 1
        const val NOTIFY = 2
        const val REMINDERS = 3
        private const val FACES_SHOWN = 6
        private const val LAST_SEEN_INFO = "Bağlantı beklenmedik koparsa telefonun konumu kaydedilir. Yalnızca son kayıt tutulur."
    }

    private class SettingViews(
        val main: TextView,
        val sub: TextView,
        val toggle: Toggle,
        val status: StatusLine,
        val retry: Button,
        val card: View? = null,
    )

    private var section = FACES
    private val chips = ArrayList<TextView>()
    private val sections = ArrayList<LinearLayout>()
    private val addFace: View

    // ---------- Kadranlar ----------
    private val facesBox: LinearLayout = ui.vertical()
    private val ls05Faces: LinearLayout = ui.vertical()
    private val faceCount: TextView = ui.text("", 12f, R.color.text2)
    private val faceGrid: LinearLayout = ui.vertical()
    private val faceMore: Button = ui.outlineAccent("") { allFaces = !allFaces; refreshFaces() }
    private val faceStatus: TextView = ui.caption("")
    private val faceThumbs = ArrayList<Pair<String, FaceThumbView>>()
    private var allFaces = false

    /** Band 8 kadran galerisi, yerleşikler ve navigasyon kadranı. MainActivity dosya seçici sonucunu buraya verir. */
    val bandFaces = Band8FaceCard(act)

    // ---------- Alarmlar ----------
    private val alarmsBox: LinearLayout = ui.vertical()
    private val alarms = ArrayList<SettingViews>()
    private val band8Alarms = Band8AlarmsCard(act)
    private val addAlarm: Button = ui.secondary("Alarm ekle") { addAlarm() }
    private val noAlarm: TextView = ui.caption("Henüz alarm yok. Saatte en çok 3 alarm kurulabilir.")
    private val exactBanner: Banner = ui.banner(
        "Tam zamanlı alarm izni yok. Alarm notu ve hatırlatıcılar gecikebilir.",
        "İzin ver",
    ) { requestExactAlarm() }

    // ---------- Bildirimler ve Hatırlatıcılar ----------
    val notify = NotifySection(act)
    private val reminders = RemindersScreen(act)

    // ---------- Saat ayarları ----------
    private val dnd: SettingViews
    private val dndBlock: LinearLayout = ui.vertical()
    private val bandDndRow: LinearLayout = ui.horizontal()
    private var lastDndNowAt = 0L
    private val findRow: LinearLayout
    private val findTitle: TextView
    private val findSub: TextView
    private val findButton: Button = ui.tonal("Titret") { toggleFind() }
    private val band8Idle = Band8IdleCard(act)
    private val settingsCard: LinearLayout = ui.card(padDp = 16, padVDp = 4)

    // Ekran ve profil (LS05)
    private val profileBox = ui.collapsible("Ekran ve profil", "Ekran süresi, bilek, adım hedefi", null, open = false)
    private val screenRow: NavRow = ui.navRow(null, "Ekran açık kalma süresi", null, "") { editScreenTime() }
    private val wristRow: SwitchRow = ui.switchRow("Bileği kaldırınca aç", null, ProfileSetting.DEFAULT.raiseWrist) { on ->
        SettingsStore.setProfileDraft(act, SettingsStore.profileDraft(act).copy(raiseWrist = on))
    }
    private val goalRow: NavRow = ui.navRow(null, "Adım hedefi", null, "") { editGoal() }
    private val profileRow: NavRow = ui.navRow(null, "Profil", "", "Düzenle") { editProfile() }
    private val profileStatus: StatusLine = ui.statusLine()
    private val profileSend: Button = ui.tonal("Saate gönder") { sendProfileFirstTime() }
    private val profileRetry: Button = ui.tonal("Yeniden gönder") { SettingsStore.retry(act, ProfileSetting.KEY) }

    // Hava durumu (LS05)
    private val weatherBox = ui.collapsible("Hava durumu", null, R.drawable.ic_weather, open = false)
    private val weatherSwitch: SwitchRow = ui.switchRow(
        "Hava durumunu saate gönder",
        "Open-Meteo'dan saatte bir alınır. Yalnızca açıkken internete çıkılır.",
        Prefs.weatherEnabled(act),
    ) { on ->
        Prefs.setWeatherEnabled(act, on)
        if (on && Prefs.weatherLat(act) == null) pickCity() else if (on) WeatherSync.refresh(act, force = true)
        refreshExtras()
    }
    private val cityRow: NavRow = ui.navRow(null, "Şehir", null, "") { pickCity() }
    private val weatherInfo: TextView = ui.caption("")

    // Uyarılar ve son görülen yer
    private val alertsBox = ui.collapsible("Uzaklaşma uyarısı ve kumanda", null, R.drawable.ic_alert, open = false)
    private val remote: SwitchRow = ui.switchRow(
        "Saatten kumanda",
        "Saatin müzik, ses ve telefonu bul düğmeleri telefonda uygulanır.",
        Prefs.remoteControl(act),
    ) { Prefs.setRemoteControl(act, it) }
    private val lostAlert: SwitchRow = ui.switchRow(
        "Saat uzaklaşınca uyar",
        "Bağlantı beklenmedik koparsa ve 1 dakika içinde geri gelmezse telefonda uyarı çıkar. Konum tutulmaz.",
        Prefs.lostAlert(act),
    ) { on ->
        Prefs.setLostAlert(act, on)
        if (!on) LinkAlert.disarm(act, "ayar kapatıldı")
        if (on && android.os.Build.VERSION.SDK_INT >= 33 &&
            act.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            act.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        refreshExtras()
    }
    private val lostQuiet: SwitchRow = ui.switchRow("Sessiz saatlerde uyarma", "", Prefs.lostQuiet(act)) {
        Prefs.setLostQuiet(act, it)
    }
    private val lastSeenSwitch: SwitchRow = ui.switchRow(
        "Son görülen yeri kaydet",
        LAST_SEEN_INFO,
        Prefs.lastSeenEnabled(act),
    ) { on -> toggleLastSeen(on) }
    private val lastSeenBox: LinearLayout = ui.vertical()
    private var lastSeenAsked = false
    private var lastSeenDenied = false

    init {
        val addBtn = ui.horizontal()
        addBtn.gravity = Gravity.CENTER
        addBtn.minimumHeight = ui.dp(40)
        addBtn.setPadding(ui.dp(14), 0, ui.dp(14), 0)
        addBtn.background = ui.ripple(ui.round(ui.color(R.color.accent_fill), 20), null)
        addBtn.isClickable = true
        addBtn.isFocusable = true
        addBtn.contentDescription = "Dosyadan kadran ekle"
        ui.icon(R.drawable.ic_plus, R.color.on_accent)?.let { addBtn.addView(it, LinearLayout.LayoutParams(ui.dp(18), ui.dp(18))) }
        ui.add(addBtn, ui.text("Dosyadan ekle", 14f, R.color.on_accent, bold = true), width = Ui.WRAP, leftDp = 6)
        addBtn.setOnClickListener { addFaceFromFile() }
        addFace = addBtn
        ui.add(body, ui.header("Saat", addBtn))

        // Bölüm kapsülleri: yatay kaydırılır.
        val chipRow = ui.horizontal()
        listOf("Kadranlar", "Alarmlar", "Bildirimler", "Hatırlatıcılar").forEachIndexed { i, n ->
            val c = ui.chip(n) { select(i) }
            chips += c
            ui.add(chipRow, c, width = Ui.WRAP, leftDp = if (i == 0) 0 else 8)
        }
        val chipScroll = HorizontalScrollView(act)
        chipScroll.isHorizontalScrollBarEnabled = false
        chipScroll.clipToPadding = false
        chipScroll.setPadding(ui.dp(16), 0, ui.dp(16), 0)
        chipScroll.addView(chipRow)
        val lp = LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP)
        lp.topMargin = ui.dp(10)
        lp.leftMargin = -ui.dp(16)
        lp.rightMargin = -ui.dp(16)
        body.addView(chipScroll, lp)

        buildFaces()
        buildAlarms()
        sections += facesBox
        sections += alarmsBox
        sections += notify.view
        sections += reminders.view
        sections.forEach { ui.add(body, it, topDp = 14) }

        // Saat ayarları: her bölümün altında.
        ui.add(body, ui.section("Saat ayarları"), topDp = 24)
        val dndRow = ui.horizontal()
        dndRow.minimumHeight = ui.dp(56)
        val dndTexts = ui.vertical()
        ui.add(dndTexts, ui.text("Rahatsız etme", 15f))
        val dndMain = ui.text("", 13f, R.color.text2)
        ui.add(dndTexts, dndMain, topDp = 2)
        ui.add(dndRow, dndTexts, width = 0, weight = 1f)
        val dndToggle = ui.toggle(false) { on -> toggleDnd(on) }
        ui.add(dndRow, dndToggle.sw, width = Ui.WRAP, leftDp = 12)
        dndRow.background = ui.pressable(12)
        dndRow.isClickable = true
        dndRow.setOnClickListener { editDnd() }
        ui.add(dndBlock, dndRow)
        val dndStatus = ui.statusLine()
        ui.add(dndBlock, dndStatus.view)
        val dndRetry = ui.tonal("Yeniden gönder") { SettingsStore.retry(act, DndSetting.KEY) }
        dndRetry.visibility = View.GONE
        ui.add(dndBlock, dndRetry, topDp = 8, width = Ui.WRAP)
        // Band 8: rahatsız etmeyi hemen aç ya da kapat (2/23). Saat aralığı satırla (17/9) ayarlanır.
        ui.add(bandDndRow, ui.tonal("Şimdi aç") { bandDndNow(true) }, width = 0, weight = 1f)
        ui.add(bandDndRow, ui.secondary("Kapat") { bandDndNow(false) }, width = 0, weight = 1f, leftDp = 8)
        ui.add(dndBlock, bandDndRow, topDp = 8)
        ui.add(dndBlock, ui.space(), height = ui.dp(8))
        dnd = SettingViews(dndMain, ui.caption(""), dndToggle, dndStatus, dndRetry)

        findRow = ui.horizontal()
        findRow.minimumHeight = ui.dp(56)
        val findTexts = ui.vertical()
        findTitle = ui.text("Saati bul", 15f)
        findSub = ui.caption("")
        ui.add(findTexts, findTitle)
        ui.add(findTexts, findSub, topDp = 2)
        ui.add(findRow, findTexts, width = 0, weight = 1f)
        ui.add(findRow, findButton, width = Ui.WRAP, leftDp = 12)

        ui.add(settingsCard, dndBlock)
        ui.add(settingsCard, ui.divider(), height = ui.dp(1))
        ui.add(settingsCard, findRow)
        ui.add(settingsCard, band8Idle.view)
        ui.add(body, settingsCard, topDp = 8)

        ui.add(profileBox.content, screenRow.view)
        ui.add(profileBox.content, ui.divider(), height = ui.dp(1))
        ui.add(profileBox.content, wristRow.view)
        ui.add(profileBox.content, ui.divider(), height = ui.dp(1))
        ui.add(profileBox.content, goalRow.view)
        ui.add(profileBox.content, ui.divider(), height = ui.dp(1))
        ui.add(profileBox.content, profileRow.view)
        ui.add(profileBox.content, profileStatus.view, topDp = 4)
        ui.add(profileBox.content, profileSend, topDp = 10, width = Ui.WRAP)
        profileRetry.visibility = View.GONE
        ui.add(profileBox.content, profileRetry, topDp = 10, width = Ui.WRAP)
        ui.add(body, profileBox.view, topDp = 12)

        ui.add(weatherBox.content, weatherSwitch.view)
        ui.add(weatherBox.content, ui.divider(), height = ui.dp(1))
        ui.add(weatherBox.content, cityRow.view)
        val wxBottom = ui.horizontal()
        ui.add(wxBottom, weatherInfo, width = 0, weight = 1f)
        ui.add(wxBottom, ui.tonal("Güncelle") {
            if (!Prefs.weatherEnabled(act) || Prefs.weatherLat(act) == null) {
                act.toast("Önce hava durumunu aç ve şehir seç.")
            } else {
                weatherInfo.text = "Alınıyor…"
                WeatherSync.refresh(act, force = true) { err ->
                    if (err != null) act.toast(err)
                    refreshExtras()
                }
            }
        }, width = Ui.WRAP, leftDp = 8)
        ui.add(weatherBox.content, wxBottom, topDp = 4)
        ui.add(body, weatherBox.view, topDp = 12)

        ui.add(alertsBox.content, lostAlert.view)
        ui.add(alertsBox.content, ui.divider(), height = ui.dp(1))
        ui.add(alertsBox.content, lostQuiet.view)
        ui.add(alertsBox.content, ui.divider(), height = ui.dp(1))
        ui.add(alertsBox.content, lastSeenSwitch.view)
        ui.add(alertsBox.content, lastSeenBox)
        ui.add(alertsBox.content, ui.divider(), height = ui.dp(1))
        ui.add(alertsBox.content, remote.view)
        ui.add(body, alertsBox.view, topDp = 12)

        select(FACES)
    }

    /** Bölüm seçimi (Ana > Galeriyi aç da kullanır). */
    fun select(i: Int) {
        section = i.coerceIn(0, sections.size - 1)
        chips.forEachIndexed { k, c -> ui.setChip(c, k == section) }
        sections.forEachIndexed { k, v -> v.visibility = if (k == section) View.VISIBLE else View.GONE }
        addFace.visibility = if (section == FACES && WatchRegistry.active(act) != null) View.VISIBLE else View.GONE
        when (section) {
            FACES -> refreshFaces()
            NOTIFY -> notify.refresh()
            REMINDERS -> reminders.render()
        }
    }

    // ---------- Kadranlar ----------

    private fun buildFaces() {
        val head = ui.horizontal()
        ui.add(head, ui.text("Galeri", 13f, R.color.text2, bold = true), width = 0, weight = 1f)
        ui.add(head, faceCount, width = Ui.WRAP)
        ui.add(ls05Faces, head)
        faceStatus.visibility = View.GONE
        ui.add(ls05Faces, faceStatus, topDp = 8)
        ui.add(ls05Faces, faceGrid, topDp = 8)
        ui.add(ls05Faces, faceMore, topDp = 12)
        ui.add(facesBox, ls05Faces)
        ui.add(facesBox, bandFaces.view)
    }

    private fun addFaceFromFile() {
        if (WatchRegistry.active(act)?.type == WatchType.BAND8) bandFaces.pick() else act.pickFace()
    }

    /** Galeriyi yeniden kurar (ekleme, silme, yükleme sonrası). Etkin saate göre LS05 ya da Band 8 galerisi. */
    fun refreshFaces() {
        val band = WatchRegistry.active(act)?.type == WatchType.BAND8
        ls05Faces.visibility = if (band) View.GONE else View.VISIBLE
        bandFaces.view.visibility = if (band) View.VISIBLE else View.GONE
        if (band) {
            bandFaces.refresh()
            return
        }
        faceGrid.removeAllViews()
        faceThumbs.clear()
        val all = FaceLibrary.list(act).reversed() // en yeni önce
        val current = currentFace(all)
        // Saattekiler önce.
        val ordered = all.filter { it.id == current } + all.filter { it.id != current }
        faceCount.text = "${all.size} kadran"
        if (all.isEmpty()) {
            ui.add(faceGrid, ui.caption("Henüz kadran yok. \"Dosyadan ekle\" ile .bin ya da .tbwf kadran dosyası seç."))
        }
        val shown = if (allFaces) ordered else ordered.take(FACES_SHOWN)
        FaceGrid.build(ui, faceGrid, shown.map { e ->
            val th = FaceThumbView(act)
            th.set(FaceLibrary.preview(e), e.id == current)
            faceThumbs += e.id to th
            FaceGrid.Tile(e.name, if (e.id == current) "Saatte" else "Telefonda", e.id == current, th, ui.dp(84), ui.dp(84)) { openFace(e) }
        })
        faceMore.visibility = if (all.size > FACES_SHOWN) View.VISIBLE else View.GONE
        faceMore.text = if (allFaces) "Daha az göster" else "Tümünü göster (${all.size})"
        refreshFaceProgress()
    }

    /** Yükleme ilerlemesi: ilgili önizlemenin halkası ve durum satırı. */
    fun refreshFaceProgress() {
        val busy = WatchHub.faceBusy
        val id = WatchHub.faceId
        faceThumbs.forEach { (k, v) -> v.setProgress(if (busy && k == id) WatchHub.facePercent else -1) }
        val text = WatchHub.faceText
        faceStatus.text = text
        faceStatus.setTextColor(ui.color(if (WatchHub.facePhase == FaceUpload.Phase.FAILED) R.color.warning else R.color.text2))
        faceStatus.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    // ---------- Alarmlar ----------

    private fun buildAlarms() {
        // Band 8: bandın kendi alarm listesi (LS05 yuvaları yerine).
        ui.add(alarmsBox, band8Alarms.view)
        for (slot in 1..UteAlarm.SLOTS) {
            val key = AlarmSetting.key(slot)
            alarms += alarmCard(
                top = if (slot == 1) 0 else 10,
                onEdit = { AlarmSheet(act, slot, isNew = false).show() },
                onToggle = { on -> toggleAlarm(slot, on) },
                onRetry = { SettingsStore.retry(act, key) },
            )
        }
        noAlarm.setPadding(ui.dp(4), 0, ui.dp(4), 0)
        ui.add(alarmsBox, noAlarm, topDp = 8)
        ui.add(alarmsBox, addAlarm, topDp = 10, width = Ui.WRAP)
        exactBanner.view.visibility = View.GONE
        ui.add(alarmsBox, exactBanner.view, topDp = 10)
    }

    override fun refresh() {
        val caps = WatchManager.caps(act)
        val active = WatchRegistry.active(act)
        val band = active?.type == WatchType.BAND8
        fun show(v: View?, on: Boolean) { v?.visibility = if (on) View.VISIBLE else View.GONE }
        addFace.visibility = if (section == FACES && active != null) View.VISIBLE else View.GONE

        // Alarmlar
        val slots = if (band) 0 else minOf(caps.alarmSlots, UteAlarm.SLOTS)
        show(band8Alarms.view, band)
        if (band) band8Alarms.refresh()
        val visible = SettingsStore.visibleAlarms(act).filter { it <= slots }
        for (slot in 1..UteAlarm.SLOTS) {
            val e = SettingsStore.entry(act, AlarmSetting.key(slot))
            val a = AlarmSetting.decode(e.desired) ?: AlarmSetting.DEFAULT
            val v = alarms[slot - 1]
            v.card?.visibility = if (slot in visible) View.VISIBLE else View.GONE
            v.main.text = Fmt.hm(a.hour, a.minute)
            v.main.setTextColor(ui.color(if (a.enabled) R.color.text else R.color.muted))
            // Ör. "Hafta içi · Sabah ilacı · 20 sn"
            v.sub.text = listOf(UteAlarm.maskLabel(a.mask), Reminders.note(act, slot), UteAlarm.vibesLabel(a.vibes))
                .filter { it.isNotEmpty() }.joinToString(" · ")
            v.toggle.set(a.enabled)
            v.toggle.sw.contentDescription = "Alarm $slot"
            showStatus(v, e)
        }
        noAlarm.visibility = if (visible.isEmpty() && slots > 0) View.VISIBLE else View.GONE
        addAlarm.visibility = if (slots > 0 && visible.size < slots) View.VISIBLE else View.GONE
        exactBanner.view.visibility =
            if (Reminders.anyActive(act) && !Reminders.canExact(act)) View.VISIBLE else View.GONE

        // Saat ayarları
        show(dndBlock, caps.dnd)
        show(bandDndRow, band)
        val e = SettingsStore.entry(act, DndSetting.KEY)
        val d = DndSetting.decode(e.desired) ?: DndSetting.DEFAULT
        dnd.main.text = "${Fmt.hm(d.startHour, d.startMinute)} → ${Fmt.hm(d.endHour, d.endMinute)}"
        dnd.toggle.set(d.enabled)
        dnd.toggle.sw.contentDescription = "Rahatsız etme"
        showStatus(dnd, e)
        show(findRow, caps.findWatch && active != null)
        findTitle.text = if (band) "Bandı bul" else "Saati bul"
        val finding = WatchHub.ready && WatchHub.service?.finding == true
        findButton.text = if (finding) "Durdur" else "Titret"
        findSub.text = if (finding) "Titriyor. En çok 30 sn sürer." else "30 sn boyunca titrer."
        show(band8Idle.view, band)
        if (band) band8Idle.refresh()
        show(profileBox.view, caps.profile && active != null)
        show(weatherBox.view, caps.weather && active != null)
        show(remote.view, caps.musicRemote || caps.findPhone)
        show(settingsCard, active != null)
        refreshProfile()
        refreshExtras()

        when (section) {
            FACES -> refreshFaces()
            NOTIFY -> notify.refresh()
        }
    }

    private fun bandDndNow(on: Boolean) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastDndNowAt < 2_000L) return
        lastDndNowAt = now
        val s = act.readyService() ?: return
        val err = s.bandDndNow(on)
        act.toast(err ?: if (on) "Rahatsız etme açılıyor." else "Rahatsız etme kapatılıyor.")
    }

    /** Titret: sürekli titreşim (en çok 30 sn); Durdur: titreşimi keser. */
    private fun toggleFind() {
        val s = WatchHub.service
        if (s != null && s.finding) {
            s.stopFind("kullanıcı durdurdu")
            return
        }
        val ready = act.readyService() ?: return
        if (!ready.startFind()) act.toast("Saatte arama ekranı açık; şimdi titretilemez.")
    }

    private fun faceOnWatch(): String = currentFace(FaceLibrary.list(act))

    /** Hava durumu ve uyarı satırları (eski Ayarlar bölümleri). */
    fun refreshExtras() {
        weatherSwitch.toggle.set(Prefs.weatherEnabled(act))
        cityRow.value.text = Prefs.weatherCityName(act).ifEmpty { "Seçilmedi" }
        weatherInfo.text = WeatherSync.summary(act)
        remote.toggle.set(Prefs.remoteControl(act))
        lostAlert.toggle.set(Prefs.lostAlert(act))
        lostQuiet.toggle.set(Prefs.lostQuiet(act))
        ui.enable(lostQuiet.view, Prefs.lostAlert(act))
        lostQuiet.toggle.sw.isEnabled = Prefs.lostAlert(act)
        // Sessiz saatler saatin rahatsız etme penceresidir; ayrı saat seçimi yok (bkz. LinkAlert).
        val dnd = DndSetting.decode(SettingsStore.entry(act, DndSetting.KEY).desired) ?: DndSetting.DEFAULT
        lostQuiet.subtitle.text = if (dnd.enabled) {
            "Saatin rahatsız etme saatleri: ${Fmt.hm(dnd.startHour, dnd.startMinute)}-${Fmt.hm(dnd.endHour, dnd.endMinute)}."
        } else {
            "Saatin rahatsız etme saatleri kullanılır. Rahatsız etme şu an kapalı."
        }
        lostQuiet.subtitle.visibility = View.VISIBLE
        refreshLastSeen()
    }

    /** Alarm kartı: ikon, saat, günler, durum satırı, anahtar. Karta dokununca düzenleme açılır. */
    private fun alarmCard(
        top: Int,
        onEdit: () -> Unit,
        onToggle: (Boolean) -> Unit,
        onRetry: () -> Unit,
    ): SettingViews {
        val card = ui.card(onEdit, padDp = 14)
        val row = ui.horizontal()
        ui.icon(R.drawable.ic_alarm, R.color.text2)?.let {
            val lp = LinearLayout.LayoutParams(ui.dp(24), ui.dp(24))
            lp.rightMargin = ui.dp(14)
            row.addView(it, lp)
        }
        val left = ui.vertical()
        val main = ui.big("", 28f)
        val sub = ui.caption("")
        val status = ui.statusLine()
        ui.add(left, main)
        ui.add(left, sub, topDp = 3)
        ui.add(left, status.view, topDp = 4)
        ui.add(row, left, width = 0, weight = 1f)
        val tg = ui.toggle(false, onToggle)
        ui.add(row, tg.sw, width = Ui.WRAP, leftDp = 12)
        ui.add(card, row)
        val retry = ui.tonal("Yeniden gönder", onRetry)
        retry.visibility = View.GONE
        ui.add(card, retry, topDp = 8, width = Ui.WRAP)
        ui.add(alarmsBox, card, topDp = top)
        return SettingViews(main, sub, tg, status, retry, card)
    }

    /** İlk gizli yuva için boş alarm paneli. */
    private fun addAlarm() {
        val slots = minOf(WatchManager.caps(act).alarmSlots, UteAlarm.SLOTS)
        val slot = (1..slots).firstOrNull { !Prefs.alarmVisible(act, it) } ?: return
        AlarmSheet(act, slot, isNew = true).show()
    }

    /** Titret: sürekli titreşim (en çok 30 sn); Durdur: titreşimi keser. */
    private fun toggleFind() {
        val s = WatchHub.service
        if (s != null && s.finding) {
            s.stopFind("kullanıcı durdurdu")
            return
        }
        val ready = act.readyService() ?: return
        if (!ready.startFind()) act.toast("Saatte arama ekranı açık; şimdi titretilemez.")
    }

    private fun alarm(slot: Int): AlarmSetting =
        AlarmSetting.decode(SettingsStore.entry(act, AlarmSetting.key(slot)).desired) ?: AlarmSetting.DEFAULT

    private fun dndSetting(): DndSetting =
        DndSetting.decode(SettingsStore.entry(act, DndSetting.KEY).desired) ?: DndSetting.DEFAULT

    private fun toggleAlarm(slot: Int, on: Boolean) {
        SettingsStore.set(act, AlarmSetting.key(slot), alarm(slot).copy(enabled = on).encode())
    }

    private fun toggleDnd(on: Boolean) {
        SettingsStore.set(act, DndSetting.KEY, dndSetting().copy(enabled = on).encode())
    }
    /** Saatteki kadran: uygulamanın kaydı (en son yüklenen). Galeride yoksa boş. Otomatik sorgu kullanılmaz. */
    private fun currentFace(all: List<FaceLibrary.Entry>): String {
        val id = Prefs.currentFace(act)
        return if (all.any { it.id == id }) id else ""
    }

    private fun faceOnWatch(): String = currentFace(FaceLibrary.list(act))
    private fun openFace(e: FaceLibrary.Entry) {
        val f = FaceLibrary.load(e)
        val box = ui.vertical()
        box.gravity = Gravity.CENTER_HORIZONTAL
        box.setPadding(ui.dp(24), ui.dp(20), ui.dp(24), ui.dp(4))
        val big = FaceThumbView(act)
        val onWatch = faceOnWatch() == e.id
        big.set(FaceLibrary.preview(e), onWatch)
        box.addView(big, LinearLayout.LayoutParams(ui.dp(200), ui.dp(200)))
        val name = ui.text(e.name, 18f, R.color.text, bold = true)
        name.gravity = Gravity.CENTER
        ui.add(box, name, topDp = 14)
        val info = buildString {
            append("${e.sizeKb} KB")
            if (f != null) append(" · ${f.elements.size} öğe")
            if (onWatch) append(" · Saatte şu an")
        }
        val cap = ui.caption(if (f == null) "Dosya okunamadı." else info)
        cap.gravity = Gravity.CENTER
        ui.add(box, cap, topDp = 4)

        val d = AlertDialog.Builder(act)
            .setView(box)
            .setPositiveButton("Saate yükle", null)
            .setNeutralButton("Sil", null)
            .setNegativeButton("Kapat", null)
            .create()
        d.setOnShowListener {
            val up = d.getButton(AlertDialog.BUTTON_POSITIVE)
            up.isEnabled = f != null && WatchHub.ready && !WatchHub.faceBusy
            up.setOnClickListener {
                d.dismiss()
                if (f != null) confirmUpload(e, f)
            }
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                if (WatchHub.faceBusy && WatchHub.faceId == e.id) {
                    act.toast("Bu kadran şu an yükleniyor.")
                    return@setOnClickListener
                }
                d.dismiss()
                AlertDialog.Builder(act)
                    .setMessage("\"${e.name}\" galeriden silinsin mi? Saatteki kadran değişmez.")
                    .setPositiveButton("Sil") { _, _ ->
                        FaceLibrary.delete(act, e.id)
                        refreshFaces()
                    }
                    .setNegativeButton("Vazgeç", null)
                    .show()
            }
        }
        d.show()
    }

    /** Onay penceresi yok: doğrudan yükler. Pil sınırın altındaysa ya da saat hazır değilse nedeni gösterilir. */
    private fun confirmUpload(e: FaceLibrary.Entry, f: com.che.ls05.protocol.FaceFile) {
        val s = act.readyService() ?: return
        val err = s.startFace(f, e.id)
        if (err != null) {
            AlertDialog.Builder(act).setMessage(err).setPositiveButton("Tamam", null).show()
        }
    }
    private fun refreshProfile() {
        val p = SettingsStore.profileDraft(act)
        screenRow.value.text = "${p.screenSec} sn"
        wristRow.toggle.set(p.raiseWrist)
        goalRow.value.text = Fmt.num(p.stepGoal)
        profileRow.subtitle.text =
            if (p.personalComplete) "Boy, kilo, yaş ve cinsiyet girildi." else "Boy, kilo, yaş ve cinsiyet girilmedi."

        val e = SettingsStore.entry(act, ProfileSetting.KEY)
        val verified = Prefs.profileVerified(act)
        val phase = e.phase
        when {
            !p.personalComplete ->
                ui.setStatus(profileStatus, StatusKind.NO_ACK, "Profil girilmeden saate hiçbir şey gönderilmez.")
            !verified && phase == SyncPhase.SENDING ->
                ui.setStatus(profileStatus, StatusKind.SENDING, "Gönderiliyor…")
            !verified && phase == SyncPhase.NO_ACK ->
                ui.setStatus(profileStatus, StatusKind.NO_ACK, "Onay gelmedi. Saatteki durum bilinmiyor.")
            !verified ->
                ui.setStatus(
                    profileStatus, StatusKind.IDLE,
                    "İlk gönderim elle yapılır: Saate gönder, sonra saatteki ekran süresini kontrol et.",
                )
            e.desired != null && e.desired != p.encode() && phase != SyncPhase.SENDING ->
                ui.setStatus(profileStatus, StatusKind.WAITING, "Bağlanınca gönderilecek")
            else -> {
                val (kind, text) = statusText(e)
                ui.setStatus(profileStatus, kind, text)
            }
        }
        profileSend.visibility = if (p.personalComplete && !verified) View.VISIBLE else View.GONE
        ui.enable(profileSend, WatchHub.ready && phase != SyncPhase.SENDING)
        profileRetry.visibility =
            if (verified && (phase == SyncPhase.NO_ACK || phase == SyncPhase.UNKNOWN)) View.VISIBLE else View.GONE
    }

    /** İlk gönderim kontrollü testtir: yalnızca kullanıcı dokununca ve saat bağlıyken. */
    private fun sendProfileFirstTime() {
        val p = SettingsStore.profileDraft(act)
        if (p.command() == null) {
            act.toast("Önce profili eksiksiz gir.")
            return
        }
        act.readyService() ?: return
        WatchHub.log("Profil ve ekran ayarı elle saate gönderiliyor (ilk kontrollü test).")
        SettingsStore.set(act, ProfileSetting.KEY, p.encode())
    }

    private fun editScreenTime() {
        val cur = SettingsStore.profileDraft(act)
        val options = UteProfile.SCREEN_SECONDS
        val labels = options.map { "$it sn" }.toTypedArray()
        val dialog = AlertDialog.Builder(act)
            .setTitle("Ekran açık kalma süresi")
            .setSingleChoiceItems(labels, options.indexOf(cur.screenSec)) { d, which ->
                SettingsStore.setProfileDraft(act, SettingsStore.profileDraft(act).copy(screenSec = options[which]))
                d.dismiss()
            }
            .setNegativeButton("Vazgeç", null)
            .create()
        act.track(dialog)
        dialog.show()
    }

    private fun editGoal() {
        val cur = SettingsStore.profileDraft(act)
        val box = ui.dialogBox()
        ui.add(box, ui.caption("Sağlık ekranındaki hedef ve saatin hedefi aynıdır."), topDp = 8)
        val input = ui.field("Adım")
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.setText(cur.stepGoal.toString())
        ui.add(box, input, topDp = 16)
        val dialog = AlertDialog.Builder(act)
            .setTitle("Adım hedefi")
            .setView(box)
            .setPositiveButton("Kaydet", null)
            .setNegativeButton("Vazgeç", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val v = input.text.toString().trim().toIntOrNull()
                val range = UteProfile.GOAL_RANGE
                if (v == null || v !in range) {
                    act.toast("Hedef ${Fmt.num(range.first)} ile ${Fmt.num(range.last)} arasında olmalı.")
                } else {
                    SettingsStore.setProfileDraft(act, SettingsStore.profileDraft(act).copy(stepGoal = v))
                    dialog.dismiss()
                }
            }
        }
        act.track(dialog)
        dialog.show()
    }

    /** Boy, kilo, yaş, cinsiyet. Değerler yalnızca telefonda saklanır, logda gösterilmez. */
    fun editProfile() {
        val cur = SettingsStore.profileDraft(act)
        val box = ui.dialogBox()
        ui.add(box, ui.caption("Yalnızca telefonda saklanır, logda gösterilmez. Saate ekran ayarlarıyla aynı pakette gider."), topDp = 8)

        fun numberField(title: String, value: Int?): android.widget.EditText {
            ui.add(box, ui.cardTitle(title), topDp = 16)
            val f = ui.field(title)
            f.inputType = InputType.TYPE_CLASS_NUMBER
            f.setText(value?.toString() ?: "")
            ui.add(box, f, topDp = 4)
            return f
        }
        val height = numberField("Boy (cm)", cur.heightCm)
        val weight = numberField("Kilo (kg)", cur.weightKg)
        val age = numberField("Yaş", cur.age)

        ui.add(box, ui.cardTitle("Cinsiyet"), topDp = 16)
        val male = ui.check("Erkek", cur.sex == UteProfile.SEX_MALE)
        val female = ui.check("Kadın", cur.sex == UteProfile.SEX_FEMALE)
        male.setOnCheckedChangeListener { _, on -> if (on) female.isChecked = false }
        female.setOnCheckedChangeListener { _, on -> if (on) male.isChecked = false }
        val sexRow = ui.horizontal()
        ui.add(sexRow, male, width = 0, weight = 1f)
        ui.add(sexRow, female, width = 0, weight = 1f)
        ui.add(box, sexRow, topDp = 4)

        val dialog = AlertDialog.Builder(act)
            .setTitle("Profil")
            .setView(ui.scroll(box))
            .setPositiveButton("Kaydet", null)
            .setNegativeButton("Vazgeç", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val h = height.text.toString().trim().toIntOrNull()
                val w = weight.text.toString().trim().toIntOrNull()
                val a = age.text.toString().trim().toIntOrNull()
                val sex = when {
                    male.isChecked -> UteProfile.SEX_MALE
                    female.isChecked -> UteProfile.SEX_FEMALE
                    else -> null
                }
                val error = when {
                    h == null || h !in UteProfile.HEIGHT_RANGE ->
                        "Boy ${UteProfile.HEIGHT_RANGE.first} ile ${UteProfile.HEIGHT_RANGE.last} cm arasında olmalı."
                    w == null || w !in UteProfile.WEIGHT_RANGE ->
                        "Kilo ${UteProfile.WEIGHT_RANGE.first} ile ${UteProfile.WEIGHT_RANGE.last} kg arasında olmalı."
                    a == null || a !in UteProfile.AGE_RANGE ->
                        "Yaş ${UteProfile.AGE_RANGE.first} ile ${UteProfile.AGE_RANGE.last} arasında olmalı."
                    sex == null -> "Cinsiyet seç."
                    else -> null
                }
                if (error != null) {
                    act.toast(error)
                } else {
                    SettingsStore.setProfileDraft(
                        act,
                        SettingsStore.profileDraft(act).copy(heightCm = h, weightKg = w, age = a, sex = sex),
                    )
                    WatchHub.log("Profil kaydedildi (değerler loga yazılmaz).")
                    dialog.dismiss()
                }
            }
        }
        act.track(dialog)
        dialog.show()
    }

    private fun showStatus(v: SettingViews, e: SyncEntry) {
        val (kind, text) = statusText(e)
        ui.setStatus(v.status, kind, text)
        val phase = e.phase
        v.retry.visibility =
            if (phase == SyncPhase.NO_ACK || phase == SyncPhase.UNKNOWN) View.VISIBLE else View.GONE
    }

    /** Şartname bölüm 4: altı hal. Onayı gelmeyen komut başarısız sayılmaz; kırmızı kullanılmaz. */
    private fun statusText(e: SyncEntry): Pair<StatusKind, String> = when (e.phase) {
        SyncPhase.SENDING -> StatusKind.SENDING to "Gönderiliyor…"
        SyncPhase.CONFIRMED ->
            StatusKind.CONFIRMED to
                (if (e.confirmedAt == 0L) "Saat onayladı" else "Saat onayladı · ${Fmt.dayMs(e.confirmedAt)}")
        SyncPhase.PENDING ->
            if (WatchHub.ready) StatusKind.SENDING to "Gönderiliyor…" else StatusKind.WAITING to "Bağlanınca gönderilecek"
        SyncPhase.NO_ACK -> StatusKind.NO_ACK to "Onay gelmedi. Saatteki durum bilinmiyor."
        SyncPhase.UNKNOWN -> StatusKind.UNKNOWN to "Saat sıfırlanmış olabilir."
        SyncPhase.IDLE -> StatusKind.IDLE to "Henüz saate gönderilmedi"
    }

    /** Android 12 ve sonrasında tam zamanlı alarm izni ekranını açar. */
    private fun requestExactAlarm() {
        if (android.os.Build.VERSION.SDK_INT < 31) return
        act.openSettings(
            android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(android.net.Uri.parse("package:${act.packageName}")),
        )
    }

    private fun editDnd() {
        val cur = dndSetting()
        var sh = cur.startHour
        var sm = cur.startMinute
        var eh = cur.endHour
        var em = cur.endMinute

        val box = ui.dialogBox()
        val startBtn = ui.secondary(Fmt.hm(sh, sm)) {}
        startBtn.setOnClickListener {
            act.pickTime(sh, sm) { h, m ->
                sh = h
                sm = m
                startBtn.text = Fmt.hm(h, m)
            }
        }
        val endBtn = ui.secondary(Fmt.hm(eh, em)) {}
        endBtn.setOnClickListener {
            act.pickTime(eh, em) { h, m ->
                eh = h
                em = m
                endBtn.text = Fmt.hm(h, m)
            }
        }
        ui.add(box, ui.row("Başlangıç", trailing = startBtn), topDp = 8)
        ui.add(box, ui.row("Bitiş", trailing = endBtn))
        val enabledBox = ui.check("Açık", cur.enabled)
        ui.add(box, enabledBox, topDp = 8)

        val dialog = AlertDialog.Builder(act)
            .setTitle("Rahatsız etme")
            .setView(ui.scroll(box))
            .setPositiveButton("Kaydet") { _, _ ->
                SettingsStore.set(act, DndSetting.KEY, DndSetting(enabledBox.isChecked, sh, sm, eh, em).encode())
            }
            .setNegativeButton("Vazgeç", null)
            .create()
        act.track(dialog)
        dialog.show()
    }

    // ---------- Hava durumu ve son görülen yer ----------

    }

    /** Şehir arama penceresi. */
    private fun pickCity() {
        val box = ui.vertical()
        box.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), 0)
        val field = ui.field("Şehir adı, ör. İstanbul")
        field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        ui.add(box, field)
        val list = ui.vertical()
        val status = ui.caption("")
        ui.add(box, status, topDp = 8)
        ui.add(box, list, topDp = 4)
        val dialog = AlertDialog.Builder(act)
            .setTitle("Şehir seç")
            .setView(box)
            .setPositiveButton("Ara", null)
            .setNegativeButton("Vazgeç", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val q = field.text.toString().trim()
                if (q.length < 2) { status.text = "En az 2 harf yaz."; return@setOnClickListener }
                status.text = "Aranıyor…"
                list.removeAllViews()
                WeatherSync.search(q) { cities, err ->
                    when {
                        err != null -> status.text = err
                        cities.isNullOrEmpty() -> status.text = "Sonuç bulunamadı."
                        else -> {
                            status.text = "Birini seç:"
                            for (city in cities) {
                                val r = ui.navRow(null, city.name, city.detail.ifEmpty { null }, null) {
                                    Prefs.setWeatherCity(act, city.name, city.lat, city.lon)
                                    Prefs.setWeatherEnabled(act, true)
                                    dialog.dismiss()
                                    refreshExtras()
                                    WeatherSync.refresh(act, force = true) { e ->
                                        if (e != null) act.toast(e)
                                        refreshExtras()
                                    }
                                }
                                ui.add(list, r.view)
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------- Son görülen yer ----------

    private fun toggleLastSeen(on: Boolean) {
        lastSeenDenied = false
        if (!on) {
            Prefs.setLastSeenEnabled(act, false)
            WatchHub.service?.refreshForegroundType()
            refreshLastSeen()
            return
        }
        if (LastSeen.hasPermission(act)) {
            enableLastSeen()
            return
        }
        // İzin gelene kadar anahtar kapalı kalır. Android 12+ pencerede yaklaşık ya da kesin seçilir.
        lastSeenSwitch.toggle.set(false)
        lastSeenAsked = true
        val perms = if (android.os.Build.VERSION.SDK_INT >= 31) {
            arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        act.requestPermissions(perms, MainActivity.REQ_LOCATION)
    }

    private fun enableLastSeen() {
        Prefs.setLastSeenEnabled(act, true)
        val s = WatchHub.service
        if (s != null) {
            act.toast(if (s.refreshForegroundType()) "Servis konum için güncellendi." else "Servis konuma erişemedi; logu kontrol et.")
        }
        refreshLastSeen()
    }

    /** Konum izni penceresi kapandı. */
    fun onLocationPermission(granted: Boolean) {
        if (!lastSeenAsked) return
        lastSeenAsked = false
        if (granted) {
            enableLastSeen()
        } else {
            lastSeenDenied = true
            refreshLastSeen()
        }
    }

    private fun refreshLastSeen() {
        // İzin sonradan geri alındıysa ayar kapanır, kayıt silinir.
        if (Prefs.lastSeenEnabled(act) && !LastSeen.hasPermission(act)) Prefs.setLastSeenEnabled(act, false)
        val on = Prefs.lastSeenEnabled(act)
        lastSeenSwitch.toggle.set(on)
        ui.enable(lastSeenSwitch.view, true)
        if (lastSeenDenied && !on) {
            lastSeenSwitch.subtitle.text = "Konum izni verilmedi; kayıt kapalı. İzni uygulama ayarlarından da verebilirsin."
            lastSeenSwitch.subtitle.setTextColor(ui.color(R.color.warning))
        } else {
            lastSeenSwitch.subtitle.text = LAST_SEEN_INFO
            lastSeenSwitch.subtitle.setTextColor(ui.color(R.color.text2))
        }
        lastSeenBox.removeAllViews()
        if (!on) return
        val f = Prefs.lastSeen(act)
        if (f == null) {
            ui.add(lastSeenBox, ui.row("Henüz kayıt yok", "Bağlantı beklenmedik koparsa kaydedilir."))
            return
        }
        val del = ui.textButton("Sil") {
            Prefs.setLastSeen(act, null)
            refreshLastSeen()
        }
        ui.add(lastSeenBox, ui.row(LastSeen.label(f), "${Fmt.dayMs(f.at)} · Haritada açmak için dokun", trailing = del) {
            if (!LastSeen.openMap(act, f)) act.toast("Harita uygulaması bulunamadı.")
}
