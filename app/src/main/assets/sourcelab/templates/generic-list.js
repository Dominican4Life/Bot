// @version 3
// Generic list template of the Source Lab, written for {{name}} ({{baseUrl}}) from what was tapped in Build by
// tapping (or filled by hand). Lists are pages of ITEM cards, chapters / episodes are the links of CHAPTER on a
// title page, pages are the IMAGE pictures of a chapter page, videos the VIDEO_FRAME of an episode page.
// r100 (v3): videos come from the app's video finder (host.findVideo); the tabs, filters and settings recorded in
// "Lists and options" of Build by tapping are added only when there are some (part "extras").

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
// r100: "Lists and options" of Build by tapping: the names of the Popular / Latest buttons (empty = the app's own),
// extra tabs [{ name, path }], search filters [{ key, name, type, options, param, on }] and settings (the
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
  // Search: SEARCH_PATH with {q} and {page} filled, the same cards. Each FILTERS entry adds its value to the address
  // as param=value (a dropdown: the picked option's value; a checkbox: its "on" value when ticked; a text: when not
  // blank; empty values are left out), replacing that parameter when the path already has it.
  search: async function (query, page, filters) {
    var url = pathFor(SEARCH_PATH, page, query || "");
    for (var i = 0; i < FILTERS.length; i++) {
      var f = FILTERS[i];
      var v = filters ? filters[f.key] : undefined;
      if (f.type === "checkbox") v = v === true || v === "true" ? String(f.on || "1") : "";
      else v = v === undefined || v === null ? "" : String(v).trim();
      if (v) url = withParam(url, f.param || f.key, v);
    }
    return cards(await get(url));
  },
/*@end*/

/*@part id="getDetails"*/
  // Title page: the h1 (or og:title), og:image as the cover, the meta description, genre-looking links.
  getDetails: async function (url) {
    var doc = await host.parse(await get(url));
    return {
      url: url,
      title: (await doc.text("h1") || await doc.attr("meta[property='og:title']", "content") || "").trim(),
      coverUrl: abs(await doc.attr("meta[property='og:image']", "content")),
      description: await doc.attr("meta[property='og:description']", "content") || await doc.attr("meta[name='description']", "content"),
      genres: (await doc.select("a[href*='/genre'], a[href*='/tag'], a[rel~=tag]")).map(function (a) { return a.text; }),
      webUrl: url,
    };
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
      if (f.type === "select") out.options = f.options || [];
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
