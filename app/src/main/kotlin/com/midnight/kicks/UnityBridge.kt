package com.midnight.kicks

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON bridge between Kotlin and Unity (UaaL).
 *
 * Kotlin → Unity: via UnitySendMessage (static call to Unity's player)
 * Unity → Kotlin: via AndroidJavaObject callback (Unity calls our Java method)
 *
 * All messages are JSON strings with a "type" field for routing.
 */
object UnityBridge {

    private const val TAG = "UnityBridge"
    private const val GAME_OBJECT = "GameController"

    /** Callback from Unity — set by KicksViewModel to receive messages. */
    var onMessageFromUnity: ((String) -> Unit)? = null

    private val _replayCinematicDoneAt = MutableStateFlow(0L)

    /**
     * Wall-clock time Unity last reported a replay cinematic finished
     * (`replayComplete`). The replay overlay — running in `:unity`, the same
     * process that `replayComplete` lands in — collects this to know exactly
     * when the kicks have stopped, so it holds its result HUD back until then
     * rather than guessing with a timer and risking the HUD landing on top of a
     * kick still in flight. Carries a timestamp so a collector can ignore
     * completions that predate the cinematic it just fired.
     */
    val replayCinematicDoneAt: StateFlow<Long> = _replayCinematicDoneAt.asStateFlow()

    /** A replay kick resolving in Unity: which kick ([index]) and when ([atMs]). */
    data class ReplayKick(val atMs: Long, val index: Int)

    private val _replayKick = MutableStateFlow(ReplayKick(0L, -1))

    /**
     * Emitted as each kick lands during the cinematic (Unity's per-round
     * `roundResult`). The replay overlay flashes GOAL!/SAVED! and climbs its
     * live score chip off this, in step with the 3D action. Carries [atMs] so a
     * collector can ignore kicks that predate the cinematic it just fired.
     */
    val replayKick: StateFlow<ReplayKick> = _replayKick.asStateFlow()

    // ── Kotlin → Unity ──
    //
    // The choice phase no longer goes to Unity: the picker is the Compose
    // overlay [MatchPickerOverlay], driven by [MatchHud.showPicker]. Unity owns
    // the 3D scene + the replay cinematic below.

    /**
     * Play the 3D kick cinematic for [rounds] — the main event of the replay.
     * Called by [MatchReplayOverlay] (which runs in `:unity`) the instant the
     * replay appears, so the kicks are shown immediately rather than gated
     * behind a text scoreboard + Continue. Uses [deliverToUnityPlayer] (the
     * `:unity`-local hand-off) since the caller is already in `:unity`.
     */
    fun playReplayCinematic(rounds: List<RoundResult>, p1Score: Int, p2Score: Int, winner: String?) {
        val json = JSONObject().apply {
            put("type", "replay")
            put("rounds", roundsToJson(rounds))
            put("finalScore", JSONObject().apply {
                put("p1", p1Score)
                put("p2", p2Score)
            })
            put("winner", winner ?: JSONObject.NULL)
        }
        deliverToUnityPlayer(json.toString())
    }

    /**
     * main → `:unity`: the local player's kit and the contrasting opponent kit,
     * applied by Unity's appearance scripts at kickoff (see ShooterAppearance /
     * KeeperAppearance). Sent during the Unity-boot window, before the picker.
     * Pure local cosmetics — none of this is on-chain. Uses [relayToUnity] since
     * the caller (KicksActivity) runs in main, not `:unity`.
     */
    fun sendPlayerAppearance(playerName: String, playerKit: KitColors, opponentKit: KitColors) {
        val json = JSONObject().apply {
            put("type", "playerAppearance")
            put("playerName", playerName)
            put("player", kitToJson(playerKit))
            put("opponent", kitToJson(opponentKit))
        }
        relayToUnity(json.toString())
    }

    private fun kitToJson(kit: KitColors): JSONObject = JSONObject().apply {
        put("jersey", kit.jersey.toHex())
        put("shorts", kit.shorts.toHex())
        put("socks", kit.socks.toHex())
    }

    // Status (waiting / proving / reconnecting / …) is no longer sent to Unity:
    // it's the Compose MatchHudOverlay + MatchStageOverlay, driven by MatchHud.
    // (The old sendStatus → Unity IMGUI label is gone.)

    // ── Unity → Kotlin ──

    /**
     * Called by Unity's GameController via AndroidJavaObject.
     * This is the entry point for all messages FROM Unity.
     * Must be called on the Unity thread — posts to main thread via callback.
     */
    @JvmStatic
    fun receiveFromUnity(jsonString: String) {
        Log.d(TAG, "← Unity: $jsonString")
        // Surface replay timing to the :unity-local overlay (see
        // [replayCinematicDoneAt] / [replayKick]) before relaying onward to main.
        val now = System.currentTimeMillis()
        runCatching { JSONObject(jsonString) }.getOrNull()?.let { o ->
            when (o.optString("type")) {
                "replayComplete" -> _replayCinematicDoneAt.value = now
                "roundResult" -> _replayKick.value = ReplayKick(now, o.optInt("index", -1))
            }
        }
        onMessageFromUnity?.invoke(jsonString)
    }

    /**
     * The Compose picker ([MatchPickerOverlay]) submitting its locked picks —
     * the post-IMGUI replacement for Unity's `choicesLocked`. Feeds the SAME
     * [onMessageFromUnity] callback Unity's picker did, so downstream
     * orchestration ([KicksActivity.handleChoicesLocked]) is unchanged — it
     * can't tell the picks came from Compose rather than the Unity player.
     */
    fun submitLocalPicks(choices: IntArray) {
        val json = JSONObject().apply {
            put("type", "choicesLocked")
            put("choices", JSONArray().apply { choices.forEach { put(it) } })
        }
        receiveFromUnity(json.toString())
    }

    // ── Internal ──

    /**
     * main → `:unity`: relay the Unity-bound JSON across the process boundary
     * via [MatchBridge]. Orchestration (MatchManager, the SDK) lives in main,
     * but Unity's player lives in `:unity`, so we can't call UnitySendMessage
     * here — the `:unity` relay does that on receipt (see [deliverToUnityPlayer]).
     *
     * This object is a per-process `object`; these `send*` entry points are only
     * ever called from main, so this always relays outward.
     */
    private fun relayToUnity(json: String) {
        Log.d(TAG, "→ (relay) Unity: $json")
        MatchBridge.sendToUnity(json)
    }

    /**
     * `:unity`-side: hand the relayed JSON to the running Unity player. Called
     * ONLY by the `:unity` relay (KicksMatchActivity) on a `MSG_TO_UNITY` —
     * never from main, where UnityPlayer isn't loaded. The Unity-side method is
     * always `OnMessage`; the `type` field inside the JSON does the routing.
     */
    fun deliverToUnityPlayer(json: String) {
        Log.d(TAG, "→ Unity (player): $json")
        try {
            // UnitySendMessage is available when Unity player is running
            val unityPlayerClass = Class.forName("com.unity3d.player.UnityPlayer")
            val sendMethod = unityPlayerClass.getMethod(
                "UnitySendMessage", String::class.java, String::class.java, String::class.java
            )
            sendMethod.invoke(null, GAME_OBJECT, "OnMessage", json)
        } catch (e: Exception) {
            Log.w(TAG, "Unity not available: ${e.message}")
        }
    }

    private fun roundsToJson(rounds: List<RoundResult>): JSONArray {
        return JSONArray().apply {
            rounds.forEach { round ->
                put(JSONObject().apply {
                    put("round", round.round)
                    put("shooter", round.shooter)
                    put("shootDir", round.shootDir)
                    put("keepDir", round.keepDir)
                    put("result", round.result)
                })
            }
        }
    }
}

/** A single round result for replay. */
data class RoundResult(
    val round: Int,
    val shooter: String, // "P1" or "P2"
    val shootDir: Int,   // 0=left, 1=center, 2=right
    val keepDir: Int,    // 0=left, 1=center, 2=right
    val result: String,  // "goal" or "save"
)
