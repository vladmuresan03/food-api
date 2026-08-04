#!/usr/bin/env bash
# Build the foodfinder-api image on the Portainer host so Portainer Standalone
# can deploy it without a build context.
#
# Run this on the same Docker host as Portainer, BEFORE adding the stack.
# After the build, use the stack at deploy/portainer-stack-image.yml in
# Portainer (image-only, no build context).
set -euo pipefail

REPO_URL="${REPO_URL:-https://github.com/vladmuresan03/food-api.git}"
REF="${REF:-main}"
IMAGE_TAG="${IMAGE_TAG:-0.1.0}"
WORKDIR="${WORKDIR:-/tmp/foodfinder-api-build}"

if ! command -v docker >/dev/null 2>&1; then
    echo "ERROR: docker not on PATH. Run this on the Portainer host." >&2
    exit 1
fi

# Clean previous build dir to avoid stale files.
rm -rf "$WORKDIR"
git clone --depth 1 --branch "$REF" "$REPO_URL" "$WORKDIR"

cd "$WORKDIR"
docker build \
    --tag "foodfinder-api:${IMAGE_TAG}" \
    --label "org.opencontainers.image.source=${REPO_URL}" \
    --label "org.opencontainers.image.revision=$(git rev-parse HEAD)" \
    .

echo
echo "Image built: foodfinder-api:${IMAGE_TAG}"
docker image inspect "foodfinder-api:${IMAGE_TAG}" --format '{{.Id}}  {{.Created}}'
echo
echo "Now in Portainer, add the stack using deploy/portainer-stack-image.yml"
echo "(NOT deploy/portainer-stack.yml — that one tries to build from context)."
