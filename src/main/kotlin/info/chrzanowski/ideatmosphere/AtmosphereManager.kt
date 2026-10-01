package info.chrzanowski.ideatmosphere

import com.intellij.ide.PowerSaveMode
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.UI
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.IdeFrame
import com.intellij.ui.JBColor
import com.intellij.util.concurrency.annotations.RequiresEdt
import info.chrzanowski.ideatmosphere.core.AtmosphereEffect
import info.chrzanowski.ideatmosphere.core.AtmosphereEvent
import info.chrzanowski.ideatmosphere.core.AtmospherePalette
import info.chrzanowski.ideatmosphere.core.EffectContext
import info.chrzanowski.ideatmosphere.effects.AtmosphereEffects
import info.chrzanowski.ideatmosphere.effects.fireflies.FirefliesEffect
import info.chrzanowski.ideatmosphere.events.BackgroundTaskActivityProducer
import info.chrzanowski.ideatmosphere.events.CaretAnchorProducer
import info.chrzanowski.ideatmosphere.events.IdleActivityProducer
import info.chrzanowski.ideatmosphere.events.TypingRhythmProducer
import info.chrzanowski.ideatmosphere.frame.FrameAtmosphere
import info.chrzanowski.ideatmosphere.render.AtmosphereTicker
import info.chrzanowski.ideatmosphere.settings.AtmosphereSettingsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.annotations.TestOnly
import java.awt.AWTEvent
import java.util.EnumSet
import java.util.HashMap
import javax.swing.JRootPane
import javax.swing.SwingUtilities
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

/**
 * Application-level owner of everything atmosphere-related: the registry of project frames, the shared
 * [AtmosphereTicker], the persisted enabled flag, pause reasons, event fan-out, the [IdleActivityProducer] and the
 * [TypingRhythmProducer] (both running while the plugin is enabled and at least one frame is registered), and one
 * [CaretAnchorProducer] per live frame while the caret anchor setting is on.
 *
 * Every project frame is [attach]ed once (by `AtmosphereProjectActivity`) and stays registered until the
 * project closes ([detach], or disposal of the `parent` passed to [attach]). While the plugin is disabled the
 * registrations are kept but no [FrameAtmosphere] exists for them, so re-enabling re-attaches all frames.
 *
 * Application (de)activation ([setApplicationActive]) fades every frame out or in; the ticker keeps running while
 * frames are fading and is stopped by [AtmosphereTicker.onAllDormant] once all of them are transparent, so a
 * deactivated IDE costs nothing. Frames attached while the application is inactive start dormant.
 *
 * All mutating methods are EDT-only.
 */
@Service(Service.Level.APP)
class AtmosphereManager(
    /** Plugin-scoped coroutine scope for event producers (the idle/active tracker). */
    val coroutineScope: CoroutineScope,
) : Disposable {

    /**
     * Why the animation is currently frozen. Several reasons may be active at once.
     * [USER_SNOOZE] is set by [snooze] and never persisted: it ends after the snooze duration or on [resumeFromSnooze].
     */
    enum class PauseReason { POWER_SAVE, USER_SNOOZE }

    private class Registration(
        val project: Project,
        val rootPane: JRootPane,
        var effect: AtmosphereEffect,
        val parent: Disposable,
    ) {
        var atmosphere: FrameAtmosphere? = null
        var attachDisposable: Disposable? = null
        var caretProducer: CaretAnchorProducer? = null
        var parentDisposed = false
    }

    private val registrations = LinkedHashMap<Project, Registration>()
    private val activityCounts = HashMap<Project, Int>()
    /** Suspended executions (debug sessions) per project; the field holds still while any is paused. */
    private val pausedExecutions = HashMap<Project, MutableSet<Any>>()
    private val pauseReasons: EnumSet<PauseReason> = EnumSet.noneOf(PauseReason::class.java)
    private var disposed = false
    private var idleJob: Job? = null
    private var snoozeJob: Job? = null
    private var typingJob: Job? = null
    private var backgroundJob: Job? = null

    /** Status-bar background tasks → reference-counted, neutral activity; sampled while [isBackgroundProducerRunning]. */
    private val backgroundProducer = BackgroundTaskActivityProducer(onBegin = ::beginActivity, onEnd = { endActivity(it) })
    private var typingAttached = false
    private val changeListeners = ArrayList<() -> Unit>()

    /** Keystroke rhythm → flow level; forwards level changes to the frames and polls the decay while non-zero. */
    private val typingProducer = TypingRhythmProducer(onLevelChanged = ::onFlowChanged)

    /** Dark/light decision of the current theme; replaceable so tests need no LaF switch. */
    internal var isDarkTheme: () -> Boolean = { !JBColor.isBright() }

    /** Created lazily so merely loading the service (e.g. from an application listener) allocates no timer. */
    private val tickerDelegate = lazy {
        AtmosphereTicker().also {
            it.onAllDormant = ::updateTicker
            it.reduceMotion = AtmosphereSettingsState.getInstance().state.reduceMotion
        }
    }
    val ticker: AtmosphereTicker by tickerDelegate

    /** Wall-clock millis at which the current snooze ends, or `0` when not snoozed. */
    var snoozeEndsAtMillis: Long = 0L
        private set

    /** `false` while the IDE application is deactivated (frames fade out and go dormant). */
    var isApplicationActive: Boolean = true
        private set

    init {
        if (PowerSaveMode.isEnabled()) pauseReasons.add(PauseReason.POWER_SAVE)
    }

    /** Persisted user toggle (`View | Appearance | Atmosphere`). Changing it attaches/detaches all frames. */
    var isEnabled: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(ENABLED_KEY, true)
        @RequiresEdt
        set(value) {
            if (value == isEnabled) return
            PropertiesComponent.getInstance().setValue(ENABLED_KEY, value, true)
            if (value) {
                registrations.values.forEach { activate(it) }
            } else {
                registrations.values.forEach { deactivate(it) }
            }
            updateTicker()
            updateIdleProducer()
            updateTypingProducer()
            updateBackgroundProducer()
        }

    val isPaused: Boolean get() = pauseReasons.isNotEmpty()

    /** `true` while the user's snooze (see [snooze]) is active. */
    val isSnoozed: Boolean get() = PauseReason.USER_SNOOZE in pauseReasons

    /**
     * Id of the effect that applies to the current theme brightness (per-theme effect or the general effect) as
     * configured.
     */
    val currentEffectId: String
        get() = AtmosphereSettingsState.getInstance().state.normalized().effectIdFor(isDarkTheme())

    /**
     * Registers [listener] to be called after the user-visible state changed (effect, snooze, settings); it is removed
     * when [parent] is disposed. Used by the status bar widget to refresh itself.
     */
    fun addChangeListener(parent: Disposable, listener: () -> Unit) {
        changeListeners.add(listener)
        Disposer.register(parent) { changeListeners.remove(listener) }
    }

    private fun notifyChanged() {
        for (listener in changeListeners.toList()) listener()
    }

    /** Projects currently registered (attached or waiting for re-enable). */
    val projects: Set<Project> get() = registrations.keys

    /** Live [FrameAtmosphere] of [project], or `null` when not attached or disabled. */
    fun atmosphereFor(project: Project): FrameAtmosphere? = registrations[project]?.atmosphere

    /** `true` while the caret of [project]'s selected editor is being followed as an anchor. */
    fun isCaretAnchorRunning(project: Project): Boolean = registrations[project]?.caretProducer != null

    /** `true` while the idle/active producer is collecting `IdleTracker` events. */
    val isIdleProducerRunning: Boolean get() = idleJob?.isActive == true

    /** `true` while background tasks in the status bar are sampled. */
    val isBackgroundProducerRunning: Boolean get() = backgroundJob?.isActive == true

    /** `true` while some execution (debug session) of [project] is suspended. */
    fun isExecutionPaused(project: Project): Boolean = pausedExecutions[project]?.isNotEmpty() == true

    /** `true` while keystrokes are observed for the typing flow. */
    val isTypingProducerRunning: Boolean get() = typingAttached

    /** Current typing flow level in `0..1` as last forwarded to the frames. */
    val flowLevel: Float get() = typingProducer.level

    /**
     * Feeds [event] to the typing observer as the toolkit would. Tests cannot route a key event through a focus
     * owner, so they call this instead of dispatching; a no-op while the typing producer is not running.
     */
    @TestOnly
    internal fun observeKeyForTest(event: AWTEvent) {
        if (typingAttached) typingProducer.observe(event)
    }

    /** Registers the project's main [frame]; a no-op when the frame has no root pane or the project is known. */
    @RequiresEdt
    fun attach(project: Project, frame: IdeFrame, parent: Disposable) {
        val rootPane = frame.component?.let { SwingUtilities.getRootPane(it) }
        if (rootPane == null) {
            LOG.info("No root pane for ${project.name}; atmosphere not attached")
            return
        }
        val state = AtmosphereSettingsState.getInstance().state
        attach(project, rootPane, parent, AtmosphereEffects.createOrDefault(state.effectIdFor(isDarkTheme())))
    }

    /**
     * Registers [rootPane] as the frame of [project] and animates it with [effect] while enabled.
     * Disposing [parent] is equivalent to [detach].
     */
    @RequiresEdt
    fun attach(
        project: Project,
        rootPane: JRootPane,
        parent: Disposable,
        effect: AtmosphereEffect = AtmosphereEffects.createOrDefault(null),
    ) {
        if (disposed || registrations.containsKey(project)) return
        val registration = Registration(project, rootPane, effect, parent)
        registrations[project] = registration
        Disposer.register(parent) {
            registration.parentDisposed = true
            detach(project)
        }
        if (isEnabled) activate(registration)
        updateTicker()
        updateIdleProducer()
        updateTypingProducer()
        updateBackgroundProducer()
    }

    /** Stops and forgets the atmosphere of [project]. Safe to call for unknown projects and more than once. */
    @RequiresEdt
    fun detach(project: Project) {
        val registration = registrations.remove(project) ?: return
        activityCounts.remove(project)
        pausedExecutions.remove(project)
        deactivate(registration)
        updateTicker()
        updateIdleProducer()
        updateTypingProducer()
        updateBackgroundProducer()
    }

    /**
     * Delivers [event] to every attached effect, or only to the frame of [project] when given.
     * Application-wide `USER_IDLE` / `USER_ACTIVE` additionally switch the ticker's calm (reduced-rate) mode.
     */
    @RequiresEdt
    fun publish(event: AtmosphereEvent, project: Project? = null) {
        if (disposed) return
        if (project != null) {
            registrations[project]?.atmosphere?.publish(event)
        } else {
            when (event) {
                AtmosphereEvent.USER_IDLE -> ticker.calm = true
                AtmosphereEvent.USER_ACTIVE -> ticker.calm = false
                else -> Unit
            }
            for (registration in registrations.values) registration.atmosphere?.publish(event)
        }
    }

    /**
     * Increments the activity counter for [project]. If this is the first activity (0→1), publishes
     * [AtmosphereEvent.ACTIVITY_STARTED]. Generic and reference-counted so overlapping tasks
     * (e.g. build + run) don't flicker the start/finish state. Respects `reactToActivity` setting.
     */
    @RequiresEdt
    fun beginActivity(project: Project) {
        if (disposed) return
        val state = AtmosphereSettingsState.getInstance().state
        if (!state.reactToActivity) return

        val count = activityCounts.getOrDefault(project, 0) + 1
        activityCounts[project] = count
        // While execution is suspended the field stays still; resuming re-publishes the busy state.
        if (count == 1 && !isExecutionPaused(project)) {
            publish(AtmosphereEvent.ACTIVITY_STARTED, project)
        }
    }

    /**
     * Marks the execution identified by [key] (a debug session) of [project] as suspended or running again. While at
     * least one execution is suspended the field holds still ([AtmosphereEvent.EXECUTION_PAUSED]); when the last one
     * resumes it returns to the busy speed if activities are still running, else to rest. Respects `reactToActivity`.
     */
    @RequiresEdt
    fun setExecutionPaused(project: Project, key: Any, paused: Boolean) {
        if (disposed || !registrations.containsKey(project)) return
        val wasPaused = isExecutionPaused(project)
        if (paused) {
            pausedExecutions.getOrPut(project) { HashSet() }.add(key)
        } else {
            pausedExecutions[project]?.let { keys ->
                keys.remove(key)
                if (keys.isEmpty()) pausedExecutions.remove(project)
            }
        }
        val nowPaused = isExecutionPaused(project)
        if (wasPaused == nowPaused || !AtmosphereSettingsState.getInstance().state.reactToActivity) return
        val event = when {
            nowPaused -> AtmosphereEvent.EXECUTION_PAUSED
            activityCounts.getOrDefault(project, 0) > 0 -> AtmosphereEvent.ACTIVITY_STARTED
            else -> AtmosphereEvent.ACTIVITY_FINISHED
        }
        publish(event, project)
    }

    /**
     * Decrements the activity counter for [project]. If this reaches zero (1→0), publishes
     * [AtmosphereEvent.ACTIVITY_FINISHED]. Always publishes [AtmosphereEvent.SUCCESS] or
     * [AtmosphereEvent.FAILURE] regardless of counter transition. Respects `reactToActivity` setting.
     */
    @RequiresEdt
    fun endActivity(project: Project, success: Boolean) {
        finishActivity(project, if (success) AtmosphereEvent.SUCCESS else AtmosphereEvent.FAILURE)
    }

    /** Ends activity without a success/failure reaction, for cancellation or an unknown outcome. */
    @RequiresEdt
    fun endActivity(project: Project) {
        finishActivity(project, null)
    }

    /**
     * Plays a `SUCCESS` / `FAILURE` reaction on the frame of [project] for an outcome that has no activity of its own
     * (test results, commits, pushes). Respects `reactToActivity` and never touches the activity counters.
     */
    @RequiresEdt
    fun publishOutcome(project: Project, success: Boolean) {
        if (disposed) return
        if (!AtmosphereSettingsState.getInstance().state.reactToActivity) return
        publish(if (success) AtmosphereEvent.SUCCESS else AtmosphereEvent.FAILURE, project)
    }

    /**
     * Pushes the typing flow level (`0..1`) to every attached frame; the [TypingRhythmProducer] calls this through
     * [onFlowChanged], and tests may call it directly.
     */
    @RequiresEdt
    fun setFlow(level: Float) {
        if (disposed) return
        for (registration in registrations.values) registration.atmosphere?.setFlow(level)
    }

    private fun finishActivity(project: Project, outcome: AtmosphereEvent?) {
        if (disposed) return
        val state = AtmosphereSettingsState.getInstance().state
        if (!state.reactToActivity) return

        val oldCount = activityCounts.getOrDefault(project, 0)
        val newCount = (oldCount - 1).coerceAtLeast(0)
        if (newCount == 0) {
            activityCounts.remove(project)
        } else {
            activityCounts[project] = newCount
        }

        if (oldCount == 1 && newCount == 0 && !isExecutionPaused(project)) {
            publish(AtmosphereEvent.ACTIVITY_FINISHED, project)
        }

        if (outcome != null) publish(outcome, project)
    }

    /**
     * The theme changed: switches to the per-theme effect when one is configured, recomputes the palette from the
     * current LaF and pushes it to every attached effect.
     */
    @RequiresEdt
    fun refreshPalette() {
        if (registrations.isEmpty() || disposed) return
        swapEffects(AtmosphereSettingsState.getInstance().state.effectIdFor(isDarkTheme()))
        val palette = AtmospherePalette.fromCurrentLaf()
        for (registration in registrations.values) registration.atmosphere?.paletteChanged(palette)
        notifyChanged()
    }

    /** Freezes (or resumes) the animation for [reason]; the ticker stops while any reason is active. */
    @RequiresEdt
    fun setPaused(reason: PauseReason, paused: Boolean) {
        val changed = if (paused) pauseReasons.add(reason) else pauseReasons.remove(reason)
        if (!changed) return
        val frozen = isPaused
        for (registration in registrations.values) registration.atmosphere?.paused = frozen
        updateTicker()
        notifyChanged()
    }

    /**
     * Freezes the animation for [duration] (one hour by default) via [PauseReason.USER_SNOOZE], then resumes it.
     * Snoozing again restarts the countdown; the snooze is not persisted, so a restart ends it.
     */
    @RequiresEdt
    fun snooze(duration: Duration = SNOOZE_DURATION) {
        if (disposed) return
        snoozeJob?.cancel()
        snoozeEndsAtMillis = System.currentTimeMillis() + duration.inWholeMilliseconds
        snoozeJob = coroutineScope.launch(Dispatchers.UI) {
            delay(duration)
            snoozeJob = null
            resumeFromSnooze()
        }
        setPaused(PauseReason.USER_SNOOZE, true)
    }

    /** Ends a running [snooze] immediately; a no-op when not snoozed. */
    @RequiresEdt
    fun resumeFromSnooze() {
        snoozeJob?.cancel()
        snoozeJob = null
        snoozeEndsAtMillis = 0L
        setPaused(PauseReason.USER_SNOOZE, false)
    }

    /**
     * Fades every frame out when the application is deactivated and back in on activation. The ticker runs on
     * while frames are fading and stops once all of them are dormant (see [AtmosphereTicker.onAllDormant]).
     */
    @RequiresEdt
    fun setApplicationActive(active: Boolean) {
        if (disposed || isApplicationActive == active) return
        isApplicationActive = active
        if (!active) registrations.values.forEach { it.atmosphere?.clearPointer() }

        val state = AtmosphereSettingsState.getInstance().state
        if (!state.pauseOnFocusLoss && !active) return

        for (registration in registrations.values) registration.atmosphere?.setVisible(active)
        updateTicker()
    }

    /**
     * Applies the current settings to all registered frames and event producers.
     */
    @RequiresEdt
    fun applySettings() {
        if (disposed) return
        val state = AtmosphereSettingsState.getInstance().state

        // 1. Effect: the per-theme effect of the current theme brightness, else the general effect
        swapEffects(state.effectIdFor(isDarkTheme()))

        // 2. Intensity & Speed, Reduce Motion (slower speed, capped FPS, no bursts/gusts)
        if (tickerDelegate.isInitialized()) ticker.reduceMotion = state.reduceMotion
        for (registration in registrations.values) {
            val atmosphere = registration.atmosphere ?: continue
            atmosphere.effect.setIntensity(state.intensity)
            atmosphere.effect.setSpeedScale(state.effectiveSpeed())
            atmosphere.reduceMotion = state.reduceMotion
            atmosphere.pointerMode = state.pointerMode
        }

        // 3. React to activity, background tasks, typing and the caret
        updateIdleProducer()
        updateBackgroundProducer()
        updateTypingProducer()
        updateCaretProducers()

        // 4. Pause on focus loss
        if (!state.pauseOnFocusLoss) {
            for (registration in registrations.values) registration.atmosphere?.setVisible(true)
        } else {
            for (registration in registrations.values) registration.atmosphere?.setVisible(isApplicationActive)
        }
        updateTicker()
        notifyChanged()
    }

    /**
     * Switches the effect of the current theme brightness to [id] for all registered frames and remembers it: the
     * per-theme effect of the current brightness when one is configured, the general effect otherwise.
     */
    @RequiresEdt
    fun setEffectId(id: String) {
        if (disposed) return
        val state = AtmosphereSettingsState.getInstance().state
        val dark = isDarkTheme()
        when {
            dark && state.darkEffectId != null -> state.darkEffectId = id
            !dark && state.lightEffectId != null -> state.lightEffectId = id
            else -> state.effectId = id
        }
        swapEffects(id)
        notifyChanged()
    }

    /** Replaces the effect of every registration that does not run [id] yet; keeps the frames' enabled state. */
    private fun swapEffects(id: String) {
        for (registration in registrations.values) {
            if (registration.effect.id == id) continue

            deactivate(registration)
            registration.effect = AtmosphereEffects.createOrDefault(id)
            if (isEnabled) activate(registration)
        }
    }

    override fun dispose() {
        disposed = true
        snoozeJob?.cancel()
        snoozeJob = null
        changeListeners.clear()
        stopIdleProducer()
        stopBackgroundProducer()
        stopTypingProducer()
        for (registration in registrations.values) deactivate(registration)
        registrations.clear()
        updateTicker()
    }

    /**
     * Plays a `SUCCESS` / `FAILURE` reaction on every live frame, e.g. from the settings page. Deliberately ignores
     * `reactToActivity` (the user asked for it explicitly) and never touches the activity counters.
     */
    @RequiresEdt
    fun previewOutcome(event: AtmosphereEvent) {
        if (disposed || (event != AtmosphereEvent.SUCCESS && event != AtmosphereEvent.FAILURE)) return
        for (registration in registrations.values) {
            val atmosphere = registration.atmosphere ?: continue
            if (!atmosphere.paused && !atmosphere.isDormant) atmosphere.publish(event)
        }
    }

    private fun activate(registration: Registration) {
        if (registration.atmosphere != null || registration.parentDisposed) return
        val attachDisposable = Disposer.newDisposable(registration.parent, "IDE Atmosphere frame")
        val state = AtmosphereSettingsState.getInstance().state
        registration.effect.setIntensity(state.intensity)
        registration.effect.setSpeedScale(state.effectiveSpeed())
        val atmosphere = FrameAtmosphere(
            rootPane = registration.rootPane,
            effect = registration.effect,
            context = EffectContext.fromCurrentLaf(),
            maxParticles = FirefliesEffect.MAX_PARTICLES,
        )
        atmosphere.paused = isPaused
        atmosphere.reduceMotion = state.reduceMotion
        atmosphere.pointerMode = state.pointerMode
        // Before attach the opacity snaps: a frame that appears while the IDE is in the background starts dormant.
        atmosphere.setVisible(isApplicationActive || !state.pauseOnFocusLoss)
        atmosphere.attach(attachDisposable)
        registration.atmosphere = atmosphere
        registration.attachDisposable = attachDisposable
        ticker.add(atmosphere)
        updateCaretProducer(registration)
    }

    private fun deactivate(registration: Registration) {
        val atmosphere = registration.atmosphere ?: return
        stopCaretProducer(registration)
        registration.atmosphere = null
        ticker.remove(atmosphere)
        atmosphere.detach()
        registration.attachDisposable?.let { Disposer.dispose(it) }
        registration.attachDisposable = null
    }

    private fun updateTicker() {
        val shouldRun = !disposed && !isPaused && registrations.values.any { registration ->
            val atmosphere = registration.atmosphere
            atmosphere != null && !atmosphere.isDormant
        }
        if (shouldRun) {
            ticker.start()
        } else if (tickerDelegate.isInitialized()) {
            ticker.stop()
        }
    }

    private fun updateIdleProducer() {
        val state = AtmosphereSettingsState.getInstance().state
        val shouldRun = !disposed && isEnabled && registrations.isNotEmpty() && state.reactToActivity
        if (shouldRun) {
            if (idleJob?.isActive != true) {
                idleJob = IdleActivityProducer.forIde(::publish).start(coroutineScope, Dispatchers.UI)
            }
        } else {
            stopIdleProducer()
        }
    }

    private fun stopIdleProducer() {
        idleJob?.cancel()
        idleJob = null
        if (tickerDelegate.isInitialized()) ticker.calm = false
    }

    private fun updateBackgroundProducer() {
        val state = AtmosphereSettingsState.getInstance().state
        val shouldRun = !disposed && isEnabled && registrations.isNotEmpty() && state.reactToActivity
        if (!shouldRun) {
            stopBackgroundProducer()
            return
        }
        if (backgroundJob?.isActive == true) return
        backgroundJob = coroutineScope.launch(Dispatchers.UI) {
            while (true) {
                delay(BackgroundTaskActivityProducer.POLL_INTERVAL)
                // A backgrounded IDE is dormant anyway; skip sampling until it is activated again.
                if (isApplicationActive) backgroundProducer.sample(registrations.keys.toList())
            }
        }
    }

    private fun stopBackgroundProducer() {
        backgroundJob?.cancel()
        backgroundJob = null
        if (!disposed) backgroundProducer.reset()
    }

    private fun updateTypingProducer() {
        val state = AtmosphereSettingsState.getInstance().state
        val shouldRun = !disposed && isEnabled && registrations.isNotEmpty() && state.reactToActivity && state.reactToTyping
        if (shouldRun) {
            if (!typingAttached) {
                typingProducer.attach()
                typingAttached = true
            }
        } else {
            stopTypingProducer()
        }
    }

    private fun stopTypingProducer() {
        typingJob?.cancel()
        typingJob = null
        if (typingAttached) {
            typingAttached = false
            typingProducer.detach() // resets the level, which forwards 0 to the frames
        }
    }

    private fun updateCaretProducers() {
        for (registration in registrations.values) updateCaretProducer(registration)
    }

    /** Follows the caret for [registration] while enabled, attached and the setting is on; stops otherwise. */
    private fun updateCaretProducer(registration: Registration) {
        val atmosphere = registration.atmosphere
        val shouldRun = !disposed && atmosphere != null && AtmosphereSettingsState.getInstance().state.caretAnchor
        if (!shouldRun) {
            stopCaretProducer(registration)
            return
        }
        if (registration.caretProducer != null) return
        val producer = CaretAnchorProducer(registration.project, registration.rootPane, { x, y, active ->
            registration.atmosphere?.setAnchor(x, y, active)
        })
        registration.caretProducer = producer
        Disposer.register(registration.attachDisposable ?: registration.parent, producer)
        producer.start()
    }

    private fun stopCaretProducer(registration: Registration) {
        val producer = registration.caretProducer ?: return
        registration.caretProducer = null
        Disposer.dispose(producer)
    }

    /** Forwards a flow change and keeps polling the decay (every [FLOW_POLL_INTERVAL]) until the level settles. */
    private fun onFlowChanged(level: Float) {
        setFlow(level)
        if (level <= 0f || disposed || typingJob?.isActive == true) return
        typingJob = coroutineScope.launch(Dispatchers.UI) {
            while (!typingProducer.isSettled) {
                delay(FLOW_POLL_INTERVAL)
                typingProducer.sample()
            }
        }
    }

    companion object {
        const val ENABLED_KEY: String = "ide.atmosphere.enabled"

        /** Length of the status bar's "Pause for 1 hour". */
        val SNOOZE_DURATION: Duration = 1.hours

        /** How often the typing flow decay is sampled while the level is above zero. */
        val FLOW_POLL_INTERVAL: Duration = 100.milliseconds

        private val LOG = Logger.getInstance(AtmosphereManager::class.java)

        @JvmStatic
        fun getInstance(): AtmosphereManager = ApplicationManager.getApplication().service()
    }
}
