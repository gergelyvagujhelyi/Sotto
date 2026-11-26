#!/bin/bash
set -eo pipefail
export PATH="/usr/local/bin:/usr/bin:/bin:$PATH"
# Pull-based deploy: checks for new commits on develop and redeploys if changed.
# Install in cron: */5 * * * * /path/to/ChatControll/server/deploy.sh >> /var/log/chatcontroll-deploy.log 2>&1

LOCK="/tmp/chatcontroll-deploy.lock"
exec 200>"$LOCK"
flock -n 200 || { echo "$(date): Deploy already running, skipping"; exit 0; }

REPO_ROOT="$(dirname "$0")/.."
cd "$REPO_ROOT"

COMPOSE="docker compose -f server/docker-compose.yml"

# nginx.conf and nginx-relay-hostname.sh are bind-mounted as single files: `git reset --hard`
# replaces the files, but the running nginx keeps reading the old ones, and `up -d` doesn't
# recreate nginx for them. So compare what nginx is running with the repo's files, and when
# they differ, check the new ones with `nginx -t` and recreate nginx. Skipped while nginx is
# down (a certificate renewal stops it and starts it again by itself).
apply_nginx_conf() {
    local running wanted
    # The script is missing in an nginx started before it existed: compare without it then.
    running=$($COMPOSE exec -T nginx sh -c 'cat /etc/nginx/conf.d/default.conf; cat /docker-entrypoint.d/40-relay-hostname.sh 2>/dev/null || true' 2>/dev/null | sha256sum | cut -d' ' -f1) || return 0
    [ -n "$running" ] || return 0
    wanted=$(cat server/nginx.conf server/nginx-relay-hostname.sh | sha256sum | cut -d' ' -f1)
    # Empty output from a failed exec hashes to this; treat it as "nginx not running".
    [ "$running" != "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" ] || return 0
    [ "$running" = "$wanted" ] && return 0
    echo "$(date): nginx.conf changed, checking it"
    if $COMPOSE run --rm --no-deps -T nginx nginx -t >/dev/null 2>&1; then
        $COMPOSE up -d --force-recreate nginx
        echo "$(date): nginx recreated with the new nginx.conf"
    else
        echo "$(date): the new nginx.conf fails nginx -t; keeping the running nginx"
    fi
}

# Phones behind some home lines (PPPoE, VPNs) lose full-size packets on the way to the relay
# without being told (a path MTU "black hole"): small requests work, but a message send stalls
# for seconds or hangs (seen 2026-11-26 from a home Wi-Fi in Serbia; the same phones were fine
# on mobile data). The relay tells phones to send at most MSS_CLAMP bytes per packet on its
# HTTPS port, which fits under those lines. Every run, so it's back after a reboot.
# MSS_CLAMP=0 in the environment leaves the firewall alone.
apply_mss_clamp() {
    local mss="${MSS_CLAMP:-1360}"
    [ "$mss" != "0" ] || return 0
    command -v iptables >/dev/null || return 0
    local rule=(-p tcp --sport 443 --tcp-flags SYN,ACK SYN,ACK -j TCPMSS --set-mss "$mss")
    iptables -t mangle -C POSTROUTING "${rule[@]}" 2>/dev/null && return 0
    iptables -t mangle -A POSTROUTING "${rule[@]}" && echo "$(date): MSS clamp $mss set on port 443"
}

LAST_DEPLOY_FILE="/tmp/chatcontroll-last-deploy"
git fetch origin develop --quiet
LOCAL=$(cat "$LAST_DEPLOY_FILE" 2>/dev/null || echo "none")
REMOTE=$(git rev-parse origin/develop)

# Always ensure working tree matches origin/develop
git reset --hard origin/develop --quiet

# Every run, also when nothing new arrived: the first run after this script changed, a
# deploy that failed halfway, or a hand-made change all get the repo's nginx.conf applied.
apply_nginx_conf
apply_mss_clamp || echo "$(date): could not set the MSS clamp"

if [ "$LOCAL" = "$REMOTE" ]; then
    exit 0
fi

# Validate LOCAL commit still exists (could be GC'd after force-push)
if [ "$LOCAL" != "none" ] && ! git rev-parse --verify "$LOCAL^{commit}" >/dev/null 2>&1; then
    echo "$(date): Previous commit $LOCAL no longer exists, forcing deploy"
    LOCAL="none"
fi

SERVER_CHANGES=$(git diff --name-only "$LOCAL" "$REMOTE" -- server/ 2>/dev/null || echo "unknown")
if [ "$LOCAL" = "none" ] || [ -n "$SERVER_CHANGES" ]; then
    echo "$(date): Server changed ($LOCAL -> $REMOTE), deploying"
    # Rebuild the app image and apply every change in the compose file. Services
    # whose image and settings didn't change keep running.
    $COMPOSE up -d --build
    docker image prune -f
    echo "$(date): Deploy complete"
else
    echo "$(date): New commits ($LOCAL -> $REMOTE) but server/ unchanged, skipping deploy"
fi

echo "$REMOTE" > "$LAST_DEPLOY_FILE"
