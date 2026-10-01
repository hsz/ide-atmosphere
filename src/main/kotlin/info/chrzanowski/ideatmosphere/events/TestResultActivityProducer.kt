package info.chrzanowski.ideatmosphere.events

import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsAdapter
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import info.chrzanowski.ideatmosphere.AtmosphereManager

/**
 * Publishes one SUCCESS (every test passed) or FAILURE (at least one test failed) per test run, from the test
 * runner's own result tree rather than the process exit code.
 *
 * Registered as a project listener on `SMTRunnerEventsListener.TEST_STATUS`. The activity itself (started/finished,
 * speed-up) is already reported by the run's process through [ExecutionActivityProducer]; this producer only adds the
 * outcome and never touches the activity counters. A run that was stopped by the user, or that ran no tests at all,
 * is neutral and publishes nothing.
 */
class TestResultActivityProducer(private val project: Project) : SMTRunnerEventsAdapter() {

    override fun onTestingFinished(testsRoot: SMTestProxy.SMRootTestProxy) {
        onFinished(outcomeOf(testsRoot))
    }

    /** `null` when the run has no outcome to react to. */
    internal fun onFinished(success: Boolean?) {
        if (success == null) return
        ApplicationManager.getApplication().invokeLater {
            AtmosphereManager.getInstance().publishOutcome(project, success)
        }
    }

    companion object {
        /**
         * Reads the outcome of a finished run: `false` when any test failed or errored, `true` when tests ran and
         * none failed (ignored tests do not count against the run), `null` when the run was interrupted / terminated
         * or did not run anything.
         */
        fun outcomeOf(root: SMTestProxy.SMRootTestProxy): Boolean? {
            if (root.wasTerminated() || root.isInterrupted) return null
            // Leaves plus suites that never got children (e.g. a failed suite set-up); the root itself is skipped.
            val tests = root.allTests.filter { it !== root && it.children.isEmpty() }
            if (tests.isEmpty()) return null
            return tests.none { it.isDefect && !it.isIgnored }
        }
    }
}
