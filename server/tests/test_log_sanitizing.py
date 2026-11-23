"""#31: log injection — control characters in received entries are neutralized.
Plus H-05 token sanitization and the /api/log endpoint end-to-end."""
import json

from .conftest import TEST_TOKEN, auth_header


def test_sanitize_strips_control_chars(main_mod):
    dirty = "line1\r\nline2\ttabbed\x00\x1f\x7f"
    clean = main_mod.sanitize_log_field(dirty)
    assert "\r" not in clean and "\n" not in clean and "\t" not in clean
    assert "\x00" not in clean and "\x1f" not in clean and "\x7f" not in clean
    assert "line1line2tabbed" == clean


def test_sanitize_keeps_normal_text(main_mod):
    assert main_mod.sanitize_log_field("Plain message 123 !@#") == "Plain message 123 !@#"


def test_upload_log_neutralizes_injection(client):
    """End-to-end: an entry with \r\n in message must not land in the log as a separate line."""
    c, m = client
    payload = {
        "entries": [
            {"timestamp": "2026-11-22T10:00:00", "level": "INFO", "tag": "T",
             "message": "real message\r\n2026-11-22 [ERROR] forged: fake line"},
            {"timestamp": "2026-11-22T10:00:01", "level": "WARN", "tag": "T2",
             "message": f"token leaks: {TEST_TOKEN} en abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"},
        ]
    }
    r = c.post("/api/log", headers={**auth_header(), "Content-Type": "application/json"},
               content=json.dumps(payload))
    assert r.status_code == 200
    assert r.json() == {"received": 2}
    log = (m.LOGS_DIR / "calendar-alarm-device.log").read_text()
    lines = log.strip().split("\n")
    assert len(lines) == 2, f"log injection! lines: {lines}"
    assert "forged" in lines[0] and "fake line" in lines[0]  # on 1 line, no
    assert TEST_TOKEN not in log
    assert "<TOKEN>" in log


def test_upload_log_rejects_wrong_content_type(client):
    c, _ = client
    r = c.post("/api/log", headers={**auth_header(), "Content-Type": "text/plain"},
               content="{}")
    assert r.status_code == 415


def test_upload_log_requires_auth(client):
    c, _ = client
    r = c.post("/api/log", headers={"Content-Type": "application/json"},
               content=json.dumps({"entries": []}))
    assert r.status_code == 401
