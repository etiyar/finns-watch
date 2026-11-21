# Finn's Watch: Xiaomi Smart Band 8 desteği ve çoklu saat planı

Bu belge, projeyi hiç bilmeyen bir gözden geçirici için yazıldı. Amacı: planı kontrol etmek, riskleri ve eksikleri bulmak, daha iyi bir yol varsa önermek.

## 1. Proje ne?

- **Finn's Watch:** Che'nin kendi Android uygulaması. Repo: github.com/etiyar/ls05-controller (herkese açık).
- **Amaç:** Haylou Solar LS05 akıllı saatinin üretici uygulamasını (Haylou Fun) tamamen değiştirmek. Üretici uygulaması artık güncellenmiyor.
- **Teknik yapı:** Kotlin. Yalnızca Android framework View'ları kullanılıyor; AndroidX, Compose ve coroutine yok. minSdk 26, targetSdk 35.
  - `protocol/` modülü saf Kotlin, birim testleri burada.
  - `app/` modülü Android tarafı.
  - Derleme ve testler GitHub Actions'ta çalışıyor. Telefona yayın yalnızca `main` dalından çıkıyor.
- **LS05'te bugün çalışanlar** (hepsi cihazda doğrulandı):
  - Bağlantı ve kimlik doğrulama; bildirim iletimi (uygulama filtresi, Türkçe karakter).
  - Gelen ve cevapsız arama; saatten aramayı reddetme.
  - Alarm (titreşim süresiyle) ve alarm notu; rahatsız etme.
  - Hava durumu (Open-Meteo).
  - Adım, nabız ve uyku geçmişi; 7/30 günlük grafikler; CSV dışa aktarma.
  - Saatten müzik kumandası; telefonu bul / saati bul.
  - Kayıp saat uyarısı ve son görülen yer.
  - Kadran yükleme: kendi kadran üreticimiz ve .tbwf tasarım çevirici.
- **LS05 protokolü:** Haylou Fun ve Hello Haylou uygulamaları incelenerek ve Bluetooth kayıtlarıyla çözüldü. Bulgular `docs/PLAN.md` içinde.

## 2. Yeni istek

Che'de bir de Xiaomi Smart Band 8 var. Bazen LS05'i, bazen Band 8'i takmak istiyor. Uygulama ikisini de desteklemeli.

**Che'nin kararları:**
- **Aynı anda tek saat bağlı olur.** Uygulamada "Saatlerim" listesi olur; hangisini takacaksa onu seçer, tek dokunuşla geçer.
- **Sağlık grafikleri birleşik olur.** Hangi saati takarsa taksın, adım, nabız ve uyku tek bir zaman çizgisinde görünür. Her kaydın kaynağı (hangi saat) yine de saklanır.
- **Kimlik anahtarı:** Band 8'i Mi Fitness ile eşleştirip anahtarı almayı daha önce başka bir uygulama için yapmış; yöntem tanıdık.

## 3. Band 8 hakkında bilinenler

- **Protokol:** Band 8, LS05'ten tamamen farklı bir protokol kullanıyor. Xiaomi'nin şifreli, protobuf tabanlı protokolü bu.
  - Bağlanmak için Xiaomi hesabına bağlı gizli bir kimlik anahtarı gerekiyor.
  - Anahtar, Mi Fitness ile bir kez eşleştirdikten sonra Xiaomi sunucusundan bir araçla alınıyor. Sonra Mi Fitness'a gerek kalmıyor; ancak üretici uygulamadaki eşleşme silinirse anahtar geçersizleşiyor.
- **Referans 1, Gadgetbridge:** Açık kaynak bir uygulama. Band 8'i iyi destekliyor ("Xiaomi protobuf" ailesi). Kodu AGPL lisanslı, bu yüzden kod kopyalanmayacak; belgeleri ve protokol bilgisi referans olarak kullanılacak.
- **Referans 2, Notify for Xiaomi 24.0.4:** Kapalı kaynak, ücretli. Che'nin elinde APK'sı var; içinde Band 8, 8 Active, 9 ve 10 tanımları, protobuf ve anahtarla bağlanma kodu görülüyor.
  - Yalnızca protokolü anlamak için incelenecek, kodu kopyalanmayacak.
  - Uygulamanın paketi `com.mc.xiaomi1`. İçinde Xiaomi'nin `com.xiaomi.xms.wearable` SDK'sı da var.
- **Kadranlar taşınmaz:** Band 8'in ekranı ve kadran biçimi farklı; LS05 kadranları orada çalışmaz.

## 4. Plan

### Aşama 0: Çözümleme (uygulamaya dokunmaz)
Bir ajan Notify'ı (jadx ile) ve Gadgetbridge'in herkese açık belgelerini inceleyip `docs/BAND8_PROTOKOL.md` yazar. Belgelenecekler:
- Bağlantı: GATT servisleri ve kanallar.
- Kimlik doğrulama: anahtarla el sıkışma, şifreleme yöntemi ve oturum anahtarı.
- Mesaj çerçevesi: protobuf şeması (komut türleri, alt türler), parçalama ve onaylar.
- Temel komutlar: saat eşitleme, bildirim gönderme, gelen ve cevapsız arama (arama reddi dahil), pil, band'i bulma.
- Ayarlar ve hava: alarm, rahatsız etme, hava durumu.
- Sağlık verisi: adım, nabız ve uyku geçmişi; dosya tabanlı aktarım varsa biçimi.
- Kadran yükleme: yalnızca bilgi için, uygulanmayacak.

Her bulgu güven düzeyiyle yazılır: doğrulandı / güçlü kanıt / tahmin. Gerekirse Che'den, Notify ile Band 8 kullanırken alınmış bir Bluetooth kaydı (btsnoop) istenir.

### Aşama 1: "Saatlerim" altyapısı (yalnızca LS05 ile)
- **Saat arayüzü:** Saat türünden bağımsız bir arayüz tanımlanır (`WatchDevice`). İçeriği: bağlan / kes, bildirim, arama, saat eşitleme, alarm, rahatsız etme, hava, geçmiş okuma, bul, pil. Ayrıca her saatin hangi özellikleri desteklediğini bildiren bir bayrak kümesi.
- **LS05'in taşınması:** Bugünkü LS05 kodu bu arayüzün arkasına taşınır.
- **Arayüz:** Saatin desteklemediği özellikler gizlenir; örneğin kadranlar Band 8'de görünmez.
- **"Saatlerim" ekranı:** Saat ekleme, adlandırma, etkin saati seçme ve silme. Her saatin ayarları ayrı saklanır (adres, kimlik, anahtar, alarm yuvaları gibi).
- **Sağlık deposu:** Kayıtlara kaynak alanı eklenir. Görünüm katmanı aynı zaman aralığı için tek kaynak seçer: o gün hangi saatin verisi varsa o; ikisi de varsa o gün en çok veri gelen saat. Çift sayım olmaz.
- **Kabul ölçütü:** LS05'in tüm özellikleri bugünkü gibi çalışır. Mevcut ayarlar ve sağlık geçmişi kaybolmaz; taşıma (migration) testleri yazılır.

### Aşama 2: Band 8 temel
- Anahtar girişi ve band eşleştirme. Anahtar Android Keystore ile şifreli saklanır; asla loga ya da repoya yazılmaz.
- Bağlantı, kimlik doğrulama, saat eşitleme.
- Bildirimler (mevcut bildirim filtresiyle), gelen ve cevapsız arama, pil, band'i bul.

### Aşama 3: Band 8 sağlık ve ayarlar
- Adım, nabız ve uyku geçmişi, birleşik grafiklere.
- Alarm, rahatsız etme, hava durumu (mevcut Open-Meteo verisiyle).

### Aşama 4 (sonra, ayrı karar): Band 8 kadranları

## 5. Çalışma kuralları (değişmez)

1. Saate doğrulanmamış komut otomatik gönderilmez. Yeni komut önce elle, Gelişmiş bölümündeki bir düğmeyle denenir.
2. Kişisel veri loga ya da repoya yazılmaz: MAC, kimlik ve anahtar, bildirim içeriği, konum, sağlık değerleri.
3. Kodda, yorumlarda ve commit mesajlarında yapay zekâ izi bırakılmaz. Arayüz Türkçedir.
4. Kod kopyalanmaz: Gadgetbridge (AGPL) ve Notify (kapalı kaynak) yalnızca protokolü anlamak için incelenir.
5. Her aşama ayrı dalda yapılır. Telefonda test edilmeden `main`'e yayın yapılmaz. Saf mantık `protocol/` modülünde birim testiyle gelir.
6. Verimlilik: Bağımsız işler paralel ajanlarla yürütülür. Her ajan kendi git worktree'sinde çalışır.

## 6. Tahmini iş

| Aşama | Oturum |
|---|---|
| 0 | 1 |
| 1 | 1-2 |
| 2 | 2 |
| 3 | 2 |

Her aşamanın sonunda Che telefonda test eder.

## 7. Gözden geçiriciden istenenler

1. Bu sıralama doğru mu? Örneğin Aşama 0 bitmeden Aşama 1'e başlamak mantıklı mı, yoksa paralel mi yürümeli?
2. Çoklu saat yapısında gözden kaçan bir şey var mı? Bildirim dinleyici, önplan servisi, eşleştirme (bond), arka planda bağlantı.
3. Birleşik sağlık verisi kuralı (gün başına tek kaynak) uyku için yeterli mi? Gece saat değiştirme gibi uç durumlar.
4. Kimlik anahtarının güvenliği ve Xiaomi tarafında bilinen tuzaklar. Örneğin üretici uygulaması kaldırılınca anahtarın geçersiz olması, ya da aynı anda iki uygulamanın bağlanmaya çalışması.
5. Band 8 için Gadgetbridge belgelerinde bilinen sorunlar ya da sınırlar.
6. Lisans açısından risk: kapalı kaynak bir uygulamayı protokol için incelemek.
7. Atlanan bir aşama ya da daha verimli bir yol.

## 8. Revizyon (gözden geçirme sonrası, 17 Kasım, onaylandı)

Gözden geçiricinin mimari önerileri kabul edildi: WatchManager (etkin saat, yaşam döngüsü, geçiş durum makinesi), ayrı sürücüler (LS05, Band 8), küçük bir Capabilities modeli, cihazdan bağımsız ortak servisler. Yeni Gradle modülü yok. Auth key Android Keystore'da. Göç ve gerileme testleri zorunlu. Eski veri okunabilir kalır.

### Sağlık birleştirme kuralları
- **Adım:** Saatlik bazda kaynakların en büyüğü. Band 8 yalnızca günlük toplam veriyorsa ve saatlik fark çıkarılamıyorsa günlük toplam ayrı tutulur. Uydurma saatlik dağılım yok.
- **Nabız:** Saat değişim zamanları kaydedilir. Aynı dakikada etkin saat kullanılır. Değilse iki ham okuma da tutulur ve sabit öncelik uygulanır (önce LS05).
- **Uyku:** Oturum bazında. Daha ayrıntılı ve bütün olan seçilir (derin/hafif ayrımı, boşluksuz). Eşitlikte uzun oturum.

### Uygulama düzeni: 2 kalıcı ajan
| Ajan | Adımlar |
|---|---|
| A: Band 8 | 1. docs/BAND8_PROTOKOL.md → 3-4. bağlantı, auth, temel işlevler → 6. alarm, rahatsız etme, hava |
| B: Çekirdek | 1. docs/COKLU_SAAT_TASARIM.md → 2. altyapı ve LS05'in taşınması → 5. birleşik sağlık verisi |

Her adım arasında onay. Adım 1 yalnızca belge üretir: kod, çalışan uygulama, sağlık verisi ve saat ayarları değişmez.

### Kaynaklar
- Notify for Xiaomi APK: yalnızca protokol başvurusu, kod kopyalanmaz.
- Gadgetbridge: yalnızca herkese açık belgeler ve davranış; AGPL kodu kopyalanmaz.
- 17 Kasım Bluetooth HCI kaydı (Notify + Band 8, ~16 dk): repo dışında tutulur, MAC ve içerik belgeye yazılmaz.

### Adım 7 (17 Kasım, onaylandı): Sleep as Android ve Health Connect
- Sleep as Android "Wearables" eklentisi (DIY seçeneği) ile entegrasyon: canlı nabız, gece hareket verisi, akıllı alarm titreşimi. Önce Band 8; LS05'in nabız gönderip gönderemeyeceği ayrıca denenir.
- Health Connect'e birleşik sağlık verisinin yazılması (Android 14+ sistem API'si, ek kütüphane yok).
- Küçük Band 8 ayarları 6. adıma eklenir: duş modu (kaldırınca uyanma kapalı), hareketsizlik uyarısı.
- Kapsam dışı: bellenim güncelleme, Google Fit, Sheets, Home Assistant, Tasker.

## 9. Sonraya notlar (17 Kasım)
- **Repo adı:** GitHub reposu uygulama adına geçecek (öneri: finns-watch). Sürüm başlıklarındaki "LS05 Controller" da değişecek. Paket adı com.che.ls05 değişmez (değişirse güncelleme yerine yeniden kurulum, veri kaybı).
- **Yedekle ve geri yükle (Gelişmiş):** Tek dosyalık tam yedek: saat kaydı, saate göre ayarlar, alarm notları, kadran kütüphanesi, sağlık klasörleri, geçiş günlüğü. Uygulama silinip yeniden kurulsa da geri yüklenebilmeli. Bugün yalnızca sağlık CSV dışa aktarımı var, geri yükleme yok; allowBackup kapalı. Band 8 auth key yedeğe düz yazılmaz: ya hiç girmez (yeniden girilir) ya da kullanıcının parolasıyla şifrelenir. Geri yüklemeden önce mevcut durumun otomatik yedeği alınır.
- **Saat bilgilerini göster (Saatlerim):** Her saatin kartında kimlik bilgisi görünsün. LS05 kullanıcı kimliği ve Band 8 anahtarı varsayılan maskeli (••••1234). "Göster" ve "Kopyala" ekran kilidi doğrulamasından (KeyguardManager) sonra çalışır. Kopyalanan değer 60 sn sonra panodan silinir. Loga hiçbir zaman yazılmaz. Band 8 için "Anahtarı değiştir" de aynı yerde.

## v0.17 temizlik

v0.17.0-alpha2'de uygulandı: her satırın sonundaki "yapıldı" notu ne olduğunu söyler. Koşullu (a) satırları (koşulu henüz doğrulanmayan) Gelişmiş'te bırakıldı. Sınıflar: **(a)** doğrulandı ve normal bir ekranda zaten var, kaldırılabilir. **(b)** doğrulandı ama normal ekranda yeri yok, taşınmalı. **(c)** hâlâ gerekli, kalır. Doğrulama kaynağı `docs/TEST_RESULTS.md`.

### Gelişmiş ve log ekranı (Ayarlar > Gelişmiş ve log)

| Öğe | Komut | Doğrulama (TEST_RESULTS) | Normal ekrandaki yeri | Sınıf |
|---|---|---|---|---|
| Saati eşitle | saat eşitleme | Band 8 her auth'tan sonra saati kendisi gönderir. LS05 için elle eşitleme kayıtlı değil | Ayarlar > "Bağlanınca saati eşitle" (otomatik) | (c) LS05 için elle kurtarma yolu. yapıldı: Gelişmiş > Saat (yalnızca LS05) |
| Band 8 saati bul testi: Başlatmayı dene, Durdurmayı dene | 2/18 | GEÇTİ (20.11, build 182 ve 19.11, build 173) | Yok. Saat > "Saati bul" kartı Band 8'de gizli (`findWatch = false`) | (b) Band 8 için `findWatch` açılıp Saat > Saati bul kartına taşınır. yapıldı: `findWatch` açık; Ana > Bandı bul ve Saat > Saat ayarları > Bandı bul |
| Band 8 arama ekranı denemesi: dene, kapat | 7/0 arama | Gerçek gelen arama GEÇTİ (20.11, build 197); bantta reddet GEÇTİ (build 188) | Bildirimler > Gelen aramalar (otomatik) | (a). yapıldı: kaldırıldı |
| Emoji testi | 7/0 | Emoji kısa metne dönüyor, GEÇTİ (build 191) | Otomatik (her bildirim) | (a). yapıldı: kaldırıldı |
| Titreşim deseni testi | 2/59 | GEÇTİ (build 191) | Yok. Kurallardaki titreşim yalnızca LS05 | (b) Bildirim kuralına Band 8 titreşim deseni seçimi olarak taşınır. yapıldı: kuraldaki titreşim sayısı Band 8'de desen olarak gider (B8Vibration.count) |
| Canlı nabız 30 sn | 8/45, 8/46 | Sleep as Android gecesinde canlı nabız GEÇTİ (21.11, build 215) | Ayarlar > Sleep as Android | (a). yapıldı: kaldırıldı |
| Gizli antrenman 30 sn | 8/26 | Sleep as Android hareket verisi GEÇTİ (21.11, build 215) | Ayarlar > Sleep as Android | (a). yapıldı: kaldırıldı |
| Cevapsız arama | 7/0 "phone" | Deneme ve gerçek cevapsız arama GEÇTİ (build 182, 184) | Bildirimler > Cevapsız aramalar | (a). yapıldı: kaldırıldı |
| Alarm: listeyi oku, test alarmı kur, düzenle, sil | 17/0, 17/1, 17/2, 17/4 | Kurma, düzenleme, silme GEÇTİ (build 188) | Saat > Band 8 alarmları kartı | (a). yapıldı: kaldırıldı |
| Test alarmlarını temizle | 17/4 | Silme GEÇTİ. Eski kopya alarmların kalmadığı kullanıcıya sorulmalı | Saat > alarm kartında tek tek silme | (a) kopyalar bittiği doğrulanınca. yapıldı: koşul doğrulanmadığı için Gelişmiş > Alarm'da kaldı |
| Rahatsız etme: şimdi aç, şimdi kapat | 2/23 | GEÇTİ (build 182, 184) | Saat > Rahatsız etme satırı | (a). yapıldı: kaldırıldı (Saat ayarları > Rahatsız etme > Şimdi aç/Kapat) |
| Rahatsız etme: zamanla 22:00-07:00, zamanlamayı kapat | 17/9 | GEÇTİ (build 184) | Saat > zamanlı rahatsız etme ayarı | (a). yapıldı: kaldırıldı |
| Hava: konum ekle, anlık hava, günlük tahmin | 10/7, 10/0, 10/1 | DOĞRULANMADI (bantta hava ekranı bulunamadı) | Yok (Band 8'de `weather = false`) | (c). yapıldı: Gelişmiş > Hava durumu |
| Hareketsizlik: ayarı oku, aç, kapat | 8/12, 8/13 | Okuma GEÇTİ (build 197). Yazma doğrulanmadı | Saat > hareketsizlik kartı (okuma ve yazma) | (a) yazma kartla doğrulanınca. yapıldı: yazma doğrulanmadığı için Gelişmiş > Hareketsizlik'te kaldı |
| Otomatik nabız ölçümü: ayarı oku, her 10 dk, sürekli, kapalı (bu sürümde eklendi) | 8/10, 8/11 | Doğrulanmadı | Yok | (c) doğrulanınca Saat sayfasına taşınır. yapıldı: Gelişmiş'te kaldı |
| Test şarkı gönder | 18/1 | Müzik bilgisi, tuşlar ve ses GEÇTİ (build 191, 200) | Otomatik (bant isteyince) | (a). yapıldı: kaldırıldı |
| Sağlık dosyaları: şimdi çek | 8/1, 8/2, 8/3 | GEÇTİ (build 182) | Bugün > aşağı çek | (a). yapıldı: kaldırıldı (Ana ve Sağlık > aşağı çek) |
| Band 8 ham verisini dışa aktar | yok (dosya) | GEÇTİ (build 182) | Yok | (c) biçim incelemesi için gerekli. yapıldı: Gelişmiş > Band 8 ham verisi ve Ayarlar > Sağlık verilerini dışa aktar |
| Uygulama simgesini gönder (anahtar) | 7/16, 7/15, yükleme | GEÇTİ (21.11, build 215) | Yok | (b) Bildirimler ayarlarına taşınır, varsayılan açık düşünülebilir. yapıldı: Saat > Bildirimler (varsayılan kapalı kaldı) |
| Band 8 kadran: listeyi oku, dosya yükle, Finn Dijital deneme kadranı, etkin yap, sil | 4/0, 4/4, 4/1, 4/2, yükleme | Yükleme ve liste GEÇTİ (build 212, candidate-build-223). Etkin yap ve sil ayrıca kaydedilmedi | Yok. Saat > Kadranlar yalnızca LS05 (`faces = false`) | (b) feat/v017-faces işiyle Saat > Kadranlar'a taşınır. yapıldı: Saat > Kadranlar |
| Logu kopyala, logu temizle | yok | Gerekli | Yok | (c). yapıldı: Gelişmiş > Log |

### Tanılama logları

| Log satırı | Dosya | Doğrulama | Sınıf |
|---|---|---|---|
| "Band 8 komut kuyruğu: tür, altTür, bekleyen, çerçeve" (her komutta) | Band8Ble.kt | ACK ile fiziksel sonucu ayırmak için temel kanıt | (c). yapıldı: kaldı |
| "Band 8 deneme / deneme reddi" | Band8Ble.kt | Denemelerle birlikte | (c) denemeler kalktıkça azalır. yapıldı: kaldı |
| "Band 8 alarm listesi biçimi" (her alarmın alan biçimi) | Band8Driver.kt | Alarm GEÇTİ | (a). yapıldı: kaldırıldı |
| "Band 8 hareketsizlik ayarı okundu: alanlar" | Band8Driver.kt | Okuma GEÇTİ, yazma bekliyor | (c) yazma doğrulanınca (a). yapıldı: kaldı |
| "Band 8 nabız ölçüm ayarı okundu: alanlar, kapalı mı, aralık" (yeni) | Band8Driver.kt | Doğrulanmadı | (c). yapıldı: kaldı |
| "Band 8 müzik olayı: altTür, alanlar" (her müzik olayında) | Band8Driver.kt | Müzik GEÇTİ | (a) yalnızca tanınmayan olay kalır. yapıldı: yalnızca tanınmayan olay yazılır |
| "Band 8 müzik sesi ham: alan1, alan2, akış" | Band8Music.kt | Ses GEÇTİ (build 200) | (a). yapıldı: kaldırıldı |
| "Band 8 müzik bilgisi (neden): oturum, çalıyor, ses, gönderildi" | Band8Music.kt | GEÇTİ | (a) hata durumunda kalır. yapıldı: yalnızca gönderilemezse yazılır |
| "Band 8 canlı nabız denemesi bitti", "gizli antrenman denemesi bitti" | Band8Driver.kt | Denemeyle birlikte | (a). yapıldı: denemelerle kaldırıldı |
| "Band 8 arama olayı: tür=7, altTür" | Band8Driver.kt | Reddet GEÇTİ | (a) kısa olay satırı kalabilir. yapıldı: kısa satır ("Band 8 arama: reddet") |
| "Band 8 simge biçimi: durum, biçim, kenar" | Band8Icons.kt | Simge GEÇTİ | (a) biçim desteklenmezse uyarı kalır. yapıldı: yalnızca biçim desteklenmezse yazılır |
| "Band 8 yükleme yanıtı: durum, devam, bölümBoyu, md5" | Band8Ble.kt | Yükleme GEÇTİ, hatalarda gerekli | (c). yapıldı: kaldı |
| "Band 8 kadran ... alanlar=" (etkin yap, sil, kurulum, olay) | Band8Faces.kt | Etkin yap ve sil kaydedilmedi | (c). yapıldı: kaldı |
| "Band 8 başka istemci izi", "beklenmeyen kontrol çerçevesi" | Band8Ble.kt | #16 koruması | (c). yapıldı: kaldı |
| "BLE yanıtsız yazım callback'i gelmedi" | Band8Ble.kt | Bağlantı hatası tanısı | (c). yapıldı: kaldı |
