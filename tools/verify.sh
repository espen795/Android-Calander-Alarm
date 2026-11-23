#!/usr/bin/env bash
# Calendar Alarm — hard gate (hardening plan 2026-11-22, principle 2).
# Red = no merge, no APK. Runs: app unit tests + assembleDebug (+ assembleRelease
# only when gradle.properties with signing-config is present) + server pytest (when available).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$SCRIPT_DIR/../app"
SERVER_DIR="$SCRIPT_DIR/../server"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"

cd "$APP_DIR"

TOTAL_TESTS=0
TOTAL_FAILURES=0
SUMMARY=()

echo "== [1/4] app :app:testDebugUnitTest =="
if ./gradlew --no-daemon --console=plain :app:testDebugUnitTest; then
    # Parse the JUnit XML results for the summary
    RESULTS_DIR="$APP_DIR/app/build/test-results/testDebugUnitTest"
    if [ -d "$RESULTS_DIR" ]; then
        read -r T F S < <(python3 - "$RESULTS_DIR" <<'PY'
import sys, glob, xml.etree.ElementTree as ET
t = f = s = 0
for p in glob.glob(sys.argv[1] + "/TEST-*.xml"):
    r = ET.parse(p).getroot()
    t += int(r.get("tests", 0))
    f += int(r.get("failures", 0)) + int(r.get("errors", 0))
    s += int(r.get("skipped", 0))
print(t, f, s)
PY
)
        TOTAL_TESTS=$((TOTAL_TESTS + T))
        TOTAL_FAILURES=$((TOTAL_FAILURES + F))
        SUMMARY+=("app unit tests: $T tests, $F failures, $S skipped (@Ignore)")
    else
        SUMMARY+=("app unit tests: OK (no XML results found for counting)")
    fi
    echo "== [1/4] OK =="
else
    echo "== [1/4] FAILED: app unit tests ==" >&2
    exit 1
fi

echo "== [2/4] app :app:assembleDebug =="
./gradlew --no-daemon --console=plain :app:assembleDebug
SUMMARY+=("assembleDebug: OK")
echo "== [2/4] OK =="

echo "== [3/4] app :app:assembleRelease (only when gradle.properties AND keystore exist) =="
# gradle.properties contains KEYSTORE_PATH (secrets — contents are NEVER read/printed).
# The keystore itself is git-ignored and only lives in the main workspace; in a worktree
# we do NOT copy it (secret material stays out of the worktree).
KEYSTORE_PATH=""
if [ -f "$APP_DIR/gradle.properties" ]; then
    # NB: grep exit 1 on no match is OK under pipefail — catch with || true
    KEYSTORE_PATH="$(grep -E '^KEYSTORE_PATH=' "$APP_DIR/gradle.properties" | cut -d= -f2- | tr -d '[:space:]' || true)"
fi
if [ -f "$APP_DIR/gradle.properties" ] && [ -n "$KEYSTORE_PATH" ] && [ -f "$KEYSTORE_PATH" ]; then
    ./gradlew --no-daemon --console=plain :app:assembleRelease
    SUMMARY+=("assembleRelease: OK")
    echo "== [3/4] OK =="
else
    SUMMARY+=("assembleRelease: SKIPPED (no gradle.properties or keystore not at KEYSTORE_PATH — e.g. in a git worktree)")
    echo "== [3/4] skipped (no signing-config/keystore available) =="
fi

echo "== [4/4] server pytest (only when pytest available) =="
# Server venv (outside the repo) — created when it is missing
SRV_VENV="${CALALARM_SRV_VENV:-$HOME/.cache/server-srv-venv}"
if [ -d "$SERVER_DIR" ] && [ ! -x "$SRV_VENV/bin/python" ]; then
    python3 -m venv "$SRV_VENV" && "$SRV_VENV/bin/pip" install -q -r "$SERVER_DIR/requirements.txt" -r "$SERVER_DIR/requirements-dev.txt"
fi
if [ -x "$SRV_VENV/bin/python" ] && "$SRV_VENV/bin/python" -c "import pytest" >/dev/null 2>&1; then
    if [ -d "$SERVER_DIR" ]; then
        (cd "$SERVER_DIR" && "$SRV_VENV/bin/python" -m pytest -q)
        SUMMARY+=("server pytest: OK")
        echo "== [4/4] OK =="
    else
        SUMMARY+=("server pytest: SKIPPED (no server/ dir)")
        echo "== [4/4] skipped (no server/) =="
    fi
else
    SUMMARY+=("server pytest: SKIPPED (pytest not installed)")
    echo "== [4/4] skipped (pytest not available) =="
fi

echo
echo "================ SUMMARY ================"
for line in "${SUMMARY[@]}"; do echo " - $line"; done
echo "---------------------------------------------"
echo "Total app tests: $TOTAL_TESTS, failures: $TOTAL_FAILURES"
if [ "$TOTAL_FAILURES" -ne 0 ]; then
    echo "RESULT: RED ($TOTAL_FAILURES failures)"
    exit 1
fi
# A skipped step is NOT green — only allowed with ALLOW_SKIP=1 (e.g. in a worktree without keystore).
if printf '%s\n' "${SUMMARY[@]}" | grep -q "SKIPPED"; then
    if [ "${ALLOW_SKIP:-0}" = "1" ]; then
        echo "RESULT: GREEN (with skipped steps — ALLOW_SKIP=1, NOT release-worthy)"
        exit 0
    fi
    echo "RESULT: INCOMPLETE — step(s) skipped; set ALLOW_SKIP=1 for a non-release check"
    exit 2
fi
echo "RESULT: GREEN"
