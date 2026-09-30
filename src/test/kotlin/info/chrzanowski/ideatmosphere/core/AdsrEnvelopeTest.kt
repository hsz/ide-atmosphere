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

    @Test
    fun `sustain interpolates from rest for peaks above and below rest`() {
        for (peak in listOf(200f, 20f)) {
            val env = AdsrEnvelope(100f, 1f, 1f, 0.25f, 1f)
            env.trigger(peak)
            env.advance(2f)

            val sustain = 100f + (peak - 100f) * 0.25f
            assertEquals(sustain, env.value, 0.001f)
            assertEquals(AdsrEnvelope.Phase.SUSTAIN, env.phase)
            env.advance(100f)
            assertEquals(sustain, env.value, 0.001f)

            env.release()
            assertEquals(sustain, env.value, 0f)
            env.advance(0.5f)
            assertEquals((sustain + 100f) / 2f, env.value, 0.001f)
            env.advance(0.5f)
            assertEquals(100f, env.value, 0.001f)
            assertEquals(AdsrEnvelope.Phase.IDLE, env.phase)
        }
    }

    @Test
    fun `configure idle preserves rest and uses new settings on trigger`() {
        val env = AdsrEnvelope(20f, 1f, 1f, 0.5f, 1f)
        env.configure(EnvelopeSettings(2f, 2f, 0.75f, 2f))
        assertEquals(20f, env.value, 0f)
        assertEquals(AdsrEnvelope.Phase.IDLE, env.phase)

        env.trigger(100f)
        env.advance(1f)
        assertEquals(60f, env.value, 0.001f)
        env.advance(1f)
        assertEquals(100f, env.value, 0.001f)
        env.advance(1f)
        assertEquals(90f, env.value, 0.001f)
        env.advance(1f)
        assertEquals(80f, env.value, 0.001f)
        env.release()
        env.advance(1f)
        assertEquals(50f, env.value, 0.001f)
    }

    @Test
    fun `configure attack restarts smoothly from current value`() {
        val env = AdsrEnvelope(20f, 1f, 1f, 0.5f, 1f)
        env.trigger(100f)
        env.advance(0.5f)
        env.configure(EnvelopeSettings(2f, 3f, 0.25f, 4f))
        assertEquals(60f, env.value, 0f)
        assertEquals(AdsrEnvelope.Phase.ATTACK, env.phase)

        env.advance(1f)
        assertEquals(80f, env.value, 0.001f)
        env.advance(1f)
        assertEquals(100f, env.value, 0.001f)
        env.advance(3f)
        assertEquals(40f, env.value, 0.001f)
        assertEquals(AdsrEnvelope.Phase.SUSTAIN, env.phase)
    }

    @Test
    fun `configure decay restarts smoothly toward new sustain`() {
        val env = AdsrEnvelope(20f, 1f, 1f, 0.5f, 1f)
        env.trigger(100f)
        env.advance(1.5f)
        env.configure(EnvelopeSettings(2f, 2f, 0.25f, 2f))
        assertEquals(80f, env.value, 0f)
        assertEquals(AdsrEnvelope.Phase.DECAY, env.phase)

        env.advance(1f)
        assertEquals(60f, env.value, 0.001f)
        env.advance(1f)
        assertEquals(40f, env.value, 0.001f)
        assertEquals(AdsrEnvelope.Phase.SUSTAIN, env.phase)
    }

    @Test
    fun `configure sustain smoothly decays to new sustain`() {
        val env = AdsrEnvelope(20f, 1f, 1f, 0.5f, 1f)
        env.trigger(100f)
        env.advance(2f)
        env.configure(EnvelopeSettings(2f, 2f, 0.25f, 2f))
        assertEquals(60f, env.value, 0f)
        assertEquals(AdsrEnvelope.Phase.DECAY, env.phase)

        env.advance(1f)
        assertEquals(50f, env.value, 0.001f)
        env.advance(1f)
        assertEquals(40f, env.value, 0.001f)
        env.advance(100f)
        assertEquals(40f, env.value, 0.001f)
        assertEquals(AdsrEnvelope.Phase.SUSTAIN, env.phase)
    }

    @Test
    fun `configure release restarts smoothly toward rest`() {
        val env = AdsrEnvelope(20f, 1f, 1f, 0.5f, 1f)
        env.trigger(100f)
        env.advance(2f)
        env.release()
        env.advance(0.5f)
        env.configure(EnvelopeSettings(2f, 2f, 0.25f, 2f))
        assertEquals(40f, env.value, 0f)
        assertEquals(AdsrEnvelope.Phase.RELEASE, env.phase)

        env.advance(1f)
        assertEquals(30f, env.value, 0.001f)
        env.advance(1f)
        assertEquals(20f, env.value, 0.001f)
        assertEquals(AdsrEnvelope.Phase.IDLE, env.phase)
    }

    @Test
    fun `equal settings do not restart any phase`() {
        val expected = mapOf(
            AdsrEnvelope.Phase.IDLE to 20f,
            AdsrEnvelope.Phase.ATTACK to 100f,
            AdsrEnvelope.Phase.DECAY to 60f,
            AdsrEnvelope.Phase.SUSTAIN to 60f,
            AdsrEnvelope.Phase.RELEASE to 20f
        )
        for ((phase, valueAfterAdvance) in expected) {
            val env = envelopeInPhase(phase)
            val valueBefore = env.value
            env.configure(EnvelopeSettings(1f, 1f, 0.5f, 1f))
            assertEquals(phase, env.phase)
            assertEquals(valueBefore, env.value, 0f)
            env.advance(0.5f)
            assertEquals(phase.name, valueAfterAdvance, env.value, 0.001f)
        }
    }

    @Test(timeout = 1000)
    fun `configure zero durations preserves value until next positive advance in every phase`() {
        for (phase in AdsrEnvelope.Phase.values()) {
            val env = envelopeInPhase(phase)
            val valueBefore = env.value
            env.configure(EnvelopeSettings(0f, 0f, 0.25f, 0f))
            assertEquals(phase.name, valueBefore, env.value, 0f)
            val configuredPhase = if (phase == AdsrEnvelope.Phase.SUSTAIN) AdsrEnvelope.Phase.DECAY else phase
            assertEquals(configuredPhase, env.phase)
            env.advance(0f)
            assertEquals(valueBefore, env.value, 0f)
            assertEquals(configuredPhase, env.phase)

            env.advance(0.1f)
            if (phase == AdsrEnvelope.Phase.IDLE || phase == AdsrEnvelope.Phase.RELEASE) {
                assertEquals(20f, env.value, 0f)
                assertEquals(AdsrEnvelope.Phase.IDLE, env.phase)
            } else {
                assertEquals(40f, env.value, 0f)
                assertEquals(AdsrEnvelope.Phase.SUSTAIN, env.phase)
            }
        }
    }

    @Test
    fun `configure takes a snapshot of mutable settings`() {
        val env = AdsrEnvelope(20f, 1f, 1f, 0.5f, 1f)
        val settings = EnvelopeSettings(2f, 2f, 0.25f, 2f)
        env.configure(settings)
        settings.attackSeconds = 0f
        settings.decaySeconds = 0f
        settings.sustainRatio = 1f
        settings.releaseSeconds = 0f

        env.trigger(100f)
        env.advance(1f)
        assertEquals(60f, env.value, 0.001f)
        env.advance(3f)
        assertEquals(40f, env.value, 0.001f)
        env.release()
        env.advance(1f)
        assertEquals(30f, env.value, 0.001f)
    }

    @Test
    fun `one shot automatically releases nonzero sustain to nonzero rest`() {
        val env = AdsrEnvelope(20f, 1f, 1f, 0.5f, 1f, oneShot = true)
        env.trigger(100f)
        env.advance(1f)
        assertEquals(100f, env.value, 0.001f)
        assertEquals(AdsrEnvelope.Phase.DECAY, env.phase)
        env.advance(1f)
        assertEquals(60f, env.value, 0.001f)
        assertEquals(AdsrEnvelope.Phase.RELEASE, env.phase)
        env.advance(0.5f)
        assertEquals(40f, env.value, 0.001f)
        env.advance(0.5f)
        assertEquals(20f, env.value, 0.001f)
        assertEquals(AdsrEnvelope.Phase.IDLE, env.phase)
    }

    @Test
    fun `one shot preserves default zero sustain glow decay timing`() {
        val env = AdsrEnvelope(0f, 0.1f, 1.5f, 0f, 0f, oneShot = true)
        env.trigger(100f)
        env.advance(0.1f)
        assertEquals(100f, env.value, 0.001f)
        assertEquals(AdsrEnvelope.Phase.DECAY, env.phase)
        env.advance(0.75f)
        assertEquals(50f, env.value, 0.001f)
        env.advance(0.75f)
        assertEquals(0f, env.value, 0f)
        assertEquals(AdsrEnvelope.Phase.IDLE, env.phase)
    }

    @Test
    fun `one shot retriggers and releases smoothly from every active phase`() {
        for (elapsed in listOf(0.5f, 1.5f, 2.5f)) {
            val env = AdsrEnvelope(20f, 1f, 1f, 0.5f, 1f, oneShot = true)
            env.trigger(100f)
            env.advance(elapsed)
            val valueBefore = env.value
            env.trigger(200f)
            assertEquals(valueBefore, env.value, 0f)
            assertEquals(AdsrEnvelope.Phase.ATTACK, env.phase)
            env.advance(0.5f)
            assertEquals((valueBefore + 200f) / 2f, env.value, 0.001f)
            env.advance(10f)
            assertEquals(20f, env.value, 0f)
            assertEquals(AdsrEnvelope.Phase.IDLE, env.phase)

            env.trigger(100f)
            env.advance(elapsed)
            env.release()
            assertEquals(valueBefore, env.value, 0f)
            assertEquals(AdsrEnvelope.Phase.RELEASE, env.phase)
            env.advance(0.5f)
            assertEquals((valueBefore + 20f) / 2f, env.value, 0.001f)
            env.advance(0.5f)
            assertEquals(20f, env.value, 0f)
            assertEquals(AdsrEnvelope.Phase.IDLE, env.phase)
        }
    }

    @Test(timeout = 1000)
    fun `one shot zero duration phases resolve even at exact boundaries`() {
        val env = AdsrEnvelope(20f, 1f, 0f, 0.5f, 1f, oneShot = true)
        env.trigger(100f)
        env.advance(1f)
        assertEquals(60f, env.value, 0f)
        assertEquals(AdsrEnvelope.Phase.RELEASE, env.phase)
        env.advance(1f)
        assertEquals(20f, env.value, 0f)
        assertEquals(AdsrEnvelope.Phase.IDLE, env.phase)

        val instant = AdsrEnvelope(20f, 0f, 0f, 0.5f, 0f, oneShot = true)
        repeat(2) {
            instant.trigger(100f)
            assertEquals(20f, instant.value, 0f)
            assertEquals(AdsrEnvelope.Phase.IDLE, instant.phase)
            instant.release()
            instant.advance(1f)
            assertEquals(20f, instant.value, 0f)
        }
    }

    @Test(timeout = 1000)
    fun `one shot configured to zero durations resolves on next advance`() {
        for (elapsed in listOf(0.5f, 1.5f, 2.5f)) {
            val env = AdsrEnvelope(20f, 1f, 1f, 0.5f, 1f, oneShot = true)
            env.trigger(100f)
            env.advance(elapsed)
            val valueBefore = env.value
            env.configure(EnvelopeSettings(0f, 0f, 0.75f, 0f))
            assertEquals(valueBefore, env.value, 0f)
            env.advance(0.1f)
            assertEquals(20f, env.value, 0f)
            assertEquals(AdsrEnvelope.Phase.IDLE, env.phase)
        }
    }

    @Test(timeout = 1000)
    fun `invalid deltas leave every phase unchanged`() {
        for (phase in AdsrEnvelope.Phase.values()) {
            val env = envelopeInPhase(phase)
            val valueBefore = env.value
            for (delta in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
                assertEquals(valueBefore, env.advance(delta), 0f)
                assertEquals(phase, env.phase)
            }
        }
    }

    @Test
    fun `constructor and configure reject nonfinite settings without changing state`() {
        val env = envelopeInPhase(AdsrEnvelope.Phase.ATTACK)
        val valueBefore = env.value
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val settings = listOf(
                EnvelopeSettings(invalid, 1f, 0.5f, 1f),
                EnvelopeSettings(1f, invalid, 0.5f, 1f),
                EnvelopeSettings(1f, 1f, invalid, 1f),
                EnvelopeSettings(1f, 1f, 0.5f, invalid)
            )
            for (config in settings) {
                assertInvalid {
                    AdsrEnvelope(20f, config.attackSeconds, config.decaySeconds, config.sustainRatio, config.releaseSeconds)
                }
                assertInvalid { env.configure(config) }
                assertEquals(valueBefore, env.value, 0f)
                assertEquals(AdsrEnvelope.Phase.ATTACK, env.phase)
            }
            assertInvalid { AdsrEnvelope(invalid, 1f, 1f, 0.5f, 1f) }
            assertInvalid { env.trigger(invalid) }
            assertInvalid { env.restLevel = invalid }
            assertEquals(valueBefore, env.value, 0f)
            assertEquals(20f, env.restLevel, 0f)
        }
        env.advance(0.5f)
        assertEquals(100f, env.value, 0f)
        assertEquals(AdsrEnvelope.Phase.DECAY, env.phase)
    }

    @Test
    fun `sustain must be bounded and finite negative durations remain instantaneous`() {
        for (invalid in listOf(-0.1f, 1.1f)) {
            assertInvalid { AdsrEnvelope(20f, 1f, 1f, invalid, 1f) }
            val env = envelopeInPhase(AdsrEnvelope.Phase.SUSTAIN)
            assertInvalid { env.configure(EnvelopeSettings(1f, 1f, invalid, 1f)) }
            assertEquals(60f, env.value, 0f)
            assertEquals(AdsrEnvelope.Phase.SUSTAIN, env.phase)
        }

        val env = AdsrEnvelope(20f, -1f, -1f, 0.5f, -1f)
        env.trigger(100f)
        assertEquals(60f, env.value, 0f)
        assertEquals(AdsrEnvelope.Phase.SUSTAIN, env.phase)
        env.configure(EnvelopeSettings(-2f, -2f, 0.25f, -2f))
        assertEquals(60f, env.value, 0f)
        env.advance(0.1f)
        assertEquals(40f, env.value, 0f)
        env.release()
        assertEquals(20f, env.value, 0f)
        assertEquals(AdsrEnvelope.Phase.IDLE, env.phase)
    }

    @Test
    fun `finite extreme levels interpolate without overflow`() {
        val env = AdsrEnvelope(-Float.MAX_VALUE, 1f, 1f, 0.5f, 1f)
        env.trigger(Float.MAX_VALUE)
        env.advance(0.5f)
        assertEquals(0f, env.value, 0f)
        env.advance(1.5f)
        assertEquals(0f, env.value, 0f)
        env.release()
        env.advance(0.5f)
        assertEquals(-Float.MAX_VALUE / 2f, env.value, 0f)
    }

    private fun envelopeInPhase(phase: AdsrEnvelope.Phase): AdsrEnvelope {
        val env = AdsrEnvelope(20f, 1f, 1f, 0.5f, 1f)
        when (phase) {
            AdsrEnvelope.Phase.IDLE -> Unit
            AdsrEnvelope.Phase.ATTACK -> {
                env.trigger(100f)
                env.advance(0.5f)
            }
            AdsrEnvelope.Phase.DECAY -> {
                env.trigger(100f)
                env.advance(1.5f)
            }
            AdsrEnvelope.Phase.SUSTAIN -> {
                env.trigger(100f)
                env.advance(2f)
            }
            AdsrEnvelope.Phase.RELEASE -> {
                env.trigger(100f)
                env.advance(2f)
                env.release()
                env.advance(0.5f)
            }
        }
        assertEquals(phase, env.phase)
        return env
    }

    private fun assertInvalid(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
