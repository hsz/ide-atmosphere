package info.chrzanowski.ideatmosphere.support

import com.intellij.openapi.components.*
import com.intellij.util.xmlb.annotations.OptionTag
import java.time.Duration

data class SponsorNotificationPreferences(
    @field:OptionTag @JvmField val firstUsedAt: Long = 0,
    @field:OptionTag @JvmField val sessions: Int = 0,
    @field:OptionTag @JvmField val snoozedUntil: Long = 0,
    @field:OptionTag @JvmField val dismissed: Boolean = false,
)

@Service(Service.Level.APP)
@State(
    name = "AtmosphereSponsor",
    storages = [Storage("ideatmosphere-sponsor.xml", roamingType = RoamingType.DISABLED)],
    category = SettingsCategory.PLUGINS,
)
class SponsorNotificationState :
    SerializablePersistentStateComponent<SponsorNotificationPreferences>(SponsorNotificationPreferences()) {

    @Synchronized
    fun recordSessionAndClaimNotification(now: Long = System.currentTimeMillis()): Boolean {
        val current = state
        if (current.dismissed) return false
        val updated = current.copy(
            firstUsedAt = current.firstUsedAt.takeIf { it > 0 } ?: now,
            sessions = (current.sessions.coerceIn(0, MIN_SESSIONS) + 1).coerceAtMost(MIN_SESSIONS),
        )
        val eligible = now >= updated.snoozedUntil && now >= updated.firstUsedAt &&
            updated.sessions >= MIN_SESSIONS && now - updated.firstUsedAt >= MIN_USAGE_MILLIS
        updateState {
            if (eligible) updated.copy(snoozedUntil = now + SNOOZE_DURATION_MILLIS) else updated
        }
        return eligible
    }

    @Synchronized
    fun snooze(now: Long = System.currentTimeMillis()) {
        updateState { it.copy(snoozedUntil = now + SNOOZE_DURATION_MILLIS) }
    }

    @Synchronized
    fun dismiss() {
        updateState { it.copy(dismissed = true) }
    }

    companion object {
        const val MIN_SESSIONS = 10
        val MIN_USAGE_MILLIS: Long = Duration.ofDays(14).toMillis()
        val SNOOZE_DURATION_MILLIS: Long = Duration.ofDays(90).toMillis()
    }
}