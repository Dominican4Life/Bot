---
version: 16
# Komikku Source Lab rules file v16 (built in from r115; v15 was r105). v16 is short: the 47 checks keep their ids and
# behaviour; 65 of the 81 read-only tips were dropped or merged, because the Site check, the builder's helper and Page
# source now do that looking; the body is a short Help. Your switches, levels and wording live apart from this file in
# sourcelab/rule_overrides.json (the Rules screen), so an update of this file keeps them.
# `check`: regex-in-code, selector-pattern, render-option (the code), header-present, response-status (the site's
# replies in the last Test Run), result-field, compare-saved-chapter-urls (what the source returned), or text (a tip).
# `applies` tags this build computes: manga, anime, js, json, api, madara, cloudflare, apk-port, videos, all. See 11.4.
rules:
  # ---------- checks (ids and behaviour unchanged since v15) ----------
  - id: og-attr-unquoted
    severity: warn
    check: regex-in-code
    section: "1.11"
    title: Quote attribute values with a colon in the selector
    fix: "In meta[property=og:image] the colon reads as a pseudo-class. Quote the value: meta[property='og:image']."
    snippet: "doc.attr(\"meta[property='og:image']\", \"content\")"
    apply: { find: "[property=og:image]", replace: "[property='og:image']" }
    params: { pattern: '\[(?:property|name|rel|itemprop)=[^''"\]\s]*:[^''"\]]*\]' }
  - id: text-on-element
    severity: error
    check: regex-in-code
    section: "1.11"
    title: text(css) is only on the page, not on an element
    fix: "On an element, text is a string. Find the child first: var c = await el.selectOne(css); c ? c.text : null."
    snippet: "async function subText(el, css) { var c = await el.selectOne(css); return c ? c.text : null; }"
    params: { pattern: '\b(?!doc\b)(?!page\b)(?!html\b)[A-Za-z_]\w*\.text\(\s*["''`]', unless: 'await\s+doc\.|JSON\.|res(ponse)?\.text\(' }
  - id: selector-generated-class
    severity: warn
    check: selector-pattern
    section: "1.11"
    title: A generated class name in a selector
    fix: "Classes like css-1x2y3z change on every build of the site. Pick a parent with a stable class, or an attribute."
    params: { pattern: '\.(css|sc)-[a-z0-9]{4,}' }
  - id: relative-url-returned
    severity: error
    check: result-field
    section: "9.1"
    title: A relative address was returned
    fix: "The app needs https://… for pages, covers and videos. Join with the base: path.startsWith('/') ? BASE + path : require('url').resolve(pageUrl, path)."
    params: { kind: relative-url, field: "coverUrl,imageUrl,videoUrl,webUrl,thumbnailUrl" }
  - id: image-field-server-path
    severity: warn
    check: result-field
    section: "5.5"
    title: An image address is a server path
    fix: "Skip values with // right after the host or a /home/…/wp-content/ path, and fall back to the other field."
    params: { kind: server-path, field: "coverUrl,imageUrl,thumbnailUrl" }
  - id: chapter-url-matches-saved
    severity: error
    check: compare-saved-chapter-urls
    section: "4.2"
    title: Chapter addresses must match what the app saved
    fix: "Print whole numbers as 1.0 like the APK: Number.isInteger(n) ? n + \".0\" : String(n)"
    apply: { find: "String(n)", replace: '(Number.isInteger(n) ? n + ".0" : String(n))' }
    applies: [manga, apk-port]
  - id: referer-on-images
    severity: warn
    check: header-present
    section: "5.3"
    title: Covers or pages need a Referer
    fix: "Set headers: { Referer: BASE + '/' } on the extension object; the app sends them for covers and pages."
    snippet: "headers: { Referer: BASE + \"/\" },"
    params: { header: Referer, on: images }
    applies: [manga]
  - id: status-default-filled
    severity: warn
    check: result-field
    section: "3.2"
    title: A status filled in for every entry
    fix: "Leave status out when the site doesn't say; the app shows it as a badge on every cover."
    params: { kind: same-status }
  - id: date-format-known
    severity: warn
    check: result-field
    section: "4.5"
    title: dateUploaded in a format the app can't read
    fix: "Give an ISO date (2026-10-30T12:00:00Z), a date (2026-10-30) or epoch seconds / milliseconds; a human date reads as 0."
    params: { kind: date-zero }
  - id: referer-on-videos
    severity: warn
    check: header-present
    section: "6.5"
    title: Every video needs a Referer
    fix: "Give each video headers: { Referer: embedPageUrl } (or set headers on the extension object); hosters answer 403 without it."
    snippet: "{ url: stream, quality: \"1080p\", server: \"Server 1\", headers: { Referer: embedUrl } }"
    params: { header: Referer, on: videos }
    applies: [anime, videos]
  - id: render-no-useragent-cf
    severity: warn
    check: render-option
    section: "7.3"
    title: host.render with its own userAgent on a Cloudflare site
    fix: "Leave userAgent out of host.render options: the hidden browser must look like the one that passed the bot check."
    params: { option: userAgent }
  - id: rate-limit-touchy
    severity: warn
    check: response-status
    section: "8.1"
    title: The site answered 429 and the source has no rateLimitMs
    fix: "Add rateLimitMs: 1000 (or more) to the extension object; the runtime spaces the requests."
    snippet: "rateLimitMs: 1500,"
    params: { status: 429, unless: 'rateLimitMs' }
  - id: this-not-extension
    severity: error
    check: regex-in-code
    section: "9.2"
    title: this is not the extension object
    fix: "Each method is called on its own, so this.baseUrl is undefined. Write extension.baseUrl (or a BASE constant)."
    snippet: "var BASE = \"https://example.com\"; /* … */ baseUrl: BASE,"
    apply: { find: "this.baseUrl", replace: "extension.baseUrl" }
    params: { pattern: '\bthis\.(?:baseUrl|name|lang|headers|iconUrl|rateLimitMs)\b', unless: 'prototype|\bclass\s' }
    applies: [js]
  - id: settimeout-undefined
    severity: error
    check: regex-in-code
    section: "8.3"
    title: setTimeout and setInterval don't exist in the engine
    fix: "Wait with await host.sleep(ms) (10 s at most per call; loop for longer). There is no timer in the prelude. Inside a host.render script string, setTimeout is the page's and is fine."
    snippet: "await host.sleep(1500);"
    params: { pattern: '\bset(?:Timeout|Interval)\s*\(', unless: 'function\s+set(?:Timeout|Interval)|typeof\s+set(?:Timeout|Interval)|^\s*["''`]|["''`]\s*\+\s*$|\bscript\s*:' }
    applies: [js]
  - id: resolve-leading-slash
    severity: warn
    check: regex-in-code
    section: "9.1"
    title: url.resolve with a path that starts with / keeps both slashes
    fix: "require('url').resolve(BASE + '/', '/slug/') gives https://example.com//slug/ here. For a path starting with /, write BASE + path; keep resolve for ../x.jpg kinds."
    snippet: "function abs(u) { return /^https?:/.test(u) ? u : /^\\/\\//.test(u) ? \"https:\" + u : u.charAt(0) === \"/\" ? BASE + u : require(\"url\").resolve(BASE + \"/\", u); }"
    params: { pattern: '\.resolve\(\s*[^,()]+,\s*["'']/(?!/)' }
    applies: [js]
  - id: user-agent-on-cloudflare
    severity: info
    check: regex-in-code
    section: "7.3"
    title: A User-Agent of your own on a site with a bot check
    fix: "A passed check is tied to the user agent that passed it. The app, WebView and the hidden browser share one; a different one in your headers makes cf_clearance worthless. Take it out unless the site needs a specific one."
    params: { pattern: '["'']?User-Agent["'']?\s*:' }
    applies: [cloudflare]
  - id: accept-encoding-by-hand
    severity: warn
    check: regex-in-code
    section: "8.4"
    title: Don't set Accept-Encoding yourself
    fix: "The app adds gzip and unpacks the reply only when it set the header. Your own Accept-Encoding gives you compressed bytes (and brotli can't be unpacked at all): body looks like garbage."
    params: { pattern: '["'']?Accept-Encoding["'']?\s*:' }
    applies: [js]
  - id: empty-catch-hides-errors
    severity: info
    check: regex-in-code
    section: "11.7"
    title: A catch that swallows the error
    fix: "An empty catch, or one that returns [] / null, hides the bot-check mark the app needs and leaves no line in Logs. Rethrow, or throw new Error with a clear message; return a partial list only after logging why."
    snippet: "catch (e) { console.log(\"chapters: \" + (e && e.message)); throw e; }"
    params: { pattern: 'catch\s*\([^)]*\)\s*\{\s*\}|catch\s*\([^)]*\)\s*\{\s*return\s+(?:\[\]|null|undefined|false|"")\s*;?\s*\}', unless: '^\s*["''`]|["''`]\s*\+\s*$|\bscript\s*:|JSON\.parse' }
    applies: [js]
  - id: status-literal-default
    severity: warn
    check: regex-in-code
    section: "3.2"
    title: A status written as a fixed word
    fix: "status: \"Completed\" on every entry shows a badge on every cover. Map the site's own words, and leave status out when the site has none."
    params: { pattern: '\bstatus\s*:\s*["''](?:Completed|Complete|Ongoing|Finished|Airing|completed|ongoing)["'']\s*,?\s*(?://.*)?$' }
    applies: [js]
  - id: descramble-unsupported
    severity: warn
    check: regex-in-code
    section: "5.6"
    title: descramble isn't supported in this app yet
    fix: "A page with descramble shows still scrambled. Prefer a quality or server the site serves unshuffled (MangaReader's quality=high often is), or leave those chapters out with a clear error."
    params: { pattern: '\bdescramble\s*:' }
    applies: [manga]
  - id: offset-page-times-size
    severity: warn
    check: regex-in-code
    section: "2.2"
    title: offset = page × size skips the first page
    fix: "Pages count from 1 here, so page × 20 starts at item 20 and the 20 most popular are never shown. Use (page - 1) × size, and say so in a comment."
    apply: { find: "page *", replace: "(page - 1) *" }
    params: { pattern: '\boffset\b[^;,]*?\bpage\s*\*', unless: 'page\s*-\s*1' }
    applies: [js]
  - id: eval-in-source
    severity: warn
    check: regex-in-code
    section: "6.1"
    title: eval() of a site's script
    fix: "Packed player scripts (eval(function(p,a,c,k,e,d)…)) are unpacked with require(\"unpacker\").unpackAll(html); then read file: / sources: from the text. Never run a site's code in the engine."
    snippet: "var plain = require(\"unpacker\").unpackAll(html); var m = plain.match(/file\\s*:\\s*[\"']([^\"']+\\.m3u8[^\"']*)/);"
    params: { pattern: '\beval\s*\(', unless: 'unpack|/eval|eval\(function\(p,a,c,k,e,' }
    applies: [js]
  - id: fetch-cache-grows
    severity: info
    check: regex-in-code
    section: "10.2"
    title: fetch's cache option keeps whole bodies in storage.json
    fix: "cache: true stores the ETag and the body under __fetchCache:<url>, and storage.json is rewritten whole on every write. Cache in a variable for the run instead, or store only what you parsed."
    params: { pattern: '\bcache\s*:\s*true' }
    applies: [js]
  - id: selector-position-only
    severity: info
    check: selector-pattern
    section: "1.11"
    title: A selector that only counts positions
    fix: "div > div > div and :nth-child(3) > a break on the first redesign. Name the thing: .chapter-list a, [data-id], a[href*='/chapter/']."
    params: { pattern: ':nth-child\(\d+\)\s*>|(?:div|span|li)\s*>\s*(?:div|span|li)\s*>\s*(?:div|span|li)' }
  - id: selector-bare-link
    severity: info
    check: selector-pattern
    section: "1.11"
    title: A link selector with no container
    fix: "a[href*='/series/'] over the whole page also picks the site's menus (an artist menu that sits on every title). Search inside the title's own block (.summary_content, .post-content) and fall back to the page only when that finds nothing."
    params: { pattern: '^a\[href[*^$~|]?=' }
  - id: chapter-number-missing
    severity: warn
    check: result-field
    section: "4.3"
    title: No chapter or episode has a number
    fix: "Ordering, \"next chapter\" and the r90 address-change protection all read number. Cut it from the name: /(?:Chapter|Ch\\.?|Episode|Ep\\.?)\\s*([\\d.]+)/i, or from the address."
    params: { kind: field-missing, field: number, step: chapters }
  - id: cover-missing-lists
    severity: warn
    check: result-field
    section: "3.4"
    title: No list entry has a cover
    fix: "Check the lazy attributes (data-src, data-lazy-src, data-original, srcset) and background-image styles; a src with a data:image/gif or placeholder means the real one is in a data- attribute."
    params: { kind: field-missing, field: coverUrl, step: popular }
  - id: episode-thumbnail-missing
    severity: info
    check: result-field
    section: "4.4"
    title: Episodes without a thumbnail
    fix: "The episode row shows thumbnailUrl (or previewUrl). Episode cards often have an img; APIs call it thumbnail, image, still or poster. Leave it out only when the site truly has none."
    params: { kind: field-missing, field: thumbnailUrl, step: chapters }
    applies: [anime]
  - id: weburl-missing-api
    severity: info
    check: result-field
    section: "3.5"
    title: An API source without webUrl on the title
    fix: "When url is an API address or an id, WebView, Share and \"Open in browser\" have nothing to open. Set webUrl to the site's page (og:url, link[rel=canonical], or built from the slug)."
    params: { kind: field-missing, field: webUrl, step: title }
    applies: [api]
  - id: chapter-date-missing
    severity: info
    check: result-field
    section: "4.5"
    title: No chapter has a date
    fix: "Library update order and the \"new\" marks use dateUploaded. Sites write it as text (\"2 days ago\", \"Mar 3, 2024\"), a Unix time or an ISO string: require(\"date\").parse(text) reads all of these."
    params: { kind: field-missing, field: dateUploaded, step: chapters }
  - id: video-server-missing
    severity: info
    check: result-field
    section: "6.3"
    title: Videos without a server name
    fix: "Give every video server: \"HD-1\" (set it before hls.expand so every quality keeps it). The player then groups the Qualities list per server, as for an Aniyomi hoster."
    params: { kind: field-missing, field: server, step: hosters }
    applies: [anime]
  - id: relative-url-more-fields
    severity: warn
    check: result-field
    section: "9.1"
    title: A relative address in a secondary picture field
    fix: "previewUrl, seasonCoverUrl, bannerUrl and avatarUrl are fetched like covers: join them with the base too."
    params: { kind: relative-url, field: "previewUrl,seasonCoverUrl,bannerUrl,avatarUrl" }
  - id: video-url-server-path
    severity: warn
    check: result-field
    section: "6.2"
    title: A video address with a doubled slash or a server path
    fix: "https://host//hls/… or /home/…/wp-content/ in a video url means the site gave a path and it was joined twice, or a file path was taken for an address. Pick the field that is a real web address."
    params: { kind: server-path, field: url, step: hosters }
    applies: [anime, videos]
  - id: forbidden-403-seen
    severity: info
    check: response-status
    section: "8.2"
    title: Something answered 403 during the Test Run
    fix: "403 is Referer or Origin (images, videos), a bot check (body says Just a moment / cf-chl / DDoS-Guard), or a header the browser retry dropped. The request line in the Test pane says which address; 7.1 says how to tell them apart."
    params: { status: 403 }
  - id: not-found-404-seen
    severity: info
    check: response-status
    section: "8.2"
    title: Something answered 404 during the Test Run
    fix: "An address pattern is wrong: paging copied from an APK as is (0-based vs 1-based), a slug joined with two slashes, or two test addresses pasted together. Compare with the address the site itself uses."
    params: { status: 404 }
  - id: unavailable-503-no-retry
    severity: warn
    check: response-status
    section: "8.2"
    title: The site answered 503 and the source never retries
    fix: "503 is a bot check or an overloaded site. Use fetch(url, { retry: { attempts: 3 } }) (429 and 503 by default, honours Retry-After), and don't fire the lists in parallel."
    snippet: "var r = await fetch(url, { retry: { attempts: 3, baseDelayMs: 800 } });"
    params: { status: 503, unless: 'retry' }
  - id: unauthorized-401-api
    severity: info
    check: response-status
    section: "7.5"
    title: An API answered 401
    fix: "The app's browser retry can't carry signature or user-agent headers. Send the request yourself from a page of the site with host.render's script and fetch(credentials: \"include\"), or refresh the token."
    params: { status: 401 }
    applies: [api]
  - id: unprocessable-422-csrf
    severity: info
    check: response-status
    section: "7.5"
    title: An API answered 422
    fix: "Usually a missing or empty CSRF token the site now checks. Look in the page's config for a csrf token address, fetch it from the same page, and send its token in x-csrf-token."
    params: { status: 422 }
    applies: [api]
  - id: render-early-ignored
    severity: info
    check: render-option
    section: "7.5"
    title: host.render's early option is ignored in this app
    fix: "Code in early never runs here (7.6 of guide 08). Use script for fetch-only work on a page that doesn't redirect (/robots.txt), and waitForResource plus capture to catch a stream."
    params: { option: early, always: true }
  - id: video-iframe-src-only
    severity: warn
    check: regex-in-code
    section: "6.7"
    title: Videos found only from the iframe's src in the plain page
    fix: "This getVideoList gives up when the site's script fills in the player's address (no src in the HTML). Update the template (Lab editor strip › Update: Videos), or call host.findVideo(url, { frame, referer }) and use found.videos; found.reason says why when it is empty."
    snippet: "var found = await host.findVideo(url, { frame: \"iframe\", referer: BASE + \"/\" });"
    params: { pattern: 'player host not recognised' }
    applies: [js]
  - id: name-global-can-be-empty
    severity: warn
    check: regex-in-code
    section: "2.5"
    title: A global POPULAR_NAME or LATEST_NAME
    fix: "The app reads these globals as the Popular / Latest button names, and an empty text still counts as a name. Put the name on the extension object (popularName: TAB_POPULAR_NAME || undefined) and give the global another name."
    snippet: "var TAB_POPULAR_NAME = \"Trending\"; /* … */ popularName: TAB_POPULAR_NAME || undefined,"
    params: { pattern: '^\s*(?:var|let|const)\s+(?:POPULAR_NAME|LATEST_NAME)\b' }
    applies: [js]
  - id: ad-frame-not-player
    severity: warn
    check: regex-in-code
    section: "6.8"
    title: An ad network's frame used as the video player
    fix: "Frames from ad and traffic networks sit over or beside the player on many video sites; they are ads, never the video. Point the video frame at the real player and skip ad hosts (iframe:not([src*='exoclick'])…, or a list of ad hosts the code leaves out). host.findVideo already skips them."
    snippet: "var AD_HOSTS = /exoclick|exosrv|juicyads|trafficjunky|trafficstars|tsyndicate|magsrv|realsrv|adsterra|popads|doubleclick|googlesyndication/i; frames = frames.filter(function (f) { return !AD_HOSTS.test(f.src || ''); });"
    params: { pattern: '(?:iframe|frame|player|embed|video)[^;]*?(?:pnonolet|pemsrv|juicyads|exoclick|exosrv|trafficjunky|trafficstars|tsyndicate|adsterra|popads|propellerads|magsrv|realsrv|clickadu|hilltopads|adspyglass|ero-advertising|onclickads|a-ads\.com|adnxs|doubleclick|googlesyndication)', unless: 'skip|ignore|block|exclude|AD_HOST|adHost|isAd|notAd|:not\(|\.test\(|!\s*/', flags: i }
    applies: [anime, videos]
  - id: filter-value-is-label
    severity: warn
    check: regex-in-code
    section: "2.6"
    title: A filter option's value looks like a label, not the site's code
    fix: "Sites send codes (most-viewed, multi, recent), not the words they show (Most Viewed). A value with a space, or a capitalised value equal to its label, was copied from the menu and the site answers it with nothing or everything. Pick the option on the site and read the address bar, or use Learn addresses in the builder: it presses the options and keeps the codes. If the site really sends this text, Ignore the rule."
    snippet: "{ label: \"Most Viewed\", value: \"most-viewed\" }"
    params: { pattern: '\bvalue\\?["'']?\s*:\s*\\?["''][^"''\\]*[ ][^"''\\]*\\?["'']|\blabel\\?["'']?\s*:\s*\\?["'']([A-Z][^"''\\]*)\\?["'']\s*,\s*\\?["'']?value\\?["'']?\s*:\s*\\?["'']\1\\?["'']', unless: '^\s*var (TAG_BLOCK|TAG_GROUPS|SETTINGS|SUGGESTION_GROUPS) = |\bshape\s*=|\bvalue\s*:\s*"[~+>] ' }
    applies: [js]
  - id: cover-src-before-lazy
    severity: warn
    check: regex-in-code
    section: "3.6"
    title: A cover read from src before its lazy-load attributes
    fix: "img.attr(\"src\") || img.attr(\"data-src\") stops at the placeholder in src (a data:image/gif, loading.gif, a 1x1 picture), so lazy covers stay blank or all show the same picture. Read data-src, data-lazy-src, data-original, srcset first and src last, skipping data: and placeholder addresses. Suggestions of generic-list v7 did this: Update the template (v8 reads covers through coverOf)."
    snippet: "var src = img.attr(\"data-src\") || img.attr(\"data-lazy-src\") || img.attr(\"data-original\") || img.attr(\"src\") || \"\";"
    params: { pattern: '\.attr\(\s*[^()]*["'']src["'']\s*\)\s*\|\|\s*[\w.]*\.attr\(\s*["'']data-', unless: 'COVER_ATTR' }
    applies: [js]
  - id: converted-part-todo
    severity: warn
    check: regex-in-code
    section: "11.9"
    title: A part Record and convert left as TODO
    fix: "This part only throws. The TODO line above it says why in one line (no data request recorded, the values in no reply, an encoded field, the extension builds the value itself); 11.9 says what to check for each. Record that step again (page 2 too) and use ⋮ › Convert again, or write the part from the recorded requests listed in the comment."
    params: { pattern: 'was not converted from the recording' }
    applies: [js]
  - id: tag-group-whole-row
    severity: info
    check: regex-in-code
    section: "3.7"
    title: A Tags group that reads whole rows or the rows' labels
    fix: "A group whose selector ends in dt, th, tr or a row box (.info-row, .field) gives \"Release date 2026-10-30\" or \"Code, Type, Studio\", never the values alone. In the builder open the source with Edit and press Split into rows on that group (or tap the details block again with Tags armed): one group per row, named after its label, values only."
    params: { pattern: '\\?"selector\\?"\s*:\s*\\?"[^"]*?(?<![\w.-])(?:dt|th|tr|[a-z]+\.(?:[\w-]*[-_])?(?:row|field|meta-item|info-item|detail-item))(?:\.[\w-]+)*\\?"', near: 'TAG_GROUPS' }
    applies: [js]
  - id: value-encoded-twice
    severity: warn
    check: regex-in-code
    section: "2.6"
    title: An address encoded twice (%25 followed by a code)
    fix: "%2520 is a space encoded twice (%20, then its % as %25). A value read from the site's links (a directory's slug) is often already encoded: keep %XX as it is and encode the rest. generic-list v8 does this for a path's value (encodeValue); write the address once, not encodeURIComponent of an encoded text. If the site really sends %25 there, Ignore the rule."
    snippet: "function encodeValue(v) { return String(v).split(/(%[0-9A-Fa-f][0-9A-Fa-f])/).map(function (s, i) { return i % 2 ? s : encodeURIComponent(s); }).join(\"\"); }"
    params: { pattern: '%25[0-9A-Fa-f][0-9A-Fa-f]', unless: 'encodeValue|split\(' }
    applies: [js]
  # ---------- tips (check: text, listed to read when the tags match; v16 kept 16 of 81) ----------
  # the templates' Explain panes link lazy-cover-attrs and stable-chapter-url
  - id: lazy-cover-attrs
    severity: info
    check: text
    section: "3.4"
    title: Covers are often lazy-loaded
    fix: "Read data-src, data-lazy-src, data-original, data-srcset and srcset (first entry) before src, and trim the value; a data: address, a known placeholder file or one picture on every card is not the cover. Page images are lazy the same way (5.2)."
    applies: [manga]
  - id: stable-chapter-url
    severity: info
    check: text
    section: "4.2"
    title: A chapter's url must never change
    fix: "Read state, history and downloads hang off the chapter url. Keep tokens, times and CDN hosts out of it, and keep an APK's format character for character (1.0, not 1)."
  - id: cover-in-details
    severity: info
    check: text
    section: "3.4"
    title: Only return coverUrl from getDetails when the list has none
    fix: "A coverUrl from getDetails replaces the list's picture after the title is opened."
    applies: [manga]
  - id: json-setting-placeholder
    severity: info
    check: text
    section: "2.4"
    title: A setting's default doesn't fill a JSON placeholder
    fix: "Write the default into the address and offer the other choices as catalogs, or write the source in JS."
    applies: [json]
  - id: search-empty-query
    severity: info
    check: text
    section: "2.3"
    title: search gets an empty query when only filters are set
    fix: "Browsing by filters calls search(\"\", page, filters). Send the filter-only address then (emptyQueryUrl in JSON), never a search for an empty word."
  - id: filter-does-nothing
    severity: info
    check: text
    section: "2.4"
    title: Don't ship a setting, filter or tab the site ignores
    fix: "Check that changing it changes the site's answer, and only add tabs for lists the site really has; Popular and Latest already exist."
    applies: [apk-port]
  - id: scanlator-sub-dub
    severity: info
    check: text
    section: "4.4"
    title: Use scanlator for Sub / Dub and for the group
    fix: "Episodes: scanlator is the info line, so \"Sub\", \"Dub\" or \"Sub · Dub\". Chapters: the group's name."
    applies: [anime, manga]
  - id: embeds-use-videolisturl
    severity: info
    check: text
    section: "5.7"
    title: Player embeds in a post use videoListUrl
    fix: "Don't resolve every iframe in getPageList. Return { index, videoListUrl: embedUrl, imageUrl: poster } and resolve in getVideoList when ▶ is tapped; save the source as Manga."
    applies: [manga, videos]
  - id: servers-not-skipped
    severity: info
    check: text
    section: "6.3"
    title: List each server the site offers
    fix: "Two servers that play the same video are both listed; name videos by what the group heading doesn't say."
    applies: [anime]
  - id: hoster-token-expiry
    severity: info
    check: text
    section: "6.6"
    title: Hoster addresses expire
    fix: "Signed stream addresses (a token and an expiry in the query) die in minutes to hours. Resolve them in getVideoList every time; never cache a stream address in host.storage. Downloads re-resolve too."
    applies: [anime, videos]
  - id: keep-apk-id-quoted
    severity: info
    check: text
    section: "9.2"
    title: The APK's id goes in quotes
    fix: "id: \"1234567890123456789\": ids are bigger than a JS number holds exactly. Keep the APK's url formats too, keep name stable, and never change the id."
    applies: [apk-port]
  - id: one-call-at-a-time
    severity: info
    check: text
    section: "10.4"
    title: One call at a time per source
    fix: "A slow getVideoList or a getDetails that fetches five pages holds up Browse paging of the same source. Parse a page once and share it, read in bounded batches, keep partial results, move big reads to getTags."
  - id: test-new-code-really
    severity: info
    check: text
    section: "11.1"
    title: Make sure you're testing the new code
    fix: "After pasting, check something only the new version has before running. Copy test addresses one at a time; two pasted together give a 404."
  - id: debug-log-reaches-logs
    severity: info
    check: text
    section: "11.6"
    title: console.log lines reach the Logs screen (while the global switch is on)
    fix: "Every console line goes to Logs under js:<name> while Settings › Advanced › JS sources: debug logs is on (the default); off, only warnings and errors do. No debug setting of your own needed."
  - id: converted-checked-count
    severity: info
    check: text
    section: "11.9"
    title: Read the "checked" line of each converted part
    fix: "\"checked: 20 of 20 items match the extension's output\". Fewer than about 9 in 10, or a list of the wrong size, means the extension filters, sorts or builds values in its own code; check those items by hand."
    applies: [apk-port]
  - id: checklist-before-saving
    severity: info
    check: text
    section: "12"
    title: Run the checklist in section 12 before saving
    fix: "Lists and page 2, covers, a two-word search, a title, its chapters with numbers and dates, pages or a video that plays, no console errors, Lint clean or ignored on purpose."
---
# Help: writing sources for this app

The short guide behind the checks above. Guide 08 is the API reference (`extension`, `host.*`, the return fields in its §4.3, JSON definitions in §5). Since v16 the Lab does the looking for you: **Site check** (raw vs rendered page, data requests, next page, bot check, JSON in the page), the builder's **helper** (each pick re-checked on 3 titles and on the raw page) and **Page source** (Raw / Rendered / Requests). This file keeps what those tools can't tell you. Addresses use `example.com`-style placeholders.

## 1. How sites are built

### 1.1 Where the data comes from
Prefer, in this order: a JSON API (Site check lists the data requests it saw), JSON in the page (`__NEXT_DATA__`, `__NUXT__`, JSON-LD, `var pages = [...]`), meta tags (`og:title`, `og:image`, keep the quotes), the HTML, and only last `host.render` (7.5). Something in Rendered but not in Raw was built by the site's script: Page source shows it with an "only in Rendered" banner.

### 1.2 Known engines
Fingerprints and templates cover them; read the template's Explain entries before changing one.
- **Madara**: lists `/manga/page/N/` or `admin-ajax.php` (`madara_load_more`, page counts **from 0**); chapters by `POST <title>ajax/chapters/` with `X-Requested-With`; covers and pages lazy (3.4).
- **MangaThemesia**: pages in a script, `ts_reader.run({...})` → `sources[0].images`.
- **MangaReader-style**: chapters and images through `/ajax/...` returning `{ html }`; shuffled tiles can't be shown (5.6).
- **FoolSlide**: pages as `var pages = [...]`; the chapter number is in the address.
- **Keyoapp**: covers in `style="background-image: url(...)"`, pages built from an id; copy the template's form.
- **WordPress REST** (`/wp-json/wp/v2/...`): past the last page it answers **400**, not `[]` (2.2).
- **Next.js**: read `buildId` from `__NEXT_DATA__` on each run, never hard-code it.

### 1.11 Selectors that last
- Meaning over position: `.chapter-list a`, not `div > div:nth-child(3) > a`.
- No generated classes (`css-1x2y3z`, `sc-…`) and no numeric ids (`#post-12345`); use attributes (`a[href*='/manga/']`).
- Quote attribute values with anything but letters, digits, `-` and `_`: `meta[property='og:image']`.
- Search inside the title's or the list's block, not the whole page; menus link genres too.
- On an element `text` is a string; only `doc.text(css)` / `doc.attr(css, name)` take a selector. A child: `var c = await el.selectOne(css); c ? c.text : null`.
- jsoup selectors work (`:contains()`, `:has()`, `:matchesOwn()`); XPath doesn't.

## 2. Lists, paging, search and filters

### 2.1 The list and the item
An entry needs `url` and `title`; entries without `url` are dropped, and more than 20 % without a cover turns the Test Run step amber. The Item is the smallest repeated card that holds the link and the cover (the helper checks it).

### 2.2 Paging traps
- Pages count **from 1** here. An API from 0 takes `page - 1`; an offset API `(page - 1) × size` (`offset = page × size` skips page 1).
- A site that answers page N+1 with page N again pages forever: compare the first `url` with the previous page's, honour `total`, or stop when there is no next link.
- Infinite scroll is a hidden endpoint (Site check lists it); return `[]` when the reply has no cursor. WordPress REST ends with a 400.

### 2.3 Search
Copy the search address from a real search on the site (the builder's Search card records it as `{q}` / `{page}`). **`search` gets an empty query when only filters are set**: send the filter-only address (`emptyQueryUrl` in JSON), never a search for `""`.

### 2.4 Filters, catalogs and tabs
`filters` is `undefined` when nothing is set (`var f = filters || {}`). `tab: true` catalogs replace Popular and Latest; add only lists the site has, and use `popularName` / `latestName` to rename the buttons. Before shipping a setting or filter, check it changes the site's answer. JSON placeholders from settings only fill once the setting is saved: write the default into the address, or write the source in JS.

### 2.5 Names, tabs, filters and settings
The builder's Lists and options card writes these. **Never declare a global `POPULAR_NAME` or `LATEST_NAME` that can be empty**: the app reads them as names. Settings: `getSourcePreferences()`; read `await host.preference.get("key")` once per call.

### 2.6 Filters that pick the list
- **Roles.** `page` filters pick the list (a whole address, or `{value}` in a path; only the first changed one applies); `extra` filters add `param=value` to whichever list is chosen, only when changed. The built-in List follows what Browse was showing (`__from`).
- **Values are the site's codes**, not its labels (`sort=most-viewed`, not "Most Viewed"). Learn addresses reads them; a value with spaces is flagged.
- Dropdowns start with an empty **Site default**; values already percent-encoded aren't encoded again (no `%25`); a red card ("Not in the source yet") is skipped, never sent.
- Directories become autocomplete page filters (`/makers/{value}`); groups keep their children's own keys.
- The Test Run's Filters step loads the list with no filters first; 0 titles there means the filters were learned on an empty page.

## 3. Title pages

### 3.1 Title and description
Trim titles; cut SEO tails (" - Read Online"). Keep paragraphs in the description and append `Alternative titles: …` at the end so library search finds them.

### 3.2 Status
The app reads ongoing / completed / hiatus / cancelled / licensed / upcoming words. Map the site's words in one small table, and **leave `status` out when the site doesn't say**.

### 3.3 Authors, artists, genres
`author` and `artist` are separate; several names joined with `", "`. A genre written `"Group: value"` shows under its group; group all of them or none.

### 3.4 Covers
- Lazy: read `data-src`, `data-lazy-src`, `data-original`, `data-srcset`, `srcset` before `src`, and trim. The helper offers "switch the attribute" when it sees a placeholder.
- A placeholder is a known file name (`blank.gif`, `placeholder.png`, `1x1.gif`, `no-image.jpg`), or the same picture on 3+ cards. `data:`, `.svg` and logos are never covers.
- Only `srcset` is split at commas (`a.jpg 320w, b.jpg 640w`).
- Return `coverUrl` from `getDetails` only when the list has none: it replaces the list's picture.

### 3.5 webUrl and ids
`url` is your key and comes back unchanged. When it isn't the site's page (an API address, an id) set `webUrl` for WebView and Share. Keep one `url` format in lists, search, suggestions and details.

### 3.6 Suggestions (`getRelated`)
Return `{ "Heading": [entries] }` groups in the lists' `url` format. Suggestion covers are lazy too: an `a || b` chain that starts with `src` stops at the placeholder. A section in Rendered only was filled by a later request: the builder marks it **loads later**. `getRelated` runs on every title open: keep it to one or two requests.

### 3.7 Tags from the site's rows
One tap on a details block gives one group per row, named after the label, with the value elements only. Link rows go to Tags, plain rows to the description. Old whole-row or label-list groups get **Split into rows**.

## 4. Chapters and episodes

### 4.1 Where the list comes from
Not in the raw page? It's an AJAX call (Site check / Page source › Requests shows it). Return the site's order; the app sorts by `number` when every row has one.

### 4.2 URL stability
The app matches chapters by `url`: read state, history and downloads hang off it. Keep tokens, times and CDN hosts out of it. An APK port copies the APK's format character for character (Kotlin prints `1.0`: `Number.isInteger(n) ? n + ".0" : String(n)`). Changing the format later loses everyone's progress.

### 4.3 Numbering
Always return `number` (from the name or the address); decimals are real, `volume` is separate. One row without a number turns number sorting off for the whole list.

### 4.4 Scanlators, Sub/Dub, thumbnails
`scanlator` is the group for chapters and the info line for episodes ("Sub", "Dub", "Sub · Dub"). Episodes take `thumbnailUrl`, `description`, `season`, `seasonName`; a title that is its own only episode uses the title's picture.

### 4.5 Dates
`dateUploaded` is ISO text, a date or epoch seconds / ms; **a human date reads as 0**: `require("date").parse(text)` (with a `format` when day/month could swap). A date with no zone is UTC; prefer the API's `date_gmt`.

### 4.6 Duplicates
Two rows with the same number are two chapters when their urls differ; give each its `scanlator`.

## 5. Pages

### 5.1 Finding the images
In the HTML, in a script or from an API. Return `{ index, imageUrl }` from 0; a page needs `imageUrl`, `text`, `videoUrl` or `videoListUrl`.

### 5.2 Lazy pages
Same as covers (3.4), plus `data-url` and `data-cfsrc`; sort by `data-index` when the reader loads out of order.

### 5.3 Referer and hotlinks
Images load with cookies, the app's UA and `baseUrl` as Referer; the source's `headers` win. Blank covers that open in a browser tab: set `headers: { Referer: BASE + "/" }`. Don't HEAD covers yourself; the Lab probes the first page image.

### 5.5 CDN rotation and server paths
Keep a rotating host out of the chapter `url` and pick it in `getPageList`. Skip values with `//` right after the host or a `/home/…/wp-content/` path, and fall back to the other field.

### 5.6 Blank, ad and shuffled pages
Leave out logos, credits and banners (the same address in every chapter). `descramble` isn't supported: prefer the unshuffled quality.

### 5.7 Videos inside chapters
A manga page with `videoUrl` or `videoListUrl` plays in the player. Embeds use `videoListUrl` (resolved when ▶ is tapped), with a poster as `imageUrl`; save the source as Manga.

## 6. Anime: videos

### 6.1 Hosters and extractors
Episode page → server list → embed → player. Fetch each step with `Referer` set to the page before it. Read the embed's scripts first (`.m3u8`, `file:`, `sources:`); unpack packed scripts with `require("unpacker").unpackAll(html)`; never `eval` a site's code. Log each reply's status and start, because hosts change often.

### 6.2 HLS and the relay
Give a master playlist as one `quality: "Auto"` video and `require("hls").expand(video)` for the qualities (set `server` first). Every `.m3u8` goes through the app's relay with the video's headers. A video `url` that isn't `http`, `https` or `//` is dropped.

### 6.3 Qualities and servers
Write `"1080p"`, `"720p"`, `"Auto"`; `server` groups the player's list. **List each server the site offers**, even two that play the same file; read them with `Promise.all`.

### 6.4 Subtitles and audio
`subtitles: [{ url, label, language }]`; a track without a label shows as nothing. Keep `kind: "captions"`, drop thumbnail tracks.

### 6.5 Per-host Referer and Origin
Video hosts check `Referer` (and sometimes `Origin`) of the **embed host**. Put them in each video's `headers`; `Origin` is scheme and host only.

### 6.6 Tokens and expiry
Signed stream addresses die in minutes to hours: resolve them **in `getVideoList` every time**, never store them. Encrypted lists: `host.crypto.aes` (hex or base64 keys, `passphrase` for `Salted__`).

### 6.7 Players filled in by the site's script
An `iframe` with no `src` in Raw (`:src`, `v-bind:src`, `ng-src`) is filled in later: call `host.findVideo(url, { frame, referer })` in `getVideoList`; put its `reason` in your error. It can take about 45 s and is never cached.

### 6.8 Ad frames over the player
Ad and traffic networks' frames are often the largest or first `iframe`: never the video. Narrow the selector to the player's box; `host.findVideo` and the helper skip known ad hosts.

## 7. Bot checks

### 7.1 Recognising one
403/503 with "Just a moment" or `cf-chl` (a challenge page), a DDoS-Guard page, a 429 (8.1), a 403 on media only (hotlink, 5.3), or a 200 "Continue" interstitial (a cookie set with `host.cookies.set`). Site check names the one it met.

### 7.2 What the app does
On a check the request is retried from the hidden browser; once that passes, the site's requests go through it for the session. **Don't catch and hide** the "wants you to pass its bot check" error. Don't fire many requests at once, and read each page once (the second request gets the check).

### 7.3 User agent
A passed check is tied to one UA, shared by the app, WebView and the hidden browser. A `User-Agent` of your own, or `userAgent` on `host.render`, breaks it on a checked site.

### 7.4 Cookies
Cookies are shared with WebView and kept until they expire: you can't unset a site's toggle with your own `Cookie` header; filter in the request instead.

### 7.5 When to use host.render
Only when nothing else gives the data. Capture what you need (`capture: /\.m3u8/`), log `location.href`, run fetch-only scripts on a page that doesn't redirect (`/robots.txt`). `early` is ignored here. An API that answers 401 / 422 after the browser retry lost a token or CSRF header: send it from a `host.render` script.

## 8. Networking

### 8.1 Rate limits and 429
`rateLimitMs` (500–1500 ms on touchy sites) is the gap between requests. Wait out a 429 with growing pauses (`host.sleep` is 10 s at most) and keep what you already read.

### 8.2 Retries and status codes
`host.fetch` doesn't throw on 4xx/5xx: check `statusCode`. `retry: { attempts, statuses }` retries 429 and 503. 403: Referer or a check; 404: a wrong address (paging base, doubled slash); 503: a check or an overloaded site. Throw a clear `Error`.

### 8.3 Timeouts
JS that runs 30 s without awaiting is stopped. There is no `setTimeout` / `setInterval`: use `host.sleep` and `Promise.race`.

### 8.4 Redirects, HEAD, compression
Redirects are followed; `response.url` is the final address. Don't HEAD covers (many CDNs answer 405). **Don't set `Accept-Encoding`**: the app unpacks only what it asked for.

### 8.5 Caching
Keep a page you already have within one run (a module-level cache with a time). Never `cache: true` on `fetch` (10.2) and never cache stream addresses.

## 9. Data hygiene

### 9.1 Full addresses
Pictures, videos, subtitles and `webUrl` must be full `http`/`https` (a `//` start gets `https:`); anything else is dropped. An entry's or chapter's `url` may be a path. `/path` is `BASE + path`: `require("url").resolve(BASE + "/", "/x")` keeps both slashes.

### 9.2 Ids and keys
`id` in quotes (`id: "1234567890123456789"`). **`this` is not the extension**: write `extension.baseUrl` or a `BASE` constant. Calls run at the same time: no shared per-call globals. Keep `name` stable and never change `id`: library, settings and downloads follow them.

### 9.3 Encoding
Decode entities from regex matches (`require("entities").decode`), trim and collapse whitespace, `encodeURIComponent` query words.

### 9.4 Leave out what the site doesn't say
No default `status`, `author`, `rating` or dates; return plain objects.

## 10. Performance and memory

### 10.1 Parsing
`host.parse` keeps the last 24 pages; parse a page once and share it between details, chapters and suggestions.

### 10.2 storage.json
`host.storage` is rewritten whole on every write: tokens, ids and cursors only, never bodies or catalogs (`fetch`'s `cache: true` stores bodies there).

### 10.4 Many requests
**One call at a time per source**: a slow `getDetails` holds up Browse. Read in bounded batches (5 at a time), move big reads to `getTags()`, return partial results rather than failing.

## 11. Testing with the Lab

### 11.1 Test Run
Run all: tabs → Popular → Latest → search → Filters → Settings → details → chapters / episodes → pages / hosters, then the image and video probes; Lint runs after. The console holds every line with times and requests. **Make sure you're testing the new code** before you judge a fix.

### 11.2 Red steps
0 items after a 200: the selector, or the list is built by script (Page source). 403/503: a check or a header. 404: the address pattern. Every date 0: parse it (4.5). Probe 403: Referer (5.3, 6.5).

### 11.4 Rules, checks and your changes
Each check above runs on the code (`regex-in-code`, `selector-pattern`, `render-option`), on what the site answered in the last Test Run (`header-present`, `response-status`) or on what the source returned (`result-field`, `compare-saved-chapter-urls`). `check: text` rules are tips listed to read when their `applies` tags match. On the Rules screen each rule has a switch, a level and its wording; your changes are kept in `sourcelab/rule_overrides.json` (in backups with the Source Lab files), Lint skips a rule that is off, and **Reset to built-in** drops them. Update from file imports a file with a higher `version`; Versions › Roll back returns to an older one. Patterns are ICU regexes in single-quoted YAML: `''` for a quote, no bare braces.

### 11.5 Issue Report
Report › Share builds a redacted zip (source, lint, last runs, requests, console). Send that, not a screenshot.

### 11.6 Debug logs
One switch for every source: Settings › Advanced › JS sources: debug logs (on by default) sends every `console.log` to Logs under `js:<name>`; off, only warnings and errors. No Debug setting of your own needed; slice data to about 1,500 characters.

### 11.7 Errors and catches
An empty `catch`, or one that returns `[]`, hides the bot-check mark and leaves nothing in Logs: log and rethrow, or throw your own clear `Error`. Template helpers log what they couldn't read.

### 11.8 The builder
Read the helper after each pick: ✓ / ! / ✕ / ○ on 3 titles and the raw page, with one-tap fixes. Up / Down move a pick that landed on an overlay or a menu. Next is one link to page 2; Video is the `iframe` or `video`, never an ad frame.

### 11.9 Record and convert
Each written part starts with `// checked: N of M items match the extension's output`; a low N, or a list of the wrong size, gets a TODO saying why. A part left as TODO throws until it is written. Convert again re-records after an app update.

## 12. Checklist before saving
- Popular, Latest and a two-word search give a full page; page 2 differs; covers show.
- Filters change the answer, with the site's codes; no red filter cards.
- A title gives description and genres; `status` only when the site says; `webUrl` right.
- Every chapter or episode has `number` and `dateUploaded`; nothing that changes is in its `url`; an APK port keeps its formats and quoted `id`.
- Pages are full addresses and the first probe is green; each episode has a video that plays, with `server`, `quality` and a Referer.
- The console has no errors; Lint is clean or each finding was ignored or switched off on purpose.
