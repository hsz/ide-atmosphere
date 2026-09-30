package info.chrzanowski.ideatmosphere.render

import kotlin.math.abs

/**
 * Chooses the animation frame rate so that `tickCost × fps` stays under a CPU budget.
 *
 * The cost of every tick (simulation + synchronous repaints, in microseconds) is fed through
 * [recordTick] and smoothed with an exponential moving average. Roughly once per second
 * (every `fps` ticks) the target rate is recomputed as `budget / avgTick`, clamped to
 * `[minFps, maxFps]`. A change is applied only when it differs from the current rate by at least
 * [hysteresisFps] so the rate does not flap between adjacent values.
 *
 * Default budget: 100 000 µs of work per second ≈ 10 % of one core, governed between 20 and 30 FPS. The ceiling
 * was raised from 3 % after the smoothness fix (see `docs/overlay-investigation.md` §10.3): with the nested batch
 * a tick costs ≈ 3 ms, so 30 FPS fits, and the floor of 20 FPS keeps the motion fluid before any other lever
 * (particle count, idle speed) is touched.
 */
class FrameBudgetGovernor(
    private val budgetMicrosPerSecond: Int = 100_000,
    val minFps: Int = 20,
    val maxFps: Int = 30,
    initialFps: Int = 30,
    private val smoothing: Float = 0.2f,
    private val hysteresisFps: Int = 2,
) {

    init {
        require(minFps in 1..maxFps) { "minFps must be within 1..maxFps" }
        require(initialFps in minFps..maxFps) { "initialFps must be within [minFps, maxFps]" }
        require(smoothing > 0f && smoothing <= 1f) { "smoothing must be in (0, 1]" }
    }

    /** Current frame rate in frames per second, always within `[minFps, maxFps]`. */
    var fps: Int = initialFps
        private set

    /** Timer delay derived from [fps]. */
    val intervalMillis: Int
        get() = 1000 / fps

    /** Smoothed tick cost in microseconds; `0` until the first tick was recorded. */
    var averageTickMicros: Float = 0f
        private set

    private var ticksSinceEvaluation = 0
    private var hasSamples = false

    fun recordTick(costMicros: Long) {
        val cost = costMicros.coerceAtLeast(0L).toFloat()
        averageTickMicros = if (hasSamples) {
            averageTickMicros + smoothing * (cost - averageTickMicros)
        } else {
            hasSamples = true
            cost
        }
        ticksSinceEvaluation++
        if (ticksSinceEvaluation >= fps) {
            ticksSinceEvaluation = 0
            reevaluate()
        }
    }

    /** Forgets the measured history; the next evaluation starts from fresh samples. Keeps the current [fps]. */
    fun reset() {
        averageTickMicros = 0f
        hasSamples = false
        ticksSinceEvaluation = 0
    }

    private fun reevaluate() {
        val desired = if (averageTickMicros <= 0f) {
            maxFps
        } else {
            (budgetMicrosPerSecond / averageTickMicros).toInt().coerceIn(minFps, maxFps)
        }
        if (desired == fps) return
        val atBoundary = desired == minFps || desired == maxFps
        if (atBoundary || abs(desired - fps) >= hysteresisFps) {
            fps = desired
        }
    }
}
