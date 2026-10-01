package info.chrzanowski.ideatmosphere.effects.snow

import info.chrzanowski.ideatmosphere.core.AdsrEnvelope
import info.chrzanowski.ideatmosphere.core.AtmosphereEffect
import info.chrzanowski.ideatmosphere.core.AtmosphereEvent
import info.chrzanowski.ideatmosphere.core.AtmospherePalette
import info.chrzanowski.ideatmosphere.core.CommitPolicy
import info.chrzanowski.ideatmosphere.core.CursorAvoidance
import info.chrzanowski.ideatmosphere.core.EffectContext
import info.chrzanowski.ideatmosphere.core.PointerMode
import info.chrzanowski.ideatmosphere.render.DirtyRegionCollector
import java.awt.Color
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.geom.Ellipse2D
import java.util.SplittableRandom
import org.jetbrains.annotations.TestOnly
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Calm, depth-layered snowfall.
 *
 * Every flake carries a random depth in `0..1`: near flakes (depth → 1) are larger, brighter and fall faster;
 * far flakes are tiny, faint, slow and twinkle gently, so the field reads as a volume rather than a flat
 * sprinkle. All flakes ride the same slow, sinusoidal breeze with depth-dependent parallax ([depthFactor]).
 *
 * Reactions are motion-only and never touch the flake color:
 * - [AtmosphereEvent.USER_IDLE] / [AtmosphereEvent.ACTIVITY_STARTED] ease the speed multiplier to
 *   [IDLE_SPEED] / [ACTIVITY_SPEED]; [AtmosphereEvent.USER_ACTIVE] / [AtmosphereEvent.ACTIVITY_FINISHED]
 *   release it back to [NORMAL_SPEED];
 * - [AtmosphereEvent.SUCCESS] is a "warm updraft": a one-shot envelope ([UPDRAFT_ATTACK_SECONDS] +
 *   [UPDRAFT_DECAY_SECONDS]) that cancels the fall, lifts the flakes by up to [UPDRAFT_SPEED] and widens their sway;
 * - [AtmosphereEvent.FAILURE] is a sideways gust of up to [GUST_SPEED] in one direction — chosen when the gust
 *   starts and kept while it is active — that also dims the flakes by up to [FAILURE_DIM].
 *
 * Particles are stored as structure-of-arrays so [update] and [paint] never allocate: the only shape is a
 * single reused [Ellipse2D.Float] and colors come from an [ALPHA_STEPS]-entry alpha table rebuilt only in
 * [start] and [onPaletteChanged]. Rendering is continuous (float centre and radius, antialiased with
 * `STROKE_PURE` by the caller). Dirty reporting is adaptive ([CommitPolicy]): the simulation runs on every tick,
 * but a flake is *committed* (reported and repainted) only once its on-screen state would visibly change — it
 * moved by [CommitPolicy.MOVE_THRESHOLD] px, its alpha changed by [CommitPolicy.ALPHA_STEP_THRESHOLD] of
 * [ALPHA_STEPS] steps, or it has been stale for [CommitPolicy.MAX_STALE_TICKS] ticks. Slow, far flakes therefore
 * skip ticks while fast ones are repainted on every tick.
 *
 * Invariant (shared with `FirefliesEffect`): `prevX/prevY/prevAlpha` are the **on-screen** state. [paint]
 * draws from them and they advance only when a region covering both the old and the new flake (padded by
 * `radius + 1` px of antialiasing fringe) has been reported, so the screen always matches what was reported.
 *
 * Flakes (re)spawn in a [SPAWN_BAND] above the top edge and fade in over [FADE_IN_SECONDS], so they never
 * pop; the initial fill distributes them over the whole area instead. A flake respawns as soon as it has left
 * the bounds through the bottom or a side (there is no lifetime). Coordinates are root-pane user-space
 * pixels; sizes and speeds are scaled by [EffectContext.uiScale].
 */
class SnowEffect : AtmosphereEffect {

    override val id: String get() = ID

    private var random: SplittableRandom = SplittableRandom()
    private var uiScale = 1f
    private var palette: AtmospherePalette = AtmospherePalette.DARK

    private var intensity: Float = 1f
    private var speedScale: Float = 1f
    /** Typing-flow gain on top of [speedScale]: `1` at rest, up to `1 + FLOW_SPEED_BOOST` while typing fast. */
    private var flowBoost: Float = 1f
    private var populationChanged = false

    private val x = FloatArray(MAX_PARTICLES)
    private val y = FloatArray(MAX_PARTICLES)
    private val vx = FloatArray(MAX_PARTICLES) // Velocity applied on the last update, px/s.
    private val vy = FloatArray(MAX_PARTICLES)
    private val depth = FloatArray(MAX_PARTICLES) // 0 = far, 1 = near.
    private val radius = FloatArray(MAX_PARTICLES)
    private val fallSpeed = FloatArray(MAX_PARTICLES)
    private val swayPhase = FloatArray(MAX_PARTICLES)
    private val swayRate = FloatArray(MAX_PARTICLES)
    private val swayAmplitude = FloatArray(MAX_PARTICLES)
    private val twinklePhase = FloatArray(MAX_PARTICLES)
    private val twinkleRate = FloatArray(MAX_PARTICLES)
    private val baseAlpha = FloatArray(MAX_PARTICLES)
    private val fadeIn = FloatArray(MAX_PARTICLES) // 0..1 progress since the last (re)spawn.
    private val alpha = FloatArray(MAX_PARTICLES)

    // Last *painted* state. `paint` draws from these so the screen always matches what dirty rects
    // covered; they advance only when a region is emitted, so sub-threshold motion accumulates.
    private val prevX = FloatArray(MAX_PARTICLES)
    private val prevY = FloatArray(MAX_PARTICLES)
    private val prevAlpha = FloatArray(MAX_PARTICLES)

    // Ticks since the flake was last committed (reported dirty); bounds how long motion may stay un-painted.
    private val staleTicks = IntArray(MAX_PARTICLES)

    private val colors = arrayOfNulls<Color>(ALPHA_STEPS)
    private var colorTableRebuilds = 0
    private val avoidance = CursorAvoidance(MAX_PARTICLES)

    // Single reused shape for sub-pixel filling; mutated in `paint` only (EDT).
    private val ellipse = Ellipse2D.Float()

    // Phase of the shared breeze and the direction (±1) of the current failure gust.
    private var windPhase = 0f
    private var gustDirection = 1f

    /** Number of live flakes for the current bounds. */
    var particleCount: Int = 0
        private set

    /** Current smoothed speed multiplier (driven by [AdsrEnvelope]), including [setSpeedScale]. */
    var speedMultiplier: Float = 1f
        private set

    /** Strength 0..1 of the success updraft (driven by [AdsrEnvelope]); motion only, never a color change. */
    var glow: Float = 0f
        private set

    /** Strength 0..1 of the failure gust and its dimming (driven by [AdsrEnvelope]). */
    var failureGlow: Float = 0f
        private set

    private val speedEnvelope = AdsrEnvelope(
        restLevel = NORMAL_SPEED,
        attackSeconds = 1f,
        decaySeconds = 1.5f,
        sustainRatio = 1f,
        releaseSeconds = 2f,
    )

    private val updraftEnvelope = AdsrEnvelope(
        restLevel = 0f,
        attackSeconds = UPDRAFT_ATTACK_SECONDS,
        decaySeconds = UPDRAFT_DECAY_SECONDS,
        sustainRatio = 0f,
        releaseSeconds = OUTCOME_RELEASE_SECONDS,
        oneShot = true,
    )

    private val gustEnvelope = AdsrEnvelope(
        restLevel = 0f,
        attackSeconds = GUST_ATTACK_SECONDS,
        decaySeconds = GUST_DECAY_SECONDS,
        sustainRatio = 0f,
        releaseSeconds = OUTCOME_RELEASE_SECONDS,
        oneShot = true,
    )

    private var started = false
    private var lastWidth = -1
    private var lastHeight = -1

    override fun start(context: EffectContext) {
        random = context.random
        uiScale = context.uiScale
        palette = context.palette
        rebuildColorTable()
        particleCount = 0
        lastWidth = -1
        lastHeight = -1
        windPhase = random.nextFloat() * TWO_PI
        gustDirection = 1f
        avoidance.clear()
        // Reset envelopes to idle at rest level.
        speedEnvelope.release()
        speedEnvelope.advance(100f)
        updraftEnvelope.release()
        updraftEnvelope.advance(100f)
        gustEnvelope.release()
        gustEnvelope.advance(100f)
        speedMultiplier = speedEnvelope.value * speedScale * flowBoost
        glow = 0f
        failureGlow = 0f
        started = true
    }

    override fun stop() {
        started = false
        particleCount = 0
        lastWidth = -1
        lastHeight = -1
        avoidance.clear()
        updraftEnvelope.release()
        updraftEnvelope.advance(100f)
        gustEnvelope.release()
        gustEnvelope.advance(100f)
        glow = 0f
        failureGlow = 0f
    }

    override fun onPaletteChanged(palette: AtmospherePalette) {
        this.palette = palette
        rebuildColorTable()
    }

    override fun setIntensity(scale: Float) {
        val value = if (scale.isFinite()) scale.coerceIn(0.3f, 2f) else 1f
        if (intensity == value) return
        intensity = value
        populationChanged = true
    }

    override fun setSpeedScale(scale: Float) {
        speedScale = if (scale.isFinite()) scale.coerceIn(0.3f, 2f) else 1f
        speedMultiplier = speedEnvelope.value * speedScale * flowBoost
    }

    override fun setFlow(level: Float) {
        flowBoost = 1f + AtmosphereEffect.FLOW_SPEED_BOOST * (if (level.isFinite()) level.coerceIn(0f, 1f) else 0f)
        speedMultiplier = speedEnvelope.value * speedScale * flowBoost
    }

    override fun onPointerChanged(x: Float, y: Float, inside: Boolean) {
        avoidance.setPointer(x, y, inside)
    }

    override fun onPointerPressed(x: Float, y: Float) {
        avoidance.burst(x, y, uiScale, particleCount, this.x, this.y)
    }

    override fun setPointerMode(mode: PointerMode) {
        avoidance.mode = mode
    }

    override fun onEvent(event: AtmosphereEvent) {
        when (event) {
            AtmosphereEvent.USER_IDLE -> speedEnvelope.trigger(IDLE_SPEED)
            AtmosphereEvent.USER_ACTIVE -> speedEnvelope.release()
            AtmosphereEvent.ACTIVITY_STARTED -> speedEnvelope.trigger(ACTIVITY_SPEED)
            AtmosphereEvent.ACTIVITY_FINISHED -> speedEnvelope.release()
            AtmosphereEvent.SUCCESS -> {
                // The opposite outcome eases out over OUTCOME_RELEASE_SECONDS instead of snapping, so nothing pops.
                gustEnvelope.release()
                updraftEnvelope.trigger(1f)
            }
            AtmosphereEvent.FAILURE -> {
                // One direction per gust: chosen only when no gust is active, kept for retriggers while it lasts.
                if (gustEnvelope.phase == AdsrEnvelope.Phase.IDLE) gustDirection = if (random.nextBoolean()) 1f else -1f
                updraftEnvelope.release()
                gustEnvelope.trigger(1f)
            }
        }
    }

    override fun update(deltaSeconds: Float, bounds: Rectangle, dirty: DirtyRegionCollector): Boolean {
        if (!started || !deltaSeconds.isFinite() || deltaSeconds <= 0f || bounds.width <= 0 || bounds.height <= 0) return false
        val bx0 = bounds.x.toFloat()
        val by0 = bounds.y.toFloat()
        val bw = bounds.width.toFloat()
        val bx1 = bx0 + bw
        val by1 = by0 + bounds.height

        var changed = false
        if (populationChanged || bounds.width != lastWidth || bounds.height != lastHeight) {
            changed = resize(bounds, dirty)
            populationChanged = false
        }

        // Smooth global modifiers.
        speedMultiplier = speedEnvelope.advance(deltaSeconds) * speedScale * flowBoost
        glow = updraftEnvelope.advance(deltaSeconds)
        failureGlow = gustEnvelope.advance(deltaSeconds)

        // Shared breeze: a fundamental plus a phase-shifted second harmonic so it does not swing like a
        // metronome; the sum is 2π-periodic, so wrapping the phase is seamless, and |wind| ≤ WIND_SPEED.
        windPhase += WIND_RATE * deltaSeconds * speedMultiplier
        if (windPhase > TWO_PI) windPhase -= TWO_PI
        val wind = (sin(windPhase) + 0.5f * sin(2f * windPhase + 1f)) * (WIND_SPEED / 1.5f) * uiScale
        val gust = gustDirection * failureGlow * GUST_SPEED * uiScale
        val lift = glow * UPDRAFT_SPEED * uiScale
        val fallScale = 1f - glow
        val swayBoost = 1f + glow * UPDRAFT_SWAY_BOOST
        val alphaScale = intensity * palette.opacityScale
        val dim = 1f - failureGlow * FAILURE_DIM
        val fadeStep = deltaSeconds / FADE_IN_SECONDS
        val topExit = by0 - SPAWN_BAND * uiScale

        for (i in 0 until particleCount) {
            val d = depth[i]
            val parallax = depthFactor(d)

            swayPhase[i] += swayRate[i] * deltaSeconds * speedMultiplier
            if (swayPhase[i] > TWO_PI) swayPhase[i] -= TWO_PI
            val sway = sin(swayPhase[i]) * swayAmplitude[i] * swayBoost

            // Motion: sway + breeze/gust with parallax, fall traded for lift by the updraft, plus cursor steering.
            avoidance.advance(i, x[i], y[i], deltaSeconds, uiScale)
            vx[i] = (sway + (wind + gust) * parallax) * speedMultiplier + avoidance.velocityX(i)
            vy[i] = (fallSpeed[i] * fallScale - lift * (0.6f + 0.4f * d)) * speedMultiplier + avoidance.velocityY(i)
            x[i] += vx[i] * deltaSeconds
            y[i] += vy[i] * deltaSeconds

            // Left through the bottom, a side or (rarely, lifted) above the spawn band: respawn above the top.
            val r = radius[i]
            if (y[i] - r > by1 || x[i] + r < bx0 || x[i] - r > bx1 || y[i] + r < topExit) {
                val padding = r + 1f
                spawn(i, bx0, by0, bw)
                changed = emitVanish(dirty, bounds, i, padding) or changed
                continue
            }

            // Opacity: depth-graded base × fade-in × twinkle (far flakes) × theme × intensity, dimmed by a gust.
            fadeIn[i] = min(1f, fadeIn[i] + fadeStep)
            twinklePhase[i] += twinkleRate[i] * deltaSeconds
            if (twinklePhase[i] > TWO_PI) twinklePhase[i] -= TWO_PI
            val twinkle = 1f - TWINKLE_DEPTH * (1f - d) * (0.5f + 0.5f * sin(twinklePhase[i]))
            alpha[i] = min(1f, baseAlpha[i] * alphaScale * fadeIn[i] * twinkle) * dim

            val alphaDelta = abs(alphaIndex(alpha[i]) - alphaIndex(prevAlpha[i]))
            if (CommitPolicy.shouldCommit(x[i] - prevX[i], y[i] - prevY[i], alphaDelta, staleTicks[i])) {
                changed = emitDirty(dirty, bounds, i, r + 1f) or changed
            } else {
                staleTicks[i]++
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
            val r = radius[i]
            val px = prevX[i]
            val py = prevY[i]
            // Cheap AABB test against the clip, including the antialiasing fringe.
            if (px + r + 1f < cx0 || px - r - 1f > cx1 || py + r + 1f < cy0 || py - r - 1f > cy1) continue

            // Float geometry: with STROKE_PURE the fill is antialiased at the exact sub-pixel centre.
            ellipse.setFrame(px - r, py - r, r * 2f, r * 2f)
            g.color = colors[idx]
            g.fill(ellipse)
        }
    }

    /**
     * Whether every flake still obeys the respawn rules, i.e. lies within [bounds] extended by its own radius on
     * the sides and bottom and by [SPAWN_BAND] above the top edge; test helper.
     */
    fun allInside(bounds: Rectangle): Boolean {
        val topExit = bounds.y - SPAWN_BAND * uiScale
        val right = (bounds.x + bounds.width).toFloat()
        val bottom = (bounds.y + bounds.height).toFloat()
        for (i in 0 until particleCount) {
            val r = radius[i]
            if (y[i] - r > bottom || x[i] + r < bounds.x || x[i] - r > right || y[i] + r < topExit) return false
        }
        return true
    }

    /** Mean horizontal velocity applied on the last [update] in px/s (positive = rightwards); test helper. */
    fun meanVelocityX(): Float = mean(vx)

    /** Mean vertical velocity applied on the last [update] in px/s (positive = downwards); test helper. */
    fun meanVelocityY(): Float = mean(vy)

    /**
     * Places flake [index] at ([px], [py]) with the given on-screen [alpha] and commits it as painted state,
     * so the next [paint] draws exactly one flake there. Test seam for rendering checks.
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
        staleTicks[index] = 0
    }

    /** Number of populated entries in the cached color table; test seam. */
    @TestOnly
    fun colorTableSizeForTest(): Int = colors.count { it != null }

    /** How many times the color table was rebuilt (once per [start] and [onPaletteChanged], never in [paint]); test seam. */
    @TestOnly
    fun colorTableRebuildsForTest(): Int = colorTableRebuilds

    private fun mean(values: FloatArray): Float {
        if (particleCount == 0) return 0f
        var sum = 0f
        for (i in 0 until particleCount) sum += values[i]
        return sum / particleCount
    }

    /** Adjusts the flake count to [bounds]; returns `true` if a region was reported for vanished flakes. */
    private fun resize(bounds: Rectangle, dirty: DirtyRegionCollector): Boolean {
        val bx = bounds.x.toFloat()
        val by = bounds.y.toFloat()
        val bw = bounds.width.toFloat()
        val bh = bounds.height.toFloat()
        val target = particleCountFor(bounds.width, bounds.height, intensity)
        val previous = particleCount
        lastWidth = bounds.width
        lastHeight = bounds.height
        particleCount = target
        for (i in previous until target) {
            spawn(i, bx, by, bw)
            // Initial fill: distribute over the whole area instead of the spawn band; the fade-in hides the arrival.
            y[i] = by + random.nextFloat() * bh
            prevX[i] = x[i]
            prevY[i] = y[i]
            prevAlpha[i] = 0f
        }
        // Shrinking: flakes beyond the new count vanish; their last painted area must be cleared.
        var changed = false
        for (i in target until previous) {
            changed = emitVanish(dirty, bounds, i, radius[i] + 1f) or changed
        }
        // Flakes left outside the new bounds are respawned by the update pass that follows.
        return changed
    }

    /** Gives flake [i] a fresh depth and look and places it, invisible, in the spawn band above the top edge. */
    private fun spawn(i: Int, bx: Float, by: Float, bw: Float) {
        avoidance.reset(i)
        val t = random.nextFloat()
        // Biased toward far flakes: a few large near flakes read as accents rather than as a wall of dots.
        val d = t * sqrt(t)
        depth[i] = d
        val parallax = depthFactor(d)
        radius[i] = (MIN_RADIUS + (MAX_RADIUS - MIN_RADIUS) * d) * uiScale
        baseAlpha[i] = MIN_BASE_ALPHA + (MAX_BASE_ALPHA - MIN_BASE_ALPHA) * d
        fallSpeed[i] = (MIN_FALL_SPEED + random.nextFloat() * (MAX_FALL_SPEED - MIN_FALL_SPEED)) * uiScale * parallax
        swayPhase[i] = random.nextFloat() * TWO_PI
        swayRate[i] = MIN_SWAY_RATE + random.nextFloat() * (MAX_SWAY_RATE - MIN_SWAY_RATE)
        swayAmplitude[i] = (MIN_SWAY_SPEED + random.nextFloat() * (MAX_SWAY_SPEED - MIN_SWAY_SPEED)) * uiScale * parallax
        twinklePhase[i] = random.nextFloat() * TWO_PI
        twinkleRate[i] = MIN_TWINKLE_RATE + random.nextFloat() * (MAX_TWINKLE_RATE - MIN_TWINKLE_RATE)
        x[i] = bx + random.nextFloat() * bw
        y[i] = by - radius[i] - random.nextFloat() * SPAWN_BAND * uiScale
        vx[i] = 0f
        vy[i] = 0f
        fadeIn[i] = 0f
        alpha[i] = 0f
        staleTicks[i] = 0
    }

    /**
     * Reports `prev ∪ new` bounds (+ [padding]) and commits the new state as painted.
     * Returns `false` when nothing on screen changes: the flake was invisible before and after, or both
     * positions lie outside [bounds] (e.g. still descending through the spawn band).
     */
    private fun emitDirty(dirty: DirtyRegionCollector, bounds: Rectangle, i: Int, padding: Float): Boolean {
        val visible = prevAlpha[i] > 0f || alpha[i] > 0f
        val minX = min(prevX[i], x[i]) - padding
        val minY = min(prevY[i], y[i]) - padding
        val maxX = max(prevX[i], x[i]) + padding
        val maxY = max(prevY[i], y[i]) + padding
        prevX[i] = x[i]
        prevY[i] = y[i]
        prevAlpha[i] = alpha[i]
        staleTicks[i] = 0
        if (!visible || !intersects(bounds, minX, minY, maxX, maxY)) return false
        dirty.add(minX, minY, maxX - minX, maxY - minY)
        return true
    }

    /**
     * A (re)spawned flake starts invisible: only its previously painted spot needs clearing.
     * Returns `false` when nothing was on screen.
     */
    private fun emitVanish(dirty: DirtyRegionCollector, bounds: Rectangle, i: Int, padding: Float): Boolean {
        val px = prevX[i]
        val py = prevY[i]
        val visible = prevAlpha[i] > 0f && intersects(bounds, px - padding, py - padding, px + padding, py + padding)
        if (visible) dirty.add(px - padding, py - padding, padding * 2f, padding * 2f)
        prevX[i] = x[i]
        prevY[i] = y[i]
        prevAlpha[i] = 0f
        staleTicks[i] = 0
        return visible
    }

    private fun intersects(bounds: Rectangle, x0: Float, y0: Float, x1: Float, y1: Float): Boolean =
        x1 >= bounds.x && y1 >= bounds.y && x0 <= bounds.x + bounds.width && y0 <= bounds.y + bounds.height

    // Plain AWT colors on purpose: theme awareness comes from the palette, and JBColor would re-resolve
    // the LaF on every access inside the paint loop.
    @Suppress("UseJBColor")
    private fun rebuildColorTable() {
        val c = if (palette.isDark) DARK_FLAKE_COLOR else LIGHT_FLAKE_COLOR
        colors[0] = TRANSPARENT
        for (i in 1 until ALPHA_STEPS) {
            val a = i / (ALPHA_STEPS - 1).toFloat()
            colors[i] = Color(c.red, c.green, c.blue, (a * 255f + 0.5f).toInt().coerceIn(0, 255))
        }
        colorTableRebuilds++
    }

    companion object {
        const val ID: String = "snow"

        const val MIN_PARTICLES: Int = 15
        const val MAX_PARTICLES: Int = 50

        /** User-space pixels per flake at intensity 1 (≈ 47 on a 1512×949 frame; denser than fireflies by nature). */
        const val AREA_PER_PARTICLE: Int = 30_000

        /** Flake radius range in user px (× uiScale); nearer flakes are larger. */
        const val MIN_RADIUS: Float = 1.2f
        const val MAX_RADIUS: Float = 4f

        /** Base opacity range; nearer flakes are brighter. */
        const val MIN_BASE_ALPHA: Float = 0.25f
        const val MAX_BASE_ALPHA: Float = 0.7f

        /** Fall speed range in px/s (× uiScale × [depthFactor]); nearer flakes are faster. */
        const val MIN_FALL_SPEED: Float = 12f
        const val MAX_FALL_SPEED: Float = 40f

        /** Horizontal sway velocity amplitude range in px/s (× uiScale × [depthFactor]). */
        const val MIN_SWAY_SPEED: Float = 8f
        const val MAX_SWAY_SPEED: Float = 20f

        /** Sway angular rate range in rad/s (one side-to-side swing every 6–16 s). */
        const val MIN_SWAY_RATE: Float = 0.4f
        const val MAX_SWAY_RATE: Float = 1f

        /** Peak velocity of the shared breeze in px/s (× uiScale) and its angular rate in rad/s (≈ 50 s per cycle). */
        const val WIND_SPEED: Float = 10f
        const val WIND_RATE: Float = 0.12f

        /** Alpha twinkle: modulation depth for the farthest flakes (none for the nearest) and its rate range in rad/s. */
        const val TWINKLE_DEPTH: Float = 0.35f
        const val MIN_TWINKLE_RATE: Float = 0.6f
        const val MAX_TWINKLE_RATE: Float = 1.6f

        /** Height in user px (× uiScale) of the band above the top edge in which flakes (re)spawn. */
        const val SPAWN_BAND: Float = 40f
        const val FADE_IN_SECONDS: Float = 1.5f

        const val IDLE_SPEED: Float = 0.5f
        const val NORMAL_SPEED: Float = 1f
        const val ACTIVITY_SPEED: Float = 1.4f

        /** Success updraft: peak lift in px/s (× uiScale), sway widening at the peak and envelope timings. */
        const val UPDRAFT_SPEED: Float = 90f
        const val UPDRAFT_SWAY_BOOST: Float = 2f
        const val UPDRAFT_ATTACK_SECONDS: Float = 0.3f
        const val UPDRAFT_DECAY_SECONDS: Float = 1.5f

        /** Failure gust: peak sideways velocity in px/s (× uiScale), dimming at the peak and envelope timings. */
        const val GUST_SPEED: Float = 60f
        const val FAILURE_DIM: Float = 0.25f
        const val GUST_ATTACK_SECONDS: Float = 0.25f
        const val GUST_DECAY_SECONDS: Float = 1.75f

        /**
         * Number of alpha steps in the cached color table (8-bit resolution, so fades never step visibly);
         * also the alpha resolution of dirty reporting.
         */
        const val ALPHA_STEPS: Int = 256

        /** Flake color on dark themes: cold white. */
        @Suppress("UseJBColor")
        val DARK_FLAKE_COLOR: Color = Color(235, 242, 255)

        /** Flake color on light themes: dusty steel blue; alpha is further reduced by [AtmospherePalette.opacityScale]. */
        @Suppress("UseJBColor")
        val LIGHT_FLAKE_COLOR: Color = Color(120, 145, 185)

        /** Easing applied when the opposite outcome cuts an active updraft or gust short. */
        private const val OUTCOME_RELEASE_SECONDS = 0.4f
        private const val TWO_PI = (Math.PI * 2).toFloat()
        @Suppress("UseJBColor")
        private val TRANSPARENT = Color(0, 0, 0, 0)

        /** `clamp(area / (AREA_PER_PARTICLE * (1/intensity)), 15, 50)`. */
        @JvmStatic
        fun particleCountFor(width: Int, height: Int, intensity: Float = 1f): Int {
            val areaPerParticle = AREA_PER_PARTICLE * (1f / intensity)
            return (width.toLong() * height / areaPerParticle).toInt().coerceIn(MIN_PARTICLES, MAX_PARTICLES)
        }

        /** Motion parallax: far flakes (depth 0) move at half the speed of near flakes (depth 1). */
        private fun depthFactor(depth: Float): Float = 0.5f + 0.5f * depth

        private fun alphaIndex(a: Float): Int = (a * (ALPHA_STEPS - 1) + 0.5f).toInt().coerceIn(0, ALPHA_STEPS - 1)
    }
}
