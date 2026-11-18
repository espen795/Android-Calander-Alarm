## What does this PR change?

<!-- Short summary. Link the issue if there is one: Closes #123 -->

## Type of change

- [ ] Bug fix
- [ ] New feature
- [ ] Refactor / internal change
- [ ] Documentation
- [ ] Build / CI

## How was it tested?

<!-- Describe manual testing on a real device and/or unit tests. Alarm firing, widget and
     ringing-activity changes should be verified on a device if possible. -->

- [ ] `tools/sanitize-check.sh` prints `sanitize-check: CLEAN` (no personal data, no real
      domains/IPs/paths/credentials; docs use `example.com`)
- [ ] `./gradlew :app:testDebugUnitTest` passes (android/)
- [ ] `python -m pytest -q` passes (server/)
- [ ] Manual test on device: ____________ (Android version / OEM skin)

## Checklist

- [ ] `CHANGELOG.md` updated if user-visible
- [ ] Docs updated (`docs/`, `README.md`) if behavior/config/API changed
- [ ] No changes to secrets handling (tokens stay in EncryptedSharedPreferences and
      `Authorization` headers — never in URLs, logs or the repo)
- [ ] No changes under `android/` or `server/` code beyond what this PR intends
      (release process does not modify them)
