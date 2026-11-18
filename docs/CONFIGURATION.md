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
| `CALALARM_UPDATE_SERVER_URL` | Base URL of the self-hosted update feed (`version.json` + `releases/*.apk`). **Empty (default) = in-app updater disabled** | `https://updates.example.com` |
| `CALALARM_CERT_PINS` | Comma-separated **base64 SPKI SHA-256** certificate pins enforced on all HTTPS connections. Empty = normal system CA + hostname validation only | `dGhpcyBpcyBhIHBpbg...` |
| `CALALARM_UPDATE_SIGNER_SHA256` | Hex SHA-256 of your release signing certificate (`keytool -list -v`), extra pin for in-app updates. Empty = only the "same signer as installed app" check | `9c7e…` (64 hex) |
| `KEYSTORE_PATH` | Path to the release signing keystore (`.jks`). Required for `assembleRelease` | `/path/to/release.jks` |
| `KEYSTORE_PASSWORD` | Keystore password | — |
| `KEY_ALIAS` | Key alias inside the keystore | `release` |
| `KEY_PASSWORD` | Key password | — |

Example `android/gradle.properties`:

```properties
CALALARM_APPLICATION_ID=io.github.calendaralarm
CALALARM_API_URL=https://alarm-api.example.com
CALALARM_UPDATE_SERVER_URL=https://updates.example.com
# CALALARM_CERT_PINS=AAAA...,BBBB...
KEYSTORE_PATH=/path/to/release.jks
KEYSTORE_PASSWORD=...
KEY_ALIAS=release
KEY_PASSWORD=...
```

To compute an SPKI pin from a certificate:

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
| `CALALARM_API_HOST` | yes (compose) | Public hostname the reverse proxy routes to this API (Traefik `Host(...)` rule) | `alarm-api.example.com` |
| `CALALARM_WORKSPACE` | no | Data directory inside the container; compose sets `/data` | `/data` |
| `CALALARM_MS_TOKEN_FILE` | no | File (relative to `CALALARM_WORKSPACE`) with Microsoft Graph OAuth tokens incl. `refresh_token` | `ms_token.json` |
| `CALALARM_MS_CONFIG_FILE` | no | File with `{"client_id": ..., "client_secret": ...}` for the Microsoft app registration | `ms_app_config.json` |
| `CALALARM_CONFIG_FILE` | no | File with the alarm keyword rules (see `alarm_config.example.json`) | `alarm_config.json` |

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
