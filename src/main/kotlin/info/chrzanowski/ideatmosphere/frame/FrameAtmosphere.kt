package info.chrzanowski.ideatmosphere.frame

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.IdeGlassPaneUtil
import info.chrzanowski.ideatmosphere.core.AtmosphereEffect
import info.chrzanowski.ideatmosphere.core.AtmosphereEvent
import info.chrzanowski.ideatmosphere.core.AtmospherePalette
import info.chrzanowski.ideatmosphere.core.EffectContext
import info.chrzanowski.ideatmosphere.render.AtmospherePainter
import info.chrzanowski.ideatmosphere.render.DirtyRegionCollector
import java.awt.Frame
import java.awt.Rectangle
import javax.swing.JComponent
import javax.swing.JRootPane
import javax.swing.SwingUtilities
import kotlin.math.max
import kotlin.math.min

/**
 * One atmosphere instance bound to one frame: owns the [AtmospherePainter] registered on the frame's
 * `IdeGlassPane`, drives the [effect] simulation and issues the merged-region repaints.
 *
 * Repaint routing (see `docs/overlay-investigation.md` §6):
 * - the part of a dirty rectangle that lies inside the content pane is queued and painted synchronously via
 *   `contentPane.paintImmediately` (the content pane is the only opaque ancestor, so this is the only
 *   synchronous region paint available);
 * - the remainder (header band above the content pane, or any other strip outside it) goes through
 *   `rootPane.repaint(rect)` immediately, which AWT coalesces asynchronously.
 *
 * Content rectangles are painted as one *nested single-flush batch*: the first rectangle is painted directly with
 * the painter's one-shot `nestedPaint` hook armed; once the painter has drawn the effect for that rectangle (paint
 * depth 1) the hook paints rectangles `2..n` re-entrantly (depth 2). `RepaintManager` reference-counts nested
 * `paintImmediately` calls, so the whole batch shares a single back-buffer flush (≈0.12 ms per rectangle instead
 * of ≈0.28 ms). If the hook never fires — frame not showing, region obscured, painter detached mid-tick — the
 * rectangles still pending after the outer call returns are painted directly (the result is identical, only
 * slower). [lastNestedRects] / [lastDirectRects] make the split observable in the stats.
 *
 * Fade and dormancy: [setVisible] eases the painter's opacity between `1` and `0` over [FADE_SECONDS]. While fading,
 * a tick still advances the effect (particles keep drifting) but paints the whole content pane plus the header band
 * once instead of the dirty rectangles, because every pixel of the overlay changes alpha. When the fade-out is complete
 * the frame is [isDormant]: [tick] returns immediately, nothing is simulated or painted, and the ticker's owner may
 * stop the timer. [lastTickWasFade] lets the ticker keep those whole-window paints out of the governor's cost model.
 * A [paused] frame (Power Save) does not fade either; the fade resumes when it is un-paused. A frame that is not
 * paintable (hidden, iconified) has nothing on screen to fade, so its opacity snaps to the target instead — a hidden
 * window therefore becomes dormant on the first tick after deactivation.
 *
 * All methods must be called on the EDT. [tick] is invoked by the ticker from a Swing timer, never from inside a
 * paint; the only re-entrancy is the deliberate, bounded batch described above.
 */
class FrameAtmosphere(
    val rootPane: JRootPane,
    val effect: AtmosphereEffect,
    private val context: EffectContext,
    maxParticles: Int = DEFAULT_MAX_PARTICLES,
    private val target: RepaintTarget = SwingRepaintTarget(rootPane),
) {

    /** Abstraction over the two repaint channels so routing can be verified without a showing window. */
    interface RepaintTarget {
        /** `true` when the frame can be painted: showing and not iconified. */
        val isPaintable: Boolean

        /** Origin of the content pane in root-pane coordinates plus its size; `false` if there is no content pane. */
        fun contentBounds(out: Rectangle): Boolean

        /** Synchronous paint of a region given in root-pane coordinates fully inside the content pane. */
        fun paintContentNow(x: Int, y: Int, width: Int, height: Int)

        /** Asynchronous repaint of a region given in root-pane coordinates (header band and other strips). */
        fun repaintRoot(x: Int, y: Int, width: Int, height: Int)
    }

    val painter: AtmospherePainter = AtmospherePainter(effect)
    val collector: DirtyRegionCollector = DirtyRegionCollector(capacity = 2 * maxParticles)

    /** When `true` the simulation is frozen and no repaints are issued (Power Save, hidden frame). */
    var paused: Boolean = false

    val isAttached: Boolean get() = attached

    /** Rectangles issued during the last [tick] (both channels). */
    var lastRectCount: Int = 0
        private set

    /** User-space pixels covered by the rectangles issued during the last [tick]. */
    var lastPaintedPixels: Long = 0
        private set

    /** Content rectangles painted from inside the nested hook during the last [tick]. */
    var lastNestedRects: Int = 0
        private set

    /** Content rectangles painted directly during the last [tick] (the batch head plus any fallback paints). */
    var lastDirectRects: Int = 0
        private set

    /** `true` when the last [tick] was a fade step (whole-content paint) rather than a dirty-region tick. */
    var lastTickWasFade: Boolean = false
        private set

    /** Current effect opacity, eased towards `1` (visible) or `0` (hidden) by [tick]. */
    var opacity: Float = 1f
        private set

    /** Target visibility set by [setVisible]. */
    var isVisible: Boolean = true
        private set

    /** Faded out completely: nothing is simulated or painted until [setVisible] `(true)`. */
    val isDormant: Boolean get() = !isVisible && opacity <= 0f

    private var attached = false
    private var painterDisposable: Disposable? = null
    private val bounds = Rectangle()
    private val content = Rectangle()

    // Batch of content rectangles queued during merge: x, y, w, h per entry; never more than the collector emits.
    private val batch = IntArray(4 * collector.capacity)
    private var batchCount = 0
    private var batchNext = 0
    private val paintRemaining = Runnable { drainBatch(nested = true) }

    /**
     * Registers the painter on the frame's glass pane and starts the effect.
     * Disposing [parent] detaches automatically; [detach] may also be called explicitly earlier.
     */
    fun attach(parent: Disposable) {
        if (attached) return
        val glassPane = IdeGlassPaneUtil.find(rootPane)
        val disposable = Disposer.newDisposable(parent, "IDE Atmosphere painter")
        glassPane.addPainter(null, painter, disposable)
        painterDisposable = disposable
        effect.start(context)
        painter.active = true
        attached = true
        Disposer.register(parent) { detach() }
    }

    /**
     * Stops compositing immediately and schedules the painter removal. Safe to call more than once.
     * `painter.active` is flipped first because the platform removes the painter via `invokeLater`.
     */
    fun detach() {
        if (!attached) return
        attached = false
        painter.active = false
        painter.nestedPaint = null
        effect.stop()
        painterDisposable?.let { Disposer.dispose(it) }
        painterDisposable = null
        lastRectCount = 0
        lastPaintedPixels = 0
        lastNestedRects = 0
        lastDirectRects = 0
        lastTickWasFade = false
    }

    /**
     * Starts fading the effect in ([visible] `true`) or out (`false`) over [FADE_SECONDS]. Before [attach] (or while
     * detached) the opacity snaps to the target so a frame attached while the application is inactive starts dormant.
     */
    fun setVisible(visible: Boolean) {
        isVisible = visible
        if (!attached) snapOpacity()
    }

    private fun snapOpacity() {
        opacity = if (isVisible) 1f else 0f
        painter.opacity = opacity
    }

    /**
     * Advances the effect by [deltaSeconds] and repaints what changed (or, while fading, the whole overlay).
     * No-op when not attached, paused or dormant; when not paintable only the opacity snaps to its target.
     */
    fun tick(deltaSeconds: Float) {
        lastRectCount = 0
        lastPaintedPixels = 0
        lastNestedRects = 0
        lastDirectRects = 0
        lastTickWasFade = false
        if (!attached || paused || isDormant) return
        if (!target.isPaintable) {
            snapOpacity()
            return
        }
        val width = rootPane.width
        val height = rootPane.height
        if (width <= 0 || height <= 0) return
        bounds.setBounds(0, 0, width, height)

        val fading = advanceFade(deltaSeconds)
        collector.clear()
        val changed = effect.update(deltaSeconds, bounds, collector)
        val hasContent = target.contentBounds(content)
        batchCount = 0
        batchNext = 0
        if (fading) {
            // Every overlay pixel changes alpha: one whole-content paint plus the header band, no batch hook needed.
            lastTickWasFade = true
            route(bounds, hasContent)
            flushBatch()
            return
        }
        if (!changed || collector.size == 0) return

        collector.merge(bounds) { rect -> route(rect, hasContent) }
        flushBatch()
    }

    /** Eases [opacity] towards the target; returns `true` when this tick is part of a fade. */
    private fun advanceFade(deltaSeconds: Float): Boolean {
        val targetOpacity = if (isVisible) 1f else 0f
        if (opacity == targetOpacity) return false
        val step = deltaSeconds / FADE_SECONDS
        opacity = if (targetOpacity > opacity) min(opacity + step, 1f) else max(opacity - step, 0f)
        painter.opacity = opacity
        return true
    }

    fun publish(event: AtmosphereEvent) {
        if (attached) effect.onEvent(event)
    }

    fun paletteChanged(palette: AtmospherePalette) {
        if (attached) effect.onPaletteChanged(palette)
    }

    private fun route(rect: Rectangle, hasContent: Boolean) {
        val rx0 = rect.x
        val ry0 = rect.y
        val rx1 = rect.x + rect.width
        val ry1 = rect.y + rect.height
        if (!hasContent) {
            repaintRoot(rx0, ry0, rx1, ry1)
            return
        }
        val cx0 = content.x
        val cy0 = content.y
        val cx1 = content.x + content.width
        val cy1 = content.y + content.height

        val ix0 = max(rx0, cx0)
        val iy0 = max(ry0, cy0)
        val ix1 = min(rx1, cx1)
        val iy1 = min(ry1, cy1)
        if (ix1 <= ix0 || iy1 <= iy0) {
            repaintRoot(rx0, ry0, rx1, ry1)
            return
        }
        queueContent(ix0, iy0, ix1, iy1)
        // Strips of the rectangle outside the content pane: top, bottom, left, right.
        if (ry0 < iy0) repaintRoot(rx0, ry0, rx1, iy0)
        if (ry1 > iy1) repaintRoot(rx0, iy1, rx1, ry1)
        if (rx0 < ix0) repaintRoot(rx0, iy0, ix0, iy1)
        if (rx1 > ix1) repaintRoot(ix1, iy0, rx1, iy1)
    }

    private fun queueContent(x0: Int, y0: Int, x1: Int, y1: Int) {
        if (4 * batchCount >= batch.size) {
            // Cannot happen (the collector emits at most `capacity` rects) but never drop a region: paint directly.
            lastRectCount++
            lastPaintedPixels += (x1 - x0).toLong() * (y1 - y0)
            lastDirectRects++
            target.paintContentNow(x0, y0, x1 - x0, y1 - y0)
            return
        }
        val i = 4 * batchCount
        batch[i] = x0
        batch[i + 1] = y0
        batch[i + 2] = x1 - x0
        batch[i + 3] = y1 - y0
        batchCount++
    }

    /**
     * Paints the queued content rectangles: the head directly with the nested hook armed, the rest from inside the
     * hook; anything still pending afterwards (hook never fired) is painted directly.
     */
    private fun flushBatch() {
        if (batchCount == 0) return
        batchNext = 1
        painter.nestedPaint = if (batchCount > 1) paintRemaining else null
        try {
            paintBatchEntry(0, nested = false)
        } finally {
            painter.nestedPaint = null
        }
        drainBatch(nested = false)
        batchCount = 0
        batchNext = 0
    }

    private fun drainBatch(nested: Boolean) {
        while (batchNext < batchCount) {
            val i = batchNext++
            paintBatchEntry(i, nested)
        }
    }

    private fun paintBatchEntry(index: Int, nested: Boolean) {
        val i = 4 * index
        val w = batch[i + 2]
        val h = batch[i + 3]
        lastRectCount++
        lastPaintedPixels += w.toLong() * h
        if (nested) lastNestedRects++ else lastDirectRects++
        target.paintContentNow(batch[i], batch[i + 1], w, h)
    }

    private fun repaintRoot(x0: Int, y0: Int, x1: Int, y1: Int) {
        lastRectCount++
        lastPaintedPixels += (x1 - x0).toLong() * (y1 - y0)
        target.repaintRoot(x0, y0, x1 - x0, y1 - y0)
    }

    /** Production routing straight to the Swing components of [rootPane]. */
    class SwingRepaintTarget(private val rootPane: JRootPane) : RepaintTarget {

        override val isPaintable: Boolean
            get() {
                if (!rootPane.isShowing) return false
                val window = SwingUtilities.getWindowAncestor(rootPane)
                return window !is Frame || (window.extendedState and Frame.ICONIFIED) == 0
            }

        override fun contentBounds(out: Rectangle): Boolean {
            val contentPane = rootPane.contentPane as? JComponent ?: return false
            if (!contentPane.isShowing) return false
            // content pane → layered pane → root pane: two fixed levels, no allocation needed.
            val layeredPane = rootPane.layeredPane
            out.setBounds(
                layeredPane.x + contentPane.x,
                layeredPane.y + contentPane.y,
                contentPane.width,
                contentPane.height,
            )
            return !out.isEmpty
        }

        override fun paintContentNow(x: Int, y: Int, width: Int, height: Int) {
            val contentPane = rootPane.contentPane as? JComponent ?: return
            val layeredPane = rootPane.layeredPane
            contentPane.paintImmediately(
                x - layeredPane.x - contentPane.x,
                y - layeredPane.y - contentPane.y,
                width,
                height,
            )
        }

        override fun repaintRoot(x: Int, y: Int, width: Int, height: Int) {
            rootPane.repaint(x, y, width, height)
        }
    }

    companion object {
        /** Upper bound of particles any PoC effect emits; sizes the dirty-region storage (2 rects per particle). */
        const val DEFAULT_MAX_PARTICLES: Int = 64

        /** Duration of the fade-in / fade-out triggered by [setVisible]. */
        const val FADE_SECONDS: Float = 0.5f
    }
}
