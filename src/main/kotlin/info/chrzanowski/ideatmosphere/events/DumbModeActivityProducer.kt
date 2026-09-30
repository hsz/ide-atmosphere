package info.chrzanowski.ideatmosphere.events

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import info.chrzanowski.ideatmosphere.AtmosphereManager

/**
 * Publishes ACTIVITY_STARTED/ACTIVITY_FINISHED when the IDE enters/exits dumb mode (indexing),
 * acting as a proxy for background IDE busy-ness.
 *
 * This is the plan's "background tasks" producer via dumb mode as the most universally observable
 * public proxy for background activity. Note that non-indexing background work (e.g. long VCS
 * operations) is NOT observed by this producer.
 */
class DumbModeActivityProducer(private val project: Project) : DumbService.DumbModeListener {

    override fun enteredDumbMode() {
        onStarted()
    }

    override fun exitDumbMode() {
        onTerminated()
    }

    internal fun onStarted() {
        // DumbModeListener callbacks arrive on the EDT per platform contract.
        AtmosphereManager.getInstance().beginActivity(project)
    }

    internal fun onTerminated() {
        // Background tasks via dumb mode always succeed (no failure concept for indexing completion).
        AtmosphereManager.getInstance().endActivity(project, success = true)
    }
}
