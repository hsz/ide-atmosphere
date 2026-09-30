package info.chrzanowski.ideatmosphere.events

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import info.chrzanowski.ideatmosphere.AtmosphereManager

/** Observes external project sync and task execution, including Gradle sync and tasks. */
class ExternalSystemActivityProducer : ExternalSystemTaskNotificationListener {

    private val activeTasks = mutableSetOf<ExternalSystemTaskId>()

    override fun onStart(projectPath: String, id: ExternalSystemTaskId) {
        if (id.type != ExternalSystemTaskType.RESOLVE_PROJECT && id.type != ExternalSystemTaskType.EXECUTE_TASK) return
        ApplicationManager.getApplication().invokeLater {
            val project = id.findProject() ?: return@invokeLater
            if (project.isDisposed || !activeTasks.add(id)) return@invokeLater
            AtmosphereManager.getInstance().beginActivity(project)
        }
    }

    override fun onSuccess(projectPath: String, id: ExternalSystemTaskId) {
        finish(id, success = true)
    }

    override fun onFailure(projectPath: String, id: ExternalSystemTaskId, exception: Exception) {
        finish(id, success = false)
    }

    override fun onCancel(projectPath: String, id: ExternalSystemTaskId) {
        finish(id, success = null)
    }

    override fun onEnd(projectPath: String, id: ExternalSystemTaskId) {
        finish(id, success = null)
    }

    private fun finish(id: ExternalSystemTaskId, success: Boolean?) {
        ApplicationManager.getApplication().invokeLater {
            if (!activeTasks.remove(id)) return@invokeLater
            val project = id.findProject() ?: return@invokeLater
            if (project.isDisposed) return@invokeLater
            val manager = AtmosphereManager.getInstance()
            if (success == null) manager.endActivity(project) else manager.endActivity(project, success)
        }
    }
}