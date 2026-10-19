package info.chrzanowski.ideatmosphere.support

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import info.chrzanowski.ideatmosphere.AtmosphereBundle
import info.chrzanowski.ideatmosphere.actions.SponsorAtmosphereAction
import kotlinx.coroutines.runBlocking

class SponsorNotificationTest : BasePlatformTestCase() {

    fun `test startup in unit tests does not count usage`() {
        val state = service<SponsorNotificationState>()
        val original = state.state
        try {
            state.loadState(SponsorNotificationPreferences())
            runBlocking { SponsorNotificationActivity().execute(project) }
            assertEquals(SponsorNotificationPreferences(), state.state)
        } finally {
            state.loadState(original)
        }
    }

    fun `test notification has localized provider and dismissal actions`() {
        val notification = SponsorNotificationActivity().createNotification(SponsorNotificationState())
        try {
            assertEquals(SponsorNotificationActivity.GROUP_ID, notification.groupId)
            assertEquals(AtmosphereBundle.message("sponsor.notification.title"), notification.title)
            assertEquals(AtmosphereBundle.message("sponsor.notification.content"), notification.content)
            assertEquals(
                listOf("support.github", "support.kofi", "support.paypal", "sponsor.notification.later", "sponsor.notification.dismiss")
                    .map { AtmosphereBundle.message(it) },
                notification.actions.map { it.templatePresentation.text },
            )
        } finally {
            notification.expire()
        }
    }

    fun `test each provider dismisses future reminders before opening its URL`() {
        val urls = listOf(AtmosphereSupport.GITHUB_URL, AtmosphereSupport.KO_FI_URL, AtmosphereSupport.PAYPAL_URL)
        urls.forEachIndexed { index, url ->
            val state = SponsorNotificationState()
            val opened = mutableListOf<String>()
            val notification = SponsorNotificationActivity().createNotification(state) {
                assertTrue(state.state.dismissed)
                opened += it
            }
            try {
                perform(notification, index)
                assertEquals(listOf(url), opened)
                assertTrue(state.state.dismissed)
                assertTrue(notification.isExpired)
                assertFalse(state.recordSessionAndClaimNotification())
            } finally {
                notification.expire()
            }
        }
    }

    fun `test remind later snoozes without opting out`() {
        val state = SponsorNotificationState()
        val notification = SponsorNotificationActivity().createNotification(state)
        try {
            val before = System.currentTimeMillis()
            perform(notification, 3)
            val after = System.currentTimeMillis()
            assertTrue(state.state.snoozedUntil >= before + SponsorNotificationState.SNOOZE_DURATION_MILLIS)
            assertTrue(state.state.snoozedUntil <= after + SponsorNotificationState.SNOOZE_DURATION_MILLIS)
            assertFalse(state.state.dismissed)
            assertTrue(notification.isExpired)
        } finally {
            notification.expire()
        }
    }

    fun `test dont show again permanently dismisses without opening a browser`() {
        val state = SponsorNotificationState()
        val notification = SponsorNotificationActivity().createNotification(state) { fail("Must not open a browser") }
        try {
            perform(notification, 4)
            assertTrue(state.state.dismissed)
            assertTrue(notification.isExpired)
        } finally {
            notification.expire()
        }
    }

    fun `test closing a claimed reminder keeps the ninety day snooze`() {
        val state = SponsorNotificationState()
        val firstUse = 1_000_000L
        val now = firstUse + SponsorNotificationState.MIN_USAGE_MILLIS
        state.loadState(SponsorNotificationPreferences(firstUsedAt = firstUse, sessions = SponsorNotificationState.MIN_SESSIONS))
        assertTrue(state.recordSessionAndClaimNotification(now))
        SponsorNotificationActivity().createNotification(state).expire()
        assertFalse(state.state.dismissed)
        assertFalse(state.recordSessionAndClaimNotification(now + SponsorNotificationState.SNOOZE_DURATION_MILLIS - 1))
        assertTrue(state.recordSessionAndClaimNotification(now + SponsorNotificationState.SNOOZE_DURATION_MILLIS))
    }

    fun `test sponsor action is registered dumb aware and opens GitHub Sponsors`() {
        val registered = ActionManager.getInstance().getAction("info.chrzanowski.ideatmosphere.SponsorAtmosphereAction")
        assertTrue(registered is SponsorAtmosphereAction)
        assertTrue(registered is DumbAware)
        assertEquals(AtmosphereBundle.message("action.info.chrzanowski.ideatmosphere.SponsorAtmosphereAction.text"), registered.templatePresentation.text)

        val opened = mutableListOf<String>()
        val action = SponsorAtmosphereAction { opened += it }
        action.actionPerformed(TestActionEvent.createTestEvent(action))
        assertEquals(listOf("https://github.com/sponsors/hsz"), opened)
    }

    private fun perform(notification: Notification, index: Int) {
        val action = notification.actions[index] as NotificationAction
        action.actionPerformed(TestActionEvent.createTestEvent(action), notification)
    }
}