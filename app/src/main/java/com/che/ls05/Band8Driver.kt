package com.che.ls05

import android.bluetooth.BluetoothDevice
import android.content.Context
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
 * Band 8 bağlantısı, cihaz bilgisi ve normal uygulama bildirimleri.
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

    override val isConnected: Boolean get() = ble.isConnected
    override val isReady: Boolean get() = ble.isReady
    override val busy = false

    fun resumeAuth(): Boolean = ble.resumeAuth()

    override fun startScan() = ble.scan()
    override fun connect(device: BluetoothDevice, autoConnect: Boolean) = ble.connect(device, autoConnect)
    override fun disconnect() = ble.disconnect()
    override fun forceClose(reason: String) = ble.forceClose()
    override fun release(reason: String) = ble.release()

    override fun sendNotification(title: String, body: String, vibrate: Boolean, type: Int): Boolean {
        val now = System.currentTimeMillis()
        return ble.sendAppNotification(B8Notification(
            packageName, "Finn's Watch", title, body, notificationTime(now), now.toInt(), "test:$now",
        ))
    }

    override fun sendAppNotification(note: WatchHub.Note, vibrate: Boolean): Boolean {
        val key = note.androidKey ?: return false
        if (note.kind != WatchHub.NoteKind.MESSAGE) return false
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
        if (ready) listener.onAuth(AuthOutcome(AuthOutcome.Kind.OK))
        listener.onReadyChanged(ready)
    }

    override fun onDisconnected(userInitiated: Boolean, status: Int) =
        listener.onDisconnected(userInitiated, status)

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
        val NOTIFICATION_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss", Locale.ROOT)
    }
}
