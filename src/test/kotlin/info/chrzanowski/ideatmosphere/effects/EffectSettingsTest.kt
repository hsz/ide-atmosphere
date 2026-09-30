package info.chrzanowski.ideatmosphere.effects

import info.chrzanowski.ideatmosphere.core.AtmosphereEffect
import info.chrzanowski.ideatmosphere.core.AtmosphereEvent
import info.chrzanowski.ideatmosphere.core.AtmosphereReactionProfile
import info.chrzanowski.ideatmosphere.core.EffectContext
import info.chrzanowski.ideatmosphere.core.EnvelopeSettings
import info.chrzanowski.ideatmosphere.effects.fireflies.FirefliesEffect
import info.chrzanowski.ideatmosphere.effects.sakura.SakuraEffect
import info.chrzanowski.ideatmosphere.render.DirtyRegionCollector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage

class EffectSettingsTest {

    private val factories = listOf({ FirefliesEffect() }, { SakuraEffect() })
    private val dt = 1f / 24f

    private fun started(factory: () -> AtmosphereEffect): AtmosphereEffect = factory().also {
        it.start(EffectContext.forTesting(seed = 42L))
    }

    private fun count(effect: AtmosphereEffect): Int = when (effect) {
        is FirefliesEffect -> effect.particleCount
        is SakuraEffect -> effect.particleCount
        else -> error(effect.id)
    }

    private fun multiplier(effect: AtmosphereEffect): Float = when (effect) {
        is FirefliesEffect -> effect.speedMultiplier
        is SakuraEffect -> effect.speedMultiplier
        else -> error(effect.id)
    }

    private fun expectedCount(effect: AtmosphereEffect, bounds: Rectangle, intensity: Float): Int = when (effect) {
        is FirefliesEffect -> FirefliesEffect.particleCountFor(bounds.width, bounds.height, intensity)
        is SakuraEffect -> SakuraEffect.particleCountFor(bounds.width, bounds.height, intensity)
        else -> error(effect.id)
    }

    @Test
    fun `intensity changes population without a resize and clears removed pixels`() {
        val bounds = Rectangle(0, 0, 1800, 1200)
        for (factory in factories) {
            val effect = started(factory)
            val dirty = DirtyRegionCollector(100)
            repeat(48) { dirty.clear(); effect.update(dt, bounds, dirty) }
            val originalCount = count(effect)
            val before = paint(effect, bounds)
            effect.setIntensity(0.3f)
            dirty.clear()
            assertTrue(effect.update(dt, bounds, dirty))
            assertEquals(effect.id, expectedCount(effect, bounds, 0.3f), count(effect))
            assertTrue(effect.id, count(effect) < originalCount)
            assertCovered(before, paint(effect, bounds), bounds, dirty)

            effect.setIntensity(2f)
            dirty.clear()
            effect.update(dt, bounds, dirty)
            assertEquals(effect.id, expectedCount(effect, bounds, 2f), count(effect))
        }
    }

    @Test
    fun `intensity changes existing opacity without respawning`() {
        val bounds = Rectangle(0, 0, 800, 600)
        for (factory in factories) {
            val effect = started(factory)
            val reference = started(factory)
            val dirty = DirtyRegionCollector(100)
            repeat(48) { dirty.clear(); effect.update(dt, bounds, dirty); reference.update(dt, bounds, dirty) }
            effect.setIntensity(0.5f)
            dirty.clear()
            effect.update(dt, bounds, dirty)
            reference.update(dt, bounds, DirtyRegionCollector(100))
            assertEquals(effect.id, count(reference), count(effect))
            val dimmed = alphaSum(paint(effect, bounds))
            val unchanged = alphaSum(paint(reference, bounds))
            assertTrue("${effect.id} should immediately dim existing particles", dimmed < unchanged * 0.65)
            assertTrue(effect.id, dimmed > unchanged * 0.35)
        }
    }

    @Test
    fun `speed slider scales normal idle and busy motion`() {
        val bounds = Rectangle(0, 0, 800, 600)
        for (factory in factories) {
            for (event in listOf(AtmosphereEvent.USER_ACTIVE, AtmosphereEvent.USER_IDLE, AtmosphereEvent.ACTIVITY_STARTED)) {
                val effect = started(factory)
                val reference = started(factory)
                effect.onEvent(event)
                reference.onEvent(event)
                effect.update(6f, bounds, DirtyRegionCollector(100))
                reference.update(6f, bounds, DirtyRegionCollector(100))
                effect.setSpeedScale(0.5f)
                effect.update(dt, bounds, DirtyRegionCollector(100))
                assertEquals("${effect.id} $event", multiplier(reference) * 0.5f, multiplier(effect), 0.001f)
                effect.setSpeedScale(2f)
                effect.update(dt, bounds, DirtyRegionCollector(100))
                assertEquals("${effect.id} $event", multiplier(reference) * 2f, multiplier(effect), 0.001f)
            }
        }
    }

    @Test
    fun `profiles shape movement around baseline and success pulses release automatically`() {
        val bounds = Rectangle(0, 0, 800, 600)
        for (factory in factories) {
            val effect = started(factory)
            effect.onReactionProfileChanged(AtmosphereReactionProfile(
                movement = EnvelopeSettings(2f, 0f, 0.5f, 0.5f),
                successPulse = EnvelopeSettings(0.1f, 0.1f, 0.5f, 0.2f),
            ))
            effect.setSpeedScale(2f)
            effect.onEvent(AtmosphereEvent.ACTIVITY_STARTED)
            effect.update(1f, bounds, DirtyRegionCollector(100))
            val peak = if (effect is FirefliesEffect) FirefliesEffect.ACTIVITY_SPEED else SakuraEffect.ACTIVITY_SPEED
            assertEquals(effect.id, 2f * (1f + (peak - 1f) * 0.5f), multiplier(effect), 0.001f)
            effect.update(2f, bounds, DirtyRegionCollector(100))
            assertEquals(effect.id, 2f * (1f + (peak - 1f) * 0.5f), multiplier(effect), 0.001f)

            effect.onEvent(AtmosphereEvent.SUCCESS)
            effect.update(0.1f, bounds, DirtyRegionCollector(100))
            assertEquals(effect.id, 1f, glow(effect), 0.001f)
            effect.update(0.2f, bounds, DirtyRegionCollector(100))
            assertTrue(effect.id, glow(effect) > 0f && glow(effect) < 0.5f)
            effect.update(0.2f, bounds, DirtyRegionCollector(100))
            assertEquals(effect.id, 0f, glow(effect), 0.001f)
        }
    }

    @Test
    fun `default success pulse brightens rendered particles and fades back to normal`() {
        val bounds = Rectangle(0, 0, 800, 600)
        for (factory in factories) {
            val effect = started(factory)
            val reference = started(factory)
            effect.onReactionProfileChanged(AtmosphereReactionProfile())
            reference.onReactionProfileChanged(AtmosphereReactionProfile())
            val dirty = DirtyRegionCollector(100)
            repeat(48) {
                dirty.clear()
                effect.update(dt, bounds, dirty)
                reference.update(dt, bounds, DirtyRegionCollector(100))
            }
            effect.onEvent(AtmosphereEvent.SUCCESS)
            repeat(3) {
                dirty.clear()
                effect.update(dt, bounds, dirty)
                reference.update(dt, bounds, DirtyRegionCollector(100))
            }
            val brightened = alphaSum(paint(effect, bounds))
            val baseline = alphaSum(paint(reference, bounds))
            assertTrue("${effect.id} success must brighten the rendered particles", brightened > baseline * 1.3)
            assertTrue("${effect.id} success must report dirty regions", dirty.size > 0)
            assertEquals(effect.id, count(reference), count(effect))
            assertEquals(effect.id, multiplier(reference), multiplier(effect), 0f)

            repeat(48) {
                dirty.clear()
                effect.update(dt, bounds, dirty)
                reference.update(dt, bounds, DirtyRegionCollector(100))
            }
            assertEquals(effect.id, 0f, glow(effect), 0f)
            assertEquals(effect.id, alphaSum(paint(reference, bounds)), alphaSum(paint(effect, bounds)))
        }
    }

    private fun glow(effect: AtmosphereEffect): Float = when (effect) {
        is FirefliesEffect -> effect.glow
        is SakuraEffect -> effect.glow
        else -> error(effect.id)
    }

    private fun paint(effect: AtmosphereEffect, bounds: Rectangle): BufferedImage {
        val image = BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g.clip = bounds
            effect.paint(g, bounds)
        } finally {
            g.dispose()
        }
        return image
    }

    private fun alphaSum(image: BufferedImage): Long {
        var sum = 0L
        for (y in 0 until image.height) for (x in 0 until image.width) sum += image.getRGB(x, y) ushr 24
        return sum
    }

    private fun assertCovered(before: BufferedImage, after: BufferedImage, bounds: Rectangle, dirty: DirtyRegionCollector) {
        val regions = ArrayList<Rectangle>()
        dirty.merge(bounds) { regions.add(Rectangle(it)) }
        var changed = 0
        for (y in 0 until bounds.height) for (x in 0 until bounds.width) {
            if (before.getRGB(x, y) != after.getRGB(x, y)) {
                changed++
                assertTrue("changed pixel ($x,$y) must be covered", regions.any { it.contains(x, y) })
            }
        }
        assertTrue("must exercise visible changes", changed > 0)
    }
}