package info.chrzanowski.ideatmosphere.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameBudgetGovernorTest {

    private fun FrameBudgetGovernor.simulateSeconds(seconds: Int, costMicros: Long) {
        repeat(seconds * 30) { recordTick(costMicros) }
    }

    @Test
    fun `starts at initial fps`() {
        val governor = FrameBudgetGovernor()
        assertEquals(30, governor.fps)
        assertEquals(33, governor.intervalMillis)
    }

    @Test
    fun `the measured nested-batch tick cost keeps the full 30 fps`() {
        // 2.9 ms per tick × 30 fps = 87 ms/s, under the 100 ms/s (≈10 %) budget.
        val governor = FrameBudgetGovernor()
        governor.simulateSeconds(3, costMicros = 2_900)
        assertEquals(30, governor.fps)
    }

    @Test
    fun `sustained cost above budget lowers fps to the 20 fps floor`() {
        val governor = FrameBudgetGovernor()
        governor.simulateSeconds(3, costMicros = 6_000)
        assertEquals(20, governor.fps)
        assertEquals(50, governor.intervalMillis)
    }

    @Test
    fun `cheap ticks raise fps to the maximum`() {
        val governor = FrameBudgetGovernor()
        governor.simulateSeconds(3, costMicros = 100)
        assertEquals(30, governor.fps)
    }

    @Test
    fun `fps recovers after the cost drops`() {
        val governor = FrameBudgetGovernor()
        governor.simulateSeconds(3, costMicros = 6_000)
        assertEquals(20, governor.fps)
        governor.simulateSeconds(5, costMicros = 200)
        assertEquals(30, governor.fps)
    }

    @Test
    fun `fps never leaves the configured range`() {
        val governor = FrameBudgetGovernor()
        for (cost in longArrayOf(0, 1, 50, 1_000, 3_000, 10_000, 100_000, 0)) {
            governor.simulateSeconds(2, cost)
            assertTrue("fps ${governor.fps} out of range for cost $cost", governor.fps in 20..30)
        }
    }

    @Test
    fun `hysteresis ignores changes of a single fps`() {
        // budget 100_000 / 4_000 µs = 25 fps → applied; 4_100 µs → 24 fps → within hysteresis, stays.
        val governor = FrameBudgetGovernor()
        governor.simulateSeconds(3, costMicros = 4_000)
        assertEquals(25, governor.fps)
        governor.simulateSeconds(3, costMicros = 4_100)
        assertEquals(25, governor.fps)
        // 4_500 µs → 22 fps → applied.
        governor.simulateSeconds(3, costMicros = 4_500)
        assertEquals(22, governor.fps)
    }

    @Test
    fun `reset forgets samples but keeps the rate`() {
        val governor = FrameBudgetGovernor()
        governor.simulateSeconds(3, costMicros = 6_000)
        governor.reset()
        assertEquals(20, governor.fps)
        assertEquals(0f, governor.averageTickMicros)
    }
}
