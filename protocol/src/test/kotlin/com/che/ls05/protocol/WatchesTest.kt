package com.che.ls05.protocol

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** v0.12 çoklu saat çekirdeği: kayıt, göç, geçiş kuralları, saat dilimi, gizli zarf. */
class WatchesTest {

    private val now = 1_763_400_000_000L

    // ---------- Göç ----------

    @Test
    fun migrationFreshInstallIsEmpty() {
        val (list, src) = RegistryMigration.load(null, null, null, now)
        assertEquals(RegistryMigration.Source.MIGRATED, src)
        assertTrue(list.watches.isEmpty())
        assertNull(list.activeId)
    }

    @Test
    fun migrationWithLegacyAddressMakesLegacyLs05() {
        val (list, src) = RegistryMigration.load(null, "addr", null, now)
        assertEquals(RegistryMigration.Source.MIGRATED, src)
        assertEquals(1, list.watches.size)
        val w = list.watches[0]
        assertEquals(WatchList.LEGACY_ID, w.id)
        assertEquals(WatchType.LS05, w.type)
        assertEquals("", w.ns)
        assertTrue(w.legacy)
        assertEquals(w.id, list.activeId)
    }

    @Test
    fun migrationWithOnlyUserIdMakesLegacyLs05() {
        val (list, _) = RegistryMigration.load(null, null, "123", now)
        assertEquals(WatchList.LEGACY_ID, list.activeId)
        val (blank, _) = RegistryMigration.load(null, "", "  ", now)
        assertTrue(blank.watches.isEmpty())
    }

    @Test
    fun migrationSecondRunKeepsStoredList() {
        val (first, _) = RegistryMigration.load(null, "addr", null, now)
        val text = first.serialize()
        // Eski ayarlar sonradan değişse de kayıt yeniden kurulmaz.
        val (second, src) = RegistryMigration.load(text, null, null, now + 1)
        assertEquals(RegistryMigration.Source.LOADED, src)
        assertEquals(first, second)
    }

    @Test
    fun migrationCorruptRegistryRebuildsFromLegacy() {
        val (list, src) = RegistryMigration.load("çöp\tveri", "addr", null, now)
        assertEquals(RegistryMigration.Source.REBUILT, src)
        assertEquals(WatchList.LEGACY_ID, list.activeId)
        val (wrongVersion, src2) = RegistryMigration.load("v=99\nactive=\nnext=2\n", null, null, now)
        assertEquals(RegistryMigration.Source.REBUILT, src2)
        assertTrue(wrongVersion.watches.isEmpty())
    }

    @Test
    fun legacyKeysAreUnchanged() {
        val (list, _) = RegistryMigration.load(null, "addr", null, now)
        val ns = list.active!!.ns
        for (k in listOf("address", "userId", "sync", "alarmNote1", "alarmVisible3", "currentFace", "lastSeen", "lostSince")) {
            assertEquals(k, PrefKeys.key(ns, k))
        }
    }

    // ---------- Kayıt işlemleri ----------

    @Test
    fun addSecondWatchGetsPrefix() {
        val (start, _) = RegistryMigration.load(null, "addr", null, now)
        val (l2, band) = start.add(WatchType.BAND8, "Band", now)
        assertEquals("w2", band.id)
        assertEquals("d.w2.", band.ns)
        assertEquals("d.w2.address", PrefKeys.key(band.ns, "address"))
        val (l3, ls) = l2.add(WatchType.LS05, "İkinci LS05", now)
        assertEquals("w3", ls.id)
        assertEquals(4, l3.next)
        assertEquals(WatchList.LEGACY_ID, l3.activeId)
    }

    @Test
    fun firstLs05OnEmptyListIsLegacy() {
        val (l, rec) = WatchList.EMPTY.add(WatchType.LS05, "", now)
        assertEquals(WatchList.LEGACY_ID, rec.id)
        assertEquals("", rec.ns)
        assertEquals("Haylou LS05", rec.name)
        assertNull(l.activeId)
        val (_, band) = WatchList.EMPTY.add(WatchType.BAND8, "B", now)
        assertEquals("d.w2.", band.ns)
    }

    @Test
    fun serializeRoundTripAndNameCleaning() {
        val (a, _) = WatchList.EMPTY.add(WatchType.LS05, "  Ad\tile\nsekme  ", now)
        val (b, band) = a.add(WatchType.BAND8, "x".repeat(50), now)
        val c = b.withActive(band.id).rename(WatchList.LEGACY_ID, "Yeni ad")
        val back = WatchList.deserialize(c.serialize())
        assertEquals(c, back)
        assertEquals("Ad ile sekme", a.watches[0].name)
        assertEquals(WatchList.MAX_NAME, band.name.length)
        assertEquals("Yeni ad", back!!.find(WatchList.LEGACY_ID)!!.name)
    }

    @Test
    fun activeCannotBeRemovedAndUnknownActiveIgnored() {
        val (a, _) = WatchList.EMPTY.add(WatchType.LS05, "L", now)
        val (b, band) = a.add(WatchType.BAND8, "B", now)
        val c = b.withActive(WatchList.LEGACY_ID)
        assertEquals(c, c.remove(WatchList.LEGACY_ID))
        assertEquals(1, c.remove(band.id).watches.size)
        assertEquals(c, c.withActive("yok"))
        assertNull(WatchList.deserialize("v=1\nactive=yok\nnext=2\n")!!.activeId)
    }

    // ---------- Geçiş ----------

    @Test
    fun switchDecisions() {
        val (a, _) = WatchList.EMPTY.add(WatchType.LS05, "L", now)
        val (b, band) = a.add(WatchType.BAND8, "B", now)
        val l = b.withActive(WatchList.LEGACY_ID)
        assertEquals(SwitchRules.Decision.PROCEED, SwitchRules.decide(l, band.id, busy = false))
        assertEquals(SwitchRules.Decision.BUSY, SwitchRules.decide(l, band.id, busy = true))
        assertEquals(SwitchRules.Decision.SAME, SwitchRules.decide(l, WatchList.LEGACY_ID, busy = true))
        assertEquals(SwitchRules.Decision.UNKNOWN, SwitchRules.decide(l, "w9", busy = false))
    }

    @Test
    fun lostAlertNotArmedOnSwitch() {
        assertTrue(SwitchRules.armLostAlert(switching = false, stopping = false, userInitiated = false, hadLink = true))
        assertFalse(SwitchRules.armLostAlert(switching = true, stopping = false, userInitiated = false, hadLink = true))
        assertFalse(SwitchRules.armLostAlert(switching = false, stopping = true, userInitiated = false, hadLink = true))
        assertFalse(SwitchRules.armLostAlert(switching = false, stopping = false, userInitiated = true, hadLink = true))
        assertFalse(SwitchRules.armLostAlert(switching = false, stopping = false, userInitiated = false, hadLink = false))
    }

    // ---------- Yetenekler ----------

    @Test
    fun capabilities() {
        val l = Capabilities.of(WatchType.LS05)
        assertEquals(Capabilities.LS05, l)
        assertEquals(3, l.alarmSlots)
        assertTrue(l.faces && l.alarmNote && l.alarmVibration && l.weather && l.profile)
        assertEquals(Capabilities.LS05, Capabilities.of(null))
        val b = Capabilities.of(WatchType.BAND8)
        assertFalse(b.faces || b.alarmNote || b.alarmVibration)
        assertTrue(Capabilities.MAX_ALARM_SLOTS >= l.alarmSlots)
    }

    // ---------- Saat dilimi ----------

    @Test
    fun localWallUsesRecordedZone() {
        // 2026-03-29 00:30 UTC; kayıt UTC+3 (12 çeyrek).
        val t = 1_774_744_200L
        assertEquals(LocalDateTime.of(2026, 3, 29, 3, 30), LocalWall.of(t, 12, ZoneId.of("UTC")))
        assertEquals(-12, LocalWall.quarterFromByte(0xF4))
        assertEquals(12, LocalWall.quarterFromByte(0x0C))
    }

    @Test
    fun localWallZoneChangeDay() {
        // Londra, 2026-03-29: 01:00 UTC'de yaz saatine geçiş. Kayıt kendi farkını taşır: önce 0, sonra +1 saat (4 çeyrek).
        val before = 1_774_744_200L // 00:30 UTC
        val after = before + 3_600 // 01:30 UTC
        assertEquals(LocalDateTime.of(2026, 3, 29, 0, 30), LocalWall.of(before, 0, ZoneId.of("UTC")))
        assertEquals(LocalDateTime.of(2026, 3, 29, 2, 30), LocalWall.of(after, 4, ZoneId.of("UTC")))
        // Saat dilimi yoksa telefonun bölgesi o anki kuralıyla.
        val london = ZoneId.of("Europe/London")
        assertEquals(LocalDateTime.of(2026, 3, 29, 0, 30), LocalWall.of(before, null, london))
        assertEquals(LocalDateTime.of(2026, 3, 29, 2, 30), LocalWall.of(after, null, london))
        // Geçersiz bayt: telefona düşer.
        assertEquals(LocalDateTime.of(2026, 3, 29, 2, 30), LocalWall.of(after, 100, london))
        // Yolculuk: aynı gün İstanbul'dan (UTC+3) Londra'ya (UTC+1).
        assertEquals(LocalDateTime.of(2026, 3, 29, 4, 30), LocalWall.of(after, 12, london))
    }

    // ---------- Gizli zarf ----------

    @Test
    fun secretEnvelopeRoundTrip() {
        val iv = ByteArray(12) { it.toByte() }
        val ct = ByteArray(32) { (it * 7).toByte() }
        val s = SecretEnvelope.encode(iv, ct)
        assertTrue(s.startsWith("1:"))
        val (iv2, ct2) = SecretEnvelope.decode(s)!!
        assertTrue(iv.contentEquals(iv2) && ct.contentEquals(ct2))
        assertNull(SecretEnvelope.decode(null))
        assertNull(SecretEnvelope.decode("2:AA==:AA=="))
        assertNull(SecretEnvelope.decode("1:@@:AA=="))
        assertNull(SecretEnvelope.decode("1::AA=="))
    }

    @Test
    fun keyHexValidation() {
        val k = SecretEnvelope.parseKeyHex("0x000102030405060708090a0b0c0d0e0F")!!
        assertEquals(16, k.size)
        assertEquals(15, k[15].toInt())
        assertEquals(16, SecretEnvelope.parseKeyHex(" 00010203 04050607 08090a0b 0c0d0e0f ")!!.size)
        assertNull(SecretEnvelope.parseKeyHex("0001"))
        assertNull(SecretEnvelope.parseKeyHex("zz0102030405060708090a0b0c0d0e0f"))
        assertNull(SecretEnvelope.parseKeyHex(""))
    }
}
