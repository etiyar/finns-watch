package com.che.ls05

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.che.ls05.protocol.band8.B8ArchiveZip
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Band 8 ham arşivini tek zip olarak dışa aktarır (Gelişmiş). Yer ACTION_CREATE_DOCUMENT ile seçilir.
 * Dosyalar şifresi çözülmüş hâlde arşivde durur; zip'i çözmek için anahtar gerekmez. Değerler loglanmaz.
 */
object Band8RawExport {
    const val REQ = 62

    private val main = Handler(Looper.getMainLooper())
    private var busy = false
    private val NAME_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuuMMdd-HHmm", Locale.ROOT)

    fun start(act: Activity) {
        if (busy) {
            Toast.makeText(act, "Dışa aktarma sürüyor.", Toast.LENGTH_SHORT).show()
            return
        }
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_TITLE, "finnswatch-band8-ham-${NAME_TIME.format(LocalDateTime.now())}.zip")
        try {
            @Suppress("DEPRECATION")
            act.startActivityForResult(i, REQ)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(act, "Dosya kaydetme ekranı açılamadı.", Toast.LENGTH_SHORT).show()
        }
    }

    /** MainActivity.onActivityResult buraya yönlendirir. */
    fun onResult(act: Activity, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        if (busy) return
        busy = true
        val app = act.applicationContext
        Thread({
            var count = 0
            val err = try {
                val entries = Band8HealthArchive.readEntries(app)
                count = entries.size
                val out = app.contentResolver.openOutputStream(uri) ?: throw IllegalStateException("akış yok")
                out.use { B8ArchiveZip.write(it, entries, BuildIdentity.label, System.currentTimeMillis()) }
                null
            } catch (e: Throwable) {
                e.javaClass.simpleName
            }
            main.post {
                busy = false
                if (err == null) {
                    WatchHub.log("Band 8 ham verisi dışa aktarıldı: $count dosya")
                    Toast.makeText(app, "Dışa aktarıldı: $count dosya", Toast.LENGTH_SHORT).show()
                } else {
                    WatchHub.log("Band 8 ham verisi dışa aktarılamadı: $err")
                    Toast.makeText(app, "Dışa aktarılamadı.", Toast.LENGTH_SHORT).show()
                }
            }
        }, "band8-disa-aktar").start()
    }
}
