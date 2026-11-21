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
 * Ayarlar > Gelişmiş: Band 8 bildirim simgesi, kadran galerisi ve navigasyon kadranı (deneysel).
 *
 * Galeri: depodaki (Band8FaceStore) her kadran iki sütunlu ızgarada önizlemesiyle; dokununca Yükle,
 * Etkin yap, Sil. Bantta olup depoda olmayanlar (yerleşikler) adla, resimsiz listelenir (4/0).
 * Her işlem tek dokunuşla tek komut gönderir. Protokol Band8Faces ve protocol/band8 içinde.
 */
class Band8FaceCard(private val act: MainActivity) {
    private val ui = act.ui
    val view: LinearLayout = ui.vertical()
    private val grid: LinearLayout = ui.vertical()
    private val bandOnly: LinearLayout = ui.vertical()
    private val status: TextView = ui.caption("")

    init {
        ui.add(view, ui.cardTitle("Band 8 bildirim simgesi (deneysel)"))
        val icons = ui.switchRow(
            "Uygulama simgesini gönder",
            "Band bir uygulamanın simgesini isteyince o uygulamanın simgesi gönderilir. Kapalıyken band kendi simgesini gösterir.",
            Prefs.band8Icons(act),
        ) { on -> Prefs.setBand8Icons(act, on) }
        ui.add(view, icons.view, topDp = 4)

        ui.add(view, ui.cardTitle("Band 8 kadran galerisi (deneysel)"), topDp = 16)
        ui.add(view, ui.caption("Yalnızca Band 8 için yapılmış .bin kadran dosyası yükle. Yüklenen dosyalar uygulamada saklanır. Kurulumdan sonra kadran kendiliğinden seçilmez."), topDp = 4)
        val r = ui.horizontal()
        ui.add(r, ui.secondary("Listeyi oku") { withService { it.bandFaceRead() } }, width = 0, weight = 1f)
        ui.add(r, ui.secondary("Dosya ekle") { pick() }, width = 0, weight = 1f, leftDp = 8)
        ui.add(view, r, topDp = 8)
        ui.add(view, ui.secondary("Deneme kadranı: Finn Dijital") { ownTestFace() }, topDp = 4)
        ui.add(view, status, topDp = 8)
        ui.add(view, grid, topDp = 4)
        ui.add(view, bandOnly, topDp = 8)

        ui.add(view, ui.cardTitle("Band 8 navigasyon kadranı (deneysel)"), topDp = 16)
        ui.add(view, ui.caption("Maps yönergesi bantta kadran olur: büyük ok, mesafe, sokak. Her manevrada yüklenip etkin yapılır; navigasyon bitince önceki kadran geri gelir. Önce denemeyle yükleme süresine bak."), topDp = 4)
        val n = ui.horizontal()
        ui.add(n, ui.secondary("Örnek okla dene") { withService { it.bandNavTest() } }, width = 0, weight = 1f)
        ui.add(n, ui.secondary("Kaldır, eskiye dön") { withService { it.bandNavEnd() } }, width = 0, weight = 1f, leftDp = 8)
        ui.add(view, n, topDp = 8)
        val auto = ui.switchRow(
            "Navigasyonu kadranda göster",
            "Bildirimler > Navigasyon yönergeleri açık olmalı. Mesafe tek başına değişince kadran yenilenmez.",
            Prefs.band8NavFace(act),
        ) { on -> Prefs.setBand8NavFace(act, on) }
        ui.add(view, auto.view, topDp = 4)
        val text = ui.switchRow(
            "Tam metin bildirimi de gönder",
            "Kapalıyken her manevrada yalnızca kısa manevra adı gider; bant uyanır ve titrer.",
            Prefs.band8NavText(act),
        ) { on -> Prefs.setBand8NavText(act, on) }
        ui.add(view, text.view, topDp = 4)
    }

    private fun withService(f: (WatchService) -> String?) {
        val s = act.readyService() ?: return
        f(s)?.let { act.toast(it) }
    }

    fun refresh() {
        status.text = Band8Faces.status
        val band = Band8Faces.faces
        val stored = Band8FaceStore.list(act)
        grid.removeAllViews()
        bandOnly.removeAllViews()
        // Depodaki kadranlar: iki sütunlu önizleme ızgarası.
        for (row in stored.chunked(2)) {
            val r = ui.horizontal()
            row.forEachIndexed { i, e ->
                val face = band?.firstOrNull { it.id == e.id }
                ui.add(r, tile(e, face), width = 0, weight = 1f, leftDp = if (i == 0) 0 else 8)
            }
            if (row.size == 1) ui.add(r, View(act), width = 0, weight = 1f, leftDp = 8)
            ui.add(grid, r, topDp = 8)
        }
        if (stored.isEmpty()) ui.add(grid, ui.caption("Galeride kadran yok. \"Dosya ekle\" ile ekle."), topDp = 4)
        // Bantta olup depoda olmayanlar (yerleşikler ve başka uygulamayla kurulanlar).
        val ids = stored.map { it.id }.toSet()
        val others = band?.filter { it.id !in ids && it.id != B8NavFace.ID } ?: return
        if (others.isEmpty()) return
        ui.add(bandOnly, ui.caption("Bantta (galeride yok)"))
        others.forEachIndexed { i, f ->
            if (i > 0) ui.add(bandOnly, ui.divider(), height = ui.dp(1))
            val sub = buildString {
                append("Kimlik ").append(f.id)
                if (f.active) append(" · etkin")
                append(if (f.canDelete) " · yüklenen" else " · yerleşik")
            }
            val buttons = ui.horizontal()
            if (!f.active) ui.add(buttons, ui.secondary("Etkin yap") { setActive(f) }, width = Ui.WRAP)
            if (B8Watchface.canDelete(f)) ui.add(buttons, ui.danger("Sil") { deleteFromBand(f) }, width = Ui.WRAP, leftDp = 8)
            ui.add(bandOnly, ui.row(label(f), sub, trailing = buttons))
        }
    }

    /** Dikey önizleme döşemesi: resim, ad ve bant durumu. */
    private fun tile(e: Band8FaceStore.Entry, onBand: B8Watchface.Face?): View {
        val col = ui.vertical()
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.background = ui.pressable(16)
        col.setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6))
        col.isClickable = true
        col.setOnClickListener { actions(e, onBand) }
        val img = ImageView(act)
        img.scaleType = ImageView.ScaleType.FIT_CENTER
        Band8FaceStore.preview(e)?.let { img.setImageBitmap(it) }
        col.addView(img, LinearLayout.LayoutParams(ui.dp(80), ui.dp(204)))
        val t = ui.text(e.name, 13f, R.color.text)
        t.gravity = Gravity.CENTER
        t.maxLines = 2
        t.ellipsize = TextUtils.TruncateAt.END
        ui.add(col, t, topDp = 4)
        val state = when {
            onBand == null && Band8Faces.faces == null -> "bant listesi okunmadı"
            onBand == null -> "bantta değil"
            onBand.active -> "bantta · etkin"
            else -> "bantta"
        }
        ui.add(col, ui.caption(state).also { it.gravity = Gravity.CENTER })
        col.contentDescription = "${e.name}, $state"
        return col
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

    private fun pick() {
        if (Band8Faces.faces == null) {
            act.toast("Önce listeyi oku.")
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
            act.toast("Önce listeyi oku.")
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
        val faces = Band8Faces.faces ?: run { act.toast("Önce listeyi oku."); return }
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
        /** Deneme kadranının kimliği; bizim önekimiz B8FaceBin.OWN_ID_PREFIX. */
        const val TEST_FACE_ID = "167299001"
    }
}
