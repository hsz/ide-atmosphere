package info.chrzanowski.ideatmosphere.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitPolicyTest {
    @Test
    fun `sub-threshold motion does not commit`() {
        assertFalse(CommitPolicy.shouldCommit(0.1f, -0.2f, 1, 0))
        assertFalse(CommitPolicy.shouldCommit(CommitPolicy.MOVE_THRESHOLD - 0.01f, 0f, 0, 0))
    }

    @Test
    fun `crossing the movement threshold commits on either axis and either direction`() {
        assertTrue(CommitPolicy.shouldCommit(CommitPolicy.MOVE_THRESHOLD, 0f, 0, 0))
        assertTrue(CommitPolicy.shouldCommit(0f, -CommitPolicy.MOVE_THRESHOLD, 0, 0))
        assertTrue(CommitPolicy.shouldCommit(-1f, 0.1f, 0, 0))
    }

    @Test
    fun `alpha commits only from the step threshold`() {
        assertFalse(CommitPolicy.shouldCommit(0f, 0f, CommitPolicy.ALPHA_STEP_THRESHOLD - 1, 0))
        assertTrue(CommitPolicy.shouldCommit(0f, 0f, CommitPolicy.ALPHA_STEP_THRESHOLD, 0))
    }

    @Test
    fun `staleness bound forces commit of any pending change`() {
        assertFalse(CommitPolicy.shouldCommit(0.01f, 0f, 0, CommitPolicy.MAX_STALE_TICKS - 1))
        assertTrue(CommitPolicy.shouldCommit(0.01f, 0f, 0, CommitPolicy.MAX_STALE_TICKS))
        assertTrue(CommitPolicy.shouldCommit(0f, -0.01f, 0, CommitPolicy.MAX_STALE_TICKS + 5))
        assertTrue(CommitPolicy.shouldCommit(0f, 0f, 1, CommitPolicy.MAX_STALE_TICKS))
    }

    @Test
    fun `zero change never commits however stale`() {
        assertFalse(CommitPolicy.shouldCommit(0f, 0f, 0, 0))
        assertFalse(CommitPolicy.shouldCommit(0f, 0f, 0, 1_000))
    }

    @Test
    fun `custom move threshold is honoured`() {
        assertFalse(CommitPolicy.shouldCommit(0.5f, 0f, 0, 0, moveThreshold = 1f))
        assertTrue(CommitPolicy.shouldCommit(1f, 0f, 0, 0, moveThreshold = 1f))
    }
}
