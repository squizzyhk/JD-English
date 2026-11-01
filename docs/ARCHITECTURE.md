# JD English — Architecture & Contracts

An Android app (Kotlin, single Activity) that hosts JD's mainland-China mobile
site (`m.jd.com`, `item.m.jd.com`, `so.m.jd.com`, `p.m.jd.com`, `home.m.jd.com`,
`wqs.jd.com`, `pro.m.jd.com`, etc.) in a WebView and rewrites everything into
natural English **in place**: UI chrome, menus, store info, forms (placeholders,
button values, labels), product titles/specs/reviews, and Chinese text baked
into images (OCR + overlay).

Package: `com.jdenglish.app` · minSdk 24 · target/compile 35 · Kotlin · no Compose
(classic Views + Material Components). Build: `./gradlew assembleRelease`.

## Translation pipeline (native, `translate/`)

`TranslationEngine.translate(items: List<TextItem>): List<String?>` — tiered,
first hit wins, per string:

1. **Normalize** — trim, collapse whitespace; strings with no CJK pass through untouched.
2. **Glossary** (`assets/glossary/*.json`) — exact match of curated, natural
   e-commerce English for JD UI terms ("加入购物车" → "Add to Cart",
   "领券" → "Get Coupons", "京东自营" → "JD Direct", "百亿补贴" → "Big Subsidy Deals").
3. **Rules** (`RuleEngine`) — regex patterns with captured numbers, e.g.
   `已售1万+` → `10K+ sold`, `满199减20` → `¥20 off orders over ¥199`,
   `好评率98%` → `98% positive`, `3小时前` → `3 hours ago`,
   `月销2000+` → `2,000+ sold/mo`, `2件8折` → `20% off 2 items`.
   Rules may call back into the glossary for sub-parts.
4. **Cache** (`TranslationCache`, SQLite) — previously translated strings.
5. **Backend** — the user-selected machine translator:
   - `MlKitBackend` (default; free, on-device; model downloads once over Wi-Fi/any network).
     The translate path never waits for a download: while the model is unavailable it
     starts the download in the background and returns nulls; when the model becomes
     ready the app calls `window.__jdt.retry()` so the page re-requests what failed.
   - `ClaudeBackend` (optional; user supplies an Anthropic API key in Settings;
     best quality for product titles/descriptions; batches strings into one request)
   Backend output is post-processed by `PostEditor` (fix common MT errors:
   "self-operated" → "JD Direct", "Jingdong" → "JD", "Beans" context etc.) and cached.

`TextItem(text: String, ctx: String)` where ctx ∈ `ui | title | product | attr | review | image`.
Backend cache keys: ML Kit `(mlkit, text)`; Claude `(claude:<model>, ctx + text)`, so a model
switch refreshes and Claude output for one ctx isn't reused for another.
Glossary and rules only apply to short strings; long product titles go to the backend
(but the glossary is still used as a *pre-substitution* hint for ClaudeBackend).

## Bridge contract (JS ⇄ native)

JS object name: `JDTNative` (added with `addJavascriptInterface`).

JS → native (all strings; `@JavascriptInterface`):

| method | args | behavior |
|---|---|---|
| `getConfig()` | – | **sync**, returns JSON `{"enabled":bool,"images":bool,"engine":"mlkit"|"claude"}` |
| `translateBatch(reqId, json)` | `json = {"items":[{"t":"原文","c":"ui"}]}` | async; later calls `window.__jdt.onBatch(reqId, resultJson)` with `{"items":["English"|null, ...]}` same order/length |
| `translateImage(reqId, url)` | absolute image URL (http/https/data:) | async; later calls `window.__jdt.onImage(reqId, resultJson)` — see below |
| `pageTitle(title)` | translated document title | updates native toolbar |
| `log(msg)` | – | Logcat |

Request ids: the top frame prefixes every id with a random per-document nonce `FRAME_ID`
(`f<base36>_`): own batches `FRAME_ID+seq`, images `FRAME_ID+'i'+seq`, and ids it relays for
sub-frames `'R'+FRAME_ID+seq`. Native always delivers results (the page ignores unknown ids);
on a page change it cancels in-flight work by nonce (documents other than the one that calls
next), never by timing.

Image result JSON:
```
{"w":800,"h":600,"blocks":[{"x":10,"y":20,"w":200,"h":40,"text":"Free Shipping","orig":"包邮","fg":"#FFFFFF","bg":"#E1251B","angle":0}]}
```
Coordinates are in the decoded bitmap's pixel space (`w`,`h`), JS scales to the
rendered `<img>` box (respecting `object-fit`). Empty `blocks` = nothing to do.
`"partial":true` = some text could not be translated because the translator wasn't available
yet (e.g. the offline model is downloading); `blocks` holds what was translated, and JS may
request the image again later (bounded back-off, and on `retry()`).
On error: `{"error":"..."}`.

native → JS (via `evaluateJavascript`):
- `window.__jdt && window.__jdt.setEnabled(true|false)` — false restores all originals (text, attributes, hides image overlays).
- `window.__jdt && window.__jdt.setImages(true|false)`
- `window.__jdt && window.__jdt.rescan()`
- `window.__jdt && window.__jdt.retry()` — the translator changed or became available (engine,
  Claude key/model changed; offline model finished downloading): forget strings that came back
  null and failed/partial images for this page view, and request them again (all frames).

JS timeouts are soft: after 20 s (batch) / 45 s (image) the in-flight slot is freed but the
request keeps waiting for its answer; only after a hard timeout (180 s / 240 s) are the strings
re-queued or the image made requestable again.

## Content script (`assets/js/jdt.js`)

Injected at document start in every frame via `WebViewCompat.addDocumentStartJavaScript`
(fallback: `evaluateJavascript` in `onPageStarted/onPageFinished`). Responsibilities:
- Walk text nodes (skip script/style/noscript/textarea/code, contenteditable), plus
  attributes `placeholder`, `title`, `alt`, `aria-label`, `value` (on button/submit inputs),
  `data-placeholder`; also `document.title`.
- `MutationObserver` for dynamic content (JD is a heavy SPA with infinite scroll);
  debounce + batch (≤ 80 strings / batch), dedupe, in-memory cache, idle-time scheduling.
- Keep originals (WeakMap) so `setEnabled(false)` restores; never re-translate our own output.
- Tight layouts: product cards have fixed heights/line-clamps — English is longer than
  Chinese; apply a small injected stylesheet that relaxes overflow where safe and
  uses `-webkit-line-clamp` friendly wrapping; never break the page layout.
- Images: IntersectionObserver on `<img>` (and CSS background images on large elements)
  ≥ 80×40 CSS px; request `translateImage`; render absolutely-positioned overlay boxes
  (bg/fg colors from native, auto-fit font size) in a wrapper that follows the image.
  Long-press an overlay to peek the original image.
- Search: input in Latin letters is fine for JD search (JD handles English brand names); the
  native side additionally offers English→Chinese query translation (see MainActivity).

## Native app (`MainActivity`, `web/`, `bridge/`)

- Top bar: back, translated page title, EN/中 toggle, overflow (Refresh, Open in browser, Copy link, Settings).
- Bottom navigation (like the JD app): Home `https://m.jd.com/`, Categories
  `https://so.m.jd.com/category/all.html`, Cart `https://p.m.jd.com/cart/cart.action`,
  My JD `https://home.m.jd.com/myJd/newhome.action`.
- Native English search bar on the Home tab: English query → translated to Chinese
  (glossary reverse map + backend en→zh) → `https://so.m.jd.com/ware/search.action?keyword=...`.
- `WebViewClient` blocks app-launch schemes (`openapp.jdmobile://`, `jdmobile://`,
  `weixin://`, `alipays://` etc.), keeps JD http(s) in-app, handles back stack, file chooser,
  pull-to-refresh, cookies persisted (login works), progress bar.
- Mobile Chrome UA (not WebView "wv" token) so JD serves its normal H5 site.

## File ownership (for parallel work)

| owner | files |
|---|---|
| App/UI | `MainActivity.kt`, `SettingsActivity.kt`, `web/*`, `bridge/*`, `JdEnglishApp.kt`, `AndroidManifest.xml`, `res/**` |
| Engine | `translate/TranslationEngine.kt` (impl), `TranslationCache.kt`, `MlKitBackend.kt`, `ClaudeBackend.kt`, `PostEditor.kt`, `Glossary.kt` (loader), `Settings.kt` |
| Lexicon | `assets/glossary/*.json`, `translate/RuleEngine.kt`, `app/src/test/**/RuleEngineTest.kt` |
| OCR | `ocr/*` |
| Content script | `assets/js/jdt.js`, `tools/jstest/**` |
