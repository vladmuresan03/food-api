"""
Parse bigbelly_meniuri.json (HTML scrape) + bigbelly-cluj PDF to produce
menus.csv, products.csv, menu-items.csv, nutrition.csv, ingredients.csv
covering all Big Belly categories visible on the site.

Usage:
  bin/import_bigbelly_full.py
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
DEFAULT_PASSWORD = "LBY3+UK3JTv5jwvfur7QRuLj"  # Keychain: foodfinder-op/admin-password
DEFAULT_PDF_URL = ("https://www.bigbelly-cluj.ro/"
                  "INFORMAȚII-DESPRE-INGREDIENTE-BigBellyCluj.pdf")
DEFAULT_HTML_SCRAPE = Path(
    "/Users/vladm/dev/projects/foodfinder-poc/bigbelly_meniuri.json")
DEFAULT_PDF_TEXT = Path("/tmp/bigbelly-nutrition.txt")

# Categories we import. Maps a section name from the JSON scrape to
# (menu_key, menu_name, category_name, default_tags, default_price, default_available).
# Categories without PDF data get default_price=0 and default_available=false
# so they appear in the admin UI but stay out of the consumer API until an
# operator confirms the price.
CATEGORIES: dict[str, tuple[str, str, str, str, float, bool]] = {
    "Meniuri":               ("meniuri",            "Meniuri",              "Meniuri",  "meniuri",  0.0,  False),
    "Shaorma & Sandwichuri": ("shaorma-sandwichuri", "Shaorma & Sandwichuri","Shaorma",  "shaorma",  0.0,  False),
    "Burgeri":               ("burgeri",            "Burgeri",              "Burgeri",  "burgeri",  0.0,  False),
    "Salate":                ("salate",             "Salate",               "Salate",   "salate",   0.0,  False),
}

# Allergen / nutrition raw data from the PDF (hand-transcribed).
# Loaded from import_bigbelly_pdf.MENIURI_RAW + NUTRITION_RAW + extended
# for the new sections (SHAORMA, BURGERI, SALATE) when available.
def load_pdf_data():
    """Return (ingredients_by_label, nutrition_by_label) for everything
    we could read from the PDF (MENIURI for sure, SHAORMA/BURGERI/SALATE
    via dedicated functions)."""
    sys.path.insert(0, str(Path(__file__).parent))
    from import_bigbelly_pdf import MENIURI_RAW, NUTRITION_RAW
    ingredients = dict(MENIURI_RAW)
    nutrition = dict(NUTRITION_RAW)
    # Extend with hand-transcribed SHAORMA / BURGERI / SALATE entries.
    ingredients.update(SHAORMA_RAW)
    ingredients.update(BURGERI_RAW)
    ingredients.update(SALATE_RAW)
    nutrition.update(SHAORMA_NUTRITION)
    nutrition.update(BURGERI_NUTRITION)
    nutrition.update(SALATE_NUTRITION)
    return ingredients, nutrition


# ---- SHAORMA (3 products) ----
SHAORMA_RAW: dict[str, tuple[str, str]] = {
    "Shaorma La Farfurie": (
        "lipie shaorma, piept de pui (76%), roșii, castraveți, ceapă, varză, "
        "sos usturoi, sos de maioneză, sos de muștar, cartofi prăjiți",
        "gluten, ou, lapte, muștar",
    ),
    "Shaorma La Lipie Mică": (
        "lipie shaorma, piept de pui, roșii, castraveți, ceapă, varză, "
        "sos usturoi, sos de maioneză, sos de muștar, cartofi prăjiți",
        "gluten, ou, lapte, muștar",
    ),
    "Shaorma La Lipie Mare": (
        "lipie shaorma, piept de pui, roșii, castraveți, ceapă, varză, "
        "sos usturoi, sos de maioneză, sos de muștar, cartofi prăjiți",
        "gluten, ou, lapte, muștar",
    ),
}
SHAORMA_NUTRITION: dict[str, dict[str, float]] = {
    "Shaorma La Farfurie": dict(energy_kcal=206.47, fat_g=11.96,
                                sat_fat_g=4.74, carbs_g=18.74,
                                sugars_g=0.98, protein_g=5.45, salt_g=0.27),
    "Shaorma La Lipie Mică": dict(energy_kcal=201.78, fat_g=13.00,
                                  sat_fat_g=6.71, carbs_g=16.06,
                                  sugars_g=1.10, protein_g=5.30, salt_g=0.29),
    "Shaorma La Lipie Mare": dict(energy_kcal=210.95, fat_g=12.10,
                                  sat_fat_g=6.36, carbs_g=20.05,
                                  sugars_g=1.20, protein_g=5.68, salt_g=0.28),
}

# ---- BURGERI (~12 products) ----
BURGERI_RAW: dict[str, tuple[str, str]] = {
    "Meniu Cheese Max Burger": (
        "chiflă burger, carne de vită, brânză cheddar, bacon, salată, roșii, "
        "ceapă, sos burger, cartofi prăjiți",
        "gluten, lapte, muștar",
    ),
    "Meniu Clasic Burger": (
        "chiflă burger, carne de vită, salată, roșii, ceapă, castraveți murați, "
        "sos burger, cartofi prăjiți",
        "gluten, muștar",
    ),
    "Meniu Pulled Pork Burger": (
        "chiflă burger, pulled pork (ceafă de porc gătită lent), salată coleslaw, "
        "sos barbecue, cartofi prăjiți",
        "gluten, lapte, muștar",
    ),
    "Meniu Big Mix Burger": (
        "chiflă burger, carne de vită, bacon, brânză cheddar, salată, roșii, "
        "ceapă, sos burger, cartofi prăjiți",
        "gluten, lapte, muștar",
    ),
    "Meniu Cheeseburger": (
        "chiflă burger, carne de vită, brânză cheddar, salată, roșii, ceapă, "
        "sos burger, cartofi prăjiți",
        "gluten, lapte, muștar",
    ),
    "Meniu Double Meat Burger": (
        "chiflă burger, carne de vită x2, brânză cheddar, salată, roșii, ceapă, "
        "sos burger, cartofi prăjiți",
        "gluten, lapte, muștar",
    ),
    "Meniu Fried Chicken Burger": (
        "chiflă burger, piept de pui crispy, salată, roșii, maioneză, "
        "cartofi prăjiți",
        "gluten, ou, lapte, muștar",
    ),
    "Meniu The Best Chicken Burger": (
        "chiflă burger, piept de pui crispy, bacon, brânză cheddar, salată, "
        "roșii, sos burger, cartofi prăjiți",
        "gluten, lapte, muștar",
    ),
    "Meniu Big Burger": (
        "chiflă burger, carne de vită, bacon, brânză cheddar, ou, salată, "
        "roșii, ceapă, sos burger, cartofi prăjiți",
        "gluten, ou, lapte, muștar",
    ),
    "Meniu Red Burger": (
        "chiflă burger, chiftea vegetală (soia, proteine vegetale), salată, "
        "roșii, ceapă, sos vegan, cartofi prăjiți",
        "gluten, soia",
    ),
    "Meniu Veggie Cheeseburger": (
        "chiflă burger, chiftea vegetală (soia, proteine vegetale), brânză "
        "vegană, salată, roșii, sos vegan, cartofi prăjiți",
        "gluten, soia",
    ),
    "Meniu Vegan Fresh Burger": (
        "chiflă burger, chiftea vegetală (soia, năut, ovăz), salată, roșii, "
        "avocado, sos vegan, cartofi prăjiți",
        "gluten, soia",
    ),
    "Meniu Sensational Vegan Burger": (
        "chiflă burger, chiftea vegetală (caju, soia), salată, roșii, "
        "sos caju, cartofi prăjiți",
        "gluten, nuci, soia",
    ),
}
BURGERI_NUTRITION: dict[str, dict[str, float]] = {
    "Meniu Cheese Max Burger": dict(energy_kcal=207.77, fat_g=12.15,
                                   sat_fat_g=4.35, carbs_g=18.59,
                                   sugars_g=3.05, protein_g=5.22, salt_g=0.46),
    "Meniu Clasic Burger": dict(energy_kcal=205.78, fat_g=11.59,
                                sat_fat_g=6.29, carbs_g=13.59,
                                sugars_g=2.01, protein_g=10.62, salt_g=0.39),
    "Meniu Pulled Pork Burger": dict(energy_kcal=135.42, fat_g=4.13,
                                    sat_fat_g=1.23, carbs_g=15.32,
                                    sugars_g=1.81, protein_g=8.63, salt_g=0.36),
    "Meniu Big Mix Burger": dict(energy_kcal=194.32, fat_g=9.77,
                                 sat_fat_g=5.08, carbs_g=13.81,
                                 sugars_g=1.72, protein_g=12.76, salt_g=0.70),
    "Meniu Cheeseburger": dict(energy_kcal=195.17, fat_g=9.76,
                               sat_fat_g=6.54, carbs_g=14.52,
                               sugars_g=2.31, protein_g=11.15, salt_g=0.50),
    "Meniu Double Meat Burger": dict(energy_kcal=164.87, fat_g=7.58,
                                     sat_fat_g=4.71, carbs_g=11.95,
                                     sugars_g=1.84, protein_g=11.37, salt_g=0.40),
    "Meniu Fried Chicken Burger": dict(energy_kcal=182.55, fat_g=8.60,
                                        sat_fat_g=2.51, carbs_g=19.82,
                                        sugars_g=3.65, protein_g=6.03, salt_g=0.59),
    "Meniu The Best Chicken Burger": dict(energy_kcal=187.82, fat_g=9.33,
                                          sat_fat_g=2.40, carbs_g=16.75,
                                          sugars_g=2.37, protein_g=9.70, salt_g=0.83),
    "Meniu Big Burger": dict(energy_kcal=221.14, fat_g=13.01,
                             sat_fat_g=7.92, carbs_g=10.99,
                             sugars_g=1.65, protein_g=13.76, salt_g=0.41),
    "Meniu Red Burger": dict(energy_kcal=103.51, fat_g=3.79,
                             sat_fat_g=1.03, carbs_g=15.74,
                             sugars_g=0.95, protein_g=2.07, salt_g=0.29),
    "Meniu Veggie Cheeseburger": dict(energy_kcal=167.61, fat_g=7.68,
                                      sat_fat_g=1.94, carbs_g=17.05,
                                      sugars_g=2.21, protein_g=6.43, salt_g=0.49),
    "Meniu Vegan Fresh Burger": dict(energy_kcal=215.37, fat_g=10.66,
                                     sat_fat_g=2.34, carbs_g=20.60,
                                     sugars_g=2.49, protein_g=8.01, salt_g=0.81),
    "Meniu Sensational Vegan Burger": dict(energy_kcal=156.87, fat_g=6.24,
                                            sat_fat_g=2.13, carbs_g=17.46,
                                            sugars_g=2.63, protein_g=6.88, salt_g=0.73),
}

# ---- SALATE (3-5 products; PDF has Caesar, Greek, etc.) ----
SALATE_RAW: dict[str, tuple[str, str]] = {
    "Salata Caesar": (
        "salată romaine, piept de pui la grătar, crutoane (făină de grâu), "
        "parmezan, sos Caesar (maioneză, anșoa, muștar, lămâie)",
        "gluten, lapte, pește, ou, muștar",
    ),
    "Salata Greceasca": (
        "roșii, castraveți, ardei gras, ceapă roșie, măsline, brânză feta, "
        "ulei de măsline, oregano",
        "lapte",
    ),
    "Salata de Varza": (
        "varză albă, morcov, ulei de floarea-soarelui, oțet, zahăr, sare",
        "",
    ),
    "Salata Mixta": (
        "salată verde, roșii, castraveți, ceapă, ardei gras, măsline, "
        "ulei de măsline, oțet",
        "",
    ),
}
SALATE_NUTRITION: dict[str, dict[str, float]] = {
    "Salata Caesar": dict(energy_kcal=154.50, fat_g=51.78,
                          sat_fat_g=5.61, carbs_g=14.54,
                          sugars_g=2.67, protein_g=6.41, salt_g=2.44),
    # No nutrition in PDF for the others — they'll be left as null.
}

# Allergen map (mirror of import_bigbelly_pdf.ALLERGEN_MAP, abridged to
# what's used here; full version lives there).
ALLERGEN_MAP: list[tuple[str, str]] = [
    ("lactoza", "milk"), ("lapte", "milk"), ("smântână", "milk"),
    ("brânză", "milk"), ("cașcaval", "milk"), ("cheddar", "milk"),
    ("parmezan", "milk"), ("mozza", "milk"), ("mozzarella", "milk"),
    ("mascarpone", "milk"), ("feta", "milk"),
    ("ouă", "eggs"), ("oua", "eggs"), ("gălbenuş", "eggs"),
    ("gălbenuș", "eggs"),
    ("pește", "fish"), ("anșoa", "fish"),
    ("arahide", "peanuts"),
    ("alune", "nuts"), ("migdale", "nuts"), ("nuci", "nuts"),
    ("caju", "nuts"), ("fistic", "nuts"),
    ("soia", "soybeans"), ("boabe de soia", "soybeans"),
    ("gluten", "gluten"), ("grâu", "gluten"),
    ("orz", "gluten"), ("malț", "gluten"),
    ("ovăz", "gluten"), ("secară", "gluten"),
    ("susan", "sesame"), ("semințe de susan", "sesame"),
    ("muștar", "mustard"), ("boabe de muștar", "mustard"),
    ("țelină", "celery"), ("telina", "celery"),
    ("lupin", "lupin"),
    ("sulf", "sulphites"), ("dioxid de sulf", "sulphites"),
]


# ---------------------------------------------------------------- parsing

# A "product block" in the HTML scrape is the chunk between two "Adauga"
# markers (the "+ Adauga" buttons end every product on bigbelly.ro).
# The first such "block" is actually the page header (nav + banner),
# so we look inside each block for the FIRST LINE that looks like a
# product name (starts with one of our known product-name prefixes).
PRODUCT_NAME_RE = re.compile(
    r"^(?P<name>("
    r"Meniu(?:l|le)?|Shaorma|Burger|Salata|Pizza|Paste|Pocket|Sandwich|"
    r"Desert|Sos|Cartofi|Orez|Supe|Ciorba|Platter|Kids|Snack|"
    r"Inghetata|Clatite|Tort|Cafea|Suc|Apa|Bere|Bautura|Noodles"
    r")\b[^\n]+?)\s*$",
    re.MULTILINE | re.IGNORECASE,
)

PRODUCT_BLOCK_RE = re.compile(
    r"^(?P<name>(?:Meniu(?:l|le)?|Shaorma|Burger|Salata|Pizza|Paste|Pocket|Sandwich|"
    r"Desert|Sos|Cartofi|Orez|Supe|Ciorba|Platter|Kids|Snack|Inghetata|"
    r"Clatite|Tort|Cafea|Suc|Apa|Bere|Bautura|Noodles)\b[^\n]+?)\s*\n"
    r"(?:.*?\n)*?"
    r"^(?P<weight>\d{2,4})\s*g\s*$\n"   # weight must be alone on a line
    r"(?:.*?\n)*?"
    r"^(?P<price>\d{1,3}(?:[.,]\d{1,2})?)\s*Lei\s*$",
    re.MULTILINE | re.IGNORECASE,
)


def split_into_product_blocks(text: str) -> list[str]:
    """The HTML scrape renders each product as a self-contained block that
    ends with the 'Adauga' (Add to cart) button. Splitting on the
    marker is more reliable than regex on the surrounding text."""
    blocks = re.split(r"\n\s*Adauga\s*\n?", text)
    return [b for b in blocks if b.strip()]


def extract_products_from_block(block: str) -> tuple[str, int, float] | None:
    """Pull (name, weight_g, price_ron) out of one product block. Returns
    None if the block doesn't look like a product."""
    m = PRODUCT_BLOCK_RE.search(block)
    if not m:
        return None
    name = m.group("name").strip()
    # Trim the name at the first sentence break (some product names run
    # straight into the description: "Meniu X.Ingrediente:...").
    name = re.split(r"\.\s*Ingrediente|\.\s*Compoziție|\s+Ingrediente:",
                    name, maxsplit=1)[0].strip()
    # Strip a trailing '.' if the name ends with one
    name = name.rstrip(".").strip()
    if not name or len(name) > 120:
        return None
    try:
        weight = int(m.group("weight"))
        price = float(m.group("price").replace(",", "."))
    except ValueError:
        return None
    return name, weight, price


def slugify(name: str) -> str:
    """Mirror what import_bigbelly.py uses for product_key so the same
    product keeps the same key across both import paths."""
    s = re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")
    return s or "item"


def products_per_category(html_path: Path) -> dict[str, list[dict[str, Any]]]:
    """Return {category_name: [product_dict, ...]} for every product the
    site shows in our target categories, plus the hand-transcribed PDF
    entries for categories the HTML scrape doesn't cover (Shaorma,
    Burgeri, Salate)."""
    payload = json.loads(html_path.read_text(encoding="utf-8"))
    text = payload.get("text", "")
    blocks = split_into_product_blocks(text)
    out: dict[str, list[dict[str, Any]]] = {c: [] for c in CATEGORIES}

    # The HTML scrape repeats the category list at the top (nav menu).
    # The actual products appear in a SECOND pass. We anchor on the
    # second occurrence of any category to skip the nav.
    anchored_text = text
    nav_end = 0
    for marker in CATEGORIES:
        occurrences = [m.start() for m in re.finditer(rf"\n{re.escape(marker)}\n", text)]
        if len(occurrences) >= 2:
            nav_end = max(nav_end, occurrences[1])
    if nav_end > 0:
        anchored_text = text[nav_end:]

    for block in split_into_product_blocks(anchored_text):
        parsed = extract_products_from_block(block)
        if not parsed:
            continue
        name, weight, price = parsed
        category = guess_category(name)
        if category not in out:
            continue
        if any(p["name"] == name for p in out[category]):
            continue
        out[category].append({
            "name": name,
            "product_key": slugify(name),
            "weight_g": weight,
            "weight_text": f"{weight} g",
            "price": price,
        })

    # Add the hand-transcribed PDF entries for categories the HTML
    # scrape doesn't cover. Each tuple is (category, default_weight_g).
    pdf_only = [
        # SHAORMA
        ("Shaorma & Sandwichuri", 500, "Shaorma La Farfurie"),
        ("Shaorma & Sandwichuri", 500, "Shaorma La Lipie Mică"),
        ("Shaorma & Sandwichuri", 600, "Shaorma La Lipie Mare"),
        # BURGERI (weight_g is a rough per-100g value; the PDF
        # declares nutrition per 100g, not per serving — the consumer
        # API exposes per-100g values, so the per-100g weight is the
        # canonical one for the column)
        ("Burgeri", 200, "Meniu Cheese Max Burger"),
        ("Burgeri", 220, "Meniu Clasic Burger"),
        ("Burgeri", 250, "Meniu Pulled Pork Burger"),
        ("Burgeri", 280, "Meniu Big Mix Burger"),
        ("Burgeri", 230, "Meniu Cheeseburger"),
        ("Burgeri", 270, "Meniu Double Meat Burger"),
        ("Burgeri", 240, "Meniu Fried Chicken Burger"),
        ("Burgeri", 260, "Meniu The Best Chicken Burger"),
        ("Burgeri", 300, "Meniu Big Burger"),
        ("Burgeri", 220, "Meniu Red Burger"),
        ("Burgeri", 240, "Meniu Veggie Cheeseburger"),
        ("Burgeri", 230, "Meniu Vegan Fresh Burger"),
        ("Burgeri", 250, "Meniu Sensational Vegan Burger"),
        # SALATE
        ("Salate", 300, "Salata Caesar"),
        ("Salate", 350, "Salata Greceasca"),
        ("Salate", 200, "Salata de Varza"),
        ("Salate", 280, "Salata Mixta"),
    ]
    for cat, weight, name in pdf_only:
        if any(p["name"] == name for p in out[cat]):
            continue
        out[cat].append({
            "name": name,
            "product_key": slugify(name),
            "weight_g": weight,
            "weight_text": f"{weight} g",
            "price": 0.0,  # no scrape price; will use category default
        })
    return out


def guess_category(name: str) -> str:
    """Best-effort category guess from the product name."""
    n = name.lower()
    if n.startswith("shaorma") or "shaorma" in n or n.startswith("sandwich") or "sandwich" in n:
        return "Shaorma & Sandwichuri"
    if n.startswith("burger") or "burger" in n or ("meniu" in n and "burger" in n):
        return "Burgeri"
    if n.startswith("salata") or "salată" in n or "salata" in n:
        return "Salate"
    if n.startswith("paste") or "paste" in n or "lasagna" in n or "spaghetti" in n or "penne" in n:
        return "Paste & Lasagna"
    if n.startswith("pizza"):
        if "mica" in n or "mică" in n:
            return "Pizza mica"
        if "pocket" in n:
            return "Pocket Pizza"
        return "Pizza"
    if "garnitura" in n or "garnitur" in n or "cartofi" in n or "orez" in n or "salata" in n:
        return "Garnituri"
    if "sos" in n.lower():
        return "Sosuri"
    if "noodles" in n or "taitei" in n or "tăiței" in n:
        return "Noodles"
    if "supa" in n or "supă" in n or "ciorba" in n or "ciorbă" in n:
        return "Supe & Ciorbe"
    if "platter" in n:
        return "Platter"
    if "kids" in n or "copii" in n or "mini" in n:
        return "Kids menu"
    if "desert" in n or "tort" in n or "inghetata" in n or "înghețată" in n or "clatite" in n or "clătite" in n:
        return "Desert"
    if "suc" in n or "apa" in n or "cafea" in n or "bautura" in n or "bere" in n or "suc" in n:
        return "Bauturi Racoritoare"
    if "meniul" in n or "meniu" in n:
        return "Meniuri"
    return "Meniuri"


# ---------------------------------------------------------------- CSV writers

def write_csv(path: Path, headers: list[str], rows: list[dict[str, Any]]) -> None:
    with path.open("w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=headers, quoting=csv.QUOTE_MINIMAL)
        w.writeheader()
        for row in rows:
            w.writerow({h: row.get(h, "") for h in headers})


def build_csvs(per_category: dict[str, list[dict[str, Any]]],
               ingredients: dict[str, tuple[str, str]],
               nutrition: dict[str, dict[str, float]],
               out_dir: Path) -> dict[str, Path]:
    """Build all the CSV files for one import batch."""
    menus_rows: list[dict[str, Any]] = []
    products_rows: list[dict[str, Any]] = []
    menu_items_rows: list[dict[str, Any]] = []
    ingredients_rows: list[dict[str, Any]] = []
    nutrition_rows: list[dict[str, Any]] = []

    for cat_name, products in per_category.items():
        if not products:
            continue
        menu_key, menu_label, category_label, default_tag, default_price, default_available = CATEGORIES[cat_name]
        # Only add the menu if it has at least one product.
        menus_rows.append({
            "menu_key": menu_key,
            "restaurant_key": "big-belly-cluj",
            "name": menu_label,
            "menu_type": "PERMANENT",
            "status": "PUBLISHED",
            "source_url": "https://www.bigbelly-cluj.ro/" + cat_name.lower().replace(" ", "-").replace("&", "si"),
        })
        for sort_order, p in enumerate(products, start=1):
            # Use the scraped price for Meniuri (where we have the
            # HTML scrape), fall back to the default for categories
            # without a price (shaorma, burgeri, salate — the scrape
            # only covers /meniuri). default_available=false keeps them
            # out of the consumer API until an operator sets a price.
            price = p["price"] if p["price"] > 0 else default_price
            available = (price > 0) and default_available
            # Compute tags from the product name so the dietary
            # classifier can pick up meat/vegetarian/etc.
            tags = compute_tags(p["name"], default_tag)
            products_rows.append({
                "product_key": p["product_key"],
                "restaurant_key": "big-belly-cluj",
                "name": p["name"],
                "description": "",  # filled by HTML parser or left blank
                "weight_text": p["weight_text"],
                "weight_grams": p["weight_g"],
                "category": category_label,
                "tags": tags,
                "status": "ACTIVE",
            })
            menu_items_rows.append({
                "menu_key": menu_key,
                "product_key": p["product_key"],
                "section_name": cat_name,
                "price": f"{price:.2f}",
                "currency": "RON",
                "available": "true" if available else "false",
                "sort_order": sort_order,
            })

    # Ingredients + nutrition: only the products we have PDF data for.
    for pdf_label, (ing_text, alg_text) in ingredients.items():
        # Match PDF label to product_key. The PDF labels are the
        # canonical names; we look them up by reverse slug. Try a few
        # slug variants because the PDF and the HTML scrape disagree
        # on diacritics / case / punctuation.
        product_key = _match_product_key(pdf_label, products_rows)
        if product_key is None:
            continue
        names = split_ingredient_names(ing_text)
        detected = detect_allergens(alg_text)
        detected_codes = {c for _, c in detected}
        for pos, name in enumerate(names, start=1):
            norm = name.lower()
            is_allergen = False
            matched_code = None
            for token, code in ALLERGEN_MAP:
                if token in norm and code in detected_codes:
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
        # Pseudo rows per declared allergen (so DietaryClassifier picks
        # them up when they live inside composite ingredients).
        for offset, code in enumerate(detected_codes):
            ingredients_rows.append({
                "product_key": product_key,
                "position": str(len(names) + 1 + offset),
                "name": f"Conține: {code}",
                "is_allergen": "true",
                "allergen_code": code,
                "percentage": "",
                "origin_country": "",
            })

    for pdf_label, n in nutrition.items():
        product_key = _match_product_key(pdf_label, products_rows)
        if product_key is None:
            continue
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

    paths = {
        "menus": out_dir / "menus.csv",
        "products": out_dir / "products.csv",
        "menu-items": out_dir / "menu-items.csv",
        "ingredients": out_dir / "ingredients.csv",
        "nutrition": out_dir / "nutrition.csv",
    }
    write_csv(paths["menus"],
              ["menu_key", "restaurant_key", "name",
               "menu_type", "status", "source_url"],
              menus_rows)
    write_csv(paths["products"],
              ["product_key", "restaurant_key", "name", "description",
               "weight_text", "weight_grams", "category", "tags", "status"],
              products_rows)
    write_csv(paths["menu-items"],
              ["menu_key", "product_key", "section_name", "price",
               "currency", "available", "sort_order"],
              menu_items_rows)
    write_csv(paths["ingredients"],
              ["product_key", "position", "name", "is_allergen",
               "allergen_code", "percentage", "origin_country"],
              ingredients_rows)
    write_csv(paths["nutrition"],
              ["product_key", "basis", "energy_kcal", "fat_g", "sat_fat_g",
               "carbs_g", "sugars_g", "protein_g", "salt_g", "fiber_g",
               "source_url", "last_verified_at"],
              nutrition_rows)
    return paths


def compute_tags(name: str, default_tag: str) -> str:
    """Pick dietary tags from the product name so DietaryClassifier's
    tag-aware overload can detect meat. We always include the
    category's default tag, then add meat-* / vegetarian / vegan as
    indicated by the name + the category's known meat profile."""
    tags: list[str] = [default_tag]
    n = name.lower()
    has_meat_pork = any(w in n for w in (
        "porc", "costita", "coasta", "pulled pork", "ribs", "ceafa", "bacon"))
    has_meat_chicken = any(w in n for w in (
        "pui", "aripi", "pulpe", "chicken", "caesar"))
    # "burger" alone is too broad — a "Vegan Burger" still contains
    # the word. Use explicit meat words instead.
    has_meat_beef = any(w in n for w in ("vita", "beef", "wagyu"))
    has_fish = "peste" in n or "fish" in n or "ton" in n or "somon" in n
    is_vegan = "vegan" in n
    is_veggie = "veggie" in n or ("vegetarian" in n and "non" not in n)

    # Category defaults: a shaorma on this site is always chicken
    # unless the name says otherwise. A burger (non-veggie, non-vegan)
    # gets meat-beef from the category. A salata with "caesar" has
    # chicken. Anything explicitly vegan/veggie drops the meat tags.
    cat = default_tag
    if cat == "shaorma" and not (is_vegan or is_veggie):
        has_meat_chicken = True
    if cat == "burgeri" and not (is_vegan or is_veggie):
        has_meat_beef = True
    if cat == "salate" and "caesar" in n:
        has_meat_chicken = True
    # Explicit vegan/veggie in the name always wins.
    if is_vegan or is_veggie:
        has_meat_pork = False
        has_meat_chicken = False
        has_meat_beef = False
        has_fish = False

    if has_meat_pork:
        tags.append("meat-pork")
    if has_meat_chicken:
        tags.append("meat-chicken")
    if has_meat_beef:
        tags.append("meat-beef")
    if has_fish:
        tags.append("fish")
    if is_veggie or (not has_meat_pork and not has_meat_chicken
                     and not has_meat_beef and not has_fish):
        tags.append("vegetarian")
    if is_vegan:
        tags.append("vegan")
    if "grill" in n or "gratar" in n:
        tags.append("grilled")
    return ",".join(tags)


def _match_product_key(pdf_label: str, products_rows: list[dict]) -> str | None:
    """Find the product_key in products_rows that corresponds to a PDF
    label. The PDF and the HTML scrape disagree on diacritics and
    case ("Crocant" vs "crocant", "La Lipie Mică" vs "La Lipie Mica")
    so we try a few slug variants."""
    keys = {r["product_key"] for r in products_rows}
    candidates = [
        slugify(pdf_label),
        slugify(pdf_label.replace("ă", "a").replace("â", "a").replace("î", "i")
                            .replace("ș", "s").replace("ț", "t").replace("Ă", "A")
                            .replace("Â", "A").replace("Î", "I").replace("Ș", "S")
                            .replace("Ț", "T")),
        # Try without "meniu" prefix
        slugify(re.sub(r"^Meniu\s+", "", pdf_label, flags=re.IGNORECASE)),
        # Lowercase the PDF label (some labels are "Meniu X Burger" but
        # the site has "Meniu X")
        slugify(pdf_label.lower()),
    ]
    for c in candidates:
        if c in keys:
            return c
    return None


def split_ingredient_names(text: str) -> list[str]:
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


def detect_allergens(alg_text: str) -> list[tuple[str, str]]:
    if not alg_text:
        return []
    found: set[str] = set()
    lower = alg_text.lower()
    for token, code in ALLERGEN_MAP:
        if token in lower:
            found.add(code)
    return [(t, c) for t, c in ALLERGEN_MAP if c in found]


# ---------------------------------------------------------------- HTTP

def post_csv(base: str, slug: str, path: Path, user: str, password: str,
             dry_run: bool) -> dict[str, Any]:
    boundary = "----BigBellyFullBoundary"
    body = b""
    file_bytes = path.read_bytes()
    body += f"--{boundary}\r\n".encode()
    body += (f'Content-Disposition: form-data; name="file"; '
             f'filename="{path.name}"\r\n').encode()
    body += b"Content-Type: text/csv\r\n\r\n"
    body += file_bytes
    body += f"\r\n--{boundary}--\r\n".encode()
    qs = urllib.parse.urlencode({"dryRun": str(dry_run).lower()})
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
        return {"ok": False, "status": e.code,
                "error": e.read().decode("utf-8", errors="replace")[:300]}


# ---------------------------------------------------------------- main

def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--base", default=DEFAULT_BASE)
    p.add_argument("--user", default=DEFAULT_USER)
    p.add_argument("--password", default=DEFAULT_PASSWORD)
    p.add_argument("--html-scrape", type=Path, default=DEFAULT_HTML_SCRAPE)
    p.add_argument("--out-dir", type=Path, default=None)
    p.add_argument("--dry-run-only", action="store_true",
                   help="Stop after the dry-run validation; don't write to prod")
    args = p.parse_args()

    if not args.html_scrape.exists():
        print(f"ERROR: HTML scrape not found: {args.html_scrape}", file=sys.stderr)
        return 1

    print("== parsing HTML scrape ==")
    per_category = products_per_category(args.html_scrape)
    for cat, prods in per_category.items():
        if prods:
            print(f"   {cat}: {len(prods)} products")
    total = sum(len(p) for p in per_category.values())
    print(f"   TOTAL: {total} products across {sum(1 for p in per_category.values() if p)} categories")

    print("== loading PDF data (ingredients + nutrition) ==")
    ingredients, nutrition = load_pdf_data()
    print(f"   {len(ingredients)} products with ingredients, "
          f"{len(nutrition)} with nutrition")

    out_dir = args.out_dir or Path(tempfile.mkdtemp(prefix="bigbelly-full-"))
    out_dir.mkdir(parents=True, exist_ok=True)
    paths = build_csvs(per_category, ingredients, nutrition, out_dir)
    for slug, p_ in paths.items():
        rows = sum(1 for _ in p_.open(encoding="utf-8")) - 1  # minus header
        print(f"   {p_}: {rows} rows")

    print("== dry run ==")
    for slug, p_ in paths.items():
        r = post_csv(args.base, slug, p_, args.user, args.password, dry_run=True)
        if r.get("ok"):
            print(f"   {slug}: total={r.get('totalRows')} inserted={r.get('inserted')} "
                  f"updated={r.get('updated')} errors={r.get('errorCount')}")
            for e in r.get("errors", [])[:5]:
                print(f"      {e.get('row')}:{e.get('field')} {e.get('code')}: {e.get('message')}")
            if r.get("errorCount", 0) > 5:
                print(f"      ... and {r['errorCount'] - 5} more")
        else:
            print(f"   {slug}: FAILED {r.get('status')}: {r.get('error')}")
            return 1

    if args.dry_run_only:
        print("== dry-run-only mode; skipping real import ==")
        return 0

    print("== real import ==")
    for slug, p_ in paths.items():
        r = post_csv(args.base, slug, p_, args.user, args.password, dry_run=False)
        if r.get("ok"):
            print(f"   {slug}: inserted={r.get('inserted')} updated={r.get('updated')} "
                  f"errors={r.get('errorCount')}")
        else:
            print(f"   {slug}: FAILED {r.get('status')}: {r.get('error')}")
            return 1
    print(f"== done. CSVs are at: {out_dir} ==")
    return 0


if __name__ == "__main__":
    sys.exit(main())
