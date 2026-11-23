# Changelog

Condensed English changelog — major milestones only. Current version: **2.8.0**
(versionCode 66, targetSdk 35, minSdk 26).

## v2.8.0 — 2026-11-23 — reliability, monitoring & security hardening

### Features

- **Device heartbeat with server-side verdict** — the app posts its currently scheduled
  alarms and permission status to `POST /api/heartbeat` every 3 hours.
  `GET /api/heartbeat/status` compares the last heartbeat with the cached plan for
  tomorrow and returns a verdict (`ok`, `missing`, `mismatch`, `pending`,
  `user_disabled`, `no_plan`, `permissions`, `stale`) so an external monitor can alert
  when the phone does not have the expected alarm. A separate read-only
  `CALALARM_MONITOR_TOKEN` can poll the status endpoint without knowing the device token.
- **Direct boot support** — the alarm path (alarms, timer state, widget cache, settings)
  moved to device-protected storage, so alarms still fire after a reboot before the first
  unlock; `LOCKED_BOOT_COMPLETED` re-arms them, `USER_UNLOCKED` re-initializes secure
  storage and schedules the workers.
- **Time-change handling** — on `TIME_SET` / `TIMEZONE_CHANGED` all future alarms are
  recomputed from their wall-clock time in the new time zone.
- **Power-button dismiss with pocket-press guard** — while ringing, a screen-off is
  treated as a power-button press only when the app holds the screen wake-lock and the
  screen was on for at least 1.5 s; a press within the first 10 s of ringing counts as
  snooze instead of dismiss.
- **Bundled fallback alarm sound** — if the configured sound cannot be played (e.g.
  before the first unlock), playback falls back to the system default alarm sound and
  then to a sound bundled in the APK.
- **Dedicated timer ringing service** — one foreground service owns the
  "timer finished" ringing (fixes silent or duplicated timer alarms).
- **Permissions card in Settings** — live status of notifications, full-screen intent,
  exact alarms, display-over-other-apps and battery exemption.

### Reliability

- **Last-known-good plan cache** — every computed plan is cached server-side per date
  (`/data/plans/<date>.json`, mode 600, pruned after 14 days); when the calendar backend
  fails, the cached plan is returned (`source: "cache"`) instead of an error or a
  misleading default rule. Without a cache the endpoint still returns `503` and the app
  keeps the existing alarm.
- **Non-blocking server** — the Microsoft token is cached in memory under a lock and
  refreshed in a threadpool, so concurrent requests share one refresh and the async event
  loop never blocks; calendar query windows are converted to true UTC (previously a local
  time could be sent labelled as UTC).
- **Persistent sync-failure counter** — `SYNC_FAILED` notifications now fire only after 3
  consecutive failed sync runs and then at most once per 12 h (the counter survives worker
  and app restarts).

### Security

- **Per-host certificate pinning** — every HTTPS client gets its own trust manager bound
  to one fixed host (no shared mutable host). The default pin set (`ACTIVE_PINS`) covers
  the root certificates of Let's Encrypt (ISRG), Google Trust Services, SSL.com and
  GlobalSign, and hostname verification is always mandatory. `CALALARM_CERT_PINS`
  overrides the set (e.g. for a private CA). Reproduce pins with `tools/compute_pins.sh`.
- **Fail-closed in-app updater** — the updater is only active when **both** the update
  server URL and the pinned signer SHA-256 (`CALALARM_UPDATE_SIGNER_SHA256`) are set.
  Only the update channel may fall back to system trust (without pinning, still with
  hostname verification) on a pin mismatch; sync and log upload always stay pinned.
- **Heartbeat validation** — strict payload schema, 64 KB payload cap, max 50 scheduled
  entries, ISO-8601/`YYYY-MM-DD`/`HH:MM` formats enforced; alarm labels are accepted but
  never stored; validation errors return field names only, never input values.
- **Container hardening** — all capabilities dropped, `no-new-privileges`, memory
  (256 MB) and PID limits, a pure-Python healthcheck (no curl in the image), and
  `FORWARDED_ALLOW_IPS` so uvicorn only trusts `X-Forwarded-For` from the reverse
  proxy's IP on the `proxy` network.
- **`tools/verify.sh` as hard pre-push gate** — Android unit tests + `assembleDebug` (+
  `assembleRelease` when a keystore is configured) + server pytest must all pass.

## v2.6.x — 2026-11 — notification hygiene (2026-11-12)

- **v2.6.13** — Fixed nine notification leaks: the sync worker is now the single notifier
  (one notification on SET/CHANGED, silent when the plan is identical), immediate sync on
  app start throttled to 1×/30 min, repeat alarms re-arm silently after firing, sync-failure
  notifications deduped (first 2 attempts silent, then max 1×/12 h), update notifications
  only once per version, plus a content-dedup safety net in the central notification
  funnel. Notification policy: notify only on alarm set / pre-alarm (60 min before) /
  alarm changed; REMOVED/SYNC_FAILED only on real actions or errors.
- **v2.6.12** — Introduced the notification policy and central funnel
  (`AlarmNotificationHelper.notifyAlarmEvent` with `SET, CHANGED, REMOVED, PRE_ALARM,
  RINGING, SYNC_FAILED, UPDATE`); boot/app-update reschedules and identical re-syncs are
  silent.
- **v2.6.11 / v2.6.10** — timer ringing activity simplified for lock-screen compatibility;
  alarm ringing activity no longer destroyed by rotation/dark-mode changes.

## v2.6.0 — 2026-10-26 — timer & alarm improvements

- Timer: live countdown in the status-bar notification (Chronometer), full-screen ringing
  activity on completion, stop action preserved.
- Alarms: notifications only when time/label actually changes; configurable volume ramp,
  vibration pattern and custom sound (global + per alarm); side/power-button dismiss
  (volume up/down, power — handled on `onKeyUp`); pre-alarm notification before ringing;
  full-screen alarm activity retained.

## v2.4.0 — 2026-10-14 — widget fix

- Native `TextClock` in the widget (ticks on its own), `previewLayout` for correct grid
  sizing on One UI 6+, extensive logging, defensive error handling with fallback rendering.

## v2.2.x — 2026-10-14 — home-screen widget series

- v2.2.0: transparent home-screen widget (live time, date, next alarm), timer tab, logs in
  Settings.
- v2.2.2–v2.2.8: Samsung One UI widget compatibility fixes — removed `reconfigurable`
  widget features and `configure` attribute (placement failed), added plain-drawable
  `previewImage`, opaque background colors, per-widget try/catch, simplified JSON parsing.

## v2.1.x — 2026-10-12/14 — alarm management & hardening

- v2.1.0: alarm list with enable/disable toggles, delete with confirmation, add-alarm dialog
  (time, label, repeat days), pre-alarm notification 60 min before with turn-off/skip,
  "skip this time" for repeat alarms, configurable snooze (5/10/15/30 min), unit tests.
- v2.1.1: race-condition fix in the alarm store, skip-logic fixes, automatic re-arm of
  repeat alarms, smaller fixes.
- v2.1.3–v2.1.4: crash fixes (nested scroll, coroutine cancellation re-throw, token
  rotation guard).
- v2.1.5: security hardening — no keystore secrets in git, server binds locally behind the
  proxy, fail-closed encrypted storage, APK signature verification for updates, log upload
  interval reduced to 6 h for privacy, `FLAG_SECURE` on the ringing activity.
- **v2.1.6 (breaking)**: device token moved from URL query parameter to the
  `Authorization: Bearer` header on all endpoints (app and server must be updated
  together); server-side hardening — Content-Type validation and payload size limits on all
  POST endpoints, token file mode 600, log sanitization (hex tokens, Cloudflare secrets),
  per-token rate limiting, startup permission warnings.
- v2.1.7: certificate-pinning hotfix (restored the non-hostname-aware trust path used by
  Ktor CIO; SPKI pinning stays active).
- v2.1.8 / v2.1.9: optional weekend rule (empty `weekend_rule_id` = no alarm on weekends,
  `wake_time: null`), `default_rule_id` may be empty.

## v2.0.x — 2026-10-12 — pull-model redesign

- **v2.0.0**: architecture changed from in-app server (push) to **pull model** — the app is
  now an HTTPS client (Ktor) polling a self-hosted FastAPI backend (`server/main.py`) that
  reads the Outlook/Microsoft 365 calendar and computes the wake time; WorkManager sync
  (hourly + on start), boot receiver re-arms alarms, server URL + device token configurable
  in the app.
- v2.0.1: Cloudflare Access service-token support (headers + settings UI).
- v2.0.2: proper User-Agent header (Cloudflare bot detection).
- v2.0.3: logs viewer + CSV export, automatic log upload to `POST /api/log`, editable
  device token, specific per-error messages.
- v2.0.4–v2.0.7: certificate pinning implemented directly in the HTTP client (Ktor CIO
  ignores Android Network Security Config); `<pin-set>` removed from the NSC to avoid
  conflicts; all HTTP clients share the pinner; streaming APK download (OOM fix).

## v1.0.x — 2026-10-11 — initial release

- v1.0.0: smart alarm driven by the Outlook calendar, in-app API server, bearer-token auth
  + rate limiting, self-update via `version.json`, encrypted settings storage, certificate
  pinning.
- v1.0.1: app-owned alarms via `AlarmManager` (fire unattended), API bound to loopback
  only, fail-closed encrypted storage, APK signature verification, strict input validation,
  minimal permissions.
- v1.0.2: test alarm, connection-status indicator, alarm sound, snooze/dismiss fixes,
  safe alarm replacement (new before old), crash-safe alarm storage.
