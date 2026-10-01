package info.chrzanowski.ideatmosphere.effects

import info.chrzanowski.ideatmosphere.core.AtmosphereEffect
import info.chrzanowski.ideatmosphere.core.AtmosphereEvent
import info.chrzanowski.ideatmosphere.core.AtmospherePalette
import info.chrzanowski.ideatmosphere.core.EffectContext
import info.chrzanowski.ideatmosphere.effects.fireflies.FirefliesEffect
import info.chrzanowski.ideatmosphere.effects.sakura.SakuraEffect
import info.chrzanowski.ideatmosphere.effects.snow.SnowEffect
import info.chrzanowski.ideatmosphere.render.DirtyRegionCollector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage

class EffectSettingsTest {

    private val factories = listOf({ FirefliesEffect() }, { SakuraEffect() }, { SnowEffect() })
    private val dt = 1f / 24f

    private fun started(factory: () -> AtmosphereEffect): AtmosphereEffect = factory().also {
        it.start(EffectContext.forTesting(seed = 42L))
    }

    private fun count(effect: AtmosphereEffect): Int = when (effect) {
        is FirefliesEffect -> effect.particleCount
        is SakuraEffect -> effect.particleCount
        is SnowEffect -> effect.particleCount
        else -> error(effect.id)
    }

    private fun multiplier(effect: AtmosphereEffect): Float = when (effect) {
        is FirefliesEffect -> effect.speedMultiplier
        is SakuraEffect -> effect.speedMultiplier
        is SnowEffect -> effect.speedMultiplier
        else -> error(effect.id)
    }

    private fun expectedCount(effect: AtmosphereEffect, bounds: Rectangle, intensity: Float): Int = when (effect) {
        is FirefliesEffect -> FirefliesEffect.particleCountFor(bounds.width, bounds.height, intensity)
        is SakuraEffect -> SakuraEffect.particleCountFor(bounds.width, bounds.height, intensity)
        is SnowEffect -> SnowEffect.particleCountFor(bounds.width, bounds.height, intensity)
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
        // Small enough that every atmosphere sits at its minimum population for both intensities.
        val bounds = Rectangle(0, 0, 600, 400)
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
    fun `outcome pulses are one-shot and release automatically without further events`() {
        val bounds = Rectangle(0, 0, 800, 600)
        for (factory in factories) {
            val effect = started(factory)
            repeat(48) { effect.update(dt, bounds, DirtyRegionCollector(100)) }
            effect.onEvent(AtmosphereEvent.SUCCESS)
            effect.update(0.25f, bounds, DirtyRegionCollector(100))
            assertTrue("${effect.id} success pulse must rise quickly", glow(effect) > 0.8f)
            assertEquals(effect.id, 0f, failureGlow(effect), 0f)
            repeat(96) { effect.update(dt, bounds, DirtyRegionCollector(100)) }
            assertEquals("${effect.id} success pulse must fade by itself", 0f, glow(effect), 0f)

            effect.onEvent(AtmosphereEvent.FAILURE)
            effect.update(0.25f, bounds, DirtyRegionCollector(100))
            assertTrue("${effect.id} failure pulse must rise quickly", failureGlow(effect) > 0.8f)
            assertEquals(effect.id, 0f, glow(effect), 0f)
            repeat(96) { effect.update(dt, bounds, DirtyRegionCollector(100)) }
            assertEquals("${effect.id} failure pulse must fade by itself", 0f, failureGlow(effect), 0f)
        }
    }

    @Test
    fun `outcomes never tint particles or add halos on either palette`() {
        val bounds = Rectangle(0, 0, 800, 600)
        for (factory in factories) for (palette in listOf(AtmospherePalette.DARK, AtmospherePalette.LIGHT)) {
            val effect = started(factory)
            effect.onPaletteChanged(palette)
            repeat(48) { effect.update(dt, bounds, DirtyRegionCollector(100)) }
            val baseline = paint(effect, bounds)
            val baselineColor = meanColor(baseline)
            for (event in listOf(AtmosphereEvent.SUCCESS, AtmosphereEvent.FAILURE)) {
                effect.onEvent(event)
                effect.update(0.1f, bounds, DirtyRegionCollector(100))
                val reacting = paint(effect, bounds)
                val color = meanColor(reacting)
                for (channel in 0..2) {
                    assertEquals("${effect.id} $event must keep the particle colour", baselineColor[channel], color[channel], 8.0)
                }
                assertTrue(
                    "${effect.id} $event must not grow the painted footprint",
                    visiblePixels(reacting) < visiblePixels(baseline) * 1.25,
                )
                repeat(72) { effect.update(dt, bounds, DirtyRegionCollector(100)) }
            }
        }
    }

    @Test
    fun `success lifts the particles and failure dims them before both settle back`() {
        val bounds = Rectangle(0, 0, 800, 600)
        for (factory in factories) {
            val effect = started(factory)
            val reference = started(factory)
            repeat(48) {
                effect.update(dt, bounds, DirtyRegionCollector(100))
                reference.update(dt, bounds, DirtyRegionCollector(100))
            }
            // Same seed, same spot: without the lift both fields would evolve (almost) identically.
            placeAllAtCentre(effect)
            placeAllAtCentre(reference)
            effect.onEvent(AtmosphereEvent.SUCCESS)
            repeat(12) {
                effect.update(dt, bounds, DirtyRegionCollector(100))
                reference.update(dt, bounds, DirtyRegionCollector(100))
            }
            assertTrue(
                "${effect.id} success must lift the particles",
                centroidY(paint(effect, bounds)) < centroidY(paint(reference, bounds)) - 5.0,
            )
            assertEquals(effect.id, count(reference), count(effect))
            assertEquals(effect.id, multiplier(reference), multiplier(effect), 0f)
            repeat(96) { effect.update(dt, bounds, DirtyRegionCollector(100)) }
            assertEquals(effect.id, 0f, glow(effect), 0f)

            val before = alphaSum(paint(effect, bounds))
            effect.onEvent(AtmosphereEvent.FAILURE)
            effect.update(0.2f, bounds, DirtyRegionCollector(100))
            val dimmed = alphaSum(paint(effect, bounds))
            assertTrue("${effect.id} failure must dim the particles", dimmed < before * 0.9)
            repeat(96) { effect.update(dt, bounds, DirtyRegionCollector(100)) }
            assertEquals(effect.id, 0f, failureGlow(effect), 0f)
        }
    }

    @Test
    fun `dirty regions cover every pixel changed by successive and interrupted outcomes`() {
        val bounds = Rectangle(0, 0, 400, 300)
        for (factory in factories) {
            val effect = started(factory)
            repeat(48) { effect.update(dt, bounds, DirtyRegionCollector(100)) }
            for (event in listOf(AtmosphereEvent.SUCCESS, AtmosphereEvent.FAILURE, AtmosphereEvent.FAILURE, AtmosphereEvent.SUCCESS)) {
                effect.onEvent(event)
                repeat(12) {
                    val before = paint(effect, bounds)
                    val dirty = DirtyRegionCollector(100)
                    effect.update(dt, bounds, dirty)
                    assertCovered(before, paint(effect, bounds), bounds, dirty)
                }
            }
        }
    }

    @Test
    fun `particles smoothly escape a nearby cursor without affecting distant motion`() {
        val bounds = Rectangle(0, 0, 800, 600)
        for (factory in factories) {
            val effect = started(factory)
            val reference = started(factory)
            repeat(48) {
                effect.update(dt, bounds, DirtyRegionCollector(100))
                reference.update(dt, bounds, DirtyRegionCollector(100))
            }
            placeAllAtCentre(effect)
            placeAllAtCentre(reference)
            effect.onPointerChanged(370f, 300f, true)
            repeat(12) {
                effect.update(dt, bounds, DirtyRegionCollector(100))
                reference.update(dt, bounds, DirtyRegionCollector(100))
            }
            assertTrue("${effect.id} particles must flee to the right", centroidX(paint(effect, bounds)) > centroidX(paint(reference, bounds)) + 15.0)
            val distant = started(factory)
            val unchanged = started(factory)
            distant.onPointerChanged(-1000f, -1000f, true)
            repeat(12) {
                distant.update(dt, bounds, DirtyRegionCollector(100))
                unchanged.update(dt, bounds, DirtyRegionCollector(100))
            }
            assertEquals(effect.id, alphaSum(paint(unchanged, bounds)), alphaSum(paint(distant, bounds)))
            assertEquals(effect.id, centroidX(paint(unchanged, bounds)), centroidX(paint(distant, bounds)), 0.0)
        }
    }

    /** Stacks every particle at (400, 300) of the 800×600 test bounds so that motion, not spread, drives the centroid. */
    private fun placeAllAtCentre(effect: AtmosphereEffect) {
        for (i in 0 until count(effect)) when (effect) {
            is FirefliesEffect -> effect.placeParticleForTest(i, 400f, 300f, 0.8f)
            is SakuraEffect -> effect.placeParticleForTest(i, 400f, 300f, 0f, 0.8f)
            is SnowEffect -> effect.placeParticleForTest(i, 400f, 300f, 0.8f)
        }
    }

    private fun centroidX(image: BufferedImage): Double {
        var weighted = 0.0
        var alpha = 0L
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val a = image.getRGB(x, y) ushr 24
            weighted += x * a.toDouble()
            alpha += a
        }
        return weighted / alpha
    }

    private fun centroidY(image: BufferedImage): Double {
        var weighted = 0.0
        var alpha = 0L
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val a = image.getRGB(x, y) ushr 24
            weighted += y * a.toDouble()
            alpha += a
        }
        return weighted / alpha
    }

    /** Alpha-weighted mean (R, G, B) of the visible pixels; a tint shifts it by tens of units per channel. */
    private fun meanColor(image: BufferedImage): DoubleArray {
        val sums = DoubleArray(3)
        var alpha = 0.0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val pixel = image.getRGB(x, y)
            val a = (pixel ushr 24).toDouble()
            if (a == 0.0) continue
            sums[0] += (pixel shr 16 and 255) * a
            sums[1] += (pixel shr 8 and 255) * a
            sums[2] += (pixel and 255) * a
            alpha += a
        }
        assertTrue("image must contain visible particles", alpha > 0.0)
        return DoubleArray(3) { sums[it] / alpha }
    }

    private fun visiblePixels(image: BufferedImage): Int {
        var count = 0
        for (y in 0 until image.height) for (x in 0 until image.width) if (image.getRGB(x, y) ushr 24 > 0) count++
        return count
    }

    private fun failureGlow(effect: AtmosphereEffect): Float = when (effect) {
        is FirefliesEffect -> effect.failureGlow
        is SakuraEffect -> effect.failureGlow
        is SnowEffect -> effect.failureGlow
        else -> error(effect.id)
    }

    private fun glow(effect: AtmosphereEffect): Float = when (effect) {
        is FirefliesEffect -> effect.glow
        is SakuraEffect -> effect.glow
        is SnowEffect -> effect.glow
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