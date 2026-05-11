package com.midnight.kicks

import android.content.Context
import android.util.Log
import com.midnight.kuira.core.compact.ContractCallStage
import com.midnight.kuira.core.compact.MidnightContract
import com.midnight.kuira.core.compact.WitnessResult
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.sdk.MidnightSdk
import kotlinx.coroutines.delay
import java.io.File
import java.security.SecureRandom

/**
 * Manages the penalty match lifecycle.
 *
 * Supports Player vs AI mode: both players run on the same device.
 * P1 = human (picks directions in Unity), P2 = AI (random choices).
 */
class MatchManager(
    private val context: Context,
    private val network: MidnightNetwork,
    private val seed: ByteArray,
) {
    private var sdk: MidnightSdk? = null
    private var contractAddress: String? = null

    // P1 (human) keys
    private val p1SecretKey = ByteArray(SECRET_KEY_BYTES).also { SecureRandom().nextBytes(it) }

    // P2 (AI) keys — separate identity
    private val p2SecretKey = ByteArray(SECRET_KEY_BYTES).also { SecureRandom().nextBytes(it) }

    // Last match result
    var lastResult: MatchResult? = null
        private set

    suspend fun initSdk(onProgress: (String) -> Unit) {
        onProgress("Initializing SDK...")
        installProvingKeys()

        val midnightSdk = MidnightSdk.Builder(context)
            .network(network)
            .seed(seed)
            .build()
        sdk = midnightSdk
        Log.i(TAG, "SDK initialized, wallet: ${midnightSdk.walletAddress}")
        Log.i(TAG, "Wallet keys installed: ${midnightSdk.provingKeyManager.hasWalletKeys()}")
    }

    /**
     * Full Player vs AI game loop:
     * 1. Deploy contract (P1 creates match)
     * 2. AI joins as P2
     * 3. Player picks 5 directions (passed in)
     * 4. AI picks 5 random directions
     * 5. P1 commits, P2 commits
     * 6. P1 reveals, P2 reveals (auto-resolves)
     * 7. Returns match result
     */
    suspend fun playAgainstAi(
        playerChoices: IntArray,
        onProgress: (String) -> Unit,
    ): MatchResult {
        require(playerChoices.size == 5) { "Need 5 choices" }
        val midnightSdk = requireNotNull(sdk) { "Call initSdk first" }

        // AI picks random directions
        val aiChoices = IntArray(5) { SecureRandom().nextInt(3) }
        val aiLabels = aiChoices.map { when (it) { 0 -> "L"; 1 -> "C"; 2 -> "R"; else -> "?" } }
        Log.i(TAG, "AI choices: $aiLabels")

        // Step 1: Deploy contract (P1 creates match)
        onProgress("Deploying match...")
        val address = deployMatch(p1SecretKey, onProgress)
        Log.i(TAG, "Match at: $address")

        // Wait for indexer to catch up with both the contract AND the dust spend
        onProgress("Waiting for indexer (contract + dust)...")
        delay(5000) // Give indexer time to process the deploy block's dust events

        // Force dust resync — deploy consumed a UTXO, need fresh state
        onProgress("Resyncing dust...")
        midnightSdk.wallet.forceResyncDust()

        // Wait for indexer to find the contract
        onProgress("Waiting for indexer...")
        var joined = false
        for (attempt in 1..10) {
            delay(2000)
            try {
                onProgress("AI joining match (attempt $attempt)...")
                val deadlineAbsolute = java.math.BigInteger.valueOf(
                    System.currentTimeMillis() / 1000 + COMMIT_DEADLINE_DURATION_SECS
                )
                callCircuit(p2SecretKey, address, "joinMatch", arrayOf(deadlineAbsolute), onProgress)
                joined = true
                break
            } catch (e: Exception) {
                if (e.message?.contains("not found") == true && attempt < 10) {
                    Log.w(TAG, "Indexer not ready, retrying in 2s...")
                    continue
                }
                throw e
            }
        }
        if (!joined) throw IllegalStateException("Failed to join match after 10 attempts")
        Log.i(TAG, "AI joined")

        // Step 3: P1 commits — delay for indexer to process joinMatch state
        onProgress("Waiting for state sync...")
        delay(8000)
        midnightSdk.wallet.forceResyncDust()
        onProgress("Committing your choices...")
        val p1Nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        commitChoices(p1SecretKey, address, playerChoices, p1Nonce, onProgress)
        Log.i(TAG, "P1 committed")

        // Step 4: P2 (AI) commits
        onProgress("Resyncing dust...")
        delay(3000)
        midnightSdk.wallet.forceResyncDust()
        onProgress("AI committing...")
        val p2Nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        commitChoices(p2SecretKey, address, aiChoices, p2Nonce, onProgress)
        Log.i(TAG, "P2 committed")

        // Step 5: P1 reveals
        onProgress("Resyncing dust...")
        delay(3000)
        midnightSdk.wallet.forceResyncDust()
        onProgress("Revealing your choices...")
        revealChoices(p1SecretKey, address, playerChoices, p1Nonce, onProgress)
        Log.i(TAG, "P1 revealed")

        // Step 6: P2 (AI) reveals — contract auto-resolves
        onProgress("Resyncing dust...")
        delay(3000)
        midnightSdk.wallet.forceResyncDust()
        onProgress("AI revealing...")
        revealChoices(p2SecretKey, address, aiChoices, p2Nonce, onProgress)
        Log.i(TAG, "P2 revealed — match resolved!")

        // Build result
        val result = MatchResult(
            playerChoices = playerChoices,
            aiChoices = aiChoices,
            contractAddress = address,
        )
        lastResult = result

        onProgress("Match complete!")
        return result
    }

    // ── Contract operations ──

    private suspend fun deployMatch(
        secretKey: ByteArray,
        onProgress: (String) -> Unit,
    ): String {
        val midnightSdk = requireNotNull(sdk)
        val verifierKeys = loadVerifierKeys()

        val contract = createContractHandle(midnightSdk, secretKey, address = null, verifierKeys = verifierKeys)
        val result = contract.deploy { stage ->
            onProgress(stageLabel(stage))
        }

        contractAddress = result.contractAddress
        return result.contractAddress
    }

    private suspend fun callCircuit(
        secretKey: ByteArray,
        address: String,
        circuitName: String,
        args: Array<Any?> = emptyArray(),
        onProgress: (String) -> Unit,
    ) {
        val midnightSdk = requireNotNull(sdk)
        val contract = createContractHandle(midnightSdk, secretKey, address)
        contract.call(circuitName, *args) { stage ->
            onProgress(stageLabel(stage))
        }
    }

    private suspend fun commitChoices(
        secretKey: ByteArray,
        address: String,
        choices: IntArray,
        nonce: ByteArray,
        onProgress: (String) -> Unit,
    ) {
        val midnightSdk = requireNotNull(sdk)
        val contract = createContractHandle(midnightSdk, secretKey, address, choices, nonce)
        contract.call("commitBatch") { stage ->
            onProgress(stageLabel(stage))
        }
    }

    private suspend fun revealChoices(
        secretKey: ByteArray,
        address: String,
        choices: IntArray,
        nonce: ByteArray,
        onProgress: (String) -> Unit,
    ) {
        val midnightSdk = requireNotNull(sdk)
        val contract = createContractHandle(midnightSdk, secretKey, address, choices, nonce)
        contract.call("revealBatch") { stage ->
            onProgress(stageLabel(stage))
        }
    }

    /**
     * Creates a contract handle with the given identity and optional choices/nonce.
     */
    private fun createContractHandle(
        midnightSdk: MidnightSdk,
        secretKey: ByteArray,
        address: String?,
        choices: IntArray? = null,
        nonce: ByteArray? = null,
        verifierKeys: Map<String, ByteArray>? = null,
    ): MidnightContract {
        val dummyNonce = ByteArray(NONCE_BYTES)

        return MidnightContract.create(midnightSdk.config) {
            name = "penalty"
            contractJs = context.assets.open("runtime/penalty-contract.js")
            if (address != null) this.address = address

            // Each witness returns a fresh ByteArray. The SDK zeroizes the
            // returned bytes after consumption (CircuitExecutor#registerWitnesses),
            // so callers' original arrays must not be exposed by reference.
            witness("localSecretKey") { WitnessResult(null, secretKey.copyOf()) }
            witness("localChoice0") { WitnessResult(null, byteArrayOf((choices?.get(0) ?: 0).toByte())) }
            witness("localChoice1") { WitnessResult(null, byteArrayOf((choices?.get(1) ?: 0).toByte())) }
            witness("localChoice2") { WitnessResult(null, byteArrayOf((choices?.get(2) ?: 0).toByte())) }
            witness("localChoice3") { WitnessResult(null, byteArrayOf((choices?.get(3) ?: 0).toByte())) }
            witness("localChoice4") { WitnessResult(null, byteArrayOf((choices?.get(4) ?: 0).toByte())) }
            witness("localNonce") { WitnessResult(null, (nonce ?: dummyNonce).copyOf()) }

            initialPrivateState = mapOf("secretKey" to secretKey.copyOf())
            coinPublicKey = midnightSdk.coinPublicKey
            if (verifierKeys != null) circuitVerifierKeys = verifierKeys
        }
    }

    fun close() {
        sdk?.close()
        sdk = null
        p1SecretKey.fill(0)
        p2SecretKey.fill(0)
    }

    // ── Internal ──

    private fun loadVerifierKeys(): Map<String, ByteArray> {
        val circuits = listOf("commitBatch", "revealBatch", "joinMatch", "cancelMatch", "claimTimeout")
        return circuits.associateWith { name ->
            context.assets.open("keys/$name.verifier").use { it.readBytes() }
        }
    }

    private fun installProvingKeys() {
        val keysDir = File(context.filesDir, "proving_keys")
        keysDir.mkdirs()
        File(keysDir, "zswap").mkdirs()
        File(keysDir, "dust").mkdirs()

        // BLS params from bboard temp dir
        val blsDir = File("/data/local/tmp/bboard_keys")
        listOf("bls_midnight_2p13", "bls_midnight_2p14", "bls_midnight_2p15").forEach { name ->
            val src = File(blsDir, name)
            val dst = File(keysDir, name)
            if (src.exists() && !dst.exists()) {
                src.copyTo(dst)
                Log.d(TAG, "Installed BLS: $name")
            }
        }

        // Wallet keys (zswap/dust) from pre-pushed temp dir
        val walletDir = File("/data/local/tmp/wallet_keys")
        listOf("zswap/spend", "zswap/output", "zswap/sign", "dust/spend").forEach { base ->
            listOf("prover", "verifier", "bzkir").forEach { ext ->
                val src = File(walletDir, "$base.$ext")
                val dst = File(keysDir, "$base.$ext")
                if (src.exists() && !dst.exists()) {
                    dst.parentFile?.mkdirs()
                    src.copyTo(dst)
                    Log.d(TAG, "Installed wallet key: $base.$ext")
                }
            }
        }

        // Contract proving keys from app assets
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

        val versionFile = File(keysDir, "version.txt")
        if (!versionFile.exists()) versionFile.writeText("9")
    }

    private fun stageLabel(stage: ContractCallStage): String = when (stage) {
        is ContractCallStage.FetchingState -> "Fetching state..."
        is ContractCallStage.Executing -> "Executing circuit..."
        is ContractCallStage.Proving -> "Generating ZK proof..."
        is ContractCallStage.Balancing -> "Balancing transaction..."
        is ContractCallStage.BalancingDetail -> "Balancing..."
        is ContractCallStage.Submitting -> "Submitting..."
    }

    companion object {
        private const val TAG = "MatchManager"
        private const val COMMIT_DEADLINE_DURATION_SECS = 300L
        private const val SECRET_KEY_BYTES = 32
        private const val NONCE_BYTES = 32
    }
}

/**
 * Result of a completed match — used to build the replay data for Unity.
 */
data class MatchResult(
    val playerChoices: IntArray,
    val aiChoices: IntArray,
    val contractAddress: String,
) {
    /** Build round results for Unity replay. */
    fun toRoundResults(): List<RoundResult> {
        return (0 until 5).map { i ->
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
