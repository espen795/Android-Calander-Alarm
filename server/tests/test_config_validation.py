"""Config validation via POST /api/config + GET /api/config (backwards compat)."""
import json

import pytest

from .conftest import TEST_TOKEN, auth_header  # noqa: F401


def valid_config():
    return {
        "rules": [
            {"id": "office", "name": "Kantoor", "time": "07:00", "keywords": ["kantoor"]},
            {"id": "home", "name": "Thuis", "time": "08:30", "keywords": ["thuis"]},
        ],
        "default_rule_id": "home",
        "weekend_rule_id": "",
    }


def test_get_config_roundtrip(client):
    c, _ = client
    r = c.get("/api/config", headers=auth_header())
    assert r.status_code == 200
    body = r.json()
    assert "rules" in body and isinstance(body["rules"], list)


def test_post_valid_config(client):
    c, _ = client
    r = c.post("/api/config", headers={**auth_header(), "Content-Type": "application/json"},
               content=json.dumps(valid_config()))
    assert r.status_code == 200
    assert r.json()["status"] == "ok"
    # GET returns it
    r2 = c.get("/api/config", headers=auth_header())
    assert r2.json()["default_rule_id"] == "home"


@pytest.mark.parametrize("bad", [
    {"rules": []},                                   # empty
    {"rules": [{"id": "x", "name": "X", "time": "25:00"}]},   # bad hour
    {"rules": [{"id": "x", "name": "X", "time": "07:00"},
               {"id": "x", "name": "Y", "time": "08:00"}]},       # duplicate id
    {"rules": [{"id": "BAD ID!", "name": "X", "time": "07:00"}]},  # invalid id
    {"rules": [{"id": "x", "name": "", "time": "07:00"}]},         # empty name
    {"rules": [{"id": "x", "name": "X", "time": "07:00"}],
     "default_rule_id": "unknown"},                 # unknown default
    "not a dict",
])
def test_post_invalid_config_400(client, bad):
    c, _ = client
    r = c.post("/api/config", headers={**auth_header(), "Content-Type": "application/json"},
               content=json.dumps(bad))
    assert r.status_code == 400


def test_post_config_wrong_content_type_415(client):
    c, _ = client
    r = c.post("/api/config", headers={**auth_header(), "Content-Type": "text/plain"},
               content="{}")
    assert r.status_code == 415


def test_post_config_requires_auth(client):
    c, _ = client
    r = c.post("/api/config", headers={"Content-Type": "application/json"},
               content=json.dumps(valid_config()))
    assert r.status_code == 401


def test_config_payload_too_large_413(client):
    c, _ = client
    big = json.dumps({"rules": [{"id": "x", "name": "X", "time": "07:00",
                                 "keywords": ["k" * 200000]}]})  # > 100 KB payload
    r = c.post("/api/config", headers={**auth_header(), "Content-Type": "application/json"},
               content=big)
    assert r.status_code == 413
