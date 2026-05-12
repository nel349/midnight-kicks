using UnityEngine;

/// <summary>
/// Builds a procedural cylindrical stadium ring around the pitch and textures
/// it with a stadium-stand image. The texture is loaded from
/// <c>Assets/Resources/StadiumCrowd.(png|jpg)</c> at runtime.
///
/// Drop any tileable stadium / tribune / crowd texture at that path and
/// Unity will pick it up automatically. If the texture is missing the script
/// logs a warning and leaves the ring untextured (visible as a flat tinted
/// cylinder so the absence is obvious).
///
/// Self-attaches at scene load. No scene edits required.
///
/// Recommended sources (CC0 / free):
///   - ambientcg.com (search "stadium", "tribune")
///   - polyhaven.com (search "stadium")
///   - opengameart.org
/// </summary>
public class CrowdBackdrop : MonoBehaviour
{
    // Resources path — no extension, no leading "Resources/".
    private const string CrowdTextureResource = "StadiumCrowd";

    // ── Ring geometry ──
    private const int PanelCount = 36;
    private const float Radius = 45f;
    private const float PanelHeight = 22f;
    private const float BaseY = 0f;
    private static readonly Vector3 RingCenter = new Vector3(0f, 0f, 4f);

    // How many times the texture tiles around the ring. Adjust if the texture
    // shows obvious repetition or if the people-density looks wrong.
    private const float TilingU = 2f;
    private const float TilingV = 1f;

    // Fallback color when no texture is found — drab dark blue so the missing
    // texture is unambiguous rather than rendering as magenta or white.
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
                $"[CrowdBackdrop] No Texture2D found at Resources/{CrowdTextureResource}. " +
                "Drop a tileable stadium texture (PNG or JPG) at " +
                "unity/Assets/Resources/StadiumCrowd.png. Falling back to flat tinted ring.");
        }

        BuildRing(crowdTex);
    }

    private void BuildRing(Texture2D crowdTex)
    {
        var mesh = new Mesh { name = "CrowdRing" };
        const int vertsPerPanel = 4;
        int vertCount = PanelCount * vertsPerPanel;
        var vertices = new Vector3[vertCount];
        var uvs = new Vector2[vertCount];
        var triangles = new int[PanelCount * 6];

        for (int i = 0; i < PanelCount; i++)
        {
            float a0 = (i / (float)PanelCount) * Mathf.PI * 2f;
            float a1 = ((i + 1) / (float)PanelCount) * Mathf.PI * 2f;
            // U tiles TilingU times around the ring so the source texture
            // doesn't get stretched flat across 360°.
            float u0 = (i / (float)PanelCount) * TilingU;
            float u1 = ((i + 1) / (float)PanelCount) * TilingU;

            Vector3 left  = new Vector3(Mathf.Cos(a0) * Radius, BaseY, Mathf.Sin(a0) * Radius);
            Vector3 right = new Vector3(Mathf.Cos(a1) * Radius, BaseY, Mathf.Sin(a1) * Radius);
            Vector3 up = Vector3.up * PanelHeight;

            int v = i * vertsPerPanel;
            vertices[v + 0] = left;          uvs[v + 0] = new Vector2(u0, 0f);
            vertices[v + 1] = right;         uvs[v + 1] = new Vector2(u1, 0f);
            vertices[v + 2] = right + up;    uvs[v + 2] = new Vector2(u1, TilingV);
            vertices[v + 3] = left + up;     uvs[v + 3] = new Vector2(u0, TilingV);

            // Inside-facing winding so the camera (which sits inside the ring)
            // sees the texture. _Cull = 0 on the material draws both sides
            // anyway, but starting with the right winding helps the shader
            // sample lighting normals consistently.
            int t = i * 6;
            triangles[t + 0] = v + 0; triangles[t + 1] = v + 2; triangles[t + 2] = v + 1;
            triangles[t + 3] = v + 0; triangles[t + 4] = v + 3; triangles[t + 5] = v + 2;
        }

        mesh.vertices = vertices;
        mesh.uv = uvs;
        mesh.triangles = triangles;
        mesh.RecalculateNormals();
        mesh.RecalculateBounds();

        var child = new GameObject("CrowdRing");
        child.transform.SetParent(transform, worldPositionStays: false);
        child.transform.position = RingCenter;

        var mf = child.AddComponent<MeshFilter>();
        mf.sharedMesh = mesh;

        var mr = child.AddComponent<MeshRenderer>();
        var shader = Shader.Find("Universal Render Pipeline/Unlit")
                  ?? Shader.Find("Unlit/Texture")
                  ?? Shader.Find("Sprites/Default");
        var mat = new Material(shader) { name = "Crowd_Stadium" };
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
        if (mat.HasProperty("_Cull")) mat.SetFloat("_Cull", 0f);
        mr.sharedMaterial = mat;
        mr.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
        mr.receiveShadows = false;
    }
}
