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
    - HTTPS only (via Traefik/Cloudflare)
    - Device token auth (constant-time compare, via Authorization header)
    - Rate limited (10 req/min per IP + per device token)
    - Content-Type validation on POST endpoints (M-05)
    - Payload size limits (1 MB logs, 100 KB config)
    - Token file permissions 600 — owner-only (H-02)
    - Log sanitization with regex: hex tokens + CF secrets (H-05)
    - No sensitive data in logs (no calendar subjects)
"""

import hmac
import json
import os
import re
import stat
import threading
import time

# H4 hardening: new files created by this process get owner-only permissions (600).
os.umask(0o077)

from collections import defaultdict
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.request import Request, urlopen

from fastapi import FastAPI, Query, Header, HTTPException, Request as FastAPIRequest
from fastapi.concurrency import run_in_threadpool
from pydantic import BaseModel, Field, field_validator
import uvicorn

# ============== Configuration ==============

# H4 hardening: default to the isolated data dir (compose sets CALALARM_WORKSPACE=/data).
# Only this data dir is mounted; nothing else from the host is visible.
WORKSPACE = Path(os.environ.get("CALALARM_WORKSPACE", "/data"))
TOKEN_FILE = WORKSPACE / os.environ.get("CALALARM_MS_TOKEN_FILE", "ms_token.json")
MS_CONFIG_FILE = WORKSPACE / os.environ.get("CALALARM_MS_CONFIG_FILE", "ms_app_config.json")
ALARM_CONFIG_FILE = WORKSPACE / os.environ.get("CALALARM_ALARM_CONFIG_FILE", "alarm_config.json")
DEVICE_TOKEN = os.environ.get("CALALARM_DEVICE_TOKEN", "")
# P4 review: separate read-only token for the homelab monitor (only /api/heartbeat/status),
# so the phone credential does not need to live on the monitor host. Empty = not configured.
MONITOR_TOKEN = os.environ.get("CALALARM_MONITOR_TOKEN", "")

_rate_buckets: dict = defaultdict(list)
_token_buckets: dict = defaultdict(list)  # M-03/#20: per-token rate limiting
RATE_LIMIT = 10
RATE_WINDOW = 60
# #20: bound the memory of the rate-limit buckets. Without this they grow
# _rate_buckets/_token_buckets without limit with many different IPs/tokens.
MAX_RATE_BUCKETS = 10_000          # max aantal gevolgde sleutels (IP resp. token)
RATE_BUCKET_TTL = RATE_WINDOW * 10  # buckets ouder dan dit worden opgeruimd

LOGS_DIR = WORKSPACE / "logs"
# B2: last-known-good plan cache — after every successful calendar call the
# computed plan is stored atomically per date; on calendar failure this
# plan is returned instead of a default rule (which the app could
# interpret as "wake up later" → oversleeping).
PLANS_DIR = WORKSPACE / "plans"
PLAN_CACHE_DAYS = 14  # max bewaartermijn cached plans (YYYY-MM-DD.json)
MAX_LOG_BATCH = 500
MAX_LOG_LINE = 1000
MAX_CONFIG_SIZE = 100 * 1024  # 100 KB cap for config payloads
MAX_LOG_PAYLOAD_SIZE = 1 * 1024 * 1024  # 1 MB cap for log payloads (M-05)

# Phase 4 (principle 7): heartbeat from the phone — "does the phone have the alarm
# the server expects?" The app periodically posts (every 3h + after each sync) which
# alarms are scheduled + the permission status; the server compares that with the
# cached plan for tomorrow (/data/plans/<date>.json) and returns a verdict.
HEARTBEAT_FILE = WORKSPACE / "heartbeat.json"
HEARTBEAT_MAX_AGE_S = 8 * 3600  # ouder dan 8u → verdict "stale"
MAX_HEARTBEAT_PAYLOAD_SIZE = 64 * 1024  # 64 KB cap (50 entries × ~150 B = ample)
MAX_HEARTBEAT_SCHEDULED = 50
MAX_HEARTBEAT_LABEL = 100
# Strict regex validation for the heartbeat fields (P4: server NEVER trusts
# client input without validation/sanitization).
HB_DATE_PATTERN = re.compile(r"^\d{4}-\d{2}-\d{2}$")
HB_TIME_PATTERN = re.compile(r"^([01][0-9]|2[0-3]):[0-5][0-9]$")
HB_SENT_AT_PATTERN = re.compile(
    r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,6})?(Z|[+-]\d{2}:?\d{2})$"
)

# H-05: Regex patterns for token sanitization in logs
HEX_TOKEN_PATTERN = re.compile(r'\b[a-f0-9]{64}\b')
CF_SECRET_PATTERN = re.compile(r'cfast_[A-Za-z0-9_-]+')

# #31: control characters (\r \n \t \x00..\x1f, \x7f-\x9f) are stripped from log
# fields so a client cannot inject/forge log lines.
CONTROL_CHARS_PATTERN = re.compile(r'[\x00-\x1f\x7f-\x9f]')


def sanitize_log_field(value) -> str:
    """#31: Neutralize control characters in received log fields (anti log injection)."""
    return CONTROL_CHARS_PATTERN.sub('', str(value))

# ============== Alarm Config (dynamic rules) ==============

DEFAULT_ALARM_CONFIG = {
    "rules": [
        {
            "id": "office",
            "name": "Kantoor dag",
            "time": "07:00",
            "keywords": ["kantoor", "office", "example-corp"],
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

    # Weekend and default rule may be EMPTY (no alarm on weekend / no default)
    for key in ("default_rule_id", "weekend_rule_id"):
        val = str(config.get(key, "") or "").strip().lower()
        if val and val not in seen_ids:
            raise ValueError(f"Unknown {key}: {val}")
        config[key] = val  # May be empty!

    ALARM_CONFIG_FILE.parent.mkdir(parents=True, exist_ok=True)
    tmp = ALARM_CONFIG_FILE.with_suffix(".tmp")
    with open(tmp, "w") as f:
        json.dump(config, f, indent=2)
    os.chmod(tmp, 0o600)  # H4: config may be considered sensitive — owner-only
    tmp.replace(ALARM_CONFIG_FILE)


# ============== Rate Limiting & Auth ==============

def _prune_buckets(buckets: dict, now: float):
    """#20: prune buckets — remove empty/expired entries and cap the number of
    keys (oldest are removed first). Prevents unbounded growth."""
    stale = [k for k, v in buckets.items() if not v or now - v[-1] > RATE_BUCKET_TTL]
    for k in stale:
        buckets.pop(k, None)
    while len(buckets) > MAX_RATE_BUCKETS:
        # oudste bucket eerst (laagste laatste-tijdstip)
        oldest = min(buckets, key=lambda k: buckets[k][-1] if buckets[k] else 0.0)
        buckets.pop(oldest, None)


def _hit(buckets: dict, key: str, limit: int, window: int, detail: str):
    now = time.time()
    if len(buckets) >= MAX_RATE_BUCKETS or len(buckets) % 97 == 0:
        _prune_buckets(buckets, now)
    stamps = [t for t in buckets.get(key, []) if now - t < window]
    if len(stamps) >= limit:
        raise HTTPException(status_code=429, detail=detail)
    stamps.append(now)
    buckets[key] = stamps


def check_rate_limit(client_ip: str):
    """Per-IP rate limit (anonymous, before auth — protects against brute force)."""
    _hit(_rate_buckets, client_ip, RATE_LIMIT, RATE_WINDOW,
         "Rate limited. Max 10 req/min.")


def check_token_rate_limit(token: str):
    """#20 + M-03: per-token rate limit — only applied AFTER successful auth,
    so an attacker with random tokens cannot fill the token-bucket table.
    Prevents IP-rotation attacks."""
    _hit(_token_buckets, token, RATE_LIMIT, RATE_WINDOW,
         "Rate limited. Max 10 req/min per device.")


def validate_token(token: str) -> bool:
    # #30: hmac.compare_digest — constant-time compare (timing-attack resistant),
    # even when the lengths differ (compare_digest handles unequal length
    # without early-exit on length).
    if not DEVICE_TOKEN or not token:
        return False
    return hmac.compare_digest(token.encode("utf-8"), DEVICE_TOKEN.encode("utf-8"))


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

# #21: lock around token refresh so two concurrent requests do not burn
# the same refresh_token twice (Microsoft then revokes the whole chain).
_ms_token_lock = threading.Lock()
# #21: in-memory cache van access_token + vervaltijdstip (UTC epoch).
_ms_token_cache: dict = {"access_token": None, "expires_at": 0.0}
# Safety margin: refresh the token 60 s before the official expiry.
_TOKEN_EXPIRY_MARGIN = 60.0


def save_token_secure(data: dict):
    """#21 + H-02: atomic write (tmp file + os.replace) with 600 permissions.

    os.replace is atomic on POSIX: readers see either the old or the new
    file, never a half-written JSON. The tmp file gets the same
    owner-only permissions before the replace.
    """
    TOKEN_FILE.parent.mkdir(parents=True, exist_ok=True)
    tmp = TOKEN_FILE.with_name(TOKEN_FILE.name + ".tmp")
    with open(tmp, "w") as f:
        json.dump(data, f)
    os.chmod(tmp, 0o600)  # Owner read/write only, before the rename
    os.replace(tmp, TOKEN_FILE)
    # Update the cache so get_access_token uses the new token immediately
    try:
        expires_in = float(data.get("expires_in", 3600))
    except (TypeError, ValueError):
        expires_in = 3600.0
    _ms_token_cache["access_token"] = data.get("access_token")
    _ms_token_cache["expires_at"] = time.time() + max(expires_in - _TOKEN_EXPIRY_MARGIN, 0.0)


def refresh_microsoft_token():
    """Refresh the MS access token. Must be called under _ms_token_lock
    (happens via get_access_token)."""
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
    # client_secret is OPTIONAL: public clients (device-code flow, see
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
    # N2: MS does not always return a new refresh_token — keep the old one
    # (otherwise you lose the only valid refresh token and the calendar is dead).
    new_tokens.setdefault("refresh_token", refresh_token)
    save_token_secure(new_tokens)


def get_access_token() -> str:
    """#21: return the cached access token if still valid (expires_in),
    otherwise refresh a new one under the lock. Prevents a token refresh per request.
    """
    with _ms_token_lock:
        if _ms_token_cache["access_token"] and time.time() < _ms_token_cache["expires_at"]:
            return _ms_token_cache["access_token"]
        refresh_microsoft_token()
        token = _ms_token_cache.get("access_token")
        if not token:
            # Fallback: cache miste (bv. proces herstart) — lees van disk
            with open(TOKEN_FILE) as f:
                token = json.load(f)["access_token"]
        return token


def _to_utc(dt: datetime) -> datetime:
    """#23: naive datetimes are treated as UTC (container TZ=UTC);
    tz-aware datetimes are correctly converted to UTC."""
    if dt.tzinfo is None:
        return dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc)


def get_calendar_events(token: str, start: datetime, end: datetime) -> list:
    # #23: calendar query must be in UTC (real UTC, not Amsterdam time with a 'Z' appended).
    # Before this fix, Europe/Amsterdam time was labeled as 'Z' (UTC) → 1-2 hour shift.
    start_utc = _to_utc(start)
    end_utc = _to_utc(end)
    url = (
        "https://graph.microsoft.com/v1.0/me/calendarview"
        f"?startDateTime={start_utc.strftime('%Y-%m-%dT%H:%M:%SZ')}"
        f"&endDateTime={end_utc.strftime('%Y-%m-%dT%H:%M:%SZ')}"
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
        # v2.6.14: ignore cancelled meetings (Graph isCancelled=true or "geannuleerd" in title)
        if event.get("isCancelled") or event.get("status") == "cancelled":
            continue
        subject_raw = event.get("subject", "") or ""
        if "geannuleerd" in subject_raw.lower():  # PRESERVED: Dutch keyword matched against server data
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

    # v2.5.2 fix: If NO events match (best_score == 0), fall back to default/weekend
    # Previously: best_rule was always returned, even when score 0 was → office 07:00
    if best_score == 0:
        weekday = target_date.weekday()
        if weekday >= 5:
            weekend_id = config.get("weekend_rule_id", "").strip().lower()
            if not weekend_id:
                return {"id": "none", "name": "No alarm", "time": None}, "none"
            fallback_id = weekend_id
        else:
            fallback_id = config.get("default_rule_id", "").strip().lower()
        
        for rule in rules:
            if rule["id"] == fallback_id:
                return rule, "low"
        # If the default/weekend rule does not exist, NO alarm
        return {"id": "none", "name": "No alarm", "time": None}, "none"

    if best_rule is not None:
        return best_rule, "high" if best_score > 1 else "medium"

    # Fallback: weekend or default rule
    weekday = target_date.weekday()
    # Weekend (Saturday=5, Sunday=6): if no weekend_rule_id is set → NO alarm
    if weekday >= 5:
        weekend_id = config.get("weekend_rule_id", "").strip().lower()
        if not weekend_id:
            # No weekend rule set → no alarm on weekends
            return {"id": "none", "name": "No alarm", "time": None}, "none"
        fallback_id = weekend_id
    else:
        fallback_id = config.get("default_rule_id", "").strip().lower()
    
    for rule in rules:
        if rule["id"] == fallback_id:
            return rule, "low"

    return (rules[0] if rules else {"id": "unknown", "name": "Unknown", "time": "08:00"}), "low"


# ============== Plan cache (B2: last-known-good) ==============

def _plan_path(date_str: str) -> Path:
    return PLANS_DIR / f"{date_str}.json"


def save_plan_cache(date_str: str, plan: dict):
    """B2: plan atomisch opslaan (tmp + os.replace, mode 600) in /data/plans/<date>.json."""
    try:
        PLANS_DIR.mkdir(parents=True, exist_ok=True, mode=0o700)
        tmp = _plan_path(date_str).with_suffix(".json.tmp")
        # P4 review B2: store the caching time, so /api/heartbeat/status can see whether
        # the plan is newer than the last heartbeat (→ "pending" instead of a false P1).
        stored = dict(plan)
        stored["cached_at"] = datetime.now(timezone.utc).isoformat()
        with open(tmp, "w") as f:
            json.dump(stored, f)
        os.chmod(tmp, 0o600)
        os.replace(tmp, _plan_path(date_str))
    except OSError:
        pass  # cache is best-effort — failing must never break the API


def load_plan_cache(date_str: str):
    """B2: read the cached plan, or None if absent/unreadable."""
    try:
        p = _plan_path(date_str)
        if p.exists():
            return json.loads(p.read_text())
    except Exception:
        pass
    return None


def prune_plan_cache():
    """B2: cached plans ouder dan PLAN_CACHE_DAYS opruimen (best-effort)."""
    try:
        if not PLANS_DIR.exists():
            return
        cutoff = (datetime.now(timezone.utc) - timedelta(days=PLAN_CACHE_DAYS)).date()
        for p in PLANS_DIR.glob("*.json"):
            try:
                d = datetime.strptime(p.stem, "%Y-%m-%d").date()
                if d < cutoff:
                    p.unlink()
            except ValueError:
                continue
    except OSError:
        pass


# ============== Fase 4: Heartbeat (telefoon meldt ingeplande wekkers) ==============

def _parse_hb_iso(value: str) -> datetime:
    """P4: parse ISO-8601 timestamp; naive → UTC (same rule as _to_utc)."""
    return _to_utc(datetime.fromisoformat(value.replace("Z", "+00:00")))


def save_heartbeat(payload: dict, received_at: datetime) -> None:
    """P4: heartbeat atomisch opslaan in /data/heartbeat.json (tmp + os.replace,
    mode 600 — same pattern as the token file / save_plan_cache)."""
    data = dict(payload)
    data["received_at"] = received_at.astimezone(timezone.utc).isoformat()
    tmp = HEARTBEAT_FILE.with_suffix(".json.tmp")
    with open(tmp, "w") as f:
        json.dump(data, f)
    os.chmod(tmp, 0o600)  # owner-only, before the rename
    os.replace(tmp, HEARTBEAT_FILE)


def load_heartbeat():
    """P4: read the last stored heartbeat, or None if absent/unreadable."""
    try:
        if HEARTBEAT_FILE.exists():
            return json.loads(HEARTBEAT_FILE.read_text())
    except Exception:
        pass
    return None


def _hb_tomorrow_date(now: datetime) -> str:
    """P4: 'tomorrow' in Europe/Amsterdam (same zone as the alarm plan)."""
    try:
        from zoneinfo import ZoneInfo
        amsterdam = ZoneInfo("Europe/Amsterdam")
    except ImportError:
        amsterdam = timezone(timedelta(hours=1))
    return (now.astimezone(amsterdam) + timedelta(days=1)).strftime("%Y-%m-%d")


def compute_heartbeat_status(now: datetime | None = None) -> dict:
    """P4: compute the verdict based on the last heartbeat + the plan cache
    for tomorrow.

    Verdict rules (order = priority):
      - "stale"        : no heartbeat, or older than HEARTBEAT_MAX_AGE_S (8h)
      - "permissions"  : critical permissions (notifications/full_screen/exact_alarms)
                         missing — takes PRIORITY over ok/missing/mismatch
      - "no_plan"      : no cached plan for tomorrow (not fetched yet)
      - "ok"           : plan expects no alarm (wake_time=null) OR the phone
                         has an enabled, non-snooze entry on that date
      - "user_disabled": user turned off the alarm for tomorrow themselves (silent)
      - "pending"      : plan is newer than the last heartbeat (status uncertain)
      - "missing"      : plan expects an alarm, phone has none
      - "mismatch"     : phone has an alarm on that date, but at a
                         different time than the plan
    """
    now = now or datetime.now(timezone.utc)
    hb = load_heartbeat()

    # --- stale check ---
    if hb is None:
        return {
            "last_heartbeat_at": None,
            "age_seconds": None,
            "expected_tomorrow": None,
            "phone_has": [],
            "verdict": "stale",
            "details": "No heartbeat received from the phone",
        }
    try:
        last_at = _parse_hb_iso(hb.get("received_at") or hb.get("sent_at", ""))
        age = (now - last_at).total_seconds()
    except Exception:
        return {
            "last_heartbeat_at": hb.get("received_at"),
            "age_seconds": None,
            "expected_tomorrow": None,
            "phone_has": [],
            "verdict": "stale",
            "details": "Heartbeat contains no valid timestamp",
        }
    last_heartbeat_at = last_at.astimezone(timezone.utc).isoformat()
    if age < 0:
        age = 0.0  # clock skew client/server — do not make it "stale"
    if age > HEARTBEAT_MAX_AGE_S:
        return {
            "last_heartbeat_at": last_heartbeat_at,
            "age_seconds": int(age),
            "expected_tomorrow": None,
            "phone_has": [],
            "verdict": "stale",
            "details": f"Laatste heartbeat is {int(age)}s geleden (> {HEARTBEAT_MAX_AGE_S}s)",
        }

    # --- plan for tomorrow from the cache ---
    tomorrow = _hb_tomorrow_date(now)
    plan = load_plan_cache(tomorrow)
    if plan is None:
        expected = {"date": tomorrow, "wake_time": None, "source": None}
        expected_reason = "no_plan_cache"
    else:
        expected = {
            "date": plan.get("date", tomorrow),
            "wake_time": plan.get("wake_time"),
            "source": plan.get("source"),
        }
        expected_reason = None

    scheduled = hb.get("scheduled") or []
    phone_tomorrow = [
        e for e in scheduled
        if isinstance(e, dict) and e.get("date") == tomorrow
    ]
    # P4 review (privacy): no labels in the status (manual labels are free text)
    phone_has = [
        {
            "time": e.get("time"),
            "enabled": bool(e.get("enabled")),
            "is_snooze": bool(e.get("is_snooze")),
            "user_disabled": bool(e.get("user_disabled")),
        }
        for e in phone_tomorrow
    ]

    # --- permissions hebben voorrang op ok ---
    perms = hb.get("permissions") or {}
    missing_perms = [
        k for k in ("notifications", "full_screen", "exact_alarms")
        if perms.get(k) is not True
    ]
    if missing_perms:
        return {
            "last_heartbeat_at": last_heartbeat_at,
            "age_seconds": int(age),
            "expected_tomorrow": expected,
            "phone_has": phone_has,
            "verdict": "permissions",
            "details": f"Critical permissions missing on the phone: {', '.join(missing_perms)}"
                       + (f" (plan tomorrow: {plan.get('wake_time') or 'no alarm'})" if plan else ""),
        }

    # --- no plan for tomorrow → no comparison possible ---
    if plan is None:
        return {
            "last_heartbeat_at": last_heartbeat_at,
            "age_seconds": int(age),
            "expected_tomorrow": expected,
            "phone_has": phone_has,
            "verdict": "no_plan",
            "details": f"No cached plan for {tomorrow} (not fetched by the app yet)",
        }

    wake_time = plan.get("wake_time")

    # --- plan expects no alarm → ok (regardless of what the phone has) ---
    if wake_time is None:
        return {
            "last_heartbeat_at": last_heartbeat_at,
            "age_seconds": int(age),
            "expected_tomorrow": expected,
            "phone_has": phone_has,
            "verdict": "ok",
            "details": f"Plan for {tomorrow} expects no alarm",
        }

    # --- plan expects an alarm ---
    # P4 review B1: the user turned off tomorrow's alarm themselves → deliberate choice, silent.
    user_off = [
        e for e in phone_tomorrow
        if isinstance(e, dict) and e.get("user_disabled") is True and e.get("is_snooze") is not True
    ]
    # P4 review B2: plan newer than the last heartbeat → the phone may not have
    # processed it yet; do not report it as P1.
    plan_newer = False
    try:
        cached_at = plan.get("cached_at")
        if cached_at:
            plan_newer = _parse_hb_iso(cached_at) > last_at
    except Exception:
        plan_newer = False
    active = [
        e for e in phone_tomorrow
        if isinstance(e, dict) and e.get("enabled") is True and e.get("is_snooze") is not True
    ]
    # User turned off the SCHEDULED time themselves (possibly with their own other alarm)
    # → deliberate choice, no mismatch/missing.
    if any(e.get("time") == wake_time for e in user_off):
        return {
            "last_heartbeat_at": last_heartbeat_at,
            "age_seconds": int(age),
            "expected_tomorrow": expected,
            "phone_has": phone_has,
            "verdict": "user_disabled",
            "details": f"Scheduled alarm {wake_time} for {tomorrow} was turned off by the user"
                       + (f"; own alarm(s): {', '.join(sorted(e.get('time') for e in active if e.get('time')))}" if active else ""),
        }
    if not active and user_off:
        return {
            "last_heartbeat_at": last_heartbeat_at,
            "age_seconds": int(age),
            "expected_tomorrow": expected,
            "phone_has": phone_has,
            "verdict": "user_disabled",
            "details": f"Alarm for {tomorrow} was turned off by the user",
        }
    if plan_newer and (not active or wake_time not in {e.get("time") for e in active}):
        return {
            "last_heartbeat_at": last_heartbeat_at,
            "age_seconds": int(age),
            "expected_tomorrow": expected,
            "phone_has": phone_has,
            "verdict": "pending",
            "details": f"Plan for {tomorrow} is newer than the last heartbeat — status uncertain",
        }
    if not active:
        return {
            "last_heartbeat_at": last_heartbeat_at,
            "age_seconds": int(age),
            "expected_tomorrow": expected,
            "phone_has": phone_has,
            "verdict": "missing",
            "details": f"Server expects an alarm at {wake_time} on {tomorrow}, but the phone has no enabled alarm for that date",
        }
    times = {e.get("time") for e in active}
    if wake_time not in times:
        return {
            "last_heartbeat_at": last_heartbeat_at,
            "age_seconds": int(age),
            "expected_tomorrow": expected,
            "phone_has": phone_has,
            "verdict": "mismatch",
            "details": f"Server verwacht {wake_time} op {tomorrow}, telefoon heeft {', '.join(sorted(t for t in times if t))}",
        }
    return {
        "last_heartbeat_at": last_heartbeat_at,
        "age_seconds": int(age),
        "expected_tomorrow": expected,
        "phone_has": phone_has,
        "verdict": "ok",
        "details": f"Phone has alarm {wake_time} on {tomorrow} — matches the plan",
    }


# ============== Fase 4: Heartbeat pydantic-modellen (strikte validatie) ==============

class HeartbeatScheduledEntry(BaseModel):
    """P4: one scheduled alarm as reported by the phone."""
    date: str = Field(min_length=10, max_length=10)
    time: str = Field(min_length=5, max_length=5)
    # label is accepted (older app builds send it) but NOT stored
    label: str = Field(default="", max_length=MAX_HEARTBEAT_LABEL)
    enabled: bool
    is_snooze: bool = False
    user_disabled: bool = False

    @field_validator("date")
    @classmethod
    def _date_ok(cls, v: str) -> str:
        if not HB_DATE_PATTERN.match(v):
            raise ValueError("date must be YYYY-MM-DD")
        try:
            datetime.strptime(v, "%Y-%m-%d")
        except ValueError:
            raise ValueError(f"invalid calendar date: {v}")
        return v

    @field_validator("time")
    @classmethod
    def _time_ok(cls, v: str) -> str:
        if not HB_TIME_PATTERN.match(v):
            raise ValueError("time must be HH:MM (00:00-23:59)")
        return v

    @field_validator("label")
    @classmethod
    def _label_sanitized(cls, v: str) -> str:
        # anti log injection: strip control characters (same rule as #31)
        return CONTROL_CHARS_PATTERN.sub("", v)[:MAX_HEARTBEAT_LABEL]


class HeartbeatPermissions(BaseModel):
    """P4: permission status of the phone."""
    notifications: bool
    full_screen: bool
    exact_alarms: bool
    battery_unrestricted: bool = False


class HeartbeatPayload(BaseModel):
    """P4: body van POST /api/heartbeat."""
    app_version: str = Field(min_length=1, max_length=32)
    version_code: int = Field(ge=1, le=10_000_000)
    sent_at: str = Field(min_length=20, max_length=40)
    scheduled: list[HeartbeatScheduledEntry] = Field(default_factory=list, max_length=MAX_HEARTBEAT_SCHEDULED)
    permissions: HeartbeatPermissions

    @field_validator("app_version")
    @classmethod
    def _app_version_sanitized(cls, v: str) -> str:
        v = CONTROL_CHARS_PATTERN.sub("", v)
        if not v.strip():
            raise ValueError("app_version must not be empty")
        return v[:32]

    @field_validator("sent_at")
    @classmethod
    def _sent_at_ok(cls, v: str) -> str:
        if not HB_SENT_AT_PATTERN.match(v):
            raise ValueError("sent_at must be ISO-8601 (e.g. 2026-11-22T21:00:00Z)")
        try:
            _parse_hb_iso(v)
        except ValueError:
            raise ValueError(f"invalid sent_at timestamp: {v}")
        return v


# ============== FastAPI App ==============

# #32/#33: @app.on_event is deprecated → lifespan context manager.
from contextlib import asynccontextmanager


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Server startup validation — warns if critical files have wrong permissions."""
    for f in [TOKEN_FILE, MS_CONFIG_FILE]:
        if f.exists():
            mode = stat.S_IMODE(f.stat().st_mode)
            if mode & 0o077:  # Group/other permissions
                print(f"⚠️  WARNING: {f} has overly permissive mode {oct(mode)} — should be 600")
    yield


def _fetch_calendar_events_sync(target_date: datetime) -> list:
    """#22: synchronous wrapper for the blocking MS calls; invoked via
    run_in_threadpool from the async handler."""
    return get_calendar_events(
        get_access_token(), target_date, target_date + timedelta(days=1)
    )


app = FastAPI(
    title="Calendar Alarm API",
    version="2.1.6",
    # #32/#33: no public API docs (docs/redoc/openapi) in production —
    # reduces attack surface; internal clients do not use OpenAPI.
    docs_url=None,
    redoc_url=None,
    openapi_url=None,
    lifespan=lifespan,
)


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
    if not validate_token(token):
        raise HTTPException(status_code=401, detail="Invalid token")
    # #20: per-token rate limit only after successful auth
    check_token_rate_limit(token)

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
    date_str = target_date.strftime("%Y-%m-%d")

    # #22: the whole MS call (token refresh + calendarview, both blocking urlopen)
    # runs in the threadpool so the async event loop never blocks.
    # B2 fail-safe: on calendar failure the last-known-good plan (cache) for this
    # date is returned — NEVER a default rule, because the app could
    # read it as "wake up later" and overwrite a good plan (e.g. office 06:30).
    # Without cache → 503 (old behavior): the app keeps the existing alarm and retries.
    source = "calendar"
    try:
        events = await run_in_threadpool(_fetch_calendar_events_sync, target_date)
    except Exception:
        cached = load_plan_cache(date_str)
        if cached is not None:
            cached = dict(cached)
            cached["source"] = "cache"
            return cached
        raise HTTPException(status_code=503, detail="Calendar service error")
    rule, confidence = detect_rule(events, target_date, config)

    # No alarm scheduled (e.g. weekend without a weekend rule)
    if rule.get("time") is None:
        plan = {
            "date": date_str,
            "wake_time": None,
            "label": "No alarm",
            "work_location": "none",
            "confidence": "none",
            "event_count": len(events),
            "source": source,
        }
    else:
        plan = {
            "date": date_str,
            "wake_time": rule["time"],
            "label": rule['name'],
            "work_location": rule["id"],
            "confidence": confidence,
            "event_count": len(events),
            "source": source,
        }
    # B2: cache the successful plan as last-known-good + prune old cache
    save_plan_cache(date_str, plan)
    prune_plan_cache()
    return plan


@app.get("/api/config")
async def get_config(request: FastAPIRequest, authorization: str = Header(None)):
    """Return the current alarm rules config (editable from the app)."""
    check_rate_limit(request.client.host if request.client else "unknown")
    token = extract_token_from_header(authorization)
    if not validate_token(token):
        raise HTTPException(status_code=401, detail="Invalid token")
    # #20: per-token rate limit only after successful auth
    check_token_rate_limit(token)
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
    if not validate_token(token):
        raise HTTPException(status_code=401, detail="Invalid token")
    # #20: per-token rate limit only after successful auth
    check_token_rate_limit(token)

    # Body size limit BEFORE parsing
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
    if not validate_token(token):
        raise HTTPException(status_code=401, detail="Invalid token")
    # #20: per-token rate limit only after successful auth
    check_token_rate_limit(token)

    # M-05: Body size limit BEFORE parsing (1 MB max)
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
        # #31: sanitize all fields before they go into the log file (anti log injection)
        ts = sanitize_log_field(e.get("timestamp", ""))[:30]
        level = sanitize_log_field(e.get("level", "INFO"))[:10].upper()
        tag = sanitize_log_field(e.get("tag", ""))[:50]
        msg = sanitize_log_field(e.get("message", ""))[:MAX_LOG_LINE]
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


# ============== Fase 4: Heartbeat endpoints ==============

@app.post("/api/heartbeat")
async def post_heartbeat(request: FastAPIRequest, authorization: str = Header(None)):
    """P4 (principle 7): the phone periodically posts here which alarms are
    scheduled + the permission status. Stored atomically in /data/heartbeat.json."""
    content_type = request.headers.get("content-type", "")
    if not content_type.startswith("application/json"):
        raise HTTPException(status_code=415, detail="Content-Type must be application/json")

    check_rate_limit(request.client.host if request.client else "unknown")
    token = extract_token_from_header(authorization)
    if not validate_token(token):
        raise HTTPException(status_code=401, detail="Invalid token")
    check_token_rate_limit(token)

    raw = await request.body()
    if len(raw) > MAX_HEARTBEAT_PAYLOAD_SIZE:
        raise HTTPException(status_code=413, detail="Heartbeat payload too large (max 64 KB)")
    try:
        body = json.loads(raw)
    except Exception:
        raise HTTPException(status_code=400, detail="Invalid JSON body")

    try:
        payload = HeartbeatPayload.model_validate(body)
    except Exception as e:
        # P4 review: only return field names, not input values
        fields = []
        try:
            fields = sorted({".".join(str(x) for x in err.get("loc", ())) for err in e.errors()})
        except Exception:
            pass
        raise HTTPException(status_code=422, detail={"error": "Invalid heartbeat payload", "fields": fields})

    data = payload.model_dump(mode="json")
    # P4 review (privacy): do not store labels — the verdict does not use them
    for entry in data.get("scheduled", []):
        entry.pop("label", None)
    received_at = datetime.now(timezone.utc)
    try:
        save_heartbeat(data, received_at)
    except OSError:
        raise HTTPException(status_code=500, detail="Could not persist heartbeat")

    return {
        "status": "ok",
        "received_at": received_at.astimezone(timezone.utc).isoformat(),
        "scheduled": len(data["scheduled"]),
    }


@app.get("/api/heartbeat/status")
async def get_heartbeat_status(request: FastAPIRequest, authorization: str = Header(None)):
    """P4: homelab monitor check — verdict of server vs. phone for tomorrow."""
    check_rate_limit(request.client.host if request.client else "unknown")
    token = extract_token_from_header(authorization)
    is_monitor = bool(MONITOR_TOKEN) and bool(token) and hmac.compare_digest(
        token.encode("utf-8"), MONITOR_TOKEN.encode("utf-8"))
    if not (is_monitor or validate_token(token)):
        raise HTTPException(status_code=401, detail="Invalid token")
    check_token_rate_limit(token)
    return compute_heartbeat_status()


# ============== Run ==============

if __name__ == "__main__":
    # H-01: in production the container binds 0.0.0.0 on an internal Docker
    # network only (see Dockerfile/docker-compose.yml); an edge proxy terminates
    # TLS and enforces access policy. For local development use:
    #   uvicorn main:app --host 127.0.0.1 --port 8100
    uvicorn.run(app, host="127.0.0.1", port=8100, log_level="info")
