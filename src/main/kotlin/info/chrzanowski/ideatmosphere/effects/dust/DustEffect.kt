package info.chrzanowski.ideatmosphere.effects.dust

import info.chrzanowski.ideatmosphere.core.AtmospherePalette
import info.chrzanowski.ideatmosphere.effects.common.DriftingParticleEffect
import java.awt.Color
import java.awt.Graphics2D
import java.awt.geom.Ellipse2D
import kotlin.math.cos

/**
 * Dust motes floating in a beam of afternoon light: tiny specks hanging almost still, drifting a few pixels a second
 * in lazy loops and shimmering as they turn in and out of the light. Motes are born and fade out wherever they are,
 * so the field never streams in one direction.
 *
 * A success lifts them in a warm updraft; a failure stirs them sideways and dims them briefly. Everything else comes
 * from [DriftingParticleEffect].
 */
class DustEffect : DriftingParticleEffect(TUNING) {

    override val id: String get() = ID

    // Reused geometry; mutated in paint only (EDT).
    private val ellipse = Ellipse2D.Float()

    override fun spawn(i: Int, bx: Float, by: Float, bw: Float, bh: Float, initial: Boolean) {
        size[i] = (MIN_SIZE + random.nextFloat() * (MAX_SIZE - MIN_SIZE)) * uiScale
        // Motes are born where they are (fading in), never streaming in from an edge.
        x[i] = bx + random.nextFloat() * bw
        y[i] = by + random.nextFloat() * bh
        drift[i] = (random.nextFloat() * 2f - 1f) * MAX_DRIFT * uiScale
        wander[i] = (random.nextFloat() * 2f - 1f) * MAX_DRIFT * uiScale
        swayRate[i] = 0.3f + random.nextFloat() * 0.5f
        swayAmplitude[i] = (MIN_LOOP + random.nextFloat() * (MAX_LOOP - MIN_LOOP)) * uiScale
    }

    /** Lazy loops: the vertical half of the sway, a quarter turn behind the horizontal one. */
    override fun driftY(i: Int): Float = drift[i] + cos(swayPhase[i] * 0.7f) * swayAmplitude[i] * 0.6f

    override fun isOutside(i: Int, bx: Float, by: Float, bw: Float, bh: Float): Boolean {
        val margin = EDGE_MARGIN * uiScale + size[i]
        return x[i] < bx - margin || x[i] > bx + bw + margin || y[i] < by - margin || y[i] > by + bh + margin
    }

    override fun boundingRadius(i: Int): Float = size[i] + 0.5f

    override fun paintParticle(g: Graphics2D, i: Int, px: Float, py: Float, rot: Float, color: Color) {
        val r = size[i]
        ellipse.setFrame(px - r, py - r, r * 2f, r * 2f)
        g.color = color
        g.fill(ellipse)
    }

    @Suppress("UseJBColor")
    override fun baseColor(palette: AtmospherePalette, variant: Int): Color =
        if (palette.isDark) Color(245, 228, 190) else Color(140, 118, 84)

    override val minBaseAlpha: Float get() = 0.3f
    override val maxBaseAlpha: Float get() = 0.65f
    override val minLife: Float get() = 8f
    override val maxLifeSeconds: Float get() = 20f

    companion object {
        const val ID: String = "dust"

        const val MIN_PARTICLES: Int = 25
        const val MAX_PARTICLES: Int = 50
        /** User-space pixels per mote (≈ 32 on a 1512×949 frame). */
        const val AREA_PER_PARTICLE: Int = 45_000

        const val MIN_SIZE: Float = 0.9f
        const val MAX_SIZE: Float = 2.2f
        const val MAX_DRIFT: Float = 4f
        const val MIN_LOOP: Float = 4f
        const val MAX_LOOP: Float = 10f

        private const val EDGE_MARGIN = 20f

        private val TUNING = Tuning(
            minParticles = MIN_PARTICLES,
            maxParticles = MAX_PARTICLES,
            areaPerParticle = AREA_PER_PARTICLE,
            idleSpeed = 0.5f,
            activitySpeed = 1.8f,
            liftSpeed = 90f,
            liftFlutterBoost = 1.5f,
            gustImpulseMin = 35f,
            gustImpulseMax = 65f,
            fadeInSeconds = 2.5f,
            fadeOutSeconds = 2.5f,
            // Motes shimmer as they turn in the light.
            pulseDepth = 0.5f,
        )

        fun particleCountFor(width: Int, height: Int, intensity: Float = 1f): Int =
            countFor(TUNING, width, height, intensity)
    }
}
