# Troubleshooting

## The alarm doesn't fire

Work through this list in order — on Samsung One UI, items 1–3 are the usual culprits:

1. **Exact alarm permission** — Settings → Apps → Calendar Alarm → Special app access →
   *Alarms & reminders* → **Allow**. Without it, `AlarmManager.setAlarmClock()` throws
   `SecurityException` on Android 12+ and the sync logs "exact alarm permission not
   granted".
2. **Battery optimization** — Settings → Apps → Calendar Alarm → Battery → **Unrestricted**
   (not "Optimized"). Aggressive OEM battery savers kill the app and its WorkManager
   jobs; the app also runs a foreground service while ringing to resist the OOM killer.
3. **Full-screen intent / display over other apps** — Android 14+ requires
   `USE_FULL_SCREEN_INTENT` for the ringing activity to pop over the lock screen; on
   Samsung One UI the overlay additionally needs *Display over other apps* → Allow.
   Check: Settings → Apps → Calendar Alarm → Special app access.
4. **Notifications permission** (Android 13+) — needed for the pre-alarm and status
   notifications.
5. **Is the alarm in the list?** Open the app → Alarms tab. After a successful sync you
   should see tomorrow's smart alarm. Use **Sync now** to force a fetch.
6. **Screen off / locked?** The ringing activity is declared with `showWhenLocked`,
   `turnScreenOn` and a `setFullScreenIntent()` on its notification — the screen should
   wake. On some devices the very first alarm after boot can be delayed; reboot and retry.

## Sync fails — "server unreachable" or an HTTP error

Check in this order:

1. Internet works at all? (open a browser)
2. **Server URL** correct in Settings (e.g. `https://alarm-api.example.com`, no trailing
   path)? A wrong URL gives `404`.
3. **Device token** matches `CALALARM_DEVICE_TOKEN` on the server exactly → mismatch
   gives `401`.
4. **Cloudflare Access**: if the API is behind Access, the service token **Client ID +
   Secret** in Settings must be valid → wrong/missing credentials give `403` (or a
   redirect). Make sure the Access policy for the hostname has a service-token rule.
5. `429` → rate limited (max 10 req/min per IP and per token; the app should never hit
   this — hourly sync). Wait a minute.
6. `503` → the server can't reach Microsoft Graph: the Microsoft app registration or
   token files in `data/` are missing/expired, or the refresh token was revoked (then the
   owner must re-consent the app in Azure and replace `ms_token.json`).
7. SSL/certificate errors → if you built with `CALALARM_CERT_PINS`, the pins must match the
   chain actually served (see *certificate pinning* below). Without pins, only system CA +
   hostname validation apply.
8. DNS / connection refused → server down or hostname doesn't resolve.

The in-app **Logs** screen shows the exact HTTP status and error per sync attempt.

## Certificate pinning failures

If you set `CALALARM_CERT_PINS` at build time and sync fails with an SSL/certificate error:

- The pins are **base64 SPKI SHA-256** hashes of certificates in the served chain
  (typically your CA's intermediate + root). Compute with:
  `openssl x509 -in cert.pem -pubkey -noout | openssl pkey -pubin -outform DER | openssl dgst -sha256 -binary | base64 -w0`
- Include a **backup pin** (e.g. the root) so a CA intermediate rotation doesn't lock
  you out.
- Pinning is enforced in `CertificatePinner` (Ktor's CIO engine ignores Android's
  Network Security Config, so `<pin-set>` is intentionally absent from
  `network_security_config.xml`).
- To debug, temporarily rebuild **without** `CALALARM_CERT_PINS` — if sync then works, your
  pins are wrong.

## Cloudflare Access (403 / redirects)

- The app sends `Cf-Access-Client-Id` / `Cf-Access-Client-Secret` headers **only** when
  both are set in Settings.
- In the Cloudflare dashboard: Zero Trust → Access → Applications → the application
  protecting your API hostname must have a policy rule allowing your **service token**,
  and it should be evaluated before any deny/redirect rules.
- If the API hostname is behind Access but you open it in a browser, you'll get the
  Access login page — that's expected; the app authenticates with the service token
  headers instead.
- The update feed (if `CALALARM_UPDATE_SERVER_URL` is set) is a **different hostname** and may
  have its own Access policy — `403` on update checks usually means the service token
  isn't valid for *that* host.

## Token refresh (server side, 503 "Calendar service unavailable")

The server refreshes the Microsoft token on every `/api/alarm/next` call using
`refresh_token` from `CALALARM_MS_TOKEN_FILE`. If that fails:

- check the files exist in the mounted `data/` dir and have mode `600` (a startup
  warning is printed if they're group/other-readable),
- check `CALALARM_MS_CONFIG_FILE` contains `client_id` / `client_secret` matching the Azure
  app registration,
- if the refresh token itself is expired/revoked, re-consent: run the OAuth flow again,
  replace `ms_token.json`, restart the container.

## Log upload not appearing server-side

- Log upload runs every 6 h via WorkManager and only sends **new** entries — it is not
  instant. Use the in-app Logs screen for immediate debugging.
- Same connectivity as sync (token, CF headers, pinning), so if sync works, upload
  usually works too. Server-side the file is `<CALALARM_WORKSPACE>/logs/calendar-alarm-device.log`
  (rotated to `.log.old` past 5 MB).
- Entries are sanitized server-side: the device token, 64-char hex strings and
  Cloudflare `cfast_*` secrets are redacted. Calendar subjects never leave the server.

## Widget shows stale data or won't add

- The widget refreshes on every alarm change and uses a native `TextClock` for the time;
  if the clock is frozen, the launcher hasn't refreshed the widget — remove and re-add it.
- On Samsung One UI the widget needs a plain drawable `previewImage` (not an adaptive
  launcher icon) and no `configure` activity — both are already set correctly; if placement
  fails with "can't add widget", update the launcher or try a different widget size.
- There is a hidden **Widget Debug** activity (`io.github.calendaralarm.WIDGET_DEBUG`) that
  forces a refresh and shows cache contents.

## Updates: "update check failed" or install blocked

- `version.json` must be at `<CALALARM_UPDATE_SERVER_URL>/version.json` and contain
  `version`, `versionCode`, relative `url` under `releases/`, 64-hex `sha256` and a
  `size` ≤ 50 MB. Generate it with `tools/make_version_json.py`.
- Downloads never follow redirects; a `302` usually means Cloudflare Access in front of
  the update host without valid service-token headers.
- The APK is only offered for install if its SHA-256 matches `version.json` **and** its
  signing certificate matches the pinned release signer — if you re-sign with a new
  keystore, update the pinned fingerprint in `UpdateChecker`.
- Android blocks installs from unknown sources: allow your file manager/browser, and
  on Android 8+ the install intent comes from the app itself (`REQUEST_INSTALL_PACKAGES`).
