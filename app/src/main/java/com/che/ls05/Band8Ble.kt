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
import android.os.SystemClock
import com.che.ls05.protocol.band8.B8Auth
import com.che.ls05.protocol.band8.B8Battery
import com.che.ls05.protocol.band8.B8Char
import com.che.ls05.protocol.band8.B8Channel
import com.che.ls05.protocol.band8.B8Command
import com.che.ls05.protocol.band8.B8DeviceInfo
import com.che.ls05.protocol.band8.B8Frame
import com.che.ls05.protocol.band8.B8Incoming
import com.che.ls05.protocol.band8.B8Messages
import com.che.ls05.protocol.band8.B8Notification
import com.che.ls05.protocol.band8.B8PhoneInfo
import com.che.ls05.protocol.band8.B8Sub
import com.che.ls05.protocol.band8.B8Type
import com.che.ls05.protocol.band8.B8Write
import java.util.Locale
import java.util.UUID

/**
 * Band 8 BLE oturumu, kimlik doğrulaması ve şifreli komut iletimi.
 */
@SuppressLint("MissingPermission")
class Band8Ble(private val context: Context, private val listener: Listener, private val watchId: String) {
    interface Listener {
        fun onLog(message: String)
        fun onStatus(message: String)
        fun onFound(device: BluetoothDevice, name: String, rssi: Int)
        fun onReady(ready: Boolean)
        fun onDisconnected(userInitiated: Boolean, status: Int)
        fun onKeyRequired()
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
    private var writeAttempt = 0
    private var mtu = 23
    private var generation = 0
    private var nextFindTestAt = 0L
    private val commandTimeout = Runnable {
        if (ready && channel.pending > 0) {
            listener.onLog("Band 8 komut zaman aşımı: bekleyen=${channel.pending}, yazımKuyruğu=${writes.size}, yazıyor=$writing; bağlantı yenileniyor")
            listener.onStatus("Band 8 aktarım onayı zaman aşımına uğradı")
            close(false, -1)
        }
    }

    val isConnected: Boolean get() = gatt != null
    val isReady: Boolean get() = ready

    fun sendAppNotification(note: B8Notification): Boolean {
        if (!ready || released || gatt == null || channel.pending >= MAX_PENDING_COMMANDS) {
            listener.onLog("Band 8 bildirim reddi: hazır=$ready, serbest=${!released}, gatt=${gatt != null}, bekleyen=${channel.pending}")
            return false
        }
        listener.onLog("Band 8 bildirim komutu sıraya giriyor: bekleyen=${channel.pending}, yazımKuyruğu=${writes.size}")
        sendAuthenticated(B8Messages.notification(note))
        return ready
    }

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
            val record = result.scanRecord
            val advertisedFe95 = record?.serviceUuids?.any { it.uuid == serviceId } == true ||
                record?.serviceData?.keys?.any { it.uuid == serviceId } == true
            val name = record?.deviceName ?: result.device.name
            // Bazı saatler adsız yayın yapar. Yakın adsız cihazları sınırlı sayıda gösterip
            // seçildiklerinde FE95 servislerini salt-okunur biçimde doğrularız.
            if (name.isNullOrBlank() && !advertisedFe95 && result.rssi < -85) return
            main.post {
                if (!scanning || released || !seen.add(result.device.address)) return@post
                if (name.isNullOrBlank() && !advertisedFe95 && scanResults >= 12) return@post
                scanResults++
                val label = when {
                    advertisedFe95 -> "${name?.takeIf { it.isNotBlank() } ?: "Xiaomi BLE cihazı"} · FE95"
                    !name.isNullOrBlank() -> name
                    else -> "İsimsiz Bluetooth cihazı · …${result.device.address.takeLast(5)}"
                }
                listener.onFound(result.device, label, result.rssi)
                listener.onStatus("$scanResults Bluetooth cihazı bulundu; Band 8 olduğunu doğrula")
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
        // Bağlantı öncesi anahtar istenmez. İlk olarak FE95 servisi ve gerekli kanallar doğrulanır.
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
        if (gatt != null) listener.onLog("Band 8 bağlantı kapanıyor: kullanıcı=$byUser, durum=$status, hazır=$ready, bekleyen=${channel.pending}, yazımKuyruğu=${writes.size}")
        generation++
        main.removeCallbacks(commandTimeout)
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
                    listener.onLog("Band 8 GATT bağlandı; kimlik doğrulama bekleniyor")
                    listener.onStatus("Band 8 servisleri aranıyor…")
                    if (!g.requestMtu(247)) g.discoverServices()
                } else if (state == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    listener.onLog("Band 8 GATT koptu: durum=$status")
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
                    listener.onLog("Band 8 BLE yazım sonucu: hata=$status")
                    listener.onStatus("Band 8 yazma hatası")
                    close(false, status)
                } else {
                    listener.onLog("Band 8 BLE yazım sonucu: kabul, yazımKuyruğu=${writes.size}")
                    main.postDelayed({ drainWrites() }, 15)
                }
            }
        }
    }

    private fun discovered(g: BluetoothGatt, status: Int) {
        val service = if (status == BluetoothGatt.GATT_SUCCESS) g.getService(serviceId) else null
        if (service == null) {
            listener.onAuthFailed("Seçilen cihaz Xiaomi Smart Band 8 değil veya FE95 servisini sunmuyor. Başka cihaz seç.")
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

    /** FE95 doğrulandıktan sonra anahtar kaydedilirse aynı GATT oturumunda devam eder. */
    fun resumeAuth(): Boolean {
        if (released || gatt == null || chars.isEmpty() || ready) return false
        startAuth()
        return true
    }

    private fun startAuth() {
        if (auth != null || ready || gatt == null) return
        val key = SecretStore.get(context, watchId)
        if (key == null || key.size != 16) {
            key?.fill(0)
            listener.onStatus("Xiaomi FE95 servisi bulundu; Band 8 anahtarı gerekli")
            listener.onKeyRequired()
            return
        }
        authKey = key
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

    /** Durdurma komutu cihazda doğrulanana kadar yalnızca elle denenir. */
    fun testFindBand(start: Boolean): Boolean {
        val now = SystemClock.elapsedRealtime()
        val reason = when {
            !ready -> "hazır değil"
            released -> "sürücü kapalı"
            gatt == null -> "GATT yok"
            channel.session == null -> "oturum yok"
            channel.pending != 0 -> "bekleyen komut"
            channel.counter >= 0xFFFF -> "sayaç sınırı"
            writing -> "BLE yazımı sürüyor"
            writes.isNotEmpty() -> "BLE yazım kuyruğu dolu"
            now < nextFindTestAt -> "bir saniyelik bekleme"
            else -> null
        }
        if (reason != null) {
            listener.onLog("Band 8 bulma testi reddi: $reason; bekleyen=${channel.pending}, yazımKuyruğu=${writes.size}, yazıyor=$writing, oturum=${channel.session != null}")
            return false
        }
        val frames = channel.send(B8Messages.findBand(start))
        if (frames.isEmpty()) {
            listener.onLog("Band 8 bulma testi reddi: komut çerçevesi üretilmedi")
            return false
        }
        nextFindTestAt = now + 1_000L
        enqueue(frames)
        armCommandTimeout()
        if (!ready) return false
        listener.onLog("Band 8 bulma testi: " + if (start) "başlatma isteği gönderildi" else "durdurma isteği gönderildi")
        return true
    }

    /** Kayıtta arama olmadığı için yalnızca Gelişmiş ekranındaki kontrollü deneme. */
    fun testCallScreen(start: Boolean, timestamp: String): Boolean {
        if (!ready || released || gatt == null || channel.session == null ||
            channel.pending >= MAX_PENDING_COMMANDS ||
            (start && (channel.pending != 0 || writing || writes.isNotEmpty()))
        ) {
            listener.onLog("Band 8 arama ekranı testi reddi: hazır=$ready, bekleyen=${channel.pending}, yazımKuyruğu=${writes.size}")
            return false
        }
        val message = if (start) B8Messages.incomingCall("Test araması", "", timestamp) else B8Messages.callEnded()
        sendAuthenticated(message)
        listener.onLog("Band 8 arama ekranı denemesi sıraya alındı: ${if (start) "aç" else "kapat"}")
        return ready
    }

    private fun enqueue(list: List<B8Write>) {
        for (w in list) writes.addLast(w)
        drainWrites()
    }

    private fun sendAuthenticated(command: ByteArray) {
        if (!ready) return
        val wasIdle = channel.pending == 0
        val frames = channel.send(command)
        val parsed = B8Command.parse(command)
        listener.onLog("Band 8 komut kuyruğu: tür=${parsed?.type ?: -1}, altTür=${parsed?.subtype ?: -1}, bekleyen=${channel.pending}, çerçeve=${frames.size}")
        enqueue(frames)
        if (wasIdle) armCommandTimeout()
    }

    private fun armCommandTimeout() {
        main.removeCallbacks(commandTimeout)
        if (ready && channel.pending > 0) main.postDelayed(commandTimeout, COMMAND_TIMEOUT_MS)
    }

    private fun drainWrites() {
        if (writing || writes.isEmpty()) return
        val g = gatt ?: return
        val cmd = writes.removeFirst()
        val ch = chars[cmd.char] ?: return
        writing = true
        val attempt = ++writeAttempt
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
            if (serial == generation && writing && writeAttempt == attempt) {
                listener.onLog("Band 8 BLE yanıtsız yazım callback'i gelmedi; sıradaki pakete geçiliyor")
                writing = false
                drainWrites()
            }
        }, 100)
    }

    private fun received(ch: BluetoothGattCharacteristic, value: ByteArray) {
        val id = B8Char.values().firstOrNull { chars[it] === ch } ?: return
        val before = channel.pending
        val control = if (id == B8Char.CMD_OUT) when (val ev = B8Frame.decode(value)) {
            is B8Frame.Event.Ack -> "ACK"
            is B8Frame.Event.Ready -> "READY"
            is B8Frame.Event.AllReceived -> "ALL_RECEIVED"
            is B8Frame.Event.Nack -> "NACK(${ev.code})"
            is B8Frame.Event.Rejected -> "REJECTED"
            is B8Frame.Event.Resend -> "RESEND(${ev.indexes.size})"
            else -> null
        } else null
        val result = channel.onNotify(id, value)
        if (control != null) listener.onLog("Band 8 protokol yanıtı: $control, bekleyen=$before→${channel.pending}, yazılacak=${result.writes.size}")
        enqueue(result.writes)
        if (id == B8Char.CMD_OUT && (result.writes.isNotEmpty() || !channel.busy)) armCommandTimeout()
        for (event in result.incoming) {
            when (event) {
                is B8Incoming.Command -> processCommand(event.plain, event.encrypted)
                is B8Incoming.DecryptFailed -> listener.onLog("Band 8 mesajı doğrulanamadı")
                is B8Incoming.SendRejected -> listener.onLog("Band 8 isteği reddetti")
                is B8Incoming.ActivityPart -> Unit
            }
        }
    }

    private fun processCommand(plain: ByteArray, encrypted: Boolean) {
        val command = B8Command.parse(plain) ?: run {
            listener.onLog("Band 8 gelen komut çözümlenemedi; şifreli=$encrypted")
            return
        }
        listener.onLog("Band 8 gelen komut: tür=${command.type}, altTür=${command.subtype}, durum=${command.status ?: "yok"}, şifreli=$encrypted")
        // Saat aynı GATT bağlantısında yeni kimlik doğrulaması başlatabiliyor.
        // Eski oturum anahtarıyla gelen sonraki paketler çözülemez; mevcut B8Auth
        // DONE durumunda yeni 1/26 ve 1/27 yanıtlarını işleyemez.
        if (ready && !encrypted && command.type == B8Type.AUTH &&
            (command.subtype == B8Sub.AUTH_NONCE || command.subtype == B8Sub.AUTH_PROOF)
        ) {
            listener.onLog("Band 8 oturum yenileme isteği; bağlantı yeniden kuruluyor")
            close(false, -1)
            return
        }
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
                    sendAuthenticated(B8Messages.batteryRequest())
                    sendAuthenticated(B8Messages.deviceInfoRequest())
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
            ready && encrypted && command.matches(B8Type.SYSTEM, B8Sub.SYS_FIND_BAND) -> {
                val state = command.body(B8Type.F_SYSTEM)?.int(5)
                listener.onLog("Band 8 bulma yanıtı: durum=${state ?: "yok"}")
            }
        }
    }

    private companion object {
        const val MAX_PENDING_COMMANDS = 24
        const val COMMAND_TIMEOUT_MS = 8_000L
    }
}
