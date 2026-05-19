package com.midnight.kicks

/**
 * Top-level navigation state for [KicksActivity]. State-based routing keeps
 * the dep surface small (no Navigation-Compose dep) and matches the existing
 * `mutableStateOf` pattern in this Activity.
 *
 * - [Menu] — main menu (CREATE / JOIN / RESUME if any active matches /
 *   dev-only PRACTICE vs AI)
 * - [Creating] — P1 side: contract is being deployed, then the address +
 *   QR are shown for the opponent. `address == null` while deploying.
 * - [Joining] — P2 side: opponent address entry. `prefilledAddress` is
 *   set when reached via `midnight://kicks?match=…` deep link.
 * - [MatchReady] — both players are in the contract; tap to launch Unity.
 * - [Resume] — list view of every active match in [MatchStore]. Multi-
 *   match support: the user picks which one to resume into. Single-match
 *   case still passes through here (one row → tap → straight into the
 *   right screen for that match's role).
 *
 * The Unity match phase is rendered by `UnityPlayerGameActivity` in its own
 * Activity, so it doesn't get a screen here — when the user is in a match
 * they're not on [KicksActivity] at all.
 */
sealed class KicksScreen {
    data object Menu : KicksScreen()
    data class Creating(val address: String? = null) : KicksScreen()
    data class Joining(
        val prefilledAddress: String? = null,
        /** True while the joinMatch circuit is in flight. */
        val inFlight: Boolean = false,
    ) : KicksScreen()
    /**
     * Both players are in the contract; the next tap launches Unity. [role]
     * tells the gameplay orchestrator (next-session work) which side to
     * play. [address] is the contract address so the orchestrator can call
     * commit/reveal on it without re-deriving from state.
     */
    data class MatchReady(val address: String, val role: Player) : KicksScreen()
    /**
     * Resume picker. Renders every match in [MatchStore], each row tappable.
     * No additional state in the variant — the screen reads the store
     * directly so a tap-Back → tap-Resume round-trip picks up any new
     * matches that landed in the meantime (e.g. a backup-restore that
     * just repopulated the store).
     */
    data object Resume : KicksScreen()
}

/** Which side of the match this device represents. */
enum class Player { P1, P2 }
