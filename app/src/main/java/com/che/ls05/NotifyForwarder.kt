package com.che.ls05

import android.app.Notification
import android.content.ComponentName
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.che.ls05.protocol.AppTypeMap
import com.che.ls05.protocol.CallClassifier
import com.che.ls05.protocol.CallKind
import com.che.ls05.protocol.CallTracker
import com.che.ls05.protocol.MSG_TYPE_CALL
import com.che.ls05.protocol.MSG_TYPE_GENERIC
import com.che.ls05.protocol.MSG_TYPE_INCOMING_CALL
import com.che.ls05.protocol.NavigationFilter
import com.che.ls05.protocol.NoteFacts
import com.che.ls05.protocol.NotifyDecision
import com.che.ls05.protocol.NotifyRules
import com.che.ls05.protocol.RepeatTracker
import com.che.ls05.protocol.TextSanitizer
import com.che.ls05.protocol.TitleDedupe
import com.che.ls05.protocol.band8.B8Text

/**
 * Telefon bildirimlerini saate iletir.
 * Atlanır: kendi bildirimimiz, kalıcı bildirimler (müzik, navigasyon), grup özetleri, ön plan servisleri,
 * kullanıcının kapattığı uygulamalar ve 60 sn içindeki aynı içerik.
 * İstisna: arama bildirimleri (kalıcı olsalar da) ayrı yoldan gider: gelen arama saatte arama ekranı açar,
 * cevaplanınca ya da bitince kapatılır; cevapsız arama mesaj tipi (4) bildirim olarak gider. Ek izin gerekmez.
 * Bildirim içeriği ve arayan adı loga yazılmaz; yalnızca uygulama adı ve karakter sayısı.
 */
class NotifyForwarder : NotificationListenerService() {

    private companion object {
        /** Bütün bildirimler için saatte en çok tekrar sayısı. */
        const val MAX_REPEATS_PER_HOUR = 20
        const val NAV_VIBRATION = 1
    }

    private val recent = LinkedHashMap<String, Long>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val navigation = NavigationFilter()
    private var repeats = RepeatTracker(MAX_REPEATS_PER_HOUR)
    private val repeatTick = Runnable { runRepeats() }

    override fun onListenerConnected() {
        WatchHub.log("Bildirim erişimi etkin; iletim hazır.")
        // Servis yeniden başladıysa tekrar sayaçları sürer; metin saklanmaz, telefondaki bildirimden yeniden okunur.
        repeats = RepeatTracker.decode(Prefs.repeatState(this), MAX_REPEATS_PER_HOUR)
        scheduleRepeats()
        // Süreç yeniden başladıysa (ör. uygulama son uygulamalardan kaydırıldı) servis ilk bildirimi beklemeden kalksın.
        if (WatchHub.service == null && Prefs.serviceEnabled(this) && WatchRegistry.hasActive(this)) {
            WatchHub.log("Saat servisi çalışmıyor; bildirim dinleyici başlatıyor.")
            WatchService.start(this)
        }
    }

    override fun onListenerDisconnected() {
        WatchHub.log("Bildirim erişimi koptu; sistemden yeniden bağlama isteniyor.")
        try {
            NotificationListenerService.requestRebind(ComponentName(this, NotifyForwarder::class.java))
        } catch (e: Exception) {
            WatchHub.log("Yeniden bağlama istenemedi (${e.javaClass.simpleName}).")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return
        val n = sbn.notification ?: return
        val selected = Prefs.forwardingEnabled(this) && Prefs.appEnabled(this, pkg)
        if (selected) WatchHub.log("Bildirim algılandı: ${appLabel(pkg)}; Android anahtarı=${sbn.key != null}.")
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) {
            if (selected) WatchHub.log("Bildirim atlandı: grup özeti.")
            return
        }
        val ongoing = n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0

        val callType = if (Build.VERSION.SDK_INT >= 31) n.extras?.getInt(Notification.EXTRA_CALL_TYPE, 0) ?: 0 else 0
        val chronometer = n.extras?.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false) ?: false
        val kind = CallClassifier.classify(pkg, n.category, ongoing, callType, n.fullScreenIntent != null, chronometer)
        if (kind != CallKind.NONE) {
            if (selected) WatchHub.log("Bildirim arama yoluna ayrıldı: tür=$kind.")
            handleCall(sbn, n, kind)
            return
        }
        if (ongoing && pkg in NavigationFilter.PACKAGES && Prefs.forwardingEnabled(this) && Prefs.navigation(this)) {
            forwardNavigation(sbn, n, pkg)
            return
        }
        if (ongoing) {
            if (selected) WatchHub.log("Bildirim atlandı: kalıcı veya ön plan bildirimi.")
            return
        }

        Prefs.addSeenApp(this, pkg)
        Prefs.setLastNotified(this, pkg, System.currentTimeMillis())
        if (!selected) return

        val (cleanTitle, cleanText) = texts(n)
        if (cleanTitle.isEmpty() && cleanText.isEmpty()) {
            WatchHub.log("Bildirim atlandı: gösterilecek metin yok.")
            return
        }

        val now = System.currentTimeMillis()
        if (isRepeat("$pkg|${cleanTitle.hashCode()}|${cleanText.hashCode()}", now)) {
            WatchHub.log("Bildirim atlandı: tekrar filtresi.")
            return
        }

        val rule = when (val d = NotifyRules.decide(Prefs.notifyRules(this), NoteFacts(pkg, cleanTitle, cleanText), java.time.LocalDateTime.now())) {
            is NotifyDecision.Block -> {
                WatchHub.log("Bildirim engellendi: ${d.reason}${d.ruleId?.let { " (kural $it)" } ?: ""}.")
                return
            }
            is NotifyDecision.Forward -> d.rule
        }
        if (pkg in TitleDedupe.CALENDAR_PACKAGES) {
            // Takvim hatırlatmamız aynı etkinlik için az önce gittiyse ikincisi gitmez.
            if (WatchHub.calendarDedupe.seenRecently(cleanTitle, now) || WatchHub.calendarDedupe.seenRecently(cleanText, now)) {
                WatchHub.log("Bildirim atlandı: aynı takvim etkinliği 3 dk içinde zaten gönderildi.")
                return
            }
            WatchHub.calendarDedupe.mark(cleanTitle, now)
        }
        val label = appLabel(pkg)
        WatchHub.log("Bildirim dinleyiciden iletime geçti: $label, tür=MESSAGE${rule?.let { ", kural ${it.id}" } ?: ""}.")
        WatchHub.forward(this, note(sbn, pkg, label, cleanTitle, cleanText, now, rule?.vibration ?: -1))
        if (rule != null && rule.repeats && sbn.key != null) {
            // Aynı anahtar yeni mesajla güncellenirse kalan tekrar sayısı korunur (sayaç baştan başlamaz).
            repeats.schedule(sbn.key, rule.repeatEveryMin, rule.repeatMax, now)
            saveRepeats()
            scheduleRepeats()
        }
    }

    private fun note(sbn: StatusBarNotification, pkg: String, label: String, title: String, text: String, now: Long, vibration: Int) =
        WatchHub.Note(
            pkg = pkg,
            appLabel = label,
            title = title.ifEmpty { label },
            body = text,
            type = AppTypeMap.forPackage(pkg),
            at = now,
            androidId = sbn.id,
            androidKey = sbn.key,
            vibration = vibration,
        )

    // ---------- Navigasyon ----------

    /** Süren navigasyon bildirimi: başlık manevra, gövde mesafe ve sokak (NavigationFilter). */
    private fun forwardNavigation(sbn: StatusBarNotification, n: Notification, pkg: String) {
        val (title, text) = texts(n)
        if (title.isEmpty() && text.isEmpty()) return
        val now = System.currentTimeMillis()
        // Manevra değişince hemen, yalnızca mesafe değişince en sık 30 sn'de bir.
        val out = navigation.decide(pkg, title, text, android.os.SystemClock.elapsedRealtime()) ?: return
        WatchHub.log("Navigasyon yönergesi iletime geçti: ${appLabel(pkg)}.")
        // Navigasyonun kendi anahtarı var; uygulama filtresine takılmaz. Kısa titreşim (LS05'te tek).
        WatchHub.forward(this, note(sbn, pkg, appLabel(pkg), out.title, out.body, now, NAV_VIBRATION).copy(appFilter = false))
    }

    // ---------- Okunana kadar tekrar ----------

    private fun saveRepeats() = Prefs.setRepeatState(this, repeats.encode())

    private fun scheduleRepeats() {
        main.removeCallbacks(repeatTick)
        val next = repeats.nextWake() ?: return
        main.postDelayed(repeatTick, (next - System.currentTimeMillis()).coerceIn(1_000L, 60 * 60_000L))
    }

    /** Zamanı gelen bildirimler telefonda hâlâ duruyorsa yeniden gönderilir; yoksa tekrar biter. */
    private fun runRepeats() {
        val now = System.currentTimeMillis()
        val active = try { activeNotifications?.associateBy { it.key } ?: emptyMap() } catch (_: Exception) { emptyMap() }
        for (key in repeats.due(now)) {
            val sbn = active[key]
            if (sbn == null) {
                repeats.remove(key)
                continue
            }
            val pkg = sbn.packageName ?: continue
            val n = sbn.notification ?: continue
            val (title, text) = texts(n)
            val d = NotifyRules.decide(Prefs.notifyRules(this), NoteFacts(pkg, title, text), java.time.LocalDateTime.now())
            if (d !is NotifyDecision.Forward || !Prefs.forwardingEnabled(this)) continue
            WatchHub.log("Okunmamış bildirim yeniden gönderiliyor: ${appLabel(pkg)}.")
            WatchHub.forward(this, note(sbn, pkg, appLabel(pkg), title, text, now, d.rule?.vibration ?: -1))
        }
        // Telefonda artık olmayanlar silinir.
        for (key in repeats.keys.toList()) if (active.isNotEmpty() && key !in active) repeats.remove(key)
        saveRepeats()
        scheduleRepeats()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val key = sbn?.key ?: return
        if (key in repeats.keys) {
            repeats.remove(key)
            saveRepeats()
        }
        sbn.packageName?.let { if (it in NavigationFilter.PACKAGES) navigation.reset(it) }
        if (WatchHub.declineKey == key) WatchHub.declineAction = null
        if (WatchHub.calls.onRemoved(key) == CallTracker.Decision.STOP) stopCallOnWatch(sbn.packageName ?: "")
    }

    // ---------- Aramalar ----------

    private fun handleCall(sbn: StatusBarNotification, n: Notification, kind: CallKind) {
        val pkg = sbn.packageName ?: return
        val now = System.currentTimeMillis()
        if (WatchHub.calls.expire(now) == CallTracker.Decision.STOP) stopCallOnWatch(pkg)
        val forwarding = Prefs.forwardingEnabled(this)

        when (kind) {
            CallKind.INCOMING, CallKind.ONGOING -> when (WatchHub.calls.onPosted(sbn.key, kind, now)) {
                CallTracker.Decision.ALERT -> {
                    if (!forwarding || !Prefs.incomingCalls(this)) {
                        WatchHub.log("Gelen arama algılandı; saate iletim kapalı.")
                        return
                    }
                    rememberDeclineAction(pkg, n, sbn.key, now)
                    val (title, text) = texts(n)
                    // Arama uygulamaları arayanı genelde başlıkta verir; yoksa metin.
                    val name = title.takeIf { TextSanitizer.clean(it).isNotEmpty() } ?: text
                    WatchHub.log("Gelen arama algılandı (${appLabel(pkg)}).")
                    WatchHub.forward(
                        this,
                        WatchHub.Note(pkg, appLabel(pkg), "", name, MSG_TYPE_INCOMING_CALL, now, WatchHub.NoteKind.CALL_ALERT),
                    )
                }
                CallTracker.Decision.STOP -> stopCallOnWatch(pkg)
                CallTracker.Decision.NONE -> if (kind == CallKind.INCOMING) rememberDeclineAction(pkg, n, sbn.key, now)
            }
            CallKind.MISSED -> {
                if (!forwarding || !Prefs.missedCalls(this)) return
                val (title, text) = texts(n)
                // LS05 ile aynı sonuç için: LS05'te boş kalacak (yalnızca emoji) parçalar birleştirilmez.
                val body = listOf(title, text).filter { TextSanitizer.clean(it).isNotEmpty() }.joinToString(": ").ifEmpty { "Cevapsız arama" }
                if (isRepeat("missed|$pkg|${body.hashCode()}", now)) return
                WatchHub.forward(
                    this,
                    // Tip 3 saatte görünür bir bildirim bırakmadı (13 Kasım testi); mesaj tipiyle gider.
                    WatchHub.Note(pkg, appLabel(pkg), "", body, MSG_TYPE_GENERIC, now, WatchHub.NoteKind.MISSED_CALL),
                )
            }
            CallKind.NONE -> Unit
        }
    }

    /**
     * Arama bildirimindeki "Reddet" eylemini saklar; saatteki kırmızı düğme (12 02) bunu tetikler.
     * İzin gerektirmez. Etiket tanınmazsa ve iki eylem varsa ilki alınır (CallStyle'da reddet önce gelir).
     */
    private fun rememberDeclineAction(pkg: String, n: Notification, key: String, now: Long) {
        val actions = n.actions ?: return
        val words = listOf("reddet", "decline", "reject", "kapat", "sonlandır", "bitir", "hang up", "end call", "dismiss")
        val found = actions.firstOrNull { a ->
            val t = a.title?.toString()?.lowercase(java.util.Locale.ROOT) ?: ""
            words.any { t.contains(it) }
        } ?: if (actions.size == 2) actions[0] else null
        if (found?.actionIntent == null) return
        WatchHub.declineAction = found.actionIntent
        WatchHub.declineKey = key
        WatchHub.declineAt = now
        WatchHub.declineLabel = found.title?.toString()?.take(20) ?: ""
        WatchHub.declinePkg = pkg
    }

    private fun stopCallOnWatch(pkg: String) {
        WatchHub.forward(
            this,
            WatchHub.Note(pkg, "", "", "", MSG_TYPE_INCOMING_CALL, System.currentTimeMillis(), WatchHub.NoteKind.CALL_STOP),
        )
    }

    // ---------- Yardımcılar ----------

    /** Temizlenmiş başlık ve metin (kontrol karakterleri çıkar, emoji kalır). */
    private fun texts(n: Notification): Pair<String, String> {
        val extras = n.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (
            extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras?.getCharSequence(Notification.EXTRA_TEXT)
                ?: n.tickerText
            )?.toString().orEmpty()
        // Emoji korunur (Band 8 çizer); LS05'e gönderirken WatchService eski temizleyiciyi uygular.
        return B8Text.clean(title) to B8Text.clean(text)
    }

    /** 60 sn içinde aynı içerik ikinci kez gelirse true. */
    private fun isRepeat(key: String, now: Long): Boolean {
        val iterator = recent.entries.iterator()
        while (iterator.hasNext()) if (now - iterator.next().value > 60_000) iterator.remove()
        if (recent.containsKey(key)) return true
        recent[key] = now
        return false
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }
}
