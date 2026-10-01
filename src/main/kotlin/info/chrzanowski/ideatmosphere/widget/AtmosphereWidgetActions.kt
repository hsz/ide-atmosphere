package info.chrzanowski.ideatmosphere.widget

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import info.chrzanowski.ideatmosphere.AtmosphereBundle
import info.chrzanowski.ideatmosphere.AtmosphereManager
import info.chrzanowski.ideatmosphere.effects.AtmosphereEffects
import info.chrzanowski.ideatmosphere.settings.AtmosphereSettingsConfigurable

/**
 * The actions behind the status bar widget's popup: one radio-like entry per effect, "Pause for 1 Hour" (or "Resume"
 * while snoozed) and "Open Atmosphere Settings…". Everything delegates to [AtmosphereManager].
 */
object AtmosphereWidgetActions {

    /** Builds the popup content; [openSettings] is called for the settings entry (the platform dialog by default). */
    fun createGroup(
        project: Project?,
        manager: () -> AtmosphereManager = { AtmosphereManager.getInstance() },
        openSettings: () -> Unit = { ShowSettingsUtil.getInstance().showSettingsDialog(project, AtmosphereSettingsConfigurable::class.java) },
    ): DefaultActionGroup {
        val group = DefaultActionGroup()
        for (id in AtmosphereEffects.ids) group.add(SelectEffectAction(id, manager))
        group.add(Separator.getInstance())
        group.add(SnoozeAction(manager))
        group.add(OpenSettingsAction(openSettings))
        return group
    }

    /** Selects effect [id] for the current theme brightness; shown as selected while it is the current effect. */
    class SelectEffectAction(
        private val id: String,
        private val manager: () -> AtmosphereManager,
    ) : ToggleAction(AtmosphereBundle.messageOrNull("effect.$id.name") ?: id), DumbAware {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun isSelected(e: AnActionEvent): Boolean = manager().currentEffectId == id

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (state) manager().setEffectId(id)
        }
    }

    /** "Pause for 1 Hour" while running, "Resume Atmosphere" while snoozed. */
    class SnoozeAction(private val manager: () -> AtmosphereManager) : AnAction(), DumbAware {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.text = AtmosphereBundle.message(if (manager().isSnoozed) "widget.popup.resume" else "widget.popup.snooze")
        }

        override fun actionPerformed(e: AnActionEvent) {
            val manager = manager()
            if (manager.isSnoozed) manager.resumeFromSnooze() else manager.snooze()
        }
    }

    class OpenSettingsAction(private val openSettings: () -> Unit) :
        AnAction(AtmosphereBundle.message("widget.popup.settings")), DumbAware {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) = openSettings()
    }
}
