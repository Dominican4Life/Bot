// @version 1
// Anime player page template of the Source Lab, written for {{name}} ({{baseUrl}}).
// The home page links to titles (/anime/…) and to episodes (/watch/… or …-episode-N); an episode page holds the
// player in an <{{frameSelector}}>. Known embed hosts (StreamWish, Filemoon, Vidstream / Vidsrc, a plain <source>)
// are read with the hidden browser catching the .m3u8 the player loads; any other host is a TODO.

/*@part id="headers"*/
// Headers and constants: the site as Referer on covers; COVER_ATTR the <img> attribute that held a real
// address; FRAME the element that holds the player on an episode page.
var BASE = "{{baseUrl}}";
var COVER_ATTR = "{{coverAttr}}";
var FRAME = "{{frameSelector}}";
var KNOWN_HOSTS = ["streamwish", "filemoon", "vidstream", "vidsrc", "wish", "moon", "dood", "mp4upload", "vidhide", "voe"];
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
  // Videos: the episode page's player frame(s). A plain <video><source> is a direct file; a known embed host is
  // loaded in the hidden browser and the .m3u8 / .mp4 it asks for is the video (the embed page as Referer).
  // An unknown host is reported by name so the Lab can show which extractor is missing.
  getVideoList: async function (url) {
    var doc = await host.parse(await get(url));
    var frames = await doc.select(FRAME + "[src], iframe[data-src], video source[src], video[src]");
    var out = [];
    for (var i = 0; i < frames.length && out.length < 6; i++) {
      var src = frames[i].attr("src") || frames[i].attr("data-src");
      if (!src || /about:blank|ads|banner/i.test(src)) continue;
      src = abs(src);
      if (frames[i].tag === "source" || frames[i].tag === "video") {
        out.push({ url: src, quality: frames[i].attr("label") || frames[i].attr("size") || "Direct", server: "Direct", headers: { Referer: BASE + "/" } });
        continue;
      }
      var hostName = (src.match(/^https?:\/\/([^/]+)/) || [])[1] || "";
      var known = KNOWN_HOSTS.filter(function (k) { return hostName.indexOf(k) >= 0; }).length > 0;
      var videos = await extract(src, hostName);
      if (videos.length) out.push.apply(out, videos);
      else if (!known) console.log("Unknown player host " + hostName + ": no video caught");
    }
    if (!out.length) throw new Error("No playable video on " + url + " (player host not recognised)");
    return out;
  },
/*@end*/
};

/*@part id="helpers"*/
// Helpers: one request with the site as Referer; the title-card parser (links under /anime/ or with a poster,
// each once); the embed extractor (hidden browser, .m3u8 / .mp4 caught, the embed as Referer, "Auto" when it is
// a master playlist); absolute addresses and the first number of a text.
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
async function extract(embedUrl, hostName) {
  var page = await host.render(embedUrl, { capture: /\.m3u8|\.mp4|\.mkv/i, waitForResource: /\.m3u8|\.mp4/i, timeout: 25000 });
  var urls = (page.resources || []).filter(function (u) { return /\.m3u8|\.mp4|\.mkv/i.test(u); });
  var seen = {};
  var out = [];
  var hls = require("hls");
  for (var i = 0; i < urls.length; i++) {
    var u = urls[i].replace(/#.*$/, "");
    if (seen[u]) continue;
    seen[u] = true;
    var video = {
      url: u,
      quality: /\.m3u8/i.test(u) ? "Auto" : "Direct",
      server: hostName.split(".")[0],
      headers: { Referer: embedUrl, Origin: embedUrl.replace(/^(https?:\/\/[^/]+).*$/, "$1") },
    };
    // A master playlist becomes "Auto" plus one video per quality, all under the same server
    var expanded = /\.m3u8/i.test(u) ? await hls.expand(video) : [video];
    out.push.apply(out, expanded);
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
