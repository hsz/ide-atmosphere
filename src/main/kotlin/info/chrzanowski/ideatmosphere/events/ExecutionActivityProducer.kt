package info.chrzanowski.ideatmosphere.events

import com.intellij.execution.ExecutionListener
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import info.chrzanowski.ideatmosphere.AtmosphereManager

/**
 * Publishes ACTIVITY_STARTED/ACTIVITY_FINISHED and SUCCESS/FAILURE when a Run/Debug process starts/terminates,
 * per the plan's Run/Debug producer.
 */
class ExecutionActivityProducer : ExecutionListener {

    override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        onStarted(env.project)
    }

    override fun processTerminated(
        executorId: String,
        env: ExecutionEnvironment,
        handler: ProcessHandler,
        exitCode: Int
    ) {
        onTerminated(env.project, exitCode)
    }

    internal fun onStarted(project: Project) {
        // ExecutionListener callbacks usually run on the EDT for interactive actions, but we wrap in
        // invokeLater to guarantee compliance with AtmosphereManager's @RequiresEdt contract.
        ApplicationManager.getApplication().invokeLater {
            AtmosphereManager.getInstance().beginActivity(project)
        }
    }

    internal fun onTerminated(project: Project, exitCode: Int) {
        // Same threading approach as processStarted.
        ApplicationManager.getApplication().invokeLater {
            AtmosphereManager.getInstance().endActivity(project, success = exitCode == 0)
        }
    }
}
