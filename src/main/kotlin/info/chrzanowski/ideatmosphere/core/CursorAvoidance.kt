package info.chrzanowski.ideatmosphere.core

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Local, damped pointer steering in root-pane user pixels; no input interception or hot-path allocation.
 *
 * [PointerMode.AVOID]: particles within [RADIUS] of the pointer are accelerated straight away from it; the push grows
 * smoothly from nothing at the edge of the radius to a clear escape (capped at [MAX_SPEED]) right under the cursor.
 *
 * [PointerMode.ATTRACT]: the same reach, inverted. The pull is strongest just outside [STOP_RADIUS] and vanishes
 * inside it, where the extra velocity is damped hard, so particles gather and hover around the pointer instead of
 * piling up on top of it.
 *
 * In either mode the extra velocity decays with [DAMPING] once the pointer has moved on, so particles glide back to
 * their own motion. A [burst] adds an outward puff around a point (a mouse click) regardless of the mode and fades
 * the same way.
 */
internal class CursorAvoidance(capacity: Int) {
    private val vx = FloatArray(capacity)
    private val vy = FloatArray(capacity)
    private var cursorX = 0f
    private var cursorY = 0f
    private var inside = false

    var mode: PointerMode = PointerMode.AVOID
        set(value) {
            field = value
            if (value == PointerMode.OFF) clear()
        }

    private val active: Boolean get() = inside && mode != PointerMode.OFF

    fun setPointer(x: Float, y: Float, inside: Boolean) {
        this.inside = inside && x.isFinite() && y.isFinite()
        cursorX = x
        cursorY = y
    }

    fun reset(index: Int) {
        vx[index] = 0f
        vy[index] = 0f
    }

    fun clear() {
        inside = false
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
        // A particle exactly under the pointer has no direction; spread those by index (golden angle) instead.
        val awayX = if (distance > 0.001f) dx / distance else cos(index * 2.399963f)
        val awayY = if (distance > 0.001f) dy / distance else sin(index * 2.399963f)
        if (mode == PointerMode.ATTRACT) {
            val stop = STOP_RADIUS * scale
            if (distance <= stop) {
                // Soft stop: no pull, and whatever speed is left bleeds off quickly so the particle hovers.
                val hold = exp(-STOP_DAMPING * delta)
                vx[index] *= hold
                vy[index] *= hold
                return
            }
            val reach = (distance - stop) / (radius - stop)
            val pull = 1f - reach
            val impulse = ACCELERATION * scale * pull * sqrt(pull) * (1f - damping) / DAMPING
            vx[index] -= awayX * impulse
            vy[index] -= awayY * impulse
        } else {
            val proximity = 1f - distance / radius
            // Exact integral of a constant acceleration under exponential damping over this tick.
            val impulse = ACCELERATION * scale * proximity * sqrt(proximity) * (1f - damping) / DAMPING
            vx[index] += awayX * impulse
            vy[index] += awayY * impulse
        }
        clamp(index, MAX_SPEED * scale)
    }

    /**
     * Puffs every particle of `count` within [BURST_RADIUS] of (`centerX`, `centerY`) outward, strongest at the centre.
     * Positions are read from [xs] / [ys]; the puff rides on the damped velocity, so it settles like a steering push.
     */
    fun burst(centerX: Float, centerY: Float, scale: Float, count: Int, xs: FloatArray, ys: FloatArray) {
        if (mode == PointerMode.OFF || !centerX.isFinite() || !centerY.isFinite()) return
        val radius = BURST_RADIUS * scale
        for (i in 0 until count) {
            val dx = xs[i] - centerX
            val dy = ys[i] - centerY
            val distance = sqrt(dx * dx + dy * dy)
            if (distance >= radius) continue
            val proximity = 1f - distance / radius
            val kick = BURST_SPEED * scale * proximity
            val awayX = if (distance > 0.001f) dx / distance else cos(i * 2.399963f)
            val awayY = if (distance > 0.001f) dy / distance else sin(i * 2.399963f)
            vx[i] += awayX * kick
            vy[i] += awayY * kick
            clamp(i, BURST_SPEED * scale)
        }
    }

    private fun clamp(index: Int, maximum: Float) {
        val velocity = sqrt(vx[index] * vx[index] + vy[index] * vy[index])
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

        /** Attract mode: inside this distance from the pointer the pull stops and particles hover. */
        const val STOP_RADIUS: Float = 28f

        /** Reach of a click burst in user pixels. */
        const val BURST_RADIUS: Float = 140f

        /**
         * Escape speed cap in px/s: clearly above every atmosphere's own motion, yet low enough that a fleeing particle
         * moves only a few pixels per frame at the 20–30 FPS animation rate. Faster escapes jumped by 10+ px per frame,
         * which the eye reads as a fading trail (stroboscopic after shadow) behind the particle.
         */
        const val MAX_SPEED: Float = 120f

        /** Peak outward speed of a click burst in px/s; brief, since it decays with [DAMPING]. */
        const val BURST_SPEED: Float = 200f

        /** Peak acceleration in px/s² right under the pointer; the steady state is `ACCELERATION / DAMPING`. */
        private const val ACCELERATION = 600f

        /** Velocity decay rate in 1/s once the pointer is gone (≈ 0.6 s to settle). */
        private const val DAMPING = 5f

        /** Extra decay rate in 1/s inside [STOP_RADIUS] while attracting. */
        private const val STOP_DAMPING = 12f
    }
}
