package info.chrzanowski.ideatmosphere.events

import com.intellij.execution.testframework.sm.runner.SMTestProxy
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

/**
 * The outcome-only producers (test results) share one contract: exactly one SUCCESS / FAILURE per
 * event on the project's frame, nothing for neutral results, no activity counting, and silence while
 * `reactToActivity` is off.
 */
class OutcomeProducersTest : BasePlatformTestCase() {

    private lateinit var effect: RecordingEffect
    private val parent = Disposer.newDisposable()

    override fun setUp() {
        super.setUp()
        AtmosphereSettingsState.getInstance().loadState(AtmosphereSettingsState.State())
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

    fun `test a passing test run publishes exactly one SUCCESS and a failing one exactly one FAILURE`() {
        val producer = TestResultActivityProducer(project)

        producer.onFinished(success = true)
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(listOf(AtmosphereEvent.SUCCESS), effect.events)

        producer.onFinished(success = false)
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(listOf(AtmosphereEvent.SUCCESS, AtmosphereEvent.FAILURE), effect.events)
    }

    fun `test interrupted or empty test runs are neutral`() {
        TestResultActivityProducer(project).onFinished(success = null)
        UIUtil.dispatchAllInvocationEvents()
        assertEmpty(effect.events)
    }

    fun `test the outcome is read from the test runner's result tree`() {
        fun root(vararg tests: (SMTestProxy) -> Unit): SMTestProxy.SMRootTestProxy {
            val root = SMTestProxy.SMRootTestProxy()
            root.setStarted()
            for ((index, configure) in tests.withIndex()) {
                val test = SMTestProxy("test$index", false, null)
                root.addChild(test)
                test.setStarted()
                configure(test)
            }
            return root
        }

        val passed = root({ it.setFinished() }, { it.setFinished() }).also { it.setFinished() }
        assertEquals(true, TestResultActivityProducer.outcomeOf(passed))

        val failed = root({ it.setFinished() }, { it.setTestFailed("boom", null, false); it.setFinished() }).also { it.setFinished() }
        assertEquals(false, TestResultActivityProducer.outcomeOf(failed))

        val errored = root({ it.setTestFailed("boom", "trace", true); it.setFinished() }).also { it.setFinished() }
        assertEquals(false, TestResultActivityProducer.outcomeOf(errored))

        val ignoredOnly = root({ it.setTestIgnored("skip", null); it.setFinished() }).also { it.setFinished() }
        assertEquals("ignored tests are not failures", true, TestResultActivityProducer.outcomeOf(ignoredOnly))

        val empty = root().also { it.setFinished() }
        assertNull("a run without tests has no outcome", TestResultActivityProducer.outcomeOf(empty))

        val terminated = root({ it.setFinished() }, { it.setTestFailed("boom", null, false) }).also { it.setTerminated() }
        assertNull("a run stopped by the user is neutral", TestResultActivityProducer.outcomeOf(terminated))

        // The listener entry point publishes exactly once per finished run.
        val producer = TestResultActivityProducer(project)
        producer.onTestingFinished(passed)
        producer.onTestingFinished(empty)
        producer.onTestingFinished(failed)
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(listOf(AtmosphereEvent.SUCCESS, AtmosphereEvent.FAILURE), effect.events)
    }

    fun `test outcomes never touch the activity counters`() {
        val manager = AtmosphereManager.getInstance()
        TestResultActivityProducer(project).onFinished(success = true)
        UIUtil.dispatchAllInvocationEvents()

        manager.beginActivity(project)
        assertEquals(listOf(AtmosphereEvent.SUCCESS, AtmosphereEvent.ACTIVITY_STARTED), effect.events)
        manager.endActivity(project)
        assertEquals(AtmosphereEvent.ACTIVITY_FINISHED, effect.events.last())
    }

    fun `test disabled activity reactions silence every outcome producer`() {
        AtmosphereSettingsState.getInstance().state.reactToActivity = false

        TestResultActivityProducer(project).onFinished(success = true)
        UIUtil.dispatchAllInvocationEvents()

        assertEmpty(effect.events)
    }

    fun `test outcomes for a project that is not attached are dropped`() {
        AtmosphereManager.getInstance().detach(project)

        TestResultActivityProducer(project).onFinished(success = true)
        UIUtil.dispatchAllInvocationEvents()

        assertEmpty(effect.events)
    }
}
