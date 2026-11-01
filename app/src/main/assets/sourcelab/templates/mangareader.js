// @version 1
// MangaReader (mangareader.to layout) template of the Source Lab, written for {{name}} ({{baseUrl}}).
// Lists are /most-viewed, /latest-updated and /search pages of .manga-poster cards; chapters come from the
// site's /ajax/manga/reading-list/<id> reply; pages from /ajax/image/list/chap/<id> (a JSON with HTML inside).
// Pictures refused without a Referer at detection: {{imageReferer}}.

/*@part id="headers"*/
// Headers and constants: the site as Referer on every picture; COVER_ATTR the <img> attribute that held a real
// address on the home page.
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
  // Popular: /most-viewed?page=N, the .manga_list-sbs .manga-poster cards with the title in .manga-name a.
  getPopular: async function (page) {
    return list(BASE + "/most-viewed?page=" + page);
  },
/*@end*/

/*@part id="getLatest"*/
  // Latest: /latest-updated?page=N, the same cards.
  getLatest: async function (page) {
    return list(BASE + "/latest-updated?page=" + page);
  },
/*@end*/

/*@part id="search"*/
  // Search: /search?keyword=words&page=N.
  search: async function (query, page, filters) {
    return list(BASE + "/search?keyword=" + encodeURIComponent(query || "") + "&page=" + page);
  },
/*@end*/

/*@part id="getDetails"*/
  // Title page: .manga-name, the poster image, .description, the .anisc-info rows (Authors, Status), genres.
  getDetails: async function (url) {
    var doc = await host.parse(await get(url));
    var status = await doc.text(".anisc-info .item:contains(Status) .name");
    var authors = await doc.select(".anisc-info .item:contains(Authors) a");
    return {
      url: url,
      title: (await doc.text(".manga-name, h2.manga-name") || "").trim(),
      coverUrl: cover(await doc.selectOne(".manga-poster img")),
      description: await doc.text(".description"),
      author: authors.map(function (a) { return a.text; }).join(", ") || null,
      genres: (await doc.select(".genres a")).map(function (a) { return a.text; }),
      status: status ? status.trim() : null,
      webUrl: url,
    };
  },
/*@end*/

/*@part id="getChapterList"*/
  // Chapters: the title's numeric id (data-id on #ani_detail or the end of the address, title-123), then the
  // /ajax/manga/reading-list/<id> reply, whose "html" holds the .chapter-item rows per language. Rows of the
  // source's language come first; number from data-number, date is not given by this layout.
  getChapterList: async function (url) {
    var html = await get(url);
    var id = (html.match(/data-id="(\d+)"/) || url.match(/-(\d+)\/?$/) || [])[1];
    var rows;
    if (id) {
      var reply = await get(BASE + "/ajax/manga/reading-list/" + id + "?readingBy=chap", {
        headers: { "X-Requested-With": "XMLHttpRequest" },
      });
      var json = {};
      try { json = JSON.parse(reply); } catch (e) { json = {}; }
      rows = await (await host.parse(json.html || "")).select(".chapters-list-ul .chapter-item, #" + "{{lang}}" + "-chapters .chapter-item, .chapter-item");
    } else {
      rows = await (await host.parse(html)).select("#list-chapter .chapter-item, .chapter-item");
    }
    var out = [];
    for (var i = 0; i < rows.length; i++) {
      var a = await rows[i].selectOne("a");
      if (!a || !a.attr("href")) continue;
      out.push({
        url: abs(a.attr("href")),
        title: a.attr("title") || a.text,
        number: numberOf(rows[i].attr("data-number") || a.text),
      });
    }
    return out;
  },
/*@end*/

/*@part id="getPageList"*/
  // Pages: the chapter's numeric id (data-reading-id on the reader page), then /ajax/image/list/chap/<id>: the
  // reply's "html" lists .iv-card elements whose data-url is the picture. Shuffled pictures (.shuffled) need the
  // site's own unscrambling, which this template does not do (they are returned as they come).
  getPageList: async function (url) {
    var html = await get(url);
    var id = (html.match(/data-reading-id="(\d+)"/) || html.match(/readingId\s*=\s*"?(\d+)/) || [])[1];
    if (!id) throw new Error("No reading id on the chapter page: the layout may have changed");
    var reply = await get(BASE + "/ajax/image/list/chap/" + id + "?mode=vertical&quality=high", {
      headers: { "X-Requested-With": "XMLHttpRequest" },
    });
    var json = {};
    try { json = JSON.parse(reply); } catch (e) { json = {}; }
    var cards = await (await host.parse(json.html || "")).select(".iv-card");
    var out = [];
    for (var i = 0; i < cards.length; i++) {
      var src = (cards[i].attr("data-url") || "").trim();
      if (src) out.push({ index: out.length, imageUrl: abs(src) });
    }
    return out;
  },
/*@end*/
};

/*@part id="helpers"*/
// Helpers: one request with the site as Referer, the .manga-poster card parser the lists share, absolute
// addresses, the cover attribute chain and the first number in a text.
async function get(url, options) {
  var o = options || {};
  var headers = Object.assign({ Referer: BASE + "/" }, o.headers || {});
  var r = await host.fetch(url, Object.assign({}, o, { headers: headers }));
  if (r.statusCode >= 400) throw new Error("The site answered " + r.statusCode + " for " + url);
  return r.body || "";
}
async function list(url) {
  var doc = await host.parse(await get(url));
  var cards = await doc.select(".manga_list-sbs .item, .mls-wrap .item, .item:has(.manga-poster)");
  var out = [];
  for (var i = 0; i < cards.length; i++) {
    var a = await cards[i].selectOne(".manga-name a, a.manga-poster, a");
    if (!a || !a.attr("href")) continue;
    var name = await cards[i].selectOne(".manga-name");
    out.push({ url: abs(a.attr("href")), title: name ? name.text : (a.attr("title") || a.text), coverUrl: cover(await cards[i].selectOne("img")) });
  }
  return out;
}
function abs(href) {
  return href ? require("url").resolve(BASE, String(href).trim()) : null;
}
function cover(img) {
  if (!img) return null;
  var v = img.attr(COVER_ATTR) || img.attr("data-src") || img.attr("src") || "";
  v = String(v).trim().split(/[\s,]+/)[0];
  return v ? abs(v) : null;
}
function numberOf(text) {
  var m = String(text || "").match(/(\d+(?:[.,]\d+)?)/);
  return m ? parseFloat(m[1].replace(",", ".")) : null;
}
/*@end*/
