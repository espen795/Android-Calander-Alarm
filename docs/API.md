# API reference

Base URL: the server URL configured in the app (e.g. `https://alarm-api.example.com`).
All endpoints except `/health` require the header:

```
Authorization: Bearer <CALALARM_DEVICE_TOKEN>
```

The token is never accepted as a URL parameter. Responses are JSON.

There is also an optional **read-only monitor token** (`CALALARM_MONITOR_TOKEN`, see
[docs/CONFIGURATION.md](CONFIGURATION.md)): when set, it is accepted on
`GET /api/heartbeat/status` **only** — it cannot post heartbeats or read/modify config,
so an external monitoring host never needs the device token.

## Limits & errors

- **Rate limit**: max **10 requests/minute per client IP** and **10/minute per device
  token** → `429 {"detail": "Rate limited. Max 10 req/min."}`. (With a reverse proxy in
  front, the per-IP limit is effectively per proxy unless the proxy IP is listed in
  `FORWARDED_ALLOW_IPS`; the per-token limit is always per device.)
- Invalid/missing token → `401`; malformed `Authorization` header → `401`.
- POST endpoints require `Content-Type: application/json` → `415` otherwise.
- Payload limits: `POST /api/config` max **100 KB**, `POST /api/log` max **1 MB**,
  `POST /api/heartbeat` max **64 KB** → `413`. Invalid JSON → `400`.
- Calendar backend unreachable/not configured → `503` (unless a cached plan exists —
  see `GET /api/alarm/next`).

## `GET /health`

Liveness probe. No auth.

```json
{"status": "ok", "service": "calendar-alarm-api"}
```

## `GET /api/alarm/next`

Returns tomorrow's alarm plan (or another day via `?date=`).

Query parameters:

| Param | Description |
|---|---|
| `date` | optional, `YYYY-MM-DD`; default = tomorrow (server timezone Europe/Amsterdam) |

Response `200` when an alarm applies:

```json
{
  "date": "2026-11-19",
  "wake_time": "07:00",
  "label": "Office day",
  "work_location": "office",
  "confidence": "high",
  "event_count": 3
}
```

Response `200` when **no alarm** is planned (e.g. weekend without a weekend rule) —
`wake_time` is `null` and the app cancels that day's alarms:

```json
{
  "date": "2026-11-22",
  "wake_time": null,
  "label": "No alarm",
  "work_location": "none",
  "confidence": "none",
  "event_count": 0
}
```

Fields: `wake_time` is `HH:mm` (24 h) or `null`; `label` is the rule's display name;
`work_location` is the rule id; `confidence` is one of `high` / `medium` / `low` / `none`
(`high` = ≥2 keyword matches or an all-day "off" event; `medium` = 1 match; `low` =
fallback rule). `source` is `calendar` on a live fetch or `cache` when a last-known-good
cached plan is returned because the calendar backend failed. Event subjects are never
included — only the count.

`400` on a malformed `date`.

## `GET /api/config`

Returns the current alarm rules config (the same structure as `alarm_config.json`):

```json
{
  "rules": [
    {
      "id": "office",
      "name": "Office day",
      "time": "07:00",
      "keywords": ["office", "on site"]
    }
  ],
  "default_rule_id": "home",
  "weekend_rule_id": ""
}
```

## `POST /api/config`

Replaces the whole config. The server validates before persisting (see
[docs/CONFIGURATION.md](CONFIGURATION.md) for the schema and limits) and responds:

```json
{"status": "ok", "rules": 3}
```

Validation failures → `400 {"detail": "<reason>"}` (e.g. invalid `HH:mm` time, duplicate
rule id, unknown `default_rule_id`, more than 20 rules, payload over 100 KB → `413`).

## `POST /api/log`

Uploads a batch of in-app log entries. The server redacts the device token, any 64-char
hex string and Cloudflare `cfast_*` secrets, truncates fields, and appends to
`logs/calendar-alarm-device.log` (rotated to `.log.old` at 5 MB).

Request:

```json
{
  "entries": [
    {"timestamp": "2026-11-18T07:30:00+01:00", "level": "INFO", "tag": "AlarmSyncWorker", "message": "Sync OK: 07:00 on 2026-11-19"}
  ]
}
```

Limits: max **500 entries** per request (`400` otherwise), max **1 MB** body (`413`),
each line max 1000 chars, level max 10 chars, tag max 50 chars, timestamp max 30 chars.

Response:

```json
{"received": 1}
```

## `POST /api/heartbeat`

The app sends this every 3 hours (WorkManager, network connected) to report which alarms
are currently scheduled on the phone plus the permission status. The payload is
validated strictly (pydantic); on success the whole payload is stored atomically in
`/data/heartbeat.json` (tmp + rename, mode 600). Alarm **labels are accepted but never
stored** — the status endpoint does not use them.

Request (device token required):

```json
{
  "app_version": "2.8.0",
  "version_code": 66,
  "sent_at": "2026-11-23T18:00:00Z",
  "scheduled": [
    {"date": "2026-11-24", "time": "07:00", "label": "Office day",
     "enabled": true, "is_snooze": false, "user_disabled": false}
  ],
  "permissions": {
    "notifications": true,
    "full_screen": true,
    "exact_alarms": true,
    "battery_unrestricted": true
  }
}
```

Validation rules: `sent_at` must be ISO-8601 (e.g. `2026-11-23T18:00:00Z`), `date` must
be a real `YYYY-MM-DD`, `time` must be `HH:MM` (00:00–23:59), max **50** entries in
`scheduled`, max **100** chars per label (control characters stripped), max **64 KB**
body. Invalid payload → `422` with the offending **field names only** (never the input
values):

```json
{"detail": {"error": "Invalid heartbeat payload", "fields": ["scheduled.0.date", "sent_at"]}}
```

Response:

```json
{"status": "ok", "received_at": "2026-11-23T18:00:01Z", "scheduled": 1}
```

## `GET /api/heartbeat/status`

Monitoring endpoint: compares the last heartbeat with the cached plan for **tomorrow**
(server timezone Europe/Amsterdam) and returns a verdict. Accepts the device token or
the read-only `CALALARM_MONITOR_TOKEN` (constant-time compare; empty = not configured).

```json
{
  "last_heartbeat_at": "2026-11-23T18:00:01Z",
  "age_seconds": 120,
  "expected_tomorrow": {"date": "2026-11-24", "wake_time": "07:00", "source": "calendar"},
  "phone_has": [{"time": "07:00", "enabled": true, "is_snooze": false, "user_disabled": false}],
  "verdict": "ok",
  "details": "Phone has alarm 07:00 on 2026-11-24 — matches the plan"
}
```

`verdict` is one of (checked in this priority order):

| Verdict | Meaning |
|---|---|
| `stale` | no heartbeat received, or the last one is older than **8 h** (or has no valid timestamp) |
| `permissions` | critical permissions missing on the phone (`notifications`, `full_screen`, `exact_alarms`) — takes precedence over ok/missing/mismatch |
| `no_plan` | no cached plan for tomorrow yet (the app has not fetched it) |
| `ok` | the plan expects no alarm, or the phone has an enabled, non-snooze alarm on that date at the planned time |
| `user_disabled` | the user deliberately disabled tomorrow's planned alarm (a conscious choice — reported quietly) |
| `pending` | the cached plan is newer than the last heartbeat — the phone may not have processed it yet |
| `missing` | the plan expects an alarm at `wake_time`, but the phone has no enabled alarm for that date |
| `mismatch` | the phone has an enabled alarm for that date, but at a different time than planned |

`phone_has` never contains labels (free text) — only time/enabled/snooze/user_disabled.
`details` is a human-readable English explanation. A heartbeat older than 8 h always
yields `stale`; client/server clock differences are clamped, not treated as stale.

## Error summary

| Code | Meaning |
|---|---|
| `400` | Bad request (invalid JSON, invalid `date`, failed config validation, too many log entries) |
| `401` | Missing/invalid bearer token |
| `413` | Payload too large (config > 100 KB, logs > 1 MB, heartbeat > 64 KB) |
| `422` | Heartbeat payload failed validation (response lists the invalid field names) |
| `415` | POST without `Content-Type: application/json` |
| `429` | Rate limited (10/min per IP and per token) |
| `500` | Server-side failure writing config/logs |
| `503` | Calendar backend not configured or Microsoft Graph unreachable |
