package com.midnight.kicks

import android.content.Context
import android.util.Log
import com.midnight.kuira.core.compact.BalanceProgress
import com.midnight.kuira.core.compact.ContractCallStage
import com.midnight.kuira.core.compact.MidnightContract
import com.midnight.kuira.core.compact.WitnessKind
import com.midnight.kuira.core.compact.WitnessResult
import com.midnight.kuira.core.compact.proving.ProvingKeyManager
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.sdk.MidnightSdk
import com.midnight.kuira.sdk.walletruntime.MidnightSdkProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.math.BigInteger
import java.security.SecureRandom

/**
 * Drives a penalty match through the protocol as a discrete state machine.
 *
 * The state machine — [MatchState] — is the source of truth. Each public
 * suspend method represents ONE on-chain transaction (or one logical step),
 * preconditions on the current state, advances to an in-progress state,
 * does the work, then either advances to the success state or transitions
 * to [MatchState.Failed]. Callers observe [state] (a StateFlow) to render
 * stage-specific UX, snapshot to BlockStore, or trigger the next step.
 *
 * Two ways to run a match:
 *
 * 1. **Step-by-step** — call individual transitions in order. Use this for
 *    real PvP (StatePoller advances P2 steps when on-chain events fire) and
 *    for any UX that wants between-step pauses or cancel/retry surfaces.
 *
 *        deployMatch() → aiJoin() → submitP1Choices(c) → submitP2Choices(c)
 *        → revealP1() → revealP2()
 *
 * 2. **Orchestrated** — call [playAgainstAi]. Hands in P1's choices, AI
 *    auto-generates P2's, all transitions chain. Less control, but the
 *    canonical "play one match against an AI" entry point for demos.
 *
 * For now P2 is an AI on-device; the [submitP2Choices] / [revealP2] split
 * exists so a future PvP code path can swap "AI auto-decides" for "wait
 * for the friend's transaction on chain".
 */
open class MatchManager(
    private val context: Context,
    private val network: MidnightNetwork,
    seed: ByteArray? = null,
    /**
     * Multi-match encrypted store. Survives `kill -9` between commit
     * and reveal so the user's stake doesn't get stranded behind a
     * commitment we no longer have the witnesses for. Replaces the
     * earlier `KicksSessionStore` + `MatchVault` split — one type, one
     * lifecycle, one source of truth. See [MatchStore] KDoc for the
     * threat model + lifetime contract.
     */
    private val store: MatchStore = MatchStore(context),
    /**
     * Shared SDK owner. When non-null (production), MatchManager is a
     * *follower*: [initSdkInternal] takes the SDK the config authority
     * (KicksActivity / the wallet panel) already built via the provider —
     * one SDK, one chain sync for the whole app. When null (standalone /
     * non-panel hosts), MatchManager builds its own SDK from [seed].
     */
    private val sdkProvider: MidnightSdkProvider? = null,
) {
    // Take the seed by value into a local-only field so we can wipe it from
    // both the caller's reference and our own at close() time. Null when a
    // shared [sdkProvider] supplies the SDK (the provider owns the seed).
    private var seed: ByteArray? = seed?.copyOf()

    private val _state = MutableStateFlow<MatchState>(MatchState.Idle)
    /** Observable state for UI / BlockStore / StatePoller. */
    val state: StateFlow<MatchState> = _state.asStateFlow()

    /**
     * Latest known on-chain contract state, or null before deploy / between
     * polls. Driven by [StatePoller] started in [deployMatch]. UI / debug
     * panels can observe; orchestrator does NOT yet read this (next commit
     * replaces the hardcoded settle delays with poll-driven waits).
     */
    private val _contractState = MutableStateFlow<ContractStateSnapshot?>(null)
    val contractState: StateFlow<ContractStateSnapshot?> = _contractState.asStateFlow()

    private var sdk: MidnightSdk? = null

    /** Single accessor that asserts SDK is initialized — avoids `sdk!!` everywhere. */
    private val requireSdk: MidnightSdk
        get() = requireNotNull(sdk) { "SDK not initialized — call initSdk first" }

    /**
     * Scope owning the poll-loop coroutine. SupervisorJob so a poller crash
     * doesn't kill anything else; IO dispatcher so the queryState network
     * round-trips don't block the orchestrator's thread.
     */
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollerJob: Job? = null

    /**
     * Reference count for [awaitContractState] callers. The poller
     * stays alive while any wait is outstanding and tears down only
     * when the last wait finishes — refcounting is TOCTOU-safe against
     * concurrent waits where a presence check would race.
     */
    private val pollerRefCount = AtomicInteger(0)

    /**
     * Single reused SecureRandom — instantiating per call may re-seed from
     * /dev/urandom and burn entropy / CPU on the hot path.
     */
    private val random = SecureRandom()

    /**
     * Address of the match the state machine is currently driving.
     * Set when `deployMatch` / `joinAsP2` lands; cleared on
     * [resetForNewAction]. Determines which `MatchStore` entry the
     * commit / reveal save targets — without this, multi-match
     * couldn't tell which record to update on each circuit call.
     */
    private var currentAddress: String? = null

    // P1 (local human) identity. Fresh per session; populated from
    // [store] when [tryResumeActiveMatch] picks up an existing match.
    // The same key must hash into the deployed commitment for reveal
    // to succeed, so resume MUST rehydrate it before any reveal call.
    private var p1SecretKey: ByteArray = ByteArray(SECRET_KEY_BYTES).also { random.nextBytes(it) }

    // P2 identity. `var` so [rehydrateLocalIdentity] can replace it on
    // a P2-side resume — the joining device must commit / reveal with
    // the exact same key it joined with (hashed into the on-chain
    // commitment), not a freshly random one. Today still used as the
    // AI's key in PvAI fallback; PvP joiners overwrite it via the
    // store on every fresh joinAsP2.
    private var p2SecretKey = ByteArray(SECRET_KEY_BYTES).also { random.nextBytes(it) }

    // V3 regulation picks + nonces — captured at commit, reused at reveal.
    // Each player commits shoots[5] + keeps[5] together; one nonce binds
    // the whole regulation batch.
    private var p1Shoots: IntArray? = null
    private var p1Keeps:  IntArray? = null
    private var p2Shoots: IntArray? = null
    private var p2Keeps:  IntArray? = null
    private var p1RegulationNonce: ByteArray? = null
    private var p2RegulationNonce: ByteArray? = null

    // V3 sudden death picks + nonces. A fresh pair commits per SD round
    // (re-using a nonce across rounds would leak the previous round's
    // picks the moment the next round's commit lands).
    private var p1SdShoot: Int = 0
    private var p1SdKeep:  Int = 0
    private var p2SdShoot: Int = 0
    private var p2SdKeep:  Int = 0
    private var p1SdNonce: ByteArray? = null
    private var p2SdNonce: ByteArray? = null

    // Optional SD pairings captured during the match, for the replay UI.
    private val sdRoundsForReplay = mutableListOf<SdRoundData>()

    /**
     * One-shot notification published by [tryResumeActiveMatch] when
     * the persisted match's chain state is [PHASE_COMPLETE]. The
     * activity reads + clears it on bootstrap to render a friendly
     * "your previous match finished" banner instead of silently
     * deleting the store entry.
     *
     * `null` means no notification pending. The activity should call
     * [consumePriorMatchFinished] (don't read directly) so the
     * one-shot is cleared after delivery.
     */
    private var priorMatchFinished: PriorMatchFinished? = null

    /**
     * Serializes [deployMatch] + [joinAsP2] calls so two concurrent
     * invocations (e.g. a double-tapped CREATE / JOIN button) can't
     * race through [resetForNewAction] and clobber each other's
     * freshly-generated [p1SecretKey] / [p2SecretKey] — a regenerated
     * key on a deploy that lands on chain anyway permanently locks
     * the user out of the match (commit fails with "Not a player").
     *
     * Held for the full duration of deploy/join including the chain
     * round-trip. Inflight callers wait their turn; the second one
     * sees state already advanced past SdkReady and either no-ops
     * (idempotent path) or correctly rejects.
     */
    private val deployJoinMutex = Mutex()

    /**
     * Atomically reads + clears [priorMatchFinished]. One-shot — the
     * caller is responsible for rendering the notification; we don't
     * want stale data showing twice across configuration changes.
     */
    fun consumePriorMatchFinished(): PriorMatchFinished? {
        val pending = priorMatchFinished
        priorMatchFinished = null
        return pending
    }

    /**
     * The local device's role in the current match — set when:
     *  - [deployMatch] succeeds → [Player.P1]
     *  - [joinAsP2] succeeds → [Player.P2]
     *  - [tryResumeActiveMatch] rehydrates → reads from
     *    [MatchStore.Match.role]
     *
     * Used by [setState] to drive role-aware [MatchState.labelFor] and
     * [hudModeFor] so the HUD reads correctly for both sides (P1's
     * "Your picks committed" reads as P2's "Opponent committed — your
     * turn to pick"). Cleared by [resetForNewAction].
     *
     * `null` means PvAI / no match yet — the labels and HUD modes
     * fall back to the P1-perspective default, which is also what
     * the PvAI human (always P1) wants.
     */
    private var localRole: Player? = null

    /** Last completed match's data, used by [KicksActivity] for the replay payload. */
    var lastResult: MatchResult? = null
        private set

    // ── Setup ──────────────────────────────────────────────────────────

    suspend fun initSdk() = withContext(Dispatchers.IO) {
        require(state.value is MatchState.Idle) { "initSdk requires Idle, got ${state.value}" }
        setState(MatchState.InitializingSdk)
        initSdkInternal()

        setState(MatchState.SdkReady)
    }

    /**
     * Test seam — the chain-touching, dependency-heavy half of
     * [initSdk]. Production builds a real [MidnightSdk] (Builder pulls
     * in indexer/wallet/dust subscriptions), installs proving keys.
     * Tests override this to skip the SDK build entirely and just
     * return — the state-machine transitions are what they're
     * locking down, not the SDK init pipeline.
     *
     * `internal open` because (1) the file already exposes a fair bit
     * via `internal` for the in-package test surface, and (2) `open`
     * lets a `TestableMatchManager` subclass override without
     * stand-up of mockk-final-class machinery.
     */
    internal open suspend fun initSdkInternal() {
        val provider = sdkProvider
        if (provider != null) {
            // Follower path: the config authority (KicksActivity) already built
            // the one shared SDK via the provider, so we just take it. The
            // provider owns wallet proving-key readiness; we only need our own
            // contract circuit keys. No seed here — the provider sourced it.
            installContractCircuitKeys()
            sdk = provider.awaitSdk()
        } else {
            // Standalone path: build our own SDK from the supplied seed. Used by
            // non-panel hosts; tests override this method entirely.
            installProvingKeys()
            sdk = MidnightSdk.Builder(context)
                .network(network)
                .seed(requireNotNull(seed) { "seed required when no sdkProvider" })
                .build()
            seed?.fill(0)
            seed = null
        }
        Log.i(TAG, "SDK ready, wallet: ${sdk?.walletAddress}")
        Log.i(TAG, "Wallet keys installed: ${sdk?.provingKeyManager?.hasWalletKeys()}")
    }

    /**
     * If the [vault] holds an active match, rehydrate the in-memory
     * witnesses and route the state machine to whatever phase the
     * on-chain contract is currently in. Called by [KicksActivity]
     * right after [initSdk] so a relaunch lands the user back on the
     * correct screen instead of the create-or-join menu.
     *
     * Returns the resumed address, or null if the vault was empty
     * (= fresh-launch path, no resume needed).
     *
     * **Scope (initial implementation):** create-side resume for the
     * four common regulation kill points (Deployed / P1Committed /
     * BothCommitted / P1Revealed). Join-side resume + SD-round resume
     * follow in a later pass — for those, killing mid-flight still
     * forces a re-deploy.
     *
     * **COMPLETE short-circuit:** if the chain reports the match is
     * already over (phase == [PHASE_COMPLETE]), the store entry is
     * deleted and we return null. Stale resume state from a previous
     * match doesn't follow the user across launches.
     */
    suspend fun tryResumeActiveMatch(): String? = withContext(Dispatchers.IO) {
        // Multi-match: arbitrary first-found would bounce the user
        // into a match they didn't intend. Return null and let the
        // activity surface the Resume picker. Single-match path is
        // unchanged.
        val all = store.loadAll()
        val match = when (all.size) {
            0 -> return@withContext null
            1 -> all.single()
            else -> {
                Log.i(TAG, "tryResumeActiveMatch: store has ${all.size} matches — deferring to Resume UI")
                return@withContext null
            }
        }
        require(state.value is MatchState.SdkReady) {
            "tryResumeActiveMatch must be called from SdkReady — got ${state.value}"
        }
        resumeMatchInternal(match)
    }

    /**
     * Resume a SPECIFIC match by address. Used by the Resume picker
     * after the user explicitly chooses which match to engage with —
     * also drives the state machine forward so the next CHECK STATUS
     * tap doesn't no-op against `state.value = SdkReady`.
     *
     * Returns the address on successful resume (state machine
     * advances), `null` when the match is no longer in the store or
     * already COMPLETE on chain.
     */
    suspend fun resumeSpecificMatch(address: String): String? = withContext(Dispatchers.IO) {
        val match = store.load(address) ?: run {
            Log.w(TAG, "resumeSpecificMatch: no store record for $address")
            return@withContext null
        }
        // Reset any in-flight in-memory state from a different match.
        // This is critical when the user navigates Menu → Resume →
        // pick match B while in-memory still holds match A (e.g. from
        // the auto-resume that fell through earlier). resetForNewAction
        // is a no-op when state is already SdkReady.
        resetForNewAction()
        resumeMatchInternal(match)
    }

    /**
     * Shared per-match resume logic for both [tryResumeActiveMatch]
     * (auto, single-match boot) and [resumeSpecificMatch] (manual,
     * Resume picker). Rehydrates witnesses, reads chain, maps to
     * [MatchState], and sets state.
     */
    private suspend fun resumeMatchInternal(match: MatchStore.Match): String? {
        Log.i(TAG, "Resuming from store — address=${match.address.take(16)}… role=${match.role}")

        // Rehydrate the in-memory state machine from the persisted match.
        // Same key must hash into the deployed commitment for any reveal
        // to succeed — write into the right field based on the saved
        // role so a P2 resume doesn't load its key into the P1 slot
        // (which would silently fail every later reveal: the contract
        // would see a different key than the one in the commitment).
        currentAddress = match.address
        rehydrateLocalIdentity(match)

        val chainSnap = readResumeSnapshot(match.address)
        if (chainSnap == null) {
            Log.w(TAG, "Resume: indexer returned no snapshot — assuming Deployed and letting user retry")
            setState(MatchState.Deployed(match.address))
            return match.address
        }
        if (chainSnap.phase == PHASE_COMPLETE) {
            Log.i(
                TAG,
                "Resume: match COMPLETE on chain (${chainSnap.p1Score}-${chainSnap.p2Score}) — deleting store entry + queueing user notification",
            )
            priorMatchFinished = PriorMatchFinished(
                address = match.address,
                role = match.role,
                p1Score = chainSnap.p1Score,
                p2Score = chainSnap.p2Score,
                winner = chainSnap.winner.copyOf(),
                isDraw = chainSnap.isDraw,
            )
            store.delete(match.address)
            currentAddress = null
            return null
        }

        // Populate regulation picks from chain so [buildMatchResult]
        // can fire if the match resolves later in this session.
        if (chainSnap.p1Revealed || chainSnap.phase >= PHASE_SD_COMMITTING) {
            p1Shoots = chainSnap.p1Shoots.copyOf()
            p1Keeps = chainSnap.p1Keeps.copyOf()
        }
        if (chainSnap.p2Revealed || chainSnap.phase >= PHASE_SD_COMMITTING) {
            p2Shoots = chainSnap.p2Shoots.copyOf()
            p2Keeps = chainSnap.p2Keeps.copyOf()
        }

        // Map the on-chain phase + flags to a MatchState.
        val target = chainPhaseToState(match.address, chainSnap)
            ?: run {
                Log.w(TAG, "Resume: phase=${chainSnap.phase} not supported — staying on SdkReady")
                return null
            }
        setState(target)
        return match.address
    }

    /**
     * Pure mapping from a chain snapshot to a [MatchState] for [address].
     * Returns `null` for unsupported phases (today: anything outside
     * the WAITING / regulation-commit-or-reveal / SD-commit-or-reveal
     * progression — i.e. [PHASE_COMPLETE] is handled separately by
     * callers and any other value means schema drift).
     *
     * Used by [tryResumeActiveMatch] and by [joinAsP2]'s rejoiner
     * path. Centralised so a chain phase the contract supports but
     * the state machine doesn't is one edit, not two.
     *
     * Biases toward the earlier state when commit/reveal flags don't
     * uniquely identify the substep — the UI then lets the user
     * re-submit rather than wait on something already done.
     */
    private fun chainPhaseToState(
        address: String,
        chainSnap: ContractStateSnapshot,
    ): MatchState? = when (chainSnap.phase) {
        PHASE_WAITING -> MatchState.Deployed(address)
        PHASE_COMMITTING -> if (chainSnap.p1Committed) {
            MatchState.P1Committed(address)
        } else {
            MatchState.Joined(address)
        }
        PHASE_REVEALING -> if (chainSnap.p1Revealed) {
            MatchState.P1Revealed(address)
        } else {
            MatchState.BothCommitted(address)
        }
        // SD commit phase — each round resets both flags, so flag
        // pattern alone tells us where in this round we are.
        PHASE_SD_COMMITTING -> when {
            chainSnap.p1Committed && chainSnap.p2Committed ->
                MatchState.BothSdCommitted(address, chainSnap.sdRound)
            chainSnap.p1Committed ->
                MatchState.P1SdCommitted(address, chainSnap.sdRound)
            else ->
                MatchState.SdRoundOpen(address, chainSnap.sdRound)
        }
        // SD reveal phase — flags persist until the second reveal
        // triggers an atomic reset.
        PHASE_SD_REVEALING -> if (chainSnap.p1Revealed) {
            MatchState.P1SdRevealed(address, chainSnap.sdRound)
        } else {
            MatchState.BothSdCommitted(address, chainSnap.sdRound)
        }
        else -> null
    }

    /**
     * Save whatever's in-flight to [store] and reset the in-memory
     * state machine to [MatchState.SdkReady] so the user can start a
     * fresh top-level action (CREATE another match, JOIN a different
     * match). Called from [deployMatch] and [joinAsP2] before their
     * precondition check.
     *
     * The previous match's record stays in [store] — the user can
     * resume it later via the Resume UI. Only the in-memory state
     * machine resets.
     *
     * No-op if already on [MatchState.SdkReady] / [MatchState.Idle].
     */
    private fun resetForNewAction() {
        val current = state.value
        // Already at a clean entry point — nothing to do.
        if (current is MatchState.SdkReady || current is MatchState.Idle) return

        // Drop the in-memory match witnesses + secret key; future
        // deploy/join generates fresh material. The store still holds
        // the prior match for resume.
        Log.i(TAG, "Resetting in-memory state from ${current::class.simpleName} → SdkReady (prior match preserved in store)")
        p1SecretKey = ByteArray(SECRET_KEY_BYTES).also { random.nextBytes(it) }
        p1Shoots = null
        p1Keeps = null
        p1RegulationNonce = null
        p1SdShoot = 0
        p1SdKeep = 0
        p1SdNonce = null
        currentAddress = null
        // Clear the role too — the next deploy / join / resume will
        // set it to the right value. Leaving a stale role would make
        // the HUD render previous-match phrasing during the gap
        // between resetForNewAction and the next setState.
        localRole = null
        // Clear the HUD overlay state too — stale replay / banner from a
        // prior match must not survive into a new one. Without this,
        // the user can briefly see the previous match's "FINAL 3-2"
        // scoreboard flash on top of a brand-new deploy.
        MatchHud.reset()
        setState(MatchState.SdkReady)
    }

    // ── Transition steps (one per circuit / logical action) ─────────────

    /**
     * Clear a [MatchState.Failed] back to [MatchState.SdkReady] so the
     * user can start a fresh top-level action (deploy a different match,
     * join a different match) after a prior failure. No-op if state is
     * not Failed.
     *
     * Symmetry with the UI: [MatchState.Failed] is informational
     * (rendered as a toast / error banner), not blocking. Once the user
     * acknowledges it by initiating a fresh action, the state machine
     * should let them proceed. Auto-fired by [deployMatch] and
     * [joinAsP2]; reveal/commit steps still require the user to land
     * on the right state, which is why this isn't called from inside
     * `transitionFrom`.
     */
    /**
     * Restore the in-memory player identity + witnesses from a
     * persisted [match]. Role-aware: a P1-side resume writes into the
     * `p1*` fields; a P2-side resume writes into the `p2*` fields.
     * Without this branch a cross-role resume would put the wrong key
     * in the wrong slot and every subsequent reveal would fail at the
     * proof step (the contract sees a key that doesn't match its
     * stored commitment).
     */
    private fun rehydrateLocalIdentity(match: MatchStore.Match) {
        // Persist the role so subsequent setState calls publish a
        // role-aware HUD label.
        localRole = match.role
        when (match.role) {
            Player.P1 -> {
                p1SecretKey = match.secretKey.copyOf()
                match.regulation?.let { reg ->
                    p1Shoots = reg.shoots.copyOf()
                    p1Keeps = reg.keeps.copyOf()
                    p1RegulationNonce = reg.nonce.copyOf()
                }
                match.sd?.let { sd ->
                    p1SdShoot = sd.shoot
                    p1SdKeep = sd.keep
                    p1SdNonce = sd.nonce.copyOf()
                }
            }
            Player.P2 -> {
                // p2SecretKey was originally `val` (PvAI assumption that
                // both keys live on one device for life of the session).
                // For PvP resume we need to overwrite the joining
                // device's key with the one that's already on chain.
                // See [p2SecretKey] declaration for the broader naming
                // cleanup that belongs in a later refactor.
                p2SecretKey = match.secretKey.copyOf()
                match.regulation?.let { reg ->
                    p2Shoots = reg.shoots.copyOf()
                    p2Keeps = reg.keeps.copyOf()
                    p2RegulationNonce = reg.nonce.copyOf()
                }
                match.sd?.let { sd ->
                    p2SdShoot = sd.shoot
                    p2SdKeep = sd.keep
                    p2SdNonce = sd.nonce.copyOf()
                }
            }
        }
    }

    /**
     * Helper for the persist-on-circuit-success path: load the current
     * active match from [store], transform via [block], save the result
     * back. Throws if there's no current match — every caller is in
     * `transitionFrom` where the state is post-deploy/post-join, so
     * `currentAddress` is guaranteed non-null. If that ever changes,
     * the IllegalStateException surfaces the lifecycle drift loudly.
     */
    private fun updateCurrentMatch(block: (MatchStore.Match) -> MatchStore.Match) {
        val addr = checkNotNull(currentAddress) {
            "updateCurrentMatch called without a currentAddress — caller invoked outside a match flow"
        }
        val existing = checkNotNull(store.load(addr)) {
            "MatchStore has no record for currentAddress=$addr — was the deploy/join save skipped?"
        }
        store.save(block(existing))
    }

    /**
     * P1 deploys a fresh contract. Transitions [SdkReady] → [Deployed].
     *
     * Mutex-serialised with [joinAsP2] so a double-tap on CREATE / JOIN
     * can't race two concurrent invocations through [resetForNewAction]
     * and produce a key-vs-chain desync (see [deployJoinMutex] KDoc).
     */
    suspend fun deployMatch(): String = deployJoinMutex.withLock {
        resetForNewAction()
        transitionFrom<MatchState.SdkReady, String>(
            inProgress = { MatchState.Deploying },
            onSuccess = { _, address -> MatchState.Deployed(address) },
        ) {
            val address = executeDeploy(p1SecretKey)
            Log.i(TAG, "Match at: $address")
            // Pin local role so setState publishes role-aware HUD text.
            localRole = Player.P1
            // Persist before the indexer-settle delay below so a SIGKILL in
            // that window doesn't strand the user without the local secret
            // key that hashes into the on-chain participant set.
            currentAddress = address
            store.save(
                MatchStore.Match(
                    address = address,
                    role = Player.P1,
                    deadline = System.currentTimeMillis() / 1000 + COMMIT_DEADLINE_DURATION_SECS,
                    secretKey = p1SecretKey.copyOf(),
                ),
            )

            afterDeploySettle()

            // StatePoller is NOT started here. It's only relevant for PvP, and
            // even then only during explicit wait windows (see waitForP2*).
            // PvAI has no real wait windows — both players' transactions are
            // submitted from this device, and `nodeRpcClient.submitAndWaitForFinalization`
            // already guarantees finality before each transition returns.

            address
        }
    }

    /**
     * Test seam — the chain-touching half of [deployMatch]. Production
     * loads verifier keys, builds a [MidnightContract], runs
     * `contract.deploy(...)`, returns the resulting on-chain address.
     * Tests override to return a stub address without standing up the
     * SDK or hitting a chain.
     */
    internal open suspend fun executeDeploy(secretKey: ByteArray): String {
        val verifierKeys = loadVerifierKeys()
        val contract = createContractHandle(secretKey, address = null, verifierKeys = verifierKeys)
        val deploy = contract.deploy { stage -> Log.d(TAG, "deploy: ${stage.javaClass.simpleName}") }
        return deploy.contractAddress
    }

    /**
     * Test seam — the after-deploy chain settle. Production waits for the
     * indexer to ingest the deploy block and refreshes the wallet view
     * so the next callCircuit sees the post-deploy UTXO state. Tests
     * override to no-op (the chain isn't actually moving in test).
     */
    internal open suspend fun afterDeploySettle() {
        delay(INDEXER_SETTLE_MS)
        requireSdk.wallet.refresh()
    }

    /**
     * Test seam — the chain-touching `joinMatch` circuit invocation
     * wrapped in the indexer-readiness retry. Production calls the
     * actual circuit on chain; tests override to either succeed
     * (advances state to Joined) or throw a "WAITING phase" exception
     * (exercises the rejoiner-vs-stranger branch in [joinAsP2]'s
     * catch block).
     */
    internal open suspend fun executeJoinMatch(
        secretKey: ByteArray,
        address: String,
        deadline: BigInteger,
    ) {
        retryUntilIndexerReady(JOIN_RETRY_LIMIT, JOIN_RETRY_DELAY_MS) {
            callCircuit(secretKey, address, "joinMatch", arrayOf(deadline))
        }
    }

    /** P2 (AI today) joins the match. Transitions [Deployed] → [Joined]. */
    suspend fun aiJoin() = transitionFrom<MatchState.Deployed, Unit>(
        inProgress = { MatchState.JoiningAsP2(it.address) },
        onSuccess = { prev, _ -> MatchState.Joined(prev.address) },
    ) { prev ->
        val deadline = BigInteger.valueOf(
            System.currentTimeMillis() / 1000 + COMMIT_DEADLINE_DURATION_SECS
        )
        retryUntilIndexerReady(JOIN_RETRY_LIMIT, JOIN_RETRY_DELAY_MS) {
            callCircuit(p2SecretKey, prev.address, "joinMatch", arrayOf(deadline))
        }
    }

    /**
     * P2 (real opponent) joins an existing deployed match by [address].
     * Transitions [SdkReady] → [JoiningAsP2(address)] → [Joined(address)].
     *
     * The address comes from outside (deep link / QR scan / paste).
     * The retry loop covers the indexer-readiness window — the
     * create-side's deploy has to be ingested before this call
     * succeeds.
     *
     * **Idempotent only when [isResume] is true.** If the contract has
     * already advanced past the WAITING phase (e.g. this device joined
     * earlier, the user backed out before committing choices, and is
     * now resuming), the `joinMatch` circuit asserts and the caller
     * gets a [MatchAlreadyJoinedException]. Pass `isResume = true` to
     * treat that as a no-op and advance the state machine to [Joined]
     * anyway — but only the legitimate rejoiner should set this flag,
     * and the caller (KicksActivity) gates it on `MatchStore` records
     * having a P2 session for the same address.
     *
     * **Why the gating matters — wrong-actor protection:** an
     * unintended person with the deep link who taps JOIN MATCH on a
     * fresh device would also hit the same "not in WAITING phase"
     * assert. Without the gate, idempotent handling would route them
     * through to MatchReady → choice phase, where their commit tx
     * would fail at chain time because their p2SecretKey doesn't
     * match the on-chain P2 pubkey. The contract enforces the
     * security, but the UX feels like "I'm playing" until it isn't.
     * Refusing the resume up front (when no session matches) keeps
     * the wrong actor on JoinMatchScreen with a clear error.
     *
     * Proper cryptographic check (compare on-chain P2 pubkey to ours)
     * is a follow-up; the session-based heuristic catches the common
     * case of fresh-device-with-the-link.
     */
    suspend fun joinAsP2(
        address: String,
        isResume: Boolean = false,
    ) = deployJoinMutex.withLock {
        val current = state.value

        // Idempotent shortcut: the state machine is already past the
        // join for THIS exact address — typically because
        // [tryResumeActiveMatch] (on app launch) routed us straight
        // into the persisted match, and the user then re-tapped Join
        // via the deep link or the JoinMatchScreen. The store record
        // + on-chain state already prove we joined; another chain
        // call would just round-trip a "WAITING phase" error.
        //
        // Failed states fall through so a previous failure can be
        // retried.
        if (isResume && current.address == address && current !is MatchState.Failed) {
            Log.i(TAG, "joinAsP2: state already at ${current::class.simpleName} for $address — idempotent no-op")
            return@withLock
        }

        // Switching to a different match (or a fresh join). Always
        // reset — this covers two cases that the old `if (!isResume)`
        // guard mishandled:
        //   1. Fresh join from SdkReady (reset is a no-op).
        //   2. Resume of THIS match while state is on a DIFFERENT
        //      match (e.g. tryResumeActiveMatch already restored a P1
        //      deploy and the user now joins their P2 match). The
        //      reset drops the unrelated in-memory state; the prior
        //      match is preserved in [store] and can be re-resumed
        //      later via the Resume UI.
        resetForNewAction()
        transitionFrom<MatchState.SdkReady, Unit>(
            inProgress = { MatchState.JoiningAsP2(address) },
            onSuccess = { _, _ -> MatchState.Joined(address) },
        ) {
            // Pre-flight chain check — read the contract phase BEFORE
            // attempting the join tx. Saves a wasted tx + lets us
            // surface a precise reason (e.g. "match is over, P1 3 – 4
            // P2") instead of the generic "match in progress" copy.
            val preflightSnap = try {
                readResumeSnapshot(address)
            } catch (snapEx: Exception) {
                Log.w(TAG, "joinAsP2: preflight snapshot failed (${snapEx.message}) — falling through to chain attempt")
                null
            }
            if (preflightSnap != null) {
                when {
                    preflightSnap.phase == PHASE_COMPLETE -> {
                        // Match is over. Wipe any stale store entry so
                        // future resume attempts don't get confused.
                        store.delete(address)
                        throw MatchAlreadyResolvedException(
                            address = address,
                            p1Score = preflightSnap.p1Score,
                            p2Score = preflightSnap.p2Score,
                            winner = preflightSnap.winner.copyOf(),
                        )
                    }
                    preflightSnap.phase != PHASE_WAITING && !isResume -> {
                        // Match has progressed past WAITING and we
                        // don't claim to be the original P2. Refuse
                        // with the typed exception — the UI renders
                        // a "match in progress" message and stays on
                        // the Join screen.
                        throw MatchAlreadyJoinedException(address)
                    }
                    // PHASE_WAITING: safe to attempt the join below.
                    // Or `isResume=true` past WAITING — fall through to
                    // the existing chain-attempt + catch-and-rejoin
                    // path so the rejoiner logic stays intact.
                }
            }

            val deadlineSecs = System.currentTimeMillis() / 1000 + COMMIT_DEADLINE_DURATION_SECS
            val deadline = BigInteger.valueOf(deadlineSecs)
            try {
                executeJoinMatch(p2SecretKey, address, deadline)
                // Pin local role so setState publishes role-aware HUD text.
                localRole = Player.P2
                // Persist after the chain accepts our join — without
                // this, a kill before the next save loses the session
                // and a relaunch can't tell we're the rightful P2.
                currentAddress = address
                store.save(
                    MatchStore.Match(
                        address = address,
                        role = Player.P2,
                        deadline = deadlineSecs,
                        secretKey = p2SecretKey.copyOf(),
                    ),
                )
            } catch (e: Exception) {
                // V3 contract emits two phrasings depending on which assert
                // tripped: "not in WAITING phase" or "already past the
                // WAITING phase — a P2 has already joined." Match on the
                // shared substring so a future revision doesn't silently
                // re-break this branch. Anchored to "WAITING phase" since
                // no other Kicks circuit references that phrase.
                val isJoinClosedAssert = e.message?.contains("WAITING phase") == true
                if (isJoinClosedAssert) {
                    if (isResume) {
                        // Legitimate rejoiner — MatchStore confirmed
                        // this device holds the P2 session for this
                        // address. Rehydrate witnesses + read the
                        // actual chain phase via [chainPhaseToState] so
                        // the state machine lands on the right step
                        // (not unconditionally Joined, which would ask
                        // for regulation picks on an SD-phase match).
                        val persisted = store.load(address)
                            ?: run {
                                // Caller said isResume but the store no
                                // longer has this address (e.g. user wiped
                                // app data between sessions). Surface as
                                // MatchAlreadyJoined — without the key we
                                // can't actually resume.
                                Log.w(
                                    TAG,
                                    "joinAsP2: isResume=true but store has no record for $address — treating as wrong-actor",
                                )
                                throw MatchAlreadyJoinedException(address)
                            }
                        currentAddress = address
                        rehydrateLocalIdentity(persisted)
                        // Read chain phase to override transitionFrom's
                        // default Joined target. The outer transitionFrom
                        // will still call setState(Joined) on success,
                        // but we'll immediately set the correct target
                        // afterwards via [resumedRejoinerTarget].
                        val chainSnap = try {
                            readResumeSnapshot(address)
                        } catch (snapEx: Exception) {
                            Log.w(TAG, "joinAsP2 rejoiner: chain snapshot failed (${snapEx.message}) — falling back to Joined")
                            null
                        }
                        val mapped = chainSnap?.let { chainPhaseToState(address, it) }
                        // Stash the mapped target — transitionFrom's
                        // onSuccess fires MatchState.Joined first; we
                        // override right after via a corrective setState.
                        resumedRejoinerTarget = mapped
                        Log.i(
                            TAG,
                            "joinAsP2: rejoiner — chain phase=${chainSnap?.phase} → " +
                                "resume target=${mapped?.let { it::class.simpleName } ?: "Joined (fallback)"}",
                        )
                    } else {
                        // Stranger with the deep link — surface a typed
                        // exception so the UI can render "another player
                        // already joined" instead of a generic stack.
                        throw MatchAlreadyJoinedException(address)
                    }
                } else {
                    throw e
                }
            }
        }

        // transitionFrom's onSuccess just fired MatchState.Joined.
        // If the rejoiner path stashed a different chain-derived
        // target (e.g. SdRoundOpen because the chain is already in
        // SD), correct the state to that target now.
        resumedRejoinerTarget?.let { actual ->
            Log.i(TAG, "joinAsP2 rejoiner: overriding state to actual chain target ${actual::class.simpleName}")
            setState(actual)
        }
        resumedRejoinerTarget = null
    }

    /**
     * Stashed by [joinAsP2]'s rejoiner-recovery path so the post-
     * `transitionFrom` block can correct the state to the actual
     * chain phase (rather than the default [MatchState.Joined] that
     * `onSuccess` always fires). One-shot — cleared after the
     * corrective setState.
     */
    private var resumedRejoinerTarget: MatchState? = null

    /**
     * P1 (create-side) waits for the opponent's [joinAsP2] transaction to
     * land on chain. On success transitions [Deployed] → [Joined].
     *
     * **Timeout is non-terminal** — if no opponent has joined within
     * [timeoutMs], the state stays on [Deployed] and a
     * [kotlinx.coroutines.TimeoutCancellationException] is thrown. This
     * lets the CHECK STATUS button in [CreateMatchScreen] poll repeatedly
     * without putting the state machine into [Failed]. Use a long
     * [timeoutMs] (>= [DEFAULT_OPPONENT_WAIT_MS]) for the "block until
     * opponent shows up" semantic, or a short value for a one-shot probe.
     *
     * Not implemented via [transitionFrom] because that helper treats every
     * exception as a fatal transition — wrong shape for repeated polling.
     */
    suspend fun awaitOpponentJoin(
        timeoutMs: Long = DEFAULT_OPPONENT_WAIT_MS,
    ) {
        when (val current = state.value) {
            is MatchState.Deployed -> {
                awaitContractState(timeoutMs) { it.matchJoined }
                setState(MatchState.Joined(current.address))
            }
            // Already past the join — the opponent must have joined
            // (otherwise we'd still be on Deployed). Most common path:
            // [tryResumeActiveMatch] read chain phase=COMMITTING on
            // launch and routed us into Joined / P1Committed / … . The
            // user then taps CHECK STATUS, which reaches here; treat
            // it as an idempotent confirmation, not an error.
            is MatchState.Joined,
            is MatchState.P1Committing, is MatchState.P1Committed,
            is MatchState.P2Committing, is MatchState.BothCommitted,
            is MatchState.P1Revealing, is MatchState.P1Revealed,
            is MatchState.P2Revealing,
            is MatchState.SdRoundOpen,
            is MatchState.P1SdCommitting, is MatchState.P1SdCommitted,
            is MatchState.P2SdCommitting, is MatchState.BothSdCommitted,
            is MatchState.P1SdRevealing, is MatchState.P1SdRevealed,
            is MatchState.P2SdRevealing,
            is MatchState.Resolved -> {
                Log.i(
                    TAG,
                    "awaitOpponentJoin: state already at ${current::class.simpleName} — opponent has joined, no-op",
                )
            }
            // States where there's no match to wait on (or it's
            // unrecoverable without a reset). The CHECK STATUS button
            // shouldn't be reachable from these, but defend the
            // contract anyway.
            is MatchState.Idle,
            is MatchState.InitializingSdk,
            is MatchState.SdkReady -> {
                // No active match — caller (CHECK STATUS button) gets
                // a typed signal so the UI renders "no match" instead
                // of the misleading "still waiting" text.
                throw NoActiveMatchException(
                    "awaitOpponentJoin: no active match — state is ${current::class.simpleName}",
                )
            }
            is MatchState.Deploying,
            is MatchState.JoiningAsP2,
            is MatchState.Failed -> {
                // Match is in-flight (deploy/join in progress) or
                // the prior attempt failed. Both legitimately happen
                // when the user spams CHECK STATUS during a slow
                // first deploy. No-op + log so the activity loops
                // back and tries again on the next tap.
                Log.i(
                    TAG,
                    "awaitOpponentJoin: state ${current::class.simpleName} — match still settling, no-op",
                )
            }
        }
    }

    /** P1 commits their regulation picks. Transitions [Joined] → [P1Committed]. */
    suspend fun submitP1Picks(shoots: IntArray, keeps: IntArray) {
        require(shoots.size == PICKS_PER_ARRAY) { "Need $PICKS_PER_ARRAY shoots" }
        require(keeps.size == PICKS_PER_ARRAY)  { "Need $PICKS_PER_ARRAY keeps" }
        // Diagnostic: every regulation commit prints the bucketed picks
        // so a logcat grep proves what this device actually committed —
        // without this we can only see opaque "score=3-3" results and
        // can't tell whether a tie came from misaligned bucketing,
        // overlapping player choices, or a witness encoding bug. Cheap
        // to log (10 ints), critical to debugging tie patterns.
        Log.i(TAG, "submitP1Picks: shoots=${shoots.toList()} keeps=${keeps.toList()}")
        transitionFrom<MatchState.Joined, Unit>(
            inProgress = { MatchState.P1Committing(it.address) },
            onSuccess = { prev, _ -> MatchState.P1Committed(prev.address) },
        ) { prev ->
            delay(POST_JOIN_SETTLE_MS) // join must finalize before next tx
            requireSdk.wallet.refresh()

            val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
            commitRegulation(p1SecretKey, prev.address, shoots, keeps, nonce)
            p1Shoots = shoots.copyOf()
            p1Keeps  = keeps.copyOf()
            p1RegulationNonce = nonce
            // Persist BEFORE reveal — without these the reveal circuit
            // can never reopen the on-chain commitment and the stake
            // sits until the timeout-claim path fires.
            updateCurrentMatch { it.copy(
                regulation = MatchStore.RegulationWitnesses(
                    shoots = shoots.copyOf(),
                    keeps = keeps.copyOf(),
                    nonce = nonce.copyOf(),
                ),
            ) }
        }
    }

    /** P2 commits their regulation picks. Transitions [P1Committed] → [BothCommitted]. */
    suspend fun submitP2Picks(shoots: IntArray, keeps: IntArray) {
        require(shoots.size == PICKS_PER_ARRAY) { "Need $PICKS_PER_ARRAY shoots" }
        require(keeps.size == PICKS_PER_ARRAY)  { "Need $PICKS_PER_ARRAY keeps" }
        // Diagnostic — see [submitP1Picks] for rationale.
        Log.i(TAG, "submitP2Picks: shoots=${shoots.toList()} keeps=${keeps.toList()}")
        transitionFrom<MatchState.P1Committed, Unit>(
            inProgress = { MatchState.P2Committing(it.address) },
            onSuccess = { prev, _ -> MatchState.BothCommitted(prev.address) },
        ) { prev ->
            delay(INTER_TX_SETTLE_MS)
            requireSdk.wallet.refresh()

            val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
            commitRegulation(p2SecretKey, prev.address, shoots, keeps, nonce)
            p2Shoots = shoots.copyOf()
            p2Keeps  = keeps.copyOf()
            p2RegulationNonce = nonce
        }
    }

    /** P1 reveals their regulation picks. Transitions [BothCommitted] → [P1Revealed]. */
    suspend fun revealP1() = transitionFrom<MatchState.BothCommitted, Unit>(
        inProgress = { MatchState.P1Revealing(it.address) },
        onSuccess = { prev, _ -> MatchState.P1Revealed(prev.address) },
    ) { prev ->
        val shoots = requireNotNull(p1Shoots) { "No P1 shoots captured" }
        val keeps  = requireNotNull(p1Keeps)  { "No P1 keeps captured" }
        val nonce  = requireNotNull(p1RegulationNonce) { "No P1 nonce captured" }
        delay(INTER_TX_SETTLE_MS)
        requireSdk.wallet.refresh()
        revealRegulation(p1SecretKey, prev.address, shoots, keeps, nonce)
    }

    /**
     * P2 reveals. On the second reveal the contract either finalises
     * ([MatchState.Resolved]) or enters sudden death — callers should
     * read the contract state after this transitions and dispatch into
     * the SD round loop if needed.
     *
     * Transitions [P1Revealed] → [Resolved] (regulation decisive) **or**
     * [P1Revealed] → [SdRoundOpen] (regulation drew).
     */
    suspend fun revealP2(): MatchResult? = transitionFrom<MatchState.P1Revealed, MatchResult?>(
        inProgress = { MatchState.P2Revealing(it.address) },
        onSuccess = { prev, result ->
            if (result != null) MatchState.Resolved(result)
            else MatchState.SdRoundOpen(prev.address, round = 1)
        },
    ) { prev ->
        val p2s = requireNotNull(p2Shoots) { "No P2 shoots captured" }
        val p2k = requireNotNull(p2Keeps)  { "No P2 keeps captured" }
        val p2n = requireNotNull(p2RegulationNonce) { "No P2 nonce captured" }
        delay(INTER_TX_SETTLE_MS)
        requireSdk.wallet.refresh()
        revealRegulation(p2SecretKey, prev.address, p2s, p2k, p2n)

        // Wait for the chain to actually reflect P2's reveal before
        // reading the snapshot — an immediate readOnce() can hit a
        // pre-reveal indexer view (p2Revealed=false, zero picks) and
        // make publishReplay render the wrong score.
        // [secondRegulationRevealLanded] is the atomic-reset-aware
        // predicate the P1-side wait uses, covering both decisive
        // and draw paths.
        val snap = awaitContractState(
            DEFAULT_OPPONENT_WAIT_MS,
            ::secondRegulationRevealLanded,
        )
        Log.i(
            TAG,
            "regulation reveal landed (P2-side / PvAI): " +
                "p1Shoots=${snap.p1Shoots.toList()} p1Keeps=${snap.p1Keeps.toList()} " +
                "p2Shoots=${snap.p2Shoots.toList()} p2Keeps=${snap.p2Keeps.toList()} " +
                "score=${snap.p1Score}-${snap.p2Score} phase=${snap.phase} sdRound=${snap.sdRound}",
        )
        // On the draw path the chain resets reveal flags atomically
        // when advancing to SD — but p1Shoots/p2Shoots persist for the
        // replay (contract invariant pinned by test "second-reveal
        // atomicity (app-poll predicate invariants)").
        publishRegulationReplay(snap)
        if (snap.phase == PHASE_COMPLETE) {
            buildMatchResult(prev.address).also { lastResult = it }
        } else {
            null   // SD round 1 is now open; orchestrator drives the SD loop
        }
    }

    // ── Sudden death — single-pairing rounds until decisive ─────────────
    //
    // Each round both players commit a {shoot, keep} pair; second reveal
    // either resolves the match (one player scored, the other missed) or
    // advances to round+1. Local picks are captured at commit; the contract
    // captures the pair for replay once both reveals land. Nonces are fresh
    // per player per round — reusing across rounds leaks past picks once
    // the next round's commitment hash hits chain.

    /** P1 commits this SD round's {shoot, keep}. SdRoundOpen → P1SdCommitted. */
    suspend fun submitP1SdPick(shoot: Int, keep: Int) =
        transitionFrom<MatchState.SdRoundOpen, Unit>(
            inProgress = { MatchState.P1SdCommitting(it.address, it.round) },
            onSuccess  = { prev, _ -> MatchState.P1SdCommitted(prev.address, prev.round) },
        ) { prev ->
            // Diagnostic — log the SD pick + a warning when shoot==keep
            // because that is the input pattern that guarantees "both
            // score or both miss" on every round (SD scoring is
            // symmetric: P1goal = p1Shoot!=p2Keep, P2goal = p2Shoot!=p1Keep,
            // so when each player picks the same direction for both
            // their shoot and keep, the scoring becomes mutually
            // entangled and no round can ever be decisive). Surface
            // this as a Log.w so it's easy to grep when debugging
            // never-ending SD loops.
            Log.i(TAG, "submitP1SdPick round=${prev.round} shoot=$shoot keep=$keep")
            if (shoot == keep) {
                Log.w(
                    TAG,
                    "submitP1SdPick: shoot==keep (=$shoot). If P2 also has " +
                        "shoot==keep this round, scoring collapses to a " +
                        "symmetric equation and SD will never resolve. " +
                        "Pick different shoot/keep directions to break ties.",
                )
            }
            delay(INTER_TX_SETTLE_MS)
            requireSdk.wallet.refresh()
            val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
            commitSuddenDeath(p1SecretKey, prev.address, shoot, keep, nonce)
            p1SdShoot = shoot
            p1SdKeep  = keep
            p1SdNonce = nonce
            // Persist this round's SD witnesses so a kill before reveal
            // doesn't lock the pot behind a sealed commitment.
            updateCurrentMatch { it.copy(
                sd = MatchStore.SdWitnesses(
                    round = prev.round,
                    shoot = shoot,
                    keep = keep,
                    nonce = nonce.copyOf(),
                ),
            ) }
        }

    /** P2 commits this SD round's {shoot, keep}. P1SdCommitted → BothSdCommitted. */
    suspend fun submitP2SdPick(shoot: Int, keep: Int) =
        transitionFrom<MatchState.P1SdCommitted, Unit>(
            inProgress = { MatchState.P2SdCommitting(it.address, it.round) },
            onSuccess  = { prev, _ -> MatchState.BothSdCommitted(prev.address, prev.round) },
        ) { prev ->
            // Diagnostic — mirror of submitP1SdPick. The shoot==keep
            // warning matters here too: if BOTH players pick the same
            // direction for shoot AND keep, the contract's symmetric
            // SD scoring (p1Goal=p1Shoot!=p2Keep, p2Goal=p2Shoot!=p1Keep)
            // collapses to a single boolean that's always true-for-both
            // or false-for-both → continue forever. Same trap whether
            // it bites on the P1 or P2 device.
            Log.i(TAG, "submitP2SdPick round=${prev.round} shoot=$shoot keep=$keep")
            if (shoot == keep) {
                Log.w(
                    TAG,
                    "submitP2SdPick: shoot==keep (=$shoot). If P1 also has " +
                        "shoot==keep this round, scoring collapses to a " +
                        "symmetric equation and SD will never resolve. " +
                        "Pick different shoot/keep directions to break ties.",
                )
            }
            delay(INTER_TX_SETTLE_MS)
            requireSdk.wallet.refresh()
            val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
            commitSuddenDeath(p2SecretKey, prev.address, shoot, keep, nonce)
            p2SdShoot = shoot
            p2SdKeep  = keep
            p2SdNonce = nonce
            // Persist before reveal — a process kill mid-SD loses the
            // nonce otherwise, and the commitment can't be re-opened.
            updateCurrentMatch { it.copy(
                sd = MatchStore.SdWitnesses(
                    round = prev.round,
                    shoot = shoot,
                    keep = keep,
                    nonce = nonce.copyOf(),
                ),
            ) }
        }

    /** P1 reveals SD pick. BothSdCommitted → P1SdRevealed. */
    suspend fun revealP1Sd() = transitionFrom<MatchState.BothSdCommitted, Unit>(
        inProgress = { MatchState.P1SdRevealing(it.address, it.round) },
        onSuccess  = { prev, _ -> MatchState.P1SdRevealed(prev.address, prev.round) },
    ) { prev ->
        val nonce = requireNotNull(p1SdNonce) { "No P1 SD nonce captured" }
        delay(INTER_TX_SETTLE_MS)
        requireSdk.wallet.refresh()
        revealSuddenDeath(p1SecretKey, prev.address, p1SdShoot, p1SdKeep, nonce)
    }

    /**
     * P2 reveals SD pick. The contract scores this pairing and either
     * finalises (transition to [MatchState.Resolved]) or opens another
     * SD round (transition to [MatchState.SdRoundOpen]).
     *
     * Returns the final [MatchResult] when the match resolves; null when
     * SD continues so the caller can loop.
     */
    suspend fun revealP2Sd(): MatchResult? =
        transitionFrom<MatchState.P1SdRevealed, MatchResult?>(
            inProgress = { MatchState.P2SdRevealing(it.address, it.round) },
            onSuccess  = { prev, result ->
                if (result != null) MatchState.Resolved(result)
                else MatchState.SdRoundOpen(prev.address, prev.round + 1)
            },
        ) { prev ->
            val nonce = requireNotNull(p2SdNonce) { "No P2 SD nonce captured" }
            delay(INTER_TX_SETTLE_MS)
            requireSdk.wallet.refresh()
            revealSuddenDeath(p2SecretKey, prev.address, p2SdShoot, p2SdKeep, nonce)

            // Record this pairing for the replay UI.
            val sdData = SdRoundData(
                round = prev.round,
                p1Shoot = p1SdShoot, p1Keep = p1SdKeep,
                p2Shoot = p2SdShoot, p2Keep = p2SdKeep,
            )
            sdRoundsForReplay += sdData

            // Wait for the chain to actually reflect this SD round's
            // reveal before reading the snapshot, otherwise the
            // indexer's pre-reveal state could leak into the replay.
            // [secondSdRevealLanded] handles the atomic-reset path
            // when the round stalemates and sdRound bumps.
            val snap = awaitContractState(DEFAULT_OPPONENT_WAIT_MS) { s ->
                secondSdRevealLanded(s, prev.round)
            }
            // Belt-and-suspenders: a resume that landed in mid-SD
            // never ran [waitForP1Revealed] (which normally captures
            // p1Shoots/p1Keeps on the P2 side), so [buildMatchResult]
            // would throw on PHASE_COMPLETE. The chain persists the
            // regulation arrays through to match end; populate from
            // [snap] now if memory is still missing them.
            if (p1Shoots == null) p1Shoots = snap.p1Shoots.copyOf()
            if (p1Keeps == null) p1Keeps = snap.p1Keeps.copyOf()
            if (p2Shoots == null) p2Shoots = snap.p2Shoots.copyOf()
            if (p2Keeps == null) p2Keeps = snap.p2Keeps.copyOf()
            // Publish the per-round replay BEFORE returning, mirroring
            // the regulation pattern. The activity's `gatherSdPicksFromUi`
            // (or its terminal "show winner" path on decisive SD)
            // awaits dismissal before proceeding.
            publishSdRoundReplay(sdData, snap)
            if (snap.phase == PHASE_COMPLETE) {
                buildMatchResult(prev.address).also { lastResult = it }
            } else {
                null
            }
        }

    // ── PvP wait helpers (observe opponent via chain) ───────────────────
    //
    // In PvP, P2 commits and reveals from their own device — we don't have
    // a local copy of their tx to wait on. We discover their actions by
    // polling the contract state. Helpers below start a [StatePoller] only
    // for the duration of the wait so it doesn't run continuously (battery,
    // indexer load) and never overlaps an orchestrator-driven submission.
    //
    // PvAI does NOT use these — see [playAgainstAi].

    /**
     * Predicate: "the second regulation reveal has landed (either path)."
     *
     * The naive predicate `{ it.p2Revealed }` is unsafe: when regulation
     * draws, the contract sets `p2Revealed = true`, scores, then calls
     * `resetRoundState()` and advances to SD — all inside a single
     * atomic circuit call. The poller's 3-second cadence cannot observe
     * the transient `p2Revealed = true` state, so it would deadlock
     * forever on a drawn match.
     *
     * The fix accepts three equivalent witnesses for "second reveal
     * landed":
     *   - `p2Revealed`           — decisive path, flag persists.
     *   - `phase == COMPLETE`    — decisive path (both paths set this).
     *   - `sdRound >= 1`         — drew into SD; flags reset but
     *                              sdRound increment is observable in
     *                              the same snapshot.
     *
     * Pinned by contract test "second-reveal atomicity (app-poll
     * predicate invariants)".
     */
    internal fun secondRegulationRevealLanded(snap: ContractStateSnapshot): Boolean =
        snap.p2Revealed ||
            snap.phase == PHASE_COMPLETE ||
            snap.sdRound >= 1

    /**
     * Predicate: "this SD round's second reveal has landed (either path)."
     *
     * Same atomic-reset problem as regulation, scoped to one SD round:
     * on stalemate the contract resets `p1Revealed`/`p2Revealed` and
     * bumps `sdRound` in the same call. The poll-safe witness set:
     *   - `p2Revealed && sdRound == callerRound` — decisive within this round.
     *   - `phase == COMPLETE`                    — decisive within this round.
     *   - `sdRound > callerRound`                — stalemate; next round opened.
     *
     * Pinned by contract test "stalemate revealSuddenDeath: p1Revealed
     * AND p2Revealed both reset, sdRound advances atomically".
     */
    internal fun secondSdRevealLanded(snap: ContractStateSnapshot, callerRound: Int): Boolean =
        (snap.p2Revealed && snap.sdRound == callerRound) ||
            snap.phase == PHASE_COMPLETE ||
            snap.sdRound > callerRound

    /**
     * Wait for the on-chain contract state to satisfy [predicate]. Starts the
     * [StatePoller] if not already running (and stops it before returning if
     * this call was the one that started it).
     *
     * Throws [kotlinx.coroutines.TimeoutCancellationException] if [timeoutMs]
     * elapses before a matching snapshot arrives.
     */
    internal suspend fun awaitContractState(
        timeoutMs: Long,
        predicate: (ContractStateSnapshot) -> Boolean,
    ): ContractStateSnapshot {
        val address = state.value.address
            ?: error("awaitContractState called before deploy — no address")

        // Refcount the poller across concurrent awaitContractState
        // callers. A presence check is TOCTOU-racy — two concurrent
        // waits would both start (and the first's finally would
        // cancel the second's poller). Refcount keeps the poller
        // alive for as long as any wait is using it.
        val before = pollerRefCount.getAndIncrement()
        if (before == 0) startStatePoller(address)
        return try {
            withTimeout(timeoutMs) {
                contractState.filterNotNull().first(predicate)
            }
        } finally {
            if (pollerRefCount.decrementAndGet() == 0) {
                pollerJob?.cancel()
                pollerJob = null
                // No waiter left polling — drop any stale "Reconnecting…" so it
                // doesn't linger into the next phase.
                MatchHud.publishConnectionLost(false)
            }
        }
    }

    /**
     * P2 committed on their device — wait for that to land on chain.
     * Transitions [P1Committed] → [BothCommitted].
     */
    suspend fun waitForP2Committed(
        timeoutMs: Long = DEFAULT_OPPONENT_WAIT_MS,
    ) = transitionFrom<MatchState.P1Committed, Unit>(
        inProgress = { it },  // stay on P1Committed visually; the label drives UX
        onSuccess = { prev, _ -> MatchState.BothCommitted(prev.address) },
    ) {
        awaitContractState(timeoutMs) { it.p2Committed }
        Unit
    }

    /**
     * P2-side mirror of [waitForP2Committed]: this device is P2 and is
     * waiting for P1's commit transaction to land on chain. Transitions
     * [Joined] → [P1Committed] when the chain reports `p1Committed`.
     */
    suspend fun waitForP1Committed(
        timeoutMs: Long = DEFAULT_OPPONENT_WAIT_MS,
    ) {
        val current = state.value
        // Resume can land here past P1's commit; skip if chain
        // already advanced.
        if (current.protocolRank >= PhaseRank.P1_COMMITTED) {
            Log.i(TAG, "waitForP1Committed: state already ${current::class.simpleName} — no-op")
            return
        }
        require(current is MatchState.Joined) {
            "waitForP1Committed: expected Joined or later, got ${current::class.simpleName}"
        }
        awaitContractState(timeoutMs) { it.p1Committed }
        setState(MatchState.P1Committed(current.address))
    }

    /**
     * P2-side mirror of [waitForP2Revealed]: this device is P2 and is
     * waiting for P1's reveal transaction. Transitions [BothCommitted] →
     * [P1Revealed] when the chain reports `p1Revealed`, and **captures
     * `p1Shoots`/`p1Keeps` from the chain snapshot** so the subsequent
     * [revealP2] can build a full [MatchResult] without local P1 data
     * (we don't have it on P2's device).
     */
    suspend fun waitForP1Revealed(
        timeoutMs: Long = DEFAULT_OPPONENT_WAIT_MS,
    ) {
        val current = state.value
        // Idempotent: chain already advanced past P1's reveal.
        if (current.protocolRank >= PhaseRank.P1_REVEALED) {
            Log.i(TAG, "waitForP1Revealed: state already ${current::class.simpleName} — no-op")
            return
        }
        require(current is MatchState.BothCommitted) {
            "waitForP1Revealed: expected BothCommitted or later, got ${current::class.simpleName}"
        }
        // On a regulation draw the contract atomically clears
        // p1Revealed and bumps sdRound in the same tx — `p1Revealed`
        // alone never fires. Accept any of the three signals: direct
        // `p1Revealed`, `phase==COMPLETE` (decisive after both reveals),
        // or `sdRound >= 1` (drew → SD opened).
        val snap = awaitContractState(timeoutMs) {
            it.p1Revealed || it.sdRound >= 1 || it.phase == PHASE_COMPLETE
        }
        // Capture P1's revealed regulation picks from chain — P2 doesn't
        // have them locally and needs them for the replay payload. On
        // the draw path the contract has already reset p1Revealed
        // but p1Shoots/p1Keeps persist past the reset.
        p1Shoots = snap.p1Shoots.copyOf()
        p1Keeps  = snap.p1Keeps.copyOf()
        setState(MatchState.P1Revealed(current.address))
    }

    /**
     * P2 revealed on their device — wait for that to land on chain. The
     * contract auto-resolves on the second reveal, so this also produces
     * the final [MatchResult] by reading p2's choices from the snapshot
     * (we don't have them locally in PvP).
     *
     * Transitions [P1Revealed] → [Resolved].
     */
    // ── SD wait helpers — same shape as regulation, parameterised on round ─

    /** P2-side: wait for P1's SD commit to land. SdRoundOpen → P1SdCommitted. */
    suspend fun waitForP1SdCommitted(timeoutMs: Long = DEFAULT_OPPONENT_WAIT_MS) {
        val current = state.value
        val round = current.sdRound
            ?: throw IllegalArgumentException(
                "waitForP1SdCommitted: not in SD, state=${current::class.simpleName}",
            )
        // Idempotent: chain already advanced past P1's SD commit for
        // this round.
        if (current.protocolRank >= PhaseRank.sdP1CommittedAt(round)) {
            Log.i(TAG, "waitForP1SdCommitted: state already ${current::class.simpleName} — no-op")
            return
        }
        require(current is MatchState.SdRoundOpen) {
            "waitForP1SdCommitted: expected SdRoundOpen or later, got ${current::class.simpleName}"
        }
        awaitContractState(timeoutMs) { it.p1Committed && it.sdRound == round }
        setState(MatchState.P1SdCommitted(current.address, round))
    }

    /** P1-side: wait for P2's SD commit to land. P1SdCommitted → BothSdCommitted. */
    suspend fun waitForP2SdCommitted(timeoutMs: Long = DEFAULT_OPPONENT_WAIT_MS) =
        transitionFrom<MatchState.P1SdCommitted, Unit>(
            inProgress = { it },
            onSuccess  = { prev, _ -> MatchState.BothSdCommitted(prev.address, prev.round) },
        ) { prev ->
            awaitContractState(timeoutMs) { it.p2Committed && it.sdRound == prev.round }
            Unit
        }

    /**
     * P2-side: wait for P1's SD reveal. Captures P1's shoot+keep from
     * the chain snapshot so this device can build the replay.
     * BothSdCommitted → P1SdRevealed.
     */
    suspend fun waitForP1SdRevealed(timeoutMs: Long = DEFAULT_OPPONENT_WAIT_MS) {
        val current = state.value
        val round = current.sdRound
            ?: throw IllegalArgumentException(
                "waitForP1SdRevealed: not in SD, state=${current::class.simpleName}",
            )
        // Idempotent: chain already advanced past P1's SD reveal for
        // this round.
        if (current.protocolRank >= PhaseRank.sdP1RevealedAt(round)) {
            Log.i(TAG, "waitForP1SdRevealed: state already ${current::class.simpleName} — no-op")
            return
        }
        require(current is MatchState.BothSdCommitted) {
            "waitForP1SdRevealed: expected BothSdCommitted or later, got ${current::class.simpleName}"
        }
        val snap = awaitContractState(timeoutMs) {
            it.p1Revealed && it.sdRound == round
        }
        p1SdShoot = snap.p1SdShoot
        p1SdKeep  = snap.p1SdKeep
        setState(MatchState.P1SdRevealed(current.address, round))
    }

    /**
     * P1-side: wait for P2's SD reveal. Contract auto-resolves or opens
     * the next SD round. P1SdRevealed → (Resolved | SdRoundOpen(r+1)).
     */
    suspend fun waitForP2SdRevealed(
        timeoutMs: Long = DEFAULT_OPPONENT_WAIT_MS,
    ): MatchResult? = transitionFrom<MatchState.P1SdRevealed, MatchResult?>(
        inProgress = { it },
        onSuccess = { prev, result ->
            if (result != null) MatchState.Resolved(result)
            else MatchState.SdRoundOpen(prev.address, prev.round + 1)
        },
    ) { prev ->
        val snap = awaitContractState(timeoutMs) {
            secondSdRevealLanded(it, callerRound = prev.round)
        }
        // Capture P2's SD pair for the replay payload. On the stalemate
        // path the contract has just cleared p1Revealed/p2Revealed and
        // bumped sdRound past prev.round, but p2SdShoot/p2SdKeep persist
        // on chain (resetRoundState doesn't touch them). Same guarantee
        // covered by contract test "stalemate revealSuddenDeath: …".
        p2SdShoot = snap.p2SdShoot
        p2SdKeep  = snap.p2SdKeep
        // Defensive populate of regulation arrays from chain so
        // [buildMatchResult] can fire from a mid-SD resume (where
        // neither [waitForP1Revealed] nor [waitForP2Revealed] ran in
        // this session).
        if (p1Shoots == null) p1Shoots = snap.p1Shoots.copyOf()
        if (p1Keeps == null) p1Keeps = snap.p1Keeps.copyOf()
        if (p2Shoots == null) p2Shoots = snap.p2Shoots.copyOf()
        if (p2Keeps == null) p2Keeps = snap.p2Keeps.copyOf()
        val sdData = SdRoundData(
            round = prev.round,
            p1Shoot = p1SdShoot, p1Keep = p1SdKeep,
            p2Shoot = p2SdShoot, p2Keep = p2SdKeep,
        )
        sdRoundsForReplay += sdData
        // Publish per-SD-round replay so the user sees the pairing
        // resolve before either the next SD picker or the winner UI.
        publishSdRoundReplay(sdData, snap)

        if (snap.phase == PHASE_COMPLETE) {
            buildMatchResult(prev.address).also { lastResult = it }
        } else {
            null  // Stalemate — orchestrator opens the next SD round.
        }
    }

    suspend fun waitForP2Revealed(
        timeoutMs: Long = DEFAULT_OPPONENT_WAIT_MS,
    ): MatchResult? = transitionFrom<MatchState.P1Revealed, MatchResult?>(
        inProgress = { it },
        onSuccess = { prev, result ->
            if (result != null) MatchState.Resolved(result)
            else MatchState.SdRoundOpen(prev.address, round = 1)
        },
    ) { prev ->
        val snap = awaitContractState(timeoutMs, ::secondRegulationRevealLanded)
        // Capture P2's regulation picks for the replay payload. On the
        // draw path, p1Revealed/p2Revealed are observably false in this
        // snapshot (contract reset them atomically when entering SD),
        // but p2Shoots/p2Keeps persist on chain for the replay — see
        // contract test "second-reveal atomicity (app-poll predicate
        // invariants)".
        p2Shoots = snap.p2Shoots.copyOf()
        p2Keeps  = snap.p2Keeps.copyOf()
        // Diagnostic — log the FULL chain-stored regulation outcome so
        // we can prove the contract has the picks we sent and rule out
        // a "always tie" bucketing bug. Pairs with the
        // submit{P1,P2}Picks logging that printed the values we
        // committed.
        Log.i(
            TAG,
            "regulation reveal landed (P1-side): " +
                "p1Shoots=${snap.p1Shoots.toList()} p1Keeps=${snap.p1Keeps.toList()} " +
                "p2Shoots=${snap.p2Shoots.toList()} p2Keeps=${snap.p2Keeps.toList()} " +
                "score=${snap.p1Score}-${snap.p2Score}",
        )
        // Publish the regulation replay BEFORE returning, so the HUD
        // overlay can render its row-by-row scoreboard above Unity
        // before the orchestrator advances into either SD or Resolved.
        // Both code paths (decisive → Resolved, drew → SdRoundOpen) get
        // the replay; the activity decides what beat comes AFTER the
        // user dismisses it (winner announce vs SD round 1).
        publishRegulationReplay(snap)
        if (snap.phase == PHASE_COMPLETE) {
            buildMatchResult(prev.address).also { lastResult = it }
        } else {
            null  // Drew into SD — orchestrator drives the SD loop next.
        }
    }

    // ── Orchestrators ───────────────────────────────────────────────────

    /**
     * Orchestrator: runs a full PvAI match end-to-end given P1's five
     * choices. P2's choices are generated locally. Real PvP doesn't use
     * this method — it calls the individual transitions as the
     * [StatePoller] reports opponent activity.
     *
     * Callers observe [state] for UX. No progress callback — the state
     * flow is the only progress surface. This is the canonical pattern
     * for a Kuira dApp.
     */
    suspend fun playAgainstAi(
        playerShoots: IntArray,
        playerKeeps: IntArray,
        getSdPicks: suspend (round: Int) -> Pair<Int, Int> = ::randomSdPair,
    ): MatchResult {
        val aiShoots = generateAiPicks()
        val aiKeeps  = generateAiPicks()
        Log.i(TAG, "AI shoots: ${aiShoots.map(::dirLabel)}")
        Log.i(TAG, "AI keeps:  ${aiKeeps.map(::dirLabel)}")

        if (state.value is MatchState.Idle) initSdk()
        deployMatch()
        aiJoin()
        submitP1Picks(playerShoots, playerKeeps)
        submitP2Picks(aiShoots, aiKeeps)
        revealP1()
        var result: MatchResult? = revealP2()
        // Sudden-death loop — repeats until decisive. The AI uses fresh
        // random L/C/R picks each round; the human's picks come from
        // [getSdPicks], which the caller wires to its choice UI
        // (KicksActivity sends a Unity SD `choicePhase` and suspends on
        // the returned `choicesLocked`).
        while (result == null) {
            val round = currentSdRoundOrError()
            val (pShoot, pKeep) = getSdPicks(round)
            val aiShoot = random.nextInt(DIRECTION_COUNT)
            val aiKeep  = random.nextInt(DIRECTION_COUNT)
            submitP1SdPick(pShoot, pKeep)
            submitP2SdPick(aiShoot, aiKeep)
            revealP1Sd()
            result = revealP2Sd()
        }
        return result
    }

    /**
     * P1 (create-side) gameplay orchestrator. Precondition: match has
     * reached [Joined] (both players are in the contract). Submits this
     * device's commit, waits for the opponent's commit, reveals, and
     * waits for the opponent's reveal — at which point the contract
     * auto-resolves and we build the final [MatchResult] from the chain
     * snapshot's p2 choices.
     *
     * Each step transitions a single state in the FSM, and on
     * cancellation / timeout the state machine reflects the last reached
     * step so the user can resume cleanly (Phase 4 follow-up: encrypted
     * key persistence so resume works across process death too).
     */
    suspend fun playAsP1(
        shoots: IntArray,
        keeps: IntArray,
        getSdPicks: suspend (round: Int) -> Pair<Int, Int> = ::randomSdPair,
    ): MatchResult {
        // State-aware step walk — works whether the caller is starting
        // fresh from [MatchState.Joined] OR has resumed into a later
        // state (chain already shows P1's commit / reveal). Each `if`
        // re-reads `state.value` so we naturally fall through to
        // later steps as we advance.
        unwrapFailedState("playAsP1")
        if (state.value is MatchState.Joined) submitP1Picks(shoots, keeps)
        if (state.value is MatchState.P1Committed) waitForP2Committed()
        if (state.value is MatchState.BothCommitted) revealP1()
        var result: MatchResult? =
            if (state.value is MatchState.P1Revealed) waitForP2Revealed() else null

        // SD loop — picks come from [getSdPicks] (UI for real players,
        // random by default for tests / smoke runs).
        while (result == null) {
            val round = currentSdRoundOrError()
            if (state.value is MatchState.SdRoundOpen) {
                val (pShoot, pKeep) = getSdPicks(round)
                submitP1SdPick(pShoot, pKeep)
            }
            if (state.value is MatchState.P1SdCommitted) waitForP2SdCommitted()
            if (state.value is MatchState.BothSdCommitted) revealP1Sd()
            if (state.value is MatchState.P1SdRevealed) {
                result = waitForP2SdRevealed()
            } else {
                throw IllegalStateException(
                    "playAsP1 SD loop stalled at ${state.value::class.simpleName} for round $round",
                )
            }
        }
        return result
    }

    /**
     * P2 (join-side) gameplay orchestrator. Waits for P1 to commit,
     * submits our commit, waits for P1 to reveal (capturing P1's
     * choices from the chain snapshot), then reveals our own. The
     * contract auto-resolves on the second reveal and [revealP2]
     * returns the [MatchResult].
     */
    suspend fun playAsP2(
        shoots: IntArray,
        keeps: IntArray,
        getSdPicks: suspend (round: Int) -> Pair<Int, Int> = ::randomSdPair,
    ): MatchResult {
        // State-aware step walk — see [playAsP1] for rationale.
        // Handles both fresh joins (state == Joined) and resumed
        // mid-match flows (state == P1Committed and beyond).
        unwrapFailedState("playAsP2")
        if (state.value is MatchState.Joined) waitForP1Committed()
        if (state.value is MatchState.P1Committed) submitP2Picks(shoots, keeps)
        if (state.value is MatchState.BothCommitted) waitForP1Revealed()
        var result: MatchResult? =
            if (state.value is MatchState.P1Revealed) revealP2() else null

        // SD loop — pre-launch the picker so it's visible while we
        // wait on P1's SD commit. The contract doesn't care about
        // commit order (only that both land before either reveals),
        // so we collect this device's pick concurrently. A serial
        // wait-then-prompt would leave the user staring at an empty
        // field until P1's tx lands.
        coroutineScope {
            while (result == null) {
                val round = currentSdRoundOrError()
                val pickedAsync = if (state.value is MatchState.SdRoundOpen) {
                    async { getSdPicks(round) }
                } else null
                if (state.value is MatchState.SdRoundOpen) waitForP1SdCommitted()
                if (state.value is MatchState.P1SdCommitted) {
                    val (pShoot, pKeep) = pickedAsync?.await() ?: getSdPicks(round)
                    submitP2SdPick(pShoot, pKeep)
                } else {
                    pickedAsync?.cancel()
                }
                if (state.value is MatchState.BothSdCommitted) waitForP1SdRevealed()
                if (state.value is MatchState.P1SdRevealed) {
                    result = revealP2Sd()
                } else {
                    throw IllegalStateException(
                        "playAsP2 SD loop stalled at ${state.value::class.simpleName} for round $round",
                    )
                }
            }
        }
        return result!!
    }

    /**
     * Resume the P1-side orchestrator from whatever state the resume
     * landed in. Used when the user re-enters a match that's already
     * past regulation-commit time — picks are on chain, witnesses are
     * rehydrated from [MatchStore] (see [rehydrateLocalIdentity]), so
     * there's nothing for Unity to ask for in the regulation phase.
     *
     * Each step is gated on the current [state] so this is safe to call
     * from anywhere between [MatchState.P1Committed] and the final
     * [MatchState.Resolved]. Sudden-death rounds delegate to
     * [getSdPicks] (the same callback the live orchestrator uses) when
     * a fresh pick is needed; if the saved state is mid-SD-after-pick
     * (e.g. [MatchState.P1SdCommitted]), the SD pick is already on
     * chain and we skip straight to the wait/reveal steps.
     *
     * @throws IllegalArgumentException if state is [MatchState.Joined]
     *   (no commit yet — caller must use [playAsP1] with picks) or any
     *   state without an address.
     */
    suspend fun resumePlayAsP1(
        getSdPicks: suspend (round: Int) -> Pair<Int, Int> = ::randomSdPair,
    ): MatchResult {
        // Failed-state recovery — most common cause: prior resume
        // timed out on `waitForP2Committed` because the opponent
        // hadn't committed yet. Unwrap to the previous state and
        // try again from there (the orchestrator's wait/reveal steps
        // are themselves idempotent — they'll either advance or hit
        // a new timeout).
        unwrapFailedState("resumePlayAsP1")
        // Soft preconditions: throw typed exceptions instead of
        // hard `require`. This lets the activity branch the user to
        // the right entry point (e.g. `playAsP1` when picks are
        // still needed) without faulting on a sealed-state mismatch.
        val current = state.value
        if (current.address == null) {
            throw NoActiveMatchException("resumePlayAsP1: no active match — state is ${current::class.simpleName}")
        }
        if (current.protocolRank < PhaseRank.P1_COMMITTED) {
            throw NeedFreshPicksException(
                "resumePlayAsP1: state==${current::class.simpleName} is pre-commit; call playAsP1(shoots, keeps) with fresh picks",
            )
        }
        Log.i(TAG, "resumePlayAsP1: starting from ${current::class.simpleName}")

        // Regulation phase — skip whatever's already done. Each `if`
        // tests the CURRENT state.value (re-read after each step) so
        // we naturally fall through to later steps as we go.
        if (state.value is MatchState.P1Committed) waitForP2Committed()
        if (state.value is MatchState.BothCommitted) revealP1()
        var result: MatchResult? =
            if (state.value is MatchState.P1Revealed) waitForP2Revealed() else null

        // SD loop — same shape as the regulation block, repeated per
        // round. We re-read state.value each iteration because each SD
        // round can re-enter at a different step.
        while (result == null) {
            val round = currentSdRoundOrError()
            if (state.value is MatchState.SdRoundOpen) {
                val (pShoot, pKeep) = getSdPicks(round)
                submitP1SdPick(pShoot, pKeep)
            }
            if (state.value is MatchState.P1SdCommitted) waitForP2SdCommitted()
            if (state.value is MatchState.BothSdCommitted) revealP1Sd()
            if (state.value is MatchState.P1SdRevealed) {
                result = waitForP2SdRevealed()
            } else {
                throw IllegalStateException(
                    "resumePlayAsP1 SD loop stalled at ${state.value::class.simpleName} for round $round",
                )
            }
        }
        return result
    }

    /**
     * Resume the P2-side orchestrator from a post-commit state. Mirror
     * of [resumePlayAsP1]; same constraints — except P2 commits LATER
     * in the protocol, so the no-go states are wider: anything before
     * [MatchState.BothCommitted] means P2 hasn't committed yet and the
     * caller must use [playAsP2] with fresh picks from Unity.
     *
     * @throws IllegalArgumentException if state is at or before
     *   [MatchState.P1Committed] (P2's commit still pending — needs
     *   fresh picks) or has no address.
     */
    suspend fun resumePlayAsP2(
        getSdPicks: suspend (round: Int) -> Pair<Int, Int> = ::randomSdPair,
    ): MatchResult {
        unwrapFailedState("resumePlayAsP2")
        val current = state.value
        if (current.address == null) {
            throw NoActiveMatchException("resumePlayAsP2: no active match — state is ${current::class.simpleName}")
        }
        // Soft precondition: P2 needs picks if chain shows their
        // commit hasn't landed yet. Typed exception lets the activity
        // route to `launchUnityChoicePhase` cleanly.
        if (current.protocolRank < PhaseRank.BOTH_COMMITTED) {
            throw NeedFreshPicksException(
                "resumePlayAsP2: state==${current::class.simpleName} is pre-P2-commit; call playAsP2(shoots, keeps) with fresh picks",
            )
        }
        Log.i(TAG, "resumePlayAsP2: starting from ${current::class.simpleName}")

        // Regulation phase.
        if (state.value is MatchState.BothCommitted) waitForP1Revealed()
        var result: MatchResult? =
            if (state.value is MatchState.P1Revealed) revealP2() else null

        // SD loop — pre-launch P2's picker concurrently with the
        // wait-for-P1-commit. See [playAsP2]'s loop for rationale.
        coroutineScope {
            while (result == null) {
                val round = currentSdRoundOrError()
                val pickedAsync = if (state.value is MatchState.SdRoundOpen) {
                    async { getSdPicks(round) }
                } else null
                if (state.value is MatchState.SdRoundOpen) waitForP1SdCommitted()
                if (state.value is MatchState.P1SdCommitted) {
                    val (pShoot, pKeep) = pickedAsync?.await() ?: getSdPicks(round)
                    submitP2SdPick(pShoot, pKeep)
                } else {
                    pickedAsync?.cancel()
                }
                if (state.value is MatchState.BothSdCommitted) waitForP1SdRevealed()
                if (state.value is MatchState.P1SdRevealed) {
                    result = revealP2Sd()
                } else {
                    throw IllegalStateException(
                        "resumePlayAsP2 SD loop stalled at ${state.value::class.simpleName} for round $round",
                    )
                }
            }
        }
        return result!!
    }

    /**
     * Assemble a [MatchResult] from the captured per-player picks and any
     * SD pairings collected during the match. Callable as soon as the
     * contract reports [PHASE_COMPLETE].
     */
    private fun buildMatchResult(address: String): MatchResult = MatchResult(
        p1Shoots = requireNotNull(p1Shoots) { "p1Shoots not captured" }.copyOf(),
        p1Keeps  = requireNotNull(p1Keeps)  { "p1Keeps not captured" }.copyOf(),
        p2Shoots = requireNotNull(p2Shoots) { "p2Shoots not captured" }.copyOf(),
        p2Keeps  = requireNotNull(p2Keeps)  { "p2Keeps not captured" }.copyOf(),
        sdRounds = sdRoundsForReplay.toList(),
        contractAddress = address,
    )

    fun close() {
        managerScope.cancel()  // stops StatePoller and any in-flight observers
        pollerJob = null
        // Only close the SDK if we built it (standalone). When it came from the
        // shared MidnightSdkProvider, the provider owns its lifecycle — closing
        // it here would tear down the wallet panel's SDK out from under it.
        if (sdkProvider == null) sdk?.close()
        sdk = null
        p1SecretKey.fill(0)
        p2SecretKey.fill(0)
        p1RegulationNonce?.fill(0)
        p2RegulationNonce?.fill(0)
        p1SdNonce?.fill(0)
        p2SdNonce?.fill(0)
        seed?.fill(0)
        seed = null
    }

    // ── Internal helpers ────────────────────────────────────────────────

    /** Generate one AI picks array (shoots or keeps) for regulation. */
    private fun generateAiPicks(): IntArray =
        IntArray(PICKS_PER_ARRAY) { random.nextInt(DIRECTION_COUNT) }

    /** Default getSdPicks impl — uniform random {shoot, keep} pair. */
    private fun randomSdPair(round: Int): Pair<Int, Int> {
        val s = random.nextInt(DIRECTION_COUNT)
        val k = random.nextInt(DIRECTION_COUNT)
        Log.d(TAG, "randomSdPair(round=$round) → shoot=$s keep=$k")
        return s to k
    }

    /**
     * Read the current SD round number, from whatever SD-phase state the
     * machine is in. Called at the top of every SD loop iteration.
     *
     * The LIVE orchestrator always enters a round at [MatchState.SdRoundOpen],
     * but RESUME can re-enter mid-round — `BothSdCommitted` (both picks landed,
     * nobody has revealed yet), `P1SdRevealed`, etc. — and those carry the same
     * round number. So this reads it via [MatchState.sdRound] (defined over the
     * whole SD phase) rather than insisting on SdRoundOpen, which used to throw
     * and dead-lock both players after a relaunch at `BothSdCommitted`. It still
     * errors for genuinely non-SD states (regulation / resolved), where there is
     * no round to read.
     */
    private fun currentSdRoundOrError(): Int {
        val s = state.value
        return s.sdRound
            ?: error("currentSdRoundOrError: state ${s::class.simpleName} carries no SD round")
    }

    private fun dirLabel(d: Int): String = when (d) { 0 -> "L"; 1 -> "C"; 2 -> "R"; else -> "?" }

    /**
     * Run [block] up to [attempts] times, swallowing exceptions whose
     * message mentions "not found" (the indexer hasn't seen the deploy
     * yet). Any other exception, or running out of attempts, propagates.
     *
     * Lives here because the "deploy → indexer eventual consistency"
     * pattern is universal across Kuira dApps and the SDK doesn't yet
     * provide a built-in retry policy (see PLAN.md wishlist #3).
     */
    private suspend fun retryUntilIndexerReady(
        attempts: Int,
        delayMs: Long,
        block: suspend () -> Unit,
    ) {
        repeat(attempts) { i ->
            delay(delayMs)
            try {
                block()
                return
            } catch (e: Exception) {
                val notFound = e.message?.contains("not found") == true
                val canRetry = notFound && i < attempts - 1
                if (!canRetry) throw e
                Log.w(TAG, "Indexer not ready (attempt ${i + 1}/$attempts), retrying")
            }
        }
        error("Failed after $attempts attempts")
    }

    /**
     * Canonical state-machine transition. Asserts current state matches
     * [P], publishes [inProgress], runs [block], then publishes
     * [onSuccess] (or Failed if block throws). All state assignments in
     * this class route through here.
     */
    private suspend inline fun <reified P : MatchState, T> transitionFrom(
        crossinline inProgress: (P) -> MatchState,
        crossinline onSuccess: (P, T) -> MatchState,
        crossinline block: suspend (P) -> T,
    ): T {
        val prev = state.value
        require(prev is P) {
            "expected ${P::class.simpleName}, got ${prev::class.simpleName}"
        }
        setState(inProgress(prev))
        return try {
            val result = block(prev)
            setState(onSuccess(prev, result))
            result
        } catch (e: Exception) {
            setState(MatchState.Failed(prev, e))
            throw e
        }
    }

    /**
     * Centralised state setter — logs the transition (essential for
     * debugging "is the state machine even running?") and updates the flow.
     *
     * Also deletes the resolved match from [store] on terminal-success
     * transitions so it stops showing up in the Resume UI. The store
     * still carries any *other* in-flight matches the user may have.
     * [MatchState.Failed] deliberately does NOT delete — witnesses
     * stay valid and the user may want to retry whatever step blew up
     * (network blip on a reveal, etc.).
     */
    /**
     * If the state machine is parked on [MatchState.Failed], reset it
     * to the [MatchState.Failed.previous] value so the orchestrator
     * can advance from where it left off. Common case: a prior
     * [resumePlayAsP1] / [resumePlayAsP2] hit the 5-minute opponent
     * timeout on [waitForP2Committed], state went Failed; the user
     * taps CONTINUE again, the orchestrator's wait calls would
     * precondition-fail because they `require()` their named
     * predecessor state, not Failed.
     *
     * Logs which state we're unwrapping back to so logcat shows the
     * retry was deliberate, not a silent re-run.
     */
    private fun unwrapFailedState(caller: String) {
        val current = state.value
        if (current is MatchState.Failed) {
            Log.i(
                TAG,
                "$caller: unwrapping Failed → ${current.previous::class.simpleName} (prior error: ${current.error.message})",
            )
            setState(current.previous)
        }
    }

    private fun setState(newState: MatchState) {
        val prev = _state.value
        _state.value = newState
        // Log uses the role-aware label so logcat reads as what the
        // user actually saw on screen — same string the HUD publishes.
        val displayedLabel = newState.labelFor(localRole)
        Log.i(TAG, "state: ${prev::class.simpleName} → ${newState::class.simpleName}  «$displayedLabel»  role=$localRole")
        // Mirror to the HUD overlay. The Compose layer over Unity
        // subscribes to MatchHud.state to keep the in-game status
        // banner alive during long blockchain waits.
        MatchHud.publishPrimary(
            label = displayedLabel,
            mode = hudModeFor(newState, localRole),
            role = localRole,
        )
        if (newState is MatchState.Resolved) {
            currentAddress?.let { store.delete(it) }
            currentAddress = null
        }
    }

    /**
     * Test seam — production wires the indexer-backed [StatePoller] to
     * [_contractState]. The two `predicate` tests in
     * `MatchManagerStateMachineTest` override this to a no-op and
     * publish snapshots directly via [publishContractStateForTest].
     *
     * `internal open` matches the rest of the seam conventions in this
     * file (see `initSdkInternal` etc.) — keeps the production path
     * exactly as it was.
     */
    internal open fun startStatePoller(address: String) {
        pollerJob?.cancel()
        val poller = StatePoller(pollingContract(address))
        pollerJob = managerScope.launch {
            // Snapshots drive the wait predicates; the connected flow drives the
            // "Reconnecting…" HUD banner so an indexer outage reads as a network
            // issue (not a freeze) and auto-clears when it returns.
            launch { poller.snapshots().collect { _contractState.value = it } }
            launch { poller.connected.collect { ok -> MatchHud.publishConnectionLost(!ok) } }
        }
    }

    /**
     * Cache of read-only contract handles keyed by deployed address.
     *
     * One [MidnightContract] per address — they hold a lazy
     * [com.midnight.kuira.core.compact.LedgerEvaluator] whose
     * pre-loaded asset strings (polyfills + runtime) get reused
     * across every poll for that contract. Rebuilding per poll
     * would re-read the contract JS asset from disk and re-allocate
     * the evaluator, defeating that cache.
     *
     * Lifetime: lives for the [managerScope]'s lifetime. There's no
     * eviction — a single MatchManager session only sees one or two
     * distinct addresses (the current match, plus any rematch).
     */
    private val pollingContracts = mutableMapOf<String, MidnightContract>()

    /**
     * Build (or fetch the cached) read-only [MidnightContract] for
     * the given address — used by [StatePoller] to call
     * `contract.ledger()` losslessly. No witnesses and no
     * `coinPublicKey` are needed for ledger reads; circuit calls
     * use a separate handle built by [createContractHandle].
     */
    internal open fun pollingContract(address: String): MidnightContract =
        pollingContracts.getOrPut(address) {
            MidnightContract.create(requireSdk.config) {
                contractJs = context.assets.open("runtime/penalty-contract.js")
                this.address = address
            }
        }

    /**
     * Test seam — one-shot chain snapshot read used by
     * [tryResumeActiveMatch] to decide the resume target state.
     *
     * Production wraps the cached polling contract in a [StatePoller]
     * and calls `readOnce()`. Tests override this with an in-memory
     * stub so the resume path can be exercised end-to-end without a
     * live indexer.
     */
    internal open suspend fun readResumeSnapshot(address: String): ContractStateSnapshot? =
        StatePoller(pollingContract(address)).readOnce()

    /**
     * Test seam — drive a snapshot into the StateFlow that
     * [awaitContractState] consumes. Production uses [startStatePoller].
     */
    internal fun publishContractStateForTest(snapshot: ContractStateSnapshot) {
        _contractState.value = snapshot
    }

    /**
     * Publish the regulation replay payload to [MatchHud] so the
     * Compose overlay (rendered above Unity by [KicksMatchActivity])
     * can show the row-by-row scoreboard before the orchestrator
     * advances into SD or Resolved.
     *
     * Why we read the four arrays straight from [snap] instead of the
     * locally-cached `p1Shoots`/`p2Shoots`/etc. members: the snapshot
     * is the chain's authoritative version, and it's present on BOTH
     * devices' post-reveal observation (the chain has all four arrays
     * stored by the time `secondRegulationRevealLanded` fires). Using
     * the snapshot keeps both sides showing the same data without
     * special-casing which member field is populated on which device.
     *
     * The replay also flows through Unity (eventually) via
     * `UnityBridge.sendReplay(...)` — the data shape produced here is
     * the same one the cinematic will consume. See `GAME_DESIGN.md`
     * §4 "After regulation reveal".
     */
    private fun publishRegulationReplay(snap: ContractStateSnapshot) {
        val replayResult = MatchResult(
            p1Shoots = snap.p1Shoots.copyOf(),
            p1Keeps  = snap.p1Keeps.copyOf(),
            p2Shoots = snap.p2Shoots.copyOf(),
            p2Keeps  = snap.p2Keeps.copyOf(),
            sdRounds = emptyList(),
            contractAddress = state.value.address ?: "",
        )
        val regulationRounds = replayResult.toRoundResults().take(REGULATION_ROUNDS)
        Log.i(TAG, "publishRegulationReplay: ${regulationRounds.size} rounds, score=${snap.p1Score}-${snap.p2Score}")
        MatchHud.publishReplay(
            MatchHud.ReplayShow(
                rounds = regulationRounds,
                p1Score = snap.p1Score,
                p2Score = snap.p2Score,
                kind = MatchHud.ReplayKind.REGULATION,
            ),
        )
    }

    /**
     * Publish the SD-round replay — two entries (P1 shoot vs P2 keep,
     * P2 shoot vs P1 keep) — to [MatchHud] so the overlay can show
     * what happened in this SD round before the user is asked to pick
     * again (or before the winner UI lands on a decisive resolution).
     *
     * Without this, every SD round looks identical from the player's
     * point of view: "submit picks → submit picks → submit picks…"
     * with no signal of what just happened. The replay closes that
     * gap — even when the kicks are simple, seeing them resolve gives
     * the user agency over their next pick.
     *
     * Score in the replay is the CURRENT chain score (post-this-round),
     * so the climbing scoreboard reflects reality. The scoring rule
     * (P1 goal = p1Shoot != p2Keep, P2 goal = p2Shoot != p1Keep) is
     * the same as `SdRoundData.p1Goal`/`p2Goal` — single source of
     * truth.
     */
    private fun publishSdRoundReplay(
        sdData: SdRoundData,
        snap: ContractStateSnapshot,
    ) {
        val rounds = listOf(
            RoundResult(
                round = sdData.round,
                shooter = "P1",
                shootDir = sdData.p1Shoot,
                keepDir  = sdData.p2Keep,
                result = if (sdData.p1Goal) "goal" else "save",
            ),
            RoundResult(
                round = sdData.round,
                shooter = "P2",
                shootDir = sdData.p2Shoot,
                keepDir  = sdData.p1Keep,
                result = if (sdData.p2Goal) "goal" else "save",
            ),
        )
        Log.i(
            TAG,
            "publishSdRoundReplay: round=${sdData.round} " +
                "p1=${sdData.p1Shoot}vs${sdData.p2Keep} (${if (sdData.p1Goal) "GOAL" else "SAVE"}) " +
                "p2=${sdData.p2Shoot}vs${sdData.p1Keep} (${if (sdData.p2Goal) "GOAL" else "SAVE"}) " +
                "score=${snap.p1Score}-${snap.p2Score}",
        )
        MatchHud.publishReplay(
            MatchHud.ReplayShow(
                rounds = rounds,
                p1Score = snap.p1Score,
                p2Score = snap.p2Score,
                kind = MatchHud.ReplayKind.SUDDEN_DEATH_ROUND,
                sdRoundNumber = sdData.round,
            ),
        )
    }

    /**
     * Test seam — position the state machine at an arbitrary state so a
     * predicate-focused test can call `waitFor…` directly without
     * running the full deploy/commit/reveal pipeline. Production must
     * never call this; transitions always go through `transitionFrom`.
     */
    internal fun setStateForTest(target: MatchState) {
        _state.value = target
    }

    // ── Circuit invocations ─────────────────────────────────────────────

    // ── Escape hatches — end a stuck match without finishing play ───────

    /**
     * Claim the pot when the opponent missed the deadline (forfeit).
     *
     * The contract's `claimTimeout` only succeeds when (a) the on-chain
     * deadline has passed and (b) this player did their part and the opponent
     * didn't — so it can only ever resolve as a win for the caller. A premature
     * call (deadline not reached) surfaces as a [MatchState.Failed] with the
     * contract's "Deadline not reached" message, which [toMatchErrorMessage]
     * renders legibly.
     */
    suspend fun claimForfeit() = withContext(Dispatchers.IO) {
        val prev = _state.value
        val address = prev.address ?: error("claimForfeit: no active match")
        val match = store.load(address) ?: error("claimForfeit: no stored match for $address")
        try {
            callCircuit(match.secretKey, address, "claimTimeout")
            setState(MatchState.Resolved(forfeitResult(address, EarlyOutcome.WON_BY_FORFEIT)))
        } catch (e: Exception) {
            Log.w(TAG, "claimForfeit failed", e)
            setState(MatchState.Failed(prev, e))
        }
    }

    /**
     * Creator cancels a match no opponent ever joined and reclaims the stake.
     *
     * `cancelMatch` only needs WAITING phase + the creator's key — no deadline
     * gate — so it's the immediate escape hatch for "I created a match, nobody
     * joined." Resolves as a refund (the contract sets `isDraw`).
     */
    suspend fun cancelMatch() = withContext(Dispatchers.IO) {
        val prev = _state.value
        val address = prev.address ?: error("cancelMatch: no active match")
        val match = store.load(address) ?: error("cancelMatch: no stored match for $address")
        try {
            callCircuit(match.secretKey, address, "cancelMatch")
            setState(MatchState.Resolved(forfeitResult(address, EarlyOutcome.CANCELLED_REFUND)))
        } catch (e: Exception) {
            Log.w(TAG, "cancelMatch failed", e)
            setState(MatchState.Failed(prev, e))
        }
    }

    /** A no-play-through result carrying just the early-end outcome + address. */
    private fun forfeitResult(address: String, outcome: EarlyOutcome) = MatchResult(
        p1Shoots = IntArray(0),
        p1Keeps = IntArray(0),
        p2Shoots = IntArray(0),
        p2Keeps = IntArray(0),
        contractAddress = address,
        endedEarly = outcome,
    )

    private suspend fun callCircuit(
        secretKey: ByteArray,
        address: String,
        circuitName: String,
        args: Array<Any?> = emptyArray(),
    ) {
        val contract = createContractHandle(secretKey, address)
        contract.call(circuitName, *args, onProgress = stageReporter(circuitName))
    }

    private suspend fun commitRegulation(
        secretKey: ByteArray,
        address: String,
        shoots: IntArray,
        keeps: IntArray,
        nonce: ByteArray,
    ) {
        val contract = createContractHandle(
            secretKey, address, shoots = shoots, keeps = keeps, nonce = nonce,
        )
        contract.call("commitRegulation", onProgress = stageReporter("commitRegulation"))
    }

    private suspend fun revealRegulation(
        secretKey: ByteArray,
        address: String,
        shoots: IntArray,
        keeps: IntArray,
        nonce: ByteArray,
    ) {
        val contract = createContractHandle(
            secretKey, address, shoots = shoots, keeps = keeps, nonce = nonce,
        )
        contract.call("revealRegulation", onProgress = stageReporter("revealRegulation"))
    }

    private suspend fun commitSuddenDeath(
        secretKey: ByteArray,
        address: String,
        sdShoot: Int,
        sdKeep: Int,
        nonce: ByteArray,
    ) {
        val contract = createContractHandle(
            secretKey, address, sdShoot = sdShoot, sdKeep = sdKeep, nonce = nonce,
        )
        contract.call("commitSuddenDeath", onProgress = stageReporter("commitSD"))
    }

    private suspend fun revealSuddenDeath(
        secretKey: ByteArray,
        address: String,
        sdShoot: Int,
        sdKeep: Int,
        nonce: ByteArray,
    ) {
        val contract = createContractHandle(
            secretKey, address, sdShoot = sdShoot, sdKeep = sdKeep, nonce = nonce,
        )
        contract.call("revealSuddenDeath", onProgress = stageReporter("revealSD"))
    }

    /**
     * Build a stage callback that logs and mirrors the stage to
     * [MatchHud]. Centralized here so every circuit call gets the same
     * dual behavior (log + HUD) without duplicating the lambda body.
     *
     * Returns `(ContractCallStage) -> Unit` so the lambda can be passed
     * straight into [MidnightContract.call]'s `onProgress`. Captures
     * [circuitName] for the log line — useful when the user has stacked
     * multiple operations and we want to know which circuit emitted a
     * stage in the logs.
     */
    private fun stageReporter(circuitName: String): (ContractCallStage) -> Unit = { stage ->
        Log.d(TAG, "$circuitName: ${stage.javaClass.simpleName}")
        MatchHud.publishSecondary(formatContractCallStage(stage))
    }

    /**
     * Single contract-handle factory. Always registers every witness — the
     * Compact runtime expects all witness names referenced by the JS to be
     * declared, even if only a subset is exercised by the circuit being
     * called. Unused witnesses receive zero-filled bytes (their value is
     * never consumed; the proof's private-input section will only contain
     * the witnesses the active circuit actually evaluates).
     *
     * V3 witnesses:
     *   - localSecretKey:  Bytes<32>
     *   - localNonce:      Bytes<32>
     *   - localShoots:     Vector<5, Uint<8>>  → 5 concatenated bytes
     *   - localKeeps:      Vector<5, Uint<8>>  → 5 concatenated bytes
     *   - localSdShoot:    Uint<8>             → 1 byte
     *   - localSdKeep:     Uint<8>             → 1 byte
     */
    private fun createContractHandle(
        secretKey: ByteArray,
        address: String?,
        shoots: IntArray? = null,
        keeps:  IntArray? = null,
        sdShoot: Int? = null,
        sdKeep:  Int? = null,
        nonce: ByteArray? = null,
        verifierKeys: Map<String, ByteArray>? = null,
    ): MidnightContract {
        val midnightSdk = requireSdk
        val dummyNonce = ByteArray(NONCE_BYTES)

        // Pack a [PICKS_PER_ARRAY]-element IntArray into a ByteArray of the
        // same length — the wire form of Vector<5, Uint<8>>. The SDK's
        // witness machinery zeroizes the returned bytes after consumption,
        // so we always copy/build fresh per witness invocation.
        fun packPicks(arr: IntArray?): ByteArray =
            ByteArray(PICKS_PER_ARRAY) { (arr?.getOrElse(it) { 0 } ?: 0).toByte() }

        return MidnightContract.create(midnightSdk.config) {
            name = "penalty"
            contractJs = context.assets.open("runtime/penalty-contract.js")
            if (address != null) this.address = address

            // Bytes<32> witnesses — SDK default (BYTES) emits Uint8Array
            // in JS which the Compact runtime accepts directly.
            witness("localSecretKey") { WitnessResult(null, secretKey.copyOf()) }
            witness("localNonce")     { WitnessResult(null, (nonce ?: dummyNonce).copyOf()) }
            // Vector<5, Uint<8>> witnesses — must be VECTOR_OF_UINT8 so
            // the SDK emits Array<BigInt> in JS instead of Uint8Array.
            // Without this, the contract's regulation commit fails its
            // type check with "received {0:…, 1:…, 2:…, 3:…, 4:…}"
            // (the JSON-stringified Uint8Array shape).
            witness("localShoots") {
                WitnessResult(null, packPicks(shoots), WitnessKind.VECTOR_OF_UINT8)
            }
            witness("localKeeps") {
                WitnessResult(null, packPicks(keeps), WitnessKind.VECTOR_OF_UINT8)
            }
            // Scalar Uint<8> witnesses — single-byte payload, JS template's
            // `values.length === 1` branch emits BigInt which matches.
            witness("localSdShoot")   { WitnessResult(null, byteArrayOf((sdShoot ?: 0).toByte())) }
            witness("localSdKeep")    { WitnessResult(null, byteArrayOf((sdKeep  ?: 0).toByte())) }

            initialPrivateState = mapOf("secretKey" to secretKey.copyOf())
            coinPublicKey = midnightSdk.coinPublicKey
            if (verifierKeys != null) circuitVerifierKeys = verifierKeys
        }
    }

    // ── Bootstrap (keys, verifier files) ────────────────────────────────

    private fun loadVerifierKeys(): Map<String, ByteArray> {
        // V3 has 7 user circuits. Names match the .compact `export circuit`
        // declarations and the asset file basenames.
        val circuits = listOf(
            "joinMatch",
            "commitRegulation", "revealRegulation",
            "commitSuddenDeath", "revealSuddenDeath",
            "claimTimeout", "cancelMatch",
        )
        return circuits.associateWith { name ->
            context.assets.open("keys/$name.verifier").use { it.readBytes() }
        }
    }

    private suspend fun installProvingKeys() {
        // BLS params + zswap/dust wallet keys — try local-tmp first
        // (dev shortcut from `adb push`), fall back to S3 download
        // on a fresh emulator / new device. The SDK owns the recipe;
        // we just invoke it.
        ProvingKeyManager(context).ensureWalletKeysAvailable(
            logger = { Log.i(TAG, it) },
        )
        installContractCircuitKeys()
    }

    /**
     * Copies the V3 contract's circuit keys (joinMatch, commitRegulation,
     * revealRegulation, commitSuddenDeath, revealSuddenDeath, claimTimeout,
     * cancelMatch) from the APK's `assets/keys/` into `filesDir/proving_keys/`
     * where the Rust prover expects them. APK-bundled means no per-device
     * setup — these never need to be downloaded.
     */
    private fun installContractCircuitKeys() {
        val keysDir = File(context.filesDir, "proving_keys")
        val assetKeys = context.assets.list("keys") ?: emptyArray()
        assetKeys.filter { it.endsWith(".prover") || it.endsWith(".bzkir") || it.endsWith(".verifier") }.forEach { name ->
            val dst = File(keysDir, name)
            if (!dst.exists()) {
                context.assets.open("keys/$name").use { input ->
                    dst.outputStream().use { output -> input.copyTo(output) }
                }
                Log.d(TAG, "Installed contract key: $name")
            }
        }
    }

    companion object {
        private const val TAG = "MatchManager"

        // Game rules
        /** Picks per array (5 shoots OR 5 keeps) per player. */
        const val PICKS_PER_ARRAY = 5
        /** Total regulation rounds (5 P1-shooting + 5 P2-shooting). */
        const val REGULATION_ROUNDS = 10
        /** L=0, C=1, R=2 — the three penalty directions. */
        const val DIRECTION_COUNT = 3

        // Phase enum values mirroring penalty.compact's `enum Phase`.
        // Mapping: 0 WAITING, 1 COMMITTING, 2 REVEALING, 3 SD_COMMITTING,
        // 4 SD_REVEALING, 5 COMPLETE.
        private const val PHASE_WAITING = 0
        private const val PHASE_COMMITTING = 1
        private const val PHASE_REVEALING = 2
        private const val PHASE_SD_COMMITTING = 3
        private const val PHASE_SD_REVEALING = 4
        private const val PHASE_COMPLETE = 5

        // 24 hours — humans pause for meals, sleep, time-zone-offset
        // opponents. The contract is a chain primitive, not a real-time
        // game timer; players should be able to walk away from a join
        // and come back the next morning to commit. 5 minutes was a
        // localnet smoke-test value; this is the right number for any
        // production scenario.
        //
        // Note (chain-anchored time): we still set the deadline from
        // System.currentTimeMillis(), so chain ⟷ device clock skew
        // erodes the budget. At 24 hours the budget tolerates skews
        // up to several hours — far above what any reasonable device
        // would drift. If we ever ship to a setting with multi-hour
        // chain lag, switch this to an indexer-reported block time.
        private const val COMMIT_DEADLINE_DURATION_SECS = 24L * 60L * 60L
        private const val SECRET_KEY_BYTES = 32
        private const val NONCE_BYTES = 32

        /** How long to wait after deploy for the indexer to see the contract. */
        private const val INDEXER_SETTLE_MS = 5_000L

        /** How long to wait after a join before committing — joinMatch state must finalize. */
        private const val POST_JOIN_SETTLE_MS = 8_000L

        /** Between commit/reveal txs, the previous tx must be confirmed. */
        private const val INTER_TX_SETTLE_MS = 3_000L

        /** Retry budget for indexer-not-ready errors when joining. */
        private const val JOIN_RETRY_LIMIT = 10
        private const val JOIN_RETRY_DELAY_MS = 2_000L

        /**
         * How long to wait for an opponent's tx to surface on chain before
         * giving up. 5 minutes matches [COMMIT_DEADLINE_DURATION_SECS] —
         * past that, the contract's own timeout kicks in.
         */
        private const val DEFAULT_OPPONENT_WAIT_MS = 5L * 60L * 1_000L
    }
}

/**
 * One sudden-death pairing captured during the match for the replay UI.
 * Each SD round produces one entry. The pair is decisive when exactly
 * one of `p1Goal` / `p2Goal` is true.
 */
data class SdRoundData(
    val round: Int,
    val p1Shoot: Int,
    val p1Keep:  Int,
    val p2Shoot: Int,
    val p2Keep:  Int,
) {
    val p1Goal: Boolean get() = p1Shoot != p2Keep
    val p2Goal: Boolean get() = p2Shoot != p1Keep
}

/**
 * Result of a completed match — used to build the replay data for Unity.
 *
 * V3 shape: each player has shoots[5] + keeps[5] for regulation, plus an
 * optional list of sudden-death pairings. The 10 regulation rounds
 * alternate shooter:
 *   - rounds 0,2,4,6,8: P1 shoots, P2 keeps (kick index = round/2)
 *   - rounds 1,3,5,7,9: P2 shoots, P1 keeps
 *
 * Field semantics — `p1*` are always P1's choices regardless of who this
 * device is. The renderer maps "this device" → P1 or P2 via the role
 * the orchestrator was launched with.
 */
/** How a match ended without a normal play-through — see [MatchResult.endedEarly]. */
enum class EarlyOutcome { WON_BY_FORFEIT, CANCELLED_REFUND }

data class MatchResult(
    val p1Shoots: IntArray,
    val p1Keeps:  IntArray,
    val p2Shoots: IntArray,
    val p2Keeps:  IntArray,
    val sdRounds: List<SdRoundData> = emptyList(),
    val contractAddress: String,
    /**
     * Non-null when the match ended without a full play-through — the opponent
     * forfeited past the deadline (`claimTimeout`), or the creator cancelled an
     * unjoined match (`cancelMatch`). There are no revealed choices to replay in
     * that case, so [toRoundResults] returns empty and the UI shows a terminal
     * message instead of running the cinematic.
     */
    val endedEarly: EarlyOutcome? = null,
) {
    /** Build round results for Unity replay. Empty for an early-ended match. */
    fun toRoundResults(): List<RoundResult> {
        if (endedEarly != null) return emptyList()
        val regulation = (0 until MatchManager.REGULATION_ROUNDS).map { i ->
            val kickIdx = i / 2
            val p1Shoots = i % 2 == 0
            val shootDir = if (p1Shoots) this.p1Shoots[kickIdx] else this.p2Shoots[kickIdx]
            val keepDir  = if (p1Shoots) this.p2Keeps[kickIdx]  else this.p1Keeps[kickIdx]
            val isGoal = shootDir != keepDir
            RoundResult(
                round = i + 1,
                shooter = if (p1Shoots) "P1" else "P2",
                shootDir = shootDir,
                keepDir = keepDir,
                result = if (isGoal) "goal" else "save",
            )
        }
        // SD pairings: each round contributes two replay entries — P1's
        // kick then P2's kick — so the cinematic shows both attempts in
        // sequence.
        val sd = sdRounds.flatMap { sd ->
            val baseRound = MatchManager.REGULATION_ROUNDS + (sd.round - 1) * 2
            listOf(
                RoundResult(
                    round = baseRound + 1,
                    shooter = "P1",
                    shootDir = sd.p1Shoot,
                    keepDir  = sd.p2Keep,
                    result = if (sd.p1Goal) "goal" else "save",
                ),
                RoundResult(
                    round = baseRound + 2,
                    shooter = "P2",
                    shootDir = sd.p2Shoot,
                    keepDir  = sd.p1Keep,
                    result = if (sd.p2Goal) "goal" else "save",
                ),
            )
        }
        return regulation + sd
    }

    fun scores(): Pair<Int, Int> {
        val rounds = toRoundResults()
        val p1Goals = rounds.count { it.shooter == "P1" && it.result == "goal" }
        val p2Goals = rounds.count { it.shooter == "P2" && it.result == "goal" }
        return p1Goals to p2Goals
    }
}

/**
 * Thrown by [MatchManager.joinAsP2] when the contract rejects the
 * `joinMatch` call with "not in WAITING phase" and the caller didn't
 * opt into resume behaviour. Indicates the contract address already
 * has a P2 — either this same device joined earlier (legitimate
 * rejoin; caller should re-call with `isResume = true`) or another
 * actor with the deep link tried to join (wrong-actor case; caller
 * should refuse).
 *
 * The distinction is made at the call site (KicksActivity) via the
 * local [MatchStore]: if a P2 session exists for [address],
 * treat as resume; otherwise refuse and tell the user the match is
 * taken.
 */
class MatchAlreadyJoinedException(val address: String) :
    Exception("Match $address is already past the WAITING phase — a P2 has already joined.")

/**
 * Thrown by [MatchManager.joinAsP2] when the user attempts to join a
 * match whose chain phase is [PHASE_COMPLETE]. Distinct from
 * [MatchAlreadyJoinedException] (which means "match is mid-play with
 * another player") so the UI can render the right copy:
 *   - "Match in progress" → opponent already in, you can't join
 *   - "Match finished"    → game over, here's the score
 *
 * Carries the final score + winner so the UI can show the result
 * without a separate chain query.
 */
class MatchAlreadyResolvedException(
    val address: String,
    val p1Score: Int,
    val p2Score: Int,
    val winner: ByteArray,
) : Exception(
    "Match $address has already finished — P1 $p1Score - $p2Score P2",
)

/**
 * Thrown when an entry point (e.g. CHECK STATUS, resume orchestrator)
 * runs while [MatchManager] has no active match — typically Idle /
 * SdkReady / InitializingSdk. Lets callers distinguish "nothing to
 * check" from "waiting on a slow chain operation".
 */
class NoActiveMatchException(message: String) : Exception(message)

/**
 * Thrown by [MatchManager.resumePlayAsP1] / [MatchManager.resumePlayAsP2]
 * when state hasn't advanced past the role's commit yet — picks are
 * still required from Unity. The activity catches this and routes to
 * [KicksActivity.launchUnityChoicePhase] instead of the resume path.
 */
class NeedFreshPicksException(message: String) : Exception(message)

/**
 * Result of [MatchManager.tryResumeActiveMatch] when the persisted
 * match's chain phase is [PHASE_COMPLETE]. Carries enough info for
 * the activity to render a "your previous match finished" banner
 * including the local user's perspective (win / loss / draw) without
 * a separate chain query.
 */
data class PriorMatchFinished(
    val address: String,
    val role: Player,
    val p1Score: Int,
    val p2Score: Int,
    val winner: ByteArray,
    val isDraw: Boolean,
) {
    /**
     * Win/loss/draw from the LOCAL user's perspective — flips the
     * comparison based on [role]. Lets the UI pick the right copy
     * without re-deriving who-was-who.
     */
    val outcome: Outcome = when {
        isDraw -> Outcome.Draw
        role == Player.P1 && p1Score > p2Score -> Outcome.Win
        role == Player.P2 && p2Score > p1Score -> Outcome.Win
        else -> Outcome.Loss
    }

    enum class Outcome { Win, Loss, Draw }
}

// ── HUD mapping helpers ─────────────────────────────────────────────────
//
// Presentation policy for [MatchHud] — kept at file scope (not member of
// MatchManager) because they're pure functions of public types and want
// to remain trivially testable in isolation. Adjust copy / mode mapping
// here without touching the state machine.

/**
 * Map a [MatchState] to the coarse [MatchHud.Mode] that the overlay
 * uses to pick a color / icon / animation.
 *
 *  - In-flight local tx (commit / reveal on this device) → TX_IN_FLIGHT.
 *  - Anything where we're blocked on the opponent → WAITING_FOR_OPPONENT.
 *  - Resolved → DONE, Failed → ERROR.
 *  - Setup states (Idle / InitializingSdk / SdkReady) and the moment
 *    after a new SD round opens (which is waiting on the user to pick)
 *    → IDLE so the banner hides and the choice UI gets full focus.
 */
/**
 * P1-perspective default. Use [hudModeFor]`(state, role)` for the
 * role-aware version that flips PICKING / TX_IN_FLIGHT /
 * WAITING_FOR_OPPONENT for P2. Kept as the default because callers
 * outside the activity (e.g. tests) often don't know the role.
 */
internal fun hudModeFor(state: MatchState): MatchHud.Mode = hudModeFor(state, role = null)

/**
 * Role-aware mode mapping — flips the P1-named states for P2 so the
 * banner color + sub-line text track who's actually expected to act.
 *
 * The P1-perspective mapping is correct for PvAI (where the human is
 * always P1). For PvP, the same protocol state means opposite things
 * to each player: when state is [MatchState.P1Committed], P1 is
 * `WAITING_FOR_OPPONENT` and P2 is `PICKING` (their turn to commit).
 */
internal fun hudModeFor(state: MatchState, role: Player?): MatchHud.Mode {
    val isP2 = role == Player.P2
    return when (state) {
        // Pre-match — banner hidden so the menu / picker is uncluttered.
        is MatchState.Idle,
        is MatchState.InitializingSdk,
        is MatchState.SdkReady -> MatchHud.Mode.IDLE

        // Local-pick states. From P1's view, "their turn to pick" =
        // [Joined] / [SdRoundOpen]. From P2's view, P2's turn-to-pick
        // additionally includes [P1Committed] (P1 done, P2 owes a
        // commit) and [P1SdCommitted] (P1 SD done, P2 owes a SD pick).
        is MatchState.Joined,
        is MatchState.SdRoundOpen -> MatchHud.Mode.PICKING

        // Tx-in-flight: whichever side is actively submitting.
        is MatchState.Deploying -> MatchHud.Mode.TX_IN_FLIGHT
        is MatchState.JoiningAsP2 ->
            if (isP2) MatchHud.Mode.TX_IN_FLIGHT else MatchHud.Mode.WAITING_FOR_OPPONENT
        is MatchState.P1Committing,
        is MatchState.P1Revealing,
        is MatchState.P1SdCommitting,
        is MatchState.P1SdRevealing ->
            if (isP2) MatchHud.Mode.WAITING_FOR_OPPONENT else MatchHud.Mode.TX_IN_FLIGHT
        is MatchState.P2Committing,
        is MatchState.P2Revealing,
        is MatchState.P2SdCommitting,
        is MatchState.P2SdRevealing ->
            if (isP2) MatchHud.Mode.TX_IN_FLIGHT else MatchHud.Mode.WAITING_FOR_OPPONENT

        // The bug case — for P2, [P1Committed] is "your turn to commit".
        // Same idea for [P1SdCommitted] in the SD loop.
        is MatchState.P1Committed ->
            if (isP2) MatchHud.Mode.PICKING else MatchHud.Mode.WAITING_FOR_OPPONENT
        is MatchState.P1SdCommitted ->
            if (isP2) MatchHud.Mode.PICKING else MatchHud.Mode.WAITING_FOR_OPPONENT

        // Both-committed and post-reveal are symmetric — both sides
        // are technically "blocked on next tx," whoever's role it is
        // to drive it.
        is MatchState.Deployed,
        is MatchState.BothCommitted,
        is MatchState.BothSdCommitted -> MatchHud.Mode.WAITING_FOR_OPPONENT

        // P1Revealed: P1 done revealing → waiting on P2's reveal.
        // From P2's view, P2 is up next.
        is MatchState.P1Revealed,
        is MatchState.P1SdRevealed ->
            if (isP2) MatchHud.Mode.TX_IN_FLIGHT else MatchHud.Mode.WAITING_FOR_OPPONENT

        is MatchState.Resolved -> MatchHud.Mode.DONE
        is MatchState.Failed -> MatchHud.Mode.ERROR
    }
}

/**
 * User-facing label for a contract-call stage. Returns `null` to clear
 * the sub-line (e.g. when the stage doesn't add information the user
 * needs to see).
 *
 * Stage class names are emitted to logs as-is for grep; the *user* sees
 * these humanized strings instead. Adjust freely — these are
 * presentation copy, not protocol.
 */
internal fun formatContractCallStage(stage: ContractCallStage): String? = when (stage) {
    is ContractCallStage.FetchingState -> "Fetching contract state…"
    is ContractCallStage.Executing -> "Building transaction…"
    is ContractCallStage.Proving -> "Generating zero-knowledge proof…"
    is ContractCallStage.Balancing -> "Balancing dust fee…"
    is ContractCallStage.BalancingDetail -> when (val progress = stage.progress) {
        is BalanceProgress.SyncingDust -> "Syncing dust wallet…"
        is BalanceProgress.SyncingDustProgress ->
            "Syncing dust (${progress.eventsProcessed}/${progress.totalEvents})…"
        is BalanceProgress.ProvingDust -> "Proving dust payment…"
        is BalanceProgress.Submitting -> "Submitting to chain…"
        is BalanceProgress.WaitingFinalization -> "Waiting for block finalization…"
        is BalanceProgress.RetryingDustSync -> "Retrying dust sync…"
    }
    is ContractCallStage.Submitting -> "Submitting to chain…"
}
