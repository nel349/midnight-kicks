using UnityEngine;
using System.Collections;
using System.Collections.Generic;

/// <summary>
/// Bridge between Kotlin (UaaL host) and Unity.
///
/// Receives JSON messages from Kotlin via UnitySendMessage("GameController", "OnMessage", json).
/// Sends JSON back to Kotlin via AndroidJavaObject callback.
///
/// Message types received:
///   choicePhase — player picks directions (10 for regulation, 2 for SD)
///   replay — play back match results (regulation + any SD pairings)
///   status — show a status message
///
/// Message types sent:
///   choicesLocked — player confirmed their picks
///   replayComplete — replay animation finished
/// </summary>
public class GameController : MonoBehaviour
{
    // Auto-create on scene load — no need to add to scene manually.
    // UnitySendMessage requires a GameObject named "GameController".
    [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
    static void AutoCreate()
    {
        if (FindFirstObjectByType<GameController>() != null) return;
        var go = new GameObject("GameController");
        go.AddComponent<GameController>();
        DontDestroyOnLoad(go);
        Debug.Log("[GameController] Auto-created");

        // Disable the old BallKicker so it doesn't interfere
        var oldKicker = FindFirstObjectByType<BallKicker>();
        if (oldKicker != null)
        {
            oldKicker.enabled = false;
            Debug.Log("[GameController] Disabled old BallKicker");
        }
    }

    // Player's picks for the current choice phase. Size set per
    // choicePhase message: 10 for V3 regulation (5 shoots + 5 keeps
    // interleaved), 2 for sudden death (1 shoot + 1 keep). Default 10
    // covers the regulation case until the first message arrives.
    private int[] choices = new int[10];
    private int currentChoice = 0;
    private bool inChoicePhase = false;
    private bool waitingForMessage = true;
    private string currentRound = "regulation";
    // Per-round role from this device's perspective. Indexed by
    // currentChoice. Defaults to all "shoot" of length 10 until the
    // first choicePhase message arrives.
    private string[] roundRoles = new string[] {
        "shoot", "shoot", "shoot", "shoot", "shoot",
        "shoot", "shoot", "shoot", "shoot", "shoot",
    };

    // Replay state
    private bool inReplay = false;
    private ShotManager shotManager;

    void Start()
    {
        EnsureShotManager();
    }

    /// <summary>
    /// Entry point for messages from Kotlin.
    /// Called via UnitySendMessage("GameController", "OnMessage", jsonString).
    /// </summary>
    public void OnMessage(string jsonString)
    {
        Debug.Log($"[GameController] Received: {jsonString}");

        var json = JsonUtility.FromJson<BridgeMessage>(jsonString);

        switch (json.type)
        {
            case "choicePhase":
                StartChoicePhase(jsonString);
                break;
            case "replay":
                StartReplay(jsonString);
                break;
            case "playerAppearance":
                ApplyPlayerAppearance(jsonString);
                break;
            // "status" removed: status is now the Compose HUD/stage (Kotlin no
            // longer sends it; sendStatus is gone).
            default:
                Debug.LogWarning($"[GameController] Unknown message type: {json.type}");
                break;
        }
    }

    // ── Choice Phase ──

    private void StartChoicePhase(string json)
    {
        var msg = JsonUtility.FromJson<ChoicePhaseMessage>(json);
        currentRound = msg.round;
        // V3: regulation sends 10 roles (5 shoots + 5 keeps interleaved
        // by per-round shooter), sudden death sends 2 (1 shoot + 1 keep).
        // We accept any non-empty length so the bridge owns the count.
        if (msg.roles != null && msg.roles.Length > 0)
        {
            roundRoles = msg.roles;
        }
        else
        {
            Debug.LogWarning($"[GameController] choicePhase missing/invalid roles array — defaulting to 10×shoot");
            roundRoles = new string[] {
                "shoot","shoot","shoot","shoot","shoot",
                "shoot","shoot","shoot","shoot","shoot",
            };
        }
        currentChoice = 0;
        choices = new int[roundRoles.Length];
        inChoicePhase = true;
        waitingForMessage = false;
        inReplay = false;

        Debug.Log($"[GameController] Choice phase: round={currentRound}, picks={roundRoles.Length}, roles=[{string.Join(",", roundRoles)}]");
    }

    // ── Player appearance ──

    /// <summary>
    /// Dress the Shooter in the local player's chosen kit and the Keeper in the
    /// contrasting opponent kit. Colours arrive as #RRGGBB hex in the Kotlin
    /// `playerAppearance` message — purely local cosmetics, nothing on-chain.
    /// Stored statically on the appearance scripts, so it survives a Shooter/
    /// Keeper that hasn't spawned yet (applied on their next dress).
    /// </summary>
    private void ApplyPlayerAppearance(string json)
    {
        var msg = JsonUtility.FromJson<PlayerAppearanceMessage>(json);
        if (msg == null || msg.player == null)
        {
            Debug.LogWarning("[GameController] playerAppearance: empty payload");
            return;
        }

        if (TryParseKit(msg.player, out Color pj, out Color ps, out Color pk))
        {
            ShooterAppearance.SetKit(pj, ps, pk);
            Debug.Log($"[GameController] Shooter kit ← {msg.playerName} (#{ColorUtility.ToHtmlStringRGB(pj)})");
        }
        if (msg.opponent != null && TryParseKit(msg.opponent, out Color oj, out Color os, out Color ok))
        {
            KeeperAppearance.SetKit(oj, os, ok);
            Debug.Log($"[GameController] Keeper kit ← opponent (#{ColorUtility.ToHtmlStringRGB(oj)})");
        }
    }

    private static bool TryParseKit(KitColorsJson kit, out Color jersey, out Color shorts, out Color socks)
    {
        bool ok = ColorUtility.TryParseHtmlString(kit.jersey, out jersey);
        ok &= ColorUtility.TryParseHtmlString(kit.shorts, out shorts);
        ok &= ColorUtility.TryParseHtmlString(kit.socks, out socks);
        return ok;
    }

    // OnGUI removed: every in-match 2D element now lives in the Android Compose
    // overlays on top of Unity's surface —
    //   - LEAVE button       → MatchLeaveButton (kills :unity)
    //   - "Waiting" / status  → MatchStageOverlay + MatchHudOverlay
    //   - direction picker    → MatchPickerOverlay (grouped shoots-then-keeps)
    //   - replay scoreboard   → MatchReplayOverlay (thin HUD over the kicks)
    // Unity is now a pure 3D renderer: the pitch + the kick cinematic
    // (StartReplay / ShotManager.PlayReplay). The choicePhase handler +
    // MakeChoice + RequestPause below are now dead (Kotlin drives the picker and
    // the leave action) and can be pruned in a later pass.

    private void MakeChoice(int direction)
    {
        choices[currentChoice] = direction;
        currentChoice++;

        Debug.Log($"[GameController] Choice {currentChoice}: {direction}");

        if (currentChoice >= choices.Length)
        {
            inChoicePhase = false;
            SendChoicesToKotlin();
        }
    }

    private void SendChoicesToKotlin()
    {
        // Build the picks array dynamically — choices.Length is 10 for
        // regulation, 2 for SD, both must round-trip cleanly through
        // KicksActivity.handleChoicesLocked.
        var sb = new System.Text.StringBuilder();
        sb.Append("{\"type\":\"choicesLocked\",\"choices\":[");
        for (int i = 0; i < choices.Length; i++)
        {
            if (i > 0) sb.Append(',');
            sb.Append(choices[i]);
        }
        sb.Append("]}");
        string json = sb.ToString();
        Debug.Log($"[GameController] Sending choices: {json}");
        SendToKotlin(json);
    }

    // ── Replay ──

    private void EnsureShotManager()
    {
        if (shotManager != null) return;

        shotManager = FindFirstObjectByType<ShotManager>();
        if (shotManager == null)
        {
            var go = new GameObject("ShotManager");
            shotManager = go.AddComponent<ShotManager>();
            shotManager.ballKicker = FindFirstObjectByType<BallKicker>();
            shotManager.keeper = FindFirstObjectByType<Keeper>();
            Debug.Log("[GameController] Auto-created ShotManager");
        }
    }

    private void StartReplay(string json)
    {
        Debug.Log($"[GameController] Starting replay");
        var msg = JsonUtility.FromJson<ReplayMessage>(json);

        inReplay = true;
        inChoicePhase = false;

        EnsureShotManager();

        if (shotManager != null)
        {
            Debug.Log("[GameController] Dispatching to ShotManager.PlayReplay");
            StartCoroutine(shotManager.PlayReplay(msg.rounds, OnRoundResolved, OnReplayComplete));
        }
        else
        {
            Debug.LogError("[GameController] ShotManager not found even after auto-setup — falling back to SimulateReplay");
            StartCoroutine(SimulateReplay());
        }
    }

    // Fired by ShotManager after each kick resolves. The Compose replay overlay
    // uses it to flash GOAL!/SAVED! and climb its live score chip in step with
    // the 3D action. Only the index travels; Kotlin owns the authoritative
    // round outcome.
    private void OnRoundResolved(int index)
    {
        SendToKotlin("{\"type\":\"roundResult\",\"index\":" + index + "}");
    }

    private void OnReplayComplete()
    {
        inReplay = false;
        string json = "{\"type\":\"replayComplete\"}";
        SendToKotlin(json);
    }

    private IEnumerator SimulateReplay()
    {
        yield return new WaitForSeconds(3f);
        OnReplayComplete();
    }

    // ── Kotlin Communication ──

    /// <summary>
    /// Leave = notify Kotlin, then kill THIS (:unity) process. Match state IS
    /// preserved: it lives on chain + in MatchStore, and the menu's RESUME
    /// MATCH re-drives the state machine from there.
    ///
    /// Unity now runs in its own ":unity" process (android:process=":unity").
    /// We still kill rather than currentActivity.finish() because Unity's
    /// onDestroy takes 10s+ to tear down on emulators (audio, rendering,
    /// IL2CPP unload) — killing skips that entirely so the user returns to the
    /// menu instantly. And because it's a *separate* process, the kill can't
    /// touch the main thread that hosts KicksActivity, so there's no ANR
    /// (which is the whole reason the process was split out — see docs/PLAN.md).
    ///
    /// The matchPaused message must go out BEFORE the kill: Kotlin uses it to
    /// cancel the in-flight orchestrator and clear the dead :unity Messenger
    /// (KicksActivity.handleMatchPaused). Messenger.send is a oneway binder
    /// call queued in the kernel, so it survives this process dying.
    /// </summary>
    private void RequestPause()
    {
        Debug.Log("[GameController] Leave pressed — notifying Kotlin, then killing :unity");
        SendToKotlin("{\"type\":\"matchPaused\"}");

#if UNITY_ANDROID && !UNITY_EDITOR
        try
        {
            using (var processClass = new AndroidJavaClass("android.os.Process"))
            {
                int pid = processClass.CallStatic<int>("myPid");
                processClass.CallStatic("killProcess", pid);
            }
        }
        catch (System.Exception e)
        {
            Debug.LogError($"[GameController] Failed to kill process: {e.Message}");
        }
#endif
    }

    private void SendToKotlin(string json)
    {
        Debug.Log($"[GameController] → Kotlin: {json}");

#if UNITY_ANDROID && !UNITY_EDITOR
        try
        {
            using (var bridge = new AndroidJavaClass("com.midnight.kicks.UnityBridge"))
            {
                bridge.CallStatic("receiveFromUnity", json);
            }
        }
        catch (System.Exception e)
        {
            Debug.LogError($"[GameController] Failed to send to Kotlin: {e.Message}");
        }
#else
        Debug.Log($"[GameController] (Editor mode) Would send to Kotlin: {json}");
#endif
    }
}

// ── JSON message types ──

[System.Serializable]
public class BridgeMessage
{
    public string type;
}

[System.Serializable]
public class ChoicePhaseMessage
{
    public string type;
    public string round;
    /// <summary>
    /// Per-round role from THIS device's perspective, each entry
    /// "shoot" or "keep". Length is set by Kotlin and tells Unity how
    /// many picks to gather: 10 for V3 regulation (5 shoots + 5 keeps
    /// interleaved by the contract's i % 2 == 0 → P1 shoots rule), 2
    /// for sudden death (1 shoot + 1 keep). Unity uses
    /// `roles[currentChoice]` to label each pick "YOU SHOOT" / "YOU
    /// KEEP" and sizes its `choices` buffer from this array.
    /// </summary>
    public string[] roles;
}

[System.Serializable]
public class PlayerAppearanceMessage
{
    public string type;
    public string playerName;
    public KitColorsJson player;
    public KitColorsJson opponent;
}

[System.Serializable]
public class KitColorsJson
{
    public string jersey; // #RRGGBB
    public string shorts; // #RRGGBB
    public string socks;  // #RRGGBB
}

[System.Serializable]
public class ReplayMessage
{
    public string type;
    public List<RoundData> rounds;
}

[System.Serializable]
public class RoundData
{
    public int round;
    public string shooter;
    public int shootDir;
    public int keepDir;
    public string result;
}
