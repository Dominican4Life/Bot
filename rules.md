# Source rules: video streams and comic pages

These are general lessons for writing Code editor (Sumizuri) sources for this app, video and comic alike.
They describe patterns, not particular sites. Every helper named here exists in the app
(`app/src/main/assets/sumizuri/stdlib.js`, `prelude.js` and `app/.../sumizuri/*.kt`).

---

## 0. Hard limits (read first)

| Rule | Why |
|------|-----|
| **Skip DRM sources. Never try to break DRM.** | Widevine, PlayReady and FairPlay streams can't be played by the app's player, and getting around them is off-limits. |
| Don't work around paywalls, logins you don't own, or geo-blocks. | A source should show only what a normal visitor can see in a browser. |
| Respect the site: set `rateLimitMs`, cache, don't hammer. | Getting the app's IP banned breaks the source for good. |

**How to recognise DRM, then drop that video and pick another server or quality:**

- DASH `.mpd` with `<ContentProtection>` and a Widevine/PlayReady `schemeIdUri`
  (`edef8ba9-79d6-4ace-a3c8-27dcd51d21ed` = Widevine, `9a04f079-…` = PlayReady), or a `cenc:pssh` box.
- HLS with `#EXT-X-KEY:METHOD=SAMPLE-AES`, `SAMPLE-AES-CTR`, or `KEYFORMAT="com.widevine…"` / `"com.apple.streamingkeydelivery"`.
- The page's player calls a "license" URL (`/license`, `/widevine`, `/drm`) or uses `navigator.requestMediaKeySystemAccess`.

**Not DRM, and fine to support:**
- `#EXT-X-KEY:METHOD=AES-128,URI="…"`. This is ordinary HLS encryption. The playlist names its own key and the player
  (mpv) fetches it with the video's headers. Return the playlist as is and don't decrypt anything yourself.
- Obfuscated or packed player scripts, base64 or encrypted *links* in the page, signed/expiring CDN URLs, and
  Referer/cookie checks. All of these are covered below.

---

## 1. How video gets delivered

| Kind | Looks like | What the source returns |
|------|-----------|-------------------------|
| HLS | `.m3u8` (master lists qualities, media lists `.ts` / `.m4s` pieces) | The master `.m3u8` URL. The app sends HLS through `HlsRelay` automatically. |
| DASH | `.mpd` XML | The `.mpd` URL (check for DRM first, see §0). |
| Progressive | `.mp4` / `.webm`, often with `?token=` | The file URL. Add one entry per quality if the site offers several. |
| `blob:` URL in the browser | Media Source Extensions. The real stream is a `.m3u8`/`.mpd` fetched by the player script. | **Never return a `blob:` URL.** Find the manifest it was built from (§2). |

Rules:
- **Return the master playlist** and let the player pick qualities, unless the site lists separate files per quality.
  `require('hls').parseMaster(text, url)` splits a master list into `{ url, quality, bandwidth, … }` entries if you
  want each quality as its own entry; `require('dash').parseManifest` does the same for DASH.
- Fill `quality` like `"1080p"` so the player can read the resolution, and set `server` when there are several hosts.
- Subtitles go in `subtitles: [{ url, label }]` and separate audio in `audioTracks`. `require('subtitles')` converts
  SRT/VTT if a site serves something odd.
- Some hosts disguise HLS pieces as PNG/JPEG/GIF. Browsers play them but mpv stops. `HlsRelay` strips the fake header,
  so make sure the URL contains `m3u8` so the app knows to relay it.

---

## 2. Finding the stream URL (in order of preference)

Start with the cheapest method and only go further down the list when it fails.

1. **The site's own JSON API.** Look in the page for `fetch(`/`ajax` calls, `/api/`, `/ajax/`, `.json` and
   `?action=`. These are the most stable and the fastest.
2. **Data embedded in the HTML:** `<script type="application/json">`, `__NEXT_DATA__`, `__NUXT__`,
   `window.__INITIAL_STATE__ = {…}`, `<video><source src>`, `data-src` / `data-config` attributes, JSON-LD
   (`"contentUrl"`). Use `doc.select(...)` plus `JSON.parse`.
3. **Player setup calls:** `jwplayer().setup({file: …})`, `new Clappr.Player({source: …})`, `Playerjs({file: …})`,
   `videojs`, `sources: [{ file/src }]`. Regex the script text for `https?://[^"']+\.(m3u8|mpd|mp4)`.
4. **Packed scripts:** `eval(function(p,a,c,k,e,d)…)`. Use `require('unpacker').unpackAll(html)`, then search the
   result as in step 3.
5. **Encoded links:** base64 (`atob`, `require('base64')`), reversed strings, hex, `String.fromCharCode` arrays, or
   ROT/XOR. Decode the same way the page's own script does. Read the script first. Don't guess.
6. **Links encrypted with a key in the page's JS** (often AES-CBC, CryptoJS-style `U2FsdGVkX1…` = "Salted__").
   Use `require('crypto').aes.decrypt(data, { key, iv, mode, keyEncoding, … })`. Expect the key to rotate:
   read it from the script at run time instead of hard-coding it, and log clearly when decryption fails.
7. **Iframe embed hosts.** The page often only contains `<iframe src="//host/e/ID">`. Fetch that URL **with the
   parent page as `Referer`**, then apply steps 1–6 to the embed page. Write one small function per embed host and
   reuse it.
8. **Last resort: `host.render(url, { capture: /\.m3u8|\.mpd|\.mp4/, waitForResource, timeout })`.** This runs the
   real page in a hidden WebView and returns the network URLs it requested. It's slow (seconds) and heavy, so use it
   only when the URL is built by code too tangled to follow.

---

## 3. Headers, tokens and cookies (most "it doesn't play" bugs)

- **Referer/Origin:** most CDNs refuse requests without the embed page as `Referer` (and sometimes `Origin`). By
  default the app sends `Referer: baseUrl/`. When the stream came from an embed host, set
  `headers: { Referer: "<embed page>", Origin: "<embed origin>" }` **on the video entry**. Per-video headers win.
- **User-Agent:** if you fetched the page with a particular UA, play with the same one. Tokens are sometimes tied to it.
- **Signed/expiring URLs** (`?token=…&expires=…`, `md5=`, `/hls/<hash>/`). Fetch them **in `getVideoList`, at
  play time**, never in chapter/episode lists or storage, because they expire within minutes or hours.
- **IP-bound tokens:** the token is valid only for the IP that asked for it. That's fine on the phone, but casting
  to a TV can fail. Note it in the source.
- **Cookies:** use `host.cookies.get/set`. Some players need a cookie the page set first, so fetch the page before
  the API.
- **Bot checks (Cloudflare "Just a moment…"):** the app detects these and automatically re-sends the request from a
  hidden browser page (`SumizuriBrowserFetch`). Don't build your own bypass. Binary requests (images) don't go
  through the browser, so image hosts behind a challenge may need the cookies from a page fetch first.

---

## 4. Comics / manga pages

- `getPageList` returns `[{ index, imageUrl }]`. Look for, in order: a chapter JSON API, an image array in a
  script (`var images = [...]`, `chapImages = '…'.split(',')`, base64 JSON), then `<img>` tags.
- **Lazy loading:** the real URL is usually in `data-src`, `data-lazy-src`, `data-original`, `srcset` or
  `data-srcset`, while `src` holds a placeholder (`lazy.gif`, `data:image/…`). Check the data attributes first and
  drop placeholders.
- **Image Referer:** image CDNs often check `Referer`. Pages are fetched with the source's `headers` (from
  `getMetadata`). Put the needed `Referer` there. Per-page headers aren't supported for images yet.
- **Formats:** WebP and AVIF are normal. JXL may not decode on older Android. Skip GIF ad banners and tracking pixels
  (tiny width/height, or `/ads/` in the path).
- **Pagination:** some chapters spread images over several pages (`?page=2` or a "next" link). Fetch them all,
  sequentially and with the rate limit.
- **Scrambled/tiled images** (the page reassembles shuffled tiles on a `<canvas>`; the seed is often in the URL or a
  data attribute). **The app can't unscramble these yet.** Returning the raw URL shows jumbled tiles. If a site does
  this, either skip it or add a descrambling step to the app first (a Kotlin image interceptor that redraws tiles
  from the seed), as its own task.
- **Ordering:** sort pages by `index`. Never rely on the order of `<img>` tags when the site also has ads or
  thumbnails in the same container.
- **Video inside a chapter:** a page can carry `videoUrl` (direct stream) or `videoListUrl` (resolved by the
  source's `getVideoList` at play time) plus `thumbnailUrl` and `headers`. Use `videoListUrl` for anything with
  expiring tokens.

---

## 5. Resilience

- **Selectors:** prefer stable hooks (ids, `data-*`, JSON keys) over layout classes (`.col-md-4 > div`). Give
  important lookups a fallback: `select('.a') || select('.b') || regex`.
- **Multiple servers:** return every working server with `server` names so the user can switch when one is down.
  Wrap each embed host in `try/catch` so one broken host doesn't hide the rest.
- **Retries:** use `fetch(url, { retry: { attempts, statuses, baseDelayMs } })` for flaky hosts. It retries 429/503
  by default and honours `Retry-After`. Don't add 403/404 to `statuses`, because retrying won't fix them.
- **Absolute URLs:** resolve every link with `require('url').absolute(base, href)`. Handle protocol-relative
  `//cdn…` (the app adds `https:` for videos).
- **Don't hard-code** keys, tokens, hostnames of mirrors, or build ids (`/_next/data/<buildId>/`). Read them from
  the page each time.
- **Cache wisely:** `fetch(url, { cache: true })` revalidates pages with ETag. Cache stable things (genre lists, a mirror domain) with `host.storage`. Never cache stream URLs.
- **Fail loudly:** throw an error with a clear message ("embed host X: no m3u8 in unpacked script") instead of
  returning an empty list. Empty results look like "no episodes" to the user.

---

## 6. Debugging

- Turn on the source's testing/debug switch. Requests and `console.log` go to the app's **Logs** screen
  (`host.isTesting` is `true` while it's on).
- When a video won't play, check in this order: (1) is the URL `m3u8`/`mpd`/`mp4` and not `blob:` or HTML?
  (2) does it open with the same headers in a desktop tool? (3) is it DRM (§0)? (4) has the token expired?
  (5) is it a disguised-piece host (handled by the relay)?
- When images won't load: Referer, a lazy-load placeholder, a challenge on the image host, or scrambling.
- Keep the source file's top comment up to date: which approach from §2 it uses and which headers it needs.

---

## 7. Checklist before shipping a source

- [ ] No DRM streams returned. DRM entries are detected and skipped.
- [ ] Stream URLs are resolved in `getVideoList`, not stored.
- [ ] Each video entry has `quality`, `server` when there are several, and the right `Referer`/`Origin`.
- [ ] Each embed host is in its own `try/catch`.
- [ ] Comic pages: no placeholders, ads or duplicates. Order checked against the site.
- [ ] `rateLimitMs` set and pagination done sequentially.
- [ ] Errors are thrown with useful messages and debug logging works.
- [ ] No specific site names in shared docs. Only general lessons go here.
