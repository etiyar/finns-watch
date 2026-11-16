package com.che.ls05.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** v0.11: alarm titreşim süresi (5. bayt). Ölçüm: 8 ≈ 10 sn, 16 ≈ 20 sn, 30 ≈ 45 sn. */
class V011Test {

    @Test
    fun oldRecordDecodesAsShort() {
        // v0.11 öncesi kayıt: 5. alan yok, gönderilen değer 8'di.
        val a = AlarmSetting.decode("1|7|0|62")!!
        assertEquals(UteAlarm.VIBES_SHORT, a.vibes)
        assertEquals("1|7|0|62", a.encode())
        assertContentEquals(Hex.parse("06 3E 07 00 02 08 06 00 01"), a.command(1))
    }

    @Test
    fun shortEncodesWithoutFifthField() {
        // Eski metin aynı kalır: istenen ve onaylanan değer eşit kalır, yeniden gönderim olmaz.
        assertEquals("0|6|30|127", AlarmSetting(false, 6, 30, 0x7F, UteAlarm.VIBES_SHORT).encode())
    }

    @Test
    fun roundTripNormalAndLong() {
        for (v in intArrayOf(UteAlarm.VIBES_NORMAL, UteAlarm.VIBES_LONG)) {
            val a = AlarmSetting(true, 21, 20, UteAlarm.WEEKDAYS, v)
            assertEquals("1|21|20|62|$v", a.encode())
            assertEquals(a, AlarmSetting.decode(a.encode()))
        }
    }

    @Test
    fun commandBytesCarryVibes() {
        assertContentEquals(
            Hex.parse("06 3E 07 00 02 10 06 00 02"),
            AlarmSetting(true, 7, 0, UteAlarm.WEEKDAYS, UteAlarm.VIBES_NORMAL).command(2),
        )
        assertContentEquals(
            Hex.parse("06 7F 06 2D 02 1E 06 00 03"),
            AlarmSetting(true, 6, 45, UteAlarm.EVERY_DAY, UteAlarm.VIBES_LONG).command(3),
        )
        // Kapalı alarm titreşimden bağımsız: üç bayt sıfır.
        assertContentEquals(
            Hex.parse("06 60 07 00 00 00 00 00 01"),
            AlarmSetting(false, 7, 0, UteAlarm.WEEKDAYS, UteAlarm.VIBES_LONG).command(1),
        )
        assertContentEquals(
            Hex.parse("06 3E 07 00 02 1E 06 00 01"),
            SettingCommands.build("alarm1", "1|7|0|62|30"),
        )
    }

    @Test
    fun invalidVibesRejected() {
        assertNull(AlarmSetting.decode("1|7|0|62|0"))
        assertNull(AlarmSetting.decode("1|7|0|62|61"))
        assertNull(AlarmSetting.decode("1|7|0|62|x"))
        assertNull(AlarmSetting.decode("1|7|0|62|16|1"))
        assertFailsWith<IllegalArgumentException> { UteAlarm.build(1, 7, 0, UteAlarm.WEEKDAYS, true, 0) }
    }

    @Test
    fun durationChangeIsNewDesiredValue() {
        // Süre değişince istenen değer değişir ve bir kez daha gönderilir (onay modeli aynı).
        val s = SettingSync()
        val key = AlarmSetting.key(1)
        val short = AlarmSetting(true, 7, 0, UteAlarm.WEEKDAYS)
        s.setDesired(key, short.encode())
        s.markSent(key, short.encode(), 1, 10)
        s.onAck(key, 1, 11)
        assertEquals(SyncPhase.CONFIRMED, s.entry(key).phase)
        s.setDesired(key, short.copy(vibes = UteAlarm.VIBES_LONG).encode())
        assertEquals(SyncPhase.PENDING, s.entry(key).phase)
        assertEquals(listOf(key to "1|7|0|62|30"), s.nextToSend())
        val restored = SettingSync.deserialize(s.serialize())
        assertEquals("1|7|0|62|30", restored.entry(key).desired)
    }

    @Test
    fun labels() {
        assertEquals("10 sn", UteAlarm.vibesLabel(UteAlarm.VIBES_SHORT))
        assertEquals("20 sn", UteAlarm.vibesLabel(UteAlarm.VIBES_NORMAL))
        assertEquals("45 sn", UteAlarm.vibesLabel(UteAlarm.VIBES_LONG))
        assertEquals(UteAlarm.VIBES_NORMAL, AlarmSetting.NEW.vibes)
    }
}
