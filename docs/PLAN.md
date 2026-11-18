# Finn's Watch | Güncel plan

**18 Kasım 2026:** Kararlı dal `main` v0.12.0; Band 8 geliştirme dalı `dev/v0.13.1-band8-daily` v0.13.1-beta1. Build 170'te bildirimler ve bilekliği bul ilk oturumda çalıştı; Bluetooth kapat/aç sonrası yeniden bağlantı ve kimlik doğrulama gerçekleştiği hâlde bileklik fiziksel tepki vermedi. Beklenmedik kimlik doğrulama ve fiziksel tepkisiz `ACK` olayları [#16](https://github.com/etiyar/ls05-controller/issues/16) kapsamında açık. Band 8 sağlık verisi ve gerçek gelen arama/müzik entegrasyonu tamamlanmadı.

| İş | Takip | Sahip/dal | Yayın kapısı |
| --- | --- | --- | --- |
| APK kimliği ve elle test adayı yayımlama | [#20](https://github.com/etiyar/ls05-controller/issues/20), [PR #21](https://github.com/etiyar/ls05-controller/pull/21) | `feat/apk-build-identity` | CI ve eski kurulum üstüne veri koruma cihaz testi |
| Belgeleri ve açık kabul listesini düzenleme | [#22](https://github.com/etiyar/ls05-controller/issues/22), [PR #23](https://github.com/etiyar/ls05-controller/pull/23) | `docs/current-project-status` | Eski protokol ve plan bilgisi kaybolmaz |
| Band 8 aralıklı komut/yeniden doğrulama | [#16](https://github.com/etiyar/ls05-controller/issues/16), [taslak PR #18](https://github.com/etiyar/ls05-controller/pull/18) | `fix/band8-unexpected-reauth` | Test APK kimliği önceden kaydedilir; beklenmedik 1/26 veya 1/27 olayı görülürse toparlanma ayrıca doğrulanır |
| Band 8 bildirim dayanıklılığı | [#2](https://github.com/etiyar/ls05-controller/issues/2) | Issue üzerinden atanacak | Kapalı uygulama filtresi ve telefon açılışında elle açmadan bağlantı |
| Gerçek arama ve müzik | [#9](https://github.com/etiyar/ls05-controller/issues/9) | Issue üzerinden atanacak | Ayrı gerçek cihaz gözlemi; elle arama ekranı testi yeterli değildir |
| Band 8 sağlık kaynakları | [#10](https://github.com/etiyar/ls05-controller/issues/10) | Issue üzerinden atanacak | LS05 ve Band 8 verileri ayrı; adımlar toplanmaz |
| v0.13.1 yayın adayı | [#8](https://github.com/etiyar/ls05-controller/issues/8) | Kabul sonrası | Kayıt/anahtar kalıcılığı, LS05 regresyonu, v0.12.0 üstüne güncellemede veri korunması |

Sıradaki somut adım ve güncel kabul listesi ilgili issue içindedir. CI ile fiziksel sonuç ayrı kaydedilir. Cihaz gözlemleri [TEST_RESULTS.md](TEST_RESULTS.md), sürüm geçmişi [CHANGELOG.md](../CHANGELOG.md), ajan düzeni [AGENTS.md](../AGENTS.md) içindedir.

LS05 komutları ve kadran biçimi [LS05_PROTOKOL.md](LS05_PROTOKOL.md), eski planın eksiksiz hâli [PLAN_ARSIV.md](arsiv/PLAN_ARSIV.md) içindedir.

Yayın sonrasına ayrılan istekler: [Saatlerim'de kimlik #24](https://github.com/etiyar/ls05-controller/issues/24) ve [güvenli yedekleme #25](https://github.com/etiyar/ls05-controller/issues/25).
