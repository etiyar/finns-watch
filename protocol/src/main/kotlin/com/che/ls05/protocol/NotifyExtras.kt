package com.che.ls05.protocol

/**
 * "Okunana kadar tekrar": bildirim telefonda durdukça her [everyMin] dakikada bir, en çok [max] kez yeniden gönderilir.
 * Sınır ilk bildirimden sonraki toplam tekrardır: aynı anahtar yeni mesajla güncellenince sayaç baştan başlamaz.
 * Hakkı biten anahtar, bildirim kaldırılana kadar (remove) kalan 0 olarak tutulur; böylece yeni mesaj sayacı yenilemez.
 * Bildirim kaldırılınca durur. Saatte en çok [maxPerHour] tekrar (bütün bildirimler için) gider.
 * Durum yalnızca bildirim anahtarı ve sayaçlardır; metin saklanmaz.
 */
class RepeatTracker(private val maxPerHour: Int = 20) {
    private class Entry(val everyMs: Long, var remaining: Int, var nextAt: Long)

    private val entries = LinkedHashMap<String, Entry>()
    private val sentAt = ArrayDeque<Long>()

    /** Tekrar hakkı kalan bildirim yok. */
    val isEmpty: Boolean get() = entries.values.none { it.remaining > 0 }

    /** Takip edilen bütün anahtarlar (hakkı bitenler dahil); telefonda kalmayanları silmek için. */
    val keys: Set<String> get() = entries.keys

    /** Anahtarın kalan tekrar sayısı; takip edilmiyorsa null. */
    fun remaining(key: String): Int? = entries[key]?.remaining

    /**
     * İlk bildirimde sayaç [max] ile başlar. Anahtar zaten varsa (sohbete yeni mesaj geldi) kalan sayı ve sıradaki
     * zaman korunur; yalnızca kural sınırı düşürüldüyse kalan sayı yeni sınıra indirilir.
     */
    fun schedule(key: String, everyMin: Int, max: Int, now: Long) {
        if (everyMin <= 0 || max <= 0) { entries.remove(key); return }
        val existing = entries[key]
        if (existing != null) {
            if (existing.remaining > max) existing.remaining = max
            return
        }
        val every = everyMin * 60_000L
        entries[key] = Entry(every, max, now + every)
        trimExhausted()
    }

    fun remove(key: String) { entries.remove(key) }

    fun nextWake(): Long? = entries.values.filter { it.remaining > 0 }.minOfOrNull { it.nextAt }

    /** Zamanı gelen anahtarlar; gönderilmiş sayılır. Saatlik sınır doluysa bekler. */
    fun due(now: Long): List<String> {
        while (sentAt.isNotEmpty() && now - sentAt.first() > 3_600_000L) sentAt.removeFirst()
        val out = ArrayList<String>()
        val ready = entries.entries.filter { it.value.remaining > 0 && it.value.nextAt <= now }.sortedBy { it.value.nextAt }
        for ((key, e) in ready) {
            if (sentAt.size >= maxPerHour) break
            out += key
            sentAt.addLast(now)
            e.remaining--
            e.nextAt = if (e.remaining > 0) now + e.everyMs else Long.MAX_VALUE
        }
        return out
    }

    /** Hakkı biten anahtarlar bildirim kaldırılınca silinir; kaçan olursa liste sınırsız büyümesin. */
    private fun trimExhausted() {
        val done = entries.entries.filter { it.value.remaining <= 0 }.map { it.key }
        if (done.size > MAX_EXHAUSTED) done.take(done.size - MAX_EXHAUSTED).forEach { entries.remove(it) }
    }

    /** Servis yeniden başlarsa sürsün diye: anahtar, aralık, kalan, sonraki zaman. */
    fun encode(): String = entries.entries.joinToString("\n") { (k, e) ->
        "${k.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")}\t${e.everyMs}\t${e.remaining}\t${e.nextAt}"
    }

    companion object {
        /** Kalanı 0 olarak tutulan en çok anahtar sayısı. */
        const val MAX_EXHAUSTED = 200

        fun decode(text: String?, maxPerHour: Int): RepeatTracker {
            val t = RepeatTracker(maxPerHour)
            for (line in (text ?: "").split('\n')) {
                val p = line.split('\t')
                if (p.size != 4) continue
                val every = p[1].toLongOrNull() ?: continue
                val rem = p[2].toIntOrNull() ?: continue
                val next = p[3].toLongOrNull() ?: continue
                if (every <= 0 || rem < 0) continue
                val key = p[0].replace("\\t", "\t").replace("\\n", "\n").replace("\\\\", "\\")
                t.entries[key] = Entry(every, rem, if (rem == 0) Long.MAX_VALUE else next)
            }
            return t
        }
    }
}

/**
 * Süren navigasyon bildirimi (Google Maps ve benzerleri). Mesafe ("200 m", "0,4 km") başlıkta ya da metinde olabilir.
 * Manevra (mesafe dışındaki yazı) değişince hemen, yalnızca mesafe değişince en sık [minIntervalMs]'de bir iletilir.
 * Aynı manevra ve mesafe tekrar gitmez. Çıktıda başlık kısa manevra adı ("Sola dön"), gövde mesafe, yönerge ve sokak.
 * Manevra tanınmazsa başlık yönergenin kendisidir. Uzun yazı başa değil gövdeye gider; bant baştan kırpmaz.
 */
class NavigationFilter(private val minIntervalMs: Long = 30_000) {
    data class Out(val title: String, val body: String)

    private class Last(val maneuver: String, val distance: String, val at: Long)

    private val last = HashMap<String, Last>()

    private fun strip(s: String) = s.replace(DISTANCE, "").replace(Regex("^[\\s·•,-]+|[\\s·•,-]+$"), "").replace(Regex("\\s{2,}"), " ").trim()

    /** Ayrıştırılmış yönerge: manevra yazısı, mesafe ve geri kalan (çoğunlukla sokak). */
    data class Parts(val maneuver: String, val distance: String, val rest: String) {
        val key: String get() = NotifyRules.norm(maneuver)
        val kind: NavManeuver get() = NavManeuver.classify(maneuver)
    }

    /** Başlık ve metinden manevra, mesafe ve sokağı ayırır. Durum tutmaz. */
    fun parts(title: String, text: String): Parts {
        val titleDist = DISTANCE.find(title)?.value
        val textDist = DISTANCE.find(text)?.value
        val distance = titleDist ?: textDist ?: ""
        val t = strip(title)
        val x = strip(text)
        // Manevra: mesafesi olmayan alan; ikisinde de yoksa başlık.
        val (maneuver, rest) = when {
            titleDist != null && textDist == null -> x.ifEmpty { t } to (if (x.isEmpty()) "" else t)
            textDist != null && titleDist == null -> t.ifEmpty { x } to (if (t.isEmpty()) "" else x)
            else -> t.ifEmpty { x } to (if (t.isEmpty()) "" else x)
        }
        return Parts(maneuver, distance, rest)
    }

    /** İletilecekse başlık ve gövde; değilse null. */
    fun decide(pkg: String, title: String, text: String, now: Long): Out? {
        val (maneuver, distance, rest) = parts(title, text)
        val key = NotifyRules.norm(maneuver)
        val l = last[pkg]
        val go = when {
            l == null -> true
            l.maneuver != key -> true
            l.distance == distance -> false
            else -> now - l.at >= minIntervalMs
        }
        if (!go) return null
        last[pkg] = Last(key, distance, now)
        val kind = NavManeuver.classify(maneuver)
        val m = maneuver.ifEmpty { title.trim() }
        return when {
            kind != NavManeuver.UNKNOWN -> Out(kind.label, join(distance, maneuver, rest))
            m.length <= TITLE_MAX -> Out(m, join(distance, rest))
            else -> Out(shorten(m), join(distance, m, rest))
        }
    }

    fun reset(pkg: String) { last.remove(pkg) }

    private fun join(vararg parts: String) = parts.filter { it.isNotBlank() }.joinToString(" · ")

    /** Sağdan, sözcük sınırında kısaltır; baş her zaman görünür. */
    private fun shorten(s: String): String {
        val cut = s.take(TITLE_MAX)
        val space = cut.lastIndexOf(' ')
        return (if (space >= TITLE_MAX / 2) cut.substring(0, space) else cut).trimEnd(' ', ',', '·', '-') + "…"
    }

    companion object {
        /** Bilinen navigasyon paketleri. Google Maps dışındakiler cihazda doğrulanmadı. */
        val PACKAGES: Set<String> = setOf("com.google.android.apps.maps", "net.osmand", "net.osmand.plus", "de.komoot.android")

        /** Bant başlığı kısa olmalı: uzun başlık bandda baştan kesik görüldü (build 197). */
        const val TITLE_MAX = 20

        private val DISTANCE = Regex("\\d+(?:[.,]\\d+)?\\s?(?:km|m|mi|ft|yd)\\b", RegexOption.IGNORE_CASE)
    }
}

/** Navigasyon yönergesinden kısa manevra adı. Türkçe ve İngilizce anahtar sözcükler; tanınmazsa [UNKNOWN]. */
enum class NavManeuver(val label: String) {
    ARRIVE("Varış"),
    U_TURN("U dönüşü"),
    ROUNDABOUT("Göbek"),
    SLIGHT_LEFT("Hafif sola"),
    SLIGHT_RIGHT("Hafif sağa"),
    SHARP_LEFT("Keskin sola"),
    SHARP_RIGHT("Keskin sağa"),
    LEFT("Sola dön"),
    RIGHT("Sağa dön"),
    STRAIGHT("Düz devam"),
    UNKNOWN("");

    companion object {
        private val LEFT_WORDS = setOf("sola", "solda", "left")
        private val RIGHT_WORDS = setOf("saga", "sagda", "right")
        private val SLIGHT = setOf("hafif", "slight", "keep", "bear")
        private val SHARP = setOf("keskin", "sharp")

        fun classify(text: String): NavManeuver {
            val w = NotifyRules.norm(text).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
            fun has(vararg words: String) = words.any { it in w }
            fun pair(a: Set<String>, b: Set<String>) = (0 until w.size - 1).any { w[it] in a && w[it + 1] in b }
            return when {
                has("ulastiniz", "vardiniz", "arrive", "arrived", "destination") -> ARRIVE
                (has("u") && has("donusu", "donus", "turn")) || has("uturn") -> U_TURN
                has("gobek", "gobekten", "gobekte", "roundabout") -> ROUNDABOUT
                pair(SLIGHT, LEFT_WORDS) -> SLIGHT_LEFT
                pair(SLIGHT, RIGHT_WORDS) -> SLIGHT_RIGHT
                pair(SHARP, LEFT_WORDS) -> SHARP_LEFT
                pair(SHARP, RIGHT_WORDS) -> SHARP_RIGHT
                w.any { it in LEFT_WORDS } -> LEFT
                w.any { it in RIGHT_WORDS } -> RIGHT
                has("duz", "straight", "continue", "devam", "head", "ilerleyin") -> STRAIGHT
                else -> UNKNOWN
            }
        }
    }
}

/**
 * Band 8 müzik ses düzeyi. 18/1 içinde STREAM_MUSIC yüzdesi (0-100) bildirilir.
 * Bantın 18/2 ile gönderdiği değerin anlamı kesin değil (build 197: yukarıda en üst, aşağıda en alt sıçraması).
 * [decide]: değer son bildirdiğimiz yüzdeye yakınsa yüzde sayılır ve uygulanır; uzaksa yalnızca yön sayılır, bir çentik oynatılır.
 */
object MusicVolume {
    /** Yüzde sayılacak en büyük fark (puan). */
    const val PLAUSIBLE_DELTA = 10

    /** Bir çentik: akışın on beşte biri, en az bir adım. */
    fun notch(streamMax: Int): Int = maxOf(1, streamMax / 15)

    fun toStreamStep(level: Int, streamMax: Int): Int = ((level.coerceIn(0, 100) * streamMax + 50) / 100).coerceIn(0, streamMax)

    fun toPercent(step: Int, streamMax: Int): Int = if (streamMax <= 0) 0 else Math.round(step * 100f / streamMax).coerceIn(0, 100)

    /** Bantın ses olayından hedef akış adımı. [raw]: 18/2 ses alanının ham değeri. */
    fun decide(raw: Int, lastReportedPercent: Int?, currentStep: Int, streamMax: Int): Int {
        if (streamMax <= 0) return 0
        val base = lastReportedPercent ?: toPercent(currentStep, streamMax)
        if (raw == base) return currentStep.coerceIn(0, streamMax)
        if (raw in 0..100 && kotlin.math.abs(raw - base) <= PLAUSIBLE_DELTA) return toStreamStep(raw, streamMax)
        val dir = if (raw > base) 1 else -1
        return (currentStep + dir * notch(streamMax)).coerceIn(0, streamMax)
    }
}

/** Aynı başlık kısa sürede ikinci kez gelirse yakalar (takvim hatırlatması ve takvim uygulaması bildirimi). Başlık yalnızca bellekte, sadeleştirilmiş tutulur. */
class TitleDedupe(private val windowMs: Long = 180_000) {
    private val seen = LinkedHashMap<String, Long>()

    private fun prune(now: Long) {
        val it = seen.entries.iterator()
        while (it.hasNext()) if (now - it.next().value > windowMs) it.remove()
    }

    @Synchronized
    fun seenRecently(title: String, now: Long): Boolean {
        prune(now)
        val k = NotifyRules.norm(title)
        if (k.isEmpty()) return false
        val at = seen[k] ?: return false
        return now - at <= windowMs
    }

    @Synchronized
    fun mark(title: String, now: Long) {
        prune(now)
        val k = NotifyRules.norm(title)
        if (k.isNotEmpty()) seen[k] = now
    }

    companion object {
        /** Kendi etkinlik bildirimini gösteren takvim uygulamaları. Google Takvim dışındakiler cihazda doğrulanmadı. */
        val CALENDAR_PACKAGES: Set<String> = setOf(
            "com.google.android.calendar", "com.samsung.android.calendar", "com.android.calendar", "com.xiaomi.calendar",
        )
    }
}

/** Telefon pili uyarıları: düşük (eşik, şarjda değilken) ve dolu (şarjda %100). Her geçişte bir kez. */
class BatteryAlerts {
    enum class Alert { LOW, FULL }

    private var lowFired = false
    private var fullFired = false

    fun update(level: Int, charging: Boolean, lowOn: Boolean, threshold: Int, fullOn: Boolean): Alert? {
        if (charging || level > threshold) lowFired = false
        if (!charging || level < 100) fullFired = false
        if (lowOn && !charging && level in 0..threshold && !lowFired) {
            lowFired = true
            return Alert.LOW
        }
        if (fullOn && charging && level >= 100 && !fullFired) {
            fullFired = true
            return Alert.FULL
        }
        if (!lowOn && !charging && level <= threshold) lowFired = true
        if (!fullOn && charging && level >= 100) fullFired = true
        return null
    }
}
