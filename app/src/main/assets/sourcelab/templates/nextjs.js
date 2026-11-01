// @version 1
// Next.js template of the Source Lab, written for {{name}} ({{baseUrl}}).
// A Next.js site ships the data of every page in its __NEXT_DATA__ script (props.pageProps). This template reads
// that JSON instead of the HTML: lists from the home / search pages' pageProps, chapters and pictures from the
// title and chapter pages' pageProps. Field names differ per site, so the common ones are tried and the parts
// that guess are marked TODO (shown as "not written" in the Explain pane). Build id seen at detection: {{buildId}}.

/*@part id="headers"*/
// Headers and constants: the site as Referer on every picture (covers often go through /_next/image, which
// also wants it); COVER_ATTR the <img> attribute that held a real address.
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
  // Popular: the home page's pageProps, the first list of objects that look like titles (TODO: the real
  // list key, e.g. pageProps.popular or pageProps.data.items).
  getPopular: async function (page) {
    if (page > 1) return [];
    return entries(await pageProps(BASE + "/"), null);
  },
/*@end*/

/*@part id="getLatest"*/
  // Latest: the same page, the list whose key mentions latest / recent / updates when there is one.
  getLatest: async function (page) {
    if (page > 1) return [];
    return entries(await pageProps(BASE + "/"), /latest|recent|update/i);
  },
/*@end*/

/*@part id="search"*/
  // Search: /search?q=words (TODO: the real search path), its pageProps list.
  search: async function (query, page, filters) {
    return entries(await pageProps(BASE + "/search?q=" + encodeURIComponent(query || "") + "&page=" + page), null);
  },
/*@end*/

/*@part id="getDetails"*/
  // Title page: the first object in pageProps with a title and a description / summary.
  getDetails: async function (url) {
    var props = await pageProps(url);
    var item = findObject(props, function (o) { return (o.title || o.name) && (o.description || o.summary || o.synopsis); }) || {};
    return {
      url: url,
      title: textOf(item.title || item.name) || "",
      coverUrl: abs(item.cover || item.cover_url || item.coverUrl || item.thumbnail || item.image || item.poster),
      description: item.description || item.summary || item.synopsis || null,
      author: textOf(item.author || item.authors),
      genres: (item.genres || item.tags || []).map(function (g) { return typeof g === "string" ? g : (g.name || g.title || ""); }),
      status: item.status ? String(item.status) : null,
      webUrl: url,
    };
  },
/*@end*/

/*@part id="getChapterList"*/
  // Chapters: the pageProps list whose items carry a chapter number (number / chapter / chapter_number); the
  // address is built from the item's slug or id under the title's address (TODO: the site's chapter path).
  getChapterList: async function (url) {
    var props = await pageProps(url);
    var list = findList(props, function (o) { return o.number !== undefined || o.chapter !== undefined || o.chapter_number !== undefined; });
    return list.map(function (c) {
      var number = parseFloat(c.number !== undefined ? c.number : (c.chapter !== undefined ? c.chapter : c.chapter_number));
      var key = c.slug || c.id || number;
      return {
        url: /^https?:/.test(c.url || "") ? c.url : url.replace(/\/$/, "") + "/" + key,
        title: c.title || c.name || ("Chapter " + number),
        number: isNaN(number) ? null : number,
        dateUploaded: require("date").parse(c.created_at || c.createdAt || c.date || c.updated_at || ""),
      };
    });
  },
/*@end*/

/*@part id="getPageList"*/
  // Pages: the chapter page's pageProps list of pictures (strings or objects with url / image / src), else
  // the page's <img> tags under the reader (TODO: the reader's container).
  getPageList: async function (url) {
    var html = await get(url);
    var props = propsOf(html);
    var list = findList(props, function (o) { return typeof o === "string" ? /\.(jpe?g|png|webp|avif)/i.test(o) : (o.url || o.image || o.src); });
    if (list.length) {
      return list.map(function (p, i) { return { index: i, imageUrl: abs(typeof p === "string" ? p : (p.url || p.image || p.src)) }; });
    }
    var doc = await host.parse(html);
    var imgs = await doc.select("main img, .reader img, #reader img");
    return imgs.map(function (img, i) { return { index: i, imageUrl: abs(img.attr("data-src") || img.attr("src")) }; });
  },
/*@end*/
};

/*@part id="helpers"*/
// Helpers: one request with the site as Referer; the __NEXT_DATA__ JSON of a page (props.pageProps); a search
// of that JSON for lists of title-like objects (a title and an address or slug) and for objects by a test;
// the common entry fields; /_next/image addresses unwrapped to the real picture; absolute addresses.
async function get(url, options) {
  var o = options || {};
  var headers = Object.assign({ Referer: BASE + "/" }, o.headers || {});
  var r = await host.fetch(url, Object.assign({}, o, { headers: headers }));
  if (r.statusCode >= 400) throw new Error("The site answered " + r.statusCode + " for " + url);
  return r.body || "";
}
function propsOf(html) {
  var m = html.match(/<script id="__NEXT_DATA__"[^>]*>([\s\S]*?)<\/script>/);
  if (!m) throw new Error("No __NEXT_DATA__ script on the page: the site may render on the client only");
  var data = JSON.parse(m[1]);
  return (data.props && data.props.pageProps) || data.props || data;
}
async function pageProps(url) {
  return propsOf(await get(url));
}
function walk(node, visit, depth) {
  if (depth > 8 || node === null || typeof node !== "object") return;
  if (visit(node) === true) return;
  var keys = Array.isArray(node) ? node.map(function (_, i) { return i; }) : Object.keys(node);
  for (var i = 0; i < keys.length; i++) walk(node[keys[i]], visit, depth + 1);
}
function findList(props, test) {
  var best = [];
  walk(props, function (node) {
    if (Array.isArray(node) && node.length > best.length && node.filter(function (o) { return o && test(o); }).length === node.length) best = node;
  }, 0);
  return best;
}
function findObject(props, test) {
  var found = null;
  walk(props, function (node) {
    if (!found && !Array.isArray(node) && test(node)) { found = node; return true; }
  }, 0);
  return found;
}
function entries(props, keyHint) {
  var lists = [];
  walk(props, function (node) {
    if (!Array.isArray(node)) {
      Object.keys(node).forEach(function (k) {
        var v = node[k];
        if (Array.isArray(v) && v.length && v.filter(isTitle).length === v.length) lists.push({ key: k, list: v });
      });
    }
  }, 0);
  var pick = keyHint ? lists.filter(function (l) { return keyHint.test(l.key); })[0] : null;
  var list = (pick || lists[0] || { list: [] }).list;
  return list.map(function (item) {
    var key = item.slug || item.id;
    return {
      url: /^https?:/.test(item.url || "") ? item.url : abs(item.url || item.href || ("/" + (item.type || "series") + "/" + key)),
      title: textOf(item.title || item.name),
      coverUrl: abs(item.cover || item.cover_url || item.coverUrl || item.thumbnail || item.image || item.poster),
    };
  });
}
function isTitle(o) {
  return o && typeof o === "object" && (o.title || o.name) && (o.slug || o.id || o.url || o.href);
}
function textOf(v) {
  if (!v) return null;
  if (typeof v === "string") return v;
  if (Array.isArray(v)) return v.map(textOf).filter(Boolean).join(", ");
  return v.en || v.name || v.title || null;
}
function abs(href) {
  if (!href) return null;
  var s = String(href).trim();
  var m = s.match(/\/_next\/image\?url=([^&]+)/);
  if (m) s = decodeURIComponent(m[1]);
  return require("url").resolve(BASE, s);
}
/*@end*/
