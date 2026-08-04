# FoodFinder API (Spring Boot)

A minimalist catalog API and table-first admin for FoodFinder, written in Spring Boot 4.1 / Java 21.

This is a **sibling** of the existing FastAPI deployment. It exists to make the catalog data easy to inspect and administer. The FastAPI deployment remains untouched until this Spring stack reaches data and endpoint parity.

## Stack

- Java 21 (build target), tested on Java 25 runtime
- Spring Boot 4.1.0 (Spring MVC, not WebFlux)
- Spring Data JPA + Hibernate 7
- PostgreSQL 17
- Flyway (sole schema authority, `ddl-auto=validate`)
- Spring Security (form login for the admin UI, HTTP Basic for the admin API, single user)
- Thymeleaf for the admin UI (server-rendered, no JS framework)
- Apache Commons CSV for import/export
- ImageIO for image decoding and thumbnail generation
- PostgreSQL JDBC, JUnit 5, AssertJ, Spring MockMvc

**No** Lombok, MapStruct, WebFlux, Redis, Elasticsearch, Kafka, Spring Batch, GraphQL, JSONB catalog fields, PostgreSQL enum types, Hibernate schema generation. No microservices, no hexagonal-architecture ceremony.

## Project layout

```
src/main/java/com/foodfinder/
├── FoodFinderApplication.java
├── common/        — Timestamped, GlobalExceptionHandler, AdminConflictException
├── security/      — SecurityConfig
├── restaurant/    — Restaurant, RestaurantStatus, RestaurantRepository
├── menu/          — Menu, MenuType, MenuStatus, MenuItem, MenuAsset, ...
├── product/       — Product, ProductStatus, ProductRepository
├── photo/         — Photo, PhotoStorageService, PhotoRepository, PhotoController
├── storage/       — FileStorage, LocalFileStorage, ImageProcessing, Hashes
├── csv/           — RestaurantCsv, MenuCsv, ProductCsv, MenuItemCsv, PhotoCsv, MenuAssetCsv, CsvController, ...
├── publicapi/     — Dtos, PublicApiService, PublicController
└── admin/         — Admin*Controller (REST), AdminViewController (Thymeleaf), AdminViewService

src/main/resources/
├── application.yaml
├── db/migration/V1__catalog.sql
└── templates/admin/

data/legacy-import/  — generated CSV snapshots from the live FastAPI
deploy/portainer-stack.yml
docs/
Dockerfile
docker-compose.yml
```

## Database

Internal BIGINT identity primary keys. Stable lowercase slug keys (`restaurant_key`, `menu_key`, `product_key`, `photo_key`, `asset_key`) are the only way external systems identify records. Foreign keys, useful indexes, and CHECK constraints enforce the same-restaurant invariant at the database level. The full schema is in [`docs/schema.md`](docs/schema.md).

Run migrations on a fresh DB: just start the app. Flyway reads `db/migration/V1__catalog.sql` on boot.

## Build and run

### Prerequisites

- JDK 21 or 25 on the path
- Maven 3.9+ (or use the bundled wrapper)
- PostgreSQL 14+

The repo ships a Maven Wrapper configuration; if you have `mvn` you can run `mvn -B package` directly.

### Local development (no Docker)

```bash
# create the role and databases (once)
psql -d postgres -c "CREATE ROLE foodfinder LOGIN PASSWORD 'foodfinder' CREATEDB;"
psql -d postgres -c "CREATE DATABASE foodfinder OWNER foodfinder;"
psql -d postgres -c "CREATE DATABASE foodfinder_test OWNER foodfinder;"

# build and test
mvn -B test

# run locally
FOODFINDER_ADMIN_USERNAME=admin \
FOODFINDER_ADMIN_PASSWORD=admin \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/foodfinder \
SPRING_DATASOURCE_USERNAME=foodfinder \
SPRING_DATASOURCE_PASSWORD=foodfinder \
mvn -B spring-boot:run
```

Open <http://localhost:8080/admin/login> and sign in with `admin` / `admin`.

### Docker

```bash
docker compose up --build
```

The compose file builds the image from `Dockerfile` and brings up PostgreSQL 17 alongside the app. Media is persisted in the `foodfinder_media` named volume.

### Production (Portainer)

`deploy/portainer-stack.yml` is a Portainer stack definition that:

- pulls the pre-built image from `${FOODFINDER_IMAGE}`
- joins the existing `postgresql_foodfinder_net` and `nginx-proxy-manager_default` networks (these are created and managed by other stacks — see [`docs/deploy.md`](docs/deploy.md) for the Portainer + NPM setup)
- persists media in the `foodfinder_media` named volume
- exposes only `/actuator/health` and `/actuator/info`
- runs as a non-root user inside the container

Push a new image to update, then re-deploy the stack in Portainer.

## Endpoints

| Path                                     | Auth | Notes                                                    |
|------------------------------------------|------|----------------------------------------------------------|
| `GET /api/restaurants`                    | —    | Public. Defaults to `status=ACTIVE`.                      |
| `GET /api/restaurants/{key}`              | —    | Public. Includes primary photo and product count.        |
| `GET /api/restaurants/{key}/menus`        | —    | Public. `PUBLISHED` menus only.                            |
| `GET /api/menus/{key}`                    | —    | Public. Nested sections.                                   |
| `GET /api/products`                       | —    | Public. Filters: `q`, `restaurantKey`, `menuKey`, `section`, `minPrice`, `maxPrice`, `hasPhoto`, `available`, `page`, `size`, `sort`. |
| `GET /api/products/{key}`                 | —    | Public. Photos + menu appearances.                         |
| `GET /api/photos/{key}/content`           | —    | Public. ACTIVE only.                                       |
| `GET /api/photos/{key}/thumbnail`         | —    | Public. ACTIVE only.                                       |
| `GET /admin` … `/admin/csv`                | yes  | Thymeleaf pages (form login).                              |
| `GET /admin/api/restaurants` …            | yes  | REST CRUD. Basic auth or session.                          |
| `POST /admin/api/csv/*`                   | yes  | Multipart upload + `dryRun=true|false` query parameter.    |
| `GET /admin/api/csv/*`                    | yes  | CSV download (`text/csv`).                                 |
| `GET /actuator/health`                    | —    | Open.                                                     |
| `GET /actuator/info`                      | —    | Open.                                                     |

Error responses use RFC 7807 `application/problem+json`. Codes: 400 for validation, 404 for missing, 409 for key conflicts or cross-restaurant relations.

## CSV

CSV is the primary interchange format. Headers, semantics, and round-trip behavior are documented in [`docs/csv-contract.md`](docs/csv-contract.md). Use the import endpoints with `dryRun=true` to preview all row errors before writing.

## Legacy data import

A one-shot Python script reads the live FastAPI and writes CSVs in the new contract:

```bash
cd data/legacy-import
python3 generate.py
# then import through the admin UI at /admin/csv, or
# via curl with basic auth against /admin/api/csv/*
```

The CSVs are checked into the repo for reproducibility; you can re-generate at any time.

## Tests

```bash
mvn -B test
```

Tests run against a real PostgreSQL (`foodfinder_test`). With `mvn -B test` you get 66 tests covering the schema constraints, the CSV round-trip, the public read API, the admin REST + security, the file storage layer (uploads, MIME validation, SHA-256, path traversal), and the Thymeleaf admin pages.

## Known limitations of the v1

- Photos: only `image/jpeg` and `image/png` are accepted. WebP is supported for menu assets (storage only, no decoding).
- The CSV import is single-file-per-resource; cross-resource imports have to be ordered: restaurants → menus → products → menu_items → photos.
- The admin UI has no inline editors — use separate HTML forms (TODO once the data stabilizes).
- A single in-memory admin user; no users table, no roles, no audit log.

## License

Internal project.
