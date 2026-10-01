package info.chrzanowski.ideatmosphere.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.SimpleColoredComponent
import info.chrzanowski.ideatmosphere.AtmosphereBundle
import info.chrzanowski.ideatmosphere.core.AtmosphereEvent
import info.chrzanowski.ideatmosphere.core.PointerMode
import info.chrzanowski.ideatmosphere.effects.AtmosphereEffects
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JSlider
import javax.swing.JSpinner
import javax.swing.ListCellRenderer

class AtmosphereSettingsConfigurableTest : BasePlatformTestCase() {

    private lateinit var settings: AtmosphereSettingsState
    private lateinit var configurable: AtmosphereSettingsConfigurable
    private lateinit var component: JComponent
    private var enabled = true
    private var applyCalls = 0
    private val committedStates = mutableListOf<AtmosphereSettingsState.State>()
    private val previews = mutableListOf<AtmosphereEvent>()

    override fun setUp() {
        super.setUp()
        settings = AtmosphereSettingsState()
        enabled = true
        applyCalls = 0
        committedStates.clear()
        previews.clear()
        configurable = AtmosphereSettingsConfigurable(
            settings = settings,
            readEnabled = { enabled },
            applySettings = {
                enabled = it
                applyCalls++
                committedStates += settings.state.deepCopy()
            },
            previewOutcome = { previews += it },
        )
        component = configurable.createComponent()
    }

    override fun tearDown() {
        try {
            configurable.disposeUIResources()
        } finally {
            super.tearDown()
        }
    }

    fun `test new page is unmodified and reuses its component`() {
        assertFalse(configurable.isModified())
        assertSame(component, configurable.createComponent())
        assertEquals(0, applyCalls)
        assertTrue(previews.isEmpty())
    }

    fun `test plugin-facing constructor exposes the display name`() {
        assertEquals(AtmosphereBundle.message("settings.displayName"), AtmosphereSettingsConfigurable().displayName)
        assertEquals(AtmosphereBundle.message("settings.displayName"), configurable.displayName)
    }

    fun `test controls appear in the promised order without spinners`() {
        val order = descendants(component).mapNotNull { it.name }.filter { it in CONTROL_NAMES }.toList()

        assertEquals(CONTROL_NAMES, order)
        assertTrue(descendants(component).none { it is JSpinner })
    }

    fun `test labels come from the bundle`() {
        val labels = descendants(component).filterIsInstance<JLabel>().map { it.text }.toList()
        val comments = descendants(component).filterIsInstance<JEditorPane>().map { normalized(it.text) }.toList()

        assertEquals(AtmosphereBundle.message("settings.enabled"), checkBox("enabled").text)
        assertEquals(AtmosphereBundle.message("settings.reactToActivity"), checkBox("reactToActivity").text)
        assertEquals(AtmosphereBundle.message("settings.reactToTyping"), checkBox("reactToTyping").text)
        assertTrue(comments.any { it.contains(AtmosphereBundle.message("settings.reactToTyping.comment")) })
        assertTrue(AtmosphereBundle.message("settings.pointerMode") in labels)
        assertTrue(comments.any { it.contains(AtmosphereBundle.message("settings.pointerMode.comment")) })
        assertEquals(AtmosphereBundle.message("settings.pauseOnFocusLoss"), checkBox("pauseOnFocusLoss").text)
        assertEquals(AtmosphereBundle.message("settings.reduceMotion"), checkBox("reduceMotion").text)
        assertTrue(comments.any { it.contains(AtmosphereBundle.message("settings.reduceMotion.comment")) })
        for (key in listOf("settings.themePresets", "settings.lightEffect", "settings.darkEffect")) {
            assertTrue(key, AtmosphereBundle.message(key) in labels)
        }
        assertEquals(AtmosphereBundle.message("settings.preview.success"), button("previewSuccess").text)
        assertEquals(AtmosphereBundle.message("settings.preview.failure"), button("previewFailure").text)
        for (key in listOf("settings.effect", "settings.intensity", "settings.speed", "settings.behaviour")) {
            assertTrue(key, AtmosphereBundle.message(key) in labels)
        }
        assertTrue(comments.any { it.contains(AtmosphereBundle.message("settings.reactToActivity.comment")) })
        assertTrue(normalized(control<JEditorPane>("previewComment").text).contains(AtmosphereBundle.message("settings.preview.comment")))
    }

    fun `test controls edit a draft including enabled until Apply`() {
        val before = settings.state.deepCopy()
        val effect = nonDefaultEffect()
        checkBox("enabled").isSelected = false
        effectCombo().selectedItem = effect
        slider("intensity").value = 150
        slider("speed").value = 30
        checkBox("reactToActivity").isSelected = false
        checkBox("reactToTyping").isSelected = false
        checkBox("pauseOnFocusLoss").isSelected = false
        pointerModeCombo().selectedItem = PointerMode.ATTRACT

        assertTrue(configurable.isModified())
        assertEquals(before, settings.state)
        assertTrue(enabled)
        assertEquals(0, applyCalls)

        configurable.apply()

        val expected = AtmosphereSettingsState.State(
            effectId = effect,
            intensity = 1.5f,
            speed = 0.3f,
            reactToActivity = false,
            pauseOnFocusLoss = false,
            reactToTyping = false,
            pointerMode = PointerMode.ATTRACT,
        )
        assertEquals(expected, settings.state)
        assertEquals(1, applyCalls)
        assertEquals(expected, committedStates.single())
        assertFalse(enabled)
        assertFalse(configurable.isModified())
    }

    fun `test reduce motion and per-theme presets are edited in the draft until Apply`() {
        checkBox("reduceMotion").isSelected = true
        presetCombo("lightEffectId").selectedItem = "rain"
        presetCombo("darkEffectId").selectedItem = "starfield"

        assertTrue(configurable.isModified())
        assertEquals(AtmosphereSettingsState.State(), settings.state)
        assertEquals(0, applyCalls)

        configurable.apply()

        val expected = AtmosphereSettingsState.State(reduceMotion = true, lightEffectId = "rain", darkEffectId = "starfield")
        assertEquals(expected, settings.state)
        assertEquals(expected, committedStates.single())
        assertFalse(configurable.isModified())

        presetCombo("lightEffectId").selectedItem = null
        assertTrue(configurable.isModified())
        configurable.apply()
        assertNull(settings.state.lightEffectId)
        assertEquals("starfield", settings.state.darkEffectId)

        configurable.reset()
        assertEquals(null, presetCombo("lightEffectId").selectedItem)
        assertEquals("starfield", presetCombo("darkEffectId").selectedItem)
        assertTrue(checkBox("reduceMotion").isSelected)
    }

    fun `test preset combos offer same-as-above and every effect`() {
        for (name in listOf("lightEffectId", "darkEffectId")) {
            val combo = presetCombo(name)
            val items = (0 until combo.itemCount).map { combo.getItemAt(it) }

            assertEquals(listOf<String?>(null) + AtmosphereEffects.ids, items)
            assertNull(combo.selectedItem)
            assertEquals(AtmosphereBundle.message("settings.preset.same"), renderedText(combo, null, 0))
            items.drop(1).forEachIndexed { index, id ->
                assertEquals(AtmosphereBundle.message("effect.$id.name"), renderedText(combo, id, index + 1))
            }
        }
    }

    fun `test persisted presets and reduce motion position the controls`() {
        settings.loadState(AtmosphereSettingsState.State(reduceMotion = true, darkEffectId = "snow"))

        configurable.reset()

        assertTrue(checkBox("reduceMotion").isSelected)
        assertEquals("snow", presetCombo("darkEffectId").selectedItem)
        assertNull(presetCombo("lightEffectId").selectedItem)
        assertFalse(configurable.isModified())
    }

    fun `test theme change stays in the draft until Apply`() {
        val effect = nonDefaultEffect()

        effectCombo().selectedItem = effect

        assertTrue(configurable.isModified())
        assertEquals(AtmosphereEffects.DEFAULT_ID, settings.state.effectId)
        assertEquals(0, applyCalls)

        configurable.apply()

        assertEquals(effect, settings.state.effectId)
        assertEquals(1, applyCalls)
        assertFalse(configurable.isModified())
    }

    fun `test pointer mode combo offers avoid attract and off with bundle names`() {
        val combo = pointerModeCombo()
        val items = (0 until combo.itemCount).map { combo.getItemAt(it) }

        assertEquals(PointerMode.entries, items)
        assertEquals(PointerMode.AVOID, combo.selectedItem)
        PointerMode.entries.forEachIndexed { index, mode ->
            assertEquals(AtmosphereBundle.message("settings.pointerMode.${mode.name}"), renderedText(combo, mode, index))
        }
    }

    fun `test every Apply persists once and notifies once`() {
        pointerModeCombo().selectedItem = PointerMode.OFF
        configurable.apply()
        slider("speed").value = 120
        configurable.apply()

        assertEquals(2, applyCalls)
        assertEquals(2, committedStates.size)
        assertEquals(PointerMode.OFF, committedStates[0].pointerMode)
        assertEquals(1f, committedStates[0].speed)
        assertEquals(PointerMode.OFF, committedStates[1].pointerMode)
        assertEquals(1.2f, committedStates[1].speed)
        assertEquals(committedStates[1], settings.state)
    }

    fun `test sliders span 30 to 200 percent`() {
        for (name in listOf("intensity", "speed")) {
            assertEquals(30, slider(name).minimum)
            assertEquals(200, slider(name).maximum)
            assertEquals(100, slider(name).value)
        }
    }

    fun `test slider positions map to intensity and speed`() {
        for ((percent, scale) in listOf(30 to 0.3f, 100 to 1f, 150 to 1.5f, 200 to 2f)) {
            slider("intensity").value = percent
            slider("speed").value = percent

            configurable.apply()

            assertEquals(scale, settings.state.intensity)
            assertEquals(scale, settings.state.speed)
            assertEquals(percent, slider("intensity").value)
            assertEquals(percent, slider("speed").value)
            assertFalse(configurable.isModified())
        }
    }

    fun `test intensity and speed position the sliders`() {
        for ((scale, percent) in listOf(0.3f to 30, 1f to 100, 1.5f to 150, 2f to 200)) {
            settings.loadState(AtmosphereSettingsState.State(intensity = scale, speed = scale))

            configurable.reset()

            assertEquals(percent, slider("intensity").value)
            assertEquals(percent, slider("speed").value)
            assertFalse(configurable.isModified())
        }
    }

    fun `test fractional percents are rounded and keep an untouched page unmodified`() {
        settings.loadState(AtmosphereSettingsState.State(intensity = 0.556f, speed = 1.234f))

        configurable.reset()

        assertEquals(56, slider("intensity").value)
        assertEquals(123, slider("speed").value)
        assertFalse(configurable.isModified())

        configurable.disposeUIResources()
        component = configurable.createComponent()

        assertEquals(56, slider("intensity").value)
        assertEquals(123, slider("speed").value)
        assertFalse(configurable.isModified())

        // Applying a page whose sliders were never moved keeps the saved precision instead of rounding it away.
        configurable.apply()

        assertEquals(1, applyCalls)
        assertEquals(0.556f, settings.state.intensity)
        assertEquals(1.234f, settings.state.speed)
        assertFalse(configurable.isModified())

        slider("speed").value = 124
        configurable.apply()

        assertEquals(0.556f, settings.state.intensity)
        assertEquals(1.24f, settings.state.speed)
    }

    fun `test slider ends are captioned`() {
        for ((name, captions) in listOf("intensity" to ("Subtle" to "Vivid"), "speed" to ("Calm" to "Lively"))) {
            val slider = slider(name)
            val labels = slider.labelTable

            assertTrue(slider.paintLabels)
            assertFalse(slider.paintTicks)
            assertEquals(2, labels.size())
            assertEquals(captions.first, (labels[30] as JLabel).text)
            assertEquals(captions.second, (labels[200] as JLabel).text)
            assertEquals(AtmosphereBundle.message("settings.$name.min"), captions.first)
            assertEquals(AtmosphereBundle.message("settings.$name.max"), captions.second)
        }
    }

    fun `test Reset discards draft edits`() {
        checkBox("enabled").isSelected = false
        effectCombo().selectedItem = nonDefaultEffect()
        slider("intensity").value = 42
        slider("speed").value = 200
        checkBox("reactToActivity").isSelected = false
        pointerModeCombo().selectedItem = PointerMode.OFF
        checkBox("pauseOnFocusLoss").isSelected = false
        assertTrue(configurable.isModified())

        configurable.reset()

        assertFalse(configurable.isModified())
        assertTrue(checkBox("enabled").isSelected)
        assertEquals(AtmosphereEffects.DEFAULT_ID, effectCombo().selectedItem)
        assertEquals(100, slider("intensity").value)
        assertEquals(100, slider("speed").value)
        assertTrue(checkBox("reactToActivity").isSelected)
        assertEquals(PointerMode.AVOID, pointerModeCombo().selectedItem)
        assertTrue(checkBox("pauseOnFocusLoss").isSelected)
        assertEquals(AtmosphereSettingsState.State(), settings.state)
        assertEquals(0, applyCalls)
    }

    fun `test live changes made elsewhere mark the page modified until Reset`() {
        val effect = nonDefaultEffect()
        settings.loadState(AtmosphereSettingsState.State(effectId = effect, speed = 0.5f, pointerMode = PointerMode.OFF))
        enabled = false

        assertTrue(configurable.isModified())

        configurable.reset()

        assertFalse(configurable.isModified())
        assertFalse(checkBox("enabled").isSelected)
        assertEquals(effect, effectCombo().selectedItem)
        assertEquals(50, slider("speed").value)
        assertEquals(PointerMode.OFF, pointerModeCombo().selectedItem)
        assertEquals(0, applyCalls)
    }

    fun `test disposing discards the draft`() {
        checkBox("enabled").isSelected = false
        effectCombo().selectedItem = nonDefaultEffect()
        slider("speed").value = 200

        configurable.disposeUIResources()

        assertFalse(configurable.isModified())
        configurable.apply()
        configurable.reset()
        assertEquals(0, applyCalls)
        assertEquals(AtmosphereSettingsState.State(), settings.state)
        assertTrue(enabled)

        component = configurable.createComponent()

        assertFalse(configurable.isModified())
        assertTrue(checkBox("enabled").isSelected)
        assertEquals(AtmosphereEffects.DEFAULT_ID, effectCombo().selectedItem)
        assertEquals(100, slider("speed").value)
    }

    fun `test combo lists every registered effect with its name`() {
        val combo = effectCombo()
        val items = (0 until combo.itemCount).map { combo.getItemAt(it) }

        assertEquals(AtmosphereEffects.ids.toList(), items)
        assertEquals(AtmosphereEffects.DEFAULT_ID, combo.selectedItem)
        items.forEachIndexed { index, id ->
            assertEquals(AtmosphereBundle.message("effect.$id.name"), renderedText(combo, id, index))
        }
    }

    fun `test description follows the selected effect without touching settings`() {
        assertTrue(descriptionText().contains(description(AtmosphereEffects.DEFAULT_ID)))

        for (id in AtmosphereEffects.ids) {
            effectCombo().selectedItem = id

            assertTrue(id, descriptionText().contains(description(id)))
            for (other in AtmosphereEffects.ids - id) {
                assertFalse("$id vs $other", descriptionText().contains(description(other)))
            }
        }
        assertEquals(AtmosphereSettingsState.State(), settings.state)
        assertEquals(0, applyCalls)

        configurable.reset()

        assertTrue(descriptionText().contains(description(AtmosphereEffects.DEFAULT_ID)))
    }

    fun `test description of the persisted effect is shown on a fresh page`() {
        val effect = nonDefaultEffect()
        settings.loadState(AtmosphereSettingsState.State(effectId = effect))
        configurable.disposeUIResources()

        component = configurable.createComponent()

        assertEquals(effect, effectCombo().selectedItem)
        assertTrue(descriptionText().contains(description(effect)))
        assertFalse(descriptionText().contains(description(AtmosphereEffects.DEFAULT_ID)))
    }

    fun `test bundle names and describes every effect`() {
        for (id in AtmosphereEffects.ids + listOf("fireflies", "sakura", "snow")) {
            assertTrue("effect.$id.name", AtmosphereBundle.containsKey("effect.$id.name"))
            assertTrue("effect.$id.description", AtmosphereBundle.containsKey("effect.$id.description"))
            assertTrue(AtmosphereBundle.message("effect.$id.name").isNotBlank())
            assertTrue(AtmosphereBundle.message("effect.$id.description").isNotBlank())
        }
        assertEquals("Snow", AtmosphereBundle.message("effect.snow.name"))
    }

    fun `test preview buttons fire the callback without persisting`() {
        slider("intensity").value = 200

        button("previewSuccess").doClick()
        button("previewFailure").doClick()
        button("previewSuccess").doClick()

        assertEquals(listOf(AtmosphereEvent.SUCCESS, AtmosphereEvent.FAILURE, AtmosphereEvent.SUCCESS), previews)
        assertEquals(AtmosphereSettingsState.State(), settings.state)
        assertEquals(0, applyCalls)
        assertEquals(200, slider("intensity").value)
        assertTrue(configurable.isModified())
    }

    fun `test previews need no validation and work while the atmosphere is disabled`() {
        enabled = false
        configurable.reset()
        assertFalse(checkBox("enabled").isSelected)

        button("previewFailure").doClick()

        assertEquals(listOf(AtmosphereEvent.FAILURE), previews)
        assertFalse(configurable.isModified())
        assertEquals(0, applyCalls)
    }

    private fun description(id: String): String = AtmosphereBundle.message("effect.$id.description")

    private fun descriptionText(): String = normalized(control<JEditorPane>("effectDescription").text)

    private fun normalized(text: String): String = text.replace(Regex("\\s+"), " ")

    private fun nonDefaultEffect(): String = AtmosphereEffects.ids.first { it != AtmosphereEffects.DEFAULT_ID }

    private fun renderedText(combo: JComboBox<*>, item: Any?, index: Int): String {
        @Suppress("UNCHECKED_CAST")
        val renderer = combo.renderer as ListCellRenderer<Any?>
        val rendered = renderer.getListCellRendererComponent(JList<Any?>(), item, index, false, false)
        return descendants(rendered).filterIsInstance<SimpleColoredComponent>().single().getCharSequence(false).toString()
    }

    private fun descendants(root: Component): Sequence<Component> = sequence {
        yield(root)
        if (root is Container) {
            root.components.forEach { yieldAll(descendants(it)) }
        }
    }

    private inline fun <reified T : Component> control(name: String): T =
        descendants(component).filterIsInstance<T>().single { it.name == name }

    private fun checkBox(name: String): JCheckBox = control(name)
    private fun slider(name: String): JSlider = control(name)
    private fun button(name: String): JButton = control(name)
    private fun effectCombo(): JComboBox<*> = control("effectId")
    private fun pointerModeCombo(): JComboBox<*> = control("pointerMode")
    private fun presetCombo(name: String): JComboBox<*> = control(name)

    private companion object {
        val CONTROL_NAMES = listOf(
            "enabled",
            "effectId",
            "effectDescription",
            "intensity",
            "speed",
            "reactToActivity",
            "reactToTyping",
            "previewSuccess",
            "previewFailure",
            "previewComment",
            "pointerMode",
            "pauseOnFocusLoss",
            "reduceMotion",
            "lightEffectId",
            "darkEffectId",
        )
    }
}
