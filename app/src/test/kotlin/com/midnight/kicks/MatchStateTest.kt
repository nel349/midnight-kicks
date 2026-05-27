package com.midnight.kicks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for [MatchState]. Covers:
 *  - every variant resolves a non-empty label,
 *  - the `address` getter propagates correctly through [MatchState.Resolved]
 *    and [MatchState.Failed],
 *  - states that legitimately have no address return null.
 */
class MatchStateTest {

    private val address = "deadbeef".repeat(8)
    private val sampleResult = MatchResult(
        p1Shoots = intArrayOf(0, 1, 2, 1, 0),
        p1Keeps  = intArrayOf(2, 1, 0, 1, 2),
        p2Shoots = intArrayOf(2, 0, 1, 2, 0),
        p2Keeps  = intArrayOf(0, 2, 1, 0, 2),
        sdRounds = emptyList(),
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
        MatchState.SdRoundOpen(address, round = 1),
        MatchState.P1SdCommitting(address, round = 1),
        MatchState.P1SdCommitted(address, round = 1),
        MatchState.P2SdCommitting(address, round = 1),
        MatchState.BothSdCommitted(address, round = 1),
        MatchState.P1SdRevealing(address, round = 1),
        MatchState.P1SdRevealed(address, round = 1),
        MatchState.P2SdRevealing(address, round = 1),
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
        // JoiningAsP2, …) share a label by design. But two different
        // state classes should never collide.
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
            MatchState.SdRoundOpen(address, round = 1),
            MatchState.BothSdCommitted(address, round = 2),
        )
        for (state in withAddress) {
            assertEquals("Address for ${state::class.simpleName}", address, state.address)
        }
    }

    @Test
    fun `SD state labels mention the round number`() {
        val r3 = MatchState.SdRoundOpen(address, round = 3)
        assertTrue(r3.label, r3.label.contains("3"))

        val committing = MatchState.P1SdCommitting(address, round = 7)
        assertTrue(committing.label, committing.label.contains("7"))
    }

    @Test
    fun `sdRound is readable from every SD-phase state, not just SdRoundOpen`() {
        // The SD orchestrator loop reads the round at its top via
        // MatchManager.currentSdRoundOrError() → MatchState.sdRound. A resume
        // can re-enter the loop mid-round (BothSdCommitted, P1SdRevealed, …),
        // so EVERY SD-phase state must surface its round — not just
        // SdRoundOpen. When this regressed, a relaunch at BothSdCommitted
        // threw and dead-locked both players in sudden death.
        val sdStates = listOf(
            MatchState.SdRoundOpen(address, round = 4),
            MatchState.P1SdCommitting(address, round = 4),
            MatchState.P1SdCommitted(address, round = 4),
            MatchState.P2SdCommitting(address, round = 4),
            MatchState.BothSdCommitted(address, round = 4),
            MatchState.P1SdRevealing(address, round = 4),
            MatchState.P1SdRevealed(address, round = 4),
            MatchState.P2SdRevealing(address, round = 4),
        )
        for (state in sdStates) {
            assertEquals("sdRound for ${state::class.simpleName}", 4, state.sdRound)
        }
        // A Failed state in SD recovers the round from its previous state.
        assertEquals(
            4,
            MatchState.Failed(MatchState.BothSdCommitted(address, round = 4), RuntimeException("x")).sdRound,
        )
    }

    @Test
    fun `sdRound is null for non-SD states`() {
        // currentSdRoundOrError must still fault on regulation / resolved
        // states — there's no round to read, and silently inventing one
        // would mask a real orchestrator bug.
        assertNull(MatchState.SdkReady.sdRound)
        assertNull(MatchState.BothCommitted(address).sdRound)
        assertNull(MatchState.P1Revealed(address).sdRound)
        assertNull(MatchState.Resolved(sampleResult).sdRound)
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
    fun `Failed label maps a recognised error to recovery copy, not the raw exception`() {
        // Part A (legible failure copy): a beta player must never read a raw
        // exception. An indexer/sync-flavoured failure maps to recovery guidance,
        // and the verbatim throwable text is NOT surfaced — it stays in logs.
        val failed = MatchState.Failed(MatchState.Joined(address), Exception("indexer down"))
        assertEquals(
            "Still syncing with the network. Give it a moment, then RESUME MATCH from the menu.",
            failed.label,
        )
        assertFalse("must not leak the raw message: ${failed.label}", failed.label.contains("indexer down"))
    }

    @Test
    fun `Failed label uses a safe generic line for unrecognised errors`() {
        // Bare Exception (null message) → no internals leaked (not even the
        // class name); just calm copy plus the real recovery path.
        val failed = MatchState.Failed(MatchState.Joined(address), Exception())
        assertEquals(
            "Something went wrong. Exit and tap RESUME MATCH to try again.",
            failed.label,
        )
        assertFalse("must not mention the exception class: ${failed.label}", failed.label.contains("Exception"))
    }
}
