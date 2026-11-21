package com.che.ls05.protocol

import com.che.ls05.protocol.band8.B8Workout
import com.che.ls05.protocol.band8.SaaMovement

/*
 * Sleep as Android saat eklentisi arayüzü (docs/SLEEP_AS_ANDROID.md).
 * Adlar Sleep as Android'in "Wearable integration API" belgesinden alındı (doğrulandı).
 * Bu dosya Android'e bağımlı değildir: gelen komutları ve band olaylarını alır, yapılacak işleri döndürür.
 */
object SaaApi {
    const val PACKAGE = "com.urbandroid.sleep"
    private const val W = "com.urbandroid.sleep.watch."

    // Sleep as Android'den saate
    const val CHECK_CONNECTED = W + "CHECK_CONNECTED"
    const val START_TRACKING = W + "START_TRACKING"
    const val STOP_TRACKING = W + "STOP_TRACKING"
    const val SET_PAUSE = W + "SET_PAUSE"
    const val SET_SUSPENDED = W + "SET_SUSPENDED"
    const val SET_BATCH_SIZE = W + "SET_BATCH_SIZE"
    const val START_ALARM = W + "START_ALARM"
    const val STOP_ALARM = W + "STOP_ALARM"
    const val UPDATE_ALARM = W + "UPDATE_ALARM"
    const val SHOW_NOTIFICATION = W + "SHOW_NOTIFICATION"
    const val HINT = W + "HINT"

    // Saatten Sleep as Android'e
    const val CONFIRM_CONNECTED = W + "CONFIRM_CONNECTED"
    const val HR_DATA_UPDATE = W + "HR_DATA_UPDATE"
    const val DATA_UPDATE = W + "DATA_UPDATE"
    const val SNOOZE_FROM_WATCH = W + "SNOOZE_FROM_WATCH"
    const val DISMISS_FROM_WATCH = W + "DISMISS_FROM_WATCH"

    // Ekler
    const val EXTRA_TIMESTAMP = "TIMESTAMP"
    const val EXTRA_SUSPENDED = "SUSPENDED"
    const val EXTRA_SIZE = "SIZE"
    const val EXTRA_DELAY = "DELAY"
    const val EXTRA_REPEAT = "REPEAT"
    const val EXTRA_DATA = "DATA"
    const val EXTRA_MAX_DATA = "MAX_DATA"
    const val EXTRA_MAX_RAW_DATA = "MAX_RAW_DATA"

    val INCOMING: List<String> = listOf(
        CHECK_CONNECTED, START_TRACKING, STOP_TRACKING, SET_PAUSE, SET_SUSPENDED, SET_BATCH_SIZE,
        START_ALARM, STOP_ALARM, UPDATE_ALARM, SHOW_NOTIFICATION, HINT,
    )

    /** Log için kısa ad (ör. "START_TRACKING"). */
    fun shortName(action: String): String = action.removePrefix(W)
}

/** Denetleyicinin istediği iş. Uygulama bunları banda ya da Sleep as Android'e iletir. */
sealed class SaaOut {
    /** Sleep as Android'e "bağlıyım". */
    object ConfirmConnected : SaaOut()
    /** Banda 8/45. */
    object HrStart : SaaOut()
    /** Banda 8/46. */
    object HrStop : SaaOut()
    /** Sleep as Android'e nabız paketi (geliş sırasıyla). */
    class SendHr(val bpm: FloatArray) : SaaOut()
    /** Banda gizli antrenman başlat (8/26, hareket için). */
    object MoveStart : SaaOut()
    /** Gizli antrenmanı bitir (8/26 duraklat, sonra bitir). */
    object MoveStop : SaaOut()
    /** Sleep as Android'e hareket paketi (DATA_UPDATE). */
    class SendMove(val max: FloatArray, val raw: FloatArray) : SaaOut()
    /** Kısa titreşim, [repeat] kez. */
    class Hint(val repeat: Int) : SaaOut()
    /** Alarm ekranını aç ve titret. */
    object AlarmShow : SaaOut()
    /** Alarm sürerken titreşimi yinele. */
    object AlarmBuzz : SaaOut()
    /** Alarm ekranını kapat. */
    object AlarmHide : SaaOut()
    /** Sleep as Android'e "alarmı kapat". */
    object Dismiss : SaaOut()
    /** Sleep as Android'e "alarmı ertele". */
    object Snooze : SaaOut()
}

/**
 * Takip ve alarm durumu. Zaman milisaniye, duvar saati (Sleep as Android'in TIMESTAMP ekiyle aynı).
 * Nabız akışı yalnızca takip sürerken, nabız ayarı açıkken, band hazırken ve duraklatma yokken açık kalır.
 * Nabız değerleri yalnızca pakete girer; sayaçlar log içindir.
 */
class SaaController(var hrEnabled: Boolean = true, var moveEnabled: Boolean = false) {
    var tracking = false
        private set
    var streaming = false
        private set
    var bandReady = false
        private set
    var alarmActive = false
        private set
    var moving = false
        private set

    private val movement = SaaMovement()
    val accelSamples: Int get() = movement.samples
    val moveWindows: Int get() = movement.windows
    var movePackets = 0; private set

    private var suspended = false
    private var pausedUntil = 0L
    private var batchSize = DEFAULT_BATCH
    private val buffer = ArrayList<Float>()
    private var lastFlushAt = 0L
    private var lastEventAt = 0L
    private var lastStartAt = 0L
    private var alarmAt: Long? = null
    private var alarmStartedAt = 0L
    private var lastBuzzAt = 0L

    /** Oturum sayaçları (yalnızca sayı). */
    var events = 0; private set
    var valid = 0; private set
    var packets = 0; private set
    var restarts = 0; private set

    /** Paket aralığı: batch boyu × 10 sn, 10 sn ile 5 dk arası. */
    val periodMs: Long get() = (batchSize.coerceIn(1, 30)) * SAMPLE_MS

    fun paused(now: Long): Boolean = suspended || pausedUntil > now

    fun onCheckConnected(): List<SaaOut> = if (bandReady) listOf(SaaOut.ConfirmConnected) else emptyList()

    fun onStartTracking(now: Long): List<SaaOut> {
        if (!tracking) {
            tracking = true
            suspended = false
            pausedUntil = 0L
            buffer.clear()
            events = 0; valid = 0; packets = 0; restarts = 0
            movePackets = 0
            lastFlushAt = now
        }
        val out = ArrayList<SaaOut>()
        if (bandReady) out += SaaOut.ConfirmConnected
        out += sync(now)
        return out
    }

    fun onStopTracking(now: Long): List<SaaOut> {
        tracking = false
        return sync(now)
    }

    /** SET_PAUSE: [until] geçmişteyse ya da 0 ise duraklatma biter. */
    fun onPause(until: Long, now: Long): List<SaaOut> {
        pausedUntil = if (until > now) until else 0L
        return sync(now)
    }

    fun onSuspended(value: Boolean, now: Long): List<SaaOut> {
        suspended = value
        if (!value) pausedUntil = 0L
        return sync(now)
    }

    fun onBatchSize(size: Long) {
        batchSize = size.coerceIn(1, 30)
    }

    fun onSettings(hr: Boolean, now: Long, move: Boolean = moveEnabled): List<SaaOut> {
        hrEnabled = hr
        moveEnabled = move
        return sync(now)
    }

    /** Banddan ivme örnekleri (mili-g). Hareket akışı bizim değilse false. */
    fun onAccel(samples: List<B8Workout.Accel>, now: Long): Boolean {
        if (!moving) return false
        for (a in samples) movement.add(a, now)
        return true
    }

    /** Band hazır oldu ya da koptu. Koptuysa banda komut gidemez; akış yeniden bağlanınca yeniden başlar. */
    fun onBandReady(ready: Boolean, now: Long): List<SaaOut> {
        bandReady = ready
        if (ready) return sync(now)
        val out = ArrayList<SaaOut>()
        flushInto(out, now)
        streaming = false
        if (moving) {
            moveFlushInto(out)
            moving = false
        }
        if (alarmActive) {
            alarmActive = false
            alarmAt = null
        }
        return out
    }

    /**
     * 8/47 olayı. [bpm] geçerli nabız ya da null. Dönüş: (akış bizim isteğimizle mi açık, yapılacaklar).
     * Akış istenmiyorsa uygulama bandı durdurabilir.
     */
    fun onHeartRate(bpm: Int?, now: Long): Pair<Boolean, List<SaaOut>> {
        if (!streaming) return false to emptyList()
        events++
        lastEventAt = now
        if (bpm != null && !paused(now)) {
            buffer += bpm.toFloat()
            valid++
        }
        val out = ArrayList<SaaOut>()
        if (now - lastFlushAt >= periodMs) flushInto(out, now)
        return true to out
    }

    fun onStartAlarm(delayMs: Int, now: Long): List<SaaOut> {
        // -1: alarm saatte kapalı, titreşim yok.
        if (delayMs < 0) return emptyList()
        alarmAt = now + delayMs
        return onTick(now)
    }

    fun onStopAlarm(): List<SaaOut> {
        val out = ArrayList<SaaOut>()
        if (alarmActive) out += SaaOut.AlarmHide
        alarmActive = false
        alarmAt = null
        return out
    }

    /** Bantta alarm ekranında reddet (kapat) ya da sessize al (ertele). Alarm yoksa null: olay başkasınındır. */
    fun onBandAlarmAction(dismiss: Boolean): List<SaaOut>? {
        if (!alarmActive) return null
        val out = onStopAlarm().toMutableList()
        out += if (dismiss) SaaOut.Dismiss else SaaOut.Snooze
        return out
    }

    fun onHint(repeat: Int): List<SaaOut> =
        if (bandReady) listOf(SaaOut.Hint(repeat.coerceIn(1, MAX_HINT_REPEAT))) else emptyList()

    /** Düzenli denetim: paket gönderme, sessiz kalan akışı yeniden başlatma, duraklatma bitişi, alarm tekrarı. */
    fun onTick(now: Long): List<SaaOut> {
        val out = ArrayList<SaaOut>()
        out += sync(now)
        if (streaming) {
            if (now - lastEventAt >= WATCHDOG_MS && now - lastStartAt >= WATCHDOG_MS) {
                out += SaaOut.HrStart
                lastStartAt = now
                restarts++
            }
            if (now - lastFlushAt >= periodMs) flushInto(out, now)
        }
        if (moving) movement.tick(now, batchSize.toInt(), paused(now))?.let {
            out += SaaOut.SendMove(it.max, it.raw)
            movePackets++
        }
        val at = alarmAt
        if (at != null && bandReady) {
            if (!alarmActive && now >= at) {
                alarmActive = true
                alarmStartedAt = now
                lastBuzzAt = now
                out += SaaOut.AlarmShow
            } else if (alarmActive) {
                if (now - alarmStartedAt >= ALARM_MAX_MS) {
                    out += onStopAlarm()
                } else if (now - lastBuzzAt >= ALARM_REPEAT_MS) {
                    lastBuzzAt = now
                    out += SaaOut.AlarmBuzz
                }
            }
        }
        return out
    }

    /** Bir sonraki denetime kalan süre; denetim gerekmiyorsa null. */
    fun nextTickMs(now: Long): Long? {
        val at = alarmAt
        return when {
            alarmActive -> ALARM_REPEAT_MS
            at != null -> (at - now).coerceIn(500L, TICK_MS)
            tracking || moving -> TICK_MS
            else -> null
        }
    }

    private fun sync(now: Long): List<SaaOut> {
        val want = tracking && hrEnabled && bandReady && !paused(now)
        val out = ArrayList<SaaOut>()
        if (want && !streaming) {
            streaming = true
            lastStartAt = now
            lastEventAt = now
            lastFlushAt = now
            out += SaaOut.HrStart
        } else if (!want && streaming) {
            flushInto(out, now)
            streaming = false
            out += SaaOut.HrStop
        }
        // Hareket duraklatmada da sürer: belge duraklatmada 0 değer ister.
        val wantMove = tracking && moveEnabled && bandReady
        if (wantMove && !moving) {
            moving = true
            movement.start(now)
            out += SaaOut.MoveStart
        } else if (!wantMove && moving) {
            moveFlushInto(out)
            moving = false
            out += SaaOut.MoveStop
        }
        return out
    }

    private fun moveFlushInto(out: MutableList<SaaOut>) {
        movement.flush()?.let {
            out += SaaOut.SendMove(it.max, it.raw)
            movePackets++
        }
    }

    private fun flushInto(out: MutableList<SaaOut>, now: Long) {
        lastFlushAt = now
        if (buffer.isEmpty()) return
        out += SaaOut.SendHr(buffer.toFloatArray())
        buffer.clear()
        packets++
    }

    companion object {
        /** Sleep as Android'in varsayılanı: 12 değer × 10 sn = 2 dk. */
        const val DEFAULT_BATCH = 12L
        const val SAMPLE_MS = 10_000L
        const val TICK_MS = 10_000L
        /** Bu süre olay gelmezse 8/45 yeniden gönderilir. */
        const val WATCHDOG_MS = 60_000L
        const val ALARM_REPEAT_MS = 5_000L
        /** Alarm titreşimi en çok bu kadar sürer (Sleep as Android durdurmasa da). */
        const val ALARM_MAX_MS = 5 * 60_000L
        const val MAX_HINT_REPEAT = 5
    }
}
