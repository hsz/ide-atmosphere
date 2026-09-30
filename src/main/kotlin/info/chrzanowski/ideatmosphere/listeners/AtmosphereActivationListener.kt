package info.chrzanowski.ideatmosphere.listeners

import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.wm.IdeFrame
import info.chrzanowski.ideatmosphere.AtmosphereManager

/** Fades the atmosphere out (and lets the ticker stop) while the IDE is in the background; fades it back in on activation. */
class AtmosphereActivationListener : ApplicationActivationListener {

    override fun applicationActivated(ideFrame: IdeFrame) {
        AtmosphereManager.getInstance().setApplicationActive(true)
    }

    override fun applicationDeactivated(ideFrame: IdeFrame) {
        AtmosphereManager.getInstance().setApplicationActive(false)
    }
}
