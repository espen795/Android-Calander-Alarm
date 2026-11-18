# Security Policy

## Supported Versions

Security fixes are applied to the latest release only. We recommend always
running the newest version of Calendar Alarm, both for the Android app and the
self-hosted server component.

| Version | Supported |
| ------- | --------- |
| latest release (see [Releases](../../releases)) | ✅ |
| older releases | ❌ |

## Reporting a Vulnerability

**Please do not open a public GitHub issue for security vulnerabilities.**

Instead, report privately via **GitHub Security Advisories**:

1. Go to the repository's **Security** tab.
2. Click **"Report a vulnerability"**.
3. Fill in the details (affected version, impact, reproduction steps,
   and optionally a proof of concept).

You can expect an acknowledgment within **72 hours**. If the report is
confirmed, we will work on a fix, publish a patched release, and credit you
in the release notes (unless you prefer to remain anonymous).

If GitHub Security Advisories are unavailable for any reason, you may also
open a **private** security report by contacting the maintainers through the
contact listed on the repository profile page.

## Scope

In scope:

- The Android app (`android/`): token/credential storage, update mechanism
  (download verification, signature checks), exported components, network
  security configuration.
- The server component (`server/`): authentication, rate limiting, input
  validation, log handling, OAuth token handling.
- The CI/release workflows (`.github/workflows/`): secret handling, signing
  key usage, supply-chain configuration.

Out of scope (please do not report):

- Vulnerabilities in third-party dependencies that are already fixed in a
  newer upstream release (we track these with Dependabot).
- Physical access to a device where the app is installed.
- Denial of service against your own self-hosted instance.

## Threat Model Summary

Calendar Alarm is a personal alarm app that pulls its schedule from a
self-hosted API. The main assets to protect are:

1. **The device token** that authenticates the app to the API — stored in
   `EncryptedSharedPreferences` (Android Keystore-backed), never logged,
   sent only as an `Authorization: Bearer` header (never in URLs).
2. **Calendar data** — the server never returns event subjects, only match
   counts and the chosen rule; log uploads are sanitized server-side.
3. **The update channel** — `version.json` is fetched over HTTPS from a
   configured host; the download URL is restricted to a relative
   `releases/*.apk` path on that same host, redirects are not followed, the
   download is size-capped, verified against a SHA-256 from `version.json`,
   and the APK signing certificate is checked against both a pinned release
   signer and the currently installed app's signer before any install intent
   is shown.
4. **The release signing key** — this is the app's identity on Android. It is
   never stored in the repository; release builds decode it from a GitHub
   secret into a temp file that is deleted immediately after the build.

See [docs/SECURITY-AUDIT.md](docs/SECURITY-AUDIT.md) for the audit findings
and residual risks, and [docs/RELEASE.md](docs/RELEASE.md) for the release
and key-management process.
