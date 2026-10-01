package info.chrzanowski.ideatmosphere.settings

import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import info.chrzanowski.ideatmosphere.core.PointerMode
import info.chrzanowski.ideatmosphere.effects.AtmosphereEffects

class AtmosphereSettingsStateTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        AtmosphereSettingsState.getInstance().loadState(AtmosphereSettingsState.State())
    }

    override fun tearDown() {
        try {
            // The application-level settings are shared with other test classes; leave the defaults behind.
            AtmosphereSettingsState.getInstance().loadState(AtmosphereSettingsState.State())
        } finally {
            super.tearDown()
        }
    }

    fun `test defaults`() {
        val state = AtmosphereSettingsState().state

        assertEquals(AtmosphereEffects.DEFAULT_ID, state.effectId)
        assertEquals(1f, state.intensity)
        assertEquals(1f, state.speed)
        assertTrue(state.reactToActivity)
        assertTrue(state.pauseOnFocusLoss)
        assertEquals(PointerMode.AVOID, state.pointerMode)
        assertTrue(state.reactToTyping)
        assertFalse(state.reduceMotion)
        assertNull(state.darkEffectId)
        assertNull(state.lightEffectId)
        assertEquals(AtmosphereSettingsState.State(), state)
        assertEquals(state, state.normalized())
    }

    fun `test reduce motion and per-theme presets round-trip through XML`() {
        val original = AtmosphereSettingsState.State(
            reduceMotion = true,
            darkEffectId = "starfield",
            lightEffectId = "rain",
        )

        val restored = roundTrip(original)
        val settings = AtmosphereSettingsState()
        settings.loadState(restored)

        assertEquals(original, restored)
        assertEquals(original, settings.state)
        val options = XmlSerializer.serialize(original).getChildren("option").associate {
            it.getAttributeValue("name") to it.getAttributeValue("value")
        }
        assertEquals("true", options["reduceMotion"])
        assertEquals("starfield", options["darkEffectId"])
        assertEquals("rain", options["lightEffectId"])
        // Plain attributes only, no nested elements.
        assertTrue(XmlSerializer.serialize(original).getChildren("option").all { it.children.isEmpty() })
    }

    fun `test unknown per-theme ids fall back to no preset`() {
        val settings = AtmosphereSettingsState()

        settings.loadState(AtmosphereSettingsState.State(darkEffectId = "unknown", lightEffectId = ""))
        assertNull(settings.state.darkEffectId)
        assertNull(settings.state.lightEffectId)

        settings.loadState(AtmosphereSettingsState.State(darkEffectId = "snow", lightEffectId = "SNOW"))
        assertEquals("snow", settings.state.darkEffectId)
        assertNull(settings.state.lightEffectId)
    }

    fun `test every registered effect is accepted as a per-theme preset`() {
        for (effectId in AtmosphereEffects.ids) {
            val settings = AtmosphereSettingsState()
            settings.loadState(roundTrip(AtmosphereSettingsState.State(darkEffectId = effectId, lightEffectId = effectId)))

            assertEquals(effectId, settings.state.darkEffectId)
            assertEquals(effectId, settings.state.lightEffectId)
        }
    }

    fun `test effectIdFor prefers the per-theme preset`() {
        val plain = AtmosphereSettingsState.State(effectId = "sakura")
        assertEquals("sakura", plain.effectIdFor(isDark = true))
        assertEquals("sakura", plain.effectIdFor(isDark = false))

        val both = AtmosphereSettingsState.State(effectId = "sakura", darkEffectId = "starfield", lightEffectId = "rain")
        assertEquals("starfield", both.effectIdFor(isDark = true))
        assertEquals("rain", both.effectIdFor(isDark = false))

        val darkOnly = AtmosphereSettingsState.State(effectId = "sakura", darkEffectId = "starfield")
        assertEquals("starfield", darkOnly.effectIdFor(isDark = true))
        assertEquals("sakura", darkOnly.effectIdFor(isDark = false))
    }

    fun `test effective speed is capped only while reduce motion is on`() {
        assertEquals(1.5f, AtmosphereSettingsState.State(speed = 1.5f).effectiveSpeed())
        assertEquals(AtmosphereSettingsState.REDUCED_MOTION_SPEED, AtmosphereSettingsState.State(speed = 1.5f, reduceMotion = true).effectiveSpeed())
        // Never raised above what the user already chose.
        assertEquals(0.3f, AtmosphereSettingsState.State(speed = 0.3f, reduceMotion = true).effectiveSpeed())
        assertEquals(AtmosphereSettingsState.SCALE_RANGE.start, AtmosphereSettingsState.REDUCED_MOTION_SPEED)
    }

    fun `test legacy XML without the new options loads with reduce motion off and no presets`() {
        val legacy = JDOMUtil.load(
            """
            <State>
              <option name="effectId" value="snow" />
              <option name="avoidMouse" value="false" />
            </State>
            """.trimIndent(),
        )

        val restored = XmlSerializer.deserialize(legacy, AtmosphereSettingsState.State::class.java)

        assertEquals(AtmosphereSettingsState.State(effectId = "snow", pointerMode = PointerMode.OFF), restored)
        assertEquals(restored, restored.normalized())
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
            pointerMode = PointerMode.OFF,
        )

        val restored = roundTrip(original)
        val settings = AtmosphereSettingsState.getInstance()
        settings.loadState(restored)

        assertEquals(original, restored)
        assertEquals(original, settings.state)
        assertNotSame(restored, settings.state)
    }

    fun `test serialized XML consists of the plain options plus the legacy avoidMouse mirror only`() {
        val state = AtmosphereSettingsState.State(
            effectId = nonDefaultEffect(),
            intensity = 0.5f,
            speed = 1.5f,
            reactToActivity = false,
            pauseOnFocusLoss = false,
            reactToTyping = false,
            pointerMode = PointerMode.OFF,
            reduceMotion = true,
            darkEffectId = "starfield",
            lightEffectId = "rain",
        )

        val xml = XmlSerializer.serialize(state)
        val options = xml.getChildren("option")

        assertEquals("State", xml.name)
        assertEquals(options.size, xml.children.size)
        assertEquals(
            setOf(
                "effectId", "intensity", "speed", "reactToActivity", "pauseOnFocusLoss", "reactToTyping", "pointerMode",
                "avoidMouse", "reduceMotion", "darkEffectId", "lightEffectId",
            ),
            options.map { it.getAttributeValue("name") }.toSet(),
        )
        assertEquals(11, options.size)
        assertTrue(options.all { it.getAttributeValue("value") != null && it.children.isEmpty() })
    }

    fun `test legacy avoidMouse migrates to the pointer mode`() {
        for ((legacy, expected) in listOf("true" to PointerMode.AVOID, "false" to PointerMode.OFF)) {
            val restored = XmlSerializer.deserialize(
                JDOMUtil.load("""<State><option name="avoidMouse" value="$legacy" /></State>"""),
                AtmosphereSettingsState.State::class.java,
            )

            assertEquals(legacy, expected, restored.pointerMode)
            assertEquals(AtmosphereSettingsState.State(pointerMode = expected), restored)
            val settings = AtmosphereSettingsState()
            settings.loadState(restored)
            assertEquals(expected, settings.state.pointerMode)
        }
    }

    fun `test pointer mode wins over the legacy mirror whatever the option order`() {
        for (mode in PointerMode.entries) {
            val mirror = if (mode == PointerMode.OFF) "false" else "true"
            for (xml in listOf(
                """<State><option name="pointerMode" value="$mode" /><option name="avoidMouse" value="$mirror" /></State>""",
                """<State><option name="avoidMouse" value="$mirror" /><option name="pointerMode" value="$mode" /></State>""",
                """<State><option name="pointerMode" value="$mode" /></State>""",
            )) {
                val restored = XmlSerializer.deserialize(JDOMUtil.load(xml), AtmosphereSettingsState.State::class.java)
                assertEquals(xml, mode, restored.pointerMode)
            }
        }
    }

    fun `test the avoidMouse mirror follows the pointer mode`() {
        assertTrue(AtmosphereSettingsState.State(pointerMode = PointerMode.AVOID).avoidMouse)
        assertTrue(AtmosphereSettingsState.State(pointerMode = PointerMode.ATTRACT).avoidMouse)
        assertFalse(AtmosphereSettingsState.State(pointerMode = PointerMode.OFF).avoidMouse)
        // The mirror only ever switches the pointer off, so a stale `true` cannot undo an explicit mode.
        val attract = AtmosphereSettingsState.State(pointerMode = PointerMode.ATTRACT)
        attract.avoidMouse = true
        assertEquals(PointerMode.ATTRACT, attract.pointerMode)
        attract.avoidMouse = false
        assertEquals(PointerMode.OFF, attract.pointerMode)
    }

    fun `test boolean flags round-trip independently of each other`() {
        for (pointerMode in PointerMode.entries) {
            for (reactToActivity in listOf(false, true)) {
                for (pauseOnFocusLoss in listOf(false, true)) {
                    val original = AtmosphereSettingsState.State(
                        reactToActivity = reactToActivity,
                        pauseOnFocusLoss = pauseOnFocusLoss,
                        reactToTyping = pauseOnFocusLoss != reactToActivity,
                        pointerMode = pointerMode,
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
            pointerMode = PointerMode.OFF,
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
        assertEquals(PointerMode.AVOID, restored.pointerMode)
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
        original.pointerMode = PointerMode.OFF
        original.reduceMotion = true
        original.darkEffectId = "snow"
        original.lightEffectId = "rain"
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
            pointerMode = PointerMode.OFF,
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
        copy.pointerMode = PointerMode.ATTRACT
        copy.reduceMotion = true
        copy.darkEffectId = "snow"
        copy.lightEffectId = "rain"
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
        draft.pointerMode = PointerMode.OFF

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
