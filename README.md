# OpenMangaReader

A multi-source manga / manhwa / webtoon reader for Android, rebuilt from a technical analysis of the behaviour of
`org.hamak.mangareader` 2.0.7.6.11. It is a **clean re-implementation** (no decompiled code is included).

* Add **any** site by URL. The engine learns the layout from the live site and proves it end to end
  (list → work → chapters → page images) before saving it. Everything it learned is editable JSON (CSS, XPath, JSON paths).
* Extracts **all** chapters (pagination, AJAX, "next page" links), de-duplicates and orders them.
* Extracts only the **real page images** (lazy-load attributes, `srcset`, inline script data) and rejects emoji, avatars,
  logos and ads; every downloaded file is checked by magic bytes, so an HTML page is never saved as an image.
* Latest updates per source, sorted by the real update time, with pull-to-refresh, infinite scroll and an offline cache
  that is clearly labelled as stored data.
* One failing source never affects another; every failure has a plain-language diagnostic.

See [`docs/REPORT.md`](docs/REPORT.md) for what was done, what was tested, and what was **not**.

## Layout

| Module | What it is |
|---|---|
| `engine/` | Pure Kotlin/JVM source engine (jsoup only). Fully testable without Android. |
| `app/` | Android app (Views, OkHttp, Glide, WorkManager) built on the engine. |

## Build and test

```bash
# 1. Engine end-to-end tests (local fixture servers, no Internet needed)
./scripts/run-engine-tests.sh                 # needs kotlinc + a jsoup jar (see the script header)
# or, with Gradle:
gradle :engine:engineE2E

# 2. Android app (needs Android Studio / Android SDK 35 and JDK 17)
gradle wrapper --gradle-version 8.9           # once, to create ./gradlew
./gradlew :app:assembleDebug
```

## Add a source by hand (advanced)

Open *Add source → Advanced settings*, paste or edit the JSON. Minimal HTML example:

```json
{
  "name": "My site", "baseUrl": "https://example.org",
  "latest": {
    "request": {"url": "{base}/page/{page}/"},
    "item": {"css": "div.card"},
    "title": {"css": "h3"}, "link": {"css": "a", "attr": "href"},
    "cover": {"css": "img", "attrs": ["data-src", "src"]}
  },
  "chapters": {
    "item": {"css": "ul.chapters li"}, "name": {"css": "a"},
    "url": {"css": "a", "attr": "href"}, "date": {"css": ".date"}
  },
  "pages": {"item": {"css": "div.reader img"}}
}
```

Placeholders: `{base}`, `{url}`, `{urlSlash}`, `{page}`, `{query}`. For JSON APIs set `"kind": "JSON"` and use `"json"`
paths instead of `"css"`. For sites that only render with JavaScript set `"fetchMode": "WEBVIEW"`.
