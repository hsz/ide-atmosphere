package info.chrzanowski.ideatmosphere.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class CursorAvoidanceTest {
    @Test
    fun `steering is local bounded and directed away from the cursor`() {
        val avoidance = CursorAvoidance(3)
        avoidance.setPointer(100f, 100f, true)
        repeat(100) {
            avoidance.advance(0, 110f, 100f, 1f / 30f, 1f)
            avoidance.advance(1, 100f, 80f, 1f / 30f, 1f)
            avoidance.advance(2, 500f, 100f, 1f / 30f, 1f)
        }
        assertTrue("a particle right next to the pointer must clearly escape", avoidance.velocityX(0) > 80f)
        assertEquals(0f, avoidance.velocityY(0), 0f)
        assertTrue(avoidance.velocityY(1) < -60f)
        assertEquals(0f, avoidance.velocityX(1), 0f)
        assertEquals("outside the radius nothing happens", 0f, avoidance.velocityX(2), 0f)
        assertTrue(hypot(avoidance.velocityX(0), avoidance.velocityY(0)) <= CursorAvoidance.MAX_SPEED)
    }

    @Test
    fun `push fades smoothly towards the edge of the radius`() {
        val avoidance = CursorAvoidance(3)
        avoidance.setPointer(0f, 0f, true)
        val edge = CursorAvoidance.RADIUS
        repeat(100) {
            avoidance.advance(0, edge * 0.25f, 0f, 1f / 30f, 1f)
            avoidance.advance(1, edge * 0.75f, 0f, 1f / 30f, 1f)
            avoidance.advance(2, edge * 0.99f, 0f, 1f / 30f, 1f)
        }
        assertTrue(avoidance.velocityX(0) > avoidance.velocityX(1))
        assertTrue(avoidance.velocityX(1) > avoidance.velocityX(2))
        assertTrue("the push must vanish at the edge, not stop abruptly", avoidance.velocityX(2) < 2f)
        assertTrue(avoidance.velocityX(2) > 0f)
    }

    @Test
    fun `overlapping cursor never produces NaN and motion decays after leaving`() {
        val avoidance = CursorAvoidance(2)
        avoidance.setPointer(100f, 100f, true)
        repeat(100) { avoidance.advance(1, 100f, 100f, 1f / 30f, 2f) }
        val before = hypot(avoidance.velocityX(1), avoidance.velocityY(1))
        assertTrue(before.isFinite() && before > 100f && before <= CursorAvoidance.MAX_SPEED * 2f + 0.001f)
        avoidance.setPointer(0f, 0f, false)
        avoidance.advance(1, 100f, 100f, 2f, 2f)
        assertTrue(hypot(avoidance.velocityX(1), avoidance.velocityY(1)) < before * 0.001f)
        avoidance.reset(1)
        assertEquals(0f, avoidance.velocityX(1), 0f)
        assertEquals(0f, avoidance.velocityY(1), 0f)
    }

    @Test
    fun `attract pulls particles towards the cursor and holds them at the soft stop radius`() {
        val avoidance = CursorAvoidance(3)
        avoidance.mode = PointerMode.ATTRACT
        avoidance.setPointer(100f, 100f, true)
        repeat(100) {
            avoidance.advance(0, 160f, 100f, 1f / 30f, 1f)
            avoidance.advance(1, 100f, 40f, 1f / 30f, 1f)
            avoidance.advance(2, 500f, 100f, 1f / 30f, 1f)
        }
        assertTrue("a particle near the pointer must clearly be pulled in", avoidance.velocityX(0) < -60f)
        assertEquals(0f, avoidance.velocityY(0), 0f)
        assertTrue(avoidance.velocityY(1) > 60f)
        assertEquals(0f, avoidance.velocityX(1), 0f)
        assertEquals("outside the radius nothing happens", 0f, avoidance.velocityX(2), 0f)
        assertTrue(hypot(avoidance.velocityX(0), avoidance.velocityY(0)) <= CursorAvoidance.MAX_SPEED)

        // Inside the stop radius there is no pull and the remaining speed bleeds off faster than plain damping.
        val pullBefore = avoidance.velocityX(0)
        avoidance.advance(0, 100f + CursorAvoidance.STOP_RADIUS * 0.5f, 100f, 0.2f, 1f)
        assertTrue(avoidance.velocityX(0) < 0f)
        assertTrue(avoidance.velocityX(0) > pullBefore * 0.1f)
        assertTrue("faster than the plain exp(-5 * 0.2) decay", avoidance.velocityX(0) > pullBefore * 0.3f)
        avoidance.advance(0, 100f, 100f, 2f, 1f)
        assertTrue(hypot(avoidance.velocityX(0), avoidance.velocityY(0)) < 0.01f)
    }

    @Test
    fun `attract pull vanishes at both the outer edge and the stop radius`() {
        val avoidance = CursorAvoidance(3)
        avoidance.mode = PointerMode.ATTRACT
        avoidance.setPointer(0f, 0f, true)
        val stop = CursorAvoidance.STOP_RADIUS
        val edge = CursorAvoidance.RADIUS
        repeat(100) {
            avoidance.advance(0, stop + 1f, 0f, 1f / 30f, 1f)
            avoidance.advance(1, (stop + edge) / 2f, 0f, 1f / 30f, 1f)
            avoidance.advance(2, edge * 0.99f, 0f, 1f / 30f, 1f)
        }
        assertTrue(avoidance.velocityX(0) < avoidance.velocityX(1))
        assertTrue(avoidance.velocityX(1) < avoidance.velocityX(2))
        assertTrue("the pull must vanish at the edge, not stop abruptly", avoidance.velocityX(2) > -2f)
        assertTrue(avoidance.velocityX(2) < 0f)
    }

    @Test
    fun `off ignores the pointer and clears any steering`() {
        val avoidance = CursorAvoidance(1)
        avoidance.setPointer(100f, 100f, true)
        repeat(30) { avoidance.advance(0, 110f, 100f, 1f / 30f, 1f) }
        assertTrue(avoidance.velocityX(0) > 0f)

        avoidance.mode = PointerMode.OFF
        assertEquals(0f, avoidance.velocityX(0), 0f)
        avoidance.setPointer(100f, 100f, true)
        repeat(30) { avoidance.advance(0, 110f, 100f, 1f / 30f, 1f) }
        assertEquals(0f, avoidance.velocityX(0), 0f)
        avoidance.burst(100f, 100f, 1f, 1, floatArrayOf(110f), floatArrayOf(100f))
        assertEquals(0f, avoidance.velocityX(0), 0f)

        avoidance.mode = PointerMode.AVOID
        repeat(30) { avoidance.advance(0, 110f, 100f, 1f / 30f, 1f) }
        assertTrue(avoidance.velocityX(0) > 0f)
    }

    @Test
    fun `burst puffs nearby particles outward strongest at the centre and decays`() {
        val xs = floatArrayOf(110f, 100f, 100f + CursorAvoidance.BURST_RADIUS * 0.9f, 100f + CursorAvoidance.BURST_RADIUS, 100f)
        val ys = floatArrayOf(100f, 60f, 100f, 100f, 100f)
        for (mode in listOf(PointerMode.AVOID, PointerMode.ATTRACT)) {
            val avoidance = CursorAvoidance(5)
            avoidance.mode = mode
            avoidance.burst(100f, 100f, 1f, 5, xs, ys)

            assertTrue(mode.name, avoidance.velocityX(0) > 100f)
            assertEquals(0f, avoidance.velocityY(0), 0f)
            assertTrue(avoidance.velocityY(1) < -60f)
            assertEquals(0f, avoidance.velocityX(1), 0f)
            assertTrue(avoidance.velocityX(2) > 0f)
            assertTrue(avoidance.velocityX(2) < avoidance.velocityX(0))
            assertEquals("the edge of the burst is untouched", 0f, avoidance.velocityX(3), 0f)
            val under = hypot(avoidance.velocityX(4), avoidance.velocityY(4))
            assertTrue("a particle under the click still gets a direction", under > 100f && under.isFinite())
            assertTrue(under <= CursorAvoidance.BURST_SPEED + 0.001f)

            val before = avoidance.velocityX(0)
            avoidance.advance(0, 110f, 100f, 2f, 1f)
            assertTrue(avoidance.velocityX(0) < before * 0.001f)
        }
        val scaled = CursorAvoidance(1)
        scaled.burst(200f, 200f, 2f, 1, floatArrayOf(220f), floatArrayOf(200f))
        val normal = CursorAvoidance(1)
        normal.burst(100f, 100f, 1f, 1, floatArrayOf(110f), floatArrayOf(100f))
        assertEquals(normal.velocityX(0) * 2f, scaled.velocityX(0), 0.001f)
        normal.burst(Float.NaN, 100f, 1f, 1, floatArrayOf(110f), floatArrayOf(100f))
        assertTrue(normal.velocityX(0).isFinite())
    }

    @Test
    fun `gust pushes nearby particles along its direction strongest at the centre and decays`() {
        val xs = floatArrayOf(100f, 100f, 100f + CursorAvoidance.GUST_RADIUS * 0.9f, 100f + CursorAvoidance.GUST_RADIUS, 150f)
        val ys = floatArrayOf(100f, 60f, 100f, 100f, 100f)
        for (mode in listOf(PointerMode.AVOID, PointerMode.ATTRACT)) {
            val avoidance = CursorAvoidance(5)
            avoidance.mode = mode
            // Scrolling down: the content, and the particles, move up.
            avoidance.gust(100f, 100f, 0f, -1f, 1f, 1f, 5, xs, ys)

            assertEquals("$mode: a full-strength gust at the centre reaches the peak", -CursorAvoidance.GUST_SPEED, avoidance.velocityY(0), 0.001f)
            assertEquals("a gust has one direction, no sideways component", 0f, avoidance.velocityX(0), 0f)
            assertTrue(avoidance.velocityY(1) < 0f && avoidance.velocityY(1) > avoidance.velocityY(0))
            assertTrue("weaker towards the edge", avoidance.velocityY(2) < 0f && avoidance.velocityY(2) > avoidance.velocityY(1))
            assertEquals("the edge of the gust is untouched", 0f, avoidance.velocityY(3), 0f)
            assertTrue("every particle in reach moves the same way", avoidance.velocityY(4) < 0f)
            assertEquals(0f, avoidance.velocityX(4), 0f)

            val before = avoidance.velocityY(0)
            avoidance.advance(0, 100f, 100f, 2f, 1f)
            assertTrue("the gust decays like any steering", avoidance.velocityY(0) > before * 0.001f)
        }

        // Strength scales the push and is clamped to 1; a direction is normalised; scrolling up pushes down.
        val half = CursorAvoidance(1)
        half.gust(100f, 100f, 0f, -1f, 0.5f, 1f, 1, floatArrayOf(100f), floatArrayOf(100f))
        assertEquals(-CursorAvoidance.GUST_SPEED * 0.5f, half.velocityY(0), 0.001f)
        val over = CursorAvoidance(1)
        over.gust(100f, 100f, 0f, -5f, 7f, 1f, 1, floatArrayOf(100f), floatArrayOf(100f))
        assertEquals(-CursorAvoidance.GUST_SPEED, over.velocityY(0), 0.001f)
        val down = CursorAvoidance(1)
        down.gust(100f, 100f, 0f, 1f, 1f, 1f, 1, floatArrayOf(100f), floatArrayOf(100f))
        assertEquals(CursorAvoidance.GUST_SPEED, down.velocityY(0), 0.001f)
        val diagonal = CursorAvoidance(1)
        diagonal.gust(100f, 100f, 3f, 4f, 1f, 1f, 1, floatArrayOf(100f), floatArrayOf(100f))
        assertEquals(CursorAvoidance.GUST_SPEED * 0.6f, diagonal.velocityX(0), 0.001f)
        assertEquals(CursorAvoidance.GUST_SPEED * 0.8f, diagonal.velocityY(0), 0.001f)

        // Repeated gusts never exceed the gust speed; the UI scale multiplies reach and speed alike.
        repeat(10) { down.gust(100f, 100f, 0f, 1f, 1f, 1f, 1, floatArrayOf(100f), floatArrayOf(100f)) }
        assertEquals(CursorAvoidance.GUST_SPEED, hypot(down.velocityX(0), down.velocityY(0)), 0.001f)
        val scaled = CursorAvoidance(1)
        scaled.gust(200f, 200f, 0f, -1f, 1f, 2f, 1, floatArrayOf(200f), floatArrayOf(200f + CursorAvoidance.GUST_RADIUS * 1.5f))
        assertTrue("twice the reach at scale 2", scaled.velocityY(0) < 0f)
        assertTrue(hypot(scaled.velocityX(0), scaled.velocityY(0)) <= CursorAvoidance.GUST_SPEED * 2f + 0.001f)

        // Off, a zero or non-finite direction, zero strength, or a non-finite centre leave everything alone.
        val off = CursorAvoidance(1)
        off.mode = PointerMode.OFF
        off.gust(100f, 100f, 0f, -1f, 1f, 1f, 1, floatArrayOf(100f), floatArrayOf(100f))
        assertEquals(0f, off.velocityY(0), 0f)
        val still = CursorAvoidance(1)
        still.gust(100f, 100f, 0f, 0f, 1f, 1f, 1, floatArrayOf(100f), floatArrayOf(100f))
        still.gust(100f, 100f, Float.NaN, 1f, 1f, 1f, 1, floatArrayOf(100f), floatArrayOf(100f))
        still.gust(100f, 100f, 0f, 1f, 0f, 1f, 1, floatArrayOf(100f), floatArrayOf(100f))
        still.gust(100f, 100f, 0f, 1f, Float.NaN, 1f, 1, floatArrayOf(100f), floatArrayOf(100f))
        still.gust(Float.NaN, 100f, 0f, 1f, 1f, 1f, 1, floatArrayOf(100f), floatArrayOf(100f))
        assertEquals(0f, still.velocityX(0), 0f)
        assertEquals(0f, still.velocityY(0), 0f)
    }

    @Test
    fun `anchor pulls nearby particles in whatever the pointer mode and holds them at the stop radius`() {
        for (mode in listOf(PointerMode.AVOID, PointerMode.ATTRACT)) {
            val avoidance = CursorAvoidance(3)
            avoidance.mode = mode
            avoidance.setAnchor(100f, 100f, true)
            assertTrue(avoidance.isAnchored)
            repeat(100) {
                avoidance.advance(0, 160f, 100f, 1f / 30f, 1f)
                avoidance.advance(1, 100f, 100f - CursorAvoidance.ANCHOR_RADIUS * 0.6f, 1f / 30f, 1f)
                avoidance.advance(2, 100f + CursorAvoidance.ANCHOR_RADIUS, 100f, 1f / 30f, 1f)
            }
            assertTrue("$mode: a particle near the anchor must be pulled in", avoidance.velocityX(0) < -20f)
            assertEquals(0f, avoidance.velocityY(0), 0f)
            assertTrue(avoidance.velocityY(1) > 20f)
            assertEquals(0f, avoidance.velocityX(1), 0f)
            assertEquals("outside the anchor radius nothing happens", 0f, avoidance.velocityX(2), 0f)
            assertTrue(hypot(avoidance.velocityX(0), avoidance.velocityY(0)) <= CursorAvoidance.MAX_SPEED)

            // Inside the stop radius there is no pull and the remaining speed bleeds off quickly.
            avoidance.advance(0, 100f + CursorAvoidance.STOP_RADIUS * 0.5f, 100f, 2f, 1f)
            assertTrue(hypot(avoidance.velocityX(0), avoidance.velocityY(0)) < 0.01f)
        }

        // The anchor is a smaller attractor than the pointer: same distance, weaker pull.
        val pointer = CursorAvoidance(1)
        pointer.mode = PointerMode.ATTRACT
        pointer.setPointer(100f, 100f, true)
        val anchor = CursorAvoidance(1)
        anchor.setAnchor(100f, 100f, true)
        repeat(30) {
            pointer.advance(0, 100f + CursorAvoidance.STOP_RADIUS + 10f, 100f, 1f / 30f, 1f)
            anchor.advance(0, 100f + CursorAvoidance.STOP_RADIUS + 10f, 100f, 1f / 30f, 1f)
        }
        assertTrue(anchor.velocityX(0) < 0f)
        assertTrue("the anchor pulls less than the pointer", anchor.velocityX(0) > pointer.velocityX(0) * 0.8f)

        // Pointer avoidance and the anchor combine: fleeing the pointer still bends towards the anchor.
        val both = CursorAvoidance(1)
        both.setPointer(100f, 100f, true)
        both.setAnchor(130f, 160f, true)
        repeat(30) { both.advance(0, 130f, 100f, 1f / 30f, 1f) }
        assertTrue(both.velocityX(0) > 0f)
        assertTrue("pulled down towards the anchor while fleeing right", both.velocityY(0) > 0f)
        assertTrue(hypot(both.velocityX(0), both.velocityY(0)) <= CursorAvoidance.MAX_SPEED + 0.001f)
    }

    @Test
    fun `anchor is cleared by deactivation off mode and clear and rejects invalid coordinates`() {
        val avoidance = CursorAvoidance(1)
        avoidance.setAnchor(100f, 100f, true)
        repeat(30) { avoidance.advance(0, 150f, 100f, 1f / 30f, 1f) }
        assertTrue(avoidance.velocityX(0) < 0f)

        avoidance.setAnchor(100f, 100f, false)
        assertTrue(!avoidance.isAnchored)
        avoidance.advance(0, 150f, 100f, 2f, 1f)
        repeat(30) { avoidance.advance(0, 150f, 100f, 1f / 30f, 1f) }
        assertTrue("no pull without an anchor", hypot(avoidance.velocityX(0), avoidance.velocityY(0)) < 0.01f)

        avoidance.setAnchor(100f, 100f, true)
        avoidance.mode = PointerMode.OFF
        assertTrue(!avoidance.isAnchored)
        repeat(30) { avoidance.advance(0, 150f, 100f, 1f / 30f, 1f) }
        assertEquals(0f, avoidance.velocityX(0), 0f)
        avoidance.mode = PointerMode.AVOID
        assertTrue("switching off forgets the anchor; the frame re-applies it", !avoidance.isAnchored)

        avoidance.setAnchor(100f, 100f, true)
        avoidance.clear()
        assertTrue(!avoidance.isAnchored)

        avoidance.setAnchor(Float.NaN, 100f, true)
        assertTrue(!avoidance.isAnchored)
        repeat(30) { avoidance.advance(0, 150f, 100f, 1f / 30f, 1f) }
        assertEquals(0f, avoidance.velocityX(0), 0f)
    }

    @Test
    fun `steering scales with UI coordinates not animation speed and rejects invalid pointers`() {
        val normal = CursorAvoidance(1)
        val scaled = CursorAvoidance(1)
        normal.setPointer(100f, 100f, true)
        scaled.setPointer(200f, 200f, true)
        normal.advance(0, 130f, 100f, 0.1f, 1f)
        scaled.advance(0, 260f, 200f, 0.1f, 2f)
        assertEquals(normal.velocityX(0) * 2f, scaled.velocityX(0), 0.001f)
        scaled.clear()
        scaled.setPointer(Float.NaN, 0f, true)
        scaled.advance(0, 0f, 0f, 0.1f, 1f)
        assertEquals(0f, scaled.velocityX(0), 0f)
        assertEquals(0f, scaled.velocityY(0), 0f)
    }
}