package info.chrzanowski.ideatmosphere

import com.intellij.ide.util.PropertiesComponent
import com.intellij.mock.MockProject
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.impl.IdeGlassPaneImpl
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import info.chrzanowski.ideatmosphere.core.AtmosphereEvent
import javax.swing.JRootPane

class AtmosphereManagerTest : BasePlatformTestCase() {

    private class Frame(name: String) {
        val rootPane = JRootPane()
        val glassPane = IdeGlassPaneImpl(rootPane)
        val effect = RecordingEffect(name)
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
            PropertiesComponent.getInstance().unsetValue(AtmosphereManager.ENABLED_KEY)
            UIUtil.dispatchAllInvocationEvents()
        } finally {
            super.tearDown()
        }
    }

    private fun attachFirst() = manager.attach(project, first.rootPane, first.parent, first.effect)

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

    fun `test refresh palette reaches attached effects only`() {
        manager.refreshPalette()
        attachFirst()
        manager.refreshPalette()
        assertEquals(1, first.effect.palettes.size)

        manager.isEnabled = false
        manager.refreshPalette()
        assertEquals(1, first.effect.palettes.size)
    }
}
