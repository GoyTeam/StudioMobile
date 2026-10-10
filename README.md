# Studio Mobile (Unofficial) 0.4.0 — Roblox Studio, shaped for Android

Native Android app that manages Roblox place projects **and** now opens them in a real on-device
3D engine: Explorer hierarchy, Properties inspector, GL viewport, editing tools, and a playable
character with an on-screen joystick. Landscape, Android 8.0+ (API 26+), no Roblox account
password, no third-party engine runtime.

> Unofficial community project. Not affiliated with, endorsed by, or a product of Roblox Corporation.
> Roblox is a trademark of Roblox Corporation. This tool works with **your own** creator credentials
> and the place files you are authorized to access.

---

## What exists today

### 1. Project manager (0.2 → 0.3 layer, still maintained)

- Local experiences as real folders: `project.yml` (portable YAML manifest) + `places/<uuid>.rbxlx` + `backups/`.
- Create projects from a native form (name, description, devices, baseplate/empty start place,
  start-place name, planned server size, Workspace gravity, streaming flag) — output is a genuine
  Roblox XML data model (`version="4"`).
- Multiple places per project, local start place selection, rename, settings, file replacement with
  backup, protected start-place removal, duplicate, ZIP snapshot export/import, confirmed deletion.
- Roblox Open Cloud integration with an encrypted API key (Android Keystore AES/GCM):
  browse accessible experiences, manual universe IDs, authorized place download, explicit
  **Save** / **Publish** to a linked existing place. Only Roblox's returned version number confirms
  an upload; there is no automatic retry and no fabricated success.
- Safe thumbnail loading from Roblox CDNs with host allow-listing and bounded decoding.

### 2. Engine editor (new in 0.4.0)

Open a place (project place or loose file) → **Edit**:

- **Decoder**: reads Roblox **XML** places (`<roblox version="4">`) and **binary** `.rbxl` chunks
  (`INST` / `PROP` / `PRNT` / `META`, LZ4-block compressed). Unmodeled XML property types are kept
  verbatim so saving does not silently drop content.
- **Explorer**: the real data-model tree — DataModel services (`Workspace`, `Lighting`,
  `ReplicatedStorage`, `ServerScriptService`, `StarterGui`, …) with nested parts, models, folders,
  scripts, lights; expand/collapse, filter, long-press actions (rename, duplicate, delete, focus).
- **Properties inspector**: Name, Position, Size, Y rotation, Color, Transparency, Material,
  Anchored, CanCollide, SpawnLocation Enabled, Workspace Gravity/Streaming. Edits apply live.
- **Viewport**: OpenGL ES 2.0 renderer written for this app (no third-party engine). Parts render
  with their real size, CFrame orientation and color; grid, sky, selection outline, tap-to-select
  ray picking.
- **Tools**: move (0.5/1/4-stud nudges), rotate ±90° around Y, scale 10% steps, insert
  (Part, SpawnLocation, Folder, Model, Script, PointLight), duplicate, delete, focus, undo/redo
  (40 steps).
- **Play mode**: classic blocky avatar, gravity from `Workspace.Gravity`, 16 studs/s walk,
  jump, AABB collision against all collidable parts, third-person follow camera, **joystick** to
  move and a **JUMP** button — playable directly on the phone.
- **Save** writes the Roblox XML data model back into the place file (SAF `content://` and plain
  `file://` both supported) and updates the project manifest hash. Cloud **Save/Publish** stays in
  the project manager where the API key lives.

### Engine limits this beta (honest, not hidden)

- Scripts do not execute yet; no `game.*` runtime. That is the next phase (script layer + assets).
- Unions/MeshParts render and collide as boxes; terrain, textures, decals, particles, animations
  and GUIs are not drawn yet.
- Rotated parts collide as axis-aligned boxes in play mode.
- `.rbxl` files using **ZSTD** post-compression are detected and reported clearly instead of being
  half-parsed: re-save them as `.rbxlx` in desktop Studio, or wait for the ZSTD reader.
- Sound, physics constraints, networking/Team Create and cloud instance-property editing are not
  implemented.

---

## Repository layout

```
app/src/main/java/dev/falix/studiomobile/
  MainActivity.java        project manager shell: navigation, home, projects, cloud import, setup
  ProjectUi.java           creation + per-place management flows
  ManagedProjects.java     SAF/filesystem project repository (YAML manifest, ZIP, backups, hashes)
  ProjectStore.java        place-file scanning/import on device storage (SAF + Documents)
  RobloxApi.java           Open Cloud REST calls (introspection, listing, downloads, images)
  PublishService.java      one-shot Save/Publish upload (no automatic retry)
  CredentialVault.java     Android Keystore AES/GCM credential storage
  NetworkRuntime.java      shared OkHttp transport
  GuideUi.java             source-linked API key / permissions guide
  PlaceTemplate.java       generates the XML starter place for new projects
  StudioIcon.java          all vector icons (no image assets, no borrowed identity art)
  UiMotion.java            shared motion behaviour (honors reduce-motion + system setting)

  Instance.java            engine scene node: typed Roblox properties, CFrame math, bounds
  PlaceDecoder.java        rbxlx (XML) + rbxl (binary LZ4 chunks) decoder
  PlaceWriter.java         rbxlx serializer (round-trips unmodeled properties)
  EngineScene.java         editor/play state: selection, camera, undo, picking, character physics
  EngineRenderer.java      OpenGL ES 2.0 viewport renderer
  EngineView.java          GLSurfaceView with orbit/pan/zoom and tap picking
  JoystickView.java        analog movement stick
  EngineActivity.java      editor UI: Explorer, Properties, tools, play mode, saving

app/src/test/java/…        JVM tests for the engine core (LZ4, cursor, CFrame, ray, collision)
tests/                     on-device QA harness (adb) and its reports
docs/ENGINE.md             engine internals, format coverage, extension points
docs/AI-POLICY.md          the AI contribution policy in full detail
```

## Build

Requirements: JDK 17, Android SDK platform 35, build-tools 35.0.0.

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug          # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # engine core tests
```

`app/libs/*.jar` (OkHttp/Okio/Kotlin stdlib/SnakeYAML/annotations) are vendored and hash-pinned;
`sha256sum -c app/libs/SHA256SUMS` verifies them before compilation. `ANDROID_HOME` must point at
an SDK where `platforms/android-35` and `build-tools/35.0.0` are installed.

### CI builds (GoyTeam)

Every push, pull request and manual run builds and tests the app in GitHub Actions
(`.github/workflows/goyteam-apk.yml`) and uploads a ready-to-install artifact named
**`StudioMobile-GoyTeam-v<version>`** with a SHA-256 checksum. Tagged commits (`v*`) also publish a
GitHub Release with the APK attached. CI artifacts are signed with the CI debug keystore; uninstall
an older build before installing a new one unless you ship your own keystore.

## Install notes

This project ships test-signed sideload builds. Keep your own keystore if you want stable updates.
Back up `.rbxl`/`.rbxlx` files (including `Documents/StudioMobile`) before switching builds, and
never share your creator API key. On Android 11+ the app can use all-files access or a user-chosen
SAF folder; on Android 13+ notifications are optional.

## Contributing

Contributions are welcome — human-written or AI-assisted, as long as a human stands behind every
line. Read **[CONTRIBUTING.md](CONTRIBUTING.md)** and **[docs/AI-POLICY.md](docs/AI-POLICY.md)**.

Short version:

1. **Anyone may write the code — a human must review it.** AI tools are allowed. The person opening
   the pull request is accountable: they must understand the change, run it, and describe it in
   detail (what changed, why, how it was verified, what is still unverified).
2. **Never fabricate success.** No screenshots or logs of things that were not actually run. Report
   failures and unknowns plainly — this repository documents its limitations on purpose.
3. **Keep the offline-first, credential-safe architecture.** New runtime dependencies need an issue
   first; test-only dependencies are easier to accept.
4. **Prove the change.** Engine/core changes need unit tests (`./gradlew testDebugUnitTest`) and,
   where UI/device behaviour is involved, an on-device check. Include the exact commands you ran.
5. **Describe in detail.** PRs use the template in CONTRIBUTING.md: goal, design, risks, test
   evidence, AI assistance disclosure, follow-ups.

## Roadmap

- **Next: scripting layer** — Luau-ish script runtime, editing Script/ModuleScript/LocalScript
  contents in Explorer/Properties, output console, event connections.
- **Assets**: asset browser/search, meshes/textures/decal rendering, Upload via Cloud `asset` APIs.
- **Engine depth**: ZSTD binary reading, terrain, GUIs, materials rendering, physics constraints.
- **Collaboration**: real Team Create presence only if an official supported API exists — never
  simulated.
- **Quality**: instrumented tests in CI (emulator), regression screenshots, perf budget on low-end
  devices.

## Sources of truth

- Project format: [PROJECT-FORMAT.md](PROJECT-FORMAT.md)
- API scopes: [API-PERMISSIONS.md](API-PERMISSIONS.md)
- Engine internals: [docs/ENGINE.md](docs/ENGINE.md)
- QA history: `QA-report*.txt`, `redesign-report.txt`, `build-apk.sh`

## License

See [LICENSE](LICENSE).
