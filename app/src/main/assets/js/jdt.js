/*
 * JD English content script (jdt.js)
 *
 * Injected at DOCUMENT START into every frame of JD's mobile site. Translates the
 * live page to English in place and keeps it translated while the SPA re-renders.
 * Bridge contract: see docs/ARCHITECTURE.md ("Bridge contract").
 *
 *   JS -> native : window.JDTNative.{getConfig, translateBatch, translateImage, pageTitle, log}
 *   native -> JS : window.__jdt.{onBatch, onImage, setEnabled, setImages, rescan}
 *
 * Plain ES2017, no dependencies, single IIFE, idempotent, never throws into the page.
 *
 * Frames: native callbacks (evaluateJavascript) only ever reach the TOP frame, so
 * sub-frames never call JDTNative's async methods directly. They relay requests to
 * the top frame with postMessage; the top frame calls native and posts the result
 * back. Control calls (setEnabled/setImages/rescan) are broadcast from the top
 * frame to every descendant frame.
 */
(function () {
  'use strict';

  if (window.__jdt) {
    try { window.__jdt.rescan(); } catch (e) { /* ignore */ }
    return;
  }

  var N = window.JDTNative;
  var IS_TOP = true;
  try { IS_TOP = window.top === window; } catch (e) { IS_TOP = false; }

  function noop() {}
  if (!N && IS_TOP) {
    window.__jdt = {
      onBatch: noop, onImage: noop, setEnabled: noop, setImages: noop, rescan: noop, retry: noop,
      stats: function () { return { active: false, reason: 'no JDTNative' }; }
    };
    return;
  }

  // ---------------------------------------------------------------------------
  // Constants & state
  // ---------------------------------------------------------------------------
  var HAN = /[㐀-鿿豈-﫿]/;
  var WS_SPLIT = /^(\s*)([\s\S]*?)(\s*)$/;
  var SKIP_TAGS = {
    SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, TEXTAREA: 1, CODE: 1, TEMPLATE: 1,
    IFRAME: 1, OBJECT: 1, EMBED: 1, CANVAS: 1, VIDEO: 1, AUDIO: 1, MATH: 1
  };
  var ATTRS = ['placeholder', 'title', 'alt', 'aria-label', 'data-placeholder'];
  var OBS_ATTRS = ATTRS.concat(['value', 'src', 'srcset']);
  var BATCH_MAX = 80;
  var MAX_INFLIGHT = 4;
  var FLUSH_DELAY = 40;
  // Soft timeouts free the in-flight slot but keep waiting for the answer (the bridge queues
  // work, so a slow answer is normal and re-sending would duplicate paid backend calls).
  // Only after the hard timeout are the strings re-queued / the image made requestable again.
  var REQ_TIMEOUT = 20000;
  var REQ_HARD_TIMEOUT = 180000;
  var IMG_TIMEOUT = 45000;
  var IMG_HARD_TIMEOUT = 240000;
  var MAX_LATE_REQS = 500;
  var IMG_RETRY_DELAYS = [10000, 30000];   // automatic retries of "partial" image results
  var IMG_MAX_INFLIGHT = 3;
  var MAX_STR = 4000;
  var MAX_AUTO_RETRY = 2;
  var MAX_REWRITES = 60;
  var SLICE_MS = 8;
  var FRAME_ID = 'f' + Math.random().toString(36).slice(2, 8) + '_';

  var cfg = { enabled: true, images: true, engine: 'mlkit' };
  try {
    if (N && typeof N.getConfig === 'function') {
      var c0 = JSON.parse(N.getConfig() || '{}');
      if (c0 && typeof c0 === 'object') {
        if (typeof c0.enabled === 'boolean') cfg.enabled = c0.enabled;
        if (typeof c0.images === 'boolean') cfg.images = c0.images;
        if (c0.engine) cfg.engine = c0.engine;
      }
    }
  } catch (e) { /* keep defaults */ }

  var enabled = cfg.enabled;
  var imagesOn = cfg.images;

  /** text node -> {n, orig, lead, trail, key, tr, cnt} */
  var TX = new WeakMap();
  /** element -> {attrName: {el, name, orig, key, tr, cnt}} */
  var AT = new WeakMap();
  /** element -> original inline font-size (for layout fitting) */
  var FIT = new WeakMap();
  var fitted = new Set();

  var cache = new Map();      // key -> English | null (null = failed, don't retry this page view)
  var queue = new Map();      // key -> ctx (not yet sent)
  var pending = new Map();    // key -> reqId (in flight)
  var waiters = new Map();    // key -> Set<rec>
  var retries = new Map();    // key -> count of automatic retries
  var reqs = new Map();       // reqId -> {keys, timedOut, dead, timer}
  var inflight = 0;
  var seq = 0;
  var flushT = 0;

  var S = {
    textWrites: 0, attrWrites: 0, restored: 0, keysSent: 0, batches: 0, batchResults: 0,
    nulls: 0, timeouts: 0, cacheHits: 0, scans: 0, nodesVisited: 0, fitted: 0,
    imgRequested: 0, imgResults: 0, imgErrors: 0, overlays: 0, blocks: 0, peeks: 0,
    errors: 0, relayed: 0, tStart: now(), tDCL: 0, tFirstBatch: 0, lastError: ''
  };

  function now() { return (window.performance && performance.now) ? performance.now() : Date.now(); }

  function log(msg) {
    try { if (N && typeof N.log === 'function') N.log('[jdt] ' + msg); } catch (e) { /* ignore */ }
  }
  function err(where, e) {
    S.errors++;
    S.lastError = where + ': ' + (e && e.message ? e.message : String(e));
    if (S.errors <= 25) log('error ' + S.lastError);
  }
  /** Wrap any callback the page/browser may call so we never throw into the page. */
  function guard(where, fn) {
    return function () {
      try { return fn.apply(this, arguments); } catch (e) { err(where, e); }
    };
  }

  // ---------------------------------------------------------------------------
  // Native calls (direct in top frame, relayed via postMessage in sub-frames)
  // ---------------------------------------------------------------------------
  function callBatch(id, json) {
    if (IS_TOP) N.translateBatch(id, json);
    else window.top.postMessage({ __jdt: 1, k: 'batch', id: id, p: json }, '*');
  }
  function callImage(id, url) {
    if (IS_TOP) N.translateImage(id, url);
    else window.top.postMessage({ __jdt: 1, k: 'image', id: id, p: url }, '*');
  }
  var lastTitle = null;
  function reportTitle() {
    if (!IS_TOP) return;
    try {
      var t = document.title || '';
      if (t === lastTitle) return;
      lastTitle = t;
      if (N && typeof N.pageTitle === 'function') N.pageTitle(t);
    } catch (e) { err('title', e); }
  }

  function parseJson(x) {
    if (x == null) return null;
    if (typeof x === 'object') return x;
    try { return JSON.parse(String(x)); } catch (e) { return null; }
  }

  // ---------------------------------------------------------------------------
  // Classification helpers
  // ---------------------------------------------------------------------------
  function classOf(el) {
    var c = el.getAttribute && el.getAttribute('class');
    return (c || '') + ' ' + (el.id || '');
  }
  var RE_TITLE = /title|name|goods|sku|prod|item/i;
  var RE_REVIEW = /comment|review|eval|pingjia|appraise/i;
  var RE_ATTR = /spec|param|attr|prop/i;

  function ctxFor(el, key) {
    var len = key.length;
    var e = el, depth = 0, cls = '';
    while (e && e.nodeType === 1 && depth < 4) { cls += ' ' + classOf(e); e = e.parentElement; depth++; }
    if (len > 12 && RE_TITLE.test(cls)) return 'title';
    if (RE_REVIEW.test(cls)) return 'review';
    if (RE_ATTR.test(cls)) return 'attr';
    return len <= 16 ? 'ui' : 'product';
  }

  function isOurs(el) {
    var c = el.className;
    return typeof c === 'string' && (c.indexOf('jdt-ov') !== -1 || c.indexOf('jdt-meas') !== -1);
  }

  /** Element whose subtree must never be touched. */
  function skipEl(el) {
    if (SKIP_TAGS[el.tagName] || SKIP_TAGS[(el.tagName || '').toUpperCase()]) return true;
    if (isOurs(el)) return true;
    if (el.getAttribute('translate') === 'no') return true;
    if (el.hasAttribute('contenteditable') && el.isContentEditable) return true;
    return false;
  }

  /** True if node (or any ancestor) lies in a skipped subtree. */
  function inSkipped(node) {
    var el = node.nodeType === 1 ? node : node.parentNode;
    var d = 0;
    while (el && d < 60) {
      if (el.nodeType === 11) { el = el.host; continue; } // shadow root -> host
      if (el.nodeType !== 1) return false;
      if (skipEl(el)) return true;
      el = el.parentNode; d++;
    }
    return false;
  }

  function isButtonInput(el) {
    if (el.tagName !== 'INPUT') return false;
    var t = (el.getAttribute('type') || '').toLowerCase();
    // A named submit button contributes its value to the form submission; leave it.
    return t === 'button' || t === 'reset' || (t === 'submit' && !el.getAttribute('name'));
  }

  function normKey(core) { return core.replace(/\s+/g, ' '); }

  // ---------------------------------------------------------------------------
  // Text nodes
  // ---------------------------------------------------------------------------
  function isTitleNode(n) {
    var p = n.parentNode;
    return !!(p && p.nodeType === 1 && p.localName === 'title' &&
      p.namespaceURI === 'http://www.w3.org/1999/xhtml');
  }

  function handleText(node) {
    var d = node.data;
    var rec = TX.get(node);
    if (rec) {
      if (d === rec.tr) return;                 // our own output
      if (d === rec.orig) {                     // still the same original
        if (!enabled) return;
        var c = cache.get(rec.key);
        if (c) { writeText(rec, c); S.cacheHits++; return; }
        if (c === null) return;
        enqueue(rec.key, rec);
        return;
      }
    }
    if (!HAN.test(d)) {
      if (rec) TX.delete(node);
      if (isTitleNode(node)) reportTitle();
      return;
    }
    if (!enabled) return;
    var m = WS_SPLIT.exec(d);
    var key = normKey(m[2]);
    if (!key || key.length > MAX_STR) return;
    var cnt = rec ? rec.cnt + 1 : 0;
    if (cnt > MAX_REWRITES) return;             // page keeps fighting us; stop.
    rec = { n: node, orig: d, lead: m[1], trail: m[3], key: key, tr: null, cnt: cnt };
    TX.set(node, rec);
    var en = cache.get(key);
    if (en) { writeText(rec, en); S.cacheHits++; return; }
    if (en === null) { if (isTitleNode(node)) reportTitle(); return; }
    enqueue(key, rec);
  }

  function writeText(rec, en) {
    var node = rec.n;
    if (TX.get(node) !== rec || node.data !== rec.orig) return;
    var v = rec.lead + en + rec.trail;
    rec.tr = v;
    if (node.data !== v) { node.data = v; S.textWrites++; }
    var p = node.parentNode;
    if (!p || p.nodeType !== 1) return;
    if (isTitleNode(node)) { reportTitle(); return; }
    scheduleFit(p);
  }

  // ---------------------------------------------------------------------------
  // Attributes
  // ---------------------------------------------------------------------------
  function getAttrVal(el, name) {
    return name === 'value' ? el.value : el.getAttribute(name);
  }

  function handleAttr(el, name) {
    if (name === 'value' && !isButtonInput(el)) return;
    var v = getAttrVal(el, name);
    var map = AT.get(el);
    var rec = map && map[name];
    if (v == null) { if (rec) delete map[name]; return; }
    if (rec) {
      if (v === rec.tr) return;
      if (v === rec.orig) {
        if (!enabled) return;
        var c = cache.get(rec.key);
        if (c) { writeAttr(rec, c); S.cacheHits++; return; }
        if (c === null) return;
        enqueue(rec.key, rec);
        return;
      }
    }
    if (!HAN.test(v)) { if (rec) delete map[name]; return; }
    if (!enabled) return;
    var key = normKey(v.trim());
    if (!key || key.length > MAX_STR) return;
    var cnt = rec ? rec.cnt + 1 : 0;
    if (cnt > MAX_REWRITES) return;
    if (!map) { map = {}; AT.set(el, map); }
    rec = { el: el, name: name, orig: v, key: key, tr: null, cnt: cnt };
    map[name] = rec;
    var en = cache.get(key);
    if (en) { writeAttr(rec, en); S.cacheHits++; return; }
    if (en === null) return;
    enqueue(key, rec);
  }

  function writeAttr(rec, en) {
    var el = rec.el, map = AT.get(el);
    if (!map || map[rec.name] !== rec || getAttrVal(el, rec.name) !== rec.orig) return;
    // Keep surrounding whitespace of the original value.
    var m = WS_SPLIT.exec(rec.orig);
    var v = m[1] + en + m[3];
    rec.tr = v;
    if (rec.name === 'value') el.value = v; else el.setAttribute(rec.name, v);
    S.attrWrites++;
  }

  function scanAttrs(el) {
    if (!el.hasAttributes()) return;
    for (var i = 0; i < ATTRS.length; i++) {
      var a = el.getAttribute(ATTRS[i]);
      if (a && (HAN.test(a) || AT.has(el))) handleAttr(el, ATTRS[i]);
    }
    if (el.tagName === 'INPUT' && isButtonInput(el)) handleAttr(el, 'value');
  }

  // ---------------------------------------------------------------------------
  // Queue / batching
  // ---------------------------------------------------------------------------
  function enqueue(key, rec) {
    var ws = waiters.get(key);
    if (!ws) { ws = new Set(); waiters.set(key, ws); }
    ws.add(rec);
    if (pending.has(key) || queue.has(key)) return;
    var el = rec.el || rec.n.parentNode;
    queue.set(key, el && el.nodeType === 1 ? ctxFor(el, key) : 'ui');
    scheduleFlush();
  }

  function scheduleFlush() {
    if (flushT || !queue.size) return;
    flushT = setTimeout(guard('flush', flush), FLUSH_DELAY);
  }

  function prioOf(key, vh) {
    var ws = waiters.get(key);
    if (!ws) return 3;
    var best = 3, n = 0;
    ws.forEach(function (rec) {
      if (best === 0 || n++ > 4) return;
      var el = rec.el || (rec.n && rec.n.parentNode);
      if (!el || el.nodeType !== 1 || !el.isConnected) return;
      if (el.tagName === 'TITLE') { best = 0; return; }
      var r = el.getBoundingClientRect();
      var p;
      if (r.width === 0 && r.height === 0) p = 2;
      else if (r.bottom >= -80 && r.top <= vh + 80) p = 0;
      else if (r.bottom >= -vh && r.top <= vh * 2.5) p = 1;
      else p = 2;
      if (p < best) best = p;
    });
    return best;
  }

  function flush() {
    flushT = 0;
    if (!enabled || !queue.size) return;
    if (inflight >= MAX_INFLIGHT) return;
    var vh = window.innerHeight || 800;
    var items = [];
    queue.forEach(function (ctx, key) {
      items.push({ k: key, c: ctx, p: items.length < 4000 ? prioOf(key, vh) : 2, i: items.length });
    });
    items.sort(function (a, b) { return a.p - b.p || a.i - b.i; });
    var pos = 0;
    while (inflight < MAX_INFLIGHT && pos < items.length) {
      // A batch that carries on-screen strings carries only on-screen strings, so
      // the visible part of the page comes back first.
      var batch = [], visible = items[pos].p === 0;
      while (pos < items.length && batch.length < BATCH_MAX && (!visible || items[pos].p === 0)) {
        batch.push(items[pos++]);
      }
      send(batch);
    }
    if (queue.size) scheduleFlush();
  }

  function send(batch) {
    var id = FRAME_ID + (++seq);
    var keys = [], payload = [];
    for (var i = 0; i < batch.length; i++) {
      keys.push(batch[i].k);
      payload.push({ t: batch[i].k, c: batch[i].c });
      queue.delete(batch[i].k);
      pending.set(batch[i].k, id);
    }
    var r = { keys: keys, timedOut: false, dead: false, timer: 0 };
    reqs.set(id, r);
    inflight++;
    S.batches++;
    S.keysSent += keys.length;
    if (!S.tFirstBatch) S.tFirstBatch = now();
    r.timer = setTimeout(guard('timeout', function () { onTimeout(id); }), REQ_TIMEOUT);
    try {
      callBatch(id, JSON.stringify({ items: payload }));
    } catch (e) {
      err('translateBatch', e);
      clearTimeout(r.timer);
      reqs.delete(id);
      inflight--;
      releaseKeys(keys, id);
      if (queue.size) scheduleFlush();
    }
  }

  /** Soft timeout: free the slot, keep the keys pending (no duplicate request) and keep waiting. */
  function onTimeout(id) {
    var r = reqs.get(id);
    if (!r || r.timedOut) return;
    r.timedOut = true;
    inflight--;
    S.timeouts++;
    r.timer = setTimeout(guard('hardTimeout', function () { onHardTimeout(id); }), REQ_HARD_TIMEOUT - REQ_TIMEOUT);
    if (queue.size) scheduleFlush();
  }

  /** Hard timeout: give the keys up for a retry; a late answer is still applied if not superseded. */
  function onHardTimeout(id) {
    var r = reqs.get(id);
    if (!r || r.dead) return;
    r.dead = true;
    releaseKeys(r.keys, id);
    if (queue.size) scheduleFlush();
    if (reqs.size > MAX_LATE_REQS) {
      reqs.forEach(function (x, k) { if (x.dead && reqs.size > MAX_LATE_REQS) reqs.delete(k); });
    }
  }

  /** Request failed without per-item answers: requeue (bounded) so it can be retried. */
  function releaseKeys(keys, id) {
    for (var i = 0; i < keys.length; i++) {
      var k = keys[i];
      if (pending.get(k) !== id) continue;
      pending.delete(k);
      var n = (retries.get(k) || 0) + 1;
      retries.set(k, n);
      if (n <= MAX_AUTO_RETRY && waiters.has(k)) queue.set(k, 'ui');
      // Beyond that, waiters stay; a later rescan/mutation re-enqueues the string.
    }
  }

  function onBatch(id, json) {
    id = String(id);
    if (relays.has(id)) { relayBack(id, 'rb', json); return; }
    var r = reqs.get(id);
    if (!r) return;
    reqs.delete(id);
    clearTimeout(r.timer);
    if (!r.timedOut) inflight--;
    S.batchResults++;
    var res = parseJson(json);
    var items = res && Array.isArray(res.items) ? res.items : null;
    if (!items) {
      log('batch ' + id + ' failed: ' + (res && res.error ? res.error : 'bad result'));
      releaseKeys(r.keys, id);
    } else {
      for (var i = 0; i < r.keys.length; i++) {
        var k = r.keys[i];
        if (pending.get(k) === id) pending.delete(k);
        else if (pending.has(k) || cache.get(k)) continue;   // superseded by a retry
        var en = items[i];
        if (typeof en === 'string' && en.trim()) cache.set(k, en.trim());
        else { cache.set(k, null); S.nulls++; }
        applyKey(k);
      }
    }
    if (queue.size) { if (flushT) { clearTimeout(flushT); flushT = 0; } flush(); }
  }

  function applyKey(key) {
    var ws = waiters.get(key);
    if (!ws) return;
    waiters.delete(key);
    var en = cache.get(key);
    if (!en) {
      ws.forEach(function (rec) { if (rec.n && isTitleNode(rec.n)) reportTitle(); });
      return;
    }
    if (!enabled) return;
    ws.forEach(function (rec) {
      try { if (rec.el) writeAttr(rec, en); else writeText(rec, en); } catch (e) { err('apply', e); }
    });
  }

  // ---------------------------------------------------------------------------
  // Layout fitting: only for nodes we translated, batched per animation frame.
  // If the translated text overflows a nowrap box that has no ellipsis, shrink
  // the font of the text's parent in steps down to 85% of its original size.
  // ---------------------------------------------------------------------------
  var fitQ = new Set();
  var fitRaf = 0;
  var raf = window.requestAnimationFrame ? window.requestAnimationFrame.bind(window) : function (f) { return setTimeout(f, 16); };

  function scheduleFit(el) {
    if (!enabled || fitQ.size > 5000) return;
    fitQ.add(el);
    if (!fitRaf) fitRaf = raf(guard('fit', runFit));
  }

  function overflowBox(el) {
    var c = el;
    for (var i = 0; i < 3 && c && c.nodeType === 1; i++, c = c.parentElement) {
      var cw = c.clientWidth;
      if (cw > 0 && c.scrollWidth > cw + 2) return c;
    }
    return null;
  }

  function runFit() {
    fitRaf = 0;
    var t0 = now(), done = 0;
    var arr = [];
    fitQ.forEach(function (el) { arr.push(el); });
    fitQ.clear();
    for (var i = 0; i < arr.length; i++) {
      if (now() - t0 > SLICE_MS) { for (var j = i; j < arr.length; j++) fitQ.add(arr[j]); break; }
      var el = arr[i];
      if (!el.isConnected || el.tagName === 'TITLE' || el.tagName === 'OPTION') continue;
      var box = overflowBox(el);
      if (!box) continue;
      var cs = getComputedStyle(box);
      if (cs.whiteSpace.indexOf('nowrap') === -1 && cs.whiteSpace !== 'pre') continue;
      if (cs.textOverflow === 'ellipsis') continue;
      if (cs.overflowX === 'auto' || cs.overflowX === 'scroll') continue;
      var base = FIT.has(el) ? FIT.get(el).px : parseFloat(getComputedStyle(el).fontSize);
      if (!(base > 0)) continue;
      if (!FIT.has(el)) { FIT.set(el, { px: base, inline: el.style.fontSize }); fitted.add(el); }
      var steps = [0.95, 0.9, 0.85];
      for (var s = 0; s < steps.length; s++) {
        el.style.fontSize = (base * steps[s]).toFixed(2) + 'px';
        if (box.scrollWidth <= box.clientWidth + 2) break;
      }
      S.fitted++;
      done++;
    }
    if (fitQ.size) fitRaf = raf(guard('fit', runFit));
  }

  function restoreFits() {
    fitted.forEach(function (el) {
      var f = FIT.get(el);
      if (f) el.style.fontSize = f.inline;
      FIT.delete(el);
    });
    fitted.clear();
  }

  // ---------------------------------------------------------------------------
  // Tree scanning (time-sliced)
  // ---------------------------------------------------------------------------
  var scanQ = [];
  var walker = null;
  var scanScheduled = false;
  var fullQueued = false;
  var shadowRoots = new Set();
  var deferredRemovals = [];

  var walkFilter = {
    acceptNode: function (n) {
      if (n.nodeType === 3) return HAN.test(n.data) || TX.has(n) ? 1 : 3;   // ACCEPT : SKIP
      return skipEl(n) ? (isStrayOverlay(n) ? 1 : 2) : 1;                 // REJECT subtree
    }
  };

  function queueScan(root) {
    if (!root) return;
    if (root === document || root === document.documentElement) {
      if (fullQueued) return;
      fullQueued = true;
      scanQ.length = 0;
      walker = null;
      scanQ.push(document);
    } else {
      if (fullQueued && !walker) return;    // pending full scan will cover it
      scanQ.push(root);
    }
    scheduleScan();
  }

  var mc = null;
  if (typeof MessageChannel === 'function') {
    try {
      mc = new MessageChannel();
      mc.port1.onmessage = guard('scan', runScan);
    } catch (e) { mc = null; }
  }
  function scheduleScan() {
    if (scanScheduled) return;
    scanScheduled = true;
    if (mc) mc.port2.postMessage(0); else setTimeout(guard('scan', runScan), 0);
  }

  function visit(n) {
    S.nodesVisited++;
    if (n.nodeType === 3) { handleText(n); return; }
    if (isStrayOverlay(n)) { deferredRemovals.push(n); return; }
    if (enabled) scanAttrs(n);
    if (n.tagName === 'IMG') registerImg(n);
    if (n.shadowRoot && !shadowRoots.has(n.shadowRoot)) {
      shadowRoots.add(n.shadowRoot);
      observeRoot(n.shadowRoot);
      scanQ.push(n.shadowRoot);
    } else if (n.shadowRoot) {
      scanQ.push(n.shadowRoot);
    }
  }

  function runScan(budget) {
    scanScheduled = false;
    var limit = typeof budget === 'number' ? budget : SLICE_MS;
    var t0 = now(), count = 0;
    for (;;) {
      if ((++count & 31) === 0 && now() - t0 > limit) break;
      if (!walker) {
        var root = scanQ.shift();
        if (!root) break;
        if (root === document) fullQueued = false;
        if (root !== document && !root.isConnected) continue;
        if (root.nodeType === 3) {
          if (!inSkipped(root)) handleText(root);
          continue;
        }
        if (root.nodeType === 1) {
          if (inSkipped(root)) { if (isStrayOverlay(root)) deferredRemovals.push(root); continue; }
          visit(root);
        }
        S.scans++;
        walker = document.createTreeWalker(root, 5 /* ELEMENT|TEXT */, walkFilter, false);
        continue;
      }
      var n = walker.nextNode();
      if (!n) { walker = null; continue; }
      visit(n);
    }
    while (deferredRemovals.length) {
      var d = deferredRemovals.pop();
      if (d.parentNode) d.parentNode.removeChild(d);
    }
    if (walker || scanQ.length) scheduleScan();
    else if (queue.size) {
      // Scan done: send right away instead of waiting for the debounce.
      if (flushT) { clearTimeout(flushT); flushT = 0; }
      flush();
    }
  }

  // ---------------------------------------------------------------------------
  // MutationObserver
  // ---------------------------------------------------------------------------
  var MO_OPTS = {
    childList: true, subtree: true, characterData: true,
    attributes: true, attributeFilter: OBS_ATTRS
  };
  var mo = new MutationObserver(guard('mo', onMutations));
  function observeRoot(root) {
    try { mo.observe(root, MO_OPTS); } catch (e) { err('observe', e); }
  }

  function onMutations(list) {
    for (var i = 0; i < list.length; i++) {
      var m = list[i], t = m.target;
      if (m.type === 'characterData') {
        if (!enabled) continue;
        if (t.nodeType === 3 && (HAN.test(t.data) || TX.has(t)) && !inSkipped(t)) handleText(t);
      } else if (m.type === 'childList') {
        if (m.removedNodes.length) handleRemoved(m.removedNodes);
        var added = m.addedNodes;
        for (var j = 0; j < added.length; j++) {
          var a = added[j];
          if (a.nodeType === 3) {
            if (enabled && HAN.test(a.data) && !inSkipped(a)) handleText(a);
            else if (a.parentNode && a.parentNode.tagName === 'TITLE') reportTitle();
          } else if (a.nodeType === 1) {
            if (isOurs(a)) { if (isStrayOverlay(a)) deferredRemovals.push(a); continue; }
            if (t.nodeType === 1 && SKIP_TAGS[t.tagName]) continue;
            queueScan(a);
          }
        }
        if (deferredRemovals.length) scheduleScan();
      } else if (m.type === 'attributes') {
        var name = m.attributeName;
        if (t.nodeType !== 1) continue;
        if (name === 'src' || name === 'srcset') {
          if (t.tagName === 'IMG') onImgSrcChange(t);
          continue;
        }
        if (!enabled) continue;
        var v = name === 'value' ? (isButtonInput(t) ? t.value : null) : t.getAttribute(name);
        if (v == null && !AT.has(t)) continue;
        if (v != null && !HAN.test(v) && !AT.has(t)) continue;
        if (!inSkipped(t)) handleAttr(t, name);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Enable / disable
  // ---------------------------------------------------------------------------
  function forEachNodeDeep(root, fn) {
    var w = document.createTreeWalker(root, 5, null, false);
    var n = root.nodeType === 1 ? root : w.nextNode();
    while (n) {
      fn(n);
      if (n.nodeType === 1 && n.shadowRoot) forEachNodeDeep(n.shadowRoot, fn);
      n = w.nextNode();
    }
  }

  function restoreAll() {
    forEachNodeDeep(document, function (n) {
      if (n.nodeType === 3) {
        var rec = TX.get(n);
        if (rec && rec.tr != null && n.data === rec.tr) { n.data = rec.orig; S.restored++; }
      } else {
        var map = AT.get(n);
        if (map) {
          for (var k in map) {
            var r = map[k];
            if (r.tr != null && getAttrVal(n, k) === r.tr) {
              if (k === 'value') n.value = r.orig; else n.setAttribute(k, r.orig);
              S.restored++;
            }
          }
        }
      }
    });
    restoreFits();
  }

  function setEnabled(v) {
    v = !!v;
    broadcast({ __jdt: 1, k: 'ctl', op: 'enabled', v: v });
    if (v === enabled) { if (v) rescan(); return; }
    enabled = v;
    setRootClass('jdt-off', !v);
    if (!v) {
      if (flushT) { clearTimeout(flushT); flushT = 0; }
      queue.clear();
      fitQ.clear();
      restoreAll();
      mo.takeRecords();         // drop records caused by our restore
      reportTitle();
    } else {
      // Re-apply from cache synchronously (up to a generous budget), then continue sliced.
      fullQueued = false;
      queueScan(document);
      scanScheduled = false;
      runScan(60);
      sweepBgSoon();
      imgStates.forEach(function (st) { if (st.vis) tryImage(st.el); });
    }
  }

  function setImages(v) {
    v = !!v;
    broadcast({ __jdt: 1, k: 'ctl', op: 'images', v: v });
    imagesOn = v;
    setRootClass('jdt-noimg', !v);
    if (v) { queueScan(document); sweepBgSoon(); }
  }

  var routeT = 0;
  function rescan() {
    broadcast({ __jdt: 1, k: 'ctl', op: 'rescan' });
    if (!enabled) return;
    queueScan(document);
    sweepBgSoon();
  }

  function forgetTextFailures() {
    cache.forEach(function (v, k) { if (v === null) cache.delete(k); });
    retries.clear();
    lastTitle = null;
  }

  /** New page view (SPA navigation): forget failures so they get another chance. */
  function onRoute() {
    if (routeT) clearTimeout(routeT);
    routeT = setTimeout(guard('route', function () {
      routeT = 0;
      forgetTextFailures();
      rescan();
    }), 60);
  }

  /**
   * native -> JS: the translator changed or became available (model downloaded, Claude key
   * added). Forget failed strings (null cache entries) and failed/partial images, and request
   * them again. Also forwarded to sub-frames.
   */
  function retry() {
    broadcast({ __jdt: 1, k: 'ctl', op: 'retry' });
    forgetTextFailures();
    imgTries.clear();
    var again = [];
    imgCache.forEach(function (e, url) {
      if (e.st === 'done' && (!e.res || e.partial)) {
        if (e.retryT) clearTimeout(e.retryT);
        imgCache.delete(url);
        e.els.forEach(function (el) { again.push(el); });
      }
    });
    if (!enabled) return;
    queueScan(document);
    sweepBgSoon();
    for (var i = 0; i < again.length; i++) retryImageEl(again[i]);
  }

  function setRootClass(cls, on) {
    var de = document.documentElement;
    if (de && de.classList) de.classList.toggle(cls, on);
  }

  // ---------------------------------------------------------------------------
  // Styles
  // ---------------------------------------------------------------------------
  var styleEl = null;
  function ensureStyle() {
    if (styleEl && styleEl.isConnected) return;
    var parent = document.head || document.documentElement;
    if (!parent) return;
    styleEl = document.createElement('style');
    styleEl.id = 'jdt-style';
    styleEl.textContent =
      '.jdt-ov{position:absolute!important;pointer-events:none!important;overflow:hidden;' +
      'margin:0!important;padding:0!important;border:0!important;box-sizing:border-box;' +
      'transform:none;background:none!important;display:block;min-width:0;min-height:0;max-width:none;max-height:none}' +
      '.jdt-ov>.jdt-b{position:absolute;display:flex;align-items:center;justify-content:center;' +
      'text-align:center;box-sizing:border-box;padding:0 1px;margin:0;overflow:hidden;border-radius:2px;' +
      'font-family:Roboto,"Helvetica Neue",Arial,sans-serif;line-height:1.1;letter-spacing:0;' +
      'white-space:normal;word-break:normal;overflow-wrap:normal;text-transform:none;text-shadow:none;' +
      'font-style:normal;text-indent:0;pointer-events:none}' +
      '.jdt-meas{position:absolute!important;left:-10000px!important;top:0!important;visibility:hidden!important;' +
      'pointer-events:none!important;height:auto!important;box-sizing:border-box;padding:0 1px;margin:0;' +
      'font-family:Roboto,"Helvetica Neue",Arial,sans-serif;line-height:1.1;letter-spacing:0;' +
      'white-space:normal;word-break:normal;overflow-wrap:normal;contain:layout style}' +
      'html.jdt-off .jdt-ov,html.jdt-noimg .jdt-ov,.jdt-ov.jdt-peek{display:none!important}';
    parent.appendChild(styleEl);
  }

  // ---------------------------------------------------------------------------
  // Images: OCR overlay
  //
  // Placement strategy: the overlay <div class="jdt-ov"> is inserted as a SIBLING
  // right after the <img> (inside the bg element for CSS backgrounds), absolutely
  // positioned. Being in the same parent means it is subject to the same
  // containing-block transforms and scroll containers as the image, so it moves
  // with swiper/carousel translate3d animations and horizontal scrollers without
  // any JS on scroll. A global fixed layer would have to chase those transforms
  // every frame and visibly lag. We never change the parent's `position`.
  // The offset is found by measuring: place the overlay at left/top 0, read where
  // that lands (the containing block origin, wherever it is), then shift by the
  // difference to the image's content box. This works whether or not the parent
  // is positioned, and also corrects for ancestor scale transforms.
  // ---------------------------------------------------------------------------
  var IMGST = new WeakMap();          // element -> state
  var OVOWN = new WeakMap();          // overlay -> owning element
  var imgStates = new Set();          // states that have overlays or are being watched
  var imgCache = new Map();           // url -> {st:'queued'|'pending'|'done', res, partial, els:Set}
  var imgQ = [];                      // urls waiting to be requested
  var imgReqs = new Map();            // reqId -> {url, soft, timer}
  var imgTries = new Map();           // url -> automatic retries of partial results so far
  var imgInflight = 0;
  var io = null, ro = null;

  function isStrayOverlay(el) {
    return el.nodeType === 1 && typeof el.className === 'string' &&
      el.className.indexOf('jdt-ov') !== -1 && !OVOWN.has(el);
  }

  function getIO() {
    if (io || typeof IntersectionObserver !== 'function') return io;
    io = new IntersectionObserver(guard('io', function (entries) {
      for (var i = 0; i < entries.length; i++) {
        var e = entries[i], st = IMGST.get(e.target);
        if (!st) continue;
        st.vis = e.isIntersecting;
        if (st.vis) {
          if (st.bg) tryBg(st.el); else tryImage(st.el);
          if (st.ov) positionOverlay(st);
        }
      }
    }), { rootMargin: '300px' });
    return io;
  }

  function getRO() {
    if (ro || typeof ResizeObserver !== 'function') return ro;
    ro = new ResizeObserver(guard('ro', function (entries) {
      for (var i = 0; i < entries.length; i++) {
        var st = IMGST.get(entries[i].target);
        if (st && st.ov) layoutOverlay(st);
      }
    }));
    return ro;
  }

  function registerImg(img) {
    if (!imagesOn || IMGST.has(img)) return;
    var st = { el: img, bg: false, url: '', ov: null, res: null, vis: false, peek: false, key: '' };
    IMGST.set(img, st);
    var o = getIO();
    if (o) o.observe(img);
  }

  function imgUrlOf(img) {
    return img.currentSrc || img.src || '';
  }

  function badUrl(u) {
    if (!u) return true;
    if (/^data:/i.test(u)) return !/^data:image\/(png|jpe?g|webp)/i.test(u) || u.length < 400;
    if (!/^https?:/i.test(u)) return true;
    var path = u.split(/[?#]/)[0].toLowerCase();
    if (/\.gif$/.test(path) || /\.svg$/.test(path)) return true;
    if (/loading|spinner|placeholder|blank\.|(^|[^\d])1x1([^\d]|$)|pixel/.test(path)) return true;
    return false;
  }

  /**
   * Rendered-size gate. Nominally >= 80x40 CSS px, but JD's bottom tab bar and
   * channel icons are ~62x48 images with the label baked in, so anything with at
   * least 48x32 and 2400 px^2 qualifies. Tiny glyph icons (<= ~45px) stay skipped.
   */
  function bigEnough(w, h) {
    return (w >= 80 && h >= 40) || (w >= 48 && h >= 32 && w * h >= 2400);
  }

  function tryImage(img) {
    if (!imagesOn || !enabled) return;
    var st = IMGST.get(img);
    if (!st || !img.isConnected) return;
    var url = imgUrlOf(img);
    if (url !== st.url) { dropOverlay(st); st.url = url; st.res = null; }
    if (badUrl(url)) return;
    if (!img.complete || !img.naturalWidth) return;        // the load listener retries
    if (img.naturalWidth < 40 || img.naturalHeight < 20) return;
    if (!bigEnough(img.offsetWidth, img.offsetHeight)) return;
    requestImage(st, url);
  }

  function requestImage(st, url) {
    if (st.ov && st.res && !st.res.partial) return;
    var e = imgCache.get(url);
    if (e && e.st === 'done') { if (e.res) renderOverlay(st, e.res); return; }
    if (!e) {
      e = { st: 'queued', res: null, els: new Set() };
      imgCache.set(url, e);
      imgQ.push(url);
    }
    e.els.add(st.el);
    pumpImages();
  }

  function pumpImages() {
    while (imgInflight < IMG_MAX_INFLIGHT && imgQ.length) {
      var url = imgQ.shift();
      var e = imgCache.get(url);
      if (!e || e.st !== 'queued') continue;
      var id = FRAME_ID + 'i' + (++seq);
      e.st = 'pending';
      e.id = id;
      var q = { url: url, soft: false, timer: 0 };
      imgReqs.set(id, q);
      imgInflight++;
      S.imgRequested++;
      q.timer = setTimeout(guard('imgTimeout', imgTimeoutFn(id)), IMG_TIMEOUT);
      try {
        callImage(id, url);
      } catch (x) {
        err('translateImage', x);
        clearTimeout(q.timer);
        imgReqs.delete(id);
        imgInflight--;
        if (e.id === id) imgCache.delete(url);
      }
    }
  }

  /** Soft timeout frees the slot but keeps waiting; the hard timeout allows a new request. */
  function imgTimeoutFn(id) {
    return function () {
      var q = imgReqs.get(id);
      if (!q) return;
      if (!q.soft) {
        q.soft = true;
        imgInflight--;
        q.timer = setTimeout(guard('imgHardTimeout', imgTimeoutFn(id)), IMG_HARD_TIMEOUT - IMG_TIMEOUT);
      } else {
        imgReqs.delete(id);
        var e = imgCache.get(q.url);
        if (e && e.id === id) imgCache.delete(q.url);       // allow a retry later
      }
      pumpImages();
    };
  }

  /** Re-request an element's image after its cache entry was dropped (retry). */
  function retryImageEl(el) {
    var st = IMGST.get(el);
    if (!st || !st.vis || !el.isConnected) return;
    if (st.bg) tryBg(el); else tryImage(el);
  }

  function onImage(id, json) {
    id = String(id);
    if (relays.has(id)) { relayBack(id, 'ri', json); return; }
    var q = imgReqs.get(id);
    if (!q) return;
    var url = q.url;
    imgReqs.delete(id);
    clearTimeout(q.timer);
    if (!q.soft) imgInflight--;
    S.imgResults++;
    var e = imgCache.get(url);
    if (e && e.id !== id) e = null;                       // superseded by a newer request
    var res = parseJson(json);
    if (!res || res.error || !Array.isArray(res.blocks)) {
      S.imgErrors++;
      // Final for this page view; retry() (native) clears it. Keep els for that.
      if (e) { e.st = 'done'; e.res = null; }
      pumpImages();
      return;
    }
    var partial = !!res.partial;                          // some text couldn't be translated yet
    if (!(res.w > 0 && res.h > 0) || !res.blocks.length) res = null;
    if (e) {
      e.st = 'done';
      e.res = res;
      e.partial = partial;
      var els = e.els;
      if (!partial) e.els = new Set();                    // partial: keep them for the retry
      if (res) {
        els.forEach(function (el) {
          var st = IMGST.get(el);
          if (!st) return;
          var cur = st.bg ? st.url : imgUrlOf(el);
          if (cur === url) { st.url = url; renderOverlay(st, res); }
        });
      }
      if (partial) scheduleImgRetry(url, e);
    }
    pumpImages();
  }

  /** Partial result (translator not ready yet): ask again after a back-off, a bounded number of times. */
  function scheduleImgRetry(url, e) {
    var n = imgTries.get(url) || 0;
    if (n >= IMG_RETRY_DELAYS.length) return;
    imgTries.set(url, n + 1);
    e.retryT = setTimeout(guard('imgRetry', function () {
      if (imgCache.get(url) !== e) return;
      imgCache.delete(url);
      e.els.forEach(retryImageEl);
    }), IMG_RETRY_DELAYS[n]);
  }

  function onImgSrcChange(img) {
    var st = IMGST.get(img);
    if (!st) { if (img.isConnected) registerImg(img); return; }
    var url = imgUrlOf(img);
    if (url !== st.url) { dropOverlay(st); st.url = ''; }
    if (st.vis) tryImage(img);
  }

  function dropOverlay(st) {
    if (st.ov) {
      if (st.ov.parentNode) st.ov.parentNode.removeChild(st.ov);
      OVOWN.delete(st.ov);
      st.ov = null;
      S.overlays--;
    }
    st.res = null;
    st.peek = false;
    st.key = '';
    if (ro) try { ro.unobserve(st.el); } catch (e) { /* ignore */ }
    imgStates.delete(st);
  }

  function renderOverlay(st, res) {
    var el = st.el;
    if (!el.isConnected) return;
    ensureStyle();
    if (st.res !== res) st.key = '';      // new result (e.g. a retry of a partial one): re-layout
    st.res = res;
    if (!st.ov) {
      var ov = document.createElement('div');
      ov.className = 'jdt-ov';
      ov.setAttribute('aria-hidden', 'true');
      ov.setAttribute('translate', 'no');
      OVOWN.set(ov, el);
      st.ov = ov;
      S.overlays++;
      if (st.bg) {
        el.appendChild(ov);
      } else {
        var anchor = el.parentNode && el.parentNode.tagName === 'PICTURE' ? el.parentNode : el;
        if (!anchor.parentNode) return;
        anchor.parentNode.insertBefore(ov, anchor.nextSibling);
      }
      if (st.peek) ov.classList.add('jdt-peek');
      imgStates.add(st);
      var r = getRO();
      if (r) r.observe(el);
      startRealign();
    }
    layoutOverlay(st);
  }

  function px(v) { var f = parseFloat(v); return isFinite(f) ? f : 0; }

  /** Box (layout px, relative to el's border-box origin) in which the image is painted. */
  function paintBox(el, bg) {
    var cs = getComputedStyle(el);
    var bl = px(cs.borderLeftWidth), bt = px(cs.borderTopWidth);
    var br = px(cs.borderRightWidth), bb = px(cs.borderBottomWidth);
    var x = bl, y = bt, w = el.offsetWidth - bl - br, h = el.offsetHeight - bt - bb;
    if (!bg) {
      var pl = px(cs.paddingLeft), pt = px(cs.paddingTop);
      x += pl; y += pt;
      w -= pl + px(cs.paddingRight);
      h -= pt + px(cs.paddingBottom);
    }
    return { x: x, y: y, w: Math.max(0, w), h: Math.max(0, h), cs: cs };
  }

  /** Parse one axis of object-position / background-position into {pct} or {px}. */
  function parsePosAxis(tok) {
    if (!tok) return { pct: 50 };
    if (tok === 'left' || tok === 'top') return { pct: 0 };
    if (tok === 'right' || tok === 'bottom') return { pct: 100 };
    if (tok === 'center') return { pct: 50 };
    if (/%$/.test(tok)) return { pct: px(tok) };
    if (/px$/.test(tok) || /^-?[\d.]+$/.test(tok)) return { px: px(tok) };
    return { pct: 50 };
  }
  function parsePos(pos) {
    var p = String(pos || '50% 50%').split(',')[0].trim().split(/\s+/);
    var a = p[0], b = p[1];
    // Keyword order may be swapped ("top left").
    if (a === 'top' || a === 'bottom' || (b === 'left' || b === 'right')) { var t = a; a = b; b = t; }
    if (p.length === 1) {
      if (a === 'top' || a === 'bottom') { b = a; a = 'center'; } else b = 'center';
    }
    return { x: parsePosAxis(a), y: parsePosAxis(b) };
  }
  function placeAxis(ax, free) { return ax.px != null ? ax.px : free * ax.pct / 100; }

  /**
   * Where an image with intrinsic size (iw, ih) is drawn inside a cw x ch box for a
   * given object-fit / object-position. Returns {x, y, w, h} of the drawn image.
   */
  function fitRect(fit, pos, cw, ch, iw, ih) {
    var w, h, s;
    fit = fit || 'fill';
    if (fit === 'contain' || fit === 'scale-down') {
      s = Math.min(cw / iw, ch / ih);
      if (fit === 'scale-down') s = Math.min(s, 1);
      w = iw * s; h = ih * s;
    } else if (fit === 'cover') {
      s = Math.max(cw / iw, ch / ih);
      w = iw * s; h = ih * s;
    } else if (fit === 'none') {
      w = iw; h = ih;
    } else {
      return { x: 0, y: 0, w: cw, h: ch };
    }
    var p = parsePos(pos);
    return { x: placeAxis(p.x, cw - w), y: placeAxis(p.y, ch - h), w: w, h: h };
  }

  function bgLen(tok, ref) {
    if (!tok || tok === 'auto') return null;
    if (/%$/.test(tok)) return ref * px(tok) / 100;
    return px(tok);
  }
  /** Same as fitRect for CSS background-size / background-position (first layer). */
  function bgRect(size, pos, cw, ch, iw, ih) {
    size = String(size || 'auto').split(',')[0].trim();
    if (size === 'cover' || size === 'contain') return fitRect(size, pos, cw, ch, iw, ih);
    var parts = size.split(/\s+/);
    var W = bgLen(parts[0], cw), H = bgLen(parts[1] || 'auto', ch);
    if (W == null && H == null) { W = iw; H = ih; }
    else if (W == null) W = H * iw / ih;
    else if (H == null) H = W * ih / iw;
    var p = parsePos(pos);
    return { x: placeAxis(p.x, cw - W), y: placeAxis(p.y, ch - H), w: W, h: H };
  }

  /** Map bitmap-space blocks into overlay-local CSS px. Pure; exported for tests. */
  function mapBlocks(res, rect) {
    var sx = rect.w / res.w, sy = rect.h / res.h, out = [];
    for (var i = 0; i < res.blocks.length; i++) {
      var b = res.blocks[i];
      out.push({ x: rect.x + b.x * sx, y: rect.y + b.y * sy, w: b.w * sx, h: b.h * sy, b: b });
    }
    return out;
  }

  /** Position overlay over the paint box: measure-and-correct (see strategy above). */
  function positionOverlay(st) {
    var el = st.el, ov = st.ov;
    if (!ov || !el.isConnected) return false;
    var box = st.box;
    if (!box) return false;
    ov.style.left = '0px';
    ov.style.top = '0px';
    ov.style.width = box.w + 'px';
    ov.style.height = box.h + 'px';
    var r0 = ov.getBoundingClientRect();
    var ri = el.getBoundingClientRect();
    var scale = ov.offsetWidth > 0 && r0.width > 0 ? r0.width / ov.offsetWidth : 1;
    if (!(scale > 0.01)) scale = 1;
    var left = (ri.left - r0.left) / scale + box.x;
    var top = (ri.top - r0.top) / scale + box.y;
    ov.style.left = left.toFixed(2) + 'px';
    ov.style.top = top.toFixed(2) + 'px';
    st.dx = left; st.dy = top;
    return true;
  }

  function layoutOverlay(st) {
    var el = st.el, ov = st.ov, res = st.res;
    if (!ov || !res) return;
    if (!el.isConnected) { dropOverlay(st); return; }
    if (!st.bg && ov.parentNode !== (el.parentNode && el.parentNode.tagName === 'PICTURE' ? el.parentNode.parentNode : el.parentNode)) {
      dropOverlay(st); return;
    }
    var box = paintBox(el, st.bg);
    if (box.w < 2 || box.h < 2) { ov.style.display = 'none'; st.key = ''; return; }
    syncVisibility(st, box.cs);
    var iw, ih, rect;
    if (st.bg) {
      iw = st.natW || res.w; ih = st.natH || res.h;
      rect = bgRect(box.cs.backgroundSize, box.cs.backgroundPosition, box.w, box.h, iw, ih);
    } else {
      iw = el.naturalWidth || res.w; ih = el.naturalHeight || res.h;
      rect = fitRect(box.cs.objectFit, box.cs.objectPosition, box.w, box.h, iw, ih);
    }
    st.box = box;
    positionOverlay(st);
    var key = box.w.toFixed(1) + 'x' + box.h.toFixed(1) + ':' + rect.x.toFixed(1) + ',' + rect.y.toFixed(1) + ',' + rect.w.toFixed(1);
    if (key === st.key) return;
    st.key = key;
    while (ov.firstChild) ov.removeChild(ov.firstChild);
    var mapped = mapBlocks(res, rect);
    for (var i = 0; i < mapped.length; i++) {
      var m = mapped[i], b = m.b;
      if (!b || !b.text || m.w < 3 || m.h < 3) continue;
      var d = document.createElement('div');
      d.className = 'jdt-b';
      var bold = m.h >= 18;
      d.style.left = m.x.toFixed(2) + 'px';
      d.style.top = m.y.toFixed(2) + 'px';
      d.style.width = m.w.toFixed(2) + 'px';
      d.style.height = m.h.toFixed(2) + 'px';
      d.style.background = b.bg || '#FFFFFF';
      d.style.color = b.fg || '#000000';
      d.style.fontWeight = bold ? '700' : '400';
      d.style.fontSize = fitFont(String(b.text), m.w, m.h, bold).toFixed(1) + 'px';
      if (b.angle && Math.abs(b.angle) > 0.5) d.style.transform = 'rotate(' + Number(b.angle) + 'deg)';
      d.textContent = String(b.text);
      ov.appendChild(d);
      S.blocks++;
    }
  }

  /**
   * Stack the overlay exactly like its image: z-index auto keeps it inside the same
   * stacking order (so drawers/popups that cover the image also cover the overlay);
   * an image with an explicit z-index gets the same value (we come later in the DOM,
   * so we paint just above it). Mirror opacity/visibility (lazy-load fade-ins).
   */
  function syncVisibility(st, cs) {
    var ov = st.ov;
    if (!st.bg) {
      var z = cs.position !== 'static' && cs.zIndex !== 'auto' ? cs.zIndex : '';
      if (ov.style.zIndex !== z) ov.style.zIndex = z;
    }
    var hidden = cs.visibility === 'hidden' || parseFloat(cs.opacity) === 0;
    var d = hidden ? 'none' : '';
    if (ov.style.display !== d) ov.style.display = d;
    var op = parseFloat(cs.opacity) < 1 ? cs.opacity : '';
    if (ov.style.opacity !== op) ov.style.opacity = op;
  }

  var meas = null;
  var fontCache = new Map();
  function fitFont(text, w, h, bold) {
    var ck = text + '|' + Math.round(w) + '|' + Math.round(h) + '|' + (bold ? 1 : 0);
    var c = fontCache.get(ck);
    if (c) return c;
    if (!meas || !meas.isConnected) {
      meas = document.createElement('div');
      meas.className = 'jdt-meas';
      meas.setAttribute('aria-hidden', 'true');
      (document.body || document.documentElement).appendChild(meas);
    }
    meas.style.width = w + 'px';
    meas.style.fontWeight = bold ? '700' : '400';
    meas.textContent = text;
    var lo = 6, hi = Math.max(6, Math.min(h * 0.9, 72));
    if (fits(hi, w, h)) lo = hi;
    else {
      for (var i = 0; i < 8 && hi - lo > 0.25; i++) {
        var mid = (lo + hi) / 2;
        if (fits(mid, w, h)) lo = mid; else hi = mid;
      }
    }
    if (fontCache.size > 2000) fontCache.clear();
    fontCache.set(ck, lo);
    return lo;
  }
  function fits(fs, w, h) {
    meas.style.fontSize = fs + 'px';
    return meas.scrollHeight <= h + 0.5 && meas.scrollWidth <= w + 0.5;
  }

  // Keep overlays glued to their images when layout shifts without a resize.
  var realignT = 0;
  function startRealign() {
    if (realignT) return;
    realignT = setInterval(guard('realign', function () {
      if (!imgStates.size) { clearInterval(realignT); realignT = 0; return; }
      if (document.hidden) return;
      imgStates.forEach(function (st) {
        if (!st.el.isConnected || (st.ov && !st.ov.isConnected)) { dropOverlay(st); return; }
        if (st.vis && st.ov && st.box) {
          if (st.el.offsetWidth < 2 || st.el.offsetHeight < 2) { st.ov.style.display = 'none'; st.key = ''; return; }
          if (!st.key) { layoutOverlay(st); return; }
          syncVisibility(st, getComputedStyle(st.el));
          if (st.ov.style.display === 'none') return;
          var ri = st.el.getBoundingClientRect(), ro2 = st.ov.getBoundingClientRect();
          var bx = st.box ? st.box.x : 0, by = st.box ? st.box.y : 0;
          if (Math.abs(ro2.left - ri.left - bx) > 1.5 || Math.abs(ro2.top - ri.top - by) > 1.5) layoutOverlay(st);
        }
      });
    }), 1200);
  }

  function handleRemoved(nodes) {
    for (var i = 0; i < nodes.length; i++) {
      var n = nodes[i];
      if (n.nodeType !== 1) continue;
      var st = IMGST.get(n);
      if (st && st.ov && st.ov.isConnected && !n.isConnected) dropOverlay(st);
    }
  }

  // --- CSS background images (banners): sampled lazily from the viewport -------
  var bgSeen = new WeakMap();
  var bgT = 0;
  function sweepBgSoon() {
    if (bgT) return;
    bgT = setTimeout(guard('bg', function () { bgT = 0; sweepBg(); }), 250);
  }
  function sweepBg() {
    if (!imagesOn || !enabled || !document.elementsFromPoint || !document.body) return;
    var vw = window.innerWidth, vh = window.innerHeight;
    if (!vw || !vh) return;
    var xs = [0.2, 0.5, 0.8], rows = 8, seen = new Set();
    for (var r = 0; r < rows; r++) {
      for (var c = 0; c < xs.length; c++) {
        var stack = document.elementsFromPoint(vw * xs[c], vh * (r + 0.5) / rows);
        for (var k = 0; k < stack.length && k < 10; k++) {
          var el = stack[k];
          if (seen.has(el) || el === document.documentElement || el === document.body) continue;
          seen.add(el);
          if (el.tagName === 'IMG' || isOurs(el)) continue;
          checkBg(el);
        }
      }
    }
  }
  function checkBg(el) {
    var cs = getComputedStyle(el);
    var bi = cs.backgroundImage;
    if (!bi || bi === 'none' || bi.indexOf('url(') === -1) return;
    var m = /url\(\s*(['"]?)(.*?)\1\s*\)/.exec(bi);
    if (!m) return;
    var url;
    try { url = new URL(m[2], location.href).href; } catch (e) { return; }
    if (bgSeen.get(el) === url) return;
    bgSeen.set(el, url);
    if (el.offsetWidth < 120 || el.offsetHeight < 60 || badUrl(url)) return;
    // Only pure banners: an element that carries its own text or <img> content would
    // have that content covered by the overlay (and the text is translated anyway).
    if (!isPureBanner(el)) return;
    if (inSkipped(el)) return;
    var st = IMGST.get(el);
    if (!st) {
      st = { el: el, bg: true, url: '', ov: null, res: null, vis: true, peek: false, key: '' };
      IMGST.set(el, st);
      var o = getIO();
      if (o) o.observe(el);
    }
    if (st.url !== url) { dropOverlay(st); st.url = url; }
    tryBg(el);
  }
  function isPureBanner(el) {
    if (el.querySelector('img,video,canvas,svg,input,button,textarea,select,iframe')) return false;
    var w = document.createTreeWalker(el, 4, null, false), n;
    while ((n = w.nextNode())) {
      if (n.data.trim() && !(n.parentNode && n.parentNode.closest && n.parentNode.closest('.jdt-ov'))) return false;
    }
    return true;
  }

  function tryBg(el) {
    if (!imagesOn || !enabled) return;
    var st = IMGST.get(el);
    if (!st || !st.url || (st.res && !st.res.partial)) return;
    if (st.natW) { requestImage(st, st.url); return; }
    var probe = new Image();
    var url = st.url;
    probe.onload = guard('bgload', function () {
      if (st.url !== url || probe.naturalWidth < 40 || probe.naturalHeight < 20) return;
      st.natW = probe.naturalWidth; st.natH = probe.naturalHeight;
      requestImage(st, url);
    });
    probe.src = url;
  }

  // --- Long-press to peek at the original image --------------------------------
  var lp = null;
  function onTouchStart(e) {
    if (lp) { clearTimeout(lp.t); lp = null; }
    if (!imgStates.size || !e.touches || e.touches.length !== 1) return;
    var t = e.touches[0];
    lp = { x: t.clientX, y: t.clientY, t: setTimeout(guard('peek', firePeek), 500) };
  }
  function onTouchMove(e) {
    if (!lp || !e.touches || !e.touches.length) return;
    var t = e.touches[0];
    if (Math.abs(t.clientX - lp.x) > 10 || Math.abs(t.clientY - lp.y) > 10) { clearTimeout(lp.t); lp = null; }
  }
  function onTouchEnd() { if (lp) { clearTimeout(lp.t); lp = null; } }
  function firePeek() {
    if (!lp) return;
    var x = lp.x, y = lp.y;
    lp = null;
    var stack = document.elementsFromPoint ? document.elementsFromPoint(x, y) : [];
    for (var i = 0; i < stack.length; i++) {
      var st = IMGST.get(stack[i]);
      if (st && st.ov) {
        st.peek = !st.peek;
        st.ov.classList.toggle('jdt-peek', st.peek);
        S.peeks++;
        return;
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Frames: relay (top) and control broadcast
  // ---------------------------------------------------------------------------
  var relays = new Map();     // top-frame reqId -> {src, id}

  function relayBack(id, kind, json) {
    var r = relays.get(id);
    relays.delete(id);
    if (!r) return;
    try { r.src.postMessage({ __jdt: 1, k: kind, id: r.id, p: json }, '*'); } catch (e) { err('relay', e); }
  }

  function broadcast(msg) {
    if (!IS_TOP) return;
    (function walk(w, depth) {
      if (depth > 6) return;
      var n = 0;
      try { n = w.frames.length; } catch (e) { return; }
      for (var i = 0; i < n; i++) {
        try { w.frames[i].postMessage(msg, '*'); walk(w.frames[i], depth + 1); } catch (e) { /* detached */ }
      }
    })(window, 0);
  }

  function onMessage(e) {
    var d = e.data;
    if (!d || typeof d !== 'object' || d.__jdt !== 1) return;
    if (IS_TOP) {
      var src = e.source;
      if (!src || src === window) return;
      if (d.k === 'cfg') {
        src.postMessage({ __jdt: 1, k: 'ctl', op: 'cfg', enabled: enabled, images: imagesOn }, '*');
        return;
      }
      if ((d.k !== 'batch' && d.k !== 'image') || typeof d.id !== 'string') return;
      var nid = 'R' + FRAME_ID + (++seq);   // nonce: never matches a relay of another document
      relays.set(nid, { src: src, id: d.id });
      setTimeout(function () { relays.delete(nid); }, 90000);
      S.relayed++;
      try {
        if (d.k === 'batch') N.translateBatch(nid, typeof d.p === 'string' ? d.p : JSON.stringify(d.p));
        else N.translateImage(nid, String(d.p));
      } catch (x) { err('relay-call', x); relays.delete(nid); }
      return;
    }
    // Sub-frame: only trust our own top frame.
    var top = null;
    try { top = window.top; } catch (x) { return; }
    if (e.source !== top) return;
    if (d.k === 'rb') onBatch(d.id, d.p);
    else if (d.k === 'ri') onImage(d.id, d.p);
    else if (d.k === 'ctl') {
      if (d.op === 'enabled') setEnabled(d.v);
      else if (d.op === 'images') setImages(d.v);
      else if (d.op === 'rescan') rescan();
      else if (d.op === 'retry') retry();
      else if (d.op === 'cfg') {
        if (d.images !== imagesOn) setImages(d.images);
        if (d.enabled !== enabled) setEnabled(d.enabled);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Debug helpers (used by tools/jstest)
  // ---------------------------------------------------------------------------
  function why(node) {
    if (!node) return 'none';
    if (node.nodeType === 3) {
      if (!HAN.test(node.data)) return 'translated-or-no-han';
      if (inSkipped(node)) return 'skipped-subtree';
      var rec = TX.get(node);
      if (!rec) return 'not-seen';
      if (rec.cnt > MAX_REWRITES) return 'rewrite-loop';
      var c = cache.get(rec.key);
      if (c === null) return 'translator-null';
      if (c) return node.data === rec.orig ? 'cached-not-applied' : 'changed';
      if (pending.has(rec.key)) return 'in-flight';
      if (queue.has(rec.key)) return 'queued';
      return 'waiting(retry)';
    }
    return 'element';
  }

  function stats() {
    var o = {};
    for (var k in S) o[k] = S[k];
    o.enabled = enabled;
    o.images = imagesOn;
    o.frame = IS_TOP ? 'top' : 'sub';
    o.cache = cache.size;
    o.queue = queue.size;
    o.pending = pending.size;
    o.inflight = inflight;
    o.waiters = waiters.size;
    o.imgInflight = imgInflight;
    o.imgQueue = imgQ.length;
    o.firstBatchAfterDCL = S.tDCL && S.tFirstBatch ? Math.round(S.tFirstBatch - S.tDCL) : null;
    return o;
  }

  // ---------------------------------------------------------------------------
  // Public API
  // ---------------------------------------------------------------------------
  window.__jdt = {
    onBatch: guard('onBatch', onBatch),
    onImage: guard('onImage', onImage),
    setEnabled: guard('setEnabled', setEnabled),
    setImages: guard('setImages', setImages),
    rescan: guard('rescan', rescan),
    retry: guard('retry', retry),
    stats: guard('stats', stats),
    _why: guard('why', why),
    _fitRect: fitRect,
    _bgRect: bgRect,
    _mapBlocks: mapBlocks,
    _overlayOf: function (el) { var st = IMGST.get(el); return st ? st.ov : null; },
    _imgState: function (el) {
      var st = IMGST.get(el);
      if (!st) return null;
      var e = imgCache.get(st.url);
      return { url: st.url, vis: st.vis, bg: st.bg, overlay: !!st.ov, result: !!st.res, peek: st.peek, req: e ? e.st : null };
    }
  };

  // ---------------------------------------------------------------------------
  // Boot
  // ---------------------------------------------------------------------------
  try {
    observeRoot(document);
    if (!enabled) setRootClass('jdt-off', true);
    if (!imagesOn) setRootClass('jdt-noimg', true);

    // Open shadow roots created after we started.
    try {
      var origAttach = Element.prototype.attachShadow;
      if (typeof origAttach === 'function') {
        Element.prototype.attachShadow = function () {
          var root = origAttach.apply(this, arguments);
          try {
            if (root && arguments[0] && arguments[0].mode === 'open') {
              shadowRoots.add(root);
              observeRoot(root);
              queueScan(root);
            }
          } catch (e) { err('attachShadow', e); }
          return root;
        };
      }
    } catch (e) { err('patch-shadow', e); }

    // SPA routing.
    ['pushState', 'replaceState'].forEach(function (m) {
      try {
        var orig = history[m];
        if (typeof orig !== 'function') return;
        history[m] = function () {
          var r = orig.apply(this, arguments);
          try { onRoute(); } catch (e) { err('route', e); }
          return r;
        };
      } catch (e) { err('patch-history', e); }
    });
    window.addEventListener('popstate', guard('popstate', onRoute));
    window.addEventListener('hashchange', guard('hashchange', onRoute));
    window.addEventListener('pageshow', guard('pageshow', function (e) { if (e.persisted) onRoute(); }));
    window.addEventListener('message', guard('message', onMessage));

    // Images: a load anywhere (load doesn't bubble, so capture) may make an image eligible.
    document.addEventListener('load', guard('imgload', function (e) {
      var t = e.target;
      if (!t || t.tagName !== 'IMG') return;
      var st = IMGST.get(t);
      if (!st) { if (imagesOn && !inSkipped(t)) registerImg(t); return; }
      if (st.vis) tryImage(t);
      else if (st.ov) layoutOverlay(st);
    }), true);
    var scrollT = 0;
    window.addEventListener('scroll', function () {
      if (scrollT) clearTimeout(scrollT);
      scrollT = setTimeout(guard('scrollend', function () { scrollT = 0; sweepBg(); }), 300);
    }, { passive: true, capture: true });
    window.addEventListener('resize', guard('resize', function () {
      imgStates.forEach(function (st) { if (st.ov) layoutOverlay(st); });
    }), { passive: true });
    document.addEventListener('touchstart', guard('ts', onTouchStart), { passive: true, capture: true });
    document.addEventListener('touchmove', guard('tm', onTouchMove), { passive: true, capture: true });
    document.addEventListener('touchend', guard('te', onTouchEnd), { passive: true, capture: true });
    document.addEventListener('touchcancel', guard('tc', onTouchEnd), { passive: true, capture: true });

    var onReady = guard('ready', function () {
      if (!S.tDCL) S.tDCL = now();
      ensureStyle();
      queueScan(document);
      if (!HAN.test(document.title || '')) reportTitle();
      setTimeout(guard('bg0', sweepBg), 600);
    });
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', onReady);
      if (document.documentElement) queueScan(document);   // translate early content ASAP
    } else {
      onReady();
    }
    window.addEventListener('load', guard('load', function () { sweepBgSoon(); }));

    if (!IS_TOP) {
      try { window.top.postMessage({ __jdt: 1, k: 'cfg' }, '*'); } catch (e) { /* ignore */ }
    }
  } catch (e) {
    err('boot', e);
  }
})();
