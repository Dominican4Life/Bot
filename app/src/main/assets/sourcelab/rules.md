---
version: 15
# Komikku Source Lab rules file, v15: the ultimate guide (built in from r105; v14 was r102, v13 r100). New in v15: filters that
# pick the list, codes not labels, groups and directories (2.6), suggestions and sections that load later (3.6), Record and
# convert's self-check and its TODOs (11.9), stalled extension installs (11.10); and from r104 and r105's fix pass: tags
# as the site's rows (3.7), the Suggestions label by section heading (3.6), filters not in the source yet, ▲▼ across
# groups, "Site default", the List following the list browsed, the page-number style, values already encoded, re-made
# filters keeping their settings (2.6), cover placeholders and srcset (3.4), the single episode's thumbnail (4.4),
# template helpers that log (11.7), Record and convert's other threads, app traffic and list sizes (11.9).
# Body: sections 1–12 below, written for this fork.
# Machine rules first; `check` is one of regex-in-code, selector-pattern, result-field,
# compare-saved-chapter-urls, header-present, response-status, render-option, or text (read only).
# `applies` tags this build computes: manga, anime, js, json, api, madara, cloudflare, apk-port, videos, all.
# Other tags (mangathemesia, mangareader, foolslide, keyoapp, wordpress, nextjs, hls, extractors) are for
# reading: a rule with them always also carries one of the computed tags. See 11.4.
rules:
  # ---------- kept from v11 (ids unchanged; sections point at the new body) ----------
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
  - id: cloudflare-same-page-twice
    severity: info
    check: text
    section: "7.2"
    title: Don't ask a Cloudflare site for the same page twice
    fix: "Read a page once and keep the parsed document; the second request is the one that gets the bot check."
    applies: [cloudflare]
  - id: referer-on-videos
    severity: warn
    check: header-present
    section: "6.5"
    title: Every video needs a Referer
    fix: "Give each video headers: { Referer: embedPageUrl } (or set headers on the extension object); hosters answer 403 without it."
    snippet: "{ url: stream, quality: \"1080p\", server: \"StreamWish\", headers: { Referer: embedUrl } }"
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
  - id: debug-log-reaches-logs
    severity: info
    check: text
    section: "11.6"
    title: console.log lines reach the Logs screen (while the global switch is on)
    fix: "Every console line goes to Logs under js:<name> while Settings › Advanced › JS sources: debug logs is on (the default); off, only warnings and errors do. Keep prints short and useful; no need for a debug setting of your own."
  - id: tabs-not-needed
    severity: info
    check: text
    section: "2.4"
    title: Tabs a source doesn't need
    fix: "Only give getCatalogs tabs the site itself has as lists; Popular and Latest already exist."
  - id: servers-not-skipped
    severity: info
    check: text
    section: "6.3"
    title: List each server the site offers
    fix: "Two servers that play the same video are both listed; name videos by what the group heading doesn't say."
    applies: [anime]

  # ---------- new in v12: code checks (regex-in-code) ----------
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

  # ---------- new in v12: selector checks ----------
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

  # ---------- new in v12: Test Run result checks (result-field) ----------
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

  # ---------- new in v12: reply status checks (response-status) ----------
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

  # ---------- new in v12: hidden-browser options (render-option) ----------
  - id: render-early-ignored
    severity: info
    check: render-option
    section: "7.5"
    title: host.render's early option is ignored in this app
    fix: "Code in early never runs here (7.6 of guide 08). Use script for fetch-only work on a page that doesn't redirect (/robots.txt), and waitForResource plus capture to catch a stream."
    params: { option: early, always: true }

  # ---------- new in v13 (r98–r100) ----------
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

  # ---------- new in v14 (r102) ----------
  - id: ad-frame-not-player
    severity: warn
    check: regex-in-code
    section: "6.8"
    title: An ad network's frame used as the video player
    fix: "Frames from ad and traffic networks sit over or beside the player on many video sites; they are ads, never the video. Point the video frame at the real player and skip ad hosts (iframe:not([src*='exoclick'])…, or a list of ad hosts the code leaves out). host.findVideo already skips them."
    snippet: "var AD_HOSTS = /exoclick|exosrv|juicyads|trafficjunky|trafficstars|tsyndicate|magsrv|realsrv|adsterra|popads|doubleclick|googlesyndication/i; frames = frames.filter(function (f) { return !AD_HOSTS.test(f.src || ''); });"
    params: { pattern: '(?:iframe|frame|player|embed|video)[^;]*?(?:pnonolet|pemsrv|juicyads|exoclick|exosrv|trafficjunky|trafficstars|tsyndicate|adsterra|popads|propellerads|magsrv|realsrv|clickadu|hilltopads|adspyglass|ero-advertising|onclickads|a-ads\.com|adnxs|doubleclick|googlesyndication)', unless: 'skip|ignore|block|exclude|AD_HOST|adHost|isAd|notAd|:not\(|\.test\(|!\s*/', flags: i }
    applies: [anime, videos]

  # ---------- new in v15 (r105) ----------
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

  # ---------- new in v15: code checks from r104 and r105's fix pass ----------
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

  # ---------- text rules (read when the tags match) ----------
  - id: madara-ajax-headers
    severity: info
    check: text
    section: "1.2"
    title: Madara AJAX needs X-Requested-With and a form body
    fix: "POST <title page>ajax/chapters/ (or admin-ajax.php with action=manga_get_chapters&manga=<post id>) with X-Requested-With: XMLHttpRequest; the fetch form option sets Content-Type. page= in madara_load_more counts from 0."
    applies: [manga, madara]
  - id: madara-lazy-covers
    severity: info
    check: text
    section: "1.2"
    title: Madara covers are lazy
    fix: "Read data-src, then data-lazy-src, then srcset (last entry), then src. The thumbnail sizes (-193x278, -110x150) can be cut out of the address for the full picture when the site keeps the original."
    applies: [manga, madara]
  - id: mangathemesia-ts-reader
    severity: info
    check: text
    section: "1.3"
    title: MangaThemesia pages live in ts_reader.run
    fix: "Match ts_reader.run\\((\\{.*?\\})\\) in the chapter page's scripts and read sources[0].images; fall back to #readerarea img. The script holds every server in sources[]."
    applies: [manga, mangathemesia]
  - id: mangareader-encrypted-lists
    severity: info
    check: text
    section: "1.4"
    title: MangaReader-style sites hand out chapter and image lists through AJAX
    fix: "/ajax/manga/reading-list/<id> gives HTML inside JSON; the image list is a second AJAX call per chapter, sometimes encrypted with a key in the site's JS, and shuffled pages need descramble (not supported here): take the quality that isn't shuffled."
    applies: [manga, mangareader]
  - id: foolslide-pages-in-script
    severity: info
    check: text
    section: "1.5"
    title: FoolSlide pages are a JSON array in the reader page
    fix: "var pages = [...] in /read/<slug>/<lang>/<vol>/<ch>/ holds every page's url; the chapter number comes from the address segments. Dates are Today / Yesterday / a date."
    applies: [manga, foolslide]
  - id: keyoapp-pages-by-id
    severity: info
    check: text
    section: "1.6"
    title: Keyoapp covers and pages are built from ids
    fix: "Covers sit in style=\"background-image: url(...)\"; #pages > img carry an id attribute the CDN address is built from. Copy the current form from the Keyoapp template; it changes."
    applies: [manga, keyoapp]
  - id: wordpress-rest-paging
    severity: info
    check: text
    section: "1.7"
    title: WordPress REST ends with a 400, not an empty page
    fix: "/wp-json/wp/v2/posts?per_page=20&page=N answers 400 rest_post_invalid_page_number past the last page: return [] on it. per_page is capped at 100; X-WP-TotalPages says how many pages there are."
    applies: [api, wordpress]
  - id: nextdata-buildid
    severity: info
    check: text
    section: "1.8"
    title: Next.js data addresses change with every deploy
    fix: "/_next/data/<buildId>/… stops working when the site deploys. Read buildId from __NEXT_DATA__ on each run (or use the page's own API) rather than hard-coding it."
    applies: [api, nextjs]
  - id: json-api-page-base
    severity: info
    check: text
    section: "2.2"
    title: Check whether the API counts pages from 0 or 1
    fix: "Ask for page 1 and page 2 in a browser and compare the first items. Offset APIs take (page - 1) × size. In JSON definitions use pageStart: 0 and pageSize with {offset}."
    applies: [api]
  - id: repeating-page-ends-list
    severity: info
    check: text
    section: "2.2"
    title: A list that repeats its last page pages forever
    fix: "The app stops when a page adds nothing new, but a site that answers the last page again for page N+1 keeps Browse loading. Compare the first url with the previous page, or stop at the site's own total."
  - id: infinite-scroll-endpoint
    severity: info
    check: text
    section: "2.2"
    title: Infinite scroll means a hidden list endpoint
    fix: "Scroll the page in DevTools with Fetch/XHR filtered and copy the request that loads more; it is the real list address, usually with page=, offset= or a cursor the previous reply gave."
  - id: search-empty-query
    severity: info
    check: text
    section: "2.3"
    title: search gets an empty query when only filters are set
    fix: "Browsing by filters calls search(\"\", page, filters). Send the filter-only address then (emptyQueryUrl in JSON), never a search for an empty word, which many sites answer with everything or nothing."
  - id: filter-does-nothing
    severity: info
    check: text
    section: "2.4"
    title: Don't ship a setting or filter the site ignores
    fix: "Check that changing it changes the site's answer (a Country setting the site really takes from the IP, a family-safe switch that is really a cookie the app keeps sending). Count the site's results with and without before trusting it."
    applies: [apk-port]
  - id: alt-titles-in-description
    severity: info
    check: text
    section: "3.1"
    title: Put alternative titles at the end of the description
    fix: "The entry has no field for them. Append \"\\n\\nAlternative titles: …\" to description so search by another name still finds the title in the library."
  - id: author-vs-artist
    severity: info
    check: text
    section: "3.3"
    title: author and artist are separate fields
    fix: "author is shown; artist is kept too. Several names go in one text joined with \", \". Don't put genres or the status in author because the site lists them in one block."
  - id: srcset-largest
    severity: info
    check: text
    section: "3.4"
    title: srcset holds several sizes
    fix: "a.jpg 300w, b.jpg 600w: take the last (largest) with /([^\\s,]+)\\s+\\d+w\\s*$/, or the first with /^(\\S+)/. A list thumbnail at 110 px looks blurry in the library; og:image on the title page is usually the full one."
  - id: chapter-url-no-token
    severity: info
    check: text
    section: "4.2"
    title: Nothing that changes goes in a chapter url
    fix: "Read state is keyed by the chapter url. A session token, a timestamp or a CDN host in it makes every chapter look new on the next refresh and loses history. Keep the stable part only and build the rest in getPageList."
  - id: chapter-decimals-volumes
    severity: info
    check: text
    section: "4.3"
    title: Decimals, volumes and specials
    fix: "10.5 and 10.1 are real numbers; Vol. 3 Ch. 12 is number 12 with volume 3; Extra / Special / Omake without a number gets no number (one missing number turns number sorting off for the whole list, so then give all a number or none)."
  - id: scanlator-sub-dub
    severity: info
    check: text
    section: "4.4"
    title: Use scanlator for Sub / Dub and for the group
    fix: "Episodes: scanlator is the info line, so \"Sub\", \"Dub\" or \"Sub · Dub\". Chapters: the group's name. Separate dub lists can return both episodes with scanlator telling them apart."
    applies: [anime, manga]
  - id: relative-dates-timezone
    severity: info
    check: text
    section: "4.5"
    title: Relative and zone-less dates
    fix: "\"2 days ago\" is relative to now, fine. A date with no zone is read as UTC; a site in another zone may show one day off. Prefer the API's date_gmt or a Unix time over the page's text."
  - id: duplicate-chapters
    severity: info
    check: text
    section: "4.6"
    title: Duplicate chapters from several groups or servers
    fix: "Two rows with the same number are kept as two chapters (the app matches by url). Give each a distinct url and scanlator, or merge them in the source when the site lists the same scan twice."
  - id: lazy-page-images
    severity: info
    check: text
    section: "5.2"
    title: Page images are lazy too
    fix: "Read data-src, data-lazy-src, data-original, data-url and data-cfsrc before src; trim the value (Madara pads it with tabs and newlines). A src that is a 1×1 gif or loading.gif is the placeholder."
    applies: [manga]
  - id: split-tall-images
    severity: info
    check: text
    section: "5.4"
    title: Tall webtoon strips
    fix: "A 20 000 px strip is one page; the reader splits tall pictures itself (Settings › Reader). Don't split them in the source and don't drop WebP or AVIF: the app decodes both."
    applies: [manga]
  - id: cdn-rotation
    severity: info
    check: text
    section: "5.5"
    title: CDN hosts rotate
    fix: "A site that serves pages from i1…i9 or from a list in its JS hands out a different host each time. Keep the host out of the chapter url and pick it in getPageList; if one host 403s, try the next."
    applies: [manga]
  - id: blank-page-detection
    severity: info
    check: text
    section: "5.6"
    title: Blank, placeholder and ad pages
    fix: "Leave out pictures whose address is the site's logo, a credits page the site adds, a loading.gif or an ad banner (the same address in every chapter). The Test Run shows the first page: look at it."
    applies: [manga]
  - id: embeds-use-videolisturl
    severity: info
    check: text
    section: "5.7"
    title: Player embeds in a post use videoListUrl
    fix: "Don't resolve 26 iframes in getPageList. Return { index, videoListUrl: embedUrl, imageUrl: poster } and resolve in getVideoList when ▶ is tapped; save the source as Manga."
    applies: [manga, videos]
  - id: hosters-iframe-chain
    severity: info
    check: text
    section: "6.1"
    title: Follow the iframe chain with the right Referer
    fix: "Episode page → server list (often an AJAX call with data-id) → embed page → player page. Fetch each with Referer set to the page before it; many hosts answer a blank page without it."
    applies: [anime, extractors]
  - id: hoster-token-expiry
    severity: info
    check: text
    section: "6.6"
    title: Hoster addresses expire
    fix: "DoodStream (pass_md5 + token + expiry), Streamtape, MegaCloud and signed CDN links die in minutes to hours. Resolve them in getVideoList every time; never cache a stream address in host.storage. Downloads re-resolve too."
    applies: [anime, videos]
  - id: megacloud-key-rotation
    severity: info
    check: text
    section: "6.1"
    title: Vidstream / MegaCloud keys rotate
    fix: "getSources answers encrypted sources; the AES key changes often and lives in the player's JS (or a published key list). Fetch the key at run time, keep the last working one in storage with a time, and log the decrypt failure clearly."
    applies: [anime, extractors]
  - id: hls-master-vs-media
    severity: info
    check: text
    section: "6.2"
    title: Master and media playlists are different things
    fix: "A master has #EXT-X-STREAM-INF lines (one per quality); a media playlist has #EXTINF segments. Give the player the master as \"Auto\" and require(\"hls\").expand(video) for one video per quality. Every .m3u8 goes through the app's relay."
    applies: [anime, hls]
  - id: hls-encrypted-segments
    severity: info
    check: text
    section: "6.6"
    title: AES-128 playlists and odd segment hosts
    fix: "#EXT-X-KEY URIs are fetched direct (not through the relay) with the video's headers. Segments on an image CDN or named .png are retried without Referer/Origin and their fake picture header is cut (r67+). A host that still won't play is dropped, not kept broken."
    applies: [anime, hls]
  - id: subtitle-tracks
    severity: info
    check: text
    section: "6.4"
    title: Subtitles need a label and skip thumbnails
    fix: "tracks: [...] from a player mixes kind: captions with kind: thumbnails. Keep captions only, as { url, label, language } with .vtt/.srt/.ass; a track with no label shows as nothing in the player's menu."
    applies: [anime, videos]
  - id: origin-per-host
    severity: info
    check: text
    section: "6.5"
    title: Some hosts want Origin too
    fix: "Origin is the scheme and host only (https://player.example.net, no path or slash). Set it per video next to Referer; a wrong Origin is as bad as a missing Referer. Keep headers for the playlist host; segments elsewhere are handled by the relay."
    applies: [anime, videos]
  - id: ddos-guard-cookies
    severity: info
    check: text
    section: "7.1"
    title: DDoS-Guard and Turnstile aren't Cloudflare
    fix: "DDoS-Guard answers 403 with its own page and sets __ddg1_/__ddg2_ cookies after a JS check; Turnstile is a widget the site embeds and verifies on its own API. Both pass in WebView; neither is passed by a plain fetch. Tell the user to open the source in WebView once."
    applies: [cloudflare]
  - id: cookie-lifetimes
    severity: info
    check: text
    section: "7.4"
    title: Cookies have lifetimes
    fix: "cf_clearance lasts hours and is tied to the UA and IP (a VPN change kills it); __cf_bm about 30 minutes; the app keeps a cookie you set (host.cookies.set) and sends it until it expires: you can't un-set a site's toggle cookie by sending your own Cookie header."
    applies: [cloudflare]
  - id: render-as-last-resort
    severity: info
    check: text
    section: "7.5"
    title: host.render only when nothing else gives the data
    fix: "Try the API, JSON in the page, then the HTML. Render for a check only the browser passes, a player that needs its own scripts, or WebCrypto work (P-256). Capture what you need (a stream address), not a field name; log location.href from the script."
  - id: head-vs-get
    severity: info
    check: text
    section: "8.4"
    title: Don't HEAD covers yourself
    fix: "Many CDNs answer 405 to HEAD. The Lab's probe does HEAD then GET Range: bytes=0-0 for you; a source that tests each cover first costs seconds per list page (6 s on one site)."
  - id: redirect-final-url
    severity: info
    check: text
    section: "8.4"
    title: Read the final address after redirects
    fix: "host.fetch's url (fetch's response.url) is the page you landed on after up to 20 redirects. Use it to learn a title's real path (a /?p=<id> link that redirects to the slug) and to resolve relative links against the right base."
  - id: keep-partial-results
    severity: info
    check: text
    section: "10.4"
    title: Keep what you already read
    fix: "In anything that reads many pages (getTags, catalogs, page lists) pace the requests, wait out a 429 with growing pauses, and return the partial list rather than throwing away 1,800 tags on one failure."
  - id: entities-and-whitespace
    severity: info
    check: text
    section: "9.3"
    title: Decode entities and trim
    fix: "Attribute values from regexes keep &amp; and &#039;: require(\"entities\").decode(text). Trim titles and collapse whitespace (\\s+ to one space, \\u00a0 too); encode query words with encodeURIComponent."
  - id: keep-apk-id-quoted
    severity: info
    check: text
    section: "9.2"
    title: The APK's id goes in quotes
    fix: "id: \"1234567890123456789\": ids are bigger than a JS number holds exactly. Keep the APK's url formats for titles and chapters too, or the library titles saved under that id don't open."
    applies: [apk-port]
  - id: parse-once-share
    severity: info
    check: text
    section: "10.1"
    title: Parse a page once and share it
    fix: "Details, chapters and getRelated usually read the same title page: keep it in a short-lived in-memory cache keyed by url with a time, and pass { html: false } to select when you only need text and attributes."
  - id: large-json-in-memory
    severity: info
    check: text
    section: "10.3"
    title: A whole catalog in memory, not in storage
    fix: "An API that returns every title at once is fine to keep in a module variable with a timestamp for the run; writing it to host.storage rewrites storage.json whole on every set and slows every call."
    applies: [api]
  - id: one-call-at-a-time
    severity: info
    check: text
    section: "10.4"
    title: One call at a time per source
    fix: "The runtime holds a lock per call, so a slow getVideoList or a getDetails that fetches five pages holds up Browse paging of the same source. Keep per-call work small; move big reads to getTags (its own runtime)."
  - id: test-run-red-steps
    severity: info
    check: text
    section: "11.2"
    title: What a red step means
    fix: "Popular red with 200: the item selector or itemsPath found nothing (view-source and count). Title red: a missing url or title field. Chapters red: the list is loaded by AJAX. Pages red with [net] 200: the images are lazy or in a script. Hosters red: a 403 on the stream (Referer) or an expired token."
  - id: issue-report-contents
    severity: info
    check: text
    section: "11.5"
    title: Send the Issue Report, not a screenshot
    fix: "Report › Share zips the code, meta, lint, the last two Test Runs, the failing step's requests, the console and the device line, with cookies, tokens and e-mails removed. Its first line names the failing step and the likely cause."
  - id: test-new-code-really
    severity: info
    check: text
    section: "11.1"
    title: Make sure you're testing the new code
    fix: "After pasting, check something only the new version has (a new setting) before running. Copy test addresses from the Test tab one at a time; two pasted together give a 404."
  # ---------- new in v13: text rules ----------
  - id: video-address-by-script
    severity: info
    check: text
    section: "6.7"
    title: The player's address is filled in by the site's script
    fix: "An iframe with :src, v-bind:src or no src in view-source is set by the page's JS. Don't parse the plain page for it: host.findVideo(url, { frame, referer }) opens the page in the hidden browser, presses Play and catches the .m3u8 / .mpd / .mp4 (up to about 45 s)."
    applies: [anime, videos]
  - id: search-address-from-site
    severity: info
    check: text
    section: "2.3"
    title: Copy the search address from a real search
    fix: "Search the site for two words and copy the address it lands on; a guessed /search?q= often answers 404 or the home page. The builder's Search card records it for you."
  - id: builder-lists-options
    severity: info
    check: text
    section: "2.5"
    title: Names, tabs, filters and settings
    fix: "popularName / latestName rename the two buttons; getCatalogs with tab: true plus getCatalog(id, page) make tabs; getFilters values reach search(query, page, filters) as key → value; getSourcePreferences settings are read with await host.preference.get(\"key\")."
  - id: keep-source-name
    severity: info
    check: text
    section: "9.2"
    title: Keep a source's name stable
    fix: "Download folders are named after the source's name: before r99 a rename left the downloads behind. r99 links them by id, and the id decides which source the library, settings and downloads belong to. Rename only on purpose; never change the id."
  - id: builder-tap-target
    severity: info
    check: text
    section: "11.8"
    title: A tap can land on an invisible link or a menu
    fix: "Full-page overlay links and the site's menus catch taps in the builder. Read the helper's card after each pick and move the pick with Up / Down until it shows a tick."
  - id: builder-item-is-card
    severity: info
    check: text
    section: "11.8"
    title: The Item is the repeated card
    fix: "Item = the smallest element that repeats (3 or more) and holds the title link and the cover; not a heading bar such as New Releases, and not the list's wrapper."
  # the templates' Explain panes link these two ids (missing from v11 and v12)
  - id: lazy-cover-attrs
    severity: info
    check: text
    section: "3.4"
    title: Covers are often lazy-loaded
    fix: "Read data-src, data-lazy-src, data-original, data-srcset and srcset (last entry) before src, and trim the value; a src with data:image/gif or a placeholder picture is not the cover."
    applies: [manga]
  - id: stable-chapter-url
    severity: info
    check: text
    section: "4.2"
    title: A chapter's url must never change
    fix: "Read state, history and downloads hang off the chapter url. Keep tokens, times and CDN hosts out of it, and keep an APK's format character for character (1.0, not 1)."
  - id: checklist-before-saving
    severity: info
    check: text
    section: "12"
    title: Run the checklist in section 12 before saving
    fix: "Lists, page 2, covers, a two-word search, a title, its chapters with numbers and dates, one chapter's pages or one episode's videos that play, no errors in the console, no {placeholder} left, Lint clean or ignored on purpose."
  # ---------- new in v15: text rules (r105) ----------
  - id: filter-roles
    severity: info
    check: text
    section: "2.6"
    title: Page filters pick the list, extras add to it
    fix: "A dropdown that only changes a parameter of the page it is on (type=, year=, sort= on /en/recent) is an extra and works on every list. A menu whose entries open other pages (/en/all?sort=week, a type's own page) is a page filter. Only the first page filter changed from its default applies; the built-in List (Popular, Latest, the tabs) is used when none is."
    applies: [js]
  - id: filter-directory-autocomplete
    severity: info
    check: text
    section: "2.6"
    title: A long list of names is a directory, not a dropdown
    fix: "Genres, people, makers or series on paginated directory pages become one page filter with a text box and suggestions: Fetch the full list reads the pages (with progress and Stop) and keeps name → slug; a name typed that isn't in it is sent as a slug (lowercase, spaces as -)."
    applies: [js]
  - id: filters-empty-base
    severity: info
    check: text
    section: "2.6"
    title: Filters on a page that lists nothing
    fix: "When the Test Run's Filters step says \"filters are on a page with nothing listed\", the filters were recorded on the search page with no words, which many sites answer empty. Learn them on a list page (Latest, a tab) so they are extras of every list."
  - id: search-words-extras-only
    severity: info
    check: text
    section: "2.6"
    title: A search with words only takes the search page's extras
    fix: "Typed words open the search address; page filters and extras never seen on the search page are left out with a log line. Tick \"Also while searching for words\" on an extra only after checking the site's search accepts it."
    applies: [js]
  - id: suggestions-load-later
    severity: info
    check: text
    section: "3.6"
    title: A suggestions section the site's script fills in
    fix: "If the section's cards are in WebView but not in view-source, the page's script fetched them. The builder looks for that request and reads the section from it (\"loads later\"); when it can't find one the card says so. Find the request in DevTools › Network while the section appears, or leave the group out."
    applies: [js]
  - id: converted-checked-count
    severity: info
    check: text
    section: "11.9"
    title: Read the "checked" line of each converted part
    fix: "Record and convert runs each part it wrote on the recorded reply and compares it with what the extension returned: \"checked: 20 of 20 items match the extension's output\". Fewer than about 9 in 10, or a covers count below that, means the extension filters, sorts or builds values in its own code; check those items by hand."
    applies: [apk-port]
  - id: extension-install-stall
    severity: info
    check: text
    section: "11.10"
    title: A batch of extension installs that seems stuck
    fix: "Android sometimes waits for a confirm nobody sees. From r105 a prompt left unanswered gets a \"Tap to approve\" notification, and the app gives up on that one extension after 60 s with no answer from Android (3 min when it is waiting for your confirm), logs it under extensions and goes on with the next; install the skipped one again on its own."
    applies: [apk-port]
  # ---------- new in v15: text rules (r104 and r105's fix pass) ----------
  - id: tag-rows-one-group
    severity: info
    check: text
    section: "3.7"
    title: Tags are the site's rows, one group each
    fix: "With Tags armed, one tap on a details block, a row, a label or a value gives one group per row in page order: the name is the row's label, the values are the value elements only. Rows of links go to Tags, rows of plain text (a code, a date, a duration) to Description. Older whole-row or label-list groups get Split into rows in the Tags card; Undo puts them back."
    applies: [js]
  - id: suggestions-by-heading
    severity: info
    check: text
    section: "3.6"
    title: Tap a card to get its section as a Suggestions group
    fix: "With Suggestions armed, tap one card in a \"You may like\" or \"Related\" section: the group is every card of that section, named after the section's heading (read by its own text, so a \"See all\" link in the heading is fine). Tap a card of each section you want; rename or remove groups in the card."
    applies: [js]
  - id: filter-not-in-source
    severity: info
    check: text
    section: "2.6"
    title: A filter in red is not in the source yet
    fix: "\"Not in the source yet\" means the filter has no parameter and no address (a dropdown of buttons not learned, a filter added by hand). It is skipped with a log line, never sent. Learn addresses, type the parameter name, or pick the menu again; filters made from links keep their links' own page."
    applies: [js]
  - id: filter-site-default
    severity: info
    check: text
    section: "2.6"
    title: Dropdowns start with an empty Site default option
    fix: "The app always sends a dropdown's or sort's picked option, the first when nothing was picked. From r105 a parameter's Sort or Select gets an empty first option, Site default (an empty option the site has is moved first instead): left there nothing is sent, and every real option, the site's first one too, is sent when picked."
    applies: [js]
  - id: filter-list-follows-browse
    severity: info
    check: text
    section: "2.6"
    title: Filters applied on a tab or Popular stay on that list
    fix: "The List filter's default is the list Browse was showing (the app sends it as __from: popular, latest or tab:<path>). Extras applied on Popular or a tab are added to that list; only a changed List or page filter moves to another one. With no list known, Latest."
    applies: [js]
  - id: page-number-style
    severity: info
    check: text
    section: "2.6"
    title: /page/N only after a page word
    fix: "The page number of a page filter's list follows the list paths: ?page=N (any name), /page/N when the word before the number is a page word (page, p, pg, pagina, seite, strona, trang, halaman), else /N at the end. /recent/2 is the recent list's own name plus /N, so another list gets /other/2, not /other/recent/2."
    applies: [js]
  - id: filter-remade-keeps-settings
    severity: info
    check: text
    section: "2.6"
    title: Making a filter again keeps what you set on it
    fix: "Learn again, Fetch again or picking the menu again replaces the filter's options and address but keeps its place, its group, its Also while searching for words switch and a role you chose on the card (a page role only when the new filter has an address)."
    applies: [js]
  - id: cover-placeholders-known
    severity: info
    check: text
    section: "3.4"
    title: Placeholders are known file names, or one picture on every card
    fix: "A cover is skipped when its file name is a placeholder's (blank.gif, spacer.gif, placeholder.png, lazy.png, 1x1.gif, no-image.jpg), not when the name only starts that way. When 3 or more cards all have the same picture it is the site's placeholder: read each card's next attribute. Only srcset attributes are split at commas: a comma inside an address (w_200,h_300) stays."
  - id: single-episode-cover-thumb
    severity: info
    check: text
    section: "4.4"
    title: The only episode's thumbnail is the title's picture
    fix: "A title page that is its own only episode gives the episode the title's picture as thumbnailUrl: the Cover tapped on the title page, else og:image, a video poster, twitter:image (generic-list v7+)."
    applies: [js]
  - id: recorder-other-threads
    severity: info
    check: text
    section: "11.9"
    title: Record and convert counts requests made on other threads
    fix: "From r104 a request made off the step's thread (OkHttp's enqueue, an Rx scheduler) belongs to the step running then. Requests with another site's Referer or Origin and the app's own traffic (extension repos, trackers, GitHub, update checks) are left out; requests counted only because their step was running have their own budget (20, 2 MB per step) and never teach hosts. Logs: Recording <step>: N requests (M started on other threads)."
    applies: [apk-port]
  - id: converted-list-size
    severity: info
    check: text
    section: "11.9"
    title: A converted list must be the right size too
    fix: "From r105 the self-check also compares counts. A paged list must give at least min(returned, 5) items and as many as the reply's best list holds, so a 1-item side array can't pass as 1 of 1. Other lists must give between 0.8 and 1.1 times the returned count: a superset array holding the 20 returned among 140 gets a TODO saying the extension picks the list in its own code."
    applies: [apk-port]
  # ---------- softened in v14: the app's global debug switch replaces a source's own (kept last, read rarely) ----------
  - id: console-log-unguarded
    severity: info
    check: text
    section: "11.6"
    title: console.log needs no guard of its own (the app has a global debug switch)
    fix: "Since r102 the app decides: Settings › Advanced › JS sources: debug logs (on by default) sends every console line to Logs; off, only console.warn / console.error do. Log each step with console.log, use console.warn / console.error for real problems, slice big data to about 1,500 characters. A source's own Debug setting is no longer needed (an old one still works)."
    applies: [js]
---
# The ultimate guide to sources for this app

Everything a JS or JSON source for this fork needs, in the order you meet it when writing one: how the site is built, its lists, its title pages, chapters, pages, videos, bot checks, the network, clean data, speed, testing in the Lab, and a checklist. Guide 08 stays the reference for the API itself (`extension`, `host.*`, the return fields in its §4.3, JSON definitions in §5); this file is the rules and the lessons. The Lint rules in the front matter point at the numbered sections here.

Plain words, one idea per line. Every lesson comes from a real source written for this app (October 2026); sites are described by what they do, not by name, and addresses use `example.com`-style placeholders.

## 1. How sites are built

### 1.1 Find where the data really comes from

A source fails most often because it reads the wrong place, not because the code is wrong. The golden rule: **if something shows in the browser but not in `view-source:`, the page built it with JavaScript**, and the data comes from somewhere else. Look in this order, and stop at the first that has what you need. The higher up, the steadier the source.

1. **A JSON API.** DevTools › Network › Fetch/XHR: `/api/`, `/ajax/`, `/wp-json/`, `graphql`. APIs change less than layouts and give clean fields.
2. **JSON inside the page.** `__NEXT_DATA__`, `window.__NUXT__`, `__INITIAL_STATE__`, `__APOLLO_STATE__`, `self.__next_f.push`, `<script type="application/ld+json">`, `ts_reader.run({...})`, `var pages = [...]`. A regex plus `JSON.parse` in JS, `jsonRegex` in JSON.
3. **Meta tags.** `meta[property='og:title']`, `meta[property='og:image']`, `meta[property='og:description']`, `link[rel=canonical]`. Keep the quotes around `og:…`.
4. **The HTML itself.** CSS selectors (1.11).
5. **Only after the scripts run.** `host.render`. Slow; keep it for the part that needs it (7.5).

The **New source** flow does the first pass for you: it fetches the home page and one title page, compares them with `fingerprints.json`, and fills the engine's template. Read what it wrote; every part has an Explain entry.

### 1.2 Madara (WordPress theme)

The most common manga engine. Markers: `/wp-content/themes/madara`, `wp-manga`, `.page-item-detail`, `.c-tabs-item__content`.

- **Lists.** `GET /manga/page/N/?m_orderby=views` (popular) or `latest`, or the AJAX form: `POST /wp-admin/admin-ajax.php` with `action=madara_load_more`, `template=madara-core/content/content-archive`, `page=N` (**counts from 0**), `vars[orderby]=meta_value_num`, `vars[meta_key]=_wp_manga_views` (popular) or `_latest_update` (latest), `vars[post_type]=wp-manga`. Search is `/?s=words&post_type=wp-manga`.
- **Item.** `div.page-item-detail` (lists) or `div.c-tabs-item__content` (search). Title in `.post-title a`, cover `img` with **lazy** attributes: `data-src`, then `data-lazy-src`, then `srcset` (take the last), then `src`. The value is padded with tabs and newlines: trim it. The thumbnail size (`-193x278`, `-110x150`) can be cut out of the address when the site keeps the original.
- **Title page.** `.summary_content` holds author (`.author-content a`), artist, genres (`.genres-content a`), status (`.post-status .summary-content`, "OnGoing" / "Completed" / "Canceled" / "On Hold"), alternative names (`.summary-heading:contains(Alt) + .summary-content`), description `.description-summary .summary__content` (or `.manga-excerpt`). Search inside `.summary_content`: the menus link genres and artists too (1.11).
- **Chapters.** Not in the page source. `POST <title url>ajax/chapters/` with `X-Requested-With: XMLHttpRequest` (newer sites), else `POST /wp-admin/admin-ajax.php` with `action=manga_get_chapters&manga=<post id>` (id from `#manga-chapters-holder[data-id]` or `.rating-post-id`). Rows `li.wp-manga-chapter`, link `a`, date `.chapter-release-date i` (text like "October 12, 2026", "2 days ago", "yesterday"; a "new" badge instead of a date means today). Newer Keiyoushi APKs save chapters as the slug only (`chapter-10`) with the path in `memo`: give your chapter url the whole path (8.3 of guide 08; 4.2).
- **Pages.** `div.page-break img`, `li.blocks-gallery-item img`, `.reading-content img`; attributes `data-src`, `src`. Some sites put the list in a script as `chapter_preloaded_images`. A `?style=list` on the chapter address lists every page in one go instead of one per page.
- **Cookies.** Mature toggles (a `<site>-mature=1` cookie) are cookies the app keeps sending; filter in the request instead (7.4).

### 1.3 MangaThemesia (WordPress, formerly WPMangaStream)

Markers: `.listupd .bs .bsx`, `#chapterlist`, `ts_reader`. Lists `/manga/?page=N&order=popular` (`update`, `latest`, `title`), search `/?s=words` or `/manga/?title=words`, filters as `genre[]=` and `status=`. Item `.bsx a[title]` (the `title` attribute has the full name), cover lazy as in Madara. Title page: `.infox`, status in `.imptdt:contains(Status) i` or `.tsinfo .imptdt:contains(status)`, alternative names `.alternative` or `.seriestualt`, genres `.mgen a`. Chapters in the page: `#chapterlist li` with `data-num`, `.chapternum`, `.chapterdate` ("Oktober 12, 2026" in the site's language; set the locale or map month names). Pages: **in a script**, `ts_reader.run({...})`: match `ts_reader\.run\((\{.*?\})\)`, `JSON.parse`, read `sources[0].images` (every server is in `sources[]`); fall back to `#readerarea img`. Many MangaThemesia sites put a credits or "read on our site" picture first or last: leave the same address out when it repeats across chapters (5.6).

### 1.4 MangaReader-style sites

Markers: `/filter?`, `/ajax/manga/reading-list/`, `.manga_list-sbs`, `div.iv-card`. Lists `/filter?sort=most-viewed&page=N` (`latest-updated`), search `/search?keyword=words`. Chapter list via `GET /ajax/manga/reading-list/<id>?readingBy=chap` returning `{ "html": "…" }`: parse the HTML in `html`, rows `li.item.reading-item.chapter-item`, number in `data-number`, languages in `#<lang>-chapters`. Pages: a second AJAX call per chapter (`/ajax/image/list/chap/<id>?mode=vertical&quality=high&hozPageSize=1`) with HTML of `div.iv-card[data-url]`; a `shuffled` class marks pages cut into tiles that need `descramble`, which this app can't show: take the quality that isn't shuffled, and say so in the source. Some clones encrypt the list field with a key in the site's JS: find the function in DevTools, port it with `host.crypto.aes` (keys and IV in hex), and log the decrypt failure clearly, since the key changes.

### 1.5 FoolSlide

Markers: `/directory/`, `/read/<slug>/<lang>/`, `div.list div.group`. Lists `/directory/N/` (title order, no popular), latest `/latest/N/`, search `POST /search/` with `search=words`. Title page `/series/<slug>/`: `.comic .info`, each row "Author: …" as text; chapters `div.list div.element`, `div.title a`, date in `div.meta_r` after the group ("Today", "Yesterday", or "2026.10.12"). The chapter number comes from the address: `/read/<slug>/<lang>/<volume>/<chapter>/<sub>/`, so `/1/12/5/` is chapter 12.5 of volume 1. Pages: the reader page holds `var pages = [...]` (a JSON array with `url`), or `/read/…/page/1` with a `<select>` of pages.

### 1.6 Keyoapp

Markers: `/series/`, `div.gridView`, `#pages > img[uid]`. Lists `/latest/?page=N` and `/series/?page=N`, search `/series/?q=words`. Covers in `style="background-image: url(...)"` on the card: read `attr("style")` with `url\(['"]?([^'")]+)`. Chapters `#chapters > a`, date in a `.text-xs` with relative times, number from the name. Pages `#pages > img` carry an id attribute (`uid`) the CDN address is built from (`<cdn>/uploads/<uid>` at the time of writing); the Keyoapp template holds the current form, copy it from there because it moves.

### 1.7 WordPress REST (`/wp-json/`)

Any WordPress site, not only manga themes. `GET /wp-json/wp/v2/posts?per_page=20&page=N&_embed` (`per_page` capped at 100), search `&search=words`, by tag `&tags=<id>`, by category `&categories=<id>`. Past the last page the API answers **400** `rest_post_invalid_page_number`: return `[]` on it, and read `X-WP-TotalPages` if you want the count. With `_embed`, the cover is `_embedded["wp:featuredmedia"][0].source_url` (or `media_details.sizes.full.source_url`), tags and categories are `_embedded["wp:term"]`. `title.rendered` and `content.rendered` are HTML with entities: decode them (9.3). Dates: `date_gmt` is UTC, `date` is the site's zone (4.5). Tags for `getTags()`: `/wp-json/wp/v2/tags?per_page=100&page=N&orderby=count&order=desc`. Custom post types (`wp-manga`) have their own endpoints under `/wp-json/wp/v2/<type>` when the theme exposes them.

### 1.8 Next.js and other JSON in the page

- **Pages router.** `<script id="__NEXT_DATA__" type="application/json">`: `JSON.parse` it, read `props.pageProps`. The same JSON is at `/_next/data/<buildId>/<path>.json`, but **`buildId` changes with every deploy**: read it from `__NEXT_DATA__` on each run, never hard-code it.
- **App router.** `self.__next_f.push([1, "…"])` pieces: join the strings, unescape, and find the object you need with a regex; or look for the site's own API, which the client components call (`?_rsc=` requests are not for you).
- **Nuxt, Vue, Apollo.** `window.__NUXT__`, `__INITIAL_STATE__`, `__APOLLO_STATE__`: often a JS object, not strict JSON (unquoted keys, functions). Match the field you need with a regex rather than parsing the whole thing.
- **JSON-LD.** `<script type="application/ld+json">`: title, image, description, author, dates. Several blocks per page; pick by `@type`.

### 1.9 JSON APIs

Read page 1 and page 2 in a browser before writing code: **does the API count from 0 or 1** (2.2)? Which of `page`, `offset`, `skip`, `cursor`, `after` does it take, and is `limit` capped? Where is the end: an empty array, `hasNext: false`, `total`, a missing `next`? Which headers does it want: `Accept: application/json`, `X-Requested-With`, an `Authorization` key copied from the site's JS (keep it in one constant), signatures (`x-signature`, `x-time`)? GraphQL: copy the query and variables from DevTools; persisted queries need the hash and break when the site changes them. Build `webUrl` from the slug, since the `url` you keep is the API's (3.5).

### 1.10 Cloudflare-fronted sites

A reply with status 403 or 503 whose body has "Just a moment", `cf-chl`, `cf_chl_opt` or a `cf-mitigated: challenge` header is the bot check, not the page. The app retries from a hidden browser page and, once that works, sends that site's requests through the browser for the rest of the session (7.2). What you do: don't fire many requests at once, don't set your own `User-Agent`, don't fetch the same page twice, and prefer the site's API when its HTML is guarded. Also check the image host: it may be behind its own check and want a Referer (5.3).

### 1.11 Selectors that last

- **Meaning over position.** `.chapter-list a` survives a redesign; `div > div:nth-child(3) > a` doesn't.
- **No generated class names** (`css-1x2y3z`, `sc-bdfBwQ`): they change on every build. Use attributes: `[data-testid=title]`, `a[href*='/manga/']`.
- **No numeric ids** (`#post-12345`): they belong to one title.
- **Quote attribute values** that have anything but letters, digits, `-` and `_`: `meta[property='og:image']`. Without quotes the `:` reads as a pseudo-class.
- **Search inside the block** that belongs to the title or the list (`.summary_content`, `.post-content`, `.entry-content`), not the whole page; the menus link genres and artists too.
- **Read attributes, not only text**: `a[title]` holds the full title when the visible text is cut short.
- **jsoup selectors** work: `:contains(Status)`, `:has(img)`, `:eq(0)`, `:matchesOwn(regex)`, `[href~=regex]`. XPath isn't supported by `doc.select`.
- **On an element, `text` is a string**, not a function. Only `doc.text(css)` and `doc.attr(css, name)` take a selector. To read a child: `var c = await el.selectOne(css); c ? c.text : null`.
- **Check every selector copied from an APK** against the site's real addresses (`/manga-genre/` on one copy of a theme, `/comic-category/` on another) and note it in a comment.
- **Count.** Page 1 should give about as many items as the site shows (20, 24, 30, 40). Too many means a slider matched too: make the selector more exact (`div.listupd div.bs`).

## 2. Lists, paging, search and filters

### 2.1 The list page and the item

For Popular, Latest and search find: the address (click page 2 and read the address bar: `?page=2`, `/page/2/`, `&offset=40`), how pages count, the smallest repeated element that holds the link, title and cover of one title (`div.item`, `article`, `li.post`), and where the cover really is (3.4). An entry needs `url` and `title`; entries without `url` are dropped silently, and more than 20 % without a cover turns the Test Run step amber.

### 2.2 Paging traps

- **1-based here.** `getPopular(page)` gets 1, 2, 3… An API that counts from 0 takes `page - 1`; an offset API takes `(page - 1) × size`. `offset = page × 20` skips the 20 most popular (one ported APK did; Mihon counts from 1 too, so the APK itself was wrong). In JSON definitions: `pageStart: 0`, `pageSize` with `{offset}`.
- **A repeating last page pages forever.** The app stops when a page adds nothing new, but a site that answers page N+1 with page N's items again (or redirects to page 1) keeps Browse loading. Compare the first `url` with the previous page's, honour the site's `total`, or stop when the "next" link is gone.
- **Infinite scroll** is a hidden list endpoint. Scroll in DevTools with Fetch/XHR filtered and copy the request that loads more; it has `page=`, `offset=` or a cursor the previous reply gave (`next`, `after`, `cursor`). Return `[]` when the reply has no cursor.
- **Page size differs per list.** Popular may give 24 and search 10. That's fine; don't assume one size for offsets.
- **An empty page ends the list** in the app. When you drop entries after the reply (a filter the API can't do), read on to the next site page when every entry of a page was dropped, and keep that shift for the later pages of the same search (up to 5 pages).
- **WordPress REST** ends with a 400, not an empty list (1.7).

### 2.3 Search endpoints

Search for two words and read the address: `?s=` (WordPress), `?q=`, `?keyword=`, `/search/words`. Spaces as `+` need `{queryPlus}`; slug sites need `{querySlug}`; a POST form needs `method: "POST"` with `form:`. Encode with `encodeURIComponent` in JS. **`search` gets an empty query when only filters are set**: send the filter-only address then (`emptyQueryUrl` in JSON), not a search for `""`, which many sites answer with everything or nothing. A search API that can't exclude (a `genres` parameter with nothing for "not") gets the includes, and you drop the rest after the reply (2.2). Some sites' search is a different engine than their lists (a `/ajax/search` that answers HTML inside JSON, or a quick-search JSON with 5 results): use the full results page when there is one.

**Copy the search address from the site; don't guess it.** Search the site itself for two words and copy the address you land on (or the request in DevTools › Network). A guessed `/search?q=` often answers 404 or quietly gives the home page, which looks like results. The New source builder's **Search** card does this for you: type the word, switch to **Browse**, search on the site, and it records the address with the word as `{q}` and the page number as `{page}` (a site that searches without changing the address can't be recorded there; read its request in DevTools).

### 2.4 Filters, catalogs and tabs

- **Filters** come from `getFilters()` and reach `search(query, page, filters)` as `key → value`: text for a select, a list for multiSelect and autocomplete, `true`/`false` for a checkbox, `0/1/2` for tristate, `{ index, value, ascending }` for sort. Group children arrive under their own keys. `filters` is `undefined` when nothing is set: write `var f = filters || {}`.
- **Autocomplete tag boxes** (`type: "autocomplete"`) suggest from `getTags()` lists; a person can type a tag that isn't in the list, so don't assume every value came from yours. `-tag` means exclude.
- **Catalogs.** Any `getCatalogs` adds a Catalog box to the filter sheet; `tab: true` catalogs replace Popular and Latest with your buttons. When porting an extension, give it the lists it had (`getPopular`, `getLatest`) and no catalogs unless asked. Use `popularName` / `latestName` to rename the two buttons instead of making tabs.
- **Settings that do nothing.** Before porting a setting from an APK, check that changing it changes the site's answer: one site's Country setting really came from the IP; another's family-safe mode was a cookie the app keeps sending (7.4). Count the site's results with and without.
- **JSON placeholders from settings** only get a value once the person saves the setting; the `defaultValue` isn't used. Write the default into the address and offer the others as catalogs, or write the source in JS with `(await host.preference.get("k")) || "default"`.
- **One switch, one job.** Keep what the site itself gives outside any optional-lookup switch (a thumbnails switch once turned off the site's own pictures too).

### 2.5 Names, tabs, filters and settings (what the builder writes)

The New source builder's **Lists and options** card writes these without code; written by hand they look the same. Each is only added when used, so a source without them is unchanged.

- **Names.** `popularName` / `latestName` on the extension object rename the two buttons; left out, the app's names show. Generated code writes `popularName: TAB_POPULAR_NAME || undefined`. **Never declare a global `POPULAR_NAME` or `LATEST_NAME` that can be empty**: the app also reads those two globals as names, and an empty text counts as one (`name-global-can-be-empty`).
- **Which list each button shows.** A list's address with `{page}` where the page number goes (`/trending?page={page}`, `/page/{page}/`); a page without a number in its address gets `page={page}` added, and the Test Run shows whether page 2 really differs (2.2).
- **Tabs.** `getCatalogs()` returns `{ id, name, tab: true }` (the id can be the list's address), and `getCatalog(id, page)` loads it. Tab catalogs replace the Popular and Latest buttons with yours (2.4); add one only for a list the site really has.
- **Filters.** `getFilters()` returns `{ key, name, type: "select", options: [{ label, value }] }`, `{ key, name, type: "checkbox" }`, `{ key, name, type: "text" }` or `{ key, name, type: "autocomplete", options }`, and from r105 (generic-list v8) each has a `role` and may sit in a group (2.6). An extra adds its value to the address as `param=value` (select: its value; checkbox: its "on" value; text when not blank; nothing when left at its default), URL-encoded, with `?` or `&` as the address needs; a page filter replaces the address. The builder offers a filter when two addresses of the same page differ in one query parameter (`sort=`, `genre=`), and reads the codes with **Learn addresses** (2.6). Check that a filter changes the site's answer (2.4).
- **Settings.** `getSourcePreferences()` returns `{ key, title, type: "list" | "switch" | "text", entries, defaultValue }`; the code reads `await host.preference.get("key")` once per call. Ready-made in the builder: preferred quality (`quality`), preferred server (`server`) and "hide titles containing" (`hideWords`, comma-separated); `getVideoList` puts the preferred quality and server first rather than dropping the others.

### 2.6 Filters that pick the list: roles, codes, groups, directories

Many sites put the same dropdowns (Type, Year, Sort…) on every list page and change the address with them: `/en/recent?page=1&type=a&year=2025&sort=recent`, `/en/hot?page=1&type=b&sort=longest`. Their menus also open whole other lists: Trending ▸ This week → `/en/all?sort=week`, a type's own page → `/en/<type>`, a collection's entry → `/en/makers/<slug>`. Since r105 (generic-list v8) a filter says which of the two it is.

- **Roles.** Every filter has a `role`:
  - **List** (built in, first in the sheet): Popular, Latest and the source's tabs; Latest at first. It is the page a filter search browses when no other page filter is changed.
  - **page**: picks which list is browsed. Its option is a whole address (`/en/all?sort=week`, `/en/<type>`) or fills `{value}` in its path (`/en/makers/{value}`). **Only one page filter applies**: the first one changed from its default, in sheet order; the others are left out with a log line.
  - **extra**: adds `param=value` on top of whichever list is chosen (List, a tab or a page filter's list), only when changed from its default, so a page keeps its own sort otherwise.
  - The page number keeps the style learned from the list paths (`?page=N`, `/page/N`, `/N`). Filters made before r105 have no role and keep working: a path filter is a page filter, a parameter filter an extra of the search page.
- **Values are the site's codes, not its labels.** The menu shows "Most Viewed", the address says `sort=most-viewed`; it shows "Two or more", the address says `multi`. A value copied from the label is answered with nothing, or with everything (`filter-value-is-label` flags values with spaces, and capitalised values equal to their label). To find a code by hand: pick the option on the site and read the address bar; do it for two options and compare. The builder's **Learn addresses** does this on dropdowns and `<select>`s in the hidden browser: it picks the options (every one up to 30, else two checked against the option values, data attributes or link addresses), reads the address each gives, and keeps the parameter name and each option's code. It also decides the role: the path changed → page; only a parameter of the list page changed → extra, sent on every list (check it on a second list once, in the Test Run's Filters step or on the site). Menus whose entries are links (a type list, Trending) become page filters whose values are the links' paths.
- **Groups.** A site menu with a heading and sub-menus (Collections ▸ Genres / People / Makers / Series) becomes a group: one heading in the Browse sheet with one filter per sub-menu under it, written `{ type: "group", name: "Collections", filters: [...] }` by `getFilters()`. In the builder each filter card shows its group; ▲▼ reorder inside and across groups; **Move to group…** and **Out of group** change it. Group children still arrive in `search` under their own keys (2.4).
- **Directories.** A sub-menu entry that opens a directory (cards or rows linking to lists of one pattern, `/en/makers/<slug>`, often paginated, sometimes thousands of names) becomes a **page filter of type autocomplete**: a text box with suggestions, `path: "/en/makers/{value}"`. **Fetch the full list** follows the directory's next pages (you choose how many, with progress and Stop) and keeps label → slug, up to 3,000 suggestions. A name typed that isn't in the list is sent as a slug (lowercase, spaces and other signs as `-`) and logged; names starting with `-` are left out (no exclusions).
- **A base page with nothing listed.** The Test Run's Filters step first loads the source with no filters. When that gives 0 titles it warns "filters are on a page with nothing listed": the filters were recorded on the search page with no words, which many sites answer empty. Learn them again on a list page (Latest, a tab) so they become extras of every list.
- **Search with words** uses the search address plus only the extras learned on the search page (or ticked **Also while searching for words**); page filters and other extras are left out with a log line, because the search page doesn't take them.
- **Check it.** Each try in the Filters step shows the filter's role and the address tried (`Sort (extra) = Longest → GET /en/recent?page=1&sort=longest → 24 titles`). An extra that gives the same list as no filter, or 0 titles, has a wrong code or doesn't work on that page.
- **Filters not in the source yet (r104).** A filter with no parameter and no address (a dropdown of buttons not learned yet, a filter added by hand) is shown in red on its card, "Not in the source yet — Learn addresses or type the address name", and is **skipped with a log line**, never silently dropped and never sent. Learn addresses, type the parameter's name, or pick the menu again (`filter-not-in-source`). Filters made from links keep their links' own page (a path filter with an empty first option "Any", so it opens that page only when picked); one made from links before r104 says "pick this menu again". Values put into a path are URL-encoded.
- **Order (▲▼).** The cards' order is the Browse sheet's order, and it decides which page filter wins. Inside a group ▲▼ swap a filter with its neighbour; at the group's edge the next press takes it **out of the group** (it stays where it is); a filter outside groups that moves next to a group **joins it** at that end; the only filter of a group moves past its neighbour with its group kept. A group's filters always stay together under one heading.
- **"Site default" first (r105 fix).** The app always sends a dropdown's or sort's picked option, the first one when nothing was picked, so the site's own first option used to be the same as "nothing". Now a parameter's Sort or Select gets an empty first option **Site default** (an empty option it already has, such as "All", is moved first instead): left there, nothing is sent; every real option, the first one too, is sent when picked (`filter-site-default`). Page filters made from links get "Any" the same way.
- **The List follows what you were browsing (r105 fix).** The app passes the list Browse was showing as `__from` (`popular`, `latest`, `tab:<path>`): applying filters while on a tab or Popular adds the extras to **that** list; only a changed List or page filter moves to another. With nothing known it is Latest (`filter-list-follows-browse`).
- **The page-number style (r105 fix).** A page filter's list takes the page number like the list paths do: `?page=N` (whatever the parameter is called); `/page/N` only when the word before the number is a page word (`page`, `p`, `pg`, `pagina`, `seite`, `strona`, `trang`, `halaman`); otherwise `/N` is added at the end, keeping a trailing slash. A Latest path `/recent/{page}` means "the recent list plus `/N`": another list becomes `/other/2`, never `/other/recent/2` (`page-number-style`).
- **Values already encoded (r105 fix).** A value read from the site's links (a directory's slug such as `caf%C3%A9`) is often already percent-encoded. `%XX` already in it is kept and only the rest is encoded, so it isn't sent as `%25C3%25A9` (`value-encoded-twice` finds `%25` followed by a code in the source).
- **Making a filter again keeps your settings (r105 fix).** Learn again, Fetch again or picking the menu again replaces the options and the address, but keeps the filter's place, its **group**, its **Also while searching for words** switch and a **role you chose** on the card (a page role only when the new one has an address to open) (`filter-remade-keeps-settings`).

## 3. Title pages

### 3.1 Title, description, alternative titles

`title` from `h1`, `og:title`, or the API. `description` from `.summary`, `.synopsis`, `.description`, `[itemprop=description]`, else `og:description` or JSON-LD; HTML tags are taken out, so read the block's HTML and keep paragraphs as blank lines (`htmlToText`). There is no field for alternative titles: append `"\n\nAlternative titles: …"` to the description so the library search by another name finds the title. `rating` (a number) is added to the end of the description by the app. Don't put counts in `title`; use `badge` for Browse-only text such as a post count.

### 3.2 Status mapping

Words the app understands: ongoing, publishing, releasing, airing; complete, completed, finished, ended; hiatus; cancelled, dropped; licensed; upcoming (anime). Anything else is "unknown", which is fine. Map the site's words (`OnGoing`, `En cours`, `連載中`) to one of these in one small table. **Leave `status` out when the site doesn't say.** A fixed `status: "Completed"` on every entry puts a badge on every cover, and the Lint rule `status-default-filled` fires when five or more list entries share one status.

### 3.3 Authors, artists, genres

`author` and `artist` are separate fields; several names go in one text joined with `", "`. Genres are a list of text (a single text also works). Read them inside the title's block, not the whole page (1.11). Don't fold the status or the type ("Manhwa") into the genres unless the site does. Labels and values: for "Status: Ongoing" rows use `div.info-row:contains(Status) span`, not row counting.

**Grouped tags.** A genre written `"Group: value"` (`"Cast: Jane Doe"`, `"Maker: Studio X"`) is shown by the app under its group, like E-Hentai's namespaces: the group's name on the left, its values as chips on the right. Tapping such a tag searches the source with the whole `"Group: value"` (map it to a filter in `search` when a filter has that name and option, else search the value's words); a long press searches every source for the value alone. When you group some genres, group all of them (others as `"Tags: …"`), or the ungrouped ones sit apart. The builder's **Tags** label does this for you (3.7, 11.8): one tap on the details block makes each row (`dt`/`dd`, `th`/`td`, a label and its links) a group in `tagGroups`, sent to the genres, the description, or both.

### 3.4 Covers

Covers are the most common thing that "doesn't load":

1. **Lazy loading.** The real address is in `data-src`, `data-lazy-src`, `data-original`, `data-srcset`, `srcset`, not `src`. A `src` with `data:image/gif` or `placeholder.png` is the placeholder.
2. **`srcset`** holds several sizes (`a.jpg 300w, b.jpg 600w`): take the last with `([^\s,]+)\s+\d+w\s*$`, or the first with `^(\S+)`.
3. **Background images** in `style="background-image: url(...)"`: read the `style` attribute with `url\(['"]?([^'")]+)`.
4. **Thumbnails.** A 110 px list thumbnail is blurry in the library. `meta[property='og:image']` on the title page is usually the full picture: return it as `coverUrl` from `getDetails` **only when the list has none or the original extension does the same**; a `coverUrl` from details replaces the list's picture after the title is opened, and in Panorama it can change shape.
5. **Relative addresses** must become full ones (9.1). `//cdn/x.jpg` gets `https:`.
6. **Hotlink protection and image-host bot checks**: 5.3.
7. **What counts as a placeholder (r105 fix).** A picture is a placeholder when its **whole file name** is a known one (`blank.gif`, `spacer.gif`, `placeholder.png`, `lazy.png`, `loading.gif`, `1x1.gif`, `no-image.jpg`, with an optional `-min` or size suffix), not when the name only starts that way (`pixel-art-cover.jpg` is a cover). When **3 or more cards all have the same picture**, that picture is the site's placeholder: each card's next attribute is read instead (generic-list v8 logs "every card has the picture …: a placeholder, left out"). `data:`, `about:blank`, `.svg`, favicons and logos are never covers (`cover-placeholders-known`).
8. **Only srcset is split at commas (r105 fix).** `srcset` and `data-srcset` give their first entry (`a.jpg 320w, b.jpg 640w` → `a.jpg`). Any other attribute keeps commas inside the address (an image service's `w_200,h_300` path), so it isn't cut in half.

### 3.5 webUrl and ids

An entry's `url` is your key: whatever your code needs to find the title again, a path, an id or an API address; the app hands it back unchanged. **When it isn't the site's page, set `webUrl`** (`og:url`, `link[rel=canonical]`, or built from the slug): it is what WebView, Share and "Open in browser" open; without it the app opens `url` when it's a full address, else the site's base. Keep the same `url` format in lists, search, `getRelated` and details, or the same title shows twice in the library. When porting an APK keep its `url` format character for character (4.2).

### 3.6 Suggestions (`getRelated`)

- **Named groups.** `getRelated(url)` may return `{ "You may like": [...], "Related": [...] }`: the title screen shows one row per name, in order. The builder's **Suggestions** label makes one group per section you tap on the title page (generic-list v7+); give each the site's own heading. Entries are list entries (`url`, `title`, `coverUrl`) in the same `url` format as the lists (3.5); the title itself and repeats are left out. Since r105 the anime title screen also shows one named row per group, and the full Suggestions page is split into the same sections.
- **The Suggestions label (r104).** Like Tags it stays armed. Tap **one card** in a section: the group is every card of that section, **named after the section's heading**. The heading is found by its own text (`:matchesOwn`), so a "See all" link inside the heading doesn't stop it. Tap a card of each section you want; rename or remove groups in the Suggestions card (`suggestions-by-heading`).
- **Covers are lazy here too.** Suggestion rows load late on most sites, so their `img` usually has a placeholder in `src` and the picture in `data-src`, `data-lazy-src`, `data-original` or `srcset`. Read those first and `src` last, and skip `data:` addresses and placeholder files (`placeholder`, `blank`, `lazy`, `loading`, `1x1`, `.svg`). An `a || b` chain that starts with `src` stops at the placeholder (`cover-src-before-lazy`); generic-list v8's `coverOf` reads them in the right order for lists and suggestions (3.4).
- **Sections that load later.** A section whose cards are in WebView but not in `view-source:` was filled by the site's script after the page loaded, from a request of its own (HTML, or JSON holding HTML). The source only reads the page, so it finds nothing. After a Suggestions pick the builder reads the raw page; when the cards aren't there it looks through the requests the page made and keeps the one whose reply has them. The group is marked **loads later**, and its address becomes a template filled per title (`{seg1}`… the title address's parts, `{path}`, `{url}`, `{attr:name}` a value from the page). When no request has the cards the card warns that the section is filled by the site's script and isn't in the page: find the request in DevTools › Network while the section appears and put it in by hand, or leave the group out.
- **Keep it cheap.** `getRelated` runs every time a title opens: one or two requests, the page already read for details when you can (10.1).

### 3.7 Tags from the site's rows (the builder)

Title pages often have a details block of label / value rows: `Release date 2026-10-30`, `Studio Studio X`, `Genres Drama, Slice of Life`. The app shows grouped tags (3.3) like the site shows these rows.

- **One tap, every row (r104).** With **Tags** armed, one tap on the details block, on a row, on a label or on a value gives **one group per row, in page order**. A details block is a `dl` (`dt` / `dd`), a `table` (`th` or a short first `td`, then the next cell), or a box of 2 or more rows whose first child is a short label and the next the value. Rows with a picture are not rows.
- **Name and values.** A group's **name is the row's label** (without its colon); its **values are the value elements only**, never the label. The selector is anchored on the label's text (`dt:matches(^\s*Studio) + dd a`), so it finds the same row on every title even when rows are missing or move.
- **Where each row goes.** A row of **links** goes to **Tags** (`"Studio: Studio X"` genres, tappable). A row of **plain text** (a code, a date, a duration) goes to **Description** as a line under it (`Release date: 2026-10-30`). Change a group's target to Tags, Description or both in the Tags card.
- **Re-tapping** the block adds only the rows still missing; Undo removes that tap's rows.
- **Older groups.** Groups made before r104 may read **whole rows** (`… > div.info-row`, `tr`, giving "Release date 2026-10-30") or **the labels** (`… > dt`, `th`, giving "Code, Type, Studio"). In Edit (11.8) such a group gets **Split into rows** in the Tags card: the rows of its block replace it, one group each; Undo restores the old groups exactly. Lint's `tag-group-whole-row` finds these selectors in `TAG_GROUPS`.
- A row class is matched as a whole word (`.row`, `.info-row`, `.meta-item`), never inside another word (`.arrow`, `.browse-list`).

## 4. Chapters and episodes

### 4.1 Where the list comes from

The second most common failure: many sites load the list separately. Not in the page source? Then it's `POST <title>ajax/chapters/` (Madara), `admin-ajax.php` with `action=`, `/ajax/manga/reading-list/<id>` (MangaReader), `/api/manga/<id>/chapters`, or a list split over pages (`pagination` with the next link in JSON; loop in JS until a page adds nothing). The id it needs is on the title page (`data-id`, `data-post`, `manga_id` in a script): read it with a `resolve` step or one fetch, and cache that page (10.1). Return chapters in the site's own order; the app sorts by `number` when every chapter has one, else by the dates, else keeps your order.

### 4.2 URL stability: the `1.0` lesson

The app matches chapters by `url`. Read state, history, downloads and "last read" hang off it.

- **Porting an APK: copy its `url` format exactly**, including how numbers print. Kotlin prints a `Float` as `1.0`, so the APK saved `/manga/<slug>/1.0` and the JS wrote `/manga/<slug>/1`: every chapter looked removed and new, history was lost, unread badges jumped (33 → 37). Write `Number.isInteger(n) ? n + ".0" : String(n)`. From r90 the app keeps a chapter whose number is the same when only the address changed, but the source should still match. Check against a title already in the library: the address in a `getPageList` log line must equal the one `getChapterList` produces. The Lint rule `chapter-url-matches-saved` compares them for you when the tested title is in the library.
- **Nothing that changes goes in the url**: no session token, timestamp, CDN host or `?t=`. Keep the stable part and build the rest in `getPageList`.
- **Keiyoushi Madara's `memo` trick.** The APK saves `chapter-10` and keeps the title's path in a field you never get. Give chapters a url with the title in it (`/serie/<title>/<chapter>/`) and keep the APK's title url.
- **Changing a url format later** means every reader loses progress. If you must, do it once and say so in the source's notes.

### 4.3 Numbering

Always try to return `number`: ordering, "next chapter" and the r90 protection read it. Cut it out of the name with `/(?:Chapter|Ch\.?|Episode|Ep\.?)\s*([\d.]+)/i`, or from the address. Decimals are real (`10.5`, `10.1`); `Vol. 3 Ch. 12` is number 12 with `volume: 3` (shown as "Vol. 3"); "Extra", "Special", "Omake" without a number get none. **One chapter without a number turns number sorting off for the whole list**, so give all a number or accept date order. Episodes: the same; `season` / `seasonName` label episodes when a title lists several seasons ("S2 · Episode 4"). Two chapters with the same number from different groups are fine when their urls differ (4.6).

### 4.4 Scanlators, Sub/Dub, seasons

`scanlator` is the group's name for a chapter and the episode's info line for anime: put **"Sub"**, **"Dub"** or **"Sub · Dub"** there. Sites with separate dub lists can return both episodes, with `scanlator` telling them apart. Episodes also take `thumbnailUrl` (a 16:9 picture on the row; cards have an `img`, APIs call it `thumbnail`, `image`, `still`, `poster`), `description` (the summary), `season`, `seasonName`, `seasonCoverUrl`. Anime databases (Jikan, Kitsu, AniList, TMDB) have thumbnails and summaries by title when the site has none, but keep those lookups behind a setting and bound them (10.4).

**A title that is its own only episode** (the builder's single-episode switch: one video per title page, no list) gives that episode the **title's picture** as `thumbnailUrl` (generic-list v7+): the Cover tapped on the title page (its picked attribute, then `data-src`, `src`, `poster`), else `og:image`, a `video[poster]`, then `twitter:image`. A failure to read it is logged, and the episode is still returned (`single-episode-cover-thumb`).

### 4.5 Dates and timezones

`dateUploaded` is ISO 8601 text (`2026-10-30T12:00:00Z`), a date (`2026-10-30`) or epoch seconds / milliseconds. **A human date reads as 0** (the `date-format-known` rule), so parse it: `require("date").parse(text)` reads month names, day/month in either order, two-digit years, Unix numbers and "3 hours ago"; give a `format` such as `"dd/MM/yyyy"` when numbers could be read either way. A date with no zone is read as UTC; a site in another zone shows one day off on some chapters; prefer the API's `date_gmt` or a Unix time over the page's text. Relative dates ("2 days ago", "yesterday", "just now") are relative to now; a "new" badge instead of a date means today. Month names in other languages need a small table.

### 4.6 Duplicates and order

Two rows with the same number are two chapters when their urls differ (the app matches by url): keep them when they are different scans (give each its `scanlator`), merge them when the site lists one scan twice. Return the list in the site's order; most list newest first and the app works it out. Episode lists: "oldest upload first" or whatever the site does, with numbers. Don't drop "duplicate" servers in anime (6.3).

## 5. Pages

### 5.1 Finding the images

In the HTML (`div.reading-content img`, `#readerarea img`, `.page-break img`), in a script (`ts_reader.run`, `var chapImages`, `var pages = [...]`, `__NEXT_DATA__`), or from an API (`/api/chapter/<id>` with `images: [...]`). Return `{ index, imageUrl }` with `index` from 0 in the chapter's order; the app sorts by index and re-indexes. A page needs `imageUrl`, `text`, `videoUrl` or `videoListUrl`; one with none is skipped.

### 5.2 Lazy attributes and placeholders

Page images are lazy too: read `data-src`, `data-lazy-src`, `data-original`, `data-url`, `data-cfsrc` before `src`, and trim (Madara pads with tabs and newlines). A `src` that is a 1×1 gif, `loading.gif` or a `data:` address is the placeholder. Some readers have `data-index` and load pages out of DOM order: sort by that attribute.

### 5.3 Referer and hotlink rules

Covers and pages load through the app's own connection with cookies, Cloudflare handling, a browser User-Agent and the source's `baseUrl` as `Referer`; the source's `headers` are added on top and win. So most image servers just work. When covers stay blank but the address opens in a browser tab, the image host checks the `Referer`: set `headers: { Referer: BASE + "/" }` on the extension object, the page the images normally load from. One image host needed a mobile Chrome `User-Agent` there too; on another site, its own-domain copy of the picture worked where the CDN's didn't. **Don't fetch each cover first to test it** (6 s per list page). If covers worked and stopped suddenly, try without the VPN before changing code. The Lab probes the first page image with HEAD (then GET Range on 405); 403 with no Referer in the source fires `referer-on-images`.

### 5.4 Formats: WebP, AVIF, tall strips

The app decodes JPEG, PNG, GIF, WebP and AVIF; don't filter formats. A 20 000 px webtoon strip is one page; the reader splits tall pictures itself. When an API offers both `webpUrl` and `avifUrl`, either is fine, but check both are web addresses (5.5). Animated pages (GIF, animated WebP) play in the reader but show as stills in previews.

### 5.5 CDN rotation and server paths

Sites that serve pages from `i1…i9` or from a host list in their JS hand out a different host each time: keep the host out of the chapter `url`, pick it in `getPageList`, and when one host 403s try the next. **Server paths.** One site's API gives `avifUrl` and `webpUrl`, and for titles moved from its old site one of them is a file path (`https://cdn.example.com//home/<site>/en/wp-content/uploads/…`): skip values with `//` right after the host or a `/home/…/wp-content/` path and fall back to the other field (`image-field-server-path`).

### 5.6 Blank, placeholder and ad pages

Leave out pictures that aren't pages: the site's logo, a credits picture, "read on our site" banners, `loading.gif`, ad images. They have the same address in every chapter, so compare across two chapters while writing the source. `descramble` (shuffled tiles) isn't supported here: the page shows scrambled, so prefer the unshuffled quality or server (1.4). The Test Run shows the first page: look at it.

### 5.7 Videos inside chapters

A **manga** source can mix pictures and clips in one chapter: a page with `videoUrl` (the stream) or `videoListUrl` (an embed your `getVideoList` resolves when ▶ is tapped) plays in the app's player. **Embeds use `videoListUrl`**: a post with 26 iframes must not resolve them in `getPageList`. Give video pages a picture (`imageUrl`: the player's poster, `og:image`), headers, and keep the post's own order. Save the source as **Manga** in the Details panel; a source with both `getPageList` and `getVideoList` saved as Anime loses `getRelated` and the APK's library. Downloads save the clip next to its picture and re-resolve `videoListUrl` at download time.

## 6. Anime: videos

### 6.1 Hosters and extractors

Most time in an anime source goes here. The chain: episode page → server list (often AJAX: `/ajax/episode/servers?episodeId=` → `data-id`, then `/ajax/episode/sources?id=` → `{ type: "iframe", link }`) → embed page → player page. **Fetch each step with `Referer` set to the page before it**; many hosts answer a blank page or a redirect to their front page without it. Read the embed page's scripts before reaching for the hidden browser: `.m3u8`, `.mp4`, `file:`, `sources:`, `jwplayer(`. Packed scripts (`eval(function(p,a,c,k,e,d)`) are unpacked with `require("unpacker").unpackAll(html)`; never `eval` a site's code. Copy the current steps from Aniyomi's extractors (`lib/<name>-extractor` in aniyomi-extensions) and log every reply's status and the start of its body, because hosts change monthly.

| Host (and clones) | How the stream is found | Headers the stream wants |
| --- | --- | --- |
| **StreamWish** (embedwish, swhoi, wishfast, strwish, dwish, awish, mwish, VidHide, Lulustream) | `/e/<id>`: a packed script with `sources: [{ file: "…m3u8" }]`; unpack, match `file`. Sometimes `links.hls2`. | `Referer: https://<embed host>/`, sometimes `Origin` |
| **Filemoon** | Older: `/e/<id>` packed script with `file:"…m3u8"`; the page may iframe a second player domain first. Newer "Byse" player: `/api/videos/<id>/embed/details`, then a P-256 challenge, proof-of-work and encrypted playback, which only runs in `host.render`'s script on the player's own page (guide 08 §8.6). Subtitles from `?sub.info=` in the iframe address. | `Referer` the player's own address; `X-Embed-*` headers for the new API |
| **Vidstream / MegaCloud** (and its clones) | `/embed-2/ajax/e-1/getSources?id=<id>` (or `/embed-2/v2/e-1/getSources`): `{ sources, tracks, encrypted }`. When encrypted, the AES key **rotates**: it lives in the player's JS (or a published key list). Fetch it at run time, keep the last working key in storage with a time, decrypt (CryptoJS `Salted__`: `passphrase`). `tracks` with `kind: captions` are subtitles. | `Referer: https://<player host>/`, `Origin` the same host, `X-Requested-With: XMLHttpRequest` for the API |
| **Mp4Upload** | `/embed-<id>.html`: a packed script with `src: "…mp4"` (or `player.src({ src })`). A plain MP4. | `Referer: https://<embed host>/` |
| **DoodStream** (dood.*, ds2play, d0o0d, doply, vidply…) | `/e/<id>`: the page has `$.get('/pass_md5/<path>'`. GET `https://<host>/pass_md5/<path>` with `Referer` the embed page → a base address. Stream = base + 10 random letters + `?token=<token from the page>&expiry=<Date.now()>`. **Expires in minutes**: resolve at play time (6.6). | `Referer: https://<host>/` |
| **Streamtape** | Two script pieces concatenated into `//<host>/get_video?id=…&expires=…&token=…`; follow the redirect. Expires. | `Referer` the embed |
| **Voe** | Redirects to a new domain often; `'hls': '<base64>'` or `var sources = {...}` in the page. | `Referer` the embed |
| **Okru** | `data-options` attribute (HTML-escaped JSON) → `metadata.hlsManifestUrl` / `videos[]`. | none special |
| **Vidmoly, Uqload, Sendvid, Mixdrop** | `sources: [...]` or `MDCore.wurl` in a packed script (Mixdrop addresses start with `//`). Segments 403 without the Referer. | `Referer: https://<host>/` |
| **Keyed packed players** | Episode buttons with `data-src`, resolution and audio lead to a player page whose script is packed with a key; unpack it and read the source. | `Referer: https://<player host>/` |
| **JW Player pages** | After the scripts run, `jwplayer().getPlaylist()[0].file` holds the stream, even when obfuscated code decrypted it: read it in a `host.render` script; no play press needed. | the page's host |
| **Players that load after play** | The stream comes from an API only after a press. `host.findVideo` presses Play for you (6.7); by hand, give `host.render` a `script` that clicks `.vjs-big-play-button` / `.jw-icon-display` / `.plyr__control--overlaid`, calls `video.play()`, waits and returns `performance.getEntriesByType("resource")` names and `video.currentSrc`. Check `location.href`: a desktop UA may get a "no video" page, so pass a phone `userAgent` (not on Cloudflare sites, 7.3). The hidden browser sends no Referer, so a player that checks it can't be rendered directly. | as the player did |

Test each server on its own with a debug setting that tries every server and logs each step. Drop a server that can't play rather than keep a broken choice.

### 6.2 HLS: master vs media playlists, and the app's relay

A **master** playlist has `#EXT-X-STREAM-INF:BANDWIDTH=…,RESOLUTION=1920x1080` lines, each followed by a quality's address (relative to the master's address). A **media** playlist has `#EXTINF` segments. Give the master as one video with `quality: "Auto"`, and `require("hls").expand(video)` for one video per quality on top (`isMaster(text)`, `parseMaster(text, baseUrl)` for the pieces); `expand` gives every quality the video's `server`, so set it before. **Every `.m3u8` goes through the app's `HlsRelay`** (`http://127.0.0.1:<port>/p/<id>?u=…&x=list.m3u8`): it fetches the playlist and segments with the video's headers, rewrites addresses, cuts fake picture headers off segments, and from r67 retries a segment without `Referer`/`Origin` when another host refuses it. Keys (`#EXT-X-KEY`) go direct with the video's headers. Anything that inspects a video address must look for `m3u8` anywhere, not at the end. DASH (`.mpd`) and plain MP4 play directly. A video `url` that isn't `http`, `https` or `//` is dropped silently (`video-url-server-path` catches doubled slashes).

### 6.3 Quality labels and server groups

`quality` is the text in the Qualities list; the `(\d{3,4})p` in it is the resolution the player sorts by, so write `"1080p"`, `"720p"`, `"Auto"`. `server` (or `hoster`) names the host: with it, the full-screen player shows one group per server, in the order servers first appear in your list; videos without a server go to "Other"; when none has one the list is flat. The `quality` is shown in full under the group, so `"Sub - 1080p"` under "HD-1", not `"SUB - HD-1 - 1080p"`. **List each server the site offers**, even when two play the same file (a source once lost servers by de-duplicating); read their pages at the same time with `Promise.all`. Leave out an "Auto" copy unless it is really different. Grouping is the app's (beta r11+); older builds show a flat list whatever you do.

### 6.4 Subtitle and audio tracks

`subtitles: [{ url, label, language }]` and `audioTracks` the same shape; `url` is required and `label` is what the menu shows, so a track with no label shows as nothing. Player `tracks: [...]` mix `kind: "captions"` with `kind: "thumbnails"` (preview strips): keep captions only. Formats `.vtt`, `.srt`, `.ass`; `require("subtitles")` converts. Master playlists carry `#EXT-X-MEDIA:TYPE=SUBTITLES` and `TYPE=AUDIO`; `parseMaster` lists them. Filemoon's subtitles are a JSON list at the `sub.info` address in the iframe's query.

### 6.5 Per-host Referer and Origin

Video servers almost always check `Referer` and sometimes `Origin`, set to the **embed host**, not the main site. Put them in each video's `headers` (the player uses the video's headers first, then the source's, then `Referer: baseUrl/`). `Origin` is scheme and host only: `https://player.example.net`, no path, no trailing slash; a wrong Origin is as bad as a missing Referer. A 403 in the player nearly always means a wrong Referer. Keep the headers for the playlist host; segments on another host are the relay's business (6.2). `referer-on-videos` fires when videos carry no Referer and the source sets none.

### 6.6 Encrypted segments, tokens and expiry

- **AES-128 playlists** (`#EXT-X-KEY:METHOD=AES-128`) play when the key host answers with the video's headers. A playlist encrypted in a way the player can't open is dropped.
- **Encrypted source lists** (MegaCloud, MangaReader-style sites, players with a `decryptLink` function): find the key and the mode in the site's JS; `host.crypto.aes.decrypt` does CBC/ECB/CTR/GCM with hex or base64 keys, and `passphrase` for CryptoJS `Salted__` data. The engine has no P-256 signing; that work runs in `host.render`'s script (Filemoon Byse).
- **Tokens expire.** DoodStream, Streamtape, MegaCloud and signed CDN links (`?token=&expires=`) die in minutes to hours. Resolve them **inside `getVideoList` each time**; never store a stream address in `host.storage` or in the chapter url. Downloads call `getVideoList` again, so a fresh address is used.
- **Segments on image CDNs** (a big social site's CDN, `.png` names) are handled by the relay (6.2). If a segment still fails, the Logs screen names the host and the code.

### 6.7 Players filled in by the site's script: `host.findVideo`

- **What you see.** The episode page shows a player, but in `view-source:` its `iframe` has no `src`, only `:src="…"`, `v-bind:src`, `x-bind:src`, `[src]`, `ng-src`, or nothing at all: the site's script (Vue and the like) fills in the address after the page loads. A `getVideoList` that parses the plain page finds the frame, has nothing to follow and fails in well under a second ("player host not recognised"). In the builder, the helper marks such a Video pick "Video player found — needs the hidden browser".
- **Do this.** Don't parse the plain page for the player. Call the app's shared finder:

  ```js
  var found = await host.findVideo(url, { frame: "iframe.player", referer: BASE + "/" });
  // found.videos: [{ url, kind, quality, server, headers }], found.reason, found.steps, found.frames, found.tookMs
  ```

  It opens `url` in the hidden browser (with `referer` as its Referer), mutes and presses Play (the usual play buttons, `video.play()`, a tap in the middle), and catches the stream the player asks for: `.m3u8` anywhere in the address, `.mpd`, `.mp4`, `.mkv`, `.webm`, ads left out. When nothing came, it reads the player's address from the page **after** the scripts ran (the `frame` selector, else every `iframe`; plus each `<video>`'s source) and opens up to three of those players themselves with the page as Referer. It can take **about 45 s** (`timeout` option, in ms); the Test Run gives the video step 70 s.
- **What it returns.** `videos`, at most 6, HLS first, then DASH, then plain files: `kind` is `"hls"` (`quality: "Auto"`; give it to `require("hls").expand(video)` for one video per quality), `"dash"` (`"Auto (DASH)"`) or `"file"` (`"Direct"`); `server` is the first label of the player's host; `headers` carry the `Referer` the player sent and its `Origin`, so keep them on the video (6.5). `frames` are the player addresses it saw. `steps` are short lines of what it did (also in Logs under `video`).
- **When it finds nothing**, `reason` says why in plain words, and your error should include it:
  1. `no player on the page (nothing matched "<frame>", no <video> or <iframe>)`: the selector is wrong, or the player is behind another click.
  2. `the player at <host> never asked for a video in <n> s (it may need a real tap, or it blocks hidden browsers)`: try another server, or the player's own page with `host.render` and a script (6.1).
  3. `the player at <host> streams in pieces without a playlist address the app can use (<n> pieces seen)`: the stream is built in the page (MSE); usually not playable here.
  4. `the page didn't load (<error>)`.
- **Still parse first when you can.** When the plain page or the embed has the address (6.1), reading it is faster than a hidden browser. Call `host.findVideo` only in `getVideoList` (it costs a browser each time), and never cache what it found (6.6).
- **Templates.** generic-list v3 and anime-player v2 call it in their Videos part; a source made from an older version gets **Update** in the Lab editor's strip, which replaces only the Videos part and keeps the old code in History (`video-iframe-src-only` finds the old part).

### 6.8 Ad frames over the player

- **What you see.** Many video sites, adult ones above all, put `iframe`s from ad and traffic networks over or beside the player: hosts such as pnonolet, pemsrv, juicyads, exoclick, exosrv, trafficjunky, trafficstars, tsyndicate, adsterra, popads, propellerads, magsrv, realsrv, clickadu, hilltopads, adspyglass, ero-advertising, onclickads, a-ads.com, adnxs, doubleclick and googlesyndication. Their frame is often the largest one, or the first `iframe` on the page, so a tap or a plain `iframe` selector lands on it.
- **They are ads, never the video.** A video-frame selector or an extractor must skip them: narrow the selector to the player's own box (`#player iframe`, `div.video-container iframe`), add `:not([src*='exoclick'])` and the like, or filter the frames with a list of ad hosts. Never treat an ad frame's address as a hoster.
- **What the app does.** `host.findVideo` skips these hosts (from r102) when it picks frames to play or open; the builder warns when a **Video** pick is one of their frames. Lint's `ad-frame-not-player` flags code that points the player at one of them.

## 7. Bot checks

### 7.1 Recognising a check

| Check | What the reply looks like | What passes it |
| --- | --- | --- |
| **Cloudflare challenge** | 403 or 503, body with "Just a moment", `cf-chl`, `cf_chl_opt`; header `cf-mitigated: challenge`; cookie `cf_clearance` once passed | A real browser; the hidden browser often; WebView by hand |
| **Cloudflare Turnstile** | A widget on the site's own page (`challenges.cloudflare.com/turnstile`), verified by the site's API with a token | A real browser; the user in WebView; the site's own player in `host.render` |
| **DDoS-Guard** | 403 with its own page, script from `/.well-known/ddos-guard/`, cookies `__ddg1_`, `__ddg2_`, `__ddgid_` after a JS check | The hidden browser or WebView |
| **Rate limit** | 429, often with `Retry-After` | Waiting; `rateLimitMs`; fewer parallel requests (8.1) |
| **Hotlink protection** | 403 on images or videos only, with the page itself fine | A `Referer` (5.3, 6.5) |
| **Age / interstitial page** | 200 with a "Continue" page or an ad page instead of the content (an `/inter` page) | A cookie (`adult=1`, `age_ok=1`) set with `host.cookies.set` |

### 7.2 What the app does on its own

When a reply is a bot check, the request is sent again from a hidden browser page on that site, with the browser's connection, cookies and user agent; if that gets through, **all of that site's requests go through the browser for the rest of the session** (the console says so). Binary requests always use the app's connection. The browser can't carry every header you set (signature headers, your `User-Agent`), so an API behind a check may answer 401 after the retry; then send the request yourself from a light page of the site (`/robots.txt`) with `host.render`'s `script` and `fetch(..., { credentials: "include" })`. When the browser can't pass it either, the call fails with "wants you to pass its bot check" and the app offers to open WebView; **don't catch and hide that error**: your own message loses the mark the app needs. Two habits keep checks away: don't fire many requests at once (Popular and Latest together, every page in parallel), and don't ask for the same page twice in one run.

### 7.3 User agent consistency

A passed check is tied to the user agent that passed it. The app, WebView, `host.fetch` (by default) and the hidden browser all use one UA, so a check you pass in WebView counts for the source. A `User-Agent` of your own in `host.fetch` headers, or a `userAgent` option on `host.render`, makes `cf_clearance` worthless on that site: the hidden browser gets "Just a moment..." (403) and fetches from it fail with `TypeError: Failed to fetch`. Set your own UA only where a site needs a specific one (an image host that wanted mobile Chrome; a player that wanted a phone), and never on a Cloudflare-fronted host. When you see "Just a moment", tell the user to open the source in WebView and pass the check once, instead of retrying.

### 7.4 Cookies and their lifetimes

Cookies are kept per site in the app's shared jar: WebView, APK sources and JS sources share them. `cf_clearance` lasts hours and is bound to UA and IP (a VPN change kills it); `__cf_bm` about 30 minutes; DDoS-Guard's `__ddg*` about a day. **A cookie the app holds keeps being sent until it expires**: you can't un-set a site's toggle by sending your own `Cookie` header or by setting it to 0 (a `<site>-mature=1` cookie kept arriving); filter in the request instead. A GET of a cookie-dependent page can come back as a copy made for another visitor: add a throwaway `?_t=<time>` when that block matters. Fixed cookies (`adult=1`, `age_ok=1`) are set with `host.cookies.set` before the request. A cookie handed out mid-redirect-chain (a gallery site's login cookie) is kept by the app's redirect following; if a site still loops, load it once with `host.render` or offer the cookie as a setting.

### 7.5 When to use host.render

Only when nothing else gives the data: a check only a browser passes, a player that runs its own scripts, WebCrypto work the engine can't do (P-256), or a page built entirely by JS with no API. Rules: capture what you need (`capture: /\.m3u8/`), not a field name that an early reply also has; log `location.href` from the `script` to see which page you got; run fetch-only scripts on a page that doesn't redirect (`/robots.txt`), because a redirect throws the script's answer away; the hidden browser sends no `Referer` of its own; `early` is ignored in this app, and so is `host.fetch`'s `browserPage`; the default timeout is 20 s and the app doesn't cap `timeout`. Keep the direct request as a fallback and remember in storage which way worked.

| Call | Use it for | Gives back |
| --- | --- | --- |
| `host.render(url, { waitFor, waitForResource, capture, script, userAgent, timeout })` | A page after its scripts ran, or your own script on it: a check only the browser passes, WebCrypto work, catching a request you name | `html` after the scripts ran, the script's `result`, the captured `resources`, the final `url` |
| `host.findVideo(url, { frame, referer, timeout })` | An episode's video when the player is filled in by the site's script or only asks for its stream after Play (6.7) | `videos` (`url`, `kind`, `quality`, `server`, `headers`), `reason`, `steps`, `frames`, `tookMs` |

## 8. Networking

### 8.1 Rate limits and 429

`rateLimitMs` on the extension object is the smallest gap between two requests of the source; the runtime waits before each request. Set it on touchy sites (500–1500 ms) and no higher than the site needs: one source's fixed delay on every request made galleries slow. A 429 during the Test Run with no `rateLimitMs` fires `rate-limit-touchy`. In anything that reads many pages (`getTags`, catalogs, big page lists) pace yourself, wait out a 429 with growing pauses (`host.sleep` is 10 s at most per call: loop), and keep what you already read (one source threw away 1,800 tags on one 429). The app's related-title searches also hit the site; a source with `getRelated` turns them off.

### 8.2 Retries and status codes

`host.fetch` doesn't fail on 4xx or 5xx: check `statusCode` yourself. `fetch(url, { retry: { attempts, statuses, baseDelayMs } })` retries 429 and 503 by default, honouring `Retry-After` or a doubling delay. What each code usually means in a source:

| Code | Usually | Look at |
| --- | --- | --- |
| 400 | A wrong parameter; WordPress REST past the last page | The API's error body; return `[]` for the paging case |
| 401 | A token or signature header the browser retry dropped, or an expired token | 7.2, 7.5 |
| 403 | Referer/Origin on images and videos; a bot check (body); a blocked UA | 5.3, 6.5, 7.1 |
| 404 | An address pattern is wrong: paging base, a doubled slash, two addresses pasted together | 2.2, 9.1, 11.1 |
| 405 | HEAD not allowed (the Lab's probe falls back to GET Range) | 8.4 |
| 422 | A missing or empty CSRF token the site now checks | 7.5 |
| 429 | Too many requests | 8.1 |
| 500 / 502 | The site itself; try once more later | nothing in the source |
| 503 | A bot check or an overloaded site | `retry`, 7.1 |

Throw an `Error` with a clear message ("The site answered 503 for the chapter list") rather than letting `undefined` reach the parser; let bot-check errors pass through unchanged.

### 8.3 Timeouts and the 30 s clock

One `fetch` has 60 s for the whole call, on top of 30 s connect and read. JS that runs **30 s without awaiting anything** is stopped (since r86) with "its code ran for 30 s without stopping"; the clock pauses while a host call is pending, so a method that keeps awaiting may run for minutes. `host.sleep(ms)` waits 10 s at most. `host.render` waits its `timeout` (20 s default, uncapped). There is no `setTimeout` or `setInterval`: wait with `host.sleep`, race with `Promise.race([p, host.sleep(ms).then(...)])`. Give a debug run its own budget per request and split it into sections (`!debug lists`, `!debug videos`) so one slow step doesn't run the whole thing out of time.

### 8.4 Redirects, HEAD vs GET, compression

Redirects are followed (up to 20) with the app's cookie jar; `host.fetch`'s `url` (fetch's `response.url`, `redirected`) is the final address: use it to learn a title's real path (`/?p=<id>` → the slug) and as the base for relative links. **Don't HEAD covers yourself**: many CDNs answer 405, and a HEAD per cover costs seconds per list page; the Lab's probe does HEAD then `GET Range: bytes=0-0` for you. **Don't set `Accept-Encoding`**: the app adds gzip and unpacks the reply only when it set the header itself; your own value gives you compressed bytes, and brotli can't be unpacked at all. `binary: true` gives `bodyBase64` for files; `host.compress.gzip.decode` reads gzip you were handed as base64. Sources must only reach public `https` addresses; the engine doesn't refuse `localhost` or private ranges, but a source must not depend on that.

### 8.5 Caching what you fetched

Keep a reply you already have within one run: the same title page serves details, chapters and related, and the second request is the one that gets the bot check. Use a module-level cache keyed by address with a timestamp (the runtime lives for the process, so it is shared across calls; clear it by time). `fetch`'s `cache: true` stores the ETag **and the body** in `host.storage`, which rewrites `storage.json` whole on every write (10.2): avoid it. Never cache stream addresses (6.6).

## 9. Data hygiene

### 9.1 Absolute vs path urls (guide 08 §4.3)

- **Pictures and videos must be full `http`/`https` addresses**: `coverUrl`, `imageUrl`, `thumbnailUrl`, `previewUrl`, `seasonCoverUrl`, `bannerUrl`, `avatarUrl`, `webUrl`, video `url`, subtitle `url`. One that starts with `//` gets `https:`; anything else is **dropped silently** (the `relative-url-returned` rule shows them from the Test Run).
- **An entry's or chapter's `url` may be a path, an id or anything else**: the app hands it back to you unchanged. In JSON definitions write `{baseUrl}{entryUrl}` when it's a path and `{entryUrl}` when it's full.
- **Joining.** A path starting with `/` is `BASE + path`: `require("url").resolve(BASE + "/", "/slug/")` keeps both slashes here (`https://example.com//slug/`). Keep `resolve` / `absolute` for paths relative to a page (`../x.jpg`). One helper for the whole file: full address as is, `//host` → `https:` + it, `/path` → `BASE + path`, else resolve against the page.
- **Doubled slashes and server paths** (`https://host//hls/…`, `/home/…/wp-content/`) mean a path was joined twice or a file path was taken for an address (5.5).

### 9.2 Ids and keys

`extension.id` in quotes (`id: "1234567890123456789"`): ids are bigger than a JS number holds exactly. With the APK's id, its library opens through the JS; then the APK's `url` formats must be kept too (4.2). The app's own ids and ids another saved JS source uses can't be taken; the Logs screen says why. Settings live under the id, storage under the key: adding or removing an `id` line makes the settings look reset. In a repo index the `id` is a text that must never change. **`this` is not the extension**: each method is called on its own, so `this.baseUrl` is `undefined`; write `extension.baseUrl` or a `BASE` constant. Calls run at the same time: don't keep one call's state in a global another call can change.

**Keep `name` stable; ids decide identity.** Download folders are named after the source's `name`: before r99, renaming a source left its downloads behind in the old folder. From r99 downloads are linked by id, and the `id` decides which source the library, settings and downloads belong to. Rename only on purpose, and never change the `id` (`keep-source-name`).

### 9.3 Encoding, entities, whitespace

Values from regexes over HTML keep entities (`&amp;`, `&#039;`, `&quot;`): `require("entities").decode(text)`. jsoup's `text` and `attr` already decode. Trim titles and collapse whitespace (`/\s+/g` → one space; ` ` is whitespace too). Encode query words with `encodeURIComponent` (`+` for spaces only when the site wants it). JSON bodies with `JSON.stringify`; form bodies with `fetch`'s `form` option. A title with a trailing " - Read Online" or the site's name is the site's SEO, not the title: cut it. Numbers from text: cut the right part out with a regex before `Number()`, since "Vol 3 Ch 12" has two numbers.

### 9.4 Fields: leave out what the site doesn't say

Only set `status`, `author`, `rating`, `genres`, `dateUploaded` from what the site gives; a default fills the UI with wrong badges and dates. A broken item in a list is skipped and the rest kept; an item missing a required field (`url`, `title`) counts as broken. Return plain objects, lists, text, numbers and booleans: everything is turned into JSON. Don't put `badge` text in `title`; don't put the status in `genres`; don't return `coverUrl` from `getDetails` when the list had one (3.4).

## 10. Performance and memory

### 10.1 Parsing

`host.parse(html)` keeps the last 24 parsed pages per runtime; a page that was evicted gives "That parsed page is gone": parse again. Pass `{ html: false }` to `select` when you only need text and attributes. Parse a page once and share it between details, chapters and `getRelated` with a short-lived cache (8.5). `host.query(html, css)` is fine for one question; `host.parse` for many. A memory-low event clears every source's parsed pages (r95): don't hold element handles across awaits that fetch.

### 10.2 storage.json growth

`host.storage` is one JSON file per source, **rewritten whole on every write**. Keep it small: tokens, a few ids, the last working key, a cursor. Don't store page bodies, whole catalogs or stream addresses; `fetch`'s `cache: true` stores bodies there (the `fetch-cache-grows` rule). `getTags()` must not write to storage (it runs in its own runtime). Storage isn't backed up or synced; everything in it must be rebuildable.

### 10.3 Large JSON and lists

An API that returns every title at once (thousands of rows) is fine to keep in a module variable with a timestamp for the life of the runtime, paged in memory 24 at a time; parsing it on every call costs seconds, storing it costs more. The engine has 128 MB; a 20 MB JSON string plus its parsed form fits once, not several times. Slice before you log (`.slice(0, 1500)`); `JSON.stringify` of a big object for a console line is as slow as the parse.

### 10.4 Many requests

The runtime holds a lock per call: **one call at a time per source**, so a slow `getVideoList` or a `getDetails` that fetches five pages holds up Browse paging of the same source, and the app's related-title searches queue behind it. Read servers' pages with `Promise.all` in bounded batches (5 at a time), not one by one and not all at once. Move big reads (tag lists) to `getTags()`, which runs in its own runtime, bounded (the 30 most popular pages, not all 900). Keep partial results and return them rather than failing (8.1). Keep `getRelated` to a few requests: it runs every time a title opens.

## 11. Testing with the Lab

### 11.1 Test Run steps

**Run all** on the Test pane (or the nightly Re-test) runs: page 1 of each tab catalog (in Browse order) → Popular → Latest → search (the Test box, else the first title's first word of 3+ letters) → **Filters** (when the source has `getFilters`: the list with no filters first, then up to 6 filters, the built-in List first, each once with its first non-empty value through `search("", 1, { key: value })`, a Sort with its first two options; each try shows the filter's role and the address tried, how many titles came back and whether the list differs from the unfiltered first page, a Sort's two options from each other; an unfiltered list with 0 titles adds "filters are on a page with nothing listed", 2.6) → **Settings** (when it has `getSourcePreferences`: the keys and defaults, flagging entries without a key, keys used twice, a list without options or a default that isn't one of them, a switch whose default isn't true/false) → the first title's details (`getDetails` when the source has it) → its chapters or episodes → the first chapter's pages or the first episode's hosters. Each step gets its `[net]` lines (method, address, status, time, `BROWSER` when the hidden browser sent it), its `[warn]`/`[error]` lines and the first 60 objects as returned. Judgements: entries without `url`, or more than 20 % without a cover, turn a list step **Amber**; the title is Green with 6 of 8 fields; chapters are Green when every one has a number and a date; hosters are grouped by `server`. Then the probes: the first page image is fetched with HEAD (GET `Range: bytes=0-0` on 405), 403/404 → Amber, and once more without a Referer so the evidence says whether the Referer is what the server refuses; each hoster's first video is fetched for 2 KB (HLS through the relay) and counts as played when it starts with `#EXTM3U`, `ftyp`/`moov`, the MKV or MPEG magic, or has a `video/*` type. The run is saved only at the end; Lint runs after it. **The console** under the steps (tap its header to open it) holds every line of the run, live while it runs, grouped by step, each with its time since the start and its level (E red, W amber, I normal, D grey, requests as `GET /path → 200 · 340 ms`; tap a request for its headers and, when it failed, the first 2 KB of the reply; tap an error with `source.js:N` to open that line). Chips filter Errors / Warnings / Network / Logs, the box searches, **Copy** takes the lines shown and **Share** sends the whole console as a .txt. **Make sure you're testing the new code**: after pasting, check something only the new version has before running (one "fix" report was byte-for-byte the old one). Copy test addresses from the Test tab one at a time (two pasted together give a 404).

### 11.2 What each red step usually means

| Step red | With | Usually |
| --- | --- | --- |
| Popular / Latest / Tab | `[net] … -> 200` then 0 items | The item selector or `itemsPath` matched nothing: `view-source:` the page and count. Or the list is built by JS (1.1). |
| Popular / Latest | `[net] … -> 403` / `503` | A bot check (7.1), or the API needs a header (1.9) |
| Popular / Latest | 404 | The paging base or the address pattern (2.2) |
| Popular / Latest | `TypeError: not a function` | `text(css)` on an element (1.11) |
| Search | 0 items | The search address or the encoding; an empty-query address missing (2.3) |
| Title | missing `url` / `title` | The details selector; `getDetails` must return the same `url` it was given |
| Chapters | 0 items with 200 | The list is loaded by AJAX (4.1); check the request in DevTools |
| Chapters | every date 0 | `dateUploaded` is human text: parse it (4.5) |
| Pages | 0 items with 200 | Images are lazy or in a script (5.1, 5.2) |
| Pages | probe 403 | Referer (5.3); the evidence says if the bare request worked |
| Pages | probe 404 | A doubled slash or a server path in `imageUrl` (5.5, 9.1) |
| Hosters | 0 videos | The embed chain broke at a step: log each reply (6.1) |
| Hosters | probe 403 | Referer/Origin on the video (6.5) |
| Hosters | probe not played | A token expired between resolve and probe (6.6), or an encrypted playlist the player can't open |
| Any | "its code ran for 30 s" | A loop without awaits, a runaway regex (8.3) |
| Any | "wants you to pass its bot check" | Open the source in WebView once (7.2) |

A `[net] … -> 200` line followed by an error means the site answered fine and the bug is in the code that reads the reply. Line numbers in `at list (source.js:381:38)` are your file's lines as saved in the editor.

### 11.3 Probes for the hard cases

- **Is the HTML what the browser shows?** `console.log(body.slice(0, 2000))` in the method, compare with `view-source:`, remove it after.
- **Where does the stream come from?** `host.render(episodeUrl, { capture: /\.m3u8|\.mp4/ })` on the Test tab lists every matching request the page made (`resources`).
- **Which page did the hidden browser get?** Return `location.href` and `document.title` from the `script`.
- **Does a cookie matter?** Fetch the page plain and with `?_t=<time>`, log the block from both.
- **Do the counts match?** A debug run that asks for one big page (`posts_per_page=2500`) and compares with the site's own "N results".
- **Which server fails?** A debug setting that tries every server and logs each step's status and the first 200 bytes.
- **Is it the VPN?** Try without it before changing code when covers or checks changed suddenly.
- **Video pages in a manga chapter** show as "Page N · video (tap to test)" on the Test panel; tapping runs `getVideoList` on it.

### 11.4 Lint and this rules file

Lint runs after every save and Test Run, never blocking either, with the machine rules of this file against the code and the last run. Seven check kinds: `regex-in-code` (a pattern per line, with `unless`, `near`, `part`, `flags: i`), `selector-pattern` (over string literals that look like selectors), `result-field` (`kind`: relative-url, same-status, date-zero, server-path, field-missing; over the last run's objects, optionally one `step`), `compare-saved-chapter-urls` (the run's chapters against the library's rows of the same title; silent under 3 matches, so it mostly speaks for titles you have in the library), `header-present` (`on`: videos | images), `response-status` (a code in the run's requests, unless the code matches `unless`), `render-option` (an option on `host.render` when the run met a bot check, or always with `always: true`). `check: text` rules are listed "to read" (the first three) when their `applies` tags match the source. **Tags this build computes:** `manga` or `anime`; `js` or `json`; `api` when the code has `/api/`, `/wp-json/`, `application/json` or `JSON.parse`; `madara` for `madara`, `wp-manga`, `manga_load_more` or `wp-admin/admin-ajax`; `cloudflare` when the last run met a browser retry or "bot check" in the console; `apk-port` for a source with an `id` line or imported from an extension; `videos`; `all`. Other tags in this file (`mangathemesia`, `mangareader`, `foolslide`, `keyoapp`, `wordpress`, `nextjs`, `hls`, `extractors`) are for reading and always sit next to a computed one. **Apply** changes the code in the editor only when the rule's `find` occurs exactly once in the finding's part; you still save. **Ignore** hides a rule per source. `regex-in-code` patterns are ICU regexes in single-quoted YAML: write `''` for a quote and no bare braces. `getComments` / `getChapterComments` exist in the API but no Kotlin code calls them yet; `descramble`, novel `text` pages and `early` aren't supported (guide 08 §7.6). Rules file: **Source Lab › Rules file › Update from file** imports a file whose `version` is higher than the current one (a parse error names the line and keeps the old file); **Versions › Roll back** returns to an earlier import or to the built-in file (version 15 from r105). The Versions list shows which file is current; after importing a file, roll back to the built-in one to use a newer built-in.

### 11.5 Issue Report

**Report › Share** builds a zip: `README.txt` with one line (`<step> failed: <subject> <evidence>, <cause>`, the cause one of referer missing, bot check in the way, 0 items after 200, relative URL returned, timeout, 403 from a host), the source, `meta.json`, `lint.json`, the last two Test Runs, the failing step's requests, the console, the fingerprint and a device line; settings only when switched on (secret keys shown as `<set>`). Cookies, tokens and e-mails are removed, and with the switch on every host but the source's own becomes `host-<n>`. Send that, not a screenshot.

### 11.6 Debug logs

One switch for every source: **Settings › Advanced › JS sources: debug logs** (on by default). On, every console line of every JS / JSON source goes to the Logs screen under `js:<name>`; off, only `console.warn` and `console.error` (and the app's own errors) do. So a source needs no Debug setting of its own and no `debug()` helper: log each step with `console.log` (list page and count, details, pages found, every step of finding a video), use `console.warn` / `console.error` for real problems, and slice data to about 1,500 characters. Don't write a separate test run or a Debug tab. An older source's own Debug switch still works; it just isn't needed. The Test Run's console (11.1) shows the same lines with times, request details and copy / share.

### 11.7 Reading errors and catches

Line numbers match your file. A `[net] … -> 200` then an error is a parsing bug. `TypeError: not a function` right after a 200 is `text(css)` on an element. "That parsed page is gone" means the 24-page cache evicted it. Don't swallow errors: an empty `catch`, or one that returns `[]`, hides the bot-check mark the app needs and leaves nothing in Logs; log the message and rethrow, or throw your own clear `Error`. Turn a redirect loop into "not logged in" rather than a generic failure.

**The templates follow this too (r104, generic-list v7+).** The title page's small readers (a field's selector, a meta tag, a Tags group, the episode thumbnail) catch their error, **log it** ("Title page: <css> wasn't read: …", "Tags group <name> wasn't read: …") and give `""`, so the other fields still come and the line is in Logs. No template helper has an empty `catch`; a source made from one is Lint-clean on `empty-catch-hides-errors`. Keep it that way when you edit one.

### 11.8 The New source builder: picking elements

- **Read the helper after every tap.** Each pick is checked at once: ✓ (looks right), ! (doesn't look like that label) or i (needs something, such as a player for the hidden browser). The card lists what passed and what didn't, with sample texts. Keep **Check each pick** on.
- **Taps land on things you didn't mean.** Invisible full-page overlay links (ad layers), sticky headers, the site's menus and heading bars sit over or around the content and catch the tap. Use **Up** / **Down** to move the pick to the parent or child (it keeps its label and is checked again), or **Use suggestion** for the boxes the helper outlines with a dashed line.
- **The Item is the repeated card**: the smallest element that repeats (3 or more on the page) and holds both the title's link and its cover. Not a heading bar such as "New Releases", not the list's wrapper, not one link. Title and Cover should then count about as many as Items (within a fifth).
- **Next** is one link to page 2 on the same site; **Chapter** links repeat and differ (a title that is its own only episode uses the single-episode switch); **Video** is the `iframe` or `video`, not the play button over it, and never an ad network's frame (6.8).
- **Tapping for…** (from r102). The row under the address bar lists every label by page (List page, Title page, Episode/Chapter page, List for a filter). With nothing chosen the page works like a normal browser (scroll, open menus, follow links); tap a label to arm it, and the next tap on the page is that pick, never a navigation. A long press still picks without arming. The step follows the page you're on (a title's address → Title page), so open a title in the site itself and its labels are there.
- **Tags in groups.** **Tags** stays armed: from r104 one tap on a details block, a row, a label or a value brings **every row of the block**, one group each in page order, named after the row's label with the value elements only; link rows go to Tags, plain rows (a code, a date, a duration) to Description. Rename, retarget or remove one in the Tags card; old whole-row or label-list groups get **Split into rows** (3.7). The source writes them as `"Group: value"` genres (3.3) from `tagGroups`.
- **Learn this list.** For a filter menu whose entries are buttons without links (a year or type dropdown the site's script runs), pick the list with **List for a filter**, then **Learn this list**: the builder presses each entry on the list page (opening the menu first), waits for the address to change and records it, with a short random pause between presses; after three it looks for the pattern (a query value or path part equal to the entry's text) and fills in the rest. An entry that changes nothing (like "All") becomes the filter's "Any". Tap the entries, not the menu's own button. From r105 **Learn addresses** does the same for dropdowns and `<select>`s (a `<select>` without a name too), keeps the codes the site sends rather than the labels, and sets each filter's role (2.6).
- **Menus with sub-menus and directories.** Tapping a menu heading whose sub-menus are menus makes a group with one filter per sub-menu; a sub-menu that opens a paginated directory of names offers **Fetch the full list** and becomes a text box with suggestions (2.6).
- **Filter cards.** Red text "Not in the source yet" on a card means that filter is skipped until it has a parameter or an address; ▲▼ also move a filter into or out of a group; making a filter again keeps its group, search switch and role (2.6).
- **Suggestions.** Tap one card of a section with **Suggestions** armed: the section is a named group, named after its heading; a card marked **loads later** reads the section from the request the page made for it, and a warning on the card means none was found (3.6).
- **Edit by tapping.** A source made by the builder opens again in it from Test Run › **Edit** (next to Open in Browse), with its picks restored (older sources: what can be read from the code's settings at the top); **Save to this source** writes a new History version under the same key, so the library and downloads keep it.

### 11.9 Record and convert: the self-check and its TODOs

**Record and convert** runs an installed extension's lists, a title, its chapters and pages (or videos), records every request it makes with the replies, and writes a JS source from that evidence only.

- **It checks its own work (r105).** For every list part (Popular, Latest, Search) and for the title, chapter and page parts it tries ways to read the reply from strict to loose, runs each on the recorded reply, and keeps the first whose output matches what the extension returned: the same addresses and titles in order for at least 9 in 10 items, and the covers after any change the extension made to them. Loosenings it may use: an array where at least 8 in 10 of the returned items map onto its entries; a fixed text before or after a field (a CDN folder before an image name, `?w=250` after it), with values that are already full addresses kept as they are; items wrapped one or two levels deep; an address built from an id with a fixed path in front; a description with a fixed label around it ("Synopsis: …").
- **Read the `checked:` line.** Each written part starts with `// checked: N of M items match the extension's output` (covers counted too, and whether the code reverses its list). The file's header lists all of them. N close to M is a part you can trust; a lower N gets a TODO line saying the extension filters, sorts or adds items in its own code. Compare those items in Test Run with the extension in Browse.
- **The size counts too (r105 fix).** Matching items isn't enough: the number of items the code gives on the recorded reply must fit the list. For a **paged** list it must reach at least the smaller of the returned count and 5, and as many of the returned items as the reply's best list holds, so a 1-item side array (a "featured" entry next to the real list) can't score "1 of 1". For **other** lists it must be between 0.8 and 1.1 times the returned count, so a superset array holding the 20 returned items among 140 doesn't pass. A reading of the wrong size isn't written as checked; a superset is written with a TODO saying the extension picks the list in its own code (`converted-list-size`).
- **Requests on other threads (r104).** Extensions often make their requests through OkHttp's `enqueue` or an Rx scheduler, off the thread that runs the step, and those used to be missed ("no data request was recorded"). Now an untagged request belongs to the one step that is running (steps run one at a time). Left out: requests whose Referer or Origin names a site the recording has never seen, and the **app's own traffic** during a step (extension repo indexes, update checks, trackers, GitHub). A request counted only because its step was running, to a host not seen yet, has its own budget (20 requests, 2 MB per step) and never teaches the recorder a host. Logs show `Recording <step>: N requests (M started on other threads)` (`recorder-other-threads`). Requests a cancelled step leaves behind are dropped, never counted for the next step.
- **Every TODO says why, in one line**, above the recorded requests and a reply excerpt (the part throws "… was not converted from the recording" until it is written: `converted-part-todo`). The usual causes and what to do:

| TODO says | What it means | Check or do |
| --- | --- | --- |
| no data request was recorded in this step | Only images, bot-check pages or failed requests were seen; or the extension read a cache | Clear the extension's cache or open a title it hasn't seen, pass a bot check in WebView first, record again |
| none of the recorded replies holds the values | The data came from a request the app didn't see (a WebView of the extension's own, a second app) or from the extension's own code | Look at the listed requests; if the values aren't there, write the part from the site directly |
| one long encoded string at … (a scrambled field) | The reply carries base64 / hex / encrypted text the extension decodes | Find the decode in the extension's code (key, mode) and write it with `host.crypto` (6.6), or read the site's page instead |
| a piece of them …, so the extension builds them in its own code | The reply has an id or a folder name, and the extension puts the address together (image addresses most often) | Copy the pattern from one returned address (`"https://cdn/" + id + "/" + file`) and build it the same way |
| no page number in the address: page 2 and on return nothing | Page 2 differs from page 1 nowhere the converter could see, often because the page number sits in a POST body (a GraphQL `variables.page`, a form `page=`) | Record with page 2 (Popular records it); check the body was recorded (bodies over 8 KB or streamed aren't, which is its own TODO) and put the page into the body by hand |
| the search words are not in the recorded request | The extension changes the words (slug, upper case, a hash) | Write the search address from a real search on the site (2.3) |
| the search was recorded with the default filters | How the extension sends filters is unknown | Read `filters` and add them (2.4, 2.6) |

- **DNS lookups are ignored.** Requests that are DNS-over-HTTPS lookups (an `application/dns-message` reply, `/dns-query`, the public resolvers) are the app resolving the API's host, never the extension's data; from r105 they are left out of the recording and of the TODO listings.
- **Convert again.** On a source made by Record and convert, ⋮ › **Convert again** records the same extension once more, converts it with the current converter and saves the result as a new History version of the same source (same key and id; your name, language, icon and address kept), then opens Test Run. Use it after an app update that improves the converter, or after fixing what a TODO pointed at. If the extension isn't installed any more it says so: install it again first.

### 11.10 Extension installs that stall

A batch of extension installs or updates can stop on one extension when Android waits for a confirm that never shows (the app was in the background, a system dialog nobody answered). From r105 the app posts a "Tap to approve" notification for a prompt left open, gives up on that extension after 60 s with no answer from Android (3 min while it waits for your confirm), logs it under `extensions` and moves on with the next. Install the skipped one again on its own.

## 12. Checklist before saving

### 12.1 Lists

- [ ] Popular, Latest and a two-word search each give a full page, and page 2 gives different titles (2.2).
- [ ] Covers show in the Test results; if not, lazy attributes and Referer (3.4, 5.3).
- [ ] Every entry has `url` and `title`; no `{placeholder}` left in addresses.
- [ ] Filters change the site's answer; no tabs or catalogs the site doesn't have (2.4).
- [ ] Filter values are the site's codes, not its labels; each filter has the right role, and the unfiltered list isn't empty (2.6).
- [ ] No filter card is red ("Not in the source yet"); dropdowns start with Site default; filters applied on Popular or a tab stay on that list; page 2 of a page filter's list has the right address; no `%25` codes (2.6).
- [ ] Suggestion groups show covers, and a section marked "loads later" really gives cards (3.6).
- [ ] A converted source: every part's `checked:` line is close to its total, and no part is left as TODO (11.9).
- [ ] The search address was copied from a real search on the site (2.3).
- [ ] Button names on the extension object; no global `POPULAR_NAME` / `LATEST_NAME` (2.5).

### 12.2 Title and chapters

- [ ] Opening a title gives its description and genres; `status` only when the site says; `webUrl` opens the right page (3.2, 3.5).
- [ ] Tags groups are the site's rows: each named after its label with values only, link rows in Tags, plain rows in the description; no whole-row or label-list group left (3.7).
- [ ] The chapter or episode list is complete, every row has `number` and `dateUploaded`, newest at the top in the app (4.3, 4.5).
- [ ] An APK port keeps the APK's `id` (quoted) and its title and chapter `url` formats character for character (4.2, 9.2).
- [ ] Nothing that changes is in a chapter `url` (4.2).
- [ ] Anime: thumbnails, summaries and Sub/Dub where the site has them (4.4).

### 12.3 Pages and videos

- [ ] A chapter gives every page as a full address; the first page's probe is green (5.1, 5.3).
- [ ] An episode gives at least one video that plays, with `server`, `quality`, and `headers.Referer` on every video (6.3, 6.5).
- [ ] Stream addresses are resolved in `getVideoList`, never cached (6.6).
- [ ] A player filled in by the site's script uses `host.findVideo`, and its `reason` is in the error (6.7).
- [ ] Mixed posts: each clip is a page with `videoUrl` or `videoListUrl`, and the source is saved as Manga (5.7).

### 12.4 Code and Lab

- [ ] The Test Run console shows no errors (the global debug switch decides whether `console.log` reaches Logs, 11.6); no `this.baseUrl`, `setTimeout`, `eval`, own `Accept-Encoding` (9.2, 8.3, 8.4).
- [ ] No own `User-Agent` or `host.render` `userAgent` on a Cloudflare site (7.3).
- [ ] `rateLimitMs` set when the site answered 429; `retry` on 503 (8.1, 8.2).
- [ ] Lint is clean, or each finding was ignored on purpose (11.4).
- [ ] `name` only changed on purpose, `id` never (9.2).
- [ ] The source's notes at the top say how the site is read, which APK it replaces, and what was decided on purpose (paging base, dropped servers).
