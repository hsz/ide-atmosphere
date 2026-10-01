package info.chrzanowski.ideatmosphere.settings

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import info.chrzanowski.ideatmosphere.core.AtmosphereEffect
import info.chrzanowski.ideatmosphere.core.EffectContext
import info.chrzanowski.ideatmosphere.core.PointerMode
import info.chrzanowski.ideatmosphere.effects.AtmosphereEffects
import info.chrzanowski.ideatmosphere.render.DirtyRegionCollector
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.JPanel
import javax.swing.Timer

/**
 * A small live preview of an atmosphere on the settings page: runs its own effect instance with the drafted intensity
 * and speed, so slider changes are visible before Apply. It never touches the real frames or the shared ticker.
 *
 * The animation timer only runs while the panel is showing (between [addNotify] and [removeNotify]); the whole panel
 * is repainted per tick, which is cheap at this size. The pointer is ignored so the preview stays a calm sample.
 */
class AtmospherePreviewPanel(
    /** Creates the effect for an id; replaceable so tests can count instances. */
    private val factory: (String) -> AtmosphereEffect = AtmosphereEffects::createOrDefault,
) : JPanel() {

    /** Id of the atmosphere currently previewed. */
    var effectId: String = AtmosphereEffects.DEFAULT_ID
        private set

    /** Live effect, or `null` while the panel is not showing. */
    var effect: AtmosphereEffect? = null
        private set

    private var intensity = 1f
    private var speed = 1f
    private var lastTickNanos = 0L
    private val bounds = Rectangle()
    private val dirty = DirtyRegionCollector(100)
    private val timer = Timer(TICK_MILLIS) { tick() }

    init {
        isOpaque = true
        name = "atmospherePreview"
        border = JBUI.Borders.customLine(JBColor.border())
        preferredSize = JBUI.size(PREFERRED_WIDTH, PREFERRED_HEIGHT)
        minimumSize = Dimension(JBUI.scale(120), JBUI.scale(60))
    }

    /** Shows [id] with [intensity] and [speed]; restarts the effect only when the atmosphere itself changes. */
    fun configure(id: String, intensity: Float, speed: Float) {
        this.intensity = intensity
        this.speed = speed
        if (id != effectId) {
            effectId = id
            if (effect != null) restart()
        }
        effect?.let(::applyScales)
    }

    /** `true` while the preview animates. */
    val isAnimating: Boolean get() = timer.isRunning

    override fun addNotify() {
        super.addNotify()
        restart()
        timer.start()
    }

    override fun removeNotify() {
        timer.stop()
        effect?.stop()
        effect = null
        super.removeNotify()
    }

    private fun restart() {
        effect?.stop()
        effect = factory(effectId).also {
            it.start(EffectContext.fromCurrentLaf())
            it.setPointerMode(PointerMode.OFF)
            applyScales(it)
        }
        lastTickNanos = 0L
    }

    private fun applyScales(effect: AtmosphereEffect) {
        effect.setIntensity(intensity)
        effect.setSpeedScale(speed)
    }

    /** Advances the simulation by the elapsed time (capped) and repaints; called by the timer. */
    internal fun tick() {
        val current = effect ?: return
        val now = System.nanoTime()
        val dt = if (lastTickNanos == 0L) TICK_MILLIS / 1000f else ((now - lastTickNanos) / 1e9f).coerceAtMost(MAX_DT)
        lastTickNanos = now
        val insets = insets
        bounds.setBounds(0, 0, width - insets.left - insets.right, height - insets.top - insets.bottom)
        if (bounds.width <= 0 || bounds.height <= 0) return
        dirty.clear()
        current.update(dt, bounds, dirty)
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        val insets = insets
        g.color = background
        g.fillRect(0, 0, width, height)
        val current = effect ?: return
        val g2 = g.create(insets.left, insets.top, width - insets.left - insets.right, height - insets.top - insets.bottom) as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            current.paint(g2, g2.clipBounds ?: Rectangle(0, 0, width, height))
        } finally {
            g2.dispose()
        }
    }

    companion object {
        private const val TICK_MILLIS = 40
        private const val MAX_DT = 0.1f
        private const val PREFERRED_WIDTH = 420
        private const val PREFERRED_HEIGHT = 120
    }
}
