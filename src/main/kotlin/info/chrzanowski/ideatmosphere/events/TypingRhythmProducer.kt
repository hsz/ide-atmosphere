package info.chrzanowski.ideatmosphere.events

import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent
import kotlin.math.exp
import kotlin.math.min

/**
 * Turns the user's typing rhythm into a continuous flow level in `0..1` for `AtmosphereEffect.setFlow`.
 *
 * Every keystroke tops up a smoothed keystroke rate (an exponential moving average with time constant
 * [SMOOTHING_SECONDS]); the level is that rate relative to [FULL_RATE] keys per second, clamped to `1`. Steady typing
 * ramps the level up within a second or two, and after a pause it decays back to `0` on its own, so [sample] must
 * be polled while the level is non-zero (see [isSettled]).
 *
 * The producer only observes: in the IDE it is fed by a passive `AWTEventListener` (see [attach]) that cannot
 * consume or alter key events. Keystrokes are counted per [KeyEvent.KEY_PRESSED] of a non-modifier key; the
 * clock is injected as nanoseconds so the ramp and decay can be driven deterministically in tests.
 *
 * [onLevelChanged] fires from [keystroke], [sample] and [reset] whenever the level actually moves, on the caller's
 * thread (the EDT for toolkit events).
 */
class TypingRhythmProducer(
    private val nanoTime: () -> Long = System::nanoTime,
    private val onLevelChanged: (Float) -> Unit = {},
) {

    private var rate = 0f
    private var lastNanos = 0L
    private var hasSample = false

    /** Current flow level in `0..1`, as of the last [keystroke] or [sample]. */
    var level: Float = 0f
        private set

    /** `true` once the level has decayed to (effectively) zero; polling may stop until the next keystroke. */
    val isSettled: Boolean get() = level <= 0f

    private val keyListener = AWTEventListener(::observe)

    /** Counts [event] as a keystroke if it is the press of a non-modifier key; anything else is ignored. */
    fun observe(event: AWTEvent) {
        if (event is KeyEvent && event.id == KeyEvent.KEY_PRESSED && !isModifier(event.keyCode)) keystroke()
    }

    /** Registers a keystroke now. Returns the new level. */
    fun keystroke(): Float {
        val now = nanoTime()
        decayTo(now)
        rate += 1f / SMOOTHING_SECONDS
        return updateLevel()
    }

    /** Advances the decay to now without a keystroke. Returns the new level. */
    fun sample(): Float {
        decayTo(nanoTime())
        return updateLevel()
    }

    /** Forgets all typing; the level drops to `0` immediately. */
    fun reset() {
        rate = 0f
        hasSample = false
        publish(0f)
    }

    /** Starts observing key presses through the toolkit; passive, never consumes input. Pair with [detach]. */
    fun attach() {
        Toolkit.getDefaultToolkit().addAWTEventListener(keyListener, AWTEvent.KEY_EVENT_MASK)
    }

    fun detach() {
        Toolkit.getDefaultToolkit().removeAWTEventListener(keyListener)
        reset()
    }

    private fun decayTo(now: Long) {
        if (hasSample) {
            val elapsed = ((now - lastNanos) / 1_000_000_000.0).toFloat().coerceAtLeast(0f)
            rate *= exp(-elapsed / SMOOTHING_SECONDS)
        }
        lastNanos = now
        hasSample = true
    }

    private fun updateLevel(): Float {
        val raw = min(1f, rate / FULL_RATE)
        return publish(if (raw < SETTLE_THRESHOLD) 0f else raw)
    }

    private fun publish(value: Float): Float {
        if (value != level) {
            level = value
            onLevelChanged(value)
        }
        return value
    }

    companion object {
        /** Time constant of the keystroke-rate average: ramp-up and settle both take a couple of these. */
        const val SMOOTHING_SECONDS: Float = 1.2f

        /** Sustained keystrokes per second that count as full flow (≈ 60 words per minute). */
        const val FULL_RATE: Float = 5f

        /** Below this the level snaps to zero so idle polling can stop. */
        const val SETTLE_THRESHOLD: Float = 0.02f

        private fun isModifier(keyCode: Int): Boolean = when (keyCode) {
            KeyEvent.VK_SHIFT, KeyEvent.VK_CONTROL, KeyEvent.VK_ALT, KeyEvent.VK_ALT_GRAPH, KeyEvent.VK_META,
            KeyEvent.VK_CAPS_LOCK, KeyEvent.VK_UNDEFINED -> true
            else -> false
        }
    }
}
