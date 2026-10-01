package info.chrzanowski.ideatmosphere.effects.starfield

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

class StarfieldEffectTest {

    private val dt = 1f / 24f

    private fun started(seed: Long = 42L, palette: AtmospherePalette = AtmospherePalette.DARK): StarfieldEffect =
        StarfieldEffect().also { it.start(EffectContext.forTesting(palette = palette, seed = seed)) }

    private fun newCollector() = DirtyRegionCollector(StarfieldEffect.MAX_PARTICLES * 2)

    /** Runs [seconds] of simulation; returns how many updates reported a change. */
    private fun simulate(effect: StarfieldEffect, bounds: Rectangle, seconds: Float, dirty: DirtyRegionCollector = newCollector()): Int {
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
    fun `effect id is starfield and the effect is registered`() {
        assertEquals("starfield", StarfieldEffect.ID)
        assertEquals(StarfieldEffect.ID, StarfieldEffect().id)
        assertTrue(StarfieldEffect.ID in AtmosphereEffects.ids)
        assertTrue(AtmosphereEffects.create(StarfieldEffect.ID) is StarfieldEffect)
    }

    @Test
    fun `particle count is clamped to 30-90 and scales with area and intensity`() {
        assertEquals(34, StarfieldEffect.particleCountFor(800, 600))
        assertEquals(30, StarfieldEffect.particleCountFor(400, 300))
        assertEquals(90, StarfieldEffect.particleCountFor(1512, 949))
        assertEquals(90, StarfieldEffect.particleCountFor(3840, 2160))
        assertEquals(68, StarfieldEffect.particleCountFor(800, 600, 2f))
        assertEquals(30, StarfieldEffect.particleCountFor(800, 600, 0.3f))

        val small = started()
        small.update(dt, Rectangle(0, 0, 800, 600), newCollector())
        assertEquals(34, small.particleCount)

        val large = started()
        large.update(dt, Rectangle(0, 0, 3840, 2160), newCollector())
        assertEquals(90, large.particleCount)
    }

    @Test
    fun `intensity changes the population on the next update and is clamped`() {
        val effect = started()
        val bounds = Rectangle(0, 0, 800, 600)
        effect.update(dt, bounds, newCollector())
        assertEquals(34, effect.particleCount)

        effect.setIntensity(2f)
        assertEquals("population changes on the next tick, not immediately", 34, effect.particleCount)
        effect.update(dt, bounds, newCollector())
        assertEquals(68, effect.particleCount)

        effect.setIntensity(5f)
        effect.update(dt, bounds, newCollector())
        assertEquals("intensity is clamped to 2", 68, effect.particleCount)

        effect.setIntensity(0.1f)
        val dirty = newCollector()
        effect.update(dt, bounds, dirty)
        assertEquals("intensity is clamped to 0.3", 30, effect.particleCount)
        assertTrue("removed stars must be reported for clearing", dirty.size > 0)

        effect.setIntensity(Float.NaN)
        effect.update(dt, bounds, newCollector())
        assertEquals("non-finite intensity falls back to 1", 34, effect.particleCount)
    }

    @Test
    fun `start scatters stars over the whole area and layers them by depth`() {
        val effect = started()
        val bounds = Rectangle(0, 40, 1512, 909)
        effect.update(0.001f, bounds, newCollector())
        assertEquals(90, effect.particleCount)
        assertTrue(effect.allInside(bounds))

        val ys = floats(effect, "y")
        val depth = floats(effect, "depth")
        val radius = floats(effect, "radius")
        val baseAlpha = floats(effect, "baseAlpha")
        var upper = 0
        var lower = 0
        for (i in 0 until effect.particleCount) {
            if (ys[i] < 40f + 909f * 0.25f) upper++
            if (ys[i] > 40f + 909f * 0.75f) lower++
            // Nearer (depth → 1) means larger and brighter.
            val d = depth[i]
            assertTrue("depth[$i]=$d", d in 0f..1f)
            assertEquals(StarfieldEffect.MIN_RADIUS + (StarfieldEffect.MAX_RADIUS - StarfieldEffect.MIN_RADIUS) * d, radius[i], 1e-4f)
            assertEquals(
                StarfieldEffect.MIN_BASE_ALPHA + (StarfieldEffect.MAX_BASE_ALPHA - StarfieldEffect.MIN_BASE_ALPHA) * d,
                baseAlpha[i],
                1e-4f,
            )
        }
        assertTrue("stars should reach the upper quarter, got $upper", upper > 0)
        assertTrue("stars should reach the lower quarter, got $lower", lower > 0)
    }

    @Test
    fun `stars fade in at their place and twinkle afterwards`() {
        val effect = started()
        val bounds = Rectangle(0, 0, 800, 600)
        effect.update(dt, bounds, newCollector())
        val alpha = floats(effect, "alpha")
        assertTrue("a new star starts almost invisible", alpha.take(effect.particleCount).all { it < 0.1f })

        simulate(effect, bounds, StarfieldEffect.FADE_IN_SECONDS + 0.5f)
        // Track one star for a while: its opacity must breathe by a clear margin while the star hardly moves.
        var low = Float.MAX_VALUE
        var high = 0f
        val startX = floats(effect, "x")[0]
        simulate(effect, bounds, 0.01f)
        var elapsed = 0f
        while (elapsed < 8f) {
            effect.update(dt, bounds, newCollector())
            low = minOf(low, alpha[0])
            high = maxOf(high, alpha[0])
            elapsed += dt
        }
        assertTrue("star 0 should twinkle, alpha stayed within $low..$high", high > low * 1.3f)
        assertTrue("star 0 drifts only slowly", abs(floats(effect, "x")[0] - startX) < 60f)
    }

    @Test
    fun `stars drift slowly to the left`() {
        val effect = started()
        val bounds = Rectangle(0, 0, 800, 600)
        simulate(effect, bounds, 1f)
        val vx = effect.meanVelocityX()
        assertTrue("expected a very slow leftward drift, got $vx px/s", vx < -0.5f && vx > -StarfieldEffect.MAX_DRIFT_SPEED)
        assertEquals(0f, effect.meanVelocityY(), 1e-4f)
    }

    @Test
    fun `particles stay inside bounds after 120 seconds`() {
        val effect = started()
        val bounds = Rectangle(0, 40, 1512, 909)
        val changed = simulate(effect, bounds, 120f)
        assertTrue("simulation should report changes", changed > 0)
        assertTrue(effect.allInside(bounds))
        assertEquals(StarfieldEffect.particleCountFor(1512, 909), effect.particleCount)
    }

    @Test
    fun `stars that drift out reappear inside the bounds and fade in`() {
        val effect = started()
        val bounds = Rectangle(0, 0, 800, 600)
        simulate(effect, bounds, 3f)
        val xs = floats(effect, "x")
        val alpha = floats(effect, "alpha")
        effect.placeParticleForTest(0, -5f, 300f, 0.8f)

        val before = paint(effect, bounds, bounds)
        val dirty = newCollector()
        effect.update(1f, bounds, dirty)
        assertTrue("star 0 left through the left edge and must be placed inside again", xs[0] >= 0f && xs[0] <= 800f)
        assertCovered(before, paint(effect, bounds, bounds), mergedRects(dirty, bounds))
        assertEquals("a respawned star starts invisible", 0f, alpha[0], 0.05f)
    }

    @Test
    fun `shrinking bounds respawns particles inside the new bounds within one tick`() {
        val effect = started()
        simulate(effect, Rectangle(0, 0, 1600, 1200), 2f)
        assertEquals(90, effect.particleCount)

        val halved = Rectangle(0, 0, 1600, 600)
        val dirty = newCollector()
        assertTrue(effect.update(dt, halved, dirty))
        assertEquals(68, effect.particleCount)
        assertTrue(effect.allInside(halved))
        assertTrue("vanished stars inside the new bounds must be reported for clearing", dirty.size > 0)

        val tiny = Rectangle(0, 0, 400, 300)
        effect.update(dt, tiny, newCollector())
        assertEquals(30, effect.particleCount)
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
        val effect = StarfieldEffect()
        val bounds = Rectangle(0, 0, 800, 600)
        val dirty = newCollector()
        assertFalse(effect.update(dt, bounds, dirty))
        assertEquals(0, dirty.size)

        effect.start(EffectContext.forTesting())
        simulate(effect, bounds, 1f)
        effect.onEvent(AtmosphereEvent.SUCCESS)
        effect.update(StarfieldEffect.LIFT_ATTACK_SECONDS, bounds, newCollector())
        assertEquals(1f, effect.glow, 0.001f)
        assertTrue(effect.shootingStarActive)

        effect.stop()
        assertEquals(0, effect.particleCount)
        assertFalse(effect.shootingStarActive)
        assertEquals(0f, effect.glow, 0f)
        assertEquals(0f, effect.failureGlow, 0f)
        assertFalse(effect.update(dt, bounds, dirty))
        assertEquals(0, dirty.size)

        effect.start(EffectContext.forTesting())
        assertTrue(effect.update(dt, bounds, newCollector()))
        assertEquals(34, effect.particleCount)
        assertEquals("a restarted effect forgets earlier launches", 0, effect.shootingStarsLaunched)
    }

    @Test
    fun `update is allocation free`() {
        val effect = started()
        val bounds = Rectangle(0, 0, 1512, 909)
        val dirty = newCollector()
        // Warm-up: class loading, JIT, the initial fill and a first shooting star.
        repeat(400) { dirty.clear(); effect.update(dt, bounds, dirty) }
        effect.onPointerChanged(700f, 400f, true)
        effect.onEvent(AtmosphereEvent.FAILURE)
        repeat(100) { dirty.clear(); effect.update(dt, bounds, dirty) }

        val allocated = allocatedBytes {
            repeat(400) {
                dirty.clear()
                effect.update(dt, bounds, dirty)
            }
            // Includes a shooting star in flight and a success reaction.
            effect.onEvent(AtmosphereEvent.SUCCESS)
            repeat(100) {
                dirty.clear()
                effect.update(dt, bounds, dirty)
            }
        }
        assertTrue("update allocated $allocated bytes over 500 ticks", allocated < ALLOCATION_BUDGET_BYTES)
    }

    @Test
    fun `regions per tick stay within the star count plus the shooting star`() {
        val effect = started()
        val bounds = Rectangle(0, 0, 1512, 909)
        val dirty = newCollector()
        effect.onEvent(AtmosphereEvent.SUCCESS)
        repeat(240) {
            dirty.clear()
            effect.update(dt, bounds, dirty)
            assertTrue("got ${dirty.size} regions", dirty.size <= StarfieldEffect.MAX_PARTICLES + 1)
            assertFalse(dirty.overflowed)
        }
    }

    @Test
    fun `dirty regions cover every pixel changed on screen over many ticks`() {
        val effect = started()
        val bounds = Rectangle(0, 0, 400, 300)
        simulate(effect, bounds, 4f)
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
        assertTrue("the screen must keep changing", visibleChanges > 50)
    }

    @Test
    fun `dirty regions cover every pixel changed by successive and interrupted outcomes`() {
        val bounds = Rectangle(0, 0, 400, 300)
        val effect = started()
        simulate(effect, bounds, 4f)
        for (event in listOf(AtmosphereEvent.SUCCESS, AtmosphereEvent.FAILURE, AtmosphereEvent.FAILURE, AtmosphereEvent.SUCCESS)) {
            effect.onEvent(event)
            repeat(24) {
                val before = paint(effect, bounds, bounds)
                val dirty = newCollector()
                effect.update(dt, bounds, dirty)
                assertCovered(before, paint(effect, bounds, bounds), mergedRects(dirty, bounds))
            }
        }
    }

    @Test
    fun `stars fleeing the pointer leave no uncovered stale pixels`() {
        val bounds = Rectangle(0, 0, 400, 300)
        val effect = started()
        simulate(effect, bounds, 4f)
        repeat(90) { tick ->
            effect.onPointerChanged(50f + tick * 3f, 20f + tick * 2f, true)
            val before = paint(effect, bounds, bounds)
            val dirty = newCollector()
            effect.update(dt, bounds, dirty)
            assertCovered(before, paint(effect, bounds, bounds), mergedRects(dirty, bounds))
        }
    }

    @Test
    fun `a shooting star appears on its own, crosses the sky and is cleared when it is over`() {
        val bounds = Rectangle(0, 0, 800, 600)
        val effect = started()
        hideStars(effect, bounds)
        assertEquals(0, effect.shootingStarsLaunched)

        // The first one shows up within the initial wait.
        var elapsed = 0f
        while (!effect.shootingStarActive && elapsed < StarfieldEffect.INITIAL_SHOOTING_MAX_SECONDS + 1f) {
            effect.update(dt, bounds, newCollector())
            elapsed += dt
        }
        assertTrue("no shooting star within ${StarfieldEffect.INITIAL_SHOOTING_MAX_SECONDS} s", effect.shootingStarActive)
        assertEquals(1, effect.shootingStarsLaunched)
        assertTrue(elapsed >= StarfieldEffect.INITIAL_SHOOTING_MIN_SECONDS - 0.5f)

        // While it flies it is visible and every change is covered; it stays within a short flight.
        var visibleTicks = 0
        var ticks = 0
        while (effect.shootingStarActive && ticks < 200) {
            val before = paint(effect, bounds, bounds)
            val dirty = newCollector()
            effect.update(dt, bounds, dirty)
            val after = paint(effect, bounds, bounds)
            assertCovered(before, after, mergedRects(dirty, bounds))
            if (countOpaque(after, bounds) > 0) visibleTicks++
            ticks++
        }
        assertFalse("the flight must end", effect.shootingStarActive)
        assertTrue("the flight lasts ~1 s, took $ticks ticks", ticks * dt <= StarfieldEffect.MAX_SHOOTING_SECONDS + 0.2f)
        assertTrue("the shooting star must be visible while it flies, saw $visibleTicks ticks", visibleTicks >= 5)
        assertEquals("nothing is left behind", 0, countOpaque(paint(effect, bounds, bounds), bounds))

        // It is rare: the next one waits at least the minimum interval.
        simulate(effect, bounds, StarfieldEffect.MIN_SHOOTING_INTERVAL_SECONDS - 2f)
        assertEquals(1, effect.shootingStarsLaunched)
        var waited = 0f
        while (effect.shootingStarsLaunched == 1 && waited < StarfieldEffect.MAX_SHOOTING_INTERVAL_SECONDS) {
            effect.update(dt, bounds, newCollector())
            waited += dt
        }
        assertEquals("another one follows eventually", 2, effect.shootingStarsLaunched)
    }

    @Test
    fun `a higher intensity shortens the wait between shooting stars`() {
        val bounds = Rectangle(0, 0, 800, 600)
        val effect = started()
        effect.setIntensity(2f)
        effect.update(dt, bounds, newCollector())
        floats(effect, "baseAlpha").fill(0f)
        // Let the first one come and go, then measure how soon the next one launches.
        var guard = 0f
        while (effect.shootingStarsLaunched == 0 && guard < 20f) { effect.update(dt, bounds, newCollector()); guard += dt }
        while (effect.shootingStarActive && guard < 40f) { effect.update(dt, bounds, newCollector()); guard += dt }
        val launched = effect.shootingStarsLaunched
        var waited = 0f
        while (effect.shootingStarsLaunched == launched && waited < 60f) { effect.update(dt, bounds, newCollector()); waited += dt }
        assertTrue(
            "intensity 2 halves the wait, took $waited s",
            waited <= StarfieldEffect.MAX_SHOOTING_INTERVAL_SECONDS / 2f + 0.2f && waited >= StarfieldEffect.MIN_SHOOTING_INTERVAL_SECONDS / 2f - 0.2f,
        )
    }

    @Test
    fun `success launches one shooting star right away unless one is already flying`() {
        val bounds = Rectangle(0, 0, 800, 600)
        val effect = started()
        effect.update(dt, bounds, newCollector())
        assertFalse(effect.shootingStarActive)

        effect.onEvent(AtmosphereEvent.SUCCESS)
        effect.onEvent(AtmosphereEvent.SUCCESS)
        effect.update(dt, bounds, newCollector())
        assertTrue(effect.shootingStarActive)
        assertEquals("back-to-back successes launch a single star", 1, effect.shootingStarsLaunched)

        // A success during the flight does not queue a second one behind it.
        effect.onEvent(AtmosphereEvent.SUCCESS)
        simulate(effect, bounds, StarfieldEffect.MAX_SHOOTING_SECONDS + 0.5f)
        assertFalse(effect.shootingStarActive)
        simulate(effect, bounds, 1f)
        assertEquals(1, effect.shootingStarsLaunched)
    }

    @Test
    fun `paint uses only the cached color table and never rebuilds it`() {
        val bounds = Rectangle(0, 0, 40, 40)
        val effect = started()
        effect.update(dt, bounds, newCollector())
        assertEquals(256, StarfieldEffect.ALPHA_STEPS)
        assertEquals(256, effect.colorTableSizeForTest())
        assertEquals(1, effect.colorTableRebuildsForTest())

        for (i in 0 until effect.particleCount) effect.placeParticleForTest(i, -100f, -100f, 0f)
        floats(effect, "radius")[0] = 4f
        effect.placeParticleForTest(0, 20.5f, 20.5f, 0.8f)
        val image = paint(effect, bounds, bounds)
        repeat(20) { paint(effect, bounds, bounds) }
        assertEquals("paint must never rebuild the color table", 1, effect.colorTableRebuildsForTest())
        assertEquals(256, effect.colorTableSizeForTest())

        // The fully covered centre pixel carries the cached table entry: theme RGB with the star's 8-bit alpha.
        val centre = Color(image.getRGB(20, 20), true)
        val expected = StarfieldEffect.DARK_STAR_COLOR
        assertEquals(expected.red.toFloat(), centre.red.toFloat(), 2f)
        assertEquals(expected.green.toFloat(), centre.green.toFloat(), 2f)
        assertEquals(expected.blue.toFloat(), centre.blue.toFloat(), 2f)
        assertEquals(204f, centre.alpha.toFloat(), 3f)

        simulate(effect, bounds, 1f)
        assertEquals("update must not rebuild the color table either", 1, effect.colorTableRebuildsForTest())
    }

    @Test
    fun `stars are rendered at sub-pixel positions`() {
        val bounds = Rectangle(0, 0, 40, 40)
        val whole = columnCoverage(paintSingle(10.0f), bounds)
        val half = columnCoverage(paintSingle(10.5f), bounds)
        assertTrue("nothing painted", whole.sum() > 0)
        assertFalse("x=10.0 and x=10.5 must not produce identical coverage", whole.contentEquals(half))
    }

    @Test
    fun `offscreen paint writes only inside the clip and is visible`() {
        val effect = started()
        val bounds = Rectangle(0, 0, 800, 600)
        simulate(effect, bounds, 4f)

        val full = paint(effect, bounds, bounds)
        assertTrue("stars should be visible after fading in", countOpaque(full, bounds) > 0)

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
    fun `paint uses the dark and the soft light variant and rebuilds colors on palette change`() {
        val effect = started(palette = AtmospherePalette.DARK)
        val bounds = Rectangle(0, 0, 800, 600)
        simulate(effect, bounds, 4f)
        val dark = paint(effect, bounds, bounds)
        assertTrue(colorDistance(dark, StarfieldEffect.DARK_STAR_COLOR) < 8.0)
        assertEquals(1, effect.colorTableRebuildsForTest())

        effect.onPaletteChanged(AtmospherePalette.LIGHT)
        assertEquals(2, effect.colorTableRebuildsForTest())
        assertEquals(256, effect.colorTableSizeForTest())
        val light = paint(effect, bounds, bounds)
        assertTrue(colorDistance(light, StarfieldEffect.LIGHT_STAR_COLOR) < 8.0)
        assertTrue(colorDistance(light, StarfieldEffect.DARK_STAR_COLOR) > 100.0)

        // The light variant is softer: opacity × opacityScale × LIGHT_SOFTENING from the next update on.
        val stationary = stationaryEffect(bounds)
        val darkAlpha = sumAlpha(stationary)
        stationary.onPaletteChanged(AtmospherePalette.LIGHT)
        stationary.update(dt, bounds, newCollector())
        assertEquals(
            darkAlpha * AtmospherePalette.LIGHT.opacityScale * StarfieldEffect.LIGHT_SOFTENING,
            sumAlpha(stationary),
            darkAlpha * 0.001f,
        )
    }

    @Test
    fun `intensity scales the opacity of existing stars`() {
        val bounds = Rectangle(0, 0, 400, 300)
        val effect = stationaryEffect(bounds)
        val full = sumAlpha(effect)
        effect.setIntensity(0.5f)
        effect.update(dt, bounds, newCollector())
        assertEquals("population is unchanged at the minimum", 30, effect.particleCount)
        assertEquals(full * 0.5f, sumAlpha(effect), full * 0.001f)
    }

    @Test
    fun `speed scale multiplies the drift and is clamped`() {
        val bounds = Rectangle(0, 0, 800, 600)
        val normal = started()
        normal.update(dt, bounds, newCollector())
        val fast = started()
        fast.setSpeedScale(2f)
        fast.update(dt, bounds, newCollector())
        assertEquals(2f, fast.speedMultiplier, 0f)
        assertTrue(normal.meanVelocityX() < 0f)
        assertEquals(normal.meanVelocityX() * 2f, fast.meanVelocityX(), 0.001f)

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
        val cruising = effect.meanVelocityX()

        effect.onEvent(AtmosphereEvent.USER_IDLE)
        assertEquals(1f, effect.speedMultiplier, 0f)
        effect.update(dt, bounds, newCollector())
        assertTrue("should interpolate, not jump", effect.speedMultiplier > 0.9f && effect.speedMultiplier < 1f)
        simulate(effect, bounds, 3f)
        assertEquals(StarfieldEffect.IDLE_SPEED, effect.speedMultiplier, 0.001f)
        assertTrue("idle stars drift slower", abs(effect.meanVelocityX()) < abs(cruising) * 0.7f)

        effect.onEvent(AtmosphereEvent.USER_ACTIVE)
        simulate(effect, bounds, 4f)
        assertEquals(StarfieldEffect.NORMAL_SPEED, effect.speedMultiplier, 0.001f)

        effect.onEvent(AtmosphereEvent.ACTIVITY_STARTED)
        simulate(effect, bounds, 3f)
        assertEquals(StarfieldEffect.ACTIVITY_SPEED, effect.speedMultiplier, 0.001f)

        effect.onEvent(AtmosphereEvent.ACTIVITY_FINISHED)
        simulate(effect, bounds, 4f)
        assertEquals(StarfieldEffect.NORMAL_SPEED, effect.speedMultiplier, 0.001f)

        // The speed scale multiplies the idle and busy levels as well.
        effect.setSpeedScale(2f)
        effect.onEvent(AtmosphereEvent.USER_IDLE)
        simulate(effect, bounds, 3f)
        assertEquals(StarfieldEffect.IDLE_SPEED * 2f, effect.speedMultiplier, 0.001f)
    }

    @Test
    fun `success lifts the stars briefly without touching the colors, then recovers`() {
        val effect = started()
        val bounds = Rectangle(0, 0, 800, 600)
        simulate(effect, bounds, 1f)
        assertEquals(0f, effect.glow, 0f)
        assertEquals(0f, effect.meanVelocityY(), 1e-4f)
        val rebuilds = effect.colorTableRebuildsForTest()

        effect.onEvent(AtmosphereEvent.SUCCESS)
        assertTrue("the lift eases in rather than jumping", effect.glow < 1f)
        effect.update(StarfieldEffect.LIFT_ATTACK_SECONDS, bounds, newCollector())
        assertEquals(1f, effect.glow, 0.001f)
        val lift = effect.meanVelocityY()
        assertTrue("stars should rise at the peak, got $lift px/s", lift < -StarfieldEffect.LIFT_SPEED * 0.5f)
        assertTrue("the lift stays bounded, got $lift px/s", lift > -StarfieldEffect.LIFT_SPEED - 0.01f)
        assertEquals("the lift is motion only", rebuilds, effect.colorTableRebuildsForTest())

        simulate(effect, bounds, StarfieldEffect.LIFT_DECAY_SECONDS + 0.5f)
        assertEquals(0f, effect.glow, 0.001f)
        assertEquals(0f, effect.meanVelocityY(), 1e-4f)
        assertTrue(effect.allInside(bounds))

        // A failure cuts a running lift short smoothly instead of snapping it to zero.
        effect.onEvent(AtmosphereEvent.SUCCESS)
        effect.update(StarfieldEffect.LIFT_ATTACK_SECONDS, bounds, newCollector())
        assertEquals(1f, effect.glow, 0.001f)
        effect.onEvent(AtmosphereEvent.FAILURE)
        effect.update(0.2f, bounds, newCollector())
        assertTrue("eased out, not snapped: glow=${effect.glow}", effect.glow > 0f && effect.glow < 1f)
        assertTrue(effect.failureGlow > 0f)
        effect.update(0.3f, bounds, newCollector())
        assertEquals(0f, effect.glow, 0.001f)
    }

    @Test
    fun `success speeds up the twinkle`() {
        val bounds = Rectangle(0, 0, 800, 600)
        val calm = stationaryEffect(bounds)
        val excited = stationaryEffect(bounds)
        floats(calm, "twinkleRate").fill(1f)
        floats(excited, "twinkleRate").fill(1f)
        val phaseCalm = floats(calm, "twinklePhase")
        val phaseExcited = floats(excited, "twinklePhase")
        val startCalm = phaseCalm[0]
        val startExcited = phaseExcited[0]

        excited.onEvent(AtmosphereEvent.SUCCESS)
        calm.update(StarfieldEffect.LIFT_ATTACK_SECONDS, bounds, newCollector())
        excited.update(StarfieldEffect.LIFT_ATTACK_SECONDS, bounds, newCollector())
        val advancedCalm = phaseCalm[0] - startCalm
        val advancedExcited = phaseExcited[0] - startExcited
        assertTrue("twinkle should speed up: $advancedCalm vs $advancedExcited", advancedExcited > advancedCalm * 2f)
    }

    @Test
    fun `failure shivers the whole sky sideways and dims the stars temporarily`() {
        val bounds = Rectangle(0, 0, 800, 600)
        val effect = stationaryEffect(bounds)
        val alphaBefore = sumAlpha(effect)
        assertTrue(alphaBefore > 0f)
        assertEquals(0f, effect.failureGlow, 0f)
        assertEquals(0f, effect.meanVelocityX(), 1e-4f)
        val rebuilds = effect.colorTableRebuildsForTest()

        effect.onEvent(AtmosphereEvent.FAILURE)
        effect.update(StarfieldEffect.SHIVER_ATTACK_SECONDS, bounds, newCollector())
        assertEquals(1f, effect.failureGlow, 0.001f)
        assertEquals(
            "stars dim by FAILURE_DIM at the peak of the tremor",
            alphaBefore * (1f - StarfieldEffect.FAILURE_DIM),
            sumAlpha(effect),
            alphaBefore * 0.001f,
        )

        // The sky swings both ways while it shivers.
        var left = 0f
        var right = 0f
        repeat(24) {
            effect.update(dt, bounds, newCollector())
            left = minOf(left, effect.meanVelocityX())
            right = maxOf(right, effect.meanVelocityX())
        }
        assertTrue("expected a swing to both sides, got $left..$right px/s", left < -StarfieldEffect.SHIVER_SPEED * 0.2f && right > StarfieldEffect.SHIVER_SPEED * 0.2f)
        assertEquals("the tremor is motion and dimming only", rebuilds, effect.colorTableRebuildsForTest())

        simulate(effect, bounds, StarfieldEffect.SHIVER_DECAY_SECONDS + 1f)
        assertEquals(0f, effect.failureGlow, 0f)
        assertEquals("opacity recovers fully", alphaBefore, sumAlpha(effect), alphaBefore * 0.001f)
        assertTrue(effect.allInside(bounds))
    }

    @Test
    fun `cursor avoidance pushes a nearby star away and settles after the pointer leaves`() {
        val bounds = Rectangle(0, 0, 400, 400)
        val effect = started()
        effect.update(dt, bounds, newCollector())
        floats(effect, "driftSpeed").fill(0f)
        effect.placeParticleForTest(0, 100f, 110f, 0.8f)
        val ys = floats(effect, "y")

        effect.onPointerChanged(100f, 100f, true)
        simulate(effect, bounds, 0.5f)
        assertTrue("a star just below the cursor should be pushed further down, got y=${ys[0]}", ys[0] > 115f)

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

    /** Effect with every star parked at the centre of [bounds], fully faded in, not drifting and not twinkling. */
    private fun stationaryEffect(bounds: Rectangle): StarfieldEffect {
        val effect = started()
        effect.update(dt, bounds, newCollector())
        floats(effect, "driftSpeed").fill(0f)
        floats(effect, "twinkleRate").fill(0f)
        floats(effect, "twinklePhase").fill(0f)
        floats(effect, "fadeIn").fill(1f)
        for (i in 0 until effect.particleCount) {
            effect.placeParticleForTest(i, bounds.width / 2f, bounds.height / 2f, 0f)
        }
        effect.update(dt, bounds, newCollector())
        return effect
    }

    /** Makes every star invisible (base opacity 0), so that only the shooting star can be seen. */
    private fun hideStars(effect: StarfieldEffect, bounds: Rectangle) {
        effect.update(dt, bounds, newCollector())
        floats(effect, "baseAlpha").fill(0f)
        // No drift: a star that drifts out would respawn with a fresh, visible base opacity.
        floats(effect, "driftSpeed").fill(0f)
        // Let the stars (which were just spawned invisible) settle at alpha 0.
        repeat(2) { effect.update(dt, bounds, newCollector()) }
    }

    /** Paints a single star at ([px], 20) on a 40×40 canvas, with all the others hidden. */
    private fun paintSingle(px: Float): BufferedImage {
        val bounds = Rectangle(0, 0, 40, 40)
        val effect = started()
        effect.update(dt, bounds, newCollector())
        for (i in 0 until effect.particleCount) effect.placeParticleForTest(i, -100f, -100f, 0f)
        floats(effect, "radius")[0] = 2f
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

    private fun floats(effect: StarfieldEffect, name: String): FloatArray {
        val field = StarfieldEffect::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(effect) as FloatArray
    }

    private fun sumAlpha(effect: StarfieldEffect): Float {
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

    private fun paint(effect: StarfieldEffect, bounds: Rectangle, clip: Rectangle): BufferedImage {
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
