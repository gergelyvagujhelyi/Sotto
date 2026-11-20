#!/bin/sh
# Run by the nginx image's entrypoint before nginx starts (mounted into /docker-entrypoint.d/).
#
# With RELAY_HOSTNAME set to the relay's name (as in its certificate; several comma-separated),
# nginx answers only that name. A TLS connection that asks for no name or another one is
# refused before the certificate is sent, and plain HTTP for another name is closed. Most
# scanners sweep IP addresses without a name, so they get nothing back, not even the name in
# the certificate. Without RELAY_HOSTNAME, nginx answers any name, as before.
set -eu

name_file=/etc/nginx/relay/server-name.conf
default_servers=/etc/nginx/conf.d/00-relay-hostname-only.conf
mkdir -p /etc/nginx/relay
rm -f "$name_file" "$default_servers"

names=$(printf '%s' "${RELAY_HOSTNAME:-}" | tr ',' ' ' | xargs)
if [ -z "$names" ]; then
    echo "$0: RELAY_HOSTNAME is not set, answering any name"
    exit 0
fi
for name in $names; do
    case "$name" in
        *[!A-Za-z0-9.-]* | .* | *. | *..*)
            echo "$0: RELAY_HOSTNAME has an invalid name '$name', answering any name" >&2
            exit 0
            ;;
    esac
done

printf 'server_name %s;\n' "$names" > "$name_file"
cat > "$default_servers" <<'EOF'
# Written by nginx-relay-hostname.sh: every connection that isn't for RELAY_HOSTNAME.
server {
    listen 443 ssl default_server;
    ssl_reject_handshake on;
    access_log off;
    # A request for another name over a connection made for the relay's.
    return 444;
}
server {
    listen 80 default_server;
    access_log off;
    return 444;
}
EOF
echo "$0: answering only $names"
