package info.chrzanowski.ideatmosphere.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import info.chrzanowski.ideatmosphere.core.PointerMode
import info.chrzanowski.ideatmosphere.effects.AtmosphereEffects

/**
 * Persists the atmosphere settings across IDE restarts.
 */
@Service(Service.Level.APP)
@State(name = "AtmosphereSettings", storages = [Storage("ideatmosphere.xml")])
class AtmosphereSettingsState : PersistentStateComponent<AtmosphereSettingsState.State> {

    data class State(
        var effectId: String = AtmosphereEffects.DEFAULT_ID,
        var intensity: Float = 1f,
        var speed: Float = 1f,
        var reactToActivity: Boolean = true,
        var pauseOnFocusLoss: Boolean = true,
        /** Particles stir with the typing rhythm; only effective while [reactToActivity] is on. */
        var reactToTyping: Boolean = true,
        /** How particles react to the pointer; replaces the 1.x `avoidMouse` flag (see [avoidMouse]). */
        var pointerMode: PointerMode = PointerMode.AVOID,
        /** Static-ish, very slow, low-FPS motion without bursts and gusts. */
        var reduceMotion: Boolean = false,
        /** Effect used on dark themes instead of [effectId]; `null` means "same as [effectId]". */
        var darkEffectId: String? = null,
        /** Effect used on light themes instead of [effectId]; `null` means "same as [effectId]". */
        var lightEffectId: String? = null,
    ) {
        /**
         * Migration shim for the `avoidMouse` option of 1.x settings files: `false` becomes [PointerMode.OFF], `true`
         * keeps the (default) [PointerMode.AVOID]. The mirror value written back equals the default unless the
         * pointer is off, so the component store omits it from new files and an older plugin still reads them.
         */
        @Suppress("unused")
        var avoidMouse: Boolean
            get() = pointerMode != PointerMode.OFF
            set(value) {
                if (!value) pointerMode = PointerMode.OFF
            }

        /** Detached copy of this state; the state holds plain values only, so [copy] is already deep. */
        fun deepCopy(): State = copy()

        /**
         * Falls back to the default effect for unknown ids, drops unknown per-theme ids (back to "same as
         * [effectId]") and clamps intensity and speed into [SCALE_RANGE].
         */
        fun normalized(): State = copy(
            effectId = effectId.takeIf { it in AtmosphereEffects.ids } ?: AtmosphereEffects.DEFAULT_ID,
            intensity = intensity.takeIf { it.isFinite() }?.coerceIn(SCALE_RANGE) ?: 1f,
            speed = speed.takeIf { it.isFinite() }?.coerceIn(SCALE_RANGE) ?: 1f,
            darkEffectId = darkEffectId?.takeIf { it in AtmosphereEffects.ids },
            lightEffectId = lightEffectId?.takeIf { it in AtmosphereEffects.ids },
        )

        /** The effect to show on a dark or light theme: the per-theme preset when set, [effectId] otherwise. */
        fun effectIdFor(isDark: Boolean): String = (if (isDark) darkEffectId else lightEffectId) ?: effectId

        /**
         * Speed to apply to effects: [speed], capped to [REDUCED_MOTION_SPEED] while [reduceMotion] is on. Effects
         * clamp the speed scale to [SCALE_RANGE], so the cap equals its lower bound.
         */
        fun effectiveSpeed(): Float = if (reduceMotion) minOf(speed, REDUCED_MOTION_SPEED) else speed
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state.normalized().deepCopy()
    }

    companion object {
        /** Supported range of the intensity and speed multipliers. */
        val SCALE_RANGE: ClosedFloatingPointRange<Float> = 0.3f..2f

        /** Speed multiplier ceiling while Reduce Motion is on (the slowest the effects support). */
        const val REDUCED_MOTION_SPEED: Float = 0.3f

        @JvmStatic
        fun getInstance(): AtmosphereSettingsState =
            ApplicationManager.getApplication().getService(AtmosphereSettingsState::class.java)
    }
}
