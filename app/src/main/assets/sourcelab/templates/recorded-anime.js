// @version 1
// Recorded anime source of the Source Lab, written for {{name}} ({{baseUrl}}).
// "Record and convert" ran the installed extension's own methods, captured every request it made, and wrote each
// part from what came back: JSON replies as paths into the reply, HTML pages as selectors found from the values the
// extension returned. A part the Lab could not derive keeps the recorded requests and a reply excerpt as comments.

/*@part id="headers"*/
// Identity and headers: the loaded extension's name, language and address; HEADERS are the extension's own request
// headers (also sent with covers and pages); BOT_CHECK names the check the recording met; SITE_KEY is the setting
// that holds the site's address when the extension has one.
var BASE = "{{baseUrl}}";
/*@slot constants*/ var HEADERS = {}; var BOT_CHECK = ""; var SITE_KEY = "";
var extension = {
  name: "{{name}}",
  lang: "{{lang}}",
  baseUrl: BASE,
  iconUrl: "{{iconUrl}}",
  headers: HEADERS,
/*@end*/

/*@part id="getPopular"*/
  // Popular: written from the recorded pages 1 and 2 of the extension's popular list.
  getPopular: async function (page) {
    /*@slot getPopular*/ throw new Error("Not recorded: use Record and convert");
  },
/*@end*/

/*@part id="getLatest"*/
  // Latest: written from the recorded page 1 of the extension's latest list.
  getLatest: async function (page) {
    /*@slot getLatest*/ throw new Error("Not recorded: use Record and convert");
  },
/*@end*/

/*@part id="search"*/
  // Search: written from the recorded search (default filters).
  search: async function (query, page, filters) {
    /*@slot search*/ throw new Error("Not recorded: use Record and convert");
  },
/*@end*/

/*@part id="getDetails"*/
  // Title page: written from the recorded details of the first popular title.
  getDetails: async function (url) {
    /*@slot getDetails*/ return { url: url };
  },
/*@end*/

/*@part id="getChapterList"*/
  // Episodes: written from the recorded list of the first popular title.
  getChapterList: async function (url) {
    /*@slot getChapterList*/ throw new Error("Not recorded: use Record and convert");
  },
/*@end*/

/*@part id="getVideoList"*/
  // Videos: written from the recorded video list of the first episode (see the comments inside).
  getVideoList: async function (url) {
    /*@slot getVideoList*/ throw new Error("Not recorded: use Record and convert");
  },
/*@end*/

/*@part id="getFilters"*/
  // Filters: the extension's filter list (getFilterList), with its names, options and defaults.
  getFilters: function () {
    /*@slot getFilters*/ return [];
  },
/*@end*/

/*@part id="getSourcePreferences"*/
  // Settings: the extension's settings screen, with the same keys and defaults, so the values it saved keep working.
  getSourcePreferences: function () {
    /*@slot getSourcePreferences*/ return [];
  },
/*@end*/
};

/*@part id="helpers"*/
// Helpers: one request with the extension's headers (a recorded bot check is passed once in the hidden browser,
// whose cookies the app's requests share); the site's address (site, origin) and the extension's settings
// (setting); JSON paths (v, where); the address pieces of a url (param, seg); the HTML pickers (htmlList,
// htmlFields, attrAt); absolute addresses against the current site (abs); numbers and dates; packed scripts and
// addresses written with escaped slashes (unslash).
var SITE = BASE;
async function site() {
  var value = SITE_KEY ? await host.preference.get(SITE_KEY) : null;
  if (!value) {
    SITE = BASE;
    return SITE;
  }
  value = String(value).trim().replace(/\/+$/, "");
  SITE = /^https?:\/\//i.test(value) ? value : "https://" + value;
  return SITE;
}
function origin(url) {
  var m = String(url || "").match(/^[a-z][a-z0-9+.-]*:\/\/[^\/?#]+/i);
  return m ? m[0] : String(url || "");
}
async function setting(key, fallback) {
  var value = await host.preference.get(key);
  return value === null || value === undefined || value === "" ? fallback : value;
}
function looksLikeCheck(r) {
  if (r.statusCode !== 403 && r.statusCode !== 429 && r.statusCode !== 503) return false;
  var b = String(r.body || "").slice(0, 20000);
  return /ddos-guard|__ddg|challenge-platform|cf-chl|Just a moment|Checking your browser/i.test(b);
}
async function get(url, options) {
  var o = options || {};
  var headers = Object.assign({}, HEADERS, o.headers || {});
  var request = Object.assign({}, o, { headers: headers });
  var r = await host.fetch(url, request);
  if (BOT_CHECK && looksLikeCheck(r)) {
    await host.render((await site()) + "/", { timeout: 30000 });
    r = await host.fetch(url, request);
  }
  if (r.statusCode >= 400) throw new Error("The site answered " + r.statusCode + " for " + url);
  return r.body || "";
}
async function getJson(url, options) {
  var text = await get(url, options);
  try {
    return JSON.parse(text);
  } catch (e) {
    throw new Error("Not JSON from " + url + ": " + String(text).slice(0, 80));
  }
}
function v(obj, path) {
  var cur = obj;
  for (var i = 0; i < path.length && cur !== null && cur !== undefined; i++) cur = cur[path[i]];
  return cur === undefined ? null : cur;
}
function s(x) {
  return x === null || x === undefined ? "" : String(x);
}
function where(list, path, value) {
  if (!list || !list.length) return null;
  for (var i = 0; i < list.length; i++) if (s(v(list[i], path)) === s(value)) return list[i];
  return null;
}
function param(url, name) {
  var q = String(url).split("#")[0].split("?")[1] || "";
  var pairs = q.split("&");
  for (var i = 0; i < pairs.length; i++) {
    var kv = pairs[i].split("=");
    if (decodeURIComponent(kv[0]) === name) return decodeURIComponent((kv.slice(1).join("=") || "").replace(/\+/g, " "));
  }
  return "";
}
function seg(url, i) {
  var path = String(url).replace(/^[a-z][a-z0-9+.-]*:\/\/[^/]+/i, "").split(/[?#]/)[0];
  var parts = path.split("/");
  return decodeURIComponent(parts[i + 1] || "");
}
function abs(href) {
  return href ? require("url").resolve(SITE, String(href).trim()) : null;
}
function pathOf(href) {
  return href ? String(href).trim().replace(/^[a-z][a-z0-9+.-]*:\/\/[^/]+/i, "") : null;
}
function shape(value, how) {
  if (value === null || value === undefined) return null;
  value = String(value).trim();
  if (how === "srcset" || how === "srcsetabs") value = value.split(/[\s,]+/)[0];
  if (how === "abs" || how === "srcsetabs") return abs(value);
  if (how === "path") return pathOf(abs(value));
  return value;
}
async function pickFrom(el, p) {
  var target = p[0] ? await el.selectOne(p[0]) : el;
  if (!target) return null;
  var value = p[1] === "text" ? target.text : target.attr(p[1].slice(1));
  return shape(value, p[2]);
}
async function htmlList(html, item, spec) {
  var doc = await host.parse(html);
  var cards = await doc.select(item);
  var out = [];
  for (var i = 0; i < cards.length; i++) {
    var e = {};
    for (var k in spec) e[k] = await pickFrom(cards[i], spec[k]);
    out.push(e);
  }
  return out;
}
async function htmlFields(html, spec) {
  var doc = await host.parse(html);
  var out = {};
  for (var k in spec) {
    var p = spec[k];
    if (p[2] === "all") {
      var found = await doc.select(p[0]);
      out[k] = found.map(function (el) { return p[1] === "text" ? el.text : el.attr(p[1].slice(1)); }).filter(Boolean);
    } else {
      var el = await doc.selectOne(p[0]);
      out[k] = el ? shape(p[1] === "text" ? el.text : el.attr(p[1].slice(1)), p[2]) : null;
    }
  }
  return out;
}
async function attrAt(doc, css, index, name) {
  var found = await doc.select(css);
  return found[index] ? found[index].attr(name) : null;
}
function numberOf(text) {
  if (typeof text === "number") return text;
  var m = String(text || "").match(/(\d+(?:[.,]\d+)?)/);
  return m ? parseFloat(m[1].replace(",", ".")) : null;
}
function dateOf(value) {
  if (value === null || value === undefined || value === "") return null;
  if (typeof value === "number" || /^\d{9,13}$/.test(String(value))) {
    var n = Number(value);
    return new Date(n < 100000000000 ? n * 1000 : n).toISOString();
  }
  return require("date").parse(String(value));
}
function unslash(u) {
  return u ? String(u).split("\\/").join("/") : u;
}
function unpacked(text) {
  return require("unpacker").unpackAll(String(text || "")).join("\n");
}
/*@end*/
