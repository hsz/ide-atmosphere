package info.chrzanowski.ideatmosphere.effects.rain

import info.chrzanowski.ideatmosphere.core.AtmosphereEvent
import info.chrzanowski.ideatmosphere.core.AtmospherePalette
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
import java.lang.management.ManagementFactory
import kotlin.math.abs
import kotlin.math.sqrt

class RainEffectTest {

    private val dt = 1f / 24f

    private fun started(seed: Long = 42L, palette: AtmospherePalette = AtmospherePalette.DARK): RainEffect =
        RainEffect().also { it.start(EffectContext.forTesting(palette = palette, seed = seed)) }

    private fun newCollector() = DirtyRegionCollector((RainEffect.MAX_PARTICLES + RainEffect.MAX_SPLASHES) * 2)

    /** Runs [seconds] of simulation; returns how many updates reported a change. */
    private fun simulate(effect: RainEffect, bounds: Rectangle, seconds: Float, dirty: DirtyRegionCollector = newCollector()): Int {
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
    fun `effect id is rain and the effect is registered`() {
        assertEquals("rain", RainEffect.ID)
        assertEquals(RainEffect.ID, RainEffect().id)
        assertTrue(RainEffect.ID in AtmosphereEffects.ids)
        assertTrue(AtmosphereEffects.create(RainEffect.ID) is RainEffect)
    }

    @Test
    fun `particle count is clamped to 20-70 and scales with area and intensity`() {
        assertEquals(24, RainEffect.particleCountFor(800, 600))
        assertEquals(20, RainEffect.particleCountFor(400, 300))
        assertEquals(70, RainEffect.particleCountFor(1512, 949))
        assertEquals(70, RainEffect.particleCountFor(3840, 2160))
        assertEquals(48, RainEffect.particleCountFor(800, 600, 2f))
        assertEquals(20, RainEffect.particleCountFor(800, 600, 0.3f))

        val small = started()
        small.update(dt, Rectangle(0, 0, 800, 600), newCollector())
        assertEquals(24, small.particleCount)

        val large = started()
        large.update(dt, Rectangle(0, 0, 3840, 2160), newCollector())
        assertEquals(70, large.particleCount)
    }

    @Test
    fun `intensity changes the population on the next update and is clamped`() {
        val effect = started()
        val bounds = Rectangle(0, 0, 800, 600)
        effect.update(dt, bounds, newCollector())
        assertEquals(24, effect.particleCount)

        effect.setIntensity(2f)
        assertEquals("population changes on the next tick, not immediately", 24, effect.particleCount)
        effect.update(dt, bounds, newCollector())
        assertEquals(48, effect.particleCount)

        effect.setIntensity(5f)
        effect.update(dt, bounds, newCollector())
        assertEquals("intensity is clamped to 2", 48, effect.particleCount)

        effect.setIntensity(0.1f)
        val dirty = newCollector()
        assertTrue(effect.update(dt, bounds, dirty))
        assertEquals("intensity is clamped to 0.3", 20, effect.particleCount)
        assertTrue("removed drops must be reported for clearing", dirty.size > 0)

        effect.setIntensity(Float.NaN)
        effect.update(dt, bounds, newCollector())
        assertEquals("non-finite intensity falls back to 1", 24, effect.particleCount)
    }

    @Test
    fun `start distributes drops above their landing lines and layers them by depth`() {
        val effect = started()
        val bounds = Rectangle(0, 40, 1512, 909)
        effect.update(0.001f, bounds, newCollector())
        assertEquals(68, effect.particleCount)
        assertTrue(effect.allInside(bounds))

        val ys = floats(effect, "y")
        val landY = floats(effect, "landY")
        val depth = floats(effect, "depth")
        val length = floats(effect, "length")
        val width = floats(effect, "width")
        val baseAlpha = floats(effect, "baseAlpha")
        val fallSpeed = floats(effect, "fallSpeed")
        var upper = 0
        var lower = 0
        for (i in 0 until effect.particleCount) {
            assertTrue("y[$i]=${ys[i]} is below its landing line ${landY[i]}", ys[i] <= landY[i])
            assertTrue("landing line ${landY[i]} outside the frame", landY[i] >= 40f + 909f * RainEffect.LAND_MIN - 0.01f && landY[i] <= 949f)
            if (ys[i] < 40f + 909f * 0.25f) upper++
            if (ys[i] > 40f + 909f * 0.5f) lower++

            // Nearer (depth → 1) means longer, thicker, brighter and faster.
            val d = depth[i]
            assertTrue("depth[$i]=$d", d in 0f..1f)
            assertEquals(RainEffect.MIN_LENGTH + (RainEffect.MAX_LENGTH - RainEffect.MIN_LENGTH) * d, length[i], 1e-3f)
            assertEquals(RainEffect.MIN_WIDTH + (RainEffect.MAX_WIDTH - RainEffect.MIN_WIDTH) * d, width[i], 1e-4f)
            assertEquals(RainEffect.MIN_BASE_ALPHA + (RainEffect.MAX_BASE_ALPHA - RainEffect.MIN_BASE_ALPHA) * d, baseAlpha[i], 1e-4f)
            val parallax = 0.5f + 0.5f * d
            assertTrue(
                "fallSpeed[$i]=${fallSpeed[i]} for depth $d",
                fallSpeed[i] >= RainEffect.MIN_FALL_SPEED * parallax - 1e-3f && fallSpeed[i] <= RainEffect.MAX_FALL_SPEED * parallax + 1e-3f,
            )
        }
        assertTrue("the initial fill should reach the upper quarter, got $upper drops", upper > 0)
        assertTrue("the initial fill should reach the lower half, got $lower drops", lower > 0)
    }

    @Test
    fun `drops fall quickly downwards and lean along the fall`() {
        val effect = started()
        val bounds = Rectangle(0, 0, 800, 600)
        simulate(effect, bounds, 1f)
        val vy = effect.meanVelocityY()
        assertTrue("expected a brisk downward fall, got $vy px/s", vy > 150f && vy < RainEffect.MAX_FALL_SPEED)
        assertTrue("rain leans rightwards, got ${effect.meanVelocityX()} px/s", effect.meanVelocityX() > 0f)

        // Every streak trails behind its head: the tail lies above it.
        val tailY = floats(effect, "tailY")
        for (i in 0 until effect.particleCount) assertTrue("tail of drop $i should trail upwards, got ${tailY[i]}", tailY[i] < 0f)
    }

    @Test
    fun `drops splash where they land and splashes fade away by themselves`() {
        val effect = started()
        val bounds = Rectangle(0, 0, 800, 600)
        var seen = false
        var elapsed = 0f
        val dirty = newCollector()
        while (elapsed < 20f) {
            dirty.clear()
            effect.update(dt, bounds, dirty)
            if (effect.splashCount > 0) seen = true
            assertTrue(effect.splashCount in 0..RainEffect.MAX_SPLASHES)
            assertTrue("regions per tick are bounded, got ${dirty.size}", dirty.size <= RainEffect.MAX_PARTICLES + RainEffect.MAX_SPLASHES)
            assertFalse(dirty.overflowed)
            elapsed += dt
        }
        assertTrue("landing drops should leave splashes", seen)

        // A single landing, isolated from the rest of the field.
        val one = started()
        one.update(dt, bounds, newCollector())
        floats(one, "landY").fill(1e6f)
        floats(one, "depth").fill(1f)
        floats(one, "fallSpeed").fill(0f)
        one.placeParticleForTest(0, 400f, 300f, 0.8f)
        floats(one, "landY")[0] = 305f
        floats(one, "fallSpeed")[0] = 600f
        val before = paint(one, bounds, bounds)
        val dirtyOne = newCollector()
        one.update(dt, bounds, dirtyOne)
        assertEquals("the landing drop leaves exactly one splash", 1, one.splashCount)
        assertTrue("the landed drop respawns above the top edge", floats(one, "y")[0] <= 0f)
        assertCovered(before, paint(one, bounds, bounds), mergedRects(dirtyOne, bounds))

        // Hide the drops so that only the splash remains: it is painted at the landing line, then fades out and is cleared.
        floats(one, "baseAlpha").fill(0f)
        floats(one, "fallSpeed").fill(0f)
        var painted = false
        repeat(40) { tick ->
            val image = paint(one, bounds, bounds)
            if (tick >= 1 && countOpaque(image, Rectangle(380, 295, 40, 20)) > 0) painted = true
            val dirtyTick = newCollector()
            one.update(dt, bounds, dirtyTick)
            assertCovered(image, paint(one, bounds, bounds), mergedRects(dirtyTick, bounds))
        }
        assertTrue("the splash must be visible near the landing point", painted)
        assertEquals("splashes live for a fraction of a second", 0, one.splashCount)
        assertEquals("a finished splash leaves no pixels behind", 0, countOpaque(paint(one, bounds, bounds), Rectangle(380, 295, 40, 20)))
    }

    @Test
    fun `particles stay inside bounds after 60 seconds`() {
        val effect = started()
        val bounds = Rectangle(0, 40, 1512, 909)
        val changed = simulate(effect, bounds, 60f)
        assertTrue("simulation should report changes", changed > 0)
        assertTrue(effect.allInside(bounds))
        assertEquals(RainEffect.particleCountFor(1512, 909), effect.particleCount)
    }

    @Test
    fun `shrinking bounds respawns particles inside the new bounds and clears splashes`() {
        val effect = started()
        simulate(effect, Rectangle(0, 0, 1600, 1200), 8f)
        assertEquals(70, effect.particleCount)

        val halved = Rectangle(0, 0, 1600, 600)
        val dirty = newCollector()
        assertTrue(effect.update(dt, halved, dirty))
        assertEquals(48, effect.particleCount)
        assertTrue(effect.allInside(halved))
        assertTrue("vanished drops inside the new bounds must be reported for clearing", dirty.size > 0)
        assertFalse(dirty.overflowed)

        val tiny = Rectangle(0, 0, 400, 300)
        effect.update(dt, tiny, newCollector())
        assertEquals(20, effect.particleCount)
        assertTrue(effect.allInside(tiny))
    }

    @Test
    fun `resizing the frame during a failure keeps the screen covered`() {
        val effect = started()
        simulate(effect, Rectangle(0, 0, 600, 400), 6f)
        effect.onEvent(AtmosphereEvent.FAILURE)
        var bounds = Rectangle(0, 0, 600, 400)
        for (next in listOf(Rectangle(0, 0, 600, 400), Rectangle(0, 0, 500, 300), Rectangle(0, 0, 600, 400), Rectangle(0, 0, 600, 400))) {
            val full = Rectangle(0, 0, 600, 400)
            val before = paint(effect, full, full)
            val dirty = newCollector()
            effect.update(dt, next, dirty)
            val after = paint(effect, full, full)
            // The frame outside the new bounds is not repainted by the host, so only compare inside both.
            val common = bounds.intersection(next)
            assertCovered(before, after, mergedRects(dirty, full), common)
            bounds = next
        }
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
        val effect = RainEffect()
        val bounds = Rectangle(0, 0, 800, 600)
        val dirty = newCollector()
        assertFalse(effect.update(dt, bounds, dirty))
        assertEquals(0, dirty.size)

        effect.start(EffectContext.forTesting())
        simulate(effect, bounds, 3f)
        effect.onEvent(AtmosphereEvent.SUCCESS)
        effect.update(RainEffect.EASE_ATTACK_SECONDS, bounds, newCollector())
        assertEquals(1f, effect.glow, 0.001f)

        effect.stop()
        assertEquals(0, effect.particleCount)
        assertEquals(0, effect.splashCount)
        assertEquals(0f, effect.glow, 0f)
        assertEquals(0f, effect.failureGlow, 0f)
        assertFalse(effect.update(dt, bounds, dirty))
        assertEquals(0, dirty.size)

        effect.start(EffectContext.forTesting())
        assertTrue(effect.update(dt, bounds, newCollector()))
        assertEquals(24, effect.particleCount)
    }

    @Test
    fun `update is allocation free`() {
        val effect = started()
        val bounds = Rectangle(0, 0, 1512, 909)
        val dirty = newCollector()
        // Warm-up: class loading, JIT and the initial fill.
        repeat(300) { dirty.clear(); effect.update(dt, bounds, dirty) }
        effect.onPointerChanged(700f, 400f, true)
        effect.onEvent(AtmosphereEvent.FAILURE)
        repeat(100) { dirty.clear(); effect.update(dt, bounds, dirty) }

        val allocated = allocatedBytes {
            repeat(400) {
                dirty.clear()
                effect.update(dt, bounds, dirty)
            }
            effect.onEvent(AtmosphereEvent.SUCCESS)
            repeat(100) {
                dirty.clear()
                effect.update(dt, bounds, dirty)
            }
        }
        assertTrue("update allocated $allocated bytes over 500 ticks", allocated < ALLOCATION_BUDGET_BYTES)
    }

    @Test
    fun `dirty regions cover every pixel changed on screen over many ticks`() {
        val effect = started()
        val bounds = Rectangle(0, 0, 400, 300)
        simulate(effect, bounds, 3f)
        var visibleChanges = 0
        for (step in listOf(1f / 60f, dt, 0.1f)) {
            repeat(60) {
                val before = paint(effect, bounds, bounds)
                val dirty = newCollector()
                effect.update(step, bounds, dirty)
                val after = paint(effect, bounds, bounds)
                if (assertCovered(before, after, mergedRects(dirty, bounds))) visibleChanges++
            }
        }
        assertTrue("the screen must keep changing", visibleChanges > 100)
    }

    @Test
    fun `dirty regions cover every pixel changed by successive and interrupted outcomes`() {
        val bounds = Rectangle(0, 0, 400, 300)
        val effect = started()
        simulate(effect, bounds, 3f)
        for (event in listOf(AtmosphereEvent.SUCCESS, AtmosphereEvent.FAILURE, AtmosphereEvent.FAILURE, AtmosphereEvent.SUCCESS)) {
            effect.onEvent(event)
            repeat(12) {
                val before = paint(effect, bounds, bounds)
                val dirty = newCollector()
                effect.update(dt, bounds, dirty)
                assertCovered(before, paint(effect, bounds, bounds), mergedRects(dirty, bounds))
            }
        }
    }

    @Test
    fun `drops fleeing the pointer leave no uncovered stale pixels`() {
        val bounds = Rectangle(0, 0, 400, 300)
        val effect = started()
        simulate(effect, bounds, 3f)
        repeat(90) { tick ->
            effect.onPointerChanged(50f + tick * 3f, 20f + tick * 2f, true)
            val before = paint(effect, bounds, bounds)
            val dirty = newCollector()
            effect.update(dt, bounds, dirty)
            assertCovered(before, paint(effect, bounds, bounds), mergedRects(dirty, bounds))
        }
    }

    @Test
    fun `paint uses only the cached color table and never rebuilds it`() {
        val bounds = Rectangle(0, 0, 60, 60)
        val effect = started()
        effect.update(dt, bounds, newCollector())
        assertEquals(256, RainEffect.ALPHA_STEPS)
        assertEquals(256, effect.colorTableSizeForTest())
        assertEquals(1, effect.colorTableRebuildsForTest())

        for (i in 0 until effect.particleCount) effect.placeParticleForTest(i, -300f, -300f, 0f)
        floats(effect, "width")[0] = 6f
        effect.placeParticleForTest(0, 30f, 50f, 0.8f)
        val image = paint(effect, bounds, bounds)
        repeat(20) { paint(effect, bounds, bounds) }
        assertEquals("paint must never rebuild the color table", 1, effect.colorTableRebuildsForTest())

        // Just above the head the streak is still several pixels wide: its pixels carry the cached colour.
        val head = Color(image.getRGB(30, 45), true)
        val expected = RainEffect.DARK_DROP_COLOR
        assertEquals(expected.red.toFloat(), head.red.toFloat(), 2f)
        assertEquals(expected.green.toFloat(), head.green.toFloat(), 2f)
        assertEquals(expected.blue.toFloat(), head.blue.toFloat(), 2f)
        assertEquals(204f, head.alpha.toFloat(), 3f)
        // The tail runs upwards from the head.
        assertTrue("the streak should reach above its head", countOpaque(image, Rectangle(0, 10, 60, 30)) > 0)
        assertEquals("nothing is painted below the head", 0, countOpaque(image, Rectangle(0, 58, 60, 2)))

        simulate(effect, bounds, 1f)
        assertEquals("update must not rebuild the color table either", 1, effect.colorTableRebuildsForTest())
    }

    @Test
    fun `offscreen paint writes only inside the clip and is visible`() {
        val effect = started()
        val bounds = Rectangle(0, 0, 800, 600)
        simulate(effect, bounds, 3f)

        val full = paint(effect, bounds, bounds)
        assertTrue("drops should be visible after fading in", countOpaque(full, bounds) > 0)

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
    fun `paint uses theme drop colors and rebuilds them on palette change`() {
        val effect = started(palette = AtmospherePalette.DARK)
        val bounds = Rectangle(0, 0, 800, 600)
        simulate(effect, bounds, 3f)
        val dark = paint(effect, bounds, bounds)
        assertTrue(colorDistance(dark, RainEffect.DARK_DROP_COLOR) < 8.0)
        assertEquals(1, effect.colorTableRebuildsForTest())

        effect.onPaletteChanged(AtmospherePalette.LIGHT)
        assertEquals(2, effect.colorTableRebuildsForTest())
        assertEquals(256, effect.colorTableSizeForTest())
        val light = paint(effect, bounds, bounds)
        assertTrue(colorDistance(light, RainEffect.LIGHT_DROP_COLOR) < 8.0)
        assertTrue(colorDistance(light, RainEffect.DARK_DROP_COLOR) > 100.0)

        // The light palette also reduces the opacity (× opacityScale) from the next update on.
        val stationary = stationaryEffect(bounds)
        val darkAlpha = sumAlpha(stationary)
        stationary.onPaletteChanged(AtmospherePalette.LIGHT)
        stationary.update(dt, bounds, newCollector())
        assertEquals(darkAlpha * AtmospherePalette.LIGHT.opacityScale, sumAlpha(stationary), darkAlpha * 0.001f)
    }

    @Test
    fun `intensity scales the opacity of existing drops`() {
        val bounds = Rectangle(0, 0, 600, 400)
        val effect = stationaryEffect(bounds)
        val full = sumAlpha(effect)
        effect.setIntensity(0.5f)
        effect.update(dt, bounds, newCollector())
        assertEquals("population is unchanged at the minimum", 20, effect.particleCount)
        assertEquals(full * 0.5f, sumAlpha(effect), full * 0.001f)
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
        assertEquals(normal.meanVelocityY() * 2f, fast.meanVelocityY(), 0.05f)

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
        assertEquals(RainEffect.IDLE_SPEED, effect.speedMultiplier, 0.001f)
        assertTrue("idle rain falls slower", effect.meanVelocityY() < cruising * 0.7f)

        effect.onEvent(AtmosphereEvent.USER_ACTIVE)
        simulate(effect, bounds, 4f)
        assertEquals(RainEffect.NORMAL_SPEED, effect.speedMultiplier, 0.001f)

        effect.onEvent(AtmosphereEvent.ACTIVITY_STARTED)
        simulate(effect, bounds, 3f)
        assertEquals(RainEffect.ACTIVITY_SPEED, effect.speedMultiplier, 0.001f)

        effect.onEvent(AtmosphereEvent.ACTIVITY_FINISHED)
        simulate(effect, bounds, 4f)
        assertEquals(RainEffect.NORMAL_SPEED, effect.speedMultiplier, 0.001f)

        // The speed scale multiplies the idle and busy levels as well.
        effect.setSpeedScale(2f)
        effect.onEvent(AtmosphereEvent.USER_IDLE)
        simulate(effect, bounds, 3f)
        assertEquals(RainEffect.IDLE_SPEED * 2f, effect.speedMultiplier, 0.001f)
    }

    @Test
    fun `success eases the rain without touching the colors, then recovers`() {
        val bounds = Rectangle(0, 0, 800, 600)
        val effect = started()
        val reference = started()
        effect.update(dt, bounds, newCollector())
        reference.update(dt, bounds, newCollector())
        assertEquals(0f, effect.glow, 0f)
        val rebuilds = effect.colorTableRebuildsForTest()

        effect.onEvent(AtmosphereEvent.SUCCESS)
        assertTrue("the reaction eases in rather than jumping", effect.glow < 1f)
        effect.update(RainEffect.EASE_ATTACK_SECONDS, bounds, newCollector())
        reference.update(RainEffect.EASE_ATTACK_SECONDS, bounds, newCollector())
        assertEquals(1f, effect.glow, 0.001f)
        assertEquals(
            "the fall slows down by EASE_SLOWDOWN at the peak",
            reference.meanVelocityY() * (1f - RainEffect.EASE_SLOWDOWN),
            effect.meanVelocityY(),
            0.5f,
        )
        assertEquals("the reaction is motion only", rebuilds, effect.colorTableRebuildsForTest())

        simulate(effect, bounds, RainEffect.EASE_DECAY_SECONDS + 0.5f)
        assertEquals(0f, effect.glow, 0.001f)
        assertTrue(effect.allInside(bounds))

        // A failure cuts a running reaction short smoothly instead of snapping it to zero.
        effect.onEvent(AtmosphereEvent.SUCCESS)
        effect.update(RainEffect.EASE_ATTACK_SECONDS, bounds, newCollector())
        assertEquals(1f, effect.glow, 0.001f)
        effect.onEvent(AtmosphereEvent.FAILURE)
        effect.update(0.2f, bounds, newCollector())
        assertTrue("eased out, not snapped: glow=${effect.glow}", effect.glow > 0f && effect.glow < 1f)
        assertTrue(effect.failureGlow > 0f)
        effect.update(0.3f, bounds, newCollector())
        assertEquals(0f, effect.glow, 0.001f)
    }

    @Test
    fun `failure gusts sideways in one consistent direction and flashes the brightness briefly`() {
        val bounds = Rectangle(0, 0, 800, 600)
        val effect = stationaryEffect(bounds)
        val alphaBefore = sumAlpha(effect)
        assertTrue(alphaBefore > 0f)
        assertEquals(0f, effect.failureGlow, 0f)
        val rebuilds = effect.colorTableRebuildsForTest()

        effect.onEvent(AtmosphereEvent.FAILURE)
        effect.update(RainEffect.GUST_ATTACK_SECONDS, bounds, newCollector())
        assertEquals(1f, effect.failureGlow, 0.001f)
        val gust = effect.meanVelocityX()
        assertTrue("the gust should dominate the breeze, got $gust px/s", abs(gust) > RainEffect.GUST_SPEED * 0.3f)
        assertEquals(
            "drops flash by FLASH_BOOST at the peak of the gust",
            alphaBefore * (1f + RainEffect.FLASH_BOOST),
            sumAlpha(effect),
            alphaBefore * 0.001f,
        )

        // A second failure while the gust is still active keeps blowing the same way.
        effect.onEvent(AtmosphereEvent.FAILURE)
        effect.update(RainEffect.GUST_ATTACK_SECONDS, bounds, newCollector())
        assertEquals(1f, effect.failureGlow, 0.001f)
        val again = effect.meanVelocityX()
        assertTrue("the gust direction must not flip: $gust then $again", gust * again > 0f && abs(again) > RainEffect.GUST_SPEED * 0.3f)
        assertEquals("the gust is motion and brightness only", rebuilds, effect.colorTableRebuildsForTest())

        simulate(effect, bounds, RainEffect.GUST_DECAY_SECONDS + 1f)
        assertEquals(0f, effect.failureGlow, 0f)
        assertEquals("opacity returns to normal", alphaBefore, sumAlpha(effect), alphaBefore * 0.001f)
        assertTrue(effect.allInside(bounds))
    }

    @Test
    fun `a failure flash only changes opacity never the hue of painted pixels`() {
        for (palette in listOf(AtmospherePalette.DARK, AtmospherePalette.LIGHT)) {
            val effect = started(palette = palette)
            val bounds = Rectangle(0, 0, 800, 600)
            simulate(effect, bounds, 3f)
            val baseline = meanColor(paint(effect, bounds, bounds))
            effect.onEvent(AtmosphereEvent.FAILURE)
            effect.update(RainEffect.GUST_ATTACK_SECONDS, bounds, newCollector())
            val flashing = meanColor(paint(effect, bounds, bounds))
            for (channel in 0..2) assertEquals(baseline[channel], flashing[channel], 8.0)
        }
    }

    @Test
    fun `cursor avoidance pushes a nearby drop away and settles after the pointer leaves`() {
        val bounds = Rectangle(0, 0, 400, 400)
        val effect = started()
        effect.update(dt, bounds, newCollector())
        floats(effect, "fallSpeed").fill(0f)
        effect.setSpeedScale(0.3f)
        effect.placeParticleForTest(0, 100f, 110f, 0.8f)
        val xs = floats(effect, "x")
        val ys = floats(effect, "y")

        effect.onPointerChanged(100f, 100f, true)
        simulate(effect, bounds, 0.5f)
        assertTrue("a drop just below the cursor should be pushed further down, got y=${ys[0]}", ys[0] > 115f)
        assertTrue("the push is radial, so x barely changes, got x=${xs[0]}", abs(xs[0] - 100f) < 15f)

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

    /** Effect with every drop parked at the centre of [bounds], fully faded in and not falling. */
    private fun stationaryEffect(bounds: Rectangle): RainEffect {
        val effect = started()
        effect.update(dt, bounds, newCollector())
        floats(effect, "fallSpeed").fill(0f)
        floats(effect, "fadeIn").fill(1f)
        for (i in 0 until effect.particleCount) {
            effect.placeParticleForTest(i, bounds.width / 2f, bounds.height / 2f, 0f)
        }
        effect.update(dt, bounds, newCollector())
        return effect
    }

    private fun floats(effect: RainEffect, name: String): FloatArray {
        val field = RainEffect::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(effect) as FloatArray
    }

    private fun sumAlpha(effect: RainEffect): Float {
        val alpha = floats(effect, "alpha")
        var sum = 0f
        for (i in 0 until effect.particleCount) sum += alpha[i]
        return sum
    }

    @Suppress("DEPRECATION")
    private fun allocatedBytes(block: () -> Unit): Long {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        block()
        return bean.getThreadAllocatedBytes(id) - before
    }

    private fun mergedRects(dirty: DirtyRegionCollector, bounds: Rectangle): List<Rectangle> {
        val merged = ArrayList<Rectangle>()
        dirty.merge(bounds) { merged.add(Rectangle(it)) }
        return merged
    }

    /**
     * Asserts every pixel that differs between [before] and [after] inside [area] lies in a [merged] rect;
     * returns whether any did.
     */
    private fun assertCovered(
        before: BufferedImage,
        after: BufferedImage,
        merged: List<Rectangle>,
        area: Rectangle = Rectangle(0, 0, before.width, before.height),
    ): Boolean {
        var changed = false
        for (yy in area.y until area.y + area.height) {
            for (xx in area.x until area.x + area.width) {
                if (before.getRGB(xx, yy) != after.getRGB(xx, yy)) {
                    changed = true
                    assertTrue("changed pixel ($xx,$yy) is not covered by a dirty rect", merged.any { it.contains(xx, yy) })
                }
            }
        }
        return changed
    }

    private fun paint(effect: RainEffect, bounds: Rectangle, clip: Rectangle): BufferedImage {
        val image = BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            // Same hints AtmospherePainter applies (GraphicsUtil.setupAAPainting).
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

    /** Alpha-weighted mean (R, G, B) of the visible pixels. */
    private fun meanColor(image: BufferedImage): DoubleArray {
        val sums = DoubleArray(3)
        var alpha = 0.0
        for (yy in 0 until image.height) for (xx in 0 until image.width) {
            val pixel = image.getRGB(xx, yy)
            val a = (pixel ushr 24).toDouble()
            if (a == 0.0) continue
            sums[0] += (pixel shr 16 and 255) * a
            sums[1] += (pixel shr 8 and 255) * a
            sums[2] += (pixel and 255) * a
            alpha += a
        }
        assertTrue("image must contain visible drops", alpha > 0.0)
        return DoubleArray(3) { sums[it] / alpha }
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

    private companion object {
        /** Generous slack for JVM bookkeeping; a per-tick allocation would add up to many times this over 500 ticks. */
        const val ALLOCATION_BUDGET_BYTES = 8_192L
    }
}
