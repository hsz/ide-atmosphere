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
        assertTrue("a particle right next to the pointer must clearly escape", avoidance.velocityX(0) > 150f)
        assertEquals(0f, avoidance.velocityY(0), 0f)
        assertTrue(avoidance.velocityY(1) < -100f)
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