# Roadmap

**North star: no server at all.** You install the app, sign in to your calendar
*inside the app*, set your rules, and everything — calendar sync, rule matching and
alarm scheduling — happens on the phone. The self-hosted backend becomes optional,
for people who want central control over several devices or automation hooks.

Status legend: ✅ done · 🚧 in progress · 📋 planned · 💡 idea

## Today (v2.6.x) ✅

- Android app pulls tomorrow's alarm from a self-hosted FastAPI backend
  (`GET /api/alarm/next`); the backend reads Microsoft 365 / Outlook via Microsoft
  Graph and matches keyword rules. See [docs/BACKEND.md](docs/BACKEND.md).
- Standalone OAuth bootstrap for the backend: `server/tools/ms_device_login.py`
  (device-code flow), so no external tooling is needed to obtain the token.

## Phase 1 — Pluggable alarm source in the app 📋

- Introduce an `AlarmSource` interface in the app with `RemoteApiSource` (today's
  backend) and new on-device sources. The rule editor and rules (`alarm_config`)
  move into the app so every source uses the same rules.
- Port the backend rule engine (`detect_rule`: keywords, cancelled events,
  weekend/default rule, confidence) to Kotlin with the same unit tests.
- Server URL becomes optional in Settings; a fresh install works without it.

## Phase 2 — Use the phone's own calendars (zero login) 📋

- `LocalCalendarSource` reads `CalendarContract` (`READ_CALENDAR` permission).
  Works with every account already synced to the phone: Exchange/Outlook, Google,
  Samsung, CalDAV via DAVx⁵ …
- User picks which calendars count. No tokens, no network, best privacy.
- This is the recommended default for new users.

## Phase 3 — Sign in inside the app 📋

For calendars that are not synced to the phone (e.g. work accounts that block
device sync), the app logs in itself and syncs directly from the provider:

| Provider | Library / flow | Scope | Notes |
|---|---|---|---|
| Microsoft 365 / Outlook | **MSAL for Android** (or AppAuth) — Authorization Code + PKCE, public client, no secret | `Calendars.Read offline_access` | Needs a multi-tenant Entra app registration with an Android redirect URI (package name + signing cert hash). Some work tenants require admin consent. |
| Google Calendar | **Credential Manager / Google Identity** + AuthorizationClient | `calendar.readonly` (or `calendar.events.readonly`) | Restricted scope: a public build needs Google OAuth app verification; self-built forks can use their own OAuth client in testing mode. |
| CalDAV (Nextcloud, iCloud, Fastmail…) | Username + app password, `PROPFIND`/`REPORT` | — | iCloud requires an app-specific password. |
| ICS subscription URL | Plain HTTPS fetch + iCal parser with RRULE expansion | — | Simplest; works with Outlook/Google "publish calendar" links. |

Design points:
- Tokens stored only in Android Keystore-backed encrypted storage; refresh in a
  WorkManager job; never leave the device.
- Same sync windows as today (evening sync + pre-alarm re-check), so behaviour is
  identical regardless of source.
- Build flavours: a `foss` flavour without Google Play Services dependencies
  (CalendarContract, CalDAV, ICS, MSAL via browser) and a `full` flavour.
- OAuth client IDs are build-time Gradle properties, like the existing
  `CALALARM_*` settings; forks register their own.

## Phase 4 — Backend becomes an optional add-on 💡

- Server-side providers behind one interface (`server/providers/`: Graph, ICS,
  CalDAV, Google) for multi-device households.
- `POST /api/alarm/override` so external automation (Home Assistant, n8n, an AI
  agent) can push a decision, with a short TTL so stale overrides never ring.

## Also on the list 💡

- English/Dutch UI everywhere, more languages via `strings.xml`.
- F-Droid / reproducible builds once there are no proprietary dependencies in the
  `foss` flavour.

The detailed comparison and sequence diagrams are in
[docs/CALENDAR-PROVIDERS.md](docs/CALENDAR-PROVIDERS.md).
