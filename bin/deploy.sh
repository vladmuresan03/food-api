#!/usr/bin/env bash
# End-to-end deploy for the foodfinder-api stack.
#
#   bin/deploy.sh IMAGE_TAG               # e.g. bin/deploy.sh 0.1.2
#   IMAGE_TAG=0.1.2 STACK_ID=159 bin/deploy.sh
#
# Steps:
#   1. Auth to Portainer via 1Password service-account token (Keychain).
#   2. Build foodfinder-api:IMAGE_TAG on the Portainer host via the
#      remote docker build API (clones vladmuresan03/food-api@main from
#      GitHub). Skipped if the image already exists on the host.
#   3. PUT the existing Portainer stack with the repo's
#      deploy/portainer-stack-image.yml as the new file content and the
#      stack's current Env values, swapping only IMAGE_TAG. Other env
#      values (SPRING_DATASOURCE_PASSWORD, FOODFINDER_ADMIN_PASSWORD,
#      etc.) are preserved so the deploy script never has to know them.
#   4. Wait until the new container is healthy.
#   5. Smoke-test the public endpoints behind the NPM proxy.
#
# Requires: op, curl, python3, jq (optional). bin/op-token-store.sh must
# have populated the macOS Keychain first.
set -euo pipefail

IMAGE_TAG="${IMAGE_TAG:-${1:-}}"
STACK_ID="${STACK_ID:-159}"
ENDPOINT_ID="${ENDPOINT_ID:-3}"
REPO_URL="${REPO_URL:-https://github.com/vladmuresan03/food-api.git}"
GIT_REF="${GIT_REF:-main}"
HEALTH_TIMEOUT_S="${HEALTH_TIMEOUT_S:-180}"
SMOKE_HOST="${SMOKE_HOST:-food.treloc.com}"

if [ -z "$IMAGE_TAG" ]; then
    echo "usage: $0 IMAGE_TAG" >&2
    echo "  e.g. $0 0.1.2" >&2
    exit 2
fi

KEYCHAIN_SERVICE="foodfinder-op"
KEYCHAIN_ACCOUNT="service-token"
export OP_SERVICE_ACCOUNT_TOKEN="$(security find-generic-password -s "$KEYCHAIN_SERVICE" -a "$KEYCHAIN_ACCOUNT" -w 2>/dev/null || true)"
[ -n "$OP_SERVICE_ACCOUNT_TOKEN" ] || { echo "ERROR: no 1Password service token in Keychain. Run bin/op-token-store.sh." >&2; exit 1; }

P="https://portainer.treloc.com/api"

portainer_auth() {
    # Resolve Portainer credentials from 1Password. Uses bin/deploy.env
    # (which the user copies from bin/deploy.env.example and fills with
    # the 1Password item UUIDs of the Portainer and NPM Login items).
    local env_file item user pass
    env_file="$(dirname "$0")/deploy.env"
    if [ ! -f "$env_file" ]; then
        echo "ERROR: $env_file not found. Copy deploy.env.example to deploy.env" >&2
        echo "       and set OP_ITEM_PORTAINER to the UUID of the Portainer Login." >&2
        exit 1
    fi
    # shellcheck disable=SC1090
    . "$env_file"
    item="${OP_ITEM_PORTAINER:-}"
    [ -n "$item" ] || { echo "ERROR: OP_ITEM_PORTAINER not set in $env_file" >&2; exit 1; }

    user="$(op item get "$item" --vault treloc --format json 2>/dev/null | python3 -c '
import json,sys
it=json.load(sys.stdin)
print(next((f.get("value","") for f in it.get("fields",[]) if f.get("purpose")=="USERNAME"), ""))
')"
    pass="$(op item get "$item" --vault treloc --format json 2>/dev/null | python3 -c '
import json,sys
it=json.load(sys.stdin)
print(next((f.get("value","") for f in it.get("fields",[]) if f.get("purpose")=="PASSWORD"), ""))
')"
    if [ -z "$user" ] || [ -z "$pass" ]; then
        echo "ERROR: item $item has no username/password fields." >&2
        exit 1
    fi
    curl -sk -m 30 -X POST "$P/auth" -H 'Content-Type: application/json' \
        --data "$(python3 -c 'import json,sys; print(json.dumps({"Username":sys.argv[1],"Password":sys.argv[2]}))' "$user" "$pass")" \
        | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("jwt",""))'
}

jwt="$(portainer_auth)"
[ -n "$jwt" ] || { echo "ERROR: Portainer auth failed." >&2; exit 1; }
trap 'unset jwt OP_SERVICE_ACCOUNT_TOKEN' EXIT
echo "== Portainer auth OK =="

# image already on host?
echo "== check if foodfinder-api:$IMAGE_TAG is already on the host =="
have_image="$(curl -sk -m 15 "$P/endpoints/$ENDPOINT_ID/docker/images/json" -H "Authorization: Bearer $jwt" | python3 -c '
import json, sys
target = "foodfinder-api:'$IMAGE_TAG'"
data = json.load(sys.stdin)
print("yes" if any(target in (t or "") for i in data for t in (i.get("RepoTags") or [])) else "no")
')"

if [ "$have_image" = "yes" ]; then
    echo "  present, skipping build"
else
    echo "  absent, building via remote docker build (this takes several minutes)"
    remote="$(python3 -c "import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1]+'#'+sys.argv[2], safe=''))" "$REPO_URL" "$GIT_REF")"
    tag="$(python3 -c "import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1]+':'+sys.argv[2], safe=''))" "foodfinder-api" "$IMAGE_TAG")"
    build_log="$(mktemp -t foodfinder-build.XXXXXX.log)"
    http_code=$(curl -sk -N -m 1800 -X POST \
        "$P/endpoints/$ENDPOINT_ID/docker/build?t=$tag&remote=$remote" \
        -H "Authorization: Bearer $jwt" \
        -o "$build_log" -w '%{http_code}')
    if [ "$http_code" != "200" ]; then
        echo "ERROR: build returned HTTP $http_code. Last 50 lines:" >&2
        tail -n 50 "$build_log" >&2
        exit 1
    fi
    echo "  build OK (log: $build_log)"
    mavis-trash "$build_log" 2>/dev/null || true
fi

# fetch current stack env (to preserve secrets we do not know)
echo "== fetch current stack $STACK_ID env =="
stack_json="$(curl -sk -m 15 "$P/stacks/$STACK_ID" -H "Authorization: Bearer $jwt")"
new_env="$(printf '%s' "$stack_json" | python3 -c "
import json,sys
s=json.load(sys.stdin)
env=[{'name':e['name'],'value':e.get('value','')} for e in s.get('Env',[])]
# replace IMAGE_TAG with the one we are deploying
seen=False
for e in env:
    if e['name']=='IMAGE_TAG':
        e['value']='$IMAGE_TAG'; seen=True
if not seen:
    env.append({'name':'IMAGE_TAG','value':'$IMAGE_TAG'})
print(json.dumps(env))
")"

compose="$(cat "$(dirname "$0")/../deploy/portainer-stack-image.yml")"

# write body, PUT
body_file="$(mktemp -t foodfinder-stack.XXXXXX.json)"
python3 - "$compose" "$new_env" "$STACK_ID" "$ENDPOINT_ID" "$body_file" <<'PY'
import json, sys
compose, new_env, stack_id, endpoint_id, out = sys.argv[1:6]
json.dump({
    "StackFileContent": compose,
    "Env": json.loads(new_env),
    "Prune": True,
}, open(out, "w"))
PY

echo "== PUT stack $STACK_ID (endpoint $ENDPOINT_ID, image $IMAGE_TAG) =="
http_code=$(curl -sk -m 180 -X PUT "$P/stacks/$STACK_ID?endpointId=$ENDPOINT_ID" \
    -H "Authorization: Bearer $jwt" -H 'Content-Type: application/json' \
    --data @"$body_file" -o /tmp/foodfinder-put.json -w '%{http_code}')
mavis-trash "$body_file" 2>/dev/null || true
if [ "$http_code" != "200" ]; then
    echo "ERROR: stack update returned HTTP $http_code" >&2
    python3 -c 'import json; d=json.load(open("/tmp/foodfinder-put.json")); print(d.get("message",d.get("details",str(d))))' >&2
    exit 1
fi
python3 -c 'import json; d=json.load(open("/tmp/foodfinder-put.json")); print("  stack:", d.get("Name"), "status:", d.get("Status"))'
mavis-trash /tmp/foodfinder-put.json 2>/dev/null || true

# poll health
echo "== wait for foodfinder-api to become healthy with image $IMAGE_TAG (timeout ${HEALTH_TIMEOUT_S}s) =="
deadline=$(( $(date +%s) + HEALTH_TIMEOUT_S ))
got_healthy=0
while [ "$(date +%s)" -lt "$deadline" ]; do
    out="$(curl -sk -m 15 "$P/endpoints/$ENDPOINT_ID/docker/containers/json?all=1" -H "Authorization: Bearer $jwt" | python3 -c '
import json,sys
for c in json.load(sys.stdin):
    if (c.get("Names") or [""])[0].lstrip("/")=="foodfinder-api":
        print((c.get("Image") or ""), "|", (c.get("Status") or ""))
')"
    echo "  $(date +%H:%M:%S) $out"
    case "$out" in
        *foodfinder-api:$IMAGE_TAG*healthy*) echo "HEALTHY"; got_healthy=1; break;;
        *foodfinder-api:$IMAGE_TAG*Exited*|*foodfinder-api:$IMAGE_TAG*Dead*) echo "CONTAINER DIED"; exit 1;;
    esac
    sleep 5
done
[ "$got_healthy" = "1" ] || { echo "ERROR: container did not become healthy within ${HEALTH_TIMEOUT_S}s" >&2; exit 1; }

# smoke test
echo "== smoke test =="
check() {
    local desc="$1" url="$2" want="$3"
    local got
    got="$(curl -sk -m 20 -o /dev/null -w '%{http_code}' "$url")"
    if [ "$got" = "$want" ]; then
        printf '  %-30s HTTP %s OK\n' "$desc" "$got"
    else
        printf '  %-30s HTTP %s (expected %s) FAIL\n' "$desc" "$got" "$want" >&2
        FAIL=1
    fi
}
FAIL=0
check "redirect /"          "https://$SMOKE_HOST/"            302
check "actuator health"     "https://$SMOKE_HOST/actuator/health" 200
check "public api"          "https://$SMOKE_HOST/api/restaurants"   200
check "admin login page"    "https://$SMOKE_HOST/admin/login"      200
[ "$FAIL" -eq 0 ] || { echo "smoke test FAILED" >&2; exit 1; }

echo
echo "== deploy done: foodfinder-api:$IMAGE_TAG on https://$SMOKE_HOST =="
