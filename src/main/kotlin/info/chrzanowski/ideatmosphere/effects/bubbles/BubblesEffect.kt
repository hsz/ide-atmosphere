package info.chrzanowski.ideatmosphere.effects.bubbles

import info.chrzanowski.ideatmosphere.core.AtmospherePalette
import info.chrzanowski.ideatmosphere.core.EffectContext
import info.chrzanowski.ideatmosphere.effects.common.DriftingParticleEffect
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.geom.Ellipse2D
import kotlin.math.max

/**
 * Bubbles rising through still water — the opposite of the falling atmospheres: thin rings with a small glint,
 * wobbling from side to side as they rise, the larger ones faster. They are born below the window and fade out
 * near the end of their life as if popping.
 *
 * A success sends them up in a quick rush; a failure pushes them sideways in one current and dims them briefly.
 * Everything else comes from [DriftingParticleEffect].
 */
class BubblesEffect : DriftingParticleEffect(TUNING) {

    override val id: String get() = ID

    // Reused geometry; mutated in paint only (EDT). The stroke depends on the UI scale, so it is built in start().
    private val ellipse = Ellipse2D.Float()
    private var ring = BasicStroke(1f)

    override fun start(context: EffectContext) {
        super.start(context)
        ring = BasicStroke(max(1f, RING_WIDTH * context.uiScale))
    }

    override fun spawn(i: Int, bx: Float, by: Float, bw: Float, bh: Float, initial: Boolean) {
        val t = random.nextFloat()
        size[i] = (MIN_SIZE + t * (MAX_SIZE - MIN_SIZE)) * uiScale
        x[i] = bx + random.nextFloat() * bw
        y[i] = if (initial) by + random.nextFloat() * bh else by + bh + size[i] + 1f
        // Larger bubbles rise faster, with a little individual spread.
        drift[i] = -(MIN_RISE_SPEED + (0.6f * t + 0.4f * random.nextFloat()) * (MAX_RISE_SPEED - MIN_RISE_SPEED)) * uiScale
        swayRate[i] = 1.2f + random.nextFloat() * 1.4f
        swayAmplitude[i] = (MIN_WOBBLE + random.nextFloat() * (MAX_WOBBLE - MIN_WOBBLE)) * uiScale
    }

    override fun isOutside(i: Int, bx: Float, by: Float, bw: Float, bh: Float): Boolean {
        val margin = EDGE_MARGIN * uiScale + size[i]
        return y[i] < by - size[i] - 1f || y[i] > by + bh + margin || x[i] < bx - margin || x[i] > bx + bw + margin
    }

    override fun boundingRadius(i: Int): Float = size[i] + ring.lineWidth

    override fun paintParticle(g: Graphics2D, i: Int, px: Float, py: Float, rot: Float, color: Color) {
        val r = size[i]
        g.color = color
        val previousStroke = g.stroke
        g.stroke = ring
        ellipse.setFrame(px - r, py - r, r * 2f, r * 2f)
        g.draw(ellipse)
        g.stroke = previousStroke
        // A small glint on the upper left, as light catches the bubble.
        val glint = r * 0.28f
        ellipse.setFrame(px - r * 0.45f - glint, py - r * 0.45f - glint, glint * 2f, glint * 2f)
        g.fill(ellipse)
    }

    @Suppress("UseJBColor")
    override fun baseColor(palette: AtmospherePalette, variant: Int): Color =
        if (palette.isDark) Color(170, 215, 240) else Color(70, 130, 175)

    override val minBaseAlpha: Float get() = 0.35f
    override val maxBaseAlpha: Float get() = 0.7f

    companion object {
        const val ID: String = "bubbles"

        const val MIN_PARTICLES: Int = 15
        const val MAX_PARTICLES: Int = 45
        /** User-space pixels per bubble (≈ 18 on a 1512×949 frame). */
        const val AREA_PER_PARTICLE: Int = 80_000

        const val MIN_RISE_SPEED: Float = 18f
        const val MAX_RISE_SPEED: Float = 42f
        const val MIN_SIZE: Float = 3f
        const val MAX_SIZE: Float = 8f
        const val MIN_WOBBLE: Float = 8f
        const val MAX_WOBBLE: Float = 18f
        const val RING_WIDTH: Float = 1.2f

        private const val EDGE_MARGIN = 20f

        private val TUNING = Tuning(
            minParticles = MIN_PARTICLES,
            maxParticles = MAX_PARTICLES,
            areaPerParticle = AREA_PER_PARTICLE,
            idleSpeed = 0.4f,
            activitySpeed = 1.6f,
            liftSpeed = 110f,
            liftFlutterBoost = 1.2f,
            gustImpulseMin = 40f,
            gustImpulseMax = 70f,
        )

        fun particleCountFor(width: Int, height: Int, intensity: Float = 1f): Int =
            countFor(TUNING, width, height, intensity)
    }
}
