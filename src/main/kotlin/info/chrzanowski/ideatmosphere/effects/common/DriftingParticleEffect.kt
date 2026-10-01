package info.chrzanowski.ideatmosphere.effects.common

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
import java.util.SplittableRandom
import org.jetbrains.annotations.TestOnly
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Shared simulation for atmospheres made of independent drifting particles (autumn leaves, bubbles, dust motes).
 *
 * The base owns everything the atmospheres have in common and that [info.chrzanowski.ideatmosphere.effects.EffectSettingsTest]
 * holds every effect to: population per area and intensity, the speed / idle / busy / paused envelope, the one-shot
 * success updraft and failure gust with brief dimming, pointer avoidance / attraction, click puffs, scroll gusts, the
 * caret anchor, typing flow and adaptive dirty reporting ([CommitPolicy]). Subclasses only decide where particles are
 * born ([spawn]), how they move on their own ([driftX] / [driftY]), when they leave the window ([isOutside]) and how
 * they look ([paintParticle], [baseColor]).
 *
 * Layout is structure-of-arrays sized to [Tuning.maxParticles]; [update] and [paint] allocate nothing. Outcomes are
 * motion and brightness only: colors never change.
 */
abstract class DriftingParticleEffect(protected val tuning: Tuning) : AtmosphereEffect {

    /** Per-atmosphere constants; all speeds and sizes are user-space pixels at `uiScale == 1`. */
    class Tuning(
        val minParticles: Int,
        val maxParticles: Int,
        /** User-space pixels per particle at intensity `1`. */
        val areaPerParticle: Int,
        val idleSpeed: Float = 0.4f,
        val activitySpeed: Float = 1.6f,
        /** Peak upward speed of the success updraft at `glow == 1`. */
        val liftSpeed: Float = 100f,
        /** Extra sway / spin rate at `glow == 1`. */
        val liftFlutterBoost: Float = 1f,
        /** Fraction of alpha removed at `failureGlow == 1`. */
        val failureAlphaDim: Float = 0.25f,
        val gustImpulseMin: Float = 40f,
        val gustImpulseMax: Float = 80f,
        /** How quickly (per second) a gust or puff impulse decays back to the particle's own drift. */
        val gustDamping: Float = 0.8f,
        val fadeInSeconds: Float = 2f,
        val fadeOutSeconds: Float = 3f,
        /** Ratio between the deepest and the brightest point of the per-particle alpha pulse (`1` = no pulse). */
        val pulseDepth: Float = 0.1f,
    )

    protected lateinit var random: SplittableRandom
    protected var uiScale: Float = 1f
        private set
    protected var palette: AtmospherePalette = AtmospherePalette.DARK
        private set

    private var intensity = 1f
    private var speedScale = 1f
    private var flowBoost = 1f
    private var populationChanged = false

    private val capacity = tuning.maxParticles

    protected val x = FloatArray(capacity)
    protected val y = FloatArray(capacity)
    /** Transient horizontal impulse (gusts, failure); damped back to zero by [Tuning.gustDamping]. */
    protected val impulseX = FloatArray(capacity)
    /** The particle's own vertical drift speed (positive falls, negative rises), already scaled by `uiScale`. */
    protected val drift = FloatArray(capacity)
    /** The particle's own horizontal drift speed, already scaled by `uiScale`. */
    protected val wander = FloatArray(capacity)
    protected val swayPhase = FloatArray(capacity)
    protected val swayRate = FloatArray(capacity)
    /** Sway amplitude in px/s, already scaled by `uiScale`. */
    protected val swayAmplitude = FloatArray(capacity)
    protected val rotation = FloatArray(capacity)
    protected val angularVelocity = FloatArray(capacity)
    /** Particle radius-like size, already scaled by `uiScale`. */
    protected val size = FloatArray(capacity)
    /** Free per-particle variant (shape / tone); never changes while the particle lives. */
    protected val variant = IntArray(capacity)
    private val baseAlpha = FloatArray(capacity)
    private val alpha = FloatArray(capacity)
    private val pulsePhase = FloatArray(capacity)
    private val pulseRate = FloatArray(capacity)
    private val life = FloatArray(capacity)
    private val maxLife = FloatArray(capacity)

    // Last *painted* state.
    private val prevX = FloatArray(capacity)
    private val prevY = FloatArray(capacity)
    private val prevRotation = FloatArray(capacity)
    private val prevAlpha = FloatArray(capacity)
    private val staleTicks = IntArray(capacity)

    private val colors = arrayOfNulls<Color>(ALPHA_STEPS * MAX_COLOR_VARIANTS)
    private val cursorAvoidance = CursorAvoidance(capacity)

    // Horizontal direction (+1/-1) of the running gust; chosen once per gust so overlapping failures never cancel.
    private var gustDirection = 0f

    private var started = false
    private var lastWidth = -1
    private var lastHeight = -1

    /** Number of live particles for the current bounds. */
    var particleCount: Int = 0
        private set

    /** Current smoothed speed multiplier (envelope × speed setting × typing flow). */
    var speedMultiplier: Float = 1f
        private set

    /** Updraft strength 0..1 after [AtmosphereEvent.SUCCESS]; never affects colors. */
    var glow: Float = 0f
        private set

    /** Dimming 0..1 after [AtmosphereEvent.FAILURE]. */
    var failureGlow: Float = 0f
        private set

    private val speedEnvelope = AdsrEnvelope(
        restLevel = NORMAL_SPEED,
        attackSeconds = 1.0f,
        decaySeconds = 1.5f,
        sustainRatio = 1.0f,
        releaseSeconds = 2.0f,
    )

    private val glowEnvelope = AdsrEnvelope(
        restLevel = 0f,
        attackSeconds = 0.2f,
        decaySeconds = 1.6f,
        sustainRatio = 0f,
        releaseSeconds = 0f,
        oneShot = true,
    )

    private val failureEnvelope = AdsrEnvelope(
        restLevel = 0f,
        attackSeconds = 0.2f,
        decaySeconds = 2.0f,
        sustainRatio = 0f,
        releaseSeconds = 0f,
        oneShot = true,
    )

    // ---- Subclass hooks -------------------------------------------------------------------------------------------

    /**
     * Initialises particle [i] inside bounds (`bx`, `by`, `bw`, `bh`): position, [drift], [wander], sway, rotation,
     * [size] and [variant]. [initial] is `true` while the field is first populated (particles may appear anywhere),
     * `false` when a particle is reborn after it left (it should enter from the side it naturally comes from).
     */
    protected abstract fun spawn(i: Int, bx: Float, by: Float, bw: Float, bh: Float, initial: Boolean)

    /** `true` once particle [i] has left the bounds for good and should be reborn. */
    protected abstract fun isOutside(i: Int, bx: Float, by: Float, bw: Float, bh: Float): Boolean

    /** Paints particle [i] at (`px`, `py`) with turn [rot] in [color]; must not allocate or change the transform/clip. */
    protected abstract fun paintParticle(g: Graphics2D, i: Int, px: Float, py: Float, rot: Float, color: Color)

    /** Base color of the atmosphere for [palette]; alpha is applied by the base. */
    protected abstract fun baseColor(palette: AtmospherePalette, variant: Int): Color

    /** Number of color variants [baseColor] distinguishes (each gets its own alpha table). */
    protected open val colorVariants: Int get() = 1

    /** Radius around the centre that covers everything [paintParticle] draws for particle [i]. */
    protected open fun boundingRadius(i: Int): Float = size[i]

    /** Own horizontal velocity of particle [i] (px/s) before the speed multiplier; default: wander plus sway. */
    protected open fun driftX(i: Int): Float = wander[i] + sin(swayPhase[i]) * swayAmplitude[i]

    /** Own vertical velocity of particle [i] (px/s) before the speed multiplier; default: its [drift]. */
    protected open fun driftY(i: Int): Float = drift[i]

    /** Initial base alpha range of a particle (before intensity and palette opacity). */
    protected open val minBaseAlpha: Float get() = 0.3f
    protected open val maxBaseAlpha: Float get() = 0.6f

    /** Lifetime range in seconds. */
    protected open val minLife: Float get() = 10f
    protected open val maxLifeSeconds: Float get() = 25f

    // ---- AtmosphereEffect -----------------------------------------------------------------------------------------

    override fun start(context: EffectContext) {
        random = context.random
        uiScale = context.uiScale
        palette = context.palette
        rebuildColorTables()
        particleCount = 0
        lastWidth = -1
        lastHeight = -1
        speedMultiplier = speedScale * flowBoost
        glow = 0f
        failureGlow = 0f
        gustDirection = 0f
        cursorAvoidance.clear()
        speedEnvelope.release()
        speedEnvelope.advance(100f)
        settleOutcomes()
        started = true
    }

    override fun stop() {
        started = false
        particleCount = 0
        lastWidth = -1
        lastHeight = -1
        cursorAvoidance.clear()
        settleOutcomes()
        glow = 0f
        failureGlow = 0f
        gustDirection = 0f
    }

    private fun settleOutcomes() {
        glowEnvelope.release()
        glowEnvelope.advance(100f)
        failureEnvelope.release()
        failureEnvelope.advance(100f)
    }

    override fun onPaletteChanged(palette: AtmospherePalette) {
        this.palette = palette
        rebuildColorTables()
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

    override fun onPointerChanged(x: Float, y: Float, inside: Boolean) = cursorAvoidance.setPointer(x, y, inside)

    override fun onPointerPressed(x: Float, y: Float) =
        cursorAvoidance.burst(x, y, uiScale, particleCount, this.x, this.y)

    override fun onScroll(x: Float, y: Float, amount: Float) =
        cursorAvoidance.gust(x, y, 0f, if (amount > 0f) -1f else 1f, abs(amount), uiScale, particleCount, this.x, this.y)

    override fun setAnchor(x: Float, y: Float, active: Boolean) = cursorAvoidance.setAnchor(x, y, active)

    override fun setPointerMode(mode: PointerMode) {
        cursorAvoidance.mode = mode
    }

    override fun onEvent(event: AtmosphereEvent) {
        when (event) {
            AtmosphereEvent.USER_IDLE -> speedEnvelope.trigger(tuning.idleSpeed)
            AtmosphereEvent.EXECUTION_PAUSED -> speedEnvelope.trigger(tuning.idleSpeed * AtmosphereEffect.PAUSED_SPEED_FACTOR)
            AtmosphereEvent.USER_ACTIVE -> speedEnvelope.release()
            AtmosphereEvent.ACTIVITY_STARTED -> speedEnvelope.trigger(tuning.activitySpeed)
            AtmosphereEvent.ACTIVITY_FINISHED -> speedEnvelope.release()
            AtmosphereEvent.SUCCESS -> {
                glowEnvelope.trigger(1f)
                failureEnvelope.release()
                failureEnvelope.advance(100f)
            }
            AtmosphereEvent.FAILURE -> {
                failureEnvelope.trigger(1f)
                glowEnvelope.release()
                glowEnvelope.advance(100f)
                if (started) gust()
            }
        }
    }

    override fun update(deltaSeconds: Float, bounds: Rectangle, dirty: DirtyRegionCollector): Boolean {
        if (!started || !deltaSeconds.isFinite() || deltaSeconds <= 0f || bounds.width <= 0 || bounds.height <= 0) return false
        val bx = bounds.x.toFloat()
        val by = bounds.y.toFloat()
        val bw = bounds.width.toFloat()
        val bh = bounds.height.toFloat()

        var changed = false
        if (populationChanged || bounds.width != lastWidth || bounds.height != lastHeight) {
            changed = resize(bounds, dirty)
            populationChanged = false
        }

        speedMultiplier = speedEnvelope.advance(deltaSeconds) * speedScale * flowBoost
        glow = glowEnvelope.advance(deltaSeconds)
        failureGlow = failureEnvelope.advance(deltaSeconds)
        if (failureEnvelope.phase == AdsrEnvelope.Phase.IDLE) gustDirection = 0f
        val lift = glow * tuning.liftSpeed * uiScale
        val flutter = 1f + glow * tuning.liftFlutterBoost
        val failureDim = 1f - failureGlow * tuning.failureAlphaDim
        val opacityScale = palette.opacityScale
        val damp = min(1f, deltaSeconds * tuning.gustDamping)

        for (i in 0 until particleCount) {
            life[i] += deltaSeconds
            if (life[i] >= maxLife[i] || isOutside(i, bx, by, bw, bh)) {
                val padding = boundingRadius(i) + 1f
                respawn(i, bx, by, bw, bh, initial = false)
                changed = emitVanish(dirty, i, padding) or changed
                continue
            }

            swayPhase[i] += swayRate[i] * deltaSeconds * speedMultiplier * flutter
            impulseX[i] -= impulseX[i] * damp
            cursorAvoidance.advance(i, x[i], y[i], deltaSeconds, uiScale)
            x[i] += (driftX(i) * speedMultiplier + impulseX[i] + cursorAvoidance.velocityX(i)) * deltaSeconds
            y[i] += (driftY(i) * speedMultiplier - lift + cursorAvoidance.velocityY(i)) * deltaSeconds
            rotation[i] += angularVelocity[i] * speedMultiplier * flutter * deltaSeconds

            pulsePhase[i] += pulseRate[i] * deltaSeconds
            val pulse = 1f - tuning.pulseDepth * 0.5f * (1f - sin(pulsePhase[i]))
            alpha[i] = min(1f, baseAlpha[i] * intensity * pulse * lifeEnvelope(life[i], maxLife[i]) * opacityScale) * failureDim

            val turn = abs(rotation[i] - prevRotation[i]) * boundingRadius(i)
            val dx = max(abs(x[i] - prevX[i]), turn)
            val alphaDelta = abs(alphaIndex(alpha[i]) - alphaIndex(prevAlpha[i]))
            if (CommitPolicy.shouldCommit(dx, y[i] - prevY[i], alphaDelta, staleTicks[i])) {
                changed = emitDirty(dirty, i, boundingRadius(i) + 1f) or changed
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
            val px = prevX[i]
            val py = prevY[i]
            val br = boundingRadius(i)
            if (px + br < cx0 || px - br > cx1 || py + br < cy0 || py - br > cy1) continue
            val tone = variant[i].mod(colorVariants)
            paintParticle(g, i, px, py, prevRotation[i], colors[tone * ALPHA_STEPS + idx] ?: continue)
        }
    }

    /** Expected population for a [width] × [height] frame at [intensity]. */
    fun particleCountFor(width: Int, height: Int, intensity: Float = 1f): Int = countFor(tuning, width, height, intensity)

    // ---- Internals ------------------------------------------------------------------------------------------------

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
            respawn(i, bx, by, bw, bh, initial = true)
            prevX[i] = x[i]
            prevY[i] = y[i]
            prevRotation[i] = rotation[i]
            prevAlpha[i] = 0f
            staleTicks[i] = 0
        }
        var changed = false
        for (i in target until previous) {
            changed = emitVanish(dirty, i, boundingRadius(i) + 1f) or changed
        }
        return changed
    }

    private fun respawn(i: Int, bx: Float, by: Float, bw: Float, bh: Float, initial: Boolean) {
        cursorAvoidance.reset(i)
        staleTicks[i] = 0
        impulseX[i] = 0f
        wander[i] = 0f
        swayPhase[i] = random.nextFloat() * TWO_PI
        swayRate[i] = 0.5f + random.nextFloat()
        swayAmplitude[i] = 0f
        rotation[i] = 0f
        angularVelocity[i] = 0f
        variant[i] = 0
        spawn(i, bx, by, bw, bh, initial)
        baseAlpha[i] = minBaseAlpha + random.nextFloat() * (maxBaseAlpha - minBaseAlpha)
        pulsePhase[i] = random.nextFloat() * TWO_PI
        pulseRate[i] = 0.2f + random.nextFloat() * 0.4f
        maxLife[i] = minLife + random.nextFloat() * (maxLifeSeconds - minLife)
        life[i] = if (initial) random.nextFloat() * maxLife[i] else 0f
        alpha[i] = 0f
    }

    private fun gust() {
        val impulse = (tuning.gustImpulseMin + random.nextFloat() * (tuning.gustImpulseMax - tuning.gustImpulseMin)) * uiScale
        if (gustDirection == 0f) gustDirection = if (random.nextBoolean()) 1f else -1f
        for (i in 0 until particleCount) impulseX[i] += gustDirection * impulse * (0.8f + random.nextFloat() * 0.4f)
    }

    private fun emitDirty(dirty: DirtyRegionCollector, i: Int, padding: Float): Boolean {
        staleTicks[i] = 0
        if (prevAlpha[i] <= 0f && alpha[i] <= 0f) {
            prevX[i] = x[i]
            prevY[i] = y[i]
            prevRotation[i] = rotation[i]
            return false
        }
        val minX = min(prevX[i], x[i]) - padding
        val minY = min(prevY[i], y[i]) - padding
        val maxX = max(prevX[i], x[i]) + padding
        val maxY = max(prevY[i], y[i]) + padding
        dirty.add(minX, minY, maxX - minX, maxY - minY, 0f)
        prevX[i] = x[i]
        prevY[i] = y[i]
        prevRotation[i] = rotation[i]
        prevAlpha[i] = alpha[i]
        return true
    }

    private fun emitVanish(dirty: DirtyRegionCollector, i: Int, padding: Float): Boolean {
        val visible = prevAlpha[i] > 0f
        // The painted footprint belongs to the *previous* state, so measure it before forgetting it.
        if (visible) dirty.add(prevX[i] - padding, prevY[i] - padding, padding * 2f, padding * 2f, 0f)
        prevX[i] = x[i]
        prevY[i] = y[i]
        prevRotation[i] = rotation[i]
        prevAlpha[i] = 0f
        staleTicks[i] = 0
        return visible
    }

    private fun rebuildColorTables() {
        check(colorVariants in 1..MAX_COLOR_VARIANTS) { "Unsupported number of color variants: $colorVariants" }
        for (tone in 0 until colorVariants) {
            val base = baseColor(palette, tone)
            val offset = tone * ALPHA_STEPS
            @Suppress("UseJBColor")
            colors[offset] = Color(0, 0, 0, 0)
            for (step in 1 until ALPHA_STEPS) {
                val a = step / (ALPHA_STEPS - 1).toFloat()
                @Suppress("UseJBColor")
                colors[offset + step] = Color(base.red, base.green, base.blue, (a * 255f + 0.5f).toInt().coerceIn(0, 255))
            }
        }
    }

    /** Places particle [index] at (`px`, `py`) with [alpha] as if it had just been painted there; test helper. */
    @TestOnly
    fun placeParticleForTest(index: Int, px: Float, py: Float, alpha: Float) {
        require(index in 0 until particleCount)
        x[index] = px
        y[index] = py
        this.alpha[index] = alpha
        prevX[index] = px
        prevY[index] = py
        prevRotation[index] = rotation[index]
        prevAlpha[index] = alpha
        staleTicks[index] = 0
    }

    /** Horizontal direction of the running gust: `1` right, `-1` left, `0` when no gust is active. */
    @TestOnly
    fun gustDirectionForTest(): Float = gustDirection

    private fun lifeEnvelope(life: Float, maxLife: Float): Float {
        val fadeIn = min(1f, life / tuning.fadeInSeconds)
        val fadeOut = min(1f, (maxLife - life) / tuning.fadeOutSeconds)
        return max(0f, min(fadeIn, fadeOut))
    }

    companion object {
        const val ALPHA_STEPS: Int = 256
        const val NORMAL_SPEED: Float = 1f
        /** Upper bound for [colorVariants]; the alpha tables are preallocated for this many tones. */
        const val MAX_COLOR_VARIANTS: Int = 4

        protected const val TWO_PI: Float = 6.2831855f

        /** Population of an atmosphere with [tuning] on a [width] × [height] frame at [intensity]. */
        @JvmStatic
        fun countFor(tuning: Tuning, width: Int, height: Int, intensity: Float = 1f): Int {
            val areaPerParticle = tuning.areaPerParticle * (1f / intensity)
            return (width.toLong() * height / areaPerParticle).toInt().coerceIn(tuning.minParticles, tuning.maxParticles)
        }

        private fun alphaIndex(a: Float): Int = (a * (ALPHA_STEPS - 1) + 0.5f).toInt().coerceIn(0, ALPHA_STEPS - 1)
    }
}
