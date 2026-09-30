package info.chrzanowski.ideatmosphere.render

import java.awt.Rectangle
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Collects the regions changed by an effect during one tick and merges them into repaint rectangles, joining
 * regions only where the cost model says one union is cheaper than separate paints (or when more than
 * [maxRects] clusters remain, which the default of `capacity` never forces).
 *
 * Storage is preallocated (`capacity` rectangles as packed ints) so [add] never allocates on the hot path.
 *
 * Merge strategy ([merge]):
 * 1. every region (clamped to the bounds) is assigned to a cell of a fine grid ([gridCols]×[gridRows], laid over
 *    the bounding box of all regions) by its center and unioned with the other regions of that cell — this bounds
 *    the number of clusters to `gridCols × gridRows` regardless of how many regions were added. The grid is fine
 *    enough (12×8 → ≈126×113 px cells on a 1512×909 content pane) that a forced cell union never costs more than
 *    the flush it saves;
 * 2. clusters are then merged greedily, always joining the pair whose union costs the least extra according to the
 *    repaint cost model `cost(rect) = fixed + area·perPixel`. Merging continues while more than [maxRects] clusters
 *    remain **or** while a merge is cheaper than two separate paints (the saved fixed cost outweighs the added
 *    area). One bounding box is therefore chosen automatically whenever it is the cheapest option. Constants come
 *    from the measurements recorded in `docs/overlay-investigation.md`: ≈120 µs per rectangle painted inside the
 *    nested single-flush batch and ≈5 ns per user-space pixel. With these numbers two 22×22 fireflies are merged
 *    only when they are closer than ≈150 px; otherwise each keeps its own small rectangle.
 *
 * All emitted rectangles are clamped to the bounds passed to [merge]; the same [Rectangle] instance is reused
 * for every callback, so the sink must not retain it.
 */
class DirtyRegionCollector(
    capacity: Int,
    val maxRects: Int = capacity,
    private val fixedCostNanos: Long = DEFAULT_FIXED_COST_NANOS,
    private val perPixelCostNanos: Long = DEFAULT_PER_PIXEL_COST_NANOS,
    private val gridCols: Int = DEFAULT_GRID_COLS,
    private val gridRows: Int = DEFAULT_GRID_ROWS,
) {

    init {
        require(capacity > 0) { "capacity must be positive" }
        require(maxRects > 0) { "maxRects must be positive" }
        require(gridCols > 0 && gridRows > 0) { "grid must have at least one cell" }
    }

    private val minX = IntArray(capacity)
    private val minY = IntArray(capacity)
    private val maxX = IntArray(capacity)
    private val maxY = IntArray(capacity)

    private val cellCount = gridCols * gridRows

    // Cluster storage: cells first (indexed by grid cell), then compacted in place for the greedy phase.
    private val cellMinX = IntArray(cellCount)
    private val cellMinY = IntArray(cellCount)
    private val cellMaxX = IntArray(cellCount)
    private val cellMaxY = IntArray(cellCount)
    private val cellUsed = BooleanArray(cellCount)

    /** Number of regions added since the last [clear]. Additions beyond capacity are dropped. */
    var size: Int = 0
        private set

    /** `true` when at least one region was added and one had to be dropped due to capacity. */
    var overflowed: Boolean = false
        private set

    val capacity: Int get() = minX.size

    fun clear() {
        size = 0
        overflowed = false
    }

    /**
     * Adds the region `[x, x + w] × [y, y + h]` expanded by [padding] on every side.
     * Fractional coordinates are rounded outwards so partially covered pixels are always repainted.
     */
    fun add(x: Float, y: Float, w: Float, h: Float, padding: Float = 0f) {
        if (size >= minX.size) {
            overflowed = true
            return
        }
        val i = size
        minX[i] = floor(x - padding).toInt()
        minY[i] = floor(y - padding).toInt()
        maxX[i] = ceil(x + w + padding).toInt()
        maxY[i] = ceil(y + h + padding).toInt()
        size++
    }

    /**
     * Emits up to `min(maxRects, gridCols × gridRows)` rectangles covering all added regions, clamped to [bounds].
     * Emits nothing when no region was added, when [bounds] is empty or when nothing intersects [bounds].
     */
    fun merge(bounds: Rectangle, sink: (Rectangle) -> Unit) {
        if (size == 0 || bounds.isEmpty) return
        val bx0 = bounds.x
        val by0 = bounds.y
        val bx1 = bounds.x + bounds.width
        val by1 = bounds.y + bounds.height

        // Bounding box of everything (also the fallback when a single region was reported or overflowed).
        var x0 = Int.MAX_VALUE
        var y0 = Int.MAX_VALUE
        var x1 = Int.MIN_VALUE
        var y1 = Int.MIN_VALUE
        for (i in 0 until size) {
            x0 = min(x0, minX[i])
            y0 = min(y0, minY[i])
            x1 = max(x1, maxX[i])
            y1 = max(y1, maxY[i])
        }
        x0 = max(x0, bx0)
        y0 = max(y0, by0)
        x1 = min(x1, bx1)
        y1 = min(y1, by1)
        if (x1 <= x0 || y1 <= y0) return

        if (size == 1 || cellCount == 1 || overflowed) {
            emit(x0, y0, x1, y1, sink)
            return
        }

        // 1. Cell-union: bucket clamped regions by center into the fine grid laid over the bounding box.
        for (c in 0 until cellCount) cellUsed[c] = false
        val spanX = x1 - x0
        val spanY = y1 - y0
        for (i in 0 until size) {
            val rx0 = max(minX[i], bx0)
            val ry0 = max(minY[i], by0)
            val rx1 = min(maxX[i], bx1)
            val ry1 = min(maxY[i], by1)
            if (rx1 <= rx0 || ry1 <= ry0) continue
            val cx = (rx0 + rx1) / 2
            val cy = (ry0 + ry1) / 2
            val col = (((cx - x0).toLong() * gridCols) / spanX).toInt().coerceIn(0, gridCols - 1)
            val row = (((cy - y0).toLong() * gridRows) / spanY).toInt().coerceIn(0, gridRows - 1)
            val c = row * gridCols + col
            if (cellUsed[c]) {
                cellMinX[c] = min(cellMinX[c], rx0)
                cellMinY[c] = min(cellMinY[c], ry0)
                cellMaxX[c] = max(cellMaxX[c], rx1)
                cellMaxY[c] = max(cellMaxY[c], ry1)
            } else {
                cellUsed[c] = true
                cellMinX[c] = rx0
                cellMinY[c] = ry0
                cellMaxX[c] = rx1
                cellMaxY[c] = ry1
            }
        }

        // Compact the used cells to the front so the greedy phase works on a dense list.
        var n = 0
        for (c in 0 until cellCount) {
            if (!cellUsed[c]) continue
            if (n != c) {
                cellMinX[n] = cellMinX[c]
                cellMinY[n] = cellMinY[c]
                cellMaxX[n] = cellMaxX[c]
                cellMaxY[n] = cellMaxY[c]
            }
            n++
        }

        // 2. Greedy agglomeration driven by the cost model.
        while (n > 1) {
            var bestI = -1
            var bestJ = -1
            var bestDelta = Long.MAX_VALUE
            for (i in 0 until n - 1) {
                val costI = rectCost(cellMinX[i], cellMinY[i], cellMaxX[i], cellMaxY[i])
                for (j in i + 1 until n) {
                    val ux0 = min(cellMinX[i], cellMinX[j])
                    val uy0 = min(cellMinY[i], cellMinY[j])
                    val ux1 = max(cellMaxX[i], cellMaxX[j])
                    val uy1 = max(cellMaxY[i], cellMaxY[j])
                    val delta = rectCost(ux0, uy0, ux1, uy1) - costI -
                        rectCost(cellMinX[j], cellMinY[j], cellMaxX[j], cellMaxY[j])
                    if (delta < bestDelta) {
                        bestDelta = delta
                        bestI = i
                        bestJ = j
                    }
                }
            }
            if (n <= maxRects && bestDelta >= 0L) break
            cellMinX[bestI] = min(cellMinX[bestI], cellMinX[bestJ])
            cellMinY[bestI] = min(cellMinY[bestI], cellMinY[bestJ])
            cellMaxX[bestI] = max(cellMaxX[bestI], cellMaxX[bestJ])
            cellMaxY[bestI] = max(cellMaxY[bestI], cellMaxY[bestJ])
            n--
            if (bestJ != n) {
                cellMinX[bestJ] = cellMinX[n]
                cellMinY[bestJ] = cellMinY[n]
                cellMaxX[bestJ] = cellMaxX[n]
                cellMaxY[bestJ] = cellMaxY[n]
            }
        }

        for (c in 0 until n) emit(cellMinX[c], cellMinY[c], cellMaxX[c], cellMaxY[c], sink)
    }

    private fun rectCost(x0: Int, y0: Int, x1: Int, y1: Int): Long =
        fixedCostNanos + (x1 - x0).toLong() * (y1 - y0) * perPixelCostNanos

    private fun emit(x0: Int, y0: Int, x1: Int, y1: Int, sink: (Rectangle) -> Unit) {
        reused.setBounds(x0, y0, x1 - x0, y1 - y0)
        sink(reused)
    }

    private val reused = Rectangle()

    companion object {
        /**
         * Measured fixed overhead of one rectangle painted inside the nested single-flush batch, ≈120 µs
         * (a stand-alone `paintImmediately` with its own buffer flush costs ≈300 µs).
         */
        const val DEFAULT_FIXED_COST_NANOS: Long = 120_000L

        /** Measured incremental cost per repainted user-space pixel, ≈5 ns. */
        const val DEFAULT_PER_PIXEL_COST_NANOS: Long = 5L

        /**
         * Fine bucketing grid (96 cells) that seeds the greedy merge. A cell of a 1512×909 content pane is ≈126×113 px
         * (≈71 µs of area), so unioning two small regions inside one cell is always cheaper than one extra 120 µs paint.
         */
        const val DEFAULT_GRID_COLS: Int = 12
        const val DEFAULT_GRID_ROWS: Int = 8
    }
}
