# Studio Mobile — Unofficial · 0.2.0

A **native Android Java** first-layer project manager inspired by the supplied Roblox Studio screenshot. This is not official Roblox Studio, is not affiliated with Roblox, and does **not** include a game engine, a 3D editor, publishing, project creation, or real-time collaboration.

## Deliverables

- `StudioMobile-0.2.0-test.apk`: test-signed sideload build, not a production/store release.
- Complete source: Android Studio/Gradle project plus a SDK build script using the bundled, checksum-pinned networking runtime.
- App-only screenshots captured from the actual laid-out native Android views. Emulator-system windows are excluded. Local workspace screenshots are **not signed in**; no invented creator identity, sample games, templates, or cloud projects are shipped.
- Test sources and a QA report with explicit verification limits.

## Implemented

- Sensor-landscape orientation (both landscape directions).
- First-launch storage setup; all-files settings on Android 11+, legacy runtime storage on Android 8–10. Folder picker alternative with persisted grants. All-files access is a sideload requirement chosen by the requester, **not** claimed to meet Google Play policy.
- Network declared in manifest; Android has no runtime network permission popup. Optional Android 13+ notification permission. Actual import-completion notifications.
- Real API-key introspection at `POST https://apis.roblox.com/api-keys/v1/introspect`. Only an active, non-expired key with an identifiable `authorizedUserId` creates a connected session.
- Real Roblox user name/display name and avatar requests. Unavailable avatars are not replaced with fake people.
- AES-GCM encryption using Android Keystore; no plaintext credential persistence, app backend, analytics, cookies, or `.ROBLOSECURITY` collection. Backups disabled and credential screens protected with `FLAG_SECURE`.
- Encrypted connection persistence; the saved key is revalidated against Roblox on restart.
- Account menu: browser profile link (Chrome preferred, installed browser fallback), Add New Account / Coming soon, and Logout. Logout deletes encrypted key material and session state, preserving local place files. It does **not** revoke the key server-side.
- Home with real local recents and the 0.2.0 changelog.
- Projects lists actual `.rbxl` / `.rbxlx` files; full-access mode scans Documents (up to depth 8 / 2,000 files), chosen-folder mode scans that folder. Scanning is intentionally bounded. Files over 100 MiB cannot be imported in this layer.
- Local-file import through the document picker. No file conversion or pretend editor. Recent projects are files whose details you inspected in this manager.
- Projects FAB explicitly explains that project creation comes later.
- Shared with me clearly marked Coming soon.
- Import icon near the profile opens Roblox experience discovery with real thumbnails/names and known visibility or **Unknown**.
- Paginated public, user-owned experience discovery through the public Games API. Tries explicit universe IDs in key scopes and manually added universe IDs via Open Cloud. Manual IDs are verified and stored per account.
- Place chooser, with paginated place listing and manual-place-ID fallback when listing is unavailable.
- Authenticated place download via Asset Delivery, using **`legacy-asset:manage`**. Downloads are saved to `Documents/StudioMobile` or the selected project folder. Partial files are discarded on failure; imports never overwrite existing files.
- Keys go only to `apis.roblox.com`. Asset content follows only HTTPS redirects on Roblox/Roblox CDN domains, without forwarding the key. Maximum import size is 100 MiB. Binary/XML place signatures are checked; this is **not a complete Roblox-format parser**.
- Full-screen, in-app API key guide checked against published Roblox documentation on October 10, 2026, with official links.

## Important API boundaries

**An API key is tool authorization, not a Roblox website login.** The connected account is identified from Roblox's introspection response, not from a guessed user ID.

**This app does not claim to list every private or group experience.** Public listings do not become private listings when you supply an API key. The documented authenticated legacy `develop.roblox.com/v1/user/universes` route uses cookie authentication; this build deliberately does not request or store Roblox session cookies. Explicitly scoped/manual IDs can retrieve accessible metadata, but wildcard keys are not a discovery mechanism. A failed listing shows an error rather than a fabricated empty success.

**A download is an individual place file**, not a whole experience folder or every external asset dependency. Roblox decides whether the account can download/edit the place. IDs never bypass permission checks. An API key also cannot reproduce the Roblox Studio runtime on Android.

**OAuth is not implemented or falsely advertised.** Roblox supports authorization code + PKCE, but a public app needs its own registered application, client ID, allowed redirect, and deployment/review decisions. No client secret should be embedded in an APK. Configure that separately before adding a real account-login option.

## API-key setup

1. Open `https://create.roblox.com/dashboard/credentials?activeTab=ApiKeysTab` in your own browser and sign in.
2. Create a user-owned key, name it for this app, and select only needed APIs/operations.
3. For place downloads, enable the legacy Asset Delivery permission **`legacy-asset:manage`**. This is a broader Manage permission, not a fictional read-only download permission. Roblox still enforces place access.
4. Apply experience restrictions where supported. Not every API scope supports resource restrictions.
5. Optional expiry/IP restrictions should match your threat model. Changing mobile IP addresses can cause authentication rejection; do not broaden permissions blindly to fix a failure.
6. Save & Generate key; paste it directly into the app. Never share it in chat, screenshots, or a repository.
7. For future features, the in-app Optional scopes section and API-PERMISSIONS.md list exact systems, operations, scopes and caveats for publishing, assets, instances, place/universe metadata, Creator Store and OAuth. These permissions do not implement those features; do not grant unrelated writes to a download-only key.

Key introspection and unauthenticated public-profile/listing calls do not require a data-store/publishing permission. This is not a recommendation to give the key every possible scope. Consult the official endpoint reference for changing permission requirements.

## Install

Android 8.0+ (API 26). Enable **Install unknown apps** for the app you use to open the APK, then install the APK. This is an early test build, not a Play Store package or a security-audited production app. Start with a dedicated, minimally privileged test key.

On first launch, Android may take you to all-files settings. If you decline, select a folder instead. Notifications are optional. Without project-folder permission, browsing the app still works, but reading/saving external place files does not.

## Build with Android Studio

Open this folder using Android Studio with JDK 17. SDK platform 35 and build tools 35.0.0 are used. Gradle wrapper is 8.11.1 and Android Gradle Plugin is 8.9.2. Pinned OkHttp/Okio/Kotlin runtime JARs are bundled in app/libs and used by both build paths; see DEPENDENCIES.md.

```sh
./gradlew assembleDebug
```

The Gradle route is provided for development; the delivered APK was compiled with the equivalent SDK-only route below.

## Reproduce the delivered SDK-only build

Install Google's Android command-line tools; accept the SDK licenses yourself, then:

```sh
sdkmanager 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools'
export JAVA_HOME=/path/to/jdk17
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME=/path/to/android-sdk
./build-apk.sh
```

Output: `build/StudioMobile-0.2.0-test.apk`.

The script creates a local test keystore at `$HOME/.android/studio-mobile-test.keystore` if absent. The conventional test password is in the script; this is **not a release-signing setup**. Use a private production signing process for any distribution. The private build keystore is not included in the source archive; rebuilding with a new key will require uninstalling this test build before installing that differently signed build.

## Native QA

Run on an emulator/test device with Android SDK tooling available:

```sh
export ANDROID_SERIAL=emulator-5554
./tests/run-native-qa.sh
# Light/dark redesign, guide, network rejection and motion checks:
./tests/run-redesign-qa.sh
```

The separate QA APK is not shipped inside the main app. It uses explicitly labeled local fixtures to test Keystore, file signatures/imports/scanning, and logout cleanup. Fixtures are removed before final screenshots. QA captures actual native app views (not mockups) and temporarily clears screenshot protection to capture an **empty** key screen; real credential screens in the delivered app remain protected.

Successful account login and authorized place downloads still require a real key/experience test. Passing invalid-key and fixture tests is **not** a claim that live-key authentication or every Roblox permission scenario is verified. Read the accompanying QA report for the exact device coverage and gaps.

## Source map

- `MainActivity.java`: native views, navigation, permissions, dialogs, account lifecycle.
- `RobloxApi.java`: HTTP client, real key introspection, public discovery, metadata and place downloads.
- `CredentialVault.java`: Keystore-backed credential encryption/deletion.
- `ProjectStore.java`: SAF / Documents access, bounded scans and atomic local imports.
- `StudioIcon.java`: native vector icon primitives.
- `GuideUi.java`: step-based required/optional scope guide.
- `UiMotion.java`: native transitions and reduced-motion handling.
- `NetworkRuntime.java`: bundled shared OkHttp transport.
- `tests/NativeQa.java` / `RedesignQa.java`: test-only integration checks and screenshots.

## Official references (checked October 10, 2026)

- Keys / introspection: https://create.roblox.com/docs/cloud/auth/api-keys
- Universe reference: https://create.roblox.com/docs/cloud/reference/features/universes
- Legacy asset delivery authentication / scope: https://devforum.roblox.com/t/creator-action-required-new-asset-delivery-api-endpoints-for-community-tools/3574403
- OAuth / PKCE: https://create.roblox.com/docs/cloud/auth/oauth2-overview
- Android all-files access: https://developer.android.com/training/data-storage/manage-all-files
- Android folder access: https://developer.android.com/training/data-storage/shared/documents-files

Roblox API availability, key eligibility, policies and dashboard labels may change. Do not treat the guide's date as a guarantee of future API behavior.

## 0.2.0 UI refresh

Home keeps its approved layout. Other screens use rounded cards/fields/actions, working project search and All/On device/Roblox filters. Appearance settings provide Light, Dark, or Follow device, persisted between launches, plus Reduce motion. Native transitions and press feedback honor disabled system animations. The guide is a four-section landscape reader with fixed navigation/footer and exact required/optional scope cards; see API-PERMISSIONS.md. The centered blue S on a rounded black tile is provided as an adaptive launcher icon, with a separate monochrome notification glyph.

Own app pages, guide, inputs, appearance sheet and dialogs are themed. Android-owned permission/settings/file-picker screens follow the device theme; the app does not attempt to recolor those system screens. This remains a test build, not a full Studio engine or a security-audited production release.
