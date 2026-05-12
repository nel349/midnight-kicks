# Resources

Files in this directory are bundled into the build and loadable at runtime via
`Resources.Load<T>(name)`. They survive shader/asset stripping that would
otherwise remove unreferenced assets.

## Expected drop-ins

| Filename | Purpose | Used by | Fallback if missing |
|---|---|---|---|
| `StadiumCrowd.png` (or `.jpg`) | Tileable stadium-stand / crowd texture wrapped around the cylindrical crowd ring | `CrowdBackdrop.cs` | Flat tinted ring (absence is obvious) |
| `GrassField.png` (or `.jpg`) | Tileable grass photo for the pitch surface | `GrassPitch.cs` | Procedural mowing-stripes + Perlin grain |

Both are optional — scripts log which source they used at scene load
(`[GrassPitch] ... source: Resources/GrassField` vs `... source: procedural fallback`).

## Where to find free / CC0 textures

| Source | Best for |
|---|---|
| **[AmbientCG](https://ambientcg.com)** | CC0, high quality. Search "stadium" / "tribune" for crowd; "grass" / "lawn" for pitch |
| **[Poly Haven](https://polyhaven.com)** | CC0, polished. Search "stadium", "grass" |
| **[OpenGameArt](https://opengameart.org)** | Mixed licenses — check per asset |
| **[Sketchfab](https://sketchfab.com)** | Filter Downloadable + CC. Search "stadium crowd" — extract textures from a 3D model if needed |
| **[TextureCan](https://www.texturecan.com)** / **[FreePBR](https://freepbr.com)** | PBR-style grass packs (free tier) |

### Picking the StadiumCrowd texture

- **Tileable horizontally** so the seam at U=1.0 doesn't show
- **Aspect ratio ~2:1**, **1024×512 or 2048×1024**
- **Mid-distance view of seating with crowd** (not extreme close-ups, not aerial shots of empty stadiums)
- The script tiles 2× around the ring (see `TilingU` in `CrowdBackdrop.cs`), so a vertically-tall narrow texture works fine — it'll repeat twice

### Picking the GrassField texture

- **Seamlessly tileable** in both U and V (most pure-grass textures are)
- **Top-down or slightly-angled view of real grass**, not artistic
- **1024×1024 or 2048×2048**
- A "stadium pitch" or "soccer field" texture with mowing stripes baked in is great if you can find one; otherwise plain grass + my procedural would blend stripes back in if you set the script up that way
- The script tiles 6× across the 50×40m field (see `Tiling` in `GrassPitch.cs`); adjust if blades look too big/small
