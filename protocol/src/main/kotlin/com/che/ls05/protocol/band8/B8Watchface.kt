package com.che.ls05.protocol.band8

/**
 * Band 8 kadran komutları (tür 4, Command alan 6). Belge 9 ve 18.
 *
 * - 4/0 liste: doğrulandı (kayıtta yanıt çözüldü).
 * - 4/1 etkin yap, 4/2 sil, 4/4 kurulum başlat: güçlü kanıt (Gadgetbridge davranışı), cihazda denenmedi.
 */
object B8Watchface {
    const val SUB_LIST = 0
    const val SUB_SET = 1
    const val SUB_DELETE = 2
    const val SUB_INSTALL = 4

    data class Face(val id: String, val name: String, val active: Boolean, val canDelete: Boolean)

    fun list(): ByteArray = B8Messages.command(B8Type.WATCHFACE, SUB_LIST).toByteArray()

    /** 4/0 yanıtı: Watchface alan 1 > tekrarlı 1 { 1 kimlik, 2 ad, 3 etkin, 4 silinebilir }. */
    fun parseList(c: B8Command): List<Face>? {
        if (!c.matches(B8Type.WATCHFACE, SUB_LIST)) return null
        val l = c.body(B8Type.F_WATCHFACE)?.message(1) ?: return null
        return l.messages(1).mapNotNull { m ->
            val id = m.string(1)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Face(id, m.string(2) ?: "", m.bool(3) == true, m.bool(4) == true)
        }
    }

    fun set(id: String): ByteArray = withId(SUB_SET, id)

    fun delete(id: String): ByteArray = withId(SUB_DELETE, id)

    private fun withId(sub: Int, id: String): ByteArray = B8Messages.command(B8Type.WATCHFACE, sub)
        .message(B8Type.F_WATCHFACE, PbWriter().string(2, id)).toByteArray()

    /** 4/4: Watchface alan 6 { 1 kimlik, 2 boyut }. */
    fun installStart(id: String, size: Int): ByteArray = B8Messages.command(B8Type.WATCHFACE, SUB_INSTALL)
        .message(B8Type.F_WATCHFACE, PbWriter().message(6, PbWriter().string(1, id).uint(2, size))).toByteArray()

    /** 4/4 yanıtındaki kurulum durumu (Watchface alan 5). 0 = yüklemeye hazır, 2 = zaten kurulu (güçlü kanıt). */
    fun installStatus(c: B8Command): Int? {
        if (!c.matches(B8Type.WATCHFACE, SUB_INSTALL)) return null
        val w = c.body(B8Type.F_WATCHFACE) ?: return null
        return w.int(5) ?: if (w.has(7)) null else 0
    }

    /** Silme yalnızca kullanıcı kadranında ve etkin değilse. */
    fun canDelete(f: Face): Boolean = f.canDelete && !f.active

    /**
     * Kimliğin ilk 3 hanesi bandın kendi kadranlarından biriyle aynı mı. Kayıttaki bütün kimlikler aynı
     * önekle başlıyordu; önek modeli gösteriyor olabilir (tahmin). Uyuşmazsa kullanıcı açıkça uyarılır.
     */
    fun sameFamily(id: String, list: List<Face>): Boolean =
        id.length >= 3 && list.any { it.id.length >= 3 && it.id.regionMatches(0, id, 0, 3) }

    /**
     * Uyarı için karşılaştırılacak önekler: önce sonradan yüklenen (silinebilir) kadranların önekleri,
     * yoksa bütün kadranlarınki. Bu bantta yüklenip çalışan kadran 167 önekliydi, yerleşikler 266.
     */
    fun installedPrefixes(list: List<Face>): Set<String> {
        val user = list.filter { it.canDelete && it.id.length >= 3 }.map { it.id.take(3) }.toSet()
        return user.ifEmpty { list.filter { it.id.length >= 3 }.map { it.id.take(3) }.toSet() }
    }

    /** Kimliğin öneki yüklü kadranların öneklerinden farklıysa true. Liste boşsa uyarı yok. */
    fun prefixDiffers(id: String, list: List<Face>): Boolean {
        val p = installedPrefixes(list)
        return p.isNotEmpty() && id.take(3) !in p
    }
}

/**
 * Kadran dosyası (.bin) başlığı (güçlü kanıt: Gadgetbridge davranışı; tek dosya incelenmedi):
 * - 0x00: `5A A5` imzası
 * - 0x28: kimlik, yalnızca rakam, sıfırla biten ASCII
 * - 0x68: ad, sıfırla biten UTF-8. `FF FF FF FF` ise ad çok dilli tabloda (0x74 konum, 0x78 boy)
 */
object B8FaceFile {
    /** Band 8 kadranları birkaç yüz KB; 8 MB üstü kabul edilmez. */
    const val MAX_SIZE = 8 * 1024 * 1024
    private const val ID_AT = 0x28
    private const val NAME_AT = 0x68
    private const val HEADER = 0x80

    sealed class Result {
        /** [name] çok dilli ya da okunamadıysa boş. */
        class Ok(val id: String, val name: String, val size: Int) : Result()
        class Invalid(val reason: String) : Result()
    }

    fun parse(b: ByteArray): Result {
        if (b.size < HEADER) return Result.Invalid("dosya çok kısa")
        if (b.size > MAX_SIZE) return Result.Invalid("dosya ${MAX_SIZE / 1024 / 1024} MB'tan büyük")
        if ((b[0].toInt() and 0xFF) != 0x5A || (b[1].toInt() and 0xFF) != 0xA5) return Result.Invalid("kadran imzası yok")
        val id = cString(b, ID_AT, NAME_AT) ?: return Result.Invalid("kimlik okunamadı")
        if (id.isEmpty() || id.length > 20 || !id.all { it in '0'..'9' }) return Result.Invalid("kimlik sayı değil")
        val localized = (0 until 4).all { (b[NAME_AT + it].toInt() and 0xFF) == 0xFF }
        val name = if (localized) "" else cString(b, NAME_AT, NAME_AT + 0x40)?.trim() ?: ""
        return Result.Ok(id, name, b.size)
    }

    private fun cString(b: ByteArray, from: Int, limit: Int): String? {
        var end = from
        while (end < limit && end < b.size && b[end].toInt() != 0) end++
        if (end >= limit || end >= b.size) return null
        return String(b, from, end - from, Charsets.UTF_8)
    }
}
