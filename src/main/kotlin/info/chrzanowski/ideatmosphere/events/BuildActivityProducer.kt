package info.chrzanowski.ideatmosphere.events

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.task.ProjectTaskContext
import com.intellij.task.ProjectTaskListener
import com.intellij.task.ProjectTaskManager
import info.chrzanowski.ideatmosphere.AtmosphereManager

/**
 * Publishes ACTIVITY_STARTED/ACTIVITY_FINISHED and SUCCESS/FAILURE when a project build starts/finishes.
 * Covers the platform's unified build system (compiler, Gradle, etc.), not just the legacy CompilerManager.
 */
class BuildActivityProducer(private val project: Project) : ProjectTaskListener {

    override fun started(context: ProjectTaskContext) {
        onStarted()
    }

    override fun finished(result: ProjectTaskManager.Result) {
        onTerminated(result.isAborted, result.hasErrors())
    }

    internal fun onStarted() {
        // Wrapping in invokeLater to be defensive about EDT delivery from ProjectTaskManager.
        ApplicationManager.getApplication().invokeLater {
            AtmosphereManager.getInstance().beginActivity(project)
        }
    }

    internal fun onTerminated(isAborted: Boolean, hasErrors: Boolean) {
        ApplicationManager.getApplication().invokeLater {
            val manager = AtmosphereManager.getInstance()
            if (isAborted) manager.endActivity(project) else manager.endActivity(project, success = !hasErrors)
        }
    }
}
