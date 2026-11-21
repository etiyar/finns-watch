# Band 8 kadran dosyası (.bin) biçimi

Bu belge Band 8 kadran dosyasının yapısını ve bizim kadran üreticimizin (`protocol/.../band8/B8FaceBin.kt`, `B8FaceDesign.kt`) neye dayandığını anlatır. Kod içermez. Yükleme komutları için `BAND8_PROTOKOL.md` bölüm 9 ve 18.

## 0. Kaynaklar ve etiketler

- **doğrulandı**: kullanıcının 5 gerçek kadran dosyasında yapı olarak görüldü (dosyalar repoya girmez, yalnızca yapı okundu) ya da cihazda gözlendi.
- **güçlü kanıt**: topluluk araçlarının herkese açık belgeleri ve davranışı, gerçek dosyalarla çelişmiyor.
- **tahmin**: dolaylı çıkarım. Cihazda denenmedi.

Kaynaklar:

1. Kullanıcının 5 kadran dosyası. İkisinin kimlik öneki 167 (bu öneki taşıyan bir kadran bu Band 8'e yüklendi ve çalıştı). Üçünün öneki 266 (Çince adlı; hangi model için yapıldığı doğrulanmadı).
2. Mi8WfBinTool (zhy8388608, GitHub): Band 8'i (`miwear.watch.m66`, araçta `mi8`) açıkça destekleyen paketleyici ve biçim notları. Lisans yok: yalnızca okundu, kod alınmadı. Yerelde yalnızca bağımsız ikinci okuyucu olarak çalıştırıldı (repoya girmez).
3. Fprj_to_wfDef (Pzqqt, MIT): Band 8 dönemi veri kaynağı adları ve 192 x 490 ekran.
4. Band 8 için yapılmış "Kite" kadran projesi (Chriz76, GitHub): öğe yerleşimi ve veri kaynakları.
5. band10-toolkit belgeleri (MIT): Band 10 için aynı ailenin biçim notları ve kurtarma önerileri.
6. Byteria Lab yazısı (Redmi Watch 5 Active): bellenimin dosyada yalnızca imzayı denetlediği, bozuk kadranda saatin yeniden başlayıp varsayılan kadrana döndüğü. Başka model; Band 8 için yalnızca uyarı.

Kurtarma uyarısı: topluluk belgeleri Band 8'i bozuk kadranda kurtarması zayıf cihazlar arasında sayıyor. Bu yüzden kendi kadranımız en küçük alt kümeyle, gerçek dosyalarla aynı yerleşimde üretilir ve yüklemeden önce denetlenir (bölüm 7).

## 1. Genel yerleşim (doğrulandı: 5 dosyanın 5'inde)

Bütün tamsayılar küçük endian. 167 önekli iki dosyada ve bizim üreticide sıra:

1. Ana başlık, 0xA8 bayt
2. İki kadran başlığı, her biri 0x58 bayt: normal ekran ve her zaman açık ekran (AOD)
3. Önce normal ekranın, sonra AOD'nin tanım tabloları (16 bayt)
4. Normal ekran: öğe özellikleri, widget özellikleri, düz görüntüler, görüntü listeleri
5. AOD: aynı sıra
6. Önizleme görüntüsü, dosyanın sonunda

- Görüntü blokları ve önizleme 4 bayta hizalı başlar; dosya boyu 4'ün katı (doğrulandı).
- Aynı içerikli görüntüler paylaşılmaz, her başvurunun kendi bloğu var (doğrulandı). Topluluk paketleyicisi paylaşıyor ve önizlemeyi görüntülerden önce koyuyor; biz gerçek dosyaları izliyoruz.
- 5 dosyanın 5'i de iki ekranlı. Tek ekranlı Band 8 kadranı görülmedi. Üretici AOD'yi zorunlu tutar.
- Dosyanın içinde sağlama toplamı yok (güçlü kanıt; 5 dosyada da böyle bir alan bulunmadı). Bütünlük aktarımda MD5 ve CRC32 ile korunur (`BAND8_PROTOKOL.md` 18.1).

## 2. Ana başlık (0xA8)

| Konum | Boy | Alan | Değer | Etiket |
|---|---|---|---|---|
| 0x00 | 4 | imza | `5A A5 34 12` | doğrulandı |
| 0x04 | 4 | ? | 167 önekli dosyalarda 0; diğerlerinde `0A 08 00 00` olabiliyor | doğrulandı (değer), anlam bilinmiyor |
| 0x10 | 4 | aygıt bayrağı | `0x800` (5 dosyada da) | doğrulandı |
| 0x14 | 4 | ? | çoğunda 0, birinde 1, birinde 0x10009 | doğrulandı (değer), anlam bilinmiyor |
| 0x1C | 2 | ekran sayısı | 2 | doğrulandı |
| 0x1E | 2 | biçem sayısı | 4 (167 önekliler) ya da 6 | doğrulandı (değer), anlam tahmin |
| 0x20 | 4 | önizleme konumu | dosya içi mutlak konum | doğrulandı |
| 0x28 | 9 | kimlik | ASCII rakam, sonrası 0 | doğrulandı |
| 0x68 | 60 | ad | UTF-8, sonrası 0 | doğrulandı |

Üretici 0x04 ve 0x14'ü 0, 0x1E'yi 4 yazar (167 önekli dosyalar gibi).

## 3. Kadran başlığı (0x58) ve tanımlar

Kadran başlığı: 0x00'da `00 00 00 02` (5 dosyada da; anlamı bilinmiyor), 0x04'te önizleme konumu (normal ekranda ana başlıktakiyle aynı, AOD'de 0 ya da `FFFFFFFF`). Sonra sekiz baytlık çiftler: sayı, tablo konumu. Boş bölümün konumu bir önceki bölümün sonudur (doğrulandı).

| Çift | Tanım türü | İçerik | Etiket |
|---|---|---|---|
| 0x08 | 0 | öğeler (ekrandaki sırayla çizilir) | doğrulandı |
| 0x18 | 2 | düz görüntüler | doğrulandı |
| 0x20 | 3 | görüntü listeleri | doğrulandı |
| 0x28 | 4 | animasyon | güçlü kanıt (örneklerde boş) |
| 0x30 | 5 | betik | güçlü kanıt (örneklerde boş) |
| 0x40 | 7 | widget (sayı, liste, ibre) | doğrulandı |
| 0x48 | 8 | düzenlenebilir öğe | doğrulandı (bir dosyada) |
| 0x50 | 9 | dokunma ile atlama | doğrulandı (bir dosyada) |

Tanım (16 bayt): 0x00 sıra (2 bayt), 0x03 tür, 0x08 mutlak konum, 0x0C boy (doğrulandı).

## 4. Özellikler

### 4.1 Öğe (16 bayt, doğrulandı)

0x00 başvurulan tanımın sırası (3 bayt), 0x03 türü (2 görüntü, 7 widget), 0x04 x, 0x06 y (işaretli 16 bit; örnekte arka plan -4, -2'de), kalan 8 bayt 0.

### 4.2 Sayı widget'ı (20 bayt, doğrulandı)

| Konum | Alan |
|---|---|
| 0x00 | veri kaynağı, 2 bayt (bölüm 6) |
| 0x02 | basamak sayısı |
| 0x03 | `0x10` + (başta sıfır göster ise 4) + hizalama (0 sağ, 1 sol, 2 orta) |
| 0x08 | rakam listesinin tanım sırası (3 bayt), 0x0B'de tür 3 |
| 0x0D | basamak arası (işaretli; örneklerde -3, -14) |
| 0x10 | isteğe bağlı sonek görüntüsü (3 bayt sıra), 0x13'te tür 2 |

Hizalama ve "başta sıfır" anlamı topluluk araçlarından (güçlü kanıt). Gerçek bir dosyadaki saat widget'ı: `08 11 02 15 00 00 00 00 03 00 00 03 00 FD 00 ...`.

### 4.3 Görüntü listesi widget'ı (doğrulandı)

16 bayt: kaynak, 0x03'te `0x20`, 0x08'de liste. Değer eşlemesi varsa 0x0C'de `00 02 00`, 0x10'dan itibaren değer başına 4 bayt (44 ve 56 baytlık örnekler). Topluluk paketleyicisi eşlemesiz listeyi 20 bayt yazıyor; gerçek dosyalarda 16.

## 5. Görüntü blokları

Başlık 12 bayt: 0x00 işaret, 0x04 genişlik, 0x06 yükseklik, 0x08 veri boyu.

| Tür | 0x01 | 0x02 | Etiket |
|---|---|---|---|
| Düz görüntü | 4 = sıkıştırılmış, 0 = ham | 0 | doğrulandı |
| Liste | görüntü sayısı | 4 = sıkıştırılmış, 0 = ham | doğrulandı |

- İşaret 3: RGB565 küçük endian, saydamsız (arka planlar, önizleme). İşaret 0: BGRA 8888 (saydam rakamlar, listeler). İkisi de 5 dosyada doğrulandı.
- Sıkıştırılmış liste: başlıktan sonra görüntü başına 4 bayt boy tablosu, sonra yükler. 0x08'deki toplam 5 dosyanın 4'ünde yalnızca yüklerin toplamı, birinde (266250014) tablo dahil (doğrulandı). Üretici ilkini yazar, denetleyici ikisini de kabul eder.
- Sıkıştırılmış yük: `E0 21 A5 5A`, sonra 4 bayt (ham boy << 4 | piksel başına bayt), sonra kayıtlar. Kayıt: üst bit 0 ise 1-127 kez tekrar eden tek piksel; üst bit 1 ise ardından gelen 1-127 piksel olduğu gibi (doğrulandı: 5 dosyadaki 800'ü aşkın sıkıştırılmış görüntünün hepsi bu kuralla tam boyuna açıldı).
- Ham görüntü de geçerli: bir dosyanın AOD'sinde ham BGRA görüntü ve liste var (doğrulandı).
- Önizleme: 122 x 310, işaret 3, sıkıştırılmış (doğrulandı).
- Üretici yalnızca tekrar kaydı yazar (topluluk paketleyicisi de öyle; bir gerçek dosya da yalnızca tekrar kaydı kullanıyor).

## 6. Veri kaynakları

| Kod | Anlam | Etiket |
|---|---|---|
| 0811 | saat (0-23) | güçlü kanıt: gerçek dosyada AOD'de saat, iki nokta, 1011 sırası; ibre kaynağı olarak da aynı kod |
| 1011 | dakika | güçlü kanıt (aynı) |
| 1811 | saniye | güçlü kanıt |
| 0A11 / 0911 | saatin onlar / birler basamağı | güçlü kanıt |
| 1211 / 1111 | dakikanın onlar / birler basamağı | güçlü kanıt |
| 1012 | ay | güçlü kanıt: iki gerçek dosyada ve Kite'ta 1012, nokta, 1812 dizilişi |
| 1812 | gün | güçlü kanıt (aynı) |
| 1912 / 1A12 | günün birler / onlar basamağı | güçlü kanıt |
| 2012 | haftanın günü (7 görüntülü liste) | güçlü kanıt |
| 0841 | pil yüzdesi (3 basamak) | güçlü kanıt |
| 0821 | adım (5 basamak) | güçlü kanıt |
| 0822 | nabız | güçlü kanıt |
| 0813 | bilinmiyor (liste) | doğrulandı (görüldü), anlam bilinmiyor |

## 7. Yüklemeden önce denetim (uygulamada)

`B8FaceBin.check` bütün kadran dosyalarına uygulanır; kendi kimlik önekimizdeki (1672990) dosyalar denetimden geçmezse hiç gönderilmez. Diğer dosyalarda sonuç onay penceresinde gösterilir.

Denetlenenler: imza, boy (en çok 8 MB), ekran sayısı 1-2, kimlik 9 rakam, önizleme var ve açılıyor; her bölüm tablosu ve her tanım dosya içinde, türü doğru; her düz görüntü ve listenin işareti biliniyor (0 ya da 3), boyu 192 x 490 içinde, sıkıştırılmış veri tam ham boyuna açılıyor ve toplam tutuyor; her öğe var olan bir tanıma bağlı, konumu ekran çevresinde; sayı ve liste widget'larının listesi var.

Kullanıcının 5 dosyası denetimden sorunsuz geçti (yanlış alarm yok). Bizim deneme kadranımız da geçti. Ayrıca topluluk paketleyicisinin okuyucusu bizim dosyamızı doğru açtı (öğeler, kaynaklar, görüntüler).

Kimlik öneki uyarısı: yüklenen kimliğin ilk 3 hanesi, banttaki sonradan yüklenmiş kadranların öneklerinden (yoksa bütün kadranlarınkinden) farklıysa onay penceresinde uyarı çıkar. Bu bantta yerleşikler 266, yüklenip çalışan kadran 167 önekli. Önekin anlamı (model, bölge, mağaza) tahmin.

## 8. Deneme kadranı "Finn Dijital"

- Kimlik 167299001 (bizim aralığımız 1672990xx; mağaza kimlikleriyle çakışmaması için seçildi, tahmin).
- Normal ekran: siyah arka plan (192 x 490, RGB565), saat 0811 ve dakika 1011 iki basamaklı sayı olarak (74 x 124 beyaz yedi parçalı rakam), altta ay 1012, nokta, gün 1812 (20 x 34 rakam).
- AOD: gri saat ve dakika, aralarında küçük nokta.
- Boyu yaklaşık 56 KB. Rakamlar yazı tipi olmadan çizilir.
- Gelişmiş > Band 8 kadran > "Deneme kadranı: Finn Dijital" telefonda aynı dosyayı üretir.

## 9. Bilinmeyenler

- 0x04, 0x14, 0x1E ve kadran başlığı 0x00 alanlarının anlamı. Gerçek dosyaların değerlerini yazıyoruz.
- Bellenimin hangi alanları denetlediği. Redmi Watch 5 Active yalnızca imzayı denetliyordu; Band 8 için bilinmiyor.
- AOD'siz kadranın kabul edilip edilmediği.
- Önek 266 ile 167 arasındaki fark.
