using UnityEngine;

/// <summary>
/// Builds the grass pitch surface: resizes the Field plane to 50×40m so it
/// holds true-FIFA-proportion markings, then textures it.
///
/// Texture source order:
///   1. <c>Assets/Resources/GrassField.(png|jpg)</c> — drop in a real tileable
///      grass photo for a photo-real pitch.
///   2. Procedural mowing-stripes fallback if no Resource is found, so the
///      field always renders as grass even before the texture is added.
///
/// Self-attaches at scene load. No scene edits required.
///
/// Recommended sources for the drop-in (CC0 / free):
///   - ambientcg.com (search "grass", "lawn")
///   - polyhaven.com (search "grass", "soccer")
///   - texturecan.com / freepbr.com
/// </summary>
public class GrassPitch : MonoBehaviour
{
    // Resources path — no extension, no leading "Resources/".
    private const string GrassTextureResource = "GrassField";

    private const string FieldObjectName = "Field";

    // Procedural-fallback texture resolution. 512 is plenty for a tiled plane
    // viewed from camera distance; keeps generation under 100ms on mobile.
    private const int TextureSize = 512;
    private const int StripeWidth = 64;       // pixels per mowing band
    private const float Tiling = 6f;          // tile count across the Field plane

    // Two greens that read as a freshly-mown pitch. The contrast is small —
    // big differences look like AstroTurf, not real grass.
    private static readonly Color DarkStripe = new Color(0.14f, 0.42f, 0.16f);
    private static readonly Color LightStripe = new Color(0.20f, 0.52f, 0.22f);

    private const float NoiseScale = 0.12f;   // Perlin frequency
    private const float NoiseAmplitude = 0.07f;
    private const float GrainAmplitude = 0.04f; // per-pixel high-freq grain

    [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
    private static void AutoCreate()
    {
        if (FindFirstObjectByType<GrassPitch>() != null) return;
        var go = new GameObject("GrassPitch");
        go.AddComponent<GrassPitch>();
        Debug.Log("[GrassPitch] Auto-created");
    }

    void Start()
    {
        var field = GameObject.Find(FieldObjectName);
        if (field == null)
        {
            Debug.LogWarning($"[GrassPitch] No GameObject named '{FieldObjectName}' found — skipping");
            return;
        }

        // Resize the Field plane to fit true-FIFA-proportion markings.
        // Penalty area is 40.32m wide × 16.5m deep, arc extends a further
        // ~9.15m toward the shooter — we need ~45m × 30m of ground at minimum.
        // Unity's plane primitive is 10×10 at scale 1, so (5, 1, 4) = 50×40m.
        field.transform.localScale = new Vector3(5f, 1f, 4f);

        var renderer = field.GetComponent<MeshRenderer>();
        if (renderer == null)
        {
            Debug.LogWarning("[GrassPitch] Field has no MeshRenderer");
            return;
        }

        var shader = Shader.Find("Universal Render Pipeline/Lit")
                  ?? Shader.Find("Standard");
        if (shader == null)
        {
            Debug.LogWarning("[GrassPitch] No Lit shader available");
            return;
        }

        // Prefer the drop-in texture; fall back to procedural if absent.
        Texture2D texture = Resources.Load<Texture2D>(GrassTextureResource);
        string source;
        if (texture != null)
        {
            texture.wrapMode = TextureWrapMode.Repeat;
            texture.filterMode = FilterMode.Trilinear;
            source = $"Resources/{GrassTextureResource}";
        }
        else
        {
            texture = GenerateGrassTexture();
            source = "procedural fallback (drop a tileable grass texture at " +
                     $"unity/Assets/Resources/{GrassTextureResource}.png for photo-real)";
        }

        var material = new Material(shader) { name = "GrassPitch_Material" };
        // BaseMap (URP/Lit) and _MainTex (built-in Standard) — set both so the
        // material works in either pipeline.
        if (material.HasProperty("_BaseMap")) material.SetTexture("_BaseMap", texture);
        if (material.HasProperty("_MainTex")) material.SetTexture("_MainTex", texture);
        material.mainTextureScale = new Vector2(Tiling, Tiling);
        // Smoothness low — grass shouldn't be shiny.
        if (material.HasProperty("_Smoothness")) material.SetFloat("_Smoothness", 0.1f);
        if (material.HasProperty("_Glossiness")) material.SetFloat("_Glossiness", 0.1f);

        renderer.material = material;
        Debug.Log($"[GrassPitch] Applied grass to Field — source: {source}");
    }

    private Texture2D GenerateGrassTexture()
    {
        var tex = new Texture2D(TextureSize, TextureSize, TextureFormat.RGB24, mipChain: true)
        {
            name = "GrassPitch_Tex",
            wrapMode = TextureWrapMode.Repeat,
            filterMode = FilterMode.Trilinear,
            anisoLevel = 4,
        };

        var pixels = new Color[TextureSize * TextureSize];
        for (int y = 0; y < TextureSize; y++)
        {
            bool darkBand = (y / StripeWidth) % 2 == 0;
            Color baseColor = darkBand ? DarkStripe : LightStripe;

            for (int x = 0; x < TextureSize; x++)
            {
                // Low-frequency Perlin: large soft patches of variation.
                float noise = (Mathf.PerlinNoise(x * NoiseScale, y * NoiseScale) - 0.5f) * NoiseAmplitude;
                // High-frequency grain: per-pixel grass-blade specks.
                float grain = (Random.value - 0.5f) * GrainAmplitude;
                float delta = noise + grain;

                pixels[y * TextureSize + x] = new Color(
                    Mathf.Clamp01(baseColor.r + delta),
                    Mathf.Clamp01(baseColor.g + delta * 1.2f), // green channel slightly more responsive
                    Mathf.Clamp01(baseColor.b + delta));
            }
        }

        tex.SetPixels(pixels);
        tex.Apply(updateMipmaps: true);
        return tex;
    }
}
