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
///   choicePhase — player picks 5 directions
///   replay — play back regulation results
///   suddenDeathReplay — play back sudden death results
///   status — show a status message
///
/// Message types sent:
///   choicesLocked — player confirmed 5 choices
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

    // Player's 5 choices for the current round
    private int[] choices = new int[5];
    private int currentChoice = 0;
    private bool inChoicePhase = false;
    private bool waitingForMessage = true;
    private string currentRound = "regulation";
    // Per-round role from this device's perspective. Indexed by
    // currentChoice (0..4). Defaults to all "shoot" until the first
    // choicePhase message arrives.
    private string[] roundRoles = new string[] { "shoot", "shoot", "shoot", "shoot", "shoot" };

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
            case "suddenDeathReplay":
                StartSuddenDeathReplay(jsonString);
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
        if (msg.roles != null && msg.roles.Length == 5)
        {
            roundRoles = msg.roles;
        }
        else
        {
            Debug.LogWarning($"[GameController] choicePhase missing/invalid roles array — defaulting to all-shoot");
            roundRoles = new string[] { "shoot", "shoot", "shoot", "shoot", "shoot" };
        }
        currentChoice = 0;
        choices = new int[5];
        inChoicePhase = true;
        waitingForMessage = false;
        inReplay = false;

        Debug.Log($"[GameController] Choice phase: round={currentRound}, roles=[{string.Join(",", roundRoles)}]");
    }

    void OnGUI()
    {
        // Pause button — top-right corner, renders in every game state
        // (waiting, choice phase, replay). Tapping it finishes the Unity
        // activity so the user lands back on KicksActivity where the
        // wallet + sigil pills live. Match state is not preserved — pause
        // here is "exit match", documented in the panel adoption design.
        float pauseSize = 64f;
        float pauseMargin = 24f;
        if (GUI.Button(new Rect(Screen.width - pauseSize - pauseMargin, pauseMargin, pauseSize, pauseSize), "II"))
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
            $"Round {currentChoice + 1} / 5",
            labelStyle
        );

        // Per-round role banner. Shoot rounds are about offense
        // (kick where keeper isn't); keep rounds are about defense
        // (dive where shooter aims). The contract uses the same
        // committed direction for both — but the player can think
        // about each pick in the right frame.
        var roleStyle = new GUIStyle(GUI.skin.label);
        roleStyle.fontSize = 28;
        roleStyle.fontStyle = FontStyle.Bold;
        roleStyle.alignment = TextAnchor.MiddleCenter;
        string currentRole = (currentChoice < roundRoles.Length) ? roundRoles[currentChoice] : "shoot";
        bool isShoot = currentRole == "shoot";
        roleStyle.normal.textColor = isShoot ? new Color(0.55f, 1f, 0.48f) : new Color(1f, 0.7f, 0.3f);
        GUI.Label(
            new Rect(Screen.width / 2 - 200, y - 70, 400, 50),
            isShoot ? "YOU SHOOT — pick where to kick" : "YOU KEEP — pick where to dive",
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

        if (currentChoice >= 5)
        {
            inChoicePhase = false;
            SendChoicesToKotlin();
        }
    }

    private void SendChoicesToKotlin()
    {
        string json = $"{{\"type\":\"choicesLocked\",\"choices\":[{choices[0]},{choices[1]},{choices[2]},{choices[3]},{choices[4]}]}}";
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

    private void StartSuddenDeathReplay(string json)
    {
        Debug.Log($"[GameController] Starting sudden death replay");
        var msg = JsonUtility.FromJson<ReplayMessage>(json);

        inReplay = true;
        inChoicePhase = false;

        EnsureShotManager();

        if (shotManager != null)
        {
            Debug.Log("[GameController] Dispatching to ShotManager.PlayReplay (SD)");
            StartCoroutine(shotManager.PlayReplay(msg.rounds, OnReplayComplete));
        }
        else
        {
            Debug.LogError("[GameController] ShotManager not found even after auto-setup — falling back to SimulateReplay (SD)");
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
    /// Pause = kill the process. Match state is not preserved — see the
    /// panel adoption design doc (option A) for the rationale.
    ///
    /// We kill the whole process instead of calling currentActivity.finish()
    /// because Unity and KicksActivity share the same OS process, and Unity's
    /// onDestroy can take 10s+ to tear down on emulators (audio, rendering,
    /// IL2CPP unload). During that teardown the shared main thread is
    /// blocked, so any touch on the now-foreground KicksActivity ANRs after
    /// 5s. Killing the process bypasses the destroy phase entirely.
    ///
    /// User restarts the game by tapping the Kicks icon from launcher/recents
    /// (singleTask launch mode → fresh KicksActivity in a clean state).
    ///
    /// Phase 5 polish should move Unity to its own process via
    /// android:process=":unity" so this hard-kill becomes unnecessary, but
    /// that requires re-plumbing UnityBridge across processes (AIDL/IPC).
    /// </summary>
    private void RequestPause()
    {
        Debug.Log("[GameController] Pause pressed — killing process");
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
    /// Per-round role from THIS device's perspective. 5 entries, each
    /// "shoot" or "keep". Kotlin computes it from the player's P1/P2
    /// role + the contract's `i % 2 == 0 → P1 shoots` rule. Unity uses
    /// `roles[currentChoice]` to label each pick "YOU SHOOT" / "YOU KEEP".
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
