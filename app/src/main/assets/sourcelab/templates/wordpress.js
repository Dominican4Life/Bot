// @version 1
// Generic WordPress template of the Source Lab, written for {{name}} ({{baseUrl}}).
// A plain WordPress site: every post is a title with one "chapter" (the post itself), and the chapter's pages
// are the pictures inside the post's .{{imageSelector}} block. Lists use the WordPress REST API (/wp-json/wp/v2/posts)
// when the site has it and the HTML archive pages otherwise.

/*@part id="headers"*/
// Headers and constants: the site as Referer on every picture; COVER_ATTR the <img> attribute that held a real
// address on the home page; IMAGE_BLOCK the class of the post body that holds the pictures.
var BASE = "{{baseUrl}}";
var COVER_ATTR = "{{coverAttr}}";
var IMAGE_BLOCK = "{{imageSelector}}";
var extension = {
  name: "{{name}}",
  lang: "{{lang}}",
  baseUrl: BASE,
  iconUrl: "{{iconUrl}}",
  headers: { Referer: BASE + "/" },
/*@end*/

/*@part id="getPopular"*/
  // Popular: WordPress has no popularity order without a plugin, so this is the post archive by date
  // (REST: /wp-json/wp/v2/posts?page=N&_embed=1; HTML: /page/N/).
  getPopular: async function (page) {
    return posts(page, "");
  },
/*@end*/

/*@part id="getLatest"*/
  // Latest: the same archive (newest first is WordPress's own order).
  getLatest: async function (page) {
    return posts(page, "");
  },
/*@end*/

/*@part id="search"*/
  // Search: REST ?search=words, or the HTML /page/N/?s=words.
  search: async function (query, page, filters) {
    return posts(page, query || "");
  },
/*@end*/

/*@part id="getDetails"*/
  // Title page: the post's h1.entry-title, its featured image (og:image), the first paragraph as description,
  // the author link and the category / tag links as genres.
  getDetails: async function (url) {
    var html = await get(url);
    var doc = await host.parse(html);
    return {
      url: url,
      title: (await doc.text("h1.entry-title, h1") || "").trim(),
      coverUrl: abs(await doc.attr("meta[property='og:image']", "content") || await doc.attr(".wp-post-image", "src")),
      description: await doc.text("." + IMAGE_BLOCK + " p, .entry-content p"),
      author: await doc.text(".author a, .byline a, a[rel=author]"),
      genres: (await doc.select("a[rel~=category], a[rel~=tag], .cat-links a, .tags-links a")).map(function (a) { return a.text; }),
      webUrl: url,
    };
  },
/*@end*/

/*@part id="getChapterList"*/
  // Chapters: one chapter per post (the post itself), dated from the <time datetime> of the page.
  getChapterList: async function (url) {
    var doc = await host.parse(await get(url));
    var when = await doc.attr("time[datetime]", "datetime") || await doc.attr("meta[property='article:published_time']", "content");
    return [{
      url: url,
      title: (await doc.text("h1.entry-title, h1") || "Post").trim(),
      number: 1,
      dateUploaded: require("date").parse(when || ""),
    }];
  },
/*@end*/

/*@part id="getPageList"*/
  // Pages: the <img> tags inside the post body (the IMAGE_BLOCK class), the full-size address from the
  // lazy-load attribute, srcset's last entry or src; WordPress's -300x200 thumbnail suffix is removed.
  getPageList: async function (url) {
    var doc = await host.parse(await get(url));
    var imgs = await doc.select("." + IMAGE_BLOCK + " img, .entry-content img");
    var out = [];
    var seen = {};
    for (var i = 0; i < imgs.length; i++) {
      var src = imgs[i].attr(COVER_ATTR) || imgs[i].attr("data-src") || imgs[i].attr("data-lazy-src") || imgs[i].attr("src") || "";
      src = String(src).trim().split(/[\s,]+/)[0].replace(/-\d{2,4}x\d{2,4}(\.[a-z]+)$/i, "$1");
      if (!src || seen[src] || /emoji|avatar|logo/i.test(src)) continue;
      seen[src] = true;
      out.push({ index: out.length, imageUrl: abs(src) });
    }
    return out;
  },
/*@end*/
};

/*@part id="helpers"*/
// Helpers: one request with the site as Referer; the post lists through the REST API when it answers (title,
// link and the embedded featured image) and through the HTML archive's <article> cards otherwise.
async function get(url, options) {
  var o = options || {};
  var headers = Object.assign({ Referer: BASE + "/" }, o.headers || {});
  var r = await host.fetch(url, Object.assign({}, o, { headers: headers }));
  if (r.statusCode >= 400) throw new Error("The site answered " + r.statusCode + " for " + url);
  return r.body || "";
}
async function posts(page, query) {
  var api = BASE + "/wp-json/wp/v2/posts?per_page=20&_embed=1&page=" + page +
    (query ? "&search=" + encodeURIComponent(query) : "");
  var r = await host.fetch(api, { headers: { Referer: BASE + "/", Accept: "application/json" } });
  if (r.statusCode === 200 && /^\s*\[/.test(r.body || "")) {
    var list = JSON.parse(r.body);
    return list.map(function (p) {
      var media = p._embedded && p._embedded["wp:featuredmedia"] && p._embedded["wp:featuredmedia"][0];
      return {
        url: p.link,
        title: require("entities").decode((p.title && p.title.rendered) || ""),
        coverUrl: media && media.source_url ? abs(media.source_url) : null,
      };
    });
  }
  var html = await get(BASE + (page > 1 ? "/page/" + page + "/" : "/") + (query ? "?s=" + encodeURIComponent(query) : ""));
  var doc = await host.parse(html);
  var cards = await doc.select("article");
  var out = [];
  for (var i = 0; i < cards.length; i++) {
    var a = await cards[i].selectOne("h2 a, .entry-title a, h3 a");
    if (!a || !a.attr("href")) continue;
    var img = await cards[i].selectOne("img");
    var src = img ? (img.attr(COVER_ATTR) || img.attr("data-src") || img.attr("src") || "") : "";
    out.push({ url: abs(a.attr("href")), title: a.text, coverUrl: src ? abs(String(src).trim().split(/[\s,]+/)[0]) : null });
  }
  return out;
}
function abs(href) {
  return href ? require("url").resolve(BASE, String(href).trim()) : null;
}
/*@end*/
