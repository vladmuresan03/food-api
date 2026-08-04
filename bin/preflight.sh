#!/usr/bin/env bash
# Pre-flight check for the foodfinder-api stack.
# Run this on the Portainer host before Deploy to make sure the chosen
# host port is free and the external networks are reachable.
set -euo pipefail

HOST_PORT="${HOST_PORT:-9150}"

echo "== port $HOST_PORT (TCP, host) =="
if command -v ss >/dev/null 2>&1; then
    if ss -ltn "sport = :$HOST_PORT" | tail -n +2 | grep -q .; then
        echo "  OCCUPIED — pick a different HOST_PORT"
        ss -ltnp "sport = :$HOST_PORT" || true
        exit 1
    else
        echo "  free"
    fi
elif command -v lsof >/dev/null 2>&1; then
    if lsof -iTCP:"$HOST_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
        echo "  OCCUPIED — pick a different HOST_PORT"
        lsof -iTCP:"$HOST_PORT" -sTCP:LISTEN || true
        exit 1
    else
        echo "  free"
    fi
else
    echo "  (no ss/lsof; skipped)"
fi

echo
echo "== docker external networks =="
for net in postgresql_foodfinder_net nginx-proxy-manager_default; do
    if docker network inspect "$net" >/dev/null 2>&1; then
        echo "  $net: present"
    else
        echo "  $net: MISSING — Portainer cannot attach the stack to it"
        exit 1
    fi
done

echo
echo "== postgresql container on the foodfinder network =="
POSTGRES_HOST=$(docker network inspect postgresql_foodfinder_net \
    --format '{{range .Containers}}{{.Name}} {{.IPv4Address}}{{"\n"}}{{end}}' \
    | awk '{print $1}' | grep -E '(postgres|postgresql)' | head -1 || true)
if [ -n "$POSTGRES_HOST" ]; then
    echo "  found: $POSTGRES_HOST"
else
    echo "  no postgresql-* container on the network — DATABASE_URL will fail"
    exit 1
fi

echo
echo "== all good — proceed with Portainer Deploy =="
