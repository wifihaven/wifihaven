# Staging / e2e image for WifiHaven.
#
# Multi-stage:
#   1. scala-build: compile + assemble the api jar with mill
#   2. web-build:   build the React/Vite bundle
#   3. runtime:     small JRE image that runs the api + serves the bundle
#
# The same image is used for local browser-driven testing, the e2e GitHub
# Actions workflow, and the staging stack. Production still deploys via
# scripts/deploy.sh on the host (no docker on the prod box).

# ── 1. Scala build ────────────────────────────────────────────────────────
# Pin to BUILDPLATFORM (always the runner's native arch). Without this,
# buildx runs this stage once per target arch — and the arm64 leg compiles
# Scala under QEMU emulation, which is glacial (~30 min on its own). The
# .jar is platform-neutral, so cross-arch builds gain nothing from running
# scalac twice.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS scala-build
ARG MILL_VERSION=1.1.5
RUN apt-get update -qq && apt-get install -y -qq curl git ca-certificates \
 && curl -fsSL "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/${MILL_VERSION}/mill-dist-${MILL_VERSION}-mill.sh" \
      -o /usr/local/bin/mill \
 && chmod +x /usr/local/bin/mill
WORKDIR /src
COPY build.mill .scalafmt.conf .scalafix.conf ./
COPY shared shared
COPY api api
RUN mill api.assembly \
 && cp out/api/assembly.dest/out.jar /tmp/api.jar

# ── 2. Web build ──────────────────────────────────────────────────────────
# Same rationale: Vite output is static JS/CSS, no native code.
FROM --platform=$BUILDPLATFORM node:22-bookworm-slim AS web-build
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci --silent
COPY web/ ./
RUN npm run build

# ── 3. Runtime ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre
RUN apt-get update -qq && apt-get install -y -qq --no-install-recommends \
      curl ca-certificates dnsutils \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=scala-build /tmp/api.jar /app/api.jar
# TODO(#601): once the CDN-served Static Sites (#587) are confirmed
# working in prod, remove the web-build stage and this COPY — the JVM no
# longer needs to bundle or serve the SPA. Track cleanup in the linked issue.
COPY --from=web-build /web/dist /app/web
COPY config/application.conf.example /app/application.conf.example
COPY docker/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

EXPOSE 8080

ENV WIFIHAVEN_HTTP_PORT=8080 \
    WIFIHAVEN_STATIC_DIR=/app/web

ENTRYPOINT ["/app/entrypoint.sh"]
