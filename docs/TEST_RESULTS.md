# Finn's Watch | Gerçek cihaz test sonuçları

Kaynak: kullanıcının doğrudan bildirdiği denemeler. CI derlemesi burada ayrıca belirtilir; gerçek cihazda denenmemiş bir işlevi başarılı saymayız.

| Tarih | Sürüm / cihaz | Deneme | Sonuç | Not |
|---|---|---|---|---|
| 18.11.2026 | v0.13.0-beta5 / Xiaomi Smart Band 8 | Bilinen Bluetooth adresiyle GATT bağlantısı ve pil okuma | **GEÇTİ** | Kullanıcı: 'Adresle bağlandı şarj doğru gösteriyor.' Tam adres ve pil yüzdesi kaydedilmedi. |
| 18.11.2026 | v0.13.0-beta2-beta4 / Band 8 | Genel BLE taramasından doğru cihazı seçme | **DOĞRULANMADI** | Liste başka cihazları da içeriyordu; önceki seçimde FE95 servisi bulunamadı. Bilinen adresle bağlanma sorunu aştı. |
| 18.11.2026 | v0.13.0-beta5 / Band 8 | GitHub Actions protokol testleri ve Android APK derlemesi | **GEÇTİ (CI)** | Gerçek cihaz testi yerine geçmez. |
| 17-18.11.2026 | v0.12.0 / Haylou Solar LS05 | Temel saat işlevleri | **GEÇTİ (kullanıcı bildirimi)** | Kullanıcı diğer saat testlerinde sorun olmadığını belirtti. |
| 17-18.11.2026 | v0.12.0 / Haylou Solar LS05 | Kadran aktarımı | **KISMEN GEÇTİ** | Aktarım başarılıydı; aynı anda hatalı 'Bu saatte kadran yüklenemez' uyarısı çıktı. Kod düzeltmesi yapıldı; cihazda tekrar test edilmeli. |

## Sıradaki kısa kabul testi
1. Band 8 bağlıyken Bluetooth'u kapatıp aç: bağlantı otomatik geri geliyor mu?
2. Uygulamayı ve telefonu yeniden başlat: Band 8'in son seçilen cihazı ve anahtarı korunuyor mu?
3. Saatlerim'den LS05'e geç, ardından Band 8'e dön: iki cihazın bilgileri doğru mu; yalnızca seçilen saat mi etkin?
4. LS05'e kadran yükle: aktarım çalışıyor ve yanlış uyarı çıkmıyor mu?
5. Band 8 ile bağlantı kesikken 'Bağlan' seçeneği çalışıyor mu? Kayıtlı adresle tekrar eşleşme yapmadan bağlanabiliyor mu?

**Not:** Pil yüzdesinin doğru olması bağlantı ve komut kanalının işlediğine güçlü kanıttır. Bu tek başına uzun süreli bağlantı kararlılığı veya sağlık geçmişi eşitlemesini kanıtlamaz.

## Kayıt ilkeleri
- Sonuçlar: GEÇTİ, KISMEN GEÇTİ, BAŞARISIZ, DOĞRULANMADI.
- Gerçek cihaz kanıtı kullanıcı bildirimi veya güvenli, maskelenmiş hata özeti olmalı.
- Parola, kimlik anahtarı, tam MAC adresi, kişisel sağlık verisi veya ham Bluetooth dökümü commit edilmez.
