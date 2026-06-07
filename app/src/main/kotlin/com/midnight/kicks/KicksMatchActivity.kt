package com.midnight.kicks

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Build
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.unity3d.player.UnityPlayerGameActivity

/**
 * Kicks-specific Unity host. Extends [com.unity3d.player.UnityPlayerGameActivity]
 * and injects a [ComposeView] on top of Unity's surface so the in-match
 * status banner ([MatchHudOverlay]) stays visible during the long
 * blockchain waits.
 *
 * **Runs in its own `:unity` process** (`android:process=":unity"` in the
 * manifest). That's the Approach A fix for the exit ANR: Unity's `onDestroy`
 * blocks its host's main thread for 10s+, but here that thread belongs to
 * `:unity`, not main — so the menu ([KicksActivity], main process) stays
 * responsive, and killing `:unity` on exit tears Unity down instantly without
 * touching main. See `docs/PLAN.md`.
 *
 * **The bridge spans the process boundary now.** The orchestration
 * ([MatchManager], the SDK, the [MatchHud] publisher) lives in main; this
 * activity + the Compose overlays live in `:unity`. This activity binds to
 * [MatchBridgeService] (main process), hands main its inbox via
 * [MatchBridge.MSG_REGISTER], and relays:
 *   - main → `:unity`: `MSG_TO_UNITY` → [UnityBridge.deliverToUnityPlayer];
 *     `MSG_PUBLISH_HUD` → [MatchHud.applyRemote] (overlays re-render).
 *   - `:unity` → main: Unity callbacks → `MSG_FROM_UNITY`; HUD events (the
 *     replay Continue tap → `dismissReplay`) → `MSG_HUD_FROM_UNITY`.
 *
 * **Why subclass instead of embed-as-fragment:** Unity 6's
 * [com.unity3d.player.UnityPlayerGameActivity] extends `GameActivity` which
 * manages the native window + GameActivity bridge thread. Subclassing keeps
 * that machinery untouched; the overlay only needs to sit visually above the
 * Unity surface, not share a Compose tree with it.
 *
 * **How the overlay attaches:** we `addContentView` a transparent
 * [ComposeView] *after* `super.onCreate(...)`, which adds it as a sibling of
 * Unity's surface — same FrameLayout, rendered above it because it's added
 * last.
 *
 * Manifest declares this activity with the same theme + flags as Unity's
 * default activity — see `app/src/main/AndroidManifest.xml`.
 */
class KicksMatchActivity : UnityPlayerGameActivity() {

    private companion object {
        const val TAG = "KicksMatchActivity"
    }

    /** main's inbox, obtained on bind; null until [conn] connects / after disconnect. */
    private var toMain: Messenger? = null

    /**
     * `:unity`'s own inbox. Handles messages main pushes to us. Lives on
     * `:unity`'s main looper, so [MatchHud.applyRemote] mutates state on the
     * same thread the overlays collect on, and [UnityBridge.deliverToUnityPlayer]
     * hands off on the UI thread.
     */
    private val unityInbox = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MatchBridge.MSG_TO_UNITY -> {
                    val json = msg.data?.getString(MatchBridge.KEY_JSON) ?: return
                    UnityBridge.deliverToUnityPlayer(json)
                }
                MatchBridge.MSG_PUBLISH_HUD -> {
                    val json = msg.data?.getString(MatchBridge.KEY_JSON) ?: return
                    MatchHud.applyRemote(json)
                }
                else -> super.handleMessage(msg)
            }
        }
    })

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
            val main = Messenger(binder).also { toMain = it }
            Log.i(TAG, ":unity bound to main; registering inbox")

            // Hand main our inbox (carried as replyTo, per MatchBridge).
            try {
                main.send(Message.obtain(null, MatchBridge.MSG_REGISTER).apply {
                    replyTo = unityInbox
                })
            } catch (e: RemoteException) {
                Log.w(TAG, "register failed: ${e.message}")
                toMain = null
                return
            }

            // `:unity` → main relays. Unity (running here) calls
            // UnityBridge.receiveFromUnity → onMessageFromUnity; we forward to
            // main's orchestration. The replay overlay's Continue tap calls
            // MatchHud.dismissReplay → relayHook; we forward that too.
            UnityBridge.onMessageFromUnity = { json -> sendToMain(MatchBridge.MSG_FROM_UNITY, json) }
            MatchHud.relayHook = { json -> sendToMain(MatchBridge.MSG_HUD_FROM_UNITY, json) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // main process died/restarted. Drop the reference; BIND_AUTO_CREATE
            // reconnects and re-registers when it returns.
            Log.w(TAG, ":unity lost main connection")
            toMain = null
        }
    }

    private fun sendToMain(what: Int, json: String) {
        val target = toMain ?: run {
            Log.w(TAG, "main not bound — dropping what=$what")
            return
        }
        try {
            target.send(Message.obtain(null, what).apply {
                data = Bundle().apply { putString(MatchBridge.KEY_JSON, json) }
            })
        } catch (e: RemoteException) {
            Log.w(TAG, "main inbox dead (${e.message}) — clearing")
            toMain = null
        }
    }

    /**
     * Leave the match — driven by the Compose [MatchLeaveButton]. Same two steps
     * as Unity's old GameController.RequestPause: tell main first (it cancels the
     * in-flight orchestrator + flips the menu to RESUME via handleMatchPaused),
     * then kill THIS (`:unity`) process for an instant teardown. main survives,
     * so the user lands on the live menu with no ANR. The matchPaused send is a
     * oneway binder call, queued in the kernel before the kill, so it survives.
     */
    private fun leaveMatch() {
        Log.i(TAG, "LEAVE tapped — notifying main, then killing :unity")
        sendToMain(MatchBridge.MSG_FROM_UNITY, """{"type":"matchPaused"}""")
        Process.killProcess(Process.myPid())
    }

    /**
     * End-screen exits (the match is RESOLVED, not paused — so these use their
     * own message types, not `matchPaused`, to avoid main overwriting the win
     * text with "paused" copy). Each notifies main, then kills `:unity` for the
     * same ANR-free instant teardown as [leaveMatch]; main does the navigation.
     */
    private fun endMatchToMenu() {
        Log.i(TAG, "End screen: MENU")
        sendToMain(MatchBridge.MSG_FROM_UNITY, """{"type":"endToMenu"}""")
        Process.killProcess(Process.myPid())
    }

    private fun endMatchRematch() {
        Log.i(TAG, "End screen: REMATCH")
        sendToMain(MatchBridge.MSG_FROM_UNITY, """{"type":"rematch"}""")
        Process.killProcess(Process.myPid())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Bind to the main-process bridge so the Kotlin↔Unity messages + HUD
        // snapshots flow across the process boundary. BIND_AUTO_CREATE keeps
        // main's service alive for the duration of the match.
        bindService(
            Intent(this, MatchBridgeService::class.java),
            conn,
            Context.BIND_AUTO_CREATE,
        )

        // Compose view that renders MatchHudOverlay. ViewCompositionStrategy
        // `DisposeOnViewTreeLifecycleDestroyed` ties the composition to
        // this Activity's lifecycle so flow collectors get cancelled when
        // the user backs out of the match — no leaked subscribers when
        // the user finishes a session and returns to the menu.
        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            // Touch routing: leave the ComposeView clickable (default)
            // so the replay overlay's Continue button is reachable.
            // For regions that have NO Compose consumer (i.e. the HUD
            // banner Text and the empty transparent space when the
            // replay overlay is hidden), Compose's hit testing
            // returns false and Android forwards the touch to the
            // Unity surface beneath. Net behavior: Unity receives
            // gameplay touches as long as no Composable wants them.
            //
            // If a future Compose addition accidentally puts a
            // tap-eating modifier across the screen (e.g. a
            // `.clickable { }` on a full-screen Box), Unity input
            // dies silently — flag any such modifier in review.
            setContent {
                // Box stack: the full-screen replay overlay paints
                // first (when active it dims Unity and shows the
                // scoreboard), the HUD banner paints on top so its
                // status badge stays visible during transitions.
                // When the replay isn't showing, the Box has nothing
                // to draw beyond the HUD itself.
                Box(modifier = Modifier.fillMaxSize()) {
                    // Keep this overlay window's frame loop alive from window
                    // creation. On-chain matches get this for free — MatchManager
                    // continuously publishes HUD updates that animate the overlay
                    // — but off-chain practice publishes nothing, so the window
                    // would idle and the OS throttles its frames, freezing the
                    // picker (taps mutate state but don't repaint). A continuous
                    // frame request, started before the window can idle, keeps it
                    // drawing live regardless of whether a MatchManager is active.
                    LaunchedEffect(Unit) {
                        while (true) withFrameNanos { }
                    }
                    // Full-screen wait "stage" (covers Unity's idle label +
                    // masks the commit/reveal wait). Bottom of the stack — the
                    // phase overlays below are mutually exclusive with it.
                    MatchStageOverlay()
                    MatchReplayOverlay(onRematch = ::endMatchRematch, onMenu = ::endMatchToMenu)
                    MatchHudOverlay()
                    // Picker on top — when a choice phase is open it's a focused
                    // modal over the dimmed pitch; otherwise it draws nothing.
                    MatchPickerOverlay()
                    // LEAVE button is the very top layer so it's tappable over
                    // every modal overlay (the bug: Unity's IMGUI button sat
                    // behind these and never received the tap).
                    MatchLeaveButton(onLeave = ::leaveMatch)
                }
            }
        }

        // Attach as a sibling of Unity's surface. We size the
        // ComposeView MATCH_PARENT in both dimensions because the
        // replay overlay needs to occupy the full screen when active.
        // Touch routing is handled at the Compose level (see the
        // setContent block above) — by default ComposeView passes
        // touches through to Unity when no Composable consumes them.
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        addContentView(composeView, params)

        // When the Unity scene is idle (e.g. the direction picker is up with no
        // 3D animation), the OS frame-throttles this overlay window — Choreographer
        // delivers callbacks at a fraction of vsync, so Compose can't redraw and
        // taps appear to do nothing until a stray frame arrives. Request a high
        // frame rate for the overlay so it keeps drawing live. (API 35+.)
        if (Build.VERSION.SDK_INT >= 35) {
            composeView.requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
        }
    }

    override fun onDestroy() {
        // Clear the `:unity`-side relays so a stale lambda can't fire after
        // teardown, then unbind. NOTE: the normal exit path is
        // GameController killing this process outright (instant teardown,
        // dodges Unity's slow onDestroy) — in that case this never runs and
        // the OS reclaims everything; main detects the dead inbox via
        // handleMatchPaused → MatchBridge.onUnityGone.
        UnityBridge.onMessageFromUnity = null
        MatchHud.relayHook = null
        runCatching { unbindService(conn) }
        super.onDestroy()
    }
}
