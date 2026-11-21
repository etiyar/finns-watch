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
import com.che.ls05.protocol.band8.B8Workout
import com.che.ls05.protocol.band8.B8Standing
import com.che.ls05.protocol.band8.B8HeartRateConfig
import com.che.ls05.protocol.band8.B8Vibration
import com.che.ls05.protocol.band8.B8Sub
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

/** Gelişmiş > Band 8 denemeleri. Her dokunuş tek komut gönderir. Doğrulananlar normal ekranlara taşındı (v0.17). */
enum class Band8Probe(val label: String) {
    ALARM_CLEANUP("test alarmlarını temizle 17/4"),
    WEATHER_LOCATION("hava konumu ekle 10/7"),
    WEATHER_NOW("anlık hava 10/0"),
    WEATHER_FORECAST("günlük tahmin 10/1"),
    IDLE_READ("hareketsizlik ayarını oku 8/12"),
    IDLE_ON("hareketsizlik uyarısı aç 8/13"),
    IDLE_OFF("hareketsizlik uyarısı kapat 8/13"),
    HR_CONFIG_READ("nabız ölçüm ayarını oku 8/10"),
    HR_CONFIG_EVERY_10("nabız ölçümü her 10 dk 8/11"),
    HR_CONFIG_CONTINUOUS("nabız ölçümü sürekli 8/11"),
    HR_CONFIG_OFF("nabız ölçümü kapalı 8/11"),
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
    private val appContext = context.applicationContext
    private val music: Band8Music by lazy {
        Band8Music(appContext, main, { listener.onLog(it) }) { ble.sendFeature(it, "müzik bilgisi 18/1") }
    }

    private val icons = Band8Icons(appContext, { listener.onLog(it) }, { c, l -> ble.sendFeature(c, l) }, ble::startUpload)
    /** Her kadran komutu ve yüklemesi ağır işleri 10 sn bekletir ve çökme korumasına işlenir (build 238). */
    private val faces = Band8Faces(main, { listener.onLog(it) }, { c, l -> faceOp(); ble.sendFeature(c, l) }) { t, d, l, cb ->
        faceOp(); ble.startUpload(t, d, l, cb)
    }
    private val nav = Band8NavFace(appContext, main, faces, { ble.holdHeavy(it) }) { listener.onLog(it) }

    private fun faceOp() {
        ble.holdHeavy(Band8NavFace.QUIET_AFTER_MS)
        Band8NavFace.guard.faceOp(android.os.SystemClock.elapsedRealtime())
    }

    init { ble.heavyBlocked = { nav.busy || Band8Faces.busy } }

    /** Navigasyon kadranı (deneysel). Kare bildirim dinleyiciden gelir; Gelişmiş'te deneme ve kaldırma. */
    fun navFrame(f: Band8NavFace.Frame) { if (ble.isReady) nav.onFrame(f) }
    fun navTest(): String? = if (!ble.isReady) "Band 8 hazır değil." else nav.test()
    fun navEnd(): String? = if (!ble.isReady) "Band 8 hazır değil." else nav.end()

    /** Gelişmiş > Band 8 kadran (deneysel). Gönderildiyse null, değilse kısa neden. */
    fun faceRead(): String? = if (!ble.isReady) "Band 8 hazır değil." else faces.read()
    fun faceSet(id: String): String? = if (!ble.isReady) "Band 8 hazır değil." else faces.setActive(id)
    fun faceDelete(id: String): String? = if (!ble.isReady) "Band 8 hazır değil." else faces.delete(id)
    fun faceInstall(bytes: ByteArray): String? = if (!ble.isReady) "Band 8 hazır değil." else faces.install(bytes)

    /** Banddan okunan hareketsizlik ayarı (deneme). Değer loglanmaz. */
    private var standing: B8RawSetting? = null

    /** Banddan okunan nabız ölçüm ayarı (deneme, cihazda doğrulanmadı). Yalnızca alan biçimi loglanır. */
    private var hrConfig: B8RawSetting? = null
    private var hrConfigReadAfterWrite = false

    /** Ayarlar kartından giden zamanlı rahatsız etme (17/9) yanıt bekliyor. */
    private var dndSettingPending = false

    override val isConnected: Boolean get() = ble.isConnected
    override val isReady: Boolean get() = ble.isReady
    override val busy = false

    fun resumeAuth(): Boolean = ble.resumeAuth()

    /** Bandı bul (2/18, cihazda doğrulandı). Saati bul düğmesi WatchService.startFind/stopFind üzerinden. */
    private fun findBand(start: Boolean): Boolean = ble.findBand(start)

    /** Rahatsız etmeyi hemen aç ya da kapat (2/23, cihazda doğrulandı). */
    fun dndNow(on: Boolean): String? {
        if (!ble.isReady) return "Band 8 hazır değil."
        return if (ble.sendFeature(B8Dnd.now(on), if (on) "rahatsız etme aç 2/23" else "rahatsız etme kapat 2/23")) null
        else "Komut gönderilemedi. Biraz bekleyip tekrar dene."
    }

    /** Sağlık dosyalarını şimdi çek (8/1, yalnızca okuma). Aşağı çekip yenileme kullanır. */
    fun fetchNow(): String? {
        if (!ble.isReady) return "Band 8 hazır değil."
        return if (ble.fetchHealthNow()) null else "Çekme zaten sürüyor ya da oturum hazır değil."
    }

    /** Gönderildiyse null, değilse kullanıcıya gösterilecek kısa neden. */
    fun probe(p: Band8Probe): String? {
        if (!ble.isReady) return "Band 8 hazır değil."
        val command: ByteArray = when (p) {
            Band8Probe.ALARM_CLEANUP -> {
                val c = alarmCleanupCandidates() ?: return "Önce alarm listesini oku."
                if (c.isEmpty()) return "Silinecek test alarmı yok."
                B8Alarms.delete(c.map { it.id })
            }
            Band8Probe.WEATHER_LOCATION, Band8Probe.WEATHER_NOW, Band8Probe.WEATHER_FORECAST -> {
                val f = WeatherSync.cached(appContext) ?: return "Önce hava durumu verisi alınmalı."
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
            Band8Probe.HR_CONFIG_READ -> B8HeartRateConfig.read()
            Band8Probe.HR_CONFIG_EVERY_10, Band8Probe.HR_CONFIG_CONTINUOUS, Band8Probe.HR_CONFIG_OFF -> {
                val raw = hrConfig ?: return "Önce nabız ölçüm ayarını oku."
                val mode = when (p) {
                    Band8Probe.HR_CONFIG_EVERY_10 -> B8HeartRateConfig.Mode.EVERY_10
                    Band8Probe.HR_CONFIG_CONTINUOUS -> B8HeartRateConfig.Mode.CONTINUOUS
                    else -> B8HeartRateConfig.Mode.OFF
                }
                val bytes = B8HeartRateConfig.write(raw, mode) ?: return "Okunan ayar beklenmeyen biçimde; gönderilmedi."
                hrConfigReadAfterWrite = true
                bytes
            }
        }
        if (!ble.sendProbe(command, p.label)) return "Band 8 denemesi gönderilemedi. Logu kontrol et."
        // Alarm kimlikleri sıra numarasıdır; değişiklikten sonra liste yeniden okunmalı.
        if (p == Band8Probe.ALARM_CLEANUP) {
            alarmListFresh = false
            refreshAfterChange = true
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

    /** "Test alarmlarını temizle" adayları: deneme imzasına uyanlar. Taze liste yoksa null. Saatler yalnızca ekranda. */
    fun alarmCleanupCandidates(): List<B8Alarm>? = freshAlarms()?.let { B8Alarms.probeCandidates(it) }

    /** Deneme yanıtları. Loga yalnızca sayı, alan numarası ve durum yazılır. */
    override fun onBandCommand(command: B8Command) {
        if (faces.onCommand(command)) return
        when {
            command.matches(B8Type.NOTIFICATION, B8Sub.NOTIF_ICON_QUERY) -> icons.onQuery(command)
            command.matches(B8Type.NOTIFICATION, B8Sub.NOTIF_ICON_REQUEST) -> icons.onSpec(command)
            B8Realtime.isEvent(command) -> onRealtime(command)
            B8Workout.hasSensor(command) -> onSensor(command)
            command.matches(B8Type.HEALTH, B8Workout.SUB_STATUS) ->
                listener.onLog("Band 8 antrenman durumu yanıtı: durum=${command.status ?: "yok"}")
            command.matches(B8Type.SCHEDULE, 0) -> B8Alarms.parseList(command)?.let { list ->
                alarmList = list
                alarmListFresh = true
                WatchHub.settingsChanged()
                listener.onLog("Band 8 alarm listesi: ${list.alarms.size} alarm, en çok ${list.max ?: "?"}, " +
                    "deneme imzalı ${B8Alarms.probeCandidates(list).size}")
            }
            command.matches(B8Type.SCHEDULE, 1) -> {
                listener.onLog("Band 8 alarm oluşturma yanıtı: durum=${command.status ?: "yok"}; kimlik için listeyi oku")
                refreshIfChanged()
            }
            command.matches(B8Type.SCHEDULE, 2) -> {
                listener.onLog("Band 8 alarm düzenleme yanıtı: durum=${command.status ?: "yok"}")
                refreshIfChanged()
            }
            command.matches(B8Type.NOTIFICATION, 2) || command.matches(B8Type.NOTIFICATION, 5) -> {
                val reject = command.subtype == 2
                listener.onLog("Band 8 arama: ${if (reject) "reddet" else "sessize al"}")
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
            command.matches(B8Type.HEALTH, 10) -> {
                hrConfig = B8HeartRateConfig.parse(command)
                val h = hrConfig
                listener.onLog(if (h != null) "Band 8 nabız ölçüm ayarı okundu: ${B8HeartRateConfig.describe(h)}"
                    else "Band 8 nabız ölçüm ayarı beklenen biçimde değil: Health alanları ${command.msg.bytes(B8Type.F_HEALTH)?.let { B8RawSetting(0, it).shape() } ?: "yok"}")
            }
            command.matches(B8Type.HEALTH, 11) -> {
                listener.onLog("Band 8 nabız ölçüm ayarı yazma yanıtı: durum=${command.status ?: "yok"}")
                if (hrConfigReadAfterWrite) {
                    hrConfigReadAfterWrite = false
                    ble.sendProbe(B8HeartRateConfig.read(), Band8Probe.HR_CONFIG_READ.label)
                }
            }
            command.type == B8Type.MUSIC -> {
                // İstek yanıtlanır, tuş uygulanır. Yalnızca tanınmayan olayın alan biçimi loga yazılır (metin yok).
                val key = B8Music.mediaKey(command)
                when {
                    key != null -> music.onKey(key)
                    B8Music.isStateRequest(command) -> music.onRequest()
                    else -> listener.onLog("Band 8 müzik olayı tanınmadı: altTür=${command.subtype}, alanlar=${B8Music.describe(command)}")
                }
            }
        }
    }

    // ---------- Canlı nabız (8/45 başlat, 8/46 durdur, 8/47 olay) ----------

    private var lastUnwantedStopAt = 0L

    /** Sleep as Android için canlı akışı başlatır ya da durdurur. */
    fun realtimeHr(start: Boolean): Boolean =
        ble.isReady && ble.sendFeature(if (start) B8Realtime.start() else B8Realtime.stop(),
            if (start) "canlı nabız başlat 8/45" else "canlı nabız durdur 8/46")

    /** 2/59 titreşim deseni (cihazda doğrulandı, build 191). */
    fun vibrate(steps: List<Pair<Boolean, Int>>, label: String): Boolean =
        ble.isReady && ble.sendFeature(B8Vibration.pattern(steps), label)

    /** Sleep as Android alarmı: bantta "Alarm" adlı arama ekranı (reddet = kapat, sessize al = ertele). */
    fun alarmScreen(show: Boolean): Boolean {
        if (!ble.isReady) return false
        val bytes = if (show) B8Messages.incomingCall(ALARM_TITLE, "", notificationTime(System.currentTimeMillis())) else B8Messages.callEnded()
        return ble.sendFeature(bytes, if (show) "alarm ekranı 7/0" else "alarm ekranı kapat 7/1")
    }

    /** 8/47: nabız değeri loga yazılmaz, yalnızca sayılır. */
    private fun onRealtime(command: B8Command) {
        val hr = B8Realtime.validHeartRate(B8Realtime.parse(command)?.heartRate)
        val ours = SleepAsAndroidBridge.onHeartRate(hr)
        if (!ours) {
            // Kimsenin istemediği akış (ör. uygulama yeniden başladı) pil harcamasın.
            val now = System.currentTimeMillis()
            if (now - lastUnwantedStopAt >= 60_000L) {
                lastUnwantedStopAt = now
                listener.onLog("Band 8 istenmeyen canlı akış; durduruluyor")
                realtimeHr(false)
            }
        }
    }

    // ---------- Gizli antrenman ve ivme (8/26, Health alan 15; Sleep as Android hareket verisi) ----------

    private val sensorSubtypes = sortedSetOf<Int>()
    private var lastUnwantedWorkoutStopAt = 0L

    /** Gizli uyku antrenmanı: başlat ya da duraklat + 500 ms sonra bitir (Notify'ın sırası). */
    fun workout(start: Boolean): Boolean {
        if (!ble.isReady) return false
        val now = System.currentTimeMillis()
        val q = java.util.TimeZone.getDefault().getOffset(now) / 60_000 / 15
        if (start) return ble.sendFeature(B8Workout.status(B8Workout.STATE_START, now / 1000, q), "gizli antrenman başlat 8/26")
        val ok = ble.sendFeature(B8Workout.status(B8Workout.STATE_PAUSE, now / 1000, q), "gizli antrenman duraklat 8/26")
        main.postDelayed({
            if (ble.isReady) ble.sendFeature(B8Workout.status(B8Workout.STATE_FINISH, System.currentTimeMillis() / 1000, q), "gizli antrenman bitir 8/26")
        }, B8Workout.FINISH_GAP_MS)
        return ok
    }

    /** Health alan 15: ivme. Değer loga yazılmaz; alt tür ilk görülüşte yazılır. */
    private fun onSensor(command: B8Command) {
        if (sensorSubtypes.add(command.subtype)) listener.onLog("Band 8 ivme komutu görüldü: tür=8, altTür=${command.subtype}")
        val samples = B8Workout.accel(command)
        val ours = SleepAsAndroidBridge.onAccel(samples)
        if (!ours) {
            val now = System.currentTimeMillis()
            if (now - lastUnwantedWorkoutStopAt >= 60_000L) {
                lastUnwantedWorkoutStopAt = now
                listener.onLog("Band 8 istenmeyen ivme akışı; gizli antrenman bitiriliyor")
                workout(false)
            }
        }
    }

    override fun onForeignClient() = listener.onForeignClient()

    override fun startScan() = ble.scan()
    override fun connect(device: BluetoothDevice, autoConnect: Boolean) = ble.connect(device, autoConnect)
    override fun disconnect() = ble.disconnect()
    override fun forceClose(reason: String) = ble.forceClose()
    override fun release(reason: String) {
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
        val sent = ble.sendAppNotification(B8Notification(
            note.pkg, note.appLabel, note.title, note.body, notificationTime(note.at), note.androidId, key,
        ))
        // Kuraldaki titreşim (1-5): bandın kendi titreşiminden sonra desen (2/59). Son titreşim uzundur.
        // Navigasyon bildirimine kural titreşimi (2/59) uygulanmaz (build 238).
        if (sent && vibrate && note.vibration > 0 && !note.nav) {
            ble.sendFeature(B8Vibration.pattern(B8Vibration.count(note.vibration)), "kural titreşimi 2/59")
        }
        return sent
    }

    private fun notificationTime(at: Long): String =
        NOTIFICATION_TIME.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))

    /** Gerçek gelen arama: bantta arama ekranı. Arayan adı ya da numarası loga yazılmaz. */
    override fun sendIncomingCall(name: String): Boolean {
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
    override fun findStart() { findBand(true) }
    override fun findStop(reason: String) { findBand(false) }
    override fun sendMusicState(playing: Boolean, volumePercent: Int) = Unit
    override fun sendWeather(current: Int, days: List<UteWeather.Day>, city: String) = Unit

    override fun onLog(message: String) = listener.onLog(message)
    override fun onStatus(message: String) = listener.onStatus(message)
    override fun onFound(device: BluetoothDevice, name: String, rssi: Int) =
        listener.onDeviceFound(device, name, rssi)

    override fun onReady(ready: Boolean) {
        if (ready) music.start() else music.stop()
        if (ready) nav.onConnected()
        // Alarm kartı için liste bağlanınca bir kez okunur (yalnızca okuma).
        if (ready) main.postDelayed({ if (ble.isReady && freshAlarms() == null) alarmRefresh() }, ALARM_READ_DELAY_MS)
        if (ready) listener.onAuth(AuthOutcome(AuthOutcome.Kind.OK))
        listener.onReadyChanged(ready)
    }

    override fun onDisconnected(userInitiated: Boolean, status: Int) {
        faces.onDisconnected()
        nav.onDisconnected(userInitiated)
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
        const val ALARM_READ_DELAY_MS = 4_000L
        const val ALARM_TITLE = "Alarm"

        /** Alarm listesi süreç boyunca tutulur (sürücü yeniden bağlanınca yenilenir); taze değilse değişiklik yapılmaz. */
        private var alarmList: B8AlarmList? = null
        private var alarmListFresh = false
        const val WEATHER_KEY = "finnswatch"
        val NOTIFICATION_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss", Locale.ROOT)
    }
}
