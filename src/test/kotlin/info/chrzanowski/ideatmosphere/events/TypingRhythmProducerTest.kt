package info.chrzanowski.ideatmosphere.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TypingRhythmProducerTest {

    private var nowNanos = 0L
    private val changes = ArrayList<Float>()
    private val producer = TypingRhythmProducer(nanoTime = { nowNanos }, onLevelChanged = { changes.add(it) })

    private fun advance(seconds: Double) {
        nowNanos += (seconds * 1_000_000_000L).toLong()
    }

    /** Types at [keysPerSecond] for [seconds], returning the level after the last keystroke. */
    private fun type(keysPerSecond: Double, seconds: Double): Float {
        val interval = 1.0 / keysPerSecond
        var level = 0f
        var elapsed = 0.0
        while (elapsed < seconds) {
            advance(interval)
            elapsed += interval
            level = producer.keystroke()
        }
        return level
    }

    @Test
    fun `starts at rest and ramps up with steady typing`() {
        assertEquals(0f, producer.level, 0f)
        assertTrue(producer.isSettled)

        val first = producer.keystroke()
        assertTrue("one keystroke is a gentle stir, not full flow", first > 0f && first < 0.3f)

        val afterOneSecond = type(TypingRhythmProducer.FULL_RATE.toDouble(), 1.0)
        val afterThreeSeconds = type(TypingRhythmProducer.FULL_RATE.toDouble(), 2.0)
        assertTrue("ramps up: $first < $afterOneSecond < $afterThreeSeconds", first < afterOneSecond && afterOneSecond < afterThreeSeconds)
        assertTrue("sustained full-rate typing reaches full flow, got $afterThreeSeconds", afterThreeSeconds > 0.9f)
        assertFalse(producer.isSettled)
    }

    @Test
    fun `level is clamped to one and never exceeds it when hammering keys`() {
        val level = type(40.0, 2.0)
        assertEquals(1f, level, 0f)
        assertEquals(1f, producer.sample(), 0f)
    }

    @Test
    fun `slow typing settles at a proportionally lower level`() {
        val half = type(TypingRhythmProducer.FULL_RATE / 2.0, 6.0)
        assertTrue("half the full rate gives roughly half the flow, got $half", half > 0.35f && half < 0.65f)
    }

    @Test
    fun `decays to zero after a pause and reports settled`() {
        type(TypingRhythmProducer.FULL_RATE.toDouble(), 3.0)
        val full = producer.level

        advance(1.0)
        val afterOne = producer.sample()
        advance(1.0)
        val afterTwo = producer.sample()
        assertTrue("decays monotonically: $full > $afterOne > $afterTwo > 0", full > afterOne && afterOne > afterTwo && afterTwo > 0f)
        assertFalse(producer.isSettled)

        advance(6.0)
        assertEquals(0f, producer.sample(), 0f)
        assertTrue(producer.isSettled)
        assertEquals("the last change is the settle to zero", 0f, changes.last(), 0f)
    }

    @Test
    fun `change callback fires only when the level moves`() {
        producer.sample()
        producer.sample()
        assertTrue(changes.isEmpty())

        producer.keystroke()
        assertEquals(1, changes.size)
        val afterSample = producer.sample()
        assertEquals("same instant, same level, no callback", 1, changes.size)
        assertEquals(producer.level, afterSample, 0f)

        advance(0.5)
        producer.sample()
        assertEquals(2, changes.size)
        assertTrue(changes[1] < changes[0])
    }

    @Test
    fun `reset drops to zero immediately and the next keystroke starts afresh`() {
        type(TypingRhythmProducer.FULL_RATE.toDouble(), 3.0)
        producer.reset()
        assertEquals(0f, producer.level, 0f)
        assertTrue(producer.isSettled)
        assertEquals(0f, changes.last(), 0f)

        advance(100.0)
        val fresh = producer.keystroke()
        assertTrue(fresh > 0f && fresh < 0.3f)
    }

    @Test
    fun `a long idle gap before the first sample does not blow up`() {
        advance(3600.0)
        assertEquals(0f, producer.sample(), 0f)
        nowNanos -= 5_000_000_000L // clock going backwards is treated as no time passed
        val level = producer.keystroke()
        assertTrue(level.isFinite() && level > 0f)
    }
}
