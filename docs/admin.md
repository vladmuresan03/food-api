# Admin UI

The admin is a Thymeleaf server-rendered application mounted at `/admin`. It is intentionally minimal: HTML, a single static stylesheet (inline in the layout), and a handful of forms. No JavaScript framework, no SPA, no modals.

## Sign in

- `/admin/login` — form login. Default credentials are read from `FOODFINDER_ADMIN_USERNAME` and `FOODFINDER_ADMIN_PASSWORD`. The session is held in a cookie scoped to `/admin/**`.
- `/admin/logout` — POST, terminates the session.

## Pages

| URL                       | What it does                                                          |
|---------------------------|-----------------------------------------------------------------------|
| `/admin`                  | Overview.                                                             |
| `/admin/restaurants`      | Restaurants table. Filter by `q`, `city`, `status`.                  |
| `/admin/menus`            | Menus table. Filter by `q`, `restaurantKey`, `status`.                |
| `/admin/products`         | Products table. Filter by `q`, `restaurantKey`, `status`. Thumbnail column. |
| `/admin/menu-items`       | Menu items table. Filter by `menuKey`, `productKey`, `section`.        |
| `/admin/photos`           | Photos table with upload form. Filter by `restaurantKey`, `productKey`, `status`. |
| `/admin/menu-assets`      | Menu assets table with upload form and URL registration form.        |
| `/admin/csv`              | CSV import/export table.                                              |

## Filters

Filters are simple query parameters on the GET endpoint. The page renders a form with `method="get"`, the values pre-fill from the query string, and the buttons resubmit the same URL. Pagination is a future addition; v1 lists everything in a single page.

## Status pills

The `restaurant.status`, `menu.status`, `product.status`, and `photo.status` columns render as colored pills. CSS classes are `.pill-draft`, `.pill-active`, `.pill-published`, `.pill-archived` and can be customized in `templates/admin/_layout.html`.

## CSV import workflow

1. Open `/admin/csv`.
2. Pick a resource row.
3. Choose the file. The `dry-run` checkbox is on by default.
4. Click **Upload**. The page re-renders with a green OK panel or a red errors table.
5. If errors, fix the CSV in your editor. Drop the `dry-run` checkbox to write the import.

## Editing

The v1 admin supports CSV-driven bulk editing plus the following per-row actions:

- **Menu items:** `Delete` button on the row submits `DELETE /admin/api/menu-items/{id}`.
- **Photos:** `Archive` button on the row submits `DELETE /admin/api/photos/{photoKey}`.
- Other resources are edited via the API or by re-importing CSV. Inline forms are a planned v1.1 addition.

## Security

The admin is gated by `SecurityConfig` (form login + Basic auth, single in-memory user from env vars). CSRF is enabled for browser forms and disabled for the `/admin/api/**` REST surface (which is intended for stateless clients). The `/api/**` public read surface is open.

CORS is configured from the `FOODFINDER_ALLOWED_ORIGINS` environment variable (comma-separated exact origins). No wildcards.
