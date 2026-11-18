# Calendar Alarm — Pluggable Calendar Providers (design)

Today the backend has exactly one calendar source: Microsoft Graph
(`/me/calendarview`), authenticated with a stored OAuth refresh token, with the
rule engine (`detect_rule()` in `server/main.py`) hard-wired behind
`GET /api/alarm/next`. This document is a **design** (not a full
implementation) for making the calendar source pluggable, so a public
deployment does not have to depend on a Microsoft account, a self-hosted
backend, or any external assistant platform.

Options compared: **(a)** Microsoft Graph direct (status quo), **(b)** Google
Calendar API, **(c)** CalDAV, **(d)** read-only ICS/iCal subscription URL,
**(e)** no backend at all — on-device Android `CalendarContract`, **(f)**
external automation platform pushing an override.

A non-wired skeleton of the proposed Python provider interface already exists
in `server/providers/` (see section 6); `main.py` is **not** changed to use it
yet.

---

## 1. Goals and constraints

- The Android app keeps its simple pull contract: `GET /api/alarm/next` →
  `{date, wake_time, label, work_location, confidence, event_count}`.
- Rule matching (`alarm_config.json`, keywords, off/office/home priority,
  weekend/default fallback) stays in **one** place and works on normalized
  events regardless of source.
- No personal data leaves the device unless the user explicitly configures a
  remote source.
- The provider layer must import with only the deps in
  `server/requirements.txt` (fastapi, uvicorn) + stdlib, unless a provider
  explicitly opts into an extra dependency.

---

## 2. Normalized event model

Every provider returns plain dicts:

```python
{
    "subject": str,
    "start": datetime,          # timezone-aware
    "end": datetime,            # timezone-aware
    "is_cancelled": bool,
    "is_all_day": bool,
    # optional, used by detect_rule's text matching:
    "body": str,
    "location": str,
    "categories": list[str],
}
```

`detect_rule()` currently consumes raw Graph shape (`start.dateTime`,
`isCancelled`, `status`, `body.content`, `location.displayName`,
`categories`). When wiring providers in, either (i) adapt `detect_rule()` to
the normalized model (recommended — one small change, keeps providers simple),
or (ii) have `msgraph.py` keep returning the raw Graph shape. Option (i) is
cleaner; the skeleton in `server/providers/base.py` already defines the
normalized dict, and `detect_rule()` can be ported mechanically.

---

## 3. Options compared

| Option | How it works | Pros | Cons | Effort | Privacy |
|---|---|---|---|---|---|
| **(a) Microsoft Graph direct** (current) | Entra app + refresh token in `/data/ms_token.json`; bootstrap via device-code flow (`tools/ms_device_login.py`) or auth-code flow. Server calls `/me/calendarview`. | Rich data (subject, body, location, categories, cancelled flags, timezones); incremental sync possible; already implemented; works for Outlook.com + M365. | Needs an Entra app registration per deployment (or a shared multi-tenant one); token file is a sensitive secret on the server; Microsoft-only; refresh tokens can be revoked. | Done (main.py); bootstrap script done. | Events processed on your server; subjects never returned to the app, but the server *sees* them. |
| **(b) Google Calendar API** | OAuth installed-app/device flow (public client, no secret, like (a)); `calendar.events.list` with `timeMin/timeMax`, singleEvents=true. | Same richness as (a); works for Gmail/Google Workspace users; device flow available for installed apps. | Google-only; another OAuth provider to bootstrap and maintain; same token-file-on-server concern. | Medium (~1 provider class + token bootstrap; reuse the device-login pattern). | Same as (a). |
| **(c) CalDAV** (Nextcloud, iCloud app-specific password, Fastmail, …) | Server (or app) authenticates with username + password/app-password to the CalDAV server and issues `REPORT calendar-query` for the day. Python: `caldav` library. | Works with many self-hosted/privacy-friendly providers; one standard protocol for all of them; no OAuth app registration. | Extra dependency (`caldav` + `vobject`); passwords (even app-specific) on the server; iCloud needs app-specific passwords; recurrence expansion server-side; slightly slower. | Medium–high (auth handling per provider, recurrence, timezones). | Server sees events; credentials on server. |
| **(d) Read-only ICS/iCal subscription URL** | User publishes a calendar as a secret ICS URL (Outlook: "Publish calendar" → ICS link; Google: "Secret address in iCal format"; Nextcloud: share link `…/remote.php/dav/calendars/…/…?export`). Server (or app) fetches the URL with plain HTTP GET and parses VEVENTs. | **Simplest remote option**: no OAuth, no app registration, no secrets beyond an unguessable URL; works with Outlook, Google, Nextcloud, Fastmail, …; can even be fetched **directly by the Android app**; read-only by design. | No body/location/categories in many published ICS feeds (subject only, sometimes); **RRULE recurrence must be expanded manually** (`recurring-ical-events` or hand-rolled; the skeleton `ics.py` documents this limitation); URL leaks = calendar leaks (treat like a password; can be rotated by re-publishing); no cancelled-event flag in some feeds (STATUS:CANCELLED exists in ICS, but support varies). | Low (stdlib urllib + minimal VEVENT parser — `providers/ics.py` is a working minimal version). | Best remote option: no account credentials; only an opaque URL. If fetched on-device, **no server sees anything**. |
| **(e) No backend — on-device Android `CalendarContract`** | App requests `READ_CALENDAR` permission and queries the phone's `CalendarContract.Events` for tomorrow (any account already synced to the phone: Google, Exchange/Outlook via Outlook app, Nextcloud DAVx5, …). Rule engine ported to Kotlin (~150 lines, mirroring `detect_rule()`). | **Zero server, zero OAuth, zero tokens**; works with *every* account type the phone syncs; calendar never leaves the device; offline; no privacy surface at all; the app already has the rules UI locally. | Needs `READ_CALENDAR` runtime permission (users may decline); rule engine duplicated in Kotlin (drift risk — mitigate with shared JSON config + parity tests); no body/location on some providers (same as d); phone must have the calendar synced. | Medium (Kotlin `AlarmSource` + rule engine + permission flow; no server work). | **Best**: nothing leaves the device. |
| **(f) External automation/agent pushes an override** | An external platform (Home Assistant, n8n, a personal AI assistant, a cron script) computes the wake time and calls a new `POST /api/alarm/override`. | Maximum flexibility; the "intelligence" can live anywhere; Calendar Alarm stays a dumb, secure endpoint. | The pusher needs calendar access + logic somewhere (back to a/b/c/d somewhere else); new attack surface (the override endpoint must be authenticated); operational complexity. | Low for the endpoint (one POST handler + validation), but the external logic is out of scope. | Depends on the external platform. |

---

## 4. Recommended roadmap

**Ship (e) on-device `CalendarContract` + (d) ICS subscription first:**

1. **Phase 1 — (e) on-device source (recommended default).** Add an
   `AlarmSource` interface in the Android app with two implementations:
   `RemoteApiSource` (current `SyncRepository.fetchAlarmPlan()`) and
   `LocalCalendarSource` (queries `CalendarContract` for tomorrow, runs a
   Kotlin port of `detect_rule()` over the same `alarm_config.json` rules —
   keep the rules in the app's settings so both sources share them). Default
   new installs to the local source; the server URL becomes optional.
   Privacy story is unbeatable: no backend needed at all.
2. **Phase 2 — (d) ICS URL.** Let the user paste a published ICS URL —
   either fetched **by the app** (best privacy; the skeleton
   `server/providers/ics.py` shows the minimal server-side parse, and the
   same logic ports to Kotlin) or by the backend as a provider. Handles
   Outlook/Google publish links; document that RRULE expansion needs
   `recurring-ical-events` (Python) or a small Kotlin expander.
3. **Phase 3 — keep (a) as a server provider** (already works; document the
   device-code bootstrap in `docs/BACKEND.md`), and add (b) Google and (c)
   CalDAV behind the same `CalendarProvider` interface for users who want a
   central server for multiple devices.
4. **Phase 4 — (f) `POST /api/alarm/override`** for power users who want an
   external agent to push the decision (authenticated with the same device
   token, validated like `/api/config`, short TTL so a stale override can't
   ring an alarm days later).

Rationale: (e) + (d) cover ~all users with **no server and no OAuth**, which
is what an open-source release should default to; (a) remains for people who
already run the backend; (f) is a small, safe addition for automation fans.

---

## 5. Where the on-device source plugs into the app (option e)

Current flow: `AlarmSyncWorker.doWork()` → `SyncRepository.fetchAlarmPlan()`
→ `AlarmPlan` → `AlarmRepository.replaceForDate(...)`.

Proposed:

```kotlin
// io.github.calendaralarm.sync.AlarmSource
interface AlarmSource {
    suspend fun fetchAlarmPlan(date: LocalDate? = null): SyncRepository.SyncResult
}

class RemoteApiSource(private val repo: SyncRepository) : AlarmSource {
    override suspend fun fetchAlarmPlan(date: LocalDate?) = repo.fetchAlarmPlan(date?.toString())
}

class LocalCalendarSource(private val context: Context) : AlarmSource {
    // queries CalendarContract.Events for the target day (projection:
    // TITLE, DTSTART, DTEND, ALL_DAY, EVENT_STATUS, DESCRIPTION, LOCATION)
    // runs AlarmRuleEngine.detectRule(events, date, rules) — Kotlin port of
    // detect_rule() — and maps the result to the same AlarmPlan shape.
}
```

In `AlarmSyncWorker.doWork()`:

```kotlin
val source: AlarmSource = when (settings.calendarSource) {
    CalendarSource.REMOTE -> RemoteApiSource(SyncRepository(applicationContext))
    CalendarSource.LOCAL  -> LocalCalendarSource(applicationContext)
    CalendarSource.ICS    -> IcsUrlSource(settings.icsUrl)   // phase 2
}
val syncResult = source.fetchAlarmPlan()
// ... the rest (replaceForDate, notifications, dedup) is UNCHANGED
```

Notes:

- Permission: `READ_CALENDAR` must be requested at runtime (AndroidManifest +
  `ActivityCompat.requestPermissions`); if declined, fall back to remote or
  show a one-time explanation. Add `<uses-permission android:name="android.permission.READ_CALENDAR"/>`.
- Query window: target day 00:00–24:00 in the device timezone, selection on
  `Events.DTSTART < end AND (Events.DTEND > start OR Events.ALL_DAY = 1)`;
  include `Events.STATUS = STATUS_CANCELED` filtering in Kotlin (mirror the
  `isCancelled` / "geannuleerd" checks).
- The Kotlin rule engine must mirror `detect_rule()` exactly (all-day `off`
  shortcut → priority off>office>home → keyword scoring over
  title+description+location → weekend/default fallback with confidence
  high/medium/low/none). Keep the rules JSON identical so `GET/POST
  /api/config` and the local engine stay interchangeable; add parity unit
  tests with a few sample event sets.
- `SyncRepository.AlarmPlan` is reused verbatim, so `AlarmSyncWorker` needs
  no changes beyond source selection.

---

## 6. Server-side provider interface (skeleton in `server/providers/`)

Proposed Python interface (implemented as a **non-wired skeleton** in
`server/providers/`):

```python
# providers/base.py
from abc import ABC, abstractmethod
from datetime import datetime

class CalendarProvider(ABC):
    @abstractmethod
    def get_events(self, start: datetime, end: datetime) -> list[dict]:
        """Return normalized events overlapping [start, end).

        Each event: {subject, start, end, is_cancelled, is_all_day,
                     body, location, categories}
        """
```

- `providers/msgraph.py` — wraps the existing `refresh_microsoft_token()` /
  `get_calendar_events()` logic conceptually and maps Graph events to the
  normalized dicts (raises `NotImplementedError` where the wiring isn't done
  yet).
- `providers/ics.py` — **working minimal implementation**: stdlib `urllib`
  fetch + a small VEVENT parser (unfolding continuation lines, SUMMARY,
  DTSTART/DTEND with `TZID`/`Z`/floating, DTEND-or-DURATION, STATUS,
  ALL-DAY via `VALUE=DATE`). **Documented limitation: no RRULE/RDATE
  expansion** — recurring events appear only at their first occurrence; use
  `recurring-ical-events` (extra dep) if recurrence matters.
- `providers/caldav_provider.py`, `providers/google.py` — stubs with
  docstrings describing auth and the query to implement.

Wiring sketch (future, **not** done now — `main.py` must keep working as-is):

```python
# main.py (future)
provider = build_provider(os.environ.get("CALALARM_CALENDAR_PROVIDER", "msgraph"),
                          WORKSPACE)
events = provider.get_events(target_start, target_end)
rule, confidence = detect_rule_normalized(events, target_date, config)
```

---

## 7. Sequence diagrams

### 7.1 Current flow (a) — server-side Microsoft Graph

```
App (AlarmSyncWorker)          calendar-alarm-api (main.py)         Microsoft Graph
        |                              |                            |
        |-- GET /api/alarm/next ------>|                            |
        |   (Bearer device token)      |                            |
        |                              |-- POST /token (refresh) -->|
        |                              |<-- access_token ----------|
        |                              |-- GET /me/calendarview --->|
        |                              |<-- events -----------------|
        |                              | detect_rule(events)        |
        |<-- {wake_time, label, ...} --|                            |
        | replaceForDate(...)          |                            |
```

### 7.2 Proposed (e) — fully on-device, no backend

```
AlarmSyncWorker            Android CalendarContract (on device)     AlarmRepository
      |                              |                                  |
      |-- check READ_CALENDAR ------>|                                  |
      |-- query Events (tomorrow) -->|                                  |
      |<-- rows (title, dtstart, ...) |                                  |
      | AlarmRuleEngine.detectRule() |                                  |
      |----------------------------> | replaceForDate(...)              |
      |<-----------------------------|                                  |
```

### 7.3 Proposed (d) — ICS URL fetched by the app

```
AlarmSyncWorker        ICS URL (Outlook/Google/Nextcloud publish link)
      |                        |
      |-- GET <ics-url> ------>|
      |<-- VCALENDAR text ----|
      | parse VEVENTs (no RRULE expansion in minimal parser) |
      | AlarmRuleEngine.detectRule() -> replaceForDate(...)  |
```

### 7.4 Proposed (f) — external agent pushes an override

```
external agent (HA/n8n/OpenClaw/...)     calendar-alarm-api              App
        |                                      |                      |
        |-- POST /api/alarm/override --------->|                      |
        |   (Bearer device token, {date,      |                      |
        |    wake_time, label, ttl})          |                      |
        |<-- 200 {status: ok}                 |                      |
        |                                      |<-- GET /api/alarm/next (override wins while ttl valid)
        |                                      |--------------------->|
```

`POST /api/alarm/override` (design): same auth + rate limit as other
endpoints; body `{"date": "YYYY-MM-DD", "wake_time": "HH:mm"|null, "label":
str, "ttl_minutes": 60}`; validated like `save_alarm_config`; stored in
`/data/override.json` with an expiry timestamp; `GET /api/alarm/next` checks
the override **before** querying the provider and ignores it once expired.
This keeps the server dumb and makes the external platform responsible for
the calendar logic.

---

## 8. Summary recommendation

Default the app to **(e) on-device CalendarContract**, offer **(d) ICS URL**
as the zero-account remote option, keep **(a)** as the documented server
provider (with device-code bootstrap), and add (b)/(c)/(f) later behind the
`CalendarProvider` / `AlarmSource` interfaces sketched above.
