package com.midnight.kicks

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.midnight.kuira.dapp.PanelBar
import com.midnight.kuira.core.identity.backup.SigilRequiredException
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.core.identity.sigil.SigilStateStore
import com.midnight.kuira.sdk.walletruntime.MidnightSdkProvider
import com.midnight.kuira.sdk.walletruntime.WalletConfig
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Main activity for Midnight Kicks.
 *
 * Hosts the Compose menu UI (match creation, joining, results) and launches
 * Unity (via UaaL) for the 3D game portions.
 *
 * Flow: Menu → launch KicksMatchActivity (UnityPlayerGameActivity subclass
 * with the MatchHudOverlay on top) → Unity shows choice UI →
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
    // Local-only player cosmetics (name / nation / kit). Loaded in onCreate,
    // edited during the deploy wait, persisted on change, and sent to Unity at
    // kickoff. Never on-chain.
    private val playerProfile = mutableStateOf(PlayerProfile.DEFAULT)

    /**
     * Non-null while the abandon-match confirmation dialog is up
     * for a specific [MatchStore.Match] row. The dialog is rendered
     * by the top-level Compose tree — when the user confirms, we
     * delete from [store] and refresh the Resume list.
     */
    private val pendingAbandon = mutableStateOf<MatchStore.Match?>(null)
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
     * Owner of the one shared [MidnightSdk]. KicksActivity is the config
     * authority: [ensureSdkReady] calls [MidnightSdkProvider.ensureSdk] (same
     * UNDEPLOYED config as the wallet panel, so the provider dedups to a single
     * SDK / single chain sync), which triggers the seed bootstrap + biometric
     * and throws [SigilRequiredException] when the user hasn't forged a passkey
     * yet — translated to "forge your sigil first" instead of a generic error.
     * MatchManager then *follows* the SDK this built.
     */
    @Inject lateinit var sdkProvider: MidnightSdkProvider

    /** Used to gate create/join on a forged sigil — without one, the wallet
     *  bootstrap can't run and the flow would otherwise hang on a spinner. */
    @Inject lateinit var sigilStateStore: SigilStateStore
    /**
     * Role this device is playing for the current Unity choice phase.
     * `null` → PvAI (legacy practice mode). Drives the dispatch in
     * [handleChoicesLocked]. Set when launching Unity from
     * [KicksScreen.MatchReady]; cleared in [handleReplayComplete].
     */
    private var currentRole: Player? = null

    /**
     * True while an off-chain QUICK PRACTICE match is in flight. Off-chain
     * practice never builds the SDK / a MatchManager — [handleChoicesLocked]
     * dispatches to [PracticeSimulator] instead of the on-chain orchestrator.
     * Set in [launchQuickPractice]; cleared on every on-chain entry point and
     * when returning to the menu, so a stale flag can't misroute a real match.
     */
    private var quickPracticeMode = false

    /**
     * The role of the match that just finished, captured in [handleMatchResult]
     * before [currentRole] is cleared. The end screen's REMATCH lands back in
     * main as a `rematch` message AFTER the match is done, so [currentRole] is
     * already null by then — this remembers which "play again" flow to start:
     * `null` → new PvAI; `P1` → create a new match; `P2` → join screen.
     */
    private var lastPlayedRole: Player? = null

    /**
     * Role array last published to the picker via [MatchHud.showPicker].
     * Cached here so [handleChoicesLocked] can bucket the returned picks
     * back into shoots vs keeps using the same per-index role labels the
     * picker displayed. Cleared after each match.
     */
    private var currentChoiceRoles: List<String> = emptyList()

    /**
     * When non-null, [handleChoicesLocked] routes the returned picks to
     * this deferred instead of starting a new match — the orchestrator's
     * SD loop is suspended on it via [gatherSdPicksFromUi]. Set inside
     * the SD callback; cleared as soon as the deferred completes.
     */
    private var pendingSdPicks: CompletableDeferred<Pair<Int, Int>>? = null

    /**
     * The in-flight match orchestrator coroutine (regulation play, or a
     * resume). Tracked so [handleMatchPaused] can cancel it when the user
     * leaves mid-match — otherwise it keeps polling the indexer / waiting on
     * the opponent in the background after `:unity` is gone, and a later
     * RESUME would start a *second* orchestrator racing the first. Cancelling
     * is safe: chain state is the source of truth and RESUME re-drives the
     * state machine from it, so there's nothing local worth keeping alive.
     */
    private var orchestratorJob: Job? = null

    /**
     * Armed when a replay cinematic finishes ([handleReplayComplete]); fires
     * the auto-advance safety net after [REPLAY_AUTO_ADVANCE_GRACE_MS]. Tracked
     * so it can be cancelled on pause and re-armed per replay — a single missed
     * Continue tap must never strand the committed opponent forever.
     */
    private var replayAutoAdvanceJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge per Android HIG: the menu's stadium backdrop draws behind
        // the system bars while content claims the safe area via window insets
        // (see KicksApp). Required default on Android 15; opt in explicitly so
        // older versions match.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Register for Unity bridge messages. Post-split these arrive relayed
        // from :unity via MatchBridge → mainInbox → UnityBridge.onMessageFromUnity,
        // so the orchestration here is unchanged.
        UnityBridge.onMessageFromUnity = { json ->
            runOnUiThread { handleUnityMessage(json) }
        }

        // main → :unity HUD relay: MatchManager publishes HudState here; ship
        // each change to the :unity overlays. The :unity activity applies it
        // via MatchHud.applyRemote (see KicksMatchActivity / MatchBridge).
        MatchHud.relayHook = { json -> MatchBridge.publishHud(json) }

        // Surface RESUME MATCH if a previous session is on disk. Independent
        // from any deep-link handling: even a cold launch with no intent
        // data should show the resume affordance if a session exists.
        hasActiveSession.value = store.loadAll().isNotEmpty()
        playerProfile.value = PlayerProfileStore.load(this)

        handleDeepLink(intent)

        setContent {
            // Adaptive WindowSizeClass for the whole UI tree; recomputes on
            // rotation (observes Configuration) under this activity's configChanges.
            ProvideWindowSizeClass(this@KicksActivity) {
            when (val s = screen.value) {
                KicksScreen.Menu -> KicksApp(
                    statusMessage = statusMessage.value,
                    lastChoices = lastChoices.value,
                    hasActiveSession = hasActiveSession.value,
                    network = NetworkPref.load(this),
                    onNetworkChange = { NetworkPref.save(this, it) },
                    onCreateMatch = {
                        clearMenuStatus()
                        startCreateMatch()
                    },
                    onJoinMatch = {
                        clearMenuStatus()
                        screen.value = KicksScreen.Joining()
                    },
                    onResumeMatch = {
                        clearMenuStatus()
                        resumeMatch()
                    },
                    onQuickPractice = {
                        clearMenuStatus()
                        launchQuickPractice()
                    },
                    onPracticeVsAi = {
                        clearMenuStatus()
                        currentRole = null
                        launchUnityChoicePhase()
                    },
                )
                is KicksScreen.Creating -> CreateMatchScreen(
                    address = s.address,
                    checking = creatingChecking.value,
                    statusMessage = creatingStatus.value,
                    profile = playerProfile.value,
                    onProfileChange = ::updatePlayerProfile,
                    onBack = { screen.value = KicksScreen.Menu },
                    onCheckStatus = ::checkCreateStatus,
                    onCancel = ::cancelCreateMatch,
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
                        // PvAI resumes as role=P1 on chain but must DISPLAY as AI.
                        currentRole = if (matchManager?.isVsAi == true) null else s.role
                        // Gate on state — resuming a match where picks
                        // are already committed must NOT re-launch the
                        // Unity choice phase. The resume orchestrator
                        // drives the remaining steps from the
                        // rehydrated picks.
                        val managerState = matchManager?.state?.value
                        // Needs fresh picks iff this device hasn't committed its
                        // regulation yet — the same threshold the resume
                        // orchestrators enforce (P1 < P1_COMMITTED, P2 <
                        // BOTH_COMMITTED). SD states are past both, so the SD loop
                        // re-prompts; null state → resume path builds the manager.
                        val rank = managerState?.protocolRank ?: Int.MAX_VALUE
                        val needsFreshPicks = when (s.role) {
                            Player.P1 -> rank < PhaseRank.P1_COMMITTED
                            Player.P2 -> rank < PhaseRank.BOTH_COMMITTED
                        }
                        if (needsFreshPicks) {
                            Log.i(TAG, "CONTINUE: role=${s.role} state=$managerState → launching Unity for picks")
                            launchUnityChoicePhase()
                        } else {
                            Log.i(TAG, "CONTINUE: role=${s.role} state=$managerState → resume orchestrator")
                            resumeOrchestrator(s.role)
                        }
                    },
                    // Wall-clock hint only — the contract's claimTimeout is the real
                    // gate (claimForfeit fails legibly if the deadline hasn't passed).
                    claimable = store.load(s.address)?.let {
                        System.currentTimeMillis() / 1000 >= it.deadline
                    } ?: false,
                    onClaimForfeit = { claimForfeit(s.address) },
                )
                KicksScreen.Resume -> {
                    // Load the store OFF the main thread. loadAll() decrypts each
                    // match from EncryptedSharedPreferences (and, on first access,
                    // builds the Keystore master key) — doing that synchronously
                    // during recomposition janked the list and made rows feel
                    // unresponsive. Reloads on entering Resume and after an abandon
                    // (key2) so Backup/Restore-added matches still surface.
                    val resumeMatches by produceState(
                        initialValue = emptyList<MatchStore.Match>(),
                        key1 = screen.value,
                        key2 = pendingAbandon.value,
                    ) {
                        value = withContext(Dispatchers.IO) { store.loadAll() }
                    }
                    ResumeScreen(
                        matches = resumeMatches,
                        onBack = { screen.value = KicksScreen.Menu },
                        onMatchSelected = ::resumeIntoMatch,
                        onAbandon = { pendingAbandon.value = it },
                    )
                }
            }
            // Abandon-match confirmation dialog. Sits outside the
            // screen `when` so it overlays whichever screen is up.
            // Confirming deletes the local witness key — the match
            // address on chain is unaffected, but this device can no
            // longer act on it.
            pendingAbandon.value?.let { match ->
                AbandonMatchDialog(
                    match = match,
                    onConfirm = {
                        Log.i(TAG, "Abandoning match ${match.address.take(16)}… (role=${match.role})")
                        // delete() does a blocking EncryptedSharedPreferences
                        // .commit(); run it + the reload off the main thread.
                        lifecycleScope.launch {
                            val remaining = withContext(Dispatchers.IO) {
                                store.delete(match.address)
                                store.loadAll()
                            }
                            hasActiveSession.value = remaining.isNotEmpty()
                            pendingAbandon.value = null
                            // If we were on Resume and this was the last match,
                            // fall back to the Menu.
                            if (remaining.isEmpty()) screen.value = KicksScreen.Menu
                        }
                    },
                    onCancel = { pendingAbandon.value = null },
                )
            }
            } // ProvideWindowSizeClass
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
    /**
     * Gate: a funds-bearing flow can't start without a forged sigil (the
     * wallet seed derives from the passkey). Returns true + sets a status hint
     * if no sigil — callers must bail BEFORE switching to a loading screen, or
     * the screen would spin forever once [ensureSdkReady] gates on the sigil.
     */
    private fun sigilMissing(): Boolean {
        if (sigilStateStore.snapshot() == null) {
            statusMessage.value = "Forge your sigil first (tap the sigil chip up top)."
            return true
        }
        return false
    }

    private fun startCreateMatch() {
        if (sigilMissing()) return
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
    /**
     * Creator cancels a match no opponent joined and reclaims the stake.
     * Valid only while still on the create screen (WAITING phase) — once an
     * opponent joins, [checkCreateStatus] moves the user to MatchReady and the
     * contract no longer allows cancel. On success, the match is gone (the
     * manager resolves + clears the store), so we return to the menu.
     */
    private fun cancelCreateMatch() {
        val address = (screen.value as? KicksScreen.Creating)?.address ?: return
        creatingChecking.value = true
        creatingStatus.value = null
        lifecycleScope.launch {
            try {
                val manager = matchManager ?: return@launch
                manager.cancelMatch()
                Log.i(TAG, "Match cancelled + stake refunded: $address")
                screen.value = KicksScreen.Menu
            } catch (e: Exception) {
                Log.w(TAG, "cancelMatch failed", e)
                creatingStatus.value = "Couldn't cancel — ${e.message ?: "try again in a moment"}."
            } finally {
                creatingChecking.value = false
            }
        }
    }

    /**
     * Claim the pot when the opponent missed the deadline. claimForfeit() lands
     * the match in Resolved(WON_BY_FORFEIT) on success — the state collector
     * surfaces the "you win" label — or Failed (e.g. contract "deadline not
     * reached") whose message the collector also shows. Either way, back to Menu.
     */
    private fun claimForfeit(address: String) {
        lifecycleScope.launch {
            val manager = matchManager ?: return@launch
            Log.i(TAG, "Claiming forfeit for ${address.take(16)}…")
            manager.claimForfeit()
            hasActiveSession.value = store.loadAll().isNotEmpty()
            screen.value = KicksScreen.Menu
        }
    }

    private fun checkCreateStatus() {
        val s = screen.value as? KicksScreen.Creating ?: return
        val address = s.address ?: return
        creatingChecking.value = true
        creatingStatus.value = null
        lifecycleScope.launch {
            try {
                val manager = matchManager ?: return@launch
                // After app kill + resume, state should land somewhere
                // with an address attached — Deployed (still waiting),
                // Joined (opponent in), or any later phase if the user
                // already committed / revealed before the kill. Only
                // address-less states (Idle / SdkReady / InitializingSdk)
                // mean we have nothing to check — typically because the
                // resume already concluded the prior match was over
                // (see the "your previous match finished" banner in
                // ensureSdkReady — that already explained it).
                if (manager.state.value.address == null) {
                    creatingStatus.value =
                        "No match in progress — tap CREATE MATCH on the menu to start a new one."
                    return@launch
                }
                // awaitOpponentJoin is idempotent — on any post-join
                // state it returns immediately (the opponent's already
                // in), so we land on MatchReady without retrying.
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
        // Cancel any still-running orchestrator before resumeSpecificMatch mutates
        // shared identity (currentAddress / keys) out from under it.
        orchestratorJob?.cancel()
        orchestratorJob = null
        ensureSdkReady {
            lifecycleScope.launch {
                val manager = matchManager ?: return@launch
                // Advance the state machine into the picked match.
                // Without this the SdkReady → … transition never
                // fires; CHECK STATUS subsequently sees
                // state.value.address == null and bails with "no
                // match in progress".
                val resumed = try {
                    manager.resumeSpecificMatch(match.address)
                } catch (e: Exception) {
                    Log.e(TAG, "resumeSpecificMatch failed", e)
                    statusMessage.value = "Resume failed: ${e.message}"
                    return@launch
                }
                if (resumed == null) {
                    // Match either disappeared from the store or
                    // was already COMPLETE on chain — surface the
                    // priorMatchFinished banner if available.
                    manager.consumePriorMatchFinished()?.let { finished ->
                        val (you, them) = when (finished.role) {
                            Player.P1 -> finished.p1Score to finished.p2Score
                            Player.P2 -> finished.p2Score to finished.p1Score
                        }
                        statusMessage.value = when (finished.outcome) {
                            PriorMatchFinished.Outcome.Win ->
                                "Match already finished — YOU WIN $you – $them"
                            PriorMatchFinished.Outcome.Loss ->
                                "Match already finished — you lost $you – $them"
                            PriorMatchFinished.Outcome.Draw ->
                                "Match already finished — drawn $you – $them"
                        }
                    }
                    hasActiveSession.value = store.loadAll().isNotEmpty()
                    screen.value = KicksScreen.Menu
                    return@launch
                }
                // Route based on actual chain-derived state, not
                // just the stored role — a P1 whose match is already
                // in SD should land on MatchReady (CONTINUE → resume
                // orchestrator), not Creating (CHECK STATUS).
                screen.value = chooseResumeScreen(
                    address = resumed,
                    role = match.role,
                    state = manager.state.value,
                )
            }
        }
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
        // Deep-link joiners land on JoinMatchScreen, which has NO sigil chip —
        // so a no-sigil JOIN can't forge in place and the gate would bail
        // invisibly (the "forge first" status isn't shown there). Route to the
        // menu, which has the sigil chip + surfaces the status message, so the
        // user can forge and then re-open the invite to join.
        if (sigilStateStore.snapshot() == null) {
            Log.i(TAG, "Join gated on missing sigil — routing to menu to forge")
            statusMessage.value =
                "Forge your sigil first (sigil chip, top of the menu), then re-open the invite to join."
            screen.value = KicksScreen.Menu
            return
        }
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
                } catch (e: MatchAlreadyResolvedException) {
                    // Match is over — show the final score instead of
                    // the misleading "another player already joined"
                    // copy. Also clean up any stale session affordance.
                    Log.w(TAG, "joinAsP2 refused: match already finished — ${e.p1Score}-${e.p2Score}")
                    hasActiveSession.value = store.loadAll().isNotEmpty()
                    statusMessage.value =
                        "Match is over. Final score: P1 ${e.p1Score} – ${e.p2Score} P2."
                    screen.value = KicksScreen.Joining(
                        prefilledAddress = address,
                        inFlight = false,
                    )
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
        MatchHud.relayHook = null
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
    private fun ensureSdkReady(autoResume: Boolean = false, onReady: () -> Unit) {
        lifecycleScope.launch {
            try {
                // Reconcile the shared SDK to the user's CURRENT network on EVERY
                // action, not just first launch. A network switch only saves
                // NetworkPref + refreshes the wallet panel; the Kicks action path
                // must independently re-point the provider, else a Create/Join
                // after switching runs against the boot network's wallet (stale
                // dust → "Insufficient dust balance"). MatchManager (a follower)
                // reads the provider's live SDK, so re-pointing here is enough —
                // no MatchManager rebuild needed at the menu. ensureSdk dedups an
                // unchanged config (cheap on the hot path). Throws
                // SigilRequiredException until a passkey is forged — the catch
                // below turns that into a "forge your sigil first" prompt.
                val currentNetwork = NetworkPref.load(this@KicksActivity)
                sdkProvider.ensureSdk(
                    this@KicksActivity,
                    WalletConfig(network = currentNetwork),
                )
                if (matchManager == null) {
                    // First launch shows one biometric prompt (PRF derivation);
                    // later launches hit the SeedVault cache.
                    val manager = MatchManager(
                        context = applicationContext,
                        network = currentNetwork,
                        // Follower: takes the SDK the provider just built —
                        // no second SDK, no second chain sync.
                        sdkProvider = sdkProvider,
                        // Shared Hilt-singleton MatchStore so KicksActivity,
                        // MatchManager, and MatchStoreBackupProvider all read
                        // one instance (cloud backup sees the same data).
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
                    // Auto-resume is OPT-IN ([autoResume]) — a genuine app
                    // bootstrap, never an explicit fresh-match action. Without
                    // this gate, tapping VS AI · ON-CHAIN (which leaves the
                    // screen on Menu) hijacked the tap into the user's prior
                    // match — resuming the old "your picks revealed" state AND
                    // showing the fresh picker at once. CREATE/JOIN set their
                    // screen first so they were already immune; this makes the
                    // immunity explicit and uniform. Resume stays available via
                    // the RESUME MATCH button (resumeMatch → Resume picker).
                    val resumedAddress = if (autoResume) manager.tryResumeActiveMatch() else null
                    val screenIsMenu = screen.value is KicksScreen.Menu
                    if (resumedAddress != null && screenIsMenu) {
                        Log.i(TAG, "Resumed active match: $resumedAddress")
                        screen.value = chooseResumeScreen(
                            address = resumedAddress,
                            role = store.load(resumedAddress)?.role,
                            state = manager.state.value,
                        )
                    } else if (autoResume && resumedAddress == null && store.loadAll().size > 1 && screenIsMenu) {
                        // tryResumeActiveMatch returns null for multi-match
                        // stores — defer to the Resume picker so the
                        // user chooses explicitly instead of bouncing
                        // into an arbitrary first-found.
                        Log.i(TAG, "Multiple stored matches — routing to Resume picker")
                        statusMessage.value = "Pick a match to resume."
                        screen.value = KicksScreen.Resume
                    } else if (autoResume && !screenIsMenu) {
                        Log.i(TAG, "Bootstrap auto-resume skipped — user is on ${screen.value::class.simpleName}")
                    }
                    // Surface a "your prior match finished" banner when
                    // the resume bailed because chain is COMPLETE. The
                    // manager populates [priorMatchFinished]; we
                    // consume it once and write a friendly status line.
                    manager.consumePriorMatchFinished()?.let { finished ->
                        val (you, them) = when (finished.role) {
                            Player.P1 -> finished.p1Score to finished.p2Score
                            Player.P2 -> finished.p2Score to finished.p1Score
                        }
                        statusMessage.value = when (finished.outcome) {
                            PriorMatchFinished.Outcome.Win ->
                                "Your previous match finished — YOU WIN $you – $them"
                            PriorMatchFinished.Outcome.Loss ->
                                "Your previous match finished — you lost $you – $them"
                            PriorMatchFinished.Outcome.Draw ->
                                "Your previous match finished — drawn $you – $them"
                        }
                        Log.i(TAG, "Prior match finished banner: ${statusMessage.value}")
                    }
                    matchManager = manager
                }
                onReady()
            } catch (e: SigilRequiredException) {
                // The wallet seed derives from the user's passkey; until
                // a sigil is forged we can't bootstrap. Surface an
                // actionable hint instead of a generic error — the sigil
                // panel (top of screen) is the user's next stop.
                Log.i(TAG, "SDK init gated on sigil — prompting forge")
                statusMessage.value = "Forge your sigil first (tap the sigil chip up top)."
                // Don't strand the caller on a loading screen (the
                // "Deploying…" / "Joining…" spinner) — that spinner never
                // resolves because the onReady block above never ran. Drop
                // back to the menu where the hint is visible.
                if (screen.value !is KicksScreen.Menu) screen.value = KicksScreen.Menu
            } catch (e: Exception) {
                Log.e(TAG, "SDK init failed", e)
                statusMessage.value = "Error: ${e.message}"
                if (screen.value !is KicksScreen.Menu) screen.value = KicksScreen.Menu
            }
        }
    }

    private fun launchUnityChoicePhase() {
        quickPracticeMode = false // on-chain entry — a stale practice flag must not linger
        ensureSdkReady {
            statusMessage.value = "Pick your 5 directions!"
            Log.i(TAG, "Launching Unity for choice phase...")

            // Launch our subclass [KicksMatchActivity], not Unity's
            // default activity directly — the subclass attaches the
            // MatchHudOverlay ComposeView on top of Unity's surface so
            // the user sees state / tx-progress / opponent-wait during
            // gameplay instead of a frozen 3D scene.
            val intent = Intent(this@KicksActivity, KicksMatchActivity::class.java)
            startActivity(intent)

            // Wait briefly for Unity's Activity to come up before sending
            // the first bridge message — UnityPlayer needs to be alive to
            // receive it. Coroutine + delay is the canonical idiom; the
            // old `decorView.postDelayed` worked but didn't share the
            // activity's cancellation scope.
            lifecycleScope.launch {
                delay(UNITY_BOOT_DELAY_MS)
                // Apply the player's chosen kit (+ a contrasting opponent kit)
                // now that the Unity scene is up.
                sendPlayerAppearanceToUnity()
                // Per-round role from this device's perspective. The
                // contract's `i % 2 == 0 → P1 shoots` rule means P1 sees
                // shoot/keep/shoot/keep/shoot and P2 sees keep/shoot/keep/
                // shoot/keep. PvAI uses the P1 pattern since the human is
                // always P1 in that flow. Unity uses this to label each
                // pick "YOU SHOOT" / "YOU KEEP".
                val roles = rolesForCurrentDevice()
                currentChoiceRoles = roles
                // Drive the Compose picker overlay (MatchPickerOverlay) instead
                // of Unity's old IMGUI picker — main publishes, :unity renders +
                // collects, picks return via the same choicesLocked path.
                MatchHud.showPicker(roles = roles, title = "Regulation")
            }
        }
    }

    /**
     * Off-chain QUICK PRACTICE entry. Unlike [launchUnityChoicePhase] it does
     * NOT build the SDK or a MatchManager — no sigil, dust, proving, or chain.
     * It boots Unity, shows the picker, and [handleChoicesLocked] runs the
     * whole match locally via [PracticeSimulator]. Instant; nothing persisted.
     */
    private fun launchQuickPractice() {
        quickPracticeMode = true
        currentRole = null // human is P1 in the role pattern
        statusMessage.value = "Pick your 5 directions!"
        Log.i(TAG, "Launching Unity for QUICK PRACTICE (off-chain)…")
        startActivity(Intent(this@KicksActivity, KicksMatchActivity::class.java))
        lifecycleScope.launch {
            delay(UNITY_BOOT_DELAY_MS)
            sendPlayerAppearanceToUnity()
            val roles = rolesForCurrentDevice()
            currentChoiceRoles = roles
            MatchHud.showPicker(roles = roles, title = "Practice")
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
        // Round 1 = the FIRST SD round, opened immediately after a
        // regulation tie. The user must see the regulation replay
        // (rendered by MatchReplayOverlay above Unity) before learning
        // SD exists. Players don't get the SD picker until the replay
        // is dismissed.
        //
        // MatchManager.publishRegulationReplay has already populated
        // MatchHud.replay by the time this is called (it fires inside
        // waitForP2Revealed / revealP2, both of which complete before
        // the orchestrator hits the SD loop). If for any reason the
        // replay state is empty (e.g. orchestrator skipped the publish),
        // the `first { it == null }` predicate is already true and we
        // proceed immediately — no deadlock on a missing replay.
        // Gate on replay dismissal for EVERY round, not just round 1.
        //   - Round 1: a regulation-end replay was published in
        //     waitForP2Revealed/revealP2 and the user hasn't dismissed.
        //   - Rounds 2+: the previous SD round's replay was published
        //     in waitForP2SdRevealed/revealP2Sd; the user must see
        //     "round N — both scored / both saved → going to round N+1"
        //     before being asked to pick again.
        //
        // StateFlow.first { it == null } returns IMMEDIATELY if no
        // replay is currently showing (the user already dismissed it,
        // or it was never published), and suspends only if a replay is
        // mid-flight. SharedFlow had a dismissal-before-await race:
        // if the user tapped Continue between the check and the await,
        // the dismissal-event subscription would arrive too late and
        // we'd deadlock waiting for a signal that already fired.
        // StateFlow's replay-the-current-value-on-collect semantics
        // makes the check + wait one atomic step.
        if (MatchHud.replay.value != null) {
            Log.i(TAG, "gatherSdPicksFromUi(round=$round): waiting for replay dismissal before SD picker")
        }
        MatchHud.replay.first { it == null }
        Log.i(TAG, "gatherSdPicksFromUi(round=$round): replay clear, proceeding to SD picker")

        val roles = listOf("shoot", "keep")
        val deferred = CompletableDeferred<Pair<Int, Int>>()
        pendingSdPicks = deferred
        currentChoiceRoles = roles
        runOnUiThread {
            statusMessage.value = "Sudden death — round $round"
            MatchHud.showPicker(roles = roles, title = "Sudden death — round $round")
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
                "endToMenu" -> handleEndToMenu()
                "rematch" -> handleRematch()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Unity message: ${e.message}")
        }
    }

    /**
     * Match paused from the Unity HUD. `:unity` killed itself right after
     * sending this, so the user is back on this (main-process) Activity with
     * the wallet + sigil pills at the top. Tear down the local match state and
     * point them at the right way back in.
     */
    private fun handleMatchPaused() {
        // :unity sent this right before killing itself (GameController), so its
        // inbox is about to become a dead Messenger — drop our reference now
        // rather than waiting for the next relay to hit RemoteException.
        MatchBridge.onUnityGone()

        // Stop the in-flight orchestrator. Without this it keeps polling the
        // indexer / awaiting the opponent in the background, and a later RESUME
        // would spin up a *second* orchestrator racing this one. Safe to drop:
        // the match lives on chain + in MatchStore, and RESUME re-drives it.
        if (orchestratorJob?.isActive == true) {
            Log.i(TAG, "matchPaused: cancelling in-flight orchestrator")
        }
        orchestratorJob?.cancel()
        orchestratorJob = null

        // Drop any armed replay auto-advance — the match it belonged to is gone.
        replayAutoAdvanceJob?.cancel()
        replayAutoAdvanceJob = null

        // Clear the in-match HUD so a stale banner doesn't linger if the user
        // re-enters via a different path.
        MatchHud.reset()

        // Clear on exit too, else a stale flag misroutes the next on-chain match.
        quickPracticeMode = false

        // A paused PvP match is still on disk → RESUME MATCH is the way back.
        // PvAI leaves nothing resumable. Surface whichever is true so the menu
        // copy matches the affordance the user actually has.
        hasActiveSession.value = store.loadAll().isNotEmpty()
        statusMessage.value = if (hasActiveSession.value) {
            "Match paused — tap RESUME MATCH to pick up where you left off"
        } else {
            "Match paused"
        }
    }

    /**
     * Clear the post-match summary the menu carries forward — the win text and
     * the pick recap [handleMatchResult] sets, which [handleEndToMenu] keeps so
     * the user sees the result when they land back on the menu. It's a
     * show-once message: the moment the user starts the next thing (create /
     * join / practice / resume / rematch) the old result is stale, so those
     * entry points wipe it instead of letting it bleed into the new flow.
     */
    private fun clearMenuStatus() {
        statusMessage.value = null
        lastChoices.value = null
    }

    /** Persist + reflect a player-profile edit from the customize card. */
    private fun updatePlayerProfile(profile: PlayerProfile) {
        playerProfile.value = profile
        PlayerProfileStore.save(this, profile)
    }

    /**
     * Ship the player's kit (and a contrasting opponent kit) to Unity at
     * kickoff — local cosmetics only, applied by the appearance scripts once the
     * scene is up. Called after [UNITY_BOOT_DELAY_MS].
     */
    private fun sendPlayerAppearanceToUnity() {
        val profile = playerProfile.value
        UnityBridge.sendPlayerAppearance(
            playerName = profile.name,
            playerKit = profile.kit,
            opponentKit = contrastingOpponentKit(profile.kit),
        )
    }

    /**
     * End-screen MENU tap (the match is already RESOLVED, not paused). `:unity`
     * killed itself right after sending this, so drop the dead inbox and clear
     * the in-match HUD, then land on the menu — KEEPING the win text
     * [handleMatchResult] already set (unlike the paused path, which overwrites
     * the status).
     */
    private fun handleEndToMenu() {
        MatchBridge.onUnityGone()
        MatchHud.reset()
        quickPracticeMode = false // practice match (if any) is over
        hasActiveSession.value = store.loadAll().isNotEmpty()
        screen.value = KicksScreen.Menu
    }

    /**
     * End-screen REMATCH tap. The finished match is gone (Resolved → deleted)
     * and there's no peer channel for an "instant" PvP rematch — the only link
     * between players is the contract. So REMATCH means "play again in your
     * role": PvAI → a fresh AI match now; P1 → create a new match to share; P2 →
     * the join screen for the opponent's new invite.
     */
    private fun handleRematch() {
        MatchBridge.onUnityGone()
        MatchHud.reset()
        clearMenuStatus()
        when (lastPlayedRole) {
            // null = AI match. quickPracticeMode (still set from the match that
            // just ended) tells off-chain practice from an on-chain AI match.
            null -> if (quickPracticeMode) {
                launchQuickPractice()
            } else {
                currentRole = null
                launchUnityChoicePhase()
            }
            Player.P1 -> startCreateMatch()
            Player.P2 -> {
                statusMessage.value = "Rematch — enter your opponent's new match link"
                screen.value = KicksScreen.Joining()
            }
        }
    }

    /**
     * Bucket Unity's interleaved 10 picks into shoots[5] + keeps[5] using the
     * per-index role array we sent on the way out (role[i]=="shoot" → next
     * shoots entry, else keeps). Legacy fallback: a pre-V3 Unity APK returns 5
     * picks — reuse them as both shoots and keeps so the match still runs.
     */
    private fun bucketRolePicks(picks: IntArray, roles: List<String>): Pair<IntArray, IntArray> {
        if (picks.size == roles.size && picks.size == 10) {
            val s = mutableListOf<Int>()
            val k = mutableListOf<Int>()
            roles.forEachIndexed { i, role -> if (role == "shoot") s += picks[i] else k += picks[i] }
            return s.toIntArray() to k.toIntArray()
        }
        Log.w(
            TAG,
            "choicesLocked returned ${picks.size} picks (expected 10). Unity APK is " +
                "pre-V3 — re-export needed for the 10-pick flow. Falling back to shoots == keeps.",
        )
        val legacy = picks.copyOf(5)
        return legacy to legacy.copyOf()
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

        // Off-chain QUICK PRACTICE — simulate the whole match locally with no
        // SDK / MatchManager (the practice path never built one). Sudden death
        // still prompts the player through the same SD picker (gatherSdPicksFromUi).
        if (quickPracticeMode) {
            orchestratorJob = lifecycleScope.launch {
                try {
                    val (shoots, keeps) = bucketRolePicks(choiceList.toIntArray(), currentChoiceRoles)
                    Log.i(TAG, "practice choicesLocked: shoots=${shoots.toList()} keeps=${keeps.toList()}")
                    val result = PracticeSimulator.simulate(shoots, keeps, getSdPicks = ::gatherSdPicksFromUi)
                    // Practice has no MatchManager to publish the end replay, so do
                    // it here — drives the cinematic + result screen (REMATCH/MENU).
                    val (p1, p2) = result.scores()
                    MatchHud.publishReplay(
                        MatchHud.ReplayShow(
                            rounds = result.toRoundResults(),
                            p1Score = p1,
                            p2Score = p2,
                        ),
                    )
                    handleMatchResult(result, deviceLabels = labels)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Practice match failed", e)
                    statusMessage.value = "Practice failed: ${e.message}"
                }
            }
            return
        }

        // No progress callback needed — `manager.state` is already bound
        // to statusMessage via the state collector in ensureSdkReady().
        orchestratorJob = lifecycleScope.launch {
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
                // Diagnostic — log the raw inputs so a tie pattern can be traced
                // back to overlapping human picks, a legacy 5-pick Unity APK, or
                // a role-mismatch. Public game inputs, no secret material.
                Log.i(TAG, "choicesLocked-IN: role=${currentRole ?: "PvAI"} picks=${choiceList} roles=$currentChoiceRoles")
                val (shoots, keeps) = bucketRolePicks(choiceList.toIntArray(), currentChoiceRoles)
                Log.i(TAG, "choicesLocked-OUT: shoots=${shoots.toList()} keeps=${keeps.toList()}")

                val result = when (currentRole) {
                    null -> manager.playAgainstAi(shoots, keeps, getSdPicks = ::gatherSdPicksFromUi)
                    Player.P1 -> manager.playAsP1(shoots, keeps, getSdPicks = ::gatherSdPicksFromUi)
                    Player.P2 -> manager.playAsP2(shoots, keeps, getSdPicks = ::gatherSdPicksFromUi)
                }
                handleMatchResult(result, deviceLabels = labels)
            } catch (e: CancellationException) {
                // Deliberate: the user paused out (handleMatchPaused cancelled
                // us). Don't surface it as a failure — rethrow so structured
                // concurrency unwinds cleanly.
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Match failed", e)
                statusMessage.value = "Match failed: ${e.message}"
            }
        }
    }

    /**
     * Post-orchestrator result rendering. Extracted from
     * [handleChoicesLocked] so the resume entry point
     * ([resumeOrchestrator]) can run the same closing UX (replay
     * dismissal wait → status update → opponent-picks line →
     * sendReplay) without duplicating the block.
     *
     * @param result        the orchestrator's final [MatchResult].
     * @param deviceLabels  direction labels for the picks THIS device
     *   committed. Live path passes the freshly-locked Unity picks;
     *   resume path passes the rehydrated picks pulled out of
     *   [result] (since the user didn't re-pick).
     */
    private suspend fun handleMatchResult(result: MatchResult, deviceLabels: List<String>) {
        // The final replay (published by the orchestrator before it returned)
        // becomes the end screen — a decisive ResultHud that the user exits via
        // REMATCH / MENU, not a dismissal. So this no longer waits on the replay
        // being dismissed (it never is); it runs immediately, doing the menu-side
        // housekeeping (win text, role release) the user sees when they return.
        val (p1Score, p2Score) = result.scores()
        val winner = when {
            p1Score > p2Score -> "P1"
            p2Score > p1Score -> "P2"
            else -> null
        }

        Log.i(TAG, "Match result: P1=$p1Score P2=$p2Score winner=$winner role=$currentRole")
        // Win-text flourish from THIS device's perspective (P2's "mine" is the
        // chain's p2Score). Moved here from handleReplayComplete — see below.
        val (mine, theirs) = if (currentRole == Player.P2) p2Score to p1Score else p1Score to p2Score
        val themName = if (currentRole == null) "AI" else "OPPONENT"
        statusMessage.value = when {
            mine > theirs -> "YOU WIN!  $mine – $theirs"
            theirs > mine -> "$themName WINS  $mine – $theirs"
            else -> "DRAW  $mine – $theirs"
        }

        // Opponent's regulation shoots — whichever side this device is,
        // the *other* player's picks form the line we show.
        val opponentShoots = if (currentRole == Player.P2) result.p1Shoots else result.p2Shoots
        val opponentLabels = opponentShoots.map(::directionLabel)
        val youLabel = playerProfile.value.name.ifBlank { "You" }
        val themLabel = if (currentRole == null) "AI" else "Opponent"
        lastChoices.value =
            "$youLabel: ${deviceLabels.joinToString(" ")}  $themLabel: ${opponentLabels.joinToString(" ")}"

        // Match is resolved on chain — MatchManager already deleted this
        // specific match from [store] when its setState fired
        // MatchState.Resolved. Refresh the affordance flag in case it
        // was the last one.
        if (currentRole != null) {
            hasActiveSession.value = store.loadAll().isNotEmpty()
        }

        // The 3D kick cinematic is no longer fired here (it used to play
        // dead-last). MatchReplayOverlay plays it the moment the replay appears
        // — the kicks are the main event, not a post-script. This block now
        // just gates the winner UI on that replay being seen.

        // Release the role at the true end of the match — but remember it first
        // so the end screen's REMATCH (which arrives after this runs) knows which
        // "play again" flow to start.
        lastPlayedRole = currentRole
        currentRole = null
    }

    /**
     * Resume entry point — fired by [MatchReadyScreen]'s CONTINUE button
     * when [MatchManager.state] is already past the regulation commit
     * (so re-launching Unity for picks would be wrong — picks are on
     * chain and rehydrated from [MatchStore]).
     *
     * Drives whichever steps haven't been done yet via
     * [MatchManager.resumePlayAsP1] / [MatchManager.resumePlayAsP2].
     * Unity is still launched so the user has a place to see the
     * replay overlay land and (for any future SD rounds) provide
     * input via [gatherSdPicksFromUi] — but **no `choicePhase`
     * message is sent**, since the regulation picker would re-ask
     * for picks already committed.
     */
    private fun resumeOrchestrator(role: Player) {
        quickPracticeMode = false // on-chain resume — never a practice match
        ensureSdkReady {
            statusMessage.value = "Resuming match…"
            val intent = Intent(this@KicksActivity, KicksMatchActivity::class.java)
            startActivity(intent)

            orchestratorJob = lifecycleScope.launch {
                // The role array must be set BEFORE any SD pick comes
                // back — handleSdChoicesLocked reads it to bucket the
                // returned picks. Same value the live path computes.
                currentChoiceRoles = rolesForCurrentDevice()

                // Let Unity's IL2CPP/GameController boot the 3D scene before
                // the picker overlay appears, so the user sees the pitch behind
                // it rather than a black surface. The picker itself is race-safe
                // regardless (MatchHud.showPicker is re-pushed to :unity on bind
                // via resendCurrent), so this delay is purely cosmetic now.
                // [launchUnityChoicePhase] pays the same cost for the
                // fresh-match path.
                delay(UNITY_BOOT_DELAY_MS)
                sendPlayerAppearanceToUnity()

                val manager = matchManager ?: run {
                    Log.e(TAG, "resumeOrchestrator: manager not ready")
                    statusMessage.value = "Not ready yet — try again"
                    return@launch
                }
                try {
                    val result = when {
                        // PvAI (role is P1 but the AI is local) — drive both
                        // sides, don't wait for a non-existent remote P2.
                        manager.isVsAi -> manager.resumeAgainstAi(getSdPicks = ::gatherSdPicksFromUi)
                        role == Player.P1 -> manager.resumePlayAsP1(getSdPicks = ::gatherSdPicksFromUi)
                        else -> manager.resumePlayAsP2(getSdPicks = ::gatherSdPicksFromUi)
                    }
                    // Device's own pick labels for the bottom-of-screen
                    // recap — read from the rehydrated picks in result.
                    val deviceShoots = if (role == Player.P2) result.p2Shoots else result.p1Shoots
                    handleMatchResult(result, deviceLabels = deviceShoots.map(::directionLabel))
                } catch (e: NeedFreshPicksException) {
                    // State machine isn't past the role's commit yet —
                    // the user needs to pick fresh picks via Unity. The
                    // CONTINUE gate normally routes these via
                    // [launchUnityChoicePhase] directly, but a race
                    // between resume and chain-state read can land us
                    // here. Re-route.
                    Log.i(TAG, "resumeOrchestrator: ${e.message} — routing to fresh-picks flow")
                    launchUnityChoicePhase()
                } catch (e: NoActiveMatchException) {
                    Log.w(TAG, "resumeOrchestrator: no active match — ${e.message}")
                    statusMessage.value = "No match to resume — tap CREATE on the menu."
                } catch (e: CancellationException) {
                    // Deliberate pause-out — see handleMatchPaused. Rethrow so
                    // it isn't reported as "Resume failed".
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Resume orchestrator failed", e)
                    statusMessage.value = "Resume failed: ${e.message}"
                }
            }
        }
    }

    private fun handleReplayComplete() {
        // The 3D cinematic finished one replay (regulation, an SD round, or the
        // final). This is NOT a "match over" signal — the winner UI + role
        // release live in handleMatchResult, gated on the replay's dismissal.
        //
        // What it IS: the reliable moment to arm the auto-advance safety net.
        // Both replay gates (gatherSdPicksFromUi + handleMatchResult) await
        // `MatchHud.replay.first { it == null }`, which normally clears on the
        // user's Continue tap. But a player who never taps — confused by Unity's
        // idle label bleeding through, distracted, backgrounded — would hang the
        // orchestrator here forever, AND strand the opponent (who has committed
        // and is waiting on this device's next move). So once the cinematic has
        // played, give the user a grace to tap Continue themselves, then advance
        // on their behalf. A deliberate Continue tap still wins — it clears the
        // replay first, and the guard below then no-ops.
        val finished = MatchHud.replay.value ?: return
        // A decisive (match-over) replay becomes the celebration end screen — it
        // must stay put until the user taps REMATCH / MENU, so it gets NO
        // auto-advance. Only intermediate replays (tie → SD, SD round → next)
        // auto-advance on the user's behalf.
        if (finished.show.p1Score != finished.show.p2Score) {
            Log.i(TAG, "Replay cinematic finished — decisive, end screen persists (no auto-advance)")
            return
        }
        Log.i(TAG, "Replay cinematic finished (role=$currentRole) — arming auto-advance")
        replayAutoAdvanceJob?.cancel()
        replayAutoAdvanceJob = lifecycleScope.launch {
            delay(REPLAY_AUTO_ADVANCE_GRACE_MS)
            // Advance ONLY the replay that just finished. If the user already
            // tapped Continue (replay is null) or a newer replay was published
            // during the grace (different publish epoch), leave it untouched.
            if (MatchHud.replay.value?.publishedAtMs == finished.publishedAtMs) {
                Log.i(TAG, "Auto-advancing replay — no Continue tap within grace")
                MatchHud.dismissReplay()
            }
        }
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

        /** How long to wait for KicksMatchActivity (Unity host) to come up before sending the first bridge message. */
        private const val UNITY_BOOT_DELAY_MS = 2_000L

        /**
         * After a replay cinematic finishes, how long to wait for the user to
         * tap Continue before auto-advancing on their behalf. Long enough that
         * the final score lands and a present player can tap deliberately
         * (honoring the manual "tap to continue" beat); short enough that a
         * player who walks away can't strand their committed opponent for long.
         * See [handleReplayComplete].
         */
        private const val REPLAY_AUTO_ADVANCE_GRACE_MS = 8_000L

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
    network: MidnightNetwork,
    onNetworkChange: (MidnightNetwork) -> Unit,
    onCreateMatch: () -> Unit,
    onJoinMatch: () -> Unit,
    onResumeMatch: () -> Unit,
    onQuickPractice: () -> Unit,
    onPracticeVsAi: () -> Unit,
) {
    // Landscape (short height): scroll the menu + tighten spacers so every button
    // stays reachable; portrait keeps the centered layout.
    val compact = isCompactHeight()
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = KicksColors.Background,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // World Cup stadium backdrop — the menu's signature visual.
            Image(
                painter = painterResource(R.drawable.world_cup_stadium),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            // Legibility scrim: light at the top so the stadium + flags read
            // through, darkening toward the bottom where the menu sits.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to KicksColors.Background.copy(alpha = 0.30f),
                            0.45f to KicksColors.Background.copy(alpha = 0.62f),
                            1f to KicksColors.Background.copy(alpha = 0.94f),
                        ),
                    ),
            )

            val panel = @Composable { mod: Modifier ->
                MenuPanel(
                    hasActiveSession = hasActiveSession,
                    onResumeMatch = onResumeMatch,
                    onCreateMatch = onCreateMatch,
                    onJoinMatch = onJoinMatch,
                    onQuickPractice = onQuickPractice,
                    onPracticeVsAi = onPracticeVsAi,
                    modifier = mod,
                )
            }

            // PanelBar sits ATOP the menu in a Column (its documented pattern),
            // not overlaid in the Box — so the menu content fills only the space
            // *below* the pills and can never rise under them (the landscape
            // panel was colliding with the top-right wallet pill). The stadium
            // image + scrim above remain full-bleed behind this Column.
            // PanelBar already clears the status bar; the content below only
            // needs the side + bottom safe insets (landscape nav bar / cutout).
            Column(modifier = Modifier.fillMaxSize()) {
                PanelBar(network = network, onNetworkChange = onNetworkChange)

                val contentInsets = Modifier.windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                    ),
                )
                if (compact) {
                    // Landscape: two panes — brand + status on the left, the
                    // action panel on the right — so every button fits the short
                    // height without scrolling (the right pane scrolls as a
                    // fallback).
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(contentInsets)
                            .padding(horizontal = 32.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(32.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            MenuBrand()
                            MenuStatus(statusMessage, lastChoices, topGap = 20.dp)
                            Spacer(modifier = Modifier.height(20.dp))
                            MenuFooter()
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            panel(Modifier)
                        }
                    }
                } else {
                    // Portrait: the classic centered stack.
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(contentInsets)
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        MenuBrand()
                        Spacer(modifier = Modifier.height(48.dp))
                        panel(Modifier)
                        MenuStatus(statusMessage, lastChoices, topGap = 48.dp)
                        Spacer(modifier = Modifier.height(48.dp))
                        MenuFooter()
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuBrand() {
    Text("MIDNIGHT", color = Color.White.copy(alpha = 0.4f), fontSize = 14.sp, letterSpacing = 6.sp)
    Text(
        "KICKS",
        color = Color.White,
        fontSize = 48.sp,
        fontWeight = FontWeight.W200,
        letterSpacing = 8.sp,
    )
}

@Composable
private fun MenuFooter() {
    Text("World Cup 2026", color = Color.White.copy(alpha = 0.2f), fontSize = 12.sp, letterSpacing = 4.sp)
}

/** Status / last-choices block under the menu; nothing rendered when both null. */
@Composable
private fun MenuStatus(statusMessage: String?, lastChoices: String?, topGap: Dp) {
    if (statusMessage == null && lastChoices == null) return
    Spacer(modifier = Modifier.height(topGap))
    if (lastChoices != null) {
        Text(
            lastChoices,
            color = KicksColors.Accent,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 4.sp,
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
    if (statusMessage != null) {
        Text(
            statusMessage,
            // Amber + full opacity so actionable warnings ("forge your sigil
            // first") aren't buried against the near-black background.
            color = KicksColors.Warning,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
    }
}

/** The glass action panel — shared by the portrait stack and the landscape pane. */
@Composable
private fun MenuPanel(
    hasActiveSession: Boolean,
    onResumeMatch: () -> Unit,
    onCreateMatch: () -> Unit,
    onJoinMatch: () -> Unit,
    onQuickPractice: () -> Unit,
    onPracticeVsAi: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(alpha = 0.82f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(24.dp))
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (hasActiveSession) {
            MenuButton("RESUME MATCH", onClick = onResumeMatch)
            Spacer(modifier = Modifier.height(16.dp))
        }
        MenuButton("CREATE MATCH", onClick = onCreateMatch)
        Spacer(modifier = Modifier.height(16.dp))
        MenuButton("JOIN MATCH", onClick = onJoinMatch)
        Spacer(modifier = Modifier.height(16.dp))
        // Off-chain instant practice — no contract, dust, or proving. Same game
        // (picker → AI → 3D replay), computed locally; nothing persisted.
        KicksButton(label = "QUICK PRACTICE", onClick = onQuickPractice, style = KicksButtonStyle.Secondary)
        Spacer(modifier = Modifier.height(16.dp))
        // On-chain match vs a local AI (deploy → commit → reveal) — real proofs.
        KicksButton(label = "VS AI · ON-CHAIN", onClick = onPracticeVsAi, style = KicksButtonStyle.Secondary)
    }
}

@Composable
private fun MenuButton(text: String, onClick: () -> Unit) {
    KicksButton(label = text, onClick = onClick)
}

/**
 * App-layer persistence of the user's selected network. The SDK is
 * network-agnostic — it takes a network config and emits onNetworkChange;
 * remembering the choice across launches is the app's responsibility, so the
 * wallet boots on the network the user last used instead of always defaulting
 * to UNDEPLOYED. Plain (non-encrypted) prefs — the network id isn't a secret.
 */
private object NetworkPref {
    private const val PREFS = "kicks_network_pref"
    private const val KEY = "selected_network"

    fun load(ctx: Context): MidnightNetwork =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
            ?.let { name -> runCatching { MidnightNetwork.valueOf(name) }.getOrNull() }
            ?: MidnightNetwork.UNDEPLOYED

    fun save(ctx: Context, network: MidnightNetwork) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, network.name)
            .apply()
    }
}
