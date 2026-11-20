package com.che.ls05

import android.content.Context
import com.che.ls05.protocol.Band8Health
import com.che.ls05.protocol.HealthEntry
import com.che.ls05.protocol.HealthLog
import com.che.ls05.protocol.HealthQuery
import com.che.ls05.protocol.HealthSource
import com.che.ls05.protocol.HealthView
import com.che.ls05.protocol.Hex
import com.che.ls05.protocol.MergedHealth
import com.che.ls05.protocol.SourceKind
import com.che.ls05.protocol.StepHour
import com.che.ls05.protocol.SwitchTimeline
import com.che.ls05.protocol.WatchRecord
import com.che.ls05.protocol.WatchType
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Ham sağlık kayıtları: filesDir/health/yyyy-MM-dd.log (alınma gününe göre), yalnızca sona ekleme.
 * Kaynak klasörü (v0.12): göç edilen LS05 (önek boş) v0.11'deki "health" klasörünü kullanır; diğer saatler
 * "health-<kimlik>" klasörünü. Satır biçimi aynıdır; kaynak klasörden bilinir. Bu sürümde okuma etkin saatin
 * klasöründen yapılır; birleşik görünüm sonraki adımda.
 * Bellekte son 3 günün kayıtları tutulur. Canlı adım (09) dosyaya yazılmaz, yalnızca bellekte.
 * Sağlık değerleri loga yazılmaz. Tüm çağrılar ana iş parçacığında yapılır.
 */
object HealthStore {

    private const val DIR = "health"
    private const val VIEW_DAYS = 3L
    private const val KEEP_DAYS = 90L
    private val NAME = Regex("""\d{4}-\d{2}-\d{2}\.log""")

    private var dir: File? = null
    private val entries = ArrayList<HealthEntry>()
    private val seen = HashSet<String>()
    private var trimmedFor: LocalDate? = null

    /** Canlı (09) adım kayıtları, anahtar "y-m-d-h". Dosyaya yazılmaz. */
    val live: MutableMap<String, StepHour> = LinkedHashMap()

    private fun zone(): ZoneId = ZoneId.systemDefault()
    private fun today(): LocalDate = LocalDate.now(zone())
    private fun fileFor(d: File, day: LocalDate) = File(d, "$day.log")

    /** Etkin saatin sağlık klasörü adı. */
    fun dirName(c: Context): String {
        val a = WatchRegistry.active(c)
        return if (a == null || a.legacy) DIR else "$DIR-${a.id}"
    }

    /** Belirli bir saat kaydının sağlık klasörü adı. */
    fun dirNameFor(w: WatchRecord): String = if (w.legacy) DIR else "$DIR-${w.id}"

    private var app: Context? = null

    /** Etkin olmayan LS05 saatlerinin son 3 günlük kayıtları (birleşik görünüm için). */
    private var otherLs05: List<Pair<HealthSource, List<HealthEntry>>> = emptyList()
    private var switches: SwitchTimeline = SwitchTimeline.parse("")

    private fun source(w: WatchRecord) =
        HealthSource(w.id, w.name, if (w.type == WatchType.BAND8) SourceKind.BAND8 else SourceKind.LS05)

    private fun readEntries(d: File, days: Long, today: LocalDate): List<HealthEntry> {
        val list = ArrayList<HealthEntry>()
        val known = HashSet<String>()
        for (back in days - 1 downTo 0L) {
            val f = fileFor(d, today.minusDays(back))
            try {
                if (!f.exists()) continue
                for (e in HealthLog.parseAll(f.readText(Charsets.UTF_8))) if (known.add(e.hex)) list += e
            } catch (_: Exception) {
            }
        }
        return list
    }

    private fun readSwitches(c: Context): SwitchTimeline = try {
        val f = SwitchLog.file(c)
        SwitchTimeline.parse(if (f.isFile) f.readText(Charsets.UTF_8) else "")
    } catch (_: Exception) {
        SwitchTimeline.parse("")
    }

    fun init(c: Context) {
        if (dir != null) return
        app = c.applicationContext
        val d = File(c.filesDir, dirName(c))
        dir = d
        val activeId = WatchRegistry.active(c)?.id
        otherLs05 = WatchRegistry.list(c).watches
            .filter { it.type == WatchType.LS05 && it.id != activeId }
            .map { source(it) to readEntries(File(c.filesDir, dirNameFor(it)), VIEW_DAYS, today()) }
        switches = readSwitches(c)
        Band8HealthStore.ensureLoaded(c)
        try {
            if (!d.exists()) d.mkdirs()
        } catch (e: Exception) {
            WatchHub.log("Sağlık klasörü açılamadı: ${e.javaClass.simpleName}")
        }
        val today = today()
        var bad = 0
        for (back in VIEW_DAYS - 1 downTo 0L) {
            val day = today.minusDays(back)
            val f = fileFor(d, day)
            try {
                if (!f.exists()) continue
                val text = f.readText(Charsets.UTF_8)
                val parsed = HealthLog.parseAll(text)
                bad += text.split('\n').count { it.isNotBlank() } - parsed.size
                for (e in parsed) {
                    if (seen.add(e.hex)) entries += e
                }
                // Yarım kalmış son satır yeni kayda yapışmasın.
                if (back == 0L && text.isNotEmpty() && !text.endsWith("\n")) {
                    FileOutputStream(f, true).use { it.write("\n".toByteArray(Charsets.UTF_8)) }
                }
            } catch (e: Exception) {
                WatchHub.log("Sağlık kaydı okunamadı ($day): ${e.javaClass.simpleName}")
            }
        }
        trimmedFor = today
        if (bad > 0) WatchHub.log("Sağlık kayıtlarında $bad bozuk satır atlandı.")
        deleteOld(d, today)
    }

    /** reset ile artar; uzun aralık önbellekleri (Bugün ekranı) buna bakıp yeniden okur. */
    var generation = 0
        private set

    /** Band 8 verisi yeniden ayrıştırıldı: uzun aralık önbellekleri yeniden okunsun. */
    fun band8Changed() {
        generation++
    }

    /** Etkin saat değişti: bellek boşaltılır, sonraki çağrı etkin saatin kayıtlarını yükler. */
    fun reset(c: Context) {
        generation++
        dir = null
        entries.clear()
        seen.clear()
        live.clear()
        trimmedFor = null
        otherLs05 = emptyList()
        init(c)
    }

    /** 90 günden eski günlük dosyaları siler. Yalnızca adı tarih biçimine uyan dosyalara dokunur. */
    private fun deleteOld(d: File, today: LocalDate) {
        try {
            val limit = today.minusDays(KEEP_DAYS)
            var removed = 0
            for (f in d.listFiles() ?: emptyArray()) {
                if (!f.isFile || !NAME.matches(f.name)) continue
                val day = try { LocalDate.parse(f.name.removeSuffix(".log")) } catch (e: Exception) { continue }
                if (day.isBefore(limit) && f.delete()) removed++
            }
            if (removed > 0) WatchHub.log("90 günden eski $removed sağlık dosyası silindi.")
        } catch (e: Exception) {
            WatchHub.log("Eski sağlık dosyaları silinemedi: ${e.javaClass.simpleName}")
        }
    }

    /** Gün değiştiyse bellekte yalnızca son 3 günü bırakır ve eski dosyaları siler. */
    private fun trim() {
        val today = today()
        if (trimmedFor == today) return
        trimmedFor = today
        val start = today.minusDays(VIEW_DAYS - 1).atStartOfDay(zone()).toEpochSecond()
        entries.removeAll { it.receivedSec < start }
        seen.clear()
        entries.forEach { seen.add(it.hex) }
        dir?.let { deleteOld(it, today) }
    }

    /** Saklanması gereken bir kayıtsa dosyaya ekler. Saklandıysa true. Aynı paket ikinci kez yazılmaz. */
    fun add(c: Context, bytes: ByteArray): Boolean {
        init(c)
        if (!HealthLog.shouldStore(bytes)) return false
        trim()
        val copy = bytes.copyOf()
        val hex = Hex.of(copy)
        if (hex in seen) return false
        val d = dir ?: return false
        val nowMs = System.currentTimeMillis()
        val instant = Instant.ofEpochMilli(nowMs)
        val offsetMin = zone().rules.getOffset(instant).totalSeconds / 60
        val entry = HealthEntry(nowMs / 1000, offsetMin, copy)
        val day = instant.atZone(zone()).toLocalDate()
        return try {
            if (!d.exists()) d.mkdirs()
            FileOutputStream(fileFor(d, day), true).use {
                it.write((HealthLog.line(entry) + "\n").toByteArray(Charsets.UTF_8))
            }
            seen.add(hex)
            entries += entry
            true
        } catch (e: Exception) {
            WatchHub.log("Sağlık kaydı yazılamadı: ${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * Dışa aktarma için son [days] günün dosyaları. Ana iş parçacığında çağrılır; dönen işlev arka planda
     * çalıştırılabilir, dosyaları yalnızca okur ve bellekteki duruma dokunmaz. [view] yalnızca son 3 günü tuttuğu için
     * uzun aralık dosyalardan okunur.
     */
    fun reader(c: Context, days: Long): () -> HealthQuery {
        init(c)
        val liveCopy = live.values.toList()
        val today = today()
        val filesDir = c.filesDir
        val appCtx = c.applicationContext
        val watches = WatchRegistry.list(c).watches
        val activeId = WatchRegistry.active(c)?.id
        val zone = zone()
        return {
            val ls = watches.filter { it.type == WatchType.LS05 }.map { w ->
                val entries = readEntries(File(filesDir, dirNameFor(w)), days, today)
                source(w) to HealthView(entries, if (w.id == activeId) liveCopy else emptyList())
            }
            val band = watches.filter { it.type == WatchType.BAND8 }.map { w ->
                source(w) to Band8HealthStore.readDays(appCtx, dirNameFor(w), today.minusDays(days - 1), today)
            }
            val sw = readSwitches(appCtx)
            MergedHealth(ls, band, { t -> sw.activeAt(t.atZone(zone).toEpochSecond()) }, activeId)
        }
    }

    /** Son 3 günün kayıtları + bellekteki canlı adımlar, bütün saatlerden birleşik. */
    fun view(): HealthQuery {
        if (dir != null) trim()
        val c = app ?: return HealthView(entries.toList(), live.values.toList())
        val list = WatchRegistry.list(c)
        val active = list.active
        val ls = ArrayList<Pair<HealthSource, HealthView>>()
        if (active == null || active.type == WatchType.LS05) {
            val src = active?.let { source(it) } ?: HealthSource("", "LS05", SourceKind.LS05)
            ls += src to HealthView(entries.toList(), live.values.toList())
        }
        for ((src, e) in otherLs05) ls += src to HealthView(e)
        val since = today().minusDays(VIEW_DAYS)
        val snap = Band8HealthStore.snapshot
        val band = list.watches.filter { it.type == WatchType.BAND8 }.mapNotNull { w ->
            val h = snap[w.id] ?: return@mapNotNull null
            source(w) to recent(h, since)
        }
        val zone = zone()
        val sw = switches
        return MergedHealth(ls, band, { t -> sw.activeAt(t.atZone(zone).toEpochSecond()) }, active?.id)
    }

    /** Ana iş parçacığında birleştirme küçük kalsın: yalnızca son günler. */
    private fun recent(h: Band8Health, since: LocalDate): Band8Health = Band8Health(
        h.stepHours.filterKeys { !it.first.isBefore(since) },
        h.stepDays.filterKeys { !it.isBefore(since) },
        h.heart.filterKeys { !it.toLocalDate().isBefore(since) },
        h.sleep.filter { !it.wakeDay.isBefore(since) },
    )
}
