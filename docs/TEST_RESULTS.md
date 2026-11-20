# Finn's Watch | Gerçek cihaz test sonuçları

Yeni sonuç biçimi: **Tarih | APK kimliği | Test | Fiziksel gözlem | Logun kanıtladığı aşama | Sonuç.** Yalnızca saat üzerinde gözlenen sonuç `GEÇTİ` sayılır. `ACK` tek başına fiziksel tepkiyi doğrulamaz. Önceki satırlarda tam APK kimliği yoksa eski sonuca yeni kimlik atanmaz; **APK kimliği yok** olarak değerlendirilir. PR #18 cihaz testi için PR'a önceden yazılan kimlik ile Ayarlar > Hakkında satırı eşleşmelidir.

| Tarih | APK kimliği | Test | Fiziksel gözlem | Logun kanıtladığı aşama | Sonuç |
| --- | --- | --- | --- | --- | --- |
| 18.11.2026 | `0.13.1-beta1 · build 161 · run 44386438656-1 · b881509 · feat/apk-build-identity`; kullanıcı Ayarlar > Hakkında kimliğini doğruladı | Mevcut kurulum üzerine APK yükleme ve kalıcılık | Kullanıcı kurulumun başarılı olduğunu, iki saate bağlandığını ve önceki verilerin durduğunu bildirdi. Band 8 anahtarının içerik/bütünlüğü ayrıca incelenmedi. | [CI build 161](https://github.com/etiyar/ls05-controller/actions/runs/44386438656) başarılı. Cihaz logu paylaşılmadı; sonuç kullanıcının fiziksel gözlemi. | **GEÇTİ (kullanıcı bildirimi): kimlik, kurulum, saat bağlantıları ve önceki veriler** |
| 18.11.2026 | `0.13.1-beta1 · build 170 · run 44391327672-1 · 5a66fa1 · test/band8-reauth-identity` | Band 8 bildirim ve bulma, Bluetooth kapat/aç sonrası | Kullanıcı ilk bağlantıda bildirim ve bulmanın çalıştığını, Bluetooth kapatılıp açılınca fiziksel tepki alınmadığını bildirdi. 23:37:03'te kuyruktaki bildirim gönderilirken 23:37:04'te oturum yenileme nedeniyle bağlantı kapandı. 23:37:06'da yeniden bağlanıp kimlik doğruladı; sonrasındaki bildirim ve bulma yine bileklikte çalışmadı. | İlk oturum ve yeniden bağlanmada GATT, auth ve cihaz bilgisi; sonraki `MESSAGE` ve bulma komutlarında BLE kabulü ile ACK. 23:37:04 kapanışında bekleyen 2 komut vardı. ACK fiziksel uygulama kanıtı değildir. | **BAŞARISIZ: Bluetooth kapat/aç sonrası fiziksel işlevler; yeniden bağlantı GEÇTİ, beklenmedik yeniden doğrulama sonrası toparlanma doğrulanmadı** |
| 19.11.2026 | Sağlanan test APK'sı: `0.13.1-beta1 · build 173 · run 44401797442-1 · c497121 · test/band8-reauth-identity`; Ayarlar > Hakkında satırı ayrıca paylaşılmadı | İlk bağlantıda WhatsApp ve bilekliği bul; Bluetooth 30 sn'den uzun kapat/aç sonrası yeni WhatsApp ve bulma; Bugün ekranını aşağı çekme | Kullanıcı istenen üç adımın da tamam olduğunu bildirdi. Bu bildirim ilk ve yeniden kurulan bağlantıda işlevlerin saatte çalıştığı ve boş sağlık eşitleme göstergesinin dönmediği yönünde kabul edildi. Ayrı zaman damgası veya ekran videosu verilmedi. | [CI build 173](https://github.com/etiyar/ls05-controller/actions/runs/44401797442) test ve derleme başarılı. Bu cihaz turuna ait yeni maskeli log paylaşılmadı; bağlantı ve komut aşamalarına ilişkin ayrıca log kanıtı yok. | **GEÇTİ (kullanıcı bildirimi, tek test turu): fiziksel bildirim/bulma ve boş sağlık yenileme düzeltmesi. Uzun oturum ve beklenmedik yeniden doğrulama doğrulanmadı.** |
| 19.11.2026 | `0.13.1-beta1 · build 173 · run 44401797442-1 · c497121` | Bluetooth kapat/aç, ikinci tur | Kullanıcı Bluetooth dönüşünde işlevlerin çalışmadığını, Ayarlar > Saat bağlantısı > Bağlan düğmesine basınca düzeldiğini bildirdi. | Bluetooth yaklaşık 12 sn kapalı kaldı. İlk oturumda 00:34:22'de yeni 1/26 görüldü; otomatik ikinci bağlantı 00:34:24'te şifreli pil ve cihaz bilgisi yanıtı aldı. Test bildirimi ve bulma ACK aldı. LS05 servis keşfi sırasında bırakıldı. | **BAŞARISIZ: tekrarlanan cihaz denemesi.** |
| 19.11.2026 | `0.13.1-beta1 · build 175 · run 44404281076-1 · b5843ec · test/band8-reauth-identity` | Notify kaldırıldıktan sonra Band 8 bildirimleri | Bildirimler `ACK` aldı fakat bileklikte görünmedi. | Logda başka bir istemcinin izleri sürdü. 23:26:51.553'te bizim auth'umuzdan önce, bağlantıdan 60 ms sonra istenmemiş bir 1/27 geldi. 23:29:44 ve 23:29:55'te bizim kuyruğumuz boşken 0,6 sn arayla üçlü `ACK` geldi. 23:30:44'ten sonra her bildirimin ardından `READY` ve `ALL_RECEIVED` geldi. Bunları biz istemedik. | **BAŞARISIZ: bantla ikinci bir istemci (Mi Fitness) aynı anda konuşuyordu** |
| 19.11.2026 | `0.13.1-beta1 · build 175 · run 44404281076-1 · b5843ec · test/band8-reauth-identity` | Mi Fitness kaldırıldıktan sonra dört Bluetooth kapat/aç turu ve WhatsApp bildirimleri | Kullanıcı bildirimlerin bileklikte göründüğünü doğruladı. | 23:35–23:50 arasında dört Bluetooth kapat/aç turu. Her turda tek ve temiz auth. 12 WhatsApp bildirimi gönderildi. Başka istemci izi hiç yok. | **GEÇTİ (kullanıcı bildirimi). #16 kök nedeni: ikinci istemci (önce Notify, sonra Mi Fitness). Band 8 kullanılırken bu uygulamalar kaldırılmalı ya da Bluetooth izni kapatılmalı.** |
| 20.11.2026 | `0.15.0-alpha1 · build 182 · run 44619800678-1 · 562dea1 · dev/v0.13.1-band8-daily` | Band 8 sağlık dosyası çekme, arşiv ve dışa aktarma | Kullanıcı zip'i dışa aktardı. Arşiv dışarıda çözüldü ve bandın uyku ve adım ekranıyla eşleştirildi (değerler kaydedilmedi). | 11 dosya alındı, hepsinin CRC'si tuttu, arşivlendi ve zip'e yazıldı. 8/5 silme gönderilmedi. | **GEÇTİ** |
| 20.11.2026 | `0.15.0-alpha1 · build 182 · run 44619800678-1 · 562dea1 · dev/v0.13.1-band8-daily` | Test bildirimi, bilekliği bul, cevapsız arama (7/0, "phone" paketi), rahatsız etme aç/kapat (2/23) | Kullanıcı dördünün de bantta çalıştığını bildirdi. | Komutlar `ACK` aldı. | **GEÇTİ (kullanıcı bildirimi)** |
| 20.11.2026 | `0.15.0-alpha1 · build 182 · run 44619800678-1 · 562dea1 · dev/v0.13.1-band8-daily` | Test alarmı oluşturma (17/1) | Alarmlar çaldı. Kullanıcı düğmeye birkaç kez bastı; yaklaşık 7 kopya alarm oluştu. | 17/1 yanıtında kimlik yoktu ("kimlik gelmedi"). Liste okununca "test alarmı yok" yazdı; düzenle ve sil bu yüzden hiç hedef bulamadı. | **KISMEN GEÇTİ: oluşturma çalıştı; kimlik bulma, düzenleme ve silme BAŞARISIZ. Kopyaları önlemek için düğme bekleme süresi gerekli** |
| 20.11.2026 | `0.15.0-alpha1 · build 182 · run 44619800678-1 · 562dea1 · dev/v0.13.1-band8-daily` | Müzik bilgisi (18/1) | Bantta bir şey görünmedi. | `ACK` alındı. | **BAŞARISIZ (görünür sonuç yok)** |
| 20.11.2026 | `0.15.0-alpha1 · build 182 · run 44619800678-1 · 562dea1 · dev/v0.13.1-band8-daily` | Hava durumu: konum ekle (10/7), anlık (10/0), tahmin (10/1) | Kullanıcı bantta hava ekranı bulamadı. | 10/7 yanıtı durum 0; 10/0 ve 10/1 `ACK` aldı. | **DOĞRULANMADI (görünür sonuç yok)** |
| 20.11.2026 | `0.15.0-alpha1 · build 182 · run 44619800678-1 · 562dea1 · dev/v0.13.1-band8-daily` | Hareketsizlik uyarısı (8/12, 8/13) | Denenmedi. | Yok. | **TEST EDİLMEDİ** |
| 20.11.2026 | `0.15.0-beta1 · build 184 · run 44673877183-1 · cfc41b3 · dev/v0.13.1-band8-daily` | Bugün ekranında Band 8 uyku ve adım, LS05'e geçince LS05 görünümü, CSV dışa aktarma | Kullanıcı test adımlarının 4. adıma kadar sorunsuz olduğunu bildirdi. | Ayrıca log paylaşılmadı. | **GEÇTİ (kullanıcı bildirimi)** |
| 20.11.2026 | `0.15.0-beta1 · build 184 · run 44673877183-1 · cfc41b3 · dev/v0.13.1-band8-daily` | Gerçek cevapsız arama ve çalan gelen arama | Cevapsız arama bantta göründü. Çalan gelen arama bantta görünmedi. | Bu sürümde Band 8 için otomatik gelen arama yolu yoktu (beklenen). | **Cevapsız arama GEÇTİ; gelen arama UYGULANMADI** |
| 20.11.2026 | `0.15.0-beta1 · build 184 · run 44673877183-1 · cfc41b3 · dev/v0.13.1-band8-daily` | Rahatsız etme şimdi aç/kapat (2/23) ve zamanlama (17/9) | Kullanıcı sorun bildirmedi. | Yanıtlar durum 0. | **GEÇTİ (log ve kullanıcı bildirimi)** |
| 20.11.2026 | `0.15.0-beta1 · build 184 · run 44673877183-1 · cfc41b3 · dev/v0.13.1-band8-daily` | Test alarmı kurma ve düzenleme | Alarm kurulan saatte çaldı. Düzenleme alarmı değiştirmedi. | Neden logla ayrıştırılmadı. beta2 listedeki alarmın alan biçimini ve düzenleme yanıtını logluyor. | **Kurma GEÇTİ; düzenleme BAŞARISIZ** |
| 20.11.2026 | `0.15.0-beta1 · build 184 · run 44673877183-1 · cfc41b3 · dev/v0.13.1-band8-daily` | WhatsApp tepki bildirimi | Bantta tepki metni emojisiz göründü. | LS05 yazı tipi için olan metin temizleyicisi emojileri siliyordu. | **BAŞARISIZ: emoji kayboluyor (beta2'de Band 8 için ayrı temizleyici)** |
| 20.11.2026 | `0.15.0-beta2 · build 188 · run 44758261913-1 · 5f1a5cd · dev/v0.13.1-band8-daily` | Emoji testi (❤️ 👍 😂 😮 😢 🙏) | Bantta altı boş kutucuk göründü. | Bildirim `ACK` aldı. | **BAŞARISIZ: bandın yazı tipi emoji çizemiyor; beta3'te emoji kısa metne dönüyor** |
| 20.11.2026 | `0.15.0-beta2 · build 188 · run 44758261913-1 · 5f1a5cd · dev/v0.13.1-band8-daily` | Gerçek gelen arama ekranı ve bantta reddet | Kullanıcı arama ekranının bantta açıldığını ve bandtan reddetmenin çalıştığını bildirdi. | Ayrıca log paylaşılmadı. | **GEÇTİ (kullanıcı bildirimi)** |
| 20.11.2026 | `0.15.0-beta2 · build 188 · run 44758261913-1 · 5f1a5cd · dev/v0.13.1-band8-daily` | Müzik (18/1 test şarkısı) | Bantta bir şey görünmedi. | 18/1 `ACK` aldı. Band müzik ekranı açılınca ve tuşlara basınca şifreli 18/0 gönderdi (18:03, 18:04, 18:07). | **BAŞARISIZ: band 18/0 ile istiyor; beta3 isteğe yanıt veriyor ve alan biçimini logluyor** |
| 20.11.2026 | `0.15.0-beta2 · build 188 · run 44758261913-1 · 5f1a5cd · dev/v0.13.1-band8-daily` (kullanıcıya göre build 184'te de) | Test alarmını düzenleme (17/2) | Bandın alarm saati "Test alarmını düzenle" ile değişti. Önceki "düzenlemiyor" bildirimi yanlış anlamaydı (kullanıcı elle düzenlemeyi bekliyordu). | Ayrıca log paylaşılmadı. | **GEÇTİ (kullanıcı bildirimi): alarm kurma, düzenleme ve silme doğrulandı** |
| 20.11.2026 | `0.15.0-beta3 · build 191 · run 44763729905-1 · dc9e283 · dev/v0.13.1-band8-daily` | Band 8 müzik bilgisi ve tuşlar | Müzik bilgisi bantta göründü; çal/duraklat, sonraki ve önceki çalıştı. Ses ayarı telefonu en yükseğe ya da sıfıra götürdü. | Band ses ekranında 18/2 gönderdi, Music alanları `1v 2v`. | **GEÇTİ: bilgi ve tuşlar; BAŞARISIZ: ses düzeyi (rc1'de düzeltme)** |
| 20.11.2026 | `0.15.0-beta3 · build 191 · run 44763729905-1 · dc9e283 · dev/v0.13.1-band8-daily` | Band 8 titreşim deseni testi (2/59) | Kısa-kısa-uzun titreşim hissedildi. | Komut `ACK` aldı. | **GEÇTİ (kullanıcı bildirimi)** |
| 20.11.2026 | `0.15.0-beta3 · build 191 · run 44763729905-1 · dc9e283 · dev/v0.13.1-band8-daily` | Bildirim kuralları | 1. kurala uyan bildirim geçti, 2. kurala uyan engellendi. | Logda kural kararları. | **GEÇTİ (kullanıcı bildirimi ve log)** |
| 20.11.2026 | `0.15.0-beta3 · build 191 · run 44763729905-1 · dc9e283 · dev/v0.13.1-band8-daily` | Emoji → metin | Bantta emoji yerine kısa metin göründü. | | **GEÇTİ (kullanıcı bildirimi)** |
| 20.11.2026 | `0.15.0-beta3 · build 191 · run 44763729905-1 · dc9e283 · dev/v0.13.1-band8-daily` | Google Maps navigasyonu | Bantta hiçbir şey görünmedi. | 18:59:33'te iki kez "Navigasyon yönergesi iletime geçti"; ardından Band 8 komutu kuyruğa girmedi. Neden: Band 8 yolunda uygulama filtresi Maps'i sessizce atıyordu. | **BAŞARISIZ (rc1'de düzeltme)** |
| 20.11.2026 | `0.15.0-rc1 · build 197 · run 44772756454-1 · 90c2422 · dev/v0.13.1-band8-daily` | Özel hatırlatıcılar | Hatırlatıcı zamanında saate geldi. |  | **GEÇTİ (kullanıcı bildirimi)** |
| 20.11.2026 | `0.15.0-rc1 · build 197 · run 44772756454-1 · 90c2422 · dev/v0.13.1-band8-daily` | Gelen arama (gerçek arama) | Arama bantta göründü; telefonda açılınca bant ekranı kapandı. | Arama ve kapanma komutları logda. | **GEÇTİ (kullanıcı bildirimi ve log)** |
| 20.11.2026 | `0.15.0-rc1 · build 197 · run 44772756454-1 · 90c2422 · dev/v0.13.1-band8-daily` | WhatsApp ve okunana kadar tekrar | WhatsApp bildirimi geldi, kurala göre okunana kadar yinelendi. | Logda tekrar gönderimleri. | **GEÇTİ (kullanıcı bildirimi ve log)** |
| 20.11.2026 | `0.15.0-rc1 · build 197 · run 44772756454-1 · 90c2422 · dev/v0.13.1-band8-daily` | Band 8 hareketsizlik ayarı okuma | Kart bandın ayarını gösterdi. | Ayar alan biçimi `1v 2m 3m 4v 6m 7m`. | **GEÇTİ (okuma; yazma ayrıca denenecek)** |
| 20.11.2026 | `0.15.0-rc1 · build 197 · run 44772756454-1 · 90c2422 · dev/v0.13.1-band8-daily` | Takvim hatırlatması | Etkinlik doğru saatte bantta göründü. Google Takvim uygulamasının kendi bildirimi de geldi, aynı etkinlik iki kez göründü. | Logda takvim gönderimi ve takvim uygulamasının bildirimi. | **KISMEN GEÇTİ (zamanlama doğru; çift bildirim rc2'de düzeltme)** |
| 20.11.2026 | `0.15.0-rc1 · build 197 · run 44772756454-1 · 90c2422 · dev/v0.13.1-band8-daily` | Band 8 müzik sesi | Ses yine en yükseğe ya da sıfıra sıçradı. | Ses artırınca "adım 150/150", azaltınca "adım 0/150"; sırayla. Bandın gönderdiği değer yüzde gibi yorumlanınca uçlara gidiyor. | **BAŞARISIZ (rc2'de ham değer logu ve çentik yedeği)** |
| 20.11.2026 | `0.15.0-rc1 · build 197 · run 44772756454-1 · 90c2422 · dev/v0.13.1-band8-daily` | Google Maps navigasyonu | Yönerge Band 8'e gitti ama düz bildirim olarak: genel mesaj simgesi, metin baştan kesik. | Logda "Navigasyon yönergesi iletime geçti" ve Band 8 gönderimi. | **KISMEN GEÇTİ (iletim çalışıyor; görünüm rc2'de düzeltme)** |

## 18 Kasım 2026 | Band 8 uzun oturum, 19:50–20:36

Kullanıcı bu oturumda Band 8'in iyi çalıştığını ve bildirimlerin geldiğini bildirdi. 19:50:35–19:50:38 bilekliği bul başlat/durdur komutları `ACK` aldı. 19:50:50–20:36:12 arasında birçok WhatsApp `MESSAGE` bildirimi algılandı, Band 8 kuyruğuna alındı, BLE yazımı kabul gördü ve protokol `ACK` aldı. Grup özeti ve tekrar filtresi atlamaları beklenendi. Paylaşılan aralıkta yeni auth, şifre çözme hatası veya oturum yenileme satırı yok. **Sonuç: bu oturumda kullanıcı gözlemine göre çalıştı.** Hangi APK'nın yüklü olduğu ve [PR #18](https://github.com/etiyar/ls05-controller/pull/18) içindeki beklenmedik auth sonrası toparlanma yolu bu kesitle doğrulanmaz. [#16](https://github.com/etiyar/ls05-controller/issues/16) açık kalır.

## 18 Kasım 2026 | Band 8 bildirim kısa oturum tekrarı (17:55)

Kullanıcı 17:55:39'da Band 8'i etkinleştirdi. 17:55:43'te cihaz bilgisi okundu. 17:55:59'da WhatsApp bildirimi dinleyicide algılandı (`Android anahtarı=true`), Band 8 için `tür=7, altTür=0` komutu kuyruğa girdi, BLE yazımı kabul gördü ve 17:55:59.482'de `ACK` ile bekleyen komut 1→0 oldu. Kullanıcı bildirimin **Band 8 ekranında göründüğünü** doğruladı. Grup özeti beklendiği gibi atlandı. **Sonuç: GEÇTİ (tek bildirim, yeni oturum).** Önceki oturumdaki `ACK` alıp bileklikte tepki oluşturmayan komutlar ve birkaç dakika sonra kayıp bildirim şikâyeti açıklanmadı. Aynı oturumda süreli tekrar kabulü henüz yapılmadı. [#16](https://github.com/etiyar/ls05-controller/issues/16)

Kaynak: kullanıcının doğrudan bildirdiği denemeler. CI derlemesi burada ayrıca belirtilir; gerçek cihazda denenmemiş bir işlevi başarılı saymayız.

## 18 Kasım 2026 | Band 8 elle arama ekranı denemesi (PR #13 birleşti)

Kullanıcı arama ekranını açmanın ve uygulamadaki **Kapat** düğmesiyle kapatmanın bileklikte çalıştığını bildirdi. Elle kapatma için yeni zaman damgalı log paylaşılmadı; bu sonucun kanıtı kullanıcı gözlemidir. Önceki logda 14:16:02 ve 14:16:21'deki `7/0` açma istekleri protokol `ACK` aldı. Her iki denemede 15 saniyelik güvenlik süresi sonunda `7/1` kapatma isteği gönderildi ve `ACK` alındı (14:16:17, 14:16:36); kullanıcı sorun bildirmedi. 14:16:07'de bileklikten şifreli `7/2` komutu geldi; bunun reddetme eylemi olup olmadığı doğrulanmadı. `ACK` fiziksel ekran durumunun tek başına kanıtı değildir. [PR #13](https://github.com/etiyar/ls05-controller/pull/13) geliştirme dalına alındı. Gerçek gelen arama, aramayı saatten reddetme veya cevaplama, cevapsız arama ve müzik henüz test edilmedi.

## 18 Kasım 2026 | Son dayanıklılık kabulü

Kullanıcı, önceki turda istenen **telefon kilitliyken bildirim**, **Bluetooth kapat/aç sonrası Band 8 bildirimi ve saati bul**, **LS05 bildirim kontrolü** testlerinin tümünün başarılı olduğunu bildirdi. Bu tur için ayrı zaman damgalı log veya test başına ayrıntı paylaşılmadı; sonuç kullanıcı gözlemine dayanır. Önceki Band 8 bildirim ve bilekliği bul denemelerinin komut/ACK kanıtı aşağıdadır. Eski başarısızlığın kök nedeni belirlenmedi. Kapalı uygulama filtresi ve uygulamayı elle açmadan telefon başlangıcında otomatik bağlantı bu mesajla ayrıca doğrulanmış sayılmaz.

## 18 Kasım 2026 | Band 8 tanılama APK'sı ile tekrar test

| Deneme | Sonuç | Kanıt ve sınır |
|---|---|---|
| Bilekliği bul, bileklikten durdur | **GEÇTİ (kullanıcı bildirimi)** | 12:19:04 başlatma gönderildi ve protokol `ACK` ile kuyruk sıfırlandı. Kullanıcı bileklikten durdurduğunu ve işlevin çalıştığını bildirdi. Bileklikten durdurma için ayrı bir gelen komut bu logda yok. |
| Bilekliği bul, uygulamadan durdur | **GEÇTİ (kullanıcı bildirimi)** | 12:19:15 başlatma ve 12:19:17 durdurma istekleri ayrı `ACK` aldı, kuyruk sıfırlandı. Kullanıcı titreşimin durduğunu bildirdi. |
| Notification Test bildirimi | **GEÇTİ (kullanıcı bildirimi)** | 12:19:32 `MESSAGE` dinleyiciden geçti, `tür=7, altTür=0` gönderildi ve `ACK` alındı. Kullanıcı saatte gördüğünü bildirdi. |
| WhatsApp bildirimi | **GEÇTİ (kullanıcı bildirimi)** | 12:19:56 `MESSAGE` dinleyiciden geçti, `tür=7, altTür=0` gönderildi ve `ACK` alındı. Kullanıcı saatte gördüğünü bildirdi. Grup özeti beklenen şekilde atlandı. |

Bu tekrar testi [tanılama PR #5](https://github.com/etiyar/ls05-controller/pull/5) APK'sıyla yapıldı. Kod yalnızca olay logları ekledi; önceki başarısızlığın kök nedeni belirlenmedi. Bu oturumda simge isteği (`tür=7, altTür=15`), kuyruk takılması veya zaman aşımı görünmedi. Ekran kapalı ve yeniden bağlanma sonrası tekrarlama, kapalı uygulama filtresi ve LS05 gerileme kontrolü henüz yapılmadı. `ACK`, fiziksel görüntüleme kanıtı değildir; saatte görünme kullanıcı gözlemidir.

## 18 Kasım 2026 | Band 8 bildirim ve saati bul denemesi

| Deneme | Sonuç | Kanıt ve sınır |
|---|---|---|
| WhatsApp bildirimi | **BAŞARISIZ** | Kullanıcı uygulama iletimini açıp denedi; bileklikte bildirim görünmedi. Bu denemenin filtre, BLE yazımı veya protokol onayı logu paylaşılmadı. [#2](https://github.com/etiyar/ls05-controller/issues/2) |
| Bildirim testi uygulaması | **BAŞARISIZ** | Kullanıcı uygulama ayarını açıp denedi; bileklikte bildirim görünmedi. Telefon bildirim dinleyicisine ulaşıp ulaşmadığı henüz ayrıştırılmadı. [#2](https://github.com/etiyar/ls05-controller/issues/2) |
| Band 8 saati bul: başlatma | **BAŞARISIZ** | Ayarlar ekranı `Bağlı` gösterirken `Band 8 komutu gönderilemedi. Biraz bekleyip tekrar dene.` uyarısı çıktı. Bu uyarı komutun yerel koşullarca reddedildiğini gösterir; cihaz ACK'i veya titreşim kanıtı değildir. [#3](https://github.com/etiyar/ls05-controller/issues/3) |

Önceki sürümde bileklikten **telefonu bul** denemesinde telefonun titremesi, telefondan **bilekliği bul** komutunun çalıştığını göstermez. Aynı BLE hattı arızası olasıdır, fakat ortak kök neden henüz kanıtlanmadı.

## 18 Kasım 2026 | Tek satırlı saat kaydı düzeltmesi

| Deneme | Sonuç | Kanıt ve sınır |
|---|---|---|
| Bozuk listeden Band 8 kurtarma ve etkin seçim | **GEÇTİ** | Kurtarma sonrası ana kayıt ve yedek geçerliydi, iki saat vardı, Band 8 etkin ve anahtar zarfı mevcuttu. |
| Önceki çok satırlı kayıtla uygulamayı zorla durdurup açma | **BAŞARISIZ** | 08:51'de geçerli okunan iki kopya, 08:52 açılışında `CORRUPT` göründü. Kesin kök neden doğrulanmadı. |
| Tek satırlı kodlanmış kayıtla uygulamayı yeniden açma | **GEÇTİ** | 09:48 açılışında `PRIMARY`, iki saat, Band 8 etkin, adres ve anahtar zarfı mevcut, ana ve yedek kayıt geçerli (her biri 147 karakter). Band 8 cihaz bilgisi okundu. Kullanıcı ikinci durdur/aç denemesinde de sorun olmadığını bildirdi. |
| Telefonun tamamen yeniden başlatılması | **GEÇTİ (kullanıcı bildirimi)** | Kullanıcı yeniden başlatma sonrası sorun olmadığını bildirdi. Uygulamayı elle açmadan otomatik bağlantı zamanlaması ayrıca doğrulanmadı. |

Band 8 sağlık eşitlemesi, bildirimlerin saatte görünmesi ve saati bulma işlevi bu logla doğrulanmadı.

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
