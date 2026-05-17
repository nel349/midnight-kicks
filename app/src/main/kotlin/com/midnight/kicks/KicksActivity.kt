package com.midnight.kicks

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.midnight.example.common.PanelBar
import com.midnight.kuira.core.network.MidnightNetwork
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Main activity for Midnight Kicks.
 *
 * Hosts the Compose menu UI (match creation, joining, results) and launches
 * Unity (via UaaL) for the 3D game portions.
 *
 * Flow: Menu → launch UnityPlayerGameActivity → Unity shows choice UI →
 * player picks 5 directions → Unity sends choicesLocked → back to Kotlin.
 *
 * All coroutines run on [lifecycleScope] so they cancel automatically on
 * Activity destruction. The owned [MatchManager] is closed in [onDestroy]
 * to release SDK / FFI resources and wipe key material.
 */
class KicksActivity : FragmentActivity() {

    private val statusMessage = mutableStateOf<String?>(null)
    private val lastChoices = mutableStateOf<String?>(null)
    // Top-level nav. State-based so we don't need Navigation-Compose for
    // three screens. handleDeepLink() / button onClicks mutate this; the
    // setContent { } block switches on it.
    private val screen = mutableStateOf<KicksScreen>(KicksScreen.Menu)
    private var matchManager: MatchManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register for Unity bridge messages
        UnityBridge.onMessageFromUnity = { json ->
            runOnUiThread { handleUnityMessage(json) }
        }

        handleDeepLink(intent)

        setContent {
            when (val s = screen.value) {
                KicksScreen.Menu -> KicksApp(
                    statusMessage = statusMessage.value,
                    lastChoices = lastChoices.value,
                    onCreateMatch = ::startCreateMatch,
                    onJoinMatch = { screen.value = KicksScreen.Joining() },
                    onPracticeVsAi = { launchUnityChoicePhase() },
                )
                is KicksScreen.Creating -> CreateMatchScreen(
                    address = s.address,
                    onBack = { screen.value = KicksScreen.Menu },
                )
                is KicksScreen.Joining -> JoinMatchScreen(
                    prefilledAddress = s.prefilledAddress,
                    onBack = { screen.value = KicksScreen.Menu },
                    onJoin = ::startJoinMatch,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    /**
     * P1 entry point. Switches to [KicksScreen.Creating] in the deploying
     * state, then asynchronously deploys the penalty contract and updates
     * the screen with the resulting address. Wait-for-opponent + Unity
     * handoff is Phase 4 step 2.
     */
    private fun startCreateMatch() {
        screen.value = KicksScreen.Creating(address = null)
        ensureSdkReady {
            lifecycleScope.launch {
                try {
                    val manager = matchManager ?: return@launch
                    val address = manager.deployMatch()
                    Log.i(TAG, "Deployed match: $address")
                    screen.value = KicksScreen.Creating(address = address)
                } catch (e: Exception) {
                    Log.e(TAG, "deployMatch failed", e)
                    statusMessage.value = "Deploy failed: ${e.message}"
                    screen.value = KicksScreen.Menu
                }
            }
        }
    }

    /**
     * P2 entry point. Phase 4 step 2 will plumb this into
     * `MatchManager.joinAsP2(address)` and transition into the Unity
     * choice phase on success. For now we log + bounce back to the menu
     * so the matchmaking nav can be tested in isolation.
     */
    private fun startJoinMatch(address: String) {
        Log.i(TAG, "Join requested for address: $address")
        statusMessage.value = "Join not wired yet — Phase 4 step 2 (chain logic)"
        screen.value = KicksScreen.Menu
    }

    override fun onDestroy() {
        UnityBridge.onMessageFromUnity = null
        // MatchManager owns the SDK + the StatePoller + secret keys —
        // closing it releases native FFI resources, the indexer websocket,
        // and wipes key material. The lifecycleScope cancels its own
        // coroutines automatically, but the SDK isn't lifecycle-aware.
        matchManager?.close()
        matchManager = null
        super.onDestroy()
    }

    /**
     * Lazy-init the [MatchManager] and bind its StateFlows to the UI.
     * Re-entrant: subsequent calls are no-ops once [matchManager] is set.
     */
    private fun ensureSdkReady(onReady: () -> Unit) {
        lifecycleScope.launch {
            try {
                if (matchManager == null) {
                    val manager = MatchManager(
                        context = applicationContext,
                        network = MidnightNetwork.UNDEPLOYED,
                        seed = TEST_SEED,
                    )
                    // Bind statusMessage to the SDK's published state — this
                    // is the canonical way to surface progress in a Kuira dApp.
                    // One StateFlow drives every label the user sees.
                    lifecycleScope.launch {
                        manager.state.collect { statusMessage.value = it.label }
                    }
                    // Observe on-chain state — demonstrates the same
                    // collect-StateFlow pattern that BlockStore snapshots
                    // and orchestrator waitForP2* hooks will use next.
                    lifecycleScope.launch {
                        manager.contractState.collect { snap ->
                            if (snap != null) Log.i(TAG, "chain: ${snap.summary()}")
                        }
                    }
                    manager.initSdk()
                    matchManager = manager
                }
                onReady()
            } catch (e: Exception) {
                Log.e(TAG, "SDK init failed", e)
                statusMessage.value = "Error: ${e.message}"
            }
        }
    }

    private fun launchUnityChoicePhase() {
        ensureSdkReady {
            statusMessage.value = "Pick your 5 directions!"
            Log.i(TAG, "Launching Unity for choice phase...")

            val intent = Intent(this@KicksActivity, com.unity3d.player.UnityPlayerGameActivity::class.java)
            startActivity(intent)

            // Wait briefly for Unity's Activity to come up before sending
            // the first bridge message — UnityPlayer needs to be alive to
            // receive it. Coroutine + delay is the canonical idiom; the
            // old `decorView.postDelayed` worked but didn't share the
            // activity's cancellation scope.
            lifecycleScope.launch {
                delay(UNITY_BOOT_DELAY_MS)
                UnityBridge.sendChoicePhase(round = "regulation", playerRole = "shooter")
            }
        }
    }

    private fun handleUnityMessage(jsonString: String) {
        Log.i(TAG, "From Unity: $jsonString")

        try {
            val json = JSONObject(jsonString)
            when (json.getString("type")) {
                "choicesLocked" -> handleChoicesLocked(json)
                "replayComplete" -> handleReplayComplete()
                "matchPaused" -> handleMatchPaused()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Unity message: ${e.message}")
        }
    }

    /**
     * Match paused from the Unity HUD. Unity has already called
     * `currentActivity.finish()` on its side, so the user is back on this
     * Activity with the wallet + sigil pills at the top. We just update
     * the status line so they know what state they're in.
     */
    private fun handleMatchPaused() {
        statusMessage.value = "Paused — tap CREATE MATCH to start a new match"
    }

    private fun handleChoicesLocked(json: JSONObject) {
        val choices = json.getJSONArray("choices")
        val choiceList = (0 until choices.length()).map { choices.getInt(it) }
        val labels = choiceList.map(::directionLabel)

        Log.i(TAG, "Player choices: $labels")
        lastChoices.value = "You picked: ${labels.joinToString(" ")}"

        // No progress callback needed — `manager.state` is already bound
        // to statusMessage via the state collector in ensureSdkReady().
        lifecycleScope.launch {
            // Guard against a race where Unity returns choicesLocked
            // before ensureSdkReady has finished assigning matchManager.
            val manager = matchManager ?: run {
                Log.e(TAG, "choicesLocked received before MatchManager was ready")
                statusMessage.value = "Not ready yet — try again"
                return@launch
            }

            try {
                val result = manager.playAgainstAi(choiceList.toIntArray())

                val (p1Score, p2Score) = result.scores()
                val winner = when {
                    p1Score > p2Score -> "P1"
                    p2Score > p1Score -> "P2"
                    else -> null
                }

                Log.i(TAG, "Match result: P1=$p1Score P2=$p2Score winner=$winner")
                statusMessage.value = "You $p1Score - $p2Score AI"

                val aiLabels = result.aiChoices.map(::directionLabel)
                lastChoices.value = "You: ${labels.joinToString(" ")}  AI: ${aiLabels.joinToString(" ")}"

                UnityBridge.sendReplay(
                    rounds = result.toRoundResults(),
                    p1Score = p1Score,
                    p2Score = p2Score,
                    winner = winner,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Match failed", e)
                statusMessage.value = "Match failed: ${e.message}"
            }
        }
    }

    private fun handleReplayComplete() {
        Log.i(TAG, "Replay finished")
        val result = matchManager?.lastResult ?: return
        val (p1, p2) = result.scores()
        val winText = when {
            p1 > p2 -> "YOU WIN!"
            p2 > p1 -> "AI WINS"
            else -> "DRAW"
        }
        statusMessage.value = "$winText  ($p1 - $p2)"
    }

    /**
     * Deep link handler — `midnight://kicks?match=<address>` routes the
     * user straight into [JoinMatchScreen] with the address prefilled.
     * Called from both `onCreate` (cold start via tap) and `onNewIntent`
     * (warm hit when the activity is already foreground).
     */
    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "midnight" && uri.host == "kicks") {
            val matchAddress = uri.getQueryParameter("match")
            val network = uri.getQueryParameter("network") ?: "undeployed"
            if (matchAddress != null) {
                Log.i(TAG, "Deep link: match=$matchAddress, network=$network")
                screen.value = KicksScreen.Joining(prefilledAddress = matchAddress)
            }
        }
    }

    companion object {
        private const val TAG = "Kicks"

        /** How long to wait for UnityPlayerGameActivity to come up before sending the first bridge message. */
        private const val UNITY_BOOT_DELAY_MS = 2_000L
    }
}

private fun directionLabel(d: Int): String = when (d) {
    0 -> "L"
    1 -> "C"
    2 -> "R"
    else -> "?"
}

@Composable
fun KicksApp(
    statusMessage: String?,
    lastChoices: String?,
    onCreateMatch: () -> Unit,
    onJoinMatch: () -> Unit,
    onPracticeVsAi: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0A0A0A),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            PanelBar(network = MidnightNetwork.UNDEPLOYED)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "MIDNIGHT",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 14.sp,
                    letterSpacing = 6.sp,
                )
                Text(
                    "KICKS",
                    color = Color.White,
                    fontSize = 48.sp,
                    fontWeight = FontWeight.W200,
                    letterSpacing = 8.sp,
                )

                Spacer(modifier = Modifier.height(64.dp))

                MenuButton("CREATE MATCH", onClick = onCreateMatch)
                Spacer(modifier = Modifier.height(16.dp))
                MenuButton("JOIN MATCH", onClick = onJoinMatch)
                Spacer(modifier = Modifier.height(28.dp))
                // Dev affordance — kept accessible while Phase 4 PvP
                // chain logic is being plumbed. Drop it (or hide behind
                // a long-press / debug build) once two-emulator E2E is
                // wired through CREATE / JOIN.
                Text(
                    "PRACTICE VS AI",
                    color = Color.White.copy(alpha = 0.3f),
                    fontSize = 11.sp,
                    letterSpacing = 3.sp,
                    modifier = Modifier
                        .clickable(onClick = onPracticeVsAi)
                        .padding(8.dp),
                )

                if (statusMessage != null || lastChoices != null) {
                    Spacer(modifier = Modifier.height(48.dp))
                    if (lastChoices != null) {
                        Text(
                            lastChoices,
                            color = Color(0xFF64B5F6),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 4.sp,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    if (statusMessage != null) {
                        Text(
                            statusMessage,
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(48.dp))
                Text(
                    "World Cup 2026",
                    color = Color.White.copy(alpha = 0.2f),
                    fontSize = 12.sp,
                    letterSpacing = 4.sp,
                )
            }
        }
    }
}

/** Test seed — same as BBoard's alice wallet. */
private val TEST_SEED = hexToBytes(
    "7dc468f62278cd0c14b6674f31531a90b64599d657d3c7ab2adb63395d647f7a" +
    "505de6428fcf8b0d208873f4d5e2a1340c14688067477542f53c48dfea817da4"
)

private fun hexToBytes(hex: String): ByteArray =
    ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

@Composable
private fun MenuButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 3.sp,
        )
    }
}
