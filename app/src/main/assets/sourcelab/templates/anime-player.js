// @version 2
// Anime player page template of the Source Lab, written for {{name}} ({{baseUrl}}).
// The home page links to titles (/anime/…) and to episodes (/watch/… or …-episode-N); an episode page holds the
// player in an <{{frameSelector}}>. Videos come from the app's video finder (host.findVideo): the hidden browser
// plays the episode page (and then the player frame itself) and catches the .m3u8 / .mpd / .mp4 it asks for.

/*@part id="headers"*/
// Headers and constants: the site as Referer on covers; COVER_ATTR the <img> attribute that held a real
// address; FRAME the element that holds the player on an episode page (the video finder starts there).
var BASE = "{{baseUrl}}";
var COVER_ATTR = "{{coverAttr}}";
var FRAME = "{{frameSelector}}";
var extension = {
  name: "{{name}}",
  lang: "{{lang}}",
  baseUrl: BASE,
  iconUrl: "{{iconUrl}}",
  headers: { Referer: BASE + "/" },
/*@end*/

/*@part id="getPopular"*/
  // Popular: the home page's title links (/anime/…, .film-poster cards) — one page; sites with a /popular
  // page can point this at it.
  getPopular: async function (page) {
    if (page > 1) return [];
    return titles(await get(BASE + "/"));
  },
/*@end*/

/*@part id="getLatest"*/
  // Latest: the home page's episode links (/watch/…, …-episode-N), folded back to their title.
  getLatest: async function (page) {
    if (page > 1) return [];
    return titles(await get(BASE + "/"));
  },
/*@end*/

/*@part id="search"*/
  // Search: /search?keyword=words (TODO: some sites use /search?q= or /?s=), the same title cards.
  search: async function (query, page, filters) {
    var html = await get(BASE + "/search?keyword=" + encodeURIComponent(query || "") + "&page=" + page);
    return titles(html);
  },
/*@end*/

/*@part id="getDetails"*/
  // Title page: the h1 (or og:title), og:image as the cover, og:description, genre links.
  getDetails: async function (url) {
    var doc = await host.parse(await get(url));
    return {
      url: url,
      title: (await doc.text("h1, h2.film-name") || await doc.attr("meta[property='og:title']", "content") || "").trim(),
      coverUrl: abs(await doc.attr("meta[property='og:image']", "content") || await doc.attr(".film-poster img, .poster img", COVER_ATTR)),
      description: await doc.text(".film-description .text, .description, .synopsis") || await doc.attr("meta[property='og:description']", "content"),
      genres: (await doc.select("a[href*='/genre/']")).map(function (a) { return a.text; }),
      webUrl: url,
    };
  },
/*@end*/

/*@part id="getChapterList"*/
  // Episodes: every episode link of the title page (…/episode…, …/watch/…, …-episode-N), each address once,
  // the number from the link's data-number / text / address; returned oldest first.
  getChapterList: async function (url) {
    var doc = await host.parse(await get(url));
    var links = await doc.select("a[href*='/episode'], a[href*='/watch/'], a[href*='-episode-'], .ep-item, .episode a");
    var out = [];
    var seen = {};
    for (var i = 0; i < links.length; i++) {
      var href = links[i].attr("href") || links[i].attr("data-href");
      if (!href || seen[href]) continue;
      seen[href] = true;
      var text = links[i].attr("title") || links[i].text;
      var number = numberOf(links[i].attr("data-number") || links[i].attr("data-episode") || text || href.replace(/.*episode-?/, ""));
      out.push({ url: abs(href), title: text || ("Episode " + number), number: number });
    }
    out.sort(function (a, b) { return (a.number || 0) - (b.number || 0); });
    return out;
  },
/*@end*/

/*@part id="getVideoList"*/
  // Videos: the app's video finder (host.findVideo) opens the episode page in the hidden browser, presses Play and
  // catches the stream the player asks for (.m3u8 / .mpd / .mp4), also when the site's script fills in the
  // player's address after the page loads; then the player itself is opened with the page as Referer. {{videoNote}}
  // The "quality" and "server" settings (when the source has them) put the preferred ones first.
  getVideoList: async function (url) {
    var found = await host.findVideo(url, { frame: FRAME, referer: BASE + "/" });
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

/*@part id="helpers"*/
// Helpers: one request with the site as Referer; the title-card parser (links under /anime/ or with a poster,
// each once); absolute addresses and the first number of a text.
async function get(url, options) {
  var o = options || {};
  var headers = Object.assign({ Referer: BASE + "/" }, o.headers || {});
  var r = await host.fetch(url, Object.assign({}, o, { headers: headers }));
  if (r.statusCode >= 400) throw new Error("The site answered " + r.statusCode + " for " + url);
  return r.body || "";
}
async function titles(html) {
  var doc = await host.parse(html);
  var links = await doc.select("a[href*='/anime/'], .film-poster a, .film-name a, a[href*='/watch/']:has(img)");
  var out = [];
  var seen = {};
  for (var i = 0; i < links.length; i++) {
    var href = (links[i].attr("href") || "").replace(/[?#].*$/, "").replace(/-episode-\d+\/?$/, "").replace(/\/(ep|episode)[-/]\d+\/?$/, "");
    if (!href || seen[href]) continue;
    seen[href] = true;
    var img = await links[i].selectOne("img");
    var title = links[i].attr("title") || links[i].attr("data-jname") || (img ? img.attr("alt") : "") || links[i].text;
    if (!title) continue;
    var src = img ? (img.attr(COVER_ATTR) || img.attr("data-src") || img.attr("src") || "") : "";
    out.push({ url: abs(href), title: title.trim(), coverUrl: src ? abs(String(src).trim().split(/[\s,]+/)[0]) : null });
  }
  return out;
}
function abs(href) {
  return href ? require("url").resolve(BASE, String(href).trim()) : null;
}
function numberOf(text) {
  var m = String(text || "").match(/(\d+(?:[.,]\d+)?)/);
  return m ? parseFloat(m[1].replace(",", ".")) : null;
}
/*@end*/
