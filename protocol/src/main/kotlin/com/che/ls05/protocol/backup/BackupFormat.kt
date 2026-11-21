package com.che.ls05.protocol.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Yedek dosyası (.fwb). Başlık:
 *   "FWBK" | sürüm (1 bayt) | bayraklar (1 bayt, bit0 = şifreli)
 *   şifreliyse: kdf (1 = PBKDF2-HMAC-SHA256) | tur sayısı (u32 BE) | tuz boyu + tuz | nonce boyu + nonce
 * Ardından yük: düz yedekte ZIP, şifreli yedekte AES-256-GCM ile şifrelenmiş ZIP (başlık ek veri olarak doğrulanır).
 * ZIP içi: manifest.txt, prefs/<dosya>.txt, files/<klasör>/<ad>, secrets.txt (yalnızca şifreli yedekte).
 * Gizli değer hiçbir zaman istisna mesajına ya da toString'e girmez.
 */
object BackupFormat {

    const val VERSION = 1
    const val ITERATIONS = 310_000
    const val MIN_ITERATIONS = 200_000
    const val MAX_ITERATIONS = 5_000_000
    const val MIN_PASSWORD = 8
    const val MAX_FILE_BYTES = 128L * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 32L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 160L * 1024 * 1024
    private const val MAX_ENTRIES = 4000

    private val MAGIC = byteArrayOf('F'.code.toByte(), 'W'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
    private const val FLAG_ENCRYPTED = 1
    private const val KDF_PBKDF2_SHA256 = 1
    private const val SALT_LEN = 16
    private const val NONCE_LEN = 12
    private const val TAG_BITS = 128

    private const val MANIFEST = "manifest.txt"
    private const val SECRETS = "secrets.txt"
    private const val PREFS_DIR = "prefs/"
    private const val FILES_DIR = "files/"
    private val WATCH_ID = Regex("^[A-Za-z0-9]{1,16}$")

    /** Başlık bilgisi (parola istemeden okunur). */
    class Header(val version: Int, val encrypted: Boolean, val iterations: Int, val salt: ByteArray, val nonce: ByteArray, val length: Int)

    fun fileName(year: Int, month: Int, day: Int): String =
        String.format(java.util.Locale.US, "finns-watch-yedek-%04d%02d%02d.fwb", year, month, day)

    // ---------- Yazma ----------

    /**
     * İçeriği dosya baytlarına çevirir. [password] verilirse şifreler. Kimlik içeren yedek parolasız yazılmaz.
     * Parola dizisine dokunulmaz; çağıran siler.
     */
    fun encode(
        c: BackupContents,
        password: CharArray?,
        random: SecureRandom = SecureRandom(),
        iterations: Int = ITERATIONS,
    ): ByteArray {
        if (password == null && (c.includesKeys || c.secrets.isNotEmpty() || hasCredentialPrefs(c))) {
            throw BackupException(BackupError.KEYS_NEED_PASSWORD)
        }
        if (password != null && password.size < MIN_PASSWORD) throw BackupException(BackupError.WEAK_PASSWORD)
        val zip = zip(c)
        if (password == null) return header(false, 0, ByteArray(0), ByteArray(0)) + zip
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS)
        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        val nonce = ByteArray(NONCE_LEN).also { random.nextBytes(it) }
        val head = header(true, iterations, salt, nonce)
        val key = derive(password, salt, iterations)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(head)
            return head + cipher.doFinal(zip)
        } finally {
            key.fill(0)
            zip.fill(0)
        }
    }

    private fun hasCredentialPrefs(c: BackupContents): Boolean =
        c.prefs[BackupPolicy.PREFS_MAIN]?.keys?.any { BackupPolicy.isCredential(it) } == true

    private fun header(encrypted: Boolean, iterations: Int, salt: ByteArray, nonce: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(MAGIC)
        out.write(VERSION)
        out.write(if (encrypted) FLAG_ENCRYPTED else 0)
        if (encrypted) {
            out.write(KDF_PBKDF2_SHA256)
            out.write(ByteBuffer.allocate(4).putInt(iterations).array())
            out.write(salt.size)
            out.write(salt)
            out.write(nonce.size)
            out.write(nonce)
        }
        return out.toByteArray()
    }

    private fun zip(c: BackupContents): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            fun put(name: String, data: ByteArray) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
            val manifest = buildString {
                append("format=").append(VERSION).append('\n')
                append("created=").append(c.createdAt).append('\n')
                append("app=").append(c.appVersion.replace('\n', ' ').replace('\r', ' ')).append('\n')
                append("keys=").append(if (c.includesKeys) 1 else 0).append('\n')
            }
            put(MANIFEST, manifest.toByteArray(Charsets.UTF_8))
            for ((name, map) in c.prefs) {
                require(name in BackupPolicy.PREF_FILES)
                put("$PREFS_DIR$name.txt", PrefCodec.encode(map).toByteArray(Charsets.UTF_8))
            }
            for ((path, data) in c.files.toSortedMap()) {
                require(BackupPolicy.isAllowedFile(path))
                put(FILES_DIR + path, data)
            }
            if (c.secrets.isNotEmpty()) {
                val sb = StringBuilder()
                for ((id, key) in c.secrets.toSortedMap()) {
                    require(WATCH_ID.matches(id))
                    sb.append(id).append('\t').append(hex(key)).append('\n')
                }
                val bytes = sb.toString().toByteArray(Charsets.UTF_8)
                sb.setLength(0)
                put(SECRETS, bytes)
                bytes.fill(0)
            }
        }
        return bos.toByteArray()
    }

    // ---------- Okuma ----------

    /** Başlığı denetler. Yedek değilse ya da sürüm yeniyse BackupException. */
    fun inspect(bytes: ByteArray): Header {
        if (bytes.size > MAX_FILE_BYTES) throw BackupException(BackupError.TOO_LARGE)
        if (bytes.size < 6 || !bytes.copyOfRange(0, 4).contentEquals(MAGIC)) throw BackupException(BackupError.NOT_BACKUP)
        val version = bytes[4].toInt() and 0xFF
        if (version > VERSION) throw BackupException(BackupError.NEWER_VERSION)
        if (version < 1) throw BackupException(BackupError.CORRUPT)
        val flags = bytes[5].toInt() and 0xFF
        if (flags and FLAG_ENCRYPTED.inv() != 0) throw BackupException(BackupError.NEWER_VERSION)
        if (flags and FLAG_ENCRYPTED == 0) return Header(version, false, 0, ByteArray(0), ByteArray(0), 6)
        var i = 6
        fun need(n: Int) { if (bytes.size < i + n) throw BackupException(BackupError.CORRUPT) }
        need(1)
        if (bytes[i++].toInt() and 0xFF != KDF_PBKDF2_SHA256) throw BackupException(BackupError.NEWER_VERSION)
        need(4)
        val iterations = ByteBuffer.wrap(bytes, i, 4).int
        i += 4
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) throw BackupException(BackupError.CORRUPT)
        need(1)
        val saltLen = bytes[i++].toInt() and 0xFF
        if (saltLen < 8) throw BackupException(BackupError.CORRUPT)
        need(saltLen)
        val salt = bytes.copyOfRange(i, i + saltLen)
        i += saltLen
        need(1)
        val nonceLen = bytes[i++].toInt() and 0xFF
        if (nonceLen != NONCE_LEN) throw BackupException(BackupError.CORRUPT)
        need(nonceLen)
        val nonce = bytes.copyOfRange(i, i + nonceLen)
        i += nonceLen
        if (bytes.size < i + TAG_BITS / 8) throw BackupException(BackupError.CORRUPT)
        return Header(version, true, iterations, salt, nonce, i)
    }

    /**
     * Dosyayı çözer ve doğrular. Şifreli dosyada parola yoksa NEED_PASSWORD, yanlışsa WRONG_PASSWORD.
     * Herhangi bir hatada hiçbir şey döndürülmez; çağıran mevcut veriyi değiştirmez.
     */
    fun decode(bytes: ByteArray, password: CharArray?): BackupContents {
        val h = inspect(bytes)
        val payload: ByteArray = if (!h.encrypted) {
            bytes.copyOfRange(h.length, bytes.size)
        } else {
            if (password == null || password.isEmpty()) throw BackupException(BackupError.NEED_PASSWORD)
            val key = derive(password, h.salt, h.iterations)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, h.nonce))
                cipher.updateAAD(bytes, 0, h.length)
                cipher.doFinal(bytes, h.length, bytes.size - h.length)
            } catch (e: AEADBadTagException) {
                throw BackupException(BackupError.WRONG_PASSWORD)
            } finally {
                key.fill(0)
            }
        }
        try {
            return unzip(payload, h.encrypted)
        } finally {
            if (h.encrypted) payload.fill(0)
        }
    }

    private fun unzip(payload: ByteArray, encrypted: Boolean): BackupContents {
        var manifest: Map<String, String>? = null
        val prefs = LinkedHashMap<String, Map<String, PrefValue>>()
        val files = LinkedHashMap<String, ByteArray>()
        val secrets = LinkedHashMap<String, ByteArray>()
        var total = 0L
        var count = 0
        try {
            ZipInputStream(ByteArrayInputStream(payload)).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (++count > MAX_ENTRIES) throw BackupException(BackupError.TOO_LARGE)
                    if (e.isDirectory) continue
                    val name = e.name
                    val data = readLimited(z)
                    total += data.size
                    if (total > MAX_TOTAL_BYTES) throw BackupException(BackupError.TOO_LARGE)
                    when {
                        name == MANIFEST -> {
                            if (manifest != null) throw BackupException(BackupError.CORRUPT)
                            manifest = parseManifest(String(data, Charsets.UTF_8))
                        }
                        name == SECRETS -> {
                            if (!encrypted) throw BackupException(BackupError.CORRUPT)
                            parseSecrets(data, secrets)
                            data.fill(0)
                        }
                        name.startsWith(PREFS_DIR) && name.endsWith(".txt") -> {
                            val file = name.substring(PREFS_DIR.length, name.length - 4)
                            if (file !in BackupPolicy.PREF_FILES || file in prefs) throw BackupException(BackupError.CORRUPT)
                            prefs[file] = PrefCodec.decode(String(data, Charsets.UTF_8))
                        }
                        name.startsWith(FILES_DIR) -> {
                            val path = name.substring(FILES_DIR.length)
                            if (!BackupPolicy.isAllowedFile(path) || path in files) throw BackupException(BackupError.CORRUPT)
                            files[path] = data
                        }
                        else -> throw BackupException(BackupError.CORRUPT)
                    }
                }
            }
        } catch (e: ZipException) {
            throw BackupException(BackupError.CORRUPT)
        } catch (e: java.io.IOException) {
            throw BackupException(BackupError.CORRUPT)
        }
        val m = manifest ?: throw BackupException(BackupError.NOT_BACKUP)
        val format = m["format"]?.toIntOrNull() ?: throw BackupException(BackupError.CORRUPT)
        if (format > VERSION) throw BackupException(BackupError.NEWER_VERSION)
        val created = m["created"]?.toLongOrNull() ?: throw BackupException(BackupError.CORRUPT)
        val keys = m["keys"] == "1"
        if (keys && !encrypted) throw BackupException(BackupError.CORRUPT)
        if (!keys && secrets.isNotEmpty()) throw BackupException(BackupError.CORRUPT)
        val main = prefs[BackupPolicy.PREFS_MAIN] ?: throw BackupException(BackupError.CORRUPT)
        if (!keys && main.keys.any { BackupPolicy.isCredential(it) }) throw BackupException(BackupError.CORRUPT)
        val watches = prefs[BackupPolicy.PREFS_WATCHES] ?: throw BackupException(BackupError.CORRUPT)
        if (watches.keys.any { it != BackupPolicy.WATCH_LIST_KEY }) throw BackupException(BackupError.CORRUPT)
        val listText = (watches[BackupPolicy.WATCH_LIST_KEY] as? PrefValue.Str)?.v
        if (listText != null) {
            val list = com.che.ls05.protocol.WatchListStorage.parse(listText) ?: throw BackupException(BackupError.CORRUPT)
            // Anahtar yalnızca listede olan Band 8 saatine ait olabilir.
            for (id in secrets.keys) {
                val w = list.find(id)
                if (w == null || w.type != com.che.ls05.protocol.WatchType.BAND8) throw BackupException(BackupError.CORRUPT)
            }
        } else if (secrets.isNotEmpty()) {
            throw BackupException(BackupError.CORRUPT)
        }
        return BackupContents(created, m["app"].orEmpty(), prefs, files, secrets, keys)
    }

    private fun readLimited(s: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var n = 0L
        while (true) {
            val r = s.read(buf)
            if (r < 0) break
            n += r
            if (n > MAX_ENTRY_BYTES) throw BackupException(BackupError.TOO_LARGE)
            out.write(buf, 0, r)
        }
        return out.toByteArray()
    }

    private fun parseManifest(text: String): Map<String, String> {
        val m = HashMap<String, String>()
        for (line in text.split('\n')) {
            if (line.isEmpty()) continue
            val eq = line.indexOf('=')
            if (eq <= 0) throw BackupException(BackupError.CORRUPT)
            m[line.substring(0, eq)] = line.substring(eq + 1)
        }
        return m
    }

    private fun parseSecrets(data: ByteArray, out: MutableMap<String, ByteArray>) {
        val text = String(data, Charsets.UTF_8)
        for (line in text.split('\n')) {
            if (line.isEmpty()) continue
            val p = line.split('\t')
            if (p.size != 2 || !WATCH_ID.matches(p[0]) || p[0] in out) throw BackupException(BackupError.CORRUPT)
            out[p[0]] = unhex(p[1]) ?: throw BackupException(BackupError.CORRUPT)
        }
    }

    // ---------- Yardımcılar ----------

    private fun derive(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun hex(b: ByteArray): String {
        val d = "0123456789abcdef"
        val c = CharArray(b.size * 2)
        for (i in b.indices) {
            val v = b[i].toInt() and 0xFF
            c[i * 2] = d[v ushr 4]
            c[i * 2 + 1] = d[v and 15]
        }
        return String(c)
    }

    private fun unhex(s: String): ByteArray? {
        if (s.isEmpty() || s.length % 2 != 0 || s.length > 128) return null
        if (!s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
