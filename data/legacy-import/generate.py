#!/usr/bin/env python3
"""
Fetch the live FoodFinder FastAPI catalog and write a set of CSVs that
match the new Spring Boot import contract.

Output (in the same directory as this script):
  - restaurants.csv
  - menus.csv
  - products.csv
  - menu_items.csv
  - photos.csv

Usage:
  FOODFINDER_API_BASE=https://api.food.treloc.com python3 generate.py

This script is a one-time legacy data export, not part of the Spring
runtime. It uses only the Python standard library.
"""
from __future__ import annotations

import csv
import json
import os
import re
import sys
import unicodedata
import urllib.request
from pathlib import Path

API_BASE = os.environ.get("FOODFINDER_API_BASE", "https://api.food.treloc.com")
OUT_DIR = Path(__file__).resolve().parent


def fetch_json(path: str) -> list:
    url = f"{API_BASE}{path}"
    with urllib.request.urlopen(url, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def slugify(name: str) -> str:
    """Lowercase, accent-stripped, dash-separated slug. ASCII only."""
    if not name:
        return "x"
    # strip diacritics
    nfkd = unicodedata.normalize("NFKD", name)
    ascii_only = "".join(c for c in nfkd if not unicodedata.combining(c))
    # lowercase, replace non-alnum with dash, collapse
    s = re.sub(r"[^a-z0-9]+", "-", ascii_only.lower()).strip("-")
    return s or "x"


def write_csv(name: str, headers: list[str], rows: list[list]) -> None:
    path = OUT_DIR / name
    with open(path, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, quoting=csv.QUOTE_MINIMAL, lineterminator="\n")
        w.writerow(headers)
        for r in rows:
            w.writerow(r)
    print(f"  wrote {path.name} ({len(rows)} rows)")


def main() -> int:
    print(f"Fetching from {API_BASE} ...")
    restaurants = fetch_json("/api/restaurants")
    dishes = fetch_json("/api/dishes?limit=1000")

    # restaurants.csv
    rest_rows = []
    for r in restaurants:
        loc = r.get("location") or {}
        coords = (loc.get("coordinates") or {})
        rest_rows.append([
            r["key"],
            r.get("name") or "",
            "",  # website_url not in FastAPI
            loc.get("address") or "",
            "Cluj-Napoca",  # all current records are in CJ
            coords.get("latitude") or "",
            coords.get("longitude") or "",
            "ACTIVE",
        ])
    write_csv("restaurants.csv",
              ["restaurant_key", "name", "website_url", "address_line",
               "city", "latitude", "longitude", "status"],
              rest_rows)

    # one menu per restaurant
    menu_rows = []
    for r in restaurants:
        menu_rows.append([
            f"{r['key']}-main",
            r["key"],
            "Main Menu",
            "PERMANENT",
            "PUBLISHED",
            "", "", "",  # source_url, valid_from, valid_to
        ])
    write_csv("menus.csv",
              ["menu_key", "restaurant_key", "name", "menu_type",
               "status", "source_url", "valid_from", "valid_to"],
              menu_rows)

    # products + menu_items
    product_rows = []
    item_rows = []
    photo_rows = []
    seen_keys: dict[str, int] = {}  # for slug collisions

    for d in dishes:
        rest = d.get("restaurant") or {}
        rk = rest.get("key") or ""
        if not rk:
            continue
        base = f"{rk}-{slugify(d.get('name') or 'unnamed')}"
        product_key = base
        n = seen_keys.get(base, 0)
        if n > 0:
            product_key = f"{base}-{n + 1}"
        seen_keys[base] = n + 1

        product_rows.append([
            product_key,
            rk,
            d.get("name") or "",
            d.get("description") or "",
            d.get("weight") or "",
            "ACTIVE",
        ])

        price = d.get("price")
        item_rows.append([
            f"{rk}-main",
            product_key,
            d.get("section") or "Altele",
            "" if price is None else price,
            d.get("currency") or "RON",
            "true",
            "0",
            "",
        ])

        # photo metadata
        img = d.get("image") or {}
        if img.get("url"):
            photo_key = "ph-" + slugify(product_key)
            # ensure slug regex (lowercase alnum + dashes)
            photo_key = re.sub(r"[^a-z0-9-]+", "-", photo_key)[:160].strip("-")
            if not photo_key:
                continue
            is_primary = "false"
            if img.get("isDishSpecific"):
                is_primary = "true"
            photo_rows.append([
                photo_key,
                rk,
                product_key,
                "GOOGLE_PROTOTYPE",
                img["url"],
                "",  # alt_text
                is_primary,
                "ACTIVE",
            ])

    write_csv("products.csv",
              ["product_key", "restaurant_key", "name", "description",
               "weight_text", "status"],
              product_rows)

    write_csv("menu_items.csv",
              ["menu_key", "product_key", "section_name", "price",
               "currency", "available", "sort_order", "source_url"],
              item_rows)

    write_csv("photos.csv",
              ["photo_key", "restaurant_key", "product_key", "source_type",
               "external_url", "alt_text", "is_primary", "status"],
              photo_rows)

    print()
    print("Done. Import via:")
    print("  POST /admin/api/csv/restaurants?dryRun=true")
    print("  POST /admin/api/csv/menus?dryRun=true")
    print("  POST /admin/api/csv/products?dryRun=true")
    print("  POST /admin/api/csv/menu-items?dryRun=true")
    print("  POST /admin/api/csv/photos?dryRun=true")
    return 0


if __name__ == "__main__":
    sys.exit(main())
