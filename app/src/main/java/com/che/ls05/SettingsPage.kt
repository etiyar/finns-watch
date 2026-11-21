package com.che.ls05

import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.che.ls05.protocol.SecretEnvelope
import com.che.ls05.protocol.UteCommand
import com.che.ls05.protocol.WatchType

// =====================================================================
// 4. Ayarlar: Saatlerim, Görünüm, Yedekleme, Bağlantılar ve veriler
// =====================================================================

class SettingsPage(act: MainActivity) : Page(act, "Ayarlar") {

    private val setup: Collapsible = ui.collapsible("Kurulum", "", R.drawable.ic_check, open = false)
    private var setupOpened = false
    private val watches = WatchesCard(act)

    // Bağlantı: tarama, bağlan, durdur, LS05 kimliği, Band 8 doğrudan bağlantı, saat eşitleme, firmware.
    private val connection = ui.collapsible("Bağlantı ve eşleştirme", "Tara, bağlan, durdur", null, open = false)
    private val idRow: NavRow = ui.navRow(null, "Kimlik", null, "") { editId() }
    private val deviceBox: LinearLayout = ui.vertical()
    private val directConnectButtons: LinearLayout = ui.vertical()
    private var scanCandidates = 0
    private var scanGeneration = 0
    private var bandKeyDialog: AlertDialog? = null
    private val autoTime: SwitchRow = ui.switchRow(
        "Bağlanınca saati eşitle",
        "Saat her bağlantıda telefonun saatine ayarlanır.",
        Prefs.autoTime(act),
    ) { Prefs.setAutoTime(act, it) }
    private val firmwareValue: TextView = ui.text("", 15f, R.color.text2)

    private val theme: Segmented = ui.segmented(listOf("Sistem", "Koyu", "Açık"), Prefs.themeMode(act)) { act.setThemeMode(it) }
    private val saaCard = SaaCard(act, ui)
    private val saaRow: NavRow
    val advanced = AdvancedScreen(act)

    init {
        ui.add(body, ui.header("Ayarlar"))
        // Kurulum: yalnızca eksik adım varsa görünür; dokununca ayrıntılı liste.
        ui.add(body, setup.view, topDp = 12)

        ui.add(body, watches.view, topDp = 14)

        ui.add(connection.content, idRow.view)
        val btns = ui.horizontal()
        ui.add(btns, ui.secondary("Tara") { scan() }, width = 0, weight = 1f)
        ui.add(btns, ui.tonal("Bağlan") { connectSaved() }, width = 0, weight = 1f, leftDp = 8)
        ui.add(btns, ui.secondary("Durdur") { WatchService.start(act, WatchService.ACTION_STOP) },
            width = 0, weight = 1f, leftDp = 8)
        ui.add(connection.content, btns, topDp = 4)
        ui.add(directConnectButtons, ui.row("Kayıtlı Bluetooth cihazlarından seç", "Tarama gerektirmez; mevcut eşleşmeler", onClick = { showBondedDevices() }))
        ui.add(directConnectButtons, ui.row("Bluetooth adresiyle bağlan", "Önceki bağlantı kaydındaki adresi kullan", onClick = { enterKnownBluetoothAddress() }))
        ui.add(connection.content, directConnectButtons, topDp = 6)
        ui.add(connection.content, deviceBox, topDp = 8)
        ui.add(connection.content, ui.divider(), height = ui.dp(1))
        ui.add(connection.content, autoTime.view)
        ui.add(connection.content, ui.divider(), height = ui.dp(1))
        ui.add(connection.content, ui.row("Firmware", trailing = firmwareValue))
        ui.add(body, connection.view, topDp = 12)

        // Görünüm
        ui.add(body, ui.section("Görünüm"), topDp = 20)
        val themeCard = ui.card(padDp = 16, padVDp = 14)
        ui.add(themeCard, ui.text("Tema", 15f))
        ui.add(themeCard, theme.view, topDp = 10)
        ui.add(body, themeCard, topDp = 8)

        // Yedekleme (BackupSection.kt)
        ui.add(body, ui.section("Yedekleme"), topDp = 20)
        ui.add(body, BackupSection.build(act, ui), topDp = 8)

        // Bağlantılar ve veriler
        ui.add(body, ui.section("Bağlantılar ve veriler"), topDp = 20)
        saaRow = ui.navRow(null, "Sleep as Android", "Uyku takibi bağlantısı", null) { showSaa() }
        val links = listOf(
            saaRow.view,
            ui.navRow(null, "Sağlık verilerini dışa aktar", "CSV ve ham arşiv", null) { askExport() }.view,
            ui.navRow(null, "İzinler", "Bildirim erişimi, pil, Bluetooth", null) { showPermissions() }.view,
            ui.navRow(null, "Gelişmiş ve log", "Denemeler, log, tanı", null) { advanced.show() }.view,
        )
        links.forEach { it.minimumHeight = ui.dp(60) }
        ui.add(body, ui.group(links), topDp = 8)

        // Hakkında
        val footer = ui.text(BuildIdentity.label, 12f, R.color.text2)
        footer.setGravity(Gravity.CENTER)
        ui.add(body, footer, topDp = 20)
    }

    override fun refresh() {
        refreshSetup()
        refreshWatchInfo()
        theme.select(Prefs.themeMode(act))
        onReady(WatchHub.ready)
    }

    fun onLog(line: String) = advanced.onLog(line)

    /** Konum izni penceresi kapandı (Saat > Saat ayarları > Son görülen yer). */
    fun onLocationPermission(granted: Boolean) = act.watch.onLocationPermission(granted)

    private fun showSaa() {
        saaCard.refresh()
        (saaCard.card.parent as? android.view.ViewGroup)?.removeView(saaCard.card)
        val box = ui.vertical()
        box.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), 0)
        ui.add(box, saaCard.card)
        val d = AlertDialog.Builder(act)
            .setTitle("Sleep as Android")
            .setView(ui.scroll(box))
            .setPositiveButton("Kapat", null)
            .create()
        act.track(d)
        d.show()
    }

    /** Adım, nabız ve uyku CSV olarak tek bir zip'e; önce aralık sorulur. Band 8 ham arşivi de buradan. */
    private fun askExport() {
        val ranges = HealthExport.RANGES
        val band = WatchRegistry.list(act).watches.any { it.type == WatchType.BAND8 }
        val labels = ranges.map { "CSV: ${it.first}" } + if (band) listOf("Band 8 ham arşivi (zip)") else emptyList()
        val dialog = AlertDialog.Builder(act)
            .setTitle("Sağlık verilerini dışa aktar")
            .setItems(labels.toTypedArray()) { d, which ->
                d.dismiss()
                if (which < ranges.size) HealthExport.start(act, ranges[which].second) else Band8RawExport.start(act)
            }
            .setNegativeButton("Vazgeç", null)
            .create()
        act.track(dialog)
        dialog.show()
    }

    /** İzinler ve kurulum denetimleri: her satır eksikse ilgili ayarı açar. */
    private fun showPermissions() {
        val box = ui.vertical()
        box.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), 0)
        for (item in act.setupItems()) {
            val (label, tone) = when (item.ok) {
                true -> "Tamam" to Tone.OK
                false -> "Eksik" to Tone.WARN
                null -> "Kontrol et" to Tone.NEUTRAL
            }
            ui.add(box, ui.row(item.label, trailing = ui.pill(label, tone), onClick = item.fix))
        }
        val d = AlertDialog.Builder(act).setTitle("İzinler").setView(ui.scroll(box))
            .setPositiveButton("Kapat", null).create()
        act.track(d)
        d.show()
    }

    /** Özet satırı: eksik sayısı. Hepsi tamamsa görünmez. Eksik varsa liste ilk açılışta açık gelir. */
    private fun refreshSetup() {
        val items = act.setupItems()
        val missing = items.count { it.ok == false }
        if (missing == 0) {
            setup.view.visibility = View.GONE
            return
        }
        setup.view.visibility = View.VISIBLE
        setup.subtitle.text = "$missing adım eksik"
        setup.subtitle.setTextColor(ui.color(R.color.warning))
        ui.setIcon(setup.icon, R.drawable.ic_alert, R.color.warning)
        setup.subtitle.visibility = View.VISIBLE
        if (!setupOpened) {
            setupOpened = true
            setup.setOpen(true)
        }
        setup.content.removeAllViews()
        for (item in items) {
            val (label, tone) = when (item.ok) {
                true -> "Tamam" to Tone.NEUTRAL
                false -> "Eksik" to Tone.WARN
                null -> "Kontrol et" to Tone.NEUTRAL
            }
            ui.add(setup.content, ui.row(item.label, trailing = ui.pill(label, tone), onClick = item.fix))
        }
    }

    /** Saat kurulumu: bağlantı bölümünü açar ve ona kaydırır. */
    fun openWatchConnection() {
        act.selectTab(3)
        connection.setOpen(true)
        root.post { root.smoothScrollTo(0, connection.view.top) }
    }

    fun refreshWatchInfo() {
        watches.refresh()
        saaCard.refresh()
        saaRow.value.text = if (Prefs.saaEnabled(act)) "Açık" else "Kapalı"
        saaRow.value.visibility = View.VISIBLE
        val band = WatchRegistry.active(act)?.type == WatchType.BAND8
        idRow.view.visibility = if (band) View.GONE else View.VISIBLE
        directConnectButtons.visibility = if (band) View.VISIBLE else View.GONE
        autoTime.view.visibility = if (band) View.GONE else View.VISIBLE
        idRow.value.text = if (hasId()) "Kayıtlı" else "Girilmedi"
        autoTime.toggle.set(Prefs.autoTime(act))
        firmwareValue.text = WatchHub.firmware ?: "bilinmiyor"
        // Kurulum gerekiyorsa bağlantı bölümü açık gelsin.
        if (WatchRegistry.active(act) != null && !WatchHub.ready && Prefs.address(act) == null) connection.setOpen(true)
    }

    fun onReady(ready: Boolean) {
        advanced.onReady(ready)
        if (ready) {
            ++scanGeneration
            deviceBox.removeAllViews()
        }
    }

    // ---------- Kimlik ----------

    private fun hasId(): Boolean = UteCommand.parseUserId(Prefs.userIdText(act)) != null


    /** Kimlik ekranda ve logda gösterilmez; kutu boş açılır. */
    private fun editId() {
        val box = ui.dialogBox()
        ui.add(box, ui.caption("8 haneli onaltılık kimlik. Yalnızca telefonda saklanır, ekranda ve logda gösterilmez."), topDp = 8)
        val input = ui.field("Kimlik (8 hane)")
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        ui.add(box, input, topDp = 16)
        val dialog = AlertDialog.Builder(act)
            .setTitle("Kimlik")
            .setView(box)
            .setPositiveButton("Kaydet", null)
            .setNegativeButton("Vazgeç", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = input.text.toString().trim()
                if (UteCommand.parseUserId(text) == null) {
                    WatchHub.log("Kimlik geçersiz veya boş: 8 haneli onaltılık değer gerekli.")
                    act.toast("Kimlik geçersiz: 8 haneli onaltılık değer gerekli.")
                } else {
                    Prefs.setUserIdText(act, text)
                    WatchHub.log("Kimlik kaydedildi.")
                    refreshWatchInfo()
                    dialog.dismiss()
                }
            }
        }
        act.track(dialog)
        dialog.show()
    }

    // ---------- Tarama ve bağlantı ----------

    private fun scan() {
        if (!act.hasBlePermissions()) {
            act.requestBlePermissions()
            return
        }
        deviceBox.removeAllViews()
        scanCandidates = 0
        val generation = ++scanGeneration
        ui.add(deviceBox, ui.caption("10 saniye boyunca yakındaki Bluetooth cihazları aranıyor…"), topDp = 8)
        act.handler.postDelayed({
            if (scanGeneration == generation && scanCandidates == 0) {
                deviceBox.removeAllViews()
                ui.add(deviceBox, ui.caption("Cihaz bulunamadı. Band 8'i yakına getir, Mi Fitness ve Notify bağlantılarını kapat, ardından tekrar Tara'ya bas."), topDp = 8)
            }
        }, 11_000L)
        if (WatchHub.service == null) WatchService.start(act)
        act.toast("Saat aranıyor.")
        act.handler.postDelayed({
            val s = WatchHub.service
            if (s == null) WatchHub.log("Servis başlamadı; Bluetooth iznini kontrol et.") else s.startScan()
        }, 600L)
    }

    private fun connectSaved() {
        if (!act.hasBlePermissions()) {
            act.requestBlePermissions()
            return
        }
        val active = WatchRegistry.active(act)
        if (active?.type == WatchType.BAND8) {
            if (!SecretStore.has(act, active.id)) {
                if (Prefs.address(act) != null && WatchHub.service?.resumeBandAuth() == true) return
                act.toast("Önce Tara ile Band 8'i seç; anahtar cihaz doğrulandıktan sonra istenecek.")
                return
            }
        } else if (!hasId()) {
            WatchHub.log("Kimlik geçersiz veya boş: 8 haneli onaltılık değer gerekli.")
            act.toast("Önce kimliği gir.")
            editId()
            return
        }
        val address = Prefs.address(act)
        if (address == null) {
            WatchHub.log("Kayıtlı saat yok; önce Tara ile saati seç.")
            act.toast("Kayıtlı saat yok. Önce Tara ile saati seç.")
            return
        }
        WatchService.start(act, WatchService.ACTION_CONNECT_NEW, address)
    }

    fun onDeviceFound(device: BluetoothDevice, name: String, rssi: Int) {
        if (scanCandidates++ == 0) deviceBox.removeAllViews()
        val candidate = name.contains("Band 8", true) || name.contains("M2239B1", true) ||
            name.contains("FE95", true)
        val subtitle = if (WatchRegistry.active(act)?.type == WatchType.BAND8) {
            if (candidate) "Olası Band 8 · servisi doğrula · $rssi dBm"
            else "Diğer Bluetooth cihazı · FE95 kontrolü · $rssi dBm"
        } else "Bu cihazı seç · $rssi dBm"
        ui.add(deviceBox, ui.row(name, subtitle, onClick = { useDevice(device) }))
    }

    private fun useDevice(device: BluetoothDevice) {
        if (WatchRegistry.active(act)?.type != WatchType.BAND8 && !hasId()) {
            act.toast("Önce kimliği gir.")
            editId()
            return
        }
        val active = WatchRegistry.active(act)
        if (active?.type == WatchType.BAND8) {
            val likely = device.name?.let {
                it.contains("Band 8", true) || it.contains("M2239B1", true)
            } == true
            if (!likely) {
                val dlg = AlertDialog.Builder(act)
                    .setTitle("Cihazı doğrula")
                    .setMessage("Bu cihazın Band 8 olduğu kesin değil. Xiaomi FE95 servis kontrolü yapılacak. Eşleşme veya sıfırlama yapılmaz.")
                    .setPositiveButton("Servisi kontrol et") { _, _ ->
                        deviceBox.removeAllViews()
                        WatchService.start(act, WatchService.ACTION_CONNECT_NEW, device.address)
                    }
                    .setNegativeButton("Vazgeç", null)
                    .create()
                act.track(dlg)
                dlg.show()
                return
            }
        }
        deviceBox.removeAllViews()
        WatchService.start(act, WatchService.ACTION_CONNECT_NEW, device.address)
    }

    /** Sadece Android'in zaten eşleştirdiği cihazları göster; yeni bond oluşturmaz. */
    @Suppress("MissingPermission")
    private fun showBondedDevices() {
        if (WatchRegistry.active(act)?.type != WatchType.BAND8) return
        if (!act.hasBlePermissions()) {
            act.requestBlePermissions()
            return
        }
        val adapter = act.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter?.isEnabled != true) {
            act.toast("Bluetooth'u aç ve tekrar dene.")
            return
        }
        val devices = try { adapter.bondedDevices.orEmpty().sortedBy { it.name ?: "" } }
            catch (_: SecurityException) {
                act.toast("Kayıtlı Bluetooth cihazları için izin gerekli.")
                return
            }
        if (devices.isEmpty()) {
            act.toast("Android'de kayıtlı Bluetooth cihazı bulunamadı. Adresle bağlantıyı dene.")
            return
        }
        val names = devices.map { device ->
            val name = device.name?.takeIf { it.isNotBlank() } ?: "İsimsiz kayıtlı cihaz"
            "$name · …${device.address.takeLast(5)}"
        }
        val d = AlertDialog.Builder(act)
            .setTitle("Kayıtlı Bluetooth cihazları")
            .setMessage("Band 8'i seç. Bağlantı kurulursa Xiaomi FE95 servisi doğrulanır; eşleşmeler silinmez.")
            .setItems(names.toTypedArray()) { _, index -> connectKnownDevice(devices[index].address) }
            .setNegativeButton("Vazgeç", null)
            .create()
        act.track(d)
        d.show()
    }

    /** Kullanıcı girer. Tam adres ekranda, logda veya GitHub'da gösterilmez. */
    private fun enterKnownBluetoothAddress() {
        if (WatchRegistry.active(act)?.type != WatchType.BAND8) return
        if (!act.hasBlePermissions()) {
            act.requestBlePermissions()
            return
        }
        val box = ui.dialogBox()
        ui.add(box, ui.caption("Önceki Bluetooth bağlantı kaydındaki 6 baytlık adresi yaz. Örnek biçim: XX:XX:XX:XX:XX:XX. Eşleşme değiştirilmez."), topDp = 8)
        val input = ui.field("Bluetooth adresi")
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        input.filters = arrayOf(android.text.InputFilter.LengthFilter(17))
        ui.add(box, input, topDp = 12)
        val d = AlertDialog.Builder(act)
            .setTitle("Bilinen adrese bağlan")
            .setView(box)
            .setPositiveButton("Bağlan", null)
            .setNegativeButton("Vazgeç", null)
            .create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val address = input.text.toString().trim().uppercase(java.util.Locale.ROOT)
                if (!BluetoothAdapter.checkBluetoothAddress(address)) {
                    act.toast("Geçerli Bluetooth adresi gir. Biçim: XX:XX:XX:XX:XX:XX")
                    return@setOnClickListener
                }
                input.text.clear()
                d.dismiss()
                connectKnownDevice(address)
            }
        }
        act.track(d)
        d.show()
    }

    /** Tarama olmadan GATT bağlantısını dener; FE95 kontrolü Band8Ble'de kalır. */
    private fun connectKnownDevice(address: String) {
        if (WatchRegistry.active(act)?.type != WatchType.BAND8) return
        if (!act.hasBlePermissions()) {
            act.requestBlePermissions()
            return
        }
        ++scanGeneration
        deviceBox.removeAllViews()
        ui.add(deviceBox, ui.caption("Bilinen Bluetooth adresine bağlanılıyor. Xiaomi FE95 servisi doğrulanacak…"), topDp = 8)
        if (!WatchService.start(act, WatchService.ACTION_CONNECT_NEW, address)) {
            act.toast("Saat servisi başlatılamadı. Uygulamadan tekrar dene.")
        }
    }

    /** İlk GATT servis doğrulamasından sonra istenir; anahtar kullanıcıya geri gösterilmez. */
    fun promptBandKey() {
        val active = WatchRegistry.active(act) ?: return
        if (active.type != WatchType.BAND8 || bandKeyDialog?.isShowing == true) return
        val box = ui.dialogBox()
        ui.add(box, ui.caption("Xiaomi BLE protokol servisi bulundu. Eşleştirilmiş Band 8 için 32 haneli anahtarı gir. Anahtar telefonda şifreli saklanır."), topDp = 8)
        val input = ui.field("Band 8 anahtarı")
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        input.filters = arrayOf(android.text.InputFilter.LengthFilter(40))
        ui.add(box, input, topDp = 12)
        val d = AlertDialog.Builder(act)
            .setTitle("Band 8 kimlik doğrulama")
            .setView(box)
            .setPositiveButton("Bağlan", null)
            .setNegativeButton("Vazgeç") { _, _ -> WatchHub.service?.cancelBandSetup() }
            .create()
        bandKeyDialog = d
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().trim()
                val bytes = SecretEnvelope.parseKeyHex(value)
                if (bytes == null) {
                    act.toast("32 haneli geçerli onaltılık anahtar gerekli.")
                    return@setOnClickListener
                }
                bytes.fill(0)
                if (!SecretStore.putHex(act, active.id, value)) {
                    act.toast("Anahtar güvenli biçimde kaydedilemedi.")
                    return@setOnClickListener
                }
                input.text.clear()
                d.dismiss()
                if (WatchHub.service?.resumeBandAuth() != true) {
                    val address = Prefs.address(act)
                    if (address != null) WatchService.start(act, WatchService.ACTION_CONNECT_NEW, address)
                    else act.toast("Bağlantı kesildi; yeniden Tara'ya bas.")
                }
            }
        }
        d.setOnDismissListener { if (bandKeyDialog === d) bandKeyDialog = null }
        act.track(d)
        d.show()
    }
}
