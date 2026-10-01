package info.chrzanowski.ideatmosphere.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import info.chrzanowski.ideatmosphere.AtmosphereBundle
import info.chrzanowski.ideatmosphere.AtmosphereManager
import info.chrzanowski.ideatmosphere.core.AtmosphereEvent
import info.chrzanowski.ideatmosphere.core.PointerMode
import info.chrzanowski.ideatmosphere.effects.AtmosphereEffects
import java.awt.event.ItemEvent
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JSlider
import kotlin.math.roundToInt

/**
 * Settings UI for the Atmosphere plugin, appearing under Appearance & Behavior.
 *
 * All edits go to an independent draft: Apply persists the draft once and then notifies the manager, Reset reloads
 * the live settings, and disposing the page discards the draft. Intensity and speed are edited as whole percents.
 */
class AtmosphereSettingsConfigurable internal constructor(
    private val settings: AtmosphereSettingsState,
    private val readEnabled: () -> Boolean,
    private val applySettings: (Boolean) -> Unit,
    private val previewOutcome: (AtmosphereEvent) -> Unit = { AtmosphereManager.getInstance().previewOutcome(it) },
) : Configurable {

    constructor() : this(
        AtmosphereSettingsState.getInstance(),
        { AtmosphereManager.getInstance().isEnabled },
        { enabled ->
            val manager = AtmosphereManager.getInstance()
            manager.isEnabled = enabled
            manager.applySettings()
        },
    )

    private var panel: DialogPanel? = null
    private var draft: AtmosphereSettingsState.State? = null
    private var draftEnabled = false
    private var effectDescription: JEditorPane? = null
    private var effectCombo: ComboBox<String>? = null
    private var intensitySlider: JSlider? = null
    private var speedSlider: JSlider? = null

    /** Live preview of the drafted atmosphere, intensity and speed; `null` before the component is created. */
    internal var preview: AtmospherePreviewPanel? = null
        private set

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
            }.bottomGap(BottomGap.SMALL)
            row(AtmosphereBundle.message("settings.effect")) {
                comboBox(AtmosphereEffects.selectableIds, textListCellRenderer { id -> id?.let(::effectName) })
                    .bindItem({ currentDraft().effectId }, { currentDraft().effectId = it ?: AtmosphereEffects.DEFAULT_ID })
                    .applyToComponent {
                        name = "effectId"
                        addItemListener {
                            if (it.stateChange == ItemEvent.SELECTED) {
                                showDescription(it.item as? String)
                                updatePreview()
                            }
                        }
                    }
                    .also { effectCombo = it.component }
            }
            row("") {
                effectDescription = comment("").applyToComponent { name = "effectDescription" }.component
            }
            row("") {
                preview = cell(AtmospherePreviewPanel())
                    .align(AlignX.FILL)
                    .comment(AtmosphereBundle.message("settings.preview.live.comment"))
                    .component
            }
            row(AtmosphereBundle.message("settings.intensity")) {
                percentSlider(
                    "intensity",
                    AtmosphereBundle.message("settings.intensity.min"),
                    AtmosphereBundle.message("settings.intensity.max"),
                    { currentDraft().intensity },
                    { currentDraft().intensity = it },
                ).also { intensitySlider = it.component }
            }
            row(AtmosphereBundle.message("settings.speed")) {
                percentSlider(
                    "speed",
                    AtmosphereBundle.message("settings.speed.min"),
                    AtmosphereBundle.message("settings.speed.max"),
                    { currentDraft().speed },
                    { currentDraft().speed = it },
                ).also { speedSlider = it.component }
            }
            group(AtmosphereBundle.message("settings.behaviour")) {
                row {
                    checkBox(AtmosphereBundle.message("settings.reactToActivity"))
                        .bindSelected({ currentDraft().reactToActivity }, { currentDraft().reactToActivity = it })
                        .applyToComponent { name = "reactToActivity" }
                        .comment(AtmosphereBundle.message("settings.reactToActivity.comment"))
                }
                row {
                    checkBox(AtmosphereBundle.message("settings.reactToTyping"))
                        .bindSelected({ currentDraft().reactToTyping }, { currentDraft().reactToTyping = it })
                        .applyToComponent { name = "reactToTyping" }
                        .comment(AtmosphereBundle.message("settings.reactToTyping.comment"))
                }
                row {
                    button(AtmosphereBundle.message("settings.preview.success")) { previewOutcome(AtmosphereEvent.SUCCESS) }
                        .applyToComponent { name = "previewSuccess" }
                    button(AtmosphereBundle.message("settings.preview.failure")) { previewOutcome(AtmosphereEvent.FAILURE) }
                        .applyToComponent { name = "previewFailure" }
                }
                row {
                    comment(AtmosphereBundle.message("settings.preview.comment"))
                        .applyToComponent { name = "previewComment" }
                }
                row(AtmosphereBundle.message("settings.pointerMode")) {
                    comboBox(PointerMode.entries, textListCellRenderer { mode -> mode?.let(::pointerModeName) })
                        .bindItem({ currentDraft().pointerMode }, { currentDraft().pointerMode = it ?: PointerMode.AVOID })
                        .applyToComponent { name = "pointerMode" }
                        .comment(AtmosphereBundle.message("settings.pointerMode.comment"))
                }
                row {
                    checkBox(AtmosphereBundle.message("settings.caretAnchor"))
                        .bindSelected({ currentDraft().caretAnchor }, { currentDraft().caretAnchor = it })
                        .applyToComponent { name = "caretAnchor" }
                        .comment(AtmosphereBundle.message("settings.caretAnchor.comment"))
                }
                row {
                    checkBox(AtmosphereBundle.message("settings.pauseOnFocusLoss"))
                        .bindSelected({ currentDraft().pauseOnFocusLoss }, { currentDraft().pauseOnFocusLoss = it })
                        .applyToComponent { name = "pauseOnFocusLoss" }
                }
                row {
                    checkBox(AtmosphereBundle.message("settings.reduceMotion"))
                        .bindSelected({ currentDraft().reduceMotion }, { currentDraft().reduceMotion = it })
                        .applyToComponent { name = "reduceMotion" }
                        .comment(AtmosphereBundle.message("settings.reduceMotion.comment"))
                }
            }
            group(AtmosphereBundle.message("settings.themePresets")) {
                row(AtmosphereBundle.message("settings.lightEffect")) {
                    presetCombo("lightEffectId", { currentDraft().lightEffectId }, { currentDraft().lightEffectId = it })
                }
                row(AtmosphereBundle.message("settings.darkEffect")) {
                    presetCombo("darkEffectId", { currentDraft().darkEffectId }, { currentDraft().darkEffectId = it })
                }
                row {
                    comment(AtmosphereBundle.message("settings.themePresets.comment"))
                }
            }
        }
        intensitySlider?.addChangeListener { updatePreview() }
        speedSlider?.addChangeListener { updatePreview() }
        showDescription(currentDraft().effectId)
        updatePreview()
        return panel!!
    }

    /** Slider over [AtmosphereSettingsState.SCALE_RANGE] in whole percents with a caption at either end. */
    private fun Row.percentSlider(
        id: String,
        minLabel: String,
        maxLabel: String,
        read: () -> Float,
        write: (Float) -> Unit,
    ): Cell<JSlider> = slider(MIN_PERCENT, MAX_PERCENT, 0, 0)
        .labelTable(mapOf(MIN_PERCENT to JLabel(minLabel), MAX_PERCENT to JLabel(maxLabel)))
        .bindValue({ read().toPercent() }, { write(it.toScale()) })
        .applyToComponent {
            name = id
            paintTicks = false
        }

    /** Combo of "same as the atmosphere above" (`null`) followed by every selectable effect. */
    private fun Row.presetCombo(id: String, read: () -> String?, write: (String?) -> Unit): Cell<ComboBox<String?>> =
        comboBox(
            listOf<String?>(null) + AtmosphereEffects.selectableIds,
            textListCellRenderer { effectId ->
                if (effectId == null) AtmosphereBundle.message("settings.preset.same") else effectName(effectId)
            },
        )
            .bindItem(read, write)
            .applyToComponent { name = id }

    private fun effectName(id: String): String = AtmosphereBundle.messageOrNull("effect.$id.name") ?: id

    private fun pointerModeName(mode: PointerMode): String =
        AtmosphereBundle.messageOrNull("settings.pointerMode.${mode.name}") ?: mode.name

    private fun showDescription(id: String?) {
        effectDescription?.text = id?.let { AtmosphereBundle.messageOrNull("effect.$it.description") } ?: ""
    }

    /** Feeds the values currently shown in the components (not yet applied to the draft) to the preview. */
    private fun updatePreview() {
        val target = preview ?: return
        val id = effectCombo?.selectedItem as? String ?: currentDraft().effectId
        val intensity = intensitySlider?.value?.toScale() ?: currentDraft().intensity
        val speed = speedSlider?.value?.toScale() ?: currentDraft().speed
        target.configure(id, intensity, speed)
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
        effectDescription = null
        effectCombo = null
        intensitySlider = null
        speedSlider = null
        preview = null
    }
}

private val MIN_PERCENT = AtmosphereSettingsState.SCALE_RANGE.start.toPercent()
private val MAX_PERCENT = AtmosphereSettingsState.SCALE_RANGE.endInclusive.toPercent()

private fun Float.toPercent(): Int = (this * 100f).roundToInt()

private fun Int.toScale(): Float = this / 100f
