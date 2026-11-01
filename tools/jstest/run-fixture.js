#!/usr/bin/env node
// Offline fixture test for jdt.js (no network): node tools/jstest/run-fixture.js
'use strict';
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const { chromium } = require('playwright');
const C = require('./common');

const ORIGIN = 'https://fixture.test';

// --- tiny PNG encoder (solid colour + a stripe so it is a "real" image) -------
const CRC = (() => { const t = new Uint32Array(256); for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0; } return t; })();
function crc32(buf) { let c = 0xffffffff; for (const b of buf) c = CRC[(c ^ b) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; }
function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type), data]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td));
  return Buffer.concat([len, td, crc]);
}
function png(w, h) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 2;
  const raw = Buffer.alloc((w * 3 + 1) * h);
  for (let y = 0; y < h; y++) {
    raw[y * (w * 3 + 1)] = 0;
    for (let x = 0; x < w; x++) {
      const o = y * (w * 3 + 1) + 1 + x * 3;
      const stripe = y < h / 4;
      raw[o] = stripe ? 225 : 120; raw[o + 1] = stripe ? 37 : 160; raw[o + 2] = stripe ? 27 : 200;
    }
  }
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]);
}

const FRAME_HTML = '<!DOCTYPE html><html><head><meta charset="utf-8"><title>子框架</title></head><body>' +
  '<p id="ft" title="框架提示">框架内的文字</p></body></html>';

const results = [];
function check(name, ok, detail) {
  results.push({ name, ok: !!ok, detail });
  console.log((ok ? 'PASS ' : 'FAIL ') + name + (detail !== undefined ? '  ' + JSON.stringify(detail) : ''));
}
const near = (a, b, tol = 1) => Math.abs(a - b) <= tol;
const hasEn = s => typeof s === 'string' && s.indexOf('‹') !== -1;
const HAN = /[㐀-鿿]/;

(async () => {
  fs.mkdirSync(C.OUT, { recursive: true });
  const browser = await chromium.launch();
  const ctx = await C.newMobileContext(browser);
  await ctx.route(ORIGIN + '/**', route => {
    const u = new URL(route.request().url());
    if (u.pathname === '/' || u.pathname === '/fixture.html') {
      return route.fulfill({ contentType: 'text/html; charset=utf-8', body: fs.readFileSync(path.join(__dirname, 'fixture.html')) });
    }
    if (u.pathname === '/frame.html') return route.fulfill({ contentType: 'text/html; charset=utf-8', body: FRAME_HTML });
    const m = /^\/img\/(\d+)x(\d+)\.png$/.exec(u.pathname);
    if (m) return route.fulfill({ contentType: 'image/png', body: png(+m[1], +m[2]) });
    return route.fulfill({ status: 404, body: '' });
  });
  await ctx.addInitScript({ content: C.mockSource({ nullFor: ['无法翻译的文本'] }) });
  await ctx.addInitScript({ content: C.jdtSource() });
  const page = await ctx.newPage();
  const pageErrors = [];
  page.on('pageerror', e => pageErrors.push(e.message));
  await page.goto(ORIGIN + '/fixture.html');
  await page.waitForTimeout(1500);

  // ---- text, attributes, forms -------------------------------------------------
  const a = await page.evaluate(() => {
    const $ = id => document.getElementById(id);
    return {
      placeholder: $('search').placeholder, alt: $('alt-img').alt, title: $('alt-img').title,
      aria: $('aria').getAttribute('aria-label'), dph: $('dph').getAttribute('data-placeholder'),
      btn: $('btn').value, submit: $('submit').value, named: $('named-submit').value,
      button: $('button').textContent, buttonValue: $('button').value,
      options: [...$('sel').options].map(o => o.text), optionValues: [...$('sel').options].map(o => o.value),
      docTitle: document.title, titles: window.__mockLog.titles.slice(),
      ta: $('ta').value, ce: $('ce').textContent, code: $('code').textContent, notr: $('notr').textContent,
      react: $('react-root').innerText,
      shadow: $('shadow-host').shadowRoot.getElementById('sh').textContent,
      nullText: $('null-text').textContent,
      ctx: window.__mockLog.batches.reduce((m, b) => { b.items.forEach(i => { m[i.t] = i.c; }); return m; }, {})
    };
  });
  check('placeholder translated', hasEn(a.placeholder), a.placeholder);
  check('img alt + title translated', hasEn(a.alt) && hasEn(a.title), [a.alt, a.title]);
  check('aria-label translated', hasEn(a.aria), a.aria);
  check('data-placeholder translated', hasEn(a.dph), a.dph);
  check('input[type=button] value translated', hasEn(a.btn), a.btn);
  check('unnamed submit value translated', hasEn(a.submit), a.submit);
  check('named submit value untouched (form data)', a.named === '提交订单', a.named);
  check('<button> text translated, value attr untouched', hasEn(a.button) && a.buttonValue === '内部值', [a.button, a.buttonValue]);
  check('<option> texts translated, values untouched', a.options.every(hasEn) && a.optionValues.join() === '1,2,3', a.options);
  check('document.title translated + reported via pageTitle', hasEn(a.docTitle) && a.titles.length && hasEn(a.titles[a.titles.length - 1]), [a.docTitle, a.titles]);
  check('textarea / contenteditable / code / translate=no untouched',
    a.ta === '用户输入的内容' && a.ce === '可编辑区域' && a.code === '代码片段' && a.notr === '不翻译', [a.ta, a.ce, a.code, a.notr]);
  check('re-rendered subtree translated', hasEn(a.react) && !HAN.test(a.react.replace(/‹[^›]*›/g, '')), a.react);
  check('product title gets ctx=title', a.ctx['小米14 Ultra 16GB+1TB 白色 徕卡光学'] === 'title', a.ctx['小米14 Ultra 16GB+1TB 白色 徕卡光学']);
  check('open shadow root translated', hasEn(a.shadow), a.shadow);
  check('null result leaves Chinese', a.nullText === '无法翻译的文本', a.nullText);

  // ---- React-like text replacement -------------------------------------------
  const samples = [];
  for (let i = 0; i < 12; i++) {
    samples.push(await page.evaluate(() => document.getElementById('ticker').firstChild.data));
    await page.waitForTimeout(55);
  }
  const ticks = await page.evaluate(() => window.__ticks);
  check('re-rendered text node always shows English', samples.every(s => hasEn(s) && !HAN.test(s.replace(/‹[^›]*›/g, ''))), samples.slice(0, 4));
  check('leading/trailing whitespace preserved', samples.every(s => /^  ‹/.test(s) && /›  $/.test(s)));
  check('page kept re-rendering (no fight / no freeze)', ticks > 10, ticks);

  // ---- nowrap fitting -----------------------------------------------------------
  const fit = await page.evaluate(() => ({
    span: parseFloat(getComputedStyle(document.getElementById('nowrap-span')).fontSize),
    ell: parseFloat(getComputedStyle(document.getElementById('ellipsis-span')).fontSize)
  }));
  check('nowrap overflow shrinks font to >=85%', fit.span < 16 && fit.span >= 13.59, fit.span);
  check('ellipsis box left alone', fit.ell === 16, fit.ell);

  // ---- images: requests ---------------------------------------------------------
  const imgReq = await page.evaluate(() => window.__mockLog.images.map(u => u.replace(location.origin, '')));
  check('lazy img requested only after src swap (gif skipped)', imgReq.includes('/img/300x150.png') && !imgReq.some(u => u.startsWith('data:')), imgReq);
  check('small images (<80x40) skipped', !imgReq.includes('/img/60x30.png') && !imgReq.includes('/img/40x20.png'));
  check('each image URL requested once', new Set(imgReq).size === imgReq.length);

  // ---- images: overlay geometry --------------------------------------------------
  await page.evaluate(() => document.getElementById('banner').scrollIntoView({ block: 'center' }));
  await page.waitForTimeout(800);
  const geo = await page.evaluate(() => {
    const out = {};
    for (const id of ['lazy', 'cover', 'cover-tl', 'contain', 'slide1', 'slide2', 'banner', 'tiny']) {
      const el = document.getElementById(id);
      const ov = window.__jdt._overlayOf(el);
      if (!ov) { out[id] = null; continue; }
      const ri = el.getBoundingClientRect(), ro = ov.getBoundingClientRect();
      const b = ov.firstElementChild, rb = b.getBoundingClientRect();
      out[id] = {
        img: [ri.left, ri.top, ri.width, ri.height], ov: [ro.left, ro.top, ro.width, ro.height],
        block: [rb.left - ro.left, rb.top - ro.top, rb.width, rb.height],
        text: b.textContent, bg: getComputedStyle(b).backgroundColor, color: getComputedStyle(b).color,
        fs: parseFloat(b.style.fontSize), sh: b.scrollHeight, ch: b.clientHeight, sw: b.scrollWidth, cw: b.clientWidth,
        parentIsImgParent: ov.parentNode === (el.tagName === 'IMG' ? el.parentNode : el)
      };
    }
    out.pure = window.__jdt._fitRect('cover', '50% 50%', 300, 100, 200, 200);
    out.pureContain = window.__jdt._fitRect('contain', 'right bottom', 300, 100, 200, 200);
    out.pureBg = window.__jdt._bgRect('100% auto', '0% 50%', 320, 120, 400, 100);
    return out;
  });
  const g = geo;
  const exp = (name, o, ovBox, block) => {
    if (!o) return check(name + ': overlay exists', false);
    const okOv = ovBox.every((v, i) => near(o.ov[i], v));
    const okB = block.every((v, i) => near(o.block[i], v));
    check(name + ': overlay box', okOv, { got: o.ov.map(Math.round), want: ovBox.map(Math.round) });
    check(name + ': block geometry', okB, { got: o.block.map(x => +x.toFixed(1)), want: block });
  };
  if (g.cover) {
    // content box = border(3)+padding(4) inside; cover of 200x200 into 300x100 -> 300x300 at y=-100; scale 3.
    exp('object-fit:cover (padding+border, centered)', g.cover, [g.cover.img[0] + 7, g.cover.img[1] + 7, 300, 100], [15, -85, 150, 60]);
    check('cover: block text/colors', g.cover.text === 'Free Shipping' && g.cover.bg === 'rgb(225, 37, 27)' && g.cover.color === 'rgb(255, 255, 255)', [g.cover.text, g.cover.bg, g.cover.color]);
    check('cover: font fitted within block', g.cover.fs >= 6 && g.cover.fs <= 60 * 0.9 + 0.01 && g.cover.sh <= g.cover.ch + 1 && g.cover.sw <= g.cover.cw + 1, [g.cover.fs, g.cover.sh, g.cover.ch]);
  } else check('cover overlay exists', false);
  if (g['cover-tl']) exp('object-fit:cover object-position 0 0 (static parent w/ padding)', g['cover-tl'], g['cover-tl'].img, [15, 15, 150, 60]);
  else check('cover-tl overlay exists', false);
  if (g.contain) exp('object-fit:contain', g.contain, g.contain.img, [105, 5, 50, 20]);
  else check('contain overlay exists', false);
  if (g.slide1) exp('carousel slide in translated wrapper', g.slide1, g.slide1.img, [10, 5, 100, 20]);
  else check('slide1 overlay exists', false);
  if (g.lazy) exp('lazy img after swap (fill)', g.lazy, g.lazy.img, [15, 7.5, 150, 30]);
  else check('lazy overlay exists', false);
  if (g.banner) exp('CSS background banner (cover)', g.banner, g.banner.img, [16, 6, 160, 24]);
  else check('banner overlay exists', false);
  check('overlay is a sibling in the image parent', g.cover && g.cover.parentIsImgParent && g.slide1 && g.slide1.parentIsImgParent);
  check('no overlay on tiny image', g.tiny === null);
  check('pure fitRect cover math', near(g.pure.x, 0, 0.01) && near(g.pure.y, -100, 0.01) && near(g.pure.w, 300, 0.01) && near(g.pure.h, 300, 0.01), g.pure);
  check('pure fitRect contain right bottom', near(g.pureContain.x, 200, 0.01) && near(g.pureContain.y, 0, 0.01) && near(g.pureContain.w, 100, 0.01), g.pureContain);
  check('pure bgRect 100% auto', near(g.pureBg.w, 320, 0.01) && near(g.pureBg.h, 80, 0.01) && near(g.pureBg.y, 20, 0.01) && near(g.pureBg.x, 0, 0.01), g.pureBg);

  // Carousel moves: overlay must follow without any JS (same parent).
  await page.evaluate(() => { document.querySelector('.swiper-wrapper').style.transform = 'translate3d(-180px,0,0)'; });
  const mv = await page.evaluate(() => {
    const el = document.getElementById('slide2'); const ov = window.__jdt._overlayOf(el);
    return ov && [el.getBoundingClientRect().left, ov.getBoundingClientRect().left];
  });
  check('overlay follows carousel transform', mv && near(mv[0], mv[1]), mv);

  // Image resize: ResizeObserver re-lays out.
  await page.evaluate(() => { document.getElementById('contain').style.width = '200px'; });
  await page.waitForTimeout(150);
  const rs = await page.evaluate(() => {
    const el = document.getElementById('contain'); const ov = window.__jdt._overlayOf(el); const b = ov.firstElementChild;
    return { ow: ov.getBoundingClientRect().width, bx: b.getBoundingClientRect().left - ov.getBoundingClientRect().left };
  });
  // contain 200x200 into 200x100 -> 100x100 at x=50; block x = 50+5 = 55
  check('overlay re-laid out after image resize', near(rs.ow, 200) && near(rs.bx, 55), rs);

  await page.screenshot({ path: path.join(C.OUT, 'fixture.png'), fullPage: true });

  // ---- long-press peek -----------------------------------------------------------
  const cdp = await ctx.newCDPSession(page);
  const center = await page.evaluate(() => { const el = document.getElementById('contain'); el.scrollIntoView({ block: 'center' }); const r = el.getBoundingClientRect(); return { x: r.left + r.width / 2, y: r.top + r.height / 2 }; });
  const press = async (ms) => {
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: center.x, y: center.y }] });
    await page.waitForTimeout(ms);
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    await page.waitForTimeout(50);
  };
  const ovVisible = () => page.evaluate(() => getComputedStyle(window.__jdt._overlayOf(document.getElementById('contain'))).display !== 'none');
  await press(150);
  check('short tap does not toggle overlay', await ovVisible());
  await press(650);
  check('long-press hides overlay (peek original)', !(await ovVisible()));
  await press(650);
  check('second long-press shows overlay again', await ovVisible());

  // ---- iframe (relay through top frame) ------------------------------------------
  const frame = page.frames().find(f => f.url().endsWith('/frame.html'));
  const fr = frame ? await frame.evaluate(() => ({ t: document.getElementById('ft').textContent, title: document.getElementById('ft').title })) : null;
  check('iframe text + attributes translated', fr && hasEn(fr.t) && hasEn(fr.title), fr);
  const relayed = await page.evaluate(() => window.__jdt.stats().relayed);
  check('iframe requests relayed via top frame', relayed > 0, relayed);

  // ---- requests: no duplicates, null not retried, batch size -------------------------
  await page.evaluate(() => window.__jdt.rescan());
  await page.waitForTimeout(300);
  const rq = await page.evaluate(() => {
    const all = [].concat(...window.__mockLog.batches.map(b => b.items.map(i => i.t)));
    return { n: all.length, uniq: new Set(all).size, nullReq: all.filter(t => t === '无法翻译的文本').length,
      maxBatch: Math.max(...window.__mockLog.batches.map(b => b.n)), ownOutput: all.filter(t => t.indexOf('‹') !== -1).length };
  });
  check('no string requested twice', rq.n === rq.uniq, rq);
  check('own output never sent for translation', rq.ownOutput === 0);
  check('null result not retried in same page view', rq.nullReq === 1, rq.nullReq);
  check('batches <= 80 items', rq.maxBatch <= 80, rq.maxBatch);
  await page.evaluate(() => window.__jdt.retry());
  await page.waitForTimeout(300);
  const nullAfterRetry = await page.evaluate(() => [].concat(...window.__mockLog.batches.map(b => b.items.map(i => i.t)))
    .filter(t => t === '无法翻译的文本').length);
  check('retry() re-requests strings that came back null', nullAfterRetry === 2, nullAfterRetry);

  // ---- SPA navigation --------------------------------------------------------------
  await page.evaluate(() => {
    history.pushState({}, '', '/fixture.html#cart');
    const d = document.createElement('div'); d.id = 'spa-new'; d.textContent = '我的购物车'; document.getElementById('spa').appendChild(d);
  });
  await page.waitForTimeout(300);
  const spa = await page.evaluate(() => document.getElementById('spa-new').textContent);
  check('content added after pushState translated', hasEn(spa), spa);

  // ---- disable / enable ------------------------------------------------------------
  const before = await page.evaluate(() => window.__mockLog.titles.length);
  await page.evaluate(() => window.__jdt.setEnabled(false));
  await page.waitForTimeout(400);
  const off = await page.evaluate(C.leftoverFn);
  const offState = await page.evaluate(() => ({
    btn: document.getElementById('btn').value, submit: document.getElementById('submit').value,
    ph: document.getElementById('search').placeholder, opt: document.getElementById('sel').options[0].text,
    ticker: document.getElementById('ticker').firstChild.data,
    font: getComputedStyle(document.getElementById('nowrap-span')).fontSize,
    ovs: [...document.querySelectorAll('.jdt-ov')].filter(e => getComputedStyle(e).display !== 'none').length,
    title: document.title, lastTitle: window.__mockLog.titles[window.__mockLog.titles.length - 1],
    shadow: document.getElementById('shadow-host').shadowRoot.getElementById('sh').textContent
  }));
  const frOff = frame ? await frame.evaluate(() => document.getElementById('ft').textContent) : null;
  await page.screenshot({ path: path.join(C.OUT, 'fixture-disabled.png'), fullPage: true });
  check('disable: no translated text/attrs left', off.text === 0 && off.attrs === 0, off);
  check('disable: originals restored exactly', offState.btn === '加入购物车' && offState.submit === '立即购买' && offState.ph === '搜索商品' && offState.opt === '请选择颜色' && offState.shadow === '店铺首页', offState);
  check('disable: re-rendering page stays Chinese', HAN.test(offState.ticker) && !hasEn(offState.ticker), offState.ticker);
  check('disable: fitted font restored', offState.font === '16px', offState.font);
  check('disable: overlays hidden', offState.ovs === 0, offState.ovs);
  check('disable: title restored and reported', offState.title === '京东商城 - 首页' && offState.lastTitle === '京东商城 - 首页', [offState.title, offState.lastTitle]);
  check('disable: broadcast to iframe', frOff === '框架内的文字', frOff);

  const t0 = Date.now();
  await page.evaluate(() => window.__jdt.setEnabled(true));
  const on = await page.evaluate(() => ({
    btn: document.getElementById('btn').value, ph: document.getElementById('search').placeholder,
    react: document.getElementById('react-root').innerText, title: document.title,
    ovs: [...document.querySelectorAll('.jdt-ov')].filter(e => getComputedStyle(e).display !== 'none').length
  }));
  check('enable: re-applied synchronously from cache', hasEn(on.btn) && hasEn(on.ph) && hasEn(on.react) && hasEn(on.title), { ms: Date.now() - t0, on });
  check('enable: overlays visible again', on.ovs >= 5, on.ovs);
  await page.waitForTimeout(300);
  const frOn = frame ? await frame.evaluate(() => document.getElementById('ft').textContent) : null;
  check('enable: iframe re-translated', hasEn(frOn), frOn);

  await page.evaluate(() => window.__jdt.setImages(false));
  const noImg = await page.evaluate(() => [...document.querySelectorAll('.jdt-ov')].filter(e => getComputedStyle(e).display !== 'none').length);
  check('setImages(false) hides overlays', noImg === 0, noImg);
  await page.evaluate(() => window.__jdt.setImages(true));

  // ---- idempotency + robustness ---------------------------------------------------------
  await page.addScriptTag({ content: C.jdtSource() });
  const st = await page.evaluate(() => window.__jdt.stats());
  check('re-injection is idempotent', typeof st.textWrites === 'number');
  check('no internal errors', st.errors === 0, st.lastError);
  check('no page errors', pageErrors.length === 0, pageErrors);
  console.log('stats: ' + JSON.stringify(st));

  await browser.close();
  const failed = results.filter(r => !r.ok);
  console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
  process.exit(failed.length ? 1 : 0);
})().catch(e => { console.error(e); process.exit(2); });
