package com.che.ls05.protocol.band8

import com.che.ls05.protocol.UteWeather

/*
 * Band 8 ayar ve deneme komutları. Gelişmiş > Band 8 denemeleri yalnızca elle gönderir.
 * Yapılar docs/BAND8_PROTOKOL.md 6.6, 7.10, 8.1, 8.3, 8.5'e göre. Kayıtla doğrulanmayanlar orada "tahmin"
 * ya da "güçlü kanıt" diye işaretli; cihazdaki sonuç docs/TEST_RESULTS.md'ye yazılır.
 */

private fun cmd(type: Int, subtype: Int, field: Int, body: PbWriter): ByteArray =
    B8Messages.command(type, subtype).message(field, body).toByteArray()

private fun hourMinute(h: Int, m: Int): PbWriter = PbWriter().uint(1, h).uint(2, m)

/** Cevapsız arama: ayrı komut bilinmiyor. "phone" paketinden normal bildirim olarak denenir (tahmin, belge 6.6). */
object B8Calls {
    const val MISSED_CALL_ID = 1
    const val MISSED_CALL_TEXT = "Cevapsız arama"

    /** 20.11.2026: bantta göründü. Gerçek cevapsız aramada [id] telefondaki bildirimin kimliğidir. */
    fun missedCall(caller: String, timestamp: String, id: Int = MISSED_CALL_ID): ByteArray = B8Messages.notification(
        B8Notification(
            packageName = B8Messages.CALL_PACKAGE, appName = B8Messages.CALL_PACKAGE,
            title = caller, body = MISSED_CALL_TEXT, timestamp = timestamp,
            id = id, key = "missed:$id", isCall = false,
        )
    )
}

/** Banddaki bir alarm. [repeat]: 0 bir kez, 1 her gün, 5 haftalık. [smart]: 1 akıllı, 2 normal. */
data class B8Alarm(val id: Int, val hour: Int, val minute: Int, val repeat: Int, val enabled: Boolean, val smart: Int, val dayFlags: Int? = null)

/** 17/0 yanıtı: alarmlar ve bandın izin verdiği en çok alarm sayısı. Saatler loga yazılmaz. */
data class B8AlarmList(val alarms: List<B8Alarm>, val max: Int?) {
    val ids: List<Int> get() = alarms.map { it.id }
}

/**
 * Kullanıcı alarmı. [days]: bit 0 pazartesi ... bit 6 pazar; 0 bir kez, 0x7F her gün, diğerleri haftalık.
 * Akıllı alarm yok (normal).
 */
data class B8AlarmSpec(val hour: Int, val minute: Int, val days: Int, val enabled: Boolean)

/** Alarm: 17/0 liste, 17/1 oluştur, 17/2 düzenle, 17/4 sil (belge 8.1). Kimlikleri band verir. */
object B8Alarms {
    private const val ONCE = 0
    private const val SMART = 1
    private const val NORMAL = 2

    fun list(): ByteArray = B8Messages.command(B8Type.SCHEDULE, 0).toByteArray()

    fun parseList(c: B8Command): B8AlarmList? {
        if (!c.matches(B8Type.SCHEDULE, 0)) return null
        val alarms = c.body(B8Type.F_SCHEDULE)?.message(1) ?: return null
        val list = alarms.messages(1).mapNotNull { a ->
            val id = a.int(1) ?: return@mapNotNull null
            val d = a.message(2)
            val t = d?.message(2)
            // Band sıfır değerli alanları yazmayabilir: yoksa 0 (saat 0, dakika 0, bir kez, kapalı, akıllı değil).
            B8Alarm(id, t?.int(1) ?: 0, t?.int(2) ?: 0, d?.int(3) ?: ONCE, d?.bool(5) ?: false, d?.int(7) ?: 0, d?.int(4))
        }
        return B8AlarmList(list, alarms.int(2))
    }

    /** Log için: her alarmın kimliği ve alan biçimi (numara + tel türü). Saat ve değer yazılmaz. */
    fun describe(c: B8Command): List<String> {
        val alarms = c.body(B8Type.F_SCHEDULE)?.message(1) ?: return emptyList()
        return alarms.messages(1).map { a ->
            val d = a.bytes(2)
            val t = d?.let { PbMessage.parseOrNull(it) }?.bytes(2)
            "kimlik=${a.int(1) ?: "yok"} ayrıntı=[${d?.let { B8RawSetting(0, it).shape() } ?: "yok"}] saat=[${t?.let { B8RawSetting(0, it).shape() } ?: "yok"}]"
        }
    }

    /** Deneme düğmesinin kurduğu alarmın imzası: bir kez, akıllı değil, gün bayrağı yok. */
    fun matchesProbe(a: B8Alarm): Boolean = a.repeat == ONCE && a.smart != SMART && (a.dayFlags ?: 0) == 0

    /** Deneme imzasına uyan bütün alarmlar ("Test alarmlarını temizle" adayları). */
    fun probeCandidates(l: B8AlarmList): List<B8Alarm> = l.alarms.filter { matchesProbe(it) }

    /** Belirli saatte kurulan deneme alarmı; birden çok varsa en büyük kimlik (en son eklenen). */
    fun findTest(l: B8AlarmList, hour: Int, minute: Int): B8Alarm? =
        l.alarms.filter { matchesProbe(it) && it.hour == hour && it.minute == minute }.maxByOrNull { it.id }

    /** Bir kez çalan, açık, normal (akıllı olmayan) alarm. */
    private fun details(hour: Int, minute: Int): PbWriter =
        PbWriter().message(2, hourMinute(hour, minute)).uint(3, ONCE).bool(5, true).uint(7, NORMAL)

    private const val DAILY = 1
    private const val WEEKLY = 5

    private fun details(a: B8AlarmSpec): PbWriter {
        val d = PbWriter().message(2, hourMinute(a.hour, a.minute))
        when (a.days and 0x7F) {
            0 -> d.uint(3, ONCE)
            0x7F -> d.uint(3, DAILY)
            else -> d.uint(3, WEEKLY).uint(4, a.days and 0x7F)
        }
        return d.bool(5, a.enabled).uint(7, NORMAL)
    }

    fun create(a: B8AlarmSpec): ByteArray =
        cmd(B8Type.SCHEDULE, 1, B8Type.F_SCHEDULE, PbWriter().message(2, details(a)))

    fun edit(id: Int, a: B8AlarmSpec): ByteArray =
        cmd(B8Type.SCHEDULE, 2, B8Type.F_SCHEDULE, PbWriter().message(3, PbWriter().uint(1, id).message(2, details(a))))

    /** Listedeki alarmın gün maskesi (bir kez 0, her gün 0x7F, haftalıkta bayraklar). */
    fun days(a: B8Alarm): Int = when (a.repeat) {
        DAILY -> 0x7F
        WEEKLY -> (a.dayFlags ?: 0) and 0x7F
        else -> 0
    }

    fun create(hour: Int, minute: Int): ByteArray =
        cmd(B8Type.SCHEDULE, 1, B8Type.F_SCHEDULE, PbWriter().message(2, details(hour, minute)))

    fun edit(id: Int, hour: Int, minute: Int): ByteArray =
        cmd(B8Type.SCHEDULE, 2, B8Type.F_SCHEDULE, PbWriter().message(3, PbWriter().uint(1, id).message(2, details(hour, minute))))

    fun delete(ids: List<Int>): ByteArray {
        val d = PbWriter()
        for (id in ids) d.uint(1, id)
        return cmd(B8Type.SCHEDULE, 4, B8Type.F_SCHEDULE, PbWriter().message(5, d))
    }

    fun plusMinutes(hour: Int, minute: Int, add: Int): Pair<Int, Int> {
        val total = ((hour * 60 + minute + add) % 1440 + 1440) % 1440
        return total / 60 to total % 60
    }
}

/** Rahatsız etme: 2/23 anlık (belge 8.3, Notify'ın kullandığı yapı) ve 17/9 zamanlı uyku modu. */
object B8Dnd {
    fun now(on: Boolean): ByteArray =
        cmd(B8Type.SYSTEM, 23, B8Type.F_SYSTEM, PbWriter().message(11, PbWriter().uint(1, if (on) 0 else 2)))

    fun sleepSchedule(enabled: Boolean, startH: Int, startM: Int, endH: Int, endM: Int): ByteArray {
        val range = PbWriter().message(1, hourMinute(startH, startM)).message(2, hourMinute(endH, endM)).uint(3, 0)
        return cmd(B8Type.SCHEDULE, 9, B8Type.F_SCHEDULE, PbWriter().message(9, PbWriter().bool(1, enabled).message(2, range)))
    }
}

/**
 * Banddan okunan, alan numarası henüz kayıtla doğrulanmamış bir ayar mesajı. [shape] yalnızca alan numarası
 * ve tel türünü verir (v varint, m uzunluklu, f sabit uzunluk); değer yazmaz.
 */
class B8RawSetting(val field: Int, val bytes: ByteArray) {
    fun shape(): String = (PbMessage.parseOrNull(bytes)?.fields ?: emptyList()).joinToString(" ") {
        it.number.toString() + when (it.wire) {
            PbWriter.WIRE_VARINT -> "v"
            PbWriter.WIRE_LEN -> "m"
            else -> "f"
        }
    }
}

/**
 * Hareketsizlik (ayakta durma) uyarısı: 8/12 oku, 8/13 yaz (belge 7.10). Health içindeki alan numarası
 * kayıtla doğrulanmadığı için önce okunur; yazarken aynı alan kullanılır ve yalnızca 1. alan (açık mı)
 * değiştirilir. Saat aralıkları olduğu gibi geri gider.
 */
object B8Standing {
    fun read(): ByteArray = B8Messages.command(B8Type.HEALTH, 12).toByteArray()

    fun parse(c: B8Command): B8RawSetting? {
        if (!c.matches(B8Type.HEALTH, 12)) return null
        val h = c.body(B8Type.F_HEALTH) ?: return null
        val msgs = h.fields.filter { it.wire == PbWriter.WIRE_LEN && it.data != null }
        val one = msgs.singleOrNull() ?: return null
        return B8RawSetting(one.number, one.data!!)
    }

    /** Okunan ayarın açık mı ve saat aralığı (alan 1, 2, 3; tahmin). Biçim beklenmedikse null. */
    data class Window(val enabled: Boolean, val startH: Int, val startM: Int, val endH: Int, val endM: Int)

    fun window(raw: B8RawSetting): Window? {
        val m = PbMessage.parseOrNull(raw.bytes) ?: return null
        val a = m.message(2) ?: return null
        val b = m.message(3) ?: return null
        return Window(m.bool(1) ?: false, a.int(1) ?: 0, a.int(2) ?: 0, b.int(1) ?: 0, b.int(2) ?: 0)
    }

    /** Açık mı ve başlangıç/bitiş saati değişir; öğle arası gibi diğer alanlar olduğu gibi geri gider. */
    fun writeWindow(raw: B8RawSetting, enabled: Boolean, startH: Int, startM: Int, endH: Int, endM: Int): ByteArray? =
        rewrite(raw, mapOf(1 to null, 2 to hourMinute(startH, startM), 3 to hourMinute(endH, endM)), enabled)

    private fun rewrite(raw: B8RawSetting, replace: Map<Int, PbWriter?>, enabled: Boolean): ByteArray? {
        val m = PbMessage.parseOrNull(raw.bytes) ?: return null
        val w = PbWriter().bool(1, enabled)
        for ((n, v) in replace) if (n != 1 && v != null) w.message(n, v)
        for (f in m.fields) {
            if (f.number in replace.keys) continue
            when (f.wire) {
                PbWriter.WIRE_VARINT -> w.uint(f.number, f.value)
                PbWriter.WIRE_LEN -> w.bytes(f.number, f.data ?: return null)
                PbWriter.WIRE_I32 -> w.float(f.number, java.lang.Float.intBitsToFloat(f.value.toInt()))
                else -> return null
            }
        }
        return cmd(B8Type.HEALTH, 13, B8Type.F_HEALTH, PbWriter().message(raw.field, w))
    }

    /** I64 gibi beklenmeyen bir alan varsa null: değiştirmeden göndermek yerine hiç gönderilmez. */
    fun write(raw: B8RawSetting, enabled: Boolean): ByteArray? {
        val m = PbMessage.parseOrNull(raw.bytes) ?: return null
        val w = PbWriter().bool(1, enabled)
        for (f in m.fields) {
            if (f.number == 1) continue
            when (f.wire) {
                PbWriter.WIRE_VARINT -> w.uint(f.number, f.value)
                PbWriter.WIRE_LEN -> w.bytes(f.number, f.data ?: return null)
                PbWriter.WIRE_I32 -> w.float(f.number, java.lang.Float.intBitsToFloat(f.value.toInt()))
                else -> return null
            }
        }
        return cmd(B8Type.HEALTH, 13, B8Type.F_HEALTH, PbWriter().message(raw.field, w))
    }
}

/** Hava durumu meta bilgisi. Konum adı banda gider, loga yazılmaz. [key] sabit bir koddur. */
data class B8WeatherMeta(val publishedIso: String, val city: String, val key: String)

/** Bir günlük tahmin. Kodlar Xiaomi kodudur ([B8Weather.fromUte]). Sıcaklık °C. */
data class B8ForecastDay(val dayCode: Int, val nightCode: Int, val lowC: Int, val highC: Int)

/** Hava durumu: 10/7 konum ekle, 10/0 anlık, 10/1 günlük tahmin (belge 8.5; alanlar güçlü kanıt, kodlar tahmin). */
object B8Weather {
    const val CELSIUS = "℃"

    private fun meta(m: B8WeatherMeta): PbWriter = PbWriter()
        .string(1, m.publishedIso).string(2, m.city).string(3, m.city).string(4, m.key).bool(5, false)

    private fun value(unit: String, v: Int): PbWriter = PbWriter().string(1, unit).sint(2, v)

    private fun range(from: Int, to: Int): PbWriter = PbWriter().sint(1, from).sint(2, to)

    fun addLocation(key: String, city: String): ByteArray =
        cmd(B8Type.WEATHER, 7, B8Type.F_WEATHER, PbWriter().message(5, PbWriter().string(1, key).string(2, city)))

    fun current(m: B8WeatherMeta, xiaomiCode: Int, temperatureC: Int): ByteArray {
        val w = PbWriter().message(1, meta(m)).uint(2, xiaomiCode).message(3, value(CELSIUS, temperatureC))
        return cmd(B8Type.WEATHER, 0, B8Type.F_WEATHER, PbWriter().message(1, w))
    }

    fun forecast(m: B8WeatherMeta, days: List<B8ForecastDay>): ByteArray {
        val entries = PbWriter()
        for (d in days) {
            entries.message(1, PbWriter()
                .message(2, range(d.dayCode, d.nightCode))
                .message(3, range(d.lowC, d.highC))
                .string(4, CELSIUS))
        }
        val f = PbWriter().message(1, meta(m)).message(2, entries)
        return cmd(B8Type.WEATHER, 1, B8Type.F_WEATHER, PbWriter().message(2, f))
    }

    /** Uygulamanın ortak hava kodundan (WeatherCodes) Xiaomi koduna. Eşleme tahmindir, cihazda bakılacak. */
    fun fromUte(code: Int): Int = when (code) {
        UteWeather.SUNNY -> 0
        UteWeather.PARTLY_CLOUDY -> 1
        UteWeather.CLOUDY -> 2
        UteWeather.SHOWERS -> 3
        UteWeather.THUNDER -> 4
        UteWeather.SLEET -> 6
        UteWeather.DRIZZLE -> 7
        UteWeather.HEAVY_RAIN -> 9
        UteWeather.SNOW -> 14
        UteWeather.HAZE -> 18
        UteWeather.DUST -> 29
        else -> 2
    }
}

/**
 * Müzik: 18/1, Music alan 20 > 1 MusicInfo (tahmin): 1 durum (0 yok, 1 çalıyor, 2 duraklatıldı), 2 ses (yüzde),
 * 4 şarkı, 5 sanatçı, 6 konum (sn), 7 süre (sn). 20.11.2026: kendiliğinden giden 18/1 ACK aldı ama görünmedi;
 * band müzik ekranını açınca ve tuşlara basınca 18/0 gönderdi. 18/0 durum isteği sayılır, yanıt olarak 18/1 gider.
 * Tuş olayı varsa (tahmin) Music alan 2 > 1 tuş, 2 ses. Gelen 18/x yapısı loga yalnızca alan biçimiyle yazılır.
 */
object B8Music {
    const val STATE_NONE = 0
    const val STATE_PLAYING = 1
    const val STATE_PAUSED = 2

    /** Tuş kodları (tahmin): 0 çal, 1 duraklat, 3 önceki, 4 sonraki, 5 ses ([volume] yüzde). */
    data class MediaKey(val key: Int, val volume: Int?)

    const val KEY_PLAY = 0
    const val KEY_PAUSE = 1
    const val KEY_PREVIOUS = 3
    const val KEY_NEXT = 4
    const val KEY_VOLUME = 5

    fun info(track: String?, artist: String?, state: Int, durationSec: Int, positionSec: Int, volumePercent: Int): ByteArray {
        val i = PbWriter().uint(1, state).uint(2, volumePercent.coerceIn(0, 100))
        track?.let { i.string(4, B8Text.forBand(it)) }
        artist?.let { i.string(5, B8Text.forBand(it)) }
        if (state != STATE_NONE) i.uint(6, positionSec.coerceAtLeast(0)).uint(7, durationSec.coerceAtLeast(0))
        return cmd(B8Type.MUSIC, 1, B8Type.F_MUSIC, PbWriter().message(1, i))
    }

    fun info(track: String, artist: String, playing: Boolean, durationSec: Int, volumePercent: Int): ByteArray =
        info(track, artist, if (playing) STATE_PLAYING else STATE_PAUSED, durationSec, 0, volumePercent)

    /**
     * Tuş olayı: Music alan 2 içinde (1 tuş, 2 ses) ya da 20.11.2026'da görüldüğü gibi doğrudan Music alanlarında
     * (1v tuş, 2v ses; band ses ekranında 18/2). Durum bilgisinde alan 1 bir mesajdır, tuş sayılmaz.
     */
    fun mediaKey(c: B8Command): MediaKey? {
        if (c.type != B8Type.MUSIC) return null
        val m = c.body(B8Type.F_MUSIC) ?: return null
        m.message(2)?.let { k -> return MediaKey(k.int(1) ?: return null, k.int(2)) }
        val key = m.int(1) ?: return null
        return MediaKey(key, m.int(2))
    }

    /** Banddan gelen 18/0, içinde tuş olayı yoksa: güncel müzik bilgisi isteniyor. */
    fun isStateRequest(c: B8Command): Boolean = c.matches(B8Type.MUSIC, 0) && mediaKey(c) == null

    /** Log için: Music mesajının alan biçimi, iç mesajlar bir düzey açılır. Metin ve değer yazılmaz. */
    fun describe(c: B8Command): String {
        val m = c.body(B8Type.F_MUSIC) ?: return "yok"
        return m.fields.joinToString(" ") { f ->
            when (f.wire) {
                PbWriter.WIRE_VARINT -> "${f.number}v"
                PbWriter.WIRE_LEN -> {
                    val inner = f.data?.let { PbMessage.parseOrNull(it) }
                    if (inner != null && inner.fields.isNotEmpty() && f.data.isNotEmpty() && looksLikeMessage(f.data))
                        "${f.number}m{${B8RawSetting(0, f.data).shape()}}" else "${f.number}m"
                }
                else -> "${f.number}f"
            }
        }
    }

    /** UTF-8 metni yanlışlıkla mesaj diye açmamak için kaba denetim: metin baytları genelde yazdırılabilir. */
    private fun looksLikeMessage(b: ByteArray): Boolean = b.firstOrNull()?.let { (it.toInt() and 0x07) <= 5 && (it.toInt() and 0xFF) >= 0x08 } ?: false
}

/** Titreşim deseni dene: 2/59, System alan 41 > tekrarlı 1 { 1 titreşim (1/0), 2 süre ms } (belge 6.9, güçlü kanıt). */
object B8Vibration {
    val TEST: List<Pair<Boolean, Int>> = listOf(true to 400, false to 200, true to 400, false to 200, true to 800)

    fun pattern(steps: List<Pair<Boolean, Int>>): ByteArray {
        val body = PbWriter()
        for ((on, ms) in steps) body.message(1, PbWriter().uint(1, if (on) 1 else 0).uint(2, ms.coerceIn(0, 10_000)))
        return cmd(B8Type.SYSTEM, 59, B8Type.F_SYSTEM, PbWriter().message(41, body))
    }
}
