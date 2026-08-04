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

## Portainer stack

`deploy/portainer-stack.yml` is the Portainer stack definition.

Required env vars at the stack level:

| Var                                | Example                                                       |
|------------------------------------|---------------------------------------------------------------|
| `FOODFINDER_IMAGE`                 | `registry.treloc.com/foodfinder-api:0.1.0`                    |
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
