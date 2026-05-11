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
    
    private GameObject shooter;
    private Animator shooterAnim;
    private Quaternion shooterInitialRotation;
    private bool isPlaying = false;
    private bool showResult = false;
    private string resultMessage = "";
    private string currentFeedback = "";
    private string currentScore = "P1: 0 - P2: 0";
    private Color feedbackColor = Color.white;

    private static readonly Vector3 GoalLookTarget = new Vector3(0, 1.4f, 11f);
    private static readonly Vector3 IntroStartCam = new Vector3(-6f, 2.5f, -1f);
    private static readonly Vector3 PlayCamPos = new Vector3(-4f, 1.7f, -3f);

    void Update()
    {
        // Debug: press T to test a single goal replay in Editor
        if (Keyboard.current != null && Keyboard.current.tKey.wasPressedThisFrame && !isPlaying)
        {
            List<RoundData> testRounds = new List<RoundData> {
                new RoundData { round = 1, shooter = "P1", shootDir = 1, keepDir = 0, result = "goal" }
            };
            StartCoroutine(PlayReplay(testRounds, null));
        }
    }

    private void CacheShooter()
    {
        if (shooter == null)
        {
            shooter = GameObject.Find("Shooter");
            if (shooter != null)
            {
                shooterAnim = shooter.GetComponent<Animator>();
                shooterInitialRotation = shooter.transform.rotation;
            }
        }

        if (ballKicker == null) ballKicker = FindAnyObjectByType<BallKicker>();
        if (keeper == null) keeper = FindAnyObjectByType<Keeper>();
    }

    public IEnumerator PlayReplay(List<RoundData> rounds, System.Action onComplete)
    {
        isPlaying = true;
        showResult = false;
        CacheShooter();
        
        yield return StartCoroutine(PlayIntro());

        int p1Score = 0;
        int p2Score = 0;
        currentScore = "P1: 0 - P2: 0";

        foreach (var round in rounds)
        {
            yield return StartCoroutine(PlayRound(round));
            
            if (round.result == "goal")
            {
                if (round.shooter == "P1") p1Score++;
                else p2Score++;
            }
            
            currentScore = $"P1: {p1Score} - P2: {p2Score}";
            yield return new WaitForSeconds(1f);
        }

        if (p1Score > p2Score) resultMessage = "PLAYER 1 WINS!";
        else if (p2Score > p1Score) resultMessage = "PLAYER 2 WINS!";
        else resultMessage = "DRAW!";

        showResult = true;
        yield return new WaitForSeconds(4f);
        showResult = false;

        isPlaying = false;
        onComplete?.Invoke();
    }

    private IEnumerator PlayIntro()
    {
        currentFeedback = "GET READY...";
        float duration = 2.2f;
        float elapsed = 0f;

        while (elapsed < duration)
        {
            elapsed += Time.deltaTime;
            float t = Mathf.SmoothStep(0f, 1f, elapsed / duration);
            Camera.main.transform.position = Vector3.Lerp(IntroStartCam, PlayCamPos, t);
            Camera.main.transform.LookAt(GoalLookTarget);
            yield return null;
        }

        Camera.main.transform.position = PlayCamPos;
        Camera.main.transform.LookAt(GoalLookTarget);
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
            if (shooterAnim != null) shooterAnim.Play("Idle");
        }

        currentFeedback = "";
        yield return new WaitForSeconds(1.2f);

        if (shooterAnim != null) shooterAnim.Play("Run");

        float runTime = 0.9f;
        float elapsed = 0f;
        while (elapsed < runTime)
        {
            elapsed += Time.deltaTime;
            float t = elapsed / runTime;
            if (shooter != null)
                shooter.transform.position = Vector3.Lerp(shooterStartPos, shooterKickPos, t);
            yield return null;
        }
        if (shooter != null)
            shooter.transform.position = shooterKickPos;

        if (shooterAnim != null) shooterAnim.Play("Kick");
        yield return new WaitForSeconds(0.75f);

        if (ballKicker != null) ballKicker.KickTo(round.shootDir);
        yield return new WaitForSeconds(0.05f);
        if (keeper != null) keeper.Dive(round.keepDir, 0.8f);

        yield return new WaitForSeconds(1.2f);

        currentFeedback = round.result.ToUpper() + "!";
        feedbackColor = round.result == "goal" ? Color.green : Color.yellow;

        yield return new WaitForSeconds(1.8f);
    }

    void OnGUI()
    {
        if (!isPlaying) return;

        var scoreStyle = new GUIStyle(GUI.skin.label);
        scoreStyle.fontSize = 32;
        scoreStyle.alignment = TextAnchor.UpperCenter;
        scoreStyle.normal.textColor = Color.white;
        GUI.Label(new Rect(0, 20, Screen.width, 50), currentScore, scoreStyle);

        if (showResult)
        {
            var resultStyle = new GUIStyle(GUI.skin.label);
            resultStyle.fontSize = 72;
            resultStyle.fontStyle = FontStyle.Bold;
            resultStyle.alignment = TextAnchor.MiddleCenter;
            resultStyle.normal.textColor = Color.white;
            GUI.Label(new Rect(0, 0, Screen.width, Screen.height), resultMessage, resultStyle);
        }
        else if (!string.IsNullOrEmpty(currentFeedback))
        {
            var feedbackStyle = new GUIStyle(GUI.skin.label);
            feedbackStyle.fontSize = 64;
            feedbackStyle.fontStyle = FontStyle.Bold;
            feedbackStyle.alignment = TextAnchor.MiddleCenter;
            feedbackStyle.normal.textColor = feedbackColor;
            GUI.Label(new Rect(0, Screen.height / 2 - 100, Screen.width, 100), currentFeedback, feedbackStyle);
        }
    }
}
