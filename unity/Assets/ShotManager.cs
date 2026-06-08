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
    private const string ResultSave = "save";

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

    // Opening pan (PlayIntro): IntroStartCam → EstablishingCam wide overview.
    // Per-kick framing is FrameShot (aspect-aware) below; CameraSetup.cs places
    // the idle/menu camera at EstablishingCam to match.
    private static readonly Vector3 IntroStartCam    = new Vector3( 12f, 11f, -22f);
    private static readonly Vector3 EstablishingCam  = new Vector3(  7f,  7f, -15f);
    private static readonly Vector3 EstablishingLook = new Vector3(  0f, 0.5f,  4f);
    private const float              EstablishingFov = 60f;

    // ── Aspect-aware framing (portrait fix) ──
    // A behind+above angle whose FOV is computed each round to keep the shooter,
    // the goal mouth, and the keeper's dive range in the viewport for the CURRENT
    // screen aspect. Portrait has a narrow horizontal FOV, which is why a camera
    // tuned on a wide editor aspect drops the shooter off the side; this recomputes
    // the FOV from the targets + Screen aspect so they stay framed. Tunable below.
    [SerializeField] private Vector3 framingOffset = new Vector3(0f, 2.5f, -7f);
    [SerializeField] private Vector3 framingGoal   = new Vector3(0f, 1.5f, 9.5f);
    [SerializeField] private float   framingGoalHalfWidth = 2.6f;
    [SerializeField] private float   framingPadding = 1.15f;
    [SerializeField] private float   framingMinFov  = 32f;
    [SerializeField] private float   framingMaxFov  = 82f;

    // Run-up leg cadence multiplier: speeds up the in-place Run clip so the feet
    // plant at the run's travel speed instead of sliding (it read as a walk at
    // 1×). Tune until the feet stop skating.
    [SerializeField] private float runAnimSpeed = 1.4f;

    // ── Runtime state ──
    private GameObject shooter;
    private Animator shooterAnim;
    private Quaternion shooterInitialRotation;
    private Camera mainCamera;
    private bool isPlaying = false;
    private string resultMessage = "";

    // ── Live re-framing on rotation ──
    // The last aspect-aware shot framing, kept so Update() can re-fit the FOV the
    // instant the screen aspect changes (device rotation) instead of waiting for
    // the next kick's FrameShot(). The camera doesn't move within a round, so the
    // stored cam pos / look-at stay valid; only the FOV needs to re-fit the new
    // aspect. Set by ApplyFramed, replayed by ApplyStoredFraming.
    private Vector3 frameCamPos;
    private Vector3 frameLookAt;
    private Vector3[] framePoints;
    private bool hasFrame = false;
    private float lastAspect = -1f;
    // Which side ("P1"/"P2") is the local device — set per replay so the shooter
    // wears the local vs opponent kit each round. Defaults to P1 (PvAI / editor).
    private string localSide = "P1";

    void Start()
    {
        Debug.Log("[ShotManager] Editor test keys — T: single goal · Y: single save · " +
                  "U: full 10-kick shootout · I: all saves · O: all goals");
    }

    void Update()
    {
        // Re-fit the shot framing the moment the screen aspect changes (device
        // rotation), so an in-flight kick re-frames live instead of waiting for
        // the next kick. Runs in every state (incl. mid-replay), so it sits
        // ahead of the editor-key guard below.
        if (mainCamera == null) mainCamera = Camera.main;
        if (hasFrame && mainCamera != null && !Mathf.Approximately(mainCamera.aspect, lastAspect))
            ApplyStoredFraming();

        // Editor test scenarios (keyboard). No keyboard on a phone, so these are
        // inert in a device build — `Keyboard.current` is null there.
        if (isPlaying || Keyboard.current == null) return;

        if (Keyboard.current.tKey.wasPressedThisFrame)
            StartCoroutine(PlayReplay(ScenarioSingle(goal: true), "P1", null, null));
        else if (Keyboard.current.yKey.wasPressedThisFrame)
            StartCoroutine(PlayReplay(ScenarioSingle(goal: false), "P1", null, null));
        else if (Keyboard.current.uKey.wasPressedThisFrame)
            StartCoroutine(PlayReplay(ScenarioShootout(), "P1", null, null));
        else if (Keyboard.current.iKey.wasPressedThisFrame)
            StartCoroutine(PlayReplay(ScenarioUniform(goal: false), "P1", null, null));
        else if (Keyboard.current.oKey.wasPressedThisFrame)
            StartCoroutine(PlayReplay(ScenarioUniform(goal: true), "P1", null, null));
    }

    // ── Editor test scenarios ──
    // A SAVE is keeper-guesses-right (keepDir == shootDir, ball meets the dive);
    // a GOAL is keeper-wrong-way (keepDir != shootDir). Kept consistent so each
    // scenario reads correctly in the 3D choreography.
    private static RoundData TestRound(int round, string shooter, int shootDir, bool goal)
    {
        int keepDir = goal ? (shootDir + 1) % 3 : shootDir; // wrong way vs. right way
        return new RoundData
        {
            round = round,
            shooter = shooter,
            shootDir = shootDir,
            keepDir = keepDir,
            result = goal ? ResultGoal : ResultSave,
        };
    }

    /// <summary>One kick to the right — goal (keeper dives wrong) or save (keeper guesses right).</summary>
    private static List<RoundData> ScenarioSingle(bool goal) =>
        new List<RoundData> { TestRound(1, "P1", shootDir: 2, goal: goal) };

    /// <summary>Ten kicks to the same lane, all goals or all saves — isolates one outcome.</summary>
    private static List<RoundData> ScenarioUniform(bool goal)
    {
        var rounds = new List<RoundData>();
        for (int i = 0; i < 10; i++)
            rounds.Add(TestRound(i + 1, (i % 2 == 0) ? "P1" : "P2", shootDir: i % 3, goal: goal));
        return rounds;
    }

    /// <summary>Full 10-kick shootout: alternating P1/P2, varied lanes, a realistic mix.</summary>
    private static List<RoundData> ScenarioShootout()
    {
        // (shootDir, goal?) per kick — a believable spread of lanes and outcomes.
        var script = new (int dir, bool goal)[]
        {
            (0, true), (2, true), (1, false), (0, true), (2, false),
            (1, true), (0, false), (2, true), (1, true), (0, true),
        };
        var rounds = new List<RoundData>();
        for (int i = 0; i < script.Length; i++)
            rounds.Add(TestRound(i + 1, (i % 2 == 0) ? "P1" : "P2", script[i].dir, script[i].goal));
        return rounds;
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

        // Fire the ball's parry from the keeper's dive-contact Animation Event.
        if (keeper != null && ballKicker != null)
            keeper.saveContact = ballKicker.RequestSaveDeflect;

        Debug.Log($"[ShotManager] CacheShooter: shooter={(shooter != null)} " +
                  $"shooterAnim={(shooterAnim != null)} " +
                  $"ballKicker={(ballKicker != null)} " +
                  $"keeper={(keeper != null)} " +
                  $"mainCamera={(mainCamera != null)}");
    }

    public IEnumerator PlayReplay(List<RoundData> rounds, string localSide, System.Action<int> onRoundResolved, System.Action onComplete)
    {
        this.localSide = string.IsNullOrEmpty(localSide) ? "P1" : localSide;
        Debug.Log($"[ShotManager] PlayReplay START rounds={rounds.Count} localSide={this.localSide}");
        float startTime = Time.realtimeSinceStartup;

        isPlaying = true;
        CacheShooter();

        yield return StartCoroutine(PlayIntro());

        int p1Score = 0;
        int p2Score = 0;

        for (int i = 0; i < rounds.Count; i++)
        {
            var round = rounds[i];
            Debug.Log($"[ShotManager] Round {i + 1}/{rounds.Count} shooter={round.shooter} " +
                      $"shootDir={round.shootDir} keepDir={round.keepDir} result={round.result}");
            DressShooterForRound(round);
            yield return StartCoroutine(PlayRound(round));

            if (round.result == ResultGoal)
            {
                if (round.shooter == "P1") p1Score++;
                else p2Score++;
            }

            // Tell Kotlin this kick just resolved, so the Compose overlay can
            // flash GOAL!/SAVED! and climb its live score chip in step with the
            // 3D action (the suspense beat). The overlay derives the outcome
            // from its own authoritative round data; we only send the index.
            onRoundResolved?.Invoke(i);
            yield return new WaitForSeconds(ScoreHoldBetweenRounds);
        }

        if (p1Score > p2Score) resultMessage = "PLAYER 1 WINS!";
        else if (p2Score > p1Score) resultMessage = "PLAYER 2 WINS!";
        else resultMessage = "DRAW!";

        // Final eruption as the result screen appears — framed from THIS device's
        // side so a win roars and a loss is restrained.
        bool localWon = (this.localSide == "P1" && p1Score > p2Score)
                     || (this.localSide == "P2" && p2Score > p1Score);
        AudioManager.PlayMatchEnd(localWon);

        yield return new WaitForSeconds(FinalResultHold);

        isPlaying = false;
        float duration = Time.realtimeSinceStartup - startTime;
        Debug.Log($"[ShotManager] PlayReplay END after {duration:F1}s, final={resultMessage}");
        onComplete?.Invoke();
    }

    /// <summary>
    /// Dress the shooter in the kit of whoever takes this kick: the local kit
    /// when this round's shooter is our side, the opponent kit otherwise. The
    /// keeper keeps its own fixed goalkeeper kit (KeeperAppearance), so it's not
    /// touched here.
    /// </summary>
    private void DressShooterForRound(RoundData round)
    {
        var kit = round.shooter == localSide ? MatchKits.Local : MatchKits.Opponent;
        ShooterAppearance.SetKit(kit.jersey, kit.shorts, kit.socks);
    }

    private IEnumerator PlayIntro()
    {
        float elapsed = 0f;

        // The intro drives the camera itself; suspend stored-frame re-fitting so a
        // rotation mid-pan doesn't snap the camera to last round's shot framing.
        // FrameShot() re-arms it for each kick.
        hasFrame = false;

        if (mainCamera == null) yield break;
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
    }

    /// <summary>Snap the camera to a position + look-at + FOV in one call.</summary>
    private void ApplyCamera(Vector3 pos, Vector3 lookAt, float fov)
    {
        if (mainCamera == null) return;
        mainCamera.transform.position = pos;
        mainCamera.transform.LookAt(lookAt);
        mainCamera.fieldOfView = fov;
    }

    /// <summary>
    /// Aspect-aware shot: from a behind+above angle, compute the FOV that keeps
    /// the shooter, the goal mouth, and the keeper's dive range inside the
    /// viewport for the current screen aspect (so the shooter stays visible in
    /// portrait). Held static for the round.
    /// </summary>
    private void FrameShot()
    {
        Vector3 goalLeft  = framingGoal + Vector3.left  * framingGoalHalfWidth;
        Vector3 goalRight = framingGoal + Vector3.right * framingGoalHalfWidth;
        Vector3 center = Vector3.Lerp(shooterKickPos, framingGoal, 0.5f);
        ApplyFramed(center + framingOffset, center, shooterStartPos, shooterKickPos, goalLeft, goalRight);
    }

    /// <summary>
    /// Place the camera at <paramref name="camPos"/> looking at
    /// <paramref name="lookAt"/>, then widen the (vertical) FOV until every
    /// point in <paramref name="points"/> fits — accounting for the current
    /// aspect, where the horizontal FOV = vertical FOV scaled by width/height.
    /// </summary>
    private void ApplyFramed(Vector3 camPos, Vector3 lookAt, params Vector3[] points)
    {
        frameCamPos = camPos;
        frameLookAt = lookAt;
        framePoints = points;
        hasFrame = true;
        ApplyStoredFraming();
    }

    /// <summary>
    /// Place the camera at the stored framing and widen the (vertical) FOV until
    /// every stored point fits the CURRENT aspect (horizontal FOV = vertical FOV
    /// scaled by width/height). Called by <see cref="ApplyFramed"/> per kick and
    /// re-called from <see cref="Update"/> the moment the aspect changes, so a
    /// rotation re-fits the shot live instead of at the next kick.
    /// </summary>
    private void ApplyStoredFraming()
    {
        if (mainCamera == null || !hasFrame) return;
        Transform cam = mainCamera.transform;
        cam.position = frameCamPos;
        cam.LookAt(frameLookAt);

        float aspect = mainCamera.aspect; // width / height; < 1 in portrait
        float maxTanV = 0.02f;
        float maxTanH = 0.02f;
        foreach (var p in framePoints)
        {
            Vector3 v = cam.InverseTransformPoint(p); // camera-local, +z forward
            if (v.z <= 0.05f) continue;               // ignore points behind the camera
            maxTanV = Mathf.Max(maxTanV, Mathf.Abs(v.y) / v.z);
            maxTanH = Mathf.Max(maxTanH, Mathf.Abs(v.x) / v.z);
        }
        float vFovForHeight = 2f * Mathf.Atan(maxTanV);
        float vFovForWidth  = 2f * Mathf.Atan(maxTanH / aspect);
        float vFov = Mathf.Max(vFovForHeight, vFovForWidth) * Mathf.Rad2Deg * framingPadding;
        mainCamera.fieldOfView = Mathf.Clamp(vFov, framingMinFov, framingMaxFov);
        lastAspect = aspect;
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

        // Aspect-aware framing held for the round: keeps the shooter, ball and
        // goal in shot on the current screen aspect (the portrait fix). The
        // cinematic push-in is parked until the camera is rebuilt on Cinemachine.
        FrameShot();

        yield return new WaitForSeconds(PreRunHold);

        if (shooterAnim != null)
        {
            shooterAnim.speed = runAnimSpeed;
            shooterAnim.Play(AnimRun);
        }

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

        if (shooterAnim != null)
        {
            shooterAnim.speed = 1f;
            shooterAnim.Play(AnimKick);
        }
        yield return new WaitForSeconds(KickWindupDuration);

        if (ballKicker != null) ballKicker.KickTo(round.shootDir, round.result == ResultGoal);
        AudioManager.PlayKick();
        yield return new WaitForSeconds(KeeperDiveDelay);
        if (keeper != null) keeper.Dive(round.keepDir, KeeperDiveDuration);

        yield return new WaitForSeconds(BallFlightDuration);
        // The reaction lands as the ball arrives: goal → net ripple + crowd roar,
        // save → defiant drum + a lower roar. (Was save-only; goals were silent.)
        AudioManager.PlayRoundResult(round.result);

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

    // No OnGUI: the score / result / feedback live in the Compose
    // MatchReplayOverlay; the goal/save outcome is conveyed by the 3D animation.
    // ShotManager is pure 3D choreography.
}
