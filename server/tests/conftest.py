"""Shared fixtures for the hardening tests.

Microsoft Graph is FULLY mocked — these tests never touch the network.
CALALARM_WORKSPACE + device token are set BEFORE importing main,
so that main.py works in a tmp dir and never touches the real data/ or .env.
"""
import os
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent.parent))

TEST_TOKEN = "test-device-token-0123456789abcdef"


@pytest.fixture()
def main_mod(tmp_path, monkeypatch):
    """Import main.py with an isolated workspace and test token."""
    monkeypatch.setenv("CALALARM_WORKSPACE", str(tmp_path))
    monkeypatch.setenv("CALALARM_DEVICE_TOKEN", TEST_TOKEN)
    for mod in list(sys.modules):
        if mod == "main":
            del sys.modules[mod]
    import main
    # Clear rate-limit buckets between tests
    main._rate_buckets.clear()
    main._token_buckets.clear()
    # Reset MS token cache
    main._ms_token_cache["access_token"] = None
    main._ms_token_cache["expires_at"] = 0.0
    return main


@pytest.fixture()
def client(main_mod):
    from fastapi.testclient import TestClient
    with TestClient(main_mod.app) as c:
        yield c, main_mod


def auth_header(token: str = TEST_TOKEN) -> dict:
    return {"Authorization": f"Bearer {token}"}
