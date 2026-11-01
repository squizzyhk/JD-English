#!/usr/bin/env node
// Harvests visible Chinese strings from JD mobile pages.
// Usage: node tools/harvest/harvest.js [outfile]   (default tools/harvest/strings.tsv)
// Output TSV: count \t kind \t text \t first-url
'use strict';
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36';
const OUT = process.argv[2] || path.join(__dirname, 'strings.tsv');

const START = [
  'https://m.jd.com/',
  'https://item.m.jd.com/product/100012043978.html',
  'https://so.m.jd.com/category/all.html',
  'https://p.m.jd.com/cart/cart.action',
  'https://plogin.m.jd.com/login/login',
  'https://home.m.jd.com/myJd/newhome.action',
  'https://so.m.jd.com/ware/search.action?keyword=%E6%89%8B%E6%9C%BA',
  'https://so.m.jd.com/ware/search.action?keyword=%E7%89%9B%E5%A5%B6',
  'https://so.m.jd.com/ware/search.action?keyword=%E5%8F%A3%E7%BA%A2',
];
// Fallback product ids in different categories (phones, food, beauty, clothing, appliances, books).
const EXTRA_ITEMS = ['100066896338', '100008348542', '100015253059', '100019867468', '100038004389',
  '12185501', '100009464799', '100035246702', '100026667910', '10052430658853', '100004404912', '100011177217'];

const counts = new Map(); // key kind\ttext -> {n, url}
function add(kind, text, url) {
  text = text.replace(/\s+/g, ' ').trim();
  if (!text || !/[㐀-鿿]/.test(text)) return;
  const k = kind + '\t' + text;
  const e = counts.get(k);
  if (e) e.n++; else counts.set(k, { n: 1, url });
}

async function collect(page) {
  const frames = page.frames();
  const out = [];
  for (const f of frames) {
    try {
      const r = await f.evaluate(() => {
        const res = [];
        const skip = new Set(['SCRIPT', 'STYLE', 'NOSCRIPT', 'TEMPLATE']);
        const w = document.createTreeWalker(document.body || document.documentElement, NodeFilter.SHOW_TEXT);
        let n;
        while ((n = w.nextNode())) {
          const p = n.parentElement;
          if (!p || skip.has(p.tagName)) continue;
          const t = n.nodeValue;
          if (t && /[㐀-鿿]/.test(t)) res.push(['text', t]);
        }
        for (const el of document.querySelectorAll('[placeholder],[title],[alt],[aria-label],[value],[data-placeholder]')) {
          for (const a of ['placeholder', 'title', 'alt', 'aria-label', 'value', 'data-placeholder']) {
            const v = el.getAttribute(a);
            if (v && /[㐀-鿿]/.test(v)) res.push([a, v]);
          }
        }
        if (/[㐀-鿿]/.test(document.title)) res.push(['title', document.title]);
        const links = [...document.querySelectorAll('a[href]')].map(a => a.href);
        return { res, links };
      });
      out.push(r);
    } catch (e) { /* detached frame */ }
  }
  return out;
}

// Category page: click every left-rail tab so each tab's sub-categories render, collecting as we go.
async function clickCategoryTabs(page) {
  const names = await page.evaluate(() => {
    const out = [];
    for (const el of document.querySelectorAll('body *')) {
      if (el.children.length) continue;
      const r = el.getBoundingClientRect();
      const t = (el.textContent || '').trim();
      if (r.left < 90 && r.width > 0 && t && t.length <= 6 && /[\u4e00-\u9fff]/.test(t)) out.push(t);
    }
    return [...new Set(out)];
  });
  for (const n of names) {
    try {
      await page.getByText(n, { exact: true }).first().click({ timeout: 3000 });
      await page.waitForTimeout(1500);
      for (let i = 0; i < 3; i++) { await page.mouse.wheel(0, 1200); await page.waitForTimeout(400); }
      for (const d of await collect(page)) for (const [k, t] of d.res) add(k, t, page.url());
    } catch (e) { /* tab not clickable */ }
  }
  console.error(`  clicked ${names.length} category tabs`);
}

async function visit(ctx, url, scrolls, links) {
  const page = await ctx.newPage();
  try {
    await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 45000 });
    await page.waitForTimeout(3500);
    for (let i = 0; i < scrolls; i++) {
      await page.mouse.wheel(0, 1600);
      await page.evaluate(() => window.scrollBy(0, 1600));
      await page.waitForTimeout(1200);
    }
    if (/minicaty|category/.test(page.url())) await clickCategoryTabs(page);
    const finalUrl = page.url();
    const data = await collect(page);
    let n = 0;
    for (const d of data) {
      for (const [k, t] of d.res) { add(k, t, finalUrl); n++; }
      if (links) for (const l of d.links) links.add(l);
    }
    console.error(`[ok] ${url} -> ${finalUrl} (${n} strings)`);
  } catch (e) {
    console.error(`[fail] ${url}: ${e.message.split('\n')[0]}`);
  } finally {
    await page.close();
  }
}

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({
    userAgent: UA, viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true,
    locale: 'zh-CN', deviceScaleFactor: 3,
  });
  const links = new Set();
  for (const u of START) await visit(ctx, u, u === 'https://m.jd.com/' ? 12 : 4, links);
  const ids = new Set(EXTRA_ITEMS);
  for (const l of links) {
    const m = l.match(/(?:item\.m\.jd\.com\/(?:product\/)?|item\.jd\.com\/|sku=|wareId=)(\d{6,14})/);
    if (m) ids.add(m[1]);
  }
  const other = [...links].filter(l => /^https:\/\/(pro|prodev|so|h5)\.m\.jd\.com\//.test(l) || /^https:\/\/pro\.jd\.com/.test(l)).slice(0, 8);
  console.error(`found ${ids.size} item ids, ${other.length} other pages`);
  for (const id of [...ids].slice(0, 24)) await visit(ctx, `https://item.m.jd.com/product/${id}.html`, 5, null);
  for (const u of other) await visit(ctx, u, 4, null);
  await browser.close();

  const rows = [...counts.entries()].map(([k, v]) => { const [kind, text] = k.split('\t'); return [v.n, kind, text, v.url]; });
  rows.sort((a, b) => b[0] - a[0] || a[2].localeCompare(b[2]));
  fs.writeFileSync(OUT, 'count\tkind\ttext\turl\n' + rows.map(r => r.map(x => String(x).replace(/[\t\n]/g, ' ')).join('\t')).join('\n') + '\n');
  console.error(`wrote ${rows.length} rows to ${OUT}`);
})();
