"""Minimal read-only ICS/iCal subscription provider (stdlib only).

Fetches a published calendar URL (Outlook "publish calendar" ICS link, Google
"secret address in iCal format", Nextcloud share `...?export`, Fastmail, ...)
with urllib and parses the VEVENTs it needs for the alarm rule engine.

Supported (per VEVENT):
  * line unfolding (continuation lines starting with space/tab)
  * SUMMARY, DTSTART, DTEND (DATE-TIME with Z / TZID / floating, and
    VALUE=DATE for all-day), DURATION as fallback for DTEND
  * STATUS:CANCELLED -> is_cancelled
  * X-MICROSOFT-CDO-ALLDAYEVENT / VALUE=DATE -> is_all_day
  * DESCRIPTION, LOCATION

DOCUMENTED LIMITATION: no RRULE/RDATE recurrence expansion. A recurring event
appears only at its first occurrence inside the queried window. For real
recurrence support add the third-party `recurring-ical-events` library
(optional dependency, see docs/CALENDAR-PROVIDERS.md option d) — this module
deliberately stays stdlib-only so `import providers` never requires extra
packages.
"""

import re
from datetime import datetime, timedelta, timezone
from urllib.request import Request, urlopen

from .base import CalendarProvider, CalendarProviderError

_UTC = timezone.utc


def _unfold(text: str) -> list[str]:
    """Unfold folded content lines (RFC 5545 §3.1)."""
    lines: list[str] = []
    for raw in text.replace("\r\n", "\n").replace("\r", "\n").split("\n"):
        if raw[:1] in (" ", "\t") and lines:
            lines[-1] += raw[1:]
        else:
            lines.append(raw)
    return lines


def _parse_dt(value: str, params: dict) -> tuple[datetime, bool]:
    """Parse a DTSTART/DTEND value. Returns (datetime, is_date_only)."""
    value = value.strip()
    is_date = params.get("VALUE", "").upper() == "DATE" or re.fullmatch(r"\d{8}", value)
    if is_date:
        d = datetime.strptime(value, "%Y%m%d").replace(tzinfo=_UTC)
        return d, True
    m = re.fullmatch(r"(\d{8})T(\d{6})(Z?)", value)
    if not m:
        raise ValueError(f"unsupported datetime value: {value!r}")
    dt = datetime.strptime(m.group(1) + "T" + m.group(2), "%Y%m%dT%H%M%S")
    if m.group(3) == "Z":
        return dt.replace(tzinfo=_UTC), False
    # Floating or TZID — we keep floating times as UTC-naive→UTC (documented
    # simplification; the rule engine only compares dates for day matching).
    return dt.replace(tzinfo=_UTC), False


def _parse_duration(value: str) -> timedelta:
    """Parse an ISO-8601 duration subset (PnWnDTnHnMnS) as used by iCal DURATION."""
    m = re.fullmatch(
        r"([+-]?)P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?",
        value.strip(),
    )
    if not m:
        raise ValueError(f"unsupported DURATION: {value!r}")
    sign = -1 if m.group(1) == "-" else 1
    weeks, days, hours, minutes, seconds = (int(g) if g else 0 for g in m.groups()[1:])
    return sign * timedelta(weeks=weeks, days=days, hours=hours, minutes=minutes, seconds=seconds)


def parse_ics(text: str, window_start: datetime | None = None,
              window_end: datetime | None = None) -> list[dict]:
    """Parse VCALENDAR text into normalized event dicts.

    If window_start/window_end are given, events that do not overlap the
    window are dropped (matching the CalendarProvider.get_events contract).
    """
    events: list[dict] = []
    current: dict | None = None
    for line in _unfold(text):
        if not line or ":" not in line:
            continue
        name_params, value = line.split(":", 1)
        parts = name_params.split(";")
        name = parts[0].strip().upper()
        params = {}
        for p in parts[1:]:
            if "=" in p:
                k, v = p.split("=", 1)
                params[k.strip().upper()] = v.strip().strip('"')
        if name == "BEGIN" and value.strip().upper() == "VEVENT":
            current = {}
            continue
        if name == "END" and value.strip().upper() == "VEVENT":
            if current is not None:
                events.append(_finalize(current))
            current = None
            continue
        if current is None:
            continue
        if name == "SUMMARY":
            current["subject"] = _unescape(value)
        elif name == "DESCRIPTION":
            current["body"] = _unescape(value)
        elif name == "LOCATION":
            current["location"] = _unescape(value)
        elif name == "CATEGORIES":
            current["categories"] = [_unescape(c) for c in value.split(",") if c.strip()]
        elif name == "STATUS":
            current["is_cancelled"] = value.strip().upper() == "CANCELLED"
        elif name == "DTSTART":
            current["start"], current["_all_day"] = _parse_dt(value, params)
        elif name == "DTEND":
            current["end"], end_is_date = _parse_dt(value, params)
            if end_is_date:
                current["_all_day"] = True
        elif name == "DURATION" and "start" in current and "end" not in current:
            try:
                current["end"] = current["start"] + _parse_duration(value)
            except ValueError:
                pass
        elif name == "X-MICROSOFT-CDO-ALLDAYEVENT":
            if value.strip() == "TRUE":
                current["_all_day"] = True
    if window_start is not None and window_end is not None:
        events = [e for e in events if e["end"] > window_start and e["start"] < window_end]
    return events


def _unescape(value: str) -> str:
    return (value.replace("\\n", "\n").replace("\\,", ",")
            .replace("\\;", ";").replace("\\\\", "\\"))


def _finalize(raw: dict) -> dict:
    start = raw.get("start")
    if start is None:
        # No DTSTART — unusable for alarm matching; keep a far-past start so
        # window filtering drops it deterministically.
        start = datetime(1970, 1, 1, tzinfo=_UTC)
    end = raw.get("end") or (start + timedelta(hours=1))
    return {
        "subject": raw.get("subject", ""),
        "start": start,
        "end": end,
        "is_cancelled": bool(raw.get("is_cancelled", False)),
        "is_all_day": bool(raw.get("_all_day", False)),
        "body": raw.get("body", ""),
        "location": raw.get("location", ""),
        "categories": raw.get("categories", []),
    }


class IcsCalendarProvider(CalendarProvider):
    """Fetches a read-only ICS subscription URL and parses VEVENTs.

    The URL is a bearer capability — treat it like a password (it is the
    "secret address" of a published calendar).
    """

    def __init__(self, ics_url: str, timeout: int = 15, user_agent: str = "CalendarAlarm/1.0"):
        self.ics_url = ics_url
        self.timeout = timeout
        self.user_agent = user_agent

    def get_events(self, start: datetime, end: datetime) -> list[dict]:
        req = Request(self.ics_url, headers={"User-Agent": self.user_agent,
                                             "Accept": "text/calendar, text/plain, */*"})
        try:
            with urlopen(req, timeout=self.timeout) as resp:
                text = resp.read().decode("utf-8", errors="replace")
        except Exception as e:
            raise CalendarProviderError(f"ICS fetch failed: {e}") from e
        try:
            return parse_ics(text, window_start=start, window_end=end)
        except Exception as e:
            raise CalendarProviderError(f"ICS parse failed: {e}") from e
