"""Tests for WORKSPACE-based path resolution (H4 hardening).

Verifies the server resolves all runtime paths under CALALARM_WORKSPACE (/data in
the hardened container) and never references the host workspace or keystore.
"""
import os
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent.parent))


@pytest.fixture()
def workspace(tmp_path, monkeypatch):
    monkeypatch.setenv("CALALARM_WORKSPACE", str(tmp_path))
    # main.py reads CALALARM_WORKSPACE at import time — force re-import
    for mod in list(sys.modules):
        if mod == "main":
            del sys.modules[mod]
    import main
    return main, tmp_path


def test_paths_under_workspace(workspace):
    main, tmp = workspace
    assert main.WORKSPACE == tmp
    assert main.TOKEN_FILE == tmp / "ms_token.json"
    assert main.MS_CONFIG_FILE == tmp / "ms_app_config.json"
    assert main.ALARM_CONFIG_FILE == tmp / "alarm_config.json"
    assert main.LOGS_DIR == tmp / "logs"


def test_default_workspace_is_data(monkeypatch):
    monkeypatch.delenv("CALALARM_WORKSPACE", raising=False)
    for mod in list(sys.modules):
        if mod == "main":
            del sys.modules[mod]
    import main
    # H4: hardened default is the isolated /data dir, NOT the host workspace
    assert main.WORKSPACE == Path("/data")


def test_no_host_workspace_or_keystore_refs():
    src = (Path(__file__).parent.parent / "main.py").read_text()
    assert "/home/" not in src
    assert "keystore" not in src.lower()
    assert ".jks" not in src.lower()
    assert "gradle.properties" not in src.lower()


def test_cancelled_meetings_are_ignored():
    """v2.6.14: events with isCancelled / status=cancelled / 'geannuleerd' in the title never match."""
    from datetime import datetime, timezone
    import main as m

    day = datetime(2026, 11, 13, tzinfo=timezone.utc)
    ev = lambda subject, **kw: {
        "subject": subject,
        "start": {"dateTime": "2026-11-13T09:00:00Z"},
        "end": {"dateTime": "2026-11-13T10:00:00Z"},
        **kw,
    }
    events = [
        ev("Kantoor dag geannuleerd", isCancelled=True),  # PRESERVED: Dutch keywords matched against server data
        ev("Standup", status="cancelled"),
        ev("Geannuleerd: sync met team"),  # PRESERVED: "geannuleerd" is a Dutch keyword the server matches
        ev("Echte meeting op kantoor"),  # PRESERVED: must still match the "kantoor" keyword
    ]
    config = {"rules": [{"id": "office", "name": "Kantoor", "time": "05:45",
                          "keywords": ["kantoor", "standup"]}],  # PRESERVED: Dutch keyword list
              "default_rule_id": "", "weekend_rule_id": ""}
    rule, conf = m.detect_rule(events, day, config)
    # Only the real office meeting counts -> office match, not via cancelled ones
    assert rule["id"] == "office", rule
    assert conf != "high"
