# Haylou Solar LS05 | Protokol ve kadran başvurusu

Aşağıdaki bölümler önceki `docs/PLAN.md` dosyasından içerik değiştirilmeden alınmıştır. Tarihsel bağlamın tamamı [plan arşivindedir](arsiv/PLAN_ARSIV.md).

### 4.1 12 Kasım kaydından çözülen komutlar

Güven: Y = kayıt ve Haylou Fun kodu birebir uyuşuyor; O = tek kaynak veya az örnek.

Hello Haylou kuralı: o uygulama birçok saat markasını destekliyor. Yalnızca LS05 ile aynı UUID'leri kullanan UTE sürücüsü (`c8/a3`) okunur ve oradan gelen her bilgi, kayıt veya Haylou Fun ile doğrulanana kadar "aday" sayılır. Tek başına Hello Haylou'ya dayanan komut gönderilmez. Yasak listesi oradan da teyitli: `07 00` = eşleşmeyi silme.

| Komut | Biçim | Güven |
| --- | --- | --- |
| Alarm `06` | `06 [gün maskesi] [sa] [dk] 02 08 06 00 [yuva 1..3]`; kapalıysa `06 60 [sa] [dk] 00 00 00 00 [yuva]`. Maske bit0 pazar … bit6 cumartesi (62 hafta içi, 127 her gün, 65 hafta sonu). Yanıt `06 [yuva]`. Önce `87 00` (alarm sayısı sorgusu) gönderiliyor. | Y |
| Rahatsız etme `15` | `15 00 [baş sa] [baş dk] [bit sa] [bit dk] [01/00]`, yankı aynısı | Y |
| Hava `11` | 3 paket: `11 01` bugün (kod 2B LE, şimdi/yüksek/düşük, PM2.5 2B, hava kalitesi 2B, şehir UTF-16BE en çok 8 bayt), `11 02` 4 gün, `11 03` 2 gün. Eksi sıcaklıkta bit7. Yanıt `11 01/02/03`. | Y düzen, O kod anlamları |
| Hava kodları | 1 güneşli, 2 parçalı bulutlu, 3 kapalı, 4 sağanak, 5 gök gürültülü, 6 karla karışık, 7 çisenti, 8 şiddetli yağmur, 9 kar, 10 toz, 11 pus, 12 fırtına (başka saat sürücüsünün eşlemesinden) | O |
| Adım geçmişi `0a` | İstek `0a [YY YY AA GG] 00 00` (o günden beri). Kayıt 18 bayt: tarih, saat, toplam 2B, koşu (baş dk, son dk, dk, adım 2B), yürüme (aynı). Sonu `0a fd [XOR]`. Okuma kayıtları silmiyor (aynı kayıt iki eşitlemede geldi). | Y toplam, O dakika alanları |
| Canlı adım `09` | Aynı 18 baytlık düzen; saat yürürken kendiliğinden saniyede bir yollar, istek yok. | Y |
| Nabız geçmişi `18 FA` | İstek `18 FA [tarih]`. Önce `18 04` özeti (en yüksek, en düşük, ortalama), sonra `18 [tarih] [bitiş saati] + 12 değer` (10 dk aralık, FF yok; blok bitiş saatinden 2 saat öncesini kapsar), sonu `18 fd [XOR]`. | Y |
| Canlı nabız `18 03` | `18 03 [tarih] [sa] [dk] [nabız]`, 10 dakikada bir kendiliğinden | Y |
| Geçmiş sonu XOR | Kayıtların komut baytı hariç XOR'u; 4/4 eşitlemede tuttu | Y |
| Uyku `1d 01 [tarih]` | Hello Haylou: başlangıç `1d 01 …`, kayıtlar `1e …` ayrı kanaldan (`61FF/6102`, uygulama zaten abone), son `1d 02`. Kayıtta yalnızca `1d 02` geldi (o gece veri yoktu). `1e` iç düzeni çözülmedi; uygulama bilinmeyen çerçeveyi ham logladığı için bir gece sonraki log yeterli. | O |
| SpO2 `86 FA` | İstek `86 FA [tarih] 00 00` (Haylou Fun), veri `86 FA …`, son `86 FA FD` (Hello Haylou). İki uygulama uyuşuyor. | O |
| Hareketsizlik uyarısı `14` | `14 [açık] [aralık dk] 02 05 00 00 [baş sa] [baş dk] [bit sa] [bit dk] [öğle arası]` (Haylou Fun); Hello Haylou da `14` diyor | O |
| Durum `0c 02` | Yanıt `0c 02 [bayrak]`; rahatsız etme açılınca 0x42 → 0x4a (bit 0x08) | O |
| Müzik `12` | `12 0c [1 çalıyor / 2 durdu]`, `12 0d [ses %]`; saat düğmelerden `12 xx` yollayabilir. v0.5'te çözüldü, bkz. 4.2. | Y |
| Dil `25 ab [kod]` | Kayıtta 02 (İngilizce). Haylou Fun tablosunda `tr_TR` = 16. Saat yazılımı Türkçe menüyü destekliyor mu bilinmiyor; tek kontrollü test adayı. | O |
| Birim/saat biçimi `01` | `01 [mesafe birimi] [saat biçimi]` | Y |
| Profil `05` | Boy, kilo, yaş, cinsiyet, adım hedefi (8000 = `00 00 1f 40`) vb. Kişisel veri: logda gizlenir, depoya girmez. Tam düzen 4.2'de. | Y |

### 4.2 v0.5.0 bulguları (13 Kasım, kod okuması + 11 ve 12 Kasım kayıtları)

Kaynaklar: Haylou Fun (`youcy/event`, `com.yingsheng.protocol.youcy.task`, `rd/b`), Hello Haylou (`c8/a3` ve `MiBandSupport` geri çağrısı, classes10 tek sınıf yeniden açıldı), kayıtlar (`nt.log` 11 Kasım: iki gelen arama; `s.log` 12 Kasım). Y = iki kaynak ve kayıt tutarlı; O = tek kaynak ya da kaynaklar arasında fark var.

| Komut | Biçim | Güven |
| --- | --- | --- |
| Gelen arama metni | `0F 00 00 [uzunluk]` + ad (UTF-16BE, en çok 30 bayt = 15 birim), devam `0F 01 …`, `0F FD`. Tip 0, `0F` bildirimiyle aynı paketleme. Haylou Fun `UpdateMobCallStatusEvent`/`Task`, Hello Haylou `ALERT_CALL_TEXT`, kayıtta iki arama (`0F 00 00 18`, `0F 00 00 0A`). | Y |
| Arama bitti | `0D 04`; saat aynen yankılıyor. Haylou Fun (arama bitince), Hello Haylou `ALERT_CALL_TEXT_STOP`, kayıt. | Y |
| Arama titreşimi | Düzen `06 00 00 00 [sayı] [süre] 00 00` (Haylou Fun `SetVibrateEvent`). Değer kaynaklarda farklı: kayıtta `FF FF`, Haylou Fun 4.0.7 kodunda `03 20`, Hello Haylou `01 0A 02 01`. Uygulama kayıttakini (`FF FF`) gönderiyor, bitişte mutlaka durduruyor, en geç 60 sn sonra susturuyor. | O |
| Titreşimi durdur | `06 00 00 00 00 00 00 00`; Haylou Fun, Hello Haylou `VIBRATE_END`, kayıt aynı. Yanıt `06 00`. | Y |
| Cevapsız arama | Ayrı komut yok; tip 3 bildirim (Haylou Fun `SendMsgEvent` arama öğesi 3 başlıksız gönderir, Hello Haylou tablosunda 3 arama). Saatteki simgesi denenmedi. | O |
| Saatten kumanda | `12 [kod]`: `07` oynat/duraklat, `08` sonraki, `09` önceki, `0D` ses aç, `0E` ses kıs, `0A` telefonu bul (ek bayt yoksa aç/kapa, `01` başlat, `00` durdur), `02` aramayı reddet, `11`/`0F` kamera aç/kapat. `D1 0A` de telefonu bul. Haylou Fun `OnClickKeyTask` + `rd/b`, Hello Haylou `a3` (tam 2 baytlık paketler) tutarlı. Kayıtta saatten gelen tuş yok; ilk kullanım kontrollü test. Uygulama müzik ve ses kodlarını yalnızca tam 2 baytlık pakette uygular (`12 0D [ses]` yankısı döngü yapmasın); `02`, kamera ve bilinmeyenler yalnızca loglanır. | Y kod, test bekliyor |
| Müzik durumu | `12 0C [1 çalıyor / 2 durdu]` (yanıt `12 0C 00`), `12 0D [ses %]`. Haylou Fun `MusicControlTask`, Hello Haylou `SET_MUSIC_STATE`, kayıt (`12 0c 02`, `12 0d 06`, `12 0d 64`). Uygulama bağlanınca ve kumandadan sonra gönderiyor. | Y |
| Profil ve ekran `05` | `05 [boy 2B] [kilo 2B] [ekran sn] [hedef 4B] [bilek 0/1] [nabız üst, kapalı FF] 00 [yaş] [cinsiyet 1 erkek / 2 kadın] [kayıp uyarısı] [dil] [mesafe 1 km / 2 mil] [nabız alt, kapalı 00]`. Haylou Fun `SetDeviceParamsEvent` ve Hello Haylou `u0` aynı düzen. Kayıttaki 39 paketin 38'i uygulamanın üreticisiyle bayt bayt aynı (yerel kontrol, değerler yazılmadı); farklı olan tek paket başka bir oturumda nabız uyarıları ve dil baytı değişmiş hali. Yanıt tek bayt `05`. | Y |
| `05` bayt 15 ("d6") | Haylou Fun `watch_device_lost_warning` ayarı: saatin bağlantı kopunca uyarması. Hello Haylou hep 0, kayıtta 0. Uygulama 0 gönderir. | O |
| `05` bayt 16 ("E") | Haylou Fun `SetWatchLanguageEvent.E()`: dil kodu (`25 AB` ile aynı tablo, 2 İngilizce, 16 Türkçe). Hello Haylou hep 2, kayıtta 2 (bir oturumda 1). Uygulama 2 gönderir. | Y anlam, O etki |
| Ekran süresi seçenekleri | 5, 10, 15 sn (Haylou Fun ayar penceresi). | Y |

Gönderilmeyenler: `12 02` ile aramayı reddetme (ek izin gerekir), kamera kumandası, `D1` ile başlayan diğer kodlar (yalnızca Hello Haylou'da), Türkçe dil kodu (ayrı kontrollü test).



### Kadran 1. kapı (14 Kasım): dosya biçimi çözüldü

Kaynak: 12 Kasım kaydındaki yükleme (2426 yazma) birebir yeniden kuruldu: 584.296 bayt. Ağ gerekmedi; Haylou sunucularına bu ortamdan erişilemiyor. Dosya üreticiye ait, repoya konmaz.

Aktarım (Haylou Fun `SendPlateTask`, kayıtla birebir):
- `1a 01` yapılandırma (yanıt `1a 01 ff ff ff ff 00 f0 00 f0 00 00`: 240x240).
- `1a 02` başlat, saat `1a 02` ile hazır der.
- Dosya 4096 baytlık bloklara, bloklar MTU-3 (241) baytlık parçalara bölünür. Her parça `61FF/6101` üzerine yanıtsız yazma: `1b [sıra hi] [sıra lo] + veri`. Sıra 0'dan dosya sonuna kadar artar. Her 10 yazmada 30 ms bekleme.
- Her blok sonunda saat `26 03 04` (devam) der. `26 03 03 [hi] [lo]`: veri kopuk, o sıradan yeniden gönder. `26 03 02` ve diğerleri: hata, aktarım durur.
- Bitiş: uygulama `1a 03 00`, saat `1a 03 00` ile başarı der. Haylou Fun 10 sn yanıtsızlıkta hata sayar.

Dosya biçimi (doğrulandı):
- 24 bayt başlık: `[kimlik u16 LE][00 00][veri boyu u32 LE = dosya-24][CRC32 u32 LE, 24. bayttan sona][en 240][boy 240][00 01][ff x6]`.
- CRC32 standart zlib CRC'si, kayıttaki dosyada eşleşti. Saatin hata kodları (`26 03 02`, Haylou kodunda "INVALID CRC ERROR") saatin dosyayı doğruladığını gösteriyor. Kanıt değil, güçlü işaret.
- 0x18'den itibaren 24 baytlık öğe tablosu, 0 türüyle biter: `[tür u16][en u16][konum u32][boy u16][x u16][y u16][00 00][kare sayısı u8][7 bayt, kopyalanır]`.
- Görüntüler sıkıştırmasız RGB565, büyük uçlu (big-endian). Konum dosya başına göre.
- Kayıttaki kadranın öğeleri: 1 akrep, 2 yelkovan, 3 saniye (240x240, saat döndürür), 10 önizleme, 17 arka plan, 13 gün rakamları (`-./0123456789:`, 14 kare), 15 gün adları (7 kare), 25 adım rakamları.

Sonuç: özel kadran yapılabilir. En güvenli yol, bu dosyanın yapısını aynen koruyup yalnızca arka plan ve önizleme görüntüsünü değiştirmek ve CRC'yi yeniden hesaplamak.
Sıradaki karar (kapı 2): kayıttaki resmi dosyayı bizim uygulamadan birebir yüklemek.

### Kadran 2. kapı (14 Kasım 01:45): GEÇTİ
- v0.7.0 kayıttaki resmi dosyayı yükledi: 2426 paket, 44 sn, saat `1a 03 00` ile kabul etti, kadran çalışıyor.
- `1a 01` yanıtı: `1a 01 [kadran kimliği u32 BE] [en] [boy] 00 00 10 00 00`. 12 Kasım'da kimlik `ff ff ff ff` (yerleşik kadran), şimdi `00 00 13 8a` = dosya başlığındaki 5002. Saatteki kadran buradan tanınabilir.
- Aktarım sırasında saat `1a 03 03 06 d9` ile 1753. paketten yeniden gönderim istedi (yanıtsız yazmada kayıp). Yeniden gönderim yolu çalıştı. Ack ön eki `26` değil `1a` da olabiliyor; kod ikisini de kabul ediyor.
- Son blokta onay gelmedi; 3 sn sonra bitiş gönderildi, saat kabul etti (Haylou Fun da onay beklemiyor).
- Gözlem: ekran açılınca saniye ibresi birkaç saniye hızlıca yerine dönüyor. Dosya birebir aynı olduğundan büyük olasılıkla saat yazılımının davranışı; Haylou Fun ile yüklenmiş hâlinde de olup olmadığı soruldu.

### Kadran yazıcı cihaz testleri (14 Kasım 13:19): 1 ve 2 GEÇTİ
- Test 1: FW Logo, FaceWriter ile yeniden yazılmış, kimlik 0xF0F0. Saat kabul etti.
- Test 2: ibresiz dijital (tür 17, 10, 4, 5), FaceWriter çıktısı. Saat gerçek saati doğru gösterdi (13:19): 14 karelik "-,/0123456789:" sırası, hücre yerleşimi ve ibresiz kadran doğrulandı.
- Sıradaki: aşama 1 .tbwf derlemesi (Mus ya da 1526); ortak sprite ve katman sırası ayrı denenecek.

### v0.8.1 cihaz testi (14 Kasım 14:46): GEÇTİ
- Hava durumu (Open-Meteo → `11 01/02/03`) saatte göründü. WMO → saat kodu eşlemesi kullanımda.
- FW kadranlarında Türkçe gün adı ve yeni adım kutusu.

## v0.10: son görülen yer ve saatteki kadran

Son görülen yer:
- Ayarlar > Saat > "Son görülen yeri kaydet", varsayılan kapalı. Açılınca konum izni istenir (Android 12+ pencerede yaklaşık ya da kesin). Arka plan konum izni istenmez.
- Beklenmeyen kopmada (`LinkAlert.arm` ile aynı an) konum alınır: API 30+ `getCurrentLocation` en çok 10 sn; gelmezse NETWORK/PASSIVE/GPS son konumlarından en yenisi, yalnızca son 5 dakikadaysa (`LastSeenRules.pick`).
- Yalnızca son kayıt `Prefs`'te; zaman kopma anı. Loga konum yazılmaz. Ayar kapanınca kayıt silinir.
- Uyarı bildiriminde "Son görülen: 14:32 (±40 m)" ve "Haritada aç" (`geo:` adresi). Önceki kopmadan kalan kayıt gösterilmez (`belongsTo`).
- Servis türü `connectedDevice|location`. Location yalnızca ayar açık ve izin varken eklenir; eklenemezse (Android 14+ arka planda başlatma) connectedDevice ile devam edilir, uygulama öne gelince yeniden denenir. İzin sonradan verilince servis durdurulmaz, `startForeground` yeniden çağrılarak türe eklenir.

Saatteki kadran (`1a 01`):
- Aşama A: Ayarlar > Gelişmiş > "Kadranı sor (1a 01)". Yalnızca `1a 01` gider, yanıt loga yazılır (kimlik onaltılık, en/boy). Tek başına gönderim cihazda denenmedi; sonucu buraya yazılacak.
- Aşama B: "Saatteki kadranı otomatik sor", varsayılan kapalı. Açıksa bağlanınca bir kez (8 sn sonra) ve kadran yüklemesi bitince sorulur; yükleme sürerken hiç sorulmaz. Yanıt `WatchHub.faceOnWatchId`; galerideki işaret `FaceMatch` ile: tek aday o, birden çok aday varsa yalnızca en son yüklenen aralarındaysa o; `ff ff ff ff` "Saatte yerleşik kadran"; eşleşme yoksa "Saatteki kadran galeride yok".
- `tools/face_builder.py --id` (varsayılan 0xF100): her FW kadranı ayrı kimlik alır.

### 16 Kasım: alarm baytları ve testler
- Alarm komutu `06 [gün] [sa] [dk] 02 [N] [G] 00 [yuva]`: N = titreşim sayısı (8 → 8 kez ~10 sn, 16 → 16 kez ~20 sn, 30 → 30 kez ~45 sn; denendi). G büyük olasılıkla güç; 6'da kalıyor (iki uygulama da 6).
- Saatten aramayı reddetme ve cevapsız arama (tip 4) cihazda doğrulandı. Telefonu bul doğrulandı.

### 17 Kasım: kadran bulguları (saatte denenen 6 kadran + 3 topluluk kadranı)
- Saat sayıları sabit genişlikli alanın ORTASINA yazıyor (sola yaslamıyor). Alan genişlikleri Hello Haylou örnek uzunluklarıyla aynı görünüyor: adım 5, mesafe 5, kalori 4, nabız 3, pil 3, sıcaklık 3, saat/dakika/saniye/gün/ay 2, yıl 4. Topluluk kadranları da işareti alanın hemen sağına koyuyor (sıcaklık x=60, hücre 10, ° x=89).
- İbre kuyruk baytları: b0 = ibrenin toplam boyu (merkezin altına uzanan kısım dahil), b1 = uçtan merkeze uzaklık, b2 = genişlik. Örnek: saniye 8d 67 04 → toplam 141, uç-merkez 103, 38 px kuyruk. Saat ibre resminin yalnızca bu şeridini çiziyor; tam disk için (240, 120, 240) denenecek.
- Yeni türler (topluluk kadranlarında çalışıyor): 7 sabit iki nokta (1 kare), 12 ay adı (12 kare, OCA…ARA yazılabilir), 18 yanıp sönen iki nokta (2 kare: açık/kapalı), 22 derece işareti, 34 yüzde işareti.
- Nane yeşili (#7FE3C3) saatte mavimsi görünüyor; saat kadranlarında daha yeşil ton gerekli.

