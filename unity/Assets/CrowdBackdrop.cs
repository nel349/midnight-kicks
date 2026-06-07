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

    // ── Curved crowd arc ──
    // The backdrop is a cylinder segment centred on the CAMERA's XZ position, so
    // every spectator sits at the same distance from the view and the crowd wraps
    // the whole frame with no visible flat edge — even at ultra-wide aspect or an
    // angled camera (a flat wall showed its left edge + blue sky past it). The
    // seamless 4:1 StadiumCrowd panorama maps once across the arc.
    //
    // Tunables (adjust to taste, then re-export Unity):
    //   Radius           — distance of the crowd from the view (≈ old wall depth).
    //   ArcHalfDegrees    — half the sweep each side of straight-ahead.
    //   WallHeight/BaseY  — vertical extent + floor of the stands.
    private const float CameraZ = -13f;        // match camera sits at (0, 4.5, -13)
    private const float Radius = 32f;          // ≈ old flat-wall distance → keeps spectator size
    private const float ArcHalfDegrees = 80f;  // 160° total sweep — covers ultra-wide / angled views
    private const int Segments = 48;           // arc smoothness
    // ~160° arc at R=32 is ≈89m long. Height/base tuned so the camera frames
    // the crowd, not the pitch-edge lip at the bottom of the panorama texture.
    // UVBottom crops the bottom few % of the texture (transition band); BaseY
    // sinks that band below the grass plane so the visible arc is all stands.
    private const float WallHeight = 28f;
    private const float WallBaseY = -2f;
    private const float UVBottom = 0.05f;

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
        // Build a curved strip: an arc of [Segments] quads sweeping from
        // -ArcHalfDegrees to +ArcHalfDegrees around the camera's XZ position, at
        // constant [Radius]. Double-sided so it's visible from inside regardless
        // of shader culling. The seamless panorama maps once (U: 0→1 across the
        // arc, V: 0→1 bottom→top).
        var mesh = new Mesh { name = "CrowdArc" };

        int cols = Segments + 1;
        var vertices = new Vector3[cols * 2];
        var uvs = new Vector2[cols * 2];
        float halfRad = ArcHalfDegrees * Mathf.Deg2Rad;
        for (int i = 0; i < cols; i++)
        {
            float t = (float)i / Segments;               // 0..1 across the arc
            float a = Mathf.Lerp(-halfRad, halfRad, t);
            float x = Mathf.Sin(a) * Radius;
            float z = CameraZ + Mathf.Cos(a) * Radius;
            vertices[i] = new Vector3(x, WallBaseY, z);                       // bottom row
            vertices[cols + i] = new Vector3(x, WallBaseY + WallHeight, z);   // top row
            uvs[i] = new Vector2(t, UVBottom);
            uvs[cols + i] = new Vector2(t, 1f);
        }

        // Two triangles per segment, emitted in both windings (front + back) so
        // the inside-facing arc always renders.
        var triangles = new int[Segments * 12];
        int ti = 0;
        for (int i = 0; i < Segments; i++)
        {
            int bl = i, br = i + 1, tl = cols + i, tr = cols + i + 1;
            triangles[ti++] = bl; triangles[ti++] = tl; triangles[ti++] = br;
            triangles[ti++] = br; triangles[ti++] = tl; triangles[ti++] = tr;
            triangles[ti++] = bl; triangles[ti++] = br; triangles[ti++] = tl;
            triangles[ti++] = br; triangles[ti++] = tr; triangles[ti++] = tl;
        }

        mesh.vertices = vertices;
        mesh.uv = uvs;
        mesh.triangles = triangles;
        mesh.RecalculateNormals();
        mesh.RecalculateBounds();

        var child = new GameObject("CrowdArc");
        child.transform.SetParent(transform, worldPositionStays: false);
        // Vertices are already baked in world space (centred on the camera XZ).
        child.transform.position = Vector3.zero;

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
            // Trilinear smooths mip transitions; anisotropic filtering keeps
            // the texture sharp when sampled at oblique angles — and the
            // crowd wall IS at an oblique angle from the off-axis FC25 cam.
            // Without this, Bilinear blurs spectator faces toward the
            // edges of the frame.
            crowdTex.filterMode = FilterMode.Trilinear;
            crowdTex.anisoLevel = 8;
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
