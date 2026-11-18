# Server runtime data

This directory is the **only** host path mounted into the `calendar-alarm-api`
container (as `/data`, read-write). It contains just the files the API needs.

```
data/
├── ms_token.json         # Microsoft Graph OAuth tokens (600, owner 10001) — bootstrap with ../tools/ms_device_login.py
├── ms_app_config.json    # {"client_id": "...", "client_secret": "..."} (client_secret optional; 600, owner 10001)
├── alarm_config.json     # alarm rules; start from ../alarm_config.example.json (600)
└── logs/                 # device log uploads
```

File names can be overridden with `CALALARM_MS_TOKEN_FILE`, `CALALARM_MS_CONFIG_FILE`
and `CALALARM_CONFIG_FILE` (relative to `/data`).

## Host setup (one-time)

```bash
cd server
mkdir -p data/logs
cp alarm_config.example.json data/alarm_config.json
# data/ must be owned by the container user (10001:10001)
docker run --rm -v "$PWD/data:/data" alpine:3 chown -R 10001:10001 /data
chmod 600 data/*.json
# Bootstrap the Microsoft Graph token (device-code flow, no client_secret needed):
# python3 tools/ms_device_login.py --client-id <your-entra-app-id> --out data/ms_token.json
```

`data/` is git-ignored — tokens, logs and runtime config are never committed.
