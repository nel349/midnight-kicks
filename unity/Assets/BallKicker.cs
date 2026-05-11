using UnityEngine;

public class BallKicker : MonoBehaviour
{
    public float kickForce = 18f;

    // Ball rests just in front of where the shooter's run-up ends so the
    // kick animation's foot extension naturally meets the ball. ShotManager
    // ends the run at z=-0.3; foot extends ~0.5m forward, so the ball
    // sits at z=0.2 to be where the foot lands.
    public Vector3 restPosition = new Vector3(0f, 0.11f, 0.2f);

    // Kick targeting: lateral offset between L/C/R, vertical aim, and how
    // far past the goal line the ball aims so it crosses the goal mouth.
    private const float DirectionOffsetMeters = 2.5f;
    private const float TargetY = 1.8f;
    private const float TargetZ = 10.5f;
    private const float BallMass = 0.45f;
    private const float ResetDelaySeconds = 3f;

    private Rigidbody rb;

    private Rigidbody RB => rb ??= GetComponent<Rigidbody>();

    void Awake()
    {
        if (RB != null) RB.mass = BallMass;
    }

    public void KickTo(int directionIndex)
    {
        // 0: Left, 1: Center, 2: Right
        float xOffset = (directionIndex - 1) * DirectionOffsetMeters;
        Kick(new Vector3(xOffset, TargetY, TargetZ));
    }

    public void ResetBall()
    {
        CancelInvoke(nameof(Reset));
        Reset();
    }

    void Kick(Vector3 target)
    {
        if (RB == null) return;
        Vector3 direction = (target - transform.position).normalized;
        RB.AddForce(direction * kickForce, ForceMode.Impulse);
        Invoke(nameof(Reset), ResetDelaySeconds);
    }

    void Reset()
    {
        if (RB != null)
        {
            RB.linearVelocity = Vector3.zero;
            RB.angularVelocity = Vector3.zero;
        }
        transform.position = restPosition;
    }
}