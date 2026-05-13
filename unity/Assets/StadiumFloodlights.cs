using UnityEngine;

/// <summary>
/// Daylight lighting setup for the pitch — bright sun, blue sky, high
/// ambient. Replaces the previous night-stadium-with-floodlights approach
/// that was reading as obscure and dim regardless of intensity tuning.
///
/// Reasons we switched:
///   - The four floodlights at corners couldn't compete with URP's
///     post-processing tonemapping (Global Volume in the scene) which
///     was crushing brightness no matter how high the intensity went.
///   - The crowd texture in Resources/StadiumCrowd is daylit anyway,
///     so a daylight scene matches without any color-grade gymnastics.
///   - Easier reads for gameplay — characters and ball are immediately
///     visible without dramatic shadow falloff hiding them.
///
/// Class name kept as StadiumFloodlights to avoid breaking the
/// RuntimeInitializeOnLoadMethod auto-create chain in the build. The
/// behavior is now a daylight setup.
/// </summary>
public class StadiumFloodlights : MonoBehaviour
{
    // ── Sun (the scene's existing Directional Light, re-tinted) ──
    private static readonly Color SunColor = new Color(1.00f, 0.97f, 0.92f, 1f);
    private const float SunIntensity = 1.8f;          // bumped — was 1.4
    private const float ShadowDistanceMeters = 25f;   // shadows only cast within 25m of camera
                                                      // (default ~150 was projecting a long shadow
                                                      // of the shooter onto the crowd ring)
    private static readonly Vector3 SunEulerAngles = new Vector3(70f, 25f, 0f); // higher in sky

    // ── Ambient (Color mode for predictability — Trilight was being
    //    interpreted unevenly by URP across surfaces) ──
    private static readonly Color AmbientColor = new Color(0.65f, 0.70f, 0.75f, 1f);
    private const float AmbientIntensity = 1.4f;

    // ── Sky (procedural skybox, NO visible sun disc) ──
    // _SunSize = 0 prevents the bright sun disc from blowing out the view
    // through the goal opening. Sky still renders as a soft blue gradient.
    private static readonly Color SkyTint = new Color(0.60f, 0.78f, 1.00f, 1f);
    private static readonly Color SkyGroundColor = new Color(0.48f, 0.55f, 0.58f, 1f);
    private const float SkyAtmosphereThickness = 0.8f;
    private const float SkyExposure = 0.75f;          // reduced — was 1.3, blew out the center

    [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
    private static void AutoCreate()
    {
        if (FindFirstObjectByType<StadiumFloodlights>() != null) return;
        var go = new GameObject("DaylightLighting");
        go.AddComponent<StadiumFloodlights>();
        Debug.Log("[DaylightLighting] Auto-created");
    }

    void Start()
    {
        TintDirectionalLightToDay();
        SetAmbientToDay();
        DisableFog();
        SetSkyToDay();
        DisableScenePostProcessingVolumes();
        DisableCameraPostProcessing();
    }

    /// <summary>
    /// Belt-and-braces post-processing kill. The Volume disable handles
    /// scene-level overrides; this turns off the URP per-camera post-FX
    /// stack, which can re-enable bloom/tonemapping independently of any
    /// Volume. White blob in the center of frame was likely bloom.
    /// </summary>
    private static void DisableCameraPostProcessing()
    {
        var cam = Camera.main;
        if (cam == null) return;

        // No-HDR removes the headroom that bloom needs to blow out.
        cam.allowHDR = false;

        // Toggle the URP additional camera data via reflection so we don't
        // need a compile-time dependency on the URP package namespace.
        var dataComp = cam.GetComponent("UniversalAdditionalCameraData");
        if (dataComp == null) return;
        var prop = dataComp.GetType().GetProperty("renderPostProcessing");
        if (prop != null && prop.CanWrite)
        {
            prop.SetValue(dataComp, false);
            Debug.Log("[DaylightLighting] Disabled URP camera post-processing");
        }
    }

    private void TintDirectionalLightToDay()
    {
        var directional = FindDirectionalLight();
        if (directional == null)
        {
            Debug.LogWarning("[DaylightLighting] No Directional Light found");
            return;
        }
        directional.color = SunColor;
        directional.intensity = SunIntensity;
        directional.transform.rotation = Quaternion.Euler(SunEulerAngles);
        directional.shadows = LightShadows.Soft;
        directional.shadowStrength = 0.6f;
        directional.flare = null;            // no lens flare halo blob

        // Limit shadow draw distance so the directional doesn't project a
        // long character shadow onto the crowd backdrop in the distance.
        QualitySettings.shadowDistance = ShadowDistanceMeters;
    }

    private static Light FindDirectionalLight()
    {
        var lights = FindObjectsByType<Light>(FindObjectsSortMode.None);
        foreach (var l in lights)
        {
            if (l.type == LightType.Directional) return l;
        }
        return null;
    }

    private static void SetAmbientToDay()
    {
        // Plain Color mode — predictable across all surfaces. Trilight was
        // producing inconsistent shading on the Mixamo character materials.
        RenderSettings.ambientMode = UnityEngine.Rendering.AmbientMode.Flat;
        RenderSettings.ambientLight = AmbientColor;
        RenderSettings.ambientIntensity = AmbientIntensity;
    }

    private static void DisableFog()
    {
        // Night fog was darkening distant geometry. For a clear daytime
        // shoot we don't want any fog at all.
        RenderSettings.fog = false;
    }

    private void SetSkyToDay()
    {
        // Solid-color sky. Skybox/Procedural kept drawing a bright sun disc
        // bleeding through the goal opening regardless of _SunSize tweaks
        // (probably a shader-stripping or render-feature corner case). A
        // flat clear color can't draw a sun by definition.
        var cam = Camera.main;
        if (cam == null) return;
        cam.clearFlags = CameraClearFlags.SolidColor;
        cam.backgroundColor = SkyTint;
        // Remove any existing skybox material so it doesn't influence ambient.
        RenderSettings.skybox = null;
    }

    /// <summary>
    /// Disable any post-processing Volumes in the scene. The project's
    /// Global Volume was likely the cause of repeated reports of "still
    /// too dim" — it applies tonemapping / color grading that we can't
    /// tune from outside the editor. Turning the Volume off lets the
    /// raw lit colors through.
    /// </summary>
    private static void DisableScenePostProcessingVolumes()
    {
        // UnityEngine.Rendering.Volume is the URP/HDRP post-FX entry point.
        // Use string lookup to avoid a hard dependency on the URP package
        // namespace at compile time if anyone reuses this file.
        var volumeType = System.Type.GetType(
            "UnityEngine.Rendering.Volume, Unity.RenderPipelines.Core.Runtime");
        if (volumeType == null) return;

        var volumes = FindObjectsByType(volumeType, FindObjectsSortMode.None);
        foreach (var v in volumes)
        {
            if (v is Behaviour b)
            {
                b.enabled = false;
            }
        }
        if (volumes.Length > 0)
            Debug.Log($"[DaylightLighting] Disabled {volumes.Length} post-processing Volume(s)");
    }
}
