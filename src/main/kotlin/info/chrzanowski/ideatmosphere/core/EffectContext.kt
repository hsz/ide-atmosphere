package info.chrzanowski.ideatmosphere.core

import com.intellij.ui.scale.JBUIScale
import java.util.SplittableRandom

/**
 * Everything an [AtmosphereEffect] needs at [AtmosphereEffect.start] time.
 *
 * @property palette theme-derived colors and opacity.
 * @property uiScale IDE user-space scale factor (font/IDE scale, not the HiDPI device scale, which is
 *   carried by the `Graphics2D` transform). Effects use it to size particles consistently.
 * @property random seeded generator so simulations are reproducible in tests.
 */
class EffectContext(
    val palette: AtmospherePalette,
    val uiScale: Float = JBUIScale.scale(1f),
    val random: SplittableRandom = SplittableRandom(),
) {
    companion object {
        /** Context for the current LaF with a random seed — used by the production wiring. */
        @JvmStatic
        fun fromCurrentLaf(): EffectContext = EffectContext(AtmospherePalette.fromCurrentLaf())

        /** Deterministic context for tests: fixed palette, unit scale, seeded generator. */
        @JvmStatic
        fun forTesting(
            palette: AtmospherePalette = AtmospherePalette.DARK,
            seed: Long = 42L,
            uiScale: Float = 1f,
        ): EffectContext = EffectContext(palette, uiScale, SplittableRandom(seed))
    }
}
