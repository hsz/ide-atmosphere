package info.chrzanowski.ideatmosphere.events

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.ex.StatusBarEx
import com.intellij.util.concurrency.annotations.RequiresEdt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Turns generic background tasks (the progress shown in a project's status bar: VCS refreshes, downloads, inspections,
 * plugin tasks, ...) into a reference-counted activity of that project, so the atmosphere stirs while the IDE is busy
 * with anything, not only with builds, runs, indexing and sync.
 *
 * The platform's task-start listener is internal API, so the producer *samples* the status bar instead: the owner
 * calls [sample] every [POLL_INTERVAL] with the live projects. A project becomes busy ([onBegin]) only once tasks
 * were seen in [MIN_BUSY_SAMPLES] consecutive samples, so the many sub-second tasks never flicker the field, and idle
 * again ([onEnd], a neutral finish without any outcome) on the first sample without tasks. Builds and indexing also
 * appear in the status bar; that only adds one more reference to an activity that is already counted.
 */
class BackgroundTaskActivityProducer(
    private val onBegin: (Project) -> Unit,
    private val onEnd: (Project) -> Unit,
    /** Number of background tasks currently shown for a project; replaceable so tests need no status bar. */
    private val runningTasks: (Project) -> Int = ::statusBarTaskCount,
) {

    private val busySamples = HashMap<Project, Int>()
    private val busy = HashSet<Project>()

    /** `true` while [project] is reported busy because of background tasks. */
    fun isBusy(project: Project): Boolean = project in busy

    /** Samples [projects] once; projects no longer listed are forgotten without a callback (they were detached). */
    @RequiresEdt
    fun sample(projects: Collection<Project>) {
        busySamples.keys.retainAll(projects.toSet())
        busy.retainAll(projects.toSet())
        for (project in projects) {
            if (project.isDisposed) continue
            if (runningTasks(project) > 0) {
                val samples = (busySamples[project] ?: 0) + 1
                busySamples[project] = samples
                if (samples >= MIN_BUSY_SAMPLES && busy.add(project)) onBegin(project)
            } else {
                busySamples.remove(project)
                if (busy.remove(project)) onEnd(project)
            }
        }
    }

    /** Ends every activity this producer began (neutrally) and forgets all samples; used when sampling stops. */
    @RequiresEdt
    fun reset() {
        val ended = busy.toList()
        busy.clear()
        busySamples.clear()
        for (project in ended) onEnd(project)
    }

    companion object {
        /** How often the owner should call [sample]. */
        val POLL_INTERVAL: Duration = 1.seconds

        /** Consecutive busy samples before a project counts as busy (≈ one second of uninterrupted background work). */
        const val MIN_BUSY_SAMPLES: Int = 2

        private fun statusBarTaskCount(project: Project): Int =
            (WindowManager.getInstance().getStatusBar(project) as? StatusBarEx)?.backgroundProcessModels?.size ?: 0
    }
}
