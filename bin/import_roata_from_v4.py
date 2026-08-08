#!/usr/bin/env python3
"""
One-off: import the Roata Făget menu from the v4 extraction JSON in
foodfinder-poc (last updated 2026-07-25).

The v4 JSON has 339 items across 12 menus/sections in a nested format
(menus[].sections[].items[]) with a slightly different shape than
scrape_jaxx.mjs produces. We translate it on the fly to the scrape
JSON format and reuse bin/import_from_scrape.py.

Key transformations:
  - One menu per "outer menu" in the v4 (e.g. "Meniu", "Băuturi")
  - Section is preserved on each item (e.g. "Mic Dejun", "Ciorbe")
  - Bilingual/multilingual names are reduced to the Romanian
    canonical form when present (nameRom); Hungarian/English is
    kept in description parenthetical for searchability.
  - Items without a parseable price are skipped (they're combo
    descriptions or section headers in the PDF).
  - Allergen codes from the description are forwarded to the
    ingredients extractor via the standard "Alergeni: N, M" suffix.
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path("/Users/vladm/dev/projects/food-api")
V4 = Path("/Users/vladm/dev/projects/foodfinder-poc/roata_extraction_v4.json")
SCRAPER = ROOT / "bin" / "import_from_scrape.py"

RESTAURANT_META = {
    "restaurantKey": "roata-faget",
    "restaurantName": "Roata Făget",
    "address": "Calea Turzii nr. 1, Cluj-Napoca",
    "city": "Cluj-Napoca",
    "phone": "+40264413299",
    "website": "https://www.roatafaget.ro/",
    "cuisine": "hungarian-traditional",
    "defaultTags": ["hungarian", "traditional"],
}


# Strip the trailing allergens tail so the importer's extract_allergens
# function can re-parse it. Format in v4: "  Alergeni: 1, 2  " or
# "  Allergens: 1, 2" or "  Alergén: 1, 2" (Hungarian).
ALLERGEN_TAIL_RE = re.compile(
    r"\s+(?:Alergeni|Alergén|Allergens|Allergen)\s*:\s*([0-9.,\s]+)\s*$",
    re.IGNORECASE,
)

# Strip weight like "300g" or "300 g" or "330 ml" from a free-text field.
WEIGHT_G_RE = re.compile(r"(\d+)\s*g\b", re.IGNORECASE)
WEIGHT_ML_RE = re.compile(r"(\d+)\s*ml\b", re.IGNORECASE)


def clean_name(name: str) -> str:
    if not name:
        return ""
    s = name.strip()
    # Strip asterisk markers ("*" for spicy, allergens, etc.)
    s = re.sub(r"\s*\*+\s*$", "", s).strip()
    # Strip trailing weight that bled into the name
    s = re.sub(r"\s+\d+\s*g(r)?\s*$", "", s, flags=re.IGNORECASE)
    return s.strip()


def parse_item(raw: dict) -> dict | None:
    """Translate one v4 item into the scrape shape. Returns None to
    skip items without a usable price or name."""
    name = raw.get("name") or raw.get("nameRom") or ""
    if not name:
        return None
    name = clean_name(name)
    if not name:
        return None

    price = raw.get("price")
    if not isinstance(price, (int, float)) or price <= 0:
        return None

    desc_raw = (raw.get("description") or "").strip()

    # Extract allergen code tail; preserve it in the description so
    # the generic importer can decode numeric codes (1=gluten, 2=
    # crustaceans, etc.) into EU allergen codes.
    m = ALLERGEN_TAIL_RE.search(desc_raw)
    allergens_text = ""
    if m:
        allergens_text = m.group(1).strip()
        desc_clean = desc_raw[: m.start()].rstrip()
    else:
        desc_clean = desc_raw

    # If the v4 source has separate nameRom / nameHun / nameEng fields,
    # surface the alternates in the description for search.
    alt = []
    for k in ("nameRom", "nameHun", "nameEng"):
        v = (raw.get(k) or "").strip()
        if v and v != name and v != raw.get("name"):
            alt.append(f"{k}={v}")
    if alt:
        alt_note = "  (also: " + "; ".join(alt) + ")"
        desc_clean = (desc_clean + alt_note).strip()

    # Re-attach the allergens tail so the importer can find it
    if allergens_text:
        desc_with_allergens = f"{desc_clean}\nAlergeni: {allergens_text}"
    else:
        desc_with_allergens = desc_clean

    weight_text = raw.get("weight") or ""
    weight_grams = None
    m_g = WEIGHT_G_RE.search(weight_text)
    if m_g:
        weight_grams = int(m_g.group(1))
    elif WEIGHT_ML_RE.search(weight_text):
        # Volume in ml; no grams, but keep the original text
        pass

    return {
        "name": name,
        "price": float(price),
        "currency": raw.get("currency") or "RON",
        "weight_text": weight_text.strip(),
        "weight_grams": weight_grams,
        "description": desc_with_allergens,
        "imageUrl": "",
    }


def main() -> int:
    if not V4.exists():
        print(f"ERROR: {V4} not found", file=sys.stderr)
        return 1
    data = json.loads(V4.read_text(encoding="utf-8"))
    menus_out = []
    total = 0
    skipped = 0
    for menu in data.get("menus", []):
        mname = menu.get("name") or menu.get("rawName") or "Meniu"
        # If the menu has only one section, treat the section's
        # items as the menu items directly (simpler menu_key).
        # If it has multiple sections, the section becomes the
        # sub-section in the menu_item CSV.
        items = []
        for sec in menu.get("sections", []):
            sname = sec.get("name") or sec.get("rawName") or mname
            for raw in sec.get("items", []):
                t = parse_item(raw)
                if t is None:
                    skipped += 1
                    continue
                t["section"] = sname
                items.append(t)
        menus_out.append({"name": mname, "items": items})
        total += len(items)
        print(f"  {mname}: {len(items)} items")

    payload = {
        **RESTAURANT_META,
        "scrapedAt": "2026-07-25T00:00:00Z",  # v4 extraction date
        "menus": menus_out,
    }
    print(f"\n== Total: {total} items (skipped {skipped}) across {len(menus_out)} menus ==")

    tmp = Path(tempfile.mkdtemp(prefix="roata-scrape-")) / "roata.json"
    tmp.parent.mkdir(parents=True, exist_ok=True)
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"== wrote scrape JSON: {tmp} ==")

    # Invoke the generic importer. Default to --no-dry-run so this
    # is a one-shot, but allow the user to pass --dry-run to skip
    # the actual POST (just write CSVs to the out dir).
    args = [str(SCRAPER), str(tmp)]
    if "--dry-run" in sys.argv:
        # We don't pass anything; the default is dry-run
        pass
    else:
        args.append("--no-dry-run")
    if "--wipe" in sys.argv:
        args.append("--wipe")
    if "--upload-photos" in sys.argv:
        args.append("--upload-photos")
    print("== running import_from_scrape.py ==")
    res = subprocess.run(args, cwd=str(ROOT))
    return res.returncode


if __name__ == "__main__":
    raise SystemExit(main())
