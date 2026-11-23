"""Auth tests: no token, wrong token, good token — via Bearer header.
Backwards compatible with app v2.6.13/v2.7.0 (SyncRepository/LogUploadWorker)."""
from .conftest import TEST_TOKEN, auth_header


def test_missing_authorization_header_401(client):
    c, _ = client
    r = c.get("/api/alarm/next")
    assert r.status_code == 401


def test_wrong_scheme_401(client):
    c, _ = client
    r = c.get("/api/alarm/next", headers={"Authorization": "Basic abc"})
    assert r.status_code == 401


def test_empty_bearer_401(client):
    c, _ = client
    r = c.get("/api/alarm/next", headers={"Authorization": "Bearer "})
    assert r.status_code == 401


def test_wrong_token_401(client):
    c, _ = client
    r = c.get("/api/alarm/next", headers=auth_header("wrong-token"))
    assert r.status_code == 401


def test_correct_token_passes_auth(client, monkeypatch):
    """Good token → no 401 (endpoint may still fail on MS, but auth passes)."""
    c, m = client
    # Mock the MS layer so we purely test auth
    monkeypatch.setattr(m, "_fetch_calendar_events_sync", lambda d: [])
    r = c.get("/api/alarm/next", headers=auth_header())
    assert r.status_code == 200
    body = r.json()
    # Response schema must stay compatible with the app (AlarmPlan)
    for key in ("date", "wake_time", "label", "work_location", "confidence", "event_count"):
        assert key in body, f"missing key {key}"


def test_token_compare_is_constant_time_implementation(main_mod):
    """#30: validate_token gebruikt hmac.compare_digest."""
    import inspect
    src = inspect.getsource(main_mod.validate_token)
    assert "hmac.compare_digest" in src
    assert main_mod.validate_token(TEST_TOKEN) is True
    assert main_mod.validate_token("other") is False
    assert main_mod.validate_token("") is False
    # length difference must not leak via early exit (compare_digest handles this)
    assert main_mod.validate_token(TEST_TOKEN + "x") is False
