# Contributing to Studio Mobile

Thanks for taking the time to help. This document is deliberately strict about one thing only:
**the project must stay honest.** Everything else is open to discussion in issues.

Studio Mobile is an unofficial Android companion for Roblox creators: a project manager plus a
growing on-device 3D engine. It is built to be useful on a phone, to never pretend a cloud action
succeeded, and to never claim a feature works when only part of it does.

---

## 1. Ways to contribute

- **Bug reports** — include device, Android version, exact steps, expected vs actual result, and the
  full error text. Screenshots or screen recordings must be real captures of the app.
- **Feature work** — engine features, project management improvements, accessibility, performance.
- **Tests** — unit tests for engine/core code, device QA flows in `tests/`, regression captures.
- **Docs** — this repository treats documentation as part of the product: ROADMAP accurate, limits
  listed honestly.
- **Translation / UX review** — clearer wording for the API key guide and error messages.

Open an issue before starting anything large (new engine subsystem, dependency, format support).
Small fixes can go straight to a pull request.

---

## 2. Ground rules (non-negotiable)

1. **No fabricated results.** Do not add screenshots, logs, QA passes or "verified" claims for
   anything you did not actually run. If Roblox rejected a request, say so. If a device test was
   not possible, write "not verified".
2. **No credential leakage.** Never commit API keys, keystores, tokens, personal place IDs of other
   people, or session cookies. The app itself must keep keys encrypted via `CredentialVault`.
3. **No undisclosed data collection.** No analytics, telemetry, tracking or third-party SDKs that
   phone home. The only network traffic is to documented Roblox endpoints the user explicitly
   triggered.
4. **Respect Roblox.** Only implement features that work with official, documented APIs and the
   user's own authorized content. No scraping, no bypassing ownership checks, no session-cookie
   impersonation, no pretending to be desktop Studio.
5. **Offline-first.** The app must stay usable without network for local project operations; the
   editor must never require connectivity.

---

## 3. AI-assisted contributions

**Policy:** AI tools may be used to write code, docs and tests. A human is always accountable.

Requirements for any PR that used AI assistance (Copilot, ChatGPT, Claude, Codebuff, local models,
agentic tools — anything):

1. **Disclose it.** State which tool was used and for what (e.g. "decoder boilerplate drafted with
   an AI agent, reviewed and corrected by hand").
2. **Human review is real review.** You must be able to explain every changed line, why it is
   correct, and what you tested. "The model wrote it" is not an explanation.
3. **Regenerate quality, don't dump quality.** AI output commonly brings: invented APIs, silently
   swallowed exceptions, fake claims in comments/docs, tests that assert nothing, copied code with
   incompatible licenses. Remove all of it before opening the PR.
4. **The AI Policy file is binding.** See [docs/AI-POLICY.md](docs/AI-POLICY.md) for the detailed
   checklist reviewers will apply.
5. **No AI-spam PRs.** PRs that are large, unexplained, or clearly unreviewed output will be closed
   with a request to resubmit a smaller, tested change.

If you use an AI agent, it is your responsibility to keep it inside these rules — including making
it run the test suite, not just write code.

---

## 4. Development setup

Requirements: JDK 17, Android SDK platform 35, build-tools 35.0.0, an emulator or Android 8+ device.

```bash
git clone https://github.com/GoyTeam/StudioMobile.git
cd StudioMobile
export ANDROID_HOME=/path/to/android-sdk

# verify vendored jars first (CI does this too)
(cd app/libs && sha256sum -c SHA256SUMS)

./gradlew testDebugUnitTest     # engine/core unit tests — must pass
./gradlew assembleDebug         # produces app/build/outputs/apk/debug/app-debug.apk
```

Device QA harness (optional, requires adb + device with storage permission):

```bash
./tests/run-project-qa.sh       # creates/removes clearly labelled QA projects
./tests/run-native-qa.sh        # instrumentation + captures into screenshots/
```

QA scripts never publish to Roblox. They may create labeled local projects and delete them again.

### Code conventions

- Java 8 source level, package `dev.falix.studiomobile`, no Kotlin in `app/` yet.
- **No XML layouts and no appcompat/material dependencies.** UI is built with native views.
- All icons come from `StudioIcon` (vector paths). Do not add bitmap assets or Roblox artwork.
- No new runtime dependencies without an accepted issue explaining why the standard library or
  existing vendored jars are insufficient. Vendored jars must be hash-pinned in
  `app/libs/SHA256SUMS` with a license note in `licenses/`.
- Keep the compact, pragmatic style already used in the codebase; prefer small helper methods in
  the activity/page classes over large frameworks.
- UI sizes should stay usable on a 5" phone in landscape: the app intentionally ships a compact
  typography scale (`compactUi` preference) — don't hardcode large paddings.
- Every user-visible string must be factual: no invented Roblox features, no fake progress.

### Engine-specific notes

- `Instance` stores Roblox property names exactly (`size`, `CFrame`, `Color3uint8`, …) so that
  encode/decode round-trips are faithful. New property support must round-trip too: decode → edit →
  `PlaceWriter` output must still be a valid place.
- Unmodeled data must never be silently dropped. XML property types we do not model are captured
  verbatim; binary types that cannot be skipped must abort with a clear message.
- Rendering, collision and painting code runs on the GL thread; the data model is edited on the UI
  thread. Use the existing `synchronized (scene)` + `version` + `refreshCache()` pattern; do not
  touch the tree from the renderer.

---

## 5. Pull request checklist

Copy this into your PR description and fill it in honestly:

```markdown
## Goal
What problem does this solve? Link the issue.

## Design
How it works, in a few sentences. Why this approach over alternatives.

## Changes
- file-by-file list of meaningful changes

## Evidence
- [ ] `./gradlew testDebugUnitTest` passes (paste summary)
- [ ] `./gradlew assembleDebug` passes (paste summary)
- [ ] Device/manual check: what was tapped, on which device/API, what was observed
- [ ] Screenshots/logs are real captures (not mockups)

## AI assistance
Tool(s) used and how; what a human verified afterwards. Write "none" if not used.

## Not verified / risks
Anything untested, platform-specific, or potentially breaking.

## Follow-ups
Known gaps you intentionally left for another PR.
```

Reviewers check exactly the "Evidence" and "Not verified" sections. Missing evidence means the PR
will be asked to update before review continues.

## 6. Commit style

- One logical change per commit; imperative subject ("Add LZ4 reader for binary places").
- Reference issues (`#12`) when applicable.
- Do not commit generated build output (`build/`, `app/build/`, `.gradle/`), keystores, or APKs.

## 7. Review and merge

- Maintainers review for: correctness, honesty of claims, test coverage, dependency/architecture
  fit, and Roblox policy compliance.
- A change that cannot be verified locally (cloud calls, desktop-Studio loading) must say so in the
  PR and in the docs; that is acceptable — pretending it was verified is not.
- CI must be green (build + unit tests + library hash verification). The GoyTeam APK artifact is
  produced on the same run.

Thanks for building this carefully. A smaller honest PR beats a large impressive one that lies.
