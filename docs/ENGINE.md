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
3. **Binary depth** — ZSTD chunk support, actually decoding (not just skipping) sequences, UDim2
   and Font, resolving `Ref`/`UniqueId`, and physics blobs; add fixtures saved by real Studio.
4. **Instrumented tests** — snapshot the rendered AABB list for a fixture place and compare
   against expected geometry, on an emulator, in CI.

## Testing

`app/src/test/java/.../EngineCoreTest.java` covers LZ4 (literals and back-references), the binary
cursor together with the planar / zig-zag / bit-rotation array transforms, the 24 built-in CFrame
rotations, an end-to-end decode of a synthetic binary `.rbxl` (header, `INST`/`PROP`/`PRNT`/`END`,
hierarchy, size and colour), CFrame rotation round-trip, ray/AABB behaviour, character collision
landing on a floor, XML place decoding of a generated baseplate, decode→write→decode round-tripping,
and verbatim preservation of unmodeled properties (`Content`, `int64`). Run with
`./gradlew testDebugUnitTest`.
Device QA lives in `tests/` (see CONTRIBUTING.md).
