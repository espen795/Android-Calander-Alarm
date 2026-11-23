# Contributing

Thanks for considering a contribution to the server Alarm! This is a personal project, so
reviews may take a while — but issues and PRs are welcome.

## Before you start

- **Search existing issues** first; add to one if it fits.
- This project is **not on the Play Store** and is primarily tested on Samsung One UI
  (Android 14/15). Changes that affect alarm firing, the widget or the ringing activity
  should be tested on a real device if possible, and say so in the PR.
- **Privacy is a hard rule**: no personal data in the repo — no names, real domains,
  IPs, paths, usernames or credentials. Use `example.com` in docs and examples. Run
  `tools/sanitize-check.sh` before pushing; it must print `sanitize-check: CLEAN`
  (CI runs it, plus gitleaks).
- Do not commit `android/gradle.properties`, `android/local.properties`, `.env`,
  keystores or `token*.json` — all are git-ignored.

## Development setup

```bash
# Hard gate (required before every push): app unit tests + assembleDebug (+ assembleRelease
# when a keystore is configured) + server pytest — must print RESULTAAT: GROEN
tools/verify.sh

# Or run the parts manually:
# App
cd android
cp gradle.properties.template gradle.properties
./gradlew :app:testDebugUnitTest :app:assembleDebug

# Server
cd server
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt -r requirements-dev.txt
python -m pytest -q
```

## Pull requests

- Keep PRs focused: one change per PR. Use the PR template.
- Update `CHANGELOG.md` if the change is user-visible.
- Update or add docs under `docs/` when behavior, config or the API changes.
- Add/adjust unit tests for logic changes (`android/app/src/test/...`, `server/tests/...`).
- **Pre-push gate**: run `tools/verify.sh` before pushing — it is a hard gate (app unit
  tests + `assembleDebug` + server pytest; release builds add `assembleRelease`). It must
  end with `RESULTAAT: GROEN`; a skipped step (e.g. no keystore in a worktree) requires
  `ALLOW_SKIP=1` and is never release-worthy.
- CI must pass: gitleaks + `tools/sanitize-check.sh`, server pytest, Android unit tests +
  debug build.
- Never modify secrets handling to make things "easier" — tokens stay in
  `EncryptedSharedPreferences` and in `Authorization` headers, never in URLs or logs.

## Releases

Push a git tag `vX.Y.Z` matching `versionName` in `android/app/build.gradle.kts`.
`.github/workflows/release.yml` then builds the signed APK (keystore via repository
secrets), publishes the server image to GHCR, and creates a GitHub Release containing the
APK, `version.json` and `SHA256SUMS`. The in-app updater consumes that `version.json`.

## Code of conduct

Be respectful and constructive. This is a hobby project maintained by one person; there is
no SLA and no obligation to merge anything.

## License of contributions

By submitting a pull request you agree that your contribution is licensed to the
project under the PolyForm Noncommercial License 1.0.0 **and** that the maintainer may
also distribute it under separate commercial licenses (see COMMERCIAL.md). Add
`Signed-off-by: Your Name <email>` to your commits (`git commit -s`) to confirm you
have the right to submit the code.
