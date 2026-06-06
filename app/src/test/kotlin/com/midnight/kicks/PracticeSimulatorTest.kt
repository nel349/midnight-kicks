package com.midnight.kicks

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Tests for the off-chain QUICK PRACTICE simulator. Pure local logic — no SDK,
 * no chain — so it's fully unit-testable with a controlled [Random].
 */
class PracticeSimulatorTest {

    private val zeros = intArrayOf(0, 0, 0, 0, 0)

    /** A Random that always yields 0 → AI shoots/keeps are all 0 (deterministic). */
    private val allZeroRandom = object : Random() {
        override fun nextBits(bitCount: Int): Int = 0
    }

    @Test
    fun `simulate returns a full local match result`() = runBlocking {
        val shoots = intArrayOf(0, 1, 2, 0, 1)
        val keeps = intArrayOf(2, 1, 0, 2, 1)
        var sdCalls = 0
        val result = PracticeSimulator.simulate(
            shoots.copyOf(),
            keeps.copyOf(),
            getSdPicks = { sdCalls++; 0 to 1 },
            random = Random(42),
        )

        assertEquals("no contract backs a practice match", PracticeSimulator.PRACTICE_ADDRESS, result.contractAddress)
        assertArrayEquals("player shoots preserved", shoots, result.p1Shoots)
        assertArrayEquals("player keeps preserved", keeps, result.p1Keeps)
        // Regulation is always 10 rounds.
        assertEquals(10, result.toRoundResults().take(10).size)
        // getSdPicks is called exactly once per recorded SD round.
        assertEquals(sdCalls, result.sdRounds.size)
        // Terminated decisively (unless the symmetric-pick safety cap was hit).
        val (p1, p2) = result.scores()
        assertTrue("match resolved or hit SD cap", p1 != p2 || result.sdRounds.size >= 100)
    }

    @Test
    fun `same seed yields a deterministic result`() = runBlocking {
        val s = intArrayOf(0, 1, 2, 0, 1)
        val k = intArrayOf(1, 2, 0, 1, 2)
        val r1 = PracticeSimulator.simulate(s.copyOf(), k.copyOf(), { 0 to 1 }, Random(7))
        val r2 = PracticeSimulator.simulate(s.copyOf(), k.copyOf(), { 0 to 1 }, Random(7))
        assertEquals(r1.scores(), r2.scores())
        assertArrayEquals(r1.p2Shoots, r2.p2Shoots)
        assertArrayEquals(r1.p2Keeps, r2.p2Keeps)
    }

    @Test
    fun `a regulation tie goes to sudden death until decisive`() = runBlocking {
        // All-zero player + all-zero AI → every regulation kick is shoot==keep
        // → 0 goals each → 0-0 draw → sudden death.
        var lastSdRound = -1
        val result = PracticeSimulator.simulate(
            zeros.copyOf(),
            zeros.copyOf(),
            // Player SD: shoot=1 (beats AI keep=0 → goal), keep=0 (AI shoot=0 → saved).
            // Decisive in one round.
            getSdPicks = { round -> lastSdRound = round; 1 to 0 },
            random = allZeroRandom,
        )

        assertEquals("regulation drew 0-0", 0, result.toRoundResults().take(10).count { it.result == "goal" })
        assertEquals("one SD round was enough", 1, result.sdRounds.size)
        assertEquals("SD rounds are 1-indexed", 1, lastSdRound)
        val (p1, p2) = result.scores()
        assertEquals("player won in SD", 1, p1)
        assertEquals(0, p2)
    }
}
