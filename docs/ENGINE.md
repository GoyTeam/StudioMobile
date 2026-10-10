# Engine internals (0.4.0 beta)

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
        │                              │        ├─ EngineRenderer (GLES2 viewport)
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

Chunk reader with LZ4 block decompression implemented in-file (`lz4()`):

- Header `<roblox!` + signature bytes + version + class/instance counts.
- `META` (string pairs), `INST` (class id, class name, object format, count, and **interleaved**
  referents: four `u32` ids then four scope bytes), `PROP` (referent, name, type byte, then one
  value per instance of that class in creation order), `PRNT` (child → parent pairs), `SIGN`/`END`
  terminate.
- Value types supported: string, bool, int32, float32, float64, BrickColor, Color3, Vector3, CFrame
  (rotation matrix then position), enum, Color3uint8, int64, ProtectedString and the fixed-size
  skippable geometry types (UDim, UDim2, Ray, Faces, Axes, Vector2, Vector2int16, Quaternion,
  Vector3int16, NumberSequence, ColorSequence, NumberRange, Rect, PhysicalProperties,
  OptionalCFrame). Unknown types abort with a clear message instead of corrupting the tree.
- **ZSTD** post-compression is detected and reported ("save as .rbxlx or wait for the ZSTD reader")
  rather than half-decoded.

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

## Renderer — `EngineRenderer.java`

- One unit-cube mesh (24 vertices, per-face normals) + one line program; ~30 lines of shader.
- Per part: model matrix from CFrame × size, color from `Color3uint8`, transparency to alpha,
  selection gets a bright outline drawn as 12 lines.
- Grid at 4-stud spacing over ±256 studs; sky is the clear color (no fog yet).
- Character drawn from six boxes (torso/head/arms/legs) rotated by movement yaw.

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
3. **Binary depth** — ZSTD chunk support, `PROP` types 0x1F (UniqueId) and physics blobs; add
   fixtures with real Studio-saved files.
4. **Instrumented tests** — snapshot the rendered AABB list for a fixture place and compare
   against expected geometry, on an emulator, in CI.

## Testing

`app/src/test/java/.../EngineCoreTest.java` covers LZ4 (literals and back-references), the binary
cursor, CFrame rotation round-trip, ray/AABB behaviour, character collision landing on a floor,
XML place decoding of a generated baseplate, decode→write→decode round-tripping, and verbatim
preservation of unmodeled properties (`Content`, `int64`). Run with `./gradlew testDebugUnitTest`.
Device QA lives in `tests/` (see CONTRIBUTING.md).
