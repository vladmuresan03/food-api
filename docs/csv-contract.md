# CSV contract

CSV is the primary interchange format. All imports/exports share the same column order, encoding, and semantics. The endpoint is `POST /admin/api/csv/{resource}?dryRun=true|false` (multipart with a `file` field) and `GET /admin/api/csv/{resource}` (text/csv download).

## Common rules

- **Encoding:** UTF-8, with optional BOM. The importer accepts BOM transparently.
- **Delimiter:** comma. Standard RFC 4180 quoting (double-quote, escape by doubling).
- **Header row:** required, treated as the schema. Unknown headers produce an `UNKNOWN_HEADER` error and the import is rejected. **No silent column ignoring.**
- **Whitespace:** surrounding whitespace is trimmed.
- **Empty cells:** mean `NULL` (not the string `"null"`).
- **Slug keys:** every `*_key` column must match `^[a-z0-9]+(?:-[a-z0-9]+)*$`.
- **Dry-run:** with `dryRun=true`, the importer runs the full validation and returns a report, but performs no writes.
- **Strict mode:** the import is atomic. If any row has an error, **no rows are written** and the report lists every problem.
- **Upsert:** rows are matched by their natural key (`restaurant_key`, `menu_key`, etc.). The report distinguishes `inserted` and `updated`. A row already in the database that is **absent** from the CSV is not deleted.

## Response shape

```json
{
  "dryRun": true,
  "totalRows": 47,
  "inserted": 0,
  "updated": 0,
  "unchanged": 0,
  "errors": [
    {
      "row": 12,
      "field": "restaurant_key",
      "code": "UNKNOWN_RESTAURANT",
      "message": "Unknown restaurant_key: bad-r"
    }
  ]
}
```

Error codes are listed in `com.foodfinder.csv.CsvErrorCode`.

## File contracts

### restaurants.csv

```
restaurant_key, name, website_url, address_line, city, latitude, longitude, status
```

- `restaurant_key` — required, slug.
- `name` — required.
- `website_url`, `address_line` — optional.
- `city` — optional, defaults to `Cluj-Napoca`.
- `latitude`, `longitude` — either both null or both set, within range. Decimal degrees.
- `status` — `DRAFT` / `ACTIVE` / `ARCHIVED`. Defaults to `DRAFT`.

### menus.csv

```
menu_key, restaurant_key, name, menu_type, status, source_url, valid_from, valid_to
```

- `menu_key` — required, slug.
- `restaurant_key` — required, must exist in DB.
- `name` — required.
- `menu_type` — `PERMANENT` / `DAILY` / `WEEKLY` / `SEASONAL` / `OTHER`. Defaults to `PERMANENT`.
- `status` — `DRAFT` / `PUBLISHED` / `ARCHIVED`. Defaults to `DRAFT`.
- `source_url` — optional URL.
- `valid_from`, `valid_to` — `YYYY-MM-DD`. `valid_to >= valid_from`.

### products.csv

```
product_key, restaurant_key, name, description, weight_text, status
```

- `product_key` — required, slug.
- `restaurant_key` — required, must exist in DB.
- `name` — required.
- `description`, `weight_text` — optional.
- `status` — `DRAFT` / `ACTIVE` / `ARCHIVED`. Defaults to `DRAFT`.

### menu_items.csv

```
menu_key, product_key, section_name, price, currency, available, sort_order, source_url
```

- `menu_key`, `product_key` — required, must exist in DB.
- The two must belong to the **same restaurant**; cross-restaurant rows are rejected.
- `section_name` — optional, defaults to `Altele`.
- `price` — optional decimal, `>= 0` when present.
- `currency` — 3 uppercase letters, defaults to `RON`.
- `available` — `true` / `false` / `1` / `0`. Defaults to `true`.
- `sort_order` — non-negative integer.
- A given `(menu_key, product_key)` pair can appear at most once per file.

### photos.csv

```
photo_key, restaurant_key, product_key, source_type, external_url, alt_text, is_primary, status
```

Plus the **export-only** columns (present in export, ignored on import):

```
storage_key, thumbnail_storage_key, mime_type, width, height
```

- `photo_key` — required, slug.
- `restaurant_key` — required, must exist.
- `product_key` — optional. When present, must belong to the same restaurant.
- `source_type` — `UPLOAD` / `RESTAURANT_OFFICIAL` / `GOOGLE_PROTOTYPE` / `IMPORTED_URL`.
- `external_url` — required when `source_type=IMPORTED_URL` or `GOOGLE_PROTOTYPE`. The CSV-import XOR with `storage_key` is enforced: exactly one of the two must be set per row. For metadata-only imports (the only kind supported by `photos.csv`), `storage_key` is left empty and `external_url` carries the original URL.
- `is_primary` — boolean. When a row sets `is_primary=true`, the import service demotes any other primary photo for the same scope (product or restaurant) in the same transaction.
- `status` — `ACTIVE` / `ARCHIVED`. Defaults to `ACTIVE`.

### menu_assets.csv

```
asset_key, menu_key, asset_type, original_filename, source_url, mime_type, size_bytes, sha256, sort_order
```

Plus the **export-only** column:

```
storage_key
```

- `asset_key` — required, slug.
- `menu_key` — required, must exist.
- `asset_type` — `PDF` / `IMAGE` / `URL`.
- `source_url` — required for `URL` assets; nullable for `PDF`/`IMAGE` with uploaded file.
- `mime_type` — for `PDF` assets typically `application/pdf`; for `IMAGE` typically `image/jpeg`/`png`/`webp`.
- `size_bytes` — optional, `> 0` when present.
- `sha256` — optional, 64 lowercase hex chars.

## Import order

Cross-file foreign keys are validated against the **current database state**, not the file being imported. To import a complete catalog from CSV, run them in this order:

1. `restaurants.csv`
2. `menus.csv`
3. `products.csv`
4. `menu_items.csv`
5. `photos.csv`
6. `menu_assets.csv`

Or use the admin UI at `/admin/csv` which renders all six forms in one page.

## Round-trip stability

Exporting then re-importing the same data must produce zero row errors. This is verified by `CsvRoundTripTest`. Export uses the column order listed above, sorted by stable key (`restaurant_key`, `menu_key`, …).
