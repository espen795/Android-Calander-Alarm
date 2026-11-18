FROM python:3.11-slim

# No extra packages needed: main.py uses only the Python standard library
# (urllib) for the OAuth token refresh. Keeps the image small and reduces
# the attack surface.

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

# Bind to 0.0.0.0 inside the container so the reverse proxy can reach the API
# over the internal Docker network. The container is NOT published to the host
# (docker-compose.yml has no ports: section) and sits on an internal network
# only; TLS + access policy are terminated at the edge proxy.
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8100", "--log-level", "info"]
