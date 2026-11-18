"""Pluggable calendar providers for Calendar Alarm (skeleton — NOT wired into main.py).

This package defines the normalized calendar-event model and provider
interfaces described in docs/CALENDAR-PROVIDERS.md. It imports with only the
standard library plus what is already in server/requirements.txt (fastapi,
uvicorn) — no extra third-party dependencies are required to import it.

Current status:
  * base.py            — abstract CalendarProvider interface (normalized events)
  * ics.py             — working minimal ICS/iCal fetcher+parser (stdlib only)
  * msgraph.py         — Microsoft Graph provider wrapping the existing logic
  * caldav_provider.py — stub (docstring design for CalDAV via `caldav` lib)
  * google.py          — stub (docstring design for Google Calendar API)

main.py intentionally still uses its own inline Microsoft Graph code; wiring
this package in is future work (see docs/CALENDAR-PROVIDERS.md section 6).
"""

from .base import CalendarProvider, CalendarProviderError

__all__ = ["CalendarProvider", "CalendarProviderError"]
