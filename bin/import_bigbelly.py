#!/usr/bin/env python3
"""
Import the Big Belly Cluj menu from the foodfinder-poc extraction
into the food-api catalog.

Pipeline (mirror the SOURCE -> EXTRACTION -> REVIEW -> CANONICAL
in foodfinder-poc, with this script playing the role of the
"publish" step):

  1. Read the v3 extraction (the structured, review-cleared JSON)
     at /Users/vladm/dev/projects/foodfinder-poc/bigbelly_extraction_v3.json.
  2. Map each item to a product (name + description + weight_text
     + weight_grams parsed from "500 g") and a menu_item (price +
     currency RON + section).
  3. Emit 4 CSVs in the shape the food-api CSV importer expects.
  4. POST each CSV to /admin/api/csv/{slug} on the running
     food-api instance (dry-run first, then real).
  5. Use the bundle endpoint (a single zip) to import in dependency
     order: restaurants -> menus -> products -> menu-items.

Idempotent: the food-api CSVs are upserts keyed on *_key, so
re-running does not duplicate rows. To start fresh, run
`bin/import_bigbelly.py --wipe` first (deletes the restaurant
via DELETE on the admin API, which cascades via V8).

Usage:
  bin/import_bigbelly.py                  # dry run + import to prod
  bin/import_bigbelly.py --no-dry-run     # skip dry run
  bin/import_bigbelly.py --wipe           # hard-delete first
  bin/import_bigbelly.py --base https://food.treloc.com
  bin/import_bigbelly.py --user admin --password ...
"""
from __future__ import annotations

import argparse
import csv
import io
import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path
from typing import Any, Iterable

# ---------------------------------------------------------------- constants

# Defaults; override via CLI flags.
DEFAULT_BASE = "https://food.treloc.com"
DEFAULT_USER = "admin"
DEFAULT_PASSWORD = "LBY3+UK3JTv5jwvfur7QRuLj"

# Source of truth: the v3 extraction (post-review) in foodfinder-poc.
DEFAULT_EXTRACTION = (
    Path("/Users/vladm/dev/projects/foodfinder-poc/bigbelly_extraction_v3.json")
)

# Single restaurant in the catalog. We pick the Manastur location
# as the primary address (the menu HTML lists it first). The
# extraction doesn't carry address/website; we hardcode from the
# public site.
RESTAURANT = {
    "restaurant_key": "big-belly-cluj",
    "name": "Big Belly",
    "website_url": "https://www.bigbelly-cluj.ro",
    "address_line": "Calea Manastur nr. 68 (langa Kaufland)",
    "city": "Cluj-Napoca",
    "latitude": "46.7755",
    "longitude": "23.5920",  # approx; falls back to null on parse failure
    "status": "ACTIVE",
}

# One menu for everything; section name is the source-of-truth
# ("Meniuri") which is what the restaurant's own website uses.
MENU = {
    "menu_key": "meniuri",
    "restaurant_key": RESTAURANT["restaurant_key"],
    "name": "Meniuri",
    "menu_type": "PERMANENT",
    "status": "PUBLISHED",
    "source_url": "https://www.bigbelly-cluj.ro/meniuri",
}

# Parse "500 g" / "650 gr" / "1 kg" -> int grams. The extraction
# stores weight as a free-text string; this is the only parsing
# needed for the weight_grams column.
WEIGHT_RE = re.compile(r"(\d+(?:[.,]\d+)?)\s*(kg|g|gr|ml|l)?\b", re.IGNORECASE)


def parse_weight_grams(raw: str | None) -> str:
    """Return a string for the weight_grams column, or '' for unknown.
    Accepts formats like "500 g", "1.2 kg", "650 gr"."""
    if not raw:
        return ""
    m = WEIGHT_RE.search(raw)
    if not m:
        return ""
    value = float(m.group(1).replace(",", "."))
    unit = (m.group(2) or "g").lower()
    if unit in ("kg",):
        return str(int(value * 1000))
    if unit in ("l",):
        # not weight, but if extraction has it mislabeled, accept as ml
        return str(int(value * 1000))
    return str(int(value))


def slugify(s: str) -> str:
    """Lowercase, replace non-slug chars with '-', collapse repeats."""
    s = s.lower()
    s = re.sub(r"[^a-z0-9]+", "-", s)
    s = re.sub(r"-+", "-", s).strip("-")
    return s or "item"


def load_items(extraction_path: Path) -> list[dict[str, Any]]:
    """Flatten the v3 extraction into a list of (section, item) tuples
    ready for product / menu_item mapping."""
    payload = json.loads(extraction_path.read_text(encoding="utf-8"))
    out: list[dict[str, Any]] = []
    for menu in payload.get("menus", []):
        for section in menu.get("sections", []):
            sec_name = (section.get("name") or section.get("rawName") or "").strip()
            for item in section.get("items", []):
                out.append({
                    "section": sec_name,
                    "item": item,
                })
    return out


def build_product_key(item: dict[str, Any], used: set[str]) -> str:
    """Build a unique product_key (slug) from the cleaned name.
    The extraction cleans duplicate-ish names already, but we still
    dedupe at the product_key level in case of near-duplicates."""
    name = (item.get("name") or item.get("rawName") or "").strip()
    base = slugify(name)
    key = base
    i = 2
    while key in used:
        key = f"{base}-{i}"
        i += 1
    used.add(key)
    return key


# ---------------------------------------------------------------- CSV writers

def write_csv(path: Path, headers: list[str], rows: Iterable[dict[str, Any]]) -> None:
    with path.open("w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=headers, quoting=csv.QUOTE_MINIMAL)
        w.writeheader()
        for row in rows:
            w.writerow({h: row.get(h, "") for h in headers})


def build_csvs(items: list[dict[str, Any]], out_dir: Path) -> dict[str, Path]:
    used_keys: set[str] = set()
    products: list[dict[str, Any]] = []
    menu_items: list[dict[str, Any]] = []

    for entry in items:
        item = entry["item"]
        section = entry["section"] or "Meniuri"
        product_key = build_product_key(item, used_keys)
        products.append({
            "product_key": product_key,
            "restaurant_key": RESTAURANT["restaurant_key"],
            "name": item.get("name") or item.get("rawName") or "",
            "description": (item.get("description") or "").strip(),
            "weight_text": item.get("weight") or "",
            "weight_grams": parse_weight_grams(item.get("weight")),
            "category": "Meniuri",
            "tags": "meniuri",
            "status": "ACTIVE",
        })
        menu_items.append({
            "menu_key": MENU["menu_key"],
            "product_key": product_key,
            "section_name": section,
            "price": str(item.get("price") or ""),
            "currency": item.get("currency") or "RON",
            "available": "true",
            "sort_order": str(len(menu_items) * 10),
            "spice_level": "",
        })

    paths: dict[str, Path] = {}
    paths["restaurants"] = out_dir / "restaurants.csv"
    paths["menus"] = out_dir / "menus.csv"
    paths["products"] = out_dir / "products.csv"
    paths["menu-items"] = out_dir / "menu-items.csv"

    write_csv(paths["restaurants"],
              ["restaurant_key", "name", "website_url", "address_line",
               "city", "latitude", "longitude", "status"],
              [RESTAURANT])
    write_csv(paths["menus"],
              ["menu_key", "restaurant_key", "name", "menu_type",
               "status", "source_url", "valid_from", "valid_to"],
              [{**MENU, "valid_from": "", "valid_to": ""}])
    write_csv(paths["products"],
              ["product_key", "restaurant_key", "name", "description",
               "weight_text", "weight_grams", "category", "tags", "status"],
              products)
    write_csv(paths["menu-items"],
              ["menu_key", "product_key", "section_name", "price",
               "currency", "available", "sort_order", "spice_level",
               "source_url"],
              [{**mi, "source_url": ""} for mi in menu_items])
    return paths


# ---------------------------------------------------------------- HTTP

def post_csv(base: str, slug: str, path: Path, user: str, password: str,
             dry_run: bool) -> dict[str, Any]:
    """POST a single CSV to /admin/api/csv/{slug}."""
    boundary = "----ImportBoundary"
    body = b""
    with path.open("rb") as f:
        file_bytes = f.read()
    body += f"--{boundary}\r\n".encode()
    body += (f'Content-Disposition: form-data; name="file"; '
             f'filename="{path.name}"\r\n').encode()
    body += b"Content-Type: text/csv\r\n\r\n"
    body += file_bytes
    body += f"\r\n--{boundary}--\r\n".encode()
    qs = urllib.parse.urlencode({
        "dryRun": str(dry_run).lower(),
        "actor": "import_bigbelly.py",
    })
    url = f"{base}/admin/api/csv/{slug}?{qs}"
    req = urllib.request.Request(url, data=body, method="POST")
    req.add_header("Content-Type",
                   f"multipart/form-data; boundary={boundary}")
    req.add_header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X)")
    import base64
    token = base64.b64encode(f"{user}:{password}".encode()).decode()
    req.add_header("Authorization", f"Basic {token}")
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        body_text = e.read().decode("utf-8", errors="replace")
        return {"ok": False, "status": e.code, "error": body_text}


# ---------------------------------------------------------------- main

def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--base", default=DEFAULT_BASE)
    p.add_argument("--user", default=DEFAULT_USER)
    p.add_argument("--password", default=DEFAULT_PASSWORD)
    p.add_argument("--extraction", type=Path, default=DEFAULT_EXTRACTION)
    p.add_argument("--no-dry-run", action="store_true",
                   help="Skip the dry run and post the real import.")
    p.add_argument("--wipe", action="store_true",
                   help="Hard-delete the restaurant first (uses DELETE /admin/api/restaurants/{key}, requires status=ARCHIVED).")
    p.add_argument("--out-dir", type=Path, default=None,
                   help="Where to write the CSVs. Defaults to a tempdir.")
    args = p.parse_args()

    if not args.extraction.exists():
        print(f"ERROR: extraction file not found: {args.extraction}", file=sys.stderr)
        return 1

    print(f"== loading {args.extraction.name} ==")
    items = load_items(args.extraction)
    print(f"   {len(items)} items across all menus/sections")

    out_dir = args.out_dir or Path(tempfile.mkdtemp(prefix="bigbelly-"))
    out_dir.mkdir(parents=True, exist_ok=True)
    paths = build_csvs(items, out_dir)
    for slug, p_ in paths.items():
        line_count = sum(1 for _ in p_.open(encoding="utf-8")) - 1
        print(f"   {slug}.csv: {line_count} rows -> {p_}")

    if args.wipe:
        # 2-step guard: archive first, then DELETE.
        # We POST the form-style archive endpoint via the REST PATCH
        # (no CSRF needed), then DELETE.
        print("== wiping existing big-belly-cluj (archive + delete) ==")
        patch_url = f"{args.base}/admin/api/restaurants/{RESTAURANT['restaurant_key']}/status"
        import base64, urllib.request
        token = base64.b64encode(f"{args.user}:{args.password}".encode()).decode()
        patch_req = urllib.request.Request(
            patch_url, method="PATCH",
            data=json.dumps({"status": "ARCHIVED"}).encode(),
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Basic {token}",
                "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X)",
            })
        try:
            with urllib.request.urlopen(patch_req, timeout=15) as r:
                print(f"   archived: HTTP {r.status}")
        except urllib.error.HTTPError as e:
            if e.code != 404:
                print(f"   archive failed: HTTP {e.code}")
        del_url = f"{args.base}/admin/api/restaurants/{RESTAURANT['restaurant_key']}"
        del_req = urllib.request.Request(del_url, method="DELETE",
                                        headers={"Authorization": f"Basic {token}",
                                                 "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X)"})
        try:
            with urllib.request.urlopen(del_req, timeout=15) as r:
                print(f"   hard-delete: HTTP {r.status}")
        except urllib.error.HTTPError as e:
            if e.code == 404:
                print("   nothing to delete")
            else:
                print(f"   hard-delete failed: HTTP {e.code}")

    if not args.no_dry_run:
        print("== dry run ==")
        for slug, p_ in paths.items():
            r = post_csv(args.base, slug, p_, args.user, args.password, dry_run=True)
            if r.get("ok"):
                print(f"   {slug}: total={r.get('totalRows')} "
                      f"errors={r.get('errorCount')}")
                if r.get("errorCount", 0) > 0:
                    for e in r.get("errors", [])[:5]:
                        print(f"      {e.get('row')}:{e.get('field')} {e.get('code')}: {e.get('message')}")
            else:
                print(f"   {slug}: FAILED {r.get('status')}: {r.get('error')}")
                return 1

    print("== real import ==")
    for slug, p_ in paths.items():
        r = post_csv(args.base, slug, p_, args.user, args.password, dry_run=False)
        if r.get("ok"):
            print(f"   {slug}: inserted={r.get('inserted')} "
                  f"updated={r.get('updated')} errors={r.get('errorCount')}")
        else:
            print(f"   {slug}: FAILED {r.get('status')}: {r.get('error')}")
            return 1
    print(f"== done. CSVs are at: {out_dir} ==")
    return 0


if __name__ == "__main__":
    sys.exit(main())
