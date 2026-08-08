#!/usr/bin/env node
/**
 * Scrape My Thai (https://mythai.ro/) — Elementor Price-List pages.
 *
 * The site has two main menu pages:
 *   - https://mythai.ro/meniu-mancare/   (food: 100+ items)
 *   - https://mythai.ro/bauturi         (drinks: 120+ items)
 *
 * The DOM structure is:
 *   - Each product is <li class="elementor-price-list-item"> with:
 *       .elementor-price-list-title  (name, sometimes with <br>)
 *       .elementor-price-list-price  (price + currency, e.g. "38 LEI")
 *       .elementor-price-list-description (free text + ingredients)
 *       <img> (optional, full URL in src)
 *   - Section headers are also <li> items with title but NO price and
 *     NO description (e.g. "STARTERS:", "SUPE", "FEL PRINCIPAL").
 *   - On the bauturi page there are no inline section markers; the
 *     section is the page itself (we emit one "Băuturi" menu).
 *
 * Output: JSON in the canonical scrape format consumed by
 *         bin/import_from_scrape.py.
 *
 * Usage:
 *   bin/scrape_mythai.mjs --out /tmp/mythai.json
 */
import puppeteer from 'puppeteer-core';
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';

const PAGES = [
  { name: 'Meniu mâncare', url: 'https://mythai.ro/meniu-mancare/' },
  { name: 'Băuturi',      url: 'https://mythai.ro/bauturi' },
];

const RESTAURANT = {
  restaurantKey: 'my-thai',
  restaurantName: 'My Thai',
  address: 'Strada Napoca 12, Cluj-Napoca',
  city: 'Cluj-Napoca',
  phone: '+40 264 123 456',
  website: 'https://mythai.ro',
  cuisine: 'thai',
  defaultTags: ['thai', 'asian'],
};

const PRICE_RE = /(\d+(?:[.,]\d+)?)\s*(LEI|LEI|RON|lei|ron)/i;
const WEIGHT_RE = /(\d+)\s*(g|gr|kg|ml|l)\b/i;

// Section labels that appear as prefixes to product titles on
// mythai.ro (all caps, may end with ":"). Stripped from the title
// when cleaning. The current section is tracked separately so the
// label is preserved in the section field.
const SECTION_PREFIXES = [
  'STARTERS', 'APERITIVE', 'SUPE', 'FEL PRINCIPAL', 'MENIU',
  'SOPES', 'PREPARATE', 'DESERT', 'GARNITURI', 'SOSURI',
  'PESTE', 'CURRY', 'WOK', 'OREZ', 'FRUCTE',
  'COMBO', 'BĂUTURI',
];

function cleanTitle(raw, options = {}) {
  if (!raw) return '';
  // Collapse <br> variants
  let s = raw
    .replace(/<br\s*\/?>/gi, ' / ')
    .replace(/&nbsp;/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
  // Strip weight-only titles ("600 g" for combo)
  if (/^\d+\s*(g|gr|kg|ml|l)\s*$/i.test(s)) return '';
  // Strip weight that bled to the end of a real title ("Pad Thai 300g")
  s = s.replace(/\s+\d+\s*g(r)?\s*$/i, '');
  // Strip leading section prefix like "STARTERS:" or "FEL PRINCIPAL"
  for (const pfx of SECTION_PREFIXES) {
    const re = new RegExp(`^${pfx}\\s*:?\\s*`, 'i');
    if (re.test(s)) {
      s = s.replace(re, '').trim();
      // Remember that this title is a section marker
      options._wasSectionPrefix = true;
      break;
    }
  }
  if (!s) return '';

  // The site uses these patterns for bilingual titles:
  //   "ROMANIAN<sep>ENGLISH" where <sep> is one of:
  //     - " / "   (slash with spaces)
  //     - "- "    (dash + space)
  //     - " -"    (space + dash)
  //     - "/ "    (slash + space)
  //   The English part is typically ALL CAPS and Latin-only (no
  //   diacritics). The Romanian part may also have a " / " sub-
  //   separator (e.g. "VEGETARIENE / CREVEȚI" = "vegetarian / shrimp"),
  //   which we want to keep.

  // 1) Strip trailing English after " / " — but only if the second
  //    half is ALL CAPS Latin (no diacritics). This handles
  //    "X / Y" where Y is the English name.
  if (s.includes(' / ')) {
    const idx = s.lastIndexOf(' / ');
    const before = s.slice(0, idx).trim();
    const after = s.slice(idx + 3).trim();
    const isLatinUpper = /^[A-Z][A-Z0-9'\-\s]*$/.test(after) && /[A-Z]/.test(after);
    if (isLatinUpper && after.length >= 3) {
      s = before;
    } else {
      // Keep the part before " / " (it's the canonical name) and
      // drop the parenthetical alt.
      s = before;
    }
  }

  // 2) Strip trailing English after " - " or "- " (last separator)
  for (const sep of [' - ', '- ', ' /']) {
    const idx = s.lastIndexOf(sep);
    if (idx < 0) continue;
    const before = s.slice(0, idx).trim();
    const after = s.slice(idx + sep.length).trim();
    // The English part should be 2+ all-upper Latin tokens and have
    // no diacritics.
    const tokens = after.split(/\s+/);
    const isLatinUpper = tokens.length >= 2 &&
      tokens.every((t) => /^[A-Z][A-Z0-9'\-]*$/.test(t));
    if (isLatinUpper && /[A-Z]/.test(after)) {
      s = before;
      break;
    }
  }

  // 3) Heuristic: if the title has diacritics, look for a chunk
  //    of 2+ ASCII-uppercase Latin tokens at the END and cut
  //    before them.
  if (/[ĂÂÎȘȚăâîșț]/.test(s)) {
    const tokens = s.split(/\s+/);
    let cutAt = tokens.length;
    for (let i = tokens.length - 1; i >= 0; i--) {
      const t = tokens[i];
      if (/^[A-Z][A-Z0-9'\-]{2,}$/.test(t)) {
        // This token looks like an English word; keep going
        // backwards to find the start of the English chunk.
        let j = i;
        while (j > 0 && /^[A-Z][A-Z0-9'\-]+$/.test(tokens[j - 1])) j--;
        // Only cut if the English chunk is 2+ tokens AND there's
        // a clear boundary (e.g. a non-uppercase token or a
        // separator before j).
        if (i - j + 1 >= 2 && j > 0) {
          cutAt = j;
          break;
        }
      } else {
        break;
      }
    }
    if (cutAt < tokens.length) {
      s = tokens.slice(0, cutAt).join(' ');
    }
  }

  // Strip trailing diacritics / punctuation
  s = s.replace(/[\s\-_:]+$/, '').trim();
  return s;
}

function parsePrice(raw) {
  if (!raw) return null;
  const m = raw.match(PRICE_RE);
  if (!m) return null;
  return parseFloat(m[1].replace(',', '.'));
}

function parseCurrency(raw) {
  if (!raw) return 'RON';
  if (/LEI/i.test(raw)) return 'RON';
  if (/RON/i.test(raw)) return 'RON';
  return 'RON';
}

function parseWeightFromDescription(desc) {
  if (!desc) return null;
  // Strip the "Alergeni: ..." segment first so its digits (e.g.
  // "1, 2, 3") don't trip the weight regex. We only strip the
  // numeric allergen code after the label, not the rest of the
  // line — the site often concatenates the weight to the
  // allergen line, e.g. "Alergeni: 2 200g" means "2 (crustaceans)
  // and 200g weight".
  const clean = desc.replace(
    /\n?\s*Alergeni\s*:\s*\d+(?:\s*[,\.]\s*\d+)*\s*/gi, '').trim();
  // Prefer weight on its own line (the most common pattern: the
  // site lists ingredients, then on a new line shows the portion
  // weight like "200g" or "500 g").
  const lineMatch = clean.match(/(?:^|\n)\s*(\d+)\s*(g|gr|kg|ml|l)\s*$/i);
  const m = lineMatch || clean.match(WEIGHT_RE);
  if (!m) return null;
  let grams = parseInt(m[1], 10);
  if (m[2].toLowerCase() === 'kg') grams *= 1000;
  if (m[2].toLowerCase() === 'ml' || m[2].toLowerCase() === 'l') return null;
  // Sanity: reject obviously-wrong values (over 5kg or under 10g)
  if (grams > 5000 || grams < 10) return null;
  return grams;
}

function extractImageUrl(src) {
  if (!src) return null;
  // MyThai serves several sizes:
  //   -150x150.jpg: thumbnail (~37KB)
  //   -scaled.jpg: full size scaled to max dimension (~555KB)
  //   .jpg: original (~442KB)
  // The scaled variant is the best quality/size tradeoff.
  if (/-150x150\.(jpg|jpeg|png|webp)$/i.test(src)) {
    return src.replace(/-150x150\.(jpg|jpeg|png|webp)$/i, '-scaled.$1');
  }
  return src;
}

function cleanDescription(raw) {
  if (!raw) return '';
  return raw.replace(/\s+/g, ' ').trim();
}

async function scrapePage(browser, pageInfo) {
  const page = await browser.newPage();
  await page.setViewport({ width: 1920, height: 8000 });
  await page.goto(pageInfo.url, { waitUntil: 'networkidle0', timeout: 60000 });
  await new Promise(r => setTimeout(r, 2000));

  // Walk all top-level widgets in document order. When we see a
  // heading widget, that becomes the current section. When we see a
  // price-list widget, all its items are tagged with the current
  // section.
  const data = await page.evaluate(() => {
    const widgets = document.querySelectorAll('.elementor-widget');
    const items = [];
    let currentSection = null;
    for (const w of widgets) {
      if (w.classList.contains('elementor-widget-heading')) {
        const t = w.querySelector('.elementor-heading-title')?.textContent?.trim();
        if (t) currentSection = t;
      } else if (w.classList.contains('elementor-widget-price-list')) {
        const list = w.querySelectorAll('.elementor-price-list-item');
        for (const it of list) {
          items.push({
            section: currentSection,
            title: it.querySelector('.elementor-price-list-title')?.textContent?.trim() || '',
            price: it.querySelector('.elementor-price-list-price')?.textContent?.trim() || null,
            desc: it.querySelector('.elementor-price-list-description')?.textContent?.trim() || null,
            img: it.querySelector('img')?.getAttribute('src') || null,
          });
        }
      }
    }
    return items;
  });
  await page.close();
  return data;
}

function buildMenu(pageInfo, rawItems) {
  const out = { name: pageInfo.name, url: pageInfo.url, items: [] };
  let currentSection = pageInfo.name; // fallback when no heading seen
  for (const raw of rawItems) {
    // The page walker already attached the section from the nearest
    // heading widget. If the section is null (no heading yet), use
    // the page name as fallback.
    const section = (raw.section && raw.section.trim()) || pageInfo.name;
    const opts = {};
    const name = cleanTitle(raw.title, opts);
    if (!name) continue;
    const hasPrice = raw.price && parsePrice(raw.price) !== null;
    const hasDesc = raw.desc && cleanDescription(raw.desc).length > 3;
    // Skip items that are clearly section headers (title became a
    // section name, e.g. "SUPE" → "" after stripping "SUPE")
    if (opts._wasSectionPrefix && !hasDesc) continue;
    // Skip combo options (items inside the COMBO MENIU block have
    // no price of their own). We detect them as: section matches
    // a combo label AND no price.
    const isCombo = /combo/i.test(section) && !hasPrice;
    if (isCombo) continue;
    const price = parsePrice(raw.price);
    const desc = cleanDescription(raw.desc);
    const weight_grams = parseWeightFromDescription(desc);
    out.items.push({
      name,
      price,
      currency: parseCurrency(raw.price),
      weight_text: weight_grams ? `${weight_grams} g` : '',
      weight_grams,
      description: desc,
      section,
      imageUrl: extractImageUrl(raw.img),
    });
  }
  return out;
}

async function main() {
  const args = process.argv.slice(2);
  let outPath = '/tmp/mythai-scrape.json';
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--out' && args[i + 1]) outPath = args[++i];
  }
  mkdirSync(dirname(outPath), { recursive: true });

  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: 'new',
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--window-size=1920,8000'],
  });
  try {
    const menus = [];
    for (const pageInfo of PAGES) {
      console.error(`scraping ${pageInfo.url} ...`);
      const raw = await scrapePage(browser, pageInfo);
      console.error(`  raw items: ${raw.length}`);
      const menu = buildMenu(pageInfo, raw);
      console.error(`  products: ${menu.items.length}`);
      menus.push(menu);
    }
    const total = menus.reduce((s, m) => s + m.items.length, 0);
    const totalImgs = menus.reduce(
      (s, m) => s + m.items.filter((i) => i.imageUrl).length, 0);
    const payload = { ...RESTAURANT, menus };
    writeFileSync(outPath, JSON.stringify(payload, null, 2), 'utf-8');
    console.error(`wrote ${outPath}: ${menus.length} menus, ${total} products, ${totalImgs} with images`);
  } finally {
    await browser.close();
  }
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
