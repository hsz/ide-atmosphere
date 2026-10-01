package info.chrzanowski.ideatmosphere.core

/** Plain ADSR timings for [AdsrEnvelope.configure]: durations in seconds, sustain as a ratio of the peak. */
data class EnvelopeSettings(
    var attackSeconds: Float = 0.3f,
    var decaySeconds: Float = 0.4f,
    var sustainRatio: Float = 1f,
    var releaseSeconds: Float = 0.7f,
)
