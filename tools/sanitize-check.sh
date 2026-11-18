#!/usr/bin/env bash
# Repo hygiene gate — run locally before every push and in CI.
# 1. Generic checks (forbidden file types, private IPs, key material).
# 2. Optional maintainer denylist kept OUTSIDE the repo (personal names, domains,
#    hostnames) so the list itself never gets published:
#    ${CALALARM_DENYLIST:-$HOME/.config/calendar-alarm/denylist.txt}, one regex per line.
set -uo pipefail
cd "$(dirname "$0")/.." && cd "$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
fail=0
files() { git ls-files 2>/dev/null || find . -type f -not -path './.git/*'; }

bad_files=$(files | grep -E '\.(jks|keystore|p12|pem|key|apk|aab)$|(^|/)\.env$|(^|/)local\.properties$|(^|/)gradle\.properties$|token.*\.json$' || true)
[ -n "$bad_files" ] && { echo "FORBIDDEN FILES:"; echo "$bad_files"; fail=1; }

pat='(192\.168\.[0-9]+\.[0-9]+|10\.[0-9]+\.[0-9]+\.[0-9]+|100\.(6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\.[0-9]+\.[0-9]+|BEGIN (RSA|EC|OPENSSH) PRIVATE KEY|cfast_[A-Za-z0-9_-]{24,}|client_secret"?\s*[:=]\s*"[^"<]{8,}|/home/[a-z]+/)'
hits=$(files | grep -v '^tools/sanitize-check.sh$' | xargs -d '\n' grep -nIE "$pat" 2>/dev/null || true)
[ -n "$hits" ] && { echo "SUSPICIOUS CONTENT:"; echo "$hits"; fail=1; }

deny="${CALALARM_DENYLIST:-$HOME/.config/calendar-alarm/denylist.txt}"
if [ -f "$deny" ]; then
  hits=$(files | xargs -d '\n' grep -nIiE -f <(grep -v '^\s*#' "$deny" | grep -v '^\s*$') 2>/dev/null || true)
  if [ "${1:-}" = "--history" ]; then
    hits+=$(git log -p --all | grep -iE -f <(grep -v '^\s*#' "$deny" | grep -v '^\s*$') | head -20 || true)
  fi
  [ -n "$hits" ] && { echo "DENYLIST HITS:"; echo "$hits"; fail=1; }
else
  echo "(no private denylist at $deny — generic checks only)"
fi
[ $fail -eq 0 ] && echo "sanitize-check: CLEAN"
exit $fail
