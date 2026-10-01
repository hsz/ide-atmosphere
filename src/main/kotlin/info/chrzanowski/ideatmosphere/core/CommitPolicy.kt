package info.chrzanowski.ideatmosphere.core

import kotlin.math.abs

/**
 * Decides when a simulated particle is *committed*, i.e. reported dirty and its painted state advanced.
 *
 * Painting cost is proportional to the number of dirty rectangles, not to the simulation cost, so a particle
 * is only repainted once its on-screen state would visibly change: it moved at least [MOVE_THRESHOLD] user
 * pixels (≈ 0.8 device px on a 2× display), its alpha changed by at least [ALPHA_STEP_THRESHOLD] of 256 steps,
 * or it has been accumulating invisible motion for [MAX_STALE_TICKS] ticks. Slow or distant particles therefore
 * skip ticks while fast ones are still repainted every tick.
 */
object CommitPolicy {
    /** Displacement (user px) at which a particle is repainted. */
    const val MOVE_THRESHOLD: Float = 0.4f

    /** Alpha change (in 1/256 steps) at which a particle is repainted. */
    const val ALPHA_STEP_THRESHOLD: Int = 3

    /** Number of consecutive un-committed ticks after which any pending change is committed. */
    const val MAX_STALE_TICKS: Int = 4

    /**
     * @param dx simulated minus painted horizontal position (user px)
     * @param dy simulated minus painted vertical position (user px)
     * @param alphaStepDelta absolute difference of the painted and simulated alpha in 1/256 steps
     * @param staleTicks ticks since the particle was last committed
     * @param moveThreshold displacement threshold; effects may pass a scaled value
     */
    @JvmStatic
    @JvmOverloads
    fun shouldCommit(
        dx: Float,
        dy: Float,
        alphaStepDelta: Int,
        staleTicks: Int,
        moveThreshold: Float = MOVE_THRESHOLD,
    ): Boolean {
        val adx = abs(dx)
        val ady = abs(dy)
        if (adx >= moveThreshold || ady >= moveThreshold || alphaStepDelta >= ALPHA_STEP_THRESHOLD) return true
        return staleTicks >= MAX_STALE_TICKS && (adx > 0f || ady > 0f || alphaStepDelta > 0)
    }
}
