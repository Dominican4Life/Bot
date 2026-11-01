// @version 2
// Generic list template of the Source Lab, written for {{name}} ({{baseUrl}}) from what was tapped in Build by
// tapping (or filled by hand). Lists are pages of ITEM cards, chapters / episodes are the links of CHAPTER on a
// title page, pages are the IMAGE pictures of a chapter page, videos the VIDEO_FRAME of an episode page.

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
var extension = {
  name: "{{name}}",
  lang: "{{lang}}",
  baseUrl: BASE,
  iconUrl: "{{iconUrl}}",
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
  // Search: SEARCH_PATH with {q} and {page} filled, the same cards.
  search: async function (query, page, filters) {
    return cards(await get(pathFor(SEARCH_PATH, page, query || "")));
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
  // Videos: the VIDEO_FRAME of the episode page. {{videoNote}}
  // A plain <video><source> is a direct file; an embed is loaded in the hidden browser and the .m3u8 / .mp4 it
  // asks for is the video (the embed page as Referer, every video under its host's server group).
  getVideoList: async function (url) {
    var doc = await host.parse(await get(url));
    var frames = await doc.select(VIDEO_FRAME + ", video source[src], video[src]");
    var out = [];
    for (var i = 0; i < frames.length && out.length < 6; i++) {
      var src = frames[i].attr("src") || frames[i].attr("data-src");
      if (!src || /about:blank/.test(src)) continue;
      src = abs(src);
      if (frames[i].tag === "source" || frames[i].tag === "video") {
        out.push({ url: src, quality: frames[i].attr("label") || "Direct", server: "Direct", headers: { Referer: BASE + "/" } });
        continue;
      }
      var hostName = (src.match(/^https?:\/\/([^/]+)/) || [])[1] || "embed";
      var page = await host.render(src, { capture: /\.m3u8|\.mp4|\.mkv/i, waitForResource: /\.m3u8|\.mp4/i, timeout: 25000 });
      var urls = (page.resources || []).filter(function (u) { return /\.m3u8|\.mp4|\.mkv/i.test(u); });
      var seen = {};
      for (var j = 0; j < urls.length; j++) {
        var u = urls[j].replace(/#.*$/, "");
        if (seen[u]) continue;
        seen[u] = true;
        var video = { url: u, quality: /\.m3u8/i.test(u) ? "Auto" : "Direct", server: hostName.split(".")[0], headers: { Referer: src } };
        var expanded = /\.m3u8/i.test(u) ? await require("hls").expand(video) : [video];
        out.push.apply(out, expanded);
      }
    }
    if (!out.length) throw new Error("No playable video on " + url + " (player host not recognised)");
    return out;
  },
/*@end*/
};

/*@part id="helpers"*/
// Helpers: one request with the site as Referer, the list path with {page} / {q} filled, the ITEM card parser
// the three lists share, absolute addresses and the first number of a text.
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
async function cards(html) {
  var doc = await host.parse(html);
  var items = await doc.select(ITEM);
  var out = [];
  for (var i = 0; i < items.length; i++) {
    var t = TITLE ? await items[i].selectOne(TITLE) : null;
    var a = t && t.tag === "a" ? t : (await items[i].selectOne("a[href]")) || (items[i].tag === "a" ? items[i] : null);
    if (!a || !a.attr("href")) continue;
    var img = COVER ? await items[i].selectOne(COVER) : await items[i].selectOne("img");
    var src = img ? (img.attr(COVER_ATTR) || img.attr("data-src") || img.attr("data-lazy-src") || img.attr("src") || "") : "";
    out.push({
      url: abs(a.attr("href")),
      title: (t ? (t.text || t.attr("title")) : "") || a.attr("title") || a.text || (img ? img.attr("alt") : ""),
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
