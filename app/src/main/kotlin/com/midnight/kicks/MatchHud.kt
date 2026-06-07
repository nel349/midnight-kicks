package com.midnight.kicks

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Process-wide HUD state for the in-match Compose overlay.
 *
 * The Unity activity ([KicksMatchActivity]) renders a status banner on
 * top of Unity's surface. The banner has to keep showing during the
 * long blockchain waits between commit / reveal / opponent activity —
 * otherwise the user sees "submitted my pick" and a frozen 3D scene,
 * with no idea whether the system is alive or hung.
 *
 * `MatchManager` publishes here every time it advances state or starts /
 * completes a circuit call. The overlay collects the [StateFlow] and
 * re-renders. Process-wide singleton (object), same pattern as
 * [UnityBridge] — the source-of-truth lives outside any one Activity so
 * KicksActivity ↔ KicksMatchActivity transitions don't lose the HUD
 * state mid-match.
 */
object MatchHud {

    /**
     * Coarse classification of what the user should be doing right now,
     * used by the overlay to pick the right icon / accent color / animation.
     */
    enum class Mode {
        /** Nothing to show — banner hidden. */
        IDLE,

        /**
         * User is actively picking on Unity (regulation 10-pick or SD
         * 2-pick). The banner shows context like "Sudden death round
         * 3" so the user knows which round they're picking for. No
         * elapsed timer (the user is the one being waited on, not the
         * other way around).
         */
        PICKING,

        /** A blockchain transaction is in flight from this device. */
        TX_IN_FLIGHT,

        /** Tx submitted; we're waiting for the opponent to act. */
        WAITING_FOR_OPPONENT,

        /** Match has resolved — show the final result. */
        DONE,

        /** Something went wrong — show the error. */
        ERROR,
    }

    /**
     * Snapshot of what the HUD should show. Driven entirely by
     * [MatchManager]; the overlay is a pure view of this.
     *
     * `primary` mirrors [MatchState.label] — the headline the user reads.
     * `secondary` is the per-circuit-stage detail when a tx is in flight
     *   (Proving / Balancing / Submitting) — kept short so the overlay
     *   doesn't grow into the game area.
     * `mode` drives presentation; the overlay maps each mode to a
     *   distinct color + icon + animation.
     * `sessionEpochMs` is a key the overlay's elapsed-time counter
     *   resets on. Bumped whenever the user starts a fresh wait, so the
     *   "00:23 — waiting for opponent" timer restarts when the state
     *   moves to a different opponent wait (e.g. P1Committed →
     *   BothCommitted → P1Revealed all bump it).
     */
    data class HudState(
        val primary: String? = null,
        val secondary: String? = null,
        /**
         * Tx-in-flight progress in 0..1 for the stage bar, or null when there's
         * no determinate progress to show. Published alongside [secondary] from
         * the circuit-call stage stream; reset on every primary transition.
         */
        val progress: Float? = null,
        /**
         * Accent (ARGB) for the in-flight stage UI, so each submission type
         * (deploy / commit / reveal / …) tints its progress bar distinctly.
         * Null falls back to the mode's default accent.
         */
        val accentArgb: Int? = null,
        val mode: Mode = Mode.IDLE,
        val sessionEpochMs: Long = 0L,
        /**
         * True while the indexer is unreachable (StatePoller lost contact).
         * The overlay shows "Reconnecting to the network…" over whatever the
         * current mode is, instead of an indistinguishable "waiting". Clears
         * automatically when the indexer returns.
         */
        val connectionLost: Boolean = false,
        /**
         * The local device's role in the current match (P1 / P2 / null
         * for PvAI). The overlay reads this to render scoreboard
         * labels from the user's own perspective.
         */
        val role: Player? = null,
    )

    /**
     * Full-screen replay snapshot rendered above Unity by
     * [MatchReplayOverlay]. Non-null = overlay showing. Cleared via
     * [dismissReplay] when the user taps Continue, at which point any
     * orchestrator awaiting `MatchHud.replay.first { it == null }`
     * (i.e. "user has seen the replay") wakes up.
     *
     * `rounds` is the same `List<RoundResult>` Unity's cinematic would
     * consume (built via `MatchResult.toRoundResults()`), so the
     * Compose fallback and the eventual Unity cinematic share one data
     * contract.
     *
     * `kind` distinguishes the regulation-end replay (10 rounds) from
     * later SD-round replays (2 entries per round). Both flow through
     * the same overlay; kind picks the framing copy.
     */
    enum class ReplayKind { REGULATION, SUDDEN_DEATH_ROUND }

    data class ReplayShow(
        val rounds: List<RoundResult>,
        val p1Score: Int,
        val p2Score: Int,
        val kind: ReplayKind = ReplayKind.REGULATION,
        val sdRoundNumber: Int? = null,   // populated when kind == SUDDEN_DEATH_ROUND
    )

    data class HudReplay(
        val show: ReplayShow,
        val publishedAtMs: Long,
    )

    /**
     * Drives the in-match direction picker rendered above Unity by
     * [MatchPickerOverlay]. Non-null = picker showing. Published by main when a
     * choice phase opens (replacing the old `UnityBridge.sendChoicePhase` that
     * drew Unity's IMGUI picker); the overlay collects the picks locally and
     * sends them back via the existing `choicesLocked` path, then [dismissPicker]
     * clears it.
     *
     * `roles` is the per-pick role from THIS device's perspective ("shoot" /
     * "keep"); its size IS the number of picks to collect. `title` frames the
     * round ("Regulation" / "Sudden death — round 3").
     */
    data class PickerShow(
        val roles: List<String>,
        val title: String,
    )

    private val _state = MutableStateFlow(HudState())
    val state: StateFlow<HudState> = _state.asStateFlow()

    /**
     * The replay [StateFlow] doubles as the dismissal gate. Consumers
     * that need to wait for "user has seen the replay" can call
     * `MatchHud.replay.first { it == null }` — StateFlow re-emits its
     * current value on subscription, so callers don't race against
     * dismissals that happened before they started awaiting. The
     * orchestrator in [KicksActivity.gatherSdPicksFromUi] uses this
     * exact pattern to gate the SD picker.
     */
    private val _replay = MutableStateFlow<HudReplay?>(null)
    val replay: StateFlow<HudReplay?> = _replay.asStateFlow()

    /** Active direction picker, or null. Read by [MatchPickerOverlay]. */
    private val _picker = MutableStateFlow<PickerShow?>(null)
    val picker: StateFlow<PickerShow?> = _picker.asStateFlow()

    // ── Cross-process relay (Approach A — see docs/PLAN.md) ──────────────
    //
    // Post-split, the publisher (MatchManager) runs in **main** and the
    // overlays render in **:unity** — two processes, two separate instances
    // of this `object`. The relay keeps them mirrored:
    //   - main → :unity:  publishPrimary / publishSecondary / publishReplay /
    //                     reset (MatchManager drives state; overlays render it)
    //   - :unity → main:  dismissReplay (the overlay's Continue tap; wakes the
    //                     orchestrator awaiting `replay.first { it == null }`)
    //
    // Loop-avoidance: locally-originated changes update state AND relay; an
    // [applyRemote] from the other side updates state with relay = false.

    private const val EV = "event"
    private const val EV_PRIMARY = "primary"
    private const val EV_SECONDARY = "secondary"
    private const val EV_REPLAY = "replay"
    private const val EV_DISMISS = "dismissReplay"
    private const val EV_RESET = "reset"
    private const val EV_PICKER = "picker"
    private const val EV_PICKER_DISMISS = "dismissPicker"
    private const val EV_CONNECTION = "connection"

    /**
     * How a local HUD change reaches the other process. Each process sets it:
     *  - main → `{ MatchBridge.publishHud(it) }`
     *  - `:unity` → `{ /* MSG_HUD_FROM_UNITY to main */ }`
     *
     * Null before the bridge is wired (single-process, or pre-bind): no relay,
     * but local state still updates so an in-process overlay keeps working.
     */
    @Volatile
    var relayHook: ((String) -> Unit)? = null

    /**
     * Replace the primary label + mode, leaving secondary in place and
     * rotating the session epoch so the elapsed-time counter restarts.
     *
     * Called on every [MatchState] transition. Mode is derived by the
     * caller (MatchManager) because the mapping from [MatchState] to
     * [Mode] is policy, not data — kept there so this file stays
     * presentation-agnostic.
     */
    fun publishPrimary(label: String, mode: Mode, role: Player? = _state.value.role) {
        // currentTimeMillis() keeps the epoch monotonic across processes
        // (same device clock both sides); relayed so :unity's elapsed counter
        // anchors to when main actually started the wait, not bind time.
        setPrimary(label, mode, role, System.currentTimeMillis(), relay = true)
    }

    /**
     * Update only the per-tx-stage secondary line. Cleared (passed `null`)
     * when the circuit call completes / fails, so the overlay drops the
     * sub-line and falls back to whatever the primary mode dictates.
     */
    fun publishSecondary(text: String?, progress: Float? = null, accentArgb: Int? = null) =
        setSecondary(text, progress, accentArgb, relay = true)

    /**
     * Flag/clear "indexer unreachable". Driven by [MatchManager] off the
     * StatePoller's `connected` flow; the overlay shows "Reconnecting…" while
     * true. Independent of [Mode] — the wait keeps its mode; this just layers
     * the connection state on top.
     */
    fun publishConnectionLost(lost: Boolean) = setConnectionLost(lost, relay = true)

    /**
     * Surface a full-screen replay above Unity. Called by [MatchManager]
     * after both regulation reveals have landed (or after each SD round
     * has both reveals). The overlay renders the rounds row-by-row and
     * waits for the user to tap Continue.
     */
    fun publishReplay(show: ReplayShow) = setReplay(show, System.currentTimeMillis(), relay = true)

    /**
     * User tapped Continue on the replay. Clearing [_replay] is the
     * dismissal signal: any orchestrator step awaiting
     * `MatchHud.replay.first { it == null }` wakes up on the next
     * StateFlow emission. No separate event channel needed — the
     * absence of a replay IS the dismissed state. Post-split the tap
     * happens in `:unity`, so this also relays back to main.
     */
    fun dismissReplay() = clearReplay(relay = true)

    /**
     * Open the direction picker. Called by [KicksActivity] when a choice phase
     * starts (regulation or sudden death) — main publishes, the `:unity` overlay
     * renders + collects the picks.
     */
    fun showPicker(roles: List<String>, title: String) =
        setPicker(PickerShow(roles, title), relay = true)

    /**
     * Close the picker. Fired by the overlay once all picks are locked (it has
     * already sent `choicesLocked` back), and relayed so main clears its copy
     * too — mirror of [dismissReplay].
     */
    fun dismissPicker() = clearPicker(relay = true)

    /**
     * Wipe everything — call when leaving the match screen entirely
     * (e.g. KicksActivity.onDestroy or after the user dismisses the
     * resolved-match dialog) so a stale label doesn't briefly flash on
     * the next match before MatchManager has time to publish.
     */
    fun reset() = doReset(relay = true)

    /**
     * Re-emit the current snapshot through [relayHook]. Called on main when
     * `:unity` (re)binds, so an overlay that came up after main already
     * published isn't blank. Idempotent — reuses the existing epoch, so it
     * doesn't restart the elapsed-time counter.
     */
    fun resendCurrent() {
        val s = _state.value
        if (s.primary != null) {
            setPrimary(s.primary, s.mode, s.role, s.sessionEpochMs, relay = true)
            if (s.secondary != null) setSecondary(s.secondary, s.progress, s.accentArgb, relay = true)
        }
        _replay.value?.let { setReplay(it.show, it.publishedAtMs, relay = true) }
        _picker.value?.let { setPicker(it, relay = true) }
        if (_state.value.connectionLost) setConnectionLost(true, relay = true)
    }

    /**
     * Apply a HUD event relayed from the other process. Updates local state
     * **without** re-relaying (relay = false), so main↔`:unity` mirroring
     * can't loop. Called by each side's bridge inbox handler.
     */
    fun applyRemote(json: String) {
        val o = JSONObject(json)
        when (o.getString(EV)) {
            EV_PRIMARY -> setPrimary(
                label = o.getString("primary"),
                mode = Mode.valueOf(o.getString("mode")),
                role = if (o.isNull("role")) null else Player.valueOf(o.getString("role")),
                epoch = o.getLong("epoch"),
                relay = false,
            )
            EV_SECONDARY -> setSecondary(
                text = if (o.isNull("secondary")) null else o.getString("secondary"),
                progress = if (o.isNull("progress")) null else o.getDouble("progress").toFloat(),
                accentArgb = if (o.isNull("accentArgb")) null else o.getInt("accentArgb"),
                relay = false,
            )
            EV_REPLAY -> setReplay(
                show = showFromJson(o.getJSONObject("show")),
                publishedAtMs = o.getLong("epoch"),
                relay = false,
            )
            EV_DISMISS -> clearReplay(relay = false)
            EV_RESET -> doReset(relay = false)
            EV_PICKER -> setPicker(pickerFromJson(o.getJSONObject("show")), relay = false)
            EV_PICKER_DISMISS -> clearPicker(relay = false)
            EV_CONNECTION -> setConnectionLost(o.getBoolean("lost"), relay = false)
        }
    }

    // ── Internal mutators (the relay flag is the only behavioural diff) ──

    private fun setPrimary(label: String, mode: Mode, role: Player?, epoch: Long, relay: Boolean) {
        _state.value = _state.value.copy(
            primary = label,
            // A state transition implicitly ends whatever tx-in-flight detail
            // was being shown, so drop the sub-line + its progress.
            secondary = null,
            progress = null,
            accentArgb = null,
            mode = mode,
            sessionEpochMs = epoch,
            role = role,
        )
        if (relay) relayHook?.invoke(
            JSONObject().apply {
                put(EV, EV_PRIMARY)
                put("primary", label)
                put("mode", mode.name)
                put("epoch", epoch)
                put("role", role?.name ?: JSONObject.NULL)
            }.toString()
        )
    }

    private fun setSecondary(text: String?, progress: Float?, accentArgb: Int?, relay: Boolean) {
        _state.value = _state.value.copy(secondary = text, progress = progress, accentArgb = accentArgb)
        if (relay) relayHook?.invoke(
            JSONObject().apply {
                put(EV, EV_SECONDARY)
                put("secondary", text ?: JSONObject.NULL)
                put("progress", progress?.toDouble() ?: JSONObject.NULL)
                put("accentArgb", accentArgb ?: JSONObject.NULL)
            }.toString()
        )
    }

    private fun setConnectionLost(lost: Boolean, relay: Boolean) {
        if (_state.value.connectionLost == lost) return
        _state.value = _state.value.copy(connectionLost = lost)
        if (relay) relayHook?.invoke(
            JSONObject().apply {
                put(EV, EV_CONNECTION)
                put("lost", lost)
            }.toString()
        )
    }

    private fun setReplay(show: ReplayShow, publishedAtMs: Long, relay: Boolean) {
        _replay.value = HudReplay(show = show, publishedAtMs = publishedAtMs)
        if (relay) relayHook?.invoke(
            JSONObject().apply {
                put(EV, EV_REPLAY)
                put("epoch", publishedAtMs)
                put("show", showToJson(show))
            }.toString()
        )
    }

    private fun clearReplay(relay: Boolean) {
        _replay.value = null
        if (relay) relayHook?.invoke(JSONObject().apply { put(EV, EV_DISMISS) }.toString())
    }

    private fun setPicker(show: PickerShow, relay: Boolean) {
        _picker.value = show
        if (relay) relayHook?.invoke(
            JSONObject().apply {
                put(EV, EV_PICKER)
                put("show", pickerToJson(show))
            }.toString()
        )
    }

    private fun clearPicker(relay: Boolean) {
        _picker.value = null
        if (relay) relayHook?.invoke(JSONObject().apply { put(EV, EV_PICKER_DISMISS) }.toString())
    }

    private fun doReset(relay: Boolean) {
        _state.value = HudState()
        _replay.value = null
        _picker.value = null
        if (relay) relayHook?.invoke(JSONObject().apply { put(EV, EV_RESET) }.toString())
    }

    // ── ReplayShow (de)serialization for the relay ──

    private fun showToJson(s: ReplayShow): JSONObject = JSONObject().apply {
        put("rounds", JSONArray().apply { s.rounds.forEach { put(roundToJson(it)) } })
        put("p1", s.p1Score)
        put("p2", s.p2Score)
        put("kind", s.kind.name)
        put("sdRound", s.sdRoundNumber ?: JSONObject.NULL)
    }

    private fun showFromJson(o: JSONObject): ReplayShow {
        val arr = o.getJSONArray("rounds")
        val rounds = (0 until arr.length()).map { roundFromJson(arr.getJSONObject(it)) }
        return ReplayShow(
            rounds = rounds,
            p1Score = o.getInt("p1"),
            p2Score = o.getInt("p2"),
            kind = ReplayKind.valueOf(o.getString("kind")),
            sdRoundNumber = if (o.isNull("sdRound")) null else o.getInt("sdRound"),
        )
    }

    private fun roundToJson(r: RoundResult): JSONObject = JSONObject().apply {
        put("round", r.round)
        put("shooter", r.shooter)
        put("shootDir", r.shootDir)
        put("keepDir", r.keepDir)
        put("result", r.result)
    }

    private fun roundFromJson(o: JSONObject): RoundResult = RoundResult(
        round = o.getInt("round"),
        shooter = o.getString("shooter"),
        shootDir = o.getInt("shootDir"),
        keepDir = o.getInt("keepDir"),
        result = o.getString("result"),
    )

    // ── PickerShow (de)serialization for the relay ──

    private fun pickerToJson(s: PickerShow): JSONObject = JSONObject().apply {
        put("roles", JSONArray().apply { s.roles.forEach { put(it) } })
        put("title", s.title)
    }

    private fun pickerFromJson(o: JSONObject): PickerShow {
        val arr = o.getJSONArray("roles")
        return PickerShow(
            roles = (0 until arr.length()).map { arr.getString(it) },
            title = o.getString("title"),
        )
    }
}
