---
version: 11
# Komikku Source Lab rules file. Body: fork guide 08 §7–§8 (scripts/rules_from_guide.py).
# Machine rules below; `check` is one of regex-in-code, selector-pattern, result-field,
# compare-saved-chapter-urls, header-present, response-status, render-option, or text (read only).
rules:
  - id: og-attr-unquoted
    severity: warn
    check: regex-in-code
    section: "8.1"
    title: Quote attribute values with a colon in the selector
    fix: "In meta[property=og:image] the colon reads as a pseudo-class. Quote the value: meta[property='og:image']."
    snippet: "doc.attr(\"meta[property='og:image']\", \"content\")"
    apply: { find: "[property=og:image]", replace: "[property='og:image']" }
    params: { pattern: '\[(?:property|name|rel|itemprop)=[^''"\]\s]*:[^''"\]]*\]' }
  - id: text-on-element
    severity: error
    check: regex-in-code
    section: "8.1"
    title: text(css) is only on the page, not on an element
    fix: "On an element, text is a string. Find the child first: var c = await el.selectOne(css); c ? c.text : null."
    snippet: "async function subText(el, css) { var c = await el.selectOne(css); return c ? c.text : null; }"
    params: { pattern: '\b(?!doc\b)(?!page\b)(?!html\b)[A-Za-z_]\w*\.text\(\s*["''`]', unless: 'await\s+doc\.|JSON\.|res(ponse)?\.text\(' }
  - id: selector-generated-class
    severity: warn
    check: selector-pattern
    section: "8.1"
    title: A generated class name in a selector
    fix: "Classes like css-1x2y3z change on every build of the site. Pick a parent with a stable class, or an attribute."
    params: { pattern: '\.(css|sc)-[a-z0-9]{4,}' }
  - id: relative-url-returned
    severity: error
    check: result-field
    section: "8.2"
    title: A relative address was returned
    fix: "The app needs https://… for pages, covers and videos. Join with the base: path.startsWith('/') ? BASE + path : require('url').resolve(pageUrl, path)."
    params: { kind: relative-url, field: "coverUrl,imageUrl,videoUrl,webUrl,thumbnailUrl" }
  - id: image-field-server-path
    severity: warn
    check: result-field
    section: "8.2"
    title: An image address is a server path
    fix: "Skip values with // right after the host or a /home/…/wp-content/ path, and fall back to the other field."
    params: { kind: server-path, field: "coverUrl,imageUrl,thumbnailUrl" }
  - id: chapter-url-matches-saved
    severity: error
    check: compare-saved-chapter-urls
    section: "8.2"
    title: Chapter addresses must match what the app saved
    fix: "Print whole numbers as 1.0 like the APK: Number.isInteger(n) ? n + \".0\" : String(n)"
    apply: { find: "String(n)", replace: '(Number.isInteger(n) ? n + ".0" : String(n))' }
    applies: [manga, apk-port]
  - id: referer-on-images
    severity: warn
    check: header-present
    section: "8.2"
    title: Covers or pages need a Referer
    fix: "Set headers: { Referer: BASE + '/' } on the extension object; the app sends them for covers and pages."
    snippet: "headers: { Referer: BASE + \"/\" },"
    params: { header: Referer, on: images }
    applies: [manga]
  - id: status-default-filled
    severity: warn
    check: result-field
    section: "8.3"
    title: A status filled in for every entry
    fix: "Leave status out when the site doesn't say; the app shows it as a badge on every cover."
    params: { kind: same-status }
  - id: date-format-known
    severity: warn
    check: result-field
    section: "8.3"
    title: dateUploaded in a format the app can't read
    fix: "Give an ISO date (2026-10-30T12:00:00Z), a date (2026-10-30) or epoch seconds / milliseconds; a human date reads as 0."
    params: { kind: date-zero }
  - id: cover-in-details
    severity: info
    check: text
    section: "8.3"
    title: Only return coverUrl from getDetails when the list has none
    fix: "A coverUrl from getDetails replaces the list's picture after the title is opened."
    applies: [manga]
  - id: json-setting-placeholder
    severity: info
    check: text
    section: "8.4"
    title: A setting's default doesn't fill a JSON placeholder
    fix: "Write the default into the address and offer the other choices as catalogs, or write the source in JS."
    applies: [json]
  - id: cloudflare-same-page-twice
    severity: info
    check: text
    section: "8.5"
    title: Don't ask a Cloudflare site for the same page twice
    fix: "Read a page once and keep the parsed document; the second request is the one that gets the bot check."
    applies: [cloudflare]
  - id: referer-on-videos
    severity: warn
    check: header-present
    section: "8.6"
    title: Every video needs a Referer
    fix: "Give each video headers: { Referer: embedPageUrl } (or set headers on the extension object); hosters answer 403 without it."
    snippet: "{ url: stream, quality: \"1080p\", server: \"StreamWish\", headers: { Referer: embedUrl } }"
    params: { header: Referer, on: videos }
    applies: [anime, videos]
  - id: render-no-useragent-cf
    severity: warn
    check: render-option
    section: "8.6"
    title: host.render with its own userAgent on a Cloudflare site
    fix: "Leave userAgent out of host.render options: the hidden browser must look like the one that passed the bot check."
    params: { option: userAgent }
  - id: rate-limit-touchy
    severity: warn
    check: response-status
    section: "8.7"
    title: The site answered 429 and the source has no rateLimitMs
    fix: "Add rateLimitMs: 1000 (or more) to the extension object; the runtime spaces the requests."
    snippet: "rateLimitMs: 1500,"
    params: { status: 429, unless: 'rateLimitMs' }
  - id: debug-log-reaches-logs
    severity: info
    check: text
    section: "8.8"
    title: console.log lines reach the Logs screen
    fix: "Take debug prints out before saving; every console line goes to Logs under js:<name>."
  - id: tabs-not-needed
    severity: info
    check: text
    section: "8.8"
    title: Tabs a source doesn't need
    fix: "Only give getCatalogs tabs the site itself has as lists; Popular and Latest already exist."
  - id: servers-not-skipped
    severity: info
    check: text
    section: "8.8"
    title: List each server the site offers
    fix: "Two servers that play the same video are both listed; name videos by what the group heading doesn't say."
    applies: [anime]
---
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

**Writing chapter addresses differently from the APK the source replaces** *(Hiperdex, October 2026)*

- **What you see:** after a refresh (library update, title page, or just previewing a cover), library titles jump around under "Last read" sort, unread badges change (33 → 37), "last read" times are gone, and page previews get cancelled. The log shows the app reading pages for `…/1.0` while the source lists `…/1`.
- **Why:** the library titles were added with the APK, which saved chapter addresses as `/manga/<slug>/1.0` (Kotlin prints a `Float` as `1.0`). The JS port wrote `/manga/<slug>/1`. The app matches chapters by address, so every chapter looked removed + new: the old rows were deleted with their history and new ones inserted. Before r90 nothing protected against this; r90 keeps the chapter (and its history) when only the address changed but the number is the same, but the source should still match.
- **Do this instead:** when porting an APK, copy its `url` format exactly, character for character, including how numbers are printed (`Number.isInteger(n) ? n + ".0" : String(n)` for a Kotlin `Float`), and keep the APK's `id`. Check against a title that's already in the library: the address in a `getPageList` log line must be the same as the one your `getChapterList` produces.

**Two image fields where one is a server path** *(Hiperdex, October 2026)*

- **What you see:** some older titles show no pages; the log has failed requests like `https://i1.r2d2storage.com//home/newdex/en/wp-content/uploads/…/01.jpg`.
- **Why:** the API gives `avifUrl` and `webpUrl`; for titles moved from the old WordPress site one of them is a file path on the server, not a web address. The source (and the APK) took `avifUrl || webpUrl`.
- **Do this instead:** pick the field that is a real web address (skip ones with `//` right after the host or a `/home/…/wp-content/` path), and fall back to the other.

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
