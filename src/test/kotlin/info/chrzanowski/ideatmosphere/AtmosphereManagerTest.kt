package info.chrzanowski.ideatmosphere

import com.intellij.ide.util.PropertiesComponent
import com.intellij.mock.MockProject
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.impl.IdeGlassPaneImpl
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import info.chrzanowski.ideatmosphere.core.AtmosphereEvent
import info.chrzanowski.ideatmosphere.effects.AtmosphereEffects
import info.chrzanowski.ideatmosphere.effects.fireflies.FirefliesEffect
import info.chrzanowski.ideatmosphere.effects.sakura.SakuraEffect
import info.chrzanowski.ideatmosphere.render.DirtyRegionCollector
import info.chrzanowski.ideatmosphere.settings.AtmosphereSettingsState
import java.awt.Rectangle
import javax.swing.JRootPane

class AtmosphereManagerTest : BasePlatformTestCase() {

    private class Frame(name: String) {
        val rootPane = JRootPane()
        val glassPane = IdeGlassPaneImpl(rootPane)
        // Use the default ID so applySettings doesn't swap the effect for a real FirefliesEffect
        val effect = RecordingEffect(AtmosphereEffects.DEFAULT_ID)
        val parent: Disposable = Disposer.newDisposable("AtmosphereManagerTest.$name")

        init {
            rootPane.glassPane = glassPane
            rootPane.setSize(800, 600)
            rootPane.doLayout()
        }
    }

    private lateinit var manager: AtmosphereManager
    private lateinit var first: Frame
    private lateinit var second: Frame

    override fun setUp() {
        super.setUp()
        PropertiesComponent.getInstance().unsetValue(AtmosphereManager.ENABLED_KEY)
        manager = AtmosphereManager.getInstance()
        first = Frame("first")
        second = Frame("second")
    }

    override fun tearDown() {
        try {
            manager.detach(project)
            Disposer.dispose(first.parent)
            Disposer.dispose(second.parent)
            manager.setApplicationActive(true)
            manager.isEnabled = true
            AtmosphereSettingsState.getInstance().loadState(AtmosphereSettingsState.State())
            PropertiesComponent.getInstance().unsetValue(AtmosphereManager.ENABLED_KEY)
            UIUtil.dispatchAllInvocationEvents()
        } finally {
            super.tearDown()
        }
    }

    private fun attachFirst() = manager.attach(project, first.rootPane, first.parent, first.effect)

    fun `test cursor avoidance applies immediately independently of activity gating`() {
        val state = AtmosphereSettingsState.getInstance().state
        state.reactToActivity = false
        state.avoidMouse = false
        attachFirst()
        val atmosphere = manager.atmosphereFor(project)!!
        assertFalse(atmosphere.cursorAvoidanceEnabled)
        assertFalse(atmosphere.isPointerTracking)
        state.avoidMouse = true
        manager.applySettings()
        assertTrue(atmosphere.cursorAvoidanceEnabled)
        assertTrue(atmosphere.isPointerTracking)
        state.avoidMouse = false
        manager.applySettings()
        assertFalse(atmosphere.isPointerTracking)
    }

    fun `test explicit outcome previews ignore activity gating and only play outcomes`() {
        attachFirst()
        val settings = AtmosphereSettingsState.getInstance()
        settings.state.reactToActivity = false
        val saved = settings.state.deepCopy()
        manager.previewOutcome(AtmosphereEvent.SUCCESS)
        manager.previewOutcome(AtmosphereEvent.FAILURE)
        manager.previewOutcome(AtmosphereEvent.ACTIVITY_STARTED)
        assertEquals(listOf(AtmosphereEvent.SUCCESS, AtmosphereEvent.FAILURE), first.effect.events)
        assertEquals(saved, settings.state)
        // Previews never touch the activity counters: a following real completion is still a plain outcome.
        settings.state.reactToActivity = true
        manager.endActivity(project, success = true)
        assertEquals(listOf(AtmosphereEvent.SUCCESS, AtmosphereEvent.FAILURE, AtmosphereEvent.SUCCESS), first.effect.events)
        manager.isEnabled = false
        manager.previewOutcome(AtmosphereEvent.SUCCESS)
        assertEquals(3, first.effect.events.size)
        assertFalse(manager.isEnabled)
    }

    fun `test focus loss clears the cursor even when focus fading is disabled`() {
        attachFirst()
        AtmosphereSettingsState.getInstance().state.pauseOnFocusLoss = false
        first.effect.onPointerChanged(100f, 100f, true)
        manager.setApplicationActive(false)
        assertFalse(first.effect.pointerInside)
    }

    fun `test enabled by default and attach starts the ticker and shows the glass pane`() {
        assertTrue(manager.isEnabled)
        assertFalse(manager.ticker.isRunning)

        attachFirst()

        assertTrue(first.glassPane.isVisible)
        assertEquals(1, first.effect.startCount)
        assertTrue(manager.ticker.isRunning)
        assertEquals(1, manager.ticker.frameCount)
        assertNotNull(manager.atmosphereFor(project))
    }

    fun `test attaching the same project twice is a no-op`() {
        attachFirst()
        manager.attach(project, second.rootPane, second.parent, second.effect)

        assertEquals(1, manager.ticker.frameCount)
        assertEquals(0, second.effect.startCount)
        assertFalse(second.glassPane.isVisible)
    }

    fun `test disable stops the ticker and detaches painters, re-enable re-attaches`() {
        attachFirst()

        manager.isEnabled = false
        UIUtil.dispatchAllInvocationEvents()

        assertFalse(manager.isEnabled)
        assertFalse(PropertiesComponent.getInstance().getBoolean(AtmosphereManager.ENABLED_KEY, true))
        assertFalse(manager.ticker.isRunning)
        assertEquals(0, manager.ticker.frameCount)
        assertFalse(first.glassPane.isVisible)
        assertEquals(1, first.effect.stopCount)
        assertNull(manager.atmosphereFor(project))
        assertTrue(project in manager.projects)

        manager.isEnabled = true

        assertTrue(manager.ticker.isRunning)
        assertEquals(1, manager.ticker.frameCount)
        assertTrue(first.glassPane.isVisible)
        assertEquals(2, first.effect.startCount)
        assertNotNull(manager.atmosphereFor(project))
    }

    fun `test detach and parent disposal stop the ticker when no frames remain`() {
        attachFirst()
        manager.detach(project)
        UIUtil.dispatchAllInvocationEvents()

        assertFalse(manager.ticker.isRunning)
        assertFalse(first.glassPane.isVisible)
        assertEquals(1, first.effect.stopCount)
        assertFalse(project in manager.projects)

        // re-attach, then dispose the parent instead of calling detach
        attachFirst()
        assertTrue(manager.ticker.isRunning)
        Disposer.dispose(first.parent)
        UIUtil.dispatchAllInvocationEvents()

        assertFalse(manager.ticker.isRunning)
        assertFalse(first.glassPane.isVisible)
        assertEquals(2, first.effect.stopCount)
        assertFalse(project in manager.projects)
    }

    fun `test publish reaches every attached effect or only the targeted project`() {
        attachFirst()
        // The light fixture has a single project; register the second frame under a mock project instead.
        val secondProject = MockProject(null, second.parent)
        manager.attach(secondProject, second.rootPane, second.parent, second.effect)
        assertEquals(2, manager.ticker.frameCount)

        manager.publish(AtmosphereEvent.SUCCESS)
        assertEquals(listOf(AtmosphereEvent.SUCCESS), first.effect.events)
        assertEquals(listOf(AtmosphereEvent.SUCCESS), second.effect.events)

        manager.publish(AtmosphereEvent.FAILURE, secondProject)
        assertEquals(listOf(AtmosphereEvent.SUCCESS), first.effect.events)
        assertEquals(listOf(AtmosphereEvent.SUCCESS, AtmosphereEvent.FAILURE), second.effect.events)

        manager.detach(secondProject)
        manager.publish(AtmosphereEvent.USER_IDLE)
        assertEquals(listOf(AtmosphereEvent.SUCCESS, AtmosphereEvent.USER_IDLE), first.effect.events)
        assertEquals(listOf(AtmosphereEvent.SUCCESS, AtmosphereEvent.FAILURE), second.effect.events)
    }

    fun `test power save pauses frames and stops the ticker, resume restarts it`() {
        attachFirst()
        val atmosphere = manager.atmosphereFor(project)!!

        manager.setPaused(AtmosphereManager.PauseReason.POWER_SAVE, true)
        assertTrue(manager.isPaused)
        assertTrue(atmosphere.paused)
        assertFalse(manager.ticker.isRunning)
        assertTrue(first.glassPane.isVisible)

        manager.setPaused(AtmosphereManager.PauseReason.POWER_SAVE, true)
        assertFalse(manager.ticker.isRunning)

        manager.setPaused(AtmosphereManager.PauseReason.POWER_SAVE, false)
        assertFalse(manager.isPaused)
        assertFalse(atmosphere.paused)
        assertTrue(manager.ticker.isRunning)
    }

    fun `test application deactivation fades frames out and stops the ticker once they are dormant`() {
        attachFirst()
        val atmosphere = manager.atmosphereFor(project)!!
        assertTrue(manager.isApplicationActive)
        assertTrue(atmosphere.isVisible)

        manager.setApplicationActive(false)
        assertFalse(manager.isApplicationActive)
        assertFalse(atmosphere.isVisible)
        assertFalse(atmosphere.isDormant)
        // The ticker keeps running: the frame still has to fade out.
        assertTrue(manager.ticker.isRunning)
        assertTrue(first.glassPane.isVisible)

        // The test frame is not showing, so the fade snaps to transparent on the first tick and the frame is dormant;
        // the ticker's onAllDormant callback then stops the timer.
        manager.ticker.tick()
        assertTrue(atmosphere.isDormant)
        assertEquals(0f, atmosphere.painter.opacity)
        assertFalse(manager.ticker.isRunning)
        // Still attached (painter registered, effect not stopped): only the animation is asleep.
        assertTrue(atmosphere.isAttached)
        assertTrue(first.glassPane.isVisible)
        assertEquals(0, first.effect.stopCount)

        // Repeating the same state is a no-op.
        manager.setApplicationActive(false)
        assertFalse(manager.ticker.isRunning)

        manager.setApplicationActive(true)
        assertTrue(manager.isApplicationActive)
        assertTrue(atmosphere.isVisible)
        assertFalse(atmosphere.isDormant)
        assertTrue(manager.ticker.isRunning)
    }

    fun `test a frame attached while the application is inactive starts dormant`() {
        manager.setApplicationActive(false)
        assertFalse(manager.ticker.isRunning)

        attachFirst()
        val atmosphere = manager.atmosphereFor(project)!!
        assertTrue(atmosphere.isAttached)
        assertTrue(first.glassPane.isVisible)
        assertEquals(1, first.effect.startCount)
        assertTrue(atmosphere.isDormant)
        assertEquals(0f, atmosphere.painter.opacity)
        // Nothing to animate yet: the ticker stays stopped.
        assertFalse(manager.ticker.isRunning)
        assertEquals(1, manager.ticker.frameCount)

        manager.setApplicationActive(true)
        assertFalse(atmosphere.isDormant)
        assertTrue(manager.ticker.isRunning)
    }

    fun `test power save wins over the fade and the fade resumes when power save ends`() {
        attachFirst()
        val atmosphere = manager.atmosphereFor(project)!!
        manager.setPaused(AtmosphereManager.PauseReason.POWER_SAVE, true)
        assertFalse(manager.ticker.isRunning)

        manager.setApplicationActive(false)
        assertFalse(atmosphere.isVisible)
        assertFalse(atmosphere.isDormant)
        assertFalse(manager.ticker.isRunning)

        manager.setPaused(AtmosphereManager.PauseReason.POWER_SAVE, false)
        // Not dormant yet, so the fade has to run.
        assertTrue(manager.ticker.isRunning)
        manager.ticker.tick()
        assertTrue(atmosphere.isDormant)
        assertFalse(manager.ticker.isRunning)
    }

    fun `test re-enabling while the application is inactive attaches dormant frames`() {
        attachFirst()
        manager.setApplicationActive(false)
        manager.isEnabled = false
        UIUtil.dispatchAllInvocationEvents()
        assertFalse(manager.ticker.isRunning)

        manager.isEnabled = true
        val atmosphere = manager.atmosphereFor(project)!!
        assertTrue(atmosphere.isDormant)
        assertFalse(manager.ticker.isRunning)

        manager.setApplicationActive(true)
        assertTrue(manager.ticker.isRunning)
    }

    fun `test idle producer runs while enabled with a registered frame`() {
        assertFalse(manager.isIdleProducerRunning)
        attachFirst()
        assertTrue(manager.isIdleProducerRunning)

        manager.isEnabled = false
        assertFalse(manager.isIdleProducerRunning)
        manager.isEnabled = true
        assertTrue(manager.isIdleProducerRunning)

        manager.detach(project)
        assertFalse(manager.isIdleProducerRunning)
    }

    fun `test application-wide idle and active events switch the calm tick rate`() {
        attachFirst()
        val normal = manager.ticker.intervalMillis

        manager.publish(AtmosphereEvent.USER_IDLE)
        assertTrue(manager.ticker.calm)
        assertTrue(manager.ticker.intervalMillis > normal)
        assertEquals(listOf(AtmosphereEvent.USER_IDLE), first.effect.events)

        manager.publish(AtmosphereEvent.USER_ACTIVE)
        assertFalse(manager.ticker.calm)
        assertEquals(normal, manager.ticker.intervalMillis)

        // A project-targeted idle event is delivered to that frame but does not touch the shared ticker.
        manager.publish(AtmosphereEvent.USER_IDLE, project)
        assertFalse(manager.ticker.calm)
        assertEquals(3, first.effect.events.size)

        // Stopping the producer (last frame gone) clears the calm flag.
        manager.publish(AtmosphereEvent.USER_IDLE)
        manager.detach(project)
        assertFalse(manager.ticker.calm)
    }

    fun `test activity reference counting avoids flickering`() {
        attachFirst()

        manager.beginActivity(project)
        assertEquals(listOf(AtmosphereEvent.ACTIVITY_STARTED), first.effect.events)

        manager.beginActivity(project)
        // No second START event
        assertEquals(listOf(AtmosphereEvent.ACTIVITY_STARTED), first.effect.events)

        manager.endActivity(project, success = true)
        // No FINISH yet, but SUCCESS is published
        assertEquals(listOf(AtmosphereEvent.ACTIVITY_STARTED, AtmosphereEvent.SUCCESS), first.effect.events)

        manager.endActivity(project, success = false)
        // Last activity finished -> FINISH event, plus FAILURE
        assertEquals(
            listOf(AtmosphereEvent.ACTIVITY_STARTED, AtmosphereEvent.SUCCESS, AtmosphereEvent.ACTIVITY_FINISHED, AtmosphereEvent.FAILURE),
            first.effect.events
        )
    }

    fun `test endActivity without beginActivity clamps and does not publish FINISHED but publishes outcome`() {
        attachFirst()

        manager.endActivity(project, success = true)
        // Already 0, no 1->0 transition -> no FINISHED, but SUCCESS published
        assertEquals(listOf(AtmosphereEvent.SUCCESS), first.effect.events)

        // Ensure it didn't go negative: a new beginActivity should still be 0->1
        manager.beginActivity(project)
        assertEquals(listOf(AtmosphereEvent.SUCCESS, AtmosphereEvent.ACTIVITY_STARTED), first.effect.events)
    }

    fun `test neutral completion releases activity without an outcome reaction`() {
        attachFirst()
        manager.beginActivity(project)
        manager.beginActivity(project)
        manager.endActivity(project)
        assertEquals(listOf(AtmosphereEvent.ACTIVITY_STARTED), first.effect.events)

        manager.endActivity(project)
        assertEquals(listOf(AtmosphereEvent.ACTIVITY_STARTED, AtmosphereEvent.ACTIVITY_FINISHED), first.effect.events)
        manager.endActivity(project)
        assertEquals(2, first.effect.events.size)
    }

    fun `test reactToActivity setting suppresses all activity events`() {
        attachFirst()
        AtmosphereSettingsState.getInstance().state.reactToActivity = false

        manager.beginActivity(project)
        manager.endActivity(project, success = true)

        assertEmpty(first.effect.events)

        AtmosphereSettingsState.getInstance().state.reactToActivity = true
        manager.beginActivity(project)
        assertEquals(listOf(AtmosphereEvent.ACTIVITY_STARTED), first.effect.events)
    }

    fun `test detach clears activity count`() {
        attachFirst()
        manager.beginActivity(project)
        assertEquals(listOf(AtmosphereEvent.ACTIVITY_STARTED), first.effect.events)

        manager.detach(project)
        // Clear effect recorded events by re-attaching with a fresh frame/parent to be safe
        first = Frame("re-attached")
        attachFirst()

        // If count was preserved, this would be 1->2, so no START event
        manager.beginActivity(project)
        assertEquals(listOf(AtmosphereEvent.ACTIVITY_STARTED), first.effect.events)
    }

    fun `test refresh palette reaches attached effects only`() {
        manager.refreshPalette()
        attachFirst()
        manager.refreshPalette()
        assertEquals(1, first.effect.palettes.size)

        manager.isEnabled = false
        manager.refreshPalette()
        assertEquals(1, first.effect.palettes.size)
    }

    fun `test setEffectId swaps the live effect without leaking`() {
        attachFirst()
        val oldEffect = first.effect
        val oldAtmosphere = manager.atmosphereFor(project)!!

        manager.setEffectId("new-effect")

        val newAtmosphere = manager.atmosphereFor(project)!!
        assertNotSame(oldAtmosphere, newAtmosphere)
        assertNotSame(oldEffect, newAtmosphere.effect)
        assertEquals(1, oldEffect.stopCount)
        assertTrue(newAtmosphere.isAttached)
        assertTrue(first.glassPane.isVisible)

        // Check that old atmosphere is removed from ticker
        assertFalse(oldAtmosphere in manager.ticker.targetsForTest())
        assertTrue(newAtmosphere in manager.ticker.targetsForTest())
        assertEquals(1, manager.ticker.frameCount)
    }

    fun `test applySettings pushes intensity and speed`() {
        attachFirst()
        val state = AtmosphereSettingsState.getInstance().state
        state.intensity = 0.5f
        state.speed = 1.5f

        manager.applySettings()

        assertEquals(0.5f, first.effect.lastIntensity)
        assertEquals(1.5f, first.effect.lastSpeedScale)
    }

    fun `test applySettings reaches every frame without restarting the effects`() {
        attachFirst()
        val otherProject = MockProject(null, testRootDisposable)
        try {
            manager.attach(otherProject, second.rootPane, second.parent, second.effect)
            val state = AtmosphereSettingsState.getInstance().state
            state.intensity = 1.7f
            state.speed = 0.4f
            manager.applySettings()
            assertEquals(1.7f, first.effect.lastIntensity)
            assertEquals(1.7f, second.effect.lastIntensity)
            assertEquals(0.4f, first.effect.lastSpeedScale)
            assertEquals(0.4f, second.effect.lastSpeedScale)
            assertEquals(1, first.effect.startCount)
            assertEquals(1, second.effect.startCount)
        } finally {
            manager.detach(otherProject)
        }
    }

    fun `test theme replacement creates a real effect that reacts to activity`() {
        attachFirst()
        val state = AtmosphereSettingsState.getInstance().state
        state.effectId = "sakura"
        manager.applySettings()

        val effect = manager.atmosphereFor(project)!!.effect as SakuraEffect
        effect.onEvent(AtmosphereEvent.ACTIVITY_STARTED)
        effect.update(5f, Rectangle(0, 0, 800, 600), DirtyRegionCollector(100))
        assertTrue(effect.speedMultiplier > 1f)

        state.effectId = "fireflies"
        manager.applySettings()
        val fireflies = manager.atmosphereFor(project)!!.effect as FirefliesEffect
        fireflies.onEvent(AtmosphereEvent.ACTIVITY_STARTED)
        fireflies.update(5f, Rectangle(0, 0, 800, 600), DirtyRegionCollector(100))
        assertEquals(FirefliesEffect.ACTIVITY_SPEED, fireflies.speedMultiplier, 0.001f)
    }

    fun `test reactToActivity=false stops the idle producer`() {
        attachFirst()
        assertTrue(manager.isIdleProducerRunning)

        val state = AtmosphereSettingsState.getInstance().state
        state.reactToActivity = false
        manager.applySettings()

        assertFalse(manager.isIdleProducerRunning)

        state.reactToActivity = true
        manager.applySettings()
        assertTrue(manager.isIdleProducerRunning)
    }

    fun `test pauseOnFocusLoss=false makes setApplicationActive a no-op`() {
        attachFirst()
        val atmosphere = manager.atmosphereFor(project)!!

        val state = AtmosphereSettingsState.getInstance().state
        state.pauseOnFocusLoss = false
        manager.applySettings()

        manager.setApplicationActive(false)
        assertTrue(atmosphere.isVisible)
        assertTrue(manager.ticker.isRunning)

        state.pauseOnFocusLoss = true
        manager.applySettings()
        assertFalse(atmosphere.isVisible)
        // snap to dormant on next tick
        manager.ticker.tick()
        assertTrue(atmosphere.isDormant)
    }
}
