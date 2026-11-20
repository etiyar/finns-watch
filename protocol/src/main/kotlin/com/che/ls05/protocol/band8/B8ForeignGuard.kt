package com.che.ls05.protocol.band8

/**
 * Bantla aynı anda konuşan ikinci bir istemciyi (Mi Fitness, Notify) fark eder (#16).
 *
 * Android'de aynı BLE bağlantısını birden çok uygulama paylaşır. Bandın bildirimleri hepsine gider.
 * Bu yüzden başka bir uygulamanın auth yanıtları ve onayları bize de ulaşır:
 * - Bizim başlatmadığımız şifresiz 1/26 ya da 1/27 yanıtı.
 * - Kanalımız beklemiyorken gelen onay, "hazırım" ya da "hepsi alındı".
 *
 * Android'e bağlı değil. Zamanı çağıran verir.
 */
class B8ForeignGuard {
    private var awaitingAuth: Int? = null
    private val unexpected = ArrayDeque<Long>()

    /** Biz 1/[subtype] gönderdik; aynı alt türde tek bir yanıt bekliyoruz. */
    fun expectAuth(subtype: Int) {
        awaitingAuth = subtype
    }

    /** Şifresiz 1/26 ya da 1/27 geldi. Beklediğimiz yanıt değilse true (yabancı). */
    fun onAuthReply(subtype: Int): Boolean {
        if (awaitingAuth == subtype) {
            awaitingAuth = null
            return false
        }
        return true
    }

    /** Beklenmeyen kontrol çerçevesi. Pencere içinde ikincisi gelince true. Tek sapma tetiklemez. */
    fun onUnexpectedControl(nowMs: Long): Boolean {
        while (unexpected.isNotEmpty() && nowMs - unexpected.first() > CONTROL_WINDOW_MS) unexpected.removeFirst()
        unexpected.addLast(nowMs)
        return unexpected.size >= CONTROL_THRESHOLD
    }

    companion object {
        const val CONTROL_WINDOW_MS = 30_000L
        const val CONTROL_THRESHOLD = 2
        const val RECONNECT_COOLDOWN_MS = 5 * 60_000L

        fun reconnectAllowed(nowMs: Long, lastReconnectMs: Long?): Boolean =
            lastReconnectMs == null || nowMs - lastReconnectMs >= RECONNECT_COOLDOWN_MS

        /**
         * Log için yalnızca çerçeve başlığı: parça başlatmada 6 bayt, diğer kontrol ve tek paketlerde 4 bayt,
         * parçada 2 baytlık sıra. Yük asla yazılmaz.
         */
        fun header(frame: ByteArray): String = headerOrNull(frame) ?: "boş"

        fun headerOrNull(frame: ByteArray): String? {
            if (frame.isEmpty()) return null
            val seq = if (frame.size >= 2) (frame[0].toInt() and 0xFF) or ((frame[1].toInt() and 0xFF) shl 8) else -1
            val n = when {
                frame.size < 2 -> frame.size
                seq != 0 -> 2
                frame.size >= 6 && (frame.getOrNull(2)?.toInt() ?: -1) == 0 -> 6
                else -> minOf(4, frame.size)
            }
            return frame.copyOf(n).joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
        }
    }
}
