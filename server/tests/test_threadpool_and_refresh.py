"""N4: agenda call must go through the threadpool (event loop must not block).
N2: on token refresh without a new refresh_token, the old one is kept."""
import json

from .conftest import TEST_TOKEN  # noqa: F401


def test_agenda_call_goes_through_run_in_threadpool(client, monkeypatch):
    """N4: if _fetch_calendar_events_sync ran directly (sync), it would block the
    event loop. We force that run_in_threadpool is called."""
    c, m = client
    called = {"threadpool": 0, "direct": 0}

    real_rip = m.run_in_threadpool

    async def spy_rip(func, *args, **kwargs):
        called["threadpool"] += 1
        return await real_rip(func, *args, **kwargs)

    def fake_sync(d):
        called["direct"] += 1
        return []

    monkeypatch.setattr(m, "run_in_threadpool", spy_rip)
    monkeypatch.setattr(m, "_fetch_calendar_events_sync", fake_sync)
    r = c.get("/api/alarm/next?date=2026-11-23", headers={"Authorization": f"Bearer {TEST_TOKEN}"})
    assert r.status_code == 200
    assert called["threadpool"] >= 1, "agenda call did NOT go through run_in_threadpool"


def test_refresh_keeps_old_refresh_token_when_missing(main_mod, monkeypatch):
    """N2: MS response without refresh_token → old refresh_token is kept."""
    main_mod.WORKSPACE.mkdir(parents=True, exist_ok=True)
    main_mod.MS_CONFIG_FILE.write_text(json.dumps(
        {"client_id": "cid", "client_secret": "sec", "redirect_uri": "http://localhost:8080"}))
    main_mod.TOKEN_FILE.write_text(json.dumps({"refresh_token": "OUDE_RT", "access_token": "old"}))

    def fake_urlopen(req, timeout=10):
        class R:
            def read(self):
                # note: NO refresh_token in response
                return json.dumps({"access_token": "nieuw_at", "expires_in": 3600}).encode()
            def __enter__(self): return self
            def __exit__(self, *a): return False
        return R()

    monkeypatch.setattr(main_mod, "urlopen", fake_urlopen)
    main_mod.refresh_microsoft_token()
    data = json.loads(main_mod.TOKEN_FILE.read_text())
    assert data["access_token"] == "nieuw_at"
    assert data["refresh_token"] == "OUDE_RT", "old refresh_token lost!"
