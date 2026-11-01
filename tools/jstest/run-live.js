#!/usr/bin/env node
// Live test of jdt.js against JD's real mobile site with a mocked JDTNative bridge.
//   node tools/jstest/run-live.js [--no-baseline]
// Prints coverage, remaining Han strings (with reason), page errors, stats(),
// enable/disable round-trip results; writes screenshots to tools/jstest/out/.
'use strict';
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');
const C = require('./common');

const PAGES = [
  { name: 'home', url: 'https://m.jd.com/', scrolls: 6 },
  { name: 'item', url: 'https://item.m.jd.com/product/100012043978.html', scrolls: 4 }
];

async function gotoSafe(page, url) {
  try {
    await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 45000 });
  } catch (e) {
    // JD's item page sometimes never fires DCL in time behind the proxy; keep going if content exists.
    console.log('  (goto: ' + e.message.split('\n')[0] + ')');
  }
}

async function baselineErrors(browser, url) {
  const ctx = await C.newMobileContext(browser);
  const page = await ctx.newPage();
  const errs = new Set();
  page.on('pageerror', e => errs.add(e.message));
  await gotoSafe(page, url);
  await page.waitForTimeout(6000);
  await ctx.close();
  return errs;
}

async function runPage(browser, spec, baseline) {
  const ctx = await C.newMobileContext(browser);
  await ctx.addInitScript({ content: C.mockSource() });
  await ctx.addInitScript({ content: C.jdtSource() });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push({ msg: e.message, stack: e.stack || '' }));
  page.on('console', m => { if (/jdt/i.test(m.text()) && m.type() === 'error') errors.push({ msg: 'console: ' + m.text(), stack: '' }); });

  const t0 = Date.now();
  await gotoSafe(page, spec.url);
  await page.waitForTimeout(5000);
  const cov5 = await page.evaluate(C.coverageFn, true);
  const timing = await page.evaluate(() => {
    const s = window.__jdt.stats();
    const b = window.__mockLog.batches[0];
    return { firstBatchAfterDCL: s.firstBatchAfterDCL, dcl: s.tDCL, firstBatchT: b ? b.t : null };
  });
  await page.screenshot({ path: path.join(C.OUT, spec.name + '-top.png') });

  const scrollCov = [];
  for (let i = 0; i < spec.scrolls; i++) {
    await page.mouse.wheel(0, 600);
    await page.evaluate(() => window.scrollBy(0, 0));
    await page.waitForTimeout(1500);
    scrollCov.push(await page.evaluate(C.coverageFn, true));
    if (i === 1 || i === spec.scrolls - 1) await page.screenshot({ path: path.join(C.OUT, spec.name + '-scroll' + (i + 1) + '.png') });
  }
  const covAll = await page.evaluate(C.coverageFn, false);
  const stats = await page.evaluate(() => window.__jdt.stats());
  const mock = await page.evaluate(() => ({
    batches: window.__mockLog.batches.length,
    maxBatch: Math.max(0, ...window.__mockLog.batches.map(b => b.n)),
    strings: window.__mockLog.batches.reduce((a, b) => a + b.n, 0),
    uniq: new Set([].concat(...window.__mockLog.batches.map(b => b.items.map(i => i.t)))).size,
    ctx: window.__mockLog.batches.reduce((a, b) => { b.items.forEach(i => { a[i.c] = (a[i.c] || 0) + 1; }); return a; }, {}),
    images: window.__mockLog.images.length,
    titles: window.__mockLog.titles.slice(-3)
  }));

  // Disable: everything we touched must be restored.
  await page.evaluate(() => window.__jdt.setEnabled(false));
  await page.waitForTimeout(300);
  const off = await page.evaluate(C.leftoverFn);
  const offText = await page.evaluate(() => document.body.innerText);
  await page.screenshot({ path: path.join(C.OUT, spec.name + '-disabled.png') });
  // Re-enable: re-applied from cache almost immediately.
  await page.evaluate(() => window.__jdt.setEnabled(true));
  await page.waitForTimeout(150);
  const reCov = await page.evaluate(C.coverageFn, true);
  // Disable again: identical to the first disabled snapshot (modulo live timers).
  await page.evaluate(() => window.__jdt.setEnabled(false));
  await page.waitForTimeout(300);
  const offText2 = await page.evaluate(() => document.body.innerText);
  const off2 = await page.evaluate(C.leftoverFn);
  await page.evaluate(() => window.__jdt.setEnabled(true));
  await page.waitForTimeout(500);

  const ours = errors.filter(e => /jdt\.js|__jdt/.test(e.stack + e.msg));
  const novel = errors.filter(e => !baseline.has(e.msg));
  const lineDiff = diffLines(offText, offText2);
  await ctx.close();
  return { spec, ms: Date.now() - t0, cov5, scrollCov, covAll, timing, stats, mock, off, off2, reCov, lineDiff, errors, ours, novel };
}

function diffLines(a, b) {
  const A = a.split('\n'), B = b.split('\n');
  const setB = new Set(B);
  let changed = 0;
  for (const l of A) if (!setB.has(l)) changed++;
  return { lines: A.length, changed };
}

function topMissing(cov, n) {
  return Object.entries(cov.missing).sort((a, b) => b[1].count - a[1].count).slice(0, n)
    .map(([k, v]) => `    ${JSON.stringify(k)} x${v.count} [${v.why}] <${v.parent}>`).join('\n');
}

(async () => {
  fs.mkdirSync(C.OUT, { recursive: true });
  const browser = await chromium.launch();
  const noBaseline = process.argv.includes('--no-baseline');
  let worst = 100, ourErrors = 0, restoreOk = true;
  for (const spec of PAGES) {
    const baseline = noBaseline ? new Set() : await baselineErrors(browser, spec.url);
    const r = await runPage(browser, spec, baseline);
    const lastCov = r.scrollCov[r.scrollCov.length - 1] || r.cov5;
    console.log(`\n=== ${spec.name}: ${spec.url}`);
    console.log(`  visible Han text nodes translated @5s: ${r.cov5.done}/${r.cov5.total} = ${r.cov5.pct}%`);
    console.log(`  after scrolls (viewport): ${r.scrollCov.map(c => c.pct + '%').join(', ')}`);
    console.log(`  whole document (rendered): ${r.covAll.done}/${r.covAll.total} = ${r.covAll.pct}%`);
    console.log(`  first batch after DCL: ${r.timing.firstBatchAfterDCL} ms`);
    console.log(`  mock: ${JSON.stringify(r.mock)}`);
    if (Object.keys(r.cov5.missing).length) console.log('  remaining @5s (viewport):\n' + topMissing(r.cov5, 15));
    if (Object.keys(r.covAll.missing).length) console.log('  remaining (whole doc):\n' + topMissing(r.covAll, 15));
    console.log(`  disabled: leftover text=${r.off.text} attrs=${r.off.attrs} overlaysVisible=${r.off.visibleOverlays} title=${JSON.stringify(r.off.title)} ${r.off.samples.join(' | ')}`);
    console.log(`  re-enabled after 150ms: ${r.reCov.done}/${r.reCov.total} = ${r.reCov.pct}%`);
    console.log(`  disable #2: leftover text=${r.off2.text} attrs=${r.off2.attrs}; innerText lines changed between disable #1 and #2: ${r.lineDiff.changed}/${r.lineDiff.lines}`);
    console.log(`  page errors: ${r.errors.length} total, ${r.ours.length} from jdt.js, ${r.novel.length} not in baseline`);
    for (const e of r.ours.concat(r.novel).slice(0, 10)) console.log('    ! ' + e.msg + '\n      ' + e.stack.split('\n').slice(0, 3).join('\n      '));
    console.log('  stats: ' + JSON.stringify(r.stats));
    worst = Math.min(worst, r.cov5.pct, lastCov.pct);
    ourErrors += r.ours.length;
    if (r.off.text || r.off.attrs || r.off.visibleOverlays || r.off2.text || r.off2.attrs) restoreOk = false;
  }
  await browser.close();
  console.log(`\nSUMMARY worst viewport coverage=${worst}% jdtErrors=${ourErrors} restoreOk=${restoreOk}`);
  process.exit(worst >= 95 && ourErrors === 0 && restoreOk ? 0 : 1);
})().catch(e => { console.error(e); process.exit(2); });
