package info.chrzanowski.ideatmosphere.frame

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.impl.IdeGlassPaneImpl
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import info.chrzanowski.ideatmosphere.RecordingEffect
import info.chrzanowski.ideatmosphere.core.AtmosphereEvent
import info.chrzanowski.ideatmosphere.core.AtmospherePalette
import info.chrzanowski.ideatmosphere.core.EffectContext
import info.chrzanowski.ideatmosphere.render.AtmospherePainter
import java.awt.Color
import java.awt.Rectangle
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JRootPane

class FrameAtmosphereTest : BasePlatformTestCase() {

    private lateinit var rootPane: JRootPane
    private lateinit var glassPane: IdeGlassPaneImpl
    private lateinit var effect: RecordingEffect
    private lateinit var parent: Disposable

    /**
     * Fake repaint channels: records every rectangle and lets the test control paintability/content bounds.
     * When [painter] is set, [paintContentNow] simulates the platform by painting the glass-pane painter into
     * [image] with the rectangle as clip — this is what lets the nested hook fire in tests.
     */
    private class FakeTarget : FrameAtmosphere.RepaintTarget {
        override var isPaintable: Boolean = true
        var content: Rectangle? = Rectangle(0, 40, 6000, 4000)
        val contentPaints = ArrayList<Rectangle>()
        val rootRepaints = ArrayList<Rectangle>()

        var painter: AtmospherePainter? = null
        var component: JComponent = JPanel().apply { setSize(6000, 4000) }
        val image = BufferedImage(6000, 4000, BufferedImage.TYPE_INT_ARGB)
        /** Depth of the painter observed while each content paint was issued (0 = direct, 1 = from the hook). */
        val depthsAtPaint = ArrayList<Int>()
        /** Invoked on every content paint before painting; lets a test detach mid-batch. */
        var onPaint: (() -> Unit)? = null

        override fun contentBounds(out: Rectangle): Boolean {
            val c = content ?: return false
            out.setBounds(c)
            return true
        }

        override fun paintContentNow(x: Int, y: Int, width: Int, height: Int) {
            contentPaints.add(Rectangle(x, y, width, height))
            val p = painter
            depthsAtPaint.add(p?.depth ?: 0)
            onPaint?.invoke()
            if (p != null) {
                val g = image.createGraphics()
                try {
                    g.setClip(x, y, width, height)
                    p.paint(component, g)
                } finally {
                    g.dispose()
                }
            }
        }

        override fun repaintRoot(x: Int, y: Int, width: Int, height: Int) {
            rootRepaints.add(Rectangle(x, y, width, height))
        }
    }

    override fun setUp() {
        super.setUp()
        rootPane = JRootPane()
        glassPane = IdeGlassPaneImpl(rootPane)
        rootPane.glassPane = glassPane
        rootPane.setSize(6000, 4040)
        rootPane.doLayout()
        effect = RecordingEffect()
        parent = Disposer.newDisposable("FrameAtmosphereTest")
    }

    override fun tearDown() {
        try {
            Disposer.dispose(parent)
            UIUtil.dispatchAllInvocationEvents()
        } finally {
            super.tearDown()
        }
    }

    private fun newAtmosphere(target: FrameAtmosphere.RepaintTarget = FrameAtmosphere.SwingRepaintTarget(rootPane)) =
        FrameAtmosphere(rootPane, effect, EffectContext.forTesting(), maxParticles = 8, target = target)

    fun `test attach registers the painter and shows the glass pane`() {
        assertFalse(glassPane.isVisible)
        val atmosphere = newAtmosphere()
        atmosphere.attach(parent)

        assertTrue(atmosphere.isAttached)
        assertTrue(atmosphere.painter.active)
        assertTrue(atmosphere.painter.needsRepaint())
        assertTrue(glassPane.isVisible)
        assertEquals(1, effect.startCount)
        assertNotNull(effect.context)
    }

    fun `test detach deactivates immediately and hides the glass pane after pending events`() {
        val atmosphere = newAtmosphere()
        atmosphere.attach(parent)
        atmosphere.detach()

        assertFalse(atmosphere.isAttached)
        assertFalse(atmosphere.painter.active)
        assertFalse(atmosphere.painter.needsRepaint())
        assertEquals(1, effect.stopCount)

        UIUtil.dispatchAllInvocationEvents()
        assertFalse(glassPane.isVisible)

        // idempotent
        atmosphere.detach()
        assertEquals(1, effect.stopCount)
    }

    fun `test disposing the parent detaches`() {
        val atmosphere = newAtmosphere()
        atmosphere.attach(parent)
        Disposer.dispose(parent)
        UIUtil.dispatchAllInvocationEvents()

        assertFalse(atmosphere.isAttached)
        assertFalse(atmosphere.painter.active)
        assertFalse(glassPane.isVisible)
        assertEquals(1, effect.stopCount)
    }

    fun `test tick on a non-showing root pane performs no update and no paint`() {
        val atmosphere = newAtmosphere()
        atmosphere.attach(parent)
        effect.regions.add(floatArrayOf(10f, 10f, 20f, 20f))

        assertFalse(rootPane.isShowing)
        atmosphere.tick(0.04f)

        assertEquals(0, effect.updateCount)
        assertEquals(0, effect.paintCount)
        assertEquals(0, atmosphere.lastRectCount)
    }

    fun `test tick before attach and while paused does nothing`() {
        val target = FakeTarget()
        val atmosphere = newAtmosphere(target)
        effect.regions.add(floatArrayOf(10f, 100f, 20f, 20f))

        atmosphere.tick(0.04f)
        assertEquals(0, effect.updateCount)

        atmosphere.attach(parent)
        atmosphere.paused = true
        atmosphere.tick(0.04f)
        assertEquals(0, effect.updateCount)

        atmosphere.paused = false
        atmosphere.tick(0.04f)
        assertEquals(1, effect.updateCount)
        assertEquals(listOf(0.04f), effect.deltas)
    }

    fun `test regions inside the content pane are painted synchronously in root-pane coordinates`() {
        val target = FakeTarget()
        val atmosphere = newAtmosphere(target)
        atmosphere.attach(parent)
        effect.regions.add(floatArrayOf(100f, 100f, 20f, 20f))

        atmosphere.tick(0.04f)

        assertEquals(listOf(Rectangle(100, 100, 20, 20)), target.contentPaints)
        assertTrue(target.rootRepaints.isEmpty())
        assertEquals(1, atmosphere.lastRectCount)
        assertEquals(400L, atmosphere.lastPaintedPixels)
    }

    fun `test regions crossing the header band are split between the two channels`() {
        val target = FakeTarget()
        val atmosphere = newAtmosphere(target)
        atmosphere.attach(parent)
        effect.regions.add(floatArrayOf(100f, 30f, 20f, 20f))

        atmosphere.tick(0.04f)

        assertEquals(listOf(Rectangle(100, 40, 20, 10)), target.contentPaints)
        assertEquals(listOf(Rectangle(100, 30, 20, 10)), target.rootRepaints)
        assertEquals(2, atmosphere.lastRectCount)
    }

    fun `test regions fully in the header band use the asynchronous channel only`() {
        val target = FakeTarget()
        val atmosphere = newAtmosphere(target)
        atmosphere.attach(parent)
        effect.regions.add(floatArrayOf(100f, 5f, 20f, 20f))

        atmosphere.tick(0.04f)

        assertTrue(target.contentPaints.isEmpty())
        assertEquals(listOf(Rectangle(100, 5, 20, 20)), target.rootRepaints)
    }

    fun `test regions are clamped to the root pane and nothing is painted when the effect reports no change`() {
        val target = FakeTarget()
        val atmosphere = newAtmosphere(target)
        atmosphere.attach(parent)

        atmosphere.tick(0.04f)
        assertEquals(1, effect.updateCount)
        assertEquals(0, atmosphere.lastRectCount)

        effect.regions.add(floatArrayOf(5990f, 4030f, 50f, 50f))
        atmosphere.tick(0.04f)
        assertEquals(listOf(Rectangle(5990, 4030, 10, 10)), target.contentPaints)
    }

    fun `test not paintable target skips the tick`() {
        val target = FakeTarget().apply { isPaintable = false }
        val atmosphere = newAtmosphere(target)
        atmosphere.attach(parent)
        effect.regions.add(floatArrayOf(100f, 100f, 20f, 20f))

        atmosphere.tick(0.04f)
        assertEquals(0, effect.updateCount)
    }

    /**
     * Five 20×20 content regions offset on both axes so that every pairwise union exceeds ≈100 k px and the cost
     * model keeps them as five separate rectangles.
     */
    private val fiveSpreadRegions = listOf(
        Rectangle(1000, 1000, 20, 20),
        Rectangle(2000, 2000, 20, 20),
        Rectangle(3000, 3000, 20, 20),
        Rectangle(4000, 4000, 20, 20),
        Rectangle(5000, 1000, 20, 20),
    )

    private fun addFiveSpreadRegions() {
        for (r in fiveSpreadRegions) {
            effect.regions.add(floatArrayOf(r.x.toFloat(), r.y.toFloat(), r.width.toFloat(), r.height.toFloat()))
        }
    }

    fun `test content rects are painted as one nested batch when the platform invokes the painter`() {
        val target = FakeTarget()
        val atmosphere = newAtmosphere(target)
        target.painter = atmosphere.painter
        atmosphere.attach(parent)
        addFiveSpreadRegions()

        atmosphere.tick(0.04f)

        assertEquals(5, target.contentPaints.size)
        assertEquals(5, atmosphere.lastRectCount)
        assertEquals(1, atmosphere.lastDirectRects)
        assertEquals(4, atmosphere.lastNestedRects)
        // The head is issued at depth 0, the other four from inside the hook while the head is being painted.
        assertEquals(listOf(0, 1, 1, 1, 1), target.depthsAtPaint)
        // Every rectangle reached the effect exactly once with its own clip.
        assertEquals(5, effect.paintCount)
        assertEquals(target.contentPaints, effect.paintedClips)
        assertNull(atmosphere.painter.nestedPaint)
        assertEquals(0, atmosphere.painter.depth)
        for (rect in target.contentPaints) {
            assertEquals(Color.RED.rgb, target.image.getRGB(rect.x + 5, rect.y + 5))
        }
        assertTrue(target.rootRepaints.isEmpty())

        // The next tick arms and consumes the hook again.
        atmosphere.tick(0.04f)
        assertEquals(1, atmosphere.lastDirectRects)
        assertEquals(4, atmosphere.lastNestedRects)
        assertEquals(10, effect.paintCount)
    }

    fun `test all rects are painted directly when the hook never fires`() {
        val target = FakeTarget() // never invokes the painter, like a hidden or obscured frame
        val atmosphere = newAtmosphere(target)
        atmosphere.attach(parent)
        addFiveSpreadRegions()

        atmosphere.tick(0.04f)

        assertEquals(5, target.contentPaints.size)
        assertEquals(5, atmosphere.lastDirectRects)
        assertEquals(0, atmosphere.lastNestedRects)
        assertEquals(5, atmosphere.lastRectCount)
        assertEquals(0, effect.paintCount)
        assertNull(atmosphere.painter.nestedPaint)
        assertEquals(fiveSpreadRegions.toSet(), target.contentPaints.toSet())
    }

    fun `test a single content rect does not arm the hook`() {
        val target = FakeTarget()
        val atmosphere = newAtmosphere(target)
        target.painter = atmosphere.painter
        atmosphere.attach(parent)
        effect.regions.add(floatArrayOf(100f, 100f, 20f, 20f))

        atmosphere.tick(0.04f)

        assertEquals(1, atmosphere.lastDirectRects)
        assertEquals(0, atmosphere.lastNestedRects)
        assertEquals(1, effect.paintCount)
    }

    fun `test detaching in the middle of a batch is safe`() {
        val target = FakeTarget()
        val atmosphere = newAtmosphere(target)
        target.painter = atmosphere.painter
        atmosphere.attach(parent)
        addFiveSpreadRegions()
        var detachAfter = 2
        target.onPaint = { if (--detachAfter == 0) atmosphere.detach() }

        atmosphere.tick(0.04f)

        assertFalse(atmosphere.isAttached)
        assertFalse(atmosphere.painter.active)
        assertNull(atmosphere.painter.nestedPaint)
        assertEquals(0, atmosphere.painter.depth)
        // Every queued rectangle was still repainted (restoring the background), but the effect was painted only
        // for the head; after `stop()` it was never touched again.
        assertEquals(5, target.contentPaints.size)
        assertEquals(1, effect.paintCount)
        assertEquals(1, effect.stopCount)

        // Subsequent ticks are no-ops.
        atmosphere.tick(0.04f)
        assertEquals(5, target.contentPaints.size)
        assertEquals(1, effect.updateCount)
    }

    private val fullContent = Rectangle(0, 40, 6000, 4000)
    private val headerBand = Rectangle(0, 0, 6000, 40)

    fun `test fade-out paints the whole overlay per tick, then the frame is dormant and paints nothing`() {
        val target = FakeTarget()
        val atmosphere = newAtmosphere(target)
        target.painter = atmosphere.painter
        atmosphere.attach(parent)
        effect.regions.add(floatArrayOf(100f, 100f, 20f, 20f))
        assertTrue(atmosphere.isVisible)
        assertEquals(1f, atmosphere.opacity)

        atmosphere.setVisible(false)
        assertFalse(atmosphere.isVisible)
        assertFalse(atmosphere.isDormant)
        // Still fully opaque until the first tick eases it.
        assertEquals(1f, atmosphere.opacity)

        // dt = 0.25 s → two steps of 0.5 to reach transparency.
        atmosphere.tick(0.25f)
        assertTrue(atmosphere.lastTickWasFade)
        assertEquals(0.5f, atmosphere.opacity)
        assertEquals(0.5f, atmosphere.painter.opacity)
        assertEquals(1, effect.updateCount)
        assertEquals(listOf(fullContent), target.contentPaints)
        assertEquals(listOf(headerBand), target.rootRepaints)
        assertEquals(2, atmosphere.lastRectCount)
        assertEquals(1, atmosphere.lastDirectRects)
        assertEquals(0, atmosphere.lastNestedRects)
        assertFalse(atmosphere.isDormant)
        // The effect was composited at half opacity.
        val alpha = target.image.getRGB(100, 100) ushr 24
        assertTrue("alpha $alpha should be about half of 255", alpha in 126..129)

        atmosphere.tick(0.25f)
        assertTrue(atmosphere.lastTickWasFade)
        assertEquals(0f, atmosphere.opacity)
        assertEquals(2, effect.updateCount)
        assertEquals(listOf(fullContent, fullContent), target.contentPaints)
        assertEquals(listOf(headerBand, headerBand), target.rootRepaints)
        assertTrue(atmosphere.isDormant)
        // Fully transparent: the last whole-content paint restored the background without touching the effect.
        assertEquals(1, effect.paintCount)
        assertEquals(1L, atmosphere.painter.paintCount)

        // Dormant: nothing is simulated or painted any more.
        atmosphere.tick(0.25f)
        atmosphere.tick(0.25f)
        assertFalse(atmosphere.lastTickWasFade)
        assertEquals(2, effect.updateCount)
        assertEquals(2, target.contentPaints.size)
        assertEquals(2, target.rootRepaints.size)
        assertEquals(0, atmosphere.lastRectCount)
    }

    fun `test fade-in restores dirty-region ticks once fully opaque`() {
        val target = FakeTarget()
        val atmosphere = newAtmosphere(target)
        atmosphere.attach(parent)
        effect.regions.add(floatArrayOf(100f, 100f, 20f, 20f))
        atmosphere.setVisible(false)
        atmosphere.tick(0.25f)
        atmosphere.tick(0.25f)
        assertTrue(atmosphere.isDormant)
        target.contentPaints.clear()
        target.rootRepaints.clear()

        atmosphere.setVisible(true)
        assertTrue(atmosphere.isVisible)
        assertFalse(atmosphere.isDormant)
        assertEquals(0f, atmosphere.opacity)

        atmosphere.tick(0.25f)
        assertTrue(atmosphere.lastTickWasFade)
        assertEquals(0.5f, atmosphere.opacity)
        assertEquals(listOf(fullContent), target.contentPaints)
        assertEquals(listOf(headerBand), target.rootRepaints)

        atmosphere.tick(0.25f)
        assertTrue(atmosphere.lastTickWasFade)
        assertEquals(1f, atmosphere.opacity)
        assertEquals(1f, atmosphere.painter.opacity)
        assertEquals(2, target.contentPaints.size)

        // Back to normal: only the dirty rectangle is painted.
        atmosphere.tick(0.04f)
        assertFalse(atmosphere.lastTickWasFade)
        assertEquals(Rectangle(100, 100, 20, 20), target.contentPaints.last())
        assertEquals(2, target.rootRepaints.size)
        assertEquals(1, atmosphere.lastRectCount)
    }

    fun `test a 30 fps fade completes within about half a second`() {
        val target = FakeTarget()
        val atmosphere = newAtmosphere(target)
        atmosphere.attach(parent)
        atmosphere.setVisible(false)

        var ticks = 0
        while (!atmosphere.isDormant && ticks < 40) {
            atmosphere.tick(1 / 30f)
            ticks++
        }

        assertTrue(atmosphere.isDormant)
        assertTrue("fade took $ticks ticks", ticks in 15..17)
        assertEquals(ticks, target.contentPaints.size)
        assertTrue(target.contentPaints.all { it == fullContent })
    }

    fun `test paused wins over fading and the fade resumes when un-paused`() {
        val target = FakeTarget()
        val atmosphere = newAtmosphere(target)
        atmosphere.attach(parent)
        atmosphere.setVisible(false)
        atmosphere.paused = true

        atmosphere.tick(0.25f)
        assertFalse(atmosphere.lastTickWasFade)
        assertEquals(1f, atmosphere.opacity)
        assertFalse(atmosphere.isDormant)
        assertEquals(0, effect.updateCount)
        assertTrue(target.contentPaints.isEmpty())
        assertTrue(target.rootRepaints.isEmpty())

        atmosphere.paused = false
        atmosphere.tick(0.25f)
        assertTrue(atmosphere.lastTickWasFade)
        assertEquals(0.5f, atmosphere.opacity)
        assertEquals(listOf(fullContent), target.contentPaints)
    }

    fun `test a hidden frame snaps its opacity instead of fading and becomes dormant without paints`() {
        val target = FakeTarget().apply { isPaintable = false }
        val atmosphere = newAtmosphere(target)
        atmosphere.attach(parent)
        effect.regions.add(floatArrayOf(100f, 100f, 20f, 20f))
        atmosphere.setVisible(false)

        atmosphere.tick(0.04f)

        assertTrue(atmosphere.isDormant)
        assertEquals(0f, atmosphere.opacity)
        assertEquals(0f, atmosphere.painter.opacity)
        assertFalse(atmosphere.lastTickWasFade)
        assertEquals(0, effect.updateCount)
        assertTrue(target.contentPaints.isEmpty())
        assertTrue(target.rootRepaints.isEmpty())

        // Made visible while still hidden: snaps back to opaque, then paints normally once paintable.
        atmosphere.setVisible(true)
        atmosphere.tick(0.04f)
        assertEquals(1f, atmosphere.opacity)
        assertEquals(0, effect.updateCount)

        target.isPaintable = true
        atmosphere.tick(0.04f)
        assertFalse(atmosphere.lastTickWasFade)
        assertEquals(1, effect.updateCount)
        assertEquals(listOf(Rectangle(100, 100, 20, 20)), target.contentPaints)
    }

    fun `test a frame made invisible before attach starts dormant and fades in when shown`() {
        val target = FakeTarget()
        val atmosphere = newAtmosphere(target)
        atmosphere.setVisible(false)
        assertEquals(0f, atmosphere.opacity)
        assertTrue(atmosphere.isDormant)

        atmosphere.attach(parent)
        assertTrue(atmosphere.isDormant)
        assertEquals(0f, atmosphere.painter.opacity)
        atmosphere.tick(0.04f)
        assertEquals(0, effect.updateCount)
        assertTrue(target.contentPaints.isEmpty())

        atmosphere.setVisible(true)
        assertFalse(atmosphere.isDormant)
        // Attached now, so the opacity eases instead of snapping.
        assertEquals(0f, atmosphere.opacity)
        atmosphere.tick(0.25f)
        assertTrue(atmosphere.lastTickWasFade)
        assertEquals(0.5f, atmosphere.opacity)
        assertEquals(1, effect.updateCount)
    }

    fun `test detach resets the fade bookkeeping`() {
        val target = FakeTarget()
        val atmosphere = newAtmosphere(target)
        atmosphere.attach(parent)
        atmosphere.setVisible(false)
        atmosphere.tick(0.25f)
        assertTrue(atmosphere.lastTickWasFade)

        atmosphere.detach()
        assertFalse(atmosphere.lastTickWasFade)
        assertEquals(0, atmosphere.lastRectCount)
    }

    fun `test events and palette changes reach the effect only while attached`() {
        val atmosphere = newAtmosphere()
        atmosphere.publish(AtmosphereEvent.SUCCESS)
        atmosphere.paletteChanged(AtmospherePalette.LIGHT)
        assertTrue(effect.events.isEmpty())
        assertTrue(effect.palettes.isEmpty())

        atmosphere.attach(parent)
        atmosphere.publish(AtmosphereEvent.USER_IDLE)
        atmosphere.paletteChanged(AtmospherePalette.LIGHT)
        assertEquals(listOf(AtmosphereEvent.USER_IDLE), effect.events)
        assertEquals(listOf(AtmospherePalette.LIGHT), effect.palettes)
    }
}
