package info.chrzanowski.ideatmosphere.core

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Local, damped steering in root-pane user pixels; no input interception or hot-path allocation.
 *
 * Particles within [RADIUS] of the pointer are accelerated straight away from it; the push grows smoothly from
 * nothing at the edge of the radius to a clear escape (capped at [MAX_SPEED]) right under the cursor, and the
 * extra velocity decays with [DAMPING] once the pointer has moved on, so particles glide back to their own motion.
 */
internal class CursorAvoidance(capacity: Int) {
    private val vx = FloatArray(capacity)
    private val vy = FloatArray(capacity)
    private var cursorX = 0f
    private var cursorY = 0f
    private var active = false

    fun setPointer(x: Float, y: Float, inside: Boolean) {
        active = inside && x.isFinite() && y.isFinite()
        cursorX = x
        cursorY = y
    }

    fun reset(index: Int) {
        vx[index] = 0f
        vy[index] = 0f
    }

    fun clear() {
        active = false
        vx.fill(0f)
        vy.fill(0f)
    }

    fun advance(index: Int, x: Float, y: Float, delta: Float, scale: Float) {
        val damping = exp(-DAMPING * delta)
        vx[index] *= damping
        vy[index] *= damping
        if (!active) return
        val dx = x - cursorX
        val dy = y - cursorY
        val distance = sqrt(dx * dx + dy * dy)
        val radius = RADIUS * scale
        if (distance >= radius) return
        val proximity = 1f - distance / radius
        // Exact integral of a constant acceleration under exponential damping over this tick.
        val impulse = ACCELERATION * scale * proximity * sqrt(proximity) * (1f - damping) / DAMPING
        // A particle exactly under the pointer has no direction; spread those by index (golden angle) instead.
        val directionX = if (distance > 0.001f) dx / distance else cos(index * 2.399963f)
        val directionY = if (distance > 0.001f) dy / distance else sin(index * 2.399963f)
        vx[index] += directionX * impulse
        vy[index] += directionY * impulse
        val velocity = sqrt(vx[index] * vx[index] + vy[index] * vy[index])
        val maximum = MAX_SPEED * scale
        if (velocity > maximum) {
            vx[index] *= maximum / velocity
            vy[index] *= maximum / velocity
        }
    }

    fun velocityX(index: Int): Float = vx[index]
    fun velocityY(index: Int): Float = vy[index]

    companion object {
        /** Reach of the pointer in user pixels. */
        const val RADIUS: Float = 180f

        /**
         * Escape speed cap in px/s: clearly above every atmosphere's own motion, yet low enough that a fleeing particle
         * moves only a few pixels per frame at the 20–30 FPS animation rate. Faster escapes jumped by 10+ px per frame,
         * which the eye reads as a fading trail (stroboscopic after shadow) behind the particle.
         */
        const val MAX_SPEED: Float = 120f

        /** Peak acceleration in px/s² right under the pointer; the steady state is `ACCELERATION / DAMPING`. */
        private const val ACCELERATION = 600f

        /** Velocity decay rate in 1/s once the pointer is gone (≈ 0.6 s to settle). */
        private const val DAMPING = 5f
    }
}