"""#21: MS token — cache until expires_in, atomic write (tmp+os.replace, 600),
lock around refresh. Everything mocked, no network."""
import json
import os
import stat
import threading
import time

import pytest
from fastapi import HTTPException

from .conftest import TEST_TOKEN  # noqa: F401


def _write_ms_files(main_mod, tmp_path):
    cfg = {"client_id": "cid", "client_secret": "sec", "redirect_uri": "http://localhost:8080"}
    (main_mod.WORKSPACE).mkdir(parents=True, exist_ok=True)
    main_mod.MS_CONFIG_FILE.write_text(json.dumps(cfg))
    main_mod.TOKEN_FILE.write_text(json.dumps({"refresh_token": "rt", "access_token": "old"}))


def test_save_token_secure_is_atomic_and_600(main_mod, monkeypatch):
    """#21: token file is written via tmp + os.replace, mode 600."""
    _write_ms_files(main_mod, main_mod.WORKSPACE)
    calls = []
    real_replace = os.replace

    def spy(src, dst):
        calls.append((src, dst))
        return real_replace(src, dst)

    monkeypatch.setattr(main_mod.os, "replace", spy)
    main_mod.save_token_secure({"access_token": "new", "refresh_token": "rt2", "expires_in": 3600})
    assert calls, "os.replace not called — not atomic!"
    src, dst = calls[0]
    assert dst == main_mod.TOKEN_FILE
    assert src != main_mod.TOKEN_FILE  # tmp file, not directly
    mode = stat.S_IMODE(main_mod.TOKEN_FILE.stat().st_mode)
    assert mode == 0o600, oct(mode)
    data = json.loads(main_mod.TOKEN_FILE.read_text())
    assert data["access_token"] == "new"
    # no tmp file left behind
    leftovers = [p for p in main_mod.WORKSPACE.iterdir() if p.name.endswith(".tmp")]
    assert not leftovers


def test_token_cache_used_until_expires_in(main_mod, monkeypatch):
    """#21: valid cache → no refresh call."""
    _write_ms_files(main_mod, main_mod.WORKSPACE)
    refreshed = []
    monkeypatch.setattr(main_mod, "refresh_microsoft_token",
                        lambda: refreshed.append(1) or main_mod.save_token_secure(
                            {"access_token": "cached", "expires_in": 3600}))
    tok1 = main_mod.get_access_token()
    tok2 = main_mod.get_access_token()
    assert tok1 == tok2 == "cached"
    assert len(refreshed) == 1  # second call used the cache


def test_token_refreshed_after_expiry(main_mod, monkeypatch):
    _write_ms_files(main_mod, main_mod.WORKSPACE)
    counter = {"n": 0}

    def fake_refresh():
        counter["n"] += 1
        main_mod.save_token_secure({"access_token": f"tok{counter['n']}", "expires_in": 0})
    monkeypatch.setattr(main_mod, "refresh_microsoft_token", fake_refresh)
    # expires_in=0 + margin → expires immediately → every call refreshes
    t1 = main_mod.get_access_token()
    t2 = main_mod.get_access_token()
    assert t1 != t2
    assert counter["n"] == 2


def test_refresh_lock_serializes_concurrent(main_mod, monkeypatch):
    """#21: lock around refresh — 20 threads, each 1 refresh call per 'round'."""
    _write_ms_files(main_mod, main_mod.WORKSPACE)
    lock_seen = []
    real_lock = main_mod._ms_token_lock

    class SpyLock:
        def __enter__(self):
            lock_seen.append("acquire")
            return real_lock.__enter__()
        def __exit__(self, *a):
            return real_lock.__exit__(*a)

    monkeypatch.setattr(main_mod, "_ms_token_lock", SpyLock())
    n = {"v": 0}

    def fake_refresh():
        time.sleep(0.01)  # simulate network
        n["v"] += 1
        main_mod.save_token_secure({"access_token": "x", "expires_in": 0})

    monkeypatch.setattr(main_mod, "refresh_microsoft_token", fake_refresh)
    threads = [threading.Thread(target=main_mod.get_access_token) for _ in range(20)]
    for t in threads: t.start()
    for t in threads: t.join()
    assert len(lock_seen) == 20  # every call went through the lock
    assert n["v"] == 20  # cache was always expired → 20 refreshes, but serialized


def test_refresh_failure_raises_503(main_mod, monkeypatch):
    _write_ms_files(main_mod, main_mod.WORKSPACE)

    def boom(req, timeout=10):
        raise OSError("network down")
    monkeypatch.setattr(main_mod, "urlopen", boom)
    with pytest.raises(HTTPException) as exc:
        main_mod.refresh_microsoft_token()
    assert exc.value.status_code == 503
