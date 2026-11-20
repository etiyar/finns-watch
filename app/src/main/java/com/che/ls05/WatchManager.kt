package com.che.ls05

import android.content.Context
import com.che.ls05.protocol.Capabilities
import com.che.ls05.protocol.SwitchRules
import com.che.ls05.protocol.WatchState
import com.che.ls05.protocol.WatchType

/**
 * Etkin saat ve saat değiştirme için arayüzün tek giriş noktası. Bağlantı işini WatchService yapar;
 * servis çalışmıyorsa yalnızca kayıt değişir ve servis gerekiyorsa başlatılır. Ana iş parçacığında çağrılır.
 */
object WatchManager {

    /** Servisin bildirdiği durum (Saatlerim ekranı için). */
    @Volatile
    var state: WatchState = WatchState.NONE
        private set

    fun setState(s: WatchState) {
        if (state == s) return
        state = s
        WatchHub.settingsChanged()
    }

    fun caps(c: Context): Capabilities = WatchRegistry.caps(c)

    fun driverFor(type: WatchType, c: Context, listener: DriverListener): WatchDriver = when (type) {
        WatchType.LS05 -> Ls05Driver(c, listener)
        WatchType.BAND8 -> Band8Driver(c, listener)
    }

    /** Etkin saati değiştirir. Yapılamazsa kullanıcıya gösterilecek neden döner. */
    fun switchTo(c: Context, id: String): String? {
        val s = WatchHub.service
        if (s != null) return s.switchTo(id)
        when (SwitchRules.decide(WatchRegistry.list(c), id, WatchHub.faceBusy)) {
            SwitchRules.Decision.SAME -> return null
            SwitchRules.Decision.BUSY -> return "Kadran yükleniyor; bitince tekrar dene."
            SwitchRules.Decision.UNKNOWN -> return "Saat bulunamadı."
            SwitchRules.Decision.PROCEED -> Unit
        }
        LinkAlert.disarm(c, "saat değiştirildi")
        if (!WatchRegistry.setActive(c, id)) return "Saat seçimi kaydedilemedi. Depolama durumunu kontrol et."
        SwitchLog.record(c, id)
        afterSwitch(c)
        if (Prefs.serviceEnabled(c) && WatchRegistry.hasActive(c)) WatchService.start(c)
        return null
    }

    /** Kayıtta etkin saat değişti: saate bağlı telefon tarafı yenilenir. */
    fun afterSwitch(c: Context) {
        HealthStore.reset(c)
        Reminders.reschedule(c)
        WatchHub.resetDevice()
        WatchHub.healthChanged()
    }
}
