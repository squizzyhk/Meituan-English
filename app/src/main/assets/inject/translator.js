/*
 * MT English page translator.
 *
 * Injected at document start into every page/frame of the built-in browser. Finds Chinese text in
 * text nodes, translatable attributes, the document title and large images, asks the native app for
 * English through `window.MTEBridge.postMessage(json)`, and writes the English back in place while
 * remembering the originals so everything can be restored exactly.
 *
 * Protocol (all messages are JSON strings):
 *   page -> native  {type:"translate", reqId, items:[{t:text, k:kind, c:context}]}
 *                   {type:"image", reqId, url}
 *   native -> page  window.__mte.receive(reqId, {source: english, ...}, done)   (done=false for partial chunks;
 *                   native acknowledges every request at once with a partial, possibly empty, chunk)
 *                   window.__mte.receiveImage(reqId, url, translatedUrlOrNull)
 *                   or the same as messages on MTEBridge ("message" event):
 *                   {type:"translated", reqId, map, done} / {type:"image", reqId, url, result}
 *
 * Self-contained, ES2017, no dependencies. Safe to inject more than once.
 */
(function () {
  'use strict';
  var W = window;
  var D = document;
  // Guard per document: an iframe's initial about:blank Window is reused by a same-origin navigation,
  // so a Window-level flag alone would leave the new document untranslated.
  if (W.__mte && W.__mte.__installed && W.__mte.__doc === D) return;

  function getBridge() {
    var b = W.MTEBridge;
    return b && typeof b.postMessage === 'function' ? b : null;
  }

  // In a child frame that cannot receive replies directly (bridge without message events), stay out of
  // the way: the parent frame translates same-origin frames itself.
  var isTopFrame = true;
  try { isTopFrame = W.top === W; } catch (e) { isTopFrame = false; }
  var initialBridge = getBridge();
  if (!isTopFrame && !(initialBridge && typeof initialBridge.addEventListener === 'function')) return;

  var CFG = W.__mteConfig || {};
  var enabled = CFG.enabled !== false;
  var imagesOn = CFG.images !== false;
  var longPressOn = CFG.longPress !== false;

  var HAN = /[㐀-鿿豈-﫿]/;
  var ATTRS = ['placeholder', 'title', 'aria-label', 'alt'];
  var OBSERVED_ATTRS = ATTRS.concat(['value', 'src', 'srcset', 'style']);
  var BUTTON_INPUT = /^(button|submit|reset)$/i;
  var TEXT_SKIP = { SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, TEXTAREA: 1, CODE: 1, TEMPLATE: 1, IFRAME: 1 };
  var SKIP_SELECTOR = 'script,style,noscript,textarea,code,template,[data-mte-ui]';
  var ATTR_SKIP_SELECTOR = 'script,style,noscript,template,[data-mte-ui]';
  var BATCH_MS = 60;
  var BATCH_MAX = 80;
  var MAX_TEXT = 3000;
  var ACK_TIMEOUT = 10000;      // no chunk at all by then: the message or its reply was lost
  var REQUEST_TIMEOUT = 120000; // after the native acknowledgement (slow LLM batches)
  var MAX_RETRIES = 3;
  var CACHE_MAX = 5000;
  var TEMPLATE_MAX = 2000;
  var WAITERS_PRUNE_AT = 64;
  var OSC_PER_TICK = 5;         // same text re-applied this often without yielding: the page fights back
  var OSC_PER_SECOND = 20;
  var UNIT_MAX_CHILDREN = 12;
  var UNIT_MAX_TEXT = 80;
  var INLINE = { B: 1, I: 1, EM: 1, STRONG: 1, SPAN: 1, SMALL: 1, BIG: 1, SUB: 1, SUP: 1, U: 1, S: 1, FONT: 1, MARK: 1, DEL: 1, INS: 1 };
  var RX_FRAGMENT = /[\d¥￥$+%]/;
  var MAX_IMG_INFLIGHT = 4;
  var MIN_IMG_AREA = 120 * 60;
  var IMG_TIMEOUT = 90000;
  var LONG_PRESS_MS = 600;
  var FAKE_HOST = 'mte.local';
  var MO_CFG = { childList: true, subtree: true, characterData: true, attributes: true, attributeFilter: OBSERVED_ATTRS };

  var frameId = Math.random().toString(36).slice(2, 8);
  var seq = 0;
  function nextId(prefix) { seq += 1; return prefix + frameId + '-' + seq; }

  // ---------------------------------------------------------------- state
  var cache = new Map();      // chinese core text -> english (LRU, CACHE_MAX)
  var templates = new Map();  // core with digit runs replaced -> {parts, map}  (countdowns, live counts)
  var waiters = new Map();    // core -> Set(target)  (Text node or {el, attr})
  var queued = new Map();     // core -> {k, el}      waiting to be sent
  var inflight = new Map();   // core -> reqId
  var requests = new Map();   // reqId -> {cores, timer}
  var failures = new Map();   // core -> {n, until, k}
  var textRec = new WeakMap();  // Text -> {orig, core, applied, unit?}
  var attrRec = new WeakMap();  // Element -> {attr: {orig, core, applied, target, added}}
  var imgRec = new WeakMap();   // Element -> image replacement record
  var wrapMarked = new WeakSet();
  var attachedRoots = new WeakSet();
  var closedHosts = new WeakMap(); // host element -> its closed shadow root
  var oscState = new WeakMap();    // parent -> {orig, tick, nTick, t0, n}
  var oscFlag = new WeakSet();     // parents whose text the page keeps reverting: left alone
  var titleTries = new WeakMap();
  var tick = 0;
  var tickScheduled = false;
  var attachedDocs = new WeakSet();
  var hookedFrames = new WeakSet();
  var loadHooked = new WeakSet();
  var flushTimer = 0;
  var bridgeRetries = 0;
  var observer = null;
  var styleEl = null;
  var layoutQueue = [];
  var layoutScheduled = false;
  var counters = {
    messages: 0, translateRequests: 0, itemsSent: 0, received: 0, applied: 0, cacheHits: 0,
    imagesRequested: 0, imagesReplaced: 0, failures: 0
  };

  // ---------------------------------------------------------------- helpers
  function split(v) {
    var m = /^(\s*)([\s\S]*?)(\s*)$/.exec(v);
    return [m[1], m[2], m[3]];
  }

  function classString(e) {
    var c = e.className;
    if (typeof c !== 'string') c = (e.getAttribute && e.getAttribute('class')) || '';
    return (c + ' ' + (e.id || '')).toLowerCase();
  }

  function isSkipped(el) {
    if (!el || el.nodeType !== 1) return false;
    if (el.isContentEditable) return true;
    try { return !!el.closest(SKIP_SELECTOR); } catch (e) { return false; }
  }

  function textWidth(s) {
    var w = 0;
    for (var i = 0; i < s.length; i++) w += HAN.test(s.charAt(i)) ? 2 : 1;
    return w;
  }

  function post(obj) {
    var b = getBridge();
    if (!b) return false;
    try {
      b.postMessage(JSON.stringify(obj));
      counters.messages += 1;
      return true;
    } catch (e) {
      return false;
    }
  }

  function trunc(s, n) {
    s = (s || '').replace(/\s+/g, ' ').trim();
    return s.length > n ? s.slice(0, n) : s;
  }

  /** Original (pre-translation) text content of an element. */
  function originalText(el, limit) {
    var out = '';
    var tw = (el.ownerDocument || D).createTreeWalker(el, NodeFilter.SHOW_TEXT, null);
    var n, count = 0;
    while ((n = tw.nextNode()) && count < 20) {
      count += 1;
      var rec = textRec.get(n);
      out += rec && rec.applied !== null && n.nodeValue === rec.applied ? rec.orig : n.nodeValue;
      if (out.length > limit * 2) break;
    }
    return trunc(out, limit);
  }

  function pageTitle() {
    var t = D.querySelector('title');
    return t ? originalText(t, 40) : '';
  }

  var STORE_SEL = [
    '[class*="shop-name"]', '[class*="shopName"]', '[class*="shop_name"]', '[class*="poi-name"]',
    '[class*="poiName"]', '[class*="poi_name"]', '[class*="store-name"]', '[class*="storeName"]',
    '[class*="merchant-name"]', '[class*="restaurant-name"]'
  ].join(',');

  function pageContext() {
    var title = pageTitle();
    var store = '';
    try {
      var els = D.querySelectorAll(STORE_SEL);
      if (els.length === 1) store = originalText(els[0], 30);
    } catch (e) { /* ignore */ }
    if (store && title && title.indexOf(store) < 0) return trunc(title + ' | ' + store, 60);
    return trunc(store || title, 60);
  }

  // ---------------------------------------------------------------- cache
  function cacheSet(core, en) {
    if (cache.has(core)) cache.delete(core);
    cache.set(core, en);
    if (cache.size > CACHE_MAX) cache.delete(cache.keys().next().value);
    learnTemplate(core, en);
  }

  function cacheGet(core) {
    var en = cache.get(core);
    if (en !== undefined) {
      cache.delete(core);
      cache.set(core, en);
      return en;
    }
    return fromTemplate(core);
  }

  // Strings that differ only in their numbers ("剩余12秒", "剩余11秒") reuse one translation when the
  // English carries the same numbers unchanged ("12 sec left"). Never for a 1 (plurals) or for
  // numbers of 4+ digits (thousands separators), and only when every number maps unambiguously.
  var RX_DIGITS = /\d+/g;

  function templateKey(s) { return s.replace(RX_DIGITS, '\u0000'); }

  function templatable(runs) {
    if (!runs.length || runs.length > 4) return false;
    for (var i = 0; i < runs.length; i++) {
      if (runs[i] === '1' || runs[i].length > 3) return false;
    }
    return true;
  }

  function learnTemplate(core, en) {
    var runs = core.match(RX_DIGITS);
    if (!runs || !templatable(runs)) return;
    var unique = true;
    for (var i = 0; i < runs.length; i++) if (runs.indexOf(runs[i]) !== i) unique = false;
    var parts = [];
    var map = [];
    var last = 0;
    var rx = /\d+/g;
    var m;
    while ((m = rx.exec(en))) {
      var before = en.charAt(m.index - 1);
      var after = en.charAt(m.index + m[0].length);
      if (/[A-Za-z0-9]/.test(before) || /[A-Za-z0-9]/.test(after)) return;
      var j = map.length;
      var idx = unique ? runs.indexOf(m[0]) : (runs[j] === m[0] ? j : -1);
      if (idx < 0 || map.indexOf(idx) >= 0) return;
      parts.push(en.slice(last, m.index));
      map.push(idx);
      last = m.index + m[0].length;
    }
    if (map.length !== runs.length) return;
    parts.push(en.slice(last));
    var key = templateKey(core);
    if (templates.has(key)) templates.delete(key);
    templates.set(key, { parts: parts, map: map });
    if (templates.size > TEMPLATE_MAX) templates.delete(templates.keys().next().value);
  }

  function fromTemplate(core) {
    if (!templates.size) return undefined;
    var runs = core.match(RX_DIGITS);
    if (!runs || !templatable(runs)) return undefined;
    var t = templates.get(templateKey(core));
    if (!t || t.map.length !== runs.length) return undefined;
    var out = t.parts[0];
    for (var j = 0; j < t.map.length; j++) out += runs[t.map[j]] + t.parts[j + 1];
    return out;
  }

  // ---------------------------------------------------------------- oscillation guard
  function bumpTick() {
    if (tickScheduled) return;
    tickScheduled = true;
    setTimeout(function () { tickScheduled = false; tick += 1; }, 0);
  }

  /**
   * False when the page keeps putting the same Chinese back under [p] (its own observer reverting our
   * write): re-applying would loop forever in microtasks or burn CPU every frame.
   */
  function allowApply(p, orig) {
    if (!p) return true;
    if (oscFlag.has(p)) return false;
    bumpTick();
    var now = Date.now();
    var o = oscState.get(p);
    if (!o || o.orig !== orig) {
      oscState.set(p, { orig: orig, tick: tick, nTick: 1, t0: now, n: 1 });
      return true;
    }
    if (o.tick === tick) o.nTick += 1; else { o.tick = tick; o.nTick = 1; }
    if (now - o.t0 > 1000) { o.t0 = now; o.n = 0; }
    o.n += 1;
    if (o.nTick > OSC_PER_TICK || o.n > OSC_PER_SECOND) {
      oscFlag.add(p);
      return false;
    }
    return true;
  }

  // ---------------------------------------------------------------- classification
  var RX_STORE = /(shop|store|poi|merchant|restaurant|seller|brand)[-_]?(name|title)/;
  var RX_DISH = /(food|dish|goods|spu|sku|product|item|menu)[-_]?(name|title)|caipin/;
  var RX_REVIEW = /comment|review|evaluat|pingjia|pinglun|feedback/;
  var RX_ADDRESS = /addr|location|dizhi/;
  var RX_DESC = /desc|intro|detail|summary|notice|announce|bulletin/;
  var RX_MENU = /food|dish|goods|spu|sku|menu|product|caipin/;
  var RX_ROLE = /^(button|tab|menuitem|link|navigation|tablist|menubar|checkbox|radio|switch|option)$/;
  var RX_PRICE = /[¥￥]\s*\d/;

  function classify(el, text, attr) {
    if (attr === 'placeholder') return 'FORM_HINT';
    var long = text.length > 40;
    var ui = attr === 'title' || attr === 'aria-label' || attr === 'value';
    var e, d;
    for (e = el, d = 0; e && d < 3; e = e.parentElement, d++) {
      var tag = e.tagName;
      if (tag === 'BUTTON' || tag === 'A' || tag === 'NAV' || tag === 'LABEL' || tag === 'OPTION' ||
          tag === 'SELECT' || tag === 'TH' || tag === 'LEGEND' || RX_ROLE.test(e.getAttribute('role') || '')) {
        ui = true;
      }
      if (d <= 1) {
        var near = classString(e);
        if (RX_STORE.test(near)) return 'STORE_NAME';
        if (RX_DISH.test(near)) return long ? 'DESCRIPTION' : 'MENU_ITEM';
      }
    }
    if (ui && !long) return 'UI_LABEL';
    for (e = el, d = 0; e && d < 6; e = e.parentElement, d++) {
      var cls = classString(e);
      if (cls.length < 2) continue;
      if (RX_REVIEW.test(cls)) return 'REVIEW';
      if (RX_ADDRESS.test(cls)) return 'ADDRESS';
      if (RX_DESC.test(cls)) return 'DESCRIPTION';
      if (RX_MENU.test(cls)) return long ? 'DESCRIPTION' : 'MENU_ITEM';
    }
    if (long) return 'DESCRIPTION';
    if (attr !== 'alt' && el && text.length <= 30) {
      // Only small rows: reading textContent of a huge container for every string is quadratic.
      var p = el.parentElement;
      if (p && p.childNodes.length <= 12) {
        var around = p.textContent || '';
        if (around.length < 200 && RX_PRICE.test(around)) return 'MENU_ITEM';
      }
    }
    return 'GENERIC';
  }

  // ---------------------------------------------------------------- requesting
  function need(core, target, el, attr) {
    if (core.length > MAX_TEXT) return;
    var f = failures.get(core);
    if (f && f.until === Infinity) return; // given up for this page: do not hold on to the target
    var set = waiters.get(core);
    if (!set) { set = new Set(); waiters.set(core, set); }
    if (set.size >= WAITERS_PRUNE_AT) pruneWaiters(core);
    set.add(target);
    if (inflight.has(core) || queued.has(core)) return;
    if (f && Date.now() < f.until) return;
    queued.set(core, { k: classify(el, core, attr), el: null });
    scheduleFlush();
  }

  function targetAlive(t) {
    if (t.nodeType === 3) return t.isConnected;
    if (t.unit) return t.container.isConnected;
    return !!(t.el && t.el.isConnected);
  }

  /** Drops targets the page has thrown away; true when someone still waits for [core]. */
  function pruneWaiters(core) {
    var set = waiters.get(core);
    if (!set) return false;
    set.forEach(function (t) { if (!targetAlive(t)) set.delete(t); });
    if (!set.size) { waiters.delete(core); return false; }
    return true;
  }

  function scheduleFlush() {
    if (queued.size >= BATCH_MAX) {
      if (flushTimer) clearTimeout(flushTimer);
      flushTimer = setTimeout(flush, 0);
    } else if (!flushTimer) {
      flushTimer = setTimeout(flush, BATCH_MS);
    }
  }

  function flush() {
    flushTimer = 0;
    if (!queued.size) return;
    if (!enabled) { queued.clear(); return; }
    if (!getBridge()) {
      bridgeRetries += 1;
      if (bridgeRetries < 40) flushTimer = setTimeout(flush, 500);
      return;
    }
    bridgeRetries = 0;
    var ctx = pageContext();
    var title = pageTitle();
    var items = [];
    var cores = [];
    var it = queued.entries();
    var step;
    while (items.length < BATCH_MAX && !(step = it.next()).done) {
      var core = step.value[0];
      var q = step.value[1];
      var c = q.k === 'STORE_NAME' || core === title ? title : ctx;
      var item = { t: core, k: q.k };
      if (c && c !== core) item.c = c;
      items.push(item);
      cores.push(core);
    }
    var reqId = nextId('t');
    for (var i = 0; i < cores.length; i++) {
      queued.delete(cores[i]);
      inflight.set(cores[i], reqId);
    }
    requests.set(reqId, { cores: cores, acked: false, timer: setTimeout(function () { finish(reqId); }, ACK_TIMEOUT) });
    if (post({ type: 'translate', reqId: reqId, items: items })) {
      counters.translateRequests += 1;
      counters.itemsSent += items.length;
    } else {
      // Bridge vanished: put the work back and try again shortly.
      var req = requests.get(reqId);
      clearTimeout(req.timer);
      requests.delete(reqId);
      for (var j = 0; j < cores.length; j++) {
        inflight.delete(cores[j]);
        queued.set(cores[j], { k: 'GENERIC', el: null });
      }
      flushTimer = setTimeout(flush, 500);
      return;
    }
    if (queued.size) scheduleFlush();
  }

  function finish(reqId) {
    var req = requests.get(reqId);
    if (!req) return;
    requests.delete(reqId);
    clearTimeout(req.timer);
    for (var i = 0; i < req.cores.length; i++) {
      var core = req.cores[i];
      if (inflight.get(core) === reqId) {
        inflight.delete(core);
        markFailed(core);
      }
    }
  }

  /**
   * [untranslatable]: native answered with the source itself (nothing could translate it, e.g.
   * offline without a key). That is retried once, later; a lost or empty reply up to MAX_RETRIES times.
   */
  function markFailed(core, untranslatable) {
    counters.failures += 1;
    var f = failures.get(core) || { n: 0, until: 0 };
    f.n += untranslatable ? MAX_RETRIES : 1;
    if (f.n > MAX_RETRIES) {
      f.until = Infinity;
      failures.set(core, f);
      waiters.delete(core);
      return;
    }
    var delay = 4000 * Math.pow(2, f.n - 1);
    f.until = Date.now() + delay;
    failures.set(core, f);
    pruneWaiters(core);
    setTimeout(function () {
      if (!enabled || !pruneWaiters(core) || inflight.has(core) || queued.has(core) || cache.has(core)) return;
      queued.set(core, { k: 'GENERIC', el: null });
      scheduleFlush();
    }, delay + 10);
  }

  function deliver(core, en) {
    if (typeof en !== 'string') return;
    inflight.delete(core);
    var trimmed = en.trim();
    if (!trimmed) {
      markFailed(core, false);
      return;
    }
    if (trimmed === core && HAN.test(trimmed)) {
      markFailed(core, true);
      return;
    }
    cacheSet(core, trimmed);
    failures.delete(core);
    var set = waiters.get(core);
    waiters.delete(core);
    if (!set || !enabled) return;
    set.forEach(function (t) { applyTarget(t, core, trimmed); });
  }

  function receive(reqId, map, done) {
    counters.received += 1;
    var req = requests.get(reqId);
    if (req && done === false && !req.acked) {
      // Native is working on it: switch from the short "lost message" timeout to the long one.
      req.acked = true;
      clearTimeout(req.timer);
      req.timer = setTimeout(function () { finish(reqId); }, REQUEST_TIMEOUT);
    }
    if (map && typeof map === 'object') {
      for (var src in map) {
        if (Object.prototype.hasOwnProperty.call(map, src)) deliver(src, map[src]);
      }
    }
    if (done !== false) finish(reqId);
  }

  // ---------------------------------------------------------------- applying text
  function applyTarget(t, core, en) {
    if (t.nodeType === 3) {
      var rec = textRec.get(t);
      if (!rec || rec.unit || rec.applied !== null || rec.core !== core || t.nodeValue !== rec.orig) return;
      applyText(t, rec, en);
    } else if (t.unit) {
      if (t.core === core && t.applied === null) applyUnit(t, en);
    } else if (t.el) {
      var recs = attrRec.get(t.el);
      var r = recs && recs[t.attr];
      if (!r || r.applied !== null || r.core !== core || currentAttr(t.el, t.attr) !== r.orig) return;
      applyAttr(t.el, t.attr, r, en);
    }
  }

  function applyText(node, rec, en) {
    var p = node.parentNode;
    if (!allowApply(p, rec.orig)) return;
    var parts = split(rec.orig);
    var out = parts[0] + en + parts[2];
    if (p && p.tagName === 'OPTION') preserveOptionValue(p);
    rec.applied = out;
    if (node.nodeValue !== out) node.nodeValue = out;
    counters.applied += 1;
    afterApply(p, en, rec.core);
  }

  function afterApply(p, en, core) {
    if (p && p.nodeType === 1 && p.tagName !== 'TITLE' && p.tagName !== 'OPTION' &&
        en.length > textWidth(core) * 1.25 + 2) {
      layoutQueue.push(p);
      scheduleLayout();
    }
  }

  /**
   * An <option> without a value attribute submits its text: pin the original text as its value
   * before translating so forms still post what the site expects. Removed again on restore.
   */
  function preserveOptionValue(opt) {
    if (opt.hasAttribute('value')) return;
    var v = opt.value;
    var recs = attrRec.get(opt);
    if (!recs) { recs = {}; attrRec.set(opt, recs); }
    recs.value = { orig: null, core: null, applied: v, added: true, target: null };
    opt.setAttribute('value', v);
  }

  /** Text of [n] as the page wrote it (undoing our translation if it is still in place). */
  function origPiece(n) {
    var rec = textRec.get(n);
    return rec && rec.applied !== null && n.nodeValue === rec.applied ? rec.orig : n.nodeValue;
  }

  function handleText(node, trusted) {
    if (!enabled) return;
    var v = node.nodeValue;
    var rec = textRec.get(node);
    if (rec && rec.unit) {
      if (v === (rec.applied !== null ? rec.applied : rec.orig)) return;
      unitPieceChanged(node, rec.unit);
      return;
    }
    if (!v || !HAN.test(v)) return;
    if (rec && (rec.applied !== null ? v === rec.applied : v === rec.orig)) return;
    var p = node.parentNode;
    if (!p) return;
    if (oscFlag.has(p)) return;
    if (!trusted && p.nodeType === 1 && isSkipped(p)) return;
    var unit = findUnit(node);
    if (unit) {
      handleUnit(unit);
      return;
    }
    var parts = split(v);
    rec = { orig: v, core: parts[1], applied: null };
    textRec.set(node, rec);
    var en = cacheGet(parts[1]);
    if (en !== undefined) {
      counters.cacheHits += 1;
      applyText(node, rec, en);
      return;
    }
    need(parts[1], node, p.nodeType === 1 ? p : p.host || null, null);
  }

  // ---------------------------------------------------------------- inline units
  // A phrase split over adjacent text nodes or inline elements ("月售" "1000" "+", or
  // 满<b>30</b>减<b>5</b>) is translated as one string: the English goes into the first node and the
  // other pieces are emptied (every node keeps its own original for exact restore). Only merged when
  // some piece is a number/price fragment (or the Chinese itself is split over raw text nodes) and at
  // most one inline element holds Chinese, so separate labels in a row are never glued together.

  /** Collects the text nodes of an inline-only element; false if it holds anything else. */
  function inlineTexts(el, out, depth) {
    var kids = el.childNodes;
    for (var i = 0; i < kids.length; i++) {
      var k = kids[i];
      if (k.nodeType === 3) out.push(k);
      else if (k.nodeType === 1) {
        if (!INLINE[k.tagName] || depth >= 2 || k.hasAttribute('data-mte-ui') || k.shadowRoot || closedHosts.has(k)) return false;
        if (!inlineTexts(k, out, depth + 1)) return false;
      } else if (k.nodeType !== 8) return false;
    }
    return true;
  }

  function unitNodes(c) {
    if (!c || c.nodeType !== 1 || TEXT_SKIP[c.tagName] || c.tagName === 'TITLE' || c.hasAttribute('data-mte-ui')) return null;
    var kids = c.childNodes;
    if (kids.length < 2 || kids.length > UNIT_MAX_CHILDREN) return null;
    var nodes = [];
    var len = 0, hanEls = 0, hanTexts = 0, fragments = 0;
    for (var i = 0; i < kids.length; i++) {
      var k = kids[i];
      var piece = '';
      if (k.nodeType === 3) {
        nodes.push(k);
        piece = origPiece(k);
        if (HAN.test(piece)) hanTexts += 1;
        else if (RX_FRAGMENT.test(piece)) fragments += 1;
      } else if (k.nodeType === 1) {
        if (!INLINE[k.tagName] || k.hasAttribute('data-mte-ui') || k.shadowRoot || closedHosts.has(k)) return null;
        var sub = [];
        if (!inlineTexts(k, sub, 1)) return null;
        for (var j = 0; j < sub.length; j++) { nodes.push(sub[j]); piece += origPiece(sub[j]); }
        if (HAN.test(piece)) hanEls += 1;
        else if (RX_FRAGMENT.test(piece)) fragments += 1;
      } else if (k.nodeType !== 8) {
        return null;
      }
      len += piece.length;
      if (len > UNIT_MAX_TEXT) return null;
    }
    if (nodes.length < 2 || hanEls > 1 || hanEls + hanTexts === 0) return null;
    if (!fragments && hanTexts < 2) return null;
    return nodes;
  }

  function findUnit(node) {
    var p = node.parentNode;
    if (!p || p.nodeType !== 1) return null;
    var nodes;
    if (INLINE[p.tagName] && p.parentNode && p.parentNode.nodeType === 1 && (nodes = unitNodes(p.parentNode))) {
      return { container: p.parentNode, nodes: nodes };
    }
    if (p.childNodes.length < 2) return null;
    nodes = unitNodes(p);
    return nodes ? { container: p, nodes: nodes } : null;
  }

  function handleUnit(found) {
    var nodes = found.nodes;
    var pieces = [];
    var joined = '';
    var i, r;
    for (i = 0; i < nodes.length; i++) { pieces.push(origPiece(nodes[i])); joined += pieces[i]; }
    var r0 = textRec.get(nodes[0]);
    var prev = r0 && r0.unit;
    if (prev && prev.orig === joined && prev.nodes.length === nodes.length) {
      var same = true;
      for (i = 0; i < nodes.length && same; i++) {
        r = textRec.get(nodes[i]);
        same = !!r && r.unit === prev && prev.nodes[i] === nodes[i] &&
          nodes[i].nodeValue === (r.applied !== null ? r.applied : r.orig);
      }
      if (same) return;
    }
    if (!HAN.test(joined) || oscFlag.has(found.container)) return;
    // Put every piece back to the page's text before translating the phrase as a whole.
    for (i = 0; i < nodes.length; i++) if (nodes[i].nodeValue !== pieces[i]) nodes[i].nodeValue = pieces[i];
    var core = split(joined)[1].replace(/\s+/g, ' ');
    var u = { unit: true, container: found.container, nodes: nodes, orig: joined, core: core, applied: null };
    for (i = 0; i < nodes.length; i++) textRec.set(nodes[i], { orig: pieces[i], core: core, applied: null, unit: u });
    var en = cacheGet(core);
    if (en !== undefined) {
      counters.cacheHits += 1;
      applyUnit(u, en);
      return;
    }
    need(core, u, found.container, null);
  }

  function applyUnit(u, en) {
    var nodes = u.nodes;
    var i, r;
    for (i = 0; i < nodes.length; i++) {
      r = textRec.get(nodes[i]);
      if (!r || r.unit !== u || nodes[i].nodeValue !== r.orig || !nodes[i].parentNode) return;
    }
    if (!allowApply(u.container, u.orig)) return;
    var first = 0;
    for (i = 0; i < nodes.length; i++) { if (nodes[i].nodeValue.trim()) { first = i; break; } }
    var out = split(textRec.get(nodes[first]).orig)[0] + en + split(u.orig)[2];
    if (u.container.tagName === 'OPTION') preserveOptionValue(u.container);
    for (i = 0; i < nodes.length; i++) {
      r = textRec.get(nodes[i]);
      var v = i < first ? r.orig : (i === first ? out : '');
      r.applied = v;
      if (nodes[i].nodeValue !== v) nodes[i].nodeValue = v;
    }
    u.applied = en;
    counters.applied += 1;
    afterApply(u.container, en, u.core);
  }

  /** The page changed one piece of a unit (e.g. a live count): rebuild it from the page's text. */
  function unitPieceChanged(node, u) {
    textRec.delete(node); // its new value is the page's new original
    var found = node.parentNode ? findUnit(node) : null;
    if (found) {
      handleUnit(found);
      return;
    }
    // No longer a unit: give every piece its own text back and translate them one by one.
    var rest = [];
    for (var i = 0; i < u.nodes.length; i++) {
      var n = u.nodes[i];
      if (n === node) continue;
      var r = textRec.get(n);
      if (!r || r.unit !== u) continue;
      textRec.delete(n);
      if (r.applied !== null && n.nodeValue === r.applied && n.nodeValue !== r.orig) n.nodeValue = r.orig;
      if (n.isConnected) rest.push(n);
    }
    if (node.isConnected) handleText(node, true);
    for (var j = 0; j < rest.length; j++) handleText(rest[j], true);
  }

  // ---------------------------------------------------------------- attributes
  function currentAttr(el, name) {
    return name === 'value' ? el.value : el.getAttribute(name);
  }

  function handleAttr(el, name) {
    if (!enabled) return;
    var isVal = name === 'value';
    if (isVal && !(el.tagName === 'INPUT' && BUTTON_INPUT.test(el.type || ''))) return;
    // A named submit button posts its value with the form: translating it would change the request.
    if (isVal && /^submit$/i.test(el.type || '') && el.name) return;
    var v = currentAttr(el, name);
    if (!v || !HAN.test(v)) return;
    var recs = attrRec.get(el);
    var r = recs && recs[name];
    if (r && (r.applied !== null ? v === r.applied : v === r.orig)) return;
    try { if (el.closest(ATTR_SKIP_SELECTOR)) return; } catch (e) { return; }
    if (!recs) { recs = {}; attrRec.set(el, recs); }
    var parts = split(v);
    r = recs[name] = { orig: v, core: parts[1], applied: null, target: (r && r.target) || { el: el, attr: name } };
    var en = cacheGet(parts[1]);
    if (en !== undefined) {
      counters.cacheHits += 1;
      applyAttr(el, name, r, en);
      return;
    }
    need(parts[1], r.target, el, name);
  }

  function applyAttr(el, name, r, en) {
    var parts = split(r.orig);
    var out = parts[0] + en + parts[2];
    r.applied = out;
    if (name === 'value') {
      if (el.hasAttribute('value')) el.setAttribute('value', out);
      el.value = out;
    } else {
      el.setAttribute(name, out);
    }
    counters.applied += 1;
  }

  // ---------------------------------------------------------------- layout fixes
  function scheduleLayout() {
    if (layoutScheduled) return;
    layoutScheduled = true;
    var run = function () { layoutScheduled = false; runLayout(); };
    if (typeof W.requestAnimationFrame === 'function' && D.visibilityState !== 'hidden') {
      W.requestAnimationFrame(run);
    } else {
      setTimeout(run, 16);
    }
  }

  function ensureStyle() {
    if (styleEl && styleEl.isConnected) return;
    styleEl = D.createElement('style');
    styleEl.setAttribute('data-mte-ui', '');
    styleEl.textContent = '[data-mte-wrap]{overflow-wrap:break-word;word-break:break-word;}';
    (D.head || D.documentElement).appendChild(styleEl);
  }

  function styleOf(el) {
    try { return (el.ownerDocument.defaultView || W).getComputedStyle(el); } catch (e) { return null; }
  }

  function isClipped(cs) {
    return cs.textOverflow === 'ellipsis' || (cs.whiteSpace === 'nowrap' && cs.overflow === 'hidden') ||
      (cs.webkitLineClamp && cs.webkitLineClamp !== 'none');
  }

  /** Gives a clipped element the full English as its tooltip, once all of its text is English. */
  function setFullTitle(el) {
    var full = trunc(el.textContent, 300);
    if (!full) return;
    if (HAN.test(full)) {
      // Siblings still waiting for their translation: look again shortly.
      var n = (titleTries.get(el) || 0) + 1;
      titleTries.set(el, n);
      if (n <= 4) setTimeout(function () { if (enabled && el.isConnected) { layoutQueue.push(el); scheduleLayout(); } }, 1500);
      return;
    }
    var recs = attrRec.get(el);
    var ours = recs && recs.title && recs.title.added;
    if (el.hasAttribute('title') && !(ours && el.getAttribute('title') === recs.title.applied)) return; // the page's own title
    if (!recs) { recs = {}; attrRec.set(el, recs); }
    recs.title = { orig: null, core: null, applied: full, added: true, target: null };
    if (el.getAttribute('title') !== full) el.setAttribute('title', full);
  }

  function runLayout() {
    var list = layoutQueue;
    layoutQueue = [];
    if (!enabled) return;
    var seen = new Set();
    for (var i = 0; i < list.length; i++) {
      var el = list[i];
      if (seen.has(el) || !el.isConnected) continue;
      seen.add(el);
      var cs = styleOf(el);
      if (!cs) continue;
      // The clipping style often sits on a wrapper: <div class=ellipsis><span>text</span></div>.
      var clipEl = null;
      var e = el, ecs = cs;
      for (var d = 0; e && e.nodeType === 1 && d < 3; d++) {
        if (d > 0) ecs = styleOf(e);
        if (ecs && isClipped(ecs)) { clipEl = e; break; }
        e = e.parentElement;
      }
      if (clipEl) {
        if (!seen.has(clipEl) || clipEl === el) { seen.add(clipEl); setFullTitle(clipEl); }
      } else if (cs.whiteSpace !== 'nowrap' && cs.whiteSpace !== 'pre' && !el.hasAttribute('data-mte-wrap')) {
        ensureStyle();
        el.setAttribute('data-mte-wrap', '');
        wrapMarked.add(el);
      }
    }
  }

  // ---------------------------------------------------------------- scanning
  function acceptNode(n) {
    if (n.nodeType === 1) {
      if (n.hasAttribute('data-mte-ui')) return NodeFilter.FILTER_REJECT;
      handleElement(n);
      if (TEXT_SKIP[n.tagName] || n.isContentEditable) return NodeFilter.FILTER_REJECT;
      return NodeFilter.FILTER_SKIP;
    }
    return NodeFilter.FILTER_ACCEPT;
  }

  function scan(root) {
    if (!enabled || !root) return;
    var t = root.nodeType;
    if (t === 3) { handleText(root, false); return; }
    if (t === 1) {
      if (root.hasAttribute('data-mte-ui')) return;
      handleElement(root);
      if (TEXT_SKIP[root.tagName] || root.isContentEditable) return;
    } else if (t !== 9 && t !== 11) {
      return;
    }
    var doc = root.nodeType === 9 ? root : root.ownerDocument || D;
    var tw = doc.createTreeWalker(root, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT, { acceptNode: acceptNode });
    var n;
    while ((n = tw.nextNode())) handleText(n, true);
  }

  function handleElement(el) {
    for (var i = 0; i < ATTRS.length; i++) {
      if (el.hasAttribute(ATTRS[i])) handleAttr(el, ATTRS[i]);
    }
    var tag = el.tagName;
    if (tag === 'INPUT') {
      if (BUTTON_INPUT.test(el.type || '')) handleAttr(el, 'value');
    } else if (tag === 'IMG') {
      considerImage(el);
    } else if (tag === 'IFRAME') {
      attachFrame(el);
    } else if (maybeBackground(el)) {
      considerImage(el);
    }
    var sr = el.shadowRoot || closedHosts.get(el);
    if (sr) attachRoot(sr);
  }

  function attachRoot(root) {
    if (!attachedRoots.has(root)) {
      attachedRoots.add(root);
      try { if (observer) observer.observe(root, MO_CFG); } catch (e) { /* ignore */ }
    }
    scan(root);
  }

  function attachFrame(iframe) {
    if (!hookedFrames.has(iframe)) {
      hookedFrames.add(iframe);
      iframe.addEventListener('load', function () { attachFrameDoc(iframe); });
    }
    attachFrameDoc(iframe);
  }

  function frameDoc(iframe) {
    try {
      var w = iframe.contentWindow;
      var d = iframe.contentDocument;
      if (!d || !w) return null;
      if (w.__mte && w.__mte.__installed && w.__mte.__doc === d) return null; // that frame translates itself
      return d;
    } catch (e) {
      return null; // cross-origin
    }
  }

  function attachFrameDoc(iframe) {
    var d = frameDoc(iframe);
    if (!d) return;
    if (!attachedDocs.has(d)) {
      attachedDocs.add(d);
      try { if (observer) observer.observe(d, MO_CFG); } catch (e) { /* ignore */ }
      installGestures(d);
    }
    scan(d);
  }

  function fullScan() {
    if (!enabled) return;
    scan(D);
  }

  // ---------------------------------------------------------------- mutations
  function onMutations(records) {
    if (!enabled) return;
    for (var i = 0; i < records.length; i++) {
      var r = records[i];
      if (r.type === 'childList') {
        var added = r.addedNodes;
        if (!added.length) continue;
        var parent = r.target;
        if (parent.nodeType === 1 && isSkipped(parent)) continue;
        for (var j = 0; j < added.length; j++) {
          var n = added[j];
          if (n.nodeType === 3) handleText(n, true);
          else if (n.nodeType === 1) scan(n);
        }
      } else if (r.type === 'characterData') {
        if (r.target.nodeType === 3) handleText(r.target, false);
      } else if (r.type === 'attributes') {
        onAttr(r.target, r.attributeName);
      }
    }
  }

  function onAttr(el, name) {
    if (name === 'src' || name === 'srcset') {
      if (el.tagName !== 'IMG' && el.tagName !== 'SOURCE') return;
      var img = el.tagName === 'IMG' ? el : null;
      if (!img) return;
      var rec = imgRec.get(img);
      if (rec && img.getAttribute('src') === rec.applied) return;
      if (rec) imgRec.delete(img);
      considerImage(img);
      return;
    }
    if (name === 'style') {
      var bg = el.style && el.style.backgroundImage;
      if (!bg || bg.indexOf('url(') < 0) return;
      var brec = imgRec.get(el);
      if (brec && bg.indexOf(brec.applied) >= 0) return;
      if (brec) imgRec.delete(el);
      considerImage(el);
      return;
    }
    handleAttr(el, name);
  }

  // ---------------------------------------------------------------- images
  var io = null;
  var imgState = new Map();   // url -> {st: 'q'|'p'|'ok'|'no', res, els}
  var imgQueue = [];
  var imgInflight = 0;
  var imgReqs = new Map();    // reqId -> {url, timer}
  var RX_BG_CLASS = /banner|swiper|slide|poster|cover|bg|background|pic|img|photo|activity|promo|carousel/;

  function maybeBackground(el) {
    var st = el.getAttribute('style');
    if (st && st.indexOf('url(') >= 0) return true;
    var c = el.getAttribute('class');
    return !!c && RX_BG_CLASS.test(c.toLowerCase());
  }

  function eligibleUrl(url) {
    if (!/^https?:\/\//i.test(url)) return false;
    var host = '';
    var path = '';
    try { var u = new URL(url); host = u.hostname; path = u.pathname; } catch (e) { return false; }
    if (host === FAKE_HOST) return false;
    if (/\.svgz?$/i.test(path)) return false;
    return true;
  }

  function absolutize(url, el) {
    try { return new URL(url, (el && el.ownerDocument || D).baseURI).href; } catch (e) { return url; }
  }

  function considerImage(el) {
    if (!enabled || !imagesOn) return;
    if (typeof W.IntersectionObserver !== 'function') { checkImage(el); return; }
    if (!io) {
      io = new W.IntersectionObserver(function (entries) {
        for (var i = 0; i < entries.length; i++) {
          if (entries[i].isIntersecting) {
            io.unobserve(entries[i].target);
            checkImage(entries[i].target);
          }
        }
      }, { rootMargin: '300px 0px' });
    }
    try { io.observe(el); } catch (e) { /* element from a foreign document */ }
  }

  function checkImage(el) {
    if (!enabled || !imagesOn || !el.isConnected) return;
    var rec = imgRec.get(el);
    if (rec && rec.applied) return;
    var url;
    if (el.tagName === 'IMG') {
      if (!el.complete || !el.naturalWidth) {
        if (!loadHooked.has(el)) {
          loadHooked.add(el);
          el.addEventListener('load', function () { loadHooked.delete(el); checkImage(el); }, { once: true });
        }
        return;
      }
      if (el.naturalWidth * el.naturalHeight < MIN_IMG_AREA) return;
      url = el.currentSrc || el.src;
    } else {
      var r = el.getBoundingClientRect();
      if (r.width * r.height < MIN_IMG_AREA) return;
      var bg = '';
      try { bg = (el.ownerDocument.defaultView || W).getComputedStyle(el).backgroundImage || ''; } catch (e) { return; }
      var m = /url\(\s*(['"]?)(.*?)\1\s*\)/.exec(bg);
      if (!m) return;
      url = m[2];
    }
    url = absolutize(url, el);
    if (!eligibleUrl(url)) return;
    requestImage(url, el);
  }

  function requestImage(url, el) {
    var s = imgState.get(url);
    if (!s) {
      s = { st: 'q', res: null, els: new Set() };
      imgState.set(url, s);
      imgQueue.push(url);
    }
    if (s.st === 'ok') { applyImage(el, s.res, url); return; }
    if (s.st === 'no') return;
    s.els.add(el);
    pumpImages();
  }

  function pumpImages() {
    while (imgInflight < MAX_IMG_INFLIGHT && imgQueue.length) {
      if (!getBridge()) { setTimeout(pumpImages, 1000); return; }
      var url = imgQueue.shift();
      var s = imgState.get(url);
      if (!s || s.st !== 'q') continue;
      var reqId = nextId('i');
      s.st = 'p';
      imgInflight += 1;
      imgReqs.set(reqId, { url: url, timer: setTimeout(timeoutImage.bind(null, reqId, url), IMG_TIMEOUT) });
      counters.imagesRequested += 1;
      if (!post({ type: 'image', reqId: reqId, url: url })) {
        clearTimeout(imgReqs.get(reqId).timer);
        imgReqs.delete(reqId);
        imgInflight -= 1;
        counters.imagesRequested -= 1;
        s.st = 'q';
        imgQueue.unshift(url);
        setTimeout(pumpImages, 1000);
        return;
      }
    }
  }

  function timeoutImage(reqId, url) { receiveImage(reqId, url, null); }

  function receiveImage(reqId, url, result) {
    var r = imgReqs.get(reqId);
    if (r) {
      imgReqs.delete(reqId);
      clearTimeout(r.timer);
      imgInflight = Math.max(0, imgInflight - 1);
      url = r.url;
    }
    var s = imgState.get(url);
    if (s && s.st === 'p') {
      if (typeof result === 'string' && result) {
        // Make sure the translated file really loads here (a service worker could intercept it)
        // before swapping it in; otherwise keep the original picture.
        verifyImage(result, function (ok) {
          if (s.st !== 'p') return;
          if (ok) {
            s.st = 'ok';
            s.res = result;
            if (enabled && imagesOn) s.els.forEach(function (el) { applyImage(el, result, url); });
          } else {
            s.st = 'no';
          }
          s.els.clear();
        });
      } else {
        s.st = 'no';
        s.els.clear();
      }
    }
    pumpImages();
  }

  function verifyImage(src, cb) {
    var done = false;
    var finish = function (ok) { if (!done) { done = true; cb(ok); } };
    try {
      var im = new W.Image();
      im.onload = function () { finish(im.naturalWidth > 0); };
      im.onerror = function () { finish(false); };
      setTimeout(function () { finish(false); }, 15000);
      im.src = src;
    } catch (e) {
      finish(false);
    }
  }

  function applyImage(el, result, url) {
    if (!el.isConnected) return;
    var rec = imgRec.get(el);
    if (rec && rec.applied === result) return;
    if (el.tagName === 'IMG') {
      var cur = el.currentSrc || el.src;
      if (cur && absolutize(cur, el) !== url) return; // image changed meanwhile
      rec = { kind: 'img', src: el.getAttribute('src'), srcset: el.getAttribute('srcset'), sources: [], applied: result };
      var pic = el.parentElement;
      if (pic && pic.tagName === 'PICTURE') {
        var kids = pic.children;
        for (var i = 0; i < kids.length; i++) {
          if (kids[i].tagName === 'SOURCE' && kids[i].hasAttribute('srcset')) {
            rec.sources.push([kids[i], kids[i].getAttribute('srcset')]);
          }
        }
      }
      imgRec.set(el, rec);
      for (var j = 0; j < rec.sources.length; j++) rec.sources[j][0].setAttribute('srcset', result);
      if (rec.srcset !== null) el.setAttribute('srcset', result);
      el.setAttribute('src', result);
      el.addEventListener('error', function () {
        if (el.getAttribute('src') === result) restoreImage(el);
      }, { once: true });
    } else {
      rec = { kind: 'bg', bg: el.style.backgroundImage, styleAttr: el.getAttribute('style'), ourStyle: null, applied: result };
      imgRec.set(el, rec);
      el.style.backgroundImage = 'url("' + result + '")';
      rec.ourStyle = el.getAttribute('style');
    }
    counters.imagesReplaced += 1;
  }

  function restoreImage(el) {
    var rec = imgRec.get(el);
    if (!rec) return;
    imgRec.delete(el);
    if (rec.kind === 'img') {
      if (el.getAttribute('src') !== rec.applied) return;
      for (var i = 0; i < rec.sources.length; i++) {
        if (rec.sources[i][0].getAttribute('srcset') === rec.applied) rec.sources[i][0].setAttribute('srcset', rec.sources[i][1]);
      }
      if (rec.srcset !== null && el.getAttribute('srcset') === rec.applied) el.setAttribute('srcset', rec.srcset);
      if (rec.src === null) el.removeAttribute('src'); else el.setAttribute('src', rec.src);
    } else {
      var st = el.getAttribute('style');
      if (st === rec.ourStyle) {
        if (rec.styleAttr === null) el.removeAttribute('style'); else el.setAttribute('style', rec.styleAttr);
      } else if ((el.style.backgroundImage || '').indexOf(rec.applied) >= 0) {
        el.style.backgroundImage = rec.bg;
      }
    }
  }

  // ---------------------------------------------------------------- restore / toggles
  function forEachNode(cb) {
    var visit = function (root) {
      var doc = root.nodeType === 9 ? root : root.ownerDocument || D;
      var tw = doc.createTreeWalker(root, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT, null);
      var n;
      while ((n = tw.nextNode())) {
        cb(n);
        if (n.nodeType === 1) {
          var sr = n.shadowRoot || closedHosts.get(n);
          if (sr) visit(sr);
          if (n.tagName === 'IFRAME') { var fd = frameDoc(n); if (fd) visit(fd); }
        }
      }
    };
    visit(D);
  }

  function restoreNode(n, includeImages) {
    if (n.nodeType === 3) {
      var rec = textRec.get(n);
      if (rec) {
        textRec.delete(n);
        if (rec.applied !== null && n.nodeValue === rec.applied) n.nodeValue = rec.orig;
      }
      return;
    }
    var recs = attrRec.get(n);
    if (recs) {
      attrRec.delete(n);
      for (var name in recs) {
        if (!Object.prototype.hasOwnProperty.call(recs, name)) continue;
        var r = recs[name];
        if (r.applied === null || currentAttr(n, name) !== r.applied) continue;
        if (r.added) n.removeAttribute(name);
        else if (name === 'value') { if (n.hasAttribute('value')) n.setAttribute('value', r.orig); n.value = r.orig; }
        else n.setAttribute(name, r.orig);
      }
    }
    if (wrapMarked.has(n)) {
      wrapMarked.delete(n);
      n.removeAttribute('data-mte-wrap');
    }
    if (includeImages) restoreImage(n);
  }

  function setEnabled(on) {
    on = !!on;
    if (on === enabled) return;
    enabled = on;
    if (!on) {
      if (flushTimer) { clearTimeout(flushTimer); flushTimer = 0; }
      queued.clear();
      waiters.clear();
      layoutQueue = [];
      hideTip();
      forEachNode(function (n) { restoreNode(n, true); });
      if (styleEl && styleEl.parentNode) styleEl.parentNode.removeChild(styleEl);
      styleEl = null;
    } else {
      fullScan();
    }
  }

  function setImages(on) {
    on = !!on;
    if (on === imagesOn) return;
    imagesOn = on;
    if (!on) {
      forEachNode(function (n) { if (n.nodeType === 1) restoreImage(n); });
    } else if (enabled) {
      imgState.forEach(function (s) { if (s.st === 'no') s.st = 'q'; });
      imgState.forEach(function (s, url) { if (s.st === 'q' && imgQueue.indexOf(url) < 0) imgQueue.push(url); });
      forEachNode(function (n) {
        if (n.nodeType === 1 && (n.tagName === 'IMG' || maybeBackground(n))) considerImage(n);
      });
    }
  }

  function setLongPress(on) {
    longPressOn = !!on;
    if (!longPressOn) hideTip();
  }

  // ---------------------------------------------------------------- long press: show original
  var tip = null;
  var tipTimer = 0;
  var lpTimer = 0;
  var lpStart = null;
  var lpShownAt = 0;
  var suppressClickUntil = 0;

  function hideTip() {
    if (tipTimer) { clearTimeout(tipTimer); tipTimer = 0; }
    if (tip && tip.parentNode) tip.parentNode.removeChild(tip);
    tip = null;
  }

  function collectOriginals(el) {
    var parts = [];
    var recs = attrRec.get(el);
    if (recs) {
      ['placeholder', 'alt', 'aria-label', 'value'].forEach(function (a) {
        var r = recs[a];
        if (r && r.applied !== null && !r.added) parts.push(r.core);
      });
    }
    var tw = (el.ownerDocument || D).createTreeWalker(el, NodeFilter.SHOW_TEXT, null);
    var n, count = 0;
    var units = new Set();
    while ((n = tw.nextNode()) && count < 60) {
      count += 1;
      var rec = textRec.get(n);
      if (!rec || rec.applied === null || n.nodeValue !== rec.applied) continue;
      if (rec.unit) {
        if (units.has(rec.unit)) continue;
        units.add(rec.unit);
      }
      parts.push(rec.core);
    }
    return trunc(parts.join(' '), 300);
  }

  function showOriginalFor(target, x, y) {
    if (!enabled || !longPressOn || !target) return false;
    var el = target.nodeType === 3 ? target.parentElement : target;
    for (var d = 0; el && el.nodeType === 1 && d < 4; d++, el = el.parentElement) {
      if (el.hasAttribute('data-mte-ui')) return false;
      var orig = collectOriginals(el);
      if (orig) {
        showTip(orig, x, y);
        lpShownAt = Date.now();
        return true;
      }
    }
    return false;
  }

  function showTip(text, x, y) {
    hideTip();
    tip = D.createElement('div');
    tip.setAttribute('data-mte-ui', '');
    tip.setAttribute('role', 'tooltip');
    tip.style.cssText = [
      'position:fixed', 'z-index:2147483647', 'max-width:80vw', 'padding:8px 10px', 'border-radius:8px',
      'background:rgba(34,34,34,0.94)', 'color:#fff', 'font:14px/1.4 -apple-system,Roboto,sans-serif',
      'box-shadow:0 4px 16px rgba(0,0,0,0.3)', 'pointer-events:none', 'white-space:normal',
      'word-break:break-word', 'left:0', 'top:0', 'visibility:hidden'
    ].join(';');
    var label = D.createElement('div');
    label.style.cssText = 'font-size:11px;opacity:0.7;margin-bottom:2px;';
    label.textContent = 'Original';
    var body = D.createElement('div');
    body.textContent = text;
    tip.appendChild(label);
    tip.appendChild(body);
    (D.body || D.documentElement).appendChild(tip);
    var vw = W.innerWidth || 360;
    var vh = W.innerHeight || 640;
    var r = tip.getBoundingClientRect();
    var left = Math.max(8, Math.min(x - r.width / 2, vw - r.width - 8));
    var top = y - r.height - 16;
    if (top < 8) top = Math.min(y + 24, vh - r.height - 8);
    tip.style.left = left + 'px';
    tip.style.top = Math.max(8, top) + 'px';
    tip.style.visibility = 'visible';
    tipTimer = setTimeout(hideTip, 4000);
  }

  function installGestures(doc) {
    var opts = { capture: true, passive: true };
    doc.addEventListener('touchstart', function (e) {
      hideTip();
      if (lpTimer) { clearTimeout(lpTimer); lpTimer = 0; }
      if (!enabled || !longPressOn || !e.touches || e.touches.length !== 1) return;
      var t = e.touches[0];
      lpStart = { x: t.clientX, y: t.clientY, target: e.target };
      lpTimer = setTimeout(function () {
        lpTimer = 0;
        if (lpStart && showOriginalFor(lpStart.target, lpStart.x, lpStart.y)) suppressClickUntil = Date.now() + 1500;
      }, LONG_PRESS_MS);
    }, opts);
    doc.addEventListener('touchmove', function (e) {
      if (!lpTimer || !lpStart || !e.touches || !e.touches.length) return;
      var t = e.touches[0];
      if (Math.abs(t.clientX - lpStart.x) > 10 || Math.abs(t.clientY - lpStart.y) > 10) {
        clearTimeout(lpTimer);
        lpTimer = 0;
      }
    }, opts);
    var end = function () { if (lpTimer) { clearTimeout(lpTimer); lpTimer = 0; } };
    doc.addEventListener('touchend', end, opts);
    doc.addEventListener('touchcancel', end, opts);
    doc.addEventListener('click', function (e) {
      if (Date.now() < suppressClickUntil) {
        suppressClickUntil = 0;
        e.preventDefault();
        e.stopPropagation();
      }
    }, true);
    doc.addEventListener('contextmenu', function (e) {
      if (!enabled || !longPressOn) return;
      if (Date.now() - lpShownAt < 1000) { e.preventDefault(); return; }
      if (showOriginalFor(e.target, e.clientX, e.clientY)) e.preventDefault();
    }, true);
    doc.addEventListener('scroll', function () { if (tip) hideTip(); }, { capture: true, passive: true });
  }

  // ---------------------------------------------------------------- native messages
  function onNativeMessage(data) {
    var m = data;
    if (typeof m === 'string') {
      try { m = JSON.parse(m); } catch (e) { return; }
    }
    if (!m || typeof m !== 'object') return;
    if (m.type === 'translated') receive(m.reqId, m.map, m.done);
    else if (m.type === 'image') receiveImage(m.reqId, m.url, m.result);
    else if (m.type === 'config') {
      if ('images' in m) setImages(m.images);
      if ('longPress' in m) setLongPress(m.longPress);
      if ('enabled' in m) setEnabled(m.enabled);
    }
  }

  function hookBridge() {
    var b = getBridge();
    if (!b) return;
    try {
      if (typeof b.addEventListener === 'function') {
        b.addEventListener('message', function (ev) { onNativeMessage(ev && ev.data); });
      }
    } catch (e) { /* plain JavaScript interface: replies arrive through window.__mte */ }
  }

  // ---------------------------------------------------------------- install
  var api = {
    __installed: true,
    __doc: D,
    version: 1,
    receive: receive,
    receiveImage: receiveImage,
    setEnabled: setEnabled,
    setImages: setImages,
    setLongPress: setLongPress,
    rescan: fullScan,
    isEnabled: function () { return enabled; },
    stats: function () {
      var out = {};
      for (var k in counters) out[k] = counters[k];
      out.cached = cache.size;
      out.queued = queued.size;
      out.inflight = inflight.size;
      out.imagesInflight = imgInflight;
      out.enabled = enabled;
      out.images = imagesOn;
      return out;
    }
  };
  try {
    Object.defineProperty(W, '__mte', { value: api, configurable: true, enumerable: false, writable: false });
  } catch (e) {
    W.__mte = api;
  }

  hookBridge();

  observer = new MutationObserver(onMutations);
  observer.observe(D, MO_CFG);

  // Catch shadow roots created after we started.
  try {
    var origAttach = Element.prototype.attachShadow;
    if (typeof origAttach === 'function') {
      Element.prototype.attachShadow = function (init) {
        var root = origAttach.apply(this, arguments);
        // Closed roots are only reachable through this hook: remember them per host so scans and
        // restore can walk into them too.
        if (init && init.mode === 'closed') closedHosts.set(this, root);
        Promise.resolve().then(function () {
          if (enabled) {
            attachRoot(root);
          } else if (!attachedRoots.has(root)) {
            attachedRoots.add(root);
            observer.observe(root, MO_CFG);
          }
        });
        return root;
      };
    }
  } catch (e) { /* ignore */ }

  installGestures(D);
  fullScan();
  if (D.readyState === 'loading') D.addEventListener('DOMContentLoaded', fullScan);
  W.addEventListener('load', fullScan);
  W.addEventListener('pageshow', function (e) { if (e.persisted) fullScan(); });
})();
