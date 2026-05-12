using UnityEngine;

/// <summary>
/// Builds a procedural low-poly crowd silhouette ring around the field at
/// scene load. A cylinder of dark teal-blue panels with a subtle vertical
/// gradient that reads as "stadium stand at night" from gameplay distance.
/// Zero asset imports.
///
/// Auto-attaches at scene load. Idempotent.
/// </summary>
public class CrowdBackdrop : MonoBehaviour
{
    // Geometry: more panels = smoother silhouette. Pushed out past the new
    // 50×40m field, tall enough to fill the horizon from any reasonable
    // camera angle.
    private const int PanelCount = 36;
    private const float Radius = 45f;
    private const float PanelHeight = 22f;
    private const float BaseY = 0f;
    private static readonly Vector3 RingCenter = new Vector3(0f, 0f, 4f);

    // Three-band base gradient: dark base (stands closer to pitch), lighter
    // mid (crowd faces catching floodlight bleed), darker top (back of stand
    // against the night sky). Each panel multiplies these by a random
    // brightness in [BrightnessVarianceMin, BrightnessVarianceMax] so the
    // ring reads as crowd sections, not a uniform wall.
    private static readonly Color BottomColor = new Color(0.10f, 0.12f, 0.16f, 1f);
    private static readonly Color MidColor    = new Color(0.28f, 0.32f, 0.38f, 1f);
    private static readonly Color TopColor    = new Color(0.14f, 0.16f, 0.22f, 1f);
    private const float BrightnessVarianceMin = 0.6f;
    private const float BrightnessVarianceMax = 1.4f;

    // Audience light specks — quads visible at 45m, multiple colors to read
    // as phone screens, camera flashes, and safety lighting in the crowd.
    private const int SpeckCount = 280;
    private const float SpeckHeightMin = 0.20f;  // 0..1 normalized along panel height
    private const float SpeckHeightMax = 0.92f;
    private const float SpeckSizeMin = 0.45f;    // visible at 45m distance
    private const float SpeckSizeMax = 0.95f;
    private static readonly Color[] SpeckColors = new[]
    {
        new Color(1.00f, 0.95f, 0.65f, 1f),  // warm white (phone screens / flashes)
        new Color(1.00f, 0.80f, 0.45f, 1f),  // amber (incandescent)
        new Color(0.85f, 0.95f, 1.00f, 1f),  // cool white (LED lights)
        new Color(0.55f, 0.75f, 1.00f, 1f),  // blue (phone screens)
    };

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
        BuildRing();
        ScatterAudienceSpecks();
    }

    private void BuildRing()
    {
        // Three vertical bands per panel so the gradient has a Mid color
        // pulling brightness up where the camera looks, instead of a flat
        // bottom-to-top fade. 4 verts per panel × 2 quads = 8 verts.
        var mesh = new Mesh { name = "CrowdRing" };
        int vertsPerPanel = 6; // bottom, mid, top — left and right side each
        int vertCount = PanelCount * vertsPerPanel;
        var vertices = new Vector3[vertCount];
        var triangles = new int[PanelCount * 12]; // 2 quads = 4 tris × 3 indices
        var colors = new Color[vertCount];

        for (int i = 0; i < PanelCount; i++)
        {
            float a0 = (i / (float)PanelCount) * Mathf.PI * 2f;
            float a1 = ((i + 1) / (float)PanelCount) * Mathf.PI * 2f;

            Vector3 left = new Vector3(Mathf.Cos(a0) * Radius, BaseY, Mathf.Sin(a0) * Radius);
            Vector3 right = new Vector3(Mathf.Cos(a1) * Radius, BaseY, Mathf.Sin(a1) * Radius);
            Vector3 mid = Vector3.up * (PanelHeight * 0.5f);
            Vector3 up = Vector3.up * PanelHeight;

            int v = i * vertsPerPanel;
            vertices[v + 0] = left;             // bottom-left
            vertices[v + 1] = right;            // bottom-right
            vertices[v + 2] = left + mid;       // mid-left
            vertices[v + 3] = right + mid;      // mid-right
            vertices[v + 4] = left + up;        // top-left
            vertices[v + 5] = right + up;       // top-right

            // Random per-panel brightness so the ring reads as crowd
            // sections rather than a uniform fence.
            float tint = Random.Range(BrightnessVarianceMin, BrightnessVarianceMax);
            colors[v + 0] = BottomColor * tint;
            colors[v + 1] = BottomColor * tint;
            colors[v + 2] = MidColor    * tint;
            colors[v + 3] = MidColor    * tint;
            colors[v + 4] = TopColor    * tint;
            colors[v + 5] = TopColor    * tint;

            // Two quads per panel (bottom→mid, mid→top). Winding chosen so
            // the inside-facing surface (toward field center) is the front
            // face — camera sits inside the ring looking outward.
            int t = i * 12;
            // Bottom quad
            triangles[t + 0] = v + 0; triangles[t + 1] = v + 3; triangles[t + 2] = v + 1;
            triangles[t + 3] = v + 0; triangles[t + 4] = v + 2; triangles[t + 5] = v + 3;
            // Top quad
            triangles[t + 6] = v + 2; triangles[t + 7] = v + 5; triangles[t + 8] = v + 3;
            triangles[t + 9] = v + 2; triangles[t + 10] = v + 4; triangles[t + 11] = v + 5;
        }

        mesh.vertices = vertices;
        mesh.triangles = triangles;
        mesh.colors = colors;
        mesh.RecalculateNormals();
        mesh.RecalculateBounds();

        var child = new GameObject("CrowdRing");
        child.transform.SetParent(transform, worldPositionStays: false);
        child.transform.position = RingCenter;

        var mf = child.AddComponent<MeshFilter>();
        mf.sharedMesh = mesh;

        var mr = child.AddComponent<MeshRenderer>();
        var shader = Shader.Find("Universal Render Pipeline/Unlit")
                  ?? Shader.Find("Unlit/Color")
                  ?? Shader.Find("Sprites/Default");
        var mat = new Material(shader);
        mat.color = Color.white;
        // Render both sides — winding may invert depending on shader pipeline,
        // and at no measurable cost we avoid the whole inside/outside problem.
        if (mat.HasProperty("_Cull")) mat.SetFloat("_Cull", 0f); // CullMode.Off
        mr.sharedMaterial = mat;
        mr.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
        mr.receiveShadows = false;
    }

    /// <summary>
    /// Scatter inward-facing colored quads through the upper portion of the
    /// ring as phone screens / camera flashes / safety lighting. Multiple
    /// colors and per-instance sizing so the eye reads them as crowd, not
    /// a regular pattern.
    /// </summary>
    private void ScatterAudienceSpecks()
    {
        var shader = Shader.Find("Universal Render Pipeline/Unlit")
                  ?? Shader.Find("Unlit/Color")
                  ?? Shader.Find("Sprites/Default");

        // One material per color so draw calls batch; the speck-color array
        // is small (4 entries) so this is fine.
        var materials = new Material[SpeckColors.Length];
        for (int i = 0; i < SpeckColors.Length; i++)
        {
            materials[i] = new Material(shader) { color = SpeckColors[i] };
            // Ensure both faces draw — a speck might end up facing slightly
            // outward depending on LookAt corner cases.
            if (materials[i].HasProperty("_Cull")) materials[i].SetFloat("_Cull", 0f);
        }

        var parent = new GameObject("AudienceSpecks");
        parent.transform.SetParent(transform, worldPositionStays: false);
        parent.transform.position = RingCenter;

        for (int i = 0; i < SpeckCount; i++)
        {
            float angle = Random.Range(0f, Mathf.PI * 2f);
            float heightT = Random.Range(SpeckHeightMin, SpeckHeightMax);
            // Sit just inside the ring radius so they're not occluded by it.
            float r = Radius - 0.2f;
            var pos = new Vector3(Mathf.Cos(angle) * r, heightT * PanelHeight, Mathf.Sin(angle) * r);

            var speck = GameObject.CreatePrimitive(PrimitiveType.Quad);
            var col = speck.GetComponent<Collider>();
            if (col != null) Destroy(col);

            speck.name = "Speck";
            speck.transform.SetParent(parent.transform, worldPositionStays: false);
            speck.transform.localPosition = pos;
            speck.transform.LookAt(RingCenter + Vector3.up * heightT * PanelHeight);
            float size = Random.Range(SpeckSizeMin, SpeckSizeMax);
            speck.transform.localScale = new Vector3(size, size, 1f);

            var rend = speck.GetComponent<MeshRenderer>();
            rend.sharedMaterial = materials[Random.Range(0, materials.Length)];
            rend.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            rend.receiveShadows = false;
        }
    }
}
