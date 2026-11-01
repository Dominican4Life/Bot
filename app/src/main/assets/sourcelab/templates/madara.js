// @version 1
// Madara (WordPress theme) template of the Source Lab, written for {{name}} ({{baseUrl}}).
// Every part sits between /*@part id="…"*/ and /*@end*/ so the Explain pane can point at it; the first comment
// lines of a part say what it reads. Lists come from the theme's "load more" call (admin-ajax.php), chapters from
// the title's ajax/chapters/ reply, pages from the reader's .reading-content images.

/*@part id="headers"*/
// Headers and constants: the site is the Referer of every picture, so covers and pages load from its CDN.
// POST_TYPE is the WordPress post type of titles; COVER_ATTR the <img> attribute that held a real address
// on the home page; CHAPTERS_VIA how the theme serves chapter lists (newer themes: ajax/chapters/).
// Pictures refused without a Referer at detection: {{imageReferer}} (one HEAD of the first cover).
var BASE = "{{baseUrl}}";
var POST_TYPE = "{{postType}}";
var COVER_ATTR = "{{coverAttr}}";
var CHAPTERS_VIA = "{{chaptersVia}}";
var extension = {
  name: "{{name}}",
  lang: "{{lang}}",
  baseUrl: BASE,
  iconUrl: "{{iconUrl}}",
  headers: { Referer: BASE + "/" },
  rateLimitMs: 400,
/*@end*/

/*@part id="getPopular"*/
  // Popular: the theme's load-more call sorted by views (meta key _wp_manga_views). Each reply is one page of
  // .page-item-detail cards: the title link in .post-title a, the cover <img> with its lazy-load attribute.
  getPopular: async function (page) {
    return loadMore(page, "_wp_manga_views");
  },
/*@end*/

/*@part id="getLatest"*/
  // Latest: the same load-more call sorted by the last update (meta key _latest_update).
  getLatest: async function (page) {
    return loadMore(page, "_latest_update");
  },
/*@end*/

/*@part id="search"*/
  // Search: the theme's search page (?s=words&post_type=…), paged as /page/N/. Results are
  // .c-tabs-item__content cards, which read like the home cards (.post-title a and an <img>).
  search: async function (query, page, filters) {
    var url = BASE + (page > 1 ? "/page/" + page + "/" : "/") +
      "?s=" + encodeURIComponent(query || "") + "&post_type=" + POST_TYPE;
    var html = await get(url);
    return parseCards(html, ".c-tabs-item__content, .page-item-detail, .manga-item");
  },
/*@end*/

/*@part id="getDetails"*/
  // Title page: name from .post-title, cover from .summary_image, description from .summary__content, author,
  // genres and status from the post-content rows the theme prints on every title.
  getDetails: async function (url) {
    var html = await get(url);
    var doc = await host.parse(html);
    var status = (await doc.text(".post-status .summary-content") || await doc.text(".post-content_item:contains(Status) .summary-content") || "").trim();
    return {
      url: url,
      title: (await doc.text(".post-title h1") || await doc.text(".post-title") || "").trim(),
      coverUrl: cover(await doc.selectOne(".summary_image img")),
      description: await doc.text(".summary__content, .description-summary .summary__content, .manga-excerpt"),
      author: (await doc.select(".author-content a")).map(function (a) { return a.text; }).join(", ") || null,
      genres: (await doc.select(".genres-content a")).map(function (a) { return a.text; }),
      status: status || null,
      webUrl: url,
    };
  },
/*@end*/

/*@part id="getChapterList"*/
  // Chapters: the title's chapter list reply (POST ajax/chapters/ on newer themes, admin-ajax.php with the
  // post id on older ones), rows li.wp-manga-chapter: the link, its text as the title and number, the date in
  // .chapter-release-date. Chapter addresses are kept absolute, as the APK sources do.
  getChapterList: async function (url) {
    var html;
    if (CHAPTERS_VIA === "admin-ajax.php") {
      var page = await get(url);
      var id = (page.match(/manga-chapters-holder[^>]*data-id="(\d+)"/) || page.match(/rating-post-id[^>]*value="(\d+)"/) || [])[1];
      if (!id) throw new Error("No post id on the title page: can't ask admin-ajax.php for the chapters");
      html = await get(BASE + "/wp-admin/admin-ajax.php", {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded", "X-Requested-With": "XMLHttpRequest" },
        body: "action=manga_get_chapters&manga=" + id,
      });
    } else {
      html = await get(url.replace(/\/?$/, "/") + "ajax/chapters/", {
        method: "POST",
        headers: { "X-Requested-With": "XMLHttpRequest" },
      });
      if (!/wp-manga-chapter/.test(html)) html = await get(url);
    }
    var doc = await host.parse(html);
    var rows = await doc.select("li.wp-manga-chapter");
    var out = [];
    for (var i = 0; i < rows.length; i++) {
      var a = await rows[i].selectOne("a");
      if (!a || !a.attr("href")) continue;
      var dateEl = await rows[i].selectOne(".chapter-release-date i, .chapter-release-date a");
      var dateText = dateEl ? (dateEl.attr("title") || dateEl.text) : "";
      out.push({
        url: abs(a.attr("href")),
        title: a.text,
        number: numberOf(a.text),
        dateUploaded: require("date").parse(dateText),
      });
    }
    return out;
  },
/*@end*/

/*@part id="getPageList"*/
  // Pages: the chapter in list style (?style=list) so every picture is in the page; the <img> of
  // .reading-content, lazy-loaded through data-src, in page order.
  getPageList: async function (url) {
    var html = await get(url + (url.indexOf("?") >= 0 ? "&" : "?") + "style=list");
    var doc = await host.parse(html);
    var imgs = await doc.select(".reading-content img, .read-container img");
    var out = [];
    for (var i = 0; i < imgs.length; i++) {
      var src = (imgs[i].attr("data-src") || imgs[i].attr("data-lazy-src") || imgs[i].attr("src") || "").trim();
      if (!src) continue;
      out.push({ index: out.length, imageUrl: abs(src) });
    }
    return out;
  },
/*@end*/
};

/*@part id="helpers"*/
// Helpers: one request with the site as Referer (an answer of 400 or more is an error with the address), the
// load-more POST the three lists share, the card parser, absolute addresses, the cover from its lazy-load
// attribute (srcset: the first address) and the first number in a chapter title.
async function get(url, options) {
  var o = options || {};
  var headers = Object.assign({ Referer: BASE + "/" }, o.headers || {});
  var r = await host.fetch(url, Object.assign({}, o, { headers: headers }));
  if (r.statusCode >= 400) throw new Error("The site answered " + r.statusCode + " for " + url);
  return r.body || "";
}
async function loadMore(page, metaKey) {
  var body = "action=madara_load_more&page=" + (page - 1) +
    "&template=madara-core%2Fcontent%2Fcontent-archive" +
    "&vars%5Borderby%5D=meta_value_num&vars%5Bpaged%5D=1&vars%5Bposts_per_page%5D=20" +
    "&vars%5Bpost_type%5D=" + encodeURIComponent(POST_TYPE) + "&vars%5Bpost_status%5D=publish" +
    "&vars%5Bmeta_key%5D=" + metaKey + "&vars%5Border%5D=desc&vars%5Bsidebar%5D=right";
  var html = await get(BASE + "/wp-admin/admin-ajax.php", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded", "X-Requested-With": "XMLHttpRequest" },
    body: body,
  });
  return parseCards(html, ".page-item-detail, .manga-item");
}
async function parseCards(html, selector) {
  var doc = await host.parse(html);
  var cards = await doc.select(selector);
  var out = [];
  for (var i = 0; i < cards.length; i++) {
    var a = await cards[i].selectOne(".post-title a, h3 a, h4 a, a[title]");
    if (!a || !a.attr("href")) continue;
    out.push({ url: abs(a.attr("href")), title: a.text || a.attr("title"), coverUrl: cover(await cards[i].selectOne("img")) });
  }
  return out;
}
function abs(href) {
  return href ? require("url").resolve(BASE, String(href).trim()) : null;
}
function cover(img) {
  if (!img) return null;
  var v = img.attr(COVER_ATTR) || img.attr("data-src") || img.attr("data-lazy-src") || img.attr("data-original") || img.attr("src") || "";
  v = String(v).trim().split(/[\s,]+/)[0];
  return v ? abs(v) : null;
}
function numberOf(text) {
  var m = String(text || "").match(/(\d+(?:[.,]\d+)?)/);
  return m ? parseFloat(m[1].replace(",", ".")) : null;
}
/*@end*/
