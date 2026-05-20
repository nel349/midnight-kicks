package com.midnight.kicks

import android.content.Context
import android.util.Log
import com.midnight.kuira.core.compact.MidnightContract
import com.midnight.kuira.core.compact.WitnessKind
import com.midnight.kuira.core.compact.WitnessResult
import com.midnight.kuira.core.compact.proving.ProvingKeyManager
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.sdk.MidnightSdk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
    seed: ByteArray,
    /**
     * Multi-match encrypted store. Survives `kill -9` between commit
     * and reveal so the user's stake doesn't get stranded behind a
     * commitment we no longer have the witnesses for. Replaces the
     * earlier `KicksSessionStore` + `MatchVault` split — one type, one
     * lifecycle, one source of truth. See [MatchStore] KDoc for the
     * threat model + lifetime contract.
     */
    private val store: MatchStore = MatchStore(context),
) {
    // Take the seed by value into a local-only field so we can wipe it from
    // both the caller's reference and our own at close() time.
    private var seed: ByteArray? = seed.copyOf()

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
        installProvingKeys()
        val builtSdk = MidnightSdk.Builder(context)
            .network(network)
            .seed(requireNotNull(seed) { "seed already wiped" })
            .build()
        sdk = builtSdk
        Log.i(TAG, "SDK initialized, wallet: ${builtSdk.walletAddress}")
        Log.i(TAG, "Wallet keys installed: ${builtSdk.provingKeyManager.hasWalletKeys()}")

        // Seed has been copied into the SDK by build(); wipe our local copy.
        seed?.fill(0)
        seed = null
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
        // Pick the first match in the store as the "active" one. With
        // multi-match storage this is a heuristic — the Resume UI (Commit
        // 3) lets the user pick explicitly. For now first-found is fine
        // because Kicks only writes one match at a time in this commit.
        val match = store.loadAll().firstOrNull() ?: return@withContext null
        require(state.value is MatchState.SdkReady) {
            "tryResumeActiveMatch must be called from SdkReady — got ${state.value}"
        }
        Log.i(TAG, "Resuming from store — address=${match.address.take(16)}…")

        // Rehydrate the in-memory state machine from the persisted match.
        // Same key must hash into the deployed commitment for any reveal
        // to succeed — write into the right field based on the saved
        // role so a P2 resume doesn't load its key into the P1 slot
        // (which would silently fail every later reveal: the contract
        // would see a different key than the one in the commitment).
        currentAddress = match.address
        rehydrateLocalIdentity(match)

        val chainSnap = StatePoller(requireSdk.config, match.address).readOnce()
        if (chainSnap == null) {
            Log.w(TAG, "Resume: indexer returned no snapshot — assuming Deployed and letting user retry")
            setState(MatchState.Deployed(match.address))
            return@withContext match.address
        }
        if (chainSnap.phase == PHASE_COMPLETE) {
            Log.i(TAG, "Resume: match already COMPLETE on chain — deleting store entry, no resume")
            store.delete(match.address)
            currentAddress = null
            return@withContext null
        }

        // Map the on-chain phase + flags to a MatchState. Bias toward
        // the state where the local player still has work to do — if
        // we're not sure, prefer the earlier state so the UI lets the
        // user re-submit rather than wait on something already done.
        val target = when (chainSnap.phase) {
            PHASE_WAITING -> MatchState.Deployed(match.address)
            PHASE_COMMITTING -> if (chainSnap.p1Committed) {
                MatchState.P1Committed(match.address)
            } else {
                MatchState.Joined(match.address)
            }
            PHASE_REVEALING -> if (chainSnap.p1Revealed) {
                MatchState.P1Revealed(match.address)
            } else {
                MatchState.BothCommitted(match.address)
            }
            else -> {
                // SD phases not yet wired for resume. Bail out gracefully so
                // the user can manually re-engage via the menu, rather than
                // trapping the state machine in a half-restored SD round.
                Log.w(TAG, "Resume: phase=${chainSnap.phase} not yet supported — staying on SdkReady")
                return@withContext null
            }
        }
        setState(target)
        match.address
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

    /** P1 deploys a fresh contract. Transitions [SdkReady] → [Deployed]. */
    suspend fun deployMatch(): String {
        resetForNewAction()
        return transitionFrom<MatchState.SdkReady, String>(
            inProgress = { MatchState.Deploying },
            onSuccess = { _, address -> MatchState.Deployed(address) },
        ) {
            val address = executeDeploy(p1SecretKey)
            Log.i(TAG, "Match at: $address")
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
     * Mirror of [aiJoin] but for the join-side device — the address comes
     * from outside (deep link / QR scan / paste) instead of from a local
     * [deployMatch] call. The retry loop is the same indexer-readiness
     * pattern (`"not found"` substring match): the create-side's deploy
     * has to land + be ingested before this call succeeds.
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
    ) {
        // Resume case: we're rejoining a match we already saved. Skip
        // the reset so the existing store record + in-memory witnesses
        // survive. Otherwise this is a fresh join action and we drop
        // any prior in-flight match's in-memory state (preserved in
        // store for the Resume UI).
        if (!isResume) resetForNewAction()
        transitionFrom<MatchState.SdkReady, Unit>(
            inProgress = { MatchState.JoiningAsP2(address) },
            onSuccess = { _, _ -> MatchState.Joined(address) },
        ) {
            val deadlineSecs = System.currentTimeMillis() / 1000 + COMMIT_DEADLINE_DURATION_SECS
            val deadline = BigInteger.valueOf(deadlineSecs)
            try {
                executeJoinMatch(p2SecretKey, address, deadline)
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
                        // address. The contract correctly refuses the
                        // resubmit, but we treat that as a no-op and
                        // advance to Joined so the user lands back on
                        // MatchReady → choice phase.
                        //
                        // Rehydrate the persisted record so subsequent
                        // commit/reveal calls hash the original P2 key
                        // (not a freshly random one) and find their
                        // witnesses in [updateCurrentMatch].
                        val persisted = store.load(address)
                        if (persisted != null) {
                            currentAddress = address
                            rehydrateLocalIdentity(persisted)
                        } else {
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
                        Log.i(
                            TAG,
                            "joinAsP2: contract already past WAITING and caller marked " +
                                "this as a resume — advancing to Joined without resubmitting",
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
    }

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
        val current = state.value
        require(current is MatchState.Deployed) {
            "awaitOpponentJoin: expected Deployed, got ${current::class.simpleName}"
        }
        awaitContractState(timeoutMs) { it.matchJoined }
        setState(MatchState.Joined(current.address))
    }

    /** P1 commits their regulation picks. Transitions [Joined] → [P1Committed]. */
    suspend fun submitP1Picks(shoots: IntArray, keeps: IntArray) {
        require(shoots.size == PICKS_PER_ARRAY) { "Need $PICKS_PER_ARRAY shoots" }
        require(keeps.size == PICKS_PER_ARRAY)  { "Need $PICKS_PER_ARRAY keeps" }
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

        // Did regulation decide it, or did we draw into SD?
        val snap = StatePoller(requireSdk.config, prev.address).readOnce()
        val phase = snap?.phase ?: -1
        if (phase == PHASE_COMPLETE) {
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
            delay(INTER_TX_SETTLE_MS)
            requireSdk.wallet.refresh()
            val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
            commitSuddenDeath(p2SecretKey, prev.address, shoot, keep, nonce)
            p2SdShoot = shoot
            p2SdKeep  = keep
            p2SdNonce = nonce
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
            sdRoundsForReplay += SdRoundData(
                round = prev.round,
                p1Shoot = p1SdShoot, p1Keep = p1SdKeep,
                p2Shoot = p2SdShoot, p2Keep = p2SdKeep,
            )

            val snap = StatePoller(requireSdk.config, prev.address).readOnce()
            if (snap?.phase == PHASE_COMPLETE) {
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
     * Wait for the on-chain contract state to satisfy [predicate]. Starts the
     * [StatePoller] if not already running (and stops it before returning if
     * this call was the one that started it).
     *
     * Throws [kotlinx.coroutines.TimeoutCancellationException] if [timeoutMs]
     * elapses before a matching snapshot arrives.
     */
    private suspend fun awaitContractState(
        timeoutMs: Long,
        predicate: (ContractStateSnapshot) -> Boolean,
    ): ContractStateSnapshot {
        val address = state.value.address
            ?: error("awaitContractState called before deploy — no address")

        val startedHere = pollerJob == null
        if (startedHere) startStatePoller(address)
        return try {
            withTimeout(timeoutMs) {
                contractState.filterNotNull().first(predicate)
            }
        } finally {
            if (startedHere) {
                pollerJob?.cancel()
                pollerJob = null
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
        require(current is MatchState.Joined) {
            "waitForP1Committed: expected Joined, got ${current::class.simpleName}"
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
        require(current is MatchState.BothCommitted) {
            "waitForP1Revealed: expected BothCommitted, got ${current::class.simpleName}"
        }
        val snap = awaitContractState(timeoutMs) { it.p1Revealed }
        // Capture P1's revealed regulation picks from chain — P2 doesn't
        // have them locally and needs them for the replay payload.
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
        require(current is MatchState.SdRoundOpen) {
            "waitForP1SdCommitted: expected SdRoundOpen, got ${current::class.simpleName}"
        }
        awaitContractState(timeoutMs) { it.p1Committed && it.sdRound == current.round }
        setState(MatchState.P1SdCommitted(current.address, current.round))
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
        require(current is MatchState.BothSdCommitted) {
            "waitForP1SdRevealed: expected BothSdCommitted, got ${current::class.simpleName}"
        }
        val snap = awaitContractState(timeoutMs) {
            it.p1Revealed && it.sdRound == current.round
        }
        p1SdShoot = snap.p1SdShoot
        p1SdKeep  = snap.p1SdKeep
        setState(MatchState.P1SdRevealed(current.address, current.round))
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
            it.p2Revealed && it.sdRound == prev.round
        }
        // Capture P2's SD pair for the replay payload.
        p2SdShoot = snap.p2SdShoot
        p2SdKeep  = snap.p2SdKeep
        sdRoundsForReplay += SdRoundData(
            round = prev.round,
            p1Shoot = p1SdShoot, p1Keep = p1SdKeep,
            p2Shoot = p2SdShoot, p2Keep = p2SdKeep,
        )

        if (snap.phase == PHASE_COMPLETE) {
            buildMatchResult(prev.address).also { lastResult = it }
        } else {
            null
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
        val snap = awaitContractState(timeoutMs) { it.p2Revealed }
        // Capture P2's regulation picks for the replay payload.
        p2Shoots = snap.p2Shoots.copyOf()
        p2Keeps  = snap.p2Keeps.copyOf()
        if (snap.phase == PHASE_COMPLETE) {
            buildMatchResult(prev.address).also { lastResult = it }
        } else {
            null  // SD round 1 open
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
        submitP1Picks(shoots, keeps)
        waitForP2Committed()
        revealP1()
        var result: MatchResult? = waitForP2Revealed()
        // SD loop — picks come from [getSdPicks] (UI for real players,
        // random by default for tests / smoke runs).
        while (result == null) {
            val round = currentSdRoundOrError()
            val (pShoot, pKeep) = getSdPicks(round)
            submitP1SdPick(pShoot, pKeep)
            waitForP2SdCommitted()
            revealP1Sd()
            result = waitForP2SdRevealed()
        }
        return result
    }

    /**
     * P2 (join-side) gameplay orchestrator. Mirror of [playAsP1]:
     * waits for P1 to commit, submits our commit, waits for P1 to
     * reveal (capturing P1's choices from the chain snapshot), then
     * reveals our own. The contract auto-resolves on the second reveal
     * and [revealP2] returns the [MatchResult].
     */
    suspend fun playAsP2(
        shoots: IntArray,
        keeps: IntArray,
        getSdPicks: suspend (round: Int) -> Pair<Int, Int> = ::randomSdPair,
    ): MatchResult {
        waitForP1Committed()
        submitP2Picks(shoots, keeps)
        waitForP1Revealed()
        var result: MatchResult? = revealP2()
        // SD loop — gather P2's picks via [getSdPicks] (UI on real
        // devices, random by default). We can pre-gather the picks at
        // the top of each round while P1 commits; the contract doesn't
        // care about commit order, only that both pairs land before
        // either reveals.
        while (result == null) {
            val round = currentSdRoundOrError()
            val (pShoot, pKeep) = getSdPicks(round)
            waitForP1SdCommitted()
            submitP2SdPick(pShoot, pKeep)
            waitForP1SdRevealed()
            result = revealP2Sd()
        }
        return result
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
        sdk?.close()
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
     * Read the round number from the current [MatchState.SdRoundOpen].
     * Called at the top of every SD loop iteration; the orchestrator's
     * state machine has just transitioned into SdRoundOpen so this is
     * always the live round number to surface to the UI.
     */
    private fun currentSdRoundOrError(): Int {
        val s = state.value
        return (s as? MatchState.SdRoundOpen)?.round
            ?: error("currentSdRoundOrError: state is ${s::class.simpleName}, expected SdRoundOpen")
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
    private fun setState(newState: MatchState) {
        val prev = _state.value
        _state.value = newState
        // Log both the class (for grep / cheap pattern matching) and the
        // human label (so the log doubles as a script of what the user sees).
        Log.i(TAG, "state: ${prev::class.simpleName} → ${newState::class.simpleName}  «${newState.label}»")
        if (newState is MatchState.Resolved) {
            currentAddress?.let { store.delete(it) }
            currentAddress = null
        }
    }

    private fun startStatePoller(address: String) {
        pollerJob?.cancel()
        val poller = StatePoller(requireSdk.config, address)
        pollerJob = managerScope.launch {
            poller.snapshots().collect { _contractState.value = it }
        }
    }

    // ── Circuit invocations ─────────────────────────────────────────────

    private suspend fun callCircuit(
        secretKey: ByteArray,
        address: String,
        circuitName: String,
        args: Array<Any?> = emptyArray(),
    ) {
        val contract = createContractHandle(secretKey, address)
        contract.call(circuitName, *args) { stage -> Log.d(TAG, "$circuitName: ${stage.javaClass.simpleName}") }
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
        contract.call("commitRegulation") { stage -> Log.d(TAG, "commitRegulation: ${stage.javaClass.simpleName}") }
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
        contract.call("revealRegulation") { stage -> Log.d(TAG, "revealRegulation: ${stage.javaClass.simpleName}") }
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
        contract.call("commitSuddenDeath") { stage -> Log.d(TAG, "commitSD: ${stage.javaClass.simpleName}") }
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
        contract.call("revealSuddenDeath") { stage -> Log.d(TAG, "revealSD: ${stage.javaClass.simpleName}") }
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
data class MatchResult(
    val p1Shoots: IntArray,
    val p1Keeps:  IntArray,
    val p2Shoots: IntArray,
    val p2Keeps:  IntArray,
    val sdRounds: List<SdRoundData> = emptyList(),
    val contractAddress: String,
) {
    /** Build round results for Unity replay. */
    fun toRoundResults(): List<RoundResult> {
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
