package info.chrzanowski.ideatmosphere.effects.snow

import info.chrzanowski.ideatmosphere.core.AtmosphereEvent
import info.chrzanowski.ideatmosphere.core.AtmospherePalette
import info.chrzanowski.ideatmosphere.core.EffectContext
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
import kotlin.math.sqrt

class SnowEffectTest {

    private val dt = 1f / 24f

    private fun started(seed: Long = 42L, palette: AtmospherePalette = AtmospherePalette.DARK): SnowEffect =
        SnowEffect().also { it.start(EffectContext.forTesting(palette = palette, seed = seed)) }

    private fun newCollector() = DirtyRegionCollector(SnowEffect.MAX_PARTICLES * 2)

    /** Runs [seconds] of simulation; returns how many updates reported a change. */
    private fun simulate(effect: SnowEffect, bounds: Rectangle, seconds: Float, dirty: DirtyRegionCollector = newCollector()): Int {
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
    fun `effect id is snow`() {
        assertEquals("snow", SnowEffect.ID)
        assertEquals(SnowEffect.ID, SnowEffect().id)
    }

    @Test
    fun `particle count is clamped to 15-50 and scales with area and intensity`() {
        assertEquals(16, SnowEffect.particleCountFor(800, 600))
        assertEquals(15, SnowEffect.particleCountFor(400, 300))
        assertEquals(47, SnowEffect.particleCountFor(1512, 949))
        assertEquals(50, SnowEffect.particleCountFor(3840, 2160))
        assertEquals(32, SnowEffect.particleCountFor(800, 600, 2f))
        assertEquals(15, SnowEffect.particleCountFor(800, 600, 0.3f))

        val small = started()
        small.update(dt, Rectangle(0, 0, 800, 600), newCollector())
        assertEquals(16, small.particleCount)

        val large = started()
        large.update(dt, Rectangle(0, 0, 3840, 2160), newCollector())
        assertEquals(50, large.particleCount)
    }

    @Test
    fun `intensity changes the population on the next update and is clamped`() {
        val effect = started()
        val bounds = Rectangle(0, 0, 800, 600)
        effect.update(dt, bounds, newCollector())
        assertEquals(16, effect.particleCount)

        effect.setIntensity(2f)
        assertEquals("population changes on the next tick, not immediately", 16, effect.particleCount)
        effect.update(dt, bounds, newCollector())
        assertEquals(32, effect.particleCount)

        effect.setIntensity(5f)
        effect.update(dt, bounds, newCollector())
        assertEquals("intensity is clamped to 2", 32, effect.particleCount)

        effect.setIntensity(0.1f)
        val dirty = newCollector()
        assertTrue(effect.update(dt, bounds, dirty))
        assertEquals("intensity is clamped to 0.3", 15, effect.particleCount)
        assertTrue("removed flakes must be reported for clearing", dirty.size > 0)

        effect.setIntensity(Float.NaN)
        effect.update(dt, bounds, newCollector())
        assertEquals("non-finite intensity falls back to 1", 16, effect.particleCount)
    }

    @Test
    fun `start distributes flakes over the whole area and layers them by depth`() {
        val effect = started()
        val bounds = Rectangle(0, 40, 1512, 909)
        effect.update(0.001f, bounds, newCollector())
        assertEquals(45, effect.particleCount)
        assertTrue(effect.allInside(bounds))

        val xs = floats(effect, "x")
        val ys = floats(effect, "y")
        val depth = floats(effect, "depth")
        val radius = floats(effect, "radius")
        val baseAlpha = floats(effect, "baseAlpha")
        val fallSpeed = floats(effect, "fallSpeed")
        var upper = 0
        var lower = 0
        for (i in 0 until effect.particleCount) {
            assertTrue("x[$i]=${xs[i]} outside the bounds", xs[i] in -1f..1513f)
            assertTrue("y[$i]=${ys[i]} outside the bounds", ys[i] in 39f..950f)
            if (ys[i] < 40f + 909f * 0.25f) upper++
            if (ys[i] > 40f + 909f * 0.75f) lower++

            // Nearer (depth → 1) means larger, brighter and faster.
            val d = depth[i]
            assertTrue("depth[$i]=$d", d in 0f..1f)
            assertEquals(SnowEffect.MIN_RADIUS + (SnowEffect.MAX_RADIUS - SnowEffect.MIN_RADIUS) * d, radius[i], 1e-4f)
            assertEquals(SnowEffect.MIN_BASE_ALPHA + (SnowEffect.MAX_BASE_ALPHA - SnowEffect.MIN_BASE_ALPHA) * d, baseAlpha[i], 1e-4f)
            val parallax = 0.5f + 0.5f * d
            assertTrue(
                "fallSpeed[$i]=${fallSpeed[i]} for depth $d",
                fallSpeed[i] >= SnowEffect.MIN_FALL_SPEED * parallax - 1e-3f && fallSpeed[i] <= SnowEffect.MAX_FALL_SPEED * parallax + 1e-3f,
            )
        }
        assertTrue("the initial fill should reach the upper quarter, got $upper flakes", upper > 0)
        assertTrue("the initial fill should reach the lower quarter, got $lower flakes", lower > 0)
    }

    @Test
    fun `flakes fall downward on average at a calm pace`() {
        val effect = started()
        val bounds = Rectangle(0, 0, 800, 600)
        simulate(effect, bounds, 1f)
        val vy = effect.meanVelocityY()
        assertTrue("expected a calm downward drift, got $vy px/s", vy > 5f && vy < 45f)
    }

    @Test
    fun `particles stay inside bounds after 60 seconds`() {
        val effect = started()
        val bounds = Rectangle(0, 40, 1512, 909)
        val changed = simulate(effect, bounds, 60f)
        assertTrue("simulation should report changes", changed > 0)
        assertTrue(effect.allInside(bounds))
        assertEquals(SnowEffect.particleCountFor(1512, 909), effect.particleCount)
    }

    @Test
    fun `shrinking bounds respawns particles inside the new bounds within one tick`() {
        val effect = started()
        simulate(effect, Rectangle(0, 0, 1600, 1200), 5f)
        assertEquals(50, effect.particleCount)

        val halved = Rectangle(0, 0, 1600, 600)
        val dirty = newCollector()
        assertTrue(effect.update(dt, halved, dirty))
        assertEquals(32, effect.particleCount)
        assertTrue(effect.allInside(halved))
        assertTrue("vanished flakes inside the new bounds must be reported for clearing", dirty.size > 0)

        val tiny = Rectangle(0, 0, 400, 300)
        effect.update(dt, tiny, newCollector())
        assertEquals(15, effect.particleCount)
        assertTrue(effect.allInside(tiny))
    }

    @Test
    fun `zero or invalid delta and empty bounds emit nothing`() {
        val effect = started()
        val bounds = Rectangle(0, 0, 800, 600)
        simulate(effect, bounds, 2f)
        val dirty = newCollector()
        assertFalse(effect.update(0f, bounds, dirty))
        assertFalse(effect.update(-dt, bounds, dirty))
        assertFalse(effect.update(Float.NaN, bounds, dirty))
        assertFalse(effect.update(Float.POSITIVE_INFINITY, bounds, dirty))
        assertFalse(effect.update(dt, Rectangle(0, 0, 0, 600), dirty))
        assertFalse(effect.update(dt, Rectangle(0, 0, 800, 0), dirty))
        assertEquals(0, dirty.size)
    }

    @Test
    fun `update before start or after stop is a no-op and stop clears the outcome state`() {
        val effect = SnowEffect()
        val bounds = Rectangle(0, 0, 800, 600)
        val dirty = newCollector()
        assertFalse(effect.update(dt, bounds, dirty))
        assertEquals(0, dirty.size)

        effect.start(EffectContext.forTesting())
        simulate(effect, bounds, 1f)
        effect.onEvent(AtmosphereEvent.SUCCESS)
        effect.update(SnowEffect.UPDRAFT_ATTACK_SECONDS, bounds, newCollector())
        assertEquals(1f, effect.glow, 0.001f)

        effect.stop()
        assertEquals(0, effect.particleCount)
        assertEquals(0f, effect.glow, 0f)
        assertEquals(0f, effect.failureGlow, 0f)
        assertFalse(effect.update(dt, bounds, dirty))
        assertEquals(0, dirty.size)

        effect.start(EffectContext.forTesting())
        assertTrue(effect.update(dt, bounds, newCollector()))
        assertEquals(16, effect.particleCount)
    }

    @Test
    fun `dirty reporting is continuous with a small epsilon`() {
        val effect = started()
        val bounds = Rectangle(0, 0, 800, 600)
        simulate(effect, bounds, 3f)

        // A 1/30 s tick moves even the slowest flake by 0.2 px (>= MOVE_EPSILON), so every flake on screen is
        // dirty; only flakes still descending through the spawn band above the top edge have nothing to report.
        val dirty = newCollector()
        assertTrue(effect.update(1f / 30f, bounds, dirty))
        assertTrue(
            "expected most flakes dirty on a normal tick, got ${dirty.size}/${effect.particleCount}",
            dirty.size >= effect.particleCount * 3 / 4,
        )

        // Four 0.1 ms ticks move a flake by at most 0.016 px in total and change alpha by far less than one
        // 8-bit step, so (almost) nothing needs repainting.
        var reported = 0
        repeat(4) {
            dirty.clear()
            effect.update(0.0001f, bounds, dirty)
            reported += dirty.size
        }
        assertTrue("expected almost no dirty regions for invisible changes, got $reported", reported <= 3)

        // Sub-epsilon motion accumulates in `x/y` while `prev*` (on-screen state) stays put, and is reported
        // once the accumulated displacement becomes visible.
        dirty.clear()
        assertTrue(effect.update(1f / 30f, bounds, dirty))
        assertTrue(dirty.size >= effect.particleCount * 3 / 4)
    }

    @Test
    fun `dirty regions cover every painted flake`() {
        val effect = started()
        val bounds = Rectangle(0, 0, 800, 600)
        simulate(effect, bounds, 3f)

        // Paint the full frame, then compare with the union of the dirty rects of the next tick.
        val before = paint(effect, bounds, bounds)
        val dirty = newCollector()
        assertTrue(effect.update(dt, bounds, dirty))
        val after = paint(effect, bounds, bounds)
        val merged = mergedRects(dirty, bounds)
        assertTrue(merged.isNotEmpty() && merged.size <= dirty.maxRects)
        assertTrue("something must have changed on screen", assertCovered(before, after, merged))
    }

    @Test
    fun `a flake leaving through the bottom is cleared and respawns above the top edge fading in`() {
        val effect = started()
        val bounds = Rectangle(0, 0, 800, 600)
        simulate(effect, bounds, 2f)
        effect.placeParticleForTest(0, 400f, 599.5f, 0.8f)
        val ys = floats(effect, "y")
        val alpha = floats(effect, "alpha")

        var ticks = 0
        while (ys[0] > 0f && ticks < 60) {
            val before = paint(effect, bounds, bounds)
            val dirty = newCollector()
            effect.update(dt, bounds, dirty)
            assertCovered(before, paint(effect, bounds, bounds), mergedRects(dirty, bounds))
            ticks++
        }
        assertTrue("flake 0 should have left through the bottom and respawned above the top edge", ys[0] < 0f)
        assertEquals("a respawned flake starts invisible", 0f, alpha[0], 0f)

        // One tick later it is barely visible; half a second later still well below its base opacity: no pop.
        effect.update(dt, bounds, newCollector())
        assertTrue("a respawned flake fades in from zero, got alpha ${alpha[0]}", alpha[0] > 0f && alpha[0] < 0.05f)
        simulate(effect, bounds, 0.5f)
        assertTrue("a respawned flake fades in gradually, got alpha ${alpha[0]}", alpha[0] < 0.3f)
    }

    @Test
    fun `paint uses only the cached color table and never rebuilds it`() {
        val bounds = Rectangle(0, 0, 40, 40)
        val effect = started()
        effect.update(dt, bounds, newCollector())
        assertEquals(256, SnowEffect.ALPHA_STEPS)
        assertEquals(256, effect.colorTableSizeForTest())
        assertEquals(1, effect.colorTableRebuildsForTest())

        for (i in 0 until effect.particleCount) effect.placeParticleForTest(i, -100f, -100f, 0f)
        effect.placeParticleForTest(0, 20.5f, 20.5f, 0.8f)
        val image = paint(effect, bounds, bounds)
        repeat(20) { paint(effect, bounds, bounds) }
        assertEquals("paint must never rebuild the color table", 1, effect.colorTableRebuildsForTest())
        assertEquals(256, effect.colorTableSizeForTest())

        // The fully covered centre pixel carries the cached table entry: theme RGB with the flake's 8-bit alpha.
        val centre = Color(image.getRGB(20, 20), true)
        val expected = SnowEffect.DARK_FLAKE_COLOR
        assertEquals(expected.red.toFloat(), centre.red.toFloat(), 2f)
        assertEquals(expected.green.toFloat(), centre.green.toFloat(), 2f)
        assertEquals(expected.blue.toFloat(), centre.blue.toFloat(), 2f)
        assertEquals(204f, centre.alpha.toFloat(), 3f)

        simulate(effect, bounds, 1f)
        assertEquals("update must not rebuild the color table either", 1, effect.colorTableRebuildsForTest())
    }

    @Test
    fun `flakes are rendered at sub-pixel positions`() {
        val bounds = Rectangle(0, 0, 40, 40)
        val whole = columnCoverage(paintSingle(10.0f), bounds)
        val half = columnCoverage(paintSingle(10.5f), bounds)
        assertTrue("nothing painted", whole.sum() > 0)
        assertFalse("x=10.0 and x=10.5 must not produce identical coverage", whole.contentEquals(half))
        val shiftedByOne = LongArray(bounds.width) { xx -> if (xx > 0) whole[xx - 1] else 0L }
        assertFalse("x=10.5 must not simply be x=10.0 snapped to the next pixel", shiftedByOne.contentEquals(half))
    }

    @Test
    fun `offscreen paint writes only inside the clip and is visible`() {
        val effect = started()
        val bounds = Rectangle(0, 0, 800, 600)
        simulate(effect, bounds, 3f)

        val full = paint(effect, bounds, bounds)
        assertTrue("flakes should be visible after fading in", countOpaque(full, bounds) > 0)

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
    fun `paint uses theme flake colors and rebuilds them on palette change`() {
        val effect = started(palette = AtmospherePalette.DARK)
        val bounds = Rectangle(0, 0, 800, 600)
        simulate(effect, bounds, 3f)
        val dark = paint(effect, bounds, bounds)
        assertTrue(colorDistance(dark, SnowEffect.DARK_FLAKE_COLOR) < 8.0)
        assertEquals(1, effect.colorTableRebuildsForTest())

        effect.onPaletteChanged(AtmospherePalette.LIGHT)
        assertEquals(2, effect.colorTableRebuildsForTest())
        assertEquals(256, effect.colorTableSizeForTest())
        val light = paint(effect, bounds, bounds)
        assertTrue(colorDistance(light, SnowEffect.LIGHT_FLAKE_COLOR) < 8.0)
        assertTrue(colorDistance(light, SnowEffect.DARK_FLAKE_COLOR) > 100.0)

        // The light palette also reduces the opacity (× opacityScale) from the next update on.
        val stationary = stationaryEffect(bounds)
        val darkAlpha = sumAlpha(stationary)
        stationary.onPaletteChanged(AtmospherePalette.LIGHT)
        stationary.update(dt, bounds, newCollector())
        assertEquals(darkAlpha * AtmospherePalette.LIGHT.opacityScale, sumAlpha(stationary), darkAlpha * 0.001f)
    }

    @Test
    fun `speed scale multiplies the fall and is clamped`() {
        val bounds = Rectangle(0, 0, 800, 600)
        val normal = started()
        normal.update(dt, bounds, newCollector())
        val fast = started()
        fast.setSpeedScale(2f)
        fast.update(dt, bounds, newCollector())
        assertEquals(2f, fast.speedMultiplier, 0f)
        assertTrue(normal.meanVelocityY() > 0f)
        assertEquals(normal.meanVelocityY() * 2f, fast.meanVelocityY(), 0.01f)

        fast.setSpeedScale(10f)
        assertEquals(2f, fast.speedMultiplier, 0f)
        fast.setSpeedScale(0.1f)
        assertEquals(0.3f, fast.speedMultiplier, 0f)
        fast.setSpeedScale(Float.NaN)
        assertEquals(1f, fast.speedMultiplier, 0f)
    }

    @Test
    fun `idle and activity events move the speed multiplier smoothly`() {
        val effect = started()
        val bounds = Rectangle(0, 0, 800, 600)
        simulate(effect, bounds, 1f)
        val cruising = effect.meanVelocityY()

        effect.onEvent(AtmosphereEvent.USER_IDLE)
        assertEquals(1f, effect.speedMultiplier, 0f)
        effect.update(dt, bounds, newCollector())
        assertTrue("should interpolate, not jump", effect.speedMultiplier > 0.9f && effect.speedMultiplier < 1f)
        simulate(effect, bounds, 3f)
        assertEquals(SnowEffect.IDLE_SPEED, effect.speedMultiplier, 0.001f)
        assertTrue("idle flakes fall slower", effect.meanVelocityY() < cruising * 0.7f)

        effect.onEvent(AtmosphereEvent.USER_ACTIVE)
        simulate(effect, bounds, 4f)
        assertEquals(SnowEffect.NORMAL_SPEED, effect.speedMultiplier, 0.001f)

        effect.onEvent(AtmosphereEvent.ACTIVITY_STARTED)
        simulate(effect, bounds, 3f)
        assertEquals(SnowEffect.ACTIVITY_SPEED, effect.speedMultiplier, 0.001f)

        effect.onEvent(AtmosphereEvent.ACTIVITY_FINISHED)
        simulate(effect, bounds, 4f)
        assertEquals(SnowEffect.NORMAL_SPEED, effect.speedMultiplier, 0.001f)

        // The speed scale multiplies the idle and busy levels as well.
        effect.setSpeedScale(2f)
        effect.onEvent(AtmosphereEvent.USER_IDLE)
        simulate(effect, bounds, 3f)
        assertEquals(SnowEffect.IDLE_SPEED * 2f, effect.speedMultiplier, 0.001f)
    }

    @Test
    fun `success lifts the flakes briefly without touching the colors, then recovers`() {
        val effect = started()
        val bounds = Rectangle(0, 0, 800, 600)
        simulate(effect, bounds, 1f)
        assertEquals(0f, effect.glow, 0f)
        assertTrue(effect.meanVelocityY() > 0f)
        val rebuilds = effect.colorTableRebuildsForTest()

        effect.onEvent(AtmosphereEvent.SUCCESS)
        assertTrue("the updraft eases in rather than jumping", effect.glow < 1f)
        effect.update(SnowEffect.UPDRAFT_ATTACK_SECONDS, bounds, newCollector())
        assertEquals(1f, effect.glow, 0.001f)
        val lift = effect.meanVelocityY()
        assertTrue("flakes should rise at the peak of the updraft, got $lift px/s", lift < -SnowEffect.UPDRAFT_SPEED * 0.5f)
        assertTrue("the lift stays bounded, got $lift px/s", lift > -SnowEffect.UPDRAFT_SPEED - 1f)
        assertEquals("the updraft is motion only", rebuilds, effect.colorTableRebuildsForTest())

        simulate(effect, bounds, SnowEffect.UPDRAFT_DECAY_SECONDS + 0.5f)
        assertEquals(0f, effect.glow, 0.001f)
        assertTrue("normal fall resumes", effect.meanVelocityY() > 0f)
        assertTrue(effect.allInside(bounds))

        // A failure cuts a running updraft short smoothly instead of snapping it to zero.
        effect.onEvent(AtmosphereEvent.SUCCESS)
        effect.update(SnowEffect.UPDRAFT_ATTACK_SECONDS, bounds, newCollector())
        assertEquals(1f, effect.glow, 0.001f)
        effect.onEvent(AtmosphereEvent.FAILURE)
        effect.update(0.2f, bounds, newCollector())
        assertTrue("eased out, not snapped: glow=${effect.glow}", effect.glow > 0f && effect.glow < 1f)
        assertTrue(effect.failureGlow > 0f)
        effect.update(0.3f, bounds, newCollector())
        assertEquals(0f, effect.glow, 0.001f)
    }

    @Test
    fun `failure gusts sideways in one consistent direction and dims the flakes temporarily`() {
        val bounds = Rectangle(0, 0, 800, 600)
        val effect = stationaryEffect(bounds)
        val alphaBefore = sumAlpha(effect)
        assertTrue(alphaBefore > 0f)
        assertEquals(0f, effect.failureGlow, 0f)
        val rebuilds = effect.colorTableRebuildsForTest()

        effect.onEvent(AtmosphereEvent.FAILURE)
        effect.update(SnowEffect.GUST_ATTACK_SECONDS, bounds, newCollector())
        assertEquals(1f, effect.failureGlow, 0.001f)
        val gust = effect.meanVelocityX()
        assertTrue("the gust should dominate the breeze, got $gust px/s", abs(gust) > SnowEffect.GUST_SPEED * 0.3f)
        assertEquals(
            "flakes dim by FAILURE_DIM at the peak of the gust",
            alphaBefore * (1f - SnowEffect.FAILURE_DIM),
            sumAlpha(effect),
            alphaBefore * 0.001f,
        )

        // A second failure while the gust is still active keeps blowing the same way.
        effect.onEvent(AtmosphereEvent.FAILURE)
        effect.update(SnowEffect.GUST_ATTACK_SECONDS, bounds, newCollector())
        assertEquals(1f, effect.failureGlow, 0.001f)
        val again = effect.meanVelocityX()
        assertTrue("the gust direction must not flip: $gust then $again", gust * again > 0f && abs(again) > SnowEffect.GUST_SPEED * 0.3f)
        assertEquals("the gust is motion and dimming only", rebuilds, effect.colorTableRebuildsForTest())

        simulate(effect, bounds, SnowEffect.GUST_DECAY_SECONDS + 1f)
        assertEquals(0f, effect.failureGlow, 0f)
        assertEquals("opacity recovers fully", alphaBefore, sumAlpha(effect), alphaBefore * 0.001f)
        assertTrue(effect.allInside(bounds))
    }

    @Test
    fun `cursor avoidance pushes a nearby flake away and settles after the pointer leaves`() {
        val bounds = Rectangle(0, 0, 400, 400)
        val effect = started()
        effect.update(dt, bounds, newCollector())
        floats(effect, "fallSpeed").fill(0f)
        floats(effect, "swayAmplitude").fill(0f)
        effect.setSpeedScale(0.3f)
        effect.placeParticleForTest(0, 100f, 110f, 0.8f)
        val xs = floats(effect, "x")
        val ys = floats(effect, "y")

        effect.onPointerChanged(100f, 100f, true)
        simulate(effect, bounds, 0.5f)
        assertTrue("a flake just below the cursor should be pushed further down, got y=${ys[0]}", ys[0] > 115f)
        assertTrue("the push is radial, so x barely changes, got x=${xs[0]}", abs(xs[0] - 100f) < 10f)

        effect.onPointerChanged(100f, 100f, false)
        var previous = ys[0]
        effect.update(dt, bounds, newCollector())
        val initialStep = ys[0] - previous
        assertTrue("leaving should damp steering rather than stop abruptly", initialStep > 0f)
        repeat(24) { effect.update(dt, bounds, newCollector()) }
        previous = ys[0]
        effect.update(dt, bounds, newCollector())
        assertTrue("disabled pointer must not keep repelling", ys[0] - previous < initialStep * 0.02f)
    }

    /** Effect with every flake parked at the centre of [bounds], fully faded in and free of fall, sway and twinkle. */
    private fun stationaryEffect(bounds: Rectangle): SnowEffect {
        val effect = started()
        effect.update(dt, bounds, newCollector())
        for (name in arrayOf("fallSpeed", "swayAmplitude", "twinkleRate")) floats(effect, name).fill(0f)
        floats(effect, "fadeIn").fill(1f)
        for (i in 0 until effect.particleCount) {
            effect.placeParticleForTest(i, bounds.width / 2f, bounds.height / 2f, 0f)
        }
        effect.update(dt, bounds, newCollector())
        return effect
    }

    private fun floats(effect: SnowEffect, name: String): FloatArray {
        val field = SnowEffect::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(effect) as FloatArray
    }

    private fun sumAlpha(effect: SnowEffect): Float {
        val alpha = floats(effect, "alpha")
        var sum = 0f
        for (i in 0 until effect.particleCount) sum += alpha[i]
        return sum
    }

    /** Paints a single flake at ([px], 20) on a 40×40 canvas, with all the others hidden. */
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

    private fun mergedRects(dirty: DirtyRegionCollector, bounds: Rectangle): List<Rectangle> {
        val merged = ArrayList<Rectangle>()
        dirty.merge(bounds) { merged.add(Rectangle(it)) }
        return merged
    }

    /** Asserts every pixel that differs between [before] and [after] lies in a [merged] rect; returns whether any did. */
    private fun assertCovered(before: BufferedImage, after: BufferedImage, merged: List<Rectangle>): Boolean {
        var changed = false
        for (yy in 0 until before.height) {
            for (xx in 0 until before.width) {
                if (before.getRGB(xx, yy) != after.getRGB(xx, yy)) {
                    changed = true
                    assertTrue("changed pixel ($xx,$yy) is not covered by a dirty rect", merged.any { it.contains(xx, yy) })
                }
            }
        }
        return changed
    }

    private fun paint(effect: SnowEffect, bounds: Rectangle, clip: Rectangle): BufferedImage {
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
