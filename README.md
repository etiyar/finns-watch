# Finn's Watch

**Haylou Solar LS05 ve Xiaomi Smart Band 8 için geliştirilen, yerel Bluetooth bağlantısını esas alan açık kaynak Android uygulaması.**

Finn's Watch, birden fazla saati tek uygulamadan yönetmeyi hedefleyen bağımsız bir projedir. LS05 için mevcut günlük kullanım işlevlerini sürdürürken Xiaomi Smart Band 8 desteği aşamalı olarak geliştirilmektedir. Bulut hesabı veya üreticinin uygulaması, Finn's Watch'ın temel LS05 işlevleri için gerekli değildir.

## Proje durumu

| Bileşen | Durum | Açıklama |
| --- | --- | --- |
| Haylou Solar LS05 | Kullanımda | Bağlantı, bildirim, arama, alarm, DND, sağlık geçmişi, cihaz bulma ve kadran aktarımı için mevcut işlevler |
| Xiaomi Smart Band 8 | **Beta** | Bağlantı, doğrulama, pil ve cihaz bilgisi, normal bildirim ve bilekliği bul gerçek cihazda denendi |
| Çoklu saat yönetimi | **Beta** | Etkin saat değiştirme, kayıt kalıcılığı ve Bluetooth kapat/aç sonrasında yeniden bağlantı kullanıcı testinde çalıştı |
| Band 8 günlük özellikleri | Kısmi | WhatsApp ve test bildirimi ile bilekliği bul doğrulandı. Sağlık dosyaları arşivlenir ama değerler gösterilmez. Arama, müzik ve saat ayarları yalnızca Gelişmiş'teki elle denemelerdedir |

**Sürümler:** `main` üzerinde **v0.12.0** bulunur. **v0.13.1-beta1 sürüm adayı** [geliştirme dalında](https://github.com/etiyar/ls05-controller/tree/dev/v0.13.1-band8-daily) hazırlanır. Kurulum ve regresyon testi geçmeden kararlı sürüm olarak yayımlanmaz.

> **Bilinen sınırlar:** Band 8'e aynı anda Mi Fitness ya da Notify bağlıysa bildirimler `ACK` alır ama bileklikte görünmez ([#16](https://github.com/etiyar/ls05-controller/issues/16) kök nedeni). Bu uygulamaları kaldır ya da Bluetooth izinlerini kapat. Finn's Watch bunu fark edince uyarı gösterir ve en çok 5 dakikada bir yeniden bağlanır. Band 8 sağlık dosyaları yalnızca okunur ve telefonda arşivlenir; değerler henüz gösterilmez, bandan dosya silinmez. Band 8 ve LS05 emoji çizemez; yaygın emojiler kısa metne döner. Band 8 müzik, hava ve hareketsizlik uyarısı cihazda doğrulanmadı; Bildirim kuralları, navigasyon, VoIP arama ve telefon pili uyarıları (beta3), özel hatırlatıcılar, takvim hatırlatmaları ve Band 8 hareketsizlik kartı (beta4, deneysel) cihazda doğrulanmadı. LS05 hareketsizlik komutu (`14`) belgelerde orta güvenle var ama uygulanmadı. Kapalı uygulama filtresi ve telefon açılışında uygulama elle açılmadan bağlantı ayrıca doğrulanacak.

## Özellikler

**Haylou Solar LS05**
- Arka planda Bluetooth bağlantısı ve uygun olduğunda yeniden bağlanma.
- Türkçe bildirimler, gelen ve cevapsız arama bilgisi.
- Alarm, rahatsız etmeme, saat ve profil ayarları.
- Adım, nabız ve uyku geçmişi ile yerel grafikler.
- Saati bulma, son görülen konum ve desteklenen telefon kumandaları.
- Özel kadran yükleme ve kadran araçları.

**Xiaomi Smart Band 8 (beta)**
- FE95 üzerinden Bluetooth bağlantısı ve kimlik doğrulama.
- Pil yüzdesi ve firmware sürümünü okuma.
- Bluetooth taraması, kayıtlı cihazlardan seçim veya bilinen adresle bağlantı.
- Kimlik anahtarını Android Keystore aracılığıyla koruma.
- LS05 ile etkin saat arasında geçiş.
- Normal Android bildirimleri ve elle bilekliği bulma. WhatsApp, test bildirimi, iki durdurma yolu ve Bluetooth kapat/aç sonrası kullanım kullanıcı testinde çalıştı.
- Bağlıyken 6 saatte bir sağlık dosyalarını okuyup yerel arşive yazma ve Gelişmiş'ten tek zip olarak dışa aktarma (cihazda doğrulanmadı).
- Gelişmiş > Band 8 denemeleri: cevapsız arama, alarm, rahatsız etme, hava durumu, hareketsizlik uyarısı ve müzik için tek komutluk elle denemeler (cihazda doğrulanmadı).

Band 8 için LS05 ile aynı özellik kapsamı **henüz sunulmaz**. Destek yalnızca gerçek cihaz testleriyle doğrulandıkça genişletilir.

## Kurulum ve geliştirme

Bu depo Android kaynak kodunu ve GitHub Actions testlerini içerir. Telefondan indirilecek test adayları [Releases](https://github.com/etiyar/ls05-controller/releases) sayfasında açıkça adlandırılır; her CI derlemesi [Actions](https://github.com/etiyar/ls05-controller/actions) çıktısında kalır. Aday testinde Ayarlar > Hakkında satırındaki APK kimliği kaydedilir.

Yerel derleme için Android SDK ve JDK 17 gerekir:

```bash
./gradlew :protocol:test
./gradlew :app:assembleDebug
```

Paket kimliği `com.che.ls05` olarak korunmaktadır. **Mevcut uygulamayı kaldırmadan önce imza uyumluluğunu ve verilerin korunmasını doğrulayın.** Saat eşleşmesini silmek veya saati sıfırlamak, kurulumun bir parçası değildir.

## Mimari

- `protocol/`: Saat protokolleri, komutlar, ayrıştırıcılar, şifreleme ve birim testleri.
- `app/`: Android arayüzü, Bluetooth sürücüleri, saat yönetimi ve yerel veri depolama.
- `docs/`: Teknik incelemeler, mevcut yol haritası ve cihaz testleri.

Tasarım ilkesi, çalışan LS05 işlevlerini koruyarak saat başına ayrı sürücüler üzerinden ilerlemektir.

## Geliştirme ve test takibi

Güncel belgeler [v0.13.1 geliştirme dalında](https://github.com/etiyar/ls05-controller/tree/dev/v0.13.1-band8-daily) tutulur:

- [CHANGELOG.md](https://github.com/etiyar/ls05-controller/blob/dev/v0.13.1-band8-daily/CHANGELOG.md): Sürüm değişiklikleri.
- [TEST_RESULTS.md](https://github.com/etiyar/ls05-controller/blob/dev/v0.13.1-band8-daily/docs/TEST_RESULTS.md): Gerçek cihaz ve CI test sonuçları.
- [PLAN.md](https://github.com/etiyar/ls05-controller/blob/dev/v0.13.1-band8-daily/docs/PLAN.md): Güncel durum ve açık issue bağlantıları.

CI derlemesinin geçmesi, ilgili özelliğin gerçek saatte doğrulandığı anlamına gelmez. Bu iki durum ayrı takip edilir.

## Gizlilik ve güvenlik

Saat bağlantıları yerel Bluetooth üzerinden yürütülür. Kimlik anahtarları kaynak koduna veya GitHub'a eklenmez. Tam Bluetooth adresleri, kişisel sağlık kayıtları ve ham cihaz günlükleri sürüm belgelerine yazılmaz. Uygulama geliştirme aşamasındadır; özellikle beta özellikleri için veri saklama ve geri yükleme henüz tam olarak doğrulanmamıştır.

**Not:** Finn's Watch bağımsız bir topluluk/kişisel geliştirme projesidir. Haylou veya Xiaomi tarafından yayımlanan resmî bir uygulama değildir.
