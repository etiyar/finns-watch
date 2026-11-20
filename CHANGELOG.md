# Finn's Watch | Değişiklik günlüğü

Bu dosya kullanıcıya görünen sürüm değişikliklerini kaydeder. Teknik ayrıntılar için Git commit geçmişi, cihaz denemeleri için `docs/TEST_RESULTS.md`, yol haritası için `docs/PLAN.md` kullanılır.

Durum tanımları: **Derlendi** = CI testi ve APK derlemesi başarılı. **Cihazda doğrulandı** = gerçek cihaz denemesi kullanıcı tarafından bildirildi. İkisi aynı şey değildir.

## v0.15.0 | 20 Kasım 2026 (kararlı sürüm)

İlk Band 8 sürümü. v0.13.1–v0.15.0-rc2 arasındaki tüm değişiklikleri içerir.

- Xiaomi Smart Band 8 desteği: bağlantı, kimlik doğrulama, yabancı istemci uyarısı (Mi Fitness, Notify).
- Bildirim, gelen ve cevapsız arama, banttan reddetme, bilekliği bul, rahatsız etme, alarmlar (10 yuva), müzik kumandası ve ses düzeyi.
- Band 8 sağlık verisi: uyku (derin, hafif, REM, uyanma, nabız), adım, nabız; LS05 ile birleşik görünüm, CSV'de kaynak sütunu.
- Bildirim kuralları (filtre, iş profili, sessiz saatler, okunana kadar tekrar, titreşim), navigasyon yönergeleri, WhatsApp/Telegram aramaları, telefon pili uyarısı, emojinin metne çevrilmesi.
- Hatırlatıcılar, takvim hatırlatmaları, Band 8 hareketsizlik ayarı (deneysel).
- Cihazda doğrulanan (build 200): müzik ses düzeyi kademeli değişiyor, navigasyon bildirimi kısa manevra başlığıyla görünüyor.

## v0.15.0-rc2 | 20 Kasım 2026 (geliştirme, cihazda doğrulanmadı)

- Band 8 müzik sesi: banda telefon müzik sesinin yüzdesi bildirilir. Bandın ses olayı bu yüzdeye yakınsa uygulanır, uzaksa telefon sesi bir çentik oynar; uca sıçrama olmaz. Log bandın ham değerlerini (yalnızca sayılar) yazar.
- Navigasyon bildirimi: başlık kısa manevra adı ("Sola dön", "Hafif sağa", "Göbek", "Varış"), gövde mesafe, yönerge ve sokak. Uzun başlık sağdan kısalır, baş her zaman görünür.
- Takvim: hatırlatmamız ile takvim uygulamasının bildirimi aynı başlıkla 3 dk içinde gelirse yalnızca biri gider. Takvim bölümüne not eklendi.
- Band 8 navigasyon ekranı araştırıldı: ayrı bir protokol komutu bulunmadı (docs/BAND8_PROTOKOL.md 17.8).
- Cihazda doğrulanan (build 197): hatırlatıcılar, gelen arama ve açınca kapanma, WhatsApp ve okunana kadar tekrar, hareketsizlik ayarı okuma, takvim hatırlatmasının zamanı.

## v0.15.0-rc1 | 20 Kasım 2026 (geliştirme, cihazda doğrulanmadı)

- Band 8 müzik ses düzeyi: bandın gönderdiği düzey (0-100) telefonun müzik sesine aynen çevrilir, sistem ses çubuğu gösterilmez; güncel düzey banda geri gider. Önce ses en yükseğe ya da sıfıra gidiyordu.
- Navigasyon Band 8'e gider: uygulama filtresi Maps bildirimini sessizce atıyordu. Başlık manevra, gövde mesafe ve sokak; manevra değişince hemen, yalnızca mesafe değişince en sık 30 sn'de bir. LS05'te de aynı.
- Band 8 bildirimi iletim kapalı ya da uygulama filtresi nedeniyle atlanınca logda nedeni yazar.
- Takvim sorguları arka planda yapılır.
- Cihazda doğrulanan (build 191): Band 8 müzik bilgisi ve tuşları, titreşim deseni, bildirim kuralları, emoji metni.

## v0.15.0-beta4 | 20 Kasım 2026 (geliştirme, cihazda doğrulanmadı)

- **Hatırlatıcılar** (Saat > Hatırlatıcılar ve takvim): başlık, ilk gün, saat ve tekrar (bir kez, her gün, hafta içi, seçili günler, her N günde). Zamanı gelince telefon etkin saate bildirim gönderir; alarm notlarıyla aynı tam zamanlı alarm yolu, telefon açılışı ve saat değişiminde yeniden kurulur. 5 dakikadan çok geciken hatırlatma gönderilmez.
- **Telefon takvimi** (isteğe bağlı, takvim izni): seçili takvimlerin etkinlikleri, etkinliğin kendi hatırlatma süresiyle (yoksa 10 dk önce; tüm gün etkinliği o gün 09:00) saate gider. Reddedilen etkinlik atlanır. Takvim değişince ve 30 dakikada bir yeniden okunur. Etkinlik başlığı saklanmaz, gönderim anında takvimden okunur.
- **Band 8 hareketsizlik uyarısı (deneysel):** Saat sayfasında açık/kapalı ve saat aralığı. Bandın ayarı önce okunur, yalnızca bu alanlar değişir; gün seçimi protokolde bilinmediği için bandın kendi ayarında kalır.
- Band 8'de kurala göre titreşim, 2/59 deseni cihazda doğrulanana kadar yalnızca Gelişmiş denemesinde.

## v0.15.0-beta3 | 20 Kasım 2026 (geliştirme, cihazda doğrulanmadı)

v0.15, v0.16 ve v0.17 planları tek sürümde (v0.15.0) toplandı.

- **Bildirim kuralları** (Bildirimler > Bildirim kuralları), iki saat için ortak: uygulamaya, kişiye ya da gruba (başlık) ve kelimeye göre izin ver / engelle. Kişisel WhatsApp ve WhatsApp Business ayrı uygulama olarak seçilir. Büyük/küçük harf ve Türkçe harf farkı gözetilmez. En çok alanı dolu kural geçerlidir.
- **Sessiz saatler:** birden çok aralık, gün seçimi, gece yarısını geçen aralık; istisna uygulamalar ve kelimeler yine geçer.
- **Okunana kadar tekrar:** kurala göre bildirim telefonda durdukça X dakikada bir, en çok N kez yeniden gider; telefonda kapatılınca durur. Saatte en çok 20 tekrar. Servis yeniden başlasa da sayaçlar sürer (metin saklanmaz).
- **Titreşim:** LS05'te kurala göre titreşimsiz ya da 1-5 titreşim. Band 8'de Gelişmiş'te "Titreşim deseni testi" (2/59); kurallarda henüz kullanılmaz.
- **Navigasyon:** Google Maps yönergesi değişince saate gider; yalnızca mesafe değişirse en sık 10 sn'de bir, aynı yönerge tekrar gitmez (varsayılan kapalı). OsmAnd ve Komoot denenmedi.
- **VoIP aramalar:** WhatsApp, Telegram, Signal gibi uygulamaların çalan arama bildirimi saatte arama ekranı açar. Saatten reddetme yalnızca bildirimdeki reddet eylemiyle; telefon araması kapatılmaz.
- **Telefon pili:** isteğe bağlı düşük pil (eşik varsayılan %15) ve dolu pil uyarısı, her geçişte bir kez.
- **Emoji:** iki saat de emoji çizemiyor (Band 8'de boş kutucuk). Yaygın emojiler kısa metne döner (❤️ → <3, 👍 → (+1), 😂 → :D, 😮 → :O, 😢 → :(, 🙏 → (dua)); diğer emojiler çıkar. "Reacted 👍 to" → "Reacted (+1) to".
- **Band 8 alarmları:** Saat > Alarmlar'da bandın kendi alarm listesi. Ekle, saat/gün/açık-kapalı düzenle, sil; bandın en çok alarm sayısına kadar. Her değişiklikten sonra liste yeniden okunur. Kurma, düzenleme ve silme komutları cihazda doğrulandı; deneme düğmeleri Gelişmiş'te kalır.
- **Band 8 müzik:** band müzik bilgisi isteyince (18/0) telefondaki çalan medyanın durumu, şarkı, sanatçı, süre ve ses düzeyi gider; değişince en sık 1,5 sn'de bir güncellenir. Band tuşları (çal, duraklat, önceki, sonraki, ses) telefonu kumanda eder. Yapı tahmin; gelen 18/x alan biçimi loga yazılır.

## v0.15.0-beta2 | 20 Kasım 2026 (geliştirme, cihazda doğrulanmadı)

- Band 8 bildirimlerinde emoji korunur (ZWJ dizileri, değişken seçiciler, ten rengi dahil). Kontrol karakterleri çıkar, uzun metin emoji bölünmeden kısaltılır. LS05 metni eskisi gibi temizlenir.
- Gelişmiş > Band 8 denemeleri: "Emoji testi" sabit emojili bir test bildirimi gönderir.
- Band 8'de gerçek gelen arama: telefon çalınca bantta arama ekranı açılır, arama cevaplanınca, reddedilince ya da bitince kapanır. Bantta reddet, "Saatten aramayı reddet" ayarı açıksa aramayı reddeder; sessize al zili susturmayı dener. Arayan adı ve numarası loga yazılmaz.
- Alarm denemesi: bandın yazmadığı sıfır alanlar (bir kez, dakika 0) artık doğru okunur; test alarmı bu yüzden listede bulunamıyordu. Liste loga alan biçimiyle (değersiz) yazılır. Düzenlemenin yeni saati band kabul edince kaydedilir.

## v0.15.0-beta1 | 20 Kasım 2026 (geliştirme, cihazda doğrulanmadı)

- Band 8 adım, nabız ve uyku Bugün ekranında görünür. Uyku evrelerinde REM ayrı renkte; REM, derin, hafif, uyanık süreleri, uyanma sayısı ve uyku boyunca ortalama nabız gösterilir. Veri, arşivdeki dosyalardan uygulama açılışında ve her çekme bitince ayrıştırılır. Aynı arşiv yeniden ayrıştırılınca çift kayıt oluşmaz.
- Birleşik sağlık görünümü: adımda her saat için saatlerin en büyüğü alınır, toplanmaz. Gün toplamı, saatlik toplam ile bandın günlük özetinin büyüğüdür. Nabızda her dakika için o an etkin saat seçilir. Uykuda çakışan gecelerden daha ayrıntılı, sonra kesintisiz, sonra uzun olan seçilir. Yalnızca LS05 verisi olan günler v0.12'deki gibi görünür.
- CSV dışa aktarmada her dosyanın sonuna `kaynak` sütunu eklendi. Uyku dosyasına REM satırları eklendi.
- Bugün ekranını aşağı çekmek Band 8'de sağlık dosyalarını hemen çeker.
- Band 8 cevapsız aramaları otomatik iletir (Bildirimler > Cevapsız aramalar ayarıyla).
- Band 8'de rahatsız etme kartı açıldı: saat aralığı bantta zamanlı uyku modu olarak ayarlanır, "Şimdi aç" ve "Kapat" hemen uygular.
- Band 8 denemeleri: her düğme 2 saniyede en çok bir komut gönderir. Test alarmı artık listedeki saatinden bulunur; kurma, düzenleme ve silme taze liste ister. "Test alarmlarını temizle" deneme imzalı (bir kez, akıllı olmayan) alarmları saatleriyle gösterip onayla siler. Alarm, müzik, hava ve hareketsizlik yalnızca elle denemede kalır.

## v0.15.0-alpha1 | 20 Kasım 2026 (geliştirme, cihazda doğrulanmadı)

- Band 8'e aynı anda başka bir uygulama (Mi Fitness, Notify) bağlıysa uyarı: "Banda başka bir uygulama bağlı (Mi Fitness, Notify). O uygulamayı kaldır ya da Bluetooth iznini kapat." Uygulama en çok 5 dakikada bir tam yeniden bağlanır. Logda yalnızca çerçeve başlığı baytları görünür. #16'nın kök nedeni buydu.
- Band 8 sağlık dosyaları oturum hazır olunca ve bağlıyken 6 saatte bir okunur, CRC denetlenir ve telefonda arşivlenir. Bandan silme komutu gönderilmez. Bildirimler önce gider; çekme komut kanalı boşken ilerler. Değerler gösterilmez, loga yalnızca dosya türü sayısı, bayt ve CRC sonucu yazılır.
- Gelişmiş > Band 8 denemeleri: "Band 8 ham verisini dışa aktar" arşivi index.txt ile tek zip olarak kaydeder. Dosyalar şifresi çözülmüş hâldedir.
- Gelişmiş > Band 8 denemeleri: cevapsız arama, alarm (liste, test alarmı kur, düzenle, sil), rahatsız etme (aç, kapat, 22:00–07:00 zamanla), hava durumu (konum, anlık, tahmin), hareketsizlik uyarısı (oku, aç, kapat), müzik test şarkısı ve sağlık dosyalarını şimdi çek. Her düğme tek komut gönderir.
- Arama ekranı denemesindeki arayan metni "Test arama" oldu.
- `versionCode` CI çalıştırma numarasından gelir (1000 + numara).

## v0.13.1-beta1 sürüm adayı | 18 Kasım 2026

- `versionCode` 34 oldu. `0.13.1-alpha1` üzerine güncelleme için APK ve imza uyumu ayrıca gerçek cihazda doğrulanacak; sürüm adayı henüz `main` üzerinde değildir.
- Band 8 kayıt kurtarma ve kalıcılığı, pil/cihaz bilgisi, normal bildirimler ve bilekliği bul işlevleri önceki geliştirme APK'sıyla kullanıcı testinde çalıştı. Telefon kilitliyken bildirim, Bluetooth kapat/aç sonrası bildirim ve bulma, LS05 bildirim kontrolü de kullanıcı bildirimine göre geçti.
- Band 8 gelen/cevapsız arama, müzik ve sağlık eşitlemesi bu adayda tamamlanmış değildir. Kapalı uygulama filtresi ve uygulama elle açılmadan telefon açılışı ayrı doğrulanacaktır. Önceki tek seferlik komut reddinin kök nedeni bilinmiyor.

## v0.13.1-alpha1 | 18 Kasım 2026

- Geliştirme dalında saat listesini tek satırlı biçimde saklama ve eski biçimi okuma eklendi. Bozuk listeden Band 8 kurtarma, yazma kontrolü ve maskeli tanılama eklendi. Kullanıcı iki uygulama yeniden açma denemesi ve telefon yeniden başlatma sonrasında kayıt kaybı görmedi. Arka planda otomatik bağlantı ayrıca doğrulanacak.
- Band 8'e normal Android bildirimleri, mevcut uygulama filtresiyle iletilir. Türkçe metin ve kaynak uygulama bilgisi korunur. WhatsApp ve test bildirimi saatte görüldü; ekran kilitli ve Bluetooth kapat/aç sonrası bildirim denemeleri kullanıcı bildirimine göre geçti. Kapalı uygulama filtresi ayrıca doğrulanacak.
- Band 8 bağlıyken Gelişmiş ve log bölümünde elle başlatma ve durdurma denetimleri bulunur. Uygulamadan ve bileklikten durdurma, Bluetooth kapat/aç sonrası saati bul ve LS05 bildirim kontrolü kullanıcı testinde geçti. Önceki tek seferlik başarısızlığın kök nedeni bilinmiyor.
- Gelen ve cevapsız aramalar, müzik ve sağlık eşitlemesi sonraki işlerdir.

## v0.13.0-beta8 | 18 Kasım 2026

- Saatlerim, kaydı bulunmayan Haylou LS05 ve Xiaomi Smart Band 8 modellerini kurulum kısayolu olarak gösterir.
- Kısayol, mevcut saat kayıtları ve etkin seçim korunarak doğrudan bağlantı ayarlarını açar. Bluetooth adresi ve doğrulama anahtarı kurulumda telefonda saklanır.
- Saat eklenirken görünen genel kurulum uyarısı kaldırıldı; bağlantı tamamlandığında kurulum ilerleme yazısı temizlenir.
- Kayıt tanılaması, yükleme kaynağını, etkin saat için adres ve Band 8 anahtar zarfı bulunup bulunmadığını gösterir. Cihazda kayıt kalıcılığı testi bekliyor.

## v0.13.0-beta7 | 18 Kasım 2026

**Kayıt ve bağlantı güvenliği**
- Saat listesi için ayrı yerel kurtarma kopyası; bozuk/eksik birincil kayıt durumunda geri yükleme.
- İki kayıt da geçersizse otomatik boş listeyle üzerine yazma önlenir.
- Saat ekleme ve değiştirmede kayıt yazımı başarısızlığı kullanıcıya bildirilir; aktif saat değişimi iptal edilir.
- Kimlik anahtarı ve Bluetooth adresi içermeyen kısa kayıt tanılama özeti.
- Birincil ve yedek kayıt seçimi için Kotlin regresyon testleri.
- **Gerçek cihaz doğrulaması bekliyor:** beta6'da uygulama kapat/aç ile Band 8 kaydı korunmadı; beta7 bunun için deneme sürümüdür.

## v0.13.0-beta6 | 18 Kasım 2026
**Düzeltme (geliştirme dalı)**
- Saat listesi diske yazılamazsa RAM önbelleğinin hatalı biçimde güncellenmesi önlendi.
- Okunamayan mevcut saat listesi, yeniden oluşturulmadan önce uygulamanın özel yerel yedeğinde saklanıyor.
- Band 8 kimlik doğrulama durması daha anlaşılır ve yeniden deneme yönlendirmeli gösteriliyor.
- **Gerçek cihaz testi (18 Kasım):** Uygulama yeniden açıldığında Band 8 kaydı korunmadı (**BAŞARISIZ**). LS05 kadranı yanlış uyarı olmadan yüklendi (**GEÇTİ**). Telefon yeniden başlatma testi yapılmadı.
- Cihaz kayıt kaybının kesin kök nedeni belirlenmedi. v0.13.0-beta6 kabul edilmedi; ek hata incelemesi gerekiyor.

## v0.13.0-beta5 | 18 Kasım 2026
**Eklenen**
- Band 8 için Android BLE sürücüsü, kimlik doğrulama, pil ve cihaz bilgisi okuma.
- Bluetooth reklam taramasında ismi farklı veya isimsiz adayları görme.
- Band 8 cihazı seçildikten ve Xiaomi FE95 protokol servisi doğrulandıktan sonra anahtar isteme.
- Telefonda eşleştirilmiş cihazları listeleme ve Bluetooth adresi girerek doğrudan bağlanma.
- Anahtarı Android Keystore ile şifreli saklama ve kayıtlı Band 8 için anahtar değiştirme seçeneği.

**Düzeltilen**
- LS05 kadranı başarıyla yüklenirken yanlış 'Bu saatte kadran yüklenemez' uyarısının çıkmasına neden olan null kontrolü.
- Tarama sonuçlarının yalnızca sabit cihaz adlarıyla filtrelenmesi.
- Tarama ilerlemesi ve sonuç bulunamadığında bilgilendirme.

**Durum**
- beta5 GitHub Actions testleri ve APK derlemesi başarılı.
- **Cihazda doğrulandı:** Band 8 bilinen Bluetooth adresiyle bağlandı; şarj yüzdesi kullanıcı tarafından doğru bulundu.
- **Cihazda doğrulandı:** Bluetooth kapat/aç sonrası otomatik yeniden bağlanma ve saat değiştirirken bağlantı kurma.
- **Açık hata:** Bir oturumda doğrulama durdu mesajı ve uygulama tekrar açılınca Band 8 kaydının kaybolması bildirildi.
- **Henüz doğrulanmadı:** Telefon yeniden başlatma sonrası kayıt kalıcılığı, taramayla Band 8 bulma, kadran uyarısının yeni APK üzerinde teyidi.
- Band 8 bildirim, arama, müzik, sağlık ve ayar komutları bu sürümün kapsamına dahil değildir.

## v0.12.0 | 17 Kasım 2026
- Çoklu saat kayıt altyapısı, WatchDriver ve Ls05Driver, WatchManager, saat seçimi ve Android Keystore anahtar deposu eklendi.
- LS05 ayar ve sağlık kayıtlarının eski yolları korundu.
- Kullanıcı LS05'in temel saat işlevlerinde sorun olmadığını, kadran yüklemede ise yanlış uyarıya rağmen aktarımın başarıyla tamamlandığını bildirdi.
- Mevcut uygulamanın üzerine kurulum ve tüm sağlık verilerinin bütünlüğü ayrıca teyit edilmeli.

## Önceki sürümler
- LS05 bildirim, alarm, DND, sağlık geçmişi, kadran yükleme ve özel kadran araçları aşamalı eklendi.
- Ayrıntılı tarihçe `docs/PLAN.md` önceki bölümlerinde ve Git commit geçmişinde korunmaktadır.

### Gizlilik
Bu dosyaya Bluetooth MAC adresi, kimlik doğrulama anahtarı, bireysel sağlık ölçümü veya cihaz logu eklenmez.
