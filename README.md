# Finn's Watch

**Haylou Solar LS05 ve Xiaomi Smart Band 8 için geliştirilen, yerel Bluetooth bağlantısını esas alan açık kaynak Android uygulaması.**

Finn's Watch, birden fazla saati tek uygulamadan yönetmeyi hedefleyen bağımsız bir projedir. LS05 için mevcut günlük kullanım işlevlerini sürdürürken Xiaomi Smart Band 8 desteği aşamalı olarak geliştirilmektedir. Bulut hesabı veya üreticinin uygulaması, Finn's Watch'ın temel LS05 işlevleri için gerekli değildir.

## Proje durumu

| Bileşen | Durum | Açıklama |
| --- | --- | --- |
| Haylou Solar LS05 | Kullanımda | Bağlantı, bildirim, arama, alarm, DND, sağlık geçmişi, cihaz bulma ve kadran aktarımı |
| Xiaomi Smart Band 8 | Kullanımda | Bildirim, arama, alarm, DND, müzik, sağlık verisi, hatırlatıcılar; gerçek cihazda doğrulandı |
| Çoklu saat yönetimi | Kullanımda | Etkin saat değiştirme, birleşik sağlık grafikleri |

**Sürüm:** Kararlı sürüm **v0.15.0** ([Releases](https://github.com/etiyar/finns-watch/releases)). Ayrıntılar [CHANGELOG](CHANGELOG.md), cihaz kanıtları [TEST_RESULTS](docs/TEST_RESULTS.md) içinde.

> **Bilinen sınırlar:** Band 8'e Mi Fitness ya da Notify aynı anda bağlıysa komutlar `ACK` alır ama bileklikte işlenmez. Bu uygulamaları kaldır ya da Bluetooth izinlerini kapat; Finn's Watch bunu fark edince uyarır. Band 8 ve LS05 emoji çizemez; yaygın emojiler kısa metne döner. Band 8'de navigasyon düz bildirim olarak gösterilir, oklu ekran yoktur. Band 8 hava durumu ve kadran yükleme henüz yoktur. Band 8 hareketsizlik kartı deneyseldir.

## Özellikler

**Haylou Solar LS05**
- Arka planda Bluetooth bağlantısı ve yeniden bağlanma.
- Türkçe bildirimler, gelen ve cevapsız arama.
- Alarm (3 yuva, notlu), rahatsız etme, saat ve profil ayarları.
- Adım, nabız ve uyku geçmişi, yerel grafikler.
- Saati bulma, son görülen konum, telefon kumandaları.
- Özel kadran yükleme.

**Xiaomi Smart Band 8**
- FE95 üzerinden bağlantı ve kimlik doğrulama; anahtar Android Keystore'da.
- Bildirimler, gelen arama (banttan reddetme), cevapsız arama, bilekliği bul.
- Alarmlar (10 yuva), rahatsız etme ve zamanlaması.
- Müzik bilgisi, oynatma tuşları ve ses düzeyi.
- Uyku (derin, hafif, REM, uyanma, nabız), adım ve nabız; banddan dosya silinmez.

**İki saat için ortak**
- Bildirim kuralları: uygulama, kişi, kelime filtresi; iş profili; sessiz saatler; okunana kadar tekrar; titreşim.
- Google Maps navigasyon yönergeleri, WhatsApp/Telegram aramaları, telefon pili uyarısı.
- Hatırlatıcılar ve takvim hatırlatmaları.
- Birleşik sağlık grafikleri ve CSV dışa aktarma (kaynak sütunu).

## Kurulum ve geliştirme

Bu depo Android kaynak kodunu ve GitHub Actions testlerini içerir. Telefondan indirilecek test adayları [Releases](https://github.com/etiyar/finns-watch/releases) sayfasında açıkça adlandırılır; her CI derlemesi [Actions](https://github.com/etiyar/finns-watch/actions) çıktısında kalır. Aday testinde Ayarlar > Hakkında satırındaki APK kimliği kaydedilir.

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

Güncel belgeler [v0.13.1 geliştirme dalında](https://github.com/etiyar/finns-watch/tree/dev/v0.13.1-band8-daily) tutulur:

- [CHANGELOG.md](https://github.com/etiyar/finns-watch/blob/dev/v0.13.1-band8-daily/CHANGELOG.md): Sürüm değişiklikleri.
- [TEST_RESULTS.md](https://github.com/etiyar/finns-watch/blob/dev/v0.13.1-band8-daily/docs/TEST_RESULTS.md): Gerçek cihaz ve CI test sonuçları.
- [PLAN.md](https://github.com/etiyar/finns-watch/blob/dev/v0.13.1-band8-daily/docs/PLAN.md): Güncel durum ve açık issue bağlantıları.

CI derlemesinin geçmesi, ilgili özelliğin gerçek saatte doğrulandığı anlamına gelmez. Bu iki durum ayrı takip edilir.

## Gizlilik ve güvenlik

Saat bağlantıları yerel Bluetooth üzerinden yürütülür. Kimlik anahtarları kaynak koduna veya GitHub'a eklenmez. Tam Bluetooth adresleri, kişisel sağlık kayıtları ve ham cihaz günlükleri sürüm belgelerine yazılmaz. Uygulama geliştirme aşamasındadır; özellikle beta özellikleri için veri saklama ve geri yükleme henüz tam olarak doğrulanmamıştır.

**Not:** Finn's Watch bağımsız bir topluluk/kişisel geliştirme projesidir. Haylou veya Xiaomi tarafından yayımlanan resmî bir uygulama değildir.
