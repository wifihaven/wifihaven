# #1208: Grafana Alloy collector for the cloud app-metrics path.
#
# A runtime:image service can't mount a repo-local file into a prebuilt
# image, and Alloy needs our config.alloy baked in — so we build a thin
# image FROM the official Alloy release and COPY the config. Built by Render
# from this repo (render.yaml -> wifihaven-alloy, dockerfilePath here).
#
# Pin the base tag explicitly for reproducibility; bump deliberately.
FROM grafana/alloy:v1.5.1

# Default Alloy config path is /etc/alloy/config.alloy.
COPY config.alloy /etc/alloy/config.alloy

# Run in agent mode. The HTTP server is bound to loopback only — Alloy has no
# inbound role here (it scrapes + remote_writes), so it is not exposed.
CMD ["run", \
     "/etc/alloy/config.alloy", \
     "--storage.path=/var/lib/alloy/data", \
     "--server.http.listen-addr=127.0.0.1:12345"]
