package info.chrzanowski.ideatmosphere.events

import info.chrzanowski.ideatmosphere.core.AtmosphereEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class IdleActivityProducerTest {

    private val timeout = 30.seconds

    @Test
    fun `burst of events publishes nothing while active, silence publishes USER_IDLE once`() = runTest {
        val events = MutableSharedFlow<Unit>()
        val published = ArrayList<AtmosphereEvent>()
        val producer = IdleActivityProducer(events, published::add, timeout)
        val job = producer.start(this)
        runCurrent()

        repeat(5) {
            events.emit(Unit)
            advanceTimeBy(1.seconds)
        }
        assertTrue(published.isEmpty())
        assertFalse(producer.isIdle)

        advanceTimeBy(timeout - 1.seconds)
        assertTrue("timeout counts from the last event", published.isEmpty())
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(listOf(AtmosphereEvent.USER_IDLE), published)
        assertTrue(producer.isIdle)

        advanceTimeBy(300.seconds)
        assertEquals("idle is published once per idle period", listOf(AtmosphereEvent.USER_IDLE), published)

        job.cancel()
    }

    @Test
    fun `first event after idle publishes USER_ACTIVE once and restarts the countdown`() = runTest {
        val events = MutableSharedFlow<Unit>()
        val published = ArrayList<AtmosphereEvent>()
        val producer = IdleActivityProducer(events, published::add, timeout)
        val job = producer.start(this)
        runCurrent()

        events.emit(Unit)
        advanceTimeBy(timeout + 1.seconds)
        assertEquals(listOf(AtmosphereEvent.USER_IDLE), published)

        repeat(3) {
            events.emit(Unit)
            advanceTimeBy(1.seconds)
        }
        assertEquals(listOf(AtmosphereEvent.USER_IDLE, AtmosphereEvent.USER_ACTIVE), published)
        assertFalse(producer.isIdle)

        advanceTimeBy(timeout + 1.seconds)
        assertEquals(
            listOf(AtmosphereEvent.USER_IDLE, AtmosphereEvent.USER_ACTIVE, AtmosphereEvent.USER_IDLE),
            published,
        )

        job.cancel()
    }

    @Test
    fun `replayed event on subscription starts the countdown, cancel stops publishing`() = runTest {
        // IdleTracker.events has replay = 1: a subscriber immediately receives the last event.
        val events = MutableSharedFlow<Unit>(replay = 1)
        events.emit(Unit)
        val published = ArrayList<AtmosphereEvent>()
        val producer = IdleActivityProducer(events, published::add, timeout)
        val job = producer.start(this)

        advanceTimeBy(timeout + 1.seconds)
        assertEquals(listOf(AtmosphereEvent.USER_IDLE), published)

        job.cancel()
        events.emit(Unit)
        advanceTimeBy(timeout + 1.seconds)
        assertEquals("no events after cancel", listOf(AtmosphereEvent.USER_IDLE), published)

        // Restarting begins as active again.
        val restarted = producer.start(this)
        assertFalse(producer.isIdle)
        advanceTimeBy(timeout + 1.seconds)
        assertEquals(listOf(AtmosphereEvent.USER_IDLE, AtmosphereEvent.USER_IDLE), published)
        restarted.cancel()
    }
}
