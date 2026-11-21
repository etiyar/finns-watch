package com.che.ls05

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.text.TextUtils
import android.view.View
import android.widget.TextView
import com.che.ls05.protocol.WatchRecord
import com.che.ls05.protocol.WatchType
import com.che.ls05.protocol.backup.BackupFormat

/**
 * Saatin kimlik bilgisi satırı (Saatlerim). Band 8: kimlik anahtarı. LS05: kayıtlı kullanıcı kimliği.
 * Değer varsayılan maskeli. "Göster" onay ister ve 60 sn sonra kendiliğinden gizlenir. "Kopyala" panoya
 * hassas olarak yazar ve 30 sn sonra panoyu temizler. Değer loga, Toast'a ve içerik açıklamasına girmez.
 */
object CredentialRow {

    const val MASK = "•••• •••• ••••"
    private const val CLEAR_CLIP_MS = 30_000L
    private const val AUTO_HIDE_MS = 60_000L
    private const val CLIP_LABEL = "Saat kimliği"
    private const val SENSITIVE = "android.content.extra.IS_SENSITIVE"

    private val main = Handler(Looper.getMainLooper())
    private val clearClip = Runnable { pendingClipContext?.let { clear(it) } }
    private var pendingClipContext: Context? = null

    /** Görünür biçim: Band 8 anahtarı dörtlü gruplar, LS05 kimliği olduğu gibi. Yoksa null. */
    private fun read(c: Context, w: WatchRecord): String? = when (w.type) {
        WatchType.BAND8 -> SecretStore.get(c, w.id)?.let { raw ->
            try { BackupFormat.hex(raw).chunked(4).joinToString(" ") } finally { raw.fill(0) }
        }
        else -> Prefs.userIdText(c, w.ns).trim().ifEmpty { null }
    }

    private fun has(c: Context, w: WatchRecord): Boolean = when (w.type) {
        WatchType.BAND8 -> SecretStore.has(c, w.id)
        else -> Prefs.userIdText(c, w.ns).isNotBlank()
    }

    fun build(act: MainActivity, ui: Ui, watch: WatchRecord): View {
        val title = if (watch.type == WatchType.BAND8) "Kimlik anahtarı" else "Kullanıcı kimliği"
        val box = ui.vertical()
        ui.add(box, ui.text(title, 15f))
        if (!has(act, watch)) {
            ui.add(box, ui.caption("Bu saat için anahtar yok"), topDp = 2)
            return box
        }
        val value = ui.mono()
        value.text = MASK
        value.textSize = 15f
        value.setTextIsSelectable(false)
        value.maxLines = 2
        value.ellipsize = TextUtils.TruncateAt.END
        value.contentDescription = "Gizli"
        ui.add(box, value, topDp = 4)

        val btns = ui.horizontal()
        var shown = false
        lateinit var toggle: TextView
        val autoHide = Runnable { if (shown) { shown = false; value.text = MASK; toggle.text = "Göster" } }
        fun hide() {
            main.removeCallbacks(autoHide)
            shown = false
            value.text = MASK
            toggle.text = "Göster"
        }
        toggle = ui.textButton("Göster") {
            if (shown) {
                hide()
                return@textButton
            }
            AlertDialog.Builder(act)
                .setTitle("$title gösterilsin mi?")
                .setMessage("Değer ekranda açıkça görünür. Yanında kimse yokken göster. 60 saniye sonra yeniden gizlenir.")
                .setPositiveButton("Göster") { _, _ ->
                    val v = read(act, watch)
                    if (v == null) {
                        act.toast("Değer okunamadı; yeniden girilmesi gerekebilir.")
                    } else {
                        shown = true
                        value.text = v
                        toggle.text = "Gizle"
                        main.removeCallbacks(autoHide)
                        main.postDelayed(autoHide, AUTO_HIDE_MS)
                    }
                }
                .setNegativeButton("Vazgeç", null)
                .show()
        }
        ui.add(btns, toggle, width = Ui.WRAP)
        ui.add(btns, ui.textButton("Kopyala") {
            val v = read(act, watch)
            if (v == null) act.toast("Değer okunamadı.") else {
                copy(act, v.replace(" ", ""))
                act.toast("Kopyalandı. Pano 30 saniye sonra temizlenir.")
            }
        }, width = Ui.WRAP, leftDp = 8)
        ui.add(box, btns, topDp = 2)
        box.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) = hide()
        })
        return box
    }

    private fun copy(c: Context, text: String) {
        val cm = c.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(CLIP_LABEL, text)
        // Android 13+: pano önizlemesinde değer gizlenir (ClipDescription.EXTRA_IS_SENSITIVE).
        clip.description.extras = PersistableBundle().apply { putBoolean(SENSITIVE, true) }
        cm.setPrimaryClip(clip)
        pendingClipContext = c.applicationContext
        main.removeCallbacks(clearClip)
        main.postDelayed(clearClip, CLEAR_CLIP_MS)
    }

    /** Pano hâlâ bizim kopyamızı tutuyorsa temizlenir; başka bir şey kopyalandıysa dokunulmaz. */
    private fun clear(c: Context) {
        pendingClipContext = null
        val cm = c.getSystemService(ClipboardManager::class.java) ?: return
        try {
            val label = cm.primaryClipDescription?.label?.toString()
            if (label != null && label != CLIP_LABEL) return
            if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", ""))
        } catch (_: Exception) {
            // Arka planda pano erişimi kısıtlıysa boş içerik yazılır.
            try { cm.setPrimaryClip(ClipData.newPlainText("", "")) } catch (_: Exception) { }
        }
    }
}
