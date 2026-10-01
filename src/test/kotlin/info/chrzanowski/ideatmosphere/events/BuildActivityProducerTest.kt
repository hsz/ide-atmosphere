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

class BuildActivityProducerTest : BasePlatformTestCase() {

    private lateinit var producer: BuildActivityProducer
    private lateinit var effect: RecordingEffect
    private val parent = Disposer.newDisposable()

    override fun setUp() {
        super.setUp()
        producer = BuildActivityProducer(project)
        effect = RecordingEffect(AtmosphereEffects.DEFAULT_ID)
        val rootPane = JRootPane()
        rootPane.glassPane = IdeGlassPaneImpl(rootPane)
        AtmosphereManager.getInstance().attach(project, rootPane, parent, effect)
    }

    override fun tearDown() {
        try {
            Disposer.dispose(parent)
            AtmosphereManager.getInstance().detach(project)
            AtmosphereSettingsState.getInstance().loadState(AtmosphereSettingsState.State())
            UIUtil.dispatchAllInvocationEvents()
        } finally {
            super.tearDown()
        }
    }

    fun `test started and finished drive AtmosphereManager`() {
        producer.onStarted()
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(listOf(AtmosphereEvent.ACTIVITY_STARTED), effect.events)

        producer.onTerminated(isAborted = false, hasErrors = false)
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(
            listOf(AtmosphereEvent.ACTIVITY_STARTED, AtmosphereEvent.ACTIVITY_FINISHED, AtmosphereEvent.SUCCESS),
            effect.events
        )
    }

    fun `test failure cases`() {
        // Error
        producer.onTerminated(isAborted = false, hasErrors = true)
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(listOf(AtmosphereEvent.FAILURE), effect.events)
    }

    fun `test aborted builds finish neutrally without a negative visual outcome`() {
        for (errors in listOf(false, true)) {
            effect.events.clear()
            producer.onStarted()
            producer.onTerminated(isAborted = true, hasErrors = errors)
            UIUtil.dispatchAllInvocationEvents()
            assertEquals(listOf(AtmosphereEvent.ACTIVITY_STARTED, AtmosphereEvent.ACTIVITY_FINISHED), effect.events)
        }
    }

    fun `test settings respect`() {
        AtmosphereSettingsState.getInstance().state.reactToActivity = false
        producer.onStarted()
        UIUtil.dispatchAllInvocationEvents()
        assertEmpty(effect.events)
    }

    fun `test overlap behavior with DumbModeActivityProducer`() {
        val dumbProducer = DumbModeActivityProducer(project)
        
        dumbProducer.onStarted()
        producer.onStarted()
        UIUtil.dispatchAllInvocationEvents()
        
        // Coalesced
        assertEquals(listOf(AtmosphereEvent.ACTIVITY_STARTED), effect.events)
        
        dumbProducer.onTerminated()
        UIUtil.dispatchAllInvocationEvents()
        
        // Still active
        assertEquals(listOf(AtmosphereEvent.ACTIVITY_STARTED, AtmosphereEvent.SUCCESS), effect.events)
        
        producer.onTerminated(isAborted = false, hasErrors = false)
        UIUtil.dispatchAllInvocationEvents()
        
        // Finished
        assertEquals(
            listOf(
                AtmosphereEvent.ACTIVITY_STARTED,
                AtmosphereEvent.SUCCESS,
                AtmosphereEvent.ACTIVITY_FINISHED,
                AtmosphereEvent.SUCCESS
            ),
            effect.events
        )
    }
}
