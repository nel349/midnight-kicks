using UnityEngine;

/// <summary>
/// A flat rectangular crowd wall placed behind the goal. Single quad, single
/// material, double-sided triangles so winding can never make it invisible.
/// The texture tiles horizontally so each spectator is large per screen pixel.
///
/// Drop a tileable stadium-crowd texture at
/// <c>Assets/Resources/StadiumCrowd.(png|jpg)</c>. Falls back to a flat
/// tinted wall if absent.
///
/// Self-attaches at scene load. No scene edits required.
/// </summary>
public class CrowdBackdrop : MonoBehaviour
{
    private const string CrowdTextureResource = "StadiumCrowd";

    // ── Wall placement ──
    // Goal line is at z=9.5; wall sits ~8m behind that. From the camera at
    // (0, 2.2, -8) looking at (0, 1.4, 9.5), the wall is ~25m forward — far
    // enough to feel "in the distance" without dominating the frame.
    private const float WallZ = 17.5f;
    // Wall dimensions — wide and tall enough to fill the camera FOV at this
    // distance (FOV 55° at 25m gives ~26m horizontal × ~18m vertical visible).
    private const float WallWidth = 40f;
    private const float WallHeight = 26f;
    private const float WallBaseY = 0f;

    // ── Texture tiling ──
    // Tile so each "section" of the source crowd is repeated for higher
    // per-pixel detail per spectator. 3× horizontal is the FC25 trick —
    // they use 5-8 copies of a crowd asset across the wall.
    private const float TilingU = 3f;
    private const float TilingV = 1f;

    private static readonly Color FallbackTint = new Color(0.22f, 0.26f, 0.32f, 1f);

    [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
    private static void AutoCreate()
    {
        if (FindFirstObjectByType<CrowdBackdrop>() != null) return;
        var go = new GameObject("CrowdBackdrop");
        go.AddComponent<CrowdBackdrop>();
        Debug.Log("[CrowdBackdrop] Auto-created");
    }

    void Start()
    {
        var crowdTex = Resources.Load<Texture2D>(CrowdTextureResource);
        if (crowdTex == null)
        {
            Debug.LogWarning(
                $"[CrowdBackdrop] No Texture2D at Resources/{CrowdTextureResource}. " +
                "Drop a tileable stadium texture at unity/Assets/Resources/StadiumCrowd.png. " +
                "Falling back to flat tinted wall.");
        }
        BuildWall(crowdTex);
    }

    private void BuildWall(Texture2D crowdTex)
    {
        // Build a flat quad facing -Z (toward the camera/field). Geometry is
        // double-sided (two triangles per side) so it renders regardless of
        // shader culling settings — no more invisible-back-face surprises.
        float halfW = WallWidth * 0.5f;
        var mesh = new Mesh { name = "CrowdWall" };

        var vertices = new Vector3[]
        {
            new Vector3(-halfW, WallBaseY,              0f),   // 0: bottom-left
            new Vector3( halfW, WallBaseY,              0f),   // 1: bottom-right
            new Vector3( halfW, WallBaseY + WallHeight, 0f),   // 2: top-right
            new Vector3(-halfW, WallBaseY + WallHeight, 0f),   // 3: top-left
        };
        var uvs = new Vector2[]
        {
            new Vector2(0f,      0f),
            new Vector2(TilingU, 0f),
            new Vector2(TilingU, TilingV),
            new Vector2(0f,      TilingV),
        };
        // Two triangles facing -Z (front, toward camera) + two facing +Z (back).
        // Front: 0→2→1, 0→3→2
        // Back:  0→1→2, 0→2→3   (reverse winding)
        var triangles = new int[]
        {
            0, 2, 1,
            0, 3, 2,
            0, 1, 2,
            0, 2, 3,
        };

        mesh.vertices = vertices;
        mesh.uv = uvs;
        mesh.triangles = triangles;
        mesh.RecalculateNormals();
        mesh.RecalculateBounds();

        var child = new GameObject("CrowdWall");
        child.transform.SetParent(transform, worldPositionStays: false);
        child.transform.position = new Vector3(0f, 0f, WallZ);
        // Quad lives in the XY plane by construction; it already faces -Z.

        var mf = child.AddComponent<MeshFilter>();
        mf.sharedMesh = mesh;

        var mr = child.AddComponent<MeshRenderer>();
        var shader = Shader.Find("Universal Render Pipeline/Unlit")
                  ?? Shader.Find("Unlit/Texture")
                  ?? Shader.Find("Sprites/Default");
        var mat = new Material(shader) { name = "Crowd_Wall" };
        if (crowdTex != null)
        {
            crowdTex.wrapMode = TextureWrapMode.Repeat;
            crowdTex.filterMode = FilterMode.Bilinear;
            if (mat.HasProperty("_BaseMap")) mat.SetTexture("_BaseMap", crowdTex);
            if (mat.HasProperty("_MainTex")) mat.SetTexture("_MainTex", crowdTex);
        }
        else
        {
            mat.color = FallbackTint;
        }
        mr.sharedMaterial = mat;
        mr.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
        mr.receiveShadows = false;
    }
}
