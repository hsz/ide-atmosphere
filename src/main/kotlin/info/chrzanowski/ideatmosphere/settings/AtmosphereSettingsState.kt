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
        var pauseOnFocusLoss: Boolean = true
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        @JvmStatic
        fun getInstance(): AtmosphereSettingsState = ApplicationManager.getApplication().getService(AtmosphereSettingsState::class.java)
    }
}
