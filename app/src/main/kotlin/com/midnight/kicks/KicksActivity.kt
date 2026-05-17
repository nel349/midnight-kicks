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
    // Per-screen UX state for the create-and-go flow.
    private val creatingChecking = mutableStateOf(false)
    private val creatingStatus = mutableStateOf<String?>(null)
    // True if KicksSessionStore has an active match — surfaces the
    // RESUME MATCH affordance on the menu.
    private val hasActiveSession = mutableStateOf(false)
    private var matchManager: MatchManager? = null
    private val sessionStore by lazy { KicksSessionStore(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register for Unity bridge messages
        UnityBridge.onMessageFromUnity = { json ->
            runOnUiThread { handleUnityMessage(json) }
        }

        // Surface RESUME MATCH if a previous session is on disk. Independent
        // from any deep-link handling: even a cold launch with no intent
        // data should show the resume affordance if a session exists.
        hasActiveSession.value = sessionStore.load() != null

        handleDeepLink(intent)

        setContent {
            when (val s = screen.value) {
                KicksScreen.Menu -> KicksApp(
                    statusMessage = statusMessage.value,
                    lastChoices = lastChoices.value,
                    hasActiveSession = hasActiveSession.value,
                    onCreateMatch = ::startCreateMatch,
                    onJoinMatch = { screen.value = KicksScreen.Joining() },
                    onResumeMatch = ::resumeMatch,
                    onPracticeVsAi = { launchUnityChoicePhase() },
                )
                is KicksScreen.Creating -> CreateMatchScreen(
                    address = s.address,
                    checking = creatingChecking.value,
                    statusMessage = creatingStatus.value,
                    onBack = { screen.value = KicksScreen.Menu },
                    onCheckStatus = ::checkCreateStatus,
                )
                is KicksScreen.Joining -> JoinMatchScreen(
                    prefilledAddress = s.prefilledAddress,
                    inFlight = s.inFlight,
                    onBack = { screen.value = KicksScreen.Menu },
                    onJoin = ::startJoinMatch,
                )
                is KicksScreen.MatchReady -> MatchReadyScreen(
                    address = s.address,
                    role = s.role,
                    onBack = { screen.value = KicksScreen.Menu },
                    onContinue = {
                        Log.i(TAG, "CONTINUE: role=${s.role} address=${s.address} — PvP gameplay = step 3")
                        statusMessage.value = "Match ready as ${s.role}. PvP play orchestrator pending."
                        screen.value = KicksScreen.Menu
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    /**
     * P1 entry point — "create and go". Deploys a fresh penalty contract,
     * persists the session ([KicksSessionStore]) so it survives process
     * death, and renders the QR + COPY screen. **Does not block waiting
     * for the opponent** — matchmaking is async by nature, and pinning a
     * coroutine to this Activity's lifecycle would kill the wait the
     * moment the user backgrounds the app.
     *
     * The user shares the link, leaves the app, comes back via RESUME
     * MATCH or by tapping the launcher icon (the screen will be
     * re-rendered from the persisted session at that point). When ready,
     * they tap CHECK STATUS to one-shot poll the chain and advance to
     * [KicksScreen.MatchReady] if the opponent has joined.
     */
    private fun startCreateMatch() {
        screen.value = KicksScreen.Creating(address = null)
        creatingStatus.value = null
        ensureSdkReady {
            lifecycleScope.launch {
                try {
                    val manager = matchManager ?: return@launch
                    val address = manager.deployMatch()
                    Log.i(TAG, "Deployed match: $address")
                    val deadline = System.currentTimeMillis() / 1000 +
                        COMMIT_DEADLINE_DURATION_SECS
                    sessionStore.save(
                        MatchSession(address = address, role = Player.P1, deadline = deadline)
                    )
                    hasActiveSession.value = true
                    screen.value = KicksScreen.Creating(address = address)
                } catch (e: Exception) {
                    Log.e(TAG, "Create-match flow failed", e)
                    statusMessage.value = "Create failed: ${e.message}"
                    screen.value = KicksScreen.Menu
                }
            }
        }
    }

    /**
     * One-shot chain probe wired to the CHECK STATUS button. Short timeout
     * (it's a tap, not a background wait), so the user gets quick feedback
     * either way. `awaitOpponentJoin` is non-terminal on timeout — state
     * stays on [Deployed] so the user can tap again. If `matchJoined` is
     * true we route to [MatchReady].
     *
     * Same-process gotcha: if the user killed the app entirely after
     * deploy, [MatchManager] was destroyed and the secrets it generated
     * are gone, so this device can no longer act as P1 on the persisted
     * match. We detect this case (state is [Idle] but a session is on
     * disk) and surface a clear message — the proper fix is encrypted
     * key persistence (Block Store + sigil-style PRF), which is the same
     * Phase 4 follow-up as cross-device session sync.
     */
    private fun checkCreateStatus() {
        val s = screen.value as? KicksScreen.Creating ?: return
        val address = s.address ?: return
        creatingChecking.value = true
        creatingStatus.value = null
        lifecycleScope.launch {
            try {
                val manager = matchManager ?: return@launch
                // Cross-process resume isn't supported yet — see the
                // KDoc on this method. Detect and explain rather than
                // erroring opaquely from awaitOpponentJoin's precondition.
                if (manager.state.value !is MatchState.Deployed &&
                    manager.state.value !is MatchState.Joined
                ) {
                    creatingStatus.value =
                        "Match keys lost on app kill — re-deploy to play. (Cross-process resume coming.)"
                    return@launch
                }
                manager.awaitOpponentJoin(timeoutMs = CHECK_STATUS_TIMEOUT_MS)
                Log.i(TAG, "Opponent joined: $address")
                screen.value = KicksScreen.MatchReady(address, Player.P1)
            } catch (e: Exception) {
                Log.i(TAG, "Opponent not yet joined: ${e.message}")
                creatingStatus.value = "Still waiting — tap again in a moment."
            } finally {
                creatingChecking.value = false
            }
        }
    }

    /**
     * Resume the most recent persisted session. Reopens the right screen
     * based on [MatchSession.role]:
     *  - P1 → [KicksScreen.Creating] with the saved address; user can tap
     *    CHECK STATUS to see if the opponent has joined
     *  - P2 → [KicksScreen.Joining] with the address prefilled (in case
     *    the user backed out before tapping JOIN MATCH the first time)
     *
     * If the session is post-Joined (we're already past matchmaking), a
     * follow-up could check chain state here and jump straight to
     * [KicksScreen.MatchReady] or into gameplay. Today we just rehydrate
     * the matchmaking view.
     */
    private fun resumeMatch() {
        val session = sessionStore.load() ?: run {
            hasActiveSession.value = false
            return
        }
        Log.i(TAG, "Resuming session: address=${session.address.take(20)}… role=${session.role}")
        screen.value = when (session.role) {
            Player.P1 -> KicksScreen.Creating(address = session.address)
            Player.P2 -> KicksScreen.Joining(prefilledAddress = session.address)
        }
        // Make sure the SDK is bootstrapped so CHECK STATUS / JOIN can
        // make chain calls without an additional cold-start delay.
        ensureSdkReady { }
    }

    /**
     * P2 entry point. Connects to an existing deployed match by [address]
     * and submits the `joinMatch` circuit. Routes to [KicksScreen.MatchReady]
     * on success; PvP gameplay orchestrator (per-role commit/reveal) is
     * Phase 4 step 3.
     *
     * UI shows the "Joining…" in-flight state during the chain round-trip
     * via [KicksScreen.Joining.inFlight].
     */
    private fun startJoinMatch(address: String) {
        Log.i(TAG, "Join requested for address: $address")
        screen.value = KicksScreen.Joining(prefilledAddress = address, inFlight = true)
        ensureSdkReady {
            lifecycleScope.launch {
                try {
                    val manager = matchManager ?: return@launch
                    manager.joinAsP2(address)
                    Log.i(TAG, "Joined as P2: $address")
                    // Persist as P2 so the user can back out and resume.
                    // Deadline is informational only on the join side —
                    // P1 set the on-chain deadline at deploy time.
                    val deadline = System.currentTimeMillis() / 1000 +
                        COMMIT_DEADLINE_DURATION_SECS
                    sessionStore.save(
                        MatchSession(address = address, role = Player.P2, deadline = deadline)
                    )
                    hasActiveSession.value = true
                    screen.value = KicksScreen.MatchReady(address, Player.P2)
                } catch (e: Exception) {
                    Log.e(TAG, "joinAsP2 failed", e)
                    statusMessage.value = "Join failed: ${e.message}"
                    screen.value = KicksScreen.Joining(prefilledAddress = address, inFlight = false)
                }
            }
        }
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

        /**
         * Stored alongside the persisted [MatchSession] so the resume UI
         * can tell the user how much time is left on chain. Should match
         * `MatchManager.COMMIT_DEADLINE_DURATION_SECS`; duplicated here
         * because that value is `private` to MatchManager and exposing
         * it just for this caller isn't worth the API churn.
         */
        private const val COMMIT_DEADLINE_DURATION_SECS = 300L

        /**
         * One-shot "is opponent here yet?" probe. Short enough that a
         * tap on CHECK STATUS feels responsive even when the opponent
         * hasn't joined — the user doesn't want to stare at a spinner
         * during their own create-and-go flow. The chain poll inside
         * `awaitContractState` runs at 3s ticks, so 4s gives one real
         * shot at finding the joinMatch tx.
         */
        private const val CHECK_STATUS_TIMEOUT_MS = 4_000L
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
    hasActiveSession: Boolean,
    onCreateMatch: () -> Unit,
    onJoinMatch: () -> Unit,
    onResumeMatch: () -> Unit,
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

                if (hasActiveSession) {
                    MenuButton("RESUME MATCH", onClick = onResumeMatch)
                    Spacer(modifier = Modifier.height(16.dp))
                }
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
