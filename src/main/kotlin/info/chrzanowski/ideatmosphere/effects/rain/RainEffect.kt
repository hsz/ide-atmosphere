package info.chrzanowski.ideatmosphere.effects.rain

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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Calm, depth-layered rain with splashes.
 *
 * Every drop carries a random depth in `0..1`: near drops (depth → 1) are longer, thicker, brighter and faster;
 * far drops are short, faint and slow, so the field reads as a volume. A drop is drawn as a thin tapered streak
 * (a triangle whose sharp end trails behind the head) leaning along its velocity. Each drop falls to its own
 * landing line somewhere in the lower part of the frame; the nearer ones leave a short-lived, expanding and
 * fading splash there before the drop respawns above the top edge.
 *
 * Reactions are motion and brightness only and never touch the drop color:
 * - [AtmosphereEvent.USER_IDLE] / [AtmosphereEvent.ACTIVITY_STARTED] ease the speed multiplier to
 *   [IDLE_SPEED] / [ACTIVITY_SPEED]; [AtmosphereEvent.USER_ACTIVE] / [AtmosphereEvent.ACTIVITY_FINISHED]
 *   release it back to [NORMAL_SPEED];
 * - [AtmosphereEvent.SUCCESS] is "the rain eases": a one-shot envelope ([EASE_ATTACK_SECONDS] +
 *   [EASE_DECAY_SECONDS]) that slows the fall by up to [EASE_SLOWDOWN] and shortens the streaks by [EASE_SHORTEN];
 * - [AtmosphereEvent.FAILURE] is a storm gust with a lightning flash: a sideways gust of up to [GUST_SPEED] in one
 *   direction (chosen when the gust starts and kept while it is active), a faster fall and a brief brightness
 *   flash of the drops by up to [FLASH_BOOST] (as opacity, never as a different color).
 *
 * Drops and splashes are stored as structure-of-arrays so [update] and [paint] never allocate: the shapes are a
 * single reused [Path2D.Float] and a single reused [Ellipse2D.Float], and colors come from an [ALPHA_STEPS]-entry
 * alpha table rebuilt only in [start] and [onPaletteChanged]. Dirty reporting follows the contract shared with
 * `SnowEffect`: `prev*` fields are the **on-screen** state, [paint] draws from them, and they advance only when a
 * region covering both the old and the new shape has been reported ([CommitPolicy] decides when a drop is
 * committed). At most one region is reported per drop and per splash per tick, i.e. at most
 * `MAX_PARTICLES + MAX_SPLASHES` regions.
 *
 * Coordinates are root-pane user-space pixels; sizes and speeds are scaled by [EffectContext.uiScale].
 */
class RainEffect : AtmosphereEffect {

    override val id: String get() = ID

    private var random: SplittableRandom = SplittableRandom()
    private var uiScale = 1f
    private var palette: AtmospherePalette = AtmospherePalette.DARK

    private var intensity: Float = 1f
    private var speedScale: Float = 1f
    /** Typing-flow gain on top of [speedScale]: `1` at rest, up to `1 + FLOW_SPEED_BOOST` while typing fast. */
    private var flowBoost: Float = 1f
    private var populationChanged = false

    // Drops: the head position and the offset from the head to the tapered tail.
    private val x = FloatArray(MAX_PARTICLES)
    private val y = FloatArray(MAX_PARTICLES)
    private val vx = FloatArray(MAX_PARTICLES) // Velocity applied on the last update, px/s.
    private val vy = FloatArray(MAX_PARTICLES)
    private val tailX = FloatArray(MAX_PARTICLES)
    private val tailY = FloatArray(MAX_PARTICLES)
    private val depth = FloatArray(MAX_PARTICLES) // 0 = far, 1 = near.
    private val length = FloatArray(MAX_PARTICLES)
    private val width = FloatArray(MAX_PARTICLES)
    private val fallSpeed = FloatArray(MAX_PARTICLES)
    private val baseAlpha = FloatArray(MAX_PARTICLES)
    private val landY = FloatArray(MAX_PARTICLES)
    private val fadeIn = FloatArray(MAX_PARTICLES) // 0..1 progress since the last (re)spawn.
    private val alpha = FloatArray(MAX_PARTICLES)

    // Last *painted* state; advances only when a covering region is emitted.
    private val prevX = FloatArray(MAX_PARTICLES)
    private val prevY = FloatArray(MAX_PARTICLES)
    private val prevTailX = FloatArray(MAX_PARTICLES)
    private val prevTailY = FloatArray(MAX_PARTICLES)
    private val prevAlpha = FloatArray(MAX_PARTICLES)
    private val staleTicks = IntArray(MAX_PARTICLES)

    // Splashes: a fixed pool; a landing drop takes a free slot or skips its splash when none is free.
    private val splashActive = BooleanArray(MAX_SPLASHES)
    private val splashX = FloatArray(MAX_SPLASHES)
    private val splashY = FloatArray(MAX_SPLASHES)
    private val splashAge = FloatArray(MAX_SPLASHES)
    private val splashDuration = FloatArray(MAX_SPLASHES)
    private val splashMaxRadius = FloatArray(MAX_SPLASHES)
    private val splashBaseAlpha = FloatArray(MAX_SPLASHES)
    private val splashRadius = FloatArray(MAX_SPLASHES)
    private val splashAlpha = FloatArray(MAX_SPLASHES)
    private val prevSplashRadius = FloatArray(MAX_SPLASHES)
    private val prevSplashAlpha = FloatArray(MAX_SPLASHES)
    private var nextSplash = 0

    private val colors = arrayOfNulls<Color>(ALPHA_STEPS)
    private var colorTableRebuilds = 0
    private val avoidance = CursorAvoidance(MAX_PARTICLES)

    // Single reused shapes; mutated in `paint` only (EDT).
    private val streak = Path2D.Float(Path2D.WIND_NON_ZERO, 8)
    private val ellipse = Ellipse2D.Float()

    // Phase of the shared breeze and the direction (±1) of the current failure gust.
    private var windPhase = 0f
    private var gustDirection = 1f

    /** Number of live drops for the current bounds. */
    var particleCount: Int = 0
        private set

    /** Number of splashes currently alive. */
    var splashCount: Int = 0
        private set

    /** Current smoothed speed multiplier (driven by [AdsrEnvelope]), including [setSpeedScale]. */
    var speedMultiplier: Float = 1f
        private set

    /** Strength 0..1 of the success "rain eases" reaction (driven by [AdsrEnvelope]). */
    var glow: Float = 0f
        private set

    /** Strength 0..1 of the failure gust and lightning flash (driven by [AdsrEnvelope]). */
    var failureGlow: Float = 0f
        private set

    private val speedEnvelope = AdsrEnvelope(
        restLevel = NORMAL_SPEED,
        attackSeconds = 1f,
        decaySeconds = 1.5f,
        sustainRatio = 1f,
        releaseSeconds = 2f,
    )

    private val easeEnvelope = AdsrEnvelope(
        restLevel = 0f,
        attackSeconds = EASE_ATTACK_SECONDS,
        decaySeconds = EASE_DECAY_SECONDS,
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
        clearSplashes()
        lastWidth = -1
        lastHeight = -1
        windPhase = random.nextFloat() * TWO_PI
        gustDirection = 1f
        avoidance.clear()
        speedEnvelope.release()
        speedEnvelope.advance(100f)
        easeEnvelope.release()
        easeEnvelope.advance(100f)
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
        clearSplashes()
        lastWidth = -1
        lastHeight = -1
        avoidance.clear()
        easeEnvelope.release()
        easeEnvelope.advance(100f)
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

    override fun onScroll(x: Float, y: Float, amount: Float) {
        // The gust follows the content: scrolling down moves the text up, and the drops with it.
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
            AtmosphereEvent.USER_ACTIVE -> speedEnvelope.release()
            AtmosphereEvent.ACTIVITY_STARTED -> speedEnvelope.trigger(ACTIVITY_SPEED)
            AtmosphereEvent.ACTIVITY_FINISHED -> speedEnvelope.release()
            AtmosphereEvent.SUCCESS -> {
                // The opposite outcome eases out over OUTCOME_RELEASE_SECONDS instead of snapping, so nothing pops.
                gustEnvelope.release()
                easeEnvelope.trigger(1f)
            }
            AtmosphereEvent.FAILURE -> {
                // One direction per gust: chosen only when no gust is active, kept for retriggers while it lasts.
                if (gustEnvelope.phase == AdsrEnvelope.Phase.IDLE) gustDirection = if (random.nextBoolean()) 1f else -1f
                easeEnvelope.release()
                gustEnvelope.trigger(1f)
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
        if (bounds.width != lastWidth || bounds.height != lastHeight) {
            // The landing lines are tied to the frame size: start over with splashes instead of tracking them.
            changed = vanishAllSplashes(dirty, bounds)
        }
        if (populationChanged || bounds.width != lastWidth || bounds.height != lastHeight) {
            changed = resize(bounds, dirty) or changed
            populationChanged = false
        }

        // Smooth global modifiers.
        speedMultiplier = speedEnvelope.advance(deltaSeconds) * speedScale * flowBoost
        glow = easeEnvelope.advance(deltaSeconds)
        failureGlow = gustEnvelope.advance(deltaSeconds)

        // Shared breeze, a gentle sinusoid on top of the constant lean of the fall.
        windPhase += WIND_RATE * deltaSeconds * speedMultiplier
        if (windPhase > TWO_PI) windPhase -= TWO_PI
        val wind = sin(windPhase) * WIND_SPEED * uiScale
        val gust = gustDirection * failureGlow * GUST_SPEED * uiScale
        val fallScale = (1f - glow * EASE_SLOWDOWN) * (1f + failureGlow * FAILURE_FALL_BOOST)
        val lengthScale = (0.7f + 0.3f * speedMultiplier).coerceIn(0.8f, 1.6f) * (1f - glow * EASE_SHORTEN)
        val alphaScale = intensity * palette.opacityScale * (1f + FLASH_BOOST * failureGlow * failureGlow)
        val fadeStep = deltaSeconds / FADE_IN_SECONDS
        val sideMargin = SIDE_MARGIN * uiScale
        val topExit = by0 - SPAWN_BAND * uiScale - 1f

        for (i in 0 until particleCount) {
            val parallax = depthFactor(depth[i])

            // Motion: fall along the lean, breeze/gust with parallax, plus cursor steering.
            avoidance.advance(i, x[i], y[i], deltaSeconds, uiScale)
            val fall = fallSpeed[i] * fallScale * speedMultiplier
            vy[i] = fall + avoidance.velocityY(i)
            vx[i] = fall * LEAN + (wind + gust) * parallax * speedMultiplier + avoidance.velocityX(i)
            x[i] += vx[i] * deltaSeconds
            y[i] += vy[i] * deltaSeconds

            // The tapered tail trails behind the head along the velocity.
            val speed = sqrt(vx[i] * vx[i] + vy[i] * vy[i])
            val tailLength = length[i] * lengthScale
            if (speed > 0.001f) {
                tailX[i] = -vx[i] / speed * tailLength
                tailY[i] = -vy[i] / speed * tailLength
            } else {
                tailX[i] = 0f
                tailY[i] = -tailLength
            }

            // Landed, or left through the bottom, a side or (rarely, pushed up) above the spawn band: respawn on top.
            val landed = y[i] >= landY[i]
            val outside = x[i] < bx0 - sideMargin || x[i] > bx1 + sideMargin
            if (landed || outside || y[i] > by1 || y[i] < topExit) {
                if (landed && !outside && y[i] <= by1 && x[i] >= bx0 && x[i] <= bx1 && depth[i] >= SPLASH_MIN_DEPTH) {
                    startSplash(x[i], landY[i], depth[i], baseAlpha[i])
                }
                spawn(i, bx0, by0, bw, bh)
                changed = emitVanish(dirty, bounds, i) or changed
                continue
            }

            // Opacity: depth-graded base × fade-in × theme × intensity, brightened by a lightning flash.
            fadeIn[i] = min(1f, fadeIn[i] + fadeStep)
            alpha[i] = min(1f, baseAlpha[i] * alphaScale * fadeIn[i])

            val alphaDelta = abs(alphaIndex(alpha[i]) - alphaIndex(prevAlpha[i]))
            if (CommitPolicy.shouldCommit(x[i] - prevX[i], y[i] - prevY[i], alphaDelta, staleTicks[i])) {
                changed = emitDirty(dirty, bounds, i) or changed
            } else {
                staleTicks[i]++
            }
        }

        changed = updateSplashes(deltaSeconds, bounds, dirty, intensity * palette.opacityScale) or changed
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
            val hx = prevX[i]
            val hy = prevY[i]
            val ox = prevTailX[i]
            val oy = prevTailY[i]
            val pad = width[i] * 0.5f + 1f
            // Cheap AABB test against the clip, including the antialiasing fringe.
            if (max(hx, hx + ox) + pad < cx0 || min(hx, hx + ox) - pad > cx1 ||
                max(hy, hy + oy) + pad < cy0 || min(hy, hy + oy) - pad > cy1
            ) continue
            val l = sqrt(ox * ox + oy * oy)
            if (l < 0.01f) continue

            // Tapered streak: a triangle from the sharp tail to the full width at the head.
            val nx = -oy / l * width[i] * 0.5f
            val ny = ox / l * width[i] * 0.5f
            streak.reset()
            streak.moveTo(hx + ox, hy + oy)
            streak.lineTo(hx + nx, hy + ny)
            streak.lineTo(hx - nx, hy - ny)
            streak.closePath()
            g.color = colors[idx]
            g.fill(streak)
        }

        for (s in 0 until MAX_SPLASHES) {
            val a = prevSplashAlpha[s]
            if (a <= 0f) continue
            val idx = alphaIndex(a)
            if (idx == 0) continue
            val r = prevSplashRadius[s]
            val px = splashX[s]
            val py = splashY[s]
            if (px + r + 1f < cx0 || px - r - 1f > cx1 || py + r + 1f < cy0 || py - r - 1f > cy1) continue
            ellipse.setFrame(px - r, py - r * SPLASH_SQUASH, r * 2f, r * 2f * SPLASH_SQUASH)
            g.color = colors[idx]
            g.fill(ellipse)
        }
    }

    /**
     * Whether every drop still obeys the respawn rules, i.e. its head lies within [bounds] extended by
     * [SIDE_MARGIN] on the sides, by [SPAWN_BAND] above the top edge and by nothing below the bottom edge; test helper.
     */
    fun allInside(bounds: Rectangle): Boolean {
        val margin = SIDE_MARGIN * uiScale
        val topExit = bounds.y - SPAWN_BAND * uiScale - 1f
        val bottom = (bounds.y + bounds.height).toFloat()
        for (i in 0 until particleCount) {
            if (y[i] > bottom || y[i] < topExit || x[i] < bounds.x - margin || x[i] > bounds.x + bounds.width + margin) return false
        }
        return true
    }

    /** Mean horizontal velocity applied on the last [update] in px/s (positive = rightwards); test helper. */
    fun meanVelocityX(): Float = mean(vx)

    /** Mean vertical velocity applied on the last [update] in px/s (positive = downwards); test helper. */
    fun meanVelocityY(): Float = mean(vy)

    /**
     * Places drop [index] with its head at ([px], [py]) with the given on-screen [alpha], far from its landing line,
     * and commits it as painted state, so the next [paint] draws exactly one streak there. Test seam for rendering checks.
     */
    @TestOnly
    fun placeParticleForTest(index: Int, px: Float, py: Float, alpha: Float) {
        require(index in 0 until particleCount) { "index $index out of 0 until $particleCount" }
        x[index] = px
        y[index] = py
        landY[index] = py + 10_000f
        this.alpha[index] = alpha
        prevX[index] = px
        prevY[index] = py
        prevTailX[index] = tailX[index]
        prevTailY[index] = tailY[index]
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

    /** Adjusts the drop count to [bounds]; returns `true` if a region was reported for vanished drops. */
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
            spawn(i, bx, by, bw, bh)
            // Initial fill: distribute over the area above the landing line; the fade-in hides the arrival.
            y[i] = by + random.nextFloat() * (landY[i] - by)
            prevX[i] = x[i]
            prevY[i] = y[i]
            prevTailX[i] = tailX[i]
            prevTailY[i] = tailY[i]
            prevAlpha[i] = 0f
        }
        // Shrinking: drops beyond the new count vanish; their last painted area must be cleared.
        var changed = false
        for (i in target until previous) {
            changed = emitVanish(dirty, bounds, i) or changed
        }
        // Drops left outside the new bounds are respawned by the update pass that follows.
        return changed
    }

    /** Gives drop [i] a fresh depth, look and landing line and places it, invisible, in the spawn band above the top. */
    private fun spawn(i: Int, bx: Float, by: Float, bw: Float, bh: Float) {
        avoidance.reset(i)
        val t = random.nextFloat()
        // Biased toward far drops: a few long near drops read as accents rather than as a wall of lines.
        val d = t * sqrt(t)
        depth[i] = d
        val parallax = depthFactor(d)
        length[i] = (MIN_LENGTH + (MAX_LENGTH - MIN_LENGTH) * d) * uiScale
        width[i] = (MIN_WIDTH + (MAX_WIDTH - MIN_WIDTH) * d) * uiScale
        baseAlpha[i] = MIN_BASE_ALPHA + (MAX_BASE_ALPHA - MIN_BASE_ALPHA) * d
        fallSpeed[i] = (MIN_FALL_SPEED + random.nextFloat() * (MAX_FALL_SPEED - MIN_FALL_SPEED)) * uiScale * parallax
        x[i] = bx - SPAWN_SKEW * uiScale + random.nextFloat() * (bw + SPAWN_SKEW * uiScale)
        y[i] = by - random.nextFloat() * SPAWN_BAND * uiScale
        landY[i] = by + bh * (LAND_MIN + (1f - LAND_MIN) * random.nextFloat())
        // Resting orientation: along the constant lean of the fall.
        tailX[i] = -length[i] * LEAN_DIRECTION_X
        tailY[i] = -length[i] * LEAN_DIRECTION_Y
        fadeIn[i] = 0f
        alpha[i] = 0f
        staleTicks[i] = 0
    }

    /**
     * Reports `prev ∪ new` bounds of drop [i] (+ half the width and the antialiasing fringe) and commits the new
     * state as painted. Returns `false` when nothing on screen changes: the drop was invisible before and after,
     * or both shapes lie outside [bounds] (e.g. still descending through the spawn band).
     */
    private fun emitDirty(dirty: DirtyRegionCollector, bounds: Rectangle, i: Int): Boolean {
        val visible = prevAlpha[i] > 0f || alpha[i] > 0f
        val padding = width[i] * 0.5f + 1f
        val minX = min(min(prevX[i], prevX[i] + prevTailX[i]), min(x[i], x[i] + tailX[i])) - padding
        val minY = min(min(prevY[i], prevY[i] + prevTailY[i]), min(y[i], y[i] + tailY[i])) - padding
        val maxX = max(max(prevX[i], prevX[i] + prevTailX[i]), max(x[i], x[i] + tailX[i])) + padding
        val maxY = max(max(prevY[i], prevY[i] + prevTailY[i]), max(y[i], y[i] + tailY[i])) + padding
        commit(i)
        if (!visible || !intersects(bounds, minX, minY, maxX, maxY)) return false
        dirty.add(minX, minY, maxX - minX, maxY - minY)
        return true
    }

    /**
     * A (re)spawned drop starts invisible: only its previously painted streak needs clearing.
     * Returns `false` when nothing was on screen.
     */
    private fun emitVanish(dirty: DirtyRegionCollector, bounds: Rectangle, i: Int): Boolean {
        val padding = width[i] * 0.5f + 1f
        val minX = min(prevX[i], prevX[i] + prevTailX[i]) - padding
        val minY = min(prevY[i], prevY[i] + prevTailY[i]) - padding
        val maxX = max(prevX[i], prevX[i] + prevTailX[i]) + padding
        val maxY = max(prevY[i], prevY[i] + prevTailY[i]) + padding
        val visible = prevAlpha[i] > 0f && intersects(bounds, minX, minY, maxX, maxY)
        if (visible) dirty.add(minX, minY, maxX - minX, maxY - minY)
        commit(i)
        prevAlpha[i] = 0f
        return visible
    }

    private fun commit(i: Int) {
        prevX[i] = x[i]
        prevY[i] = y[i]
        prevTailX[i] = tailX[i]
        prevTailY[i] = tailY[i]
        prevAlpha[i] = alpha[i]
        staleTicks[i] = 0
    }

    /** Takes a free splash slot for a drop that landed at ([sx], [sy]); skips the splash when the pool is full. */
    private fun startSplash(sx: Float, sy: Float, depth: Float, dropAlpha: Float) {
        for (n in 0 until MAX_SPLASHES) {
            val s = (nextSplash + n) % MAX_SPLASHES
            if (splashActive[s]) continue
            nextSplash = (s + 1) % MAX_SPLASHES
            splashActive[s] = true
            splashX[s] = sx
            splashY[s] = sy
            splashAge[s] = 0f
            splashDuration[s] = MIN_SPLASH_SECONDS + random.nextFloat() * (MAX_SPLASH_SECONDS - MIN_SPLASH_SECONDS)
            splashMaxRadius[s] = (MIN_SPLASH_RADIUS + (MAX_SPLASH_RADIUS - MIN_SPLASH_RADIUS) * depth) * uiScale
            splashBaseAlpha[s] = dropAlpha * SPLASH_ALPHA
            splashRadius[s] = 0f
            splashAlpha[s] = 0f
            prevSplashRadius[s] = 0f
            prevSplashAlpha[s] = 0f
            splashCount++
            return
        }
    }

    private fun updateSplashes(deltaSeconds: Float, bounds: Rectangle, dirty: DirtyRegionCollector, alphaScale: Float): Boolean {
        var changed = false
        for (s in 0 until MAX_SPLASHES) {
            if (!splashActive[s]) continue
            splashAge[s] += deltaSeconds
            val t = splashAge[s] / splashDuration[s]
            if (t >= 1f) {
                changed = emitSplashVanish(dirty, bounds, s) or changed
                splashActive[s] = false
                splashCount--
                continue
            }
            val fade = 1f - t
            splashRadius[s] = splashMaxRadius[s] * (0.3f + 0.7f * t)
            splashAlpha[s] = min(1f, splashBaseAlpha[s] * alphaScale) * fade * fade
            val r = max(prevSplashRadius[s], splashRadius[s]) + 1f
            val visible = prevSplashAlpha[s] > 0f || splashAlpha[s] > 0f
            if (visible && intersects(bounds, splashX[s] - r, splashY[s] - r, splashX[s] + r, splashY[s] + r)) {
                dirty.add(splashX[s] - r, splashY[s] - r, r * 2f, r * 2f)
                changed = true
            }
            prevSplashRadius[s] = splashRadius[s]
            prevSplashAlpha[s] = splashAlpha[s]
        }
        return changed
    }

    /** Clears the painted area of splash [s]; returns `false` when nothing was on screen. */
    private fun emitSplashVanish(dirty: DirtyRegionCollector, bounds: Rectangle, s: Int): Boolean {
        val r = prevSplashRadius[s] + 1f
        val visible = prevSplashAlpha[s] > 0f &&
            intersects(bounds, splashX[s] - r, splashY[s] - r, splashX[s] + r, splashY[s] + r)
        if (visible) dirty.add(splashX[s] - r, splashY[s] - r, r * 2f, r * 2f)
        prevSplashAlpha[s] = 0f
        prevSplashRadius[s] = 0f
        return visible
    }

    private fun vanishAllSplashes(dirty: DirtyRegionCollector, bounds: Rectangle): Boolean {
        var changed = false
        for (s in 0 until MAX_SPLASHES) {
            if (!splashActive[s]) continue
            changed = emitSplashVanish(dirty, bounds, s) or changed
            splashActive[s] = false
        }
        splashCount = 0
        return changed
    }

    private fun clearSplashes() {
        splashActive.fill(false)
        prevSplashAlpha.fill(0f)
        prevSplashRadius.fill(0f)
        splashCount = 0
        nextSplash = 0
    }

    private fun intersects(bounds: Rectangle, x0: Float, y0: Float, x1: Float, y1: Float): Boolean =
        x1 >= bounds.x && y1 >= bounds.y && x0 <= bounds.x + bounds.width && y0 <= bounds.y + bounds.height

    // Plain AWT colors on purpose: theme awareness comes from the palette, and JBColor would re-resolve
    // the LaF on every access inside the paint loop.
    @Suppress("UseJBColor")
    private fun rebuildColorTable() {
        val c = if (palette.isDark) DARK_DROP_COLOR else LIGHT_DROP_COLOR
        colors[0] = TRANSPARENT
        for (i in 1 until ALPHA_STEPS) {
            val a = i / (ALPHA_STEPS - 1).toFloat()
            colors[i] = Color(c.red, c.green, c.blue, (a * 255f + 0.5f).toInt().coerceIn(0, 255))
        }
        colorTableRebuilds++
    }

    companion object {
        const val ID: String = "rain"

        const val MIN_PARTICLES: Int = 20
        const val MAX_PARTICLES: Int = 70

        /** Size of the splash pool; together with [MAX_PARTICLES] it bounds the regions reported per tick. */
        const val MAX_SPLASHES: Int = 32

        /** User-space pixels per drop at intensity 1 (≈ 70 on a 1512×949 frame). */
        const val AREA_PER_PARTICLE: Int = 20_000

        /** Streak length and width ranges in user px (× uiScale); nearer drops are longer and thicker. */
        const val MIN_LENGTH: Float = 14f
        const val MAX_LENGTH: Float = 38f
        const val MIN_WIDTH: Float = 0.8f
        const val MAX_WIDTH: Float = 1.8f

        /** Base opacity range; nearer drops are brighter. */
        const val MIN_BASE_ALPHA: Float = 0.15f
        const val MAX_BASE_ALPHA: Float = 0.45f

        /** Fall speed range in px/s (× uiScale × [depthFactor]); nearer drops are faster. */
        const val MIN_FALL_SPEED: Float = 320f
        const val MAX_FALL_SPEED: Float = 520f

        /** Constant lean of the fall: sideways velocity as a fraction of the fall velocity (rightwards). */
        const val LEAN: Float = 0.12f

        /** Peak velocity of the shared breeze in px/s (× uiScale) and its angular rate in rad/s. */
        const val WIND_SPEED: Float = 25f
        const val WIND_RATE: Float = 0.2f

        /** Height in user px (× uiScale) of the band above the top edge in which drops (re)spawn. */
        const val SPAWN_BAND: Float = 60f

        /** Extra width in user px (× uiScale) left of the frame in which drops spawn, compensating the lean. */
        const val SPAWN_SKEW: Float = 100f

        /** Distance in user px (× uiScale) outside the side edges at which a drop is respawned. */
        const val SIDE_MARGIN: Float = 160f
        const val FADE_IN_SECONDS: Float = 0.3f

        /** Lowest landing line as a fraction of the frame height; the landing line of a drop is random below it. */
        const val LAND_MIN: Float = 0.3f

        /** Only drops at least this near leave a splash. */
        const val SPLASH_MIN_DEPTH: Float = 0.25f
        const val MIN_SPLASH_RADIUS: Float = 2f
        const val MAX_SPLASH_RADIUS: Float = 6f
        const val MIN_SPLASH_SECONDS: Float = 0.3f
        const val MAX_SPLASH_SECONDS: Float = 0.45f

        /** Splash opacity relative to the opacity of the drop that made it, and its height relative to its width. */
        const val SPLASH_ALPHA: Float = 0.8f
        const val SPLASH_SQUASH: Float = 0.35f

        const val IDLE_SPEED: Float = 0.5f
        const val NORMAL_SPEED: Float = 1f
        const val ACTIVITY_SPEED: Float = 1.4f

        /** Success: the rain eases — peak slowdown and shortening of the streaks, and envelope timings. */
        const val EASE_SLOWDOWN: Float = 0.55f
        const val EASE_SHORTEN: Float = 0.3f
        const val EASE_ATTACK_SECONDS: Float = 0.3f
        const val EASE_DECAY_SECONDS: Float = 1.5f

        /**
         * Failure: peak sideways gust in px/s (× uiScale), faster fall, brightness flash (as opacity, relative to the
         * normal opacity of a drop; quadratic in the envelope so it is brief) and envelope timings.
         */
        const val GUST_SPEED: Float = 140f
        const val FAILURE_FALL_BOOST: Float = 0.3f
        const val FLASH_BOOST: Float = 1.2f
        const val GUST_ATTACK_SECONDS: Float = 0.12f
        const val GUST_DECAY_SECONDS: Float = 1.6f

        /**
         * Number of alpha steps in the cached color table (8-bit resolution, so fades never step visibly);
         * also the alpha resolution of dirty reporting.
         */
        const val ALPHA_STEPS: Int = 256

        /** Drop color on dark themes: cool blue-white. */
        @Suppress("UseJBColor")
        val DARK_DROP_COLOR: Color = Color(170, 200, 235)

        /** Drop color on light themes: slate blue; alpha is further reduced by [AtmospherePalette.opacityScale]. */
        @Suppress("UseJBColor")
        val LIGHT_DROP_COLOR: Color = Color(70, 100, 150)

        /** Easing applied when the opposite outcome cuts an active reaction short. */
        private const val OUTCOME_RELEASE_SECONDS = 0.4f
        private const val TWO_PI = (Math.PI * 2).toFloat()
        private val LEAN_NORM = sqrt(1f + LEAN * LEAN)
        private val LEAN_DIRECTION_X = LEAN / LEAN_NORM
        private val LEAN_DIRECTION_Y = 1f / LEAN_NORM
        @Suppress("UseJBColor")
        private val TRANSPARENT = Color(0, 0, 0, 0)

        /** `clamp(area / (AREA_PER_PARTICLE * (1/intensity)), 20, 70)`. */
        @JvmStatic
        fun particleCountFor(width: Int, height: Int, intensity: Float = 1f): Int {
            val areaPerParticle = AREA_PER_PARTICLE * (1f / intensity)
            return (width.toLong() * height / areaPerParticle).toInt().coerceIn(MIN_PARTICLES, MAX_PARTICLES)
        }

        /** Motion parallax: far drops (depth 0) move at half the speed of near drops (depth 1). */
        private fun depthFactor(depth: Float): Float = 0.5f + 0.5f * depth

        private fun alphaIndex(a: Float): Int = (a * (ALPHA_STEPS - 1) + 0.5f).toInt().coerceIn(0, ALPHA_STEPS - 1)
    }
}
