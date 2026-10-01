package info.chrzanowski.ideatmosphere.events

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.impl.IdeGlassPaneImpl
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import info.chrzanowski.ideatmosphere.AtmosphereManager
import info.chrzanowski.ideatmosphere.RecordingEffect
import info.chrzanowski.ideatmosphere.core.AtmosphereEvent
import info.chrzanowski.ideatmosphere.effects.AtmosphereEffects
import info.chrzanowski.ideatmosphere.settings.AtmosphereSettingsState
import javax.swing.JRootPane

class DebuggerAndBackgroundProducersTest : BasePlatformTestCase() {

    private lateinit var effect: RecordingEffect
    private val parent = Disposer.newDisposable()
    private val manager get() = AtmosphereManager.getInstance()

    override fun setUp() {
        super.setUp()
        effect = RecordingEffect(AtmosphereEffects.DEFAULT_ID)
        val rootPane = JRootPane()
        rootPane.glassPane = IdeGlassPaneImpl(rootPane)
        manager.attach(project, rootPane, parent, effect)
    }

    override fun tearDown() {
        try {
            Disposer.dispose(parent)
            manager.detach(project)
            AtmosphereSettingsState.getInstance().loadState(AtmosphereSettingsState.State())
            UIUtil.dispatchAllInvocationEvents()
        } finally {
            super.tearDown()
        }
    }

    fun `test a breakpoint holds the field still and resuming returns to the busy state`() {
        val producer = DebuggerActivityProducer(project)
        val session = Any()
        manager.beginActivity(project) // the debug process itself
        producer.onPausedChanged(session, true)
        producer.onPausedChanged(session, true) // a step: still paused, no second event
        UIUtil.dispatchAllInvocationEvents()
        assertTrue(manager.isExecutionPaused(project))
        assertEquals(listOf(AtmosphereEvent.ACTIVITY_STARTED, AtmosphereEvent.EXECUTION_PAUSED), effect.events)

        producer.onPausedChanged(session, false)
        UIUtil.dispatchAllInvocationEvents()
        assertFalse(manager.isExecutionPaused(project))
        assertEquals(AtmosphereEvent.ACTIVITY_STARTED, effect.events.last())
    }

    fun `test activity changes while paused wait for the resume`() {
        val session = Any()
        manager.setExecutionPaused(project, session, true)
        manager.beginActivity(project)
        manager.endActivity(project, success = true)
        // The outcome still plays, but the field does not leave its paused speed.
        assertEquals(listOf(AtmosphereEvent.EXECUTION_PAUSED, AtmosphereEvent.SUCCESS), effect.events)

        manager.setExecutionPaused(project, session, false)
        assertEquals(AtmosphereEvent.ACTIVITY_FINISHED, effect.events.last())
    }

    fun `test the field stays paused until the last of several sessions resumes`() {
        val first = Any()
        val second = Any()
        manager.setExecutionPaused(project, first, true)
        manager.setExecutionPaused(project, second, true)
        manager.setExecutionPaused(project, first, false)
        assertTrue(manager.isExecutionPaused(project))
        assertEquals(listOf(AtmosphereEvent.EXECUTION_PAUSED), effect.events)
        manager.setExecutionPaused(project, second, false)
        assertEquals(listOf(AtmosphereEvent.EXECUTION_PAUSED, AtmosphereEvent.ACTIVITY_FINISHED), effect.events)
    }

    fun `test breakpoints respect react to activity`() {
        AtmosphereSettingsState.getInstance().state.reactToActivity = false
        manager.setExecutionPaused(project, Any(), true)
        assertEmpty(effect.events)
    }

    fun `test background tasks become a neutral activity only after they persist`() {
        var tasks = 0
        val producer = BackgroundTaskActivityProducer(
            onBegin = manager::beginActivity,
            onEnd = { manager.endActivity(it) },
            runningTasks = { tasks },
        )
        producer.sample(listOf(project))
        assertEmpty(effect.events)

        // A short task seen in a single sample never stirs the field.
        tasks = 1
        producer.sample(listOf(project))
        tasks = 0
        producer.sample(listOf(project))
        assertEmpty(effect.events)

        tasks = 2
        repeat(BackgroundTaskActivityProducer.MIN_BUSY_SAMPLES) { producer.sample(listOf(project)) }
        assertTrue(producer.isBusy(project))
        producer.sample(listOf(project))
        assertEquals(listOf(AtmosphereEvent.ACTIVITY_STARTED), effect.events)

        tasks = 0
        producer.sample(listOf(project))
        assertFalse(producer.isBusy(project))
        // Neutral: no success or failure for a background task.
        assertEquals(listOf(AtmosphereEvent.ACTIVITY_STARTED, AtmosphereEvent.ACTIVITY_FINISHED), effect.events)
    }

    fun `test reset ends running background activity and forgotten projects get no callback`() {
        val ended = ArrayList<Any>()
        val producer = BackgroundTaskActivityProducer(onBegin = {}, onEnd = { ended.add(it) }, runningTasks = { 1 })
        repeat(BackgroundTaskActivityProducer.MIN_BUSY_SAMPLES) { producer.sample(listOf(project)) }
        producer.reset()
        assertEquals(listOf<Any>(project), ended)

        repeat(BackgroundTaskActivityProducer.MIN_BUSY_SAMPLES) { producer.sample(listOf(project)) }
        producer.sample(emptyList())
        assertFalse(producer.isBusy(project))
        assertEquals(1, ended.size)
    }

    fun `test background sampling runs only while reacting to activity`() {
        assertTrue(manager.isBackgroundProducerRunning)
        AtmosphereSettingsState.getInstance().state.reactToActivity = false
        manager.applySettings()
        assertFalse(manager.isBackgroundProducerRunning)
    }
}
