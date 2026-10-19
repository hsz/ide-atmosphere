package info.chrzanowski.ideatmosphere.widget

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import info.chrzanowski.ideatmosphere.AtmosphereBundle
import info.chrzanowski.ideatmosphere.AtmosphereManager
import info.chrzanowski.ideatmosphere.actions.SponsorAtmosphereAction
import info.chrzanowski.ideatmosphere.effects.AtmosphereEffects
import info.chrzanowski.ideatmosphere.settings.AtmosphereSettingsState

class AtmosphereStatusBarWidgetTest : BasePlatformTestCase() {

    private lateinit var manager: AtmosphereManager
    private lateinit var originalIsDarkTheme: () -> Boolean
    private var settingsOpened = 0

    override fun setUp() {
        super.setUp()
        manager = AtmosphereManager.getInstance()
        originalIsDarkTheme = manager.isDarkTheme
        manager.isDarkTheme = { true }
        AtmosphereSettingsState.getInstance().loadState(AtmosphereSettingsState.State())
        settingsOpened = 0
    }

    override fun tearDown() {
        try {
            manager.resumeFromSnooze()
            manager.isDarkTheme = originalIsDarkTheme
            AtmosphereSettingsState.getInstance().loadState(AtmosphereSettingsState.State())
        } finally {
            super.tearDown()
        }
    }

    private fun group() = AtmosphereWidgetActions.createGroup(project, { manager }, { settingsOpened++ })

    private fun update(action: AnAction): AnActionEvent =
        TestActionEvent.createTestEvent(action).also { action.update(it) }

    fun `test factory is registered in plugin xml and identified by a stable id`() {
        val factory = StatusBarWidgetFactory.EP_NAME.extensionList.filterIsInstance<AtmosphereStatusBarWidgetFactory>().single()

        assertEquals(AtmosphereStatusBarWidgetFactory.ID, factory.id)
        assertEquals(AtmosphereBundle.message("widget.displayName"), factory.displayName)
        assertTrue(factory.isEnabledByDefault)
        assertTrue(factory.isAvailable(project))
    }

    fun `test popup lists every effect then snooze and settings`() {
        val children = group().getChildren(null).toList()
        val ids = AtmosphereEffects.selectableIds

        val effects = children.take(ids.size)
        assertEquals(
            ids.map { AtmosphereBundle.message("effect.$it.name") },
            effects.map { it.templatePresentation.text },
        )
        assertTrue(effects.all { it is ToggleAction })
        assertTrue(children[ids.size] is Separator)
        assertEquals(AtmosphereBundle.message("widget.popup.snooze"), update(children[ids.size + 1]).presentation.text)
        assertEquals(AtmosphereBundle.message("widget.popup.settings"), children[ids.size + 2].templatePresentation.text)
        assertTrue(children[ids.size + 3] is Separator)
        assertTrue(children.last() is SponsorAtmosphereAction)
        assertEquals(AtmosphereBundle.message("action.info.chrzanowski.ideatmosphere.SponsorAtmosphereAction.text"), children.last().templatePresentation.text)
        assertEquals(ids.size + 5, children.size)
    }

    fun `test selecting an effect switches it and marks it selected`() {
        val effects = group().getChildren(null).filterIsInstance<ToggleAction>()
        val snow = effects.single { it.templatePresentation.text == AtmosphereBundle.message("effect.snow.name") }
        val fireflies = effects.first()
        val autumn = effects.single { it.templatePresentation.text == AtmosphereBundle.message("effect.autumn.name") }

        assertTrue(autumn.isSelected(TestActionEvent.createTestEvent(autumn)))
        assertFalse(fireflies.isSelected(TestActionEvent.createTestEvent(fireflies)))
        assertFalse(snow.isSelected(TestActionEvent.createTestEvent(snow)))

        snow.setSelected(TestActionEvent.createTestEvent(snow), true)

        assertEquals("snow", AtmosphereSettingsState.getInstance().state.effectId)
        assertEquals("snow", manager.currentEffectId)
        assertTrue(snow.isSelected(TestActionEvent.createTestEvent(snow)))
        assertFalse(autumn.isSelected(TestActionEvent.createTestEvent(autumn)))
        assertFalse(fireflies.isSelected(TestActionEvent.createTestEvent(fireflies)))

        // Deselecting the current entry does not switch to anything else.
        snow.setSelected(TestActionEvent.createTestEvent(snow), false)
        assertEquals("snow", manager.currentEffectId)
    }

    fun `test selecting an effect updates the per-theme effect of the current theme when one is set`() {
        val state = AtmosphereSettingsState.getInstance().state
        state.darkEffectId = "rain"
        val starfield = group().getChildren(null).filterIsInstance<ToggleAction>().single {
            it.templatePresentation.text == AtmosphereBundle.message("effect.starfield.name")
        }

        starfield.setSelected(TestActionEvent.createTestEvent(starfield), true)

        assertEquals("starfield", state.darkEffectId)
        assertEquals(AtmosphereEffects.DEFAULT_ID, state.effectId)
    }

    fun `test snooze entry pauses for an hour and turns into resume`() {
        val snooze = group().getChildren(null)[AtmosphereEffects.selectableIds.size + 1]

        assertEquals(AtmosphereBundle.message("widget.popup.snooze"), update(snooze).presentation.text)
        snooze.actionPerformed(TestActionEvent.createTestEvent(snooze))

        assertTrue(manager.isSnoozed)
        assertEquals(AtmosphereBundle.message("widget.popup.resume"), update(snooze).presentation.text)

        snooze.actionPerformed(TestActionEvent.createTestEvent(snooze))

        assertFalse(manager.isSnoozed)
        assertEquals(AtmosphereBundle.message("widget.popup.snooze"), update(snooze).presentation.text)
    }

    fun `test settings entry opens the settings`() {
        val settings = group().getChildren(null).filterIsInstance<AtmosphereWidgetActions.OpenSettingsAction>().single()

        settings.actionPerformed(TestActionEvent.createTestEvent(settings))

        assertEquals(1, settingsOpened)
    }

    fun `test widget shows the current effect or the paused state`() {
        val widget = AtmosphereStatusBarWidget(project) { manager }

        assertEquals(AtmosphereStatusBarWidgetFactory.ID, widget.ID())
        assertSame(widget, widget.getPresentation())
        assertEquals(
            AtmosphereBundle.message("widget.text", AtmosphereBundle.message("effect.${AtmosphereEffects.DEFAULT_ID}.name")),
            widget.getSelectedValue(),
        )
        assertEquals(AtmosphereBundle.message("widget.tooltip"), widget.getTooltipText())

        manager.setEffectId("rain")
        assertEquals(
            AtmosphereBundle.message("widget.text", AtmosphereBundle.message("effect.rain.name")),
            widget.getSelectedValue(),
        )

        manager.snooze()
        assertEquals(AtmosphereBundle.message("widget.text.paused"), widget.getSelectedValue())
        assertTrue(widget.getTooltipText().startsWith(AtmosphereBundle.message("widget.tooltip.paused", "").substringBefore('{')))

        manager.resumeFromSnooze()
        assertEquals(AtmosphereBundle.message("widget.tooltip"), widget.getTooltipText())
        Disposer.dispose(widget)
    }

    fun `test popup builds a list popup with the project context`() {
        val widget = AtmosphereStatusBarWidget(project) { manager }

        val popup = widget.getPopup()

        assertNotNull(popup)
        Disposer.dispose(popup)
        Disposer.dispose(widget)
    }
}
