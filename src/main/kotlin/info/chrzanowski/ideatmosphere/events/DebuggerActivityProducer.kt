package info.chrzanowski.ideatmosphere.events

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManagerListener
import info.chrzanowski.ideatmosphere.AtmosphereManager

/**
 * While the debugger is suspended (a breakpoint was hit or a step finished) the field of the project holds still
 * almost completely; resuming lets it move again. The debug process itself is already an activity through
 * [ExecutionActivityProducer], so this producer only reports the suspension, keyed by the debug session so several
 * sessions can be paused at once ([AtmosphereManager.setExecutionPaused]).
 *
 * Registered through the optional `com.intellij.modules.xdebugger` descriptor, so IDEs without a debugger still load
 * the plugin. Session callbacks arrive on debugger threads and are moved to the EDT.
 */
class DebuggerActivityProducer(private val project: Project) : XDebuggerManagerListener {

    override fun processStarted(debugProcess: XDebugProcess) {
        val session = debugProcess.session
        session.addSessionListener(object : XDebugSessionListener {
            override fun sessionPaused() = onPausedChanged(session, true)
            override fun sessionResumed() = onPausedChanged(session, false)
            override fun sessionStopped() = onPausedChanged(session, false)
        })
    }

    override fun processStopped(debugProcess: XDebugProcess) = onPausedChanged(debugProcess.session, false)

    internal fun onPausedChanged(session: Any, paused: Boolean) {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) AtmosphereManager.getInstance().setExecutionPaused(project, session, paused)
        }
    }
}
