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
| `CalendarAlarmApp` | `Application` class; checks secure storage, creates notification channels, schedules all WorkManager workers |
| `MainActivity` + Compose screens (`ui/screens/`) | Main UI: alarm list, timer, logs, server-rules editor |
| `sync/SyncRepository.kt` | HTTPS client (Ktor CIO) for the backend: `fetchAlarmPlan()`, `fetchConfig()`, `saveConfig()`, `healthCheck()`. Holds `CertificatePinner` |
| `sync/CertificatePinner` | Manual SPKI SHA-256 pinning `X509ExtendedTrustManager` (Ktor's CIO engine does not use Android's Network Security Config, so pins are enforced here) |
| `worker/AlarmSyncWorker.kt` | Periodic (hourly) + one-shot (app start, throttled to 1×/30 min) WorkManager job: fetches tomorrow's plan and schedules/cancels the alarm for that date |
| `worker/UpdateCheckWorker.kt` / `DownloadVerifiedUpdateWorker.kt` | Check `version.json` every 6 h; download APK streamed to disk (max 50 MB), verify SHA-256 **and** signer certificate, then offer install |
| `worker/LogUploadWorker.kt` | Uploads new in-app log entries to `POST /api/log` every 6 h (batch ≤ 200 entries) |
| `data/AlarmRepository.kt` / `AlarmStore.kt` | App-owned alarms via `AlarmManager.setAlarmClock()` with `AlarmClockInfo.showIntent` (full-screen activity) + operation PendingIntent; JSON store with a lock for thread safety; per-date replacement; pre-alarm scheduling |
| `receiver/OwnedAlarmReceiver.kt` | Fires the alarm: starts the foreground service, re-arms repeat alarms |
| `receiver/PreAlarmReceiver.kt` | Pre-alarm notification **60 min** before (`PRE_ALARM_OFFSET_MS`), with turn-off / skip actions |
| `receiver/TimerFireReceiver.kt` / `TimerActionReceiver.kt` | Countdown timer fire / stop |
| `service/BootReceiver.kt` | Re-arms all alarms after `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` (silently — no notifications) |
| `service/AlarmForegroundService.kt` | Foreground service (`mediaPlayback`) during ringing — protection against aggressive background-process killing (Samsung One UI OOM killer); holds a short WakeLock and posts the notification with `setFullScreenIntent()` |
| `service/TimerForegroundService.kt` | Keeps the process alive while a timer runs |
| `ui/AlarmRingingActivity.kt` / `TimerRingingActivity.kt` | Full-screen ringing UI over the lock screen (`showWhenLocked`, `turnScreenOn`); dismiss via on-screen buttons or volume/power keys (`onKeyUp`) |
| `receiver/AlarmWidgetProvider.kt` + `res/layout/widget_alarm.xml` | Home-screen widget: live `TextClock`, date, next alarm; refreshed on every alarm change via `WidgetCache` |
| `data/SettingsRepository.kt` | Settings + **secure** storage: device token and Cloudflare Access credentials in `EncryptedSharedPreferences` (fail-closed: if it can't initialize, API features are disabled); non-sensitive settings in regular `SharedPreferences` |
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

### Update flow

1. Every 6 h `UpdateCheckWorker` fetches `<CALALARM_UPDATE_SERVER_URL>/version.json`
   (disabled when the URL is empty).
2. `version.json` must contain `version`, `versionCode`, a **relative** `url` under
   `releases/calendar-alarm-v<version>.apk` (enforced by a strict path regex), a 64-hex
   `sha256` and a bounded `size`.
3. `DownloadVerifiedUpdateWorker` streams the APK to disk (hard cap 50 MB, no redirects,
   same certificate pinning), verifies the SHA-256 and that the APK's signing certificate
   matches the pinned release signer, then posts an install notification (FileProvider +
   `REQUEST_INSTALL_PACKAGES`).
4. `tools/make_version_json.py <apk> <versionName> [versionCode]` generates a valid
   `version.json` for a release APK.

## Server components

Source: `server/main.py` (FastAPI, single file).

- **Auth**: `Authorization: Bearer <token>`, constant-time comparison against
  `CALALARM_DEVICE_TOKEN`.
- **Rate limiting**: 10 req/min per client IP **and** per device token (429 when exceeded);
  Traefik adds another layer in compose.
- **Microsoft Graph**: refreshes the OAuth token
  (`login.microsoftonline.com/common/oauth2/v2.0/token`), then queries
  `/me/calendarview` for the target day. Token file is written back with mode `600`.
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
  `umask 077` so new files are owner-only; startup check warns if token/config files have
  group/other permissions.

## Data storage (app)

| Data | Where |
|---|---|
| Device token, Cloudflare Access credentials | `EncryptedSharedPreferences` (Android Keystore `MasterKey`, AES256-GCM) — **fail-closed**: if initialization fails, API calls are disabled |
| Alarms | JSON file / `SharedPreferences` via `AlarmStore` (synchronous, lock-guarded writes) |
| Non-sensitive settings (snooze, freeze window, defaults) | Regular `SharedPreferences` (more reliable on some OEM builds) |
| Widget cache, sync state, notification dedup state | Regular `SharedPreferences` |
| In-app log buffer | In-memory ring buffer (`AppLogger`), optionally uploaded |

`allowBackup="false"` and data-extraction/backup rules exclude this data from cloud
backup and adb backup.
