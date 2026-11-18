"""Abstract calendar provider interface.

Design doc: docs/CALENDAR-PROVIDERS.md (section 6). All providers return the
same normalized event dicts so the rule engine (detect_rule in main.py, or
its normalized variant) does not care where events came from.
"""

from abc import ABC, abstractmethod
from datetime import datetime


class CalendarProviderError(Exception):
    """Raised when a provider cannot fetch events (auth, network, parse...)."""


class CalendarProvider(ABC):
    """A source of calendar events for a time window."""

    @abstractmethod
    def get_events(self, start: datetime, end: datetime) -> list[dict]:
        """Return normalized events overlapping the window [start, end).

        Both arguments are timezone-aware datetimes. The returned list contains
        plain dicts with exactly these keys:

            subject:      str   — event title
            start:        datetime (tz-aware)
            end:          datetime (tz-aware)
            is_cancelled: bool
            is_all_day:   bool
            body:         str   — optional free text (may be "")
            location:     str   — optional (may be "")
            categories:   list[str] — optional (may be [])

        Providers should raise CalendarProviderError on failure instead of
        returning partial/garbage data.
        """
        raise NotImplementedError

    def close(self) -> None:
        """Release any resources (HTTP sessions, tokens). Optional."""
        return None
