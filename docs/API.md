# API reference

Base URL: the server URL configured in the app (e.g. `https://alarm-api.example.com`).
All endpoints except `/health` require the header:

```
Authorization: Bearer <CALALARM_DEVICE_TOKEN>
```

The token is never accepted as a URL parameter. Responses are JSON.

## Limits & errors

- **Rate limit**: max **10 requests/minute per client IP** and **10/minute per device
  token** → `429 {"detail": "Rate limited. Max 10 req/min."}`
- Invalid/missing token → `401`; malformed `Authorization` header → `401`.
- POST endpoints require `Content-Type: application/json` → `415` otherwise.
- Payload limits: `POST /api/config` max **100 KB** → `413`; `POST /api/log` max **1 MB**
  → `413`. Invalid JSON → `400`.
- Calendar backend unreachable/not configured → `503`.

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
fallback rule). Event subjects are never included — only the count.

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

## Error summary

| Code | Meaning |
|---|---|
| `400` | Bad request (invalid JSON, invalid `date`, failed config validation, too many log entries) |
| `401` | Missing/invalid bearer token |
| `413` | Payload too large (config > 100 KB, logs > 1 MB) |
| `415` | POST without `Content-Type: application/json` |
| `429` | Rate limited (10/min per IP and per token) |
| `500` | Server-side failure writing config/logs |
| `503` | Calendar backend not configured or Microsoft Graph unreachable |
