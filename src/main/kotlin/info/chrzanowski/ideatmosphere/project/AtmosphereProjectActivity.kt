package info.chrzanowski.ideatmosphere.project

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.UI
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.WindowManager
import info.chrzanowski.ideatmosphere.AtmosphereManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Attaches the atmosphere to the project's main frame once the frame exists (post-startup).
 * Skipped in headless mode and when the project has no frame (LightEdit, tests).
 */
class AtmosphereProjectActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (ApplicationManager.getApplication().isHeadlessEnvironment) return
        withContext(Dispatchers.UI) {
            if (project.isDisposed) return@withContext
            val frame = WindowManager.getInstance().getIdeFrame(project)
            if (frame == null) {
                LOG.info("No IDE frame for ${project.name}; atmosphere not attached")
                return@withContext
            }
            AtmosphereManager.getInstance().attach(project, frame, project.service<AtmosphereProjectService>())
        }
    }

    companion object {
        private val LOG = Logger.getInstance(AtmosphereProjectActivity::class.java)
    }
}
