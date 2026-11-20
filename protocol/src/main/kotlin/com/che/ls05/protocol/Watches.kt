package com.che.ls05.protocol

/**
 * Çoklu saat çekirdeği (v0.12): saat türü, yetenekler, saat kaydı ve göç kuralı.
 * Saf Kotlin; Android'e bağlı değil. Ayrıntı: docs/COKLU_SAAT_TASARIM.md.
 */
enum class WatchType(val label: String) {
    LS05("Haylou LS05"),
    BAND8("Xiaomi Smart Band 8");

    companion object {
        fun parse(text: String?): WatchType? = values().firstOrNull { it.name == text }
    }
}

/**
 * Saatin desteklediği özellikler. Arayüz desteklenmeyeni gizler.
 * Yeni bir özellik için bayrak yalnızca o özellik yazıldığında eklenir.
 */
data class Capabilities(
    val notifications: Boolean,
    val incomingCall: Boolean,
    val callReject: Boolean,
    val missedCall: Boolean,
    /** Saatteki alarm yuvası sayısı (0: alarm yok). */
    val alarmSlots: Int,
    /** Alarm notu (telefon notu saate bildirim olarak gönderir). */
    val alarmNote: Boolean,
    /** Alarmda titreşim süresi seçimi. */
    val alarmVibration: Boolean,
    val dnd: Boolean,
    val profile: Boolean,
    val weather: Boolean,
    val timeSync: Boolean,
    val steps: Boolean,
    val heart: Boolean,
    val sleep: Boolean,
    val liveSteps: Boolean,
    val findWatch: Boolean,
    val findPhone: Boolean,
    val musicRemote: Boolean,
    val faces: Boolean,
) {
    companion object {
        /** Herhangi bir saatte olabilecek en çok alarm yuvası (Band 8: 10). Zamanlama iptali buna kadar döner. */
        const val MAX_ALARM_SLOTS = 10

        val LS05 = Capabilities(
            notifications = true, incomingCall = true, callReject = true, missedCall = true,
            alarmSlots = UteAlarm.SLOTS, alarmNote = true, alarmVibration = true, dnd = true, profile = true,
            weather = true, timeSync = true, steps = true, heart = true, sleep = true, liveSteps = true,
            findWatch = true, findPhone = true, musicRemote = true, faces = true,
        )

        /** Doğrulanan Band 8 özellikleri yazıldıkça açılır. */
        val BAND8_BASE = Capabilities(
            notifications = true, incomingCall = false, callReject = false, missedCall = true,
            alarmSlots = 0, alarmNote = false, alarmVibration = false, dnd = true, profile = false,
            weather = false, timeSync = false, steps = true, heart = true, sleep = true, liveSteps = false,
            findWatch = false, findPhone = false, musicRemote = false, faces = false,
        )

        fun of(type: WatchType?): Capabilities = when (type) {
            WatchType.BAND8 -> BAND8_BASE
            else -> LS05
        }
    }
}

/**
 * Kayıtlı saat. ns: saate ait ayar anahtarlarının öneki. Göç edilen (ilk) LS05'in öneki boştur; anahtarları
 * v0.11 ile birebir aynıdır. Diğer saatler "d.<id>." önekini alır.
 */
data class WatchRecord(
    val id: String,
    val type: WatchType,
    val name: String,
    val ns: String,
    val added: Long,
) {
    val legacy: Boolean get() = ns.isEmpty()
}

/** Saat listesi. Değişmez; her işlem yeni bir nesne döner. */
data class WatchList(
    val watches: List<WatchRecord>,
    val activeId: String?,
    val next: Int,
) {
    val active: WatchRecord? get() = watches.firstOrNull { it.id == activeId }

    fun find(id: String?): WatchRecord? = watches.firstOrNull { it.id == id }

    /**
     * Yeni saat ekler. Öneksiz (göç edilmiş) LS05 kaydı yoksa yeni LS05 onun yerini alır: kimlik "ls05", önek boş.
     * Böylece eski sürüme dönülürse bu saat yine çalışır.
     */
    fun add(type: WatchType, name: String, now: Long): Pair<WatchList, WatchRecord> {
        val clean = cleanName(name).ifEmpty { type.label }
        if (type == WatchType.LS05 && watches.none { it.legacy || it.id == LEGACY_ID }) {
            val rec = WatchRecord(LEGACY_ID, type, clean, "", now)
            return copy(watches = watches + rec) to rec
        }
        var n = maxOf(next, 2)
        while (watches.any { it.id == "w$n" }) n++
        val rec = WatchRecord("w$n", type, clean, "d.w$n.", now)
        return copy(watches = watches + rec, next = n + 1) to rec
    }

    fun rename(id: String, name: String): WatchList {
        val clean = cleanName(name)
        if (clean.isEmpty()) return this
        return copy(watches = watches.map { if (it.id == id) it.copy(name = clean) else it })
    }

    /** Etkin saat silinemez; önce başka saate geçilir. */
    fun remove(id: String): WatchList {
        if (id == activeId) return this
        return copy(watches = watches.filter { it.id != id })
    }

    fun withActive(id: String?): WatchList = if (id == null || find(id) != null) copy(activeId = id) else this

    /** Satır: kimlik, tür, önek, eklenme, ad (sekmeyle). */
    fun serialize(): String = buildString {
        append("v=").append(VERSION).append('\n')
        append("active=").append(activeId ?: "").append('\n')
        append("next=").append(next).append('\n')
        for (w in watches) {
            append("w=").append(w.id).append('\t').append(w.type.name).append('\t').append(w.ns).append('\t')
                .append(w.added).append('\t').append(w.name).append('\n')
        }
    }

    companion object {
        const val VERSION = 1
        const val LEGACY_ID = "ls05"
        const val MAX_NAME = 30
        val EMPTY = WatchList(emptyList(), null, 2)

        /** Ad tek satır, sekmesiz, en çok 30 karakter. */
        fun cleanName(name: String): String =
            name.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim().take(MAX_NAME).trim()

        /** Bozuk ya da bilinmeyen sürümde null. */
        fun deserialize(text: String?): WatchList? {
            if (text.isNullOrBlank()) return null
            var version = -1
            var active: String? = null
            var next = 2
            val list = ArrayList<WatchRecord>()
            for (line in text.split('\n')) {
                if (line.isEmpty()) continue
                val eq = line.indexOf('=')
                if (eq <= 0) return null
                val k = line.substring(0, eq)
                val v = line.substring(eq + 1)
                when (k) {
                    "v" -> version = v.toIntOrNull() ?: return null
                    "active" -> active = v.ifEmpty { null }
                    "next" -> next = v.toIntOrNull() ?: return null
                    "w" -> {
                        val p = v.split('\t')
                        if (p.size != 5) return null
                        val type = WatchType.parse(p[1]) ?: return null
                        val added = p[3].toLongOrNull() ?: return null
                        if (p[0].isEmpty() || list.any { it.id == p[0] }) return null
                        list += WatchRecord(p[0], type, p[4], p[2], added)
                    }
                    else -> Unit
                }
            }
            if (version != VERSION) return null
            if (active != null && list.none { it.id == active }) active = null
            return WatchList(list, active, next)
        }
    }
}

/** Single-line SharedPreferences representation. Legacy plain-text lists remain readable. */
object WatchListStorage {
    private const val PREFIX = "b64.v1:"

    fun encode(list: WatchList): String = PREFIX + java.util.Base64.getEncoder()
        .encodeToString(list.serialize().toByteArray(Charsets.UTF_8))

    fun parse(stored: String?): WatchList? {
        if (stored == null) return null
        if (!stored.startsWith(PREFIX)) return WatchList.deserialize(stored)
        return try {
            val decoded = java.util.Base64.getDecoder().decode(stored.removePrefix(PREFIX))
            WatchList.deserialize(String(decoded, Charsets.UTF_8))
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/** Saate ait ayar anahtarı: önek + ad. Öneksiz kayıtta anahtar v0.11 ile aynıdır. */
object PrefKeys {
    fun key(ns: String, name: String): String = ns + name
}

/**
 * v0.11'den göç. Eski "ls05" ayar dosyasına hiçbir şey yazılmaz; yalnızca okunur.
 */
object RegistryMigration {

    enum class Source {
        /** Kayıt vardı ve okundu. */
        LOADED,

        /** İlk açılış: eski ayarlardan kuruldu (eski LS05 ya da boş liste). */
        MIGRATED,

        /** Kayıt bozuktu: eski ayarlardan yeniden kuruldu. */
        REBUILT,
    }

    /** Eski ayarlarda adres ya da kimlik varsa tek kayıtlık liste, yoksa boş liste. */
    fun fromLegacy(legacyAddress: String?, legacyUserId: String?, now: Long): WatchList =
        if (!legacyAddress.isNullOrEmpty() || !legacyUserId.isNullOrBlank()) {
            WatchList(listOf(WatchRecord(WatchList.LEGACY_ID, WatchType.LS05, "LS05", "", now)), WatchList.LEGACY_ID, 2)
        } else {
            WatchList.EMPTY
        }

    fun load(stored: String?, legacyAddress: String?, legacyUserId: String?, now: Long): Pair<WatchList, Source> {
        if (stored == null) return fromLegacy(legacyAddress, legacyUserId, now) to Source.MIGRATED
        val parsed = WatchList.deserialize(stored)
        return if (parsed != null) parsed to Source.LOADED else fromLegacy(legacyAddress, legacyUserId, now) to Source.REBUILT
    }
}

/**
 * Saat listesi kurtarma seçimi. Birincil kayıt geçerliyse her zaman onu kullanır.
 * Birincil veri yok veya bozuksa son geçerli ayrı kurtarma kopyasına döner.
 * Mevcut bozuk kayıtlar hiçbir durumda otomatik boş listeyle ezilmez.
 */
object RegistryRecovery {
    enum class Source { PRIMARY, BACKUP, MIGRATED, CORRUPT }
    data class Result(val list: WatchList, val source: Source)

    fun load(primary: String?, mirror: String?, legacyAddress: String?, legacyUserId: String?, now: Long): Result {
        WatchListStorage.parse(primary)?.let { return Result(it, Source.PRIMARY) }
        WatchListStorage.parse(mirror)?.let { return Result(it, Source.BACKUP) }
        if (primary == null && mirror == null) {
            return Result(RegistryMigration.fromLegacy(legacyAddress, legacyUserId, now), Source.MIGRATED)
        }
        // Geçersiz kayıt: eski LS05'ten arayüzde geçici görüntü oluştur, diske asla yazma.
        return Result(RegistryMigration.fromLegacy(legacyAddress, legacyUserId, now), Source.CORRUPT)
    }
}

/** WatchManager durumları (Saatlerim ekranı ve log için). */
enum class WatchState(val label: String) {
    NONE("Saat yok"),
    STOPPED("Durduruldu"),
    BT_OFF("Bluetooth kapalı"),
    CONNECTING("Bağlanıyor"),
    READY("Bağlı"),
    BUSY("Bağlı (kadran yükleniyor)"),
    WAIT_RECONNECT("Yeniden bağlanmayı bekliyor"),
    AUTH_BLOCKED("Doğrulama durdu"),
    SWITCHING("Saat değiştiriliyor"),
    UNSUPPORTED("Bu sürümde desteklenmiyor"),
}

/** Saat değiştirme kararları. */
object SwitchRules {

    enum class Decision {
        PROCEED,

        /** Zaten etkin saat. */
        SAME,

        /** Kadran yükleniyor; geçiş yapılmaz. */
        BUSY,

        /** Böyle bir saat yok. */
        UNKNOWN,
    }

    fun decide(list: WatchList, targetId: String, busy: Boolean): Decision = when {
        list.find(targetId) == null -> Decision.UNKNOWN
        list.activeId == targetId -> Decision.SAME
        busy -> Decision.BUSY
        else -> Decision.PROCEED
    }

    /**
     * Kopmada "saat uzaklaştı" uyarısı kurulur mu. Saat değiştirme, durdurma ve kullanıcı kopması uyarı kurmaz.
     * Hiç hazır olmamış bağlantının kopması da kurmaz (v0.11 kuralı).
     */
    fun armLostAlert(switching: Boolean, stopping: Boolean, userInitiated: Boolean, hadLink: Boolean): Boolean =
        !switching && !stopping && !userInitiated && hadLink
}
