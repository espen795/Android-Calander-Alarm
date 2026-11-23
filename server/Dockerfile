FROM python:3.11-slim

# #32/#33: curl en jq zijn NIET meer nodig — de MS token-refresh gebruikt
# Python (urllib) en de healthcheck gebruikt een Python one-liner.
# Kleinere image = kleiner aanvalsoppervlak.

# H4 hardening: run as non-root user 10001 (appuser).
# -M = no home dir creation (read_only rootfs; home not needed)
RUN useradd -u 10001 -M -s /usr/sbin/nologin appuser

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY main.py .

# Writable locations live on mounts only: /data (bind, rw) and /tmp (tmpfs).
# Root filesystem is read_only in docker-compose.yml.
EXPOSE 8100

USER appuser

# Bind to 0.0.0.0 so Traefik can reach via Docker network.
# SAFE: container is on internal 'proxy' network only, not exposed to host.
# External access is protected by Cloudflare Access + Traefik routing.
#
# --proxy-headers: laat uvicorn X-Forwarded-* headers honoreren.
# LET OP: Traefik is hier NIET geconfigureerd met forwardedHeaders.trustedIPs,
# dus de headers worden aan de app doorgegeven maar de client-IP in
# request.client.host blijft het proxy-IP — de per-IP rate-limit in de app
# werkt daarmee effectief per proxy, NIET per echte client-IP.
# De per-TOKEN rate-limit (na auth) is wel per device en/onafhankelijk hiervan.
# --forwarded-allow-ips laten we weg uit CMD: uvicorn leest zelf de env var
# FORWARDED_ALLOW_IPS (ingesteld in docker-compose.yml op het Traefik-IP).
# '*' wordt expres NIET gebruikt: daarmee vertrouwt uvicorn álle
# X-Forwarded-For headers, ook van clients die de container rechtstreeks raken.
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8100", "--log-level", "info", "--proxy-headers"]
