package info.chrzanowski.ideatmosphere.effects.autumn

import info.chrzanowski.ideatmosphere.core.AtmospherePalette
import info.chrzanowski.ideatmosphere.effects.common.DriftingParticleEffect
import java.awt.Color
import java.awt.Graphics2D
import java.awt.geom.Path2D
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Autumn leaves swinging down like a pendulum: each leaf sways from side to side, falls fastest in the middle of its
 * swing and hangs for a moment at either end, while slowly turning. Leaves come in a few close warm tones (amber,
 * rust, ochre) so the field reads as one colour from a distance.
 *
 * A success lifts the leaves in an updraft; a failure sends one gust through them and dims them briefly. Everything
 * else (population, pointer, caret, reactions, dirty reporting) comes from [DriftingParticleEffect].
 */
class AutumnLeavesEffect : DriftingParticleEffect(TUNING) {

    override val id: String get() = ID

    // Single reused path for leaf drawing; mutated in paint only (EDT).
    private val path = Path2D.Float()

    override val colorVariants: Int get() = 3

    override fun spawn(i: Int, bx: Float, by: Float, bw: Float, bh: Float, initial: Boolean) {
        size[i] = (MIN_SIZE + random.nextFloat() * (MAX_SIZE - MIN_SIZE)) * uiScale
        x[i] = bx + random.nextFloat() * bw
        y[i] = if (initial) by + random.nextFloat() * bh else by - size[i] - 1f
        drift[i] = (MIN_FALL_SPEED + random.nextFloat() * (MAX_FALL_SPEED - MIN_FALL_SPEED)) * uiScale
        wander[i] = (random.nextFloat() * 2f - 1f) * 4f * uiScale
        swayRate[i] = 0.6f + random.nextFloat() * 0.6f
        swayAmplitude[i] = (MIN_SWAY + random.nextFloat() * (MAX_SWAY - MIN_SWAY)) * uiScale
        rotation[i] = random.nextFloat() * TWO_PI
        angularVelocity[i] = (random.nextFloat() * 2f - 1f) * MAX_ANGULAR_VELOCITY
        variant[i] = random.nextInt(3)
    }

    /** Falls fastest through the middle of the swing and almost hangs at either end of it. */
    override fun driftY(i: Int): Float = drift[i] * (0.45f + 0.55f * abs(cos(swayPhase[i])))

    override fun isOutside(i: Int, bx: Float, by: Float, bw: Float, bh: Float): Boolean {
        val margin = EDGE_MARGIN * uiScale + size[i]
        return y[i] > by + bh + size[i] || x[i] < bx - margin || x[i] > bx + bw + margin
    }

    override fun paintParticle(g: Graphics2D, i: Int, px: Float, py: Float, rot: Float, color: Color) {
        val cosR = cos(rot)
        val sinR = sin(rot)
        val s = size[i]
        path.reset()
        var j = 0
        while (j < LEAF_VERTICES.size) {
            val vx = LEAF_VERTICES[j]
            val vy = LEAF_VERTICES[j + 1]
            val wx = px + (vx * cosR - vy * sinR) * s
            val wy = py + (vx * sinR + vy * cosR) * s
            if (j == 0) path.moveTo(wx, wy) else path.lineTo(wx, wy)
            j += 2
        }
        path.closePath()
        g.color = color
        g.fill(path)
    }

    @Suppress("UseJBColor")
    override fun baseColor(palette: AtmospherePalette, variant: Int): Color = if (palette.isDark) {
        when (variant) {
            0 -> Color(214, 120, 56)
            1 -> Color(200, 100, 50)
            else -> Color(222, 140, 64)
        }
    } else {
        when (variant) {
            0 -> Color(176, 92, 40)
            1 -> Color(160, 72, 36)
            else -> Color(186, 112, 48)
        }
    }

    companion object {
        const val ID: String = "autumn"

        const val MIN_PARTICLES: Int = 12
        const val MAX_PARTICLES: Int = 40
        /** User-space pixels per leaf (≈ 14 on a 1512×949 frame). */
        const val AREA_PER_PARTICLE: Int = 100_000

        const val MIN_FALL_SPEED: Float = 22f
        const val MAX_FALL_SPEED: Float = 45f
        const val MIN_SIZE: Float = 6f
        const val MAX_SIZE: Float = 10f
        const val MIN_SWAY: Float = 25f
        const val MAX_SWAY: Float = 45f
        const val MAX_ANGULAR_VELOCITY: Float = 1.2f

        private const val EDGE_MARGIN = 20f

        private val TUNING = Tuning(
            minParticles = MIN_PARTICLES,
            maxParticles = MAX_PARTICLES,
            areaPerParticle = AREA_PER_PARTICLE,
            idleSpeed = 0.4f,
            activitySpeed = 1.6f,
            liftSpeed = 100f,
            liftFlutterBoost = 1f,
            gustImpulseMin = 50f,
            gustImpulseMax = 90f,
        )

        /** Leaf outline in unit space around its centre (x, y pairs): pointed tip, broad middle, short stem. */
        private val LEAF_VERTICES = floatArrayOf(
            0f, -1f,
            0.38f, -0.55f,
            0.55f, -0.05f,
            0.38f, 0.45f,
            0.06f, 0.75f,
            0.04f, 1f,
            -0.04f, 1f,
            -0.06f, 0.75f,
            -0.38f, 0.45f,
            -0.55f, -0.05f,
            -0.38f, -0.55f,
        )

        fun particleCountFor(width: Int, height: Int, intensity: Float = 1f): Int =
            countFor(TUNING, width, height, intensity)
    }
}
