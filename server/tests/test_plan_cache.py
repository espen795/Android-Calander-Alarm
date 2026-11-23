"""B2: last-known-good plan cache — calendar ok → source=calendar + cache written;
calendar fails + cache → cache plan (source=cache); calendar fails without cache → 503;
old cache is pruned."""
import json
import os
import stat
from datetime import datetime, timedelta, timezone

import pytest
from fastapi import HTTPException

from .conftest import TEST_TOKEN, auth_header  # noqa: F401


def _ok_events(target_date):
    return [{
        "subject": "Day at the office",  # "kantoor" keyword preserved in config below
        "start": {"dateTime": target_date.strftime("%Y-%m-%dT08:00:00.0000000Z")},
        "end": {"dateTime": target_date.strftime("%Y-%m-%dT09:00:00.0000000Z")},
    }]


def test_calendar_ok_writes_cache_and_source_calendar(client, monkeypatch):
    c, m = client
    monkeypatch.setattr(m, "_fetch_calendar_events_sync", _ok_events)
    r = c.get("/api/alarm/next?date=2026-11-23", headers=auth_header())
    assert r.status_code == 200
    body = r.json()
    assert body["source"] == "calendar"
    assert body["work_location"] == "office"
    # cache file written, atomically, mode 600
    plan_file = m.PLANS_DIR / "2026-11-23.json"
    assert plan_file.exists()
    assert stat.S_IMODE(plan_file.stat().st_mode) == 0o600
    cached = json.loads(plan_file.read_text())
    assert cached["work_location"] == "office"
    # no .tmp leftovers
    assert not list(m.PLANS_DIR.glob("*.tmp"))


def test_agenda_fail_with_cache_returns_cached_plan(client, monkeypatch):
    """B2 blocker fix: calendar failure → cached plan (source=cache), NOT a default rule."""
    c, m = client
    # First a successful call → cache
    monkeypatch.setattr(m, "_fetch_calendar_events_sync", _ok_events)
    r1 = c.get("/api/alarm/next?date=2026-11-23", headers=auth_header())
    assert r1.status_code == 200 and r1.json()["source"] == "calendar"
    # Now the calendar fails
    def boom(d):
        raise RuntimeError("MS down")
    monkeypatch.setattr(m, "_fetch_calendar_events_sync", boom)
    # reset rate limit for this test
    m._token_buckets.clear()
    m._rate_buckets.clear()
    r2 = c.get("/api/alarm/next?date=2026-11-23", headers=auth_header())
    assert r2.status_code == 200
    body = r2.json()
    assert body["source"] == "cache"
    assert body["work_location"] == "office"   # the GOOD plan, not home-default
    assert body["wake_time"] == "07:00"


def test_agenda_fail_without_cache_returns_503(client, monkeypatch):
    """No cache → 503 (old behavior): app keeps the existing alarm and retries."""
    c, m = client
    def boom(d):
        raise RuntimeError("MS down")
    monkeypatch.setattr(m, "_fetch_calendar_events_sync", boom)
    r = c.get("/api/alarm/next?date=2026-12-24", headers=auth_header())
    assert r.status_code == 503


def test_prune_plan_cache_removes_old(client, monkeypatch):
    c, m = client
    m.PLANS_DIR.mkdir(parents=True, exist_ok=True)
    old = (datetime.now(timezone.utc) - timedelta(days=30)).strftime("%Y-%m-%d")
    new = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    (m.PLANS_DIR / f"{old}.json").write_text("{}")
    (m.PLANS_DIR / f"{new}.json").write_text("{}")
    m.prune_plan_cache()
    assert not (m.PLANS_DIR / f"{old}.json").exists()
    assert (m.PLANS_DIR / f"{new}.json").exists()


def test_save_plan_cache_is_atomic(main_mod, monkeypatch):
    calls = []
    real = os.replace
    monkeypatch.setattr(main_mod.os, "replace",
                        lambda s, d: (calls.append((s, d)), real(s, d))[1])
    main_mod.PLANS_DIR.mkdir(parents=True, exist_ok=True)
    main_mod.save_plan_cache("2026-11-23", {"work_location": "office"})
    assert calls and calls[0][1] == main_mod.PLANS_DIR / "2026-11-23.json"
    assert calls[0][0] != calls[0][1]  # tmp file


def test_plan_response_schema_still_compatible(client, monkeypatch):
    """'source' is extra; all app fields (SyncRepository.AlarmPlan) remain."""
    c, m = client
    monkeypatch.setattr(m, "_fetch_calendar_events_sync", _ok_events)
    r = c.get("/api/alarm/next?date=2026-11-23", headers=auth_header())
    body = r.json()
    for key in ("date", "wake_time", "label", "work_location", "confidence", "event_count"):
        assert key in body
    assert body["source"] in ("calendar", "cache")
