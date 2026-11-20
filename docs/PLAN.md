# Finn's Watch | Güncel plan

**20 Kasım 2026:** PR #28 (v0.15.0-alpha1) birleşti, build 182 cihazda denendi: sağlık dosyaları (11 dosya, CRC tamam), test bildirimi, bulma, cevapsız arama, rahatsız etme aç/kapat ve alarm kurma çalıştı; müzik ve hava bantta görünmedi (`TEST_RESULTS.md`). Sıradaki aday v0.15.0-beta1 (`feat/v015-apk2`): Band 8 uyku, adım ve nabzın Bugün ekranında birleşik gösterimi, CSV kaynak sütunu, otomatik cevapsız arama ve rahatsız etme, alarm denemesi düzeltmesi. [#16](https://github.com/etiyar/ls05-controller/issues/16) kök nedeni ikinci istemciydi.

| İş | Takip | Sahip/dal | Yayın kapısı |
| --- | --- | --- | --- |
| APK kimliği ve elle test adayı yayımlama | [#20](https://github.com/etiyar/ls05-controller/issues/20), [PR #21](https://github.com/etiyar/ls05-controller/pull/21) birleşti | `dev/v0.13.1-band8-daily` | Eski kurulum üstüne veri koruma cihaz testi |
| Belgeleri ve açık kabul listesini düzenleme | [#22](https://github.com/etiyar/ls05-controller/issues/22), [PR #23](https://github.com/etiyar/ls05-controller/pull/23) birleşti | `dev/v0.13.1-band8-daily` | Eski protokol ve plan bilgisi kaybolmaz |
| Band 8 aralıklı komut/yeniden doğrulama | [#16](https://github.com/etiyar/ls05-controller/issues/16) kapandı | Kök neden: ikinci istemci (Notify, Mi Fitness). PR #27 birleşti, PR #18 kapandı | Yabancı istemci uyarısı ve 5 dakikalık yeniden bağlanma `feat/v015-apk1` içinde; cihazda doğrulanacak |
| v0.15.0-alpha1 (APK-1): sağlık arşivi, dışa aktarma, elle denemeler | [#10](https://github.com/etiyar/ls05-controller/issues/10), [#9](https://github.com/etiyar/ls05-controller/issues/9) | `feat/v015-apk1` | CI ve sabah cihaz testi: arşiv logu, zip, her denemenin bantta gözlenen sonucu |
| Band 8 bildirim dayanıklılığı | [#2](https://github.com/etiyar/ls05-controller/issues/2) | Issue üzerinden atanacak | Kapalı uygulama filtresi ve telefon açılışında elle açmadan bağlantı |
| Gerçek arama ve müzik | [#9](https://github.com/etiyar/ls05-controller/issues/9) | Issue üzerinden atanacak | Ayrı gerçek cihaz gözlemi; elle arama ekranı testi yeterli değildir |
| Band 8 sağlık kaynakları | [#10](https://github.com/etiyar/ls05-controller/issues/10) | Issue üzerinden atanacak | LS05 ve Band 8 verileri ayrı; adımlar toplanmaz |
| v0.13.1 yayın adayı | [#8](https://github.com/etiyar/ls05-controller/issues/8) | Kabul sonrası | Kayıt/anahtar kalıcılığı, LS05 regresyonu, v0.12.0 üstüne güncellemede veri korunması |

Sıradaki somut adım ve güncel kabul listesi ilgili issue içindedir. CI ile fiziksel sonuç ayrı kaydedilir. Cihaz gözlemleri [TEST_RESULTS.md](TEST_RESULTS.md), sürüm geçmişi [CHANGELOG.md](../CHANGELOG.md), ajan düzeni [AGENTS.md](../AGENTS.md) içindedir.

LS05 komutları ve kadran biçimi [LS05_PROTOKOL.md](LS05_PROTOKOL.md), eski planın eksiksiz hâli [PLAN_ARSIV.md](arsiv/PLAN_ARSIV.md) içindedir.

Yayın sonrasına ayrılan istekler: [Saatlerim'de kimlik #24](https://github.com/etiyar/ls05-controller/issues/24) ve [güvenli yedekleme #25](https://github.com/etiyar/ls05-controller/issues/25).
