package com.midnight.kicks

/**
 * Discrete states for a match's life cycle. The state machine is the source
 * of truth for "where is this player in the protocol?" — UI observes it,
 * BlockStore snapshots derive from it, and StatePoller (future) advances it
 * when an on-chain event for the opponent fires.
 *
 * Today's PvAI flow walks straight through: [Idle] → ... → [Resolved], with
 * the orchestrator auto-advancing the AI's steps. The shape is the same for
 * future PvP — the only difference is who supplies P2's choices and who
 * triggers the P2 transitions (the StatePoller, not the orchestrator).
 *
 * Each non-Idle state carries enough context (address, role) to either
 * resume after process death or render stage-specific UX without
 * cross-referencing the [MatchManager].
 */
/**
 * Named rank constants matching [MatchState.protocolRank] for the
 * specific transition points wait functions test against. Use
 * `state.protocolRank >= PhaseRank.X` for idempotent skips. SD
 * ranks need to be combined with the round number — see KDoc on
 * [MatchState.protocolRank].
 */
object PhaseRank {
    const val SDK_READY = 0
    const val DEPLOYED = 10
    const val JOINED = 20
    const val P1_COMMITTED = 30
    const val BOTH_COMMITTED = 40
    const val P1_REVEALED = 50
    // SD ranks are 100 + step + round*10. The step offsets:
    const val SD_ROUND_OPEN_STEP = 0
    const val SD_P1_COMMITTED_STEP = 2
    const val SD_BOTH_COMMITTED_STEP = 4
    const val SD_P1_REVEALED_STEP = 6
    /** P1's SdCommitted rank for [round]: 102, 112, 122, … */
    fun sdP1CommittedAt(round: Int): Int = 100 + SD_P1_COMMITTED_STEP + round * 10
    /** BothSdCommitted rank for [round]: 104, 114, 124, … */
    fun sdBothCommittedAt(round: Int): Int = 100 + SD_BOTH_COMMITTED_STEP + round * 10
    /** P1SdRevealed rank for [round]: 106, 116, 126, … */
    fun sdP1RevealedAt(round: Int): Int = 100 + SD_P1_REVEALED_STEP + round * 10
}

sealed class MatchState {
    /** Contract address once known; null only in [Idle], [InitializingSdk], [SdkReady]. */
    open val address: String? = null

    // ── Setup ──────────────────────────────────────────────────────────
    data object Idle : MatchState()
    data object InitializingSdk : MatchState()
    data object SdkReady : MatchState()

    // ── Deploy ─────────────────────────────────────────────────────────
    data object Deploying : MatchState()
    data class Deployed(override val address: String) : MatchState()

    // ── Join (AI today, scanned opponent in PvP) ────────────────────────
    data class JoiningAsP2(override val address: String) : MatchState()
    data class Joined(override val address: String) : MatchState()

    // ── Commit ─────────────────────────────────────────────────────────
    data class P1Committing(override val address: String) : MatchState()
    data class P1Committed(override val address: String) : MatchState()
    data class P2Committing(override val address: String) : MatchState()
    data class BothCommitted(override val address: String) : MatchState()

    // ── Reveal (regulation) ────────────────────────────────────────────
    data class P1Revealing(override val address: String) : MatchState()
    data class P1Revealed(override val address: String) : MatchState()
    data class P2Revealing(override val address: String) : MatchState()

    // ── Sudden death — one round at a time, parameterised by `round`. ──
    //   After regulation drew, P2's reveal transitions to SdRoundOpen(1).
    //   Each round walks the same Committing → Committed → Revealing →
    //   Revealed sequence as regulation but for {shoot, keep} pairs.
    //   After P2SdRevealed, the contract is either COMPLETE (transition
    //   to Resolved) or back in SD_COMMITTING (transition to
    //   SdRoundOpen(round + 1)).
    data class SdRoundOpen(override val address: String, val round: Int) : MatchState()
    data class P1SdCommitting(override val address: String, val round: Int) : MatchState()
    data class P1SdCommitted(override val address: String, val round: Int) : MatchState()
    data class P2SdCommitting(override val address: String, val round: Int) : MatchState()
    data class BothSdCommitted(override val address: String, val round: Int) : MatchState()
    data class P1SdRevealing(override val address: String, val round: Int) : MatchState()
    data class P1SdRevealed(override val address: String, val round: Int) : MatchState()
    data class P2SdRevealing(override val address: String, val round: Int) : MatchState()

    data class Resolved(val result: MatchResult) : MatchState() {
        override val address: String get() = result.contractAddress
    }

    // ── Failure (preserves the state we were in so the UI can retry) ───
    data class Failed(val previous: MatchState, val error: Throwable) : MatchState() {
        override val address: String? get() = previous.address
    }

    /**
     * P1-perspective label (default). The state names are P1-centric
     * (`P1Committed` = P1 has committed, P2 hasn't), and so are these
     * labels — they're correct for P1 and for PvAI (where the human
     * is P1). For PvP-as-P2, use [labelFor] which flips the wording.
     */
    val label: String get() = labelFor(role = null)

    /**
     * Role-aware label. The protocol's state machine names states
     * from P1's perspective (`P1Committed`, `P1Revealing`, …); for
     * PvP-as-P2, the same chain state reads inverted (when chain shows
     * "P1 committed, P2 hasn't", P2's user is the one with work).
     */
    /**
     * Linear ordering along the protocol progression. Use for
     * idempotent "is the chain already at or past this step?" checks —
     * the chain can validly be at any later state by the time a wait
     * runs (resume mid-match, opponent acted between local writes).
     *
     * SD rounds use `round * 10` offset so round-2 sub-states all
     * rank above round-1's. [Failed] delegates to its previous state.
     * [Resolved] is `Int.MAX_VALUE`; pre-match states are negative so
     * `rank >= JOINED` cleanly excludes pre-match.
     */
    val protocolRank: Int get() = when (this) {
        is Idle -> -20
        is InitializingSdk -> -10
        is SdkReady -> 0
        is Deploying -> 5
        is Deployed -> 10
        is JoiningAsP2 -> 15
        is Joined -> 20
        is P1Committing -> 25
        is P1Committed -> 30
        is P2Committing -> 35
        is BothCommitted -> 40
        is P1Revealing -> 45
        is P1Revealed -> 50
        is P2Revealing -> 55
        is SdRoundOpen -> 100 + round * 10
        is P1SdCommitting -> 101 + round * 10
        is P1SdCommitted -> 102 + round * 10
        is P2SdCommitting -> 103 + round * 10
        is BothSdCommitted -> 104 + round * 10
        is P1SdRevealing -> 105 + round * 10
        is P1SdRevealed -> 106 + round * 10
        is P2SdRevealing -> 107 + round * 10
        is Resolved -> Int.MAX_VALUE
        is Failed -> previous.protocolRank
    }

    /**
     * The current SD round number if this state carries one,
     * `null` otherwise. Used by SD wait predicates that need to
     * scope their predicate to a specific round (e.g.
     * `it.p1Committed && it.sdRound == round`).
     */
    val sdRound: Int? get() = when (this) {
        is SdRoundOpen -> round
        is P1SdCommitting -> round
        is P1SdCommitted -> round
        is P2SdCommitting -> round
        is BothSdCommitted -> round
        is P1SdRevealing -> round
        is P1SdRevealed -> round
        is P2SdRevealing -> round
        is Failed -> previous.sdRound
        else -> null
    }

    fun labelFor(role: Player?): String {
        // PvAI / unknown — fall back to P1-perspective text (the
        // human is always P1 in PvAI).
        val isP2 = role == Player.P2
        return when (this) {
            is Idle               -> "Idle"
            is InitializingSdk    -> "Initializing SDK…"
            is SdkReady           -> "SDK ready"
            is Deploying          -> "Deploying match…"
            is Deployed           -> "Match deployed"
            // P2 device sees "Joining…"; P1 device sees the joining
            // tx coming from the opponent.
            is JoiningAsP2        -> if (isP2) "Joining match…" else "Opponent joining…"
            is Joined             -> "Both players in"
            // P1-named commit phase from P2's view = "opponent committing".
            is P1Committing       -> if (isP2) "Opponent committing…" else "Committing your picks…"
            // P2 sees this when P1 landed their commit and P2 still owes one.
            is P1Committed        -> if (isP2) "Opponent committed — your turn to pick" else "Your picks committed"
            is P2Committing       -> if (isP2) "Committing your picks…" else "Opponent committing…"
            is BothCommitted      -> "Both players committed"
            is P1Revealing        -> if (isP2) "Opponent revealing…" else "Revealing your picks…"
            is P1Revealed         -> if (isP2) "Opponent revealed — your turn to reveal" else "Your picks revealed"
            is P2Revealing        -> if (isP2) "Revealing your picks…" else "Opponent revealing…"
            is SdRoundOpen        -> "Sudden death round $round"
            is P1SdCommitting     -> if (isP2) "Opponent committing SD round $round…" else "Committing SD round $round…"
            is P1SdCommitted      -> if (isP2) "Opponent committed SD — your turn (round $round)" else "Your SD pick committed (round $round)"
            is P2SdCommitting     -> if (isP2) "Committing SD round $round…" else "Opponent committing SD round $round…"
            is BothSdCommitted    -> "Both committed (SD round $round)"
            is P1SdRevealing      -> if (isP2) "Opponent revealing SD round $round…" else "Revealing SD round $round…"
            is P1SdRevealed       -> if (isP2) "Opponent revealed SD — your turn (round $round)" else "Your SD pick revealed (round $round)"
            is P2SdRevealing      -> if (isP2) "Revealing SD round $round…" else "Opponent revealing SD round $round…"
            is Resolved           -> when (result.endedEarly) {
                EarlyOutcome.WON_BY_FORFEIT -> "You win — opponent didn't respond in time"
                EarlyOutcome.CANCELLED_REFUND -> "Match cancelled — your stake is refunded"
                null -> "Match complete!"
            }
            // Plain-language copy, not the raw exception — see toMatchErrorMessage.
            // The HUD's ERROR mode already supplies the red "something's wrong"
            // visual, so the text just needs to say what + how to recover.
            is Failed             -> error.toMatchErrorMessage()
        }
    }
}
