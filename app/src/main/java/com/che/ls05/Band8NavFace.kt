package com.che.ls05

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.che.ls05.protocol.band8.B8FaceBin
import com.che.ls05.protocol.band8.B8NavFace
import com.che.ls05.protocol.band8.B8NavGate

/**
 * Navigasyon kadranı (deneysel, Notify yöntemi). Maps bildiriminin oku, mesafe ve sokak siyah zeminli
 * kadrana çizilir, sabit kimlikle (167299090) banda yüklenir ve etkin yapılır. Navigasyon bitince önceki
 * kadran geri etkin yapılır ve navigasyon kadranı silinir.
 *
 * Yalnızca manevra değişince yüklenir (B8NavGate). Yükleme sürerken gelen yeni manevra beklemeye alınır,
 * yalnızca en sonuncusu gider. Logda sokak ve metin yok; yalnızca kimlik, boy, süre ve adım.
 */
class Band8NavFace(
    private val context: Context,
    private val main: Handler,
    private val faces: Band8Faces,
    private val log: (String) -> Unit,
) {
    /** Bildirimden gelen bir kare. [arrow] ARGB, boyu bildirimin büyük simgesi kadar. */
    class Frame(val key: String, val distance: String, val street: String, val arrow: B8FaceBin.Image?)

    private enum class Phase { IDLE, WAIT_LIST, INSTALLING, SETTING, CLEAR_RESTORE, CLEAR_DELETE, END_RESTORE, END_DELETE }

    private var phase = Phase.IDLE
    private var session = false
    private var pending: Frame? = null
    private var endRequested = false
    private var retried = false
    private val gate = B8NavGate()
    private val watchdog = Runnable {
        if (phase != Phase.IDLE) {
            log("Band 8 navigasyon kadranı: adım zaman aşımı ($phase); akış sıfırlandı")
            phase = Phase.IDLE
            next()
        }
    }

    init { faces.listener = ::onEvent }

    /** Bildirim dinleyiciden: ayar açıksa ve manevra değiştiyse kadran yenilenir. */
    fun onFrame(f: Frame) {
        if (!Prefs.band8NavFace(context)) return
        if (!gate.decide(f.key, f.distance, android.os.SystemClock.elapsedRealtime())) return
        submit(f)
    }

    /** Gelişmiş > deneme: örnek okla, ayar ve kapı olmadan. */
    fun test(): String? {
        if (phase != Phase.IDLE) return "Navigasyon kadranı işi sürüyor."
        submit(Frame("deneme", "300 m", "Örnek Caddesi", null))
        return null
    }

    /** Navigasyon bitti (bildirim kalktı) ya da Gelişmiş > kaldır. */
    fun end(): String? {
        gate.reset()
        pending = null
        if (!session && Prefs.band8NavPrevFace(context) == null) return "Navigasyon kadranı etkin değil."
        endRequested = true
        if (phase == Phase.IDLE) next()
        return null
    }

    fun onDisconnected() {
        main.removeCallbacks(watchdog)
        phase = Phase.IDLE
        pending = null
        // Oturum ve önceki kadran Prefs'te kalır; yeniden bağlanınca navigasyon bittiyse geri dönülür.
    }

    private fun submit(f: Frame) {
        pending = f
        endRequested = false
        if (phase == Phase.IDLE) next()
    }

    /** Boşta iken sıradaki işi başlatır: bitiş, bekleyen kare ya da hiçbir şey. */
    private fun next() {
        if (phase != Phase.IDLE) return
        when {
            endRequested -> startEnd()
            pending != null -> startUpload()
        }
    }

    private fun step(p: Phase) {
        phase = p
        main.removeCallbacks(watchdog)
        if (p != Phase.IDLE) main.postDelayed(watchdog, STEP_TIMEOUT_MS)
    }

    private fun startUpload() {
        val list = Band8Faces.faces
        if (list == null) {
            step(Phase.WAIT_LIST)
            faces.read()?.let { log("Band 8 navigasyon kadranı: liste okunamadı ($it)"); step(Phase.IDLE) }
            return
        }
        if (!session) {
            session = true
            retried = false
            val prev = list.firstOrNull { it.active }?.id?.takeIf { it != B8NavFace.ID }
            if (prev != null) Prefs.setBand8NavPrevFace(context, prev)
            log("Band 8 navigasyon kadranı başladı: önceki kadran ${prev ?: "bilinmiyor"}")
        }
        val f = pending ?: return
        pending = null
        lastFrame = f
        val bytes = B8NavFace.build(f.arrow ?: B8NavFace.sampleArrow(), text(f.distance, 46f, true, 1), f.street.takeIf { it.isNotBlank() }?.let { text(it, 26f, false, 3) })
        val problems = B8FaceBin.check(bytes)
        if (problems.isNotEmpty()) {
            log("Band 8 navigasyon kadranı denetimden geçmedi: ${problems.first()}")
            next()
            return
        }
        log("Band 8 navigasyon kadranı yükleniyor: boy=${bytes.size}, ok=${if (f.arrow != null) "bildirimden" else "örnek"}")
        step(Phase.INSTALLING)
        faces.install(bytes, replace = true)?.let {
            log("Band 8 navigasyon kadranı yüklenemedi: $it")
            step(Phase.IDLE)
            if (it.contains("sürüyor")) pending = pending ?: f
        }
    }

    private fun startEnd() {
        endRequested = false
        val prev = Prefs.band8NavPrevFace(context)
            ?: Band8Faces.faces?.firstOrNull { !it.canDelete }?.id
        if (prev == null) {
            log("Band 8 navigasyon kadranı: geri dönülecek kadran yok; yalnızca silme denenecek")
            step(Phase.END_DELETE)
            faces.delete(B8NavFace.ID, force = true)?.let { step(Phase.IDLE) }
            return
        }
        step(Phase.END_RESTORE)
        faces.setActive(prev, force = true)?.let { log("Band 8 navigasyon kadranı: geri dönüş gönderilemedi ($it)"); step(Phase.IDLE) }
    }

    private fun onEvent(e: Band8Faces.Event) {
        when (phase) {
            Phase.WAIT_LIST -> if (e is Band8Faces.Event.Listed) { step(Phase.IDLE); if (e.faces != null) next() }
            Phase.INSTALLING -> if (e is Band8Faces.Event.Installed && e.id == B8NavFace.ID) {
                if (e.error == null) {
                    log("Band 8 navigasyon kadranı yüklendi: boy=${e.size}, süre=${e.ms} ms; etkin yapılıyor")
                    step(Phase.SETTING)
                    faces.setActive(B8NavFace.ID, force = true)?.let { step(Phase.IDLE); next() }
                } else if (e.installStatus == 2 && !retried) {
                    // Bant aynı kimliğin üzerine yazmayı reddetti: önce başka kadrana geç, sil, yeniden yükle.
                    retried = true
                    log("Band 8 navigasyon kadranı: bant üzerine yazmadı (durum 2); silinip yeniden yüklenecek")
                    val prev = Prefs.band8NavPrevFace(context)
                    if (prev != null) { step(Phase.CLEAR_RESTORE); faces.setActive(prev, force = true) }
                    else { step(Phase.CLEAR_DELETE); faces.delete(B8NavFace.ID, force = true) }
                } else {
                    log("Band 8 navigasyon kadranı yüklenemedi: ${e.error}")
                    step(Phase.IDLE); next()
                }
            }
            Phase.SETTING -> if (e is Band8Faces.Event.SetReply) {
                log("Band 8 navigasyon kadranı etkin: durum=${e.status ?: "yok"}")
                step(Phase.IDLE); next()
            }
            Phase.CLEAR_RESTORE -> if (e is Band8Faces.Event.SetReply) {
                step(Phase.CLEAR_DELETE); faces.delete(B8NavFace.ID, force = true)
            }
            Phase.CLEAR_DELETE -> if (e is Band8Faces.Event.DeleteReply) {
                // Son kare yeniden yüklensin.
                step(Phase.IDLE)
                gate.reset()
                if (pending == null) pending = lastFrame
                next()
            }
            Phase.END_RESTORE -> if (e is Band8Faces.Event.SetReply) {
                log("Band 8 navigasyon bitti: önceki kadran geri etkin (durum ${e.status ?: "yok"}); navigasyon kadranı siliniyor")
                step(Phase.END_DELETE)
                faces.delete(B8NavFace.ID, force = true)?.let { step(Phase.IDLE); finishSession() }
            }
            Phase.END_DELETE -> if (e is Band8Faces.Event.DeleteReply) {
                log("Band 8 navigasyon kadranı silindi: durum=${e.status ?: "yok"}")
                step(Phase.IDLE)
                finishSession()
                next()
            }
            Phase.IDLE -> {}
        }
    }

    /** Son yüklenen kare (bant üzerine yazmayı reddederse yeniden yüklemek için). */
    private var lastFrame: Frame? = null

    private fun finishSession() {
        session = false
        Prefs.setBand8NavPrevFace(context, null)
    }

    /** Metni beyaz, ortalanmış görüntüye çizer (Türkçe karakterler sistem yazı tipiyle). En çok [lines] satır. */
    private fun text(s: String, sizePx: Float, bold: Boolean, lines: Int): B8FaceBin.Image? {
        if (s.isBlank()) return null
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = sizePx
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val width = B8NavFace.TEXT_MAX_W
        val layout = StaticLayout.Builder.obtain(s, 0, s.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(lines)
            .setEllipsize(android.text.TextUtils.TruncateAt.END)
            .build()
        val h = layout.height.coerceIn(1, 200)
        val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
        layout.draw(Canvas(bmp))
        val px = IntArray(width * h)
        bmp.getPixels(px, 0, width, 0, 0, width, h)
        bmp.recycle()
        return B8FaceBin.Image(width, h, px)
    }

    companion object {
        const val STEP_TIMEOUT_MS = 60_000L

        /** Bildirimin büyük simgesini ARGB görüntüye çevirir (en çok 192 piksel). */
        fun arrowFrom(bmp: Bitmap?): B8FaceBin.Image? {
            if (bmp == null || bmp.width <= 0 || bmp.height <= 0) return null
            val src = if (bmp.width > B8FaceBin.WIDTH || bmp.height > B8FaceBin.WIDTH) {
                val k = minOf(B8FaceBin.WIDTH.toFloat() / bmp.width, B8FaceBin.WIDTH.toFloat() / bmp.height)
                Bitmap.createScaledBitmap(bmp, maxOf(1, (bmp.width * k).toInt()), maxOf(1, (bmp.height * k).toInt()), true)
            } else bmp
            val px = IntArray(src.width * src.height)
            src.getPixels(px, 0, src.width, 0, 0, src.width, src.height)
            return B8FaceBin.Image(src.width, src.height, px)
        }
    }
}
