package com.midnight.kicks

import android.content.Context
import android.util.Log
import com.midnight.kuira.core.compact.MidnightContract
import com.midnight.kuira.core.compact.WitnessResult
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
class MatchManager(
    private val context: Context,
    private val network: MidnightNetwork,
    seed: ByteArray,
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

    // P1 (local human) identity
    private val p1SecretKey = ByteArray(SECRET_KEY_BYTES).also { random.nextBytes(it) }

    // P2 identity. Today this is an on-device AI; tomorrow it's the friend
    // on the other phone (their key, not stored here at all).
    private val p2SecretKey = ByteArray(SECRET_KEY_BYTES).also { random.nextBytes(it) }

    // Choices + nonces are captured at commit time and reused at reveal.
    private var p1Choices: IntArray? = null
    private var p1Nonce: ByteArray? = null
    private var p2Choices: IntArray? = null
    private var p2Nonce: ByteArray? = null

    /** Last completed match's data, used by [KicksActivity] for the replay payload. */
    var lastResult: MatchResult? = null
        private set

    // ── Setup ──────────────────────────────────────────────────────────

    suspend fun initSdk() = withContext(Dispatchers.IO) {
        require(state.value is MatchState.Idle) { "initSdk requires Idle, got ${state.value}" }
        setState(MatchState.InitializingSdk)

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

        setState(MatchState.SdkReady)
    }

    // ── Transition steps (one per circuit / logical action) ─────────────

    /** P1 deploys a fresh contract. Transitions [SdkReady] → [Deployed]. */
    suspend fun deployMatch(): String = transitionFrom<MatchState.SdkReady, String>(
        inProgress = { MatchState.Deploying },
        onSuccess = { _, address -> MatchState.Deployed(address) },
    ) {
        val verifierKeys = loadVerifierKeys()
        val contract = createContractHandle(p1SecretKey, address = null, verifierKeys = verifierKeys)
        val deploy = contract.deploy { stage -> Log.d(TAG, "deploy: ${stage.javaClass.simpleName}") }
        Log.i(TAG, "Match at: ${deploy.contractAddress}")

        // Indexer needs a beat to ingest the deploy block, and the deploy
        // consumed a dust UTXO so we need to refresh the wallet's view.
        delay(INDEXER_SETTLE_MS)
        requireSdk.wallet.refresh()

        // StatePoller is NOT started here. It's only relevant for PvP, and
        // even then only during explicit wait windows (see waitForP2*).
        // PvAI has no real wait windows — both players' transactions are
        // submitted from this device, and `nodeRpcClient.submitAndWaitForFinalization`
        // already guarantees finality before each transition returns.

        deploy.contractAddress
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
     */
    suspend fun joinAsP2(address: String) = transitionFrom<MatchState.SdkReady, Unit>(
        inProgress = { MatchState.JoiningAsP2(address) },
        onSuccess = { _, _ -> MatchState.Joined(address) },
    ) {
        val deadline = BigInteger.valueOf(
            System.currentTimeMillis() / 1000 + COMMIT_DEADLINE_DURATION_SECS
        )
        retryUntilIndexerReady(JOIN_RETRY_LIMIT, JOIN_RETRY_DELAY_MS) {
            callCircuit(p2SecretKey, address, "joinMatch", arrayOf(deadline))
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

    /** P1 commits their five choices. Transitions [Joined] → [P1Committed]. */
    suspend fun submitP1Choices(choices: IntArray) {
        require(choices.size == ROUNDS_PER_BATCH) { "Need $ROUNDS_PER_BATCH choices" }
        transitionFrom<MatchState.Joined, Unit>(
            inProgress = { MatchState.P1Committing(it.address) },
            onSuccess = { prev, _ -> MatchState.P1Committed(prev.address) },
        ) { prev ->
            delay(POST_JOIN_SETTLE_MS) // join must finalize before next tx
            requireSdk.wallet.refresh()

            val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
            commitChoices(p1SecretKey, prev.address, choices, nonce)
            p1Choices = choices.copyOf()
            p1Nonce = nonce
        }
    }

    /** P2 commits their five choices. Transitions [P1Committed] → [BothCommitted]. */
    suspend fun submitP2Choices(choices: IntArray) {
        require(choices.size == ROUNDS_PER_BATCH) { "Need $ROUNDS_PER_BATCH choices" }
        transitionFrom<MatchState.P1Committed, Unit>(
            inProgress = { MatchState.P2Committing(it.address) },
            onSuccess = { prev, _ -> MatchState.BothCommitted(prev.address) },
        ) { prev ->
            delay(INTER_TX_SETTLE_MS)
            requireSdk.wallet.refresh()

            val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
            commitChoices(p2SecretKey, prev.address, choices, nonce)
            p2Choices = choices.copyOf()
            p2Nonce = nonce
        }
    }

    /** P1 reveals their choices. Transitions [BothCommitted] → [P1Revealed]. */
    suspend fun revealP1() = transitionFrom<MatchState.BothCommitted, Unit>(
        inProgress = { MatchState.P1Revealing(it.address) },
        onSuccess = { prev, _ -> MatchState.P1Revealed(prev.address) },
    ) { prev ->
        val choices = requireNotNull(p1Choices) { "No P1 choices captured" }
        val nonce = requireNotNull(p1Nonce) { "No P1 nonce captured" }
        delay(INTER_TX_SETTLE_MS)
        requireSdk.wallet.refresh()
        revealChoices(p1SecretKey, prev.address, choices, nonce)
    }

    /**
     * P2 reveals. The contract auto-resolves when the second reveal lands.
     * Transitions [P1Revealed] → [Resolved].
     */
    suspend fun revealP2(): MatchResult = transitionFrom<MatchState.P1Revealed, MatchResult>(
        inProgress = { MatchState.P2Revealing(it.address) },
        onSuccess = { _, result -> MatchState.Resolved(result) },
    ) { prev ->
        val p1c = requireNotNull(p1Choices) { "No P1 choices captured" }
        val p2c = requireNotNull(p2Choices) { "No P2 choices captured" }
        val p2n = requireNotNull(p2Nonce) { "No P2 nonce captured" }
        delay(INTER_TX_SETTLE_MS)
        requireSdk.wallet.refresh()
        revealChoices(p2SecretKey, prev.address, p2c, p2n)

        MatchResult(playerChoices = p1c, aiChoices = p2c, contractAddress = prev.address).also {
            lastResult = it
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
     * P2 revealed on their device — wait for that to land on chain. The
     * contract auto-resolves on the second reveal, so this also produces
     * the final [MatchResult] by reading p2's choices from the snapshot
     * (we don't have them locally in PvP).
     *
     * Transitions [P1Revealed] → [Resolved].
     */
    suspend fun waitForP2Revealed(
        timeoutMs: Long = DEFAULT_OPPONENT_WAIT_MS,
    ): MatchResult = transitionFrom<MatchState.P1Revealed, MatchResult>(
        inProgress = { it },
        onSuccess = { _, result -> MatchState.Resolved(result) },
    ) { prev ->
        val snap = awaitContractState(timeoutMs) { it.p2Revealed }
        val p1c = requireNotNull(p1Choices) { "No P1 choices captured" }
        MatchResult(
            playerChoices = p1c,
            aiChoices = snap.p2Choices,  // historical field name; in PvP it's the friend's
            contractAddress = prev.address,
        ).also { lastResult = it }
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
    suspend fun playAgainstAi(playerChoices: IntArray): MatchResult {
        val aiChoices = generateAiChoices()
        Log.i(TAG, "AI choices: ${aiChoices.map(::dirLabel)}")

        if (state.value is MatchState.Idle) initSdk()
        deployMatch()
        aiJoin()
        submitP1Choices(playerChoices)
        submitP2Choices(aiChoices)
        revealP1()
        return revealP2()
    }

    fun close() {
        managerScope.cancel()  // stops StatePoller and any in-flight observers
        pollerJob = null
        sdk?.close()
        sdk = null
        p1SecretKey.fill(0)
        p2SecretKey.fill(0)
        p1Nonce?.fill(0)
        p2Nonce?.fill(0)
        seed?.fill(0)
        seed = null
    }

    // ── Internal helpers ────────────────────────────────────────────────

    /** Generate AI choices locally. Each round is independent [0..2]. */
    private fun generateAiChoices(): IntArray =
        IntArray(ROUNDS_PER_BATCH) { random.nextInt(DIRECTION_COUNT) }

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
     */
    private fun setState(newState: MatchState) {
        val prev = _state.value
        _state.value = newState
        // Log both the class (for grep / cheap pattern matching) and the
        // human label (so the log doubles as a script of what the user sees).
        Log.i(TAG, "state: ${prev::class.simpleName} → ${newState::class.simpleName}  «${newState.label}»")
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

    private suspend fun commitChoices(
        secretKey: ByteArray,
        address: String,
        choices: IntArray,
        nonce: ByteArray,
    ) {
        val contract = createContractHandle(secretKey, address, choices, nonce)
        contract.call("commitBatch") { stage -> Log.d(TAG, "commit: ${stage.javaClass.simpleName}") }
    }

    private suspend fun revealChoices(
        secretKey: ByteArray,
        address: String,
        choices: IntArray,
        nonce: ByteArray,
    ) {
        val contract = createContractHandle(secretKey, address, choices, nonce)
        contract.call("revealBatch") { stage -> Log.d(TAG, "reveal: ${stage.javaClass.simpleName}") }
    }

    private fun createContractHandle(
        secretKey: ByteArray,
        address: String?,
        choices: IntArray? = null,
        nonce: ByteArray? = null,
        verifierKeys: Map<String, ByteArray>? = null,
    ): MidnightContract {
        val midnightSdk = requireSdk
        val dummyNonce = ByteArray(NONCE_BYTES)

        return MidnightContract.create(midnightSdk.config) {
            name = "penalty"
            contractJs = context.assets.open("runtime/penalty-contract.js")
            if (address != null) this.address = address

            // Each witness returns a fresh ByteArray. The SDK zeroizes the
            // returned bytes after consumption (CircuitExecutor#registerWitnesses),
            // so callers' original arrays must not be exposed by reference.
            witness("localSecretKey") { WitnessResult(null, secretKey.copyOf()) }
            repeat(ROUNDS_PER_BATCH) { i ->
                witness("localChoice$i") {
                    WitnessResult(null, byteArrayOf((choices?.getOrElse(i) { 0 } ?: 0).toByte()))
                }
            }
            witness("localNonce") { WitnessResult(null, (nonce ?: dummyNonce).copyOf()) }

            initialPrivateState = mapOf("secretKey" to secretKey.copyOf())
            coinPublicKey = midnightSdk.coinPublicKey
            if (verifierKeys != null) circuitVerifierKeys = verifierKeys
        }
    }

    // ── Bootstrap (keys, verifier files) ────────────────────────────────

    private fun loadVerifierKeys(): Map<String, ByteArray> {
        val circuits = listOf("commitBatch", "revealBatch", "joinMatch", "cancelMatch", "claimTimeout")
        return circuits.associateWith { name ->
            context.assets.open("keys/$name.verifier").use { it.readBytes() }
        }
    }

    private fun installProvingKeys() {
        // BLS params + wallet keys (zswap/dust) → canonical dev installer on
        // ProvingKeyManager. Same method the SDK e2e test and BBoard canary call.
        com.midnight.kuira.core.compact.proving.ProvingKeyManager(context).installFromLocalTmp()

        // Kicks-specific: contract circuit keys (commitBatch, revealBatch,
        // joinMatch, cancelMatch, claimTimeout) ship inside the APK at
        // assets/keys/ — copied into keysDir for the Rust prover to find.
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
        /** Choices per commit-reveal batch. The compact contract has this baked in. */
        const val ROUNDS_PER_BATCH = 5
        /** L=0, C=1, R=2 — the three penalty directions. */
        const val DIRECTION_COUNT = 3

        private const val COMMIT_DEADLINE_DURATION_SECS = 300L
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
 * Result of a completed match — used to build the replay data for Unity.
 *
 * Field name `aiChoices` is historical (AI was the only P2 implementation
 * when this was written). For PvP it holds the friend's choices.
 */
data class MatchResult(
    val playerChoices: IntArray,
    val aiChoices: IntArray,
    val contractAddress: String,
) {
    /** Build round results for Unity replay. */
    fun toRoundResults(): List<RoundResult> {
        return (0 until MatchManager.ROUNDS_PER_BATCH).map { i ->
            val isPlayerShooting = i % 2 == 0
            val shootDir = if (isPlayerShooting) playerChoices[i] else aiChoices[i]
            val keepDir = if (isPlayerShooting) aiChoices[i] else playerChoices[i]
            val isGoal = shootDir != keepDir

            RoundResult(
                round = i + 1,
                shooter = if (isPlayerShooting) "P1" else "P2",
                shootDir = shootDir,
                keepDir = keepDir,
                result = if (isGoal) "goal" else "save",
            )
        }
    }

    fun scores(): Pair<Int, Int> {
        val rounds = toRoundResults()
        val p1Goals = rounds.count { it.shooter == "P1" && it.result == "goal" }
        val p2Goals = rounds.count { it.shooter == "P2" && it.result == "goal" }
        return p1Goals to p2Goals
    }
}
