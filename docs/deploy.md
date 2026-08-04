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

`deploy/portainer-stack.yml` is the Portainer stack definition. The stack builds the image from the repo's `Dockerfile` (multi-stage Maven + Temurin JRE) on every deploy — no pre-built image is needed.

### Import the stack

1. Portainer → **Stacks** → **Add stack**.
2. **Name**: `foodfinder-api`.
3. **Build method**: leave on the default ("Use the web editor") for the first try. Portainer Standalone has two paths:
   - **Repository** (recommended): paste `https://github.com/vladmuresan03/food-api.git` and set **Compose path** = `deploy/portainer-stack.yml`. Portainer clones the repo, then `docker build` against the included Dockerfile.
   - **Web editor**: paste the contents of `deploy/portainer-stack.yml` directly. Same result, but you lose automatic rebuilds on push.
4. Set the env vars below.
5. **Deploy the stack**.

The first deploy will take 4–6 minutes (Maven download + build of the JRE image). Subsequent deploys use the cached Maven layers and are faster.

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

Networks: `postgresql_foodfinder_net` and `nginx-proxy-manager_default` must exist before deploying the stack. They are external to this stack.

Volume: `foodfinder_media` is created automatically and persists uploaded photos and menu PDFs across deploys.

Healthcheck: container-level HTTP probe against `/actuator/health` over `/dev/tcp`. Returns 200 only when the app is ready to serve traffic.

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
