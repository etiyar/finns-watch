package com.che.ls05

import android.os.Handler
import com.che.ls05.protocol.band8.B8Command
import com.che.ls05.protocol.band8.B8FaceBin
import com.che.ls05.protocol.band8.B8FaceFile
import com.che.ls05.protocol.band8.B8Type
import com.che.ls05.protocol.band8.B8Upload
import com.che.ls05.protocol.band8.B8Watchface

/**
 * Band 8 kadran yönetimi (deneysel). Her işlem Gelişmiş > "Band 8 kadran (deneysel)" düğmesiyle başlar.
 * Liste (4/0) kayıtta doğrulandı. Etkin yap (4/1), sil (4/2), kurulum (4/4 + tür 16 yükleme) güçlü kanıt,
 * cihazda denenmedi. Yüklemeden sonra kadran kendiliğinden etkin yapılmaz; kullanıcı listeden seçer.
 * Logda kadran kimliği yazılır (kişisel değil), ad yazılmaz.
 */
class Band8Faces(
    private val main: Handler,
    private val log: (String) -> Unit,
    private val send: (ByteArray, String) -> Boolean,
    private val upload: (Int, ByteArray, String, Band8Ble.UploadCallback) -> String?,
) {
    /** Kurulum isteği gönderildi, 4/4 yanıtı bekleniyor. */
    private var pendingInstall: Pair<String, ByteArray>? = null
    private var lastShownPercent = -1
    private val installTimeout = Runnable {
        if (pendingInstall != null) {
            pendingInstall = null
            setStatus("Band kurulum isteğine yanıt vermedi.")
        }
    }

    fun read(): String? {
        if (!send(B8Watchface.list(), "kadran listesi 4/0")) return "Band 8 hazır değil."
        setStatus("Liste okunuyor…")
        return null
    }

    fun setActive(id: String): String? {
        val f = faces?.firstOrNull { it.id == id } ?: return "Önce listeyi oku."
        if (f.active) return "Bu kadran zaten etkin."
        if (busy) return "Kadran yüklemesi sürüyor."
        if (!send(B8Watchface.set(id), "kadranı etkin yap 4/1")) return "Band 8 hazır değil."
        log("Band 8 kadran etkin yapma isteği: kimlik=$id")
        return null
    }

    fun delete(id: String): String? {
        val f = faces?.firstOrNull { it.id == id } ?: return "Önce listeyi oku."
        if (!B8Watchface.canDelete(f)) return "Bu kadran silinemez (yerleşik ya da etkin)."
        if (busy) return "Kadran yüklemesi sürüyor."
        if (!send(B8Watchface.delete(id), "kadran sil 4/2")) return "Band 8 hazır değil."
        log("Band 8 kadran silme isteği: kimlik=$id")
        return null
    }

    /** Kullanıcının seçip onayladığı .bin dosyası. */
    fun install(bytes: ByteArray): String? {
        val list = faces ?: return "Önce listeyi oku."
        if (busy) return "Kadran yüklemesi sürüyor."
        val ok = B8FaceFile.parse(bytes) as? B8FaceFile.Result.Ok ?: return "Geçerli bir Band 8 kadran dosyası değil."
        if (list.any { it.id == ok.id }) return "Bu kadran zaten bantta."
        // Kendi kadranımız yapı denetiminden geçmeden gönderilmez (belge BAND8_KADRAN_BICIMI 7).
        if (ok.id.startsWith(B8FaceBin.OWN_ID_PREFIX) && B8FaceBin.check(bytes).isNotEmpty()) return "Kendi kadranımız denetimden geçmedi."
        if (!send(B8Watchface.installStart(ok.id, ok.size), "kadran kurulumu 4/4")) return "Band 8 hazır değil."
        pendingInstall = ok.id to bytes
        busy = true
        log("Band 8 kadran kurulum isteği: kimlik=${ok.id}, boy=${ok.size}")
        setStatus("Kurulum isteniyor…")
        main.removeCallbacks(installTimeout)
        main.postDelayed(installTimeout, INSTALL_REPLY_MS)
        return null
    }

    /** Tür 4 yanıtları. İşlendiyse true. */
    fun onCommand(c: B8Command): Boolean {
        if (c.type != B8Type.WATCHFACE) return false
        when (c.subtype) {
            B8Watchface.SUB_LIST -> {
                val l = B8Watchface.parseList(c)
                faces = l
                log(if (l == null) "Band 8 kadran listesi beklenen biçimde değil"
                    else "Band 8 kadran listesi: ${l.size} kadran, silinebilir ${l.count { it.canDelete }}, etkin ${l.firstOrNull { it.active }?.id ?: "yok"}")
                if (!busy) setStatus(if (l == null) "Liste okunamadı." else "Bantta ${l.size} kadran.")
                else WatchHub.settingsChanged()
            }
            B8Watchface.SUB_SET -> {
                log("Band 8 kadran etkin yapma yanıtı: durum=${c.status ?: "yok"}, alanlar=${fields(c)}")
                read()
            }
            B8Watchface.SUB_DELETE -> {
                log("Band 8 kadran silme yanıtı: durum=${c.status ?: "yok"}, alanlar=${fields(c)}")
                read()
            }
            B8Watchface.SUB_INSTALL -> onInstallReply(c)
            else -> log("Band 8 kadran olayı: altTür=${c.subtype}, alanlar=${fields(c)}")
        }
        return true
    }

    private fun onInstallReply(c: B8Command) {
        val st = B8Watchface.installStatus(c)
        log("Band 8 kadran kurulum yanıtı: kurulumDurumu=${st ?: "yok"}, alanlar=${fields(c)}")
        val p = pendingInstall ?: return
        main.removeCallbacks(installTimeout)
        pendingInstall = null
        if (st != 0) {
            busy = false
            setStatus(if (st == 2) "Band bu kadranın kurulu olduğunu söyledi." else "Band kurulumu reddetti (durum ${st ?: "yok"}).")
            return
        }
        lastShownPercent = -1
        val err = upload(B8Upload.TYPE_WATCHFACE, p.second, "kadran ${p.first}", object : Band8Ble.UploadCallback {
            override fun onProgress(done: Int, total: Int) {
                val pct = done * 100 / total
                if (pct / 10 != lastShownPercent / 10) {
                    lastShownPercent = pct
                    setStatus("Yükleniyor: %$pct")
                }
            }
            override fun onFinished(reason: String?) {
                busy = false
                setStatus(if (reason == null) "Yüklendi. Listede görünüyorsa \"Etkin yap\" ile seç." else "Yükleme durdu: $reason")
                if (reason == null) main.postDelayed({ read() }, LIST_AFTER_UPLOAD_MS)
            }
        })
        if (err != null) {
            busy = false
            setStatus("Yükleme başlamadı: $err")
        } else setStatus("Yükleniyor: %0")
    }

    fun onDisconnected() {
        main.removeCallbacks(installTimeout)
        pendingInstall = null
        if (busy) setStatus("Bağlantı koptu.")
        busy = false
        faces = null
    }

    private fun fields(c: B8Command): String =
        c.body(B8Type.F_WATCHFACE)?.fields?.map { it.number }?.distinct()?.joinToString(",") ?: "-"

    private fun setStatus(s: String) {
        status = s
        WatchHub.settingsChanged()
    }

    companion object {
        const val INSTALL_REPLY_MS = 10_000L
        const val LIST_AFTER_UPLOAD_MS = 1_500L

        /** Arayüzün okuduğu durum (süreç boyunca). */
        @Volatile var faces: List<B8Watchface.Face>? = null
            private set
        @Volatile var status: String = ""
            private set
        @Volatile var busy: Boolean = false
            private set
    }
}
