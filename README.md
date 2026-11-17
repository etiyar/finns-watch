# Finn's Watch

**Haylou Solar LS05 ve Xiaomi Smart Band 8 için geliştirilen, yerel Bluetooth bağlantısını esas alan açık kaynak Android uygulaması.**

Finn's Watch, birden fazla saati tek uygulamadan yönetmeyi hedefleyen bağımsız bir projedir. LS05 için mevcut günlük kullanım işlevlerini sürdürürken Xiaomi Smart Band 8 desteği aşamalı olarak geliştirilmektedir. Bulut hesabı veya üreticinin uygulaması, Finn's Watch'ın temel LS05 işlevleri için gerekli değildir.

## Proje durumu

| Bileşen | Durum | Açıklama |
| --- | --- | --- |
| Haylou Solar LS05 | Kullanımda | Bağlantı, bildirim, arama, alarm, DND, sağlık geçmişi, cihaz bulma ve kadran aktarımı için mevcut işlevler |
| Xiaomi Smart Band 8 | **Beta** | Bluetooth bağlantısı, kimlik doğrulama, pil yüzdesi ve firmware bilgisi gerçek cihazda doğrulandı |
| Çoklu saat yönetimi | **Beta** | Etkin saat değiştirme ve Bluetooth kapat/aç sonrasında yeniden bağlantı kullanıcı testinde çalıştı |
| Band 8 günlük özellikleri | Geliştirmede | Bildirim, arama, müzik, sağlık senkronizasyonu ve saat ayarları henüz tamamlanmadı |

**Sürümler:** `main` üzerinde **v0.12.0** bulunur. **v0.13.0-beta6**, [ayrı geliştirme dalındadır](https://github.com/etiyar/ls05-controller/tree/dev/v0.13-band8-connection); kararlı sürüm olarak yayımlanmamıştır.

> **Bilinen açık sorun:** Band 8 için bir test oturumunda doğrulama durdu ve uygulama yeniden açıldığında saat kaydı listede görünmedi. Koruyucu kod değişiklikleri geliştirme dalına eklendi; kesin kök neden ve gerçek cihaz testi bekleniyor. Sağlık verileri Band 8 için henüz eşitlenmediğinden ilgili ekranda veri görünmemesi bu aşamada normaldir.

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

Band 8 için LS05 ile aynı özellik kapsamı **henüz sunulmaz**. Destek yalnızca gerçek cihaz testleriyle doğrulandıkça genişletilir.

## Kurulum ve geliştirme

Bu depo Android kaynak kodunu ve GitHub Actions testlerini içerir. Deneme APK'ları [Actions](https://github.com/etiyar/ls05-controller/actions) çıktılarından alınabilir. Beta sürümler yalnızca ilgili geliştirme dalından derlenir.

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

Güncel belgeler [v0.13 geliştirme dalında](https://github.com/etiyar/ls05-controller/tree/dev/v0.13-band8-connection) tutulur:

- [CHANGELOG.md](https://github.com/etiyar/ls05-controller/blob/dev/v0.13-band8-connection/CHANGELOG.md): Sürüm değişiklikleri.
- [TEST_RESULTS.md](https://github.com/etiyar/ls05-controller/blob/dev/v0.13-band8-connection/docs/TEST_RESULTS.md): Gerçek cihaz ve CI test sonuçları.
- [PLAN.md](https://github.com/etiyar/ls05-controller/blob/dev/v0.13-band8-connection/docs/PLAN.md): Mevcut durum, açık sorunlar ve sonraki adımlar.

CI derlemesinin geçmesi, ilgili özelliğin gerçek saatte doğrulandığı anlamına gelmez. Bu iki durum ayrı takip edilir.

## Gizlilik ve güvenlik

Saat bağlantıları yerel Bluetooth üzerinden yürütülür. Kimlik anahtarları kaynak koduna veya GitHub'a eklenmez. Tam Bluetooth adresleri, kişisel sağlık kayıtları ve ham cihaz günlükleri sürüm belgelerine yazılmaz. Uygulama geliştirme aşamasındadır; özellikle beta özellikleri için veri saklama ve geri yükleme henüz tam olarak doğrulanmamıştır.

**Not:** Finn's Watch bağımsız bir topluluk/kişisel geliştirme projesidir. Haylou veya Xiaomi tarafından yayımlanan resmî bir uygulama değildir.
