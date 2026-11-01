// KMK (r96, Source Lab): the page-side half of "Build by tapping". Injected into the builder's WebView after
// the page loads. A tap on anything outlines every element that shares the tapped element's tag + class path
// (dashed) and the tapped one (solid), then posts what it is to the app through LabPicker.pick(json):
//   { cssPath, tag, attrs, text, count, sameParentCount, hrefs, attrsAllHttp, pageUrl }
// Nothing is guessed here: cssPath is the tag and classes of the element and its parents (nth-child only where
// an element has no class), count is how many elements the document holds for that path.
//
// Modes (r96 fix): "pick" (default) swallows every tap before the site sees it, so nothing navigates or opens;
// "browse" leaves the page alone so a title page can be opened by hand. The app switches with
// window.__labPicker.setMode("pick" | "browse"). Injected again after every page load and on in-page (SPA)
// navigations, so it has to be idempotent: a second run only puts the style back and re-applies the mode.
//
// r98: every message to the app goes through send(): the LabPicker bridge when the page can call it, else
// console.log("LABPICK:" + json) / console.log("LABLOG:" + text), which the app's WebChromeClient takes. The
// outlines are inline !important styles drawn inside the element (outline-offset < 0), so a card with
// overflow:hidden or a site rule can't hide them, and a small badge at the top of the page says what was picked.
// r98 fix: the console fallback carries a per-session token the app injects as window.__labToken right before this
// script ("LABPICK:<token>:<json>"); the app ignores console lines without it, so the page can't fake a pick. The
// token is taken off window at once. Unpainting puts back the element's own inline outline / background values.
//
// r99 (owner's run): (1) a tap on the site's own controls opened them (a "Type: All" dropdown opened under
// the tap and its option became the Item pick): in pick mode pointerdown / mousedown / touchstart are now taken at
// the window (capture, not passive) and their default is prevented on interactive targets (button, select, input,
// label, summary, [role=button], [aria-haspopup] …), so no focus, no menu; a touch on one is picked on touchend
// (its click never comes). Other touches pass (scrolling works, touchmove is never prevented) and their click is
// cancelled. (2) Every tap on a title page came out as a bare `a` ×109: an invisible link over the whole page took
// it. The tapped element is now resolved with document.elementsFromPoint, skipping links / divs that cover half the
// viewport, transparent ones and positioned boxes with no text and no picture, and taking the innermost element with
// visible text or a picture. The path is never a bare tag. (3) window.__labPicker.up() / .down() move the pick to
// the parent / the first meaningful child and send it again (via "up" / "down"), for the app's Up / Down buttons.
//
// r100 (the tap helper; the owner's Item tap landed on a "New Releases" heading and nothing said so): every pick
// also carries imgCount, linkCount, textLen, distinctHrefs, boundByScript, nextLike, rect and samples, which the
// app's PickCheck turns into a verdict. window.__labPicker.mark(state) colours the current pick (ok green, warn
// amber, info blue); .suggest(label) finds the element the label most likely means near the last tap (Item: the
// smallest repeated box with a picture and a link) and sends it as a pick with via "suggest" + forLabel, its
// look-alikes outlined dashed green until the next tap / label; .adopt() makes it the current element when the app
// uses it; .optionsFor(param) sends a filter's choices (a select's options, else link values) through
// LabPicker.options / console "LABOPTS:<token>:", in Browse mode too. Nothing here changes how a tap is taken.
(function () {
  var TOKEN = "";
  try {
    TOKEN = String(window.__labToken || "");
    delete window.__labToken;
  } catch (e0) { /* nothing */ }

  function bridgeHas(name) {
    try {
      return typeof LabPicker !== "undefined" && LabPicker && typeof LabPicker[name] === "function";
    } catch (e) {
      return false;
    }
  }

  // r100: "options" (optionsFor's answer) goes to LabPicker.options, console "LABOPTS:<token>:<json>"
  function send(kind, text) {
    var method = kind === "pick" ? "pick" : kind === "options" ? "options" : "log";
    if (bridgeHas(method)) {
      try {
        LabPicker[method](text);
        return;
      } catch (e) {
        // falls through to the console
      }
    }
    var prefix = kind === "pick" ? "LABPICK:" : kind === "options" ? "LABOPTS:" : "LABLOG:";
    try { console.log(prefix + TOKEN + ":" + text); } catch (e2) { /* nothing left to try */ }
  }

  var STYLE_ID = "__lab-picker-style";
  var STYLE_TEXT =
    ".__lab-like{outline:2px dashed #ff6d00 !important;outline-offset:-2px !important}" +
    ".__lab-picked{outline:3px solid #2962ff !important;outline-offset:-3px !important;background-color:rgba(41,98,255,.12) !important}" +
    ".__lab-kept{outline:2px solid #00bfa5 !important;outline-offset:-2px !important}" +
    "#__lab-badge{position:fixed !important;left:8px !important;right:8px !important;top:8px !important;z-index:2147483647 !important;" +
    "background:#2962ff !important;color:#fff !important;font:600 13px/1.3 sans-serif !important;padding:6px 10px !important;" +
    "border-radius:8px !important;box-shadow:0 2px 8px rgba(0,0,0,.35) !important;pointer-events:none !important;" +
    "white-space:nowrap !important;overflow:hidden !important;text-overflow:ellipsis !important}" +
    "html.__lab-picking a,html.__lab-picking a *,html.__lab-picking button,html.__lab-picking [role=button]{cursor:crosshair !important}";

  function ensureStyle() {
    if (document.getElementById(STYLE_ID)) return;
    var style = document.createElement("style");
    style.id = STYLE_ID;
    style.textContent = STYLE_TEXT;
    (document.head || document.documentElement).appendChild(style);
  }

  function applyModeClass(mode) {
    var root = document.documentElement;
    if (!root) return;
    if (mode === "pick") root.classList.add("__lab-picking"); else root.classList.remove("__lab-picking");
  }

  if (window.__labPicker) {
    // Same window (an SPA route change, or the app injecting again): the listeners are still attached
    ensureStyle();
    applyModeClass(window.__labPicker.mode);
    send("log", "already on " + location.href + " (" + window.__labPicker.mode + ")");
    return;
  }
  ensureStyle();
  var mode = "pick";
  applyModeClass(mode);

  var GENERATED = /^(css|sc|jss|emotion)-[a-z0-9]{4,}$|^[A-Za-z]+_[A-Za-z0-9]{5,}$|^_[a-z0-9]{5,}$/;

  function classesOf(el) {
    var list = [];
    var raw = (el.getAttribute("class") || "").split(/\s+/);
    for (var i = 0; i < raw.length; i++) {
      var c = raw[i];
      if (!c || c.indexOf("__lab-") === 0) continue;
      if (/[^A-Za-z0-9_-]/.test(c)) continue;
      if (/^(active|hover|selected|current|lazy|loaded|loading|visible|hidden)$/.test(c)) continue;
      list.push(c);
    }
    return list;
  }

  function segment(el) {
    var tag = el.tagName.toLowerCase();
    var classes = classesOf(el).filter(function (c) { return !GENERATED.test(c); });
    if (el.id && /^[A-Za-z][A-Za-z0-9_-]*$/.test(el.id) && !/\d{3,}/.test(el.id)) return "#" + el.id;
    if (classes.length) return tag + "." + classes.slice(0, 2).join(".");
    var parent = el.parentElement;
    if (!parent) return tag;
    var same = 0;
    var index = 0;
    for (var i = 0; i < parent.children.length; i++) {
      if (parent.children[i].tagName === el.tagName) {
        same++;
        if (parent.children[i] === el) index = same;
      }
    }
    return same > 1 ? tag + ":nth-of-type(" + index + ")" : tag;
  }

  // The path: up to 5 segments, stopping at the first id or at body
  function pathOf(el) {
    var parts = [];
    var node = el;
    while (node && node.tagName && node.tagName.toLowerCase() !== "body" && node.tagName.toLowerCase() !== "html" && parts.length < 5) {
      var seg = segment(node);
      parts.unshift(seg);
      if (seg.charAt(0) === "#") break;
      node = node.parentElement;
    }
    // r99: never a bare tag (`a` ×109 matched every link of the page): a classless element right under body
    // keeps body in front of it
    if (parts.length === 1 && /^[a-z0-9]+(:nth-of-type\(\d+\))?$/.test(parts[0])) parts.unshift("body");
    return parts.join(" > ");
  }

  function likeSelector(path) {
    // The same path without positional parts: what "others like this" means
    return path.replace(/:nth-of-type\(\d+\)/g, "");
  }

  function queryAll(selector) {
    try { return Array.prototype.slice.call(document.querySelectorAll(selector)); } catch (e) { return []; }
  }

  function attrsOf(el) {
    var out = {};
    for (var i = 0; i < el.attributes.length; i++) {
      var a = el.attributes[i];
      if (a.name === "class" || a.name === "style" || a.name.indexOf("on") === 0) continue;
      out[a.name] = String(a.value).slice(0, 500);
    }
    return out;
  }

  function isHttp(v) {
    v = String(v || "").trim().split(/[\s,]+/)[0];
    return /^https?:\/\//.test(v) || v.indexOf("//") === 0;
  }

  // Inline !important beats any stylesheet rule (a site's `*{outline:none !important}` included). r98 fix: the
  // element's own inline values (and their priority) are saved the first time it is painted and put back after
  var STYLED = [];
  var PAINTED = ["outline", "outline-offset", "background-color"];
  function paint(el, outline, offset, background) {
    try {
      var saved = null;
      for (var s = 0; s < STYLED.length; s++) if (STYLED[s].el === el) saved = STYLED[s];
      if (!saved) {
        saved = { el: el, values: {} };
        for (var p = 0; p < PAINTED.length; p++) {
          saved.values[PAINTED[p]] = [el.style.getPropertyValue(PAINTED[p]), el.style.getPropertyPriority(PAINTED[p])];
        }
        STYLED.push(saved);
      }
      el.style.setProperty("outline", outline, "important");
      el.style.setProperty("outline-offset", offset, "important");
      if (background) el.style.setProperty("background-color", background, "important");
    } catch (e) { /* an SVG node without style */ }
  }

  function unpaint(saved) {
    try {
      for (var p = 0; p < PAINTED.length; p++) {
        var name = PAINTED[p];
        var before = saved.values[name];
        if (before && before[0]) saved.el.style.setProperty(name, before[0], before[1] || "");
        else saved.el.style.removeProperty(name);
      }
    } catch (e) { /* nothing */ }
  }

  // r100: the suggestion's dashed green outlines, saved and put back the same way (their own list, so the
  // suggestion can go without touching the pick's outlines)
  var SUGG = [];
  function paintSuggestion(el) {
    try {
      for (var s = 0; s < STYLED.length; s++) if (STYLED[s].el === el) return; // the pick's own outline stays
      var saved = { el: el, values: {} };
      for (var p = 0; p < PAINTED.length; p++) {
        saved.values[PAINTED[p]] = [el.style.getPropertyValue(PAINTED[p]), el.style.getPropertyPriority(PAINTED[p])];
      }
      SUGG.push(saved);
      el.style.setProperty("outline", "3px dashed #4caf6a", "important");
      el.style.setProperty("outline-offset", "-3px", "important");
    } catch (e) { /* an SVG node without style */ }
  }
  function clearSuggestion() {
    for (var i = 0; i < SUGG.length; i++) unpaint(SUGG[i]);
    SUGG = [];
    suggested = null;
  }
  var suggested = null;

  function clearMarks() {
    clearSuggestion();
    var marked = document.querySelectorAll(".__lab-like, .__lab-picked");
    for (var i = 0; i < marked.length; i++) {
      marked[i].classList.remove("__lab-like");
      marked[i].classList.remove("__lab-picked");
    }
    for (var j = 0; j < STYLED.length; j++) {
      // a kept element keeps its class outline (the stylesheet draws it once the inline style is gone)
      unpaint(STYLED[j]);
    }
    STYLED = [];
    showBadge(null);
  }

  function showBadge(text) {
    var badge = document.getElementById("__lab-badge");
    if (!text) {
      if (badge && badge.parentNode) badge.parentNode.removeChild(badge);
      return;
    }
    if (!badge) {
      badge = document.createElement("div");
      badge.id = "__lab-badge";
      (document.body || document.documentElement).appendChild(badge);
    }
    badge.textContent = text;
  }

  function describe(el, via) {
    var path = pathOf(el);
    var like = likeSelector(path);
    var likes = queryAll(like);
    if (likes.indexOf(el) < 0) likes.push(el);
    var sameParent = 0;
    if (el.parentElement) {
      for (var i = 0; i < el.parentElement.children.length; i++) {
        if (segment(el.parentElement.children[i]) === segment(el)) sameParent++;
      }
    }
    var hrefs = [];
    var attrsAllHttp = [];
    var names = ["data-src", "data-lazy-src", "data-original", "srcset", "src", "href", "data-href", "poster"];
    for (var n = 0; n < names.length; n++) {
      var name = names[n];
      var all = likes.length > 0;
      for (var k = 0; k < likes.length && all; k++) {
        if (!isHttp(likes[k].getAttribute(name) || absolute(likes[k], name))) all = false;
      }
      if (all && el.hasAttribute(name)) attrsAllHttp.push(name);
    }
    for (var h = 0; h < likes.length && hrefs.length < 30; h++) {
      var link = likes[h].tagName.toLowerCase() === "a" ? likes[h] : (likes[h].querySelector("a[href]") || likes[h].closest("a[href]"));
      if (link && link.href) hrefs.push(link.href);
    }
    // r100: what the app's helper checks (PickCheck): pictures and links in the element, its text, how many
    // different addresses its look-alikes open, a player whose address the site's script sets, a "next" link
    var distinct = {};
    var distinctCount = 0;
    for (var d = 0; d < likes.length && distinctCount < 200; d++) {
      var dl = linkOf(likes[d]);
      var key = dl && dl.href ? String(dl.href).split("#")[0] : "";
      if (key && !distinct[key]) { distinct[key] = true; distinctCount++; }
    }
    var samples = [];
    for (var m = 0; m < likes.length && samples.length < 3; m++) {
      var st = snippetOf(likes[m]);
      if (st) samples.push(st);
    }
    var r = el.getBoundingClientRect();
    return {
      imgCount: countIn(el, PICTURE),
      linkCount: countIn(el, "a[href]"),
      textLen: String(el.textContent || "").replace(/\s+/g, " ").trim().length,
      distinctHrefs: distinctCount,
      boundByScript: boundByScript(el),
      nextLike: nextLike(el),
      rect: { x: Math.round(r.left), y: Math.round(r.top), w: Math.round(r.width), h: Math.round(r.height) },
      samples: samples,
      cssPath: path,
      snippet: snippetOf(el),
      via: via || "tap",
      likeSelector: like,
      tag: el.tagName.toLowerCase(),
      attrs: attrsOf(el),
      text: String(el.textContent || "").replace(/\s+/g, " ").trim().slice(0, 200),
      count: likes.length,
      sameParentCount: sameParent,
      hrefs: hrefs,
      attrsAllHttp: attrsAllHttp,
      pageUrl: location.href,
    };
  }

  // src / href as the browser resolves them (an absolute address even when the attribute is relative)
  function absolute(el, name) {
    if (name === "src" && el.src) return el.src;
    if (name === "href" && el.href) return el.href;
    return "";
  }

  // The tapped element solid blue (tinted), its repeats dashed orange; returns how many there are
  function mark(el) {
    clearMarks();
    var likes = queryAll(likeSelector(pathOf(el)));
    for (var i = 0; i < likes.length; i++) {
      if (likes[i] === el) continue;
      likes[i].classList.add("__lab-like");
      paint(likes[i], "2px dashed #ff6d00", "-2px", null);
    }
    el.classList.add("__lab-picked");
    paint(el, "3px solid #2962ff", "-3px", "rgba(41,98,255,.12)");
    return likes.indexOf(el) < 0 ? likes.length + 1 : likes.length;
  }

  function shortName(path) {
    var parts = likeSelector(path).split(" > ");
    return parts[parts.length - 1] || path;
  }

  // ---------- r99: what the tap meant ----------

  function snippetOf(el) {
    var t = "";
    try { t = String(el.innerText || el.textContent || ""); } catch (e) { t = String(el.textContent || ""); }
    t = t.replace(/\s+/g, " ").trim();
    if (!t) t = el.getAttribute("alt") || el.getAttribute("title") || el.getAttribute("aria-label") || "";
    if (!t) {
      var img = el.querySelector && el.querySelector("img[alt]");
      if (img) t = img.getAttribute("alt") || "";
    }
    return String(t).replace(/\s+/g, " ").trim().slice(0, 60);
  }

  var MEDIA = /^(img|video|iframe|picture|canvas|svg)$/;
  function isMedia(el) { return MEDIA.test(el.tagName.toLowerCase()); }
  function hasMedia(el) {
    try { return !!el.querySelector("img, video, iframe, picture, canvas"); } catch (e) { return false; }
  }
  function hasVisibleText(el) {
    var t = "";
    try { t = String(el.innerText || ""); } catch (e) { t = ""; }
    return t.replace(/\s+/g, "").length > 0;
  }

  // Why an element under the finger is not what the user meant, or "" when it may be
  function skipReason(el, vw, vh) {
    var cs;
    try { cs = window.getComputedStyle(el); } catch (e) { return ""; }
    if (!cs) return "";
    if (cs.visibility === "hidden" || cs.visibility === "collapse") return "hidden";
    // Opacity is inherited visually: the element's and its ancestors' together
    var opacity = 1;
    var node = el;
    for (var depth = 0; node && node.nodeType === 1 && depth < 12; depth++) {
      var o = parseFloat(window.getComputedStyle(node).opacity);
      if (!isNaN(o)) opacity *= o;
      node = node.parentElement;
    }
    if (opacity < 0.05) return "transparent";
    var tag = el.tagName.toLowerCase();
    var r = el.getBoundingClientRect();
    var w = Math.max(0, Math.min(r.right, vw) - Math.max(r.left, 0));
    var h = Math.max(0, Math.min(r.bottom, vh) - Math.max(r.top, 0));
    if ((tag === "a" || tag === "div") && w * h >= 0.5 * vw * vh) return "covers the page";
    if ((cs.position === "fixed" || cs.position === "absolute") && !isMedia(el) && !hasVisibleText(el) && !hasMedia(el)) return "empty overlay";
    return "";
  }

  // The innermost element under (x, y) that has visible text, a picture, or is a link with text
  function resolveAt(x, y, fallback) {
    var stack = [];
    try { if (document.elementsFromPoint) stack = document.elementsFromPoint(x, y) || []; } catch (e) { stack = []; }
    if (!stack.length && fallback) stack = [fallback];
    var vw = window.innerWidth || document.documentElement.clientWidth || 1;
    var vh = window.innerHeight || document.documentElement.clientHeight || 1;
    var kept = [];
    var skipped = [];
    var firstSkipped = null;
    var firstWhy = "";
    for (var i = 0; i < stack.length; i++) {
      var el = stack[i];
      if (!el || !el.tagName) continue;
      var tag = el.tagName.toLowerCase();
      if (tag === "html" || tag === "body" || el.id === "__lab-badge") continue;
      var why = skipReason(el, vw, vh);
      if (why) {
        if (skipped.length < 4) skipped.push(tag + " (" + why + ")");
        if (!firstSkipped) { firstSkipped = el; firstWhy = tag + " (" + why + ")"; }
        continue;
      }
      kept.push(el);
    }
    for (var k = 0; k < kept.length; k++) {
      var c = kept[k];
      if (isMedia(c) || hasVisibleText(c)) return { el: c, skipped: skipped };
    }
    if (kept.length) return { el: kept[0], skipped: skipped };
    // r99 fix: everything under the finger was skipped: the first skipped element rather than nothing (the pick
    // says so, and Up / Down can move from it)
    return { el: firstSkipped, skipped: skipped, fallback: firstSkipped ? firstWhy : "" };
  }

  var current = null;

  function pickElement(el, via, note) {
    current = el;
    var count = mark(el);
    showBadge("Picked " + shortName(pathOf(el)) + " ×" + count + (note ? " (" + note + ")" : "") + " — now tap a label in the app");
    try {
      var d = describe(el, via);
      // r99 fix: a pick that only a skipped element gave says so in its text (the hint shows the snippet)
      if (note) d.snippet = ("[" + note + "] " + d.snippet).trim().slice(0, 100);
      send("pick", JSON.stringify(d));
    } catch (e) {
      send("log", "pick failed: " + e.message);
    }
  }

  function pickAt(x, y, target) {
    var found = resolveAt(x, y, target);
    var el = found.el;
    if (!el || !el.tagName) return;
    if (found.skipped.length) send("log", "tap skipped " + found.skipped.join(", "));
    var note = "";
    if (found.fallback) {
      note = "everything here was skipped; using " + found.fallback;
      send("log", "tap: " + note);
    }
    // A tap on text inside a link or an image's wrapper means the link or the image
    if (el.tagName.toLowerCase() !== "a" && !isMedia(el)) {
      var inner = el.querySelector("img, iframe, video");
      var link = el.closest("a");
      if (inner && el.children.length === 1) el = inner;
      else if (link && String(el.textContent || "").trim().length > 0 && String(el.textContent || "").trim() === String(link.textContent || "").trim()) el = link;
    }
    pickElement(el, "tap", note);
  }

  // The first child worth picking: shown, and with text or a picture (Down)
  function firstMeaningfulChild(el) {
    if (!el) return null;
    for (var i = 0; i < el.children.length; i++) {
      var c = el.children[i];
      var tag = c.tagName.toLowerCase();
      if (/^(script|style|noscript|template|link|meta)$/.test(tag) || c.id === "__lab-badge") continue;
      var r = c.getBoundingClientRect();
      if (r.width <= 0 || r.height <= 0) continue;
      if (isMedia(c) || hasVisibleText(c) || hasMedia(c)) return c;
    }
    return null;
  }

  // ---------- r100: facts for the helper ----------

  var PICTURE = "img, picture, [style*=background-image]";

  // How many elements match [selector]: the element itself and inside it
  function countIn(el, selector) {
    var n = 0;
    try {
      if (el.matches && el.matches(selector)) n++;
      n += el.querySelectorAll(selector).length;
    } catch (e) { /* nothing */ }
    return n;
  }

  // The link an element is or holds or sits in
  function linkOf(el) {
    if (!el || !el.tagName) return null;
    if (el.tagName.toLowerCase() === "a" && el.hasAttribute("href")) return el;
    try { return el.querySelector("a[href]") || el.closest("a[href]"); } catch (e) { return null; }
  }

  function usableAddress(v) {
    v = String(v || "").trim();
    return v.length > 0 && !/^(about:blank|javascript:|#)/i.test(v);
  }

  // A player (iframe / video) whose address the site's script fills in after the page loads (Vue `:src`,
  // `v-bind:src`, Alpine `x-bind:src`, Angular `[src]` / `ng-src`): the plain page has no address for it. A src
  // binding counts even when the live page already shows a src (the script ran here, it won't in a plain fetch);
  // any other binding counts only when there is no usable src / data-src.
  var BIND_SRC = /^(:src|v-bind:src|x-bind:src|\[src\]|ng-src|data-ng-src|:data-src)$/i;
  var BIND_ANY = /^(:|v-bind:|x-bind:|\[src\]|ng-src)/i;
  function boundByScript(el) {
    var tag = el.tagName.toLowerCase();
    if (tag !== "iframe" && tag !== "video") return false;
    var usable = usableAddress(el.getAttribute("src")) || usableAddress(el.getAttribute("data-src"));
    if (!usable && tag === "video") {
      var source = el.querySelector("source[src]");
      if (source && usableAddress(source.getAttribute("src"))) usable = true;
    }
    var srcBound = false;
    var anyBound = false;
    for (var i = 0; i < el.attributes.length; i++) {
      var name = el.attributes[i].name;
      if (BIND_SRC.test(name)) srcBound = true;
      if (BIND_ANY.test(name)) anyBound = true;
    }
    return srcBound || (!usable && anyBound);
  }

  // A "next page" link: rel=next, an aria-label saying next, or text that starts with next / › / » / > / →
  function nextLike(el) {
    var a = linkOf(el) || el;
    var rel = String(a.getAttribute("rel") || "");
    if (/(^|\s)next(\s|$)/i.test(rel)) return true;
    if (/next/i.test(String(a.getAttribute("aria-label") || ""))) return true;
    var t = String(a.textContent || "").replace(/\s+/g, " ");
    return /^\s*(next|›|»|>|→)/i.test(t);
  }

  // ---------- r100: suggest (the helper's "Did you mean …?") ----------

  function shown(el) {
    try {
      var r = el.getBoundingClientRect();
      return r.width > 0 && r.height > 0;
    } catch (e) { return false; }
  }

  function centre(el) {
    var r = el.getBoundingClientRect();
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
  }

  // Distance from the last tap (the current element) to [el]: nearer suggestions first
  function distanceFromTap(el) {
    if (!current) return 0;
    try {
      var a = centre(current);
      var b = centre(el);
      return Math.abs(a.x - b.x) + Math.abs(a.y - b.y);
    } catch (e) { return 0; }
  }

  var NOT_ITEM = /^(html|body|head|main|nav|header|footer|ul|ol|table|tbody|thead|tr|form|script|style|img|picture|svg|video|iframe|source|a)$/;

  // One box per title: holds a picture, a link and some text, and is shown
  function itemish(el) {
    if (!el || el.nodeType !== 1 || NOT_ITEM.test(el.tagName.toLowerCase())) return false;
    if (el.id === "__lab-badge" || !shown(el)) return false;
    if (countIn(el, PICTURE) < 1) return false;
    if (!el.querySelector("a[href]") && !el.closest("a[href]")) return false;
    return String(el.textContent || "").replace(/\s+/g, "").length > 0;
  }

  function likesOf(el, cache) {
    var sel = likeSelector(pathOf(el));
    if (!cache[sel]) cache[sel] = queryAll(sel);
    return cache[sel];
  }

  // Repeated (≥ 3 look-alikes) and most of the look-alikes are boxes like it
  function repeatedItem(el, cache) {
    if (!itemish(el)) return false;
    var sel = likeSelector(pathOf(el));
    var key = "rep:" + sel;
    if (cache[key] === undefined) {
      var likes = likesOf(el, cache);
      var checked = 0;
      var good = 0;
      for (var i = 0; i < likes.length && checked < 20; i++, checked++) if (itemish(likes[i])) good++;
      cache[key] = likes.length >= 3 && good >= 3 && good >= 0.6 * checked;
    }
    return cache[key];
  }

  // The smallest repeated boxes under [root]: candidates with no candidate inside them, grouped by look-alike
  // selector; the biggest group wins (ties: the one nearer the tap); returns its member nearest the tap
  function bestItemUnder(root, cache) {
    if (!root || !root.querySelectorAll) return null;
    var all = Array.prototype.slice.call(root.querySelectorAll("*"), 0, 5000);
    var found = [];
    for (var i = 0; i < all.length; i++) if (repeatedItem(all[i], cache)) found.push(all[i]);
    var minimal = [];
    for (var j = 0; j < found.length; j++) {
      var inner = false;
      for (var k = 0; k < found.length && !inner; k++) {
        if (k !== j && found[j] !== found[k] && found[j].contains(found[k])) inner = true;
      }
      if (!inner) minimal.push(found[j]);
    }
    if (!minimal.length) return null;
    var groups = {};
    for (var m = 0; m < minimal.length; m++) {
      var sel = likeSelector(pathOf(minimal[m]));
      (groups[sel] = groups[sel] || []).push(minimal[m]);
    }
    var best = null;
    for (var key in groups) {
      if (!Object.prototype.hasOwnProperty.call(groups, key)) continue;
      var g = groups[key];
      g.sort(function (a, b) { return distanceFromTap(a) - distanceFromTap(b); });
      if (!best || g.length > best.length || (g.length === best.length && distanceFromTap(g[0]) < distanceFromTap(best[0]))) best = g;
    }
    return best ? best[0] : null;
  }

  // Item: the tap's ancestors first, then inside the tap's nearest container (up to 6 levels), then the page
  function findItem() {
    var cache = {};
    var node = current;
    while (node && node.nodeType === 1 && !/^(body|html)$/i.test(node.tagName)) {
      // A repeated shelf around the tap (heading + its cards) holds smaller repeated boxes: those are the items
      if (repeatedItem(node, cache)) return bestItemUnder(node, cache) || node;
      node = node.parentElement;
    }
    var box = current ? current.parentElement : null;
    for (var level = 0; box && level < 6 && !/^(html)$/i.test(box.tagName); level++, box = box.parentElement) {
      var inside = bestItemUnder(box, cache);
      if (inside) return inside;
      if (/^body$/i.test(box.tagName)) return null;
    }
    return bestItemUnder(document.body, cache);
  }

  function findTitle() {
    var item = findItem();
    if (!item) return null;
    var heading = item.querySelector("h1 a[href], h2 a[href], h3 a[href], h4 a[href], h5 a[href], h6 a[href]");
    if (heading && String(heading.textContent || "").trim()) return heading;
    var links = item.querySelectorAll("a[href]");
    for (var i = 0; i < links.length; i++) if (String(links[i].textContent || "").trim()) return links[i];
    return null;
  }

  function findCover() {
    var item = findItem();
    if (!item) return null;
    return item.querySelector("img") || item.querySelector("picture, [style*=background-image]");
  }

  function samePage(href) {
    return String(href || "").split("#")[0].replace(/\/$/, "") === String(location.href).split("#")[0].replace(/\/$/, "");
  }

  function findNext() {
    var links = queryAll("a[href]").filter(function (a) { return shown(a) && !/(^|\s)prev/i.test(String(a.getAttribute("rel") || "")); });
    for (var i = 0; i < links.length; i++) if (nextLike(links[i]) && !samePage(links[i].href)) return links[i];
    for (var j = 0; j < links.length; j++) if (/[?&](page|p)=2(\D|$)|\/page\/2(\D|$)/.test(links[j].href)) return links[j];
    return null;
  }

  // Groups of repeated links (≥ 2 different addresses, not this page, not in the site's menus) under [root]
  function bestLinkGroup(root) {
    if (!root || !root.querySelectorAll) return null;
    var links = Array.prototype.slice.call(root.querySelectorAll("a[href]"), 0, 3000);
    var groups = {};
    for (var i = 0; i < links.length; i++) {
      var a = links[i];
      if (!shown(a) || samePage(a.href) || /^javascript:/i.test(a.getAttribute("href") || "")) continue;
      try { if (a.closest("nav, header, footer")) continue; } catch (e) { /* nothing */ }
      var sel = likeSelector(pathOf(a));
      var g = groups[sel] || (groups[sel] = { els: [], hrefs: {}, n: 0 });
      g.els.push(a);
      var key = String(a.href).split("#")[0];
      if (!g.hrefs[key]) { g.hrefs[key] = true; g.n++; }
    }
    var best = null;
    for (var s in groups) {
      if (!Object.prototype.hasOwnProperty.call(groups, s)) continue;
      if (groups[s].n >= 2 && (!best || groups[s].n > best.n)) best = groups[s];
    }
    return best ? best.els[0] : null;
  }

  function findChapter() {
    // The tap's own link group when it is a repeated one
    var own = current ? linkOf(current) : null;
    if (own && current && (current === own || current.contains(own) || own.contains(current))) {
      var group = queryAll(likeSelector(pathOf(own)));
      var hrefs = {};
      var n = 0;
      for (var i = 0; i < group.length; i++) {
        var key = String(group[i].href || "").split("#")[0];
        if (key && !samePage(key) && !hrefs[key]) { hrefs[key] = true; n++; }
      }
      if (n >= 2) return own;
    }
    var box = current ? current.parentElement : null;
    for (var level = 0; box && level < 5 && !/^(html|body)$/i.test(box.tagName); level++, box = box.parentElement) {
      var near = bestLinkGroup(box);
      if (near) return near;
    }
    return bestLinkGroup(document.body);
  }

  function findImage() {
    var imgs = queryAll("img").filter(function (i) {
      var r = i.getBoundingClientRect();
      return Math.max(r.width, i.naturalWidth || 0) >= 200 && r.height > 0;
    });
    var groups = {};
    for (var i = 0; i < imgs.length; i++) {
      var sel = likeSelector(pathOf(imgs[i]));
      (groups[sel] = groups[sel] || []).push(imgs[i]);
    }
    var best = null;
    for (var s in groups) {
      if (!Object.prototype.hasOwnProperty.call(groups, s)) continue;
      if (groups[s].length >= 2 && (!best || groups[s].length > best.length)) best = groups[s];
    }
    return best ? best[0] : null;
  }

  function largest(list) {
    var best = null;
    var area = 0;
    for (var i = 0; i < list.length; i++) {
      var r = list[i].getBoundingClientRect();
      if (r.width * r.height > area) { area = r.width * r.height; best = list[i]; }
    }
    return best;
  }

  function findVideo() {
    var node = current;
    if (node && /^(iframe|video)$/i.test(node.tagName)) return node;
    for (var level = 0; node && level < 4 && !/^(html|body)$/i.test(node.tagName); level++, node = node.parentElement) {
      var inside = largest(Array.prototype.slice.call(node.querySelectorAll("iframe, video")));
      if (inside) return inside;
    }
    return largest(queryAll("iframe, video"));
  }

  var FINDERS = { item: findItem, title: findTitle, cover: findCover, next: findNext, chapter: findChapter, image: findImage, video: findVideo };

  // ---------- pick mode: the site never sees the tap ----------

  var INTERACTIVE = "button, select, option, textarea, input, label, summary, [role=button], [role=option], [role=menuitem], [aria-haspopup]";
  function interactive(el) {
    try { return !!(el && el.closest && el.closest(INTERACTIVE)); } catch (e) { return false; }
  }
  function targetOf(event) {
    var t = event.target;
    return t && t.nodeType === 3 ? t.parentElement : t;
  }

  // touchstart on an interactive target is prevented (no focus, no menu, no click), so it is picked on touchend
  var touch = null;            // { x, y, target, interactive, moved }
  var touchPickedAt = 0;       // a click right after a touch pick is the same tap
  var MOVE_SLOP = 10;

  function onTouchStart(event) {
    if (mode !== "pick") return;
    event.stopImmediatePropagation();
    var t = event.touches && event.touches[0];
    if (!t || event.touches.length > 1) { touch = null; return; }
    var target = targetOf(event);
    touch = { x: t.clientX, y: t.clientY, target: target, interactive: interactive(target), moved: false };
    if (touch.interactive) event.preventDefault();
  }
  function onTouchMove(event) {
    if (mode !== "pick") return;
    event.stopImmediatePropagation(); // never preventDefault: scrolling stays the browser's
    var t = event.touches && event.touches[0];
    if (touch && t && (Math.abs(t.clientX - touch.x) > MOVE_SLOP || Math.abs(t.clientY - touch.y) > MOVE_SLOP)) touch.moved = true;
  }
  function onTouchEnd(event) {
    if (mode !== "pick") return;
    event.stopImmediatePropagation();
    var start = touch;
    touch = null;
    if (!start || !start.interactive) return; // the browser's click follows and onClick takes it
    event.preventDefault();
    if (start.moved) return;
    touchPickedAt = Date.now();
    pickAt(start.x, start.y, start.target);
  }
  function onPress(event) {
    if (mode !== "pick") return;
    event.stopImmediatePropagation();
    if (interactive(targetOf(event))) event.preventDefault();
  }
  function onClick(event) {
    if (mode !== "pick") return;
    // The default action (following the link, submitting, opening) and the site's own handlers are stopped even
    // for a tap on the background, so pick mode never navigates
    event.preventDefault();
    event.stopImmediatePropagation();
    if (Date.now() - touchPickedAt < 700) return;
    pickAt(event.clientX, event.clientY, targetOf(event));
  }

  var ACTIVE = { capture: true, passive: false };
  window.addEventListener("touchstart", onTouchStart, ACTIVE);
  window.addEventListener("touchmove", onTouchMove, { capture: true, passive: true });
  window.addEventListener("touchend", onTouchEnd, ACTIVE);
  window.addEventListener("touchcancel", function () { touch = null; }, true);
  var PRESS = ["pointerdown", "pointerup", "mousedown", "mouseup"];
  for (var e = 0; e < PRESS.length; e++) window.addEventListener(PRESS[e], onPress, ACTIVE);
  // auxclick: a stylus button or long press on some keyboards; dblclick: a second quick tap
  window.addEventListener("click", onClick, ACTIVE);
  window.addEventListener("auxclick", onClick, ACTIVE);
  window.addEventListener("dblclick", function (ev) { if (mode === "pick") { ev.preventDefault(); ev.stopImmediatePropagation(); } }, ACTIVE);
  window.addEventListener("submit", function (ev) { if (mode === "pick") ev.preventDefault(); }, true);
  window.addEventListener("focusin", function (ev) {
    // A control that still got focus (a label's input) is let go, so no on-screen keyboard or menu opens
    if (mode !== "pick") return;
    var t = targetOf(ev);
    if (t && interactive(t) && typeof t.blur === "function") { try { t.blur(); } catch (e2) { /* nothing */ } }
  }, true);
  // A link opened through a JS handler we didn't catch (window.open from a window-level listener): kept inside
  window.open = (function (original) {
    return function (url) {
      if (mode === "pick") return null;
      return original ? original.apply(window, arguments) : null;
    };
  })(window.open);

  window.__labPicker = {
    mode: mode,
    setMode: function (m) {
      mode = m === "browse" ? "browse" : "pick";
      window.__labPicker.mode = mode;
      applyModeClass(mode);
      if (mode === "browse") { clearMarks(); current = null; }
      send("log", "mode " + mode + " on " + location.href);
      return mode;
    },
    // Keeps the outline on the elements already labelled (called by the app after a label is given)
    keep: function (selector) {
      clearMarks();
      var els = queryAll(selector);
      for (var i = 0; i < els.length; i++) els[i].classList.add("__lab-kept");
      showBadge("Labelled " + els.length + " \u2014 tap the next thing");
      setTimeout(function () { showBadge(null); }, 2500);
      return els.length;
    },
    clear: function () {
      clearMarks();
      var kept = document.querySelectorAll(".__lab-kept");
      for (var i = 0; i < kept.length; i++) kept[i].classList.remove("__lab-kept");
    },
    count: function (selector) { return queryAll(selector).length; },
    // r99: Up / Down from the app's hint bar: the parent, or the first child with text or a picture
    // r100 fix pass: every "can't move" answer is also logged as "up: …" / "down: …" (the app clears its re-label)
    up: function () {
      if (mode !== "pick" || !current) { send("log", "up: nothing picked to move from"); return "none"; }
      var p = current.parentElement;
      if (!p || /^(body|html)$/i.test(p.tagName)) { send("log", "up: already at the top"); return "top"; }
      pickElement(p, "up");
      return pathOf(p);
    },
    down: function () {
      if (mode !== "pick" || !current) { send("log", "down: nothing picked to move from"); return "none"; }
      var c = firstMeaningfulChild(current);
      if (!c) { send("log", "down: " + shortName(pathOf(current)) + " has no child with text or a picture"); return "none"; }
      pickElement(c, "down");
      return pathOf(c);
    },
    // r100: the helper's verdict on the current pick: its outline turns green ("ok"), amber ("warn") or blue
    // ("info"); [name] is the label as the app shows it, for the badge
    mark: function (state, name) {
      if (!current) return "none";
      var colour = state === "ok" ? "#4caf6a" : state === "warn" ? "#f2b33d" : "#7cb4ff";
      var likes = queryAll(likeSelector(pathOf(current)));
      for (var i = 0; i < likes.length; i++) {
        if (likes[i] !== current) paint(likes[i], "2px solid " + colour, "-2px", null);
      }
      paint(current, "3px solid " + colour, "-3px", null);
      var label = String(name || "pick");
      showBadge(state === "ok" ? "\u2713 " + label + " looks right" : state === "warn" ? "! Check the helper: this may not be a " + label : "i " + label + ": see the helper");
      return state;
    },
    // r100: the best element for [label] near the last tap, sent as a pick { via: "suggest", forLabel } with its
    // look-alikes outlined dashed green; { via: "suggest", forLabel, none: true } when there is none
    suggest: function (label) {
      clearSuggestion();
      var el = null;
      try { el = FINDERS[label] ? FINDERS[label]() : null; } catch (e) { send("log", "suggest " + label + " failed: " + e.message); }
      if (el && current && likeSelector(pathOf(el)) === likeSelector(pathOf(current))) el = null; // that is the pick itself
      if (!el) {
        send("pick", JSON.stringify({ via: "suggest", forLabel: label, none: true, pageUrl: location.href }));
        return "none";
      }
      suggested = el;
      var likes = queryAll(likeSelector(pathOf(el)));
      for (var i = 0; i < likes.length; i++) paintSuggestion(likes[i]);
      if (likes.indexOf(el) < 0) paintSuggestion(el);
      var d = describe(el, "suggest");
      d.forLabel = label;
      showBadge("Suggested " + label + " \u00d7" + d.count + " (dashed green) \u2014 see the helper");
      send("pick", JSON.stringify(d));
      return d.likeSelector;
    },
    // r100: the app used the suggestion: it becomes the current element (Up / Down move from it)
    adopt: function () {
      var el = suggested;
      clearSuggestion();
      if (!el) return "none";
      current = el;
      return pathOf(el);
    },
    unsuggest: function () {
      clearSuggestion();
      return "ok";
    },
    // r100: "Not this": the pick's outlines go, the labelled ones of [selector] too
    drop: function (selector) {
      clearMarks();
      var els = selector ? queryAll(selector) : [];
      for (var i = 0; i < els.length; i++) els[i].classList.remove("__lab-kept");
      return els.length;
    },
    // r100: the choices a filter on [param] can take, sent through LabPicker.options (console "LABOPTS:"):
    // a <select name=param>'s options, else the values of links whose address carries param= (max 60). Works in
    // Browse mode too.
    optionsFor: function (param) {
      param = String(param || "");
      var out = [];
      var seen = {};
      function add(label, value) {
        if (out.length >= 60 || value === null || value === undefined) return;
        value = String(value);
        if (seen["v:" + value]) return;
        seen["v:" + value] = true;
        out.push({ label: String(label || value).replace(/\s+/g, " ").trim().slice(0, 80) || value, value: value });
      }
      var select = null;
      try {
        var quoted = window.CSS && CSS.escape ? CSS.escape(param) : param.replace(/["\\]/g, "\\$&");
        select = document.querySelector('select[name="' + quoted + '"]');
      } catch (e) { select = null; }
      if (select) {
        for (var i = 0; i < select.options.length; i++) add(select.options[i].text, select.options[i].value);
      } else if (param) {
        var links = queryAll("a[href]");
        for (var j = 0; j < links.length && out.length < 60; j++) {
          var href = links[j].getAttribute("href") || "";
          if (href.indexOf(param + "=") < 0) continue;
          try {
            var value = new URL(links[j].href, location.href).searchParams.get(param);
            if (value !== null) add(snippetOf(links[j]) || value, value);
          } catch (e2) { /* not an address */ }
        }
      }
      send("options", JSON.stringify({ param: param, options: out }));
      send("log", "options for " + param + ": " + out.length + (select ? " (select)" : " (links)"));
      return out.length;
    },
  };
  send("log", "ready (" + mode + ") on " + location.href + " \u00b7 bridge " + (bridgeHas("pick") ? "yes" : "no, console"));
})();
