package com.che.ls05

import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.che.ls05.protocol.AlarmReminder
import com.che.ls05.protocol.AlarmSetting
import com.che.ls05.protocol.DndSetting
import com.che.ls05.protocol.HealthLog
import com.che.ls05.protocol.HealthQuery
import com.che.ls05.protocol.MergedHealth
import com.che.ls05.protocol.NotificationState
import com.che.ls05.protocol.ProfileSetting
import com.che.ls05.protocol.SyncEntry
import com.che.ls05.protocol.SyncPhase
import com.che.ls05.protocol.UteAlarm
import com.che.ls05.protocol.UteCommand
import com.che.ls05.protocol.UteDate
import com.che.ls05.protocol.UteFrame
import com.che.ls05.protocol.UteProfile
import com.che.ls05.protocol.SecretEnvelope
import com.che.ls05.protocol.WatchType
import java.time.LocalDateTime

/**
 * Bir sekme: kaydırılabilir dikey gövde. Görünümler bir kez kurulur, refresh yalnızca günceller.
 * Ekranlar yalnızca Ui.kt bileşenleriyle kurulur (şartname bölüm 5); kart arası 10 dp, yatay boşluk 16 dp.
 */
abstract class Page(val act: MainActivity, val title: String) {
    protected val ui: Ui = act.ui
    val body: LinearLayout = ui.vertical()
    val root: ScrollView = ui.scroll(body)

    /** Ekrana eklenen görünüm; bir sayfa kaydırma görünümünü sarabilir (ör. aşağı çekip yenileme). */
    open val view: View get() = root

    init {
        body.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(16))
    }

    abstract fun refresh()
}

// =====================================================================
// 1. Bugün
// =====================================================================

class TodayPage(act: MainActivity) : Page(act, "Bugün") {

    private fun dur(min: Int): String = if (min >= 60) "${min / 60} sa ${min % 60} dk" else "$min dk"

    private fun durSpan(min: Int): CharSequence =
        if (min >= 60) ui.valueSpan("${min / 60}", " sa ", "${min % 60}", " dk") else ui.valueSpan("$min", " dk")

    private val banner: Banner = ui.banner("", "Ayarlar") { act.selectTab(3) }

    private val ring = RingView(act)
    private val stepNum: TextView = ui.big("")
    private val stepSub: TextView = ui.caption("")

    private val bars = BarsView(act)
    private val barsEmpty: TextView = ui.caption("Henüz veri yok")

    private val heartNum: TextView = ui.big("")
    private val spark = SparkView(act)
    private val heartTime: TextView = ui.caption("")
    private val heartRange: TextView = ui.caption("")

    private val sleepNum: TextView = ui.big("")
    private val sleepStrip = SleepStripView(act)
    private val sleepSpan: TextView = ui.caption("")
    private val legend: LinearLayout = ui.card(padDp = 16, padVDp = 12)
    private val deepText: TextView = ui.caption("")
    private val lightText: TextView = ui.caption("")
    private val awakeText: TextView = ui.caption("")
    private val remText: TextView = ui.caption("")
    private val remRow: LinearLayout = ui.horizontal()
    private val sleepExtra: TextView = ui.caption("")

    // Son eşitleme: sayfanın en üstünde küçük açıklama. Yenileme aşağı çekerek yapılır.
    private val syncText: TextView = ui.caption("")
    private val pull = PullRefreshLayout(act, root)
    override val view: View get() = pull

    // Geçmiş: 7 veya 30 günlük adım, uyku ve nabız.
    private var span = 7
    private val stepTrend = BarsView(act)
    private val sleepTrend = BarsView(act)
    private val heartTrend = BarsView(act)
    private val stepAvg: TextView = ui.caption("")
    private val sleepAvg: TextView = ui.caption("")
    private val heartAvg: TextView = ui.caption("")

    init {
        syncText.setPadding(ui.dp(4), 0, ui.dp(4), 0)
        ui.add(body, syncText, topDp = 0)
        ui.add(body, banner.view, topDp = 8)
        pull.onRefresh = { requestData() }

        // Büyük adım kartı: halka + toplam + hedef.
        val steps = ui.card(big = true, padDp = 20)
        val stepRow = ui.horizontal()
        stepRow.addView(ring, LinearLayout.LayoutParams(ui.dp(96), ui.dp(96)))
        val stepCol = ui.vertical()
        ui.add(stepCol, ui.cardTitle("Adım"))
        ui.add(stepCol, stepNum, topDp = 6)
        ui.add(stepCol, stepSub, topDp = 6)
        ui.add(stepRow, stepCol, width = 0, weight = 1f, leftDp = 20)
        ui.add(steps, stepRow)
        ui.add(body, steps, topDp = 10)

        // Saatlik adım sütunları.
        val hourly = ui.card()
        ui.add(hourly, ui.cardTitle("Saatlik adım"))
        ui.add(hourly, bars, topDp = 12, height = ui.dp(104))
        ui.add(hourly, barsEmpty, topDp = 8)
        ui.add(body, hourly, topDp = 10)

        // Nabız ve uyku yan yana, eşit yükseklikte.
        val pair = LinearLayout(act)
        pair.orientation = LinearLayout.HORIZONTAL

        val heart = ui.card()
        ui.add(heart, ui.iconTitle(R.drawable.ic_heart, R.color.heart, "Nabız"))
        ui.add(heart, heartNum, topDp = 10)
        ui.add(heart, spark, topDp = 10, height = ui.dp(28))
        ui.add(heart, heartTime, topDp = 8)
        ui.add(heart, heartRange, topDp = 2)
        ui.add(pair, heart, width = 0, height = Ui.MATCH, weight = 1f)

        val sleep = ui.card()
        ui.add(sleep, ui.iconTitle(R.drawable.ic_moon, R.color.sleep_light, "Uyku"))
        ui.add(sleep, sleepNum, topDp = 10)
        ui.add(sleep, sleepStrip, topDp = 18, height = ui.dp(12))
        ui.add(sleep, sleepSpan, topDp = 16)
        ui.add(pair, sleep, width = 0, height = Ui.MATCH, weight = 1f, leftDp = 10)
        ui.add(body, pair, topDp = 10)

        // Evre açıklaması: uyku kartının hemen altında.
        val legendRow = ui.horizontal()
        legendItem(legendRow, R.color.sleep_deep, deepText, 0)
        legendItem(legendRow, R.color.sleep_light, lightText, 12)
        ui.add(legend, legendRow)
        // Band 8: REM ayrı renkte; LS05 gecesinde gizli.
        legendItem(remRow, R.color.sleep_rem, remText, 0)
        ui.add(remRow, View(act), width = 0, weight = 1f, leftDp = 12)
        remRow.visibility = View.GONE
        ui.add(legend, remRow, topDp = 4)
        ui.add(legend, awakeText, topDp = 4)
        sleepExtra.visibility = View.GONE
        ui.add(legend, sleepExtra, topDp = 4)
        ui.add(body, legend, topDp = 10)

        val trends = ui.card()
        val head = ui.horizontal()
        ui.add(head, ui.cardTitle("Geçmiş"), width = 0, weight = 1f)
        val seg = ui.segmented(listOf("7 gün", "30 gün"), 0) { i ->
            span = if (i == 0) 7 else 30
            refresh()
        }
        // "30 gün" iki satıra bölünmesin: parçalar tek satır ve içerik genişliğinde.
        for (k in 0 until seg.view.childCount) {
            val t = seg.view.getChildAt(k) as? TextView ?: continue
            t.isSingleLine = true
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            t.setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(4))
            t.layoutParams = LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP)
        }
        ui.add(head, seg.view, width = Ui.WRAP, leftDp = 8)
        ui.add(trends, head)
        trendBlock(trends, R.drawable.ic_today, R.color.accent, "Adım", stepAvg, stepTrend, 16)
        trendBlock(trends, R.drawable.ic_moon, R.color.sleep_light, "Uyku", sleepAvg, sleepTrend, 20)
        trendBlock(trends, R.drawable.ic_heart, R.color.heart, "Nabız", heartAvg, heartTrend, 20)
        ui.add(body, trends, topDp = 10)

        ui.add(body, ui.secondary("Verileri dışa aktar") { askExport() }, topDp = 10)
    }

    private fun trendBlock(card: LinearLayout, icon: Int, colorId: Int, title: String, avg: TextView, chart: BarsView, topDp: Int) {
        val row = ui.horizontal()
        ui.add(row, ui.iconTitle(icon, colorId, title), width = 0, weight = 1f)
        ui.add(row, avg, width = Ui.WRAP)
        ui.add(card, row, topDp = topDp)
        ui.add(card, chart, topDp = 8, height = ui.dp(72))
    }

    // Bellekte yalnızca son 3 gün var; daha eski günler arka planda dosyalardan okunur ve günde bir yenilenir.
    private var olderView: HealthQuery? = null
    private var olderHeartByDay: Map<java.time.LocalDate, List<com.che.ls05.protocol.HeartPoint>>? = null
    private var olderDate: java.time.LocalDate? = null
    private var olderLoading = false
    private var olderGen = -1

    private fun loadOlder(today: java.time.LocalDate) {
        if (olderLoading || (olderDate == today && olderView != null && olderGen == HealthStore.generation)) return
        olderLoading = true
        val gen = HealthStore.generation
        val read = HealthStore.reader(act, 31)
        Thread({
            val v = try { read() } catch (e: Exception) { null }
            val hb = v?.heartPoints()?.filter { it.bpm in HealthLog.VALID_BPM }?.groupBy { it.at.toLocalDate() }
            act.runOnUiThread {
                olderLoading = false
                if (v != null) {
                    olderView = v
                    olderHeartByDay = hb
                    olderDate = today
                    olderGen = gen
                    refresh()
                }
            }
        }, "trend-read").start()
    }

    /** Günlük geçmiş grafikleri. Son sütun bugün (uykuda bu sabah biten gece). Ortalamalar yalnızca verisi olan günlerden. */
    private fun refreshTrends(recent: HealthQuery, today: java.time.LocalDate) {
        loadOlder(today)
        val older = olderView
        // Son 3 gün bellekteki güncel veriden, öncesi dosyalardan.
        fun src(d: java.time.LocalDate): HealthQuery = if (older != null && d.isBefore(today.minusDays(2))) older else recent
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
        fun ute(d: java.time.LocalDate) = UteDate(d.year, d.monthValue, d.dayOfMonth)

        val steps = IntArray(span) { src(days[it]).stepsForDay(ute(days[it])) ?: 0 }
        stepTrend.setDaily(steps, span - 1, labels)
        val stepDays = steps.filter { it > 0 }
        stepAvg.text = if (stepDays.isEmpty()) "Veri yok" else "Ort. ${Fmt.num(stepDays.average().toInt())}"

        val sleep = IntArray(span) { src(days[it]).sleepFor(ute(days[it]))?.sleepMin ?: 0 }
        sleepTrend.setDaily(sleep, span - 1, labels)
        val sleepDays = sleep.filter { it > 0 }
        sleepAvg.text = if (sleepDays.isEmpty()) "Veri yok" else "Ort. ${dur(sleepDays.average().toInt())}"

        val recentHeart = recent.heartPoints().filter { it.bpm in HealthLog.VALID_BPM }.groupBy { it.at.toLocalDate() }
        val olderHeart = olderHeartByDay
        val byDay = days.associateWith { d -> (if (olderHeart != null && d.isBefore(today.minusDays(2))) olderHeart[d] else recentHeart[d]) }
        val heart = IntArray(span) { i -> byDay[days[i]]?.map { it.bpm }?.average()?.toInt() ?: 0 }
        heartTrend.setDaily(heart, span - 1, labels)
        val heartDays = heart.filter { it > 0 }
        heartAvg.text = if (heartDays.isEmpty()) "Veri yok" else "Ort. ${heartDays.average().toInt()} bpm"

        stepTrend.contentDescription = "Günlük adım, son $span gün"
        sleepTrend.contentDescription = "Gecelik uyku, son $span gün"
        heartTrend.contentDescription = "Günlük ortalama nabız, son $span gün"
    }

    private fun legendItem(row: LinearLayout, colorId: Int, label: TextView, leftDp: Int) {
        val item = ui.horizontal()
        val lp = LinearLayout.LayoutParams(ui.dp(10), ui.dp(10))
        lp.rightMargin = ui.dp(8)
        item.addView(ui.dot(colorId), lp)
        ui.add(item, label, width = 0, weight = 1f)
        ui.add(row, item, width = 0, weight = 1f, leftDp = leftDp)
    }

    /** Adım, nabız ve uyku CSV olarak tek bir zip'e; önce aralık sorulur. */
    private fun askExport() {
        val ranges = HealthExport.RANGES
        val dialog = AlertDialog.Builder(act)
            .setTitle("Verileri dışa aktar")
            .setItems(ranges.map { it.first }.toTypedArray()) { d, which ->
                d.dismiss()
                HealthExport.start(act, ranges[which].second)
            }
            .setNegativeButton("Vazgeç", null)
            .create()
        act.track(dialog)
        dialog.show()
    }

    /**
     * Aşağı çekip bırakınca çağrılır. Okuma başladıysa ya da zaten sürüyorsa true (gösterge döner).
     * Saat bağlı değilse ya da kadran yükleniyorsa readyService kısa uyarı gösterir.
     */
    private fun requestData(): Boolean {
        if (WatchRegistry.active(act)?.type == WatchType.BAND8) {
            // Band 8: sağlık dosyalarını şimdi çek (yalnızca okuma). Bitince ekran kendiliğinden yenilenir.
            val s = act.readyService() ?: return false
            val err = s.bandProbe(Band8Probe.FETCH_NOW)
            act.toast(err ?: "Band 8 sağlık dosyaları çekiliyor.")
            return false
        }
        val s = act.readyService() ?: return false
        if (s.requestHistory(true)) return true
        if (s.historyBusy) return true
        act.toast("Veriler az önce okundu.")
        return false
    }

    /** Okuma sürerken (elle ya da bağlanınca otomatik) gösterge döner; bitince ya da kopunca gizlenir. */
    private fun refreshPullState() {
        pull.setRefreshing(WatchRegistry.active(act)?.type != WatchType.BAND8 && WatchHub.service?.historyBusy == true)
    }

    override fun refresh() {
        val missing = act.firstMissingSetup()
        if (missing == null) {
            banner.view.visibility = View.GONE
        } else {
            banner.text.text = "Kurulum eksik: $missing"
            banner.view.visibility = View.VISIBLE
        }

        val now = LocalDateTime.now()
        val today = UteDate(now.year, now.monthValue, now.dayOfMonth)
        val view = HealthStore.view()
        val last = Prefs.lastSync(act)

        // Adım
        val goal = SettingsStore.profileDraft(act).stepGoal
        val steps = view.stepsForDay(today)
        stepSub.text = "Hedef ${Fmt.num(goal)}"
        if (steps == null) {
            ui.setBig(stepNum, null, "Veri yok")
            ring.set(0f, "")
            ring.contentDescription = "Adım verisi yok"
        } else {
            ui.setBig(stepNum, Fmt.num(steps), "")
            val pct = if (goal > 0) (steps.toLong() * 100L / goal).toInt() else 0
            ring.set(pct / 100f, "%$pct")
            ring.contentDescription = "Hedefin yüzde $pct kadarı"
        }

        // Saatlik adım: eşitleme yapıldıysa boş saat gerçek 0'dır; hiç eşitleme yoksa grafik çizilmez.
        val band8Today = (view as? MergedHealth)?.hasBand8Steps(today) == true
        if (last == 0L && !band8Today) {
            bars.visibility = View.GONE
            barsEmpty.visibility = View.VISIBLE
        } else {
            bars.set(view.hourlySteps(today), now.hour)
            bars.contentDescription = "Bugünün saatlik adım grafiği"
            bars.visibility = View.VISIBLE
            barsEmpty.visibility = View.GONE
        }

        // Nabız
        val limit = now.plusMinutes(5)
        val hp = view.lastHeart(notAfter = limit)
        if (hp == null) {
            ui.setBig(heartNum, null, "Ölçüm yok")
            heartTime.visibility = View.GONE
        } else {
            ui.setBig(heartNum, ui.valueSpan(hp.bpm.toString(), " bpm"), "")
            heartTime.text = "Ölçüm: ${Fmt.day(hp.at)}"
            heartTime.visibility = View.VISIBLE
        }
        val day = now.toLocalDate()
        val curve = view.heartPoints()
            .filter { it.at.toLocalDate() == day && !it.at.isAfter(limit) }
            .map { (it.at.hour * 60 + it.at.minute) to it.bpm }
        if (curve.size >= 2) {
            spark.set(curve)
            spark.visibility = View.VISIBLE
        } else {
            spark.visibility = View.GONE
        }

        val sum = view.heartSummary
        val valid = HealthLog.VALID_BPM
        if (sum != null && sum.date == today && sum.min in valid && sum.avg in valid && sum.max in valid) {
            heartRange.text = "Gün içi ${sum.min}-${sum.max}\nOrtalama ${sum.avg}"
            heartRange.visibility = View.VISIBLE
        } else {
            heartRange.visibility = View.GONE
        }

        // Uyku
        val night = view.sleepFor(today)
        if (night == null || night.sleepMin <= 0) {
            ui.setBig(sleepNum, null, "Veri yok")
            sleepSpan.text = "Bu gecenin uykusu henüz okunmadı."
            sleepStrip.visibility = View.GONE
            legend.visibility = View.GONE
        } else {
            ui.setBig(sleepNum, durSpan(night.sleepMin), "")
            sleepStrip.set(view.sleepSegmentsFor(today))
            sleepStrip.contentDescription = "Uyku evreleri şeridi"
            sleepStrip.visibility = View.VISIBLE
            sleepSpan.text = "${Fmt.hm(night.start.hour, night.start.minute)} → ${Fmt.hm(night.end.hour, night.end.minute)}"
            deepText.text = "Derin ${dur(night.deepMin)}"
            lightText.text = "Hafif ${dur(night.lightMin)}"
            if (night.awakeMin > 0) {
                awakeText.text = "Uyanık ${dur(night.awakeMin)} (şeritte gri)"
                awakeText.visibility = View.VISIBLE
            } else {
                awakeText.visibility = View.GONE
            }
            // Band 8 gecesi: REM, uyanma sayısı ve ortalama nabız. LS05 gecesinde bu satırlar görünmez.
            if (night.remMin > 0) {
                remText.text = "REM ${dur(night.remMin)}"
                remRow.visibility = View.VISIBLE
            } else {
                remRow.visibility = View.GONE
            }
            val extra = listOfNotNull(
                night.wakeUps?.let { "Uyanma $it" },
                night.avgHeart?.let { "Ortalama nabız $it bpm" },
            )
            sleepExtra.text = extra.joinToString(" · ")
            sleepExtra.visibility = if (extra.isEmpty()) View.GONE else View.VISIBLE
            legend.visibility = View.VISIBLE
        }

        refreshTrends(view, now.toLocalDate())

        syncText.text = if (last == 0L) "Son eşitleme: henüz yok" else "Son eşitleme: ${Fmt.dayMs(last)}"
        refreshPullState()
    }
}

// =====================================================================
// 2. Saat
// =====================================================================

class WatchPage(act: MainActivity) : Page(act, "Saat") {

    private class SettingViews(
        val main: TextView,
        val sub: TextView,
        val toggle: Toggle,
        val status: StatusLine,
        val retry: Button,
        val card: View? = null,
    )

    private val alarms = ArrayList<SettingViews>()
    private val dnd: SettingViews

    // Etkin saatin desteklemediği bölümler gizlenir (Capabilities). LS05'te hepsi görünür.
    private val alarmViews = ArrayList<View>()
    private val band8Alarms = Band8AlarmsCard(act)
    private val band8Idle = Band8IdleCard(act)
    private var alarmSection: View? = null
    private val faceViews = ArrayList<View>()
    private val profileViews = ArrayList<View>()
    private var dndView: View? = null
    private var bandDndRow: View? = null
    private var lastDndNowAt = 0L

    private fun bandDndNow(on: Boolean) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastDndNowAt < 2_000L) return
        lastDndNowAt = now
        val s = act.readyService() ?: return
        val err = s.bandProbe(if (on) Band8Probe.DND_ON else Band8Probe.DND_OFF)
        act.toast(err ?: if (on) "Rahatsız etme açılıyor." else "Rahatsız etme kapatılıyor.")
    }
    private var findView: View? = null

    // Saatlerim: kayıtlı saatler ve etkin saat.
    private val watchList = WatchesCard(act)

    // Alarm ekle: yalnızca kurulmuş yuvalar görünür (v0.11).
    private val addAlarm: Button = ui.secondary("Alarm ekle") { addAlarm() }
    private val noAlarm: TextView = ui.caption("Henüz alarm yok. Saatte en çok 3 alarm kurulabilir.")

    // Saati bul
    private val findCaption: TextView = ui.caption("")
    private val findButton: Button = ui.tonal("Titret") { toggleFind() }

    // Kadranlarım: en çok 4 döşeme; tümü galeride.
    private val faceRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
    private val faceStatus: TextView = ui.caption("")
    private val faceThumbs = ArrayList<Pair<String, FaceThumbView>>()
    private var gallery: FaceGallery? = null

    // Alarm notu var ama tam zamanlı alarm izni yoksa uyarı.
    private val exactBanner: Banner = ui.banner(
        "Tam zamanlı alarm izni yok. Alarm notu gecikebilir veya gönderilemeyebilir.",
        "İzin ver",
    ) { requestExactAlarm() }

    // Ekran ve profil (05)
    private val screenRow: NavRow = ui.navRow(null, "Ekran açık kalma süresi", null, "") { editScreenTime() }
    private val wristRow: SwitchRow = ui.switchRow("Bileği kaldırınca aç", null, ProfileSetting.DEFAULT.raiseWrist) { on ->
        SettingsStore.setProfileDraft(act, SettingsStore.profileDraft(act).copy(raiseWrist = on))
    }
    private val goalRow: NavRow = ui.navRow(null, "Adım hedefi", null, "") { editGoal() }
    private val profileRow: NavRow = ui.navRow(null, "Profil", "", "Düzenle") { editProfile() }
    private val profileStatus: StatusLine = ui.statusLine()
    private val profileSend: Button = ui.tonal("Saate gönder") { sendProfileFirstTime() }
    private val profileRetry: Button = ui.tonal("Yeniden gönder") { SettingsStore.retry(act, ProfileSetting.KEY) }

    init {
        // Alarmlar: saatte üç yuva; yalnızca kurulmuş olanlar görünür. Yoğunluk: saat 28 sp, kart içi 14 dp, kart arası 10 dp.
        ui.add(body, watchList.view)
        val alarmSec = ui.section("Alarmlar")
        alarmSection = alarmSec
        ui.add(body, alarmSec, topDp = 20)
        // Band 8: bandın kendi alarm listesi (LS05 yuvaları yerine).
        band8Alarms.view.visibility = View.GONE
        ui.add(body, band8Alarms.view)
        for (slot in 1..UteAlarm.SLOTS) {
            val key = AlarmSetting.key(slot)
            alarms += alarmCard(
                top = if (slot == 1) 8 else 10,
                onEdit = { AlarmSheet(act, slot, isNew = false).show() },
                onToggle = { on -> toggleAlarm(slot, on) },
                onRetry = { SettingsStore.retry(act, key) },
            )
        }
        noAlarm.setPadding(ui.dp(4), 0, ui.dp(4), 0)
        ui.add(body, noAlarm, topDp = 8)
        ui.add(body, addAlarm, topDp = 10, width = Ui.WRAP)
        exactBanner.view.visibility = View.GONE
        ui.add(body, exactBanner.view, topDp = 10)

        // Hatırlatıcılar ve takvim: iki saat için ortak, telefondan gönderilir.
        val reminders = ui.card(padDp = 16, padVDp = 6)
        ui.add(reminders, ui.row("Hatırlatıcılar ve takvim", "Özel hatırlatıcılar ve telefon takvimi saate bildirim olarak gelir") {
            RemindersScreen(act).show()
        })
        ui.add(body, reminders, topDp = 10)
        band8Idle.view.visibility = View.GONE
        ui.add(body, band8Idle.view)

        // Rahatsız etme ve saati bul yan yana, eşit yükseklikte.
        val pair = LinearLayout(act)
        pair.orientation = LinearLayout.HORIZONTAL

        val dndCard = ui.card({ editDnd() }, padDp = 14)
        val dndTop = ui.horizontal()
        ui.icon(R.drawable.ic_moon, R.color.text2)?.let { dndTop.addView(it, LinearLayout.LayoutParams(ui.dp(24), ui.dp(24))) }
        ui.add(dndTop, ui.space(), width = 0, height = ui.dp(1), weight = 1f)
        val dndToggle = ui.toggle(false) { on -> toggleDnd(on) }
        ui.add(dndTop, dndToggle.sw, width = Ui.WRAP)
        ui.add(dndCard, dndTop)
        ui.add(dndCard, ui.cardTitle("Rahatsız etme"), topDp = 6)
        val dndMain = ui.text("", 18f, R.color.text, bold = true)
        ui.add(dndCard, dndMain, topDp = 2)
        val dndStatus = ui.statusLine()
        ui.add(dndCard, dndStatus.view, topDp = 6)
        val dndRetry = ui.tonal("Yeniden gönder") { SettingsStore.retry(act, DndSetting.KEY) }
        dndRetry.visibility = View.GONE
        ui.add(dndCard, dndRetry, topDp = 8)
        // Band 8: rahatsız etmeyi hemen aç ya da kapat (2/23). Saat aralığı kartın kendisiyle (17/9) ayarlanır.
        val dndNow = ui.horizontal()
        ui.add(dndNow, ui.tonal("Şimdi aç") { bandDndNow(true) }, width = 0, weight = 1f)
        ui.add(dndNow, ui.tonal("Kapat") { bandDndNow(false) }, width = 0, weight = 1f, leftDp = 6)
        dndNow.visibility = View.GONE
        ui.add(dndCard, dndNow, topDp = 8)
        bandDndRow = dndNow
        dnd = SettingViews(dndMain, ui.caption(""), dndToggle, dndStatus, dndRetry)
        ui.add(pair, dndCard, width = 0, height = Ui.MATCH, weight = 1f)
        dndView = dndCard

        val find = ui.card(padDp = 14)
        val findTop = ui.horizontal()
        findTop.minimumHeight = ui.dp(48)
        ui.icon(R.drawable.ic_vibrate, R.color.text2)?.let { findTop.addView(it, LinearLayout.LayoutParams(ui.dp(24), ui.dp(24))) }
        ui.add(find, findTop)
        ui.add(find, ui.cardTitle("Saati bul"), topDp = 6)
        ui.add(find, findCaption, topDp = 2)
        ui.add(find, ui.space(), height = 0, weight = 1f)
        ui.add(find, findButton, topDp = 8)
        ui.add(pair, find, width = 0, height = Ui.MATCH, weight = 1f, leftDp = 10)
        findView = find
        ui.add(body, pair, topDp = 10)

        // Kadranlarım: saatteki önce, sonra en son eklenen iki kadran, sonda Tümü (ya da Ekle).
        val facesSec = ui.section("Kadranlarım")
        ui.add(body, facesSec, topDp = 20)
        val faces = ui.card(padDp = 0, padVDp = 14)
        faceViews += facesSec
        faceViews += faces
        faceRow.setPadding(ui.dp(8), 0, ui.dp(8), 0)
        ui.add(faces, faceRow)
        faceStatus.visibility = View.GONE
        faceStatus.setPadding(ui.dp(16), 0, ui.dp(16), 0)
        ui.add(faces, faceStatus, topDp = 10)
        ui.add(body, faces, topDp = 8)

        // Ekran ve profil
        val dispSec = ui.section("Ekran ve profil")
        ui.add(body, dispSec, topDp = 20)
        val disp = ui.card(padDp = 16, padVDp = 6)
        profileViews += dispSec
        profileViews += disp
        ui.add(disp, screenRow.view)
        ui.add(disp, ui.divider(), height = ui.dp(1))
        ui.add(disp, wristRow.view)
        ui.add(disp, ui.divider(), height = ui.dp(1))
        ui.add(disp, goalRow.view)
        ui.add(disp, ui.divider(), height = ui.dp(1))
        ui.add(disp, profileRow.view)
        ui.add(disp, profileStatus.view, topDp = 4)
        ui.add(disp, profileSend, topDp = 10, width = Ui.WRAP)
        profileRetry.visibility = View.GONE
        ui.add(disp, profileRetry, topDp = 10, width = Ui.WRAP)
        ui.add(disp, ui.space(), height = ui.dp(10))
        ui.add(body, disp, topDp = 8)
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
        ui.add(body, card, topDp = top)
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

    override fun refresh() {
        watchList.refresh()
        val caps = WatchManager.caps(act)
        val slots = minOf(caps.alarmSlots, UteAlarm.SLOTS)
        fun show(v: View?, on: Boolean) { v?.visibility = if (on) View.VISIBLE else View.GONE }
        alarmViews.forEach { show(it, slots > 0) }
        val band = WatchRegistry.active(act)?.type == WatchType.BAND8
        show(alarmSection, slots > 0 || band)
        show(band8Alarms.view, band)
        show(band8Idle.view, band)
        if (band) {
            band8Alarms.refresh()
            band8Idle.refresh()
        }
        faceViews.forEach { show(it, caps.faces) }
        profileViews.forEach { show(it, caps.profile) }
        show(dndView, caps.dnd)
        show(bandDndRow, WatchRegistry.active(act)?.type == WatchType.BAND8)
        show(findView, caps.findWatch)
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
        val e = SettingsStore.entry(act, DndSetting.KEY)
        val d = DndSetting.decode(e.desired) ?: DndSetting.DEFAULT
        dnd.main.text = "${Fmt.hm(d.startHour, d.startMinute)} → ${Fmt.hm(d.endHour, d.endMinute)}"
        dnd.main.setTextColor(ui.color(if (d.enabled) R.color.text else R.color.muted))
        dnd.toggle.set(d.enabled)
        dnd.toggle.sw.contentDescription = "Rahatsız etme"
        showStatus(dnd, e)
        noAlarm.visibility = if (visible.isEmpty() && slots > 0) View.VISIBLE else View.GONE
        addAlarm.visibility = if (visible.size < slots) View.VISIBLE else View.GONE
        exactBanner.view.visibility =
            if (Reminders.anyActive(act) && !Reminders.canExact(act)) View.VISIBLE else View.GONE
        refreshFind()
        refreshProfile()
        refreshFaces()
    }

    private fun refreshFind() {
        val finding = WatchHub.ready && WatchHub.service?.finding == true
        findButton.text = if (finding) "Durdur" else "Titret"
        findCaption.text = if (finding) "Saat titriyor. En çok 30 sn sürer." else "Saat 30 sn boyunca titrer."
    }

    // ---------- Kadranlarım ----------

    /** Saatteki kadran: uygulamanın kaydı (en son yüklenen). Galeride yoksa boş. Otomatik sorgu kullanılmaz. */
    private fun currentFace(all: List<FaceLibrary.Entry>): String {
        val id = Prefs.currentFace(act)
        return if (all.any { it.id == id }) id else ""
    }

    private fun faceOnWatch(): String = currentFace(FaceLibrary.list(act))

    /**
     * Galeriyi yeniden kurar (ekleme, silme, yükleme sonrası). En çok 4 döşeme: saatteki kadran, en son eklenen
     * iki kadran, sonda "Tümü (N)". 3'ten az kadran varsa hepsi görünür ve sonda "Ekle" durur.
     */
    fun refreshFaces() {
        faceRow.removeAllViews()
        faceThumbs.clear()
        val all = FaceLibrary.list(act) // eski → yeni
        val current = currentFace(all)
        val shown = ArrayList<FaceLibrary.Entry>()
        all.firstOrNull { it.id == current }?.let { shown += it }
        shown += all.filter { it.id != current }.takeLast(2).reversed()
        for (e in shown) {
            val th = FaceThumbView(act)
            th.set(FaceLibrary.preview(e), e.id == current)
            faceThumbs += e.id to th
            val label = if (e.id == current) "${e.name}\nSaatte" else e.name
            ui.add(faceRow, FaceTiles.tile(ui, label, th, 64) { openFace(e) }, width = 0, weight = 1f)
        }
        if (all.size < 3) {
            val add = FaceThumbView(act)
            add.set(null, false)
            ui.add(faceRow, FaceTiles.tile(ui, "Ekle", add, 64) { act.pickFace() }, width = 0, weight = 1f)
        } else {
            ui.add(faceRow, FaceTiles.tile(ui, "Tümü (${all.size})", FaceTiles.countCircle(ui, all.size), 64) { openGallery() }, width = 0, weight = 1f)
        }
        // Döşemeler hep aynı genişlikte kalsın.
        repeat(4 - faceRow.childCount) { ui.add(faceRow, ui.space(), width = 0, height = ui.dp(1), weight = 1f) }
        gallery?.takeIf { it.isShowing }?.refresh(current)
        refreshFaceProgress()
    }

    private fun openGallery() {
        val g = FaceGallery(act, onOpen = { openFace(it) }, onClosed = { gallery = null })
        gallery = g
        g.refresh(faceOnWatch())
        g.show()
        refreshFaceProgress()
    }

    /** Yükleme ilerlemesi: ilgili önizlemenin halkası ve alttaki durum satırı. */
    fun refreshFaceProgress() {
        val busy = WatchHub.faceBusy
        val id = WatchHub.faceId
        val all = faceThumbs + (gallery?.thumbs ?: emptyList())
        all.forEach { (k, v) -> v.setProgress(if (busy && k == id) WatchHub.facePercent else -1) }
        val text = WatchHub.faceText
        val colorId = if (WatchHub.facePhase == FaceUpload.Phase.FAILED) R.color.warning else R.color.text2
        faceStatus.text = text
        faceStatus.setTextColor(ui.color(colorId))
        faceStatus.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        gallery?.setStatus(text, colorId)
    }

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

    // ---------- Ekran ve profil ----------

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
        ui.add(box, ui.caption("Bugün ekranındaki hedef ve saatin hedefi aynıdır."), topDp = 8)
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
}

// =====================================================================
// 3. Bildirimler
// =====================================================================

class NotifyPage(act: MainActivity) : Page(act, "Bildirimler") {

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
        ui.add(body, accessBanner.view, topDp = 4)

        val main = ui.card(padDp = 16, padVDp = 6)
        ui.add(main, forward.view)
        for (row in listOf(incomingCalls, missedCalls, vibrate, foldTurkish, rejectCalls)) {
            ui.add(ls05NotificationOptions, ui.divider(), height = ui.dp(1))
            ui.add(ls05NotificationOptions, row.view)
        }
        ui.add(main, ls05NotificationOptions)
        ui.add(body, main, topDp = 10)

        // Kurallar ve özel kaynaklar: iki saat için ortak.
        val extra = ui.card(padDp = 16, padVDp = 6)
        ui.add(extra, ui.row("Bildirim kuralları", "Uygulama, kişi, kelime, sessiz saatler, tekrar") { NotifyRulesScreen(act).show() })
        for (row in listOf(navigationRow, batteryLowRow, batteryFullRow)) {
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

    override fun refresh() {
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

// =====================================================================
// 4. Ayarlar
// =====================================================================

class SettingsPage(act: MainActivity) : Page(act, "Ayarlar") {

    private companion object {
        const val LAST_SEEN_INFO = "Bağlantı beklenmedik koparsa telefonun konumu kaydedilir. Yalnızca son kayıt tutulur."
        const val PROBE_DEBOUNCE_MS = 2_000L
    }

    private val logShown = 300
    private var logCount = 0

    private val setup: Collapsible = ui.collapsible("Kurulum", "", R.drawable.ic_check, open = false)
    private val theme: Segmented = ui.segmented(listOf("Sistem", "Koyu", "Açık"), Prefs.themeMode(act)) { act.setThemeMode(it) }
    private val connectionHeading = ui.section("Saat bağlantısı")
    private val idRow: NavRow = ui.navRow(null, "Kimlik", null, "") { editId() }
    private val deviceBox: LinearLayout = ui.vertical()
    private val directConnectButtons: LinearLayout = ui.vertical()
    private var scanCandidates = 0
    private var scanGeneration = 0
    private var bandKeyDialog: AlertDialog? = null
    // Hava durumu
    private val weatherSwitch: SwitchRow = ui.switchRow(
        "Hava durumunu saate gönder",
        "Open-Meteo'dan saatte bir alınır. Yalnızca açıkken internete çıkılır.",
        Prefs.weatherEnabled(act),
        iconRes = R.drawable.ic_weather,
    ) { on ->
        Prefs.setWeatherEnabled(act, on)
        if (on && Prefs.weatherLat(act) == null) pickCity() else if (on) WeatherSync.refresh(act, force = true)
        refreshWatchInfo()
    }
    private val cityRow: NavRow = ui.navRow(null, "Şehir", null, "") { pickCity() }
    private val weatherInfo: TextView = ui.caption("")

    /** Hava durumu bölümü (etkin saat desteklemiyorsa gizlenir). */
    private val weatherViews = ArrayList<View>()

    private val autoTime: SwitchRow = ui.switchRow(
        "Bağlanınca saati eşitle",
        "Saat her bağlantıda telefonun saatine ayarlanır.",
        Prefs.autoTime(act),
    ) { Prefs.setAutoTime(act, it) }
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
        refreshWatchInfo()
    }
    private val lostQuiet: SwitchRow = ui.switchRow("Sessiz saatlerde uyarma", "", Prefs.lostQuiet(act)) {
        Prefs.setLostQuiet(act, it)
    }
    // Son görülen yer
    private val lastSeenSwitch: SwitchRow = ui.switchRow(
        "Son görülen yeri kaydet",
        LAST_SEEN_INFO,
        Prefs.lastSeenEnabled(act),
    ) { on -> toggleLastSeen(on) }
    private val lastSeenBox: LinearLayout = ui.vertical()
    private var lastSeenAsked = false
    private var lastSeenDenied = false
    private val firmwareValue: TextView = ui.text("", 15f, R.color.text2)
    private val actionButtons = ArrayList<Button>()
    private val bandFindTests = ui.vertical()
    private val bandCallTests = ui.vertical()
    private val bandProbes = ui.vertical()
    private val logView: TextView = ui.mono()
    private var setupOpened = false

    init {
        // Kurulum: yalnızca eksik adım varsa görünür; dokununca ayrıntılı liste.
        ui.add(body, setup.view, topDp = 4)

        // Saat bağlantısı
        ui.add(body, connectionHeading, topDp = 8)
        val w = ui.card(padDp = 16, padVDp = 6)
        ui.add(w, idRow.view)
        val btns = ui.horizontal()
        ui.add(btns, ui.secondary("Tara") { scan() }, width = 0, weight = 1f)
        ui.add(btns, ui.tonal("Bağlan") { connectSaved() }, width = 0, weight = 1f, leftDp = 8)
        ui.add(btns, ui.secondary("Durdur") { WatchService.start(act, WatchService.ACTION_STOP) },
            width = 0, weight = 1f, leftDp = 8)
        ui.add(w, btns, topDp = 4)
        ui.add(directConnectButtons, ui.row("Kayıtlı Bluetooth cihazlarından seç", "Tarama gerektirmez; mevcut eşleşmeler", onClick = { showBondedDevices() }))
        ui.add(directConnectButtons, ui.row("Bluetooth adresiyle bağlan", "Önceki bağlantı kaydındaki adresi kullan", onClick = { enterKnownBluetoothAddress() }))
        ui.add(w, directConnectButtons, topDp = 6)
        ui.add(w, deviceBox, topDp = 8)
        ui.add(w, ui.divider(), height = ui.dp(1))
        ui.add(w, autoTime.view)
        ui.add(w, ui.divider(), height = ui.dp(1))
        ui.add(w, ui.row("Firmware", trailing = firmwareValue))
        ui.add(body, w, topDp = 8)

        // Uyarılar
        ui.add(body, ui.section("Uyarılar"), topDp = 20)
        val alerts = ui.card(padDp = 16, padVDp = 6)
        ui.add(alerts, lostAlert.view)
        ui.add(alerts, ui.divider(), height = ui.dp(1))
        ui.add(alerts, lostQuiet.view)
        ui.add(alerts, ui.divider(), height = ui.dp(1))
        ui.add(alerts, lastSeenSwitch.view)
        ui.add(alerts, lastSeenBox)
        ui.add(alerts, ui.divider(), height = ui.dp(1))
        ui.add(alerts, remote.view)
        ui.add(body, alerts, topDp = 8)

        // Hava durumu
        val wxSec = ui.section("Hava durumu")
        ui.add(body, wxSec, topDp = 20)
        val wx = ui.card(padDp = 16, padVDp = 6)
        weatherViews += wxSec
        weatherViews += wx
        ui.add(wx, weatherSwitch.view)
        ui.add(wx, ui.divider(), height = ui.dp(1))
        ui.add(wx, cityRow.view)
        val wxBottom = ui.horizontal()
        ui.add(wxBottom, weatherInfo, width = 0, weight = 1f)
        ui.add(wxBottom, ui.tonal("Güncelle") {
            if (!Prefs.weatherEnabled(act) || Prefs.weatherLat(act) == null) {
                act.toast("Önce hava durumunu aç ve şehir seç.")
            } else {
                weatherInfo.text = "Alınıyor…"
                WeatherSync.refresh(act, force = true) { err ->
                    if (err != null) act.toast(err)
                    refreshWatchInfo()
                }
            }
        }, width = Ui.WRAP, leftDp = 8)
        ui.add(wx, wxBottom, topDp = 4)
        ui.add(body, wx, topDp = 8)

        // Görünüm
        ui.add(body, ui.section("Görünüm"), topDp = 20)
        val themeCard = ui.card()
        ui.add(themeCard, ui.iconTitle(R.drawable.ic_theme, R.color.text2, "Tema"))
        ui.add(themeCard, theme.view, topDp = 12)
        ui.add(body, themeCard, topDp = 8)

        // Gelişmiş ve log: varsayılan kapalı.
        val adv = ui.collapsible("Gelişmiş ve log", "Saati eşitle, son satırlar", R.drawable.ic_code, open = false)
        val timeButton = ui.secondary("Saati eşitle") { withWatch { it.sendTime() } }
        actionButtons += timeButton
        ui.add(adv.content, timeButton)
        ui.add(bandFindTests, ui.cardTitle("Band 8 saati bul testi"))
        ui.add(bandFindTests, ui.caption("Band 8 yanındayken dene. Gerekirse titreşimi bileklikten durdur."), topDp = 4)
        val findStart = ui.secondary("Başlatmayı dene") { testBandFind(start = true) }
        val findStop = ui.secondary("Durdurmayı dene") { testBandFind(start = false) }
        actionButtons += findStart
        actionButtons += findStop
        ui.add(bandFindTests, findStart, topDp = 8)
        ui.add(bandFindTests, findStop, topDp = 4)
        bandFindTests.visibility = View.GONE
        ui.add(adv.content, bandFindTests, topDp = 12)
        ui.add(bandCallTests, ui.cardTitle("Band 8 arama ekranı denemesi"))
        ui.add(bandCallTests, ui.caption("Deneysel: gerçek telefon araması başlatmaz. Yalnızca bileklikteki ekranı dener; bağlantı sürerse 15 saniye sonra kapatmayı dener."), topDp = 4)
        val callStart = ui.secondary("Arama ekranını dene") { testBandCallScreen(start = true) }
        val callStop = ui.secondary("Arama ekranını kapat") { testBandCallScreen(start = false) }
        actionButtons += callStart
        actionButtons += callStop
        ui.add(bandCallTests, callStart, topDp = 8)
        ui.add(bandCallTests, callStop, topDp = 4)
        bandCallTests.visibility = View.GONE
        ui.add(adv.content, bandCallTests, topDp = 12)
        buildBandProbes()
        bandProbes.visibility = View.GONE
        ui.add(adv.content, bandProbes, topDp = 12)
        ui.add(adv.content, ui.cardTitle("Log"), topDp = 20)
        val lr = ui.horizontal()
        ui.add(lr, ui.secondary("Logu kopyala") { copyLog() }, width = 0, weight = 1f)
        ui.add(lr, ui.danger("Logu temizle") { clearLog() }, width = 0, weight = 1f, leftDp = 8)
        ui.add(adv.content, lr, topDp = 8)
        ui.add(adv.content, logView, topDp = 12)
        ui.add(body, adv.view, topDp = 20)

        // Hakkında
        val footer = ui.caption(BuildIdentity.label)
        footer.setGravity(Gravity.CENTER)
        ui.add(body, footer, topDp = 20)

        refreshLog()
    }

    override fun refresh() {
        val caps = WatchManager.caps(act)
        weatherViews.forEach { it.visibility = if (caps.weather) View.VISIBLE else View.GONE }
        remote.view.visibility = if (caps.musicRemote || caps.findPhone) View.VISIBLE else View.GONE
        bandFindTests.visibility =
            if (WatchRegistry.active(act)?.type == WatchType.BAND8) View.VISIBLE else View.GONE
        bandCallTests.visibility = bandFindTests.visibility
        bandProbes.visibility = bandFindTests.visibility
        refreshSetup()
        refreshWatchInfo()
        theme.select(Prefs.themeMode(act))
        onReady(WatchHub.ready)
    }

    private fun testBandFind(start: Boolean) {
        val service = act.readyService() ?: return
        if (!service.testBandFind(start)) act.toast("Band 8 komutu gönderilemedi. Biraz bekleyip tekrar dene.")
    }

    /** Band 8 denemeleri: her düğme tek komut gönderir, yalnızca elle. */
    private fun buildBandProbes() {
        ui.add(bandProbes, ui.cardTitle("Band 8 denemeleri"))
        ui.add(bandProbes, ui.caption("Her düğme banda tek komut gönderir. Logda yalnızca komut türü ve yanıt türü görünür. Bantta ne olduğunu gözle."), topDp = 4)
        fun group(title: String, vararg items: Pair<String, Band8Probe>) {
            ui.add(bandProbes, ui.caption(title), topDp = 12)
            for (row in items.toList().chunked(2)) {
                val r = ui.horizontal()
                row.forEachIndexed { i, (label, probe) ->
                    val b = ui.secondary(label) { bandProbe(probe) }
                    actionButtons += b
                    ui.add(r, b, width = 0, weight = 1f, leftDp = if (i == 0) 0 else 8)
                }
                if (row.size == 1) ui.add(r, View(act), width = 0, weight = 1f, leftDp = 8)
                ui.add(bandProbes, r, topDp = 4)
            }
        }
        group("Bildirim: emojiler kısa metne dönüyor mu (<3 (+1) :D :O :( (dua))", "Emoji testi" to Band8Probe.EMOJI_TEST)
        group("Titreşim: kısa-kısa-uzun desen (kurallarda henüz kullanılmaz)", "Titreşim deseni testi" to Band8Probe.VIBRATION_TEST)
        group("Arama (gelen arama ve kapatma yukarıdaki denemede)", "Cevapsız arama" to Band8Probe.MISSED_CALL)
        group("Alarm: her değişiklikten önce listeyi oku. Test alarmı 2 dakika sonra çalar.",
            "Alarm listesini oku" to Band8Probe.ALARM_LIST, "Test alarmı kur" to Band8Probe.ALARM_CREATE,
            "Test alarmını düzenle" to Band8Probe.ALARM_EDIT, "Test alarmını sil" to Band8Probe.ALARM_DELETE,
            "Test alarmlarını temizle" to Band8Probe.ALARM_CLEANUP)
        group("Rahatsız etme",
            "Şimdi aç" to Band8Probe.DND_ON, "Şimdi kapat" to Band8Probe.DND_OFF,
            "Zamanla 22:00–07:00" to Band8Probe.DND_SCHEDULE, "Zamanlamayı kapat" to Band8Probe.DND_SCHEDULE_OFF)
        group("Hava durumu (Ayarlar > Hava durumu verisiyle)",
            "Konum ekle" to Band8Probe.WEATHER_LOCATION, "Anlık hava" to Band8Probe.WEATHER_NOW,
            "Günlük tahmin" to Band8Probe.WEATHER_FORECAST)
        group("Hareketsizlik uyarısı: önce ayarı oku",
            "Ayarı oku" to Band8Probe.IDLE_READ, "Aç" to Band8Probe.IDLE_ON, "Kapat" to Band8Probe.IDLE_OFF)
        group("Müzik", "Test şarkı gönder" to Band8Probe.MUSIC)
        group("Sağlık dosyaları (yalnızca okuma, bandan silinmez)", "Şimdi çek" to Band8Probe.FETCH_NOW)
        val export = ui.secondary("Band 8 ham verisini dışa aktar") { Band8RawExport.start(act) }
        ui.add(bandProbes, export, topDp = 12)
        ui.add(bandProbes, ui.caption("Arşivdeki dosyaları ve index.txt'yi tek zip olarak kaydeder. Değerler uygulamada gösterilmez."), topDp = 4)
    }

    /** Her deneme düğmesi en çok 2 saniyede bir komut gönderir (çift alarm gibi tekrarları önler). */
    private val lastProbeAt = HashMap<Band8Probe, Long>()

    private fun bandProbe(p: Band8Probe) {
        val now = android.os.SystemClock.elapsedRealtime()
        val last = lastProbeAt[p]
        if (last != null && now - last < PROBE_DEBOUNCE_MS) {
            act.toast("Biraz bekle; komut az önce gönderildi.")
            return
        }
        lastProbeAt[p] = now
        val service = act.readyService() ?: return
        if (p == Band8Probe.ALARM_CLEANUP) {
            confirmAlarmCleanup(service)
            return
        }
        service.bandProbe(p)?.let { act.toast(it) }
    }

    /** Deneme imzasına uyan alarmları (bir kez, normal) saatleriyle gösterir; onaylanırsa tek 17/4 ile siler. */
    private fun confirmAlarmCleanup(service: WatchService) {
        val list = service.bandAlarmCleanupCandidates()
        if (list == null) {
            act.toast("Önce alarm listesini oku.")
            return
        }
        if (list.isEmpty()) {
            act.toast("Silinecek test alarmı yok.")
            return
        }
        val times = list.sortedWith(compareBy({ it.hour }, { it.minute })).joinToString(", ") { Fmt.hm(it.hour, it.minute) }
        val d = AlertDialog.Builder(act)
            .setTitle("Test alarmlarını temizle")
            .setMessage("Bir kez çalan ve akıllı olmayan ${list.size} alarm silinecek: $times. Bunlar arasında kendi alarmın varsa Vazgeç'e bas.")
            .setPositiveButton("Sil") { _, _ -> service.bandProbe(Band8Probe.ALARM_CLEANUP)?.let { act.toast(it) } }
            .setNegativeButton("Vazgeç", null)
            .create()
        act.track(d)
        d.show()
    }

    private fun testBandCallScreen(start: Boolean) {
        val service = act.readyService() ?: return
        if (!service.testBandCallScreen(start)) act.toast("Band 8 arama denemesi gönderilemedi. Logu kontrol et.")
    }

    /** Özet satırı: "Her şey hazır" ya da eksik sayısı. Eksik varsa liste ilk açılışta açık gelir. */
    private fun refreshSetup() {
        val items = act.setupItems()
        val missing = items.count { it.ok == false }
        // Her şey hazırsa satır hiç görünmez.
        if (missing == 0) {
            setup.view.visibility = View.GONE
            return
        }
        setup.view.visibility = View.VISIBLE
        setup.subtitle.text = "$missing adım eksik"
        setup.subtitle.setTextColor(ui.color(R.color.warning))
        ui.setIcon(setup.icon, R.drawable.ic_alert, R.color.warning)
        setup.subtitle.visibility = View.VISIBLE
        if (missing > 0 && !setupOpened) {
            setupOpened = true
            setup.setOpen(true)
        }
        setup.content.removeAllViews()
        for (item in items) {
            val (label, tone) = when (item.ok) {
                true -> "Tamam" to Tone.NEUTRAL
                false -> "Eksik" to Tone.WARN
                null -> "Kontrol et" to Tone.NEUTRAL
            }
            ui.add(setup.content, ui.row(item.label, trailing = ui.pill(label, tone), onClick = item.fix))
        }
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
                                    refreshWatchInfo()
                                    WeatherSync.refresh(act, force = true) { e ->
                                        if (e != null) act.toast(e)
                                        refreshWatchInfo()
                                    }
                                }
                                ui.add(list, r.view)
                            }
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    fun openWatchConnection() {
        act.selectTab(3)
        root.post { root.smoothScrollTo(0, connectionHeading.top) }
    }

    fun refreshWatchInfo() {
        weatherSwitch.toggle.set(Prefs.weatherEnabled(act))
        cityRow.value.text = Prefs.weatherCityName(act).ifEmpty { "Seçilmedi" }
        weatherInfo.text = WeatherSync.summary(act)
        val band = WatchRegistry.active(act)?.type == WatchType.BAND8
        idRow.view.visibility = if (band) View.GONE else View.VISIBLE
        directConnectButtons.visibility = if (band) View.VISIBLE else View.GONE
        autoTime.view.visibility = if (band) View.GONE else View.VISIBLE
        idRow.value.text = if (hasId()) "Kayıtlı" else "Girilmedi"
        autoTime.toggle.set(Prefs.autoTime(act))
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
        firmwareValue.text = WatchHub.firmware ?: "bilinmiyor"
    }

    fun onReady(ready: Boolean) {
        actionButtons.forEach { ui.enable(it, ready) }
        if (ready) {
            ++scanGeneration
            deviceBox.removeAllViews()
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
        })
    }

    private fun withWatch(block: (WatchService) -> Unit) {
        val s = act.readyService() ?: return
        block(s)
    }

    // ---------- Kimlik ----------

    private fun hasId(): Boolean = UteCommand.parseUserId(Prefs.userIdText(act)) != null

    /** Kimlik ekranda ve logda gösterilmez; kutu boş açılır. */
    private fun editId() {
        val box = ui.dialogBox()
        ui.add(box, ui.caption("8 haneli onaltılık kimlik. Yalnızca telefonda saklanır, ekranda ve logda gösterilmez."), topDp = 8)
        val input = ui.field("Kimlik (8 hane)")
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        ui.add(box, input, topDp = 16)
        val dialog = AlertDialog.Builder(act)
            .setTitle("Kimlik")
            .setView(box)
            .setPositiveButton("Kaydet", null)
            .setNegativeButton("Vazgeç", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = input.text.toString().trim()
                if (UteCommand.parseUserId(text) == null) {
                    WatchHub.log("Kimlik geçersiz veya boş: 8 haneli onaltılık değer gerekli.")
                    act.toast("Kimlik geçersiz: 8 haneli onaltılık değer gerekli.")
                } else {
                    Prefs.setUserIdText(act, text)
                    WatchHub.log("Kimlik kaydedildi.")
                    refreshWatchInfo()
                    dialog.dismiss()
                }
            }
        }
        act.track(dialog)
        dialog.show()
    }

    // ---------- Tarama ve bağlantı ----------

    private fun scan() {
        if (!act.hasBlePermissions()) {
            act.requestBlePermissions()
            return
        }
        deviceBox.removeAllViews()
        scanCandidates = 0
        val generation = ++scanGeneration
        ui.add(deviceBox, ui.caption("10 saniye boyunca yakındaki Bluetooth cihazları aranıyor…"), topDp = 8)
        act.handler.postDelayed({
            if (scanGeneration == generation && scanCandidates == 0) {
                deviceBox.removeAllViews()
                ui.add(deviceBox, ui.caption("Cihaz bulunamadı. Band 8'i yakına getir, Mi Fitness ve Notify bağlantılarını kapat, ardından tekrar Tara'ya bas."), topDp = 8)
            }
        }, 11_000L)
        if (WatchHub.service == null) WatchService.start(act)
        act.toast("Saat aranıyor.")
        act.handler.postDelayed({
            val s = WatchHub.service
            if (s == null) WatchHub.log("Servis başlamadı; Bluetooth iznini kontrol et.") else s.startScan()
        }, 600L)
    }

    private fun connectSaved() {
        if (!act.hasBlePermissions()) {
            act.requestBlePermissions()
            return
        }
        val active = WatchRegistry.active(act)
        if (active?.type == WatchType.BAND8) {
            if (!SecretStore.has(act, active.id)) {
                if (Prefs.address(act) != null && WatchHub.service?.resumeBandAuth() == true) return
                act.toast("Önce Tara ile Band 8'i seç; anahtar cihaz doğrulandıktan sonra istenecek.")
                return
            }
        } else if (!hasId()) {
            WatchHub.log("Kimlik geçersiz veya boş: 8 haneli onaltılık değer gerekli.")
            act.toast("Önce kimliği gir.")
            editId()
            return
        }
        val address = Prefs.address(act)
        if (address == null) {
            WatchHub.log("Kayıtlı saat yok; önce Tara ile saati seç.")
            act.toast("Kayıtlı saat yok. Önce Tara ile saati seç.")
            return
        }
        WatchService.start(act, WatchService.ACTION_CONNECT_NEW, address)
    }

    fun onDeviceFound(device: BluetoothDevice, name: String, rssi: Int) {
        if (scanCandidates++ == 0) deviceBox.removeAllViews()
        val candidate = name.contains("Band 8", true) || name.contains("M2239B1", true) ||
            name.contains("FE95", true)
        val subtitle = if (WatchRegistry.active(act)?.type == WatchType.BAND8) {
            if (candidate) "Olası Band 8 · servisi doğrula · $rssi dBm"
            else "Diğer Bluetooth cihazı · FE95 kontrolü · $rssi dBm"
        } else "Bu cihazı seç · $rssi dBm"
        ui.add(deviceBox, ui.row(name, subtitle, onClick = { useDevice(device) }))
    }

    private fun useDevice(device: BluetoothDevice) {
        if (WatchRegistry.active(act)?.type != WatchType.BAND8 && !hasId()) {
            act.toast("Önce kimliği gir.")
            editId()
            return
        }
        val active = WatchRegistry.active(act)
        if (active?.type == WatchType.BAND8) {
            val likely = device.name?.let {
                it.contains("Band 8", true) || it.contains("M2239B1", true)
            } == true
            if (!likely) {
                val dlg = AlertDialog.Builder(act)
                    .setTitle("Cihazı doğrula")
                    .setMessage("Bu cihazın Band 8 olduğu kesin değil. Xiaomi FE95 servis kontrolü yapılacak. Eşleşme veya sıfırlama yapılmaz.")
                    .setPositiveButton("Servisi kontrol et") { _, _ ->
                        deviceBox.removeAllViews()
                        WatchService.start(act, WatchService.ACTION_CONNECT_NEW, device.address)
                    }
                    .setNegativeButton("Vazgeç", null)
                    .create()
                act.track(dlg)
                dlg.show()
                return
            }
        }
        deviceBox.removeAllViews()
        WatchService.start(act, WatchService.ACTION_CONNECT_NEW, device.address)
    }

    /** Sadece Android'in zaten eşleştirdiği cihazları göster; yeni bond oluşturmaz. */
    @Suppress("MissingPermission")
    private fun showBondedDevices() {
        if (WatchRegistry.active(act)?.type != WatchType.BAND8) return
        if (!act.hasBlePermissions()) {
            act.requestBlePermissions()
            return
        }
        val adapter = act.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter?.isEnabled != true) {
            act.toast("Bluetooth'u aç ve tekrar dene.")
            return
        }
        val devices = try { adapter.bondedDevices.orEmpty().sortedBy { it.name ?: "" } }
            catch (_: SecurityException) {
                act.toast("Kayıtlı Bluetooth cihazları için izin gerekli.")
                return
            }
        if (devices.isEmpty()) {
            act.toast("Android'de kayıtlı Bluetooth cihazı bulunamadı. Adresle bağlantıyı dene.")
            return
        }
        val names = devices.map { device ->
            val name = device.name?.takeIf { it.isNotBlank() } ?: "İsimsiz kayıtlı cihaz"
            "$name · …${device.address.takeLast(5)}"
        }
        val d = AlertDialog.Builder(act)
            .setTitle("Kayıtlı Bluetooth cihazları")
            .setMessage("Band 8'i seç. Bağlantı kurulursa Xiaomi FE95 servisi doğrulanır; eşleşmeler silinmez.")
            .setItems(names.toTypedArray()) { _, index -> connectKnownDevice(devices[index].address) }
            .setNegativeButton("Vazgeç", null)
            .create()
        act.track(d)
        d.show()
    }

    /** Kullanıcı girer. Tam adres ekranda, logda veya GitHub'da gösterilmez. */
    private fun enterKnownBluetoothAddress() {
        if (WatchRegistry.active(act)?.type != WatchType.BAND8) return
        if (!act.hasBlePermissions()) {
            act.requestBlePermissions()
            return
        }
        val box = ui.dialogBox()
        ui.add(box, ui.caption("Önceki Bluetooth bağlantı kaydındaki 6 baytlık adresi yaz. Örnek biçim: XX:XX:XX:XX:XX:XX. Eşleşme değiştirilmez."), topDp = 8)
        val input = ui.field("Bluetooth adresi")
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        input.filters = arrayOf(android.text.InputFilter.LengthFilter(17))
        ui.add(box, input, topDp = 12)
        val d = AlertDialog.Builder(act)
            .setTitle("Bilinen adrese bağlan")
            .setView(box)
            .setPositiveButton("Bağlan", null)
            .setNegativeButton("Vazgeç", null)
            .create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val address = input.text.toString().trim().uppercase(java.util.Locale.ROOT)
                if (!BluetoothAdapter.checkBluetoothAddress(address)) {
                    act.toast("Geçerli Bluetooth adresi gir. Biçim: XX:XX:XX:XX:XX:XX")
                    return@setOnClickListener
                }
                input.text.clear()
                d.dismiss()
                connectKnownDevice(address)
            }
        }
        act.track(d)
        d.show()
    }

    /** Tarama olmadan GATT bağlantısını dener; FE95 kontrolü Band8Ble'de kalır. */
    private fun connectKnownDevice(address: String) {
        if (WatchRegistry.active(act)?.type != WatchType.BAND8) return
        if (!act.hasBlePermissions()) {
            act.requestBlePermissions()
            return
        }
        ++scanGeneration
        deviceBox.removeAllViews()
        ui.add(deviceBox, ui.caption("Bilinen Bluetooth adresine bağlanılıyor. Xiaomi FE95 servisi doğrulanacak…"), topDp = 8)
        if (!WatchService.start(act, WatchService.ACTION_CONNECT_NEW, address)) {
            act.toast("Saat servisi başlatılamadı. Uygulamadan tekrar dene.")
        }
    }

    /** İlk GATT servis doğrulamasından sonra istenir; anahtar kullanıcıya geri gösterilmez. */
    fun promptBandKey() {
        val active = WatchRegistry.active(act) ?: return
        if (active.type != WatchType.BAND8 || bandKeyDialog?.isShowing == true) return
        val box = ui.dialogBox()
        ui.add(box, ui.caption("Xiaomi BLE protokol servisi bulundu. Eşleştirilmiş Band 8 için 32 haneli anahtarı gir. Anahtar telefonda şifreli saklanır."), topDp = 8)
        val input = ui.field("Band 8 anahtarı")
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        input.filters = arrayOf(android.text.InputFilter.LengthFilter(40))
        ui.add(box, input, topDp = 12)
        val d = AlertDialog.Builder(act)
            .setTitle("Band 8 kimlik doğrulama")
            .setView(box)
            .setPositiveButton("Bağlan", null)
            .setNegativeButton("Vazgeç") { _, _ -> WatchHub.service?.cancelBandSetup() }
            .create()
        bandKeyDialog = d
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().trim()
                val bytes = SecretEnvelope.parseKeyHex(value)
                if (bytes == null) {
                    act.toast("32 haneli geçerli onaltılık anahtar gerekli.")
                    return@setOnClickListener
                }
                bytes.fill(0)
                if (!SecretStore.putHex(act, active.id, value)) {
                    act.toast("Anahtar güvenli biçimde kaydedilemedi.")
                    return@setOnClickListener
                }
                input.text.clear()
                d.dismiss()
                if (WatchHub.service?.resumeBandAuth() != true) {
                    val address = Prefs.address(act)
                    if (address != null) WatchService.start(act, WatchService.ACTION_CONNECT_NEW, address)
                    else act.toast("Bağlantı kesildi; yeniden Tara'ya bas.")
                }
            }
        }
        d.setOnDismissListener { if (bandKeyDialog === d) bandKeyDialog = null }
        act.track(d)
        d.show()
    }

    // ---------- Log ----------

    fun onLog(line: String) {
        logView.append(line + "\n")
        logCount++
        if (logCount > logShown * 2) refreshLog()
    }

    private fun refreshLog() {
        val lines = WatchHub.logForSharing(logShown)
        logCount = lines.size
        logView.text = if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n")
    }

    private fun clearLog() {
        WatchHub.logLines.clear()
        WatchHub.logLines.addLast(BuildIdentity.label)
        logView.text = BuildIdentity.label + "\n"
        logCount = 1
    }

    private fun copyLog() {
        val cm = act.getSystemService(ClipboardManager::class.java)
        val lines = WatchHub.logForSharing()
        cm.setPrimaryClip(ClipData.newPlainText("LS05 log", lines.joinToString("\n")))
        act.toast("Son ${lines.size} satır kopyalandı (bildirim içerikleri gizli, MAC maskeli).")
    }
}
