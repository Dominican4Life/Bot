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
(function () {
  var STYLE_ID = "__lab-picker-style";
  var STYLE_TEXT =
    ".__lab-like{outline:2px dashed #7aa2ff !important;outline-offset:1px !important}" +
    ".__lab-picked{outline:3px solid #2962ff !important;outline-offset:1px !important}" +
    ".__lab-kept{outline:2px solid #4cd3c2 !important;outline-offset:-1px !important}" +
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
    if (window.LabPicker && window.LabPicker.log) window.LabPicker.log("picker already on " + location.href + " (" + window.__labPicker.mode + ")");
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

  function clearMarks() {
    var marked = document.querySelectorAll(".__lab-like, .__lab-picked");
    for (var i = 0; i < marked.length; i++) {
      marked[i].classList.remove("__lab-like");
      marked[i].classList.remove("__lab-picked");
    }
  }

  function describe(el) {
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

  function mark(el) {
    clearMarks();
    var likes = queryAll(likeSelector(pathOf(el)));
    for (var i = 0; i < likes.length; i++) likes[i].classList.add("__lab-like");
    el.classList.add("__lab-picked");
  }

  function onTap(event) {
    if (mode !== "pick") return;
    var el = event.target;
    if (!el || !el.tagName) return;
    // The default action (following the link, submitting) and the site's own handlers are stopped even for a
    // tap on the background, so picking mode never navigates
    event.preventDefault();
    event.stopPropagation();
    if (el.tagName.toLowerCase() === "html" || el.tagName.toLowerCase() === "body") return;
    // A tap on text inside a link or an image's wrapper means the link or the image
    if (el.tagName.toLowerCase() !== "a" && el.tagName.toLowerCase() !== "img" && el.tagName.toLowerCase() !== "iframe" && el.tagName.toLowerCase() !== "video") {
      var inner = el.querySelector("img, iframe, video");
      var link = el.closest("a");
      if (inner && el.children.length === 1) el = inner;
      else if (link && String(el.textContent || "").trim().length > 0 && String(el.textContent || "").trim() === String(link.textContent || "").trim()) el = link;
    }
    mark(el);
    try {
      if (window.LabPicker && window.LabPicker.pick) window.LabPicker.pick(JSON.stringify(describe(el)));
    } catch (e) {
      if (window.LabPicker && window.LabPicker.log) window.LabPicker.log("pick failed: " + e.message);
    }
  }

  // In pick mode the site's handlers never see the tap: the touch / pointer / mouse phases are stopped at the
  // document in the capture phase (Android fires them before the click, and sites open lightboxes or route
  // from them), without preventDefault, so scrolling and the browser's synthesized click still happen; that
  // click is then taken by onTap, which prevents the link's own navigation.
  function swallow(event) {
    if (mode !== "pick") return;
    event.stopPropagation();
  }
  var EARLY = ["pointerdown", "pointerup", "mousedown", "mouseup", "touchstart", "touchend"];
  for (var e = 0; e < EARLY.length; e++) document.addEventListener(EARLY[e], swallow, true);
  // auxclick: a stylus button or long press on some keyboards; dblclick: a second quick tap
  document.addEventListener("click", onTap, true);
  document.addEventListener("auxclick", onTap, true);
  document.addEventListener("dblclick", function (ev) { if (mode === "pick") { ev.preventDefault(); ev.stopPropagation(); } }, true);
  document.addEventListener("submit", function (ev) { if (mode === "pick") ev.preventDefault(); }, true);
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
      if (mode === "browse") clearMarks();
      if (window.LabPicker && window.LabPicker.log) window.LabPicker.log("picker mode " + mode + " on " + location.href);
      return mode;
    },
    // Keeps the outline on the elements already labelled (called by the app after a label is given)
    keep: function (selector) {
      var els = queryAll(selector);
      for (var i = 0; i < els.length; i++) els[i].classList.add("__lab-kept");
      return els.length;
    },
    clear: function () {
      clearMarks();
      var kept = document.querySelectorAll(".__lab-kept");
      for (var i = 0; i < kept.length; i++) kept[i].classList.remove("__lab-kept");
    },
    count: function (selector) { return queryAll(selector).length; },
  };
  if (window.LabPicker && window.LabPicker.log) window.LabPicker.log("picker ready (" + mode + ") on " + location.href);
})();
