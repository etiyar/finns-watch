package com.che.ls05

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.che.ls05.protocol.AuthOutcome
import com.che.ls05.protocol.UteWeather
import com.che.ls05.protocol.WatchType
import com.che.ls05.protocol.band8.B8Battery
import com.che.ls05.protocol.band8.B8DeviceInfo
import com.che.ls05.protocol.band8.B8Notification
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

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
        val NOTIFICATION_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss", Locale.ROOT)
    }
}
