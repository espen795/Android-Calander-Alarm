"""#23: UTC conversion of the calendar window, incl. around DST switches
(2026-03-29 = start of summer time, 2026-10-25 = start of winter time)."""
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

from .conftest import TEST_TOKEN  # noqa: F401

AMS = ZoneInfo("Europe/Amsterdam")


def test_utc_conversion_winter(main_mod):
    # Winter (CET = UTC+1): 2026-01-15 00:00 Amsterdam = 2026-01-14 23:00 UTC
    dt = datetime(2026, 1, 15, 0, 0, tzinfo=AMS)
    utc = main_mod._to_utc(dt)
    assert utc.strftime("%Y-%m-%dT%H:%M:%SZ") == "2026-01-14T23:00:00Z"


def test_utc_conversion_summer(main_mod):
    # Summer (CEST = UTC+2): 2026-07-15 00:00 Amsterdam = 2026-07-14 22:00 UTC
    dt = datetime(2026, 7, 15, 0, 0, tzinfo=AMS)
    utc = main_mod._to_utc(dt)
    assert utc.strftime("%Y-%m-%dT%H:%M:%SZ") == "2026-07-14T22:00:00Z"


def test_utc_conversion_dst_spring_forward(main_mod):
    # 2026-03-29 02:00 → 03:00 (summer time starts). Day 2026-03-30 00:00 AMS = 2026-03-29 22:00 UTC
    dt = datetime(2026, 3, 30, 0, 0, tzinfo=AMS)
    utc = main_mod._to_utc(dt)
    assert utc.strftime("%Y-%m-%dT%H:%M:%SZ") == "2026-03-29T22:00:00Z"


def test_utc_conversion_dst_fall_back(main_mod):
    # 2026-10-25 03:00 → 02:00 (winter time starts). Day 2026-10-26 00:00 AMS = 2026-10-25 23:00 UTC
    dt = datetime(2026, 10, 26, 0, 0, tzinfo=AMS)
    utc = main_mod._to_utc(dt)
    assert utc.strftime("%Y-%m-%dT%H:%M:%SZ") == "2026-10-25T23:00:00Z"


def test_naive_datetime_treated_as_utc(main_mod):
    dt = datetime(2026, 6, 1, 12, 0)  # naive
    utc = main_mod._to_utc(dt)
    assert utc.strftime("%Y-%m-%dT%H:%M:%SZ") == "2026-06-01T12:00:00Z"


def test_calendar_url_uses_real_utc(main_mod, monkeypatch):
    """The calendarview URL contains real UTC times (not Amsterdam time with 'Z')."""
    captured = {}

    def fake_urlopen(req, timeout=10):
        captured["url"] = req.full_url
        class R:
            def read(self):
                return b'{"value": []}'
            def __enter__(self): return self
            def __exit__(self, *a): return False
        return R()

    monkeypatch.setattr(main_mod, "urlopen", fake_urlopen)
    start = datetime(2026, 1, 15, 0, 0, tzinfo=AMS)   # = 2026-01-14 23:00 UTC
    end = start + timedelta(days=1)
    main_mod.get_calendar_events("tok", start, end)
    assert "startDateTime=2026-01-14T23:00:00Z" in captured["url"]
    assert "endDateTime=2026-01-15T23:00:00Z" in captured["url"]


def test_calendar_url_dst_boundary(main_mod, monkeypatch):
    """Around DST: 2026-03-30 (after switch) → 22:00 UTC, not 23:00 (old bug)."""
    captured = {}

    def fake_urlopen(req, timeout=10):
        captured["url"] = req.full_url
        class R:
            def read(self): return b'{"value": []}'
            def __enter__(self): return self
            def __exit__(self, *a): return False
        return R()

    monkeypatch.setattr(main_mod, "urlopen", fake_urlopen)
    start = datetime(2026, 3, 30, 0, 0, tzinfo=AMS)
    main_mod.get_calendar_events("tok", start, start + timedelta(days=1))
    assert "startDateTime=2026-03-29T22:00:00Z" in captured["url"]  # CEST, UTC+2
