package info.chrzanowski.ideatmosphere.render

import com.intellij.openapi.diagnostic.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class AtmosphereStatsTest {
    private var nowNanos = 1L

    private fun stats(enabled: Boolean = true) = AtmosphereStats(
        enabled = enabled,
        periodNanos = TimeUnit.SECONDS.toNanos(1),
        logger = Logger.getInstance(AtmosphereStatsTest::class.java),
        clock = { nowNanos },
    )

    @Test
    fun `report contains absolute rects per second`() {
        val stats = stats()
        repeat(10) {
            stats.recordTick(costMicros = 100, rects = 20, paintedPixels = 1000, governedFps = 30)
            nowNanos += TimeUnit.MILLISECONDS.toNanos(100)
        }
        stats.recordTick(costMicros = 100, rects = 20, paintedPixels = 1000, governedFps = 30)
        assertEquals(1, stats.reports)
        // 11 ticks x 20 rects over 1.0 s
        assertEquals(220.0, stats.lastRectsPerSecond, 0.5)
        val line = stats.lastReport
        assertNotNull(line)
        assertTrue(line!!, line.contains("rects/s=220") && line.contains("rects/tick=20.0"))
    }

    @Test
    fun `disabled stats record nothing`() {
        val stats = stats(enabled = false)
        repeat(50) {
            stats.recordTick(costMicros = 100, rects = 20, paintedPixels = 1000, governedFps = 30)
            nowNanos += TimeUnit.MILLISECONDS.toNanos(100)
        }
        assertEquals(0, stats.reports)
        assertNull(stats.lastReport)
    }
}
