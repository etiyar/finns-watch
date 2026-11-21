package com.che.ls05.protocol.backup

import com.che.ls05.protocol.WatchList
import com.che.ls05.protocol.WatchListStorage
import com.che.ls05.protocol.WatchType
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Yedek biçimi, şifreleme ve doğrulama. Anahtar ve kimlikler uydurmadır. */
class BackupFormatTest {

    private val it2 = BackupFormat.MIN_ITERATIONS
    private val fakeKey = ByteArray(16) { (it * 7 + 3).toByte() }

    private fun list(): String {
        var l = WatchList.EMPTY
        l = l.add(WatchType.LS05, "Kol", 1L).first
        l = l.add(WatchType.BAND8, "Bant", 2L).first
        return WatchListStorage.encode(l.withActive("w2"))
    }

    private fun mainPrefs() = mapOf(
        "forwarding" to PrefValue.Bool(true),
        "themeMode" to PrefValue.Int32(2),
        "lastSync" to PrefValue.Int64(5L),
        "userId" to PrefValue.Str("123456"),
        "d.w2.alarmNote1" to PrefValue.Str("ilaç"),
        "d.w2.lastSeen" to PrefValue.Str("x"),
        "seenApps" to PrefValue.StrSet(setOf("a.b", "c\td")),
        "weatherCache" to PrefValue.Str("{}"),
        "batteryLowAt" to PrefValue.Real(1.5f),
        "serviceEnabled" to PrefValue.Bool(true),
    )

    private fun contents(keys: Boolean) = BackupContents(
        createdAt = 1_700_000_000_000L,
        appVersion = "0.17.0-alpha1",
        prefs = mapOf(
            BackupPolicy.PREFS_MAIN to BackupPolicy.filterMain(mainPrefs(), keys),
            BackupPolicy.PREFS_WATCHES to BackupPolicy.filterWatches(
                mapOf("list" to PrefValue.Str(list()), "w.w2.auth" to PrefValue.Str("1:a:b")),
            ),
        ),
        files = mapOf("faces/100.face" to byteArrayOf(1, 2, 3), "band8faces/42.bin" to byteArrayOf(9)),
        secrets = if (keys) mapOf("w2" to fakeKey.copyOf()) else emptyMap(),
        includesKeys = keys,
    )

    @Test
    fun plainRoundTripKeepsSettingsAndFiles() {
        val bytes = BackupFormat.encode(contents(false), null)
        assertFalse(BackupFormat.inspect(bytes).encrypted)
        val d = BackupFormat.decode(bytes, null)
        val main = d.prefs.getValue(BackupPolicy.PREFS_MAIN)
        assertEquals(PrefValue.Bool(true), main["forwarding"])
        assertEquals(PrefValue.StrSet(setOf("a.b", "c\td")), main["seenApps"])
        assertEquals(PrefValue.Real(1.5f), main["batteryLowAt"])
        assertContentEquals(byteArrayOf(1, 2, 3), d.files["faces/100.face"])
        assertEquals(0, d.secrets.size)
        assertFalse(d.includesKeys)
    }

    @Test
    fun plainBackupNeverHoldsKeysOrRuntimeState() {
        val main = contents(false).prefs.getValue(BackupPolicy.PREFS_MAIN)
        assertFalse("userId" in main, "kimlik düz yedekte olmamalı")
        assertFalse("lastSync" in main)
        assertFalse("d.w2.lastSeen" in main, "konum yedeğe girmez")
        assertFalse("weatherCache" in main)
        assertFalse("serviceEnabled" in main)
        assertTrue("d.w2.alarmNote1" in main)
        val w = contents(false).prefs.getValue(BackupPolicy.PREFS_WATCHES)
        assertEquals(setOf("list"), w.keys)
        val text = String(BackupFormat.encode(contents(false), null), Charsets.ISO_8859_1)
        assertFalse(text.contains("secrets.txt"))
    }

    @Test
    fun keysWithoutPasswordRefused() {
        val e = assertFails { BackupFormat.encode(contents(true), null) }
        assertEquals(BackupError.KEYS_NEED_PASSWORD, e.error)
    }

    @Test
    fun shortPasswordRefused() {
        val e = assertFails { BackupFormat.encode(contents(true), "kisa".toCharArray(), iterations = it2) }
        assertEquals(BackupError.WEAK_PASSWORD, e.error)
    }

    @Test
    fun encryptedRoundTripRestoresKeys() {
        val pw = "dogru-parola".toCharArray()
        val bytes = BackupFormat.encode(contents(true), pw, SecureRandom(), it2)
        val h = BackupFormat.inspect(bytes)
        assertTrue(h.encrypted)
        assertEquals(it2, h.iterations)
        assertEquals(16, h.salt.size)
        val d = BackupFormat.decode(bytes, pw)
        assertTrue(d.includesKeys)
        assertContentEquals(fakeKey, d.secrets["w2"])
        assertEquals(PrefValue.Str("123456"), d.prefs.getValue(BackupPolicy.PREFS_MAIN)["userId"])
        // Şifreli dosyada anahtar ve dosya adları düz görünmez.
        val raw = String(bytes, Charsets.ISO_8859_1)
        assertFalse(raw.contains(BackupFormat.hex(fakeKey)))
        assertFalse(raw.contains("manifest"))
    }

    @Test
    fun wrongPasswordFailsClearly() {
        val bytes = BackupFormat.encode(contents(true), "dogru-parola".toCharArray(), SecureRandom(), it2)
        assertEquals(BackupError.WRONG_PASSWORD, assertFails { BackupFormat.decode(bytes, "yanlis-parola".toCharArray()) }.error)
        assertEquals(BackupError.NEED_PASSWORD, assertFails { BackupFormat.decode(bytes, null) }.error)
    }

    @Test
    fun saltAndNonceAreRandom() {
        val pw = "dogru-parola".toCharArray()
        val a = BackupFormat.inspect(BackupFormat.encode(contents(false), pw, SecureRandom(), it2))
        val b = BackupFormat.inspect(BackupFormat.encode(contents(false), pw, SecureRandom(), it2))
        assertFalse(a.salt.contentEquals(b.salt))
        assertFalse(a.nonce.contentEquals(b.nonce))
    }

    @Test
    fun tamperedHeaderOrBodyRejected() {
        val pw = "dogru-parola".toCharArray()
        val bytes = BackupFormat.encode(contents(true), pw, SecureRandom(), it2)
        val h = BackupFormat.inspect(bytes)
        val body = bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertEquals(BackupError.WRONG_PASSWORD, assertFails { BackupFormat.decode(body, pw) }.error)
        // Tuzun bir baytı değişirse anahtar da değişir; doğrulama yine düşer.
        val salt = bytes.copyOf().also { it[h.length - 13 - 1] = (it[h.length - 13 - 1] + 1).toByte() }
        assertEquals(BackupError.WRONG_PASSWORD, assertFails { BackupFormat.decode(salt, pw) }.error)
    }

    @Test
    fun headerValidation() {
        assertEquals(BackupError.NOT_BACKUP, assertFails { BackupFormat.inspect("PK\u0003\u0004xx".toByteArray()) }.error)
        val plain = BackupFormat.encode(contents(false), null)
        val newer = plain.copyOf().also { it[4] = 9 }
        assertEquals(BackupError.NEWER_VERSION, assertFails { BackupFormat.inspect(newer) }.error)
        val flags = plain.copyOf().also { it[5] = 4 }
        assertEquals(BackupError.NEWER_VERSION, assertFails { BackupFormat.inspect(flags) }.error)
        val trunc = BackupFormat.encode(contents(false), "dogru-parola".toCharArray(), SecureRandom(), it2).copyOf(20)
        assertEquals(BackupError.CORRUPT, assertFails { BackupFormat.inspect(trunc) }.error)
        val lowIter = BackupFormat.encode(contents(false), "dogru-parola".toCharArray(), SecureRandom(), it2)
            .also { it[7] = 0; it[8] = 0; it[9] = 0; it[10] = 1 }
        assertEquals(BackupError.CORRUPT, assertFails { BackupFormat.inspect(lowIter) }.error)
    }

    private fun plainWith(entries: Map<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((n, t) in entries) { z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() }
        }
        return byteArrayOf('F'.code.toByte(), 'W'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte(), 1, 0) + bos.toByteArray()
    }

    private val okManifest = "format=1\ncreated=1\napp=x\nkeys=0\n"
    private val okPrefs = mapOf("manifest.txt" to okManifest, "prefs/ls05.txt" to "", "prefs/watches.txt" to "")

    @Test
    fun forgedPlainArchivesRejected() {
        BackupFormat.decode(plainWith(okPrefs), null)
        assertEquals(BackupError.CORRUPT, assertFails { BackupFormat.decode(plainWith(okPrefs + ("secrets.txt" to "w2\t00")), null) }.error)
        assertEquals(BackupError.CORRUPT, assertFails { BackupFormat.decode(plainWith(okPrefs + ("files/../x" to "a")), null) }.error)
        assertEquals(BackupError.CORRUPT, assertFails { BackupFormat.decode(plainWith(okPrefs + ("files/faces/a.sh" to "a")), null) }.error)
        assertEquals(BackupError.CORRUPT, assertFails { BackupFormat.decode(plainWith(okPrefs + ("health/x.log" to "a")), null) }.error)
        assertEquals(BackupError.CORRUPT, assertFails { BackupFormat.decode(plainWith(okPrefs + ("manifest.txt" to okManifest.replace("keys=0", "keys=1"))), null) }.error)
        assertEquals(BackupError.NEWER_VERSION, assertFails { BackupFormat.decode(plainWith(okPrefs + ("manifest.txt" to "format=2\ncreated=1\n")), null) }.error)
        assertEquals(BackupError.NOT_BACKUP, assertFails { BackupFormat.decode(plainWith(okPrefs - "manifest.txt"), null) }.error)
        val withId = "s\t" + b64("userId") + "\t" + b64("1") + "\n"
        assertEquals(BackupError.CORRUPT, assertFails { BackupFormat.decode(plainWith(okPrefs + ("prefs/ls05.txt" to withId)), null) }.error)
        assertEquals(BackupError.CORRUPT, assertFails { BackupFormat.decode(plainWith(okPrefs + ("prefs/ls05.txt" to "zz")), null) }.error)
    }

    private fun b64(s: String) = java.util.Base64.getEncoder().encodeToString(s.toByteArray())

    @Test
    fun secretForUnknownWatchRejected() {
        val c = contents(true)
        val bad = BackupContents(c.createdAt, c.appVersion, c.prefs, c.files, mapOf("w9" to fakeKey.copyOf()), true)
        val pw = "dogru-parola".toCharArray()
        val bytes = BackupFormat.encode(bad, pw, SecureRandom(), it2)
        assertEquals(BackupError.CORRUPT, assertFails { BackupFormat.decode(bytes, pw) }.error)
    }

    @Test
    fun filePolicy() {
        assertTrue(BackupPolicy.isAllowedFile("faces/1700000000000.face"))
        assertTrue(BackupPolicy.isAllowedFile("band8faces/42.png"))
        assertFalse(BackupPolicy.isAllowedFile("faces/../../x.face"))
        assertFalse(BackupPolicy.isAllowedFile("faces/sub/1.face"))
        assertFalse(BackupPolicy.isAllowedFile("health/2025-01-01.log"))
        assertFalse(BackupPolicy.isAllowedFile("band8faces/1.face"))
    }

    @Test
    fun mergeKeepsRuntimeAndCurrentIdsWhenBackupHasNoKeys() {
        val current = mapOf(
            "userId" to PrefValue.Str("999"),
            "serviceEnabled" to PrefValue.Bool(true),
            "forwarding" to PrefValue.Bool(false),
            "vibrate" to PrefValue.Bool(false),
        )
        val backup = mapOf("forwarding" to PrefValue.Bool(true), "userId" to PrefValue.Str("111"))
        val m = BackupPolicy.mergeMain(current, backup, backupHasKeys = false)
        assertEquals(PrefValue.Str("999"), m["userId"])
        assertEquals(PrefValue.Bool(true), m["serviceEnabled"])
        assertEquals(PrefValue.Bool(true), m["forwarding"])
        assertFalse("vibrate" in m, "yedekte olmayan ayar silinir")
        val k = BackupPolicy.mergeMain(current, backup, backupHasKeys = true)
        assertEquals(PrefValue.Str("111"), k["userId"])
    }

    @Test
    fun namespacedKeysClassified() {
        assertEquals("userId", BackupPolicy.baseName("d.w3.userId"))
        assertTrue(BackupPolicy.isCredential("d.w3.userId"))
        assertTrue(BackupPolicy.isRuntime("d.w3.reminderDay2"))
        assertTrue(BackupPolicy.isRuntime("customSent:4"))
        assertFalse(BackupPolicy.isRuntime("d.w3.alarmNote2"))
        assertFalse(BackupPolicy.isRuntime("d.w3.sync"))
    }

    @Test
    fun summaryCountsWithoutSecrets() {
        val s = BackupSummary.of(BackupFormat.decode(BackupFormat.encode(contents(false), null), null))
        assertEquals(listOf("Kol (LS05)", "Bant (Band 8)"), s.watchNames)
        assertEquals(1, s.alarmNotes)
        assertEquals(1, s.ls05Faces)
        assertEquals(1, s.band8Faces)
        assertEquals(0, s.bandKeys)
        assertFalse(s.includesKeys)
        val pw = "dogru-parola".toCharArray()
        val k = BackupSummary.of(BackupFormat.decode(BackupFormat.encode(contents(true), pw, SecureRandom(), it2), pw))
        assertEquals(1, k.bandKeys)
        assertEquals(1, k.ls05Ids)
        assertFalse(k.lines().joinToString().contains("123456"))
        assertFalse(k.toString().contains(BackupFormat.hex(fakeKey)))
    }

    @Test
    fun fileNameFormat() {
        assertEquals("finns-watch-yedek-20261121.fwb", BackupFormat.fileName(2026, 11, 21))
    }

    private fun assertFails(block: () -> Unit): BackupException {
        try {
            block()
        } catch (e: BackupException) {
            return e
        }
        throw AssertionError("BackupException bekleniyordu")
    }
}
