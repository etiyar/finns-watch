package com.che.ls05

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.che.ls05.protocol.WatchList
import com.che.ls05.protocol.WatchListStorage
import com.che.ls05.protocol.WatchType
import com.che.ls05.protocol.backup.BackupContents
import com.che.ls05.protocol.backup.BackupError
import com.che.ls05.protocol.backup.BackupException
import com.che.ls05.protocol.backup.BackupFormat
import com.che.ls05.protocol.backup.BackupPolicy
import com.che.ls05.protocol.backup.BackupSummary
import com.che.ls05.protocol.backup.PrefValue
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Yedekle ve geri yükle (dosya işleri). Biçim ve doğrulama protocol modülündedir (BackupFormat).
 * Yedeğe girenler: "ls05" ayarları (geçici durum hariç), Saatlerim listesi, kadran klasörleri.
 * Kimlikler (Band 8 anahtarı, LS05 kimliği) yalnızca kullanıcı isterse ve parolayla şifreli yedeğe girer.
 * Sağlık kayıtları, log ve "son görülen yer" girmez. Loga yalnızca sayılar yazılır.
 */
object Backup {

    private const val STATE = "backup_state"
    private const val UNDO_DIR = "yedek"
    private const val UNDO_FILE = "geri-yukleme-oncesi.fwb"
    private const val STOP_WAIT_MS = 2_500L

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var busy = false
        private set

    private fun state(c: Context) = c.getSharedPreferences(STATE, Context.MODE_PRIVATE)

    fun lastAt(c: Context): Long = state(c).getLong("lastAt", 0L)
    fun lastSummary(c: Context): String = state(c).getString("lastSummary", "") ?: ""

    fun undoFile(c: Context): File = File(File(c.filesDir, UNDO_DIR), UNDO_FILE)
    fun hasUndo(c: Context): Boolean = undoFile(c).isFile

    // ---------- Toplama ----------

    private fun prefMap(c: Context, name: String): Map<String, PrefValue> {
        val out = LinkedHashMap<String, PrefValue>()
        for ((k, v) in c.getSharedPreferences(name, Context.MODE_PRIVATE).all) PrefValue.of(v)?.let { out[k] = it }
        return out
    }

    /** Mevcut durumdan yedek içeriği. Anahtarlar istenmediyse hiç okunmaz. */
    fun collect(c: Context, includeKeys: Boolean): BackupContents {
        val files = LinkedHashMap<String, ByteArray>()
        for (dir in BackupPolicy.FILE_DIRS) {
            val d = File(c.filesDir, dir)
            for (f in d.listFiles().orEmpty().sortedBy { it.name }) {
                val rel = "$dir/${f.name}"
                if (f.isFile && BackupPolicy.isAllowedFile(rel)) files[rel] = f.readBytes()
            }
        }
        val secrets = LinkedHashMap<String, ByteArray>()
        if (includeKeys) {
            for (w in WatchRegistry.list(c).watches) {
                if (w.type != WatchType.BAND8) continue
                SecretStore.get(c, w.id)?.let { secrets[w.id] = it }
            }
        }
        return BackupContents(
            createdAt = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
            prefs = mapOf(
                BackupPolicy.PREFS_MAIN to BackupPolicy.filterMain(prefMap(c, BackupPolicy.PREFS_MAIN), includeKeys),
                BackupPolicy.PREFS_WATCHES to BackupPolicy.filterWatches(prefMap(c, BackupPolicy.PREFS_WATCHES)),
            ),
            files = files,
            secrets = secrets,
            includesKeys = includeKeys,
        )
    }

    // ---------- Yedekleme ----------

    /** Arka planda yedeği [uri]'ye yazar. Sonuç ana iş parçacığında: null başarı, değilse Türkçe hata. Parola silinir. */
    fun write(c: Context, uri: Uri, includeKeys: Boolean, password: CharArray?, done: (String?) -> Unit) {
        val app = c.applicationContext
        if (busy) { done("Başka bir yedek işi sürüyor."); return }
        busy = true
        Thread({
            var err: String? = null
            var contents: BackupContents? = null
            try {
                contents = collect(app, includeKeys)
                val bytes = BackupFormat.encode(contents, if (includeKeys) password else null)
                val out = app.contentResolver.openOutputStream(uri, "wt") ?: throw java.io.IOException()
                out.use { it.write(bytes) }
                val s = BackupSummary.of(contents)
                state(app).edit().putLong("lastAt", contents.createdAt).putString("lastSummary", s.short()).apply()
                WatchHub.log("Yedek alındı: ${s.watchNames.size} saat, ${s.ls05Faces + s.band8Faces} kadran, " +
                    "${if (includeKeys) "şifreli" else "şifresiz"}.")
            } catch (e: BackupException) {
                err = e.error.message
            } catch (e: Exception) {
                err = "Yedek yazılamadı (${e.javaClass.simpleName})."
                WatchHub.log(err)
            } finally {
                contents?.wipe()
                password?.fill('\u0000')
                busy = false
            }
            main.post { done(err) }
        }, "yedek-yaz").start()
    }

    // ---------- Okuma ----------

    /** Dosyayı okur (boyut sınırıyla). Arka planda çağrılmalı. */
    fun readBytes(c: Context, uri: Uri): ByteArray {
        val input = c.contentResolver.openInputStream(uri) ?: throw java.io.IOException()
        input.use { s ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var n = 0L
            while (true) {
                val r = s.read(buf)
                if (r < 0) break
                n += r
                if (n > BackupFormat.MAX_FILE_BYTES) throw BackupException(BackupError.TOO_LARGE)
                out.write(buf, 0, r)
            }
            return out.toByteArray()
        }
    }

    // ---------- Geri yükleme ----------

    /**
     * Doğrulanmış içeriği uygular: önce mevcut durumun kopyası (anahtarsız) alınır, servis durdurulur,
     * dosyalar ve ayarlar değiştirilir, sonra servis (açıksa) yeniden başlar. Sonuç ana iş parçacığında.
     * [makeUndo] false ise (geri dönüşte) kopya alınmaz.
     */
    fun apply(c: Context, contents: BackupContents, makeUndo: Boolean = true, done: (String?) -> Unit) {
        val app = c.applicationContext
        if (busy) { done("Başka bir yedek işi sürüyor."); return }
        busy = true
        val wasEnabled = Prefs.serviceEnabled(app)
        Thread({
            var err: String? = null
            try {
                if (makeUndo) {
                    val undo = BackupFormat.encode(collect(app, includeKeys = false), null)
                    val f = undoFile(app)
                    f.parentFile?.mkdirs()
                    val tmp = File(f.parentFile, "$UNDO_FILE.tmp")
                    tmp.writeBytes(undo)
                    if (!tmp.renameTo(f)) throw java.io.IOException()
                }
                if (wasEnabled) {
                    main.post { WatchService.start(app, WatchService.ACTION_STOP) }
                    Thread.sleep(STOP_WAIT_MS)
                }
                applyNow(app, contents)
            } catch (e: BackupException) {
                err = e.error.message
            } catch (e: Exception) {
                err = "Geri yükleme tamamlanamadı (${e.javaClass.simpleName})."
                WatchHub.log(err)
            } finally {
                contents.wipe()
            }
            main.post {
                SettingsStore.forgetAll()
                FaceLibrary.clearPreviews()
                if (err == null) {
                    Reminders.reschedule(app)
                    WatchHub.log("Yedek geri yüklendi.")
                }
                if (wasEnabled && WatchRegistry.hasActive(app)) WatchService.start(app)
                busy = false
                done(err)
            }
        }, "yedek-yukle").start()
    }

    private fun applyNow(c: Context, b: BackupContents) {
        // 1) Saat listesi: önce denetlenir; yazılamazsa başka hiçbir şeye dokunulmaz.
        val listText = (b.prefs[BackupPolicy.PREFS_WATCHES]?.get(BackupPolicy.WATCH_LIST_KEY) as? PrefValue.Str)?.v
        val list = if (listText == null) WatchList.EMPTY else WatchListStorage.parse(listText)
            ?: throw BackupException(BackupError.CORRUPT)

        // 2) Kadran klasörleri: önce geçici klasöre yazılır, sonra yer değiştirir.
        for (dir in BackupPolicy.FILE_DIRS) {
            val tmp = File(c.filesDir, "$dir.restore")
            tmp.deleteRecursively()
            tmp.mkdirs()
            for ((path, data) in b.files) {
                if (!path.startsWith("$dir/")) continue
                File(tmp, path.substring(dir.length + 1)).writeBytes(data)
            }
        }
        if (!WatchRegistry.replaceAll(c, list)) throw java.io.IOException("liste")
        for (dir in BackupPolicy.FILE_DIRS) {
            val target = File(c.filesDir, dir)
            val tmp = File(c.filesDir, "$dir.restore")
            target.deleteRecursively()
            if (!tmp.renameTo(target)) throw java.io.IOException("klasör")
        }

        // 3) Ana ayarlar: yedekteki her şey; geçici durum ve (yedekte yoksa) mevcut kimlikler korunur.
        val prefs = c.getSharedPreferences(BackupPolicy.PREFS_MAIN, Context.MODE_PRIVATE)
        val merged = BackupPolicy.mergeMain(prefMap(c, BackupPolicy.PREFS_MAIN),
            b.prefs[BackupPolicy.PREFS_MAIN].orEmpty(), b.includesKeys)
        val e = prefs.edit().clear()
        for ((k, v) in merged) {
            when (v) {
                is PrefValue.Str -> e.putString(k, v.v)
                is PrefValue.Int32 -> e.putInt(k, v.v)
                is PrefValue.Int64 -> e.putLong(k, v.v)
                is PrefValue.Bool -> e.putBoolean(k, v.v)
                is PrefValue.Real -> e.putFloat(k, v.v)
                is PrefValue.StrSet -> e.putStringSet(k, v.v)
            }
        }
        if (!e.commit()) throw java.io.IOException("ayar")

        // 4) Band 8 anahtarları: bu telefonun Keystore anahtarıyla yeniden şifrelenir.
        var keys = 0
        for ((id, key) in b.secrets) {
            if (list.find(id)?.type != WatchType.BAND8) continue
            if (SecretStore.putHex(c, id, BackupFormat.hex(key))) keys++
        }
        WatchHub.log("Geri yükleme: ${list.watches.size} saat, ${b.files.size} kadran dosyası, $keys anahtar.")
    }

    /** "Geri yükleme öncesine dön": otomatik kopyayı okur (şifresiz, anahtarsız). */
    fun readUndo(c: Context): BackupContents = BackupFormat.decode(undoFile(c).readBytes(), null)
}
