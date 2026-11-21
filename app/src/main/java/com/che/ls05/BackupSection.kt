package com.che.ls05

import android.app.AlertDialog
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.che.ls05.protocol.backup.BackupContents
import com.che.ls05.protocol.backup.BackupError
import com.che.ls05.protocol.backup.BackupException
import com.che.ls05.protocol.backup.BackupFormat
import com.che.ls05.protocol.backup.BackupSummary
import java.time.LocalDate

/**
 * Ayarlar sayfasındaki "Yedekle ve geri yükle" kartı. Kendi içinde çalışır: dosya seçimi BackupFileActivity ile,
 * dosya işleri Backup ile yapılır. Yalnızca Ui yardımcılarıyla çizilir; tema ondan gelir.
 */
object BackupSection {

    private val main = Handler(Looper.getMainLooper())

    /** Kart görünümü. Sayfa yeniden çizilince yeniden çağrılabilir. */
    fun build(act: MainActivity, ui: Ui): View {
        val card = ui.card()
        ui.add(card, ui.cardTitle("Yedekle ve geri yükle"))
        ui.add(card, ui.caption("Ayarlar, bildirim kuralları, hatırlatıcılar, alarm notları, Saatlerim ve kadranlar tek dosyaya yazılır. Sağlık verisi ve log girmez."), topDp = 6)
        val last = ui.caption("")
        ui.add(card, last, topDp = 8)
        val btns = ui.horizontal()
        ui.add(btns, ui.tonal("Yedekle") { askBackup(act, ui) { refreshLast(act, last) } }, width = 0, weight = 1f)
        ui.add(btns, ui.secondary("Geri yükle") { pickRestore(act, ui) }, width = 0, weight = 1f, leftDp = 8)
        ui.add(card, btns, topDp = 12)
        val undo = ui.textButton("Geri yükleme öncesine dön") { askUndo(act) }
        ui.add(card, undo, topDp = 4, width = Ui.WRAP)
        undo.visibility = if (Backup.hasUndo(act)) View.VISIBLE else View.GONE
        refreshLast(act, last)
        return card
    }

    private fun refreshLast(act: MainActivity, t: TextView) {
        val at = Backup.lastAt(act)
        t.text = if (at == 0L) "Henüz yedek alınmadı." else "Son yedek: ${Fmt.dayMs(at)} · ${Backup.lastSummary(act)}"
    }

    // ---------- Yedekleme ----------

    private fun passwordField(ui: Ui, hint: String): EditText {
        val e = ui.field(hint)
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        return e
    }

    private fun chars(e: EditText): CharArray {
        val t = e.text
        val out = CharArray(t.length)
        t.getChars(0, t.length, out, 0)
        t.clear()
        return out
    }

    private fun askBackup(act: MainActivity, ui: Ui, after: () -> Unit) {
        if (Backup.busy) { act.toast("Başka bir yedek işi sürüyor."); return }
        val box = ui.dialogBox()
        val keys = ui.check("Kimlik anahtarlarını dahil et", false)
        ui.add(box, keys)
        ui.add(box, ui.caption("Açıksa Band 8 anahtarı ve LS05 kimliği de yazılır. O zaman yedek parolayla şifrelenir. Parolayı unutursan yedek açılamaz."), topDp = 2)
        val pwBox = ui.vertical()
        val pw1 = passwordField(ui, "Parola (en az 8 karakter)")
        val pw2 = passwordField(ui, "Parola tekrar")
        ui.add(pwBox, pw1, topDp = 12)
        ui.add(pwBox, pw2, topDp = 8)
        val err = ui.text("", 13f, R.color.danger)
        ui.add(pwBox, err, topDp = 6)
        pwBox.visibility = View.GONE
        ui.add(box, pwBox)
        keys.setOnCheckedChangeListener { _, on -> pwBox.visibility = if (on) View.VISIBLE else View.GONE }
        val d = AlertDialog.Builder(act)
            .setTitle("Yedekle")
            .setView(box)
            .setPositiveButton("Dosya seç", null)
            .setNegativeButton("Vazgeç") { _, _ -> pw1.text.clear(); pw2.text.clear() }
            .create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val withKeys = keys.isChecked
                var password: CharArray? = null
                if (withKeys) {
                    val a = chars(pw1)
                    val b = chars(pw2)
                    val problem = when {
                        a.size < BackupFormat.MIN_PASSWORD -> BackupError.WEAK_PASSWORD.message
                        !a.contentEquals(b) -> "Parolalar aynı değil."
                        else -> null
                    }
                    b.fill('\u0000')
                    if (problem != null) {
                        a.fill('\u0000')
                        err.text = problem
                        return@setOnClickListener
                    }
                    password = a
                }
                d.dismiss()
                val now = LocalDate.now()
                BackupFileActivity.create(act, BackupFormat.fileName(now.year, now.monthValue, now.dayOfMonth)) { uri ->
                    if (uri == null) {
                        password?.fill('\u0000')
                        return@create
                    }
                    val progress = progress(act, ui, "Yedek yazılıyor…")
                    Backup.write(act, uri, withKeys, password) { e ->
                        progress.dismiss()
                        if (e != null) message(act, "Yedek alınamadı", e)
                        else act.toast(if (withKeys) "Şifreli yedek kaydedildi." else "Yedek kaydedildi.")
                        after()
                    }
                }
            }
        }
        d.show()
    }

    // ---------- Geri yükleme ----------

    private fun pickRestore(act: MainActivity, ui: Ui) {
        if (Backup.busy) { act.toast("Başka bir yedek işi sürüyor."); return }
        BackupFileActivity.open(act) { uri -> if (uri != null) load(act, ui, uri) }
    }

    private fun load(act: MainActivity, ui: Ui, uri: Uri) {
        val progress = progress(act, ui, "Yedek okunuyor…")
        val app = act.applicationContext
        Thread({
            val result: Result<Pair<ByteArray, Boolean>> = try {
                val bytes = Backup.readBytes(app, uri)
                Result.success(bytes to BackupFormat.inspect(bytes).encrypted)
            } catch (e: BackupException) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(Exception("Dosya okunamadı."))
            }
            main.post {
                progress.dismiss()
                result.fold(
                    onSuccess = { (bytes, encrypted) ->
                        if (encrypted) askPassword(act, ui, bytes) else decode(act, ui, bytes, null) { }
                    },
                    onFailure = { message(act, "Geri yüklenemedi", it.message ?: "Dosya okunamadı.") },
                )
            }
        }, "yedek-oku").start()
    }

    private fun askPassword(act: MainActivity, ui: Ui, bytes: ByteArray) {
        val box = ui.dialogBox()
        ui.add(box, ui.caption("Bu yedek şifreli. Yedeği alırken girdiğin parolayı yaz."))
        val pw = passwordField(ui, "Parola")
        ui.add(box, pw, topDp = 12)
        val err = ui.text("", 13f, R.color.danger)
        ui.add(box, err, topDp = 6)
        val d = AlertDialog.Builder(act)
            .setTitle("Parola")
            .setView(box)
            .setPositiveButton("Aç", null)
            .setNegativeButton("Vazgeç") { _, _ -> pw.text.clear() }
            .create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val p = chars(pw)
                if (p.isEmpty()) { err.text = "Parola gir."; return@setOnClickListener }
                err.text = ""
                decode(act, ui, bytes, p) { e ->
                    if (e == null) d.dismiss() else err.text = e
                }
            }
        }
        d.show()
    }

    /** Arka planda çözer. Parola hatası [onPasswordResult]'a döner; başarılıysa özet onayı açılır. */
    private fun decode(act: MainActivity, ui: Ui, bytes: ByteArray, password: CharArray?, onPasswordResult: (String?) -> Unit) {
        val progress = progress(act, ui, if (password != null) "Şifre çözülüyor…" else "Yedek denetleniyor…")
        Thread({
            val r: Result<BackupContents> = try {
                Result.success(BackupFormat.decode(bytes, password))
            } catch (e: BackupException) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(BackupException(BackupError.CORRUPT))
            } finally {
                password?.fill('\u0000')
            }
            main.post {
                progress.dismiss()
                r.fold(
                    onSuccess = { c ->
                        onPasswordResult(null)
                        confirm(act, ui, c)
                    },
                    onFailure = { e ->
                        val msg = (e as? BackupException)?.error?.message ?: BackupError.CORRUPT.message
                        if (password != null && (e as? BackupException)?.error == BackupError.WRONG_PASSWORD) onPasswordResult(msg)
                        else { onPasswordResult(null); message(act, "Geri yüklenemedi", msg) }
                    },
                )
            }
        }, "yedek-coz").start()
    }

    private fun confirm(act: MainActivity, ui: Ui, c: BackupContents) {
        val s = BackupSummary.of(c)
        val box = ui.dialogBox()
        ui.add(box, ui.caption("Yedek: ${Fmt.dayMs(s.createdAt)}${if (s.appVersion.isNotEmpty()) " · sürüm ${s.appVersion}" else ""}"))
        for (line in s.lines()) ui.add(box, ui.text(line, 14f), topDp = 6)
        ui.add(box, ui.caption("Bu telefondaki ayarlar, kurallar, hatırlatıcılar, Saatlerim listesi ve kadranlar yedektekiyle değiştirilir. Sağlık verisine dokunulmaz. Önce şimdiki durumun bir kopyası alınır. Bağlantı yeniden başlar."), topDp = 12)
        if (!s.includesKeys) {
            ui.add(box, ui.caption("Yedekte kimlik anahtarı yok. Bu telefonda kayıtlı anahtarlar korunur; yoksa saati yeniden eklemen gerekir."), topDp = 6)
        }
        AlertDialog.Builder(act)
            .setTitle("Geri yüklensin mi?")
            .setView(ui.scroll(box))
            .setPositiveButton("Geri yükle") { _, _ -> run(act, ui, c, makeUndo = true) }
            .setNegativeButton("Vazgeç") { _, _ -> c.wipe() }
            .setOnCancelListener { c.wipe() }
            .show()
    }

    private fun run(act: MainActivity, ui: Ui, c: BackupContents, makeUndo: Boolean) {
        val progress = progress(act, ui, "Geri yükleniyor…")
        Backup.apply(act, c, makeUndo) { err ->
            progress.dismiss()
            if (err != null) {
                message(act, "Geri yükleme tamamlanamadı", err)
            } else {
                act.toast("Yedek geri yüklendi.")
                if (!act.isFinishing && !act.isDestroyed) act.recreate()
            }
        }
    }

    private fun askUndo(act: MainActivity) {
        val ui = act.ui
        AlertDialog.Builder(act)
            .setTitle("Geri yükleme öncesine dön")
            .setMessage("Son geri yüklemeden hemen önceki ayarlar, Saatlerim ve kadranlar geri gelir. Kimlik anahtarlarına dokunulmaz.")
            .setPositiveButton("Dön") { _, _ ->
                val progress = progress(act, ui, "Okunuyor…")
                val app = act.applicationContext
                Thread({
                    val r = try { Result.success(Backup.readUndo(app)) } catch (e: Exception) { Result.failure(e) }
                    main.post {
                        progress.dismiss()
                        r.fold(
                            onSuccess = { run(act, ui, it, makeUndo = false) },
                            onFailure = { message(act, "Dönülemedi", (it as? BackupException)?.error?.message ?: "Kopya okunamadı.") },
                        )
                    }
                }, "yedek-geri").start()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    // ---------- Yardımcılar ----------

    private fun message(act: MainActivity, title: String, text: String) {
        if (act.isFinishing || act.isDestroyed) return
        AlertDialog.Builder(act).setTitle(title).setMessage(text).setPositiveButton("Tamam", null).show()
    }

    private fun progress(act: MainActivity, ui: Ui, text: String): AlertDialog {
        val row = ui.horizontal()
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(ui.dp(24), ui.dp(20), ui.dp(24), ui.dp(20))
        row.addView(ProgressBar(act), LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)))
        ui.add(row, ui.text(text), width = Ui.WRAP, leftDp = 16)
        val d = AlertDialog.Builder(act).setView(row).setCancelable(false).create()
        d.show()
        return d
    }
}
