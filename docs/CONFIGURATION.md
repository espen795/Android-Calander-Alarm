# Configuration reference

All deployment-specific values are injected at **build time** (app) or **runtime** (server).
Nothing personal is hardcoded in the repo; all examples below use `example.com`.

## Android app — Gradle properties

Set these in `android/gradle.properties` (copy from `gradle.properties.template`; never
commit the real file), or pass as `-PCALALARM_...=...` / `ORG_GRADLE_PROJECT_CALALARM_...`
environment variables (this is what `.github/workflows/release.yml` uses).

| Property | Effect | Example |
|---|---|---|
| `CALALARM_APPLICATION_ID` | Android `applicationId` (namespace is always `io.github.calendaralarm`) | `io.github.calendaralarm` |
| `CALALARM_API_URL` | Default backend base URL, shown on first start (changeable in app Settings) | `https://alarm-api.example.com` |
| `CALALARM_UPDATE_SERVER_URL` | Base URL of the self-hosted update feed (`version.json` + `releases/*.apk`). **Must be set together with `CALALARM_UPDATE_SIGNER_SHA256` — otherwise the in-app updater stays disabled (fail-closed)** | `https://updates.example.com` |
| `CALALARM_CERT_PINS` | Comma-separated **base64 SPKI SHA-256** certificate pins enforced on all HTTPS connections (sync, log upload, heartbeat, updates). **Empty (default) = the built-in public-CA root pin set `ACTIVE_PINS`** (Let's Encrypt/ISRG, Google Trust Services, SSL.com, GlobalSign roots) — set this only to pin a **private CA** instead | `dGhpcyBpcyBhIHBpbg...` |
| `CALALARM_UPDATE_SIGNER_SHA256` | Hex SHA-256 of your release signing certificate (`keytool -list -v`; colons allowed). The in-app updater only runs when this is a valid 64-hex value **and** `CALALARM_UPDATE_SERVER_URL` is set; every downloaded APK is verified against it | `9c7e…` (64 hex) |
| `KEYSTORE_PATH` | Path to the release signing keystore (`.jks`). Required for `assembleRelease` | `/path/to/release.jks` |
| `KEYSTORE_PASSWORD` | Keystore password | — |
| `KEY_ALIAS` | Key alias inside the keystore | `release` |
| `KEY_PASSWORD` | Key password | — |

Example `android/gradle.properties`:

```properties
CALALARM_APPLICATION_ID=io.github.calendaralarm
CALALARM_API_URL=https://alarm-api.example.com
CALALARM_UPDATE_SERVER_URL=https://updates.example.com
CALALARM_UPDATE_SIGNER_SHA256=9c7e...(64 hex chars of your release cert)
# CALALARM_CERT_PINS=AAAA...,BBBB...   # only for a private CA; empty = built-in public-CA pins
KEYSTORE_PATH=/path/to/release.jks
KEYSTORE_PASSWORD=...
KEY_ALIAS=release
KEY_PASSWORD=...
```

### Certificate pinning behaviour (`ACTIVE_PINS`)

- Every HTTPS connection (sync, heartbeat, log upload, update feed) uses a **per-host**
  trust manager: PKIX validation against the system trust store + **mandatory hostname
  verification** (SAN/CN, exact match or a single left-most wildcard label, fail-closed)
  + SPKI SHA-256 pinning against the active pin set.
- The default pin set (`CertificatePins.ACTIVE_PINS` when `CALALARM_CERT_PINS` is empty)
  pins the **root certificates** of all public CA families a typical edge certificate may
  chain to — Let's Encrypt (ISRG Root X1/X2), Google Trust Services (WE1 + Roots R1–R4),
  SSL.com (4 roots) and GlobalSign Root CA — so a CA rotation at your edge provider does
  not break sync, log upload and updates at the same time. The leaf certificate is never
  pinned (it rotates on every renewal).
- `CALALARM_CERT_PINS` **replaces** the whole set — use it to pin a private/internal CA
  instead of the public roots.
- Only the **update channel** has a recovery path: on a pure pin mismatch (never on a
  hostname error or a network error) `version.json` and the APK download fall back to
  system trust without pinning (still with hostname verification). The APK is then still
  verified by SHA-256, exact size and the pinned signer certificate. Sync, heartbeat and
  log upload never fall back — they fail closed.
- Reproduce/verify pin values with `tools/compute_pins.sh` (live chain + local root CAs;
  `--offline` for roots only, `--host HOST` for another host).

To compute a single SPKI pin from a certificate:

```bash
openssl x509 -in cert.pem -pubkey -noout \
  | openssl pkey -pubin -outform DER \
  | openssl dgst -sha256 -binary | base64 -w0
```

### Runtime settings (in the app, stored securely)

These are **not** build-time; they live in the app's Settings screen and are stored in
`EncryptedSharedPreferences` (Android Keystore):

| Setting | Meaning |
|---|---|
| Server URL | Overrides `CALALARM_API_URL` at runtime |
| Device token | Shared secret with the server; sent as `Authorization: Bearer <token>` |
| Cloudflare Access Client ID / Secret | Sent as `Cf-Access-Client-Id` / `Cf-Access-Client-Secret` headers when set |

Non-sensitive settings (snooze minutes, wake-time defaults, "freeze" window, update
check interval) are kept in regular `SharedPreferences` for reliability on some devices.

## Server — environment variables

Set in `server/.env` (copy from `.env.example`; never commit) — consumed by
`docker-compose.yml` — or directly in the container environment.

| Variable | Required | Meaning | Example |
|---|---|---|---|
| `CALALARM_DEVICE_TOKEN` | yes | Bearer token the app must present on every `/api/*` call | `secrets.token_hex(32)` output |
| `CALALARM_MONITOR_TOKEN` | no | **Read-only** token accepted only on `GET /api/heartbeat/status` (constant-time compare). Empty (default) = not configured. Lets a monitoring host check the alarm verdict without the device token | `secrets.token_hex(32)` output |
| `CALALARM_API_HOST` | yes (compose) | Public hostname the reverse proxy routes to this API (Traefik `Host(...)` rule) | `alarm-api.example.com` |
| `CALALARM_WORKSPACE` | no | Data directory inside the container; compose sets `/data`. The heartbeat is stored at `<workspace>/heartbeat.json`, cached plans at `<workspace>/plans/<date>.json` (both mode 600) | `/data` |
| `CALALARM_MS_TOKEN_FILE` | no | File (relative to `CALALARM_WORKSPACE`) with Microsoft Graph OAuth tokens incl. `refresh_token` | `ms_token.json` |
| `CALALARM_MS_CONFIG_FILE` | no | File with `{"client_id": ..., "client_secret": ...}` for the Microsoft app registration | `ms_app_config.json` |
| `CALALARM_CONFIG_FILE` | no | File with the alarm keyword rules (see `alarm_config.example.json`) | `alarm_config.json` |
| `FORWARDED_ALLOW_IPS` | yes (compose) | Comma-separated IPs uvicorn trusts for `X-Forwarded-For` — set to the **IP of your reverse proxy on the external `proxy` Docker network** (e.g. an RFC1918 address like `172.18.0.2`, `docker network inspect proxy`). Never `*`: the container must only trust headers from your proxy, not from clients that reach it directly | `172.18.0.2` (example) |

### Alarm rules file (`alarm_config.json`)

```json
{
  "rules": [
    { "id": "office", "name": "Office day", "time": "07:00",
      "keywords": ["office", "on site"] },
    { "id": "home", "name": "Work from home", "time": "08:30",
      "keywords": ["home office", "remote"] },
    { "id": "off", "name": "Day off", "time": "09:00",
      "keywords": ["vacation", "day off", "sick"] }
  ],
  "default_rule_id": "home",
  "weekend_rule_id": ""
}
```

- `id`: lowercase `^[a-z0-9_-]{1,32}$`, unique, max 20 rules.
- `time`: `HH:mm` (24 h). A rule with `"time": null` means "no alarm".
- `keywords`: up to 50 per rule, matched case-insensitively as substrings against the
  event subject, body, location and categories. All-day events matching the `off` rule
  win immediately.
- `default_rule_id`: used on weekdays when no event matches any rule. May be empty.
- `weekend_rule_id`: used on Sat/Sun when nothing matches. **Empty = no alarm on
  weekends** (`wake_time: null` in the API response).

The file is validated server-side on every `POST /api/config` (max 100 KB payload) and
stored with mode `600`. The same config can be edited from the app (Server Rules screen).

## Reverse proxy

`server/docker-compose.yml` assumes:

- an external Docker network named `proxy`, and
- Traefik routing `Host(`alarm-api.example.com`)` → container port `8100`,

plus a Traefik rate-limit middleware (average 20, burst 30 req/min) on top of the app's
own limit of 10 req/min per IP **and** per device token. Adapt the labels to your proxy
of choice. Termination of TLS happens at the proxy (or at Cloudflare, if you front the API
with Cloudflare Access/Tunnel).

The compose file also hardens the container: non-root user (10001), read-only rootfs,
all capabilities dropped (`cap_drop: ALL`), `no-new-privileges`, memory limit 256 MB,
PID limit 100, and a pure-Python healthcheck against `http://127.0.0.1:8100/health`
(no curl in the image). Set `FORWARDED_ALLOW_IPS` to your proxy's IP on the `proxy`
network so uvicorn only honours `X-Forwarded-For` from that proxy.
