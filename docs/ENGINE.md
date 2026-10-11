# Engine internals (0.5.0 beta)

The engine is the layer that turns a real Roblox place file into an editable, playable scene on the
phone. It is written in plain Java with the Android framework only (OpenGL ES 2.0 via
`GLSurfaceView`), because every megabyte and every dependency matters on mobile.

```
 file (.rbxlx / .rbxl)
        │
   PlaceDecoder ─────────────► Instance tree (Roblox data model)
        │                              │
        │                              ├─ EngineScene  (selection, undo, camera, physics)
        │                              │        │
        │                              │        ├─ EngineRenderer (GLES2 viewport + post pass)
        │                              │        ├─ EngineLighting (Lighting/Sky/Atmosphere/post effects)
        │                              │        ├─ EngineMaterial (Enum.Material shading table)
        │                              │        └─ EngineView     (touch: orbit/pan/zoom/pick)
        │                              │
   PlaceWriter ◄──────────── save ─────┘
        │
 file (rbxlx) → project manifest hash + optional cloud Save/Publish
```

## Data model — `Instance.java`

One class for the whole model. Key decisions:

- **Roblox property names are kept exactly** (`size`, `CFrame`, `Color3uint8`, `Transparency`,
  `Anchored`, `Material`, `Gravity`, …). No translation layer means decode → edit → serialize is
  faithful and cheap.
- Value containers: `String`, `Boolean`, `Integer`, `Long`, `Float`, `Double`, `Instance.Vector3`,
  `double[12]` for CFrames, `int[]` for `Color3`, `PlaceWriter.Raw` for verbatim XML fragments.
- `Instance.tags` remembers the original XML tag of a property (`token`, `int64`, `Color3`) so
  re-serialization keeps the same type instead of guessing.
- `cframe()` returns a 12-element column-major array `[x,y,z, r00..r22]` and composes parent CFrames
  when the parent is a renderable part (Welds are not simulated; this is display composition).
- `bounds()` returns the world AABB used for ray picking and character collision.

## Decoder — `PlaceDecoder.java`

### XML (`.rbxlx`, version 4)

A streaming SAX pass (works identically on device and in JVM unit tests):

- `<Item class referent>` nests into the tree; `Properties` are read into the instance.
- Modeled types: `string`, `bool`, `int`, `int64`, `float`, `double`, `token`, `Enum`,
  `Color3uint8`, `Color3`, `Vector3`, `CoordinateFrame`/`CFrame`, `ProtectedString`,
  `BinaryString`.
- **Everything else is captured verbatim as raw XML** (e.g. `Content` like `MeshId`, `Ref`,
  `SharedString`, sequences) so saving does not drop properties this beta does not understand.

### Binary (`.rbxl`)

Chunk reader with in-file LZ4 block decompression (`lz4()`). The layout follows the published
Roblox binary model format and was checked against the `rbx-dom` reference implementation:

- **32-byte file header**: `<roblox!` magic, six signature bytes, little-endian `u16` version,
  class count, instance count, and 8 reserved zero bytes.
- **16-byte chunk headers**: 4-byte name, compressed length, uncompressed length, 4 reserved bytes.
  Compression is chosen per chunk from the payload itself: LZ4 block form is decoded here, and ZSTD
  is detected by its `28 B5 2F FD` magic and reported ("save as .rbxlx") rather than half-decoded.
- Chunks: `META` (key/value string pairs), `SSTR` (version, count, then a 16-byte hash plus a string
  per entry), `INST` (class id, class name, object format, count, then the referent array), `PROP`
  (class id, property name, type byte, then one value per instance of that class in INST order; a
  chunk that ends right after the name is ignored, exactly as Roblox does), `PRNT` (version, count,
  child referents, parent referents — a parent of `-1` means a child of the DataModel), and
  `END`/`SIGN` which terminate.
- Numeric values use **structure-of-arrays planes**: each component occupies its own run of `count`
  bytes and every 32/64-bit component is big-endian. `Int32`, `Enum` and referents are zig-zag
  transformed, `Float32` bit patterns are rotated one bit, referents are difference-encoded (each
  value accumulates onto the previous one), and `CFrame` stores a rotation id per value (`0` = nine
  raw floats, otherwise one of the 24 built-in rotations) followed by three position planes.
  `Float64`, `Ray`, `String`/bytecode, sequences and ranges are plain little-endian.
- Fully modeled value types: string, bool, int32, float32, float64, `Enum`, `Color3`, `Vector3`,
  `CFrame`, `Color3uint8`, `Int64`, `SharedString`, and `ProtectedString`/bytecode. Recognised but
  only skipped (bytes consumed, value left unset): UDim, UDim2, Ray, Faces, Axes, BrickColor,
  Vector2, Vector2int16, Quaternion, `Ref`, Vector3int16, NumberSequence, ColorSequence,
  NumberRange, Rect, PhysicalProperties and Font. Unknown type ids produce a warning rather than
  corrupting the tree. Because every chunk is framed independently, a property this beta cannot
  read never desynchronises the rest of the file.

Limits: 100 MiB per place (matching the project manager), 2,000,000 instances per INST chunk guard.

## Writer — `PlaceWriter.java`

Emits `<roblox version="4">` with the same property types it decoded. Unmodeled XML fragments are
re-emitted verbatim. `num()` formats doubles without locale surprises and without trailing noise.
Referents are generated (`RBX…`) when missing so remote/tooling diffing stays stable.

## Scene & physics — `EngineScene.java`

- **Editing**: insert (Part/SpawnLocation/Folder/Model/Script/PointLight), delete (services
  protected), duplicate (deep copy), nudge, ±90° Y rotation, non-uniform scale, property set.
- **Undo/redo**: XML snapshots of the tree (40 steps). Snapshot restore swaps in a freshly parsed
  tree so no dangling references remain.
- **Picking**: ray from the tap through the camera → slab test against world AABBs → nearest part.
- **Play mode**: blocky avatar (`2 × 5 × 2` studs collision box), gravity from `Workspace.Gravity`,
  16 studs/s walk speed, 50 studs/s jump, axis-by-axis AABB resolution with ground detection,
  spawn at `SpawnLocation` when present (fallback: above the highest collidable), respawn if the
  character falls out of the world.
- **Camera**: orbit (yaw/pitch/distance) with pan/zoom; follows the character in play mode.

`version` counts tree changes; `refreshCache()` rebuilds the flat render/collider lists. UI thread
edits under `synchronized`, renderer copies a frame list under the same lock — the GL thread never
walks the live tree.

## Lighting and graphics — `EngineLighting.java` / `EngineMaterial.java`

The viewport implements the Roblox lighting model, not a fixed two-light rig. `EngineLighting.from(root)`
reads the `Lighting` service (plus `Sky`, `Atmosphere`, `Clouds`, the post effects, and every
`PointLight`/`SpotLight`/`SurfaceLight`) straight out of the decoded place, so a place's authored
environment renders in the editor. `EngineScene.refreshCache()` rebuilds it whenever the tree changes.

Property coverage (Roblox name → engine behaviour):

| Property | Behaviour |
| --- | --- |
| `ClockTime`, `TimeOfDay` | Sun hour; `TimeOfDay` is parsed as `HH:MM[:SS]` when `ClockTime` is absent |
| `GeographicLatitude` | Tilts the sun's arc and lowers the noon elevation |
| `Brightness` | Scales the directional term (`sunGain = clamp(Brightness,0,4) × 0.5 × sunUp`) |
| `Ambient`, `OutdoorAmbient` | Ambient term everywhere + outdoors |
| `ColorShift_Top`, `ColorShift_Bottom` | Hue added to sun-facing / sun-away faces and to the sky |
| `EnvironmentDiffuseScale` | Mixes the sky gradient into the ambient term |
| `EnvironmentSpecularScale` | Scales specular highlights |
| `FogColor`, `FogStart`, `FogEnd` | Linear distance fog (default `FogEnd = 100000` disables it) |
| `ExposureCompensation` | `2^exposure` in the post pass |
| `GlobalShadows`, `ShadowSoftness`, `Technology`, `LightingStyle` | Read and reported (no shadow map yet) |
| `Sky` | Sun disc (`SunAngularSize`), stars (`StarCount`), `CelestialBodiesShown`, day/night gradient |
| `Atmosphere` | `Density`/`Offset`/`Haze`/`Glare`/`Decay`/`Color` haze, horizon band and distance tinting |
| `Clouds` (under `Terrain`) | `Cover`/`Density`/`Color` procedural drifting cover |
| `PointLight`/`SpotLight`/`SurfaceLight` | `Color`, `Brightness`, `Range` (capped at 120), `Face`/`Angle` cones, `Enabled`; four shaded per frame |
| `ColorCorrectionEffect` | `Brightness`, `Contrast`, `Saturation`, `TintColor`, `Enabled` |
| `BloomEffect` | `Intensity`, `Size`, `Threshold` |
| `BlurEffect` | `Size` (separable gaussian, up to 56) |
| `SunRaysEffect` | `Intensity`, `Spread` (radial blur from the sun's screen position) |
| `DepthOfFieldEffect`, `ColorGradingEffect` | Parsed and reported; DOF is not rendered yet |
| `Reflectance`, `Material` | Metalness/roughness/emissive/reflectance from `EngineMaterial` |

Sun direction (`sunDirection(clockTime, latitude)`) matches the behaviour Roblox documents: the hour
angle is `(ClockTime − 6) × 15°`, so the sun is on the eastern horizon at 06:00, highest at 12:00 and
on the western horizon at 18:00, below the horizon from 18:00 to 06:00, with the arc leaning toward
`+Z` (south) and its noon height reduced as the latitude grows. Roblox does not publish its exact
ephemeris, so this is the documented approximation; the tests pin the rise/noon/set behaviour.

Four approximations are deliberate and stated in the app (the **Engine limits** dialog) and the README:
no shadow maps (`GlobalShadows` only dims faces away from the sun), no environment probe for
`EnvironmentDiffuseScale` (so `OutdoorAmbient` also follows the sun's height), procedural sky and
clouds instead of Roblox skybox/cloud textures (they are Roblox asset ids this build cannot fetch),
and no `DepthOfFieldEffect`.

## Renderer — `EngineRenderer.java`

One frame, in order:

1. **Sky** — a full-screen quad whose fragment shader rebuilds the view ray from the inverse
   view-projection matrix, then draws the day/night gradient, the sun disc, stars, atmosphere
   haze/glare and drifting cloud noise.
2. **Geometry** — the unit-cube mesh (24 vertices, per-face normals) shaded by the vertex/fragment
   pair: hemisphere ambient, directional sun, `ColorShift` hue, up to four local lights with range
   and cone falloff, Blinn-Phong specular from the material's roughness, emissive materials, and
   linear fog. Per part: model matrix from CFrame × size, colour from `Color3uint8`, transparency
   to alpha; the selection gets a bright 12-line outline.
3. **Post pass** (only while `needsPost()` is true) — the frame renders into an offscreen RGBA
   texture with a depth renderbuffer, then `BlurEffect`, `BloomEffect` (bright pass + two gaussian
   passes) and `SunRaysEffect` build half-resolution textures, and a final composite applies bloom,
   sun rays, exposure and colour correction. If the device cannot build the targets the frame falls
   back to direct rendering and `EngineView.postNote()` reports why.

A GL-thread failure is caught, logged and shown in the editor (`EngineView.Listener.onRendererError`)
instead of killing the process; the same applies to place loading and editor construction in
`EngineActivity`.

Other viewport behaviour: grid at 4-stud spacing over ±256 studs, tinted by the day factor; character
drawn from six boxes (torso/head/arms/legs) rotated by movement yaw and shaded by the same lighting.

## Interaction — `EngineView.java` / `JoystickView.java`

- One finger: orbit (edit) / camera look (play). Two fingers: pan (edit) + pinch zoom. Tap: pick.
- Joystick: analog, forward is "away from camera"; JUMP button queues a jump.

## Saving and cloud sync

`EngineActivity.save()` serializes the tree, writes it through `content://` (SAF) or `file://`
atomically, then updates `sha256` in the project manifest when the place belongs to a managed
project. Cloud Save/Publish is intentionally **not** duplicated here: it stays in the project page,
where the encrypted API key and the explicit confirmation flow live.

## Extension points (next phases)

1. **Scripting** — `Instance` should gain a `Script` source property UI and a small interpreter
   driven by a scheduler in `EngineScene.stop/step`; keep the "no pretend execution" rule: only run
   what is implemented.
2. **Assets** — extend the decoder's raw-capture path into typed `Content` properties; add a
   texture pipeline before adding mesh rendering.
3. **Binary depth** — ZSTD chunk support, actually decoding (not just skipping) sequences, UDim2
   and Font, resolving `Ref`/`UniqueId`, and physics blobs; add fixtures saved by real Studio.
4. **Graphics depth** — shadow maps for `GlobalShadows`/`ShadowSoftness`, a skybox texture loader
   for `Sky`, `SurfaceAppearance`/`MaterialVariant` maps, `DepthOfFieldEffect`, and terrain.
5. **Instrumented tests** — snapshot the rendered AABB list for a fixture place and compare
   against expected geometry, on an emulator, in CI.

## Testing

`app/src/test/java/.../EngineCoreTest.java` covers LZ4 (literals and back-references), the binary
cursor together with the planar / zig-zag / bit-rotation array transforms, the 24 built-in CFrame
rotations, an end-to-end decode of a synthetic binary `.rbxl` (header, `INST`/`PROP`/`PRNT`/`END`,
hierarchy, size and colour), CFrame rotation round-trip, ray/AABB behaviour, character collision
landing on a floor, XML place decoding of a generated baseplate, decode→write→decode round-tripping,
verbatim preservation of unmodeled properties (`Content`, `int64`), and the lighting pipeline: the
sun's rise/noon/set arc and latitude tilt, Roblox defaults with no `Lighting` service, Lighting
properties reaching the ambient/fog/post-effect model, Sky/Atmosphere/Clouds decoding, neutral
effects not costing a framebuffer pass, local lights resolving to world space (with the four-light
cap), the material table, and the generated fixture place carrying `ClockTime 14` into the engine.
Run with `./gradlew testDebugUnitTest`.

The GLSL ES 1.00 shaders are checked with `glslangValidator` during development; they are plain string
constants in `EngineRenderer`, so no asset or dependency is needed at runtime.
Device QA lives in `tests/` (see CONTRIBUTING.md).
