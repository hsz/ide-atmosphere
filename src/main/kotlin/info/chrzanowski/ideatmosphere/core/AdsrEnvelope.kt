package info.chrzanowski.ideatmosphere.core

import kotlin.math.max

/**
 * A generic, allocation-free attack/decay/sustain/release envelope over a single [Float] value.
 *
 * Transitions between phases ([Phase]) based on time passed in [advance]. Retriggering smoothly
 * continues from the current value toward the new peak level.
 */
class AdsrEnvelope(
    restLevel: Float = 0f,
    private var attackSeconds: Float,
    private var decaySeconds: Float,
    private var sustainRatio: Float,
    private var releaseSeconds: Float,
    private val oneShot: Boolean = false
) {
    init {
        require(restLevel.isFinite())
        validateSettings(attackSeconds, decaySeconds, sustainRatio, releaseSeconds)
    }

    var restLevel: Float = restLevel
        set(newValue) {
            require(newValue.isFinite())
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

    /** Updates settings without jumping the [value], restarting the active segment when changed. */
    fun configure(settings: EnvelopeSettings) {
        validateSettings(settings.attackSeconds, settings.decaySeconds, settings.sustainRatio, settings.releaseSeconds)
        if (attackSeconds == settings.attackSeconds && decaySeconds == settings.decaySeconds &&
            sustainRatio == settings.sustainRatio && releaseSeconds == settings.releaseSeconds
        ) return

        attackSeconds = settings.attackSeconds
        decaySeconds = settings.decaySeconds
        sustainRatio = settings.sustainRatio
        releaseSeconds = settings.releaseSeconds

        when (phase) {
            Phase.IDLE -> Unit
            Phase.ATTACK -> startPhase(Phase.ATTACK, peakLevel, attackSeconds)
            Phase.DECAY, Phase.SUSTAIN -> enterDecay()
            Phase.RELEASE -> startPhase(Phase.RELEASE, restLevel, releaseSeconds)
        }
    }

    /**
     * Starts (or restarts) the attack phase from the current [value] toward [peak] over [attackSeconds].
     * Once reached, moves to [Phase.DECAY] toward `rest + (peak - rest) * sustainRatio`
     * over [decaySeconds], then [Phase.SUSTAIN] or [Phase.RELEASE] for a one-shot envelope.
     *
     * Retriggering while already in a non-idle phase continues smoothly from the current [value]
     * toward the new peak.
     */
    fun trigger(peak: Float) {
        require(peak.isFinite())
        peakLevel = peak
        startPhase(Phase.ATTACK, peak, attackSeconds)
        resolveInstantPhases()
    }

    /**
     * Starts the release phase easing the [value] toward [restLevel] over [releaseSeconds].
     * Once reached, moves to [Phase.IDLE].
     */
    fun release() {
        if (phase == Phase.IDLE) return
        startPhase(Phase.RELEASE, restLevel, releaseSeconds)
        resolveInstantPhases()
    }

    /**
     * Advances the state machine by [deltaSeconds], updates and returns [value].
     * Allocation-free and safe for zero, negative or non-finite [deltaSeconds].
     * Zero duration phases are treated as instant jumps.
     */
    fun advance(deltaSeconds: Float): Float {
        if (!deltaSeconds.isFinite() || deltaSeconds <= 0f || phase == Phase.IDLE || phase == Phase.SUSTAIN) return value

        var remaining = deltaSeconds
        while (phase != Phase.IDLE && phase != Phase.SUSTAIN) {
            val timeLeft = phaseDuration - phaseElapsed
            if (timeLeft <= 0f || remaining >= timeLeft) {
                remaining -= max(0f, timeLeft)
                value = targetLevel
                finishPhase()
            } else {
                if (remaining <= 0f) break
                phaseElapsed += remaining
                val t = phaseElapsed / phaseDuration
                value = (startLevel.toDouble() + (targetLevel.toDouble() - startLevel) * t).toFloat()
                remaining = 0f
            }
        }
        return value
    }

    private fun enterDecay() {
        val sustainLevel = (restLevel.toDouble() + (peakLevel.toDouble() - restLevel) * sustainRatio).toFloat()
        startPhase(Phase.DECAY, sustainLevel, decaySeconds)
    }

    private fun startPhase(newPhase: Phase, target: Float, duration: Float) {
        startLevel = value
        targetLevel = target
        phaseElapsed = 0f
        phaseDuration = max(0f, duration)
        phase = newPhase
    }

    private fun resolveInstantPhases() {
        while (phase != Phase.IDLE && phase != Phase.SUSTAIN && phaseDuration == 0f) {
            value = targetLevel
            finishPhase()
        }
    }

    private fun finishPhase() {
        when (phase) {
            Phase.ATTACK -> enterDecay()
            Phase.DECAY -> {
                if (oneShot) startPhase(Phase.RELEASE, restLevel, releaseSeconds)
                else phase = Phase.SUSTAIN
            }
            Phase.RELEASE -> phase = Phase.IDLE
            else -> Unit
        }
    }

    private fun validateSettings(attack: Float, decay: Float, sustain: Float, release: Float) {
        require(attack.isFinite() && decay.isFinite() && release.isFinite())
        require(sustain.isFinite() && sustain in 0f..1f)
    }
}
