package com.midnight.kicks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for [MatchState]. Covers:
 *  - every variant resolves a non-empty label (exhaustive `when` catches
 *    a missing arm at compile time, but blank strings would slip through),
 *  - the `address` getter propagates correctly through [MatchState.Resolved]
 *    and [MatchState.Failed],
 *  - states that legitimately have no address return null.
 */
class MatchStateTest {

    private val address = "deadbeef".repeat(8)
    private val sampleResult = MatchResult(
        playerChoices = intArrayOf(0, 1, 2, 1, 0),
        aiChoices = intArrayOf(2, 1, 0, 1, 2),
        contractAddress = address,
    )

    private val allStates: List<MatchState> = listOf(
        MatchState.Idle,
        MatchState.InitializingSdk,
        MatchState.SdkReady,
        MatchState.Deploying,
        MatchState.Deployed(address),
        MatchState.JoiningAsP2(address),
        MatchState.Joined(address),
        MatchState.P1Committing(address),
        MatchState.P1Committed(address),
        MatchState.P2Committing(address),
        MatchState.BothCommitted(address),
        MatchState.P1Revealing(address),
        MatchState.P1Revealed(address),
        MatchState.P2Revealing(address),
        MatchState.Resolved(sampleResult),
        MatchState.Failed(MatchState.Deployed(address), RuntimeException("boom")),
    )

    @Test
    fun `every state variant has a non-empty label`() {
        for (state in allStates) {
            val label = state.label
            assertTrue("Label for ${state::class.simpleName} is blank", label.isNotBlank())
        }
    }

    @Test
    fun `labels are unique per state class`() {
        // Variants with the same class but different params (Deployed,
        // JoiningAsP2, …) all share a label — that's by design. But two
        // different state classes should never collide.
        val labelsByClass = allStates.associate { it::class.simpleName to it.label }
        assertEquals(
            "Class-to-label mapping is not 1:1",
            labelsByClass.values.toSet().size,
            labelsByClass.size,
        )
    }

    @Test
    fun `address is null for pre-deploy states`() {
        assertNull(MatchState.Idle.address)
        assertNull(MatchState.InitializingSdk.address)
        assertNull(MatchState.SdkReady.address)
        assertNull(MatchState.Deploying.address)
    }

    @Test
    fun `address is set for post-deploy states`() {
        val withAddress = listOf(
            MatchState.Deployed(address),
            MatchState.JoiningAsP2(address),
            MatchState.Joined(address),
            MatchState.P1Committing(address),
            MatchState.P1Committed(address),
            MatchState.P2Committing(address),
            MatchState.BothCommitted(address),
            MatchState.P1Revealing(address),
            MatchState.P1Revealed(address),
            MatchState.P2Revealing(address),
        )
        for (state in withAddress) {
            assertEquals("Address for ${state::class.simpleName}", address, state.address)
        }
    }

    @Test
    fun `Resolved address comes from the contained MatchResult`() {
        val resolved = MatchState.Resolved(sampleResult)
        assertEquals(address, resolved.address)
    }

    @Test
    fun `Failed propagates the previous state's address`() {
        val failedFromDeployed = MatchState.Failed(MatchState.Deployed(address), Exception("x"))
        assertEquals(address, failedFromDeployed.address)

        val failedFromIdle = MatchState.Failed(MatchState.Idle, Exception("x"))
        assertNull(failedFromIdle.address)
    }

    @Test
    fun `Failed label includes the underlying error message`() {
        val failed = MatchState.Failed(MatchState.Joined(address), Exception("indexer down"))
        assertTrue("Label should contain 'indexer down': ${failed.label}", failed.label.contains("indexer down"))
    }

    @Test
    fun `Failed label falls back to class name when error message is null`() {
        val failed = MatchState.Failed(MatchState.Joined(address), Exception())
        // Bare Exception has null message → label uses simple class name.
        assertNotNull(failed.label)
        assertTrue("Label should mention exception class: ${failed.label}", failed.label.contains("Exception"))
    }
}
