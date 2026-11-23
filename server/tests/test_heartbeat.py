"""Phase 4 (principle 7): heartbeat endpoints — POST /api/heartbeat + GET /api/heartbeat/status.

Test all verdicts (ok/missing/mismatch/stale/no_plan/permissions), auth,
validation and the atomic write (tmp + os.replace, mode 600).
No network — CALALARM_WORKSPACE is set to a tmp dir via the conftest fixture.
"""
import json
import os
import stat
from datetime import datetime, timedelta, timezone

from .conftest import TEST_TOKEN, auth_header  # noqa: F401


def _payload(scheduled=None, permissions=None, sent_at=None, app_version="2.7.0", version_code=65):
    return {
        "app_version": app_version,
        "version_code": version_code,
        "sent_at": sent_at or datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "scheduled": scheduled if scheduled is not None else [],
        "permissions": permissions if permissions is not None else {
            "notifications": True,
            "full_screen": True,
            "exact_alarms": True,
            "battery_unrestricted": True,
        },
    }


def _entry(date, time, label="Office", enabled=True, is_snooze=False):
    return {"date": date, "time": time, "label": label, "enabled": enabled, "is_snooze": is_snooze}


def _write_plan(m, date_str, wake_time, source="calendar"):
    m.PLANS_DIR.mkdir(parents=True, exist_ok=True)
    plan = {
        "date": date_str,
        "wake_time": wake_time,
        "label": "Office day" if wake_time else "No alarm",
        "work_location": "office" if wake_time else "none",
        "confidence": "high" if wake_time else "none",
        "event_count": 1 if wake_time else 0,
        "source": source,
    }
    m.save_plan_cache(date_str, plan)
    return plan


def _tomorrow(now=None):
    now = now or datetime.now(timezone.utc)
    # same logic as the server: Europe/Amsterdam
    amsterdam = now.astimezone(timezone(timedelta(hours=1)))  # CET in november
    return (amsterdam + timedelta(days=1)).strftime("%Y-%m-%d")


# ---------- POST /api/heartbeat: auth ----------

def test_hb_post_requires_auth(client):
    c, _ = client
    r = c.post("/api/heartbeat", json=_payload())
    assert r.status_code == 401


def test_hb_post_wrong_token_401(client):
    c, _ = client
    r = c.post("/api/heartbeat", json=_payload(), headers=auth_header("fout"))
    assert r.status_code == 401


def test_hb_status_requires_auth(client):
    c, _ = client
    assert c.get("/api/heartbeat/status").status_code == 401
    assert c.get("/api/heartbeat/status", headers=auth_header("fout")).status_code == 401


def test_hb_post_content_type(client):
    c, _ = client
    r = c.post("/api/heartbeat", data="not json", headers={**auth_header(), "Content-Type": "text/plain"})
    assert r.status_code == 415


# ---------- POST /api/heartbeat: validatie ----------

def test_hb_post_validatie_bad_date(client):
    c, _ = client
    p = _payload(scheduled=[_entry("23-11-2026", "07:00")])
    r = c.post("/api/heartbeat", json=p, headers=auth_header())
    assert r.status_code == 422


def test_hb_post_validatie_bad_time(client):
    c, _ = client
    p = _payload(scheduled=[_entry("2026-11-23", "25:00")])
    r = c.post("/api/heartbeat", json=p, headers=auth_header())
    assert r.status_code == 422


def test_hb_post_validatie_impossible_date(client):
    c, _ = client
    p = _payload(scheduled=[_entry("2026-02-30", "07:00")])
    r = c.post("/api/heartbeat", json=p, headers=auth_header())
    assert r.status_code == 422


def test_hb_post_validatie_too_many_scheduled(client):
    c, _ = client
    p = _payload(scheduled=[_entry("2026-11-23", "07:00") for _ in range(51)])
    r = c.post("/api/heartbeat", json=p, headers=auth_header())
    assert r.status_code == 422


def test_hb_post_validatie_max_50_ok(client):
    c, _ = client
    p = _payload(scheduled=[_entry("2026-11-23", "07:00", label=f"a{i}") for i in range(50)])
    r = c.post("/api/heartbeat", json=p, headers=auth_header())
    assert r.status_code == 200
    assert r.json()["scheduled"] == 50


def test_hb_post_validatie_bad_sent_at(client):
    c, _ = client
    p = _payload(sent_at="gisteren")
    r = c.post("/api/heartbeat", json=p, headers=auth_header())
    assert r.status_code == 422


def test_hb_post_validatie_label_too_long(client):
    c, _ = client
    p = _payload(scheduled=[_entry("2026-11-23", "07:00", label="x" * 500)])
    r = c.post("/api/heartbeat", json=p, headers=auth_header())
    assert r.status_code == 422


def test_hb_post_sanitizes_label_control_chars(client):
    c, m = client
    p = _payload(scheduled=[_entry("2026-11-23", "07:00", label="evil\r\nINJECTED")])
    r = c.post("/api/heartbeat", json=p, headers=auth_header())
    assert r.status_code == 200
    saved = json.loads(m.HEARTBEAT_FILE.read_text())
    # P4 review (privacy): labels are no longer stored
    assert "label" not in saved["scheduled"][0]


def test_hb_post_payload_too_large(client):
    c, _ = client
    big = _payload(scheduled=[_entry("2026-11-23", "07:00", label="y" * 200) for _ in range(50)])
    # forceer > 64 KB
    big["scheduled"] = [_entry("2026-11-23", "07:00", label="y" * 200)] * 50
    raw = json.dumps(big).encode()
    # make it really > 64KB
    big["app_version"] = "v" * 32
    big["scheduled"] = [dict(_entry("2026-11-23", "07:00", label="z" * 100)) for _ in range(50)]
    raw = json.dumps(big).encode() + b" " * (70 * 1024)
    r = c.post("/api/heartbeat", data=raw,
               headers={**auth_header(), "Content-Type": "application/json"})
    assert r.status_code == 413


def test_hb_post_atomic_write_mode_600(client, monkeypatch):
    c, m = client
    calls = []
    real = os.replace
    monkeypatch.setattr(m.os, "replace",
                        lambda s, d: (calls.append((s, d)), real(s, d))[1])
    r = c.post("/api/heartbeat", json=_payload(), headers=auth_header())
    assert r.status_code == 200
    assert m.HEARTBEAT_FILE.exists()
    assert stat.S_IMODE(m.HEARTBEAT_FILE.stat().st_mode) == 0o600
    assert calls and calls[0][1] == m.HEARTBEAT_FILE
    assert calls[0][0] != calls[0][1]  # tmp bestand
    assert not list(m.WORKSPACE.glob("*.tmp"))


def test_hb_post_response_schema(client):
    c, _ = client
    r = c.post("/api/heartbeat", json=_payload(scheduled=[_entry("2026-11-23", "07:00")]),
               headers=auth_header())
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "ok"
    assert "received_at" in body
    assert body["scheduled"] == 1


# ---------- GET /api/heartbeat/status: verdicts ----------

def _post_hb(c, **kw):
    r = c.post("/api/heartbeat", json=_payload(**kw), headers=auth_header())
    assert r.status_code == 200


def _status(c, m):
    m._rate_buckets.clear()
    m._token_buckets.clear()
    r = c.get("/api/heartbeat/status", headers=auth_header())
    assert r.status_code == 200
    return r.json()


def test_status_no_heartbeat_stale(client):
    c, m = client
    s = _status(c, m)
    assert s["verdict"] == "stale"
    assert s["last_heartbeat_at"] is None


def test_status_old_heartbeat_stale(client):
    """Heartbeat-bestand ouder dan 8u (received_at) → stale."""
    c, m = client
    old_dt = datetime.now(timezone.utc) - timedelta(hours=9)
    m.save_heartbeat(_payload(), old_dt)
    # controle: bestand echt oud
    saved = json.loads(m.HEARTBEAT_FILE.read_text())
    assert saved["received_at"] == old_dt.astimezone(timezone.utc).isoformat()
    s = _status(c, m)
    assert s["verdict"] == "stale"
    assert s["age_seconds"] > 8 * 3600


def test_status_fresh_heartbeat_no_plan(client):
    c, m = client
    _post_hb(c)
    s = _status(c, m)
    assert s["verdict"] == "no_plan"
    assert s["age_seconds"] < 60


def test_status_plan_no_alarm_ok(client):
    """Plan says: no alarm tomorrow → ok, even if the phone has nothing."""
    c, m = client
    tmr = _tomorrow()
    _write_plan(m, tmr, None)
    _post_hb(c, scheduled=[])
    s = _status(c, m)
    assert s["verdict"] == "ok"
    assert s["expected_tomorrow"]["wake_time"] is None
    assert "no alarm" in s["details"]


def test_status_missing(client):
    """Plan expects 07:00, phone has no enabled entry → missing."""
    c, m = client
    tmr = _tomorrow()
    _write_plan(m, tmr, "07:00")
    _post_hb(c, scheduled=[])
    s = _status(c, m)
    assert s["verdict"] == "missing"
    assert s["expected_tomorrow"]["wake_time"] == "07:00"


def test_status_missing_when_only_snooze(client):
    """Only a snooze entry does NOT count as an alarm → missing."""
    c, m = client
    tmr = _tomorrow()
    _write_plan(m, tmr, "07:00")
    _post_hb(c, scheduled=[_entry(tmr, "07:10", is_snooze=True)])
    s = _status(c, m)
    assert s["verdict"] == "missing"


def test_status_missing_when_disabled(client):
    """Entry exists but enabled=false → missing."""
    c, m = client
    tmr = _tomorrow()
    _write_plan(m, tmr, "07:00")
    _post_hb(c, scheduled=[_entry(tmr, "07:00", enabled=False)])
    s = _status(c, m)
    assert s["verdict"] == "missing"


def test_status_ok_matching_time(client):
    c, m = client
    tmr = _tomorrow()
    _write_plan(m, tmr, "07:00")
    _post_hb(c, scheduled=[_entry(tmr, "07:00")])
    s = _status(c, m)
    assert s["verdict"] == "ok"
    assert s["phone_has"][0]["time"] == "07:00"


def test_status_mismatch(client):
    c, m = client
    tmr = _tomorrow()
    _write_plan(m, tmr, "07:00")
    _post_hb(c, scheduled=[_entry(tmr, "08:30")])
    s = _status(c, m)
    assert s["verdict"] == "mismatch"
    assert "07:00" in s["details"] and "08:30" in s["details"]


def test_status_permissions_priority_over_ok(client):
    """Permissions missing → permissions, even if the time DOES match."""
    c, m = client
    tmr = _tomorrow()
    _write_plan(m, tmr, "07:00")
    _post_hb(c, scheduled=[_entry(tmr, "07:00")],
             permissions={"notifications": False, "full_screen": True,
                          "exact_alarms": True, "battery_unrestricted": True})
    s = _status(c, m)
    assert s["verdict"] == "permissions"
    assert "notifications" in s["details"]


def test_status_permissions_priority_over_missing(client):
    """Permissions missing AND alarm missing → permissions (priority)."""
    c, m = client
    tmr = _tomorrow()
    _write_plan(m, tmr, "07:00")
    _post_hb(c, scheduled=[],
             permissions={"notifications": True, "full_screen": False,
                          "exact_alarms": True, "battery_unrestricted": False})
    s = _status(c, m)
    assert s["verdict"] == "permissions"


def test_status_permissions_stale_wins_over_permissions(client):
    """Stale (no heartbeat > 8h) takes priority over everything."""
    c, m = client
    m.save_heartbeat(
        _payload(permissions={"notifications": False, "full_screen": False,
                              "exact_alarms": False, "battery_unrestricted": False}),
        datetime.now(timezone.utc) - timedelta(hours=10),
    )
    s = _status(c, m)
    assert s["verdict"] == "stale"


def test_status_schema_keys(client):
    c, m = client
    _post_hb(c, scheduled=[_entry("2026-11-23", "07:00")])
    s = _status(c, m)
    for key in ("last_heartbeat_at", "age_seconds", "expected_tomorrow", "phone_has", "verdict", "details"):
        assert key in s, f"missing key {key}"


def test_status_phone_has_only_tomorrow(client):
    """phone_has contains only entries for tomorrow (today is filtered out)."""
    c, m = client
    tmr = _tomorrow()
    today = (datetime.now(timezone.utc).astimezone(timezone(timedelta(hours=1)))).strftime("%Y-%m-%d")
    _write_plan(m, tmr, "07:00")
    _post_hb(c, scheduled=[_entry(today, "06:00", label="today"), _entry(tmr, "07:00", label="tomorrow")])
    s = _status(c, m)
    assert s["verdict"] == "ok"
    assert len(s["phone_has"]) == 1
    assert s["phone_has"][0]["time"] == "07:00"
    assert "label" not in s["phone_has"][0]


def test_hb_rate_limit_per_token(client):
    """After 10 requests/min per token → 429."""
    c, m = client
    for i in range(10):
        r = c.post("/api/heartbeat", json=_payload(), headers=auth_header())
        assert r.status_code == 200, f"req {i} faalde: {r.status_code}"
    r = c.post("/api/heartbeat", json=_payload(), headers=auth_header())
    assert r.status_code == 429


# ---------- P4 review: user_disabled, pending, privacy, monitor-token ----------

def test_status_user_disabled_is_silent_verdict(client):
    """B1: user turned off tomorrow's alarm themselves → user_disabled, no missing."""
    c, m = client
    tmr = _tomorrow()
    _write_plan(m, tmr, "07:00")
    e = _entry(tmr, "07:00", enabled=False)
    e["user_disabled"] = True
    _post_hb(c, scheduled=[e])
    assert _status(c, m)["verdict"] == "user_disabled"


def test_status_pending_when_plan_newer_than_heartbeat(client):
    """B2: plan was cached AFTER the last heartbeat → pending instead of missing."""
    c, m = client
    tmr = _tomorrow()
    _post_hb(c, scheduled=[])
    import time as _t; _t.sleep(0.01)
    _write_plan(m, tmr, "07:00")
    assert _status(c, m)["verdict"] == "pending"


def test_status_newer_plan_but_phone_matches_is_ok(client):
    c, m = client
    tmr = _tomorrow()
    _post_hb(c, scheduled=[_entry(tmr, "07:00")])
    import time as _t; _t.sleep(0.01)
    _write_plan(m, tmr, "07:00")
    assert _status(c, m)["verdict"] == "ok"


def test_status_older_plan_still_missing(client):
    """Plan from before the heartbeat + no alarm → still missing (no false pending)."""
    c, m = client
    tmr = _tomorrow()
    _write_plan(m, tmr, "07:00")
    import time as _t; _t.sleep(0.01)
    _post_hb(c, scheduled=[])
    assert _status(c, m)["verdict"] == "missing"


def test_hb_422_does_not_echo_input_values(client):
    c, _ = client
    p = _payload(scheduled=[_entry("2026-11-23", "99:99", label="geheime-agendatitel")])
    r = c.post("/api/heartbeat", json=p, headers=auth_header())
    assert r.status_code == 422
    assert "geheime-agendatitel" not in r.text
    assert "99:99" not in r.text


def test_monitor_token_reads_status_but_cannot_post(main_mod, monkeypatch):
    from fastapi.testclient import TestClient
    monkeypatch.setattr(main_mod, "MONITOR_TOKEN", "monitor-token-abcdef0123456789")
    with TestClient(main_mod.app) as c:
        mon = {"Authorization": "Bearer monitor-token-abcdef0123456789"}
        assert c.get("/api/heartbeat/status", headers=mon).status_code == 200
        assert c.post("/api/heartbeat", json=_payload(), headers=mon).status_code == 401
        assert c.get("/api/alarm/next", headers=mon).status_code == 401


def test_monitor_token_unset_rejects_empty_bearer(main_mod, monkeypatch):
    from fastapi.testclient import TestClient
    monkeypatch.setattr(main_mod, "MONITOR_TOKEN", "")
    with TestClient(main_mod.app) as c:
        assert c.get("/api/heartbeat/status", headers={"Authorization": "Bearer "}).status_code == 401


def test_status_planned_time_user_disabled_with_own_other_alarm(client):
    """User turned off the scheduled 05:45 and set 06:45 themselves → deliberate choice, no mismatch."""
    c, m = client
    tmr = _tomorrow()
    _write_plan(m, tmr, "05:45")
    off = _entry(tmr, "05:45", enabled=False); off["user_disabled"] = True
    _post_hb(c, scheduled=[off, _entry(tmr, "06:45")])
    s = _status(c, m)
    assert s["verdict"] == "user_disabled"
    assert "06:45" in s["details"]


def test_status_other_time_user_disabled_does_not_mask_mismatch(client):
    """A turned-off alarm at a DIFFERENT time does not mask a real mismatch."""
    c, m = client
    tmr = _tomorrow()
    _write_plan(m, tmr, "05:45")
    off = _entry(tmr, "09:00", enabled=False); off["user_disabled"] = True
    _post_hb(c, scheduled=[off, _entry(tmr, "06:45")])
    assert _status(c, m)["verdict"] == "mismatch"
