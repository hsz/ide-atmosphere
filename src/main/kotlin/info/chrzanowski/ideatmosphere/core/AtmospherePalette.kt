package info.chrzanowski.ideatmosphere.core

import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import java.awt.Color

/**
 * Theme-derived colors and intensity used by effects.
 *
 * Effects must never query the LaF themselves; the palette is computed once per theme change
 * (see [fromCurrentLaf]) and pushed via [AtmosphereEffect.onPaletteChanged], so cached color tables
 * are rebuilt outside the hot paint loop.
 *
 * @property isDark `true` when the current theme is dark.
 * @property particleColor base RGB of a particle; alpha is applied by the effect.
 * @property opacityScale global multiplier applied to particle opacity (subtle on light themes).
 * @property backgroundLuminance relative luminance (0..1) of the panel background the particles are drawn over.
 */
data class AtmospherePalette(
    val isDark: Boolean,
    val particleColor: Color,
    val opacityScale: Float,
    val backgroundLuminance: Float,
) {
    companion object {
        /** Warm firefly glow for dark themes. */
        val DARK: AtmospherePalette = AtmospherePalette(
            isDark = true,
            particleColor = Color(255, 230, 150),
            opacityScale = 1.0f,
            backgroundLuminance = 0.1f,
        )

        /** Deep amber that stays visible yet subtle on bright backgrounds. */
        val LIGHT: AtmospherePalette = AtmospherePalette(
            isDark = false,
            particleColor = Color(190, 120, 20),
            opacityScale = 0.8f,
            backgroundLuminance = 0.95f,
        )

        /**
         * Builds a palette for the currently installed Look and Feel.
         *
         * Uses only public API: [JBColor.isBright] for the light/dark decision and the luminance of
         * [UIUtil.getPanelBackground] to fine-tune opacity when a theme is unusually mid-toned.
         */
        @JvmStatic
        fun fromCurrentLaf(): AtmospherePalette {
            val isDark = !JBColor.isBright()
            val luminance = relativeLuminance(UIUtil.getPanelBackground())
            val base = if (isDark) DARK else LIGHT
            // Mid-toned backgrounds offer less contrast than the extremes; scale opacity toward the base value
            // as the background approaches 0.5 luminance.
            val contrast = if (isDark) 1f - luminance else luminance
            val opacity = (base.opacityScale * (0.7f + 0.3f * contrast.coerceIn(0f, 1f)))
            return base.copy(opacityScale = opacity, backgroundLuminance = luminance)
        }

        /** Relative luminance per ITU-R BT.709 in the 0..1 range. */
        @JvmStatic
        fun relativeLuminance(color: Color): Float =
            (0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue) / 255f
    }
}
