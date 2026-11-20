package com.che.ls05.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Saat değişim günlüğü (docs/COKLU_SAAT_TASARIM.md 5.3). */
class SwitchTimelineTest {
    private val log = "100\tls\n200\tb8\nbozuk satır\n300\tls\n"

    @Test
    fun activeWatchIsLastSwitchBefore() {
        val t = SwitchTimeline.parse(log)
        assertNull(t.activeAt(99), "ilk satırdan önce bilinmiyor")
        assertEquals("ls", t.activeAt(100))
        assertEquals("ls", t.activeAt(199))
        assertEquals("b8", t.activeAt(250))
        assertEquals("ls", t.activeAt(10_000))
    }

    @Test
    fun emptyLogKnowsNothing() {
        assertNull(SwitchTimeline.parse("").activeAt(5))
    }

    @Test
    fun unsortedLinesAreSorted() {
        assertEquals("b8", SwitchTimeline.parse("200\tb8\n100\tls\n").activeAt(250))
    }
}
