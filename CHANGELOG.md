# Finn's Watch | Değişiklik günlüğü

Bu dosya kullanıcıya görünen sürüm değişikliklerini kaydeder. Teknik ayrıntılar için Git commit geçmişi, cihaz denemeleri için `docs/TEST_RESULTS.md`, yol haritası için `docs/PLAN.md` kullanılır.

Durum tanımları: **Derlendi** = CI testi ve APK derlemesi başarılı. **Cihazda doğrulandı** = gerçek cihaz denemesi kullanıcı tarafından bildirildi. İkisi aynı şey değildir.

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
- **Henüz doğrulanmadı:** Otomatik yeniden bağlanma, Android yeniden başlatma, iki saat arasında tekrarlı geçiş, taramayla Band 8 bulma, kadran uyarısının düzeldiğinin yeni APK üzerinde teyidi.
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
