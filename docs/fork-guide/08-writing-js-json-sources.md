# Writing JS / JSON extensions

A JS / JSON source tells the app how to read one site: how to list and search its titles, how to get a title's chapters, and how to get the pages, text or videos of a chapter. This guide covers how to find what a source needs on a site, the two formats you can write by hand, **JavaScript** and **JSON definitions**, how to publish them in a repo, and the rules for running them in this app (Komikku).

**New in this edition:** a manga source can give its own suggestions under a title with `getRelated(url)` ([7.1](#71-how-pictures-and-videos-are-loaded)), and tag boxes show their whole list as soon as they're tapped ([7.3](#73-tag-boxes-autocomplete-and-gettags)). A manga source can also mix pictures and videos in one chapter: pages with a `videoUrl` play in the app's player from the reader ([7.4](#74-videos-inside-manga-chapters)). The editor's **Run all** also tests tab catalogs, and a source with tab catalogs may leave out `getPopular` and `getLatest`. [Section 2](#2-reading-a-site-what-to-look-for) explains how to read a site, and [section 7](#7-rules-for-this-app-komikku) lists what works differently in this app.

- [1. Pick a format](#1-pick-a-format)
- [2. Reading a site: what to look for](#2-reading-a-site-what-to-look-for)
- [3. Build and test in the app](#3-build-and-test-in-the-app)
- [4. JavaScript extensions](#4-javascript-extensions)
- [5. JSON definitions](#5-json-definitions)
- [6. Publishing a repo](#6-publishing-a-repo)
- [7. Rules for this app (Komikku)](#7-rules-for-this-app-komikku)
- [8. Mistakes to avoid](#8-mistakes-to-avoid)

## 1. Pick a format

| Format | What it is | Use it when |
| --- | --- | --- |
| JavaScript | A script that defines an `extension` object. It makes its own requests and reads the replies however it likes. | The site needs logic: tokens, decryption, several requests per page, odd layouts. |
| JSON definition | Addresses, CSS selectors and JSON paths, with no code at all. A built-in engine runs it. | The site is plain HTML or a simple JSON API. |
| Boundless script | Scripts written for the Boundless app, run through a compatibility layer. | You already have one. This guide does not cover writing them. |

All three run in a small sandboxed JavaScript engine (QuickJS) on the device. A source can only reach public `http` and `https` addresses, never this device or the local network.

## 2. Reading a site: what to look for

Before writing any code, find out how the site delivers each thing a source needs. Most sources fail not because the code is wrong but because it reads the wrong place. Work through the site in this order, writing down what you find.

### 2.1 Tools for looking

| Tool | What it shows | How to get it on a tablet |
| --- | --- | --- |
| Page source | The HTML the server sends, before the page's own scripts run. **This is what a source sees.** | In Chrome, put `view-source:` in front of the address. |
| The editor's Test tab | What your source actually got: requests, status codes and console. | Run Popular, then tap a title, then a chapter or episode. |
| `console.log(body.slice(0, 2000))` | The start of a reply, in the editor's console. | Add it to a method while testing, remove it after. |
| `host.render(url, { capture: /…/ })` | Every address the page loaded while it ran, such as video streams and API calls. | Use it on the Test tab to find hidden requests. |
| The app's **Logs** screen | Every request the app made, with its status and time, and errors from sources. | The bug button at the bottom of the navigation rail, or More › Logs (phones too, since r86). |
| A desktop browser's DevTools (Network tab) | All requests, with their headers and replies. | Easiest on a computer. Filter by `Fetch/XHR` for APIs, `m3u8` or `mp4` for video. |

**The golden rule:** if something shows in the browser but not in `view-source:`, the page built it with JavaScript. Then the data comes from somewhere else: an API request, a JSON blob inside the page, or it needs `host.render`. Find that source rather than fighting the HTML.

### 2.2 Where data hides, best first

Check these in order. The higher up, the steadier the source will be.

1. **A JSON API.** In DevTools, look for requests returning JSON (`/api/`, `/ajax/`, `/wp-json/`, `graphql`). APIs change less than page layouts and give clean fields. Use `itemsPath` in JSON, or `fetch(...).json()` in JS.
2. **JSON inside the page.** Search the page source for these. Read them with `jsonRegex` in JSON, or a regex plus `JSON.parse` in JS.
    - `__NEXT_DATA__` (Next.js sites): a `<script id="__NEXT_DATA__">` with everything the page shows.
    - `window.__NUXT__`, `__INITIAL_STATE__`, `__APOLLO_STATE__`, `self.__next_f.push`.
    - `<script type="application/ld+json">`: title, image, description, author, dates. Very common on blogs and video sites.
3. **Meta tags.** Almost every title page has these, and they rarely change:
    - `meta[property='og:title']` for the title
    - `meta[property='og:image']` for a large cover, often better than the list thumbnail
    - `meta[property='og:description']` or `meta[name=description]` for the description
    - `meta[property='og:url']` or `link[rel=canonical]` for the page's real address, a good `webUrl`

    Keep the quotes around `og:…`: without them the `:` breaks the selector ([8.1](#81-reading-html)).
4. **The HTML itself.** Use CSS selectors (section 2.4).
5. **Only after the page's scripts run.** Use `host.render` / `"render"` (section 2.8). It is slow, so keep it for the parts that need it.

### 2.3 The list pages: popular, latest and search

For each list, find:

| What | Where to look | Tip |
| --- | --- | --- |
| The address | The address bar after clicking "Popular", "Latest", page 2, and after a search. | Change the page number in the address to confirm where it goes: `?page=2`, `/page/2/`, `&offset=40`. |
| How pages count | Does page 1 have a number? Does the site count from 0? | Use `pageStart: 0` for sites that count from 0, and `pageSize` with `{offset}` for offset-style lists. |
| The item | One repeated box per title. | Pick the smallest element that holds the link, title and cover of one title: `div.item`, `article`, `li.post`. |
| The search address | Search for something with two words, then read the address. | `?s=` is WordPress; `?q=`, `?keyword=`, `/search/word`. Spaces as `+` need `{queryPlus}`, and slug-style sites need `{querySlug}`. |

Test the item selector by counting: page 1 should give about as many items as the site shows (often 20, 24, 30 or 40). Too many usually means you matched both a slider and the list, so make the selector more exact, such as `div.listupd div.bs`.

### 2.4 Writing selectors that last

- **Prefer meaning over position.** `.chapter-list a` survives a redesign; `div > div:nth-child(3) > a` does not.
- **Avoid generated class names** like `css-1x2y3z` or `sc-bdfBwQ`. They change on every site update. Use attributes instead: `[data-testid=title]`, `a[href*=/manga/]`.
- **Read attributes, not only text.** `a[title]` often holds the full title when the visible text is cut short.
- **jsoup selectors.** Selectors follow jsoup, so `:contains(Status)`, `:has(img)`, `:eq(0)`, `:matchesOwn(regex)` and `[href~=regex]` all work.
- **Label and value pairs.** For "Status: Ongoing" rows, use `div.info-row:contains(Status) span` rather than counting rows.

### 2.5 Covers

Covers are the most common thing that "doesn't load". Check, in this order:

1. **Lazy loading.** The real address is often not in `src`. Look for `data-src`, `data-lazy-src`, `data-original`, `data-srcset` or `srcset`. A `src` holding a tiny `data:image/gif` or `placeholder.png` means the real one is in a `data-` attribute. Read them in order:
    `{ "or": [ { "selector": "img", "attr": "data-src" }, { "selector": "img", "attr": "data-lazy-src" }, { "selector": "img", "attr": "src" } ], "resolve": true }`
2. **`srcset` holds several addresses** with sizes: `a.jpg 300w, b.jpg 600w`. Take the last, which is the largest, with `"regex": "([^\\s,]+)\\s+\\d+w\\s*$"`, or the first with `"regex": "^(\\S+)"`.
3. **Background images.** Some sites put the cover in `style="background-image: url(...)"`. Read `"attr": "style"` with `"regex": "url\\(['\"]?([^'\")]+)"`.
4. **Relative addresses.** `/uploads/x.jpg` must become a full address. Use `"resolve": true`, or `require("url").resolve(extension.baseUrl, src)` in JS. `//cdn.site/x.jpg` gets `https:` automatically.
5. **Hotlink protection.** If covers show as blank but the address opens in a browser tab, the image server checks the `Referer`. Set `headers: { "Referer": "https://site.com/" }` on the source. In this app, covers already load with the source's own site as the Referer, plus its cookies and Cloudflare handling, the same way Aniyomi extensions do (see section 7).
6. **A better cover on the title page.** List thumbnails are often small. Return `meta[property='og:image']` as `coverUrl` from `getDetails` (or `details`) for a full-size one.

### 2.6 The title page: details and description

| Field | Where to look |
| --- | --- |
| `title` | `h1`, `meta[property='og:title']`, or the API's `title` / `name`. |
| `description` | A `.summary`, `.synopsis`, `.description` or `[itemprop=description]` block; else `og:description` or JSON-LD `description`. Read the block's HTML with `"attr": "html"` and `"transform": "htmlToText"` to keep paragraphs. |
| `author` | Links under an "Author" or "Artist" label, joined: `{ "selector": ".author a", "all": true, "separator": ", " }`. |
| `genres` | Tag links: `{ "selector": ".genres a", "all": true }`. |
| `status` | The text next to a "Status" label. Words the app understands are listed in [Entry](#entry). Map others with a regex or JS. |
| `webUrl` | `link[rel=canonical]`, `og:url`, or the page address itself. Always set it when your `url` is an API address or an id: it is what WebView, sharing and "Open in browser" open. |

If the list already gives everything, `getDetails` is optional. Add it when the description, genres or a large cover only show on the title page.

### 2.7 Chapters and episodes

Chapter lists are the second most common failure, because many sites load them separately from the title page.

- **Not in the page source?** The list comes from another request. Common ones:
    - **Madara:** a POST to `<title page>ajax/chapters/` with `X-Requested-With: XMLHttpRequest`.
    - **WordPress sites:** `admin-ajax.php` with an `action=` form body.
    - **Other sites:** `/api/manga/<id>/chapters`.

    Find it in DevTools, then use `"method": "POST"` with `"headers"` and `"body"`.

- **The list needs an id the address doesn't have.** Read it from the title page first with a `resolve` step, such as `data-id`, `data-post` or `manga_id` in a script.
- **The list is split over pages.** Use `pagination` with the "next" link, or loop in JS until a page adds nothing.
- **Numbers.** Always try to return `number`; ordering and "next chapter" depend on it. Cut it out of the title with `"regex": "(?:Chapter|Ch\\.?|Episode|Ep\\.?)\\s*([\\d.]+)"` and `"transform": "number"`.
- **Dates.** Sites write "2 days ago", "Mar 3, 2024" or a Unix time. `"transform": "parseDate"` and `require("date").parse()` read all of these.
- **Stable `url`.** The library remembers read and watched state by chapter `url`, so never put something that changes in it (a session token, a timestamp).

**Anime episodes have extra fields that make the episode list rich:**

| Field | What it shows | Where to find it |
| --- | --- | --- |
| `thumbnailUrl` (or `previewUrl`) | A 16:9 picture on each episode row. | Episode cards often have an `img`; APIs call it `thumbnail`, `image`, `still` or `poster`. |
| `description` (or `summary`) | The episode's summary, shown under the title. | Episode pages, or the site's API. Anime databases (Jikan / MyAnimeList, Kitsu, AniList, TMDB) have them by title too. |
| `scanlator` | Shown in the episode's info line. Put **"Sub"**, **"Dub"** or **"Sub · Dub"** here. | Sub/Dub badges or tabs on the episode list. Sites with separate dub lists can return both, with `scanlator` telling them apart. |
| `season`, `seasonName`, `seasonCoverUrl` | Labels each episode with its season when a title lists several. | Season tabs or headings. |
| `dateUploaded` | The air or upload date. | Episode cards, or the API. |

### 2.8 Pages and videos

**Manga pages:**

- **Images in the HTML.** `div.reading-content img`, `#readerarea img` or `.page-break img`. Check the lazy attributes again (`data-src`).
- **Images in a script.** For example `ts_reader.run({...})` (MangaThemesia), `var chapImages = '...'`, or `__NEXT_DATA__`. Read them with `jsonRegex`, or a regex in JS.
- **Images from an API.** One request per chapter, such as `/api/chapter/<id>` returning `images: [...]`.
- **Scrambled pages.** Pages cut into shuffled tiles need `descramble`. It isn't supported in this app yet (see section 7).
- **Posts with pictures and videos.** Galleries and creator posts (Kemono, Coomer, XAsiat and the like) often mix photos and clips. Keep the source a **manga** source and return the clips as pages with a `videoUrl`, in the post's own order. Find the clip addresses the same way as for anime (the video steps below), and use the clip's poster or preview picture as its `imageUrl`. See [7.4](#74-videos-inside-manga-chapters).

**Videos.** This is where most anime sources take the longest:

1. **Is there a `<video>` or `<source>` in the page source?** Then you're done: read `src`.
2. **An `<iframe>`?** The video lives on an embed host, such as a player page on another domain. Fetch the iframe's `src` with `Referer` set to the episode page, and look inside that page.
3. **Look in the scripts** for `.m3u8`, `.mp4`, `file:`, `sources:` or `jwplayer(`. Packed scripts (`eval(function(p,a,c,k,e,d)`) are unpacked with `require("unpacker").unpackAll(html)` or `"decode": "unpack"`.
4. **Encrypted links** (long base64 strings, `data-value`, CryptoJS `U2FsdGVkX1...`): look in the site's JS for the key, then use `host.crypto.aes.decrypt` (`passphrase` for `Salted__` data).
5. **Still nothing?** Let the page play it and catch the stream: `host.render(episodeUrl, { capture: /\.m3u8|\.mp4/ })` returns the addresses in `resources`.
6. **Headers.** Video servers almost always check `Referer` and sometimes `Origin`, usually set to the **embed host**, not the main site. Put them in each video's `headers`, or in `videoHeaders` for JSON. A 403 in the player nearly always means a missing or wrong `Referer`.
7. **Several qualities.** A master `.m3u8` already contains them, and the player offers them. To list each one, use `require("hls").expand(video)` or `"expandPlaylists": true`.
8. **Subtitles.** Look for `.vtt`, `.srt` or `.ass` addresses, or `tracks: [...]` in the player setup. Return them in `subtitles` with a `label`.
9. **Several servers.** When an episode plays from more than one server (HD-1, Vidstream-2, …), give each video the name of its server in `server`. In this app the player then lists the videos in one group per server (see section 7.1).

### 2.9 Headers, cookies and bot checks

| Header | When a site needs it | What to send |
| --- | --- | --- |
| `Referer` | Images, videos and many APIs: "hotlink protection". | The page the request would come from: the site for covers and pages, the embed page for videos. |
| `Origin` | APIs called from the page by its scripts, some video servers. | `https://site.com` (no path, no trailing slash). |
| `User-Agent` | Sites that block apps. | This app sends a browser User-Agent by default. Set your own only if a site wants a specific one. |
| `X-Requested-With: XMLHttpRequest` | WordPress and Madara AJAX endpoints. | Exactly that. |
| `Content-Type` | POST bodies. | `application/x-www-form-urlencoded` for forms, `application/json` for JSON. `fetch`'s `form` and `json` options set it for you. |
| `Authorization`, `x-api-key` | Public APIs with a fixed key in the site's JS. | Copy the value from DevTools; keep it in one constant so it's easy to update. |

**Cookies** are kept per site and shared with WebView. If a site needs you to accept an age check or log in, open the source's WebView once and do it there. For a fixed cookie, such as `adult=1`, set it in code with `host.cookies.set`.

**Cloudflare and other bot checks.** A reply with status 403 or 503 whose body mentions "Just a moment" or "cf-chl" is a bot check, not the page. In this app you usually don't need to do anything: the request is sent again from a hidden browser page on the site, and the site's requests go that way from then on (see 7.1). Only a check you must tap through needs you: open the source in WebView, pass it, then try again. Don't catch and hide fetch errors, so the app can tell you when this happens.

Two habits that keep bot checks away:

- **Don't fire many requests at once.** Running Popular and Latest together, or fetching every page of a list in parallel, is what most often sets a check off. Set `rateLimitMs` (such as `500`) on sites that are touchy.
- **Don't set your own `User-Agent`** unless the site needs a specific one. A passed check is tied to the user agent that passed it, and the app, WebView and the hidden browser all use the same one.

### 2.10 Checklist before saving

- [ ] Popular, Latest and a two-word search each give a full page, and page 2 gives different titles.
- [ ] Covers show in the Test results. If not, check lazy attributes and `Referer`.
- [ ] Opening a title gives its description and genres, and `webUrl` opens the right page.
- [ ] The chapter or episode list is complete and has `number`s, with the newest at the top in the app.
- [ ] Anime: episodes have thumbnails, summaries and Sub/Dub where the site has them.
- [ ] A chapter gives every page, or an episode gives at least one video that plays (watch out for 403s).
- [ ] Mixed posts: each clip is a page with a `videoUrl`, and tapping its play button in the reader plays it.
- [ ] The console shows no errors, and there are no `{placeholder}` left in addresses.
- [ ] Copy the console (its copy button) if you need help: it holds every request and its status.

## 3. Build and test in the app

1. In **Browse → Sources**, tap the **code button** at the top right to open the Code editor.
2. Pick **JS extension** or **JSON declarative** with the tabs at the top. Both start from a template.
    - **Start from APK** (⋮ menu) fills a new source from a Mihon or Aniyomi extension, either one you have installed or an `.apk` file. The extension's code is compiled, so this only fills in what it can read: the site's address, paths and selectors, with everything it found listed in a comment at the top. Madara and MangaThemesia sites get a complete JSON definition.
    - **Import Source** (⋮ menu) loads a `.js` or `.json` source file. **Export Source** saves the one open to a file, and **Delete Source** removes it.
3. Write the source in the code area.
4. The **Details** panel fills itself from the code a moment after you paste or type it: name, language, icon, base URL, and **Manga** or **Anime**. Change a field there only if you want something else than the code says.
5. On the **Test** panel, tap **Run all**, or run one part at a time: Popular, Latest, a search, Catalogs, Filters, Source settings. The buttons are small squares with an icon (▶▶ Run all, ♡ Popular, ✳ Latest, ☰ Catalogs, filter lines for Filters, ⚙ Source settings, Clear); the one you tap opens up with its name. Tap a title in the results to load its chapters or episodes, then tap one of those to load its pages or videos.
    - **Run all** runs Popular and Latest (page 1), your search if the box has text, then the first title's details and chapters and that title's first chapter.
    - For a source with **tab catalogs** (`tab: true`), Run all first runs page 1 of each tab, in the order Browse shows them ("Tab New · page 1", …), and takes its test title from the first tab. Popular and Latest then only run when the source has them.
    - Video pages of a manga chapter show as "Page N · video", with the video's address.
6. The **Console** at the bottom shows `console.log` output and every request. Its copy button copies the console; its share button sends it as a `.txt` file (it arrives as an attachment, however long it is).
7. Tap **Save**. The source shows in **Browse → Sources**, in the manga or anime panel. The message after saving has an **Open** button that goes straight to it.

To change a saved source later, use the folder button in the editor (**Created Sources**), or open the source's sheet in Browse and tap its settings.

## 4. JavaScript extensions

### 4.1 The `extension` object

A script defines one global object named `extension`:

```js
var extension = {
  name: "Example Comics",
  lang: "en",
  baseUrl: "https://example.com",
  iconUrl: "https://example.com/icon.png",

  getPopular: async function (page) { /* ... */ },
  getLatest: async function (page) { /* ... */ },
  search: async function (query, page, filters) { /* ... */ },
  getChapterList: async function (url) { /* ... */ },
  getPageList: async function (url) { /* ... */ },
};
```

| Field | Type | What it does |
| --- | --- | --- |
| `name` | text | The name shown in Browse. |
| `lang` | text | A language code such as `en`, `ja` or `multi`. |
| `baseUrl` | text | The site's address. Pictures are loaded with it as the `Referer` unless `headers` names one. |
| `iconUrl` | text | A full address of the source's icon. |
| `headers` | object | Headers used when the app loads this source's pictures (covers and pages). They are **not** added to your own `host.fetch` calls. |
| `rateLimitMs` | number | The smallest gap in milliseconds between two requests of this source. The app waits for it before each request. |
| `id` | text | **Komikku beta r24 and newer.** The source id to use instead of the one made from the source's key. Give it the id of the APK this source replaces, **in quotes** (`id: "4182924546386218211"`): ids are bigger than a JS number holds exactly. See below. |

**Taking over an APK's library with `id`.** The library, history, reading progress and downloads are saved under a source's id. Give the JS the APK's id and they open through the JS, with no migrating:

1. Find the APK's id in its code: `override val id`, or, when it has none, the one Mihon computes: the first 8 bytes of MD5(`"${name.lowercase()}/$lang/$versionId"`) as a positive number (`versionId` is 1 unless the source sets it).
2. Use the same address format for titles and chapters as the APK (`url` in lists, `getChapterList`). The id makes the app send the saved addresses to the JS; they only load if the JS understands them.
3. Save the source. The APK can stay installed: when a JS source and an APK have the same id, the JS is the one used. To go back to the APK, remove the `id` line and save again.

Some ids can't be taken: the app's own sources (E-Hentai/ExHentai, Local, Merged, Device files) and an id another saved JS source already uses. Then the source keeps its own id and the Logs screen says why. The settings the APK saved are kept under the same id, so the JS sees them if its setting keys match the APK's.

Scripts written for the Flutter or Mangayomi style may set these as globals instead (`NAME`, `LANG`, `BASE_URL`, `ICON_URL`, `HEADERS`, `RATE_LIMIT_MS`). Fields on `extension` win.

Two things to know about how methods are called:

- **`this` is not the extension.** Each method is called on its own, so write `extension.baseUrl`, not `this.baseUrl`.
- **Calls run at the same time.** The app may ask for the popular list and a title's chapters at once. Don't keep the state of one call in a global variable that another call could change.

### 4.2 Methods

Every method may be `async` or return a plain value. Whatever it returns is turned into JSON, so return plain objects, lists, text, numbers and booleans.

These are required. A script without them does not start. `getPopular` and `getLatest` are the exception: a source with `getCatalogs` and `getCatalog` may leave them out, and the app then treats them as empty lists (see [tab catalogs](#72-fields-this-app-uses)):

| Method | Called with | Returns |
| --- | --- | --- |
| `getPopular(page)` | `page`: 1 for the first page, then 2, 3… | A list of [entries](#entry) |
| `getLatest(page)` | `page` | A list of entries |
| `search(query, page, filters)` | `query`: the words typed, which can be empty when someone browses by filters only. `filters`: an object of [filter](#filter-group) values, or `undefined` when none are set | A list of entries |
| `getChapterList(url)` | `url`: the `url` of an entry, exactly as you gave it | A list of [chapters](#chapter) |
| `getPageList(url)` | `url`: the `url` of a chapter | A list of [pages](#page). For manga and novels. In this app, pages may also be videos ([7.4](#74-videos-inside-manga-chapters)) |
| `getVideoList(url)` | `url`: the `url` of an episode | A list of [videos](#video). For anime |

Define `getPageList`, `getVideoList`, or both.

These are optional. The app only shows a feature when its method exists:

| Method | Called with | Returns |
| --- | --- | --- |
| `getDetails(url)` | The `url` of an entry | One [entry](#entry) with the full details. Without it, the app keeps what the list gave |
| `getFilters()` | | A list of [filter groups](#filter-group), shown in the search filter sheet |
| `getTags()` | | **This app only.** Tag lists for `"autocomplete"` filters: `{ listName: ["tag", …] }`. See [section 7.3](#73-tag-boxes-autocomplete-and-gettags) |
| `getRelated(url)` | The `url` of an entry | **This app only, manga sources.** A list of [entries](#entry): the suggestions under that title. Replaces the app's title searches. See [7.1](#71-how-pictures-and-videos-are-loaded) |
| `getCatalogs()` and `getCatalog(id, page)` | `id` of a catalog, `page` | `getCatalogs` returns a list of [catalogs](#catalog), extra tabs beside Popular and Latest. `getCatalog` returns the entries of one. Both are needed |
| `getComments(url, sort)` | The `url` of an entry, and `sort`: `"newest"`, `"oldest"` or `"top"` | A list of [comments](#comment). Shows a Comments button on the title page |
| `getChapterComments(url, sort)` | The `url` of a chapter, and `sort` | A list of comments. Shows a comments button in the reader |
| `getSourcePreferences()` | | A list of [settings](#setting) the person can change for this source |
| `getPagePreviews(url)` | The `url` of a chapter | **This app only, r77 and newer, manga sources.** A list of small preview pictures, one per page: `[{ index, imageUrl }]`, in page order. The Browse and library preview panels show these instead of loading every full page, like TachiyomiSY's page previews for E-Hentai and NHentai. Tapping one still opens that page in the reader. Leave it out when the site has no small thumbnails. See [7.5](#75-fast-page-previews-getpagepreviews) |

Lists load page by page as the person scrolls. A page that adds nothing new ends the list, so returning an empty list, or the same items again, is how you say "no more".

### 4.3 What to return

General rules for every shape:

- **Addresses of pictures and videos must be full** `http` or `https` addresses. One that starts with `//` gets `https:` in front. Anything else is dropped.
- **Dates** are ISO 8601 text, such as `"2026-03-01T12:00:00Z"`. A date with no zone is read as UTC. `require("date").parse(text)` turns most dates sites write, including "3 hours ago", into this form.
- **A broken item in a list is skipped**, and the rest are kept. The call only fails when every item is broken. An item missing a required field counts as broken.
- An entry's or chapter's `url` doesn't have to be a web address. It is whatever your code needs to find that title or chapter again, and the app hands it back unchanged.

#### Entry

A title, as a list or `getDetails` returns it.

| Field | Required | Type | Notes |
| --- | --- | --- | --- |
| `url` | yes | text | Your key for the title. |
| `title` | yes | text | |
| `coverUrl` | | address | |
| `bannerUrl` | | address | Accepted, but the app doesn't show it yet. |
| `webUrl` | | address | The title's page on the site, for "Open in browser". |
| `author` | | text | Several names go in one text, such as `"Ann, Bo"`. |
| `description` | | text | HTML tags are taken out. |
| `rating` | | number | Shown as is, with one decimal. |
| `status` | | text | Words the app understands: ongoing, publishing, releasing, airing; complete, completed, finished, ended; hiatus; cancelled, dropped. |
| `genres` | | list of text | A single text also works. |

#### Chapter

A chapter, or an episode for anime. Return them in the site's own order. Most sites list the newest first, and the app works out which way your list goes from the numbers.

| Field | Required | Type | Notes |
| --- | --- | --- | --- |
| `url` | yes | text | Your key for the chapter. |
| `title` | yes, unless `number` is given | text | Without a title, the app names the chapter by its number. |
| `number` | | number | Used to order chapters and to find the next one. Give it whenever you can. |
| `dateUploaded` | | ISO date | |
| `season` | | text or number | Groups episodes into seasons. |
| `seasonName` | | text | |
| `seasonCoverUrl` | | address | |
| `volume`, `scanlator`, `locked`, `unlocksAt`, `webUrl` | | | Accepted and shown on the editor's Test tab. What this app does with them is in [7.2](#72-fields-this-app-uses). |

The reader's "Open in browser" uses the chapter's `url` when it is a full address, and `baseUrl` followed by the `url` otherwise.

#### Page

One picture of a manga chapter, or one block of text of a novel chapter.

| Field | Required | Type | Notes |
| --- | --- | --- | --- |
| `index` | yes | number | The page's place, from 0. |
| `imageUrl` | for manga | address | |
| `text` | for novels | text | **Plain text, not HTML.** Separate paragraphs with a blank line (`"\n\n"`). The reader splits a block at blank lines. |
| `descramble` | | object | For sites that cut pictures into shuffled tiles. See below. |
| `videoUrl` | | address | **This app only.** Makes the page a video: the reader shows a play button on it, and tapping it plays the video in the app's player. See [7.4](#74-videos-inside-manga-chapters). |
| `videoListUrl` | | text | **This app only.** Like `videoUrl`, for a video that still has to be found, such as a player embed: when ▶ is tapped, the app calls your `getVideoList` with it. See [7.4](#74-videos-inside-manga-chapters). |
| `thumbnailUrl` | | address | For a video page: its picture, when `imageUrl` isn't given. |
| `headers` | | object | For a video page: headers the player sends, such as `Referer`. |
| `subtitles`, `audioTracks`, `quality`, `title` | | | For a video page: as for a [video](#video). `title` names it in the player's episode list. |

A page needs `imageUrl`, `text`, `videoUrl` or `videoListUrl`; a page with none of them is skipped.

`descramble` is `{ cols, rows, order }`. The picture is a grid of `cols × rows` tiles, numbered left to right and top to bottom from 0. `order` lists, for each place in the finished picture, the number of the tile that goes there. So `order[0]` is the tile that ends up top left. `order` must have exactly `cols × rows` numbers. Downloaded chapters keep it, so they show the same way offline.

#### Video

One way to play an episode.

| Field | Required | Type | Notes |
| --- | --- | --- | --- |
| `url` | yes | address | An HLS (`.m3u8`), DASH or plain video file. |
| `quality` | | text | Such as `"1080p"`, shown in the quality menu. |
| `server` | | text | The server the video plays from, such as `"HD-1"`. Videos with the same `server` are listed as one group in this app's player. `hoster` also works. |
| `headers` | | object | Headers the player sends, such as `Referer`. |
| `subtitles` | | list | Each `{ url, label, language }`. `url` is required. |
| `audioTracks` | | list | Same shape as `subtitles`. |
| `intro`, `outro` | | object | `{ start, end }` in **seconds**, for the skip buttons. `introStart`, `introEnd`, `outroStart` and `outroEnd` also work. |

#### Filter group

| Field | Notes |
| --- | --- |
| `key` | The name the value goes by in `filters`. Falls back to `name`. |
| `name` | The label. |
| `type` | `"select"` (one choice, the default), `"multiSelect"` or `"checkbox"`. This app also has `"autocomplete"`, a box to type tags into with suggestions: see [section 7.3](#73-tag-boxes-autocomplete-and-gettags). Komikku beta r23 and newer also has every kind an APK source has, listed below the table. |
| `options` | A list of `{ label, value }`. `name` and `id` also work. Not used by a checkbox or text box. |
| `defaultIndex` | For a select or sort, the option chosen at first. |
| `defaultSelected` | For a multi-select, the values chosen at first. |
| `defaultValue` | For a checkbox, `true` or `false`. For a text box, the text it starts with. For a 3-way checkbox, `0` / `"off"`, `1` / `"include"` or `2` / `"exclude"`. |
| `ascending` | For a sort, `true` when it starts going up. |
| `filters` | For a group, the filters inside it. `children` also works. |

`search` gets `filters` as an object of `key` to value: the option's `value` text for a select, a list of values for a multi-select, and `true` or `false` for a checkbox.

**The APK kinds** *(Komikku beta r23 and newer)*. These make a converted source's filter sheet look the same as the APK's:

| `type` | In the APK | What it shows | Value in `search` |
| --- | --- | --- | --- |
| `"header"` | `Filter.Header` | A line of text, such as "Separate tags with commas (,)". No `key` needed. | none |
| `"separator"` | `Filter.Separator` | A dividing line. No `key` or `name` needed. | none |
| `"text"` | `Filter.Text` | A box to type in. | the text typed, `""` when empty |
| `"tristate"` | `Filter.TriState` | A checkbox that goes off → include (✓) → exclude (−). | `0`, `1` or `2`, like the APK's `STATE_IGNORE`, `STATE_INCLUDE`, `STATE_EXCLUDE` |
| `"sort"` | `Filter.Sort` | A list where the picked entry has an up or down arrow; tapping it again flips the arrow. | `{ index, value, ascending }` |
| `"group"` | `Filter.Group` | A section that opens and closes, holding any of these kinds (text boxes, checkboxes, selects…). No `key` needed. | none of its own: each filter inside is sent under its own `key`, as if it were outside the group |

A `"multiSelect"` is also a group that opens and closes, but it can only hold checkboxes and sends one list. Use `"group"` when the APK's `Filter.Group` mixes kinds, or when each checkbox needs its own key.

```js
getFilters: function () {
  return [
    { key: "sort", name: "Sort by", type: "select", options: [{ label: "Newest", value: "" }, { label: "Rating", value: "r" }] },
    { key: "cats", name: "Categories", type: "multiSelect", options: ["Manga", "Doujinshi", "Cosplay"] },
    { type: "header", name: "Comma (,) Separate; Prefix Dash (-) Exclude" },
    { type: "group", name: "Advanced Options", filters: [
      { key: "f_sh", name: "Browse Expunged Galleries", type: "checkbox" },
      { key: "rating", name: "Minimum Rating", type: "select", options: ["Any", "2 stars", "3 stars"] },
      { key: "f_spf", name: "Minimum Pages", type: "text" },
      { key: "f_spt", name: "Maximum Pages", type: "text" },
    ] },
    { type: "separator" },
    { key: "order", name: "Order", type: "sort", options: ["Date", "Title"], ascending: false },
    { key: "female", name: "Female", type: "tristate" },
  ];
},
// search gets: { sort, cats: [...], f_sh: false, rating: "Any", f_spf: "", f_spt: "",
//                order: { index: 0, value: "Date", ascending: false }, female: 0 }
```

#### Catalog

`{ id, name, tab }`. `id` is passed back to `getCatalog`. It falls back to `name`. `tab` is optional: in Komikku, `tab: true` also makes the catalog a button at the top of Browse. Without it, the catalog is only in the Catalog filter. See [7.2](#72-fields-this-app-uses).

#### Comment

| Field | Required | Type |
| --- | --- | --- |
| `author` | yes | text |
| `text` | yes | text |
| `date` | | ISO date |
| `avatarUrl` | | address |

#### Setting

A setting from `getSourcePreferences`:

| Field | Notes |
| --- | --- |
| `key` | The name you read it back by. |
| `title` | The label. |
| `type` | `"list"` (pick one), `"multiSelect"` (pick several), `"switch"` (on or off) or `"text"`. This app also has `"category"`, `"info"` and `"slider"`: see [Sections, text and sliders](#sections-text-and-sliders). |
| `summary` | A line under the label. |
| `defaultValue` | |
| `options` | For lists, `{ label, value }` pairs. The Mihon form, `entries` with `entryValues`, also works. |
| `dialogTitle`, `dialogMessage` | For text settings. |
| `min`, `max`, `step`, `range`, `unit` | For sliders (this app only). |

##### Sections, text and sliders

*(This app only, Komikku r-builds with source-settings sections and newer.)* These make a source's settings look like an APK's, with headed sections, a "Details" block, and sliders like Settings › Library › Pause between titles. Older builds leave out what they don't know, so the rest still shows.

| `type` | What it shows | Notes |
| --- | --- | --- |
| `"category"` | A section header, such as "Smart Search". The settings after it are listed under it, until the next category. | Needs only `title`; no `key`. `"section"` and `"header"` also work. |
| `"info"` | A block of text that can't be tapped, such as a "Details" explanation. | `title` (optional) and `summary`, which can hold `\n` line breaks. `"details"` also works. |
| `"slider"` | A slider: one handle, or two with `range: true`. The value shows next to it, with `unit` after it ("1–5 s"). | `min`, `max` and `step` are whole numbers. Saved as text when a handle is let go: `"3"`, or `"1-5"` for a range. `defaultValue` is the same kind of text. |

```js
getSourcePreferences: function () {
  return [
    { type: "category", title: "Smart Search" },
    { key: "phrase", title: "Activation phrase", type: "text", defaultValue: "?" },
    { type: "info", title: "Details", summary: "Type the phrase and a space before your search.\nExamples:\n? narutp" },
    { type: "category", title: "Tags" },
    { key: "pause", title: "Pause between tag pages", type: "slider", range: true,
      min: 0, max: 15, unit: "s", defaultValue: "1-5" },
  ];
},
// later: var range = ((await host.preference.get("pause")) || "1-5").split("-").map(Number);  // [1, 5]
```

The app reads the list once, when the source is saved, and keeps it. So text built from a setting's value (like "Your phrase" above) shows the value at save time, not the current one: write explanations that don't depend on it.

Read a setting with `await host.preference.get("key")`. It gives `null` until the person changes it, so apply your own default:

```js
var quality = (await host.preference.get("quality")) || "high";
```

### 4.4 Errors

Throw an `Error` with a clear message when something goes wrong. The message is shown to the person:

```js
if (!response.ok) throw new Error("The site answered " + response.status);
```

Let errors from `host.fetch` pass through, or rethrow them. When a site puts a bot check in the way, the error carries a mark that lets the app offer to open the site so the person can pass it. Catching that error and throwing your own hides it.

### 4.5 What a script can use

#### Requests: `host.fetch` and `fetch`

`host.fetch(url, options)` makes one request:

| Option | Notes |
| --- | --- |
| `method` | `"GET"` by default. |
| `headers` | An object of header names to values. |
| `body` | Text to send. |
| `binary` | `true` to get the reply as base64 in `bodyBase64` instead of `body`. |
| `browserPage` | When the request goes through the hidden browser, the page of the site to send it from. A plain page loads faster than a busy front page. |

It resolves to `{ statusCode, url, headers, body }`. `url` is the final address after redirects. Header values that came more than once are joined with `", "`.

`host.fetch` does **not** fail on 4xx or 5xx answers. Check `statusCode` yourself. It fails when the site can't be reached, when the address is not allowed, or when a bot check couldn't be passed. It doesn't add a browser User-Agent, so set one in `headers` if a site expects it.

`fetch(url, options)` is a friendlier wrapper with the same options plus:

| Option | Notes |
| --- | --- |
| `json` | An object to send as JSON. Sets `Content-Type`, and the method is `POST` unless you give one. |
| `form` | An object to send as a form. Sets `Content-Type`, and the method is `POST` unless you give one. |
| `retry` | `{ attempts, statuses, baseDelayMs }`. Tries again on the statuses given (429 and 503 by default), waiting for `Retry-After` or a doubling delay. |

It resolves to a response with `ok`, `status`, `url`, `redirected`, `headers.get(name)`, `headers.has(name)`, `text()`, `json()` and `base64()` (with `binary: true`). The `cache` option is accepted but does nothing.

Cookies are kept for each source between runs and sent with its requests.

**Bot checks.** When a reply is a bot check page, such as Cloudflare's, the app tries again through a hidden browser on its own. If that gets through, this source's requests go through the browser first from then on. If it doesn't, the call fails, and the app offers to open the site so the person can pass the check.

#### Reading HTML

`host.query(html, selector)` gives every match of a CSS selector as `{ text, html, attributes }`. `text` is trimmed. `html` is the element's inner HTML.

```js
var links = await host.query(body, ".chapter-list a");
links.map(function (a) { return { url: a.attributes.href, title: a.text }; });
```

`host.parse(html)` parses a page once, for many questions about it:

```js
var doc = await host.parse(body);
var title = await doc.text("h1");                  // text of the first match, or null
var cover = await doc.attr(".cover img", "src");   // an attribute of the first match, or null
var rows = await doc.select(".chapter", { limit: 50 });
var first = await doc.selectOne(".chapter");
var link = await first.selectOne("a");             // search inside an element
link.attr("href");                                 // an attribute of the element itself
```

Each element has `tag`, `text`, `html`, `attributes`, `attr(name)`, `select(css)` and `selectOne(css)`. Pass `{ html: false }` to `select` to skip the HTML of each match. The last 24 parsed pages are kept.

> **Careful:** on an element, `text` is a plain string, not a function. Only the parsed page (`doc`) takes a selector in `doc.text(css)` and `doc.attr(css, name)`. To read a child of an element, find it first: `var c = await el.selectOne(".name"); var t = c ? c.text : null;`. See [8.1](#81-reading-html).

Selectors follow jsoup, so `:contains(Next)`, `:has(img)` and `:eq(0)` work. XPath is not supported.

#### The hidden browser: `host.render`

For pages that only show their content after their own scripts run. `host.render(url, options)` loads the page in a hidden browser and resolves to `{ html, url, resources, result, timedOut }`.

| Option | Notes |
| --- | --- |
| `waitFor` | A CSS selector to wait for. |
| `waitForResource` | A pattern (text or `RegExp`) of a network request to wait for. |
| `capture` | A pattern. The addresses of requests the page made that match it come back in `resources`. Handy for video files. |
| `script` | JavaScript run in the page once it's loaded. Its value, or what its promise resolves to, comes back as text in `result`. |
| `early` | JavaScript run before the page's own scripts. It answers by calling `window.__reportResult(value)` or `window.__reportError(message)`. |
| `userAgent` | |
| `timeout` | In milliseconds. 20 000 by default, 85 000 at most. |

`timedOut` is `true` when what you waited for never showed. The page still comes back so you can see why. A device without the browser rejects the call.

#### Cookies

```js
var cookies = await host.cookies.get("https://example.com");   // [{ name, value }]
await host.cookies.set("https://example.com", "adult", "1", { path: "/", maxAge: 86400 });
```

`set` also takes `domain`, `secure` and `httpOnly`.

#### Storage and settings

`host.storage` keeps values for this source between runs. Values can be anything JSON can hold.

```js
await host.storage.set("token", { value: "abc", until: Date.now() + 3600000 });
var saved = await host.storage.get("token");   // null when there is none
await host.storage.delete("token");            // remove() works too
await host.storage.all();                      // every key and value
await host.storage.clear();
```

`host.preference.get(key)` and `host.preference.all()` read the settings from `getSourcePreferences`. `getPreferenceValue(key)` and `SharedPreferences().get(key)` work for scripts written for Mangayomi.

#### Crypto, compression and zip files

Wherever an encoding is asked for, use `"utf8"`, `"hex"` or `"base64"`. Base64 input may be web-safe or unpadded.

```js
await host.hash("sha256", "text");   // hex; md5, sha1, sha224, sha256, sha384, sha512
await host.crypto.hmac("sha256", key, input, { keyEncoding: "utf8", output: "hex" });

await host.crypto.aes.decrypt(data, {
  key: "00112233445566778899aabbccddeeff", keyEncoding: "hex",
  iv: "0123456789abcdef", ivEncoding: "utf8",
  mode: "cbc",           // cbc (default), ecb, ctr or gcm
  padding: "pkcs7",      // or "none"
});
```

AES options:

- **Default encodings.** Encrypting reads the data as `utf8` and gives `base64`. Decrypting reads `base64` and gives `utf8`. Change them with `dataEncoding` and `output`.
- **Key and IV.** The key must be 16, 24 or 32 bytes. Without an `iv`, 16 zero bytes are used.
- **Passwords.** Give `passphrase` instead of `key` and `iv` for data in the `Salted__` form that OpenSSL and CryptoJS write.

RSA:

- `host.crypto.rsa.encrypt(data, publicKeyPem, { padding: "oaep" | "pkcs1", hash: "sha256" })` gives base64.
- `host.crypto.rsa.verify(data, signature, publicKeyPem, { hash })` checks a PKCS#1 v1.5 signature and gives `true` or `false`.

Compression:

- `host.compress.gzip.decode(data)` and `host.compress.deflate.decode(data, { raw: true })` read base64 and give text. Set `encoding` and `output` to change that.
- `encode` does the reverse.
- `raw: true` means deflate with no zlib header.

Zip files:

- `host.zip.entries(data)` lists `{ name, size }`.
- `host.zip.read(data, name, { output: "base64" })` gives one file.
- Both take the archive as base64. `read` gives text unless you ask for `output: "base64"`.

#### Other helpers

- `host.sleep(ms)` waits, up to 10 seconds.
- `host.isTesting` is `true` on the editor's Test tab.
- `host.trace(kind, data)` writes to the editor's console, only while testing.
- `console.log`, `info`, `warn`, `error` and `debug` go to the app's log and the editor's console.
- `btoa` and `atob`, `URL`, `URLSearchParams`, `TextEncoder` and `TextDecoder` work as in a browser.

#### Modules: `require(name)`

| Module | What it has |
| --- | --- |
| `date` | `parse(value, format?)` gives an ISO date or `null`. It reads ISO dates, month names, day and month in either order, two-digit years, Unix times given as numbers, and "3 hours ago". Give a `format` such as `"dd/MM/yyyy"` when numbers could be read either way. |
| `url` | `resolve(base, url)` joins an address to `base`. `absolute(base, url)` resolves it the way a browser does, including `../`. |
| `entities` | `decode(text)` turns common HTML entities back into characters. |
| `base64` | `encode(text)` and `decode(text)` for UTF-8 text. `toBytes(text)` gives a list of bytes. |
| `crypto` | The same as `host.crypto`. |
| `compress` | The same as `host.compress`. |
| `zip` | The same as `host.zip`. |
| `unpacker` | `unpack(script)` and `unpackAll(html)` undo `eval(function(p,a,c,k,e,d)…)` packing. |
| `hls` | `isMaster(text)`, `parseMaster(text, baseUrl)` for the qualities, audio and subtitles of a master playlist. `expand(video)` turns one master playlist video into one video per quality. |
| `dash` | `isManifest(text)` and `parseManifest(text, baseUrl)`. |
| `jwt` | `decode(token)` gives `{ header, payload, signature }`. `verify(token, { secret })` or `verify(token, { publicKeyPem })` checks HS and RS tokens. |
| `subtitles` | `parseSrt(text)`, `parseVtt(text)`, `toSrt(cues)` and `toVtt(cues)`. Cues are `{ start, end, text }` in milliseconds. |

### 4.6 Limits

What the app really enforces (checked against `JsRuntime.kt` and `JsRender.kt`, r86). The same limits apply in the editor.

| What | Limit |
| --- | --- |
| JS running without a break | **30 seconds** (since r86). Only time spent running your code counts: while a `fetch`, `host.render`, `host.sleep` or other host call is being answered, the clock stops, and it starts again from zero when the call returns. Past 30 s the call is stopped with "its code ran for 30 s without stopping" and an `E js` line in Logs; the source keeps working afterwards. There is no limit on a whole method call: a method that keeps awaiting requests can run for minutes. |
| One request (`fetch`) | **60 seconds** for the whole call (`callTimeout`), on top of the app's 30 s connect and 30 s read timeouts. Redirects are followed with OkHttp's defaults (up to 20). |
| Memory | 128 MB per source (stack 2 MB) |
| `host.sleep` | 10 seconds (longer values are cut to 10) |
| `host.render` | `options.timeout`, default 20 seconds. The app doesn't cap it, so a large value really waits that long (and the JS clock is stopped meanwhile). |
| Addresses | Not filtered. The app doesn't refuse `localhost` or private addresses; don't rely on that and don't point a source at them. |

### 4.7 Example

A source for a site with a JSON API:

```js
var extension = {
  name: "Example Comics",
  lang: "en",
  baseUrl: "https://api.example.com",
  iconUrl: "https://example.com/icon.png",
  headers: { Referer: "https://example.com/" },
  rateLimitMs: 250,

  getPopular: async function (page) {
    return list("/comics?sort=views&page=" + page);
  },

  getLatest: async function (page) {
    return list("/comics?sort=updated&page=" + page);
  },

  search: async function (query, page, filters) {
    var genre = filters && filters.genre ? "&genre=" + encodeURIComponent(filters.genre) : "";
    return list("/comics?q=" + encodeURIComponent(query) + genre + "&page=" + page);
  },

  getDetails: async function (url) {
    var comic = await api(url);
    return {
      url: url,
      title: comic.title,
      coverUrl: comic.cover,
      author: comic.authors.join(", "),
      description: comic.synopsis,
      status: comic.ongoing ? "Ongoing" : "Completed",
      genres: comic.tags,
      webUrl: "https://example.com/comic/" + comic.slug,
    };
  },

  getChapterList: async function (url) {
    var chapters = await api(url + "/chapters");
    return chapters.map(function (c) {
      return {
        url: "/chapters/" + c.id,
        title: c.name,
        number: c.number,
        scanlator: c.group,
        dateUploaded: require("date").parse(c.published),
      };
    });
  },

  getPageList: async function (url) {
    var chapter = await api(url);
    return chapter.images.map(function (src, i) {
      return { index: i, imageUrl: src };
    });
  },

  getFilters: function () {
    return [{
      key: "genre",
      name: "Genre",
      type: "select",
      options: [
        { label: "Any", value: "" },
        { label: "Action", value: "action" },
        { label: "Romance", value: "romance" },
      ],
    }];
  },
};

// The reply of the API at a path, read as JSON.
async function api(path) {
  var response = await fetch(extension.baseUrl + path, { retry: { attempts: 3 } });
  if (!response.ok) throw new Error("The site answered " + response.status + " for " + path);
  return response.json();
}

// The titles of one page of a list.
async function list(path) {
  var data = await api(path);
  return data.items.map(function (c) {
    return { url: "/comics/" + c.id, title: c.title, coverUrl: c.cover };
  });
}
```

The JavaScript template in the editor shows the same for an HTML site, and section 2 shows how to find each part on a real site.

## 5. JSON definitions

A JSON definition describes each list as a request and a way to read items from the reply. It holds no code, so it is checked as plain data before it is installed. `//` and `/* */` comments are allowed. Otherwise it must be strict JSON, with no trailing commas.

### 5.1 Top-level keys

| Key | Notes |
| --- | --- |
| `name`, `lang`, `baseUrl`, `iconUrl`, `rateLimitMs` | As in [4.1](#41-the-extension-object). |
| `webBaseUrl` | The site's address for `resolveWeb` fields, when it differs from an API's `baseUrl`. |
| `headers` | Sent with every request of the definition, and used to load its pictures. |
| `popular`, `latest`, `search` | List sections. Each gives entries. A missing one gives an empty list. |
| `details` | A single section. It gives one entry. |
| `chapters` | A list section that gives chapters. |
| `pages` | A list section that gives pages. Needed unless `videos` is given. |
| `videos` | A list section that gives videos. See [5.9](#59-videos). |
| `comments`, `chapterComments` | List sections that give comments. |
| `filters` | A list of [filter groups](#filter-group). |
| `catalogs` | A list of catalogs. See [5.8](#58-filters-catalogs-and-settings). |
| `preferences` | A list of [settings](#setting). |

### 5.2 Sections

A section is one request plus how to read its reply.

| Key | Notes |
| --- | --- |
| `url` | Required. The address, with [placeholders](#53-placeholders). |
| `method` | `"GET"` by default. |
| `headers` | Added to the top-level headers. Values can hold placeholders. |
| `body` | Text to send, with placeholders. |
| `pageStart` | The number the site uses for the first page. 1 by default, so a site that counts from 0 uses `0`. |
| `pageSize` | How many items a page holds. It gives the `{pageSize}` and `{offset}` placeholders. |
| `itemSelector` | A CSS selector. Each match in the HTML is one item. |
| `itemsPath` | A dotted path to a list in a JSON reply, such as `data.results`. Use `""` when the reply itself is the list. |
| `itemsRegex` | A pattern. Each match is one item, as text: its first group, or the whole match. Repeats are dropped. |
| `jsonRegex` | A pattern whose first group is JSON inside the page, such as `"window.__DATA__ = (\\{.*?\\});"`. `itemsPath` then points into it. |
| `htmlPath` | For a JSON reply that carries HTML: the path to the HTML text. `""` uses the whole reply, and `"*"` finds the longest text that looks like HTML. |
| `fields` | Required. An object of output field to [field spec](#54-fields). |
| `pagination` | Fetch the following pages too and join them. See [5.6](#56-pagination-and-sort). |
| `sort` | Sort the items. See [5.6](#56-pagination-and-sort). |
| `resolve` | Steps that find values before the request. See [5.5](#55-resolve-steps). |
| `render` | Load the page in the hidden browser. See [5.7](#57-rendered-pages). |
| `emptyQueryUrl` | For `search` only. The address used when the query is empty and only filters are set. |

The engine reads the items with the first of `itemsRegex`, `jsonRegex`, `itemsPath` and `itemSelector` that is given.

What an item is depends on that choice:

- With `itemSelector`, an item is an HTML element.
- With `itemsPath` or `jsonRegex`, it is a JSON value.
- With `itemsRegex`, it is a text.

In a list of pages, an item without an `index` gets its place in the list.

A single section (`details`) reads its fields from the whole reply. That reply is the JSON at `itemsPath` or `jsonRegex` when given, the JSON object when the reply is one, and the HTML page otherwise.

### 5.3 Placeholders

`{name}` in a `url`, `headers`, `body` or `template` is replaced by a value. A placeholder with no value is left in place. A section whose address still has one is skipped with a warning in the console, and gives an empty list.

| Placeholder | Where | Value |
| --- | --- | --- |
| `{baseUrl}` | everywhere | The top-level `baseUrl`. |
| `{page}` | lists | The page number, shifted by `pageStart`. |
| `{pageSize}`, `{offset}` | lists with `pageSize` | `offset` is `(page − 1) × pageSize`. |
| `{query}` | `search` | The words, URL-encoded. |
| `{queryPlus}` | `search` | URL-encoded with `+` for spaces. |
| `{querySlug}` | `search` | Lower case, with dashes for anything that isn't a letter or digit. |
| `{queryText}` | `search` | The words as they are, safe inside a JSON string in a `body`. |
| `{key}`, `{filter_key}` | `search` | The value of the filter with that `key`. A filter that isn't set uses its default. A multi-select joins its values with the group's `join`, a comma by default. |
| `{entryUrl}`, `{entrySlug}` | `details`, `chapters`, `comments` | The entry's `url`, and its last path segment. |
| `{chapterUrl}`, `{chapterSlug}` | `pages`, `videos`, `chapterComments` | The chapter's `url`, and its last path segment. |
| `{sort}` | `comments`, `chapterComments` | `newest`, `oldest` or `top`. |
| `{key}`, `{pref_key}`, `{preference_key}` | everywhere | The value of a setting the person changed. |
| a `resolve` name | the section | The value a [resolve step](#55-resolve-steps) found. |

Values go in as they are, apart from `{query}`. So when an entry's `url` is a path, write `"{baseUrl}{entryUrl}"`. When it's a full address, write `"{entryUrl}"`.

### 5.4 Fields

A field spec says where a value comes from and what to do with it.

**Where the value comes from.** Use one of these:

| Spec | Value |
| --- | --- |
| `{ "selector": ".title" }` | The first match of a CSS selector inside the item, as text. |
| `{ "selector": "img", "attr": "src" }` | An attribute of the first match. `"attr": "html"` gives its inner HTML. |
| `{ "selector": ".genre", "all": true }` | Every match, as a list. |
| `{ "attr": "href" }` | With no selector, the item element's own text or attribute. A selector only searches inside the item, so this is how to read the item itself. |
| `{ "path": "data.title" }` | A dotted path into a JSON item. List places are numbers, as in `images.0.url`. |
| `{ "template": "/comic/{id}" }` | Text with placeholders. They take the item's attributes or top-level JSON keys, `{text}` for the element's text, `{value}` for an item that is plain text or a number, plus every placeholder of [5.3](#53-placeholders). |
| `{ "join": [spec, spec], "separator": " " }` | Several values joined, leaving out empty ones. With `"requireAll": true`, the result is empty unless every part has a value. |
| `{ "or": [spec, spec] }` | The first spec that gives a value. |
| `{ "if": condition, "then": spec, "else": spec }` | Picks a spec by a condition. |

A condition is one of these:

- A type, tested on the item: `"string"`, `"number"`, `"boolean"`, `"array"`, `"object"`, `"null"`, `"defined"` or `"truthy"`.
- `{ "not": c }`, `{ "and": [c, …] }` or `{ "or": [c, …] }`.
- `{ "path": "a.b", "is": c }` to test a value inside the item.

**What happens next.** These steps then run, in this order, when they are given:

| Step | What it does |
| --- | --- |
| `decode` | One step or a list: `"base64"`, `"urlDecode"`, `"unpack"` (packed scripts), `"jwt"` (the payload, as JSON text), `"gzip"` or `"deflate"` (from base64). |
| `regex` | Keeps the first group of the first match, or the whole match. No match gives an empty value. |
| `split` + `index` | Splits the text and keeps one part. `-1` is the last part. |
| `transform` | `"trim"`, `"number"` (the digits, dot and minus sign of the text read as one number, so cut out the right part with `regex` first when the text holds more than one number), `"capitalize"`, `"htmlToText"` (keeps paragraphs as blank lines, the right form for novel text), `"exists"` (`true` when a value was found, such as a lock icon), `"unixTimestamp"` (seconds or milliseconds to an ISO date), or `"parseDate"` (a date as sites write it, with an optional `"dateFormat"` such as `"dd/MM/yyyy"`). |
| `round` | Rounds a number to that many decimals. |
| `map` | For a list of objects, the value at this path in each one. |
| `separator` | Joins a list into one text, such as several authors. |
| `resolve` | `true` makes a full address from `baseUrl`. |
| `resolveWeb` | `true` makes a full address from `webBaseUrl`. |
| `default` | The value to use when the result is empty. |

Some common fields:

```jsonc
"url":          { "selector": "a", "attr": "href", "resolve": true },
"coverUrl":     { "or": [ { "selector": "img", "attr": "data-src" }, { "selector": "img", "attr": "src" } ], "resolve": true },
"number":       { "selector": ".name", "regex": "Chapter\\s*([\\d.]+)", "transform": "number" },
"dateUploaded": { "selector": ".date", "transform": "parseDate" },
"locked":       { "selector": ".icon-lock", "attr": "class", "transform": "exists" },
"genres":       { "selector": ".genres a", "all": true },
"author":       { "path": "authors", "map": "name", "separator": ", " },
"text":         { "selector": ".chapter-content", "attr": "html", "transform": "htmlToText" }
```

### 5.5 Resolve steps

`resolve` finds a value before a section's own request. A typical use is a numeric id that the site only shows on the title page. It is one step or a list of steps, run in order. Each step stores its value under `as` for the steps after it and for the section.

A step that makes a request:

```jsonc
"resolve": {
  "url": "{baseUrl}{entryUrl}",
  "selector": "#comic", "attr": "data-id",
  "as": "comicId"
},
"url": "{baseUrl}/api/comics/{comicId}/chapters"
```

A request step reads its reply with one of:

- `path`: a JSON path.
- `selector` and `attr`: a CSS selector, with an optional `htmlPath`.
- `cookie`: the value of a cookie the reply set.
- Nothing: the whole reply as text.

`regex` and `transform` then apply. The step can also set its own `method`, `headers` and `body`.

A step with `from` and no `url` reads a value that is already known, without a request:

```jsonc
{ "from": "entryUrl", "regex": "/comic/(\\d+)", "as": "comicId" }
```

A step that fails writes the reason to the console. The section goes on without its value.

### 5.6 Pagination and sort

`pagination` follows a "next page" link and joins every page into one list. This is the usual need for chapter lists that are split over pages:

| Key | Notes |
| --- | --- |
| `nextSelector` | A CSS selector for the link to the next page. |
| `nextAttr` | The attribute that holds its address. `href` by default, and `"text"` reads the link's text. |
| `nextPath` | For JSON replies, a path to the next page's address. |
| `maxPages` | At most this many pages. 20 by default. |

`sort` puts the items in order by one of their fields: `{ "by": "number", "order": "desc" }`. `order` is `"asc"` by default, and items missing the field go last.

### 5.7 Rendered pages

`"render": true` loads the page in the hidden browser and reads the HTML its scripts built. Give an object instead to set the [`host.render` options](#the-hidden-browser-hostrender), such as `{ "waitFor": ".reader img" }`. The addresses a `capture` pattern matched are added to the end of the page as `<div id="captured"><a href="…">…</a></div>`, so a selector can read them. `render` only works with `GET`.

### 5.8 Filters, catalogs and settings

**Filters.** `filters` uses the [filter group](#filter-group) shape, plus `join`: the text that joins the values of a multi-select. Every filter has a value in `search`, its default when the person didn't pick one:

```jsonc
"filters": [
  { "key": "genre", "name": "Genre", "type": "multiSelect", "join": ",",
    "options": [ { "label": "Action", "value": "action" }, { "label": "Drama", "value": "drama" } ] },
  { "key": "sort", "name": "Sort", "options": [ { "label": "Popular", "value": "views" }, { "label": "New", "value": "new" } ] }
],
"search": {
  "url": "{baseUrl}/search?q={query}&genres={genre}&sort={sort}&page={page}",
  "emptyQueryUrl": "{baseUrl}/browse?genres={genre}&sort={sort}&page={page}",
  "itemSelector": ".list .item",
  "fields": { "url": { "selector": "a", "attr": "href" }, "title": { "selector": ".title" } }
}
```

**Catalogs.** Each catalog in `catalogs` takes one of two forms:

- **A saved search:** `{ "id": "romance", "name": "Romance", "query": "", "filters": { "genre": ["romance"] } }`. It runs through the `search` section.
- **A list of its own:** `{ "id": "top", "name": "Top", "url": "{baseUrl}/top?page={page}" }`. It reads its items the way the `popular` section does, with this catalog's keys replacing that section's. `"from": "latest"` builds on another section instead.

Either form can add `"tab": true` to make that catalog a button at the top of Browse in Komikku ([7.2](#72-fields-this-app-uses)).

**Settings.** `preferences` uses the [setting](#setting) shape. A value the person changed can be used as a placeholder: `{quality}`, `{pref_quality}` or `{preference_quality}`.

### 5.9 Videos

`videos` is a list section whose items are [videos](#video). It also takes:

| Key | Notes |
| --- | --- |
| `videoHeaders` | Headers, with placeholders, given to every video that has none of its own. |
| `subtitles`, `audioTracks` | Sections that give `{ url, label, language }` items. They reuse the `url`, `method`, `headers`, `body` and `render` of `videos` unless they set their own. |
| `intro`, `outro` | Single sections whose fields `start` and `end` are in seconds. A missing or odd marker only loses the skip button. |
| `expandPlaylists` | `true` turns each HLS master playlist into one video per quality, plus "Auto". |

### 5.10 Settings newer apps understand

A section may hold an `upgrade` object. Its keys replace the section's own keys in this app, and older apps ignore it. Use it to keep a definition working in apps that don't know a newer key.

### 5.11 Example

The same API source as in [4.7](#47-example), as a definition:

```jsonc
{
  "name": "Example Comics",
  "lang": "en",
  "baseUrl": "https://api.example.com",
  "iconUrl": "https://example.com/icon.png",
  "headers": { "Referer": "https://example.com/" },

  "popular": {
    "url": "{baseUrl}/comics?sort=views&page={page}",
    "itemsPath": "items",
    "fields": {
      "url": { "template": "/comics/{id}" },
      "title": { "path": "title" },
      "coverUrl": { "path": "cover" }
    }
  },

  "latest": {
    "url": "{baseUrl}/comics?sort=updated&page={page}",
    "itemsPath": "items",
    "fields": {
      "url": { "template": "/comics/{id}" },
      "title": { "path": "title" },
      "coverUrl": { "path": "cover" }
    }
  },

  "search": {
    "url": "{baseUrl}/comics?q={query}&genre={genre}&page={page}",
    "itemsPath": "items",
    "fields": {
      "url": { "template": "/comics/{id}" },
      "title": { "path": "title" },
      "coverUrl": { "path": "cover" }
    }
  },

  "details": {
    "url": "{baseUrl}{entryUrl}",
    "fields": {
      "url": { "template": "{entryUrl}" },
      "title": { "path": "title" },
      "coverUrl": { "path": "cover" },
      "author": { "path": "authors", "separator": ", " },
      "description": { "path": "synopsis" },
      "genres": { "path": "tags" },
      "status": { "if": { "path": "ongoing", "is": "truthy" }, "then": { "template": "Ongoing" }, "else": { "template": "Completed" } }
    }
  },

  "chapters": {
    "url": "{baseUrl}{entryUrl}/chapters",
    "itemsPath": "",
    "fields": {
      "url": { "template": "/chapters/{id}" },
      "title": { "path": "name" },
      "number": { "path": "number" },
      "scanlator": { "path": "group" },
      "dateUploaded": { "path": "published", "transform": "parseDate" }
    }
  },

  "pages": {
    "url": "{baseUrl}{chapterUrl}",
    "itemsPath": "images",
    "fields": {
      "imageUrl": { "template": "{value}" }
    }
  },

  "filters": [
    { "key": "genre", "name": "Genre", "options": [
      { "label": "Any", "value": "" },
      { "label": "Action", "value": "action" },
      { "label": "Romance", "value": "romance" }
    ] }
  ]
}
```

The JSON template in the editor shows the same for an HTML site.

## 6. Publishing a repo

A repo is a JSON index file and the source files it points to, on any web host that serves plain files, such as GitHub. People add it in **Browse → Extensions → Add a repo** with the address of the index. An address that doesn't end in `.json` is also tried with `/index.json` and `/versioning.json` added.

```json
{
  "name": "Example repo",
  "sources": [
    {
      "id": "example-comics",
      "name": "Example Comics",
      "lang": "en",
      "mediaType": "manga",
      "engineKind": "js",
      "version": 3,
      "iconUrl": "https://example.com/icon.png",
      "baseUrl": "https://example.com",
      "fileUrl": "https://raw.githubusercontent.com/me/my-repo/main/example-comics.js",
      "nsfw": false
    }
  ]
}
```

| Field | Required | Notes |
| --- | --- | --- |
| `id` | yes | Keep it the same forever. It ties installed copies to updates and keeps reading progress linked. Don't start it with `device:`, which the built-in local sources use. |
| `name` | yes | |
| `fileUrl` | yes | The full address of the `.js` or `.json` file. |
| `lang` | | `en` by default. |
| `mediaType` | | `manga` (the default), `novel` or `anime`. It decides the reader: pictures, text or video. |
| `engineKind` | | `js` (the default) or `json`. |
| `version` | | A whole number. Raise it with every change, and people with a lower version get an update. |
| `iconUrl`, `baseUrl` | | Shown before the source is installed. |
| `nsfw` | | Only feeds the 18+ filter of the repo page. |
| `minEngine` | | The engine level the source needs. This app runs level 2 and hides sources that need more. Leave it out for level 1. |

Some things to know:

- **JSON is checked at install.** A JSON definition must be valid JSON, or it isn't installed.
- **Same ids from two repos.** When two repos give a source the same `id`, the second one is installed under a longer id so both can be kept.
- **Removing a repo.** Sources installed from a repo stay installed when the repo is removed. If the index stops listing one, it shows as "No longer listed" and gets no more updates.
- **Boundless indexes.** An index in the Boundless style is also read. It has `repositoryName`, and its sources have no `fileUrl` and live at `<repo>/<id>/index.js`. Only these can limit a source to the hosts in its `hosts` list and require a `sha256` of the file.

## 7. Rules for this app (Komikku)

This app runs JS and JSON sources with its own prelude, standard library and JSON engine. A few things work differently or aren't supported yet.

### 7.1 How pictures and videos are loaded

- **Covers, pages and episode thumbnails** load through the app's own connection, the same as an installed Aniyomi or Mihon extension: shared cookies, Cloudflare handling, a browser User-Agent, and the source's `baseUrl` as the `Referer`.
  The source's `headers` are added on top and win. So a site that blocks image requests from other apps usually just works. If it doesn't, set `headers: { "Referer": "https://site.com/" }` to the page the images normally load from.
- **Videos** use each video's own `headers` first, then the source's `headers`. The same goes for video pages of a manga chapter ([7.4](#74-videos-inside-manga-chapters)).
- **`host.fetch` sends the app's browser User-Agent** unless you give one.
- **Bot checks.** When Cloudflare (or a similar check) answers a source's request, the request is sent again from a hidden browser page on that site, with the browser's own connection, cookies and user agent. Cloudflare often lets a real browser through while it keeps challenging an app's connection. After the first time, all of that site's requests go this way for the rest of the session; the editor's console says so. Binary requests (`binary: true`) always use the app's connection. WebView uses the same user agent as the app, so a check you pass there by hand also counts for the source.
- **Related titles** (the suggestions under a title's description) work as for installed extensions, from Komikku r35: the site is searched for the full title and for each word of it. A source runs one call at a time, so these searches queue up behind each other, and opening a chapter right away may wait a few seconds for them. A site that answers some of them with "429 too many requests" just shows fewer groups.
- **Your own suggestions: `getRelated(url)`** *(manga sources, Komikku r40 and newer)*. Return a list of [entries](#entry) for the title with that `url`, and the app shows them under the description as one group, the way it shows an APK's own related list. The title searches above are then left out for the source, as APKs with their own list do (`disableRelatedMangasBySearch`). The title itself is dropped from the list. Use it when the site, or the APK you're porting, has a real related list:
  - **The site's own block**, such as Madara's "Related Comics" (`.related-reading-wrap`) or a "More like this" row. It's on the title page, so cache that page and it costs no extra request.
  - **What the APK did.** Check the APK for `fetchRelatedMangaList` / `relatedMangaListParse` before writing your own. AllPornComic's APK takes the title's first 3 genres (skipping Manga, Manhwa, Manhua and Webtoon), reads page 1 of each, and puts the titles that turn up in the most of them first, at most 25. Many APKs (NHentai, for one) have no list of their own and use the title searches; for those, leave `getRelated` out.
  - **Named groups** *(r42 and newer)*. Return an object instead of a list, `{ "Related Comics": [...], "Same genres": [...] }`, and each group is shown under its own name, in the order you give them: a label in the row under the description, and a heading on the full suggestions screen. A title in two groups only shows in the first. A plain list is one unnamed group, as before. Builds r40 and r41 only read a plain list, so an object shows nothing there.
  - Return entries with the same `url` format as your lists, or the same comic shows up twice in the library. Keep it to a few requests: it runs every time a title opens.
  - Changing the code doesn't need a save for the app to notice `getRelated`, but you may need to leave and reopen the source.
- **The full-screen player** plays your anime sources' videos with each video's `headers`, or the source's `headers` when a video has none.
- **Servers in the Qualities list.** When videos name their `server`, the full-screen player's Qualities list shows one group per server that opens and closes, as it does for an installed Aniyomi extension with hosters. The rules:
  - Groups come in the order each server first appears in the list `getVideoList` returns, and the videos inside a group keep that list's order. So sort the list the way you want it shown.
  - The `quality` text is still shown in full under the group. With a group named "HD-1", a `quality` of `"Sub - 1080p"` reads better than `"SUB - HD-1 - 1080p"`.
  - When only some videos name a server, the rest go in a group called "Other". When none do, the list stays flat, as before.
  - The small preview player and downloads don't group: they use the plain list.
  - `require("hls").expand(video)` gives every quality the video's `server`, so set it once before expanding.
  - The grouping is done by the app: it needs beta r11 or newer. Older builds ignore `server` and show one flat list, whatever the source does.

  ```js
  // "Auto" plus one video per quality, all under the "HD-1" group
  var video = { url: masterUrl, quality: "Auto", server: "HD-1", headers: { Referer: embedHost + "/" } };
  return await require("hls").expand(video);
  ```

### 7.2 Fields this app uses

| Field | Used for |
| --- | --- |
| Entry `webUrl` | WebView, Share and "Open in browser" on the title. Without it, the `url` is used when it's a full address, and the site's `baseUrl` otherwise. |
| Chapter `webUrl` | "Open in browser" from the reader. |
| Entry `rating` | Added to the end of the description. |
| Chapter `volume` | Shown as "Vol. N" in front of the chapter name. |
| Chapter `locked` | Shown as a 🔒 in front of the name. |
| Episode `thumbnailUrl` / `previewUrl` | The picture on the episode row. |
| Episode `description` / `summary` | The episode summary under its title. |
| Episode `scanlator` | The episode's info line: use it for "Sub" / "Dub". |
| Episode `season` / `seasonName` | When there are several seasons, each episode name starts with its season, such as "S2 · Episode 4". |
| Video `intro` / `outro` | Chapter marks in the player. |
| Video `server` / `hoster` | The group the video is listed under in the full-screen player's Qualities list. |
| Page `videoUrl` | A video inside a manga chapter: a play button in the reader that plays it in the app's player ([7.4](#74-videos-inside-manga-chapters)). |
| `getCatalogs` / `getCatalog` | A **Catalog** filter in the search sheet: pick a catalog and leave the search empty. A catalog with `tab: true` is also a button at the top of Browse (beta r14 or newer). |
| Catalog `tab` | `true` makes the catalog a button at the top of Browse. When a source has any, they replace Popular and Latest. |
| Entry `badge` | Short text shown after the entry's name in Browse only, such as a post count: "Byoru · 272". Never saved with the name, so the library, downloads and backups keep the plain name (beta r48 or newer). Put counts here, not in `title`. |
| `popularName` / `latestName` | The names of the Popular and Latest buttons in Browse; they keep their icons (beta r46 or newer). |
| `getFilters` | The search filter sheet. |
| `getTags` | Suggestions for `"autocomplete"` filters, read in the background and kept for a week. |
| `getRelated` | The suggestions under a title, instead of the title searches (manga sources, r40 or newer). |
| `getSourcePreferences` | The source's settings screen, and **Source settings** in the editor. `category`, `info` and `slider` settings: see [4.3](#sections-text-and-sliders). |

**Tab catalogs: your own buttons at the top of Browse.** Give any catalog `tab: true` and it becomes a button at the top of the source's Browse screen. Tapping it lists that catalog with `getCatalog(id, page)`, the same as picking it in the filter sheet. The rules:

- **They replace Popular and Latest.** When a source has at least one tab catalog, the Popular and Latest buttons are hidden, and the row is: your tab catalogs (in the order `getCatalogs` or `catalogs` lists them), Filter, then the person's saved searches.
- **Any number.** 5 or 6 tabs are fine; the row scrolls sideways when they don't fit.
- **It opens on the last one used.** The source opens on the tab the person used last, or on the first tab the first time. "Latest" from the Sources list also opens on a tab, since there is no Latest button.
- **Catalogs without `tab: true`** stay in the Catalog filter only, as before. Use that for long lists such as one catalog per genre.
- **A wrong value is safe.** `tab` must be `true`; anything else, or leaving it out, means "no button". Older builds and other apps ignore it, so it's safe in a source you share.
- **When changes show up.** The app reads the catalogs when the source is saved, and again the first time you open the source in Browse. An installed source picks up a change without being saved again, but you may need to leave and reopen it.
- **`getPopular` and `getLatest` become optional.** They're still used elsewhere (such as the Feed), so keep them when the site has those lists. A source with catalogs may leave them out: it saves without a "missing required method" error, and Popular and Latest give an empty list.

```js
var extension = {
  name: "Example",
  baseUrl: "https://example.com",
  getCatalogs: function () {
    return [
      { id: "new",    name: "New",    tab: true },
      { id: "hot",    name: "Hot",    tab: true },
      { id: "recent", name: "Recent", tab: true },
      { id: "all?sort=week", name: "Trending this week" }, // no tab: only in the Catalog filter
    ];
  },
  getCatalog: async function (id, page) { return list("/" + id, page); },
  // ...
};
```

In a JSON definition, add `"tab": true` to the catalogs in `catalogs`.

The editor's **Test** panel still has its Popular and Latest buttons, so you can test those methods. **Run all** tests the tabs: page 1 of each tab, in Browse order, then the first tab's first title ([section 3](#3-build-and-test-in-the-app)).

**Renaming Popular and Latest: `popularName` and `latestName`.** When your two main lists aren't really "popular" and "latest", give the buttons your own names instead of using tab catalogs. The buttons keep the Popular (heart) and Latest icons, so like the other buttons they shrink to a square icon when not selected, and the Feed still uses `getPopular` and `getLatest`. The rules:

- **Strings on the extension object**, next to `name`: `popularName: "Cosplayers"`, `latestName: "Cosplayers (Videos)"`. Leave one out to keep the app's own name for that button. (Globals `POPULAR_NAME` and `LATEST_NAME` work too.)
- **Only the name changes.** Tapping the button still calls `getPopular(page)` or `getLatest(page)`, and the Feed, global search and the Sources list's "Latest" use them as before.
- **Not with tab catalogs.** A source with `tab: true` catalogs hides Popular and Latest, so the names aren't shown. Pick one way: tab catalogs (any number of buttons, no icons) or renamed Popular and Latest (two buttons with icons).
- **When changes show up.** The names are read when the source is saved: save it again in the editor after changing them.
- **Safe to share.** Older builds and other apps ignore both fields.

```js
var extension = {
  name: "Mitaku",
  baseUrl: "https://mitaku.net",
  popularName: "Cosplayers",
  latestName: "Cosplayers (Videos)",
  getPopular: async function (page) { /* the A-Z cosplayers */ },
  getLatest: async function (page) { /* their video posts */ },
  // ...
};
```

### 7.3 Tag boxes: `autocomplete` and `getTags()`

When you convert an APK whose filter sheet has plain text boxes (`Filter.Text`), use `"text"` for an exact match; `"autocomplete"` is an extra this app offers, not what the APK shows. The two mix fine: NHentai keeps the APK's layout (help lines, separator, Sort By, Offset) but uses tag boxes for Tags, Artists, Parodies and the other tag kinds, and plain text boxes for Uploaded and Pages, which take values like `>20d` rather than tags.

This app can show a filter as a box you type tags into, with a dropdown of matching tags, the same box as the built-in E-Hentai source. From Komikku r40, tapping the empty box opens the box's whole list, which scrolls like a dropdown, and typing narrows it down (before r40 the list only showed after 3 letters, and at most 100 matches). From r80, the list is in 0-9 then A-Z order with each word capitalised (only how it's shown: `search` still gets the tag exactly as your list spells it). Tapping a tag in the list picks it (blue, **+**), holding it leaves it out (red, **−**, sent with `-` in front, or the first of `prefixes` when `-` isn't one), and tapping it again clears it; the list stays open to pick several. Tapping the box opens or closes the list without the keyboard; holding the box (from r81) opens a typing bar right above the keyboard, with the matching tags above it, so the filter sheet behind doesn't move. Send adds what was typed, as Enter did. So a long list, such as every artist of a site, works as a dropdown without hard-coding a single name in the source. Each box can suggest a different kind of tag, so a source can have Female Tags, Male Tags, Parodies and Artists boxes that each only suggest their own kind.

**The filter.** Give a [filter group](#filter-group) `type: "autocomplete"`:

| Field | Notes |
| --- | --- |
| `key`, `name` | As for any filter. |
| `tags` | Which of `getTags()`'s lists suggest tags in this box: `"female"`, or several, `["female", "male"]`. Without it, the list named like the `key`. |
| `options` | Extra suggestions written into the source, as plain text or `{ label }`. Fine for a short list; use `getTags()` for a long one. |
| `hint` | Text shown in the empty box, such as `"e.g. dragon"`. |
| `prefixes` | Marks a person may type in front of a tag, kept on what's picked. `["-"]` by default, so `-dragon` means "leave dragon out". `[]` for none, `["-", "~"]` to add another. |

`search` gets the picked tags as a list of text, exactly as picked, prefix included: `filters.female` is `["dragon", "-elf"]`. It's an empty list when nothing is picked. The person can also type a tag that isn't in the suggestions and press Enter, so don't assume every tag came from your lists.

**The lists: `getTags()`.** An optional method that reads the site's tags and returns them as named lists:

```js
getTags: async function () {
  return {
    female: ["dragon", "elf", "maid"],
    male:   ["dragon", "glasses"],
    parody: ["naruto", "one piece"],
  };
},
```

A plain list instead of an object is one list every box suggests. Entries may be text, or objects with `name` or `label`. Duplicates are dropped, and each list keeps up to 100,000 tags in the order given, so put popular tags first.

**When it runs.**

- In the background, when the person opens the source and it has no saved lists yet. From r80 that's the only time it runs on its own: saved lists are never read again by themselves (before r80, every week). The first time, the suggestions show up once the reading is done, so if the box is empty, close the filter sheet and open it again.
- When the person taps **Refresh tag list** in the source's settings. That button only shows when the source has `getTags()`. From r80 it goes at the end of your settings section whose title has "tag" in it (such as `{ type: "category", title: "Tag suggestions" }`), so it sits next to your tag settings; without one, at the end of the screen.
- The lists are kept on the device, so after the first time the boxes work offline and instantly. Changing the source's code doesn't read them again: tap **Refresh tag list** after changing `getTags()`.

`getTags()` runs separately from the source's other methods, so Browse isn't held up while it reads many pages. Because of that:

- Don't write to `host.storage` inside it. Reading settings with `host.preference` is fine.
- If it fails, the app logs why on the **Logs** screen and doesn't try again for an hour. The boxes still work, without suggestions.

**Reading tags from a site.** Look for, in this order:

1. An API that lists tags, such as WordPress's `/wp-json/wp/v2/tags?per_page=100&page=N` or a search-suggestion endpoint.
2. A tag index page, often `/tags/`, `/genres/`, `/artists/`, sometimes over many pages, sometimes sortable by popularity.
3. The genre or tag list on the site's own search page.
4. Nothing at all. Then leave out `getTags()`: the box still works as a plain box for typing tags.

**An API that can't leave tags out.** Some sites only take tags to include (Hiperdex's search API takes `genres` but has nothing for "not"). Then send only the included tags, and drop the entries that have a left-out tag from each page after it arrives. Since an empty page ends the list in the app, read on to the next site page when every entry of a page was dropped, and keep that shift for the later pages of the same search (Hiperdex does this, up to 5 pages).

Keep it bounded. A site with tens of thousands of tags across hundreds of pages makes the first read slow: read the popular pages first and stop after a set number of pages.

**Example.** A site whose tag pages list tags like `<a class="tag" href="/tag/dragon/"><span class="name">dragon</span></a>`, with a box per kind:

```js
getFilters: function () {
  return [
    { key: "female", name: "Female Tags", type: "autocomplete", tags: "female", hint: "e.g. dragon" },
    { key: "male",   name: "Male Tags",   type: "autocomplete", tags: "male" },
    { key: "parody", name: "Parodies",    type: "autocomplete", tags: "parody", prefixes: [] },
  ];
},

getTags: async function () {
  var lists = {};
  var kinds = { female: "/tags/female/", male: "/tags/male/", parody: "/parodies/" };
  for (var kind in kinds) {
    lists[kind] = [];
    for (var page = 1; page <= 30; page++) {           // the 30 most popular pages of each kind
      var r = await host.fetch(BASE + kinds[kind] + "?sort=popular&page=" + page);
      if (r.statusCode !== 200) break;
      var doc = await host.parse(r.body);
      var names = await doc.select("a.tag .name", { html: false });
      if (!names.length) break;                          // past the last page
      names.forEach(function (n) { lists[kind].push(n.text.trim()); });
    }
  }
  return lists;
},

search: async function (query, page, filters) {
  var f = filters || {};
  var words = query ? [query] : [];
  ["female", "male", "parody"].forEach(function (kind) {
    (f[kind] || []).forEach(function (tag) {
      var not = tag.charAt(0) === "-";
      var name = not ? tag.slice(1) : tag;
      words.push((not ? "-" : "") + kind + ':"' + name + '"');
    });
  });
  // …search the site with words.join(" ")…
},
```

### 7.4 Videos inside manga chapters

Some sites put pictures and videos in the same post. In this app, a **manga** source can return both from `getPageList`, so you browse and read the posts as usual and play their clips without leaving the app.

**What you see.** Lists, search, filters, the library and the reader all work as for any manga source. A video page shows its picture with a ▶ button in the middle, and so does its thumbnail on the reader's page panel (r43 and newer), where tapping the ▶ plays it too. Tapping it plays the video in the app's full-screen player, with no WebView or browser. Closing the player comes back to the same page in the reader, and the player's next / previous go through the chapter's other videos.

**What to return.** Give a page a `videoUrl` (the stream itself) or a `videoListUrl` (something your `getVideoList` turns into the stream when ▶ is tapped):

```js
getPageList: async function (url) {
  var post = await api(url);
  return post.files.map(function (f, i) {
    if (f.type === "video") {
      return {
        index: i,
        videoUrl: f.url,                          // .mp4, .webm, .m3u8 or .mpd
        imageUrl: f.poster,                       // optional: the picture shown in the reader
        headers: { Referer: extension.baseUrl + "/" },
        title: "Clip " + (i + 1),                 // optional: the name in the player's episode list
      };
    }
    return { index: i, imageUrl: f.url };
  });
},
```

The rules:

- **Embeds: use `videoListUrl`.** When the post only has player embeds (an `<iframe>` to another host), don't resolve them in `getPageList`: a post with 26 embeds would take 26 extra requests before it opens. Return each embed as `{ index, videoListUrl: embedUrl }` and add a `getVideoList(url)` that finds the stream, exactly as an anime source does. The app calls it only when ▶ is tapped, and passes the page's `headers` under the videos' own. A source with both `getPageList` and `getVideoList` must be saved as **Manga** in the editor's Details panel (or `"mediaType": "manga"` in a repo).

  ```js
  getPageList: async function (url) {
    // …pictures as { index, imageUrl }, embeds as:
    return [{ index: 0, videoListUrl: "https://player.example/embed/abc", title: "Video 1" }, /* … */];
  },
  getVideoList: async function (embedUrl) {
    var page = await fetch(embedUrl, { headers: { Referer: extension.baseUrl + "/" } });
    var m = (await page.text()).replace(/\\\//g, "/").match(/https?:\/\/[^"']+\.m3u8[^"']*/);
    if (m) return [{ url: m[0], quality: "Auto", headers: { Referer: "https://player.example/" } }];
    var r = await host.render(embedUrl, { capture: /\.m3u8|\.mp4/ });   // let the player load it
    return r.resources.map(function (u) { return { url: u, headers: { Referer: "https://player.example/" } }; });
  },
  ```

- **Testing embeds in the editor.** On the Test panel, a `videoListUrl` page shows as "Page N · video (tap to test)". Tapping it runs `getVideoList` on it and lists the streams it found.
- **Return pictures and videos in the post's own order.** Each video page counts as a page in the reader's page count.
- **The picture.** The page's `imageUrl`, else `thumbnailUrl`. Without either, or when the picture fails to load, a plain dark tile is shown under the play button (from Komikku r62 the tile has no ▶ of its own, so only one play button shows). The tile never fails, so the play button is always there. Give video pages a picture whenever the site has one: the player's poster (`<video poster>`, a `poster`/`image` in its setup, or the embed page's `og:image`), so the gallery shows a frame of the video instead of a dark square.
- **Headers.** The player sends the page's `headers`. Without them, it sends the source's `headers` with the site as `Referer`, the same as for pictures. A 403 in the player nearly always means a wrong `Referer` ([2.8](#28-pages-and-videos)).
- **HLS and DASH** work as for anime sources: `.m3u8` goes through the app's relay, and the player offers the playlist's qualities.
- **`subtitles`** and **`audioTracks`** work as on a [video](#video).
- **Full addresses only.** A `videoListUrl` can be any text your `getVideoList` understands. A `videoUrl` that isn't `http`, `https` or `//…` makes the page an ordinary page again (or skips it when it has no `imageUrl`).
- **JSON definitions** can use `videoUrl` too: add a `videoUrl` field to the `pages` section's `fields`. Per-page `headers` and `videoListUrl` need JS; JSON video pages use the definition's `headers`.
- **Downloads** *(r43 and newer)*. Downloading a chapter also saves each video next to its page's picture, as `NNN.video.mkv` (streams, saved with ffmpeg without re-encoding) or `NNN.video.mp4` / `.webm` (plain files). The video is found the same way ▶ finds it, so a `videoListUrl` goes through your `getVideoList` at download time and gets a fresh address. Read offline, those pages keep their ▶ (in the reader and on the page panel) and play the saved file; a chapter saved as CBZ keeps its videos inside the archive. A video that can't be saved leaves its page as a picture, and the reason goes to the Logs screen. The download's progress reaches 100% when the pictures are done, then waits for the videos. Before r43, downloaded chapters kept only the pictures.
- **History.** Videos played this way are not added to the anime History, and they aren't sent with Pair sync.

**When to use an anime source instead.** If the site is mainly videos with episodes, keep it an anime source. Video pages are for posts and galleries where pictures and clips sit together.

### 7.5 Fast page previews: `getPagePreviews`

*(This app only, r77 and newer.)* The preview panels (Browse and the library) show a chapter's pages as a grid. Without help they load every full page, the way the reader does: on a gallery site that can be dozens of large pictures, plus one request per page to find each picture. Many gallery sites also have a **small thumbnail for every page**, usually on a thumbnail server, and those load in a second.

Give the app those with `getPagePreviews(url)`. It is called with a chapter's `url`, like `getPageList`, and returns one `{ index, imageUrl }` per page, in page order. The pictures are loaded with the source's `headers`, like covers. Build the addresses from what you already have: don't fetch each page to find its thumbnail, as that is the slow part this avoids.

```js
// nhentai: the thumbnail of page n is "<n>t.<ext>" next to the full page "<n>.<ext>", on a t* server
getPagePreviews: async function (url) {
  var g = await getGallery(idFromUrl(url));          // the same cached gallery getPageList reads
  var thumbs = (await config()).thumbs;
  return (g.pages || []).map(function (p, i) {
    var path = trimSlash(p.thumbnail || String(p.path).replace(/(\d+)\.(\w+)$/, "$1t.$2"));
    return { index: i, imageUrl: pick(thumbs) + "/" + path };
  });
},
```

- **Leave it out** when the site has no small thumbnails: the panels then load the pages as before.
- **Videos:** a page that is a video ([7.4](#74-videos-inside-manga-chapters)) shows its preview picture like any other page here, without the ▶ button. Tapping it still opens it in the reader.
- **Animated pages:** the previews are still pictures, so an animated page doesn't play in the panel when previews are given.
- **Older builds** ignore the method, so adding it never breaks a source.

### 7.6 Not supported yet

| Feature | What happens |
| --- | --- |
| Page `descramble` | The page shows still scrambled. |
| Page `text` (novels) | Text pages are skipped. Only image pages are shown. |
| `getComments`, `getChapterComments` | Not shown. |
| `host.render` option `early`, `host.fetch` option `browserPage` | Ignored. |
| Source repos | Import each file in the editor instead (**Import Source**). |

### 7.7 Finding problems

- The **Logs** screen (bug button at the bottom of the navigation rail, or More › Logs since r86) has every request the app made and every source error, newest first. Switch to **Problems** to see only failures, and use copy to share them.
- In the editor, the **Console** shows the source's own `console.log` output and each request with its status code and time.
- A failed save shows the exact error the source stopped with, with a Copy button.

## 8. Mistakes to avoid

Real mistakes made while writing sources, what they looked like, and how to avoid them. New ones are added as they come up.

### 8.1 Reading HTML

**Calling `text(css)` on an element** *(Anikoto, October 2026)*

- **What you see:** `TypeError: not a function` in the console, right after a list page loaded with status 200.
- **Why:** on an element from `doc.select` or `doc.selectOne`, `text` is the element's text as a plain string. Only the parsed page itself (`doc.text(css)` and `doc.attr(css, name)`) takes a selector. `el.attr(css, name)` doesn't fail, but it quietly reads an attribute named after the selector and gives `null`.
- **Do this instead:** find the child first, then read it. A pair of helpers keeps it short:

  ```js
  async function subText(el, css) { var c = await el.selectOne(css); return c ? c.text : null; }
  async function subAttr(el, css, name) { var c = await el.selectOne(css); return c ? c.attr(name) : null; }

  var title = await subText(item, ".name");          // not item.text(".name")
  var cover = await subAttr(item, "img", "data-src"); // not item.attr("img", "data-src")
  ```

**Leaving quotes off an attribute value with a `:` in it** *(Jable, October 2026)*

- **What you see:** the details cover is missing, or the selector finds nothing, although the tag is plainly in `view-source:`.
- **Why:** in `meta[property=og:image]`, the `:` is read as the start of a pseudo-class like `:contains`, so the selector doesn't mean what it says.
- **Do this instead:** quote the value whenever it has anything but letters, digits, `-` and `_`: `meta[property='og:image']`. Quoting every attribute value is a good habit.

**Reading links of a kind from the whole page** *(AllPornComic, October 2026)*

- **What you see:** a title's author, series or genres include names that have nothing to do with it, or a tag list has extra names at the top. The same ones show up on every title.
- **Why:** the site's menus link artists, genres and series too (a "Western Artists" dropdown, a "Genres" menu), and a selector such as `a[href*='/series/']` over the whole page picks those up first.
- **Do this instead:** search inside the part of the page that belongs to the title or the list: `.summary_content`, `.post-content`, `.entry-content`, and fall back to the whole page only when that finds nothing. Where the menus and the page's own list use different addresses for the same thing (AllPornComic's group pages use `/porncomic-artist/`, its menus `/porncomicartist/`), keep the page's own.

**Copying an APK's selector that doesn't fit the site** *(AllPornComic, October 2026)*

- **What you see:** a filter from the APK is empty, or missing, though the site plainly has those genres.
- **Why:** the APK was generated from a template (Madara) and looks for genre links containing `/manga-genre/`, but this site renamed them `/porncomic-category/`. The APK's own Genre filter never filled either.
- **Do this instead:** check each selector you copy from an APK against the site's real addresses before trusting it, and use what the site actually has. Note it in a comment, so the next person doesn't "fix" it back.

**Copying an APK's paging as is** *(CosplayTele, October 2026)*

- **What you see:** the source's Popular starts with different titles than the APK's, though both read the same list.
- **Why:** the APK asks the API for `offset = page × 20`. Mihon counts pages from 1, so its first page skips the 20 most popular posts. The JS used `(page − 1) × 24`, starting at the top.
- **Do this instead:** work out what each page of the APK actually asks for (offset, page size, first page number) before copying or "fixing" it, and decide on purpose. CosplayTele now uses the APK's 20 per page but starts at the top, and says so in a comment.

### 8.2 Addresses and pictures

**Returning a relative address** *(Hanime, October 2026)*

- **What you see:** the API call works and returns sources, but the video doesn't play, or the Test tab shows a `url` like `/hls/2491/…`.
- **Why:** the site gives paths, not full addresses. The app needs `https://…` for pages, covers and videos.
- **Do this instead:** turn every address into a full one before returning it, against the page it came from or the address the APK uses (`require("url").resolve(base + "/", path)`, or `"https:" + u` for `//host/…`). Check what the APK does with relative paths: Hanime's added `https://hanime.tv` in front.

**Joining a path that starts with `/` with `url.resolve`** *(CosplayTele, October 2026)*

- **What you see:** requests go to `https://site.com//slug/`, with two slashes. Some sites still answer, others give 404 or a different page.
- **Why:** `require("url").resolve(BASE + "/", "/slug/")` in this app kept both slashes.
- **Do this instead:** when a path starts with `/`, join it yourself: `BASE + path`. Keep `url.resolve` for paths relative to a page, like `../x.jpg`.

**A manga source saved as Anime** *(CosplayTele, October 2026)*

- **What you see:** no suggestions under a title, the Logs screen says `AnimeScreen` / `AnimeRepository`, and titles added with the APK don't show up.
- **Why:** a source with both `getPageList` and `getVideoList` can be saved either way, and it was saved as Anime. `getRelated` and an APK's manga library only work for manga sources.
- **Do this instead:** in the editor's **Details** panel, pick **Manga** before saving, and say so at the top of the source. See [7.4](#74-videos-inside-manga-chapters).

**Covers blocked by the image server's bot check** *(MissAV, jav.guru, October 2026)*

- **What you see:** lists and titles load, but covers stay blank. Fetching the same image address from the source's code works.
- **Why:** the image host is behind Cloudflare or hotlink protection and refuses requests that don't look like they came from the site's own pages.
- **Do this instead:**
  1. Set `headers: { Referer: BASE + "/" }` on the `extension` object, the page the images normally load from. These headers are used for covers and pages ([4.1](#41-the-extension-object), [7.1](#71-how-pictures-and-videos-are-loaded)). For MissAV, a mobile Chrome `User-Agent` there was needed too.
  2. If the site also serves the same picture from its own domain, use that copy instead of the CDN's (jav.guru).
  3. Don't fetch each cover first to test it. On jav.guru that test cost about 6 seconds per list page.
  4. A VPN address is often what gets flagged. If covers worked before and stopped suddenly, try without it before changing code.

### 8.3 Fields

**Replacing the list cover in `getDetails`** *(CosplayTele, October 2026)*

- **What you see:** a cover changes to a different picture after you open the title, and stays changed in Browse (in Panorama it can also change shape, wide to tall).
- **Why:** `getDetails` returned a `coverUrl` (here the post's `og:image`), which replaces the list's picture.
- **Do this instead:** only return `coverUrl` from `getDetails` when the list has none or the original extension sets one there. Check the APK: Mihon's CosplayTele never sets the cover in its details.

**Filling in a field the site doesn't have** *(Hanime, October 2026)*

- **What you see:** every cover shows a "Completed" badge.
- **Why:** the code set `status: "Completed"` on every entry as a default, and the app shows status as a badge.
- **Do this instead:** leave a field out when the site doesn't say. Only set `status`, `author`, `rating` and the like from what the site actually gives.

**Copying an APK's chapter `url` that only works with its hidden `memo`** *(Toonily, October 2026)*

- **What you see:** chapters list fine, but opening one fails, because `getPageList` gets only `chapter-10` and can't tell which title it belongs to.
- **Why:** newer Keiyoushi Madara APKs (lib 1.6) save a chapter as just its slug (`chapter-10`) and keep the title's path in `SChapter.memo`. A JS source is only ever handed the `url`, never the `memo`. The title `url` is the WordPress post id (`"12345"`), with the path also kept in the `memo`.
- **Do this instead:**
  1. Keep the APK's **title** `url` (the post id), so the library still matches. Open it with `BASE + "/?p=" + id`, which redirects to the title page, and save the path you land on with `host.storage` for the chapter request.
  2. Give **chapters** a `url` that carries the title too (`/serie/<title>/<chapter>/`).
  3. Chapters the APK saved then say "Refresh the chapter list." (the APK's own message for the old format) until the list is refreshed once.

### 8.4 Settings and JSON definitions

**A setting's default doesn't fill a JSON placeholder** *(CosplayTele, October 2026)*

- **What you see:** a section such as Popular never loads on a fresh install, with an "unresolved placeholder" warning, and works once the setting is picked by hand.
- **Why:** in a JSON definition, `{setting}` placeholders only get a value once the person saves the setting. The `defaultValue` isn't used for them.
- **Do this instead:** don't build a required address from a setting in JSON. Write the default into the address, and offer the other choices as catalogs, or write the source in JS. In JS, always pass your own default: `(await host.preference.get("range")) || "last7days"`.

**A JSON `resolve` step that needs a POST** *(Xnxx, October 2026)*

- **What you see:** search returns nothing at all, even with no filters.
- **Why:** `resolve` steps can only GET. A step that had to POST (here, to switch country and category before searching) failed, and that stopped the whole section.
- **Do this instead:** in JSON, only use requests the format can make. Anything that needs a POST, a token or several dependent requests belongs in a JS source.

**A setting the site decides by itself** *(Pornhub, October 2026)*

- **What you see:** a setting, here Country, changes nothing however it's sent: address parameter, cookie or header.
- **Why:** the site picks it from the visitor's IP address, which a source can't change.
- **Do this instead:** before porting a setting from an APK, check that changing it changes what the site returns. If it doesn't, leave it out rather than ship a setting that does nothing.

### 8.5 Logins, cookies and bot checks

**Copying a site's toggle as only its cookie** *(Toonily Family mode, October 2026)*

- **What you see:** a setting such as Family mode changes nothing. The site's own list still says 1553 results, while its button gives 354.
- **Why:** the button works through a cookie (`toonily-mature=1` turns Family mode off), but this app keeps sending a cookie the source once set. Setting it to `0`, to empty or to an expired date, and sending your own `Cookie` header, all still reached the site as `toonily-mature=1`. `host.cookies.get` showed it was still there.
- **Do this instead:**
  1. Count first. Read the site's "N results" with the button on and off (open it in a browser, or WebFetch it with no cookies) so you know the target number.
  2. Don't depend on removing a cookie. Filter what the button hides in the request itself. On Toonily, that is titles flagged Adult Content.
  3. Check how the site stores the field before choosing the test. Madara keeps `manga_adult_content` on nearly every title (empty when off, a list holding `yes` when on). So "not exists", which the APK's own Adult Content filter uses, left 2 titles. Use "has the flag and it doesn't contain yes" (`NOT LIKE "yes"` on the key, which leaves out titles with no flag). My first version also let titles with no flag through and got 358 against the button's 354. The site's own no-cookie list showed the 4 extras by name (Sugar Daddy and others). They were new titles that had never had the flag saved.
  4. Leave out any block you can't filter (a title page's "Similar" list).
  5. Check the totals with a debug run that asks for one big page (`posts_per_page` 2500) and compares the counts with the site's own numbers.

**A page that comes back as a saved copy made for another visitor** *(Toonily Similar Manhwa, October 2026)*

- **What you see:** a block on a title page doesn't match what WebView shows for the same title. Here the source got 4 "Similar Manhwa" titles and WebView 6 different ones.
- **Why:** the site picks that block by a cookie, but a plain GET of a title page can be answered with a copy saved for a visitor without the cookie. The source's 4 were exactly what a no-cookie visit (WebFetch) shows. POST requests (the lists) are never saved this way, so they were right.
- **Do this instead:** when a cookie-dependent block matters, load the page with a throwaway parameter (`?_t=` plus the time) so the site builds it for this request. Have the debug run log the block from both the plain and the fresh page, to show whether it changed.

**A session cookie handed out in the middle of a redirect chain** *(E-Hentai / ExHentai, October 2026)*

- **What you see:** a redirect loop, or an empty page, on a site that works when logged in through WebView.
- **Why:** ExHentai only gives its `igneous` cookie partway through a chain of redirects (exhentai → forums → exhentai). This happened in an app that lost cookies set mid-chain, so every request started the loop again. This app follows redirects with its own cookie store, which normally keeps them, but a site can still drop a source into the same loop.
- **Do this instead:**
  1. Load the site once with `host.render` and try again. A real browser follows the whole chain and keeps every cookie on the way, and this app shares those cookies with the source.
  2. Offer the cookie as a setting the person can paste in as a last resort, and set it with `host.cookies.set`.
  3. Turn a redirect loop into a clear error message (such as "not logged in" or "no access") instead of letting it fail with a generic one.

**Asking a Cloudflare site for the same page twice** *(Jable, October 2026)*

- **What you see:** tests pass on one run and hit a bot check on the next, especially debug runs that fetch a lot.
- **Why:** repeated identical requests in a short time are the most likely to be challenged.
- **Do this instead:** keep a reply you already have instead of fetching the same address again within one run, space requests out, and use `fetch`'s `retry` for 429 and 503.

**Reading HTML pages that a Cloudflare check guards, when the site has an API** *(NHentai, October 2026)*

- **What you see:** `getTags()` fails with "wants you to pass its bot check", again right after passing the check in WebView. The Logs screen shows every `/tags/` page answered 403, while the source's `/api/v2/…` requests answer 200.
- **Why:** the site puts its HTML pages behind a check the hidden browser can't pass on its own, and passing it in WebView didn't carry over (a VPN makes this more likely). Its JSON API isn't behind that check.
- **Do this instead:** before reading HTML, look for the same data in the site's API, in DevTools or by trying the API's address pattern (nhentai has `/api/v2/tags/{type}?sort=popular&page=N`). Use the HTML only when there's no API.

**The app's browser retry loses a request's own headers** *(Hanime, October 2026)*

- **What you see:** an API that worked before now answers 403, the Logs screen says "A bot check answered … sending it from a browser page on the site instead", and the retry then answers 401 (hanime: `{"error_id":"UA1","message":"Unauthorized"}`).
- **Why:** the site put a bot check in front of that API. The app's automatic retry from a browser page can't carry every header the source set, such as signature headers or the user agent, so the API turns it down.
- **Do this instead:** send the request yourself from a page of the site with `host.render`: give it your `userAgent`, and in its `script` call `fetch()` with your headers, `credentials: "include"`, and return the status, the body's start and any reply header you need (the API must expose it, which it does when the site's own player reads it). A light page such as `/robots.txt` loads fastest. Keep the direct request as a fallback, and remember which way worked.

### 8.6 Videos and the hidden browser

**Capturing the wrong reply with `host.render`** *(MKissa, October 2026)*

- **What you see:** "No pages found" right away, though everything before it worked.
- **Why:** the capture pattern matched an early reply that had the right field name but no picture addresses in it.
- **Do this instead:** try the API directly first, and use `host.render` only as a fallback. When you capture, match on what you actually need, such as an image address, not only a field name. Log what was captured so a failure shows what came back.

**Giving `host.render` its own `userAgent` on a Cloudflare site** *(hanime, October 2026)*

- **What you see:** the hidden browser gets "Just a moment..." (403) on a site the user already opened fine in WebView, and fetches from that page fail with `TypeError: Failed to fetch`.
- **Why:** Cloudflare ties its `cf_clearance` cookie to the browser's user agent. A `userAgent` option that doesn't match the WebView's own makes the cookie worthless, so the challenge comes back.
- **Do this instead:** leave `userAgent` out of `host.render` on Cloudflare sites. When you see "Just a moment", tell the user to open the source in WebView and pass the check once, instead of retrying.

**Rendering a page that sends you to an ad page first** *(hanime /inter, October 2026)*

- **What you see:** the render captures nothing, and the page source is an "interstitial" (`/inter?r=/videos/...`) instead of the player.
- **Why:** the site shows an ad page before the video, and only skips it when a cookie says you've seen it (`inter=1` on hanime).
- **Do this instead:** set that cookie with `host.cookies.set` before calling `host.render`. Also have the `script` click Continue or Play if it's there, and capture the stream (`capture: /\.m3u8/`) rather than reading the page.

**A `script` that never answers because the page moved on** *(hanime, October 2026)*

- **What you see:** `host.render` comes back within a second with an empty `result` ("no answer"), though the script waits and loops.
- **Why:** the page redirected (here to an ad page, then back to the video). A redirect throws away the page the `script` was running in, along with its answer.
- **Do this instead:** run fetch-only scripts on a page that doesn't redirect, such as `/robots.txt`. To let a player page play, use `early` (it runs again on every page the redirect loads) for clicking, plus `waitForResource` and `capture` for the stream, rather than a `script`.

**Sending an empty CSRF token that the site now checks** *(hanime, October 2026)*

- **What you see:** an API call made from the site's own page answers `422 {"error_id":"CSRF_ERROR_2","message":"Invalid CSRF token format"}`.
- **Why:** the Aniyomi extension sent `x-csrf-token: null`, which the site accepted then. The site now hands out a token first; the address is in the page's config (`window.AppConfig.csrf_token_url`).
- **Do this instead:** look in the page's config for a `csrf` address, fetch it from the same page with `credentials: "include"`, and send its token in `x-csrf-token`. Log the token reply's status and the start of its body, since its format isn't documented.

**A video host that won't play in the app** *(jav.guru Maxstream, October 2026)*

- **What you see:** one server always fails with "Failed to open" while the others play.
- **Why:** its HLS playlist is AES-128 encrypted in a way the player couldn't open.
- **Do this instead:** test each server on its own (a debug setting that tries every server and logs each step is worth adding to video sources). Drop a server that can't play rather than keep a broken choice in the list.

**Looking for the stream in a Filemoon "Byse" player page** *(Mitaku / bysesayeveum.com, October 2026)*

- **What you see:** "Couldn't find the video in the … player". The player page answers 200 but is tiny (about 2 KB, just `<div id="app">` and a script), and `host.render` with `capture` catches nothing.
- **Why:** Filemoon's newer player (it moves between domains such as `bysesayeveum.com`; the address looks like `/e/<id>`) never puts the stream in the page. Its scripts get it through an API: `/api/videos/<id>/embed/details`, then a challenge signed with a P-256 key (`/api/videos/access/challenge` and `/attest`), a proof-of-work puzzle (`/embed/captcha` and `/captcha/verify`), and finally `/embed/playback`, whose answer is encrypted with AES-GCM. In the hidden browser nobody presses play, so none of it runs.
- **Do this instead:**
  1. Ask `https://<host>/api/videos/<id>/embed/details` first: a JSON answer means it's this player, and `embed_frame_url` is the real player page.
  2. The script engine has no P-256 signing (`host.crypto` has AES, HMAC and RSA only), so run the challenge, puzzle and playback steps in a `host.render` `script` on the player's own page, where WebCrypto signs and decrypts and the puzzle solves in well under a second.
  3. Copy the steps from Aniyomi's current `FilemoonExtractor` and log every reply (status and the start of the body), since Filemoon changes this flow often. The two Aniyomi versions sign the nonce differently (as text with a DER signature, or decoded with a raw one), so try both.
  4. Send the embed headers the way the real page would, or playback answers `403 "embedding from this domain is not allowed"` even after every other step passed: `X-Embed-Origin` is the site's host (`mitaku.net`), `X-Embed-Referer` the site (`https://mitaku.net/`), and `X-Embed-Parent` the **outer** player address from the post's iframe (`https://bysesayeveum.com/e/<id>`), not the inner `embed_frame_url` page the steps run on. Pass the post along with the page (Mitaku adds `#mitaku=<post>` to its `videoListUrl`) so `getVideoList` knows it.
  5. Play the `.m3u8` with the player's own address as `Referer`.

**Expecting the JS alone to make server groups** *(Anikoto, October 2026)*

- **What you see:** every quality sits in one flat list in the player, though each video has a `server`.
- **Why:** the groups are drawn by the app, not by the source. The source only labels each video with its `server`; an app build that doesn't read that label ignores it. Before beta r11, the beta builds didn't read it.
- **Do this instead:** set `server` on every video (set it before `hls.expand` or your own playlist splitting, so every quality keeps it), and check the app is r11 or newer. If the groups still don't show, look at the app first, not the JS. Changing the `quality` text can only reorder a flat list; it can't make real groups.

**Stream pieces kept on another site that refuses your headers** *(Anikoto / megap.shiora.top, October 2026)*

- **What you see:** the player log shows `hls: Failed to open segment 0 … 5 of playlist 0`, then "Failed to open" on addresses like `p16-ad-site-sign-sg.tiktokcdn.com/…~tplv-…-origin.image` or `…/seg-f2-00000.png`. Some servers play and most don't, and switching quality doesn't help.
- **Why:** the playlist comes from the video host, but its pieces are stored somewhere else: on an image server (TikTok's CDN), or as files named `.png`. The app sends every request with the headers your video gave it (`Referer`, `Origin`), and an image server may refuse a `Referer` from a site it doesn't know. Pieces stored as pictures may also start with a fake picture header in front of the real video data.
- **Do this instead:** keep the headers on your video for the playlist host only; you don't need to do anything per piece. From beta r67, the app asks again without your `Referer` / `Origin` when another site refuses a piece, and removes fake PNG, JPEG, GIF and WebP headers. If it still fails, the Logs screen shows which site refused the piece and with what code (403, 404 …), so you know whether the host or the piece is the problem.

### 8.7 Speed

**One slow step repeated for every request** *(E-Hentai, October 2026)*

- **What you see:** galleries take a long time to open.
- **Why:** a fixed `rateLimitMs` delay on every request, the same gallery page fetched separately by details, chapters and pages, and page links looked up one at a time.
- **Do this instead:** only rate-limit as much as the site needs, keep a page you just fetched in a short-lived cache so details, chapters and pages share it, and look up many page links a few at a time instead of one by one.

**Reading many pages fast, and dropping everything on one failure** *(NHentai getTags, October 2026)*

- **What you see:** "Couldn't read the tags: … answered 429", and no suggestions at all, although the Logs screen shows the first pages came back fine.
- **Why:** nhentai answers 429 ("too many requests") after about 15 quick requests without an API key, for close to a minute, and Browse was using the same API at the same time. The code gave up on the first failure and threw away the 1,800 tags it already had.
- **Do this instead:** in anything that reads many pages (`getTags()`, catalogs, page lists), pace the requests yourself, wait out a 429 with growing pauses (`host.sleep` takes at most 10 s, so loop), and keep what you already read: return a partial list rather than an error. Only fail when nothing at all could be read.

### 8.8 Testing

**Running out of time in the editor** *(Jable, October 2026)*

- **What you see:** a debug or test run stops with a time-out and shows nothing useful.
- **Why:** each request has a time limit, and since r86 JS that runs 30 s without awaiting anything is stopped ([4.6](#46-limits)); a run that tests everything at once in one long loop can hit either.
- **Do this instead:** give a debug run its own time budget and a time-out per request, and split it into sections that can run on their own (`!debug lists`, `!debug videos`).

**Testing old code or a wrong address** *(MissAV, Hanime, October 2026)*

- **What you see:** a fix "doesn't work", and the new report is identical to the old one. Or a 404 for an address that should exist.
- **Why:** the new code wasn't saved over the old one (MissAV's report was byte-for-byte the previous one), or two list entries were pasted together into one test address (Hanime).
- **Do this instead:** after pasting new code, check something only the new version has, such as a new setting, before testing. Copy test addresses from the Test tab one at a time. Making the code that reads an address ignore extra text after it also helps.

**A debug log that reaches the Logs screen** *(Anikoto, CosplayTele, October 2026)*

- **What you want:** to see what a source did on the device, in normal use, and send it back.
- **Don't** write a separate test run, a button or a Debug tab: the editor's **Run all** already tests the lists, a title, its chapters and its pages.
- **Do this:** add a switch `{ key: "debug", title: "🐞 Debug log", type: "switch", defaultValue: false }` and a helper that logs only when it's on (or while testing in the editor). Call it at each step: list page and item count, details, pages found, and every step of finding a video. Everything a source writes with `console.log` shows on the **Logs** screen.

  ```js
  async function debug(label, data) {
    var on = await host.preference.get("debug");
    if (!(on === true || on === "true") && !host.isTesting) return;
    console.log("🐞 " + label + ": " + (typeof data === "string" ? data : JSON.stringify(data)).slice(0, 1500));
  }
  ```

**Tabs a source doesn't need** *(CosplayTele, October 2026)*

- **What you see:** a row of tab buttons and a **Catalog** box in Filter that the original extension never had.
- **Why:** any `getCatalogs` makes a Catalog filter, and `tab: true` catalogs replace Popular and Latest.
- **Do this instead:** when porting an extension, give it the same lists it had: `getPopular` and `getLatest`, and no catalogs unless asked.

**A video player that loads its stream only after play is pressed** *(CosplayTele / cossora.stream, October 2026)*

- **What you see:** the embed page answers 200 but has no `.m3u8` or `.mp4` in it, and `host.render` with `capture` times out with nothing caught.
- **Why:** the player is an app: its scripts ask an API for the stream only once someone presses play, and nobody presses play in the hidden browser.
- **Also check the page you actually got.** cossora.stream's player sends desktop browsers to a "no video" page (a devtools check that only runs when the user agent isn't a phone), and the hidden browser then sees one request: the favicon. Log `location.href` from your `script`, and pass a phone's `userAgent` to `host.render`.
- **Read the page's own setup code before reaching for the hidden browser.** cossora.stream turned out to keep the address in the page itself, encrypted: `const videoURL = '<base64>'` and `decryptLink(videoURL, '<key>')`, where `decryptLink` (hidden in obfuscated code) is AES-256-CBC with the key text as the key and the first 16 bytes of the data as the IV. `host.crypto.aes.decrypt` does it in a few lines, with no browser. To find such a function, log the full inline script (in parts), and run just the obfuscator's string decoder and the function with a CryptoJS stand-in that records its calls.
- **The hidden browser sends no `Referer`.** A player that checks it (cossora answers `{"error":true,"message":"Unknown Error xD"}`) can't be loaded with `host.render` directly.
- **JW Player pages** (`jwplayer.js`): once the page's scripts have run, `jwplayer().getPlaylist()[0].file` (and its `sources`) hold the stream, even when it was decrypted by obfuscated code. Read it in your `script`; there's no need to press play.
- **Do this instead:** give `host.render` a `script` that clicks the play button (`.vjs-big-play-button`, `.jw-icon-display`, `.plyr__control--overlaid`, …), calls `video.play()`, then waits a few seconds and returns `performance.getEntriesByType("resource")` names and `video.currentSrc`. Pick the `.m3u8` / `.mp4` addresses from those. Log the requests too, so a failure shows what the player did.

**A setting that switches off more than it says** *(hanime, October 2026)*

- **What you see:** after turning an option off by default (here "Load episode thumbnails / descriptions", meant for lookups on outside sites), episodes lose their pictures and summaries completely, even the ones the site itself gives.
- **Why:** the site's own pictures and summaries were added inside the same `if (wantThumbs …)` check as the outside lookups, so one switch turned off both.
- **Do this instead:** keep what the site itself gives outside any optional-lookup switch, and let the switch control only the extra requests. When you change a setting's default, search the file for every place that reads it.

**Skipping "duplicate" servers makes the list look broken** *(Anikoto, October 2026)*

- **What you see:** the Qualities list has fewer servers than the APK, and some servers have only DUB or only SUB.
- **Why:** several server entries led to the same player page, and the source read each page once and left the copies out. Nothing failed, but the copies' servers disappeared from the list.
- **Do this instead:** list each server the site offers, even when two of them play the same video; read their pages at the same time (`Promise.all`) so it costs little. Name videos by what the group heading doesn't already say ("SUB - 1080p" under "HD-1", not "SUB - HD-1 - 1080p"). Leave out an "Auto" copy unless it's really different; the app lists each group from highest quality down (r84+).

### 8.9 Reading errors

- **Line numbers match your file.** In `at list (source.js:381:38)`, `source.js` is your own code as saved in the editor, and 381 is its line number. Go to that line first.
- **A `[net] … -> 200` line followed by an error** means the site answered fine and the bug is in the code that reads the reply, not in the address or headers.

