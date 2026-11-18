# Finn's Watch | Ajan çalışma düzeni

## Tek kaynak ve güncel durum

- Güncel geliştirme tabanı `dev/v0.13.1-band8-daily`; `main` kararlı sürüm içindir. İşe başlarken uzak dalın güncel commit'ini ve açık PR/issue listesini kontrol et. Eski yerel kopyaya güvenme.
- Açık hata ve kabul ölçütü GitHub issue'da; cihazda gerçekten gözlenen sonuç `docs/TEST_RESULTS.md` içinde; sıradaki sıra `docs/PLAN.md` içinde; yayınlanan değişiklik `CHANGELOG.md` içinde. Aynı bilgiyi başka devir belgesine çoğaltma.
- Son cihaz kanıtı: Band 8 kaydı uygulama yeniden açma ve telefon yeniden başlatmada korundu. WhatsApp ve test bildirimleri, bilekliği bul başlatma ve iki durdurma yolu çalıştı. Kullanıcı ayrıca ekran kilitliyken bildirim, Bluetooth kapat/aç sonrası bildirim ve bulma, LS05 bildirim kontrolü testlerinin tümünün başarılı olduğunu bildirdi. Önceki başarısızlığın kök nedeni bilinmiyor; kapalı uygulama filtresi ve uygulamayı elle açmadan telefon başlangıcı ayrı doğrulanacak (`docs/PLAN.md`).

## İş alma ve devretme

1. Açık issue'yu seç. Üzerine hangi ajan/dalın çalıştığını ve son doğrulanmış adımı kısa yorumla yaz. Aynı dosyaları iki ajan eşzamanlı değiştirmesin.
2. Son `dev/v0.13.1-band8-daily` commit'inden konu dalı aç. Her değişiklik için PR aç ve ilgili issue'yu bağla. PR'da gözlem, yapılan değişiklik, CI sonucu, gerçek cihaz sonucu ve kalan risk ayrı yazılsın.
3. Ajan değişiminde issue veya PR'ye son commit SHA'sını, devam edilecek somut adımı, engeli ve gereken maskeli cihaz logunu yaz. Yeni ajan buradan ve güncel daldan devam etsin.
4. CI geçmesi cihaz testinin yerine geçmez. Cihaz başarısı kullanıcı tarafından bildirilmeden issue'yu kapatma, test sonucunu `GEÇTİ` yapma veya Band 8 gündelik özelliklerini `main` dalına taşıma.
5. PR birleşince dalı ve dokümanları güncelle. Bir sonraki ajan birleşmiş geliştirme dalını temel alsın; açık PR'daki işi kopyalamasın.

## Gizlilik ve kapsam

Tam Bluetooth adresi, kimlik/şifreleme anahtarı, bildirim başlığı veya içeriği, kişisel sağlık kaydı issue, PR, commit ve loga girmez. Maskeli olay kodları ve sayılar yeterlidir. LS05 ve Band 8 sağlık verileri cihaz kaynağına göre ayrı kalır; adımlar toplanmaz.
