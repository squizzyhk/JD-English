// Shared helpers for the jdt.js Playwright harness.
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..', '..');
const JDT_PATH = path.join(ROOT, 'app', 'src', 'main', 'assets', 'js', 'jdt.js');
const OUT = path.join(__dirname, 'out');
const UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36';

/**
 * Mock of the native JDTNative bridge, installed in every frame BEFORE jdt.js.
 * Translation is a pseudo-translation: every Han char becomes two latin letters
 * (roughly the length growth of real English), wrapped in ‹ › so coverage and
 * restore are easy to check. Image OCR returns one fixed block in a 100x100
 * bitmap space so the script has to scale it to the rendered size.
 */
function mockSource(opts) {
  const o = Object.assign({ enabled: true, images: true, latency: 20, nullFor: [] }, opts || {});
  return `(() => {
    const OPTS = ${JSON.stringify(o)};
    const syl = ['ba','ko','ri','ne','mu','ta','sa','lo','fi','de'];
    function mockTranslate(s) {
      if (OPTS.nullFor.indexOf(s) !== -1) return null;
      let out = '';
      for (const ch of s) {
        const c = ch.codePointAt(0);
        out += (c >= 0x3400 && c <= 0x9fff) || (c >= 0xf900 && c <= 0xfaff) ? syl[c % 10] : ch;
      }
      return '‹' + out + '›';
    }
    window.__mockLog = { batches: [], images: [], titles: [], logs: [] };
    window.JDTNative = {
      getConfig() { return JSON.stringify({ enabled: OPTS.enabled, images: OPTS.images, engine: 'mlkit' }); },
      translateBatch(reqId, json) {
        const req = JSON.parse(json);
        window.__mockLog.batches.push({ id: reqId, n: req.items.length, t: performance.now(), items: req.items });
        setTimeout(() => window.__jdt.onBatch(reqId,
          JSON.stringify({ items: req.items.map(i => mockTranslate(i.t)) })), OPTS.latency);
      },
      translateImage(reqId, url) {
        window.__mockLog.images.push(url);
        setTimeout(() => window.__jdt.onImage(reqId, JSON.stringify({ w: 100, h: 100, blocks: [
          { x: 5, y: 5, w: 50, h: 20, text: 'Free Shipping', orig: '包邮', fg: '#FFFFFF', bg: '#E1251B', angle: 0 }] })),
          OPTS.latency * 2);
      },
      pageTitle(t) { window.__mockLog.titles.push(t); },
      log(m) { window.__mockLog.logs.push(m); console.log('JDTNative.log ' + m); }
    };
  })();`;
}

function jdtSource() {
  return fs.readFileSync(JDT_PATH, 'utf8') + '\n//# sourceURL=jdt.js\n';
}

async function newMobileContext(browser) {
  return browser.newContext({
    viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true,
    userAgent: UA, locale: 'zh-CN'
  });
}

/** In-page: coverage of Han text nodes. Run via page.evaluate. */
function coverageFn(onlyViewport) {
  const HAN = /[㐀-鿿豈-﫿]/;
  const SKIP = { SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, TEXTAREA: 1, CODE: 1, TEMPLATE: 1 };
  const vh = innerHeight, vw = innerWidth;
  let total = 0, done = 0;
  const missing = {};
  const w = document.createTreeWalker(document.body || document.documentElement, NodeFilter.SHOW_TEXT);
  let n;
  while ((n = w.nextNode())) {
    const p = n.parentElement;
    if (!p || SKIP[p.tagName] || !n.data.trim()) continue;
    if (p.closest('.jdt-ov,.jdt-meas')) continue;
    const range = document.createRange();
    range.selectNodeContents(n);
    const r = range.getBoundingClientRect();
    if (r.width < 1 || r.height < 1) continue;
    const cs = getComputedStyle(p);
    if (cs.visibility === 'hidden' || +cs.opacity === 0) continue;
    if (onlyViewport && (r.bottom < 0 || r.top > vh || r.right < 0 || r.left > vw)) continue;
    const translated = n.data.indexOf('‹') !== -1;
    const han = HAN.test(n.data);
    if (!translated && !han) continue;
    total++;
    if (translated && !han) { done++; continue; }
    if (translated) { done++; continue; } // contains Han but our output (mixed); counts as done
    const why = window.__jdt && window.__jdt._why ? window.__jdt._why(n) : 'no-jdt';
    const k = n.data.trim().slice(0, 50);
    missing[k] = missing[k] || { why, count: 0, parent: p.tagName + '.' + String(p.className).slice(0, 40) };
    missing[k].count++;
  }
  return { total, done, pct: total ? +(100 * done / total).toFixed(1) : 100, missing };
}

/** In-page: count anything that still carries our pseudo-translation (text + attributes). */
function leftoverFn() {
  let text = 0, attrs = 0;
  const samples = [];
  const all = [document.documentElement];
  const w = document.createTreeWalker(document.documentElement, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_ELEMENT);
  let n;
  while ((n = w.nextNode())) {
    if (n.nodeType === 3) {
      if (n.data.indexOf('‹') !== -1 && !(n.parentElement && n.parentElement.closest('.jdt-ov,.jdt-meas'))) {
        text++; if (samples.length < 10) samples.push(n.data.slice(0, 60));
      }
    } else {
      for (const a of ['placeholder', 'title', 'alt', 'aria-label', 'data-placeholder', 'value']) {
        const v = n.getAttribute(a);
        if (v && v.indexOf('‹') !== -1) { attrs++; if (samples.length < 10) samples.push('@' + a + '=' + v.slice(0, 40)); }
      }
    }
  }
  const visibleOverlays = [...document.querySelectorAll('.jdt-ov')].filter(e => e.getBoundingClientRect().width > 0).length;
  return { text, attrs, samples, visibleOverlays, title: document.title };
}

module.exports = { ROOT, JDT_PATH, OUT, UA, mockSource, jdtSource, newMobileContext, coverageFn, leftoverFn };
