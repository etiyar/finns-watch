# Finn's Watch | Gerçek cihaz test sonuçları

Kaynak: kullanıcının doğrudan bildirdiği denemeler. CI derlemesi burada ayrıca belirtilir; gerçek cihazda denenmemiş bir işlevi başarılı saymayız.

| Tarih | Sürüm / cihaz | Deneme | Sonuç | Not |
|---|---|---|---|---|
| 18.11.2026 | v0.13.0-beta6 / Xiaomi Smart Band 8 | Uygulamayı durdurup yeniden açtıktan sonra saat kaydını koruma | **BAŞARISIZ** | Kullanıcı testinde Band 8 kayıtlı kalmadı. Kayıt listesi mi, etkin seçim mi yoksa anahtar mı etkilendiği belirlenmedi. |
| 18.11.2026 | v0.13.0-beta6 / Telefon yeniden başlatma | Band 8 kayıt ve bağlantı kalıcılığı | **TEST EDİLMEDİ** | Öncelikle uygulama yeniden açılınca kaybolma sorunu çözülecek. |
| 18.11.2026 | v0.13.0-beta6 / Haylou Solar LS05 | Kadranı yanlış destek uyarısı olmadan yükleme | **GEÇTİ** | Kullanıcı gerçek saat testinde başarılı olduğunu doğruladı. |
| 18.11.2026 | v0.13.0-beta5 / Xiaomi Smart Band 8 | Bilinen Bluetooth adresiyle GATT bağlantısı ve pil okuma | **GEÇTİ** | Kullanıcı: 'Adresle bağlandı şarj doğru gösteriyor.' Tam adres ve pil yüzdesi kaydedilmedi. |
| 18.11.2026 | v0.13.0-beta2-beta4 / Band 8 | Genel BLE taramasından doğru cihazı seçme | **DOĞRULANMADI** | Liste başka cihazları da içeriyordu; önceki seçimde FE95 servisi bulunamadı. Bilinen adresle bağlanma sorunu aştı. |
| 18.11.2026 | v0.13.0-beta5 / Band 8 | GitHub Actions protokol testleri ve Android APK derlemesi | **GEÇTİ (CI)** | Gerçek cihaz testi yerine geçmez. |
| 17-18.11.2026 | v0.12.0 / Haylou Solar LS05 | Temel saat işlevleri | **GEÇTİ (kullanıcı bildirimi)** | Kullanıcı diğer saat testlerinde sorun olmadığını belirtti. |
| 17-18.11.2026 | v0.12.0 / Haylou Solar LS05 | Kadran aktarımı | **KISMEN GEÇTİ** | Aktarım başarılıydı; aynı anda hatalı 'Bu saatte kadran yüklenemez' uyarısı çıktı. Kod düzeltmesi yapıldı; cihazda tekrar test edilmeli. |

## 18 Kasım 2026 | Band 8 gerçek cihaz testleri (beta5)
- **GEÇTİ:** Bluetooth kapatılıp açıldığında Band 8 kendiliğinden yeniden bağlandı (kullanıcı bildirimi).
- **GEÇTİ:** LS05 ile Band 8 arasında saat değiştirince bağlantı kuruldu (kullanıcı bildirimi).
- **GEÇTİ:** Band 8 pil yüzdesi ve firmware bilgisi arayüzde görüntülendi. Ekran görüntüsünde bağlantı ve pil durumu görülüyor.
- **HATA:** Bir oturumda 'Doğrulama durdu; uygulamayı aç' mesajı çıktı. Ardından uygulama durdurulup açıldığında Band 8 kayıt listesinde görünmedi; yeniden eklenince bağlandı. Bu olayın altında yatan neden henüz kesinleşmedi.
- **GÜNCEL TEST (beta6):** Uygulama durdurulup açıldıktan sonra Band 8 kaydı korunmadı (**BAŞARISIZ**). LS05 kadran yükleme yanlış uyarı olmadan tamamlandı (**GEÇTİ**). Telefonun tamamen yeniden başlatılması **TEST EDİLMEDİ**.
- **Kapsam dışı:** Band 8 adım, nabız ve uyku senkronizasyonu henüz uygulanmadı. Bu yüzden Band 8 etkinse Bugün sayfasında 'Veri yok' görünmesi bu sürümde beklenir.

## v0.13.0-beta7 | Beklenen doğrulama
- Kayıt kurtarma ve yazma hatası davranışı: otomatik testler eklendi, **CI sonucu ayrıca kontrol edilmeli**.
- Gerçek cihaz testi: **BEKLENİYOR**. Uygulamayı iki kez durdur/aç, Band 8 saat listesinde mi, anahtar hâlâ geçerli mi, yeniden şarj bilgisi geliyor mu?
- Telefonu yeniden başlatıp kayıt ve otomatik bağlantıyı doğrula.
- Hata tekrarlanırsa maskeli kayıt tanılama satırını paylaş; kişisel adres veya anahtar paylaşma.

## Sıradaki kısa kabul testi
1. **GEÇTİ:** Band 8 bağlıyken Bluetooth kapatılıp açılınca otomatik geri bağlandı.
2. **BAŞARISIZ (beta6):** Uygulama durdurulup açılınca Band 8 kayıtlı kalmadı. Hatanın kaynağını belirlemeden tekrar saat ekleyip verileri değiştirme. Telefon yeniden başlatma testi henüz yapılmadı.
3. **GEÇTİ (bağlantı):** Saatlerim'den LS05 ve Band 8 arasında geçişte bağlantı kuruldu. Eski cihaz durumunun ekranda kalmadığı ve veri bütünlüğü ayrıca kontrol edilmeli.
4. **GEÇTİ (beta6):** LS05 kadranı yanlış uyarı olmadan yüklendi.
5. Band 8 ile bağlantı kesikken 'Bağlan' seçeneği çalışıyor mu? Kayıtlı adresle tekrar eşleşme yapmadan bağlanabiliyor mu?

**Not:** Pil yüzdesinin doğru olması bağlantı ve komut kanalının işlediğine güçlü kanıttır. Bu tek başına uzun süreli bağlantı kararlılığı veya sağlık geçmişi eşitlemesini kanıtlamaz.

## Kayıt ilkeleri
- Sonuçlar: GEÇTİ, KISMEN GEÇTİ, BAŞARISIZ, DOĞRULANMADI.
- Gerçek cihaz kanıtı kullanıcı bildirimi veya güvenli, maskelenmiş hata özeti olmalı.
- Parola, kimlik anahtarı, tam MAC adresi, kişisel sağlık verisi veya ham Bluetooth dökümü commit edilmez.
