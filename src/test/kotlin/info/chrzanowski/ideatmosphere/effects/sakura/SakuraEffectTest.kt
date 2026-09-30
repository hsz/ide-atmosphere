package info.chrzanowski.ideatmosphere.effects.sakura

import info.chrzanowski.ideatmosphere.core.AtmosphereEvent
import info.chrzanowski.ideatmosphere.core.AtmospherePalette
import info.chrzanowski.ideatmosphere.core.EffectContext
import info.chrzanowski.ideatmosphere.effects.AtmosphereEffects
import info.chrzanowski.ideatmosphere.render.DirtyRegionCollector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.sqrt

class SakuraEffectTest {

    private val dt = 1f / 24f

    private fun started(seed: Long = 42L, palette: AtmospherePalette = AtmospherePalette.DARK): SakuraEffect =
        SakuraEffect().also { it.start(EffectContext.forTesting(palette = palette, seed = seed)) }

    private fun newCollector() = DirtyRegionCollector(SakuraEffect.MAX_PARTICLES * 2)

    private fun simulate(effect: SakuraEffect, bounds: Rectangle, seconds: Float, dirty: DirtyRegionCollector = newCollector()): Int {
        var changedTicks = 0
        var elapsed = 0f
        while (elapsed < seconds) {
            dirty.clear()
            if (effect.update(dt, bounds, dirty)) changedTicks++
            elapsed += dt
        }
        return changedTicks
    }

    @Test
    fun `particle count is clamped and scales with area`() {
        assertEquals(15, SakuraEffect.particleCountFor(800, 600))
        assertEquals(50, SakuraEffect.particleCountFor(3840, 2160))
        
        val small = started()
        small.update(dt, Rectangle(0, 0, 800, 600), newCollector())
        assertEquals(15, small.particleCount)

        val large = started()
        large.update(dt, Rectangle(0, 0, 3840, 2160), newCollector())
        assertEquals(50, large.particleCount)
    }

    @Test
    fun `particles stay inside bounds after 60 seconds`() {
        val effect = started()
        val bounds = Rectangle(0, 0, 1512, 949)
        simulate(effect, bounds, 60f)
        assertTrue(effect.allInside(bounds))
    }

    @Test
    fun `shrinking bounds respawns particles`() {
        val effect = started()
        simulate(effect, Rectangle(0, 0, 3840, 2160), 5f)
        assertEquals(50, effect.particleCount)

        val smaller = Rectangle(0, 0, 800, 600)
        val dirty = newCollector()
        assertTrue(effect.update(dt, smaller, dirty))
        assertEquals(15, effect.particleCount)
        assertTrue(effect.allInside(smaller))
    }

    @Test
    fun `zero delta emits nothing`() {
        val effect = started()
        val bounds = Rectangle(0, 0, 800, 600)
        simulate(effect, bounds, 2f)
        val dirty = newCollector()
        assertFalse(effect.update(0f, bounds, dirty))
        assertEquals(0, dirty.size)
    }

    @Test
    fun `dirty reporting is continuous with a small epsilon`() {
        val effect = started()
        val bounds = Rectangle(0, 0, 800, 600)
        simulate(effect, bounds, 3f)

        val dirty = newCollector()
        assertTrue(effect.update(1f / 30f, bounds, dirty))
        assertTrue(dirty.size >= effect.particleCount - 2)

        var reported = 0
        repeat(10) {
            dirty.clear()
            effect.update(0.0001f, bounds, dirty)
            reported += dirty.size
        }
        assertTrue("expected almost no dirty regions for tiny changes, got $reported", reported <= 15)
    }

    @Test
    fun `color tables have 256 alpha steps`() {
        assertEquals(256, SakuraEffect.ALPHA_STEPS)
        val effect = started()
        assertEquals(256, effect.colorTableSizeForTest())
    }

    @Test
    fun `petals are rendered at sub-pixel and sub-degree positions`() {
        val bounds = Rectangle(0, 0, 40, 40)
        
        // Sub-pixel position test
        val pos1 = paintSingle(10.0f, 0f)
        val pos2 = paintSingle(10.5f, 0f)
        val posProfile1 = columnCoverage(pos1, bounds)
        val posProfile2 = columnCoverage(pos2, bounds)
        assertFalse("x=10.0 and x=10.5 must not produce identical coverage", posProfile1.contentEquals(posProfile2))

        // Sub-degree rotation test
        val rot1 = paintSingle(20.0f, 0.0f)
        val rot2 = paintSingle(20.0f, 0.1f)
        val rotProfile1 = columnCoverage(rot1, bounds)
        val rotProfile2 = columnCoverage(rot2, bounds)
        assertFalse("rotation=0.0 and rotation=0.1 must not produce identical coverage", rotProfile1.contentEquals(rotProfile2))
    }

    private fun paintSingle(px: Float, rotation: Float): BufferedImage {
        val bounds = Rectangle(0, 0, 40, 40)
        val effect = started()
        effect.update(dt, bounds, newCollector())
        for (i in 0 until effect.particleCount) effect.placeParticleForTest(i, -100f, -100f, 0f, 0f)
        effect.placeParticleForTest(0, px, 20f, rotation, 0.8f)
        return paint(effect, bounds, bounds)
    }

    private fun columnCoverage(image: BufferedImage, area: Rectangle): LongArray {
        val profile = LongArray(area.width)
        for (yy in 0 until area.height) {
            for (xx in 0 until area.width) {
                profile[xx] += (image.getRGB(xx, yy) ushr 24).toLong()
            }
        }
        return profile
    }

    @Test
    fun `dirty regions cover every painted petal`() {
        val effect = started()
        val bounds = Rectangle(0, 0, 800, 600)
        simulate(effect, bounds, 3f)

        val before = paint(effect, bounds, bounds)
        val dirty = newCollector()
        assertTrue(effect.update(dt, bounds, dirty))
        val after = paint(effect, bounds, bounds)
        val merged = ArrayList<Rectangle>()
        dirty.merge(bounds) { merged.add(Rectangle(it)) }

        for (yy in 0 until bounds.height) {
            for (xx in 0 until bounds.width) {
                if (before.getRGB(xx, yy) != after.getRGB(xx, yy)) {
                    assertTrue("changed pixel ($xx,$yy) is not covered by a dirty rect", merged.any { it.contains(xx, yy) })
                }
            }
        }
    }

    @Test
    fun `event reactions change speed multiplier and glow`() {
        val effect = started()
        val bounds = Rectangle(0, 0, 800, 600)
        simulate(effect, bounds, 1f)

        effect.onEvent(AtmosphereEvent.USER_IDLE)
        simulate(effect, bounds, 6f)
        assertEquals(SakuraEffect.IDLE_SPEED, effect.speedMultiplier, 0.05f)

        effect.onEvent(AtmosphereEvent.USER_ACTIVE)
        simulate(effect, bounds, 6f)
        assertEquals(SakuraEffect.NORMAL_SPEED, effect.speedMultiplier, 0.05f)

        effect.onEvent(AtmosphereEvent.ACTIVITY_STARTED)
        simulate(effect, bounds, 6f)
        assertEquals(SakuraEffect.ACTIVITY_SPEED, effect.speedMultiplier, 0.05f)

        effect.onEvent(AtmosphereEvent.SUCCESS)
        effect.update(0.2f, bounds, newCollector())
        assertEquals(1f, effect.glow, 0.01f)
    }

    @Test
    fun `registry contains sakura`() {
        val ids = AtmosphereEffects.ids
        assertTrue(ids.contains(SakuraEffect.ID))
        val effect = AtmosphereEffects.create(SakuraEffect.ID)
        assertTrue(effect is SakuraEffect)
    }

    private fun paint(effect: SakuraEffect, bounds: Rectangle, clip: Rectangle): BufferedImage {
        val image = BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g.clip = clip
            effect.paint(g, clip)
        } finally {
            g.dispose()
        }
        return image
    }
}
