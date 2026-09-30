package info.chrzanowski.ideatmosphere.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware
import info.chrzanowski.ideatmosphere.AtmosphereManager

/**
 * `View | Appearance | Atmosphere`: shows or hides the atmosphere on every project frame.
 * The state lives in [AtmosphereManager.isEnabled] and is persisted across restarts.
 */
class ToggleAtmosphereAction : ToggleAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean = AtmosphereManager.getInstance().isEnabled

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        AtmosphereManager.getInstance().isEnabled = state
    }
}
