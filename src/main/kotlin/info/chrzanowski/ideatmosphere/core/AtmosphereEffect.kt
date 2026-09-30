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

    /** Reacts to a semantic IDE event; default no-op. */
    fun onEvent(event: AtmosphereEvent) {}

    /** The theme changed; rebuild cached colors here, never inside [paint]. Default no-op. */
    fun onPaletteChanged(palette: AtmospherePalette) {}
}
