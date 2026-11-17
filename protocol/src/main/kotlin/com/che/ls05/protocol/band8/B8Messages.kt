package com.che.ls05.protocol.band8

/** Command mesajının tür numaraları ve içerik alanları (belge 5.1). */
object B8Type {
    const val AUTH = 1
    const val SYSTEM = 2
    const val WATCHFACE = 4
    const val NOTIFICATION = 7
    const val HEALTH = 8
    const val WEATHER = 10
    const val CALENDAR = 12
    const val SCHEDULE = 17
    const val MUSIC = 18
    const val DATA_UPLOAD = 22

    const val F_AUTH = 3
    const val F_SYSTEM = 4
    const val F_WATCHFACE = 6
    const val F_NOTIFICATION = 9
    const val F_HEALTH = 10
    const val F_WEATHER = 12
    const val F_SCHEDULE = 19
    const val F_DATA_UPLOAD = 24
    const val F_STATUS = 100
}

/** Alt tür sabitleri. Yalnızca 3. ve 4. adım için gerekenler. */
object B8Sub {
    const val AUTH_NONCE = 26
    const val AUTH_PROOF = 27

    const val SYS_BATTERY = 1
    const val SYS_DEVICE_INFO = 2
    const val SYS_CLOCK = 3
    const val SYS_FIND_PHONE = 17
    const val SYS_FIND_BAND = 18
    const val SYS_DEVICE_STATE = 79

    const val NOTIF_SEND = 0
    const val NOTIF_DISMISS = 1
    const val NOTIF_CALL_REJECT = 2
    const val NOTIF_CALL_MUTE = 5
    const val NOTIF_OPEN_ON_PHONE = 8
    const val NOTIF_ICON_REQUEST = 15
    const val NOTIF_ICON_QUERY = 16

    const val HEALTH_FILES_TODAY = 1
    const val HEALTH_FILES_PAST = 2
    const val HEALTH_FILE_REQUEST = 3
    const val HEALTH_FILE_DELETE = 5
}

/** Çözülmüş bir Command: tür, alt tür ve gövde. */
class B8Command(val type: Int, val subtype: Int, val msg: PbMessage) {
    val status: Int? get() = msg.int(B8Type.F_STATUS)
    fun body(field: Int): PbMessage? = msg.message(field)
    fun matches(type: Int, subtype: Int): Boolean = this.type == type && this.subtype == subtype

    companion object {
        fun parse(plain: ByteArray): B8Command? {
            val m = PbMessage.parseOrNull(plain) ?: return null
            val t = m.int(1) ?: return null
            val s = m.int(2) ?: return null
            return B8Command(t, s, m)
        }
    }
}

data class B8Battery(val levelPercent: Int?, val state: Int?)

data class B8DeviceInfo(val serial: String?, val firmware: String?, val model: String?)

/** Saat eşitleme girdisi. Tarih ve saat **yerel**dir. Fark 15 dakikalık birimdir. */
data class B8Clock(
    val year: Int, val month: Int, val day: Int,
    val hour: Int, val minute: Int, val second: Int, val millis: Int,
    val utcOffsetQuarters: Int, val dstQuarters: Int, val zoneId: String, val twelveHour: Boolean,
)

/** Banda gidecek bildirim. Metinler [B8Messages.notification] içinde kısaltılır. */
data class B8Notification(
    val packageName: String,
    val appName: String,
    val title: String,
    val body: String,
    /** Yerel saat, `yyyyMMdd'T'HHmmss` (15 karakter). */
    val timestamp: String,
    val id: Int,
    val key: String,
    val isCall: Boolean = false,
    val openOnPhone: Boolean = false,
)

/** Bildirimi tanımlayan üçlü (kapatma, telefonda aç). */
data class B8NotificationRef(val id: Int?, val packageName: String?, val key: String?)

/** Telefonun kendini tanıttığı cihaz bilgisi (belge 3.3). */
data class B8PhoneInfo(
    val androidApi: Int,
    val model: String,
    val region: String,
    val capabilityMask: Int = 224,
)

object B8Messages {
    const val TITLE_MAX_BYTES = 64
    const val BODY_MAX_BYTES = 512
    const val CALL_PACKAGE = "phone"

    fun command(type: Int, subtype: Int): PbWriter = PbWriter().uint(1, type).uint(2, subtype)

    private fun command(type: Int, subtype: Int, field: Int, body: PbWriter): ByteArray =
        command(type, subtype).message(field, body).toByteArray()

    // ---------- Auth ----------

    fun authNonce(phoneNonce: ByteArray): ByteArray =
        command(B8Type.AUTH, B8Sub.AUTH_NONCE, B8Type.F_AUTH, PbWriter().message(30, PbWriter().bytes(1, phoneNonce)))

    fun authProof(phoneHmac: ByteArray, encryptedInfo: ByteArray): ByteArray =
        command(B8Type.AUTH, B8Sub.AUTH_PROOF, B8Type.F_AUTH,
            PbWriter().message(32, PbWriter().bytes(1, phoneHmac).bytes(2, encryptedInfo)))

    fun phoneInfo(info: B8PhoneInfo): ByteArray = PbWriter()
        .uint(1, 0)
        .float(2, info.androidApi.toFloat())
        .string(3, info.model)
        .uint(4, info.capabilityMask)
        .string(5, info.region.uppercase())
        .toByteArray()

    // ---------- System ----------

    fun batteryRequest(): ByteArray = command(B8Type.SYSTEM, B8Sub.SYS_BATTERY).toByteArray()

    fun parseBattery(c: B8Command): B8Battery? {
        val b = c.body(B8Type.F_SYSTEM)?.message(2)?.message(1) ?: return null
        return B8Battery(b.int(1), b.int(2))
    }

    fun deviceInfoRequest(): ByteArray = command(B8Type.SYSTEM, B8Sub.SYS_DEVICE_INFO).toByteArray()

    fun parseDeviceInfo(c: B8Command): B8DeviceInfo? {
        val d = c.body(B8Type.F_SYSTEM)?.message(3) ?: return null
        return B8DeviceInfo(d.string(1), d.string(2), d.string(4))
    }

    fun clock(k: B8Clock): ByteArray {
        val tz = PbWriter().sint(1, k.utcOffsetQuarters)
        if (k.dstQuarters != 0) tz.sint(2, k.dstQuarters)
        tz.string(3, k.zoneId)
        val clock = PbWriter()
            .message(1, PbWriter().uint(1, k.year).uint(2, k.month).uint(3, k.day))
            .message(2, PbWriter().uint(1, k.hour).uint(2, k.minute).uint(3, k.second).uint(4, k.millis))
            .message(3, tz)
        if (k.twelveHour) clock.bool(4, true)
        return command(B8Type.SYSTEM, B8Sub.SYS_CLOCK, B8Type.F_SYSTEM, PbWriter().message(4, clock))
    }

    /** Band'i bul: başlat ya da durdur. */
    fun findBand(start: Boolean): ByteArray =
        command(B8Type.SYSTEM, B8Sub.SYS_FIND_BAND, B8Type.F_SYSTEM, PbWriter().uint(5, if (start) 0 else 1))

    /** Banddan gelen 2/17 (telefonu bul) ya da 2/18: true = başlat. */
    fun parseFindStart(c: B8Command): Boolean? = c.body(B8Type.F_SYSTEM)?.int(5)?.let { it == 0 }

    // ---------- Bildirim ----------

    /** UTF-8 bayt sınırına göre, karakter bölmeden kısaltır. Kısalırsa sona "…" ekler. */
    fun truncateUtf8(s: String, maxBytes: Int): String {
        if (s.toByteArray(Charsets.UTF_8).size <= maxBytes) return s
        val ell = "…"
        val budget = maxBytes - ell.toByteArray(Charsets.UTF_8).size
        val sb = StringBuilder()
        var used = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (used + n > budget) break
            sb.appendCodePoint(cp)
            used += n
            i += Character.charCount(cp)
        }
        return sb.append(ell).toString()
    }

    fun notification(n: B8Notification): ByteArray {
        val inner = PbWriter()
            .string(1, n.packageName)
            .string(2, n.appName)
            .string(3, truncateUtf8(n.title, TITLE_MAX_BYTES))
            .string(4, "")
            .string(5, truncateUtf8(n.body, BODY_MAX_BYTES))
            .string(6, n.timestamp)
            .uint(7, n.id)
        if (n.isCall) inner.bool(8, true)
        inner.string(12, n.key)
        if (n.openOnPhone) inner.bool(13, true)
        return command(B8Type.NOTIFICATION, B8Sub.NOTIF_SEND, B8Type.F_NOTIFICATION,
            PbWriter().message(3, PbWriter().message(1, inner)))
    }

    /** Gelen arama. [caller] boşsa "?" yazılır. */
    fun incomingCall(caller: String?, number: String, timestamp: String): ByteArray = notification(
        B8Notification(
            packageName = CALL_PACKAGE, appName = CALL_PACKAGE,
            title = caller?.takeIf { it.isNotBlank() } ?: "?", body = number,
            timestamp = timestamp, id = 0, key = "", isCall = true,
        )
    )

    /** Arama bitti ya da açıldı: bandda arama ekranını kapatır. */
    fun callEnded(): ByteArray = dismiss(listOf(B8NotificationRef(0, CALL_PACKAGE, null)))

    fun dismiss(refs: List<B8NotificationRef>): ByteArray {
        val body = PbWriter()
        for (r in refs) {
            val e = PbWriter()
            r.id?.let { e.uint(1, it) }
            r.packageName?.let { e.string(2, it) }
            r.key?.let { e.string(4, it) }
            body.message(1, e)
        }
        return command(B8Type.NOTIFICATION, B8Sub.NOTIF_DISMISS, B8Type.F_NOTIFICATION, PbWriter().message(4, body))
    }

    private fun ref(m: PbMessage) = B8NotificationRef(m.int(1), m.string(2), m.string(4))

    /** Banddan gelen 7/1: bandda kapatılan bildirimler. */
    fun parseDismiss(c: B8Command): List<B8NotificationRef> =
        c.body(B8Type.F_NOTIFICATION)?.message(4)?.messages(1)?.map { ref(it) } ?: emptyList()

    /** Banddan gelen 7/8: "telefonda aç". */
    fun parseOpenOnPhone(c: B8Command): B8NotificationRef? =
        c.body(B8Type.F_NOTIFICATION)?.message(2)?.let { ref(it) }

    // ---------- Etkinlik dosyaları ----------

    /** 8/1 bugün. Kayıtta Notify 1 gönderdi. */
    fun filesToday(): ByteArray =
        command(B8Type.HEALTH, B8Sub.HEALTH_FILES_TODAY, B8Type.F_HEALTH, PbWriter().message(5, PbWriter().uint(1, 1)))

    fun filesPast(): ByteArray = command(B8Type.HEALTH, B8Sub.HEALTH_FILES_PAST).toByteArray()

    /** 8/3: tek dosya iste (belge 7.2: her istekte tek kimlik). */
    fun fileRequest(id: B8FileId): ByteArray =
        command(B8Type.HEALTH, B8Sub.HEALTH_FILE_REQUEST, B8Type.F_HEALTH, PbWriter().bytes(2, id.encode()))

    /** 8/5: dosyayı bandan siler. Yalnızca [B8Fetch] çağırır, o da açık izinle. */
    internal fun fileDelete(id: B8FileId): ByteArray =
        command(B8Type.HEALTH, B8Sub.HEALTH_FILE_DELETE, B8Type.F_HEALTH, PbWriter().bytes(3, id.encode()))

    /** 8/1 ve 8/2 yanıtındaki 7'şer baytlık kimlikler. */
    fun parseFileList(c: B8Command): List<B8FileId> {
        val raw = c.body(B8Type.F_HEALTH)?.bytes(2) ?: return emptyList()
        return (0 until raw.size / 7).map { B8FileId.decode(raw, it * 7) }
    }
}
