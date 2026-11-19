package com.che.ls05

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.che.ls05.protocol.AuthOutcome
import com.che.ls05.protocol.UteWeather
import com.che.ls05.protocol.WatchType
import com.che.ls05.protocol.band8.B8Alarms
import com.che.ls05.protocol.band8.B8Battery
import com.che.ls05.protocol.band8.B8Calls
import com.che.ls05.protocol.band8.B8Command
import com.che.ls05.protocol.band8.B8DeviceInfo
import com.che.ls05.protocol.band8.B8Dnd
import com.che.ls05.protocol.band8.B8ForecastDay
import com.che.ls05.protocol.band8.B8Music
import com.che.ls05.protocol.band8.B8Notification
import com.che.ls05.protocol.band8.B8RawSetting
import com.che.ls05.protocol.band8.B8Standing
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
    ALARM_LIST("alarm listesi 17/0"),
    ALARM_CREATE("test alarmı oluştur 17/1"),
    ALARM_EDIT("test alarmını düzenle 17/2"),
    ALARM_DELETE("test alarmını sil 17/4"),
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

    /** Deneme durumu: alarm kimlikleri ve banddan okunan hareketsizlik ayarı. Saat ve değer loglanmaz. */
    private var alarmIds: List<Int>? = null
    private var alarmMax: Int? = null
    private var idsBeforeCreate: List<Int>? = null
    private var testAlarmId: Int? = null
    private var standing: B8RawSetting? = null
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
        val now = ZonedDateTime.now()
        val command: ByteArray = when (p) {
            Band8Probe.MISSED_CALL -> B8Calls.missedCall(TEST_CALLER, notificationTime(System.currentTimeMillis()))
            Band8Probe.ALARM_LIST -> B8Alarms.list()
            Band8Probe.ALARM_CREATE -> {
                val ids = alarmIds ?: return "Önce alarm listesini oku."
                if (testAlarmId != null) return "Test alarmı zaten var. Önce sil."
                alarmMax?.let { if (ids.size >= it) return "Bantta boş alarm yeri yok." }
                idsBeforeCreate = ids
                val (h, m) = B8Alarms.plusMinutes(now.hour, now.minute, 2)
                B8Alarms.create(h, m)
            }
            Band8Probe.ALARM_EDIT -> {
                val id = testAlarmId ?: return "Önce test alarmını kur, sonra listeyi yeniden oku."
                val (h, m) = B8Alarms.plusMinutes(now.hour, now.minute, 3)
                B8Alarms.edit(id, h, m)
            }
            Band8Probe.ALARM_DELETE -> {
                val id = testAlarmId ?: return "Önce test alarmını kur, sonra listeyi yeniden oku."
                B8Alarms.delete(listOf(id))
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
            Band8Probe.FETCH_NOW -> return null
        }
        return if (ble.sendProbe(command, p.label)) null else "Band 8 denemesi gönderilemedi. Logu kontrol et."
    }

    /** Deneme yanıtları. Loga yalnızca sayı, alan numarası ve durum yazılır. */
    override fun onBandCommand(command: B8Command) {
        when {
            command.matches(B8Type.SCHEDULE, 0) -> B8Alarms.parseList(command)?.let { list ->
                alarmIds = list.ids
                alarmMax = list.max
                val before = idsBeforeCreate
                if (before != null && testAlarmId == null) {
                    B8Alarms.newId(before, list.ids)?.let { testAlarmId = it; idsBeforeCreate = null }
                }
                listener.onLog("Band 8 alarm listesi: ${list.ids.size} alarm, en çok ${list.max ?: "?"}, test alarmı ${if (testAlarmId != null) "bulundu" else "yok"}")
            }
            command.matches(B8Type.SCHEDULE, 1) -> {
                val id = command.body(B8Type.F_SCHEDULE)?.message(4)?.int(1)
                if (id != null && testAlarmId == null) { testAlarmId = id; idsBeforeCreate = null }
                listener.onLog("Band 8 alarm oluşturma yanıtı: kimlik ${if (id != null) "geldi" else "gelmedi; listeyi yeniden oku"}")
            }
            command.matches(B8Type.SCHEDULE, 4) -> {
                testAlarmId = null
                listener.onLog("Band 8 alarm silme yanıtı alındı")
            }
            command.matches(B8Type.HEALTH, 12) -> {
                standing = B8Standing.parse(command)
                val s = standing
                listener.onLog(if (s != null) "Band 8 hareketsizlik ayarı okundu: Health alanı ${s.field}, alanlar ${s.shape()}"
                    else "Band 8 hareketsizlik ayarı beklenen biçimde değil")
            }
            command.type == B8Type.MUSIC ->
                listener.onLog("Band 8 müzik olayı: altTür=${command.subtype}")
        }
    }

    override fun onForeignClient() = listener.onForeignClient()

    private fun clearCallTest() {
        main.removeCallbacks(stopCallTest)
        callTestActive = false
    }

    override fun startScan() = ble.scan()
    override fun connect(device: BluetoothDevice, autoConnect: Boolean) = ble.connect(device, autoConnect)
    override fun disconnect() { clearCallTest(); ble.disconnect() }
    override fun forceClose(reason: String) { clearCallTest(); ble.forceClose() }
    override fun release(reason: String) {
        clearCallTest()
        ble.release()
    }

    override fun sendNotification(title: String, body: String, vibrate: Boolean, type: Int): Boolean {
        val now = System.currentTimeMillis()
        return ble.sendAppNotification(B8Notification(
            packageName, "Finn's Watch", title, body, notificationTime(now), now.toInt(), "test:$now",
        ))
    }

    override fun sendAppNotification(note: WatchHub.Note, vibrate: Boolean): Boolean {
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

    override fun sendIncomingCall(name: String) = false
    override fun sendCallStop() = false
    override fun sendTime(now: ZonedDateTime, label: String) = Unit
    override fun sendSetting(key: String, value: String) = false
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
        if (ready) listener.onAuth(AuthOutcome(AuthOutcome.Kind.OK))
        listener.onReadyChanged(ready)
    }

    override fun onDisconnected(userInitiated: Boolean, status: Int) {
        clearCallTest()
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
        const val TEST_CALLER = "Test arama"
        const val TEST_TRACK = "Test şarkı"
        const val WEATHER_KEY = "finnswatch"
        val NOTIFICATION_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss", Locale.ROOT)
    }
}
