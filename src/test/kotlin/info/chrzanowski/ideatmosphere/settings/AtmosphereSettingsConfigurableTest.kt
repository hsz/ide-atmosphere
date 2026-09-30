package info.chrzanowski.ideatmosphere.settings

import com.intellij.openapi.options.ConfigurationException
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Component
import java.awt.Container
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JSlider
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class AtmosphereSettingsConfigurableTest : BasePlatformTestCase() {

    private lateinit var settings: AtmosphereSettingsState
    private lateinit var configurable: AtmosphereSettingsConfigurable
    private lateinit var component: JComponent
    private var enabled = true
    private var applyCalls = 0
    private val committedStates = mutableListOf<AtmosphereSettingsState.State>()

    override fun setUp() {
        super.setUp()
        settings = AtmosphereSettingsState()
        enabled = true
        applyCalls = 0
        committedStates.clear()
        configurable = AtmosphereSettingsConfigurable(settings, { enabled }) {
            enabled = it
            applyCalls++
            committedStates += settings.state.deepCopy()
        }
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
    }

    fun `test activity toggle immediately precedes the single shared set of ADSR controls`() {
        val controls = descendants(component).filter { it is JCheckBox || it is JSpinner }.toList()
        val firstEnvelope = controls.indexOfFirst { it is JSpinner }
        assertTrue(firstEnvelope > 0)
        assertSame(checkBox("reactToActivity"), controls[firstEnvelope - 1])
        assertTrue(controls.indexOf(checkBox("pauseOnFocusLoss")) < firstEnvelope - 1)
        assertEquals(8, controls.filterIsInstance<JSpinner>().size)
        assertTrue(controls.filterIsInstance<JSpinner>().all { it.name.startsWith("activity.") })
    }

    fun `test envelope descriptions explain speed and brightness without editing settings`() {
        val movement = control<JEditorPane>("activity.movement.description").text
        val success = control<JEditorPane>("activity.successPulse.description").text
        assertTrue(movement.contains("slow down"))
        assertTrue(movement.contains("speed up"))
        assertTrue(success.contains("brightens existing particles"))
        assertTrue(success.contains("tracked operation succeeds"))
        val triggers = control<JEditorPane>("activity.triggers").text
        assertTrue(triggers.contains("Gradle"))
        assertTrue(triggers.contains("indexing"))
        assertTrue(triggers.contains("not tracked"))
        assertFalse(configurable.isModified())
        assertEquals(0, applyCalls)
    }

    fun `test controls edit a deep draft including enabled until Apply`() {
        val before = settings.state.deepCopy()
        checkBox("enabled").isSelected = false
        comboBox("effectId").selectedItem = "sakura"
        slider("intensity").value = 15
        slider("speed").value = 7
        checkBox("reactToActivity").isSelected = false
        checkBox("pauseOnFocusLoss").isSelected = false
        spinner("activity.movement.attackSeconds").value = 2.5
        spinner("activity.successPulse.sustainRatio").value = 0.35
        spinner("activity.movement.releaseSeconds").value = 4.5
        spinner("activity.successPulse.decaySeconds").value = 3.5

        assertTrue(configurable.isModified())
        assertEquals(before, settings.state)
        assertTrue(enabled)
        assertEquals(0, applyCalls)

        configurable.apply()

        assertEquals(1, applyCalls)
        assertEquals(settings.state, committedStates.single())
        assertFalse(enabled)
        assertEquals("sakura", settings.state.effectId)
        assertEquals(1.5f, settings.state.intensity)
        assertEquals(0.7f, settings.state.speed)
        assertFalse(settings.state.reactToActivity)
        assertFalse(settings.state.pauseOnFocusLoss)
        assertEquals(2.5f, settings.state.reactionProfile().movement.attackSeconds)
        assertEquals(0.35f, settings.state.reactionProfile().successPulse.sustainRatio)
        assertEquals(4.5f, settings.state.reactionProfile().movement.releaseSeconds)
        assertEquals(3.5f, settings.state.reactionProfile().successPulse.decaySeconds)
        assertFalse(configurable.isModified())

        spinner("activity.movement.attackSeconds").value = 7.5
        checkBox("enabled").isSelected = true
        assertEquals(2.5f, settings.state.reactionProfile().movement.attackSeconds)
        assertFalse(enabled)
        assertEquals(1, applyCalls)
    }

    fun `test switching themes preserves the shared profile edits`() {
        val picker = comboBox("effectId")
        spinner("activity.movement.attackSeconds").value = 2.5
        spinner("activity.successPulse.releaseSeconds").value = 3.5
        picker.selectedItem = "sakura"
        assertEquals(2.5, spinner("activity.movement.attackSeconds").value)
        assertEquals(3.5, spinner("activity.successPulse.releaseSeconds").value)
        spinner("activity.movement.attackSeconds").value = 5.5
        spinner("activity.successPulse.releaseSeconds").value = 6.5
        picker.selectedItem = "fireflies"
        assertEquals(5.5, spinner("activity.movement.attackSeconds").value)
        assertEquals(6.5, spinner("activity.successPulse.releaseSeconds").value)
        picker.selectedItem = "sakura"

        assertEquals(5.5, spinner("activity.movement.attackSeconds").value)
        configurable.apply()
        assertEquals("sakura", settings.state.effectId)
        assertEquals(5.5f, settings.state.reactionProfile().movement.attackSeconds)
        assertEquals(6.5f, settings.state.reactionProfile().successPulse.releaseSeconds)
        assertFalse(configurable.isModified())
    }

    fun `test Reset discards edits and reloads latest persisted values`() {
        spinner("activity.movement.attackSeconds").value = 7.5
        checkBox("enabled").isSelected = false
        comboBox("effectId").selectedItem = "sakura"
        val live = AtmosphereSettingsState.State(effectId = "sakura", intensity = 1.7f, speed = 0.6f,
            reactToActivity = false, pauseOnFocusLoss = false).normalized()
        live.reactionProfile().movement.attackSeconds = 4.5f
        live.reactionProfile().successPulse.sustainRatio = 0.75f
        settings.loadState(live)
        enabled = false

        configurable.reset()

        assertEquals("sakura", comboBox("effectId").selectedItem)
        assertEquals(17, slider("intensity").value)
        assertEquals(6, slider("speed").value)
        assertFalse(checkBox("enabled").isSelected)
        assertFalse(checkBox("reactToActivity").isSelected)
        assertFalse(checkBox("pauseOnFocusLoss").isSelected)
        assertEquals(4.5, spinner("activity.movement.attackSeconds").value)
        assertEquals(0.75, spinner("activity.successPulse.sustainRatio").value)
        assertEquals(live, settings.state)
        assertFalse(configurable.isModified())
        assertEquals(0, applyCalls)
    }

    fun `test restoring original control values clears modified state`() {
        val initial = spinner("activity.movement.attackSeconds").value
        spinner("activity.movement.attackSeconds").value = 8.5
        assertTrue(configurable.isModified())
        spinner("activity.movement.attackSeconds").value = initial
        assertFalse(configurable.isModified())

        checkBox("enabled").isSelected = false
        assertTrue(configurable.isModified())
        checkBox("enabled").isSelected = true
        assertFalse(configurable.isModified())
    }

    fun `test Cancel disposal never commits and reopening starts from live state`() {
        val original = settings.state.deepCopy()
        checkBox("enabled").isSelected = false
        slider("intensity").value = 20
        spinner("activity.movement.attackSeconds").value = 9.5
        spinner("activity.successPulse.releaseSeconds").value = 8.5
        assertTrue(configurable.isModified())

        configurable.disposeUIResources()
        assertFalse(configurable.isModified())
        configurable.apply()
        configurable.reset()
        assertEquals(original, settings.state)
        assertTrue(enabled)
        assertEquals(0, applyCalls)

        val live = original.deepCopy()
        live.reactionProfile().successPulse.releaseSeconds = 5.5f
        settings.loadState(live)
        val disposedComponent = component
        component = configurable.createComponent()
        assertNotSame(disposedComponent, component)
        assertTrue(checkBox("enabled").isSelected)
        assertEquals(10, slider("intensity").value)
        assertEquals(original.reactionProfile().movement.attackSeconds.toDouble(), spinner("activity.movement.attackSeconds").value)
        assertEquals(5.5, spinner("activity.successPulse.releaseSeconds").value)
        assertFalse(configurable.isModified())
    }

    fun `test Cancel after Apply keeps committed values but discards later draft`() {
        spinner("activity.movement.attackSeconds").value = 2.5
        checkBox("enabled").isSelected = false
        configurable.apply()
        val committed = settings.state.deepCopy()
        spinner("activity.movement.attackSeconds").value = 9.5
        spinner("activity.successPulse.releaseSeconds").value = 8.5
        checkBox("enabled").isSelected = true

        configurable.disposeUIResources()
        component = configurable.createComponent()

        assertEquals(committed, settings.state)
        assertEquals(2.5, spinner("activity.movement.attackSeconds").value)
        assertEquals(committed.reactionProfile().successPulse.releaseSeconds.toDouble(), spinner("activity.successPulse.releaseSeconds").value)
        assertFalse(checkBox("enabled").isSelected)
        assertFalse(enabled)
        assertEquals(1, applyCalls)
        assertFalse(configurable.isModified())
    }

    fun `test every envelope control has correct bounds and retains fractional values`() {
        val controls = descendants(component).filterIsInstance<JSpinner>().toList()
        assertEquals(8, controls.size)
        for (control in controls) {
            val model = control.model as SpinnerNumberModel
            assertEquals(0.0, model.minimum)
            assertEquals(if (control.name.endsWith("sustainRatio")) 1.0 else 10.0, model.maximum)
        }
        spinner("activity.movement.attackSeconds").value = 0.375
        spinner("activity.successPulse.sustainRatio").value = 0.625
        configurable.apply()
        assertEquals(0.375f, settings.state.reactionProfile().movement.attackSeconds)
        assertEquals(0.625f, settings.state.reactionProfile().successPulse.sustainRatio)
        assertFalse(configurable.isModified())
    }

    fun `test Apply reads text edits even before spinner commits them`() {
        val attack = spinner("activity.movement.attackSeconds")
        val sustain = spinner("activity.successPulse.sustainRatio")
        val previousAttack = attack.value
        val previousSustain = sustain.value
        (attack.editor as JSpinner.DefaultEditor).textField.text = "2.375"
        (sustain.editor as JSpinner.DefaultEditor).textField.text = "0.625"

        assertEquals(previousAttack, attack.value)
        assertEquals(previousSustain, sustain.value)
        assertTrue(configurable.isModified())
        configurable.apply()

        assertEquals(2.375f, settings.state.reactionProfile().movement.attackSeconds)
        assertEquals(0.625f, settings.state.reactionProfile().successPulse.sustainRatio)
        assertEquals(1, applyCalls)
        assertFalse(configurable.isModified())
    }

    fun `test invalid envelope text prevents persistence and manager updates`() {
        val original = settings.state.deepCopy()
        val attack = spinner("activity.movement.attackSeconds")
        val sustain = spinner("activity.successPulse.sustainRatio")
        for ((control, invalid) in listOf(attack to "not a number", attack to "11", attack to "-1",
            sustain to "2", sustain to "-1")) {
            checkBox("enabled").isSelected = false
            (control.editor as JSpinner.DefaultEditor).textField.text = invalid
            assertTrue(configurable.isModified())
            try {
                configurable.apply()
                fail("Invalid envelope text must prevent Apply: $invalid")
            } catch (_: ConfigurationException) {
                assertEquals(original, settings.state)
                assertTrue(enabled)
                assertEquals(0, applyCalls)
            }
            configurable.reset()
            assertFalse(configurable.isModified())
            assertTrue(checkBox("enabled").isSelected)
        }
    }

    fun `test Reset discards uncommitted spinner text even when model value is unchanged`() {
        val attack = spinner("activity.movement.attackSeconds")
        val previous = attack.value
        (attack.editor as JSpinner.DefaultEditor).textField.text = "2.375"
        assertTrue(configurable.isModified())
        configurable.reset()

        assertEquals(previous, attack.value)
        assertEquals((attack.editor as JSpinner.DefaultEditor).textField.formatter.valueToString(previous),
            (attack.editor as JSpinner.DefaultEditor).textField.text)
        assertFalse(configurable.isModified())
        assertEquals(0, applyCalls)
    }

    fun `test unedited fractional persisted envelopes remain exact after Apply`() {
        val live = AtmosphereSettingsState.State().normalized()
        live.reactionProfile().movement.attackSeconds = 0.12345678f
        live.reactionProfile().successPulse.sustainRatio = 0.23456789f
        settings.loadState(live)
        configurable.reset()

        assertFalse(configurable.isModified())
        configurable.apply()

        assertEquals(live, settings.state)
        assertFalse(configurable.isModified())
        assertEquals(1, applyCalls)
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
    private fun comboBox(name: String): JComboBox<*> = control(name)
    private fun slider(name: String): JSlider = control(name)
    private fun spinner(name: String): JSpinner = control(name)
}