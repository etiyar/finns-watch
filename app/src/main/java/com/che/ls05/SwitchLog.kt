package com.che.ls05

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * Saat değişim günlüğü: filesDir/health/switches.log, satır "epoch saniye<TAB>saat kimliği".
 * Birleşik nabız görünümü, bir dakikada hangi saatin etkin olduğunu buradan bulur.
 * Sağlık değeri, adres ya da konum içermez. Adı tarih biçimine uymadığı için 90 gün silme kuralı dokunmaz.
 */
object SwitchLog {

    private const val NAME = "switches.log"

    fun file(c: Context): File = File(File(c.filesDir, "health"), NAME)

    fun record(c: Context, watchId: String?) {
        if (watchId.isNullOrEmpty()) return
        try {
            val f = file(c)
            f.parentFile?.let { if (!it.exists()) it.mkdirs() }
            FileOutputStream(f, true).use {
                it.write("${System.currentTimeMillis() / 1000}\t$watchId\n".toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            WatchHub.log("Saat değişim günlüğü yazılamadı: ${e.javaClass.simpleName}")
        }
    }
}
