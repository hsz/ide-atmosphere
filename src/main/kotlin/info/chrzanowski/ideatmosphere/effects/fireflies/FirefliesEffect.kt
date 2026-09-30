package info.chrzanowski.ideatmosphere.effects.fireflies

import info.chrzanowski.ideatmosphere.core.AtmosphereEffect
import info.chrzanowski.ideatmosphere.core.AtmosphereEvent
import info.chrzanowski.ideatmosphere.core.AtmospherePalette
import info.chrzanowski.ideatmosphere.core.EffectContext
import info.chrzanowski.ideatmosphere.render.DirtyRegionCollector
import java.awt.Color
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.geom.Ellipse2D
import java.util.SplittableRandom
import org.jetbrains.annotations.TestOnly
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Slow, low-opacity fireflies drifting across the frame.
 *
 * Particles are stored as structure-of-arrays so [update] and [paint] never allocate: the only shape is a
 * single reused [Ellipse2D.Float], and colors are looked up in [ALPHA_STEPS]-entry alpha tables that are
 * rebuilt only in [onPaletteChanged] (and [start]).
 *
 * Rendering is **continuous**: every firefly is filled at its exact float centre with float radii, so
 * motion is limited only by the frame rate, never by pixel snapping. This relies on the caller enabling
 * antialiasing *and* `RenderingHints.VALUE_STROKE_PURE` (which `AtmospherePainter` does through
 * `GraphicsUtil.setupAAPainting`); with `STROKE_NORMALIZE` the geometry would be snapped back to whole
 * pixels. Dirty reporting is continuous as well: a particle is reported as soon as it has moved by at least
 * [MOVE_EPSILON] px or its 8-bit alpha step changed, so on a normal tick almost every firefly is dirty,
 * while a negligible tick (e.g. a 0.1 ms timer hiccup) reports (almost) nothing.
 *
 * Invariant: `prevX/prevY/prevAlpha` are the **on-screen** state. [paint] draws from them and they advance
 * only when a dirty region covering both the old and the new position (plus per-particle halo padding) has
 * been emitted, so the screen is always consistent with what was reported.
 *
 * Coordinates are root-pane user-space pixels; sizes are scaled by [EffectContext.uiScale].
 */
class FirefliesEffect : AtmosphereEffect {

    override val id: String get() = ID

    private lateinit var random: SplittableRandom
    private var uiScale = 1f
    private var palette: AtmospherePalette = AtmospherePalette.DARK

    private val x = FloatArray(MAX_PARTICLES)
    private val y = FloatArray(MAX_PARTICLES)
    private val vx = FloatArray(MAX_PARTICLES)
    private val vy = FloatArray(MAX_PARTICLES)
    private val speed = FloatArray(MAX_PARTICLES)
    private val heading = FloatArray(MAX_PARTICLES)
    private val wanderPhase = FloatArray(MAX_PARTICLES)
    private val wanderRate = FloatArray(MAX_PARTICLES)
    private val bobPhase = FloatArray(MAX_PARTICLES)
    private val size = FloatArray(MAX_PARTICLES)
    private val alpha = FloatArray(MAX_PARTICLES)
    private val baseAlpha = FloatArray(MAX_PARTICLES)
    private val phase = FloatArray(MAX_PARTICLES)
    private val pulseRate = FloatArray(MAX_PARTICLES)
    private val life = FloatArray(MAX_PARTICLES)
    private val maxLife = FloatArray(MAX_PARTICLES)

    // Last *painted* state. `paint` draws from these so the screen always matches what dirty rects
    // covered; they advance only when a region is emitted, so sub-threshold motion accumulates.
    private val prevX = FloatArray(MAX_PARTICLES)
    private val prevY = FloatArray(MAX_PARTICLES)
    private val prevAlpha = FloatArray(MAX_PARTICLES)

    private val coreColors = arrayOfNulls<Color>(ALPHA_STEPS)
    private val haloColors = arrayOfNulls<Color>(ALPHA_STEPS)

    // Single reused shape for sub-pixel filling; mutated in `paint` only (EDT).
    private val ellipse = Ellipse2D.Float()

    /** Number of live particles for the current bounds. */
    var particleCount: Int = 0
        private set

    /** Current smoothed speed multiplier (interpolates toward [speedTarget]). */
    var speedMultiplier: Float = 1f
        private set

    /** Speed multiplier the simulation is converging to (driven by [onEvent]). */
    var speedTarget: Float = 1f
        private set

    /** Extra brightness 0..1 added after [AtmosphereEvent.SUCCESS]; decays over [GLOW_SECONDS]. */
    var glow: Float = 0f
        private set

    private var started = false
    private var lastWidth = -1
    private var lastHeight = -1

    override fun start(context: EffectContext) {
        random = context.random
        uiScale = context.uiScale
        palette = context.palette
        rebuildColorTables()
        particleCount = 0
        lastWidth = -1
        lastHeight = -1
        speedMultiplier = 1f
        speedTarget = 1f
        glow = 0f
        started = true
    }

    override fun stop() {
        started = false
        particleCount = 0
        lastWidth = -1
        lastHeight = -1
    }

    override fun onPaletteChanged(palette: AtmospherePalette) {
        this.palette = palette
        rebuildColorTables()
    }

    override fun onEvent(event: AtmosphereEvent) {
        when (event) {
            AtmosphereEvent.USER_IDLE -> speedTarget = IDLE_SPEED
            AtmosphereEvent.USER_ACTIVE -> speedTarget = NORMAL_SPEED
            AtmosphereEvent.ACTIVITY_STARTED -> speedTarget = ACTIVITY_SPEED
            AtmosphereEvent.ACTIVITY_FINISHED -> speedTarget = NORMAL_SPEED
            AtmosphereEvent.SUCCESS -> glow = 1f
            AtmosphereEvent.FAILURE -> scatter()
        }
    }

    override fun update(deltaSeconds: Float, bounds: Rectangle, dirty: DirtyRegionCollector): Boolean {
        if (!started || deltaSeconds <= 0f || bounds.width <= 0 || bounds.height <= 0) return false
        val bx = bounds.x.toFloat()
        val by = bounds.y.toFloat()
        val bw = bounds.width.toFloat()
        val bh = bounds.height.toFloat()

        var changed = false
        if (bounds.width != lastWidth || bounds.height != lastHeight) {
            changed = resize(bounds, dirty)
        }

        // Smooth global modifiers.
        val blend = min(1f, deltaSeconds * SPEED_BLEND_RATE)
        speedMultiplier += (speedTarget - speedMultiplier) * blend
        if (abs(speedTarget - speedMultiplier) < 0.001f) speedMultiplier = speedTarget
        if (glow > 0f) glow = max(0f, glow - deltaSeconds / GLOW_SECONDS)
        val glowBoost = 1f + glow * GLOW_ALPHA_BOOST
        val opacityScale = palette.opacityScale

        for (i in 0 until particleCount) {
            life[i] += deltaSeconds
            if (life[i] >= maxLife[i]) {
                val padding = haloPadding(i)
                spawn(i, bx, by, bw, bh, awayFromEdges = true)
                changed = emitVanish(dirty, i, padding) or changed
                continue
            }

            // Heading wander + damping of any impulse back toward the cruise speed.
            wanderPhase[i] += wanderRate[i] * deltaSeconds
            heading[i] += sin(wanderPhase[i]) * WANDER_TURN_RATE * deltaSeconds
            val cruise = speed[i] * speedMultiplier
            val cx = cos(heading[i]) * cruise
            val cy = sin(heading[i]) * cruise
            val damp = min(1f, deltaSeconds * IMPULSE_DAMPING)
            vx[i] += (cx - vx[i]) * damp
            vy[i] += (cy - vy[i]) * damp
            bobPhase[i] += BOB_RATE * deltaSeconds
            val bob = cos(bobPhase[i]) * BOB_AMPLITUDE * uiScale

            x[i] += vx[i] * deltaSeconds
            y[i] += (vy[i] + bob) * deltaSeconds

            if (x[i] < bx || x[i] >= bx + bw || y[i] < by || y[i] >= by + bh) {
                val padding = haloPadding(i)
                spawn(i, bx, by, bw, bh, awayFromEdges = true)
                changed = emitVanish(dirty, i, padding) or changed
                continue
            }

            // Opacity: base × pulse × life envelope × glow.
            phase[i] += pulseRate[i] * deltaSeconds
            val pulse = 0.65f + 0.35f * sin(phase[i])
            val envelope = lifeEnvelope(life[i], maxLife[i])
            alpha[i] = min(1f, baseAlpha[i] * pulse * envelope * opacityScale * glowBoost)

            val moved = abs(x[i] - prevX[i]) >= MOVE_EPSILON || abs(y[i] - prevY[i]) >= MOVE_EPSILON
            val faded = alphaIndex(alpha[i]) != alphaIndex(prevAlpha[i])
            if (moved || faded) {
                changed = emitDirty(dirty, i, haloPadding(i)) or changed
            }
        }
        return changed
    }

    override fun paint(g: Graphics2D, clip: Rectangle) {
        if (!started) return
        val cx0 = clip.x
        val cy0 = clip.y
        val cx1 = clip.x + clip.width
        val cy1 = clip.y + clip.height
        for (i in 0 until particleCount) {
            val a = prevAlpha[i]
            if (a <= 0f) continue
            val idx = alphaIndex(a)
            if (idx == 0) continue
            val r = max(MIN_CORE_RADIUS, size[i])
            val hr = size[i] * HALO_RATIO
            val px = prevX[i]
            val py = prevY[i]
            // Cheap AABB test against the clip using the halo radius.
            if (px + hr < cx0 || px - hr > cx1 || py + hr < cy0 || py - hr > cy1) continue

            // Float geometry: with STROKE_PURE the fill is antialiased at the exact sub-pixel centre.
            ellipse.setFrame(px - hr, py - hr, hr * 2f, hr * 2f)
            g.color = haloColors[idx]
            g.fill(ellipse)

            ellipse.setFrame(px - r, py - r, r * 2f, r * 2f)
            g.color = coreColors[idx]
            g.fill(ellipse)
        }
    }

    /** Whether every live particle lies inside [bounds]; test helper. */
    fun allInside(bounds: Rectangle): Boolean {
        for (i in 0 until particleCount) {
            if (x[i] < bounds.x || x[i] >= bounds.x + bounds.width ||
                y[i] < bounds.y || y[i] >= bounds.y + bounds.height
            ) return false
        }
        return true
    }

    /**
     * Places particle [index] at ([px], [py]) with the given on-screen [alpha] and commits it as painted state,
     * so the next [paint] draws exactly one firefly there. Test seam for sub-pixel rendering checks.
     */
    @TestOnly
    fun placeParticleForTest(index: Int, px: Float, py: Float, alpha: Float) {
        require(index in 0 until particleCount) { "index $index out of 0 until $particleCount" }
        x[index] = px
        y[index] = py
        this.alpha[index] = alpha
        prevX[index] = px
        prevY[index] = py
        prevAlpha[index] = alpha
    }

    /** Number of distinct, fully populated entries in each cached color table; test seam. */
    @TestOnly
    fun colorTableSizeForTest(): Int = min(coreColors.count { it != null }, haloColors.count { it != null })

    /** Root-mean-square particle velocity in px/s; test helper. */
    fun meanSpeed(): Float {
        if (particleCount == 0) return 0f
        var sum = 0f
        for (i in 0 until particleCount) sum += sqrt(vx[i] * vx[i] + vy[i] * vy[i])
        return sum / particleCount
    }

    /** Adjusts the particle count to [bounds]; returns `true` if a region was reported for vanished particles. */
    private fun resize(bounds: Rectangle, dirty: DirtyRegionCollector): Boolean {
        val bx = bounds.x.toFloat()
        val by = bounds.y.toFloat()
        val bw = bounds.width.toFloat()
        val bh = bounds.height.toFloat()
        val target = particleCountFor(bounds.width, bounds.height)
        val previous = particleCount
        lastWidth = bounds.width
        lastHeight = bounds.height
        particleCount = target
        for (i in previous until target) {
            spawn(i, bx, by, bw, bh, awayFromEdges = false)
            // Distribute life so newly created particles do not all pulse in sync.
            life[i] = random.nextFloat() * maxLife[i]
            prevX[i] = x[i]
            prevY[i] = y[i]
            prevAlpha[i] = 0f
        }
        // Shrinking: particles beyond the new count vanish; their last painted area must be cleared.
        var changed = false
        for (i in target until previous) {
            changed = emitVanish(dirty, i, haloPadding(i)) or changed
        }
        // Particles left outside the new bounds are respawned by the update pass that follows.
        return changed
    }

    private fun spawn(i: Int, bx: Float, by: Float, bw: Float, bh: Float, awayFromEdges: Boolean) {
        val margin = if (awayFromEdges) min(EDGE_MARGIN * uiScale, min(bw, bh) * 0.25f) else 0f
        x[i] = bx + margin + random.nextFloat() * max(1f, bw - 2f * margin)
        y[i] = by + margin + random.nextFloat() * max(1f, bh - 2f * margin)
        speed[i] = (MIN_SPEED + random.nextFloat() * (MAX_SPEED - MIN_SPEED)) * uiScale
        heading[i] = random.nextFloat() * TWO_PI
        vx[i] = cos(heading[i]) * speed[i] * speedMultiplier
        vy[i] = sin(heading[i]) * speed[i] * speedMultiplier
        wanderPhase[i] = random.nextFloat() * TWO_PI
        wanderRate[i] = 0.3f + random.nextFloat() * 0.5f
        bobPhase[i] = random.nextFloat() * TWO_PI
        size[i] = (MIN_SIZE + random.nextFloat() * (MAX_SIZE - MIN_SIZE)) * uiScale
        baseAlpha[i] = MIN_BASE_ALPHA + random.nextFloat() * (MAX_BASE_ALPHA - MIN_BASE_ALPHA)
        phase[i] = random.nextFloat() * TWO_PI
        pulseRate[i] = MIN_PULSE_RATE + random.nextFloat() * (MAX_PULSE_RATE - MIN_PULSE_RATE)
        life[i] = 0f
        maxLife[i] = MIN_LIFE + random.nextFloat() * (MAX_LIFE - MIN_LIFE)
        alpha[i] = 0f
    }

    private fun scatter() {
        for (i in 0 until particleCount) {
            val angle = random.nextFloat() * TWO_PI
            val impulse = (SCATTER_IMPULSE_MIN + random.nextFloat() * (SCATTER_IMPULSE_MAX - SCATTER_IMPULSE_MIN)) * uiScale
            vx[i] += cos(angle) * impulse
            vy[i] += sin(angle) * impulse
        }
    }

    /**
     * Reports `prev ∪ new` bounds (+ halo padding) and commits the new state as painted.
     * Returns `false` when the particle was invisible before and after (nothing on screen to refresh).
     */
    private fun emitDirty(dirty: DirtyRegionCollector, i: Int, padding: Float): Boolean {
        if (prevAlpha[i] <= 0f && alpha[i] <= 0f) {
            prevX[i] = x[i]
            prevY[i] = y[i]
            return false
        }
        val minX = min(prevX[i], x[i])
        val minY = min(prevY[i], y[i])
        val maxX = max(prevX[i], x[i])
        val maxY = max(prevY[i], y[i])
        dirty.add(minX, minY, maxX - minX, maxY - minY, padding)
        prevX[i] = x[i]
        prevY[i] = y[i]
        prevAlpha[i] = alpha[i]
        return true
    }

    /**
     * A (re)spawned particle starts invisible: only its previously painted spot needs clearing.
     * Returns `false` when nothing was on screen.
     */
    private fun emitVanish(dirty: DirtyRegionCollector, i: Int, padding: Float): Boolean {
        val visible = prevAlpha[i] > 0f
        if (visible) dirty.add(prevX[i], prevY[i], 0f, 0f, padding)
        prevX[i] = x[i]
        prevY[i] = y[i]
        prevAlpha[i] = 0f
        return visible
    }

    /** Per-particle padding covering the halo radius plus one pixel of antialiasing fringe. */
    private fun haloPadding(i: Int): Float = max(MIN_CORE_RADIUS, size[i]) * HALO_RATIO + 1f

    // Plain AWT colors on purpose: theme awareness comes from the palette, and JBColor would re-resolve
    // the LaF on every access inside the paint loop.
    @Suppress("UseJBColor")
    private fun rebuildColorTables() {
        val c = palette.particleColor
        coreColors[0] = TRANSPARENT
        haloColors[0] = TRANSPARENT
        for (i in 1 until ALPHA_STEPS) {
            val a = i / (ALPHA_STEPS - 1).toFloat()
            coreColors[i] = Color(c.red, c.green, c.blue, (a * 255f + 0.5f).toInt().coerceIn(0, 255))
            haloColors[i] = Color(c.red, c.green, c.blue, (a * HALO_ALPHA_RATIO * 255f + 0.5f).toInt().coerceIn(0, 255))
        }
    }

    companion object {
        const val ID: String = "fireflies"

        const val MIN_PARTICLES: Int = 20
        const val MAX_PARTICLES: Int = 50

        /**
         * User-space pixels per firefly (≈ 24 on a 1512×949 frame). Raised from the initial 45 000 after the
         * first sandbox measurements: repaint cost grows with the number of moving particles, and two dozen
         * fireflies already read as "a field" at this opacity.
         */
        const val AREA_PER_PARTICLE: Int = 60_000

        const val MIN_SPEED: Float = 6f
        const val MAX_SPEED: Float = 18f
        const val MIN_SIZE: Float = 1.5f
        const val MAX_SIZE: Float = 3f
        const val MIN_LIFE: Float = 8f
        const val MAX_LIFE: Float = 20f
        const val FADE_IN_SECONDS: Float = 1.5f
        const val FADE_OUT_SECONDS: Float = 2f

        const val IDLE_SPEED: Float = 0.5f
        const val NORMAL_SPEED: Float = 1f
        const val ACTIVITY_SPEED: Float = 1.4f
        const val GLOW_SECONDS: Float = 1.5f

        /** Alpha pulse ("breathing") angular rate in rad/s. */
        const val MIN_PULSE_RATE: Float = 0.5f
        const val MAX_PULSE_RATE: Float = 1.2f

        /**
         * Number of alpha steps in the cached color tables (8-bit resolution, so the pulse never steps
         * visibly); also the alpha resolution of dirty reporting.
         */
        const val ALPHA_STEPS: Int = 256

        /** Minimum displacement (user px) that counts as a visible move and triggers a dirty region. */
        const val MOVE_EPSILON: Float = 0.02f

        /** Halo radius as a multiple of the core radius. */
        const val HALO_RATIO: Float = 3f
        private const val TWO_PI = (Math.PI * 2).toFloat()
        private const val MIN_CORE_RADIUS = 0.5f
        private const val HALO_ALPHA_RATIO = 0.18f
        private const val MIN_BASE_ALPHA = 0.18f
        private const val MAX_BASE_ALPHA = 0.35f
        private const val GLOW_ALPHA_BOOST = 0.6f
        private const val EDGE_MARGIN = 40f
        private const val WANDER_TURN_RATE = 1.2f
        private const val BOB_RATE = 1.5f
        private const val BOB_AMPLITUDE = 3f
        private const val SPEED_BLEND_RATE = 1.5f
        private const val IMPULSE_DAMPING = 1.2f
        private const val SCATTER_IMPULSE_MIN = 30f
        private const val SCATTER_IMPULSE_MAX = 60f
        @Suppress("UseJBColor")
        private val TRANSPARENT = Color(0, 0, 0, 0)

        /** `clamp(area / AREA_PER_PARTICLE, 20, 50)`. */
        @JvmStatic
        fun particleCountFor(width: Int, height: Int): Int =
            (width.toLong() * height / AREA_PER_PARTICLE).toInt().coerceIn(MIN_PARTICLES, MAX_PARTICLES)

        private fun lifeEnvelope(life: Float, maxLife: Float): Float {
            val fadeIn = min(1f, life / FADE_IN_SECONDS)
            val fadeOut = min(1f, (maxLife - life) / FADE_OUT_SECONDS)
            return max(0f, min(fadeIn, fadeOut))
        }

        private fun alphaIndex(a: Float): Int = (a * (ALPHA_STEPS - 1) + 0.5f).toInt().coerceIn(0, ALPHA_STEPS - 1)
    }
}
