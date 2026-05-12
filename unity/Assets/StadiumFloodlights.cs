using UnityEngine;

/// <summary>
/// Adds four corner floodlights aimed at the field center and tones the
/// existing Directional Light to a cool moonlit blue. Sells the "midnight"
/// aesthetic without requiring any asset imports.
///
/// Auto-attaches at scene load. Idempotent — re-running won't duplicate
/// fixtures.
/// </summary>
public class StadiumFloodlights : MonoBehaviour
{
    // Field center the floodlights aim at (matches camera LookAt target).
    private static readonly Vector3 FieldCenter = new Vector3(0f, 0f, 4f);

    // Four corner tower positions, set just outside the 50×40m playing surface
    // (GrassPitch enlarges Field to scale (5,1,4); pitch bounds X=±25, Z=-20..+20).
    // Y high enough to read as stadium-tower lights.
    private static readonly Vector3[] TowerPositions = new Vector3[]
    {
        new Vector3(-30f, 22f, -25f),
        new Vector3( 30f, 22f, -25f),
        new Vector3(-30f, 22f,  25f),
        new Vector3( 30f, 22f,  25f),
    };

    // Warm-white floodlight color, slightly desaturated.
    private static readonly Color FloodlightColor = new Color(1.0f, 0.97f, 0.88f, 1f);
    private const float FloodlightIntensity = 12f;    // Stadium lights are bright — make them feel it
    private const float FloodlightRange = 90f;
    private const float FloodlightSpotAngle = 80f;    // narrower → visible pools, not flat wash

    // Moonlit override for the scene's existing Directional Light. Keep a
    // gentle fill — at near-zero you lose all the model surface shading.
    // Below the floodlight intensity so the pools dominate visually.
    private static readonly Color MoonlightColor = new Color(0.55f, 0.65f, 0.85f, 1f);
    private const float MoonlightIntensity = 0.5f;

    // Linear fog adds depth — the field bleeds into the night rather than
    // ending at a hard edge. Far distance roughly matches the crowd-ring radius.
    private static readonly Color FogColor = new Color(0.08f, 0.10f, 0.16f, 1f);
    private const float FogStart = 20f;
    private const float FogEnd = 75f;

    // Ambient: low enough to read as night, high enough that nothing is true
    // black. Skybox-gradient via RenderSettings.ambientSkyColor below.
    private static readonly Color AmbientSkyColor    = new Color(0.10f, 0.13f, 0.20f, 1f);
    private static readonly Color AmbientEquatorColor = new Color(0.08f, 0.10f, 0.14f, 1f);
    private static readonly Color AmbientGroundColor = new Color(0.04f, 0.06f, 0.05f, 1f);
    // Camera background — replace the default black with a dark blue so the
    // void around the crowd ring reads as deep night sky.
    private static readonly Color NightSkyColor = new Color(0.03f, 0.05f, 0.10f, 1f);

    [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
    private static void AutoCreate()
    {
        if (FindFirstObjectByType<StadiumFloodlights>() != null) return;
        var go = new GameObject("StadiumFloodlights");
        go.AddComponent<StadiumFloodlights>();
        Debug.Log("[StadiumFloodlights] Auto-created");
    }

    void Start()
    {
        SpawnFloodlights();
        TintDirectionalLightToNight();
        SetAmbientToNight();
        SetNightFog();
        TintCameraBackground();
    }

    private static void SetNightFog()
    {
        // Linear fog gives a controlled "field fades into the night" effect.
        // Color matches the night-sky tone so distant objects blend instead
        // of silhouetting awkwardly.
        RenderSettings.fog = true;
        RenderSettings.fogMode = FogMode.Linear;
        RenderSettings.fogColor = FogColor;
        RenderSettings.fogStartDistance = FogStart;
        RenderSettings.fogEndDistance = FogEnd;
    }

    private void SpawnFloodlights()
    {
        for (int i = 0; i < TowerPositions.Length; i++)
        {
            var lightGO = new GameObject($"Floodlight_{i}");
            lightGO.transform.SetParent(transform, worldPositionStays: false);
            lightGO.transform.position = TowerPositions[i];
            lightGO.transform.LookAt(FieldCenter);

            var light = lightGO.AddComponent<Light>();
            light.type = LightType.Spot;
            light.color = FloodlightColor;
            light.intensity = FloodlightIntensity;
            light.range = FloodlightRange;
            light.spotAngle = FloodlightSpotAngle;
            light.shadows = LightShadows.Soft;
            light.shadowStrength = 0.6f;
        }
    }

    private void TintDirectionalLightToNight()
    {
        // The scene's existing key Directional Light is daytime-ish. Override
        // to a cool moonlight to match the floodlight pools.
        var directional = FindDirectionalLight();
        if (directional == null)
        {
            Debug.LogWarning("[StadiumFloodlights] No Directional Light found to tint");
            return;
        }
        directional.color = MoonlightColor;
        directional.intensity = MoonlightIntensity;
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

    private static void SetAmbientToNight()
    {
        // Use a three-color gradient so the field surface and the crowd ring
        // pick up slightly different ambient tones (sky tint above, grass tint
        // below). This makes the void around the floodlight pools feel like
        // night atmosphere rather than dead black.
        RenderSettings.ambientMode = UnityEngine.Rendering.AmbientMode.Trilight;
        RenderSettings.ambientSkyColor = AmbientSkyColor;
        RenderSettings.ambientEquatorColor = AmbientEquatorColor;
        RenderSettings.ambientGroundColor = AmbientGroundColor;
        RenderSettings.ambientIntensity = 1f;
    }

    private static void TintCameraBackground()
    {
        // Switch camera to render a procedural skybox instead of a flat
        // black/blue color. The procedural shader gives a horizon→zenith
        // gradient that hides the abrupt seam between the crowd-ring top
        // and "sky" above. We override the sun to near-zero exposure so
        // the gradient reads as a night sky, not dusk.
        var cam = Camera.main;
        if (cam == null) return;

        var skyShader = Shader.Find("Skybox/Procedural");
        if (skyShader == null)
        {
            // Fallback: just tint the solid background.
            cam.clearFlags = CameraClearFlags.SolidColor;
            cam.backgroundColor = NightSkyColor;
            return;
        }

        var skyMat = new Material(skyShader) { name = "NightSkybox" };
        skyMat.SetColor("_SkyTint", new Color(0.18f, 0.22f, 0.32f, 1f));     // upper sky
        skyMat.SetColor("_GroundColor", new Color(0.05f, 0.06f, 0.10f, 1f)); // below-horizon
        skyMat.SetFloat("_AtmosphereThickness", 0.6f);                       // softer transition
        skyMat.SetFloat("_Exposure", 0.45f);                                 // night, not day
        skyMat.SetFloat("_SunSize", 0f);                                     // no sun
        skyMat.SetFloat("_SunSizeConvergence", 0f);

        RenderSettings.skybox = skyMat;
        cam.clearFlags = CameraClearFlags.Skybox;
    }
}
