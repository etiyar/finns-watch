package com.che.ls05

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.che.ls05.protocol.band8.B8FaceBin
import com.che.ls05.protocol.band8.B8FaceFile
import java.io.File

/**
 * Band 8 kadran deposu: yüklenen her .bin uygulamanın özel klasöründe kimliğiyle saklanır
 * (kimlik.bin, kimlik.png önizleme, kimlik.txt ad). Galeri buradan okur. Navigasyon kadranı saklanmaz.
 */
object Band8FaceStore {
    class Entry(val id: String, val name: String, val file: File, val previewFile: File)

    private fun dir(c: Context): File = File(c.filesDir, "band8faces").apply { mkdirs() }

    fun list(c: Context): List<Entry> = dir(c).listFiles { f -> f.name.endsWith(".bin") }
        ?.mapNotNull { entry(c, it.name.removeSuffix(".bin")) }
        ?.sortedBy { it.name.lowercase() } ?: emptyList()

    fun entry(c: Context, id: String): Entry? {
        if (!id.all { it in '0'..'9' }) return null
        val bin = File(dir(c), "$id.bin")
        if (!bin.exists()) return null
        val name = File(dir(c), "$id.txt").takeIf { it.exists() }?.readText()?.trim().orEmpty()
        return Entry(id, name.ifBlank { "Adsız kadran" }, bin, File(dir(c), "$id.png"))
    }

    /** Dosyayı saklar (aynı kimlik varsa üzerine yazar). Kimlik okunamazsa null. */
    fun save(c: Context, bytes: ByteArray): Entry? {
        val ok = B8FaceFile.parse(bytes) as? B8FaceFile.Result.Ok ?: return null
        val d = dir(c)
        File(d, "${ok.id}.bin").writeBytes(bytes)
        File(d, "${ok.id}.txt").writeText(ok.name)
        previewBitmap(bytes)?.let { bmp ->
            File(d, "${ok.id}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
        }
        return entry(c, ok.id)
    }

    fun remove(c: Context, id: String) {
        if (!id.all { it in '0'..'9' }) return
        for (ext in listOf("bin", "png", "txt")) File(dir(c), "$id.$ext").delete()
    }

    fun preview(e: Entry): Bitmap? = if (e.previewFile.exists()) BitmapFactory.decodeFile(e.previewFile.path) else null

    /** Dosyanın içindeki önizlemeyi Bitmap'e çevirir. */
    fun previewBitmap(bytes: ByteArray): Bitmap? {
        val img = B8FaceBin.preview(bytes) ?: return null
        return Bitmap.createBitmap(img.argb, img.width, img.height, Bitmap.Config.ARGB_8888)
    }
}
