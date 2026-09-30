package info.chrzanowski.ideatmosphere.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import info.chrzanowski.ideatmosphere.AtmosphereBundle
import info.chrzanowski.ideatmosphere.AtmosphereManager
import info.chrzanowski.ideatmosphere.core.EnvelopeSettings
import info.chrzanowski.ideatmosphere.effects.AtmosphereEffects
import java.text.ParseException
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import kotlin.math.roundToInt

/**
 * Settings UI for the Atmosphere plugin, appearing under Appearance & Behavior.
 */
class AtmosphereSettingsConfigurable internal constructor(
    private val settings: AtmosphereSettingsState,
    private val readEnabled: () -> Boolean,
    private val applySettings: (Boolean) -> Unit
) : Configurable {

    constructor() : this(
        AtmosphereSettingsState.getInstance(),
        { AtmosphereManager.getInstance().isEnabled },
        { enabled ->
            val manager = AtmosphereManager.getInstance()
            manager.isEnabled = enabled
            manager.applySettings()
        }
    )

    private var panel: DialogPanel? = null
    private var draft: AtmosphereSettingsState.State? = null
    private var draftEnabled = false
    private val envelopeControls = mutableListOf<EnvelopeControl>()

    private data class EnvelopeControl(
        val spinner: JSpinner,
        val read: () -> Float,
        val write: (Float) -> Unit
    ) {
        fun editedValue(): Float {
            val field = (spinner.editor as JSpinner.DefaultEditor).textField
            return if (field.text == field.formatter.valueToString(spinner.value)) {
                (spinner.value as Number).toFloat()
            } else {
                (field.formatter.stringToValue(field.text) as Number).toFloat()
            }
        }
    }

    private fun currentDraft(): AtmosphereSettingsState.State = checkNotNull(draft)

    override fun getDisplayName(): String = AtmosphereBundle.message("settings.displayName")

    override fun createComponent(): JComponent {
        panel?.let { return it }
        draft = settings.state.normalized().deepCopy()
        draftEnabled = readEnabled()
        panel = panel {
            row {
                checkBox(AtmosphereBundle.message("settings.enabled"))
                    .bindSelected(::draftEnabled)
                    .applyToComponent { name = "enabled" }
            }
            group(AtmosphereBundle.message("settings.displayName")) {
                row(AtmosphereBundle.message("settings.effect")) {
                    comboBox(AtmosphereEffects.ids.toList(), textListCellRenderer { value ->
                        if (value != null) {
                            // Fallback to the ID itself if the bundle key is missing
                            runCatching { AtmosphereBundle.message("effect.$value.name") }.getOrDefault(value)
                        } else ""
                    }).bindItem({ currentDraft().effectId }, { value ->
                        currentDraft().effectId = value ?: AtmosphereEffects.DEFAULT_ID
                    }).applyToComponent { name = "effectId" }
                }
                row(AtmosphereBundle.message("settings.intensity")) {
                    slider(3, 20, 1, 10)
                        .labelTable(mapOf(3 to JLabel("0.3"), 10 to JLabel("1.0"), 20 to JLabel("2.0")))
                        .bindValue({ (currentDraft().intensity * 10f).roundToInt() }, { currentDraft().intensity = it / 10f })
                        .applyToComponent { name = "intensity" }
                }
                row(AtmosphereBundle.message("settings.speed")) {
                    slider(3, 20, 1, 10)
                        .labelTable(mapOf(3 to JLabel("0.3"), 10 to JLabel("1.0"), 20 to JLabel("2.0")))
                        .bindValue({ (currentDraft().speed * 10f).roundToInt() }, { currentDraft().speed = it / 10f })
                        .applyToComponent { name = "speed" }
                }
                row {
                    checkBox(AtmosphereBundle.message("settings.pauseOnFocusLoss"))
                        .bindSelected({ currentDraft().pauseOnFocusLoss }, { currentDraft().pauseOnFocusLoss = it })
                        .applyToComponent { name = "pauseOnFocusLoss" }
                }
            }
            group(AtmosphereBundle.message("settings.activity")) {
                row {
                    checkBox(AtmosphereBundle.message("settings.reactToActivity"))
                        .bindSelected({ currentDraft().reactToActivity }, { currentDraft().reactToActivity = it })
                        .applyToComponent { name = "reactToActivity" }
                        .comment(AtmosphereBundle.message("settings.activity.shared"))
                }
                envelopeSection("activity.movement", "settings.envelope.movement") { currentDraft().reactionProfile().movement }
                envelopeSection("activity.successPulse", "settings.envelope.successPulse") { currentDraft().reactionProfile().successPulse }
                row {
                    comment(AtmosphereBundle.message("settings.activity.triggers"))
                        .applyToComponent { name = "activity.triggers" }
                }
            }
            onApply {
                val values = try {
                    envelopeControls.map { it.editedValue() }
                } catch (_: ParseException) {
                    throw ConfigurationException(AtmosphereBundle.message("settings.envelope.invalid"))
                }
                envelopeControls.zip(values).forEach { (control, value) -> control.write(value) }
            }
            onReset {
                envelopeControls.forEach {
                    val value = it.read().toDouble()
                    it.spinner.value = value
                    (it.spinner.editor as JSpinner.DefaultEditor).textField.value = value
                }
            }
            onIsModified {
                envelopeControls.any { runCatching { it.editedValue() != it.read() }.getOrDefault(true) }
            }
        }
        return panel!!
    }

    private fun Panel.envelopeSection(id: String, title: String, envelope: () -> EnvelopeSettings) {
        group(AtmosphereBundle.message(title)) {
            row {
                comment(AtmosphereBundle.message("$title.description"))
                    .applyToComponent { name = "$id.description" }
            }
            envelopeField("$id.attackSeconds", "settings.envelope.attack", 10.0, 0.1,
                { envelope().attackSeconds }, { envelope().attackSeconds = it }, "$title.attack.description")
            envelopeField("$id.decaySeconds", "settings.envelope.decay", 10.0, 0.1,
                { envelope().decaySeconds }, { envelope().decaySeconds = it }, "$title.decay.description")
            envelopeField("$id.sustainRatio", "settings.envelope.sustain", 1.0, 0.01,
                { envelope().sustainRatio }, { envelope().sustainRatio = it }, "$title.sustain.description")
            envelopeField("$id.releaseSeconds", "settings.envelope.release", 10.0, 0.1,
                { envelope().releaseSeconds }, { envelope().releaseSeconds = it }, "$title.release.description")
        }
    }

    private fun Panel.envelopeField(
        id: String,
        label: String,
        maximum: Double,
        step: Double,
        read: () -> Float,
        write: (Float) -> Unit,
        description: String
    ) {
        row(AtmosphereBundle.message(label)) {
            val spinner = JSpinner(SpinnerNumberModel(read().toDouble(), 0.0, maximum, step))
            spinner.editor = JSpinner.NumberEditor(spinner, "0.#######")
            spinner.name = id
            cell(spinner).comment(AtmosphereBundle.message(description))
            envelopeControls += EnvelopeControl(spinner, read, write)
        }
    }

    override fun isModified(): Boolean = panel?.let {
        it.isModified() || currentDraft() != settings.state.normalized() || draftEnabled != readEnabled()
    } ?: false

    override fun apply() {
        val currentPanel = panel ?: return
        currentPanel.apply()
        settings.loadState(currentDraft())
        applySettings(draftEnabled)
        reset()
    }

    override fun reset() {
        val currentPanel = panel ?: return
        draft = settings.state.normalized().deepCopy()
        draftEnabled = readEnabled()
        currentPanel.reset()
    }

    override fun disposeUIResources() {
        panel = null
        draft = null
        draftEnabled = false
        envelopeControls.clear()
    }
}
