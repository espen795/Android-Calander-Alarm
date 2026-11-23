"""End-to-end /api/alarm/next: fail-safe fallback on MS error, response schema
backwards compatible with app v2.6.13/v2.7.0 (SyncRepository.AlarmPlan)."""
from datetime import datetime
from zoneinfo import ZoneInfo

from .conftest import TEST_TOKEN, auth_header  # noqa: F401

AMS = ZoneInfo("Europe/Amsterdam")


def test_alarm_next_ms_fails_without_cache_returns_503(client, monkeypatch):
    """B2: calendar down without cache → 503 (app keeps existing alarm, retries later).
    With cache → cached plan; see test_plan_cache.py."""
    c, m = client

    def boom(target_date):
        raise RuntimeError("MS down")
    monkeypatch.setattr(m, "_fetch_calendar_events_sync", boom)
    r = c.get("/api/alarm/next?date=2026-11-23", headers=auth_header())
    assert r.status_code == 503


def test_alarm_next_weekend_no_rule_returns_null_wake_time(client, monkeypatch):
    """Weekend without weekend_rule_id → wake_time null (app shows 'no alarm')."""
    c, m = client
    monkeypatch.setattr(m, "_fetch_calendar_events_sync", lambda d: [])
    # Write a config WITHOUT a weekend rule (default in code is "off")
    cfg = {"rules": [{"id": "home", "name": "Thuis", "time": "08:30", "keywords": ["thuis"]}],
           "default_rule_id": "home", "weekend_rule_id": ""}
    m.save_alarm_config(cfg)
    # 2026-11-22 is a Sunday
    r = c.get("/api/alarm/next?date=2026-11-22", headers=auth_header())
    assert r.status_code == 200
    body = r.json()
    assert body["wake_time"] is None
    assert body["work_location"] == "none"
    assert body["label"] == "No alarm"


def test_alarm_next_response_schema_compatible(client, monkeypatch):
    c, m = client
    monkeypatch.setattr(m, "_fetch_calendar_events_sync", lambda d: [])
    r = c.get("/api/alarm/next?date=2026-11-23", headers=auth_header())
    body = r.json()
    # All fields that SyncRepository.AlarmPlan expects (snake_case) must be in it;
    # "source" is an extra backwards-compatible field (app ignores unknown keys)
    for key in ("date", "wake_time", "label", "work_location", "confidence", "event_count"):
        assert key in body
    assert body["source"] in ("calendar", "cache")


def test_alarm_next_with_matching_event(client, monkeypatch):
    c, m = client
    events = [{
        "subject": "Dag op kantoor",
        "start": {"dateTime": "2026-11-23T08:00:00.0000000Z"},
        "end": {"dateTime": "2026-11-23T09:00:00.0000000Z"},
    }]
    monkeypatch.setattr(m, "_fetch_calendar_events_sync", lambda d: events)
    r = c.get("/api/alarm/next?date=2026-11-23", headers=auth_header())
    assert r.status_code == 200
    body = r.json()
    assert body["work_location"] == "office"
    assert body["wake_time"] == "07:00"
    assert body["event_count"] == 1


def test_alarm_next_invalid_date_400(client):
    c, _ = client
    r = c.get("/api/alarm/next?date=23-11-2026", headers=auth_header())
    assert r.status_code == 400


def test_health_no_auth_needed(client):
    c, _ = client
    r = c.get("/health")
    assert r.status_code == 200
    assert r.json()["status"] == "ok"


def test_docs_disabled(client):
    """#32/#33: /docs, /redoc, /openapi.json are disabled."""
    c, _ = client
    assert c.get("/docs").status_code == 404
    assert c.get("/redoc").status_code == 404
    assert c.get("/openapi.json").status_code == 404
