# Studio Mobile (Unofficial) 0.3.0

Native Android project manager, not the Roblox engine. Landscape; Android 8+; test-signed sideload build. Source includes the real networking and safe YAML runtime JARs used in the APK.

## New in this layer
- Smaller default UI typography/sidebar/spacing; Appearance → UI size toggles Compact / Comfortable.
- Less repetitive explanatory copy; Home keeps its structure, with the new changelog and real project recents.
- Thumbnail lookup for manual/scoped universe IDs; bounded Pending retry, place-icon fallback; safe HTTPS CDN redirects, bounded decoding and visible missing-image status rather than an unexplained blank tile.
- Working New project sidebar button and + FAB, a native creation form, fixed visible Create action, experience name/description/current-user attribution, device targets, baseplate/empty templates, start-place name, planned server size, Workspace gravity and streaming.
- Real folders, real `.rbxlx` files and a safe portable YAML manifest. No keys in project metadata.
- Multiple-place management, local start selection, renaming/settings, file replacement with backup, protected start-place deletion, duplicate, snapshot ZIP export/import and confirmed local deletion.
- Explicit cloud target verification and actual Save / Publish requests. Only a positive Roblox version number confirms success; requests never automatically retry an upload.

See PROJECT-FORMAT.md for the exact schema, storage locations, supported operations and cloud boundaries. API-PERMISSIONS.md documents scopes. Older README.md includes the underlying authentication/storage design.

## Install / migration — IMPORTANT

The original 0.2.0 private signing keystore was not included in the source ZIP and is not available in this fresh build environment. This 0.3.0 APK uses a **new test certificate**, so Android cannot install it over 0.2.0 as a normal update.

**Before uninstalling 0.2.0:** back up your `.rbxl` / `.rbxlx` files, including Documents/StudioMobile and any chosen provider folder. Keep your creator key available privately; do not send it in chat. Then uninstall 0.2.0 and install this APK. Re-grant storage/folder access and reconnect your key. Uninstalling clears app credentials, settings and recent history; external Documents files normally remain, but make the backup first.

Do not interpret a test signer as a production signing process. For future continuity keep the new build keystore privately; rebuilding elsewhere with another generated key will again require reinstalling. No private signing key is included in the distributed source.

## Build

JDK 17, Android platform 35 / build-tools 35.0.0. Open in Android Studio with the bundled Gradle wrapper, or set ANDROID_HOME and run `./build-apk.sh`. Local JAR hashes are verified before compilation.

Tests: `./tests/run-project-qa.sh` on an emulator/test device with storage permission. Tests create clearly labeled QA projects and delete them; they never send a live publication request. A small QA-created sample ZIP can be supplied for inspection, not as evidence of a cloud experience.

## Known boundaries

- No 3D editor, engine runtime, playtest, script execution, teleport authoring or Team Create.
- Baseplate/empty are minimal local templates, not replicas of every official Roblox template.
- Device/server-size plans and experience name/description are **local metadata**, not silently applied to a live experience by the place-upload endpoint.
- Public/private visibility, audience eligibility/compliance and cloud root-place creation remain Roblox dashboard/desktop Studio tasks.
- New cloud universes/additional cloud places are not fabricated through an undocumented endpoint. Create them in desktop Studio, then link existing IDs.
- Your key's scope cannot override Roblox's ownership, account or publishing eligibility checks. A private/moderated/pending image may remain unavailable.
- SAF support is implemented; each document provider can differ. Back up first and test with your provider before trusting a production project.
- Screenshot captures are actual native app views, with system/emulator overlays excluded. Real creator login/download/upload verification and desktop Studio load/playtest are listed separately in QA-report-030.txt.
