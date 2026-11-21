package com.che.ls05

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.che.ls05.protocol.band8.B8FaceBin
import com.che.ls05.protocol.band8.B8NavFace
import com.che.ls05.protocol.band8.B8NavGate
import com.che.ls05.protocol.band8.B8NavGuard
import com.che.ls05.protocol.band8.B8Watchface

/**
 * Navigasyon kadranı (deneysel, Notify yöntemi). Build 238'de otomatik akış bandı tekrar tekrar yeniden
 * başlattı (etkin kadran değişir değişmez silme ve ardından sağlık çekme sırasında). Bu sürümün kuralları:
 *
 * 1. Navigasyon sürerken hiçbir kadran silinmez. İki sabit kimlik dönüşümlü: yeni kare etkin olmayan
 *    kimliğe yüklenir ve etkin yapılır; eskisi navigasyon bitene kadar bantta kalır.
 * 2. Bitişte önceki kadran etkin yapılır, 4/0 ile doğrulanır, 5 sn beklenir; navigasyon kadranları tek tek,
 *    her silmeden sonra yanıt ve 5 sn beklenerek silinir. Bağlanınca kalıntı varsa aynı temizlik 20 sn sonra.
 * 3. 4/4 durum 2: silme yok; diğer kimlik denenir, o da olmazsa kare atlanır.
 * 4. Kadran işi sürerken ve son işten sonra 10 sn sağlık çekme bekler (Band8Ble.holdHeavy, [busy]).
 * 5. Kadran işinden sonraki 15 sn içinde iki kopma: otomatik akış kalıcı kapatılır, telefonda uyarı çıkar.
 *    Kopmadan sonra bekleyen iş yeniden başlatılmaz.
 *
 * Logda sokak ve metin yok; yalnızca kimlik, boy, süre, adım ve durum.
 */
class Band8NavFace(
    private val context: Context,
    private val main: Handler,
    private val faces: Band8Faces,
    private val hold: (Long) -> Unit,
    private val log: (String) -> Unit,
) {
    /** Bildirimden gelen bir kare. [arrow] ARGB, boyu bildirimin büyük simgesi kadar. */
    class Frame(val key: String, val distance: String, val street: String, val arrow: B8FaceBin.Image?)

    private enum class Phase {
        IDLE, WAIT_LIST, INSTALLING, SETTING,
        CLEAN_LIST, CLEAN_SET, CLEAN_VERIFY, CLEAN_WAIT, CLEAN_DELETE,
    }

    private var phase = Phase.IDLE
    private var session = false
    private var pending: Frame? = null
    private var endRequested = false
    private var cleaning = false
    private var restoreTried = false
    private val blocked = HashSet<String>()
    private var lastUploadAt = 0L
    private var lastFrameAt = 0L
    private val gate = B8NavGate()

    /** Kadran işi sürüyor ya da yakın zamanda bitti: ağır işler (sağlık çekme) beklesin. */
    val busy: Boolean get() = phase != Phase.IDLE

    private val watchdog = Runnable {
        if (phase != Phase.IDLE) {
            log("Band 8 navigasyon kadranı: adım zaman aşımı ($phase); akış durdu")
            cleaning = false
            step(Phase.IDLE)
        }
    }
    private val runPending = Runnable { next() }
    private val delayed = Runnable { onTimer() }
    private val connectCleanup = Runnable { maybeCleanupAfterConnect() }

    init { faces.listener = ::onEvent }

    // ---------- Girişler ----------

    /** Bildirim dinleyiciden: ayar açıksa ve manevra değiştiyse kadran yenilenir. */
    fun onFrame(f: Frame) {
        if (!Prefs.band8NavFace(context)) return
        lastFrameAt = now()
        if (!gate.decide(f.key, f.distance, now())) return
        submit(f)
    }

    /** Gelişmiş > deneme: örnek okla, ayar ve kapı olmadan. */
    fun test(): String? {
        if (phase != Phase.IDLE || cleaning) return "Navigasyon kadranı işi sürüyor."
        submit(Frame("deneme", "300 m", "Örnek Caddesi", null))
        return null
    }

    /** Navigasyon bitti (bildirim kalktı) ya da Gelişmiş > kaldır: güvenli temizlik. */
    fun end(): String? {
        gate.reset()
        pending = null
        main.removeCallbacks(runPending)
        endRequested = true
        if (phase == Phase.IDLE && !cleaning) next()
        return null
    }

    fun onConnected() {
        main.removeCallbacks(connectCleanup)
        main.postDelayed(connectCleanup, CONNECT_CLEANUP_DELAY_MS)
    }

    fun onDisconnected(userInitiated: Boolean) {
        main.removeCallbacks(watchdog)
        main.removeCallbacks(runPending)
        main.removeCallbacks(delayed)
        main.removeCallbacks(connectCleanup)
        phase = Phase.IDLE
        // Kopmadan sonra bekleyen iş yeniden başlatılmaz.
        pending = null
        cleaning = false
        endRequested = false
        if (userInitiated) return
        if (guard.disconnected(now())) trip()
        else if (guard.strikeCount > 0) log("Band 8 navigasyon kadranı: kadran işinden hemen sonra bağlantı koptu (uyarı ${guard.strikeCount})")
    }

    // ---------- Akış ----------

    private fun submit(f: Frame) {
        pending = f
        endRequested = false
        if (phase == Phase.IDLE && !cleaning) next()
    }

    private fun next() {
        if (phase != Phase.IDLE || cleaning) return
        when {
            endRequested -> { endRequested = false; startCleanup("navigasyon bitti") }
            pending != null -> {
                val wait = lastUploadAt + MIN_UPLOAD_GAP_MS - now()
                if (wait > 0) { main.removeCallbacks(runPending); main.postDelayed(runPending, wait); return }
                startUpload()
            }
        }
    }

    private fun step(p: Phase) {
        phase = p
        main.removeCallbacks(watchdog)
        if (p != Phase.IDLE) {
            main.postDelayed(watchdog, STEP_TIMEOUT_MS)
            guard.faceOp(now())
        }
        hold(QUIET_AFTER_MS)
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
            blocked.clear()
            val prev = list.firstOrNull { it.active }?.id?.takeIf { !B8NavFace.isNav(it) }
            if (prev != null) Prefs.setBand8NavPrevFace(context, prev)
            log("Band 8 navigasyon kadranı başladı: önceki kadran ${prev ?: "kayıtlı değil"}")
        }
        val id = B8NavFace.target(list, blocked) ?: run {
            log("Band 8 navigasyon kadranı: uygun kimlik yok (ikisi de dolu); kare atlandı, temizlik navigasyon bitince")
            pending = null
            return
        }
        val f = pending ?: return
        pending = null
        install(f, id)
    }

    private fun install(f: Frame, id: String) {
        val bytes = B8NavFace.build(
            f.arrow ?: B8NavFace.sampleArrow(),
            text(f.distance, 46f, true, 1),
            f.street.takeIf { it.isNotBlank() }?.let { text(it, 26f, false, 3) },
            id,
        )
        val problems = B8FaceBin.check(bytes)
        if (problems.isNotEmpty()) {
            log("Band 8 navigasyon kadranı denetimden geçmedi: ${problems.first()}")
            return
        }
        lastFrame = f
        log("Band 8 navigasyon kadranı yükleniyor: kimlik=$id, boy=${bytes.size}, ok=${if (f.arrow != null) "bildirimden" else "örnek"}")
        step(Phase.INSTALLING)
        lastUploadAt = now()
        faces.install(bytes, replace = true)?.let {
            log("Band 8 navigasyon kadranı yüklenemedi: $it")
            step(Phase.IDLE)
            if (it.contains("sürüyor")) { pending = pending ?: f; main.postDelayed(runPending, MIN_UPLOAD_GAP_MS) }
        }
    }

    /** Son yüklenmek istenen kare (4/4 durum 2 olursa diğer kimliğe yüklemek için). */
    private var lastFrame: Frame? = null

    /** Temizlik: önceki kadrana dön, doğrula, bekle, navigasyon kadranlarını tek tek sil. */
    private fun startCleanup(reason: String) {
        cleaning = true
        restoreTried = false
        log("Band 8 navigasyon kadranı temizliği başladı: $reason")
        step(Phase.CLEAN_LIST)
        faces.read()?.let { log("Band 8 navigasyon temizliği: liste okunamadı ($it)"); cleaning = false; step(Phase.IDLE) }
    }

    private fun onCleanList(list: List<B8Watchface.Face>) {
        val navs = list.filter { B8NavFace.isNav(it.id) }
        if (navs.isEmpty()) {
            log("Band 8 navigasyon temizliği bitti: bantta navigasyon kadranı yok")
            finishCleanup()
            return
        }
        val activeNav = navs.firstOrNull { it.active }
        if (activeNav != null) {
            if (restoreTried) {
                log("Band 8 navigasyon temizliği durdu: önceki kadran etkin olmadı, silme yapılmadı")
                finishCleanup()
                return
            }
            restoreTried = true
            val to = B8NavFace.restoreTo(Prefs.band8NavPrevFace(context), list) ?: run {
                log("Band 8 navigasyon temizliği durdu: dönülecek kadran yok")
                finishCleanup()
                return
            }
            log("Band 8 navigasyon temizliği: önceki kadran etkin yapılıyor ($to)")
            step(Phase.CLEAN_SET)
            faces.setActive(to, force = true)?.let { log("Band 8 navigasyon temizliği: gönderilemedi ($it)"); finishCleanup() }
            return
        }
        // Etkin kadran navigasyon dışı: 5 sn bekle, sonra tek bir kadran sil.
        pendingDelete = navs.first().id
        step(Phase.CLEAN_WAIT)
        main.postDelayed(delayed, SAFE_GAP_MS)
    }

    private var pendingDelete: String? = null

    private fun onTimer() {
        when (phase) {
            Phase.CLEAN_WAIT -> {
                val id = pendingDelete ?: return finishCleanup()
                log("Band 8 navigasyon temizliği: siliniyor ($id)")
                step(Phase.CLEAN_DELETE)
                faces.delete(id, force = true)?.let { log("Band 8 navigasyon temizliği: silme gönderilemedi ($it)"); finishCleanup() }
            }
            Phase.CLEAN_VERIFY -> {
                step(Phase.CLEAN_LIST)
                faces.read()?.let { finishCleanup() }
            }
            else -> {}
        }
    }

    private fun finishCleanup() {
        main.removeCallbacks(delayed)
        cleaning = false
        session = false
        blocked.clear()
        pendingDelete = null
        if (Band8Faces.faces?.none { B8NavFace.isNav(it.id) } == true) Prefs.setBand8NavPrevFace(context, null)
        step(Phase.IDLE)
        next()
    }

    private fun onEvent(e: Band8Faces.Event) {
        when (phase) {
            Phase.WAIT_LIST -> if (e is Band8Faces.Event.Listed) { step(Phase.IDLE); if (e.faces != null) next() }
            Phase.INSTALLING -> if (e is Band8Faces.Event.Installed && B8NavFace.isNav(e.id)) {
                when {
                    e.error == null -> {
                        log("Band 8 navigasyon kadranı yüklendi: kimlik=${e.id}, boy=${e.size}, süre=${e.ms} ms; etkin yapılıyor")
                        step(Phase.SETTING)
                        faces.setActive(e.id, force = true)?.let { step(Phase.IDLE); next() }
                    }
                    e.installStatus == 2 -> {
                        // Silme yok: bu kimlik bu oturumda kullanılmaz, diğeri uygunsa ona yüklenir.
                        blocked += e.id
                        log("Band 8 navigasyon kadranı: bant ${e.id} üzerine yazmadı (durum 2); silinmeden diğer kimlik denenecek")
                        step(Phase.IDLE)
                        val list = Band8Faces.faces.orEmpty()
                        val alt = B8NavFace.target(list, blocked)
                        val f = lastFrame
                        if (alt != null && f != null) main.postDelayed({ if (phase == Phase.IDLE && !cleaning) install(f, alt) }, SAFE_GAP_MS)
                        else log("Band 8 navigasyon kadranı: diğer kimlik de uygun değil; kare atlandı")
                    }
                    else -> {
                        log("Band 8 navigasyon kadranı yüklenemedi: ${e.error}")
                        step(Phase.IDLE); next()
                    }
                }
            }
            Phase.SETTING -> if (e is Band8Faces.Event.SetReply) {
                log("Band 8 navigasyon kadranı etkin: durum=${e.status ?: "yok"}")
                step(Phase.IDLE); next()
            }
            Phase.CLEAN_LIST -> if (e is Band8Faces.Event.Listed) {
                val l = e.faces
                if (l == null) { log("Band 8 navigasyon temizliği: liste okunamadı"); finishCleanup() } else onCleanList(l)
            }
            Phase.CLEAN_SET -> if (e is Band8Faces.Event.SetReply) {
                // 4/0 ile doğrulamadan önce kısa bekleme; doğrulama CLEAN_LIST'te.
                step(Phase.CLEAN_VERIFY)
                main.postDelayed(delayed, VERIFY_DELAY_MS)
            }
            Phase.CLEAN_DELETE -> if (e is Band8Faces.Event.DeleteReply) {
                log("Band 8 navigasyon temizliği: silme yanıtı durum=${e.status ?: "yok"}; 5 sn sonra liste")
                step(Phase.CLEAN_VERIFY)
                main.postDelayed(delayed, SAFE_GAP_MS)
            }
            else -> {}
        }
    }

    /** Bağlanınca kalıntı navigasyon kadranı varsa güvenli temizlik (navigasyon sürmüyorsa, koruma tetiklenmediyse). */
    private fun maybeCleanupAfterConnect() {
        if (Prefs.band8NavTripped(context)) return
        if (phase != Phase.IDLE || cleaning || session) return
        if (now() - lastFrameAt < ACTIVE_NAV_MS && lastFrameAt > 0) return
        val list = Band8Faces.faces
        if (list != null && list.none { B8NavFace.isNav(it.id) }) return
        // Liste okunmadıysa yalnızca önceki oturumdan iz kaldıysa denetlenir (her bağlantıda fazladan 4/0 yok).
        if (list == null && Prefs.band8NavPrevFace(context) == null) return
        startCleanup("bağlantı sonrası kalıntı denetimi")
    }

    /** Çökme koruması tetiklendi: otomatik akış kalıcı kapalı, telefonda uyarı. */
    private fun trip() {
        Prefs.setBand8NavFace(context, false)
        Prefs.setBand8NavTripped(context, true)
        guard.reset()
        session = false
        log("Band 8 navigasyon kadranı bandı yeniden başlattı; otomatik akış kapatıldı")
        WatchHub.settingsChanged()
        try {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Band 8 navigasyon kadranı", NotificationManager.IMPORTANCE_HIGH))
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("Navigasyon kadranı kapatıldı")
                .setContentText("Navigasyon kadranı bandı yeniden başlattı, kapatıldı.")
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            nm.notify(NOTIF_ID, n)
        } catch (_: Exception) {
        }
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

    private fun now() = SystemClock.elapsedRealtime()

    companion object {
        const val STEP_TIMEOUT_MS = 60_000L
        /** İki navigasyon yüklemesi arasında en az bu kadar; arada gelenlerden yalnızca sonuncusu bekler. */
        const val MIN_UPLOAD_GAP_MS = 15_000L
        /** Silme öncesi ve sonrası bekleme. */
        const val SAFE_GAP_MS = 5_000L
        const val VERIFY_DELAY_MS = 1_500L
        /** Son kadran işinden sonra sağlık çekme ve ağır işlerin beklemesi. */
        const val QUIET_AFTER_MS = 10_000L
        const val CONNECT_CLEANUP_DELAY_MS = 20_000L
        /** Son kareden bu kadar süre geçmediyse navigasyon sürüyor sayılır (bağlantı sonrası temizlik yapılmaz). */
        const val ACTIVE_NAV_MS = 60_000L
        private const val CHANNEL_ID = "band8_nav"
        private const val NOTIF_ID = 4810

        /** Süreç boyunca tek koruma: sürücü yeniden kurulsa da uyarılar korunur. */
        val guard = B8NavGuard()

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
