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

class ExecutionActivityProducerTest : BasePlatformTestCase() {

    private lateinit var producer: ExecutionActivityProducer
    private lateinit var effect: RecordingEffect
    private val parent = Disposer.newDisposable()

    override fun setUp() {
        super.setUp()
        producer = ExecutionActivityProducer()
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

    fun `test onStarted and onTerminated drive AtmosphereManager`() {
        producer.onStarted(project)
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(listOf(AtmosphereEvent.ACTIVITY_STARTED), effect.events)

        producer.onTerminated(project, 0)
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(
            listOf(AtmosphereEvent.ACTIVITY_STARTED, AtmosphereEvent.ACTIVITY_FINISHED, AtmosphereEvent.SUCCESS),
            effect.events
        )
    }

    fun `test failure outcome`() {
        producer.onTerminated(project, 1)
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(listOf(AtmosphereEvent.FAILURE), effect.events)
    }

    fun `test settings respect`() {
        AtmosphereSettingsState.getInstance().state.reactToActivity = false
        producer.onStarted(project)
        UIUtil.dispatchAllInvocationEvents()
        assertEmpty(effect.events)
    }
}
