# Finn's Watch | Gerçek cihaz test sonuçları

Kaynak: kullanıcının doğrudan bildirdiği denemeler. CI derlemesi burada ayrıca belirtilir; gerçek cihazda denenmemiş bir işlevi başarılı saymayız.

## 18 Kasım 2026 | Tek satırlı saat kaydı düzeltmesi

| Deneme | Sonuç | Kanıt ve sınır |
|---|---|---|
| Bozuk listeden Band 8 kurtarma ve etkin seçim | **GEÇTİ** | Kurtarma sonrası ana kayıt ve yedek geçerliydi, iki saat vardı, Band 8 etkin ve anahtar zarfı mevcuttu. |
| Önceki çok satırlı kayıtla uygulamayı zorla durdurup açma | **BAŞARISIZ** | 08:51'de geçerli okunan iki kopya, 08:52 açılışında `CORRUPT` göründü. Kesin kök neden doğrulanmadı. |
| Tek satırlı kodlanmış kayıtla uygulamayı yeniden açma | **GEÇTİ (bir deneme)** | 09:48 açılışında `PRIMARY`, iki saat, Band 8 etkin, adres ve anahtar zarfı mevcut, ana ve yedek kayıt geçerli (her biri 147 karakter). Band 8 cihaz bilgisi okundu. |
| İkinci zorla durdur/aç ve telefonun tamamen yeniden başlatılması | **BEKLENİYOR** | Cihazda kalıcı bağlantı kabulü için ayrı doğrulanacak. |

Bu sonuç bağlantı ve kayıt kalıcılığı için tek yeniden açma denemesidir. Band 8 sağlık eşitlemesi, bildirimlerin saatte görünmesi ve saati bulma işlevi bu logla doğrulanmadı.

## Beta8 ve v0.13.1-alpha1 | Bekleyen denemeler

- **Beta8, kurulum:** Temiz profilde iki modelin kurulumu açılır. Mevcut uygulamanın üzerine güncellemede önceki saat kartları, etkin seçim ve sağlık geçmişi korunur.
- **Beta8, bağlantı:** Band 8 etkinken zorla durdur/aç iki kez. Ardından bağlantı servisi etkinken telefonu yeniden başlat ve uygulamayı elle açmadan bağlantının kurulmasını gözle. Anahtar yeniden istenirse veya kayıt kaybolursa maskeli kayıt tanılamasını kontrol et.
- **Alpha1, bildirim:** Band 8 ekranında Türkçe test bildirimi görünür. Telefon kilitliyken açık uygulama iletilir, filtreden kapalı uygulama iletilmez. Uzun metin ve LS05 ↔ Band 8 geçişi ayrıca denenir.
- **Alpha1, saati bul:** Ayarlar > Gelişmiş ve log > Band 8 saati bul testi ile titreşimi başlat ve uygulamadan durdur. İkinci denemede bileklikten durdur; fiziksel sonucu ve varsa yalnızca sayısal durum yanıtını kaydet.

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

## v0.13.0-beta7 | 18 Kasım 2026 ekran görüntüsü gözlemi
- **GÖZLENDİ:** İlk görüntüde Saatlerim listesinde yalnızca LS05 bulunuyor ve bağlı (%85) görünüyor.
- **GÖZLENDİ:** Sonraki görüntüde LS05 ile Xiaomi Smart Band 8 birlikte listeleniyor; Band 8 etkin ve bağlı (%98) görünüyor.
- **GÖZLENDİ:** Aynı Band 8 ekranında 'Ayarlar > Saat bağlantısı > Tara ile cihazı seç. Band 8 anahtarı doğrulam...' şeklinde kesilmiş bir uyarı/toast bulunuyor.
- **AÇIK / ERTELENDİ:** Bağlı görünmesine rağmen yeniden cihaz seçimi veya anahtar doğrulaması isteyen uyarının tutarsızlığı. Görüntüler tek başına kaydın yeniden başlatma sonrasında korunduğunu veya silindiğini kanıtlamaz.
- **BEKLEYEN:** Beta7 uygulama kapat/aç kalıcılığı, telefon yeniden başlatma ve doğrulama durumunun gerçek cihazda ayrı test edilmesi. Kullanıcı bu konuyu daha sonra incelemeyi istedi.
