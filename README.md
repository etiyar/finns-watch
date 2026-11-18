# Finn's Watch

Haylou Solar LS05 ve Xiaomi Smart Band 8 için geliştirilen açık kaynak Android uygulaması. Saatlerle yerel Bluetooth bağlantısı kurar; resmî üretici uygulaması değildir.

## Sürümler ve destek

**`main`: v0.12.0.** LS05 bağlantısı, bildirimler, aramalar, alarmlar, rahatsız etmeme, sağlık geçmişi, cihaz bulma ve kadran aktarımı bu sürümün temel işlevleridir. Çoklu saat altyapısı vardır. Xiaomi Smart Band 8 için günlük kullanım desteği bu kararlı sürümün kapsamı değildir.

**[Geliştirme dalı: v0.13.1-beta1](https://github.com/etiyar/ls05-controller/tree/dev/v0.13.1-band8-daily).** Band 8 bağlantısı, pil/cihaz bilgisi, WhatsApp ve test bildirimleri ile bilekliği bulma kullanıcı tarafından gerçek cihazda denendi. Gerçek gelen arama, müzik ve sağlık eşitlemesi tamamlanmadı. Bazı oturumlarda komut `ACK` almasına rağmen bileklikte tepki görülmedi; beklenmedik yeniden doğrulama [#16](https://github.com/etiyar/ls05-controller/issues/16) kapsamında inceleniyor. Bu dal kararlı sürüm olarak yayımlanmadı.

## APK ve kaynak kodu

[Releases](https://github.com/etiyar/ls05-controller/releases) sayfasındaki test derlemeleri ön sürümdür. APK'nın hangi commit'ten üretildiğini ve gerçek cihaz sonuçlarını [geliştirme test kayıtlarında](https://github.com/etiyar/ls05-controller/blob/dev/v0.13.1-band8-daily/docs/TEST_RESULTS.md) kontrol edin. GitHub Actions geçmesi, bir özelliğin bileklikte çalıştığını tek başına kanıtlamaz.

Yerel geliştirme için JDK 17 ve Android SDK gerekir:

```bash
./gradlew :protocol:test
./gradlew :app:assembleDebug
```

Paket kimliği `com.che.ls05` korunur. Mevcut uygulamanın üzerine kurulum ve veri koruma henüz [yayın kabulünde](https://github.com/etiyar/ls05-controller/issues/8) sınanmaktadır; saati sıfırlamayın veya eşleşmesini silmeyin.

## Proje belgeleri

- [Güncel plan ve açık işler](https://github.com/etiyar/ls05-controller/blob/dev/v0.13.1-band8-daily/docs/PLAN.md)
- [Gerçek cihaz testleri](https://github.com/etiyar/ls05-controller/blob/dev/v0.13.1-band8-daily/docs/TEST_RESULTS.md)
- [Sürüm geçmişi](https://github.com/etiyar/ls05-controller/blob/dev/v0.13.1-band8-daily/CHANGELOG.md)

Saat kimlik anahtarları, tam Bluetooth adresleri, bildirim içerikleri ve kişisel sağlık kayıtları depoya eklenmez. LS05 ve Band 8 sağlık verileri cihaz kaynağına göre ayrı tutulur.
