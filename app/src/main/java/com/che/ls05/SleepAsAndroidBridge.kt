package com.che.ls05

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.che.ls05.protocol.SaaApi
import com.che.ls05.protocol.SaaController
import com.che.ls05.protocol.SaaOut
import com.che.ls05.protocol.WatchType
import com.che.ls05.protocol.band8.B8SaaVibration

/** Sleep as Android'in saat eklentisi yayınlarını alır (Ayarlar > Sleep as Android açıkken). */
class SaaReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SleepAsAndroidBridge.onIntent(context.applicationContext, intent)
    }
}

/**
 * Sleep as Android ile Band 8 arasındaki köprü (docs/SLEEP_AS_ANDROID.md).
 * Karar mantığı [SaaController]'da; burada yalnızca yayın ve band komutu.
 * Log: komut adı, durum ve sayılar. Nabız değeri, bildirim metni loga yazılmaz.
 */
object SleepAsAndroidBridge {
    private val main = Handler(Looper.getMainLooper())
    private val ctl = SaaController()
    private var app: Context? = null
    private var lastCheckLogAt = 0L
    private var lastSummaryAt = 0L
    private val tick = Runnable { onTick() }

    private fun now() = System.currentTimeMillis()

    val tracking: Boolean get() = ctl.tracking
    val streaming: Boolean get() = ctl.streaming

    /** Ayarlar kartındaki kısa durum. */
    fun statusText(c: Context): String = when {
        !Prefs.saaEnabled(c) -> "Kapalı."
        WatchRegistry.active(c)?.type != WatchType.BAND8 -> "Etkin saat Band 8 değil. LS05 için Sleep as Android saatsiz çalışır."
        ctl.alarmActive -> "Alarm çalıyor."
        ctl.tracking && ctl.streaming -> "Takip sürüyor, nabız gidiyor."
        ctl.tracking -> "Takip sürüyor, nabız gitmiyor."
        else -> "Hazır. Takip başlayınca nabız akışı açılır."
    }

    fun onIntent(c: Context, i: Intent) {
        app = c
        val action = i.action ?: return
        if (action !in SaaApi.INCOMING) return
        val name = SaaApi.shortName(action)
        if (!Prefs.saaEnabled(c)) {
            if (action != SaaApi.CHECK_CONNECTED) WatchHub.log("Sleep as Android: $name alındı, ayar kapalı; yok sayıldı")
            return
        }
        if (WatchRegistry.active(c)?.type != WatchType.BAND8) {
            if (action != SaaApi.CHECK_CONNECTED) WatchHub.log("Sleep as Android: $name alındı, etkin saat Band 8 değil; yok sayıldı")
            return
        }
        val t = now()
        ctl.hrEnabled = Prefs.saaHeartRate(c)
        // Servis bağlıysa band durumu güncel; değilse komut banda gidemez.
        if (ctl.bandReady != bandReady()) perform(ctl.onBandReady(bandReady(), t))
        if (action == SaaApi.CHECK_CONNECTED) {
            if (t - lastCheckLogAt > 60_000L) {
                lastCheckLogAt = t
                WatchHub.log("Sleep as Android: bağlantı sorusu, band ${if (ctl.bandReady) "hazır" else "hazır değil"}")
            }
        } else {
            WatchHub.log("Sleep as Android: $name")
        }
        val outs = when (action) {
            SaaApi.CHECK_CONNECTED -> ctl.onCheckConnected()
            SaaApi.START_TRACKING -> {
                lastSummaryAt = t
                if (WatchHub.service == null && Prefs.serviceEnabled(c)) WatchService.start(c)
                ctl.onStartTracking(t)
            }
            SaaApi.STOP_TRACKING -> ctl.onStopTracking(t).also { summary("takip bitti") }
            SaaApi.SET_PAUSE -> ctl.onPause(long(i, SaaApi.EXTRA_TIMESTAMP) ?: 0L, t)
            SaaApi.SET_SUSPENDED -> ctl.onSuspended(i.getBooleanExtra(SaaApi.EXTRA_SUSPENDED, false), t)
            SaaApi.SET_BATCH_SIZE -> {
                long(i, SaaApi.EXTRA_SIZE)?.let { ctl.onBatchSize(it) }
                WatchHub.log("Sleep as Android: paket aralığı ${ctl.periodMs / 1000} sn")
                emptyList()
            }
            SaaApi.START_ALARM -> ctl.onStartAlarm(long(i, SaaApi.EXTRA_DELAY)?.toInt() ?: 0, t)
            SaaApi.STOP_ALARM -> ctl.onStopAlarm()
            SaaApi.HINT -> ctl.onHint(long(i, SaaApi.EXTRA_REPEAT)?.toInt() ?: 1)
            // Alarm saati ve bildirim metni kullanılmaz, loga yazılmaz.
            else -> emptyList()
        }
        perform(outs)
        schedule()
        if (action != SaaApi.CHECK_CONNECTED) WatchHub.settingsChanged()
    }

    /** Servis: band hazır oldu ya da koptu. */
    fun onBandReady(ready: Boolean) {
        if (ctl.bandReady == ready) return
        if (!ready && !ctl.tracking && !ctl.alarmActive) {
            ctl.onBandReady(false, now())
            return
        }
        perform(ctl.onBandReady(ready, now()))
        if (ctl.tracking) WatchHub.log("Sleep as Android: band ${if (ready) "yeniden hazır" else "koptu"}, akış ${if (ctl.streaming) "açık" else "kapalı"}")
        schedule()
    }

    /** Ayar değişti. */
    fun settingsChanged(c: Context) {
        app = c.applicationContext
        val on = Prefs.saaEnabled(c)
        val t = now()
        if (!on) {
            perform(ctl.onStopAlarm())
            perform(ctl.onStopTracking(t))
        } else {
            perform(ctl.onSettings(Prefs.saaHeartRate(c), t))
        }
        schedule()
    }

    /** Band 8 8/47. Dönüş: akış bizim isteğimizle mi açık. Nabız yalnızca pakete girer. */
    fun onHeartRate(bpm: Int?): Boolean {
        val (ours, outs) = ctl.onHeartRate(bpm, now())
        perform(outs)
        return ours
    }

    /** Bantta arama ekranında reddet/sessize al. Sleep as Android alarmı açıksa olay buraya aittir. */
    fun onBandCallAction(reject: Boolean): Boolean {
        val outs = ctl.onBandAlarmAction(dismiss = reject) ?: return false
        WatchHub.log("Sleep as Android: bantta alarm ${if (reject) "kapatıldı" else "ertelendi"}")
        perform(outs)
        schedule()
        return true
    }

    private fun onTick() {
        val t = now()
        if (ctl.bandReady != bandReady()) perform(ctl.onBandReady(bandReady(), t))
        perform(ctl.onTick(t))
        if (ctl.tracking && t - lastSummaryAt >= SUMMARY_MS) summary("ara özet")
        schedule()
    }

    private fun summary(label: String) {
        lastSummaryAt = now()
        WatchHub.log("Sleep as Android $label: olay ${ctl.events}, geçerli ${ctl.valid}, paket ${ctl.packets}, yeniden başlatma ${ctl.restarts}")
    }

    private fun schedule() {
        main.removeCallbacks(tick)
        ctl.nextTickMs(now())?.let { main.postDelayed(tick, it) }
    }

    private fun band(): Band8Driver? = (WatchHub.service?.driver as? Band8Driver)?.takeIf { it.isReady }

    private fun bandReady(): Boolean = band() != null

    private fun perform(outs: List<SaaOut>) {
        for (o in outs) when (o) {
            SaaOut.ConfirmConnected -> send(Intent(SaaApi.CONFIRM_CONNECTED))
            SaaOut.HrStart -> band()?.realtimeHr(true)
            SaaOut.HrStop -> band()?.realtimeHr(false)
            is SaaOut.SendHr -> send(Intent(SaaApi.HR_DATA_UPDATE).putExtra(SaaApi.EXTRA_DATA, o.bpm))
            is SaaOut.Hint -> band()?.vibrate(B8SaaVibration.hint(o.repeat), "ipucu titreşimi 2/59")
            SaaOut.AlarmShow -> band()?.let {
                it.alarmScreen(true)
                it.vibrate(B8SaaVibration.ALARM, "alarm titreşimi 2/59")
            }
            SaaOut.AlarmBuzz -> band()?.vibrate(B8SaaVibration.ALARM, "alarm titreşimi 2/59")
            SaaOut.AlarmHide -> band()?.alarmScreen(false)
            SaaOut.Dismiss -> send(Intent(SaaApi.DISMISS_FROM_WATCH))
            SaaOut.Snooze -> send(Intent(SaaApi.SNOOZE_FROM_WATCH))
        }
    }

    private fun send(i: Intent) {
        val c = app ?: WatchHub.service ?: return
        try {
            c.sendBroadcast(i.setPackage(SaaApi.PACKAGE))
        } catch (e: Exception) {
            WatchHub.log("Sleep as Android'e gönderilemedi: ${e.javaClass.simpleName}")
        }
    }

    /** Ek int ya da long olarak gelebilir. */
    @Suppress("DEPRECATION")
    private fun long(i: Intent, key: String): Long? = (i.extras?.get(key) as? Number)?.toLong()

    private const val SUMMARY_MS = 30 * 60_000L
}
