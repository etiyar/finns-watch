package com.che.ls05.protocol

/**
 * Saatten gelen ve servisin saatten bağımsız işlediği olaylar. Sürücü kendi çerçevesini bunlara çevirir.
 */
sealed class WatchEvent {
    /** Ayar onayı. echo: saatin geri yolladığı değer (ortak biçimde), yoksa null. */
    data class SettingAck(val key: String, val echo: String?) : WatchEvent()

    /** Saatten kumanda (müzik, telefonu bul, arama reddi). */
    data class Remote(val action: RemoteAction) : WatchEvent()

    /** İçinde bulunulan saatin canlı adım toplamı. */
    data class LiveSteps(val hour: StepHour) : WatchEvent()

    /** Geçmiş okumasının bir parçası bitti. STEPS bitince son eşitleme zamanı güncellenir. */
    data class HistoryPart(val part: HistoryGate.Part) : WatchEvent()
}

/**
 * Doğrulama sonucu. Servis yalnızca bunu bilir; kodların anlamı ve mesajlar sürücüye aittir.
 * markUnknown: saat sıfırlanmış olabilir, ayarlar "durum bilinmiyor" sayılır.
 */
data class AuthOutcome(
    val kind: Kind,
    val markUnknown: Boolean = false,
    val unknownMessage: String? = null,
    /** BLOCK için kullanıcıya gösterilecek mesaj. */
    val blockMessage: String? = null,
) {
    enum class Kind { OK, TIMEOUT, BLOCK }
}

/** LS05 çerçevelerinin ortak olaylara çevrilmesi (v0.11 WatchService.onFrame ile aynı kurallar). */
object Ls05Events {

    /** Üst üste bu kadar yanıtsız doğrulama otomatik denemeyi durdurur. */
    const val MAX_AUTH_TIMEOUTS = 3

    fun of(f: UteFrame): WatchEvent? = when (f) {
        is UteFrame.AlarmAck -> WatchEvent.SettingAck(AlarmSetting.key(f.slot), null)
        is UteFrame.DndAck -> WatchEvent.SettingAck(DndSetting.KEY, DndSetting.fromEcho(f.bytes)?.encode())
        is UteFrame.ProfileAck -> WatchEvent.SettingAck(ProfileSetting.KEY, null)
        is UteFrame.WatchKey -> WatchEvent.Remote(WatchKeys.action(f))
        is UteFrame.Steps -> if (f.hour.live) WatchEvent.LiveSteps(f.hour) else null
        is UteFrame.StepHistoryEnd -> WatchEvent.HistoryPart(HistoryGate.Part.STEPS)
        is UteFrame.HeartHistoryEnd -> WatchEvent.HistoryPart(HistoryGate.Part.HEART)
        // Uyku: bölümler gelince ya da gece kaydı yoksa (0 bölüm) okuma bitmiş sayılır.
        is UteFrame.SleepData -> WatchEvent.HistoryPart(HistoryGate.Part.SLEEP)
        is UteFrame.SleepHeader -> if (f.count == 0) WatchEvent.HistoryPart(HistoryGate.Part.SLEEP) else null
        else -> null
    }

    /** Doğrulama kodu: 1 tamam, null zaman aşımı, -1 kimlik yok, 4 onay bekliyor, diğerleri ret. */
    fun auth(code: Int?): AuthOutcome {
        val unknown = code != null && code != 1 && code != -1
        val unknownMessage = if (unknown) {
            "Saat doğrulamada $code döndü; sıfırlanmış olabilir. Ayarlar 'durum bilinmiyor' olarak işaretlendi, otomatik gönderilmeyecek."
        } else {
            null
        }
        return when (code) {
            1 -> AuthOutcome(AuthOutcome.Kind.OK)
            null -> AuthOutcome(
                AuthOutcome.Kind.TIMEOUT,
                blockMessage = "Doğrulama $MAX_AUTH_TIMEOUTS kez yanıtsız kaldı. Haylou Fun açıksa kapat, sonra uygulamadan yeniden bağlan.",
            )
            -1 -> AuthOutcome(AuthOutcome.Kind.BLOCK, blockMessage = "Kimlik girilmemiş. Uygulamada kimliği gir ve yeniden bağlan.")
            4 -> AuthOutcome(
                AuthOutcome.Kind.BLOCK, unknown, unknownMessage,
                "Saat onay bekliyor (durum 4). Otomatik deneme durduruldu; uygulamayı aç.",
            )
            else -> AuthOutcome(
                AuthOutcome.Kind.BLOCK, unknown, unknownMessage,
                "Saat doğrulamayı reddetti (durum $code). Otomatik deneme durduruldu; kimliği kontrol et.",
            )
        }
    }
}
