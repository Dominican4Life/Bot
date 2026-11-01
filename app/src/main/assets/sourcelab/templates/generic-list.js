// @version 5
// Generic list template of the Source Lab, written for {{name}} ({{baseUrl}}) from what was tapped in Build by
// tapping (or filled by hand). Lists are pages of ITEM cards, chapters / episodes are the links of CHAPTER on a
// title page, pages are the IMAGE pictures of a chapter page, videos the VIDEO_FRAME of an episode page.
// r100 (v3): videos come from the app's video finder (host.findVideo); the tabs, filters and settings recorded in
// "Lists and options" of Build by tapping are added only when there are some (part "extras").
// r101 (v4): the title page reads the Cover / Description / Tags / Author / Status tapped on it first, keeps the
// list's cover when the page has none, skips the site-wide description and menu links; filters can change the list's
// address itself (a genre page /genres/{value}) and sort the list ("sort" filters).
// r102 (v5): the title page's rows tapped as Tags groups (Cast, Maker, Genres…) give "Cast: name" tags, which the
// app shows grouped like E-Hentai's; rows set to Description add a "Release date: …" line under the description.
// Tapping a grouped tag searches it in the source (a filter with that name and option when there is one).

/*@part id="headers"*/
// Headers and constants: the site as Referer on every picture; the selectors the builder derived (each with
// its reason in the Explain pane) and the list paths ({page} and {q} are filled per call).
var BASE = "{{baseUrl}}";
var POPULAR_PATH = "{{popularPath}}";
var LATEST_PATH = "{{latestPath}}";
var SEARCH_PATH = "{{searchPath}}";
var ITEM = "{{itemSelector}}";
var TITLE = "{{titleSelector}}";
var COVER = "{{coverSelector}}";
var COVER_ATTR = "{{coverAttr}}";
var NEXT = "{{nextSelector}}";
var CHAPTER = "{{chapterSelector}}";
var CHAPTER_DATE = "{{dateSelector}}";
// r99: "This page is the only episode" (one video per title page, no list): the title page is episode 1, and
// CHAPTER_DATE / EPISODE_NUMBER are read on that page (EPISODE_NUMBER is used only then)
var SINGLE_EPISODE = "{{singleEpisode}}" === "true";
var EPISODE_NUMBER = "{{numberSelector}}";
var IMAGE = "{{imageSelector}}";
var IMAGE_ATTR = "{{imageAttr}}";
var VIDEO_FRAME = "{{videoFrame}}";
// r101: the title page's own fields tapped in Build by tapping (empty = not tapped: getDetails' fallbacks then)
var DETAILS_COVER = "{{detailsCover}}";
var DETAILS_COVER_ATTR = "{{detailsCoverAttr}}";
var DESCRIPTION = "{{descriptionSelector}}";
var TAGS = "{{tagsSelector}}";
var AUTHOR = "{{authorSelector}}";
var STATUS = "{{statusSelector}}";
// r102: the title page's rows tapped as Tags groups: [{ name, selector, target }], target "tags" (genres "name: value"),
// "description" (a "name: value, value" line under the description) or "both"; "[]" when none were tapped
var TAG_GROUPS = JSON.parse("{{tagGroups}}" || "[]");
// r100: "Lists and options" of Build by tapping: the names of the Popular / Latest buttons (empty = the app's own),
// extra tabs [{ name, path }], search filters [{ key, name, type, mode, param, path, options, on }] and settings (the
// getSourcePreferences format). Lists are JSON text; "[]" when nothing was recorded. (Not POPULAR_NAME / LATEST_NAME:
// the app reads those globals as the names even when empty.)
var TAB_POPULAR_NAME = "{{tabPopularName}}";
var TAB_LATEST_NAME = "{{tabLatestName}}";
var TABS = JSON.parse("{{tabsList}}" || "[]");
var FILTERS = JSON.parse("{{filtersList}}" || "[]");
var SETTINGS = JSON.parse("{{settingsList}}" || "[]");
var HIDE_WORDS = SETTINGS.some(function (s) { return s && s.key === "hideWords"; });
var extension = {
  name: "{{name}}",
  lang: "{{lang}}",
  baseUrl: BASE,
  iconUrl: "{{iconUrl}}",
  popularName: TAB_POPULAR_NAME || undefined,
  latestName: TAB_LATEST_NAME || undefined,
  headers: { Referer: BASE + "/" },
/*@end*/

/*@part id="getPopular"*/
  // Popular: POPULAR_PATH with {page} filled, the ITEM cards (TITLE for the name and link, COVER for the picture).
  getPopular: async function (page) {
    return cards(await get(pathFor(POPULAR_PATH, page, "")));
  },
/*@end*/

/*@part id="getLatest"*/
  // Latest: LATEST_PATH with {page} filled, the same cards (the same path as Popular when the site has no
  // separate list).
  getLatest: async function (page) {
    return cards(await get(pathFor(LATEST_PATH, page, "")));
  },
/*@end*/

/*@part id="search"*/
  // Search: SEARCH_PATH with {q} and {page} filled, the same cards. Each FILTERS entry adds its value (a dropdown: the
  // picked option's value; a sort: its picked option's value; a checkbox: its "on" value when ticked; a text: when not
  // blank; empty values are left out). A "param" filter (the default) sets param=value in the address; a "path"
  // filter changes the list's address itself (path with {value}, e.g. /genres/{value}?page={page}): with no words
  // typed, the first path filter with a value (a sort only when no other has one) gives the address instead of
  // SEARCH_PATH; while searching for words, path filters are left out.
  // r102 (v5): a grouped tag tapped on a title page arrives as "Group: tag" (Genres: Drama): when a filter is named
  // like the group (or has it as key) and has an option labelled like the tag, that option is picked (no words);
  // else only the tag's words are searched. (Every constant / helper is checked, so this part also runs in older
  // sources.)
  search: async function (query, page, filters) {
    var list = typeof FILTERS !== "undefined" && Array.isArray(FILTERS) ? FILTERS : [];
    var groups = typeof TAG_GROUPS !== "undefined" && Array.isArray(TAG_GROUPS) ? TAG_GROUPS : [];
    var valueOf = typeof filterValue === "function" ? filterValue : function (f, v) {
      if (v === undefined || v === null) return "";
      if (f.type === "checkbox") return v === true || v === "true" ? String(f.on || "1") : "";
      if (typeof v === "object") {
        var o = (f.options || [])[v.index];
        v = v.value !== undefined && v.value !== null && v.value !== "" ? v.value : (o ? o.value : "");
      }
      return v === undefined || v === null ? "" : String(v).trim();
    };
    var q = String(query || "").trim();
    var tagged = /^([^:：]{1,40}?)\s*[:：]\s*(\S.*)$/.exec(q);
    if (tagged) {
      var ns = tagged[1].trim().toLowerCase();
      var tag = tagged[2].trim();
      var known = ns === "tags" || groups.some(function (g) { return g && String(g.name || "Tags").trim().toLowerCase() === ns; }) ||
        list.some(function (f) { return String(f.name || "").trim().toLowerCase() === ns || String(f.key || "").toLowerCase() === ns; });
      if (known) {
        var hit = null;
        list.forEach(function (f) {
          if (hit || (f.type !== "select" && f.type !== "sort")) return;
          if (String(f.name || "").trim().toLowerCase() !== ns && String(f.key || "").toLowerCase() !== ns) return;
          (f.options || []).forEach(function (o, index) {
            if (!hit && o && String(o.label || "").trim().toLowerCase() === tag.toLowerCase()) hit = { f: f, o: o, index: index };
          });
        });
        if (hit) {
          filters = Object.assign({}, filters || {});
          filters[hit.f.key] = hit.f.type === "sort" ? { index: hit.index, value: hit.o.value, ascending: false } : hit.o.value;
          q = "";
          console.log("Tag " + tagged[1].trim() + ": " + tag + " → filter " + (hit.f.name || hit.f.key) + " = " + (hit.o.value || "(any)"));
        } else {
          q = tag;
          console.log("Tag " + tagged[1].trim() + ": " + tag + " → no filter has it: searching \"" + tag + "\"");
        }
      }
    }
    var chosen = [];
    for (var i = 0; i < list.length; i++) {
      var f = list[i];
      var v = valueOf(f, filters ? filters[f.key] : undefined);
      if (v) chosen.push({ f: f, v: v });
    }
    var paths = chosen.filter(function (c) { return c.f.mode === "path" && c.f.path; });
    var url = null;
    if (!q && paths.length) {
      var use = paths.filter(function (c) { return c.f.type !== "sort"; })[0] || paths[0];
      url = pathFor(String(use.f.path).split("{value}").join(use.v), page, "");
      paths.forEach(function (c) {
        if (c !== use) console.log("Filter " + (c.f.name || c.f.key) + " left out: " + (use.f.name || use.f.key) + " already picks the list's address");
      });
    } else {
      url = pathFor(SEARCH_PATH, page, q);
      paths.forEach(function (c) {
        console.log("Filter " + (c.f.name || c.f.key) + " changes the list's address: left out while searching for words");
      });
    }
    chosen.forEach(function (c) {
      if (c.f.mode !== "path") url = withParam(url, c.f.param || c.f.key, c.v);
    });
    return cards(await get(url));
  },
/*@end*/

/*@part id="getDetails"*/
  // Title page: the Cover / Description / Tags / Author / Status tapped on it first (DETAILS_COVER, DESCRIPTION,
  // TAGS, AUTHOR, STATUS). Else: the cover from og:image, a <video poster> or the player's poster, and no cover at
  // all when there is none (the list's cover stays); og:description unless it is the site's own slogan (the home
  // page's description, read once); genre / tag links outside the site's menus, without "Genres" / "Tags" words.
  // Author and status only when tapped. r102 (v5): the rows tapped as Tags groups (TAG_GROUPS) come first: each
  // value becomes the genre "Group: value" (the app groups them like E-Hentai's) and / or a "Group: a, b" line under
  // the description; the other genres are then "Tags: value". (Every r101 / r102 constant is checked, so this part
  // also runs in older sources.)
  getDetails: async function (url) {
    var doc = await host.parse(await get(url));
    var pickedCover = typeof DETAILS_COVER !== "undefined" ? DETAILS_COVER : "";
    var pickedCoverAttr = typeof DETAILS_COVER_ATTR !== "undefined" ? DETAILS_COVER_ATTR : "";
    var pickedDescription = typeof DESCRIPTION !== "undefined" ? DESCRIPTION : "";
    var pickedTags = typeof TAGS !== "undefined" ? TAGS : "";
    var pickedAuthor = typeof AUTHOR !== "undefined" ? AUTHOR : "";
    var pickedStatus = typeof STATUS !== "undefined" ? STATUS : "";
    var tagGroups = (typeof TAG_GROUPS !== "undefined" && Array.isArray(TAG_GROUPS) ? TAG_GROUPS : [])
      .filter(function (g) { return g && g.selector; });
    function clean(t) { return String(t || "").replace(/\s+/g, " ").trim(); }
    function picture(u) {
      u = String(u || "").trim().split(/[\s,]+/)[0];
      if (!u || /^data:/i.test(u)) return null;
      var full = abs(u);
      return /^https?:\/\//i.test(full || "") && !/\.svg(\?|#|$)|favicon|\/logo[^\/]*$/i.test(full) ? full : null;
    }
    async function textOf(css) {
      if (!css) return "";
      try { var el = await doc.selectOne(css); return el ? clean(el.text) : ""; } catch (e) { return ""; }
    }
    async function meta(css, name) {
      try { return clean(await doc.attr(css, name)); } catch (e) { return ""; }
    }
    var out = { url: url, webUrl: url };
    out.title = (await doc.text("h1") || await doc.attr("meta[property='og:title']", "content") || "").trim();

    // Cover: the tapped picture, og:image, a <video poster>, the player's poster; none = left out (the list's stays)
    var cover = null;
    if (pickedCover) {
      try {
        var el = await doc.selectOne(pickedCover);
        if (el && el.tag !== "img") el = (await el.selectOne("img")) || el;
        if (el) {
          cover = picture(el.attr(pickedCoverAttr || "src")) || picture(el.attr("data-src")) || picture(el.attr("data-lazy-src")) ||
            picture(el.attr("data-original")) || picture(el.attr("srcset")) || picture(el.attr("src")) || picture(el.attr("poster"));
        }
      } catch (e) { cover = null; }
    }
    cover = cover || picture(await meta("meta[property='og:image']", "content")) ||
      picture(await meta("meta[name='og:image']", "content")) ||
      picture(await meta("video[poster]", "poster")) ||
      picture(await meta("[data-poster]", "data-poster")) ||
      picture(await meta("meta[itemprop='thumbnailUrl']", "content")) ||
      picture(await meta("link[itemprop='thumbnailUrl']", "href")) ||
      picture(await meta("meta[name='twitter:image']", "content"));
    if (cover) out.coverUrl = cover;

    // Description: the tapped text, else the page's meta description unless it is the whole site's
    var description = await textOf(pickedDescription);
    if (!description) {
      var meta1 = await meta("meta[property='og:description']", "content") || await meta("meta[name='description']", "content");
      if (meta1) {
        var g = typeof globalThis !== "undefined" ? globalThis : {};
        if (g.__labSiteDescriptions === undefined) {
          g.__labSiteDescriptions = [];
          try {
            var home = await host.parse(await get(BASE + "/"));
            var a1 = clean(await home.attr("meta[property='og:description']", "content"));
            var a2 = clean(await home.attr("meta[name='description']", "content"));
            g.__labSiteDescriptions = [a1, a2].filter(function (x) { return x; }).map(function (x) { return x.toLowerCase(); });
          } catch (e) { console.log("The home page's description wasn't read: " + e.message); }
        }
        if (g.__labSiteDescriptions.indexOf(meta1.toLowerCase()) < 0) description = meta1;
        else console.log("The page's description is the site's own slogan: left out");
      }
    }

    // Tags: the tapped groups' rows, the tapped ones, else genre / tag links outside the site's menus
    var genres = [];
    var seenTag = {};
    function plainTag(t) {
      t = clean(t);
      return !t || t.length > 40 || /^(genres?|categor(y|ies)|tags?|keywords?|more|all|\+\d+)$/i.test(t) ? "" : t;
    }
    function pushTag(t) {
      if (seenTag[t.toLowerCase()]) return;
      seenTag[t.toLowerCase()] = true;
      genres.push(t);
    }
    // Genres are joined with ", " and split again by the app: a comma inside a grouped value would cut it in two
    function noComma(t) { return t.replace(/\s*[,，]\s*/g, " ").trim(); }
    function addTag(t) {
      t = plainTag(t);
      if (t && tagGroups.length) t = noComma(t);
      if (t) pushTag(tagGroups.length ? "Tags: " + t : t);
    }
    var lines = [];
    for (var gi = 0; gi < tagGroups.length; gi++) {
      var group = tagGroups[gi];
      var groupName = noComma(clean(group.name).replace(/\s*[:：]$/, "")) || "Tags";
      var target = group.target === "description" || group.target === "both" ? group.target : "tags";
      var label = new RegExp("^" + groupName.replace(/[.*+?^${}()|[\]\\]/g, "\\$&") + "\\s*[:：]\\s*", "i");
      var values = [];
      var seenValue = {};
      try {
        (await doc.select(group.selector, { html: false })).forEach(function (el) {
          var t = clean(el.text).replace(label, "");
          if (!t || seenValue[t.toLowerCase()]) return;
          seenValue[t.toLowerCase()] = true;
          values.push(t);
        });
      } catch (e) { console.log("Tags group " + groupName + " wasn't read: " + e.message); }
      if (!values.length) continue;
      if (target !== "description") values.forEach(function (t) { var v = noComma(plainTag(t)); if (v) pushTag(groupName + ": " + v); });
      if (target !== "tags") lines.push(groupName + ": " + values.join(", "));
    }
    if (lines.length) description = description ? description + "\n\n" + lines.join("\n") : lines.join("\n");
    if (description) out.description = description;
    var fromGroups = genres.length;
    if (pickedTags) {
      try { (await doc.select(pickedTags, { html: false })).forEach(function (a) { addTag(a.text); }); } catch (e) { genres = genres.slice(0, fromGroups); }
    }
    if (!genres.length) {
      var links = await doc.select("a[href*='/genre'], a[href*='/tag'], a[href*='/categor'], a[rel~=tag]", { html: false });
      var menu = {};
      (await doc.select("nav a, header a, footer a, aside a, [class*=menu] a, [class*=nav] a", { html: false })).forEach(function (a) { menu[a.id] = true; });
      links.forEach(function (a) { if (!menu[a.id]) addTag(a.text); });
    }
    if (genres.length) out.genres = genres;

    // Author / status: only when tapped ("Author: X" / "Status: Ongoing" lose their label)
    var author = (await textOf(pickedAuthor)).replace(/^(authors?|artists?|by|cast|makers?|studios?)\s*[:：]\s*/i, "");
    if (author) out.author = author;
    var status = (await textOf(pickedStatus)).replace(/^status\s*[:：]?\s*/i, "");
    if (status) out.status = status;
    return out;
  },
/*@end*/

/*@part id="getChapterList"*/
  // Chapters / episodes: the CHAPTER links of the title page, each address once, the number from the text, the
  // date from CHAPTER_DATE inside or next to the link when one was picked. With SINGLE_EPISODE the title page is
  // the one episode: its own address, the h1 as title, number 1 (or EPISODE_NUMBER's), the date from CHAPTER_DATE.
  getChapterList: async function (url) {
    var doc = await host.parse(await get(url));
    if (SINGLE_EPISODE) {
      var when = CHAPTER_DATE ? (await doc.select(CHAPTER_DATE))[0] : null;
      var numberEl = EPISODE_NUMBER ? (await doc.select(EPISODE_NUMBER))[0] : null;
      var name = (await doc.text("h1") || await doc.attr("meta[property='og:title']", "content") || "Episode 1").trim();
      return [{
        url: url,
        title: name,
        number: (numberEl ? numberOf(numberEl.text) : null) || 1,
        dateUploaded: require("date").parse(when ? (when.attr("datetime") || when.attr("title") || when.text) : ""),
      }];
    }
    if (!CHAPTER) throw new Error("No chapter link was picked in Build by tapping: open a title and tap a chapter link, or say the title page is the only episode");
    var links = await doc.select(CHAPTER);
    var out = [];
    var seen = {};
    for (var i = 0; i < links.length; i++) {
      var a = links[i].tag === "a" ? links[i] : await links[i].selectOne("a");
      if (!a || !a.attr("href") || seen[a.attr("href")]) continue;
      seen[a.attr("href")] = true;
      var date = CHAPTER_DATE ? await links[i].selectOne(CHAPTER_DATE) : null;
      out.push({
        url: abs(a.attr("href")),
        title: a.attr("title") || a.text,
        number: numberOf(a.text || a.attr("href")),
        dateUploaded: require("date").parse(date ? (date.attr("datetime") || date.attr("title") || date.text) : ""),
      });
    }
    return out;
  },
/*@end*/

/*@part id="getPageList"*/
  // Pages: the IMAGE pictures of the chapter page, the address from IMAGE_ATTR (then the lazy-load attributes
  // and src), in page order.
  getPageList: async function (url) {
    var doc = await host.parse(await get(url));
    var imgs = await doc.select(IMAGE);
    var out = [];
    for (var i = 0; i < imgs.length; i++) {
      var src = imgs[i].attr(IMAGE_ATTR) || imgs[i].attr("data-src") || imgs[i].attr("data-lazy-src") || imgs[i].attr("src") || "";
      src = String(src).trim().split(/[\s,]+/)[0];
      if (src) out.push({ index: out.length, imageUrl: abs(src) });
    }
    return out;
  },
/*@end*/

/*@part id="getVideoList"*/
  // Videos: the app's video finder (host.findVideo) opens the episode page in the hidden browser, presses Play and
  // catches the stream the player asks for (.m3u8 / .mpd / .mp4), also when the site's script fills in the
  // player's address after the page loads; then the player itself is opened with the page as Referer. {{videoNote}}
  // The "quality" and "server" settings (when the source has them) put the preferred ones first.
  getVideoList: async function (url) {
    var found = await host.findVideo(url, { frame: VIDEO_FRAME, referer: BASE + "/" });
    var hls = require("hls");
    var out = [];
    for (var i = 0; i < (found.videos || []).length; i++) {
      var v = found.videos[i];
      var video = { url: v.url, quality: v.quality, server: v.server, headers: v.headers };
      out.push.apply(out, v.kind === "hls" ? await hls.expand(video) : [video]);
    }
    if (!out.length) throw new Error("No playable video on " + url + ": " + (found.reason || "nothing caught"));
    var wantQ = String((await host.preference.get("quality")) || "");
    var wantS = String((await host.preference.get("server")) || "").toLowerCase();
    if ((wantQ && wantQ !== "Best") || wantS) {
      out = out.map(function (v, i) {
        var score = (wantQ && wantQ !== "Best" && String(v.quality).indexOf(wantQ) >= 0 ? 2 : 0)
          + (wantS && String(v.server).toLowerCase().indexOf(wantS) >= 0 ? 1 : 0);
        return { v: v, i: i, s: score };
      }).sort(function (a, b) { return b.s - a.s || a.i - b.i; }).map(function (x) { return x.v; });
    }
    return out;
  },
/*@end*/
};

/*@part id="extras"*/
// Tabs, filters and settings from "Lists and options" in Build by tapping, each added only when some were recorded
// (a source without them has exactly the methods above): TABS become tab buttons at the top of Browse (getCatalogs /
// getCatalog: the tab's path with {page} filled, the same cards), FILTERS the filter sheet (search adds their
// values), SETTINGS the source's settings (the code reads them with host.preference.get("key")).
if (TABS.length) {
  extension.getCatalogs = function () {
    return TABS.map(function (t) { return { id: t.path, name: t.name || t.path, tab: true }; });
  };
  extension.getCatalog = async function (id, page) {
    return cards(await get(pathFor(id, page, "")));
  };
}
if (FILTERS.length) {
  extension.getFilters = function () {
    return FILTERS.map(function (f) {
      var out = { key: f.key, name: f.name || f.key, type: f.type };
      if (f.type === "select" || f.type === "sort") out.options = f.options || [];
      return out;
    });
  };
}
if (SETTINGS.length) {
  extension.getSourcePreferences = function () {
    return SETTINGS;
  };
}
/*@end*/

/*@part id="helpers"*/
// Helpers: one request with the site as Referer, the list path with {page} / {q} filled, a parameter set in an
// address, the ITEM card parser the lists share (titles with a "Hide titles containing" word left out when the
// source has that setting), absolute addresses and the last number of a text.
async function get(url, options) {
  var o = options || {};
  var headers = Object.assign({ Referer: BASE + "/" }, o.headers || {});
  var r = await host.fetch(url, Object.assign({}, o, { headers: headers }));
  if (r.statusCode >= 400) throw new Error("The site answered " + r.statusCode + " for " + url);
  return r.body || "";
}
function pathFor(path, page, query) {
  var p = String(path).split("{page}").join(String(page)).split("{q}").join(encodeURIComponent(query));
  if (page === 1) p = p.replace(/\/page\/1\/?$/, "/").replace(/([?&])page=1(&|$)/, "$1").replace(/[?&]$/, "");
  return /^https?:/.test(p) ? p : BASE + (p.charAt(0) === "/" ? "" : "/") + p;
}
// r101 (v4): the value a filter sends to search(): a dropdown's option value, a sort's picked option ({ index, value,
// ascending } from the app) value, a ticked checkbox's "on" value, a text's words; "" = not set
function filterValue(f, v) {
  if (v === undefined || v === null) return "";
  if (f.type === "checkbox") return v === true || v === "true" ? String(f.on || "1") : "";
  if (f.type === "sort") {
    if (typeof v === "object") {
      var opt = (f.options || [])[v.index];
      v = v.value !== undefined && v.value !== null && v.value !== "" ? v.value : (opt ? opt.value : "");
    }
    return v === undefined || v === null ? "" : String(v).trim();
  }
  if (typeof v === "object") return "";
  return String(v).trim();
}
function withParam(url, key, value) {
  var at = url.indexOf("#");
  var hash = at >= 0 ? url.slice(at) : "";
  var base = at >= 0 ? url.slice(0, at) : url;
  var k = encodeURIComponent(key);
  var pair = k + "=" + encodeURIComponent(value);
  var re = new RegExp("([?&])" + k.replace(/[.*+?^$()|[\]\\]/g, "\\$&") + "=[^&]*");
  if (re.test(base)) return base.replace(re, function (m, sep) { return sep + pair; }) + hash;
  var joiner = base.indexOf("?") < 0 ? "?" : (/[?&]$/.test(base) ? "" : "&");
  return base + joiner + pair + hash;
}
async function cards(html) {
  var doc = await host.parse(html);
  var items = await doc.select(ITEM);
  var hide = !HIDE_WORDS ? [] : String((await host.preference.get("hideWords")) || "").toLowerCase().split(",")
    .map(function (w) { return w.trim(); }).filter(function (w) { return w; });
  var out = [];
  for (var i = 0; i < items.length; i++) {
    var t = TITLE ? await items[i].selectOne(TITLE) : null;
    var a = t && t.tag === "a" ? t : (await items[i].selectOne("a[href]")) || (items[i].tag === "a" ? items[i] : null);
    if (!a || !a.attr("href")) continue;
    var img = COVER ? await items[i].selectOne(COVER) : await items[i].selectOne("img");
    var src = img ? (img.attr(COVER_ATTR) || img.attr("data-src") || img.attr("data-lazy-src") || img.attr("src") || "") : "";
    var name = (t ? (t.text || t.attr("title")) : "") || a.attr("title") || a.text || (img ? img.attr("alt") : "");
    var lower = String(name || "").toLowerCase();
    if (hide.length && hide.some(function (w) { return lower.indexOf(w) >= 0; })) continue;
    out.push({
      url: abs(a.attr("href")),
      title: name,
      coverUrl: src ? abs(String(src).trim().split(/[\s,]+/)[0]) : null,
    });
  }
  return out;
}
function abs(href) {
  return href ? require("url").resolve(BASE, String(href).trim()) : null;
}
function numberOf(text) {
  var m = String(text || "").match(/(\d+(?:[.,]\d+)?)(?!.*\d)/);
  return m ? parseFloat(m[1].replace(",", ".")) : null;
}
/*@end*/
