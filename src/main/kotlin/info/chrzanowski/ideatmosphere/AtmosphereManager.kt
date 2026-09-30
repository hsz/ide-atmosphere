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
import com.intellij.util.concurrency.annotations.RequiresEdt
import info.chrzanowski.ideatmosphere.core.AtmosphereEffect
import info.chrzanowski.ideatmosphere.core.AtmosphereEvent
import info.chrzanowski.ideatmosphere.core.AtmospherePalette
import info.chrzanowski.ideatmosphere.core.EffectContext
import info.chrzanowski.ideatmosphere.effects.AtmosphereEffects
import info.chrzanowski.ideatmosphere.effects.fireflies.FirefliesEffect
import info.chrzanowski.ideatmosphere.events.IdleActivityProducer
import info.chrzanowski.ideatmosphere.frame.FrameAtmosphere
import info.chrzanowski.ideatmosphere.render.AtmosphereTicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import java.util.EnumSet
import javax.swing.JRootPane
import javax.swing.SwingUtilities

/**
 * Application-level owner of everything atmosphere-related: the registry of project frames, the shared
 * [AtmosphereTicker], the persisted enabled flag, pause reasons, event fan-out and the [IdleActivityProducer]
 * (running while the plugin is enabled and at least one frame is registered).
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

    /** Why the animation is currently frozen. Several reasons may be active at once. */
    enum class PauseReason { POWER_SAVE }

    private class Registration(
        val rootPane: JRootPane,
        val effect: AtmosphereEffect,
        val parent: Disposable,
    ) {
        var atmosphere: FrameAtmosphere? = null
        var attachDisposable: Disposable? = null
        var parentDisposed = false
    }

    private val registrations = LinkedHashMap<Project, Registration>()
    private val pauseReasons: EnumSet<PauseReason> = EnumSet.noneOf(PauseReason::class.java)
    private var disposed = false
    private var idleJob: Job? = null

    /** Created lazily so merely loading the service (e.g. from an application listener) allocates no timer. */
    private val tickerDelegate = lazy { AtmosphereTicker().also { it.onAllDormant = ::updateTicker } }
    val ticker: AtmosphereTicker by tickerDelegate

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
        }

    val isPaused: Boolean get() = pauseReasons.isNotEmpty()

    /** Projects currently registered (attached or waiting for re-enable). */
    val projects: Set<Project> get() = registrations.keys

    /** Live [FrameAtmosphere] of [project], or `null` when not attached or disabled. */
    fun atmosphereFor(project: Project): FrameAtmosphere? = registrations[project]?.atmosphere

    /** `true` while the idle/active producer is collecting `IdleTracker` events. */
    val isIdleProducerRunning: Boolean get() = idleJob?.isActive == true

    /** Registers the project's main [frame]; a no-op when the frame has no root pane or the project is known. */
    @RequiresEdt
    fun attach(project: Project, frame: IdeFrame, parent: Disposable) {
        val rootPane = frame.component?.let { SwingUtilities.getRootPane(it) }
        if (rootPane == null) {
            LOG.info("No root pane for ${project.name}; atmosphere not attached")
            return
        }
        attach(project, rootPane, parent)
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
        val registration = Registration(rootPane, effect, parent)
        registrations[project] = registration
        Disposer.register(parent) {
            registration.parentDisposed = true
            detach(project)
        }
        if (isEnabled) activate(registration)
        updateTicker()
        updateIdleProducer()
    }

    /** Stops and forgets the atmosphere of [project]. Safe to call for unknown projects and more than once. */
    @RequiresEdt
    fun detach(project: Project) {
        val registration = registrations.remove(project) ?: return
        deactivate(registration)
        updateTicker()
        updateIdleProducer()
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

    /** Recomputes the palette from the current LaF and pushes it to every attached effect. */
    @RequiresEdt
    fun refreshPalette() {
        if (registrations.isEmpty()) return
        val palette = AtmospherePalette.fromCurrentLaf()
        for (registration in registrations.values) registration.atmosphere?.paletteChanged(palette)
    }

    /** Freezes (or resumes) the animation for [reason]; the ticker stops while any reason is active. */
    @RequiresEdt
    fun setPaused(reason: PauseReason, paused: Boolean) {
        val changed = if (paused) pauseReasons.add(reason) else pauseReasons.remove(reason)
        if (!changed) return
        val frozen = isPaused
        for (registration in registrations.values) registration.atmosphere?.paused = frozen
        updateTicker()
    }

    /**
     * Fades every frame out when the application is deactivated and back in on activation. The ticker runs on
     * while frames are fading and stops once all of them are dormant (see [AtmosphereTicker.onAllDormant]).
     */
    @RequiresEdt
    fun setApplicationActive(active: Boolean) {
        if (disposed || isApplicationActive == active) return
        isApplicationActive = active
        for (registration in registrations.values) registration.atmosphere?.setVisible(active)
        updateTicker()
    }

    override fun dispose() {
        disposed = true
        stopIdleProducer()
        for (registration in registrations.values) deactivate(registration)
        registrations.clear()
        updateTicker()
    }

    private fun activate(registration: Registration) {
        if (registration.atmosphere != null || registration.parentDisposed) return
        val attachDisposable = Disposer.newDisposable(registration.parent, "IDE Atmosphere frame")
        val atmosphere = FrameAtmosphere(
            rootPane = registration.rootPane,
            effect = registration.effect,
            context = EffectContext.fromCurrentLaf(),
            maxParticles = FirefliesEffect.MAX_PARTICLES,
        )
        atmosphere.paused = isPaused
        // Before attach the opacity snaps: a frame that appears while the IDE is in the background starts dormant.
        atmosphere.setVisible(isApplicationActive)
        atmosphere.attach(attachDisposable)
        registration.atmosphere = atmosphere
        registration.attachDisposable = attachDisposable
        ticker.add(atmosphere)
    }

    private fun deactivate(registration: Registration) {
        val atmosphere = registration.atmosphere ?: return
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
        val shouldRun = !disposed && isEnabled && registrations.isNotEmpty()
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

    companion object {
        const val ENABLED_KEY: String = "ide.atmosphere.enabled"

        private val LOG = Logger.getInstance(AtmosphereManager::class.java)

        @JvmStatic
        fun getInstance(): AtmosphereManager = ApplicationManager.getApplication().service()
    }
}
