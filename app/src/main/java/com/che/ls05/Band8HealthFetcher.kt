package com.che.ls05

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import com.che.ls05.protocol.band8.B8ActivityFile
import com.che.ls05.protocol.band8.B8Command
import com.che.ls05.protocol.band8.B8Fetch
import com.che.ls05.protocol.band8.B8FetchGate
import com.che.ls05.protocol.band8.B8FetchStats
import com.che.ls05.protocol.band8.B8Type
import java.util.concurrent.Executors

/**
 * Band 8 sağlık dosyalarını çeker ve yerel arşive yazar. Yalnızca okuma: 8/5 (bandan silme) hiç gönderilmez.
 * Komutlar yalnızca komut kanalı boşken çıkar; bildirimler önce gider. Hiçbir sağlık değeri gösterilmez
 * veya loglanmaz; log yalnızca dosya türü sayıları, bayt ve CRC sonucunu içerir.
 *
 * Bir Band 8 oturumu boyunca yaşar. Oturum hazır olunca kısa bir gecikmeyle, sonra 6 saatte bir çalışır.
 */
class Band8HealthFetcher(
    context: Context,
    private val main: Handler,
    private val log: (String) -> Unit,
    /** Komut kanalı boş mu (bekleyen komut ve BLE yazımı yok). */
    private val idle: () -> Boolean,
    /** Şifreli komut olarak kuyruğa al. */
    private val send: (ByteArray) -> Unit,
) {
    private val app = context.applicationContext
    private val gate = B8FetchGate()
    private var stats = B8FetchStats()
    private var alive = true
    private var stored = 0
    private var unchanged = 0
    private var saveFailed = 0

    private val tick = Runnable {
        poll()
        if (gate.isRunning) scheduleTick()
    }
    private val periodic = Runnable { begin("6 saatlik") }

    val isRunning: Boolean get() = gate.isRunning

    private val first = Runnable { begin("oturum başı") }

    fun startAfter(delayMs: Long) {
        main.removeCallbacks(periodic)
        main.removeCallbacks(first)
        main.postDelayed(first, delayMs)
    }

    /** Elle tetikleme (Gelişmiş). Sürüyorsa false. */
    fun startNow(): Boolean {
        if (!alive || gate.isRunning) return false
        begin("elle")
        return true
    }

    private fun begin(reason: String) {
        if (!alive) return
        main.removeCallbacks(periodic)
        main.postDelayed(periodic, INTERVAL_MS)
        if (gate.isRunning) {
            log("Band 8 sağlık dosyası çekme zaten sürüyor")
            return
        }
        stats = B8FetchStats()
        log("Band 8 sağlık dosyası çekme başladı ($reason); bandan silme komutu gönderilmez")
        handle(gate.start(now()), requestedRun = true)
        poll()
        scheduleTick()
    }

    fun onCommand(c: B8Command) {
        if (!alive || c.type != B8Type.HEALTH) return
        handle(gate.onCommand(c, now()), requestedRun = true)
        poll()
    }

    fun onActivityPart(part: ByteArray) {
        if (!alive) return
        val running = gate.isRunning
        handle(gate.onActivityPart(part, now()), requestedRun = running)
        poll()
    }

    /** Kanal boşaldıkça bekleyen çekme komutunu gönderir. */
    fun poll() {
        if (!alive) return
        handle(gate.poll(now(), idle()), requestedRun = true)
    }

    fun stop() {
        if (!alive) return
        alive = false
        main.removeCallbacks(tick)
        main.removeCallbacks(periodic)
        main.removeCallbacks(first)
        if (gate.isRunning) log("Band 8 sağlık dosyası çekme bağlantı kapanınca durdu; ${stats.summary()}")
    }

    private fun scheduleTick() {
        main.removeCallbacks(tick)
        if (alive) main.postDelayed(tick, TICK_MS)
    }

    private fun handle(outs: List<B8Fetch.Out>, requestedRun: Boolean) {
        for (o in outs) when (o) {
            is B8Fetch.Out.Send -> send(o.plain)
            is B8Fetch.Out.File -> {
                if (requestedRun) {
                    stats.file(o.file)
                    archive(o.file)
                } else {
                    // Çekme dışında band kendiliğinden dosya gönderdi (elle ölçüm gibi).
                    val single = B8FetchStats().also { it.file(o.file) }
                    log("Band 8 istenmeden dosya gönderdi: ${single.summary()}")
                    archive(o.file)
                    reportSaves("istenmeden gelen dosya")
                }
            }
            is B8Fetch.Out.Failed -> {
                stats.failed(o.reason)
                log("Band 8 sağlık dosyası alınamadı: ${o.reason}, tür=${o.id?.let { "t${it.type}/s${it.subtype}/d${it.detailType}" } ?: "bilinmiyor"}")
            }
            B8Fetch.Out.Done -> {
                main.removeCallbacks(tick)
                log("Band 8 sağlık dosyaları: ${stats.summary()}")
                reportSaves("çekme")
            }
        }
    }

    private fun archive(file: B8ActivityFile) {
        IO.execute {
            val r = Band8HealthArchive.save(app, file)
            main.post {
                when (r) {
                    Band8HealthArchive.Save.STORED -> stored++
                    Band8HealthArchive.Save.UNCHANGED -> unchanged++
                    else -> saveFailed++
                }
            }
        }
    }

    /** Arşiv yazımları tek iş parçacığında sırayla yapılır; bu özet hepsinden sonra yazılır. */
    private fun reportSaves(what: String) {
        IO.execute {
            main.post {
                val n = stored + unchanged
                log("Band 8: $n dosya arşivlendi ($what; yeni ya da güncellenen=$stored, zaten vardı=$unchanged, kaydedilemedi=$saveFailed)")
                if (what == "çekme" && WatchRegistry.active(app)?.type == com.che.ls05.protocol.WatchType.BAND8) {
                    Prefs.setLastSync(app, System.currentTimeMillis())
                }
                // Arşivden ayrıştır: gün dosyaları ve Bugün ekranı güncellenir.
                if (what == "çekme" || stored > 0) Band8HealthStore.rebuild(app, what)
                stored = 0
                unchanged = 0
                saveFailed = 0
            }
        }
    }

    private fun now(): Long = SystemClock.elapsedRealtime()

    companion object {
        const val FIRST_DELAY_MS = 5_000L
        const val INTERVAL_MS = 6 * 60 * 60 * 1000L
        private const val TICK_MS = 1_000L

        /** Arşiv yazımı için tek iş parçacığı; ana iş parçacığı diske beklemez. */
        private val IO = Executors.newSingleThreadExecutor { r -> Thread(r, "band8-arsiv").apply { isDaemon = true } }
    }
}
