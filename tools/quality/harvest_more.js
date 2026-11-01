#!/usr/bin/env node
// Harvests MORE visible Chinese strings from JD mobile pages and merges them into
// tools/harvest/strings.tsv (counts are added; first-seen URL is kept).
// Compared to tools/harvest/harvest.js it also opens the interactive parts of item pages
// (SKU picker, delivery-address sheet, coupons/promo sheet, service sheet, review tab +
// full review list), follows the shop link from item pages, crawls item recommendations
// breadth-first across categories, and visits the login page variants, category page and cart.
// Usage: node tools/quality/harvest_more.js [maxItems=40] [outfile=tools/harvest/strings.tsv]
'use strict';
const path = require('path');
const fs = require('fs');
let chromium;
try { ({ chromium } = require('playwright')); } catch (e) { ({ chromium } = require('/opt/node22/lib/node_modules/playwright')); }

const UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36';
const MAX_ITEMS = parseInt(process.argv[2] || '40', 10);
const OUT = process.argv[3] || path.join(__dirname, '..', 'harvest', 'strings.tsv');
const HAN = /[㐀-鿿]/;

// Seed SKUs across categories (appliances, digital, food, beauty, apparel, baby, books, home,
// sports, pets, auto, liquor). Dead ids are skipped; more are discovered from recommendations.
const SEEDS = ['100012043978', '100066896338', '100008348542', '100015253059', '100019867468',
  '100038004389', '12185501', '100009464799', '100035246702', '100026667910', '10052430658853',
  '100004404912', '100011177217', '100056563722', '100049486743', '100060707413', '100047678917',
  '100014565820', '100016034372', '100006538863', '100002356167', '100010079900', '100000822981',
  '4311178', '100005324258', '100027789652', '100021318714', '100036539246', '100030715542',
  '100044025813', '100031406046', '5089253', '100013116298', '7652013', '100003688077',
  '100069353256', '100055421330', '100070543462', '100058998384', '100041430380', '100023327854',
  '100037311064', '100017813346', '100001324422', '100020430562', '100012440856', '100029269870',
  '100008233591', '100005151507', '100003384063', '100016889598', '100024364802', '100039542934'];
const PAGES = [
  'https://m.jd.com/',
  'https://so.m.jd.com/category/all.html',
  'https://p.m.jd.com/cart/cart.action',
  'https://plogin.m.jd.com/login/login',
  'https://home.m.jd.com/myJd/newhome.action',
];

const counts = new Map();
function add(kind, text, url) {
  text = String(text).replace(/\s+/g, ' ').trim();
  if (!text || !HAN.test(text) || text.length > 300) return;
  const k = kind + '\t' + text;
  const e = counts.get(k);
  if (e) e.n++; else counts.set(k, { n: 1, url });
}

async function collect(page) {
  const out = [];
  for (const f of page.frames()) {
    try {
      out.push(await f.evaluate(() => {
        const HAN = /[㐀-鿿]/;
        const res = [];
        const skip = new Set(['SCRIPT', 'STYLE', 'NOSCRIPT', 'TEMPLATE']);
        const w = document.createTreeWalker(document.body || document.documentElement, NodeFilter.SHOW_TEXT);
        let n;
        while ((n = w.nextNode())) {
          const p = n.parentElement;
          if (!p || skip.has(p.tagName)) continue;
          if (n.nodeValue && HAN.test(n.nodeValue)) res.push(['text', n.nodeValue]);
        }
        for (const el of document.querySelectorAll('[placeholder],[title],[alt],[aria-label],[value],[data-placeholder]')) {
          for (const a of ['placeholder', 'title', 'alt', 'aria-label', 'value', 'data-placeholder']) {
            const v = el.getAttribute(a);
            if (v && HAN.test(v)) res.push([a, v]);
          }
        }
        if (HAN.test(document.title)) res.push(['title', document.title]);
        const links = [...document.querySelectorAll('a[href]')].map((a) => a.href);
        const skus = [];
        for (const el of document.querySelectorAll('[data-sku],[skuid],[data-skuid],[data-id]')) {
          const v = el.getAttribute('data-sku') || el.getAttribute('skuid') || el.getAttribute('data-skuid') || el.getAttribute('data-id');
          if (/^\d{6,14}$/.test(v || '')) skus.push(v);
        }
        return { res, links, skus };
      }));
    } catch (e) { /* detached frame */ }
  }
  return out;
}

async function harvest(page, ids, links) {
  const url = page.url();
  let n = 0;
  for (const d of await collect(page)) {
    for (const [k, t] of d.res) { add(k, t, url); n++; }
    for (const l of d.links) {
      if (links) links.add(l);
      const m = l.match(/(?:item\.m\.jd\.com\/(?:product\/)?|item\.jd\.com\/|sku=|wareId=|skuId=)(\d{6,14})/);
      if (m && ids) ids.add(m[1]);
    }
    if (ids) d.skus.forEach((s) => ids.add(s));
  }
  return n;
}

async function scroll(page, times) {
  for (let i = 0; i < times; i++) {
    await page.mouse.wheel(0, 1400).catch(() => {});
    await page.evaluate(() => window.scrollBy(0, 1400)).catch(() => {});
    await page.waitForTimeout(900);
  }
}

// Clicks the first visible element whose own text is exactly one of `texts`; returns true if clicked.
async function clickText(page, texts, wait = 1800) {
  for (const t of texts) {
    try {
      const loc = page.getByText(t, { exact: true });
      const c = await loc.count();
      for (let i = 0; i < Math.min(c, 4); i++) {
        const el = loc.nth(i);
        if (await el.isVisible()) {
          await el.click({ timeout: 2500 });
          await page.waitForTimeout(wait);
          return true;
        }
      }
    } catch (e) { /* not clickable */ }
  }
  return false;
}

async function closeSheet(page) {
  await page.keyboard.press('Escape').catch(() => {});
  await clickText(page, ['关闭', '确定', '完成', '取消'], 600);
}

async function visitItem(ctx, id, ids, shops) {
  const page = await ctx.newPage();
  const url = `https://item.m.jd.com/product/${id}.html`;
  try {
    await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 45000 });
    await page.waitForTimeout(3500);
    const body = await page.evaluate(() => document.body.innerText.slice(0, 400)).catch(() => '');
    if (/Whitelabel|status=404|商品已下柜|找不到/.test(body) && body.length < 300) { console.error(`[dead] ${id}`); return; }
    // The in-page shop button is not clickable for logged-out users; take the shopId from the page.
    const sid = (await page.content().catch(() => '')).match(/shopId["'=:\s]+["']?(\d{3,12})/);
    if (sid) shops.add(`https://shop.m.jd.com/?shopId=${sid[1]}`);
    let n = await harvest(page, ids, null);
    await scroll(page, 5);
    n += await harvest(page, ids, null);
    // Interactive sheets on the item page.
    for (const group of [['已选', '选择'], ['送至', '配送至'], ['优惠', '领券', '促销'], ['服务', '保障'], ['参数', '商品参数', '规格参数']]) {
      await page.evaluate(() => window.scrollTo(0, 0)).catch(() => {});
      if (await clickText(page, group)) { n += await harvest(page, ids, null); await closeSheet(page); }
    }
    // Review tab and full list.
    await page.evaluate(() => window.scrollTo(0, 0)).catch(() => {});
    if (await clickText(page, ['评价'])) {
      n += await harvest(page, ids, null);
      if (await clickText(page, ['查看全部评价', '全部评价', '查看全部', '更多评价'], 3000)) {
        await scroll(page, 4);
        n += await harvest(page, ids, null);
        for (const tab of ['有图', '追评', '好评', '中评', '差评']) {
          if (await clickText(page, [tab], 1500)) n += await harvest(page, ids, null);
        }
        if (page.url() !== url) await page.goBack().catch(() => {});
      }
    }
    // Shop link.
    await page.evaluate(() => window.scrollTo(0, 0)).catch(() => {});
    await scroll(page, 2);
    const before = page.url();
    if (await clickText(page, ['去逛逛', '进店逛逛', '进入店铺', '进店', '店铺'], 4000)) {
      if (page.url() !== before) {
        shops.add(page.url());
        await scroll(page, 4);
        n += await harvest(page, ids, null);
        for (const tab of ['全部商品', '新品', '动态', '店铺详情', '分类']) {
          if (await clickText(page, [tab], 2000)) n += await harvest(page, ids, null);
        }
      } else n += await harvest(page, ids, null);
    }
    console.error(`[item] ${id} (${n} strings)`);
  } catch (e) {
    console.error(`[fail] ${url}: ${e.message.split('\n')[0]}`);
  } finally {
    await page.close();
  }
}

async function visitPage(ctx, url, ids) {
  const page = await ctx.newPage();
  try {
    await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 45000 }).catch(async (e) => {
      // Cart sometimes aborts the first navigation through a redirect; retry once.
      await page.waitForTimeout(2000);
      await page.goto(url, { waitUntil: 'commit', timeout: 45000 });
    });
    await page.waitForTimeout(4000);
    let n = await harvest(page, ids, null);
    await scroll(page, url === 'https://m.jd.com/' ? 10 : 4);
    n += await harvest(page, ids, null);
    if (/plogin/.test(page.url())) {
      for (const t of ['账号密码登录', '手机快速注册', '短信验证码登录', '忘记密码', '其他登录方式', '同意协议并选择其他登录方式']) {
        if (await clickText(page, [t], 2000)) { n += await harvest(page, ids, null); }
        await page.goto(page.url(), { waitUntil: 'domcontentloaded' }).catch(() => {});
        await page.waitForTimeout(1500);
      }
      // Submit empty form to surface validation messages.
      if (await clickText(page, ['获取验证码', '同意协议并登录', '登录'], 1500)) n += await harvest(page, ids, null);
    }
    if (/minicaty|category/.test(page.url())) {
      const names = await page.evaluate(() => {
        const out = [];
        for (const el of document.querySelectorAll('body *')) {
          if (el.children.length) continue;
          const r = el.getBoundingClientRect();
          const t = (el.textContent || '').trim();
          if (r.left < 90 && r.width > 0 && t && t.length <= 6 && /[一-鿿]/.test(t)) out.push(t);
        }
        return [...new Set(out)];
      });
      for (const nm of names) if (await clickText(page, [nm], 1200)) { await scroll(page, 2); n += await harvest(page, ids, null); }
    }
    console.error(`[page] ${url} -> ${page.url()} (${n} strings)`);
  } catch (e) {
    console.error(`[fail] ${url}: ${e.message.split('\n')[0]}`);
  } finally {
    await page.close();
  }
}

function mergeInto(file) {
  const merged = new Map();
  if (fs.existsSync(file)) {
    const lines = fs.readFileSync(file, 'utf8').split('\n').slice(1).filter(Boolean);
    for (const line of lines) {
      const [n, kind, text, url] = line.split('\t');
      merged.set(kind + '\t' + text, { n: parseInt(n, 10) || 0, url: url || '' });
    }
  }
  let added = 0;
  for (const [k, v] of counts) {
    const e = merged.get(k);
    if (e) e.n += v.n; else { merged.set(k, { n: v.n, url: v.url }); added++; }
  }
  const rows = [...merged.entries()].map(([k, v]) => { const [kind, text] = k.split('\t'); return [v.n, kind, text, v.url]; });
  rows.sort((a, b) => b[0] - a[0] || a[2].localeCompare(b[2]));
  fs.writeFileSync(file, 'count\tkind\ttext\turl\n' + rows.map((r) => r.map((x) => String(x).replace(/[\t\n]/g, ' ')).join('\t')).join('\n') + '\n');
  console.error(`merged ${counts.size} harvested strings (${added} new) into ${file}: ${rows.length} rows`);
}

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({
    userAgent: UA, viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true,
    locale: 'zh-CN', deviceScaleFactor: 2,
  });
  const ids = new Set(SEEDS);
  const shops = new Set();
  await Promise.all(PAGES.map((u) => visitPage(ctx, u, ids)));
  const done = new Set();
  const CONC = parseInt(process.env.HARVEST_CONC || '4', 10);
  const worker = async () => {
    while (done.size < MAX_ITEMS) {
      const next = [...ids].find((i) => !done.has(i));
      if (!next) break;
      done.add(next);
      const t0 = Date.now();
      await Promise.race([visitItem(ctx, next, ids, shops), new Promise((r) => setTimeout(r, 240000))]);
      console.error(`  ${next} took ${Math.round((Date.now() - t0) / 1000)}s`);
    }
  };
  await Promise.all(Array.from({ length: CONC }, worker));
  await Promise.all([...shops].slice(0, 8).map((u) => visitPage(ctx, u, ids)));
  console.error(`visited ${done.size} items, ${shops.size} shop pages, ${ids.size} ids known`);
  await browser.close();
  mergeInto(OUT);
})();
