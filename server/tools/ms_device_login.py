#!/usr/bin/env python3
"""Bootstrap the Microsoft Graph OAuth token for Calendar Alarm (device-code flow).

Standalone helper — standard library only (urllib), no pip install needed.
It runs the OAuth 2.0 Device Code flow against Microsoft Entra ID
(``https://login.microsoftonline.com/{tenant}/oauth2/v2.0/devicecode``),
prints the user code + verification URL, polls until the user signs in,
and writes the resulting token JSON to ``--out`` with chmod 600.

The produced file is exactly what ``server/main.py`` expects at
``$CALALARM_WORKSPACE/ms_token.json`` (default ``/data/ms_token.json``), and it
pairs with ``ms_app_config.json`` (same directory) containing at least
``{"client_id": "<your-app-id>"}`` — no ``client_secret`` required for a
public client.

Prerequisites (see docs/BACKEND.md):
  * An Entra "Mobile and desktop applications" app registration with
    "Allow public client flows" = **Yes**.
  * Delegated permissions ``Calendars.Read`` and ``offline_access``.

Usage:
    python3 ms_device_login.py \
        --client-id 00000000-0000-0000-0000-000000000000 \
        --tenant common \
        --out /path/to/ms_token.json

Exit codes: 0 = token written, 1 = error / timeout / user declined.
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

SCOPES = "offline_access Calendars.Read"
DEFAULT_TENANT = "common"
POLL_INTERVAL = 5          # seconds between token polls (per device-code spec)
DEFAULT_TIMEOUT = 900      # 15 minutes, the usual device-code lifetime


def _post(url: str, form: dict, timeout: int = 30) -> dict:
    """POST a form-encoded body and return the parsed JSON response."""
    data = urllib.parse.urlencode(form).encode()
    req = urllib.request.Request(
        url,
        data=data,
        headers={"Content-Type": "application/x-www-form-urlencoded"},
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode())


def request_device_code(client_id: str, tenant: str) -> dict:
    url = f"https://login.microsoftonline.com/{tenant}/oauth2/v2.0/devicecode"
    return _post(url, {"client_id": client_id, "scope": SCOPES})


def poll_for_token(client_id: str, tenant: str, device_code: str,
                   interval: int, expires_in: int) -> dict:
    url = f"https://login.microsoftonline.com/{tenant}/oauth2/v2.0/token"
    deadline = time.time() + min(expires_in, DEFAULT_TIMEOUT)
    while time.time() < deadline:
        try:
            return _post(url, {
                "client_id": client_id,
                "grant_type": "urn:ietf:params:oauth:grant-type:device_code",
                "device_code": device_code,
            })
        except urllib.error.HTTPError as e:
            body = e.read().decode(errors="replace")
            try:
                err = json.loads(body).get("error", "")
            except Exception:
                err = ""
            if err == "authorization_pending":
                time.sleep(interval)
                continue
            if err == "slow_down":
                interval += 5
                time.sleep(interval)
                continue
            if err in ("expired_token", "authorization_declined"):
                raise SystemExit(f"Device code flow failed: {err} — run again and sign in in time.")
            raise SystemExit(f"Token request failed (HTTP {e.code}): {body[:300]}")
        except urllib.error.URLError as e:
            print(f"  network error while polling ({e}); retrying...", file=sys.stderr)
            time.sleep(interval)
    raise SystemExit("Timed out waiting for sign-in. Run the script again.")


def write_token_secure(path: str, token: dict) -> None:
    """Write the token JSON atomically with owner-only permissions (600)."""
    directory = os.path.dirname(os.path.abspath(path))
    if directory:
        os.makedirs(directory, exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w") as f:
        json.dump(token, f, indent=2)
    os.chmod(tmp, 0o600)
    os.replace(tmp, path)


def main() -> int:
    ap = argparse.ArgumentParser(
        description="OAuth 2.0 device-code bootstrap for the Calendar Alarm Microsoft Graph token."
    )
    ap.add_argument("--client-id", required=True,
                    help="Application (client) ID of the Entra app registration")
    ap.add_argument("--tenant", default=DEFAULT_TENANT,
                    help="Entra tenant: 'common', 'organizations', 'consumers', or a tenant GUID (default: common)")
    ap.add_argument("--out", required=True,
                    help="Output path for the token JSON (e.g. /data/ms_token.json)")
    ap.add_argument("--interval", type=int, default=POLL_INTERVAL,
                    help="Poll interval in seconds (default: 5)")
    args = ap.parse_args()

    print("Requesting device code from Microsoft Entra ID ...")
    dc = request_device_code(args.client_id, args.tenant)
    print()
    print("=" * 70)
    print(f"  Open:  {dc['verification_uri']}")
    print(f"  Code:  {dc['user_code']}")
    print("=" * 70)
    print("Sign in with the account whose calendar should drive the alarm,")
    print("approve the code, then wait for this script to finish.")
    print()

    token = poll_for_token(
        args.client_id, args.tenant,
        dc["device_code"], args.interval, int(dc.get("expires_in", DEFAULT_TIMEOUT)),
    )

    if "refresh_token" not in token:
        raise SystemExit("No refresh_token in response — 'offline_access' scope missing?")
    if "access_token" not in token:
        raise SystemExit("No access_token in response — unexpected token payload.")

    write_token_secure(args.out, token)
    print(f"Token written to {args.out} (mode 600).")
    print("Keep this file private — it grants read access to the calendar.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
