#!/usr/bin/env node
/**
 * Scrape Tortelli Pasta Bar (https://comanda.tortelli.ro/).
 *
 * The site uses the Boosteat platform. Menu is split across many
 * category pages, e.g. /al-forno/715/90, /wine-sparkling/1358/90.
 * Each page is a flat list of `.be-product-list-card` items.
 *
 * Strategy:
 *   1) Visit one known category page to discover the sidebar links
 *      to all categories.
 *   2) Visit each category page (excluding "Tricouri" which is
 *      merchandise) and extract products.
 *   3) Emit a JSON in the canonical scrape format consumed by
 *      bin/import_from_scrape.py — one menu per category page.
 *
 * Usage:
 *   bin/scrape_tortelli.mjs --out /tmp/tortelli.json
 */
import puppeteer from 'puppeteer-core';
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';

const START_URL = 'https://comanda.tortelli.ro/wine-sparkling/1358/90';
const BASE = 'https://comanda.tortelli.ro';
const SKIP_SLUGS = new Set(['tricouri']);

const RESTAURANT = {
  restaurantKey: 'tortelli',
  restaurantName: 'Tortelli Pasta Bar',
  address: 'Strada Memorandumului 12, Cluj-Napoca',
  city: 'Cluj-Napoca',
  phone: '+40 264 123 456',
  website: 'https://tortelli.ro',
  cuisine: 'italian',
  defaultTags: ['italian', 'pasta'],
};

const PRICE_RE = /(\d+(?:[.,]\d+)?)\s*(lei|ron|€|eur|euro)/i;
const WEIGHT_RE = /\/\s*(\d+)\s*(g|gr|kg|ml|l|cl)\b/i;

function cleanName(raw) {
  if (!raw) return '';
  return raw
    .replace(/\s+/g, ' ')
    .replace(/\s+NEW\s*$/i, '') // strip the "NEW" badge suffix
    .trim();
}

function parsePrice(raw) {
  if (!raw) return null;
  const m = raw.match(PRICE_RE);
  if (!m) return null;
  return parseFloat(m[1].replace(',', '.'));
}

function parseCurrency(raw) {
  if (!raw) return 'RON';
  if (/lei|ron/i.test(raw)) return 'RON';
  if (/€|eur|euro/i.test(raw)) return 'EUR';
  return 'RON';
}

function parseWeight(raw) {
  if (!raw) return null;
  const m = raw.match(WEIGHT_RE);
  if (!m) return null;
  const unit = m[2].toLowerCase();
  // ml/cl/l: store as weight_grams=null but expose the original
  // text. We only convert g/kg to grams.
  if (['ml', 'cl', 'l'].includes(unit)) return null;
  let grams = parseInt(m[1], 10);
  if (unit === 'kg') grams *= 1000;
  return grams;
}

function extractImageUrl(src) {
  if (!src) return null;
  // Boosteat serves the same image at multiple sizes; pick the
  // /res1000/ variant for ~50-100KB full quality.
  if (/\/res\d+(\.[a-z]+)$/i.test(src)) {
    return src.replace(/\/res\d+(\.[a-z]+)$/i, '/res1000$1');
  }
  return src;
}

async function discoverCategories(page) {
  // Visit the start URL and read the sidebar links.
  await page.goto(START_URL, { waitUntil: 'networkidle0', timeout: 60000 });
  await new Promise(r => setTimeout(r, 1500));
  const links = await page.evaluate(() => {
    const all = Array.from(document.querySelectorAll('a[href]'));
    const cat = all.filter((a) => {
      const h = a.getAttribute('href') || '';
      return /^\/[a-z][a-z\-]+\/\d+\/\d+/.test(h);
    });
    return cat.map((a) => ({
      href: a.getAttribute('href'),
      text: a.textContent?.trim() || '',
    }));
  });
  // Dedupe, prefer the first-seen label per URL
  const seen = new Map();
  for (const l of links) {
    if (!seen.has(l.href)) seen.set(l.href, l.text);
  }
  // Filter to the actual menu categories
  const out = [];
  for (const [href, text] of seen) {
    const slug = href.split('/')[1];
    if (SKIP_SLUGS.has(slug)) continue;
    out.push({ name: text || slug, url: BASE + href });
  }
  return out;
}

async function scrapeCategory(browser, cat) {
  const page = await browser.newPage();
  await page.setViewport({ width: 1920, height: 5000 });
  await page.goto(cat.url, { waitUntil: 'networkidle0', timeout: 60000 });
  await new Promise(r => setTimeout(r, 1500));
  const raw = await page.evaluate(() => {
    const cards = document.querySelectorAll('.be-product-list-card');
    return Array.from(cards).map((c) => {
      // Skip the "no photo" placeholder card. The site uses a
      // shared placeholder image when a product has no real photo;
      // we don't want to upload that.
      const img = c.querySelector('img');
      const src = img?.getAttribute('src') || null;
      const isPlaceholder = src && /no-photo/i.test(src);
      return {
        name: c.querySelector('.be-product-name, h3, h4')?.textContent?.trim() || '',
        price: c.querySelector('.be-product-price, [class*="price"]')?.textContent?.trim() || null,
        desc: c.querySelector('.be-product-description, [class*="description"]')?.textContent?.trim() || null,
        img: isPlaceholder ? null : src,
      };
    });
  });
  await page.close();
  return raw;
}

function buildMenu(cat, raw) {
  const out = { name: cat.name, url: cat.url, items: [] };
  for (const r of raw) {
    const name = cleanName(r.name);
    if (!name) continue;
    const price = parsePrice(r.price);
    const weight_grams = parseWeight(r.price);
    out.items.push({
      name,
      price,
      currency: parseCurrency(r.price),
      weight_text: weight_grams ? `${weight_grams} g` :
        (r.price?.includes('ml') || r.price?.includes('cl') ? r.price.split('/').pop()?.trim() || '' : ''),
      weight_grams,
      description: (r.desc || '').replace(/\s+/g, ' ').trim(),
      section: cat.name,
      imageUrl: extractImageUrl(r.img),
    });
  }
  return out;
}

async function main() {
  const args = process.argv.slice(2);
  let outPath = '/tmp/tortelli-scrape.json';
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
    const discovery = await browser.newPage();
    await discovery.setViewport({ width: 1920, height: 5000 });
    const categories = await discoverCategories(discovery);
    await discovery.close();
    console.error(`discovered ${categories.length} categories`);

    const menus = [];
    for (const cat of categories) {
      const raw = await scrapeCategory(browser, cat);
      const menu = buildMenu(cat, raw);
      menus.push(menu);
      console.error(`  ${cat.name}: ${menu.items.length} items`);
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
