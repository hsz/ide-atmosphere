package info.chrzanowski.ideatmosphere.media

import info.chrzanowski.ideatmosphere.core.AtmospherePalette
import info.chrzanowski.ideatmosphere.core.EffectContext
import info.chrzanowski.ideatmosphere.core.PointerMode
import info.chrzanowski.ideatmosphere.effects.AtmosphereEffects
import info.chrzanowski.ideatmosphere.render.DirtyRegionCollector
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode

/**
 * Renders the screenshots and animated previews used by the README and the Marketplace listing: one still per
 * atmosphere for the dark and the light theme (`<id>-dark.png`, `<id>-light.png`) and one looping GIF on the dark
 * theme (`<id>.gif`), each over a stylised editor so the particles are seen in context.
 *
 * Not part of the regular test run: it only renders when `ide.atmosphere.media.dir` is set, e.g.
 * `./gradlew test --tests '*MarketplaceMediaGenerator' -Pide.atmosphere.media.dir=docs/media`.
 * The effects run headless with a fixed seed, so the output is reproducible.
 */
class MarketplaceMediaGenerator {

    @Test
    fun `render marketplace media`() {
        val target = System.getProperty(MEDIA_DIR_PROPERTY)
        assumeTrue("set -P$MEDIA_DIR_PROPERTY=<dir> to render media", !target.isNullOrBlank())
        val dir = File(target!!).apply { mkdirs() }
        for (id in AtmosphereEffects.selectableIds) {
            ImageIO.write(still(id, AtmospherePalette.DARK, DARK_THEME), "png", File(dir, "$id-dark.png"))
            ImageIO.write(still(id, AtmospherePalette.LIGHT, LIGHT_THEME), "png", File(dir, "$id-light.png"))
            writeGif(frames(id, AtmospherePalette.DARK, DARK_THEME), File(dir, "$id.gif"))
        }
    }

    private class Theme(val background: Color, val gutter: Color, val text: List<Color>)

    private fun still(id: String, palette: AtmospherePalette, theme: Theme): BufferedImage =
        frames(id, palette, theme, count = 1).single()

    /** Warms the field up for [WARM_UP_SECONDS] (so particles have faded in), then captures [count] frames. */
    private fun frames(id: String, palette: AtmospherePalette, theme: Theme, count: Int = GIF_FRAMES): List<BufferedImage> {
        val effect = AtmosphereEffects.createOrDefault(id)
        effect.start(EffectContext.forTesting(palette = palette, seed = SEED))
        effect.setPointerMode(PointerMode.OFF)
        val bounds = Rectangle(0, 0, WIDTH, HEIGHT)
        val dirty = DirtyRegionCollector(100)
        val dt = 1f / FPS
        repeat((WARM_UP_SECONDS * FPS).toInt()) { dirty.clear(); effect.update(dt, bounds, dirty) }
        return List(count) {
            dirty.clear()
            effect.update(dt, bounds, dirty)
            val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            try {
                paintEditor(g, theme)
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
                g.clip = bounds
                effect.paint(g, bounds)
            } finally {
                g.dispose()
            }
            image
        }.also { effect.stop() }
    }

    /** A stylised editor: gutter, and "code" as rounded bars of varying length and indentation. */
    private fun paintEditor(g: Graphics2D, theme: Theme) {
        g.color = theme.background
        g.fillRect(0, 0, WIDTH, HEIGHT)
        g.color = theme.gutter
        g.fillRect(0, 0, GUTTER, HEIGHT)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        var y = 24
        var line = 0
        while (y < HEIGHT - 16) {
            val indent = INDENTS[line % INDENTS.size]
            val length = LENGTHS[(line * 7) % LENGTHS.size]
            if (length > 0) {
                var x = GUTTER + 24 + indent * 24
                var token = 0
                var remaining = length
                while (remaining > 0) {
                    val w = minOf(remaining, 30 + (line * 13 + token * 29) % 70)
                    g.color = theme.text[(line + token) % theme.text.size]
                    g.fillRoundRect(x, y, w, 8, 6, 6)
                    x += w + 8
                    remaining -= w + 8
                    token++
                }
            }
            y += 22
            line++
        }
    }

    private fun writeGif(frames: List<BufferedImage>, file: File) {
        val writer = ImageIO.getImageWritersBySuffix("gif").next()
        val type = ImageTypeSpecifier.createFromRenderedImage(frames.first())
        ImageIO.createImageOutputStream(file).use { output ->
            writer.output = output
            writer.prepareWriteSequence(null)
            for (frame in frames) {
                val metadata = writer.getDefaultImageMetadata(type, null)
                val format = metadata.nativeMetadataFormatName
                val root = metadata.getAsTree(format) as IIOMetadataNode
                root.child("GraphicControlExtension").apply {
                    setAttribute("disposalMethod", "none")
                    setAttribute("userInputFlag", "FALSE")
                    setAttribute("transparentColorFlag", "FALSE")
                    setAttribute("delayTime", (100 / FPS).toString())
                    setAttribute("transparentColorIndex", "0")
                }
                val app = IIOMetadataNode("ApplicationExtension").apply {
                    setAttribute("applicationID", "NETSCAPE")
                    setAttribute("authenticationCode", "2.0")
                    userObject = byteArrayOf(1, 0, 0) // loop forever
                }
                root.child("ApplicationExtensions").appendChild(app)
                metadata.setFromTree(format, root)
                writer.writeToSequence(IIOImage(frame, null, metadata), null)
            }
            writer.endWriteSequence()
        }
        writer.dispose()
    }

    private fun IIOMetadataNode.child(name: String): IIOMetadataNode {
        for (i in 0 until length) {
            val node = item(i)
            if (node.nodeName == name) return node as IIOMetadataNode
        }
        return IIOMetadataNode(name).also { appendChild(it) }
    }

    private companion object {
        const val MEDIA_DIR_PROPERTY = "ide.atmosphere.media.dir"
        const val WIDTH = 720
        const val HEIGHT = 400
        const val GUTTER = 44
        const val FPS = 20
        const val GIF_FRAMES = 80
        const val WARM_UP_SECONDS = 4f
        const val SEED = 2026L

        val INDENTS = intArrayOf(0, 1, 2, 2, 3, 2, 1, 1, 2, 0, 0, 1, 2, 3, 3, 2, 1, 0)
        val LENGTHS = intArrayOf(220, 340, 0, 180, 420, 260, 120, 300, 0, 380, 200, 160, 280)

        val DARK_THEME = Theme(
            background = Color(0x1E1F22),
            gutter = Color(0x2B2D30),
            text = listOf(Color(0x5F826B), Color(0xCF8E6D), Color(0x56A8F5), Color(0x6F737A), Color(0xBCBEC4)),
        )
        val LIGHT_THEME = Theme(
            background = Color(0xFFFFFF),
            gutter = Color(0xF7F8FA),
            text = listOf(Color(0xC2D6C8), Color(0xD9C2E8), Color(0xBCD0F5), Color(0xD6D8DC), Color(0xA8ABB0)),
        )
    }
}
