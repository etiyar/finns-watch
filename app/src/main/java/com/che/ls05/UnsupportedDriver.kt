package com.che.ls05

import android.bluetooth.BluetoothDevice
import com.che.ls05.protocol.UteWeather
import com.che.ls05.protocol.WatchType
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * Bu sürümde sürücüsü olmayan saat türü (ör. Band 8, sürücü yazılana kadar). Hiçbir şey göndermez, hiç bağlanmaz.
 * Kayıtta böyle bir saat etkin olsa da servis çökmez; durum "desteklenmiyor" olur.
 */
class UnsupportedDriver(override val type: WatchType, private val listener: DriverListener) : WatchDriver {
    override val isConnected = false
    override val isReady = false
    override val busy = false

    override fun startScan() = listener.onLog("Bu saat türü için tarama bu sürümde yok.")
    override fun connect(device: BluetoothDevice, autoConnect: Boolean) =
        listener.onStatus("${type.label} bu sürümde desteklenmiyor")
    override fun disconnect() = Unit
    override fun forceClose(reason: String) = Unit
    override fun release(reason: String) = Unit
    override fun sendNotification(title: String, body: String, vibrate: Boolean, type: Int) = false
    override fun sendIncomingCall(name: String) = false
    override fun sendCallStop() = false
    override fun sendTime(now: ZonedDateTime, label: String) = Unit
    override fun sendSetting(key: String, value: String) = false
    override fun requestHistory(from: LocalDate, includeSleep: Boolean) = Unit
    override fun findStart() = Unit
    override fun findStop(reason: String) = Unit
    override fun sendMusicState(playing: Boolean, volumePercent: Int) = Unit
    override fun sendWeather(current: Int, days: List<UteWeather.Day>, city: String) = Unit
}
