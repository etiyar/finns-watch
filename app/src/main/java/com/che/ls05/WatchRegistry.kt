package com.che.ls05

import android.content.Context
import android.content.SharedPreferences
import com.che.ls05.protocol.Capabilities
import com.che.ls05.protocol.RegistryMigration
import com.che.ls05.protocol.WatchList
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
            when (result.source) {
                RegistryRecovery.Source.PRIMARY -> Unit
                RegistryRecovery.Source.BACKUP -> {
                    WatchHub.log("Saat listesi yerel kurtarma kopyasından geri alındı.")
                    // Bozuk kaydı üzerine yazmadan önce özel cihaz alanında koru.
                    if (primary != null) p(c).edit().putString("invalid_list_backup", primary).commit()
                    if (!p(c).edit().putString(KEY, l.serialize()).commit())
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
                WatchList.deserialize(fallback) == null) {
                backup(c).edit().putString(BACKUP_KEY, l.serialize()).commit()
            }
            cached = l
            return l
        }
    }

    /** Yedek ilk yazılır; diskteki son iyi liste bir hata halinde geri alınabilir. */
    private fun persist(c: Context, l: WatchList): Boolean {
        val content = l.serialize()
        val backupWritten = backup(c).edit().putString(BACKUP_KEY, content).commit()
        if (!backupWritten) {
            WatchHub.log("Saat kurtarma kopyası yazılamadı; kayıt değiştirilmedi.")
            return false
        }
        val primaryWritten = p(c).edit().putString(KEY, content).commit()
        if (!primaryWritten) WatchHub.log("Saat listesi yazılamadı; kurtarma kopyası korundu.")
        return primaryWritten
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
        val primaryValid = WatchList.deserialize(p(c).getString(KEY, null)) != null
        val backupValid = WatchList.deserialize(backup(c).getString(BACKUP_KEY, null)) != null
        return "Saat kaydı: adet=${l.watches.size}, etkin tür=${l.active?.type?.name ?: "yok"}, " +
            "ana kayıt=${if (primaryValid) "geçerli" else "yok/bozuk"}, " +
            "yedek=${if (backupValid) "geçerli" else "yok/bozuk"}"
    }

    fun active(c: Context): WatchRecord? = list(c).active

    /** Etkin saatin ayar öneki. Etkin saat yoksa v0.11 anahtarları (önek boş). */
    fun ns(c: Context): String = active(c)?.ns ?: ""

    fun caps(c: Context): Capabilities = Capabilities.of(active(c)?.type)

    /** Bağlanılacak bir saat var mı: etkin saat seçili ve adresi kayıtlı. */
    fun hasActive(c: Context): Boolean = active(c) != null && Prefs.address(c) != null

    /** Yeni saat ekler (etkin yapmaz). */
    fun add(c: Context, type: WatchType, name: String): WatchRecord {
        val (l, rec) = list(c).add(type, name, System.currentTimeMillis())
        if (!save(c, l)) throw IllegalStateException("Saat listesi kaydedilemedi.")
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
    fun setActive(c: Context, id: String?): Boolean = save(c, list(c).withActive(id))

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
