package info.chrzanowski.ideatmosphere.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import info.chrzanowski.ideatmosphere.effects.AtmosphereEffects

class AtmosphereSettingsStateTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        AtmosphereSettingsState.getInstance().loadState(AtmosphereSettingsState.State())
    }

    fun `test defaults`() {
        val settings = AtmosphereSettingsState.getInstance()
        val state = settings.state

        assertEquals("fireflies", AtmosphereEffects.DEFAULT_ID)
        assertEquals(AtmosphereEffects.DEFAULT_ID, state.effectId)
        assertEquals(1f, state.intensity)
        assertEquals(1f, state.speed)
        assertTrue(state.reactToActivity)
        assertTrue(state.pauseOnFocusLoss)
    }

    fun `test persistence round-trip`() {
        val settings = AtmosphereSettingsState.getInstance()
        val originalState = AtmosphereSettingsState.State(
            effectId = "non-existent",
            intensity = 0.5f,
            speed = 1.5f,
            reactToActivity = false,
            pauseOnFocusLoss = false
        )

        settings.loadState(originalState)
        val savedState = settings.state

        assertEquals(originalState, savedState)
        
        // Manual round-trip through getState/loadState
        val roundTripState = AtmosphereSettingsState.State()
        settings.loadState(settings.state) // this is a bit redundant in memory, but tests the mechanism
        assertEquals(savedState, settings.state)
    }
}
