package com.che.ls05

import android.content.Context
import com.che.ls05.protocol.WatchType
import com.che.ls05.protocol.band8.B8Activity
import com.che.ls05.protocol.band8.B8ActivityFile
import com.che.ls05.protocol.band8.B8FileId
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Band 8 etkinlik dosyalarının yerel arşivi. LS05 sağlık günlükleriyle aynı biçimde okunmaz.
 * Dosya kimliği gün içinde güncellenebilir; aynı kimlik için son geçerli sürüm atomik olarak saklanır.
 * Bu katman ölçüm yorumlamaz ve banddan dosya silmez.
 */
object Band8HealthArchive {
    enum class Save { STORED, UNCHANGED, INVALID, WRONG_WATCH, FAILED }

    private const val MAX_FILE_BYTES = 1024 * 1024
    private const val SUBDIR = "band8-files"
    private val NAME = Regex("[0-9a-f]{14}\\.bin")

    private fun directory(c: Context): File? {
        val watch = WatchRegistry.active(c)?.takeIf { it.type == WatchType.BAND8 } ?: return null
        return File(File(c.filesDir, HealthStore.dirName(c)), SUBDIR)
    }

    private fun fileName(id: B8FileId): String = id.encode().joinToString("") { "%02x".format(it.toInt() and 0xff) } + ".bin"

    private fun valid(file: B8ActivityFile): Boolean =
        file.bytes.size in 12..MAX_FILE_BYTES &&
            file.bytes.copyOfRange(0, 7).contentEquals(file.id.encode()) &&
            B8Activity.crcOk(file.bytes)

    fun save(c: Context, file: B8ActivityFile): Save {
        val dir = directory(c) ?: return Save.WRONG_WATCH
        if (!valid(file)) return Save.INVALID
        var temporary: File? = null
        return try {
            if (!dir.isDirectory && !dir.mkdirs()) return Save.FAILED
            val target = File(dir, fileName(file.id))
            if (target.isFile && target.readBytes().contentEquals(file.bytes)) return Save.UNCHANGED
            val staged = File.createTempFile("b8-", ".tmp", dir)
            temporary = staged
            FileOutputStream(staged).use { out ->
                out.write(file.bytes)
                out.fd.sync()
            }
            try {
                Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staged.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            if (target.readBytes().contentEquals(file.bytes)) Save.STORED else Save.FAILED
        } catch (_: Exception) {
            Save.FAILED
        } finally {
            temporary?.delete()
        }
    }

    /** Geçersiz veya yarım dosyalar atlanır; hiçbir sağlık değeri loglanmaz. */
    fun readAll(c: Context): List<B8ActivityFile> {
        val dir = directory(c) ?: return emptyList()
        return (dir.listFiles() ?: emptyArray()).asSequence()
            .filter { it.isFile && NAME.matches(it.name) }
            .mapNotNull { stored ->
                try {
                    if (stored.length() !in 12..MAX_FILE_BYTES.toLong()) return@mapNotNull null
                    val bytes = stored.readBytes()
                    val id = B8FileId.decode(bytes)
                    B8ActivityFile(id, bytes).takeIf { valid(it) && stored.name == fileName(id) }
                } catch (_: Exception) {
                    null
                }
            }
            .sortedBy { it.id.utcSeconds }
            .toList()
    }
}
