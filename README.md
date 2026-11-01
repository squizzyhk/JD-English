# JD English

An unofficial Android app for shopping on **JD.com (mainland China)** in English. It loads JD's
mobile site and rewrites the whole thing into natural English as you browse: menus, buttons,
forms, store info, product titles, specs, reviews, and the Chinese text baked into images.

Not affiliated with JD.com. You sign in with your normal JD account; orders, cart and payments
are JD's own.

## Install

Grab an APK from the **Actions** tab (artifact `jd-english-apks`) or build it yourself:

- `app-arm64-v8a-release.apk` – almost every phone from the last ~8 years
- `app-armeabi-v7a-release.apk` – older 32-bit phones
- `app-x86_64-release.apk` – emulators / Chromebooks

Allow "Install unknown apps" for your browser/file manager, then open the APK.

## How the translation works

1. **Glossary** – ~4,500 hand-written JD terms ("加入购物车" → "Add to Cart", "京东自营" → "JD Direct").
2. **Rules** – numbers, prices and promos ("已售1万+" → "10K+ sold", "满199减20" → "¥20 off ¥199+").
3. **Cache** – everything translated once is remembered on the phone.
4. **Machine translation** for the rest (mostly long product titles):
   - *On-device* (default, free). Downloads a ~30MB language pack once. Google's download servers
     are blocked in mainland China, so do the first download on a VPN or outside China.
   - *Claude AI* (Settings → Translation engine). Paste an Anthropic API key for the most natural
     English. Costs a few cents per heavy shopping session.
5. **Images** – offline Chinese OCR finds text in banners and product photos and lays English on
   top in matching colours. Long-press an image to peek at the original.

Tap **EN / 中** in the top bar to flip between English and the original page.

## Build

```bash
./gradlew :app:testReleaseUnitTest :app:assembleRelease
```

Needs JDK 17+ and the Android SDK (platform 35). See `docs/ARCHITECTURE.md` for the design,
`tools/jstest/` for the in-page script tests, and `tools/quality/report.md` for glossary coverage.
