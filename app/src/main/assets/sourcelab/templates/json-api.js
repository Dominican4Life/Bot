// @version 1
// JSON API template of the Source Lab, written for {{name}} ({{baseUrl}}).
// The site fills its pages from an API under {{apiPath}}. An API's field names differ from site to site, so this
// template reads the common shapes (a list under data / items / results, fields id, slug, title, cover…) and
// marks what it could not know with TODO: the Explain pane shows those parts as "not written".

/*@part id="headers"*/
// Headers and constants: the site as Referer and Accept: application/json on every API call; API is the path
// detection saw in the page's scripts.
var BASE = "{{baseUrl}}";
var API = "{{apiPath}}";
var extension = {
  name: "{{name}}",
  lang: "{{lang}}",
  baseUrl: BASE,
  iconUrl: "{{iconUrl}}",
  headers: { Referer: BASE + "/" },
/*@end*/

/*@part id="getPopular"*/
  // Popular: GET API?page=N&sort=popular (TODO: check the real list path and sort parameter in the site's
  // network calls); the reply's list under data / items / results, each item read by the common field names.
  getPopular: async function (page) {
    return entries(await api(API + "?page=" + page + "&sort=popular"));
  },
/*@end*/

/*@part id="getLatest"*/
  // Latest: the same list sorted by update (TODO: the real sort parameter).
  getLatest: async function (page) {
    return entries(await api(API + "?page=" + page + "&sort=latest"));
  },
/*@end*/

/*@part id="search"*/
  // Search: the same list with q=words (TODO: some APIs call it search, keyword or title).
  search: async function (query, page, filters) {
    return entries(await api(API + "?page=" + page + "&q=" + encodeURIComponent(query || "")));
  },
/*@end*/

/*@part id="getDetails"*/
  // Title page: GET API/<slug or id> (the entry's url is that key), the common detail fields.
  getDetails: async function (url) {
    var item = await api(url);
    item = item.data || item;
    return Object.assign(entry(item, url), {
      description: item.description || item.synopsis || item.summary || null,
      author: joinNames(item.author || item.authors || item.artist),
      genres: (item.genres || item.tags || []).map(function (g) { return typeof g === "string" ? g : (g.name || g.title); }),
      status: item.status ? String(item.status) : null,
    });
  },
/*@end*/

/*@part id="getChapterList"*/
  // Chapters: GET <entry url>/chapters (TODO: the real chapters path), each item's number, name and date.
  getChapterList: async function (url) {
    var list = unwrap(await api(url.replace(/\/$/, "") + "/chapters"));
    return list.map(function (c) {
      var number = c.number !== undefined ? parseFloat(c.number) : (c.chapter !== undefined ? parseFloat(c.chapter) : null);
      return {
        url: url.replace(/\/$/, "") + "/chapters/" + (c.id || c.slug || c.number),
        title: c.title || c.name || ("Chapter " + number),
        number: isNaN(number) ? null : number,
        dateUploaded: require("date").parse(c.created_at || c.createdAt || c.date || c.updated_at || ""),
      };
    });
  },
/*@end*/

/*@part id="getPageList"*/
  // Pages: GET <chapter url>, the picture list under images / pages / data (each a string or an object with
  // url / image / src). TODO: check the real picture field.
  getPageList: async function (url) {
    var c = await api(url);
    c = c.data || c;
    var images = c.images || c.pages || c.data || [];
    return images.map(function (p, i) {
      var src = typeof p === "string" ? p : (p.url || p.image || p.src || p.image_url || "");
      return { index: i, imageUrl: abs(src) };
    });
  },
/*@end*/
};

/*@part id="helpers"*/
// Helpers: one API call read as JSON (an HTML reply is an error with the address), the list unwrapping
// (data / items / results / the reply itself), the common entry fields and absolute addresses.
async function api(path) {
  var url = /^https?:/.test(path) ? path : BASE + path;
  var r = await host.fetch(url, { headers: { Referer: BASE + "/", Accept: "application/json", "X-Requested-With": "XMLHttpRequest" } });
  if (r.statusCode >= 400) throw new Error("The API answered " + r.statusCode + " for " + url);
  try {
    return JSON.parse(r.body || "null");
  } catch (e) {
    throw new Error("The API did not answer JSON for " + url);
  }
}
function unwrap(reply) {
  if (Array.isArray(reply)) return reply;
  if (!reply || typeof reply !== "object") return [];
  var keys = ["data", "items", "results", "list", "posts", "comics", "mangas", "series", "chapters"];
  for (var i = 0; i < keys.length; i++) {
    var v = reply[keys[i]];
    if (Array.isArray(v)) return v;
    if (v && typeof v === "object" && Array.isArray(v.data)) return v.data;
  }
  return [];
}
function entries(reply) {
  return unwrap(reply).map(function (item) { return entry(item, null); }).filter(function (e) { return e.url && e.title; });
}
function entry(item, url) {
  var key = item.slug || item.id || item.hash_id;
  return {
    url: url || (API.replace(/\/$/, "") + "/" + key),
    title: item.title || item.name || (item.title && item.title.en) || "",
    coverUrl: abs(item.cover || item.cover_url || item.coverUrl || item.thumbnail || item.image || item.poster || null),
    webUrl: item.url && /^https?:/.test(item.url) ? item.url : undefined,
  };
}
function joinNames(v) {
  if (!v) return null;
  if (typeof v === "string") return v;
  if (Array.isArray(v)) return v.map(function (a) { return typeof a === "string" ? a : (a.name || ""); }).filter(Boolean).join(", ");
  return v.name || null;
}
function abs(href) {
  return href ? require("url").resolve(BASE, String(href).trim()) : null;
}
/*@end*/
