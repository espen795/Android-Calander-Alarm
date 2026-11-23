# Architecture

Calendar Alarm is a **pull-based** system: the Android app initiates all HTTPS connections to
a small self-hosted backend; nothing ever connects inbound to the phone.

```
┌────────────────────────┐   HTTPS pull    ┌─────────────────────────┐   HTTPS   ┌──────────────────┐
│ Android app            │ ──────────────► │ Backend (FastAPI)       │ ────────► │ Microsoft Graph  │
│  WorkManager sync ────►│ GET /api/alarm/next│ reads calendar rules  │           │ /me/calendarview │
│  AlarmManager alarms   │◄────────────── │ POST /api/log           │           └──────────────────┘
│  widget / timer / UI   │  JSON plan      └─────────────────────────┘
└────────────────────────┘
```

## Android app components

Source: `android/app/src/main/java/io/github/calendaralarm/`.

| Component | Role |
|---|---|
| `CalendarAlarmApp` | `Application` class; checks secure storage, creates notification channels, schedules all WorkManager workers (also re-scheduled after `USER_UNLOCKED`) |
| `MainActivity` + Compose screens (`ui/screens/`) | Main UI: alarm list, timer, logs, server-rules editor, **permissions card** (`PermissionsCard.kt`) with live status of notifications / full-screen / exact alarms / overlay / battery |
| `sync/SyncRepository.kt` | HTTPS client (Ktor CIO) for the backend: `fetchAlarmPlan()`, `fetchConfig()`, `saveConfig()`, `healthCheck()`. Holds `CertificatePinner` |
| `sync/CertificatePinner` + `sync/CertificatePins.kt` | Manual SPKI SHA-256 pinning per **fixed host** (Ktor's CIO engine does not use Android's Network Security Config, so pins are enforced here). `CertificatePins.ACTIVE_PINS` = built-in public-CA root pins (ISRG, GTS, SSL.com, GlobalSign) unless `CALALARM_CERT_PINS` overrides them; PKIX + hostname verification always on |
| `worker/AlarmSyncWorker.kt` | Periodic (hourly) + one-shot (app start, throttled to 1×/30 min) WorkManager job: fetches tomorrow's plan and schedules/cancels the alarm for that date; sync-failure notification policy in `worker/SyncFailurePolicy.kt` (notify after ≥3 consecutive failures, then ≤1×/12 h, counter in device-protected prefs) |
| `worker/HeartbeatWorker.kt` + `sync/HeartbeatReporter.kt` | Every **3 h** (network connected, also before unlock — reporting never changes alarms): posts the enabled scheduled alarms + permission status to `POST /api/heartbeat`. Failures are log-only (never a retry storm) |
| `worker/UpdateCheckWorker.kt` / `DownloadVerifiedUpdateWorker.kt` | Check `version.json` every 6 h; download APK streamed to disk (max 50 MB), verify SHA-256 **and** pinned signer certificate, then offer install. Updater only active when both update URL and signer SHA-256 are configured |
| `worker/LogUploadWorker.kt` | Uploads new in-app log entries to `POST /api/log` every 6 h (batch ≤ 200 entries) |
| `data/AlarmRepository.kt` / `AlarmStore.kt` | App-owned alarms via `AlarmManager.setAlarmClock()` with `AlarmClockInfo.showIntent` (full-screen activity) + operation PendingIntent; store with a transactional mutex; per-date replacement; pre-alarm scheduling |
| `data/DeviceProtectedStorage.kt` | **Direct boot**: the alarm path (alarm store, timer state, widget cache, settings, service state) lives in device-protected storage so it is readable before the first unlock; one-time idempotent migration from credential-encrypted storage |
| `receiver/OwnedAlarmReceiver.kt` | Fires the alarm: starts the foreground service, re-arms repeat alarms |
| `receiver/PreAlarmReceiver.kt` | Pre-alarm notification **60 min** before (`PRE_ALARM_OFFSET_MS`), with turn-off / skip actions |
| `receiver/TimeChangeReceiver.kt` | On `TIME_SET` / `TIMEZONE_CHANGED`: recomputes all future alarms from their wall-clock time (`date` + `timeLabel`) in the new zone, then re-arms (inside the alarm mutex) |
| `receiver/UserUnlockReceiver.kt` | On `USER_UNLOCKED`: re-runs the direct-boot migration, re-initializes `EncryptedSharedPreferences`, schedules the WorkManager workers |
| `receiver/TimerFireReceiver.kt` / `TimerActionReceiver.kt` | Countdown timer fire / stop |
| `service/BootReceiver.kt` | `directBootAware`; re-arms all alarms after `BOOT_COMPLETED` / `LOCKED_BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` (silently — no notifications) |
| `service/AlarmForegroundService.kt` | Foreground service (`mediaPlayback`) during ringing — protection against aggressive background-process killing (Samsung One UI OOM killer); holds a short WakeLock and posts the notification with `setFullScreenIntent()`; sound playback uses the fallback chain in `service/AlarmSoundFallback.kt` (configured sound → system default alarm → bundled `res/raw/fallback_alarm.wav`) |
| `service/PowerButtonWatcher.kt` + `PowerButtonDecision.kt` | While ringing, holds a screen wake-lock and counts `ACTION_SCREEN_OFF` as a power-button press only when the wake-lock is held and the screen was on ≥ 1.5 s (grace period after ring start); a press within the first 10 s of ringing is treated as **snooze**, not dismiss (pocket-press guard) |
| `service/TimerRingService.kt` | The single owner of "timer finished" ringing: foreground service (`mediaPlayback`) + full-screen intent; fixes duplicated/silent timer alarms |
| `service/TimerForegroundService.kt` | Keeps the process alive while a timer runs |
| `ui/AlarmRingingActivity.kt` / `TimerRingingActivity.kt` | Full-screen ringing UI over the lock screen (`showWhenLocked`, `turnScreenOn`); dismiss via on-screen buttons or volume/power keys (`onKeyUp`) |
| `receiver/AlarmWidgetProvider.kt` + `res/layout/widget_alarm.xml` | Home-screen widget: live `TextClock`, date, next alarm; refreshed on every alarm change via `WidgetCache` |
| `data/SettingsRepository.kt` | Settings + **secure** storage: device token and Cloudflare Access credentials in `EncryptedSharedPreferences` (fail-closed: if it can't initialize, API features are disabled); non-sensitive settings in regular prefs |
| `data/AppLogger.kt` | In-memory ring buffer of log lines, viewable in the app and uploadable to the server (sanitized server-side) |

### Sync & alarm scheduling flow

1. `AlarmSyncWorker` runs hourly (plus once shortly after app start) with
   `NetworkType.CONNECTED`.
2. It calls `GET /api/alarm/next` (optionally `?date=YYYY-MM-DD`) with
   `Authorization: Bearer <device-token>` (plus `Cf-Access-*` headers if configured).
3. The server refreshes the Microsoft token, fetches tomorrow's `/me/calendarview`,
   matches keyword rules and returns `{date, wake_time, label, work_location, confidence,
   event_count}` — or `wake_time: null` for "no alarm" (e.g. weekend without a weekend
   rule).
4. The worker diffs the plan against the stored alarms for that date and only then acts:
   `wake_time == null` → cancel alarms for that date; otherwise `replaceForDate()`
   schedules the smart alarm idempotently (same plan → no-op, changed plan → same alarm
   ID reused).
5. `AlarmRepository.schedule()` registers `AlarmManager.setAlarmClock()` with a
   full-screen `showIntent`, schedules the pre-alarm 60 min earlier, and updates the
   widget cache.
6. Notifications are shown **only on real changes** (set / changed / removed / pre-alarm /
   ringing / sync-failed / update available) via the central
   `AlarmNotificationHelper.notifyAlarmEvent()` funnel — identical re-syncs and
   boot-time re-arming are silent.

### Heartbeat & monitoring flow

1. Every 3 h `HeartbeatWorker` (network connected) calls `HeartbeatReporter.send()`, which
   takes a consistent snapshot of the alarm store inside the alarm mutex (no network under
   the lock) plus the permission status, and posts it to `POST /api/heartbeat` via a
   per-host pinned Ktor client. Only enabled, future alarms (and alarms the user
   deliberately disabled) are included.
2. The server validates the payload (pydantic), strips labels, and stores it atomically in
   `/data/heartbeat.json` (mode 600).
3. `GET /api/heartbeat/status` (device token **or** the read-only `CALALARM_MONITOR_TOKEN`)
   compares the heartbeat with the cached plan for tomorrow and returns a verdict:
   `ok` / `missing` / `mismatch` / `pending` / `user_disabled` / `no_plan` /
   `permissions` / `stale` — see [docs/API.md](API.md). An external monitor can therefore
   alert when the phone does not have the expected alarm, without access to the device
   token or the config.

### Update flow

1. Every 6 h `UpdateCheckWorker` fetches `<CALALARM_UPDATE_SERVER_URL>/version.json`.
   The in-app updater is **only active when both** `CALALARM_UPDATE_SERVER_URL` and a
   valid 64-hex `CALALARM_UPDATE_SIGNER_SHA256` are set (fail-closed otherwise).
2. `version.json` must contain `version`, `versionCode`, a **relative** `url` under
   `releases/calendar-alarm-v<version>.apk` (enforced by a strict path regex), a 64-hex
   `sha256` and a bounded `size`.
3. `DownloadVerifiedUpdateWorker` streams the APK to disk (hard cap 50 MB, no redirects,
   pinned per-host trust manager), verifies the SHA-256 and that the APK's signing
   certificate matches the pinned release signer (and the installed app's signer), then
   posts an install notification (FileProvider + `REQUEST_INSTALL_PACKAGES`).
4. **Update-channel-only fallback**: on a pure pin mismatch, `version.json` and the APK
   download fall back to a system-trust client (still hostname-verified, still verified by
   SHA-256 + size + signer) so a CA rotation at the update host does not brick updates.
   Sync, heartbeat and log upload never fall back — they fail closed. Hostname errors and
   network errors never trigger the fallback.
5. `tools/make_version_json.py <apk> <versionName> [versionCode]` generates a valid
   `version.json` for a release APK. `tools/compute_pins.sh` reproduces the certificate
   pins in `CertificatePins.kt`.

## Server components

Source: `server/main.py` (FastAPI, single file).

- **Auth**: `Authorization: Bearer <token>`, constant-time comparison against
  `CALALARM_DEVICE_TOKEN`. A separate `CALALARM_MONITOR_TOKEN` (constant-time compare,
  empty = disabled) is accepted on `GET /api/heartbeat/status` only — it can neither post
  nor read/modify config.
- **Rate limiting**: 10 req/min per client IP **and** per device token (429 when exceeded);
  Traefik adds another layer in compose. `FORWARDED_ALLOW_IPS` (env) restricts which
  proxies' `X-Forwarded-For` headers uvicorn trusts.
- **Microsoft Graph**: the access token is cached in memory under a lock (one refresh
  shared by concurrent requests); the blocking refresh + `/me/calendarview` call run in
  the FastAPI threadpool so the event loop never blocks. Calendar query windows are
  converted to true UTC. Token file is written back with mode `600`.
- **Plan cache (last-known-good)**: every successful plan is stored atomically per date in
  `/data/plans/<YYYY-MM-DD>.json` (mode 600, with a `cached_at` timestamp, pruned after
  14 days). On a calendar failure `GET /api/alarm/next` returns the cached plan with
  `source: "cache"` instead of a 503 or a misleading default rule; without a cache it
  still returns 503.
- **Heartbeat**: `POST /api/heartbeat` validates the payload with pydantic (strict
  ISO-8601 / `YYYY-MM-DD` / `HH:MM` formats, ≤ 50 scheduled entries, ≤ 64 KB body, labels
  stripped before storage, validation errors return field names only) and stores it
  atomically in `/data/heartbeat.json` (mode 600). `GET /api/heartbeat/status` computes a
  verdict for tomorrow (`ok`/`missing`/`mismatch`/`pending`/`user_disabled`/`no_plan`/
  `permissions`/`stale`, heartbeat older than 8 h = `stale`) by comparing the heartbeat
  with the cached plan.
- **Rule engine**: `detect_rule()` — cancelled events are ignored; all-day events matching
  the `off` rule win immediately; otherwise keywords are scored against subject + body +
  location + categories, with rule priority `off` > `office` > `home`; no match →
  `default_rule_id` (weekdays) or `weekend_rule_id` (weekends, empty = no alarm). Only
  match counts ever leave the server — never event subjects.
- **Config validation** on `POST /api/config`: schema, unique ids, `HH:mm` times, max 20
  rules, max 100 KB payload, `application/json` required (415/413/400 otherwise).
- **Log endpoint** `POST /api/log`: max 1 MB payload, max 500 entries per batch, 1000 chars
  per line; device token, 64-hex strings and Cloudflare `cfast_*` secrets are redacted
  before writing to `logs/calendar-alarm-device.log` (rotated at 5 MB).
- **Hardening**: runs as non-root (UID 10001), read-only rootfs, only `/data` writable,
  `cap_drop: ALL`, `no-new-privileges`, mem 256 MB / pids 100 limits in compose, pure-Python
  healthcheck, `umask 077` so new files are owner-only; startup check warns if
  token/config files have group/other permissions.

## Data storage (app)

| Data | Where |
|---|---|
| Device token, Cloudflare Access credentials | `EncryptedSharedPreferences` (Android Keystore `MasterKey`, AES256-GCM) — **fail-closed**: if initialization fails, API calls are disabled. Re-initialized on `USER_UNLOCKED` |
| Alarms, timer state, non-sensitive settings, widget cache, sync/notification state, service state | **Device-protected storage** (`createDeviceProtectedStorageContext()`) — readable before the first unlock after boot (direct boot), with a one-time idempotent migration from CE storage |
| In-app log buffer | In-memory ring buffer (`AppLogger`), optionally uploaded |

`allowBackup="false"` and data-extraction/backup rules exclude this data from cloud
backup and adb backup.
