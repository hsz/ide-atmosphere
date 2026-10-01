package info.chrzanowski.ideatmosphere.effects.sakura

import info.chrzanowski.ideatmosphere.core.AtmosphereEvent
import info.chrzanowski.ideatmosphere.core.AtmospherePalette
import info.chrzanowski.ideatmosphere.core.CommitPolicy
import info.chrzanowski.ideatmosphere.core.EffectContext
import info.chrzanowski.ideatmosphere.effects.AtmosphereEffects
import info.chrzanowski.ideatmosphere.render.DirtyRegionCollector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.roundToInt

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
    fun `adaptive commit reports at most one region per petal per tick`() {
        val effect = started()
        val bounds = Rectangle(0, 0, 800, 600)
        simulate(effect, bounds, 3f)

        val dirty = newCollector()
        var reported = 0
        val ticks = 300
        repeat(ticks) {
            dirty.clear()
            effect.update(1f / 30f, bounds, dirty)
            assertTrue(dirty.size <= effect.particleCount)
            reported += dirty.size
        }
        val ratio = reported.toDouble() / (ticks * effect.particleCount)
        // Petals fall at 15-35 px/s (0.5-1.2 px per 1/30 s tick, above the 0.4 px threshold), so at full frame rate
        // every visible petal is committed on every tick; skipping only kicks in for slower ticks and calm motion.
        assertTrue("commit ratio was $ratio", ratio <= 1.0)
        assertTrue("moving petals must still be reported, got $reported", reported > ticks)
    }

    @Test
    fun `a large tick commits every moving petal while hiccup ticks report nothing`() {
        val effect = started()
        val bounds = Rectangle(0, 0, 800, 600)
        simulate(effect, bounds, 3f)

        val dirty = newCollector()
        assertTrue(effect.update(0.1f, bounds, dirty))
        assertTrue(
            "expected nearly every petal dirty on a large tick, got ${dirty.size}/${effect.particleCount}",
            dirty.size >= effect.particleCount - 2,
        )

        // Tiny ticks move and turn petals by far less than the commit threshold (until the staleness bound).
        var reported = 0
        repeat(CommitPolicy.MAX_STALE_TICKS - 1) {
            dirty.clear()
            effect.update(0.0001f, bounds, dirty)
            reported += dirty.size
        }
        assertTrue("expected almost no dirty regions for tiny changes, got $reported", reported <= 3)
    }

    @Test
    fun `un-committed motion and rotation stay below the threshold and are committed within the staleness bound`() {
        val effect = started()
        val bounds = Rectangle(0, 0, 800, 600)
        simulate(effect, bounds, 3f)
        val x = floats(effect, "x")
        val y = floats(effect, "y")
        val rotation = floats(effect, "rotation")
        val size = floats(effect, "size")
        val prevX = floats(effect, "prevX")
        val prevY = floats(effect, "prevY")
        val prevRotation = floats(effect, "prevRotation")

        val dirty = newCollector()
        val stale = IntArray(effect.particleCount)
        repeat(300) {
            dirty.clear()
            effect.update(1f / 30f, bounds, dirty)
            for (i in 0 until effect.particleCount) {
                val dx = abs(x[i] - prevX[i])
                val dy = abs(y[i] - prevY[i])
                val turn = abs(rotation[i] - prevRotation[i]) * size[i]
                val limit = CommitPolicy.MOVE_THRESHOLD
                assertTrue("petal $i is $dx,$dy px (turn $turn) away from its painted state", dx < limit && dy < limit && turn < limit)
                if (dx == 0f && dy == 0f && turn == 0f) {
                    stale[i] = 0
                } else {
                    stale[i]++
                    assertTrue("petal $i went ${stale[i]} ticks without a repaint", stale[i] <= CommitPolicy.MAX_STALE_TICKS)
                }
            }
        }
    }

    @Test
    fun `adaptive commit keeps the screen covered by reported regions over many ticks`() {
        val effect = started()
        val bounds = Rectangle(0, 0, 400, 300)
        simulate(effect, bounds, 2f)
        var visibleChanges = 0
        repeat(60) {
            val before = paint(effect, bounds, bounds)
            val dirty = newCollector()
            effect.update(1f / 30f, bounds, dirty)
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
            if (merged.isNotEmpty()) visibleChanges++
        }
        assertTrue("the screen must keep changing", visibleChanges > 10)
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
    fun `cursor overlap escapes and steering decays after pointer leaves`() {
        val bounds = Rectangle(0, 0, 240, 240)
        val effect = stationaryEffect(bounds)
        effect.setSpeedScale(0.3f)
        effect.onPointerChanged(100f, 100f, true)
        simulate(effect, bounds, 0.5f)
        val positions = floats(effect, "x")
        assertTrue("overlapping petal should escape the cursor", positions[0] > 105f)
        assertEquals(100f, floats(effect, "y")[0], 0.001f)

        effect.onPointerChanged(100f, 100f, false)
        var previous = positions[0]
        effect.update(dt, bounds, newCollector())
        val initialStep = positions[0] - previous
        assertTrue("leaving should damp steering rather than stop abruptly", initialStep > 0f)
        repeat(24) { effect.update(dt, bounds, newCollector()) }
        previous = positions[0]
        effect.update(dt, bounds, newCollector())
        assertTrue("disabled pointer must not keep repelling", positions[0] - previous < initialStep * 0.02f)
    }

    @Test
    fun `success lifts petals in an updraft and then lets them fall again`() {
        val bounds = Rectangle(0, 0, 240, 240)
        val effect = stationaryEffect(bounds)
        val count = effect.particleCount
        val fallSpeed = floats(effect, "fallSpeed")
        for (i in 0 until count) {
            fallSpeed[i] = SakuraEffect.MIN_FALL_SPEED + (SakuraEffect.MAX_FALL_SPEED - SakuraEffect.MIN_FALL_SPEED) * i / (count - 1)
        }
        floats(effect, "angularVelocity").fill(1f)

        val normalFall = meanVerticalVelocity(effect, bounds)
        assertEquals("petals fall at their mean speed", 25f, normalFall, 0.5f)
        val normalTumble = tumbleStep(effect, bounds)

        effect.onEvent(AtmosphereEvent.SUCCESS)
        effect.update(0.2f, bounds, newCollector())
        assertEquals(1f, effect.glow, 0.001f)
        val lifted = meanVerticalVelocity(effect, bounds)
        assertTrue("updraft must carry the petals upward, got $lifted", lifted < 0f)
        assertTrue("updraft must quicken the tumble", tumbleStep(effect, bounds) > normalTumble * 1.2f)

        simulate(effect, bounds, 3f)
        assertEquals(0f, effect.glow, 0f)
        assertEquals("petals fall normally after the updraft", normalFall, meanVerticalVelocity(effect, bounds), 0.5f)
        assertTrue(effect.allInside(bounds))
    }

    @Test
    fun `success keeps the pink petal color and paints no halo`() {
        val bounds = Rectangle(0, 0, 240, 240)
        for (palette in arrayOf(AtmospherePalette.DARK, AtmospherePalette.LIGHT)) {
            val effect = stationaryEffect(bounds, palette)
            val normal = paint(effect, bounds, bounds)
            val normalCore = Color(normal.getRGB(100, 100), true)
            val normalAlpha = floats(effect, "alpha")[0]
            assertTrue(normalCore.alpha > 0)
            assertEquals(0, normal.getRGB(115, 100) ushr 24)

            effect.onEvent(AtmosphereEvent.SUCCESS)
            val dirty = newCollector()
            assertTrue(effect.update(0.2f, bounds, dirty))
            assertEquals(1f, effect.glow, 0.001f)
            val success = paint(effect, bounds, bounds)
            assertDirtyCovers(normal, success, dirty, bounds)
            val centerY = floats(effect, "y")[0].roundToInt()
            assertTrue("updraft moves the petal upward", centerY < 100)
            assertEquals("success must not change the petal color", normalCore, Color(success.getRGB(100, centerY), true))
            assertEquals("success must not change the body alpha", normalAlpha, floats(effect, "alpha")[0], 0f)
            assertEquals("success must not paint a halo", 0, success.getRGB(115, centerY) ushr 24)
        }
    }

    @Test
    fun `failure gust keeps one direction so repeated failures never cancel`() {
        val bounds = Rectangle(0, 0, 240, 240)
        val minimumDoubleImpulse = 2f * 0.8f * SakuraEffect.GUST_IMPULSE_MIN
        for (seed in 1L..6L) {
            val effect = stationaryEffect(bounds, seed = seed)
            effect.onEvent(AtmosphereEvent.FAILURE)
            effect.onEvent(AtmosphereEvent.FAILURE)
            val direction = effect.gustDirectionForTest()
            assertTrue("seed $seed must lock a direction", direction == 1f || direction == -1f)
            val vx = floats(effect, "vx")
            for (i in 0 until effect.particleCount) {
                assertTrue("seed $seed petal $i must be pushed the locked way", vx[i] * direction >= minimumDoubleImpulse)
            }
        }

        val effect = stationaryEffect(bounds)
        effect.onEvent(AtmosphereEvent.FAILURE)
        val direction = effect.gustDirectionForTest()
        val pushed = floats(effect, "vx")[0] * direction
        assertTrue(pushed > 0f)
        effect.update(dt, bounds, newCollector())
        assertEquals("the direction stays locked while the gust is active", direction, effect.gustDirectionForTest(), 0f)
        effect.onEvent(AtmosphereEvent.FAILURE)
        assertEquals(direction, effect.gustDirectionForTest(), 0f)
        assertTrue("a repeated failure pushes harder the same way", floats(effect, "vx")[0] * direction > pushed)

        simulate(effect, bounds, 3f)
        assertEquals(0f, effect.failureGlow, 0f)
        assertEquals("the lock lifts once the gust settles", 0f, effect.gustDirectionForTest(), 0f)
    }

    @Test
    fun `failure dims petals without tinting them and recovers`() {
        val bounds = Rectangle(0, 0, 240, 240)
        for (palette in arrayOf(AtmospherePalette.DARK, AtmospherePalette.LIGHT)) {
            val effect = stationaryEffect(bounds, palette)
            val normal = paint(effect, bounds, bounds)
            val normalCore = Color(normal.getRGB(100, 100), true)
            val normalAlpha = floats(effect, "alpha")[0]

            effect.onEvent(AtmosphereEvent.FAILURE)
            val vx = floats(effect, "vx")
            assertTrue("failure blows a gust", (0 until effect.particleCount).all { vx[it] != 0f })
            vx.fill(0f)
            val dirty = newCollector()
            assertTrue(effect.update(0.2f, bounds, dirty))
            assertEquals(0f, effect.glow, 0f)
            assertEquals(1f, effect.failureGlow, 0.001f)
            assertEquals(normalAlpha * (1f - SakuraEffect.FAILURE_ALPHA_DIM), floats(effect, "alpha")[0], 0.001f)
            val failure = paint(effect, bounds, bounds)
            assertDirtyCovers(normal, failure, dirty, bounds)
            val failureCore = Color(failure.getRGB(100, 100), true)
            assertTrue("failure dims the body", failureCore.alpha < normalCore.alpha * 0.8f)
            assertTrue("failure dims only by about a quarter", failureCore.alpha > normalCore.alpha * 0.7f)
            assertSameTint(normalCore, failureCore)

            simulate(effect, bounds, 3f)
            assertEquals(0f, effect.failureGlow, 0f)
            assertEquals(normalCore, Color(paint(effect, bounds, bounds).getRGB(100, 100), true))

            effect.onEvent(AtmosphereEvent.FAILURE)
            vx.fill(0f)
            effect.update(0.1f, bounds, newCollector())
            effect.onEvent(AtmosphereEvent.SUCCESS)
            effect.update(0.2f, bounds, newCollector())
            assertEquals("success cancels the dimming", 0f, effect.failureGlow, 0f)
            assertEquals(1f, effect.glow, 0.001f)
            effect.stop()
            effect.start(EffectContext.forTesting(palette = palette, seed = 42L))
            assertEquals(0f, effect.glow, 0f)
            assertEquals(0f, effect.failureGlow, 0f)
            assertEquals(0f, effect.gustDirectionForTest(), 0f)
        }
    }

    @Test
    fun `respawn and population reduction erase the old petal`() {
        val bounds = Rectangle(0, 0, 240, 240)
        val effect = stationaryEffect(bounds)
        val before = paint(effect, bounds, bounds)
        floats(effect, "life")[0] = floats(effect, "maxLife")[0]
        val dirty = newCollector()
        assertTrue(effect.update(dt, bounds, dirty))
        assertDirtyCovers(before, paint(effect, bounds, bounds), dirty, bounds)

        val largeBounds = Rectangle(0, 0, 1600, 1000)
        val large = stationaryEffect(largeBounds)
        val last = large.particleCount - 1
        large.placeParticleForTest(last, 180f, 100f, 0f, 0.8f)
        floats(large, "baseAlpha")[last] = 0.8f
        large.update(dt, largeBounds, newCollector())
        val beforeRemoval = paint(large, bounds, bounds)
        dirty.clear()
        assertTrue(large.update(dt, bounds, dirty))
        assertEquals(SakuraEffect.MIN_PARTICLES, large.particleCount)
        assertDirtyCovers(beforeRemoval, paint(large, bounds, bounds), dirty, bounds)
    }

    @Test
    fun `sakura exposes no preview or profile hooks`() {
        val names = SakuraEffect::class.java.methods.map { it.name }
        assertTrue(names.none { it.contains("Profile") || it.startsWith("preview") })
    }

    private fun meanVerticalVelocity(effect: SakuraEffect, bounds: Rectangle): Float {
        val before = mean(floats(effect, "y"), effect.particleCount)
        effect.update(dt, bounds, newCollector())
        return (mean(floats(effect, "y"), effect.particleCount) - before) / dt
    }

    private fun tumbleStep(effect: SakuraEffect, bounds: Rectangle): Float {
        val before = floats(effect, "rotation")[0]
        effect.update(dt, bounds, newCollector())
        return floats(effect, "rotation")[0] - before
    }

    private fun mean(values: FloatArray, count: Int): Float {
        var sum = 0.0
        for (i in 0 until count) sum += values[i]
        return (sum / count).toFloat()
    }

    private fun assertSameTint(expected: Color, actual: Color) {
        assertTrue("expected the pink of $expected, got $actual", abs(expected.red - actual.red) <= 2)
        assertTrue("expected the pink of $expected, got $actual", abs(expected.green - actual.green) <= 2)
        assertTrue("expected the pink of $expected, got $actual", abs(expected.blue - actual.blue) <= 2)
    }

    private fun stationaryEffect(bounds: Rectangle, palette: AtmospherePalette = AtmospherePalette.DARK, seed: Long = 42L): SakuraEffect {
        val effect = started(seed, palette)
        effect.update(dt, bounds, newCollector())
        for (name in arrayOf("vx", "fallSpeed", "swayPhase", "swayRate", "angularVelocity", "phase", "pulseRate", "baseAlpha")) {
            floats(effect, name).fill(0f)
        }
        floats(effect, "size").fill(8f)
        floats(effect, "life").fill(3f)
        floats(effect, "maxLife").fill(25f)
        for (i in 0 until effect.particleCount) effect.placeParticleForTest(i, 100f, 100f, 0f, 0f)
        floats(effect, "baseAlpha")[0] = 0.8f
        effect.update(dt, bounds, newCollector())
        return effect
    }

    private fun floats(effect: SakuraEffect, name: String): FloatArray {
        val field = SakuraEffect::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(effect) as FloatArray
    }

    private fun assertDirtyCovers(before: BufferedImage, after: BufferedImage, dirty: DirtyRegionCollector, bounds: Rectangle) {
        val merged = ArrayList<Rectangle>()
        dirty.merge(bounds) { merged.add(Rectangle(it)) }
        var changed = false
        for (yy in 0 until bounds.height) {
            for (xx in 0 until bounds.width) {
                if (before.getRGB(xx, yy) != after.getRGB(xx, yy)) {
                    changed = true
                    assertTrue("changed pixel ($xx,$yy) is not covered by a dirty rect", merged.any { it.contains(xx, yy) })
                }
            }
        }
        assertTrue("expected changed pixels", changed)
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
