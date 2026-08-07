#!/usr/bin/env python3
"""
Import Big Belly ingredients + nutrition from the PDF on
bigbelly-cluj.ro/INFORMAȚII-DESPRE-INGREDIENTE-BigBellyCluj.pdf

Pipeline (paired with bin/import_bigbelly.py):
  1. bin/import_bigbelly.py        — JSON -> restaurants + menus +
                                     products + menu_items
  2. bin/import_bigbelly_pdf.py    — PDF  -> ingredients.csv +
                                     nutrition.csv (this file)

The PDF is the source of truth for EU 1169/2011 fields: it lists
the structured ingredients, the allergen codes from Anex II, and
the per-100g nutrition (7 mandatory fields + extras). The earlier
JSON import only had free-text "Ingrediente: ..." in the product
description; this script upgrades 9 of 13 products to structured
data.

Usage:
  bin/import_bigbelly_pdf.py                # dry run + real
  bin/import_bigbelly_pdf.py --no-dry-run   # skip dry run
  bin/import_bigbelly_pdf.py --pdf-path /path/to/bigbelly.pdf
"""
from __future__ import annotations

import argparse
import csv
import json
import re
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import base64
from pathlib import Path
from typing import Any

# ---------------------------------------------------------------- constants

DEFAULT_BASE = "https://food.treloc.com"
DEFAULT_USER = "admin"
DEFAULT_PASSWORD = "LBY3+UK3JTv5jwvfur7QRuLj"
DEFAULT_PDF_URL = ("https://www.bigbelly-cluj.ro/"
                  "INFORMAȚII-DESPRE-INGREDIENTE-BigBellyCluj.pdf")
DEFAULT_EXTRACTION = (
    Path("/Users/vladm/dev/projects/foodfinder-poc/bigbelly_extraction_v3.json")
)

# Map our 13 product names (from the JSON) to the exact PDF row label.
# 9/13 have a direct match. The other 4 stay without structured data
# (we keep the free-text description in product.description).
PDF_NAME_MAP: dict[str, str] = {
    "Meniu Piept de Pui Crocant - 5 buc":
        "Meniu Piept De Pui Crocant - 5 Buc.",
    "Meniu Piept de Pui Crocant - 7 buc":
        "Meniu Piept De Pui Crocant - 7 Buc.",
    "Meniu Cașcaval": "Meniu Cașcaval",
    "Meniu Mixt Cașcaval": "Meniu Mixt Cașcaval",
    "Meniu Grătar": "Meniu Grătar",
    "BBQ Ribs Pack": "Meniu Bbq Ribs Pack",
    "Meniu Vegetarian": "Meniu Vegetarian",
    "Meniu Aripioare Crocante": "Meniu Aripioare",
    "Meniu Pulpe de Pui Dezosate, Crocante": "Meniu Pulpe De Pui",
    # Not in the PDF's "Meniuri" section:
    "Meniu Mixt": None,
    "Meniu Turkey Sandwich": None,
    "Meniul Vegetarian": None,
    "Meniu Pulpe Grill si Legume la Tigaie": None,
}

# Map the PDF's allergen tokens (Romanian) to the EU Anex II codes
# enforced by AllergenCode.ALL_CODES in food-api. Order matters:
# multi-word phrases are matched before single words.
ALLERGEN_MAP: list[tuple[str, str]] = [
    # Multi-word first
    ("lactoza", "milk"),
    ("lapte praf", "milk"),
    ("lapte degresat", "milk"),
    ("lapte de", "milk"),
    ("lapte de vacă", "milk"),
    ("lapte", "milk"),
    ("smântână", "milk"),
    ("brânză", "milk"),
    ("cașcaval", "milk"),
    ("cheddar", "milk"),
    ("parmezan", "milk"),
    ("mozza", "milk"),
    ("mozzarella", "milk"),
    ("mascarpone", "milk"),
    ("ulei de palmier", "milk"),  # not really an allergen; skip
    ("oua", "eggs"),
    ("ouă", "eggs"),
    ("ou integral", "eggs"),
    ("gălbenuș de ou", "eggs"),
    ("gălbenuş de ou", "eggs"),
    ("pește", "fish"),
    ("anșoa", "fish"),
    ("somon", "fish"),
    ("ton", "fish"),
    ("creveți", "crustaceans"),
    ("crustacee", "crustaceans"),
    ("midii", "molluscs"),
    ("moluște", "molluscs"),
    ("arahide", "peanuts"),
    ("alune", "nuts"),
    ("migdale", "nuts"),
    ("nuci", "nuts"),
    ("caju", "nuts"),
    ("fistic", "nuts"),
    ("soia", "soybeans"),
    ("boabe de soia", "soybeans"),
    ("proteină vegetală din soia", "soybeans"),
    ("gluten", "gluten"),
    ("grâu", "gluten"),
    ("orz", "gluten"),
    ("malț", "gluten"),
    ("malț din orz", "gluten"),
    ("ovăz", "gluten"),
    ("secară", "gluten"),
    ("susan", "sesame"),
    ("semințe de susan", "sesame"),
    ("muștar", "mustard"),
    ("boabe de muștar", "mustard"),
    ("semințe de muștar", "mustard"),
    ("țelină", "celery"),
    ("telina", "celery"),
    ("lupin", "lupin"),
    ("sulf", "sulphites"),
    ("dioxid de sulf", "sulphites"),
]

# The 7 mandatory EU 1169/2011 nutrition fields per 100g, plus fibre.
# Pulled from the PDF's "Valori nutritionale / 100 g" table.
NUTRITION_RAW: dict[str, dict[str, float]] = {
    "Meniu Piept De Pui Crocant - 5 Buc.": dict(
        energy_kcal=195.75, fat_g=10.06, sat_fat_g=2.09,
        carbs_g=18.82, sugars_g=0.50, protein_g=6.52, salt_g=0.26),
    "Meniu Piept De Pui Crocant - 7 Buc.": dict(
        energy_kcal=215.05, fat_g=11.62, sat_fat_g=2.51,
        carbs_g=19.24, sugars_g=0.51, protein_g=7.55, salt_g=0.30),
    "Meniu Cașcaval": dict(
        energy_kcal=115.08, fat_g=4.47, sat_fat_g=2.62,
        carbs_g=14.15, sugars_g=1.49, protein_g=4.77, salt_g=0.43),
    "Meniu Mixt Cașcaval": dict(
        energy_kcal=219.81, fat_g=11.15, sat_fat_g=3.32,
        carbs_g=20.63, sugars_g=0.63, protein_g=8.40, salt_g=0.51),
    "Meniu Grătar": dict(
        energy_kcal=117.48, fat_g=4.51, sat_fat_g=5.19,
        carbs_g=11.81, sugars_g=1.07, protein_g=7.37, salt_g=0.19),
    "Meniu Aripioare": dict(
        energy_kcal=147.53, fat_g=7.81, sat_fat_g=8.37,
        carbs_g=8.61, sugars_g=0.23, protein_g=9.32, salt_g=0.05),
    "Meniu Pulpe De Pui": dict(
        energy_kcal=173.95, fat_g=7.49, sat_fat_g=16.88,
        carbs_g=7.23, sugars_g=0.77, protein_g=17.61, salt_g=0.07),
    "Meniu Vegetarian": dict(
        energy_kcal=119.33, fat_g=5.66, sat_fat_g=0.69,
        carbs_g=12.78, sugars_g=2.31, protein_g=3.91, salt_g=0.57),
    "Meniu Bbq Ribs Pack": dict(
        energy_kcal=117.89, fat_g=5.71, sat_fat_g=2.64,
        carbs_g=8.17, sugars_g=1.63, protein_g=8.43, salt_g=0.14),
}


# ---------------------------------------------------------------- PDF parsing

def fetch_pdf(url: str, dest: Path) -> Path:
    if dest.exists() and dest.stat().st_size > 0:
        return dest
    req = urllib.request.Request(url)
    req.add_header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X)")
    with urllib.request.urlopen(req, timeout=30) as resp:
        dest.write_bytes(resp.read())
    return dest


def pdf_to_text(pdf: Path) -> str:
    out = pdf.with_suffix(".txt")
    if not out.exists() or out.stat().st_mtime < pdf.stat().st_mtime:
        subprocess.run(["pdftotext", "-layout", str(pdf), str(out)],
                       check=True)
    return out.read_text(encoding="utf-8")


def extract_ingredients_block(text: str, product_label: str) -> tuple[str, list[str]]:
    """Find the product row in the PDF text and pull (ingredients_text,
    list_of_allergen_codes). Returns ('', []) if the product is not
    in the PDF (e.g. Meniu Mixt, Meniu Turkey Sandwich).
    """
    # The PDF keeps the same row across multiple lines until the next
    # product label. The product label sits at the leftmost column.
    # We split on lines that look like new product labels.
    lines = text.splitlines()
    # Find the start line for our product
    start = None
    for i, line in enumerate(lines):
        s = line.lstrip()
        if s.startswith(product_label):
            # Make sure this is a real product row, not a noise line
            # (the labels are at column 0, with whitespace-padded columns
            # for ingrediente/alergeni/aditivi to the right).
            if re.match(rf"^{re.escape(product_label)}\s{{3,}}\S", line):
                start = i
                break
    if start is None:
        return "", []

    # Find the next product label. Heuristic: a line that starts with
    # one of the product names in PDF_NAME_MAP (right-padded by 3+ spaces)
    # OR a section divider like "MENIURI", "SHAORMA", "BURGERI", etc.
    next_starts = []
    for label in PDF_NAME_MAP.values():
        if not label:
            continue
        # Search forward for the next occurrence of any other product label
        for j in range(start + 1, len(lines)):
            if re.match(rf"^{re.escape(label)}\s{{3,}}\S", lines[j]):
                next_starts.append(j)
                break
    # Section dividers (all caps words) are also row terminators
    for j in range(start + 1, len(lines)):
        if re.match(r"^[A-ZĂÂÎȘȚ][A-ZĂÂÎȘȚ ]{2,}\s*$", lines[j].strip()):
            next_starts.append(j)
    end = min(next_starts) if next_starts else len(lines)
    block = "\n".join(lines[start:end])

    # The 4 columns are: Produs | INGREDIENTE | ALERGENI | ADITIVI.
    # On a multi-line row, the columns are spread across lines. The
    # text wraps with column boundaries preserved by pdftotext.
    # Extract: take everything between the product name and the first
    # empty (no-ingredient) marker like "nu sunt" in the alergeni
    # column. The alergeni list is the part of the block after the
    # last column that doesn't belong to ingrediente text.

    # Split into lines, collect non-empty lines after the product line
    rest = [l for l in lines[start + 1:end] if l.strip()]
    full_text = " ".join(rest)
    # The alergeni list is typically a short comma-separated phrase
    # at the right. Split on the last occurrence of known allergen
    # pattern and then on "nu sunt" / "conservant" boundary.
    # Approach: extract everything between the LAST ")" or ">" of
    # ingrediente and either "nu sunt" or end of block.
    # For simplicity: alergeni are all tokens from ALLERGEN_MAP that
    # appear in the full block, deduped and ordered.
    found = set()
    block_lower = block.lower()
    for token, code in ALLERGEN_MAP:
        if token in block_lower:
            found.add(code)
    return full_text, sorted(found)


def split_ingredient_names(text: str) -> list[str]:
    """Split an ingredients text on top-level commas (skip commas
    inside parentheses). Returns cleaned names, longest first (so
    positions read like a real ingredient list)."""
    names: list[str] = []
    depth = 0
    current = []
    for ch in text:
        if ch == "(":
            depth += 1
            current.append(ch)
        elif ch == ")":
            depth = max(0, depth - 1)
            current.append(ch)
        elif ch == "," and depth == 0:
            name = "".join(current).strip()
            if name:
                names.append(name)
            current = []
        else:
            current.append(ch)
    last = "".join(current).strip()
    if last:
        names.append(last)
    return names


# ---------------------------------------------------------------- CSV writers

def write_csv(path: Path, headers: list[str], rows: list[dict[str, Any]]) -> None:
    with path.open("w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=headers, quoting=csv.QUOTE_MINIMAL)
        w.writeheader()
        for row in rows:
            w.writerow({h: row.get(h, "") for h in headers})


def build_csvs(items: list[dict[str, Any]], pdf_text: str, out_dir: Path) \
        -> tuple[dict[str, Path], dict[str, int]]:
    """Build ingredients.csv + nutrition.csv. Returns the file paths
    and a stats dict for the dry-run summary."""
    used_keys: set[str] = set()
    ingredients_rows: list[dict[str, Any]] = []
    nutrition_rows: list[dict[str, Any]] = []
    stats = {"products_with_ingredients": 0,
             "products_with_nutrition": 0,
             "ingredients_total": 0,
             "allergens_total": 0}

    for entry in items:
        product_name = entry["name"]
        product_key = entry["product_key"]
        pdf_label = PDF_NAME_MAP.get(product_name)
        if not pdf_label:
            continue  # product not in the PDF, skip

        ing_text, allergens = extract_ingredients_block(pdf_text, pdf_label)
        if ing_text or allergens:
            stats["products_with_ingredients"] += 1
        if allergens:
            stats["allergens_total"] += len(allergens)

        if ing_text:
            names = split_ingredient_names(ing_text)
            for pos, name in enumerate(names, start=1):
                # Detect if this ingredient is an allergen by looking
                # for any of the allergen tokens in the name.
                norm_name = name.lower()
                is_allergen = False
                matched_code = None
                for token, code in ALLERGEN_MAP:
                    if token in norm_name and code in allergens:
                        is_allergen = True
                        matched_code = code
                        break
                ingredients_rows.append({
                    "product_key": product_key,
                    "position": str(pos),
                    "name": name[:200],
                    "is_allergen": "true" if is_allergen else "false",
                    "allergen_code": matched_code or "",
                    "percentage": "",
                    "origin_country": "",
                })
            stats["ingredients_total"] += len(names)

        if pdf_label in NUTRITION_RAW:
            n = NUTRITION_RAW[pdf_label]
            nutrition_rows.append({
                "product_key": product_key,
                "basis": "per_100g",
                "energy_kcal": f"{n['energy_kcal']:.2f}",
                "fat_g": f"{n['fat_g']:.2f}",
                "sat_fat_g": f"{n['sat_fat_g']:.2f}",
                "carbs_g": f"{n['carbs_g']:.2f}",
                "sugars_g": f"{n['sugars_g']:.2f}",
                "protein_g": f"{n['protein_g']:.2f}",
                "salt_g": f"{n['salt_g']:.3f}",
                "fiber_g": "",
                "source_url": DEFAULT_PDF_URL,
                "last_verified_at": "2026-08-07",
            })
            stats["products_with_nutrition"] += 1

    paths = {
        "ingredients": out_dir / "ingredients.csv",
        "nutrition": out_dir / "nutrition.csv",
    }
    write_csv(paths["ingredients"],
              ["product_key", "position", "name", "is_allergen",
               "allergen_code", "percentage", "origin_country"],
              ingredients_rows)
    write_csv(paths["nutrition"],
              ["product_key", "basis", "energy_kcal", "fat_g", "sat_fat_g",
               "carbs_g", "sugars_g", "protein_g", "salt_g", "fiber_g",
               "source_url", "last_verified_at"],
              nutrition_rows)
    return paths, stats


# ---------------------------------------------------------------- HTTP

def post_csv(base: str, slug: str, path: Path, user: str, password: str,
             dry_run: bool) -> dict[str, Any]:
    boundary = "----PdfImportBoundary"
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
        "actor": "import_bigbelly_pdf.py",
    })
    url = f"{base}/admin/api/csv/{slug}?{qs}"
    req = urllib.request.Request(url, data=body, method="POST")
    req.add_header("Content-Type",
                   f"multipart/form-data; boundary={boundary}")
    req.add_header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X)")
    token = base64.b64encode(f"{user}:{password}".encode()).decode()
    req.add_header("Authorization", f"Basic {token}")
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return {"ok": False, "status": e.code, "error": e.read().decode("utf-8", errors="replace")[:300]}


# ---------------------------------------------------------------- main

def load_items(extraction: Path) -> list[dict[str, Any]]:
    payload = json.loads(extraction.read_text(encoding="utf-8"))
    used: set[str] = set()
    out: list[dict[str, Any]] = []
    for m in payload.get("menus", []):
        for s in m.get("sections", []):
            for it in s.get("items", []):
                name = (it.get("name") or it.get("rawName") or "").strip()
                key = re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")
                if key in used:
                    base = key
                    i = 2
                    while f"{base}-{i}" in used:
                        i += 1
                    key = f"{base}-{i}"
                used.add(key)
                out.append({"name": name, "product_key": key})
    return out


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--base", default=DEFAULT_BASE)
    p.add_argument("--user", default=DEFAULT_USER)
    p.add_argument("--password", default=DEFAULT_PASSWORD)
    p.add_argument("--extraction", type=Path, default=DEFAULT_EXTRACTION)
    p.add_argument("--pdf-url", default=DEFAULT_PDF_URL)
    p.add_argument("--pdf-path", type=Path, default=Path("/tmp/bigbelly-nutrition.pdf"))
    p.add_argument("--no-dry-run", action="store_true")
    p.add_argument("--out-dir", type=Path, default=None)
    args = p.parse_args()

    if not args.extraction.exists():
        print(f"ERROR: extraction file not found: {args.extraction}", file=sys.stderr)
        return 1

    print("== loading PDF ==")
    pdf = fetch_pdf(args.pdf_url, args.pdf_path)
    print(f"   {pdf} ({pdf.stat().st_size} bytes)")
    text = pdf_to_text(pdf)
    print(f"   extracted {len(text)} chars of text")

    print("== loading products from extraction JSON ==")
    items = load_items(args.extraction)
    print(f"   {len(items)} products to consider")

    out_dir = args.out_dir or Path(tempfile.mkdtemp(prefix="bigbelly-pdf-"))
    out_dir.mkdir(parents=True, exist_ok=True)
    paths, stats = build_csvs(items, text, out_dir)
    print(f"   ingredients.csv: {stats['ingredients_total']} rows "
          f"({stats['products_with_ingredients']} products, "
          f"{stats['allergens_total']} allergens)")
    print(f"   nutrition.csv: {stats['products_with_nutrition']} products")
    for slug, p_ in paths.items():
        print(f"   {p_}")

    if not args.no_dry_run:
        print("== dry run ==")
        for slug, p_ in paths.items():
            r = post_csv(args.base, slug, p_, args.user, args.password, dry_run=True)
            if r.get("ok"):
                print(f"   {slug}: total={r.get('totalRows')} "
                      f"errors={r.get('errorCount')}")
                for e in r.get("errors", [])[:5]:
                    print(f"      {e.get('row')}:{e.get('field')} "
                          f"{e.get('code')}: {e.get('message')}")
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
