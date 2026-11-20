package com.che.ls05

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.che.ls05.protocol.AlarmReminder
import com.che.ls05.protocol.AlarmSetting
import com.che.ls05.protocol.Capabilities
import com.che.ls05.protocol.CustomReminder
import com.che.ls05.protocol.CustomReminders
import com.che.ls05.protocol.MSG_TYPE_GENERIC
import java.time.ZoneId

/**
 * Alarm hatırlatma notları (v0.6.1).
 *
 * Not telefonda saklanır. Alarm saati gelince telefon notu saate sıradan bildirim olarak gönderir.
 * Saatin kendi alarmı bundan bağımsızdır; not gitmese bile saat çalar.
 *
 * Tam zamanlı alarm izni varsa setExactAndAllowWhileIdle, yoksa setAndAllowWhileIdle kullanılır.
 * İkincisinde Android gönderimi geciktirebilir; 5 dakikayı aşan gecikmede not atılır.
 */
object Reminders {

    private const val REQUEST_BASE = 300
    /** Özel hatırlatıcılar: 400 + kimlik (kimlik 1..499). */
    private const val CUSTOM_BASE = 400
    const val MAX_CUSTOM = 499
    const val EXTRA_SLOT = "slot"
    const val EXTRA_AT = "at"
    const val EXTRA_KIND = "kind"
    const val EXTRA_ID = "id"
    const val KIND_CUSTOM = "custom"

    fun alarm(c: Context, slot: Int): AlarmSetting? =
        AlarmSetting.decode(SettingsStore.entry(c, AlarmSetting.key(slot)).desired)

    fun note(c: Context, slot: Int): String = AlarmReminder.cleanNote(Prefs.alarmNote(c, slot))

    /** Tam zamanlı alarm izni var mı. Android 12 öncesinde izin gerekmez. */
    fun canExact(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return c.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    }

    /** En az bir yuvada etkin not, etkin özel hatırlatıcı ya da takvim eşitlemesi var mı (izin uyarısı için). */
    fun anyActive(c: Context): Boolean =
        slots(c).any { AlarmReminder.active(alarm(c, it), note(c, it)) } ||
            custom(c).any { it.enabled } || Prefs.calendarEnabled(c)

    fun custom(c: Context): List<CustomReminder> = CustomReminders.decode(Prefs.customReminders(c))

    fun saveCustom(c: Context, list: List<CustomReminder>) {
        Prefs.setCustomReminders(c, CustomReminders.encode(list))
        rescheduleCustom(c)
    }

    private fun customPending(c: Context, id: Int, at: Long, create: Boolean): PendingIntent? {
        val i = Intent(c, ReminderReceiver::class.java)
            .setAction("com.che.ls05.CUSTOM.$id")
            .putExtra(EXTRA_KIND, KIND_CUSTOM)
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_AT, at)
        val flags = PendingIntent.FLAG_IMMUTABLE or
            (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE)
        return PendingIntent.getBroadcast(c, CUSTOM_BASE + id, i, flags)
    }

    /** Tam zamanlı alarm kurar; izin yoksa gecikebilir yolla. */
    fun setAlarm(c: Context, at: Long, pi: PendingIntent) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        try {
            if (canExact(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** Özel hatırlatıcıların zamanlamasını baştan kurar. Başlıklar loga yazılmaz. */
    fun rescheduleCustom(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        for (id in Prefs.scheduledCustom(c)) customPending(c, id, 0, create = false)?.let { am.cancel(it); it.cancel() }
        val scheduled = HashSet<Int>()
        for (r in custom(c)) {
            if (r.id !in 1..MAX_CUSTOM) continue
            val at = CustomReminders.next(r, now, zone) ?: continue
            val pi = customPending(c, r.id, at, create = true) ?: continue
            setAlarm(c, at, pi)
            scheduled += r.id
        }
        Prefs.setScheduledCustom(c, scheduled)
        if (scheduled.isNotEmpty()) WatchHub.log("Hatırlatıcı zamanlaması: ${scheduled.size} hatırlatıcı.")
    }

    fun fireCustom(c: Context, id: Int, at: Long) {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val r = custom(c).firstOrNull { it.id == id }
        when {
            r == null || !CustomReminders.stillValid(r, at, zone) -> WatchHub.log("Hatırlatıcı $id: ayar değişmiş, eski zamanlama atıldı.")
            AlarmReminder.stale(at, now) -> WatchHub.log("Hatırlatıcı $id: ${(now - at) / 60_000} dakika gecikti, gönderilmedi.")
            Prefs.customSentAt(c, id) == at -> WatchHub.log("Hatırlatıcı $id bu an için zaten gönderildi.")
            else -> {
                Prefs.setCustomSentAt(c, id, at)
                WatchHub.log("Hatırlatıcı $id saate gönderiliyor.")
                WatchHub.forward(
                    c,
                    WatchHub.Note(c.packageName, "Hatırlatıcı", "Hatırlatıcı", r.title, MSG_TYPE_GENERIC, now,
                        ttlMs = at + AlarmReminder.GRACE_MS - now, androidId = 400_000 + id, androidKey = "reminder:$id:$at"),
                )
            }
        }
        rescheduleCustom(c)
    }

    /** Etkin saatin not alabilen yuvaları (alarm notu desteklenmiyorsa boş). */
    private fun slots(c: Context): IntRange {
        val caps = WatchRegistry.caps(c)
        return if (caps.alarmNote) 1..caps.alarmSlots else IntRange.EMPTY
    }

    private fun pending(c: Context, slot: Int, at: Long, create: Boolean): PendingIntent? {
        val i = Intent(c, ReminderReceiver::class.java)
            .setAction("com.che.ls05.REMINDER.$slot")
            .putExtra(EXTRA_SLOT, slot)
            .putExtra(EXTRA_AT, at)
        val flags = PendingIntent.FLAG_IMMUTABLE or
            (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE)
        return PendingIntent.getBroadcast(c, REQUEST_BASE + slot, i, flags)
    }

    /**
     * Etkin saatin yuvalarının zamanlamasını baştan kurar. Eski zamanlama her durumda iptal edilir; iptal bütün
     * saat türlerinin en çok yuva sayısına kadar yapılır (saat değişince eski saatin notu kalmasın).
     */
    fun reschedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val exact = canExact(c)
        var count = 0
        val active = slots(c)
        for (slot in 1..Capabilities.MAX_ALARM_SLOTS) {
            pending(c, slot, 0, create = false)?.let { am.cancel(it); it.cancel() }
            if (slot !in active) continue
            val at = AlarmReminder.next(alarm(c, slot), note(c, slot), now, zone) ?: continue
            val pi = pending(c, slot, at, create = true) ?: continue
            try {
                if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                count++
            } catch (e: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                count++
            }
        }
        if (count > 0) WatchHub.log("Alarm notu zamanlaması: $count not (${if (exact) "tam zamanlı" else "gecikebilir"}).")
        rescheduleCustom(c)
        CalendarReminders.refresh(c)
    }

    /** Zamanlama tetiklendi. Geçerlilik, gecikme ve aynı gün kontrolünden sonra not saate iletilir. */
    fun fire(c: Context, slot: Int, at: Long) {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val a = alarm(c, slot)
        val n = note(c, slot)
        val day = AlarmReminder.dayKey(at, zone)
        when {
            !AlarmReminder.stillValid(a, n, at, zone) ->
                WatchHub.log("Alarm $slot notu: ayar değişmiş, eski zamanlama atıldı.")
            AlarmReminder.stale(at, now) ->
                WatchHub.log("Alarm $slot notu: ${(now - at) / 60_000} dakika gecikti, gönderilmedi.")
            Prefs.reminderSentDay(c, slot) == day ->
                WatchHub.log("Alarm $slot notu bugün zaten işlendi.")
            else -> {
                Prefs.setReminderSentDay(c, slot, day)
                val ttl = at + AlarmReminder.GRACE_MS - now
                WatchHub.forward(
                    c,
                    WatchHub.Note(c.packageName, "Alarm $slot notu", "Alarm", n, MSG_TYPE_GENERIC, now, ttlMs = ttl),
                )
            }
        }
        reschedule(c)
    }
}

/** Hatırlatma zamanlaması tetiklenince çalışır. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.getStringExtra(Reminders.EXTRA_KIND)) {
            Reminders.KIND_CUSTOM -> {
                val id = intent.getIntExtra(Reminders.EXTRA_ID, 0)
                val at = intent.getLongExtra(Reminders.EXTRA_AT, 0L)
                if (id > 0 && at > 0L) Reminders.fireCustom(app, id, at)
                return
            }
            CalendarReminders.KIND_EVENT -> { CalendarReminders.fire(app, intent); return }
            CalendarReminders.KIND_REFRESH -> { CalendarReminders.refresh(app); return }
        }
        val slot = intent.getIntExtra(Reminders.EXTRA_SLOT, 0)
        val at = intent.getLongExtra(Reminders.EXTRA_AT, 0L)
        if (slot !in 1..Capabilities.MAX_ALARM_SLOTS || at <= 0L) return
        Reminders.fire(context.applicationContext, slot, at)
    }
}
