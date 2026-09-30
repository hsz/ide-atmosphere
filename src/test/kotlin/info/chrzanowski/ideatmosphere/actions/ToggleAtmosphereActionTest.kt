package info.chrzanowski.ideatmosphere.actions

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import info.chrzanowski.ideatmosphere.AtmosphereManager

class ToggleAtmosphereActionTest : BasePlatformTestCase() {

    private lateinit var manager: AtmosphereManager

    override fun setUp() {
        super.setUp()
        PropertiesComponent.getInstance().unsetValue(AtmosphereManager.ENABLED_KEY)
        manager = AtmosphereManager.getInstance()
    }

    override fun tearDown() {
        try {
            manager.isEnabled = true
            PropertiesComponent.getInstance().unsetValue(AtmosphereManager.ENABLED_KEY)
        } finally {
            super.tearDown()
        }
    }

    fun `test isSelected mirrors the manager state`() {
        val action = ToggleAtmosphereAction()
        assertTrue(action.isSelected(TestActionEvent.createTestEvent(action)))

        manager.isEnabled = false
        assertFalse(action.isSelected(TestActionEvent.createTestEvent(action)))
    }

    fun `test setSelected drives the manager and persists the flag`() {
        val action = ToggleAtmosphereAction()
        val event = TestActionEvent.createTestEvent(action)

        action.setSelected(event, false)
        assertFalse(manager.isEnabled)
        assertFalse(PropertiesComponent.getInstance().getBoolean(AtmosphereManager.ENABLED_KEY, true))

        action.setSelected(event, true)
        assertTrue(manager.isEnabled)
        // `true` is the default, so PropertiesComponent stores it as "absent" rather than as the literal value.
        assertFalse("false" == PropertiesComponent.getInstance().getValue(AtmosphereManager.ENABLED_KEY))
        assertTrue(PropertiesComponent.getInstance().getBoolean(AtmosphereManager.ENABLED_KEY, true))
    }

    fun `test action is registered in the View Appearance group and is dumb aware`() {
        val action = ActionManager.getInstance().getAction(ACTION_ID)
        assertNotNull("action $ACTION_ID must be registered", action)
        assertTrue(action is ToggleAtmosphereAction)
        assertTrue(action is DumbAware)
        assertEquals(ActionUpdateThread.BGT, action.actionUpdateThread)
        assertEquals("Atmosphere", action.templatePresentation.text)

        val actionManager = ActionManager.getInstance()
        val group = actionManager.getAction("UIToggleActions") as DefaultActionGroup
        assertTrue(group.getChildActionsOrStubs().any { actionManager.getId(it) == ACTION_ID })
    }

    private companion object {
        const val ACTION_ID = "info.chrzanowski.ideatmosphere.ToggleAtmosphereAction"
    }
}
