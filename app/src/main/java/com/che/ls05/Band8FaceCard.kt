package com.che.ls05

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.che.ls05.protocol.band8.B8FaceBin
import com.che.ls05.protocol.band8.B8FaceDesign
import com.che.ls05.protocol.band8.B8FaceFile
import com.che.ls05.protocol.band8.B8NavFace
import com.che.ls05.protocol.band8.B8Watchface

/**
 * Saat > Kadranlar, Band 8: galeri, bantta yerleşik kadranlar ve navigasyon kadranı.
 *
 * Galeri: depodaki (Band8FaceStore) kadranlar 3 sütunlu ızgarada önizlemesiyle; dokununca Yükle, Etkin yap, Sil.
 * Bantta olup depoda olmayanlar (yerleşikler) katlanır listede, adla (4/0). Liste bağlanınca bir kez okunur.
 * Her işlem tek dokunuşla tek komut gönderir. Protokol Band8Faces ve protocol/band8 içinde.
 */
class Band8FaceCard(private val act: MainActivity) {
    private val ui = act.ui
    val view: LinearLayout = ui.vertical()
    private val count: TextView = ui.text("", 12f, R.color.text2)
    private val grid: LinearLayout = ui.vertical()
    private val more: android.widget.Button = ui.outlineAccent("") { all = !all; refresh() }
    private val builtins = ui.collapsible("Bantta yerleşik", null, null, open = false)
    private val status: TextView = ui.caption("")
    private var all = false
    private var autoRead = false

    private var navAuto: SwitchRow? = null
    private val navTripped: TextView = ui.caption("")

    init {
        val head = ui.horizontal()
        ui.add(head, ui.text("Galeri", 13f, R.color.text2, bold = true), width = 0, weight = 1f)
        ui.add(head, count, width = Ui.WRAP)
        ui.add(view, head)
        ui.add(view, status, topDp = 8)
        ui.add(view, grid, topDp = 8)
        ui.add(view, more, topDp = 12)
        ui.add(view, builtins.view, topDp = 12)

        // Diğer: hazır kadran ve listeyi yeniden okuma.
        val other = ui.card(padDp = 16, padVDp = 4)
        ui.add(other, ui.row("Finn Dijital kadranını ekle", "Telefonda üretilir, dosya gerekmez") { ownTestFace() })
        ui.add(other, ui.divider(), height = ui.dp(1))
        ui.add(other, ui.row("Bant listesini yeniden oku", null) { withService { it.bandFaceRead() } })
        ui.add(view, other, topDp = 12)

        // Navigasyon kadranı (deneysel). Tam metin seçeneği Bildirimler bölümünde.
        val nav = ui.collapsible("Navigasyon kadranı (deneysel)", "Maps yönergesi bantta kadran olur", null, open = false)
        ui.add(nav.content, ui.caption("Büyük ok, mesafe, sokak. Her manevrada yüklenip etkin yapılır; navigasyon bitince önceki kadran geri gelir. Önce denemeyle yükleme süresine bak."))
        val auto = ui.switchRow(
            "Navigasyonu kadranda göster",
            "Bildirimler > Navigasyon yönergeleri açık olmalı. Mesafe tek başına değişince kadran yenilenmez.",
            Prefs.band8NavFace(act),
        ) { on ->
            Prefs.setBand8NavFace(act, on)
            // Elle açmak çökme korumasını sıfırlar.
            if (on) { Prefs.setBand8NavTripped(act, false); Band8NavFace.guard.reset() }
        }
        ui.add(nav.content, auto.view, topDp = 4)
        navAuto = auto
        ui.add(nav.content, navTripped, topDp = 2)
        val n = ui.horizontal()
        ui.add(n, ui.secondary("Örnek okla dene") { withService { it.bandNavTest() } }, width = 0, weight = 1f)
        ui.add(n, ui.secondary("Kaldır, eskiye dön") { withService { it.bandNavEnd() } }, width = 0, weight = 1f, leftDp = 8)
        ui.add(nav.content, n, topDp = 8)
        ui.add(view, nav.view, topDp = 12)
    }

    private fun withService(f: (WatchService) -> String?) {
        val s = act.readyService() ?: return
        f(s)?.let { act.toast(it) }
    }

    fun refresh() {
        navAuto?.toggle?.set(Prefs.band8NavFace(act))
        val tripped = Prefs.band8NavTripped(act)
        navTripped.text = if (tripped) "Navigasyon kadranı bandı yeniden başlattı, kapatıldı. Yeniden açmadan önce \"Örnek okla dene\" ile dene." else ""
        navTripped.visibility = if (tripped) View.VISIBLE else View.GONE
        // Liste bağlantı başına bir kez kendiliğinden okunur (yalnızca okuma, 4/0).
        if (Band8Faces.faces == null && WatchHub.ready && !autoRead) {
            autoRead = true
            WatchHub.service?.bandFaceRead()
        }
        if (!WatchHub.ready) autoRead = false
        status.text = Band8Faces.status
        status.visibility = if (Band8Faces.status.isEmpty()) View.GONE else View.VISIBLE
        val band = Band8Faces.faces
        val stored = Band8FaceStore.list(act)
        // Bantta etkin olan önce.
        val ordered = stored.sortedByDescending { e -> band?.firstOrNull { it.id == e.id }?.active == true }
        count.text = "${stored.size} kadran"
        grid.removeAllViews()
        if (stored.isEmpty()) ui.add(grid, ui.caption("Galeride kadran yok. \"Dosyadan ekle\" ile Band 8 için yapılmış .bin dosyası seç."))
        val shown = if (all) ordered else ordered.take(SHOWN)
        FaceGrid.build(ui, grid, shown.map { e ->
            val onBand = band?.firstOrNull { it.id == e.id }
            val img = BandFaceView(act)
            img.set(Band8FaceStore.preview(e))
            val state = when {
                onBand == null && band == null -> "Okunmadı"
                onBand == null -> "Telefonda"
                onBand.active -> "Etkin"
                else -> "Bantta"
            }
            FaceGrid.Tile(e.name, state, onBand?.active == true, img, ui.dp(56), ui.dp(134)) { actions(e, onBand) }
        })
        more.visibility = if (stored.size > SHOWN) View.VISIBLE else View.GONE
        more.text = if (all) "Daha az göster" else "Tümünü göster (${stored.size})"

        // Bantta olup depoda olmayanlar (yerleşikler ve başka uygulamayla kurulanlar).
        builtins.content.removeAllViews()
        val ids = stored.map { it.id }.toSet()
        val others = band?.filter { it.id !in ids && it.id != B8NavFace.ID }
        if (others == null) {
            builtins.title.text = "Bantta yerleşik"
            ui.add(builtins.content, ui.caption(if (WatchHub.ready) "Bant listesi okunuyor…" else "Band bağlanınca okunur."))
            return
        }
        builtins.title.text = "Bantta yerleşik (${others.size})"
        if (others.isEmpty()) ui.add(builtins.content, ui.caption("Galeride olmayan kadran yok."))
        others.forEachIndexed { i, f ->
            if (i > 0) ui.add(builtins.content, ui.divider(), height = ui.dp(1))
            val sub = if (f.active) "Etkin" else if (f.canDelete) "Yüklenen" else null
            val buttons = ui.horizontal()
            if (!f.active) ui.add(buttons, ui.textButton("Etkin yap") { setActive(f) }.also { it.setTextColor(ui.color(R.color.accent_text)) }, width = Ui.WRAP)
            if (B8Watchface.canDelete(f)) ui.add(buttons, ui.textButton("Sil") { deleteFromBand(f) }.also { it.setTextColor(ui.color(R.color.danger)) }, width = Ui.WRAP, leftDp = 4)
            ui.add(builtins.content, ui.row(label(f), sub, trailing = buttons))
        }
    }

    /** Döşemeye dokununca: Yükle / Etkin yap / Sil. */
    private fun actions(e: Band8FaceStore.Entry, onBand: B8Watchface.Face?) {
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        if (onBand == null) { labels += "Banda yükle"; acts += { e.file.readBytes().let { confirmInstall(it, saveFirst = false) } } }
        if (onBand != null && !onBand.active) { labels += "Etkin yap"; acts += { setActive(onBand) } }
        if (onBand != null && B8Watchface.canDelete(onBand)) { labels += "Banttan sil"; acts += { deleteFromBand(onBand) } }
        labels += "Galeriden kaldır"
        acts += {
            confirm("Galeriden kaldır", "\"${e.name}\" uygulamadaki galeriden silinsin mi? Banttaki kopyası kalır.", "Kaldır") {
                Band8FaceStore.remove(act, e.id)
                refresh()
            }
        }
        val d = AlertDialog.Builder(act).setTitle(e.name)
            .setItems(labels.toTypedArray()) { _, which -> acts[which]() }
            .setNegativeButton("Vazgeç", null).create()
        act.track(d)
        d.show()
    }

    private fun setActive(f: B8Watchface.Face) =
        confirm("Kadranı etkin yap", "\"${label(f)}\" banttaki kadran olsun mu?", "Etkin yap") { withService { it.bandFaceSet(f.id) } }

    private fun deleteFromBand(f: B8Watchface.Face) =
        confirm("Kadranı sil", "\"${label(f)}\" banttan silinsin mi?", "Sil") { withService { it.bandFaceDelete(f.id) } }

    private fun label(f: B8Watchface.Face) = f.name.ifBlank { "Adsız kadran" }

    private fun confirm(title: String, message: String, ok: String, action: () -> Unit) {
        val d = AlertDialog.Builder(act).setTitle(title).setMessage(message)
            .setPositiveButton(ok) { _, _ -> action() }
            .setNegativeButton("Vazgeç", null).create()
        act.track(d)
        d.show()
    }

    fun pick() {
        if (Band8Faces.faces == null) {
            act.toast(if (WatchHub.ready) "Bant listesi okunuyor; biraz sonra tekrar dene." else "Önce bandı bağla.")
            withService { it.bandFaceRead() }
            return
        }
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        try {
            @Suppress("DEPRECATION")
            act.startActivityForResult(i, REQ)
        } catch (e: ActivityNotFoundException) {
            act.toast("Dosya seçici açılamadı.")
        }
    }

    /** MainActivity.onActivityResult buraya yönlendirir. */
    fun onResult(resultCode: Int, data: Intent?) {
        if (resultCode != android.app.Activity.RESULT_OK) return
        val uri: Uri = data?.data ?: return
        val bytes = read(uri) ?: run {
            act.toast("Dosya okunamadı ya da ${B8FaceFile.MAX_SIZE / 1024 / 1024} MB'tan büyük.")
            return
        }
        confirmInstall(bytes, saveFirst = true)
    }

    /** Bizim deneme kadranımız: telefonda üretilir, dosya gerekmez. Aynı denetim ve onaydan geçer. */
    private fun ownTestFace() {
        if (Band8Faces.faces == null) {
            act.toast("Bant listesi henüz okunmadı.")
            return
        }
        val bytes = B8FaceDesign.simpleDigital(B8FaceDesign.Spec(TEST_FACE_ID, "Finn Dijital"))
        confirmInstall(bytes, saveFirst = true)
    }

    /**
     * Onay penceresi: önizleme, ad, kimlik, boy, yapı denetimi, önek notu. [saveFirst]: onaylanınca dosya
     * galeriye de kaydedilir (yükleme başarısız olsa da galeride kalır, yeniden denenebilir).
     */
    private fun confirmInstall(bytes: ByteArray, saveFirst: Boolean) {
        val parsed = B8FaceFile.parse(bytes)
        if (parsed !is B8FaceFile.Result.Ok) {
            act.toast("Band 8 kadran dosyası değil: ${(parsed as B8FaceFile.Result.Invalid).reason}.")
            return
        }
        val faces = Band8Faces.faces ?: run { act.toast("Bant listesi henüz okunmadı."); return }
        val problems = B8FaceBin.check(bytes)
        val own = parsed.id.startsWith(B8FaceBin.OWN_ID_PREFIX)
        // Kendi ürettiğimiz kadran denetimden geçmezse hiç gönderilmez.
        if (own && problems.isNotEmpty()) {
            act.toast("Kendi kadranımız denetimden geçmedi: ${problems.first()}.")
            return
        }
        val onBand = faces.any { it.id == parsed.id }
        val differs = B8Watchface.prefixDiffers(parsed.id, faces)
        val msg = buildString {
            append("Ad: ").append(parsed.name.ifBlank { "dosyada çok dilli" }).append('\n')
            append("Kimlik: ").append(parsed.id).append('\n')
            append("Boy: ").append(parsed.size / 1024).append(" KB\n")
            append("Yapı denetimi: ").append(if (problems.isEmpty()) "geçti" else "${problems.size} sorun (${problems.first()})").append("\n\n")
            if (onBand) append("Bu kadran zaten bantta. Yalnızca galeriye eklenir.\n\n")
            if (differs) {
                append("Not: kimlik öneki ").append(parsed.id.take(3)).append(" bu bantta henüz görülmedi. Dosyanın Band 8 için olduğundan emin ol.\n\n")
            }
            if (problems.isNotEmpty()) append("Uyarı: dosya bilinen yapıya uymuyor. Yüklemen önerilmez.\n\n")
            if (!onBand) append("Yükleme sırasında bandı telefondan uzaklaştırma.")
        }
        val body = ui.vertical()
        body.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0)
        Band8FaceStore.previewBitmap(bytes)?.let { bmp: Bitmap ->
            val img = ImageView(act)
            img.setImageBitmap(bmp)
            img.scaleType = ImageView.ScaleType.FIT_CENTER
            body.addView(img, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(220)))
        }
        ui.add(body, ui.text(msg, 14f, R.color.text), topDp = 8)
        val scroll = android.widget.ScrollView(act)
        scroll.addView(body)
        val d = AlertDialog.Builder(act).setTitle(if (onBand) "Galeriye ekle" else "Kadranı yükle").setView(scroll)
            .setPositiveButton(if (onBand) "Ekle" else "Yükle") { _, _ ->
                if (saveFirst && Band8FaceStore.save(act, bytes) == null) act.toast("Galeriye kaydedilemedi.")
                if (!onBand) withService { it.bandFaceInstall(bytes) }
                refresh()
            }
            .setNegativeButton("Vazgeç", null).create()
        act.track(d)
        d.show()
    }

    private fun read(uri: Uri): ByteArray? = try {
        act.contentResolver.openInputStream(uri)?.use { input ->
            val buf = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                total += n
                if (total > B8FaceFile.MAX_SIZE) return@use null
                buf.write(chunk, 0, n)
            }
            buf.toByteArray()
        }
    } catch (e: Exception) {
        null
    }

    companion object {
        const val REQ = 43
        private const val SHOWN = 6
        /** Deneme kadranının kimliği; bizim önekimiz B8FaceBin.OWN_ID_PREFIX. */
        const val TEST_FACE_ID = "167299001"
    }
}
