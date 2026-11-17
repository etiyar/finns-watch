package com.che.ls05

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.che.ls05.protocol.Ls05Events
import com.che.ls05.protocol.SettingCommands
import com.che.ls05.protocol.UteCommand
import com.che.ls05.protocol.UteDate
import com.che.ls05.protocol.UteFrame
import com.che.ls05.protocol.UteHealth
import com.che.ls05.protocol.UteMusic
import com.che.ls05.protocol.UteWeather
import com.che.ls05.protocol.WatchType
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * Haylou LS05 sürücüsü. Ls05Ble'yi sarar; komut baytları ve log etiketleri v0.11 ile aynıdır.
 * Gelen UTE çerçeveleri ortak olaylara çevrilir (Ls05Events); pil, firmware ve ekran olayları için çerçeve
 * WatchHub'a da verilir (v0.11'deki gibi). Kadran yükleme yalnızca LS05'te var: [face], [startFace].
 */
class Ls05Driver(
    private val context: Context,
    private val listener: DriverListener,
) : WatchDriver, Ls05Ble.Listener {

    override val type = WatchType.LS05

    val ble = Ls05Ble(context, this)

    private val main = Handler(Looper.getMainLooper())

    /** Gecikmeli komutlar için etiket; sürücü bırakılınca hepsi iptal edilir. */
    private val delayedToken = Any()

    /** Her hazır olmada artar. Gecikmeli komut yalnızca aynı bağlantıda gider. */
    private var link = 0

    val face: FaceUpload by lazy {
        FaceUpload(ble) {
            if (face.phase == FaceUpload.Phase.DONE && WatchHub.faceId.isNotEmpty()) {
                Prefs.setCurrentFace(context, WatchHub.faceId)
            }
            listener.onBusyEnded()
        }
    }

    /** Ayarlar ekranından. Başlatılamazsa nedeni döner. */
    fun startFace(file: com.che.ls05.protocol.FaceFile, id: String): String? {
        if (face.active) return "Yükleme zaten sürüyor."
        WatchHub.faceId = id
        return face.start(file)
    }

    // ---------- Ls05Ble.Listener ----------

    override fun onLog(line: String) = listener.onLog(line)

    override fun onStatus(status: String) = listener.onStatus(status)

    override fun onDeviceFound(device: BluetoothDevice, name: String, rssi: Int) =
        listener.onDeviceFound(device, name, rssi)

    override fun onReadyChanged(ready: Boolean) {
        if (ready) link++
        listener.onReadyChanged(ready)
    }

    override fun onDisconnected(userInitiated: Boolean, status: Int) {
        face.onDisconnected()
        listener.onDisconnected(userInitiated, status)
    }

    override fun onAuthResult(code: Int?) = listener.onAuth(Ls05Events.auth(code))

    override fun onRawFrame(value: ByteArray) {
        face.onRaw(value)
        listener.onHealthRaw(value)
    }

    override fun onFrame(frame: UteFrame) {
        Ls05Events.of(frame)?.let { listener.onEvent(it) }
        WatchHub.frame(frame)
    }

    override val isConnected: Boolean get() = ble.isConnected
    override val isReady: Boolean get() = ble.isReady
    override val busy: Boolean get() = face.active

    override fun startScan() = ble.startScan()

    override fun connect(device: BluetoothDevice, autoConnect: Boolean) {
        ble.userId = Prefs.userIdBytes(context)
        ble.connect(device, autoConnect)
    }

    override fun disconnect() = ble.disconnect()

    override fun forceClose(reason: String) = ble.forceClose(reason)

    override fun release(reason: String) {
        main.removeCallbacksAndMessages(delayedToken)
        ble.stopScan()
        if (ble.isConnected) ble.forceClose(reason)
    }

    override fun sendNotification(title: String, body: String, vibrate: Boolean, type: Int): Boolean =
        ble.sendNotification(title, body, vibrate, type) > 0

    override fun sendIncomingCall(name: String): Boolean = ble.sendIncomingCall(name) > 0

    override fun sendCallStop(): Boolean = ble.sendCallStop()

    override fun sendTime(now: ZonedDateTime, label: String) {
        val offsetHours = now.offset.totalSeconds / 3600
        ble.sendCommand(
            UteCommand.timeSync(now.year, now.monthValue, now.dayOfMonth, now.hour, now.minute, now.second, offsetHours),
            label,
        )
    }

    override fun sendSetting(key: String, value: String): Boolean {
        val cmd: ByteArray? = try {
            SettingCommands.build(key, value)
        } catch (e: Exception) {
            null
        }
        if (cmd == null) return false
        ble.sendCommand(cmd, "ayar $key")
        return true
    }

    /** Adım hemen, nabız 1,5 sn sonra, includeSleep ise uyku 4 sn sonra (v0.11 sırası). */
    override fun requestHistory(from: LocalDate, includeSleep: Boolean) {
        val d = UteDate(from.year, from.monthValue, from.dayOfMonth)
        val s = link
        ble.sendCommand(UteHealth.stepHistory(d), "adım geçmişi (0a)")
        main.postAtTime(Runnable {
            if (link == s && ble.isReady) ble.sendCommand(UteHealth.heartHistory(d), "nabız geçmişi (18 FA)")
        }, delayedToken, SystemClock.uptimeMillis() + 1_500)
        if (includeSleep) {
            main.postAtTime(Runnable {
                if (link == s && ble.isReady) ble.sendCommand(UteHealth.sleepHistory(d), "uyku geçmişi (1d 01)")
            }, delayedToken, SystemClock.uptimeMillis() + 4_000)
        }
    }

    /** Gelen arama titreşimi (06 00 00 00 FF FF 00 00); cihazda doğrulandı. */
    override fun findStart() {
        ble.sendCommand(UteCommand.VIBRATE_CALL, "saati bul: sürekli titreşim (06 FF FF)")
    }

    override fun findStop(reason: String) {
        if (ble.isReady) ble.sendCommand(UteCommand.VIBRATE_STOP, "saati bul durdu (06 00): $reason")
    }

    override fun sendMusicState(playing: Boolean, volumePercent: Int) {
        ble.sendCommand(UteMusic.playState(playing), "müzik durumu (12 0C)")
        ble.sendCommand(UteMusic.volume(volumePercent), "ses düzeyi (12 0D)")
    }

    override fun sendWeather(current: Int, days: List<UteWeather.Day>, city: String) {
        val packets = UteWeather.build(current, days, city)
        packets.forEachIndexed { i, p -> ble.sendCommand(p, "hava durumu ${i + 1}/3 (11)") }
    }
}
