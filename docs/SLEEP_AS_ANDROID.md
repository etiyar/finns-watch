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
| `DATA_UPDATE` | `MAX_DATA`, `MAX_RAW_DATA` float[] | "Hareket gönder" açıkken (alpha2). 10 sn'lik pencereler, batch boyu kadar pencere dolunca. Duraklatmada 0. Bkz. 5.1. |
| `PAUSE_FROM_WATCH`, `RESUME_FROM_WATCH` | yok | Kullanılmaz. |

## 5. Band 8 tarafı

| Konu | Bizde | Etiket |
|---|---|---|
| Canlı nabız | 8/45 başlat, 8/46 durdur, 8/47 olayı. Health alan 39 > 4 nabız. 11-250 dışı geçersiz. | güçlü kanıt (BAND8_PROTOKOL 7.1). Gelişmiş > "Canlı nabız 30 sn" ile cihazda denenecek |
| Akış tazeleme | 60 sn olay gelmezse 8/45 yeniden (en çok dakikada bir). Gadgetbridge tazeleme yapmıyor; band akışı durdurulana kadar sürdürüyor olabilir | tahmin |
| Hareket verisi | alpha1'de yoktu. alpha2: "Hareket gönder (deneysel)" açıkken gizli antrenman ve ivme akışı (5.1) | güçlü kanıt, cihazda denenmedi |
| Alarm | Bantta "Alarm" adlı arama ekranı (7/0) ve 5 sn'de bir 2/59 titreşimi (üç uzun vuruş). En çok 5 dk. | 2/59 doğrulandı (build 191), arama ekranının alarm için kullanımı tahmin |
| Banttan kapat/ertele | Arama ekranında reddet (7/2) = kapat, sessize al (7/5) = ertele. Gerçek arama sürüyorsa olay aramaya aittir | 7/2 doğrulandı, eşleme tahmin |
| İpucu | 2/59, 200 ms vuruş, 400 ms ara, REPEAT kez | 2/59 doğrulandı |

Bağlıyım onayı yalnızca Band 8 hazırken gider. Onay verilince SAA saati sensör sayar.

### 5.1 Hareket: gizli antrenman ve ivme (alpha2)

**Cihaz gözlemi (build 212, alpha1):** SAA "SmartWatch connected: update interval 10s" gösterdi; "Test sensor (Wearables)" ekranı yalnızca döndü, grafik çıkmadı. Bizim log: onay, 8/45, SET_BATCH_SIZE, nabız paketleri (5 ve 23 paket), 8/46. Yani nabız gitti, hareket gitmedi.

**Neden (güçlü kanıt):**
- SAA belgesi hareket verisini 10 sn'lik pencerelerle bekliyor ve batch boyuna uyulmazsa "grafikte boşluk" olacağını yazıyor (doğrulandı). Nabzın tek başına yeterli olduğunu hiçbir yerde yazmıyor (doğrulandı: belgede yok).
- "update interval 10s" = SAA batch boyunu 1 yaptı, yani her 10 sn'de bir `DATA_UPDATE` bekliyor (forumdaki geliştirici yanıtı, güçlü kanıt).
- Gadgetbridge Xiaomi bantları için hareket verisini almak üzere gizli antrenman başlatıyor; Bangle.js için ayrı ivme uygulaması şart koşuyor. Nabızla yetinmiyorlar (güçlü kanıt).
- Notify da `DATA_UPDATE` gönderiyor (`MAX_DATA`, `MAX_RAW_DATA`, ayrıca belgede olmayan `MIN_DATA`, `SUM_DATA`).

**Nabız yayınımız belgeyle aynı (doğrulandı):** eylem `com.urbandroid.sleep.watch.HR_DATA_UPDATE`, ek `DATA`, tür float[] (int değil), hedef paket `com.urbandroid.sleep`, zaman damgası yok (belgede de yok). Notify da aynı biçimi kullanıyor.

**Bandın ivme kaynağı (güçlü kanıt, Notify incelemesi, kod alınmadı):**
- Notify SAA takibi başlayınca 8/45 ile birlikte 8/26 gönderiyor: Health alan 20 { 1 Unix sn, 2 { 1 saat farkı (15 dk, zigzag) }, 3 = 810 (uyku türü), 4 = 0 başlat, 6 = 3 }. Bitirirken 4 = 1 (duraklat), 500 ms sonra 4 = 3 (bitir).
- Band ivmeyi Health alan 15 içinde gönderiyor: alan 5 tekrarlı { 1 zaman, 2 x, 3 y, 4 z } float, mili-g. Notify ±1200 dışını atıyor. Hangi 8/x alt türünde geldiği bulunamadı; uygulama Health alan 15 taşıyan her 8/x'i okur ve alt türü ilk görüşte loga yazar.
- Hesap (SAA belgesi): her örnekte |Δx|+|Δy|+|Δz|, m/s²'ye çevrilir (× 9,806 / 1000). 10 sn'lik pencerenin en büyüğü `MAX_DATA`'ya, ivme büyüklüğü √(x²+y²+z²) en büyüğü `MAX_RAW_DATA`'ya. Örnek gelmeyen pencere eklenmez (uydurma yok), duraklatmada 0 eklenir.
- Bantta antrenman ekranı görünür (Gadgetbridge belgesi). Sabah bantta bir antrenman kaydı kalabilir (tahmin).
- Kimsenin istemediği ivme gelirse en çok dakikada bir antrenman bitirilir.

**"Hareket = adım değişimi" seçeneği neden yok:** 8/47'deki adım günlük birikmiş değer ve uykuda hemen hep 0 değişir. SAA bunu bütün gece "hiç kıpırdamadı" diye okur, derin uyku gibi yanlış bir grafik çıkar. İvme kaynağı bulunduğu için adım yolu eklenmedi. İvme cihazda çalışmazsa ayrı bir anahtar olarak, açıkça "adım değişimi" diye etiketlenip yeniden değerlendirilir.

**Varsayılan kapalı:** 8/26 cihazda doğrulanmadı. Önce Gelişmiş > "Gizli antrenman 30 sn" ile ivme sayısı görülmeli, sonra Ayarlar > Sleep as Android > "Hareket gönder (deneysel)" açılır.

**Log:** SAA'ya giden her yayının tam eylem adı ve değer sayısı yazılır (ör. `com.urbandroid.sleep.watch.DATA_UPDATE (1 değer)`). Değerler yazılmaz. `CONFIRM_CONNECTED` dakikada bir özetlenir. Takip özeti ivme örneği, pencere ve hareket paketi sayısını da verir.

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
3. Gizli antrenman (8/26) bantta ivme akışını açıyor mu, hangi alt türde, saniyede kaç örnek. SAA sensör testinde grafik çıkıyor mu.
4. Bantta "Alarm" arama ekranı, reddet ve sessize al SAA'da alarmı kapatıyor/erteliyor mu.
5. Bir gecelik pil tüketimi.
