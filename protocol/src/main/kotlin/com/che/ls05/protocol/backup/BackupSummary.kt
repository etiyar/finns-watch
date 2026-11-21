package com.che.ls05.protocol.backup

import com.che.ls05.protocol.CustomReminders
import com.che.ls05.protocol.NotifyRuleSet
import com.che.ls05.protocol.WatchListStorage
import com.che.ls05.protocol.WatchType

/** Yedeğin içerik özeti: onay ekranı ve "son yedek" satırı için. Gizli değer içermez. */
data class BackupSummary(
    val createdAt: Long,
    val appVersion: String,
    val watchNames: List<String>,
    val notifyRules: Int,
    val reminders: Int,
    val alarmNotes: Int,
    val ls05Faces: Int,
    val band8Faces: Int,
    val settings: Int,
    val bandKeys: Int,
    val ls05Ids: Int,
    val includesKeys: Boolean,
) {
    /** Türkçe satırlar (tarih çağıran tarafından eklenir). */
    fun lines(): List<String> {
        val out = ArrayList<String>()
        out += if (watchNames.isEmpty()) "Saatlerim: kayıt yok" else "Saatlerim: ${watchNames.joinToString(", ")}"
        out += "Bildirim kuralları: $notifyRules"
        out += "Hatırlatıcılar: $reminders"
        out += "Alarm notları: $alarmNotes"
        out += "Kadranlar: LS05 $ls05Faces, Band 8 $band8Faces"
        out += "Ayarlar: $settings kayıt"
        out += if (includesKeys) "Kimlik anahtarları: Band 8 $bandKeys, LS05 $ls05Ids (şifreli)" else "Kimlik anahtarları: yok"
        return out
    }

    /** Tek satırlık kısa özet. */
    fun short(): String =
        "${watchNames.size} saat, $notifyRules kural, $reminders hatırlatıcı, ${ls05Faces + band8Faces} kadran" +
            if (includesKeys) ", anahtarlar dahil" else ""

    companion object {
        fun of(c: BackupContents): BackupSummary {
            val main = c.prefs[BackupPolicy.PREFS_MAIN].orEmpty()
            val listText = (c.prefs[BackupPolicy.PREFS_WATCHES]?.get(BackupPolicy.WATCH_LIST_KEY) as? PrefValue.Str)?.v
            val list = WatchListStorage.parse(listText)
            val names = list?.watches?.map { w ->
                val label = if (w.type == WatchType.BAND8) "Band 8" else "LS05"
                if (w.name.isBlank() || w.name == label) label else "${w.name} ($label)"
            }.orEmpty()
            val rules = (main["notifyRules"] as? PrefValue.Str)?.v?.let { NotifyRuleSet.decode(it).rules.size } ?: 0
            val reminders = (main["customReminders"] as? PrefValue.Str)?.v?.let { CustomReminders.decode(it).size } ?: 0
            val notes = main.count { (k, v) -> BackupPolicy.baseName(k).startsWith("alarmNote") && v is PrefValue.Str && v.v.isNotBlank() }
            val ls05Ids = main.count { (k, v) -> BackupPolicy.isCredential(k) && v is PrefValue.Str && v.v.isNotBlank() }
            fun faces(dir: String, ext: String) = c.files.keys.count { it.startsWith("$dir/") && it.endsWith(ext) }
            return BackupSummary(
                createdAt = c.createdAt,
                appVersion = c.appVersion,
                watchNames = names,
                notifyRules = rules,
                reminders = reminders,
                alarmNotes = notes,
                ls05Faces = faces("faces", ".face"),
                band8Faces = faces("band8faces", ".bin"),
                settings = main.size,
                bandKeys = c.secrets.size,
                ls05Ids = if (c.includesKeys) ls05Ids else 0,
                includesKeys = c.includesKeys,
            )
        }
    }
}
