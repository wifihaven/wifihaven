# Smoke-test scripts/bootstrap-host.sh in a clean Ubuntu container.
#
# We seed the container with a non-root login user and the local checkout, then
# run bootstrap-host.sh against a *file://* clone of that checkout. That way
# the test exercises the real script — including the apt installs, useradd,
# /etc/systemd writes, and the service-user clone — without contacting GitHub
# and without touching the host.
#
# Usage:
#   docker build -f docker/bootstrap-test.Dockerfile -t wifihaven-bootstrap-test .
#   docker run --rm wifihaven-bootstrap-test
FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update -qq \
 && apt-get install -y -qq sudo curl ca-certificates git \
 && useradd -m -s /bin/bash deploy \
 && echo 'deploy ALL=(ALL) NOPASSWD: ALL' > /etc/sudoers.d/deploy \
 && chmod 0440 /etc/sudoers.d/deploy

# Stage the repo as if it were a remote git origin.
WORKDIR /src
COPY . /src
# COPY may have brought in a .git file/dir from the worktree — remove it and
# rebuild a clean local repo so `git clone file:///src --branch production` works.
RUN rm -rf /src/.git \
 && git -C /src -c init.defaultBranch=production init -q \
 && git -C /src -c user.email=t@t -c user.name=t add -A \
 && git -C /src -c user.email=t@t -c user.name=t commit -q -m seed --allow-empty \
 && chown -R deploy:deploy /src \
 && git config --system --add safe.directory /src \
 && git config --system --add safe.directory /src/.git

USER deploy
WORKDIR /home/deploy

ENV FAMILYDNS_REPO_URL=file:///src \
    FAMILYDNS_BRANCH=production

# We can't run systemctl inside a container, so we stub it. The bootstrap only
# uses `daemon-reload`, which is harmless to no-op.
USER root
RUN printf '#!/bin/sh\nexit 0\n' > /usr/local/bin/systemctl-stub \
 && chmod +x /usr/local/bin/systemctl-stub
USER deploy

# Verification script — keep it in the image so we can also run it ad-hoc.
USER root
COPY <<'CHECK' /usr/local/bin/check-bootstrap
#!/usr/bin/env bash
set -euo pipefail
# systemctl isn't available in a plain container — stub it for the run.
sudo ln -sf /usr/local/bin/systemctl-stub /usr/local/bin/systemctl
bash /src/scripts/bootstrap-host.sh
echo "--- post-bootstrap checks ---"
test -d /opt/wifihaven/repo/.git
test -L /etc/systemd/system/wifihaven-api.service
test -f /etc/systemd/system/wifihaven-deploy.service
test -f /etc/systemd/system/wifihaven-deploy.timer
sudo test -f /etc/wifihaven/application.conf
test -f /etc/sudoers.d/wifihaven-deploy
id wifihaven >/dev/null
command -v mill >/dev/null
command -v node >/dev/null
command -v java >/dev/null
echo "OK: bootstrap smoke test passed"
CHECK
RUN chmod +x /usr/local/bin/check-bootstrap
USER deploy

CMD ["/usr/local/bin/check-bootstrap"]
