package info.chrzanowski.ideatmosphere.effects.sakura

import info.chrzanowski.ideatmosphere.core.AdsrEnvelope
import info.chrzanowski.ideatmosphere.core.AtmosphereEffect
import info.chrzanowski.ideatmosphere.core.AtmosphereEvent
import info.chrzanowski.ideatmosphere.core.AtmospherePalette
import info.chrzanowski.ideatmosphere.core.AtmosphereReactionProfile
import info.chrzanowski.ideatmosphere.core.EffectContext
import info.chrzanowski.ideatmosphere.render.DirtyRegionCollector
import java.awt.Color
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.geom.Path2D
import java.util.SplittableRandom
import org.jetbrains.annotations.TestOnly
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Drifting, rotating sakura petals.
 *
 * Petals follow a downward fall with a horizontal breeze (sinusoidal sway) and gentle tumble (rotation).
 * The architecture mirrors [info.chrzanowski.ideatmosphere.effects.fireflies.FirefliesEffect] using
 * structure-of-arrays to avoid per-tick allocations.
 *
 * Rendering uses a single reused [Path2D.Float] rebuilt via manual trigonometry per petal to support
 * rotation without [java.awt.geom.AffineTransform] allocations.
 */
class SakuraEffect : AtmosphereEffect {

    override val id: String get() = ID

    private lateinit var random: SplittableRandom
    private var uiScale = 1f
    private var palette: AtmospherePalette = AtmospherePalette.DARK

    private var intensity: Float = 1f
    private var speedScale: Float = 1f
    private var populationChanged = false

    private val x = FloatArray(MAX_PARTICLES)
    private val y = FloatArray(MAX_PARTICLES)
    private val vx = FloatArray(MAX_PARTICLES) // Base horizontal drift
    private val fallSpeed = FloatArray(MAX_PARTICLES)
    private val swayPhase = FloatArray(MAX_PARTICLES)
    private val swayRate = FloatArray(MAX_PARTICLES)
    private val rotation = FloatArray(MAX_PARTICLES)
    private val angularVelocity = FloatArray(MAX_PARTICLES)
    private val size = FloatArray(MAX_PARTICLES)
    private val alpha = FloatArray(MAX_PARTICLES)
    private val baseAlpha = FloatArray(MAX_PARTICLES)
    private val phase = FloatArray(MAX_PARTICLES)
    private val pulseRate = FloatArray(MAX_PARTICLES)
    private val life = FloatArray(MAX_PARTICLES)
    private val maxLife = FloatArray(MAX_PARTICLES)

    // Last *painted* state for dirty region reporting.
    private val prevX = FloatArray(MAX_PARTICLES)
    private val prevY = FloatArray(MAX_PARTICLES)
    private val prevRotation = FloatArray(MAX_PARTICLES)
    private val prevAlpha = FloatArray(MAX_PARTICLES)

    private val colors = arrayOfNulls<Color>(ALPHA_STEPS)

    // Single reused path for petal drawing; mutated in paint only (EDT).
    private val path = Path2D.Float()

    /** Number of live particles for the current bounds. */
    var particleCount: Int = 0
        private set

    /** Current smoothed speed multiplier (driven by [AdsrEnvelope]). */
    var speedMultiplier: Float = 1f
        private set

    /** Extra brightness 0..1 added after [AtmosphereEvent.SUCCESS] (driven by [AdsrEnvelope]). */
    var glow: Float = 0f
        private set

    private val speedEnvelope = AdsrEnvelope(
        restLevel = NORMAL_SPEED,
        attackSeconds = 1.0f, // Gentler than fireflies
        decaySeconds = 1.5f,
        sustainRatio = 1.0f,
        releaseSeconds = 2.0f
    )

    private val glowEnvelope = AdsrEnvelope(
        restLevel = 0f,
        attackSeconds = 0.2f,
        decaySeconds = 2.0f,
        sustainRatio = 0f,
        releaseSeconds = 0f,
        oneShot = true,
    )

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
        speedMultiplier = speedScale
        glow = 0f
        speedEnvelope.release()
        speedEnvelope.advance(100f)
        glowEnvelope.release()
        glowEnvelope.advance(100f)
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

    override fun setIntensity(scale: Float) {
        val value = if (scale.isFinite()) scale.coerceIn(0.3f, 2f) else 1f
        if (intensity == value) return
        intensity = value
        populationChanged = true
    }

    override fun setSpeedScale(scale: Float) {
        speedScale = if (scale.isFinite()) scale.coerceIn(0.3f, 2f) else 1f
        speedMultiplier = speedEnvelope.value * speedScale
    }

    override fun onReactionProfileChanged(profile: AtmosphereReactionProfile) {
        val normalized = profile.normalized(AtmosphereReactionProfile.sakura())
        speedEnvelope.configure(normalized.movement)
        glowEnvelope.configure(normalized.successPulse)
    }

    override fun onEvent(event: AtmosphereEvent) {
        when (event) {
            AtmosphereEvent.USER_IDLE -> speedEnvelope.trigger(IDLE_SPEED)
            AtmosphereEvent.USER_ACTIVE -> speedEnvelope.release()
            AtmosphereEvent.ACTIVITY_STARTED -> speedEnvelope.trigger(ACTIVITY_SPEED)
            AtmosphereEvent.ACTIVITY_FINISHED -> speedEnvelope.release()
            AtmosphereEvent.SUCCESS -> glowEnvelope.trigger(1f)
            AtmosphereEvent.FAILURE -> gust()
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

        speedMultiplier = speedEnvelope.advance(deltaSeconds) * speedScale
        glow = glowEnvelope.advance(deltaSeconds)
        val glowBoost = 1f + glow * GLOW_ALPHA_BOOST
        val opacityScale = palette.opacityScale

        for (i in 0 until particleCount) {
            life[i] += deltaSeconds
            // Respawn if life ends, falls off bottom, or blown out horizontally.
            if (life[i] >= maxLife[i] || y[i] > by + bh + size[i] * BOUNDING_RADIUS_FACTOR ||
                x[i] < bx - EDGE_MARGIN * uiScale || x[i] > bx + bw + EDGE_MARGIN * uiScale) {
                val padding = boundingRadius(i) + 1f
                spawn(i, bx, by, bw, bh)
                changed = emitVanish(dirty, i, padding) or changed
                continue
            }

            // Motion
            swayPhase[i] += swayRate[i] * deltaSeconds * speedMultiplier
            val sway = sin(swayPhase[i]) * SWAY_AMPLITUDE * uiScale * speedMultiplier
            
            // Damping of gust impulse back to baseline drift
            val damp = min(1f, deltaSeconds * GUST_DAMPING)
            vx[i] += (0f - vx[i]) * damp

            x[i] += (vx[i] + sway) * deltaSeconds
            y[i] += fallSpeed[i] * speedMultiplier * deltaSeconds
            rotation[i] += angularVelocity[i] * speedMultiplier * deltaSeconds

            // Visuals
            phase[i] += pulseRate[i] * deltaSeconds
            val pulse = 0.9f + 0.1f * sin(phase[i])
            val envelope = lifeEnvelope(life[i], maxLife[i])
            alpha[i] = min(1f, baseAlpha[i] * intensity * pulse * envelope * opacityScale * glowBoost)

            // Dirty reporting
            val moved = abs(x[i] - prevX[i]) >= MOVE_EPSILON || abs(y[i] - prevY[i]) >= MOVE_EPSILON
            // For rotation, we use a simple epsilon. A rotation of R moves a point at radius S by approx S*R.
            val rotated = abs(rotation[i] - prevRotation[i]) * size[i] >= MOVE_EPSILON
            val faded = alphaIndex(alpha[i]) != alphaIndex(prevAlpha[i])

            if (moved || rotated || faded) {
                changed = emitDirty(dirty, i, boundingRadius(i) + 1f) or changed
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
            val pr = prevRotation[i]
            val s = size[i]
            val br = s * BOUNDING_RADIUS_FACTOR

            if (px + br < cx0 || px - br > cx1 || py + br < cy0 || py - br > cy1) continue

            // Manual trig for rotated petal
            val cosR = cos(pr)
            val sinR = sin(pr)

            path.reset()
            for (j in PETAL_VERTICES.indices) {
                val vx = PETAL_VERTICES[j][0]
                val vy = PETAL_VERTICES[j][1]
                val wx = px + (vx * cosR - vy * sinR) * s
                val wy = py + (vx * sinR + vy * cosR) * s
                if (j == 0) path.moveTo(wx, wy) else path.lineTo(wx, wy)
            }
            path.closePath()

            g.color = colors[idx]
            g.fill(path)
        }
    }

    /** Whether every live particle lies within the horizontal and bottom vertical bounds; test helper. */
    fun allInside(bounds: Rectangle): Boolean {
        for (i in 0 until particleCount) {
            if (x[i] < bounds.x - EDGE_MARGIN * uiScale || x[i] > bounds.x + bounds.width + EDGE_MARGIN * uiScale ||
                y[i] > bounds.y + bounds.height + size[i] * BOUNDING_RADIUS_FACTOR
            ) return false
        }
        return true
    }

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
            spawnInitial(i, bx, by, bw, bh)
            prevX[i] = x[i]
            prevY[i] = y[i]
            prevRotation[i] = rotation[i]
            prevAlpha[i] = 0f
        }
        var changed = false
        for (i in target until previous) {
            changed = emitVanish(dirty, i, boundingRadius(i) + 1f) or changed
        }
        return changed
    }

    private fun spawnInitial(i: Int, bx: Float, by: Float, bw: Float, bh: Float) {
        spawn(i, bx, by, bw, bh)
        // For initial spawn, distribute life and y position
        life[i] = random.nextFloat() * maxLife[i]
        y[i] = by + random.nextFloat() * bh
    }

    private fun spawn(i: Int, bx: Float, by: Float, bw: Float, bh: Float) {
        x[i] = bx + random.nextFloat() * bw
        y[i] = by - EDGE_MARGIN * uiScale // Spawn above top
        vx[i] = (random.nextFloat() * 2f - 1f) * 5f * uiScale // Small initial horizontal drift
        fallSpeed[i] = (MIN_FALL_SPEED + random.nextFloat() * (MAX_FALL_SPEED - MIN_FALL_SPEED)) * uiScale
        swayPhase[i] = random.nextFloat() * TWO_PI
        swayRate[i] = 0.5f + random.nextFloat() * 1.0f
        rotation[i] = random.nextFloat() * TWO_PI
        angularVelocity[i] = (random.nextFloat() * 2f - 1f) * MAX_ANGULAR_VELOCITY
        size[i] = (MIN_SIZE + random.nextFloat() * (MAX_SIZE - MIN_SIZE)) * uiScale
        baseAlpha[i] = MIN_BASE_ALPHA + random.nextFloat() * (MAX_BASE_ALPHA - MIN_BASE_ALPHA)
        phase[i] = random.nextFloat() * TWO_PI
        pulseRate[i] = 0.2f + random.nextFloat() * 0.4f
        life[i] = 0f
        maxLife[i] = MIN_LIFE + random.nextFloat() * (MAX_LIFE - MIN_LIFE)
        alpha[i] = 0f
    }

    private fun gust() {
        val impulse = (GUST_IMPULSE_MIN + random.nextFloat() * (GUST_IMPULSE_MAX - GUST_IMPULSE_MIN)) * uiScale
        val direction = if (random.nextBoolean()) 1f else -1f
        for (i in 0 until particleCount) {
            vx[i] += direction * impulse * (0.8f + random.nextFloat() * 0.4f)
        }
    }

    private fun emitDirty(dirty: DirtyRegionCollector, i: Int, padding: Float): Boolean {
        if (prevAlpha[i] <= 0f && alpha[i] <= 0f) {
            prevX[i] = x[i]
            prevY[i] = y[i]
            prevRotation[i] = rotation[i]
            return false
        }
        // For a rotating shape, the bounds are not just the center point.
        // We use the bounding radius to cover the rotated shape.
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
        if (visible) {
            dirty.add(prevX[i] - padding, prevY[i] - padding, padding * 2f, padding * 2f, 0f)
        }
        prevX[i] = x[i]
        prevY[i] = y[i]
        prevRotation[i] = rotation[i]
        prevAlpha[i] = 0f
        return visible
    }

    private fun boundingRadius(i: Int): Float = size[i] * BOUNDING_RADIUS_FACTOR

    @Suppress("UseJBColor")
    private fun rebuildColorTables() {
        // Sakura Pink: Dark theme uses a slightly more vibrant pink, Light theme a softer one.
        val baseColor = if (palette.isDark) Color(255, 180, 200) else Color(255, 220, 230)
        
        colors[0] = Color(0, 0, 0, 0)
        for (i in 1 until ALPHA_STEPS) {
            val a = i / (ALPHA_STEPS - 1).toFloat()
            colors[i] = Color(baseColor.red, baseColor.green, baseColor.blue, (a * 255f + 0.5f).toInt().coerceIn(0, 255))
        }
    }

    @TestOnly
    fun placeParticleForTest(index: Int, px: Float, py: Float, rotation: Float, alpha: Float) {
        require(index in 0 until particleCount)
        x[index] = px
        y[index] = py
        this.rotation[index] = rotation
        this.alpha[index] = alpha
        prevX[index] = px
        prevY[index] = py
        prevRotation[index] = rotation
        prevAlpha[index] = alpha
    }

    @TestOnly
    fun colorTableSizeForTest(): Int = colors.count { it != null }

    companion object {
        const val ID: String = "sakura"

        const val MAX_PARTICLES: Int = 50
        const val MIN_PARTICLES: Int = 15
        /**
         * User-space pixels per petal (≈ 16 on a 1512×949 frame). Raised from 70 000 to 90 000 in Step 15.
         */
        const val AREA_PER_PARTICLE: Int = 90_000

        const val MIN_FALL_SPEED: Float = 15f
        const val MAX_FALL_SPEED: Float = 35f
        const val MIN_SIZE: Float = 4f
        const val MAX_SIZE: Float = 8f
        const val MIN_LIFE: Float = 10f
        const val MAX_LIFE: Float = 25f
        const val FADE_IN_SECONDS: Float = 2f
        const val FADE_OUT_SECONDS: Float = 3f

        const val IDLE_SPEED: Float = 0.4f
        const val NORMAL_SPEED: Float = 1f
        const val ACTIVITY_SPEED: Float = 1.6f

        const val ALPHA_STEPS: Int = 256
        const val MOVE_EPSILON: Float = 0.02f
        
        const val SWAY_AMPLITUDE: Float = 20f
        const val MAX_ANGULAR_VELOCITY: Float = 1.5f
        const val GUST_DAMPING: Float = 0.8f
        const val GUST_IMPULSE_MIN: Float = 40f
        const val GUST_IMPULSE_MAX: Float = 80f
        const val GLOW_ALPHA_BOOST: Float = 0.4f

        private const val TWO_PI = (Math.PI * 2).toFloat()
        private const val EDGE_MARGIN = 20f
        private const val MIN_BASE_ALPHA = 0.3f
        private const val MAX_BASE_ALPHA = 0.6f
        
        /** Petal vertices in unit space (relative to center). Almond shape. */
        private val PETAL_VERTICES = arrayOf(
            floatArrayOf(0f, -1f),
            floatArrayOf(0.5f, -0.2f),
            floatArrayOf(0.5f, 0.2f),
            floatArrayOf(0f, 1f),
            floatArrayOf(-0.5f, 0.2f),
            floatArrayOf(-0.5f, -0.2f)
        )
        const val BOUNDING_RADIUS_FACTOR: Float = 1.0f

        @JvmStatic
        fun particleCountFor(width: Int, height: Int, intensity: Float = 1f): Int {
            val areaPerParticle = AREA_PER_PARTICLE * (1f / intensity)
            return (width.toLong() * height / areaPerParticle).toInt().coerceIn(MIN_PARTICLES, MAX_PARTICLES)
        }

        private fun lifeEnvelope(life: Float, maxLife: Float): Float {
            val fadeIn = min(1f, life / FADE_IN_SECONDS)
            val fadeOut = min(1f, (maxLife - life) / FADE_OUT_SECONDS)
            return max(0f, min(fadeIn, fadeOut))
        }

        private fun alphaIndex(a: Float): Int = (a * (ALPHA_STEPS - 1) + 0.5f).toInt().coerceIn(0, ALPHA_STEPS - 1)
    }
}
