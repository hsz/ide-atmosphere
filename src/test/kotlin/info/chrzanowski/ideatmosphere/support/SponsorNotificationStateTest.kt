package info.chrzanowski.ideatmosphere.support

import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.SettingsCategory
import com.intellij.openapi.components.State
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SponsorNotificationStateTest {

    @Test
    fun `both ten project openings and fourteen days are required`() {
        val state = SponsorNotificationState()
        assertEquals(SponsorNotificationPreferences(), state.state)

        repeat(SponsorNotificationState.MIN_SESSIONS) {
            assertFalse(state.recordSessionAndClaimNotification(FIRST_USE))
        }
        assertEquals(FIRST_USE, state.state.firstUsedAt)
        assertFalse(state.recordSessionAndClaimNotification(FIRST_USE - 1))
        assertFalse(state.recordSessionAndClaimNotification(ELIGIBLE_AT - 1))
        assertTrue(state.recordSessionAndClaimNotification(ELIGIBLE_AT))
        assertEquals(SponsorNotificationState.MIN_SESSIONS, state.state.sessions)

        val infrequent = SponsorNotificationState()
        assertFalse(infrequent.recordSessionAndClaimNotification(FIRST_USE))
        repeat(SponsorNotificationState.MIN_SESSIONS - 2) {
            assertFalse(infrequent.recordSessionAndClaimNotification(ELIGIBLE_AT))
        }
        assertTrue(infrequent.recordSessionAndClaimNotification(ELIGIBLE_AT))
    }

    @Test
    fun `claim snoozes for ninety days even without choosing an action`() {
        val state = eligibleState()
        assertTrue(state.recordSessionAndClaimNotification(ELIGIBLE_AT))
        val nextReminder = ELIGIBLE_AT + SponsorNotificationState.SNOOZE_DURATION_MILLIS
        assertEquals(nextReminder, state.state.snoozedUntil)
        assertFalse(state.recordSessionAndClaimNotification(ELIGIBLE_AT))
        assertFalse(state.recordSessionAndClaimNotification(nextReminder - 1))
        assertTrue(state.recordSessionAndClaimNotification(nextReminder))
    }

    @Test
    fun `simultaneous project openings claim only one reminder`() {
        val state = eligibleState()
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val claims = List(8) {
                executor.submit<Boolean> {
                    check(start.await(10, TimeUnit.SECONDS))
                    state.recordSessionAndClaimNotification(ELIGIBLE_AT)
                }
            }
            start.countDown()
            assertEquals(1, claims.count { it.get(10, TimeUnit.SECONDS) })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `explicit snooze resets the window and dismissal is permanent`() {
        val state = eligibleState()
        state.snooze(ELIGIBLE_AT)
        val later = ELIGIBLE_AT + 1_000
        state.snooze(later)
        val nextReminder = later + SponsorNotificationState.SNOOZE_DURATION_MILLIS
        assertFalse(state.recordSessionAndClaimNotification(nextReminder - 1))
        assertTrue(state.recordSessionAndClaimNotification(nextReminder))

        state.dismiss()
        val dismissed = state.state
        assertFalse(state.recordSessionAndClaimNotification(nextReminder + SponsorNotificationState.SNOOZE_DURATION_MILLIS))
        assertEquals(dismissed, state.state)
    }

    @Test
    fun `usage and choices survive XML serialization in local-only storage`() {
        val state = eligibleState()
        state.snooze(ELIGIBLE_AT)
        state.dismiss()
        val restored = SponsorNotificationState()
        restored.loadState(
            XmlSerializer.deserialize(
                JDOMUtil.load(JDOMUtil.writeElement(XmlSerializer.serialize(state.state))),
                SponsorNotificationPreferences::class.java,
            ),
        )
        assertEquals(state.state, restored.state)
        assertFalse(restored.recordSessionAndClaimNotification(ELIGIBLE_AT + SponsorNotificationState.SNOOZE_DURATION_MILLIS))

        val storage = SponsorNotificationState::class.java.getAnnotation(State::class.java)
        assertEquals(SettingsCategory.PLUGINS, storage.category)
        assertEquals("ideatmosphere-sponsor.xml", storage.storages.single().value)
        assertEquals(RoamingType.DISABLED, storage.storages.single().roamingType)
    }

    private fun eligibleState() = SponsorNotificationState().apply {
        loadState(SponsorNotificationPreferences(firstUsedAt = FIRST_USE, sessions = SponsorNotificationState.MIN_SESSIONS))
    }

    private companion object {
        const val FIRST_USE = 1_000_000L
        val ELIGIBLE_AT = FIRST_USE + SponsorNotificationState.MIN_USAGE_MILLIS
    }
}