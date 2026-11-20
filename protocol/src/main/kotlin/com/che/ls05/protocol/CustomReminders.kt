package com.che.ls05.protocol

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/*
 * Özel hatırlatıcılar ve takvim hatırlatmaları (v0.15.0-beta4). Zamanı gelince telefon, etkin saate sıradan
 * bildirim gönderir. Zamanlama ve geç kalma kuralları alarm notundakiyle aynı ([AlarmReminder.GRACE_MS]).
 * Başlıklar loga yazılmaz.
 */

enum class ReminderRepeat { ONCE, DAILY, WEEKDAYS, DAYS, EVERY_N }

/**
 * [days]: bit 0 pazartesi ... bit 6 pazar (yalnızca [ReminderRepeat.DAYS]). [everyDays]: yalnızca EVERY_N,
 * başlangıç gününden sayılır. [startDate]: ilk olası gün (bir kezlikte o gün).
 */
data class CustomReminder(
    val id: Int,
    val title: String,
    val startDate: LocalDate,
    val hour: Int,
    val minute: Int,
    val repeat: ReminderRepeat,
    val days: Int,
    val everyDays: Int,
    val enabled: Boolean,
)

object CustomReminders {
    const val MAX_TITLE = 60
    private const val HEADER = "# finnswatch hatırlatıcılar v1"
    private const val SEARCH_DAYS = 400L

    fun cleanTitle(s: String): String = s.replace(Regex("\\s+"), " ").trim().take(MAX_TITLE)

    fun matches(r: CustomReminder, date: LocalDate): Boolean {
        if (date.isBefore(r.startDate)) return false
        return when (r.repeat) {
            ReminderRepeat.ONCE -> date == r.startDate
            ReminderRepeat.DAILY -> true
            ReminderRepeat.WEEKDAYS -> date.dayOfWeek.value <= 5
            ReminderRepeat.DAYS -> r.days and (1 shl (date.dayOfWeek.value - 1)) != 0
            ReminderRepeat.EVERY_N -> ChronoUnit.DAYS.between(r.startDate, date) % r.everyDays.coerceAtLeast(1) == 0L
        }
    }

    /** [afterMs]'den kesin sonraki ilk an; yoksa null. */
    fun next(r: CustomReminder, afterMs: Long, zone: ZoneId): Long? {
        if (!r.enabled || r.hour !in 0..23 || r.minute !in 0..59) return null
        if (r.repeat == ReminderRepeat.DAYS && r.days and 0x7F == 0) return null
        val afterDate = Instant.ofEpochMilli(afterMs).atZone(zone).toLocalDate()
        var date = if (afterDate.isBefore(r.startDate)) r.startDate else afterDate
        val last = date.plusDays(SEARCH_DAYS)
        while (!date.isAfter(last)) {
            if (matches(r, date)) {
                val t = ZonedDateTime.of(date.year, date.monthValue, date.dayOfMonth, r.hour, r.minute, 0, 0, zone).toInstant().toEpochMilli()
                if (t > afterMs) return t
            }
            if (r.repeat == ReminderRepeat.ONCE && date >= r.startDate) return null
            date = date.plusDays(1)
        }
        return null
    }

    fun stillValid(r: CustomReminder, scheduledMs: Long, zone: ZoneId): Boolean = next(r, scheduledMs - 1, zone) == scheduledMs

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
    private fun unesc(s: String) = buildString {
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                append(when (s[i + 1]) { 't' -> '\t'; 'n' -> '\n'; else -> s[i + 1] }); i += 2
            } else { append(s[i]); i++ }
        }
    }

    fun encode(list: List<CustomReminder>): String = buildString {
        append(HEADER).append('\n')
        for (r in list) append(listOf("C", r.id, if (r.enabled) 1 else 0, esc(r.title), r.startDate, r.hour, r.minute, r.repeat.name, r.days, r.everyDays).joinToString("\t")).append('\n')
    }

    fun decode(text: String?): List<CustomReminder> {
        if (text == null || !text.startsWith(HEADER)) return emptyList()
        return text.split('\n').mapNotNull { line ->
            val p = line.split('\t')
            if (p.size != 10 || p[0] != "C") return@mapNotNull null
            try {
                CustomReminder(p[1].toInt(), unesc(p[3]), LocalDate.parse(p[4]), p[5].toInt(), p[6].toInt(),
                    ReminderRepeat.valueOf(p[7]), p[8].toInt(), p[9].toInt().coerceIn(1, 365), p[2] == "1")
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Takvim hatırlatmalarının planı. Etkinliğin kendi hatırlatma dakikaları, yoksa 10 dk. Reddedilen etkinlik atlanır.
 * Tüm gün etkinliği (başlangıç UTC gece yarısı) dakikası yoksa o gün yerel 09:00'da hatırlatılır.
 * Planda başlık yoktur; başlık gönderim anında takvimden yeniden okunur.
 */
object CalendarPlan {
    const val DEFAULT_MINUTES = 10
    const val ALL_DAY_HOUR = 9

    class Instance(val eventId: Long, val beginMs: Long, val allDay: Boolean, val declined: Boolean, val reminderMinutes: List<Int>)

    data class Fire(val key: String, val eventId: Long, val beginMs: Long, val at: Long)

    fun plan(instances: List<Instance>, nowMs: Long, horizonMs: Long, zone: ZoneId = ZoneOffset.UTC): List<Fire> {
        val out = ArrayList<Fire>()
        for (i in instances) {
            if (i.declined) continue
            val base = if (i.allDay) {
                Instant.ofEpochMilli(i.beginMs).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
            } else i.beginMs
            val ats = if (i.reminderMinutes.isEmpty()) {
                listOf(if (i.allDay) base + ALL_DAY_HOUR * 3_600_000L else base - DEFAULT_MINUTES * 60_000L)
            } else i.reminderMinutes.filter { it >= 0 }.map { base - it * 60_000L }
            for (at in ats.distinct()) {
                if (at <= nowMs || at > nowMs + horizonMs) continue
                out += Fire("${i.eventId}:${i.beginMs}:$at", i.eventId, i.beginMs, at)
            }
        }
        return out.sortedBy { it.at }
    }
}
