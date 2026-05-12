# Resources

Files in this directory are bundled into the build and loadable at runtime via
`Resources.Load<T>(name)`. They survive shader/asset stripping that would
otherwise remove unreferenced assets.

## Expected drop-ins

| Filename | Purpose | Used by |
|---|---|---|
| `StadiumCrowd.png` (or `.jpg`) | Tileable stadium-stand / crowd texture wrapped around the cylindrical crowd ring | `CrowdBackdrop.cs` |

If `StadiumCrowd` is missing, `CrowdBackdrop` logs a warning and falls back
to a flat tinted ring so the absence is unambiguous in-game.

## Where to find a stadium texture (free / CC0)

- **[AmbientCG](https://ambientcg.com)** — CC0, high quality. Search "stadium",
  "tribune", or "concrete" for materials suitable for crowd backdrops.
- **[Poly Haven](https://polyhaven.com)** — CC0, polished. Search "stadium".
- **[OpenGameArt](https://opengameart.org)** — mixed licenses, check each asset.
- **[Sketchfab](https://sketchfab.com)** — filter Downloadable + CC license, search
  "stadium crowd". Extract textures from a 3D model if needed.

### Picking the right texture

What works best:
- **Tileable horizontally** so the seam at U=1.0 doesn't show
- **Aspect ratio close to 2:1** (it wraps the ring once at TilingU=1)
- **Mid-distance view of seating with crowd** (not extreme close-ups of seats,
  not aerial shots of empty stadiums)
- **1024×512 or 2048×1024**. Larger is wasted because mip filtering kicks in
  at distance; smaller looks pixelated.

The script applies wrapMode=Repeat and tiles the texture 2× around the ring
(see `TilingU` in CrowdBackdrop.cs), so a vertically-tall narrow texture
works fine — it'll repeat twice around.
