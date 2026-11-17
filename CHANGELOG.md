# Finn's Watch | Değişiklik günlüğü

Bu dosya kullanıcıya görünen sürüm değişikliklerini kaydeder. Teknik ayrıntılar için Git commit geçmişi, cihaz denemeleri için `docs/TEST_RESULTS.md`, yol haritası için `docs/PLAN.md` kullanılır.

Durum tanımları: **Derlendi** = CI testi ve APK derlemesi başarılı. **Cihazda doğrulandı** = gerçek cihaz denemesi kullanıcı tarafından bildirildi. İkisi aynı şey değildir.

## v0.13.1-alpha1 | 18 Kasım 2026

- Band 8'e normal Android bildirimleri, mevcut uygulama filtresiyle iletilir. Türkçe metin ve kaynak uygulama bilgisi korunur. Cihaz testi bekliyor.
- Band 8 bağlıyken Gelişmiş ve log bölümünde elle başlatma ve durdurma denetimleri bulunur. Durdurma komutu ve bileklikten kapatma davranışı cihazda doğrulanmayı bekliyor.
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
