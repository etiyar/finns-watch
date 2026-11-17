package com.che.ls05.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Gerileme: LS05 çerçeveleri sürücüye taşındıktan sonra servisin v0.11'deki tepkisi aynı kalmalı.
 * Her çerçeve için beklenen olay ve her doğrulama kodu için beklenen sonuç ve mesaj.
 */
class Ls05EventsTest {

    private val d = UteDate(2026, 11, 17)

    @Test
    fun settingAcks() {
        assertEquals(WatchEvent.SettingAck("alarm2", null), Ls05Events.of(UteFrame.AlarmAck(2)))
        assertEquals(WatchEvent.SettingAck(ProfileSetting.KEY, null), Ls05Events.of(UteFrame.ProfileAck))
        val bytes = Hex.parse("15 00 16 00 07 00 01")
        val e = Ls05Events.of(UteFrame.DndAck(true, bytes))
        assertEquals(WatchEvent.SettingAck(DndSetting.KEY, DndSetting.fromEcho(bytes)!!.encode()), e)
    }

    @Test
    fun watchKeyMapsLikeV011() {
        val k = UteFrame.WatchKey(0x12, 0x02, emptyList())
        assertEquals(WatchEvent.Remote(WatchKeys.action(k)), Ls05Events.of(k))
    }

    @Test
    fun steps() {
        val live = StepHour(d, 9, 120, 0, 120, 0, 0, true)
        assertEquals(WatchEvent.LiveSteps(live), Ls05Events.of(UteFrame.Steps(live)))
        assertNull(Ls05Events.of(UteFrame.Steps(live.copy(live = false))))
    }

    @Test
    fun historyParts() {
        assertEquals(WatchEvent.HistoryPart(HistoryGate.Part.STEPS), Ls05Events.of(UteFrame.StepHistoryEnd(0)))
        assertEquals(WatchEvent.HistoryPart(HistoryGate.Part.HEART), Ls05Events.of(UteFrame.HeartHistoryEnd(0)))
        assertEquals(WatchEvent.HistoryPart(HistoryGate.Part.SLEEP), Ls05Events.of(UteFrame.SleepData(emptyList())))
        assertEquals(WatchEvent.HistoryPart(HistoryGate.Part.SLEEP), Ls05Events.of(UteFrame.SleepHeader(d, 0)))
        assertNull(Ls05Events.of(UteFrame.SleepHeader(d, 3)))
    }

    @Test
    fun authOutcomesAndMessages() {
        assertEquals(AuthOutcome(AuthOutcome.Kind.OK), Ls05Events.auth(1))
        val t = Ls05Events.auth(null)
        assertEquals(AuthOutcome.Kind.TIMEOUT, t.kind)
        assertEquals(false, t.markUnknown)
        assertEquals("Doğrulama 3 kez yanıtsız kaldı. Haylou Fun açıksa kapat, sonra uygulamadan yeniden bağlan.", t.blockMessage)
        val none = Ls05Events.auth(-1)
        assertEquals(AuthOutcome.Kind.BLOCK, none.kind)
        assertEquals(false, none.markUnknown)
        assertEquals("Kimlik girilmemiş. Uygulamada kimliği gir ve yeniden bağlan.", none.blockMessage)
        val wait = Ls05Events.auth(4)
        assertTrue(wait.markUnknown)
        assertEquals("Saat onay bekliyor (durum 4). Otomatik deneme durduruldu; uygulamayı aç.", wait.blockMessage)
        assertEquals(
            "Saat doğrulamada 4 döndü; sıfırlanmış olabilir. Ayarlar 'durum bilinmiyor' olarak işaretlendi, otomatik gönderilmeyecek.",
            wait.unknownMessage,
        )
        val rej = Ls05Events.auth(2)
        assertEquals(AuthOutcome.Kind.BLOCK, rej.kind)
        assertTrue(rej.markUnknown)
        assertEquals("Saat doğrulamayı reddetti (durum 2). Otomatik deneme durduruldu; kimliği kontrol et.", rej.blockMessage)
    }
}
