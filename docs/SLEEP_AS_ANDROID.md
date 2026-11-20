# Sleep as Android ile Band 8

Bu belge Sleep as Android (SAA) saat eklentisi arayüzünü ve Finn's Watch'taki karşılığını anlatır. Kod: `protocol/.../SleepAsAndroid.kt` (karar mantığı, testli), `app/.../SleepAsAndroidBridge.kt` (yayın ve band komutu), `B8Realtime.kt` (8/45, 8/46, 8/47).

Etiketler: **doğrulandı** = SAA'nın kendi geliştirici belgesinde yazıyor. **güçlü kanıt** = başka bir uygulamanın açık davranışı ya da topluluk kılavuzu. **tahmin** = denenmedi.

## 1. Kaynaklar

- SAA geliştirici belgesi, "Wearable integration API": sleep.urbandroid.org/documentation/developer-api/wearable-integration-api/ (eski adres docs.sleep.urbandroid.org/devs/wearable_api.html).
- SAA forumu, "Send movement data & SET_BATCH_SIZE command" (forum.urbandroid.org/t/5297).
- Gadgetbridge belgesi, "Sleep as Android" entegrasyon sayfası (gadgetbridge.org/basics/integrations/sleep-as-android).
- Gadgetbridge kaynağında yalnızca davranış okundu (eylem adları, alıcı tanımı, nabız gönderme biçimi). Kod alınmadı.

## 2. Kurulum (güçlü kanıt)

1. Finn's Watch: Ayarlar > Sleep as Android > "Sleep as Android ile çalış" açık. İstenirse "Nabız gönder".
2. SAA: Ayarlar > Wearables (eski sürümlerde Sensörler > Akıllı saat) > **DIY**. Paket adı alanına `com.che.ls05` yazılır.
3. Nabız için aynı bölümde nabız ölçümü açılır (bazı sürümlerde kapalı gelir).
4. "Sensörleri test et" ile denenir.

DIY seçeneği ve paket adı alanı Gadgetbridge belgesinden ve Bangle.js kılavuzundan biliniyor (güçlü kanıt). SAA belgesi Android 8 üstünde paketin SAA tarafından izinli listeye alınması gerektiğini yazıyor (doğrulandı). DIY alanının bu izni kullanıcı adına verdiği varsayılıyor (güçlü kanıt: Gadgetbridge "SAA bizi henüz listeye almadı, DIY seç" diyor).

## 3. SAA'dan saate gelen yayınlar (doğrulandı)

Önek `com.urbandroid.sleep.watch.`. Uygulama bunları manifestteki `SaaReceiver` ile alır.

| Eylem | Ek | Bizde |
|---|---|---|
| `CHECK_CONNECTED` | yok | Band 8 hazırsa `CONFIRM_CONNECTED`. SAA 5 sn'de bir, en çok 2 dk sorar. |
| `START_TRACKING` | yok | Onay, sonra nabız açıksa 8/45. |
| `STOP_TRACKING` | yok | Bekleyen nabız paketi gider, 8/46. |
| `SET_PAUSE` | `TIMESTAMP` long, ms | Bu zamana kadar duraklatma. Geçmiş ya da 0 = bitti. Duraklatmada akış durur (8/46), bitince yeniden açılır. |
| `SET_SUSPENDED` | `SUSPENDED` boolean | true: akış durur. false: yeniden açılır. |
| `SET_BATCH_SIZE` | `SIZE` long | Paket aralığı = SIZE × 10 sn (1 ile 30 arası). Varsayılan 12 = 2 dk. |
| `START_ALARM` | `DELAY` int, ms | DELAY sonra alarm. -1 = saatte titreşim yok. |
| `STOP_ALARM` | yok | Alarm ekranı kapanır, titreşim durur. |
| `UPDATE_ALARM` | `HOUR`, `MINUTE` int, `TIMESTAMP` long | Kullanılmaz, loga saat yazılmaz. |
| `SHOW_NOTIFICATION` | `TITLE`, `TEXT` string | Kullanılmaz, metin loga yazılmaz. |
| `HINT` | `REPEAT` int | Kısa titreşim, REPEAT kez (en çok 5). Lüsid rüya ve horlama ipucu. |

SIZE'ın değer sayısı olduğu forumdaki geliştirici yanıtından çıkıyor: takip ekranı açıkken 1, kapanınca 12 (güçlü kanıt).

## 4. Saatten SAA'ya giden yayınlar (doğrulandı)

Hepsi açık hedefli: `setPackage("com.urbandroid.sleep")`.

| Eylem | Ek | Bizde |
|---|---|---|
| `CONFIRM_CONNECTED` | yok | Band 8 hazırken. |
| `HR_DATA_UPDATE` | `DATA` float[] | Geliş sırasıyla nabız (vuruş/dk). Belge "birkaç dakikada bir göndermek yeterli" diyor. |
| `DISMISS_FROM_WATCH` | yok | Bantta alarm ekranında reddet. |
| `SNOOZE_FROM_WATCH` | yok | Bantta alarm ekranında sessize al. |
| `DATA_UPDATE` | `MAX_DATA`, `MAX_RAW_DATA` float[] | **Gönderilmez** (bkz. 5). |
| `PAUSE_FROM_WATCH`, `RESUME_FROM_WATCH` | yok | Kullanılmaz. |

## 5. Band 8 tarafı

| Konu | Bizde | Etiket |
|---|---|---|
| Canlı nabız | 8/45 başlat, 8/46 durdur, 8/47 olayı. Health alan 39 > 4 nabız. 11-250 dışı geçersiz. | güçlü kanıt (BAND8_PROTOKOL 7.1). Gelişmiş > "Canlı nabız 30 sn" ile cihazda denenecek |
| Akış tazeleme | 60 sn olay gelmezse 8/45 yeniden (en çok dakikada bir). Gadgetbridge tazeleme yapmıyor; band akışı durdurulana kadar sürdürüyor olabilir | tahmin |
| Hareket verisi | **Yok.** Bandda ham ivme komutu bilinmiyor. Sahte hareket gönderilmez. Gadgetbridge Xiaomi bantlarında gizli bir antrenman başlatarak hareket aldığını yazıyor; bu yol bizde yok | güçlü kanıt (yokluk) |
| Alarm | Bantta "Alarm" adlı arama ekranı (7/0) ve 5 sn'de bir 2/59 titreşimi (üç uzun vuruş). En çok 5 dk. | 2/59 doğrulandı (build 191), arama ekranının alarm için kullanımı tahmin |
| Banttan kapat/ertele | Arama ekranında reddet (7/2) = kapat, sessize al (7/5) = ertele. Gerçek arama sürüyorsa olay aramaya aittir | 7/2 doğrulandı, eşleme tahmin |
| İpucu | 2/59, 200 ms vuruş, 400 ms ara, REPEAT kez | 2/59 doğrulandı |

Bağlıyım onayı yalnızca Band 8 hazırken gider. Onay verilince SAA saati sensör sayar. Hareket gelmediği için SAA'nın hareket grafiği boş kalabilir ya da telefon sensörüne dönebilir (tahmin, cihazda bakılacak).

## 6. LS05

LS05'te istekle açılan canlı nabız komutu yok. Saat `18 03` ile 10 dakikada bir kendiliğinden nabız yolluyor (LS05_PROTOKOL). Bu yüzden LS05 için onay gönderilmez; SAA saatsiz (telefon sensörüyle) çalışır. Gelen yayınlar loga "etkin saat Band 8 değil" diye yazılıp yok sayılır.

## 7. Pil ve gizlilik

- Akış yalnızca SAA takibi sürerken, "Nabız gönder" açıkken, band hazırken ve duraklatma yokken açıktır. Takip bitince, duraklatmada, ayar kapanınca 8/46 gider. Bağlantı koparsa akış kapalı sayılır; yeniden bağlanınca 8/45 yeniden gider.
- Kimsenin istemediği 8/47 gelirse (ör. uygulama gece yeniden başladı) en çok dakikada bir 8/46 gönderilir.
- Log: komut adları, durum ve sayılar (olay, geçerli, paket, yeniden başlatma). 30 dakikada bir ve takip sonunda özet. Nabız değeri, alarm saati, bildirim metni loga yazılmaz. 8/47 için tek tek "gelen komut" satırı yazılmaz.
- Bütün gece canlı akışın bandın pilini ne kadar yediği bilinmiyor (BAND8_PROTOKOL açık soru 18). Bir gece ölçülecek.

## 8. Açık sorular

1. 8/47 hangi sıklıkla geliyor, akış kendiliğinden duruyor mu (deneme: "Canlı nabız 30 sn").
2. SAA DIY onayı ve nabız paketi kabul ediliyor mu (SAA grafiğinde nabız çizgisi).
3. Hareket gelmeyince SAA ne yapıyor.
4. Bantta "Alarm" arama ekranı, reddet ve sessize al SAA'da alarmı kapatıyor/erteliyor mu.
5. Bir gecelik pil tüketimi.
