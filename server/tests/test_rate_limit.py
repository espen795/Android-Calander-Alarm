"""#20 rate-limit tests: per token after auth, bucket pruning (TTL/LRU cap)."""
import time

from fastapi import HTTPException
import pytest

from .conftest import TEST_TOKEN, auth_header


def test_token_rate_limit_after_auth_only(client):
    """Per-token limit only after successful auth: a wrong token does not count
    in the token-bucket (otherwise an attacker fills the table with garbage)."""
    c, m = client
    # RATE_LIMIT x wrong token → all 401 (within the per-IP limit),
    # and the token-bucket for "fout" is NEVER filled (limit only after auth)
    for _ in range(m.RATE_LIMIT):
        r = c.get("/api/alarm/next", headers=auth_header("wrong"))
        assert r.status_code == 401
    assert "wrong" not in m._token_buckets


def test_good_token_gets_429_after_limit(client, monkeypatch):
    c, m = client
    monkeypatch.setattr(m, "_fetch_calendar_events_sync", lambda d: [])
    statuses = []
    for _ in range(m.RATE_LIMIT + 2):
        r = c.get("/api/alarm/next", headers=auth_header())
        statuses.append(r.status_code)
    assert statuses[: m.RATE_LIMIT] == [200] * m.RATE_LIMIT
    assert statuses[m.RATE_LIMIT:] == [429, 429]


def test_ip_rate_limit_still_applies(client):
    """Per-IP limit (before auth) still works."""
    c, m = client
    for _ in range(m.RATE_LIMIT + 1):
        r = c.get("/api/alarm/next")  # no header → 401, but the IP bucket counts
        if r.status_code == 429:
            break
    else:
        pytest.fail("IP rate limit did not trigger")


def test_bucket_pruning_removes_stale_and_caps(main_mod):
    """#20: _prune_buckets removes expired buckets and caps at MAX_RATE_BUCKETS."""
    now = time.time()
    buckets = {"old": [now - 10_000], "empty": [], "fresh": [now]}
    main_mod._prune_buckets(buckets, now)
    assert "old" not in buckets
    assert "empty" not in buckets
    assert "fresh" in buckets

    # cap: more than MAX buckets → oldest removed
    big = {f"k{i}": [now - i] for i in range(main_mod.MAX_RATE_BUCKETS + 50)}
    main_mod._prune_buckets(big, now)
    assert len(big) <= main_mod.MAX_RATE_BUCKETS


def test_window_expiry_allows_new_requests(main_mod):
    """After the window (>60s) the same token may be used again."""
    main_mod._token_buckets[TEST_TOKEN] = [time.time() - 120] * 20  # old, outside window
    main_mod.check_token_rate_limit(TEST_TOKEN)  # must not raise
    assert len(main_mod._token_buckets[TEST_TOKEN]) == 1


def test_rate_limit_raises_429(client, main_mod):
    with pytest.raises(HTTPException) as exc:
        for _ in range(main_mod.RATE_LIMIT):
            main_mod.check_token_rate_limit(TEST_TOKEN)
        main_mod.check_token_rate_limit(TEST_TOKEN)
    assert exc.value.status_code == 429
