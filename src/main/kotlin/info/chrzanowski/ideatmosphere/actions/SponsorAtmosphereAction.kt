package info.chrzanowski.ideatmosphere.actions

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import info.chrzanowski.ideatmosphere.AtmosphereBundle
import info.chrzanowski.ideatmosphere.support.AtmosphereSupport

class SponsorAtmosphereAction internal constructor(private val browse: (String) -> Unit) :
    DumbAwareAction(AtmosphereBundle.message("action.info.chrzanowski.ideatmosphere.SponsorAtmosphereAction.text")) {

    constructor() : this({ BrowserUtil.browse(it) })

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        browse(AtmosphereSupport.GITHUB_URL)
    }
}