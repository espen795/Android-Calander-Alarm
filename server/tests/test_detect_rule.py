"""Tests for detect_rule: weekday/weekend/default, cancelled meetings,
no match → default_rule_id, all-day off."""
from datetime import datetime, timezone
from zoneinfo import ZoneInfo

from .conftest import TEST_TOKEN  # noqa: F401  (fixture import)

AMS = ZoneInfo("Europe/Amsterdam")
UTC = timezone.utc


def make_config():
    return {
        "rules": [
            {"id": "office", "name": "Office day", "time": "07:00",
             "keywords": ["kantoor", "office"]},
            {"id": "home", "name": "Work from home day", "time": "08:30",
             "keywords": ["thuis"]},
            {"id": "off", "name": "Day off", "time": "09:00",
             "keywords": ["vrij", "verlof"]},
        ],
        "default_rule_id": "home",
        "weekend_rule_id": "off",
    }


def ev(subject, date="2026-11-23", time_="09:00", **kw):
    return {
        "subject": subject,
        "start": {"dateTime": f"{date}T{time_}:00.0000000Z"},
        "end": {"dateTime": f"{date}T{time_}:00.0000000+01:00"},
        **kw,
    }


def test_weekday_no_match_falls_back_to_default(main_mod):
    # Monday 2026-11-23, no events → default_rule_id = home
    day = datetime(2026, 11, 23, tzinfo=AMS)
    rule, conf = main_mod.detect_rule([], day, make_config())
    assert rule["id"] == "home"
    assert conf == "low"


def test_weekend_no_match_uses_weekend_rule(main_mod):
    # Saturday 2026-11-21, no events → weekend_rule_id = off
    day = datetime(2026, 11, 21, tzinfo=AMS)
    assert day.weekday() >= 5
    rule, conf = main_mod.detect_rule([], day, make_config())
    assert rule["id"] == "off"
    assert conf == "low"


def test_weekend_rule_empty_means_no_alarm(main_mod):
    cfg = make_config()
    cfg["weekend_rule_id"] = ""
    day = datetime(2026, 11, 22, tzinfo=AMS)  # Sunday
    rule, conf = main_mod.detect_rule([], day, cfg)
    assert rule["id"] == "none"
    assert rule["time"] is None
    assert conf == "none"


def test_no_match_and_no_default_means_no_alarm(main_mod):
    cfg = make_config()
    cfg["default_rule_id"] = ""
    day = datetime(2026, 11, 23, tzinfo=AMS)  # Monday
    rule, conf = main_mod.detect_rule([], day, cfg)
    assert rule["id"] == "none"
    assert conf == "none"


def test_keyword_match_office_wins(main_mod):
    day = datetime(2026, 11, 23, tzinfo=AMS)
    events = [ev("Standup op kantoor")]
    rule, conf = main_mod.detect_rule(events, day, make_config())
    assert rule["id"] == "office"
    assert conf in ("medium", "high")


def test_cancelled_meetings_never_match(main_mod):
    day = datetime(2026, 11, 23, tzinfo=AMS)
    events = [
        ev("Kantoor dag", isCancelled=True),
        ev("Kantoor", status="cancelled"),
        ev("Geannuleerd: kantoor"),
    ]
    rule, conf = main_mod.detect_rule(events, day, make_config())
    # No valid match → default home, not office
    assert rule["id"] == "home"
    assert conf == "low"


def test_all_day_off_event_wins_immediately(main_mod):
    day = datetime(2026, 11, 23, tzinfo=AMS)
    events = [
        ev("Verlof", isAllDay=True),
        ev("Kantoor meeting"),  # would trigger office, but all-day off wins
    ]
    rule, conf = main_mod.detect_rule(events, day, make_config())
    assert rule["id"] == "off"
    assert conf == "high"


def test_event_on_other_day_is_ignored(main_mod):
    day = datetime(2026, 11, 23, tzinfo=AMS)
    events = [ev("Kantoor", date="2026-11-24")]  # tomorrow, not today
    rule, conf = main_mod.detect_rule(events, day, make_config())
    assert rule["id"] == "home"  # fallback, no match
