# Security Audit — Calendar Alarm (public repo)

**Date:** 2026-11-18
**Scope:** all tracked files (`git ls-files`), untracked additions under
`server/` and `docs/`, full git history (`git log -p --all`), CI/release
workflows, server security posture, Android security posture.
**Method:** pattern scans (secrets, keys, IPs, domains, emails, home paths,
household terms), manual review of auth/crypto/update paths, YAML validation,
`tools/sanitize-check.sh` (worktree + `--history`).

Only `example.com` / `example.org` style placeholders are used throughout;
no personal data appears in this document.

## 1. Sanitization findings

| # | Severity | File:line | Finding | Status |
|---|----------|-----------|---------|--------|
| S1 | Medium | `android/app/src/main/java/io/github/calendaralarm/data/UpdateChecker.kt:55` | Comment references a real keystore filename (`calendar-alarm-release-new.jks`). Filename itself is not a secret, but it ties the public repo to a specific signing key artifact name. | Reported for maintainer (Kotlin is read-only for this audit — another agent owns it). Recommend rewording the comment to "the current release keystore" and rotating/renaming the keystore file. |
| S2 | Low | `android/app/src/main/java/io/github/calendaralarm/data/UpdateChecker.kt:58` | Hardcoded `PINNED_SIGNER_SHA256` — the SHA-256 of the release signing certificate is public by design (it is shipped inside the APK). | Accepted / by design; documented in RELEASE.md. Must be updated on every key rotation. |
| S3 | Low | `android/.../MainScreen.kt`, `SettingsScreenTest.kt`, `SyncRepository.kt` | UI strings mention "Cloudflare Access" as the deployment pattern of the original author. This reveals infrastructure style, not credentials. | Reported (Kotlin read-only). Low risk; consider generic wording ("access gateway / service token"). |
| S4 | Info | `android/.../*.kt` (many) | Comments mention a specific phone model ("Samsung S24") used during development. Generic brand+model, not tied to a person. | Accepted as generic device-compat notes; no action. |
| S5 | Info | `server/main.py:77-92`, `server/alarm_config.example.json` | Default rule names/keywords are Dutch generic words ("kantoor", "thuis", "verlof"). Not personal data. | Accepted; example config is user-editable. |
| S6 | Info | `server/main.py` docstring + several comments | Dutch-language comments; no personal data. | Accepted (code is being translated by another agent). |
| S7 | — | whole repo + `git log -p --all` (1 commit, 12,756 added lines scanned) | No secrets, tokens, private keys, real IPs, real domains (only `example.com`), emails, `/home/<user>` paths, personal names, phone numbers, or signing material found. Commit author is `Calendar Alarm contributors <noreply@example.com>`. | Clean. |
| S8 | Low | `server/main.py:567` (pre-fix) | Log file was chmod'ed to `0o640` (group-readable) while the process umask is `0o077` and all other sensitive files are `0o600`. Device logs can contain device context. | **Fixed** → `0o600`. |
| S9 | Medium | `server/main.py:579` (pre-fix) | `__main__` block bound uvicorn to `0.0.0.0`, contradicting the "bind localhost" hardening comment; if run outside the container it would expose the API on all interfaces without TLS. | **Fixed** → binds `127.0.0.1` for local runs; container path still uses `0.0.0.0` inside the internal network only (documented in Dockerfile). |
| S10 | Info | `server/Dockerfile` | Installed `curl` + `jq` although `main.py` uses only stdlib `urllib`. Unnecessary attack surface. | **Fixed** → removed; comment added. |
| S11 | Medium | `server/docker-compose.yml` labels | Traefik router used `entrypoints=web` (plain HTTP) with a comment claiming external protection by an access gateway; misconfiguration could expose cleartext traffic. No `ports:` mapping existed (good). | **Fixed** → `entrypoints=websecure` + `tls=true`, explicit "no ports:" comment, generic proxy note. |
| S12 | Info | `server/data/README.md` | Referenced a non-existent `docs/BACKEND.md`. | **Fixed** → points to `tools/ms_device_login.py` and documents the device-code bootstrap. |
| S13 | Info | `.github/workflows/release.yml:57` | Keystore decoded without restrictive mode. | **Fixed** → `chmod 600` + `set -euo pipefail` in all run steps. |
| S14 | Low | `.github/workflows/release.yml` (Package assets) | `sha256sum *` included `version.json` ordering nondeterminism and could include unexpected files. | **Fixed** → `sha256sum calendar-alarm-*.apk version.json`. |
| S15 | Low | `.github/workflows/release.yml` (Create Release) | awk one-liner only matched `## vX.Y.Z` exactly (not `## [X.Y.Z]`), so notes could silently be empty. | **Fixed** → awk handles both `## v2.7.0` and `## [2.7.0]` headers. |
| S16 | Info | `server/tools/ms_device_login.py` (untracked new file) | Reviewed: stdlib-only device-code OAuth bootstrap; writes token with mode 600; no secrets embedded; client IDs are passed as CLI args. | Clean; recommend adding to git (not done here — no commits allowed). |
| S17 | Info | `android/app/build/` exists on disk | Build output present locally but correctly git-ignored (`git check-ignore` OK, not in `git ls-files`). | No action. |
| S18 | Info | `.gitignore:8` `**/data/*` | This also ignores `android/.../data/` source dir pattern-wise, but those files ARE tracked (tracked files are unaffected by gitignore). New files under `android/**/data/` would be silently ignored — footgun for future contributors. | Reported; recommend narrowing to `server/data/*` (edit to .gitignore allowed but left for maintainer to avoid churn with concurrent agents). |

### sanitize-check outputs (run 2026-11-18, after fixes)

```
$ tools/sanitize-check.sh
sanitize-check: CLEAN
$ tools/sanitize-check.sh --history
sanitize-check: CLEAN
```

(No private denylist was present at the default path, so only the generic
checks ran — forbidden file types, private IP ranges, PEM key headers,
`cfast_` secrets, `client_secret` assignments, `/home/<user>/` paths.)

## 2. CI / release workflow review

| # | Item | Assessment |
|---|------|------------|
| W1 | Action pinning | Tags (`@v4`, `@v2`, `@v6`…) used. SHA-pinning recommended in comments of both workflows (kept as tags in files per repo policy). |
| W2 | Permissions | `ci.yml`: `contents: read` only — minimal, correct. `release.yml`: `contents: write` + `packages: write`, both required for release + GHCR push; documented. |
| W3 | Secrets handling | Keystore decoded to `$RUNNER_TEMP`, deleted in the same step (`rm -f`), plus `shred` of the copied `gradle.properties`; secrets only via `${{ secrets.* }}`, never echoed. `GITHUB_TOKEN` scoped per job by permissions block. |
| W4 | gitleaks | `gitleaks-action@v2` with `fetch-depth: 0` in CI and in the release `guard` job. `GITLEAKS_LICENSE` needed only for org repos — documented in both files. |
| W5 | Tag guard | `guard` job fails the release if the tag != `versionName` in `android/app/build.gradle.kts`. Fixed to `set -euo pipefail`. |
| W6 | GHCR naming | `ghcr.io/${GITHUB_REPOSITORY,,}-server:<tag>` + `:latest`. Lowercased repo — correct for GHCR. |
| W7 | CHANGELOG awk | Fixed to match both `## vX` and `## [X]` headers; fallback text added. |
| W8 | YAML validity | All three YAML files parse (`python3 -c yaml.safe_load`): ci.yml, release.yml, docker-compose.yml. |
| W9 | Artifact flow | `actions/upload-artifact@v4` (dist) → `download-artifact@v4` → `gh release create dist/*`. Note: `gh release create` with a directory glob uploads all files incl. `SHA256SUMS` and `version.json` — intended. |
| W10 | Residual | `gradle/actions/setup-gradle@v4` caches Gradle deps — fine. `pull_request` trigger in CI runs with read-only token — safe. Recommend enabling GitHub secret scanning + push protection on the repo (see RELEASE.md checklist). |

## 3. Server (`server/main.py`) security review

| Control | Status |
|---------|--------|
| Auth | Bearer token from `Authorization` header (never URL), rejected when unset (`validate_token` returns False if `DEVICE_TOKEN` empty — fail closed). Constant-time compare implemented manually with length pre-check — acceptable; `hmac.compare_digest` would be cleaner (minor). |
| Rate limiting | 10 req/60s per client IP **and** per device token; in-memory buckets (single-process uvicorn — fine for this deployment). |
| Input validation | `date` param strictly `YYYY-MM-DD`; config payload validated field-by-field (ids, HH:MM times, name/keyword lengths, max 20 rules, duplicate/unknown rule refs); log entries truncated field-wise. |
| Size limits | Config 100 KB, log payload 1 MB, checked **before** JSON parsing. |
| Content-Type | POSTs require `application/json` (415 otherwise). |
| Path handling | All runtime paths resolved under `CALALARM_WORKSPACE` (default `/data`); log filename is a fixed constant (no traversal); config written via tmp file + atomic `replace`, chmod 600. Tests in `server/tests/test_paths.py` assert no `/home/` or keystore references. |
| Log sanitization | Device token replaced with `<TOKEN>`, 64-hex tokens and `cfast_*` patterns scrubbed; file mode fixed to 600 (S8). Rotation at 5 MB (`.log.old`). |
| Secrets at rest | umask 077; token/config files chmod 600; startup check warns on permissive modes. |
| Microsoft Graph | client_secret now optional (public client / device-code flow); token refresh over HTTPS with 10s timeout; access token kept in memory only. |
| TLS | Enforced at the edge proxy; container not published to host. Docstring updated (S9). |
| Residual risks | In-memory rate buckets reset on restart (acceptable); single shared device token means one leaked token = full access (rotation supported from the app UI); no request logging of IPs (privacy-positive); `detect_rule` reads event subjects in memory only and never returns them. |

## 4. Android app security review (read-only)

| Control | Status |
|---------|--------|
| Credential storage | `EncryptedSharedPreferences` + Android Keystore `MasterKey` (AES256_GCM), fail-closed (`secureStorageAvailable`); non-sensitive settings in regular prefs. (`SettingsRepository.kt`) |
| Network | `network_security_config.xml` disables cleartext; manual SPKI SHA-256 pinning in `CertificatePinner` for Ktor CIO (pins via `CALALARM_CERT_PINS` build property; PKIX + hostname verification always on, pinning optional). |
| Update verification | `UpdateChecker`: HTTPS-only, host-locked relative `releases/*.apk` path, no redirects, 50 MB cap streamed to disk, exact size + SHA-256 (constant-time compare) from `version.json`, then APK signer checked against pinned cert **and** installed signer, package name + versionCode monotonicity checked before any install intent. Install only via user-tapped notification. |
| Exported components | `MainActivity` (launcher), `BootReceiver` (BOOT_COMPLETED / MY_PACKAGE_REPLACED — must be exported), `AlarmWidgetProvider` (APPWIDGET_UPDATE — must be exported), and **`WidgetDebugActivity` exported=true with a custom intent-filter action** — a debug-only screen reachable by any app. **Recommendation (reported, not fixed — Kotlin/manifest owned by another agent): set `android:exported="false"` or remove the activity from release builds.** All receivers/services otherwise `exported=false`. |
| Permissions | `REQUEST_INSTALL_PACKAGES` needed for self-update; `SYSTEM_ALERT_WINDOW` + battery-optimization exemption requested for alarm reliability — worth documenting in the Play/store listing. `allowBackup=false`, data-extraction rules present. |
| Token in UI | Masked by default, FLAG_SECURE while visible (MainScreen). |
| Logging | `AppLogger` writes locally; upload endpoint sanitizes server-side. |

## 5. Residual risks & recommendations

1. **WidgetDebugActivity exported** — highest-priority app-side fix (see §4).
2. **Single shared device token** — consider per-device tokens or short-lived tokens in a future version.
3. **PINNED_SIGNER_SHA256 must be rotated with the keystore** — add a checklist item (in RELEASE.md).
4. **`.gitignore` `**/data/*` is too broad** — narrow to `server/data/*`.
5. **Rate limiting is in-memory** — fine for one replica; if scaled out, move to Redis/shared store.
6. **Enable on GitHub:** secret scanning + push protection, branch protection on `main` (require PR + CI), Dependabot (github-actions + gradle/maven), and CODEOWNERS.
7. **SHA-pin GitHub Actions** when comfortable (documented in workflows).
8. Keep `tools/sanitize-check.sh` in CI (already wired) and keep the private denylist outside the repo for maintainer-specific terms.
