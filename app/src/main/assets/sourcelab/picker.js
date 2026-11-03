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
//
// r101: (1) the site's search box: on every page load (and on .readForm()) the first GET form with a search field is
// sent as { action, method, field, hidden } through LabPicker.form / console "LABFORM:<token>:", so the app records
// the real search address instead of guessing /search?q=. (2) Group pick: .groupMode(true) makes the next tap (Pick
// or Browse mode) pick a whole list instead (a genre menu, a sort menu, a <select>): never navigates, sends
// { pageUrl, tapped, likeSelector, count, items, isSelect, heading, sameList } through LabPicker.group / console
// "LABGROUP:<token>:" and turns itself off; hidden entries (collapsed submenus) are included. (3)
// .suggestSelector(css, label): the app's own suggestion (the Item derived from the Title and Cover picks), outlined
// and sent back like suggest(). (4) Every pick carries pageDate: the first date-looking text of the page ("Release
// date 2026-10-29"), so the app can say "tap the release date as Date".
//
// r102 ("Tapping for…", the owner's tablet run: the Pick / Browse switch kept him on step 1's labels, and a tap on a
// dropdown's button picked the 4 buttons): (1) window.__labPicker.arm(label | null). Armed = the next tap is a pick
// for that label (never navigates, the r99 rules), sent with `armed: label`, then the page disarms itself, except
// "tags", which stays armed until the app disarms it (each tap adds a group). Nothing armed = the page is a normal
// browser (links, menus, scrolling), and a long press (≥ 500 ms without moving) on an element picks it (via "hold":
// the app's label chips then name it); the click that follows a long press is swallowed. setMode("pick" | "browse")
// stays for old callers: "pick" = armed for anything and never disarms (the r96–r101 behaviour, which is also the
// default when the app put no `window.__labArmed` before this script). (2) Every pick carries its row: rowLabel (the
// dt of its dd, the th / first cell of its td, a label / strong / b / span[class*=label|key|title] before it in its
// row; ≤ 30 characters, colon stripped) and row { kind, label, selector (a Jsoup selector for the row's values, by
// the label's text), target ("tags" when the row holds links / chips, else "description"), values, chips }.
// (3) A group pick carries `opener` (the css path of the dropdown button that opens the list: aria-controls, else
// the nearest preceding / ancestor-sibling element with aria-haspopup / aria-expanded / a select|dropdown|toggle
// class) and `tappedOpener` (the tapped entry is itself a menu's button). (4) Video: ad-network iframes (pnonolet,
// exoclick, …) are never the Video pick or suggestion: the real player under or near the tap is used.
//
// r103 (the owner's r102 video: "Tapping for…" ate the screen; with Tags armed he tapped the side menu's headings and
// got one lumped group): (1) .menuMode(true): the next tap picks a **menu group** (a heading / toggle and the list it
// controls, hidden entries included; a dropdown button and its menu; or the list around a tapped entry), sent as
// { pageUrl, tapped, groups: [{ heading, headingPath, opener, href, index, items: [{ text, href, value, path }],
// subgroups: [...] }] } through LabPicker.menu / console "LABMENU:"; a list of sub-headings (Collections → Genres /
// Actresses…) gives one subgroup each. .outlineItems(paths) outlines one group's entries. (2) Every pick carries
// docIndex (its place in the document; a row's label's for rows: Tags groups follow the page's order), rowExact (the
// exact row of an unlabelled tag list) and inMenu (it sits in the site's menus: never a title's tags). (3) Description
// armed and the tap lands on a chip / link / short text: the description block near it is picked instead. (4) arm()
// shows no badge: the app shows "Tapping for: X · Stop" over the page.
// r103 fix pass (tester): inMenu only warns in the app, and tag links (rel=tag, .tags-links, .cat-links, [itemprop])
// and a title box's own header / footer are never the menu; picks carry likeRows (every row of the look: a restored
// Tags group finds its row); a dropdown whose menu is added only when it opens sends groups [] + hint; a heading's
// short note line ("Browse 120 categories") is skipped before its list; a menu pick outlines only a single group's
// own entries (the app outlines a group after Make filter / Learn addresses); Description redirects links / chips only.
//
// r104 (the owner's 123av run: his Tags groups were whole Details rows "Code FC2-PPV…" and the rows' labels "Code,
// Type…"): (1) a Tags tap inside a details block (a dl, a table of label / value rows, or a box of label / value rows),
// on a row, its label or its value, sends `rows`: one entry per row of the block, in page order { kind, label,
// selector (anchored on the label: `dt:matches(^\s*Cast…$) + dd a.chip`), target (tags when the row holds links /
// chips, else description), values (the value elements only, never the label), chips, order }. .rowsFor(css) does the
// same for the first element of [css] (the app's "Split into rows" of a restored whole-row group), answered as a pick
// with via "split". (2) "suggestions" stays armed like Tags: a tap on a card inside a "You may like" / "Related"
// section sends `suggest` { name (the section's heading), section, item (the section's repeated card, scoped under
// the section), title, cover, coverAttr, count, order, samples }.
//
// r106 (Suggestions cards): a Suggestions tap also sends the section's "Load more" / "Show more" button when one sits
// under it (`suggest.more` { button, text }); a Suggestions tap ON such a button sends `loadMore` { button, text,
// section (the headings before it, nearest first, one per line) } instead of a section. .scanMore({ id, item, button,
// text }) presses the button once (the picker lets that click through), waits until the section's cards stop growing
// (10 s at most) and answers as a pick { via: "more", id, ok, before, after, requests (the page's fetch / XHR addresses
// since the press), samples (titles of the new cards), reason }.
//
// KMK (r109, a directory's custom drop-downs and pictures): a menu pick's items carry `current: true` for the entry the
// site marks as chosen (active / selected / aria-selected / aria-current / a selected option): it names the menu's
// default. .pictureMode({ item } | null): the next tap names one card's picture (the cards are [item]'s elements): an
// img, or an element with a background picture, at or around the tap; answered as a pick { via: "picture", ok,
// selectors (its own tag and classes, then the steps down from the card: "> a > div.avatar > img"), attrs (the
// attributes that hold its address), url, reason }. Never navigates.
(function () {
  var TOKEN = "";
  try {
    TOKEN = String(window.__labToken || "");
    delete window.__labToken;
  } catch (e0) { /* nothing */ }
  // r102: what the app had armed when it injected this script (a label, or null = nothing); absent = old callers
  var ARMED_INIT;
  var ARMED_GIVEN = false;
  try {
    if (Object.prototype.hasOwnProperty.call(window, "__labArmed")) {
      ARMED_GIVEN = true;
      ARMED_INIT = window.__labArmed;
      delete window.__labArmed;
    }
  } catch (eArm) { /* nothing */ }

  function bridgeHas(name) {
    try {
      return typeof LabPicker !== "undefined" && LabPicker && typeof LabPicker[name] === "function";
    } catch (e) {
      return false;
    }
  }

  // r100: "options" (optionsFor's answer) goes to LabPicker.options, console "LABOPTS:<token>:<json>"
  // r101: "form" (the site's search box) → LabPicker.form / "LABFORM:", "group" (a group pick) → LabPicker.group /
  // "LABGROUP:"
  // r103: "menu" (a menu group pick) → LabPicker.menu / "LABMENU:"
  var METHODS = { pick: "pick", options: "options", form: "form", group: "group", menu: "menu", log: "log" };
  var PREFIXES = { pick: "LABPICK:", options: "LABOPTS:", form: "LABFORM:", group: "LABGROUP:", menu: "LABMENU:", log: "LABLOG:" };
  function send(kind, text) {
    var method = METHODS[kind] || "log";
    if (bridgeHas(method)) {
      try {
        LabPicker[method](text);
        return;
      } catch (e) {
        // falls through to the console
      }
    }
    var prefix = PREFIXES[kind] || "LABLOG:";
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
    // r102: the app's armed label follows into the same window
    if (ARMED_GIVEN && window.__labPicker.arm) { try { window.__labPicker.arm(ARMED_INIT); } catch (eA) { /* nothing */ } }
    applyModeClass(window.__labPicker.mode);
    send("log", "already on " + location.href + " (" + window.__labPicker.mode + ")");
    // r101: an in-page route change may bring another search box
    try { if (window.__labPicker.readForm) window.__labPicker.readForm(); } catch (eForm) { /* nothing */ }
    return;
  }
  ensureStyle();
  var mode = "pick";
  // r102: the label the next tap is for (null with mode "pick" = old callers: every tap picks, nothing disarms);
  // sticky = stays armed after a pick (Tags, and the old pick mode)
  var armed = null;
  var sticky = true;
  if (ARMED_GIVEN) {
    armed = ARMED_INIT ? String(ARMED_INIT) : null;
    mode = armed ? "pick" : "browse";
    sticky = stickyLabel(armed);
  }
  applyModeClass(mode);

  // r104: Tags and Suggestions stay armed (each tap adds a group) until the app disarms them
  function stickyLabel(label) { return label === "tags" || label === "suggestions"; }

  var GENERATED = /^(css|sc|jss|emotion)-[a-z0-9]{4,}$|^[A-Za-z]+_[A-Za-z0-9]{5,}$|^_[a-z0-9]{5,}$/;

  function classesOf(el) {
    var list = [];
    var raw = (el.getAttribute("class") || "").split(/\s+/);
    for (var i = 0; i < raw.length; i++) {
      var c = raw[i];
      if (!c || c.indexOf("__lab-") === 0) continue;
      if (/[^A-Za-z0-9_-]/.test(c)) continue;
      if (/^(active|hover|selected|current|lazy|loaded|loading|visible|hidden)$/.test(c)) continue;
      // r102 fix: state a menu / tab has only while open or touched (an opener tapped open, a hovered chip): a path
      // with it matches nothing on the next load (likeSelector, items[].path and opener all come from here)
      if (/^(open|opened|show|shown|focus|focused|expanded|is-[A-Za-z0-9_-]+|has-[A-Za-z0-9_-]+)$/.test(c)) continue;
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

  // r102: one element exactly (positions on every segment that has same-tag siblings): the dropdown button learn.js
  // clicks, where the class path alone would match every menu's button
  function exactPath(el) {
    var parts = [];
    var node = el;
    while (node && node.tagName && !/^(body|html)$/i.test(node.tagName) && parts.length < 10) {
      var seg = segment(node);
      if (seg.charAt(0) !== "#" && seg.indexOf(":nth-of-type(") < 0 && node.parentElement) {
        var same = 0;
        var index = 0;
        for (var i = 0; i < node.parentElement.children.length; i++) {
          if (node.parentElement.children[i].tagName === node.tagName) {
            same++;
            if (node.parentElement.children[i] === node) index = same;
          }
        }
        if (same > 1) seg += ":nth-of-type(" + index + ")";
      }
      parts.unshift(seg);
      if (seg.charAt(0) === "#") break;
      node = node.parentElement;
    }
    if (parts.length && parts[0].charAt(0) !== "#" && node && /^body$/i.test(node.tagName)) parts.unshift("body");
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
    var row = null;
    try { row = rowInfo(el); } catch (eRow) { row = null; }
    var exact = "";
    try { exact = rowExactOf(el); } catch (eEx) { exact = ""; }
    var likeRows = [];
    try { likeRows = rowsOfLikes(likes); } catch (eRows) { likeRows = []; }
    return {
      // r103: where it sits in the document (Tags groups are kept in the page's order), the exact row of an
      // unlabelled tag list, and whether it is in the site's menus (never a title's tags)
      docIndex: rowBoxIndex(el),
      rowExact: exact,
      // r103 fix: every row of this look on the page (its exact place, its place in the document, its first
      // texts): a Tags group saved before rows had places finds its own row again (the app reads it once)
      likeRows: likeRows,
      inMenu: inSiteMenu(el) && !(row && (row.kind === "dl" || row.kind === "table")),
      // r102: the row the element sits in (Details "Cast  [Reina Ohara]") and its own label; "" / null when none
      rowLabel: row ? row.label : "",
      row: row,
      imgCount: countIn(el, PICTURE),
      linkCount: countIn(el, "a[href]"),
      textLen: String(el.textContent || "").replace(/\s+/g, " ").trim().length,
      distinctHrefs: distinctCount,
      boundByScript: boundByScript(el),
      nextLike: nextLike(el),
      rect: { x: Math.round(r.left), y: Math.round(r.top), w: Math.round(r.width), h: Math.round(r.height) },
      samples: samples,
      pageDate: pageDate(),
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

  // r101: the first date-looking text of the page (a written date, not "3 days ago": those are the related
  // list's), with the label in front of it when the element before it says Release / Date / Published / Aired
  // (a Details block "Release date  2026-10-29"); "" when there is none. Cached per address.
  var DATE_TEXT = /\b(19|20)\d{2}[-\/.]\d{1,2}[-\/.]\d{1,2}\b|\b\d{1,2}[-\/.]\d{1,2}[-\/.](19|20)\d{2}\b|\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?\s+\d{1,2}(st|nd|rd|th)?,?\s+(19|20)\d{2}\b|\b\d{1,2}\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?,?\s+(19|20)\d{2}\b/i;
  var DATE_LABEL = /(release|date|published|posted|aired|premiered|uploaded|added)/i;
  var pageDateCache = { url: "", value: "" };
  function pageDate() {
    if (pageDateCache.url === location.href) return pageDateCache.value;
    var found = "";
    var labelled = "";
    try {
      var walker = document.createTreeWalker(document.body || document.documentElement, NodeFilter.SHOW_TEXT, null);
      var node;
      var seen = 0;
      while ((node = walker.nextNode()) && seen < 4000 && !labelled) {
        seen++;
        var parent = node.parentElement;
        if (!parent || /^(script|style|noscript|template|option)$/i.test(parent.tagName) || parent.id === "__lab-badge") continue;
        var t = String(node.nodeValue || "").replace(/\s+/g, " ").trim();
        if (!t || t.length > 60 || !DATE_TEXT.test(t)) continue;
        // The label: the text before the date in its row (the previous element, or the same text node)
        var before = "";
        var prev = parent.previousElementSibling;
        if (prev) before = String(prev.textContent || "").replace(/\s+/g, " ").trim().slice(0, 30);
        if (!before && parent.parentElement && parent.parentElement.firstElementChild !== parent) {
          before = String(parent.parentElement.firstElementChild.textContent || "").replace(/\s+/g, " ").trim().slice(0, 30);
        }
        var own = t.replace(DATE_TEXT, "").replace(/[:\s]+$/, "").trim();
        if (DATE_LABEL.test(own)) labelled = t;
        else if (DATE_LABEL.test(before)) labelled = (before.replace(/[:\s]+$/, "") + " " + t).trim();
        if (!found) found = t;
      }
    } catch (e) { /* no tree walker */ }
    pageDateCache = { url: location.href, value: (labelled || found).slice(0, 80) };
    return pageDateCache.value;
  }

  // ---------- r102: the row a pick sits in (a Details block: "Cast  [Reina Ohara]", "Release date  2026-10-29") ----------

  function textOf(el) { return String((el && el.textContent) || "").replace(/\s+/g, " ").trim(); }

  // The row's label as the app names the group: ≤ 30 characters, the colon at its end dropped; "" when not a label
  function cleanRowLabel(t) {
    t = String(t || "").replace(/\s+/g, " ").trim().replace(/\s*[:\uff1a]\s*$/, "").trim();
    return t && t.length <= 30 ? t : "";
  }

  // A label element before the values: label / strong / b / dt / th, a span / div / p whose class says label / key /
  // title / name, or a short text ending in ":" with no link in it
  function labelLike(el) {
    if (!el || el.nodeType !== 1 || el.id === "__lab-badge") return false;
    var tag = el.tagName.toLowerCase();
    if (/^(label|strong|b|dt|th)$/.test(tag)) return true;
    if (tag === "span" || tag === "div" || tag === "p" || tag === "em" || tag === "small") {
      if (el.querySelector("a[href]")) return false;
      if (/label|key|title|name|head/i.test(String(el.getAttribute("class") || ""))) return true;
      if (/:\s*$/.test(textOf(el))) return true;
    }
    return false;
  }

  // The label as a Jsoup regex for :matches (whole text): regex characters become "." (they can't break the
  // selector's parentheses), spaces \s+, an optional colon at the end
  function labelRegex(label) {
    var body = String(label).replace(/[\\^$*+?()\[\]{}|"',;]/g, ".").replace(/\s+/g, "\\s+");
    // r102 fix: the full-width colon first: a class starting "[:" reads as a POSIX set to ICU (Android's regex)
    return "^\\s*" + body + "\\s*[\uff1a:]?\\s*$";
  }

  var CHIP_CLASS = /chip|tag|badge|pill|genre|cast|actor|actress|model|studio|maker|categor/i;

  // The values of a row: its links, else elements whose class says chip / tag / badge…; [] = a plain text row
  function chipsIn(nodes, exclude) {
    var links = [];
    var classed = [];
    for (var i = 0; i < nodes.length; i++) {
      var n = nodes[i];
      if (!n || n.nodeType !== 1 || n === exclude) continue;
      var found = n.matches && n.matches("a[href]") ? [n] : Array.prototype.slice.call(n.querySelectorAll("a[href]"));
      for (var f = 0; f < found.length; f++) if (textOf(found[f])) links.push(found[f]);
      var all = [n].concat(Array.prototype.slice.call(n.querySelectorAll("*")));
      for (var c = 0; c < all.length && classed.length < 200; c++) {
        if (all[c] !== exclude && CHIP_CLASS.test(String(all[c].getAttribute("class") || "")) && textOf(all[c]) && !all[c].querySelector("[class]")) classed.push(all[c]);
      }
    }
    return links.length ? links : classed;
  }

  function rowResult(kind, labelEl, label, base, siblingBase, chips, plainText) {
    var values = [];
    var seen = {};
    for (var i = 0; i < chips.length && values.length < 8; i++) {
      var t = textOf(chips[i]).slice(0, 60);
      if (t && !seen[t]) { seen[t] = true; values.push(t); }
    }
    var selector;
    if (chips.length) {
      var chipSeg = likeSelector(segment(chips[0]));
      if (chipSeg.charAt(0) === "#") chipSeg = chips[0].tagName.toLowerCase();
      selector = siblingBase ? siblingBase + " ~ " + chipSeg + ", " + siblingBase + " ~ * " + chipSeg : base + " " + chipSeg;
    } else {
      selector = base;
      var t2 = String(plainText || "").replace(/\s+/g, " ").trim().slice(0, 80);
      if (t2) values.push(t2);
    }
    return {
      kind: kind,
      label: label,
      // r103: the row's place in the document (its label's), for the Tags groups' order
      order: docIndex(labelEl),
      selector: selector,
      target: chips.length ? "tags" : "description",
      values: values,
      chips: chips.length,
      labelPath: pathOf(labelEl),
    };
  }

  // The row [el] sits in and its label: dd ← its dt, td ← the row's th (or first cell), else a label element before
  // it in its parent (up to 4 levels up). Null when there is none.
  function rowInfo(el) {
    if (!el || !el.closest) return null;
    var dd = el.closest("dd");
    if (dd) {
      var dt = dd.previousElementSibling;
      while (dt && dt.tagName.toLowerCase() === "dd") dt = dt.previousElementSibling;
      var dl = dt && dt.tagName.toLowerCase() === "dt" ? cleanRowLabel(textOf(dt)) : "";
      if (dl) return rowResult("dl", dt, dl, "dt:matches(" + labelRegex(dl) + ") + dd", null, chipsIn([dd], null), textOf(dd));
    }
    var cell = el.closest("td");
    if (cell && cell.parentElement) {
      var tr = cell.parentElement;
      var head = null;
      for (var i = 0; i < tr.children.length; i++) {
        var ch = tr.children[i];
        if (ch === cell) break;
        if (/^(th|td)$/i.test(ch.tagName)) { head = ch; break; }
      }
      var tl = head ? cleanRowLabel(textOf(head)) : "";
      if (tl) {
        var htag = head.tagName.toLowerCase();
        return rowResult("table", head, tl, htag + ":matches(" + labelRegex(tl) + ") ~ td", null, chipsIn([cell], null), textOf(cell));
      }
    }
    var node = el;
    for (var level = 0; level < 4 && node && node.parentElement; level++, node = node.parentElement) {
      var parent = node.parentElement;
      if (/^(body|html)$/i.test(parent.tagName)) break;
      var sib = node.previousElementSibling;
      for (var hop = 0; sib && hop < 6; hop++, sib = sib.previousElementSibling) {
        if (!labelLike(sib)) continue;
        var gl = cleanRowLabel(textOf(sib));
        if (!gl) break;
        // The values: the label's later siblings (and what they hold)
        var after = [];
        for (var n = sib.nextElementSibling; n; n = n.nextElementSibling) after.push(n);
        var labSeg = likeSelector(segment(sib));
        var sibBase = labSeg + ":matches(" + labelRegex(gl) + ")";
        var chips = chipsIn(after, sib);
        if (chips.length) return rowResult("row", sib, gl, sibBase, sibBase, chips, "");
        // Plain text: the next element when there is one, else the row itself (its text then starts with the label)
        var rest = textOf(parent).replace(textOf(sib), "").trim();
        var base = after.length ? sibBase + " + " + likeSelector(segment(after[0])) : likeSelector(pathOf(parent));
        if (after.length && after[0].id) base = sibBase + " + " + after[0].tagName.toLowerCase();
        return rowResult("row", sib, gl, base, null, [], after.length ? textOf(after[0]) : rest);
      }
    }
    return null;
  }

  // ---------- r104: a details block's rows (Tags = the site's rows) ----------

  // A box of label / value rows: its first child a label (labelLike, ≤ 30 characters), the next one a value with text
  // or links; no picture in it (a card with a title and a picture is no row)
  function rowPairOf(row) {
    if (!row || row.nodeType !== 1 || row.id === "__lab-badge") return null;
    var kids = row.children;
    if (kids.length < 2 || kids.length > 6) return null;
    var lab = kids[0];
    if (!labelLike(lab)) return null;
    var label = cleanRowLabel(textOf(lab));
    if (!label) return null;
    var value = kids[1];
    if (!textOf(value)) return null;
    try { if (row.querySelector("img, picture, video, iframe")) return null; } catch (e) { return null; }
    return { labelEl: lab, valueEl: value, label: label };
  }

  function boxRows(box) {
    var out = [];
    var labels = {};
    var counted = 0;
    for (var i = 0; i < box.children.length; i++) {
      var c = box.children[i];
      if (/^(script|style|template|noscript)$/i.test(c.tagName)) continue;
      counted++;
      var p = rowPairOf(c);
      if (!p || labels[p.label.toLowerCase()]) continue;
      labels[p.label.toLowerCase()] = true;
      out.push(p);
    }
    return out.length >= 2 && out.length * 2 >= counted ? out : [];
  }

  // A dl's rows: each dt (also inside div wrappers of the dl) and the dd after it
  function dlRows(dl) {
    var out = [];
    var dts = dl.querySelectorAll("dt");
    for (var i = 0; i < dts.length; i++) {
      if (dts[i].closest("dl") !== dl) continue;
      var dd = dts[i].nextElementSibling;
      while (dd && !/^(dd|dt)$/i.test(dd.tagName)) dd = dd.nextElementSibling;
      if (!dd || !/^dd$/i.test(dd.tagName)) continue;
      var label = cleanRowLabel(textOf(dts[i]));
      if (label) out.push({ labelEl: dts[i], valueEl: dd, label: label });
    }
    return out;
  }

  // A table's rows: the first cell (th, or a short td) is the label, the next cell the value
  function tableRows(table) {
    var out = [];
    var trs = table.querySelectorAll("tr");
    for (var i = 0; i < trs.length; i++) {
      if (trs[i].closest("table") !== table) continue;
      var cells = Array.prototype.filter.call(trs[i].children, function (c) { return /^(th|td)$/i.test(c.tagName); });
      if (cells.length < 2) continue;
      var label = cleanRowLabel(textOf(cells[0]));
      if (label && textOf(cells[1])) out.push({ labelEl: cells[0], valueEl: cells[1], label: label });
    }
    return out;
  }

  // The details block [el] sits in (or is): a dl, a table or a box of ≥ 2 label / value rows, up to 8 levels up
  function detailBlock(el) {
    var node = el;
    for (var up = 0; node && node.nodeType === 1 && up < 8 && !/^(body|html)$/i.test(node.tagName); up++, node = node.parentElement) {
      var tag = node.tagName.toLowerCase();
      if (tag === "dl") { if (dlRows(node).length >= 2) return { box: node, pairs: dlRows(node) }; continue; }
      if (tag === "table") { if (tableRows(node).length >= 2) return { box: node, pairs: tableRows(node) }; continue; }
      var rows = boxRows(node);
      if (rows.length >= 2) return { box: node, pairs: rows };
    }
    return null;
  }

  // Every row of the block around [el], in page order: the r102 row (label-anchored selector, values without the
  // label, tags / description target) of each row's value element; [] when [el] is in no details block
  function detailRows(el) {
    var block = detailBlock(el);
    if (!block) return [];
    var out = [];
    var seen = {};
    for (var i = 0; i < block.pairs.length && out.length < 40; i++) {
      var p = block.pairs[i];
      var r = null;
      try { r = rowInfo(p.valueEl); } catch (e) { r = null; }
      if (!r || !r.selector || seen[r.selector]) continue;
      seen[r.selector] = true;
      out.push({
        kind: r.kind,
        label: r.label || p.label,
        selector: r.selector,
        target: r.target,
        values: r.values,
        chips: r.chips,
        order: docIndex(p.labelEl),
      });
    }
    out.sort(function (a, b) { return a.order - b.order; });
    return out;
  }

  // ---------- r104: Suggestions (a "You may like" / "Related videos" section's cards) ----------

  var HEADING_CLASS = /(^|[\s_-])(title|heading|header|head|caption)([\s_-]|$)/i;

  function hrefsIn(el) {
    var set = {};
    var n = 0;
    var links = [];
    if (el.tagName && el.tagName.toLowerCase() === "a" && el.hasAttribute("href")) links.push(el);
    try { links = links.concat(Array.prototype.slice.call(el.querySelectorAll("a[href]"))); } catch (e) { /* nothing */ }
    for (var i = 0; i < links.length; i++) {
      var h = String(links[i].href || "").split("#")[0];
      if (h && !/^javascript:/i.test(h) && !set[h]) { set[h] = true; n++; }
    }
    return n;
  }

  // A segment for a selector shared by every card: no id (an id is one element only), no position
  function shareSegment(n) {
    var seg = likeSelector(segment(n));
    return seg.charAt(0) === "#" ? n.tagName.toLowerCase() : seg;
  }

  // [el] under [root] as "a > b > c" (root itself excluded); "" when el is root
  function relPath(root, el) {
    var parts = [];
    for (var n = el; n && n !== root && n.nodeType === 1; n = n.parentElement) parts.unshift(shareSegment(n));
    return parts.join(" > ");
  }

  // The card the tap is in: the biggest box around it that opens one title (one address) and repeats on the page
  function cardAt(el) {
    var best = null;
    var firstRepeated = null;
    var node = el;
    for (var up = 0; node && node.nodeType === 1 && up < 8 && !/^(body|html)$/i.test(node.tagName); up++, node = node.parentElement) {
      var links = hrefsIn(node);
      if (links > 1) break;
      if (links === 1 && queryAll(likeSelector(pathOf(node))).length >= 2) {
        if (!firstRepeated) firstRepeated = node;
        best = node;
      }
    }
    return best || firstRepeated;
  }

  function headingText(h) {
    var t = textOf(h);
    var links = h.querySelectorAll ? h.querySelectorAll("a[href]") : [];
    var own = t;
    for (var i = 0; i < links.length; i++) own = own.replace(textOf(links[i]), " ");
    own = own.replace(/\s+/g, " ").trim();
    return (own || t).replace(/\s*[:：]\s*$/, "").trim();
  }

  function headingLike(h) {
    if (!h || h.nodeType !== 1 || h.id === "__lab-badge") return false;
    var t = headingText(h);
    if (t.length < 2 || t.length > 60) return false;
    return /^h[1-6]$/i.test(h.tagName) || HEADING_CLASS.test(String(h.getAttribute("class") || ""));
  }

  // A heading inside [box] before [card], not inside any card like it
  function headingIn(box, card, cardSeg) {
    var cands = box.querySelectorAll("h1, h2, h3, h4, h5, h6, [class*=title], [class*=heading], [class*=head], [class*=caption]");
    for (var i = 0; i < cands.length && i < 300; i++) {
      var h = cands[i];
      if (!headingLike(h) || h.contains(card) || card.contains(h)) continue;
      try { if (h.closest(cardSeg) && box.contains(h.closest(cardSeg))) continue; } catch (e) { /* a selector the browser can't read */ }
      // before the card in the page
      if (h.compareDocumentPosition(card) & 4) return h;
    }
    return null;
  }

  // The section around [card]: the smallest box holding ≥ 2 cards like it with a heading in it, or right before it
  function sectionOf(card) {
    var cardSeg = shareSegment(card);
    var node = card.parentElement;
    for (var up = 0; node && up < 8 && !/^(body|html)$/i.test(node.tagName); up++, node = node.parentElement) {
      var rel = relPath(node, card);
      var count = 0;
      try { count = node.querySelectorAll(":scope > " + rel).length; } catch (e) { count = 0; }
      if (count < 2) continue;
      var h = headingIn(node, card, cardSeg);
      if (h) return { box: node, heading: h, inside: true, rel: rel, count: count };
      var prev = node.previousElementSibling;
      for (var hop = 0; prev && hop < 2; hop++, prev = prev.previousElementSibling) {
        if (headingLike(prev)) return { box: node, heading: prev, inside: false, adjacent: hop === 0, rel: rel, count: count };
        var inner = null;
        try { inner = prev.querySelector("h1, h2, h3, h4, h5, h6"); } catch (e2) { inner = null; }
        if (inner && headingLike(inner) && !prev.querySelector("a[href] img")) {
          return { box: node, heading: prev, inside: false, adjacent: hop === 0, rel: rel, count: count, name: headingText(inner) };
        }
      }
    }
    return null;
  }

  var COVER_NAMES = ["data-src", "data-lazy-src", "data-original", "srcset", "src"];

  // r104 fix: an element's own text (its text nodes, not its children's), as Jsoup's ownText() / :matchesOwn read it
  function ownText(el) {
    var t = "";
    var kids = el && el.childNodes ? el.childNodes : [];
    for (var i = 0; i < kids.length; i++) if (kids[i].nodeType === 3) t += " " + kids[i].nodeValue;
    return t.replace(/\s+/g, " ").trim();
  }

  // r104 fix: the element of heading [h] (itself or inside it) whose own text is the section's name: "Related videos"
  // in <h2>Related videos <a>See all</a></h2> (the link's text is not its own); null when the name is split up
  function nameHolder(h, re) {
    var rx = null;
    try { rx = new RegExp(re); } catch (e) { return null; }
    if (rx.test(ownText(h))) return h;
    var all = h.querySelectorAll ? h.querySelectorAll("*") : [];
    for (var i = 0; i < all.length && i < 200; i++) if (rx.test(ownText(all[i]))) return all[i];
    return null;
  }

  // r104 fix: [selector] as Jsoup reads it (the template's getRelated): its :matchesOwn(re) / :matches(re) are
  // checked here (own text / whole text) and swapped for a temporary attribute, the rest is the browser's own.
  // null when the browser can't read it (an old WebView without :has)
  function jsoupAll(selector) {
    var marks = [];
    var k = 0;
    var css = String(selector).replace(/([^\s>+~(),]*):(matchesOwn|matches)\(([^()]*)\)/g, function (m, seg, kind, re) {
      var attr = "data-lab-m" + (k++);
      var rx = null;
      try { rx = new RegExp(re); } catch (e) { rx = null; }
      queryAll(seg || "*").forEach(function (n) {
        if (rx && rx.test(kind === "matchesOwn" ? ownText(n) : textOf(n))) { n.setAttribute(attr, ""); marks.push([n, attr]); }
      });
      return seg + "[" + attr + "]";
    });
    var out = null;
    try { out = Array.prototype.slice.call(document.querySelectorAll(css)); } catch (e) { out = null; }
    marks.forEach(function (x) { x[0].removeAttribute(x[1]); });
    return out;
  }

  // The group a Suggestions tap makes, or null (no repeated card, or no section with a heading around it)
  function suggestionOf(el) {
    var card = cardAt(el);
    if (!card) return null;
    var sec = sectionOf(card);
    if (!sec) return null;
    var name = (sec.name || headingText(sec.heading)).slice(0, 60) || "Suggestions";
    var re = labelRegex(name);
    var section;
    var boxPath = likeSelector(pathOf(sec.box));
    // r104 fix: the heading by its own text (:matchesOwn), so a link in it ("Related videos See all") doesn't stop
    // the match; a name split over several elements keeps :matches on the whole heading
    var holder = nameHolder(sec.heading, re);
    var own = holder ? ":matchesOwn(" + re + ")" : ":matches(" + re + ")";
    if (segment(sec.box).charAt(0) === "#") {
      section = segment(sec.box);
    } else if (queryAll(boxPath).length === 1) {
      section = boxPath;
    } else if (sec.inside) {
      section = boxPath + ":has(" + shareSegment(holder || sec.heading) + own + ")";
    } else {
      var headSel = likeSelector(pathOf(sec.heading));
      headSel += !holder || holder === sec.heading ? own : ":has(" + relPath(sec.heading, holder) + own + ")";
      section = headSel + (sec.adjacent ? " + " : " ~ ") + shareSegment(sec.box);
    }
    var link = card.tagName.toLowerCase() === "a" ? card :
      (card.querySelector("h1 a[href], h2 a[href], h3 a[href], h4 a[href], h5 a[href], h6 a[href]") ||
        Array.prototype.filter.call(card.querySelectorAll("a[href]"), function (a) { return !!textOf(a); })[0] ||
        card.querySelector("a[href]"));
    var nameEl = card.querySelector("h1, h2, h3, h4, h5, h6, [class*=title], [class*=name]");
    if (nameEl && !textOf(nameEl)) nameEl = null;
    var img = card.querySelector("img");
    var attr = "";
    if (img) {
      for (var i = 0; i < COVER_NAMES.length && !attr; i++) {
        var v = String(img.getAttribute(COVER_NAMES[i]) || "").trim();
        if (v && !/^data:/i.test(v)) attr = COVER_NAMES[i];
      }
    }
    var samples = [];
    var cards = [];
    var item = section + " > " + sec.rel;
    // r104 fix: the cards the template will read (the same selector, read like Jsoup), so the count can't disagree
    var read = jsoupAll(item);
    if (read === null) {
      send("log", "suggestions: this browser can't check " + item + ": counted by the section's own cards");
      try { cards = Array.prototype.slice.call(sec.box.querySelectorAll(":scope > " + sec.rel)); } catch (e) { cards = [card]; }
    } else {
      cards = read;
      if (!cards.length) {
        send("log", "suggestions \"" + name + "\": " + item + " finds no card: not added");
        return null;
      }
    }
    var nameRel = nameEl ? relPath(card, nameEl) : "";
    for (var k = 0; k < cards.length && samples.length < 3; k++) {
      var ne = nameRel ? cards[k].querySelector(nameRel) : null;
      var t = ne ? textOf(ne) : snippetOf(cards[k]);
      if (t) samples.push(t.slice(0, 60));
    }
    var more = null;
    try { more = moreNear(sec.box); } catch (eMore) { more = null; }
    if (more) send("log", "suggestions \"" + name + "\": load more button \"" + more.text + "\" " + more.button);
    return {
      name: name,
      section: section,
      item: item,
      link: link && link !== card ? relPath(card, link) : "",
      title: nameRel,
      cover: img ? relPath(card, img) : "",
      coverAttr: attr || "src",
      count: cards.length,
      order: docIndex(sec.box),
      samples: samples,
      more: more,
    };
  }

  // ---------- r106: a section's "Load more" button ----------

  var MORE_TEXT = /^(load|show|see|view|get)\s+more\b|^more(\s+(videos|titles|results|items|posts|movies|shows|episodes))?$|^(mehr|plus|m\u00e1s|lagi|thêm)\b/i;
  var MORE_CONTROLS = "button, a, [role=button], input[type=button], input[type=submit]";

  // [el] (or the control around it) when it is a "Load more" / "Show more" button: a short text saying so, and no link
  // to another page ("See more" opening a full list page is a link, not a load more)
  function loadMoreButton(el) {
    if (!el || el.nodeType !== 1 || el.id === "__lab-badge") return null;
    var b = (el.closest ? el.closest(MORE_CONTROLS) : null) || el;
    // a box around the real control (a wrapper div saying "Load more") is not it: its control is
    if (b === el && !(el.matches && el.matches(MORE_CONTROLS)) && el.querySelector && el.querySelector(MORE_CONTROLS)) return null;
    var t = String(textOf(b) || b.getAttribute("value") || b.getAttribute("aria-label") || "").replace(/[\s\u25be\u25bc\u2304\u203a\u00bb+]+$/, "").trim();
    if (!t || t.length > 40 || !MORE_TEXT.test(t)) return null;
    if (b.tagName.toLowerCase() === "a") {
      var h = String(b.getAttribute("href") || "").trim();
      if (h && !/^(#|javascript:)/i.test(h)) {
        try { if (new URL(h, location.href).pathname !== location.pathname) return null; } catch (eU) { return null; }
      }
    }
    return b;
  }

  function moreInfo(b) {
    var t = String(textOf(b) || b.getAttribute("value") || b.getAttribute("aria-label") || "").trim().slice(0, 40);
    return { button: exactPath(b), text: t };
  }

  // The Load more button of a section: inside its box, else in the next two elements after it or after its parent
  function moreNear(box) {
    var roots = [box];
    for (var n = box.nextElementSibling, hop = 0; n && hop < 2; n = n.nextElementSibling, hop++) roots.push(n);
    var parent = box.parentElement;
    if (parent && !/^(body|html)$/i.test(parent.tagName)) {
      for (var m = parent.nextElementSibling, hop2 = 0; m && hop2 < 1; m = m.nextElementSibling, hop2++) roots.push(m);
    }
    for (var i = 0; i < roots.length; i++) {
      var own = loadMoreButton(roots[i]) === roots[i] ? [roots[i]] : [];
      var cands = own;
      try { cands = own.concat(Array.prototype.slice.call(roots[i].querySelectorAll(MORE_CONTROLS))); } catch (e) { /* nothing */ }
      for (var k = 0; k < cands.length && k < 200; k++) {
        var b = loadMoreButton(cands[k]);
        if (b && shown(b)) return moreInfo(b);
      }
    }
    return null;
  }

  // The headings before [el] in its surroundings, nearest first (the app finds the section they name)
  function headingsBefore(el) {
    var out = [];
    var node = el.parentElement;
    for (var up = 0; node && up < 6 && !/^(body|html)$/i.test(node.tagName) && out.length < 30; up++, node = node.parentElement) {
      var hs = [];
      try { hs = Array.prototype.slice.call(node.querySelectorAll("h1, h2, h3, h4, h5, h6, [class*=title], [class*=heading], [class*=head]")); } catch (e) { hs = []; }
      for (var i = hs.length - 1; i >= 0 && out.length < 30; i--) {
        var h = hs[i];
        if (!(h.compareDocumentPosition(el) & 4) || !headingLike(h)) continue;
        var t = headingText(h).slice(0, 60);
        if (t && out.indexOf(t) < 0) out.push(t);
      }
    }
    return out;
  }

  // ---------- r103: document order, unlabelled rows, the site's menus ----------

  // The element's place among all elements of the document (-1 when unknown): rows tapped as Tags are kept in it
  function docIndex(el) {
    try { return Array.prototype.indexOf.call(document.getElementsByTagName("*"), el); } catch (e) { return -1; }
  }

  // An unlabelled tag list's exact place: the chips' own box (with positions) and the chips' look, so two such
  // lists are two groups ("div.tags:nth-of-type(2) > a.chip")
  function rowExactOf(el) {
    var chip = el.closest ? (el.closest("a[href]") || el) : el;
    var chipLike = chip.tagName.toLowerCase() === "a" || CHIP_CLASS.test(String(chip.getAttribute("class") || ""));
    if (chipLike && chip.parentElement && !/^(body|html)$/i.test(chip.parentElement.tagName)) {
      var seg = likeSelector(segment(chip));
      if (seg.charAt(0) === "#") seg = chip.tagName.toLowerCase();
      return exactPath(chip.parentElement) + " > " + seg;
    }
    return exactPath(el) + " a";
  }

  // r103 fix: the rows [likes] fall in: [{ exact, order, values }] (up to 30 rows, 300 look-alikes, 5 texts each)
  function rowsOfLikes(likes) {
    var rows = [];
    var byExact = {};
    for (var i = 0; i < likes.length && i < 300 && rows.length <= 30; i++) {
      var ex = "";
      try { ex = rowExactOf(likes[i]); } catch (e) { ex = ""; }
      if (!ex) continue;
      var row = byExact[ex];
      if (!row) {
        if (rows.length >= 30) break;
        row = { exact: ex, order: rowBoxIndex(likes[i]), values: [] };
        byExact[ex] = row;
        rows.push(row);
      }
      var t = snippetOf(likes[i]);
      if (t && row.values.length < 5) row.values.push(t);
    }
    return rows;
  }

  // The place of the tag list a chip sits in (its box), else of the element: chips of one row share it
  function rowBoxIndex(el) {
    var chip = el.closest ? (el.closest("a[href]") || el) : el;
    var chipLike = chip.tagName.toLowerCase() === "a" || CHIP_CLASS.test(String(chip.getAttribute("class") || ""));
    return docIndex(chipLike && chip.parentElement ? chip.parentElement : el);
  }

  // In the site's own menus (nav, header, footer, aside, a menu / sidebar / drawer box): not a title's details.
  // r103 fix: the app only warns now (a title's tags in a WordPress `footer.entry-footer` were refused). A tag link
  // (rel=tag, .tags-links / .cat-links, schema.org [itemprop]) is never the menu; inside the title's own box
  // (article, main, [itemprop], .entry, .post) its header / footer / aside are the title's, and only a <nav>, a
  // role=navigation or a sidebar / navbar / drawer box counts ("nav nav-pills" tag lists are Bootstrap's look)
  var MENU_WORD = /^(nav|navbar|menu|menus|sidebar|side-bar|drawer|navigation|offcanvas)$/i;
  var MENU_STRONG = /^(navbar|sidebar|side-bar|drawer|offcanvas)$/i;
  var MENU_PART = /^(menu|menus|sidebar|navbar|drawer|navigation|offcanvas)$/i;
  var TAG_LINKS = "[rel~=tag], .tags-links, .tag-links, .cat-links, [itemprop]";
  var TITLE_BOX = "article, main, [role=main], [itemprop], .entry, .post";
  function menuClass(value, inTitle) {
    var words = String(value || "").split(/\s+/);
    for (var i = 0; i < words.length; i++) {
      var w = words[i];
      if (!w) continue;
      if (inTitle) {
        if (MENU_STRONG.test(w)) return true;
        continue;
      }
      if (MENU_WORD.test(w)) return true;
      // main-menu, sub-menu, menu-item, site-navigation, sidebar-left, site-nav (never nav-pills / nav-link)
      var parts = w.split(/[_-]+/);
      if (parts.length > 1) {
        for (var p = 0; p < parts.length; p++) if (MENU_PART.test(parts[p])) return true;
        if (/^nav$/i.test(parts[parts.length - 1])) return true;
      }
    }
    return false;
  }
  function inSiteMenu(el) {
    if (!el || !el.closest) return false;
    try { if (el.closest(TAG_LINKS)) return false; } catch (eTag) { /* an old WebView: no closest() with these */ }
    var titleBox = null;
    try { titleBox = el.closest(TITLE_BOX); } catch (eBox) { titleBox = null; }
    for (var n = el; n && n.nodeType === 1 && !/^(body|html)$/i.test(n.tagName); n = n.parentElement) {
      var inTitle = !!titleBox && n !== titleBox && titleBox.contains(n);
      if (titleBox && n === titleBox) break; // the title's own box: what is around it is the page's layout
      if (/^nav$/i.test(n.tagName) || n.getAttribute("role") === "navigation") return true;
      if (!inTitle && /^(header|footer|aside)$/i.test(n.tagName)) return true;
      if (menuClass(n.getAttribute("class"), inTitle) || menuClass(n.id, inTitle)) return true;
    }
    return false;
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

  // [forLabel]: r102, the label the pick is for (armed), sent as `armed`
  function pickElement(el, via, note, forLabel) {
    current = el;
    var count = mark(el);
    showBadge("Picked " + shortName(pathOf(el)) + " ×" + count + (note ? " (" + note + ")" : "") +
      (forLabel ? " as " + forLabel : " — now tap a label in the app"));
    try {
      var d = describe(el, via);
      // r99 fix: a pick that only a skipped element gave says so in its text (the hint shows the snippet)
      if (note) d.snippet = ("[" + note + "] " + d.snippet).trim().slice(0, 100);
      if (forLabel) d.armed = forLabel;
      // r104: a Tags tap in a details block: every row of the block, one group each (a long press too: the app may
      // name it Tags or Suggestions after)
      if (forLabel === "tags" || via === "hold") {
        var rows = [];
        try { rows = detailRows(el); } catch (eRows) { send("log", "rows not read: " + eRows.message); rows = []; }
        if (rows.length) {
          d.rows = rows;
          send("log", "tags: " + rows.length + " rows (" + rows.map(function (r) { return r.label; }).join(", ").slice(0, 120) + ")");
        }
      }
      // r106: a Suggestions tap on a section's Load more button: that button, not a section
      var moreBtn = forLabel === "suggestions" ? loadMoreButton(el) : null;
      if (moreBtn) {
        d.loadMore = moreInfo(moreBtn);
        d.loadMore.section = headingsBefore(moreBtn).join("\n");
        send("log", "suggestions: load more button \"" + d.loadMore.text + "\" " + d.loadMore.button);
      } else if (forLabel === "suggestions" || via === "hold") {
        var sg = null;
        try { sg = suggestionOf(el); } catch (eSg) { send("log", "suggestions not read: " + eSg.message); sg = null; }
        d.suggest = sg;
        if (forLabel === "suggestions" || sg) send("log", sg ? "suggestions \"" + sg.name + "\": " + sg.count + " cards · " + sg.item : "suggestions: no section with a heading and repeated cards around the tap");
      }
      send("pick", JSON.stringify(d));
    } catch (e) {
      send("log", "pick failed: " + e.message);
    }
  }

  // ---------- r102: ad-network frames are never the player ----------

  var AD_HOSTS = /pnonolet|pemsrv|juicyads|exoclick|exosrv|trafficjunky|trafficstars|tsyndicate|adsterra|popads|propellerads|magsrv|realsrv|clickadu|hilltopads|adspyglass|ero-advertising|onclickads|a-ads\.com|adnxs|doubleclick|googlesyndication/i;
  function adHost(el) {
    if (!el || !el.getAttribute || !/^(iframe|embed|object)$/i.test(el.tagName)) return "";
    var src = el.getAttribute("src") || el.getAttribute("data-src") || el.getAttribute("data") || "";
    if (!AD_HOSTS.test(src)) return "";
    try { return new URL(src, location.href).host; } catch (e) { return src.slice(0, 60); }
  }
  function playerOk(el) { return !!el && /^(iframe|video|embed|object)$/i.test(el.tagName) && !adHost(el); }

  // A Video tap that landed on an ad frame: the real player under the finger, else the largest one on the page
  function realPlayerAt(x, y, adEl) {
    var stack = [];
    try { stack = document.elementsFromPoint(x, y) || []; } catch (e) { stack = []; }
    for (var i = 0; i < stack.length; i++) if (stack[i] !== adEl && playerOk(stack[i])) return stack[i];
    for (var j = 0; j < stack.length; j++) {
      if (stack[j] === adEl || !stack[j].querySelectorAll || /^(html|body)$/i.test(stack[j].tagName)) continue;
      var inside = largest(Array.prototype.slice.call(stack[j].querySelectorAll("iframe, video")).filter(playerOk));
      if (inside) return inside;
    }
    return largest(queryAll("iframe, video").filter(playerOk));
  }

  function pickAt(x, y, target, via) {
    var found = resolveAt(x, y, target);
    var el = found.el;
    if (!el || !el.tagName) return;
    if (found.skipped.length) send("log", "tap skipped " + found.skipped.join(", "));
    var note = "";
    if (found.fallback) {
      note = "everything here was skipped; using " + found.fallback;
      send("log", "tap: " + note);
    }
    // r109: "Tap the picture on the page": this tap names one card's picture
    if (pictureOn) {
      try { picturePick(el); } catch (ePic) { send("log", "picture pick failed: " + ePic.message); }
      return;
    }
    // r103: "Pick a menu on the site": this tap picks a menu group (a heading and its list)
    if (menuOn) {
      menuOn = false;
      applyModeClass(mode);
      try { menuPick(el); } catch (eMenu) { send("log", "menu pick failed: " + eMenu.message); }
      return;
    }
    // r101: the app asked for a whole list (groupMode): this tap picks the list, not one element
    if (groupOn) {
      groupOn = false;
      applyModeClass(mode);
      groupPick(el);
      return;
    }
    // A tap on text inside a link or an image's wrapper means the link or the image
    if (el.tagName.toLowerCase() !== "a" && !isMedia(el)) {
      var inner = el.querySelector("img, iframe, video");
      var link = el.closest("a");
      if (inner && el.children.length === 1) el = inner;
      else if (link && String(el.textContent || "").trim().length > 0 && String(el.textContent || "").trim() === String(link.textContent || "").trim()) el = link;
    }
    var forLabel = via === "hold" ? null : armed;
    // r103: Description armed and the tap landed on a chip / link / short text: the description block near it
    if (forLabel === "description" && notDescription(el)) {
      var better = null;
      try { better = descriptionNear(el); } catch (eDesc) { better = null; }
      if (better && better !== el) {
        send("log", "tap: " + shortName(pathOf(el)) + " is not a description; using " + shortName(pathOf(better)));
        el = better;
      }
    }
    // r102: an ad network's frame over the player is not the Video
    if (forLabel === "video" && adHost(el)) {
      var real = realPlayerAt(x, y, el);
      send("log", "tap: " + adHost(el) + " is an ad frame; " + (real ? "using " + shortName(pathOf(real)) : "no other player found"));
      if (real) el = real;
    }
    pickElement(el, via || "tap", note, forLabel);
    // r102: an armed label takes one tap, then the page is a browser again (Tags stays armed)
    if (forLabel && !sticky) {
      setArmed(null);
      swallowUntil = Date.now() + 700; // a late click of the same tap must not open the link now

      send("log", "picked for " + forLabel + "; nothing armed now");
    }
  }

  // r102: arms [label] (taps pick, nothing navigates) or nothing (null: the page is a normal browser + long press)
  function setArmed(label) {
    armed = label ? String(label) : null;
    sticky = stickyLabel(armed);
    mode = armed ? "pick" : "browse";
    if (window.__labPicker) {
      window.__labPicker.mode = mode;
      window.__labPicker.armed = armed;
    }
    applyModeClass(groupOn || menuOn || pictureOn ? "pick" : mode);
    cancelHold();
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

  // r102: never an ad network's frame (playerOk)
  function findVideo() {
    var node = current;
    if (node && /^(iframe|video)$/i.test(node.tagName) && playerOk(node)) return node;
    for (var level = 0; node && level < 4 && !/^(html|body)$/i.test(node.tagName); level++, node = node.parentElement) {
      var inside = largest(Array.prototype.slice.call(node.querySelectorAll("iframe, video")).filter(playerOk));
      if (inside) return inside;
    }
    return largest(queryAll("iframe, video").filter(playerOk));
  }

  // r101: title-page fields. Outside the site's menus and the page's edges
  function inChrome(el) {
    try { return !!el.closest("nav, header, footer, aside, [class*=menu], [class*=nav]"); } catch (e) { return false; }
  }

  // Cover (title page): the largest picture shown, at least 100 × 100, not a menu / header one
  function findBigCover() {
    var best = null;
    var area = 0;
    var imgs = queryAll("img");
    for (var i = 0; i < imgs.length; i++) {
      var r = imgs[i].getBoundingClientRect();
      if (r.width < 100 || r.height < 100 || inChrome(imgs[i])) continue;
      if (r.width * r.height > area) { area = r.width * r.height; best = imgs[i]; }
    }
    return best;
  }

  // Description: the block with the longest text of its own (≥ 80 characters), few links, not in the menus
  function ownText(el) {
    var t = "";
    for (var i = 0; i < el.childNodes.length; i++) {
      var n = el.childNodes[i];
      if (n.nodeType === 3) t += n.nodeValue;
      else if (n.nodeType === 1 && /^(b|i|em|strong|br|span|small)$/i.test(n.tagName)) t += n.textContent;
    }
    return t.replace(/\s+/g, " ").trim();
  }
  function findDescription() {
    var best = null;
    var longest = 79;
    var blocks = queryAll("p, div, section, article, span, dd");
    for (var i = 0; i < blocks.length && i < 5000; i++) {
      var b = blocks[i];
      if (!shown(b) || inChrome(b)) continue;
      var len = ownText(b).length;
      if (len > longest && b.querySelectorAll("a[href]").length <= 3) { longest = len; best = b; }
    }
    return best;
  }

  // r103: a chip or a link is not a description. r103 fix: a short plain text stays as picked (a short synopsis is
  // a description too; the helper's PickCheck warns about its length), and only a chip-like class counts
  // (chip / tag / badge / pill, not CHIP_CLASS's genre / cast / model…: a "summary" box can say "genre")
  var DESC_CHIP = /(^|[\s_-])(chips?|tags?|badges?|pills?)([\s_-]|$)/i;
  function notDescription(el) {
    if (!el || !el.closest) return false;
    if (el.closest("a[href]")) return true;
    return DESC_CHIP.test(String(el.getAttribute("class") || ""));
  }

  // r103: the description block nearest the tap: the longest own text (≥ 80 characters, few links, not in the menus)
  // in the tap's boxes, 6 levels up; else the page's
  function descriptionNear(el) {
    var node = el.parentElement;
    for (var level = 0; node && level < 6 && !/^(body|html)$/i.test(node.tagName); level++, node = node.parentElement) {
      var best = null;
      var longest = 79;
      var blocks = node.querySelectorAll("p, div, section, article, span, dd");
      for (var i = 0; i < blocks.length && i < 2000; i++) {
        var b = blocks[i];
        if (!shown(b) || inChrome(b)) continue;
        var len = ownText(b).length;
        if (len > longest && b.querySelectorAll("a[href]").length <= 3) { longest = len; best = b; }
      }
      if (best) return best;
    }
    return findDescription();
  }

  // Tags: a group of ≥ 2 links with short texts (≤ 30 characters) that open genre / tag / category pages
  var TAG_HREF = /genre|tag|categor|keyword/i;
  function findTags() {
    var groups = {};
    var links = queryAll("a[href]");
    for (var i = 0; i < links.length && i < 3000; i++) {
      var a = links[i];
      var t = String(a.textContent || "").replace(/\s+/g, " ").trim();
      if (!t || t.length > 30 || !shown(a) || inChrome(a) || !TAG_HREF.test(a.getAttribute("href") || "")) continue;
      var sel = likeSelector(pathOf(a));
      (groups[sel] = groups[sel] || []).push(a);
    }
    var best = null;
    for (var s in groups) {
      if (!Object.prototype.hasOwnProperty.call(groups, s)) continue;
      if (groups[s].length >= 2 && (!best || groups[s].length > best.length)) best = groups[s];
    }
    return best ? best[0] : null;
  }

  var FINDERS = {
    item: findItem, title: findTitle, cover: findCover, next: findNext, chapter: findChapter, image: findImage, video: findVideo,
    bigCover: findBigCover, description: findDescription, tags: findTags,
  };

  // ---------- r101: the site's search box ----------

  var SEARCH_NAME = /^(q|s|search|keyword|keywords|query|k|term|word)$/i;

  // The search field of [form]: input[type=search], an input inside [role=search], or one named like a search word
  function searchFieldOf(form) {
    var inputs = form.querySelectorAll("input[name]");
    var byType = null;
    var byRole = null;
    var byName = null;
    var roleBox = form.matches && form.matches("[role=search]") ? form : null;
    for (var i = 0; i < inputs.length; i++) {
      var input = inputs[i];
      var type = String(input.getAttribute("type") || "text").toLowerCase();
      if (!/^(search|text|)$/.test(type)) continue;
      if (!byType && type === "search") byType = input;
      if (!byRole && (roleBox || input.closest("[role=search]"))) byRole = input;
      if (!byName && SEARCH_NAME.test(input.getAttribute("name") || "")) byName = input;
    }
    return byType || byRole || byName;
  }

  var lastForm = "";
  function readForm() {
    var forms = queryAll("form");
    for (var i = 0; i < forms.length; i++) {
      var form = forms[i];
      var method = String(form.getAttribute("method") || "get").toLowerCase();
      if (method !== "get") continue;
      var field = searchFieldOf(form);
      if (!field) continue;
      var action = "";
      try { action = new URL(form.getAttribute("action") || location.href, location.href).href; } catch (e) { action = location.href; }
      if (!form.getAttribute("action")) action = action.split("#")[0].split("?")[0];
      var hidden = {};
      var hiddenInputs = form.querySelectorAll("input[type=hidden][name]");
      for (var h = 0; h < hiddenInputs.length; h++) {
        var hn = hiddenInputs[h].getAttribute("name");
        if (hn && hn !== field.getAttribute("name")) hidden[hn] = String(hiddenInputs[h].value || "");
      }
      var found = { action: action, method: method, field: field.getAttribute("name"), hidden: hidden, pageUrl: location.href };
      var text = JSON.stringify(found);
      // Once per page and form: the app is told again only when something changed
      if (text !== lastForm) {
        lastForm = text;
        send("form", text);
        send("log", "search form: " + action + " ?" + found.field + "=");
      }
      return found;
    }
    return null;
  }

  // ---------- r101: group pick (a whole list: a genre menu, a sort menu, a <select>) ----------

  var groupOn = false;

  function cleanText(el) {
    var t = String(el.textContent || "").replace(/\s+/g, " ").trim();
    if (!t) t = el.getAttribute("title") || el.getAttribute("aria-label") || el.getAttribute("alt") || "";
    if (!t) {
      var img = el.querySelector && el.querySelector("img[alt]");
      if (img) t = img.getAttribute("alt") || "";
    }
    return String(t).replace(/\s+/g, " ").trim().slice(0, 120);
  }

  // The entry the tap means: the <select> itself, the link, the button / option / menu item, a lone link inside
  function groupEntry(el) {
    var tag = el.tagName.toLowerCase();
    if (tag === "select") return el;
    var opt = el.closest("option, optgroup");
    if (opt) return opt.closest("select") || opt;
    var link = el.closest("a[href]");
    if (link) return link;
    var control = el.closest("button, [role=option], [role=menuitem], [role=tab], [role=radio], [data-value], label");
    if (control) return control;
    var inner = el.querySelectorAll ? el.querySelectorAll("a[href]") : [];
    if (inner.length === 1) return inner[0];
    return el;
  }

  function itemOf(el) {
    var tag = el.tagName.toLowerCase();
    var out = { text: cleanText(el) };
    if (tag === "a") {
      var raw = el.getAttribute("href") || "";
      if (raw && !/^(javascript:|#)/i.test(raw)) {
        try { out.href = new URL(raw, location.href).href; } catch (e) { out.href = el.href || ""; }
      }
    }
    var value = el.getAttribute("data-value");
    if (value === null && (tag === "option" || tag === "button" || tag === "input" || tag === "li")) value = el.getAttribute("value");
    if (value === null && tag === "option") value = el.value;
    if (value !== null && value !== undefined) out.value = String(value);
    // r109: the site's current choice (it names the menu's default)
    try { if (isCurrentEntry(el)) out.current = true; } catch (eCur) { /* nothing */ }
    return out;
  }

  // r109: an entry the site marks as chosen: an active / selected / current class (on it or its li), aria-selected,
  // aria-checked, aria-current, a selected option
  var CURRENT_CLASS = /(^|\s)(is-|o-)?(active|selected|current|checked|chosen)(\s|$)/i;
  function isCurrentEntry(el) {
    var tag = el.tagName.toLowerCase();
    if (tag === "option") return !!el.selected;
    var boxes = [el];
    var li = el.parentElement && el.parentElement.tagName.toLowerCase() === "li" ? el.parentElement : null;
    if (li) boxes.push(li);
    for (var i = 0; i < boxes.length; i++) {
      var b = boxes[i];
      if (CURRENT_CLASS.test(String(b.getAttribute("class") || "").replace(/__lab-\S+/g, ""))) return true;
      if (b.getAttribute("aria-selected") === "true" || b.getAttribute("aria-checked") === "true") return true;
      var cur = b.getAttribute("aria-current");
      if (cur !== null && cur !== "false") return true;
    }
    return false;
  }

  // The list's name: an aria-label on the list, a heading before it, or the label of the submenu toggle above it
  function headingOf(el) {
    var node = el.parentElement;
    for (var level = 0; node && level < 6 && !/^(body|html)$/i.test(node.tagName); level++, node = node.parentElement) {
      var aria = node.getAttribute("aria-label");
      if (aria && aria.length <= 40) return aria.trim();
      var prev = node.previousElementSibling;
      for (var hop = 0; prev && hop < 2; hop++, prev = prev.previousElementSibling) {
        if (/^(h1|h2|h3|h4|h5|h6|label|button|summary|strong|span|a|div)$/i.test(prev.tagName)) {
          var t = String(prev.textContent || "").replace(/\s+/g, " ").trim();
          if (t && t.length <= 40 && prev.querySelectorAll("a[href]").length <= 1) return t;
        }
      }
    }
    return "";
  }

  // r102: what opens a dropdown: an ARIA popup / expanded toggle, a select|dropdown|toggle class, a <summary>
  var ARROW_END = /[▾▼⌄⏷▿∨˅]\s*$/;
  function openerLike(n) {
    if (!n || n.nodeType !== 1 || n.id === "__lab-badge") return false;
    // "selected" / "is-selected" are an entry's state, not a select box
    var cls = String(n.getAttribute("class") || "").replace(/(^|\s)(is-)?selected(?=\s|$)/g, " ");
    try { if (n.matches("[aria-haspopup], [aria-expanded], summary")) return true; } catch (e) { /* nothing */ }
    if (/dropdown|toggle|select/i.test(cls)) return true;
    var tag = n.tagName.toLowerCase();
    return (tag === "button" || n.getAttribute("role") === "button") && (ARROW_END.test(textOf(n)) || textOf(n).length <= 30);
  }

  // A clickable opener inside [n] that is not around [entry] (n itself when it holds no entry)
  function openerWithin(n, entry) {
    if (n.contains(entry)) {
      for (var i = 0; i < n.children.length; i++) {
        var c = n.children[i];
        if (!c.contains(entry) && (openerLike(c) || /^(a|button|span|div)$/i.test(c.tagName)) && textOf(c) && textOf(c).length <= 40) return c;
      }
      return null;
    }
    return n;
  }

  // The element that opens the list holding [entry]: aria-controls pointing at the list (or one of its boxes), else
  // the nearest earlier sibling of the list box (or of a box around it, 5 levels) that looks like an opener, else an
  // opener-looking box around the list (its first child that isn't the list)
  function openerOf(entry, listBox) {
    var start = listBox || entry.parentElement;
    var node = start;
    for (var level = 0; node && level < 5 && !/^(body|html)$/i.test(node.tagName); level++, node = node.parentElement) {
      if (node.id) {
        var q = null;
        try { q = document.querySelector("[aria-controls~=\"" + (window.CSS && CSS.escape ? CSS.escape(node.id) : node.id) + "\"]"); } catch (e) { q = null; }
        if (q && !q.contains(entry)) return q;
      }
    }
    node = start;
    for (var up = 0; node && up < 5 && !/^(body|html)$/i.test(node.tagName); up++, node = node.parentElement) {
      var prev = node.previousElementSibling;
      for (var hop = 0; prev && hop < 3; hop++, prev = prev.previousElementSibling) {
        if (openerLike(prev) && !prev.contains(entry)) return prev;
        var inner = prev.querySelector ? prev.querySelector("[aria-haspopup], [aria-expanded]") : null;
        if (inner && !inner.contains(entry)) return inner;
      }
      if (node !== start && openerLike(node) && node.contains(entry)) {
        var own = openerWithin(node, entry);
        if (own) return own;
      }
    }
    return null;
  }

  // The tapped entry is a menu's own button ("Year: All ▾"): ARIA popup / expanded, an arrow at its end, or the next
  // element is a hidden box of two or more entries
  function isMenuButton(entry) {
    if (entry.matches && entry.matches("[aria-haspopup], [aria-expanded]")) return true;
    if (ARROW_END.test(textOf(entry))) return true;
    var next = entry.nextElementSibling;
    if (next && !shown(next) && next.querySelectorAll("a[href], button, li, [role=option], [role=menuitem]").length >= 2) return true;
    return false;
  }

  function groupPick(el) {
    clearMarks();
    var entry = groupEntry(el);
    var isSelect = entry.tagName.toLowerCase() === "select";
    var path = pathOf(entry);
    var like = likeSelector(path);
    var items = [];
    var members = [];
    var seen = {};
    function addItem(member) {
      if (items.length >= 400) return;
      var it = itemOf(member);
      var key = (it.href || "") + "|" + (it.value === undefined ? "" : it.value) + "|" + it.text;
      if (seen[key] || (!it.text && !it.href && it.value === undefined)) return;
      seen[key] = true;
      // r102: the entry's exact place, so learn.js can press this one (W2)
      try { if (!isSelect) it.path = exactPath(member); } catch (ePath) { /* nothing */ }
      items.push(it);
      members.push(member);
    }
    if (isSelect) {
      for (var o = 0; o < entry.options.length; o++) addItem(entry.options[o]);
    } else {
      var likes = queryAll(like); // hidden ones too: a collapsed submenu's entries are in the document
      if (likes.indexOf(entry) < 0) likes.unshift(entry);
      for (var i = 0; i < likes.length; i++) addItem(likes[i]);
    }
    // The tapped entry's own list (the nearest element holding two or more of them), for a menu whose submenus
    // share one look
    var sameList = [];
    var listBox = null;
    if (!isSelect) {
      var box = entry.parentElement;
      for (var level = 0; box && level < 4 && !/^(body|html)$/i.test(box.tagName); level++, box = box.parentElement) {
        var inBox = members.filter(function (m) { return box.contains(m); });
        if (inBox.length >= 2) {
          for (var s = 0; s < inBox.length; s++) sameList.push(itemOf(inBox[s]));
          listBox = box;
          break;
        }
      }
    }
    // r102: the dropdown's button (learn.js clicks it before pressing an entry), and whether the tap was on one
    var openerEl = null;
    try { openerEl = isSelect ? null : openerOf(entry, listBox); } catch (eOp) { openerEl = null; }
    var tappedOpener = false;
    try { tappedOpener = !isSelect && isMenuButton(entry); } catch (eTo) { tappedOpener = false; }
    for (var p = 0; p < members.length; p++) paintSuggestion(members[p]);
    var tapped = itemOf(entry);
    var out = {
      pageUrl: location.href,
      tapped: { text: tapped.text, href: tapped.href || "" },
      likeSelector: isSelect ? like + " option" : like,
      count: items.length,
      items: items,
      isSelect: isSelect,
      heading: headingOf(entry),
      sameList: sameList,
      // r102: the css path of the button that opens this list ("" when none was found), and the tap was on a
      // menu's button itself (its look-alikes are the other menus' buttons, not the menu's entries)
      opener: openerEl ? exactPath(openerEl) : "",
      openerText: openerEl ? snippetOf(openerEl) : "",
      tappedOpener: tappedOpener,
    };
    if (isSelect) out.name = entry.getAttribute("name") || "";
    if (openerEl) send("log", "group opener " + exactPath(openerEl) + " · \"" + snippetOf(openerEl) + "\"");
    if (tappedOpener) send("log", "group pick: the tapped entry is a menu's button");
    showBadge("List picked: " + items.length + " entries (dashed green) — see the app");
    send("group", JSON.stringify(out));
    send("log", "group pick " + shortName(path) + " ×" + items.length + (isSelect ? " (select)" : "") + " · \"" + tapped.text.slice(0, 40) + "\"");
  }

  // ---------- r103: "Pick a menu on the site" (one filter per menu group) ----------

  var menuOn = false;
  var MENU_ENTRY = "a[href], button, [role=menuitem], [role=option], [role=tab], [role=radio], [data-value], summary, option";
  // Entries that name list pages (an index of all genres, all actresses…), not one list's options
  var INDEX_WORDS = /^(all\s+)?(genres?|tags?|categor(y|ies)|actress(es)?|actors?|makers?|studios?|series|labels?|directors?|models?|channels?|collections?|publishers?|authors?|artists?|groups?|parod(y|ies)|characters?|languages?|countries|stars?|pornstars?|performers?|casts?|keywords?|brands?|networks?|years?)$/i;

  function isEntry(el) {
    try { return !!el && el.nodeType === 1 && el.id !== "__lab-badge" && el.matches(MENU_ENTRY); } catch (e) { return false; }
  }

  // The entries inside [box] (hidden ones too), innermost only (an <a> inside an <li data-value> is the <a>)
  function entriesIn(box) {
    if (!box || !box.querySelectorAll) return [];
    var all = Array.prototype.slice.call(box.querySelectorAll(MENU_ENTRY), 0, 1000);
    return all.filter(function (e) {
      if (e.id === "__lab-badge") return false;
      for (var i = 0; i < all.length; i++) if (all[i] !== e && e.contains(all[i])) return false;
      return true;
    });
  }

  function entryCount(el) { return entriesIn(el).length + (isEntry(el) ? 1 : 0); }

  // The list [h] opens: aria-controls, else one of its next two siblings holding two or more entries
  function controlledList(h) {
    if (!h || h.nodeType !== 1) return null;
    var ids = String(h.getAttribute("aria-controls") || "").split(/\s+/);
    for (var i = 0; i < ids.length; i++) {
      if (!ids[i]) continue;
      var t = document.getElementById(ids[i]);
      if (t && !t.contains(h) && entriesIn(t).length >= 2) return t;
    }
    var n = h.nextElementSibling;
    // r103 fix: up to two short text-only lines between the heading and its list are skipped (a WordPress
    // widget's "Browse 120 categories" under its "Categories" heading)
    var notes = 0;
    for (var hop = 0; n && hop < 4; hop++, n = n.nextElementSibling) {
      if (n.id === "__lab-badge" || n.contains(h)) continue;
      // Two items of one list (li by li, or boxes of the same look) are not a heading and its list
      var twins = (/^(li|dt|dd|tr)$/i.test(h.tagName) && h.tagName === n.tagName) ||
        (h.tagName === n.tagName && String(h.getAttribute("class") || "") === String(n.getAttribute("class") || ""));
      var inside = entriesIn(n).length;
      if (!twins && inside >= 2) return n;
      if (twins || isEntry(n) || inside > 0) break; // another heading's list or an entry first: not this one's list
      var t = textOf(n);
      if (!t) { if (hop >= 1 + notes) break; continue; } // an empty box (an icon, a spacer)
      if (notes < 2 && t.length <= 80 && !/^h[1-6]$/i.test(n.tagName)) { notes++; continue; }
      break; // another heading first
    }
    return null;
  }

  // The tapped heading / toggle (up to 4 levels, never a box that already holds two entries) and its list
  function headingAt(el) {
    var node = el;
    for (var up = 0; node && up < 4 && !/^(body|html)$/i.test(node.tagName); up++, node = node.parentElement) {
      if (entryCount(node) >= 2) break;
      var list = controlledList(node);
      if (list && textOf(node).length <= 60) return { heading: node, list: list };
    }
    return null;
  }

  function headingTextOf(el) {
    if (!el) return "";
    var t = textOf(el) || el.getAttribute("aria-label") || el.getAttribute("title") || "";
    // a toggle's arrow is no part of the name ("Trending ▸")
    return String(t).replace(/\s+/g, " ").trim().replace(/[\s▾▴▼▲⌄⌃∨∧˅˄›»⏷⏶▸▹►▶]+$/, "").slice(0, 80);
  }

  function linkHref(el) {
    var a = el && el.tagName && el.tagName.toLowerCase() === "a" ? el : null;
    if (!a) return "";
    var raw = a.getAttribute("href") || "";
    if (!raw || /^(javascript:|#)/i.test(raw)) return "";
    try { return new URL(raw, location.href).href; } catch (e) { return ""; }
  }

  // One group: [headingEl]'s text (or [headingName]), its own entries, and a subgroup per sub-heading inside [list]
  // (an entry that opens its own list, or, when most entries name list pages, each of those links)
  function buildGroup(headingEl, headingName, list, path, depth, members) {
    var heading = headingEl ? headingTextOf(headingEl) : String(headingName || "");
    var here = path.concat(heading ? [heading] : []);
    var entries = list && list.tagName && list.tagName.toLowerCase() === "select" ?
      Array.prototype.slice.call(list.options) : entriesIn(list);
    var subs = [];
    if (depth < 3 && list.querySelectorAll && list.tagName.toLowerCase() !== "select") {
      // Sub-headings: an entry or a short heading-like element inside the list that opens a list of its own
      var cands = Array.prototype.slice.call(list.querySelectorAll("*"), 0, 1500);
      for (var i = 0; i < cands.length; i++) {
        var c = cands[i];
        if (c.id === "__lab-badge" || /^(svg|path|i|img|ul|ol)$/i.test(c.tagName)) continue;
        var inner = false;
        for (var k = 0; k < subs.length && !inner; k++) if (subs[k].list.contains(c) || subs[k].el.contains(c)) inner = true;
        if (inner || entryCount(c) > 1) continue;
        var t = textOf(c);
        if (!t || t.length > 40) continue;
        var sl = controlledList(c);
        if (sl && list.contains(sl) && !sl.contains(c)) subs.push({ el: c, list: sl });
      }
    }
    var own = entries.filter(function (e) {
      for (var k = 0; k < subs.length; k++) if (subs[k].el === e || subs[k].list.contains(e)) return false;
      return true;
    });
    var subgroups = subs.map(function (s) { return buildGroup(s.el, "", s.list, here, depth + 1, members); });
    // Index links: most entries name list pages ("Genres", "Actresses", "Makers", "Series"): each is a subgroup
    if (!subs.length && own.length >= 2) {
      var links = own.filter(function (e) { return !!linkHref(e); });
      var named = links.filter(function (e) { return INDEX_WORDS.test(headingTextOf(e)); });
      if (links.length === own.length && named.length * 2 >= own.length) {
        own.forEach(function (e) {
          members.push(e);
          subgroups.push({
            heading: headingTextOf(e), headingPath: here.concat([headingTextOf(e)]), opener: "", openerText: "",
            href: linkHref(e), index: true, items: [], subgroups: [],
          });
        });
        own = [];
      }
    }
    var items = [];
    var seen = {};
    for (var j = 0; j < own.length && items.length < 400; j++) {
      var it = itemOf(own[j]);
      var key = (it.href || "") + "|" + (it.value === undefined ? "" : it.value) + "|" + it.text;
      if (seen[key] || (!it.text && !it.href && it.value === undefined)) continue;
      seen[key] = true;
      try { if (own[j].tagName.toLowerCase() !== "option") it.path = exactPath(own[j]); } catch (eP) { /* nothing */ }
      items.push(it);
      members.push(own[j]);
    }
    var href = headingEl ? linkHref(headingEl) : "";
    return {
      heading: heading,
      headingPath: here,
      opener: headingEl ? exactPath(headingEl) : "",
      openerText: headingEl ? snippetOf(headingEl) : "",
      href: href,
      index: !!href && INDEX_WORDS.test(heading),
      items: items,
      subgroups: subgroups,
    };
  }

  function countGroups(g) {
    var n = g.items.length >= 2 || g.href ? 1 : 0;
    for (var i = 0; i < g.subgroups.length; i++) n += countGroups(g.subgroups[i]);
    return n;
  }

  // r103 fix: a dropdown whose menu the site adds only when it opens (a portal: aria-controls naming a missing id,
  // aria-haspopup / aria-expanded / .dropdown-toggle on the tapped element or up to 3 parents, no list found)
  var LAZY_MENU_HINT = "This menu isn't on the page until it opens: tap Stop, open it on the site, then Pick a menu and tap one entry";
  function lazyToggle(el) {
    var node = el;
    for (var up = 0; node && node.nodeType === 1 && up < 4 && !/^(body|html)$/i.test(node.tagName); up++, node = node.parentElement) {
      if (entryCount(node) >= 2) return null; // a box holding a list: not one button
      var ids = String(node.getAttribute("aria-controls") || "").split(/\s+/).filter(function (x) { return !!x; });
      var present = ids.filter(function (id) { return !!document.getElementById(id); });
      if (ids.length && !present.length) return node; // it opens something that isn't in the page yet
      if (present.length) continue; // it opens something that is here (an accordion's panel): not a lazy menu
      var popup = String(node.getAttribute("aria-haspopup") || "").toLowerCase();
      if ((popup && popup !== "false") || node.hasAttribute("aria-expanded") ||
        /(^|\s)dropdown-toggle(\s|$)/i.test(String(node.getAttribute("class") || ""))) return node;
    }
    return null;
  }

  function menuPick(el) {
    clearMarks();
    var members = [];
    var group = null;
    var how = "";
    var select = el.closest ? el.closest("select") : null;
    var found = select ? null : headingAt(el);
    if (select) {
      var name = select.getAttribute("aria-label") || select.getAttribute("name") || headingOf(select);
      group = buildGroup(null, name, select, [], 0, members);
      group.param = select.getAttribute("name") || "";
      how = "a dropdown";
    } else if (found) {
      group = buildGroup(found.heading, "", found.list, [], 0, members);
      how = "the heading's list";
    } else {
      var lazy = null;
      try { lazy = lazyToggle(el); } catch (eLazy) { lazy = null; }
      if (lazy) {
        send("log", "menu pick: \"" + headingTextOf(lazy).slice(0, 40) + "\" opens a menu that isn't in the page yet");
        send("menu", JSON.stringify({
          pageUrl: location.href,
          tapped: { text: headingTextOf(lazy), href: "" },
          groups: [],
          hint: LAZY_MENU_HINT,
        }));
        showBadge("This menu isn't on the page until it opens \u2014 see the app");
        return;
      }
      // An entry of a list: the nearest box holding two or more entries, named by the heading before it
      var inEntry = el.closest ? el.closest(MENU_ENTRY) : null;
      var entry = inEntry || el;
      var box = entry.parentElement;
      for (var level = 0; box && level < 5 && !/^(body|html)$/i.test(box.tagName); level++, box = box.parentElement) {
        if (entriesIn(box).length >= 2) break;
      }
      if (!box || /^(body|html)$/i.test(box.tagName)) {
        send("log", "menu pick: no list around \"" + headingTextOf(el).slice(0, 40) + "\"");
        send("menu", JSON.stringify({ pageUrl: location.href, tapped: { text: headingTextOf(el), href: linkHref(entry) }, groups: [] }));
        showBadge("No menu here: tap a menu's heading, a dropdown's button or one of its entries");
        return;
      }
      var opener = null;
      try { opener = openerOf(entry, box); } catch (eO) { opener = null; }
      // r103 fix: a tapped heading (not an entry) names its group
      var ownName = inEntry ? "" : headingTextOf(el);
      if (ownName.length > 60) ownName = "";
      group = buildGroup(null, ownName || headingOf(entry) || (opener ? headingTextOf(opener) : ""), box, [], 0, members);
      if (opener) {
        group.opener = exactPath(opener);
        group.openerText = snippetOf(opener);
      }
      how = "the list around the tapped entry";
    }
    // r103 fix: nothing is outlined for a menu with subgroups (the app outlines one group after Make filter /
    // Learn addresses); a single group's own entries are
    var painted = 0;
    if (!group.subgroups.length) {
      for (var p = 0; p < group.items.length; p++) {
        if (!group.items[p].path) continue;
        var own = queryAll(group.items[p].path);
        for (var q = 0; q < own.length; q++) { paintSuggestion(own[q]); painted++; }
      }
    }
    var out = { pageUrl: location.href, tapped: { text: headingTextOf(el), href: linkHref(el.closest ? (el.closest("a[href]") || el) : el) }, groups: [group] };
    showBadge("Menu picked: " + (group.heading || "a list") + (painted ? " (dashed green)" : "") + " \u2014 see the app");
    send("menu", JSON.stringify(out));
    send("log", "menu pick \"" + (group.heading || "").slice(0, 40) + "\" (" + how + "): " + group.items.length + " entries, " +
      group.subgroups.length + " subgroups, " + countGroups(group) + " groups");
  }

  // ---------- r109: "Tap the picture on the page" (a scanned directory card) ----------

  var pictureOn = null; // { item }: the next tap names a card's picture
  var PIC_STATE = /^(active|selected|current|first|last|odd|even|hover|show|open|loaded|lazyloaded|lazyload|lazy|loading|fade|in|is-.+|js-.+)$/i;
  function picSegment(el) {
    var classes = String(el.getAttribute("class") || "").split(/\s+/).filter(function (c) {
      return /^[A-Za-z_][\w-]*$/.test(c) && !PIC_STATE.test(c) && c.indexOf("__lab") !== 0 && !GENERATED.test(c);
    });
    return el.tagName.toLowerCase() + classes.sort().map(function (c) { return "." + c; }).join("");
  }
  function bgUrl(el) {
    try {
      var m = /url\(\s*["']?([^"')]+)["']?\s*\)/i.exec(String(window.getComputedStyle(el).backgroundImage || ""));
      return m && !/^data:/i.test(m[1]) ? m[1] : "";
    } catch (e) { return ""; }
  }
  // The picture at the tap: an img (itself or inside the tapped box), else an element with a background picture,
  // looking up to 4 boxes around the tap
  function pictureAt(el) {
    for (var n = el, up = 0; n && n.nodeType === 1 && up < 4 && !/^(body|html)$/i.test(n.tagName); n = n.parentElement, up++) {
      if (n.tagName.toLowerCase() === "img") return n;
      if (bgUrl(n)) return n;
      var img = n.querySelector ? n.querySelector("img") : null;
      if (img) return img;
      var bgs = n.querySelectorAll ? n.querySelectorAll("[style*=background], [data-bg], [data-background], [class*=avatar], [class*=thumb]") : [];
      for (var i = 0; i < bgs.length; i++) if (bgUrl(bgs[i])) return bgs[i];
    }
    return null;
  }
  function picturePick(el) {
    var req = pictureOn || {};
    pictureOn = null;
    applyModeClass(mode);
    var answer = { via: "picture", pageUrl: location.href, ok: false, selectors: [], attrs: [], url: "", reason: "" };
    var item = String(req.item || "");
    var card = null;
    try { card = item && el.closest ? el.closest(item) : null; } catch (eC) { card = null; }
    var pic = pictureAt(el);
    if (pic && card && !card.contains(pic)) pic = null;
    if (!pic && card) pic = card.querySelector("img") || pictureAt(card);
    if (pic && !card) { try { card = item ? pic.closest(item) : null; } catch (eC2) { card = null; } }
    if (!pic) {
      answer.reason = "there's no picture where you tapped";
    } else if (!card) {
      answer.reason = "the tap isn't on one of the list's cards (" + item + ")";
    } else {
      var url = pic.tagName.toLowerCase() === "img" ? String(pic.currentSrc || pic.src || "") : bgUrl(pic);
      var file = url.split(/[?#]/)[0].split("/").pop();
      for (var i = 0; i < pic.attributes.length; i++) {
        var a = pic.attributes[i];
        var v = String(a.value || "");
        if (!v) continue;
        var holds = a.name === "style" ? /url\(/i.test(v) : (!!file && v.indexOf(file) >= 0) || /\.(jpe?g|png|webp|gif|avif)(\?|#|\s|$)/i.test(v);
        if (holds && answer.attrs.indexOf(a.name) < 0) answer.attrs.push(a.name);
      }
      var steps = [];
      var tags = [];
      for (var n = pic; n && n !== card; n = n.parentElement) {
        steps.unshift(picSegment(n));
        tags.unshift(n.tagName.toLowerCase());
      }
      // KMK (r109) fix: also the tags-only path and the bare tag (classes that differ from card to card); the app keeps
      // whichever finds a picture on the most cards
      answer.selectors = pic === card ? [""] : [picSegment(pic), "> " + steps.join(" > "), "> " + tags.join(" > "), tags[tags.length - 1]]
        .filter(function (x, k, all) { return all.indexOf(x) === k; });
      answer.url = url;
      answer.ok = true;
      clearMarks();
      paint(pic, "3px solid #4caf6a", "-3px", null);
    }
    showBadge(answer.ok ? "Picture picked \u2014 see the app" : "No card's picture here \u2014 see the app");
    send("pick", JSON.stringify(answer));
    send("log", "picture pick: " + (answer.ok ? answer.selectors.join(" | ") + " · " + answer.attrs.join(",") : answer.reason));
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
  // r101: taps are the picker's in Pick mode, and in Browse mode too while a group pick waits for its tap
  // r106: true while scanMore presses a Load more button: that click goes to the site, not to the picker
  var bypass = false;
  function taking() { return !bypass && (mode === "pick" || groupOn || menuOn || !!pictureOn); }

  // touchstart on an interactive target is prevented (no focus, no menu, no click), so it is picked on touchend
  var touch = null;            // { x, y, target, interactive, moved }
  var touchPickedAt = 0;       // a click right after a touch pick is the same tap
  var MOVE_SLOP = 10;

  function onTouchStart(event) {
    if (!taking()) return;
    event.stopImmediatePropagation();
    var t = event.touches && event.touches[0];
    if (!t || event.touches.length > 1) { touch = null; return; }
    var target = targetOf(event);
    touch = { x: t.clientX, y: t.clientY, target: target, interactive: interactive(target), moved: false };
    if (touch.interactive) event.preventDefault();
  }
  function onTouchMove(event) {
    if (!taking()) return;
    event.stopImmediatePropagation(); // never preventDefault: scrolling stays the browser's
    var t = event.touches && event.touches[0];
    if (touch && t && (Math.abs(t.clientX - touch.x) > MOVE_SLOP || Math.abs(t.clientY - touch.y) > MOVE_SLOP)) touch.moved = true;
  }
  function onTouchEnd(event) {
    if (!taking()) return;
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
    if (!taking()) return;
    event.stopImmediatePropagation();
    if (interactive(targetOf(event))) event.preventDefault();
  }
  function onClick(event) {
    if (!taking()) return;
    // The default action (following the link, submitting, opening) and the site's own handlers are stopped even
    // for a tap on the background, so pick mode never navigates
    event.preventDefault();
    event.stopImmediatePropagation();
    if (Date.now() - touchPickedAt < 700) return;
    pickAt(event.clientX, event.clientY, targetOf(event));
  }

  // ---------- r102: nothing armed: a long press picks (the page is a browser otherwise) ----------

  var HOLD_MS = 500;
  var hold = null;             // { x, y, target, timer, fired }
  var swallowUntil = 0;        // the click (touchend, contextmenu) that ends a long-press pick is swallowed

  function cancelHold() {
    if (hold && hold.timer) clearTimeout(hold.timer);
    hold = null;
  }
  function onHoldDown(event) {
    if (taking()) return;
    if (event.isPrimary === false || (event.pointerType === "mouse" && event.button !== 0)) { cancelHold(); return; }
    cancelHold();
    swallowUntil = 0; // r102 fix: a new press never inherits an old swallow window
    var h = { x: event.clientX, y: event.clientY, target: targetOf(event), timer: 0, fired: false };
    h.timer = setTimeout(function () {
      if (hold !== h || taking()) return;
      h.fired = true; // swallowing() holds while the finger is down (hold.fired), then a short while for its click
      send("log", "long press: picked (nothing armed)");
      try { pickAt(h.x, h.y, h.target, "hold"); } catch (e) { send("log", "long press pick failed: " + e.message); }
    }, HOLD_MS);
    hold = h;
  }
  function onHoldMove(event) {
    if (!hold || hold.fired) return;
    if (Math.abs(event.clientX - hold.x) > MOVE_SLOP || Math.abs(event.clientY - hold.y) > MOVE_SLOP) cancelHold();
  }
  function onHoldUp(event) {
    if (!hold) return;
    if (hold.fired) {
      // The finger lifts after the pick: no click, no link
      event.preventDefault();
      event.stopImmediatePropagation();
      hold = null;
      swallowUntil = Date.now() + 700;
      return;
    }
    cancelHold();
  }
  function swallowing() { return (hold !== null && hold.fired) || Date.now() < swallowUntil; }

  var ACTIVE = { capture: true, passive: false };
  window.addEventListener("pointerdown", onHoldDown, true);
  window.addEventListener("pointermove", onHoldMove, true);
  window.addEventListener("pointerup", onHoldUp, ACTIVE);
  window.addEventListener("pointercancel", function () {
    if (!hold) return;
    if (hold.fired) {
      // r102 fix: the long press turned into a scroll: no pointerup will come, so end the swallow shortly
      hold = null;
      swallowUntil = Date.now() + 700;
      return;
    }
    cancelHold();
  }, true);
  window.addEventListener("touchend", function (ev) {
    // A touch's click is synthesised after touchend: cancelling it here keeps a long-pressed link from opening
    if (!taking() && swallowing()) { ev.preventDefault(); ev.stopImmediatePropagation(); }
  }, ACTIVE);
  window.addEventListener("contextmenu", function (ev) {
    if (taking() || hold || swallowing()) ev.preventDefault();
  }, ACTIVE);
  window.addEventListener("click", function (ev) {
    if (taking() || !swallowing()) return;
    ev.preventDefault();
    ev.stopImmediatePropagation();
    swallowUntil = 0;
  }, ACTIVE);
  window.addEventListener("touchstart", onTouchStart, ACTIVE);
  window.addEventListener("touchmove", onTouchMove, { capture: true, passive: true });
  window.addEventListener("touchend", onTouchEnd, ACTIVE);
  window.addEventListener("touchcancel", function () { touch = null; }, true);
  var PRESS = ["pointerdown", "pointerup", "mousedown", "mouseup"];
  for (var e = 0; e < PRESS.length; e++) window.addEventListener(PRESS[e], onPress, ACTIVE);
  // auxclick: a stylus button or long press on some keyboards; dblclick: a second quick tap
  window.addEventListener("click", onClick, ACTIVE);
  window.addEventListener("auxclick", onClick, ACTIVE);
  window.addEventListener("dblclick", function (ev) { if (taking()) { ev.preventDefault(); ev.stopImmediatePropagation(); } }, ACTIVE);
  window.addEventListener("submit", function (ev) { if (taking()) ev.preventDefault(); }, true);
  window.addEventListener("focusin", function (ev) {
    // A control that still got focus (a label's input) is let go, so no on-screen keyboard or menu opens
    if (!taking()) return;
    var t = targetOf(ev);
    if (t && interactive(t) && typeof t.blur === "function") { try { t.blur(); } catch (e2) { /* nothing */ } }
  }, true);
  // A link opened through a JS handler we didn't catch (window.open from a window-level listener): kept inside
  window.open = (function (original) {
    return function (url) {
      if (taking()) return null;
      return original ? original.apply(window, arguments) : null;
    };
  })(window.open);

  window.__labPicker = {
    // r102: learn.js logs through this when the LabPicker bridge isn't visible (the token-carrying console line)
    log: function (text) { send("log", String(text)); },
    mode: mode,
    armed: armed,
    // Old callers (r96–r101): "pick" = every tap picks and nothing disarms; "browse" = nothing armed, marks cleared
    setMode: function (m) {
      setArmed(null);
      mode = m === "browse" ? "browse" : "pick";
      sticky = true;
      window.__labPicker.mode = mode;
      applyModeClass(mode);
      if (mode === "browse") { clearMarks(); current = null; }
      send("log", "mode " + mode + " on " + location.href);
      return mode;
    },
    // r102: "Tapping for…": [label] = the next tap picks for it (Tags: every tap until disarmed); null = nothing
    // armed (a normal browser; a long press picks). The pick's outlines stay when disarming.
    arm: function (label) {
      var was = armed;
      setArmed(label || null);
      if (was === armed) return armed || "none"; // the same again (re-injection, the app's echo): nothing to redo
      // r103: no badge (the app shows "Tapping for: X · Stop" over the page)
      if (armed) clearSuggestion();
      if (armed || was) showBadge(null);
      send("log", "armed " + (armed || "nothing") + " on " + location.href);
      return armed || "none";
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
      if (!current) { send("log", "up: nothing picked to move from"); return "none"; }
      var p = current.parentElement;
      if (!p || /^(body|html)$/i.test(p.tagName)) { send("log", "up: already at the top"); return "top"; }
      pickElement(p, "up");
      return pathOf(p);
    },
    down: function () {
      if (!current) { send("log", "down: nothing picked to move from"); return "none"; }
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
      // that is the pick itself (r102: unless the pick was an ad frame and this is the real player)
      if (el && current && likeSelector(pathOf(el)) === likeSelector(pathOf(current)) && (el === current || !adHost(current))) el = null;
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
    // r101: the app's own suggestion for [label] (default "item"): the elements of [css] (the Item derived from the
    // Title and Cover picks), outlined dashed green; the one nearest the last tap is sent like suggest() does
    suggestSelector: function (css, label) {
      label = String(label || "item");
      clearSuggestion();
      var els = queryAll(String(css || "")).filter(function (x) { return x.id !== "__lab-badge"; });
      if (!els.length) {
        send("log", "suggest " + label + ": nothing matches " + css);
        send("pick", JSON.stringify({ via: "suggest", forLabel: label, none: true, pageUrl: location.href }));
        return "none";
      }
      var shownEls = els.filter(shown);
      var pool = shownEls.length ? shownEls : els;
      pool.sort(function (a, b) { return distanceFromTap(a) - distanceFromTap(b); });
      var el = pool[0];
      suggested = el;
      for (var i = 0; i < els.length; i++) paintSuggestion(els[i]);
      var d = describe(el, "suggest");
      d.forLabel = label;
      d.fromApp = String(css);
      showBadge("Suggested " + label + " ×" + els.length + " (dashed green) — see the helper");
      send("pick", JSON.stringify(d));
      return d.likeSelector;
    },
    // r101: the site's search box (also sent by itself on every page load); null when the page has none
    readForm: function () {
      lastForm = ""; // asked for: always sent
      var found = null;
      try { found = readForm(); } catch (e) { send("log", "search form not read: " + e.message); }
      if (!found) send("log", "search form: none on " + location.href);
      return found ? found.action : "none";
    },
    // r101: on = the next tap (Pick or Browse mode) picks a whole list (see groupPick) and never navigates
    groupMode: function (on) {
      var was = groupOn;
      groupOn = !!on;
      applyModeClass(groupOn ? "pick" : mode);
      if (groupOn) {
        clearMarks();
        showBadge("Tap one entry of the list: a genre, a sort option, a type…");
      } else if (was) {
        showBadge(null); // cancelled before a tap (after a group pick the badge says what was picked)
      }
      send("log", "group mode " + (groupOn ? "on" : "off"));
      return groupOn;
    },
    // r109: { item } = the next tap names one card's picture (see picturePick) and never navigates; null = off
    pictureMode: function (req) {
      if (!req) {
        var was = !!pictureOn;
        pictureOn = null;
        applyModeClass(groupOn || menuOn ? "pick" : mode);
        if (was) showBadge(null);
        send("log", "picture mode off");
        return false;
      }
      pictureOn = { item: String(req.item || "") };
      groupOn = false;
      menuOn = false;
      applyModeClass("pick");
      clearMarks();
      showBadge("Tap one card's picture (a face, a logo)");
      send("log", "picture mode on (" + pictureOn.item + ")");
      return true;
    },
    // r103: on = the next tap picks a menu group (see menuPick) and never navigates
    menuMode: function (on) {
      var was = menuOn;
      menuOn = !!on;
      if (menuOn) groupOn = false;
      applyModeClass(menuOn || groupOn ? "pick" : mode);
      if (menuOn) {
        clearMarks();
        showBadge("Tap a menu's heading, a dropdown's button, or one entry of a list");
      } else if (was) {
        showBadge(null);
      }
      send("log", "menu mode " + (menuOn ? "on" : "off"));
      return menuOn;
    },
    // r104: "Split into rows" of a restored Tags group (a whole-row or a label list selector): the rows of the details
    // block of the first element of [css] (each of a list tried in turn), sent as a pick { via: "split", split, rows }
    rowsFor: function (css, alsoTry) {
      var tries = [String(css || "")].concat(Array.isArray(alsoTry) ? alsoTry.map(String) : []);
      var el = null;
      for (var i = 0; i < tries.length && !el; i++) {
        if (!tries[i]) continue;
        try { el = document.querySelector(tries[i]); } catch (e) { el = null; }
      }
      var rows = [];
      if (el) { try { rows = detailRows(el); } catch (e2) { rows = []; } }
      send("pick", JSON.stringify({ via: "split", split: String(css || ""), rows: rows, pageUrl: location.href }));
      send("log", "split " + String(css || "").slice(0, 80) + ": " + rows.length + " rows" + (el ? "" : " (nothing matches on this page)"));
      return rows.length;
    },
    // r103: dashed green on these entries only (one menu group's, by their exact paths)
    outlineItems: function (paths) {
      clearMarks();
      var n = 0;
      (paths || []).forEach(function (p) {
        queryAll(String(p || "")).forEach(function (el) { paintSuggestion(el); n++; });
      });
      return n;
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
    // r106: "Scan it now": presses a section's Load more once (the click reaches the site), waits until the cards
    // of [item] stop growing (10 s at most) and answers { via: "more", id, ok, before, after, requests, samples, reason }
    scanMore: function (req) {
      req = req || {};
      var id = String(req.id || "");
      var item = String(req.item || "");
      function answer(o) {
        o.via = "more";
        o.id = id;
        o.pageUrl = location.href;
        send("pick", JSON.stringify(o));
        send("log", "scan more: " + (o.ok ? o.before + " \u2192 " + o.after + " cards, " + (o.requests || []).length + " requests" : o.reason));
      }
      function cards() {
        var r = jsoupAll(item);
        if (r === null) r = queryAll(item.replace(/:(matchesOwn|matches|containsOwn|contains)\([^()]*\)/g, ""));
        return r;
      }
      var btn = null;
      try { btn = req.button ? document.querySelector(String(req.button)) : null; } catch (e) { btn = null; }
      if (btn && !loadMoreButton(btn)) btn = null;
      if (!btn) {
        var all = queryAll(MORE_CONTROLS);
        for (var i = 0; i < all.length && !btn; i++) {
          var b = loadMoreButton(all[i]);
          if (b && shown(b) && (!req.text || textOf(b).indexOf(String(req.text)) === 0)) btn = b;
        }
      }
      if (!btn) {
        answer({ ok: false, before: 0, after: 0, requests: [], samples: [], reason: "the button isn't on this page (open a title page that has it)" });
        return "none";
      }
      var before = cards();
      try { performance.setResourceTimingBufferSize(2000); performance.clearResourceTimings(); } catch (eP) { /* an old engine */ }
      bypass = true;
      try { btn.click(); } catch (eC) { send("log", "scan more: the press failed: " + eC.message); } finally { bypass = false; }
      var started = Date.now();
      var last = before.length;
      var stableSince = 0;
      function finish(now) {
        var reqs = [];
        try {
          reqs = performance.getEntriesByType("resource").filter(function (e) {
            return /^(xmlhttprequest|fetch|other)$/i.test(String(e.initiatorType || ""));
          }).map(function (e) { return e.name; });
        } catch (eR) { reqs = []; }
        var fresh = now.filter(function (c) { return before.indexOf(c) < 0; });
        var samples = [];
        for (var k = 0; k < fresh.length && samples.length < 6; k++) {
          var holder = fresh[k].querySelector("h1, h2, h3, h4, h5, h6, [class*=title], [class*=name]") || fresh[k];
          var t = textOf(holder).slice(0, 60);
          if (t) samples.push(t);
        }
        var grew = now.length > before.length;
        answer({
          ok: grew, before: before.length, after: now.length, requests: reqs.slice(-20), samples: samples,
          reason: grew ? "" : "no new cards appeared within 10 s",
        });
      }
      function poll() {
        var now = cards();
        if (now.length > before.length) {
          if (now.length !== last) { last = now.length; stableSince = Date.now(); }
          else if (Date.now() - stableSince > 700) { finish(now); return; }
        }
        if (Date.now() - started > 10000) { finish(now); return; }
        setTimeout(poll, 250);
      }
      setTimeout(poll, 300);
      return "pressing";
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
  // r101: the site's search box, once per page load
  try { readForm(); } catch (eForm) { send("log", "search form not read: " + eForm.message); }
})();
