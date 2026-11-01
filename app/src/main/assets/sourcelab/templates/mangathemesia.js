// @version 1
// MangaThemesia template of the Source Lab, written for {{name}} ({{baseUrl}}).
// Lists are the theme's /{{listPath}}/?order=… pages of .bsx cards, chapters sit in #chapterlist on the title,
// and the reader keeps its pictures in a ts_reader.run({...}) script (the <img> tags are a fallback).

/*@part id="headers"*/
// Headers and constants: the site as Referer on every picture; LIST_PATH is the directory path the home page
// linked to (/manga/, /series/ or /komik/); COVER_ATTR the <img> attribute that held a real address.
var BASE = "{{baseUrl}}";
var LIST_PATH = "{{listPath}}";
var COVER_ATTR = "{{coverAttr}}";
var extension = {
  name: "{{name}}",
  lang: "{{lang}}",
  baseUrl: BASE,
  iconUrl: "{{iconUrl}}",
  headers: { Referer: BASE + "/" },
/*@end*/

/*@part id="getPopular"*/
  // Popular: /LIST_PATH/?page=N&order=popular, the .listupd .bs .bsx cards (link = the card's <a>, title from
  // its title attribute or .tt, cover from the <img>).
  getPopular: async function (page) {
    return list(BASE + "/" + LIST_PATH + "/?page=" + page + "&order=popular");
  },
/*@end*/

/*@part id="getLatest"*/
  // Latest: the same directory ordered by update.
  getLatest: async function (page) {
    return list(BASE + "/" + LIST_PATH + "/?page=" + page + "&order=update");
  },
/*@end*/

/*@part id="search"*/
  // Search: /page/N/?s=words, the same .bsx cards.
  search: async function (query, page, filters) {
    return list(BASE + (page > 1 ? "/page/" + page + "/" : "/") + "?s=" + encodeURIComponent(query || ""));
  },
/*@end*/

/*@part id="getDetails"*/
  // Title page: h1.entry-title, the cover in .thumb img, the summary in .entry-content, author / status from the
  // .infotable or .tsinfo rows, genres from .mgen a.
  getDetails: async function (url) {
    var doc = await host.parse(await get(url));
    var status = await doc.text(".tsinfo .imptdt:contains(Status) i, .infotable tr:contains(Status) td:nth-child(2)");
    var author = await doc.text(".tsinfo .imptdt:contains(Author) i, .infotable tr:contains(Author) td:nth-child(2)");
    return {
      url: url,
      title: (await doc.text("h1.entry-title") || "").trim(),
      coverUrl: cover(await doc.selectOne(".thumb img, .infomanga > div[itemprop=image] img")),
      description: await doc.text(".entry-content[itemprop=description], .desc, .entry-content"),
      author: author ? author.trim() : null,
      genres: (await doc.select(".mgen a, .seriestugenre a")).map(function (a) { return a.text; }),
      status: status ? status.trim() : null,
      webUrl: url,
    };
  },
/*@end*/

/*@part id="getChapterList"*/
  // Chapters: #chapterlist li rows on the title page: the link, .chapternum as title and number,
  // .chapterdate as the date.
  getChapterList: async function (url) {
    var doc = await host.parse(await get(url));
    var rows = await doc.select("#chapterlist li, .eplister li");
    var out = [];
    for (var i = 0; i < rows.length; i++) {
      var a = await rows[i].selectOne("a");
      if (!a || !a.attr("href")) continue;
      var num = await rows[i].selectOne(".chapternum");
      var date = await rows[i].selectOne(".chapterdate");
      var title = num ? num.text : a.text;
      out.push({
        url: abs(a.attr("href")),
        title: title,
        number: numberOf(rows[i].attr("data-num") || title),
        dateUploaded: require("date").parse(date ? date.text : ""),
      });
    }
    return out;
  },
/*@end*/

/*@part id="getPageList"*/
  // Pages: the ts_reader.run({...}) script of the chapter page holds the picture list under sources[0].images;
  // when a site strips it, the #readerarea <img> tags are read instead.
  getPageList: async function (url) {
    var html = await get(url);
    var m = html.match(/ts_reader\.run\((\{[\s\S]*?\})\);?\s*<\/script>/);
    if (m) {
      try {
        var data = JSON.parse(m[1]);
        var images = (data.sources && data.sources[0] && data.sources[0].images) || [];
        if (images.length) return images.map(function (src, i) { return { index: i, imageUrl: abs(src) }; });
      } catch (e) {
        console.log("ts_reader script could not be read: " + e.message);
      }
    }
    var doc = await host.parse(html);
    var imgs = await doc.select("#readerarea img");
    var out = [];
    for (var i = 0; i < imgs.length; i++) {
      var src = (imgs[i].attr("data-src") || imgs[i].attr("src") || "").trim();
      if (src) out.push({ index: out.length, imageUrl: abs(src) });
    }
    return out;
  },
/*@end*/
};

/*@part id="helpers"*/
// Helpers: one request with the site as Referer, the .bsx card parser the three lists share, absolute
// addresses, the cover attribute chain and the first number in a chapter title.
async function get(url, options) {
  var o = options || {};
  var headers = Object.assign({ Referer: BASE + "/" }, o.headers || {});
  var r = await host.fetch(url, Object.assign({}, o, { headers: headers }));
  if (r.statusCode >= 400) throw new Error("The site answered " + r.statusCode + " for " + url);
  return r.body || "";
}
async function list(url) {
  var doc = await host.parse(await get(url));
  var cards = await doc.select(".listupd .bs .bsx, .bsx");
  var out = [];
  for (var i = 0; i < cards.length; i++) {
    var a = await cards[i].selectOne("a");
    if (!a || !a.attr("href")) continue;
    var tt = await cards[i].selectOne(".tt");
    out.push({ url: abs(a.attr("href")), title: a.attr("title") || (tt ? tt.text : a.text), coverUrl: cover(await cards[i].selectOne("img")) });
  }
  return out;
}
function abs(href) {
  return href ? require("url").resolve(BASE, String(href).trim()) : null;
}
function cover(img) {
  if (!img) return null;
  var v = img.attr(COVER_ATTR) || img.attr("data-src") || img.attr("data-lazy-src") || img.attr("src") || "";
  v = String(v).trim().split(/[\s,]+/)[0];
  return v ? abs(v) : null;
}
function numberOf(text) {
  var m = String(text || "").match(/(\d+(?:[.,]\d+)?)/);
  return m ? parseFloat(m[1].replace(",", ".")) : null;
}
/*@end*/
