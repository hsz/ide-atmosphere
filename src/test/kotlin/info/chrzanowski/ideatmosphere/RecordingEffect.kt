package info.chrzanowski.ideatmosphere

import info.chrzanowski.ideatmosphere.core.AtmosphereEffect
import info.chrzanowski.ideatmosphere.core.AtmosphereEvent
import info.chrzanowski.ideatmosphere.core.AtmospherePalette
import info.chrzanowski.ideatmosphere.core.EffectContext
import info.chrzanowski.ideatmosphere.render.DirtyRegionCollector
import java.awt.Color
import java.awt.Graphics2D
import java.awt.Rectangle

/**
 * Test double: records every call and reports the regions configured in [regions] on each update.
 * [paint] fills the clip with an opaque color so pixel-level assertions are possible.
 */
open class RecordingEffect(override val id: String = "recording") : AtmosphereEffect {

    var startCount = 0
    var stopCount = 0
    var updateCount = 0
    var paintCount = 0
    val deltas = ArrayList<Float>()
    val paintedClips = ArrayList<Rectangle>()
    val events = ArrayList<AtmosphereEvent>()
    val palettes = ArrayList<AtmospherePalette>()
    var context: EffectContext? = null
    var lastIntensity = 1f
    var lastSpeedScale = 1f

    /** Regions (x, y, w, h) reported on each update; empty list → update returns `false`. */
    val regions = ArrayList<FloatArray>()

    override fun start(context: EffectContext) {
        startCount++
        this.context = context
    }

    override fun stop() {
        stopCount++
    }

    override fun update(deltaSeconds: Float, bounds: Rectangle, dirty: DirtyRegionCollector): Boolean {
        updateCount++
        deltas.add(deltaSeconds)
        for (r in regions) dirty.add(r[0], r[1], r[2], r[3])
        return regions.isNotEmpty()
    }

    override fun paint(g: Graphics2D, clip: Rectangle) {
        paintCount++
        paintedClips.add(Rectangle(clip))
        g.color = Color.RED
        g.fillRect(clip.x, clip.y, clip.width, clip.height)
    }

    override fun onEvent(event: AtmosphereEvent) {
        events.add(event)
    }

    override fun onPaletteChanged(palette: AtmospherePalette) {
        palettes.add(palette)
    }

    override fun setIntensity(scale: Float) {
        lastIntensity = scale
    }

    override fun setSpeedScale(scale: Float) {
        lastSpeedScale = scale
    }
}
