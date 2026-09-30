package info.chrzanowski.ideatmosphere.effects.fireflies

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

class FirefliesEffectTest {

    private val dt = 1f / 24f

    private fun started(seed: Long = 42L, palette: AtmospherePalette = AtmospherePalette.DARK): FirefliesEffect =
        FirefliesEffect().also { it.start(EffectContext.forTesting(palette = palette, seed = seed)) }

    private fun newCollector() = DirtyRegionCollector(FirefliesEffect.MAX_PARTICLES * 2)

    /** Runs [seconds] of simulation; returns how many updates reported a change. */
    private fun simulate(effect: FirefliesEffect, bounds: Rectangle, seconds: Float, dirty: DirtyRegionCollector = newCollector()): Int {
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
    fun `particle count is clamped to 20-50 and scales with area`() {
        assertEquals(20, FirefliesEffect.particleCountFor(800, 600))
        assertEquals(50, FirefliesEffect.particleCountFor(3840, 2160))
        assertEquals(20, FirefliesEffect.particleCountFor(1512, 949))

        val small = started()
        small.update(dt, Rectangle(0, 0, 800, 600), newCollector())
        assertEquals(20, small.particleCount)

        val large = started()
        large.update(dt, Rectangle(0, 0, 3840, 2160), newCollector())
        assertEquals(50, large.particleCount)
    }

    @Test
    fun `particles stay inside bounds after 60 seconds`() {
        val effect = started()
        val bounds = Rectangle(0, 40, 1512, 909)
        val changed = simulate(effect, bounds, 60f)
        assertTrue("simulation should report changes", changed > 0)
        assertTrue(effect.allInside(bounds))
        assertEquals(FirefliesEffect.particleCountFor(1512, 909), effect.particleCount)
    }

    @Test
    fun `shrinking bounds respawns particles inside the new bounds within one tick`() {
        val effect = started()
        simulate(effect, Rectangle(0, 0, 3840, 2160), 5f)
        assertEquals(50, effect.particleCount)

        val smaller = Rectangle(0, 0, 400, 300)
        val dirty = newCollector()
        assertTrue(effect.update(dt, smaller, dirty))
        assertEquals(20, effect.particleCount)
        assertTrue(effect.allInside(smaller))
        assertTrue("vanished particles must be reported for clearing", dirty.size > 0)
    }

    @Test
    fun `zero delta emits nothing`() {
        val effect = started()
        val bounds = Rectangle(0, 0, 800, 600)
        simulate(effect, bounds, 2f)
        val dirty = newCollector()
        assertFalse(effect.update(0f, bounds, dirty))
        assertEquals(0, dirty.size)
        assertFalse(effect.update(dt, Rectangle(0, 0, 0, 0), dirty))
        assertEquals(0, dirty.size)
    }

    @Test
    fun `dirty reporting is continuous with a small epsilon`() {
        val effect = started()
        val bounds = Rectangle(0, 0, 800, 600)
        simulate(effect, bounds, 3f)

        // A 1/30 s tick moves every cruising firefly by 0.2-0.6 px (>= MOVE_EPSILON), so nearly all are dirty.
        val dirty = newCollector()
        assertTrue(effect.update(1f / 30f, bounds, dirty))
        assertTrue(
            "expected nearly every particle dirty on a normal tick, got ${dirty.size}/${effect.particleCount}",
            dirty.size >= effect.particleCount - 2,
        )

        // A 0.1 ms tick moves a particle by at most 0.002 px and changes alpha by far less than one 8-bit step,
        // so (almost) nothing needs repainting.
        var reported = 0
        repeat(10) {
            dirty.clear()
            effect.update(0.0001f, bounds, dirty)
            reported += dirty.size
        }
        assertTrue("expected almost no dirty regions for invisible changes, got $reported", reported <= 2)

        // Sub-epsilon motion accumulates in `x/y` while `prev*` (on-screen state) stays put, and is reported
        // once the accumulated displacement becomes visible.
        dirty.clear()
        assertTrue(effect.update(1f / 30f, bounds, dirty))
        assertTrue(dirty.size >= effect.particleCount - 2)
    }

    @Test
    fun `color tables have 256 alpha steps`() {
        assertEquals(256, FirefliesEffect.ALPHA_STEPS)
        val effect = started()
        assertEquals(256, effect.colorTableSizeForTest())
        effect.onPaletteChanged(AtmospherePalette.LIGHT)
        assertEquals(256, effect.colorTableSizeForTest())
    }

    @Test
    fun `fireflies are rendered at sub-pixel positions`() {
        val bounds = Rectangle(0, 0, 40, 40)
        val whole = paintSingle(10.0f)
        val half = paintSingle(10.5f)

        // Both images have the same total coverage (same shape), but shifting by half a pixel redistributes it
        // between the boundary columns; with pixel snapping the two images would be identical or shifted by 1.
        val wholeProfile = columnCoverage(whole, bounds)
        val halfProfile = columnCoverage(half, bounds)
        assertTrue("nothing painted", wholeProfile.sum() > 0)
        assertFalse("x=10.0 and x=10.5 must not produce identical coverage", wholeProfile.contentEquals(halfProfile))
        val shiftedByOne = LongArray(bounds.width) { xx -> if (xx > 0) wholeProfile[xx - 1] else 0L }
        assertFalse("x=10.5 must not simply be x=10.0 snapped to the next pixel", shiftedByOne.contentEquals(halfProfile))
        val profiles = "whole=${wholeProfile.slice(5..15)} half=${halfProfile.slice(5..15)}"
        // x = 10.0 lies on the boundary between pixel columns 9 and 10, so the coverage straddles them almost
        // evenly (the AA rasteriser is not perfectly symmetric, so allow a small tolerance) ...
        val pair = minOf(wholeProfile[9], wholeProfile[10]).toDouble() / maxOf(wholeProfile[9], wholeProfile[10])
        assertTrue("columns 9/10 should share the coverage at x=10.0: $profiles", pair > 0.85)
        // ... whereas x = 10.5 is the centre of column 10, which then clearly dominates both neighbours.
        assertTrue(profiles, halfProfile[10] > halfProfile[11] && halfProfile[10] > halfProfile[9])
        assertTrue(profiles, halfProfile[10].toDouble() / maxOf(halfProfile[9], halfProfile[11]) > 1.0 / pair)
    }

    /** Paints a single firefly at ([px], 20) on a 40×40 canvas, with all the others hidden. */
    private fun paintSingle(px: Float): BufferedImage {
        val bounds = Rectangle(0, 0, 40, 40)
        val effect = started()
        effect.update(dt, bounds, newCollector())
        for (i in 0 until effect.particleCount) effect.placeParticleForTest(i, -100f, -100f, 0f)
        effect.placeParticleForTest(0, px, 20f, 0.8f)
        return paint(effect, bounds, bounds)
    }

    /** Sum of alpha per column: a 1-D coverage profile of the painted image. */
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
    fun `update before start or after stop is a no-op`() {
        val effect = FirefliesEffect()
        val dirty = newCollector()
        assertFalse(effect.update(dt, Rectangle(0, 0, 800, 600), dirty))
        assertEquals(0, dirty.size)

        effect.start(EffectContext.forTesting())
        simulate(effect, Rectangle(0, 0, 800, 600), 1f)
        effect.stop()
        assertEquals(0, effect.particleCount)
        assertFalse(effect.update(dt, Rectangle(0, 0, 800, 600), dirty))
    }

    @Test
    fun `dirty regions cover every painted particle`() {
        val effect = started()
        val bounds = Rectangle(0, 0, 800, 600)
        simulate(effect, bounds, 3f)

        // Paint the full frame, then compare with the union of the dirty rects of the next tick.
        val before = paint(effect, bounds, bounds)
        val dirty = newCollector()
        assertTrue(effect.update(dt, bounds, dirty))
        val after = paint(effect, bounds, bounds)
        val merged = ArrayList<Rectangle>()
        dirty.merge(bounds) { merged.add(Rectangle(it)) }
        assertTrue(merged.isNotEmpty() && merged.size <= dirty.maxRects)

        for (yy in 0 until bounds.height) {
            for (xx in 0 until bounds.width) {
                if (before.getRGB(xx, yy) != after.getRGB(xx, yy)) {
                    assertTrue("changed pixel ($xx,$yy) is not covered by a dirty rect", merged.any { it.contains(xx, yy) })
                }
            }
        }
    }

    @Test
    fun `offscreen paint writes only inside the clip and is visible`() {
        val effect = started()
        val bounds = Rectangle(0, 0, 800, 600)
        simulate(effect, bounds, 3f)

        val full = paint(effect, bounds, bounds)
        assertTrue("fireflies should be visible after fading in", countOpaque(full, bounds) > 0)

        val clip = Rectangle(100, 100, 200, 150)
        val clipped = paint(effect, bounds, clip)
        var outside = 0
        var inside = 0
        for (yy in 0 until bounds.height) {
            for (xx in 0 until bounds.width) {
                val a = clipped.getRGB(xx, yy) ushr 24
                if (a == 0) continue
                if (clip.contains(xx, yy)) inside++ else outside++
            }
        }
        assertEquals(0, outside)
        assertEquals(countOpaque(full, clip), inside)
    }

    @Test
    fun `paint uses palette colors and rebuilds them on theme change`() {
        val effect = started(palette = AtmospherePalette.DARK)
        val bounds = Rectangle(0, 0, 800, 600)
        simulate(effect, bounds, 3f)
        val dark = paint(effect, bounds, bounds)
        assertTrue(colorDistance(dark, AtmospherePalette.DARK.particleColor) < 40.0)

        effect.onPaletteChanged(AtmospherePalette.LIGHT)
        val light = paint(effect, bounds, bounds)
        assertTrue(colorDistance(light, AtmospherePalette.LIGHT.particleColor) < 40.0)
        assertTrue(colorDistance(light, AtmospherePalette.DARK.particleColor) > 60.0)
    }

    @Test
    fun `idle and active events move the speed multiplier smoothly`() {
        val effect = started()
        val bounds = Rectangle(0, 0, 800, 600)
        simulate(effect, bounds, 1f)
        val cruising = effect.meanSpeed()
        assertTrue(cruising in 5f..19f)

        effect.onEvent(AtmosphereEvent.USER_IDLE)
        assertEquals(1f, effect.speedMultiplier, 0f)
        effect.update(dt, bounds, newCollector())
        assertTrue("should interpolate, not jump", effect.speedMultiplier > 0.9f && effect.speedMultiplier < 1f)
        simulate(effect, bounds, 6f)
        assertEquals(FirefliesEffect.IDLE_SPEED, effect.speedMultiplier, 0.001f)
        assertTrue(effect.meanSpeed() < cruising * 0.7f)

        effect.onEvent(AtmosphereEvent.USER_ACTIVE)
        simulate(effect, bounds, 6f)
        assertEquals(FirefliesEffect.NORMAL_SPEED, effect.speedMultiplier, 0.001f)

        effect.onEvent(AtmosphereEvent.ACTIVITY_STARTED)
        simulate(effect, bounds, 2f)
        assertEquals(FirefliesEffect.ACTIVITY_SPEED, effect.speedMultiplier, 0.001f)

        effect.onEvent(AtmosphereEvent.ACTIVITY_FINISHED)
        simulate(effect, bounds, 2f)
        assertEquals(FirefliesEffect.NORMAL_SPEED, effect.speedMultiplier, 0.001f)
    }

    @Test
    fun `success glows then decays`() {
        val effect = started()
        val bounds = Rectangle(0, 0, 800, 600)
        simulate(effect, bounds, 1f)
        assertEquals(0f, effect.glow, 0f)
        effect.onEvent(AtmosphereEvent.SUCCESS)
        // Now it uses an envelope with a fast attack (0.1s)
        assertTrue("should not jump to 1f immediately", effect.glow < 1f)
        effect.update(0.1f, bounds, newCollector())
        assertEquals(1f, effect.glow, 0.001f)
        simulate(effect, bounds, FirefliesEffect.GLOW_SECONDS + 0.5f)
        assertEquals(0f, effect.glow, 0.001f)
    }

    @Test
    fun `failure adds a bounded impulse that damps out`() {
        val effect = started()
        val bounds = Rectangle(0, 0, 1600, 1200)
        simulate(effect, bounds, 1f)
        val cruising = effect.meanSpeed()
        effect.onEvent(AtmosphereEvent.FAILURE)
        val kicked = effect.meanSpeed()
        assertTrue("impulse should raise speed", kicked > cruising * 1.5f)
        assertTrue("impulse must stay bounded", kicked < cruising + 60f)
        simulate(effect, bounds, 5f)
        assertTrue("impulse should damp out", effect.meanSpeed() < cruising * 1.3f)
        assertTrue(effect.allInside(bounds))
    }

    @Test
    fun `registry contains fireflies`() {
        val ids = AtmosphereEffects.ids
        assertTrue(ids.contains(FirefliesEffect.ID))
        val a = AtmosphereEffects.create(FirefliesEffect.ID)
        val b = AtmosphereEffects.createOrDefault("unknown")
        assertNotNull(a)
        assertTrue(a is FirefliesEffect && b is FirefliesEffect && a !== b)
    }

    private fun paint(effect: FirefliesEffect, bounds: Rectangle, clip: Rectangle): BufferedImage {
        val image = BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            // Same hints AtmospherePainter applies (GraphicsUtil.setupAAPainting): STROKE_PURE is what makes
            // the float geometry land at sub-pixel positions instead of being normalised to whole pixels.
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g.clip = clip
            effect.paint(g, clip)
        } finally {
            g.dispose()
        }
        return image
    }

    private fun countOpaque(image: BufferedImage, area: Rectangle): Int {
        var n = 0
        for (yy in area.y until area.y + area.height) {
            for (xx in area.x until area.x + area.width) {
                if ((image.getRGB(xx, yy) ushr 24) != 0) n++
            }
        }
        return n
    }

    /** Euclidean RGB distance between [expected] and the average of the most opaque painted pixels. */
    private fun colorDistance(image: BufferedImage, expected: Color): Double {
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        for (yy in 0 until image.height) {
            for (xx in 0 until image.width) {
                val p = image.getRGB(xx, yy)
                if ((p ushr 24) < 48) continue
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
                n++
            }
        }
        assertTrue("no sufficiently opaque pixels painted", n > 0)
        val dr = r.toDouble() / n - expected.red
        val dg = g.toDouble() / n - expected.green
        val db = b.toDouble() / n - expected.blue
        return sqrt(dr * dr + dg * dg + db * db)
    }
}
