# Xiaomi Smart Band 8 protokolü

Bu belge V12 planının 1. adımıdır (bkz. `docs/V12_BAND8_PLAN.md`, bölüm 8). Amacı: Band 8 desteğini yazacak kişiye gereken her şeyi tek yerde vermek. Kod içermez.

## 0. Kaynaklar ve güven düzeyleri

Her bulgunun yanında bir etiket var:

- **doğrulandı**: 17 Kasım Bluetooth kaydında doğrudan görüldü. Şifreli içerik, kullanıcının auth key'i ile çözüldü ve okundu (bkz. 4.5).
- **güçlü kanıt**: Notify for Xiaomi, Gadgetbridge ya da Mi Fitness yapılandırmasında açıkça görüldü, kayıtla çelişmiyor. Ama kayıtta bu içerik yoktu.
- **tahmin**: Dolaylı çıkarım. Cihazda denenmeden kullanılmaz.

Cihaz: Xiaomi Smart Band 8, küresel sürüm, yazılım 2.3.14. Mi Fitness model kimliği `miwear.watch.m66gl`.

Kaynaklar:

1. **Bluetooth kaydı:** Notify ile Band 8 arasında, yaklaşık 16 dakika. Repo dışında duruyor. MAC adresi, anahtar ve içerik bu belgeye yazılmadı. Zamanlar kaydın başından saniye olarak verildi.
2. **Notify for Xiaomi 24.0.4:** Yalnızca protokolü anlamak için okundu. Kod kopyalanmadı.
3. **Gadgetbridge:** Herkese açık belgeler ([Xiaomi protobuf sayfası](https://gadgetbridge.org/basics/topics/xiaomi-protobuf/), [sunucudan anahtar alma](https://gadgetbridge.org/basics/pairing/huami-xiaomi-server/)) ve kaynak kodun davranışı. Kod AGPL. Hiçbir satırı ve `.proto` dosyası kopyalanmadı. Aşağıdaki alan numaraları olgudur, kendi tablolarımızla anlatıldı.
4. **Mi Fitness günlükleri:** Telefondaki Mi Fitness uygulamasının metin günlükleri. Her satır: tarih, uygulama sürümü, düzey, etiket, mesaj. Mesajların bir kısmında JSON var: ürün yapılandırması (model başına özellik listesi), cihaz durumu (pil, şarj) ve eşitlenen sağlık özetleri (uyku süreleri gibi). Yalnızca anahtar adları ve özellik listesi kullanıldı. Hiçbir değer bu belgeye alınmadı.

Önemli genel not: Gadgetbridge belgeleri bu aileyi hâlâ "deneysel" diye tanımlıyor. Bazı cihazlarda uyku evreleri çalışmıyor. Üçüncü taraf uygulamalara canlı nabız aktarımı yok.

## 1. Bağlantı ve GATT

### 1.1 Servisler (doğrulandı)

Band 8 yalnızca BLE ile konuştu. Kayıtta görülen servisler:

| Servis | Tanıtıcı aralığı | Not |
|---|---|---|
| 0x1800 GAP | 0x0001-0x0007 | standart |
| 0x1801 GATT | 0x0010-0x0015 | standart |
| 0x180A Device Information | 0x0030-0x0040 | standart, Notify okumadı |
| 0x1812 HID | 0x0050-0x0073 | Notify kullanmadı (tahmin: kamera ve medya tuşları) |
| 0x180F Battery | 0x0088-0x008B | standart pil, Notify okumadı |
| 0xFE95 Xiaomi | 0x0100-0x0120 | asıl protokol burada |
| 0xFDAB | 0x0150-son | Notify kullanmadı, amacı bilinmiyor |

Tanıtıcı numaraları cihaza ve yazılım sürümüne göre değişebilir. Uygulama UUID ile aramalı, tanıtıcıyı sabit yazmamalı.

### 1.2 0xFE95 içindeki karakteristikler (doğrulandı)

UUID'ler 16 bitlik Bluetooth taban UUID'si biçiminde: `0000xxxx-0000-1000-8000-00805f9b34fb`.

| UUID | Değer tanıtıcısı | Özellik | Görev |
|---|---|---|---|
| 0x0050 | 0x0102 | okuma | kullanılmadı |
| 0x0051 | 0x0104 | yazma, yanıtsız yazma, bildirim | **komut okuma**: band buradan komut gönderir, telefon buraya onay yazar |
| 0x0052 | 0x0107 | aynı | **komut yazma**: telefon buraya komut yazar, band buradan onay gönderir |
| 0x0053 | 0x010A | aynı | **etkinlik verisi**: sağlık dosyaları buradan gelir |
| 0x0054 | 0x010D | aynı | kullanılmadı |
| 0x0055 | 0x0110 | aynı | **veri yükleme**: bildirim simgesi, kadran, yazılım |
| 0x0056 | 0x0113 | aynı | kullanılmadı |
| 0x0057 | 0x0116 | aynı | kullanılmadı |
| 0x0058-0x005A | 0x0119-0x011F | aynı | kullanılmadı |

- Notify sekiz CCCD'yi de açtı (0x0105, 0x0108, 0x010B, 0x010E, 0x0111, 0x0114, 0x0117, 0x0120). Gadgetbridge yalnızca dördünü açıyor: 0051, 0052, 0053, 0055 (güçlü kanıt). Bizim için dördü yeterli olmalı (tahmin).
- Telefonun bütün yazmaları **yanıtsız yazma** (ATT Write Command, 0x52). Band bildirim (0x1B) ile yanıt verdi (doğrulandı).

### 1.3 MTU, bağ ve bağlantı (doğrulandı)

- Band, MTU 247 istedi. Telefon 517 dedi. Sonuç 247. Bir yazmanın en büyük boyu 244 bayt (247 eksi 3).
- Kayıtta SMP eşleşmesi yok. Bağlantı başında bir kez bağlantı şifrelemesi açıldı (LE Start Encryption). Yani band, Android'de önceden **bağlı (bonded)** idi.
- Notify, auth'tan önce bağ durumuna bakıyor. Bağ yoksa önce bağ kuruyor (güçlü kanıt).
- Kayıt boyunca tek bağlantı vardı. Notify aynı bağlantıda başlangıcı iki kez baştan yaptı (bkz. 3.4).

## 2. Paket çerçevesi

### 2.1 Çerçeve türleri (doğrulandı)

Her yazmanın ya da bildirimin ilk iki baytı küçük endian bir **parça sırası**dır. Sıra 0 ise paket bir kontrol paketidir ve üçüncü bayt türü verir. Sıra 1 ya da daha büyükse paket, parçalı bir mesajın parçasıdır.

| Baytlar | Anlam |
|---|---|
| `00 00 02 02` + veri | tek paket, **şifresiz** (yalnızca auth) |
| `00 00 02 01` + sayaç (2 bayt LE) + şifreli veri | tek paket, şifreli, **telefondan banda** |
| `00 00 02 01` + şifreli veri | tek paket, şifreli, **banddan telefona**. Sayaç yok |
| `00 00 03 00` | onay (alındı) |
| `00 00 00 01` + parça sayısı (2 bayt LE) | parçalı gönderim başlıyor, 01 = şifreli |
| `00 00 01 01` | "hazırım, parçaları gönder" |
| `00 00 01 00` | "bütün parçalar alındı" |
| sıra (2 bayt LE, 1'den başlar) + veri | parça |

Ek kontrol yanıtları (güçlü kanıt, Gadgetbridge): `00 00 01 02` reddetme. `00 00 01 05` + eksik parça sıraları: "şu parçaları yeniden gönder". `00 00 03` + sıfırdan farklı bayt: olumsuz onay.

### 2.2 Onay akışı (doğrulandı)

- Telefon 0x0052'ye tek paket yazar. Band 0x0052'den `00 00 03 00` gönderir. Sonra yanıt mesajını 0x0051'den gönderir.
- Telefon, banddan gelen her tek pakete aynı karakteristiğe `00 00 03 00` yazarak onay verir.
- Notify bazı onayları ve "hazırım" yanıtlarını iki kez yazdı. Band bundan etkilenmedi. Biz bir kez yazacağız.
- Gadgetbridge her karakteristikte aynı anda tek mesaj bekletir. Onay gelmeden sıradakini göndermez (güçlü kanıt). Bunu biz de yapacağız.

### 2.3 Parçalama (doğrulandı)

- Şifreli bir mesaj, 6 baytlık başlıkla birlikte 244 bayta sığmıyorsa parçalı gider.
- Akış: gönderen `00 00 00 01 NN NN` yazar. Alan `00 00 01 01` der. Gönderen bütün parçaları arka arkaya yazar. Her parça: 2 bayt sıra + en çok 242 bayt veri. Alan `00 00 01 00` ile bitirir.
- Parçalar birleştirilince ortaya **tek bir şifreli blok** çıkar. Şifre çözme birleştirmeden sonra yapılır.
- Telefondan banda parçalı komutlarda (0x0052) birleşik bloğun ilk 2 baytı yine sayaçtır. Kayıtta görülen değer, tek paketlerdeki sayaç dizisiyle uyumlu (doğrulandı).
- Veri yükleme karakteristiğinde (0x0055) sayaç önü yok. Sayaç 0 ile şifreleniyor (doğrulandı: bütün yüklemeler böyle çözüldü).
- Banddan gelen parçalı mesajda sayaç yok (doğrulandı).
- Kayıtta 2,9. saniyede band 2 parçalık bir gönderim başlattı. Parçalar kayıtta yok. Muhtemelen kayıt aracı kaçırdı. Aynı yanıt (kadran listesi 4/0, 300 bayt, 2 parça) 258 s'de eksiksiz geldi ve çözüldü (doğrulandı).

## 3. Kimlik doğrulama

### 3.1 Akış (doğrulandı)

Auth paketleri şifresizdir (`00 00 02 02`). Hepsi komut türü 1 (Auth).

1. **Telefon, alt tür 26:** Auth mesajının 30. alanında `PhoneNonce`. İçinde 1. alan: 16 bayt rastgele telefon nonce'u.
2. **Band, alt tür 26:** Auth mesajının 31. alanında `WatchNonce`. 1. alan: 16 bayt band nonce'u. 2. alan: 32 bayt HMAC.
3. **Telefon, alt tür 27:** Auth mesajının 32. alanında iki alan. 1. alan: 32 bayt telefon HMAC'i. 2. alan: şifreli cihaz bilgisi (yaklaşık 30 bayt).
4. **Band, alt tür 27:** Auth mesajının 33. alanında iki sayı. 1. alan: 1 (başarı). 2. alan: 15 bitlik bir sayı, üç auth'ta da aynı (tahmin: özellik maskesi).

Hata durumu: band 26'ya Auth mesajının 3. alanında bir durum kodu döndürebilir. Notify 4 değerini ayrı bir hata olarak ele alıyor (güçlü kanıt). Anlamı bilinmiyor (tahmin: anahtar geçersiz ya da eşleşme silinmiş).

Kayıtta auth üç kez yapıldı: 1,5 s, 2,6 s ve 258 s. Üçü de başarılı.

### 3.2 Anahtar türetme (doğrulandı: üç auth'ta band ve telefon HMAC'i tuttu)

Girdi: 16 baytlık **auth key** (kullanıcının sunucudan aldığı anahtar, onaltılık 32 karakter, başında `0x` olabilir).

1. `tuz = telefonNonce (16) + bandNonce (16)`
2. HKDF-SHA256: çıkarma adımında HMAC anahtarı tuz, veri auth key. Genişletme adımında bilgi metni `miwear-auth` (ASCII), çıktı 64 bayt. Bu standart RFC 5869 HKDF'dir.
3. Çıktı dörde bölünür:

| Bayt | Ad (bizim) | Kullanım |
|---|---|---|
| 0-15 | `bandAnahtari` | banddan gelenleri çözmek, band HMAC'ini doğrulamak |
| 16-31 | `telefonAnahtari` | telefondan gidenleri şifrelemek, telefon HMAC'i |
| 32-35 | `bandNonceOnu` | banddan gelenlerin 4 baytlık nonce başı |
| 36-39 | `telefonNonceOnu` | telefondan gidenlerin 4 baytlık nonce başı |

4. Band HMAC'i doğrulanır: `HMAC-SHA256(bandAnahtari, bandNonce + telefonNonce)` 2. adımdaki 32 baytla aynı olmalı. Değilse anahtar yanlıştır. Bağlantı kesilir, kullanıcıya "anahtar yanlış" denir.
5. Telefon HMAC'i: `HMAC-SHA256(telefonAnahtari, telefonNonce + bandNonce)`.

Notify'da bir de isteğe bağlı kullanıcı kimliği metni var. Doluysa iki HMAC verisinin sonuna ekleniyor. Kayıtta telefon nonce mesajında bu alan yoktu. Biz boş bırakacağız (tahmin: Band 8 için gerekmiyor).

### 3.3 Cihaz bilgisi (doğrulandı)

3. adımdaki şifreli blok, aşağıdaki küçük protobuf mesajın şifrelenmiş hâlidir. Şifreleme 4.1'deki yöntemle, `telefonAnahtari` ve sayaç 0 ile yapılır. Kayıttaki blok bu yolla çözüldü.

| Alan | Tür | Değer |
|---|---|---|
| 1 | uint32 | 0 (açıkça yazılmalı, kayıtta yazılmış) |
| 2 | float | Android API düzeyi (örnek 34.0) |
| 3 | string | telefon modeli |
| 4 | uint32 | telefon özellik maskesi. Notify büyük bir maske gönderdi. Gadgetbridge 224 gönderiyor |
| 5 | string | iki harfli bölge, büyük harf |

Kayıtta Notify bölge olarak telefonun bölgesini gönderdi ("CN" değil). Gadgetbridge de telefon dilinden alıyor. Biz bölgeyi telefondan alacağız. Maske için Gadgetbridge'in 224 değeriyle başlayacağız. Band reddederse Gelişmiş bölümünden Notify'ın değeri denenir (açık soru 2).

### 3.4 Sayaç ve oturum (doğrulandı)

- Her başarılı auth yeni bir oturum açar. Telefon sayacı **1'den** başlar ve her şifreli mesajda bir artar. Sayaç 0 auth için ayrılmış.
- Notify 254-258 s arasında CCCD'leri yeniden yazdı ve auth'u yeniden yaptı. Sayaç yine 1'den başladı.
- 6,28 s'deki "sayaç 1" mesajı çözüldü (doğrulandı). Notify bu mesajı **bir önceki oturumun** anahtarıyla şifrelemiş. Mesaj tür 23, alt tür 58 (anlamı bilinmiyor, Notify'a özgü). Band çerçeveyi `00 00 03 00` ile onayladı ama yanıt vermedi. Ders: **taşıma onayı, mesajın çözüldüğü anlamına gelmez.** Yeniden auth'tan sonra eski anahtarla hiçbir şey gönderilmez. Kuyrukta bekleyen mesajlar yeni oturumla yeniden şifrelenir.
- Notify telefon nonce'u olarak sabit bir değer de kullanabiliyor (ikinci auth bunu kullandı). Biz her auth'ta yeni rastgele nonce üreteceğiz.

## 4. Şifreleme

### 4.1 Telefondan banda (doğrulandı)

- Yöntem: **AES-128-CCM**, etiket 4 bayt, nonce 12 bayt, ek doğrulanan veri yok.
- Anahtar: `telefonAnahtari`.
- Nonce: `telefonNonceOnu (4) + 00 00 00 00 + sayaç (4 bayt LE)`.
- Şifreli veri = şifreli metin + 4 bayt etiket. Paketteki 2 baytlık sayaç bu nonce'taki sayaçtır.

### 4.2 Banddan telefona (doğrulandı)

- Aynı yöntem. Anahtar `bandAnahtari`.
- Nonce: `bandNonceOnu (4) + 8 bayt sıfır`. Yani **sayaç hep 0**.
- Etiket 4 bayt, mesajın sonunda.
- Etkinlik verisi (0x0053) ve parçalı mesajlar da aynı yolla çözülür.

### 4.3 Uygulama notu

- JDK ve Android'de hazır AES-CCM yok. CCM, AES/ECB üstünde kolayca yazılır (CBC-MAC + CTR). Saf Kotlin, `protocol/` içinde, standart test vektörleriyle (RFC 3610 ve NIST SP 800-38C) test edilir.
- HMAC-SHA256 için `javax.crypto.Mac` hem JVM'de hem Android'de var.
- Etiket doğrulanmayan mesaj atılır ve loga yalnızca "çözülemedi" yazılır.

### 4.4 Anahtarsız gözlem: bandın anahtar akışı tekrar ediyor (doğrulandı)

Band her mesajı aynı nonce ile şifreliyor. CCM'nin şifreleme kısmı CTR olduğu için, bir oturumdaki bütün band mesajları aynı anahtar akışıyla XOR'lanıyor. Her komut mesajı `08 <tür> 10 <alt tür>` ile başladığı için ilk baytların anahtar akışı bulunabiliyor. Böylece kayıttaki her band mesajının türü, alt türü ve ilk alan etiketi anahtarsız okundu. Bulunan bütün değerler Gadgetbridge'in bildiği tür ve alan numaralarıyla birebir uyuştu (örnek: tür 2 için alan 4, tür 7 için alan 9, tür 8 için alan 10, tür 17 için alan 19, tür 22 için alan 24). Bu, 4.2'yi ve protobuf yapısını güçlü biçimde doğruluyor.

Not: Bu bir Xiaomi güvenlik zayıflığı. Bizim için sonucu yok. Biz telefondan giden her mesajda sayacı artıracağız.

### 4.5 Anahtarla doğrulama sonucu (doğrulandı)

Kullanıcının auth key'i ile kaydın tamamı repo dışındaki araçla çözüldü. Anahtar yalnızca çalışma anında ortam değişkeninden okundu.

- Üç auth'un üçünde de band HMAC'i ve telefon HMAC'i 3.2'deki formülle birebir tuttu.
- Telefondan banda giden bütün şifreli tek paketler ve parçalı mesajlar (0x0052) 4.1 ile çözüldü. Sayaç dizisi her oturumda 1, 2, 3... diye gitti. Parçalı mesajlar da diziden bir numara aldı.
- Banddan gelen bütün mesajlar (0x0051 ve 0x0053) 4.2 ile, sayaç 0 ile çözüldü.
- Veri yükleme (0x0055) sayaç önü olmadan, sayaç 0 ile çözüldü.
- Çözülemeyen tek çerçeve kalmadı. Etiket (4 bayt) her mesajda doğrulandı.
- Bütün çözülmüş mesajlar 5.1'deki protobuf yapısına uydu.

## 5. Komut yapısı (protobuf)

### 5.1 Dış mesaj (doğrulandı: alan 1, 2, 3, 4, 6, 9, 10, 19, 24. Diğerleri güçlü kanıt)

Her mesaj tek bir `Command` mesajıdır.

| Alan | Tür | Anlam |
|---|---|---|
| 1 | uint32 | tür |
| 2 | uint32 | alt tür |
| 3 | mesaj | Auth |
| 4 | mesaj | System |
| 6 | mesaj | Watchface |
| 9 | mesaj | Notification |
| 10 | mesaj | Health |
| 12 | mesaj | Weather |
| 14 | mesaj | Calendar |
| 19 | mesaj | Schedule |
| 20 | mesaj | Music |
| 23 | mesaj | Phonebook |
| 24 | mesaj | DataUpload |
| 100 | uint32 | durum (bazı yanıtlarda 0 = başarı) |

Dikkat: **tür numarası ile alan numarası farklıdır.** Örnek: tür 2 (System) içeriği alan 4'e, tür 7 (Notification) içeriği alan 9'a yazılır.

| Tür | Konu | İçerik alanı |
|---|---|---|
| 1 | Auth | 3 |
| 2 | System | 4 |
| 4 | Watchface | 6 |
| 7 | Notification | 9 |
| 8 | Health | 10 |
| 10 | Weather | 12 |
| 12 | Calendar | 14 |
| 17 | Schedule | 19 |
| 18 | Music | 20 |
| 21 | Phonebook | 23 |
| 22 | DataUpload | 24 |

Protobuf proto2 kurallarıyla yazılır. Kendi küçük kodlayıcımızı yazacağız (varint, zigzag, uzunluklu alan, float). Kütüphane yok.

Kayıttan gözlem (doğrulandı): Notify değeri 0 ya da false olan bazı isteğe bağlı alanları hiç yazmadı (saat eşitlemede yaz saati farkı ve 12 saat bayrağı, bildirimde "arama mı"). Band bunları sorunsuz kabul etti. Kural: belgede "açıkça yazılmalı" denen alanlar (örnek 3.3 alan 1) her zaman yazılır. Diğer sıfır alanlar yazılmayabilir.

Tür 23 ve Command alan 25 kayıtta bir kez görüldü (Notify'a özgü, eski oturum anahtarıyla, bkz. 3.4). Bizim için gerekmiyor.

### 5.2 Kayıtta görülen komutlar (doğrulandı: hepsi çözüldü)

| Zaman (s) | Yön | Tür/alt tür | Anlam |
|---|---|---|---|
| 1,5 / 2,6 / 258,1 | ikisi | 1/26, 1/27 | auth (üç kez) |
| 2,65 ve 258,2 | telefon | 2/3 | saat eşitleme |
| 2,7 ve sonra | ikisi | 2/1 | pil isteği ve yanıtı |
| 2,8 | ikisi | 2/2 | cihaz bilgisi isteği ve yanıtı |
| 2,8 ve 258,3 | ikisi | 4/0 | kadran listesi (yanıt 300 bayt, parçalı) |
| 2,9 ve 258,3 | ikisi | 17/0 | alarm listesi |
| 3,0 ve sonra | ikisi | 8/1 | bugünkü dosya listesi (her seferinde iki kimlik) |
| 6,28 | telefon | 23/58 | Notify'a özgü, eski oturum anahtarıyla (bkz. 3.4) |
| 81,6 ve sonra | telefon | 7/0 | bildirim (tek paket ve 500-800 baytlık parçalı) |
| 82,2 ve sonra | band | 7/16 | bildirim simgesi sorgusu (üç kez) |
| 82,2 ve sonra | ikisi | 7/15 | simge biçimi sorusu ve yanıtı |
| 82,8 ve sonra | ikisi | 22/0 | yükleme başlatma (üç kez) |
| 83-86 | telefon | 0x0055 | üç simge yüklemesi (bkz. 9) |
| 107,5 ve sonra | telefon | 8/3 | dosya isteği (bir istekte iki kimlik) |
| 108 ve sonra | ikisi | 8/2 | geçmiş dosya listesi (yanıt boş) |
| 108,7 ve sonra | band | 0x0053 | etkinlik dosyaları (bkz. 7.2) |
| 118,2 | band | 7/1 | bandda bildirim kapatıldı |
| 143,3 | band | 7/8 | "telefonda aç" basıldı |
| 209,3 | telefon | 2/18 | band'i bul, başlat |
| 311 ve 343 | ikisi | 0x0053, 8/5 | band kendiliğinden elle ölçüm dosyası gönderdi, Notify silme onayı verdi |
| 321,2 | ikisi | 2/29 | ekran öğeleri listesi (yanıt 721 bayt) |
| 573,5 | telefon | 7/1 | telefonda iki bildirim kapatıldı |

Günlük ayrıntı dosyası her istekte **birebir aynı** içerikle geldi. Notify bu dosyaya 8/5 göndermedi. Band da dosyayı silmedi.

## 6. Temel komutlar

Aşağıdaki tablolarda "S" System mesajının alanıdır. Hepsi Command alan 4 içine yazılır.

### 6.1 Pil: 2/1 (doğrulandı: istek ve yanıt yapısı)

- İstek: yalnızca tür ve alt tür.
- Yanıt: S2 (Power) > 1 (Battery) > alan 1 seviye (yüzde), alan 2 durum (şarj), alan 3 son şarj (1 durum, 2 zaman damgası saniye). Kayıttaki yanıt tam bu yapıdaydı.
- Band, durum değişince 2/79 ile kendisi de bildirir: S49 içinde 1 şarj (1 şarjda, 2 değil), 2 takılı mı (1 takılı, 2 değil), 3 uyku (1 uykuda, 2 değil). 2/78 ile istenebilir (güçlü kanıt).
- Standart pil servisi (0x180F) de var. Okunup okunamayacağı bilinmiyor (tahmin).

### 6.2 Cihaz bilgisi: 2/2 (doğrulandı)

Yanıt S3: 1 seri numarası (20 bayt metin), 2 yazılım sürümü (metin, örnek "2.3.14"), 3 boş, 4 model kodu (metin, Band 8 küresel için "M2239B1"). Seri numarası kişisel sayılır. Loga yazılmaz.

### 6.3 Saat eşitleme: 2/3 (doğrulandı)

S4 (Clock):

| Alan | İçerik |
|---|---|
| 1 | tarih: 1 yıl, 2 ay (1-12), 3 gün |
| 2 | saat: 1 saat, 2 dakika, 3 saniye, 4 milisaniye |
| 3 | saat dilimi: 1 UTC farkı (sint32, zigzag, 15 dakikalık birim), 2 yaz saati farkı (sint32, 15 dk birim), 3 IANA dilim adı |
| 4 | bool: 12 saat biçimi (24 saat için false) |

- Tarih ve saat **yerel** saattir. Kayıttaki yerel saat ile dosya zamanları (UTC) arasındaki fark, alan 3.1 × 15 dakikaya birebir eşitti. Alan 3.1 zigzag kodlu (doğrulandı).
- Notify alan 3.2'yi ve alan 4'ü yazmadı. Band kabul etti (doğrulandı).
- Notify saati her auth'tan hemen sonra, ilk mesaj olarak gönderdi. Biz de öyle yapacağız.

### 6.4 Bildirim: 7/0 (doğrulandı: alan 1-7, 11, 12, 13. Alan 8 güçlü kanıt)

Notification mesajı (Command alan 9) > alan 3 > alan 1 > asıl bildirim:

| Alan | Tür | İçerik |
|---|---|---|
| 1 | string | paket adı |
| 2 | string | uygulama adı |
| 3 | string | başlık |
| 4 | string | boş metin |
| 5 | string | gövde |
| 6 | string | zaman damgası, 15 karakter: yıl, ay, gün, `T`, saat, dakika, saniye (örnek `20250101T093000`). Uzunluk doğrulandı, biçim güçlü kanıt |
| 7 | uint32 | bildirim kimliği |
| 8 | bool | arama mı |
| 11 | bool | yanıt verilebilir mi |
| 12 | string | bildirim anahtarı |
| 13 | bool | "telefonda aç" düğmesi göster |

- Notify bunlara ek olarak alan 17 (tekrarlı: 36 karakterlik kimlik, etiket, tür, isteğe bağlı seçenek listesi; tahmin: eylem düğmeleri ve hazır yanıtlar) ve alan 18 (ek içerik) gönderdi. Biz bunları göndermeyeceğiz.
- "Arama mı" (alan 8) false iken Notify alanı yazmadı (doğrulandı).
- **Simge akışı (doğrulandı):** Band yeni bir paket görünce 7/16 gönderir (Notification alan 16 > 1 paket adı). Telefon 7/15 ile sorar (alan 14 > 1 paket adı). Band 7/15 ile yanıt verir: alan 15 > 1 durum (0), 2 piksel biçimi, 3 kenar boyu (piksel). Telefon simgeyi veri yüklemeyle (bkz. 9) gönderir. Kayıtta band aynı paket için art arda üç boy istedi: 28, 44 ve 80 piksel. Piksel biçimi 3 idi. Gadgetbridge'e göre 3 = ARGB 8888 küçük endian: her piksel 4 bayt, bellekte mavi, yeşil, kırmızı, alfa (güçlü kanıt). Yüklenen boyutlar kenar × kenar × 4 ile birebir tuttu (doğrulandı).
- Simge göndermezsek band varsayılan simge gösterir (tahmin). İlk sürümde simge yüklemesi yok. 7/16 gelirse yok sayılır.
- Uzun bildirimler parçalı gider (doğrulandı: 814 baytlık bildirim sorunsuz gitti). Türkçe karakterler UTF-8 (güçlü kanıt).
- Uzunluk sınırları: bkz. 12.1.

### 6.5 Bildirim kapatma: 7/1 (doğrulandı: iki yön de)

Notification alan 4 > tekrarlı alan 1: 1 kimlik, 2 paket adı, 4 anahtar. İki yönde de kullanılır. Telefon bir mesajda birden çok bildirimi kapatabilir (kayıtta iki tane). Band bir bildirimi kapatınca telefona 7/1 gelir. Telefonda kapanınca banda 7/1 gönderilir.

### 6.6 Gelen arama, cevapsız arama, reddetme (güçlü kanıt, cevapsız arama tahmin)

- **Gelen arama:** 7/0, paket "phone", uygulama adı "phone", başlık arayan adı ya da "?", gövde numara, kimlik 0, arama mı = true.
- **Arama bitti ya da açıldı:** 7/1, kimlik 0, paket "phone".
- **Bandda reddet:** band 7/2 gönderir. **Sessize al:** band 7/5 gönderir. Telefon aramayı kendisi reddeder.
- **Cevapsız arama:** Gadgetbridge ayrı bir komut kullanmıyor. Normal bildirim olarak (arama mı = false) gönderilebilir (tahmin). Kayıtta arama yok (açık soru 5).
- **Hızlı yanıt:** band 7/13 ile metin ve numara gönderir, telefon 7/14 ile sonucu bildirir. SMS izni gerektirir. Bu aşamada yok.

### 6.7 "Telefonda aç": 7/8 (doğrulandı: banddan gelişi ve alanlar)

Notification alan 2: 1 kimlik, 2 paket, 4 anahtar. Bu aşamada yok sayılabilir.

### 6.8 Band'i bul: 2/18 (başlatma doğrulandı. Gerisi güçlü kanıt)

- Başlat: S5 = 0 (kayıtta tam bu gitti). Durdur: S5 = 1. Band başlatmaya ayrı yanıt vermedi.
- Bandda "buldum" basılınca band 2/18 gönderir (tahmin: S5 = 1).
- **Telefonu bul:** band 2/17 gönderir. S5 = 0 başlat, 1 durdur. Mevcut "telefonu bul" özelliğimize bağlanır.

### 6.9 Titreşim (güçlü kanıt)

- 2/59: anlık titreşim deseni dene. S41 > tekrarlı alan 1: 1 titreşim (0/1), 2 süre (ms). Notify bunu kullanıyor.
- 2/46: desen listesi al. 2/47: bildirim türüne hazır desen ata (1 arama, 3 alarm, 4 bildirim, 6 SMS, diğerleri belirsiz). 2/58: özel desen oluştur.
- Saat ayarını değiştirdiği için 2/47 ve 2/58 Gelişmiş bölümünde elle denenir.

## 7. Sağlık

Health mesajı Command alan 10 içine yazılır. "H" Health alanıdır.

### 7.1 Canlı nabız ve adım: 8/45, 8/46, 8/47 (güçlü kanıt, Notify de kullanıyor)

- Başlat 8/45, durdur 8/46. İçerik yok.
- Band açıkken düzenli olarak 8/47 gönderir. H39: 1 adım, 2 kalori, 4 nabız, 6 ayakta geçen saat. 3 ve 5 bilinmiyor.
- Adım **günlük birikmiş** değerdir. Gadgetbridge iki olay arasındaki farkı alıyor (güçlü kanıt). Nabız 10 ya da altındaysa geçersiz sayılır.
- Kayıtta canlı ölçüm yok. Olay sıklığı bilinmiyor (açık soru 6).
- Nabız ölçüm ayarı: 8/10 al, 8/11 yaz. H8: 1 kapalı mı, 2 aralık (0 akıllı, 1, 10, 30 dakika), 3-4 yüksek nabız uyarısı.

### 7.2 Etkinlik dosyası çekme (doğrulandı)

1. Telefon **8/1** (bugün) gönderir. H5 > alan 1: Notify 1 gönderdi. Gadgetbridge 0 gönderiyor. İkisi de çalışıyor olmalı (tahmin). Biz kayıttaki gibi 1 göndereceğiz.
2. Band 8/1 ile yanıt verir. H2 (bytes): 7'şer baytlık **dosya kimlikleri** art arda. Kayıtta her seferinde iki kimlik geldi: bugünün ayrıntı dosyası ve bugünün özet dosyası.
3. Telefon **8/2** (geçmiş) gönderir. Band 8/2 ile aynı biçimde yanıt verir. Kayıtta liste boştu (H2 uzunluğu 0).
4. Telefon **8/3** gönderir. H2 = istenen dosya kimlikleri, art arda.
5. Band dosyayı **0x0053** üstünden gönderir.
6. Telefon dosyayı kaydettikten sonra **8/5** ile onay verir. H3 = tek dosya kimliği. **Bu onay, dosyayı bandan siler.**

Kayıttan önemli gözlemler (doğrulandı):

- Notify bir 8/3'te iki kimlik istedi. Band ayrıntı dosyasının yalnızca **1. bölümünü** (4 bölümden), sonra özet dosyasını gönderdi. Ayrıntı dosyasının 2-4. bölümleri hiç gelmedi. Bu altı istekte de aynı oldu. Gadgetbridge her 8/3'te **tek kimlik** istiyor ve bölümler arasında 5 saniyelik zaman aşımı kullanıyor (güçlü kanıt). Biz de tek kimlik isteyeceğiz. Bölümlerin gerçekten gelip gelmediği ilk cihaz denemesinde görülecek (açık soru 7).
- Band, bandda elle yapılan bir ölçüm bitince **istenmeden** bir dosya gönderdi (311 s ve 343 s, elle ölçüm dosyası, bkz. 7.9). Notify bu dosyaya hemen 8/5 gönderdi. Yani alıcı, 8/3 beklemeden gelen dosyaları da işlemeli.

Güvenlik kuralı: 8/5 yalnızca dosya CRC'si tuttuktan ve veritabanına yazıldıktan sonra gönderilir. İlk sürümde 8/5 hiç gönderilmez. Band dosyayı saklamaya devam eder ve sonraki eşitlemede yine verir. Bizim tarafta yinelenen kayıt, dosya kimliğiyle ayıklanır.

### 7.3 Dosya kimliği (7 bayt, doğrulandı)

| Bayt | İçerik |
|---|---|
| 0-3 | başlangıç zamanı, Unix saniye, LE |
| 4 | saat dilimi, 15 dakikalık birim |
| 5 | sürüm |
| 6 | bayraklar: bit 7 tür (0 günlük etkinlik, 1 spor), bit 2-6 alt tür, bit 0-1 ayrıntı türü (0 ayrıntı, 1 özet) |

Günlük etkinlik alt türleri: 0 günlük, 3 uyku evreleri, 6 elle ölçümler, 8 uyku.

Kayıtta görülen kimlikler (doğrulandı, değerler yazılmadı):

| Dosya | Sürüm | Bayrak baytı | Başlangıç zamanı |
|---|---|---|---|
| günlük ayrıntı (alt tür 0, ayrıntı) | 3 | 0x00 | yerel gece yarısının UTC karşılığı |
| günlük özet (alt tür 0, özet) | 5 | 0x01 | isteğin yapıldığı an, her istekte değişiyor |
| elle ölçüm (alt tür 6, ayrıntı) | 1 | 0x18 | ölçümün bittiği an |

- Zaman **UTC Unix saniyesidir**. Saat dilimi ayrı baytta, 15 dakikalık birimde, işaretli. Ayrıntı dosyasının başlangıcı, bu farkla tam yerel gece yarısına denk geldi (doğrulandı).
- Özet dosyasının kimliği her istekte değiştiği için "aynı dosya mı" kararı özet için kimlikle verilmez. Günün tarihiyle verilir (tahmin).

### 7.4 Dosya gövdesi (doğrulandı)

- Dosya en çok 4096 baytlık **bölümlerle** gelir. Her bölüm ayrı şifrelenir ve gerekirse 2.3'teki gibi parçalanır.
- Her bölümün çözülmüş hâlinin başında 4 bayt var: toplam bölüm (2 bayt LE) ve bu bölümün sırası (2 bayt LE, 1'den başlar). Sonrası dosyanın o bölümü (en çok 4092 bayt).
- Bütün bölümler birleşince: 7 bayt dosya kimliği, 1 bayt dolgu (0), dosyaya özgü gövde, sonda 4 bayt CRC32 (LE, standart CRC32, son 4 bayt hariç bütün veri üstünden). CRC, kayıttaki bütün tam dosyalarda tuttu.
- Bölüm başlığındaki 4 değeri, dakikalık kayıt boyuyla uyumlu: kayıt anına kadar geçen dakika sayısı × 12 bayt, 4 bölüme sığıyor (güçlü kanıt).

### 7.5 Günlük ayrıntı dosyası: adım, nabız (yapı doğrulandı, değerler güçlü kanıt)

- Dolgudan sonra sürüme göre 4, 5 ya da 6 baytlık bir başlık gelir (sürüm 1-2: 4, sürüm 3: 5, sürüm 4: 6). Band 8 sürüm 3 kullanıyor, başlık 5 bayt (doğrulandı).
- Başlığın her yarım baytı (nibble) bir alan grubunu tanımlar. Yarım baytın en üst biti 1 ise grup kayıtta vardır. Alt üç bit, grubun içindeki alt değerlerin geçerli olup olmadığını söyler.
- Sonra dakikada bir kayıt gelir. İlk kayıt dosya zamanındadır, her kayıt bir dakika sonrasıdır.
- Kayıttaki başlıkta bütün gruplar vardı. Bu durumda kayıt boyu **12 bayt** (doğrulandı: 1. bölüm tam 339 kayıt ve bir artık parçaya bölündü).
- Adım, kaydın ilk 16 bitlik grubunun alt 14 bitidir ve **o dakikanın adımıdır** (birikmiş değil, güçlü kanıt). Kayıttaki bölüm boş dakikalardan oluştuğu için adım ve nabız değerleri bu kayıtla doğrulanamadı.
- Kayıttaki gruplar sırayla (bit uzunluğu): 16 bit (1 bitlik "ek alan var" bayrağı ve 14 bit adım), 8 bit (kalori dahil), 8 bit, 16 bit (mesafe olabilir), 8 bit **nabız**, 8 bit enerji, 16 bit. Sürüm 3 ve sonrası: 8 bit SpO2, 8 bit stres (255 = yok). "Ek alan" bayrağı 1 ise bir 8 bitlik grup daha. Sürüm 4 ve sonrası: iki 16 bitlik grup daha.
- Bir grup başlıkta yoksa kayıttan bayt okunmaz.
- Bu yapı Gadgetbridge'in davranışından çıkarıldı ve kayıttaki dosyanın boyutları ve hizalamasıyla doğrulandı. Değerleri (adım, nabız) ilk cihaz denemesinde dolu bir günle karşılaştıracağız.
- Dosyada saatlik toplam yok. **Saatlik adım, 60 dakikalık kayıtların toplamıyla çıkarılır** (güçlü kanıt).

### 7.6 Uyku (sürüm 2 yapısı doğrulandı 20.11.2026; aşağıdaki eski notlar bölüm 17'ye göre okunmalı)

- **Uyku dosyası (alt tür 8):** dolgudan sonra 1 bayt başlık bayrakları, 1 bayt "uyanık mı", 4 bayt yatma zamanı, 4 bayt kalkma zamanı. Bazı sürümlerde 1 bayt uyku kalitesi. Sonra bayraklara bağlı ek ölçüm blokları (nabız, SpO2, horlama; her biri: birim, sayı, isteğe bağlı ilk kayıt zamanı, kayıtlar). En sonda evre paketleri.
- Evre paketleri 4 baytlık sabit bir işaretle başlar. Her paketin bir türü var (17 = evreler, 16 = özet, 2 = uykuya dalma, 3 = uyanma, diğerleri bilinmiyor). Evre kayıtlarında evre kodu üst 4 bittedir.
- **Evre kodları:** 0 uyanık, 1 hafif, 2 derin, 3 REM, 4 uyku dışı (güçlü kanıt).
- **REM var mı:** Evet (güçlü kanıt). Mi Fitness günlüklerinde Band 8 için sıfırdan büyük REM süreleri var. Mi Fitness ürün yapılandırmasında bazı modellerde "REM yok" bayrağı var, Band 8'de yok.
- **Uyku evreleri dosyası (alt tür 3):** ayrı bir dosya, uyku süreleri özeti (REM, derin, hafif, uyanık süreleri). Gadgetbridge belgesi bazı cihazlarda evrelerin çalışmadığını söylüyor.
- Kayıtta uyku dosyası yoktu. Yapı ilk sabah eşitlemesinde doğrulanacak (açık soru 8).
- Birleştirme kuralı (plan bölüm 8) "derin/hafif ayrımı olan ve boşluksuz oturum" seçer. Band 8 uykusu ayrıntılı olduğu için genelde Band 8 seçilir.

### 7.7 Günlük özet dosyası (alt tür 0, özet; uzunluk ve CRC doğrulandı, alanlar güçlü kanıt)

- Sürüm 5. Dolgudan sonra 4 bayt başlık, sonra 53 bayt gövde, sonra CRC.
- Gövde sırası (Gadgetbridge'in bildiği ilk 51 bayt): 4 bayt toplam adım, 3 bayt bilinmiyor, dinlenme nabzı, en yüksek nabız ve zamanı, en düşük nabız ve zamanı, ortalama nabız, stres ortalama/en yüksek/en düşük, 3 bayt ayakta geçen süre, 2 bayt kalori, 3 bayt bilinmiyor, SpO2 en yüksek/en düşük (zamanlarıyla) ve ortalama, antrenman yükü, canlılık puanı. Son 2 bayt bilinmiyor (Band 8 yazılım 2.3.14 için yeni).
- Zaman alanları UTC Unix saniyesidir (güçlü kanıt).
- Günlük toplam adım buradan alınır. Plan bölüm 8'deki "günlük toplam ayrı tutulur" kuralı bu değerle uygulanır.

### 7.8 Kullanıcı bilgisi: 8/0 (güçlü kanıt)

H1: 1 boy (cm), 2 kilo (float), 3 doğum tarihi (YYYYAAGG sayı), 4 cinsiyet, 5 en yüksek nabız, 6 kalori hedefi, 7 adım hedefi. Gadgetbridge bağlanınca bunu gönderiyor. Notify kayıtta göndermedi (doğrulandı). Biz bu aşamada **göndermeyiz**. Bandın mevcut ayarını değiştirir.

### 7.9 Elle ölçüm dosyası (alt tür 6, sürüm 1; yapı doğrulandı)

- Bandda elle nabız ölçümü bitince band dosyayı kendiliğinden 0x0053'ten gönderir (bkz. 7.2).
- Gövde: 1 bayt başlık (kayıtta hep aynı değer, anlamı bilinmiyor), sonra 6 baytlık kayıtlar: 4 bayt UTC Unix saniyesi, 1 bayt tür (1 = nabız), 1 bayt değer. Kayıtlar **saniyede bir**.
- Gadgetbridge yalnızca sürüm 2'yi tanıyor (orada başlık yok, tür 1 nabız, 2 SpO2, 3 stres, sıcaklık 4 baytlık). Band 8 sürüm 1 gönderiyor. Ayrıştırıcımız iki sürümü de tanımalı.
- Bu veri ilk sürümde saklanabilir ama zorunlu değil.

### 7.10 Diğer sağlık ayarları (güçlü kanıt, Gadgetbridge)

| Alt tür | İş |
|---|---|
| 8/8, 8/9 | SpO2 ölçüm ayarı al, yaz |
| 8/10, 8/11 | nabız ölçüm ayarı al, yaz |
| 8/12, 8/13 | **hareketsizlik uyarısı** al, yaz: açık mı, başlangıç ve bitiş saati, ayrıca öğle arası gibi bir sessiz aralık (açık mı, başlangıç, bitiş) |
| 8/14, 8/15 | stres ölçüm ayarı al, yaz |
| 8/21, 8/22 | hedefe ulaşınca bildirim ayarı |
| 8/42, 8/43 | hedefler |

Alan numaraları 6. adımda bir kayıtla doğrulanacak. Mi Fitness ürün yapılandırmasında Band 8 için "ayakta durma hatırlatıcısı" özelliği açık (güçlü kanıt).

## 8. Ayarlar

Schedule mesajı Command alan 19 içine yazılır. "Ç" Schedule alanıdır.

### 8.1 Alarm (17/0 doğrulandı. Diğerleri güçlü kanıt)

| Alt tür | İş | İçerik |
|---|---|---|
| 17/0 | listeyi al | yanıt Ç1: tekrarlı alan 1 alarm (her biri: 1 kimlik, 2 ayrıntı), alan 2 **en çok alarm sayısı: 10** (doğrulandı), alan 3 ve 4 bilinmiyor |
| 17/1 | oluştur | Ç2: alarm ayrıntısı. Yanıt Ç4: yeni kimlik |
| 17/2 | düzenle | Ç3: 1 kimlik, 2 ayrıntı. (Önceki taslakta 17/3 yazıyordu. Gadgetbridge 2 kullanıyor.) |
| 17/4 | sil | Ç5: tekrarlı kimlik |

Alarm ayrıntısı: 2 saat (1 saat, 2 dakika), 3 tekrar (0 bir kez, 1 her gün, 5 haftalık, 7 aylık, 8 yıllık), 4 gün bayrakları (yalnızca haftalık: bit 0 pazartesi ... bit 6 pazar), 5 açık mı, 7 akıllı alarm (1 akıllı, 2 normal). Kayıttaki iki alarmda bu alanlar vardı. Haftalık olmayanda alan 4 yazılmamıştı (doğrulandı).

LS05'ten farkı: Alarmda not ve titreşim süresi yok. Kimlikleri band verir.

### 8.2 Hatırlatıcı, dünya saati, takvim (güçlü kanıt)

- **Hatırlatıcılar (bandın kendi listesi):** 17/14 al, 17/15 oluştur, 17/17 düzenle, 17/18 sil. En çok sayıyı band listeyle birlikte bildirir. Metin kısa olmalı (Gadgetbridge 20 karakter varsayıyor, kesin değil). Mi Fitness yapılandırmasında Band 8 için "etkinlik hatırlatıcısı" özelliği açık.
- **Dünya saati:** 17/10 al, 17/11 yaz.
- **Takvim:** 12/1 ile etkinlik listesi gönderilir. Bandda takvim ekranı var. Gadgetbridge en çok 50 etkinlik varsayıyor (kesin değil).
- Bunlar bu aşamada yok. 6. adımda değerlendirilir.

### 8.3 Rahatsız etme (güçlü kanıt)

- **Anlık aç/kapat: 2/23.** S11 > alan 1: 0 açık, 2 kapalı. Notify bunu kullanıyor.
- **Zamanlı uyku modu: 17/8 al, 17/9 yaz.** Ç9: 1 açık mı, 2 zaman aralığı (1 başlangıç, 2 bitiş, saat ve dakika).
- **Telefonla eşitleme:** 2/14 ve 2/15, S35 > 2 > 1 açık mı. Telefon sessizdeyken bildirim gelmesin ayarıdır.
- Zamanlı rahatsız etmenin (gece) ayrı bir komutu olabilir. Gadgetbridge'de görülmedi (açık soru 9). Mi Fitness yapılandırmasında "sessiz mod" ve "yatma saati" özellikleri ayrı ayrı açık. Gadgetbridge'de ayrıca 2/43 sessiz mod al, 2/44 telefondan yaz, 2/45 banddan bildir var.

### 8.4 Diğer band ayarları (güçlü kanıt)

| Konu | Komut | Not |
|---|---|---|
| Ekran öğeleri (menü sırası) | 2/29 al, 2/30 yaz | 2/29 yanıtı kayıtta çözüldü (System alan 10) |
| Ekran kilidi (şifre) | 2/9 al, 2/21 yaz | Mi Fitness'ta "bilekten çıkınca kilit" özelliği açık |
| Çeşitli ayar | 2/14 al, 2/15 yaz, 2/42 banddan | takma biçimi ve telefonla sessiz eşitleme |
| Bildirimde ekranı aç | 7/6 al, 7/7 yaz | |
| Kamera kumandası | 2/7, 2/8 | |
| Dil | 2/6 | |

- **Bileği kaldırınca ekranı aç:** Ne Gadgetbridge'de ne Mi Fitness özellik listesinde ayrı bir komut görüldü. Ayar bandın kendi menüsünde (tahmin: protokolle değiştirilemiyor).
- **Duş (su) modu:** Band 8 özellik listesinde yok. Protokolde de görülmedi (tahmin: yok).

### 8.5 Hava durumu (güçlü kanıt)

Weather mesajı Command alan 12 içine yazılır.

- 10/7: konum ekle. Alan 5: 1 kod (metin), 2 ad.
- 10/0: anlık hava. Alan 1: 1 meta (1 yayın zamanı ISO metin, 2 şehir adı, 3 konum adı, 4 konum kodu, 5 şimdiki konum mu), 2 hava kodu (Xiaomi kodu), 3 sıcaklık, 4 nem, 5 rüzgâr, 6 UV, 7 hava kalitesi. Her ölçüm: 1 birim metni ("℃", "%"), 2 değer (sint32). 9 basınç (float).
- 10/1: günlük tahmin. Alan 2: 1 meta, 2 > tekrarlı gün: 1 hava kalitesi, 2 hava kodu aralığı (gündüz, gece), 3 sıcaklık aralığı (en düşük, en yüksek), 4 sıcaklık simgesi, 5 gün doğumu ve batımı (metin), 6 rüzgâr.
- 10/2: saatlik tahmin. Aynı yapı.
- 10/10: tercihler. Alan 6: 1 sıcaklık birimi (1 Celsius).
- Open-Meteo WMO kodlarından Xiaomi hava kodlarına bir eşleme tablosu gerekir. Xiaomi kod listesi 6. adımda çıkarılacak (tahmin: 0 güneşli, 1 parçalı bulutlu, 2 kapalı, sonra yağmur, kar ve fırtına türleri).
- Konum adı kişisel veridir. Loga yazılmaz. Gadgetbridge konum kodu olarak adın özetinden türetilmiş bir sayı kullanıyor. Biz sabit bir kod kullanacağız.

## 9. Kadran ve veri yükleme (doğrulandı. Kadran ve yazılım uygulanmayacak)

- Yükleme isteği: 22/0. Alan 24 > 1: 1 tür (16 kadran, 32 yazılım, 50 bildirim simgesi), 2 MD5 (16 bayt), 3 boyut. Band 22/0 ile onaylar: alan 24 > 2: 1 MD5 (aynısı), 2 devam konumu (0), 4 (0, anlamı bilinmiyor). Band 8 parça boyu **göndermedi** (doğrulandı).
- Veri 0x0055'e yazılır. Yüklenen blok: 1 bayt 0, 1 bayt tür, 16 bayt MD5 (yalnızca verinin), 4 bayt uzunluk (LE), veri, sonda 4 bayt CRC32 (son 4 bayt hariç bütün blok üstünden). Bu blok 4092 baytlık bölümlere bölünür. Her bölümün başında 2 bayt toplam bölüm ve 2 bayt bölüm sırası. Her bölüm ayrı şifrelenir (sayaç 0) ve 2.3'teki gibi parçalı gönderilir (doğrulandı: MD5 ve CRC kayıttaki üç yüklemede de tuttu).
- **Kayıttaki 0x0055 yüklemesi neydi:** Notify'ın test bildirimi için üç **bildirim simgesi** (tür 50). Band aynı paket için 28, 44 ve 80 piksel kare simge istedi. Veri boyları 3136, 7744 ve 25600 bayt (kenar × kenar × 4). Sırasıyla 1, 2 ve 7 bölüm. Veri ham ARGB 8888 bitmap, sıkıştırma yok (doğrulandı).
- Yükleme hızı: 25 KB'lık simge yaklaşık 0,8 saniyede gitti (doğrulandı). Yanıtsız yazmalar araya bekleme koymadan arka arkaya yazıldı.
- Kadran listesi 4/0 (yanıt Command alan 6, doğrulandı), seçme 4/1, silme 4/2, kurma 4/4.
- **Yazılım güncellemesi:** tür 32 yükleme ve 2/5 kurma komutu var (güçlü kanıt). Mi Fitness'ta da bu model için güncelleme türü tanımlı. **Uygulanmayacak.** Yanlış dosya bandı kullanılamaz hâle getirebilir.

## 10. Yetenek karşılaştırması (Capabilities modeli için)

| Özellik | LS05 | Band 8 | Not |
|---|---|---|---|
| Bildirim | var | var | Band 8'de uygulama simgesi yüklenebilir |
| Gelen arama, bandda reddet | var | var | Band 8'de ayrıca sessize al |
| Cevapsız arama | var | tahmin | normal bildirim olarak |
| Arama SMS yanıtı | yok | var | izin ister, ertelenir |
| Saat eşitleme | var | var | Band 8 saat dilimi adını da alır |
| Alarm | var (not ve titreşim süresi) | var (en çok 10, doğrulandı; akıllı alarm) | not yok |
| Rahatsız etme | var | var | anlık ve zamanlı ayrı |
| Hava durumu | var | var | Band 8'de günlük ve saatlik tahmin |
| Adım, nabız geçmişi | var | var | Band 8 dakikalık, saatlik toplam telefonda hesaplanır |
| Günlük özet | yok | var | toplam adım, dinlenme nabzı, kalori |
| Uyku | var | var, evreli | REM dahil (güçlü kanıt) |
| SpO2, stres | yok | var | ilk sürümde saklanabilir, grafik sonra |
| Elle ölçüm geçmişi | yok | var | band kendiliğinden gönderir |
| Canlı nabız | ? | var | 8/45 |
| Ham ivme verisi | yok | yok (bilinen komut yok) | Sleep as Android için önemli, bkz. 14 |
| Pil | var | var | Band 8 şarj ve takılı durumu da bildirir |
| Saati bul | var | var | |
| Telefonu bul | var | var | |
| Müzik kumandası | var | var | Band 8 şarkı adını da gösterir |
| Bildirim simgesi | yok | var | ilk sürümde yok |
| Kadran yükleme | var (kendi üreticimiz) | var ama farklı biçim | Band 8'de gizli (aşama 4) |
| Yazılım güncelleme | ? | var | **uygulanmayacak** |
| Takvim | yok | var | 12/1 |
| Bandın kendi hatırlatıcıları | yok | var | 17/14-18 |
| Dünya saati | yok | var | 17/10-11 |
| Hareketsizlik uyarısı | ? | var | 8/12-13, banddaki ayar |
| Ekran kilidi (şifre) | yok | var | 2/9, 2/21 |
| Bileği kaldırınca ekran, duş modu | ? | protokolde görülmedi | |
| Titreşim deseni | yok | var | |
| Kullanıcı profili, hedefler | ? | var | yazmak ayarları değiştirir |
| Kayıp saat uyarısı | uygulama tarafı | uygulama tarafı | ortak servis |

Önerilen bayraklar: `NOTIFY`, `NOTIFY_ICON`, `CALL`, `CALL_REJECT`, `CALL_MUTE`, `TIME_SYNC`, `ALARM`, `ALARM_NOTE`, `ALARM_VIBRATION_LENGTH`, `SMART_ALARM`, `DND_NOW`, `DND_SCHEDULE`, `WEATHER`, `WEATHER_FORECAST`, `HISTORY_STEPS`, `HISTORY_HR`, `HISTORY_SLEEP`, `SLEEP_STAGES`, `DAILY_SUMMARY`, `SPO2`, `STRESS`, `REALTIME_HR`, `RAW_ACCEL`, `BATTERY`, `FIND_WATCH`, `FIND_PHONE`, `MUSIC`, `WATCHFACE_UPLOAD`, `VIBRATION_TEST`, `IDLE_ALERT`, `WATCH_REMINDERS`, `CALENDAR`, `WORLD_CLOCK`. Alarm sayısı bayrak değil, sayı olarak tutulur (`maxAlarms`, Band 8 için banddan okunur). Kesin listeye çekirdek ajan karar verir.

## 11. Şifre çözme aracı (repo dışında)

Araç repo dışında duruyor: `b8tools/b8decrypt.py`. Anahtarı yalnızca `B8_AUTH_KEY` ortam değişkeninden okur, dosyaya yazmaz. Varsayılan çıktı yalnızca yapıyı gösterir (tür, alt tür, alan numaraları, uzunluklar). Değerleri göstermek için ayrı bir seçenek var. Araç, band HMAC'ini doğrulayarak anahtarın doğru olduğunu söyler ve 4.1, 4.2 ve 2.3'teki varsayımları tek tek dener. İstenirse çözülmüş etkinlik dosyalarını ve yüklemeleri repo dışındaki bir klasöre yazar.

Kayıt 17 Kasım'da kullanıcının anahtarıyla bu araçla çözüldü (bkz. 4.5). Yeni kayıtlar da aynı yolla çözülecek.

## 12. Açık sorular ve gereken kayıtlar

### 12.1 Yanıtlanan sorular

| Soru | Yanıt | Etiket |
|---|---|---|
| 3.2 ve 4.x tam doğru mu | Evet. Bütün çerçeveler çözüldü (bkz. 4.5) | doğrulandı |
| 6,28 s'deki sayaç 1 mesajı ne | Notify'ın eski oturum anahtarıyla gönderdiği tür 23 mesajı (bkz. 3.4) | doğrulandı |
| Telefondan giden parçalı komutlar (139 s, 504-574 s) ne | Uzun bildirimler (7/0), 559-814 bayt | doğrulandı |
| 0x0055 yüklemesi ne | Üç boy bildirim simgesi (bkz. 9) | doğrulandı |
| Etkinlik dosyası bölüm başlığı | 2 bayt toplam, 2 bayt sıra, bölüm en çok 4096 bayt (bkz. 7.4) | doğrulandı |
| Adım çözünürlüğü | Geçmişte **dakikalık** kayıt, her kayıt o dakikanın adımı. Günlük toplam ayrıca özet dosyasında. Canlı akışta (8/47) günlük birikmiş değer | yapı doğrulandı, değer anlamı güçlü kanıt |
| Saatlik adım çıkarılabilir mi | Evet, 60 dakikalık kayıt toplanır | güçlü kanıt |
| Zaman damgaları UTC mi yerel mi | Dosya kimlikleri ve dosya içi zamanlar UTC Unix saniyesi, saat dilimi ayrı (15 dk birim). Saat eşitlemede (2/3) yerel saat ve UTC farkı gönderilir. Bildirim zaman metni yerel saat (güçlü kanıt) | doğrulandı |
| Alarm sayısı | En çok 10. Band 17/0 yanıtında bildiriyor | doğrulandı |
| Uykuda REM var mı | Var. Evre kodu 3 | güçlü kanıt |
| En uzun bildirim metni | Kesin sınır bilinmiyor. 814 baytlık bildirim (eylem alanlarıyla) sorunsuz gitti. Kayıttaki en uzun gövde 42 bayttı. Gadgetbridge metni kesmiyor | doğrulandı (814 bayt), sınır tahmin |

Bildirim uzunluğu için ilk sürüm kuralı (tahmin, cihazda denenecek): başlık en çok 64 bayt, gövde en çok 512 bayt UTF-8. Kesme, karakter sınırında yapılır ve sona "…" eklenir. Uygulama adı ve paket adı kesilmez.

### 12.2 Hâlâ açık olanlar

| # | Soru | Nasıl çözülür |
|---|---|---|
| 2 | Bölge ve telefon özellik maskesi önemli mi | ilk bağlantıda Gadgetbridge değeriyle dene, olmazsa Notify'ın değeri |
| 5 | Cevapsız arama nasıl gösteriliyor | yeni kayıt: Notify açıkken telefona arama gelsin, biri reddedilsin, biri cevapsız kalsın |
| 6 | Canlı nabız mesajları ve sıklığı | yeni kayıt: Notify'da canlı nabzı 1 dakika aç |
| 7 | Tek kimlikli 8/3'te ayrıntı dosyasının 2-4. bölümleri geliyor mu | ilk cihaz denemesi, yalnızca okuma, 8/5 yok |
| 8 | Uyku ve uyku evreleri dosyası | yeni kayıt: sabah, gece verisi eşitlenirken |
| 9 | Zamanlı rahatsız etme komutu | yeni kayıt: Notify'da rahatsız etmeyi aç, kapat, zamanla |
| 10 | Alarm oluşturma, düzenleme ve silme yanıtları | yeni kayıt: Notify'da bir alarm kur, düzenle, sil |
| 11 | Hava durumu tam mesajı ve kod listesi | yeni kayıt: Notify'da hava güncellemesini elle tetikle |
| 12 | "Buldum" yanıtı | yeni kayıt: Notify'dan bandı bul, bandda durdur |
| 13 | Mi Fitness ya da Notify açıkken bizim bağlantımız ne olur | telefonda dene, ilk bağlantıda öbür uygulamalar kapalı olsun |
| 14 | 0xFDAB servisi ne işe yarıyor | şimdilik gerekmiyor |
| 15 | Standart pil servisi auth olmadan okunur mu | Gelişmiş düğmesiyle dene (salt okuma, güvenli) |
| 16 | Dolu bir günde adım ve nabız değerleri doğru okunuyor mu | ilk cihaz denemesinde, Mi Fitness'taki günlük toplamla karşılaştır |
| 17 | Hareketsizlik uyarısı alan numaraları | yeni kayıt: Notify'da ayarı değiştir |
| 18 | Canlı nabız açıkken pil tüketimi | bir gece dene, akşam ve sabah pil yüzdesini not et (bkz. 14) |

Tek yeni kayıtta 5, 6, 8, 9, 10, 11, 12 ve 17 birlikte toplanabilir. Her adım arasında 10 saniye beklenmeli ve yapılan işin saati not edilmeli. Bildirim içerikleri için test metni kullanılmalı.

## 13. 3. adım için Kotlin modül önerisi

Yeni Gradle modülü yok. Paket adı `band8`.

### `protocol/` (saf Kotlin, birim testli)

| Dosya | İçerik |
|---|---|
| `band8/Pb.kt` | küçük protobuf yazıcı ve okuyucu (varint, zigzag, uzunluklu alan, float) |
| `band8/B8Messages.kt` | gereken mesajların kurucuları ve ayrıştırıcıları (tür ve alt tür sabitleri dahil) |
| `band8/B8Crypto.kt` | HKDF-SHA256, AES-CCM (AES/ECB üstünde), oturum anahtarları ve nonce'lar |
| `band8/B8Auth.kt` | auth durum makinesi: gelen çerçeveyi alır, gönderilecek çerçeveyi ve sonucu verir |
| `band8/B8Frame.kt` | çerçeve kodlama ve çözme, parçalayıcı, birleştirici, onay mantığı |
| `band8/B8Channel.kt` | karakteristik başına kuyruk ve sayaç. Android'e bağlı değil, "şu karakteristiğe şunu yaz" eylemleri üretir. Yeniden auth olunca sayaç 1'e döner, kuyruktaki mesajlar yeni oturumla yeniden şifrelenir |
| `band8/B8Activity.kt` | dosya kimliği, bölüm birleştirici (4 baytlık bölüm başlığı), CRC32 |
| `band8/B8ActivityParsers.kt` | günlük ayrıntı (sürüm 1-4), günlük özet (sürüm 5), elle ölçüm (sürüm 1 ve 2), uyku ayrıştırıcıları |
| `band8/B8Fetch.kt` | dosya çekme sırası: 8/1, 8/2, her kimlik için ayrı 8/3, 5 sn zaman aşımı, istenmeden gelen dosyaları kabul, 8/5 yalnızca açık izinle |
| `band8/B8Weather.kt` | WMO kodundan Xiaomi koduna eşleme |

### `app/`

| Dosya | İçerik |
|---|---|
| `band8/Band8Ble.kt` | GATT bağlantısı, UUID ile keşif, MTU isteği, dört CCCD, yanıtsız yazma kuyruğu, bağ kontrolü |
| `band8/Band8Driver.kt` | çekirdek ajanın `WatchDevice` arayüzünü uygular, protokol katmanını çağırır |
| `band8/Band8Key.kt` | auth key'in Android Keystore ile şifreli saklanması ve girişin doğrulanması (32 onaltılık karakter) |

### Test stratejisi

- **Kripto:** RFC 5869 HKDF vektörleri, RFC 3610 ve NIST SP 800-38C CCM vektörleri (4 baytlık etiket dahil).
- **Altın vektörler:** Rastgele seçilmiş bir **test anahtarı** ve test nonce'larıyla, repo dışındaki bağımsız Python aracıyla üretilmiş girdi ve çıktılar. Kotlin aynı baytları üretmeli. Gerçek anahtar ve gerçek nonce kullanılmaz.
- **Çözülmüş kayıttan fikstürler:** Kayıt artık çözülmüş durumda. Fikstürler şu kuralla hazırlanır:
  1. Kayıttaki çözülmüş düz mesajın **yapısı** alınır (tür, alt tür, alan numaraları, alan türleri, sırası).
  2. Kişisel olabilecek her değer sentetik değerle değiştirilir: seri numarası, telefon modeli, bölge, nonce, MD5, dosya kimliğindeki zaman, alarm saatleri, pil yüzdesi, saat dilimi adı, bütün metinler.
  3. Mesaj test anahtarıyla yeniden şifrelenir ve çerçevelenir. Repoya yalnızca bu sentetik baytlar girer.
  4. Her fikstürün yanında "kayıttaki X mesajının yapısı" diye kaynak yazılır. Gerçek değer yazılmaz.
- **Uygun fikstür adayları:** onay, parça başlatma, "hazırım", "hepsi alındı"; auth 26 ve 27 (test nonce'larıyla); pil yanıtı; cihaz bilgisi yanıtı (sahte seri numarası); saat eşitleme; alarm listesi yanıtı (sahte saatler, en çok sayı 10); 8/1 yanıtı (iki sentetik kimlik); 7/16 ve 7/15 simge akışı (sahte paket adı); 22/0 istek ve yanıtı (sahte MD5); 7/1 kapatma (sahte anahtar).
- **Asla fikstür olmayacaklar:** bildirim metinleri, gerçek paket adları ve bildirim anahtarları, MAC, seri numarası, auth key, gerçek nonce'lar, gerçek dosya kimlikleri, sağlık değerleri (adım, nabız, SpO2, uyku), konum ve saat dilimi adı.
- **Akış testi:** Kayıttaki sıra (auth, saat, pil, cihaz bilgisi, kadran listesi, alarm listesi, etkinlik listesi, dosya bölümleri) sentetik fikstürlerle bir sahte band üstünde oynatılır. Kuyruk, onay ve sayaç mantığı kayıttaki davranışla karşılaştırılır. Eski oturum anahtarıyla gönderim (3.4) ve eksik bölüm zaman aşımı (7.2) için ayrı test.
- **Sağlık dosyaları:** Belgedeki yapıya göre sentetik dosyalar: tam bir gün ayrıntı dosyası (sentetik adım ve nabız deseni, 4 bölüme bölünmüş), özet dosyası, elle ölçüm dosyası (sürüm 1). CRC test içinde hesaplanır. Gerçek çözülmüş dosyalar repoya girmez. İstenirse yalnızca yerelde, `B8_FIXTURE_DIR` ortam değişkeni varsa çalışan ve CI'da atlanan bir test olur.
- **Kurallar:** Testlerde ve loglarda MAC, anahtar, kimlik, bildirim içeriği, konum ve sağlık değeri yok.

## 14. 7. adım için: Sleep as Android ve gece verisi

Bu bölüm 7. adımın fizibilitesidir. Kod yok.

### 14.1 Sleep as Android ne ister

Sleep as Android'in saat eklentisi arayüzü yayın (broadcast) mesajlarıyla çalışır. Notify bu arayüzü uyguluyor (güçlü kanıt, Notify):

- Sleep as Android'den gelenler: bağlantı kontrolü, takibi başlat, durdur, askıya al, toplu veri boyu, alarmı başlat, durdur, güncelle, ipucu (hafif titreşim), bildirim göster.
- Sleep as Android'e gidenler: bağlıyım onayı, **hareket verisi** (toplu, varsayılan 10 değerlik paketler), **nabız verisi**, alarmı ertele ya da kapat.
- Notify hareket verisini toplu paketler hâlinde, her dönemin en büyük ve toplam değerleriyle gönderiyor. Band 8 için bu değerlerin kaynağı Notify'da çözülemedi (açık).

### 14.2 Band 8 ne verebilir

| İhtiyaç | Band 8'de | Etiket |
|---|---|---|
| Canlı nabız | 8/45 başlat, 8/47 olayları, 8/46 durdur. Nabız ve birikmiş adım | güçlü kanıt |
| Ham ivme verisi | Bilinen bir komut yok. Gadgetbridge'in Xiaomi kodunda yok. Mi Fitness özellik listesinde de böyle bir özellik görülmedi | güçlü kanıt (yokluk) |
| "Gizli antrenman" ile hareket verisi | Gadgetbridge'in Xiaomi protobuf kodunda böyle bir yol bulunamadı. Antrenman komutları (8/26, 8/30, 8/48) yalnızca bandda başlatılan antrenmana telefon konumu vermek için. Bu iddia doğrulanamadı | tahmin (kanıt yok) |
| Akıllı alarm titreşimi | 2/59 ile anlık titreşim deseni ya da 7/0 ile bir bildirim. Alarm sürerken birkaç saniyede bir tekrar edilir | güçlü kanıt |
| Bandın kendi akıllı alarmı | Alarm ayrıntısında alan 7 = 1. Band uykuya göre erken uyandırır. Sleep as Android'den bağımsız | güçlü kanıt |
| Ertesi sabah ayrıntılı uyku | Uyku dosyası (alt tür 8) evreler, nabız ve SpO2 ile. Gerçek zamanlı değil | güçlü kanıt |

### 14.3 Fizibilite

- **Nabız:** yapılabilir. Takip başlayınca 8/45, bitince 8/46. 8/47'deki nabız Sleep as Android'e nabız verisi olarak gider.
- **Hareket:** tam yapılamaz. Ham ivme yok. Seçenekler:
  1. 8/47'deki birikmiş adımın farkını hareket yerine göndermek. Uykuda adım çoğu zaman 0 olduğu için çok kaba olur (tahmin).
  2. Hareket göndermeyip yalnızca nabız göndermek. Sleep as Android'in nabıza dayalı takibi çalışabilir ama hareket grafiği boş kalır (tahmin).
  3. Sabah, bandın uyku dosyasındaki evreleri ve nabzı Health Connect'e yazmak. Gerçek zamanlı akıllı alarm olmaz, ama gece verisi en doğru bu olur.
- Öneri: 7. adımda 2 ve 3 birlikte. 1 yalnızca Gelişmiş bölümünde, deneysel.
- **Akıllı alarm:** Sleep as Android "alarmı başlat" deyince banda titreşim ve bildirim gönderilir. Bandda kapatma (7/1) ya da "telefonda aç" Sleep as Android'e "kapat" olarak bağlanabilir (tahmin).
- **Pil maliyeti (tahmin):** Canlı akış, nabız sensörünü sürekli açık tutar ve bağlantıyı her olayda uyandırır. Gadgetbridge bu akışı kısa ölçümler için kullanıyor. Bütün gece açık kalınca bandın gecelik tüketimi normalin birkaç katına çıkabilir. Bir gece denenip ölçülmeden varsayılan yapılmaz (açık soru 18). Gece boyunca açık bırakmak yerine bandın kendi sürekli nabız ölçümünü (8/11, en sık 1 dakika) kullanmak pil için daha iyi.
- **Telefon tarafı:** Gece boyu bağlantı açık kalmalı. Bağlantı koparsa takip "askıya alındı" olarak bildirilir ve yeniden bağlanınca 8/45 yeniden gönderilir.

### 14.4 Aynı adımda bakılacak band ayarları

- **Bandın kendi hatırlatıcı ve takvim listesi:** var (bkz. 8.2).
- **Hareketsizlik uyarısı:** var, 8/12 ve 8/13 (bkz. 7.10).
- **Ekran kilidi:** var, 2/9 ve 2/21 (bkz. 8.4).
- **Bileği kaldırınca ekran ve duş modu:** protokolde görülmedi (bkz. 8.4).
- **Yazılım güncellemesi:** protokolde var, **uygulanmayacak** (bkz. 9).

## 15. Durum: 3. adımın protokol kısmı

`protocol/` içinde `band8` paketi yazıldı ve birim testli: protobuf, kripto, çerçeve, kanal, auth, mesajlar, etkinlik dosyası, ayrıştırıcılar, dosya çekme. Hava durumu 6. adımda. Uyku ayrıştırıcısı yok, uyku dosyası "desteklenmiyor" olarak döner (belge 7.6 kayıtla doğrulanmadı).

- Testler: RFC 5869, RFC 3610 ve NIST SP 800-38C vektörleri, bağımsız bir Python betiğiyle TEST anahtarıyla üretilmiş altın vektörler, sentetik fikstürler ve sahte bantla akış testi.
- `B8CaptureTest` yalnızca yerelde, `B8_AUTH_KEY` ve `B8_CAPTURE_FRAMES` varsa çalışır. Gerçek kayıtta 3 auth, bütün komutlar, yüklemeler ve tam dosyalar hatasız çözüldü. Yalnızca sayılar yazdırılır.
- Ayrıştırıcıların çıktısı UTC saniye ve 15 dakikalık saat dilimi birimidir. Yerel saate çevirme ortak yardımcıya bırakıldı.

## 16. İkinci istemci, sağlık arşivi ve elle denemeler (v0.15.0-alpha1)

### 16.1 İkinci istemci (doğrulandı, #16)

Android'de aynı BLE bağlantısını birden çok uygulama paylaşır. Bandın bildirimleri, karakteristiğe abone olan her uygulamaya gider. Mi Fitness ya da Notify açıksa:

- Onların auth'u bandın oturum anahtarını değiştirir. Bizim komutlarımız `ACK` alır ama band çözemediği için bileklikte bir şey olmaz.
- Onların auth yanıtları (şifresiz 1/26, 1/27) ve 0x0052'deki onayları (`ACK`, `READY`, `ALL_RECEIVED`) bize de gelir.

Cihaz kanıtı (19.11.2026, build 175): Notify kaldırıldıktan sonra da istenmemiş 1/27, kuyruk boşken üçlü `ACK` ve her bildirimden sonra `READY`/`ALL_RECEIVED` görüldü. Mi Fitness kaldırılınca dört Bluetooth turunda tek temiz auth ve hiç yabancı iz yoktu; bildirimler göründü.

Uygulama kuralı (`B8ForeignGuard`): bizim başlatmadığımız şifresiz 1/26 ya da 1/27 tek başına yabancı izdir ve auth durum makinesine verilmez. Kanalımızın beklemediği kontrol çerçevesi 30 saniye içinde ikinci kez gelirse yabancı izdir. Tek sapma tetiklemez. Logda yalnızca 4-6 baytlık çerçeve başlığı yazılır. Uyarı gösterilir ve en çok 5 dakikada bir tam yeniden bağlanılır (elle Bağlan ile aynı yol). Bizim beklediğimiz anda gelen yabancı 1/26 bizimkinden ayırt edilemez; o durumda auth "anahtar yanlış" diye düşebilir.

### 16.2 Sağlık dosyası arşivi

- Oturum hazır olunca (şifreli pil ve cihaz bilgisi yanıtı) 5 saniye sonra, sonra 6 saatte bir: 8/1, 8/2, her kimlik için ayrı 8/3 (belge 7.2). Bölüm zaman aşımı 5 saniye. İstenmeden gelen dosyalar da alınır.
- Çekme komutu yalnızca komut kanalı boşken gider (`B8FetchGate`). Bekleyen bildirim varsa önce o gider. Komut bekletilirken zaman aşımı işlemez.
- CRC tutan her dosya `files/<sağlık klasörü>/band8-files/<kimlik>.bin` olarak saklanır. Aynı kimlik gelirse son geçerli sürüm atomik yazılır.
- **8/5 hiç gönderilmez.** Band dosyaları saklamaya devam eder.
- Saklanan dosya şifresi çözülmüş ve bölümleri birleştirilmiş hâldedir: 7 bayt kimlik + 1 bayt dolgu + gövde + 4 bayt CRC32. Ayrıştırmak için anahtar gerekmez. Dışa aktarılan zip'te `index.txt` bunu ve APK kimliğini yazar.

### 16.3 Elle denemeler (cihazda doğrulanmadı)

Her düğme tek komut gönderir. Logda komut türü/alt türü ve gelen yanıtın türü/alt türü/durumu yazılır, içerik yazılmaz.

| Deneme | Komut | Yapı | Etiket |
|---|---|---|---|
| Cevapsız arama | 7/0 | "phone" paketinden normal bildirim, arama mı alanı yok, başlık "Test arama", gövde "Cevapsız arama", kimlik 1 | tahmin |
| Alarm listesi | 17/0 | yanıttan yalnızca alarm sayısı ve en çok sayı loglanır | doğrulandı (yapı) |
| Test alarmı kur | 17/1 | Ç2: saat şimdi + 2 dk, bir kez, açık, normal | güçlü kanıt |
| Test alarmını düzenle | 17/2 | Ç3: kimlik + ayrıntı, saat şimdi + 3 dk | güçlü kanıt |
| Test alarmını sil | 17/4 | Ç5: tekrarlı kimlik | güçlü kanıt |
| Rahatsız etme aç/kapat | 2/23 | S11 > 1: 0 açık, 2 kapalı | güçlü kanıt |
| Uyku modu zamanla / kapat | 17/9 | Ç9: 1 açık mı, 2 aralık (1 başlangıç, 2 bitiş, 3 = 0) | güçlü kanıt |
| Hava konumu ekle | 10/7 | alan 5: 1 kod (sabit `finnswatch`), 2 şehir adı | güçlü kanıt |
| Anlık hava | 10/0 | alan 1: meta, 2 Xiaomi kodu, 3 sıcaklık (℃, sint32) | güçlü kanıt; kod eşlemesi tahmin |
| Günlük tahmin | 10/1 | alan 2: meta, 2 > tekrarlı 1 gün: 2 kod aralığı, 3 sıcaklık aralığı (sint32), 4 ℃ | tahmin |
| Hareketsizlik ayarını oku | 8/12 | yanıttaki tek iç mesajın Health alan numarası ve alan biçimi loglanır | güçlü kanıt (komut), alanlar tahmin |
| Hareketsizlik aç/kapat | 8/13 | okunan mesaj aynı alanla geri gider, yalnızca 1. alan (açık mı) değişir | tahmin |
| Test şarkı | 18/1 | Music alan 20 > 1: 1 durum (1 çalıyor), 2 ses, 4 şarkı, 5 sanatçı, 6 konum, 7 süre | tahmin |

Alarm kimliğini band verir. Oluşturma yanıtı kimlik vermeyebilir; uygulama oluşturmadan önceki ve sonraki listede tek yeni kimlik varsa onu test alarmı sayar. Banddan gelen 18/x müzik olayları yalnızca alt türüyle loglanır.

Xiaomi hava kodu eşlemesi (tahmin): güneşli 0, parçalı bulutlu 1, kapalı 2, sağanak 3, gök gürültülü 4, karla karışık 6, çisenti 7, şiddetli yağmur 9, kar 14, sis 18, toz 29, diğer 2.

## 17. Cihaz arşiviyle doğrulanan dosya biçimleri (20.11.2026)

Kullanıcının dışa aktardığı arşiv repo dışında çözüldü ve bandın ekranıyla karşılaştırıldı. Değerler bu belgeye yazılmadı.

### 17.1 Uyku: tür 0, alt tür 8, ayrıntı 0, sürüm 2 (doğrulandı)

- Dosya kimliğindeki zaman uykunun **bitişidir**.
- Gövde: `f0 xx`, başlangıç u32 LE, bitiş u32 LE, u16 (bilinmiyor), sayı u16 LE, nabız başlangıcı u32 LE, sonra [sayı] bayt dakikalık nabız (0 = ölçüm yok). Sayı, oturumun dakika sayısından az olabilir.
- Kalan kısım `fb fa fc ff` işaretiyle ayrılmış kayıtlardır. Kayıt: `11` + 8 bayt anahtar + 2 bayt etiket + 1 bayt (0) + yük. Oturum kayıtlarının anahtarı: başlangıç u32 LE + 4 sıfır.
- Etiket `00 10` ya da `01 10` **özet**: `0d`, sürüm (`10`/`11`), sonra büyük endian u16: toplam, uyanık, hafif, REM, derin (dakika). Toplam = hafif + REM + derin.
- Etiket `00 11` ya da `01 11` **evreler**: uzunluk baytı, sonra (evre, dakika) çiftleri. Evre `00` uyanık, `10` hafif, `20` derin, `30` REM. Çiftlerin toplamı oturum süresine eşit. Uyanık parça sayısı bandın "uyanma" sayısı.
- Aynı oturum için birden çok kayıt olabilir (geçici ya da boş). Anahtarı tutan, boş olmayan son kayıt geçerlidir. Diğer kayıt türleri bilinmiyor, kullanılmaz.
- Dakikalık nabızların ortalaması bandın uyku "ortalama nabız" değeriyle eşleşti.
- Yerel doğrulama: üç oturumun üçünde toplam = hafif + REM + derin, evre toplamı = süre, evre başına toplam = özet.

### 17.2 Günlük özet: tür 0, alt tür 0, ayrıntı 1, sürüm 5

- 69 bayt. Gövde `ff ff ff ff`, sonra toplam adım u32 LE (7.7).
- Kimlik zamanı özetin alındığı andır; aynı gün için en son özet kullanılır.

### 17.3 Günlük ayrıntı: sürüm 3

- 17297 bayt: 8 bayt kimlik ve dolgu, 5 bayt başlık, 1440 × 12 bayt dakika kaydı, 4 bayt CRC (7.5).
- Yerel doğrulama: dört ayrıntı dosyasından üçünde dakika adımlarının toplamı aynı günün özetiyle tam eşit (yarım gün dahil). Bir tam günde özet, dakika toplamından %1-5 büyük. Neden bilinmiyor; birleşik görünüm günlük toplam olarak ikisinin büyüğünü kullanır.
- Bu arşivde dakika kayıtlarında nabız neredeyse hiç yok (gün başına 0-5 dakika). Bandın sürekli nabız ölçüm ayarı ayrıca kontrol edilmeli. Uyku dosyasındaki dakikalık nabız ayrıntı dosyasında olmayan dakikaları doldurur.

### 17.4 Alarm (20.11.2026 cihaz sonucu)

- 17/1 alarmı kurar ve alarm çalar. Yanıtta kimlik yok. Kimlik, 17/0 listesindeki sıra numarasıdır.
- Uygulama deneme alarmını listede saatine ve imzasına (bir kez, normal, gün bayrağı yok) göre bulur. Kurma, düzenleme ve silme taze liste ister.

### 17.5 Alarm listesinde sıfır alanlar ve düzenleme (20.11.2026, build 184)

- Düzenleme (17/2) bantta alarmı değiştirmedi. Olası neden: band sıfır değerli alanları (tekrar = 0 bir kez, dakika 0, akıllı alanı) yazmıyor olabilir. Eski ayrıştırıcı eksik alanı -1 sayıyordu, test alarmı imzaya uymuyor ve 17/2 hiç gönderilmiyordu. Artık eksik alan 0 okunur, akıllı alarm (1) dışındaki her değer "akıllı değil" sayılır.
- 17/2 yapısı: Ç3 > 1 kimlik, 2 ayrıntı (2 saat {1 saat, 2 dakika}, 3 tekrar, 5 açık, 7 akıllı/normal). Gadgetbridge de kimlik olarak liste sırasını (1'den) kullanıyor.
- Tanı logu: her 17/0 yanıtında alarm başına `kimlik=N ayrıntı=[alan biçimi] saat=[alan biçimi]` satırı. Değer yazılmaz. Düzenleme gönderilince kimlik, yanıtta durum loglanır.

### 17.6 Gelen arama (otomatik, cihazda doğrulanacak)

- Telefon çalınca 7/0 (paket "phone", arama mı = true, başlık arayan adı ya da numara). Cevap, ret ya da bitişte 7/1 (kimlik 0, paket "phone"). Elle arama ekranı denemesi bu iki komutun çalıştığını gösterdi.
- Banddan 7/2 reddet, 7/5 sessize al. Uygulama yalnızca kendi açtığı gerçek arama ekranı için tepki verir.

### 17.7 Emoji, müzik ve titreşim (20.11.2026, build 188)

- Bandın yazı tipi emoji çizemiyor: "Emoji testi" altı boş kutucuk gösterdi. Banda giden metinde yaygın emojiler kısa metne çevrilir, diğerleri çıkar (`EmojiText`, LS05 ile ortak).
- Müzik: kendiliğinden giden 18/1 `ACK` aldı ama görünmedi. Band müzik ekranı açılınca ve tuşlara basınca şifreli 18/0 gönderdi. Uygulama 18/0'ı durum isteği sayar ve 18/1 ile yanıtlar (Music alan 1: 1 durum, 2 ses, 4 şarkı, 5 sanatçı, 6 konum, 7 süre; tahmin). 18/0 içinde Music alan 2 (1 tuş, 2 ses) varsa tuş olayı sayılır: 0 çal, 1 duraklat, 3 önceki, 4 sonraki, 5 ses (tahmin). Gelen her 18/x'in alan biçimi loga yazılır; sonraki cihaz testi yapıyı doğrulayacak.
- Titreşim deseni 2/59: System alan 41 > tekrarlı 1 { 1 titreşim 1/0, 2 süre ms } (güçlü kanıt, cihazda denenmedi).
