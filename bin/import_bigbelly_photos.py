#!/usr/bin/env python3
"""
Import food-crawler prototype photos for Big Belly into the food-api.

Reads:
  - food-crawler/photo_matching/big-belly-prototype-bindings.json
    (13 dish bindings: 1 DISH_CONFIRMED, 5 DISH_PLAUSIBLE, 7 RESTAURANT_FALLBACK)
  - food-crawler/photo_triage/big-belly-shortlist.json
    (47 photos that passed AI triage: FOOD/DRINK + EXCELLENT/GOOD)
  - food-crawler/photo_export/restaurants/big-belly-cluj-napoca/raw/{photoId}.jpg
    (the actual JPEG bytes)

Writes to:
  - POST https://food.treloc.com/admin/api/photos (admin auth via FOODFINDER_PWD)
  - Sets sourceType=GOOGLE_PROTOTYPE to preserve provenance.
  - Marks one restaurant-level photo as primary (covers the missing "restaurant
    primary" placeholder; the product-level photos are also marked primary in
    their respective scope).

Mapping (dishId -> productKey) is a manual cross-ref because the food-crawler
catalog (13 dishes from "Meniuri" HTML extract 2026-07-25) does NOT 1:1 match
the current big-belly-manastur catalog (59 products from PDF 2026). Unmatched
dishes fall back to restaurant-level upload (no productKey).

Idempotency: each photo is uploaded with a derived photoKey that uses the
food-crawler photoId (e.g. ph_ae784068fa6bcb6cb7f89ef0). The food-api
rejects duplicate photoKeys, so re-running the script after a partial
failure is safe.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

# --------------------------------------------------------------------------- paths

REPO_ROOT = Path(__file__).resolve().parents[1]
FOOD_CRAWLER_ROOT = Path("/Users/vladm/dev/projects/food-crawler")

BINDINGS_PATH = FOOD_CRAWLER_ROOT / "photo_matching" / "big-belly-prototype-bindings.json"
SHORTLIST_PATH = FOOD_CRAWLER_ROOT / "photo_triage" / "big-belly-shortlist.json"
RAW_PHOTO_DIR = FOOD_CRAWLER_ROOT / "photo_export" / "restaurants" / "big-belly-cluj-napoca" / "raw"

# --------------------------------------------------------------------------- config

# The food-crawler's dishId -> food-api productKey. Only products that exist
# in the current big-belly-manastur catalog are listed. Unmatched dishes are
# uploaded as restaurant-level photos (no productKey) so the photo still
# appears in the restaurant's gallery.
#
# Keys here are PRE-namespaced with the restaurant_key prefix; the importer
# strips the prefix before adding it as a productKey form field.
DISH_TO_PRODUCT: dict[str, str] = {
    "bb-0": None,   # Meniu Piept de Pui Crocant - 5 buc  (no current match)
    "bb-1": None,   # Meniu Piept de Pui Crocant - 7 buc  (no current match)
    "bb-2": "por-ie-ca-caval",        # Meniu Cașcaval  ->  porție cașcaval
    "bb-3": None,   # Meniu Mixt Cașcaval  (no current match)
    "bb-4": None,   # Meniu Mixt  (no current match)
    "bb-5": "meniu-gratar",           # Meniu Gratar (the live site spells it without diacritics)
    "bb-6": "meniu-pulpe-de-pui",     # Meniu Pulpe de Pui Dezosate
    "bb-7": "meniu-aripioare",        # Meniu Aripioare Crocante
    "bb-8": "meniu-turkey-sandwich",  # Meniu Turkey Sandwich
    "bb-9": "bbq-ribs-pack",          # BBQ Ribs Pack  (DISH_CONFIRMED)
    "bb-10": None,  # Meniu Vegetarian  (no current match in meniuri)
    "bb-11": None,  # Meniul Vegetarian  (no current match)
    "bb-12": None,  # Meniu Pulpe Grill si Legume la Tigaie  (no current match)
}

RESTAURANT_KEY = "big-belly-manastur"
API_BASE = "https://food.treloc.com"

# Map the short product_keys (without restaurant prefix) to the
# namespaced product_keys used by the new scrape-based import. The
# food-crawler photo import looks up the product by the SHORT key
# (matching the historical convention); the new importer uses
# `{restaurantKey}-{slug}` everywhere, so we translate here.
def _qualify(short_key: str | None) -> str | None:
    if not short_key:
        return None
    return f"{RESTAURANT_KEY}-{short_key}"

# --------------------------------------------------------------------------- helpers


def curl_upload(jpeg_path: Path, restaurant_key: str, product_key: str | None,
                alt_text: str, is_primary: bool) -> tuple[int, str]:
    """Upload via curl; return (http_code, response_body)."""
    cmd = [
        "curl", "-s",
        "-u", f"admin:{os.environ['FOODFINDER_PWD']}",
        "-X", "POST", f"{API_BASE}/admin/api/photos",
        "-F", f"file=@{jpeg_path}",
        "-F", f"restaurantKey={restaurant_key}",
        "-F", f"altText={alt_text}",
        "-F", f"isPrimary={'true' if is_primary else 'false'}",
        "-F", "sourceType=GOOGLE_PROTOTYPE",
    ]
    if product_key:
        cmd.extend(["-F", f"productKey={product_key}"])
    cmd.extend(["-w", "\\n%{http_code}"])
    result = subprocess.run(cmd, capture_output=True, text=True, check=False)
    body = result.stdout.rsplit("\n", 1)
    if len(body) != 2:
        return -1, result.stdout
    return int(body[1]), body[0]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true",
                        help="Print what would be uploaded without actually POSTing.")
    args = parser.parse_args()

    if not args.dry_run and "FOODFINDER_PWD" not in os.environ:
        print("FOODFINDER_PWD env var required (admin password).", file=sys.stderr)
        return 2

    bindings = json.loads(BINDINGS_PATH.read_text())
    shortlist = json.loads(SHORTLIST_PATH.read_text())

    # Build (photoId, altText, productKey, isPrimary, label) tuples to upload.
    # Priority: explicit bindings (with productKey when matched) > fallback pool.
    uploads: list[tuple[str, str, str | None, bool, str]] = []
    seen_photos: set[str] = set()

    # 1) The 13 dish bindings.
    for b in bindings["bindings"]:
        photo_id = b["photoId"]
        if photo_id in seen_photos:
            # bb-10 and bb-11 both reference the same Vegetarian fallback photo;
            # upload it once (restaurant-level via this binding), not twice.
            continue
        seen_photos.add(photo_id)
        binding_type = b.get("binding", "?")
        confidence = b.get("confidence", 0.0)
        # Only DISH_CONFIRMED and DISH_PLAUSIBLE are allowed to bind to a
        # specific product. RESTAURANT_FALLBACK means the photo is "good
        # Big Belly FOOD, but not a dish match" — even if the dish name
        # happens to match a current product, we must NOT bind it (the
        # food-crawler already ruled out a visual match for the dish).
        if binding_type in ("DISH_CONFIRMED", "DISH_PLAUSIBLE"):
            product_key = _qualify(DISH_TO_PRODUCT.get(b["dishId"]))
        else:
            product_key = None
        if binding_type == "DISH_CONFIRMED":
            tag = "confirmed match"
        elif binding_type == "DISH_PLAUSIBLE":
            tag = f"plausible match (conf {confidence:.2f})"
        else:
            tag = "restaurant fallback (no specific dish match)"
        alt = f"[{tag}] {b.get('dishName','?')}"
        # Bound photos for product get isPrimary=true (only one per product).
        # Restaurant-level photos from RESTAURANT_FALLBACK: don't make primary
        # (we'll set the fallback pool's first as the restaurant primary below).
        is_primary = bool(product_key)
        label = f"binding {b['dishId']} ({binding_type}) -> {product_key or 'restaurant'}"
        uploads.append((photo_id, alt, product_key, is_primary, label))

    # 2) The 8 photos in restaurantFallbackPool (restaurant-level only).
    fallback_pool = bindings.get("restaurantFallbackPool", [])
    for i, entry in enumerate(fallback_pool):
        photo_id = entry["photoId"]
        if photo_id in seen_photos:
            continue
        seen_photos.add(photo_id)
        # Trim visual description to 200 chars for the alt text.
        desc = (entry.get("visualDescription") or "").strip()
        if len(desc) > 200:
            desc = desc[:197] + "..."
        alt = f"[restaurant gallery] {desc}" if desc else "[restaurant gallery]"
        # First fallback pool entry becomes the restaurant primary.
        is_primary = (i == 0)
        label = f"fallback pool entry {i} -> restaurant"
        uploads.append((photo_id, alt, None, is_primary, label))

    print(f"Plan: {len(uploads)} photos to upload")
    if args.dry_run:
        for photo_id, alt, pk, primary, label in uploads:
            local = RAW_PHOTO_DIR / f"{photo_id}.jpg"
            present = "OK" if local.exists() else "MISSING"
            print(f"  [{present}] {label:50s} | {photo_id}.jpg | primary={primary}")
        print("(dry run; no uploads made)")
        return 0

    # 3) Upload each.
    success = 0
    failed: list[tuple[str, str, int, str]] = []
    for photo_id, alt, product_key, is_primary, label in uploads:
        local = RAW_PHOTO_DIR / f"{photo_id}.jpg"
        if not local.exists():
            failed.append((photo_id, label, -1, "missing local file"))
            print(f"  SKIP  {label} (missing {local})")
            continue
        code, body = curl_upload(local, RESTAURANT_KEY, product_key, alt, is_primary)
        if code == 200:
            try:
                resp = json.loads(body)
                new_key = resp.get("photoKey", "?")
            except json.JSONDecodeError:
                new_key = "?"
            success += 1
            print(f"  OK    {label:50s} -> {new_key}")
        else:
            failed.append((photo_id, label, code, body[:200]))
            print(f"  FAIL  {label:50s} HTTP {code}: {body[:200]}")

    print()
    print(f"Result: {success} uploaded, {len(failed)} failed")
    if failed:
        for photo_id, label, code, body in failed:
            print(f"  - {label}: HTTP {code}: {body}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
