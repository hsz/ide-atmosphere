package info.chrzanowski.ideatmosphere

import com.intellij.ide.util.PropertiesComponent
import com.intellij.mock.MockProject
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.impl.IdeGlassPaneImpl
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import info.chrzanowski.ideatmosphere.core.AtmosphereEvent
import info.chrzanowski.ideatmosphere.core.PointerMode
import info.chrzanowski.ideatmosphere.effects.AtmosphereEffects
import info.chrzanowski.ideatmosphere.effects.fireflies.FirefliesEffect
import info.chrzanowski.ideatmosphere.effects.sakura.SakuraEffect
import info.chrzanowski.ideatmosphere.render.DirtyRegionCollector
import info.chrzanowski.ideatmosphere.settings.AtmosphereSettingsState
import java.awt.Rectangle
import java.awt.event.KeyEvent
import javax.swing.JRootPane
import kotlin.time.Duration.Companion.milliseconds

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
    private lateinit var originalIsDarkTheme: () -> Boolean

    override fun setUp() {
        super.setUp()
        PropertiesComponent.getInstance().unsetValue(AtmosphereManager.ENABLED_KEY)
        manager = AtmosphereManager.getInstance()
        originalIsDarkTheme = manager.isDarkTheme
        // Other test classes share the application-level settings; start from the defaults regardless of run order.
        AtmosphereSettingsState.getInstance().loadState(AtmosphereSettingsState.State())
        manager.applySettings()
        first = Frame("first")
        second = Frame("second")
    }

    override fun tearDown() {
        try {
            manager.detach(project)
            Disposer.dispose(first.parent)
            Disposer.dispose(second.parent)
            manager.resumeFromSnooze()
            manager.isDarkTheme = originalIsDarkTheme
            manager.setApplicationActive(true)
            manager.isEnabled = true
            AtmosphereSettingsState.getInstance().loadState(AtmosphereSettingsState.State())
            manager.applySettings()
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
        state.pointerMode = PointerMode.OFF
        attachFirst()
        val atmosphere = manager.atmosphereFor(project)!!
        assertEquals(PointerMode.OFF, atmosphere.pointerMode)
        assertEquals(PointerMode.OFF, first.effect.lastPointerMode)
        assertFalse(atmosphere.isPointerTracking)
        state.pointerMode = PointerMode.ATTRACT
        manager.applySettings()
        assertEquals(PointerMode.ATTRACT, atmosphere.pointerMode)
        assertEquals(PointerMode.ATTRACT, first.effect.lastPointerMode)
        assertTrue(atmosphere.isPointerTracking)
        state.pointerMode = PointerMode.OFF
        manager.applySettings()
        assertFalse(atmosphere.isPointerTracking)
    }

    fun `test caret anchor producer follows the setting and the frame lifecycle`() {
        attachFirst()
        assertFalse("off by default", manager.isCaretAnchorRunning(project))
        assertEquals("attaching tells the effect there is no anchor", false, first.effect.anchor?.third)

        val state = AtmosphereSettingsState.getInstance().state
        state.caretAnchor = true
        manager.applySettings()
        assertTrue(manager.isCaretAnchorRunning(project))

        // The producer reports through the frame: with the fixture editor outside the frame the anchor stays off,
        // and the frame forwards an anchor only while attached.
        val atmosphere = manager.atmosphereFor(project)!!
        assertFalse(atmosphere.isAnchored)
        atmosphere.setAnchor(10f, 20f, true)
        assertEquals(Triple(10f, 20f, true), first.effect.anchor)

        manager.isEnabled = false
        assertFalse("no frame, no producer", manager.isCaretAnchorRunning(project))
        assertEquals(false, first.effect.anchor?.third)
        manager.isEnabled = true
        assertTrue("re-enabling restarts the producer", manager.isCaretAnchorRunning(project))

        state.caretAnchor = false
        manager.applySettings()
        assertFalse(manager.isCaretAnchorRunning(project))

        state.caretAnchor = true
        manager.applySettings()
        assertTrue(manager.isCaretAnchorRunning(project))
        manager.detach(project)
        assertFalse(manager.isCaretAnchorRunning(project))
    }

    fun `test reduce motion slows the effect, caps the ticker and drops bursts and gusts`() {
        attachFirst()
        val atmosphere = manager.atmosphereFor(project)!!
        val state = AtmosphereSettingsState.getInstance().state
        state.speed = 1.5f
        state.reduceMotion = true

        manager.applySettings()

        assertEquals(AtmosphereSettingsState.REDUCED_MOTION_SPEED, first.effect.lastSpeedScale)
        assertTrue(atmosphere.reduceMotion)
        assertTrue(manager.ticker.reduceMotion)
        assertEquals(100, manager.ticker.intervalMillis)
        manager.publish(AtmosphereEvent.SUCCESS)
        manager.publish(AtmosphereEvent.FAILURE)
        manager.endActivity(project, success = true)
        manager.previewOutcome(AtmosphereEvent.FAILURE)
        assertEquals(emptyList<AtmosphereEvent>(), first.effect.events.filter { it == AtmosphereEvent.SUCCESS || it == AtmosphereEvent.FAILURE })
        manager.publish(AtmosphereEvent.USER_IDLE)
        assertEquals(AtmosphereEvent.USER_IDLE, first.effect.events.last())

        state.reduceMotion = false
        manager.applySettings()

        assertEquals(1.5f, first.effect.lastSpeedScale)
        assertFalse(atmosphere.reduceMotion)
        assertFalse(manager.ticker.reduceMotion)
        manager.publish(AtmosphereEvent.SUCCESS)
        assertEquals(AtmosphereEvent.SUCCESS, first.effect.events.last())
    }

    fun `test reduce motion applies to frames attached or re-enabled later`() {
        val state = AtmosphereSettingsState.getInstance().state
        state.speed = 1.5f
        state.reduceMotion = true
        attachFirst()
        assertEquals(AtmosphereSettingsState.REDUCED_MOTION_SPEED, first.effect.lastSpeedScale)
        assertTrue(manager.atmosphereFor(project)!!.reduceMotion)

        manager.isEnabled = false
        manager.isEnabled = true
        assertEquals(AtmosphereSettingsState.REDUCED_MOTION_SPEED, first.effect.lastSpeedScale)
        assertTrue(manager.atmosphereFor(project)!!.reduceMotion)
    }

    fun `test per-theme presets pick the effect of the current theme brightness`() {
        val state = AtmosphereSettingsState.getInstance().state
        state.effectId = "fireflies"
        state.darkEffectId = "snow"
        state.lightEffectId = "sakura"
        attachFirst()

        manager.isDarkTheme = { true }
        manager.applySettings()
        assertEquals("snow", manager.atmosphereFor(project)!!.effect.id)
        assertEquals("snow", manager.currentEffectId)

        manager.isDarkTheme = { false }
        manager.refreshPalette()
        assertEquals("sakura", manager.atmosphereFor(project)!!.effect.id)
        assertEquals("sakura", manager.currentEffectId)
        assertEquals(1, manager.ticker.frameCount)

        manager.isDarkTheme = { true }
        manager.refreshPalette()
        assertEquals("snow", manager.atmosphereFor(project)!!.effect.id)

        state.darkEffectId = null
        manager.isDarkTheme = { true }
        manager.applySettings()
        assertEquals("fireflies", manager.atmosphereFor(project)!!.effect.id)
    }

    fun `test refreshPalette keeps the effect when the theme has no preset`() {
        attachFirst()
        val atmosphere = manager.atmosphereFor(project)!!

        manager.isDarkTheme = { false }
        manager.refreshPalette()
        manager.isDarkTheme = { true }
        manager.refreshPalette()

        assertSame(atmosphere, manager.atmosphereFor(project))
        assertEquals(0, first.effect.stopCount)
        assertEquals(2, first.effect.palettes.size)
    }

    fun `test a new frame starts with the effect of the current theme`() {
        val state = AtmosphereSettingsState.getInstance().state
        state.darkEffectId = "rain"
        state.lightEffectId = "starfield"
        manager.isDarkTheme = { true }
        val rootPane = first.rootPane
        val frame = object : com.intellij.openapi.wm.IdeFrame {
            override fun getStatusBar() = null
            override fun suggestChildFrameBounds() = Rectangle()
            override fun getProject() = this@AtmosphereManagerTest.project
            override fun setFrameTitle(title: String?) {}
            override fun getBalloonLayout(): com.intellij.ui.BalloonLayout? = null
            override fun getComponent() = rootPane
        }

        manager.attach(project, frame, first.parent)

        assertEquals("rain", manager.atmosphereFor(project)!!.effect.id)
    }

    fun `test setEffectId writes the preset of the current brightness when one exists`() {
        val state = AtmosphereSettingsState.getInstance().state
        state.effectId = "fireflies"
        state.darkEffectId = "snow"
        attachFirst()

        manager.isDarkTheme = { true }
        manager.setEffectId("rain")
        assertEquals("rain", state.darkEffectId)
        assertEquals("fireflies", state.effectId)
        assertNull(state.lightEffectId)
        assertEquals("rain", manager.atmosphereFor(project)!!.effect.id)

        manager.isDarkTheme = { false }
        manager.setEffectId("starfield")
        assertEquals("starfield", state.effectId)
        assertEquals("rain", state.darkEffectId)
        assertNull(state.lightEffectId)
        assertEquals("starfield", manager.atmosphereFor(project)!!.effect.id)
    }

    fun `test snooze freezes the animation and resume restarts it`() {
        attachFirst()
        val atmosphere = manager.atmosphereFor(project)!!
        assertFalse(manager.isSnoozed)
        assertTrue(manager.ticker.isRunning)

        manager.snooze()

        assertTrue(manager.isSnoozed)
        assertTrue(manager.isPaused)
        assertTrue(atmosphere.paused)
        assertFalse(manager.ticker.isRunning)
        assertTrue(manager.snoozeEndsAtMillis > System.currentTimeMillis() + 59 * 60 * 1000L)

        manager.resumeFromSnooze()

        assertFalse(manager.isSnoozed)
        assertFalse(manager.isPaused)
        assertFalse(atmosphere.paused)
        assertTrue(manager.ticker.isRunning)
        assertEquals(0L, manager.snoozeEndsAtMillis)
    }

    fun `test snooze lasts one hour by default and is independent of power save`() {
        assertEquals(1L, AtmosphereManager.SNOOZE_DURATION.inWholeHours)
        attachFirst()
        manager.setPaused(AtmosphereManager.PauseReason.POWER_SAVE, true)
        manager.snooze()

        manager.resumeFromSnooze()
        assertTrue(manager.isPaused)
        assertFalse(manager.ticker.isRunning)

        manager.setPaused(AtmosphereManager.PauseReason.POWER_SAVE, false)
        assertFalse(manager.isPaused)
        assertTrue(manager.ticker.isRunning)
    }

    fun `test snooze resumes by itself after its duration`() {
        attachFirst()

        manager.snooze(100.milliseconds)
        assertTrue(manager.isSnoozed)
        PlatformTestUtil.waitWithEventsDispatching("Snooze did not end", { !manager.isSnoozed }, 10)

        assertFalse(manager.isPaused)
        assertTrue(manager.ticker.isRunning)
    }

    fun `test snoozing again restarts the countdown and a new snooze outlives the old one`() {
        attachFirst()

        manager.snooze(50.milliseconds)
        manager.snooze()
        val deadline = System.currentTimeMillis() + 300
        while (System.currentTimeMillis() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(10)
        }

        assertTrue(manager.isSnoozed)
        assertFalse(manager.ticker.isRunning)
    }

    fun `test change listeners hear about effect, snooze and settings changes until disposed`() {
        attachFirst()
        val listenerParent = Disposer.newDisposable("AtmosphereManagerTest.listener")
        var calls = 0
        manager.addChangeListener(listenerParent) { calls++ }

        manager.setEffectId("snow")
        assertEquals(1, calls)
        manager.snooze()
        assertEquals(2, calls)
        manager.resumeFromSnooze()
        assertEquals(3, calls)
        manager.applySettings()
        assertEquals(4, calls)
        manager.refreshPalette()
        assertEquals(5, calls)

        Disposer.dispose(listenerParent)
        manager.setEffectId("fireflies")
        assertEquals(5, calls)
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

    fun `test idle broadcasts preserve debugger suspension without affecting other frames or ticker`() {
        attachFirst()
        val otherProject = MockProject(null, testRootDisposable)
        try {
            manager.attach(otherProject, second.rootPane, second.parent, second.effect)
            val session = Any()
            manager.beginActivity(project)
            manager.setExecutionPaused(project, session, true)
            first.effect.events.clear()

            manager.publish(AtmosphereEvent.USER_IDLE)
            assertTrue(manager.ticker.calm)
            manager.publish(AtmosphereEvent.USER_ACTIVE)
            assertFalse(manager.ticker.calm)
            assertEmpty(first.effect.events)
            assertEquals(listOf(AtmosphereEvent.USER_IDLE, AtmosphereEvent.USER_ACTIVE), second.effect.events)

            manager.publishOutcome(project, success = true)
            manager.setExecutionPaused(project, session, false)
            assertEquals(listOf(AtmosphereEvent.SUCCESS, AtmosphereEvent.ACTIVITY_STARTED), first.effect.events)
            manager.endActivity(project)
            assertEquals(AtmosphereEvent.ACTIVITY_FINISHED, first.effect.events.last())
        } finally {
            manager.detach(otherProject)
        }
    }

    fun `test targeted idle transitions cannot release suspension until the last debugger resumes`() {
        attachFirst()
        val firstSession = Any()
        val secondSession = Any()
        manager.setExecutionPaused(project, firstSession, true)
        manager.setExecutionPaused(project, secondSession, true)
        manager.setExecutionPaused(project, firstSession, false)
        first.effect.events.clear()

        manager.publish(AtmosphereEvent.USER_IDLE, project)
        manager.publish(AtmosphereEvent.USER_ACTIVE, project)
        assertEmpty(first.effect.events)

        manager.setExecutionPaused(project, secondSession, false)
        manager.publish(AtmosphereEvent.USER_IDLE, project)
        manager.publish(AtmosphereEvent.USER_ACTIVE, project)
        assertEquals(
            listOf(AtmosphereEvent.ACTIVITY_FINISHED, AtmosphereEvent.USER_IDLE, AtmosphereEvent.USER_ACTIVE),
            first.effect.events,
        )
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

    fun `test activity finishing while reactions are disabled does not leave a stale count`() {
        attachFirst()
        manager.beginActivity(project)
        val state = AtmosphereSettingsState.getInstance().state
        state.reactToActivity = false
        manager.applySettings()
        first.effect.events.clear()

        manager.endActivity(project, success = true)
        assertEmpty(first.effect.events)

        state.reactToActivity = true
        manager.applySettings()
        assertEquals(listOf(AtmosphereEvent.ACTIVITY_FINISHED), first.effect.events)
        first.effect.events.clear()
        manager.beginActivity(project)
        manager.endActivity(project)
        assertEquals(listOf(AtmosphereEvent.ACTIVITY_STARTED, AtmosphereEvent.ACTIVITY_FINISHED), first.effect.events)
    }

    fun `test activities started while reactions are disabled remain reference counted`() {
        attachFirst()
        val state = AtmosphereSettingsState.getInstance().state
        state.reactToActivity = false
        manager.applySettings()
        manager.beginActivity(project)
        manager.beginActivity(project)
        assertEmpty(first.effect.events)

        state.reactToActivity = true
        manager.applySettings()
        assertEquals(listOf(AtmosphereEvent.ACTIVITY_STARTED), first.effect.events)
        manager.endActivity(project)
        assertEquals(listOf(AtmosphereEvent.ACTIVITY_STARTED), first.effect.events)
        manager.endActivity(project)
        assertEquals(listOf(AtmosphereEvent.ACTIVITY_STARTED, AtmosphereEvent.ACTIVITY_FINISHED), first.effect.events)
    }

    fun `test re-enabling reactions restores debugger suspension rather than the busy state`() {
        attachFirst()
        manager.beginActivity(project)
        val session = Any()
        manager.setExecutionPaused(project, session, true)
        val state = AtmosphereSettingsState.getInstance().state
        state.reactToActivity = false
        manager.applySettings()
        manager.endActivity(project)
        first.effect.events.clear()

        state.reactToActivity = true
        manager.applySettings()
        assertEquals(listOf(AtmosphereEvent.EXECUTION_PAUSED), first.effect.events)
        manager.setExecutionPaused(project, session, false)
        assertEquals(listOf(AtmosphereEvent.EXECUTION_PAUSED, AtmosphereEvent.ACTIVITY_FINISHED), first.effect.events)
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

    fun `test typing producer runs while enabled with a registered frame and follows the toggles`() {
        assertFalse(manager.isTypingProducerRunning)
        attachFirst()
        assertTrue(manager.isTypingProducerRunning)

        manager.isEnabled = false
        assertFalse(manager.isTypingProducerRunning)
        manager.isEnabled = true
        assertTrue(manager.isTypingProducerRunning)

        val state = AtmosphereSettingsState.getInstance().state
        state.reactToTyping = false
        manager.applySettings()
        assertFalse(manager.isTypingProducerRunning)
        state.reactToTyping = true
        manager.applySettings()
        assertTrue(manager.isTypingProducerRunning)

        state.reactToActivity = false
        manager.applySettings()
        assertFalse("typing is one of the activity reactions", manager.isTypingProducerRunning)
        state.reactToActivity = true
        manager.applySettings()
        assertTrue(manager.isTypingProducerRunning)

        manager.detach(project)
        assertFalse(manager.isTypingProducerRunning)
    }

    fun `test keystrokes raise the flow of every frame and it settles back to zero`() {
        attachFirst()
        val secondProject = MockProject(null, second.parent)
        manager.attach(secondProject, second.rootPane, second.parent, second.effect)
        assertEquals(0f, manager.flowLevel)

        // Observed like any key press the toolkit reports; the event itself is untouched.
        val key = KeyEvent(first.rootPane, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_A, 'a')
        manager.observeKeyForTest(key)
        assertFalse(key.isConsumed)
        assertTrue(manager.flowLevel > 0f)
        assertEquals(manager.flowLevel, first.effect.lastFlow)
        assertEquals(manager.flowLevel, second.effect.lastFlow)

        // Modifier keys and releases alone are not typing.
        val before = manager.flowLevel
        manager.observeKeyForTest(KeyEvent(first.rootPane, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED))
        manager.observeKeyForTest(KeyEvent(first.rootPane, KeyEvent.KEY_RELEASED, System.currentTimeMillis(), 0, KeyEvent.VK_A, 'a'))
        assertEquals(before, manager.flowLevel)

        // The decay poll brings it back to zero and forwards the final zero.
        PlatformTestUtil.waitWithEventsDispatching("flow did not settle", { manager.flowLevel == 0f }, 15)
        assertEquals(0f, first.effect.lastFlow)
        assertEquals(0f, second.effect.lastFlow)
        assertTrue(first.effect.flows.zipWithNext().all { (a, b) -> b <= a })
    }

    fun `test disabling typing reactions resets the flow and reduce motion keeps the effect at zero flow`() {
        attachFirst()
        manager.setFlow(0.7f)
        assertEquals(0.7f, first.effect.lastFlow)

        val state = AtmosphereSettingsState.getInstance().state
        state.reduceMotion = true
        manager.applySettings()
        assertEquals("reduce motion zeroes the flow immediately", 0f, first.effect.lastFlow)
        manager.setFlow(0.9f)
        assertEquals(0f, first.effect.lastFlow)
        state.reduceMotion = false
        manager.applySettings()
        manager.setFlow(0.9f)
        assertEquals(0.9f, first.effect.lastFlow)

        manager.observeKeyForTest(KeyEvent(first.rootPane, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_B, 'b'))
        assertTrue(manager.flowLevel > 0f)
        state.reactToTyping = false
        manager.applySettings()
        assertEquals(0f, manager.flowLevel)
        assertEquals(0f, first.effect.lastFlow)
        manager.observeKeyForTest(KeyEvent(first.rootPane, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_B, 'b'))
        assertEquals("not observing while switched off", 0f, manager.flowLevel)
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
