package com.che.ls05

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import com.che.ls05.protocol.AuthOutcome
import com.che.ls05.protocol.HistoryGate
import com.che.ls05.protocol.MSG_TYPE_GENERIC
import com.che.ls05.protocol.ProfileSetting
import com.che.ls05.protocol.RemoteAction
import com.che.ls05.protocol.SettingSync
import com.che.ls05.protocol.SwitchRules
import com.che.ls05.protocol.EmojiText
import com.che.ls05.protocol.TextSanitizer
import com.che.ls05.protocol.TurkishFold
import com.che.ls05.protocol.WatchEvent
import com.che.ls05.protocol.WatchKeys
import com.che.ls05.protocol.WatchState
import com.che.ls05.protocol.WatchType
import com.che.ls05.protocol.band8.B8ForeignGuard
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * Saatle kalıcı bağlantıyı tutan ön plan servisi (connectedDevice).
 *
 * - Kopunca kademeli bekleme ile yeniden bağlanır (autoConnect: saat menzile girince sistem bağlar).
 * - Her bağlantıda doğrulama yapılır. Ret (2/3/5), onay bekleme (4) veya kimlik yokluğu otomatik
 *   denemeleri durdurur; zaman aşımı en fazla 3 kez denenir (kural 7). Sonra kullanıcıya sorulur.
 * - Kullanıcı "Durdur" derse yeniden bağlanmaz ve açılışta başlamaz.
 */
class WatchService : Service(), DriverListener {

    companion object {
        const val ACTION_START = "com.che.ls05.START"
        const val ACTION_STOP = "com.che.ls05.STOP"
        const val ACTION_CONNECT_NEW = "com.che.ls05.CONNECT_NEW"
        const val EXTRA_ADDRESS = "address"

        private const val CHANNEL_ID = "watch"
        private const val NOTIF_ID = 1
        private const val MAX_AUTH_TIMEOUTS = 3
        private const val ACK_TIMEOUT_MS = 8_000L
        private const val ACK_CHECK_MS = 8_200L
        private const val HISTORY_STALE_MS = 30 * 60 * 1000L
        private const val MAX_LIVE_STEPS = 48

        /** Saat bu süreden uzun çalmaz: arama bitişi kaçırılırsa saat susturulur. */
        private const val CALL_MAX_MS = 60_000L
        private const val NUDGE_MIN_INTERVAL_MS = 30_000L
        private const val MUSIC_STATE_DELAY_MS = 700L

        /** Saati bul en çok bu kadar titretir. */
        private const val FIND_MAX_MS = 30_000L

        /** Band 8'e aynı anda başka bir istemci bağlıyken gösterilen uyarı (#16). */
        const val FOREIGN_CLIENT_WARNING = "Banda başka bir uygulama bağlı (Mi Fitness, Notify). O uygulamayı kaldır ya da Bluetooth iznini kapat."

        /** Başlatma isteği sisteme iletildiyse true. Arka plan kısıtı (Android 12+) gibi hatalarda false. */
        fun start(context: Context, action: String = ACTION_START, address: String? = null): Boolean {
            val i = Intent(context, WatchService::class.java).setAction(action)
            if (address != null) i.putExtra(EXTRA_ADDRESS, address)
            return try {
                context.startForegroundService(i)
                true
            } catch (e: Exception) {
                WatchHub.log("Servis başlatılamadı: ${e.javaClass.simpleName}. Uygulamayı açıp tekrar dene.")
                false
            }
        }
    }

    /** Etkin saatin sürücüsü. */
    lateinit var driver: WatchDriver
        private set

    /** Yalnızca LS05'e özel işler (kadran) için. */
    private val ls05: Ls05Driver? get() = driver as? Ls05Driver

    private val main = Handler(Looper.getMainLooper())
    private val backoffMs = longArrayOf(2_000, 5_000, 15_000, 30_000, 60_000)

    private var stopping = false
    private var restarting = false
    private var authBlocked = false
    private var authTimeouts = 0
    private var reconnectAttempt = 0

    /** Bağlantı oturumu: her hazır olmada artar. Onay yalnızca aynı oturumda gönderilen komuta sayılır. */
    private var session = 0

    /** Gecikmeli işler (onay denetimi, geçmiş istekleri) için ortak etiket; servis kapanınca hepsi iptal edilir. */
    private val delayedToken = Any()

    private val reconnectRunnable = Runnable { reconnectSaved() }

    /** Bu bağlantı doğrulanıp hazır oldu mu. Hiç hazır olmamış bağlantı denemesinin kopması uyarı kurmaz. */
    private var wasReady = false
    private var bandReadyAt = 0L

    /** Geçmiş okuma kapısı: üst üste gelen istekleri ayıklar (bkz. requestHistory). */
    private val historyGate = HistoryGate()
    private val historyTimeout = Runnable {
        if (historyGate.timeout(SystemClock.uptimeMillis())) {
            WatchHub.log("Geçmiş okuması ${HistoryGate.TIMEOUT_MS / 1000} sn içinde tamamlanmadı; bitmiş sayıldı.")
            historyChanged()
        }
    }

    /** Geçmiş okuması sürüyor mu (Bugün ekranındaki yenileme göstergesi için). */
    val historyBusy: Boolean get() = historyGate.running

    /** Saatte gelen arama ekranı açık (0D 04 henüz gönderilmedi). */
    private var callOnWatch = false
    private val callSafetyStop = Runnable { sendCallStop("arama ${CALL_MAX_MS / 1000} sn içinde bitmedi") }
    private val musicStateRunnable = Runnable { sendMusicState() }
    private var lastNudge = 0L

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_OFF -> {
                    WatchHub.log("Bluetooth kapandı.")
                    // Kullanıcı Bluetooth'u kapattı: "saat uzaklaştı" uyarısı verilmez.
                    LinkAlert.disarm(this@WatchService, "Bluetooth kapandı")
                    main.removeCallbacks(reconnectRunnable)
                    restarting = true
                    driver.forceClose("Bluetooth kapandı")
                    restarting = false
                    WatchManager.setState(WatchState.BT_OFF)
                    updateStatus("Bluetooth kapalı")
                }
                BluetoothAdapter.STATE_ON -> {
                    WatchHub.log("Bluetooth açıldı; yeniden bağlanılıyor.")
                    reconnectAttempt = 0
                    main.removeCallbacks(reconnectRunnable)
                    main.postDelayed(reconnectRunnable, 1_500)
                }
            }
        }
    }

    // ---------- Telefon pili uyarısı ----------

    private val batteryAlerts = com.che.ls05.protocol.BatteryAlerts()

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
            if (level < 0 || scale <= 0) return
            val pct = level * 100 / scale
            val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
            val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                status == android.os.BatteryManager.BATTERY_STATUS_FULL
            val c = this@WatchService
            val alert = batteryAlerts.update(pct, charging, Prefs.batteryLow(c), Prefs.batteryLowThreshold(c), Prefs.batteryFull(c)) ?: return
            val body = when (alert) {
                com.che.ls05.protocol.BatteryAlerts.Alert.LOW -> "Pil %$pct. Şarja tak."
                com.che.ls05.protocol.BatteryAlerts.Alert.FULL -> "Pil doldu (%$pct)."
            }
            WatchHub.log("Telefon pili uyarısı: ${alert.name}.")
            val now = System.currentTimeMillis()
            WatchHub.forward(c, WatchHub.Note(packageName, "Telefon", "Telefon pili", body, MSG_TYPE_GENERIC, now,
                androidId = (now / 1000).toInt(), androidKey = "battery:${alert.name}:$now"))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        WatchHub.log(WatchRegistry.diagnosticSummary(this))
        driver = WatchManager.driverFor(WatchRegistry.active(this)?.type ?: WatchType.LS05, applicationContext, this)
        HealthStore.init(applicationContext)
        SettingsStore.sync(applicationContext)
        WatchHub.service = this
        createChannel()
        LinkAlert.createChannel(this)
        registerReceiver(btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        CalendarReminders.refresh(this)
    }

    override fun onDestroy() {
        main.removeCallbacks(reconnectRunnable)
        main.removeCallbacks(foreignReconnect)
        main.removeCallbacks(callSafetyStop)
        main.removeCallbacks(musicStateRunnable)
        main.removeCallbacks(weatherTick)
        main.removeCallbacks(historyTimeout)
        historyGate.reset()
        LinkAlert.disarm(this, "servis kapandı")
        PhoneFinder.stop("servis kapandı")
        stopFind("servis kapandı")
        main.removeCallbacksAndMessages(delayedToken)
        try { unregisterReceiver(btReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        restarting = true
        driver.release("servis kapandı")
        restarting = false
        SleepAsAndroidBridge.onBandReady(false)
        WatchHub.setReady(false)
        WatchHub.service = null
        WatchManager.setState(WatchState.STOPPED)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!goForeground()) return START_NOT_STICKY

        when (intent?.action) {
            ACTION_STOP -> {
                stopping = true
                Prefs.setServiceEnabled(this, false)
                WatchManager.setState(WatchState.STOPPED)
                main.removeCallbacks(reconnectRunnable)
                main.removeCallbacks(foreignReconnect)
                WatchHub.log("Servis kullanıcı tarafından durduruluyor.")
                LinkAlert.disarm(this, "servis durduruldu")
                if (driver.isConnected) driver.disconnect()
                main.postDelayed({
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }, 1_500)
            }
            ACTION_CONNECT_NEW -> {
                val address = intent.getStringExtra(EXTRA_ADDRESS)
                // İlk kurulumda etkin saat yoksa öneksiz LS05 kaydı etkin olur (v0.11'deki gibi tek adım).
                WatchRegistry.ensureActive(this)
                if (address != null) Prefs.setAddress(this, address)
                Prefs.setServiceEnabled(this, true)
                connectFresh("yeni bağlantı isteği")
            }
            else -> {
                stopping = false
                if (!driver.isConnected) connectSaved(autoConnect = false)
            }
        }
        return START_STICKY
    }

    // ---------- Bağlantı yönetimi ----------

    fun isWatchReady(): Boolean = driver.isReady && !driver.busy &&
        (driver.type != WatchType.BAND8 || SystemClock.uptimeMillis() >= bandReadyAt)

    // ---------- Kadran ----------

    /** Uzun aktarım (kadran) bitti ya da durdu: bekleyen trafik şimdi gider. */
    override fun onBusyEnded() {
        if (driver.isReady) {
            flushPending()
            pushSettings()
        }
    }

    /** Ayarlar ekranından çağrılır. Başlatılamazsa nedeni döner. */
    fun startFace(file: com.che.ls05.protocol.FaceFile, id: String): String? {
        val watch = ls05 ?: return "Bu saatte kadran yüklenemez."
        return watch.startFace(file, id)
    }

    /** Saat ekranındaki deneme bildirimi (sadeleştirme yok, genel tür). */
    fun sendTestNotification(title: String, message: String): Boolean =
        driver.sendNotification(title, message, Prefs.vibrate(this), MSG_TYPE_GENERIC)

    /** Kurulumdaki "Tara". */
    fun startScan() = driver.startScan()

    /** Anahtar girilince FE95 bağlantısını yeniden açmadan kimlik doğrulamayı sürdürür. */
    fun resumeBandAuth(): Boolean = (driver as? Band8Driver)?.resumeAuth() ?: false

    fun testBandFind(start: Boolean): Boolean = (driver as? Band8Driver)?.testFindBand(start) ?: false

    fun testBandCallScreen(start: Boolean): Boolean = (driver as? Band8Driver)?.testCallScreen(start) ?: false

    /** Kullanıcı doğrulama anahtarını girmekten vazgeçtiğinde bağlantıyı sonlandır. */
    fun cancelBandSetup() { (driver as? Band8Driver)?.disconnect() }

    /** Ayarlar > Saat bağlantısı > Bağlan ile aynı yol: bağlantı kapanır, Band 8 sürücüsü yenilenir. */
    private fun connectFresh(reason: String) {
        stopping = false
        resetAuth()
        main.removeCallbacks(reconnectRunnable)
        main.removeCallbacks(foreignReconnect)
        if (driver.isConnected) {
            restarting = true
            driver.forceClose(reason)
            restarting = false
        }
        connectSaved(autoConnect = false, rebuildBandDriver = true)
    }

    /** Gelişmiş > Band 8 denemeleri. Gönderildiyse null, değilse kısa neden. */
    fun bandProbe(p: Band8Probe): String? {
        val band = driver as? Band8Driver ?: return "Band 8 etkin değil."
        return band.probe(p)
    }

    /** Gelişmiş > Band 8 kadran (deneysel). Gönderildiyse null, değilse kısa neden. */
    fun bandFaceRead(): String? = (driver as? Band8Driver).let { if (it == null) "Band 8 etkin değil." else it.faceRead() }
    fun bandFaceSet(id: String): String? = (driver as? Band8Driver).let { if (it == null) "Band 8 etkin değil." else it.faceSet(id) }
    fun bandFaceDelete(id: String): String? = (driver as? Band8Driver).let { if (it == null) "Band 8 etkin değil." else it.faceDelete(id) }
    fun bandFaceInstall(bytes: ByteArray): String? = (driver as? Band8Driver).let { if (it == null) "Band 8 etkin değil." else it.faceInstall(bytes) }

    /** Band 8 hareketsizlik uyarısı kartı (deneysel). */
    fun bandIdleWindow(): com.che.ls05.protocol.band8.B8Standing.Window? = (driver as? Band8Driver)?.idleWindow()
    fun bandIdleRead(): String? = (driver as? Band8Driver).let { if (it == null) "Band 8 etkin değil." else it.idleRead() }
    fun bandIdleSave(enabled: Boolean, sh: Int, sm: Int, eh: Int, em: Int): String? =
        (driver as? Band8Driver).let { if (it == null) "Band 8 etkin değil." else it.idleSave(enabled, sh, sm, eh, em) }

    /** Band 8 alarm kartı. */
    fun bandAlarms(): com.che.ls05.protocol.band8.B8AlarmList? = (driver as? Band8Driver)?.alarmsForCard()
    fun bandAlarmRefresh(): String? = (driver as? Band8Driver).let { if (it == null) "Band 8 etkin değil." else it.alarmRefresh() }
    fun bandAlarmSave(id: Int?, spec: com.che.ls05.protocol.band8.B8AlarmSpec): String? =
        (driver as? Band8Driver).let { if (it == null) "Band 8 etkin değil." else it.alarmSave(id, spec) }
    fun bandAlarmDelete(id: Int): String? = (driver as? Band8Driver).let { if (it == null) "Band 8 etkin değil." else it.alarmDelete(id) }

    /** "Test alarmlarını temizle" için silinecek alarmlar; taze liste yoksa null. */
    fun bandAlarmCleanupCandidates(): List<com.che.ls05.protocol.band8.B8Alarm>? =
        (driver as? Band8Driver)?.alarmCleanupCandidates()

    private var lastForeignReconnectAt: Long? = null
    private val foreignReconnect = Runnable {
        if (stopping || driver.type != WatchType.BAND8) return@Runnable
        lastForeignReconnectAt = SystemClock.elapsedRealtime()
        WatchHub.log("Band 8 başka istemci uyarısı: bekleme doldu; tam yeniden bağlanılıyor")
        connectFresh("başka istemci")
    }

    override fun onForeignClient() {
        if (driver.type != WatchType.BAND8) return
        WatchHub.bandForeignClient()
        updateStatus(FOREIGN_CLIENT_WARNING)
        val now = SystemClock.elapsedRealtime()
        if (B8ForeignGuard.reconnectAllowed(now, lastForeignReconnectAt)) {
            WatchHub.log("Band 8 başka istemci uyarısı: tam yeniden bağlanılıyor")
            main.removeCallbacks(foreignReconnect)
            // Sürücünün kendi geri çağrısının içinden kapatmamak için bir sonraki döngüde.
            main.post(foreignReconnect)
        } else {
            val wait = lastForeignReconnectAt!! + B8ForeignGuard.RECONNECT_COOLDOWN_MS - now
            WatchHub.log("Band 8 başka istemci uyarısı: son 5 dakikada yeniden bağlanıldı; ${wait / 1000} sn sonra bir kez daha denenecek")
            main.removeCallbacks(foreignReconnect)
            main.postDelayed(foreignReconnect, wait)
        }
    }

    private fun resetAuth() {
        authBlocked = false
        authTimeouts = 0
        reconnectAttempt = 0
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    // ---------- Saat değiştirme ----------

    /**
     * Etkin saati değiştirir (docs/COKLU_SAAT_TASARIM.md 2.4). Eski saat sessizce kapatılır: kayıp uyarısı kurulmaz,
     * son görülen yer alınmaz. Eski saatin bekleyen ayarları kendi kaydında kapanır, yeni saate taşınmaz.
     * Telefondaki bildirim kuyruğu korunur ve yeni saat hazır olunca gider. Yeni saat bağlanamazsa eski saate
     * kendiliğinden dönülmez. Yapılamazsa nedeni döner.
     */
    fun switchTo(id: String): String? {
        val list = WatchRegistry.list(this)
        when (SwitchRules.decide(list, id, driver.busy)) {
            SwitchRules.Decision.SAME -> return null
            SwitchRules.Decision.BUSY -> return "Kadran yükleniyor; bitince tekrar dene."
            SwitchRules.Decision.UNKNOWN -> return "Saat bulunamadı."
            SwitchRules.Decision.PROCEED -> Unit
        }
        val target = list.find(id) ?: return "Saat bulunamadı."
        // Eski saat titriyorsa durdurma komutu kapanmadan gidemeyebilir: önce kullanıcı durdurur.
        if (finding) return "Saati bul sürüyor; önce Durdur'a bas."
        if (callOnWatch) return "Saatte arama ekranı açık; arama bitince tekrar dene."
        WatchManager.setState(WatchState.SWITCHING)
        WatchHub.log("Saat değiştiriliyor (${target.type.label}).")
        // Eski saatin zamanlayıcıları.
        main.removeCallbacks(reconnectRunnable)
        main.removeCallbacks(foreignReconnect)
        main.removeCallbacks(historyTimeout)
        main.removeCallbacks(musicStateRunnable)
        main.removeCallbacksAndMessages(delayedToken)
        if (historyGate.running) historyGate.reset()
        main.removeCallbacks(callSafetyStop)
        LinkAlert.disarm(this, "saat değiştirildi")
        val dropped = (driver as? Ls05Driver)?.ble?.pendingOps ?: 0
        if (dropped > 0) WatchHub.log("Eski saatin kuyruğundaki $dropped işlem atıldı.")
        restarting = true
        driver.release("saat değiştirildi")
        restarting = false
        WatchHub.setReady(false)
        if (!WatchRegistry.setActive(this, id)) {
            WatchHub.log("Saat değişimi iptal edildi: kayıt diske yazılamadı.")
            // Eski sürücü kapatıldı, fakat etkin kayıt değişmedi; aynı saate geri dön.
            driver = WatchManager.driverFor(list.active?.type ?: WatchType.LS05, applicationContext, this)
            connectSaved(autoConnect = false)
            return "Saat seçimi kaydedilemedi; mevcut saat korunuyor."
        }
        SwitchLog.record(this, id)
        resetAuth()
        wasReady = false
        stopping = false
        WatchManager.afterSwitch(this)
        driver = WatchManager.driverFor(target.type, applicationContext, this)
        updateStatus("${target.name}: bağlanıyor")
        connectSaved(autoConnect = false)
        return null
    }

    private fun reconnectSaved() {
        val band = driver.type == WatchType.BAND8
        connectSaved(autoConnect = !band, rebuildBandDriver = band)
    }

    private fun connectSaved(autoConnect: Boolean, rebuildBandDriver: Boolean = false) {
        if (stopping || driver.isConnected) return
        if (!hasConnectPermission()) {
            updateStatus("Bluetooth izni yok")
            WatchHub.log("Bluetooth bağlantı izni yok; uygulamayı açıp izin ver.")
            return
        }
        val address = Prefs.address(this)
        if (address == null) {
            WatchManager.setState(WatchState.NONE)
            updateStatus("Saat seçilmedi")
            return
        }
        if (authBlocked) {
            WatchManager.setState(WatchState.AUTH_BLOCKED)
            updateStatus("Doğrulama durdu; uygulamayı aç")
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            WatchManager.setState(WatchState.BT_OFF)
            updateStatus("Bluetooth kapalı")
            return
        }
        if (driver is UnsupportedDriver) WatchManager.setState(WatchState.UNSUPPORTED) else WatchManager.setState(WatchState.CONNECTING)
        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            updateStatus("Kayıtlı saat adresi geçersiz")
            return
        }
        if (rebuildBandDriver && driver.type == WatchType.BAND8) {
            // Saat değişimindeki temiz sürücü başlangıcını kayıtlı saati değiştirmeden uygula.
            driver.release("Band 8 yeniden bağlanıyor")
            driver = WatchManager.driverFor(WatchType.BAND8, applicationContext, this)
            WatchHub.log("Band 8 sürücüsü yenilendi; kayıtlı bilekliğe bağlanılıyor.")
        }
        if (autoConnect) WatchHub.log("Saat menzile girince bağlanılacak (otomatik bağlantı).")
        driver.connect(device, autoConnect)
    }

    /**
     * Saat hazır değilken bildirim geldi: servis yaşıyor ama yeniden bağlanma beklemesindeyse beklemeyi öne alır.
     * Bağlantı denemesi sürüyorsa (otomatik bağlantı dahil) bir şey yapmaz. En çok 30 sn'de bir.
     */
    fun nudgeReconnect() {
        if (stopping || authBlocked || driver.isConnected) return
        val now = SystemClock.uptimeMillis()
        if (now - lastNudge < NUDGE_MIN_INTERVAL_MS) return
        lastNudge = now
        WatchHub.log("Bekleyen bildirim var; yeniden bağlanma öne alındı.")
        main.removeCallbacks(reconnectRunnable)
        connectSaved(autoConnect = false, rebuildBandDriver = true)
    }

    private fun scheduleReconnect() {
        val delay = backoffMs[minOf(reconnectAttempt, backoffMs.size - 1)]
        reconnectAttempt++
        WatchHub.log("Yeniden bağlanma ${delay / 1000} sn sonra (deneme $reconnectAttempt).")
        main.removeCallbacks(reconnectRunnable)
        main.postDelayed(reconnectRunnable, delay)
    }

    // ---------- Komutlar ----------

    /** Hava durumu (WeatherSync). Saat hazır değilse false. */
    fun sendWeather(current: Int, days: List<com.che.ls05.protocol.UteWeather.Day>, city: String): Boolean {
        if (!isWatchReady()) return false
        driver.sendWeather(current, days, city)
        return true
    }

    // ---------- Saati bul ----------

    /** Saati bul titreşimi sürüyor mu (Saat ekranındaki "Durdur" düğmesi için). */
    var finding = false
        private set

    private val findTimeout = Runnable { stopFind("${FIND_MAX_MS / 1000} sn doldu") }

    /**
     * Saati sürekli titretir: gelen arama titreşimi (06 00 00 00 FF FF 00 00), durdurma 06 00 … 00.
     * İkisi de aramada cihazda doğrulandı. En çok 30 sn sürer.
     */
    fun startFind(): Boolean {
        if (!driver.isReady) return false
        if (callOnWatch) {
            WatchHub.log("Saati bul: saatte arama ekranı açık, titreşim gönderilmedi.")
            return false
        }
        driver.findStart()
        finding = true
        main.removeCallbacks(findTimeout)
        main.postDelayed(findTimeout, FIND_MAX_MS)
        WatchHub.settingsChanged()
        return true
    }

    /** Titreşimi durdurur. Saatte arama ekranı açıksa durdurma gönderilmez (aramanın titreşimi sürer). */
    fun stopFind(reason: String) {
        if (!finding) return
        finding = false
        main.removeCallbacks(findTimeout)
        if (!callOnWatch) driver.findStop(reason)
        WatchHub.settingsChanged()
    }

    /** Bağlantı koptu ya da arama geldi: komut göndermeden durum temizlenir. */
    private fun clearFind(reason: String) {
        if (!finding) return
        finding = false
        main.removeCallbacks(findTimeout)
        WatchHub.log("Saati bul bitti ($reason).")
        WatchHub.settingsChanged()
    }

    fun sendTime(label: String = "saat eşitleme (04)") {
        driver.sendTime(ZonedDateTime.now(), label)
    }

    fun forward(note: WatchHub.Note) {
        when (note.kind) {
            WatchHub.NoteKind.MESSAGE, WatchHub.NoteKind.MISSED_CALL -> {
                val isBand = driver.type == WatchType.BAND8
                // Cevapsız arama kendi ayarıyla süzülür (Bildirimler > Cevapsız aramalar), uygulama filtresiyle değil.
                // Uygulamanın kendi uyarıları (telefon pili) ve navigasyon uygulama filtresine takılmaz.
                if (isBand && !Prefs.forwardingEnabled(this)) {
                    WatchHub.log("Band 8 bildirim atlandı: iletim kapalı.")
                    return
                }
                if (isBand && note.kind == WatchHub.NoteKind.MESSAGE && note.appFilter && note.pkg != packageName && !Prefs.appEnabled(this, note.pkg)) {
                    WatchHub.log("Band 8 bildirim atlandı: uygulama filtresinde kapalı.")
                    return
                }
                // Bildirim metni emojili gelir (Band 8 çizer). LS05'in yazı tipi için eski temizleyici burada uygulanır.
                val text = if (driver.type == WatchType.LS05) {
                    // Bilinen emoji önce kısa metne döner (❤️ → <3), kalanını eski temizleyici çıkarır.
                    val t = TextSanitizer.clean(EmojiText.apply(note.title))
                    val b = TextSanitizer.clean(EmojiText.apply(note.body))
                    if (t.isEmpty() && b.isEmpty()) {
                        WatchHub.log("Bildirim atlandı: gösterilecek metin yok.")
                        return
                    }
                    note.copy(title = fold(t.ifEmpty { note.appLabel }), body = fold(b))
                } else note
                // Kural titreşimi 0 ise titreşimsiz; sayıyı LS05 sürücüsü uygular.
                val sent = driver.sendAppNotification(text, Prefs.vibrate(this) && note.vibration != 0)
                if (sent) {
                    val what = if (note.kind == WatchHub.NoteKind.MISSED_CALL) "Cevapsız arama" else note.appLabel
                    if (isBand) WatchHub.log("Band 8 bildirim gönderimi sıraya alındı: $what.")
                    else WatchHub.log("İletildi: $what (tip ${note.type}, ${note.title.length + note.body.length} karakter).")
                } else if (isBand) {
                    WatchHub.log("Band 8 bildirim iletilmedi: tür=${note.kind}, hazır=${driver.isReady}.")
                }
            }
            WatchHub.NoteKind.CALL_ALERT ->
                sendCallAlert(if (driver.type == WatchType.LS05) fold(TextSanitizer.clean(EmojiText.apply(note.body))) else note.body)
            WatchHub.NoteKind.CALL_STOP -> sendCallStop("arama cevaplandı ya da bitti")
        }
    }

    /** "Türkçe harfleri sadeleştir" açıksa ı/İ sadeleşir (saat yazı tipinde ı boşluklu görünüyor). */
    private fun fold(text: String): String = if (Prefs.foldTurkish(this)) TurkishFold.apply(text) else text

    // ---------- Gelen arama ----------

    private fun sendCallAlert(name: String) {
        if (!driver.isReady) return
        // Arama titreşimi saati bul titreşiminin yerini alır; aramanın bitişi titreşimi durdurur.
        clearFind("gelen arama")
        if (driver.sendIncomingCall(name)) {
            callOnWatch = true
            main.removeCallbacks(callSafetyStop)
            main.postDelayed(callSafetyStop, CALL_MAX_MS)
            WatchHub.log("Gelen arama saate iletildi (${name.length} karakter).")
        }
    }

    /** Saatte arama ekranı açıksa kapatır. Saat hazır değilse bağlanınca kapatılır. */
    private fun sendCallStop(reason: String) {
        if (!callOnWatch) return
        if (!driver.isReady) return
        main.removeCallbacks(callSafetyStop)
        if (driver.sendCallStop()) {
            callOnWatch = false
            WatchHub.log("Saatteki arama ekranı kapatıldı ($reason).")
        }
    }

    // ---------- Saatten kumanda ----------

    private fun handleRemote(action: RemoteAction) {
        val label = WatchKeys.label(action)
        if (!Prefs.remoteControl(this)) {
            if (action != RemoteAction.UNKNOWN) WatchHub.log("Saatten kumanda kapalı; yok sayıldı ($label).")
            return
        }
        when (action) {
            RemoteAction.PLAY_PAUSE -> PhoneRemote.mediaKey(this, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            RemoteAction.NEXT -> PhoneRemote.mediaKey(this, KeyEvent.KEYCODE_MEDIA_NEXT)
            RemoteAction.PREVIOUS -> PhoneRemote.mediaKey(this, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            RemoteAction.VOLUME_UP -> PhoneRemote.adjustVolume(this, true)
            RemoteAction.VOLUME_DOWN -> PhoneRemote.adjustVolume(this, false)
            RemoteAction.FIND_PHONE_START -> PhoneFinder.start(this)
            RemoteAction.FIND_PHONE_STOP -> PhoneFinder.stop("saatten durduruldu")
            RemoteAction.FIND_PHONE_TOGGLE ->
                if (PhoneFinder.isActive) PhoneFinder.stop("saatten durduruldu") else PhoneFinder.start(this)
            RemoteAction.REJECT_CALL -> {
                rejectCallFromWatch()
                return
            }
            RemoteAction.CAMERA_ENTER, RemoteAction.CAMERA_EXIT -> {
                WatchHub.log("Saat kamera kumandası gönderdi ($label). Bu sürümde desteklenmiyor; yalnızca loglandı.")
                return
            }
            RemoteAction.UNKNOWN -> {
                WatchHub.log("Saatten bilinmeyen kumanda paketi; yalnızca loglandı.")
                return
            }
        }
        WatchHub.log("Saatten kumanda uygulandı: $label.")
        if (action != RemoteAction.FIND_PHONE_START && action != RemoteAction.FIND_PHONE_STOP &&
            action != RemoteAction.FIND_PHONE_TOGGLE
        ) {
            main.removeCallbacks(musicStateRunnable)
        main.removeCallbacks(weatherTick)
            main.postDelayed(musicStateRunnable, MUSIC_STATE_DELAY_MS)
        }
    }

    /** Telefonun müzik durumu ve ses düzeyi (12 0C, 12 0D). Haylou Fun bağlanınca ve değişince gönderiyor. */
    private fun sendMusicState() {
        if (!driver.isReady || driver.busy || !Prefs.remoteControl(this)) return
        driver.sendMusicState(PhoneRemote.isMusicActive(this), PhoneRemote.volumePercent(this))
    }

    // ---------- Ayar eşitleme ----------

    /** Saat hazırsa gönderilmeyi bekleyen ayarları gönderir. Onay gelmeyenler otomatik yeniden gönderilmez. */
    fun pushSettings() {
        if (!driver.isReady || driver.busy) return
        val sync = SettingsStore.sync(this)
        val todo = sync.nextToSend()
        if (todo.isEmpty()) return
        var sent = 0
        for ((key, value) in todo) {
            if (!driver.sendSetting(key, value)) {
                WatchHub.log("Ayar komutu üretilemedi ($key); gönderilmedi.")
                continue
            }
            sync.markSent(key, value, session, System.currentTimeMillis())
            sent++
        }
        if (sent == 0) return
        SettingsStore.save(this)
        WatchHub.settingsChanged()
        main.postAtTime(Runnable { checkAckTimeouts() }, delayedToken, SystemClock.uptimeMillis() + ACK_CHECK_MS)
    }

    private fun checkAckTimeouts() {
        val sync = SettingsStore.sync(this)
        val changed = sync.expire(System.currentTimeMillis(), ACK_TIMEOUT_MS)
        if (changed.isEmpty()) return
        SettingsStore.save(this)
        changed.forEach { WatchHub.log("Onay gelmedi: $it. Otomatik yeniden gönderilmeyecek.") }
        WatchHub.settingsChanged()
    }

    private fun handleAck(key: String, ackValue: String?) {
        val sync = SettingsStore.sync(this)
        when (sync.onAck(key, session, System.currentTimeMillis(), ackValue)) {
            SettingSync.Ack.MATCHED -> {
                SettingsStore.save(this)
                if (key == ProfileSetting.KEY && !Prefs.profileVerified(this)) {
                    Prefs.setProfileVerified(this, true)
                    WatchHub.log("Profil ilk kez onaylandı; bundan sonra değişiklikler saate kendiliğinden gider.")
                }
                WatchHub.log("Saat ayarı onayladı ($key).")
                WatchHub.settingsChanged()
                // Komut yoldayken kullanıcı değeri değiştirdiyse yeni istek şimdi gider.
                pushSettings()
            }
            SettingSync.Ack.UNMATCHED -> WatchHub.log("Eşleşmeyen onay ($key): hiçbir komuta sayılmadı.")
        }
    }

    // ---------- Sağlık geçmişi ----------

    /**
     * Dünden beri adım, 1,5 sn sonra nabız, includeSleep ise 4 sn sonra uyku geçmişini ister.
     * Okuma sürerken ya da bitişinden 30 sn geçmeden gelen istek yok sayılır (tek satır log).
     * Kayıtta "Verileri yenile" ile bağlanınca otomatik okuma 6 sn arayla iki tam okuma yapmıştı.
     * İstek gönderildiyse true.
     */
    fun requestHistory(includeSleep: Boolean): Boolean {
        if (driver.type == WatchType.BAND8) {
            WatchHub.log("Band 8 sağlık geçmişi henüz desteklenmiyor; okuma başlatılmadı.")
            return false
        }
        if (driver.busy) {
            WatchHub.log("Kadran yükleniyor; geçmiş daha sonra okunacak.")
            return false
        }
        if (!driver.isReady) {
            WatchHub.log("Saat hazır değil; geçmiş okunmadı.")
            return false
        }
        val now = SystemClock.uptimeMillis()
        when (val c = historyGate.check(now)) {
            HistoryGate.Check.Running -> {
                WatchHub.log("Geçmiş okuması zaten sürüyor; yeni istek yok sayıldı.")
                return false
            }
            is HistoryGate.Check.Recent -> {
                WatchHub.log("Geçmiş ${c.secondsAgo} sn önce okundu; yeni istek yok sayıldı.")
                return false
            }
            HistoryGate.Check.Start -> Unit
        }
        val parts = mutableSetOf(HistoryGate.Part.STEPS, HistoryGate.Part.HEART)
        if (includeSleep) parts += HistoryGate.Part.SLEEP
        historyGate.start(now, parts)
        main.removeCallbacks(historyTimeout)
        main.postDelayed(historyTimeout, HistoryGate.TIMEOUT_MS)
        historyChanged()
        WatchHub.log("Geçmiş okunuyor (dünden beri${if (includeSleep) ", uyku dahil" else ""}).")
        driver.requestHistory(LocalDate.now().minusDays(1), includeSleep)
        return true
    }

    /** Okumanın bir parçası bitti. Hepsi bittiyse kapı kapanır ve ekran haber alır. */
    private fun historyPartDone(part: HistoryGate.Part) {
        if (historyGate.partDone(part, SystemClock.uptimeMillis())) {
            main.removeCallbacks(historyTimeout)
            historyChanged()
        }
    }

    /** Bugün ekranındaki yenileme göstergesi healthChanged ile güncellenir. */
    private fun historyChanged() = WatchHub.healthChanged()

    private fun flushPending() {
        val notes = WatchHub.drainPending()
        if (notes.isNotEmpty()) WatchHub.log("Kuyruktaki ${notes.size} bildirim gönderiliyor.")
        notes.forEach { forward(it) }
    }

    // ---------- DriverListener ----------

    override fun onBandKeyRequired() = WatchHub.bandKeyRequired()

    override fun onLog(line: String) = WatchHub.log(line)

    override fun onStatus(status: String) = updateStatus(status)

    override fun onDeviceFound(device: BluetoothDevice, name: String, rssi: Int) =
        WatchHub.deviceFound(device, name, rssi)

    override fun onHealthRaw(bytes: ByteArray) {
        if (HealthStore.add(this, bytes)) WatchHub.healthChanged()
    }

    override fun onEvent(event: WatchEvent) {
        when (event) {
            is WatchEvent.SettingAck -> handleAck(event.key, event.echo)
            is WatchEvent.Remote -> handleRemote(event.action)
            is WatchEvent.LiveSteps -> {
                val h = event.hour
                HealthStore.live["${h.date.year}-${h.date.month}-${h.date.day}-${h.hour}"] = h
                while (HealthStore.live.size > MAX_LIVE_STEPS) HealthStore.live.remove(HealthStore.live.keys.first())
                WatchHub.healthChanged()
            }
            is WatchEvent.HistoryPart -> {
                if (event.part == HistoryGate.Part.STEPS) {
                    Prefs.setLastSync(this, System.currentTimeMillis())
                    WatchHub.healthChanged()
                }
                historyPartDone(event.part)
            }
        }
    }

    override fun onReadyChanged(ready: Boolean) {
        // Oturum, gözlemciler haber almadan önce artar ki o sırada yapılan ayar yeni oturuma sayılsın.
        if (ready) session++
        WatchHub.setReady(ready)
        if (!ready) return
        WatchManager.setState(WatchState.READY)
        wasReady = true
        LinkAlert.disarm(this, "saat yeniden bağlandı")
        reconnectAttempt = 0
        authTimeouts = 0
        // Band 8 yalnızca normal telefon bildirimlerini alır; LS05'e özel istekler gönderilmez.
        if (driver.type == WatchType.BAND8) {
            bandReadyAt = SystemClock.uptimeMillis() + 1_500
            val readySession = session
            main.postDelayed({
                if (driver.type == WatchType.BAND8 && driver.isReady && session == readySession) {
                    flushPending()
                    // Yalnızca kullanıcının değiştirdiği ayar (rahatsız etme saatleri) gider.
                    pushSettings()
                    SleepAsAndroidBridge.onBandReady(true)
                }
            }, 1_500)
            return
        }
        if (Prefs.autoTime(this)) sendTime("otomatik saat eşitleme (04)")
        // Bağlantı koparken saat çalıyor kaldıysa ve arama artık sürmüyorsa sustur.
        if (callOnWatch && !WatchHub.calls.isRinging) sendCallStop("bağlantı yeniden kuruldu, arama sürmüyor")
        flushPending()
        sendMusicState()
        pushSettings()
        if (System.currentTimeMillis() - Prefs.lastSync(this) > HISTORY_STALE_MS) requestHistory(true)
        WeatherSync.refresh(this, force = false)
        scheduleWeather()
    }

    private val weatherTick = Runnable { WeatherSync.refresh(this, force = false); scheduleWeather() }

    /** Servis açık kaldıkça saatte bir hava durumu yenilenir. */
    private fun scheduleWeather() {
        main.removeCallbacks(weatherTick)
        if (Prefs.weatherEnabled(this)) main.postDelayed(weatherTick, WeatherSync.REFRESH_MS)
    }

    override fun onDisconnected(userInitiated: Boolean, status: Int) {
        bandReadyAt = 0L
        SleepAsAndroidBridge.onBandReady(false)
        clearFind("bağlantı koptu")
        val hadLink = wasReady
        wasReady = false
        // Yarım kalan geçmiş okuması sayılmaz; yeniden bağlanınca hemen okunabilir.
        if (historyGate.running) {
            main.removeCallbacks(historyTimeout)
            historyGate.reset()
            historyChanged()
        }
        val sync = SettingsStore.sync(this)
        if (sync.onDisconnect().isNotEmpty()) {
            SettingsStore.save(this)
            WatchHub.settingsChanged()
        }
        WatchHub.setReady(false)
        if (restarting) return
        if (authBlocked) {
            WatchManager.setState(WatchState.AUTH_BLOCKED)
            updateStatus(if (driver.type == WatchType.BAND8)
                "Band 8 doğrulaması durdu; Bağlan ile yeniden dene"
            else "Doğrulama durdu; uygulamayı aç")
            return
        }
        if (stopping || userInitiated) {
            WatchManager.setState(if (stopping) WatchState.STOPPED else WatchState.NONE)
            updateStatus("Bağlı değil")
            return
        }
        // Beklenmeyen kopma: ayar açıksa 60 sn sonra denetlenir; saat bu arada dönerse uyarı kalkar.
        if (SwitchRules.armLostAlert(switching = false, stopping = false, userInitiated = false, hadLink = hadLink)) {
            LinkAlert.arm(this)
            LastSeen.capture(this)
        }
        WatchManager.setState(WatchState.WAIT_RECONNECT)
        scheduleReconnect()
    }

    override fun onAuth(outcome: AuthOutcome) {
        if (outcome.markUnknown) {
            // Saat yeniden bağlama istiyor ya da reddediyor: sıfırlanmış olabilir, ayarları belirsiz say.
            SettingsStore.sync(this).markAllUnknown()
            SettingsStore.save(this)
            WatchHub.settingsChanged()
            outcome.unknownMessage?.let { WatchHub.log(it) }
        }
        when (outcome.kind) {
            AuthOutcome.Kind.OK -> authTimeouts = 0
            AuthOutcome.Kind.TIMEOUT -> {
                authTimeouts++
                if (authTimeouts >= MAX_AUTH_TIMEOUTS) blockAuth(outcome.blockMessage ?: "Doğrulama yanıtsız kaldı.")
            }
            AuthOutcome.Kind.BLOCK -> blockAuth(outcome.blockMessage ?: "Doğrulama durdu.")
        }
    }

    private fun blockAuth(message: String) {
        authBlocked = true
        WatchManager.setState(WatchState.AUTH_BLOCKED)
        // Saat yakında ama doğrulama durdu: "uzaklaştı" uyarısı yanlış olur.
        LinkAlert.disarm(this, "doğrulama durdu")
        main.removeCallbacks(reconnectRunnable)
        WatchHub.log(message)
        updateStatus(if (driver.type == WatchType.BAND8)
            "Band 8 doğrulaması durdu; Bağlan ile yeniden dene"
        else "Doğrulama durdu; uygulamayı aç")
        if (driver.isConnected) driver.disconnect()
    }

    // ---------- Ön plan bildirimi ----------

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL_ID, "Saat bağlantısı", NotificationManager.IMPORTANCE_LOW)
        ch.description = "LS05 bağlantı durumu"
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WatchService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(notificationTitle())
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null as Icon?, "Durdur", stop).build())
            .build()
    }

    /** Tek saat kayıtlıysa v0.11 ile aynı başlık; birden çok saatte etkin saatin adı eklenir. */
    private fun notificationTitle(): String {
        val l = WatchRegistry.list(this)
        val a = l.active
        return if (l.watches.size > 1 && a != null) "Finn's Watch · ${a.name}" else "Finn's Watch"
    }

    /** Ön plan türüne "location" eklendi mi (son görülen yer için). */
    var locationType = false
        private set

    /**
     * Tür: connectedDevice; "son görülen yer" açık ve konum izni o an verilmişse location da eklenir.
     * İzinsiz eklenirse servis SecurityException ile çöker. Android 14+ arka planda başlatılan serviste
     * location türüne izin vermeyebilir; o zaman yalnızca connectedDevice ile devam edilir.
     */
    private fun goForeground(): Boolean = try {
        val n = buildNotification(WatchHub.status)
        if (Build.VERSION.SDK_INT >= 29) {
            val base = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            var withLocation = LastSeen.active(this)
            if (withLocation) {
                try {
                    startForeground(NOTIF_ID, n, base or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                } catch (e: Exception) {
                    WatchHub.log("Servis türüne konum eklenemedi (${e.javaClass.simpleName}); uygulama açılınca yeniden denenecek.")
                    withLocation = false
                }
            }
            if (!withLocation) startForeground(NOTIF_ID, n, base)
            locationType = withLocation
        } else {
            startForeground(NOTIF_ID, n)
        }
        true
    } catch (e: Exception) {
        WatchHub.log("Ön plan servisi başlatılamadı: ${e.javaClass.simpleName}. Bluetooth iznini kontrol et.")
        stopSelf()
        false
    }

    /**
     * Konum izni ya da "son görülen yer" ayarı değişti, veya uygulama öne geldi: ön plan türü yeniden kurulur.
     * Servis durmaz, bağlantı kopmaz. Türe konum eklendiyse true.
     */
    fun refreshForegroundType(): Boolean {
        if (stopping) return locationType
        val before = locationType
        goForeground()
        if (locationType != before) {
            WatchHub.log(if (locationType) "Servis türüne konum eklendi." else "Servis türünden konum kaldırıldı.")
        }
        return locationType
    }

    private fun updateStatus(text: String) {
        WatchHub.setStatus(text)
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        } catch (_: Exception) {
        }
    }

    /**
     * Saatteki kırmızı düğme (12 02). Önce arama bildirimindeki "Reddet" eylemi denenir (izin gerekmez);
     * olmazsa ANSWER_PHONE_CALLS izni verilmişse TelecomManager.endCall.
     */
    @Suppress("DEPRECATION")
    /** Band 8 arama ekranında reddet ya da sessize al. Yalnızca bizim açtığımız gerçek arama ekranı için. */
    override fun onBandCallAction(reject: Boolean) {
        // Gerçek arama yokken bantta açık "Alarm" ekranı Sleep as Android'indir.
        if (!callOnWatch && SleepAsAndroidBridge.onBandCallAction(reject)) return
        if (!callOnWatch) {
            WatchHub.log("Band 8 arama olayı: bantta açık gerçek arama yok; yok sayıldı.")
            return
        }
        if (reject) rejectCallFromWatch() else silenceRinger()
        sendCallStop(if (reject) "bantta reddedildi" else "bantta sessize alındı")
    }

    /** Zil sesini susturur; izin yoksa yalnızca loglanır (bantta ekran yine kapanır). */
    private fun silenceRinger() {
        val ok = try {
            getSystemService(android.telecom.TelecomManager::class.java)?.silenceRinger()
            true
        } catch (e: Exception) {
            WatchHub.log("Zil susturulamadı: ${e.javaClass.simpleName}")
            false
        }
        if (ok) WatchHub.log("Arama bantta sessize alındı.")
    }

    private fun rejectCallFromWatch() {
        if (!Prefs.rejectFromWatch(this)) {
            WatchHub.log("Saat aramayı reddetmek istedi (12 02). \"Saatten aramayı reddet\" kapalı; yok sayıldı.")
            return
        }
        val action = WatchHub.declineAction
        if (action != null && System.currentTimeMillis() - WatchHub.declineAt < 120_000) {
            try {
                action.send()
                WatchHub.log("Arama saatten reddedildi (bildirimdeki \"${WatchHub.declineLabel}\" eylemi).")
                return
            } catch (e: Exception) {
                WatchHub.log("Bildirim eylemi çalışmadı: ${e.javaClass.simpleName}. endCall deneniyor.")
            }
        } else {
            WatchHub.log("Arama bildiriminde reddet eylemi bulunamadı; endCall deneniyor.")
        }
        // VoIP aramada (WhatsApp, Telegram...) endCall telefon aramasını kapatabilir; denenmez.
        if (WatchHub.declinePkg in com.che.ls05.protocol.CallClassifier.VOIP_PACKAGES) {
            WatchHub.log("VoIP arama reddedilemedi: bildirim eylemi yok ya da çalışmadı.")
            return
        }
        if (android.os.Build.VERSION.SDK_INT < 28 ||
            checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            WatchHub.log("Arama izni yok; arama reddedilemedi.")
            return
        }
        val ok = try {
            getSystemService(android.telecom.TelecomManager::class.java)?.endCall() ?: false
        } catch (e: Exception) {
            WatchHub.log("endCall hata verdi: ${e.javaClass.simpleName}")
            false
        }
        WatchHub.log(if (ok) "Arama saatten reddedildi (endCall)." else "endCall aramayı kapatamadı.")
    }
}
