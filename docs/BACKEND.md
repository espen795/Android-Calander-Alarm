# Calendar Alarm — Backend Documentation

This document describes the current server integration of the Calendar Alarm
backend (`server/main.py`, FastAPI) end to end: how to register the Microsoft
Entra application, how to obtain the initial OAuth token **without** any
external personal assistant platform, how tokens are refreshed, the on-disk
file formats, the rule-matching algorithm, and how to deploy the service
behind a reverse proxy.

Everything in this document works standalone: the only prerequisite is a
Microsoft account with a calendar (Outlook.com, Microsoft 365 work/school).

---

## 1. Architecture overview

```
┌──────────────┐   HTTPS pull (Bearer device token)   ┌───────────────────────┐
│  Android app │  ───────────────────────────────────▶ │  calendar-alarm-api      │
│  (WorkManager│   GET /api/alarm/next  (~21:00 daily) │  FastAPI :8100        │
│   AlarmSync- │ ◀───────────────────────────────────  │  ├─ refresh MS token  │
│   Worker)    │   {date, wake_time, label, ...}       │  ├─ Graph calendarview│
└──────────────┘                                       │  └─ detect_rule()     │
                                                       └──────────┬────────────┘
                                                                  │ HTTPS
                                                                  ▼
                                                   login.microsoftonline.com / graph.microsoft.com
```

The app only ever **pulls**; the server never needs inbound connectivity from
the internet to the phone. The server reads *tomorrow's* calendar events from
Microsoft Graph, matches event text against user-editable keyword rules, and
returns the wake time for tomorrow.

Server endpoints (all except `/health` require `Authorization: Bearer <device token>`):

| Method | Path               | Purpose                                             |
|--------|--------------------|-----------------------------------------------------|
| GET    | `/health`          | Liveness probe (no auth)                            |
| GET    | `/api/alarm/next`  | Wake plan for tomorrow (`?date=YYYY-MM-DD` optional)|
| GET    | `/api/config`      | Current alarm rules config                          |
| POST   | `/api/config`      | Replace alarm rules config (validated, max 100 KB)  |
| POST   | `/api/log`         | Upload device log batch (sanitized, max 1 MB)       |

---

## 2. Azure / Entra app registration (step by step)

1. Go to the **Microsoft Entra admin center** → *Identity → Applications →
   App registrations → New registration*.
   - **Name**: e.g. `Calendar Alarm` (any name; it is not shown to end users).
   - **Supported account types**: pick one:
     - *Personal Microsoft accounts only* — for an Outlook.com calendar.
     - *Accounts in any organizational directory and personal Microsoft
       accounts* ("common") — for both work/school and personal.
     - A specific tenant GUID — if you only want one organization.
   - **Redirect URI**: for the device-code bootstrap (section 3) **no redirect
     URI is required**. If you use the authorization-code flow instead, add a
     *Mobile and desktop applications* redirect of `http://localhost` (or
     `http://localhost:8080`).
2. After creation, open the app → **Authentication**:
   - Under *Advanced settings*, set **Allow public client flows** to **Yes**.
     This is required for the OAuth 2.0 **device-code flow** used by
     `tools/ms_device_login.py` (a "public client" has no client secret).
   - If you prefer a **confidential client** (server-side secret), instead add
     a *client secret* (Certificates & secrets → New client secret) and keep
     "Allow public client flows" = No; the secret is then sent on every token
     refresh (see section 4).
3. Open **API permissions → Add a permission → Microsoft Graph →
   Delegated permissions** and add:
   - `Calendars.Read` — read the user's calendar (read-only; the app never
     writes to the calendar).
   - `offline_access` — required to receive a `refresh_token`, without which
     the server cannot run unattended.
   - Grant admin consent if you use an organizational tenant (for personal
     accounts the user consents interactively during sign-in).
4. Note the **Application (client) ID** from the *Overview* page — this is the
   `client_id` used everywhere below.

> Multi-tenant note: `main.py` and `ms_device_login.py` use tenant `common`
> by default (`--tenant` / the login URL). For a single organization you can
> pin the tenant GUID instead.

---

## 3. Obtaining the initial token file (no external platform needed)

The server needs two files in its data dir (`/data` in the container):

- `ms_app_config.json` — the app registration (`client_id`, optional
  `client_secret`, optional `redirect_uri`).
- `ms_token.json` — the OAuth tokens, produced by a one-time interactive
  sign-in.

### 3.1 Recommended: device-code flow (public client, no secret)

Run the bundled helper on any machine with Python 3 (standard library only):

```bash
python3 server/tools/ms_device_login.py \
    --client-id "11111111-2222-3333-4444-555555555555" \
    --tenant common \
    --out server/data/ms_token.json
```

The script:

1. POSTs to `https://login.microsoftonline.com/{tenant}/oauth2/v2.0/devicecode`
   with scopes `offline_access Calendars.Read`.
2. Prints a **user code** and a **verification URL**
   (usually `https://microsoft.com/devicelogin`). Open the URL, enter the
   code, and sign in with the calendar account.
3. Polls `.../oauth2/v2.0/token` with
   `grant_type=urn:ietf:params:oauth:grant-type:device_code` until the user
   completes sign-in (handles `authorization_pending` / `slow_down`).
4. Writes the token JSON to `--out` **atomically with mode 600**
   (write to `.tmp`, `chmod 600`, rename).

Requires **Allow public client flows = Yes** (section 2, step 2). Because a
public client has no secret, `ms_app_config.json` only needs `client_id` —
and `main.py` treats `client_secret` as optional (section 4).

### 3.2 Alternative: authorization-code flow (confidential client)

If you registered a **web** app with a client secret, you can instead run the
classic auth-code flow (redirect URI `http://localhost:8080`) with any OAuth
client, and save the resulting token JSON in the same format. The server does
not care how the initial token was obtained — only the file format matters.

### 3.3 `ms_app_config.json`

```json
{
  "client_id": "11111111-2222-3333-4444-555555555555",
  "client_secret": "<your-client-secret>",
  "redirect_uri": "http://localhost:8080"
}
```

- `client_id` — required, the Application (client) ID from section 2.
- `client_secret` — **optional since v2.1.7**. Omit it (or set it to `""`) for
  a public client / device-code flow; include it for a confidential client.
- `redirect_uri` — optional, defaults to `http://localhost:8080` in
  `main.py`; only used as a parameter on the refresh request.

### 3.4 `ms_token.json` (written by `ms_device_login.py` or any OAuth client)

```json
{
  "token_type": "Bearer",
  "scope": "Calendars.Read offline_access",
  "expires_in": 3600,
  "ext_expires_in": 3600,
  "access_token": "<access-token>",
  "refresh_token": "<refresh-token>",
  "expires_at": 1763400000
}
```

Only `refresh_token` and `access_token` are read by `main.py`; the rest is
kept as returned by Entra so the file is a standard OAuth token dump.

**Permissions**: both files must be mode `600` and owned by the container user
(UID 10001 — see `server/data/README.md` for the one-time `chown`). `main.py`
sets `umask 077` and re-`chmod 600`s the token file on every refresh, and the
startup hook prints a warning if either file is group/world-readable.

---

## 4. Token refresh behaviour

On **every** `GET /api/alarm/next` request the server calls
`refresh_microsoft_token()`:

1. Reads `ms_app_config.json` and `ms_token.json` (503 if either is missing or
   has no `refresh_token`).
2. POSTs a `refresh_token` grant to
   `https://login.microsoftonline.com/common/oauth2/v2.0/token` with
   `client_id`, `refresh_token`, `grant_type=refresh_token`, `redirect_uri`,
   and — **only if present and non-empty** — `client_secret`. This is the only
   change vs. earlier versions: `client_secret` is now optional, so both
   public clients (device-code bootstrap) and confidential clients (secret)
   work.
3. On success the **whole response** is persisted back to
   `ms_token.json` via `save_token_secure()` (write + `chmod 600`), so the new
   `refresh_token` (Entra rotates it) is kept for the next run.
4. Any network/HTTP error → `503 Calendar service unavailable`; a response
   without `access_token` → the same 503. The endpoint never leaks token
   material in the response body.

`get_access_token()` then reads `access_token` from the file, and
`get_calendar_events()` calls
`GET https://graph.microsoft.com/v1.0/me/calendarview?startDateTime=...&endDateTime=...`
(UTC window covering the target day) with
`Authorization: Bearer <access_token>` and
`Prefer: outlook.timezone="Europe/Amsterdam"`.

> The timezone is currently hard-coded to `Europe/Amsterdam` in
> `main.py` (both for "tomorrow" and for the `Prefer` header). Adjust there
> if you need another zone.

---

## 5. `alarm_config.json` — rule file format

Start from `server/alarm_config.example.json`:

```json
{
  "rules": [
    {
      "id": "office",
      "name": "Office day",
      "time": "07:00",
      "keywords": ["office", "on-site", "at the office"]
    },
    {
      "id": "home",
      "name": "Working from home",
      "time": "08:30",
      "keywords": ["home office", "remote", "thuis"]
    },
    {
      "id": "off",
      "name": "Day off",
      "time": "09:00",
      "keywords": ["vacation", "day off", "sick"]
    }
  ],
  "default_rule_id": "home",
  "weekend_rule_id": "off"
}
```

- `rules[]` — 1..20 rules. Each has a unique `id` (`^[a-z0-9_-]{1,32}$`),
  a `name` (≤ 50 chars, shown in the app), a `time` in `HH:mm` 24-hour format,
  and up to 50 `keywords` (each ≤ 50 chars, lower-cased on save).
- `default_rule_id` — used on weekdays when **no** event matches any keyword.
  May be empty: then no alarm is set.
- `weekend_rule_id` — used on Sat/Sun when nothing matches. Empty = **no
  alarm on weekends**.

The file is managed at runtime via `GET/POST /api/config`; `save_alarm_config()`
validates everything above, writes atomically (`.tmp` + rename) with mode 600.
If the file is missing or invalid, `DEFAULT_ALARM_CONFIG` in `main.py` is used.

---

## 6. Rule matching — `detect_rule()` algorithm

Inputs: the raw Graph `calendarview` events for the target day, the target
`datetime`, and the loaded config. Returns `(rule_dict, confidence)`.

1. **Day window**: `day_start` = target date 00:00, `day_end` = +24 h.
2. **Filtering** — an event is skipped when any of these hold:
   - `isCancelled == true` or `status == "cancelled"` (Graph flags), or
   - the literal substring `"geannuleerd"` (Dutch for "cancelled") appears in
     the subject, or
   - `start.dateTime` is missing.
   - Otherwise the event counts if its start date falls within the target day.
3. **All-day "off" shortcut**: any *all-day* event whose subject contains a
   keyword of the rule with `id == "off"` wins immediately → `(off_rule, "high")`.
4. **Scoring**: for every remaining rule, count how many day events contain
   any of the rule's keywords in the concatenation
   `subject + body.content + location.displayName + categories` (all
   lower-cased). `score` = number of matching events (one event can only add 1
   per rule, since it's a per-event boolean check per keyword... precisely: a
   `score += 1` happens per (rule, event, keyword) hit, so multiple keywords of
   the same rule in one event can each add 1).
5. **Best rule**: highest score wins; ties are broken by hard-coded priority
   `off (3) > office (2) > home (1) > other (0)`. Confidence: `"high"` if
   score > 1, else `"medium"`.
6. **No match at all (`best_score == 0`)**: fall back to `weekend_rule_id` on
   Sat/Sun (empty → no alarm, returned as a synthetic
   `{"id": "none", "time": None}` with confidence `"none"`) or
   `default_rule_id` on weekdays, with confidence `"low"`. If the referenced
   rule doesn't exist, also no alarm.

Response of `GET /api/alarm/next`:

```json
{
  "date": "2026-11-19",
  "wake_time": "07:00",
  "label": "Office day",
  "work_location": "office",
  "confidence": "high",
  "event_count": 4
}
```

`wake_time: null` means "no alarm tomorrow" (the app then cancels any existing
alarm for that date). Event subjects are **never** returned or logged — only
counts.

---

## 7. Deployment — Docker Compose

```bash
cd server
cp .env.example .env          # set CALALARM_DEVICE_TOKEN + CALALARM_API_HOST
python3 -c "import secrets;print(secrets.token_hex(32))"   # generate the token
# edit .env, then:
mkdir -p data/logs
cp alarm_config.example.json data/alarm_config.json
# app registration (section 2) + initial token (section 3):
python3 tools/ms_device_login.py --client-id "<app-id>" --out data/ms_token.json
cat > data/ms_app_config.json <<'EOF'
{ "client_id": "<app-id>" }
EOF
docker run --rm -v "$PWD/data:/data" alpine:3 chown -R 10001:10001 /data
chmod 600 data/*.json
docker compose up -d
```

- `docker-compose.yml` runs the image as **non-root user `10001:10001`**,
  `read_only: true` rootfs, tmpfs `/tmp`, and mounts **only** `./data` as
  `/data` (`CALALARM_WORKSPACE=/data`). No host paths besides `./data` are
  visible inside the container.
- `CALALARM_DEVICE_TOKEN` is the shared secret between app and server
  (generate with `secrets.token_hex(32)`; enter the same value in the app).
- The container joins the external `proxy` Docker network; no host ports are
  published. `curl` from inside:
  `curl -H "Authorization: Bearer $CALALARM_DEVICE_TOKEN" http://localhost:8100/...`
  won't work from the host (nothing published) — test via the proxy host or
  `docker exec`.

### Environment variables

| Variable                     | Default            | Meaning                                  |
|------------------------------|--------------------|------------------------------------------|
| `CALALARM_WORKSPACE`            | `/data`            | Data dir (all state lives here)          |
| `CALALARM_DEVICE_TOKEN`   | (empty = deny all) | Bearer token required by the app         |
| `CALALARM_MS_TOKEN_FILE`        | `ms_token.json`    | Token file name inside the workspace     |
| `CALALARM_MS_CONFIG_FILE`       | `ms_app_config.json` | App config file name                   |
| `CALALARM_CONFIG_FILE`    | `alarm_config.json`| Rules file name                          |

---

## 8. Reverse proxy

### 8.1 Traefik (as shipped in `docker-compose.yml`)

The compose file attaches these labels to the service (network `proxy`,
external):

```
traefik.enable=true
traefik.docker.network=proxy
traefik.http.routers.calendar-alarm-api.rule=Host(`alarm-api.example.com`)
traefik.http.routers.calendar-alarm-api.entrypoints=web
traefik.http.routers.calendar-alarm-api.priority=25
traefik.http.services.calendar-alarm-api.loadbalancer.server.port=8100
traefik.http.middlewares.calendar-alarm-api-ratelimit.ratelimit.average=20
traefik.http.middlewares.calendar-alarm-api-ratelimit.ratelimit.burst=30
traefik.http.routers.calendar-alarm-api.middlewares=calendar-alarm-api-ratelimit
```

(`CALALARM_API_HOST` from `.env` fills in the hostname — use your own domain,
e.g. `alarm-api.example.com`.) Point the domain's DNS at the proxy host and
issue a certificate via your Traefik entrypoint (e.g. Let's Encrypt
resolver on `websecure`); add `entrypoints=websecure` + `tls=true` /
`tls.certresolver=...` if you terminate TLS in Traefik.

### 8.2 Any other proxy (nginx example)

```nginx
server {
    listen 443 ssl;
    server_name alarm-api.example.com;

    location / {
        proxy_pass http://calendar-alarm-api:8100;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        # The app rate-limits itself (10 req/min per IP and per token),
        # but a proxy-level limit is cheap defense in depth:
        limit_req zone=calalarm burst=30 nodelay;
    }
}
```

Any reverse proxy works as long as it forwards `Authorization` and
`X-Forwarded-For` (the app uses the client IP for rate limiting) and does not
buffer/limit the small JSON bodies. The API speaks plain HTTP on port 8100
inside the Docker network — TLS should terminate at the proxy.

### 8.3 Optional: Cloudflare Access in front

If the API is published through Cloudflare, put a **Cloudflare Access
application** in front of `alarm-api.example.com` (service token or Access
for SaaS). The Android app then additionally sends
`Cf-Access-Client-Id` / `Cf-Access-Client-Secret` headers (configured in the
app's settings; see `SyncRepository.addHeaders()`). This is optional — the
device token alone is sufficient when the host is not publicly exposed.

---

## 9. Security model

- **Device token auth**: every API endpoint (except `/health`) requires
  `Authorization: Bearer <CALALARM_DEVICE_TOKEN>`. The token is compared in
  constant time (`validate_token()`), never appears in URLs (K-03), and is
  stripped from any log line that reaches `POST /api/log` (literal replace +
  regex for 64-char hex tokens + Cloudflare `cfast_` service-token secrets).
- **Rate limiting**: 10 req/min per client IP **and** per device token
  (429 responses), plus the Traefik middleware as a second layer.
- **Payload limits / content-type checks**: `POST /api/config` max 100 KB,
  `POST /api/log` max 1 MB, both require `application/json` (415 otherwise);
  log batches are capped at 500 entries × 1000 chars.
- **Data-dir isolation**: all state (`ms_token.json`, `ms_app_config.json`,
  `alarm_config.json`, `logs/`) lives under `CALALARM_WORKSPACE` (`/data`), the
  only bind mount; `os.umask(0o077)` makes new files owner-only, tokens are
  re-`chmod 600` after every refresh, and the startup hook warns on
  group/world-readable token files. Config writes are atomic (`.tmp` +
  rename).
- **Container hardening**: non-root UID 10001, `read_only` rootfs, tmpfs
  `/tmp` (`noexec,nosuid`), no published host ports, internal `proxy` network
  only.
- **HTTPS only** externally (proxy/TLS); optional Cloudflare Access in front.
- **Least privilege Graph scope**: `Calendars.Read` only (read-only) +
  `offline_access`. No write scopes, no mail, no profile data.
- **Privacy**: the API never returns or logs calendar subjects —
  `detect_rule()` deliberately returns only rule id/name and match counts.
- **Operational hygiene**: `data/` is git-ignored; `tools/sanitize-check.sh`
  (run in CI) rejects key material, private IPs, real secrets and
  `client_secret` values in committed files.

---

## 10. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `503 Calendar not configured` | `ms_token.json` or `ms_app_config.json` missing in `/data`, or no `refresh_token` in the token file → re-run `tools/ms_device_login.py`. |
| `503 Calendar service unavailable` | Refresh failed: expired/revoked refresh token (re-consent needed), wrong `client_id`, network blocked, or missing `Calendars.Read`/`offline_access` grant. |
| `401` from the app | Device token mismatch between `.env` and the app; or missing/malformed `Authorization: Bearer` header. |
| `429` | Rate limit (10/min per IP and per token) — the app retries with backoff. |
| `403` through Cloudflare | Access policy blocks the request; check the service-token credentials in the app. |
| Alarm always falls back to default rule | No keyword matched: check the subjects/keywords (all-day `off` events short-circuit; cancelled events are ignored). |
