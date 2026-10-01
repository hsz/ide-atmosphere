package info.chrzanowski.ideatmosphere.widget

import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import info.chrzanowski.ideatmosphere.AtmosphereBundle
import info.chrzanowski.ideatmosphere.AtmosphereManager
import java.text.DateFormat
import java.util.Date

/** Registers the "Atmosphere: <effect>" status bar widget (enabled by default, toggleable from the status bar menu). */
class AtmosphereStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = ID

    override fun getDisplayName(): String = AtmosphereBundle.message("widget.displayName")

    override fun createWidget(project: Project): StatusBarWidget = AtmosphereStatusBarWidget(project)

    companion object {
        const val ID: String = "info.chrzanowski.ideatmosphere.statusBarWidget"
    }
}

/**
 * Shows the current atmosphere (or "paused") in the status bar; clicking opens a popup built from
 * [AtmosphereWidgetActions]. The widget refreshes itself through [AtmosphereManager.addChangeListener]. It creates
 * no Swing components itself, as required for widgets instantiated on a background thread.
 */
class AtmosphereStatusBarWidget(
    private val project: Project,
    private val manager: () -> AtmosphereManager = { AtmosphereManager.getInstance() },
) : StatusBarWidget, StatusBarWidget.MultipleTextValuesPresentation {

    override fun ID(): String = AtmosphereStatusBarWidgetFactory.ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        manager().addChangeListener(this) { statusBar.updateWidget(ID()) }
    }

    override fun getSelectedValue(): String {
        val manager = manager()
        return if (manager.isSnoozed) {
            AtmosphereBundle.message("widget.text.paused")
        } else {
            AtmosphereBundle.message("widget.text", effectName(manager.currentEffectId))
        }
    }

    override fun getTooltipText(): String {
        val manager = manager()
        return if (manager.isSnoozed) {
            val until = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(manager.snoozeEndsAtMillis))
            AtmosphereBundle.message("widget.tooltip.paused", until)
        } else {
            AtmosphereBundle.message("widget.tooltip")
        }
    }

    override fun getPopup(): JBPopup = JBPopupFactory.getInstance().createActionGroupPopup(
        AtmosphereBundle.message("widget.popup.title"),
        AtmosphereWidgetActions.createGroup(project, manager),
        SimpleDataContext.getProjectContext(project),
        JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
        false,
    )

    private fun effectName(id: String): String = AtmosphereBundle.messageOrNull("effect.$id.name") ?: id
}
