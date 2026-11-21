package com.che.ls05

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.che.ls05.protocol.band8.B8FaceBin
import com.che.ls05.protocol.band8.B8FaceDesign
import com.che.ls05.protocol.band8.B8FaceFile
import com.che.ls05.protocol.band8.B8Watchface

/**
 * Ayarlar > Gelişmiş: Band 8 bildirim simgesi anahtarı ve "Band 8 kadran (deneysel)".
 * Her kadran işlemi tek dokunuşla tek komut gönderir. Protokol Band8Faces ve protocol/band8/B8Watchface.kt içinde.
 */
class Band8FaceCard(private val act: MainActivity) {
    private val ui = act.ui
    val view: LinearLayout = ui.vertical()
    private val list: LinearLayout = ui.vertical()
    private val status: TextView = ui.caption("")

    init {
        ui.add(view, ui.cardTitle("Band 8 bildirim simgesi (deneysel)"))
        val icons = ui.switchRow(
            "Uygulama simgesini gönder",
            "Band bir uygulamanın simgesini isteyince o uygulamanın simgesi gönderilir. Kapalıyken band kendi simgesini gösterir.",
            Prefs.band8Icons(act),
        ) { on -> Prefs.setBand8Icons(act, on) }
        ui.add(view, icons.view, topDp = 4)

        ui.add(view, ui.cardTitle("Band 8 kadran (deneysel)"), topDp = 16)
        ui.add(view, ui.caption("Yalnızca Band 8 için yapılmış .bin kadran dosyası yükle. Yanlış dosya bandı kullanılamaz hâle getirebilir; Band 8'de kurtarma yolu bilinmiyor. Kurulumdan sonra kadran kendiliğinden seçilmez."), topDp = 4)
        val r = ui.horizontal()
        ui.add(r, ui.secondary("Listeyi oku") { withService { it.bandFaceRead() } }, width = 0, weight = 1f)
        ui.add(r, ui.secondary("Dosya yükle") { pick() }, width = 0, weight = 1f, leftDp = 8)
        ui.add(view, r, topDp = 8)
        ui.add(view, ui.secondary("Deneme kadranı: Finn Dijital") { ownTestFace() }, topDp = 4)
        ui.add(view, ui.caption("Siyah zemin, büyük saat ve küçük tarih. Uygulama üretir, yapı denetiminden geçmezse gönderilmez."), topDp = 4)
        ui.add(view, status, topDp = 8)
        ui.add(view, list, topDp = 4)
    }

    private fun withService(f: (WatchService) -> String?) {
        val s = act.readyService() ?: return
        f(s)?.let { act.toast(it) }
    }

    fun refresh() {
        status.text = Band8Faces.status
        list.removeAllViews()
        val faces = Band8Faces.faces ?: return
        faces.forEachIndexed { i, f ->
            if (i > 0) ui.add(list, ui.divider(), height = ui.dp(1))
            val sub = buildString {
                append("Kimlik ").append(f.id)
                if (f.active) append(" · etkin")
                append(if (f.canDelete) " · yüklenen" else " · yerleşik")
            }
            val buttons = ui.horizontal()
            if (!f.active) ui.add(buttons, ui.secondary("Etkin yap") { confirm("Kadranı etkin yap", "\"${label(f)}\" banttaki kadran olsun mu?", "Etkin yap") { withService { it.bandFaceSet(f.id) } } }, width = Ui.WRAP)
            if (B8Watchface.canDelete(f)) ui.add(buttons, ui.danger("Sil") { confirm("Kadranı sil", "\"${label(f)}\" banttan silinsin mi?", "Sil") { withService { it.bandFaceDelete(f.id) } } }, width = Ui.WRAP, leftDp = 8)
            ui.add(list, ui.row(label(f), sub, trailing = buttons))
        }
    }

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
        confirmInstall(bytes)
    }

    /** Bizim deneme kadranımız: telefonda üretilir, dosya gerekmez. Aynı denetim ve onaydan geçer. */
    private fun ownTestFace() {
        if (Band8Faces.faces == null) {
            act.toast("Önce listeyi oku.")
            return
        }
        val bytes = B8FaceDesign.simpleDigital(B8FaceDesign.Spec(TEST_FACE_ID, "Finn Dijital"))
        confirmInstall(bytes)
    }

    private fun confirmInstall(bytes: ByteArray) {
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
        val differs = B8Watchface.prefixDiffers(parsed.id, faces)
        val msg = buildString {
            append("Ad: ").append(parsed.name.ifBlank { "dosyada çok dilli" }).append('\n')
            append("Kimlik: ").append(parsed.id).append('\n')
            append("Boy: ").append(parsed.size / 1024).append(" KB\n")
            append("Yapı denetimi: ").append(if (problems.isEmpty()) "geçti" else "${problems.size} sorun (${problems.first()})").append("\n\n")
            if (differs) {
                append("Uyarı: kimlik öneki ").append(parsed.id.take(3)).append(", banttaki yüklü kadranlarınki ")
                append(B8Watchface.installedPrefixes(faces).sorted().joinToString(", "))
                append(". Dosya başka bir saat ya da bölge için olabilir.\n\n")
            }
            if (problems.isNotEmpty()) append("Uyarı: dosya bilinen yapıya uymuyor. Yüklemen önerilmez.\n\n")
            append("Yükleme sırasında bandı telefondan uzaklaştırma.")
        }
        val d = AlertDialog.Builder(act).setTitle("Kadranı yükle").setMessage(msg)
            .setPositiveButton("Yükle") { _, _ -> withService { it.bandFaceInstall(bytes) } }
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
