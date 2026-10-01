package info.chrzanowski.ideatmosphere.core

import info.chrzanowski.ideatmosphere.render.DirtyRegionCollector
import java.awt.Graphics2D
import java.awt.Rectangle

/**
 * A visual atmosphere (fireflies, snow, ...) simulated and painted over one IDE frame.
 *
 * Contract:
 * - All methods are called on the EDT.
 * - Coordinates are root-pane user-space pixels (HiDPI scaling is applied by the `Graphics2D` transform).
 * - [update] and [paint] form the hot path and must not allocate objects per call.
 * - [paint] may be invoked far more often than [update] (on every IDE repaint that touches the frame),
 *   so it must be O(particles) with a cheap clip intersection test.
 * - The effect must never repaint the frame itself; it only reports changed regions to the collector.
 */
interface AtmosphereEffect {

    /** Stable identifier used by the effect registry (e.g. `fireflies`). */
    val id: String

    /** Called once before the first [update]; the effect may allocate its buffers here. */
    fun start(context: EffectContext)

    /** Called once when the effect is detached; release everything allocated in [start]. */
    fun stop()

    /**
     * Advances the simulation by [deltaSeconds] (already clamped by the ticker) within [bounds] and
     * reports every region whose pixels changed to [dirty], in root-pane coordinates.
     *
     * @return `true` if anything visible changed and at least one region was reported.
     */
    fun update(deltaSeconds: Float, bounds: Rectangle, dirty: DirtyRegionCollector): Boolean

    /**
     * Paints only what intersects [clip]. Antialiasing and alpha compositing are already configured
     * by the caller; the effect must not change the transform or clip of [g].
     */
    fun paint(g: Graphics2D, clip: Rectangle)

    /**
     * Reacts to a semantic IDE event; default no-op. `SUCCESS` / `FAILURE` may also arrive as explicit previews
     * from the settings page, so they must be safe at any time, including back-to-back.
     */
    fun onEvent(event: AtmosphereEvent) {}

    /** The theme changed; rebuild cached colors here, never inside [paint]. Default no-op. */
    fun onPaletteChanged(palette: AtmospherePalette) {}

    /** Observes the cursor in root-pane coordinates without receiving or consuming mouse input. */
    fun onPointerChanged(x: Float, y: Float, inside: Boolean) {}

    /**
     * A mouse button was pressed at root-pane coordinates (`x`, `y`); the event itself is neither received nor
     * consumed. Effects typically puff nearby particles outward. Default no-op; must not allocate.
     */
    fun onPointerPressed(x: Float, y: Float) {}

    /**
     * The mouse wheel turned at root-pane coordinates (`x`, `y`) by `amount` notches (positive = scrolled down,
     * fractional for high-resolution devices); the event itself is neither received nor consumed. Effects typically
     * send a gust through nearby particles in the direction the content moves. Default no-op; must not allocate.
     */
    fun onScroll(x: Float, y: Float, amount: Float) {}

    /**
     * Places (`active = true`) or removes (`active = false`) a soft anchor at root-pane coordinates (`x`, `y`) — the
     * editor caret — around which a few particles gently gather. Default no-op; must not allocate.
     */
    fun setAnchor(x: Float, y: Float, active: Boolean) {}

    /** Switches how particles react to the pointer (avoid, attract, ignore). Default no-op. */
    fun setPointerMode(mode: PointerMode) {}

    /**
     * Updates the intensity of the effect.
     * @param scale Expected in roughly [0.3, 2.0], where 1.0 is the default.
     * Effects should apply this scale (e.g. to particle count or opacity) without allocating
     * and without waiting for the next event.
     */
    fun setIntensity(scale: Float) {}

    /**
     * Updates the animation speed scale.
     * @param scale Expected in roughly [0.3, 2.0], where 1.0 is the default.
     * Effects should multiply their baseline speed by this scale.
     */
    fun setSpeedScale(scale: Float) {}

    /**
     * Typing flow in `0..1`: `0` at rest, `1` while typing fast. A continuous value rather than an event so effects
     * are not spammed per keystroke; they typically stir a little faster (see [FLOW_SPEED_BOOST]). Default no-op.
     */
    fun setFlow(level: Float) {}

    companion object {
        /** Fraction added to the speed at full typing flow (`setFlow(1f)` runs 1.5× the configured speed). */
        const val FLOW_SPEED_BOOST: Float = 0.5f

        /**
         * Share of an effect's idle speed it holds while execution is paused on a breakpoint
         * ([AtmosphereEvent.EXECUTION_PAUSED]): the field almost freezes, as if holding its breath.
         */
        const val PAUSED_SPEED_FACTOR: Float = 0.5f
    }
}
