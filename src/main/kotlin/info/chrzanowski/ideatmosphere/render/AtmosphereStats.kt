package info.chrzanowski.ideatmosphere.render

import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.TimeUnit

/**
 * Aggregates per-tick counters and logs them periodically when the IDE runs with
 * `-Dide.atmosphere.debug=true`. Disabled instances cost one boolean check per tick.
 *
 * Logged line: ticks in the period, effective FPS, governed FPS, rate mode (`normal` / `calm` / `fading`
 * as of the last tick), average/max tick cost, average rectangles per tick split into content rectangles painted
 * inside the nested batch (`nested`) and directly (`direct`), and average painted user-space pixels per tick.
 * A healthy batch shows `nested ≈ rects − 1`.
 */
class AtmosphereStats(
    val enabled: Boolean = System.getProperty(DEBUG_PROPERTY, "false").toBoolean(),
    private val periodNanos: Long = TimeUnit.SECONDS.toNanos(10),
    private val logger: Logger = Logger.getInstance(AtmosphereStats::class.java),
    private val clock: () -> Long = System::nanoTime,
) {

    private var periodStart = 0L
    private var ticks = 0L
    private var totalCostMicros = 0L
    private var maxCostMicros = 0L
    private var totalRects = 0L
    private var totalNested = 0L
    private var totalDirect = 0L
    private var totalPixels = 0L
    private var lastGovernedFps = 0
    private var lastMode = MODE_NORMAL

    /** Number of log lines emitted so far (tests). */
    var reports: Long = 0
        private set

    fun recordTick(
        costMicros: Long,
        rects: Int,
        paintedPixels: Long,
        governedFps: Int,
        calm: Boolean = false,
        fading: Boolean = false,
        nestedRects: Int = 0,
        directRects: Int = 0,
    ) {
        if (!enabled) return
        lastMode = if (fading) MODE_FADING else if (calm) MODE_CALM else MODE_NORMAL
        val now = clock()
        if (periodStart == 0L) periodStart = now
        ticks++
        totalCostMicros += costMicros
        if (costMicros > maxCostMicros) maxCostMicros = costMicros
        totalRects += rects
        totalNested += nestedRects
        totalDirect += directRects
        totalPixels += paintedPixels
        lastGovernedFps = governedFps
        val elapsed = now - periodStart
        if (elapsed >= periodNanos) {
            report(elapsed)
            periodStart = now
            ticks = 0
            totalCostMicros = 0
            maxCostMicros = 0
            totalRects = 0
            totalNested = 0
            totalDirect = 0
            totalPixels = 0
        }
    }

    private fun report(elapsedNanos: Long) {
        val seconds = elapsedNanos / 1_000_000_000.0
        val effectiveFps = if (seconds > 0) ticks / seconds else 0.0
        val avgCost = if (ticks > 0) totalCostMicros.toDouble() / ticks else 0.0
        val avgRects = if (ticks > 0) totalRects.toDouble() / ticks else 0.0
        val avgNested = if (ticks > 0) totalNested.toDouble() / ticks else 0.0
        val avgDirect = if (ticks > 0) totalDirect.toDouble() / ticks else 0.0
        val avgPixels = if (ticks > 0) totalPixels.toDouble() / ticks else 0.0
        val cpuPercent = if (seconds > 0) totalCostMicros / (seconds * 10_000.0) else 0.0
        reports++
        logger.info(
            String.format(
                "atmosphere: ticks=%d fps=%.1f (governed %d, %s) tick avg=%.0fµs max=%dµs rects/tick=%.1f (nested %.1f, direct %.1f) px/tick=%.0f cpu≈%.2f%%",
                ticks, effectiveFps, lastGovernedFps, lastMode, avgCost, maxCostMicros, avgRects, avgNested, avgDirect, avgPixels, cpuPercent,
            )
        )
    }

    companion object {
        const val DEBUG_PROPERTY: String = "ide.atmosphere.debug"

        private const val MODE_NORMAL = "normal"
        private const val MODE_CALM = "calm"
        private const val MODE_FADING = "fading"
    }
}
