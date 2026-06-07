using UnityEngine;
using System.Collections;

/// <summary>
/// Scripted (kinematic, non-physics) penalty ball. The outcome is decided by the
/// contract, not by physics — and Unity physics isn't deterministic across
/// devices — so the ball follows an authored arc that always reads correctly: a
/// goal nestles into the net and comes to rest on the ground; a save is met at
/// the keeper and parried away to the ground. The Rigidbody is forced kinematic
/// so it can still carry a collider without simulating.
/// </summary>
public class BallKicker : MonoBehaviour
{
    // Rests where the shooter's kick animation's foot lands (ShotManager ends the
    // run at z=-0.3; foot reaches ~0.5m forward). The penalty spot is painted
    // here too — see PitchMarkings.SpotZ.
    public Vector3 restPosition = new Vector3(0f, 0.11f, 0.2f);

    [Header("Targeting")]
    [SerializeField] private float directionOffset = 2.5f; // L/C/R lateral spread; matches Keeper.Dive
    [SerializeField] private float goalLineZ = 9.5f;       // keeper / goal line
    [SerializeField] private float netZ = 10.8f;           // inside the net, behind the line
    [SerializeField] private float goalHeight = 1.1f;      // height the ball enters the net

    [Header("Save")]
    [SerializeField] private float saveContactHeight = 1.2f; // height the ball meets the keeper's hands

    [Header("Flight")]
    [SerializeField] private float flightDuration = 0.55f;
    [SerializeField] private float arcHeight = 1.4f;
    [SerializeField] private float settleDuration = 0.4f;
    [SerializeField] private float deflectDuration = 0.5f;
    [SerializeField] private float spinDegPerSec = 720f;
    [SerializeField] private float maxContactWait = 0.25f; // fallback if no dive Animation Event

    private Rigidbody rb;
    private Coroutine flight;
    private bool deflectRequested;
    private float GroundY => restPosition.y;

    void Awake()
    {
        rb = GetComponent<Rigidbody>();
        if (rb != null)
        {
            rb.isKinematic = true;
            rb.useGravity = false;
        }
        transform.position = restPosition;
    }

    public void ResetBall()
    {
        if (flight != null) { StopCoroutine(flight); flight = null; }
        transform.position = restPosition;
    }

    /// <param name="dir">0 = left, 1 = center, 2 = right.</param>
    /// <param name="isGoal">true → into the net; false → keeper parries it.</param>
    public void KickTo(int dir, bool isGoal)
    {
        float x = (dir - 1) * directionOffset;
        if (flight != null) StopCoroutine(flight);
        if (isGoal)
        {
            flight = StartCoroutine(FlyGoal(x));
        }
        else
        {
            deflectRequested = false;
            flight = StartCoroutine(FlySaved(x, dir));
        }
    }

    /// <summary>Called by the keeper's dive Animation Event (Keeper.OnSaveContact)
    /// so the parry leaves the instant the keeper makes contact.</summary>
    public void RequestSaveDeflect() => deflectRequested = true;

    private IEnumerator FlyGoal(float x)
    {
        Vector3 start = transform.position;
        Vector3 inNet = new Vector3(x, goalHeight, netZ);
        yield return Arc(start, ControlPoint(start, inNet, arcHeight), inNet, flightDuration);
        // Drop down the net to rest on the ground inside the goal.
        Vector3 grounded = new Vector3(x, GroundY, netZ - 0.15f);
        yield return Arc(inNet, ControlPoint(inNet, grounded, 0.05f), grounded, settleDuration);
        flight = null;
    }

    private IEnumerator FlySaved(float x, int dir)
    {
        Vector3 start = transform.position;
        Vector3 savePoint = new Vector3(x, saveContactHeight, goalLineZ); // meets the keeper's hands
        yield return Arc(start, ControlPoint(start, savePoint, arcHeight), savePoint, flightDuration);

        // Hold at the keeper's hands until the dive's contact Animation Event
        // fires (RequestSaveDeflect), or a short fallback elapses if there's no
        // event — so the parry leaves exactly on contact.
        float wait = 0f;
        while (!deflectRequested && wait < maxContactWait) { wait += Time.deltaTime; yield return null; }

        // Parry up-and-away, then down to the ground: dropped out front on a
        // central save, pushed wide on a low one.
        Vector3 grounded = dir == 1
            ? new Vector3(x, GroundY, goalLineZ - 4.5f)
            : new Vector3(x + Mathf.Sign(x) * 4f, GroundY, goalLineZ - 3.5f);
        yield return Arc(savePoint, ControlPoint(savePoint, grounded, 1.3f), grounded, deflectDuration);
        flight = null;
    }

    private static Vector3 ControlPoint(Vector3 from, Vector3 to, float lift) =>
        Vector3.Lerp(from, to, 0.5f) + Vector3.up * lift;

    private IEnumerator Arc(Vector3 p0, Vector3 p1, Vector3 p2, float duration)
    {
        Vector3 spinAxis = Vector3.Cross(p2 - p0, Vector3.up);
        if (spinAxis.sqrMagnitude < 0.0001f) spinAxis = Vector3.right;
        spinAxis.Normalize();

        float t = 0f;
        while (t < duration)
        {
            t += Time.deltaTime;
            float u = Mathf.Clamp01(t / duration);
            float inv = 1f - u;
            transform.position = inv * inv * p0 + 2f * inv * u * p1 + u * u * p2;
            transform.Rotate(spinAxis, spinDegPerSec * Time.deltaTime, Space.World);
            yield return null;
        }
        transform.position = p2;
    }
}
