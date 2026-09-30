package info.chrzanowski.ideatmosphere.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.util.xmlb.SkipDefaultValuesSerializationFilters
import com.intellij.util.xmlb.annotations.Property
import info.chrzanowski.ideatmosphere.core.AtmosphereReactionProfile
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
        @get:Property(filter = SkipDefaultValuesSerializationFilters::class)
        var activityProfile: AtmosphereReactionProfile? = null,
        @get:Property(filter = SkipDefaultValuesSerializationFilters::class)
        var firefliesProfile: AtmosphereReactionProfile? = null,
        @get:Property(filter = SkipDefaultValuesSerializationFilters::class)
        var sakuraProfile: AtmosphereReactionProfile? = null,
    ) {
        fun reactionProfile(): AtmosphereReactionProfile = activityProfile ?: when {
            firefliesProfile != null || sakuraProfile != null -> when (effectId) {
                "sakura" -> sakuraProfile ?: AtmosphereReactionProfile.sakura()
                else -> firefliesProfile ?: AtmosphereReactionProfile.fireflies()
            }

            else -> AtmosphereReactionProfile()
        }

        fun deepCopy(): State = copy(
            activityProfile = activityProfile?.deepCopy(),
            firefliesProfile = firefliesProfile?.deepCopy(),
            sakuraProfile = sakuraProfile?.deepCopy(),
        )

        fun normalized(): State = copy(
            effectId = effectId.takeIf { it in AtmosphereEffects.ids } ?: AtmosphereEffects.DEFAULT_ID,
            intensity = intensity.takeIf { it.isFinite() }?.coerceIn(0.3f, 2f) ?: 1f,
            speed = speed.takeIf { it.isFinite() }?.coerceIn(0.3f, 2f) ?: 1f,
            activityProfile = reactionProfile().normalized(AtmosphereReactionProfile()),
            firefliesProfile = null,
            sakuraProfile = null,
        )
    }

    private var myState = State().normalized()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state.normalized().deepCopy()
    }

    companion object {
        @JvmStatic
        fun getInstance(): AtmosphereSettingsState =
            ApplicationManager.getApplication().getService(AtmosphereSettingsState::class.java)
    }
}
