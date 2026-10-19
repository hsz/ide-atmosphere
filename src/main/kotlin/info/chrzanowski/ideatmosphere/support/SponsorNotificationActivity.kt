package info.chrzanowski.ideatmosphere.support

import com.intellij.ide.BrowserUtil
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.UI
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import info.chrzanowski.ideatmosphere.AtmosphereBundle
import info.chrzanowski.ideatmosphere.AtmosphereManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SponsorNotificationActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val application = ApplicationManager.getApplication()
        if (application.isUnitTestMode || application.isHeadlessEnvironment || project.isDefault || project.isDisposed) return
        if (!AtmosphereManager.getInstance().isEnabled) return
        withContext(Dispatchers.UI) {
            if (project.isDisposed || !AtmosphereManager.getInstance().isEnabled) return@withContext
            val state = service<SponsorNotificationState>()
            if (state.recordSessionAndClaimNotification()) {
                createNotification(state).notify(project)
            }
        }
    }

    internal fun createNotification(
        state: SponsorNotificationState,
        browse: (String) -> Unit = { BrowserUtil.browse(it) },
    ): Notification = NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID).createNotification(
        AtmosphereBundle.message("sponsor.notification.title"),
        AtmosphereBundle.message("sponsor.notification.content"),
        NotificationType.INFORMATION,
    ).apply {
        setSuggestionType(true)
        listOf(
            "support.github" to AtmosphereSupport.GITHUB_URL,
            "support.kofi" to AtmosphereSupport.KO_FI_URL,
            "support.paypal" to AtmosphereSupport.PAYPAL_URL,
        ).forEach { (key, url) ->
            addAction(NotificationAction.createSimpleExpiring(AtmosphereBundle.message(key)) {
                state.dismiss()
                browse(url)
            })
        }
        addAction(NotificationAction.createSimpleExpiring(AtmosphereBundle.message("sponsor.notification.later")) {
            state.snooze()
        })
        addAction(NotificationAction.createSimpleExpiring(AtmosphereBundle.message("sponsor.notification.dismiss")) {
            state.dismiss()
        })
    }

    companion object {
        const val GROUP_ID = "IDE Atmosphere"
    }
}