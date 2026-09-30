package info.chrzanowski.ideatmosphere.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import info.chrzanowski.ideatmosphere.AtmosphereBundle
import info.chrzanowski.ideatmosphere.AtmosphereManager
import info.chrzanowski.ideatmosphere.effects.AtmosphereEffects
import javax.swing.JComponent
import javax.swing.JLabel
import kotlin.math.roundToInt

/**
 * Settings UI for the Atmosphere plugin, appearing under Appearance & Behavior.
 */
class AtmosphereSettingsConfigurable : Configurable, Configurable.NoScroll {

    private val settings = AtmosphereSettingsState.getInstance()
    private val manager = AtmosphereManager.getInstance()
    private var panel: DialogPanel? = null

    override fun getDisplayName(): String = AtmosphereBundle.message("settings.displayName")

    override fun createComponent(): JComponent {
        val currentState = settings.state
        panel = panel {
            row {
                checkBox(AtmosphereBundle.message("settings.enabled"))
                    .bindSelected(manager::isEnabled)
            }
            group(AtmosphereBundle.message("settings.displayName")) {
                row(AtmosphereBundle.message("settings.effect")) {
                    comboBox(AtmosphereEffects.ids.toList(), textListCellRenderer { value ->
                        if (value != null) {
                            // Fallback to the ID itself if the bundle key is missing
                            runCatching { AtmosphereBundle.message("effect.$value.name") }.getOrDefault(value)
                        } else ""
                    }).bindItem(currentState::effectId.toNullableProperty())
                }
                row(AtmosphereBundle.message("settings.intensity")) {
                    slider(3, 20, 1, 10)
                        .labelTable(mapOf(3 to JLabel("0.3"), 10 to JLabel("1.0"), 20 to JLabel("2.0")))
                        .bindValue({ (currentState.intensity * 10f).roundToInt() }, { currentState.intensity = it / 10f })
                }
                row(AtmosphereBundle.message("settings.speed")) {
                    slider(3, 20, 1, 10)
                        .labelTable(mapOf(3 to JLabel("0.3"), 10 to JLabel("1.0"), 20 to JLabel("2.0")))
                        .bindValue({ (currentState.speed * 10f).roundToInt() }, { currentState.speed = it / 10f })
                }
                row {
                    checkBox(AtmosphereBundle.message("settings.reactToActivity"))
                        .bindSelected(currentState::reactToActivity)
                }
                row {
                    checkBox(AtmosphereBundle.message("settings.pauseOnFocusLoss"))
                        .bindSelected(currentState::pauseOnFocusLoss)
                }
            }
        }
        return panel!!
    }

    override fun isModified(): Boolean = panel?.isModified() ?: false

    override fun apply() {
        panel?.apply()
        manager.applySettings()
    }

    override fun reset() {
        panel?.reset()
    }

    override fun disposeUIResources() {
        panel = null
    }
}
