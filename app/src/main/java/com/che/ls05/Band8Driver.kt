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
import com.che.ls05.protocol.band8.B8Standing
import com.che.ls05.protocol.band8.B8Text
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
        val now = ZonedDateTime.now()
        val command: ByteArray = when (p) {
            Band8Probe.MISSED_CALL -> B8Calls.missedCall(TEST_CALLER, notificationTime(System.currentTimeMillis()))
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
            Band8Probe.FETCH_NOW -> return null
        }
        if (!ble.sendProbe(command, p.label)) return "Band 8 denemesi gönderilemedi. Logu kontrol et."
        // Alarm kimlikleri sıra numarasıdır; değişiklikten sonra liste yeniden okunmalı.
        if (p == Band8Probe.ALARM_CREATE || p == Band8Probe.ALARM_EDIT || p == Band8Probe.ALARM_DELETE || p == Band8Probe.ALARM_CLEANUP) {
            alarmListFresh = false
        }
        return null
    }

    private fun freshAlarms(): B8AlarmList? = alarmList?.takeIf { alarmListFresh }

    private fun testAlarm(list: B8AlarmList): B8Alarm? {
        val t = Prefs.band8TestAlarm(appContext) ?: return null
        return B8Alarms.findTest(list, t.first, t.second)
    }

    /** "Test alarmlarını temizle" adayları: deneme imzasına uyanlar. Taze liste yoksa null. Saatler yalnızca ekranda. */
    fun alarmCleanupCandidates(): List<B8Alarm>? = freshAlarms()?.let { B8Alarms.probeCandidates(it) }

    /** Deneme yanıtları. Loga yalnızca sayı, alan numarası ve durum yazılır. */
    override fun onBandCommand(command: B8Command) {
        when {
            command.matches(B8Type.SCHEDULE, 0) -> B8Alarms.parseList(command)?.let { list ->
                alarmList = list
                alarmListFresh = true
                val test = testAlarm(list)
                // Tanı: alarm başına kimlik ve alan biçimi; saat ve değer yazılmaz.
                for (line in B8Alarms.describe(command)) listener.onLog("Band 8 alarm listesi biçimi: $line")
                if (Prefs.band8TestAlarm(appContext) == null) listener.onLog("Band 8 test alarmı saati kayıtlı değil")
                listener.onLog("Band 8 alarm listesi: ${list.alarms.size} alarm, en çok ${list.max ?: "?"}, " +
                    "deneme imzalı ${B8Alarms.probeCandidates(list).size}, test alarmı ${if (test != null) "bulundu" else "yok"}")
            }
            command.matches(B8Type.SCHEDULE, 1) ->
                listener.onLog("Band 8 alarm oluşturma yanıtı: durum=${command.status ?: "yok"}; kimlik için listeyi oku")
            command.matches(B8Type.SCHEDULE, 2) -> {
                val st = command.status
                val t = pendingEditTime
                pendingEditTime = null
                if (t != null && (st == null || st == 0)) Prefs.setBand8TestAlarm(appContext, t.first, t.second)
                listener.onLog("Band 8 alarm düzenleme yanıtı: durum=${st ?: "yok"}; yeni saat ${if (t != null && (st == null || st == 0)) "kaydedildi" else "kaydedilmedi"}")
            }
            command.matches(B8Type.NOTIFICATION, 2) || command.matches(B8Type.NOTIFICATION, 5) -> {
                val reject = command.subtype == 2
                listener.onLog("Band 8 arama olayı: tür=7, altTür=${command.subtype} (${if (reject) "reddet" else "sessize al"})")
                listener.onBandCallAction(reject)
            }
            command.matches(B8Type.SCHEDULE, 4) ->
                listener.onLog("Band 8 alarm silme yanıtı: durum=${command.status ?: "yok"}; listeyi yeniden oku")
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
        if (ready) listener.onAuth(AuthOutcome(AuthOutcome.Kind.OK))
        listener.onReadyChanged(ready)
    }

    override fun onDisconnected(userInitiated: Boolean, status: Int) {
        clearCallTest()
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

        /** Alarm listesi süreç boyunca tutulur (sürücü yeniden bağlanınca yenilenir); taze değilse değişiklik yapılmaz. */
        private var alarmList: B8AlarmList? = null
        private var alarmListFresh = false
        const val TEST_CALLER = "Test arama"
        const val TEST_TRACK = "Test şarkı"
        const val WEATHER_KEY = "finnswatch"
        val NOTIFICATION_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss", Locale.ROOT)
    }
}
