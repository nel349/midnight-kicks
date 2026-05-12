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
    // Slight warm bias to read as midday rather than overcast.
    private static readonly Color SunColor = new Color(1.00f, 0.96f, 0.90f, 1f);
    private const float SunIntensity = 1.4f;
    private static readonly Vector3 SunEulerAngles = new Vector3(50f, -30f, 0f); // afternoon angle

    // ── Ambient ──
    // Trilight gradient so the field surface and the crowd ring pick up
    // slightly different tones. Bright enough that nothing is in shadow.
    private static readonly Color AmbientSkyColor    = new Color(0.55f, 0.70f, 0.95f, 1f);
    private static readonly Color AmbientEquatorColor = new Color(0.55f, 0.60f, 0.65f, 1f);
    private static readonly Color AmbientGroundColor = new Color(0.30f, 0.34f, 0.28f, 1f);

    // ── Sky (procedural skybox) ──
    private static readonly Color SkyTint = new Color(0.55f, 0.75f, 1.00f, 1f);
    private static readonly Color SkyGroundColor = new Color(0.45f, 0.55f, 0.55f, 1f);
    private const float SkyAtmosphereThickness = 1.0f;
    private const float SkyExposure = 1.3f;

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
        directional.shadowStrength = 0.7f;
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
        RenderSettings.ambientMode = UnityEngine.Rendering.AmbientMode.Trilight;
        RenderSettings.ambientSkyColor = AmbientSkyColor;
        RenderSettings.ambientEquatorColor = AmbientEquatorColor;
        RenderSettings.ambientGroundColor = AmbientGroundColor;
        RenderSettings.ambientIntensity = 1f;
    }

    private static void DisableFog()
    {
        // Night fog was darkening distant geometry. For a clear daytime
        // shoot we don't want any fog at all.
        RenderSettings.fog = false;
    }

    private void SetSkyToDay()
    {
        var cam = Camera.main;
        if (cam == null) return;

        var skyShader = Shader.Find("Skybox/Procedural");
        if (skyShader == null)
        {
            cam.clearFlags = CameraClearFlags.SolidColor;
            cam.backgroundColor = SkyTint;
            return;
        }

        var skyMat = new Material(skyShader) { name = "DaySkybox" };
        skyMat.SetColor("_SkyTint", SkyTint);
        skyMat.SetColor("_GroundColor", SkyGroundColor);
        skyMat.SetFloat("_AtmosphereThickness", SkyAtmosphereThickness);
        skyMat.SetFloat("_Exposure", SkyExposure);
        skyMat.SetFloat("_SunSize", 0.04f);
        skyMat.SetFloat("_SunSizeConvergence", 5f);

        RenderSettings.skybox = skyMat;
        cam.clearFlags = CameraClearFlags.Skybox;
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
