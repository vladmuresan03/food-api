#!/usr/bin/env bash
# Resolve deploy credentials from 1Password via the service-account token
# stored in the macOS Keychain (see bin/op-token-store.sh).
#
#   bin/op-secret.sh check            verify token + vault layout (prints NO secrets)
#   bin/op-secret.sh get ITEM FIELD   print one secret, e.g. get portainer password
#   bin/op-secret.sh env              emit `export ...` lines for deploy scripts
#
# Expected 1Password layout (vault overridable via OP_VAULT, default "treloc"):
#
#   treloc/portainer      -> url, username, password
#   treloc/npm            -> url, username, password        (Nginx Proxy Manager)
#   treloc/foodfinder-env -> SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME,
#                           SPRING_DATASOURCE_PASSWORD, FOODFINDER_ADMIN_USERNAME,
#                           FOODFINDER_ADMIN_PASSWORD, FOODFINDER_ALLOWED_ORIGINS
#
# Never prints more than one secret at a time; `check` prints none at all.
set -euo pipefail

VAULT="${OP_VAULT:-treloc}"
KEYCHAIN_SERVICE="foodfinder-op"
KEYCHAIN_ACCOUNT="service-token"

require_op() {
    command -v op >/dev/null 2>&1 || {
        echo "ERROR: 'op' CLI not found (brew install 1password-cli)." >&2
        exit 1
    }
    local t
    t="$(security find-generic-password -s "$KEYCHAIN_SERVICE" -a "$KEYCHAIN_ACCOUNT" -w 2>/dev/null || true)"
    [ -n "$t" ] || {
        echo "ERROR: no service token in Keychain. Run bin/op-token-store.sh first." >&2
        exit 1
    }
    export OP_SERVICE_ACCOUNT_TOKEN="$t"
}

read_ref() { # read_ref ITEM FIELD — value on stdout, empty if missing
    op read "op://${VAULT}/$1/$2" 2>/dev/null || true
}

case "${1:-help}" in
    check)
        require_op
        if op whoami >/dev/null 2>&1; then
            echo "service token: OK (vault: $VAULT)"
        else
            echo "ERROR: service token rejected by 1Password." >&2
            exit 1
        fi
        for item in portainer npm foodfinder-env; do
            if op item get "$item" --vault "$VAULT" --format json >/dev/null 2>&1; then
                echo "item $item: present"
            else
                echo "item $item: MISSING (create it in the $VAULT vault)"
            fi
        done
        ;;
    get)
        require_op
        [ $# -eq 3 ] || { echo "usage: $0 get ITEM FIELD" >&2; exit 2; }
        read_ref "$2" "$3"
        ;;
    env)
        require_op
        emit() { # emit VAR ITEM FIELD
            local v
            v="$(read_ref "$2" "$3")"
            [ -n "$v" ] && printf 'export %s=%q\n' "$1" "$v"
        }
        emit PORTAINER_URL       portainer url
        emit PORTAINER_USERNAME  portainer username
        emit PORTAINER_PASSWORD  portainer password
        emit NPM_URL             npm url
        emit NPM_USERNAME        npm username
        emit NPM_PASSWORD        npm password
        emit SPRING_DATASOURCE_URL       foodfinder-env SPRING_DATASOURCE_URL
        emit SPRING_DATASOURCE_USERNAME  foodfinder-env SPRING_DATASOURCE_USERNAME
        emit SPRING_DATASOURCE_PASSWORD  foodfinder-env SPRING_DATASOURCE_PASSWORD
        emit FOODFINDER_ADMIN_USERNAME   foodfinder-env FOODFINDER_ADMIN_USERNAME
        emit FOODFINDER_ADMIN_PASSWORD   foodfinder-env FOODFINDER_ADMIN_PASSWORD
        emit FOODFINDER_ALLOWED_ORIGINS  foodfinder-env FOODFINDER_ALLOWED_ORIGINS
        ;;
    *)
        sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'
        exit 2
        ;;
esac
