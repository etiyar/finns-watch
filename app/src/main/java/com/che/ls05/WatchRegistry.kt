package com.che.ls05

import android.content.Context
import android.content.SharedPreferences
import com.che.ls05.protocol.Capabilities
import com.che.ls05.protocol.RegistryRecovery
import com.che.ls05.protocol.WatchList
import com.che.ls05.protocol.WatchListStorage
import com.che.ls05.protocol.WatchRecord
import com.che.ls05.protocol.WatchType

/**
 * Kayıtlı saatler ve etkin saat ("watches" ayar dosyası). v0.11'in "ls05" dosyasına göç sırasında hiçbir şey yazılmaz.
 * Göç edilen LS05'in ayar öneki boştur: anahtarları v0.11 ile birebir aynıdır (bkz. Prefs).
 * Adres, kimlik ve anahtar loga yazılmaz.
 */
object WatchRegistry {

    private const val FILE = "watches"
    private const val KEY = "list"
    private const val BACKUP_FILE = "watches_recovery"
    private const val BACKUP_KEY = "last_good_list"

    @Volatile
    private var cached: WatchList? = null
    private var loadSource = RegistryRecovery.Source.MIGRATED

    private fun p(c: Context): SharedPreferences = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun legacy(c: Context): SharedPreferences = c.getSharedPreferences("ls05", Context.MODE_PRIVATE)
    private fun backup(c: Context): SharedPreferences = c.getSharedPreferences(BACKUP_FILE, Context.MODE_PRIVATE)

    fun list(c: Context): WatchList {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val old = legacy(c)
            val primary = p(c).getString(KEY, null)
            val fallback = backup(c).getString(BACKUP_KEY, null)
            val result = RegistryRecovery.load(
                primary, fallback,
                old.getString("address", null),
                old.getString("userId", null),
                System.currentTimeMillis(),
            )
            val l = result.list
            loadSource = result.source
            when (result.source) {
                RegistryRecovery.Source.PRIMARY -> Unit
                RegistryRecovery.Source.BACKUP -> {
                    WatchHub.log("Saat listesi yerel kurtarma kopyasından geri alındı.")
                    // Bozuk kaydı üzerine yazmadan önce özel cihaz alanında koru.
                    if (primary != null) p(c).edit().putString("invalid_list_backup", primary).commit()
                    if (!p(c).edit().putString(KEY, WatchListStorage.encode(l)).commit())
                        WatchHub.log("Kurtarılan saat listesi diske yazılamadı.")
                }
                RegistryRecovery.Source.MIGRATED -> {
                    // Eski LS05 ayarından ilk kayıt. Yedeği önceden yaz, sonra asıl kaydı.
                    persist(c, l)
                    if (l.watches.isNotEmpty()) WatchHub.log("Önceki LS05 kaydı içe aktarıldı.")
                }
                RegistryRecovery.Source.CORRUPT -> {
                    // Bozuk iki kopyadan boş/LS05 listesini sessizce kaydetme.
                    WatchHub.log("Saat kayıtları okunamadı; kurtarma için eski veriler korunuyor.")
                }
            }
            // İlk başarılı okumadan sonra yedek eksikse, mevcut geçerli veriyi kaydet.
            if (result.source == RegistryRecovery.Source.PRIMARY &&
                WatchListStorage.parse(fallback) == null) {
                backup(c).edit().putString(BACKUP_KEY, WatchListStorage.encode(l)).commit()
            }
            cached = l
            return l
        }
    }

    /** Yedek ilk yazılır; diskteki son iyi liste bir hata halinde geri alınabilir. */
    private fun persist(c: Context, l: WatchList): Boolean {
        val content = WatchListStorage.encode(l)
        if (WatchListStorage.parse(content) != l) {
            WatchHub.log("Saat listesi oluşturuldu ancak yeniden ayrıştırılamadı; kayıt değiştirilmedi.")
            return false
        }
        val backupWritten = backup(c).edit().putString(BACKUP_KEY, content).commit()
        if (!backupWritten) {
            WatchHub.log("Saat kurtarma kopyası yazılamadı; kayıt değiştirilmedi.")
            return false
        }
        val primaryWritten = p(c).edit().putString(KEY, content).commit()
        if (!primaryWritten) WatchHub.log("Saat listesi yazılamadı; kurtarma kopyası korundu.")
        if (!primaryWritten) return false
        val primaryReadback = WatchListStorage.parse(p(c).getString(KEY, null)) == l
        val backupReadback = WatchListStorage.parse(backup(c).getString(BACKUP_KEY, null)) == l
        if (!primaryReadback || !backupReadback) {
            WatchHub.log("Saat kaydı yazma kontrolü başarısız: ana=$primaryReadback, yedek=$backupReadback.")
            return false
        }
        return true
    }

    private fun save(c: Context, l: WatchList): Boolean = synchronized(this) {
        if (persist(c, l)) {
            cached = l
            true
        } else {
            false
        }
    }

    fun diagnosticSummary(c: Context): String {
        val l = list(c)
        val active = l.active
        val primaryStored = p(c).getString(KEY, null)
        val backupStored = backup(c).getString(BACKUP_KEY, null)
        val primaryValid = WatchListStorage.parse(primaryStored) != null
        val backupValid = WatchListStorage.parse(backupStored) != null
        val hasAddress = active != null && Prefs.address(c, active.ns) != null
        val bandKey = if (active?.type == WatchType.BAND8) SecretStore.has(c, active.id) else null
        return "Saat kaydı: kaynak=${loadSource.name}, adet=${l.watches.size}, etkin tür=${active?.type?.name ?: "yok"}, " +
            "adres kayıtlı=$hasAddress, Band 8 anahtar zarfı=${bandKey ?: "uygulanmaz"}, " +
            "ana kayıt=${if (primaryValid) "geçerli" else "yok/bozuk"} (${primaryStored?.length ?: 0}), " +
            "yedek=${if (backupValid) "geçerli" else "yok/bozuk"} (${backupStored?.length ?: 0})"
    }

    fun active(c: Context): WatchRecord? = list(c).active

    fun needsRecovery(c: Context): Boolean {
        list(c)
        return loadSource == RegistryRecovery.Source.CORRUPT
    }

    /** Restore only devices backed by both an address and a stored Band 8 key envelope.
     * Keep the unreadable registry bytes before replacing either copy. No key is decrypted here.
     */
    fun recoverLocalDevices(c: Context): Int = synchronized(this) {
        val current = list(c)
        if (loadSource != RegistryRecovery.Source.CORRUPT) return@synchronized 0
        val settings = legacy(c).all
        val bandIds = settings.keys.mapNotNull { key ->
            Regex("^d\\.(w[0-9]+)\\.address$").matchEntire(key)?.groupValues?.get(1)
        }.distinct().sortedBy { it.drop(1).toIntOrNull() ?: Int.MAX_VALUE }
            .filter { id ->
                val address = settings["d.$id.address"] as? String
                address != null && address.isNotBlank() && SecretStore.has(c, id)
            }
        if (bandIds.isEmpty()) return@synchronized 0
        val recovery = backup(c)
        val primaryRaw = p(c).getString(KEY, null)
        val backupRaw = recovery.getString(BACKUP_KEY, null)
        val archived = recovery.edit()
        if (primaryRaw != null) archived.putString("invalid_primary_before_recovery", primaryRaw)
        if (backupRaw != null) archived.putString("invalid_backup_before_recovery", backupRaw)
        if (!archived.commit()) return@synchronized 0
        val restored = bandIds.map { id ->
            WatchRecord(id, WatchType.BAND8, WatchType.BAND8.label, "d.$id.", System.currentTimeMillis())
        }
        val next = maxOf(current.next, (bandIds.mapNotNull { it.drop(1).toIntOrNull() }.maxOrNull() ?: 1) + 1)
        val updated = current.copy(watches = current.watches + restored, next = next)
        if (!persist(c, updated)) return@synchronized 0
        cached = updated
        loadSource = RegistryRecovery.Source.PRIMARY
        WatchHub.log("Saat kurtarma sonrası: ${diagnosticSummary(c)}")
        restored.size
    }

    /** Etkin saatin ayar öneki. Etkin saat yoksa v0.11 anahtarları (önek boş). */
    fun ns(c: Context): String = active(c)?.ns ?: ""

    fun caps(c: Context): Capabilities = Capabilities.of(active(c)?.type)

    /** Bağlanılacak bir saat var mı: etkin saat seçili ve adresi kayıtlı. */
    fun hasActive(c: Context): Boolean = active(c) != null && Prefs.address(c) != null

    /** Yeni saat ekler (etkin yapmaz). */
    fun add(c: Context, type: WatchType, name: String): WatchRecord {
        val current = list(c)
        check(loadSource != RegistryRecovery.Source.CORRUPT) { "Saat kayıtları okunamadı; mevcut veriler korunuyor." }
        val (l, rec) = current.add(type, name, System.currentTimeMillis())
        if (!save(c, l)) throw IllegalStateException("Saat kaydedilemedi; telefon depolamasını kontrol et.")
        // İkinci LS05 aynı Haylou kimliğini kullanır; kimlik yeni saate kopyalanır (yalnızca telefonda).
        if (type == WatchType.LS05 && !rec.legacy) {
            val id = legacy(c).getString("userId", null)
            if (!id.isNullOrEmpty()) legacy(c).edit().putString(rec.ns + "userId", id).apply()
        }
        return rec
    }

    fun rename(c: Context, id: String, name: String) = save(c, list(c).rename(id, name))

    /**
     * Saati siler (etkin saat silinemez). Önekli saatin ayarları ve şifreli anahtarı silinir.
     * Göç edilen LS05'in (önek boş) ayarlarına dokunulmaz; eski sürüme dönülürse yine çalışır.
     * Sağlık kayıtları silinmez.
     */
    fun remove(c: Context, id: String): Boolean {
        val l = list(c)
        val rec = l.find(id) ?: return false
        if (id == l.activeId) return false
        if (!save(c, l.remove(id))) return false
        if (!rec.legacy && rec.ns.isNotEmpty()) {
            val prefs = legacy(c)
            val e = prefs.edit()
            for (k in prefs.all.keys) if (k.startsWith(rec.ns)) e.remove(k)
            e.apply()
            SettingsStore.forget(rec.ns)
        }
        SecretStore.remove(c, id)
        return true
    }

    /** Etkin saati değiştirir (yalnızca kayıt). Bağlantıyı WatchService.switchTo yönetir. */
    fun setActive(c: Context, id: String?): Boolean {
        val saved = save(c, list(c).withActive(id))
        WatchHub.log("Saat seçimi ${if (saved) "kaydedildi" else "kaydedilemedi"}: ${diagnosticSummary(c)}")
        return saved
    }

    /**
     * Kurulumdan yeni LS05 bağlanacak ama etkin saat yok (ilk kurulum): öneksiz LS05 kaydı etkin yapılır.
     * Böylece v0.11'deki gibi tek adımda bağlanılır.
     */
    fun ensureActive(c: Context) {
        val l = list(c)
        if (l.active != null) return
        val existing = l.watches.firstOrNull { it.legacy } ?: l.watches.firstOrNull()
        if (existing != null) {
            save(c, l.withActive(existing.id))
            SwitchLog.record(c, existing.id)
            return
        }
        val (l2, rec) = l.add(WatchType.LS05, "", System.currentTimeMillis())
        save(c, l2.withActive(rec.id))
        SwitchLog.record(c, rec.id)
    }

    /** Bütün saatlerin önekleri (ayarı tüm saatlerde silmek için). */
    fun allNamespaces(c: Context): Set<String> = list(c).watches.map { it.ns }.toSet() + ""
}
