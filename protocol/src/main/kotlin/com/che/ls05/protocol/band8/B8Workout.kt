package com.che.ls05.protocol.band8

import kotlin.math.abs
import kotlin.math.sqrt

/*
 * Sleep as Android için "gizli antrenman" ve ivme akışı (docs/SLEEP_AS_ANDROID.md 5.1).
 * Kaynak: Notify'ın Sleep as Android yolu (yalnızca başvuru, kod alınmadı) ve Gadgetbridge belgesi
 * ("hareket verisi için bantta gizli bir antrenman başlatılır"). Cihazda denenmedi: güçlü kanıt.
 *
 * 8/26, Health alan 20 (antrenman durumu):
 *   1 zaman (Unix sn), 2 { 1 saat farkı, 15 dk birimi, zigzag } , 3 antrenman türü (810 = uyku takibi),
 *   4 durum (0 başlat, 1 duraklat, 2 sürdür, 3 bitir), 6 = 3.
 * Bitirirken Notify önce duraklat, 500 ms sonra bitir gönderiyor.
 *
 * İvme: Health alan 15 > alan 5 tekrarlı { 1 zaman, 2 x, 3 y, 4 z } (float, mili-g). Alt tür bilinmiyor;
 * Health alan 15 taşıyan her 8/x okunur.
 */
object B8Workout {
    const val SUB_STATUS = 26
    const val SLEEP_TYPE = 810
    const val STATE_START = 0
    const val STATE_PAUSE = 1
    const val STATE_RESUME = 2
    const val STATE_FINISH = 3

    private const val F_WORKOUT = 20
    private const val F_SENSOR = 15
    private const val F_ACCEL = 5

    /** Bitirmeden önce duraklatma ile bitirme arasındaki bekleme. */
    const val FINISH_GAP_MS = 500L

    fun status(state: Int, unixSec: Long, utcOffsetQuarters: Int): ByteArray {
        val w = PbWriter()
            .uint(1, unixSec)
            .message(2, PbWriter().sint(1, utcOffsetQuarters))
            .uint(3, SLEEP_TYPE)
            .uint(4, state)
            .uint(6, 3)
        return B8Messages.command(B8Type.HEALTH, SUB_STATUS).message(B8Type.F_HEALTH, PbWriter().message(F_WORKOUT, w)).toByteArray()
    }

    data class Accel(val x: Float, val y: Float, val z: Float)

    /** Health alan 15 içindeki ivme örnekleri (mili-g). Yoksa boş liste. ±1200 dışı örnekler atılır. */
    fun accel(c: B8Command): List<Accel> {
        if (c.type != B8Type.HEALTH) return emptyList()
        val s = c.body(B8Type.F_HEALTH)?.message(F_SENSOR) ?: return emptyList()
        return s.messages(F_ACCEL).mapNotNull { m ->
            val x = m.float(2) ?: return@mapNotNull null
            val y = m.float(3) ?: return@mapNotNull null
            val z = m.float(4) ?: return@mapNotNull null
            Accel(x, y, z).takeIf { valid(it) }
        }
    }

    /** Health alan 15 var mı (ivme olmasa da). */
    fun hasSensor(c: B8Command): Boolean = c.type == B8Type.HEALTH && c.body(B8Type.F_HEALTH)?.has(F_SENSOR) == true

    private fun valid(a: Accel) = abs(a.x) < 1200f && abs(a.y) < 1200f && abs(a.z) < 1200f
}

/**
 * Sleep as Android hareket verisi (belgedeki yöntem): her örnekte eksen değişimlerinin toplamı (m/s²),
 * 10 sn'lik pencerenin en büyüğü MAX_DATA'ya, ivme büyüklüğünün en büyüğü MAX_RAW_DATA'ya girer.
 * Paket, batch boyu kadar pencere dolunca gider. Duraklatmada pencereler 0 olarak eklenir (belge böyle ister).
 */
class SaaMovement {
    private var last: B8Workout.Accel? = null
    private var windowStart = -1L
    private var windowMax = 0f
    private var windowRaw = 0f
    private var windowSamples = 0
    private val maxData = ArrayList<Float>()
    private val rawData = ArrayList<Float>()

    var samples = 0; private set
    var windows = 0; private set

    class Batch(val max: FloatArray, val raw: FloatArray)

    fun reset() {
        last = null
        windowStart = -1L
        windowMax = 0f; windowRaw = 0f; windowSamples = 0
        maxData.clear(); rawData.clear()
        samples = 0; windows = 0
    }

    fun add(a: B8Workout.Accel, now: Long) {
        if (windowStart < 0) windowStart = now
        val p = last
        last = a
        samples++
        if (p == null) return
        val change = (abs(a.x - p.x) + abs(a.y - p.y) + abs(a.z - p.z)) * MG_TO_MS2
        val raw = sqrt(a.x * a.x + a.y * a.y + a.z * a.z) * MG_TO_MS2
        if (change > windowMax) windowMax = change
        if (raw > windowRaw) windowRaw = raw
        windowSamples++
    }

    /**
     * Pencereyi kapatır ve paket doluysa döndürür. [paused] ise pencere 0 sayılır.
     * Örnek gelmeyen pencere eklenmez (uydurma değer yok); duraklatma hariç.
     */
    fun tick(now: Long, batchSize: Int, paused: Boolean): Batch? {
        if (windowStart < 0) windowStart = now
        if (now - windowStart < WINDOW_MS) return null
        windowStart = now
        when {
            paused -> { maxData += 0f; rawData += 0f; windows++ }
            windowSamples > 0 -> { maxData += windowMax; rawData += windowRaw; windows++ }
        }
        windowMax = 0f; windowRaw = 0f; windowSamples = 0
        return if (maxData.size >= batchSize.coerceAtLeast(1)) flush() else null
    }

    /** Akış başlarken: sayaçlar ve ilk pencere. */
    fun start(now: Long) {
        reset()
        windowStart = now
    }

    /** Bekleyen pencereleri, açık pencere örnek aldıysa onu da döndürür (takip biterken). */
    fun flush(): Batch? {
        if (windowSamples > 0) {
            maxData += windowMax; rawData += windowRaw; windows++
            windowMax = 0f; windowRaw = 0f; windowSamples = 0
        }
        if (maxData.isEmpty()) return null
        val b = Batch(maxData.toFloatArray(), rawData.toFloatArray())
        maxData.clear(); rawData.clear()
        return b
    }

    companion object {
        const val WINDOW_MS = 10_000L
        /** mili-g → m/s². */
        const val MG_TO_MS2 = 9.806f / 1000f
    }
}
