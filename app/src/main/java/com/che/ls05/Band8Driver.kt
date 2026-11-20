package com.che.ls05

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.che.ls05.protocol.AuthOutcome
import com.che.ls05.protocol.UteWeather
import com.che.ls05.protocol.WatchType
import com.che.ls05.protocol.DndSetting
import com.che.ls05.protocol.WatchEvent
import com.che.ls05.protocol.band8.B8Alarm
import com.che.ls05.protocol.band8.B8AlarmList
import com.che.ls05.protocol.band8.B8Alarms
import com.che.ls05.protocol.band8.B8Battery
import com.che.ls05.protocol.band8.B8Calls
import com.che.ls05.protocol.band8.B8Command
import com.che.ls05.protocol.band8.B8DeviceInfo
import com.che.ls05.protocol.band8.B8Dnd
import com.che.ls05.protocol.band8.B8Messages
import com.che.ls05.protocol.band8.B8ForecastDay
import com.che.ls05.protocol.band8.B8Music
import com.che.ls05.protocol.band8.B8Notification
import com.che.ls05.protocol.band8.B8RawSetting
import com.che.ls05.protocol.band8.B8Realtime
import com.che.ls05.protocol.band8.B8Standing
import com.che.ls05.protocol.band8.B8Text
import com.che.ls05.protocol.band8.B8Vibration
import com.che.ls05.protocol.band8.B8Type
import com.che.ls05.protocol.band8.B8Weather
import com.che.ls05.protocol.band8.B8WeatherMeta
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Gelişmiş > Band 8 denemeleri. Her dokunuş tek komut gönderir. */
enum class Band8Probe(val label: String) {
    MISSED_CALL("cevapsız arama 7/0"),
    EMOJI_TEST("emoji testi 7/0"),
    VIBRATION_TEST("titreşim deseni testi 2/59"),
    LIVE_HR_30S("canlı nabız 30 sn 8/45"),
    ALARM_LIST("alarm listesi 17/0"),
    ALARM_CREATE("test alarmı oluştur 17/1"),
    ALARM_EDIT("test alarmını düzenle 17/2"),
    ALARM_DELETE("test alarmını sil 17/4"),
    ALARM_CLEANUP("test alarmlarını temizle 17/4"),
    DND_ON("rahatsız etme aç 2/23"),
    DND_OFF("rahatsız etme kapat 2/23"),
    DND_SCHEDULE("uyku modu 22:00-07:00 17/9"),
    DND_SCHEDULE_OFF("uyku modu zamanlamasını kapat 17/9"),
    WEATHER_LOCATION("hava konumu ekle 10/7"),
    WEATHER_NOW("anlık hava 10/0"),
    WEATHER_FORECAST("günlük tahmin 10/1"),
    IDLE_READ("hareketsizlik ayarını oku 8/12"),
    IDLE_ON("hareketsizlik uyarısı aç 8/13"),
    IDLE_OFF("hareketsizlik uyarısı kapat 8/13"),
    MUSIC("test şarkı bilgisi 18/1"),
    FETCH_NOW("sağlık dosyalarını çek 8/1"),
}

/**
 * Band 8 bağlantısı ve cihaza özgü komutlar.
 */
class Band8Driver(
    context: Context,
    private val listener: DriverListener,
) : WatchDriver, Band8Ble.Listener {
    override val type = WatchType.BAND8
    private val watchId = WatchRegistry.active(context)?.id
        ?: error("Band 8 için etkin saat kaydı bulunamadı")
    private val ble = Band8Ble(context, this, watchId)
    private val packageName = context.packageName
    private val main = Handler(Looper.getMainLooper())
    private var callTestActive = false
    private val appContext = context.applicationContext
    private val music: Band8Music by lazy {
        Band8Music(appContext, main, { listener.onLog(it) }) { ble.sendFeature(it, "müzik bilgisi 18/1") }
    }

    /** Gönderilen düzenlemenin saati; yanıt gelince kaydedilir. */
    private var pendingEditTime: Pair<Int, Int>? = null

    /** Banddan okunan hareketsizlik ayarı (deneme). Değer loglanmaz. */
    private var standing: B8RawSetting? = null

    /** Ayarlar kartından giden zamanlı rahatsız etme (17/9) yanıt bekliyor. */
    private var dndSettingPending = false
    private val stopCallTest = Runnable {
        if (callTestActive) {
            listener.onLog("Band 8 arama ekranı testi: 15 saniyelik güvenlik süresi doldu")
            testCallScreen(start = false)
        }
    }

    override val isConnected: Boolean get() = ble.isConnected
    override val isReady: Boolean get() = ble.isReady
    override val busy = false

    fun resumeAuth(): Boolean = ble.resumeAuth()

    fun testFindBand(start: Boolean): Boolean = ble.testFindBand(start)

    fun testCallScreen(start: Boolean): Boolean {
        val accepted = ble.testCallScreen(start, notificationTime(System.currentTimeMillis()))
        if (!accepted) return false
        main.removeCallbacks(stopCallTest)
        callTestActive = start
        if (start) main.postDelayed(stopCallTest, CALL_TEST_MAX_MS)
        return true
    }

    /** Gönderildiyse null, değilse kullanıcıya gösterilecek kısa neden. */
    fun probe(p: Band8Probe): String? {
        if (!ble.isReady) return "Band 8 hazır değil."
        if (p == Band8Probe.FETCH_NOW) {
            return if (ble.fetchHealthNow()) null else "Çekme zaten sürüyor ya da oturum hazır değil."
        }
        if (p == Band8Probe.LIVE_HR_30S) return liveHrProbe()
        val now = ZonedDateTime.now()
        val command: ByteArray = when (p) {
            Band8Probe.MISSED_CALL -> B8Calls.missedCall(TEST_CALLER, notificationTime(System.currentTimeMillis()))
            Band8Probe.VIBRATION_TEST -> B8Vibration.pattern(B8Vibration.TEST)
            Band8Probe.EMOJI_TEST -> {
                val t = System.currentTimeMillis()
                B8Messages.notification(B8Notification(packageName, "Finn's Watch", "Emoji testi", B8Text.EMOJI_TEST, notificationTime(t), (t / 1000).toInt(), "emoji:$t"))
            }
            Band8Probe.ALARM_LIST -> B8Alarms.list()
            Band8Probe.ALARM_CREATE -> {
                // Çift alarmı önler: her oluşturmadan önce taze liste gerekir, test alarmı varsa yenisi kurulmaz.
                val list = freshAlarms() ?: return "Önce alarm listesini oku."
                testAlarm(list)?.let { return "Test alarmı zaten var. Düzenle ya da sil." }
                list.max?.let { if (list.alarms.size >= it) return "Bantta boş alarm yeri yok." }
                val (h, m) = B8Alarms.plusMinutes(now.hour, now.minute, 2)
                Prefs.setBand8TestAlarm(appContext, h, m)
                B8Alarms.create(h, m)
            }
            Band8Probe.ALARM_EDIT -> {
                val list = freshAlarms() ?: return "Önce alarm listesini oku."
                val a = testAlarm(list) ?: run {
                    listener.onLog("Band 8 alarm düzenleme: listede test alarmı bulunamadı (deneme imzalı ${B8Alarms.probeCandidates(list).size})")
                    return "Listede test alarmı yok. Önce kur, sonra listeyi oku."
                }
                val (h, m) = B8Alarms.plusMinutes(now.hour, now.minute, 3)
                // Yeni saat, band düzenlemeyi kabul edince kaydedilir; reddederse eski saatle bulunmaya devam eder.
                pendingEditTime = h to m
                listener.onLog("Band 8 alarm düzenleme gönderiliyor: kimlik=${a.id}")
                B8Alarms.edit(a.id, h, m)
            }
            Band8Probe.ALARM_DELETE -> {
                val list = freshAlarms() ?: return "Önce alarm listesini oku."
                val a = testAlarm(list) ?: return "Listede test alarmı yok. Önce listeyi oku."
                B8Alarms.delete(listOf(a.id))
            }
            Band8Probe.ALARM_CLEANUP -> {
                val c = alarmCleanupCandidates() ?: return "Önce alarm listesini oku."
                if (c.isEmpty()) return "Silinecek test alarmı yok."
                B8Alarms.delete(c.map { it.id })
            }
            Band8Probe.DND_ON -> B8Dnd.now(true)
            Band8Probe.DND_OFF -> B8Dnd.now(false)
            Band8Probe.DND_SCHEDULE -> B8Dnd.sleepSchedule(true, 22, 0, 7, 0)
            Band8Probe.DND_SCHEDULE_OFF -> B8Dnd.sleepSchedule(false, 22, 0, 7, 0)
            Band8Probe.WEATHER_LOCATION, Band8Probe.WEATHER_NOW, Band8Probe.WEATHER_FORECAST -> {
                val f = WeatherSync.cached(appContext) ?: return "Önce Ayarlar > Hava durumu > Güncelle."
                val city = Prefs.weatherCityName(appContext).ifBlank { "Hava" }
                val meta = B8WeatherMeta(
                    Instant.ofEpochMilli(f.fetchedAt).atZone(ZoneId.systemDefault()).truncatedTo(ChronoUnit.SECONDS)
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    city, WEATHER_KEY,
                )
                when (p) {
                    Band8Probe.WEATHER_LOCATION -> B8Weather.addLocation(WEATHER_KEY, city)
                    Band8Probe.WEATHER_NOW -> B8Weather.current(meta, B8Weather.fromUte(f.todayCode), f.current)
                    else -> B8Weather.forecast(meta, f.days.map {
                        val code = B8Weather.fromUte(it.code)
                        B8ForecastDay(code, code, it.low, it.high)
                    })
                }
            }
            Band8Probe.IDLE_READ -> B8Standing.read()
            Band8Probe.IDLE_ON, Band8Probe.IDLE_OFF -> {
                val raw = standing ?: return "Önce hareketsizlik ayarını oku."
                B8Standing.write(raw, p == Band8Probe.IDLE_ON) ?: return "Okunan ayar beklenmeyen biçimde; gönderilmedi."
            }
            Band8Probe.MUSIC -> B8Music.info(TEST_TRACK, "Finn's Watch", playing = true, durationSec = 180, volumePercent = 50)
            Band8Probe.FETCH_NOW, Band8Probe.LIVE_HR_30S -> return null
        }
        if (!ble.sendProbe(command, p.label)) return "Band 8 denemesi gönderilemedi. Logu kontrol et."
        // Alarm kimlikleri sıra numarasıdır; değişiklikten sonra liste yeniden okunmalı.
        if (p == Band8Probe.ALARM_CREATE || p == Band8Probe.ALARM_EDIT || p == Band8Probe.ALARM_DELETE || p == Band8Probe.ALARM_CLEANUP) {
            alarmListFresh = false
        }
        return null
    }

    private fun freshAlarms(): B8AlarmList? = alarmList?.takeIf { alarmListFresh }

    // ---------- Alarm kartı (17/0, 17/1, 17/2, 17/4 cihazda doğrulandı) ----------

    /** Kartın gösterdiği liste; değişiklikten sonra yenilenene kadar null. */
    fun alarmsForCard(): B8AlarmList? = freshAlarms()

    /** 17/0: listeyi oku. */
    fun alarmRefresh(): String? {
        if (!ble.isReady) return "Band 8 hazır değil."
        return if (ble.sendFeature(B8Alarms.list(), "alarm listesi 17/0")) null else "Alarm listesi istenemedi."
    }

    /** [id] null ise yeni alarm. Gönderilince liste eskir; yanıt gelince kendiliğinden yeniden okunur. */
    fun alarmSave(id: Int?, spec: com.che.ls05.protocol.band8.B8AlarmSpec): String? {
        if (!ble.isReady) return "Band 8 hazır değil."
        val list = freshAlarms() ?: return "Alarm listesi yenileniyor; biraz bekle."
        if (id == null) list.max?.let { if (list.alarms.size >= it) return "Bantta boş alarm yeri yok." }
        val bytes = if (id == null) B8Alarms.create(spec) else B8Alarms.edit(id, spec)
        if (!ble.sendFeature(bytes, if (id == null) "alarm ekle 17/1" else "alarm düzenle 17/2")) return "Alarm gönderilemedi."
        alarmListFresh = false
        refreshAfterChange = true
        return null
    }

    fun alarmDelete(id: Int): String? {
        if (!ble.isReady) return "Band 8 hazır değil."
        freshAlarms() ?: return "Alarm listesi yenileniyor; biraz bekle."
        if (!ble.sendFeature(B8Alarms.delete(listOf(id)), "alarm sil 17/4")) return "Alarm silinemedi."
        alarmListFresh = false
        refreshAfterChange = true
        return null
    }

    // ---------- Hareketsizlik uyarısı kartı (deneysel; 8/12 oku, 8/13 yaz) ----------

    private var idleReadAfterWrite = false

    /** Okunan ayarın açık mı ve saat aralığı; okunmadıysa ya da biçim beklenmedikse null. */
    fun idleWindow(): B8Standing.Window? = standing?.let { B8Standing.window(it) }

    fun idleRead(): String? {
        if (!ble.isReady) return "Band 8 hazır değil."
        return if (ble.sendFeature(B8Standing.read(), "hareketsizlik ayarı oku 8/12")) null else "Ayar istenemedi."
    }

    fun idleSave(enabled: Boolean, startH: Int, startM: Int, endH: Int, endM: Int): String? {
        if (!ble.isReady) return "Band 8 hazır değil."
        val raw = standing ?: return "Önce ayar okunmalı."
        val bytes = B8Standing.writeWindow(raw, enabled, startH, startM, endH, endM) ?: return "Okunan ayar beklenmeyen biçimde; gönderilmedi."
        if (!ble.sendFeature(bytes, "hareketsizlik ayarı yaz 8/13")) return "Ayar gönderilemedi."
        idleReadAfterWrite = true
        return null
    }

    /** Kart değişikliğinden sonra yanıt gelince liste bir kez yeniden okunur. */
    private var refreshAfterChange = false

    private fun refreshIfChanged() {
        if (!refreshAfterChange) return
        refreshAfterChange = false
        alarmRefresh()
    }

    private fun testAlarm(list: B8AlarmList): B8Alarm? {
        val t = Prefs.band8TestAlarm(appContext) ?: return null
        return B8Alarms.findTest(list, t.first, t.second)
    }

    /** "Test alarmlarını temizle" adayları: deneme imzasına uyanlar. Taze liste yoksa null. Saatler yalnızca ekranda. */
    fun alarmCleanupCandidates(): List<B8Alarm>? = freshAlarms()?.let { B8Alarms.probeCandidates(it) }

    /** Deneme yanıtları. Loga yalnızca sayı, alan numarası ve durum yazılır. */
    override fun onBandCommand(command: B8Command) {
        when {
            B8Realtime.isEvent(command) -> onRealtime(command)
            command.matches(B8Type.SCHEDULE, 0) -> B8Alarms.parseList(command)?.let { list ->
                alarmList = list
                alarmListFresh = true
                val test = testAlarm(list)
                // Tanı: alarm başına kimlik ve alan biçimi; saat ve değer yazılmaz.
                for (line in B8Alarms.describe(command)) listener.onLog("Band 8 alarm listesi biçimi: $line")
                WatchHub.settingsChanged()
                if (Prefs.band8TestAlarm(appContext) == null) listener.onLog("Band 8 test alarmı saati kayıtlı değil")
                listener.onLog("Band 8 alarm listesi: ${list.alarms.size} alarm, en çok ${list.max ?: "?"}, " +
                    "deneme imzalı ${B8Alarms.probeCandidates(list).size}, test alarmı ${if (test != null) "bulundu" else "yok"}")
            }
            command.matches(B8Type.SCHEDULE, 1) -> {
                listener.onLog("Band 8 alarm oluşturma yanıtı: durum=${command.status ?: "yok"}; kimlik için listeyi oku")
                refreshIfChanged()
            }
            command.matches(B8Type.SCHEDULE, 2) -> {
                val st = command.status
                val t = pendingEditTime
                pendingEditTime = null
                if (t != null && (st == null || st == 0)) Prefs.setBand8TestAlarm(appContext, t.first, t.second)
                listener.onLog("Band 8 alarm düzenleme yanıtı: durum=${st ?: "yok"}; yeni saat ${if (t != null && (st == null || st == 0)) "kaydedildi" else "kaydedilmedi"}")
                refreshIfChanged()
            }
            command.matches(B8Type.NOTIFICATION, 2) || command.matches(B8Type.NOTIFICATION, 5) -> {
                val reject = command.subtype == 2
                listener.onLog("Band 8 arama olayı: tür=7, altTür=${command.subtype} (${if (reject) "reddet" else "sessize al"})")
                listener.onBandCallAction(reject)
            }
            command.matches(B8Type.SCHEDULE, 4) -> {
                listener.onLog("Band 8 alarm silme yanıtı: durum=${command.status ?: "yok"}; listeyi yeniden oku")
                refreshIfChanged()
            }
            command.matches(B8Type.SCHEDULE, 9) -> {
                listener.onLog("Band 8 zamanlı rahatsız etme yanıtı: durum=${command.status ?: "yok"}")
                if (dndSettingPending) {
                    dndSettingPending = false
                    listener.onEvent(WatchEvent.SettingAck(DndSetting.KEY, null))
                }
            }
            command.matches(B8Type.HEALTH, 12) -> {
                standing = B8Standing.parse(command)
                val s = standing
                listener.onLog(if (s != null) "Band 8 hareketsizlik ayarı okundu: Health alanı ${s.field}, alanlar ${s.shape()}"
                    else "Band 8 hareketsizlik ayarı beklenen biçimde değil")
                WatchHub.settingsChanged()
            }
            command.matches(B8Type.HEALTH, 13) -> {
                listener.onLog("Band 8 hareketsizlik ayarı yazma yanıtı: durum=${command.status ?: "yok"}")
                if (idleReadAfterWrite) {
                    idleReadAfterWrite = false
                    idleRead()
                }
            }
            command.type == B8Type.MUSIC -> {
                // Yapı tahmin: alan biçimi loga yazılır (metin yok), istek yanıtlanır, tuş uygulanır.
                listener.onLog("Band 8 müzik olayı: altTür=${command.subtype}, alanlar=${B8Music.describe(command)}")
                val key = B8Music.mediaKey(command)
                when {
                    key != null -> music.onKey(key)
                    B8Music.isStateRequest(command) -> music.onRequest()
                }
            }
        }
    }

    // ---------- Canlı nabız (8/45 başlat, 8/46 durdur, 8/47 olay) ----------

    private var liveProbeActive = false
    private var liveProbeEvents = 0
    private var liveProbeValid = 0
    private var lastUnwantedStopAt = 0L
    private val liveProbeEnd = Runnable {
        if (!liveProbeActive) return@Runnable
        liveProbeActive = false
        // Sleep as Android akışı açıksa durdurulmaz.
        if (!SleepAsAndroidBridge.streaming) realtimeHr(false)
        listener.onLog("Band 8 canlı nabız denemesi bitti: 30 sn, olay $liveProbeEvents, geçerli nabız $liveProbeValid")
    }

    private fun liveHrProbe(): String? {
        if (SleepAsAndroidBridge.streaming) return "Sleep as Android takibi sürüyor; deneme yapılmadı."
        if (liveProbeActive) return "Deneme zaten sürüyor."
        if (!realtimeHr(true)) return "Band 8 denemesi gönderilemedi. Logu kontrol et."
        liveProbeActive = true
        liveProbeEvents = 0
        liveProbeValid = 0
        main.postDelayed(liveProbeEnd, LIVE_PROBE_MS)
        return null
    }

    /** Sleep as Android ve deneme için canlı akışı başlatır ya da durdurur. */
    fun realtimeHr(start: Boolean): Boolean =
        ble.isReady && ble.sendFeature(if (start) B8Realtime.start() else B8Realtime.stop(),
            if (start) "canlı nabız başlat 8/45" else "canlı nabız durdur 8/46")

    /** 2/59 titreşim deseni (cihazda doğrulandı, build 191). */
    fun vibrate(steps: List<Pair<Boolean, Int>>, label: String): Boolean =
        ble.isReady && ble.sendFeature(B8Vibration.pattern(steps), label)

    /** Sleep as Android alarmı: bantta "Alarm" adlı arama ekranı (reddet = kapat, sessize al = ertele). */
    fun alarmScreen(show: Boolean): Boolean {
        if (!ble.isReady) return false
        clearCallTest()
        val bytes = if (show) B8Messages.incomingCall(ALARM_TITLE, "", notificationTime(System.currentTimeMillis())) else B8Messages.callEnded()
        return ble.sendFeature(bytes, if (show) "alarm ekranı 7/0" else "alarm ekranı kapat 7/1")
    }

    /** 8/47: nabız değeri loga yazılmaz, yalnızca sayılır. */
    private fun onRealtime(command: B8Command) {
        val hr = B8Realtime.validHeartRate(B8Realtime.parse(command)?.heartRate)
        if (liveProbeActive) {
            liveProbeEvents++
            if (hr != null) liveProbeValid++
        }
        val ours = SleepAsAndroidBridge.onHeartRate(hr)
        if (!ours && !liveProbeActive) {
            // Kimsenin istemediği akış (ör. uygulama yeniden başladı) pil harcamasın.
            val now = System.currentTimeMillis()
            if (now - lastUnwantedStopAt >= 60_000L) {
                lastUnwantedStopAt = now
                listener.onLog("Band 8 istenmeyen canlı akış; durduruluyor")
                realtimeHr(false)
            }
        }
    }

    private fun clearLiveProbe() {
        main.removeCallbacks(liveProbeEnd)
        liveProbeActive = false
    }

    override fun onForeignClient() = listener.onForeignClient()

    private fun clearCallTest() {
        main.removeCallbacks(stopCallTest)
        callTestActive = false
    }

    override fun startScan() = ble.scan()
    override fun connect(device: BluetoothDevice, autoConnect: Boolean) = ble.connect(device, autoConnect)
    override fun disconnect() { clearCallTest(); clearLiveProbe(); ble.disconnect() }
    override fun forceClose(reason: String) { clearCallTest(); clearLiveProbe(); ble.forceClose() }
    override fun release(reason: String) {
        clearCallTest()
        clearLiveProbe()
        music.stop()
        ble.release()
    }

    override fun sendNotification(title: String, body: String, vibrate: Boolean, type: Int): Boolean {
        val now = System.currentTimeMillis()
        return ble.sendAppNotification(B8Notification(
            packageName, "Finn's Watch", title, body, notificationTime(now), now.toInt(), "test:$now",
        ))
    }

    override fun sendAppNotification(note: WatchHub.Note, vibrate: Boolean): Boolean {
        if (note.kind == WatchHub.NoteKind.MISSED_CALL) {
            // 20.11.2026: "phone" paketinden normal bildirim bantta göründü. Metin loga yazılmaz.
            val id = note.androidId.takeIf { it != 0 } ?: ((note.at / 1000).toInt() and 0x7FFFFFFF).coerceAtLeast(1)
            return ble.sendFeature(B8Calls.missedCall(note.body, notificationTime(note.at), id), "cevapsız arama 7/0")
        }
        val key = note.androidKey ?: run {
            listener.onLog("Band 8 bildirim atlandı: Android anahtarı yok, tür=${note.kind}")
            return false
        }
        if (note.kind != WatchHub.NoteKind.MESSAGE) {
            listener.onLog("Band 8 bildirim atlandı: tür=${note.kind}")
            return false
        }
        return ble.sendAppNotification(B8Notification(
            note.pkg, note.appLabel, note.title, note.body, notificationTime(note.at), note.androidId, key,
        ))
    }

    private fun notificationTime(at: Long): String =
        NOTIFICATION_TIME.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))

    /** Gerçek gelen arama: bantta arama ekranı. Arayan adı ya da numarası loga yazılmaz. */
    override fun sendIncomingCall(name: String): Boolean {
        clearCallTest()
        return ble.sendFeature(B8Messages.incomingCall(name, "", notificationTime(System.currentTimeMillis())), "gelen arama 7/0")
    }

    /** Arama cevaplandı, reddedildi ya da bitti: bantta arama ekranını kapatır. */
    override fun sendCallStop(): Boolean = ble.sendFeature(B8Messages.callEnded(), "arama bitti 7/1")
    override fun sendTime(now: ZonedDateTime, label: String) = Unit
    /** Ayarlar kartı: rahatsız etme saatleri zamanlı uyku modu (17/9) olarak gider. Diğer ayarlar Band 8'de yok. */
    override fun sendSetting(key: String, value: String): Boolean {
        if (key != DndSetting.KEY) return false
        val d = DndSetting.decode(value) ?: return false
        val ok = ble.sendFeature(B8Dnd.sleepSchedule(d.enabled, d.startHour, d.startMinute, d.endHour, d.endMinute), "rahatsız etme saatleri 17/9")
        if (ok) dndSettingPending = true
        return ok
    }
    override fun requestHistory(from: LocalDate, includeSleep: Boolean) = Unit
    override fun findStart() = Unit
    override fun findStop(reason: String) = Unit
    override fun sendMusicState(playing: Boolean, volumePercent: Int) = Unit
    override fun sendWeather(current: Int, days: List<UteWeather.Day>, city: String) = Unit

    override fun onLog(message: String) = listener.onLog(message)
    override fun onStatus(message: String) = listener.onStatus(message)
    override fun onFound(device: BluetoothDevice, name: String, rssi: Int) =
        listener.onDeviceFound(device, name, rssi)

    override fun onReady(ready: Boolean) {
        if (!ready) clearCallTest()
        if (ready) music.start() else music.stop()
        // Alarm kartı için liste bağlanınca bir kez okunur (yalnızca okuma).
        if (ready) main.postDelayed({ if (ble.isReady && freshAlarms() == null) alarmRefresh() }, ALARM_READ_DELAY_MS)
        if (ready) listener.onAuth(AuthOutcome(AuthOutcome.Kind.OK))
        listener.onReadyChanged(ready)
    }

    override fun onDisconnected(userInitiated: Boolean, status: Int) {
        clearCallTest()
        clearLiveProbe()
        alarmListFresh = false
        dndSettingPending = false
        listener.onDisconnected(userInitiated, status)
    }

    override fun onKeyRequired() = listener.onBandKeyRequired()

    override fun onAuthFailed(message: String) {
        listener.onAuth(AuthOutcome(AuthOutcome.Kind.BLOCK, blockMessage = message))
        listener.onStatus(message)
    }

    override fun onBattery(value: B8Battery) {
        value.levelPercent?.takeIf { it in 0..100 }?.let { WatchHub.bandBattery(it) }
    }

    override fun onDeviceInfo(value: B8DeviceInfo) {
        value.firmware?.let { WatchHub.bandFirmware(it) }
        // Cihazın seri numarası kişisel bilgidir; loga yazılmaz.
        listener.onLog("Band 8 cihaz bilgisi okundu")
    }

    private companion object {
        const val CALL_TEST_MAX_MS = 15_000L
        const val ALARM_READ_DELAY_MS = 4_000L
        const val LIVE_PROBE_MS = 30_000L
        const val ALARM_TITLE = "Alarm"

        /** Alarm listesi süreç boyunca tutulur (sürücü yeniden bağlanınca yenilenir); taze değilse değişiklik yapılmaz. */
        private var alarmList: B8AlarmList? = null
        private var alarmListFresh = false
        const val TEST_CALLER = "Test arama"
        const val TEST_TRACK = "Test şarkı"
        const val WEATHER_KEY = "finnswatch"
        val NOTIFICATION_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss", Locale.ROOT)
    }
}
