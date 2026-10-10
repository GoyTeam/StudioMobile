# Dependency foundation · Studio Mobile 0.3.0

## Already bundled in source and in the APK

The app's JSON API path uses **OkHttp 4.12.0**. Its runtime dependencies are vendored as real JAR files in `app/libs`, pinned in `dependency-lock.json` with source URLs and SHA-256 checksums:

- OkHttp 4.12.0: HTTPS REST transport, shared connection pool, timeout control. Its WebSocket API is also included as a future transport foundation; collaboration itself is not implemented.
- Okio JVM 3.6.0: I/O/buffer runtime used by OkHttp, including streaming APIs.
- Kotlin stdlib / common / JDK7 / JDK8 1.8.21: runtime for those libraries (the app remains Java).
- JetBrains annotations 13.0: bundled annotation artifact.

These pinned versions are used and tested for this update, not advertised as the latest release. Revisit security advisories and compatibility when upgrading. Source artifact URLs are in the lockfile. OkHttp project documentation: https://square.github.io/okhttp/

**No dependency is fetched on the user's phone.** The SDK build and Android Studio Gradle build use the same local JARs. No Gradle placeholder entries, missing JARs or empty “future dependency” modules are supplied.

## Android platform foundations already available

- Native Views/Canvas/vector drawables and ValueAnimator/ViewPropertyAnimator: UI, rounded shapes and motion.
- Native light/dark themes, preference persistence, device-following appearance.
- Android Keystore AES-GCM: credential encryption. No custom crypto implementation.
- Storage Access Framework + document providers: chosen folders, persistent grants and import.
- HttpURLConnection: existing binary-download/public-image paths remain bounded and credential-safe.
- Android org.json: response parsing.
- SQLite APIs: available on Android for a future local index; no unused Room database is pretended to exist.
- ExecutorService: existing background work.
- Notification channels / runtime permissions.

## Deliberately not selected blindly

A 3D renderer, Roblox/Luau editing pipeline, durable sync queue, OAuth browser integration and real-time collaboration protocol need concrete requirements and testing before choosing additional packages. Installing every imaginable engine/library would increase size and attack surface without creating those features. No editor or collaboration is implied by the bundled transport.

For a future layer, choose and test the renderer/scene model, publishing safety model, OAuth registration/PKCE redirect and collaboration backend first. The current dependency foundation does not make these unbuilt features “ready” or bypass Roblox's supported API limits.

## Local project manifests

SnakeYAML 2.3 is vendored, checksum-pinned and packaged for real YAML manifests. SafeConstructor limits nesting, aliases, duplicate keys and input size. Only primitive/map/list values are accepted; no YAML object construction or credentials in manifests. See app/libs/dependency-lock.json and licenses/snakeyaml-LICENSE.txt.
