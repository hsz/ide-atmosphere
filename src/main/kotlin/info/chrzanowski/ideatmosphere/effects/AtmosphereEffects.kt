package info.chrzanowski.ideatmosphere.effects

import info.chrzanowski.ideatmosphere.core.AtmosphereEffect
import info.chrzanowski.ideatmosphere.effects.fireflies.FirefliesEffect
import info.chrzanowski.ideatmosphere.effects.rain.RainEffect
import info.chrzanowski.ideatmosphere.effects.sakura.SakuraEffect
import info.chrzanowski.ideatmosphere.effects.snow.SnowEffect
import info.chrzanowski.ideatmosphere.effects.starfield.StarfieldEffect

/**
 * In-code registry of available atmospheres. Every frame gets its own effect instance, so the
 * registry stores factories rather than singletons. No extension point is exposed yet.
 */
object AtmosphereEffects {

    /** Effect used when no explicit id is configured. */
    const val DEFAULT_ID: String = FirefliesEffect.ID

    private val factories: Map<String, () -> AtmosphereEffect> = linkedMapOf(
        FirefliesEffect.ID to { FirefliesEffect() },
        SakuraEffect.ID to { SakuraEffect() },
        SnowEffect.ID to { SnowEffect() },
        RainEffect.ID to { RainEffect() },
        StarfieldEffect.ID to { StarfieldEffect() },
    )

    /** Ids of all registered effects, in registration order. */
    val ids: Set<String> get() = factories.keys

    /** Everything a user may pick: the registered effects. */
    val selectableIds: List<String> get() = factories.keys.toList()

    /** `true` for a registered effect id. */
    fun isSelectable(id: String?): Boolean = id != null && id in factories

    /** Creates a fresh instance of the effect with the given [id], or `null` if it is unknown. */
    fun create(id: String): AtmosphereEffect? = factories[id]?.invoke()

    /** Creates the effect with the given [id], falling back to [DEFAULT_ID] when unknown. */
    fun createOrDefault(id: String?): AtmosphereEffect =
        (id?.let(::create)) ?: create(DEFAULT_ID) ?: error("Default effect '$DEFAULT_ID' is not registered")
}
