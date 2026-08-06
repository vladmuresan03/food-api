# Deployment

## Architecture

- **App container** — Spring Boot 4.1 / Java 21. Built from `Dockerfile` (multi-stage: `maven:3.9-eclipse-temurin-21` build, `eclipse-temurin:21-jre` runtime, non-root user, JRE-based healthcheck).
- **PostgreSQL** — version 17, already deployed elsewhere in the cluster. Joins the network `postgresql_foodfinder_net`.
- **Reverse proxy** — Nginx Proxy Manager at https://proxy.treloc.com, network `nginx-proxy-manager_default`, configured to terminate TLS for `food.treloc.com` and forward to the app container's port 8080.

## Image build

```bash
docker build -t registry.treloc.com/foodfinder-api:0.1.0 .
docker push registry.treloc.com/foodfinder-api:0.1.0
```

Adjust the registry and tag to match your setup.

## Host port mapping (loopback only)

The stack publishes the app on `127.0.0.1:9150` mapped to container port `8080`:

```yaml
ports:
  - "127.0.0.1:9150:8080"
```

This makes the app reachable directly on `http://localhost:9150` for ad-hoc checks (e.g. `curl http://localhost:9150/actuator/health` while SSH'd into the server) **without exposing it on the host's public interface**. Anything not on the host itself is blocked by the kernel.

The reverse path through Nginx Proxy Manager at `food.treloc.com:443` reaches the same container port `8080` over the `nginx-proxy-manager_default` Docker network, not via the loopback mapping.

**Do not change `127.0.0.1:9150:8080` to `9150:8080`** without a host-level firewall rule. The un-prefixed form binds the admin port on every interface, including the public one — the admin endpoints and `/actuator/*` would be reachable from the internet.

If you change the internal port, also update the NPM proxy host's **Forward Port** to match. The stack file is the single source of truth for which port the container listens on.

## Portainer stack

Portainer Standalone cannot reliably build images from a `build:` context
(it can clone the repo, but `context: .` resolves against an unspecified
working directory and you get `no such file or directory: Dockerfile`).
The supported pattern on Portainer Standalone is **build the image on
the host, then deploy with `image:` only**.

### Step 1 — build the image on the Portainer host

On the same Docker host as Portainer, run:

```bash
curl -fsSL https://raw.githubusercontent.com/vladmuresan03/food-api/main/bin/build-image.sh | bash
# or, after cloning:
./bin/build-image.sh
```

This clones the repo into `/tmp/foodfinder-api-build`, runs `docker build`
against the included multi-stage Dockerfile, and tags the result as
`foodfinder-api:0.1.0`. First build takes 4–6 minutes (Maven dependencies
+ JRE base image). Subsequent builds reuse the cache.

### Step 2 — import the stack

`deploy/portainer-stack-image.yml` is the Portainer stack definition.
It uses `image: foodfinder-api:${IMAGE_TAG:-0.1.0}` and no build context.

1. Portainer → **Stacks** → **Add stack**.
2. **Name**: `foodfinder-api`.
3. **Build method**: **Web editor**. Paste the contents of `deploy/portainer-stack-image.yml`.
4. Set the env vars below (in the Environment section) — or export them
   from 1Password, see [Secrets via 1Password](#secrets-via-1password-service-account).
5. **Deploy the stack**.

The first time you do this, the stack starts in seconds because the
image is already built.

### Step 3 — redeploy after a code change

```bash
# rebuild with the same IMAGE_TAG to overwrite
./bin/build-image.sh
# in Portainer UI: Stacks → foodfinder-api → Editor → "Pull and redeploy"
# (or just "Redeploy" — Portainer will restart the container with the
# updated local image).
```

### Required env vars

| Var                                | Example                                                       |
|------------------------------------|---------------------------------------------------------------|
| `SPRING_DATASOURCE_URL`            | `jdbc:postgresql://postgresql:5432/foodfinder_spring`         |
| `SPRING_DATASOURCE_USERNAME`       | `foodfinder`                                                  |
| `SPRING_DATASOURCE_PASSWORD`       | `…`                                                           |
| `FOODFINDER_ADMIN_USERNAME`        | `admin`                                                       |
| `FOODFINDER_ADMIN_PASSWORD`        | `…`                                                           |
| `FOODFINDER_STORAGE_DIR`           | `/data/foodfinder`                                            |
| `FOODFINDER_ALLOWED_ORIGINS`       | `https://food.treloc.com`                                     |

Values live in 1Password — see [Secrets via 1Password](#secrets-via-1password-service-account).

Networks: `postgresql_foodfinder_net` and `nginx-proxy-manager_default` must exist before deploying the stack. They are external to this stack.

Volume: `foodfinder_media` is created automatically and persists uploaded photos and menu PDFs across deploys.

Healthcheck: container-level HTTP probe against `/actuator/health` over `/dev/tcp`. Returns 200 only when the app is ready to serve traffic.

### Alternative: registry push

If you prefer to push the image to a registry instead of building on the
Portainer host:

```bash
docker build -t registry.treloc.com/foodfinder-api:0.1.0 .
docker push registry.treloc.com/foodfinder-api:0.1.0
```

Then in `deploy/portainer-stack-image.yml`, change the `image:` line to
the full registry path and ensure Portainer is configured to pull from
that registry. The default `bin/build-image.sh` flow is simpler and
avoids registry credentials.

## Nginx Proxy Manager setup

1. Add a new **Proxy Host** with domain `food.treloc.com`, scheme `http`, forward hostname `foodfinder-api`, port `8080`.
2. Enable **Force SSL** and **HTTP/2**.
3. Request an SSL certificate (Let's Encrypt) for `food.treloc.com`.
4. (Optional) Enable caching for `/api/photos/*/content` and `/api/photos/*/thumbnail`.

## DNS

Point `food.treloc.com` at the public IP of the NPM host. The proxy is shared with other apps on the same host; the proxy host configuration in step 1 isolates this app to its subdomain.

## Rollout

1. Build and push the new image.
2. In Portainer, update the stack with the new image tag and re-deploy.
3. Watch the container healthcheck transition to `healthy`.
4. Smoke-test the public API: `curl -fsSL https://food.treloc.com/actuator/health` → `{"status":"UP"}`.

## Rollback

Re-deploy the previous image tag. The schema is forward-compatible — V1 has not changed since the first deploy.

## Secrets via 1Password (service account)

Deploy credentials (Portainer, Nginx Proxy Manager, app env vars) live in
1Password and are resolved on demand via a **service-account token**. No
plaintext secrets on disk, in the repo, or in chat.

### One-time setup

1. In 1Password, create a vault named `infra`.
2. Create these items in it:
   - **`portainer`** (Login): `username`, `password`, website = Portainer URL.
   - **`npm`** (Login): `username`, `password`, website = `https://proxy.treloc.com`.
   - **`foodfinder-env`** (Login, or Secure Note with labeled fields): custom
     fields named exactly like the stack env vars — `SPRING_DATASOURCE_URL`,
     `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`,
     `FOODFINDER_ADMIN_USERNAME`, `FOODFINDER_ADMIN_PASSWORD`,
     `FOODFINDER_ALLOWED_ORIGINS`.
3. 1Password web → **Developer → Service Accounts → New**. Grant it
   **View** access to the `infra` vault only. Copy the `ops-...` token.
4. On this machine: `bin/op-token-store.sh` — paste the token when prompted.
   It is validated against 1Password and stored in the macOS Keychain
   (`foodfinder-op/service-token`), never in a file.
5. `bin/op-secret.sh check` — verifies the token and the vault layout
   without printing any secret.

### Usage

```bash
bin/op-secret.sh check                   # token + item presence, no secrets
bin/op-secret.sh get portainer password  # one secret, e.g. for piping
eval "$(bin/op-secret.sh env)"           # export PORTAINER_*, NPM_*, stack env vars
```

Use a different vault with `OP_VAULT=myvault bin/op-secret.sh env`.
Different item/field names? Adjust the mapping at the top of `bin/op-secret.sh`.
