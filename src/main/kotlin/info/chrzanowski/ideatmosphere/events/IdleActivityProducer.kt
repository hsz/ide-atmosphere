package info.chrzanowski.ideatmosphere.events

import com.intellij.ide.IdleTracker
import info.chrzanowski.ideatmosphere.core.AtmosphereEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The one IDE-event producer of the PoC: turns the stream of user-initiated AWT events
 * ([IdleTracker.events]) into [AtmosphereEvent.USER_IDLE] after [idleTimeout] without input and
 * [AtmosphereEvent.USER_ACTIVE] on the first event that follows an idle period.
 *
 * A burst of events publishes `USER_ACTIVE` at most once (only on the idle → active transition); every event
 * restarts the idle countdown (`collectLatest` cancels the pending `delay`).
 *
 * The flow and the publishing callback are injected so the state machine can be driven with a test flow and
 * virtual time; production wiring uses [forIde].
 */
class IdleActivityProducer(
    private val events: Flow<Unit>,
    private val publish: (AtmosphereEvent) -> Unit,
    private val idleTimeout: Duration = DEFAULT_IDLE_TIMEOUT,
) {

    /** `true` after [idleTimeout] passed without user input, until the next user event. */
    var isIdle: Boolean = false
        private set

    /**
     * Starts collecting [events] in [scope] (with [context] added, e.g. `Dispatchers.UI`).
     * Cancel the returned [Job] to stop; the idle state is reset so the next start begins as "active".
     */
    fun start(scope: CoroutineScope, context: CoroutineContext = EmptyCoroutineContext): Job {
        isIdle = false
        return scope.launch(context) {
            events.collectLatest {
                if (isIdle) {
                    isIdle = false
                    publish(AtmosphereEvent.USER_ACTIVE)
                }
                delay(idleTimeout)
                isIdle = true
                publish(AtmosphereEvent.USER_IDLE)
            }
        }
    }

    companion object {
        /** Requirement: fireflies calm down after ~30 s without user input. */
        val DEFAULT_IDLE_TIMEOUT: Duration = 30.seconds

        /** Production producer fed by the platform [IdleTracker]. */
        fun forIde(publish: (AtmosphereEvent) -> Unit): IdleActivityProducer =
            IdleActivityProducer(IdleTracker.getInstance().events, publish)
    }
}
