package com.che.ls05

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import com.che.ls05.protocol.band8.B8Command
import com.che.ls05.protocol.band8.B8Icons
import com.che.ls05.protocol.band8.B8Upload

/**
 * Band 8 bildirim simgesi (belge 6.4 ve 9, akış kayıtta doğrulandı, uygulamada cihazda denenecek).
 * Band 7/16 ile simge ister; 7/15 ile biçim ve kenar sorulur; uygulamanın başlatıcı simgesi o boyda
 * çizilip tür 50 yüklemeyle gider. Ayar kapalıysa istek yok sayılır, band kendi simgesini gösterir.
 * Logda paket adı yok, yalnızca boy ve biçim.
 */
class Band8Icons(
    private val context: Context,
    private val log: (String) -> Unit,
    private val send: (ByteArray, String) -> Boolean,
    private val upload: (Int, ByteArray, String, Band8Ble.UploadCallback) -> String?,
) {
    /** Bandın sorduğu paket adı (gönderildiği gibi) ve telefondaki karşılığı. */
    private var pending: Pair<String, String>? = null

    fun onQuery(c: B8Command) {
        val asked = B8Icons.parseQuery(c) ?: run { log("Band 8 simge isteği çözülemedi"); return }
        if (!Prefs.band8Icons(context)) {
            log("Band 8 simge isteği geldi; ayar kapalı, yok sayıldı")
            return
        }
        val pkg = resolve(asked) ?: run {
            log("Band 8 simge isteği: uygulama telefonda bulunamadı (ad uzunluğu ${asked.length})")
            return
        }
        pending = asked to pkg
        if (!send(B8Icons.request(asked), "simge biçimi 7/15")) pending = null
    }

    fun onSpec(c: B8Command) {
        val spec = B8Icons.parseSpec(c) ?: run { log("Band 8 simge biçimi çözülemedi"); return }
        val p = pending
        pending = null
        if (p == null) return
        if (!B8Icons.supported(spec)) {
            log("Band 8 simge biçimi desteklenmiyor; gönderilmedi: durum=${spec.status}, biçim=${spec.format}, kenar=${spec.size}")
            return
        }
        val pixels = render(p.second, spec.size) ?: run { log("Band 8 simgesi çizilemedi"); return }
        val bytes = B8Icons.encode(pixels, spec.size, spec.format) ?: return
        val label = "simge ${spec.size} px"
        val err = upload(B8Upload.TYPE_ICON, bytes, label, object : Band8Ble.UploadCallback {
            override fun onProgress(done: Int, total: Int) = Unit
            override fun onFinished(reason: String?) {
                log(if (reason == null) "Band 8 simgesi gönderildi: $label" else "Band 8 simgesi gönderilemedi: $reason")
            }
        })
        if (err != null) log("Band 8 simgesi gönderilmedi: $err")
    }

    private fun resolve(asked: String): String? {
        val pm = context.packageManager
        try {
            pm.getApplicationInfo(asked, 0)
            return asked
        } catch (_: PackageManager.NameNotFoundException) {
        }
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val all = try { pm.queryIntentActivities(launcher, 0).map { it.activityInfo.packageName }.toSet() } catch (_: Exception) { emptySet() }
        return B8Icons.resolvePackage(asked, all)
    }

    private fun render(pkg: String, size: Int): IntArray? = try {
        val d = context.packageManager.getApplicationIcon(pkg)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(bmp))
        val px = IntArray(size * size)
        bmp.getPixels(px, 0, size, 0, 0, size, size)
        bmp.recycle()
        px
    } catch (_: Exception) {
        null
    }
}
