using UnityEngine;
using UnityEngine.InputSystem;
using System.Collections;
using System.Collections.Generic;

public class ShotManager : MonoBehaviour
{
    public BallKicker ballKicker;
    public Keeper keeper;

    public Vector3 shooterStartPos = new Vector3(-1.2f, 0.5f, -3.2f);
    public Vector3 shooterKickPos = new Vector3(-0.3f, 0.5f, -0.2f);

    // ── Result keys (must match JSON payload from Kotlin's MatchManager) ──
    private const string ResultGoal = "goal";

    // ── Scene lookup ──
    private const string ShooterObjectName = "Shooter";

    // ── Animator state names. Must match ShooterController / KeeperController ──
    private const string AnimIdle = "Idle";
    private const string AnimRun = "Run";
    private const string AnimKick = "Kick";

    // ── Choreography timings (seconds) ──
    private const float IntroDuration = 2.2f;
    private const float PreRunHold = 1.2f;
    private const float RunDuration = 0.9f;
    private const float KickWindupDuration = 0.75f;
    private const float KeeperDiveDelay = 0.05f;
    private const float KeeperDiveDuration = 0.8f;
    private const float BallFlightDuration = 1.2f;
    private const float ReactionDuration = 1.8f;
    private const float ScoreHoldBetweenRounds = 1f;
    private const float FinalResultHold = 4f;

    // ── Reaction motion ──
    private const float CelebrationHopHeight = 0.35f;
    private const float CelebrationHops = 2f;
    private const float DefeatLeanDegrees = 20f;

    // ── Camera framing ──
    private static readonly Vector3 GoalLookTarget = new Vector3(0, 1.4f, 11f);
    private static readonly Vector3 IntroStartCam = new Vector3(-6f, 2.5f, -1f);
    private static readonly Vector3 PlayCamPos = new Vector3(-4f, 1.7f, -3f);

    // ── UI style font sizes ──
    private const int ScoreFontSize = 32;
    private const int ResultFontSize = 72;
    private const int FeedbackFontSize = 64;

    // ── Runtime state ──
    private GameObject shooter;
    private Animator shooterAnim;
    private Quaternion shooterInitialRotation;
    private Camera mainCamera;
    private GUIStyle scoreStyle;
    private GUIStyle resultStyle;
    private GUIStyle feedbackStyle;
    private bool isPlaying = false;
    private bool showResult = false;
    private string resultMessage = "";
    private string currentFeedback = "";
    private string currentScore = "P1: 0 - P2: 0";
    private Color feedbackColor = Color.white;

    void Update()
    {
        // Debug: press T to test a single goal replay in Editor
        if (Keyboard.current != null && Keyboard.current.tKey.wasPressedThisFrame && !isPlaying)
        {
            List<RoundData> testRounds = new List<RoundData> {
                new RoundData { round = 1, shooter = "P1", shootDir = 1, keepDir = 0, result = ResultGoal }
            };
            StartCoroutine(PlayReplay(testRounds, null));
        }
    }

    private void CacheShooter()
    {
        if (shooter == null)
        {
            shooter = GameObject.Find(ShooterObjectName);
            if (shooter != null)
            {
                shooterAnim = shooter.GetComponent<Animator>();
                shooterInitialRotation = shooter.transform.rotation;
            }
        }

        if (ballKicker == null) ballKicker = FindAnyObjectByType<BallKicker>();
        if (keeper == null) keeper = FindAnyObjectByType<Keeper>();
        if (mainCamera == null) mainCamera = Camera.main;

        Debug.Log($"[ShotManager] CacheShooter: shooter={(shooter != null)} " +
                  $"shooterAnim={(shooterAnim != null)} " +
                  $"ballKicker={(ballKicker != null)} " +
                  $"keeper={(keeper != null)} " +
                  $"mainCamera={(mainCamera != null)}");
    }

    public IEnumerator PlayReplay(List<RoundData> rounds, System.Action onComplete)
    {
        Debug.Log($"[ShotManager] PlayReplay START rounds={rounds.Count}");
        float startTime = Time.realtimeSinceStartup;

        isPlaying = true;
        showResult = false;
        CacheShooter();

        yield return StartCoroutine(PlayIntro());

        int p1Score = 0;
        int p2Score = 0;
        currentScore = "P1: 0 - P2: 0";

        for (int i = 0; i < rounds.Count; i++)
        {
            var round = rounds[i];
            Debug.Log($"[ShotManager] Round {i + 1}/{rounds.Count} shooter={round.shooter} " +
                      $"shootDir={round.shootDir} keepDir={round.keepDir} result={round.result}");
            yield return StartCoroutine(PlayRound(round));

            if (round.result == ResultGoal)
            {
                if (round.shooter == "P1") p1Score++;
                else p2Score++;
            }

            currentScore = $"P1: {p1Score} - P2: {p2Score}";
            yield return new WaitForSeconds(ScoreHoldBetweenRounds);
        }

        if (p1Score > p2Score) resultMessage = "PLAYER 1 WINS!";
        else if (p2Score > p1Score) resultMessage = "PLAYER 2 WINS!";
        else resultMessage = "DRAW!";

        showResult = true;
        yield return new WaitForSeconds(FinalResultHold);
        showResult = false;

        isPlaying = false;
        float duration = Time.realtimeSinceStartup - startTime;
        Debug.Log($"[ShotManager] PlayReplay END after {duration:F1}s, final={resultMessage}");
        onComplete?.Invoke();
    }

    private IEnumerator PlayIntro()
    {
        currentFeedback = "GET READY...";
        float elapsed = 0f;

        // `Camera.main` does a tag-search internally; cache the transform.
        Transform cam = mainCamera != null ? mainCamera.transform : null;
        if (cam == null)
        {
            currentFeedback = "";
            yield break;
        }

        while (elapsed < IntroDuration)
        {
            elapsed += Time.deltaTime;
            float t = Mathf.SmoothStep(0f, 1f, elapsed / IntroDuration);
            cam.position = Vector3.Lerp(IntroStartCam, PlayCamPos, t);
            cam.LookAt(GoalLookTarget);
            yield return null;
        }

        cam.position = PlayCamPos;
        cam.LookAt(GoalLookTarget);
        currentFeedback = "";
    }

    private IEnumerator PlayRound(RoundData round)
    {
        if (ballKicker != null) ballKicker.ResetBall();
        if (keeper != null) keeper.Reset();
        if (shooter != null)
        {
            shooter.transform.position = shooterStartPos;
            shooter.transform.rotation = shooterInitialRotation;
            if (shooterAnim != null) shooterAnim.Play(AnimIdle);
        }

        currentFeedback = "";
        yield return new WaitForSeconds(PreRunHold);

        if (shooterAnim != null) shooterAnim.Play(AnimRun);

        float elapsed = 0f;
        while (elapsed < RunDuration)
        {
            elapsed += Time.deltaTime;
            float t = elapsed / RunDuration;
            if (shooter != null)
                shooter.transform.position = Vector3.Lerp(shooterStartPos, shooterKickPos, t);
            yield return null;
        }
        if (shooter != null)
            shooter.transform.position = shooterKickPos;

        if (shooterAnim != null) shooterAnim.Play(AnimKick);
        yield return new WaitForSeconds(KickWindupDuration);

        if (ballKicker != null) ballKicker.KickTo(round.shootDir);
        yield return new WaitForSeconds(KeeperDiveDelay);
        if (keeper != null) keeper.Dive(round.keepDir, KeeperDiveDuration);

        yield return new WaitForSeconds(BallFlightDuration);

        currentFeedback = round.result.ToUpper() + "!";
        feedbackColor = round.result == ResultGoal ? Color.green : Color.yellow;

        // Post-kick reactions. With only Idle / Run / Kick on the shooter and
        // Idle / Dive* / FallenIdle on the keeper, we layer procedural motion
        // on top of the existing states rather than introducing new clips.
        yield return StartCoroutine(PlayReaction(round.result));
    }

    /// <summary>
    /// Procedural reaction after the ball lands. Uses transform offsets on top
    /// of whatever animator state is currently playing so we don't need new
    /// animation clips. Runs for ~1.8s, matching the previous feedback hold.
    /// </summary>
    private IEnumerator PlayReaction(string result)
    {
        float elapsed = 0f;

        Vector3 shooterBase = shooter != null ? shooter.transform.position : Vector3.zero;
        Quaternion shooterBaseRot = shooter != null ? shooter.transform.rotation : Quaternion.identity;
        bool isGoal = result == ResultGoal;

        // Shooter celebrates by re-entering Idle (relaxed pose, hops layered on top).
        // For a save, we hold the Kick follow-through pose and lean forward instead.
        // Keeper stays in FallenIdle either way (transition handled by Keeper.Update).
        if (isGoal && shooterAnim != null) shooterAnim.Play(AnimIdle);

        while (elapsed < ReactionDuration)
        {
            elapsed += Time.deltaTime;
            float t = elapsed / ReactionDuration;

            if (shooter != null)
            {
                if (isGoal)
                {
                    // Celebratory hops across the reaction window.
                    float hop = Mathf.Abs(Mathf.Sin(t * Mathf.PI * CelebrationHops)) * CelebrationHopHeight;
                    shooter.transform.position = shooterBase + new Vector3(0, hop, 0);
                }
                else
                {
                    // Gradual head-down lean to convey defeat.
                    float lean = Mathf.SmoothStep(0f, DefeatLeanDegrees, t);
                    shooter.transform.rotation = shooterBaseRot * Quaternion.Euler(lean, 0, 0);
                }
            }
            yield return null;
        }

        // Restore baseline so the next round starts clean.
        if (shooter != null)
        {
            shooter.transform.position = shooterBase;
            shooter.transform.rotation = shooterBaseRot;
        }
    }

    void OnGUI()
    {
        if (!isPlaying) return;

        EnsureGuiStyles();

        GUI.Label(new Rect(0, 20, Screen.width, 50), currentScore, scoreStyle);

        if (showResult)
        {
            GUI.Label(new Rect(0, 0, Screen.width, Screen.height), resultMessage, resultStyle);
        }
        else if (!string.IsNullOrEmpty(currentFeedback))
        {
            // Color varies per round; mutate the cached style rather than allocate.
            feedbackStyle.normal.textColor = feedbackColor;
            GUI.Label(new Rect(0, Screen.height / 2 - 100, Screen.width, 100), currentFeedback, feedbackStyle);
        }
    }

    /// <summary>
    /// Build OnGUI styles once on first paint. `GUI.skin` is only reliably
    /// available inside an OnGUI callback, so we lazy-init here rather than in
    /// Start. Subsequent frames reuse the same instances — no GC pressure.
    /// </summary>
    private void EnsureGuiStyles()
    {
        if (scoreStyle != null) return;

        scoreStyle = new GUIStyle(GUI.skin.label)
        {
            fontSize = ScoreFontSize,
            alignment = TextAnchor.UpperCenter,
        };
        scoreStyle.normal.textColor = Color.white;

        resultStyle = new GUIStyle(GUI.skin.label)
        {
            fontSize = ResultFontSize,
            fontStyle = FontStyle.Bold,
            alignment = TextAnchor.MiddleCenter,
        };
        resultStyle.normal.textColor = Color.white;

        feedbackStyle = new GUIStyle(GUI.skin.label)
        {
            fontSize = FeedbackFontSize,
            fontStyle = FontStyle.Bold,
            alignment = TextAnchor.MiddleCenter,
        };
    }
}
