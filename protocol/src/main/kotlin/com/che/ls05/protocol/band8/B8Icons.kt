package com.che.ls05.protocol.band8

/**
 * Bildirim simgesi akışı (belge 6.4, doğrulandı):
 * band 7/16 { Notification alan 16 > 1 paket } ile simge ister, telefon 7/15 { alan 14 > 1 paket } ile sorar,
 * band 7/15 { alan 15 > 1 durum, 2 piksel biçimi, 3 kenar } ile yanıtlar, simge tür 50 yüklemeyle gider.
 */
object B8Icons {
    /** Banda giden simgenin kenar sınırı. Kayıtta 28, 44, 80 istendi. */
    const val MAX_SIZE = 240

    const val FORMAT_RGB565_LE = 0
    const val FORMAT_RGB565_BE = 1
    const val FORMAT_XRGB8888_LE = 2
    /** Kayıtta Band 8 bunu istedi (doğrulandı). */
    const val FORMAT_ARGB8888_LE = 3

    data class Spec(val status: Int, val format: Int, val size: Int)

    /** 7/16: bandın simge istediği paket adı. */
    fun parseQuery(c: B8Command): String? {
        if (!c.matches(B8Type.NOTIFICATION, B8Sub.NOTIF_ICON_QUERY)) return null
        return c.body(B8Type.F_NOTIFICATION)?.message(16)?.string(1)?.takeIf { it.isNotEmpty() }
    }

    /** 7/15 sorusu: band hangi biçim ve boyu istiyor. Paket adı bandın gönderdiği gibi geri gider. */
    fun request(pkg: String): ByteArray = B8Messages.command(B8Type.NOTIFICATION, B8Sub.NOTIF_ICON_REQUEST)
        .message(B8Type.F_NOTIFICATION, PbWriter().message(14, PbWriter().string(1, pkg)))
        .toByteArray()

    /** 7/15 yanıtı. */
    fun parseSpec(c: B8Command): Spec? {
        if (!c.matches(B8Type.NOTIFICATION, B8Sub.NOTIF_ICON_REQUEST)) return null
        val m = c.body(B8Type.F_NOTIFICATION)?.message(15) ?: return null
        return Spec(m.int(1) ?: 0, m.int(2) ?: return null, m.int(3) ?: return null)
    }

    /** Sahte band ve testler için 7/16 ve 7/15 yanıtı. */
    fun query(pkg: String): ByteArray = B8Messages.command(B8Type.NOTIFICATION, B8Sub.NOTIF_ICON_QUERY)
        .message(B8Type.F_NOTIFICATION, PbWriter().message(16, PbWriter().string(1, pkg))).toByteArray()

    fun spec(s: Spec): ByteArray = B8Messages.command(B8Type.NOTIFICATION, B8Sub.NOTIF_ICON_REQUEST)
        .message(B8Type.F_NOTIFICATION, PbWriter().message(15, PbWriter().uint(1, s.status).uint(2, s.format).uint(3, s.size)))
        .toByteArray()

    fun supported(s: Spec): Boolean = s.status == 0 && s.size in 1..MAX_SIZE &&
        s.format in FORMAT_RGB565_LE..FORMAT_ARGB8888_LE

    /**
     * Android ARGB piksellerini ([size] x [size], satır satır) bandın biçimine çevirir.
     * Biçim desteklenmiyorsa null. 565 biçimlerinde saydam kısım siyaha karışır.
     */
    fun encode(argb: IntArray, size: Int, format: Int): ByteArray? {
        if (argb.size != size * size) return null
        return when (format) {
            FORMAT_ARGB8888_LE, FORMAT_XRGB8888_LE -> {
                val out = ByteArray(argb.size * 4)
                for (i in argb.indices) {
                    val p = argb[i]
                    out[4 * i] = (p and 0xFF).toByte()
                    out[4 * i + 1] = ((p ushr 8) and 0xFF).toByte()
                    out[4 * i + 2] = ((p ushr 16) and 0xFF).toByte()
                    out[4 * i + 3] = if (format == FORMAT_ARGB8888_LE) ((p ushr 24) and 0xFF).toByte() else 0xFF.toByte()
                }
                out
            }
            FORMAT_RGB565_LE, FORMAT_RGB565_BE -> {
                val out = ByteArray(argb.size * 2)
                for (i in argb.indices) {
                    val p = argb[i]
                    val a = (p ushr 24) and 0xFF
                    val r = ((p ushr 16) and 0xFF) * a / 255
                    val g = ((p ushr 8) and 0xFF) * a / 255
                    val b = (p and 0xFF) * a / 255
                    val v = ((r shr 3) shl 11) or ((g shr 2) shl 5) or (b shr 3)
                    val lo = (v and 0xFF).toByte()
                    val hi = (v ushr 8).toByte()
                    if (format == FORMAT_RGB565_LE) { out[2 * i] = lo; out[2 * i + 1] = hi } else { out[2 * i] = hi; out[2 * i + 1] = lo }
                }
                out
            }
            else -> null
        }
    }

    /**
     * Band paket adını kısaltmış olabilir (güçlü kanıt, Gadgetbridge önek eşliyor). Önce tam eşleşme,
     * yoksa tek bir önek eşleşmesi. Birden çok aday varsa null: yanlış uygulamanın simgesi gitmesin.
     */
    fun resolvePackage(query: String, installed: Collection<String>): String? {
        if (query in installed) return query
        val c = installed.filter { it.startsWith(query) }
        return c.singleOrNull()
    }
}
