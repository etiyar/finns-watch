package com.che.ls05.protocol.backup

import java.util.Base64

/** SharedPreferences değeri. Yedekte türüyle saklanır; geri yüklemede aynı türle yazılır. */
sealed class PrefValue {
    data class Str(val v: String) : PrefValue()
    data class Int32(val v: Int) : PrefValue()
    data class Int64(val v: Long) : PrefValue()
    data class Bool(val v: Boolean) : PrefValue()
    data class Real(val v: Float) : PrefValue()
    data class StrSet(val v: Set<String>) : PrefValue()

    companion object {
        /** SharedPreferences.getAll() değerinden. Bilinmeyen tür null. */
        fun of(any: Any?): PrefValue? = when (any) {
            is String -> Str(any)
            is Int -> Int32(any)
            is Long -> Int64(any)
            is Boolean -> Bool(any)
            is Float -> Real(any)
            is Set<*> -> StrSet(any.filterIsInstance<String>().toSet())
            else -> null
        }
    }
}

/** Ayar dosyası metin biçimi. Satır: tür, sekme, anahtar (base64), sekme, değer (base64). */
object PrefCodec {
    private val enc = Base64.getEncoder()
    private val dec = Base64.getDecoder()

    private fun b(s: String): String = enc.encodeToString(s.toByteArray(Charsets.UTF_8))
    private fun u(s: String): String = String(dec.decode(s), Charsets.UTF_8)

    fun encode(map: Map<String, PrefValue>): String = buildString {
        for ((k, v) in map.toSortedMap()) {
            val (t, text) = when (v) {
                is PrefValue.Str -> "s" to v.v
                is PrefValue.Int32 -> "i" to v.v.toString()
                is PrefValue.Int64 -> "l" to v.v.toString()
                is PrefValue.Bool -> "b" to (if (v.v) "1" else "0")
                is PrefValue.Real -> "f" to v.v.toString()
                is PrefValue.StrSet -> "S" to v.v.sorted().joinToString("\n") { b(it) }
            }
            append(t).append('\t').append(b(k)).append('\t').append(b(text)).append('\n')
        }
    }

    /** Bozuk satırda BackupException. */
    fun decode(text: String): Map<String, PrefValue> {
        val out = LinkedHashMap<String, PrefValue>()
        for (line in text.split('\n')) {
            if (line.isEmpty()) continue
            val p = line.split('\t')
            if (p.size != 3) throw BackupException(BackupError.CORRUPT)
            try {
                val k = u(p[1])
                val t = u(p[2])
                out[k] = when (p[0]) {
                    "s" -> PrefValue.Str(t)
                    "i" -> PrefValue.Int32(t.toInt())
                    "l" -> PrefValue.Int64(t.toLong())
                    "b" -> PrefValue.Bool(t == "1")
                    "f" -> PrefValue.Real(t.toFloat())
                    "S" -> PrefValue.StrSet(if (t.isEmpty()) emptySet() else t.split('\n').map { u(it) }.toSet())
                    else -> throw BackupException(BackupError.CORRUPT)
                }
            } catch (e: IllegalArgumentException) {
                throw BackupException(BackupError.CORRUPT)
            }
        }
        return out
    }
}

/** Yedek hata türleri. Mesajlar Türkçe ve kısa; gizli değer içermez. */
enum class BackupError(val message: String) {
    NOT_BACKUP("Bu dosya bir Finn's Watch yedeği değil."),
    NEWER_VERSION("Yedek daha yeni bir sürümle alınmış. Önce uygulamayı güncelle."),
    CORRUPT("Yedek dosyası bozuk."),
    TOO_LARGE("Yedek dosyası çok büyük."),
    NEED_PASSWORD("Bu yedek parolayla şifreli."),
    WRONG_PASSWORD("Parola yanlış ya da dosya bozuk. Hiçbir şey değiştirilmedi."),
    WEAK_PASSWORD("Parola en az 8 karakter olmalı."),
    KEYS_NEED_PASSWORD("Kimlik anahtarları yalnızca şifreli yedeğe yazılır."),
}

class BackupException(val error: BackupError) : Exception(error.message)

/** Yedek içeriği. Kimlik anahtarları (secrets) yalnızca şifreli yedekte bulunur. */
class BackupContents(
    val createdAt: Long,
    val appVersion: String,
    /** Ayar dosyası adı → anahtar → değer. Yalnızca [BackupPolicy.PREF_FILES]. */
    val prefs: Map<String, Map<String, PrefValue>>,
    /** Göreli yol (ör. faces/123.face) → içerik. */
    val files: Map<String, ByteArray>,
    /** Saat kimliği → anahtar baytları. Çağıran kullandıktan sonra sıfırlamalı ([wipe]). */
    val secrets: Map<String, ByteArray>,
    /** LS05 kimlik metinleri (önek → kimlik) yedekte mi; [secrets] doluysa ya da LS05 kimliği varsa true. */
    val includesKeys: Boolean,
) {
    fun wipe() = secrets.values.forEach { it.fill(0) }
}
