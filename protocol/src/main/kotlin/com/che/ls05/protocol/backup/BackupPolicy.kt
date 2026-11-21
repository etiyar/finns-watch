package com.che.ls05.protocol.backup

/**
 * Yedeğe neyin girdiği. Sağlık kayıtları, log ve geçici durum yedeğe girmez.
 * Saate ait anahtarlar "d.<kimlik>." önekini alır; göç edilen LS05'in öneki boştur.
 */
object BackupPolicy {

    const val PREFS_MAIN = "ls05"
    const val PREFS_WATCHES = "watches"
    val PREF_FILES = listOf(PREFS_MAIN, PREFS_WATCHES)

    /** "watches" dosyasından yalnızca saat listesi alınır; şifreli anahtar zarfları cihaza bağlıdır. */
    const val WATCH_LIST_KEY = "list"

    /** Kadran klasörleri ve izinli dosya adları. */
    val FILE_DIRS = listOf("faces", "band8faces")
    private val FILE_NAME = mapOf(
        "faces" to Regex("^[0-9]{1,20}\\.(face|name|tbwf)$"),
        "band8faces" to Regex("^[0-9]{1,20}\\.(bin|png|txt)$"),
    )

    /** Telefona ait geçici durum: önbellek, sayaç, zamanlama kayıtları. Yedeğe girmez, geri yüklemede korunur. */
    private val RUNTIME_EXACT = setOf(
        "serviceEnabled", "repeatState", "weatherCache", "weatherAt", "weatherSentAt", "lostSnoozeUntil",
        "calendarScheduled", "calendarSent", "scheduledCustom", "band8NavPrevFace",
    )
    private val RUNTIME_PREFIX = listOf("customSent:", "last:")

    /** Saate ait geçici durum ve konum (son görülen yer). */
    private val WATCH_RUNTIME_EXACT = setOf("lostSince", "lastSeen", "lastSync", "alarmExperimentSlot")
    private val WATCH_RUNTIME_PREFIX = listOf("reminderDay")

    /** Kimlik bilgisi: yalnızca "Kimlik anahtarlarını dahil et" açıkken ve şifreli yedekte. */
    private val CREDENTIAL = setOf("userId")

    private val NS = Regex("^d\\.[A-Za-z0-9]{1,16}\\.")

    /** Önek çıkarılmış ad. */
    fun baseName(key: String): String = NS.find(key)?.let { key.substring(it.range.last + 1) } ?: key

    fun isCredential(key: String): Boolean = baseName(key) in CREDENTIAL

    fun isRuntime(key: String): Boolean {
        if (key in RUNTIME_EXACT || RUNTIME_PREFIX.any { key.startsWith(it) }) return true
        val b = baseName(key)
        return b in WATCH_RUNTIME_EXACT || WATCH_RUNTIME_PREFIX.any { b.startsWith(it) }
    }

    /** Yedeğe yazılacak ana ayarlar. */
    fun filterMain(all: Map<String, PrefValue>, includeKeys: Boolean): Map<String, PrefValue> =
        all.filterKeys { !isRuntime(it) && (includeKeys || !isCredential(it)) }

    fun filterWatches(all: Map<String, PrefValue>): Map<String, PrefValue> =
        all.filterKeys { it == WATCH_LIST_KEY }

    /** Göreli yol izinli mi (ör. faces/123.face). Klasör kaçışı ve bilinmeyen ad reddedilir. */
    fun isAllowedFile(path: String): Boolean {
        val slash = path.indexOf('/')
        if (slash <= 0 || path.indexOf('/', slash + 1) >= 0) return false
        val rx = FILE_NAME[path.substring(0, slash)] ?: return false
        return rx.matches(path.substring(slash + 1))
    }

    /**
     * Geri yüklenecek ana ayarlar: yedekteki her şey, artı mevcut cihazdan yedeğin hiç taşımadığı anahtarlar
     * (geçici durum; yedekte kimlik yoksa mevcut kimlikler). Yedekte olmayan diğer ayarlar silinir.
     */
    fun mergeMain(current: Map<String, PrefValue>, backup: Map<String, PrefValue>, backupHasKeys: Boolean): Map<String, PrefValue> {
        val out = LinkedHashMap<String, PrefValue>()
        for ((k, v) in current) {
            if (isRuntime(k) || (!backupHasKeys && isCredential(k))) out[k] = v
        }
        for ((k, v) in backup) {
            if (isRuntime(k)) continue
            if (!backupHasKeys && isCredential(k)) continue
            out[k] = v
        }
        return out
    }
}
