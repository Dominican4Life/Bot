// @version 1
// FoolSlide template of the Source Lab, written for {{name}} ({{baseUrl}}).
// FoolSlide has no popular list: the /directory/N/ pages stand in for Popular, /latest/N/ for Latest and
// /search/ (a POST with the words) for search. Chapters are the .element rows of a /series/ page; the reader
// prints its pictures in a `var pages = [...]` script.

/*@part id="headers"*/
// Headers and constants: the site as Referer on every picture; COVER_ATTR the <img> attribute of the covers.
var BASE = "{{baseUrl}}";
var COVER_ATTR = "{{coverAttr}}";
var extension = {
  name: "{{name}}",
  lang: "{{lang}}",
  baseUrl: BASE,
  iconUrl: "{{iconUrl}}",
  headers: { Referer: BASE + "/" },
/*@end*/

/*@part id="getPopular"*/
  // Popular: FoolSlide has no popularity order, so this is the alphabetical /directory/N/ (the .group blocks,
  // each with .title a and a cover image).
  getPopular: async function (page) {
    return groups(await get(BASE + "/directory/" + page + "/"));
  },
/*@end*/

/*@part id="getLatest"*/
  // Latest: /latest/N/, where every .group is a series with its newest chapters under it.
  getLatest: async function (page) {
    return groups(await get(BASE + "/latest/" + page + "/"));
  },
/*@end*/

/*@part id="search"*/
  // Search: POST /search/ with search=words (one page; FoolSlide does not page its search).
  search: async function (query, page, filters) {
    if (page > 1) return [];
    var html = await get(BASE + "/search/", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: "search=" + encodeURIComponent(query || ""),
    });
    return groups(html);
  },
/*@end*/

/*@part id="getDetails"*/
  // Title page: .large.comic: h1.title, .thumbnail img, .info text (Author / Artist / Synopsis lines).
  getDetails: async function (url) {
    var doc = await host.parse(await get(url));
    var info = (await doc.text(".large.comic .info, .info") || "");
    var author = (info.match(/Author\s*:\s*([^\n]+)/) || [])[1];
    var synopsis = (info.match(/Synopsis\s*:\s*([\s\S]+)/) || [])[1];
    return {
      url: url,
      title: (await doc.text(".large.comic .title, h1.title") || "").trim(),
      coverUrl: cover(await doc.selectOne(".large.comic .thumbnail img, .thumbnail img")),
      description: synopsis ? synopsis.trim() : null,
      author: author ? author.trim() : null,
      webUrl: url,
    };
  },
/*@end*/

/*@part id="getChapterList"*/
  // Chapters: the .list .element rows of the series page: .title a is the link and name, .meta_r holds the
  // group and the date after the comma. The number is read from the address (/read/<slug>/<lang>/<vol>/<ch>/).
  getChapterList: async function (url) {
    var doc = await host.parse(await get(url));
    var rows = await doc.select(".list .element");
    var out = [];
    for (var i = 0; i < rows.length; i++) {
      var a = await rows[i].selectOne(".title a");
      if (!a || !a.attr("href")) continue;
      var meta = await rows[i].selectOne(".meta_r");
      var metaText = meta ? meta.text : "";
      var href = a.attr("href");
      var m = href.match(/\/read\/[^/]+\/[^/]+\/(\d+)\/(\d+)(?:\/(\d+))?/);
      var number = m ? parseFloat(m[2] + (m[3] ? "." + m[3] : "")) : numberOf(a.text);
      out.push({
        url: abs(href),
        title: a.text,
        number: number,
        scanlator: metaText.split(",")[0].replace(/^by\s+/i, "").trim() || null,
        dateUploaded: require("date").parse(metaText.split(",").slice(1).join(",").trim()),
      });
    }
    return out;
  },
/*@end*/

/*@part id="getPageList"*/
  // Pages: the reader's `var pages = [...]` script (each entry has url); older readers print <img> tags in
  // #page instead.
  getPageList: async function (url) {
    var html = await get(url);
    var m = html.match(/var\s+pages\s*=\s*(\[[\s\S]*?\]);/);
    if (m) {
      try {
        return JSON.parse(m[1]).map(function (p, i) { return { index: i, imageUrl: abs(p.url || p) }; });
      } catch (e) {
        console.log("pages script could not be read: " + e.message);
      }
    }
    var doc = await host.parse(html);
    var imgs = await doc.select("#page img, .inner img");
    return imgs.map(function (img, i) { return { index: i, imageUrl: abs(img.attr("src")) }; });
  },
/*@end*/
};

/*@part id="helpers"*/
// Helpers: one request with the site as Referer, the .group parser the lists share, absolute addresses, the
// cover attribute chain and the first number in a text.
async function get(url, options) {
  var o = options || {};
  var headers = Object.assign({ Referer: BASE + "/" }, o.headers || {});
  var r = await host.fetch(url, Object.assign({}, o, { headers: headers }));
  if (r.statusCode >= 400) throw new Error("The site answered " + r.statusCode + " for " + url);
  return r.body || "";
}
async function groups(html) {
  var doc = await host.parse(html);
  var cards = await doc.select(".list .group, .group");
  var out = [];
  var seen = {};
  for (var i = 0; i < cards.length; i++) {
    var a = await cards[i].selectOne(".title a");
    if (!a || !a.attr("href") || seen[a.attr("href")]) continue;
    seen[a.attr("href")] = true;
    out.push({ url: abs(a.attr("href")), title: a.text, coverUrl: cover(await cards[i].selectOne("img")) });
  }
  return out;
}
function abs(href) {
  return href ? require("url").resolve(BASE, String(href).trim()) : null;
}
function cover(img) {
  if (!img) return null;
  var v = img.attr(COVER_ATTR) || img.attr("src") || img.attr("data-src") || "";
  v = String(v).trim().split(/[\s,]+/)[0];
  return v ? abs(v) : null;
}
function numberOf(text) {
  var m = String(text || "").match(/(\d+(?:[.,]\d+)?)/);
  return m ? parseFloat(m[1].replace(",", ".")) : null;
}
/*@end*/
