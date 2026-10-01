package info.chrzanowski.ideatmosphere.effects.starfield

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
import java.awt.geom.Path2D
import java.util.SplittableRandom
import org.jetbrains.annotations.TestOnly
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A quiet, twinkling night sky with an occasional shooting star.
 *
 * Every star carries a random depth in `0..1`: near stars (depth → 1) are larger, brighter and drift faster, far
 * ones are tiny, faint and slow. All stars drift very slowly to the left (parallax by depth) and twinkle by
 * modulating their opacity. Roughly every [MIN_SHOOTING_INTERVAL_SECONDS]–[MAX_SHOOTING_INTERVAL_SECONDS] seconds
 * (shorter at higher intensity) a single shooting star streaks across the upper part of the frame: a tapered
 * trail that fades in and out along its short flight.
 *
 * The dark variant is a cold white; the light variant is a soft slate blue drawn at [LIGHT_SOFTENING] of the
 * opacity, so the sky stays a subtle texture on bright themes.
 *
 * Reactions are motion and brightness only and never touch the star color:
 * - [AtmosphereEvent.USER_IDLE] / [AtmosphereEvent.ACTIVITY_STARTED] ease the speed multiplier (drift and twinkle
 *   rate) to [IDLE_SPEED] / [ACTIVITY_SPEED]; [AtmosphereEvent.USER_ACTIVE] / [AtmosphereEvent.ACTIVITY_FINISHED]
 *   release it back to [NORMAL_SPEED];
 * - [AtmosphereEvent.SUCCESS] is a "celebration": a one-shot envelope ([LIFT_ATTACK_SECONDS] + [LIFT_DECAY_SECONDS])
 *   that lifts the stars by up to [LIFT_SPEED], speeds up the twinkle by [TWINKLE_BOOST] and launches a shooting
 *   star right away (unless one is in flight);
 * - [AtmosphereEvent.FAILURE] is a "tremor": the whole sky shivers sideways with up to [SHIVER_SPEED] and the
 *   stars dim by up to [FAILURE_DIM].
 *
 * Stars and the shooting star are stored in primitive arrays/fields so [update] and [paint] never allocate: the
 * shapes are a single reused [Ellipse2D.Float] and a single reused [Path2D.Float], and colors come from an
 * [ALPHA_STEPS]-entry alpha table rebuilt only in [start] and [onPaletteChanged]. Dirty reporting follows the
 * contract shared with `SnowEffect` and `FirefliesEffect`: `prev*` fields are the **on-screen** state, [paint] draws
 * from them, and they advance only when a covering region has been reported ([CommitPolicy] decides when a star is
 * committed). At most one region is reported per star and one for the shooting star per tick.
 *
 * Stars fade in at their position (they never pop); a star that has left the bounds is respawned at a random place
 * inside them. Coordinates are root-pane user-space pixels; sizes and speeds are scaled by [EffectContext.uiScale].
 */
class StarfieldEffect : AtmosphereEffect {

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
    private val driftSpeed = FloatArray(MAX_PARTICLES)
    private val twinklePhase = FloatArray(MAX_PARTICLES)
    private val twinkleRate = FloatArray(MAX_PARTICLES)
    private val baseAlpha = FloatArray(MAX_PARTICLES)
    private val fadeIn = FloatArray(MAX_PARTICLES) // 0..1 progress since the last (re)spawn.
    private val alpha = FloatArray(MAX_PARTICLES)

    // Last *painted* state; advances only when a covering region is emitted.
    private val prevX = FloatArray(MAX_PARTICLES)
    private val prevY = FloatArray(MAX_PARTICLES)
    private val prevAlpha = FloatArray(MAX_PARTICLES)
    private val staleTicks = IntArray(MAX_PARTICLES)

    private val colors = arrayOfNulls<Color>(ALPHA_STEPS)
    private var colorTableRebuilds = 0
    private val avoidance = CursorAvoidance(MAX_PARTICLES)

    // Single reused shapes; mutated in `paint` only (EDT).
    private val ellipse = Ellipse2D.Float()
    private val trail = Path2D.Float(Path2D.WIND_NON_ZERO, 8)

    // Phase of the failure tremor.
    private var shiverPhase = 0f

    // The shooting star: head position, unit direction, painted (previous) head and tail, and the wait for the next one.
    private var shootingActive = false
    private var launchRequested = false
    private var shootingTimer = 0f
    private var shootingAge = 0f
    private var shootingDuration = 1f
    private var shootingSpeed = 0f
    private var shootingTail = 0f
    private var shootingX = 0f
    private var shootingY = 0f
    private var shootingDirX = 1f
    private var shootingDirY = 0f
    private var shootingAlpha = 0f
    private var prevShootingX = 0f
    private var prevShootingY = 0f
    private var prevShootingTailX = 0f
    private var prevShootingTailY = 0f
    private var prevShootingAlpha = 0f

    /** Number of live stars for the current bounds. */
    var particleCount: Int = 0
        private set

    /** Whether a shooting star is currently in flight. */
    val shootingStarActive: Boolean get() = shootingActive

    /** How many shooting stars were launched since [start]. */
    var shootingStarsLaunched: Int = 0
        private set

    /** Current smoothed speed multiplier (driven by [AdsrEnvelope]), including [setSpeedScale]. */
    var speedMultiplier: Float = 1f
        private set

    /** Strength 0..1 of the success celebration (driven by [AdsrEnvelope]); motion only, never a color change. */
    var glow: Float = 0f
        private set

    /** Strength 0..1 of the failure tremor and its dimming (driven by [AdsrEnvelope]). */
    var failureGlow: Float = 0f
        private set

    private val speedEnvelope = AdsrEnvelope(
        restLevel = NORMAL_SPEED,
        attackSeconds = 1f,
        decaySeconds = 1.5f,
        sustainRatio = 1f,
        releaseSeconds = 2f,
    )

    private val liftEnvelope = AdsrEnvelope(
        restLevel = 0f,
        attackSeconds = LIFT_ATTACK_SECONDS,
        decaySeconds = LIFT_DECAY_SECONDS,
        sustainRatio = 0f,
        releaseSeconds = OUTCOME_RELEASE_SECONDS,
        oneShot = true,
    )

    private val shiverEnvelope = AdsrEnvelope(
        restLevel = 0f,
        attackSeconds = SHIVER_ATTACK_SECONDS,
        decaySeconds = SHIVER_DECAY_SECONDS,
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
        shiverPhase = 0f
        avoidance.clear()
        resetShootingStar()
        shootingTimer = INITIAL_SHOOTING_MIN_SECONDS +
            random.nextFloat() * (INITIAL_SHOOTING_MAX_SECONDS - INITIAL_SHOOTING_MIN_SECONDS)
        shootingStarsLaunched = 0
        speedEnvelope.release()
        speedEnvelope.advance(100f)
        liftEnvelope.release()
        liftEnvelope.advance(100f)
        shiverEnvelope.release()
        shiverEnvelope.advance(100f)
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
        resetShootingStar()
        liftEnvelope.release()
        liftEnvelope.advance(100f)
        shiverEnvelope.release()
        shiverEnvelope.advance(100f)
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

    override fun onScroll(x: Float, y: Float, amount: Float) {
        // The gust follows the content: scrolling down moves the text up, and the stars with it.
        avoidance.gust(x, y, 0f, if (amount > 0f) -1f else 1f, abs(amount), uiScale, particleCount, this.x, this.y)
    }

    override fun setAnchor(x: Float, y: Float, active: Boolean) {
        avoidance.setAnchor(x, y, active)
    }

    override fun setPointerMode(mode: PointerMode) {
        avoidance.mode = mode
    }

    override fun onEvent(event: AtmosphereEvent) {
        when (event) {
            AtmosphereEvent.USER_IDLE -> speedEnvelope.trigger(IDLE_SPEED)
            AtmosphereEvent.EXECUTION_PAUSED -> speedEnvelope.trigger(IDLE_SPEED * AtmosphereEffect.PAUSED_SPEED_FACTOR)
            AtmosphereEvent.USER_ACTIVE -> speedEnvelope.release()
            AtmosphereEvent.ACTIVITY_STARTED -> speedEnvelope.trigger(ACTIVITY_SPEED)
            AtmosphereEvent.ACTIVITY_FINISHED -> speedEnvelope.release()
            AtmosphereEvent.SUCCESS -> {
                // The opposite outcome eases out over OUTCOME_RELEASE_SECONDS instead of snapping, so nothing pops.
                shiverEnvelope.release()
                liftEnvelope.trigger(1f)
                // The launch needs the bounds, so it happens on the next update; a flight in progress is left alone.
                if (!shootingActive) launchRequested = true
            }
            AtmosphereEvent.FAILURE -> {
                liftEnvelope.release()
                shiverEnvelope.trigger(1f)
            }
        }
    }

    override fun update(deltaSeconds: Float, bounds: Rectangle, dirty: DirtyRegionCollector): Boolean {
        if (!started || !deltaSeconds.isFinite() || deltaSeconds <= 0f || bounds.width <= 0 || bounds.height <= 0) return false
        val bx0 = bounds.x.toFloat()
        val by0 = bounds.y.toFloat()
        val bw = bounds.width.toFloat()
        val bh = bounds.height.toFloat()
        val bx1 = bx0 + bw
        val by1 = by0 + bh

        var changed = false
        if (populationChanged || bounds.width != lastWidth || bounds.height != lastHeight) {
            changed = resize(bounds, dirty)
            populationChanged = false
        }

        // Smooth global modifiers.
        speedMultiplier = speedEnvelope.advance(deltaSeconds) * speedScale * flowBoost
        glow = liftEnvelope.advance(deltaSeconds)
        failureGlow = shiverEnvelope.advance(deltaSeconds)

        // The tremor moves the whole sky in sync, like a shaken camera: one shared sideways velocity.
        shiverPhase += SHIVER_RATE * deltaSeconds
        if (shiverPhase > TWO_PI) shiverPhase -= TWO_PI
        val shiver = if (failureGlow > 0f) cos(shiverPhase) * failureGlow * SHIVER_SPEED * uiScale else 0f
        val lift = glow * LIFT_SPEED * uiScale
        val twinkleStep = deltaSeconds * speedMultiplier * (1f + glow * TWINKLE_BOOST)
        val alphaScale = intensity * palette.opacityScale * (if (palette.isDark) 1f else LIGHT_SOFTENING)
        val dim = 1f - failureGlow * FAILURE_DIM
        val fadeStep = deltaSeconds / FADE_IN_SECONDS

        for (i in 0 until particleCount) {
            val d = depth[i]
            val parallax = depthFactor(d)

            // Motion: slow leftward drift, success lift and failure tremor with parallax, plus cursor steering.
            avoidance.advance(i, x[i], y[i], deltaSeconds, uiScale)
            vx[i] = -driftSpeed[i] * speedMultiplier + shiver * parallax + avoidance.velocityX(i)
            vy[i] = -lift * (0.6f + 0.4f * d) + avoidance.velocityY(i)
            x[i] += vx[i] * deltaSeconds
            y[i] += vy[i] * deltaSeconds

            // Left the bounds (drifted out, lifted out, or the frame shrank): reappear somewhere inside, fading in.
            val r = radius[i]
            if (x[i] + r < bx0 || x[i] - r > bx1 || y[i] + r < by0 || y[i] - r > by1) {
                spawn(i, bx0, by0, bw, bh)
                changed = emitVanish(dirty, bounds, i, r + 1f) or changed
                continue
            }

            // Opacity: depth-graded base × fade-in × twinkle × theme × intensity, dimmed by a tremor.
            fadeIn[i] = min(1f, fadeIn[i] + fadeStep)
            twinklePhase[i] += twinkleRate[i] * twinkleStep
            if (twinklePhase[i] > TWO_PI) twinklePhase[i] -= TWO_PI
            val twinkle = 1f - TWINKLE_DEPTH * (1f - 0.5f * d) * (0.5f + 0.5f * sin(twinklePhase[i]))
            alpha[i] = min(1f, baseAlpha[i] * alphaScale * fadeIn[i] * twinkle) * dim

            val alphaDelta = abs(alphaIndex(alpha[i]) - alphaIndex(prevAlpha[i]))
            if (CommitPolicy.shouldCommit(x[i] - prevX[i], y[i] - prevY[i], alphaDelta, staleTicks[i])) {
                changed = emitDirty(dirty, bounds, i, r + 1f) or changed
            } else {
                staleTicks[i]++
            }
        }

        changed = updateShootingStar(deltaSeconds, bounds, dirty, alphaScale * dim) or changed
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

        val shootingIdx = alphaIndex(prevShootingAlpha)
        if (prevShootingAlpha > 0f && shootingIdx > 0) {
            val hx = prevShootingX
            val hy = prevShootingY
            val ox = prevShootingTailX
            val oy = prevShootingTailY
            val pad = SHOOTING_WIDTH * uiScale * 0.5f + 1f
            val l = sqrt(ox * ox + oy * oy)
            if (l >= 0.01f &&
                max(hx, hx + ox) + pad >= cx0 && min(hx, hx + ox) - pad <= cx1 &&
                max(hy, hy + oy) + pad >= cy0 && min(hy, hy + oy) - pad <= cy1
            ) {
                // Tapered trail: a triangle from the sharp tail to the full width at the head.
                val nx = -oy / l * SHOOTING_WIDTH * uiScale * 0.5f
                val ny = ox / l * SHOOTING_WIDTH * uiScale * 0.5f
                trail.reset()
                trail.moveTo(hx + ox, hy + oy)
                trail.lineTo(hx + nx, hy + ny)
                trail.lineTo(hx - nx, hy - ny)
                trail.closePath()
                g.color = colors[shootingIdx]
                g.fill(trail)
            }
        }
    }

    /** Whether every star lies within [bounds] extended by its own radius; test helper. */
    fun allInside(bounds: Rectangle): Boolean {
        val right = (bounds.x + bounds.width).toFloat()
        val bottom = (bounds.y + bounds.height).toFloat()
        for (i in 0 until particleCount) {
            val r = radius[i]
            if (y[i] - r > bottom || x[i] + r < bounds.x || x[i] - r > right || y[i] + r < bounds.y) return false
        }
        return true
    }

    /** Mean horizontal velocity applied on the last [update] in px/s (positive = rightwards); test helper. */
    fun meanVelocityX(): Float = mean(vx)

    /** Mean vertical velocity applied on the last [update] in px/s (positive = downwards); test helper. */
    fun meanVelocityY(): Float = mean(vy)

    /**
     * Places star [index] at ([px], [py]) with the given on-screen [alpha] and commits it as painted state,
     * so the next [paint] draws exactly one star there. Test seam for rendering checks.
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

    /** Adjusts the star count to [bounds]; returns `true` if a region was reported for vanished stars. */
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
            // New stars appear at a random spot inside the bounds and fade in, so the arrival never pops.
            spawn(i, bx, by, bw, bh)
            prevX[i] = x[i]
            prevY[i] = y[i]
            prevAlpha[i] = 0f
        }
        // Shrinking: stars beyond the new count vanish; their last painted area must be cleared.
        var changed = false
        for (i in target until previous) {
            changed = emitVanish(dirty, bounds, i, radius[i] + 1f) or changed
        }
        // Stars left outside the new bounds are respawned by the update pass that follows.
        return changed
    }

    /** Gives star [i] a fresh depth and look and places it, invisible, at a random spot inside the bounds. */
    private fun spawn(i: Int, bx: Float, by: Float, bw: Float, bh: Float) {
        avoidance.reset(i)
        val t = random.nextFloat()
        // Biased toward far stars: a few large near stars read as accents rather than as a wall of dots.
        val d = t * sqrt(t)
        depth[i] = d
        radius[i] = (MIN_RADIUS + (MAX_RADIUS - MIN_RADIUS) * d) * uiScale
        baseAlpha[i] = MIN_BASE_ALPHA + (MAX_BASE_ALPHA - MIN_BASE_ALPHA) * d
        driftSpeed[i] = (MIN_DRIFT_SPEED + random.nextFloat() * (MAX_DRIFT_SPEED - MIN_DRIFT_SPEED)) * uiScale * depthFactor(d)
        twinklePhase[i] = random.nextFloat() * TWO_PI
        twinkleRate[i] = MIN_TWINKLE_RATE + random.nextFloat() * (MAX_TWINKLE_RATE - MIN_TWINKLE_RATE)
        x[i] = bx + random.nextFloat() * bw
        y[i] = by + random.nextFloat() * bh
        fadeIn[i] = 0f
        alpha[i] = 0f
        staleTicks[i] = 0
    }

    /**
     * Reports `prev ∪ new` bounds (+ [padding]) and commits the new state as painted.
     * Returns `false` when nothing on screen changes: the star was invisible before and after,
     * or both positions lie outside [bounds].
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
     * A (re)spawned star starts invisible: only its previously painted spot needs clearing.
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

    /**
     * Waits for, launches, advances and finishes the shooting star; reports at most one region.
     * [alphaScale] is the theme/intensity/tremor opacity factor shared with the stars.
     */
    private fun updateShootingStar(deltaSeconds: Float, bounds: Rectangle, dirty: DirtyRegionCollector, alphaScale: Float): Boolean {
        if (!shootingActive) {
            shootingTimer -= deltaSeconds
            if (!launchRequested && shootingTimer > 0f) return false
            launchRequested = false
            launchShootingStar(bounds)
        }

        shootingAge += deltaSeconds
        val t = shootingAge / shootingDuration
        val step = shootingSpeed * speedScale * deltaSeconds
        val tailLength = shootingTail
        if (t >= 1f) {
            // The flight is over: clear the last painted trail and schedule the next one.
            val changed = emitShootingVanish(dirty, bounds)
            shootingActive = false
            shootingTimer = (MIN_SHOOTING_INTERVAL_SECONDS +
                random.nextFloat() * (MAX_SHOOTING_INTERVAL_SECONDS - MIN_SHOOTING_INTERVAL_SECONDS)) / intensity
            return changed
        }

        shootingX += shootingDirX * step
        shootingY += shootingDirY * step
        // Fades in over the first part of the flight and out over the last; the middle is at full strength.
        val envelope = min(1f, min(t / SHOOTING_FADE_IN, (1f - t) / SHOOTING_FADE_OUT))
        shootingAlpha = min(1f, SHOOTING_ALPHA * alphaScale * envelope)

        val tailX = -shootingDirX * tailLength
        val tailY = -shootingDirY * tailLength
        val padding = SHOOTING_WIDTH * uiScale * 0.5f + 1f
        val visible = prevShootingAlpha > 0f || shootingAlpha > 0f
        val minX = min(min(prevShootingX, prevShootingX + prevShootingTailX), min(shootingX, shootingX + tailX)) - padding
        val minY = min(min(prevShootingY, prevShootingY + prevShootingTailY), min(shootingY, shootingY + tailY)) - padding
        val maxX = max(max(prevShootingX, prevShootingX + prevShootingTailX), max(shootingX, shootingX + tailX)) + padding
        val maxY = max(max(prevShootingY, prevShootingY + prevShootingTailY), max(shootingY, shootingY + tailY)) + padding
        prevShootingX = shootingX
        prevShootingY = shootingY
        prevShootingTailX = tailX
        prevShootingTailY = tailY
        prevShootingAlpha = shootingAlpha
        if (!visible || !intersects(bounds, minX, minY, maxX, maxY)) return false
        dirty.add(minX, minY, maxX - minX, maxY - minY)
        return true
    }

    /** Starts a shooting star in the upper part of [bounds], flying downwards at a shallow angle. */
    private fun launchShootingStar(bounds: Rectangle) {
        val leftToRight = random.nextBoolean()
        val angle = MIN_SHOOTING_ANGLE + random.nextFloat() * (MAX_SHOOTING_ANGLE - MIN_SHOOTING_ANGLE)
        shootingDirX = cos(angle) * if (leftToRight) 1f else -1f
        shootingDirY = sin(angle)
        val spanStart = if (leftToRight) 0.05f else 0.45f
        shootingX = bounds.x + bounds.width * (spanStart + 0.5f * random.nextFloat())
        shootingY = bounds.y + bounds.height * (0.05f + 0.35f * random.nextFloat())
        shootingSpeed = (MIN_SHOOTING_SPEED + random.nextFloat() * (MAX_SHOOTING_SPEED - MIN_SHOOTING_SPEED)) * uiScale
        shootingDuration = MIN_SHOOTING_SECONDS + random.nextFloat() * (MAX_SHOOTING_SECONDS - MIN_SHOOTING_SECONDS)
        shootingTail = (MIN_SHOOTING_TAIL + random.nextFloat() * (MAX_SHOOTING_TAIL - MIN_SHOOTING_TAIL)) * uiScale
        shootingAge = 0f
        shootingAlpha = 0f
        prevShootingX = shootingX
        prevShootingY = shootingY
        prevShootingTailX = -shootingDirX * shootingTail
        prevShootingTailY = -shootingDirY * shootingTail
        prevShootingAlpha = 0f
        shootingActive = true
        shootingStarsLaunched++
    }

    private fun emitShootingVanish(dirty: DirtyRegionCollector, bounds: Rectangle): Boolean {
        val padding = SHOOTING_WIDTH * uiScale * 0.5f + 1f
        val minX = min(prevShootingX, prevShootingX + prevShootingTailX) - padding
        val minY = min(prevShootingY, prevShootingY + prevShootingTailY) - padding
        val maxX = max(prevShootingX, prevShootingX + prevShootingTailX) + padding
        val maxY = max(prevShootingY, prevShootingY + prevShootingTailY) + padding
        val visible = prevShootingAlpha > 0f && intersects(bounds, minX, minY, maxX, maxY)
        if (visible) dirty.add(minX, minY, maxX - minX, maxY - minY)
        prevShootingAlpha = 0f
        shootingAlpha = 0f
        return visible
    }

    private fun resetShootingStar() {
        shootingActive = false
        launchRequested = false
        shootingAge = 0f
        shootingAlpha = 0f
        prevShootingAlpha = 0f
    }

    private fun intersects(bounds: Rectangle, x0: Float, y0: Float, x1: Float, y1: Float): Boolean =
        x1 >= bounds.x && y1 >= bounds.y && x0 <= bounds.x + bounds.width && y0 <= bounds.y + bounds.height

    // Plain AWT colors on purpose: theme awareness comes from the palette, and JBColor would re-resolve
    // the LaF on every access inside the paint loop.
    @Suppress("UseJBColor")
    private fun rebuildColorTable() {
        val c = if (palette.isDark) DARK_STAR_COLOR else LIGHT_STAR_COLOR
        colors[0] = TRANSPARENT
        for (i in 1 until ALPHA_STEPS) {
            val a = i / (ALPHA_STEPS - 1).toFloat()
            colors[i] = Color(c.red, c.green, c.blue, (a * 255f + 0.5f).toInt().coerceIn(0, 255))
        }
        colorTableRebuilds++
    }

    companion object {
        const val ID: String = "starfield"

        const val MIN_PARTICLES: Int = 30
        const val MAX_PARTICLES: Int = 90

        /** User-space pixels per star at intensity 1 (≈ 90 on a 1512×949 frame). */
        const val AREA_PER_PARTICLE: Int = 14_000

        /** Star radius range in user px (× uiScale); nearer stars are larger. */
        const val MIN_RADIUS: Float = 0.6f
        const val MAX_RADIUS: Float = 1.8f

        /** Base opacity range; nearer stars are brighter. */
        const val MIN_BASE_ALPHA: Float = 0.3f
        const val MAX_BASE_ALPHA: Float = 0.85f

        /** Leftward drift speed range in px/s (× uiScale × [depthFactor]); nearer stars drift faster. */
        const val MIN_DRIFT_SPEED: Float = 2f
        const val MAX_DRIFT_SPEED: Float = 6f

        /** Twinkle: modulation depth for the farthest stars (smaller for near ones) and its rate range in rad/s. */
        const val TWINKLE_DEPTH: Float = 0.7f
        const val MIN_TWINKLE_RATE: Float = 0.5f
        const val MAX_TWINKLE_RATE: Float = 1.5f

        const val FADE_IN_SECONDS: Float = 2f

        /** Opacity factor of the light variant on top of [AtmospherePalette.opacityScale]. */
        const val LIGHT_SOFTENING: Float = 0.75f

        /** Shooting star: waits before the first one and between two, flight timing, geometry and brightness. */
        const val INITIAL_SHOOTING_MIN_SECONDS: Float = 4f
        const val INITIAL_SHOOTING_MAX_SECONDS: Float = 12f
        const val MIN_SHOOTING_INTERVAL_SECONDS: Float = 15f
        const val MAX_SHOOTING_INTERVAL_SECONDS: Float = 40f
        const val MIN_SHOOTING_SECONDS: Float = 0.8f
        const val MAX_SHOOTING_SECONDS: Float = 1.1f
        const val MIN_SHOOTING_SPEED: Float = 520f
        const val MAX_SHOOTING_SPEED: Float = 760f
        const val MIN_SHOOTING_TAIL: Float = 90f
        const val MAX_SHOOTING_TAIL: Float = 140f
        const val SHOOTING_WIDTH: Float = 2f
        const val SHOOTING_ALPHA: Float = 0.9f

        /** Flight angle below the horizontal, in radians (≈ 20°–45°). */
        const val MIN_SHOOTING_ANGLE: Float = 0.35f
        const val MAX_SHOOTING_ANGLE: Float = 0.8f

        /** Fractions of the flight over which the trail fades in and out. */
        const val SHOOTING_FADE_IN: Float = 0.15f
        const val SHOOTING_FADE_OUT: Float = 0.4f

        const val IDLE_SPEED: Float = 0.5f
        const val NORMAL_SPEED: Float = 1f
        const val ACTIVITY_SPEED: Float = 1.4f

        /** Success: peak lift in px/s (× uiScale), twinkle speed-up at the peak and envelope timings. */
        const val LIFT_SPEED: Float = 20f
        const val TWINKLE_BOOST: Float = 2f
        const val LIFT_ATTACK_SECONDS: Float = 0.3f
        const val LIFT_DECAY_SECONDS: Float = 1.5f

        /** Failure: peak tremor velocity in px/s (× uiScale), its angular rate in rad/s, dimming at the peak, timings. */
        const val SHIVER_SPEED: Float = 24f
        const val SHIVER_RATE: Float = 8f
        const val FAILURE_DIM: Float = 0.3f
        const val SHIVER_ATTACK_SECONDS: Float = 0.25f
        const val SHIVER_DECAY_SECONDS: Float = 1.75f

        /**
         * Number of alpha steps in the cached color table (8-bit resolution, so fades never step visibly);
         * also the alpha resolution of dirty reporting.
         */
        const val ALPHA_STEPS: Int = 256

        /** Star color on dark themes: cold white. */
        @Suppress("UseJBColor")
        val DARK_STAR_COLOR: Color = Color(225, 235, 255)

        /** Star color on light themes: soft slate blue; alpha is further reduced by [LIGHT_SOFTENING]. */
        @Suppress("UseJBColor")
        val LIGHT_STAR_COLOR: Color = Color(95, 115, 170)

        /** Easing applied when the opposite outcome cuts an active reaction short. */
        private const val OUTCOME_RELEASE_SECONDS = 0.4f
        private const val TWO_PI = (Math.PI * 2).toFloat()
        @Suppress("UseJBColor")
        private val TRANSPARENT = Color(0, 0, 0, 0)

        /** `clamp(area / (AREA_PER_PARTICLE * (1/intensity)), 30, 90)`. */
        @JvmStatic
        fun particleCountFor(width: Int, height: Int, intensity: Float = 1f): Int {
            val areaPerParticle = AREA_PER_PARTICLE * (1f / intensity)
            return (width.toLong() * height / areaPerParticle).toInt().coerceIn(MIN_PARTICLES, MAX_PARTICLES)
        }

        /** Motion parallax: far stars (depth 0) move at half the speed of near stars (depth 1). */
        private fun depthFactor(depth: Float): Float = 0.5f + 0.5f * depth

        private fun alphaIndex(a: Float): Int = (a * (ALPHA_STEPS - 1) + 0.5f).toInt().coerceIn(0, ALPHA_STEPS - 1)
    }
}
