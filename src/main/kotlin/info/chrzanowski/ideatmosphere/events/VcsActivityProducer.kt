package info.chrzanowski.ideatmosphere.events

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory
import info.chrzanowski.ideatmosphere.AtmosphereManager

/**
 * Publishes SUCCESS when a commit went through and FAILURE when it failed, via the platform's commit
 * ([CheckinHandler]) hooks. Registered through the optional `com.intellij.modules.vcs` descriptor so IDEs without
 * version control still load the plugin. Pushes are covered separately by [GitPushActivityProducer].
 */
class VcsActivityProducer : CheckinHandlerFactory() {

    override fun createHandler(panel: CheckinProjectPanel, commitContext: CommitContext): CheckinHandler =
        createHandler(panel.project)

    /** The handler for one commit of [project]; split out so tests need no commit panel. */
    internal fun createHandler(project: Project): CheckinHandler = object : CheckinHandler() {
        override fun checkinSuccessful() = publish(project, success = true)

        override fun checkinFailed(exception: MutableList<VcsException>) = publish(project, success = false)
    }

    private fun publish(project: Project, success: Boolean) {
        ApplicationManager.getApplication().invokeLater {
            AtmosphereManager.getInstance().publishOutcome(project, success)
        }
    }
}
