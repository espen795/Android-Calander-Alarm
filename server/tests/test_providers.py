"""Tests for the (non-wired) calendar provider skeleton, focusing on ics.py parsing.

The providers package is a skeleton (docs/CALENDAR-PROVIDERS.md): only ics.py
is a working implementation, so it gets real parsing tests. The stubs must at
least import cleanly and refuse to run.
"""

import sys
from datetime import datetime, timezone
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent.parent))

from providers.base import CalendarProvider, CalendarProviderError
from providers.ics import IcsCalendarProvider, parse_ics
from providers import msgraph, caldav_provider, google

UTC = timezone.utc

SAMPLE_ICS = """BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Example//Calendar Alarm Test//EN
BEGIN:VEVENT
UID:event-1@example.com
SUMMARY:Office day at the headquarters
DTSTART:20261119T080000Z
DTEND:20261119T170000Z
LOCATION:Main building
DESCRIPTION:Standup at 9\\, bring coffee
CATEGORIES:work,office
END:VEVENT
BEGIN:VEVENT
UID:event-2@example.com
SUMMARY:Day off (vacation)
DTSTART;VALUE=DATE:20261119
END:VEVENT
BEGIN:VEVENT
UID:event-3@example.com
SUMMARY:Cancelled meeting
DTSTART:20261119T100000Z
DTEND:20261119T110000Z
STATUS:CANCELLED
END:VEVENT
BEGIN:VEVENT
UID:event-4@example.com
SUMMARY:Folded summary line that continues here
DTSTART:20261119T120000Z
DURATION:PT45M
END:VEVENT
BEGIN:VEVENT
UID:event-5@example.com
SUMMARY:Other day event
DTSTART:20261120T090000Z
DTEND:20261120T100000Z
END:VEVENT
END:VCALENDAR
"""

# A folded line (continuation starting with a space) to test unfolding.
FOLDED_ICS = (
    "BEGIN:VCALENDAR\r\n"
    "BEGIN:VEVENT\r\n"
    "SUMMARY:Work from home office (remote)\r\n"
    "DTSTART:20261119T090000Z\r\n"
    "DTEND:20261119T093000Z\r\n"
    "DESCRIPTION:This is a long description that has been fol\r\n"
    " ded across two lines per RFC 5545\r\n"
    "END:VEVENT\r\n"
    "END:VCALENDAR\r\n"
)


def test_parse_basic_fields():
    events = parse_ics(SAMPLE_ICS)
    assert len(events) == 5
    by_subject = {e["subject"]: e for e in events}

    office = by_subject["Office day at the headquarters"]
    assert office["start"] == datetime(2026, 11, 19, 8, 0, tzinfo=UTC)
    assert office["end"] == datetime(2026, 11, 19, 17, 0, tzinfo=UTC)
    assert office["location"] == "Main building"
    assert office["categories"] == ["work", "office"]
    assert office["body"] == "Standup at 9, bring coffee"  # escaped comma unescaped
    assert office["is_cancelled"] is False
    assert office["is_all_day"] is False


def test_all_day_event_via_value_date():
    events = parse_ics(SAMPLE_ICS)
    off = next(e for e in events if e["subject"].startswith("Day off"))
    assert off["is_all_day"] is True
    assert off["start"] == datetime(2026, 11, 19, 0, 0, tzinfo=UTC)
    # No DTEND/DURATION -> default 1 hour
    assert off["end"] > off["start"]


def test_cancelled_status():
    events = parse_ics(SAMPLE_ICS)
    cancelled = next(e for e in events if e["subject"] == "Cancelled meeting")
    assert cancelled["is_cancelled"] is True


def test_duration_instead_of_dtend():
    events = parse_ics(SAMPLE_ICS)
    folded = next(e for e in events if e["subject"].startswith("Folded summary"))
    assert folded["end"] - folded["start"] == __import__("datetime").timedelta(minutes=45)


def test_window_filtering():
    start = datetime(2026, 11, 19, 0, 0, tzinfo=UTC)
    end = datetime(2026, 11, 20, 0, 0, tzinfo=UTC)
    events = parse_ics(SAMPLE_ICS, window_start=start, window_end=end)
    subjects = {e["subject"] for e in events}
    assert "Other day event" not in subjects  # 2026-11-20 is outside the window
    assert len(events) == 4


def test_line_unfolding_crlf():
    events = parse_ics(FOLDED_ICS)
    assert len(events) == 1
    assert events[0]["body"] == (
        "This is a long description that has been folded across two lines per RFC 5545"
    )


def test_provider_interface_contract():
    assert issubclass(IcsCalendarProvider, CalendarProvider)
    assert issubclass(msgraph.MicrosoftGraphProvider, CalendarProvider)
    assert issubclass(caldav_provider.CaldavCalendarProvider, CalendarProvider)
    assert issubclass(google.GoogleCalendarProvider, CalendarProvider)


def test_stubs_raise_not_implemented():
    with pytest.raises(NotImplementedError):
        msgraph.MicrosoftGraphProvider("/tmp/token.json", "/tmp/config.json").get_events(
            datetime(2026, 11, 19, tzinfo=UTC), datetime(2026, 11, 20, tzinfo=UTC))
    with pytest.raises(NotImplementedError):
        caldav_provider.CaldavCalendarProvider("https://caldav.example.com", "u", "p").get_events(
            datetime(2026, 11, 19, tzinfo=UTC), datetime(2026, 11, 20, tzinfo=UTC))
    with pytest.raises(NotImplementedError):
        google.GoogleCalendarProvider("/tmp/token.json", "cid").get_events(
            datetime(2026, 11, 19, tzinfo=UTC), datetime(2026, 11, 20, tzinfo=UTC))


def test_ics_provider_fetch_error_wrapped(monkeypatch):
    import providers.ics as ics_mod

    def boom(req, timeout=None):
        raise OSError("connection refused")

    monkeypatch.setattr(ics_mod, "urlopen", boom)
    prov = IcsCalendarProvider("https://calendar.example.com/secret.ics")
    with pytest.raises(CalendarProviderError):
        prov.get_events(datetime(2026, 11, 19, tzinfo=UTC), datetime(2026, 11, 20, tzinfo=UTC))


def test_ics_provider_fetch_ok(monkeypatch):
    import providers.ics as ics_mod

    class FakeResp:
        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

        def read(self):
            return SAMPLE_ICS.encode()

    monkeypatch.setattr(ics_mod, "urlopen", lambda req, timeout=None: FakeResp())
    prov = IcsCalendarProvider("https://calendar.example.com/secret.ics")
    events = prov.get_events(
        datetime(2026, 11, 19, tzinfo=UTC), datetime(2026, 11, 20, tzinfo=UTC))
    assert len(events) == 4
    assert all(set(e) == {"subject", "start", "end", "is_cancelled",
                          "is_all_day", "body", "location", "categories"}
               for e in events)
