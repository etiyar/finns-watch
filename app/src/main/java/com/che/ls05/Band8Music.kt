package com.che.ls05

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.view.KeyEvent
import com.che.ls05.protocol.MusicVolume
import com.che.ls05.protocol.band8.B8Music

/**
 * Band 8 müzik ekranı: telefondaki etkin medya oturumunun durumu banda gider, bandın tuşları telefonu kumanda eder.
 * Medya oturumları bildirim erişimiyle okunur (NotifyForwarder). Şarkı adı ve sanatçı loga yazılmaz.
 * Yapı tahmindir (docs/BAND8_PROTOKOL.md 17.7); gelen 18/x alan biçimi loga yazılır.
 */
class Band8Music(
    context: Context,
    private val main: Handler,
    private val log: (String) -> Unit,
    private val send: (ByteArray) -> Boolean,
) {
    private val app = context.applicationContext
    private val sessions = app.getSystemService(MediaSessionManager::class.java)
    private val audio = app.getSystemService(AudioManager::class.java)
    private val listener = ComponentName(app, NotifyForwarder::class.java)
    private var controller: MediaController? = null
    private var running = false
    private var lastSentAt = 0L

    /** 18/1 ile banda en son bildirilen ses yüzdesi; bantın ses olayı buna göre yorumlanır. */
    private var lastReportedPercent: Int? = null

    private val pushRunnable = Runnable { sendInfo("değişiklik") }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = scheduleUpdate()
        override fun onPlaybackStateChanged(state: PlaybackState?) = scheduleUpdate()
        override fun onSessionDestroyed() {
            attach(null)
            scheduleUpdate()
        }
    }

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list -> attach(list?.firstOrNull()) }

    fun start() {
        if (running) return
        running = true
        try {
            sessions?.addOnActiveSessionsChangedListener(sessionsChanged, listener, main)
            attach(sessions?.getActiveSessions(listener)?.firstOrNull())
        } catch (e: SecurityException) {
            log("Band 8 müzik: medya oturumları okunamadı (bildirim erişimi gerekli)")
        }
    }

    fun stop() {
        running = false
        main.removeCallbacks(pushRunnable)
        try { sessions?.removeOnActiveSessionsChangedListener(sessionsChanged) } catch (_: Exception) {}
        attach(null)
    }

    private fun attach(c: MediaController?) {
        if (controller?.sessionToken == c?.sessionToken) return
        try { controller?.unregisterCallback(callback) } catch (_: Exception) {}
        controller = c
        try { c?.registerCallback(callback, main) } catch (_: Exception) {}
    }

    /** Band müzik bilgisi istedi (18/0): hemen yanıtla. */
    fun onRequest() = sendInfo("bant isteği")

    /** Banddaki tuş. Bilinmeyen kod yalnızca loglanır. */
    fun onKey(k: B8Music.MediaKey) {
        val t = controller?.transportControls
        when (k.key) {
            B8Music.KEY_PLAY -> t?.play() ?: PhoneRemote.mediaKey(app, KeyEvent.KEYCODE_MEDIA_PLAY)
            B8Music.KEY_PAUSE -> t?.pause() ?: PhoneRemote.mediaKey(app, KeyEvent.KEYCODE_MEDIA_PAUSE)
            B8Music.KEY_PREVIOUS -> t?.skipToPrevious() ?: PhoneRemote.mediaKey(app, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            B8Music.KEY_NEXT -> t?.skipToNext() ?: PhoneRemote.mediaKey(app, KeyEvent.KEYCODE_MEDIA_NEXT)
            B8Music.KEY_VOLUME -> {
                val v = k.volume ?: run { log("Band 8 müzik sesi: alan1=${k.key}, alan2 yok, yok sayıldı"); return }
                setVolume(k.key, v)
                // Bandın ses çubuğu telefonla aynı kalsın: güncel düzey hemen geri gider.
                sendInfo("ses")
                return
            }
            else -> {
                log("Band 8 müzik tuşu tanınmadı: kod=${k.key}")
                return
            }
        }
        log("Band 8 müzik tuşu uygulandı: kod=${k.key}")
        scheduleUpdate()
    }

    /**
     * Bantın ses olayı telefon müzik akışına uygulanır; sistem ses çubuğu gösterilmez.
     * Ham alan değerleri (yalnızca sayılar) loga yazılır. Yorum: [MusicVolume.decide] (yakınsa yüzde, uzaksa bir çentik).
     */
    private fun setVolume(field1: Int, raw: Int) {
        val a = audio ?: return
        val max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = a.getStreamVolume(AudioManager.STREAM_MUSIC)
        val reported = lastReportedPercent
        val step = MusicVolume.decide(raw, reported, cur, max)
        val base = reported ?: MusicVolume.toPercent(cur, max)
        val mode = when {
            raw == base -> "aynı"
            raw in 0..100 && kotlin.math.abs(raw - base) <= MusicVolume.PLAUSIBLE_DELTA -> "yüzde"
            else -> "çentik"
        }
        log("Band 8 müzik sesi ham: alan1=$field1 alan2=$raw, bildirilen=%${reported ?: -1}, akış=$cur/$max")
        if (cur != step) a.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0)
        log("Band 8 müzik sesi: adım $step/$max ($mode)")
    }

    private fun scheduleUpdate() {
        if (!running) return
        main.removeCallbacks(pushRunnable)
        val wait = (lastSentAt + MIN_INTERVAL_MS - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(300L)
        main.postDelayed(pushRunnable, wait)
    }

    private fun sendInfo(reason: String) {
        val c = controller
        val meta = c?.metadata
        val state = c?.playbackState
        val volume = audio?.let { MusicVolume.toPercent(it.getStreamVolume(AudioManager.STREAM_MUSIC), it.getStreamMaxVolume(AudioManager.STREAM_MUSIC)) } ?: 0
        val bytes = if (c == null || meta == null) {
            B8Music.info(null, null, B8Music.STATE_NONE, 0, 0, volume)
        } else {
            val playing = state?.state == PlaybackState.STATE_PLAYING
            B8Music.info(
                meta.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "",
                meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "",
                if (playing) B8Music.STATE_PLAYING else B8Music.STATE_PAUSED,
                (meta.getLong(MediaMetadata.METADATA_KEY_DURATION) / 1000).toInt(),
                ((state?.position ?: 0L) / 1000).toInt(),
                volume,
            )
        }
        lastSentAt = android.os.SystemClock.elapsedRealtime()
        val ok = send(bytes)
        if (ok) lastReportedPercent = volume
        log("Band 8 müzik bilgisi ($reason): oturum=${c != null}, çalıyor=${state?.state == PlaybackState.STATE_PLAYING}, ses=%$volume, gönderildi=$ok")
    }

    private companion object {
        const val MIN_INTERVAL_MS = 1_500L
    }
}
