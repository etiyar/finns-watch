package com.che.ls05.protocol

import com.che.ls05.protocol.band8.B8Command
import com.che.ls05.protocol.band8.B8Messages
import com.che.ls05.protocol.band8.B8Workout
import com.che.ls05.protocol.band8.PbWriter
import com.che.ls05.protocol.band8.SaaMovement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Gizli antrenman komutu, ivme okuma ve hareket paketleri. Değerler uydurmadır. */
class SaaMovementTest {
    private fun kinds(l: List<SaaOut>) = l.map { it::class.simpleName }
    private fun a(x: Float, y: Float, z: Float) = B8Workout.Accel(x, y, z)

    @Test
    fun workoutStatusLayout() {
        val c = B8Command.parse(B8Workout.status(B8Workout.STATE_START, 1_700_000_000L, -12))!!
        assertTrue(c.matches(8, 26))
        val h = c.body(10)
        assertTrue(h != null, "health")
        val w = h!!.message(20)
        assertTrue(w != null, "workout")
        w!!
        assertTrue(w.message(2) != null, "tz")
        assertEquals(1_700_000_000L, w.uint(1))
        assertEquals(-12, w.message(2)!!.sint(1))
        assertEquals(810, w.int(3))
        assertEquals(0, w.int(4))
        assertEquals(3, w.int(6))
        assertEquals(3, B8Command.parse(B8Workout.status(B8Workout.STATE_FINISH, 1L, 0))!!.body(10)!!.message(20)!!.int(4))
    }

    private fun accelCmd(sub: Int, vararg s: Triple<Float, Float, Float>): B8Command {
        val sensor = PbWriter()
        for ((x, y, z) in s) sensor.message(5, PbWriter().uint(1, 1L).float(2, x).float(3, y).float(4, z))
        return B8Command.parse(B8Messages.command(8, sub).message(10, PbWriter().message(15, sensor)).toByteArray())!!
    }

    @Test
    fun accelSamplesAreReadAndOutliersDropped() {
        val c = accelCmd(99, Triple(10f, -20f, 1000f), Triple(5000f, 0f, 0f))
        assertTrue(B8Workout.hasSensor(c))
        assertEquals(listOf(a(10f, -20f, 1000f)), B8Workout.accel(c))
        val other = B8Command.parse(B8Messages.command(8, 47).toByteArray())!!
        assertFalse(B8Workout.hasSensor(other))
        assertTrue(B8Workout.accel(other).isEmpty())
    }

    @Test
    fun windowsAggregateMaxChangeAndBatch() {
        val m = SaaMovement()
        m.add(a(0f, 0f, 1000f), 0)
        m.add(a(100f, 0f, 1000f), 1_000)
        m.add(a(100f, 50f, 1000f), 2_000)
        assertNull(m.tick(5_000, 2, false))
        assertNull(m.tick(10_000, 2, false))
        m.add(a(100f, 50f, 1000f), 11_000)
        val b = m.tick(20_000, 2, false)!!
        assertEquals(2, b.max.size)
        assertTrue(kotlin.math.abs(100f * SaaMovement.MG_TO_MS2 - b.max[0]) < 0.0001f)
        assertEquals(0f, b.max[1])
        assertTrue(b.raw[0] > 9.7f && b.raw[0] < 10f)
    }

    @Test
    fun emptyWindowsAreSkippedButPauseSendsZeros() {
        val m = SaaMovement()
        m.tick(0, 1, false)
        assertNull(m.tick(10_000, 1, false))
        val z = m.tick(20_000, 1, true)!!
        assertEquals(listOf(0f), z.max.toList())
        assertEquals(listOf(0f), z.raw.toList())
    }

    @Test
    fun controllerStartsAndStopsMovementWithTracking() {
        val c = SaaController(moveEnabled = true)
        c.onBandReady(true, 0)
        assertEquals(listOf("ConfirmConnected", "HrStart", "MoveStart"), kinds(c.onStartTracking(0)))
        assertTrue(c.onAccel(listOf(a(0f, 0f, 1000f), a(10f, 0f, 1000f)), 1_000))
        val out = c.onTick(10_000)
        assertTrue(out.isEmpty() || out.none { it is SaaOut.SendMove })
        val stop = kinds(c.onStopTracking(12_000))
        assertEquals(listOf("HrStop", "SendMove", "MoveStop"), stop)
        assertFalse(c.onAccel(listOf(a(0f, 0f, 0f)), 13_000))
    }

    @Test
    fun movementKeepsRunningDuringPause() {
        val c = SaaController(moveEnabled = true)
        c.onBandReady(true, 0)
        c.onStartTracking(0)
        c.onBatchSize(1)
        assertEquals(listOf("HrStop"), kinds(c.onSuspended(true, 1_000)))
        assertTrue(c.moving)
        val send = c.onTick(10_000).filterIsInstance<SaaOut.SendMove>().single()
        assertEquals(listOf(0f), send.max.toList())
    }

    @Test
    fun movementOffByDefaultAndToggle() {
        val c = SaaController()
        c.onBandReady(true, 0)
        c.onStartTracking(0)
        assertFalse(c.moving)
        assertEquals(listOf("MoveStart"), kinds(c.onSettings(true, 1_000, move = true)))
        assertEquals(listOf("MoveStop"), kinds(c.onSettings(true, 2_000, move = false)))
    }

    @Test
    fun disconnectStopsMovementWithoutBandCommand() {
        val c = SaaController(moveEnabled = true)
        c.onBandReady(true, 0)
        c.onStartTracking(0)
        assertTrue(c.onBandReady(false, 1_000).none { it is SaaOut.MoveStop })
        assertFalse(c.moving)
        assertTrue(kinds(c.onBandReady(true, 2_000)).contains("MoveStart"))
    }
}
