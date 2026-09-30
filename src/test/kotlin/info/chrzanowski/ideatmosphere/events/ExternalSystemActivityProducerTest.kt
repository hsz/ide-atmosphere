package info.chrzanowski.ideatmosphere.events

import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.externalSystem.service.notification.ExternalSystemProgressNotificationManager
import com.intellij.openapi.externalSystem.service.remote.ExternalSystemProgressNotificationManagerImpl
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

class ExternalSystemActivityProducerTest : BasePlatformTestCase() {

    private lateinit var effect: RecordingEffect
    private val parent = Disposer.newDisposable()
    private val projectPath = "/test/external-project"
    private val tasks = mutableListOf<ExternalSystemTaskId>()
    private val notifications: ExternalSystemProgressNotificationManagerImpl
        get() = ExternalSystemProgressNotificationManager.getInstance() as ExternalSystemProgressNotificationManagerImpl

    override fun setUp() {
        super.setUp()
        effect = RecordingEffect(AtmosphereEffects.DEFAULT_ID)
        val rootPane = JRootPane()
        rootPane.glassPane = IdeGlassPaneImpl(rootPane)
        rootPane.setSize(800, 600)
        AtmosphereManager.getInstance().attach(project, rootPane, parent, effect)
    }

    override fun tearDown() {
        try {
            tasks.forEach { notifications.onEnd(projectPath, it) }
            UIUtil.dispatchAllInvocationEvents()
            Disposer.dispose(parent)
            AtmosphereManager.getInstance().detach(project)
            AtmosphereSettingsState.getInstance().loadState(AtmosphereSettingsState.State())
            UIUtil.dispatchAllInvocationEvents()
        } finally {
            super.tearDown()
        }
    }

    fun `test project sync success reaches the manager through platform notifications`() {
        val id = task(ExternalSystemTaskType.RESOLVE_PROJECT)
        notifications.onStart(projectPath, id)
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(listOf(AtmosphereEvent.ACTIVITY_STARTED), effect.events)

        notifications.onSuccess(projectPath, id)
        notifications.onEnd(projectPath, id)
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(
            listOf(AtmosphereEvent.ACTIVITY_STARTED, AtmosphereEvent.ACTIVITY_FINISHED, AtmosphereEvent.SUCCESS),
            effect.events
        )
    }

    fun `test external task success and repeated terminal callbacks finish only once`() {
        val id = task(ExternalSystemTaskType.EXECUTE_TASK)
        notifications.onStart(projectPath, id)
        notifications.onStart(projectPath, id)
        notifications.onSuccess(projectPath, id)
        notifications.onSuccess(projectPath, id)
        notifications.onEnd(projectPath, id)
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(
            listOf(AtmosphereEvent.ACTIVITY_STARTED, AtmosphereEvent.ACTIVITY_FINISHED, AtmosphereEvent.SUCCESS),
            effect.events
        )
    }

    fun `test overlapping sync and execution stay busy until both finish`() {
        val sync = task(ExternalSystemTaskType.RESOLVE_PROJECT)
        val execution = task(ExternalSystemTaskType.EXECUTE_TASK)
        notifications.onStart(projectPath, sync)
        notifications.onStart(projectPath, execution)
        notifications.onSuccess(projectPath, sync)
        notifications.onEnd(projectPath, sync)
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(listOf(AtmosphereEvent.ACTIVITY_STARTED, AtmosphereEvent.SUCCESS), effect.events)

        notifications.onSuccess(projectPath, execution)
        notifications.onEnd(projectPath, execution)
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(
            listOf(AtmosphereEvent.ACTIVITY_STARTED, AtmosphereEvent.SUCCESS,
                AtmosphereEvent.ACTIVITY_FINISHED, AtmosphereEvent.SUCCESS),
            effect.events
        )
    }

    fun `test failed sync produces failure rather than success`() {
        val id = task(ExternalSystemTaskType.RESOLVE_PROJECT)
        notifications.onStart(projectPath, id)
        notifications.onFailure(projectPath, id, IllegalStateException("Sync failed"))
        notifications.onEnd(projectPath, id)
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(
            listOf(AtmosphereEvent.ACTIVITY_STARTED, AtmosphereEvent.ACTIVITY_FINISHED, AtmosphereEvent.FAILURE),
            effect.events
        )
    }

    fun `test cancelled sync ends neutrally even if an outcome arrives afterwards`() {
        val id = task(ExternalSystemTaskType.RESOLVE_PROJECT)
        notifications.onStart(projectPath, id)
        notifications.onCancel(projectPath, id)
        notifications.onFailure(projectPath, id, IllegalStateException("Cancelled"))
        notifications.onEnd(projectPath, id)
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(listOf(AtmosphereEvent.ACTIVITY_STARTED, AtmosphereEvent.ACTIVITY_FINISHED), effect.events)
    }

    fun `test end without an outcome ends neutrally and ignores later duplicate completion`() {
        val id = task(ExternalSystemTaskType.RESOLVE_PROJECT)
        notifications.onStart(projectPath, id)
        notifications.onEnd(projectPath, id)
        notifications.onSuccess(projectPath, id)
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(listOf(AtmosphereEvent.ACTIVITY_STARTED, AtmosphereEvent.ACTIVITY_FINISHED), effect.events)
    }

    fun `test refresh task metadata and completions without a start do not react`() {
        val refresh = task(ExternalSystemTaskType.REFRESH_TASKS_LIST)
        val execution = task(ExternalSystemTaskType.EXECUTE_TASK)
        notifications.onStart(projectPath, refresh)
        notifications.onSuccess(projectPath, refresh)
        notifications.onEnd(projectPath, refresh)
        notifications.onSuccess(projectPath, execution)
        notifications.onEnd(projectPath, execution)
        UIUtil.dispatchAllInvocationEvents()
        assertEmpty(effect.events)
    }

    fun `test disabled activity reactions suppress sync and external task outcomes`() {
        AtmosphereSettingsState.getInstance().state.reactToActivity = false
        val id = task(ExternalSystemTaskType.RESOLVE_PROJECT)
        notifications.onStart(projectPath, id)
        notifications.onSuccess(projectPath, id)
        notifications.onEnd(projectPath, id)
        UIUtil.dispatchAllInvocationEvents()
        assertEmpty(effect.events)
    }

    fun `test tasks for a project that is no longer open do not affect this frame`() {
        val id = ExternalSystemTaskId.create(ProjectSystemId("GRADLE"), ExternalSystemTaskType.RESOLVE_PROJECT, "closed-project")
        notifications.onStart(projectPath, id)
        notifications.onSuccess(projectPath, id)
        notifications.onEnd(projectPath, id)
        UIUtil.dispatchAllInvocationEvents()
        assertEmpty(effect.events)
    }

    private fun task(type: ExternalSystemTaskType): ExternalSystemTaskId =
        ExternalSystemTaskId.create(ProjectSystemId("GRADLE"), type, project).also { tasks += it }
}