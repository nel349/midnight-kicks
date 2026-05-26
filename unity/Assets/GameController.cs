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
            case "status":
                var statusMsg = JsonUtility.FromJson<StatusMessage>(jsonString);
                Debug.Log($"[GameController] Status: {statusMsg.message}");
                break;
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

    void OnGUI()
    {
        // Leave button — top-right corner, renders in every game state
        // (waiting, choice phase, replay). Tapping it kills the :unity
        // process so the user lands back on KicksActivity (main process)
        // where the wallet + sigil pills live. Match state IS preserved:
        // it lives on chain + in MatchStore, and the menu's RESUME MATCH
        // re-drives the state machine from there. Labelled "LEAVE" (not the
        // old "II") so a first-time player knows how to get out — the
        // earlier pause glyph wasn't legible as an exit.
        float leaveWidth = 150f;
        float leaveHeight = 64f;
        float leaveMargin = 24f;
        if (GUI.Button(new Rect(Screen.width - leaveWidth - leaveMargin, leaveMargin, leaveWidth, leaveHeight), "LEAVE"))
        {
            RequestPause();
        }

        if (!inChoicePhase && !inReplay)
        {
            var style = new GUIStyle(GUI.skin.label);
            style.fontSize = 24;
            style.alignment = TextAnchor.MiddleCenter;
            style.normal.textColor = Color.white;
            GUI.Label(new Rect(0, Screen.height / 2 - 20, Screen.width, 40),
                waitingForMessage ? "Waiting for match..." : "Ready", style);
            return;
        }

        if (!inChoicePhase) return;

        float btnWidth = Screen.width / 4f;
        float btnHeight = 80f;
        float y = Screen.height - 200f;

        var labelStyle = new GUIStyle(GUI.skin.label);
        labelStyle.fontSize = 20;
        labelStyle.alignment = TextAnchor.MiddleCenter;
        labelStyle.normal.textColor = Color.white;
        GUI.Label(
            new Rect(Screen.width / 2 - 150, y - 110, 300, 30),
            $"Round {currentChoice + 1} / {choices.Length}",
            labelStyle
        );

        // Per-round role banner. Both the shoot and keep prompts have
        // the player picking a GOAL CORNER — shooter's-perspective
        // L/C/R (see GAME_DESIGN.md §2 coordinate convention). The
        // keep prompt explicitly says "predict where they'll kick"
        // instead of "pick where to dive" because the underlying
        // commit is still the predicted target corner — the dive
        // animation derives from that, not the other way around.
        var roleStyle = new GUIStyle(GUI.skin.label);
        roleStyle.fontSize = 28;
        roleStyle.fontStyle = FontStyle.Bold;
        roleStyle.alignment = TextAnchor.MiddleCenter;
        string currentRole = (currentChoice < roundRoles.Length) ? roundRoles[currentChoice] : "shoot";
        bool isShoot = currentRole == "shoot";
        roleStyle.normal.textColor = isShoot ? new Color(0.55f, 1f, 0.48f) : new Color(1f, 0.7f, 0.3f);
        GUI.Label(
            new Rect(Screen.width / 2 - 220, y - 70, 440, 50),
            isShoot ? "YOU SHOOT — pick where to kick" : "YOU KEEP — predict where they'll kick",
            roleStyle
        );

        if (GUI.Button(new Rect(Screen.width / 2 - btnWidth * 1.5f, y, btnWidth, btnHeight), "LEFT"))
            MakeChoice(0);

        if (GUI.Button(new Rect(Screen.width / 2 - btnWidth / 2, y, btnWidth, btnHeight), "CENTER"))
            MakeChoice(1);

        if (GUI.Button(new Rect(Screen.width / 2 + btnWidth / 2, y, btnWidth, btnHeight), "RIGHT"))
            MakeChoice(2);

        string choicesSoFar = "";
        for (int i = 0; i < currentChoice; i++)
        {
            choicesSoFar += choices[i] == 0 ? "L " : choices[i] == 1 ? "C " : "R ";
        }
        GUI.Label(
            new Rect(Screen.width / 2 - 100, y + btnHeight + 10, 200, 40),
            choicesSoFar
        );
    }

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
            StartCoroutine(shotManager.PlayReplay(msg.rounds, OnReplayComplete));
        }
        else
        {
            Debug.LogError("[GameController] ShotManager not found even after auto-setup — falling back to SimulateReplay");
            StartCoroutine(SimulateReplay());
        }
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
public class StatusMessage
{
    public string type;
    public string message;
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
