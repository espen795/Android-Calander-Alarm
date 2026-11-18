#!/usr/bin/env python3
"""Emit the self-hosted update feed (version.json) for an APK.

Usage: make_version_json.py <apk-file> <versionName> [versionCode]
The app's UpdateChecker fetches <UPDATE_SERVER_URL>/version.json and only accepts
relative download paths under releases/ plus a matching SHA-256.
"""
import hashlib, json, re, sys
from pathlib import Path

apk, version = Path(sys.argv[1]), sys.argv[2]
if len(sys.argv) > 3:
    code = int(sys.argv[3])
else:
    gradle = Path(__file__).resolve().parent.parent / "android/app/build.gradle.kts"
    code = int(re.search(r"versionCode = (\d+)", gradle.read_text()).group(1))
print(json.dumps({
    "version": version,
    "versionCode": code,
    "url": f"releases/{apk.name}",
    "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
}, indent=2))
