package info.chrzanowski.ideatmosphere.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdsrEnvelopeTest {

    @Test
    fun `full trigger decay sustain release cycle`() {
        val env = AdsrEnvelope(
            restLevel = 0f,
            attackSeconds = 1f,
            decaySeconds = 1f,
            sustainRatio = 0.5f,
            releaseSeconds = 1f
        )

        assertEquals(AdsrEnvelope.Phase.IDLE, env.phase)
        assertEquals(0f, env.value)

        // Trigger
        env.trigger(100f)
        assertEquals(AdsrEnvelope.Phase.ATTACK, env.phase)

        // Mid-attack
        env.advance(0.5f)
        assertEquals(50f, env.value, 0.001f)
        assertEquals(AdsrEnvelope.Phase.ATTACK, env.phase)

        // Reach peak, start decay
        env.advance(0.5f)
        assertEquals(100f, env.value, 0.001f)
        assertEquals(AdsrEnvelope.Phase.DECAY, env.phase)

        // Mid-decay
        env.advance(0.5f)
        assertEquals(75f, env.value, 0.001f) // 100 -> 50
        assertEquals(AdsrEnvelope.Phase.DECAY, env.phase)

        // Reach sustain
        env.advance(0.5f)
        assertEquals(50f, env.value, 0.001f)
        assertEquals(AdsrEnvelope.Phase.SUSTAIN, env.phase)

        // Hold sustain
        env.advance(10f)
        assertEquals(50f, env.value, 0.001f)
        assertEquals(AdsrEnvelope.Phase.SUSTAIN, env.phase)

        // Release
        env.release()
        assertEquals(AdsrEnvelope.Phase.RELEASE, env.phase)
        env.advance(0.5f)
        assertEquals(25f, env.value, 0.001f) // 50 -> 0

        // Reach idle
        env.advance(0.5f)
        assertEquals(0f, env.value, 0.001f)
        assertEquals(AdsrEnvelope.Phase.IDLE, env.phase)
    }

    @Test
    fun `retrigger mid-attack does not jump`() {
        val env = AdsrEnvelope(
            attackSeconds = 1f,
            decaySeconds = 1f,
            sustainRatio = 0.5f,
            releaseSeconds = 1f
        )

        env.trigger(100f)
        env.advance(0.5f)
        val valueBefore = env.value
        assertEquals(50f, valueBefore, 0.001f)

        env.trigger(200f)
        val valueAfter = env.value
        assertEquals(valueBefore, valueAfter, 0.000001f)
        assertEquals(AdsrEnvelope.Phase.ATTACK, env.phase)

        // Continues from 50 toward 200 over 1s
        env.advance(0.5f)
        assertEquals(125f, env.value, 0.001f)
    }

    @Test
    fun `retrigger mid-decay does not jump`() {
        val env = AdsrEnvelope(
            attackSeconds = 1f,
            decaySeconds = 1f,
            sustainRatio = 0.5f,
            releaseSeconds = 1f
        )

        env.trigger(100f)
        env.advance(1.5f) // 1s attack + 0.5s decay
        val valueBefore = env.value
        assertEquals(75f, valueBefore, 0.001f)

        env.trigger(200f)
        assertEquals(valueBefore, env.value, 0.000001f)
        assertEquals(AdsrEnvelope.Phase.ATTACK, env.phase)

        // Continues from 75 toward 200 over 1s
        env.advance(1f)
        assertEquals(200f, env.value, 0.001f)
    }

    @Test
    fun `release mid-attack works and does not jump`() {
        val env = AdsrEnvelope(
            attackSeconds = 1f,
            decaySeconds = 1f,
            sustainRatio = 0.5f,
            releaseSeconds = 1f
        )

        env.trigger(100f)
        env.advance(0.5f)
        val valueBefore = env.value
        assertEquals(50f, valueBefore, 0.001f)

        env.release()
        assertEquals(valueBefore, env.value, 0.000001f)
        assertEquals(AdsrEnvelope.Phase.RELEASE, env.phase)

        env.advance(0.5f)
        assertEquals(25f, env.value, 0.001f) // 50 -> 0 over 1s
    }

    @Test
    fun `advance with zero or negative delta is no-op`() {
        val env = AdsrEnvelope(
            attackSeconds = 1f,
            decaySeconds = 1f,
            sustainRatio = 0.5f,
            releaseSeconds = 1f
        )
        env.trigger(100f)
        env.advance(0.5f)
        val value = env.value
        val phase = env.phase

        env.advance(0f)
        assertEquals(value, env.value, 0f)
        assertEquals(phase, env.phase)

        env.advance(-1f)
        assertEquals(value, env.value, 0f)
        assertEquals(phase, env.phase)
    }

    @Test
    fun `zero durations result in instant jumps`() {
        val env = AdsrEnvelope(
            restLevel = 0f,
            attackSeconds = 0f,
            decaySeconds = 0f,
            sustainRatio = 0.5f,
            releaseSeconds = 0f
        )

        env.trigger(100f)
        assertEquals(AdsrEnvelope.Phase.SUSTAIN, env.phase)
        assertEquals(50f, env.value, 0.001f)

        env.release()
        assertEquals(AdsrEnvelope.Phase.IDLE, env.phase)
        assertEquals(0f, env.value, 0.001f)
    }

    @Test
    fun `values stay within bounds`() {
        val env = AdsrEnvelope(
            restLevel = 0f,
            attackSeconds = 1f,
            decaySeconds = 1f,
            sustainRatio = 0.8f,
            releaseSeconds = 1f
        )

        env.trigger(100f)
        repeat(100) {
            env.advance(0.1f)
            assertTrue("Value ${env.value} out of [0, 100]", env.value in 0f..100f)
        }
        env.release()
        repeat(100) {
            env.advance(0.1f)
            assertTrue("Value ${env.value} out of [0, 100]", env.value in 0f..100f)
        }
    }

    @Test
    fun `large deltaSeconds skip phases`() {
        val env = AdsrEnvelope(
            attackSeconds = 1f,
            decaySeconds = 1f,
            sustainRatio = 0.5f,
            releaseSeconds = 1f
        )

        env.trigger(100f)
        env.advance(10f)
        assertEquals(AdsrEnvelope.Phase.SUSTAIN, env.phase)
        assertEquals(50f, env.value, 0.001f)

        env.release()
        env.advance(10f)
        assertEquals(AdsrEnvelope.Phase.IDLE, env.phase)
        assertEquals(0f, env.value, 0.001f)
    }
}
