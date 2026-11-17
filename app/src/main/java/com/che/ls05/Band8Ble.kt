package com.che.ls05

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.che.ls05.protocol.band8.B8Auth
import com.che.ls05.protocol.band8.B8Battery
import com.che.ls05.protocol.band8.B8Char
import com.che.ls05.protocol.band8.B8Channel
import com.che.ls05.protocol.band8.B8Command
import com.che.ls05.protocol.band8.B8DeviceInfo
import com.che.ls05.protocol.band8.B8Incoming
import com.che.ls05.protocol.band8.B8Messages
import com.che.ls05.protocol.band8.B8PhoneInfo
import com.che.ls05.protocol.band8.B8Write
import java.util.Locale
import java.util.UUID

/**
 * Band 8 için ilk, salt-okunur BLE bağlantı aşaması.
 * Auth sonrası yalnızca pil ve cihaz bilgisi istenir. Firmware, eşleşme ve sağlık verisi değiştirilmez.
 */
@SuppressLint("MissingPermission")
class Band8Ble(private val context: Context, private val listener: Listener, private val watchId: String) {
    interface Listener {
        fun onLog(message: String)
        fun onStatus(message: String)
        fun onFound(device: BluetoothDevice, name: String, rssi: Int)
        fun onReady(ready: Boolean)
        fun onDisconnected(userInitiated: Boolean, status: Int)
        fun onAuthFailed(message: String)
        fun onBattery(value: B8Battery)
        fun onDeviceInfo(value: B8DeviceInfo)
    }

    private val main = Handler(Looper.getMainLooper())
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val serviceId = UUID.fromString("0000fe95-0000-1000-8000-00805f9b34fb")
    private val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private var gatt: BluetoothGatt? = null
    private var channel = B8Channel()
    private var auth: B8Auth? = null
    private var authKey: ByteArray? = null
    private val chars = HashMap<B8Char, BluetoothGattCharacteristic>()
    private val writes = ArrayDeque<B8Write>()
    private val notifications = ArrayDeque<B8Char>()
    private val seen = HashSet<String>()
    private var scanning = false
    private var scanResults = 0
    private var scanSession = 0
    private var ready = false
    private var userDisconnect = false
    private var released = false
    private var writing = false
    private var mtu = 23
    private var generation = 0

    val isConnected: Boolean get() = gatt != null
    val isReady: Boolean get() = ready

    fun scan() {
        val scanner = adapter?.bluetoothLeScanner ?: run { listener.onStatus("Bluetooth taraması başlatılamadı"); return }
        if (scanning || released) return
        seen.clear()
        scanResults = 0
        scanning = true
        val session = ++scanSession
        try {
            scanner.startScan(scanCallback)
            listener.onStatus("Bluetooth cihazları aranıyor…")
            main.postDelayed({ if (scanning && session == scanSession) stopScan() }, 10_000)
        } catch (e: Exception) {
            scanning = false
            listener.onStatus("Bluetooth taraması başlatılamadı; izinleri kontrol et")
        }
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        ++scanSession
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
        if (!isConnected && !released) {
            listener.onStatus(if (scanResults == 0) "Cihaz bulunamadı; Band 8'in Bluetooth'unu kontrol et"
                              else "$scanResults Bluetooth cihazı bulundu; listeden Band 8'i seç")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // Band 8 bazı firmware sürümlerinde farklı adla yayın yapabilir.
            // Bu yüzden yalnızca modele göre filtrelemiyoruz. Cihazın FE95 servisi
            // bağlantı sonrasında doğrulanır; başka bir saate komut gönderilmez.
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            main.post {
                if (!scanning || released || !seen.add(result.device.address)) return@post
                scanResults++
                listener.onFound(result.device, name, result.rssi)
                listener.onStatus("$scanResults Bluetooth cihazı bulundu; Band 8'i listeden seç")
            }
        }
        override fun onScanFailed(errorCode: Int) {
            main.post {
                if (!scanning) return@post
                scanning = false
                ++scanSession
                listener.onStatus("Tarama başarısız (kod $errorCode)")
            }
        }
    }

    fun connect(device: BluetoothDevice, autoConnect: Boolean) {
        if (released || gatt != null) return
        stopScan()
        val key = SecretStore.get(context, watchId)
        if (key == null || key.size != 16) {
            key?.fill(0)
            listener.onAuthFailed("Band 8 kimlik anahtarı eksik veya okunamıyor")
            return
        }
        authKey = key
        userDisconnect = false
        listener.onStatus("Band 8 bağlanıyor…")
        try {
            gatt = device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            close(false, -1)
        }
    }

    fun disconnect() {
        userDisconnect = true
        val g = gatt ?: return
        try { g.disconnect() } catch (_: Exception) { close(true, -1) }
        main.postDelayed({ if (gatt === g) close(true, -1) }, 2_000)
    }

    fun forceClose() { close(false, -1) }

    fun release() {
        released = true
        stopScan()
        close(true, -1)
    }

    private fun close(byUser: Boolean, status: Int) {
        generation++
        val hadGatt = gatt != null
        val wasReady = ready
        ready = false
        val g = gatt
        gatt = null
        try { g?.close() } catch (_: Exception) {}
        chars.clear()
        writes.clear()
        notifications.clear()
        writing = false
        auth = null
        authKey?.fill(0)
        authKey = null
        channel.clear()
        channel.onDisconnected()
        channel = B8Channel()
        if (wasReady) listener.onReady(false)
        if (hadGatt && !released) listener.onDisconnected(byUser, status)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
            main.post {
                if (gatt !== g || released) return@post
                if (state == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    listener.onStatus("Band 8 servisleri aranıyor…")
                    if (!g.requestMtu(247)) g.discoverServices()
                } else if (state == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    close(userDisconnect, status)
                }
            }
        }
        override fun onMtuChanged(g: BluetoothGatt, value: Int, status: Int) {
            main.post {
                if (gatt !== g) return@post
                mtu = if (status == BluetoothGatt.GATT_SUCCESS) value else 23
                channel.mtu = mtu
                g.discoverServices()
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post { if (gatt === g) discovered(g, status) }
        }
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            main.post {
                if (gatt !== g) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    listener.onStatus("Band 8 bildirim kanalı açılamadı")
                    close(false, status)
                } else enableNext()
            }
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            main.post { if (gatt === g) received(ch, value) }
        }
        @Deprecated("Android 13 öncesi")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION") val value = ch.value?.clone() ?: return
            main.post { if (gatt === g) received(ch, value) }
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            main.post {
                if (gatt !== g || !writing) return@post
                writing = false
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    listener.onStatus("Band 8 yazma hatası")
                    close(false, status)
                } else main.postDelayed({ drainWrites() }, 15)
            }
        }
    }

    private fun discovered(g: BluetoothGatt, status: Int) {
        val service = if (status == BluetoothGatt.GATT_SUCCESS) g.getService(serviceId) else null
        if (service == null) {
            listener.onStatus("Band 8 protokol servisi bulunamadı")
            close(false, status)
            return
        }
        for (id in B8Char.values()) {
            val ch = service.getCharacteristic(UUID.fromString(id.uuid))
            if (ch == null || ch.getDescriptor(cccd) == null) {
                listener.onStatus("Band 8 kanal keşfi eksik")
                close(false, -1)
                return
            }
            chars[id] = ch
            notifications.addLast(id)
        }
        enableNext()
    }

    private fun enableNext() {
        val g = gatt ?: return
        val id = if (notifications.isEmpty()) null else notifications.removeFirst()
        if (id == null) { startAuth(); return }
        val ch = chars[id] ?: return
        if (!g.setCharacteristicNotification(ch, true)) {
            listener.onStatus("Band 8 bildirim dinleme açılamadı")
            close(false, -1)
            return
        }
        val desc = ch.getDescriptor(cccd) ?: return
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION") desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION") g.writeDescriptor(desc)
        }
        if (!ok) {
            listener.onStatus("Band 8 CCCD yazılamadı")
            close(false, -1)
        }
    }

    private fun startAuth() {
        val key = authKey ?: return
        val phone = B8PhoneInfo(Build.VERSION.SDK_INT, Build.MODEL, Locale.getDefault().country.ifBlank { "TR" })
        auth = B8Auth(key, phone)
        listener.onStatus("Band 8 kimlik doğruluyor…")
        enqueue(channel.beginAuth(auth!!.start()))
        val serial = generation
        main.postDelayed({
            if (generation == serial && !ready && gatt != null) {
                listener.onAuthFailed("Band 8 doğrulaması zaman aşımına uğradı")
                close(false, -1)
            }
        }, 15_000)
    }

    private fun enqueue(list: List<B8Write>) {
        for (w in list) writes.addLast(w)
        drainWrites()
    }

    private fun drainWrites() {
        if (writing || writes.isEmpty()) return
        val g = gatt ?: return
        val cmd = writes.removeFirst()
        val ch = chars[cmd.char] ?: return
        writing = true
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, cmd.bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) ==
                android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION") ch.value = cmd.bytes
            @Suppress("DEPRECATION") ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION") g.writeCharacteristic(ch)
        }
        if (!ok) {
            writing = false
            listener.onStatus("Band 8 iletim başlatılamadı")
            close(false, -1)
            return
        }
        val serial = generation
        // Bazı Android yığınları yanıtsız yazma callback'i göndermiyor.
        main.postDelayed({
            if (serial == generation && writing) {
                writing = false
                drainWrites()
            }
        }, 100)
    }

    private fun received(ch: BluetoothGattCharacteristic, value: ByteArray) {
        val id = B8Char.values().firstOrNull { chars[it] === ch } ?: return
        val result = channel.onNotify(id, value)
        enqueue(result.writes)
        for (event in result.incoming) {
            when (event) {
                is B8Incoming.Command -> processCommand(event.plain)
                is B8Incoming.DecryptFailed -> listener.onLog("Band 8 mesajı doğrulanamadı")
                is B8Incoming.SendRejected -> listener.onLog("Band 8 isteği reddetti")
                is B8Incoming.ActivityPart -> Unit
            }
        }
        // Onay gelmeyen gönderim, auth zaman aşımı ile sonlandırılır.
    }

    private fun processCommand(plain: ByteArray) {
        val command = B8Command.parse(plain) ?: return
        val step = auth?.onCommand(command)
        if (step != null) {
            when (step) {
                is B8Auth.Step.Send -> enqueue(channel.sendPlain(step.plain))
                is B8Auth.Step.Success -> {
                    enqueue(channel.setSession(step.session))
                    authKey?.fill(0)
                    authKey = null
                    ready = true
                    listener.onReady(true)
                    listener.onStatus("Band 8 bağlı")
                    enqueue(channel.send(B8Messages.batteryRequest()))
                    enqueue(channel.send(B8Messages.deviceInfoRequest()))
                }
                is B8Auth.Step.Failed -> {
                    listener.onAuthFailed("Band 8 kimlik doğrulaması başarısız: ${step.reason.name}")
                    close(false, -1)
                }
            }
            return
        }
        when {
            command.matches(2, 1) -> B8Messages.parseBattery(command)?.let(listener::onBattery)
            command.matches(2, 2) -> B8Messages.parseDeviceInfo(command)?.let(listener::onDeviceInfo)
        }
    }
}
