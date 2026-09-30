package info.chrzanowski.ideatmosphere.core

import kotlin.math.max
import kotlin.math.min

/**
 * A generic, allocation-free attack/decay/sustain/release envelope over a single [Float] value.
 *
 * Transitions between phases ([Phase]) based on time passed in [advance]. Retriggering smoothly
 * continues from the current value toward the new peak level.
 */
class AdsrEnvelope(
    restLevel: Float = 0f,
    private val attackSeconds: Float,
    private val decaySeconds: Float,
    private val sustainRatio: Float,
    private val releaseSeconds: Float
) {
    var restLevel: Float = restLevel
        set(newValue) {
            field = newValue
            if (phase == Phase.IDLE) {
                value = newValue
                targetLevel = newValue
                startLevel = newValue
            } else if (phase == Phase.RELEASE) {
                targetLevel = newValue
            }
        }

    enum class Phase { IDLE, ATTACK, DECAY, SUSTAIN, RELEASE }

    /** Current envelope phase. */
    var phase: Phase = Phase.IDLE
        private set

    /** Current envelope value. */
    var value: Float = restLevel
        private set

    private var peakLevel: Float = restLevel
    private var startLevel: Float = restLevel
    private var targetLevel: Float = restLevel
    private var phaseElapsed: Float = 0f
    private var phaseDuration: Float = 0f

    /**
     * Starts (or restarts) the attack phase from the current [value] toward [peak] over [attackSeconds].
     * Once reached, moves to [Phase.DECAY] toward `peak * sustainRatio` over [decaySeconds],
     * then [Phase.SUSTAIN].
     *
     * Retriggering while already in a non-idle phase continues smoothly from the current [value]
     * toward the new peak.
     */
    fun trigger(peak: Float) {
        peakLevel = peak
        startLevel = value
        targetLevel = peak
        phaseElapsed = 0f
        phaseDuration = max(0f, attackSeconds)
        phase = Phase.ATTACK

        if (phaseDuration == 0f) {
            value = targetLevel
            enterDecay()
        }
    }

    /**
     * Starts the release phase easing the [value] toward [restLevel] over [releaseSeconds].
     * Once reached, moves to [Phase.IDLE].
     */
    fun release() {
        if (phase == Phase.IDLE) return
        startLevel = value
        targetLevel = restLevel
        phaseElapsed = 0f
        phaseDuration = max(0f, releaseSeconds)
        phase = Phase.RELEASE

        if (phaseDuration == 0f) {
            value = targetLevel
            phase = Phase.IDLE
        }
    }

    /**
     * Advances the state machine by [deltaSeconds], updates and returns [value].
     * Allocation-free and safe for zero or negative [deltaSeconds].
     * Zero duration phases are treated as instant jumps.
     */
    fun advance(deltaSeconds: Float): Float {
        if (deltaSeconds <= 0f || phase == Phase.IDLE || phase == Phase.SUSTAIN) return value

        var remaining = deltaSeconds
        while (remaining > 0f && phase != Phase.IDLE && phase != Phase.SUSTAIN) {
            val timeLeft = phaseDuration - phaseElapsed
            if (timeLeft <= 0f || remaining >= timeLeft) {
                remaining -= max(0f, timeLeft)
                value = targetLevel
                when (phase) {
                    Phase.ATTACK -> enterDecay()
                    Phase.DECAY -> enterSustain()
                    Phase.RELEASE -> {
                        phase = Phase.IDLE
                        remaining = 0f
                    }
                    else -> remaining = 0f
                }
            } else {
                phaseElapsed += remaining
                val t = phaseElapsed / phaseDuration
                value = startLevel + (targetLevel - startLevel) * t
                remaining = 0f
            }
        }
        return value
    }

    private fun enterDecay() {
        startLevel = value
        targetLevel = peakLevel * sustainRatio
        phaseElapsed = 0f
        phaseDuration = max(0f, decaySeconds)
        phase = Phase.DECAY

        if (phaseDuration == 0f) {
            value = targetLevel
            enterSustain()
        }
    }

    private fun enterSustain() {
        value = targetLevel
        phase = Phase.SUSTAIN
    }
}
