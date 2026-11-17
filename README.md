# Finn's Watch (LS05 Controller)

Haylou Solar LS05 saatimi Haylou uygulamasına bağımlı olmaktan çıkarıp, üreticinin sunmadığı özelliklerle kullanmak için kişisel Android uygulaması. Saat artık resmi güncelleme almıyor; bu proje onun yerine geçiyor.

## Asıl hedefler

| Hedef | Nasıl | Aşama |
| --- | --- | --- |
| Haylou uygulaması olmadan kullanım, bulut ve hesap yok | Kendi BLE istemcimiz (UTE/Youcy protokolü) | 1-3 |
| Doğru Türkçe bildirimler, uygulamaya göre simge ve titreşim | `0F` bildirim + uygulama tipleri + titreşim komutları | 2-3 |
| Kayıp saati bulma | Bağlantı kopunca telefonun son konumu, RSSI yakınlık radarı, saati titretme | 4 |
| Sağlık verilerine sahip olma | Adım, nabız, uyku, SpO2 geçmişi; CSV ve Health Connect'e aktarma | 3-4 |
| Otomasyon | Tasker, bildirim filtreleri, kişisel kurallar | 4 |
| Özel saat kadranı | Haylou Fun'ın resmi yükleme akışı: `1a 02` / `61FF/6101` veri / `1a 03 00` (12 Kasım kaydı) | 5 |
| Find My tarzı kalabalık ağ takibi, özel firmware | Firmware'e yayın eklemek; önce güvenli yedek ve kurtarma yolu kanıtlanmalı | 6, araştırma |

Not: Gerçek Find My / Find Hub kalabalık ağı (başkasının telefonunun saati görüp konum bildirmesi) firmware değişikliği ister. Bu, kurtarma yolu olmadan tek saati bozma riski taşıdığı için en sona bırakıldı. Telefon tarafındaki "son görülen konum" ve radar bu riski taşımadan kayıp saatin çoğu senaryosunu çözer.

## Güncel durum (18 Kasım 2026)

**Aktif geliştirme sürümü: v0.13.0-beta6** (`dev/v0.13-band8-connection`). Kararlı `main` ayrı tutuluyor.

- **Haylou Solar LS05:** Bildirim, alarm, DND, sağlık geçmişi, kadran ve diğer önceki işlevler mevcut. Kadran aktarımı sonrasındaki yanlış uyarı için kod düzeltmesi yapıldı; yeni APK'da yeniden test edilmeli.
- **Xiaomi Smart Band 8:** BLE bağlantısı ve kimlik doğrulaması, pil ve firmware okuma çalışıyor. Bilinen yerel Bluetooth adresiyle cihazda bağlandı. Bluetooth kapat/aç sonrası otomatik yeniden bağlantı ve saat değiştirme kullanıcı tarafından doğrulandı.
- **Açık hata:** Bir oturumda Band 8 doğrulaması durdu ve uygulama yeniden açılınca Band 8 listede görünmedi. Kayıt saklama sertleştirmesi geliştirildi, kesin kök neden ve cihaz onayı bekleniyor.
- **Henüz eklenmeyenler (Band 8):** Bildirim, arama, müzik, sağlık geçmişi ve saat ayarlarının gönderimi. Bu saatte Bugün ekranında sağlık verisi olmaması şu aşamada normal.

### Takip belgeleri
- [Sürüm değişiklikleri](CHANGELOG.md)
- [Gerçek cihaz testleri](docs/TEST_RESULTS.md)
- [Güncel plan ve açık işler](docs/PLAN.md)

Kimlik doğrulama anahtarları, tam Bluetooth adresleri ve sağlık verileri GitHub'a yazılmaz. Eski APK'yı silmeden üzerine kurulum tercih edilir. Telefon yeniden başlatma ve kayıt kalıcılığı testleri tamamlanmadan beta6 kararlı sürüm sayılmaz.

## Geçmiş sürüm notları (arşiv)

### v0.6.0 (eski görsel tur)

Uygulamanın adı artık **Finn's Watch** (paket adı `com.che.ls05` aynı, güncelleme eskisinin üzerine kurulur) ve kendi "FW" simgesi var. Bu sürüm yalnızca görünümü değiştirir; bağlantı, protokol ve ayar durum modeli v0.5 ile aynıdır.

- Tek tasarım dili: renkler `docs/V06_TASARIM_SARTNAMESI.md` bölüm 2 tablosundan (`values/colors.xml`, `values-night/colors.xml`), bileşenler `Ui.kt` ve `Charts.kt` içinde. Üst uygulama çubuğu yok; her ekranda sayfa başlığı ve bağlantı kapsülü, altta tek yüzeyli menü.
- Bugün: adım halkası, saatlik adım sütunları, nabız kartı (küçük eğri), uyku kartı (gerçek evre şeridi) ve evre açıklaması.
- Saat: üç alarm kartı altı durumlu durum satırıyla ("başarısız" durumu yok), rahatsız etme ve saati bul yan yana, "Ekran ve profil" kartı.
- Bildirimler: uygulama listesinde gerçek uygulama ikonları, sistem uygulamaları katlanır grupta, arama simgeyle açılır, test en altta.
- Ayarlar: kurulum özeti, tema seçimi (Sistem, Koyu, Açık), saat, profil, "Gelişmiş ve log" (varsayılan kapalı).
- Alarm notu bu sürümde yok (v0.6.1).

