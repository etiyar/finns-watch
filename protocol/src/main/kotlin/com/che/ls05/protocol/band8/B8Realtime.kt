package com.che.ls05.protocol.band8

/*
 * Canlı nabız ve adım akışı (docs/BAND8_PROTOKOL.md 7.1, güçlü kanıt):
 * 8/45 başlat, 8/46 durdur, band açıkken 8/47 olayları gönderir.
 * Health alan 39: 1 günlük birikmiş adım, 2 kalori, 4 nabız. Nabız 10 ya da altıysa geçersizdir.
 */
object B8Realtime {
    const val START = 45
    const val STOP = 46
    const val EVENT = 47

    /** Health içindeki canlı ölçüm alanı. */
    private const val F_STATS = 39

    /** Geçerli kabul edilen nabız aralığı (vuruş/dk). */
    const val MIN_BPM = 11
    const val MAX_BPM = 250

    fun start(): ByteArray = B8Messages.command(B8Type.HEALTH, START).toByteArray()

    fun stop(): ByteArray = B8Messages.command(B8Type.HEALTH, STOP).toByteArray()

    fun isEvent(c: B8Command): Boolean = c.matches(B8Type.HEALTH, EVENT)

    data class Stats(val steps: Int?, val heartRate: Int?)

    /** 8/47 olayını okur; başka komut ya da beklenmeyen biçimde null. */
    fun parse(c: B8Command): Stats? {
        if (!isEvent(c)) return null
        val s = c.body(B8Type.F_HEALTH)?.message(F_STATS) ?: return null
        return Stats(s.int(1), s.int(4))
    }

    /** Geçerli nabız ya da null. */
    fun validHeartRate(bpm: Int?): Int? = bpm?.takeIf { it in MIN_BPM..MAX_BPM }
}

/** Sleep as Android titreşim desenleri (2/59 üstünden; 2/59 cihazda doğrulandı, build 191). */
object B8SaaVibration {
    /** Alarm: üç uzun vuruş, yaklaşık 3 sn. 5 sn'de bir yinelenir. */
    val ALARM: List<Pair<Boolean, Int>> = listOf(true to 700, false to 300, true to 700, false to 300, true to 700)

    /** İpucu (lüsid rüya, horlama): [repeat] kısa vuruş. */
    fun hint(repeat: Int): List<Pair<Boolean, Int>> {
        val n = repeat.coerceIn(1, 5)
        val out = ArrayList<Pair<Boolean, Int>>()
        repeat(n) { i ->
            if (i > 0) out += false to 400
            out += true to 200
        }
        return out
    }
}
