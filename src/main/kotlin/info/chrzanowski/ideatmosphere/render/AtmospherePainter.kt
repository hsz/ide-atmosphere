package info.chrzanowski.ideatmosphere.render

import com.intellij.openapi.ui.AbstractPainter
import info.chrzanowski.ideatmosphere.core.AtmosphereEffect
import java.awt.AlphaComposite
import java.awt.Component
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints

/**
 * Glass-pane [com.intellij.openapi.ui.Painter] that composites an [AtmosphereEffect] over one frame.
 *
 * Semantics that matter for `PainterHelper`:
 * - [needsRepaint] is overridden to return [active]; the platform paints only painters whose flag is `true`
 *   and `AbstractPainter.paint` resets its own flag before delegating, so the inherited flag is never used.
 * - `setNeedsRepaint(true)` must never be called for animation: it triggers a full glass-pane `repaint()`.
 *   Repaints are issued by `FrameAtmosphere` for merged dirty regions only.
 * - Coordinates are root-pane user space (`component == null` on registration yields zero offsets).
 *
 * Nested batch painting (see `docs/overlay-investigation.md` §3/§6): `FrameAtmosphere` paints the first dirty
 * rectangle of a tick with `paintImmediately` and arms the one-shot [nestedPaint] hook beforehand. When this
 * painter has finished painting the effect for that rectangle (depth 1) it consumes the hook, which paints the
 * remaining rectangles re-entrantly. Those nested paints reach the painter at depth 2 where only the effect is
 * painted; the hook is never fired again and re-entry beyond [MAX_DEPTH] is ignored. Because `RepaintManager`
 * reference-counts nested `paintImmediately` calls, every rectangle of the batch shares one back-buffer flush.
 *
 * [opacity] fades the whole effect in and out (application (de)activation): values below `1` are applied through a
 * cached [AlphaComposite] table (restored after painting) and `0` skips the effect entirely. The nested hook is
 * consumed regardless of the opacity so a batch armed by `FrameAtmosphere` keeps its single-flush semantics.
 *
 * The hot path is allocation-free: the clip is read into a reused [Rectangle], antialiasing hints are
 * saved/restored by hand instead of allocating a `GraphicsConfig` per paint and composites come from the table.
 */
class AtmospherePainter(private val effect: AtmosphereEffect) : AbstractPainter() {

    /**
     * `true` while the atmosphere is attached and should be composited. Flipped to `false` synchronously
     * on detach because the platform removes painters asynchronously (`invokeLater`).
     */
    @Volatile
    var active: Boolean = false

    /**
     * One-shot hook run exactly once, at depth 1, after the effect has been painted and the hints restored.
     * Cleared before it runs so a re-entrant paint triggered by the hook itself cannot fire it again.
     */
    var nestedPaint: Runnable? = null

    /** Current paint nesting depth: 0 idle, 1 outer paint, 2 nested batch paint. */
    var depth: Int = 0
        private set

    /** Effect opacity in `[0, 1]`; `1` paints the effect as is, `0` paints nothing. Quantised to [OPACITY_STEPS]. */
    var opacity: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    private val clip = Rectangle()

    /** Number of times [executePaint] actually delegated to the effect (diagnostics/tests). */
    var paintCount: Long = 0
        private set

    override fun needsRepaint(): Boolean = active

    override fun executePaint(component: Component, g: Graphics2D) {
        if (!active || depth >= MAX_DEPTH) return
        depth++
        try {
            // Swing always paints with a clip; an empty result only means "no user clip" when `g.clip` is null
            // (`getClip()` is checked last because it may allocate when a clip is set).
            g.getClipBounds(clip)
            if (clip.isEmpty && g.clip == null) {
                clip.setBounds(0, 0, component.width, component.height)
            }
            if (clip.isEmpty) return

            val oldAntialiasing = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING)
            val oldStrokeControl = g.getRenderingHint(RenderingHints.KEY_STROKE_CONTROL)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            val oldComposite = g.composite
            try {
                val step = (opacity * OPACITY_STEPS + 0.5f).toInt()
                if (step > 0) {
                    if (step < OPACITY_STEPS) g.composite = COMPOSITES[step]
                    paintCount++
                    effect.paint(g, clip)
                }
            } finally {
                g.composite = oldComposite
                g.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    oldAntialiasing ?: RenderingHints.VALUE_ANTIALIAS_DEFAULT,
                )
                g.setRenderingHint(
                    RenderingHints.KEY_STROKE_CONTROL,
                    oldStrokeControl ?: RenderingHints.VALUE_STROKE_DEFAULT,
                )
            }
            if (depth == 1) {
                val hook = nestedPaint
                nestedPaint = null
                hook?.run()
            }
        } finally {
            depth--
        }
    }

    companion object {
        /** Outer paint plus exactly one level of nested batch paints. */
        const val MAX_DEPTH: Int = 2

        /** Number of opacity steps in the composite table (33 entries: `0/32 .. 32/32`). */
        const val OPACITY_STEPS: Int = 32

        private val COMPOSITES: Array<AlphaComposite> = Array(OPACITY_STEPS + 1) { k ->
            AlphaComposite.getInstance(AlphaComposite.SRC_OVER, k / OPACITY_STEPS.toFloat())
        }
    }
}
