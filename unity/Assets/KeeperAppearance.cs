using UnityEngine;

/// <summary>
/// Dresses the Keeper GameObject as Jorge Campos in his 1994 World Cup
/// hot-pink-and-yellow self-designed kit. Classifies by renderer name —
/// the Mixamo soccer_character shares a single body material across all
/// clothing renderers, so the only reliable signal is the GameObject name
/// (Ch38_Shirt, Ch38_Shorts, Ch38_Socks, Ch38_Shoes, Ch38_Body, etc.).
///
/// Self-attaches at scene load. Idempotent.
/// </summary>
public class KeeperAppearance : MonoBehaviour
{
    private const string KeeperObjectName = "Keeper";

    // Jorge Campos 1994 World Cup — hot pink shirt, neon yellow shorts,
    // hot pink socks. The most iconic goalkeeper kit in soccer history.
    private static readonly Color JerseyColor = new Color(1.000f, 0.078f, 0.576f); // #FF1493 hot pink
    private static readonly Color ShortsColor = new Color(0.949f, 0.902f, 0.000f); // #F2E600 neon yellow
    private static readonly Color SocksColor  = new Color(1.000f, 0.078f, 0.576f); // #FF1493 hot pink
    private static readonly Color ShoesColor  = new Color(0.040f, 0.040f, 0.040f); // black boots

    // The Acapulco-jersey supporting palette used by the procedural shirt
    // texture (chevrons + camo + collar). All four hex values match colors
    // visible in the original 1995 J. Campos jersey reference.
    private static readonly Color LimeGreen    = new Color(0.224f, 1.000f, 0.078f); // #39FF14
    private static readonly Color NeonYellow   = new Color(0.949f, 0.902f, 0.000f); // #F2E600
    private static readonly Color ElectricBlue = new Color(0.000f, 0.502f, 1.000f); // #0080FF

    private const int ShirtTextureSize = 512;
    private const string ShirtRendererName = "Ch38_Shirt";

    // Skin / hair — same warm medium tone as the shooter for visual
    // continuity. Campos's actual hair is jet-black; we pick near-black
    // instead of dark brown to differentiate from the shooter.
    private static readonly Color SkinColor = new Color(0.710f, 0.537f, 0.416f); // #B5896A warm medium
    private static readonly Color HairColor = new Color(0.080f, 0.060f, 0.050f); // #14100D near-black
    private static readonly Color EyesColor = new Color(0.231f, 0.141f, 0.094f); // #3B2418 dark brown

    private enum Slot { Jersey, Shorts, Socks, Shoes, Skin, Hair, Eyes, Unknown }

    [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
    private static void AutoCreate()
    {
        if (FindAnyObjectByType<KeeperAppearance>() != null) return;
        var go = new GameObject("KeeperAppearance");
        go.AddComponent<KeeperAppearance>();
        Debug.Log("[KeeperAppearance] Auto-created");
    }

    void Start()
    {
        var keeper = GameObject.Find(KeeperObjectName);
        if (keeper == null)
        {
            Debug.LogWarning($"[KeeperAppearance] No GameObject named '{KeeperObjectName}' found — skip dressing (X Bot still in place?)");
            return;
        }

        var skinned = keeper.GetComponentsInChildren<SkinnedMeshRenderer>(includeInactive: true);
        Debug.Log($"[KeeperAppearance] Found {skinned.Length} SkinnedMeshRenderer(s) on '{KeeperObjectName}'");

        int dressed = 0;
        int skipped = 0;
        foreach (var r in skinned)
        {
            var mats = r.materials;
            for (int i = 0; i < mats.Length; i++)
            {
                var m = mats[i];
                if (m == null) continue;

                // Classify by renderer (GameObject) name — material names
                // collide ('Ch38_body' is reused across all body+clothing
                // renderers). The renderer name is reliable.
                Slot slot = ClassifyByName(r.name);

                // The Shirt gets a procedural texture, not a flat color —
                // we paint a Campos Acapulco-style pattern (green collar,
                // armpit chevrons, flame camo) into a Texture2D at scene
                // load and bind it as the shirt's _BaseMap.
                if (slot == Slot.Jersey)
                {
                    ApplyShirtTexture(m);
                    dressed++;
                }
                else if (TryGetKitColor(slot, out Color color))
                {
                    ApplyKitColor(m, color);
                    dressed++;
                }
                else
                {
                    skipped++;
                }
                Debug.Log($"[KeeperAppearance] {r.name}[{i}] → {slot}");
            }
            r.materials = mats;
        }
        Debug.Log($"[KeeperAppearance] Done — dressed {dressed}, skipped {skipped} across {skinned.Length} renderer(s)");
    }

    private static Slot ClassifyByName(string rendererName)
    {
        if (string.IsNullOrEmpty(rendererName)) return Slot.Unknown;
        string n = rendererName.ToLowerInvariant();

        if (n.Contains("jersey") || n.Contains("shirt") || n.Contains("top")) return Slot.Jersey;
        if (n.Contains("short") || n.Contains("pant") || n.Contains("trouser")) return Slot.Shorts;
        if (n.Contains("sock") || n.Contains("stocking")) return Slot.Socks;
        if (n.Contains("shoe") || n.Contains("boot") || n.Contains("cleat")) return Slot.Shoes;

        if (n.Contains("eye") && !n.Contains("lash") && !n.Contains("brow")) return Slot.Eyes;
        if (n.Contains("lash") || n.Contains("brow") || n.Contains("hair") || n.Contains("scalp")) return Slot.Hair;
        if (n.Contains("body") || n.Contains("skin") || n.Contains("face") ||
            n.Contains("head") || n.Contains("teeth") || n.Contains("gum") || n.Contains("tongue")) return Slot.Skin;

        return Slot.Unknown;
    }

    private static bool TryGetKitColor(Slot slot, out Color color)
    {
        switch (slot)
        {
            case Slot.Jersey: color = JerseyColor; return true;
            case Slot.Shorts: color = ShortsColor; return true;
            case Slot.Socks:  color = SocksColor;  return true;
            case Slot.Shoes:  color = ShoesColor;  return true;
            case Slot.Skin:   color = SkinColor;   return true;
            case Slot.Hair:   color = HairColor;   return true;
            case Slot.Eyes:   color = EyesColor;   return true;
            default:          color = default;     return false;
        }
    }

    private static void ApplyKitColor(Material m, Color color)
    {
        if (m.HasProperty("_BaseColor")) m.SetColor("_BaseColor", color);
        if (m.HasProperty("_Color"))     m.SetColor("_Color", color);
        if (m.HasProperty("_Metallic"))   m.SetFloat("_Metallic", 0f);
        if (m.HasProperty("_Smoothness")) m.SetFloat("_Smoothness", 0.15f);
        if (m.HasProperty("_Glossiness")) m.SetFloat("_Glossiness", 0.15f);
    }

    // ── Procedural Campos shirt texture ────────────────────────────────
    // The Acapulco jersey has three distinct visual regions: a lime-green
    // collar at the top, hot-pink chest panels with yellow+blue triangular
    // chevrons under the armpits and across the chest, and a multi-color
    // "flame camo" at the bottom. We paint those into a 512×512 texture
    // and bind it as _BaseMap on the shirt material. The mesh's UV layout
    // determines how cleanly each region maps onto the body — Mixamo
    // characters typically use a standard front/back/sides unwrap so the
    // top of the texture lands on the collar area. If the result looks
    // misaligned, the fix is to swap the v-axis (1f - v) or transpose u/v.

    private static Texture2D cachedShirtTexture;

    private static void ApplyShirtTexture(Material m)
    {
        if (cachedShirtTexture == null)
        {
            cachedShirtTexture = GenerateCamposShirtTexture();
        }
        if (m.HasProperty("_BaseMap")) m.SetTexture("_BaseMap", cachedShirtTexture);
        if (m.HasProperty("_MainTex")) m.SetTexture("_MainTex", cachedShirtTexture);
        // White base color so the texture's own colors render unaltered —
        // tinting with pink would lose the chevrons + flames.
        if (m.HasProperty("_BaseColor")) m.SetColor("_BaseColor", Color.white);
        if (m.HasProperty("_Color"))     m.SetColor("_Color", Color.white);
        if (m.HasProperty("_Metallic"))   m.SetFloat("_Metallic", 0f);
        if (m.HasProperty("_Smoothness")) m.SetFloat("_Smoothness", 0.20f); // slight fabric sheen
    }

    private static Texture2D GenerateCamposShirtTexture()
    {
        var tex = new Texture2D(ShirtTextureSize, ShirtTextureSize, TextureFormat.RGB24, mipChain: true)
        {
            name = "CamposShirt_Procedural",
            wrapMode = TextureWrapMode.Clamp,
            filterMode = FilterMode.Bilinear,
            anisoLevel = 4,
        };
        var pixels = new Color[ShirtTextureSize * ShirtTextureSize];
        for (int y = 0; y < ShirtTextureSize; y++)
        {
            float v = y / (float)(ShirtTextureSize - 1);
            for (int x = 0; x < ShirtTextureSize; x++)
            {
                float u = x / (float)(ShirtTextureSize - 1);
                pixels[y * ShirtTextureSize + x] = CamposPixelColor(u, v);
            }
        }
        tex.SetPixels(pixels);
        tex.Apply(updateMipmaps: true);
        Debug.Log("[KeeperAppearance] Generated procedural Campos shirt texture (512×512)");
        return tex;
    }

    /// <summary>
    /// Color of a single texel in (u, v) ∈ [0,1]². LARGE low-frequency
    /// Perlin patches in 4 Campos colors (pink / green / yellow / blue).
    /// We don't try to place named features — the Mixamo shirt UV layout
    /// would scramble them anyway. Instead the whole shirt becomes a wild
    /// multi-color jersey that reads correctly at gameplay distance.
    ///
    /// Frequencies (3, 4) are deliberately low: each color patch ends up
    /// ~30% of the texture, which mipmaps down to a few clearly-distinct
    /// screen pixels at gameplay distance — still visibly multi-color
    /// rather than averaging to mauve.
    /// </summary>
    private static Color CamposPixelColor(float u, float v)
    {
        float n1 = Mathf.PerlinNoise(u * 3f,  v * 4f);
        float n2 = Mathf.PerlinNoise(u * 7f,  v * 10f) * 0.25f;
        float n  = Mathf.Clamp01(n1 + n2);

        // Hard thresholds → sharp color boundaries that survive mipmaps.
        // Pink gets the largest slice so the shirt still reads "pink-
        // dominant Campos" rather than a generic patchwork.
        if (n < 0.22f) return LimeGreen;
        if (n < 0.42f) return ElectricBlue;
        if (n < 0.60f) return NeonYellow;
        return JerseyColor; // hot pink
    }
}
