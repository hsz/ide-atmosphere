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
 * their own motion. A [burst] adds an outward puff around a point (a mouse click) regardless of the mode, a [gust]
 * a directional push around a point (a scroll), and both fade the same way.
 *
 * An optional [setAnchor] (the editor caret) is a second, smaller attractor: within [ANCHOR_RADIUS] it uses the
 * [PointerMode.ATTRACT] steering with the same soft stop, at [ANCHOR_STRENGTH] of the pointer's pull, whatever the
 * pointer mode is, so a few particles gather near where the user types. Everything is ignored in [PointerMode.OFF].
 */
internal class CursorAvoidance(capacity: Int) {
    private val vx = FloatArray(capacity)
    private val vy = FloatArray(capacity)
    private var cursorX = 0f
    private var cursorY = 0f
    private var inside = false
    private var anchorX = 0f
    private var anchorY = 0f
    private var anchorActive = false

    var mode: PointerMode = PointerMode.AVOID
        set(value) {
            field = value
            if (value == PointerMode.OFF) clear()
        }

    /** `true` while an anchor is set and the pointer mode allows steering. */
    val isAnchored: Boolean get() = anchorActive && mode != PointerMode.OFF

    fun setPointer(x: Float, y: Float, inside: Boolean) {
        this.inside = inside && x.isFinite() && y.isFinite()
        cursorX = x
        cursorY = y
    }

    /** Places (or, with `active = false`, removes) the anchor at root-pane coordinates (`x`, `y`). */
    fun setAnchor(x: Float, y: Float, active: Boolean) {
        anchorActive = active && x.isFinite() && y.isFinite()
        anchorX = x
        anchorY = y
    }

    fun reset(index: Int) {
        vx[index] = 0f
        vy[index] = 0f
    }

    fun clear() {
        inside = false
        anchorActive = false
        vx.fill(0f)
        vy.fill(0f)
    }

    fun advance(index: Int, x: Float, y: Float, delta: Float, scale: Float) {
        val damping = exp(-DAMPING * delta)
        vx[index] *= damping
        vy[index] *= damping
        if (mode == PointerMode.OFF) return
        var steered = false
        if (inside) {
            val dx = x - cursorX
            val dy = y - cursorY
            val distance = sqrt(dx * dx + dy * dy)
            val radius = RADIUS * scale
            if (distance < radius) {
                steered = true
                // A particle exactly under the pointer has no direction; spread those by index (golden angle) instead.
                val awayX = if (distance > 0.001f) dx / distance else cos(index * 2.399963f)
                val awayY = if (distance > 0.001f) dy / distance else sin(index * 2.399963f)
                if (mode == PointerMode.ATTRACT) {
                    attract(index, awayX, awayY, distance, radius, delta, damping, scale, 1f)
                } else {
                    val proximity = 1f - distance / radius
                    // Exact integral of a constant acceleration under exponential damping over this tick.
                    val impulse = ACCELERATION * scale * proximity * sqrt(proximity) * (1f - damping) / DAMPING
                    vx[index] += awayX * impulse
                    vy[index] += awayY * impulse
                }
            }
        }
        if (anchorActive) {
            val dx = x - anchorX
            val dy = y - anchorY
            val distance = sqrt(dx * dx + dy * dy)
            val radius = ANCHOR_RADIUS * scale
            if (distance < radius) {
                steered = true
                val awayX = if (distance > 0.001f) dx / distance else cos(index * 2.399963f)
                val awayY = if (distance > 0.001f) dy / distance else sin(index * 2.399963f)
                attract(index, awayX, awayY, distance, radius, delta, damping, scale, ANCHOR_STRENGTH)
            }
        }
        if (steered) clamp(index, MAX_SPEED * scale)
    }

    /**
     * Pulls particle [index] towards an attractor it is [distance] away from, along the unit vector pointing *away*
     * from it; the pull is strongest just outside [STOP_RADIUS], vanishes at [radius], and inside the stop radius
     * the remaining speed bleeds off quickly so the particle hovers.
     */
    private fun attract(
        index: Int,
        awayX: Float,
        awayY: Float,
        distance: Float,
        radius: Float,
        delta: Float,
        damping: Float,
        scale: Float,
        strength: Float,
    ) {
        val stop = STOP_RADIUS * scale
        if (distance <= stop) {
            val hold = exp(-STOP_DAMPING * delta)
            vx[index] *= hold
            vy[index] *= hold
            return
        }
        val reach = (distance - stop) / (radius - stop)
        val pull = 1f - reach
        val impulse = ACCELERATION * strength * scale * pull * sqrt(pull) * (1f - damping) / DAMPING
        vx[index] -= awayX * impulse
        vy[index] -= awayY * impulse
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

    /**
     * Pushes every particle of `count` within [GUST_RADIUS] of (`centerX`, `centerY`) along the direction
     * (`dirX`, `dirY`) (normalised here), strongest at the centre, with `strength` in `0..1` scaling the peak
     * [GUST_SPEED]. Positions are read from [xs] / [ys]; like a [burst], the gust rides on the damped velocity.
     */
    fun gust(
        centerX: Float,
        centerY: Float,
        dirX: Float,
        dirY: Float,
        strength: Float,
        scale: Float,
        count: Int,
        xs: FloatArray,
        ys: FloatArray,
    ) {
        if (mode == PointerMode.OFF || !centerX.isFinite() || !centerY.isFinite() || !strength.isFinite()) return
        val length = sqrt(dirX * dirX + dirY * dirY)
        if (!(length > 0.001f) || !length.isFinite()) return
        val peak = GUST_SPEED * scale * strength.coerceIn(0f, 1f)
        if (peak <= 0f) return
        val ux = dirX / length
        val uy = dirY / length
        val radius = GUST_RADIUS * scale
        for (i in 0 until count) {
            val dx = xs[i] - centerX
            val dy = ys[i] - centerY
            val distance = sqrt(dx * dx + dy * dy)
            if (distance >= radius) continue
            val proximity = 1f - distance / radius
            val kick = peak * proximity
            vx[i] += ux * kick
            vy[i] += uy * kick
            clamp(i, GUST_SPEED * scale)
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

        /** Reach of a scroll gust in user pixels; wider than a burst, since a scroll moves a whole editor. */
        const val GUST_RADIUS: Float = 220f

        /** Reach of the caret anchor in user pixels: a small gathering, not a second pointer. */
        const val ANCHOR_RADIUS: Float = 90f

        /** Pull of the caret anchor relative to the pointer's attract pull. */
        const val ANCHOR_STRENGTH: Float = 0.6f

        /**
         * Escape speed cap in px/s: clearly above every atmosphere's own motion, yet low enough that a fleeing particle
         * moves only a few pixels per frame at the 20–30 FPS animation rate. Faster escapes jumped by 10+ px per frame,
         * which the eye reads as a fading trail (stroboscopic after shadow) behind the particle.
         */
        const val MAX_SPEED: Float = 120f

        /** Peak outward speed of a click burst in px/s; brief, since it decays with [DAMPING]. */
        const val BURST_SPEED: Float = 200f

        /** Peak speed of a full-strength scroll gust in px/s; gentler than a burst because scrolls come in streams. */
        const val GUST_SPEED: Float = 140f

        /** Peak acceleration in px/s² right under the pointer; the steady state is `ACCELERATION / DAMPING`. */
        private const val ACCELERATION = 600f

        /** Velocity decay rate in 1/s once the pointer is gone (≈ 0.6 s to settle). */
        private const val DAMPING = 5f

        /** Extra decay rate in 1/s inside [STOP_RADIUS] while attracting. */
        private const val STOP_DAMPING = 12f
    }
}
