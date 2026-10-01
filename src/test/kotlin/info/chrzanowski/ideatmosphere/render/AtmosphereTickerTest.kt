package info.chrzanowski.ideatmosphere.render

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.impl.IdeGlassPaneImpl
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import info.chrzanowski.ideatmosphere.RecordingEffect
import info.chrzanowski.ideatmosphere.core.EffectContext
import info.chrzanowski.ideatmosphere.frame.FrameAtmosphere
import java.awt.Rectangle
import javax.swing.JRootPane

class AtmosphereTickerTest : BasePlatformTestCase() {

    private class AlwaysPaintable : FrameAtmosphere.RepaintTarget {
        var paints = 0
        override var isPaintable: Boolean = true
        override fun contentBounds(out: Rectangle): Boolean {
            out.setBounds(0, 0, 800, 600)
            return true
        }

        override fun paintContentNow(x: Int, y: Int, width: Int, height: Int) {
            paints++
        }

        override fun repaintRoot(x: Int, y: Int, width: Int, height: Int) {
            paints++
        }
    }

    private var nowNanos = 1_000_000_000L
    private lateinit var parent: Disposable
    private lateinit var effect: RecordingEffect
    private lateinit var frame: FrameAtmosphere
    private lateinit var target: AlwaysPaintable

    override fun setUp() {
        super.setUp()
        parent = Disposer.newDisposable("AtmosphereTickerTest")
        val rootPane = JRootPane().apply {
            glassPane = IdeGlassPaneImpl(this)
            setSize(800, 600)
        }
        effect = RecordingEffect().apply { regions.add(floatArrayOf(10f, 10f, 10f, 10f)) }
        target = AlwaysPaintable()
        frame = FrameAtmosphere(rootPane, effect, EffectContext.forTesting(), maxParticles = 4, target = target)
        frame.attach(parent)
    }

    override fun tearDown() {
        try {
            Disposer.dispose(parent)
            UIUtil.dispatchAllInvocationEvents()
        } finally {
            super.tearDown()
        }
    }

    private fun newTicker(governor: FrameBudgetGovernor = FrameBudgetGovernor()) =
        AtmosphereTicker(governor, AtmosphereStats(enabled = false), clock = { nowNanos })

    fun `test first tick uses the timer interval and later ticks use the real clamped delta`() {
        val ticker = newTicker()
        ticker.add(frame)

        ticker.tick()
        nowNanos += 50_000_000L // 50 ms
        ticker.tick()
        nowNanos += 5_000_000_000L // 5 s pause → clamped to 0.1 s
        ticker.tick()

        assertEquals(3, effect.updateCount)
        assertEquals(3, target.paints)
        assertEquals(33 / 1000f, effect.deltas[0], 1e-6f)
        assertEquals(0.05f, effect.deltas[1], 1e-6f)
        assertEquals(AtmosphereTicker.MAX_DELTA_SECONDS, effect.deltas[2], 1e-6f)
    }

    fun `test removed frames are no longer ticked and add is idempotent`() {
        val ticker = newTicker()
        ticker.add(frame)
        ticker.add(frame)
        assertEquals(1, ticker.frameCount)
        ticker.tick()
        ticker.remove(frame)
        ticker.tick()
        assertEquals(1, effect.updateCount)
        assertEquals(0, ticker.frameCount)
    }

    /** Clock that makes every tick appear to cost [SLOW_TICK_NANOS]: it is read once before and once after the frames. */
    private fun slowTickClock() = object : () -> Long {
        var calls = 0
        override fun invoke(): Long {
            calls++
            return if (calls % 2 == 0) nowNanos + SLOW_TICK_NANOS else nowNanos
        }
    }

    fun `test tick cost feeds the governor and the timer interval`() {
        val governor = FrameBudgetGovernor()
        val slow = AtmosphereTicker(governor, AtmosphereStats(enabled = false), clock = slowTickClock())
        slow.add(frame)
        repeat(60) {
            nowNanos += 33_000_000L
            slow.tick()
        }
        assertEquals(20, governor.fps)
        assertEquals(50, slow.intervalMillis)
        assertEquals(60, effect.updateCount)
    }

    fun `test calm mode caps the governed rate at 15 fps but never raises it`() {
        val ticker = newTicker()
        assertEquals(33, ticker.intervalMillis)
        ticker.calm = true
        assertEquals(66, ticker.intervalMillis)
        ticker.calm = false
        assertEquals(33, ticker.intervalMillis)

        // A governor already slower than the calm cap is left alone.
        val slow = newTicker(FrameBudgetGovernor(minFps = 4, initialFps = 4))
        slow.calm = true
        assertEquals(250, slow.intervalMillis)
    }

    fun `test reduce motion caps the governed rate at 10 fps but never raises it`() {
        val ticker = newTicker()
        ticker.reduceMotion = true
        assertEquals(100, ticker.intervalMillis)
        ticker.calm = true
        assertEquals(100, ticker.intervalMillis)
        ticker.calm = false
        ticker.reduceMotion = false
        assertEquals(33, ticker.intervalMillis)

        // A governor already slower than the reduced-motion cap is left alone.
        val slow = newTicker(FrameBudgetGovernor(minFps = 4, initialFps = 4))
        slow.reduceMotion = true
        assertEquals(250, slow.intervalMillis)
    }

    fun `test onAllDormant fires once when every frame has faded out and re-arms after activation`() {
        val ticker = newTicker()
        ticker.add(frame)
        var dormantCalls = 0
        ticker.onAllDormant = { dormantCalls++ }

        ticker.tick()
        assertEquals(0, dormantCalls)

        frame.setVisible(false)
        var ticks = 0
        while (!frame.isDormant && ticks < 20) {
            nowNanos += 100_000_000L
            ticker.tick()
            ticks++
        }
        assertTrue(frame.isDormant)
        assertTrue("fade took $ticks ticks", ticks in 5..6)
        assertEquals(1, dormantCalls)

        // Further dormant ticks do not repeat the notification.
        nowNanos += 100_000_000L
        ticker.tick()
        nowNanos += 100_000_000L
        ticker.tick()
        assertEquals(1, dormantCalls)

        // Fading back in re-arms it: the next complete fade-out notifies again.
        frame.setVisible(true)
        while (frame.opacity < 1f) {
            nowNanos += 100_000_000L
            ticker.tick()
        }
        assertEquals(1, dormantCalls)
        frame.setVisible(false)
        while (!frame.isDormant) {
            nowNanos += 100_000_000L
            ticker.tick()
        }
        assertEquals(2, dormantCalls)
    }

    fun `test onAllDormant needs every frame to be dormant and never fires without frames`() {
        val ticker = newTicker()
        var dormantCalls = 0
        ticker.onAllDormant = { dormantCalls++ }
        ticker.tick()
        assertEquals(0, dormantCalls)

        val secondTarget = AlwaysPaintable()
        val secondEffect = RecordingEffect()
        val second = FrameAtmosphere(frame.rootPane, secondEffect, EffectContext.forTesting(), maxParticles = 4, target = secondTarget)
        second.attach(parent)
        ticker.add(frame)
        ticker.add(second)

        // Only the first frame goes dormant (hidden: it snaps instead of fading).
        target.isPaintable = false
        frame.setVisible(false)
        ticker.tick()
        assertTrue(frame.isDormant)
        assertFalse(second.isDormant)
        assertEquals(0, dormantCalls)

        secondTarget.isPaintable = false
        second.setVisible(false)
        ticker.tick()
        assertTrue(second.isDormant)
        assertEquals(1, dormantCalls)
    }

    fun `test fade ticks are excluded from the governor`() {
        val governor = FrameBudgetGovernor()
        val ticker = AtmosphereTicker(governor, AtmosphereStats(enabled = false), clock = slowTickClock())
        ticker.add(frame)
        frame.setVisible(false)

        nowNanos += 33_000_000L
        ticker.tick()
        assertTrue(frame.lastTickWasFade)
        assertEquals(0f, governor.averageTickMicros)

        // Bring the frame back to full opacity; the last fade step is still a fade tick.
        frame.setVisible(true)
        while (frame.opacity < 1f) {
            nowNanos += 100_000_000L
            ticker.tick()
            assertTrue(frame.lastTickWasFade)
        }
        assertEquals(0f, governor.averageTickMicros)

        // The first ordinary tick is recorded.
        nowNanos += 33_000_000L
        ticker.tick()
        assertFalse(frame.lastTickWasFade)
        assertEquals(6_000f, governor.averageTickMicros)
    }

    fun `test start and stop control the swing timer`() {
        val ticker = newTicker()
        assertFalse(ticker.isRunning)
        ticker.start()
        assertTrue(ticker.isRunning)
        ticker.start()
        assertTrue(ticker.isRunning)
        ticker.stop()
        assertFalse(ticker.isRunning)
    }

    private companion object {
        /** 6 ms per tick: over the 70 ms/s budget at 30 fps, so the governor must fall back to its 20 fps floor. */
        const val SLOW_TICK_NANOS = 6_000_000L
    }
}
