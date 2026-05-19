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
import com.midnight.kuira.dapp.PanelBar
import com.midnight.kuira.core.network.MidnightNetwork
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
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
@AndroidEntryPoint
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
    // True if [store] has any active matches — surfaces the
    // RESUME MATCH affordance on the menu.
    private val hasActiveSession = mutableStateOf(false)
    private var matchManager: MatchManager? = null
    /**
     * Unified match store, shared with the [MatchManager] this Activity
     * constructs. Activity-side reads it for the isResume gate +
     * `hasActiveSession` indicator; MatchManager writes it on every
     * deploy/join/commit/SD success. Hilt-provided as a Singleton via
     * `KicksStorageModule.provideMatchStore`, so the same instance
     * flows through to `MatchStoreBackupProvider` (which the sigil
     * panel discovers via Optional injection for cloud backup).
     */
    @Inject lateinit var store: MatchStore
    /**
     * Role this device is playing for the current Unity choice phase.
     * `null` → PvAI (legacy practice mode). Drives the dispatch in
     * [handleChoicesLocked]. Set when launching Unity from
     * [KicksScreen.MatchReady]; cleared in [handleReplayComplete].
     */
    private var currentRole: Player? = null

    /**
     * Role array last sent to Unity via [UnityBridge.sendChoicePhase].
     * Cached here so [handleChoicesLocked] can bucket the returned picks
     * back into shoots vs keeps using the same per-index role labels Unity
     * displayed. Cleared after each match.
     */
    private var currentChoiceRoles: List<String> = emptyList()

    /**
     * When non-null, [handleChoicesLocked] routes the returned picks to
     * this deferred instead of starting a new match — the orchestrator's
     * SD loop is suspended on it via [gatherSdPicksFromUi]. Set inside
     * the SD callback; cleared as soon as the deferred completes.
     */
    private var pendingSdPicks: CompletableDeferred<Pair<Int, Int>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register for Unity bridge messages
        UnityBridge.onMessageFromUnity = { json ->
            runOnUiThread { handleUnityMessage(json) }
        }

        // Surface RESUME MATCH if a previous session is on disk. Independent
        // from any deep-link handling: even a cold launch with no intent
        // data should show the resume affordance if a session exists.
        hasActiveSession.value = store.loadAll().isNotEmpty()

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
                    onPracticeVsAi = {
                        currentRole = null
                        launchUnityChoicePhase()
                    },
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
                        currentRole = s.role
                        Log.i(TAG, "CONTINUE: role=${s.role} address=${s.address} → launching Unity")
                        launchUnityChoicePhase()
                    },
                )
                KicksScreen.Resume -> ResumeScreen(
                    // Read the store on every recomposition so a Backup +
                    // Restore round-trip that adds matches while the user
                    // is on this screen surfaces them without a navigate-
                    // away/back dance.
                    matches = store.loadAll(),
                    onBack = { screen.value = KicksScreen.Menu },
                    onMatchSelected = ::resumeIntoMatch,
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
     * persists the match ([MatchStore]) so it survives process
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
                    // MatchManager.deployMatch already saved the new
                    // match to [store] (role + secret key + deadline).
                    // Just refresh the affordance flag + update the UI.
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
                // After app kill + resume, state should land at Deployed
                // (or further) for the active match. If we somehow end up
                // here with an earlier state, the resume path missed
                // something — surface a clear hint instead of letting
                // awaitOpponentJoin's precondition assertion blow up.
                if (manager.state.value !is MatchState.Deployed &&
                    manager.state.value !is MatchState.Joined
                ) {
                    creatingStatus.value =
                        "No active match in memory — go back and create one."
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
     * Open the resume picker. Multi-match aware: the picker lists every
     * entry in [MatchStore] (`Player.P1` or `Player.P2`) and the user
     * taps the one they want to engage with. Single-match users still
     * tap once — same affordance, no special-cased "auto-route" path
     * that would have silently picked among multiple matches.
     *
     * The picker re-reads the store on every recomposition, so a
     * Backup-button-then-Restore round-trip (Commit 2) that adds matches
     * while the picker is up surfaces them without a navigate-away.
     */
    private fun resumeMatch() {
        if (store.loadAll().isEmpty()) {
            // Defensive — the RESUME button on the menu only renders when
            // hasActiveSession is true, so this branch shouldn't fire in
            // practice. Stay on Menu, refresh the flag, and ignore the tap.
            hasActiveSession.value = false
            return
        }
        screen.value = KicksScreen.Resume
    }

    /**
     * Per-match destination router from [KicksScreen.Resume]. Decides
     * which screen the picked [match] should land on based on its role:
     *  - P1 → [KicksScreen.Creating] with the saved address; user can tap
     *    CHECK STATUS to see if the opponent has joined
     *  - P2 → [KicksScreen.Joining] with the address prefilled (in case
     *    the user backed out before tapping JOIN MATCH the first time)
     *
     * Post-matchmaking routing (jump straight to [KicksScreen.MatchReady]
     * when the chain phase is already past join) is a follow-up — would
     * need a chain query here, kept out of Commit 3 to avoid blocking
     * the navigate-into-resume tap on a network round-trip. The
     * destination screen's existing CHECK STATUS / JOIN button advances
     * the state machine instead.
     */
    private fun resumeIntoMatch(match: MatchStore.Match) {
        Log.i(TAG, "Resuming into match: address=${match.address.take(20)}… role=${match.role}")
        screen.value = when (match.role) {
            Player.P1 -> KicksScreen.Creating(address = match.address)
            Player.P2 -> KicksScreen.Joining(prefilledAddress = match.address)
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
    /**
     * P2 entry point. Treats the call as a resume if `MatchStore`
     * already has a P2 row for this exact address — that's how we
     * distinguish a legitimate rejoin (same device, after backing out)
     * from a stranger who got the deep link. See `MatchManager.joinAsP2`
     * KDoc for the security rationale.
     */
    private fun startJoinMatch(address: String) {
        // Wrong-actor gate (from MatchManager.joinAsP2 KDoc): a saved
        // P2 record for this exact address means we've successfully
        // joined before — treat the contract's "already joined" assert
        // as a no-op resume. No saved record for this address (or one
        // with role=P1) means we're a stranger to this match.
        val saved = store.load(address)
        val isResume = saved?.role == Player.P2
        Log.i(TAG, "Join requested for address: $address  isResume=$isResume")
        screen.value = KicksScreen.Joining(prefilledAddress = address, inFlight = true)
        ensureSdkReady {
            lifecycleScope.launch {
                try {
                    val manager = matchManager ?: return@launch
                    manager.joinAsP2(address, isResume = isResume)
                    Log.i(TAG, "Joined as P2: $address")
                    // MatchManager.joinAsP2 already saved the match
                    // record to [store]. Refresh the affordance flag +
                    // route the UI.
                    hasActiveSession.value = true
                    screen.value = KicksScreen.MatchReady(address, Player.P2)
                } catch (e: MatchAlreadyJoinedException) {
                    // Wrong actor — contract is past WAITING and we
                    // have no local session for this match. Don't fake
                    // success; the user's commit would fail at chain
                    // time anyway because their p2SecretKey doesn't
                    // match the on-chain P2 pubkey.
                    Log.w(TAG, "joinAsP2 refused: another player already joined this match")
                    statusMessage.value =
                        "This match is already in progress with another player."
                    screen.value = KicksScreen.Joining(
                        prefilledAddress = address,
                        inFlight = false,
                    )
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
                        // Pass the Hilt-singleton MatchStore so both
                        // KicksActivity and MatchManager share one
                        // instance — the same one MatchStoreBackupProvider
                        // reads from for cloud backup. Without this,
                        // MatchManager would construct its own
                        // EncryptedSharedPreferences handle and a save
                        // here wouldn't be visible to the Activity-side
                        // read (or to the backup pipeline).
                        store = store,
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
                    // After initSdk lands in SdkReady, try to resume any
                    // active match the user left behind on a prior kill.
                    // Returns the resumed contract address (and transitions
                    // the state machine into the right MatchState) when
                    // there was something to resume; null on a fresh launch.
                    val resumedAddress = manager.tryResumeActiveMatch()
                    if (resumedAddress != null) {
                        // Route the UI into the create-match screen so the
                        // user lands back on their pending match instead of
                        // the menu. CreateMatchScreen renders the
                        // address-aware actions for whichever phase the
                        // state machine ended up in.
                        Log.i(TAG, "Resumed active match: $resumedAddress")
                        screen.value = KicksScreen.Creating(resumedAddress)
                    }
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
                // Per-round role from this device's perspective. The
                // contract's `i % 2 == 0 → P1 shoots` rule means P1 sees
                // shoot/keep/shoot/keep/shoot and P2 sees keep/shoot/keep/
                // shoot/keep. PvAI uses the P1 pattern since the human is
                // always P1 in that flow. Unity uses this to label each
                // pick "YOU SHOOT" / "YOU KEEP".
                val roles = rolesForCurrentDevice()
                currentChoiceRoles = roles
                UnityBridge.sendChoicePhase(round = "regulation", roles = roles)
            }
        }
    }

    /**
     * Per-round role array for this device, V3 regulation (10 rounds).
     * Aligns with `MatchManager.toRoundResults`'s `i % 2 == 0 → P1 shoots`
     * rule and the contract's pairing model (5 P1-kicks + 5 P2-kicks).
     *
     *  - PvAI (currentRole=null): human is always P1
     *    → [S,K,S,K,S,K,S,K,S,K]
     *  - PvP as P1: same as PvAI (P1 shoots rounds 1,3,5,7,9)
     *  - PvP as P2: flipped (P2 shoots rounds 2,4,6,8,10)
     *    → [K,S,K,S,K,S,K,S,K,S]
     *
     * Centralised so the pattern stays in lockstep with the contract; the
     * matching bucketing logic in [handleChoicesLocked] uses the same array
     * to split returned picks into shoots[5] + keeps[5].
     */
    private fun rolesForCurrentDevice(): List<String> {
        // 10 entries, alternating starting with role-of-round-1 for this device.
        val rounds = MatchManager.REGULATION_ROUNDS
        val p1StartsShoot = currentRole != Player.P2
        return List(rounds) { i ->
            // round (i+1) parity: odd → P1 shoots, even → P2 shoots.
            val p1ShootsThisRound = i % 2 == 0
            val thisDeviceShoots = if (p1StartsShoot) p1ShootsThisRound else !p1ShootsThisRound
            if (thisDeviceShoots) "shoot" else "keep"
        }
    }

    /**
     * Bucket Unity's 2 SD picks into (shoot, keep) using the role array
     * we sent, and resume the orchestrator via [pendingSdPicks].
     * Tolerates a pre-V3 Unity APK by treating a 1-pick reply as shoot
     * and falling back to 0 for the missing keep.
     */
    private fun handleSdChoicesLocked(
        deferred: CompletableDeferred<Pair<Int, Int>>,
        picks: IntArray,
    ) {
        val roles = currentChoiceRoles
        var shoot = 0
        var keep  = 0
        if (picks.size == 2 && roles.size == 2) {
            roles.forEachIndexed { i, role ->
                if (role == "shoot") shoot = picks[i] else keep = picks[i]
            }
        } else {
            Log.w(
                TAG,
                "SD choicesLocked returned ${picks.size} picks (expected 2). " +
                    "Re-export Unity for V3 SD UI. Falling back to first/second pick.",
            )
            shoot = picks.getOrElse(0) { 0 }
            keep  = picks.getOrElse(1) { 0 }
        }
        Log.i(TAG, "SD pick → shoot=$shoot keep=$keep")
        deferred.complete(shoot to keep)
    }

    /**
     * Orchestrator callback for SD picks. Sends a 2-pick choicePhase to
     * Unity (`roles = ["shoot","keep"]`) and suspends until Unity returns
     * `choicesLocked`. The 2 picks are bucketed back into (shoot, keep)
     * by role index. Round number is surfaced via [statusMessage] so the
     * player sees "Sudden death — round N" while Unity boots the panel.
     *
     * Cancellation safety: if the coroutine is cancelled while we're
     * waiting, the deferred is cancelled in the `finally` and the next
     * orchestrator call starts clean.
     */
    private suspend fun gatherSdPicksFromUi(round: Int): Pair<Int, Int> {
        val roles = listOf("shoot", "keep")
        val deferred = CompletableDeferred<Pair<Int, Int>>()
        pendingSdPicks = deferred
        currentChoiceRoles = roles
        runOnUiThread {
            statusMessage.value = "Sudden death — round $round"
            UnityBridge.sendChoicePhase(round = "suddenDeath", roles = roles)
        }
        return try {
            deferred.await()
        } finally {
            // Clear regardless of outcome (complete / cancel / exception)
            // so a stale deferred doesn't intercept the next regulation
            // choicesLocked.
            if (pendingSdPicks === deferred) pendingSdPicks = null
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

        // SD branch — if the orchestrator is suspended waiting on SD
        // picks (via [gatherSdPicksFromUi]'s deferred), bucket the 2
        // returned picks into (shoot, keep) using the role array we
        // sent on the way out, then resume the orchestrator. Don't run
        // the regulation start-a-match flow below.
        val sdDeferred = pendingSdPicks
        if (sdDeferred != null) {
            handleSdChoicesLocked(sdDeferred, choiceList.toIntArray())
            return
        }

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
                // Bucket Unity's 10 picks into shoots[5] + keeps[5] using
                // the per-index role array we sent on the way out. Walk
                // both in lockstep: role[i] == "shoot" means picks[i] is
                // the next shoots[] entry; "keep" → keeps[].
                //
                // Legacy fallback: if Unity returns only 5 picks, this
                // device is running a pre-V3 export of the Unity APK that
                // hardcoded 5-pick gathering. Re-use the 5 picks as both
                // shoots and keeps (degenerate but lets PvAI still run
                // end-to-end) and log the mismatch so we know to re-export.
                val picks  = choiceList.toIntArray()
                val roles  = currentChoiceRoles
                val (shoots, keeps) = if (picks.size == roles.size && picks.size == 10) {
                    val s = mutableListOf<Int>()
                    val k = mutableListOf<Int>()
                    roles.forEachIndexed { i, role ->
                        if (role == "shoot") s += picks[i] else k += picks[i]
                    }
                    s.toIntArray() to k.toIntArray()
                } else {
                    Log.w(
                        TAG,
                        "choicesLocked returned ${picks.size} picks (expected 10). " +
                            "Unity APK is pre-V3 — re-export needed for proper 10-pick flow. " +
                            "Falling back to shoots == keeps.",
                    )
                    val legacy = picks.copyOf(5)
                    legacy to legacy.copyOf()
                }

                val result = when (currentRole) {
                    null -> manager.playAgainstAi(shoots, keeps, getSdPicks = ::gatherSdPicksFromUi)
                    Player.P1 -> manager.playAsP1(shoots, keeps, getSdPicks = ::gatherSdPicksFromUi)
                    Player.P2 -> manager.playAsP2(shoots, keeps, getSdPicks = ::gatherSdPicksFromUi)
                }

                val (p1Score, p2Score) = result.scores()
                val winner = when {
                    p1Score > p2Score -> "P1"
                    p2Score > p1Score -> "P2"
                    else -> null
                }

                Log.i(TAG, "Match result: P1=$p1Score P2=$p2Score winner=$winner role=$currentRole")
                statusMessage.value = when (currentRole) {
                    null -> "You $p1Score - $p2Score AI"
                    Player.P1 -> "You $p1Score - $p2Score opponent"
                    Player.P2 -> "Opponent $p1Score - $p2Score You"
                }

                // Opponent's regulation shoots — whichever side this
                // device is, the *other* player's picks form the line we
                // show. In Phase C we'll show shoots + keeps; for now
                // the shoots-only summary mirrors the legacy display.
                val opponentShoots = if (currentRole == Player.P2) {
                    result.p1Shoots
                } else {
                    result.p2Shoots
                }
                val opponentLabels = opponentShoots.map(::directionLabel)
                val youLabel = if (currentRole == Player.P2) "P2 (you)" else "You"
                val themLabel = if (currentRole == null) "AI" else "Opponent"
                lastChoices.value =
                    "$youLabel: ${labels.joinToString(" ")}  $themLabel: ${opponentLabels.joinToString(" ")}"

                // Match is resolved on chain — MatchManager already
                // deleted this specific match from [store] when its
                // setState fired MatchState.Resolved. Refresh the
                // affordance flag in case it was the last one.
                if (currentRole != null) {
                    hasActiveSession.value = store.loadAll().isNotEmpty()
                }

                // Replay payload — for PvP-as-P2, swap rounds so the
                // replay always renders from P1's perspective (the
                // contract treats P1 as the shooter sequence; reversing
                // would confuse the cinematic).
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
        Log.i(TAG, "Replay finished (role=$currentRole)")
        val result = matchManager?.lastResult ?: return
        val (p1, p2) = result.scores()
        // "You" depends on which side this device was on. For PvP-as-P2
        // the chain scoreboard's p1Score is the opponent's, so flip
        // the WIN/LOSE perspective. PvAI keeps the original P1-centric
        // text (the human is always P1 there).
        val (mine, theirs) = when (currentRole) {
            Player.P2 -> p2 to p1
            else -> p1 to p2
        }
        val opponentLabel = if (currentRole == null) "AI" else "OPPONENT"
        val winText = when {
            mine > theirs -> "YOU WIN!"
            theirs > mine -> "$opponentLabel WINS"
            else -> "DRAW"
        }
        statusMessage.value = "$winText  ($mine - $theirs)"
        // Done with this match — release the role so the next Unity
        // launch (e.g. PRACTICE) doesn't accidentally route through
        // the PvP orchestrators.
        currentRole = null
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
         * Stored alongside the persisted [MatchStore.Match] so the resume UI
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
