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

    @Volatile
    private var cached: WatchList? = null

    private fun p(c: Context): SharedPreferences = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun legacy(c: Context): SharedPreferences = c.getSharedPreferences("ls05", Context.MODE_PRIVATE)

    fun list(c: Context): WatchList {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val old = legacy(c)
            val (l, src) = RegistryMigration.load(
                p(c).getString(KEY, null),
                old.getString("address", null),
                old.getString("userId", null),
                System.currentTimeMillis(),
            )
            if (src != RegistryMigration.Source.LOADED) {
                p(c).edit().putString(KEY, l.serialize()).commit()
                when (src) {
                    RegistryMigration.Source.MIGRATED ->
                        if (l.watches.isNotEmpty()) WatchHub.log("Saat kaydı oluşturuldu: mevcut LS05 ayarları olduğu gibi kullanılıyor.")
                    else -> WatchHub.log("Saat kaydı okunamadı; eski LS05 ayarlarından yeniden kuruldu.")
                }
                if (l.activeId != null) SwitchLog.record(c, l.activeId)
            }
            cached = l
            return l
        }
    }

    private fun save(c: Context, l: WatchList) {
        synchronized(this) {
            p(c).edit().putString(KEY, l.serialize()).commit()
            cached = l
        }
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
        save(c, l)
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
        save(c, l.remove(id))
        if (!rec.legacy && rec.ns.isNotEmpty()) {
            val prefs = legacy(c)
            val e = prefs.edit()
            for (k in prefs.all.keys) if (k.startsWith(rec.ns)) e.remove(k)
            e.apply()
        }
        return true
    }

    /** Etkin saati değiştirir (yalnızca kayıt). Bağlantıyı WatchService.switchTo yönetir. */
    fun setActive(c: Context, id: String?) = save(c, list(c).withActive(id))

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
