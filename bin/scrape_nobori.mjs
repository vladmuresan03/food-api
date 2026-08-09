#!/usr/bin/env node
/**
 * Scrape Nobori (https://nobori.ro/) — WooCommerce on Divi theme.
 *
 * Nobori has 15 product categories, each at a dedicated URL under
 *   /product-category/<slug>/
 * The site's /meniu/ page is just a hub of buttons linking to these
 * category pages — see the button hrefs like
 *   /product-category/sushi/nigiri/, /product-category/ramen_supe/, etc.
 *
 * The WooCommerce product list uses the standard `.products li.product`
 * markup, with title in `h2.woocommerce-loop-product__title` and price
 * in `.price`. Images are full-size in the `.attachment-woocommerce_thumbnail`
 * (or `.wp-post-image`) src.
 *
 * Some categories have multiple pages — the scraper follows the
 * `.page-numbers a.next` link until exhausted.
 *
 * Emits canonical JSON in the same shape as bin/scrape_*.mjs for
 * use with bin/import_from_scrape.py.
 *
 * Usage:
 *   bin/scrape_nobori.mjs --out /tmp/nobori.json
 */
import puppeteer from 'puppeteer-core';
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const BASE = 'https://nobori.ro';

// Category → URL slug + section name (matches the food-api product
// category column for nobori).
const CATEGORIES = [
  { slug: 'sushi/nigiri',         name: 'Sushi Nigiri',         section: 'Sushi' },
  { slug: 'sushi/uramaki',        name: 'Sushi Uramaki',        section: 'Sushi' },
  { slug: 'sushi/mix_plates',     name: 'Sushi Plates',         section: 'Sushi' },
  { slug: 'sushi/maki',           name: 'Sushi Maki',          section: 'Sushi' },
  { slug: 'sushi/specialities',   name: 'Sushi Specialities',   section: 'Sushi' },
  { slug: 'sashimi',              name: 'Sashimi',             section: 'Sashimi' },
  { slug: 'ramen_supe',           name: 'Ramen & Soup',        section: 'Ramen & Supe' },
  { slug: 'main_dish_veggielicious', name: 'Main Dish Veggie', section: 'Main Dish' },
  { slug: 'main_dish_meat_lover',    name: 'Main Dish Meat',  section: 'Main Dish' },
  { slug: 'taitei_orez',          name: 'Noodles & Rice',      section: 'Noodles & Rice' },
  { slug: 'asian_burger',         name: 'Noborgers',           section: 'Burgeri' },
  { slug: 'bento',                name: 'Business Lunch',      section: 'Meniuri' },
  { slug: 'sosuri',               name: 'Sosuri',              section: 'Sosuri' },
  { slug: 'desert',               name: 'Desert',              section: 'Desert' },
  { slug: 'bauturi',              name: 'Băuturi',             section: 'Băuturi' },
];

const RESTAURANT = {
  restaurantKey: 'nobori',
  restaurantName: 'Nobori',
  address: 'Strada Plopilor nr. 62, Cluj-Napoca',
  city: 'Cluj-Napoca',
  phone: '+40 749 066 193',
  website: 'https://nobori.ro/',
  cuisine: 'japanese',
  defaultTags: ['japanese', 'asian'],
};

// Match "45,00 lei" / "45 lei" / "45.00 LEI" — handles both Romanian
// decimal comma and English decimal dot.
const PRICE_RE = /(\d+(?:[.,]\d+)?)\s*(lei|ron|lei|RON)\b/i;

function cleanName(s) {
  if (!s) return '';
  return s.replace(/\s+/g, ' ').trim();
}

function parsePrice(s) {
  if (!s) return null;
  // Remove currency suffix and any "/weight" suffix
  const cleaned = s.replace(/lei|ron/gi, '').replace(/\s*\/\s*\d+\s*(g|gr|kg|ml|l)\b/i, '').trim();
  const m = cleaned.match(/(\d+(?:[.,]\d+)?)/);
  if (!m) return null;
  return parseFloat(m[1].replace(',', '.'));
}

function upgradeImage(src) {
  if (!src) return null;
  // WC thumbs are typically 300x300. If we see a -300x300 suffix, swap
  // to -scaled or the full original by stripping the size constraint.
  if (/-\d+x\d+\.(jpg|jpeg|png|webp)$/i.test(src)) {
    return src.replace(/-\d+x\d+\.(jpg|jpeg|png|webp)$/i, '.$1');
  }
  return src;
}

async function scrapeCategoryPage(page) {
  return page.evaluate(() => {
    const cards = document.querySelectorAll('.products li.product, .woocommerce ul.products li.product');
    return Array.from(cards).map((c) => {
      const name = c.querySelector('h2.woocommerce-loop-product__title, .woocommerce-loop-product__title')?.textContent?.trim() || '';
      const price = c.querySelector('.price')?.textContent?.trim() || '';
      // Try the woocommerce thumbnail first (largest available), then
      // the wp-post-image, then any img.
      const img = c.querySelector('img.attachment-woocommerce_thumbnail, img.wp-post-image, img')?.getAttribute('src') || null;
      // Some categories have "from 23 lei" — keep that as the price.
      return { name, price, img };
    });
  });
}

async function scrapeCategory(browser, cat) {
  const page = await browser.newPage();
  await page.setViewport({ width: 1920, height: 5000 });
  let allItems = [];
  let pageNum = 1;
  let nextUrl = `${BASE}/product-category/${cat.slug}/`;
  const visited = new Set();

  while (nextUrl && !visited.has(nextUrl)) {
    visited.add(nextUrl);
    try {
      await page.goto(nextUrl, { waitUntil: 'networkidle0', timeout: 60000 });
      await new Promise(r => setTimeout(r, 1500));
      // Scroll to bottom to trigger any lazy loading
      await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
      await new Promise(r => setTimeout(r, 1000));
      const items = await scrapeCategoryPage(page);
      console.error(`  ${cat.slug} page ${pageNum}: ${items.length} items`);
      allItems = allItems.concat(items);

      // Look for next-page link
      const nextHref = await page.evaluate(() => {
        const next = document.querySelector('.page-numbers a.next, a.next.page-numbers, .woocommerce-pagination a.next, .nav-previous a, a[rel="next"]');
        return next?.getAttribute('href') || null;
      });
      if (nextHref) {
        nextUrl = nextHref;
        pageNum++;
      } else {
        nextUrl = null;
      }
    } catch (e) {
      console.error(`  ${cat.slug} page ${pageNum}: FAIL ${e.message?.slice(0, 80)}`);
      nextUrl = null;
    }
  }
  await page.close();
  return allItems;
}

function buildMenu(cat, raw) {
  const out = { name: cat.name, url: `${BASE}/product-category/${cat.slug}/`, items: [] };
  for (const r of raw) {
    const name = cleanName(r.name);
    if (!name) continue;
    const price = parsePrice(r.price);
    out.items.push({
      name,
      price,
      currency: 'RON',
      weight_text: '',
      weight_grams: null,
      description: '',
      section: cat.section,
      imageUrl: upgradeImage(r.img) || '',
    });
  }
  return out;
}

async function main() {
  const args = process.argv.slice(2);
  let outPath = '/tmp/nobori-scrape.json';
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--out' && args[i + 1]) outPath = args[++i];
  }
  mkdirSync(dirname(outPath), { recursive: true });

  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: 'new',
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--window-size=1920,5000'],
  });
  try {
    const menus = [];
    for (const cat of CATEGORIES) {
      try {
        const raw = await scrapeCategory(browser, cat);
        const menu = buildMenu(cat, raw);
        menus.push(menu);
        console.error(`  ${cat.name}: ${menu.items.length} products`);
      } catch (e) {
        console.error(`  ${cat.name}: FAIL ${e.message?.slice(0, 80)}`);
        menus.push({ name: cat.name, url: `${BASE}/product-category/${cat.slug}/`, items: [] });
      }
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
