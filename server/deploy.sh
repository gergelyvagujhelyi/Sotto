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

# nginx.conf is bind-mounted as a single file: `git reset --hard` replaces the file, but the
# running nginx keeps reading the old one, and `up -d` doesn't recreate nginx for it. So
# compare what nginx is running with the repo's file, and when they differ, check the new
# one with `nginx -t` and recreate nginx. Skipped while nginx is down (a certificate
# renewal stops it and starts it again by itself).
apply_nginx_conf() {
    local running wanted
    running=$($COMPOSE exec -T nginx cat /etc/nginx/conf.d/default.conf 2>/dev/null | sha256sum | cut -d' ' -f1) || return 0
    [ -n "$running" ] || return 0
    wanted=$(sha256sum < server/nginx.conf | cut -d' ' -f1)
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

LAST_DEPLOY_FILE="/tmp/chatcontroll-last-deploy"
git fetch origin develop --quiet
LOCAL=$(cat "$LAST_DEPLOY_FILE" 2>/dev/null || echo "none")
REMOTE=$(git rev-parse origin/develop)

# Always ensure working tree matches origin/develop
git reset --hard origin/develop --quiet

# Every run, also when nothing new arrived: the first run after this script changed, a
# deploy that failed halfway, or a hand-made change all get the repo's nginx.conf applied.
apply_nginx_conf

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
