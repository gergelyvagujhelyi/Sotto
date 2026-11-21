#!/usr/bin/env bash
# Tells the relay that the app of version $1 is published, so it tells the phones on older
# apps right away (server/app/services/app_release.py). The call is signed with the release
# workflow's Ed25519 key, CI_RELEASE_KEY (PEM); the relay holds the public half.
# Env: RELAY_URL, CI_RELEASE_KEY; OPENSSL overrides the openssl binary (it needs 3.x).
set -euo pipefail
version="$1"
if [ -z "${RELAY_URL:-}" ] || [ -z "${CI_RELEASE_KEY:-}" ]; then
  echo "RELAY_URL or CI_RELEASE_KEY not set: the relay will notice the release by itself."
  exit 0
fi
ts=$(python3 -c 'import time; print(int(time.time() * 1000))')
dir=$(mktemp -d)
trap 'rm -rf "$dir"' EXIT
printf '%s\n' "$CI_RELEASE_KEY" > "$dir/key.pem"
printf 'sotto-app-release:%s:%s' "$version" "$ts" > "$dir/payload"
sig=$("${OPENSSL:-openssl}" pkeyutl -sign -inkey "$dir/key.pem" -rawin -in "$dir/payload" | base64 | tr -d '\n')
curl -fsS -m 20 -X POST -H 'Content-Type: application/json' \
  -d "{\"version\":\"$version\",\"ts\":$ts,\"signature\":\"$sig\"}" \
  "${RELAY_URL%/}/v1/app-release"
echo
