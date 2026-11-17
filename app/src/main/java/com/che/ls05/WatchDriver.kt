package com.che.ls05

import android.bluetooth.BluetoothDevice
import com.che.ls05.protocol.AuthOutcome
import com.che.ls05.protocol.UteWeather
import com.che.ls05.protocol.WatchEvent
import com.che.ls05.protocol.WatchType
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * Saat sürücüsü: saatin protokolünü bilen tek katman. WatchService saatten bağımsız işleri (ön plan, yeniden bağlanma,
 * kuyruk, kayıp uyarısı, zamanlayıcılar) yapar ve saate yalnızca bu arayüzle konuşur.
 * Yalnızca iki saatin de gerçekten ihtiyaç duyduğu işlemler buradadır; tek saate özel olanlar (LS05 kadranı gibi)
 * sürücünün kendi sınıfındadır. Tüm çağrılar ana iş parçacığında yapılır.
 */
interface WatchDriver {
    val type: WatchType
    val isConnected: Boolean
    val isReady: Boolean

    /** Uzun bir aktarım sürüyor (LS05: kadran). Sürerken bildirim, ayar ve geçmiş isteği gönderilmez. */
    val busy: Boolean

    fun startScan()
    fun connect(device: BluetoothDevice, autoConnect: Boolean)
    fun disconnect()

    /** Sessizce kapatır; kuyruktaki işlemler atılır. */
    fun forceClose(reason: String)

    /** Sürücü bırakılır (saat değişti ya da servis kapandı). Bağlıysa sessizce kapatır, zamanlayıcıları iptal eder. */
    fun release(reason: String)

    /** type: uygulama türü (saatteki simge). Gönderildiyse true. Metin loga yazılmaz. */
    fun sendNotification(title: String, body: String, vibrate: Boolean, type: Int): Boolean

    /** Telefonda oluşan bildirim. Sürücü gerekirse kaynak uygulama ve bildirim kimliğini kullanır. */
    fun sendAppNotification(note: WatchHub.Note, vibrate: Boolean): Boolean =
        sendNotification(note.title, note.body, vibrate, note.type)

    fun sendIncomingCall(name: String): Boolean
    fun sendCallStop(): Boolean
    fun sendTime(now: ZonedDateTime, label: String)

    /** Ortak ayar modelindeki anahtar ve değer. Komut üretilemezse false. */
    fun sendSetting(key: String, value: String): Boolean

    /** from gününden beri sağlık geçmişini ister. */
    fun requestHistory(from: LocalDate, includeSleep: Boolean)

    /** Saati bul: sürekli titreşim başlat / durdur. */
    fun findStart()
    fun findStop(reason: String)

    fun sendMusicState(playing: Boolean, volumePercent: Int)
    fun sendWeather(current: Int, days: List<UteWeather.Day>, city: String)
}

/** Sürücüden servise giden olaylar. Saate özel çerçeveler burada yoktur; sürücü onları ortak olaylara çevirir. */
interface DriverListener {
    fun onLog(line: String)
    fun onStatus(status: String)
    fun onDeviceFound(device: BluetoothDevice, name: String, rssi: Int)
    fun onReadyChanged(ready: Boolean)
    fun onDisconnected(userInitiated: Boolean, status: Int)
    fun onAuth(outcome: AuthOutcome)
    fun onEvent(event: WatchEvent)

    /** Saklanması gerekebilecek ham sağlık kaydı (saklama kararı HealthStore'da). */
    fun onHealthRaw(bytes: ByteArray)

    /** Uzun aktarım (kadran) bitti ya da durdu. */
    fun onBusyEnded()

    /** Xiaomi FE95 servis keşfi başarılı, fakat Band 8 anahtarı henüz yok. */
    fun onBandKeyRequired() {}
}
