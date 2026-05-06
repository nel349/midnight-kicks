package com.midnight.kicks

import android.content.Context
import android.util.Log
import com.midnight.kuira.core.compact.ContractCallStage
import com.midnight.kuira.core.compact.MidnightContract
import com.midnight.kuira.core.compact.WitnessResult
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.sdk.MidnightSdk
import java.io.File
import java.security.SecureRandom

/**
 * Manages the penalty match lifecycle — deploy, join, commit, reveal, claim.
 *
 * Wraps MidnightSdk and MidnightContract to provide match-specific operations.
 * Each match is a new contract instance deployed on-chain.
 */
class MatchManager(
    private val context: Context,
    private val network: MidnightNetwork,
    private val seed: ByteArray,
) {
    private var sdk: MidnightSdk? = null
    private var contract: MidnightContract? = null
    private var contractAddress: String? = null

    // Match state
    private var secretKey: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private var currentChoices: IntArray? = null
    private var currentNonce: ByteArray? = null

    /**
     * Initialize the SDK (derive keys, set up wallet).
     */
    suspend fun initSdk(onProgress: (String) -> Unit) {
        onProgress("Initializing SDK...")
        installProvingKeys()

        val midnightSdk = MidnightSdk.Builder(context)
            .network(network)
            .seed(seed)
            .build()
        sdk = midnightSdk
        Log.i(TAG, "SDK initialized, wallet address: ${midnightSdk.walletAddress}")

        // Download wallet proving keys if needed (dust/zswap provers)
        if (!midnightSdk.provingKeyManager.hasWalletKeys()) {
            onProgress("Downloading wallet proving keys...")
            midnightSdk.provingKeyManager.downloadWalletKeys { progress ->
                onProgress("Downloading keys: ${(progress * 100).toInt()}%")
            }
        }
    }

    /**
     * Deploy a new penalty contract (create match).
     *
     * @return Contract address of the deployed match
     */
    suspend fun createMatch(onProgress: (String) -> Unit): String {
        val midnightSdk = requireNotNull(sdk) { "Call initSdk first" }
        onProgress("Deploying penalty contract...")

        val verifierKeys = loadVerifierKeys()

        val dummyChoice = byteArrayOf(0)
        val dummyNonce = ByteArray(32)

        val penaltyContract = MidnightContract.create(midnightSdk.config) {
            name = "penalty"
            contractJs = context.assets.open("runtime/penalty-contract.js")
            witness("localSecretKey") { WitnessResult(null, secretKey.copyOf()) }
            witness("localChoice0") { WitnessResult(null, dummyChoice.copyOf()) }
            witness("localChoice1") { WitnessResult(null, dummyChoice.copyOf()) }
            witness("localChoice2") { WitnessResult(null, dummyChoice.copyOf()) }
            witness("localChoice3") { WitnessResult(null, dummyChoice.copyOf()) }
            witness("localChoice4") { WitnessResult(null, dummyChoice.copyOf()) }
            witness("localNonce") { WitnessResult(null, dummyNonce.copyOf()) }
            initialPrivateState = mapOf(
                "secretKey" to secretKey.copyOf(),
            )
            coinPublicKey = midnightSdk.coinPublicKey
            circuitVerifierKeys = verifierKeys
        }

        val result = penaltyContract.deploy { stage ->
            val label = stageLabel(stage)
            Log.i(TAG, "Deploy: $label")
            onProgress(label)
        }

        contractAddress = result.contractAddress
        contract = penaltyContract
        Log.i(TAG, "Match deployed at: ${result.contractAddress}")
        onProgress("Match deployed!")

        return result.contractAddress
    }

    /**
     * Connect to an existing match (join).
     */
    suspend fun joinMatch(address: String, onProgress: (String) -> Unit) {
        val midnightSdk = requireNotNull(sdk) { "Call initSdk first" }
        contractAddress = address

        onProgress("Connecting to match...")
        val penaltyContract = MidnightContract.create(midnightSdk.config) {
            name = "penalty"
            contractJs = context.assets.open("runtime/penalty-contract.js")
            this.address = address
            witness("localSecretKey") { WitnessResult(null, secretKey.copyOf()) }
            initialPrivateState = mapOf("secretKey" to secretKey.copyOf())
            coinPublicKey = midnightSdk.coinPublicKey
        }
        contract = penaltyContract

        onProgress("Joining match...")
        penaltyContract.call("joinMatch", COMMIT_DEADLINE_SECS) { stage ->
            onProgress(stageLabel(stage))
        }
        Log.i(TAG, "Joined match at: $address")
        onProgress("Joined match!")
    }

    /**
     * Commit a batch of 5 choices to the contract.
     *
     * @param choices 5 direction values (0=left, 1=center, 2=right)
     */
    suspend fun commitBatch(choices: IntArray, onProgress: (String) -> Unit) {
        require(choices.size == 5) { "Must provide exactly 5 choices" }
        val penaltyContract = requireNotNull(contract) { "No active match" }
        val midnightSdk = requireNotNull(sdk)

        currentChoices = choices.copyOf()
        val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
        currentNonce = nonce

        Log.i(TAG, "Committing choices: ${choices.toList()}, nonce: ${nonce.take(8).joinToString("") { "%02x".format(it) }}...")

        // Rebuild contract with choice + nonce witnesses for this commit
        val commitContract = MidnightContract.create(midnightSdk.config) {
            name = "penalty"
            contractJs = context.assets.open("runtime/penalty-contract.js")
            address = contractAddress
            witness("localSecretKey") { WitnessResult(null, secretKey.copyOf()) }
            witness("localChoice0") { WitnessResult(null, byteArrayOf(choices[0].toByte())) }
            witness("localChoice1") { WitnessResult(null, byteArrayOf(choices[1].toByte())) }
            witness("localChoice2") { WitnessResult(null, byteArrayOf(choices[2].toByte())) }
            witness("localChoice3") { WitnessResult(null, byteArrayOf(choices[3].toByte())) }
            witness("localChoice4") { WitnessResult(null, byteArrayOf(choices[4].toByte())) }
            witness("localNonce") { WitnessResult(null, nonce) }
            initialPrivateState = mapOf(
                "secretKey" to secretKey.copyOf(),
            )
            coinPublicKey = midnightSdk.coinPublicKey
        }

        onProgress("Committing choices...")
        commitContract.call("commitBatch") { stage ->
            val label = stageLabel(stage)
            Log.i(TAG, "Commit: $label")
            onProgress(label)
        }
        Log.i(TAG, "Batch committed!")
        onProgress("Choices committed on-chain!")
    }

    /**
     * Reveal previously committed choices.
     */
    suspend fun revealBatch(onProgress: (String) -> Unit) {
        val midnightSdk = requireNotNull(sdk)
        val choices = requireNotNull(currentChoices) { "No choices to reveal" }
        val nonce = requireNotNull(currentNonce) { "No nonce to reveal" }

        val revealContract = MidnightContract.create(midnightSdk.config) {
            name = "penalty"
            contractJs = context.assets.open("runtime/penalty-contract.js")
            address = contractAddress
            witness("localSecretKey") { WitnessResult(null, secretKey.copyOf()) }
            witness("localChoice0") { WitnessResult(null, byteArrayOf(choices[0].toByte())) }
            witness("localChoice1") { WitnessResult(null, byteArrayOf(choices[1].toByte())) }
            witness("localChoice2") { WitnessResult(null, byteArrayOf(choices[2].toByte())) }
            witness("localChoice3") { WitnessResult(null, byteArrayOf(choices[3].toByte())) }
            witness("localChoice4") { WitnessResult(null, byteArrayOf(choices[4].toByte())) }
            witness("localNonce") { WitnessResult(null, nonce) }
            initialPrivateState = mapOf(
                "secretKey" to secretKey.copyOf(),
            )
            coinPublicKey = midnightSdk.coinPublicKey
        }

        onProgress("Revealing choices...")
        revealContract.call("revealBatch") { stage ->
            onProgress(stageLabel(stage))
        }
        Log.i(TAG, "Batch revealed!")
        onProgress("Choices revealed!")
    }

    fun close() {
        sdk?.close()
        sdk = null
        secretKey.fill(0)
    }

    // ── Internal ──

    private fun loadVerifierKeys(): Map<String, ByteArray> {
        val circuits = listOf("commitBatch", "revealBatch", "joinMatch", "cancelMatch", "claimTimeout")
        return circuits.associateWith { name ->
            context.assets.open("keys/$name.verifier").use { it.readBytes() }
        }
    }

    private fun installProvingKeys() {
        val tempDir = File("/data/local/tmp/bboard_keys")
        val keysDir = File(context.filesDir, "proving_keys")
        keysDir.mkdirs()

        // Copy BLS params from temp (shared with BBoard)
        listOf("bls_midnight_2p13", "bls_midnight_2p14", "bls_midnight_2p15").forEach { name ->
            val src = File(tempDir, name)
            val dst = File(keysDir, name)
            if (src.exists() && !dst.exists()) {
                src.copyTo(dst)
                Log.d(TAG, "Installed BLS: $name")
            }
        }

        // Copy penalty proving keys from assets
        val assetKeys = context.assets.list("keys") ?: emptyArray()
        assetKeys.filter { it.endsWith(".prover") || it.endsWith(".bzkir") }.forEach { name ->
            val dst = File(keysDir, name)
            if (!dst.exists()) {
                context.assets.open("keys/$name").use { input ->
                    dst.outputStream().use { output -> input.copyTo(output) }
                }
                Log.d(TAG, "Installed key: $name")
            }
        }

        // Write version.txt
        val versionFile = File(keysDir, "version.txt")
        if (!versionFile.exists()) {
            versionFile.writeText("9")
        }
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
        private const val COMMIT_DEADLINE_SECS = 300L // 5 minutes
    }
}
