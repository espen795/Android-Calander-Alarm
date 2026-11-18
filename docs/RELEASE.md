# Release Process — Calendar Alarm

This document describes how to cut a release of the Android app and the
server image, and how to operate the release signing key safely.

## 0. The signing key is the app identity — read this first

On Android, **the release signing key *is* the app's identity**. Updates are
only accepted by devices if they are signed with the same key as the installed
app.

- **Lose the key (or leak it) = users must uninstall and reinstall** the app;
  they will not receive in-app updates ever again.
- Therefore: keep the keystore **off** any machine that is not strictly needed,
  store a backup in at least two offline/encrypted locations, and never commit
  it. The repo's `.gitignore` blocks `*.jks`, `*.keystore`, `*.p12`,
  `gradle.properties`, `local.properties` — do not force-add them.
- The app additionally pins the SHA-256 of the release signing certificate
  (`PINNED_SIGNER_SHA256` in `UpdateChecker.kt`) and verifies every downloaded
  APK against it before offering an install. **When you rotate the keystore,
  you must update this pin in the same release.**

## 1. Pre-release (every release)

1. Bump the version in `android/app/build.gradle.kts`:
   - `versionName = "X.Y.Z"` (semver; the git tag must be exactly `vX.Y.Z`)
   - `versionCode = <int>` (strictly increasing integer)
2. Update `CHANGELOG.md` with a `## vX.Y.Z` (or `## [X.Y.Z]`) section — the
   release workflow extracts exactly this section for the GitHub Release notes.
3. Run the hygiene gate locally:
   ```bash
   tools/sanitize-check.sh
   tools/sanitize-check.sh --history
   ```
   Both must print `sanitize-check: CLEAN`.
4. Run the server tests: `cd server && pip install -r requirements.txt -r requirements-dev.txt && python -m pytest -q`
5. Run the Android unit tests: `cd android && cp gradle.properties.template gradle.properties && ./gradlew :app:testDebugUnitTest`
6. Commit and push to `main`; CI (`.github/workflows/ci.yml`) runs gitleaks +
   sanitize-check + server tests + Android build/tests.

## 2. Tag and push → release workflow

```bash
git tag vX.Y.Z
git push origin vX.Y.Z
```

Pushing the tag triggers `.github/workflows/release.yml`, which:

1. **guard** — runs gitleaks + sanitize-check and verifies the tag equals
   `versionName` in `android/app/build.gradle.kts` (fails otherwise).
2. **apk** — decodes the keystore from the `ANDROID_KEYSTORE_BASE64` secret into
   `$RUNNER_TEMP/release.jks` (never into the repo tree), builds
   `:app:assembleRelease`, deletes the keystore and the copied
   `gradle.properties` in the same step, then packages
   `dist/calendar-alarm-vX.Y.Z.apk` + `dist/version.json` (via
   `tools/make_version_json.py`, includes the APK SHA-256) + `dist/SHA256SUMS`.
3. **server-image** — builds `server/` and pushes
   `ghcr.io/<owner>/<repo>-server:vX.Y.Z` and `:latest` to GHCR.
4. **publish** — creates the GitHub Release `vX.Y.Z` with the `dist/` files and
   the CHANGELOG section as notes.

### Required GitHub repository secrets (Settings → Secrets and variables → Actions)

| Secret | How to produce |
|--------|----------------|
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 release.jks` (single line, no newlines) |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_ALIAS` | key alias inside the keystore |
| `ANDROID_KEY_PASSWORD` | key password |

### Optional repository variables (Settings → Variables → Actions)

| Variable | Purpose |
|----------|---------|
| `CALALARM_APPLICATION_ID` | override `applicationId` (default `io.github.calendaralarm`) |
| `CALALARM_API_URL` | default backend URL baked into the app (use your own host, e.g. `https://alarm-api.example.com`) |
| `CALALARM_UPDATE_SERVER_URL` | base URL of the self-hosted update feed (see §3). Empty = in-app updater disabled. |
| `CALALARM_CERT_PINS` | comma-separated base64 SPKI SHA-256 pins for TLS certificate pinning (empty = system CA validation only) |

The workflow passes these to Gradle as `ORG_GRADLE_PROJECT_*` environment
variables; nothing personal is hardcoded in the repo.

### Supply-chain notes

- Actions are pinned to major tags; pin to full commit SHAs for maximum
  protection (resolve with `git ls-remote`).
- `gitleaks-action` needs `GITLEAKS_LICENSE` only for organization repos.
- Secrets are never echoed or written into the checkout; the keystore lives
  only in `$RUNNER_TEMP` for the duration of one step.
- Minimal permissions: `contents: write` (release) + `packages: write` (GHCR).

## 3. Self-hosting the update feed

The app's updater fetches `<CALALARM_UPDATE_SERVER_URL>/version.json` and expects:

```json
{
  "version": "2.7.0",
  "versionCode": 65,
  "url": "releases/calendar-alarm-v2.7.0.apk",
  "sha256": "<64 hex chars of the APK>",
  "size": 12345678,
  "minSdk": 26
}
```

`tools/make_version_json.py <apk> <versionName> [versionCode]` generates this
file from an APK (versionCode is read from `build.gradle.kts` when omitted).

Serve a static directory over **HTTPS**, e.g.:

```
<update-host>/
├── version.json
└── releases/
    └── calendar-alarm-v2.7.0.apk
```

The app enforces: HTTPS only, same-host relative path under `releases/`, no
redirects, ≤ 50 MB streamed download, exact size + SHA-256 match, and APK
signing-certificate match against the pinned release signer and the installed
app's signer. Publish `SHA256SUMS` alongside for manual verification.

## 4. Pre-publish checklist (make the GitHub repo public)

- [ ] `tools/sanitize-check.sh` and `--history` both print `CLEAN`
- [ ] `git log -p --all` reviewed — no personal data in history (if there is,
  rewrite history *before* publishing, e.g. with `git filter-repo`)
- [ ] **Secret scanning** enabled (Settings → Code security and analysis →
  Secret scanning) and **Push protection** enabled
- [ ] **Branch protection** on `main`: require pull requests, require CI
  status checks (CI workflow), no force pushes, no deletions
- [ ] **Dependabot** enabled for `github-actions` and `gradle` ecosystems
  (Settings → Code security and analysis → Dependabot)
- [ ] Repository **topics** set (e.g. `android`, `kotlin`, `alarm`, `self-hosted`)
- [ ] `SECURITY.md` present (private vulnerability reporting via Security Advisories)
- [ ] `docs/RELEASE.md`, `docs/SECURITY-AUDIT.md` present
- [ ] Release secrets/variables configured (§2) — test with a pre-release tag
  on a private fork first if possible
- [ ] Keystore backed up offline in ≥ 2 locations; `PINNED_SIGNER_SHA256`
  matches the keystore that will sign releases
- [ ] No keystore, `.env`, `gradle.properties`, `*.jks`, build outputs, or
  `server/data/*` in the repo (`git ls-files` clean; `.gitignore` covers them)
- [ ] Update feed host serving `version.json` + `releases/*.apk` over HTTPS
  (only needed if you enable the in-app updater)
