package info.chrzanowski.ideatmosphere.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Rectangle

class DirtyRegionCollectorTest {

    private fun DirtyRegionCollector.mergeAll(bounds: Rectangle): List<Rectangle> {
        val out = ArrayList<Rectangle>()
        merge(bounds) { out.add(Rectangle(it)) }
        return out
    }

    @Test
    fun `empty collector emits nothing`() {
        val collector = DirtyRegionCollector(capacity = 8)
        assertTrue(collector.mergeAll(Rectangle(0, 0, 100, 100)).isEmpty())
    }

    @Test
    fun `single region is rounded outwards with padding`() {
        val collector = DirtyRegionCollector(capacity = 8)
        collector.add(10.4f, 20.6f, 5.2f, 3.1f, padding = 2f)
        val rects = collector.mergeAll(Rectangle(0, 0, 100, 100))
        assertEquals(listOf(Rectangle(8, 18, 10, 8)), rects)
    }

    @Test
    fun `regions are clamped to bounds and dropped when fully outside`() {
        val collector = DirtyRegionCollector(capacity = 8)
        collector.add(-10f, -10f, 20f, 20f)
        assertEquals(listOf(Rectangle(0, 0, 10, 10)), collector.mergeAll(Rectangle(0, 0, 100, 100)))

        collector.clear()
        collector.add(200f, 200f, 10f, 10f)
        assertTrue(collector.mergeAll(Rectangle(0, 0, 100, 100)).isEmpty())
    }

    /**
     * Measured fixed overhead of one rectangle painted inside the nested single-flush batch, ≈500 µs
     * (re-measured in Step 15 to account for real-world underlying IDE component repaints;
     * an isolated probe suggested ≈120 µs).
     */
    private fun cost(r: Rectangle): Long =
        DirtyRegionCollector.DEFAULT_FIXED_COST_NANOS +
            r.width.toLong() * r.height * DirtyRegionCollector.DEFAULT_PER_PIXEL_COST_NANOS

    /** Deterministic pseudo-random `count` squares of `side` px (already padded) inside a 1512×949 window. */
    private fun randomFireflies(collector: DirtyRegionCollector, count: Int, side: Float, seed0: Int): List<Rectangle> {
        val inputs = ArrayList<Rectangle>()
        var seed = seed0
        repeat(count) {
            seed = seed * 1103515245 + 12345
            val x = ((seed ushr 8) % (1512 - side.toInt())).toFloat()
            seed = seed * 1103515245 + 12345
            val y = ((seed ushr 8) % (949 - side.toInt())).toFloat()
            collector.add(x, y, side, side)
            inputs.add(Rectangle(x.toInt(), y.toInt(), side.toInt(), side.toInt()))
        }
        return inputs
    }

    /** Number of connected groups of inputs whose union would be cheaper than separate paints (transitively). */
    private fun cheapClusters(inputs: List<Rectangle>): Int {
        val parent = IntArray(inputs.size) { it }
        fun find(i: Int): Int = if (parent[i] == i) i else find(parent[i]).also { parent[i] = it }
        for (i in inputs.indices) for (j in i + 1 until inputs.size) {
            if (cost(inputs[i].union(inputs[j])) < cost(inputs[i]) + cost(inputs[j])) parent[find(i)] = find(j)
        }
        return inputs.indices.count { find(it) == it }
    }

    @Test
    fun `24 fireflies on a large window keep small rects and unions never cost more than the paints they replace`() {
        val collector = DirtyRegionCollector(capacity = 128)
        val bounds = Rectangle(0, 0, 1512, 949)
        val inputs = randomFireflies(collector, count = 24, side = 22f, seed0 = 12345)

        val rects = collector.mergeAll(bounds)

        val clusters = cheapClusters(inputs)
        assertTrue("expected between $clusters and 24 rects but got ${rects.size}", rects.size in clusters..24)
        assertTrue("per-particle rects must not be forced into a few big ones", rects.size > 3)
        for (input in inputs) {
            assertTrue("input $input not covered", rects.any { it.contains(input) })
        }
        for (rect in rects) {
            val covered = inputs.filter { rect.contains(it) }
            assertTrue("rect $rect covers no input", covered.isNotEmpty())
            val separate = covered.sumOf { cost(it) }
            assertTrue("rect $rect (${cost(rect)} ns) is more expensive than its ${covered.size} inputs ($separate ns)",
                covered.size == 1 || cost(rect) <= separate)
        }
        assertEquals(inputs.size, rects.sumOf { rect -> inputs.count { rect.contains(it) } })
    }

    @Test
    fun `merging 50 regions stays well under one millisecond`() {
        val collector = DirtyRegionCollector(capacity = 128)
        val bounds = Rectangle(0, 0, 1512, 949)
        val sink: (Rectangle) -> Unit = { }
        // Warm up the JIT, then take the best of a few runs so a GC pause cannot fail the test.
        var best = Long.MAX_VALUE
        repeat(60) { run ->
            collector.clear()
            randomFireflies(collector, count = 50, side = 22f, seed0 = 777 + run)
            val start = System.nanoTime()
            collector.merge(bounds, sink)
            val elapsed = System.nanoTime() - start
            if (run >= 20 && elapsed < best) best = elapsed
        }
        assertTrue("merge of 50 regions took ${best / 1000} µs", best < 1_000_000L)
    }

    @Test
    fun `default maxRects equals capacity so only the cost model merges`() {
        val collector = DirtyRegionCollector(capacity = 40)
        assertEquals(40, collector.maxRects)
        assertEquals(40, collector.capacity)
    }

    @Test
    fun `cost model prefers one bounding box when the window is small`() {
        val collector = DirtyRegionCollector(capacity = 32)
        val bounds = Rectangle(0, 0, 200, 120)
        // one region per grid cell
        for (row in 0 until 2) for (col in 0 until 3) {
            collector.add(10f + col * 60f, 10f + row * 60f, 8f, 8f)
        }
        val rects = collector.mergeAll(bounds)
        assertEquals(1, rects.size)
        assertEquals(Rectangle(10, 10, 128, 68), rects[0])
    }

    @Test
    fun `cost model keeps six separate rects when every union would be expensive`() {
        val collector = DirtyRegionCollector(capacity = 32)
        val bounds = Rectangle(0, 0, 3000, 2000)
        // 400×400 regions 1200 px apart: any union adds ≥ 640 k px (≈ 3.2 ms) which never pays for one saved 500 µs paint.
        for (row in 0 until 2) for (col in 0 until 3) {
            collector.add(100f + col * 1200f, 100f + row * 1000f, 400f, 400f)
        }
        val rects = collector.mergeAll(bounds)
        assertEquals(6, rects.size)
        for (row in 0 until 2) for (col in 0 until 3) {
            assertTrue(rects.contains(Rectangle(100 + col * 1200, 100 + row * 1000, 400, 400)))
        }
    }

    @Test
    fun `cost model merges tiny far-apart regions when the saved flush outweighs the added area`() {
        val collector = DirtyRegionCollector(capacity = 32)
        val bounds = Rectangle(0, 0, 3000, 2000)
        // Two 8×8 dots on one row: the union strip is 1208×8 ≈ 9.7 k px (≈ 48 µs) < one 500 µs paint.
        collector.add(100f, 100f, 8f, 8f)
        collector.add(1300f, 100f, 8f, 8f)
        assertEquals(listOf(Rectangle(100, 100, 1208, 8)), collector.mergeAll(bounds))
    }

    @Test
    fun `more clusters than maxRects are merged down to maxRects even when expensive`() {
        val collector = DirtyRegionCollector(capacity = 64, maxRects = 4)
        val bounds = Rectangle(0, 0, 6000, 4000)
        for (row in 0 until 4) for (col in 0 until 6) {
            collector.add(100f + col * 1000f, 100f + row * 1000f, 300f, 300f)
        }
        val rects = collector.mergeAll(bounds)
        assertEquals(4, rects.size)
        for (row in 0 until 4) for (col in 0 until 6) {
            val input = Rectangle(100 + col * 1000, 100 + row * 1000, 300, 300)
            assertTrue("input $input not covered", rects.any { it.contains(input) })
        }
    }

    @Test
    fun `nearby regions collapse into one rect while distant ones stay apart`() {
        val collector = DirtyRegionCollector(capacity = 32)
        val bounds = Rectangle(0, 0, 1512, 949)
        // A cluster of three fireflies in the top-left corner and one far away in the bottom-right corner.
        collector.add(100f, 100f, 6f, 6f, padding = 10f)
        collector.add(130f, 90f, 6f, 6f, padding = 10f)
        collector.add(115f, 140f, 6f, 6f, padding = 10f)
        collector.add(1400f, 850f, 6f, 6f, padding = 10f)
        val rects = collector.mergeAll(bounds)
        assertEquals(2, rects.size)
        assertTrue(rects.contains(Rectangle(90, 80, 56, 76)))
        assertTrue(rects.contains(Rectangle(1390, 840, 26, 26)))
    }

    @Test
    fun `regions outside bounds do not contribute to clusters`() {
        val collector = DirtyRegionCollector(capacity = 8)
        val bounds = Rectangle(0, 0, 1000, 1000)
        collector.add(-100f, -100f, 10f, 10f)
        collector.add(2000f, 2000f, 10f, 10f)
        collector.add(500f, 500f, 10f, 10f)
        assertEquals(listOf(Rectangle(500, 500, 10, 10)), collector.mergeAll(bounds))
    }

    @Test
    fun `overflow drops extra regions and falls back to bounding box`() {
        val collector = DirtyRegionCollector(capacity = 2)
        collector.add(0f, 0f, 10f, 10f)
        collector.add(50f, 50f, 10f, 10f)
        assertFalse(collector.overflowed)
        collector.add(90f, 90f, 10f, 10f)
        assertTrue(collector.overflowed)
        assertEquals(2, collector.size)
        assertEquals(listOf(Rectangle(0, 0, 60, 60)), collector.mergeAll(Rectangle(0, 0, 100, 100)))
    }

    @Test
    fun `clear resets state`() {
        val collector = DirtyRegionCollector(capacity = 1)
        collector.add(0f, 0f, 10f, 10f)
        collector.add(0f, 0f, 10f, 10f)
        collector.clear()
        assertEquals(0, collector.size)
        assertFalse(collector.overflowed)
    }
}
