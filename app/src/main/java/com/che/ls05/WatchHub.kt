package com.che.ls05

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.che.ls05.protocol.CallTracker
import com.che.ls05.protocol.UteFrame
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Servis, ekran ve bildirim dinleyici arasında ortak durum. Tüm çağrılar ana iş parçacığında işlenir.
 * Log yalnızca bellekte tutulur; bildirim içerikleri ve kimlik loga yazılmaz.
 */
object WatchHub {
    /** Takvim hatırlatmamız ile takvim uygulamasının bildirimi aynı başlıkla 3 dk içinde gelirse yalnızca biri gider. Bellekte, başlık loga yazılmaz. */
    val calendarDedupe = com.che.ls05.protocol.TitleDedupe(180_000)

    /** Son gelen aramanın bildirimindeki "Reddet" eylemi (saatten reddetmek için). */
    @Volatile var declineAction: android.app.PendingIntent? = null
    @Volatile var declineKey: String? = null
    @Volatile var declineAt: Long = 0
    @Volatile var declineLabel: String = ""
    /** Reddet eyleminin geldiği paket: VoIP aramada telefon araması kapatılmasın diye. */
    @Volatile var declinePkg: String = ""


    interface Observer {
        fun onLog(line: String) {}
        fun onStatus(status: String) {}
        fun onDeviceFound(device: BluetoothDevice, name: String, rssi: Int) {}
        fun onFrame(frame: UteFrame) {}
        fun onReadyChanged(ready: Boolean) {}

        /** Saat ayarlarının eşitleme durumu, pil ya da firmware değişti. */
        fun onSettingsChanged() {}

        /** Sağlık kayıtları değişti (en çok 300 ms'de bir). */
        fun onHealthChanged() {}

        /** Kadran yükleme durumu ya da ilerlemesi değişti. */
        fun onFaceChanged() {}
        fun onBandKeyRequired() {}
    }

    /** Saate gidecek şeyin türü. */
    enum class NoteKind {
        /** Sıradan bildirim (0F + mesaj titreşimi). */
        MESSAGE,

        /** Cevapsız arama: tip 3 bildirim olarak gider, kuyruğa alınabilir. */
        MISSED_CALL,

        /** Gelen arama ekranı (0F tip 0 + arama titreşimi). Kuyruğa alınmaz: arama bitince anlamı kalmaz. */
        CALL_ALERT,

        /** Arama cevaplandı ya da bitti (0D 04 + titreşim durdur). */
        CALL_STOP,
    }

    /** İletilecek bildirim. İçerik yalnızca bellekte ve kısa süre tutulur. */
    data class Note(
        val pkg: String,
        val appLabel: String,
        val title: String,
        val body: String,
        val type: Int,
        val at: Long,
        val kind: NoteKind = NoteKind.MESSAGE,
        /** Kuyrukta en çok ne kadar bekleyebilir (alarm notu için 5 dakika). */
        val ttlMs: Long = PENDING_TTL_MS,
        val androidId: Int = 0,
        val androidKey: String? = null,
        /** Bildirim kuralının titreşimi: -1 varsayılan, 0 titreşimsiz, 1-5 sayı (yalnızca LS05). */
        val vibration: Int = -1,
        /** false: uygulama filtresi uygulanmaz (navigasyon kendi anahtarıyla açılır). */
        val appFilter: Boolean = true,
    )

    /** Çalan aramalar (bildirim anahtarına göre). Bildirim dinleyici ve servis ana iş parçacığında kullanır. */
    val calls = CallTracker()

    private val main = Handler(Looper.getMainLooper())
    private val observers = LinkedHashSet<Observer>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    val logLines = ArrayDeque<String>().apply { addLast(BuildIdentity.label) }
    val pending = ArrayDeque<Note>()

    var status: String = "Bağlı değil"
        private set
    var ready: Boolean = false
        private set

    /** Çalışan servis; yoksa null. */
    var service: WatchService? = null

    private const val MAX_LOG = 2_000
    private const val MAX_PENDING = 20
    const val PENDING_TTL_MS = 10 * 60 * 1000L

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    fun addObserver(o: Observer) = onMain { observers += o }
    fun removeObserver(o: Observer) = onMain { observers -= o }

    fun log(line: String) = onMain {
        val stamped = "${fmt.format(Date())}  $line"
        logLines.addLast(stamped)
        while (logLines.size > MAX_LOG) logLines.removeFirst()
        observers.toList().forEach { it.onLog(stamped) }
    }

    fun logForSharing(limit: Int = 400): List<String> =
        listOf(BuildIdentity.label) + logLines.filter { it != BuildIdentity.label }.takeLast(limit)

    fun setStatus(s: String) = onMain {
        status = s
        observers.toList().forEach { it.onStatus(s) }
    }

    fun setReady(r: Boolean) = onMain {
        ready = r
        observers.toList().forEach { it.onReadyChanged(r) }
    }

    fun bandKeyRequired() = onMain {
        observers.toList().forEach { it.onBandKeyRequired() }
    }

    fun deviceFound(device: BluetoothDevice, name: String, rssi: Int) = onMain {
        observers.toList().forEach { it.onDeviceFound(device, name, rssi) }
    }

    /** Kadran yükleme durumu (Ayarlar > Kadran gösterir). */
    var facePhase = FaceUpload.Phase.IDLE
        private set
    var facePercent = 0
        private set
    var faceText = ""
        private set
    /** Yüklenen ya da en son yüklenen galeri kaydı. */
    var faceId = ""
    val faceBusy: Boolean
        get() = facePhase != FaceUpload.Phase.IDLE && facePhase != FaceUpload.Phase.DONE && facePhase != FaceUpload.Phase.FAILED

    fun setFace(p: FaceUpload.Phase, percent: Int, text: String) = onMain {
        facePhase = p
        facePercent = percent
        faceText = text
        observers.toList().forEach { it.onFaceChanged() }
    }

    /** Saatin son bildirdiği pil yüzdesi; bilinmiyorsa null. */
    var battery: Int? = null
        private set

    /** Saatin son bildirdiği firmware sürümü; bilinmiyorsa null. */
    var firmware: String? = null
        private set

    fun frame(f: UteFrame) = onMain {
        when (f) {
            is UteFrame.Battery -> {
                battery = f.percent
                settingsChanged()
            }
            is UteFrame.Firmware -> {
                firmware = f.version
                settingsChanged()
            }
            else -> Unit
        }
        observers.toList().forEach { it.onFrame(f) }
    }

    /** Band 8 cevapları ortak kartlarla paylaşılır; kimlik veya seri numarası kaydedilmez. */
    fun bandBattery(percent: Int) = onMain {
        battery = percent
        settingsChanged()
    }

    /** Band 8'de başka bir istemcinin izi en son ne zaman görüldü (SystemClock.elapsedRealtime). 0: hiç. */
    @Volatile var bandForeignAt: Long = 0L
        private set

    fun bandForeignClient() = onMain {
        bandForeignAt = android.os.SystemClock.elapsedRealtime()
        observers.toList().forEach { it.onSettingsChanged() }
    }

    /** Uyarı son 30 dakikada görüldüyse true. */
    fun bandForeignRecent(): Boolean =
        bandForeignAt != 0L && android.os.SystemClock.elapsedRealtime() - bandForeignAt < 30 * 60_000L

    fun bandFirmware(version: String) = onMain {
        firmware = version
        settingsChanged()
    }

    /** Saat değişti: eski saatin pil ve firmware bilgisi atılır. */
    fun resetDevice() = onMain {
        battery = null
        firmware = null
        observers.toList().forEach { it.onSettingsChanged() }
    }

    fun settingsChanged() = onMain {
        observers.toList().forEach { it.onSettingsChanged() }
    }

    private var healthPosted = false
    private val healthRunnable = Runnable {
        healthPosted = false
        observers.toList().forEach { it.onHealthChanged() }
    }

    /** Sağlık verisi değişti. Sık gelen paketler ekranı yormasın diye en çok 300 ms'de bir bildirilir. */
    fun healthChanged() = onMain {
        if (!healthPosted) {
            healthPosted = true
            main.postDelayed(healthRunnable, 300)
        }
    }

    /**
     * Bildirim dinleyiciden gelir. Saat hazır değilse kısa kuyruğa alınır ve bağlantı hızlandırılır:
     * servis ölüyse (ör. uygulama son uygulamalardan kaydırıldı) ön plan servisi olarak başlatılır,
     * yaşıyor ama yeniden bağlanmayı bekliyorsa bekleme öne alınır. Kuyruk bağlanınca boşaltılır.
     */
    fun forward(context: Context, note: Note) = onMain {
        val s = service
        if (s != null && s.isWatchReady()) {
            s.forward(note)
            return@onMain
        }
        when (note.kind) {
            NoteKind.CALL_STOP -> {
                // Saat çalıyorsa servis durumu tutar; bağlanınca durdurur.
                s?.forward(note)
                return@onMain
            }
            NoteKind.CALL_ALERT -> log("Saat hazır değil; gelen arama saate iletilemedi.")
            else -> {
                pending.addLast(note)
                while (pending.size > MAX_PENDING) pending.removeFirst()
                log("Saat hazır değil; bildirim kuyruğa alındı (${note.appLabel}, kuyruk ${pending.size}).")
            }
        }
        if (s != null) {
            s.nudgeReconnect()
            return@onMain
        }
        if (Prefs.serviceEnabled(context) && WatchRegistry.hasActive(context)) {
            log("Saat servisi çalışmıyor; başlatılıyor.")
            if (!WatchService.start(context)) {
                log("Servis arka planda başlatılamadı. Kuyruk uygulama açılınca gönderilir; pil kısıtlamasını kapat.")
            }
        } else {
            log("Saat servisi kapalı (Durdur denmiş ya da saat seçilmemiş); kuyruk bağlanınca gönderilir.")
        }
    }

    /** Kuyrukta bekleyen ve süresi dolmamış bildirimleri sırayla verir (varsayılan 10 dakika). */
    fun drainPending(now: Long = System.currentTimeMillis()): List<Note> {
        val fresh = pending.filter { now - it.at <= it.ttlMs }
        val dropped = pending.size - fresh.size
        pending.clear()
        if (dropped > 0) log("Kuyruktaki $dropped eski bildirim atıldı (süresi doldu).")
        return fresh
    }
}
