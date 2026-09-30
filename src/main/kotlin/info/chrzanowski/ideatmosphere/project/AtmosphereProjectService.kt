package info.chrzanowski.ideatmosphere.project

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * Project-level `Disposable` whose lifetime bounds the project's atmosphere: it is the `parent` passed to
 * `AtmosphereManager.attach`, so closing the project detaches the painter automatically.
 */
@Service(Service.Level.PROJECT)
class AtmosphereProjectService(@Suppress("unused") private val project: Project) : Disposable {

    override fun dispose() {
        // Detach is performed by the Disposer child registered in AtmosphereManager.attach.
    }
}
