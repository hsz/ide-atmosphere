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