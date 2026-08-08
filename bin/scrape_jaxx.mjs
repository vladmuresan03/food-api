#!/usr/bin/env node
/**
 * Scrape the live Jaxx Gastropub menu (Cluj-Napoca) from qubs.app via a
 * headless browser. The Qubs app is a React-Router 7 SPA whose menu
 * data is only present after JavaScript hydrates, so plain HTTP fetch
 * gets an empty payload. Puppeteer waits for the rendered DOM, then
 * walks the menu item cards.
 *
 * Output: a JSON file on stdout (or to --out) shaped as
 *   {
 *     "restaurantKey": "jaxx",
 *     "restaurantName": "Jaxx Gastropub",
 *     "address": "Strada Emil Isac 25, Cluj-Napoca",
 *     "phone": "+40373811123",
 *     "website": "https://jaxxrestaurants.ro/",
 *     "menus": [
 *       { "name": "Kitchen", "items": [{ "name": "...", "price": 76, ... }] }
 *     ]
 *   }
 *
 * The `description` field is the full text the restaurant publishes,
 * which usually embeds an "Ingrediente:" line we'll split off into
 * `ingredients` later in the Python importer.
 */
import puppeteer from 'puppeteer-core';
import fs from 'node:fs';
import path from 'node:path';

const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';

// Each Qubs menu lives at a separate URL. UNTOLD is the landing page;
// Kitchen and Bar have their own public links the homepage links to.
const MENUS = [
    { name: 'UNTOLD Special Dishes', url: 'https://qubs.app/ro/menu/jaxx/menu' },
    { name: 'Kitchen',              url: 'https://qubs.app/ro/menu/c668cc80-71d2-44db-bd86-6572f4ef3ec0/menu' },
    { name: 'Bar',                  url: 'https://qubs.app/ro/menu/6253d7d1-21c0-49fe-9dc6-fd645abbce35/menu' },
];

const RESTAURANT_META = {
    restaurantKey: 'jaxx',
    restaurantName: 'Jaxx Gastropub',
    website: 'https://jaxxrestaurants.ro/',
    // The site is published under the city key 'jaxx-cluj'. Maps URL is in
    // the rendered payload but we just hardcode the address we already know
    // from the food-crawler photos manifest.
    address: 'Strada Emil Isac 25, Cluj-Napoca',
    city: 'Cluj-Napoca',
    phone: '+40373811123',
    cuisine: 'gastropub',
};

const PARSE_PRICE_RE = /(\d+(?:[.,]\d+)?)\s*(?:RON|LEI|lei)?/i;

function parsePrice(text) {
    if (!text) return null;
    const m = text.match(PARSE_PRICE_RE);
    if (!m) return null;
    return parseFloat(m[1].replace(',', '.'));
}

function parseWeight(text) {
    if (!text) return null;
    const m = text.match(/(\d+)\s*g\b/i);
    return m ? parseInt(m[1], 10) : null;
}

async function scrapeMenu(page, menu) {
    console.error(`Navigating to ${menu.name}: ${menu.url}`);
    await page.goto(menu.url, { waitUntil: 'networkidle0', timeout: 60000 });
    // Give the Qubs a beat to populate lazy images.
    await new Promise(r => setTimeout(r, 1500));

    // Each rendered food item is a <button id="menu-element-menuLinkId-...">.
    // They live inside a container per category; we read the category
    // headings from the sticky TOC bar in the page.
    const data = await page.evaluate(() => {
        const links = Array.from(document.querySelectorAll('button[id^="menu-element-menuLinkId-"]'));
        const items = links.map((link) => {
            const id = link.id.replace('menu-element-menuLinkId-', '');
            const name = link.querySelector('.text-food-item-content')?.textContent?.trim() || '';
            const priceText = link.querySelector('.text-price-content')?.textContent?.trim() || '';
            const weightEl = link.querySelector('.text-weight');
            const weightText = weightEl ? weightEl.textContent.trim() : '';
            const descSpans = link.querySelectorAll('span.text-descriptions');
            const description = descSpans.length > 0 ? descSpans[0].textContent.trim() : '';
            // Pick the highest-resolution image we can find. The DOM
            // exposes a smallpreview; clicking the card opens a modal
            // with the full-size one. Puppeteer can grab the full size
            // by following the link target, but for the catalog it's
            // enough to keep the smallpreview URL and trust the CDN's
            // variant substitution: the same `imageId` slug works
            // for /public (full size), /smallpreview, /mediumpreview.
            const img = link.querySelector('img[alt*="Previzualizare"]');
            const imageUrl = img ? img.src.replace('/smallpreview', '/public') : '';
            return { id, name, priceText, weightText, description, imageUrl };
        });

        // Category mapping: the TOC bar lists all category names in
        // order. Each card has a sectionTitle visible above it via
        // the "From the Grill" / "Cocktails" type header — but in
        // practice the Qubs DOM doesn't expose it on the link element
        // itself. We just record each item's id and the position so
        // the importer can group by menu if needed.
        return { count: items.length, items };
    });

    return { name: menu.name, url: menu.url, ...data };
}

async function main() {
    const outIdx = process.argv.indexOf('--out');
    const outPath = outIdx >= 0 ? process.argv[outIdx + 1] : null;

    const browser = await puppeteer.launch({
        executablePath: CHROME,
        headless: 'new',
        args: ['--no-sandbox', '--disable-setuid-sandbox'],
    });
    const page = await browser.newPage();
    // The Qubs layout is responsive; viewport doesn't matter much for
    // the DOM shape, but a desktop size keeps images loaded.
    await page.setViewport({ width: 1280, height: 800 });

    const menus = [];
    for (const menu of MENUS) {
        try {
            const scraped = await scrapeMenu(page, menu);
            menus.push(scraped);
            console.error(`  -> ${scraped.name}: ${scraped.count} items`);
        } catch (e) {
            console.error(`  !! ${menu.name} failed: ${e.message}`);
            menus.push({ name: menu.name, url: menu.url, count: 0, items: [], error: e.message });
        }
    }
    await browser.close();

    // Normalise: parse price (strip "RON") and weight (parse "450 g")
    // into numeric fields. Keep the raw text too in case downstream
    // wants to show it.
    for (const m of menus) {
        m.items = m.items.map((it) => ({
            ...it,
            name: it.name.trim(),
            price: parsePrice(it.priceText),
            weight_grams: parseWeight(it.weightText),
        }));
    }

    const result = { ...RESTAURANT_META, scrapedAt: new Date().toISOString(), menus };
    const json = JSON.stringify(result, null, 2);
    if (outPath) {
        fs.writeFileSync(outPath, json);
        console.error(`Wrote ${outPath} (${json.length} bytes)`);
    } else {
        process.stdout.write(json);
    }
}

main().catch((e) => {
    console.error('Fatal:', e);
    process.exit(1);
});
