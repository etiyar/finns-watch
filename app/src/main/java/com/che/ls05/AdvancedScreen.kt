package com.che.ls05

import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.che.ls05.protocol.WatchType

/**
 * Ayarlar > Gelişmiş ve log: tam ekran pencere. Hâlâ gerekli denemeler (v0.17 temizlik envanterinde (c)) katlanır
 * gruplarda, en altta log. Doğrulanan denemeler normal ekranlara taşındı ya da kaldırıldı (docs/V12_BAND8_PLAN.md).
 */
class AdvancedScreen(private val act: MainActivity) {

    private companion object {
        const val LOG_SHOWN = 300
        const val PROBE_DEBOUNCE_MS = 2_000L
    }

    private val ui = act.ui
    private var dialog: Dialog? = null
    private val content: LinearLayout = ui.vertical()
    private val logView: TextView = ui.mono()
    private var logCount = 0
    private val actionButtons = ArrayList<Button>()
    private val ls05Views = ArrayList<View>()
    private val band8Views = ArrayList<View>()

    init {
        content.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(16))

        // LS05: saati elle eşitle (otomatik eşitleme Ayarlar > Bağlantı'da).
        val time = ui.collapsible("Saat", "Saati elle eşitle", null, open = false)
        val timeButton = ui.secondary("Saati eşitle") { act.readyService()?.sendTime() }
        actionButtons += timeButton
        ui.add(time.content, timeButton, width = Ui.WRAP)
        ui.add(content, time.view, topDp = 8)
        ls05Views += time.view

        // Band 8 denemeleri: her düğme tek komut gönderir, yalnızca elle.
        fun group(title: String, sub: String, vararg items: Pair<String, Band8Probe>) {
            val g = ui.collapsible(title, sub, null, open = false)
            for (row in items.toList().chunked(2)) {
                val r = ui.horizontal()
                row.forEachIndexed { i, (label, probe) ->
                    val b = ui.secondary(label) { probe(probe) }
                    actionButtons += b
                    ui.add(r, b, width = 0, weight = 1f, leftDp = if (i == 0) 0 else 8)
                }
                if (row.size == 1) ui.add(r, View(act), width = 0, weight = 1f, leftDp = 8)
                ui.add(g.content, r, topDp = 4)
            }
            ui.add(content, g.view, topDp = 12)
            band8Views += g.view
        }
        val head = ui.caption("Band 8 denemeleri: her düğme banda tek komut gönderir. Logda yalnızca komut türü ve yanıt türü görünür.")
        head.setPadding(ui.dp(4), 0, ui.dp(4), 0)
        ui.add(content, head, topDp = 16)
        band8Views += head
        group("Alarm", "Eski deneme alarmlarını temizle", "Test alarmlarını temizle" to Band8Probe.ALARM_CLEANUP)
        group("Hava durumu", "Bantta hava ekranı doğrulanmadı",
            "Konum ekle" to Band8Probe.WEATHER_LOCATION, "Anlık hava" to Band8Probe.WEATHER_NOW,
            "Günlük tahmin" to Band8Probe.WEATHER_FORECAST)
        group("Hareketsizlik uyarısı", "Önce ayarı oku",
            "Ayarı oku" to Band8Probe.IDLE_READ, "Aç" to Band8Probe.IDLE_ON, "Kapat" to Band8Probe.IDLE_OFF)
        group("Otomatik nabız ölçümü", "Doğrulanmadı. Önce ayarı oku",
            "Ayarı oku" to Band8Probe.HR_CONFIG_READ, "Her 10 dk" to Band8Probe.HR_CONFIG_EVERY_10,
            "Sürekli" to Band8Probe.HR_CONFIG_CONTINUOUS, "Kapalı" to Band8Probe.HR_CONFIG_OFF)
        val raw = ui.collapsible("Band 8 ham verisi", "Biçim incelemesi için zip", null, open = false)
        ui.add(raw.content, ui.caption("Arşivdeki dosyaları ve index.txt'yi tek zip olarak kaydeder. Değerler uygulamada gösterilmez."))
        ui.add(raw.content, ui.secondary("Ham veriyi dışa aktar") { Band8RawExport.start(act) }, topDp = 8, width = Ui.WRAP)
        ui.add(content, raw.view, topDp = 12)
        band8Views += raw.view

        // Log: varsayılan açık.
        val log = ui.collapsible("Log", "Son $LOG_SHOWN satır", R.drawable.ic_code, open = true)
        val lr = ui.horizontal()
        ui.add(lr, ui.secondary("Logu kopyala") { copyLog() }, width = 0, weight = 1f)
        ui.add(lr, ui.danger("Logu temizle") { clearLog() }, width = 0, weight = 1f, leftDp = 8)
        ui.add(log.content, lr)
        ui.add(log.content, logView, topDp = 12)
        ui.add(content, log.view, topDp = 20)
        refreshLog()
    }

    fun show() {
        val d = dialog ?: build().also { dialog = it }
        refresh()
        act.track(d)
        d.show()
    }

    private fun build(): Dialog {
        val d = Dialog(act, R.style.AppTheme)
        val root = ui.vertical()
        root.setBackgroundColor(ui.color(R.color.bg))
        val top = ui.horizontal()
        top.setPadding(ui.dp(16), ui.dp(12), ui.dp(8), ui.dp(4))
        ui.add(top, ui.header("Gelişmiş ve log"), width = 0, weight = 1f)
        ui.add(top, ui.textButton("Kapat") { d.dismiss() }, width = Ui.WRAP)
        ui.add(root, top)
        ui.add(root, ui.scroll(content), height = 0, weight = 1f)
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        d.setContentView(root)
        return d
    }

    private fun refresh() {
        val band = WatchRegistry.active(act)?.type == WatchType.BAND8
        ls05Views.forEach { it.visibility = if (band) View.GONE else View.VISIBLE }
        band8Views.forEach { it.visibility = if (band) View.VISIBLE else View.GONE }
        onReady(WatchHub.ready)
    }

    fun onReady(ready: Boolean) {
        actionButtons.forEach { ui.enable(it, ready) }
    }

    /** Her deneme düğmesi en çok 2 saniyede bir komut gönderir (çift komutları önler). */
    private val lastProbeAt = HashMap<Band8Probe, Long>()

    private fun probe(p: Band8Probe) {
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
            act.toast("Alarm listesi okunmadı. Saat > Alarmlar'da Yenile'ye bas.")
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

    // ---------- Log ----------

    fun onLog(line: String) {
        logView.append(line + "\n")
        logCount++
        if (logCount > LOG_SHOWN * 2) refreshLog()
    }

    private fun refreshLog() {
        val lines = WatchHub.logForSharing(LOG_SHOWN)
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
