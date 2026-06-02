# #1245: standalone Render → Grafana Cloud deploy-annotation receiver.
#
# stdlib-only Python — no requirements.txt, no pip. Built by Render from this
# repo (render.yaml -> wifihaven-deploy-webhook, dockerfilePath here), mirroring
# the Alloy thin-image pattern (deploy/alloy/Dockerfile).
#
# Pin the base tag for reproducibility; bump deliberately.
FROM python:3.13-slim

WORKDIR /app
COPY app.py /app/app.py

# Render injects PORT; app.py honours it (default 10000). No build step.
CMD ["python", "app.py"]
