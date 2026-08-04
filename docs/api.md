# HTTP API

All public endpoints return `application/json`. Errors use RFC 7807 `application/problem+json`. Timestamps are ISO-8601 (`Instant` → e.g. `2026-08-04T11:00:00Z`). Prices are `BigDecimal` serialized as JSON numbers with up to 2 decimal places.

## Public read API (no auth)

### `GET /api/restaurants`

Query parameters: `q`, `status`, `city`, `page`, `size`, `sort`. Default `status=ACTIVE`.

```json
[
  {
    "key": "big-belly-cluj-napoca",
    "name": "Big Belly",
    "city": "Cluj-Napoca",
    "latitude": 46.761202,
    "longitude": 23.565204,
    "status": "ACTIVE",
    "updatedAt": "2026-08-04T11:00:00Z"
  }
]
```

### `GET /api/restaurants/{restaurantKey}`

```json
{
  "key": "big-belly-cluj-napoca",
  "name": "Big Belly",
  "websiteUrl": null,
  "addressLine": "Calea Mănăștur nr. 68, Cluj-Napoca, RO",
  "city": "Cluj-Napoca",
  "latitude": 46.761202,
  "longitude": 23.565204,
  "status": "ACTIVE",
  "primaryPhotoUrl": "/api/photos/ph-.../content",
  "primaryPhotoThumbnailUrl": "/api/photos/ph-.../thumbnail",
  "productCount": 47,
  "menus": [
    {
      "key": "big-belly-cluj-napoca-main",
      "name": "Main Menu",
      "type": "PERMANENT",
      "validFrom": null,
      "validTo": null
    }
  ]
}
```

### `GET /api/restaurants/{restaurantKey}/menus`

Returns `MenuSummary[]` for the restaurant's `PUBLISHED` menus.

### `GET /api/menus/{menuKey}`

Returns the consumer-friendly nested view:

```json
{
  "key": "big-belly-cluj-napoca-main",
  "name": "Main Menu",
  "restaurant": { "key": "big-belly-cluj-napoca", "name": "Big Belly" },
  "sections": [
    {
      "name": "Meniuri",
      "items": [
        {
          "productKey": "big-belly-cluj-napoca-bbq-ribs-pack",
          "name": "BBQ Ribs Pack",
          "description": "Costiță de porc caramelizata...",
          "price": 57.0,
          "currency": "RON",
          "weight": "760 g",
          "available": true,
          "image": {
            "url": "/api/photos/ph-.../content",
            "thumbnailUrl": "/api/photos/ph-.../thumbnail"
          }
        }
      ]
    }
  ]
}
```

`DRAFT` and `ARCHIVED` menus return 404.

### `GET /api/products`

Query parameters: `q`, `restaurantKey`, `menuKey`, `section`, `minPrice`, `maxPrice`, `hasPhoto`, `available`, `page`, `size`, `sort`.

A product matches when **at least one** of its menu items satisfies the price/availability/has-photo/section filters. This is the consumer-friendly behavior; it avoids surprise-empty result pages.

### `GET /api/products/{productKey}`

Returns the product with restaurant reference, menu appearances, all active photos, and the primary photo's thumbnail URL.

### `GET /api/photos/{photoKey}/content` and `.../thumbnail`

Binary content. Only ACTIVE photos are served. `ARCHIVED` → 404.

## Admin REST API (auth required)

All endpoints under `/admin/api/**` are protected. The browser UI uses session-based form login. Programmatic clients use HTTP Basic with the same credentials.

### Restaurants, menus, products, menu-items

- `GET /admin/api/{resource}` — list
- `POST /admin/api/{resource}` — create
- `GET /admin/api/{resource}/{key}` — get
- `PUT /admin/api/{resource}/{key}` — full update
- `PATCH /admin/api/{resource}/{key}/status` — update status only
- For menu items: keyed by numeric `id`, not slug (since menu items are not directly addressable from external clients).

### Photos

- `POST /admin/api/photos` (multipart): `file`, `restaurantKey`, `productKey?`, `altText?`, `isPrimary?`. Photo MIME must be `image/jpeg` or `image/png`. Max 20 MB.
- `PUT /admin/api/photos/{photoKey}`: change product association, alt text, primary state, status.
- `DELETE /admin/api/photos/{photoKey}`: archive the photo (file is kept on disk).

### Menu assets

- `POST /admin/api/menus/{menuKey}/assets` (multipart): `file`. Accepted MIME: `application/pdf`, `image/jpeg`, `image/png`, `image/webp`. Max 50 MB.
- `POST /admin/api/menus/{menuKey}/assets/url` (JSON body): `{ "sourceUrl": "...", "assetType": "URL", "originalFilename": "..." , "sizeBytes": 123, "sha256": "..." }`. Stored without fetching.
- `DELETE /admin/api/menus/{menuKey}/assets/{assetKey}`: archive.

### CSV

- `GET /admin/api/csv/{resource}` — `text/csv` download.
- `POST /admin/api/csv/{resource}` (multipart `file`) — import. `?dryRun=true` to validate only.

## Error model

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "Restaurant not found: bad-r",
  "instance": "/api/restaurants/bad-r"
}
```

Validation errors (`400`) include a `errors` array with `{field, message}` per failure. Conflict (`409`) for key collisions and cross-restaurant relation violations.

## Health

- `GET /actuator/health` — open, returns `200 OK` with `{"status":"UP"}` when ready.
- `GET /actuator/info` — open.

No other actuator endpoints are exposed.
