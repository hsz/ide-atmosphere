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

class DumbModeActivityProducerTest : BasePlatformTestCase() {

    private lateinit var producer: DumbModeActivityProducer
    private lateinit var effect: RecordingEffect
    private val parent = Disposer.newDisposable()

    override fun setUp() {
        super.setUp()
        producer = DumbModeActivityProducer(project)
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

    fun `test enteredDumbMode and exitDumbMode drive AtmosphereManager`() {
        producer.onStarted()
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(listOf(AtmosphereEvent.ACTIVITY_STARTED), effect.events)

        producer.onTerminated()
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(
            listOf(AtmosphereEvent.ACTIVITY_STARTED, AtmosphereEvent.ACTIVITY_FINISHED, AtmosphereEvent.SUCCESS),
            effect.events
        )
    }

    fun `test settings respect`() {
        AtmosphereSettingsState.getInstance().state.reactToActivity = false
        producer.onStarted()
        UIUtil.dispatchAllInvocationEvents()
        assertEmpty(effect.events)
    }

    fun `test overlap behavior with BuildActivityProducer`() {
        val buildProducer = BuildActivityProducer(project)
        
        producer.onStarted()
        buildProducer.onStarted()
        UIUtil.dispatchAllInvocationEvents()
        
        // Coalesced: only one ACTIVITY_STARTED
        assertEquals(listOf(AtmosphereEvent.ACTIVITY_STARTED), effect.events)
        
        producer.onTerminated()
        UIUtil.dispatchAllInvocationEvents()
        
        // Coalesced: still active because build is running
        assertEquals(listOf(AtmosphereEvent.ACTIVITY_STARTED, AtmosphereEvent.SUCCESS), effect.events)
        
        buildProducer.onTerminated(isAborted = false, hasErrors = false)
        UIUtil.dispatchAllInvocationEvents()
        
        // Finally finished
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
