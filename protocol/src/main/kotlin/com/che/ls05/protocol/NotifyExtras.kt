package com.che.ls05.protocol

/**
 * "Okunana kadar tekrar": bildirim telefonda durdukça her [everyMin] dakikada bir, en çok [max] kez yeniden gönderilir.
 * Bildirim kaldırılınca durur. Saatte en çok [maxPerHour] tekrar (bütün bildirimler için) gider.
 * Durum yalnızca bildirim anahtarı ve sayaçlardır; metin saklanmaz.
 */
class RepeatTracker(private val maxPerHour: Int = 20) {
    private class Entry(val everyMs: Long, var remaining: Int, var nextAt: Long)

    private val entries = LinkedHashMap<String, Entry>()
    private val sentAt = ArrayDeque<Long>()

    val isEmpty: Boolean get() = entries.isEmpty()
    val keys: Set<String> get() = entries.keys

    fun schedule(key: String, everyMin: Int, max: Int, now: Long) {
        if (everyMin <= 0 || max <= 0) { entries.remove(key); return }
        val every = everyMin * 60_000L
        entries[key] = Entry(every, max, now + every)
    }

    fun remove(key: String) { entries.remove(key) }

    fun nextWake(): Long? = entries.values.minOfOrNull { it.nextAt }

    /** Zamanı gelen anahtarlar; gönderilmiş sayılır. Saatlik sınır doluysa bekler. */
    fun due(now: Long): List<String> {
        while (sentAt.isNotEmpty() && now - sentAt.first() > 3_600_000L) sentAt.removeFirst()
        val out = ArrayList<String>()
        val ready = entries.entries.filter { it.value.nextAt <= now }.sortedBy { it.value.nextAt }
        for ((key, e) in ready) {
            if (sentAt.size >= maxPerHour) break
            out += key
            sentAt.addLast(now)
            e.remaining--
            e.nextAt = now + e.everyMs
            if (e.remaining <= 0) entries.remove(key)
        }
        return out
    }

    /** Servis yeniden başlarsa sürsün diye: anahtar, aralık, kalan, sonraki zaman. */
    fun encode(): String = entries.entries.joinToString("\n") { (k, e) ->
        "${k.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")}\t${e.everyMs}\t${e.remaining}\t${e.nextAt}"
    }

    companion object {
        fun decode(text: String?, maxPerHour: Int): RepeatTracker {
            val t = RepeatTracker(maxPerHour)
            for (line in (text ?: "").split('\n')) {
                val p = line.split('\t')
                if (p.size != 4) continue
                val every = p[1].toLongOrNull() ?: continue
                val rem = p[2].toIntOrNull() ?: continue
                val next = p[3].toLongOrNull() ?: continue
                if (every <= 0 || rem <= 0) continue
                val key = p[0].replace("\\t", "\t").replace("\\n", "\n").replace("\\\\", "\\")
                t.entries[key] = Entry(every, rem, next)
            }
            return t
        }
    }
}

/**
 * Süren navigasyon bildirimi (Google Maps ve benzerleri): yönerge (başlık) değişince hemen, yalnızca mesafe
 * (metin) değişince en sık [minIntervalMs]'de bir iletilir. Aynı yönerge ve metin tekrar gitmez.
 */
class NavigationFilter(private val minIntervalMs: Long = 10_000) {
    private class Last(val title: String, val text: String, val at: Long)

    private val last = HashMap<String, Last>()

    fun shouldForward(pkg: String, title: String, text: String, now: Long): Boolean {
        val l = last[pkg]
        val go = when {
            l == null -> true
            l.title != title -> true
            l.text == text -> false
            else -> now - l.at >= minIntervalMs
        }
        if (go) last[pkg] = Last(title, text, now)
        return go
    }

    fun reset(pkg: String) { last.remove(pkg) }

    companion object {
        /** Bilinen navigasyon paketleri. Google Maps dışındakiler cihazda doğrulanmadı. */
        val PACKAGES: Set<String> = setOf("com.google.android.apps.maps", "net.osmand", "net.osmand.plus", "de.komoot.android")
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
