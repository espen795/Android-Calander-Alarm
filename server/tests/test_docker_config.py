"""B1: the Dockerfile CMD line must be valid JSON (the container will not start otherwise),
and docker-compose.yml must load as YAML with the required hardening fields."""
import json
import re
from pathlib import Path

import pytest

SERVER_DIR = Path(__file__).parent.parent


def test_dockerfile_cmd_is_valid_json():
    src = (SERVER_DIR / "Dockerfile").read_text()
    m = re.search(r'^CMD (\[.*\])$', src, re.M)
    assert m, "no CMD in exec form (JSON) found"
    cmd = json.loads(m.group(1))  # must not fail
    assert cmd[0] == "uvicorn"
    assert "--proxy-headers" in cmd
    # no shell wrapping with unescaped quotes
    assert "sh" not in cmd[:1] or cmd[0] == "uvicorn"


def test_compose_loads_and_has_hardening():
    yaml = pytest.importorskip("yaml")
    compose = yaml.safe_load((SERVER_DIR / "docker-compose.yml").read_text())
    svc = compose["services"]["calendar-alarm-api"]
    assert svc["cap_drop"] == ["ALL"]
    assert "no-new-privileges:true" in svc["security_opt"]
    assert svc["mem_limit"] == "256m"
    assert svc["pids_limit"] == 100
    assert svc["read_only"] is True
    env = svc["environment"]
    env_list = [e for e in env if isinstance(e, str)]
    # FORWARDED_ALLOW_IPS via uvicorn's own env, NOT via CMD shell
    assert any(e.startswith("FORWARDED_ALLOW_IPS=") for e in env_list)
    # no broad ranges as forwarded allow ips
    for e in env_list:
        if e.startswith("FORWARDED_ALLOW_IPS="):
            val = e.split("=", 1)[1]
            assert "/" not in val or val.endswith("/32") or ":" in val, \
                f"FORWARDED_ALLOW_IPS is a broad range: {val}"
    # healthcheck without curl
    hc = svc["healthcheck"]["test"]
    assert not any("curl" in str(x) for x in hc)


def test_dockerfile_has_no_curl_jq():
    src = (SERVER_DIR / "Dockerfile").read_text()
    # no apt-get install of curl/jq (only comments may contain the word)
    assert "apt-get" not in src
    for line in src.splitlines():
        if line.startswith("RUN"):
            assert "curl" not in line and "jq" not in line
