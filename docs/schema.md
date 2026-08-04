# Schema (V1)

Six tables, defined in `src/main/resources/db/migration/V1__catalog.sql`. Flyway is the sole authority. Hibernate runs with `ddl-auto=validate`, so any drift between the entities and the schema is a startup error.

## Tables

### restaurant

| column          | type             | notes                                         |
|-----------------|------------------|-----------------------------------------------|
| `id`            | BIGINT IDENTITY  | internal                                       |
| `restaurant_key`| VARCHAR(120)     | unique, slug regex `^[a-z0-9]+(?:-[a-z0-9]+)*$` |
| `name`          | VARCHAR(200)     |                                                |
| `website_url`   | TEXT             |                                                |
| `address_line`  | TEXT             |                                                |
| `city`          | VARCHAR(120)     | default `Cluj-Napoca`                           |
| `latitude`      | NUMERIC(9,6)     |                                                |
| `longitude`     | NUMERIC(9,6)     |                                                |
| `status`        | VARCHAR(20)      | DRAFT / ACTIVE / ARCHIVED                       |
| `created_at`    | TIMESTAMPTZ      |                                                |
| `updated_at`    | TIMESTAMPTZ      |                                                |

Constraints:
- `latitude IS NULL` iff `longitude IS NULL` (both null or both set)
- `latitude ∈ [-90, 90]`, `longitude ∈ [-180, 180]`
- `status IN ('DRAFT','ACTIVE','ARCHIVED')`

Indexes: `ix_restaurant_status (status)`.

### menu

| column        | type             | notes                                              |
|---------------|------------------|----------------------------------------------------|
| `id`          | BIGINT IDENTITY  |                                                     |
| `menu_key`    | VARCHAR(120)     | unique, slug regex                                  |
| `restaurant_id` | BIGINT         | FK → restaurant(id)                                 |
| `name`        | VARCHAR(200)     |                                                     |
| `menu_type`   | VARCHAR(20)      | PERMANENT / DAILY / WEEKLY / SEASONAL / OTHER      |
| `status`      | VARCHAR(20)      | DRAFT / PUBLISHED / ARCHIVED                       |
| `source_url`  | TEXT             |                                                     |
| `valid_from`  | DATE             |                                                     |
| `valid_to`    | DATE             |                                                     |
| `published_at`| TIMESTAMPTZ      |                                                     |
| `created_at`  | TIMESTAMPTZ      |                                                     |
| `updated_at`  | TIMESTAMPTZ      |                                                     |

Constraints:
- `valid_to >= valid_from` (or either is null)
- `menu_type` and `status` enums via CHECK
- `UNIQUE (restaurant_id, id)` — supports composite FK from `menu_item`

Indexes: `ix_menu_restaurant_status (restaurant_id, status)`.

### product

| column         | type             | notes                                                |
|----------------|------------------|------------------------------------------------------|
| `id`           | BIGINT IDENTITY  |                                                       |
| `product_key`  | VARCHAR(160)     | unique, slug regex                                    |
| `restaurant_id`| BIGINT           | FK → restaurant(id)                                   |
| `name`         | VARCHAR(250)     |                                                       |
| `description`  | TEXT             |                                                       |
| `weight_text`  | VARCHAR(100)     |                                                       |
| `status`       | VARCHAR(20)      | DRAFT / ACTIVE / ARCHIVED                             |
| `created_at`   | TIMESTAMPTZ      |                                                       |
| `updated_at`   | TIMESTAMPTZ      |                                                       |

Constraints:
- `status` enum via CHECK
- `UNIQUE (restaurant_id, id)` — supports composite FKs from `menu_item` and `photo`

Indexes: `ix_product_restaurant_status (restaurant_id, status)`, `ix_product_name (name)`.

### menu_item

| column         | type             | notes                                            |
|----------------|------------------|--------------------------------------------------|
| `id`           | BIGINT IDENTITY  |                                                  |
| `menu_id`      | BIGINT           |                                                  |
| `product_id`   | BIGINT           |                                                  |
| `restaurant_id`| BIGINT           | denormalized for the same-restaurant invariant  |
| `section_name` | VARCHAR(200)     | default `Altele`                                 |
| `price`        | NUMERIC(12,2)    | nullable                                         |
| `currency`     | VARCHAR(3)       | default `RON`                                    |
| `available`    | BOOLEAN          | default TRUE                                     |
| `sort_order`   | INTEGER          | default 0                                        |
| `created_at`   | TIMESTAMPTZ      |                                                  |
| `updated_at`   | TIMESTAMPTZ      |                                                  |

Constraints:
- `UNIQUE (menu_id, product_id)` — one row per (menu, product) pair
- Composite FKs enforce: `menu_item.menu.restaurant_id = menu_item.product.restaurant_id`
  - `FOREIGN KEY (restaurant_id, menu_id) REFERENCES menu (restaurant_id, id)`
  - `FOREIGN KEY (restaurant_id, product_id) REFERENCES product (restaurant_id, id)`
- `price IS NULL OR price >= 0`
- `sort_order >= 0`
- `currency ~ '^[A-Z]{3}$'`

Indexes: `ix_menu_item_menu_section_sort (menu_id, section_name, sort_order)`, `ix_menu_item_product (product_id)`.

### photo

| column                 | type             | notes                                              |
|------------------------|------------------|----------------------------------------------------|
| `id`                   | BIGINT IDENTITY  |                                                    |
| `photo_key`            | VARCHAR(160)     | unique, slug regex                                  |
| `restaurant_id`        | BIGINT           | FK → restaurant(id)                                 |
| `product_id`          | BIGINT           | nullable, FK → product(id)                         |
| `source_type`          | VARCHAR(30)      | UPLOAD / RESTAURANT_OFFICIAL / GOOGLE_PROTOTYPE / IMPORTED_URL |
| `storage_key`          | TEXT             | nullable (XOR with `external_url`)                  |
| `external_url`         | TEXT             | nullable (XOR with `storage_key`)                  |
| `thumbnail_storage_key`| TEXT             | nullable                                            |
| `mime_type`            | VARCHAR(100)     | nullable                                            |
| `width`                | INTEGER          | nullable, > 0                                       |
| `height`               | INTEGER          | nullable, > 0                                       |
| `alt_text`             | VARCHAR(300)     | nullable                                            |
| `is_primary`           | BOOLEAN          | default FALSE                                       |
| `status`               | VARCHAR(20)      | ACTIVE / ARCHIVED                                   |
| `sha256`               | VARCHAR(64)      | nullable                                            |
| `created_at`           | TIMESTAMPTZ      |                                                    |
| `updated_at`           | TIMESTAMPTZ      |                                                    |

Constraints:
- exactly one of `storage_key` or `external_url` is set
- `width > 0`, `height > 0` when present
- composite FK to `product (restaurant_id, id)` — when `product_id` is set, the product must belong to the same restaurant
- `source_type` and `status` enums via CHECK
- partial unique indexes:
  - `ux_photo_primary_per_product` — at most one `is_primary=true` per non-null `product_id`
  - `ux_photo_primary_per_restaurant` — at most one restaurant-level `is_primary=true`

Indexes: `ix_photo_restaurant_product_status (restaurant_id, product_id, status)`.

### menu_asset

| column             | type             | notes                                  |
|--------------------|------------------|----------------------------------------|
| `id`               | BIGINT IDENTITY  |                                        |
| `asset_key`        | VARCHAR(160)     | unique, slug regex                      |
| `menu_id`          | BIGINT           | FK → menu(id)                           |
| `asset_type`       | VARCHAR(20)      | PDF / IMAGE / URL                      |
| `original_filename`| VARCHAR(255)     | nullable                                |
| `storage_key`      | TEXT             | nullable (XOR with `source_url`)       |
| `source_url`       | TEXT             | nullable (XOR with `storage_key`)      |
| `mime_type`        | VARCHAR(100)     | nullable                                |
| `size_bytes`       | BIGINT           | nullable, > 0                           |
| `sha256`           | VARCHAR(64)      | nullable, hex                           |
| `sort_order`       | INTEGER          | default 0, >= 0                        |
| `created_at`       | TIMESTAMPTZ      |                                        |
| `updated_at`       | TIMESTAMPTZ      |                                        |

Constraints:
- exactly one of `storage_key` or `source_url`
- `asset_type` enum
- `size_bytes > 0` when present
- `sha256 ~ '^[0-9a-f]{64}$'` when present

Indexes: `ix_menu_asset_menu_sort (menu_id, sort_order)`.

## Entity-relationship sketch

```
restaurant 1 ─< menu 1 ─< menu_item >─ product >─ 1 restaurant
                                          │
                       photo (restaurant_id, product_id?)*──┘
                       menu_asset >── menu
```

\* The product on a photo (when present) is FK-checked to belong to the same restaurant as the photo's `restaurant_id`.

## Invariants worth knowing

- **Slug keys.** Every `*_key` column has the same CHECK regex. Importer rejects anything that does not match.
- **Same-restaurant invariant.** The DB itself rejects a `menu_item` that links a menu and a product from different restaurants, and a photo that links a product from a different restaurant. Application code additionally validates before submit.
- **Archive, not delete.** `restaurant`, `menu`, `product`, `photo` carry a `status` field; "deletion" is a status change. Only `menu_item` is physically deletable through the admin API.
- **Primary photo exclusivity.** Partial unique indexes enforce "at most one primary per product" and "at most one restaurant-level primary".
- **Storage XOR.** A photo or menu asset must have exactly one of `storage_key` (uploaded) or `external_url`/`source_url` (registered reference). Photos and assets that violate this cannot be inserted.

## Migrations

Only `V1__catalog.sql` exists today. Add new migrations as `V2__*.sql`, `V3__*.sql`, etc. Never edit `V1` once it has been applied to a real database.
