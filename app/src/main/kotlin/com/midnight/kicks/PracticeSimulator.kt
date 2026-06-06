package com.midnight.kicks

import android.util.Log
import kotlin.random.Random

/**
 * Off-chain QUICK PRACTICE simulator.
 *
 * Plays a full penalty match against a random AI entirely on-device — NO
 * contract, proving, dust, sigil, or chain round-trips — and returns the same
 * [MatchResult] the on-chain flow produces, so the Unity replay + result UI are
 * identical; only the chain is skipped. Practice matches are ephemeral: nothing
 * is persisted, so there is nothing to resume (the player just starts another).
 *
 * Scoring mirrors the contract exactly by reusing [MatchResult.scores] /
 * [MatchResult.toRoundResults] (a goal is shootDir != keepDir; a regulation
 * draw goes to sudden-death rounds until one side's round breaks the tie).
 */
object PracticeSimulator {
    private const val TAG = "PracticeSimulator"

    /** Sentinel [MatchResult.contractAddress] — no contract backs a practice match. */
    const val PRACTICE_ADDRESS = "offchain-practice"

    /** A fully-symmetric pick pattern can tie every SD round; bound the loop. */
    private const val MAX_SD_ROUNDS = 100

    /**
     * Run a practice match. [getSdPicks] is the same suspend callback the live
     * orchestrator uses (wired to the SD picker UI), so sudden death prompts the
     * player exactly as the on-chain mode does. [random] is injectable for tests.
     */
    suspend fun simulate(
        playerShoots: IntArray,
        playerKeeps: IntArray,
        getSdPicks: suspend (round: Int) -> Pair<Int, Int>,
        random: Random = Random.Default,
    ): MatchResult {
        require(playerShoots.size == MatchManager.PICKS_PER_ARRAY) {
            "need ${MatchManager.PICKS_PER_ARRAY} shoots"
        }
        require(playerKeeps.size == MatchManager.PICKS_PER_ARRAY) {
            "need ${MatchManager.PICKS_PER_ARRAY} keeps"
        }

        val aiShoots = IntArray(MatchManager.PICKS_PER_ARRAY) { random.nextInt(MatchManager.DIRECTION_COUNT) }
        val aiKeeps = IntArray(MatchManager.PICKS_PER_ARRAY) { random.nextInt(MatchManager.DIRECTION_COUNT) }
        Log.i(
            TAG,
            "practice: player shoots=${playerShoots.toList()} keeps=${playerKeeps.toList()} | " +
                "ai shoots=${aiShoots.toList()} keeps=${aiKeeps.toList()}",
        )

        val sdRounds = mutableListOf<SdRoundData>()
        fun current() = MatchResult(
            p1Shoots = playerShoots.copyOf(),
            p1Keeps = playerKeeps.copyOf(),
            p2Shoots = aiShoots.copyOf(),
            p2Keeps = aiKeeps.copyOf(),
            sdRounds = sdRounds.toList(),
            contractAddress = PRACTICE_ADDRESS,
        )

        var (p1, p2) = current().scores()
        var round = 1
        // Regulation tie → sudden death. Both sides enter each round level, so
        // the first round whose cumulative score goes unequal is decisive.
        while (p1 == p2 && round <= MAX_SD_ROUNDS) {
            val (pShoot, pKeep) = getSdPicks(round)
            sdRounds += SdRoundData(
                round = round,
                p1Shoot = pShoot,
                p1Keep = pKeep,
                p2Shoot = random.nextInt(MatchManager.DIRECTION_COUNT),
                p2Keep = random.nextInt(MatchManager.DIRECTION_COUNT),
            )
            current().scores().let { p1 = it.first; p2 = it.second }
            round++
        }
        Log.i(TAG, "practice result: $p1-$p2 after ${sdRounds.size} SD round(s)")
        return current()
    }
}
