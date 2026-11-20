package com.che.ls05.protocol.band8

import java.io.OutputStream
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Bir çekme turunun log özeti. Yalnızca sayılar ve dosya türü yazılır, sağlık değeri ve dosya kimliği yazılmaz.
 */
class B8FetchStats {
    private val kinds = java.util.TreeMap<String, Int>()
    private var files = 0
    private var bytes = 0L
    private var crcFail = 0
    private var otherFail = 0

    val fileCount: Int get() = files

    /** CRC'si tutmuş dosya ([B8PartAssembler] yalnızca bunları teslim eder). */
    fun file(f: B8ActivityFile) {
        files++
        bytes += f.bytes.size
        val k = "t${f.id.type}/s${f.id.subtype}/d${f.id.detailType}"
        kinds[k] = (kinds[k] ?: 0) + 1
    }

    fun failed(reason: String) {
        if (reason == CRC_REASON) crcFail++ else otherFail++
    }

    fun summary(): String {
        val types = if (kinds.isEmpty()) "yok" else kinds.entries.joinToString(", ") { "${it.key}=${it.value}" }
        return "$files dosya, $bytes bayt, CRC tamam=$files, CRC hata=$crcFail, diğer hata=$otherFail; türler: $types"
    }

    companion object {
        /** [B8PartAssembler] CRC hatasında bu gerekçeyi verir. */
        const val CRC_REASON = "CRC tutmadı"
    }
}

/**
 * Band 8 arşivini tek zip olarak dışa aktarır. Dosyalar şifresi çözülmüş, birleştirilmiş hâlde saklanır;
 * çözmek için anahtar gerekmez. Kişisel olmayan bir index.txt eklenir.
 */
object B8ArchiveZip {
    class Entry(val file: B8ActivityFile, val storedAtMs: Long)

    const val INDEX = "index.txt"

    fun entryName(id: B8FileId): String = "band8/" + hex(id.encode()) + ".bin"

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun iso(ms: Long): String = Instant.ofEpochMilli(ms).truncatedTo(ChronoUnit.SECONDS).toString()

    private fun crcHex(b: ByteArray): String {
        if (b.size < 4) return "-"
        val n = b.size
        return "%02x%02x%02x%02x".format(b[n - 1].toInt() and 0xFF, b[n - 2].toInt() and 0xFF, b[n - 3].toInt() and 0xFF, b[n - 4].toInt() and 0xFF)
    }

    fun index(entries: List<Entry>, identity: String, exportedAtMs: Long): String {
        val sb = StringBuilder()
        sb.append("Finn's Watch | Band 8 ham etkinlik dosyaları\n")
        sb.append("APK: ").append(identity).append('\n')
        sb.append("Dışa aktarma (UTC): ").append(iso(exportedAtMs)).append('\n')
        sb.append("Dosya sayısı: ").append(entries.size).append('\n')
        sb.append('\n')
        sb.append("Biçim: dosyalar şifresi çözülmüş ve bölümleri birleştirilmiş hâldedir; çözmek için anahtar gerekmez.\n")
        sb.append("Her .bin dosyası: 7 bayt dosya kimliği + 1 bayt dolgu + gövde + 4 bayt CRC32 (küçük endian).\n")
        sb.append("Kimlik: 4 bayt UTC Unix saniyesi (LE), 1 bayt saat dilimi (15 dk), 1 bayt sürüm, 1 bayt bayrak\n")
        sb.append("(bit 7 tür, bit 2-6 alt tür, bit 0-1 ayrıntı). Ayrıntı için docs/BAND8_PROTOKOL.md bölüm 7.\n")
        sb.append("Saklama zamanı, dosyanın telefonda son yazıldığı andır (UTC).\n")
        sb.append('\n')
        sb.append("dosya\tkimlik\ttür\taltTür\tayrıntı\tsürüm\tbayt\tcrc32\tcrc\tsaklama\n")
        for (e in entries) {
            val id = e.file.id
            sb.append(entryName(id)).append('\t')
                .append(hex(id.encode())).append('\t')
                .append(id.type).append('\t')
                .append(id.subtype).append('\t')
                .append(id.detailType).append('\t')
                .append(id.version).append('\t')
                .append(e.file.bytes.size).append('\t')
                .append(crcHex(e.file.bytes)).append('\t')
                .append(if (B8Activity.crcOk(e.file.bytes)) "tamam" else "hatalı").append('\t')
                .append(iso(e.storedAtMs)).append('\n')
        }
        return sb.toString()
    }

    fun write(out: OutputStream, entries: List<Entry>, identity: String, nowMs: Long) {
        val zip = ZipOutputStream(out)
        fun put(name: String, bytes: ByteArray) {
            val e = ZipEntry(name)
            e.time = nowMs
            zip.putNextEntry(e)
            zip.write(bytes)
            zip.closeEntry()
        }
        put(INDEX, index(entries, identity, nowMs).toByteArray(Charsets.UTF_8))
        for (e in entries) put(entryName(e.file.id), e.file.bytes)
        zip.finish()
        zip.flush()
    }
}
