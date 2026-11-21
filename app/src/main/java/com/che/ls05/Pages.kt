package com.che.ls05

import android.graphics.Bitmap
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.che.ls05.protocol.HealthLog
import com.che.ls05.protocol.HealthQuery
import com.che.ls05.protocol.MergedHealth
import com.che.ls05.protocol.UteDate
import com.che.ls05.protocol.WatchType
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Bir sekme: kaydırılabilir dikey gövde. Görünümler bir kez kurulur, refresh yalnızca günceller.
 * Ekranlar yalnızca Ui.kt bileşenleriyle kurulur; kart arası 12 dp, yatay boşluk 16 dp, üstte 20 dp.
 * Her sayfa kendi başlık satırını gövdenin en üstüne koyar (Ui.header).
 */
abstract class Page(val act: MainActivity, val title: String) {
    protected val ui: Ui = act.ui
    val body: LinearLayout = ui.vertical()
    val root: ScrollView = ui.scroll(body)

    /** Ekrana eklenen görünüm; bir sayfa kaydırma görünümünü sarabilir (ör. aşağı çekip yenileme). */
    open val view: View get() = root

    init {
        body.setPadding(ui.dp(16), ui.dp(20), ui.dp(16), ui.dp(16))
    }

    abstract fun refresh()
}

/** Sağlık verisi biçimleri ve "şimdi eşitle" (Ana ve Sağlık ortak). */
object HealthUi {
    /** "6 sa 52 dk" ya da "45 dk". */
    fun dur(min: Int): String = if (min >= 60) "${min / 60} sa ${min % 60} dk" else "$min dk"

    /** "1:24" (açıklama satırı). */
    fun hm(min: Int): String = "${min / 60}:${String.format(java.util.Locale.US, "%02d", min % 60)}"

    fun ute(d: LocalDate) = UteDate(d.year, d.monthValue, d.dayOfMonth)

    /**
     * Aşağı çekip bırakınca ya da Şimdi eşitle ile çağrılır. Okuma başladıysa ya da zaten sürüyorsa true.
     * Saat bağlı değilse ya da kadran yükleniyorsa readyService kısa uyarı gösterir.
     */
    fun request(act: MainActivity): Boolean {
        if (WatchRegistry.active(act)?.type == WatchType.BAND8) {
            // Band 8: sağlık dosyalarını şimdi çek (yalnızca okuma). Bitince ekran kendiliğinden yenilenir.
            val s = act.readyService() ?: return false
            val err = s.bandFetchNow()
            act.toast(err ?: "Band 8 sağlık dosyaları çekiliyor.")
            return false
        }
        val s = act.readyService() ?: return false
        if (s.requestHistory(true)) return true
        if (s.historyBusy) return true
        act.toast("Veriler az önce okundu.")
        return false
    }

    /** LS05 okuması sürerken gösterge döner. */
    fun busy(act: MainActivity): Boolean =
        WatchRegistry.active(act)?.type != WatchType.BAND8 && WatchHub.service?.historyBusy == true

    /** Günün nabız noktaları (günün dakikası, nabız), geçerli değerler. */
    fun heartCurve(view: HealthQuery, day: LocalDate, limit: LocalDateTime): List<Pair<Int, Int>> = view.heartPoints()
        .filter { it.at.toLocalDate() == day && !it.at.isAfter(limit) && it.bpm in HealthLog.VALID_BPM }
        .sortedBy { it.at }
        .map { (it.at.hour * 60 + it.at.minute) to it.bpm }
}

// =====================================================================
// 1. Ana
// =====================================================================

class HomePage(act: MainActivity) : Page(act, "Ana") {

    private val pull = PullRefreshLayout(act, root)
    override val view: View get() = pull

    private val banner: Banner = ui.banner("", "Ayarlar") { act.selectTab(3) }

    // Etkin saat kartı
    private val faceBox = FrameLayout(act)
    private val bandFace = BandFaceView(act)
    private val roundFace = FaceThumbView(act)
    private val heroName: TextView = ui.text("", 18f, R.color.text, bold = true)
    private val statusDot = View(act)
    private val statusText: TextView = ui.text("", 13f, R.color.accent_text, bold = true)
    private val battNum: TextView = ui.text("", 28f, R.color.text, bold = true)
    private val battUnit: TextView = ui.caption("pil")
    private val battRow: LinearLayout = ui.horizontal()
    private val battMeter = MeterView(act, ui.color(R.color.track), ui.color(R.color.accent))
    private val syncText: TextView = ui.text("", 12f, R.color.text2)
    private var facePreviewId: String? = null
    private var facePreview: Bitmap? = null

    // Bento
    private val ring = RingView(act)
    private val stepNum: TextView = ui.text("", 22f, R.color.text, bold = true)
    private val stepGoal: TextView = ui.text("", 12f, R.color.text2)
    private val heartNum: TextView = ui.text("", 28f, R.color.heart, bold = true)
    private val heartRange: TextView = ui.text("", 12f, R.color.text2)
    private val sleepNum: TextView = ui.text("", 22f, R.color.text, bold = true)
    private val sleepBar = SleepStripView(act)
    private val faceName: TextView = ui.text("", 16f, R.color.text, bold = true)
    private val faceCard: LinearLayout

    // Hızlı işlemler
    private val quickLabel: TextView = ui.section("Hızlı işlemler")
    private val quickRow: LinearLayout = ui.horizontal()
    private val findLabel: TextView
    private val findTile: LinearLayout

    init {
        pull.onRefresh = { HealthUi.request(act) }
        ui.add(body, ui.header("Finn's Watch", ui.circleButton(R.drawable.ic_sync, "Şimdi eşitle") {
            if (HealthUi.request(act)) pull.setRefreshing(true)
        }))
        ui.add(body, banner.view, topDp = 12)

        // Etkin saat: kadran önizlemesi, ad, durum, pil, son eşitleme. Dokununca saat seçimi.
        val hero = ui.card({ WatchSwitcher.show(act) }, padDp = 16)
        val heroRow = ui.horizontal()
        faceBox.addView(bandFace, FrameLayout.LayoutParams(ui.dp(62), ui.dp(148), Gravity.CENTER))
        faceBox.addView(roundFace, FrameLayout.LayoutParams(ui.dp(84), ui.dp(84), Gravity.CENTER))
        faceBox.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        ui.add(heroRow, faceBox, width = ui.dp(84), height = Ui.WRAP)
        val col = ui.vertical()
        ui.add(col, ui.caption("Etkin saat"))
        val nameRow = ui.horizontal()
        heroName.maxLines = 1
        heroName.ellipsize = android.text.TextUtils.TruncateAt.END
        ui.add(nameRow, heroName, width = 0, weight = 1f)
        ui.icon(R.drawable.ic_expand, R.color.text2)?.let { ui.add(nameRow, it, width = ui.dp(18), height = ui.dp(18), leftDp = 4) }
        ui.add(col, nameRow, topDp = 8)
        val statusRow = ui.horizontal()
        statusRow.addView(statusDot, LinearLayout.LayoutParams(ui.dp(8), ui.dp(8)))
        ui.add(statusRow, statusText, width = 0, weight = 1f, leftDp = 6)
        ui.add(col, statusRow, topDp = 8)
        battRow.gravity = Gravity.BOTTOM
        battNum.includeFontPadding = false
        ui.add(battRow, battNum, width = Ui.WRAP)
        ui.add(battRow, battUnit, width = Ui.WRAP, leftDp = 6)
        ui.add(col, battRow, topDp = 12)
        ui.add(col, battMeter, topDp = 8, height = ui.dp(6))
        ui.add(col, syncText, topDp = 8)
        ui.add(heroRow, col, width = 0, weight = 1f, leftDp = 16)
        ui.add(hero, heroRow)
        hero.contentDescription = "Etkin saat. Değiştirmek için dokun."
        ui.add(body, hero, topDp = 12)

        // 2x2: adım, nabız, uyku, kadran.
        val steps = bento("Bugün adım") { act.selectTab(1) }
        val stepRow = ui.horizontal()
        stepRow.addView(ring, LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)))
        val stepCol = ui.vertical()
        ui.add(stepCol, stepNum)
        ui.add(stepCol, stepGoal)
        ui.add(stepRow, stepCol, width = 0, weight = 1f, leftDp = 12)
        ui.add(steps, stepRow, topDp = 10)

        val heart = bento("Nabız") { act.selectTab(1) }
        heartNum.includeFontPadding = false
        ui.add(heart, heartNum, topDp = 10)
        ui.add(heart, heartRange, topDp = 10)

        val sleep = bento("Uyku") { act.selectTab(1) }
        ui.add(sleep, sleepNum, topDp = 10)
        ui.add(sleep, sleepBar, topDp = 10, height = ui.dp(8))

        faceCard = bento("Kadran") { act.openWatchSection(WatchPage.FACES) }
        faceName.maxLines = 2
        ui.add(faceCard, faceName, topDp = 10)
        ui.add(faceCard, ui.text("Galeriyi aç", 13f, R.color.accent_text, bold = true), topDp = 10)

        ui.add(body, pair(steps, heart), topDp = 12)
        ui.add(body, pair(sleep, faceCard), topDp = 12)

        // Hızlı işlemler: bul (dolu) ve müzik.
        ui.add(body, quickLabel, topDp = 16)
        findTile = actionTile(R.drawable.ic_find, "Bandı bul", filled = true) { toggleFind() }
        findLabel = findTile.getChildAt(1) as TextView
        val music = actionTile(R.drawable.ic_music, "Müzik", filled = false) { MusicSheet.show(act) }
        ui.add(quickRow, findTile, width = 0, height = ui.dp(64), weight = 1f)
        ui.add(quickRow, music, width = 0, height = ui.dp(64), weight = 1f, leftDp = 12)
        ui.add(body, quickRow, topDp = 8)
    }

    private fun bento(title: String, onClick: () -> Unit): LinearLayout {
        val c = ui.card(onClick, padDp = 16)
        ui.add(c, ui.text(title, 13f, R.color.text2))
        return c
    }

    private fun pair(a: View, b: View): LinearLayout {
        val r = LinearLayout(act)
        r.orientation = LinearLayout.HORIZONTAL
        ui.add(r, a, width = 0, height = Ui.MATCH, weight = 1f)
        ui.add(r, b, width = 0, height = Ui.MATCH, weight = 1f, leftDp = 12)
        return r
    }

    /** 64 dp yüksek, ikon ve yazı ortada. Dolu: yeşil zemin; değilse çerçeveli yüzey. */
    private fun actionTile(iconRes: Int, label: String, filled: Boolean, onClick: () -> Unit): LinearLayout {
        val r = ui.horizontal()
        r.gravity = Gravity.CENTER
        val fg = if (filled) R.color.on_accent else R.color.text
        r.background = ui.ripple(
            if (filled) ui.round(ui.color(R.color.accent_fill), 18) else ui.cardShape(18),
            ui.round(android.graphics.Color.WHITE, 18),
        )
        r.isClickable = true
        r.isFocusable = true
        r.setOnClickListener { onClick() }
        val ic = ui.icon(iconRes, fg) ?: View(act)
        r.addView(ic, LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)))
        ui.add(r, ui.text(label, 15f, fg, bold = true), width = Ui.WRAP, leftDp = 8)
        return r
    }

    private fun toggleFind() {
        val s = WatchHub.service
        if (s != null && s.finding) {
            s.stopFind("kullanıcı durdurdu")
            return
        }
        val ready = act.readyService() ?: return
        if (!ready.startFind()) act.toast("Saatte arama ekranı açık; şimdi titretilemez.")
    }

    override fun refresh() {
        val missing = act.firstMissingSetup()
        banner.view.visibility = if (missing == null) View.GONE else View.VISIBLE
        if (missing != null) banner.text.text = "Kurulum eksik: $missing"
        refreshHero()
        refreshBento()
        val active = WatchRegistry.active(act)
        val caps = WatchManager.caps(act)
        val band = active?.type == WatchType.BAND8
        val finding = WatchHub.ready && WatchHub.service?.finding == true
        findLabel.text = if (finding) "Durdur" else if (band) "Bandı bul" else "Saati bul"
        findTile.visibility = if (active != null && caps.findWatch) View.VISIBLE else View.GONE
        quickLabel.visibility = if (active != null) View.VISIBLE else View.GONE
        quickRow.visibility = quickLabel.visibility
        pull.setRefreshing(HealthUi.busy(act))
    }

    private fun refreshHero() {
        val w = WatchRegistry.active(act)
        val band = w?.type == WatchType.BAND8
        bandFace.visibility = if (w == null || band) View.VISIBLE else View.GONE
        roundFace.visibility = if (w != null && !band) View.VISIBLE else View.GONE
        if (w == null) {
            heroName.text = "Saat eklenmedi"
            setStatus("Ayarlar > Saatlerim ile ekle", R.color.text2)
            battRow.visibility = View.GONE
            battMeter.visibility = View.GONE
            syncText.visibility = View.GONE
            bandFace.set(null)
            return
        }
        heroName.text = w.name
        if (band) bandFace.set(activeBandFace()) else {
            val e = FaceLibrary.get(act, Prefs.currentFace(act))
            roundFace.set(e?.let { FaceLibrary.preview(it) }, false)
        }
        when {
            WatchHub.ready -> setStatus("Bağlı", R.color.accent_text)
            WatchHub.status.endsWith("…") -> setStatus(WatchHub.status, R.color.text2)
            else -> setStatus("Bağlı değil", R.color.warning)
        }
        val b = WatchHub.battery
        battRow.visibility = View.VISIBLE
        if (b != null) {
            battNum.text = "%$b"
            battUnit.text = "pil"
            battMeter.fraction = b / 100f
            battMeter.visibility = View.VISIBLE
        } else {
            battNum.text = "—"
            battUnit.text = "pil bilinmiyor"
            battMeter.visibility = View.GONE
        }
        val last = Prefs.lastSync(act)
        syncText.text = if (last == 0L) "Henüz eşitlenmedi" else "Son eşitleme ${Fmt.ago(last)}"
        syncText.visibility = View.VISIBLE
    }

    private fun setStatus(text: String, colorId: Int) {
        statusText.text = text
        statusText.setTextColor(ui.color(colorId))
        statusDot.background = ui.round(ui.color(if (colorId == R.color.accent_text) R.color.accent else colorId), 4)
    }

    /** Bantta etkin kadranın galerideki önizlemesi (yoksa null: çizim gösterilir). */
    private fun activeBandFace(): Bitmap? {
        val id = Band8Faces.faces?.firstOrNull { it.active }?.id
        if (id != facePreviewId) {
            facePreviewId = id
            facePreview = id?.let { Band8FaceStore.entry(act, it) }?.let { Band8FaceStore.preview(it) }
        }
        return facePreview
    }

    private fun refreshBento() {
        val now = LocalDateTime.now()
        val today = HealthUi.ute(now.toLocalDate())
        val view = HealthStore.view()

        val goal = SettingsStore.profileDraft(act).stepGoal
        val steps = view.stepsForDay(today)
        stepGoal.text = "/ ${Fmt.num(goal)}"
        if (steps == null) {
            stepNum.text = "—"
            ring.set(0f, "")
            ring.contentDescription = "Adım verisi yok"
        } else {
            stepNum.text = Fmt.num(steps)
            val pct = if (goal > 0) (steps.toLong() * 100L / goal).toInt() else 0
            ring.set(pct / 100f, "")
            ring.contentDescription = "Hedefin yüzde $pct kadarı"
        }

        val limit = now.plusMinutes(5)
        val hp = view.lastHeart(notAfter = limit)
        if (hp == null) {
            heartNum.text = "—"
            heartRange.text = "Ölçüm yok"
        } else {
            val sb = SpannableStringBuilder(hp.bpm.toString())
            val start = sb.length
            sb.append(" bpm")
            sb.setSpan(android.text.style.RelativeSizeSpan(0.46f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(ui.color(R.color.text2)), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            heartNum.text = sb
            val curve = HealthUi.heartCurve(view, now.toLocalDate(), limit)
            heartRange.text = if (curve.isEmpty()) "Ölçüm: ${Fmt.day(hp.at)}"
            else "Gün içi ${curve.minOf { it.second }}-${curve.maxOf { it.second }}"
        }

        val night = view.sleepFor(today)
        if (night == null || night.sleepMin <= 0) {
            sleepNum.text = "Veri yok"
            sleepNum.setTextColor(ui.color(R.color.text2))
            sleepBar.visibility = View.GONE
        } else {
            sleepNum.text = HealthUi.dur(night.sleepMin)
            sleepNum.setTextColor(ui.color(R.color.text))
            sleepBar.setTotals(night.deepMin, night.lightMin, night.remMin)
            sleepBar.contentDescription = "Derin ${HealthUi.dur(night.deepMin)}, hafif ${HealthUi.dur(night.lightMin)}"
            sleepBar.visibility = View.VISIBLE
        }

        faceName.text = currentFaceName()
    }

    private fun currentFaceName(): String {
        val w = WatchRegistry.active(act) ?: return "Saat yok"
        if (w.type == WatchType.BAND8) {
            val list = Band8Faces.faces ?: return "Liste okunmadı"
            return list.firstOrNull { it.active }?.name?.ifBlank { null } ?: "Adsız kadran"
        }
        return FaceLibrary.get(act, Prefs.currentFace(act))?.name ?: "Bilinmiyor"
    }
}

// =====================================================================
// 2. Sağlık
// =====================================================================

class HealthPage(act: MainActivity) : Page(act, "Sağlık") {

    private companion object {
        const val MAX_BACK_DAYS = 29L
        val MONTHS = arrayOf("Oca", "Şub", "Mar", "Nis", "May", "Haz", "Tem", "Ağu", "Eyl", "Eki", "Kas", "Ara")
        val DAYS = arrayOf("Pzt", "Sal", "Çar", "Per", "Cum", "Cmt", "Paz")
    }

    private val pull = PullRefreshLayout(act, root)
    override val view: View get() = pull

    private var day: LocalDate = LocalDate.now()
    private val dateText: TextView = ui.text("", 14f, R.color.text, bold = true)
    private val prev: ImageView
    private val next: ImageView

    // Uyku
    private val sleepRange: TextView = ui.text("", 12f, R.color.text2)
    private val sleepNum: TextView = ui.text("", 30f, R.color.text, bold = true)
    private val sleepBed: TextView = ui.text("", 13f, R.color.text2)
    private val timeline = SleepTimelineView(act)
    private val legend: LinearLayout = ui.horizontal()
    private val sleepExtra: TextView = ui.caption("")
    private val sleepEmpty: TextView = ui.caption("")

    // Nabız
    private val heartRange: TextView = ui.text("", 12f, R.color.text2)
    private val heartNum: TextView = ui.text("", 30f, R.color.heart, bold = true)
    private val heartWhen: TextView = ui.text("", 13f, R.color.text2)
    private val heartChart = HeartChartView(act)
    private val heartAxis: LinearLayout = ui.horizontal()
    private val heartEmpty: TextView = ui.caption("Bu gün için ölçüm yok.")

    // Adım
    private val stepGoal: TextView = ui.text("", 12f, R.color.text2)
    private val stepNum: TextView = ui.text("", 30f, R.color.text, bold = true)
    private val bars = BarsView(act)
    private val barsEmpty: TextView = ui.caption("Henüz veri yok")

    // Geçmiş: 7 veya 30 günlük adım, uyku ve nabız.
    private var span = 7
    private val stepTrend = BarsView(act)
    private val sleepTrend = BarsView(act)
    private val heartTrend = BarsView(act)
    private val stepAvg: TextView = ui.caption("")
    private val sleepAvg: TextView = ui.caption("")
    private val heartAvg: TextView = ui.caption("")

    init {
        pull.onRefresh = { HealthUi.request(act) }
        val nav = ui.horizontal()
        prev = navButton("‹", "Önceki gün") { move(-1) }
        next = navButton("›", "Sonraki gün") { move(1) }
        nav.addView(prev, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        ui.add(nav, dateText, width = Ui.WRAP)
        nav.addView(next, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        ui.add(body, ui.header("Sağlık", nav))

        // Uyku
        val sleep = ui.card(padDp = 16)
        ui.add(sleep, titleRow("Uyku", sleepRange))
        val sleepTop = ui.horizontal()
        sleepTop.gravity = Gravity.BOTTOM
        sleepNum.includeFontPadding = false
        ui.add(sleepTop, sleepNum, width = Ui.WRAP)
        ui.add(sleepTop, sleepBed, width = Ui.WRAP, leftDp = 8)
        ui.add(sleep, sleepTop, topDp = 12)
        ui.add(sleep, timeline, topDp = 12)
        ui.add(sleep, legend, topDp = 12)
        ui.add(sleep, sleepExtra, topDp = 8)
        ui.add(sleep, sleepEmpty, topDp = 8)
        ui.add(body, sleep, topDp = 12)

        // Nabız
        val heart = ui.card(padDp = 16)
        ui.add(heart, titleRow("Nabız", heartRange))
        val heartTop = ui.horizontal()
        heartTop.gravity = Gravity.BOTTOM
        heartNum.includeFontPadding = false
        ui.add(heartTop, heartNum, width = Ui.WRAP)
        ui.add(heartTop, heartWhen, width = Ui.WRAP, leftDp = 4)
        ui.add(heart, heartTop, topDp = 10)
        ui.add(heart, heartChart, topDp = 10, height = ui.dp(112))
        for ((i, h) in listOf("00", "06", "12", "18", "24").withIndex()) {
            if (i > 0) ui.add(heartAxis, ui.space(), width = 0, height = 1, weight = 1f)
            ui.add(heartAxis, ui.text(h, 11f, R.color.text2), width = Ui.WRAP)
        }
        ui.add(heart, heartAxis, topDp = 4)
        ui.add(heart, heartEmpty, topDp = 8)
        ui.add(body, heart, topDp = 12)

        // Adım
        val steps = ui.card(padDp = 16)
        ui.add(steps, titleRow("Adım", stepGoal))
        ui.add(steps, stepNum, topDp = 10)
        ui.add(steps, bars, topDp = 10, height = ui.dp(48))
        ui.add(steps, barsEmpty, topDp = 8)
        ui.add(body, steps, topDp = 12)

        // Geçmiş
        val trends = ui.card(padDp = 16)
        val head = ui.horizontal()
        ui.add(head, ui.text("Geçmiş", 13f, R.color.text2, bold = true), width = 0, weight = 1f)
        val seg = ui.segmented(listOf("7 gün", "30 gün"), 0) { i ->
            span = if (i == 0) 7 else 30
            refresh()
        }
        for (k in 0 until seg.view.childCount) {
            val t = seg.view.getChildAt(k) as? TextView ?: continue
            t.isSingleLine = true
            t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            t.setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(4))
            t.layoutParams = LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP)
        }
        ui.add(head, seg.view, width = Ui.WRAP, leftDp = 8)
        ui.add(trends, head)
        trendBlock(trends, R.drawable.ic_today, R.color.accent, "Adım", stepAvg, stepTrend, 16)
        trendBlock(trends, R.drawable.ic_moon, R.color.sleep_light, "Uyku", sleepAvg, sleepTrend, 20)
        trendBlock(trends, R.drawable.ic_heart, R.color.heart, "Nabız", heartAvg, heartTrend, 20)
        ui.add(body, trends, topDp = 12)
    }

    private fun navButton(sym: String, desc: String, onClick: () -> Unit): ImageView {
        // Ok karakteri yerine çizilen ok: ic_chevron (sağ) ve döndürülmüş hali (sol).
        val v = ui.iconButton(R.drawable.ic_chevron, desc, onClick)
        if (sym == "‹") v.rotation = 180f
        return v
    }

    private fun titleRow(title: String, right: TextView): LinearLayout {
        val r = ui.horizontal()
        ui.add(r, ui.text(title, 13f, R.color.text2, bold = true), width = 0, weight = 1f)
        ui.add(r, right, width = Ui.WRAP)
        return r
    }

    private fun trendBlock(card: LinearLayout, icon: Int, colorId: Int, title: String, avg: TextView, chart: BarsView, topDp: Int) {
        val row = ui.horizontal()
        ui.add(row, ui.iconTitle(icon, colorId, title), width = 0, weight = 1f)
        ui.add(row, avg, width = Ui.WRAP)
        ui.add(card, row, topDp = topDp)
        ui.add(card, chart, topDp = 8, height = ui.dp(72))
    }

    private fun move(delta: Long) {
        val today = LocalDate.now()
        val d = day.plusDays(delta)
        if (d.isAfter(today) || d.isBefore(today.minusDays(MAX_BACK_DAYS))) return
        day = d
        refresh()
    }

    private fun dateLabel(d: LocalDate, today: LocalDate): String {
        val base = "${d.dayOfMonth} ${MONTHS[d.monthValue - 1]}"
        return when (d) {
            today -> "Bugün, $base"
            today.minusDays(1) -> "Dün, $base"
            else -> "${DAYS[d.dayOfWeek.value - 1]}, $base"
        }
    }

    // Bellekte yalnızca son 3 gün var; daha eski günler arka planda dosyalardan okunur.
    private var olderView: HealthQuery? = null
    private var olderDate: LocalDate? = null
    private var olderLoading = false
    private var olderGen = -1

    private fun loadOlder(today: LocalDate) {
        if (olderLoading || (olderDate == today && olderView != null && olderGen == HealthStore.generation)) return
        olderLoading = true
        val gen = HealthStore.generation
        val read = HealthStore.reader(act, 31)
        Thread({
            val v = try { read() } catch (e: Exception) { null }
            act.runOnUiThread {
                olderLoading = false
                if (v != null) {
                    olderView = v
                    olderDate = today
                    olderGen = gen
                    refresh()
                }
            }
        }, "trend-read").start()
    }

    /** Seçilen günün verisi: son 3 gün bellekten, öncesi dosyalardan. Dosyalar okunmadıysa null. */
    private fun source(d: LocalDate, today: LocalDate, recent: HealthQuery): HealthQuery? =
        if (!d.isBefore(today.minusDays(2))) recent else olderView

    override fun refresh() {
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        if (day.isAfter(today)) day = today
        dateText.text = dateLabel(day, today)
        ui.enable(next, day.isBefore(today))
        ui.enable(prev, day.isAfter(today.minusDays(MAX_BACK_DAYS)))
        val recent = HealthStore.view()
        loadOlder(today)
        val v = source(day, today, recent)
        refreshSleep(v)
        refreshHeart(v, now)
        refreshSteps(v, now, recent)
        refreshTrends(recent, today)
        pull.setRefreshing(HealthUi.busy(act))
    }

    private fun refreshSleep(v: HealthQuery?) {
        val night = v?.sleepFor(HealthUi.ute(day))
        val has = night != null && night.sleepMin > 0
        for (x in listOf<View>(timeline, legend, sleepBed)) x.visibility = if (has) View.VISIBLE else View.GONE
        sleepEmpty.visibility = if (has) View.GONE else View.VISIBLE
        if (night == null || !has) {
            sleepNum.text = "—"
            sleepRange.text = ""
            sleepExtra.visibility = View.GONE
            sleepEmpty.text = when {
                v == null -> "Yükleniyor…"
                day == LocalDate.now() -> "Bu gecenin uykusu henüz okunmadı."
                else -> "Bu gece için uyku kaydı yok."
            }
            return
        }
        sleepRange.text = "${Fmt.hm(night.start.hour, night.start.minute)} - ${Fmt.hm(night.end.hour, night.end.minute)}"
        sleepNum.text = HealthUi.dur(night.sleepMin)
        val bed = java.time.Duration.between(night.start, night.end).toMinutes().toInt()
        sleepBed.text = if (bed > 0) "yatakta ${HealthUi.dur(bed)}" else ""
        val start = night.start.hour * 60 + night.start.minute
        timeline.set(v.sleepSegmentsFor(HealthUi.ute(day)), start, maxOf(bed, 1))
        timeline.contentDescription = "Uyku evreleri zaman çizelgesi"
        legend.removeAllViews()
        val items = listOfNotNull(
            Triple("Derin", R.color.sleep_deep, night.deepMin),
            Triple("Hafif", R.color.sleep_light, night.lightMin),
            if (night.remMin > 0) Triple("REM", R.color.sleep_rem, night.remMin) else null,
            Triple("Uyanık", R.color.sleep_awake, night.awakeMin),
        )
        items.forEachIndexed { i, (name, colorId, min) ->
            val col = ui.vertical()
            val top = ui.horizontal()
            top.addView(ui.dot(colorId), LinearLayout.LayoutParams(ui.dp(8), ui.dp(8)))
            ui.add(top, ui.text(name, 12f, R.color.text2), width = Ui.WRAP, leftDp = 6)
            ui.add(col, top)
            ui.add(col, ui.text(HealthUi.hm(min), 15f, R.color.text, bold = true), topDp = 2)
            col.contentDescription = "$name ${HealthUi.dur(min)}"
            ui.add(legend, col, width = 0, weight = 1f, leftDp = if (i == 0) 0 else 8)
        }
        // Band 8 gecesi: uyanma sayısı ve ortalama nabız. LS05 gecesinde görünmez.
        val extra = listOfNotNull(night.wakeUps?.let { "Uyanma $it" }, night.avgHeart?.let { "Ortalama nabız $it bpm" })
        sleepExtra.text = extra.joinToString(" · ")
        sleepExtra.visibility = if (extra.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun refreshHeart(v: HealthQuery?, now: LocalDateTime) {
        val isToday = day == now.toLocalDate()
        val limit = if (isToday) now.plusMinutes(5) else day.plusDays(1).atStartOfDay()
        val curve = if (v == null) emptyList() else HealthUi.heartCurve(v, day, limit)
        val has = curve.isNotEmpty()
        heartChart.visibility = if (has) View.VISIBLE else View.GONE
        heartAxis.visibility = heartChart.visibility
        heartEmpty.visibility = if (has) View.GONE else View.VISIBLE
        if (!has) {
            heartNum.text = "—"
            heartWhen.text = ""
            heartRange.text = ""
            return
        }
        heartChart.set(curve)
        val lo = curve.minOf { it.second }
        val hi = curve.maxOf { it.second }
        val avg = curve.map { it.second }.average().toInt()
        heartRange.text = "Gün içi $lo-$hi · ort. $avg"
        heartChart.contentDescription = "Gün içi nabız grafiği, $lo ile $hi arasında"
        val lastMin = curve.last().first
        heartNum.text = curve.last().second.toString()
        heartWhen.text = if (isToday) {
            val at = v!!.lastHeart(notAfter = limit)
            if (at != null) "bpm, ${Fmt.ago(java.time.ZonedDateTime.of(at.at, java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())}" else "bpm"
        } else "bpm, son ölçüm ${Fmt.hm(lastMin / 60, lastMin % 60)}"
    }

    private fun refreshSteps(v: HealthQuery?, now: LocalDateTime, recent: HealthQuery) {
        val d = HealthUi.ute(day)
        val goal = SettingsStore.profileDraft(act).stepGoal
        val steps = v?.stepsForDay(d)
        if (steps == null) {
            stepNum.text = "—"
            stepGoal.text = "Hedef ${Fmt.num(goal)}"
        } else {
            stepNum.text = Fmt.num(steps)
            val pct = if (goal > 0) (steps.toLong() * 100L / goal).toInt() else 0
            stepGoal.text = "Hedef ${Fmt.num(goal)} · %$pct"
        }
        // Saatlik adım: eşitleme yapıldıysa boş saat gerçek 0'dır; hiç eşitleme yoksa grafik çizilmez.
        val isToday = day == now.toLocalDate()
        val band8 = (recent as? MergedHealth)?.hasBand8Steps(d) == true
        if (v == null || steps == null && (isToday && Prefs.lastSync(act) == 0L && !band8)) {
            bars.visibility = View.GONE
            barsEmpty.visibility = View.VISIBLE
        } else {
            bars.set(v.hourlySteps(d), if (isToday) now.hour else 23)
            bars.contentDescription = "Saatlik adım çubukları"
            bars.visibility = View.VISIBLE
            barsEmpty.visibility = View.GONE
        }
    }

    /** Günlük geçmiş grafikleri. Son sütun bugün (uykuda bu sabah biten gece). Ortalamalar yalnızca verisi olan günlerden. */
    private fun refreshTrends(recent: HealthQuery, today: LocalDate) {
        val older = olderView
        fun src(d: LocalDate): HealthQuery = if (older != null && d.isBefore(today.minusDays(2))) older else recent
        val days = (span - 1 downTo 0).map { today.minusDays(it.toLong()) }
        val names = arrayOf("Pt", "Sa", "Ça", "Pe", "Cu", "Ct", "Pz")
        val labels = Array<String?>(span) { i ->
            val d = days[i]
            when {
                span == 7 -> names[d.dayOfWeek.value - 1]
                (span - 1 - i) % 7 == 0 -> d.dayOfMonth.toString()
                else -> null
            }
        }
        val steps = IntArray(span) { src(days[it]).stepsForDay(HealthUi.ute(days[it])) ?: 0 }
        stepTrend.setDaily(steps, span - 1, labels)
        val stepDays = steps.filter { it > 0 }
        stepAvg.text = if (stepDays.isEmpty()) "Veri yok" else "Ort. ${Fmt.num(stepDays.average().toInt())}"

        val sleep = IntArray(span) { src(days[it]).sleepFor(HealthUi.ute(days[it]))?.sleepMin ?: 0 }
        sleepTrend.setDaily(sleep, span - 1, labels)
        val sleepDays = sleep.filter { it > 0 }
        sleepAvg.text = if (sleepDays.isEmpty()) "Veri yok" else "Ort. ${HealthUi.dur(sleepDays.average().toInt())}"

        val heart = IntArray(span) { i ->
            val d = days[i]
            src(d).heartPoints().filter { it.at.toLocalDate() == d && it.bpm in HealthLog.VALID_BPM }
                .map { it.bpm }.takeIf { it.isNotEmpty() }?.average()?.toInt() ?: 0
        }
        heartTrend.setDaily(heart, span - 1, labels)
        val heartDays = heart.filter { it > 0 }
        heartAvg.text = if (heartDays.isEmpty()) "Veri yok" else "Ort. ${heartDays.average().toInt()} bpm"

        stepTrend.contentDescription = "Günlük adım, son $span gün"
        sleepTrend.contentDescription = "Gecelik uyku, son $span gün"
        heartTrend.contentDescription = "Günlük ortalama nabız, son $span gün"
    }
}
