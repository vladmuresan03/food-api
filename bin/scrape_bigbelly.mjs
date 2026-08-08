#!/usr/bin/env node
/**
 * Scrape Big Belly Cluj (https://bigbelly.ro/).
 *
 * The site has 16 product categories, each at a dedicated URL:
 *   /produse/meniuri, /produse/sosuri, /produse/garnituri, etc.
 *
 * The product card DOM is:
 *   <div class="card col-md-6">              ← one card per product
 *     <div class="row product-content">
 *       <div class="col-lg-3 ..."> <img/></div>
 *       <div class="col-lg-7 ...">
 *         <h3>Sos Avocado</h3>              ← name
 *         <p class="weight">70g</p>         ← weight
 *         <p class="description">...</p>    ← description (optional)
 *         <b>5.00 Lei</b>                  ← price
 *
 * Emits canonical JSON in the same shape as bin/scrape_*.mjs for
 * use with bin/import_from_scrape.py.
 *
 * Usage:
 *   bin/scrape_bigbelly.mjs --out /tmp/bigbelly.json
 *   bin/scrape_bigbelly.mjs --restaurant big-belly-fabricii
 *                             --out /tmp/bb-fabricii.json
 */
import puppeteer from 'puppeteer-core';
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const BASE = 'https://bigbelly.ro';

// Category → URL slug + section name (matches the food-api product
// category column for big-belly-manastur / big-belly-fabricii).
// Note: "Pasta, Risotto & Lasagna" is the live name on the site but
// we use the shorter "Paste & Lasagna" to match the existing prod
// category column.
const CATEGORIES = [
  { slug: 'meniuri',                name: 'Meniuri',                section: 'Meniuri' },
  { slug: 'paste-risotto-lasagna',  name: 'Paste, Risotto & Lasagna', section: 'Paste & Lasagna' },
  { slug: 'shaorma-si-sandwichuri', name: 'Shaorma & Sandwichuri',  section: 'Shaorma & Sandwichuri' },
  { slug: 'salate',                 name: 'Salate',                  section: 'Salate' },
  { slug: 'burgeri',                name: 'Burgeri',                 section: 'Burgeri' },
  { slug: 'garnituri',              name: 'Garnituri',               section: 'Garnituri' },
  { slug: 'sosuri',                 name: 'Sosuri',                  section: 'Sosuri' },
  { slug: 'supe-crema',             name: 'Supe Crema',              section: 'Supe & Ciorbe' },
  { slug: 'desert',                 name: 'Desert',                  section: 'Desert' },
  { slug: 'pizza',                  name: 'Pizza',                   section: 'Pizza' },
  { slug: 'pizza-mica',             name: 'Pizza mica',              section: 'Pizza' },
  { slug: 'portii',                 name: 'Portii',                  section: 'Burgeri' }, // "Porții" = portions/hearty dishes
  { slug: 'box-combo',              name: 'Box combo',               section: 'Meniuri' },
  { slug: 'platter',                name: 'Platter',                 section: 'Burgeri' },
  { slug: 'meniuri-kids',           name: 'Kids menu',               section: 'Meniuri' },
  { slug: 'racoritoare',            name: 'Bauturi Racoritoare',     section: 'Băuturi' },
];

function getRestaurantMeta(name) {
  if (name === 'big-belly-fabricii') {
    return {
      restaurantKey: 'big-belly-fabricii',
      restaurantName: 'Big Belly Fabricii',
      address: 'Strada Fabricii nr. 2, Cluj-Napoca',
      city: 'Cluj-Napoca',
      phone: '+40 740 808 207',
      website: 'https://bigbelly.ro/',
      cuisine: 'gastropub',
      defaultTags: ['gastropub'],
    };
  }
  // default = manastur
  return {
    restaurantKey: 'big-belly-manastur',
    restaurantName: 'Big Belly Mănăștur',
    address: 'Strada Primăverii nr. 8, Cluj-Napoca',
    city: 'Cluj-Napoca',
    phone: '+40 754 234 235',
    website: 'https://bigbelly.ro/',
    cuisine: 'gastropub',
    defaultTags: ['gastropub'],
  };
}

function cleanName(s) {
  if (!s) return '';
  return s.replace(/\s+/g, ' ').trim();
}

function parsePrice(s) {
  if (!s) return null;
  const m = s.match(/(\d+(?:[.,]\d+)?)/);
  return m ? parseFloat(m[1].replace(',', '.')) : null;
}

function parseWeight(s) {
  if (!s) return { grams: null, text: '' };
  const clean = s.replace(/\s+/g, ' ').trim();
  // Pure "200g" / "200 g" / "200 gr" → 200 g
  const m = clean.match(/^(\d+)\s*(g|gr|kg|ml|l)\b/i);
  if (!m) return { grams: null, text: '' };
  let grams = parseInt(m[1], 10);
  if (m[2].toLowerCase() === 'kg') grams *= 1000;
  return {
    grams: ['ml', 'l'].includes(m[2].toLowerCase()) ? null : grams,
    text: `${grams} g`,
  };
}

async function scrapeCategory(browser, cat) {
  const page = await browser.newPage();
  await page.setViewport({ width: 1920, height: 5000 });
  const url = `${BASE}/produse/${cat.slug}`;
  await page.goto(url, { waitUntil: 'networkidle0', timeout: 60000 });
  await new Promise(r => setTimeout(r, 2000));
  // Scroll to the bottom to trigger lazy loading.
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  await new Promise(r => setTimeout(r, 1500));
  await page.evaluate(() => window.scrollTo(0, 0));
  await new Promise(r => setTimeout(r, 500));

  const raw = await page.evaluate(() => {
    const cards = document.querySelectorAll('.card.col-md-6');
    return Array.from(cards).map((c) => {
      // The site uses Bootstrap card layout with custom
      // class names. Selectors are specific to bigbelly.ro.
      const name = c.querySelector('.ProductTitle a, .ProductTitle')?.textContent?.trim() || '';
      const weight = c.querySelector('.ProdDetails')?.textContent?.trim() || '';
      const desc = c.querySelector('.ProdDescription, .product-description, [class*="Description"]:not(.ProductTitle)')?.textContent?.trim() || '';
      // The price is in a <b> tag with inline color — no class.
      // We find it by content (contains "Lei").
      const price = Array.from(c.querySelectorAll('b'))
        .map((b) => b.textContent?.trim() || '')
        .find((t) => /lei/i.test(t)) || '';
      const img = c.querySelector('img.ProdImg, img')?.getAttribute('src') || null;
      return { name, weight, desc, price, img };
    });
  });
  await page.close();
  return raw;
}

function buildMenu(cat, raw) {
  const out = { name: cat.name, url: `${BASE}/produse/${cat.slug}`, items: [] };
  for (const r of raw) {
    const name = cleanName(r.name);
    if (!name) continue;
    const price = parsePrice(r.price);
    const w = parseWeight(r.weight);
    out.items.push({
      name,
      price,
      currency: 'RON',
      weight_text: w.text,
      weight_grams: w.grams,
      description: (r.desc || '').replace(/\s+/g, ' ').trim(),
      section: cat.section,
      // Skip bigbelly.ro's own CDN images for now — they use
      // res.cloudinary.com URLs which we can fetch, but a more
      // thorough pass would download them. The existing photos
      // from food-crawler cover the most prominent products.
      imageUrl: '',
    });
  }
  return out;
}

async function main() {
  const args = process.argv.slice(2);
  let outPath = '/tmp/bigbelly-scrape.json';
  let restaurant = 'big-belly-manastur';
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--out' && args[i + 1]) outPath = args[++i];
    if (args[i] === '--restaurant' && args[i + 1]) restaurant = args[++i];
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
        console.error(`  ${cat.name}: ${menu.items.length} items`);
      } catch (e) {
        console.error(`  ${cat.name}: FAIL ${e.message?.slice(0, 80)}`);
        menus.push({ name: cat.name, url: `${BASE}/produse/${cat.slug}`, items: [] });
      }
    }
    const total = menus.reduce((s, m) => s + m.items.length, 0);
    const payload = { ...getRestaurantMeta(restaurant), menus };
    writeFileSync(outPath, JSON.stringify(payload, null, 2), 'utf-8');
    console.error(`wrote ${outPath}: ${menus.length} menus, ${total} products`);
  } finally {
    await browser.close();
  }
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
