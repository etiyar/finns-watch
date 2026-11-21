package com.che.ls05

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

/**
 * Görünmez yardımcı ekran: yedek dosyası için sistem dosya seçicisini açar ve seçilen adresi geri verir.
 * Böylece yedek bölümü ana ekranın sonuç akışına bağlı kalmaz. Süreç yeniden kurulursa sonuç yok sayılır.
 */
class BackupFileActivity : Activity() {

    companion object {
        private const val EXTRA_REQUEST = "request"
        private const val REQ = 1

        private var pending: ((Uri?) -> Unit)? = null

        /** Yeni dosya oluştur (ACTION_CREATE_DOCUMENT). */
        fun create(c: Context, fileName: String, done: (Uri?) -> Unit) {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/octet-stream")
                .putExtra(Intent.EXTRA_TITLE, fileName)
            launch(c, i, done)
        }

        /** Var olan dosyayı seç (ACTION_OPEN_DOCUMENT). */
        fun open(c: Context, done: (Uri?) -> Unit) {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
            launch(c, i, done)
        }

        private fun launch(c: Context, request: Intent, done: (Uri?) -> Unit) {
            pending = done
            c.startActivity(Intent(c, BackupFileActivity::class.java).putExtra(EXTRA_REQUEST, request))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        @Suppress("DEPRECATION")
        val request = intent.getParcelableExtra<Intent>(EXTRA_REQUEST)
        if (request == null) {
            deliver(null)
            return
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(request, REQ)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Dosya ekranı açılamadı.", Toast.LENGTH_SHORT).show()
            deliver(null)
        }
    }

    @Deprecated("Activity sonuç akışı")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ) return
        deliver(if (resultCode == RESULT_OK) data?.data else null)
    }

    private fun deliver(uri: Uri?) {
        val cb = pending
        pending = null
        finish()
        overridePendingTransitionCompat()
        cb?.invoke(uri)
    }

    private fun overridePendingTransitionCompat() {
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
