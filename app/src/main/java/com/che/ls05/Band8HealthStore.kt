package com.che.ls05

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.che.ls05.protocol.Band8DayFile
import com.che.ls05.protocol.Band8Health
import com.che.ls05.protocol.WatchType
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors

/**
 * Band 8 sağlık verisi: arşivdeki dosyalar ayrıştırılır, `health-<kimlik>/band8-days/yyyy-MM-dd.txt` gün dosyalarına
 * yazılır ve bellekte tutulur. Aynı arşiv her seferinde aynı metni üretir; dosya yalnızca değiştiyse yazılır,
 * böylece yeniden ayrıştırma çift kayıt üretmez. Uygulama açılışında bir kez ve her çekme bitince çalışır.
 * Loga yalnızca sayılar yazılır; sağlık değeri yazılmaz.
 */
object Band8HealthStore {
    private const val DAYS_DIR = "band8-days"
    private const val KEEP_DAYS = 90L
    private val NAME = Regex("""\d{4}-\d{2}-\d{2}\.txt""")

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "band8-saglik").apply { isDaemon = true } }

    /** Saat kimliği → veri. Ana iş parçacığında okunur, yalnızca ana iş parçacığında değişir. */
    @Volatile
    var snapshot: Map<String, Band8Health> = emptyMap()
        private set

    private var startedOnce = false

    /** Süreç başına bir kez: var olan arşivi ayrıştırır. */
    fun ensureLoaded(c: Context) {
        if (startedOnce) return
        startedOnce = true
        rebuild(c, "açılış")
    }

    fun daysDir(c: Context, watchDir: String): File = File(File(c.filesDir, watchDir), DAYS_DIR)

    fun rebuild(c: Context, reason: String) {
        val app = c.applicationContext
        val watches = WatchRegistry.list(app).watches.filter { it.type == WatchType.BAND8 }
        io.execute {
            val out = HashMap<String, Band8Health>()
            var files = 0
            var days = 0
            var written = 0
            var failed = 0
            val zone = ZoneId.systemDefault()
            val limit = LocalDate.now(zone).minusDays(KEEP_DAYS)
            for (w in watches) {
                try {
                    val entries = Band8HealthArchive.readEntriesIn(Band8HealthArchive.directoryFor(app, w))
                    files += entries.size
                    val h = Band8Health.fromFiles(entries.map { it.file }, zone)
                    val dir = daysDir(app, HealthStore.dirNameFor(w))
                    if (!dir.isDirectory) dir.mkdirs()
                    for (d in Band8DayFile.days(h)) {
                        if (d.isBefore(limit)) continue
                        days++
                        when (writeIfChanged(File(dir, "$d.txt"), Band8DayFile.write(d, h))) {
                            true -> written++
                            false -> Unit
                            null -> failed++
                        }
                    }
                    deleteOld(dir, limit)
                    out[w.id] = h
                } catch (e: Exception) {
                    failed++
                }
            }
            main.post {
                snapshot = out
                WatchHub.log("Band 8 sağlık verisi ayrıştırıldı ($reason): $files arşiv dosyası, $days gün, $written gün dosyası güncellendi, hata=$failed")
                HealthStore.band8Changed()
                WatchHub.healthChanged()
            }
        }
    }

    /** Değiştiyse atomik yazar. Yazdıysa true, aynıysa false, hata varsa null. */
    private fun writeIfChanged(f: File, text: String): Boolean? {
        return try {
            if (f.isFile && f.readText(Charsets.UTF_8) == text) return false
            val tmp = File.createTempFile("b8d-", ".tmp", f.parentFile)
            try {
                FileOutputStream(tmp).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
                try {
                    Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                tmp.delete()
            }
            true
        } catch (_: Exception) {
            null
        }
    }

    private fun deleteOld(dir: File, limit: LocalDate) {
        for (f in dir.listFiles() ?: emptyArray()) {
            if (!f.isFile || !NAME.matches(f.name)) continue
            val d = try { LocalDate.parse(f.name.removeSuffix(".txt")) } catch (_: Exception) { continue }
            if (d.isBefore(limit)) f.delete()
        }
    }

    /** Arka planda: gün dosyalarından [from]..[to] arası (dışa aktarma ve 31 günlük geçmiş). */
    fun readDays(c: Context, watchDir: String, from: LocalDate, to: LocalDate): Band8Health {
        val dir = daysDir(c, watchDir)
        val parts = ArrayList<Band8Health>()
        var d = from
        while (!d.isAfter(to)) {
            val f = File(dir, "$d.txt")
            try {
                if (f.isFile) parts += Band8DayFile.read(d, f.readText(Charsets.UTF_8))
            } catch (_: Exception) {
            }
            d = d.plusDays(1)
        }
        return Band8Health.combine(parts)
    }
}
