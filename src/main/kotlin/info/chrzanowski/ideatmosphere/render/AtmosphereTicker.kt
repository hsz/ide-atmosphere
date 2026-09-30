package info.chrzanowski.ideatmosphere.render

import com.intellij.util.ui.TimerUtil
import info.chrzanowski.ideatmosphere.frame.FrameAtmosphere
import javax.swing.Timer
import kotlin.math.max
import kotlin.math.min

/**
 * The single Swing timer that animates every attached [FrameAtmosphere].
 *
 * Each tick computes a real `deltaTime` from `System.nanoTime()` (clamped to [MAX_DELTA_SECONDS] so a
 * long pause does not teleport particles), advances all frames, measures the tick cost, feeds it to the
 * [governor] and applies the resulting delay to the timer. Coalescing keeps the queue from piling up
 * ticks when the EDT is busy.
 *
 * [calm] caps the governed rate (default 15 FPS) while the user is idle: effects halve their speed on `USER_IDLE`,
 * so half the ticks give the same pixels-per-frame and the CPU cost drops proportionally.
 *
 * Application deactivation is handled by the frames themselves (`FrameAtmosphere.setVisible` fades them out until
 * they are dormant). A tick in which at least one frame was fading is not fed to the [governor] — the whole-window
 * paints of a fade would otherwise drag the rate down for the next seconds. When every attached frame is dormant
 * after a tick, [onAllDormant] is invoked once so the owner can stop the timer; it is re-armed as soon as a tick
 * finds a non-dormant frame again.
 * All methods are EDT-only.
 */
class AtmosphereTicker(
    val governor: FrameBudgetGovernor = FrameBudgetGovernor(),
    val stats: AtmosphereStats = AtmosphereStats(),
    private val calmFps: Int = 15,
    private val clock: () -> Long = System::nanoTime,
) {

    private val frames = ArrayList<FrameAtmosphere>()
    private var lastTickNanos = 0L
    private var allDormantNotified = false

    /** Called at the end of a tick after which every attached frame is dormant (faded out); fired once per transition. */
    var onAllDormant: (() -> Unit)? = null

    private val timer: Timer = TimerUtil.createNamedTimer("IDE Atmosphere", governor.intervalMillis) { tick() }.apply {
        isCoalesce = true
        isRepeats = true
    }

    val isRunning: Boolean get() = timer.isRunning

    val frameCount: Int get() = frames.size

    /** Reduced rate cap while the user is idle; takes effect on the next tick. */
    var calm: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            applyInterval()
        }

    /** Effective timer delay in milliseconds. */
    val intervalMillis: Int
        get() = if (calm) max(governor.intervalMillis, 1000 / calmFps) else governor.intervalMillis

    fun add(frame: FrameAtmosphere) {
        if (!frames.contains(frame)) frames.add(frame)
    }

    fun remove(frame: FrameAtmosphere) {
        frames.remove(frame)
    }

    fun start() {
        if (timer.isRunning) return
        lastTickNanos = 0L
        allDormantNotified = false
        governor.reset()
        applyInterval()
        timer.start()
    }

    fun stop() {
        timer.stop()
        lastTickNanos = 0L
    }

    /**
     * Runs one tick now. Exposed so tests and diagnostics can drive the pipeline without the timer;
     * the timer calls exactly this.
     */
    fun tick() {
        val now = clock()
        val delta = if (lastTickNanos == 0L) {
            intervalMillis / 1000f
        } else {
            min((now - lastTickNanos) / 1_000_000_000f, MAX_DELTA_SECONDS)
        }
        lastTickNanos = now

        var rects = 0
        var pixels = 0L
        var nested = 0
        var direct = 0
        var fading = false
        var allDormant = frames.isNotEmpty()
        // Iterate backwards so a frame detaching itself during its tick cannot skip a neighbour.
        var i = frames.size - 1
        while (i >= 0) {
            if (i < frames.size) {
                val frame = frames[i]
                frame.tick(delta)
                rects += frame.lastRectCount
                pixels += frame.lastPaintedPixels
                nested += frame.lastNestedRects
                direct += frame.lastDirectRects
                if (frame.lastTickWasFade) fading = true
                if (!frame.isDormant) allDormant = false
            }
            i--
        }

        val costMicros = (clock() - now) / 1_000L
        if (!fading) governor.recordTick(costMicros)
        stats.recordTick(costMicros, rects, pixels, governor.fps, calm, fading, nested, direct)
        applyInterval()

        if (allDormant) {
            if (!allDormantNotified) {
                allDormantNotified = true
                onAllDormant?.invoke()
            }
        } else {
            allDormantNotified = false
        }
    }

    private fun applyInterval() {
        val interval = intervalMillis
        if (timer.delay != interval) {
            timer.delay = interval
            timer.initialDelay = interval
        }
    }

    companion object {
        /** Upper clamp for the per-tick simulation step (100 ms). */
        const val MAX_DELTA_SECONDS: Float = 0.1f
    }
}
