package info.chrzanowski.ideatmosphere.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import info.chrzanowski.ideatmosphere.effects.AtmosphereEffects

/**
 * Persists the atmosphere settings across IDE restarts.
 */
@Service(Service.Level.APP)
@State(name = "AtmosphereSettings", storages = [Storage("ide-atmosphere.xml")])
class AtmosphereSettingsState : PersistentStateComponent<AtmosphereSettingsState.State> {

    data class State(
        var effectId: String = AtmosphereEffects.DEFAULT_ID,
        var intensity: Float = 1f,
        var speed: Float = 1f,
        var reactToActivity: Boolean = true,
        var pauseOnFocusLoss: Boolean = true,
        var avoidMouse: Boolean = true,
    ) {
        /** Detached copy of this state; the state holds plain values only, so [copy] is already deep. */
        fun deepCopy(): State = copy()

        /** Falls back to the default effect for unknown ids and clamps intensity and speed into [SCALE_RANGE]. */
        fun normalized(): State = copy(
            effectId = effectId.takeIf { it in AtmosphereEffects.ids } ?: AtmosphereEffects.DEFAULT_ID,
            intensity = intensity.takeIf { it.isFinite() }?.coerceIn(SCALE_RANGE) ?: 1f,
            speed = speed.takeIf { it.isFinite() }?.coerceIn(SCALE_RANGE) ?: 1f,
        )
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state.normalized().deepCopy()
    }

    companion object {
        /** Supported range of the intensity and speed multipliers. */
        val SCALE_RANGE: ClosedFloatingPointRange<Float> = 0.3f..2f

        @JvmStatic
        fun getInstance(): AtmosphereSettingsState =
            ApplicationManager.getApplication().getService(AtmosphereSettingsState::class.java)
    }
}
