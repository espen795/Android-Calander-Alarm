# Installation guide

This guide covers: (1) deploying the backend server, (2) building the Android app,
(3) sideloading it, and (4) the first-run settings and permissions you need to grant.

## 1. Server

Requirements: Docker + Docker Compose, and a reverse proxy that the compose file can
attach to (the shipped file uses an external Docker network called `proxy` with Traefik
labels — adapt to your own proxy, e.g. nginx, Caddy, or Cloudflare Tunnel + Access).

```bash
cd server

# 1. Environment: device token + public hostname (+ optional monitor token, see below)
cp .env.example .env
#   CALALARM_DEVICE_TOKEN=<long random string, e.g. python3 -c "import secrets;print(secrets.token_hex(32))">
#   CALALARM_MONITOR_TOKEN=<another long random string — optional, read-only, see below>
#   CALALARM_API_HOST=alarm-api.example.com
#   FORWARDED_ALLOW_IPS=<IP of your reverse proxy on the external 'proxy' docker network,
#                        e.g. 172.18.0.2 — find it with: docker network inspect proxy>

# 2. Runtime data directory (the ONLY host path mounted into the container)
mkdir -p data/logs
cp alarm_config.example.json data/alarm_config.json

# 3. Microsoft Graph credentials (see docs/BACKEND.md for how to obtain these):
#    data/ms_app_config.json  -> {"client_id": "...", "client_secret": "..."}
#    data/ms_token.json       -> OAuth token response incl. "refresh_token"

# 4. The container runs as user 10001 — make the data dir owned by it
docker run --rm -v "$PWD/data:/data" alpine:3 chown -R 10001:10001 /data
chmod 600 data/*.json

# 5. Start
docker compose up -d --build
```

Verify:

```bash
curl -s https://alarm-api.example.com/health
# -> {"status":"ok","service":"calendar-alarm-api"}
```

The container is hardened: non-root user (10001), read-only root filesystem, small tmpfs
for `/tmp`, **all capabilities dropped** (`cap_drop: ALL`), `no-new-privileges`, memory
limit 256 MB, PID limit 100, a pure-Python healthcheck, and only `./data` mounted
writable. The API listens on port 8100 inside the container; the reverse proxy routes the
public hostname to it (Traefik label example in `docker-compose.yml`, including a
rate-limit middleware as defense in depth).

### Monitor token & forwarded IPs

- **`CALALARM_MONITOR_TOKEN`** (optional) — a second, read-only bearer token accepted
  only on `GET /api/heartbeat/status`. Use it on an external monitoring host that should
  see the alarm verdict (does the phone have tomorrow's expected alarm?) without knowing
  the device token:

  ```bash
  curl -s -H "Authorization: Bearer $CALALARM_MONITOR_TOKEN" \
    https://alarm-api.example.com/api/heartbeat/status
  # -> {"verdict": "ok" | "missing" | "mismatch" | "stale" | ..., "expected_tomorrow": {...}, ...}
  ```

  It cannot post heartbeats or read/modify the config. Empty (default) = not configured.
- **`FORWARDED_ALLOW_IPS`** (required by compose) — the IP of your reverse proxy on the
  external `proxy` Docker network (an RFC1918 example: `172.18.0.2`). uvicorn only trusts
  `X-Forwarded-For` from this address, so the per-IP rate limit is not spoofable by
  clients that reach the container directly. Never set it to `*`.

## 2. Build the app

Requirements: JDK 17, Android SDK (compileSdk 35).

```bash
cd android
cp gradle.properties.template gradle.properties
```

Optionally set build-time properties in `android/gradle.properties` (never commit this
file) or via `-P` / `ORG_GRADLE_PROJECT_*` env vars — see
[docs/CONFIGURATION.md](docs/CONFIGURATION.md):

```properties
CALALARM_APPLICATION_ID=io.github.calendaralarm
CALALARM_API_URL=https://alarm-api.example.com
# CALALARM_UPDATE_SERVER_URL=https://updates.example.com   # enables the self-updater
# CALALARM_CERT_PINS=<base64 SPKI SHA-256 pins, comma-separated>
# KEYSTORE_PATH=/path/to/release.jks
# KEYSTORE_PASSWORD=...
# KEY_ALIAS=...
# KEY_PASSWORD=...
```

Build:

```bash
./gradlew :app:assembleDebug     # unsigned/debug build for testing
./gradlew :app:assembleRelease   # signed release (needs keystore properties)
```

APK output: `android/app/build/outputs/apk/{debug,release}/`.

Releases can also be built by CI: push a tag `vX.Y.Z` matching `versionName` in
`android/app/build.gradle.kts` and `.github/workflows/release.yml` builds a signed APK,
publishes the server image to GHCR, and creates a GitHub Release with `version.json` +
`SHA256SUMS`.

## 3. Sideload the APK

- Enable "Install unknown apps" for your browser/file manager, transfer the APK to the
  phone, and install it. Or use `adb install app-release.apk`.
- Updates installed this way must be signed with the **same key** as the installed build —
  the built-in updater verifies the downloaded APK's signer against a pinned fingerprint
  before offering the install.

## 4. First-run settings (in the app)

Open the app → **Settings**:

1. **Server URL** — the base URL of your backend, e.g. `https://alarm-api.example.com`
   (no trailing slash needed). Defaults to the build-time `CALALARM_API_URL`.
2. **Device token** — the same secret as `CALALARM_DEVICE_TOKEN` on the server. Sent as
   `Authorization: Bearer <token>` on every API call (never in the URL).
3. If the API is behind **Cloudflare Access**, also enter the service token
   **Client ID** and **Client Secret** — they are sent as `Cf-Access-Client-Id` /
   `Cf-Access-Client-Secret` headers.

Then press **Sync now** on the main screen — the app fetches tomorrow's plan from
`GET /api/alarm/next` and schedules the alarm.

## 5. Required permissions

Android gates several of these behind "special app access" — the app has buttons that open
the right system screen:

| Permission | Why | Where |
|---|---|---|
| **Alarms & reminders** (`SCHEDULE_EXACT_ALARM`) | Required on Android 12+ for `AlarmManager.setAlarmClock()` | Settings → Apps → Calendar Alarm → Special app access → Alarms & reminders → Allow |
| **Notifications** (`POST_NOTIFICATIONS`) | Android 13+ runtime permission for pre-alarm / sync-failed notifications | Asked at first launch |
| **Display over other apps** (`SYSTEM_ALERT_WINDOW`) | Needed on Samsung One UI for the overlay ringing activity | Settings → Apps → Calendar Alarm → Special app access → Display over other apps |
| **Full-screen intent** (`USE_FULL_SCREEN_INTENT`) | Lets the ringing activity appear over the lock screen (Android 14+) | Settings → Apps → Calendar Alarm → Special app access → Full-screen notifications (or "Appear on top"-related screen on some OEMs) |
| **Ignore battery optimizations** (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) | So background sync workers are not killed | Settings → Apps → Calendar Alarm → Battery → **Unrestricted** |

See [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) if alarms don't fire — on Samsung
One UI, battery optimization and full-screen intent are the two usual culprits.
