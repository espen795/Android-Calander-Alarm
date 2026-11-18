"""CalDAV calendar provider — STUB (design only, not implemented).

Target servers: Nextcloud / ownCloud, iCloud (app-specific password), Fastmail,
Posteo, ... — anything speaking CalDAV (RFC 4791).

Design (docs/CALENDAR-PROVIDERS.md option c):
  * Extra dependency: the `caldav` library (plus `vobject` for parsing).
    NOT in server/requirements.txt yet — add it only when this provider is
    actually wired in; this module must stay importable without it.
  * Auth: username + password or app-specific password, stored next to the
    token file with mode 600 (same handling as ms_token.json).
  * Query: connect with caldav.DAVClient(url), principal = client.principal(),
    calendar = principal.calendar(name), then
    calendar.date_search(start, end, expand=True) or a raw
    REPORT calendar-query with a time-range filter.
  * Mapping to normalized dicts: parse each VEVENT with vobject —
    SUMMARY -> subject, DTSTART/DTEND (with TZID via the VTIMEZONE blocks)
    -> start/end, STATUS:CANCELLED -> is_cancelled, all-day via VALUE=DATE,
    DESCRIPTION -> body, LOCATION -> location, CATEGORIES -> categories.
  * Recurrence: use expand=True (server-side expansion) — this is the main
    advantage over the plain ICS provider (ics.py has no RRULE expansion).

Until implemented, instantiating this class raises NotImplementedError.
"""

from datetime import datetime

from .base import CalendarProvider


class CaldavCalendarProvider(CalendarProvider):
    """CalDAV-backed provider (Nextcloud, iCloud, Fastmail, ...). Not implemented yet."""

    def __init__(self, url: str, username: str, password: str, calendar_name: str = "Personal"):
        self.url = url
        self.username = username
        self.password = password
        self.calendar_name = calendar_name

    def get_events(self, start: datetime, end: datetime) -> list[dict]:
        raise NotImplementedError(
            "CalDAV provider is a design stub — see module docstring and "
            "docs/CALENDAR-PROVIDERS.md option (c). Requires the third-party "
            "'caldav' package, intentionally not imported here."
        )
