# Çoklu saat tasarımı (V12, Ajan B "Çekirdek", adım 1)

Bu belge yalnızca tasarımdır. Kod, derleme, test, sağlık verisi ve saat ayarları bu adımda değişmez.
Dayanak: `docs/V12_BAND8_PLAN.md` bölüm 8 (onaylı revizyon ve sağlık birleştirme kuralları).
Band 8 protokolü ayrı belgede (`docs/BAND8_PROTOKOL.md`, Ajan A). Bu belge saatten bağımsız çekirdeği anlatır.

Temel ilke: en az değişiklik. Var olan sınıflar genişletilir. Yeni Gradle modülü yok.
LS05 için ayar anahtarları, sağlık dosyaları ve davranış aynen kalır.

## 1. Bugünkü durum haritası

### 1.1 Sınıflar: LS05'e özel mi, ortak mı?

| Sınıf | Durum | Not |
|---|---|---|
| `Ls05Ble` | LS05'e özel | GATT, UTE kanalları, kimlik (userId) doğrulama, işlem kuyruğu, kadran kanalı. |
| `WatchService` | Karışık | Ön plan servisi, Bluetooth alıcısı, yeniden bağlanma ortak. `onFrame`, `onAuthResult` kodları, `pushSettings` (UTE komutu), `requestHistory`, saati bul, arama komutları LS05'e özel. |
| `WatchHub` | Çoğu ortak | Durum, log, kuyruk (`pending`), pil, firmware ortak. `frame(UteFrame)` ve `Observer.onFrame` LS05 çerçevesine bağlı. |
| `Prefs` | Karışık | Tek dosya `ls05`. Saate ait: `address`, `userId`, `profileVerified`, `lastSync`, `alarmNote*`, `alarmVisible*`, `reminderDay*`, `currentFace`, `lostSince`, `lastSeen`. Telefona ait: bildirim filtresi, arama ayarları, tema, hava şehri ve önbelleği, kayıp uyarısı ayarları. |
| `SettingsStore` | Model ortak, komut özel | `SettingSync` saatten bağımsız (istenen, gönderilen, onay). Tek anahtar `ls05/sync`. Komutu `SettingCommands.build` ve `AlarmSetting.command` (UTE) üretir. |
| `HealthStore` | Depo ortak, içerik özel | `filesDir/health/yyyy-MM-dd.log`, ham UTE baytları. Ayrıştırma `HealthLog`/`HealthView` (protocol) içinde, UTE'ye bağlı. |
| `HealthView` (protocol) | LS05'e özel | `UteParser` ile adım, nabız, uyku hesaplar. Grafiklerin tek veri kaynağı. |
| `Reminders` | Ortak | Alarm notunu telefon zamanlar, saate bildirim olarak gönderir. Yuva sayısı `UteAlarm.SLOTS` (3). |
| `WeatherSync` | Çekme ortak, gönderme özel | Open-Meteo ortak. `UteWeather.build` ve `s.command(...)` LS05'e özel. |
| `FaceUpload`, `FaceLibrary`, `FaceGallery`, `TbwfCompiler`, `FaceWriter` | LS05'e özel | `FaceUpload` doğrudan `Ls05Ble` alır. |
| `LinkAlert` | Ortak | `WatchHub.ready` ve `Prefs.lostSince` kullanır. |
| `LastSeen` | Ortak | Kopmada konum alır, `Prefs.lastSeen` içine yazar. |
| `NotifyForwarder` | Ortak | Bildirimi `WatchHub.forward` ile verir. Servisi `Prefs.address != null` ise başlatır. |
| `PhoneRemote` | Ortak | Müzik ve telefonu bul. Tetik `WatchService.handleWatchKey` (UTE tuşu). |
| `BootReceiver`, `MainActivity` | Ortak | Servisi `Prefs.serviceEnabled && Prefs.address != null` koşuluyla başlatır. |
| `Pages` (`TodayPage`, `WatchPage`, `NotifyPage`, `SettingsPage`), `Charts`, `AlarmSheet` | Çoğu ortak | Doğrudan LS05 çağrıları: `s.ble.sendNotification` (deneme bildirimi), `s.ble.startScan`, `UteCommand.parseUserId`, `UteAlarm.SLOTS`, `NotifyPage.onFrame(UteFrame)`. |
| `HealthExport`, `HealthCsv` | Ortak | `HealthView` üzerinden çalışır. |

### 1.2 Saate dokunan yerler

- **BLE:** yalnızca `Ls05Ble`. Dışarıdan erişim `WatchService.ble` üzerinden (`Pages`: tarama, deneme bildirimi; `FaceUpload`).
- **Bağlantı yaşam döngüsü:** `WatchService.onStartCommand` (`ACTION_START`, `ACTION_STOP`, `ACTION_CONNECT_NEW`), `connectSaved`, `scheduleReconnect` (2, 5, 15, 30, 60 sn), `nudgeReconnect`, `btReceiver`, `blockAuth`.
- **Ayarlar:** `SettingsStore.set` → `WatchService.pushSettings` → `SettingCommands.build`. Onay: `onFrame` içinde `AlarmAck`, `DndAck`, `ProfileAck` → `handleAck`. Kopmada `sync.onDisconnect()`; doğrulama hatasında `markAllUnknown()`.
- **Sağlık:** `onRawFrame` → `HealthStore.add`; canlı adım `HealthStore.live`; okuma `HealthStore.view()` (3 gün) ve `reader(c, days)` (dosyadan). `Prefs.lastSync` geçmiş tazeliği.
- **Hatırlatmalar:** `Reminders.reschedule` alarm ayarını `SettingsStore` içinden okur; not `WatchHub.forward` ile gider.
- **Hava:** `WatchService.onReadyChanged` → `WeatherSync.refresh`; saatlik `weatherTick`; gönderim `WeatherSync.sendIfFresh`.
- **Kadran:** `WatchService.face`, `startFace`; bitince `Prefs.setCurrentFace`.
- **Kayıp uyarısı ve son görülen yer:** `WatchService.onDisconnected` içinde `LinkAlert.arm` ve `LastSeen.capture`; hazır olunca `LinkAlert.disarm`.

## 2. WatchManager

### 2.1 Yeri ve görevi

`WatchManager`, `WatchService` içinde yaşayan küçük bir sınıftır (servisle doğar, servisle ölür).
Ön plan bildirimi, Bluetooth alıcısı, izinler ve `START_STICKY` servis içinde kalır.
WatchManager şunları tutar: etkin saat kaydı, etkin sürücü, durum, oturum sayacı, yeniden bağlanma sayacı.
Geçiş kararlarının saf mantığı `protocol/` içinde `SwitchMachine` olarak yazılır. Böylece birim testi yapılır.

`WatchHub` aynı kalır. Arayüz durumu oradan okumaya devam eder. Yalnızca etkin saat adı ve yetenekleri eklenir.

### 2.2 Durumlar

| Durum | Anlamı |
|---|---|
| `NONE` | Kayıtlı ya da etkin saat yok. |
| `STOPPED` | Kullanıcı Durdur dedi (`serviceEnabled=false`). |
| `BT_OFF` | Bluetooth kapalı ya da izin yok. |
| `CONNECTING` | GATT bağlanıyor (doğrudan ya da otomatik bağlantı). |
| `AUTHENTICATING` | Bağlandı, kimlik doğrulama sürüyor. |
| `READY` | Komut gönderilebilir. |
| `BUSY` | READY ama kadran yükleniyor. Diğer trafik bekler (bugünkü `isWatchReady` kuralı). |
| `WAIT_RECONNECT` | Beklenmeyen kopma; geri çekilmeli bekleme. |
| `AUTH_BLOCKED` | Doğrulama durdu; kullanıcı müdahalesi gerekir. |
| `SWITCHING` | Eski sürücü kapatılıyor, yenisi kuruluyor. |

### 2.3 Geçişler

- `NONE` → saat eklenir ve seçilir → `CONNECTING`.
- `CONNECTING` → bağlandı → `AUTHENTICATING` → doğrulandı → `READY`.
- `READY` ↔ `BUSY` (kadran başladı / bitti).
- `READY` → beklenmeyen kopma → `WAIT_RECONNECT` (LinkAlert kurulur, LastSeen alınır, bugünkü gibi).
- `WAIT_RECONNECT` → süre doldu ya da `nudgeReconnect` → `CONNECTING`.
- Herhangi bir durum → doğrulama reddi ya da üç zaman aşımı → `AUTH_BLOCKED`.
- `BT_OFF` ↔ Bluetooth açıldı/kapandı (LinkAlert kurulmaz, bugünkü gibi).
- Herhangi bir durum (`BUSY` hariç) → `switchTo(id)` → `SWITCHING` → `CONNECTING` (yeni saat).
- Herhangi bir durum → Durdur → `STOPPED`.

### 2.4 Geçiş (switch) adımları

1. `BUSY` ise reddedilir: "Kadran yükleniyor; bitince tekrar dene." Aynı saat seçildiyse bir şey yapılmaz.
2. Durum `SWITCHING` olur. Yeniden bağlanma, geçmiş zaman aşımı, onay denetimi ve saati bul zamanlayıcıları iptal edilir (`delayedToken` ile hepsi birden).
3. Eski saat çalıyorsa ve bağlıysa önce arama durdurma komutu gider (`sendCallStop`).
4. `LinkAlert.disarm("saat değiştirildi")`. Bu kopma için kayıp uyarısı kurulmaz, son görülen yer alınmaz.
5. **Bekleyen yazmalar:**
   - Eski saatin GATT kuyruğundaki işlemler atılır. Sayısı loga yazılır (içerik yazılmaz).
   - Eski saatin `SettingSync` kaydında `onDisconnect()` çalışır ve kendi alanına kaydedilir. Ayarlar yeni saate taşınmaz.
   - `WatchHub.pending` (bildirim kuyruğu) telefona aittir. Silinmez; yeni saat hazır olunca süresi dolmamış olanlar gider.
6. Geçiş günlüğüne bir satır eklenir: zaman ve yeni saat kimliği (bkz. 5.3).
7. Eski sürücü `release()` ile kapanır (`forceClose`, `gatt.close`). Eşleştirme (bond) asla silinmez.
8. Kayıtta etkin saat değişir (`commit()`, eşzamanlı). Oturum sayacı artar. Doğrulama sayaçları ve `reconnectAttempt` sıfırlanır.
9. `Reminders.reschedule`: eski saatin notları iptal, yeninin notları kurulur.
10. Ön plan bildirimi: "<saat adı>: bağlanıyor". Kanal ve bildirim kimliği aynı kalır.
11. Yeni sürücü kurulur, `connect(autoConnect=false)`.
12. Yeni saat hazır olunca bugünkü `onReadyChanged` akışı çalışır: saat eşitleme, kuyruk, müzik durumu, ayarlar, eskiyse geçmiş, hava.

### 2.5 Hata durumları

- Yeni saat bağlanamazsa normal geri çekilmeli deneme sürer. Eski saate kendiliğinden dönülmez. Saatlerim ekranında "Önceki saate dön" düğmesi görünür.
- Yeni saatte doğrulama reddi: `AUTH_BLOCKED`, saat adıyla mesaj. Band 8 için ek mesaj: "Anahtar geçersiz olabilir (Mi Fitness eşleşmesi silinmiş olabilir)."
- Geçiş sırasında servis ölürse: etkin saat 8. adımda kaydedildiği için servis yeniden kalkınca yeni saate bağlanır.
- Geçiş sırasında Bluetooth kapanırsa: `BT_OFF`; açılınca etkin saate bağlanılır.

### 2.6 Servis başlatma koşulu

`Prefs.address(c) != null` kontrolü dört yerde var (`WatchHub.forward`, `BootReceiver`, `NotifyForwarder`, `MainActivity`).
Hepsi `WatchRegistry.hasActive(c)` ile değişir. LS05 için bu, aynı `address` anahtarını okur; sonuç aynıdır.

## 3. Sürücü arayüzü ve yetenekler

### 3.1 `WatchDriver` (app/)

```kotlin
interface WatchDriver {
    val type: WatchType
    val caps: Capabilities
    val isConnected: Boolean
    val isReady: Boolean
    val busy: Boolean                       // kadran yükleme gibi uzun işler
    val pendingOps: Int
    fun connect(device: BluetoothDevice, autoConnect: Boolean)
    fun disconnect()
    fun release(reason: String)             // forceClose + kuyruğu at
    fun sendNote(title: String, body: String, kind: WatchHub.NoteKind, vibrate: Boolean): Boolean
    fun sendCallAlert(name: String): Boolean
    fun sendCallStop(): Boolean
    fun sendTime(): Boolean
    fun sendSetting(key: String, value: String): Boolean   // SettingSync anahtarı → saatin komutu
    fun requestHistory(includeSleep: Boolean): Boolean
    fun startFind(): Boolean
    fun stopFind()
    fun sendWeather(w: WeatherPayload): Boolean
    fun readBattery()
    fun sendMusicState(playing: Boolean, title: String?)
}

interface DriverListener {
    fun onLog(line: String)
    fun onStatus(text: String)
    fun onReadyChanged(ready: Boolean)
    fun onDisconnected(userInitiated: Boolean, status: Int)
    fun onAuth(result: AuthResult)          // Ok, Timeout, Missing, Rejected(code), NeedsUser
    fun onBattery(percent: Int)
    fun onFirmware(version: String)
    fun onSettingAck(key: String, echoedValue: String?)
    fun onHealthRaw(bytes: ByteArray)       // HealthStore'a kaynak kimliğiyle gider
    fun onLiveSteps(date: LocalDate, hour: Int, total: Int)
    fun onHistoryDone()
    fun onWatchEvent(e: WatchEvent)         // MUSIC_*, FIND_PHONE, REJECT_CALL, FIND_STOP
}
```

- **`Ls05Driver`:** `Ls05Ble`'yi sarar. `Ls05Ble` değişmez. LS05'e özel kod `WatchService`'ten buraya taşınır: `onFrame` dönüşümü, doğrulama kodları (1, -1, 4, diğer), `SettingCommands.build`, `HistoryGate`, saati bul, `UteWeather.build`, tuş eşlemesi, `FaceUpload`.
- **`Band8Driver`:** Ajan A yazar (adım 3-4). Aynı arayüz. Anahtar `SecretStore`'dan gelir.
- Saatten bağımsız işler `WatchService`'te kalır: Türkçe katlama (`fold`), kuyruk, LinkAlert, LastSeen, PhoneRemote, hava zamanlaması, arama güvenlik durdurması.
- LS05'e özel arayüz çağrıları sürücü üzerinden yapılır: `Pages` deneme bildirimi `driver.sendNote`, tarama `WatchService.scan(type)`, kadran `(driver as? Ls05Driver)?.face`.
- `WatchHub.Observer.onFrame(UteFrame)` yalnızca `NotifyPage` (aktarım bitti) için kullanılıyor. Bu, `onNoteDelivered()` gibi ortak bir olaya çevrilir.

### 3.2 `Capabilities` (protocol/, saf veri)

```kotlin
data class Capabilities(
    val notifications: Boolean, val incomingCall: Boolean, val callReject: Boolean, val missedCall: Boolean,
    val alarmSlots: Int, val alarmVibration: Boolean, val dnd: Boolean, val profile: Boolean,
    val weather: Boolean, val timeSync: Boolean, val battery: Boolean,
    val steps: Boolean, val heart: Boolean, val sleep: Boolean, val liveSteps: Boolean,
    val findWatch: Boolean, val findPhone: Boolean, val musicRemote: Boolean, val faces: Boolean,
)
```

- `Capabilities.LS05`: bugünkü özelliklerin hepsi açık, `alarmSlots = 3`.
- `Capabilities.BAND8_BASE`: adım 3-4 için yalnızca bildirim, arama, pil, saat eşitleme, bul. Ajan A doğruladıkça açar. Kadran kapalı.
- Kural: doğrulanmamış komut olan yetenek kapalı başlar (V12 plan kural 1).
- **Arayüz:** her sayfa kartları bir kez kurar. `refresh()` içinde `WatchManager.caps` okunur ve desteklenmeyen kartın görünürlüğü `GONE` olur. Saat yoksa `Capabilities.LS05` kullanılır; bugünkü ekran aynen görünür.
- Gizlenecekler: kadran kartı ve galeri (`faces`), alarm titreşim seçimi (`alarmVibration`), "Alarm ekle" sınırı (`alarmSlots`), profil kartı (`profile`), müzik kumandası ayarı (`musicRemote`), "Saatten aramayı reddet" (`callReject`), hava kartı (`weather`).
- Sağlık kartları gizlenmez: birleşik veri saatten bağımsızdır.

## 4. Depolama

### 4.1 Saat kaydı (`WatchRegistry`)

Yeni SharedPreferences dosyası: `watches`. Eski `ls05` dosyasına göç sırasında hiçbir şey yazılmaz.

| Anahtar | İçerik |
|---|---|
| `v` | Kayıt sürümü (1). |
| `ids` | Sıralı kimlik listesi, virgülle. |
| `active` | Etkin saat kimliği ya da boş. |
| `w.<id>.type` | `LS05` ya da `BAND8`. |
| `w.<id>.name` | Kullanıcının verdiği ad. |
| `w.<id>.ns` | Ayar ad alanı (bkz. 4.2). |
| `w.<id>.added` | Eklenme zamanı. |
| `w.<id>.auth` | Yalnızca Band 8: şifreli anahtar (bkz. 4.3). |

Adres kayıtta tutulmaz, ad alanındaki `address` anahtarında durur. Böylece LS05 adresi tek yerde kalır.
Kimlikler kısa ve rastgele değildir: göç edilen LS05 `ls05`, sonrakiler `w2`, `w3` (sayaç `next`).

### 4.2 Saate ait ayarlar: ad alanı

- `Prefs` içindeki saate ait işlevler etkin saatin ad alanını kullanır: `key(c, "address")`.
- Göç edilen LS05'in ad alanı boştur. Yani anahtarlar bugünküyle birebir aynıdır: `address`, `userId`, `sync`, `alarmNote1`, `alarmVisible1`, `currentFace`, `lastSeen`.
- Yeni saatler `d.<id>.` önekini alır: `d.w2.address`, `d.w2.sync`.
- Saate ait anahtarlar: `address`, `userId`, `sync`, `profileVerified`, `lastSync`, `alarmNote*`, `alarmVisible*`, `reminderDay*`, `alarmSlotsV11`, `currentFace`, `lostSince`, `lastSeen`.
- Telefona ait (ortak) anahtarlar değişmez: bildirim ve uygulama filtresi, arama ayarları, `vibrate`, `autoTime`, `foldTurkish`, tema, hava, `lostAlert`, `lostQuiet`, `lostSnoozeUntil`, `lastSeenOn`, `serviceEnabled`, `profileDraft`.
- `SettingsStore` ad alanlı `sync` anahtarını kullanır ve önbelleği saat kimliğine göre tutar.
- `Reminders` iptal ederken tüm türlerin en büyük yuva sayısına kadar döner. Kurarken yalnızca etkin saatin yuvalarını kurar.

### 4.3 Band 8 kimlik anahtarı (`SecretStore`)

- Android Keystore'da AES-256-GCM anahtarı, takma ad `watch-auth-v1`. Kullanıcı doğrulaması istenmez (servis açılışta kullanabilmeli).
- Kayıtta saklanan: `1:<iv base64>:<şifreli metin base64>`. Düz anahtar hiçbir yere yazılmaz.
- Giriş: 32 onaltılık karakter denetlenir. Hatalıysa hiçbir şey kaydedilmez.
- Anahtar loga, durum metnine, Toast'a, istisna mesajına yazılmaz. Ekranda yalnızca "Anahtar kayıtlı" görünür.
- Bellekteki kopya kullanımdan sonra sıfırlanır (en iyi çaba).
- Keystore anahtarı bozulursa ya da silinirse: çözme hatası `AUTH_BLOCKED` ve "Anahtarı yeniden gir" mesajı verir.
- `allowBackup="false"` zaten var. Yedekten geri yüklemede anahtar sorunu çıkmaz.
- Saat silinince şifreli metin silinir. Takma ad, başka Band 8 kalmadıysa silinir.

### 4.4 Göç: sıfır veri kaybı, geri dönüşe güvenli

- **Ne zaman:** `WatchRegistry` ilk okunduğunda, bir kez. Bayrak yalnızca `watches` dosyasındadır.
- **Kural:**
  - `watches` dosyasında `v` varsa: göç yok.
  - Yoksa ve `ls05` dosyasında `address` ya da `userId` doluysa: tek kayıt yazılır. `ls05`, tür `LS05`, ad "LS05", ad alanı boş, etkin.
  - İkisi de boşsa (yeni kurulum): boş liste yazılır.
- **Eski veri:** `ls05` dosyasındaki hiçbir anahtar silinmez, taşınmaz, yeniden adlandırılmaz. `filesDir/health/` dosyalarına dokunulmaz.
- **Geri dönüş:** v0.11.2 kodu yalnızca eski anahtarları ve `health/` klasörünü okur. Bunlar aynı kaldığı için eski kodla derlenmiş bir sürüm LS05'i bugünkü gibi çalıştırır. Yeni dosyalar (`watches`, `health-<id>/`, geçiş günlüğü) eski sürümce yok sayılır.
- **Dikkat:** Android daha düşük `versionCode` kurulumunu engeller. Geri dönüş yolu: eski koddan daha yüksek `versionCode` ile yayın. Belge ve kontrol listesi buna göre yazılır.
- **Bozuk kayıt:** `watches` okunamazsa (ayrıştırma hatası) eski LS05 anahtarlarından yeniden kurulur ve loga "Saat kaydı yeniden kuruldu" yazılır. Eski veri bu durumda da yerindedir.
- Göç kararı saf bir işlevdir (`RegistryMigration.decide(snapshot)`), `protocol/` içinde test edilir.

## 5. Birleşik sağlık verisi

### 5.1 Ham depo ve kaynak etiketi

- **LS05 (göç edilen):** `filesDir/health/yyyy-MM-dd.log`, bugünkü biçim. Kaynak kimliği klasörden gelir: `ls05`.
- **Diğer saatler:** `filesDir/health-<id>/yyyy-MM-dd.log`. Satır biçimi aynı: alınma zamanı, UTC farkı, onaltılık baytlar. Ayrıştırıcı saat türüne göre seçilir.
- Satıra kaynak sütunu eklenmez. Nedeni: `HealthLog.parseLine` tam 3 alan ister; 4 alanlı satırları eski sürüm atar. Ayrı klasör, eski sürümle uyumu korur.
- 90 gün silme kuralı her klasöre ayrı uygulanır. Klasör adı `health` ya da `health-<id>` olmayan hiçbir şeye dokunulmaz.
- Saat silinince sağlık klasörü silinmez. Kaynak adı "Silinmiş saat (Band 8)" olarak görünür.
- Canlı adım (`HealthStore.live`) kaynağa göre ayrı tutulur.

### 5.2 Ortak (normalize) model, protocol/

```kotlin
enum class SourceKind { LS05, BAND8 }
data class Source(val id: String, val kind: SourceKind)

data class StepHourRec(val source: Source, val date: LocalDate, val hour: Int, val steps: Int)
data class StepDayRec(val source: Source, val date: LocalDate, val steps: Int)        // yalnızca günlük toplam varsa
data class HeartRec(val source: Source, val at: LocalDateTime, val bpm: Int)
enum class SleepStage { DEEP, LIGHT, REM, AWAKE }
data class SleepPart(val start: LocalDateTime, val minutes: Int, val stage: SleepStage)
data class SleepSession(val source: Source, val parts: List<SleepPart>)               // sıralı, boşluk olabilir

interface HealthSource {
    fun stepHours(): List<StepHourRec>
    fun stepDays(): List<StepDayRec>
    fun heart(): List<HeartRec>
    fun sleep(): List<SleepSession>
}
```

- `Ls05Source`: var olan `HealthView`'u sarar. `HealthView` değişmez. Uyku türü 1 → DEEP, 2 → LIGHT, diğerleri → AWAKE (bugünkü kural).
- `Band8Source`: Ajan A'nın ayrıştırıcısıyla. REM ayrı aşamadır; asla "uyanık" sayılmaz.
- Dikkat: bugün `SleepSegment` içinde tür 1 ve 2 dışındaki her şey uyanık sayılıyor. Band 8 REM'i bu türe sokulmamalı. Bu yüzden ortak model kendi `SleepStage` türünü kullanır.

### 5.3 Geçiş günlüğü

- `filesDir/health/switches.log`. Satır: `epochSaniye<TAB>saatKimliği`. Sağlık değeri, adres ya da konum içermez.
- `health/` içinde durur ama adı tarih biçimine uymadığı için eski sürüm ve 90 gün silme ona dokunmaz.
- Her geçişte ve ilk göçte (LS05 etkin, zaman = göç anı) bir satır yazılır.
- `activeAt(t)`: t anından önceki son satırın saati. Günlük boşsa ya da t ilk satırdan önceyse sonuç "bilinmiyor".

### 5.4 Birleştirme kuralları (bölüm 8, aynen)

Saf işlev: `HealthMerge.merge(sources, switchLog): MergedHealth`. `protocol/` içinde.

**Adım**
- Her (gün, saat) için kaynakların en büyük değeri alınır.
- Bir kaynak o gün için yalnızca günlük toplam veriyorsa ve saatlik fark çıkarılamıyorsa bu toplam `StepDayRec` olarak ayrı tutulur.
- Saatlik dağılım uydurulmaz. Saatlik grafikte o kaynak görünmez; altına "Band 8: yalnızca günlük toplam" notu düşülür.
- Günlük toplam = max(birleşik saatlik toplam, ayrı tutulan her günlük toplam). Hangisinin seçildiği `MergedHealth` içinde işaretlenir.
- Birikimli sayaç veren kaynakta saatlik fark, ardışık okumaların farkıdır. Bu hesap sürücüde değil `Band8Source` içinde yapılır ve test edilir.

**Nabız**
- Kayıtlar dakikaya yuvarlanır.
- Aynı dakikada tek kaynak varsa o kullanılır.
- Aynı dakikada birden çok kaynak varsa: geçiş günlüğüne göre o dakikada etkin olan saatin okuması seçilir.
- Etkin saat bilinmiyorsa ya da etkin saatin o dakikada okuması yoksa sabit öncelik: önce LS05, sonra Band 8. Aynı türden iki saatte kayıt sırası.
- Ham okumaların ikisi de depoda kalır. Seçim yalnızca görünümde yapılır.
- `heartSummary` (LS05 günlük özeti) yalnızca LS05 kaynaklıdır; birleşik görünümde etkin saatin özeti varsa o, yoksa LS05.

**Uyku**
- Karşılaştırma birimi oturumdur.
- Zamanı çakışan (kesişimi 0 dakikadan uzun) farklı kaynaklı oturumlardan biri seçilir. Çakışmayan oturumların hepsi kalır.
- Seçim sırası:
  1. Daha ayrıntılı olan: ayrı uyku aşaması sayısı (DEEP, LIGHT, REM) çok olan.
  2. Bütün olan: ardışık bölümler arasında 5 dakikadan uzun boşluk yok.
  3. Eşitlikte uzun oturum (ilk başlangıçtan son bitişe).
  4. Hâlâ eşitse önce LS05 (sonuç her çalıştırmada aynı olsun diye).
- Oturum, uyanılan güne bağlanır. Bugünkü kural korunur: 18:00 ve sonrası başlayan bölüm ertesi günün gecesidir.
- Gece saat değiştirme: LS05 ilk yarı, Band 8 ikinci yarı. Oturumlar çakışmaz, ikisi de kalır, aynı uyanış gününde toplanır. Arada geçen süre "kayıt yok" olarak gösterilir, uyanık sayılmaz.

### 5.5 Grafiklerin okuması

- `MergedHealth`, `HealthView`'un bugün kullanılan işlevlerini aynı imzayla verir: `stepsForDay`, `hourlySteps`, `lastHeart`, `heartPoints`, `sleepSegmentsFor`, `sleepFor`, `heartSummary`.
- Ortak bir arayüz (`HealthQuery`) tanımlanır. `HealthView` ve `MergedHealth` ikisi de bunu uygular. `Pages` ve `HealthCsv` yalnızca türü değiştirir.
- `HealthStore.view()` ve `reader()` artık `MergedHealth` döner. Tek kaynak (yalnızca LS05) varken sonuç `HealthView` ile birebir aynıdır. Bu bir gerileme testidir.
- `SleepStripView` REM için yeni bir renk alır. LS05 verisinde REM olmadığı için görünüm değişmez.
- CSV dışa aktarma: sona `kaynak` sütunu eklenir (saat adı). Açık soru 7.
- Performans: 31 günlük okuma bugün arka planda yapılıyor (`trend-read`). Birleştirme de orada yapılır. Ana iş parçacığında yalnızca 3 günlük görünüm birleşir.

## 6. Adım 2 uygulama planı

### 6.1 Commit sırası (küçük, her biri derlenir ve LS05 aynı çalışır)

1. **protocol: model ve göç kararı.** `WatchType`, `Capabilities` (`LS05`, `BAND8_BASE`), `RegistryMigration`, `SwitchMachine`. Testler: `RegistryMigrationTest`, `SwitchMachineTest`, `CapabilitiesTest`.
2. **app: `WatchRegistry` ve ad alanlı `Prefs`.** Yeni `WatchRegistry.kt`; `Prefs.kt` içinde saate ait işlevler `key(c, ...)` kullanır. Dört başlatma koşulu `WatchRegistry.hasActive` olur (`WatchHub`, `BootReceiver`, `NotifyForwarder`, `MainActivity`). LS05'te anahtarlar birebir aynı.
3. **app: `SettingsStore` ve `Reminders` ad alanı.** `sync` anahtarı ad alanlı, önbellek saat kimliğine bağlı. `Reminders` yuva sayısı `caps.alarmSlots`, iptal en büyük yuvaya kadar.
4. **app: `WatchDriver` ve `Ls05Driver` (yalnızca sarma).** Arayüz, `DriverListener`, `Ls05Driver` `Ls05Ble`'yi sarar. `WatchService` henüz eski yolları kullanır; dışarıdan `ble` erişimi sürücüye yönlenir (`Pages` deneme bildirimi, tarama).
5. **app: LS05 çerçeve işlemenin sürücüye taşınması.** `onFrame`, doğrulama kodları, ayar komutu, `HistoryGate`, saati bul, tuşlar, hava paketi, `FaceUpload` `Ls05Driver` içine. `WatchService` yalnızca `DriverListener` olur. `NotifyPage.onFrame` ortak olaya çevrilir. Bu en büyük commit; davranış değişmez.
6. **app: `WatchManager` ve geçiş.** Durum makinesi, `switchTo`, ön plan bildiriminde saat adı, geçiş günlüğü yazımı.
7. **app: yeteneklere göre arayüz.** `Pages` kartlarının görünürlüğü `caps`'e bağlanır. LS05'te hepsi görünür.
8. **app: Saatlerim ekranı.** `WatchPage` en üstüne liste: ad, tür, etkin işareti, bağlantı durumu. Ekle (tür seç → tara → ad ver; Band 8 için anahtar), yeniden adlandır, etkin yap, sil (onaylı). Göç edilen LS05 silinirse eski anahtarlar silinmez, yalnızca kayıttan çıkar.
9. **app: `SecretStore`.** Keystore şifreleme. Saf kısımlar (`1:iv:metin` zarfı, onaltılık denetim) protocol testinde. Band 8 ekleme akışı, `Band8Driver` gelene kadar "henüz desteklenmiyor" der ve saati etkin yapmaz.
10. **app: `HealthStore` kaynak klasörleri.** Sürücü kimliğiyle yazma (`health` ya da `health-<id>`), kaynağa göre canlı adım. Birleştirme adım 5'te; bu adımda okuma yalnızca etkin LS05 kaynağından, bugünkü gibi.
11. **Sürüm:** `versionCode` 24, `versionName` 0.12.0. `docs/PLAN.md` kısa not.

### 6.2 Testler

- **Göç:** boş kurulum; adresi olan LS05; yalnızca userId; ikinci çalıştırmada değişiklik yok; bozuk `watches`; göçün `ls05` dosyasına hiçbir şey yazmadığı (anlık görüntü karşılaştırması).
- **Ad alanı:** göç edilen LS05 için her saate ait anahtarın adının bugünküyle aynı olduğu; yeni saat için `d.<id>.` öneki.
- **Geçiş makinesi:** BUSY'de red; aynı saate geçişte işlem yok; geçişte LinkAlert kurulmaz; kuyruk korunur; ayarlar eski saatte `onDisconnect` olur; hatada eski saate dönülmez.
- **Gerileme (LS05):** var olan tüm protocol testleri değişmeden geçer (`HealthLogTest`, `SettingSyncTest`, `UteTest`, `V011Test` ve diğerleri). `Ls05Driver` için ayar komutu baytları `SettingCommands.build` ile aynı.
- **Adım 5 için hazırlık:** `HealthMerge` testleri adım 5'te yazılır (saatlik max, günlük toplam ayrı, nabız etkin saat, LS05 önceliği, uyku ayrıntı/bütünlük/uzunluk, gece geçişi, tek kaynakta `HealthView` ile birebir eşitlik).
- **Yerel çalıştırma:** repo dışındaki yerel test betiği bugün ana kopyanın `protocol/` klasörünü derliyor. Adım 2'de bu dalın yolunu parametre alan bir kopyası kullanılır. Repo içindeki derleme dosyaları değişmez; CI Gradle ile aynı testleri çalıştırır.

### 6.3 Cihazda doğrulama (LS05'te hiçbir şey değişmedi mi?)

Önce (v0.11.2 açıkken): Bugün ve 7/30 gün grafiklerinin ekran görüntüsü, Saat ekranındaki alarm ve rahatsız etme durumları, seçili kadran.
Sonra v0.12.0'ı kaldırmadan üstüne kur ve şunları dene:

1. Uygulama açılır; Saatlerim'de tek kayıt "LS05", etkin. Kendiliğinden bağlanır.
2. Grafikler ekran görüntüleriyle aynı. Yenile ile geçmiş okunur.
3. Alarmlar, notlar, rahatsız etme "onaylandı" durumunda; yeni alarm kurulup onay gelir.
4. Bildirim, gelen arama, cevapsız arama, saatten reddetme.
5. Saati bul, telefonu bul, müzik kumandası.
6. Hava saate gider.
7. Saati uzaklaştır: kayıp uyarısı ve son görülen yer.
8. Kadran yükle (pil %20 üstü).
9. Telefonu yeniden başlat: servis kendiliğinden kalkar.
10. Durdur / Başlat.
11. Log: adres, kimlik ya da içerik görünmüyor.

## 7. Riskler ve açık sorular

### Riskler

1. **5. commit büyük:** LS05 çerçeve işlemenin taşınması en riskli adım. Önlem: davranış değişikliği yok, satır satır taşıma, ardından tam cihaz listesi.
2. **Eski kodun anahtarları okuması:** ad alanı hatası LS05'in adresini ya da ayarlarını "boş" gösterebilir. Önlem: ad alanı testi ve göç edilen saatin boş önekli olması.
3. **Geçişte GATT artığı:** eski LS05 `autoConnect` ile bekliyorsa yeni saat bağlanırken eski bağlantı geri gelebilir. Önlem: `release` içinde `gatt.close` zorunlu; geçiş sonrası logda tek bağlantı denetimi.
4. **Band 8 ve başka uygulama:** Mi Fitness ya da Notify aynı anda bağlıysa doğrulama başarısız olur ya da anahtar geçersizleşir. Saatlerim'de uyarı metni gerekir.
5. **Keystore:** bazı cihazlarda Keystore anahtarı güncelleme ya da kilit değişiminde kaybolabilir. Sonuç yalnızca anahtarın yeniden girilmesidir; veri kaybı yok.
6. **Uyku türü karışıklığı:** REM, bugünkü `SleepSegment` kuralında uyanık sayılır. Ortak model bunu ayırmazsa uyku süresi yanlış düşer.
7. **Zaman damgaları:** LS05 yerel saat verir. Band 8 büyük olasılıkla UTC verir. Birleştirme yerel saat üzerinden yapılmalı; saat dilimi değişen günler test edilmeli.
8. **Nabız sıklığı farkı:** LS05 10 dakikada bir, Band 8 daha sık ölçebilir. Kural yalnızca aynı dakikayı çözer; iki kaynağın iç içe geçtiği saatlerde grafik dalgalı görünebilir.
9. **Hatırlatma yuvaları:** Band 8'in alarm yuvası sayısı bilinmiyor. Bilinene kadar `alarmSlots` küçük tutulur.

### Açık sorular (Che'ye ya da Ajan A'ya)

1. Band 8 adımı saatlik mi, dakikalık mı, yalnızca günlük toplam mı veriyor? (Ajan A)
2. Band 8 zaman damgaları UTC mi, yerel mi? (Ajan A)
3. Band 8 alarm yuvası sayısı ve titreşim ayarı var mı? (Ajan A)
4. Profil (boy, kilo, adım hedefi) ortak mı kalsın, saate göre mi? Öneri: ortak; yalnızca "saat onayladı" bilgisi saate göre.
5. Son görülen yer saate göre mi tutulsun (öneri), yoksa tek mi?
6. Göç edilen LS05'in görünen adı "LS05" mi olsun, yoksa ilk açılışta sorulsun mu?
7. CSV'ye `kaynak` sütunu eklensin mi? Che CSV'yi başka bir yerde okuyorsa sütun sonu ekleme yine de bozabilir.
8. Nabızda "aynı dakika" dışında, etkin olmayan saatin okumaları da gizlensin mi? Bölüm 8 bunu istemiyor; şimdilik gösterilir.
9. Saat değiştirildiğinde eski saat de titresin ya da "bağlantı yok" göstersin mi? Bu saatin kendi davranışı; uygulama bir şey göndermez.
