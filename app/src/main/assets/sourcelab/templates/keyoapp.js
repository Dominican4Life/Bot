// @version 1
// Keyoapp template of the Source Lab, written for {{name}} ({{baseUrl}}).
// Keyoapp sites list their titles as /series/ links whose covers are CSS background images; chapters are the
// links of #chapters on a title; the reader prints its pictures as <img> with a uid attribute served from the
// Keyoapp CDN. Pictures refused without a Referer at detection: {{imageReferer}}.

/*@part id="headers"*/
// Headers: the site as Referer on every picture, which the Keyoapp CDN checks.
var BASE = "{{baseUrl}}";
var extension = {
  name: "{{name}}",
  lang: "{{lang}}",
  baseUrl: BASE,
  iconUrl: "{{iconUrl}}",
  headers: { Referer: BASE + "/" },
/*@end*/

/*@part id="getPopular"*/
  // Popular: the home page's grid of /series/ links (the theme shows one page; later pages are empty).
  getPopular: async function (page) {
    if (page > 1) return [];
    return cards(await get(BASE + "/"), "div.grid > div:has(a[href*='/series/']), a[href*='/series/']");
  },
/*@end*/

/*@part id="getLatest"*/
  // Latest: /latest/ when the site has it, else the home page again.
  getLatest: async function (page) {
    if (page > 1) return [];
    var r = await host.fetch(BASE + "/latest/", { headers: { Referer: BASE + "/" } });
    var html = r.statusCode < 400 ? (r.body || "") : await get(BASE + "/");
    return cards(html, "div.grid > div:has(a[href*='/series/']), a[href*='/series/']");
  },
/*@end*/

/*@part id="search"*/
  // Search: the /series/ directory, filtered here by the words (the theme searches in the browser).
  search: async function (query, page, filters) {
    if (page > 1) return [];
    var q = String(query || "").toLowerCase();
    var all = await cards(await get(BASE + "/series/"), "div.grid > div:has(a[href*='/series/']), a[href*='/series/']");
    return all.filter(function (e) { return !q || String(e.title || "").toLowerCase().indexOf(q) >= 0; });
  },
/*@end*/

/*@part id="getDetails"*/
  // Title page: the h1, the cover's background image, the description paragraph, author / status / genre tags.
  getDetails: async function (url) {
    var html = await get(url);
    var doc = await host.parse(html);
    var coverEl = await doc.selectOne("div[style*='background-image'], img[alt]");
    return {
      url: url,
      title: (await doc.text("h1") || "").trim(),
      coverUrl: coverEl ? backgroundOf(coverEl) : null,
      description: await doc.text("div:has(> h2:contains(Description)) p, p.text-neutral-400, .description"),
      genres: (await doc.select("a[href*='/genre/'], a[href*='/tag/']")).map(function (a) { return a.text; }),
      status: (html.match(/\b(Ongoing|Completed|Hiatus|Dropped|Cancelled)\b/) || [])[1] || null,
      webUrl: url,
    };
  },
/*@end*/

/*@part id="getChapterList"*/
  // Chapters: the #chapters links (or every /chapter/ link) of the title page, newest first; the number from
  // the link text, the date from the <span> next to it.
  getChapterList: async function (url) {
    var doc = await host.parse(await get(url));
    var links = await doc.select("#chapters a[href*='/chapter/'], a[href*='/chapter/']");
    var out = [];
    var seen = {};
    for (var i = 0; i < links.length; i++) {
      var href = links[i].attr("href");
      if (!href || seen[href]) continue;
      seen[href] = true;
      var date = await links[i].selectOne("span:last-child, time");
      var title = (await links[i].selectOne("span:first-child, h3")) || null;
      var text = title ? title.text : links[i].text;
      out.push({
        url: abs(href),
        title: text,
        number: numberOf(text),
        dateUploaded: require("date").parse(date ? date.text : ""),
      });
    }
    return out;
  },
/*@end*/

/*@part id="getPageList"*/
  // Pages: the reader's <img> with a uid attribute (served as https://cdn.keyoapp.com/<uid>.jpg); plain src
  // images when the site doesn't use uids.
  getPageList: async function (url) {
    var doc = await host.parse(await get(url));
    var imgs = await doc.select("#pages img, main img, img[uid]");
    var out = [];
    for (var i = 0; i < imgs.length; i++) {
      var uid = imgs[i].attr("uid");
      var src = uid ? "https://cdn.keyoapp.com/" + uid : (imgs[i].attr("data-src") || imgs[i].attr("src") || "");
      src = String(src).trim();
      if (src && !/logo|icon|avatar/i.test(src)) out.push({ index: out.length, imageUrl: abs(src) });
    }
    return out;
  },
/*@end*/
};

/*@part id="helpers"*/
// Helpers: one request with the site as Referer, the card parser (title from the link's text or an inner
// heading, the cover from a CSS background image or an <img>), absolute addresses and the first number.
async function get(url, options) {
  var o = options || {};
  var headers = Object.assign({ Referer: BASE + "/" }, o.headers || {});
  var r = await host.fetch(url, Object.assign({}, o, { headers: headers }));
  if (r.statusCode >= 400) throw new Error("The site answered " + r.statusCode + " for " + url);
  return r.body || "";
}
async function cards(html, selector) {
  var doc = await host.parse(html);
  var els = await doc.select(selector);
  var out = [];
  var seen = {};
  for (var i = 0; i < els.length; i++) {
    var a = els[i].tag === "a" ? els[i] : await els[i].selectOne("a[href*='/series/']");
    if (!a || !a.attr("href") || seen[a.attr("href")]) continue;
    seen[a.attr("href")] = true;
    var heading = await els[i].selectOne("h2, h3, .title, span");
    var bg = await els[i].selectOne("[style*='background-image']");
    var img = await els[i].selectOne("img");
    out.push({
      url: abs(a.attr("href")),
      title: (heading ? heading.text : "") || a.attr("title") || a.text,
      coverUrl: bg ? backgroundOf(bg) : (img ? abs(img.attr("data-src") || img.attr("src")) : null),
    });
  }
  return out;
}
function backgroundOf(el) {
  var m = String(el.attr("style") || "").match(/url\((['"]?)(.*?)\1\)/);
  if (m) return abs(m[2]);
  return el.tag === "img" ? abs(el.attr("src")) : null;
}
function abs(href) {
  return href ? require("url").resolve(BASE, String(href).trim()) : null;
}
function numberOf(text) {
  var m = String(text || "").match(/(\d+(?:[.,]\d+)?)/);
  return m ? parseFloat(m[1].replace(",", ".")) : null;
}
/*@end*/
