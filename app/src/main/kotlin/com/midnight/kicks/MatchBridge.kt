package com.midnight.kicks

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log

/**
 * Cross-process channel between the **main** process (orchestration:
 * `MatchManager`, the SDK, the `MatchHud` publisher) and the **`:unity`**
 * process (Unity renderer + the Compose overlays).
 *
 * **Why this exists (Approach A — see `docs/PLAN.md`).** Unity's `onDestroy`
 * blocks the shared main thread for 10s+ → ANR on exit. Moving Unity to its
 * own process (`android:process=":unity"`) fixes that, but the bridge that was
 * an in-process `object` ([UnityBridge]) + singleton ([MatchHud]) now spans two
 * processes. This relays across the boundary:
 *  - **main → `:unity`:** Unity-bound JSON (`choicePhase` / `replay` / `status`)
 *    and `MatchHud` snapshots for the overlays.
 *  - **`:unity` → main:** JSON from Unity (`choicesLocked` / `matchPaused`).
 *
 * **Transport.** A pair of [Messenger]s exchanged on bind: main hosts
 * [MatchBridgeService]; `:unity` binds, gets main's inbox via `onBind`, and
 * registers its own inbox with [MSG_REGISTER]. Message volume is low (a few per
 * phase) so Messenger is plenty — no AIDL.
 *
 * **This object is the main-side half.** It is a per-process `object`, so in
 * `:unity` a *separate* instance exists; `:unity` does not use these members —
 * its relay (bind + its own inbox) lives in `KicksMatchActivity`.
 */
object MatchBridge {
    const val TAG = "MatchBridge"

    // message.what codes (shared by both ends)
    const val MSG_REGISTER = 1    // :unity → main: msg.replyTo carries :unity's inbox
    const val MSG_TO_UNITY = 2    // main → :unity: KEY_JSON → UnitySendMessage
    const val MSG_PUBLISH_HUD = 3 // main → :unity: KEY_JSON = serialized HUD snapshot
    const val MSG_FROM_UNITY = 4  // :unity → main: KEY_JSON from Unity

    const val KEY_JSON = "json"

    /** :unity's inbox, learned on [MSG_REGISTER]; null when no match process is bound. */
    @Volatile private var unityInbox: Messenger? = null

    /**
     * Main's inbox — receives [MSG_REGISTER] + [MSG_FROM_UNITY] from `:unity`.
     * Returned to `:unity` as the bind result by [MatchBridgeService].
     */
    val mainInbox: Messenger by lazy {
        Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                when (msg.what) {
                    MSG_REGISTER -> {
                        unityInbox = msg.replyTo
                        Log.i(TAG, "main: :unity registered its inbox")
                    }
                    MSG_FROM_UNITY -> {
                        val json = msg.data?.getString(KEY_JSON) ?: return
                        // Same callback the in-process UnityBridge fed; the
                        // orchestration (KicksActivity) is unchanged downstream.
                        UnityBridge.onMessageFromUnity?.invoke(json)
                    }
                    else -> super.handleMessage(msg)
                }
            }
        })
    }

    /** main → `:unity`: deliver JSON for Unity's `UnitySendMessage`. */
    fun sendToUnity(json: String) = relay(MSG_TO_UNITY, json)

    /** main → `:unity`: a serialized [MatchHud] snapshot for the overlays. */
    fun publishHud(serializedSnapshot: String) = relay(MSG_PUBLISH_HUD, serializedSnapshot)

    private fun relay(what: Int, json: String) {
        val target = unityInbox ?: run {
            // Pre-bind (or after :unity died) we drop — the orchestrator
            // re-sends on the next phase, and the overlay re-reads HUD on bind.
            Log.w(TAG, ":unity not bound — dropping what=$what")
            return
        }
        try {
            target.send(Message.obtain(null, what).apply {
                data = Bundle().apply { putString(KEY_JSON, json) }
            })
        } catch (e: RemoteException) {
            Log.w(TAG, ":unity inbox dead (${e.message}) — clearing")
            unityInbox = null
        }
    }

    /** Called when the `:unity` process goes away (match exit / kill). */
    fun onUnityGone() {
        unityInbox = null
    }
}

/**
 * Bound service in the **main** process. `:unity`'s `KicksMatchActivity` binds
 * here to obtain main's inbox ([MatchBridge.mainInbox]); it then registers its
 * own inbox via [MatchBridge.MSG_REGISTER]. No `android:process` in the
 * manifest → this runs in main.
 */
class MatchBridgeService : Service() {
    override fun onBind(intent: Intent?): IBinder = MatchBridge.mainInbox.binder
}
