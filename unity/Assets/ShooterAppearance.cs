using UnityEngine;

/// <summary>
/// Dresses the Shooter GameObject in a Mexico-inspired soccer kit so it
/// reads as a player instead of the imported flat-white Mixamo mesh.
///
/// Strategy: walk every SkinnedMeshRenderer on the Shooter, instance each
/// material (so we don't mutate the FBX asset), and assign a color based
/// on the slot's material name (Mixamo convention: "Body", "Hair", "Eyes",
/// + clothing slots like "Shirt", "Shorts", "Socks", "Shoes").
///
/// Body / skin / hair / eyes are left untouched — if the FBX shipped with
/// face & body textures they show through; if not, those slots stay at
/// whatever default the FBX defined (we iterate later if needed).
///
/// First run logs every material slot name found, so we can verify the
/// FBX's actual structure and refine ClassifyByName if any slot is
/// mis-classified.
///
/// Self-attaches at scene load. Idempotent.
/// </summary>
public class ShooterAppearance : MonoBehaviour
{
    private const string ShooterObjectName = "Shooter";

    // Diagnostic switch — when true, every slot gets a bright debug color
    // so we can verify the pipeline is wiring up. Off in normal play.
    private static readonly bool ForcePaintAll = false;

    // Kit colours — mutable so the Kotlin bridge can override them per match via
    // [SetKit] (the player's chosen national kit). Defaults to a Mexico-inspired
    // home strip until a playerAppearance message arrives.
    private static Color JerseyColor = new Color(0.000f, 0.408f, 0.278f); // #006847
    private static Color ShortsColor = new Color(0.960f, 0.960f, 0.960f); // off-white
    private static Color SocksColor  = new Color(0.808f, 0.067f, 0.149f); // #CE1126
    private static readonly Color ShoesColor  = new Color(0.040f, 0.040f, 0.040f); // black boots

    // Skin / hair / eyes — the FBX shipped with no textures (diagnostic log
    // showed texture=False on every slot), so we paint these flat colors
    // to humanize the character. Warm medium tone reads as a Rarámuri /
    // Latino player to match the on-brand Mexico kit; dark brown hair and
    // eyes complete the silhouette.
    private static readonly Color SkinColor = new Color(0.710f, 0.537f, 0.416f); // #B5896A warm medium
    private static readonly Color HairColor = new Color(0.165f, 0.102f, 0.059f); // #2A1A0F dark brown
    private static readonly Color EyesColor = new Color(0.231f, 0.141f, 0.094f); // #3B2418 dark brown

    // High-visibility debug palette used when ForcePaintAll = true. One
    // color per slot index so adjacent slots are visually distinct.
    private static readonly Color[] DebugPalette = new[]
    {
        new Color(1f, 0f, 0f),    // red
        new Color(0f, 1f, 0f),    // green
        new Color(0f, 0f, 1f),    // blue
        new Color(1f, 1f, 0f),    // yellow
        new Color(1f, 0f, 1f),    // magenta
        new Color(0f, 1f, 1f),    // cyan
        new Color(1f, 0.5f, 0f),  // orange
        new Color(0.5f, 0f, 1f),  // purple
    };

    // Categories whose materials we DO NOT touch — the user wants the FBX
    // texture (if any) to show through for face/body/hair/eyes.
    private enum Slot { Jersey, Shorts, Socks, Shoes, Skin, Hair, Eyes, Unknown }

    [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
    private static void AutoCreate()
    {
        if (FindAnyObjectByType<ShooterAppearance>() != null) return;
        var go = new GameObject("ShooterAppearance");
        go.AddComponent<ShooterAppearance>();
        Debug.Log("[ShooterAppearance] Auto-created");
    }

    void Start()
    {
        Dress();
    }

    /// <summary>
    /// Override the shooter's kit at runtime (Kotlin's playerAppearance bridge
    /// message) and re-dress immediately if the Shooter is already in the scene;
    /// otherwise the colours apply on the next dress. Local cosmetics only.
    /// </summary>
    public static void SetKit(Color jersey, Color shorts, Color socks)
    {
        JerseyColor = jersey;
        ShortsColor = shorts;
        SocksColor = socks;
        var instance = FindAnyObjectByType<ShooterAppearance>();
        if (instance != null) instance.Dress();
    }

    /// <summary>
    /// Walk the Shooter's renderers and apply the current kit colours. Re-run by
    /// [SetKit] when the kit changes at runtime; idempotent.
    /// </summary>
    public void Dress()
    {
        var shooter = GameObject.Find(ShooterObjectName);
        if (shooter == null)
        {
            Debug.LogWarning($"[ShooterAppearance] No GameObject named '{ShooterObjectName}' found");
            return;
        }

        // Also walk MeshRenderers in case any sub-meshes aren't skinned.
        var skinned = shooter.GetComponentsInChildren<SkinnedMeshRenderer>(includeInactive: true);
        var statics = shooter.GetComponentsInChildren<MeshRenderer>(includeInactive: true);
        Debug.Log($"[ShooterAppearance] Found {skinned.Length} SkinnedMeshRenderer(s) and {statics.Length} MeshRenderer(s) on '{ShooterObjectName}'");

        int paletteIdx = 0;
        int dressed = 0;
        int skipped = 0;
        int totalSlots = 0;

        foreach (var r in skinned)
        {
            var mats = r.materials;
            for (int i = 0; i < mats.Length; i++)
            {
                var m = mats[i];
                if (m == null) continue;
                totalSlots++;

                // Classify by RENDERER (GameObject) name, not material name.
                // The Mixamo FBX shares 2 materials (Ch38_body, Ch38_hair)
                // across 7 sub-meshes — the material name says "body" even
                // for the Shirt/Shorts/Socks renderers. The GameObject name
                // (Ch38_Shirt, Ch38_Shorts, etc.) is the reliable signal.
                Slot slot = ClassifyByName(r.name);
                bool hasBaseMap = m.HasProperty("_BaseMap") && m.GetTexture("_BaseMap") != null;
                bool hasMainTex = m.HasProperty("_MainTex") && m.GetTexture("_MainTex") != null;
                bool hasTexture = hasBaseMap || hasMainTex;

                Color color;
                string label;
                if (ForcePaintAll)
                {
                    color = DebugPalette[paletteIdx % DebugPalette.Length];
                    paletteIdx++;
                    ApplyKitColor(m, color);
                    label = $"FORCE→{ColorUtility.ToHtmlStringRGB(color)}";
                    dressed++;
                }
                else if (TryGetKitColor(slot, out color))
                {
                    ApplyKitColor(m, color);
                    label = $"{slot}=#{ColorUtility.ToHtmlStringRGB(color)}";
                    dressed++;
                }
                else
                {
                    label = $"{slot} (skipped)";
                    skipped++;
                }
                Debug.Log($"[ShooterAppearance] SKIN {r.name}[{i}] '{m.name}' → {label} (texture={hasTexture}, shader={m.shader.name})");
            }
            r.materials = mats;
        }

        // Repeat for any non-skinned MeshRenderers (rare on rigged Mixamo
        // characters, but accessories/props can sneak in).
        foreach (var r in statics)
        {
            var mats = r.materials;
            for (int i = 0; i < mats.Length; i++)
            {
                var m = mats[i];
                if (m == null) continue;
                totalSlots++;
                Color color = DebugPalette[paletteIdx % DebugPalette.Length];
                paletteIdx++;
                ApplyKitColor(m, color);
                Debug.Log($"[ShooterAppearance] MESH {r.name}[{i}] '{m.name}' → FORCE→{ColorUtility.ToHtmlStringRGB(color)} (shader={m.shader.name})");
            }
            r.materials = mats;
        }

        Debug.Log($"[ShooterAppearance] Done — totalSlots={totalSlots}, dressed={dressed}, skipped={skipped}");
    }

    /// <summary>
    /// Best-effort classification of a Mixamo material slot by its name.
    /// Mixamo uses prefixes like "Ch36_" or "Alpha_"; the role keyword is
    /// usually in the suffix ("Body", "Hair", "Shirt", etc.). Comparisons
    /// are lower-cased and substring-based so prefixes don't matter.
    /// </summary>
    private static Slot ClassifyByName(string materialName)
    {
        if (string.IsNullOrEmpty(materialName)) return Slot.Unknown;
        string n = materialName.ToLowerInvariant();

        // Clothing — order matters: check more specific terms first.
        if (n.Contains("jersey") || n.Contains("shirt") || n.Contains("top")) return Slot.Jersey;
        if (n.Contains("short") || n.Contains("pant") || n.Contains("trouser")) return Slot.Shorts;
        if (n.Contains("sock") || n.Contains("stocking")) return Slot.Socks;
        if (n.Contains("shoe") || n.Contains("boot") || n.Contains("cleat")) return Slot.Shoes;

        // Body — left untouched. Listed for logging clarity.
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

    /// <summary>
    /// Tint the material with the kit color. URP/Lit uses _BaseColor;
    /// built-in Standard uses _Color. We set both so the script survives
    /// pipeline changes. Fabric is matte — metallic 0, smoothness low.
    /// </summary>
    private static void ApplyKitColor(Material m, Color color)
    {
        if (m.HasProperty("_BaseColor")) m.SetColor("_BaseColor", color);
        if (m.HasProperty("_Color"))     m.SetColor("_Color", color);
        if (m.HasProperty("_Metallic"))   m.SetFloat("_Metallic", 0f);
        if (m.HasProperty("_Smoothness")) m.SetFloat("_Smoothness", 0.15f);
        if (m.HasProperty("_Glossiness")) m.SetFloat("_Glossiness", 0.15f);
    }
}
