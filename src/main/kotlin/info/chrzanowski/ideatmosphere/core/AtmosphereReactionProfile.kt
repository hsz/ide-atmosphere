package info.chrzanowski.ideatmosphere.core

data class EnvelopeSettings(
    var attackSeconds: Float = 0.3f,
    var decaySeconds: Float = 0.4f,
    var sustainRatio: Float = 1f,
    var releaseSeconds: Float = 0.7f,
) {
    fun normalized(defaults: EnvelopeSettings): EnvelopeSettings = EnvelopeSettings(
        finiteRange(attackSeconds, 0f, 10f, defaults.attackSeconds),
        finiteRange(decaySeconds, 0f, 10f, defaults.decaySeconds),
        finiteRange(sustainRatio, 0f, 1f, defaults.sustainRatio),
        finiteRange(releaseSeconds, 0f, 10f, defaults.releaseSeconds),
    )

    private fun finiteRange(value: Float, min: Float, max: Float, fallback: Float): Float =
        if (value.isFinite()) value.coerceIn(min, max) else fallback
}

data class AtmosphereReactionProfile(
    var movement: EnvelopeSettings = EnvelopeSettings(),
    var successPulse: EnvelopeSettings = EnvelopeSettings(0.1f, 1.5f, 0f, 0f),
) {
    fun deepCopy(): AtmosphereReactionProfile = copy(movement = movement.copy(), successPulse = successPulse.copy())

    fun normalized(defaults: AtmosphereReactionProfile): AtmosphereReactionProfile = AtmosphereReactionProfile(
        movement.normalized(defaults.movement),
        successPulse.normalized(defaults.successPulse),
    )

    companion object {
        fun fireflies(): AtmosphereReactionProfile = AtmosphereReactionProfile()

        fun sakura(): AtmosphereReactionProfile = AtmosphereReactionProfile(
            EnvelopeSettings(1f, 1.5f, 1f, 2f),
            EnvelopeSettings(0.2f, 2f, 0f, 0f),
        )
    }
}