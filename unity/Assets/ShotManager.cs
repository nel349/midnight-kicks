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
    private const string KeeperObjectName = "Keeper";

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

    // ── Camera framing — two anchors per kick (FC25-style off-axis cam) ──
    // Behind-the-shooter cams hide the ball because the shooter occludes it.
    // Solution: position the wide shot 30° off-axis (rotated around the
    // shooter) so we see past the shooter's shoulder to the ball at his feet.
    //
    // EstablishingCam: 30° off-axis, high cherry-picker. Wide cinematic
    // outside angle showing the whole penalty area + arc + ball + goal.
    // ActionCam: ~15° off-axis (small offset so the ball stays visible),
    // eye-level, telephoto. Tight focus on the ball-and-goal axis.
    //
    // The dolly runs across the FULL pre-kick window (PreRunHold + Run +
    // KickWindup ≈ 2.85s) for a slow, cinematic push-in. Peak zoom hits
    // exactly when the ball is struck. After the reaction, PlayRound snaps
    // back to EstablishingCam for the next round.
    //
    // CameraSetup.cs places the camera at EstablishingCam at scene load so
    // the menu / idle pose matches the in-play wide shot. Keep both in sync.
    private static readonly Vector3 IntroStartCam    = new Vector3( 12f, 11f, -22f);
    private static readonly Vector3 EstablishingCam  = new Vector3(  7f,  7f, -15f);
    private static readonly Vector3 EstablishingLook = new Vector3(  0f, 0.5f,  4f);
    private const float              EstablishingFov = 60f;
    private static readonly Vector3 ActionCam        = new Vector3(2.5f, 2.5f, -8f);
    private static readonly Vector3 ActionLook       = new Vector3(  0f, 0.8f, 9.5f);
    private const float              ActionFov       = 35f;

    // ── UI style font sizes ──
    private const int ScoreFontSize = 32;
    private const int ResultFontSize = 72;
    private const int FeedbackFontSize = 64;

    // ── Runtime state ──
    private GameObject shooter;
    private Animator shooterAnim;
    private Quaternion shooterInitialRotation;
    private Camera mainCamera;
    private Coroutine cameraDolly;
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

        // Always prefer the GameObject named exactly KeeperObjectName so we
        // pick the new keeper even if (a) a stale Inspector reference still
        // points at a renamed backup like "Keeper_old", or (b) the backup
        // is still active in the scene. Falls back to FindAnyObjectByType
        // only when no GameObject named "Keeper" exists.
        var keeperGO = GameObject.Find(KeeperObjectName);
        if (keeperGO != null)
        {
            var foundKeeper = keeperGO.GetComponent<Keeper>();
            if (foundKeeper != null) keeper = foundKeeper;
        }
        else if (keeper == null)
        {
            keeper = FindAnyObjectByType<Keeper>();
        }

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

        if (mainCamera == null)
        {
            currentFeedback = "";
            yield break;
        }
        Transform cam = mainCamera.transform;
        mainCamera.fieldOfView = EstablishingFov;

        while (elapsed < IntroDuration)
        {
            elapsed += Time.deltaTime;
            float t = Mathf.SmoothStep(0f, 1f, elapsed / IntroDuration);
            cam.position = Vector3.Lerp(IntroStartCam, EstablishingCam, t);
            cam.LookAt(EstablishingLook);
            yield return null;
        }

        ApplyCamera(EstablishingCam, EstablishingLook, EstablishingFov);
        currentFeedback = "";
    }

    /// <summary>
    /// Snap the camera to a position + look-at + FOV in one call. Keeps the
    /// per-frame interpolation in <see cref="DollyToAction"/> readable.
    /// </summary>
    private void ApplyCamera(Vector3 pos, Vector3 lookAt, float fov)
    {
        if (mainCamera == null) return;
        mainCamera.transform.position = pos;
        mainCamera.transform.LookAt(lookAt);
        mainCamera.fieldOfView = fov;
    }

    /// <summary>
    /// Slow cinematic push-in from EstablishingCam to ActionCam. Runs in
    /// parallel with PlayRound so the dolly arrives at peak zoom exactly at
    /// the moment of impact. Lerps position, look-at, and FOV simultaneously
    /// with SmoothStep easing so it's gentle at start and end.
    /// </summary>
    private IEnumerator DollyToAction(float duration)
    {
        if (mainCamera == null) yield break;
        Transform cam = mainCamera.transform;

        float elapsed = 0f;
        while (elapsed < duration)
        {
            elapsed += Time.deltaTime;
            float t = Mathf.SmoothStep(0f, 1f, elapsed / duration);
            cam.position = Vector3.Lerp(EstablishingCam, ActionCam, t);
            cam.LookAt(Vector3.Lerp(EstablishingLook, ActionLook, t));
            mainCamera.fieldOfView = Mathf.Lerp(EstablishingFov, ActionFov, t);
            yield return null;
        }
        ApplyCamera(ActionCam, ActionLook, ActionFov);
        cameraDolly = null;
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

        // Snap to the wide establishing shot, then start the slow push-in.
        // Dolly runs across the entire pre-kick window (PreRunHold + Run +
        // KickWindup) so peak zoom lands exactly when the ball is struck.
        if (cameraDolly != null) StopCoroutine(cameraDolly);
        ApplyCamera(EstablishingCam, EstablishingLook, EstablishingFov);
        cameraDolly = StartCoroutine(DollyToAction(PreRunHold + RunDuration + KickWindupDuration));

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

    // OnGUI + EnsureGuiStyles removed: the replay's score / result / per-round
    // feedback text now lives in the Compose MatchReplayOverlay (the score HUD +
    // the result) over the live 3D, and the goal/save OUTCOME is conveyed by the
    // 3D animation itself (ball in net vs keeper save). ShotManager is now pure
    // 3D choreography. The currentScore / resultMessage / currentFeedback fields
    // it still sets are dead (no renderer) and can be pruned in a later pass.
}
