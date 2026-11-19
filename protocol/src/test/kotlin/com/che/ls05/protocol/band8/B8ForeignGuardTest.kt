package com.che.ls05.protocol.band8

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** İkinci istemci izleri (#16): sentetik çerçeveler, gerçek log yok. */
class B8ForeignGuardTest {
    private val key = B8TestVectors.KEY

    @Test
    fun idleChannelDoesNotExpectAck() {
        val ch = B8Channel()
        assertFalse(ch.expects(B8Frame.Event.Ack))
        assertFalse(ch.expects(B8Frame.Event.Ready))
        assertFalse(ch.expects(B8Frame.Event.AllReceived))
    }

    @Test
    fun singleCommandInFlightExpectsOnlyAck() {
        val h = B8Harness(key, FakeBand(key))
        h.authenticate()
        h.ch.send(B8Messages.batteryRequest())
        assertTrue(h.ch.expects(B8Frame.Event.Ack))
        assertFalse(h.ch.expects(B8Frame.Event.Ready))
        assertFalse(h.ch.expects(B8Frame.Event.AllReceived))
    }

    @Test
    fun chunkedCommandExpectsReadyThenAllReceived() {
        val h = B8Harness(key, FakeBand(key))
        h.authenticate()
        val n = B8Notification("com.example.test", "Test", "T", "x".repeat(500), "20250101T093000", 1, "k")
        val first = h.ch.send(B8Messages.notification(n))
        assertTrue(h.ch.expects(B8Frame.Event.Ready))
        assertFalse(h.ch.expects(B8Frame.Event.Ack))
        h.ch.onNotify(B8Char.CMD_OUT, B8Frame.READY)
        assertTrue(h.ch.expects(B8Frame.Event.AllReceived))
        assertFalse(h.ch.expects(B8Frame.Event.Ready))
        assertEquals(1, first.size)
    }

    @Test
    fun ownAuthRepliesAreNotForeign() {
        val g = B8ForeignGuard()
        g.expectAuth(B8Sub.AUTH_NONCE)
        assertFalse(g.onAuthReply(B8Sub.AUTH_NONCE))
        g.expectAuth(B8Sub.AUTH_PROOF)
        assertFalse(g.onAuthReply(B8Sub.AUTH_PROOF))
    }

    @Test
    fun unsolicitedAuthProofBeforeOurAuthIsForeign() {
        val g = B8ForeignGuard()
        assertTrue(g.onAuthReply(B8Sub.AUTH_PROOF))
    }

    @Test
    fun secondAuthReplyAfterOursIsForeign() {
        val g = B8ForeignGuard()
        g.expectAuth(B8Sub.AUTH_NONCE)
        assertFalse(g.onAuthReply(B8Sub.AUTH_NONCE))
        assertTrue(g.onAuthReply(B8Sub.AUTH_NONCE))
    }

    @Test
    fun wrongSubtypeWhileWaitingIsForeign() {
        val g = B8ForeignGuard()
        g.expectAuth(B8Sub.AUTH_NONCE)
        assertTrue(g.onAuthReply(B8Sub.AUTH_PROOF))
    }

    @Test
    fun oneStrayControlFrameDoesNotTrigger() {
        val g = B8ForeignGuard()
        assertFalse(g.onUnexpectedControl(1_000))
        assertFalse(g.onUnexpectedControl(1_000 + B8ForeignGuard.CONTROL_WINDOW_MS + 1))
    }

    @Test
    fun twoUnexpectedControlFramesInWindowTrigger() {
        val g = B8ForeignGuard()
        assertFalse(g.onUnexpectedControl(1_000))
        assertTrue(g.onUnexpectedControl(1_600))
    }

    @Test
    fun reconnectAtMostOncePerFiveMinutes() {
        assertTrue(B8ForeignGuard.reconnectAllowed(nowMs = 10_000, lastReconnectMs = null))
        assertFalse(B8ForeignGuard.reconnectAllowed(nowMs = 10_000 + 299_999, lastReconnectMs = 10_000))
        assertTrue(B8ForeignGuard.reconnectAllowed(nowMs = 10_000 + 300_000, lastReconnectMs = 10_000))
    }

    @Test
    fun headerShowsOnlyControlBytesNeverPayload() {
        assertEquals("00 00 03 00", B8ForeignGuard.header(B8Frame.ACK))
        assertEquals("00 00 00 01 02 00", B8ForeignGuard.header(B8Frame.chunkStart(2, true)))
        val auth = B8Frame.plainSingle(byteArrayOf(0x08, 0x01, 0x10, 0x1b, 0x55, 0x66))
        assertEquals("00 00 02 02", B8ForeignGuard.header(auth))
        assertEquals("01 00", B8ForeignGuard.header(B8Frame.chunk(1, byteArrayOf(0x55, 0x66, 0x77))))
    }

    @Test
    fun noHeaderForEmptyFrame() {
        assertNull(B8ForeignGuard.headerOrNull(ByteArray(0)))
    }
}
