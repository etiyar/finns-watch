package com.che.ls05

import android.content.Context
import android.content.SharedPreferences
import com.che.ls05.protocol.AppTypeMap
import com.che.ls05.protocol.LastSeenRules
import com.che.ls05.protocol.SeenFix
import com.che.ls05.protocol.UteCommand

/** Telefonda saklanan ayarlar. Kimlik yalnızca burada tutulur; depoya ve loga girmez. */
object Prefs {
    private fun p(c: Context): SharedPreferences = c.getSharedPreferences("ls05", Context.MODE_PRIVATE)

    /**
     * Saate ait anahtar: etkin saatin öneki + ad. Göç edilen LS05'te önek boştur (v0.11 anahtarları).
     * Saate ait olanlar: adres, kimlik, profil onayı, son eşitleme, alarm notu/görünürlüğü/hatırlatma günü,
     * kadran, kopma zamanı, son görülen yer. Diğerleri telefona ait ve ortaktır.
     */
    private fun k(c: Context, name: String): String = WatchRegistry.ns(c) + name

    fun userIdText(c: Context, ns: String = WatchRegistry.ns(c)): String = p(c).getString(ns + "userId", "") ?: ""
    fun setUserIdText(c: Context, value: String) = p(c).edit().putString(k(c, "userId"), value.trim()).apply()
    fun userIdBytes(c: Context): ByteArray? = UteCommand.parseUserId(userIdText(c))

    fun address(c: Context, ns: String = WatchRegistry.ns(c)): String? = p(c).getString(ns + "address", null)
    fun setAddress(c: Context, value: String) = p(c).edit().putString(k(c, "address"), value).apply()

    /** Kullanıcı "Durdur" demediyse servis açılışta ve güncellemeden sonra yeniden başlar. */
    fun serviceEnabled(c: Context): Boolean = p(c).getBoolean("serviceEnabled", false)
    fun setServiceEnabled(c: Context, value: Boolean) = p(c).edit().putBoolean("serviceEnabled", value).apply()

    fun forwardingEnabled(c: Context): Boolean = p(c).getBoolean("forwarding", true)
    fun setForwardingEnabled(c: Context, value: Boolean) = p(c).edit().putBoolean("forwarding", value).apply()

    fun vibrate(c: Context): Boolean = p(c).getBoolean("vibrate", true)
    fun setVibrate(c: Context, value: Boolean) = p(c).edit().putBoolean("vibrate", value).apply()

    /** Varsayılan kapalı; saat eşitleme komutu cihazda doğrulandı, kullanıcı açar. */
    fun autoTime(c: Context): Boolean = p(c).getBoolean("autoTime", false)
    fun setAutoTime(c: Context, value: Boolean) = p(c).edit().putBoolean("autoTime", value).apply()

    fun appEnabled(c: Context, pkg: String): Boolean = p(c).getBoolean("app:$pkg", pkg in AppTypeMap.defaultOn)
    fun setAppEnabled(c: Context, pkg: String, value: Boolean) = p(c).edit().putBoolean("app:$pkg", value).apply()

    fun seenApps(c: Context): Set<String> = p(c).getStringSet("seenApps", emptySet())?.toSet() ?: emptySet()
    fun addSeenApp(c: Context, pkg: String) {
        val current = seenApps(c)
        if (pkg !in current) p(c).edit().putStringSet("seenApps", current + pkg).apply()
    }

    /** Uygulamadan son bildirimin geldiği zaman (ms); 0 = hiç. */
    fun lastNotified(c: Context, pkg: String): Long = p(c).getLong("last:$pkg", 0L)
    fun setLastNotified(c: Context, pkg: String, at: Long) = p(c).edit().putLong("last:$pkg", at).apply()

    // Günlük adım hedefi v0.5'ten beri profilde (SettingsStore.profileDraft). Bugün ekranı ve saat aynı değeri kullanır.

    /** Gelen arama saate iletilsin mi (varsayılan açık). */
    fun incomingCalls(c: Context): Boolean = p(c).getBoolean("incomingCalls", true)
    fun setIncomingCalls(c: Context, value: Boolean) = p(c).edit().putBoolean("incomingCalls", value).apply()

    /** Cevapsız arama saate iletilsin mi (varsayılan açık). */
    fun rejectFromWatch(c: Context): Boolean = p(c).getBoolean("rejectFromWatch", false)
    fun setRejectFromWatch(c: Context, value: Boolean) = p(c).edit().putBoolean("rejectFromWatch", value).apply()

    fun missedCalls(c: Context): Boolean = p(c).getBoolean("missedCalls", true)
    fun setMissedCalls(c: Context, value: Boolean) = p(c).edit().putBoolean("missedCalls", value).apply()

    /** Saatin müzik, ses ve telefonu bul kumandası uygulansın mı (varsayılan açık). */
    fun remoteControl(c: Context): Boolean = p(c).getBoolean("remoteControl", true)
    fun setRemoteControl(c: Context, value: Boolean) = p(c).edit().putBoolean("remoteControl", value).apply()

    /** Profil taslağı (ProfileSetting metni). Kişisel veri: yalnızca bu dosyada, loga girmez. */
    fun profileDraftText(c: Context): String? = p(c).getString("profileDraft", null)
    fun setProfileDraftText(c: Context, value: String) = p(c).edit().putString("profileDraft", value).apply()

    /** Saat profil paketini en az bir kez onayladı mı. O zamana kadar profil yalnızca elle gönderilir. */
    fun profileVerified(c: Context): Boolean = p(c).getBoolean(k(c, "profileVerified"), false)
    fun setProfileVerified(c: Context, value: Boolean) = p(c).edit().putBoolean(k(c, "profileVerified"), value).apply()

    /** Son başarılı adım geçmişi okuması (ms); 0 = hiç. */
    /** Band 8 deneme alarmının son kurulan saati ("Test alarmlarını temizle" ve düzenle/sil için). */
    fun band8TestAlarm(c: Context): Pair<Int, Int>? {
        val v = p(c).getInt("band8TestAlarm", -1)
        return if (v < 0) null else (v / 60) to (v % 60)
    }

    fun setBand8TestAlarm(c: Context, hour: Int, minute: Int) =
        p(c).edit().putInt("band8TestAlarm", hour * 60 + minute).apply()

    fun lastSync(c: Context): Long = p(c).getLong(k(c, "lastSync"), 0L)
    fun setLastSync(c: Context, at: Long) = p(c).edit().putLong(k(c, "lastSync"), at).apply()

    /** Tema seçimi: sistem, koyu ya da açık. */
    const val THEME_SYSTEM = 0
    const val THEME_DARK = 1
    const val THEME_LIGHT = 2
    fun themeMode(c: Context): Int = p(c).getInt("themeMode", THEME_SYSTEM).coerceIn(THEME_SYSTEM, THEME_LIGHT)
    fun setThemeMode(c: Context, value: Int) = p(c).edit().putInt("themeMode", value).apply()

    /** Alarm hatırlatma notu (en çok 40 karakter). Yalnızca telefonda; saate ve loga gitmez. */
    fun alarmNote(c: Context, slot: Int): String = p(c).getString(k(c, "alarmNote$slot"), "") ?: ""
    fun setAlarmNote(c: Context, slot: Int, value: String) = p(c).edit().putString(k(c, "alarmNote$slot"), value).apply()

    /** Bu yuvanın notunun en son gönderildiği yerel gün (ör. 2026-11-14). Aynı gün tekrarını engeller. */
    fun reminderSentDay(c: Context, slot: Int): String = p(c).getString(k(c, "reminderDay$slot"), "") ?: ""
    fun setReminderSentDay(c: Context, slot: Int, day: String) = p(c).edit().putString(k(c, "reminderDay$slot"), day).apply()

    /** Alarm yuvası Saat ekranında görünsün mü (v0.11: kurulan alarmlar görünür, silinen gizlenir). */
    fun alarmVisible(c: Context, slot: Int): Boolean = p(c).getBoolean(k(c, "alarmVisible$slot"), false)
    fun setAlarmVisible(c: Context, slot: Int, value: Boolean) = p(c).edit().putBoolean(k(c, "alarmVisible$slot"), value).apply()

    /** v0.11 yuva göçü yapıldı mı (bir kez: ayarı olan yuvalar görünür yapılır). */
    fun alarmSlotsMigrated(c: Context): Boolean = p(c).getBoolean(k(c, "alarmSlotsV11"), false)
    fun setAlarmSlotsMigrated(c: Context) = p(c).edit().putBoolean(k(c, "alarmSlotsV11"), true).apply()

    /**
     * v0.10.1 alarm deneyinden silinmeden kalmış yuva (0: yok). Okununca kayıt silinir.
     * Deney kaldırıldı; bu yalnızca güncelleme anında yarım kalan denemeyi temizlemek için.
     */
    fun takeLegacyExperimentSlot(c: Context): Int {
        val slot = p(c).getInt(k(c, "alarmExperimentSlot"), 0)
        if (slot != 0) p(c).edit().remove(k(c, "alarmExperimentSlot")).apply()
        return slot
    }

    // Hava durumu: şehir yalnızca telefonda; loga yazılmaz.
    fun weatherEnabled(c: Context): Boolean = p(c).getBoolean("weatherOn", false)
    fun setWeatherEnabled(c: Context, on: Boolean) = p(c).edit().putBoolean("weatherOn", on).apply()
    fun weatherCityName(c: Context): String = p(c).getString("weatherCity", "") ?: ""
    fun weatherLat(c: Context): Double? = p(c).getString("weatherLat", null)?.toDoubleOrNull()
    fun weatherLon(c: Context): Double? = p(c).getString("weatherLon", null)?.toDoubleOrNull()
    fun setWeatherCity(c: Context, name: String, lat: Double, lon: Double) = p(c).edit()
        .putString("weatherCity", name).putString("weatherLat", lat.toString()).putString("weatherLon", lon.toString())
        .remove("weatherCache").putLong("weatherAt", 0L).apply()
    fun weatherCache(c: Context): String? = p(c).getString("weatherCache", null)
    fun weatherFetchedAt(c: Context): Long = p(c).getLong("weatherAt", 0L)
    fun setWeatherCache(c: Context, json: String, at: Long) = p(c).edit().putString("weatherCache", json).putLong("weatherAt", at).apply()
    fun setWeatherSentAt(c: Context, at: Long) = p(c).edit().putLong("weatherSentAt", at).apply()

    /** Saate en son başarıyla yüklenen kadranın kimliği (Kadranlarım). */
    fun currentFace(c: Context): String = p(c).getString(k(c, "currentFace"), "") ?: ""
    fun setCurrentFace(c: Context, id: String) = p(c).edit().putString(k(c, "currentFace"), id).apply()

    /** Bildirim metninde ı/İ sadeleştirilsin mi (varsayılan kapalı). Küme: TurkishFold.DEFAULT. */
    fun foldTurkish(c: Context): Boolean = p(c).getBoolean("foldTurkish", false)
    fun setFoldTurkish(c: Context, value: Boolean) = p(c).edit().putBoolean("foldTurkish", value).apply()

    // "Saat uzaklaştı" uyarısı: kopma zamanı ve ayarlar. Konum yalnızca "son görülen yer" açıksa (aşağıda).
    fun lostAlert(c: Context): Boolean = p(c).getBoolean("lostAlert", false)
    fun setLostAlert(c: Context, value: Boolean) = p(c).edit().putBoolean("lostAlert", value).apply()

    /** Saatin rahatsız etme saatlerinde uyarma (varsayılan açık; o ayar kapalıysa etkisi yok). */
    fun lostQuiet(c: Context): Boolean = p(c).getBoolean("lostQuiet", true)
    fun setLostQuiet(c: Context, value: Boolean) = p(c).edit().putBoolean("lostQuiet", value).apply()

    /** "1 saat sustur" bitiş zamanı (ms); 0 = susturulmadı. */
    fun lostSnoozeUntil(c: Context): Long = p(c).getLong("lostSnoozeUntil", 0L)
    fun setLostSnoozeUntil(c: Context, at: Long) = p(c).edit().putLong("lostSnoozeUntil", at).apply()

    /** Bekleyen kopmanın zamanı (ms); 0 = bekleyen yok. */
    fun lostSince(c: Context): Long = p(c).getLong(k(c, "lostSince"), 0L)
    fun setLostSince(c: Context, at: Long) = p(c).edit().putLong(k(c, "lostSince"), at).apply()

    // Son görülen yer: yalnızca son kayıt tutulur, geçmiş yok, loga yazılmaz.
    fun lastSeenEnabled(c: Context): Boolean = p(c).getBoolean("lastSeenOn", false)
    fun setLastSeenEnabled(c: Context, value: Boolean) {
        val e = p(c).edit().putBoolean("lastSeenOn", value)
        // Kapatılınca bütün saatlerin son görülen yeri silinir.
        if (!value) WatchRegistry.allNamespaces(c).forEach { e.remove(it + "lastSeen") }
        e.apply()
    }
    fun lastSeen(c: Context): SeenFix? = LastSeenRules.decode(p(c).getString(k(c, "lastSeen"), null))
    fun setLastSeen(c: Context, f: SeenFix?) =
        if (f == null) p(c).edit().remove(k(c, "lastSeen")).apply() else p(c).edit().putString(k(c, "lastSeen"), LastSeenRules.encode(f)).apply()


    // Alarm ve rahatsız etme ayarları SettingsStore'a taşındı. Eski "alarm1".."alarm3" ve "dnd"
    // metinleri aynı dosyada kalır; SettingsStore ilk açılışta bunları okur.
}
