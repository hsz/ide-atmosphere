package info.chrzanowski.ideatmosphere.render

import info.chrzanowski.ideatmosphere.RecordingEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.swing.JPanel

class AtmospherePainterTest {

    private val component = JPanel().apply { setSize(100, 80) }

    private fun newImage() = BufferedImage(100, 80, BufferedImage.TYPE_INT_ARGB)

    @Test
    fun `inactive painter does not paint and does not need repaint`() {
        val effect = RecordingEffect()
        val painter = AtmospherePainter(effect)
        assertFalse(painter.needsRepaint())
        val g = newImage().createGraphics()
        painter.paint(component, g)
        assertEquals(0, effect.paintCount)
    }

    @Test
    fun `active painter paints only inside the clip and restores hints`() {
        val effect = RecordingEffect()
        val painter = AtmospherePainter(effect).apply { active = true }
        assertTrue(painter.needsRepaint())

        val image = newImage()
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
        g.setClip(10, 10, 20, 20)
        painter.paint(component, g)

        assertEquals(1, effect.paintCount)
        assertEquals(Rectangle(10, 10, 20, 20), effect.paintedClips.single())
        assertEquals(RenderingHints.VALUE_ANTIALIAS_OFF, g.getRenderingHint(RenderingHints.KEY_ANTIALIASING))
        assertEquals(Color.RED.rgb, image.getRGB(15, 15))
        assertEquals(0, image.getRGB(5, 5))
        assertEquals(0, image.getRGB(35, 35))
    }

    @Test
    fun `no clip means the whole component`() {
        val effect = RecordingEffect()
        val painter = AtmospherePainter(effect).apply { active = true }
        painter.paint(component, newImage().createGraphics())
        assertEquals(Rectangle(0, 0, 100, 80), effect.paintedClips.single())
    }

    @Test
    fun `re-entrant paint from the effect runs once at depth 2 and never deeper`() {
        lateinit var painter: AtmospherePainter
        val depths = ArrayList<Int>()
        val effect = object : RecordingEffect() {
            override fun paint(g: Graphics2D, clip: Rectangle) {
                super.paint(g, clip)
                depths.add(painter.depth)
                painter.paint(component, g)
            }
        }
        painter = AtmospherePainter(effect).apply { active = true }
        painter.paint(component, newImage().createGraphics())
        assertEquals(listOf(1, 2), depths)
        assertEquals(2, effect.paintCount)
        assertEquals(0, painter.depth)
    }

    @Test
    fun `nested hook fires exactly once at depth 1 after the effect was painted with hints restored`() {
        val effect = RecordingEffect()
        val painter = AtmospherePainter(effect).apply { active = true }
        val g = newImage().createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
        var hookRuns = 0
        var paintCountWhenHookRan = -1
        var depthWhenHookRan = -1
        var hintWhenHookRan: Any? = null
        painter.nestedPaint = Runnable {
            hookRuns++
            paintCountWhenHookRan = effect.paintCount
            depthWhenHookRan = painter.depth
            hintWhenHookRan = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING)
            // Re-entrant paints from the hook paint the effect (depth 2) but do not fire the hook again.
            g.setClip(0, 0, 10, 10)
            painter.paint(component, g)
            g.setClip(20, 20, 10, 10)
            painter.paint(component, g)
        }

        painter.paint(component, g)

        assertEquals(1, hookRuns)
        assertEquals(1, paintCountWhenHookRan)
        assertEquals(1, depthWhenHookRan)
        assertEquals(RenderingHints.VALUE_ANTIALIAS_OFF, hintWhenHookRan)
        assertEquals(3, effect.paintCount)
        assertEquals(listOf(Rectangle(0, 0, 100, 80), Rectangle(0, 0, 10, 10), Rectangle(20, 20, 10, 10)), effect.paintedClips)
        assertNull(painter.nestedPaint)
        assertEquals(0, painter.depth)

        // The hook is one-shot: a second outer paint does not run it again.
        painter.paint(component, g)
        assertEquals(1, hookRuns)
    }

    @Test
    fun `hook is not fired from depth 2 and re-entry beyond depth 2 is ignored`() {
        lateinit var painter: AtmospherePainter
        var hookRuns = 0
        val effect = object : RecordingEffect() {
            override fun paint(g: Graphics2D, clip: Rectangle) {
                super.paint(g, clip)
                if (painter.depth == 2) {
                    // Arm a hook while nested: it must stay armed (never consumed at depth 2) …
                    painter.nestedPaint = Runnable { hookRuns++ }
                    // … and a third level of re-entry is ignored.
                    painter.paint(component, g)
                }
            }
        }
        painter = AtmospherePainter(effect).apply { active = true }
        val g = newImage().createGraphics()
        painter.nestedPaint = Runnable { painter.paint(component, g) }

        painter.paint(component, g)

        assertEquals(2, effect.paintCount)
        assertEquals(0, hookRuns)
        assertNotNull(painter.nestedPaint)
    }

    @Test
    fun `inactive painter does not consume the hook`() {
        val effect = RecordingEffect()
        val painter = AtmospherePainter(effect)
        var hookRuns = 0
        painter.nestedPaint = Runnable { hookRuns++ }
        painter.paint(component, newImage().createGraphics())
        assertEquals(0, hookRuns)
        assertNotNull(painter.nestedPaint)
    }

    @Test
    fun `opacity one half halves the alpha of painted pixels and the composite is restored`() {
        val effect = RecordingEffect()
        val painter = AtmospherePainter(effect).apply {
            active = true
            opacity = 0.5f
        }
        val image = newImage()
        val g = image.createGraphics()
        val originalComposite = g.composite

        painter.paint(component, g)

        assertEquals(1, effect.paintCount)
        val argb = image.getRGB(50, 40)
        val alpha = argb ushr 24
        val red = (argb shr 16) and 0xFF
        assertTrue("alpha $alpha should be about half of 255", alpha in 126..129)
        assertTrue("red $red should stay saturated", red >= 250)
        assertSame(originalComposite, g.composite)
    }

    @Test
    fun `opacity zero paints nothing but still consumes the hook`() {
        val effect = RecordingEffect()
        val painter = AtmospherePainter(effect).apply {
            active = true
            opacity = 0f
        }
        var hookRuns = 0
        painter.nestedPaint = Runnable { hookRuns++ }
        val image = newImage()
        val g = image.createGraphics()
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.75f)

        painter.paint(component, g)

        assertEquals(0, effect.paintCount)
        assertEquals(0, painter.paintCount)
        assertEquals(0, image.getRGB(50, 40))
        assertEquals(1, hookRuns)
        assertNull(painter.nestedPaint)
        assertEquals(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.75f), g.composite)
    }

    @Test
    fun `full opacity paints with the caller's composite untouched`() {
        val effect = RecordingEffect()
        val painter = AtmospherePainter(effect).apply { active = true }
        val image = newImage()
        val g = image.createGraphics()

        painter.paint(component, g)

        assertEquals(Color.RED.rgb, image.getRGB(50, 40))
        assertEquals(255, image.getRGB(50, 40) ushr 24)
    }

    @Test
    fun `opacity is clamped to the unit range`() {
        val painter = AtmospherePainter(RecordingEffect())
        painter.opacity = 1.5f
        assertEquals(1f, painter.opacity)
        painter.opacity = -0.25f
        assertEquals(0f, painter.opacity)
    }
}
