package com.midnight.kicks

/**
 * Top-level navigation state for [KicksActivity]. State-based routing keeps
 * the dep surface small (no Navigation-Compose dep) and matches the existing
 * `mutableStateOf` pattern in this Activity. Phase 4 introduces just the
 * three screens the matchmaking flow needs:
 *
 * - [Menu] — main menu (CREATE / JOIN / dev-only PRACTICE vs AI)
 * - [Creating] — P1 side: contract is being deployed, then the address +
 *   QR are shown for the opponent. `address == null` while deploying.
 * - [Joining] — P2 side: opponent address entry. `prefilledAddress` is
 *   set when reached via `midnight://kicks?match=…` deep link.
 *
 * The Unity match phase is rendered by `UnityPlayerGameActivity` in its own
 * Activity, so it doesn't get a screen here — when the user is in a match
 * they're not on [KicksActivity] at all.
 */
sealed class KicksScreen {
    data object Menu : KicksScreen()
    data class Creating(val address: String? = null) : KicksScreen()
    data class Joining(val prefilledAddress: String? = null) : KicksScreen()
}
