"""Google Calendar API provider — STUB (design only, not implemented).

Design (docs/CALENDAR-PROVIDERS.md option b):
  * OAuth 2.0 for an "installed app" (public client): device-code flow works
    for installed apps too, or the loopback redirect flow
    (http://localhost:8080) for a desktop bootstrap — same pattern as
    tools/ms_device_login.py, so that script can be generalized into a small
    generic device-login helper.
  * Scopes: "https://www.googleapis.com/auth/calendar.readonly" only
    (read-only; the alarm app never writes to the calendar).
  * Token storage: same JSON token file convention as ms_token.json
    (access_token + refresh_token + expires_at), mode 600.
  * Refresh: POST grant_type=refresh_token to
    https://oauth2.googleapis.com/token with client_id (+ client_secret if a
    confidential/web client is used — for installed apps there is none).
  * Fetch: GET https://www.googleapis.com/calendar/v3/calendars/primary/events
    ?timeMin=...&timeMax=...&singleEvents=true&orderBy=startTime
    with Authorization: Bearer <access_token>.
  * Mapping to normalized dicts: summary -> subject, start.dateTime/date ->
    start (all-day uses date), end likewise, status == "cancelled" ->
    is_cancelled, description -> body, location -> location,
    organizer/attendees not needed; categories don't exist in Google — use
    colorId or extendedProperties if keyword matching needs them (or leave []).

Until implemented, instantiating this class raises NotImplementedError.
"""

from datetime import datetime

from .base import CalendarProvider


class GoogleCalendarProvider(CalendarProvider):
    """Google Calendar provider via calendar.events.list. Not implemented yet."""

    def __init__(self, token_file: str, client_id: str, client_secret: str = ""):
        self.token_file = token_file
        self.client_id = client_id
        self.client_secret = client_secret  # empty for installed (public) apps

    def get_events(self, start: datetime, end: datetime) -> list[dict]:
        raise NotImplementedError(
            "Google Calendar provider is a design stub — see module docstring "
            "and docs/CALENDAR-PROVIDERS.md option (b)."
        )
