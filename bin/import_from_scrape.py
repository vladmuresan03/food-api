#!/usr/bin/env python3
"""
Import a restaurant menu from a scrape JSON (produced by bin/scrape_*.mjs
Node scripts) into the food-api catalog.

Scrape JSON shape:
  {
    "restaurantKey":  "jaxx",
    "restaurantName": "Jaxx Gastropub",
    "address":        "Strada Emil Isac 25, Cluj-Napoca",
    "city":           "Cluj-Napoca",
    "phone":          "+40373811123",
    "website":        "https://jaxxrestaurants.ro/",
    "cuisine":        "gastropub",
    "defaultTags":    ["gastropub"],            # optional, tags added to every product
    "menus": [
      {
        "name": "Kitchen",
        "url":  "https://qubs.app/ro/menu/.../menu",
        "items": [
          {
            "name":          "Mac and Cheese",
            "price":         24.0,
            "currency":      "RON",
            "weight_text":   "200 g",
            "weight_grams":  200,
            "description":   "Autenticul Mac and Cheese: ...",
            "imageUrl":      "https://imagedelivery.net/...",
            "section":       "Pasta",           # optional: section within the menu
            "tags":          ["meat-pork"],     # optional: extra tags
            "category":      "Pasta"            # optional: overrides menu name as category
          }
        ]
      }
    ]
  }

Generates 4 CSVs (restaurants, menus, products, menu-items) and posts
them to /admin/api/csv/{slug} in dependency order. CSVs are written
to --out-dir for debugging; pass --no-dry-run to actually POST.

Idempotent: the CSV importer upserts on *_key. To start fresh, use
--wipe (hard-deletes the restaurant via the admin API; cascades via V8
FK CASCADE).

After the CSV import, if --upload-photos is set, walks the scrape
JSON, downloads each imageUrl to a temp file and POSTs as multipart
to /admin/api/photos with sourceType=GOOGLE_PROTOTYPE.

Usage:
  bin/scrape_jaxx.mjs --out /tmp/jaxx.json
  bin/import_from_scrape.py /tmp/jaxx.json --no-dry-run --upload-photos
  bin/import_from_scrape.py /tmp/jaxx.json --wipe --no-dry-run --upload-photos
"""
from __future__ import annotations

import argparse
import base64
import csv
import io
import json
import os
import re
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

# --------------------------------------------------------------- constants

DEFAULT_BASE = "https://food.treloc.com"
DEFAULT_USER = "admin"
DEFAULT_PASSWORD = "LBY3+UK3JTv5jwvfur7QRuLj"
DEFAULT_OUT_DIR = Path(tempfile.mkdtemp(prefix="foodfinder-import-"))

UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X)"

# Slugify rules: lowercase a-z0-9 with single hyphens, no leading/trailing.
SLUG_RE = re.compile(r"[^a-z0-9]+")

# The food-api Product.product_key column is 160 chars. We use 60 here
# to match the historical convention from Big Belly and keep slugs
# readable.
PRODUCT_KEY_MAX = 60
MENU_KEY_MAX = 60
SECTION_NAME_MAX = 60
CATEGORY_MAX = 60
NAME_MAX = 250
TAGS_MAX = 250

# Currency normalization: source says "LEI", "RON", "lei", "RON "
# We canonicalise to "RON" because the food-api is a Romanian product
# and the only legitimate currencies on prod are RON (with a couple of
# EUR exceptions for imported products).
CURRENCY_MAP = {
    "lei": "RON", "ron": "RON", "lei ": "RON", "ron ": "RON",
    "€": "EUR", "eur": "EUR", "euro": "EUR",
}


def slugify(s: str) -> str:
    s = s.lower()
    s = SLUG_RE.sub("-", s)
    s = s.strip("-")
    return s


# --------------------------------------------------------------- IO helpers


def write_csv(path: Path, header: list[str], rows: list[dict[str, Any]]) -> int:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=header, extrasaction="ignore")
        w.writeheader()
        for r in rows:
            w.writerow(r)
    return len(rows)


def _request(url: str, method: str = "GET", body: bytes | None = None,
             headers: dict | None = None, user: str = DEFAULT_USER,
             password: str = DEFAULT_PASSWORD, timeout: int = 60,
             auth: bool = True) -> tuple[int, bytes, dict]:
    """HTTP request helper. By default adds the food-api basic-auth
    header and a browser User-Agent. Pass auth=False for downloads
    from CDNs/S3 buckets that reject the Authorization header
    (e.g. boosteat.com returns 400 InvalidArgument for
    "Unsupported Authorization Type")."""
    headers = dict(headers or {})
    headers.setdefault("User-Agent", UA)
    if auth and user and password and "Authorization" not in headers:
        token = base64.b64encode(f"{user}:{password}".encode()).decode()
        headers["Authorization"] = f"Basic {token}"
    req = urllib.request.Request(url, data=body, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read(), dict(resp.headers)
    except urllib.error.HTTPError as e:
        return e.code, e.read(), dict(e.headers or {})


def post_csv(base: str, slug: str, path: Path, user: str, password: str,
             dry_run: bool) -> dict[str, Any]:
    """POST a single CSV to /admin/api/csv/{slug}."""
    boundary = "----ImportBoundary"
    body = b""
    file_bytes = path.read_bytes()
    body += f"--{boundary}\r\n".encode()
    body += (f'Content-Disposition: form-data; name="file"; '
             f'filename="{path.name}"\r\n').encode()
    body += b"Content-Type: text/csv\r\n\r\n"
    body += file_bytes
    body += f"\r\n--{boundary}--\r\n".encode()
    qs = urllib.parse.urlencode({
        "dryRun": str(dry_run).lower(),
        "actor": "import_from_scrape.py",
    })
    status, raw, _ = _request(
        f"{base}/admin/api/csv/{slug}?{qs}", method="POST", body=body,
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
        user=user, password=password)
    try:
        data = json.loads(raw.decode("utf-8"))
    except json.JSONDecodeError:
        data = {"raw": raw.decode("utf-8", errors="replace")}
    data["_status"] = status
    return data


# --------------------------------------------------------------- cleaning


# Patterns for cleaning product names. Order matters: bilingual
# separator patterns first, then weight-only, then trailing weight.
BILINGUAL_SEP = re.compile(r"\s*<br\s*/?>\s*", re.IGNORECASE)
LEAF_SPLIT = re.compile(r"\s*/\s*")  # "X / Y" - keep only X

# Patterns for cleaning prices. Strips currency suffix and the "/weight"
# suffix that some sites add to the price string (e.g. "73 lei /500g").
PRICE_CURRENCY = re.compile(r"\b(lei|ron|eur|euro|€|lei/ron|ron/eur)\b",
                            re.IGNORECASE)
PRICE_PER_WEIGHT = re.compile(r"\s*/\s*\d+\s*(g|gr|kg|ml|l|cl)?\s*$",
                              re.IGNORECASE)
PRICE_TRAILING = re.compile(r"[\s\xa0]*[a-z€]+.*$", re.IGNORECASE)


def _coerce_price(raw) -> float | None:
    if raw is None or raw == "":
        return None
    if isinstance(raw, (int, float)):
        return float(raw)
    s = str(raw).strip()
    if not s:
        return None
    # Strip currency
    s = PRICE_CURRENCY.sub("", s)
    # Strip "/weight" (e.g. "/500g")
    s = PRICE_PER_WEIGHT.sub("", s)
    s = s.replace(",", ".").replace("\xa0", " ").strip()
    m = re.search(r"-?\d+(?:\.\d+)?", s)
    if not m:
        return None
    try:
        return float(m.group(0))
    except ValueError:
        return None


def _coerce_currency(raw) -> str:
    if not raw:
        return "RON"
    s = str(raw).strip().lower()
    return CURRENCY_MAP.get(s, s.upper() if len(s) <= 4 else "RON")


def _coerce_weight_text(raw) -> str:
    if raw is None:
        return ""
    return str(raw).strip()


def _coerce_weight_grams(raw) -> int | None:
    if raw is None or raw == "":
        return None
    if isinstance(raw, (int, float)):
        return int(raw)
    s = str(raw).strip().lower()
    # Pure "200g" / "200 g" / "200 gr" -> 200
    m = re.match(r"^\s*(\d+(?:\.\d+)?)\s*(g|gr|kg)\s*$", s)
    if m:
        val = float(m.group(1))
        unit = m.group(2)
        if unit == "kg":
            val *= 1000
        return int(val)
    # "500ml" / "330 ml" -> not grams; leave None (volume is not grams)
    if re.match(r"^\s*\d+(?:\.\d+)?\s*(ml|l|cl)\s*$", s):
        return None
    return None


def clean_name(raw: str) -> str:
    """Clean a product name:
      - collapse <br> to a single space
      - for 'X <br> Y' bilingual, keep the longer (or first non-empty)
        line. The food-api name column is 250 chars so we keep both if
        the result fits.
      - strip leading/trailing whitespace and dashes
    """
    if not raw:
        return ""
    s = raw.strip()
    # Replace <br> tags with a single space
    s = BILINGUAL_SEP.sub(" ", s)
    s = re.sub(r"\s+", " ", s).strip()
    # Strip trailing weight that bled into the title (e.g. "Pad Thai 300g")
    s = re.sub(r"\s+\d+\s*g(r)?$", "", s, flags=re.IGNORECASE)
    return s.strip(" -")


def split_bilingual(raw: str) -> tuple[str, str | None]:
    """If raw contains 'X <br> Y' or 'X / Y' (Romanian / English), split
    into (first, second). Returns (first, None) if not bilingual."""
    if not raw:
        return "", None
    # <br> split
    if "<br" in raw.lower():
        parts = re.split(r"<br\s*/?>", raw, maxsplit=1, flags=re.IGNORECASE)
        if len(parts) == 2:
            return parts[0].strip(), parts[1].strip()
    # "X / Y" split, but only if both look like words (not "Cafea / 500g")
    if " / " in raw:
        left, right = raw.split(" / ", 1)
        # Avoid splitting when the right side looks like a size/weight
        if not re.match(r"^\d", right) and len(right) > 1:
            return left.strip(), right.strip()
    return raw.strip(), None


# --------------------------------------------------------------- ingredients + allergens


def split_ingredients(description: str) -> tuple[str, list[str]]:
    """If the description embeds an 'Ingrediente: ...' or 'Ingredients: ...'
    line, split it out into a list of ingredient names. Returns
    (clean_description, [ingredient, ...]). Ingredients are split on
    commas, semicolons, or '/' separators; each is trimmed.
    """
    if not description:
        return "", []
    pattern = re.compile(
        r"\n\s*(?:Ingrediente|Ingredients)\s*:\s*",
        re.IGNORECASE,
    )
    m = pattern.search(description)
    if not m:
        return description.strip(), []
    clean = description[:m.start()].strip()
    raw_ings = description[m.end():].strip()
    # Strip a trailing "Alergeni: 1, 2, 3" line - keep that for the
    # allergens extractor instead.
    allergeni = re.split(r"\n\s*(?:Alergeni|Allergens)\s*:",
                         raw_ings, maxsplit=1, flags=re.IGNORECASE)
    ing_block = allergeni[0]
    allergen_text = allergeni[1].strip() if len(allergeni) > 1 else ""
    # Split by comma, semicolon, or slash (with optional spaces)
    parts = re.split(r"[,;]|/\s*(?=[A-ZĂÂÎȘȚ])", ing_block)
    ings = [p.strip().rstrip(".").strip() for p in parts if p.strip()]
    if allergen_text:
        clean = f"{clean}\nAlergeni: {allergen_text}".strip()
    return clean, ings


# EU 1169/2011 Annex II allergen numbers.
# We map both numeric codes (used by some sites) and Romanian/English
# keyword matches. The codes here match the food-api AllergenCode enum.
ALLERGEN_KEYWORDS: dict[str, list[str]] = {
    "gluten":      ["grâu", "grau", "wheat", "făină", "faina", "flour", "orz",
                    "barley", "ovăz", "ovaz", "oats", "secară", "secara",
                    "rye", "paste ", "pâine", "paine", "bread", "malț", "malt",
                    "tăiței", "taitei", "noodles", "spaghetti", "fusilli", "penne", "tagliatelle", "gnocchi", "ravioli", "pappardelle", "macaroane", "fettuccine"],
    "crustaceans": ["creveți", "creveti", "shrimp", "prawn", "crab", "homar",
                    "lobster", "langustă", "langusta"],
    "eggs":        ["ouă", "oua", "egg", "ou,", "ou."],
    "fish":        ["pește", "peste", "fish", "ton", "somon", "salmon", "cod",
                    "sardine", "scrumbie", "macrou", "herring", "hake",
                    "pastrav", "păstrăv", "skate", " dorada", "dorade",
                    "biban", "șalău", "salau", "crap", "păstrăv"],
    "peanuts":     ["alune", "arahide", "peanut", "groundnut", "ciocolată cu alune", "ciocolata cu alune"],
    "soybeans":    ["soia", "soy", "soya", "edamame", "tofu", "sos de soia"],
    "milk":        ["lapte", "milk", "lactate", "brânză", "branza", "cheese",
                    "cheddar", "mozzarella", "smântână", "smantana", "cream",
                    "parmezan", "parmesan", "feta", "brie", "cașcaval",
                    "cascaval", "iaurt", "yogurt", "unt", "butter", "caș",
                    "cas", "straciatella", "ricotta", "pecorino", "parmigiano",
                    "gorgonzola", "grana padano", "béchamel", "bechamel"],
    "nuts":        ["nuci", "nuca", "walnut", "migdale", "almond", "fistic",
                    "pistachio", "caju", "cashew", "hazelnut",
                    "alune de pădure", "macadamia"],
    "celery":      ["țelină", "telina", "celery"],
    "mustard":     ["muștar", "mustar", "mustard"],
    "sesame":      ["susan", "sesame", "tahini"],
    "sulphites":   ["sulfiți", "sulfiti", "sulphite", "sulfite", "metabisulfit"],
    "lupin":       ["lupin", "lupină", "lupina"],
    "molluscs":    ["scoici", "mussels", "calmar", "squid", "octopus",
                    "caracatiță", "caracatita", "midii"],
}


def detect_allergens(text_or_ings) -> list[str]:
    if isinstance(text_or_ings, str):
        text = text_or_ings.lower()
    else:
        text = " ".join(text_or_ings or []).lower()
    if not text:
        return []
    # Use word boundaries for short keywords (3-4 chars) to avoid
    # false positives like "cajun" matching "caju" or "lapte" matching
    # inside unrelated words. Longer keywords (5+ chars) are unlikely
    # to substring-match by accident.
    import re as _re
    word_pat = _re.compile(r"\b\w+\b", flags=_re.UNICODE)
    words = set(word_pat.findall(text))
    found: list[str] = []
    for code, keywords in ALLERGEN_KEYWORDS.items():
        for kw in keywords:
            kwl = kw.lower()
            if len(kwl) <= 4:
                # Word-boundary match
                if kwl in words:
                    if code not in found:
                        found.append(code)
                    break
            else:
                if kwl in text:
                    if code not in found:
                        found.append(code)
                    break
    return found


# Numeric allergen code mapping (used by My Thai and similar sites that
# follow EU 1169/2011 numeric convention).
ALLERGEN_NUMERIC_MAP = {
    "1": "gluten", "2": "crustaceans", "3": "eggs", "4": "fish",
    "5": "peanuts", "6": "soybeans", "7": "milk", "8": "nuts",
    "9": "celery", "10": "mustard", "11": "sesame", "12": "sulphites",
    "13": "lupin", "14": "molluscs",
}

ALLERGEN_LINE_RE = re.compile(
    r"(?:^|\n)\s*Alergeni\s*:\s*([0-9.,\s]+)", re.IGNORECASE)


def extract_allergens(description: str, ingredients: list[str]) -> list[str]:
    """Combine keyword detection (from description + ingredient names) and
    numeric codes (if 'Alergeni: 1, 2, 3' is present)."""
    if not description and not ingredients:
        return []
    codes: list[str] = []
    codes.extend(detect_allergens(description or ""))
    codes.extend(detect_allergens(ingredients or []))
    if description:
        m = ALLERGEN_LINE_RE.search(description)
        if m:
            for n in re.findall(r"\d+", m.group(1)):
                c = ALLERGEN_NUMERIC_MAP.get(n)
                if c and c not in codes:
                    codes.append(c)
    # De-dupe, keep order
    seen = set()
    out = []
    for c in codes:
        if c not in seen:
            seen.add(c)
            out.append(c)
    return out


# --------------------------------------------------------------- transform


def build_csvs(payload: dict, out_dir: Path) -> dict[str, Path]:
    """Translate the scrape JSON into the 4 CSV files the food-api
    expects. Returns a mapping of slug -> path."""
    rk = payload["restaurantKey"]
    rname = payload["restaurantName"]
    address = payload.get("address", "")
    city = payload.get("city", "Cluj-Napoca")
    website = payload.get("website", "")
    phone = payload.get("phone", "")
    cuisine = payload.get("cuisine", "")
    default_tags = payload.get("defaultTags", []) or []
    if cuisine and cuisine not in default_tags:
        default_tags = [cuisine] + default_tags

    restaurant_row = {
        "restaurant_key": rk,
        "name": rname,
        "website_url": website,
        "address_line": address,
        "city": city,
        "latitude": "",
        "longitude": "",
        "status": "ACTIVE",
    }

    # Build menus: one per top-level entry in payload["menus"].
    # Menu key = "{rk}-{slugified name}".
    menu_rows: list[dict] = []
    for menu in payload["menus"]:
        mname = (menu.get("name") or "").strip() or "Meniu"
        mk = f"{rk}-{slugify(mname)}"[:MENU_KEY_MAX].rstrip("-")
        menu_rows.append({
            "menu_key": mk,
            "restaurant_key": rk,
            "name": mname[:NAME_MAX],
            "menu_type": "PERMANENT",
            "status": "PUBLISHED",
            "source_url": menu.get("url", website)[:250],
            "valid_from": "",
            "valid_to": "",
        })

    # product_key is GLOBAL in the food-api. To avoid collisions with
    # already-imported menus (e.g. an "Espresso" at Big Belly colliding
    # with an "Espresso" at Jaxx), we prefix every product_key with
    # the restaurant_key. The max length is 60 chars.
    product_rows: list[dict] = []
    menu_item_rows: list[dict] = []
    ingredient_rows: list[dict] = []  # rows for ingredients.csv
    name_to_key: dict[str, str] = {}
    seen_keys: dict[str, int] = {}

    def next_product_key(base: str) -> str:
        base_slug = slugify(base) or "item"
        prefix = rk
        max_base = PRODUCT_KEY_MAX - len(prefix) - 1
        if max_base < 8:
            prefix = rk[:8]
            max_base = PRODUCT_KEY_MAX - len(prefix) - 1
        full_base = f"{prefix}-{base_slug}"[:max_base].rstrip("-")
        n = seen_keys.get(full_base, 0)
        seen_keys[full_base] = n + 1
        if n == 0:
            return full_base
        suffix = f"-{n + 1}"
        return (full_base[: PRODUCT_KEY_MAX - len(suffix)] + suffix).rstrip("-")

    seen_pairs: set[tuple[str, str]] = set()

    for menu in payload["menus"]:
        mname = (menu.get("name") or "").strip() or "Meniu"
        mk = f"{rk}-{slugify(mname)}"[:MENU_KEY_MAX].rstrip("-")
        # Default section is the menu name; items can override via
        # item["section"]. If items in a menu have NO section, we use
        # the menu name as section for the menu_item rows so the public
        # /api/products filter has something to group by.
        default_section = mname
        sort = 10

        for it in menu.get("items", []):
            raw_name = (it.get("name") or "").strip()
            if not raw_name:
                continue
            name = clean_name(raw_name)
            if not name:
                continue
            # Take the first half of bilingual names as the canonical
            # name; keep the second half (e.g. English) in tags or
            # description if present.
            bilingual_second = None
            if "<br" in raw_name.lower() or " / " in raw_name:
                bilingual_second = split_bilingual(raw_name)[1]

            # Dedup by lowercase name within this restaurant
            norm = name.lower()
            if norm in name_to_key:
                pk = name_to_key[norm]
            else:
                pk = next_product_key(name)
                name_to_key[norm] = pk

                desc_raw = (it.get("description") or "").strip()
                # If the original name had a bilingual half, keep the
                # second language as a parenthetical in the description
                # for searchability.
                if bilingual_second and bilingual_second.lower() not in desc_raw.lower():
                    if desc_raw:
                        desc_raw = f"{desc_raw} (a.k.a. {bilingual_second})"
                    else:
                        desc_raw = f"(also: {bilingual_second})"

                # Extract "Ingrediente: ..." block from the description
                # if present; this both cleans up the description and
                # gives us the ingredient list to emit separately.
                desc_clean, ingredients = split_ingredients(desc_raw)
                if not desc_clean and desc_raw:
                    desc_clean = desc_raw

                weight_grams = it.get("weight_grams")
                if weight_grams is None:
                    weight_grams = _coerce_weight_grams(it.get("weight_text"))
                weight_text = _coerce_weight_text(it.get("weight_text"))
                if weight_grams is not None and not weight_text:
                    weight_text = f"{weight_grams} g"

                # Tags: default_tags + item tags + auto-detected dietary
                item_tags = list(default_tags) + list(it.get("tags") or [])
                # Section can also act as a tag (e.g. "Pasta" -> "pasta")
                section_name = (it.get("section") or "").strip() or default_section
                section_slug = slugify(section_name)
                if section_slug and section_slug not in [slugify(t) for t in item_tags]:
                    item_tags.append(section_slug)
                # Add a category tag from the menu name
                menu_slug = slugify(mname)
                if menu_slug and menu_slug not in [slugify(t) for t in item_tags]:
                    item_tags.append(menu_slug)

                # Category: use the per-item category if set, then
                # the section name, then fall back to the menu name.
                # For sites with real sections (My Thai, Tortelli)
                # the section is the most useful filter.
                if it.get("category"):
                    category = it["category"][:CATEGORY_MAX]
                elif section_name and section_name != mname:
                    category = section_name[:CATEGORY_MAX]
                else:
                    category = mname[:CATEGORY_MAX]

                product_rows.append({
                    "product_key": pk,
                    "restaurant_key": rk,
                    "name": name[:NAME_MAX],
                    "description": (desc_clean or "")[:1000],
                    "weight_text": weight_text[:100],
                    "weight_grams": _maybe_int(weight_grams),
                    "category": category,
                    "tags": ",".join(item_tags)[:TAGS_MAX],
                    "status": "ACTIVE",
                })

                # Ingredients: emit one row per ingredient. We detect
                # allergens both from the ingredient names and from
                # any "Alergeni: 1, 2, 3" line that survived in the
                # description.
                allergens = extract_allergens(desc_clean, ingredients)
                allergen_ings = {a for a in allergens}
                # Map ingredient name -> allergen code by substring match
                def _match_allergen(ing_name: str) -> str | None:
                    n = ing_name.lower()
                    for code, kws in ALLERGEN_KEYWORDS.items():
                        if code not in allergen_ings:
                            continue
                        if any(kw.lower() in n for kw in kws):
                            return code
                    # If ingredient matches an allergen in the full
                    # description text but we can't pinpoint the
                    # ingredient, attribute the allergen to the
                    # first ingredient in the list.
                    return None

                current_first_idx = len(ingredient_rows)
                for pos, ing in enumerate(ingredients[:50], start=1):
                    code = _match_allergen(ing)
                    ingredient_rows.append({
                        "product_key": pk,
                        "position": str(pos),
                        "name": ing[:100],
                        "is_allergen": "true" if code else "false",
                        "allergen_code": code or "",
                        "percentage": "",
                        "origin_country": "",
                    })
                # If allergens are detected but no ingredient was
                # pinned to them (e.g. from a numeric Alergeni code),
                # attach the allergen to the first ingredient of THIS
                # product (not the first row of the whole CSV).
                if allergens and not any(
                        r["product_key"] == pk and r["is_allergen"] == "true"
                        for r in ingredient_rows[current_first_idx:]):
                    if current_first_idx < len(ingredient_rows):
                        first = ingredient_rows[current_first_idx]
                        if not first["allergen_code"]:
                            first["is_allergen"] = "true"
                            first["allergen_code"] = allergens[0]

            pair = (mk, pk)
            if pair in seen_pairs:
                continue
            seen_pairs.add(pair)

            price = _coerce_price(it.get("price"))
            currency = _coerce_currency(it.get("currency"))
            section_name = (it.get("section") or "").strip() or default_section

            menu_item_rows.append({
                "menu_key": mk,
                "product_key": pk,
                "section_name": section_name[:SECTION_NAME_MAX],
                "price": _maybe_float(price),
                "currency": currency,
                "available": "true",
                "sort_order": str(sort),
                "spice_level": "",
                "source_url": (menu.get("url", website) or "")[:250],
            })
            sort += 10

    paths: dict[str, Path] = {
        "restaurants": out_dir / "restaurants.csv",
        "menus": out_dir / "menus.csv",
        "products": out_dir / "products.csv",
        "menu-items": out_dir / "menu-items.csv",
        "ingredients": out_dir / "ingredients.csv",
    }
    write_csv(paths["restaurants"],
              ["restaurant_key", "name", "website_url", "address_line",
               "city", "latitude", "longitude", "status"],
              [restaurant_row])
    write_csv(paths["menus"],
              ["menu_key", "restaurant_key", "name", "menu_type",
               "status", "source_url", "valid_from", "valid_to"],
              menu_rows)
    write_csv(paths["products"],
              ["product_key", "restaurant_key", "name", "description",
               "weight_text", "weight_grams", "category", "tags", "status"],
              product_rows)
    write_csv(paths["menu-items"],
              ["menu_key", "product_key", "section_name", "price",
               "currency", "available", "sort_order", "spice_level",
               "source_url"],
              menu_item_rows)
    write_csv(paths["ingredients"],
              ["product_key", "position", "name", "is_allergen",
               "allergen_code", "percentage", "origin_country"],
              ingredient_rows)
    return paths


def _maybe_int(x) -> str:
    if x is None or x == "":
        return ""
    try:
        return str(int(x))
    except (TypeError, ValueError):
        return ""


def _maybe_float(x, decimals: int = 2) -> str:
    if x is None or x == "":
        return ""
    try:
        return f"{float(x):.{decimals}f}"
    except (TypeError, ValueError):
        return ""


# --------------------------------------------------------------- wipe


def wipe_restaurant(base: str, key: str, user: str, password: str) -> None:
    """Two-step guard: archive via PATCH /status, then DELETE."""
    patch_url = f"{base}/admin/api/restaurants/{key}/status"
    status, _, _ = _request(
        patch_url, method="PATCH",
        body=json.dumps({"status": "ARCHIVED"}).encode(),
        headers={"Content-Type": "application/json"},
        user=user, password=password)
    print(f"  PATCH {key} ARCHIVED: HTTP {status}")
    del_url = f"{base}/admin/api/restaurants/{key}"
    status, raw, _ = _request(del_url, method="DELETE", user=user, password=password)
    print(f"  DELETE {key}: HTTP {status} ({(raw or b'')[:120].decode('utf-8', errors='replace')})")


# --------------------------------------------------------------- photos


def upload_photos(base: str, payload: dict, user: str, password: str,
                  limit: int | None = None) -> tuple[int, int, int]:
    """For each item with an imageUrl, download to a temp file and POST
    to /admin/api/photos as multipart. Returns (uploaded, failed, skipped)."""
    import uuid

    rk = payload["restaurantKey"]
    name_to_pk: dict[str, str] = {}
    # Build a name -> product_key map (same logic as the CSV builder)
    seen_keys: dict[str, int] = {}

    def pk_for(raw_name: str) -> str:
        name = clean_name(raw_name)
        norm = name.lower()
        if norm in name_to_pk:
            return name_to_pk[norm]
        base_slug = slugify(name) or "item"
        prefix = rk
        max_base = PRODUCT_KEY_MAX - len(prefix) - 1
        full_base = f"{prefix}-{base_slug}"[:max_base].rstrip("-")
        n = seen_keys.get(full_base, 0)
        seen_keys[full_base] = n + 1
        if n == 0:
            pk = full_base
        else:
            suffix = f"-{n + 1}"
            pk = (full_base[: PRODUCT_KEY_MAX - len(suffix)] + suffix).rstrip("-")
        name_to_pk[norm] = pk
        return pk

    uploaded = failed = skipped = 0
    seen_photos: set[str] = set()  # dedupe by photoKey (one image per dish)
    items_processed = 0
    for menu in payload.get("menus", []):
        for it in menu.get("items", []):
            if limit and items_processed >= limit:
                break
            items_processed += 1
            img = (it.get("imageUrl") or "").strip()
            raw_name = (it.get("name") or "").strip()
            if not img or not raw_name:
                continue
            name = clean_name(raw_name)
            if not name:
                continue
            pk = pk_for(name)
            photo_key = f"{pk}-1"
            if photo_key in seen_photos:
                continue
            seen_photos.add(photo_key)
            try:
                # Download image. Disable basic auth for CDN
                # downloads (boosteat S3 returns 400 on
                # "Unsupported Authorization Type").
                status_code, data, _ = _request(img, timeout=30, auth=False)
                if status_code != 200 or not data:
                    failed += 1
                    print(f"  photo FAIL download {img}: HTTP {status_code}")
                    continue
                # Detect mime from magic bytes (the URL extension is
                # often misleading; the Qubs CDN serves .jpg URLs that
                # are actually PNG).
                mime = "image/jpeg"
                if data[:8] == b"\x89PNG\r\n\x1a\n":
                    mime = "image/png"
                elif data[:4] == b"RIFF" and data[8:12] == b"WEBP":
                    mime = "image/webp"
                elif data[:3] == b"GIF":
                    mime = "image/gif"
                # POST as multipart
                boundary = "----PhotoBoundary"
                body = b""
                body += f"--{boundary}\r\n".encode()
                body += (f'Content-Disposition: form-data; name="file"; '
                         f'filename="photo.jpg"\r\n').encode()
                body += f"Content-Type: {mime}\r\n\r\n".encode()
                body += data
                body += b"\r\n"
                for fname, fval in [
                    ("restaurantKey", rk),
                    ("productKey", pk),
                    ("altText", name),
                    # Mark the first (and usually only) photo per product as
                    # primary so the public API's hasPhoto check picks it up.
                    # The script dedupes by (productKey, -1) so we only ever
                    # upload one photo per product.
                    ("isPrimary", "true"),
                    ("sourceType", "GOOGLE_PROTOTYPE"),
                ]:
                    body += f"--{boundary}\r\n".encode()
                    body += (f'Content-Disposition: form-data; name="{fname}"\r\n'
                             f'\r\n{fval}\r\n').encode()
                body += f"--{boundary}--\r\n".encode()
                qs = urllib.parse.urlencode({"actor": "import_from_scrape.py"})
                st, raw, _ = _request(
                    f"{base}/admin/api/photos?{qs}", method="POST", body=body,
                    headers={"Content-Type":
                             f"multipart/form-data; boundary={boundary}"},
                    user=user, password=password)
                if 200 <= st < 300:
                    uploaded += 1
                    if uploaded % 20 == 0:
                        print(f"  photos: {uploaded} uploaded so far")
                else:
                    failed += 1
                    print(f"  photo FAIL upload {pk}: HTTP {st} {(raw or b'')[:200].decode('utf-8', errors='replace')}")
            except Exception as e:
                failed += 1
                print(f"  photo FAIL {pk}: {e}")
        if limit and items_processed >= limit:
            break
    return uploaded, failed, skipped


# --------------------------------------------------------------- main


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("scrape_json", type=Path)
    p.add_argument("--base", default=DEFAULT_BASE)
    p.add_argument("--user", default=DEFAULT_USER)
    p.add_argument("--password", default=DEFAULT_PASSWORD)
    p.add_argument("--no-dry-run", action="store_true")
    p.add_argument("--wipe", action="store_true")
    p.add_argument("--upload-photos", action="store_true",
                   help="After the CSV import, download each item's "
                        "imageUrl and POST to /admin/api/photos.")
    p.add_argument("--photo-limit", type=int, default=None,
                   help="If set, only upload the first N item images.")
    p.add_argument("--out-dir", type=Path, default=DEFAULT_OUT_DIR)
    args = p.parse_args()

    if not args.scrape_json.exists():
        print(f"ERROR: scrape file not found: {args.scrape_json}", file=sys.stderr)
        return 1

    payload = json.loads(args.scrape_json.read_text(encoding="utf-8"))
    rk = payload["restaurantKey"]
    total_items = sum(len(m.get("items", [])) for m in payload.get("menus", []))
    total_imgs = sum(
        1 for m in payload.get("menus", []) for it in m.get("items", [])
        if (it.get("imageUrl") or "").strip())
    print(f"== {payload['restaurantName']} ({rk}): "
          f"{len(payload['menus'])} menus, {total_items} items, "
          f"{total_imgs} with images ==")

    args.out_dir.mkdir(parents=True, exist_ok=True)
    paths = build_csvs(payload, args.out_dir)
    for slug, p_ in paths.items():
        line_count = sum(1 for _ in p_.open(encoding="utf-8")) - 1
        print(f"   {slug}.csv: {line_count} rows -> {p_}")

    if args.wipe:
        print("== wiping existing restaurant ==")
        wipe_restaurant(args.base, rk, args.user, args.password)

    print("== posting CSVs ==")
    dry_run = not args.no_dry_run
    for slug in ["restaurants", "menus", "products", "menu-items", "ingredients"]:
        result = post_csv(args.base, slug, paths[slug],
                          args.user, args.password, dry_run=dry_run)
        st = result.get("_status", 0)
        error_count = result.get("errorCount", 0)
        inserted = result.get("inserted", 0)
        updated = result.get("updated", 0)
        ok = result.get("ok", True)
        if 200 <= st < 300 and ok and error_count == 0:
            print(f"  {slug}: HTTP {st} inserted={inserted} "
                  f"updated={updated} errors={error_count}")
        else:
            print(f"  {slug}: HTTP {st} FAIL ok={ok} "
                  f"inserted={inserted} updated={updated} "
                  f"errors={error_count}")
            print(f"    {json.dumps(result.get('errors', []))[:600]}")
            if not dry_run:
                return 1

    if args.upload_photos and not dry_run:
        print(f"== uploading photos (limit={args.photo_limit}) ==")
        up, fail, skip = upload_photos(
            args.base, payload, args.user, args.password,
            limit=args.photo_limit)
        print(f"  photos: {up} uploaded, {fail} failed, {skip} skipped")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
