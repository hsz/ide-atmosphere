package info.chrzanowski.ideatmosphere.effects.common

import info.chrzanowski.ideatmosphere.core.AtmosphereEffect
import info.chrzanowski.ideatmosphere.core.AtmosphereEvent
import info.chrzanowski.ideatmosphere.core.EffectContext
import info.chrzanowski.ideatmosphere.effects.AtmosphereEffects
import info.chrzanowski.ideatmosphere.effects.autumn.AutumnLeavesEffect
import info.chrzanowski.ideatmosphere.effects.bubbles.BubblesEffect
import info.chrzanowski.ideatmosphere.effects.dust.DustEffect
import info.chrzanowski.ideatmosphere.render.DirtyRegionCollector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Rectangle
import java.awt.image.BufferedImage

/** Behaviour specific to the autumn, bubbles and dust atmospheres; shared contracts live in `EffectSettingsTest`. */
class DriftingEffectsTest {

    private val bounds = Rectangle(0, 0, 800, 600)
    private val dt = 1f / 24f

    private fun started(effect: DriftingParticleEffect): DriftingParticleEffect =
        effect.also { it.start(EffectContext.forTesting(seed = 7L)) }

    @Test
    fun `new atmospheres are registered and selectable`() {
        for (id in listOf(AutumnLeavesEffect.ID, BubblesEffect.ID, DustEffect.ID)) {
            assertTrue(id, AtmosphereEffects.isSelectable(id))
            assertEquals(id, AtmosphereEffects.create(id)!!.id)
        }
    }

    @Test
    fun `leaves fall, bubbles rise and dust hangs almost still`() {
        val leaves = centroidShiftY(AutumnLeavesEffect())
        assertTrue("leaves must fall, moved $leaves px", leaves > 20.0)
        val bubbles = centroidShiftY(BubblesEffect())
        assertTrue("bubbles must rise, moved $bubbles px", bubbles < -20.0)
        val dust = centroidShiftY(DustEffect())
        // Dust only loops lazily in place: no steady stream like the falling leaves or the rising bubbles.
        assertTrue("dust must hang almost still, moved $dust px", kotlin.math.abs(dust) < minOf(leaves, -bubbles) / 2)
    }

    @Test
    fun `a paused debugger slows the field below idle and resuming restores the busy speed`() {
        for (effect in listOf(AutumnLeavesEffect(), BubblesEffect(), DustEffect()).map(::started)) {
            effect.onEvent(AtmosphereEvent.USER_IDLE)
            effect.update(6f, bounds, DirtyRegionCollector(100))
            val idle = effect.speedMultiplier

            effect.onEvent(AtmosphereEvent.EXECUTION_PAUSED)
            effect.update(6f, bounds, DirtyRegionCollector(100))
            assertEquals(effect.id, idle * AtmosphereEffect.PAUSED_SPEED_FACTOR, effect.speedMultiplier, 0.001f)

            effect.onEvent(AtmosphereEvent.ACTIVITY_STARTED)
            effect.update(6f, bounds, DirtyRegionCollector(100))
            assertTrue(effect.id, effect.speedMultiplier > 1f)
        }
    }

    @Test
    fun `particles that leave the window are reborn and population stays stable`() {
        for (effect in listOf(AutumnLeavesEffect(), BubblesEffect(), DustEffect()).map(::started)) {
            effect.update(dt, bounds, DirtyRegionCollector(100))
            val population = effect.particleCount
            repeat(24 * 60) { effect.update(dt, bounds, DirtyRegionCollector(100)) }
            assertEquals(effect.id, population, effect.particleCount)
            assertTrue("${effect.id} must still paint something after a minute", alphaSum(paint(effect)) > 0)
        }
    }

    @Test
    fun `stop clears the field and paints nothing`() {
        for (effect in listOf(AutumnLeavesEffect(), BubblesEffect(), DustEffect()).map(::started)) {
            repeat(48) { effect.update(dt, bounds, DirtyRegionCollector(100)) }
            effect.stop()
            assertEquals(effect.id, 0, effect.particleCount)
            assertEquals(effect.id, 0L, alphaSum(paint(effect)))
        }
    }

    /**
     * Vertical displacement (px) over 3 s of a cluster started at the window centre. Only a window around the centre
     * is measured, so the few particles reborn elsewhere at the end of their life do not skew the result.
     */
    private fun centroidShiftY(effect: DriftingParticleEffect): Double {
        started(effect)
        repeat(48) { effect.update(dt, bounds, DirtyRegionCollector(100)) }
        for (i in 0 until effect.particleCount) effect.placeParticleForTest(i, 400f, 300f, 0.8f)
        val before = centroidY(paint(effect), CLUSTER)
        repeat(72) { effect.update(dt, bounds, DirtyRegionCollector(100)) }
        return centroidY(paint(effect), CLUSTER) - before
    }

    private fun paint(effect: AtmosphereEffect): BufferedImage {
        val image = BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.clip = bounds
            effect.paint(g, bounds)
        } finally {
            g.dispose()
        }
        return image
    }

    private fun centroidY(image: BufferedImage, area: Rectangle): Double {
        var weighted = 0.0
        var alpha = 0L
        for (y in area.y until area.y + area.height) for (x in area.x until area.x + area.width) {
            val a = image.getRGB(x, y) ushr 24
            weighted += y * a.toDouble()
            alpha += a
        }
        return weighted / alpha
    }

    private fun alphaSum(image: BufferedImage): Long {
        var sum = 0L
        for (y in 0 until image.height) for (x in 0 until image.width) sum += image.getRGB(x, y) ushr 24
        return sum
    }

    private companion object {
        val CLUSTER = Rectangle(300, 150, 200, 300)
    }
}
