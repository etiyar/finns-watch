package com.che.ls05.protocol.band8

import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * Veri yükleme kanalı (belge 9). Bildirim simgesi, kadran.
 *
 * Akış (kayıtta doğrulandı):
 * 1. Telefon 22/0 gönderir: DataUpload alan 1 { 1 tür, 2 MD5 (yalnızca veri), 3 boyut }.
 * 2. Band 22/0 ile yanıtlar: DataUpload alan 2 { 1 MD5, 2 durum, 4 devam konumu, 5 bölüm boyu (Band 8'de yok) }.
 * 3. Blok: 0, tür, MD5, uzunluk (4 bayt LE), veri, CRC32 (4 bayt LE, kendinden önceki her şey).
 * 4. Blok bölümlere ayrılır: her bölüm 2 bayt toplam + 2 bayt sıra (1'den) + veri, en çok [DEFAULT_PART_SIZE].
 * 5. Her bölüm sayaç 0 ile ayrı şifrelenir ve 0x0055'te parçalı gönderilir ([B8Channel.startUpload]).
 */
object B8Upload {
    const val SUB_START = 0

    const val TYPE_WATCHFACE = 16
    /** Yazılım. Uygulanmayacak, yalnızca reddetmek için adı var. */
    const val TYPE_FIRMWARE = 32
    const val TYPE_ICON = 50

    /** Kayıtta Notify bölümleri 4096 bayt (4 bayt başlık + 4092 veri) gönderdi, band bölüm boyu bildirmedi. */
    const val DEFAULT_PART_SIZE = 4096
    const val PART_HEADER = 4

    /** Bloğun veri dışındaki baytları: 1 + 1 + 16 + 4 başta, 4 sonda. */
    const val BLOB_OVERHEAD = 26

    fun md5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)

    fun crc32(data: ByteArray, length: Int = data.size): Long {
        val c = CRC32()
        c.update(data, 0, length)
        return c.value
    }

    /** 22/0 isteği. */
    fun request(type: Int, data: ByteArray): ByteArray =
        B8Messages.command(B8Type.DATA_UPLOAD, SUB_START)
            .message(B8Type.F_DATA_UPLOAD, PbWriter().message(1,
                PbWriter().uint(1, type).bytes(2, md5(data)).uint(3, data.size)))
            .toByteArray()

    /** Bandın 22/0 yanıtı. Alan 2 ve 4'ün anlamı güçlü kanıt (Gadgetbridge), kayıtta ikisi de 0. */
    class Ack(val md5: ByteArray?, val status: Int, val resumeAt: Int, val partSize: Int?)

    fun parseAck(c: B8Command): Ack? {
        if (!c.matches(B8Type.DATA_UPLOAD, SUB_START)) return null
        val a = c.body(B8Type.F_DATA_UPLOAD)?.message(2) ?: return null
        return Ack(a.bytes(1), a.int(2) ?: 0, a.int(4) ?: 0, a.int(5))
    }

    /** Bandın 22/0 yanıtı (testler ve sahte band için). */
    fun ack(md5: ByteArray, status: Int = 0, resumeAt: Int = 0, partSize: Int? = null): ByteArray {
        val a = PbWriter().bytes(1, md5).uint(2, status).uint(4, resumeAt)
        if (partSize != null) a.uint(5, partSize)
        return B8Messages.command(B8Type.DATA_UPLOAD, SUB_START).message(B8Type.F_DATA_UPLOAD, PbWriter().message(2, a)).toByteArray()
    }

    fun blob(type: Int, data: ByteArray): ByteArray {
        val out = ByteArray(BLOB_OVERHEAD + data.size)
        out[0] = 0
        out[1] = type.toByte()
        System.arraycopy(md5(data), 0, out, 2, 16)
        le32(out, 18, data.size.toLong())
        System.arraycopy(data, 0, out, 22, data.size)
        le32(out, 22 + data.size, crc32(out, 22 + data.size))
        return out
    }

    /** Bloğu bölümlere böler. Her bölüm: toplam (2 bayt LE), sıra (2 bayt LE, 1'den), veri. */
    fun parts(blob: ByteArray, partSize: Int = DEFAULT_PART_SIZE): List<ByteArray> {
        require(partSize > PART_HEADER) { "bölüm boyu çok küçük" }
        val per = partSize - PART_HEADER
        val total = (blob.size + per - 1) / per
        require(total in 1..0xFFFF) { "bölüm sayısı sınır dışında" }
        return List(total) { i ->
            val from = i * per
            val n = minOf(per, blob.size - from)
            val p = ByteArray(PART_HEADER + n)
            p[0] = (total and 0xFF).toByte(); p[1] = (total ushr 8).toByte()
            p[2] = ((i + 1) and 0xFF).toByte(); p[3] = ((i + 1) ushr 8).toByte()
            System.arraycopy(blob, from, p, PART_HEADER, n)
            p
        }
    }

    /** Bölümleri birleştirip bloğu doğrular. Sahte band ve testler için. Bozuksa null. */
    class Parsed(val type: Int, val data: ByteArray)

    fun join(parts: List<ByteArray>): Parsed? {
        if (parts.isEmpty()) return null
        val total = u16(parts[0], 0)
        if (total != parts.size) return null
        val buf = java.io.ByteArrayOutputStream()
        parts.forEachIndexed { i, p ->
            if (p.size < PART_HEADER || u16(p, 0) != total || u16(p, 2) != i + 1) return null
            buf.write(p, PART_HEADER, p.size - PART_HEADER)
        }
        return parseBlob(buf.toByteArray())
    }

    fun parseBlob(b: ByteArray): Parsed? {
        if (b.size < BLOB_OVERHEAD || b[0].toInt() != 0) return null
        val len = (u16(b, 18).toLong() or (u16(b, 20).toLong() shl 16)).toInt()
        if (len != b.size - BLOB_OVERHEAD) return null
        val data = b.copyOfRange(22, 22 + len)
        if (!md5(data).contentEquals(b.copyOfRange(2, 18))) return null
        val crc = u16(b, b.size - 4).toLong() or (u16(b, b.size - 2).toLong() shl 16)
        if (crc != crc32(b, b.size - 4)) return null
        return Parsed(b[1].toInt() and 0xFF, data)
    }

    private fun le32(b: ByteArray, at: Int, v: Long) {
        for (i in 0 until 4) b[at + i] = ((v ushr (8 * i)) and 0xFF).toByte()
    }

    private fun u16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
}

/**
 * Tek bir yüklemenin üst düzey adımları. Android'e bağlı değil.
 *
 * [request] 22/0 isteğini verir. Band yanıtı gelince [onAck] gönderilecek bölümleri ya da hata nedenini döner.
 * Bölümlerin taşınması [B8Channel.startUpload] işidir.
 */
class B8UploadJob(val type: Int, val data: ByteArray) {
    val md5: ByteArray = B8Upload.md5(data)

    fun request(): ByteArray = B8Upload.request(type, data)

    sealed class Next {
        /** [parts] gönderilecek bölümler; [skipped] devam konumu yüzünden atlanan bölüm sayısı. */
        class Send(val parts: List<ByteArray>, val skipped: Int, val total: Int) : Next()
        class Fail(val reason: String) : Next()
    }

    fun onAck(ack: B8Upload.Ack): Next {
        val m = ack.md5
        if (m != null && !m.contentEquals(md5)) return Next.Fail("MD5 tutmadı")
        if (ack.status != 0) return Next.Fail("band durum=${ack.status}")
        val size = ack.partSize?.takeIf { it > 0 } ?: B8Upload.DEFAULT_PART_SIZE
        if (size <= B8Upload.PART_HEADER || size > 0xFFFF) return Next.Fail("bölüm boyu=$size")
        val all = B8Upload.parts(B8Upload.blob(type, data), size)
        if (ack.resumeAt == 0) return Next.Send(all, 0, all.size)
        // Devam konumu (tahmin): bloktaki bayt konumu. Yalnızca bölüm sınırındaysa kullanılır, yoksa iş durur.
        val per = size - B8Upload.PART_HEADER
        val blobSize = B8Upload.BLOB_OVERHEAD + data.size
        if (ack.resumeAt < 0 || ack.resumeAt >= blobSize || ack.resumeAt % per != 0) {
            return Next.Fail("devam konumu bölüm sınırında değil")
        }
        val skip = ack.resumeAt / per
        return Next.Send(all.drop(skip), skip, all.size)
    }
}
