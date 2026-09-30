package info.chrzanowski.ideatmosphere.settings

import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import info.chrzanowski.ideatmosphere.core.AtmosphereReactionProfile
import info.chrzanowski.ideatmosphere.effects.AtmosphereEffects
import org.jdom.Element

class AtmosphereSettingsStateTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        AtmosphereSettingsState.getInstance().loadState(AtmosphereSettingsState.State())
    }

    fun `test defaults`() {
        val settings = AtmosphereSettingsState()
        val state = settings.state

        assertEquals("fireflies", AtmosphereEffects.DEFAULT_ID)
        assertEquals(AtmosphereEffects.DEFAULT_ID, state.effectId)
        assertEquals(1f, state.intensity)
        assertEquals(1f, state.speed)
        assertTrue(state.reactToActivity)
        assertTrue(state.pauseOnFocusLoss)
        assertEquals(AtmosphereReactionProfile(), state.activityProfile)
        assertSame(state.activityProfile, state.reactionProfile())
        assertNull(state.firefliesProfile)
        assertNull(state.sakuraProfile)
        assertNull(AtmosphereSettingsState.State().activityProfile)
        assertEquals(AtmosphereReactionProfile(), AtmosphereSettingsState.State().reactionProfile())
    }

    fun `test XML persistence round-trip`() {
        val settings = AtmosphereSettingsState.getInstance()
        val originalState = AtmosphereSettingsState.State(
            effectId = "sakura",
            intensity = 0.5f,
            speed = 1.5f,
            reactToActivity = false,
            pauseOnFocusLoss = false,
            activityProfile = AtmosphereReactionProfile(),
        )
        originalState.reactionProfile().movement.apply {
            attackSeconds = 1.25f
            decaySeconds = 2.5f
            sustainRatio = 0.35f
            releaseSeconds = 3.75f
        }
        originalState.reactionProfile().successPulse.apply {
            attackSeconds = 0.25f
            decaySeconds = 1.5f
            sustainRatio = 0.45f
            releaseSeconds = 2.75f
        }

        val restoredState = roundTrip(originalState)
        settings.loadState(restoredState)

        assertEquals(originalState, restoredState)
        assertEquals(originalState, settings.state)
        assertNotSame(originalState.activityProfile, restoredState.activityProfile)
        assertNotSame(originalState.reactionProfile().movement, restoredState.reactionProfile().movement)
        assertNotSame(originalState.reactionProfile().successPulse, restoredState.reactionProfile().successPulse)
        assertNotSame(restoredState.activityProfile, settings.state.activityProfile)
        assertNotSame(restoredState.reactionProfile().movement, settings.state.reactionProfile().movement)
        assertNotSame(restoredState.reactionProfile().successPulse, settings.state.reactionProfile().successPulse)
        assertNull(settings.state.firefliesProfile)
        assertNull(settings.state.sakuraProfile)
    }

    fun `test legacy XML without profiles uses shared defaults regardless of theme`() {
        for (effectId in listOf("fireflies", "sakura")) {
            val xml = Element("State")
                .addContent(option("effectId", effectId))
                .addContent(option("intensity", "0.8"))
                .addContent(option("speed", "1.4"))
                .addContent(option("reactToActivity", "false"))
                .addContent(option("pauseOnFocusLoss", "false"))
            val restored = XmlSerializer.deserialize(xml, AtmosphereSettingsState.State::class.java)
            assertNull(restored.activityProfile)
            assertNull(restored.firefliesProfile)
            assertNull(restored.sakuraProfile)
            assertEquals(AtmosphereReactionProfile(), restored.reactionProfile())
            AtmosphereSettingsState.getInstance().loadState(restored)
            val state = AtmosphereSettingsState.getInstance().state

            assertEquals(effectId, state.effectId)
            assertEquals(0.8f, state.intensity)
            assertEquals(1.4f, state.speed)
            assertFalse(state.reactToActivity)
            assertFalse(state.pauseOnFocusLoss)
            assertEquals(AtmosphereReactionProfile(), state.activityProfile)
            assertNull(state.firefliesProfile)
            assertNull(state.sakuraProfile)
        }
    }

    fun `test legacy XML with one profile migrates it or defaults the active theme`() {
        for (effectId in listOf("fireflies", "sakura")) {
            for (legacyEffectId in listOf("fireflies", "sakura")) {
                val profile =
                    if (legacyEffectId == "sakura") AtmosphereReactionProfile.sakura() else AtmosphereReactionProfile.fireflies()
                profile.movement.attackSeconds = 2.75f
                profile.successPulse.sustainRatio = 0.35f
                val original = AtmosphereSettingsState.State(
                    effectId = effectId,
                    firefliesProfile = profile.takeIf { legacyEffectId == "fireflies" },
                    sakuraProfile = profile.takeIf { legacyEffectId == "sakura" },
                )
                val restored = roundTrip(original)
                val expected = when (effectId) {
                    legacyEffectId -> profile
                    "sakura" -> AtmosphereReactionProfile.sakura()
                    else -> AtmosphereReactionProfile.fireflies()
                }
                assertEquals(expected, restored.reactionProfile())
                AtmosphereSettingsState.getInstance().loadState(restored)
                val state = AtmosphereSettingsState.getInstance().state

                assertEquals(expected, state.activityProfile)
                assertNull(state.firefliesProfile)
                assertNull(state.sakuraProfile)
            }
        }
    }

    fun `test legacy XML migrates only the active theme profile`() {
        val firefliesProfile = AtmosphereReactionProfile.fireflies().apply {
            movement.attackSeconds = 2.75f
            movement.decaySeconds = 3.5f
            movement.sustainRatio = 0.25f
            movement.releaseSeconds = 4.5f
            successPulse.attackSeconds = 0.75f
            successPulse.decaySeconds = 1.5f
            successPulse.sustainRatio = 0.35f
            successPulse.releaseSeconds = 2.5f
        }
        val sakuraProfile = AtmosphereReactionProfile.sakura().apply {
            movement.attackSeconds = 5.75f
            movement.decaySeconds = 6.5f
            movement.sustainRatio = 0.65f
            movement.releaseSeconds = 7.5f
            successPulse.attackSeconds = 3.75f
            successPulse.decaySeconds = 4.5f
            successPulse.sustainRatio = 0.85f
            successPulse.releaseSeconds = 5.5f
        }
        for (effectId in listOf("fireflies", "sakura")) {
            val original = AtmosphereSettingsState.State(
                effectId = effectId,
                firefliesProfile = firefliesProfile,
                sakuraProfile = sakuraProfile,
            )
            val xml = XmlSerializer.serialize(original)
            val optionNames = xml.getChildren("option").map { it.getAttributeValue("name") }
            assertFalse(optionNames.contains("activityProfile"))
            assertTrue(optionNames.contains("firefliesProfile"))
            assertTrue(optionNames.contains("sakuraProfile"))
            val restored = XmlSerializer.deserialize(
                JDOMUtil.load(JDOMUtil.writeElement(xml)),
                AtmosphereSettingsState.State::class.java,
            )
            val expected = if (effectId == "sakura") sakuraProfile else firefliesProfile
            assertEquals(expected, restored.reactionProfile())
            AtmosphereSettingsState.getInstance().loadState(restored)
            val state = AtmosphereSettingsState.getInstance().state

            assertEquals(effectId, state.effectId)
            assertEquals(expected, state.activityProfile)
            assertNull(state.firefliesProfile)
            assertNull(state.sakuraProfile)
        }
    }

    fun `test shared profile takes precedence over legacy profiles`() {
        val profile = AtmosphereReactionProfile().apply {
            movement.attackSeconds = 3.25f
            successPulse.releaseSeconds = 4.75f
        }
        for (effectId in listOf("fireflies", "sakura")) {
            val original = AtmosphereSettingsState.State(
                effectId = effectId,
                activityProfile = profile,
                firefliesProfile = AtmosphereReactionProfile.fireflies(),
                sakuraProfile = AtmosphereReactionProfile.sakura(),
            )
            assertSame(profile, original.reactionProfile())
            val restored = roundTrip(original)
            assertSame(restored.activityProfile, restored.reactionProfile())
            AtmosphereSettingsState.getInstance().loadState(restored)

            assertEquals(profile, AtmosphereSettingsState.getInstance().state.activityProfile)
            assertNull(AtmosphereSettingsState.getInstance().state.firefliesProfile)
            assertNull(AtmosphereSettingsState.getInstance().state.sakuraProfile)
        }
    }

    fun `test normalized XML persists only the shared profile`() {
        val original = AtmosphereSettingsState.State(
            effectId = "sakura",
            firefliesProfile = AtmosphereReactionProfile.fireflies(),
            sakuraProfile = AtmosphereReactionProfile.sakura().apply { movement.attackSeconds = 4.25f },
        )
        val normalized = roundTrip(original).normalized()
        val xml = XmlSerializer.serialize(normalized)
        val optionNames = xml.getChildren("option").map { it.getAttributeValue("name") }

        assertTrue(optionNames.contains("activityProfile"))
        assertFalse(optionNames.contains("firefliesProfile"))
        assertFalse(optionNames.contains("sakuraProfile"))
        assertEquals(original.sakuraProfile, normalized.activityProfile)
        assertNull(normalized.firefliesProfile)
        assertNull(normalized.sakuraProfile)
        assertEquals(normalized, roundTrip(normalized))
        assertEquals(normalized, normalized.normalized())
        assertNotNull(original.firefliesProfile)
        assertNotNull(original.sakuraProfile)
        normalized.reactionProfile().movement.attackSeconds = 9f
        assertEquals(4.25f, requireNotNull(original.sakuraProfile).movement.attackSeconds)
    }

    fun `test unknown effect falls back without discarding profiles`() {
        val original =
            AtmosphereSettingsState.State(effectId = "non-existent", activityProfile = AtmosphereReactionProfile())
        original.reactionProfile().movement.attackSeconds = 2.5f
        original.reactionProfile().successPulse.releaseSeconds = 4.5f
        val settings = AtmosphereSettingsState.getInstance()
        settings.loadState(roundTrip(original))

        assertEquals(AtmosphereEffects.DEFAULT_ID, settings.state.effectId)
        assertSame(settings.state.activityProfile, settings.state.reactionProfile())
        assertEquals(2.5f, settings.state.reactionProfile().movement.attackSeconds)
        assertEquals(4.5f, settings.state.reactionProfile().successPulse.releaseSeconds)
    }

    fun `test unknown effect migrates the default theme legacy profile`() {
        val original = AtmosphereSettingsState.State(
            effectId = "non-existent",
            firefliesProfile = AtmosphereReactionProfile.fireflies().apply { movement.attackSeconds = 2.5f },
            sakuraProfile = AtmosphereReactionProfile.sakura().apply { movement.attackSeconds = 4.5f },
        )
        val settings = AtmosphereSettingsState.getInstance()
        settings.loadState(roundTrip(original))

        assertEquals(AtmosphereEffects.DEFAULT_ID, settings.state.effectId)
        assertEquals(original.firefliesProfile, settings.state.activityProfile)
        assertNull(settings.state.firefliesProfile)
        assertNull(settings.state.sakuraProfile)
    }

    fun `test nonfinite intensity and speed use defaults`() {
        val settings = AtmosphereSettingsState.getInstance()
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            settings.loadState(roundTrip(AtmosphereSettingsState.State(intensity = invalid, speed = invalid)))
            assertEquals(1f, settings.state.intensity)
            assertEquals(1f, settings.state.speed)
        }
    }

    fun `test intensity and speed clamp to supported range`() {
        val settings = AtmosphereSettingsState.getInstance()
        settings.loadState(AtmosphereSettingsState.State(intensity = -1f, speed = 20f))
        assertEquals(0.3f, settings.state.intensity)
        assertEquals(2f, settings.state.speed)

        settings.loadState(AtmosphereSettingsState.State(intensity = 20f, speed = -1f))
        assertEquals(2f, settings.state.intensity)
        assertEquals(0.3f, settings.state.speed)
    }

    fun `test nonfinite envelopes use shared defaults after XML load`() {
        val settings = AtmosphereSettingsState.getInstance()
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            for (effectId in listOf("fireflies", "sakura")) {
                val original =
                    AtmosphereSettingsState.State(effectId = effectId, activityProfile = AtmosphereReactionProfile())
                val profile = original.reactionProfile()
                for (envelope in listOf(profile.movement, profile.successPulse)) {
                    envelope.attackSeconds = invalid
                    envelope.decaySeconds = invalid
                    envelope.sustainRatio = invalid
                    envelope.releaseSeconds = invalid
                }
                settings.loadState(roundTrip(original))
                assertEquals(AtmosphereReactionProfile(), settings.state.activityProfile)
            }
        }
    }

    fun `test migrated nonfinite envelopes use shared defaults`() {
        val settings = AtmosphereSettingsState.getInstance()
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            for (effectId in listOf("fireflies", "sakura")) {
                val profile =
                    if (effectId == "sakura") AtmosphereReactionProfile.sakura() else AtmosphereReactionProfile.fireflies()
                for (envelope in listOf(profile.movement, profile.successPulse)) {
                    envelope.attackSeconds = invalid
                    envelope.decaySeconds = invalid
                    envelope.sustainRatio = invalid
                    envelope.releaseSeconds = invalid
                }
                val original = AtmosphereSettingsState.State(
                    effectId = effectId,
                    firefliesProfile = profile.takeIf { effectId == "fireflies" },
                    sakuraProfile = profile.takeIf { effectId == "sakura" },
                )
                settings.loadState(roundTrip(original))

                assertEquals(AtmosphereReactionProfile(), settings.state.activityProfile)
                assertNull(settings.state.firefliesProfile)
                assertNull(settings.state.sakuraProfile)
            }
        }
    }

    fun `test envelope values clamp to supported ranges`() {
        val original = AtmosphereSettingsState.State(activityProfile = AtmosphereReactionProfile())
        original.reactionProfile().movement.apply {
            attackSeconds = -2f
            decaySeconds = 20f
            sustainRatio = 2f
            releaseSeconds = -3f
        }
        original.reactionProfile().successPulse.apply {
            attackSeconds = 20f
            decaySeconds = -2f
            sustainRatio = -1f
            releaseSeconds = 30f
        }
        val settings = AtmosphereSettingsState.getInstance()
        settings.loadState(roundTrip(original))
        val profile = settings.state.reactionProfile()
        assertEquals(0f, profile.movement.attackSeconds)
        assertEquals(10f, profile.movement.decaySeconds)
        assertEquals(1f, profile.movement.sustainRatio)
        assertEquals(0f, profile.movement.releaseSeconds)
        assertEquals(10f, profile.successPulse.attackSeconds)
        assertEquals(0f, profile.successPulse.decaySeconds)
        assertEquals(0f, profile.successPulse.sustainRatio)
        assertEquals(10f, profile.successPulse.releaseSeconds)
    }

    fun `test deep copy separates every mutable envelope`() {
        val original = AtmosphereSettingsState.State(
            activityProfile = AtmosphereReactionProfile(),
            firefliesProfile = AtmosphereReactionProfile.fireflies(),
            sakuraProfile = AtmosphereReactionProfile.sakura(),
        )
        val copy = original.deepCopy()
        assertEquals(original, copy)
        for ((profile, copiedProfile) in listOf(
            requireNotNull(original.activityProfile) to requireNotNull(copy.activityProfile),
            requireNotNull(original.firefliesProfile) to requireNotNull(copy.firefliesProfile),
            requireNotNull(original.sakuraProfile) to requireNotNull(copy.sakuraProfile),
        )) {
            assertNotSame(profile, copiedProfile)
            assertNotSame(profile.movement, copiedProfile.movement)
            assertNotSame(profile.successPulse, copiedProfile.successPulse)
            copiedProfile.movement.attackSeconds = 9f
            copiedProfile.successPulse.decaySeconds = 8f
        }
        assertEquals(AtmosphereReactionProfile(), original.activityProfile)
        assertEquals(AtmosphereReactionProfile.fireflies(), original.firefliesProfile)
        assertEquals(AtmosphereReactionProfile.sakura(), original.sakuraProfile)
    }

    fun `test deep copy preserves absent profiles`() {
        val original = AtmosphereSettingsState.State()
        val copy = original.deepCopy()

        assertNotSame(original, copy)
        assertEquals(original, copy)
        assertNull(copy.activityProfile)
        assertNull(copy.firefliesProfile)
        assertNull(copy.sakuraProfile)
    }

    fun `test draft does not mutate live shared settings`() {
        val settings = AtmosphereSettingsState.getInstance()
        val original = settings.state.deepCopy()
        val draft = settings.state.deepCopy()
        draft.effectId = "sakura"
        draft.intensity = 2f
        draft.speed = 0.3f
        draft.reactToActivity = false
        draft.pauseOnFocusLoss = false
        draft.reactionProfile().movement.attackSeconds = 9f
        draft.reactionProfile().successPulse.releaseSeconds = 8f

        assertEquals(original, settings.state)
    }

    fun `test load state owns its profiles`() {
        val original = AtmosphereSettingsState.State(
            activityProfile = AtmosphereReactionProfile(),
            firefliesProfile = AtmosphereReactionProfile.fireflies(),
            sakuraProfile = AtmosphereReactionProfile.sakura(),
        )
        val settings = AtmosphereSettingsState.getInstance()
        settings.loadState(original)
        assertNotSame(original, settings.state)
        assertNotSame(original.activityProfile, settings.state.activityProfile)
        assertNotSame(original.reactionProfile().movement, settings.state.reactionProfile().movement)
        assertNotSame(original.reactionProfile().successPulse, settings.state.reactionProfile().successPulse)
        original.intensity = 2f
        original.reactionProfile().movement.attackSeconds = 9f
        original.reactionProfile().successPulse.releaseSeconds = 9f
        requireNotNull(original.firefliesProfile).movement.attackSeconds = 9f
        requireNotNull(original.sakuraProfile).successPulse.releaseSeconds = 9f

        assertEquals(AtmosphereSettingsState.State().normalized(), settings.state)
    }

    fun `test load state detaches migrated legacy profiles`() {
        for (effectId in listOf("fireflies", "sakura")) {
            val original = AtmosphereSettingsState.State(
                effectId = effectId,
                firefliesProfile = AtmosphereReactionProfile.fireflies(),
                sakuraProfile = AtmosphereReactionProfile.sakura(),
            )
            val profile = original.reactionProfile()
            val expected = profile.deepCopy()
            val settings = AtmosphereSettingsState.getInstance()
            settings.loadState(original)
            assertNotSame(profile, settings.state.activityProfile)
            assertNotSame(profile.movement, settings.state.reactionProfile().movement)
            assertNotSame(profile.successPulse, settings.state.reactionProfile().successPulse)
            profile.movement.attackSeconds = 9f
            profile.successPulse.releaseSeconds = 9f

            assertEquals(expected, settings.state.activityProfile)
            assertNull(settings.state.firefliesProfile)
            assertNull(settings.state.sakuraProfile)
        }
    }

    private fun roundTrip(state: AtmosphereSettingsState.State): AtmosphereSettingsState.State =
        XmlSerializer.deserialize(
            JDOMUtil.load(JDOMUtil.writeElement(XmlSerializer.serialize(state))),
            AtmosphereSettingsState.State::class.java,
        )

    private fun option(name: String, value: String): Element =
        Element("option").setAttribute("name", name).setAttribute("value", value)
}
