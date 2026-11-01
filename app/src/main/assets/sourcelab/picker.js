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
// r99 (owner's 123av run): (1) a tap on the site's own controls opened them (a "Type: All" dropdown opened under
// the tap and its option became the Item pick): in pick mode pointerdown / mousedown / touchstart are now taken at
// the window (capture, not passive) and their default is prevented on interactive targets (button, select, input,
// label, summary, [role=button], [aria-haspopup] …), so no focus, no menu; a touch on one is picked on touchend
// (its click never comes). Other touches pass (scrolling works, touchmove is never prevented) and their click is
// cancelled. (2) Every tap on a title page came out as a bare `a` ×109: an invisible link over the whole page took
// it. The tapped element is now resolved with document.elementsFromPoint, skipping links / divs that cover half the
// viewport, transparent ones and positioned boxes with no text and no picture, and taking the innermost element with
// visible text or a picture. The path is never a bare tag. (3) window.__labPicker.up() / .down() move the pick to
// the parent / the first meaningful child and send it again (via "up" / "down"), for the app's Up / Down buttons.
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

  function send(kind, text) {
    var method = kind === "pick" ? "pick" : "log";
    if (bridgeHas(method)) {
      try {
        LabPicker[method](text);
        return;
      } catch (e) {
        // falls through to the console
      }
    }
    try { console.log((kind === "pick" ? "LABPICK:" : "LABLOG:") + TOKEN + ":" + text); } catch (e2) { /* nothing left to try */ }
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

  function clearMarks() {
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
    return {
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
    up: function () {
      if (mode !== "pick" || !current) return "none";
      var p = current.parentElement;
      if (!p || /^(body|html)$/i.test(p.tagName)) { send("log", "up: already at the top"); return "top"; }
      pickElement(p, "up");
      return pathOf(p);
    },
    down: function () {
      if (mode !== "pick" || !current) return "none";
      var c = firstMeaningfulChild(current);
      if (!c) { send("log", "down: " + shortName(pathOf(current)) + " has no child with text or a picture"); return "none"; }
      pickElement(c, "down");
      return pathOf(c);
    },
  };
  send("log", "ready (" + mode + ") on " + location.href + " \u00b7 bridge " + (bridgeHas("pick") ? "yes" : "no, console"));
})();
