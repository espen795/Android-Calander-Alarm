#!/usr/bin/env python3
"""
Calendar Alarm Server — Pull-based API (v2.1.6)
===========================================
Lightweight HTTPS API that the Calendar Alarm app polls.

Endpoints:
    GET  /health
    GET  /api/alarm/next               (Authorization: Bearer *** header)
    GET  /api/config                   (Authorization: Bearer *** header)
    POST /api/config                   (Authorization: Bearer *** header, body: full alarm config JSON)
    POST /api/log                      (Authorization: Bearer *** header, body: {"entries": [...]})

Dynamic config:
    Rules (labels, wake times, keywords) are stored in alarm_config.json
    and can be edited from the app via GET/POST /api/config.

Security:
    - HTTPS only (enforced by the edge proxy in front of this service)
    - Device token auth (constant-time compare, via Authorization header)
    - Rate limited (10 req/min per IP + per device token)
    - Content-Type validation on POST endpoints (M-05)
    - Payload size limits (1 MB logs, 100 KB config)
    - Token file permissions 600 — owner-only (H-02)
    - Log sanitization with regex: hex tokens + CF secrets (H-05)
    - No sensitive data in logs (no calendar subjects)
"""

import json
import os
import re
import stat
import time

# H4 hardening: new files created by this process get owner-only permissions (600).
os.umask(0o077)

from collections import defaultdict
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.request import Request, urlopen

from fastapi import FastAPI, Query, Header, HTTPException, Request as FastAPIRequest
import uvicorn

# ============== Configuration ==============

# H4 hardening: default to the isolated data dir (compose sets CALALARM_WORKSPACE=/data).
# Only this data dir is mounted; nothing else from the host is visible.
WORKSPACE = Path(os.environ.get("CALALARM_WORKSPACE", "/data"))
TOKEN_FILE = WORKSPACE / os.environ.get("CALALARM_MS_TOKEN_FILE", "ms_token.json")
MS_CONFIG_FILE = WORKSPACE / os.environ.get("CALALARM_MS_CONFIG_FILE", "ms_app_config.json")
ALARM_CONFIG_FILE = WORKSPACE / os.environ.get("CALALARM_CONFIG_FILE", "alarm_config.json")
DEVICE_TOKEN = os.environ.get("CALALARM_DEVICE_TOKEN", "")

_rate_buckets: dict = defaultdict(list)
_token_buckets: dict = defaultdict(list)  # M-03: per-token rate limiting
RATE_LIMIT = 10
RATE_WINDOW = 60

LOGS_DIR = WORKSPACE / "logs"
MAX_LOG_BATCH = 500
MAX_LOG_LINE = 1000
MAX_CONFIG_SIZE = 100 * 1024  # 100 KB cap for config payloads
MAX_LOG_PAYLOAD_SIZE = 1 * 1024 * 1024  # 1 MB cap for log payloads (M-05)

# H-05: Regex patterns voor token sanitization in logs
HEX_TOKEN_PATTERN = re.compile(r'\b[a-f0-9]{64}\b')
CF_SECRET_PATTERN = re.compile(r'cfast_[A-Za-z0-9_-]+')

# ============== Alarm Config (dynamic rules) ==============

DEFAULT_ALARM_CONFIG = {
    "rules": [
        {
            "id": "office",
            "name": "Kantoor dag",
            "time": "07:00",
            "keywords": ["kantoor", "office"],
        },
        {
            "id": "home",
            "name": "Thuiswerk dag",
            "time": "08:30",
            "keywords": ["thuis", "home office", "thuiswerken"],
        },
        {
            "id": "off",
            "name": "Vrije dag",
            "time": "09:00",
            "keywords": ["vrij", "verlof", "vakantie", "ziek"],
        },
    ],
    "default_rule_id": "home",
    "weekend_rule_id": "off",
}


def load_alarm_config() -> dict:
    """Load alarm rules from disk. Falls back to defaults if missing/invalid."""
    try:
        if ALARM_CONFIG_FILE.exists():
            with open(ALARM_CONFIG_FILE) as f:
                cfg = json.load(f)
            if isinstance(cfg, dict) and isinstance(cfg.get("rules"), list) and cfg["rules"]:
                return cfg
    except Exception:
        pass
    return DEFAULT_ALARM_CONFIG


def save_alarm_config(config: dict) -> None:
    """Validate and persist alarm rules to disk."""
    if not isinstance(config, dict):
        raise ValueError("Config must be an object")
    rules = config.get("rules")
    if not isinstance(rules, list) or not rules:
        raise ValueError("Config must contain a non-empty 'rules' list")
    if len(rules) > 20:
        raise ValueError("Too many rules (max 20)")

    time_re = __import__("re").compile(r"^([01][0-9]|2[0-3]):[0-5][0-9]$")
    seen_ids = set()
    for rule in rules:
        if not isinstance(rule, dict):
            raise ValueError("Each rule must be an object")
        rid = str(rule.get("id", "")).strip().lower()
        if not rid or not __import__("re").compile(r"^[a-z0-9_-]{1,32}$").match(rid):
            raise ValueError(f"Invalid rule id: {rule.get('id')!r}")
        if rid in seen_ids:
            raise ValueError(f"Duplicate rule id: {rid}")
        seen_ids.add(rid)
        if not time_re.match(str(rule.get("time", ""))):
            raise ValueError(f"Invalid time for rule {rid}: {rule.get('time')!r}")
        name = str(rule.get("name", "")).strip()
        if not name or len(name) > 50:
            raise ValueError(f"Invalid name for rule {rid}")
        keywords = rule.get("keywords", [])
        if not isinstance(keywords, list) or len(keywords) > 50:
            raise ValueError(f"Invalid keywords for rule {rid}")
        clean_kw = []
        for kw in keywords:
            kw = str(kw).strip().lower()
            if kw and len(kw) <= 50 and kw not in clean_kw:
                clean_kw.append(kw)
        rule["id"] = rid
        rule["name"] = name
        rule["keywords"] = clean_kw

    # Weekend en default rule mogen LEEG zijn (geen alarm op weekend / geen default)
    for key in ("default_rule_id", "weekend_rule_id"):
        val = str(config.get(key, "") or "").strip().lower()
        if val and val not in seen_ids:
            raise ValueError(f"Unknown {key}: {val}")
        config[key] = val  # Kan leeg zijn!

    ALARM_CONFIG_FILE.parent.mkdir(parents=True, exist_ok=True)
    tmp = ALARM_CONFIG_FILE.with_suffix(".tmp")
    with open(tmp, "w") as f:
        json.dump(config, f, indent=2)
    os.chmod(tmp, 0o600)  # H4: config may be considered sensitive — owner-only
    tmp.replace(ALARM_CONFIG_FILE)


# ============== Rate Limiting & Auth ==============

def check_rate_limit(client_ip: str):
    now = time.time()
    _rate_buckets[client_ip] = [t for t in _rate_buckets[client_ip] if now - t < RATE_WINDOW]
    if len(_rate_buckets[client_ip]) >= RATE_LIMIT:
        raise HTTPException(status_code=429, detail="Rate limited. Max 10 req/min.")
    _rate_buckets[client_ip].append(now)


def check_token_rate_limit(token: str):
    """M-03 improvement: Rate limit per device token — voorkomt IP rotatie aanvallen."""
    now = time.time()
    _token_buckets[token] = [t for t in _token_buckets[token] if now - t < RATE_WINDOW]
    if len(_token_buckets[token]) >= RATE_LIMIT:
        raise HTTPException(status_code=429, detail="Rate limited. Max 10 req/min per device.")
    _token_buckets[token].append(now)


def validate_token(token: str) -> bool:
    if not DEVICE_TOKEN or not token:
        return False
    if len(token) != len(DEVICE_TOKEN):
        return False
    result = 0
    for a, b in zip(token, DEVICE_TOKEN):
        result |= ord(a) ^ ord(b)
    return result == 0


def extract_token_from_header(authorization: str = Header(None)) -> str:
    """
    K-03 fix: Extract device token from Authorization header instead of URL query parameter.
    Prevents token leakage in proxy/server logs.

    Expects: "Authorization: Bearer <token>"
    """
    if not authorization:
        raise HTTPException(status_code=401, detail="Missing Authorization header")
    if not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Invalid Authorization header format. Expected: Bearer <token>")
    token = authorization[7:]  # Strip "Bearer "
    if not token:
        raise HTTPException(status_code=401, detail="Empty token in Authorization header")
    return token


# ============== Microsoft Graph ==============

def save_token_secure(data: dict):
    """H-02 fix: Save token with restrictive permissions (600 = owner read/write only)."""
    with open(TOKEN_FILE, "w") as f:
        json.dump(data, f)
    os.chmod(TOKEN_FILE, 0o600)  # Alleen owner read/write


def refresh_microsoft_token():
    if not MS_CONFIG_FILE.exists():
        raise HTTPException(status_code=503, detail="Calendar not configured")
    with open(MS_CONFIG_FILE) as f:
        config = json.load(f)
    if not TOKEN_FILE.exists():
        raise HTTPException(status_code=503, detail="Calendar not configured")
    with open(TOKEN_FILE) as f:
        token_data = json.load(f)
    refresh_token = token_data.get("refresh_token")
    if not refresh_token:
        raise HTTPException(status_code=503, detail="No refresh token available")

    import urllib.parse
    # client_secret is OPTIONAL (v2.1.7): public clients (device-code flow, see
    # tools/ms_device_login.py) have no secret; confidential clients send it.
    form = {
        "client_id": config["client_id"],
        "refresh_token": refresh_token,
        "grant_type": "refresh_token",
        "redirect_uri": config.get("redirect_uri", "http://localhost:8080"),
    }
    if config.get("client_secret"):
        form["client_secret"] = config["client_secret"]
    data = urllib.parse.urlencode(form).encode()

    req = Request(
        "https://login.microsoftonline.com/common/oauth2/v2.0/token",
        data=data,
        headers={"Content-Type": "application/x-www-form-urlencoded"},
    )
    try:
        with urlopen(req, timeout=10) as resp:
            new_tokens = json.loads(resp.read())
    except Exception:
        raise HTTPException(status_code=503, detail="Calendar service unavailable")
    if "access_token" not in new_tokens:
        raise HTTPException(status_code=503, detail="Calendar service unavailable")
    save_token_secure(new_tokens)


def get_access_token() -> str:
    with open(TOKEN_FILE) as f:
        return json.load(f)["access_token"]


def get_calendar_events(token: str, start: datetime, end: datetime) -> list:
    url = (
        "https://graph.microsoft.com/v1.0/me/calendarview"
        f"?startDateTime={start.strftime('%Y-%m-%dT%H:%M:%SZ')}"
        f"&endDateTime={end.strftime('%Y-%m-%dT%H:%M:%SZ')}"
    )
    req = Request(url, headers={
        "Authorization": f"Bearer {token}",
        "Prefer": 'outlook.timezone="Europe/Amsterdam"',
    })
    with urlopen(req, timeout=10) as resp:
        return json.loads(resp.read()).get("value", [])


# ============== Rule-based detection ==============

def detect_rule(events: list, target_date: datetime, config: dict) -> tuple:
    """
    Pick the best matching rule for the target date.
    Never returns event subjects (privacy) — only match counts.
    Returns (rule_dict, confidence).
    """
    day_start = target_date.replace(hour=0, minute=0, second=0, microsecond=0)
    day_end = day_start + timedelta(days=1)

    day_events = []
    for event in events:
        # v2.6.14: geannuleerde meetings (Graph isCancelled=true of "geannuleerd" in titel) negeren
        if event.get("isCancelled") or event.get("status") == "cancelled":
            continue
        subject_raw = event.get("subject", "") or ""
        if "geannuleerd" in subject_raw.lower():
            continue
        start_str = event.get("start", {}).get("dateTime", "")
        if not start_str:
            continue
        event_start = datetime.fromisoformat(start_str.replace("Z", "+00:00"))
        if day_start.date() <= event_start.date() < day_end.date():
            day_events.append(event)

    rules = config.get("rules", [])

    # All-day "off" events win immediately (e.g. verlof/vakantie)
    for event in day_events:
        if event.get("isAllDay"):
            subject = event.get("subject", "").lower()
            for rule in rules:
                if rule["id"] == "off":
                    for kw in rule.get("keywords", []):
                        if kw in subject:
                            return rule, "high"

    # Priority: "off" > "office" > "home"
    rule_priority = {"off": 3, "office": 2, "home": 1}
    best_rule = None
    best_score = 0
    best_priority = 0
    for rule in rules:
        score = 0
        for event in day_events:
            subject = event.get("subject", "").lower()
            body = event.get("body", {}).get("content", "").lower()
            location_field = event.get("location", {}).get("displayName", "").lower()
            categories = " ".join(c.lower() for c in event.get("categories", []))
            full_text = f"{subject} {body} {location_field} {categories}"
            for kw in rule.get("keywords", []):
                if kw in full_text:
                    score += 1
        priority = rule_priority.get(rule["id"], 0)
        if score > best_score or (score == best_score and priority > best_priority):
            best_score = score
            best_rule = rule
            best_priority = priority

    # v2.5.2 fix: Als GEEN events matchen (best_score == 0), val terug op default/weekend
    # Previously: best_rule werd altijd teruggegeven, ook als score 0 was → office 07:00
    if best_score == 0:
        weekday = target_date.weekday()
        if weekday >= 5:
            weekend_id = config.get("weekend_rule_id", "").strip().lower()
            if not weekend_id:
                return {"id": "none", "name": "Geen alarm", "time": None}, "none"
            fallback_id = weekend_id
        else:
            fallback_id = config.get("default_rule_id", "").strip().lower()
        
        for rule in rules:
            if rule["id"] == fallback_id:
                return rule, "low"
        # Als default/weekend rule niet bestaat, GEEN alarm
        return {"id": "none", "name": "Geen alarm", "time": None}, "none"

    if best_rule is not None:
        return best_rule, "high" if best_score > 1 else "medium"

    # Fallback: weekend or default rule
    weekday = target_date.weekday()
    # Weekend (zaterdag=5, zondag=6): als geen weekend_rule_id is ingesteld → GEEN alarm
    if weekday >= 5:
        weekend_id = config.get("weekend_rule_id", "").strip().lower()
        if not weekend_id:
            # Geen weekend regel ingesteld → geen alarm in het weekend
            return {"id": "none", "name": "Geen alarm", "time": None}, "none"
        fallback_id = weekend_id
    else:
        fallback_id = config.get("default_rule_id", "").strip().lower()
    
    for rule in rules:
        if rule["id"] == fallback_id:
            return rule, "low"

    return (rules[0] if rules else {"id": "unknown", "name": "Onbekend", "time": "08:00"}), "low"


# ============== FastAPI App ==============

app = FastAPI(title="Calendar Alarm API", version="2.1.6")


@app.on_event("startup")
async def startup_check():
    """Server startup validatie — waarschuwt als kritieke bestanden verkeerde permissions hebben."""
    for f in [TOKEN_FILE, MS_CONFIG_FILE]:
        if f.exists():
            mode = stat.S_IMODE(f.stat().st_mode)
            if mode & 0o077:  # Group/other permissions
                print(f"⚠️  WARNING: {f} has overly permissive mode {oct(mode)} — should be 600")


@app.get("/health")
async def health():
    return {"status": "ok", "service": "calendar-alarm-api"}


@app.get("/api/alarm/next")
async def get_next_alarm(
    request: FastAPIRequest,
    authorization: str = Header(None),
    date: str = Query(None),
):
    check_rate_limit(request.client.host if request.client else "unknown")
    token = extract_token_from_header(authorization)
    check_token_rate_limit(token)  # M-03: per-token rate limiting
    if not validate_token(token):
        raise HTTPException(status_code=401, detail="Invalid token")

    try:
        from zoneinfo import ZoneInfo
        amsterdam_tz = ZoneInfo("Europe/Amsterdam")
    except ImportError:
        amsterdam_tz = timezone(timedelta(hours=1))

    if date:
        try:
            target_date = datetime.strptime(date, "%Y-%m-%d").replace(tzinfo=amsterdam_tz)
        except ValueError:
            raise HTTPException(status_code=400, detail="Invalid date format. Use YYYY-MM-DD")
    else:
        target_date = (datetime.now(amsterdam_tz) + timedelta(days=1)).replace(
            hour=0, minute=0, second=0, microsecond=0
        )

    config = load_alarm_config()

    try:
        refresh_microsoft_token()
        events = get_calendar_events(get_access_token(), target_date, target_date + timedelta(days=1))
        rule, confidence = detect_rule(events, target_date, config)
    except HTTPException:
        raise
    except Exception:
        raise HTTPException(status_code=503, detail="Calendar service error")

    # Geen alarm gepland (bijv. weekend zonder weekend regel)
    if rule.get("time") is None:
        return {
            "date": target_date.strftime("%Y-%m-%d"),
            "wake_time": None,
            "label": "Geen alarm",
            "work_location": "none",
            "confidence": "none",
            "event_count": len(events),
        }

    return {
        "date": target_date.strftime("%Y-%m-%d"),
        "wake_time": rule["time"],
        "label": rule['name'],
        "work_location": rule["id"],
        "confidence": confidence,
        "event_count": len(events),
    }


@app.get("/api/config")
async def get_config(request: FastAPIRequest, authorization: str = Header(None)):
    """Return the current alarm rules config (editable from the app)."""
    check_rate_limit(request.client.host if request.client else "unknown")
    token = extract_token_from_header(authorization)
    check_token_rate_limit(token)  # M-03: per-token rate limiting
    if not validate_token(token):
        raise HTTPException(status_code=401, detail="Invalid token")
    return load_alarm_config()


@app.post("/api/config")
async def update_config(request: FastAPIRequest, authorization: str = Header(None)):
    """Replace the alarm rules config. Validated server-side before persisting."""
    # M-05 fix: Content-Type validatie
    content_type = request.headers.get("content-type", "")
    if not content_type.startswith("application/json"):
        raise HTTPException(status_code=415, detail="Content-Type must be application/json")
    
    check_rate_limit(request.client.host if request.client else "unknown")
    token = extract_token_from_header(authorization)
    check_token_rate_limit(token)  # M-03: per-token rate limiting
    if not validate_token(token):
        raise HTTPException(status_code=401, detail="Invalid token")

    # Body size limit VOOR parsing
    raw = await request.body()
    if len(raw) > MAX_CONFIG_SIZE:
        raise HTTPException(status_code=413, detail="Config payload too large")
    try:
        config = json.loads(raw)
    except Exception:
        raise HTTPException(status_code=400, detail="Invalid JSON body")

    try:
        save_alarm_config(config)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception:
        raise HTTPException(status_code=500, detail="Could not save config")

    return {"status": "ok", "rules": len(config.get("rules", []))}


# ============== Log Upload ==============

try:
    LOGS_DIR.mkdir(exist_ok=True, mode=0o700)
except OSError:
    # /data may not exist yet outside the container (e.g. unit tests);
    # the log endpoint will fail with a clear 500 if it is truly missing.
    pass


@app.post("/api/log")
async def upload_logs(request: FastAPIRequest, authorization: str = Header(None)):
    # M-05 fix: Content-Type validatie
    content_type = request.headers.get("content-type", "")
    if not content_type.startswith("application/json"):
        raise HTTPException(status_code=415, detail="Content-Type must be application/json")
    
    check_rate_limit(request.client.host if request.client else "unknown")
    token = extract_token_from_header(authorization)
    check_token_rate_limit(token)  # M-03: per-token rate limiting
    if not validate_token(token):
        raise HTTPException(status_code=401, detail="Invalid token")

    # M-05: Body size limit VOOR parsing (1 MB max)
    raw = await request.body()
    if len(raw) > MAX_LOG_PAYLOAD_SIZE:
        raise HTTPException(status_code=413, detail="Payload too large (max 1 MB)")
    
    try:
        body = json.loads(raw)
    except Exception:
        raise HTTPException(status_code=400, detail="Invalid JSON body")

    entries = body.get("entries", [])
    if not isinstance(entries, list) or len(entries) > MAX_LOG_BATCH:
        raise HTTPException(status_code=400, detail=f"Invalid entries (max {MAX_LOG_BATCH})")

    log_file = LOGS_DIR / "calendar-alarm-device.log"
    lines = []
    for e in entries:
        if not isinstance(e, dict):
            continue
        ts = str(e.get("timestamp", ""))[:30]
        level = str(e.get("level", "INFO"))[:10].upper()
        tag = str(e.get("tag", ""))[:50]
        msg = str(e.get("message", ""))[:MAX_LOG_LINE]
        # H-05: Verbeterde token sanitization met regex
        if DEVICE_TOKEN:
            msg = msg.replace(DEVICE_TOKEN, "<TOKEN>")
        msg = HEX_TOKEN_PATTERN.sub('<TOKEN>', msg)
        msg = CF_SECRET_PATTERN.sub('<CF_SECRET>', msg)
        lines.append(f"{ts} [{level}] {tag}: {msg}")

    if lines:
        try:
            if log_file.exists() and log_file.stat().st_size > 5 * 1024 * 1024:
                log_file.rename(log_file.with_suffix(".log.old"))
            with open(log_file, "a") as f:
                f.write("\n".join(lines) + "\n")
            # H4: log file must stay owner-only (umask 077 already creates it 600)
            os.chmod(log_file, 0o600)
        except Exception:
            raise HTTPException(status_code=500, detail="Could not write logs")

    return {"received": len(lines)}


# ============== Run ==============

if __name__ == "__main__":
    # H-01: in production the container binds 0.0.0.0 on an internal Docker
    # network only (see Dockerfile/docker-compose.yml); an edge proxy terminates
    # TLS and enforces access policy. For local development use:
    #   uvicorn main:app --host 127.0.0.1 --port 8100
    uvicorn.run(app, host="127.0.0.1", port=8100, log_level="info")
