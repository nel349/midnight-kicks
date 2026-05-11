  using UnityEngine;                                                                                                                      
  using UnityEngine.InputSystem;                                                                                                        

  public class BallKicker : MonoBehaviour                                                                                                 
  {              
      public float kickForce = 18f;                                                                                                       
                                                                                                                                        
      private Rigidbody rb;
      private bool hasKicked = false;

      void Awake()
      {
          rb = GetComponent<Rigidbody>();
          if (rb != null) rb.mass = 0.45f;
      }

      void Start()
      {
          if (rb == null) rb = GetComponent<Rigidbody>();
      }

      public void KickTo(int directionIndex)
      {
          if (rb == null) rb = GetComponent<Rigidbody>();
          // 0: Left, 1: Center, 2: Right
          float xOffset = (directionIndex - 1) * 2.0f;
          Vector3 target = new Vector3(xOffset, 1.8f, 10f);
          Kick(target);
      }

      public void ResetBall()
      {
          CancelInvoke("Reset");
          Reset();
      }

      void Kick(Vector3 target)                                                                                                           
      {          
          if (rb == null) rb = GetComponent<Rigidbody>();
          Vector3 direction = (target - transform.position).normalized;                                                                   
          rb.AddForce(direction * kickForce, ForceMode.Impulse);                                                                        
hasKicked = true;
          Invoke("Reset", 3f);
      }                                                                                                                                   
               
      void Reset()                                                                                                                        
      {                                                                                                                                 
          if (rb == null) rb = GetComponent<Rigidbody>();
          rb.linearVelocity = Vector3.zero;
rb.angularVelocity = Vector3.zero;
          transform.position = new Vector3(0, 0.11f, 0);
          hasKicked = false;                                                                                                              
      }
  }      