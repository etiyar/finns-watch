package com.che.ls05

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import com.che.ls05.protocol.CalendarPlan
import com.che.ls05.protocol.MSG_TYPE_GENERIC
import java.time.Instant
import java.time.ZoneId

/**
 * Telefon takviminden hatırlatmalar (isteğe bağlı, READ_CALENDAR). Seçili takvimlerin önümüzdeki 26 saatlik
 * etkinlikleri okunur; etkinliğin kendi hatırlatma dakikası, yoksa 10 dk önce saate bildirim gider.
 * Reddedilen etkinlik atlanır. Plan yalnızca etkinlik kimliği ve zamanları tutar; başlık gönderim anında
 * takvimden yeniden okunur ve hiçbir yere yazılmaz. 30 dakikada bir ve takvim değişince yeniden okunur.
 */
object CalendarReminders {
    const val KIND_EVENT = "calendar"
    const val KIND_REFRESH = "calendarRefresh"
    private const val EXTRA_EVENT = "event"
    private const val EXTRA_BEGIN = "begin"
    private const val EVENT_BASE = 2000
    private const val MAX_FIRES = 60
    private const val REFRESH_CODE = 1999
    private const val HORIZON_MS = 26 * 60 * 60 * 1000L
    private const val REFRESH_MS = 30 * 60 * 1000L
    private const val OBSERVER_DELAY_MS = 5_000L

    private val main = Handler(Looper.getMainLooper())

    /** Takvim sorguları ana iş parçacığında yapılmaz; sırayla tek iş parçacığında. */
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "takvim").apply { isDaemon = true } }
    private var observer: ContentObserver? = null

    fun hasPermission(c: Context): Boolean = c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    class Calendar(val id: Long, val name: String, val account: String)

    /** Ekranda seçim için takvimler. Adlar yalnızca ekranda gösterilir. */
    fun calendars(c: Context): List<Calendar> {
        if (!hasPermission(c)) return emptyList()
        val out = ArrayList<Calendar>()
        try {
            c.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME),
                null, null, null,
            )?.use { cur -> while (cur.moveToNext()) out += Calendar(cur.getLong(0), cur.getString(1) ?: "", cur.getString(2) ?: "") }
        } catch (_: Exception) {
        }
        return out
    }

    private fun pending(c: Context, code: Int, eventId: Long, begin: Long, at: Long, create: Boolean): PendingIntent? {
        val i = Intent(c, ReminderReceiver::class.java)
            .setAction("com.che.ls05.CALENDAR.$code")
            .putExtra(Reminders.EXTRA_KIND, KIND_EVENT)
            .putExtra(EXTRA_EVENT, eventId)
            .putExtra(EXTRA_BEGIN, begin)
            .putExtra(Reminders.EXTRA_AT, at)
        val flags = PendingIntent.FLAG_IMMUTABLE or (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE)
        return PendingIntent.getBroadcast(c, code, i, flags)
    }

    private fun refreshPending(c: Context): PendingIntent = PendingIntent.getBroadcast(
        c, REFRESH_CODE,
        Intent(c, ReminderReceiver::class.java).setAction("com.che.ls05.CALENDAR.REFRESH").putExtra(Reminders.EXTRA_KIND, KIND_REFRESH),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancelAll(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        for (code in Prefs.calendarScheduled(c)) pending(c, code, 0, 0, 0, create = false)?.let { am.cancel(it); it.cancel() }
        Prefs.setCalendarScheduled(c, emptySet())
    }

    /** Etkinlikleri arka planda yeniden okur ve hatırlatmaları baştan kurar. Kapalıysa ya da izin yoksa hepsini iptal eder. */
    fun refresh(c: Context, done: (() -> Unit)? = null) {
        val app = c.applicationContext
        // Gözlemci kaydı ana iş parçacığında; sorgu ve zamanlama arka planda.
        if (Prefs.calendarEnabled(app) && hasPermission(app)) main.post { startObserver(app) } else main.post { stopObserver(app) }
        io.execute {
            try { refreshNow(app) } catch (e: Exception) { WatchHub.log("Takvim yenilenemedi: ${e.javaClass.simpleName}") }
            done?.invoke()
        }
    }

    private fun refreshNow(app: Context) {
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        cancelAll(app)
        if (!Prefs.calendarEnabled(app) || !hasPermission(app)) {
            am.cancel(refreshPending(app))
            return
        }
        val now = System.currentTimeMillis()
        val instances = try { readInstances(app, now) } catch (e: Exception) {
            WatchHub.log("Takvim okunamadı: ${e.javaClass.simpleName}")
            emptyList()
        }
        val sent = Prefs.calendarSent(app)
        val fires = CalendarPlan.plan(instances, now, HORIZON_MS, ZoneId.systemDefault()).filter { it.key !in sent }.take(MAX_FIRES)
        val codes = HashSet<Int>()
        fires.forEachIndexed { i, f ->
            val code = EVENT_BASE + i
            pending(app, code, f.eventId, f.beginMs, f.at, create = true)?.let { Reminders.setAlarm(app, f.at, it); codes += code }
        }
        Prefs.setCalendarScheduled(app, codes)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, now + REFRESH_MS, refreshPending(app))
        WatchHub.log("Takvim okundu: ${instances.size} etkinlik, ${codes.size} hatırlatma kuruldu.")
    }

    private fun readInstances(c: Context, now: Long): List<CalendarPlan.Instance> {
        val ids = Prefs.calendarIds(c)
        if (ids.isEmpty()) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now - 60 * 60 * 1000L)
            ContentUris.appendId(it, now + HORIZON_MS + 24 * 60 * 60 * 1000L)
        }.build()
        val raw = ArrayList<Triple<Long, Long, Pair<Boolean, Boolean>>>()
        c.contentResolver.query(
            uri,
            arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.SELF_ATTENDEE_STATUS, CalendarContract.Instances.CALENDAR_ID),
            null, null, null,
        )?.use { cur ->
            while (cur.moveToNext()) {
                if (cur.getLong(4) !in ids) continue
                val declined = cur.getInt(3) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED
                raw += Triple(cur.getLong(0), cur.getLong(1), (cur.getInt(2) == 1) to declined)
            }
        }
        val minutes = HashMap<Long, MutableList<Int>>()
        val eventIds = raw.map { it.first }.distinct()
        for (chunk in eventIds.chunked(50)) {
            c.contentResolver.query(
                CalendarContract.Reminders.CONTENT_URI,
                arrayOf(CalendarContract.Reminders.EVENT_ID, CalendarContract.Reminders.MINUTES, CalendarContract.Reminders.METHOD),
                "${CalendarContract.Reminders.EVENT_ID} IN (${chunk.joinToString(",")})", null, null,
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val m = cur.getInt(2)
                    if (m != CalendarContract.Reminders.METHOD_ALERT && m != CalendarContract.Reminders.METHOD_DEFAULT) continue
                    minutes.getOrPut(cur.getLong(0)) { ArrayList() } += cur.getInt(1)
                }
            }
        }
        return raw.map { (id, begin, f) -> CalendarPlan.Instance(id, begin, f.first, f.second, minutes[id] ?: emptyList()) }
    }

    /** Zamanı geldi: arka planda etkinlik okunur. [done] alıcının goAsync işini bitirir. */
    fun fire(c: Context, intent: Intent, done: () -> Unit) {
        io.execute {
            try { fireNow(c, intent) } catch (_: Exception) {} finally { done() }
        }
    }

    /** Etkinlik hâlâ var ve reddedilmemişse başlığı okunup saate gider. Başlık loga yazılmaz. */
    private fun fireNow(c: Context, intent: Intent) {
        val eventId = intent.getLongExtra(EXTRA_EVENT, -1)
        val begin = intent.getLongExtra(EXTRA_BEGIN, 0)
        val at = intent.getLongExtra(Reminders.EXTRA_AT, 0)
        val key = "$eventId:$begin:$at"
        val now = System.currentTimeMillis()
        when {
            !Prefs.calendarEnabled(c) || !hasPermission(c) -> return
            key in Prefs.calendarSent(c) -> return
            com.che.ls05.protocol.AlarmReminder.stale(at, now) -> {
                WatchHub.log("Takvim hatırlatması ${(now - at) / 60_000} dakika gecikti, gönderilmedi.")
                return
            }
        }
        val title = try { titleOf(c, eventId, begin) } catch (_: Exception) { null }
        if (title == null) {
            WatchHub.log("Takvim hatırlatması atlandı: etkinlik silinmiş, değişmiş ya da reddedilmiş.")
            refreshNow(c)
            return
        }
        Prefs.addCalendarSent(c, key)
        if (title.isNotBlank() && WatchHub.calendarDedupe.seenRecently(title, now)) {
            WatchHub.log("Takvim hatırlatması atlandı: takvim uygulamasının bildirimi 3 dk içinde zaten gönderildi.")
            return
        }
        WatchHub.calendarDedupe.mark(title, now)
        val time = Fmt.hm(Instant.ofEpochMilli(begin).atZone(ZoneId.systemDefault()).hour, Instant.ofEpochMilli(begin).atZone(ZoneId.systemDefault()).minute)
        WatchHub.log("Takvim hatırlatması saate gönderiliyor.")
        WatchHub.forward(
            c,
            WatchHub.Note(c.packageName, "Takvim", "Takvim · $time", title.ifBlank { "Etkinlik" }, MSG_TYPE_GENERIC, now,
                ttlMs = at + com.che.ls05.protocol.AlarmReminder.GRACE_MS - now, androidId = (eventId % 100_000).toInt() + 500_000, androidKey = "calendar:$key"),
        )
    }

    /** Etkinlik bu başlangıçla hâlâ varsa ve reddedilmemişse başlığı; yoksa null. */
    private fun titleOf(c: Context, eventId: Long, begin: Long): String? {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, begin - 60_000)
            ContentUris.appendId(it, begin + 60_000)
        }.build()
        c.contentResolver.query(
            uri,
            arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.BEGIN, CalendarContract.Instances.TITLE,
                CalendarContract.Instances.SELF_ATTENDEE_STATUS),
            null, null, null,
        )?.use { cur ->
            while (cur.moveToNext()) {
                if (cur.getLong(0) != eventId || cur.getLong(1) != begin) continue
                if (cur.getInt(3) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) return null
                return cur.getString(2) ?: ""
            }
        }
        return null
    }

    private fun startObserver(c: Context) {
        if (observer != null) return
        val o = object : ContentObserver(main) {
            private val run = Runnable { refresh(c) }
            override fun onChange(selfChange: Boolean) {
                main.removeCallbacks(run)
                main.postDelayed(run, OBSERVER_DELAY_MS)
            }
        }
        try {
            c.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, o)
            observer = o
        } catch (_: Exception) {
        }
    }

    private fun stopObserver(c: Context) {
        observer?.let { try { c.contentResolver.unregisterContentObserver(it) } catch (_: Exception) {} }
        observer = null
    }
}
