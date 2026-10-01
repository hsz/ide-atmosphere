package info.chrzanowski.ideatmosphere.settings

import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import info.chrzanowski.ideatmosphere.effects.AtmosphereEffects

class AtmosphereSettingsStateTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        AtmosphereSettingsState.getInstance().loadState(AtmosphereSettingsState.State())
    }

    fun `test defaults`() {
        val state = AtmosphereSettingsState().state

        assertEquals(AtmosphereEffects.DEFAULT_ID, state.effectId)
        assertEquals(1f, state.intensity)
        assertEquals(1f, state.speed)
        assertTrue(state.reactToActivity)
        assertTrue(state.pauseOnFocusLoss)
        assertTrue(state.avoidMouse)
        assertEquals(AtmosphereSettingsState.State(), state)
        assertEquals(state, state.normalized())
    }

    fun `test scale range spans 30 to 200 percent`() {
        assertEquals(0.3f, AtmosphereSettingsState.SCALE_RANGE.start)
        assertEquals(2f, AtmosphereSettingsState.SCALE_RANGE.endInclusive)
    }

    fun `test XML persistence round-trip keeps all six fields`() {
        val original = AtmosphereSettingsState.State(
            effectId = nonDefaultEffect(),
            intensity = 0.5f,
            speed = 1.5f,
            reactToActivity = false,
            pauseOnFocusLoss = false,
            avoidMouse = false,
        )

        val restored = roundTrip(original)
        val settings = AtmosphereSettingsState.getInstance()
        settings.loadState(restored)

        assertEquals(original, restored)
        assertEquals(original, settings.state)
        assertNotSame(restored, settings.state)
    }

    fun `test serialized XML consists of the six plain options only`() {
        val state = AtmosphereSettingsState.State(
            effectId = nonDefaultEffect(),
            intensity = 0.5f,
            speed = 1.5f,
            reactToActivity = false,
            pauseOnFocusLoss = false,
            avoidMouse = false,
        )

        val xml = XmlSerializer.serialize(state)
        val options = xml.getChildren("option")

        assertEquals("State", xml.name)
        assertEquals(options.size, xml.children.size)
        assertEquals(
            setOf("effectId", "intensity", "speed", "reactToActivity", "pauseOnFocusLoss", "avoidMouse"),
            options.map { it.getAttributeValue("name") }.toSet(),
        )
        assertEquals(6, options.size)
        assertTrue(options.all { it.getAttributeValue("value") != null && it.children.isEmpty() })
    }

    fun `test boolean flags round-trip independently of each other`() {
        for (avoidMouse in listOf(false, true)) {
            for (reactToActivity in listOf(false, true)) {
                for (pauseOnFocusLoss in listOf(false, true)) {
                    val original = AtmosphereSettingsState.State(
                        reactToActivity = reactToActivity,
                        pauseOnFocusLoss = pauseOnFocusLoss,
                        avoidMouse = avoidMouse,
                    )
                    val settings = AtmosphereSettingsState()
                    settings.loadState(roundTrip(original))

                    assertEquals(original, settings.state)
                }
            }
        }
    }

    fun `test out of range intensity and speed are clamped`() {
        val settings = AtmosphereSettingsState()

        settings.loadState(AtmosphereSettingsState.State(intensity = -1f, speed = 20f))
        assertEquals(0.3f, settings.state.intensity)
        assertEquals(2f, settings.state.speed)

        settings.loadState(AtmosphereSettingsState.State(intensity = 20f, speed = 0f))
        assertEquals(2f, settings.state.intensity)
        assertEquals(0.3f, settings.state.speed)
    }

    fun `test non-finite intensity and speed fall back to 1`() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val settings = AtmosphereSettingsState()
            settings.loadState(AtmosphereSettingsState.State(intensity = value, speed = value))

            assertEquals(1f, settings.state.intensity)
            assertEquals(1f, settings.state.speed)
        }
    }

    fun `test in-range values survive normalization unchanged`() {
        for (state in listOf(
            AtmosphereSettingsState.State(intensity = 0.3f, speed = 2f),
            AtmosphereSettingsState.State(intensity = 2f, speed = 0.3f),
            AtmosphereSettingsState.State(intensity = 0.556f, speed = 1.234f),
        )) {
            assertEquals(state, state.normalized())
            val settings = AtmosphereSettingsState()
            settings.loadState(state)
            assertEquals(state, settings.state)
        }
    }

    fun `test unknown effect falls back to the default`() {
        for (effectId in listOf("", "unknown", AtmosphereEffects.DEFAULT_ID.uppercase())) {
            assertEquals(AtmosphereEffects.DEFAULT_ID, AtmosphereSettingsState.State(effectId = effectId).normalized().effectId)

            val settings = AtmosphereSettingsState()
            settings.loadState(AtmosphereSettingsState.State(effectId = effectId))
            assertEquals(AtmosphereEffects.DEFAULT_ID, settings.state.effectId)
        }
    }

    fun `test every registered effect is kept as is`() {
        for (effectId in AtmosphereEffects.ids) {
            val settings = AtmosphereSettingsState()
            settings.loadState(roundTrip(AtmosphereSettingsState.State(effectId = effectId)))

            assertEquals(effectId, settings.state.effectId)
        }
    }

    fun `test legacy XML with reaction profiles loads and ignores them`() {
        val effectId = nonDefaultEffect()
        val legacy = JDOMUtil.load(
            """
            <State>
              <option name="effectId" value="$effectId" />
              <option name="intensity" value="0.8" />
              <option name="speed" value="1.4" />
              <option name="reactToActivity" value="false" />
              <option name="pauseOnFocusLoss" value="false" />
              <option name="avoidMouse" value="false" />
              <option name="activityProfile">
                <AtmosphereReactionProfile>
                  <option name="movement">
                    <EnvelopeSettings>
                      <option name="attackSeconds" value="1.25" />
                      <option name="decaySeconds" value="2.5" />
                      <option name="sustainRatio" value="0.35" />
                      <option name="releaseSeconds" value="3.75" />
                    </EnvelopeSettings>
                  </option>
                  <option name="successPulse">
                    <EnvelopeSettings>
                      <option name="attackSeconds" value="0.25" />
                    </EnvelopeSettings>
                  </option>
                </AtmosphereReactionProfile>
              </option>
              <option name="firefliesProfile">
                <AtmosphereReactionProfile>
                  <option name="movement">
                    <EnvelopeSettings>
                      <option name="attackSeconds" value="2" />
                    </EnvelopeSettings>
                  </option>
                </AtmosphereReactionProfile>
              </option>
              <option name="sakuraProfile">
                <AtmosphereReactionProfile />
              </option>
            </State>
            """.trimIndent(),
        )
        val expected = AtmosphereSettingsState.State(
            effectId = effectId,
            intensity = 0.8f,
            speed = 1.4f,
            reactToActivity = false,
            pauseOnFocusLoss = false,
            avoidMouse = false,
        )

        val restored = XmlSerializer.deserialize(legacy, AtmosphereSettingsState.State::class.java)
        val settings = AtmosphereSettingsState.getInstance()
        settings.loadState(restored)

        assertEquals(expected, restored)
        assertEquals(expected, settings.state)
        val persistedOptions = XmlSerializer.serialize(settings.state).getChildren("option").map { it.getAttributeValue("name") }
        assertFalse(persistedOptions.any { it.endsWith("Profile") })
    }

    fun `test legacy XML without optional flags keeps their defaults`() {
        val legacy = JDOMUtil.load(
            """
            <State>
              <option name="effectId" value="${AtmosphereEffects.DEFAULT_ID}" />
              <option name="intensity" value="0.8" />
            </State>
            """.trimIndent(),
        )

        val restored = XmlSerializer.deserialize(legacy, AtmosphereSettingsState.State::class.java)

        assertEquals(AtmosphereSettingsState.State(intensity = 0.8f), restored)
        assertTrue(restored.reactToActivity)
        assertTrue(restored.pauseOnFocusLoss)
        assertTrue(restored.avoidMouse)
    }

    fun `test load state normalizes and detaches`() {
        val original = AtmosphereSettingsState.State(effectId = "unknown", intensity = 5f, speed = Float.NaN)
        val expected = AtmosphereSettingsState.State(intensity = 2f, speed = 1f)
        val settings = AtmosphereSettingsState.getInstance()

        settings.loadState(original)

        assertNotSame(original, settings.state)
        assertEquals(expected, settings.state)
        original.effectId = nonDefaultEffect()
        original.intensity = 0.5f
        original.speed = 0.5f
        original.reactToActivity = false
        original.pauseOnFocusLoss = false
        original.avoidMouse = false
        assertEquals(expected, settings.state)
    }

    fun `test load state of an already normalized state still detaches`() {
        val original = AtmosphereSettingsState.State(effectId = nonDefaultEffect(), intensity = 0.5f)
        val settings = AtmosphereSettingsState()

        settings.loadState(original)

        assertNotSame(original, settings.state)
        assertEquals(original, settings.state)
        original.intensity = 2f
        assertEquals(0.5f, settings.state.intensity)
    }

    fun `test deep copy is an equal detached instance`() {
        val original = AtmosphereSettingsState.State(
            effectId = nonDefaultEffect(),
            intensity = 0.5f,
            speed = 1.5f,
            reactToActivity = false,
            pauseOnFocusLoss = false,
            avoidMouse = false,
        )
        val snapshot = original.copy()

        val copy = original.deepCopy()

        assertNotSame(original, copy)
        assertEquals(original, copy)
        copy.effectId = AtmosphereEffects.DEFAULT_ID
        copy.intensity = 2f
        copy.speed = 0.3f
        copy.reactToActivity = true
        copy.pauseOnFocusLoss = true
        copy.avoidMouse = true
        assertEquals(snapshot, original)
    }

    fun `test draft copies do not mutate live shared settings`() {
        val settings = AtmosphereSettingsState.getInstance()
        val original = settings.state.deepCopy()

        val draft = settings.state.deepCopy()
        draft.effectId = nonDefaultEffect()
        draft.intensity = 2f
        draft.speed = 0.3f
        draft.reactToActivity = false
        draft.pauseOnFocusLoss = false
        draft.avoidMouse = false

        assertEquals(original, settings.state)
        assertNotSame(draft, settings.state)
    }

    private fun nonDefaultEffect(): String = AtmosphereEffects.ids.first { it != AtmosphereEffects.DEFAULT_ID }

    private fun roundTrip(state: AtmosphereSettingsState.State): AtmosphereSettingsState.State =
        XmlSerializer.deserialize(
            JDOMUtil.load(JDOMUtil.writeElement(XmlSerializer.serialize(state))),
            AtmosphereSettingsState.State::class.java,
        )
}
