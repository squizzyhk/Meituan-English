#!/usr/bin/env node
/*
 * Headless Chromium tests for app/src/main/assets/inject/translator.js.
 *
 * Loads Meituan-like fixture pages, injects a mock MTEBridge (dictionary + "[EN]" echo for unknown
 * strings) and the translator, then checks translation coverage, attributes, dynamic content, cache
 * re-application after re-renders, exact restore, loop safety, whitespace and image requests.
 * Every fixture runs twice: once with message-event replies (WebMessageListener style) and once with
 * window.__mte.receive replies (evaluateJavascript style).
 *
 * Usage: npm test   (or: node run.js). If 'playwright' is not resolvable, run with NODE_PATH=$(npm root -g).
 */
'use strict';
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

let playwright;
try {
  playwright = require('playwright');
} catch (e) {
  const { execSync } = require('child_process');
  const globalRoot = execSync('npm root -g').toString().trim();
  playwright = require(path.join(globalRoot, 'playwright'));
}

const ROOT = path.resolve(__dirname, '..', '..');
const TRANSLATOR = fs.readFileSync(path.join(ROOT, 'app/src/main/assets/inject/translator.js'), 'utf8');
const FIXTURES = path.join(__dirname, 'fixtures');
const ORIGIN = 'https://fixture.test';
// True when no Chinese is left once mock "[EN]<original>" echoes are discounted.
const english = (s) => !/[\u3400-\u9fff]/.test(String(s).replace(/\[EN\][^\[]*/g, ''));
const TRANSLATED_IMG = 'https://mte.local/img/translated.png';

// ------------------------------------------------------------------ tiny PNG encoder
function png(width, height, rgb) {
  const raw = Buffer.alloc((width * 3 + 1) * height);
  for (let y = 0; y < height; y++) {
    const row = y * (width * 3 + 1);
    raw[row] = 0;
    for (let x = 0; x < width; x++) {
      raw[row + 1 + x * 3] = rgb[0];
      raw[row + 2 + x * 3] = rgb[1];
      raw[row + 3 + x * 3] = rgb[2];
    }
  }
  const chunk = (type, data) => {
    const len = Buffer.alloc(4);
    len.writeUInt32BE(data.length);
    const td = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(zlib.crc32(td) >>> 0);
    return Buffer.concat([len, td, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; ihdr[9] = 2; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}
const IMAGES = {
  '/img/banner.png': png(750, 300, [255, 195, 0]),
  '/img/bg-banner.png': png(400, 150, [200, 60, 60]),
  '/img/icon.png': png(20, 20, [0, 120, 255]),
};
const TRANSLATED_PNG = png(750, 300, [30, 160, 90]);

// ------------------------------------------------------------------ mock native side
const DICT = {
  '美团外卖 - 附近商家': 'Meituan Delivery - Nearby stores',
  '老北京炸酱面馆（望京店）': 'Old Beijing Zhajiang Noodle House (Wangjing)',
  '推荐': 'Recommended', '美食': 'Food', '甜点饮品': 'Desserts & Drinks', '超市便利': 'Groceries',
  '搜索商家、商品名称': 'Search stores or dishes', '搜索': 'Search',
  '送至：': 'Deliver to:', '综合排序': 'Sort', '销量最高': 'Best selling', '距离最近': 'Nearest', '筛选': 'Filter',
  '月售1000+': '1,000+ sold/month', '月售500+': '500+ sold/month', '起送¥20': '¥20 min. order',
  '起送¥15': '¥15 min. order', '配送费¥3': '¥3 delivery', '配送费¥2': '¥2 delivery', '人均¥35': '¥35 per person',
  '满30减5': '¥5 off orders over ¥30', '新客立减': 'New customer discount', '免配送费': 'Free delivery',
  '准时宝': 'On-time guarantee', '4.8分': '4.8', '4.6分': '4.6', '首页': 'Home', '订单': 'Orders', '我的': 'Me',
  '宫保鸡丁': 'Kung Pao Chicken', '麻婆豆腐': 'Mapo Tofu', '米饭': 'Steamed Rice', '鱼香肉丝': 'Yu Xiang Shredded Pork',
  '微辣': 'Mild spicy', '不辣': 'Not spicy', '中辣': 'Medium spicy', '特辣': 'Extra spicy', '辣度': 'Spice level',
  '去结算': 'Checkout', '加入购物车': 'Add to cart', '点菜': 'Menu', '评价': 'Reviews', '商家': 'Store',
  '热销': 'Best sellers', '招牌菜': 'Signature dishes', '主食': 'Staples', '饮品': 'Drinks',
  '提交订单': 'Place order', '收货地址': 'Delivery address', '联系人': 'Contact name', '手机号': 'Phone number',
  '请填写收货人姓名': "Recipient's name", '请填写收货手机号码': "Recipient's phone number",
  '详细地址': 'Address details', '送达时间': 'Delivery time', '尽快送达': 'As soon as possible', '备注': 'Notes',
  '口味、偏好等要求': 'Taste preferences or requests', '请输入正确的手机号': 'Please enter a valid phone number',
  '确认订单': 'Confirm order', '微信支付': 'WeChat Pay', '优惠': 'Discount', '商品金额': 'Subtotal',
  '回归测试': 'Regression test', '首页订单': 'WRONGLY MERGED', '老北京炸酱面馆望京店': 'Old Beijing Zhajiang Noodles (Wangjing)',
  '封闭影子文本': 'Closed shadow text', '正在加载': 'Loading',
};

function mockBridgeScript(mode) {
  return `(() => {
    const DICT = ${JSON.stringify(DICT)};
    const MODE = ${JSON.stringify(mode)};
    const listeners = [];
    const log = [];
    const isTop = window.top === window;
    window.__bridgeLog = log;
    function translate(t) { return Object.prototype.hasOwnProperty.call(DICT, t) ? DICT[t] : '[EN]' + t; }
    function reply(msg, evalCall) {
      setTimeout(() => {
        if (MODE === 'proxy') listeners.forEach((fn) => fn({ data: JSON.stringify(msg) }));
        else evalCall(window.top.__mte);
      }, 15);
    }
    const bridge = {
      postMessage(s) {
        const m = JSON.parse(s);
        (window.top.__allBridgeLog = window.top.__allBridgeLog || []).push(m);
        log.push(m);
        if (m.type === 'translate') {
          const entries = m.items.map((it) => [it.t, translate(it.t)]);
          // Reply in two chunks to exercise partial (done=false) delivery.
          const half = Math.ceil(entries.length / 2);
          const first = Object.fromEntries(entries.slice(0, half));
          const second = Object.fromEntries(entries.slice(half));
          reply({ type: 'translated', reqId: m.reqId, map: first, done: false }, (api) => api.receive(m.reqId, first, false));
          reply({ type: 'translated', reqId: m.reqId, map: second, done: true }, (api) => api.receive(m.reqId, second, true));
        } else if (m.type === 'image') {
          const result = /banner/.test(m.url) ? '${TRANSLATED_IMG}' : null;
          reply({ type: 'image', reqId: m.reqId, url: m.url, result }, (api) => api.receiveImage(m.reqId, m.url, result));
        }
      },
    };
    if (MODE === 'proxy') bridge.addEventListener = (type, fn) => { if (type === 'message') listeners.push(fn); };
    window.MTEBridge = bridge;
  })();`;
}

// ------------------------------------------------------------------ in-page helpers
const PAGE_HELPERS = `(() => {
  const HAN = /[\\u3400-\\u9fff\\uf900-\\ufaff]/;
  // The mock bridge echoes unknown strings as "[EN]<original>": those count as translated.
  const isChinese = (v) => !!v && HAN.test(v) && !v.trim().startsWith('[EN]');
  // [data-test-keep]: fixture elements that must deliberately stay Chinese.
  const SKIP = 'script,style,noscript,textarea,code,[contenteditable="true"],[data-mte-ui],[data-test-keep]';
  function texts(root, out) {
    const tw = (root.ownerDocument || root).createTreeWalker(root, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT);
    let n;
    while ((n = tw.nextNode())) {
      if (n.nodeType === 1) {
        if (n.shadowRoot) texts(n.shadowRoot, out);
        if (n.tagName === 'IFRAME') { try { if (n.contentDocument) texts(n.contentDocument, out); } catch (e) {} }
        continue;
      }
      const p = n.parentElement;
      if (p && p.closest(SKIP)) continue;
      if (isChinese(n.nodeValue)) out.push(n.nodeValue.trim());
    }
    return out;
  }
  window.__untranslated = () => texts(document, []);
  window.__untranslatedAttrs = () => {
    const out = [];
    document.querySelectorAll('[placeholder],[title],[aria-label],[alt],input[type=submit],input[type=button]').forEach((el) => {
      if (el.closest('[data-test-keep]')) return;
      ['placeholder', 'title', 'aria-label', 'alt'].forEach((a) => {
        const v = el.getAttribute(a);
        if (isChinese(v)) out.push(a + '=' + v);
      });
      if (el.tagName === 'INPUT' && /submit|button/.test(el.type) && isChinese(el.value)) out.push('value=' + el.value);
    });
    return out;
  };
  window.__snapshot = () => {
    const shadow = [];
    document.querySelectorAll('*').forEach((el) => { if (el.shadowRoot) shadow.push(el.shadowRoot.innerHTML); });
    const frames = [];
    document.querySelectorAll('iframe').forEach((f) => { try { frames.push(f.contentDocument.body.innerHTML); } catch (e) { frames.push('x'); } });
    const values = [];
    document.querySelectorAll('input,textarea').forEach((el) => values.push(el.value));
    return { body: document.body.innerHTML, head: document.head.innerHTML, title: document.title, shadow, frames, values };
  };
})();`;

// ------------------------------------------------------------------ runner
let failures = 0;
let passes = 0;
function check(name, cond, detail) {
  if (cond) {
    passes += 1;
    console.log('  ok   ' + name);
  } else {
    failures += 1;
    console.log('  FAIL ' + name + (detail !== undefined ? '\n         ' + JSON.stringify(detail).slice(0, 600) : ''));
  }
}

async function routeAll(context) {
  await context.route(ORIGIN + '/**', async (route) => {
    const url = new URL(route.request().url());
    if (IMAGES[url.pathname]) {
      return route.fulfill({ status: 200, contentType: 'image/png', body: IMAGES[url.pathname] });
    }
    const file = path.join(FIXTURES, path.basename(url.pathname));
    if (fs.existsSync(file)) {
      return route.fulfill({ status: 200, contentType: 'text/html; charset=utf-8', body: fs.readFileSync(file) });
    }
    return route.fulfill({ status: 404, body: 'not found' });
  });
  // Stand-in for the native shouldInterceptRequest image server.
  await context.route('https://mte.local/**', (route) =>
    route.fulfill({ status: 200, contentType: 'image/png', body: TRANSLATED_PNG, headers: { 'access-control-allow-origin': '*' } }));
}

async function newPage(browser, { mode, translator }) {
  const context = await browser.newContext({ viewport: { width: 420, height: 2400 }, hasTouch: true, locale: 'zh-CN' });
  await routeAll(context);
  if (translator) {
    await context.addInitScript({ content: mockBridgeScript(mode) });
    await context.addInitScript({ content: 'window.__mteConfig={enabled:true,images:true,longPress:true};\n' + TRANSLATOR });
  }
  await context.addInitScript({ content: PAGE_HELPERS });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  return { context, page, errors };
}

async function settle(page, ms = 1500) {
  await page.waitForTimeout(ms);
  await page.waitForFunction(() => {
    const s = window.__mte && window.__mte.stats();
    return !s || (s.queued === 0 && s.inflight === 0 && s.imagesInflight === 0);
  }, null, { timeout: 10000 });
  await page.waitForTimeout(150);
}

function translateMessages(log) { return log.filter((m) => m.type === 'translate'); }
function sentTexts(log) { return translateMessages(log).flatMap((m) => m.items.map((i) => i.t)); }

async function baseline(browser, fixture, prepare) {
  const { context, page } = await newPage(browser, { translator: false });
  await page.goto(ORIGIN + '/' + fixture);
  if (prepare) await prepare(page);
  await page.waitForTimeout(1500);
  const snap = await page.evaluate(() => window.__snapshot());
  await context.close();
  return snap;
}

async function commonChecks(browser, fixture, mode, extra, prepare) {
  console.log(`\n[${fixture} | ${mode} replies]`);
  const base = await baseline(browser, fixture, prepare);
  const { context, page, errors } = await newPage(browser, { mode, translator: true });
  await page.goto(ORIGIN + '/' + fixture);
  if (prepare) await prepare(page);
  await settle(page);

  const untranslated = await page.evaluate(() => window.__untranslated());
  check('all visible Chinese text translated', untranslated.length === 0, untranslated);
  const attrs = await page.evaluate(() => window.__untranslatedAttrs());
  check('placeholder/title/aria-label/alt/button values translated', attrs.length === 0, attrs);
  const title = await page.title();
  check('document title translated', english(title), title);

  const log = await page.evaluate(() => window.__allBridgeLog || []);
  const texts = sentTexts(log);
  const dupes = texts.filter((t, i) => texts.indexOf(t) !== i);
  check('each string requested once (dedupe + cache)', dupes.length === 0, dupes);
  check('batches respect 80-item limit', translateMessages(log).every((m) => m.items.length <= 80));
  check('items carry a kind', translateMessages(log).every((m) => m.items.every((i) => typeof i.k === 'string')));

  const before = log.length;
  await page.waitForTimeout(1200);
  const after = (await page.evaluate(() => window.__allBridgeLog || [])).length;
  check('no mutation feedback loop (bridge quiet after settling)', after === before && before < 40, { before, after });

  if (extra) await extra(page, log);

  // Exact restore.
  await page.evaluate(() => window.__mte.setEnabled(false));
  await page.waitForTimeout(100);
  const restored = await page.evaluate(() => window.__snapshot());
  check('setEnabled(false) restores body exactly', restored.body === base.body, diff(base.body, restored.body));
  check('setEnabled(false) restores head/title exactly', restored.head === base.head && restored.title === base.title,
    { title: [base.title, restored.title], head: diff(base.head, restored.head) });
  check('setEnabled(false) restores shadow roots and iframes', JSON.stringify(restored.shadow) === JSON.stringify(base.shadow) &&
    JSON.stringify(restored.frames) === JSON.stringify(base.frames), { shadow: [base.shadow, restored.shadow], frames: [base.frames, restored.frames] });
  check('form values untouched', JSON.stringify(restored.values) === JSON.stringify(base.values), [base.values, restored.values]);

  // Re-enable: everything comes back from the JS cache without new requests.
  const countBefore = translateMessages(await page.evaluate(() => window.__allBridgeLog || [])).length;
  await page.evaluate(() => window.__mte.setEnabled(true));
  await page.waitForTimeout(300);
  const again = await page.evaluate(() => window.__untranslated());
  const countAfter = translateMessages(await page.evaluate(() => window.__allBridgeLog || [])).length;
  check('setEnabled(true) re-translates from cache', again.length === 0 && countAfter === countBefore, { again, countBefore, countAfter });

  check('no page errors', errors.length === 0, errors);
  await context.close();
}

function diff(a, b) {
  if (a === b) return null;
  let i = 0;
  while (i < a.length && a[i] === b[i]) i++;
  return { at: i, expected: a.slice(Math.max(0, i - 60), i + 120), actual: b.slice(Math.max(0, i - 60), i + 120) };
}

async function storeListChecks(page, log) {
  const tab = await page.evaluate(() => document.getElementById('tab-rec').textContent);
  check('leading/trailing whitespace preserved', tab === ' Recommended ', tab);
  const shop = await page.evaluate(() => document.getElementById('shop1').textContent);
  check('store name translated', shop === 'Old Beijing Zhajiang Noodle House (Wangjing)', shop);
  const kinds = {};
  translateMessages(log).forEach((m) => m.items.forEach((i) => { kinds[i.t] = i.k; }));
  check('store name classified STORE_NAME', kinds['老北京炸酱面馆（望京店）'] === 'STORE_NAME', kinds['老北京炸酱面馆（望京店）']);
  check('placeholder classified FORM_HINT', kinds['搜索商家、商品名称'] === 'FORM_HINT', kinds['搜索商家、商品名称']);
  check('nav tab classified UI_LABEL', kinds['美食'] === 'UI_LABEL', kinds['美食']);
  check('address classified ADDRESS', kinds['北京市朝阳区望京SOHO'] === 'ADDRESS', kinds['北京市朝阳区望京SOHO']);
  const ctx = translateMessages(log).flatMap((m) => m.items).find((i) => i.t === '月售1000+');
  check('context sent with items (page title)', ctx && ctx.c === '美团外卖 - 附近商家', ctx);
  const ellipsisTitle = await page.evaluate(() => document.getElementById('shop1').getAttribute('title'));
  check('ellipsis element gets full English title', ellipsisTitle === (await page.evaluate(() => document.getElementById('shop1').textContent.trim())), ellipsisTitle);

  const script = await page.evaluate(() => document.querySelector('head script').textContent);
  check('script contents untouched', script.includes('你好，这段脚本里的中文不能被翻译'));
  const code = await page.evaluate(() => document.getElementById('code1').textContent);
  check('<code> untouched', code === '接口返回中文', code);
  const editor = await page.evaluate(() => document.getElementById('editor').textContent);
  check('contenteditable untouched', editor === '用户正在编辑的中文', editor);
  const memo = await page.evaluate(() => [document.getElementById('memo').value, document.getElementById('memo').placeholder]);
  check('textarea value untouched, placeholder translated', memo[0] === '用户输入的内容' && english(memo[1]), memo);
  const shadow = await page.evaluate(() => document.getElementById('coupon').shadowRoot.textContent);
  check('open shadow root translated', english(shadow), shadow);
  const frame = await page.evaluate(() => document.getElementById('frame').contentDocument.body.textContent);
  check('same-origin iframe translated', english(frame), frame);

  const images = log.filter((m) => m.type === 'image').map((m) => m.url);
  check('image request sent for big <img>', images.includes(ORIGIN + '/img/banner.png'), images);
  check('image request sent for big CSS background', images.includes(ORIGIN + '/img/bg-banner.png'), images);
  check('no image request for small icons', !images.some((u) => u.includes('icon.png')), images);
  check('image requests deduped', new Set(images).size === images.length, images);
  const img = await page.evaluate(() => ({ src: document.getElementById('banner').getAttribute('src'), w: document.getElementById('banner').naturalWidth }));
  check('translated image swapped in', img.src === TRANSLATED_IMG && img.w === 750, img);
  const bg = await page.evaluate(() => document.getElementById('bgbanner').style.backgroundImage);
  check('translated background swapped in', bg.includes(TRANSLATED_IMG), bg);

  // Long press shows the original Chinese.
  const box = await page.locator('#shop1').boundingBox();
  await page.dispatchEvent('#shop1', 'contextmenu', { clientX: box.x + 10, clientY: box.y + 5 });
  const tip = await page.evaluate(() => { const t = document.querySelector('[data-mte-ui][role=tooltip]'); return t && t.textContent; });
  check('long press tooltip shows original', !!tip && tip.includes('老北京炸酱面馆（望京店）'), tip);

  // setImages(false) restores images only.
  await page.evaluate(() => window.__mte.setImages(false));
  const imgOff = await page.evaluate(() => document.getElementById('banner').getAttribute('src'));
  check('setImages(false) restores original image', imgOff === '/img/banner.png', imgOff);
  await page.evaluate(() => window.__mte.setImages(true));
  await page.waitForTimeout(400);
  const imgOn = await page.evaluate(() => document.getElementById('banner').getAttribute('src'));
  check('setImages(true) re-applies translated image', imgOn === TRANSLATED_IMG, imgOn);
}

async function menuChecks(page, log) {
  const names = await page.evaluate(() => [...document.querySelectorAll('.food-name')].map((e) => e.textContent));
  check('dynamic dish added after load translated', names.includes('Yu Xiang Shredded Pork'), names);
  check('re-rendered dishes re-applied from cache', JSON.stringify(names) ===
    JSON.stringify(['Kung Pao Chicken', 'Mapo Tofu', 'Steamed Rice', 'Yu Xiang Shredded Pork']), names);
  const renders = await page.evaluate(() => window.__renders);
  check('fixture re-rendered several times', renders >= 4, renders);
  const counts = {};
  sentTexts(log).forEach((t) => { counts[t] = (counts[t] || 0) + 1; });
  check('re-rendered strings not re-requested', counts['宫保鸡丁'] === 1 && counts['加入购物车'] === 1, counts);
  const tip = await page.evaluate(() => document.getElementById('late-tip').textContent);
  check('late setTimeout content translated', english(tip), tip);
  const cart = await page.evaluate(() => document.getElementById('cart-count').textContent);
  check('characterData update translated', cart === '[EN]已选4件', cart);
  const kinds = {};
  translateMessages(log).forEach((m) => m.items.forEach((i) => { kinds[i.t] = i.k; }));
  check('dish classified MENU_ITEM', kinds['宫保鸡丁'] === 'MENU_ITEM', kinds['宫保鸡丁']);
  check('review classified REVIEW', kinds['味道很好，分量足，送餐也很快，下次还会再点！'] === 'REVIEW', kinds);
  check('long notice classified DESCRIPTION', Object.entries(kinds).some(([t, k]) => t.startsWith('公告') && k === 'DESCRIPTION'), kinds);
  check('spec option classified UI_LABEL', kinds['微辣'] === 'UI_LABEL', kinds['微辣']);
  const ctx = translateMessages(log).flatMap((m) => m.items).find((i) => i.t === '麻婆豆腐');
  check('store name used as context on store page', ctx && /川味小厨/.test(ctx.c || ''), ctx);
  const spec = await page.evaluate(() => [...document.querySelectorAll('.spec-opt')].map((b) => b.textContent));
  check('spec selector translated', JSON.stringify(spec) === JSON.stringify(['Not spicy', 'Mild spicy', 'Medium spicy', 'Extra spicy']), spec);
  const checkout = await page.evaluate(() => document.getElementById('checkout').textContent);
  check('checkout button natural English', checkout === 'Checkout', checkout);
}

async function checkoutPrepare(page) {
  await page.waitForTimeout(200);
  await page.fill('#typed', '我的名字叫小明');
  await page.click('#validate');
}

async function checkoutChecks(page, log) {
  const v = await page.evaluate(() => ({
    name: document.getElementById('name').placeholder,
    submit: document.getElementById('submit').value,
    typed: document.getElementById('typed').value,
    addrTitle: document.getElementById('addr').title,
    aria: document.getElementById('ware').getAttribute('aria-label'),
    error: document.querySelector('.error').textContent,
    option: document.querySelector('#time option').textContent,
  }));
  check('input placeholder translated', v.name === "Recipient's name", v.name);
  check('submit button value translated', v.submit === 'Place order', v.submit);
  check('user-typed input value untouched', v.typed === '我的名字叫小明', v.typed);
  check('title attribute translated', v.addrTitle === '[EN]门牌号', v.addrTitle);
  check('aria-label translated', v.aria === '[EN]选择餐具份数', v.aria);
  check('validation message translated', v.error === 'Please enter a valid phone number', v.error);
  check('select option translated', v.option === 'As soon as possible', v.option);
  const title = await page.title();
  check('document.title set by script translated', title === 'Confirm order', title);
  const kinds = {};
  translateMessages(log).forEach((m) => m.items.forEach((i) => { kinds[i.t] = i.k; }));
  check('form placeholder classified FORM_HINT', kinds['请填写收货人姓名'] === 'FORM_HINT', kinds['请填写收货人姓名']);
}

async function regressionChecks(page, log) {
  const sent = sentTexts(log);
  check('split text nodes sent as one phrase', sent.includes('月售1000+') && !sent.includes('月售'), sent);
  check('inline-element phrase sent as one phrase', sent.includes('满30减5') && !sent.includes('满') && !sent.includes('减'), sent);
  const v = await page.evaluate(() => ({
    split: document.getElementById('split').textContent,
    mixed: document.getElementById('mixed').textContent,
    tabs: [...document.querySelectorAll('#tabs span')].map((e) => e.textContent),
    ell: document.getElementById('ell').getAttribute('title'),
    spice: document.getElementById('spice').value,
    option: document.querySelector('#spice option').textContent,
    named: document.getElementById('named').value,
    fd: [...new FormData(document.getElementById('f'), document.getElementById('named'))],
    closed: window.__closedRoot.textContent,
  }));
  check('split phrase reads naturally', v.split === '1,000+ sold/month', v.split);
  check('inline phrase reads naturally', v.mixed === '¥5 off orders over ¥30', v.mixed);
  check('separate labels never merged', JSON.stringify(v.tabs) === JSON.stringify(['Home', 'Orders']) && !sent.includes('首页订单'), v.tabs);
  check('ellipsis wrapper gets full English title', v.ell === 'Old Beijing Zhajiang Noodles (Wangjing)', v.ell);
  check('option text translated, submitted value unchanged', v.option === 'Mild spicy' && v.spice === '微辣', v);
  check('named submit button keeps its value', v.named === '提交订单' && JSON.stringify(v.fd).includes('提交订单'), v.fd);
  check('closed shadow root translated', v.closed === 'Closed shadow text', v.closed);
  const osc = await Promise.race([
    page.evaluate(() => document.getElementById('osc').textContent),
    new Promise((r) => setTimeout(() => r('page hung'), 3000)),
  ]);
  check('page that reverts our text does not hang', osc === '正在加载', osc);
}

async function retryChecks(browser) {
  console.log('\n[untranslatable replies and lost replies]');
  const context = await browser.newContext();
  await routeAll(context);
  // Echo bridge: answers with the source (engine fallback); drops the very first message entirely.
  await context.addInitScript({ content: `(() => {
    let first = true; window.__allBridgeLog = [];
    window.MTEBridge = { postMessage(s) {
      const m = JSON.parse(s); window.__allBridgeLog.push(m);
      if (m.type !== 'translate') return;
      if (first) { first = false; return; }
      const map = {}; m.items.forEach((i) => { map[i.t] = i.t; });
      setTimeout(() => { window.__mte.receive(m.reqId, {}, false); window.__mte.receive(m.reqId, map, true); }, 10);
    } };
  })();` });
  await context.addInitScript({ content: TRANSLATOR });
  const page = await context.newPage();
  await page.goto(ORIGIN + '/regressions.html');
  await page.waitForTimeout(16000);
  const log = await page.evaluate(() => window.__allBridgeLog);
  const msgs = translateMessages(log);
  check('lost reply re-sent after the short acknowledgement timeout', msgs.length >= 2, msgs.length);
  await page.waitForTimeout(20000);
  const counts = {};
  sentTexts(await page.evaluate(() => window.__allBridgeLog)).forEach((t) => { counts[t] = (counts[t] || 0) + 1; });
  check('untranslatable text requested at most twice after a lost reply', Object.values(counts).every((n) => n <= 3), counts);
  await context.close();
}

(async () => {
  const browser = await playwright.chromium.launch();
  try {
    for (const mode of ['proxy', 'eval']) {
      await commonChecks(browser, 'store-list.html', mode, storeListChecks);
      await commonChecks(browser, 'menu.html', mode, menuChecks);
      await commonChecks(browser, 'checkout.html', mode, checkoutChecks, checkoutPrepare);
      await commonChecks(browser, 'regressions.html', mode, regressionChecks);
    }
    await retryChecks(browser);
    // Idempotent install guard.
    console.log('\n[double injection]');
    const { context, page } = await newPage(browser, { mode: 'proxy', translator: true });
    await page.goto(ORIGIN + '/menu.html');
    await page.evaluate(TRANSLATOR);
    await settle(page);
    const texts = sentTexts(await page.evaluate(() => window.__allBridgeLog || []));
    check('second injection is a no-op', new Set(texts).size === texts.length, texts.length);
    await context.close();
  } finally {
    await browser.close();
  }
  console.log(`\n${passes} passed, ${failures} failed`);
  process.exit(failures ? 1 : 0);
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
