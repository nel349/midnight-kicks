using UnityEngine;

public class Keeper : MonoBehaviour
{
    public float diveSpeed = 5f;
    public AnimationCurve diveCurve = new AnimationCurve(
        new Keyframe(0, 0, 0, 2),
        new Keyframe(1, 1, 0, 0)
    );

    private Vector3 initialPosition;
    private Vector3 targetPosition;
    private Quaternion initialRotation;
    private Quaternion targetRotation;
    private bool isDiving = false;
    private float diveTimer = 0f;
    private float currentDiveDuration = 1f;
    private Animator animator;

    void Start()
    {
        initialPosition = transform.position;
        initialRotation = transform.rotation;
        targetPosition = initialPosition;
        targetRotation = initialRotation;
        animator = GetComponent<Animator>();
    }

    void Update()
    {
        if (isDiving)
        {
            diveTimer += Time.deltaTime;
            float t = Mathf.Clamp01(diveTimer / currentDiveDuration);
            float curveT = diveCurve.Evaluate(t);
            transform.position = Vector3.Lerp(initialPosition, targetPosition, curveT);
            transform.rotation = Quaternion.Slerp(initialRotation, targetRotation, curveT);

            if (t >= 1f) isDiving = false;
        }
    }

    public void Dive(int direction, float duration = 1.0f)
    {
        float xOffset = (direction - 1) * 2.5f;
        float yOffset = (direction == 1) ? 0.6f : 0.2f;
        targetPosition = new Vector3(initialPosition.x + xOffset, initialPosition.y + yOffset, initialPosition.z);

        float tiltZ = 0f;
        if (direction == 0) tiltZ = 65f;
        else if (direction == 2) tiltZ = -65f;
        targetRotation = Quaternion.Euler(0, 0, tiltZ) * initialRotation;

        currentDiveDuration = duration;
        diveTimer = 0f;
        isDiving = true;

        if (animator != null)
        {
            if (direction == 0) animator.Play("DiveLeft");
            else if (direction == 1) animator.Play("JumpCenter");
            else if (direction == 2) animator.Play("DiveRight");
        }
    }

    public void Reset()
    {
        transform.position = initialPosition;
        transform.rotation = initialRotation;
        targetPosition = initialPosition;
        targetRotation = initialRotation;
        isDiving = false;
        if (animator != null) animator.Play("Idle");
    }
}
